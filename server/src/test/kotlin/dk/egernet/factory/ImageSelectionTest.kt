package dk.egernet.factory

import dk.egernet.factory.mcp.McpGrant
import dk.egernet.factory.mcp.McpRegistry
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Selecting chat images for issues, serving them, and cleanup (docs/ARCHITECTURE.md §6 "Generated images"). */
class ImageSelectionTest {
    private val w = TestWorld(twoFactor = false, imageProviders = listOf(RecordingImageProvider()))
    @AfterTest fun tearDown() = w.close()

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String): HttpClient {
        val c = createClient { install(HttpCookies) }
        assertEquals(200, c.post("/api/auth/login") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
        }.status.value)
        return c
    }

    private suspend fun HttpClient.select(id: String, issue: Int, name: String): HttpResponse = put("/api/images/$id/selection") {
        header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"issueNumber":$issue,"name":"$name"}""")
    }
    private suspend fun HttpClient.unselect(id: String) = delete("/api/images/$id/selection") { header("X-Factory-Request", "1") }
    private suspend fun HttpClient.discard(id: String) = delete("/api/images/$id") { header("X-Factory-Request", "1") }

    private fun json(s: String) = AppJson.parseToJsonElement(s)
    private fun code(r: String) = json(r).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    /** [n] images generated in a new chat of [user] in [repo]; returns (chat id, image ids). */
    private fun chatImages(user: String, n: Int, repo: String = "demo-app"): Pair<UUID, List<String>> {
        val u = w.user(user)
        w.imageKey(user, "rec-$user-key")
        val chat = w.services.agents.createChat(u, repo, null)
        val token = w.services.mcpRegistry.issue(McpGrant(chat.id, u.id, repo, null, McpRegistry.CHAT_TOOLS))
        val a = Mcp.generate(w, token, buildJsonObject { put("prompt", "An icon"); put("purpose", "app icon"); put("count", n); put("size", "256x256") })
        assertFalse(a.isError, a.text)
        return chat.id to a.json["images"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
    }

    private fun imageCount(body: String, issue: Int) =
        json(body).jsonArray.single { it.jsonObject["number"]!!.jsonPrimitive.int == issue }.jsonObject["imageCount"]!!.jsonPrimitive.int

    @Test
    fun `select, move, rename, unselect and discard with every refusal`() = testApplication {
        application { agernModule(w.services) }
        val (_, ids) = chatImages("alice", 3)
        w.user("bob")
        val alice = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        val (a, b, c) = ids

        val sel = alice.select(a, 1, "App  icon\n")
        assertEquals(200, sel.status.value, sel.bodyAsText())
        val s = json(sel.bodyAsText()).jsonObject["selection"]!!.jsonObject
        assertEquals(1, s["issueNumber"]!!.jsonPrimitive.int)
        assertEquals("App icon", s["name"]!!.jsonPrimitive.content)
        assertEquals("false", s["locked"]!!.jsonPrimitive.content)
        assertEquals(1, imageCount(alice.get("/api/projects/demo-app/issues").bodyAsText(), 1))
        assertEquals(1, json(alice.get("/api/projects/demo-app/issues/1").bodyAsText()).jsonObject["imageCount"]!!.jsonPrimitive.int)

        // Move and rename.
        val moved = alice.select(a, 2, "Logo")
        assertEquals(200, moved.status.value)
        assertEquals("Logo", json(moved.bodyAsText()).jsonObject["selection"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val issues = alice.get("/api/projects/demo-app/issues").bodyAsText()
        assertEquals(0, imageCount(issues, 1))
        assertEquals(1, imageCount(issues, 2))
        assertEquals(listOf(a), json(alice.get("/api/projects/demo-app/issues/2/images").bodyAsText()).jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })

        // Refusals.
        assertEquals(403, bob.select(b, 1, "Mine").status.value)
        assertEquals(403, bob.unselect(a).status.value)
        assertEquals(403, bob.discard(b).status.value)
        assertEquals(404, alice.select(UUID.randomUUID().toString(), 1, "x").status.value)
        assertEquals(404, alice.select(b, 999, "x").status.value)
        assertEquals(400, alice.select(b, 1, "").status.value)
        assertEquals(400, alice.select(b, 1, "n".repeat(61)).status.value)
        w.github.client("fake-alice").closeIssue("test-org", "demo-app", 3)
        alice.select(b, 3, "Closed").let { assertEquals(409, it.status.value); assertEquals("issue_closed", code(it.bodyAsText())) }
        alice.discard(a).let { assertEquals(409, it.status.value); assertEquals("image_selected", code(it.bodyAsText())) }

        // Unselect, then discard.
        assertEquals(200, alice.unselect(a).status.value)
        assertEquals("null", json(alice.get("/api/sessions/${w.services.images.store.get(UUID.fromString(a))!!.sessionId}/images").bodyAsText())
            .jsonArray.first().jsonObject["selection"].toString())
        assertEquals(204, alice.discard(a).status.value)
        assertEquals(404, alice.get("/api/images/$a").status.value)
        assertEquals(2, runBlocking { w.services.images.forSession(w.services.images.store.get(UUID.fromString(b))!!.sessionId) }.size)

        // At most 20 per issue.
        val (_, many) = chatImages("alice", 4).let { first -> first.first to first.second + (1..5).flatMap { chatImages("alice", 4).second } }
        many.take(20).forEach { assertEquals(200, alice.select(it, 1, "one").status.value) }
        alice.select(c, 1, "too many").let { assertEquals(409, it.status.value); assertEquals("too_many_images", code(it.bodyAsText())) }
    }

    @Test
    fun `run images cannot be selected and locked selections cannot change until their run failed`() = testApplication {
        application { agernModule(w.services) }
        val (_, ids) = chatImages("alice", 1)
        val alice = login("alice", "pw-a")
        val id = ids.single()
        assertEquals(200, alice.select(id, 1, "Icon").status.value)
        val aliceRec = w.services.users.byUsername("alice")!!
        val run = w.services.runs.createIfNoActive("demo-app", 1, "t", "task", 2, aliceRec.id, null)!!
        assertEquals(listOf(UUID.fromString(id)), w.services.images.store.claimForRun("demo-app", 1, run.id).map { it.id })
        val locked = json(alice.get("/api/projects/demo-app/issues/1/images").bodyAsText()).jsonArray.single().jsonObject["selection"]!!.jsonObject
        assertEquals("true", locked["locked"]!!.jsonPrimitive.content)
        assertEquals(run.id.toString(), locked["usedByRunId"]!!.jsonPrimitive.content)
        alice.select(id, 2, "Elsewhere").let { assertEquals(409, it.status.value); assertEquals("image_in_use", code(it.bodyAsText())) }
        alice.unselect(id).let { assertEquals(409, it.status.value); assertEquals("image_in_use", code(it.bodyAsText())) }
        w.services.runs.setStatus(run.id, RunStatus.FAILED, "boom")
        assertEquals(200, alice.unselect(id).status.value)

        // An image made in a run.
        val stageSession = w.services.sessions.create("demo-app", "builder", "m", "running", aliceRec.id, "b", run.id, null, 1)
        val token = w.services.mcpRegistry.issue(McpGrant(stageSession.id, aliceRec.id, "demo-app", run.id, setOf("factory_generate_image")))
        Files.createDirectories(w.services.workspaces.runDir("alice", "demo-app", run.id))
        val runImage = Mcp.generate(w, token).json["images"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content
        alice.select(runImage, 1, "x").let { assertEquals(409, it.status.value); assertEquals("run_image", code(it.bodyAsText())) }
        alice.discard(runImage).let { assertEquals(409, it.status.value); assertEquals("run_image", code(it.bodyAsText())) }
    }

    @Test
    fun `an issue's images from a workspace chat are listed only to whoever can read its members`() = testApplication {
        application { agernModule(w.services) }
        val (chat, ids) = chatImages("alice", 1, repo = "demo-workspace")
        // The chat checked out demo-ws-lab, which only alice can read.
        val deadline = System.currentTimeMillis() + 10_000
        while ("demo-ws-lab" !in w.services.sessions.get(chat)!!.contextRepos && System.currentTimeMillis() < deadline) Thread.sleep(20)
        w.user("bob")
        val alice = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        assertEquals(200, alice.select(ids[0], 1, "icon").status.value)
        assertEquals(1, json(alice.get("/api/projects/demo-workspace/issues/1/images").bodyAsText()).jsonArray.size)
        val forBob = bob.get("/api/projects/demo-workspace/issues/1/images")
        assertEquals(200, forBob.status.value)
        assertEquals(0, json(forBob.bodyAsText()).jsonArray.size, "the prompt may hold member content")
        assertEquals(404, bob.get("/api/images/${ids[0]}").status.value)
    }

    @Test
    fun `images are served with the attachment headers to whoever may see the repository`() = testApplication {
        application { agernModule(w.services) }
        val (chat, ids) = chatImages("alice", 1)
        val (privChat, privIds) = chatImages("alice", 1, repo = "private-lab")
        w.user("bob")
        val anon = createClient { }
        assertEquals(401, anon.get("/api/images/${ids[0]}").status.value)
        val alice = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        val r = bob.get("/api/images/${ids[0]}")
        assertEquals(200, r.status.value)
        assertTrue(r.readRawBytes().contentEquals(w.services.images.store.data(UUID.fromString(ids[0]))!!))
        assertEquals("image/png", r.headers["Content-Type"])
        assertEquals("nosniff", r.headers["X-Content-Type-Options"])
        assertEquals("default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; sandbox", r.headers["Content-Security-Policy"])
        assertEquals("same-origin", r.headers["Cross-Origin-Resource-Policy"])
        assertEquals("private, max-age=31536000, immutable", r.headers["Cache-Control"])
        assertTrue(r.headers["Content-Disposition"]!!.startsWith("inline; filename=\"app-icon-"))

        assertEquals(404, bob.get("/api/images/${privIds[0]}").status.value)
        assertEquals(404, bob.get("/api/images/${UUID.randomUUID()}").status.value)
        assertEquals(404, bob.get("/api/sessions/$privChat/images").status.value)
        assertEquals(404, bob.get("/api/projects/private-lab/issues/1/images").status.value)
        assertEquals(200, alice.get("/api/images/${privIds[0]}").status.value)
        assertEquals(1, json(bob.get("/api/sessions/$chat/images").bodyAsText()).jsonArray.size)
        assertEquals(1, json(alice.get("/api/sessions/$privChat/images").bodyAsText()).jsonArray.size)
    }

    @Test
    fun `closing a chat deletes its unselected images and their files and keeps selected ones`() = testApplication {
        application { agernModule(w.services) }
        val (chat, ids) = chatImages("alice", 2)
        val alice = login("alice", "pw-a")
        assertEquals(200, alice.select(ids[0], 1, "Keep").status.value)
        val dir: Path = w.services.images.chatDir(chat)
        assertEquals(2, Files.list(dir).use { it.count() }.toInt())
        assertEquals(204, alice.delete("/api/sessions/$chat") { header("X-Factory-Request", "1") }.status.value)
        assertFalse(Files.exists(dir))
        assertEquals(null, w.services.images.store.get(UUID.fromString(ids[1])))
        assertEquals("Keep", w.services.images.store.get(UUID.fromString(ids[0]))!!.name)
        assertEquals(200, alice.get("/api/images/${ids[0]}").status.value)
    }
}
