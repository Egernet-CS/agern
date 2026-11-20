package dk.egernet.factory.agent

import dk.egernet.factory.Config
import dk.egernet.factory.git.GitOps
import dk.egernet.factory.github.GitHub
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

val REPO_NAME_RE = Regex("^[A-Za-z0-9._-]{1,100}$")

fun requireSafeSegment(s: String, what: String): String {
    require(REPO_NAME_RE.matches(s) && s != "." && s != "..") { "Invalid $what" }
    return s
}

/** Layout under FACTORY_DATA_DIR: homes/<user>, workspaces/<user>/<repo>/{main,runs/<runId>}. */
class Workspaces(private val cfg: Config, val git: GitOps, private val github: GitHub) {
    private val locks = ConcurrentHashMap<String, Any>()

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
        }
        return dir
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
            .mapNotNull { wt -> runIdOf(wt.path)?.let { it to wt } }
        val onDisk = runCatching {
            Files.list(repoDir(username, repo).resolve("runs")).use { s -> s.toList().mapNotNull { p -> runIdOf(p) } }
        }.getOrDefault(emptyList())
        for (id in (registered.map { it.first } + onDisk).distinct()) {
            if (live(id)) continue
            git.removeWorktree(main, runDir(username, repo, id), home)
            removed += id
        }
        removed
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

    /** The run id of a worktree path `…/runs/<runId>`, else null. */
    private fun runIdOf(p: Path): UUID? =
        if (p.parent?.fileName?.toString() != "runs") null
        else runCatching { UUID.fromString(p.fileName.toString()) }.getOrNull()?.takeIf { it.toString() == p.fileName.toString() }
}
