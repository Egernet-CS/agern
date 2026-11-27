package dk.egernet.factory.git

import dk.egernet.factory.agent.AgentCommand
import dk.egernet.factory.agent.AgentIdentity
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

open class GitException(message: String) : RuntimeException(message)

/**
 * [GitOps.pushFastForward] was refused because the branch is not an ancestor of what was pushed (it moved meanwhile).
 * [output] is git's output.
 */
class PushRejectedException(val output: String) : GitException("push rejected: $output")

/**
 * The state of a worktree after a merge ([GitOps.mergeState]): its [head], whether it contains the start commit and the
 * merged ref, and whether the merge is [unfinished] (a merge in progress, unmerged paths or uncommitted changes to
 * tracked files).
 */
data class MergeCheck(val head: String, val containsStart: Boolean, val containsMerged: Boolean, val unfinished: Boolean)

/** [BaseMerge.status] values (the same words as `run_updates.merge_status`). */
const val UPDATE_UP_TO_DATE = "up_to_date"
const val UPDATE_MERGED = "merged"
const val UPDATE_CONFLICT = "conflict"

/** One entry of `git worktree list`: its path as git reports it and its branch (null when detached). */
data class WorktreeInfo(val path: Path, val branch: String?)

/** One file [headBranch] changes relative to its merge base with a base branch: [status] `A`, `M` or `D`. */
data class FileChange(val status: Char, val path: String)

/**
 * [GitOps.compareBranches]: the files on the base branch's tip under the directories of interest, and what the head
 * changes (relative to the merge base) in them. Renames are a removal plus an addition. [behind]: the commits on the
 * base that are not on the head.
 */
data class BranchComparison(val baseFiles: List<String>, val changes: List<FileChange>, val behind: Int = 0)

/**
 * A submodule pointer (gitlink) a branch adds or moves ([GitOps.changedGitlinks]): the submodule at [path] now points
 * at commit [sha]. [url] is the submodule's URL from `.gitmodules` on the head, relative URLs resolved against the
 * parent's `origin`; null when `.gitmodules` names none for [path].
 */
data class GitlinkChange(val path: String, val sha: String, val url: String?)

/** A submodule of a checkout ([GitOps.unpushedSubmodules]) at [path] whose HEAD or local branches hold [commits] commits found on none of its remote-tracking branches. */
data class UnpushedSubmodule(val path: String, val head: String, val commits: Int)

/**
 * The state of a main checkout relative to `origin/<defaultBranch>` after [GitOps.syncCheckout]
 * (docs/ARCHITECTURE.md §5 "Workspaces"); [defaultBranch] is the branch the checkout follows (a project's base branch).
 * [branch] is null for a detached HEAD; [head] is the full HEAD sha after the sync. [status] is `current`, `updated`
 * (fast-forwarded or switched from [updatedFrom]) or `behind` (not updated because of [reason]: `other_branch`,
 * `local_changes`, `local_commits` or `update_failed`). [newCommits]: how many commits a fast-forward brought in (the
 * `behind` before it); 0 otherwise. [switchedFrom]: the branch Agern switched the checkout from to [defaultBranch].
 */
data class CheckoutState(
    val branch: String?, val defaultBranch: String, val head: String, val behind: Int, val ahead: Int,
    val status: String, val reason: String?, val updatedFrom: String?, val newCommits: Int = 0,
    val switchedFrom: String? = null,
)

/**
 * [GitOps.mergeBase]: what merging the target branch into a run's worktree did. [status] is `up_to_date` (the branch
 * already contains [targetSha]), `merged` (a clean merge, committed) or `conflict` (the worktree is left mid-merge
 * with [conflicts]). [headBefore]: HEAD before; [head]: HEAD after (the merge commit, or [headBefore] when nothing was
 * committed). [incomingCount]: commits of the target the branch did not have; [incoming]: the subjects of the first-parent
 * ones, newest first (at most 50).
 */
data class BaseMerge(
    val status: String, val targetSha: String, val headBefore: String, val head: String, val incomingCount: Int,
    val incoming: List<String>, val conflicts: List<String>,
)

/**
 * [GitOps.mergeState]: whether a merge is still in progress ([merging]), the paths still unmerged, whether tracked files
 * have uncommitted changes ([dirty]), whether the target commit is contained in HEAD, and HEAD.
 */
data class MergeState(val merging: Boolean, val unmerged: List<String>, val dirty: Boolean, val containsTarget: Boolean, val head: String)

