package dk.egernet.factory

import dk.egernet.factory.agent.AgentIdentity
import dk.egernet.factory.agent.MemberCheckout
import dk.egernet.factory.github.ClaudeCredential
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The checkout of a workspace project (docs/ARCHITECTURE.md §5 "Workspace projects"): chat clones, a run's worktrees
 * and their clean-up. Real git in a temp dir (fake-GitHub mode: local repositories, no pushes).
 */
class WorkspaceCheckoutTest {
    private val world = TestWorld(realGit = true)
    private val s = world.services
    private val alice = world.user("alice")
    private val bob = world.user("bob")
    private val git = world.cliGit!!
    private val ws = "demo-workspace"
    private fun main(user: String = "alice"): Path = s.workspaces.mainDir(user, ws)
    private val home: Path get() = s.workspaces.home("alice")

    @AfterTest fun tearDown() = world.close()

    private fun script(approver: String = "APPROVE", hangRole: String? = null, builderCommits: Boolean = true) {
        world.launcher.stage = { p ->
            p.init()
            when {
                p.spec.kind == hangRole -> while (!p.killed) Thread.sleep(10)
                p.spec.kind == "spec-writer" -> p.result("## Spec\nDo it.")
                p.spec.kind == "builder" -> {
                    // A builder of a workspace run commits in the target member's directory.
                    if (builderCommits) gitIn(p.spec.workDir.resolve("app"), "commit", "--allow-empty", "-q", "-m", "work")
                    p.result("Built it.")
                }
                p.spec.kind == "approver" -> p.result(null, buildJsonObject { put("verdict", approver); put("summary", "approver: $approver") })
                else -> p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
            }
            if (!p.killed) p.exit(0)
        }
    }

    private fun gitIn(dir: Path, vararg args: String): String {
        val pb = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid") + args)
            .directory(dir.toFile()).redirectErrorStream(true)
        pb.environment()["HOME"] = home.toString()
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${args.joinToString(" ")} in $dir failed: $out" }
        return out
    }

