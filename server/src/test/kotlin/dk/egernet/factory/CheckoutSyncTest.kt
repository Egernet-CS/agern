package dk.egernet.factory

import dk.egernet.factory.agent.checkoutNote
import dk.egernet.factory.git.CheckoutState
import dk.egernet.factory.git.CliGitOps
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [CliGitOps.syncCheckout] with real git and a local bare repository as `origin` (docs/ARCHITECTURE.md §5 "Workspaces"). */
class CheckoutSyncTest {
    private val tmp: Path = Files.createTempDirectory("agern-checkout-sync")
    private val home: Path = tmp.resolve("home").also { Files.createDirectories(it) }
    private val origin: Path = tmp.resolve("origin.git")
    private val upstream: Path = tmp.resolve("upstream")
    private val main: Path = tmp.resolve("main")
    private val git = CliGitOps(System.getenv("PATH") ?: "/usr/bin:/bin", "https://github.invalid/", localOnly = false)

    @AfterTest fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun gitIn(cwd: Path, vararg args: String): String {
        val pb = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false") + args)
            .directory(cwd.toFile()).redirectErrorStream(true)
        pb.environment()["HOME"] = tmp.toString()
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${args.joinToString(" ")} failed: $out" }
        return out
    }

    private fun head(dir: Path) = gitIn(dir, "rev-parse", "HEAD").trim()

    /** A bare origin with one commit on `main`, a second clone to push from, and the main checkout under test. */
    private fun setUp() {
        gitIn(tmp, "init", "-q", "--bare", "-b", "main", origin.toString())
        Files.createDirectories(upstream)
        gitIn(upstream, "init", "-q", "-b", "main")
        gitIn(upstream, "remote", "add", "origin", origin.toString())
        Files.writeString(upstream.resolve("README.md"), "# demo\n")
        gitIn(upstream, "add", "README.md")
        gitIn(upstream, "commit", "-q", "-m", "Initial commit")
        gitIn(upstream, "push", "-q", "origin", "main")
        gitIn(tmp, "clone", "-q", origin.toString(), main.toString())
    }

    /** Pushes a commit adding [file] to origin's `main`; returns its sha. */
    private fun pushCommit(file: String = "feature.txt"): String {
        Files.writeString(upstream.resolve(file), "new $file\n")
        gitIn(upstream, "add", file)
        gitIn(upstream, "commit", "-q", "-m", "Add $file")
        gitIn(upstream, "push", "-q", "origin", "main")
        return head(upstream)
    }

    private fun sync(fetch: Boolean = true, branch: String = "main") = git.syncCheckout(null, main, branch, home, fetch)

    /** HEAD, index and status of the checkout, to show it was not modified. */
    private fun snapshot(): List<String> = listOf(
        head(main), gitIn(main, "ls-files", "--stage"), gitIn(main, "status", "--porcelain"),
        gitIn(main, "for-each-ref", "refs/heads"),
    )

    @Test
    fun `a clean checkout that is behind is fast-forwarded`() {
        setUp()
        val old = head(main)
        val tip = pushCommit()
        val s = assertNotNull(sync())
        assertEquals(CheckoutState("main", "main", tip, 0, 0, "updated", null, old, newCommits = 1), s)
        assertEquals(tip, head(main))
        assertTrue(Files.exists(main.resolve("feature.txt")))
    }

    @Test
    fun `a current checkout is left alone`() {
        setUp()
        val before = snapshot()
        val s = assertNotNull(sync())
        assertEquals("current", s.status)
        assertEquals(0, s.behind)
        assertEquals(before, snapshot())
    }

    @Test
    fun `a modified tracked file blocks the update and is kept`() {
        setUp()
        pushCommit()
        Files.writeString(main.resolve("README.md"), "# local edit\n")
        val before = snapshot()
        val s = assertNotNull(sync())
        assertEquals("behind", s.status)
        assertEquals("local_changes", s.reason)
        assertEquals(1, s.behind)
        assertEquals(before, snapshot())
        assertEquals("# local edit\n", Files.readString(main.resolve("README.md")))
        assertFalse(Files.exists(main.resolve("feature.txt")))
    }

    @Test
    fun `an untracked file counts as a local change`() {
        setUp()
        pushCommit()
        Files.writeString(main.resolve("notes.txt"), "mine\n")
        val before = snapshot()
        val s = assertNotNull(sync())
        assertEquals("behind" to "local_changes", s.status to s.reason)
        assertEquals(before, snapshot())
    }

    @Test
    fun `a local commit on the default branch blocks the update`() {
        setUp()
        pushCommit("a.txt")
        pushCommit("b.txt")
        Files.writeString(main.resolve("local.txt"), "local\n")
        gitIn(main, "add", "local.txt")
        gitIn(main, "commit", "-q", "-m", "Local work")
        val before = snapshot()
        val s = assertNotNull(sync())
        assertEquals("behind" to "local_commits", s.status to s.reason)
        assertEquals(1, s.ahead)
        assertEquals(2, s.behind)
        assertEquals(before, snapshot())
    }