/** Git operations the server performs itself (the agents run git on their own). */
interface GitOps {
    /**
     * Clones the repo into [dir] or fetches if it is already there. A fresh clone checks out [branch] (null: the remote's
     * default branch) and fetches its submodules ([updateSubmodules]; a failure is only logged).
     */
    fun ensureClone(id: AgentIdentity, cloneUrl: String, dir: Path, home: Path, branch: String? = null)
    /**
     * Adds a detached worktree at [worktree] of the clone [mainDir] at the up-to-date [baseBranch] (a workspace run's
     * root and context members). Returns the base ref used. Throws [GitException] when the branch does not exist.
     */
    fun addDetachedWorktree(id: AgentIdentity, mainDir: Path, worktree: Path, baseBranch: String, home: Path): String {
        Files.createDirectories(worktree)
        return baseRef(baseBranch)
    }
    /**
     * `git submodule update --init --recursive` in the checkout [dir] when it has a `.gitmodules` file, with [id]'s git
     * authentication: only the submodules at the paths [only] when it is not empty, and never those at the paths
     * [skip] (left uninitialised: a workspace run's submodule target). Null when there is nothing to do or it worked,
     * else git's output. Never throws.
     */
    fun updateSubmodules(id: AgentIdentity?, dir: Path, home: Path, only: List<String> = emptyList(), skip: List<String> = emptyList()): String? = null
    /**
     * Creates a worktree on a new branch from the up-to-date [baseBranch] (the default branch, or `release/<name>`
     * for a release run). Returns the base ref used. With [startFrom] the branch starts at the tip of that branch
     * instead — its local branch, else the remote one (fetched) — while the returned base ref is still [baseBranch]'s:
     * a run that continues another run's commits, or a step retry that re-creates its own worktree ([startFrom] =
     * [branch]). Throws [GitException] when [startFrom] exists neither locally nor on the remote.
     */
    fun createWorktree(
        id: AgentIdentity, mainDir: Path, worktree: Path, branch: String, baseBranch: String, home: Path, startFrom: String? = null,
    ): String
    /**
     * The number of commits on [branch] (local, else remote-tracking after a best-effort fetch with [id]) that are not
     * on [baseBranch]; null when the branch is found neither locally nor on the remote. Never throws.
     */
    fun branchCommits(id: AgentIdentity?, mainDir: Path, branch: String, baseBranch: String, home: Path): Int? = null
    fun commitsAhead(worktree: Path, baseRef: String, home: Path): Int
    /** The commit the worktree has checked out (`git rev-parse HEAD`). */
    fun head(worktree: Path, home: Path): String
    /**
     * Writes `git archive --format=tar.gz <sha>` of [worktree] to [file] (a sandbox VM deploy, docs/SANDBOX-VMS.md):
     * only committed content, no submodule content. [sha] is a full commit id. Throws [GitException] when git fails.
     */
    fun archiveHead(worktree: Path, sha: String, file: Path, home: Path) {
        throw GitException("git archive is not supported here")
    }
    /**
     * Writes the committed tree of [ref] (resolved in the repository of [dir]) into the new, empty directory [dest]
     * (`git archive` extracted with `tar`): only committed content, nothing of a worktree's uncommitted or untracked
     * files, no submodule content. Returns the commit id. Used to prepare a sandbox image from a clean tree
     * (docs/ARCHITECTURE.md §5 "Prepared images"). Throws [GitException] when git or tar fails.
     */
    fun exportTree(dir: Path, ref: String, dest: Path, home: Path): String {
        Files.createDirectories(dest)
        return "0".repeat(40)
    }
    /** True when `git status --porcelain` of [worktree] lists anything (uncommitted or untracked changes). */
    fun isDirty(worktree: Path, home: Path): Boolean = false
    /**
     * Commits every uncommitted change of [worktree] (`git add -A`, so ignored files stay out) as Agern, without hooks,
     * with [message] (docs/ARCHITECTURE.md §6 "Worktrees and branches"). Returns the new HEAD; null when there is nothing
     * to commit or a merge is in progress (`MERGE_HEAD` or unmerged paths). Throws [GitException] when git fails.
     */
    fun commitAll(worktree: Path, message: String, home: Path): String? = null
    /**
     * The paths HEAD of [worktree] changes relative to its merge base with [baseRef] (`git diff --name-only
     * <baseRef>...HEAD`, renames as their new path, deletions included). Null when it cannot be told (the default for
     * implementations that do not support it); throws [GitException] when git fails.
     */
    fun changedFiles(worktree: Path, baseRef: String, home: Path): List<String>? = null
    /** The ref a worktree on [baseBranch] is created from: `origin/<baseBranch>`, or the local branch without a remote. */
    fun baseRef(baseBranch: String): String
    fun push(id: AgentIdentity, worktree: Path, branch: String, home: Path)
    /** The worktrees registered in [mainDir] (the main working tree included). Prunes stale entries first. */
    fun listWorktrees(mainDir: Path, home: Path): List<WorktreeInfo>
    /**
     * Removes [worktree] (registered or not), prunes stale worktree entries and deletes the worktree's local branch,
     * unless [keepUnpushed] is set and the branch holds commits that are on no other local branch and no
     * remote-tracking branch (work that exists nowhere else). Never throws.
     */
    fun removeWorktree(mainDir: Path, worktree: Path, home: Path, keepUnpushed: Boolean = true)
    /**
     * The file at [path] in commit [ref] of the repository at [dir] (raw blob bytes as UTF-8), or null when it does not
     * exist there. Throws [GitException] when it is larger than [maxBytes] or git fails.
     */
    fun readFile(dir: Path, ref: String, path: String, home: Path, maxBytes: Int): String?
    /**
     * Forgets a branch that is gone on GitHub (a released release's `release/<name>`) in the clone at [mainDir]: its
     * remote-tracking ref, and its local branch unless a worktree has it checked out or it holds commits found on no
     * other local branch and no remote-tracking branch. Never throws.
     */
    fun forgetBranch(mainDir: Path, branch: String, home: Path) {}
    /**
     * Fetches [branch] from origin into its remote-tracking ref in the clone at [mainDir], so `origin/<branch>` is
     * current (a member release run reads its sandbox definition there). A failed fetch is logged; never throws.
     */
    fun fetchBranch(id: AgentIdentity?, mainDir: Path, branch: String, home: Path) {}
    /**
     * Compares [headBranch] with [baseBranch] in the clone at [mainDir], both as they are on GitHub (fetched first with
     * [githubToken]): what the head changes relative to their merge base, and the files on the base's tip. Only paths
     * for which [dirOf] names a directory count; the base is listed only under the directories the changes touch, and
     * not at all when no change counts. Null when the comparison cannot be made (no clone, a branch is missing);
     * throws [GitException] when git fails.
     */
    fun compareBranches(
        mainDir: Path, baseBranch: String, headBranch: String, home: Path, githubToken: String?, dirOf: (String) -> String?,
    ): BranchComparison? = null
    /**
     * As [compareBranches], for the `HEAD` of [worktree] against the local ref [baseRef] (e.g. `origin/main`), without
     * fetching. Null when a ref is missing; throws [GitException] when git fails.
     */
    fun compareWorktree(worktree: Path, baseRef: String, home: Path, dirOf: (String) -> String?): BranchComparison? = null
    /**
     * After a merge of [mergedRef] in [worktree] that started at commit [startSha]: see [MergeCheck]. Throws
     * [GitException] when git fails.
     */
    fun mergeState(worktree: Path, startSha: String, mergedRef: String, home: Path): MergeCheck =
        MergeCheck(head(worktree, home), containsStart = true, containsMerged = true, unfinished = false)
    /**
     * Pushes the `HEAD` of [worktree] to [branch] on origin as a fast-forward only: never `--force` or
     * `--force-with-lease` (a sync run, docs/ARCHITECTURE.md §6 "Bringing a release up to date"). In fake-GitHub mode
     * the local branch is moved instead, also only as a fast-forward. Throws [PushRejectedException] when the branch is
     * not an ancestor of `HEAD` (it moved meanwhile), [GitException] for any other failure.
     */
    fun pushFastForward(id: AgentIdentity, worktree: Path, branch: String, home: Path) {
        push(id, worktree, branch, home)
    }
    /**
     * Brings the main checkout at [mainDir] up to date with `origin/<defaultBranch>` when that is safe: fetches that
     * branch first when [fetch] is set (a failed fetch is ignored), then fast-forwards only when the checkout is on
     * [defaultBranch], clean and without local commits. With [switchFrom] (the repository's default branch), a checkout
     * that is still on the branch Agern followed last (git config `agern.followed-branch`, else [switchFrom]), clean and
     * without commits beyond `origin/<that branch>`, is first switched to [defaultBranch] (a project's base branch).
     * Never modifies the checkout otherwise. Null when there is no clone or no `origin/<defaultBranch>`; throws
     * [IllegalArgumentException] for an invalid branch name.
     */
    fun syncCheckout(
        id: AgentIdentity?, mainDir: Path, defaultBranch: String, home: Path, fetch: Boolean, switchFrom: String? = null,
    ): CheckoutState? = null
    /**
     * The submodule pointers [headBranch] adds or moves relative to its merge base with [baseBranch], both as they are
     * on GitHub (fetched first with [githubToken], as in [compareBranches]); removed submodules do not count. Null when
     * the comparison cannot be made (no clone, a branch is missing); throws [GitException] when git fails.
     */
    fun changedGitlinks(mainDir: Path, baseBranch: String, headBranch: String, home: Path, githubToken: String?): List<GitlinkChange>? = emptyList()
    /**
     * Whether the repository at [url] has commit [sha] (reachable from any of its refs): fetched by sha into a
     * throw-away repository, commit object only where the server supports filters, authenticated with [githubToken]
     * for the git host (whose SSH URLs go over its HTTPS base). Throws [GitException] when the answer cannot be had
     * (the remote cannot be reached, git fails otherwise).
     */
    fun remoteHasCommit(url: String, sha: String, home: Path, githubToken: String?): Boolean = true
    /**
     * The initialised submodules of the checkout [dir] (recursively) with commits on their HEAD or local branches that
     * are on none of their remote-tracking branches: work that exists only in this checkout. Never throws.
     */
    fun unpushedSubmodules(dir: Path, home: Path): List<UnpushedSubmodule> = emptyList()
    /**
     * Writes the commits of submodule [sub] of the checkout [dir] that are on none of its remote-tracking branches
     * (HEAD and local branches) to the bundle [file]. Throws [GitException] when git fails.
     */
    fun bundleSubmodule(dir: Path, sub: String, file: Path, home: Path) {}
    /**
     * Merges [baseBranch] as it is on GitHub (fetched first; the local branch in fake-GitHub mode) into the branch checked
     * out in [worktree] with a real merge (`git merge`, never a rebase or reset), committed as [id] when it is clean; a
     * conflict leaves the worktree mid-merge ([BaseMerge]). Throws [GitException] when the worktree has uncommitted
     * changes to tracked files, or when git fails otherwise (a failed merge without conflicts is aborted first).
     */
    fun mergeBase(id: AgentIdentity, worktree: Path, baseBranch: String, home: Path): BaseMerge =
        throw GitException("Merging a base branch is not supported")
    /** The state of [worktree] after a merge of [targetSha] ([MergeState]). Throws [GitException] when git fails. */
    fun mergeState(worktree: Path, targetSha: String, home: Path): MergeState = throw GitException("Not supported")
    /** Aborts a merge in progress in [worktree] and resets it hard to [sha] (a commit it already has). Never throws. */
    fun restore(worktree: Path, sha: String, home: Path) {}
    /** `<short sha> <subject>` of the first-parent commits from [from] (exclusive) to HEAD of [worktree], oldest first, at most [max]. */
    fun commitSubjects(worktree: Path, from: String, home: Path, max: Int): List<String> = emptyList()
    /**
     * Fake-GitHub development mode only (docs/ARCHITECTURE.md §6 "Update and merge", `.agern-fake/land-merges`): applies
     * the merged [branch] to the local [target] branch of the clone at [mainDir] as one squash commit titled [title], so
     * that later runs see their base move. When [target] is checked out in [mainDir], its working tree follows the new
     * commit if it was clean, and nothing happens when it was dirty. True when the target moved; a conflict or any git
     * failure is logged and answered false. A no-op outside that mode.
     */
    fun landSquash(mainDir: Path, target: String, branch: String, title: String, home: Path): Boolean = false
}

