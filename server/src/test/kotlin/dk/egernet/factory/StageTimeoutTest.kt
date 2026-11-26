package dk.egernet.factory

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StageTimeoutTest {
    private val world = TestWorld(tweak = { it.copy(stageTimeoutMs = 400) })
    private val s = world.services
    private val alice = world.user("alice")

    @AfterTest fun tearDown() = world.close()

    @Test
    fun `a stage that exceeds the wall-clock limit is stopped and fails the run`() {
        world.launcher.stage = { p ->
            when (p.spec.kind) {
                "builder" -> { p.init(); while (!p.killed) Thread.sleep(10) }
                else -> { p.init(); p.result("## Spec\nDo it."); p.exit(0) }
            }
        }
        val run = runBlocking { s.orchestrator.start(alice, "demo-app", 1, null) }
        val done = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.FAILED, done.status)
        assertTrue(done.error!!.contains("builder") && done.error!!.contains("Timed out"), done.error)
        assertTrue(done.error!!.contains("FACTORY_STAGE_TIMEOUT_MINUTES"), done.error)
        assertTrue(world.launcher.byRole("builder").single().killed, "the agent process was killed")
        val stages = s.runs.stages(run.id)
        assertEquals("done", stages.single { it.role == "spec-writer" }.status)
        assertEquals("failed", stages.single { it.role == "builder" }.status)
        assertTrue(stages.single { it.role == "builder" }.summary!!.contains("Timed out"))
        assertTrue(stages.filter { it.role in REVIEWER_ROLES + "approver" }.all { it.status == "skipped" })
        assertEquals(SessionStatus.FAILED, s.sessions.byRun(run.id).single { it.kind == "builder" }.status)
        val events = s.events.list(s.sessions.byRun(run.id).single { it.kind == "builder" }.id, 0, 100)
        assertTrue(events.any { it.type == "system" && it.payload.toString().contains("time limit") })
    }

    @Test
    fun `the limit is configured in minutes`() {
        val base = mapOf("FACTORY_MASTER_KEY" to java.util.Base64.getEncoder().encodeToString(ByteArray(32)), "FACTORY_GITHUB_ORG" to "o")
        assertEquals(60 * 60_000L, Config.fromEnv(base).stageTimeoutMs)
        assertEquals(5 * 60_000L, Config.fromEnv(base + ("FACTORY_STAGE_TIMEOUT_MINUTES" to "5")).stageTimeoutMs)
        kotlin.test.assertFailsWith<IllegalArgumentException> { Config.fromEnv(base + ("FACTORY_STAGE_TIMEOUT_MINUTES" to "0")) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { Config.fromEnv(base + ("FACTORY_STAGE_TIMEOUT_MINUTES" to "soon")) }
    }

    @Test
    fun `the network wait is configured in hours`() {
        val base = mapOf("FACTORY_MASTER_KEY" to java.util.Base64.getEncoder().encodeToString(ByteArray(32)), "FACTORY_GITHUB_ORG" to "o")
        assertEquals(24 * 3_600_000L, Config.fromEnv(base).networkRetryMaxMs)
        assertEquals(0L, Config.fromEnv(base + ("FACTORY_NETWORK_RETRY_HOURS" to "0")).networkRetryMaxMs)
        assertEquals(168 * 3_600_000L, Config.fromEnv(base + ("FACTORY_NETWORK_RETRY_HOURS" to "168")).networkRetryMaxMs)
        listOf("169", "-1", "soon", "1.5").forEach { v ->
            val e = kotlin.test.assertFailsWith<IllegalArgumentException> { Config.fromEnv(base + ("FACTORY_NETWORK_RETRY_HOURS" to v)) }
            assertTrue(e.message!!.contains("FACTORY_NETWORK_RETRY_HOURS"), e.message)
        }
        assertEquals(listOf(1L, 2L, 5L, 10L, 15L).map { it * 60_000L }, Config.fromEnv(base).networkRetryDelaysMs)
    }

    @Test
    fun `the update round limit is a whole number from 0 to 10`() {
        val base = mapOf("FACTORY_MASTER_KEY" to java.util.Base64.getEncoder().encodeToString(ByteArray(32)), "FACTORY_GITHUB_ORG" to "o")
        assertEquals(2, Config.fromEnv(base).maxUpdateRounds)
        assertEquals(0, Config.fromEnv(base + ("FACTORY_MAX_UPDATE_ROUNDS" to "0")).maxUpdateRounds)
        assertEquals(10, Config.fromEnv(base + ("FACTORY_MAX_UPDATE_ROUNDS" to "10")).maxUpdateRounds)
        listOf("11", "-1", "two", "1.5").forEach { v ->
            val e = kotlin.test.assertFailsWith<IllegalArgumentException> { Config.fromEnv(base + ("FACTORY_MAX_UPDATE_ROUNDS" to v)) }
            assertTrue(e.message!!.contains("FACTORY_MAX_UPDATE_ROUNDS"), e.message)
        }
    }
}
