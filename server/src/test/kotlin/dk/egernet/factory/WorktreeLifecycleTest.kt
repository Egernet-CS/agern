package dk.egernet.factory

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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Worktrees and local branches of ended runs never block a later run of the same issue (docs/ARCHITECTURE.md §6
 * "Worktrees and branches"). Real git in a temp dir (fake-GitHub mode: local clone, no pushes).
 */
class WorktreeLifecycleTest {
    private val world = TestWorld(realGit = true)
    private val s = world.services
    private val alice = world.user("alice")
    private val git = world.cliGit!!
    private val main: Path get() = s.workspaces.mainDir("alice", "demo-app")
    private val home: Path get() = s.workspaces.home("alice")

    @AfterTest fun tearDown() = world.close()

    /** Every role passes; the builder commits unless [builderCommits] is false; [hangRole] blocks until killed. */
    private fun script(builderCommits: Boolean = true, approver: String = "APPROVE", hangRole: String? = null) {
        world.launcher.stage = { p ->
            val role = p.spec.kind
            when {
                role == hangRole -> { p.init(); while (!p.killed) Thread.sleep(10) }
                role == "spec-writer" -> { p.init(); p.result("## Spec\nDo the thing."); p.exit(0) }
                role == "builder" -> {
                    p.init()
                    if (builderCommits) gitIn(p.spec.workDir, "commit", "--allow-empty", "-q", "-m", "work")
                    p.result(if (builderCommits) "Built it." else "Blocked on #19: nothing to build yet.")
                    p.exit(0)
                }
                role == "approver" -> {
                    p.init(); p.result(null, buildJsonObject { put("verdict", approver); put("summary", "approver: $approver") }); p.exit(0)
                }
                else -> {
                    p.init()
                    p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
                    p.exit(0)
                }
            }
        }
    }

    private fun gitIn(dir: Path, vararg args: String): String {
        val pb = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid") + args)
            .directory(dir.toFile()).redirectErrorStream(true)
        pb.environment()["HOME"] = home.toString()
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${args.joinToString(" ")} failed: $out" }
        return out
    }

