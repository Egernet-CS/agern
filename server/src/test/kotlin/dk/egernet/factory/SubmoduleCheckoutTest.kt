package dk.egernet.factory

import dk.egernet.factory.agent.AgentIdentity
import dk.egernet.factory.git.CliGitOps
import dk.egernet.factory.github.ClaudeCredential
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Submodules are fetched after a clone, after a fast-forward and in run worktrees (docs/ARCHITECTURE.md §5
 * "Submodules"). Real git with local bare repositories; the test home allows the `file` protocol, which Agern itself
 * never relaxes.
 */
class SubmoduleCheckoutTest {
    private val tmp: Path = Files.createTempDirectory("agern-submodules")
    private val home: Path = tmp.resolve("home").also { Files.createDirectories(it) }
    private val git = CliGitOps(System.getenv("PATH") ?: "/usr/bin:/bin", "https://github.invalid/", localOnly = false)
    private val id = AgentIdentity("alice", "Alice", "alice", "fake-alice", ClaudeCredential("oauth_token", "x"))

    init {
        Files.writeString(home.resolve(".gitconfig"), "[protocol \"file\"]\n\tallow = always\n")
    }

    @AfterTest fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun gitIn(cwd: Path, vararg args: String): String {
        val pb = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false") + args)
            .directory(cwd.toFile()).redirectErrorStream(true)
        pb.environment()["HOME"] = home.toString()
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${args.joinToString(" ")} failed: $out" }
        return out
    }

    /** A bare repository `<name>.git` with its working clone `<name>-up` holding [file]; returns the working clone. */
    private fun repo(name: String, file: String): Path {
        gitIn(tmp, "init", "-q", "--bare", "-b", "main", tmp.resolve("$name.git").toString())
        val up = tmp.resolve("$name-up")
        gitIn(tmp, "clone", "-q", tmp.resolve("$name.git").toString(), up.toString())
        gitIn(up, "checkout", "-q", "-b", "main")
        Files.writeString(up.resolve(file), "$file\n")
        gitIn(up, "add", file)
        gitIn(up, "commit", "-q", "-m", "Add $file")
        gitIn(up, "push", "-q", "origin", "main")
        return up
    }

    /** A parent repository with the repository `sub` as submodule `libs/sub`; returns the parent's working clone. */
    private fun parentWithSubmodule(): Pair<Path, Path> {
        val sub = repo("sub", "lib.txt")
        val parent = repo("parent", "README.md")
        gitIn(parent, "submodule", "add", "-q", tmp.resolve("sub.git").toString(), "libs/sub")
        gitIn(parent, "commit", "-q", "-m", "Add the submodule")
        gitIn(parent, "push", "-q", "origin", "main")
        return parent to sub
    }

    @Test
    fun `a clone and a run worktree contain the submodule's files`() {
        parentWithSubmodule()
        val main = tmp.resolve("ws/main")
        git.ensureClone(id, tmp.resolve("parent.git").toString(), main, home)
        assertEquals("lib.txt\n", Files.readString(main.resolve("libs/sub/lib.txt")))

        val wt = tmp.resolve("ws/runs/11111111-1111-1111-1111-111111111111")
        git.createWorktree(id, main, wt, "factory/x", "main", home)
        assertFalse(Files.exists(wt.resolve("libs/sub/lib.txt")), "a new worktree has no submodule content yet")
        assertNull(git.updateSubmodules(id, wt, home))
        assertTrue(Files.exists(wt.resolve("libs/sub/lib.txt")))
        // Without a .gitmodules there is nothing to do.
        assertNull(git.updateSubmodules(id, tmp.resolve("sub-up"), home))
        git.removeWorktree(main, wt, home, keepUnpushed = false)
        assertFalse(Files.exists(wt))
    }

    @Test
    fun `a fast-forward that moves the submodule pointer updates it`() {
        val (parent, sub) = parentWithSubmodule()
        val main = tmp.resolve("ws/main")
        git.ensureClone(id, tmp.resolve("parent.git").toString(), main, home)
        Files.writeString(sub.resolve("lib2.txt"), "two\n")
        gitIn(sub, "add", "lib2.txt")
        gitIn(sub, "commit", "-q", "-m", "Add lib2")
        gitIn(sub, "push", "-q", "origin", "main")
        gitIn(parent.resolve("libs/sub"), "pull", "-q", "origin", "main")
        gitIn(parent, "commit", "-q", "-am", "Bump the submodule")
        gitIn(parent, "push", "-q", "origin", "main")

        val state = assertNotNull(git.syncCheckout(id, main, "main", home, fetch = true))
        assertEquals("updated", state.status)
        assertEquals("two\n", Files.readString(main.resolve("libs/sub/lib2.txt")))
    }

    @Test
    fun `a broken submodule URL does not fail the clone`() {
        val (parent, _) = parentWithSubmodule()
        gitIn(parent, "config", "-f", ".gitmodules", "submodule.libs/sub.url", tmp.resolve("missing.git").toString())
        gitIn(parent, "commit", "-q", "-am", "Point the submodule nowhere")
        gitIn(parent, "push", "-q", "origin", "main")
        val main = tmp.resolve("ws/main")
        git.ensureClone(id, tmp.resolve("parent.git").toString(), main, home)
        assertTrue(Files.exists(main.resolve("README.md")))
        assertFalse(Files.exists(main.resolve("libs/sub/lib.txt")))
        val error = assertNotNull(git.updateSubmodules(id, main, home))
        assertTrue(error.contains("missing.git"), error)
    }
}
