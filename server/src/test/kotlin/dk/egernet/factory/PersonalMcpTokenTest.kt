package dk.egernet.factory

import dk.egernet.factory.mcp.McpGrant
import dk.egernet.factory.mcp.McpIdentity
import dk.egernet.factory.mcp.McpRegistry
import dk.egernet.factory.security.Tokens
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Personal MCP tokens (docs/ARCHITECTURE.md §7 "Personal tokens"). */
class PersonalMcpTokenTest {
    private val clock = MutableClock()
    private val world = TestWorld(twoFactor = false, clock = clock, tweak = { it.copy(publicUrl = "http://agern.example/") })
    private val s = world.services
    @AfterTest fun tearDown() = world.close()

    private fun ApplicationTestBuilder.signedIn(user: String, password: String): HttpClient {
        val c = createClient { install(HttpCookies) }
        runBlocking {
            val r = c.post("/api/auth/login") {
                header("X-Factory-Request", "1"); contentType(ContentType.Application.Json)
                setBody("""{"username":"$user","password":"$password"}""")
            }
            assertEquals(200, r.status.value, r.bodyAsText())
        }
        return c
    }

    private suspend fun HttpClient.create(body: String): HttpResponse = post("/api/me/mcp-tokens") {
        header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody(body)
    }

    private suspend fun HttpClient.revoke(path: String): HttpResponse = delete(path) { header("X-Factory-Request", "1") }

    private fun json(r: String) = AppJson.parseToJsonElement(r)

    /** A token created directly through the store, as the create endpoint does. */
    private fun token(user: String, repo: String = "demo-app", name: String = "laptop", expiresAt: java.time.Instant? = null): Pair<UUID, String> {
        val u = s.users.byUsername(user) ?: world.user(user)
        val secret = dk.egernet.factory.mcp.McpAuthenticator.newToken()
        val rec = s.mcpTokens.create(u.id, repo, name, Tokens.sha256Hex(secret), clock.instant(), expiresAt)
        return rec.id to secret
    }