/**
 * The URL of a submodule whose `.gitmodules` URL is [url], in a repository whose `origin` is [parentUrl]: a relative
 * URL (`./x`, `../x`) is resolved against the parent's URL as git does (each `../` drops one path segment; for an
 * scp-like `git@host:org/repo.git` the `:` counts as a separator); any other URL is returned as it is.
 */
fun resolveSubmoduleUrl(parentUrl: String, url: String): String {
    if (!url.startsWith("./") && !url.startsWith("../")) return url
    var base = parentUrl.trimEnd('/')
    var rel = url
    var sep = '/'
    while (true) {
        when {
            rel.startsWith("./") -> rel = rel.removePrefix("./")
            rel.startsWith("../") -> {
                rel = rel.removePrefix("../")
                val i = maxOf(base.lastIndexOf('/'), base.lastIndexOf(':'))
                if (i < 0) break
                sep = base[i]
                base = base.substring(0, i)
            }
            else -> break
        }
    }
    return "$base$sep$rel"
}

/**
 * Runs the git CLI with an environment built from scratch (auth via `GIT_CONFIG_*` env only).
 * With [localOnly] (fake GitHub dev mode) a clone is replaced by `git init` and pushes are skipped. Without it, every
 * `clone`, `fetch` and `push` is reported to [health] (docs/ARCHITECTURE.md §6 "GitHub status").
 */