    private fun start(issue: Int = 1) = runBlocking { s.orchestrator.start(alice, "demo-app", issue, null) }
    private fun await(id: UUID) = world.awaitRun(id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
    private fun runWorktrees() = git.listWorktrees(main, home).filter { it.path.parent?.fileName?.toString() == "runs" }
    private fun localBranches() = gitIn(main, "branch", "--format=%(refname:short)").lines().filter { it.isNotBlank() }
    private fun runDir(id: UUID) = s.workspaces.runDir("alice", "demo-app", id)

    private fun awaitBuilderRunning() {
        val deadline = System.currentTimeMillis() + 5000
        while (world.launcher.byRole("builder").isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    @Test
    fun `every run gets its own branch, and a merged run leaves no worktree or local branch`() {
        script()
        val run = await(start().id)
        assertEquals(RunStatus.MERGED, run.status, run.error)
        assertTrue(run.branch!!.matches(Regex("factory/issue-1-.+-${run.id.toString().take(8)}")), run.branch)
        assertTrue(runWorktrees().isEmpty(), runWorktrees().toString())
        assertFalse(Files.exists(runDir(run.id)))
        assertFalse(run.branch in localBranches(), localBranches().toString())
    }

    @Test
    fun `a run that failed in the builder does not block a new run of the same issue`() {
        script(builderCommits = false)
        val failed = await(start().id)
        assertEquals(RunStatus.FAILED, failed.status)
        // The builder's own explanation reaches the run and the issue.
        assertTrue(failed.error!!.contains("no commits") && failed.error!!.contains("Blocked on #19"), failed.error)
        assertTrue(s.runs.stages(failed.id).single { it.role == "builder" }.summary!!.contains("Blocked on #19"))
        assertTrue(world.github.actions.any { it.contains("run `${failed.id}` failed") && it.contains("Blocked on #19") })
        assertTrue(runWorktrees().isEmpty(), runWorktrees().toString())
        assertFalse(failed.branch in localBranches(), "a branch without own commits is deleted")

        script()
        val next = await(start().id)
        assertEquals(RunStatus.MERGED, next.status, next.error)
        assertNotEquals(failed.branch, next.branch)
    }

    @Test
    fun `cancel removes the worktree, keeps unpushed commits on the local branch, and a rerun works`() {
        script(hangRole = "security")
        val run = start()
        world.awaitRun(run.id, setOf(RunStatus.REVIEWING))
        val deadline = System.currentTimeMillis() + 5000
        while (world.launcher.byRole("security").isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val cancelled = runBlocking { s.orchestrator.cancel(alice, run.id) }
        assertEquals(RunStatus.CANCELLED, cancelled.status)
        assertTrue(runWorktrees().isEmpty(), runWorktrees().toString())
        assertFalse(Files.exists(runDir(run.id)))
        // Local-only mode never pushes, so the builder's commit exists nowhere else: the branch is kept (it blocks nothing).
        assertTrue(cancelled.branch in localBranches(), localBranches().toString())

        script()
        val next = await(start().id)
        assertEquals(RunStatus.MERGED, next.status, next.error)
    }

    @Test
    fun `needs_human keeps its worktree and blocks a second run until it is cancelled`() {
        script(approver = "ESCALATE")
        val waiting = await(start().id)
        assertEquals(RunStatus.NEEDS_HUMAN, waiting.status)
        assertTrue(Files.isDirectory(runDir(waiting.id)))

        val e = assertFailsWith<ApiException> { start() }
        assertEquals(409, e.status.value)
        assertEquals("active_run_exists", e.code)
        // A run of another issue of the same repository starts, and its start-up reaping leaves the live worktree alone.
        script()
        assertEquals(RunStatus.MERGED, await(start(2).id).status)
        assertTrue(Files.isDirectory(runDir(waiting.id)))
        assertEquals(listOf(waiting.id.toString()), runWorktrees().map { it.path.fileName.toString() })

        runBlocking { s.orchestrator.cancel(alice, waiting.id) }
        assertTrue(runWorktrees().isEmpty(), runWorktrees().toString())
        val next = await(start().id)
        assertEquals(RunStatus.MERGED, next.status, next.error)
    }

    @Test
    fun `a stale worktree left by a crash does not stop a run from starting`() {
        script(hangRole = "builder")
        val crashed = start()
        world.awaitRun(crashed.id, setOf(RunStatus.BUILDING))
        awaitBuilderRunning()
        // Simulated crash: the coroutine dies without any clean-up, the database says the run ended.
        runBlocking { s.orchestrator.job(crashed.id)?.let { it.cancel(); it.join() } }
        s.runs.setStatus(crashed.id, RunStatus.FAILED, "crashed")
        assertTrue(Files.isDirectory(runDir(crashed.id)))
        // Plus a worktree of a run the database does not know, and a bare directory git does not know.
        val ghost = UUID.randomUUID()
        gitIn(main, "worktree", "add", "-q", "-b", "factory/issue-1-ghost", runDir(ghost).toString(), "HEAD")
        val bare = runDir(UUID.randomUUID())
        Files.createDirectories(bare.resolve("junk"))

        script()
        val next = await(start().id)
        assertEquals(RunStatus.MERGED, next.status, next.error)
        assertTrue(runWorktrees().isEmpty(), runWorktrees().toString())
        assertFalse(Files.exists(runDir(crashed.id)))
        assertFalse(Files.exists(runDir(ghost)))
        assertFalse(Files.exists(bare))
    }

    @Test
    fun `the reaper at server start removes worktrees of ended runs and keeps live ones`() {
        script(approver = "ESCALATE")
        val waiting = await(start().id)
        assertEquals(RunStatus.NEEDS_HUMAN, waiting.status)

        script(hangRole = "builder")
        val interrupted = start(2)
        world.awaitRun(interrupted.id, setOf(RunStatus.BUILDING))
        awaitBuilderRunning()
        // The process is gone after a restart: no coroutine survives.
        runBlocking { s.orchestrator.job(interrupted.id)?.let { it.cancel(); it.join() } }
        assertTrue(Files.isDirectory(runDir(interrupted.id)))
        val orphan = runDir(UUID.randomUUID())
        Files.createDirectories(orphan)

        s.recoverAfterRestart()
        assertEquals(RunStatus.FAILED, s.runs.get(interrupted.id)!!.status)
        assertFalse(Files.exists(runDir(interrupted.id)))
        assertFalse(Files.exists(orphan))
        assertTrue(Files.isDirectory(runDir(waiting.id)), "needs_human runs keep their worktree")
        assertEquals(listOf(waiting.id.toString()), runWorktrees().map { it.path.fileName.toString() })
        assertEquals(RunStatus.NEEDS_HUMAN, s.runs.get(waiting.id)!!.status)
    }
}
