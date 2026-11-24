package dk.egernet.factory

import dk.egernet.factory.agent.AgentIdentity
import dk.egernet.factory.agent.Workspaces
import dk.egernet.factory.git.CliGitOps
import dk.egernet.factory.git.GitException
import dk.egernet.factory.git.GitlinkChange
import dk.egernet.factory.git.resolveSubmoduleUrl
import dk.egernet.factory.github.ClaudeCredential
import dk.egernet.factory.github.FakeGitHub
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The git side of the submodule pointer guard and of saving unpushed submodule commits (docs/ARCHITECTURE.md §6
 * "Submodule pointers"). Real git with local bare repositories; the test home allows the `file` protocol, which Agern
 * itself never relaxes.
 */
class SubmodulePointerGitTest {
    private val tmp: Path = Files.createTempDirectory("agern-submodule-pointers")
    private val home: Path = tmp.resolve("data/homes/alice").also { Files.createDirectories(it) }
    private val git = CliGitOps(System.getenv("PATH") ?: "/usr/bin:/bin", "https://github.invalid/", localOnly = false)
    private val id = AgentIdentity("alice", "Alice", "alice", "fake-alice", ClaudeCredential("oauth_token", "x"))
    private val workspaces = Workspaces(testConfig(tmp.resolve("data")), git, FakeGitHub.seeded("test-org"))

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

    /** `parent` with `sub` as submodule `libs/sub` under a relative URL, cloned as alice's main clone; returns the clone. */
    private fun setUp(): Path {
        repo("sub", "lib.txt")
        val parent = repo("parent", "README.md")
        gitIn(parent, "submodule", "add", "-q", "../sub.git", "libs/sub")
        gitIn(parent, "commit", "-q", "-m", "Add the submodule")
        gitIn(parent, "push", "-q", "origin", "main")
        val main = workspaces.mainDir("alice", "parent")
        git.ensureClone(id, tmp.resolve("parent.git").toString(), main, home)
        return main
    }

    /** A run worktree on `factory/x` whose builder committed inside `libs/sub` and the new pointer in the parent. */
    private fun runWithSubmoduleCommit(main: Path, runId: UUID): Pair<Path, String> {
        val wt = workspaces.runDir("alice", "parent", runId)
        git.createWorktree(id, main, wt, "factory/x", "main", home)
        assertEquals(null, git.updateSubmodules(id, wt, home))
        val sub = wt.resolve("libs/sub")
        Files.writeString(sub.resolve("fix.txt"), "fix\n")
        gitIn(sub, "add", "fix.txt")
        gitIn(sub, "commit", "-q", "-m", "Fix the library")
        val subSha = gitIn(sub, "rev-parse", "HEAD").trim()
        gitIn(wt, "add", "libs/sub")
        gitIn(wt, "commit", "-q", "-m", "Bump the submodule")
        return wt to subSha
    }

    @Test
    fun `relative submodule URLs resolve against the parent's URL`() {
        assertEquals("https://github.com/org/sub.git", resolveSubmoduleUrl("https://github.com/org/parent.git", "../sub.git"))
        assertEquals("https://github.com/other/sub.git", resolveSubmoduleUrl("https://github.com/org/parent.git/", "../../other/sub.git"))
        assertEquals("https://github.com/org/parent.git/sub", resolveSubmoduleUrl("https://github.com/org/parent.git", "./sub"))
        assertEquals("git@github.com:org/sub.git", resolveSubmoduleUrl("git@github.com:org/parent.git", "../sub.git"))
        assertEquals("git@github.com:sub.git", resolveSubmoduleUrl("git@github.com:org/parent.git", "../../sub.git"))
        assertEquals("ssh://git@github.com/org/sub.git", resolveSubmoduleUrl("https://github.com/org/parent.git", "ssh://git@github.com/org/sub.git"))
    }

    @Test
    fun `a moved pointer is found, and its commit exists on the remote only once pushed`() {
        val main = setUp()
        val (wt, subSha) = runWithSubmoduleCommit(main, UUID.randomUUID())
        git.push(id, wt, "factory/x", home)

        val links = assertNotNull(git.changedGitlinks(main, "main", "factory/x", home, null))
        assertEquals(listOf(GitlinkChange("libs/sub", subSha, tmp.resolve("sub.git").toString())), links)
        val url = links.single().url!!
        assertFalse(git.remoteHasCommit(url, subSha, home, null), "the commit exists only in the run's worktree")

        gitIn(wt.resolve("libs/sub"), "push", "-q", "origin", "HEAD:refs/heads/fix")
        assertTrue(git.remoteHasCommit(url, subSha, home, null))
        // A commit below a branch tip counts too.
        val first = gitIn(tmp.resolve("sub-up"), "rev-parse", "HEAD").trim()
        gitIn(wt.resolve("libs/sub"), "push", "-q", "origin", "HEAD:refs/heads/main")
        assertTrue(git.remoteHasCommit(url, first, home, null))
        // A remote that cannot be reached is not "missing": the caller fails closed with its own message.
        assertFailsWith<GitException> { git.remoteHasCommit(tmp.resolve("missing.git").toString(), subSha, home, null) }
        // A branch that does not touch a submodule has no pointers to check.
        assertEquals(emptyList(), git.changedGitlinks(main, "main", "main", home, null))
    }

    @Test
    fun `removing a run checkout bundles the submodule commits that exist nowhere else`() {
        val main = setUp()
        val runId = UUID.randomUUID()
        val (wt, subSha) = runWithSubmoduleCommit(main, runId)
        assertEquals(listOf("libs/sub" to 1), git.unpushedSubmodules(wt, home).map { it.path to it.commits })

        val bundles = workspaces.removeRunCheckout("alice", "parent", runId, keepUnpushed = false)
        assertFalse(Files.exists(wt))
        val b = bundles.single()
        assertEquals("libs/sub", b.path)
        assertEquals(subSha, b.head)
        assertEquals(workspaces.bundleDir("alice", "parent", runId).resolve("libs_sub.bundle"), b.file)

        // Recovery as the warning tells it: fetch the bundle in a clone of the submodule.
        val clone = tmp.resolve("recover")
        gitIn(tmp, "clone", "-q", tmp.resolve("sub.git").toString(), clone.toString())
        gitIn(clone, "fetch", "-q", b.file.toString())
        assertEquals(subSha, gitIn(clone, "rev-parse", "FETCH_HEAD").trim())
    }

    @Test
    fun `pushed submodules and checkouts without submodules have nothing to save`() {
        val main = setUp()
        val runId = UUID.randomUUID()
        val wt = workspaces.runDir("alice", "parent", runId)
        git.createWorktree(id, main, wt, "factory/y", "main", home)
        assertEquals(null, git.updateSubmodules(id, wt, home))
        assertEquals(emptyList(), git.unpushedSubmodules(wt, home))
        assertEquals(emptyList(), git.unpushedSubmodules(tmp.resolve("sub-up"), home))
        assertEquals(emptyList(), workspaces.removeRunCheckout("alice", "parent", runId, keepUnpushed = false))
        assertFalse(Files.exists(workspaces.bundleDir("alice", "parent", runId)))
    }
}
