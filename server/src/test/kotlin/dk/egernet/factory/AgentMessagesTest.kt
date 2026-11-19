package dk.egernet.factory

import dk.egernet.factory.db.SessionRecord
import dk.egernet.factory.mcp.McpGrant
import dk.egernet.factory.mcp.McpRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Messages from a run's owner to its running spec-writer / builder (docs/ARCHITECTURE.md §6 "Messages to running agents"). */
class AgentMessagesTest {
    private val world = TestWorld()
    private val s = world.services
    private val alice = world.user("alice")
    private val bob = world.user("bob")
    private val root = world.user("root", admin = true)

    @AfterTest fun tearDown() = world.close()

    private fun review() = buildJsonObject {
        put("verdict", "PASS"); put("summary", "fine"); put("findings", kotlinx.serialization.json.JsonArray(emptyList()))
    }

    /** Everything passes; the builder is scripted by [builder]. */
    private fun script(builder: (FakeProcess) -> Unit) {
        world.launcher.stage = { p ->
            when (p.spec.kind) {
                "spec-writer" -> { p.init(); p.result("## Spec\nDo the thing."); p.exit(0) }
                "builder" -> builder(p)
                "approver" -> { p.init(); p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") }); p.exit(0) }
                else -> { p.init(); p.result(null, review()); p.exit(0) }
            }
        }
    }

    /** A builder that works until a picked-up message says "finish", acknowledging each one, then behaves like the CLI. */
    private val busyBuilder: (FakeProcess) -> Unit = { p ->
        p.init()
        p.assistantText("working")
        while (!p.killed) {
            val m = p.pickUp(50) ?: continue
            p.assistantText("noted: $m")
            if ("finish" in m) break
        }
        p.result("Built it.")
        p.followUpsUntilClosed()
    }

    private fun start() = runBlocking { s.orchestrator.start(alice, "demo-app", 1, null) }

    private fun liveSession(runId: UUID, role: String, timeoutMs: Long = 5000): SessionRecord {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            s.sessions.byRun(runId).lastOrNull { it.kind == role }?.takeIf { s.agents.isLive(it.id) }?.let { return it }
            Thread.sleep(10)
        }
        error("no live $role session")
    }

    private fun events(sessionId: UUID) = s.events.list(sessionId, 0, 1000)
    private fun payload(e: EventDto) = e.payload as JsonObject
    private fun send(user: dk.egernet.factory.db.UserRecord, rec: SessionRecord, text: String) = s.agents.sendToStageAgent(user, s.sessions.get(rec.id)!!, text)
    private fun code(block: () -> Unit) = assertFailsWith<ApiException> { block() }.let { it.status.value to it.code }

