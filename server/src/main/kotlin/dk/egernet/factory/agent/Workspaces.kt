package dk.egernet.factory.agent

import dk.egernet.factory.Config
import dk.egernet.factory.AppJson
import dk.egernet.factory.git.CheckoutState
import dk.egernet.factory.git.GitException
import dk.egernet.factory.git.GitOps
import dk.egernet.factory.github.GitHub
import dk.egernet.factory.workspace.WorkspaceDefinitions
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

val REPO_NAME_RE = Regex("^[A-Za-z0-9._-]{1,100}$")

fun requireSafeSegment(s: String, what: String): String {
    require(REPO_NAME_RE.matches(s) && s != "." && s != "..") { "Invalid $what" }
    return s
}

/** A workspace member to check out: its validated [directory], [repository] and effective [baseBranch]. */
data class MemberCheckout(val directory: String, val repository: String, val baseBranch: String)

/** A member left out of a checkout, and why (for the agents and the logs). */
data class LeftOut(val directory: String, val repository: String, val reason: String)

/** What [Workspaces.ensureWorkspace] cloned (or found) and what it left out. */
data class WorkspaceCheckout(val checkedOut: List<MemberCheckout>, val leftOut: List<LeftOut>)

/**
 * A workspace run's checkout ([Workspaces.prepareRunCheckout]): [root] is the agents' working directory, [worktree]
 * the target member's worktree on the run branch, created from [baseRef]. [members]: every member checked out for
 * the run (the target first); [submoduleErrors]: (directory or repository, git's output) per failed submodule update.
 */
data class RunCheckout(
    val root: Path, val worktree: Path, val baseRef: String, val members: List<MemberCheckout>, val leftOut: List<LeftOut>,
    val submoduleErrors: List<Pair<String, String>>,
)

/** One entry of `members.json`: a member directory Agern has cloned into the workspace clone. */
@Serializable
data class ClonedMember(val directory: String, val repository: String)

/**
 * Layout under FACTORY_DATA_DIR: homes/<user>, workspaces/<user>/<repo>/{main,runs/<runId>}. A workspace project adds
 * `main/<member directory>` (member clones), `members.json` and `runs/<runId>/<member directory>` (member worktrees);
 * docs/ARCHITECTURE.md §5 "Workspace projects".
 */
class Workspaces(private val cfg: Config, val git: GitOps, private val github: GitHub) {
    private val log = LoggerFactory.getLogger(Workspaces::class.java)
    private val locks = ConcurrentHashMap<String, Any>()
    /** When each clone (`<user>/<repo>`) was last fetched by [ensureMain] or [syncMain], in epoch milliseconds. */
    private val lastFetch = ConcurrentHashMap<String, Long>()

    /** [syncMain] fetches only when the clone was not fetched within this interval (tests set 0). */
    @Volatile var mainFetchMinIntervalMs = 30_000L

    fun home(username: String): Path = cfg.dataDir.resolve("homes").resolve(requireSafeSegment(username, "username"))
    fun repoDir(username: String, repo: String): Path =
        cfg.dataDir.resolve("workspaces").resolve(requireSafeSegment(username, "username")).resolve(requireSafeSegment(repo, "repository"))
    fun mainDir(username: String, repo: String): Path = repoDir(username, repo).resolve("main")
    fun runDir(username: String, repo: String, runId: UUID): Path = repoDir(username, repo).resolve("runs").resolve(runId.toString())

    /** The owner's clone used by chat (and as the base of run worktrees). Blocking. */
    fun ensureMain(id: AgentIdentity, repo: String): Path {
        val dir = mainDir(id.username, repo)
        synchronized(locks.computeIfAbsent("${id.username}/$repo") { Any() }) {
            Files.createDirectories(home(id.username))
            git.ensureClone(id, github.cloneUrl(cfg.githubOrg, repo), dir, home(id.username))
            lastFetch["${id.username}/$repo"] = System.currentTimeMillis()
        }
        return dir
    }

    /**
     * [GitOps.syncCheckout] of the owner's main clone of [repo] under the clone's lock: fast-forwards it to
     * `origin/<defaultBranch>` when safe. Fetches unless the clone was fetched within [mainFetchMinIntervalMs]. Blocking.
     */
    fun syncMain(id: AgentIdentity, repo: String, defaultBranch: String): CheckoutState? = withRepoLock(id.username, repo) {
        val key = "${id.username}/$repo"
        val now = System.currentTimeMillis()
        val fetch = now - (lastFetch[key] ?: 0L) >= mainFetchMinIntervalMs
        if (fetch) lastFetch[key] = now
        git.syncCheckout(id, mainDir(id.username, repo), defaultBranch, home(id.username), fetch)
    }