    private fun branchOf(dir: Path) = gitIn(dir, "rev-parse", "--abbrev-ref", "HEAD").trim()
    private fun start(n: Int = 1) = runBlocking { s.orchestrator.start(alice, ws, n, null) }
    private fun await(id: UUID) = world.awaitRun(id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
    private fun runDir(id: UUID) = s.workspaces.runDir("alice", ws, id)

    /** Worktrees registered in the workspace clone and every member clone, other than the clones themselves. */
    private fun registered(): List<Path> = listOf(main(), main().resolve("app"), main().resolve("tools/cli"), main().resolve("lab"))
        .filter { Files.isDirectory(it.resolve(".git")) }
        .flatMap { clone -> git.listWorktrees(clone, home).map { it.path }.filter { it.toRealPath() != clone.toRealPath() } }

    /** Creates a chat of [user] and waits until its checkout has recorded [n] member repositories. */
    private fun chatCheckout(user: dk.egernet.factory.db.UserRecord, n: Int) {
        val chat = s.agents.createChat(user, ws, null)
        val deadline = System.currentTimeMillis() + 10_000
        while (s.sessions.get(chat.id)!!.contextRepos.size < n && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(n, s.sessions.get(chat.id)!!.contextRepos.size)
    }

    @Test
    fun `a chat checks out the members its owner can read`() {
        chatCheckout(alice, 3)
        assertEquals("dev", branchOf(main().resolve("app")))
        assertEquals("main", branchOf(main().resolve("tools/cli")))
        assertEquals("main", branchOf(main().resolve("lab")))
        val exclude = Files.readAllLines(main().resolve(".git/info/exclude"))
        assertTrue(exclude.containsAll(listOf("/app/", "/tools/cli/", "/lab/")), exclude.toString())
        // The members are separate repositories that the workspace clone does not report as changes.
        assertEquals("", gitIn(main(), "status", "--porcelain").trim())
        val members = Files.readString(s.workspaces.repoDir("alice", ws).resolve("members.json"))
        assertTrue(members.contains("\"repository\":\"demo-ws-app\""), members)

        chatCheckout(bob, 2)
        assertFalse(Files.exists(main("bob").resolve("lab")), "bob cannot read demo-ws-lab")
    }

    @Test
    fun `a run gets the whole workspace, and merging leaves nothing behind`() {
        script()
        val run = start()
        val done = await(run.id)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        val builder = world.launcher.byRole("builder").single().spec
        assertEquals(runDir(run.id), builder.workDir)
        assertTrue(world.launcher.launched.all { it.spec.workDir == runDir(run.id) })
        assertTrue(registered().isEmpty(), registered().toString())
        assertFalse(Files.exists(runDir(run.id)))
        assertFalse(gitIn(main().resolve("app"), "branch", "--format=%(refname:short)").lines().contains(done.branch))
    }

    @Test
    fun `the run's layout while it waits for a human`() {
        script(approver = "ESCALATE")
        val run = start()
        val done = await(run.id)
        assertEquals(RunStatus.NEEDS_HUMAN, done.status, done.error)
        val root = runDir(run.id)
        // The root is a detached checkout of the workspace repository; the target on the run branch from dev; the rest detached.
        assertEquals("HEAD", branchOf(root))
        assertEquals(done.branch, branchOf(root.resolve("app")))
        assertEquals("HEAD", branchOf(root.resolve("tools/cli")))
        assertEquals("HEAD", branchOf(root.resolve("lab")))
        assertEquals(gitIn(main().resolve("tools/cli"), "rev-parse", "main").trim(), gitIn(root.resolve("tools/cli"), "rev-parse", "HEAD").trim())
        assertEquals(1, gitIn(root.resolve("app"), "rev-list", "--count", "dev..HEAD").trim().toInt())
        assertEquals("", gitIn(root, "status", "--porcelain").trim(), "member worktrees are excluded in the root")
        assertEquals(4, registered().size, registered().toString())

        runBlocking { s.orchestrator.cancel(alice, run.id) }
        assertTrue(registered().isEmpty(), registered().toString())
        assertFalse(Files.exists(root))
    }

    @Test
    fun `a failed run is cleaned up, and a step retry re-creates the layout`() {
        script()
        world.realGitOps!!.failPush = "remote rejected"
        val run = start()
        val failed = await(run.id)
        assertEquals(RunStatus.FAILED, failed.status)
        assertEquals(FailedStep.PUSH, failed.failedStep)
        assertTrue(registered().isEmpty(), registered().toString())
        assertFalse(Files.exists(runDir(run.id)))
        // The unpushed run branch is kept in the member clone, so the step retry starts from it.
        assertTrue(gitIn(main().resolve("app"), "branch", "--format=%(refname:short)").lines().contains(failed.branch))
        world.realGitOps!!.failPush = null
        script(approver = "ESCALATE")
        runBlocking { s.orchestrator.retry(alice, run.id, RetryMode.STEP) }
        val again = await(run.id)
        assertEquals(RunStatus.NEEDS_HUMAN, again.status, again.error)
        assertEquals(failed.branch, branchOf(runDir(run.id).resolve("app")))
        assertTrue(Files.isDirectory(runDir(run.id).resolve("tools/cli")))
        runBlocking { s.orchestrator.cancel(alice, run.id) }
    }

    @Test
    fun `continuing a run re-creates the layout from its branch`() {
        script(approver = "ESCALATE")
        val first = start()
        await(first.id)
        runBlocking { s.orchestrator.cancel(alice, first.id) }
        val next = runBlocking { s.orchestrator.retry(alice, first.id, RetryMode.CONTINUE) }
        val done = await(next.id)
        assertEquals(RunStatus.NEEDS_HUMAN, done.status, done.error)
        val app = runDir(next.id).resolve("app")
        assertEquals(done.branch, branchOf(app))
        // Two commits: the first run's and this one's.
        assertEquals(2, gitIn(app, "rev-list", "--count", "dev..HEAD").trim().toInt())
        runBlocking { s.orchestrator.cancel(alice, next.id) }
    }

    @Test
    fun `a symbolic link at a member path is never followed`() {
        script()
        chatCheckout(alice, 3)
        // The workspace repository gains a symbolic link where the member `app` goes (written with plumbing: main/app is
        // Agern's clone of demo-ws-app on disk).
        val outside = Files.createTempDirectory("agern-outside")
        Files.writeString(outside.resolve("keep.txt"), "keep")
        val blob = ProcessBuilder("git", "hash-object", "-w", "--stdin").directory(main().toFile()).start().let { p ->
            p.outputStream.use { it.write(outside.toString().toByteArray()) }
            p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
        }
        gitIn(main(), "update-index", "--add", "--cacheinfo", "120000,$blob,app")
        gitIn(main(), "commit", "-q", "-m", "Link app elsewhere")
        val failed = await(start().id)
        assertEquals(RunStatus.FAILED, failed.status)
        assertTrue(failed.error!!.contains("occupied"), failed.error)
        assertEquals("keep", Files.readString(outside.resolve("keep.txt")), "clean-up never follows the link")
        assertFalse(Files.exists(runDir(failed.id)))
        outside.toFile().deleteRecursively()
    }

    @Test
    fun `the reaper removes member worktrees of ended runs`() {
        script()
        await(start().id)
        // A leftover of a run that is not in the database (e.g. after a crash), with worktrees in two member clones.
        val ghost = UUID.randomUUID()
        val id = AgentIdentity("alice", "Alice", "alice", "fake-alice", ClaudeCredential("oauth_token", "x"))
        s.workspaces.prepareRunCheckout(
            id, ws, ghost, "main", MemberCheckout("app", "demo-ws-app", "dev"), "factory/ghost", null,
            listOf(MemberCheckout("tools/cli", "demo-ws-tools", "main")),
        )
        assertEquals(3, registered().size)
        assertTrue(s.orchestrator.reapWorktrees().contains(ghost))
        assertTrue(registered().isEmpty(), registered().toString())
        assertFalse(Files.exists(runDir(ghost)))
    }
}
