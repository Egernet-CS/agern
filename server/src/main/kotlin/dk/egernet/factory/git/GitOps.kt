package dk.egernet.factory.git

import dk.egernet.factory.agent.AgentCommand
import dk.egernet.factory.agent.AgentIdentity
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class GitException(message: String) : RuntimeException(message)

/** One entry of `git worktree list`: its path as git reports it and its branch (null when detached). */
data class WorktreeInfo(val path: Path, val branch: String?)

/** One file [headBranch] changes relative to its merge base with a base branch: [status] `A`, `M` or `D`. */
data class FileChange(val status: Char, val path: String)

/**
 * [GitOps.compareBranches]: the files on the base branch's tip under the directories of interest, and what the head
 * changes (relative to the merge base) in them. Renames are a removal plus an addition.
 */
data class BranchComparison(val baseFiles: List<String>, val changes: List<FileChange>)

/**
 * The state of a main checkout relative to `origin/<defaultBranch>` after [GitOps.syncCheckout]
 * (docs/ARCHITECTURE.md §5 "Workspaces"). [branch] is null for a detached HEAD; [head] is the full HEAD sha after the
 * sync. [status] is `current`, `updated` (fast-forwarded from [updatedFrom]) or `behind` (not updated because of
 * [reason]: `other_branch`, `local_changes`, `local_commits` or `update_failed`). [newCommits]: how many commits an
 * update brought in (the `behind` before it); 0 otherwise.
 */
data class CheckoutState(
    val branch: String?, val defaultBranch: String, val head: String, val behind: Int, val ahead: Int,
    val status: String, val reason: String?, val updatedFrom: String?, val newCommits: Int = 0,
)

/** Git operations the server performs itself (the agents run git on their own). */
interface GitOps {
    /** Clones the repo into [dir] or fetches if it is already there. */
    fun ensureClone(id: AgentIdentity, cloneUrl: String, dir: Path, home: Path)
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
     * Brings the main checkout at [mainDir] up to date with `origin/<defaultBranch>` when that is safe: fetches that
     * branch first when [fetch] is set (a failed fetch is ignored), then fast-forwards only when the checkout is on
     * [defaultBranch], clean and without local commits. Never modifies the checkout otherwise. Null when there is no
     * clone or no `origin/<defaultBranch>`; throws [IllegalArgumentException] for an invalid branch name.
     */
    fun syncCheckout(id: AgentIdentity?, mainDir: Path, defaultBranch: String, home: Path, fetch: Boolean): CheckoutState? = null
}

/**
 * Runs the git CLI with an environment built from scratch (auth via `GIT_CONFIG_*` env only).
 * With [localOnly] (fake GitHub dev mode) a clone is replaced by `git init` and pushes are skipped.
 */
class CliGitOps(private val path: String, private val gitHost: String, private val localOnly: Boolean = false) : GitOps {
    private val log = LoggerFactory.getLogger(CliGitOps::class.java)

    private fun git(cwd: Path?, home: Path, id: AgentIdentity?, vararg args: String, timeoutSec: Long = 600, token: String? = null): String {
        val (code, out) = gitStatus(cwd, home, id, *args, timeoutSec = timeoutSec, token = token)
        if (code != 0) throw GitException("git ${args.take(2).joinToString(" ")} failed: ${out.takeLast(1000).trim()}")
        return out
    }

