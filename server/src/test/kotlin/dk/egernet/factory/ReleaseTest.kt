package dk.egernet.factory

import dk.egernet.factory.mcp.McpGrant
import dk.egernet.factory.mcp.McpRegistry
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
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Release planning (docs/ARCHITECTURE.md §6 "Releases"): model, API, feed, `/factory` and MCP. */
class ReleaseTest {
    private val world = TestWorld(twoFactor = false)
    private val s = world.services

    @AfterTest fun tearDown() {
        world.close()
        // These tests start no agents, so nothing uses the pool any more: give its connections back to PostgreSQL.
        (s.db.dataSource as java.io.Closeable).close()
    }

    private fun json(r: String) = AppJson.parseToJsonElement(r)
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun errorCode(r: HttpResponse) = runBlocking { json(r.bodyAsText()).jsonObject["error"]!!.jsonObject.str("code") }

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String): HttpClient {
        val c = createClient { install(HttpCookies); install(WebSockets) }
        assertEquals(200, c.post("/api/auth/login") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
        }.status.value)
        c.put("/api/me/github-token") { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"token":"fake-$user"}""") }
        return c
    }

    private suspend fun HttpClient.postJson(path: String, body: String = "{}", csrf: Boolean = true): HttpResponse =
        post(path) { if (csrf) header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.putJson(path: String, body: String): HttpResponse =
        put(path) { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.del(path: String): HttpResponse = delete(path) { header("X-Factory-Request", "1") }

    private suspend fun HttpClient.createRelease(repo: String, name: String, description: String = ""): JsonObject {
        val r = postJson("/api/projects/$repo/releases", """{"name":"$name","description":"$description"}""")
        assertEquals(201, r.status.value, r.bodyAsText())
        return json(r.bodyAsText()).jsonObject
    }

    private suspend fun HttpClient.assign(repo: String, numbers: List<Int>, releaseId: String?): HttpResponse =
        postJson("/api/projects/$repo/releases/assign", """{"issueNumbers":$numbers,"releaseId":${releaseId?.let { "\"$it\"" } ?: "null"}}""")

    private suspend fun HttpClient.release(id: String): JsonObject = json(get("/api/releases/$id").bodyAsText()).jsonObject

    private fun JsonObject.issueNumbers() = this["issues"]!!.jsonArray.map { it.jsonObject.str("number").toInt() }


    @Test
    fun `create, list, get, validate names, rename and delete`() = testApplication {
        application { agernModule(s) }
        val alice = login("alice", "pw-a")
        val a = alice.createRelease("demo-app", "1.4", "First cut")
        assertEquals("planning", a.str("status"))
        assertEquals("alice", a["createdBy"]!!.jsonObject.str("username"))
        assertEquals("First cut", a.str("description"))
        val b = alice.createRelease("demo-app", "chat-cleanup")
        // Nothing is built: no GitHub write and no run.
        assertTrue(world.github.actions.isEmpty(), world.github.actions.toString())
        assertTrue(s.runs.list(null, false).isEmpty())

        val list = json(alice.get("/api/projects/demo-app/releases").bodyAsText()).jsonArray.map { it.jsonObject.str("name") }
        assertEquals(listOf("chat-cleanup", "1.4"), list, "newest first")
        assertEquals("1.4", alice.release(a.str("id")).str("name"))

        for (bad in listOf("", " ", "a".repeat(51), "a/b", "-x", "a..b", "x.lock", "x.", "has space", "ü")) {
            val r = alice.postJson("/api/projects/demo-app/releases", """{"name":"$bad"}""")
            assertEquals(400, r.status.value, "name '$bad'")
        }
        assertEquals(201, alice.postJson("/api/projects/demo-app/releases", """{"name":"${"a".repeat(50)}"}""").status.value)
        assertEquals(400, alice.postJson("/api/projects/demo-app/releases", """{"name":"ok","description":"${"d".repeat(2001)}"}""").status.value)

        val dup = alice.postJson("/api/projects/demo-app/releases", """{"name":"CHAT-cleanup"}""")
        assertEquals(409, dup.status.value)
        assertEquals("release_name_taken", errorCode(dup))
        alice.createRelease("private-lab", "1.4") // same name, other repo

        // Rename, also to another casing of its own name; not onto another release's name.
        val renamed = json(alice.putJson("/api/releases/${a.str("id")}", """{"name":"v1.4","description":"Edited"}""").bodyAsText()).jsonObject
        assertEquals("v1.4", renamed.str("name"))
        assertEquals("Edited", renamed.str("description"))
        assertTrue(renamed.str("updatedAt") > a.str("updatedAt"))
        assertEquals(200, alice.putJson("/api/releases/${a.str("id")}", """{"name":"V1.4","description":""}""").status.value)
        assertEquals("release_name_taken", errorCode(alice.putJson("/api/releases/${a.str("id")}", """{"name":"chat-cleanup","description":""}""")))
        assertEquals(400, alice.putJson("/api/releases/${a.str("id")}", """{"name":"a/b","description":""}""").status.value)

        // Delete leaves the issues open and unassigned.
        assertEquals(200, alice.assign("demo-app", listOf(1, 2), b.str("id")).status.value)
        assertEquals(204, alice.del("/api/releases/${b.str("id")}").status.value)
        assertEquals(404, alice.get("/api/releases/${b.str("id")}").status.value)
        val issues = json(alice.get("/api/projects/demo-app/issues").bodyAsText()).jsonArray.map { it.jsonObject }
        assertTrue(issues.filter { it.str("number").toInt() in setOf(1, 2) }.all { it["release"] == JsonNull && it.str("state") == "open" })
        assertEquals(404, alice.del("/api/releases/${b.str("id")}").status.value)
        assertEquals(404, alice.get("/api/releases/not-a-uuid").status.value)
    }

    @Test
    fun `assign appends in order, moves between releases, unassigns, all-or-nothing`() = testApplication {
        application { agernModule(s) }
        val alice = login("alice", "pw-a")
        val a = alice.createRelease("demo-app", "1.4")
        val b = alice.createRelease("demo-app", "1.5")
        val aId = a.str("id")
        val bId = b.str("id")

        val r = alice.assign("demo-app", listOf(3, 1, 3), aId)
        assertEquals(200, r.status.value, r.bodyAsText())
        assertEquals(2, json(r.bodyAsText()).jsonArray.size, "answers all releases of the project")
        val after = alice.release(aId)
        assertEquals(listOf(3, 1), after.issueNumbers(), "request order, duplicates collapsed")
        assertEquals("Update dependencies", after["issues"]!!.jsonArray[0].jsonObject.str("title"))
        assertEquals("task", after["issues"]!!.jsonArray[0].jsonObject.str("kind"))
        assertEquals("feature", after["issues"]!!.jsonArray[1].jsonObject.str("kind"))

        // Re-assigning keeps the position; new ones go last.
        alice.assign("demo-app", listOf(1, 2), aId)
        assertEquals(listOf(3, 1, 2), alice.release(aId).issueNumbers())

        // Move #1 to 1.5: it is in exactly one release.
        alice.assign("demo-app", listOf(1), bId)
        assertEquals(listOf(3, 2), alice.release(aId).issueNumbers())
        assertEquals(listOf(1), alice.release(bId).issueNumbers())

        // IssueDto.release in the list and single-issue responses.
        val list = json(alice.get("/api/projects/demo-app/issues").bodyAsText()).jsonArray.associate { it.jsonObject.str("number").toInt() to it.jsonObject }
        assertEquals("1.5", list[1]!!["release"]!!.jsonObject.str("name"))
        assertEquals(bId, list[1]!!["release"]!!.jsonObject.str("id"))
        assertEquals("1.4", list[3]!!["release"]!!.jsonObject.str("name"))
        val one = json(alice.get("/api/projects/demo-app/issues/2").bodyAsText()).jsonObject
        assertEquals(aId, one["release"]!!.jsonObject.str("id"))
        val fresh = alice.postJson("/api/projects/demo-app/issues", """{"title":"New","body":"","kind":"task"}""")
        assertEquals(JsonNull, json(fresh.bodyAsText()).jsonObject["release"])
        val freshNo = json(fresh.bodyAsText()).jsonObject.str("number").toInt()
        assertEquals(JsonNull, json(alice.get("/api/projects/demo-app/issues/$freshNo").bodyAsText()).jsonObject["release"])

        // Closed and unknown issues: nothing in the request is applied.
        world.github.client("fake-alice").closeIssue("test-org", "demo-app", 2)
        val closed = alice.assign("demo-app", listOf(freshNo, 2), bId)
        assertEquals(409, closed.status.value)
        assertEquals("issue_closed", errorCode(closed))
        val unknown = alice.assign("demo-app", listOf(freshNo, 999), bId)
        assertEquals(404, unknown.status.value)
        assertTrue(unknown.bodyAsText().contains("Issue #999 not found"))
        assertEquals(listOf(1), alice.release(bId).issueNumbers())
        // A closed issue already in a release stays there.
        assertEquals(listOf(3, 2), alice.release(aId).issueNumbers())

        // Bad requests.
        assertEquals(400, alice.assign("demo-app", emptyList(), bId).status.value)
        assertEquals(400, alice.assign("demo-app", listOf(0), bId).status.value)
        assertEquals(400, alice.assign("demo-app", (1..101).toList(), bId).status.value)
        assertEquals(400, alice.postJson("/api/projects/demo-app/releases/assign", """{"issueNumbers":[1],"releaseId":"nope"}""").status.value)
        assertEquals(404, alice.assign("demo-app", listOf(1), UUID.randomUUID().toString()).status.value)
        // A release of another repo cannot be targeted through this repo.
        val lab = alice.createRelease("private-lab", "lab")
        assertEquals(404, alice.assign("demo-app", listOf(1), lab.str("id")).status.value)

        // Unassign; numbers in no release are ignored without GitHub calls.
        assertEquals(200, alice.assign("demo-app", listOf(1, 3, 777), null).status.value)
        assertEquals(listOf(2), alice.release(aId).issueNumbers())
        assertEquals(emptyList(), alice.release(bId).issueNumbers())
    }

    @Test
    fun `reorder and stale order`() = testApplication {
        application { agernModule(s) }
        val alice = login("alice", "pw-a")
        val id = alice.createRelease("demo-app", "1.4").str("id")
        alice.assign("demo-app", listOf(1, 2, 3), id)
        val r = alice.putJson("/api/releases/$id/issues/order", """{"issueNumbers":[3,1,2]}""")
        assertEquals(200, r.status.value, r.bodyAsText())
        assertEquals(listOf(3, 1, 2), json(r.bodyAsText()).jsonObject.issueNumbers())
        assertEquals(listOf(3, 1, 2), alice.release(id).issueNumbers())
        assertEquals(listOf(3, 1, 2), json(alice.get("/api/projects/demo-app/releases").bodyAsText()).jsonArray[0].jsonObject.issueNumbers())
        for (stale in listOf("[3,1]", "[3,1,2,4]", "[3,3,1]", "[1,2,4]")) {
            val bad = alice.putJson("/api/releases/$id/issues/order", """{"issueNumbers":$stale}""")
            assertEquals(409, bad.status.value, stale)
            assertEquals("release_issues_changed", errorCode(bad))
        }
        assertEquals(listOf(3, 1, 2), alice.release(id).issueNumbers())
        // After a move out, positions stay consistent.
        alice.assign("demo-app", listOf(1), null)
        alice.assign("demo-app", listOf(1), id)
        assertEquals(listOf(3, 2, 1), alice.release(id).issueNumbers())
    }

    @Test
    fun `visibility and csrf`() = testApplication {
        application { agernModule(s) }
        val alice = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        val lab = alice.createRelease("private-lab", "secret")
        val id = lab.str("id")
        alice.assign("private-lab", listOf(1), id)
        assertEquals(404, bob.get("/api/releases/$id").status.value)
        assertEquals(404, bob.putJson("/api/releases/$id", """{"name":"x","description":""}""").status.value)
        assertEquals(404, bob.putJson("/api/releases/$id", """{"name":"a/b","description":""}""").status.value)
        assertEquals(404, bob.putJson("/api/releases/$id/issues/order", """{"issueNumbers":[1]}""").status.value)
        assertEquals(404, bob.del("/api/releases/$id").status.value)
        assertEquals(404, bob.get("/api/projects/private-lab/releases").status.value)
        assertEquals(404, bob.postJson("/api/projects/private-lab/releases", """{"name":"mine"}""").status.value)
        assertEquals(404, bob.assign("private-lab", listOf(1), id).status.value)
        assertEquals(404, bob.assign("private-lab", listOf(1), null).status.value)
        assertEquals(listOf(1), alice.release(id).issueNumbers())
        // Bob can plan in demo-app like anyone who can see it.
        assertEquals(201, bob.postJson("/api/projects/demo-app/releases", """{"name":"bobs"}""").status.value)
        // CSRF header required on mutations.
        assertEquals(403, alice.postJson("/api/projects/demo-app/releases", """{"name":"nocsrf"}""", csrf = false).status.value)
        assertEquals(403, alice.delete("/api/releases/$id").status.value)
        assertEquals(401, createClient { }.get("/api/projects/demo-app/releases").status.value)
    }

    @Test
    fun `feed announces changes to viewers with access only`() = testApplication {
        application { agernModule(s) }
        val alice = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        bob.get("/api/projects/demo-app") // primes bob's repo cache
        val aliceFeed = alice.webSocketSession("/api/ws/feed")
        val bobFeed = bob.webSocketSession("/api/ws/feed")
        withTimeout(10_000) { while (s.bus.feedSubscribers.value < 2) delay(10) }

        suspend fun next(ws: DefaultClientWebSocketSession): JsonObject = withTimeout(10_000) {
            while (true) {
                val f = ws.incoming.receive() as? Frame.Text ?: continue
                val o = json(f.readText()).jsonObject
                if (o.str("type").startsWith("release")) return@withTimeout o
            }
            @Suppress("UNREACHABLE_CODE") JsonObject(emptyMap())
        }

        alice.createRelease("private-lab", "secret")
        val a = alice.createRelease("demo-app", "1.4").str("id")
        val b = alice.createRelease("demo-app", "1.5").str("id")
        alice.assign("demo-app", listOf(1), a)
        alice.assign("demo-app", listOf(1), b) // a move: one frame for each
        alice.del("/api/releases/$a")

        val f1 = next(aliceFeed)
        assertEquals("private-lab", f1.str("repo"))
        assertEquals("secret", f1["release"]!!.jsonObject.str("name"))
        assertEquals("1.4", next(aliceFeed)["release"]!!.jsonObject.str("name"))
        assertEquals("1.5", next(aliceFeed)["release"]!!.jsonObject.str("name"))
        val assigned = next(aliceFeed)["release"]!!.jsonObject
        assertEquals(a, assigned.str("id"))
        assertEquals(listOf(1), assigned.issueNumbers())
        val moved = listOf(next(aliceFeed), next(aliceFeed)).map { it["release"]!!.jsonObject }.associateBy { it.str("id") }
        assertEquals(emptyList(), moved[a]!!.issueNumbers())
        assertEquals(listOf(1), moved[b]!!.issueNumbers())
        assertTrue(moved[a]!!.str("updatedAt") > assigned.str("updatedAt"))
        val del = next(aliceFeed)
        assertEquals("release_deleted", del.str("type"))
        assertEquals(a, del.str("releaseId"))
        assertEquals("demo-app", del.str("repo"))

        // Bob never sees private-lab: his first release frame is the demo-app one.
        val bobFirst = next(bobFeed)
        assertEquals("demo-app", bobFirst.str("repo"))
        assertEquals("1.4", bobFirst["release"]!!.jsonObject.str("name"))
    }

    @Test
    fun `factory commands`() = runBlocking {
        val alice = world.user("alice")
        val chat = s.sessions.create("demo-app", "chat", "opus", "idle", alice.id, null)
        suspend fun cmd(t: String) = try { s.factory.handleCommand(chat, alice, t) } catch (e: ApiException) { e.message }

        assertEquals("No releases in demo-app yet.", cmd("/factory releases"))
        assertEquals("Created release \"1.4\".", cmd("/factory release create 1.4 The first plan"))
        assertEquals("The first plan", s.releaseStore.byName("demo-app", "1.4")!!.description)
        assertEquals("Created release \"1.5\".", cmd("/factory release create 1.5"))
        assertEquals("Release \"1.4\": added #1, #2 (now 2 issues).", cmd("/factory release add 1.4 #1, 2"))
        val moved = cmd("/factory release add 1.5 #2")
        assertTrue(moved.startsWith("Release \"1.5\": added #2 (now 1 issue)."), moved)
        assertTrue(moved.contains("from release \"1.4\""), moved)
        val listing = cmd("/factory releases")
        assertTrue(listing.contains("1.4 — in preparation, 1 issue"), listing)
        assertTrue(listing.contains("#1 Add a dark mode toggle"), listing)
        assertTrue(listing.contains("#2 Crash when the list is empty"), listing)
        val unknown = cmd("/factory release add nope 1")
        assertTrue(unknown.contains("No release \"nope\"") && unknown.contains("1.4") && unknown.contains("1.5"), unknown)
        assertTrue(cmd("/factory release add 1.4 x").startsWith("Usage:"))
        assertTrue(cmd("/factory release add 1.4").startsWith("Usage:"))
        assertTrue(cmd("/factory release create").startsWith("Usage:"))
        assertTrue(cmd("/factory release frobnicate").startsWith("Usage:"))
        assertTrue(cmd("/factory release create a/b").contains("release name"))
        assertEquals("Removed #1, #2 from their releases.", cmd("/factory release remove #1 #2"))
        assertEquals("None of #3 is in a release.", cmd("/factory release remove 3"))
        assertTrue(s.releaseStore.list("demo-app").all { it.issues.isEmpty() })
        val help = cmd("/factory help")
        assertTrue(help.contains("/factory releases") && help.contains("/factory release add"), help)
        Unit
    }

    @Test
    fun `mcp release tools for chat agents only`() = runBlocking {
        val alice = world.user("alice")
        val chat = s.sessions.create("demo-app", "chat", "opus", "idle", alice.id, null)
        val chatToken = s.mcpRegistry.issue(McpGrant(chat.id, alice.id, "demo-app", null, McpRegistry.CHAT_TOOLS))
        val stageToken = s.mcpRegistry.issue(McpGrant(chat.id, alice.id, "demo-app", UUID.randomUUID(), McpRegistry.STAGE_TOOLS))
        val releaseTools = setOf("factory_list_releases", "factory_create_release", "factory_assign_issues", "factory_unassign_issues")
        suspend fun rpc(token: String, body: String) = json(s.mcp.handle(token, body).second!!).jsonObject
        suspend fun call(token: String, name: String, args: String): JsonObject =
            rpc(token, """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$args}}""")["result"]!!.jsonObject
        fun JsonObject.text() = this["content"]!!.jsonArray[0].jsonObject.str("text")
        fun JsonObject.isError() = this["isError"]?.jsonPrimitive?.content == "true"

        fun names(o: JsonObject) = o["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject.str("name") }.toSet()
        val chatList = rpc(chatToken, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        assertTrue(names(chatList).containsAll(releaseTools))
        val assignSchema = chatList["result"]!!.jsonObject["tools"]!!.jsonArray.first { it.jsonObject.str("name") == "factory_assign_issues" }
            .jsonObject["inputSchema"]!!.jsonObject["properties"]!!.jsonObject["issueNumbers"]!!.jsonObject
        assertEquals("array", assignSchema.str("type"))
        assertEquals("integer", assignSchema["items"]!!.jsonObject.str("type"))
        assertTrue(names(rpc(stageToken, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")).intersect(releaseTools).isEmpty())
        for (t in releaseTools) assertTrue(call(stageToken, t, "{}").isError(), t)
        // Even a run-bound grant that somehow had the tools may not mutate.
        val runChat = s.mcpRegistry.issue(McpGrant(chat.id, alice.id, "demo-app", UUID.randomUUID(), McpRegistry.CHAT_TOOLS))
        assertTrue(call(runChat, "factory_create_release", """{"name":"x"}""").isError())

        assertEquals("No releases in demo-app yet.", call(chatToken, "factory_list_releases", "{}").text())
        assertTrue(call(chatToken, "factory_create_release", """{"name":"1.4","description":"d"}""").text().startsWith("Created release \"1.4\""))
        call(chatToken, "factory_create_release", """{"name":"1.5"}""")
        assertTrue(call(chatToken, "factory_create_release", """{"name":"1.4"}""").isError())
        assertEquals("Release \"1.4\": added #1, #2 (now 2 issues).",
            call(chatToken, "factory_assign_issues", """{"release":"1.4","issueNumbers":[1,"#2"]}""").text())
        val id15 = s.releaseStore.byName("demo-app", "1.5")!!.id
        val moved = call(chatToken, "factory_assign_issues", """{"release":"$id15","issueNumbers":[2]}""").text()
        assertTrue(moved.contains("Moved #2 from release \"1.4\""), moved)
        assertEquals(listOf(1), s.releaseStore.byName("demo-app", "1.4")!!.issues.map { it.number })
        assertEquals(listOf(2), s.releaseStore.byName("demo-app", "1.5")!!.issues.map { it.number })
        assertTrue(call(chatToken, "factory_assign_issues", """{"release":"nope","issueNumbers":[1]}""").isError())
        assertTrue(call(chatToken, "factory_assign_issues", """{"release":"1.4","issueNumbers":["x"]}""").isError())
        val listing = call(chatToken, "factory_list_releases", "{}").text()
        assertTrue(listing.contains("id $id15"), listing)
        val issues = call(chatToken, "factory_list_issues", "{}").text()
        assertTrue(issues.contains("Add a dark mode toggle [release 1.4]"), issues)
        assertTrue(issues.lines().first { it.startsWith("#3 ") }.let { !it.contains("[release") })
        assertTrue(call(chatToken, "factory_unassign_issues", """{"issueNumbers":[1,2]}""").text().startsWith("Removed #1, #2"))
        assertTrue(s.releaseStore.list("demo-app").all { it.issues.isEmpty() })
        Unit
    }

    @Test
    fun `release frames are mapped for every issue panel shape`() {
        // Unassigned issues answer release: null explicitly (always present).
        val dto = s.factory.issueDto("demo-app", dk.egernet.factory.github.GhIssue(number = 1, title = "t", state = "open", createdAt = "x", updatedAt = "x", htmlUrl = "u"), false, null)
        val o = json(AppJson.encodeToString(IssueDto.serializer(), dto)).jsonObject
        assertTrue("release" in o)
        assertEquals(JsonNull, o["release"])
        assertNull(dto.release)
        assertFalse(o["release"] is JsonArray)
    }
}
