package dk.egernet.factory

import dk.egernet.factory.workspace.WORKSPACE_FILE
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.webSocketSession
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
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Who sees a workspace project, its members, and the runs and sessions that had members checked out (§4 "Visibility"). */
class WorkspaceVisibilityTest {
    private val world = TestWorld(twoFactor = false)
    private val ws = "demo-workspace"

    init {
        // root can read demo-ws-lab too, so there is an observer who sees everything besides the owner.
        world.github.addRepo("demo-ws-lab", "lab", private = true, visibleTo = setOf("alice", "root"))
        world.launcher.stage = { p ->
            p.init()
            when (p.spec.kind) {
                "spec-writer", "builder" -> p.result("done")
                "approver" -> p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") })
                else -> p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
            }
            p.exit(0)
        }
    }

    @AfterTest fun tearDown() = world.close()

    private fun json(s: String) = AppJson.parseToJsonElement(s)

    private suspend fun HttpClient.postJson(path: String, body: String? = null): HttpResponse = post(path) {
        header("X-Factory-Request", "1")
        if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
    }

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String): HttpClient {
        val c = createClient { install(HttpCookies); install(WebSockets) }
        assertEquals(200, c.postJson("/api/auth/login", """{"username":"$user","password":"$pw"}""").status.value)
        for ((path, body) in listOf(
            "/api/me/github-token" to """{"token":"fake-$user"}""",
            "/api/me/claude-credential" to """{"kind":"oauth_token","value":"sk-ant-oat-$user"}""",
        )) {
            assertEquals(200, c.put(path) { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody(body) }.status.value)
        }
        return c
    }

    private fun ids(body: String) = json(body).jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    @Test
    fun `the project lists its members with access per caller`() = testApplication {
        application { agernModule(world.services) }
        val alice = login("alice", "pw-a")
        val bob = login("bob", "pw-b")

        fun members(body: String) = json(body).jsonObject["workspace"]!!.jsonObject.let { w ->
            assertEquals(".agern/workspace.yml", w["path"]!!.jsonPrimitive.content)
            assertEquals("true", w["valid"]!!.jsonPrimitive.content)
            w["members"]!!.jsonArray.map { it.jsonObject }
        }
        val a = members(alice.get("/api/projects/$ws").bodyAsText())
        assertEquals(listOf("app", "tools/cli", "lab"), a.map { it["directory"]!!.jsonPrimitive.content })
        assertEquals(listOf("demo-ws-app", "demo-ws-tools", "demo-ws-lab"), a.map { it["repository"]!!.jsonPrimitive.content })
        assertEquals(listOf("dev", "main", "main"), a.map { it["branch"]!!.jsonPrimitive.content })
        assertEquals(listOf("app", "demo-ws-tools", "lab"), a.map { it["label"]!!.jsonPrimitive.content })
        assertTrue(a.all { it["accessible"]!!.jsonPrimitive.content == "true" })
        val b = members(bob.get("/api/projects/$ws").bodyAsText())
        assertEquals(listOf("true", "true", "false"), b.map { it["accessible"]!!.jsonPrimitive.content })
        assertEquals(JsonNull, b[2]["branch"], "an inaccessible member shows its declared branch (none)")

        // Issues carry the members whose label they have.
        val issues = json(bob.get("/api/projects/$ws/issues").bodyAsText()).jsonArray.associate {
            it.jsonObject["number"]!!.jsonPrimitive.content.toInt() to it.jsonObject["workspaceMembers"]!!.jsonArray.map { m -> m.jsonObject["directory"]!!.jsonPrimitive.content }
        }
        assertEquals(mapOf(1 to listOf("app"), 2 to listOf("tools/cli"), 3 to emptyList(), 4 to listOf("app", "tools/cli"), 5 to listOf("lab")), issues)
        assertEquals(listOf("app"), json(bob.get("/api/projects/$ws/issues/1").bodyAsText()).jsonObject["workspaceMembers"]!!.jsonArray.map { it.jsonObject["directory"]!!.jsonPrimitive.content })

        // Refusals over the API: bob cannot run #5, nobody can run #3.
        val r5 = bob.postJson("/api/projects/$ws/runs", """{"issueNumber":5}""")
        assertEquals(403, r5.status.value)
        assertEquals("member_not_accessible", json(r5.bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(409, alice.postJson("/api/projects/$ws/runs", """{"issueNumber":3}""").status.value)

        // An invalid file lists every problem and no members.
        world.github.setFile(ws, WORKSPACE_FILE, "members:\n  - { directory: .., repository: x }\n  - { repository: y }")
        world.services.workspaceDefs.invalidate()
        val inv = json(alice.get("/api/projects/$ws").bodyAsText()).jsonObject["workspace"]!!.jsonObject
        assertEquals("false", inv["valid"]!!.jsonPrimitive.content)
        assertEquals(2, inv["errors"]!!.jsonArray.size, inv.toString())
        assertTrue(inv["members"]!!.jsonArray.isEmpty())
        assertEquals("workspace_invalid", json(alice.postJson("/api/projects/$ws/runs", """{"issueNumber":1}""").bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `runs and sessions that saw a member are hidden from whoever cannot read it`() = testApplication {
        application { agernModule(world.services) }
        val alice = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        val root = login("root", "pw-r")
        val bobFeed = bob.webSocketSession("/api/ws/feed")
        val rootFeed = root.webSocketSession("/api/ws/feed")
        withTimeout(10_000) { while (world.services.bus.feedSubscribers.value < 2) delay(10) }

        val created = alice.postJson("/api/projects/$ws/runs", """{"issueNumber":1}""")
        assertEquals(201, created.status.value, created.bodyAsText())
        val runId = json(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val run = world.awaitRun(UUID.fromString(runId), RunStatus.TERMINAL, timeoutMs = 30_000)
        assertEquals(RunStatus.MERGED, run.status, run.error)
        assertEquals(listOf("demo-ws-app", "demo-ws-tools", "demo-ws-lab"), run.contextRepos)
        val runJson = json(root.get("/api/runs/$runId").bodyAsText()).jsonObject
        assertEquals("demo-ws-app", runJson["targetRepo"]!!.jsonPrimitive.content)
        assertEquals("app", runJson["targetDirectory"]!!.jsonPrimitive.content)
        assertEquals("dev", runJson["baseBranch"]!!.jsonPrimitive.content)
        val sid = world.services.sessions.byRun(run.id).first().id.toString()

        // bob can read the workspace but not demo-ws-lab: the run and its sessions do not exist for him.
        assertEquals(404, bob.get("/api/runs/$runId").status.value)
        assertEquals(404, bob.get("/api/runs/$runId/retry").status.value)
        assertTrue(runId !in ids(bob.get("/api/runs").bodyAsText()))
        assertTrue(runId !in ids(bob.get("/api/projects/$ws/runs").bodyAsText()))
        assertEquals(404, bob.get("/api/sessions/$sid").status.value)
        assertEquals(404, bob.get("/api/sessions/$sid/events").status.value)
        assertTrue(sid !in ids(bob.get("/api/projects/$ws/sessions").bodyAsText()))
        assertTrue(sid !in ids(bob.get("/api/agents").bodyAsText()))
        bob.webSocket("/api/ws/sessions/$sid") {
            val f = incoming.receiveCatching()
            assertTrue(f.isClosed || f.getOrNull() is Frame.Close)
        }
        // root can read every member: he sees it all.
        assertEquals(200, root.get("/api/runs/$runId").status.value)
        assertTrue(runId in ids(root.get("/api/projects/$ws/runs").bodyAsText()))
        assertEquals(200, root.get("/api/sessions/$sid").status.value)
        assertTrue(sid in ids(root.get("/api/agents").bodyAsText()))

        // The feed: root got the run's frames; bob gets the next visible run's frames without any of the hidden one.
        suspend fun frame(f: DefaultClientWebSocketSession): JsonObject =
            json((f.incoming.receive() as Frame.Text).readText()).jsonObject
        suspend fun runFrameIds(f: DefaultClientWebSocketSession, until: String): List<String> = withTimeout(30_000) {
            val seen = mutableListOf<String>()
            while (true) {
                val fr = frame(f)
                val id = when (fr["type"]!!.jsonPrimitive.content) {
                    "run" -> fr["run"]!!.jsonObject["id"]!!.jsonPrimitive.content
                    "session" -> fr["session"]!!.jsonObject["runId"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
                    else -> null
                } ?: continue
                seen += id
                if (id == until) return@withTimeout seen
            }
            @Suppress("UNREACHABLE_CODE") seen
        }
        assertTrue(runId in runFrameIds(rootFeed, runId))
        val other = json(alice.postJson("/api/projects/demo-app/runs", """{"issueNumber":1}""").bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val bobSaw = runFrameIds(bobFeed, other)
        assertTrue(runId !in bobSaw, bobSaw.toString())
        world.awaitRun(UUID.fromString(other), RunStatus.TERMINAL, timeoutMs = 30_000)
    }

    @Test
    fun `a chat records every member clone on disk, whatever the definition says now`() {
        val alice = world.user("alice")
        val first = world.services.agents.createChat(alice, ws, null)
        awaitContext(first.id, 3)
        // demo-ws-lab is dropped from the definition: its clone stays in alice's checkout, so a new chat still records it.
        world.github.setFile(
            ws, WORKSPACE_FILE,
            dk.egernet.factory.github.FakeGitHub.DEMO_WORKSPACE_FILE.substringBefore("  - directory: lab"),
        )
        world.services.workspaceDefs.invalidate()
        val second = world.services.agents.createChat(alice, ws, null)
        assertEquals(setOf("demo-ws-app", "demo-ws-tools", "demo-ws-lab"), awaitContext(second.id, 3))
        // Also when the definition is gone (or cannot be read at all).
        world.github.setFile(ws, WORKSPACE_FILE, null)
        world.services.workspaceDefs.invalidate()
        val third = world.services.agents.createChat(alice, ws, null)
        assertEquals(setOf("demo-ws-app", "demo-ws-tools", "demo-ws-lab"), awaitContext(third.id, 3))
        // So bob, who can read the workspace but not demo-ws-lab, does not see that chat.
        val bob = world.user("bob")
        kotlin.test.assertFalse(world.services.access.canSee(bob, ws, world.services.sessions.get(third.id)!!.contextRepos))
    }

    private fun awaitContext(id: UUID, n: Int): Set<String> {
        val deadline = System.currentTimeMillis() + 10_000
        while (world.services.sessions.get(id)!!.contextRepos.size < n && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return world.services.sessions.get(id)!!.contextRepos.toSet()
    }

    @Test
    fun `a chat records the members it checked out`() = testApplication {
        application { agernModule(world.services) }
        val alice = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        val chat = json(alice.postJson("/api/projects/$ws/sessions", "{}").bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val rec = withTimeout(10_000) {
            while (true) {
                val r = world.services.sessions.get(UUID.fromString(chat))!!
                if (r.contextRepos.isNotEmpty()) return@withTimeout r
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
        assertEquals(setOf("demo-ws-app", "demo-ws-tools", "demo-ws-lab"), rec.contextRepos.toSet())
        assertEquals(404, bob.get("/api/sessions/$chat").status.value)
        // bob's own chat has no demo-ws-lab: alice and bob both see it.
        val bobChat = json(bob.postJson("/api/projects/$ws/sessions", "{}").bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        withTimeout(10_000) { while (world.services.sessions.get(UUID.fromString(bobChat))!!.contextRepos.size < 2) delay(20) }
        assertEquals(setOf("demo-ws-app", "demo-ws-tools"), world.services.sessions.get(UUID.fromString(bobChat))!!.contextRepos.toSet())
        assertEquals(200, alice.get("/api/sessions/$bobChat").status.value)
    }
}
