package dk.egernet.factory

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Closing a chat: `DELETE /api/sessions/{id}` (docs/ARCHITECTURE.md §8). */
class ChatCloseTest {
    private val world = TestWorld(twoFactor = false)
    private val s = world.services

    @AfterTest fun tearDown() = world.close()

    private fun json(r: String) = AppJson.parseToJsonElement(r)
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String): HttpClient {
        val c = createClient { install(HttpCookies); install(WebSockets) }
        assertEquals(200, c.post("/api/auth/login") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
        }.status.value)
        c.put("/api/me/github-token") { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"token":"fake-$user"}""") }
        c.put("/api/me/claude-credential") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"kind":"oauth_token","value":"sk-ant-oat-$user"}""")
        }
        return c
    }

    private suspend fun HttpClient.postJson(path: String, body: String = "{}"): HttpResponse =
        post(path) { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.closeChat(sid: String): HttpResponse = delete("/api/sessions/$sid") { header("X-Factory-Request", "1") }

    private suspend fun HttpClient.newChat(repo: String = "demo-app") = json(postJson("/api/projects/$repo/sessions").bodyAsText()).jsonObject.str("id")

    private suspend fun HttpClient.send(sid: String, text: String, ids: List<String> = emptyList()) = postJson(
        "/api/sessions/$sid/input",
        """{"text":${AppJson.encodeToString(kotlinx.serialization.serializer<String>(), text)},"attachmentIds":[${ids.joinToString(",") { "\"$it\"" }}]}""",
    )

    private fun errorCode(r: String) = json(r).jsonObject["error"]!!.jsonObject.str("code")

    /** Waits up to 10 s for [cond]; [what] names it in the timeout failure. */
    private suspend fun waitFor(what: String, cond: suspend () -> Boolean) {
        val ok = kotlinx.coroutines.withTimeoutOrNull(10_000) { while (!cond()) delay(20); true }
        assertTrue(ok == true, "timed out waiting for: $what")
    }

    private suspend fun eventCount(c: HttpClient, sid: String) = json(c.get("/api/sessions/$sid/events").bodyAsText()).jsonArray.size

    private fun gone(sid: String) {
        val id = UUID.fromString(sid)
        assertNull(s.sessions.get(id), "row deleted")
        assertTrue(s.events.list(id, 0, 10).isEmpty(), "events cascade")
        assertTrue(s.attachments.store.forSession(id, sentOnly = false).isEmpty(), "attachment rows cascade")
        assertFalse(Files.exists(s.attachments.dir(id)), "attachment directory removed")
    }

    @Test
    fun `owner closes a running chat, watchers are disconnected and everything answers 404`() = testApplication {
        application { agernModule(s) }
        val alice = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        val sid = alice.newChat()
        val id = UUID.fromString(sid)
        assertEquals(202, alice.send(sid, "hello").status.value)
        waitFor("chat is live with an answer") {
            s.agents.isLive(id) && json(alice.get("/api/sessions/$sid").bodyAsText()).jsonObject.str("status") == "idle"
        }
        val proc = world.launcher.launched.single { it.spec.sessionId == id }

        val ws = bob.webSocketSession("/api/ws/sessions/$sid?after=0")
        val feed = bob.webSocketSession("/api/ws/feed")
        withTimeout(10_000) { while (s.bus.feedSubscribers.value < 1) delay(10) }
        // The replay arrives first: the socket is subscribed once it has been received.
        val persisted = eventCount(alice, sid)
        withTimeout(10_000) { repeat(persisted) { ws.incoming.receive() } }

        val r = alice.closeChat(sid)
        assertEquals(204, r.status.value, r.bodyAsText())
        assertEquals("", r.bodyAsText())
        assertTrue(proc.killed)
        assertFalse(s.agents.isLive(id))
        gone(sid)

        // The watcher's socket is closed by the server with 4404 and no sentinel frame.
        val reason = withTimeout(10_000) {
            var close: CloseReason? = null
            for (f in ws.incoming) {
                assertFalse(f is Frame.Text && f.readText().contains("\"closed\""), "no sentinel frame sent")
                if (f is Frame.Close) { close = f.readReason(); break }
            }
            close ?: ws.closeReason.await()
        }
        assertEquals(4404, reason?.code?.toInt())
        assertEquals("session closed", reason?.message)

        // Bob's feed announces it.
        val frame = withTimeout(10_000) {
            while (true) {
                val o = json((feed.incoming.receive() as? Frame.Text ?: continue).readText()).jsonObject
                if (o.str("type") == "session_closed") return@withTimeout o
            }
            @Suppress("UNREACHABLE_CODE") JsonObject(emptyMap())
        }
        assertEquals(sid, frame.str("sessionId"))
        assertEquals("demo-app", frame.str("repo"))

        for (path in listOf("/api/sessions/$sid", "/api/sessions/$sid/events")) assertEquals(404, alice.get(path).status.value, path)
        assertEquals(404, alice.send(sid, "again").status.value)
        assertEquals(404, alice.postJson("/api/sessions/$sid/stop").status.value)
        assertEquals(404, alice.postJson("/api/sessions/$sid/interrupt").status.value)
        assertEquals(404, alice.closeChat(sid).status.value)
        assertTrue(json(alice.get("/api/projects/demo-app/sessions").bodyAsText()).jsonArray.none { it.jsonObject.str("id") == sid })
        assertTrue(json(alice.get("/api/agents").bodyAsText()).jsonArray.none { it.jsonObject.str("id") == sid })

        // The owner's agent slot is free again: a new chat may start a process.
        val again = alice.newChat()
        assertEquals(202, alice.send(again, "next").status.value)
        waitFor("new chat live") { s.agents.isLive(UUID.fromString(again)) }
        feed.close()
    }

    @Test
    fun `a process that does not stop blocks the close and nothing is deleted`() = testApplication {
        application { agernModule(s) }
        val alice = login("alice", "pw-a")
        val sid = alice.newChat()
        val id = UUID.fromString(sid)
        assertEquals(202, alice.send(sid, "hello").status.value)
        waitFor("chat is live") { s.agents.isLive(id) }
        val proc = world.launcher.launched.single { it.spec.sessionId == id }
        proc.unkillable = true

        val r = alice.closeChat(sid)
        assertEquals(409, r.status.value)
        assertEquals("agent_not_stopped", errorCode(r.bodyAsText()))
        assertTrue(s.sessions.get(id) != null, "nothing deleted")
        assertTrue(s.events.list(id, 0, 10).isNotEmpty())
        assertEquals(200, alice.get("/api/sessions/$sid").status.value)

        // Once it has exited, closing works.
        proc.exit(143)
        waitFor("process gone") { !s.agents.isLive(id) }
        assertEquals(204, alice.closeChat(sid).status.value)
        gone(sid)
    }

    @Test
    fun `owner closes an idle chat and a stopped chat`() = testApplication {
        application { agernModule(s) }
        val alice = login("alice", "pw-a")
        val fresh = alice.newChat()
        assertEquals(204, alice.closeChat(fresh).status.value)
        gone(fresh)

        val stopped = alice.newChat()
        assertEquals(202, alice.send(stopped, "hi").status.value)
        waitFor("live") { s.agents.isLive(UUID.fromString(stopped)) }
        assertEquals(200, alice.postJson("/api/sessions/$stopped/stop").status.value)
        assertEquals(204, alice.closeChat(stopped).status.value)
        gone(stopped)
        assertEquals(404, alice.closeChat(UUID.randomUUID().toString()).status.value)
    }

    @Test
    fun `only the owner or an admin may close, invisible chats are not found`() = testApplication {
        application { agernModule(s) }
        val alice = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        val root = login("root", "pw-r")
        val sid = alice.newChat()

        val r = bob.closeChat(sid)
        assertEquals(403, r.status.value)
        assertEquals("forbidden", errorCode(r.bodyAsText()))
        assertTrue(s.sessions.get(UUID.fromString(sid)) != null)
        // CSRF header is required as for every mutating request.
        assertEquals(403, alice.delete("/api/sessions/$sid").status.value)
        assertTrue(s.sessions.get(UUID.fromString(sid)) != null)

        assertEquals(204, root.closeChat(sid).status.value)
        gone(sid)

        val priv = alice.newChat("private-lab")
        assertEquals(404, bob.closeChat(priv).status.value)
        assertTrue(s.sessions.get(UUID.fromString(priv)) != null)
    }

    @Test
    fun `pipeline sessions cannot be closed`() = testApplication {
        application { agernModule(s) }
        val release = CompletableDeferred<Unit>()
        world.launcher.stage = { p ->
            p.init()
            if (p.spec.kind == "spec-writer") runBlocking { release.await() }
            p.result("done"); p.exit(0)
        }
        val alice = login("alice", "pw-a")
        val runId = UUID.fromString(json(alice.postJson("/api/projects/demo-app/runs", """{"issueNumber":1}""").bodyAsText()).jsonObject.str("id"))
        var stage: UUID? = null
        waitFor("spec-writer session") { s.sessions.byRun(runId).firstOrNull()?.also { stage = it.id } != null }

        val r = alice.closeChat(stage.toString())
        assertEquals(409, r.status.value)
        assertEquals("not_a_chat", errorCode(r.bodyAsText()))
        assertTrue(s.sessions.get(stage!!) != null)
        release.complete(Unit)
        world.awaitRun(runId, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertTrue(s.sessions.get(stage!!) != null)
    }

    @Test
    fun `attachment files are removed from disk`() = testApplication {
        application { agernModule(s) }
        val alice = login("alice", "pw-a")
        val sid = alice.newChat()
        val id = UUID.fromString(sid)
        val up = alice.post("/api/sessions/$sid/attachments?name=shot.png") {
            header("X-Factory-Request", "1"); contentType(ContentType.Image.PNG); setBody(png(8, 8))
        }
        assertEquals(201, up.status.value, up.bodyAsText())
        val aid = json(up.bodyAsText()).jsonObject.str("id")
        assertEquals(202, alice.send(sid, "look", listOf(aid)).status.value)
        waitFor("delivered") { world.launcher.chatInputs.isNotEmpty() }
        assertTrue(Files.isDirectory(s.attachments.dir(id)))
        assertTrue(s.attachments.store.forSession(id, sentOnly = false).isNotEmpty())

        assertEquals(204, alice.closeChat(sid).status.value)
        gone(sid)
    }

    @Test
    fun `a run started from the chat survives the close`() = testApplication {
        application { agernModule(s) }
        val release = CompletableDeferred<Unit>()
        world.launcher.stage = { p ->
            p.init()
            when (p.spec.kind) {
                "spec-writer" -> { runBlocking { release.await() }; p.result("## Spec") }
                "approver" -> p.result(null, buildJsonObject { put("verdict", "ESCALATE"); put("summary", "unsure") })
                "security", "ux", "ui-design", "code" -> p.result(null, buildJsonObject {
                    put("verdict", "PASS"); put("summary", "ok"); put("findings", JsonArray(emptyList()))
                })
                else -> p.result("done")
            }
            p.exit(0)
        }
        val alice = login("alice", "pw-a")
        val sid = alice.newChat()
        val aliceRec = s.users.byUsername("alice")!!
        val run = s.factory.startRun(aliceRec, "demo-app", 1, UUID.fromString(sid))
        assertEquals(sid, run.startedBySessionId)
        val runId = UUID.fromString(run.id)
        waitFor("spec-writer running") { s.sessions.byRun(runId).any { s.agents.isLive(it.id) } }

        assertEquals(204, alice.closeChat(sid).status.value)
        gone(sid)
        assertNull(s.runs.get(runId)!!.startedBySessionId)
        val listed = json(alice.get("/api/projects/demo-app/runs").bodyAsText()).jsonArray.map { it.jsonObject }
        assertTrue(listed.any { it.str("id") == run.id })
        assertEquals(200, alice.get("/api/runs/${run.id}").status.value)

        release.complete(Unit)
        assertEquals(RunStatus.NEEDS_HUMAN, world.awaitRun(runId, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
    }

    @Test
    fun `late work for a closed chat is harmless`() = testApplication {
        application { agernModule(s) }
        val alice = login("alice", "pw-a")
        val sid = alice.newChat()
        val id = UUID.fromString(sid)
        assertEquals(204, alice.closeChat(sid).status.value)

        val e = s.agents.systemEvent(id, "info", "too late")
        assertEquals(-1, e.seq)
        s.agents.publish(id, "user", buildJsonObject { put("text", "x") })
        s.agents.publishFeed(id)
        assertTrue(s.events.list(id, 0, 10).isEmpty())
        assertNull(s.sessions.get(id))
    }

    @Test
    fun `input queued behind the close starts no process`() = testApplication {
        application { agernModule(s) }
        val alice = login("alice", "pw-a")
        val aliceRec = s.users.byUsername("alice")!!
        val sid = alice.newChat()
        val id = UUID.fromString(sid)
        val rec = s.sessions.get(id)!!
        // Input and a /factory command in flight while the close runs.
        s.agents.sendInput(aliceRec, rec, "racing")
        s.agents.sendInput(aliceRec, rec, "/factory help")
        s.agents.closeChat(aliceRec, rec)
        delay(300)
        assertFalse(s.agents.isLive(id))
        gone(sid)
        // Whatever process the input may have started before the close was killed; none was started after it.
        assertTrue(world.launcher.launched.filter { it.spec.sessionId == id }.all { it.killed })
    }
}
