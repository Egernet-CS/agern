package dk.egernet.factory

import dk.egernet.factory.agent.REPO_NAME_RE
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.addJsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipelineTest {
    private val world = TestWorld()
    private val s = world.services
    private val alice = world.user("alice")

    @AfterTest fun tearDown() = world.close()

    private fun review(verdict: String) = buildJsonObject {
        put("verdict", verdict); put("summary", "$verdict summary")
        put("findings", buildJsonArray {
            if (verdict == "CHANGES") addJsonObject { put("severity", "major"); put("file", "a.kt"); put("detail", "fix it") }
        })
    }

    /** Default behaviour: everything passes. [reviewVerdict] decides per (role, prompt). */
    private fun script(
        reviewVerdict: (String, String) -> String = { _, _ -> "PASS" },
        approverVerdict: String = "APPROVE",
        failRole: String? = null,
        hangRole: String? = null,
    ) {
        world.launcher.stage = { p ->
            val role = p.spec.kind
            when {
                role == failRole -> { p.init(); p.exit(1) }
                role == hangRole -> { p.init(); while (!p.killed) Thread.sleep(10) }
                role == "spec-writer" -> { p.init(); p.result("## Spec\nDo the thing."); p.exit(0) }
                role == "builder" -> { p.init(); p.result("Built it."); p.exit(0) }
                role == "approver" -> {
                    p.init(); p.result(null, buildJsonObject { put("verdict", approverVerdict); put("summary", "approver says $approverVerdict") }); p.exit(0)
                }
                else -> { p.init(); p.result(null, review(reviewVerdict(role, p.spec.prompt ?: ""))); p.exit(0) }
            }
        }
    }

    private fun start(issue: Int = 1) = runBlocking { s.orchestrator.start(alice, "demo-app", issue, null) }

    @Test
    fun `all PASS then APPROVE merges`() {
        script()
        val run = start()
        val done = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertEquals(0, done.round)
        assertNotNull(done.prNumber)
        assertTrue(done.branch!!.startsWith("factory/issue-1-"))
        assertTrue(REPO_NAME_RE.matches("demo-app"))
        val actions = world.github.actions.toList()
        assertTrue(actions.any { it.startsWith("comment demo-app#1 **Agern: spec ready**") }, actions.toString())
        assertTrue(actions.any { it.startsWith("pr demo-app factory/issue-1-") })
        assertTrue(actions.any { it.startsWith("merge demo-app #") })
        assertTrue(actions.any { it == "close demo-app#1" })
        assertEquals(listOf(done.branch), world.git.pushes.toList())
        // The pushed head is recorded, and as reviewed once all four reviewers returned a verdict.
        assertEquals("head-1", done.headSha)
        assertEquals("head-1", done.reviewedSha)
        val stages = s.runs.stages(run.id)
        assertEquals(7, stages.size)
        assertTrue(stages.all { it.status == "done" }, stages.toString())
        assertEquals(setOf("PASS"), stages.filter { it.role in REVIEWER_ROLES }.map { it.verdict }.toSet())
        assertEquals("APPROVE", stages.single { it.role == "approver" }.verdict)
        // Reviewers and approver cannot edit; every stage got only the two stage MCP tools and ran with alice's credential.
        for (p in world.launcher.launched) {
            assertEquals("alice", p.spec.identity.username)
            assertEquals("sk-ant-oat-alice-secret", p.spec.identity.claude.value)
            if (p.spec.kind != "builder") assertEquals(listOf("Edit", "Write", "NotebookEdit"), p.spec.disallowedTools)
        }
        assertNotNull(world.launcher.byRole("security").single().spec.jsonSchema)
        // Sessions are recorded per stage and finished.
        assertTrue(s.sessions.byRun(run.id).all { it.status == SessionStatus.DONE })
    }

    @Test
    fun `CHANGES then PASS resumes the same builder conversation`() {
        script(reviewVerdict = { role, prompt -> if (role == "code" && !prompt.contains("after fix round")) "CHANGES" else "PASS" })
        val run = start()
        val done = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertEquals(1, done.round)
        val builders = world.launcher.byRole("builder")
        assertEquals(2, builders.size)
        assertNull(builders[0].spec.resumeClaudeSessionId)
        val firstClaudeSession = s.sessions.byRun(run.id).first { it.kind == "builder" }.claudeSessionId
        assertNotNull(firstClaudeSession)
        assertEquals(firstClaudeSession, builders[1].spec.resumeClaudeSessionId)
        assertTrue(builders[1].spec.prompt!!.contains("fix it"), "findings are handed to the builder")
        assertEquals(8, world.launcher.launched.count { it.spec.kind in REVIEWER_ROLES })
        val stages = s.runs.stages(run.id)
        assertEquals("CHANGES", stages.single { it.role == "code" && it.round == 0 }.verdict)
        assertEquals("PASS", stages.single { it.role == "code" && it.round == 1 }.verdict)
        assertEquals(1, stages.single { it.role == "approver" }.round)
        // Only one PR although the branch was pushed twice.
        assertEquals(1, world.github.actions.count { it.startsWith("pr ") })
        assertEquals(2, world.git.pushes.size)
    }

    @Test
    fun `CHANGES after max rounds goes to needs_human`() {
        script(reviewVerdict = { role, _ -> if (role == "security") "CHANGES" else "PASS" })
        val run = start()
        val done = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.NEEDS_HUMAN, done.status)
        assertEquals(2, done.round)
        assertEquals(3, world.launcher.byRole("builder").size)
        assertTrue(world.launcher.byRole("approver").isEmpty())
        assertEquals("skipped", s.runs.stages(run.id).single { it.role == "approver" }.status)
        assertTrue(world.github.actions.any { it == "label demo-app#1 needs-human" })
        assertTrue(done.error!!.contains("security"))
    }

    @Test
    fun `ESCALATE then owner approve merges, others may not`() {
        script(approverVerdict = "ESCALATE")
        val run = start()
        val waiting = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.NEEDS_HUMAN, waiting.status)
        assertEquals("ESCALATE", s.runs.stages(run.id).single { it.role == "approver" }.verdict)

        val bob = world.user("bob")
        val root = world.user("root", admin = true)
        assertEquals(403, assertFailsWith<ApiException> { runBlocking { s.orchestrator.approve(bob, run.id) } }.status.value)
        assertEquals(403, assertFailsWith<ApiException> { runBlocking { s.orchestrator.approve(root, run.id) } }.status.value, "admins may stop, never drive")

        val merged = runBlocking { s.orchestrator.approve(alice, run.id) }
        assertEquals(RunStatus.MERGED, merged.status)
        assertNull(merged.error)
        assertTrue(world.github.actions.any { it == "unlabel demo-app#1 needs-human" })
        assertEquals(409, assertFailsWith<ApiException> { runBlocking { s.orchestrator.approve(alice, run.id) } }.status.value)
    }

    @Test
    fun `cancel kills the running agent`() {
        script(hangRole = "builder")
        val run = start()
        world.awaitRun(run.id, setOf(RunStatus.BUILDING))
        // wait until the builder process exists
        val deadline = System.currentTimeMillis() + 5000
        while (world.launcher.byRole("builder").isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val bob = world.user("bob")
        assertEquals(403, assertFailsWith<ApiException> { runBlocking { s.orchestrator.cancel(bob, run.id) } }.status.value)
        val cancelled = runBlocking { s.orchestrator.cancel(alice, run.id) }
        assertEquals(RunStatus.CANCELLED, cancelled.status)
        assertTrue(world.launcher.byRole("builder").single().killed)
        val stages = s.runs.stages(run.id)
        assertEquals("failed", stages.single { it.role == "builder" }.status)
        assertTrue(stages.filter { it.role in REVIEWER_ROLES }.all { it.status == "skipped" })
        assertEquals(SessionStatus.STOPPED, s.sessions.byRun(run.id).single { it.kind == "builder" }.status)
        assertNull(s.runs.activeRunFor("demo-app", 1))
    }

    @Test
    fun `a failing stage fails the run`() {
        script(failRole = "spec-writer")
        val run = start()
        val done = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.FAILED, done.status)
        assertTrue(done.error!!.contains("spec-writer"), done.error)
        val stages = s.runs.stages(run.id)
        assertEquals("failed", stages.single { it.role == "spec-writer" }.status)
        assertTrue(stages.filter { it.role != "spec-writer" }.all { it.status == "skipped" })
        assertTrue(world.launcher.byRole("builder").isEmpty())
    }

    @Test
    fun `builder without commits fails the run`() {
        script()
        world.git.commits = 0
        val run = start()
        val done = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.FAILED, done.status)
        assertTrue(done.error!!.contains("no commits"))
    }

    @Test
    fun `second run for the same issue is rejected while one is active, and a credential is required`() {
        script(hangRole = "spec-writer")
        val run = start()
        assertEquals(409, assertFailsWith<ApiException> { start() }.status.value)
        val carol = world.user("carol", claude = false)
        assertEquals(422, assertFailsWith<ApiException> { runBlocking { s.orchestrator.start(carol, "demo-app", 2, null) } }.status.value)
        runBlocking { s.orchestrator.cancel(alice, run.id) }
    }

    @Test
    fun `stages carry the model they run with`() {
        script()
        val run = start()
        val done = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        val dto = s.runs.toDto(done)
        assertEquals(DEFAULT_PIPELINE_MODELS, dto.stages.associate { it.role to it.model })
        assertTrue(world.launcher.launched.all { p -> p.spec.model == DEFAULT_PIPELINE_MODELS[p.spec.kind] })
    }

    @Test
    fun `restart marks unfinished runs failed`() {
        script(hangRole = "spec-writer")
        val run = start()
        world.awaitRun(run.id, setOf(RunStatus.SPEC))
        s.recoverAfterRestart()
        val r = s.runs.get(run.id)!!
        assertEquals(RunStatus.FAILED, r.status)
        assertTrue(r.error!!.contains("restarted"))
        runBlocking { s.orchestrator.job(run.id)?.cancel() }
    }
}
