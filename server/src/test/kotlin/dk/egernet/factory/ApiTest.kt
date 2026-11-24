package dk.egernet.factory

import dk.egernet.factory.mcp.McpGrant
import dk.egernet.factory.mcp.McpRegistry
import dk.egernet.factory.security.Base32
import dk.egernet.factory.security.Totp
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
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
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private fun ApplicationTestBuilder.browser(): HttpClient = createClient {
    install(HttpCookies)
    install(WebSockets)
}

private suspend fun HttpClient.postJson(path: String, body: String? = null, csrf: Boolean = true, origin: String? = null): HttpResponse =
    post(path) {
        if (csrf) header("X-Factory-Request", "1")
        if (origin != null) header("Origin", origin)
        if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
    }

private suspend fun HttpClient.putJson(path: String, body: String): HttpResponse =
    put(path) { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody(body) }

private fun json(r: String) = AppJson.parseToJsonElement(r)

class TwoFactorApiTest {
    private val world = TestWorld(twoFactor = true)
    @AfterTest fun tearDown() = world.close()

    @Test
    fun `enrol, login with code, recovery code, lockout, pending sessions see nothing`() = testApplication {
        application { agernModule(world.services) }
        val c = browser()

        // Password step -> pending session in 'enroll'.
        val step = json(c.postJson("/api/auth/login", """{"username":"alice","password":"pw-a"}""").bodyAsText()).jsonObject
        assertEquals("enroll", step["next"]!!.jsonPrimitive.content)
        val secret = step["secret"]!!.jsonPrimitive.content
        assertTrue(Base32.decode(secret).size >= 20)
        assertTrue(step["otpauthUri"]!!.jsonPrimitive.content.startsWith("otpauth://totp/Agern:alice?secret=$secret&issuer=Agern"))

        // A pending session is unauthenticated everywhere else, WebSockets included; /mcp ignores cookies.
        for (p in listOf("/api/me", "/api/projects", "/api/runs", "/api/agents")) assertEquals(401, c.get(p).status.value, p)
        assertEquals(401, c.putJson("/api/me/github-token", """{"token":"fake-alice"}""").status.value)
        assertFailsWith<Exception> { c.webSocketSession("/api/ws/feed") }
        assertEquals(401, c.post("/mcp") { contentType(ContentType.Application.Json); setBody("""{"jsonrpc":"2.0","id":1,"method":"ping"}""") }.status.value)
        assertEquals(409, c.postJson("/api/auth/totp", """{"code":"123456"}""").status.value, "wrong step")

        // Wrong enrolment code, then the right one.
        assertEquals(422, c.postJson("/api/auth/totp/enroll", """{"code":"000000"}""").status.value)
        val key = Base32.decode(secret)
        val now = Instant.now().epochSecond
        val enrolled = c.postJson("/api/auth/totp/enroll", """{"code":"${Totp.at(key, now)}"}""")
        assertEquals(200, enrolled.status.value)
        val done = json(enrolled.bodyAsText()).jsonObject
        assertEquals("done", done["next"]!!.jsonPrimitive.content)
        val recovery = done["recoveryCodes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(10, recovery.size)
        assertEquals(true, done["user"]!!.jsonObject["twoFactorEnabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(200, c.get("/api/me").status.value)
        c.webSocket("/api/ws/feed") { }

        // Logout; login again needs a code. The code already used (same time step) is a replay.
        assertEquals(204, c.postJson("/api/auth/logout").status.value)
        assertEquals(401, c.get("/api/me").status.value)
        assertEquals("totp", json(c.postJson("/api/auth/login", """{"username":"alice","password":"pw-a"}""").bodyAsText()).jsonObject["next"]!!.jsonPrimitive.content)
        assertEquals(422, c.postJson("/api/auth/totp", """{"code":"${Totp.at(key, now)}"}""").status.value, "replay rejected")
        val ok = c.postJson("/api/auth/totp", """{"code":"${Totp.at(key, now + 30)}"}""")
        assertEquals(200, ok.status.value, ok.bodyAsText())
        assertEquals(200, c.get("/api/me").status.value)

        // Recovery code works once.
        c.postJson("/api/auth/logout")
        c.postJson("/api/auth/login", """{"username":"alice","password":"pw-a"}""")
        assertEquals(200, c.postJson("/api/auth/totp", """{"code":"${recovery[0]}"}""").status.value)
        c.postJson("/api/auth/logout")
        c.postJson("/api/auth/login", """{"username":"alice","password":"pw-a"}""")
        assertEquals(422, c.postJson("/api/auth/totp", """{"code":"${recovery[0]}"}""").status.value, "single use")

        // Lockout: 5 failures in total -> 423, and then even a correct code is refused.
        repeat(3) { assertEquals(422, c.postJson("/api/auth/totp", """{"code":"000000"}""").status.value) }
        val lockedResp = c.postJson("/api/auth/totp", """{"code":"000000"}""")
        assertEquals(423, lockedResp.status.value)
        val lockedErr = json(lockedResp.bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals("locked", lockedErr["code"]!!.jsonPrimitive.content)
        val lockedUntil = Instant.parse(lockedErr["lockedUntil"]!!.jsonPrimitive.content)
        assertTrue(lockedUntil.isAfter(Instant.now().plusSeconds(14 * 60)), lockedUntil.toString())
        val again = c.postJson("/api/auth/totp", """{"code":"${recovery[1]}"}""")
        assertEquals(423, again.status.value)
        assertEquals(lockedUntil, Instant.parse(json(again.bodyAsText()).jsonObject["error"]!!.jsonObject["lockedUntil"]!!.jsonPrimitive.content))

        val alice = world.services.users.byUsername("alice")!!
        val audit = world.services.twoFactorStore.auditEvents(alice.id)
        assertTrue(audit.containsAll(listOf("totp_enrolled", "totp_failed", "recovery_used", "totp_locked")), audit.toString())

        // Admin reset: alice must enrol again.
        val admin = browser()
        world.services.users.upsertOnLogin("root", "Root", true)
        val rootStep = json(admin.postJson("/api/auth/login", """{"username":"root","password":"pw-r"}""").bodyAsText()).jsonObject
        val rootKey = Base32.decode(rootStep["secret"]!!.jsonPrimitive.content)
        admin.postJson("/api/auth/totp/enroll", """{"code":"${Totp.at(rootKey, Instant.now().epochSecond)}"}""")
        val listed = json(admin.get("/api/admin/users").bodyAsText()).jsonArray.single { it.jsonObject["username"]!!.jsonPrimitive.content == "alice" }.jsonObject
        assertEquals(lockedUntil, Instant.parse(listed["twoFactorLockedUntil"]!!.jsonPrimitive.content), "admins see the lock")
        val reset = admin.postJson("/api/admin/users/${alice.id}/reset-2fa")
        assertEquals(200, reset.status.value)
        assertEquals(false, json(reset.bodyAsText()).jsonObject["twoFactorEnabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(kotlinx.serialization.json.JsonNull, json(reset.bodyAsText()).jsonObject["twoFactorLockedUntil"], "reset clears the lock")
        assertEquals("enroll", json(c.postJson("/api/auth/login", """{"username":"alice","password":"pw-a"}""").bodyAsText()).jsonObject["next"]!!.jsonPrimitive.content)
        assertEquals(401, c.postJson("/api/admin/users/${alice.id}/reset-2fa").status.value, "pending session")
    }

    @Test
    fun `session id rotates when the session becomes full`() = testApplication {
        application { agernModule(world.services) }
        val c = createClient { }
        val r1 = c.postJson("/api/auth/login", """{"username":"bob","password":"pw-b"}""")
        val pending = r1.headers.getAll("Set-Cookie")!!.first { it.startsWith("sf_session=") }.substringBefore(';')
        assertTrue(r1.headers["Set-Cookie"]!!.contains("HttpOnly"))
        assertTrue(r1.headers["Set-Cookie"]!!.contains("SameSite=Lax"))
        val secret = json(r1.bodyAsText()).jsonObject["secret"]!!.jsonPrimitive.content
        val r2 = c.post("/api/auth/totp/enroll") {
            header("X-Factory-Request", "1"); header("Cookie", pending)
            contentType(ContentType.Application.Json); setBody("""{"code":"${Totp.at(Base32.decode(secret), Instant.now().epochSecond)}"}""")
        }
        assertEquals(200, r2.status.value)
        val full = r2.headers.getAll("Set-Cookie")!!.first { it.startsWith("sf_session=") }.substringBefore(';')
        assertNotEquals(pending, full)
        assertEquals(401, c.get("/api/me") { header("Cookie", pending) }.status.value, "old id is dead")
        assertEquals(200, c.get("/api/me") { header("Cookie", full) }.status.value)
    }
}

class ApiTest {
    private val world = TestWorld(twoFactor = false)
    @AfterTest fun tearDown() = world.close()

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String, github: Boolean = true, claude: Boolean = true): HttpClient {
        val c = browser()
        val r = c.postJson("/api/auth/login", """{"username":"$user","password":"$pw"}""")
        assertEquals(200, r.status.value, r.bodyAsText())
        if (github) assertEquals(200, c.putJson("/api/me/github-token", """{"token":"fake-$user"}""").status.value)
        if (claude) assertEquals(200, c.putJson("/api/me/claude-credential", """{"kind":"oauth_token","value":"sk-ant-oat-$user"}""").status.value)
        return c
    }

    private suspend fun waitForEvents(c: HttpClient, sid: String, predicate: (JsonArray) -> Boolean): JsonArray = withTimeout(10_000) {
        while (true) {
            val ev = json(c.get("/api/sessions/$sid/events").bodyAsText()).jsonArray
            if (predicate(ev)) return@withTimeout ev
            delay(25)
        }
        @Suppress("UNREACHABLE_CODE") JsonArray(emptyList())
    }

    @Test
    fun `health, providers, csrf and origin`() = testApplication {
        application { agernModule(world.services) }
        val c = browser()
        val health = json(c.get("/api/health").bodyAsText()).jsonObject
        assertEquals("ok", health["status"]!!.jsonPrimitive.content)
        assertEquals("agern", health["name"]!!.jsonPrimitive.content)
        assertEquals("test-org", json(c.get("/api/auth/providers").bodyAsText()).jsonObject["org"]!!.jsonPrimitive.content)
        assertEquals(403, c.postJson("/api/auth/login", """{"username":"alice","password":"pw-a"}""", csrf = false).status.value)
        assertEquals(403, c.postJson("/api/auth/login", """{"username":"alice","password":"pw-a"}""", origin = "http://evil.example").status.value)
        assertEquals(401, c.postJson("/api/auth/login", """{"username":"alice","password":"nope"}""").status.value)
        assertEquals(200, c.postJson("/api/auth/login", """{"username":"alice","password":"pw-a"}""", origin = "http://localhost:8080").status.value)
        assertEquals(403, c.put("/api/me/github-token") { setBody("{}") }.status.value, "CSRF header missing")
        assertEquals(422, c.putJson("/api/me/github-token", """{"token":"ghp_bogus"}""").status.value)
        val me = json(c.get("/api/me").bodyAsText()).jsonObject
        assertEquals(false, me["hasGithubToken"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(404, c.get("/api/nope").status.value)
        assertEquals(404, c.get("/some/spa/route").status.value, "no frontend embedded in tests")
    }

    @Test
    fun `chat ownership, observation, visibility and websocket replay`() = testApplication {
        application { agernModule(world.services) }
        val alice = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        val root = login("root", "pw-r")

        val projects = json(alice.get("/api/projects").bodyAsText()).jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        val workspace = setOf("demo-workspace", "demo-ws-app", "demo-ws-tools", "demo-shop", "demo-shop-api", "demo-shop-web")
        assertEquals(setOf("demo-app", "private-lab", "demo-ws-lab") + workspace, projects.toSet())
        assertEquals(setOf("demo-app") + workspace, json(bob.get("/api/projects").bodyAsText()).jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet())
        // New fields of a project that is not a workspace: no workspace, no members on its issues, no target on its runs.
        assertEquals(JsonNull, json(alice.get("/api/projects/demo-app").bodyAsText()).jsonObject["workspace"])
        assertEquals(JsonNull, json(alice.get("/api/projects").bodyAsText()).jsonArray.first { it.jsonObject["name"]!!.jsonPrimitive.content == "demo-workspace" }.jsonObject["workspace"])
        assertEquals(404, bob.get("/api/projects/private-lab/issues").status.value)
        val issues = json(alice.get("/api/projects/demo-app/issues").bodyAsText()).jsonArray
        assertEquals(3, issues.size)
        assertTrue(issues.all { it.jsonObject["workspaceMembers"]!!.jsonArray.isEmpty() })
        assertEquals("bug", issues.first { it.jsonObject["number"]!!.jsonPrimitive.content == "2" }.jsonObject["kind"]!!.jsonPrimitive.content)

        val created = alice.postJson("/api/projects/demo-app/sessions", "{}")
        assertEquals(201, created.status.value, created.bodyAsText())
        val sid = json(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        assertEquals(202, alice.postJson("/api/sessions/$sid/input", """{"text":"hello there"}""").status.value)
        // The result event is published before the session goes idle (a separate status event): wait for both.
        val events = waitForEvents(alice, sid) { ev ->
            val result = ev.indexOfFirst { it.jsonObject["type"]!!.jsonPrimitive.content == "claude" && it.toString().contains("\"result\"") }
            result >= 0 && ev.drop(result + 1).any {
                it.jsonObject["type"]!!.jsonPrimitive.content == "status" && it.jsonObject["payload"]!!.jsonObject["status"]!!.jsonPrimitive.content == "idle"
            }
        }
        assertTrue(events.any { it.jsonObject["type"]!!.jsonPrimitive.content == "user" && it.toString().contains("hello there") })
        val session = json(alice.get("/api/sessions/$sid").bodyAsText()).jsonObject
        assertEquals("idle", session["status"]!!.jsonPrimitive.content)
        assertEquals(true, session["live"]!!.jsonPrimitive.content.toBoolean(), "idle chat with a process waiting for input")
        assertTrue(json(bob.get("/api/agents?active=true").bodyAsText()).jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == sid })
        assertEquals("hello there", session["title"]!!.jsonPrimitive.content)

        // Bob may observe but not drive; admin may stop but not drive.
        assertEquals(200, bob.get("/api/sessions/$sid/events").status.value)
        assertEquals(403, bob.postJson("/api/sessions/$sid/input", """{"text":"use my creds?"}""").status.value)
        assertEquals(403, bob.postJson("/api/sessions/$sid/interrupt").status.value)
        assertEquals(403, bob.postJson("/api/sessions/$sid/stop").status.value)
        assertEquals(403, root.postJson("/api/sessions/$sid/input", """{"text":"x"}""").status.value)
        assertTrue(world.launcher.launched.all { it.spec.identity.username == "alice" })

        // WebSocket replay: persisted events in order.
        val seen = mutableListOf<Long>()
        bob.webSocket("/api/ws/sessions/$sid?after=0") {
            withTimeout(5000) {
                while (seen.size < events.size) {
                    val f = incoming.receive() as Frame.Text
                    seen += json(f.readText()).jsonObject["seq"]!!.jsonPrimitive.content.toLong()
                }
            }
        }
        assertEquals(events.map { it.jsonObject["seq"]!!.jsonPrimitive.content.toLong() }, seen)

        // /factory commands are handled by the server.
        alice.postJson("/api/sessions/$sid/input", """{"text":"/factory help"}""")
        waitForEvents(alice, sid) { ev -> ev.any { it.jsonObject["type"]!!.jsonPrimitive.content == "system" && it.toString().contains("/factory run") } }

        val stopped = root.postJson("/api/sessions/$sid/stop")
        assertEquals(200, stopped.status.value)
        assertEquals("stopped", json(stopped.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(false, json(stopped.bodyAsText()).jsonObject["live"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(json(bob.get("/api/agents?active=true").bodyAsText()).jsonArray.none { it.jsonObject["id"]!!.jsonPrimitive.content == sid })

        // Private repo sessions are invisible to bob, over REST and WebSocket.
        val priv = json(alice.postJson("/api/projects/private-lab/sessions", "{}").bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(404, bob.get("/api/sessions/$priv").status.value)
        assertEquals(404, bob.get("/api/sessions/$priv/events").status.value)
        assertEquals(404, bob.postJson("/api/sessions/$priv/input", """{"text":"x"}""").status.value)
        bob.webSocket("/api/ws/sessions/$priv") {
            val f = incoming.receiveCatching()
            assertTrue(f.isClosed || f.getOrNull() is Frame.Close)
        }
        val bobAgents = json(bob.get("/api/agents").bodyAsText()).jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue(priv !in bobAgents && sid in bobAgents)
    }

    @Test
    fun `the GitHub token's kind and scopes are recorded and shown`() = testApplication {
        application { agernModule(world.services) }
        val alice = login("alice", "pw-a")
        val me = json(alice.get("/api/me").bodyAsText()).jsonObject
        assertEquals("classic", me["githubTokenKind"]!!.jsonPrimitive.content)
        assertEquals(listOf("read:org", "repo"), me["githubTokenScopes"]!!.jsonArray.map { it.jsonPrimitive.content })

        // A token without `repo` sees only public repositories.
        val narrowed = json(alice.putJson("/api/me/github-token", """{"token":"fake-alice~public_repo"}""").bodyAsText()).jsonObject
        assertEquals(listOf("public_repo"), narrowed["githubTokenScopes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(
            setOf("demo-app", "demo-workspace", "demo-ws-app", "demo-ws-tools", "demo-shop", "demo-shop-api", "demo-shop-web"),
            json(alice.get("/api/projects").bodyAsText()).jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet(),
        )

        val removed = json(alice.delete("/api/me/github-token") { header("X-Factory-Request", "1") }.bodyAsText()).jsonObject
        assertEquals(kotlinx.serialization.json.JsonNull, removed["githubTokenScopes"])
        assertEquals(kotlinx.serialization.json.JsonNull, removed["githubTokenKind"])
    }

    @Test
    fun `runs via the API respect ownership`() = testApplication {
        application { agernModule(world.services) }
        world.launcher.stage = { p ->
            p.init()
            when (p.spec.kind) {
                "approver" -> p.result(null, kotlinx.serialization.json.buildJsonObject {
                    put("verdict", kotlinx.serialization.json.JsonPrimitive("ESCALATE")); put("summary", kotlinx.serialization.json.JsonPrimitive("unsure"))
                })
                "security", "ux", "ui-design", "code" -> p.result(null, kotlinx.serialization.json.buildJsonObject {
                    put("verdict", kotlinx.serialization.json.JsonPrimitive("PASS")); put("summary", kotlinx.serialization.json.JsonPrimitive("ok"))
                    put("findings", JsonArray(emptyList()))
                })
                else -> p.result("done")
            }
            p.exit(0)
        }
        val alice = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        val r = alice.postJson("/api/projects/demo-app/runs", """{"issueNumber":1}""")
        assertEquals(201, r.status.value, r.bodyAsText())
        val runId = json(r.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(409, alice.postJson("/api/projects/demo-app/runs", """{"issueNumber":1}""").status.value)
        world.awaitRun(UUID.fromString(runId), RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(403, bob.postJson("/api/runs/$runId/approve").status.value)
        assertEquals(403, bob.postJson("/api/runs/$runId/cancel").status.value)
        val approved = alice.postJson("/api/runs/$runId/approve")
        assertEquals("merged", json(approved.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)
        val run = json(bob.get("/api/runs/$runId").bodyAsText()).jsonObject
        assertEquals(7, run["stages"]!!.jsonArray.size)
        assertEquals(JsonNull, run["targetRepo"])
        assertEquals(JsonNull, run["targetDirectory"])
        assertEquals("opus", run["stages"]!!.jsonArray.first().jsonObject["model"]!!.jsonPrimitive.content)
        assertEquals(0, run["stages"]!!.jsonArray.first().jsonObject["round"]!!.jsonPrimitive.content.toInt(), "round 0 is the initial build")

        // Status filter: repeatable and comma lists; unknown statuses are rejected.
        fun ids(body: String) = json(body).jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf(runId), ids(bob.get("/api/runs?status=merged").bodyAsText()))
        assertEquals(listOf(runId), ids(bob.get("/api/runs?status=needs_human,merged").bodyAsText()))
        assertEquals(listOf(runId), ids(bob.get("/api/runs?status=needs_human&status=merged").bodyAsText()))
        assertEquals(emptyList(), ids(bob.get("/api/runs?status=needs_human").bodyAsText()))
        assertEquals(emptyList(), ids(bob.get("/api/runs?active=true").bodyAsText()))
        assertEquals(400, bob.get("/api/runs?status=bogus").status.value)
    }

    @Test
    fun `the owner messages a running builder through the input endpoint, others cannot`() = testApplication {
        application { agernModule(world.services) }
        world.launcher.stage = { p ->
            p.init()
            when (p.spec.kind) {
                "builder" -> {
                    while (!p.killed) {
                        val m = p.pickUp(50) ?: continue
                        if ("finish" in m) break
                    }
                    p.result("done")
                    p.followUpsUntilClosed()
                }
                "approver" -> { p.result(null, kotlinx.serialization.json.buildJsonObject {
                    put("verdict", kotlinx.serialization.json.JsonPrimitive("APPROVE")); put("summary", kotlinx.serialization.json.JsonPrimitive("ok"))
                }); p.exit(0) }
                "security", "ux", "ui-design", "code" -> { p.result(null, kotlinx.serialization.json.buildJsonObject {
                    put("verdict", kotlinx.serialization.json.JsonPrimitive("PASS")); put("summary", kotlinx.serialization.json.JsonPrimitive("ok"))
                    put("findings", JsonArray(emptyList()))
                }); p.exit(0) }
                else -> { p.result("spec"); p.exit(0) }
            }
        }
        val alice = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        val root = login("root", "pw-r")
        val runId = json(alice.postJson("/api/projects/demo-app/runs", """{"issueNumber":1}""").bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val sid = withTimeout(5000) {
            while (true) {
                val b = world.services.sessions.byRun(UUID.fromString(runId)).firstOrNull { it.kind == "builder" }
                if (b != null && world.services.agents.isLive(b.id)) return@withTimeout b.id.toString()
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE") ""
        }
        assertEquals(403, bob.postJson("/api/sessions/$sid/input", """{"text":"bob was here"}""").status.value)
        assertEquals(403, root.postJson("/api/sessions/$sid/input", """{"text":"admin was here"}""").status.value)
        assertEquals(422, alice.postJson("/api/sessions/$sid/input", """{"text":"x","attachmentIds":["${UUID.randomUUID()}"]}""").status.value)
        val r = alice.postJson("/api/sessions/$sid/input", """{"text":"make it blue"}""")
        assertEquals(202, r.status.value, r.bodyAsText())
        val body = json(r.bodyAsText()).jsonObject
        assertEquals("queued", body["status"]!!.jsonPrimitive.content)
        assertEquals("builder", body["role"]!!.jsonPrimitive.content)
        assertTrue(body["note"]!!.jsonPrimitive.content.contains("next step"))
        val ev = waitForEvents(bob, sid) { ev -> ev.any { it.toString().contains("delivered to the agent") } }
        assertTrue(ev.any { it.jsonObject["type"]!!.jsonPrimitive.content == "user" && it.toString().contains("make it blue") && it.toString().contains("\"origin\":\"direct\"") })
        assertTrue(ev.none { it.toString().contains("was here") })
        assertEquals(202, alice.postJson("/api/sessions/$sid/input", """{"text":"finish"}""").status.value)
        world.awaitRun(UUID.fromString(runId), RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        val late = alice.postJson("/api/sessions/$sid/input", """{"text":"too late"}""")
        assertEquals(409, late.status.value)
        assertEquals("agent_not_running", json(late.bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        val reviewer = world.services.sessions.byRun(UUID.fromString(runId)).first { it.kind == "security" }.id
        val rr = alice.postJson("/api/sessions/$reviewer/input", """{"text":"pass it"}""")
        assertEquals(409, rr.status.value)
        assertEquals("agent_does_not_accept_messages", json(rr.bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `creating an issue emits a feed frame to viewers with access only`() = testApplication {
        application { agernModule(world.services) }
        val alice = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        suspend fun openIssues(c: HttpClient) =
            json(c.get("/api/projects/demo-app").bodyAsText()).jsonObject["openIssues"]!!.jsonPrimitive.content.toInt()
        val before = openIssues(bob) // primes bob's repo cache

        val aliceFeed = alice.webSocketSession("/api/ws/feed")
        val bobFeed = bob.webSocketSession("/api/ws/feed")
        // The feed has no replay: wait until both connections are subscribed before creating anything.
        withTimeout(10_000) { while (world.services.bus.feedSubscribers.value < 2) delay(10) }

        suspend fun nextIssueFrame(ws: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession): JsonObject = withTimeout(10_000) {
            while (true) {
                val f = ws.incoming.receive() as? Frame.Text ?: continue
                val o = json(f.readText()).jsonObject
                if (o["type"]!!.jsonPrimitive.content == "issue") return@withTimeout o
            }
            @Suppress("UNREACHABLE_CODE") JsonObject(emptyMap())
        }
        fun check(frame: JsonObject, repo: String, title: String, kind: String) {
            assertEquals(repo, frame["repo"]!!.jsonPrimitive.content)
            val issue = frame["issue"]!!.jsonObject
            assertEquals(title, issue["title"]!!.jsonPrimitive.content)
            assertEquals(kind, issue["kind"]!!.jsonPrimitive.content)
            assertEquals("open", issue["state"]!!.jsonPrimitive.content)
            assertTrue("body" !in issue, "frame carries no body")
            assertEquals(JsonNull, issue["activeRunId"])
        }

        assertEquals(201, alice.postJson("/api/projects/private-lab/issues", """{"title":"Secret plan","body":"x","kind":"task"}""").status.value)
        assertEquals(201, alice.postJson("/api/projects/demo-app/issues", """{"title":"Live bug","body":"y","kind":"bug"}""").status.value)
        val aliceRec = world.user("alice")
        val sessionRec = world.services.sessions.create("demo-app", "chat", "opus", "idle", aliceRec.id, null)
        val token = world.services.mcpRegistry.issue(McpGrant(sessionRec.id, aliceRec.id, "demo-app", null, McpRegistry.CHAT_TOOLS))
        val mcp = createClient { }.post("/mcp") {
            header("Authorization", "Bearer $token"); contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"factory_create_issue","arguments":{"title":"Agent feature","body":"z","kind":"feature"}}}""")
        }
        assertTrue(mcp.bodyAsText().contains("Created issue #"), mcp.bodyAsText())

        check(nextIssueFrame(aliceFeed), "private-lab", "Secret plan", "task")
        check(nextIssueFrame(aliceFeed), "demo-app", "Live bug", "bug")
        check(nextIssueFrame(aliceFeed), "demo-app", "Agent feature", "feature")
        // Bob cannot see private-lab: his first issue frame is the demo-app one.
        check(nextIssueFrame(bobFeed), "demo-app", "Live bug", "bug")
        check(nextIssueFrame(bobFeed), "demo-app", "Agent feature", "feature")

        // Failed creations emit nothing: the next frame is the following valid one.
        assertEquals(400, alice.postJson("/api/projects/demo-app/issues", """{"title":" ","body":"","kind":"bug"}""").status.value)
        assertEquals(400, alice.postJson("/api/projects/demo-app/issues", """{"title":"Odd","body":"","kind":"chore"}""").status.value)
        assertEquals(404, bob.postJson("/api/projects/private-lab/issues", """{"title":"Peek","body":"","kind":"bug"}""").status.value)
        assertEquals(201, alice.postJson("/api/projects/demo-app/issues", """{"title":"After failure","body":"","kind":"task"}""").status.value)
        check(nextIssueFrame(aliceFeed), "demo-app", "After failure", "task")
        check(nextIssueFrame(bobFeed), "demo-app", "After failure", "task")

        // The repo cache was dropped, so the open-issue count is fresh for other users too.
        assertEquals(before + 3, openIssues(bob))
        aliceFeed.close(); bobFeed.close()
    }

    @Test
    fun `mcp endpoint`() = testApplication {
        application { agernModule(world.services) }
        val alice = world.user("alice")
        val c = createClient { }
        val sessionRec = world.services.sessions.create("demo-app", "chat", "opus", "idle", alice.id, null)
        val chatToken = world.services.mcpRegistry.issue(McpGrant(sessionRec.id, alice.id, "demo-app", null, McpRegistry.CHAT_TOOLS))
        val stageToken = world.services.mcpRegistry.issue(McpGrant(sessionRec.id, alice.id, "demo-app", UUID.randomUUID(), McpRegistry.STAGE_TOOLS))
        suspend fun rpc(token: String?, body: String) = c.post("/mcp") {
            if (token != null) header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json); setBody(body)
        }
        assertEquals(401, rpc(null, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""").status.value)
        assertEquals(401, rpc("nope", """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""").status.value)
        val init = json(rpc(chatToken, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""").bodyAsText()).jsonObject
        assertEquals("agern", init["result"]!!.jsonObject["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(202, rpc(chatToken, """{"jsonrpc":"2.0","method":"notifications/initialized"}""").status.value)
        val tools = json(rpc(chatToken, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""").bodyAsText()).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(16, tools.size)
        assertTrue(tools.any { it.jsonObject["name"]!!.jsonPrimitive.content == "factory_send_to_agent" })
        val stageTools = json(rpc(stageToken, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""").bodyAsText()).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(setOf("factory_get_issue", "factory_request_software"), stageTools.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet())
        val call = json(rpc(chatToken, """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"factory_list_issues","arguments":{}}}""").bodyAsText()).jsonObject
        assertTrue(call["result"]!!.toString().contains("Add a dark mode toggle"))
        val denied = json(rpc(stageToken, """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"factory_start_run","arguments":{"issueNumber":1}}}""").bodyAsText()).jsonObject
        assertEquals("true", denied["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content)
        val sw = json(rpc(stageToken, """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"factory_request_software","arguments":{"name":"ffmpeg","reason":"video"}}}""").bodyAsText()).jsonObject
        assertTrue(sw.toString().contains("ffmpeg"))
        assertEquals("ffmpeg", world.services.softwareRequests.all().single().name)
        assertEquals(403, c.post("/mcp") { header("Origin", "http://evil.example"); header("Authorization", "Bearer $chatToken"); setBody("{}") }.status.value)
        val ping = json(rpc(chatToken, """{"jsonrpc":"2.0","id":6,"method":"ping"}""").bodyAsText()) as JsonObject
        assertTrue(ping.containsKey("result"))
    }
}
