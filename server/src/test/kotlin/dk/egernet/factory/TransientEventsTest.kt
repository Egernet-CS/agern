package dk.egernet.factory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
 * Progress-only events of the CLI (`system/thinking_tokens` about every 100 thinking tokens, `tool_progress` heartbeats
 * every few seconds of a running tool call) are sent live, like `stream_event` partials, and never stored: a run's log
 * would fill with them.
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

    private fun toolProgress(elapsed: Int) = buildJsonObject {
        put("type", "tool_progress"); put("tool_use_id", "toolu_wait"); put("tool_name", "mcp__factory__factory_sandbox_wait")
        put("parent_tool_use_id", JsonNull); put("elapsed_time_seconds", elapsed)
        put("heartbeat", true); put("uuid", "u-$elapsed"); put("session_id", "fake")
    }

    /** Runs a pipeline whose spec-writer emits [progress] before its result; returns the spec-writer's stored events. */
    private fun runWithProgress(progress: List<JsonObject>): List<EventDto> {
        world.launcher.stage = { p ->
            if (p.spec.kind == "spec-writer") {
                // Subscribed before the first line, on the stage's own thread: every live frame is seen.
                collectors += CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
                    s.bus.session(p.spec.sessionId).collect { live += it }
                }
            }
            p.init()
            if (p.spec.kind == "spec-writer") progress.forEach { p.emit(it) }
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
        val stored = s.events.list(spec.id, 0, 1000)
        // The stage's other output is still stored as usual.
        assertTrue(stored.any { it.type == "claude" && it.payload.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "result" })
        assertTrue(stored.any { it.type == "claude" && it.payload.jsonObject["subtype"]?.jsonPrimitive?.contentOrNull == "init" })
        return stored
    }

    @Test
    fun `thinking_tokens events are emitted live but not stored`() {
        val stored = runWithProgress(listOf(thinkingTokens(100), thinkingTokens(200), thinkingTokens(300)))
        fun isThinkingTokens(e: EventDto) = e.type == "claude" &&
            e.payload.jsonObject["subtype"]?.jsonPrimitive?.contentOrNull == "thinking_tokens"
        val liveTokens = live.filter(::isThinkingTokens)
        assertEquals(listOf(100, 200, 300), liveTokens.map { it.payload.jsonObject["estimated_tokens"]!!.jsonPrimitive.content.toInt() })
        assertTrue(liveTokens.all { it.seq == -1L }, "sent as live-only frames")
        assertTrue(stored.none(::isThinkingTokens), "not stored: ${stored.map { it.payload }}")
    }

    @Test
    fun `tool_progress heartbeats are emitted live but not stored`() {
        // The last one lacks the optional fields: still recognised by its type alone.
        val bare = buildJsonObject { put("type", "tool_progress") }
        val stored = runWithProgress(listOf(toolProgress(5), toolProgress(10), toolProgress(15), bare))
        fun isToolProgress(e: EventDto) = e.type == "claude" &&
            e.payload.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool_progress"
        val liveProgress = live.filter(::isToolProgress)
        assertEquals(4, liveProgress.size)
        assertEquals(listOf("5", "10", "15"), liveProgress.take(3).map { it.payload.jsonObject["elapsed_time_seconds"]!!.jsonPrimitive.content })
        assertTrue(liveProgress.all { it.seq == -1L }, "sent as live-only frames")
        assertTrue(stored.none(::isToolProgress), "not stored: ${stored.map { it.payload }}")
    }
}
