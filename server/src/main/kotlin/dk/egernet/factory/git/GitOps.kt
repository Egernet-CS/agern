package dk.egernet.factory.git

import dk.egernet.factory.agent.AgentCommand
import dk.egernet.factory.agent.AgentIdentity
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class GitException(message: String) : RuntimeException(message)

/** Git operations the server performs itself (the agents run git on their own). */
interface GitOps {
    /** Clones the repo into [dir] or fetches if it is already there. */
    fun ensureClone(id: AgentIdentity, cloneUrl: String, dir: Path, home: Path)
    /**
     * Creates a worktree on a new branch from the up-to-date [baseBranch] (the default branch, or `release/<name>`
     * for a release run). Returns the base ref used.
     */
    fun createWorktree(id: AgentIdentity, mainDir: Path, worktree: Path, branch: String, baseBranch: String, home: Path): String
    fun commitsAhead(worktree: Path, baseRef: String, home: Path): Int
    fun push(id: AgentIdentity, worktree: Path, branch: String, home: Path)
    fun removeWorktree(mainDir: Path, worktree: Path, home: Path)
    /**
     * The file at [path] in commit [ref] of the repository at [dir] (raw blob bytes as UTF-8), or null when it does not
     * exist there. Throws [GitException] when it is larger than [maxBytes] or git fails.
     */
    fun readFile(dir: Path, ref: String, path: String, home: Path, maxBytes: Int): String?
}

/**
 * Runs the git CLI with an environment built from scratch (auth via `GIT_CONFIG_*` env only).
 * With [localOnly] (fake GitHub dev mode) a clone is replaced by `git init` and pushes are skipped.
 */
class CliGitOps(private val path: String, private val gitHost: String, private val localOnly: Boolean = false) : GitOps {
    private val log = LoggerFactory.getLogger(CliGitOps::class.java)

    private fun git(cwd: Path?, home: Path, id: AgentIdentity?, vararg args: String, timeoutSec: Long = 600): String {
        val (code, out) = gitStatus(cwd, home, id, *args, timeoutSec = timeoutSec)
        if (code != 0) throw GitException("git ${args.take(2).joinToString(" ")} failed: ${out.takeLast(1000).trim()}")
        return out
    }

    /** Runs git and returns its exit code and output instead of throwing on a non-zero exit. */
    private fun gitStatus(cwd: Path?, home: Path, id: AgentIdentity?, vararg args: String, timeoutSec: Long = 600): Pair<Int, String> {
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

    override fun createWorktree(id: AgentIdentity, mainDir: Path, worktree: Path, branch: String, baseBranch: String, home: Path): String {
        val base = if (localOnly) {
            // Fake GitHub: a release branch exists only on the fake; create it locally from the clone's HEAD.
            if (runCatching { git(mainDir, home, id, "rev-parse", "--verify", "--quiet", "refs/heads/$baseBranch") }.isFailure) {
                git(mainDir, home, id, "branch", baseBranch, "HEAD")
            }
            baseBranch
        } else {
            git(mainDir, home, id, "fetch", "origin", baseBranch)
            "origin/$baseBranch"
        }
        Files.createDirectories(worktree.parent)
        git(mainDir, home, id, "worktree", "add", "-B", branch, worktree.toString(), base)
        return base
    }

    override fun commitsAhead(worktree: Path, baseRef: String, home: Path): Int =
        git(worktree, home, null, "rev-list", "--count", "$baseRef..HEAD").trim().toIntOrNull() ?: 0

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

    override fun removeWorktree(mainDir: Path, worktree: Path, home: Path) {
        runCatching { git(mainDir, home, null, "worktree", "remove", "--force", worktree.toString()) }
            .onFailure { log.warn("Could not remove worktree {}: {}", worktree, it.message) }
    }
}