    // ------------------------------------------------------------ workspace members

    /**
     * [base]/[directory] for a member directory, which must be valid ([WorkspaceDefinitions.validDirectory]) and stay
     * inside [base] after normalisation.
     */
    fun memberPath(base: Path, directory: String): Path {
        require(WorkspaceDefinitions.validDirectory(directory)) { "Invalid member directory" }
        val b = base.normalize()
        val p = b.resolve(directory).normalize()
        require(p.startsWith(b) && p != b) { "Invalid member directory" }
        return p
    }

    /** No existing path component from [base] (exclusive) to [target] is a symbolic link the repository could have planted. */
    private fun noSymlinks(base: Path, target: Path): Boolean {
        var p: Path? = target
        while (p != null && p != base && p.startsWith(base)) {
            if (Files.isSymbolicLink(p)) return false
            p = p.parent
        }
        return true
    }

    private fun membersFile(username: String, repo: String): Path = repoDir(username, repo).resolve("members.json")
    private val membersSer = ListSerializer(ClonedMember.serializer())

    /** The member directories Agern has cloned for [username]'s workspace [repo], re-validated (invalid entries are dropped). */
    fun clonedMembers(username: String, repo: String): List<ClonedMember> {
        val f = membersFile(username, repo)
        if (!Files.isRegularFile(f)) return emptyList()
        return runCatching { AppJson.decodeFromString(membersSer, Files.readString(f)) }
            .onFailure { log.warn("Ignoring unreadable {}: {}", f, it.message) }.getOrDefault(emptyList())
            .filter { WorkspaceDefinitions.validDirectory(it.directory) && REPO_NAME_RE.matches(it.repository) && it.repository !in setOf(".", "..") }
    }

    private fun recordMember(username: String, repo: String, m: ClonedMember) {
        val current = clonedMembers(username, repo)
        if (m in current) return
        val list = current.filter { it.directory != m.directory } + m
        Files.writeString(membersFile(username, repo), AppJson.encodeToString(membersSer, list))
    }