class CliGitOps(
    private val path: String,
    private val gitHost: String,
    private val localOnly: Boolean = false,
    private val health: dk.egernet.factory.github.GitHubHealth? = null,
) : GitOps {
    private val log = LoggerFactory.getLogger(CliGitOps::class.java)

    private companion object {
        /** Git config key in a main clone: the branch Agern made the checkout follow last ([syncCheckout]). */
        const val FOLLOWED_KEY = "agern.followed-branch"
        /** The mode of a gitlink (a submodule pointer) in a tree. */
        const val GITLINK_MODE = "160000"
        val SHA_RE = Regex("[0-9a-f]{40}|[0-9a-f]{64}")
        /** The git commands that talk to the remote and are recorded (docs/ARCHITECTURE.md §6 "GitHub status"). */
        val RECORDED_COMMANDS = setOf("clone", "fetch", "push")
        /** What git and the servers say when a fetched commit does not exist there (as opposed to a remote it cannot reach). */
        val MISSING_COMMIT_RE = Regex("(?i)not our ref|couldn'?t find remote ref|no such remote ref|not a valid object|unadvertised object")
    }

    private fun git(cwd: Path?, home: Path, id: AgentIdentity?, vararg args: String, timeoutSec: Long = 600, token: String? = null): String {
        val (code, out) = gitStatus(cwd, home, id, *args, timeoutSec = timeoutSec, token = token)
        if (code != 0) {
            val cls = dk.egernet.factory.github.GitHubHealth.classifyGit(code, out)
            val hint = if (cls == dk.egernet.factory.github.CallClass.SERVER_ERROR || cls == dk.egernet.factory.github.CallClass.NETWORK) {
                dk.egernet.factory.github.GitHubFailure.GIT_HINT
            } else ""
            throw GitException("git ${args.take(2).joinToString(" ")} failed: ${out.takeLast(1000).trim()}$hint")
        }
        return out
    }

    /** The git command of [args]: the first argument that is no option and not the value of a `-c`. */
    private fun commandOf(args: Array<out String>): String? {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a == "-c" || a == "-C") { i += 2; continue }
            if (!a.startsWith("-")) return a
            i++
        }
        return null
    }

    /** Reports a `clone`/`fetch`/`push` against the remote; [code] null = timed out. Not in [localOnly] mode. */
    private fun record(args: Array<out String>, code: Int?, out: String) {
        if (localOnly || health == null) return
        val cmd = commandOf(args) ?: return
        if (cmd in RECORDED_COMMANDS) health.recordGit(cmd, code, out)
    }

    /**
     * Runs git and returns its exit code and output instead of throwing on a non-zero exit. [token]: authenticate with
     * this GitHub token where there is no [id] (a fetch on the server's own behalf). [recorded]: false for commands that
     * may talk to other hosts than GitHub (a submodule's URL), which are not reported to [health].
     */
    private fun gitStatus(
        cwd: Path?, home: Path, id: AgentIdentity?, vararg args: String, timeoutSec: Long = 600, token: String? = null,
        env: Map<String, String> = emptyMap(), recorded: Boolean = true,
    ): Pair<Int, String> {
        val pb = ProcessBuilder(listOf("git") + args).redirectErrorStream(true)
        if (cwd != null) pb.directory(cwd.toFile())
        pb.environment().clear()
        pb.environment()["PATH"] = path
        pb.environment()["HOME"] = home.toString()
        pb.environment()["LANG"] = "en_US.UTF-8"
        pb.environment()["GIT_TERMINAL_PROMPT"] = "0"
        if (id != null) pb.environment().putAll(AgentCommand.gitEnvironment(id, gitHost))
        else {
            pb.environment()["GIT_AUTHOR_NAME"] = "Agern"; pb.environment()["GIT_COMMITTER_NAME"] = "Agern"
            pb.environment()["GIT_AUTHOR_EMAIL"] = "agern@agern.local"; pb.environment()["GIT_COMMITTER_EMAIL"] = "agern@agern.local"
            pb.environment().putAll(AgentCommand.gitAuthEnvironment(token, gitHost))
        }
        pb.environment().putAll(env)
        Files.createDirectories(home)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            if (recorded) record(args, null, out)
            throw GitException("git ${args.firstOrNull()} timed out" + dk.egernet.factory.github.GitHubFailure.GIT_HINT)
        }
        if (recorded) record(args, p.exitValue(), out)
        return p.exitValue() to out
    }

    override fun ensureClone(id: AgentIdentity, cloneUrl: String, dir: Path, home: Path, branch: String?) {
        if (Files.exists(dir.resolve(".git"))) {
            if (!localOnly) runCatching { git(dir, home, id, "fetch", "--prune", "origin") }
                .onFailure { log.warn("git fetch failed in {}: {}", dir, it.message) }
            return
        }
        require(branch == null || (branch.isNotBlank() && !branch.startsWith("-"))) { "Invalid branch name" }
        Files.createDirectories(dir.parent)
        if (localOnly) {
            Files.createDirectories(dir)
            git(dir, home, id, "init", "-q", "-b", branch ?: "main")
            Files.writeString(dir.resolve("README.md"), "# ${dir.parent.fileName}\n\nLocal fake repository (FACTORY_DEV_FAKE_GITHUB).\n")
            git(dir, home, id, "add", "README.md")
            git(dir, home, id, "commit", "-q", "-m", "Initial commit")
        } else {
            val args = listOf("clone", "-q") + (if (branch != null) listOf("--branch", branch) else emptyList()) +
                listOf("--", cloneUrl, dir.fileName.toString())
            git(dir.parent, home, id, *args.toTypedArray())
            updateSubmodules(id, dir, home)?.let { log.warn("Submodules of {} could not be fetched: {}", dir, it) }
        }
    }

    override fun addDetachedWorktree(id: AgentIdentity, mainDir: Path, worktree: Path, baseBranch: String, home: Path): String {
        require(baseBranch.isNotBlank() && !baseBranch.startsWith("-")) { "Invalid branch name" }
        if (localOnly) {
            if (runCatching { git(mainDir, home, id, "rev-parse", "--verify", "--quiet", "refs/heads/$baseBranch") }.isFailure) {
                git(mainDir, home, id, "branch", baseBranch, "HEAD")
            }
        } else {
            git(mainDir, home, id, "fetch", "origin", "+refs/heads/$baseBranch:refs/remotes/origin/$baseBranch")
        }
        val base = baseRef(baseBranch)
        Files.createDirectories(worktree.parent)
        git(mainDir, home, id, "worktree", "add", "--detach", worktree.toString(), base)
        return base
    }

    override fun updateSubmodules(id: AgentIdentity?, dir: Path, home: Path, only: List<String>, skip: List<String>): String? {
        // Fake GitHub: local repositories have no remotes to fetch submodules from.
        if (localOnly || !Files.isRegularFile(dir.resolve(".gitmodules"))) return null
        return try {
            require((only + skip).none { it.isBlank() || it.startsWith("-") || it.startsWith(":") }) { "Invalid submodule path" }
            // A skipped submodule is turned off for this command only (`submodule.<name>.update=none`).
            val off = if (skip.isEmpty()) emptyList() else submoduleNames(dir, home).filter { (_, path) -> path in skip }
                .flatMap { (name, _) -> listOf("-c", "submodule.$name.update=none") }
            val args = off + listOf("submodule", "update", "--init", "--recursive") + (if (only.isEmpty()) emptyList() else listOf("--") + only)
            val (code, out) = gitStatus(dir, home, id, *args.toTypedArray())
            if (code == 0) null else out.trim().ifEmpty { "git submodule update failed with exit code $code" }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /** (name, path) of every submodule `.gitmodules` in [dir] declares. */
    private fun submoduleNames(dir: Path, home: Path): List<Pair<String, String>> {
        val (code, out) = gitStatus(dir, home, null, "config", "-f", ".gitmodules", "-z", "--get-regexp", "^submodule\\..*\\.path$", timeoutSec = 60)
        if (code != 0) return emptyList()
        // -z: "<key>\n<value>\u0000" per entry.
        return out.split('\u0000').filter { it.isNotEmpty() }.mapNotNull { e ->
            val key = e.substringBefore('\n')
            val value = e.substringAfter('\n', "").takeIf { '\n' in e } ?: return@mapNotNull null
            key.removePrefix("submodule.").removeSuffix(".path") to value
        }
    }

    override fun createWorktree(
        id: AgentIdentity, mainDir: Path, worktree: Path, branch: String, baseBranch: String, home: Path, startFrom: String?,
    ): String {
        val base = if (localOnly) {
            // Fake GitHub: a release branch exists only on the fake; create it locally from the clone's HEAD.
            if (runCatching { git(mainDir, home, id, "rev-parse", "--verify", "--quiet", "refs/heads/$baseBranch") }.isFailure) {
                git(mainDir, home, id, "branch", baseBranch, "HEAD")
            }
            baseRef(baseBranch)
        } else {
            git(mainDir, home, id, "fetch", "origin", baseBranch)
            baseRef(baseBranch)
        }
        val start = if (startFrom == null) base else branchRef(id, mainDir, startFrom, home)
            ?: throw GitException("Branch $startFrom was found neither locally nor on the remote")
        Files.createDirectories(worktree.parent)
        git(mainDir, home, id, "worktree", "add", "-B", branch, worktree.toString(), start)
        return base
    }

    /** `refs/heads/<branch>` when it exists, else `refs/remotes/origin/<branch>` (fetched first when [id] is given), else null. */
    private fun branchRef(id: AgentIdentity?, mainDir: Path, branch: String, home: Path): String? {
        require(!branch.startsWith("-") && branch.isNotBlank()) { "Invalid branch" }
        fun exists(ref: String) = gitStatus(mainDir, home, null, "rev-parse", "--verify", "--quiet", "$ref^{commit}", timeoutSec = 60).first == 0
        if (exists("refs/heads/$branch")) return "refs/heads/$branch"
        if (localOnly) return null
        if (id != null) {
            runCatching { git(mainDir, home, id, "fetch", "origin", "+refs/heads/$branch:refs/remotes/origin/$branch") }
                .onFailure { log.info("Fetching {} failed: {}", branch, it.message) }
        }
        return "refs/remotes/origin/$branch".takeIf { exists(it) }
    }

    override fun branchCommits(id: AgentIdentity?, mainDir: Path, branch: String, baseBranch: String, home: Path): Int? = try {
        if (!Files.exists(mainDir.resolve(".git")) || baseBranch.startsWith("-")) null
        else branchRef(id, mainDir, branch, home)?.let { ref ->
            val base = if (localOnly) "refs/heads/$baseBranch" else "refs/remotes/origin/$baseBranch"
            val (code, out) = gitStatus(mainDir, home, null, "rev-list", "--count", ref, "--not", base, timeoutSec = 60)
            if (code == 0) out.trim().toIntOrNull() else null
        }
    } catch (e: Exception) {
        log.warn("Counting the commits of {} failed: {}", branch, e.message)
        null
    }

    override fun commitsAhead(worktree: Path, baseRef: String, home: Path): Int =
        git(worktree, home, null, "rev-list", "--count", "$baseRef..HEAD").trim().toIntOrNull() ?: 0

    override fun head(worktree: Path, home: Path): String = git(worktree, home, null, "rev-parse", "HEAD", timeoutSec = 60).trim()

    override fun archiveHead(worktree: Path, sha: String, file: Path, home: Path) {
        require(SHA_RE.matches(sha)) { "Invalid commit id" }
        git(worktree, home, null, "archive", "--format=tar.gz", "-o", file.toAbsolutePath().toString(), sha, timeoutSec = 600)
    }

    override fun exportTree(dir: Path, ref: String, dest: Path, home: Path): String {
        if (ref.isBlank() || ref.startsWith("-") || '\u0000' in ref) throw GitException("Invalid ref")
        val sha = git(dir, home, null, "rev-parse", "--verify", "--quiet", "$ref^{commit}", timeoutSec = 60).trim()
        if (!SHA_RE.matches(sha)) throw GitException("git rev-parse $ref: unexpected output")
        Files.createDirectories(dest)
        val tar = Files.createTempFile(dest.toAbsolutePath().parent, "export-", ".tar")
        try {
            git(dir, home, null, "archive", "--format=tar", "-o", tar.toAbsolutePath().toString(), sha, timeoutSec = 600)
            val p = ProcessBuilder("tar", "-x", "--no-same-owner", "-f", tar.toAbsolutePath().toString(), "-C", dest.toAbsolutePath().toString())
                .redirectErrorStream(true).also { it.environment().clear(); it.environment()["PATH"] = path }.start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(600, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                throw GitException("tar timed out while extracting $ref")
            }
            if (p.exitValue() != 0) throw GitException("tar failed while extracting $ref: ${out.takeLast(1000).trim()}")
        } finally {
            Files.deleteIfExists(tar)
        }
        return sha
    }

    override fun isDirty(worktree: Path, home: Path): Boolean =
        git(worktree, home, null, "status", "--porcelain", timeoutSec = 120).isNotBlank()

    override fun commitAll(worktree: Path, message: String, home: Path): String? {
        if (gitStatus(worktree, home, null, "rev-parse", "-q", "--verify", "MERGE_HEAD", timeoutSec = 60).first == 0) return null
        if (unmergedPaths(worktree, home).isNotEmpty()) return null
        if (git(worktree, home, null, "status", "--porcelain", timeoutSec = 120).isBlank()) return null
        git(worktree, home, null, "add", "-A", timeoutSec = 300)
        if (gitStatus(worktree, home, null, "diff", "--cached", "--quiet", timeoutSec = 120).first == 0) return null
        // The message is one argv element: no shell sees it.
        git(worktree, home, null, "commit", "--no-verify", "-q", "-m", message, timeoutSec = 300)
        return git(worktree, home, null, "rev-parse", "HEAD", timeoutSec = 60).trim()
    }

    override fun changedFiles(worktree: Path, baseRef: String, home: Path): List<String> {
        require(!baseRef.startsWith("-")) { "Invalid ref" }
        return git(worktree, home, null, "diff", "--name-only", "-z", "$baseRef...HEAD", "--", timeoutSec = 120)
            .split('\u0000').filter { it.isNotEmpty() }
    }

    override fun baseRef(baseBranch: String): String = if (localOnly) baseBranch else "origin/$baseBranch"

    override fun push(id: AgentIdentity, worktree: Path, branch: String, home: Path) {
        if (localOnly) return
        git(worktree, home, id, "push", "--force-with-lease", "origin", "HEAD:refs/heads/$branch")
    }

    override fun readFile(dir: Path, ref: String, path: String, home: Path, maxBytes: Int): String? {
        require(!ref.startsWith("-") && !path.startsWith("-") && ':' !in path) { "Invalid ref or path" }
        val obj = "$ref:$path"
        // `cat-file -e` exits 0 when the object exists, non-zero (without output on stdout) when it does not.
        if (gitStatus(dir, home, null, "cat-file", "-e", obj, timeoutSec = 60).first != 0) return null
        if (git(dir, home, null, "cat-file", "-t", obj, timeoutSec = 60).trim() != "blob") return null
        val size = git(dir, home, null, "cat-file", "-s", obj, timeoutSec = 60).trim().toLongOrNull() ?: throw GitException("git cat-file -s gave no size")
        if (size > maxBytes) throw GitException("$path is larger than ${maxBytes / 1024} KiB")
        return git(dir, home, null, "cat-file", "blob", obj, timeoutSec = 60)
    }

    override fun listWorktrees(mainDir: Path, home: Path): List<WorktreeInfo> {
        if (!Files.exists(mainDir.resolve(".git"))) return emptyList()
        git(mainDir, home, null, "worktree", "prune")
        val out = git(mainDir, home, null, "worktree", "list", "--porcelain")
        return out.split(Regex("\n\\s*\n")).mapNotNull { block ->
            val lines = block.lines()
            val path = lines.firstOrNull { it.startsWith("worktree ") }?.removePrefix("worktree ") ?: return@mapNotNull null
            val branch = lines.firstOrNull { it.startsWith("branch ") }?.removePrefix("branch ")?.removePrefix("refs/heads/")
            WorktreeInfo(Path.of(path), branch)
        }
    }

    override fun fetchBranch(id: AgentIdentity?, mainDir: Path, branch: String, home: Path) {
        if (localOnly || branch.isBlank() || branch.startsWith("-") || !Files.exists(mainDir.resolve(".git"))) return
        runCatching { git(mainDir, home, id, "fetch", "--no-tags", "origin", "+refs/heads/$branch:refs/remotes/origin/$branch", timeoutSec = 60) }
            .onFailure { log.warn("git fetch of {} failed in {}: {}", branch, mainDir, it.message) }
    }

    override fun forgetBranch(mainDir: Path, branch: String, home: Path) {
        try {
            if (!Files.exists(mainDir.resolve(".git")) || branch.startsWith("-")) return
            runCatching { git(mainDir, home, null, "update-ref", "-d", "refs/remotes/origin/$branch") }
            if (gitStatus(mainDir, home, null, "rev-parse", "--verify", "--quiet", "refs/heads/$branch").first != 0) return
            if (listWorktrees(mainDir, home).any { it.branch == branch }) {
                log.info("Keeping local branch {}: a worktree has it checked out", branch)
                return
            }
            val unique = runCatching {
                git(mainDir, home, null, "rev-list", "--count", "refs/heads/$branch", "--not", "--exclude=$branch", "--branches", "--remotes")
                    .trim().toInt()
            }.getOrDefault(1)
            if (unique > 0) {
                log.info("Keeping local branch {}: it has {} commit(s) found on no other branch", branch, unique)
                return
            }
            runCatching { git(mainDir, home, null, "branch", "-D", branch) }
                .onFailure { log.warn("Could not delete local branch {}: {}", branch, it.message) }
        } catch (e: Exception) {
            log.warn("Forgetting branch {} failed: {}", branch, e.message)
        }
    }

    /**
     * Fetches [baseBranch] and [headBranch] from GitHub (local branches in fake-GitHub mode) and returns their refs in
     * the clone at [mainDir]; null when there is no clone or a branch is missing.
     */
    private fun fetchPair(mainDir: Path, baseBranch: String, headBranch: String, home: Path, githubToken: String?): Pair<String, String>? {
        if (!Files.exists(mainDir.resolve(".git"))) return null
        require(!baseBranch.startsWith("-") && !headBranch.startsWith("-")) { "Invalid branch name" }
        val (base, head) = if (localOnly) {
            "refs/heads/$baseBranch" to "refs/heads/$headBranch"
        } else {
            git(
                mainDir, home, null, "fetch", "--no-tags", "origin",
                "+refs/heads/$baseBranch:refs/remotes/origin/$baseBranch", "+refs/heads/$headBranch:refs/remotes/origin/$headBranch",
                token = githubToken,
            )
            "refs/remotes/origin/$baseBranch" to "refs/remotes/origin/$headBranch"
        }
        for (ref in listOf(base, head)) {
            if (gitStatus(mainDir, home, null, "rev-parse", "--verify", "--quiet", "$ref^{commit}", timeoutSec = 60).first != 0) return null
        }
        return base to head
    }

    override fun compareBranches(
        mainDir: Path, baseBranch: String, headBranch: String, home: Path, githubToken: String?, dirOf: (String) -> String?,
    ): BranchComparison? {
        val (base, head) = fetchPair(mainDir, baseBranch, headBranch, home, githubToken) ?: return null
        return compareRefs(mainDir, base, head, home, dirOf)
    }

    override fun compareWorktree(worktree: Path, baseRef: String, home: Path, dirOf: (String) -> String?): BranchComparison? {
        require(baseRef.isNotBlank() && !baseRef.startsWith("-")) { "Invalid ref" }
        for (ref in listOf(baseRef, "HEAD")) {
            if (gitStatus(worktree, home, null, "rev-parse", "--verify", "--quiet", "$ref^{commit}", timeoutSec = 60).first != 0) return null
        }
        return compareRefs(worktree, baseRef, "HEAD", home, dirOf)
    }

    /** The comparison of [compareBranches] between two existing refs [base] and [head] of the repository at [dir]. */
    private fun compareRefs(dir: Path, base: String, head: String, home: Path, dirOf: (String) -> String?): BranchComparison {
        val behind = git(dir, home, null, "rev-list", "--count", "$head..$base", timeoutSec = 120).trim().toIntOrNull()
            ?: throw GitException("git rev-list gave no count")
        // NUL-separated: <status> NUL <path> NUL ...
        val fields = git(dir, home, null, "diff", "--name-status", "--no-renames", "-z", "$base...$head", "--").split('\u0000')
        val changes = (0 until fields.size - 1 step 2).mapNotNull { i ->
            val path = fields[i + 1]
            if (dirOf(path) == null) return@mapNotNull null
            val status = when (fields[i].firstOrNull()) { 'A' -> 'A'; 'D' -> 'D'; else -> 'M' }
            FileChange(status, path)
        }
        val dirs = changes.mapNotNull { dirOf(it.path) }.distinct().sorted()
        if (dirs.isEmpty()) return BranchComparison(emptyList(), emptyList(), behind)
        val baseFiles = git(dir, home, null, "ls-tree", "-r", "-z", "--name-only", base, "--", *dirs.toTypedArray())
            .split('\u0000').filter { it.isNotEmpty() && dirOf(it) != null }
        return BranchComparison(baseFiles, changes, behind)
    }

    override fun mergeState(worktree: Path, startSha: String, mergedRef: String, home: Path): MergeCheck {
        require(SHA_RE.matches(startSha)) { "Invalid commit id" }
        require(mergedRef.isNotBlank() && !mergedRef.startsWith("-")) { "Invalid ref" }
        val head = git(worktree, home, null, "rev-parse", "HEAD", timeoutSec = 60).trim()
        fun ancestor(ref: String): Boolean {
            val (code, out) = gitStatus(worktree, home, null, "merge-base", "--is-ancestor", ref, "HEAD", timeoutSec = 120)
            return when (code) {
                0 -> true
                1 -> false
                else -> throw GitException("git merge-base failed: ${out.takeLast(500).trim()}")
            }
        }
        val mergeInProgress = gitStatus(worktree, home, null, "rev-parse", "-q", "--verify", "MERGE_HEAD", timeoutSec = 60).first == 0
        val unmerged = git(worktree, home, null, "ls-files", "-u", timeoutSec = 60).isNotBlank()
        val dirty = git(worktree, home, null, "status", "--porcelain", "--untracked-files=no", timeoutSec = 120).isNotBlank()
        return MergeCheck(head, ancestor(startSha), ancestor(mergedRef), mergeInProgress || unmerged || dirty)
    }

    override fun pushFastForward(id: AgentIdentity, worktree: Path, branch: String, home: Path) {
        require(branch.isNotBlank() && !branch.startsWith("-")) { "Invalid branch name" }
        val head = git(worktree, home, null, "rev-parse", "HEAD", timeoutSec = 60).trim()
        if (localOnly) {
            // Fake GitHub: the release branch is a local branch of the clone; move it only as a fast-forward.
            val ref = "refs/heads/$branch"
            val old = gitStatus(worktree, home, null, "rev-parse", "--verify", "--quiet", "$ref^{commit}", timeoutSec = 60)
                .let { (code, out) -> if (code == 0) out.trim() else null }
            if (old != null && gitStatus(worktree, home, null, "merge-base", "--is-ancestor", old, head, timeoutSec = 120).first != 0) {
                throw PushRejectedException("! [rejected] HEAD -> $branch (non-fast-forward)")
            }
            // Compare-and-swap: refused when the branch moved since it was read.
            val (code, out) = gitStatus(worktree, home, null, "update-ref", ref, head, old ?: "", timeoutSec = 60)
            if (code != 0) throw PushRejectedException(out.takeLast(1000).trim())
            return
        }
        // Never --force or --force-with-lease: the remote refuses anything that is not a fast-forward.
        val (code, out) = gitStatus(worktree, home, id, "push", "origin", "HEAD:refs/heads/$branch")
        if (code == 0) return
        val text = out.takeLast(1000).trim()
        if (Regex("(?i)\\[rejected]|non-fast-forward|fetch first|stale info").containsMatchIn(text)) throw PushRejectedException(text)
        throw GitException("git push failed: $text")
    }

    override fun changedGitlinks(mainDir: Path, baseBranch: String, headBranch: String, home: Path, githubToken: String?): List<GitlinkChange>? {
        // Fake GitHub: local repositories have no submodule remotes.
        if (localOnly) return emptyList()
        val (base, head) = fetchPair(mainDir, baseBranch, headBranch, home, githubToken) ?: return null
        // NUL-separated: ":<old mode> <new mode> <old sha> <new sha> <status>" NUL <path> NUL ...
        val fields = git(mainDir, home, null, "diff", "--raw", "--no-renames", "--no-abbrev", "-z", "$base...$head", "--").split('\u0000')
        val links = (0 until fields.size - 1 step 2).mapNotNull { i ->
            val meta = fields[i].removePrefix(":").split(' ')
            if (meta.size < 5 || meta[1] != GITLINK_MODE) null else fields[i + 1] to meta[3]
        }
        if (links.isEmpty()) return emptyList()
        val urls = submoduleUrls(mainDir, head, home)
        val origin = gitStatus(mainDir, home, null, "remote", "get-url", "origin", timeoutSec = 60)
            .let { (code, out) -> if (code == 0) out.trim().takeIf { it.isNotEmpty() } else null }
        return links.map { (path, sha) ->
            val url = urls[path]?.let { u -> if (origin != null) resolveSubmoduleUrl(origin, u) else u.takeUnless { it.startsWith(".") } }
            GitlinkChange(path, sha, url)
        }
    }

    /** Submodule path to URL as `.gitmodules` in commit [ref] names them; empty when it has none. */
    private fun submoduleUrls(dir: Path, ref: String, home: Path): Map<String, String> {
        val (code, out) = gitStatus(
            dir, home, null, "config", "--blob", "$ref:.gitmodules", "--null", "--get-regexp", "^submodule\\..*\\.(path|url)$", timeoutSec = 60,
        )
        if (code != 0) return emptyMap()
        val paths = mutableMapOf<String, String>()
        val urls = mutableMapOf<String, String>()
        // NUL-terminated entries: <key> LF <value>.
        for (entry in out.split('\u0000')) {
            val key = entry.substringBefore('\n')
            if (key == entry) continue
            val name = key.removePrefix("submodule.").substringBeforeLast('.')
            when (key.substringAfterLast('.')) {
                "path" -> paths[name] = entry.substringAfter('\n')
                "url" -> urls[name] = entry.substringAfter('\n')
            }
        }
        return paths.mapNotNull { (name, path) -> urls[name]?.let { path to it } }.toMap()
    }

    override fun remoteHasCommit(url: String, sha: String, home: Path, githubToken: String?): Boolean {
        if (!SHA_RE.matches(sha)) throw GitException("Invalid commit id")
        if (url.isBlank() || url.startsWith("-")) throw GitException("Invalid submodule URL")
        Files.createDirectories(home)
        val tmp = Files.createTempDirectory(home, "agern-submodule-check-")
        try {
            git(tmp, home, null, "init", "-q", "--bare", timeoutSec = 60)
            git(tmp, home, null, "remote", "add", "origin", url, timeoutSec = 60)
            // A partial clone: only the commit object is fetched where the server supports filters.
            git(tmp, home, null, "config", "extensions.partialClone", "origin", timeoutSec = 60)
            git(tmp, home, null, "config", "remote.origin.promisor", "true", timeoutSec = 60)
            // As `git submodule` does: URLs from a repository may not use protocols that are only allowed for a user's own
            // commands (`file`, unless the home's git config allows it), so no other repository on this server is read.
            val (code, out) = gitStatus(
                tmp, home, null, "fetch", "-q", "--no-tags", "--depth=1", "--filter=tree:0", "origin", sha,
                timeoutSec = 300, token = githubToken, env = mapOf("GIT_PROTOCOL_FROM_USER" to "0"), recorded = false,
            )
            if (code == 0) return true
            if (MISSING_COMMIT_RE.containsMatchIn(out)) return false
            throw GitException("git fetch failed: ${out.takeLast(500).trim()}")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    override fun unpushedSubmodules(dir: Path, home: Path): List<UnpushedSubmodule> = try {
        if (localOnly || !Files.isRegularFile(dir.resolve(".gitmodules"))) emptyList()
        else git(dir, home, null, "submodule", "status", "--recursive", timeoutSec = 120).lines().mapNotNull { line ->
            // "<flag><sha> <path>[ (<describe>)]"; flag `-`: not initialised.
            if (line.length < 2 || line[0] == '-') return@mapNotNull null
            val rest = line.substring(1).trim()
            val path = rest.substringAfter(' ', "").let { if (it.endsWith(")") && " (" in it) it.substringBeforeLast(" (") else it }
            if (path.isEmpty()) return@mapNotNull null
            val sub = dir.resolve(path)
            val (code, out) = gitStatus(sub, home, null, "rev-list", "--count", "HEAD", "--branches", "--not", "--remotes", timeoutSec = 60)
            val n = if (code == 0) out.trim().toIntOrNull() ?: 0 else 0
            if (n == 0) null else UnpushedSubmodule(path, git(sub, home, null, "rev-parse", "HEAD", timeoutSec = 60).trim(), n)
        }
    } catch (e: Exception) {
        log.warn("Listing the unpushed submodule commits of {} failed: {}", dir, e.message)
        emptyList()
    }

    override fun bundleSubmodule(dir: Path, sub: String, file: Path, home: Path) {
        Files.createDirectories(file.parent)
        git(dir.resolve(sub), home, null, "bundle", "create", "-q", file.toString(), "HEAD", "--branches", "--not", "--remotes", timeoutSec = 300)
    }

    override fun mergeBase(id: AgentIdentity, worktree: Path, baseBranch: String, home: Path): BaseMerge {
        require(baseBranch.isNotBlank() && !baseBranch.startsWith("-")) { "Invalid branch name" }
        if (git(worktree, home, null, "status", "--porcelain", "--untracked-files=no", timeoutSec = 120).isNotBlank()) {
            throw GitException("the run's worktree has uncommitted changes")
        }
        val ref = if (localOnly) "refs/heads/$baseBranch" else {
            git(worktree, home, id, "fetch", "--no-tags", "origin", "+refs/heads/$baseBranch:refs/remotes/origin/$baseBranch")
            "refs/remotes/origin/$baseBranch"
        }
        val targetSha = git(worktree, home, null, "rev-parse", "--verify", "$ref^{commit}", timeoutSec = 60).trim()
        val before = git(worktree, home, null, "rev-parse", "HEAD", timeoutSec = 60).trim()
        val count = git(worktree, home, null, "rev-list", "--count", "HEAD..$targetSha", timeoutSec = 120).trim().toIntOrNull() ?: 0
        if (count == 0) return BaseMerge(UPDATE_UP_TO_DATE, targetSha, before, before, 0, emptyList(), emptyList())
        val incoming = git(worktree, home, null, "log", "--first-parent", "-n", "50", "--format=%s", "HEAD..$targetSha", timeoutSec = 120)
            .lines().filter { it.isNotBlank() }
        // A real merge (no rebase, no reset): the later push is a fast-forward of the remote branch. Hooks are off.
        val (code, out) = gitStatus(worktree, home, id, "-c", "core.hooksPath=/dev/null", "merge", "--no-edit", targetSha)
        if (code == 0) {
            val head = git(worktree, home, null, "rev-parse", "HEAD", timeoutSec = 60).trim()
            return BaseMerge(UPDATE_MERGED, targetSha, before, head, count, incoming, emptyList())
        }
        val conflicts = unmergedPaths(worktree, home)
        if (conflicts.isEmpty()) {
            gitStatus(worktree, home, null, "merge", "--abort", timeoutSec = 60)
            throw GitException("git merge failed: ${out.takeLast(1000).trim()}")
        }
        return BaseMerge(UPDATE_CONFLICT, targetSha, before, before, count, incoming, conflicts)
    }

    private fun unmergedPaths(worktree: Path, home: Path): List<String> =
        git(worktree, home, null, "diff", "--name-only", "--diff-filter=U", "-z", timeoutSec = 120).split('\u0000').filter { it.isNotEmpty() }.distinct()

    override fun mergeState(worktree: Path, targetSha: String, home: Path): MergeState {
        require(!targetSha.startsWith("-")) { "Invalid commit" }
        val merging = gitStatus(worktree, home, null, "rev-parse", "-q", "--verify", "MERGE_HEAD", timeoutSec = 60).first == 0
        val unmerged = unmergedPaths(worktree, home)
        val dirty = git(worktree, home, null, "status", "--porcelain", "--untracked-files=no", timeoutSec = 120).isNotBlank()
        val contains = gitStatus(worktree, home, null, "merge-base", "--is-ancestor", targetSha, "HEAD", timeoutSec = 60).first == 0
        val head = git(worktree, home, null, "rev-parse", "HEAD", timeoutSec = 60).trim()
        return MergeState(merging, unmerged, dirty, contains, head)
    }

    override fun restore(worktree: Path, sha: String, home: Path) {
        try {
            if (sha.isBlank() || sha.startsWith("-")) return
            if (gitStatus(worktree, home, null, "rev-parse", "-q", "--verify", "MERGE_HEAD", timeoutSec = 60).first == 0) {
                gitStatus(worktree, home, null, "merge", "--abort", timeoutSec = 60)
            }
            val (code, out) = gitStatus(worktree, home, null, "reset", "-q", "--hard", sha, timeoutSec = 120)
            if (code != 0) log.warn("Resetting {} to {} failed: {}", worktree, sha, out.takeLast(300).trim())
        } catch (e: Exception) {
            log.warn("Restoring {} to {} failed: {}", worktree, sha, e.message)
        }
    }

    override fun commitSubjects(worktree: Path, from: String, home: Path, max: Int): List<String> {
        require(!from.startsWith("-")) { "Invalid commit" }
        return git(worktree, home, null, "log", "--first-parent", "-n", max.toString(), "--format=%h %s", "$from..HEAD", timeoutSec = 120)
            .lines().filter { it.isNotBlank() }.reversed()
    }

    override fun landSquash(mainDir: Path, target: String, branch: String, title: String, home: Path): Boolean {
        if (!localOnly) return false
        require(target.isNotBlank() && !target.startsWith("-") && branch.isNotBlank() && !branch.startsWith("-")) { "Invalid branch name" }
        if (!Files.exists(mainDir.resolve(".git"))) return false
        val targetRef = "refs/heads/$target"
        fun sha(ref: String) = gitStatus(mainDir, home, null, "rev-parse", "--verify", "--quiet", "$ref^{commit}", timeoutSec = 60)
            .let { (code, out) -> if (code == 0) out.trim() else null }
        val old = sha(targetRef) ?: return false.also { log.info("Landing {}: branch {} not found", branch, target) }
        sha("refs/heads/$branch") ?: return false.also { log.info("Landing {}: the branch is gone", branch) }
        val checkedOut = gitStatus(mainDir, home, null, "symbolic-ref", "--quiet", "HEAD", timeoutSec = 60)
            .let { (code, out) -> code == 0 && out.trim() == targetRef }
        if (checkedOut && git(mainDir, home, null, "status", "--porcelain", "--untracked-files=no", timeoutSec = 120).isNotBlank()) {
            log.info("Not landing {} on {}: its checkout in {} has uncommitted changes", branch, target, mainDir)
            return false
        }
        val tmp = Files.createTempDirectory("agern-land-")
        val wt = tmp.resolve("wt")
        try {
            git(mainDir, home, null, "worktree", "add", "-q", "--detach", wt.toString(), old)
            val (code, out) = gitStatus(wt, home, null, "-c", "core.hooksPath=/dev/null", "merge", "--squash", "--quiet", "refs/heads/$branch", timeoutSec = 120)
            if (code != 0) {
                log.info("Not landing {} on {}: the squash merge failed: {}", branch, target, out.takeLast(300).trim())
                return false
            }
            if (gitStatus(wt, home, null, "diff", "--cached", "--quiet", timeoutSec = 60).first == 0) return false
            git(wt, home, null, "-c", "core.hooksPath=/dev/null", "commit", "-q", "--no-verify", "-m", title, timeoutSec = 120)
            val new = git(wt, home, null, "rev-parse", "HEAD", timeoutSec = 60).trim()
            if (checkedOut) {
                // A fast-forward of the clean checkout: its working tree ends at the new commit.
                git(mainDir, home, null, "-c", "core.hooksPath=/dev/null", "merge", "--ff-only", "--quiet", new, timeoutSec = 120)
            } else {
                // Compare-and-swap: refused when the branch moved meanwhile.
                git(mainDir, home, null, "update-ref", targetRef, new, old, timeoutSec = 60)
            }
            return true
        } catch (e: Exception) {
            log.warn("Landing {} on {} failed: {}", branch, target, e.message)
            return false
        } finally {
            gitStatus(mainDir, home, null, "worktree", "remove", "--force", "--force", wt.toString(), timeoutSec = 60)
            tmp.toFile().deleteRecursively()
            gitStatus(mainDir, home, null, "worktree", "prune", timeoutSec = 60)
        }
    }

    override fun syncCheckout(
        id: AgentIdentity?, mainDir: Path, defaultBranch: String, home: Path, fetch: Boolean, switchFrom: String?,
    ): CheckoutState? {
        require(defaultBranch.isNotBlank() && !defaultBranch.startsWith("-")) { "Invalid branch name" }
        require(switchFrom == null || (switchFrom.isNotBlank() && !switchFrom.startsWith("-"))) { "Invalid branch name" }
        if (!Files.exists(mainDir.resolve(".git"))) return null
        var branch = gitStatus(mainDir, home, null, "symbolic-ref", "--quiet", "--short", "HEAD", timeoutSec = 60)
            .let { (code, out) -> if (code == 0) out.trim().takeIf { it.isNotEmpty() } else null }
        var head = git(mainDir, home, null, "rev-parse", "HEAD", timeoutSec = 60).trim()
        // Fake GitHub: there is no remote to follow.
        if (localOnly) return CheckoutState(branch, defaultBranch, head, 0, 0, "current", null, null)

        val remote = "refs/remotes/origin/$defaultBranch"
        if (fetch) {
            runCatching { git(mainDir, home, id, "fetch", "--no-tags", "origin", "+refs/heads/$defaultBranch:$remote", timeoutSec = 30) }
                .onFailure { log.warn("git fetch of {} failed in {}: {}", defaultBranch, mainDir, it.message) }
        }
        if (gitStatus(mainDir, home, null, "rev-parse", "--verify", "--quiet", "$remote^{commit}", timeoutSec = 60).first != 0) return null
        val headBefore = head
        val switchedFrom = branch?.takeIf { switchFrom != null && it != defaultBranch && switchToFollowed(id, mainDir, it, defaultBranch, switchFrom, home) }
        if (switchedFrom != null) {
            branch = defaultBranch
            head = git(mainDir, home, null, "rev-parse", "HEAD", timeoutSec = 60).trim()
        } else if (branch == defaultBranch) {
            rememberFollowed(mainDir, defaultBranch, home)
        }
        val counts = git(mainDir, home, null, "rev-list", "--left-right", "--count", "HEAD...$remote", timeoutSec = 60)
            .trim().split(Regex("\\s+"))
        val ahead = counts.getOrNull(0)?.toIntOrNull() ?: throw GitException("git rev-list gave no counts")
        val behind = counts.getOrNull(1)?.toIntOrNull() ?: throw GitException("git rev-list gave no counts")
        if (behind == 0) {
            // A switch alone changed the files: reported like an update, from the HEAD before the switch.
            return if (switchedFrom != null) CheckoutState(branch, defaultBranch, head, 0, ahead, "updated", null, headBefore, switchedFrom = switchedFrom)
            else CheckoutState(branch, defaultBranch, head, 0, ahead, "current", null, null)
        }
        return fastForward(id, mainDir, branch, defaultBranch, remote, head, headBefore, behind, ahead, switchedFrom, home)
    }

    /**
     * The checkout at [mainDir] is on [cur], not on [base]: switches it to [base] when [cur] is the branch Agern followed
     * last (git config `agern.followed-branch`, else [defaultBranch]) and nothing of the user's can be lost (a clean tree,
     * no commits beyond `origin/<cur>`). Hooks are off. True when it switched; a failure is logged and leaves it as it was.
     */
    private fun switchToFollowed(id: AgentIdentity?, mainDir: Path, cur: String, base: String, defaultBranch: String, home: Path): Boolean {
        val followed = gitStatus(mainDir, home, null, "config", "--get", FOLLOWED_KEY, timeoutSec = 60)
            .let { (code, out) -> if (code == 0) out.trim().takeIf { it.isNotEmpty() } else null } ?: defaultBranch
        if (cur != followed) return false
        if (git(mainDir, home, null, "status", "--porcelain", timeoutSec = 60).isNotBlank()) return false
        val curRemote = "refs/remotes/origin/$cur"
        if (gitStatus(mainDir, home, null, "rev-parse", "--verify", "--quiet", "$curRemote^{commit}", timeoutSec = 60).first != 0) return false
        val unpushed = git(mainDir, home, null, "rev-list", "--count", "$curRemote..HEAD", timeoutSec = 60).trim().toIntOrNull() ?: return false
        if (unpushed > 0) return false
        val hasLocal = gitStatus(mainDir, home, null, "rev-parse", "--verify", "--quiet", "refs/heads/$base", timeoutSec = 60).first == 0
        val args = if (hasLocal) listOf("checkout", "--quiet", base) else listOf("checkout", "--quiet", "-b", base, "--track", "origin/$base")
        val (code, out) = gitStatus(mainDir, home, null, "-c", "core.hooksPath=/dev/null", *args.toTypedArray(), timeoutSec = 60)
        if (code != 0) {
            log.warn("Switching {} from {} to {} failed: {}", mainDir, cur, base, out.takeLast(500).trim())
            return false
        }
        log.info("Switched {} from {} to {}, the branch it follows", mainDir, cur, base)
        updateSubmodules(id, mainDir, home)?.let { log.warn("Submodules of {} could not be updated: {}", mainDir, it) }
        rememberFollowed(mainDir, base, home)
        return true
    }

    /** Records [branch] as the branch the checkout at [mainDir] follows (git config `agern.followed-branch`). Never throws. */
    private fun rememberFollowed(mainDir: Path, branch: String, home: Path) {
        val now = gitStatus(mainDir, home, null, "config", "--get", FOLLOWED_KEY, timeoutSec = 60)
            .let { (code, out) -> if (code == 0) out.trim() else null }
        if (now == branch) return
        val (code, out) = gitStatus(mainDir, home, null, "config", FOLLOWED_KEY, branch, timeoutSec = 60)
        if (code != 0) log.warn("Recording the followed branch of {} failed: {}", mainDir, out.takeLast(300).trim())
    }

    /** The fast-forward part of [syncCheckout]: [behind] > 0. */
    private fun fastForward(
        id: AgentIdentity?, mainDir: Path, branch: String?, defaultBranch: String, remote: String, head: String, headBefore: String,
        behind: Int, ahead: Int, switchedFrom: String?, home: Path,
    ): CheckoutState {

        val dirty = git(mainDir, home, null, "status", "--porcelain", timeoutSec = 60).isNotBlank()
        val reason = when {
            branch != defaultBranch -> "other_branch"
            dirty -> "local_changes"
            ahead > 0 -> "local_commits"
            else -> null
        }
        if (reason != null) return CheckoutState(branch, defaultBranch, head, behind, ahead, "behind", reason, null, switchedFrom = switchedFrom)

        // The only command of the sync that changes the working tree, the index or a local ref; hooks are off.
        val (code, out) = gitStatus(
            mainDir, home, null, "-c", "core.hooksPath=/dev/null", "merge", "--ff-only", "--quiet", remote, timeoutSec = 60,
        )
        if (code != 0) {
            log.warn("Fast-forwarding {} in {} failed: {}", defaultBranch, mainDir, out.takeLast(500).trim())
            return CheckoutState(branch, defaultBranch, head, behind, ahead, "behind", "update_failed", null, switchedFrom = switchedFrom)
        }
        val newHead = git(mainDir, home, null, "rev-parse", "HEAD", timeoutSec = 60).trim()
        updateSubmodules(id, mainDir, home)?.let { log.warn("Submodules of {} could not be updated: {}", mainDir, it) }
        return CheckoutState(branch, defaultBranch, newHead, 0, 0, "updated", null, headBefore, newCommits = behind, switchedFrom = switchedFrom)
    }

    /** [worktree]'s path from its last `runs/<uuid>` segments on; its last two segments when there are none. */
    private fun runSuffix(worktree: Path): Path {
        val names = (0 until worktree.nameCount).map { worktree.getName(it).toString() }
        val i = names.indices.lastOrNull { k ->
            names[k] == "runs" && k + 1 < names.size && runCatching { java.util.UUID.fromString(names[k + 1]) }.isSuccess
        } ?: (names.size - 2).coerceAtLeast(0)
        return Path.of(names[i], *names.drop(i + 1).toTypedArray())
    }

    override fun removeWorktree(mainDir: Path, worktree: Path, home: Path, keepUnpushed: Boolean) {
        try {
            // Match by the path from the `runs` segment on (`runs/<runId>`, or `runs/<runId>/<member directory>` in a
            // workspace member's clone): git may report the path with symlinks resolved.
            val suffix = runSuffix(worktree)
            val entry = runCatching { listWorktrees(mainDir, home) }.getOrDefault(emptyList())
                .firstOrNull { it.path.endsWith(suffix) }
            if (entry != null) {
                runCatching { git(mainDir, home, null, "worktree", "remove", "--force", "--force", entry.path.toString()) }
                    .onFailure { log.warn("Could not remove worktree {}: {}", worktree, it.message) }
            }
            // A directory git does not know (any more), or could not remove, goes too.
            if (Files.exists(worktree)) worktree.toFile().deleteRecursively()
            if (Files.exists(mainDir.resolve(".git"))) runCatching { git(mainDir, home, null, "worktree", "prune") }
            val branch = entry?.branch ?: return
            if (keepUnpushed) {
                val unique = runCatching {
                    git(mainDir, home, null, "rev-list", "--count", "refs/heads/$branch", "--not", "--exclude=$branch", "--branches", "--remotes")
                        .trim().toInt()
                }.getOrDefault(1)
                if (unique > 0) {
                    log.info("Keeping local branch {}: it has {} commit(s) found on no other branch", branch, unique)
                    return
                }
            }
            runCatching { git(mainDir, home, null, "branch", "-D", branch) }
                .onFailure { log.warn("Could not delete local branch {}: {}", branch, it.message) }
        } catch (e: Exception) {
            log.warn("Cleaning up worktree {} failed: {}", worktree, e.message)
        }
    }
}
