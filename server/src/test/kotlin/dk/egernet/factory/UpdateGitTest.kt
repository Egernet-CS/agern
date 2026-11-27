package dk.egernet.factory

import dk.egernet.factory.agent.AgentIdentity
import dk.egernet.factory.github.ClaudeCredential
import dk.egernet.factory.git.CliGitOps
import dk.egernet.factory.git.GitException
import dk.egernet.factory.git.UPDATE_CONFLICT
import dk.egernet.factory.git.UPDATE_MERGED
import dk.egernet.factory.git.UPDATE_UP_TO_DATE
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The git side of "Update and merge" with real git (fake-GitHub mode: the target is a local branch): merging the target
 * into a run's worktree ([CliGitOps.mergeBase]), checking what the builder left ([CliGitOps.mergeState]), restoring a
 * failed round ([CliGitOps.restore]) and listing the builder's commits ([CliGitOps.commitSubjects]).
 */
class UpdateGitTest {
    private val tmp: Path = Files.createTempDirectory("agern-update-git")
    private val home: Path = tmp.resolve("home")
    private val git = CliGitOps(System.getenv("PATH") ?: "/usr/bin:/bin", "https://github.invalid/", localOnly = true)
    private val id = AgentIdentity("alice", "Alice", "alice", null, ClaudeCredential("oauth_token", "x"))
    private val main: Path = tmp.resolve("main")
    private val wt: Path = tmp.resolve("wt")

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

    private fun commit(repo: Path, file: String, text: String, message: String) {
        Files.writeString(repo.resolve(file), text)
        gitIn(repo, "add", file)
        gitIn(repo, "commit", "-q", "-m", message)
    }

    private fun head(repo: Path) = gitIn(repo, "rev-parse", "HEAD").trim()

    /** `main` with a.txt, and the run's worktree on `feature` with one commit of its own. */
    private fun setUp() {
        Files.createDirectories(main)
        Files.createDirectories(home)
        gitIn(main, "init", "-q", "-b", "main")
        commit(main, "a.txt", "one\n", "base")
        gitIn(main, "worktree", "add", "-q", "-b", "feature", wt.toString(), "main")
        commit(wt, "b.txt", "mine\n", "feature work")
    }

    @Test
    fun `a branch that already contains the target is up to date`() {
        setUp()
        val before = head(wt)
        val m = git.mergeBase(id, wt, "main", home)
        assertEquals(UPDATE_UP_TO_DATE, m.status)
        assertEquals(before, m.head)
        assertEquals(0, m.incomingCount)
        assertEquals(before, head(wt))
    }

    @Test
    fun `a clean merge is a real merge committed by the owner, keeping both histories`() {
        setUp()
        commit(main, "c.txt", "theirs\n", "Other feature (#7)")
        commit(main, "d.txt", "more\n", "Another (#8)")
        val target = head(main)
        val before = head(wt)
        val m = git.mergeBase(id, wt, "main", home)
        assertEquals(UPDATE_MERGED, m.status)
        assertEquals(2, m.incomingCount)
        assertEquals(listOf("Another (#8)", "Other feature (#7)"), m.incoming)
        assertEquals(target, m.targetSha)
        assertEquals(before, m.headBefore)
        assertEquals(head(wt), m.head)
        // Both the target and the previous head are ancestors: nothing was rewritten.
        gitIn(wt, "merge-base", "--is-ancestor", target, "HEAD")
        gitIn(wt, "merge-base", "--is-ancestor", before, "HEAD")
        assertEquals("Alice", gitIn(wt, "log", "-1", "--format=%an").trim())
        val state = git.mergeState(wt, target, home)
        assertFalse(state.merging)
        assertFalse(state.dirty)
        assertTrue(state.containsTarget)
        assertEquals(m.head, state.head)
    }