    /**
     * Runs git and returns its exit code and output instead of throwing on a non-zero exit. [token]: authenticate with
     * this GitHub token where there is no [id] (a fetch on the server's own behalf).
     */
    private fun gitStatus(
        cwd: Path?, home: Path, id: AgentIdentity?, vararg args: String, timeoutSec: Long = 600, token: String? = null,
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
            if (token != null) pb.environment().putAll(AgentCommand.gitTokenEnvironment(token, gitHost))
        }
        Files.createDirectories(home)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            throw GitException("git ${args.firstOrNull()} timed out")
        }
        return p.exitValue() to out
    }

    override fun ensureClone(id: AgentIdentity, cloneUrl: String, dir: Path, home: Path) {
        if (Files.isDirectory(dir.resolve(".git"))) {
            if (!localOnly) runCatching { git(dir, home, id, "fetch", "--prune", "origin") }
                .onFailure { log.warn("git fetch failed in {}: {}", dir, it.message) }
            return
        }
        Files.createDirectories(dir.parent)
        if (localOnly) {
            Files.createDirectories(dir)
            git(dir, home, id, "init", "-q", "-b", "main")
            Files.writeString(dir.resolve("README.md"), "# ${dir.parent.fileName}\n\nLocal fake repository (FACTORY_DEV_FAKE_GITHUB).\n")
            git(dir, home, id, "add", "README.md")
            git(dir, home, id, "commit", "-q", "-m", "Initial commit")
        } else {
            git(dir.parent, home, id, "clone", "-q", cloneUrl, dir.fileName.toString())
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
        if (!Files.isDirectory(mainDir.resolve(".git")) || baseBranch.startsWith("-")) null
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
        if (!Files.isDirectory(mainDir.resolve(".git"))) return emptyList()
        git(mainDir, home, null, "worktree", "prune")
        val out = git(mainDir, home, null, "worktree", "list", "--porcelain")
        return out.split(Regex("\n\\s*\n")).mapNotNull { block ->
            val lines = block.lines()
            val path = lines.firstOrNull { it.startsWith("worktree ") }?.removePrefix("worktree ") ?: return@mapNotNull null
            val branch = lines.firstOrNull { it.startsWith("branch ") }?.removePrefix("branch ")?.removePrefix("refs/heads/")
            WorktreeInfo(Path.of(path), branch)
        }
    }

    override fun forgetBranch(mainDir: Path, branch: String, home: Path) {
        try {
            if (!Files.isDirectory(mainDir.resolve(".git")) || branch.startsWith("-")) return
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

    override fun compareBranches(
        mainDir: Path, baseBranch: String, headBranch: String, home: Path, githubToken: String?, dirOf: (String) -> String?,
    ): BranchComparison? {
        if (!Files.isDirectory(mainDir.resolve(".git"))) return null
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
        // NUL-separated: <status> NUL <path> NUL ...
        val fields = git(mainDir, home, null, "diff", "--name-status", "--no-renames", "-z", "$base...$head", "--").split('\u0000')
        val changes = (0 until fields.size - 1 step 2).mapNotNull { i ->
            val path = fields[i + 1]
            if (dirOf(path) == null) return@mapNotNull null
            val status = when (fields[i].firstOrNull()) { 'A' -> 'A'; 'D' -> 'D'; else -> 'M' }
            FileChange(status, path)
        }
        val dirs = changes.mapNotNull { dirOf(it.path) }.distinct().sorted()
        if (dirs.isEmpty()) return BranchComparison(emptyList(), emptyList())
        val baseFiles = git(mainDir, home, null, "ls-tree", "-r", "-z", "--name-only", base, "--", *dirs.toTypedArray())
            .split('\u0000').filter { it.isNotEmpty() && dirOf(it) != null }
        return BranchComparison(baseFiles, changes)
    }

    override fun syncCheckout(id: AgentIdentity?, mainDir: Path, defaultBranch: String, home: Path, fetch: Boolean): CheckoutState? {
        require(defaultBranch.isNotBlank() && !defaultBranch.startsWith("-")) { "Invalid branch name" }
        if (!Files.isDirectory(mainDir.resolve(".git"))) return null
        val branch = gitStatus(mainDir, home, null, "symbolic-ref", "--quiet", "--short", "HEAD", timeoutSec = 60)
            .let { (code, out) -> if (code == 0) out.trim().takeIf { it.isNotEmpty() } else null }
        val head = git(mainDir, home, null, "rev-parse", "HEAD", timeoutSec = 60).trim()
        // Fake GitHub: there is no remote to follow.
        if (localOnly) return CheckoutState(branch, defaultBranch, head, 0, 0, "current", null, null)

        val remote = "refs/remotes/origin/$defaultBranch"
        if (fetch) {
            runCatching { git(mainDir, home, id, "fetch", "--no-tags", "origin", "+refs/heads/$defaultBranch:$remote", timeoutSec = 30) }
                .onFailure { log.warn("git fetch of {} failed in {}: {}", defaultBranch, mainDir, it.message) }
        }
        if (gitStatus(mainDir, home, null, "rev-parse", "--verify", "--quiet", "$remote^{commit}", timeoutSec = 60).first != 0) return null
        val counts = git(mainDir, home, null, "rev-list", "--left-right", "--count", "HEAD...$remote", timeoutSec = 60)
            .trim().split(Regex("\\s+"))
        val ahead = counts.getOrNull(0)?.toIntOrNull() ?: throw GitException("git rev-list gave no counts")
        val behind = counts.getOrNull(1)?.toIntOrNull() ?: throw GitException("git rev-list gave no counts")
        if (behind == 0) return CheckoutState(branch, defaultBranch, head, 0, ahead, "current", null, null)

        val dirty = git(mainDir, home, null, "status", "--porcelain", timeoutSec = 60).isNotBlank()
        val reason = when {
            branch != defaultBranch -> "other_branch"
            dirty -> "local_changes"
            ahead > 0 -> "local_commits"
            else -> null
        }
        if (reason != null) return CheckoutState(branch, defaultBranch, head, behind, ahead, "behind", reason, null)

        // The only command of the sync that changes the working tree, the index or a local ref; hooks are off.
        val (code, out) = gitStatus(
            mainDir, home, null, "-c", "core.hooksPath=/dev/null", "merge", "--ff-only", "--quiet", remote, timeoutSec = 60,
        )
        if (code != 0) {
            log.warn("Fast-forwarding {} in {} failed: {}", defaultBranch, mainDir, out.takeLast(500).trim())
            return CheckoutState(branch, defaultBranch, head, behind, ahead, "behind", "update_failed", null)
        }
        val newHead = git(mainDir, home, null, "rev-parse", "HEAD", timeoutSec = 60).trim()
        return CheckoutState(branch, defaultBranch, newHead, 0, 0, "updated", null, head, newCommits = behind)
    }

    override fun removeWorktree(mainDir: Path, worktree: Path, home: Path, keepUnpushed: Boolean) {
        try {
            // Match by the last two segments (`runs/<runId>`): git may report the path with symlinks resolved.
            val entry = runCatching { listWorktrees(mainDir, home) }.getOrDefault(emptyList())
                .firstOrNull { it.path.fileName == worktree.fileName && it.path.parent?.fileName == worktree.parent?.fileName }
            if (entry != null) {
                runCatching { git(mainDir, home, null, "worktree", "remove", "--force", "--force", entry.path.toString()) }
                    .onFailure { log.warn("Could not remove worktree {}: {}", worktree, it.message) }
            }
            // A directory git does not know (any more), or could not remove, goes too.
            if (Files.exists(worktree)) worktree.toFile().deleteRecursively()
            if (Files.isDirectory(mainDir.resolve(".git"))) runCatching { git(mainDir, home, null, "worktree", "prune") }
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
