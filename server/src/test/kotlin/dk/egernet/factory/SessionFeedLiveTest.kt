package dk.egernet.factory

import dk.egernet.factory.agent.FeedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The feed item that carries a session's final status must already say `live: false`: clients keep the last item they
 * got, and `live` alone makes a session count as active ("N active", Agents page) until a reload.
 */
class SessionFeedLiveTest {
    private var world = TestWorld()
    private val s get() = world.services
    private val alice get() = world.user("alice")
    private val frames = CopyOnWriteArrayList<AgentSessionDto>()
    private var collector: Job? = null

    @AfterTest fun tearDown() {
        runBlocking { collector?.cancelAndJoin() }
        world.close()
    }

    private val ended = setOf(SessionStatus.DONE, SessionStatus.FAILED, SessionStatus.STOPPED)

    /** Starts collecting session feed items; call before the run or chat starts. */
    private fun collect() {
        collector = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            s.bus.feed.collect { if (it is FeedItem.Session) frames += it.session }
        }
    }

    private fun framesOf(id: UUID) = frames.filter { it.id == id.toString() }

    private fun awaitFrame(id: UUID, what: String, p: (AgentSessionDto) -> Boolean) = runBlocking {
        try {
            withTimeout(10_000) { while (framesOf(id).none(p)) delay(10) }
        } catch (e: Exception) {
            throw AssertionError("No feed item for $id: $what; got ${framesOf(id).map { it.status to it.live }}", e)
        }
    }

    /** The last item for [id] has [status] and is not live; no item combines an ended status with `live: true`. */
    private fun assertEndsNotLive(id: UUID, status: String) {
        awaitFrame(id, status) { it.status == status }
        val mine = framesOf(id)
        assertTrue(mine.none { it.status in ended && it.live }, "ended and live: ${mine.map { it.status to it.live }}")
        assertEquals(status, mine.last().status)
        assertFalse(mine.last().live, "last feed item is not live")
        assertFalse(s.agents.isLive(id))
        assertFalse(s.agents.isActive(s.sessions.get(id)!!))
    }

    private fun script(failRole: String? = null, hangRole: String? = null) {
        world.launcher.stage = { p ->
            val role = p.spec.kind
            when {
                role == failRole -> { p.init(); p.exit(1) }
                role == hangRole -> { p.init(); while (!p.killed) Thread.sleep(10) }
                role == "spec-writer" -> { p.init(); p.result("## Spec\nDo the thing."); p.exit(0) }
                role == "builder" -> { p.init(); p.result("Built it."); p.exit(0) }
                role == "approver" -> {
                    p.init(); p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") }); p.exit(0)
                }
                else -> {
                    p.init()
                    p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray {}) })
                    p.exit(0)
                }
            }
        }
    }

    private fun start() = runBlocking { s.orchestrator.start(alice, "demo-app", 1, null) }

    private fun stageSession(runId: UUID, role: String) = s.sessions.byRun(runId).single { it.kind == role }.id

    @Test
    fun `finished stage agents are announced as not live`() {
        script()
        collect()
        val run = start()
        world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        val stages = s.sessions.byRun(run.id)
        assertTrue(stages.map { it.kind }.containsAll(listOf("spec-writer", "builder", "approver")), stages.map { it.kind }.toString())
        for (st in stages) {
            assertEndsNotLive(st.id, SessionStatus.DONE)
            assertTrue(framesOf(st.id).any { it.status == SessionStatus.RUNNING && it.live }, "the live phase of ${st.kind} was seen")
        }
        assertTrue(s.sessions.byRun(run.id).none { s.agents.isActive(it) })
    }

    @Test
    fun `a failing stage agent is announced as not live`() {
        script(failRole = "spec-writer")
        collect()
        val run = start()
        world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEndsNotLive(stageSession(run.id, "spec-writer"), SessionStatus.FAILED)
    }

    @Test
    fun `a timed-out stage agent is announced as not live`() {
        world.close()
        world = TestWorld(tweak = { it.copy(stageTimeoutMs = 400) })
        script(hangRole = "builder")
        collect()
        val run = start()
        world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEndsNotLive(stageSession(run.id, "builder"), SessionStatus.FAILED)
    }

    @Test
    fun `a stage agent of a cancelled run is announced as not live`() {
        script(hangRole = "builder")
        collect()
        val run = start()
        world.awaitRun(run.id, setOf(RunStatus.BUILDING))
        runBlocking { withTimeout(5_000) { while (world.launcher.byRole("builder").isEmpty()) delay(10) } }
        val builder = stageSession(run.id, "builder")
        awaitFrame(builder, "running and live") { it.status == SessionStatus.RUNNING && it.live }
        runBlocking { s.orchestrator.cancel(alice, run.id) }
        assertEndsNotLive(builder, SessionStatus.STOPPED)
    }

    @Test
    fun `a chat stopped by its owner is announced as not live`() {
        collect()
        val chat = s.agents.createChat(alice, "demo-app", null)
        s.agents.sendInput(alice, chat, "hello")
        awaitFrame(chat.id, "idle and live") { it.status == SessionStatus.IDLE && it.live }
        runBlocking { s.agents.stop(alice, s.sessions.get(chat.id)!!) }
        assertEndsNotLive(chat.id, SessionStatus.STOPPED)
    }
}