    @Test
    fun `a conflict leaves the worktree mid-merge with the files, and restore puts it back`() {
        setUp()
        commit(main, "a.txt", "theirs\n", "Change a on main")
        commit(wt, "a.txt", "ours\n", "Change a on feature")
        val before = head(wt)
        val target = head(main)
        val m = git.mergeBase(id, wt, "main", home)
        assertEquals(UPDATE_CONFLICT, m.status)
        assertEquals(listOf("a.txt"), m.conflicts)
        assertEquals(before, m.head)
        val state = git.mergeState(wt, target, home)
        assertTrue(state.merging)
        assertEquals(listOf("a.txt"), state.unmerged)
        assertFalse(state.containsTarget)

        git.restore(wt, before, home)
        val after = git.mergeState(wt, target, home)
        assertFalse(after.merging)
        assertTrue(after.unmerged.isEmpty())
        assertFalse(after.dirty)
        assertEquals(before, head(wt))
        assertEquals("ours\n", Files.readString(wt.resolve("a.txt")))

        // Resolved and committed by the builder: the merge is finished and its commits are listed.
        git.mergeBase(id, wt, "main", home)
        Files.writeString(wt.resolve("a.txt"), "both\n")
        gitIn(wt, "add", "a.txt")
        gitIn(wt, "commit", "-q", "--no-edit")
        commit(wt, "e.txt", "fix\n", "Fix the build after the merge")
        val done = git.mergeState(wt, target, home)
        assertFalse(done.merging)
        assertTrue(done.containsTarget)
        val subjects = git.commitSubjects(wt, before, home, 20)
        assertEquals(2, subjects.size)
        assertTrue(subjects[0].endsWith("Merge commit '$target'") || subjects[0].contains("Merge"), subjects.toString())
        assertTrue(subjects[1].endsWith(" Fix the build after the merge"), subjects.toString())
        assertEquals(1, git.commitSubjects(wt, before, home, 1).size)
    }

    @Test
    fun `a worktree with uncommitted changes is not merged`() {
        setUp()
        commit(main, "c.txt", "theirs\n", "Other")
        Files.writeString(wt.resolve("b.txt"), "dirty\n")
        val e = assertFailsWith<GitException> { git.mergeBase(id, wt, "main", home) }
        assertEquals("the run's worktree has uncommitted changes", e.message)
        assertTrue(git.mergeState(wt, head(main), home).dirty)
        assertFailsWith<IllegalArgumentException> { git.mergeBase(id, wt, "-main", home) }
    }

    @Test
    fun `commitAll commits tracked and untracked changes as Agern, without ignored files`() {
        setUp()
        Files.writeString(wt.resolve(".gitignore"), ".env\n")
        gitIn(wt, "add", ".gitignore")
        gitIn(wt, "commit", "-q", "-m", "ignore .env")
        val before = head(wt)
        Files.writeString(wt.resolve("b.txt"), "changed\n")
        Files.writeString(wt.resolve("new.txt"), "new\n")
        Files.writeString(wt.resolve(".env"), "SECRET=1\n")
        val message = "WIP: saved\n\nThe run failed with: \"quotes\" and `backticks`; \$(not a shell)"
        val sha = git.commitAll(wt, message, home)
        assertEquals(head(wt), sha)
        assertEquals(before, gitIn(wt, "rev-parse", "HEAD~1").trim())
        assertEquals("Agern|Agern", gitIn(wt, "log", "-1", "--format=%an|%cn").trim())
        assertEquals(message, gitIn(wt, "log", "-1", "--format=%B").trim())
        assertEquals(listOf("b.txt", "new.txt"), gitIn(wt, "show", "--name-only", "--format=", "HEAD").lines().filter { it.isNotBlank() }.sorted())
        // Only the ignored file is left, still uncommitted.
        assertEquals("", gitIn(wt, "status", "--porcelain").trim())
        assertTrue(Files.exists(wt.resolve(".env")))
    }

    @Test
    fun `commitAll commits nothing on a clean tree or during a conflicted merge`() {
        setUp()
        val before = head(wt)
        assertEquals(null, git.commitAll(wt, "WIP", home))
        assertEquals(before, head(wt))

        commit(main, "a.txt", "theirs\n", "Change a on main")
        commit(wt, "a.txt", "ours\n", "Change a on feature")
        val mid = head(wt)
        assertEquals(UPDATE_CONFLICT, git.mergeBase(id, wt, "main", home).status)
        Files.writeString(wt.resolve("stray.txt"), "stray\n")
        assertEquals(null, git.commitAll(wt, "WIP", home))
        assertEquals(mid, head(wt))
        assertTrue(git.mergeState(wt, head(main), home).merging)
    }
}