    @Test
    fun `another branch or a detached HEAD blocks the update`() {
        setUp()
        gitIn(main, "checkout", "-q", "-b", "topic")
        pushCommit()
        var before = snapshot()
        var s = assertNotNull(sync())
        assertEquals("behind" to "other_branch", s.status to s.reason)
        assertEquals("topic", s.branch)
        assertEquals(before, snapshot())

        gitIn(main, "checkout", "-q", "--detach")
        before = snapshot()
        s = assertNotNull(sync())
        assertEquals("behind" to "other_branch", s.status to s.reason)
        assertNull(s.branch)
        assertEquals(before, snapshot())
    }

    @Test
    fun `without a fetch a new remote commit is not seen`() {
        setUp()
        val old = head(main)
        val tip = pushCommit()
        assertEquals("current", assertNotNull(sync(fetch = false)).status)
        assertEquals(old, head(main))
        assertEquals(tip, assertNotNull(sync(fetch = true)).head)
    }

    @Test
    fun `an unreachable origin is ignored and the last fetched ref is used`() {
        setUp()
        val old = head(main)
        pushCommit()
        origin.toFile().deleteRecursively()
        val s = assertNotNull(sync())
        assertEquals("current", s.status)
        assertEquals(old, s.head)
    }

    @Test
    fun `a missing origin ref gives no result`() {
        setUp()
        assertNull(sync(branch = "develop"))
    }

    @Test
    fun `an option-like branch name is rejected`() {
        setUp()
        assertFailsWith<IllegalArgumentException> { sync(branch = "-x") }
        assertFailsWith<IllegalArgumentException> { sync(branch = " ") }
    }

    @Test
    fun `local-only mode reports the real HEAD as current and changes nothing`() {
        Files.createDirectories(main)
        gitIn(main, "init", "-q", "-b", "main")
        Files.writeString(main.resolve("README.md"), "# x\n")
        gitIn(main, "add", "README.md")
        gitIn(main, "commit", "-q", "-m", "Initial commit")
        val before = snapshot()
        val local = CliGitOps(System.getenv("PATH") ?: "/usr/bin:/bin", "https://github.invalid/", localOnly = true)
        val s = assertNotNull(local.syncCheckout(null, main, "main", home, fetch = true))
        assertEquals(CheckoutState("main", "main", head(main), 0, 0, "current", null, null), s)
        assertEquals(before, snapshot())
    }

    @Test
    fun `no clone gives no result`() {
        assertNull(sync())
    }

    // ------------------------------------------------------------ the note for the agent

    private fun behind(reason: String, behind: Int = 1, ahead: Int = 0, branch: String? = "main") =
        CheckoutState(branch, "main", "abcdef0123456789", behind, ahead, "behind", reason, null)

    @Test
    fun `the note after a fast-forward names both short shas and the count`() {
        val one = checkoutNote(CheckoutState("main", "main", "1234567890", 0, 0, "updated", null, "abcdef0123", newCommits = 1))!!
        assertTrue(one.startsWith("<checkout_status>\n") && one.endsWith("\n</checkout_status>"), one)
        assertTrue(one.contains("fast-forwarded this checkout of `main` from abcdef0 to 1234567 (1 new commit from origin/main)"), one)
        val three = checkoutNote(CheckoutState("main", "main", "1234567890", 0, 0, "updated", null, "abcdef0123", newCommits = 3))!!
        assertTrue(three.contains("(3 new commits from origin/main)"), three)
    }

    @Test
    fun `the note when behind explains why for each reason`() {
        val one = checkoutNote(behind("local_changes"))!!
        assertTrue(one.contains("This checkout is 1 commit behind origin/main (HEAD abcdef0)"), one)
        assertTrue(one.contains("because it has uncommitted changes."), one)
        assertTrue(one.contains("`git log HEAD..origin/main`"), one)
        assertTrue(checkoutNote(behind("local_changes", behind = 4))!!.contains("4 commits behind"))
        assertTrue(checkoutNote(behind("other_branch", branch = "topic"))!!.contains("because branch `topic` is checked out, not `main`."))
        assertTrue(checkoutNote(behind("other_branch", branch = null))!!.contains("because a detached HEAD is checked out, not `main`."))
        assertTrue(checkoutNote(behind("local_commits", ahead = 1))!!.contains("because it has 1 local commit that are not on origin/main."))
        assertTrue(checkoutNote(behind("local_commits", ahead = 2))!!.contains("because it has 2 local commits that are not on origin/main."))
        assertTrue(checkoutNote(behind("update_failed"))!!.contains("because git could not fast-forward it."))
        val long = "b".repeat(300)
        val cut = checkoutNote(behind("other_branch", branch = long))!!
        assertTrue(cut.contains("`${"b".repeat(100)}`") && !cut.contains("b".repeat(101)), cut)
    }

    @Test
    fun `a current checkout gives no note`() {
        assertNull(checkoutNote(CheckoutState("main", "main", "abc", 0, 0, "current", null, null)))
    }
}