    /** Adds `/<directory>/` to the workspace clone's `info/exclude` (once), so git never reports a member as a local change. */
    private fun exclude(main: Path, directory: String) {
        val gitDir = main.resolve(".git")
        if (!Files.isDirectory(gitDir)) return
        val f = gitDir.resolve("info").resolve("exclude")
        Files.createDirectories(f.parent)
        val line = "/$directory/"
        val existing = if (Files.exists(f)) Files.readAllLines(f) else emptyList()
        if (line in existing.map { it.trim() }) return
        val text = (if (existing.isEmpty() || Files.readString(f).endsWith("\n")) "" else "\n") + line + "\n"
        Files.writeString(f, text, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    }

    /** The clone of member [m] in [username]'s workspace [repo] when Agern has cloned it there, else null. */
    private fun memberClone(username: String, repo: String, m: MemberCheckout): Path? {
        val dir = memberPath(mainDir(username, repo), m.directory)
        return dir.takeIf { ClonedMember(m.directory, m.repository) in clonedMembers(username, repo) && Files.isDirectory(it) }
    }

    /**
     * Ensures a clone of every member in [members] (those the owner's token can read) at `main/<directory>` of the
     * workspace clone of [repo] ([ensureMain] first), on its base branch when freshly cloned; existing clones are left
     * as they are (syncs and run worktrees fetch). A member whose directory is occupied (exists and is not Agern's
     * clone of that repository) or whose clone fails is left out. Blocking; under the clone's lock.
     */
    fun ensureWorkspace(id: AgentIdentity, repo: String, members: List<MemberCheckout>): WorkspaceCheckout {
        val main = mainDir(id.username, repo)
        return withRepoLock(id.username, repo) {
            val home = home(id.username)
            val ok = mutableListOf<MemberCheckout>()
            val left = mutableListOf<LeftOut>()
            for (m in members) {
                val dir = memberPath(main, m.directory)
                val ours = memberClone(id.username, repo, m) != null
                when {
                    !noSymlinks(main, dir) ->
                        left += LeftOut(m.directory, m.repository, "a symbolic link in the workspace repository is in the way")
                    ours -> {
                        exclude(main, m.directory)
                        ok += m
                    }
                    Files.exists(dir) ->
                        left += LeftOut(m.directory, m.repository, "the directory is occupied: it exists in the workspace and is not Agern's clone of ${m.repository}")
                    else -> try {
                        exclude(main, m.directory)
                        recordMember(id.username, repo, ClonedMember(m.directory, m.repository))
                        git.ensureClone(id, github.cloneUrl(cfg.githubOrg, m.repository), dir, home, m.baseBranch)
                        ok += m
                    } catch (e: Exception) {
                        log.warn("Cloning member {} ({}) of {} failed: {}", m.directory, m.repository, repo, e.message)
                        left += LeftOut(m.directory, m.repository, "it could not be cloned: ${e.message?.take(300)}")
                    }
                }
            }
            left.forEach { log.warn("Workspace {} of {}: member {} ({}) left out: {}", repo, id.username, it.directory, it.repository, it.reason) }
            WorkspaceCheckout(ok, left)
        }
    }

    /**
     * [GitOps.syncCheckout] of each member clone in [members] that Agern has cloned, against `origin/<base>`, under the
     * clone's lock. Fetches unless that member was fetched within [mainFetchMinIntervalMs]. Never throws per member.
     */
    fun syncMembers(id: AgentIdentity, repo: String, members: List<MemberCheckout>): List<Pair<MemberCheckout, CheckoutState?>> =
        withRepoLock(id.username, repo) {
            members.mapNotNull { m ->
                val dir = memberClone(id.username, repo, m) ?: return@mapNotNull null
                val key = "${id.username}/$repo/${m.directory}"
                val now = System.currentTimeMillis()
                val fetch = now - (lastFetch[key] ?: 0L) >= mainFetchMinIntervalMs
                if (fetch) lastFetch[key] = now
                m to runCatching { git.syncCheckout(id, dir, m.baseBranch, home(id.username), fetch) }
                    .onFailure { log.warn("Syncing member {} of {} failed: {}", m.directory, repo, it.message) }.getOrNull()
            }
        }

    /**
     * Creates the checkout of workspace run [runId] (docs/ARCHITECTURE.md §5 "Workspace projects"): `runs/<runId>/` as
     * a detached worktree of the workspace clone at [defaultBranch], `runs/<runId>/<target>` on [branch] from the
     * target's base branch (or [startFrom], see [GitOps.createWorktree]), and a detached worktree at its base branch
     * for every member of [others] Agern has cloned. Submodules are fetched in each; failures are returned. Throws
     * [GitException] when the root or the target cannot be created. Blocking; under the clone's lock.
     */
    fun prepareRunCheckout(
        id: AgentIdentity, repo: String, runId: UUID, defaultBranch: String, target: MemberCheckout, branch: String,
        startFrom: String?, others: List<MemberCheckout>,
    ): RunCheckout = withRepoLock(id.username, repo) {
        val main = mainDir(id.username, repo)
        val root = runDir(id.username, repo, runId)
        val home = home(id.username)
        val subs = mutableListOf<Pair<String, String>>()
        git.addDetachedWorktree(id, main, root, defaultBranch, home)
        git.updateSubmodules(id, root, home)?.let { subs += repo to it }
        val tClone = memberClone(id.username, repo, target)
            ?: throw GitException("Member `${target.directory}` (${target.repository}) is not checked out in the workspace")
        val tWt = memberPath(root, target.directory)
        if (Files.exists(tWt) || !noSymlinks(root, tWt)) {
            throw GitException("The directory `${target.directory}` is occupied in the workspace repository")
        }
        val baseRef = git.createWorktree(id, tClone, tWt, branch, target.baseBranch, home, startFrom)
        git.updateSubmodules(id, tWt, home)?.let { subs += target.directory to it }
        val members = mutableListOf(target)
        val left = mutableListOf<LeftOut>()
        for (o in others) {
            try {
                val clone = memberClone(id.username, repo, o) ?: throw GitException("it is not checked out in the workspace")
                val wt = memberPath(root, o.directory)
                if (Files.exists(wt) || !noSymlinks(root, wt)) throw GitException("the directory is occupied in the workspace repository")
                git.addDetachedWorktree(id, clone, wt, o.baseBranch, home)
                git.updateSubmodules(id, wt, home)?.let { subs += o.directory to it }
                members += o
            } catch (e: Exception) {
                log.warn("Run {}: member {} left out: {}", runId, o.directory, e.message)
                left += LeftOut(o.directory, o.repository, e.message?.take(300) ?: "it could not be checked out")
            }
        }
        RunCheckout(root, tWt, baseRef, members, left, subs)
    }

    /**
     * Removes run [runId]'s checkout from [username]'s clone of [repo]: every member worktree (found through
     * `members.json`) from its member clone, then the root worktree and the directory ([GitOps.removeWorktree] rules,
     * [keepUnpushed] for the local branches). Blocking; under the clone's lock.
     */
    fun removeRunCheckout(username: String, repo: String, runId: UUID, keepUnpushed: Boolean) = withRepoLock(username, repo) {
        val main = mainDir(username, repo)
        val root = runDir(username, repo, runId)
        val home = home(username)
        for (m in clonedMembers(username, repo)) {
            val clone = runCatching { memberPath(main, m.directory) }.getOrNull() ?: continue
            if (!Files.isDirectory(clone)) continue
            val wt = memberPath(root, m.directory)
            // A symbolic link of the workspace repository at a member path was never a worktree of ours: never follow it
            // (removing the root worktree removes the link itself).
            if (!noSymlinks(root, wt)) continue
            git.removeWorktree(clone, wt, home, keepUnpushed)
        }
        git.removeWorktree(main, root, home, keepUnpushed)
    }

    fun <T> withRepoLock(username: String, repo: String, block: () -> T): T =
        synchronized(locks.computeIfAbsent("$username/$repo") { Any() }) { block() }

    /**
     * Removes every run worktree of [username]'s clone of [repo] whose run is not [live] — registered with git or
     * only left on disk — and prunes stale worktree entries (docs/ARCHITECTURE.md §6 "Worktrees and branches").
     * Local branches holding commits that exist nowhere else are kept. Blocking; returns the run ids removed.
     */
    fun reapRunWorktrees(username: String, repo: String, live: (UUID) -> Boolean): List<UUID> = withRepoLock(username, repo) {
        val main = mainDir(username, repo)
        val home = home(username)
        val removed = mutableListOf<UUID>()
        val registered = runCatching { git.listWorktrees(main, home) }.getOrDefault(emptyList())
            .mapNotNull { wt -> runIdOf(wt.path)?.let { it to wt } } +
            // Worktrees of runs registered in member clones (`runs/<runId>/<member directory>`).
            clonedMembers(username, repo).flatMap { m ->
                val clone = runCatching { memberPath(main, m.directory) }.getOrNull()?.takeIf { Files.isDirectory(it) } ?: return@flatMap emptyList()
                runCatching { git.listWorktrees(clone, home) }.getOrDefault(emptyList())
                    .mapNotNull { wt -> runIdIn(wt.path)?.let { it to wt } }
            }
        val onDisk = runCatching {
            Files.list(repoDir(username, repo).resolve("runs")).use { s -> s.toList().mapNotNull { p -> runIdOf(p) } }
        }.getOrDefault(emptyList())
        for (id in (registered.map { it.first } + onDisk).distinct()) {
            if (live(id)) continue
            removeRunCheckout(username, repo, id, keepUnpushed = true)
            removed += id
        }
        removed
    }

    /** [GitOps.forgetBranch] in [username]'s clone of [repo], under the clone's lock. Blocking. */
    fun forgetBranch(username: String, repo: String, branch: String) = withRepoLock(username, repo) {
        git.forgetBranch(mainDir(username, repo), branch, home(username))
    }

    /** [reapRunWorktrees] for every user's clone of every repository on disk (at server start). */
    fun reapAllRunWorktrees(live: (UUID) -> Boolean): List<UUID> {
        val root = cfg.dataDir.resolve("workspaces")
        if (!Files.isDirectory(root)) return emptyList()
        val removed = mutableListOf<UUID>()
        for (userDir in Files.list(root).use { it.toList() }) {
            val user = userDir.fileName.toString()
            if (!Files.isDirectory(userDir) || runCatching { requireSafeSegment(user, "username") }.isFailure) continue
            for (repoDir in Files.list(userDir).use { it.toList() }) {
                val repo = repoDir.fileName.toString()
                if (!Files.isDirectory(repoDir) || runCatching { requireSafeSegment(repo, "repository") }.isFailure) continue
                removed += reapRunWorktrees(user, repo, live)
            }
        }
        return removed
    }

    /** The run id of a member worktree path `…/runs/<runId>/<directory…>` (the last such segments), else null. */
    private fun runIdIn(p: Path): UUID? {
        val names = (0 until p.nameCount).map { p.getName(it).toString() }
        for (k in names.indices.reversed()) {
            if (names[k] != "runs" || k + 1 >= names.size) continue
            val id = runCatching { UUID.fromString(names[k + 1]) }.getOrNull()?.takeIf { it.toString() == names[k + 1] } ?: continue
            if (k + 2 < names.size) return id
        }
        return null
    }

    /** The run id of a worktree path `…/runs/<runId>`, else null. */
    private fun runIdOf(p: Path): UUID? =
        if (p.parent?.fileName?.toString() != "runs") null
        else runCatching { UUID.fromString(p.fileName.toString()) }.getOrNull()?.takeIf { it.toString() == p.fileName.toString() }
}
