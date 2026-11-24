package dk.egernet.factory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Progress-only `system` events of the CLI (`thinking_tokens`, about every 100 thinking tokens) are sent live, like
 * `stream_event` partials, and never stored: a run's log would fill with them.
 */
class TransientEventsTest {
    private val world = TestWorld()
    private val s get() = world.services
    private val alice get() = world.user("alice")
    private val live = CopyOnWriteArrayList<EventDto>()
    private val collectors = CopyOnWriteArrayList<Job>()

    @AfterTest fun tearDown() {
        runBlocking { collectors.forEach { it.cancelAndJoin() } }
        world.close()
    }

    private fun thinkingTokens(estimated: Int) = buildJsonObject {
        put("type", "system"); put("subtype", "thinking_tokens")
        put("estimated_tokens", estimated); put("estimated_tokens_delta", 100); put("session_id", "fake")
    }

    @Test
    fun `thinking_tokens events are emitted live but not stored`() {
        world.launcher.stage = { p ->
            if (p.spec.kind == "spec-writer") {
                // Subscribed before the first line, on the stage's own thread: every live frame is seen.
                collectors += CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
                    s.bus.session(p.spec.sessionId).collect { live += it }
                }
            }
            p.init()
            if (p.spec.kind == "spec-writer") {
                p.emit(thinkingTokens(100))
                p.emit(thinkingTokens(200))
                p.emit(thinkingTokens(300))
            }
            when (p.spec.kind) {
                "spec-writer" -> p.result("## Spec")
                "builder" -> p.result("Built.")
                "approver" -> p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") })
                else -> p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
            }
            p.exit(0)
        }
        val r = runBlocking { s.orchestrator.start(alice, "demo-app", 1, null) }
        val done = world.awaitRun(r.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)

        val spec = s.sessions.byRun(r.id).first { it.kind == "spec-writer" }
        fun isThinkingTokens(e: EventDto) = e.type == "claude" &&
            e.payload.jsonObject["subtype"]?.jsonPrimitive?.contentOrNull == "thinking_tokens"
        val liveTokens = live.filter(::isThinkingTokens)
        assertEquals(listOf(100, 200, 300), liveTokens.map { it.payload.jsonObject["estimated_tokens"]!!.jsonPrimitive.content.toInt() })
        assertTrue(liveTokens.all { it.seq == -1L }, "sent as live-only frames")
        val stored = s.events.list(spec.id, 0, 1000)
        assertTrue(stored.none(::isThinkingTokens), "not stored: ${stored.map { it.payload }}")
        // The stage's other output is still stored as usual.
        assertTrue(stored.any { it.type == "claude" && it.payload.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "result" })
        assertTrue(stored.any { it.type == "claude" && it.payload.jsonObject["subtype"]?.jsonPrimitive?.contentOrNull == "init" })
    }
}