    private fun awaitEvent(sessionId: UUID, what: String, pred: (EventDto) -> Boolean): EventDto {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            events(sessionId).firstOrNull(pred)?.let { return it }
            Thread.sleep(10)
        }
        error("no event: $what")
    }

    @Test
    fun `a message reaches the running builder, is confirmed when picked up, and the stage completes`() {
        script(busyBuilder)
        val run = start()
        val b = liveSession(run.id, "builder")

        val res = send(alice, b, "the button should be blue")
        assertEquals("queued", res.status)
        assertEquals("builder", res.role)
        assertEquals(run.id.toString(), res.runId)
        // The CLI got it on stdin, as a stream-json user message that says who sent it.
        val proc = world.launcher.byRole("builder").single()
        assertTrue(proc.spec.steerable)
        val user = events(b.id).last { it.type == "user" }
        assertEquals("the button should be blue", payload(user)["text"]!!.jsonPrimitive.content)
        assertEquals("alice", payload(user)["username"]!!.jsonPrimitive.content)
        assertEquals("direct", payload(user)["origin"]!!.jsonPrimitive.content)
        assertEquals(res.messageId, payload(user)["messageId"]!!.jsonPrimitive.content)
        // Picked up by the agent: a system event with the same message id confirms delivery.
        val delivered = awaitEvent(b.id, "delivered") { it.type == "system" && payload(it)["messageId"]?.jsonPrimitive?.contentOrNull == res.messageId }
        assertTrue(payload(delivered)["text"]!!.jsonPrimitive.content.contains("delivered"))
        awaitEvent(b.id, "acknowledged") { it.type == "claude" && it.payload.toString().contains("noted: Message from the run owner (alice)") }
        // Replays only confirm delivery; they are not stored as transcript events.
        assertFalse(events(b.id).any { it.type == "claude" && (it.payload as JsonObject)["isReplay"] != null })

        send(alice, b, "finish now")
        val done = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertTrue(proc.inputClosed, "stdin is closed once the agent's turn ended with nothing waiting")
        assertEquals("Built it.", s.runs.stages(run.id).single { it.role == "builder" }.summary)
        assertEquals(SessionStatus.DONE, s.sessions.get(b.id)!!.status)
        // No GitHub traffic for forwarded messages.
        assertFalse(world.github.actions.any { it.contains("button should be blue") })

        // Finished: rejected with a clear code, and nothing is written or recorded.
        val before = events(b.id).size
        assertEquals(409 to "agent_not_running", code { send(alice, b, "one more thing") })
        assertTrue(assertFailsWith<ApiException> { send(alice, b, "x") }.message.contains("start a new run"))
        assertEquals(before, events(b.id).size)
    }

    @Test
    fun `only the owner may send, never an admin, and reviewers and the approver never accept messages`() {
        script(busyBuilder)
        val run = start()
        val b = liveSession(run.id, "builder")
        assertEquals(403 to "forbidden", code { send(bob, b, "use my words") })
        assertEquals(403 to "forbidden", code { send(root, b, "admins stop, never drive") })
        assertEquals(403 to "forbidden", code { runBlocking { s.factory.sendToAgent(root, run.id, "builder", null, "x", null) } })
        assertFalse(events(b.id).any { it.type == "user" && it.payload.toString().contains("use my words") })
        assertEquals(400 to "bad_request", code { send(alice, b, "   ") })

        // Pending stages (no agent yet) and reviewer / approver roles by run + role.
        assertEquals(409 to "agent_not_running", code { runBlocking { s.factory.sendToAgent(alice, run.id, "spec-writer", null, "late", null) } })
        for (role in REVIEWER_ROLES + "approver") {
            assertEquals(409 to "agent_does_not_accept_messages", code { runBlocking { s.factory.sendToAgent(alice, run.id, role, null, "x", null) } }, role)
        }
        send(alice, b, "finish")
        assertEquals(RunStatus.MERGED, world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        // Reviewer and approver sessions: refused by role (also by session id).
        for (role in REVIEWER_ROLES + "approver") {
            val rec = s.sessions.byRun(run.id).single { it.kind == role }
            assertEquals(409 to "agent_does_not_accept_messages", code { send(alice, rec, "x") }, role)
            assertEquals(403 to "forbidden", code { send(bob, rec, "x") }, role)
        }
        assertTrue(world.launcher.launched.filter { it.spec.kind in REVIEWER_ROLES + "approver" }.all { !it.spec.steerable && it.written.isEmpty() })
    }

    @Test
    fun `a stage without messages ends at its result as before`() {
        script { p -> p.init(); p.result("Built it."); p.followUpsUntilClosed() }
        val run = start()
        val done = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        val spec = world.launcher.byRole("spec-writer").single()
        val builder = world.launcher.byRole("builder").single()
        for (p in listOf(spec, builder)) {
            assertTrue(p.spec.steerable)
            assertTrue(p.inputClosed)
            assertEquals(1, p.written.size, "only the task prompt was written")
            val first = AppJson.parseToJsonElement(p.written.single()).jsonObject
            assertEquals("user", first["type"]!!.jsonPrimitive.content)
            assertEquals(p.spec.prompt, first["message"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a message accepted after the last step is delivered as a further turn, and the last result counts`() {
        val accepted = java.util.concurrent.CountDownLatch(1)
        script { p ->
            p.init()
            // The message arrives while the agent finishes its turn; it is not picked up mid-turn.
            accepted.await()
            while (p.inputs.isEmpty()) Thread.sleep(5)
            p.result("first answer")
            p.followUpsUntilClosed { "second answer after: ${it.substringAfter("\n\n")}" }
        }
        val run = start()
        val b = liveSession(run.id, "builder")
        val res = send(alice, b, "late change")
        accepted.countDown()
        val done = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertEquals("second answer after: late change", s.runs.stages(run.id).single { it.role == "builder" }.summary)
        assertNotNull(events(b.id).firstOrNull { it.type == "system" && payload(it)["messageId"]?.jsonPrimitive?.contentOrNull == res.messageId })
        assertEquals(2, events(b.id).count { it.type == "claude" && payload(it)["type"]?.jsonPrimitive?.content == "result" })
        assertTrue(world.launcher.byRole("builder").single().inputClosed)
    }

    @Test
    fun `a message the agent never picked up is reported, never dropped silently`() {
        script { p -> p.init(); while (!p.killed) Thread.sleep(10) }
        val run = start()
        val b = liveSession(run.id, "builder")
        val res = send(alice, b, "are you there")
        runBlocking { s.orchestrator.cancel(alice, run.id) }
        val warn = events(b.id).single { it.type == "system" && payload(it)["messageId"]?.jsonPrimitive?.contentOrNull == res.messageId }
        assertEquals("warn", payload(warn)["level"]!!.jsonPrimitive.content)
        assertTrue(payload(warn)["text"]!!.jsonPrimitive.content.contains("not delivered"))
        assertEquals(409 to "agent_not_running", code { send(alice, b, "again") })
    }

    @Test
    fun `the chat agent forwards through factory_send_to_agent, only for its owner`() {
        script(busyBuilder)
        val run = start()
        val b = liveSession(run.id, "builder")
        val chat = s.agents.createChat(alice, "demo-app", null)
        val aliceToken = s.mcpRegistry.issue(McpGrant(chat.id, alice.id, "demo-app", null, McpRegistry.CHAT_TOOLS))
        val bobChat = s.agents.createChat(bob, "demo-app", null)
        val bobToken = s.mcpRegistry.issue(McpGrant(bobChat.id, bob.id, "demo-app", null, McpRegistry.CHAT_TOOLS))
        val stageToken = s.mcpRegistry.issue(McpGrant(b.id, alice.id, "demo-app", run.id, McpRegistry.STAGE_TOOLS))

        fun call(token: String, args: JsonObject): JsonObject = runBlocking {
            val body = buildJsonObject {
                put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
                putJsonObject("params") { put("name", "factory_send_to_agent"); put("arguments", args) }
            }
            AppJson.parseToJsonElement(s.mcp.handle(token, body.toString()).second!!).jsonObject["result"]!!.jsonObject
        }
        fun text(r: JsonObject) = r["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        fun isError(r: JsonObject) = r["isError"]?.jsonPrimitive?.booleanOrNull == true

        // The tool is offered to chats only.
        val list = runBlocking { s.mcp.handle(aliceToken, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").second!! }
        assertTrue(list.contains("factory_send_to_agent"))
        assertFalse(runBlocking { s.mcp.handle(stageToken, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").second!! }.contains("factory_send_to_agent"))

        val ok = call(aliceToken, buildJsonObject { put("runId", run.id.toString()); put("role", "builder"); put("message", "tell it: blue button") })
        assertFalse(isError(ok), text(ok))
        assertTrue(text(ok).contains("Queued for the builder"), text(ok))
        val user = events(b.id).last { it.type == "user" }
        assertEquals("tell it: blue button", payload(user)["text"]!!.jsonPrimitive.content)
        assertEquals("chat", payload(user)["origin"]!!.jsonPrimitive.content)
        assertEquals(chat.id.toString(), payload(user)["viaSessionId"]!!.jsonPrimitive.content)
        // Without a role: the running spec-writer or builder. By session id works too.
        assertFalse(isError(call(aliceToken, buildJsonObject { put("runId", run.id.toString()); put("message", "no role given") })))
        assertFalse(isError(call(aliceToken, buildJsonObject { put("sessionId", b.id.toString()); put("message", "by session") })))

        // Bob's own chat agent cannot drive Alice's run; reviewers refuse; nonsense ids are explained.
        val denied = call(bobToken, buildJsonObject { put("runId", run.id.toString()); put("role", "builder"); put("message", "hijack") })
        assertTrue(isError(denied) && text(denied).contains("Only the owner"), text(denied))
        val denied2 = call(bobToken, buildJsonObject { put("sessionId", b.id.toString()); put("message", "hijack") })
        assertTrue(isError(denied2) && text(denied2).contains("Only the owner"), text(denied2))
        assertFalse(events(b.id).any { it.payload.toString().contains("hijack") })
        assertTrue(isError(call(aliceToken, buildJsonObject { put("runId", run.id.toString()); put("role", "security"); put("message", "x") })))
        assertTrue(text(call(aliceToken, buildJsonObject { put("runId", "nope"); put("message", "x") })).contains("not a valid id"))
        // A pipeline agent's token may not use it (not in its grant).
        assertTrue(isError(call(stageToken, buildJsonObject { put("runId", run.id.toString()); put("message", "x") })))

        send(alice, b, "finish")
        assertEquals(RunStatus.MERGED, world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        val late = call(aliceToken, buildJsonObject { put("runId", run.id.toString()); put("role", "builder"); put("message", "too late") })
        assertTrue(isError(late) && text(late).contains("start a new run"), text(late))
    }
}