    private fun rpcBody(method: String, params: String = "{}", id: Int = 1) = """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""
    private fun callBody(tool: String, args: String) = rpcBody("tools/call", """{"name":"$tool","arguments":$args}""")

    private fun handle(token: String?, body: String, remote: String = "10.0.0.1") = runBlocking { s.mcp.handle(token, body, remote) }

    private fun result(token: String, body: String): JsonObject {
        val (status, text) = handle(token, body)
        assertEquals(200, status, text)
        return json(text!!).jsonObject["result"]!!.jsonObject
    }

    private fun call(token: String, tool: String, args: String = "{}"): Pair<Boolean, String> {
        val r = result(token, callBody(tool, args))
        return (r["isError"]?.jsonPrimitive?.content == "true") to r["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
    }

    private fun errorMessage(text: String?) = json(text!!).jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content

    // ------------------------------------------------------------ endpoints

    @Test
    fun `create, list and revoke over HTTP, with validation, audit and admin access`() = testApplication {
        application { agernModule(s) }
        val alice = signedIn("alice", "pw-a")
        val bob = signedIn("bob", "pw-b")
        val root = signedIn("root", "pw-r")
        listOf("alice", "bob", "root").forEach { world.user(it, admin = it == "root") }

        assertEquals(400, alice.create("""{"name":"  ","repo":"demo-app","expiresInDays":30}""").status.value)
        assertEquals(400, alice.create("""{"name":"${"x".repeat(61)}","repo":"demo-app","expiresInDays":30}""").status.value)
        assertEquals(400, alice.create("""{"name":"bad\u0007name","repo":"demo-app","expiresInDays":30}""").status.value)
        assertEquals(400, alice.create("""{"name":"laptop","repo":"demo-app","expiresInDays":0}""").status.value)
        assertEquals(400, alice.create("""{"name":"laptop","repo":"demo-app","expiresInDays":367}""").status.value)
        assertEquals(404, bob.create("""{"name":"laptop","repo":"private-lab","expiresInDays":30}""").status.value)

        val created = alice.create("""{"name":" laptop ","repo":"demo-app","expiresInDays":90}""")
        assertEquals(200, created.status.value, created.bodyAsText())
        assertEquals("no-store", created.headers["Cache-Control"])
        val body = json(created.bodyAsText()).jsonObject
        val secret = body["secret"]!!.jsonPrimitive.content
        assertTrue(secret.startsWith("agern_pat_") && secret.length > 40, secret)
        assertEquals("http://agern.example/mcp", body["mcpUrl"]!!.jsonPrimitive.content)
        assertEquals("agern-example-demo-app", body["serverName"]!!.jsonPrimitive.content)
        val dto = body["token"]!!.jsonObject
        assertEquals("laptop", dto["name"]!!.jsonPrimitive.content)
        assertEquals("demo-app", dto["repo"]!!.jsonPrimitive.content)
        assertEquals("alice", dto["owner"]!!.jsonObject["username"]!!.jsonPrimitive.content)
        assertEquals(clock.instant().plus(Duration.ofDays(90)), java.time.Instant.parse(dto["expiresAt"]!!.jsonPrimitive.content))
        assertEquals("null", dto["lastUsedAt"].toString())
        val id = dto["id"]!!.jsonPrimitive.content

        // Same name (case-insensitive) for the same project: 409; another project is fine.
        assertEquals("mcp_token_name_taken", json(alice.create("""{"name":"LAPTOP","repo":"demo-app","expiresInDays":null}""").bodyAsText())
            .jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        clock.advance(Duration.ofSeconds(1))
        assertEquals(200, alice.create("""{"name":"laptop","repo":"private-lab","expiresInDays":null}""").status.value)

        // The secret appears in no other answer.
        val list = alice.get("/api/me/mcp-tokens").bodyAsText()
        assertFalse(list.contains(secret) || list.contains(Tokens.sha256Hex(secret)), list)
        assertEquals(listOf("private-lab", "demo-app"), json(list).jsonArray.map { it.jsonObject["repo"]!!.jsonPrimitive.content })
        assertEquals("[]", bob.get("/api/me/mcp-tokens").bodyAsText())

        // Admin endpoints: admins only; they list everyone's tokens without secrets.
        assertEquals(403, alice.get("/api/admin/mcp-tokens").status.value)
        assertEquals(403, alice.revoke("/api/admin/mcp-tokens/$id").status.value)
        val all = root.get("/api/admin/mcp-tokens").bodyAsText()
        assertEquals(2, json(all).jsonArray.size)
        assertFalse(all.contains(secret) || all.contains(Tokens.sha256Hex(secret)))

        // It works on /mcp, through a reverse proxy too.
        val mcp = createClient { }
        suspend fun init(secretValue: String) = mcp.post("/mcp") {
            header("Authorization", "Bearer $secretValue"); header("X-Forwarded-For", "203.0.113.9")
            contentType(ContentType.Application.Json); setBody(rpcBody("initialize"))
        }
        assertEquals(200, init(secret).status.value)
        // Browsers may not call it.
        assertEquals(403, mcp.post("/mcp") { header("Origin", "http://agern.example"); header("Authorization", "Bearer $secret"); setBody(rpcBody("ping")) }.status.value)

        // Revoke: another user's token or an unknown id is 404; the owner's revocation is immediate.
        assertEquals(404, bob.revoke("/api/me/mcp-tokens/$id").status.value)
        assertEquals(404, bob.revoke("/api/me/mcp-tokens/${UUID.randomUUID()}").status.value)
        val after = alice.revoke("/api/me/mcp-tokens/$id")
        assertEquals(200, after.status.value)
        assertEquals(listOf("private-lab"), json(after.bodyAsText()).jsonArray.map { it.jsonObject["repo"]!!.jsonPrimitive.content })
        assertEquals(404, alice.revoke("/api/me/mcp-tokens/$id").status.value)
        val refused = init(secret)
        assertEquals(401, refused.status.value)
        assertEquals("This token was revoked", errorMessage(refused.bodyAsText()))

        // An admin revokes another user's token.
        val other = json(root.get("/api/admin/mcp-tokens").bodyAsText()).jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content
        assertEquals("[]", root.revoke("/api/admin/mcp-tokens/$other").bodyAsText())
        assertEquals(404, root.revoke("/api/admin/mcp-tokens/$other").status.value)

        val audit = s.db.query("SELECT event, detail FROM auth_audit WHERE event LIKE 'mcp_token_%' ORDER BY id") {
            it.getString("event") + " " + it.getString("detail")
        }
        assertEquals(
            listOf(
                "mcp_token_created \"laptop\" for demo-app", "mcp_token_created \"laptop\" for private-lab",
                "mcp_token_revoked \"laptop\" for demo-app", "mcp_token_revoked \"laptop\" for private-lab by admin root",
            ),
            audit,
        )
        val anyRow = s.db.query("SELECT * FROM auth_audit") { rs -> (1..rs.metaData.columnCount).joinToString { rs.getString(it) ?: "" } }
        assertFalse(anyRow.any { it.contains(secret) })
    }

    @Test
    fun `at most 50 active tokens per user`() = testApplication {
        application { agernModule(s) }
        val alice = signedIn("alice", "pw-a")
        world.user("alice")
        repeat(50) { token("alice", name = "t$it") }
        val r = alice.create("""{"name":"one more","repo":"demo-app","expiresInDays":30}""")
        assertEquals(409, r.status.value)
        assertTrue(r.bodyAsText().contains("too_many_mcp_tokens"))
    }

    // ------------------------------------------------------------ storage and validity

    @Test
    fun `only the hash is stored, and tokens survive a restart`() {
        world.user("alice")
        val (_, secret) = token("alice")
        val rows = s.db.query("SELECT * FROM mcp_tokens") { rs -> (1..rs.metaData.columnCount).map { rs.getString(it) ?: "" } }
        assertTrue(rows.single().none { it.contains(secret) || it.contains(secret.removePrefix("agern_pat_")) }, rows.toString())
        assertEquals(Tokens.sha256Hex(secret), s.db.queryOne("SELECT token_hash FROM mcp_tokens") { it.getString(1) })

        val restarted = Services(s.cfg, s.db, world.github, world.git, world.launcher, clock = clock)
        val (status, _) = runBlocking { restarted.mcp.handle(secret, rpcBody("initialize"), "10.0.0.1") }
        assertEquals(200, status)
        restarted.stop()
    }

    @Test
    fun `expiry, user removal and lost visibility refuse the token`() {
        world.user("alice")
        val (_, expiring) = token("alice", expiresAt = clock.instant().plus(Duration.ofDays(30)))
        assertEquals(200, handle(expiring, rpcBody("ping")).first)
        clock.advance(Duration.ofDays(30))
        val (status, text) = handle(expiring, rpcBody("ping"))
        assertEquals(401, status)
        assertEquals("This token has expired", errorMessage(text))
        assertTrue(s.mcpTokens.listForUser(s.users.byUsername("alice")!!.id).single().toDto(clock.instant()).expired)

        // The user's GitHub token is removed: refused at once (the visibility cache is invalidated).
        world.user("bob")
        val (_, bobs) = token("bob")
        assertEquals(200, handle(bobs, rpcBody("ping")).first)
        val bob = s.users.byUsername("bob")!!
        s.users.setGithub(bob.id, null, null)
        s.access.invalidate(bob.id)
        val gone = handle(bobs, rpcBody("ping"))
        assertEquals(401, gone.first)
        assertEquals("The token's user can no longer see test-org/demo-app", errorMessage(gone.second))

        // The user row is deleted: the token goes with it.
        world.user("carol")
        val (_, carols) = token("carol")
        assertEquals(200, handle(carols, rpcBody("ping")).first)
        s.db.update("DELETE FROM users WHERE username = 'carol'")
        assertEquals(401, handle(carols, rpcBody("ping")).first)
    }

    @Test
    fun `last use is recorded, at most once a minute`() {
        world.user("alice")
        val (id, secret) = token("alice")
        assertNull(s.mcpTokens.byId(id)!!.lastUsedAt)
        handle(secret, rpcBody("ping"))
        val first = s.mcpTokens.byId(id)!!.lastUsedAt
        assertEquals(clock.instant(), first)
        clock.advance(Duration.ofSeconds(30))
        handle(secret, rpcBody("ping"))
        assertEquals(first, s.mcpTokens.byId(id)!!.lastUsedAt)
        clock.advance(Duration.ofSeconds(31))
        handle(secret, rpcBody("ping"))
        assertEquals(clock.instant(), s.mcpTokens.byId(id)!!.lastUsedAt)
    }

    @Test
    fun `revoked rows are purged after 30 days at startup`() {
        world.user("alice")
        val (id, _) = token("alice")
        s.mcpTokens.revoke(id, clock.instant())
        s.recoverAfterRestart()
        assertNotNull(s.mcpTokens.byId(id))
        clock.advance(Duration.ofDays(31))
        s.recoverAfterRestart()
        assertNull(s.mcpTokens.byId(id))
    }

    // ------------------------------------------------------------ identity

    @Test
    fun `server names are derived from the instance and the project`() {
        assertEquals("agern-example-agern", McpIdentity.serverName("https://agern.example", "agern"))
        assertEquals("agern-localhost-8080-demo-app", McpIdentity.serverName("http://localhost:8080", "demo-app"))
        assertEquals("agern-lab-internal-tools", McpIdentity.serverName("https://LAB:443/", "Internal.Tools"))
        assertEquals("agern-factory-example-org-my-repo", McpIdentity.serverName("https://factory.example.org", "--my__repo--"))
        assertEquals("localhost:8080", McpIdentity.instance("http://localhost:8080/"))
        val long = McpIdentity.serverName("https://agern.example", "a".repeat(40) + "-" + "b".repeat(40))
        assertEquals(64, long.length)
        val cut = McpIdentity.serverName("https://agern.example", "x".repeat(44) + "-yyyy")
        assertTrue(cut.length <= 64 && !cut.endsWith("-") && Regex("^[a-z0-9][a-z0-9-]*$").matches(cut), cut)
    }

    @Test
    fun `initialize and tools list name the instance, project and user`() {
        world.user("alice")
        val (_, secret) = token("alice")
        val init = result(secret, rpcBody("initialize", """{"protocolVersion":"2025-06-18"}"""))
        val info = init["serverInfo"]!!.jsonObject
        assertEquals("agern-example-demo-app", info["name"]!!.jsonPrimitive.content)
        assertEquals("Agern · test-org/demo-app (agern.example)", info["title"]!!.jsonPrimitive.content)
        val instructions = init["instructions"]!!.jsonPrimitive.content
        assertTrue(instructions.contains("http://agern.example") && instructions.contains("test-org/demo-app") && instructions.contains("alice"), instructions)

        val tools = result(secret, rpcBody("tools/list"))["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(McpRegistry.PERSONAL_TOOLS, tools.map { it["name"]!!.jsonPrimitive.content }.toSet())
        assertFalse("factory_generate_image" in McpRegistry.PERSONAL_TOOLS)
        assertTrue(tools.all { it["description"]!!.jsonPrimitive.content.startsWith("[test-org/demo-app on agern.example] ") })
    }

    @Test
    fun `per-process tokens are unchanged`() {
        val alice = world.user("alice")
        val chat = s.sessions.create("demo-app", "chat", "opus", "idle", alice.id, null)
        val token = s.mcpRegistry.issue(McpGrant(chat.id, alice.id, "demo-app", null, McpRegistry.CHAT_TOOLS))
        val init = result(token, rpcBody("initialize"))
        assertEquals("agern", init["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertNull(init["serverInfo"]!!.jsonObject["title"])
        assertEquals("Agern software factory tools for the repository this agent works on.", init["instructions"]!!.jsonPrimitive.content)
        val tools = result(token, rpcBody("tools/list"))["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(McpRegistry.CHAT_TOOLS, tools.map { it["name"]!!.jsonPrimitive.content }.toSet())
        assertEquals(
            "List issues of this project's GitHub repository (pull requests excluded).",
            tools.single { it["name"]!!.jsonPrimitive.content == "factory_list_issues" }["description"]!!.jsonPrimitive.content,
        )
    }

    // ------------------------------------------------------------ scope and permissions

    @Test
    fun `a token acts only in its project`() {
        val alice = world.user("alice")
        val (_, secret) = token("alice")
        val (err, issue) = call(secret, "factory_get_issue", """{"number":1}""")
        assertFalse(err, issue)
        assertTrue(issue.contains("Add a dark mode toggle"), issue)
        // Issue #1 of private-lab ("Experiment with caching") is not reachable through a demo-app token.
        val (_, issues) = call(secret, "factory_list_issues")
        assertFalse(issues.contains("Experiment with caching"))

        val other = s.runs.createIfNoActive("private-lab", 1, "Experiment", "task", 2, alice.id, null)!!
        s.runs.setStatus(other.id, RunStatus.NEEDS_HUMAN)
        for (tool in listOf("factory_get_run", "factory_approve_run", "factory_cancel_run")) {
            val (isErr, text) = call(secret, tool, """{"runId":"${other.id}"}""")
            assertTrue(isErr, "$tool: $text")
            assertEquals("Run belongs to another project", text, tool)
        }
        assertEquals(RunStatus.NEEDS_HUMAN, s.runs.get(other.id)!!.status)

        // Tools outside PERSONAL_TOOLS are refused.
        for (tool in listOf("factory_sandbox_exec", "factory_sandbox_info", "factory_attach_screenshot", "factory_generate_image")) {
            val (isErr, text) = call(secret, tool, """{"command":"ls","prompt":"x","purpose":"y","path":"a.png","caption":"c"}""")
            assertTrue(isErr)
            assertEquals("Tool $tool is not available to this agent", text)
        }
    }

    @Test
    fun `permission rules are those of the chat agent`() {
        val alice = world.user("alice")
        world.user("bob")
        world.user("carol", claude = false)
        val (_, aliceToken) = token("alice")
        val (_, bobToken) = token("bob")
        val (_, carolToken) = token("carol")

        val run = s.runs.createIfNoActive("demo-app", 2, "Crash", "bug", 2, alice.id, null)!!
        s.runs.setStatus(run.id, RunStatus.NEEDS_HUMAN)
        val (e1, t1) = call(bobToken, "factory_approve_run", """{"runId":"${run.id}"}""")
        assertTrue(e1 && t1.contains("Only the owner"), t1)
        val (e2, t2) = call(aliceToken, "factory_approve_run", """{"runId":"${run.id}"}""")
        assertTrue(e2 && !t2.contains("Only the owner") && t2.contains("no pull request"), t2) // ownership passed
        val (e3, t3) = call(bobToken, "factory_send_to_agent", """{"runId":"${run.id}","role":"builder","message":"hijack"}""")
        assertTrue(e3 && t3.contains("Only the owner"), t3)
        val (e4, t4) = call(bobToken, "factory_cancel_run", """{"runId":"${run.id}"}""")
        assertTrue(e4 && t4.contains("Only the owner"), t4)

        val release = runBlocking { s.releases.create(alice, "demo-app", "r1", "") }
        assertTrue(s.releaseStore.claim(release.id, alice.id, "release/r1"))
        val (e5, t5) = call(bobToken, "factory_approve_release", """{"release":"r1"}""")
        assertTrue(e5 && t5.contains("Only alice"), t5)

        val (e6, t6) = call(carolToken, "factory_start_run", """{"issueNumber":3}""")
        assertTrue(e6)
        assertEquals("Register a Claude credential in Settings first", t6)

        // A personal token has no session: a software request has none either, and the owner's cancel works.
        val (e7, _) = call(aliceToken, "factory_request_software", """{"name":"ffmpeg","reason":"video"}""")
        assertFalse(e7)
        assertNull(s.softwareRequests.all().single().sessionId)
        val (e8, t8) = call(aliceToken, "factory_cancel_run", """{"runId":"${run.id}"}""")
        assertFalse(e8, t8)
        assertEquals(RunStatus.CANCELLED, s.runs.get(run.id)!!.status)
    }

    // ------------------------------------------------------------ throttle

    @Test
    fun `unknown bearers are throttled per address, other bearers do not count`() {
        val alice = world.user("alice")
        val (_, valid) = token("alice")
        val (revokedId, revoked) = token("alice", name = "old")
        s.mcpTokens.revoke(revokedId, clock.instant())
        val (_, expired) = token("alice", name = "short", expiresAt = clock.instant().minusSeconds(1))
        val chat = s.sessions.create("demo-app", "chat", "opus", "idle", alice.id, null)
        val registry = s.mcpRegistry.issue(McpGrant(chat.id, alice.id, "demo-app", null, McpRegistry.CHAT_TOOLS))
        val addr = "198.51.100.7"

        // Revoked, expired and missing bearers do not count.
        repeat(30) {
            assertEquals(401, handle(revoked, rpcBody("ping"), addr).first)
            assertEquals(401, handle(expired, rpcBody("ping"), addr).first)
            assertEquals(401, handle(null, rpcBody("ping"), addr).first)
        }
        repeat(19) { assertEquals(401, handle("agern_pat_guess$it", rpcBody("ping"), addr).first) }
        assertEquals(200, handle(valid, rpcBody("ping"), addr).first)
        assertTrue(s.db.query("SELECT 1 FROM auth_audit WHERE event = 'mcp_token_locked'") { 1 }.isEmpty())
        assertEquals(401, handle("not-even-prefixed", rpcBody("ping"), addr).first) // the 20th

        val (status, text) = handle(valid, rpcBody("ping"), addr)
        assertEquals(429, status)
        assertTrue(errorMessage(text).startsWith("Too many failed attempts. Try again after "), text)
        assertEquals(429, handle("agern_pat_guess-more", rpcBody("ping"), addr).first)
        // Agents' per-process tokens are never throttled; other addresses are not affected.
        assertEquals(200, handle(registry, rpcBody("ping"), addr).first)
        assertEquals(200, handle(valid, rpcBody("ping"), "198.51.100.8").first)
        assertEquals(listOf(addr), s.db.query("SELECT remote FROM auth_audit WHERE event = 'mcp_token_locked'") { it.getString(1) })

        clock.advance(Duration.ofMinutes(15))
        assertEquals(200, handle(valid, rpcBody("ping"), addr).first)
    }

    @Test
    fun `a blocked address gets 429 with Retry-After over HTTP`() = testApplication {
        application { agernModule(s) }
        world.user("alice")
        val (_, valid) = token("alice")
        val c = createClient { }
        suspend fun post(bearer: String) = c.post("/mcp") {
            header("Authorization", "Bearer $bearer"); contentType(ContentType.Application.Json); setBody(rpcBody("ping"))
        }
        repeat(20) { assertEquals(401, post("agern_pat_nope$it").status.value) }
        val r = post(valid)
        assertEquals(429, r.status.value)
        assertEquals((15 * 60).toString(), r.headers["Retry-After"])
        assertTrue(errorMessage(r.bodyAsText()).startsWith("Too many failed attempts"))
    }

    // ------------------------------------------------------------ transport

    @Test
    fun `a personal token's slow answer is streamed like an agent's`() {
        val streaming = TestWorld(twoFactor = false, clock = clock, tweak = { it.copy(mcpStreamAfterMs = 0, mcpProgressEveryMs = 50) })
        try {
            streaming.user("alice")
            val u = streaming.services.users.byUsername("alice")!!
            val secret = dk.egernet.factory.mcp.McpAuthenticator.newToken()
            streaming.services.mcpTokens.create(u.id, "demo-app", "laptop", Tokens.sha256Hex(secret), clock.instant(), null)
            testApplication {
                application { agernModule(streaming.services) }
                val r = createClient { }.post("/mcp") {
                    header("Authorization", "Bearer $secret"); header("Accept", "application/json, text/event-stream")
                    contentType(ContentType.Application.Json)
                    setBody(callBody("factory_list_issues", "{}"))
                }
                assertEquals(200, r.status.value)
                assertEquals("text/event-stream", r.headers["Content-Type"]!!.substringBefore(';'))
                val body = r.bodyAsText()
                assertTrue(body.startsWith(": agern"), body)
                val data = body.lines().last { it.startsWith("data: ") }.removePrefix("data: ")
                assertTrue(json(data).toString().contains("Add a dark mode toggle"), data)
                // A refused bearer is answered at once, never streamed.
                val refused = createClient { }.post("/mcp") {
                    header("Authorization", "Bearer agern_pat_nope"); header("Accept", "text/event-stream"); setBody(rpcBody("ping"))
                }
                assertEquals(401, refused.status.value)
                assertEquals("application/json", refused.headers["Content-Type"]!!.substringBefore(';'))
            }
        } finally {
            streaming.close()
        }
    }
}
