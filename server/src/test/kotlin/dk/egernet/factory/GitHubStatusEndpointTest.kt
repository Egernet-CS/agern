package dk.egernet.factory

import com.sun.net.httpserver.HttpServer
import dk.egernet.factory.github.GitHubHealth
import dk.egernet.factory.mcp.McpGrant
import dk.egernet.factory.mcp.McpRegistry
import dk.egernet.factory.pipeline.Transient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHubStatusEndpointTest {
    private val worlds = mutableListOf<TestWorld>()
    private val servers = mutableListOf<HttpServer>()

    @AfterTest fun tearDown() {
        servers.forEach { it.stop(0) }
        worlds.forEach { it.close() }
    }

    private fun world(statusUrl: String? = null) =
        TestWorld(twoFactor = false, tweak = { it.copy(githubStatusUrl = statusUrl) }).also { worlds += it }

    /** A local stand-in for githubstatus.com; [hits] counts its requests. */
    private fun statusServer(status: Int, body: String, hits: AtomicInteger = AtomicInteger()): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                hits.incrementAndGet()
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        servers += s
        return "http://127.0.0.1:${s.address.port}/api/v2/summary.json"
    }

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String, github: Boolean = true): HttpClient {
        val c = createClient { install(HttpCookies) }
        assertEquals(200, c.post("/api/auth/login") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
        }.status.value)
        if (github) {
            assertEquals(200, c.put("/api/me/github-token") {
                header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"token":"fake-$user"}""")
            }.status.value)
        }
        return c
    }

    private fun json(s: String) = AppJson.parseToJsonElement(s).jsonObject
    private suspend fun HttpClient.status(brief: Boolean = false) =
        get("/api/github/status" + if (brief) "?brief=1" else "").let { assertEquals(200, it.status.value, it.bodyAsText()); json(it.bodyAsText()) }

    @Test
    fun `401 without login, 200 for a user without a GitHub token, disabled official status by default`() {
        val w = world()
        testApplication {
            application { agernModule(w.services) }
            assertEquals(401, createClient { }.get("/api/github/status").status.value)
            val bob = login("bob", "pw-b", github = false)
            val st = bob.status()
            assertEquals("working", st["verdict"]!!.jsonObject["state"]!!.jsonPrimitive.content)
            assertEquals("Agern has made no GitHub calls in the last 15 minutes.", st["verdict"]!!.jsonObject["reason"]!!.jsonPrimitive.content)
            val official = st["official"]!!.jsonObject
            assertEquals("disabled", official["state"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, official["indicator"])
            val kinds = st["kinds"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content }
            assertEquals(GitHubHealth.KINDS.map { it.first }, kinds)
            val rl = st["rateLimits"]!!.jsonObject
            assertEquals(JsonNull, rl["own"])
            assertEquals(0, rl["otherTokens"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun `recorded failures show up as counts and verdict`() {
        val w = world()
        repeat(3) { w.services.githubHealth.recordHttp("/repos/test-org/demo-app/issues", 502, { null }, "Bad Gateway", null) }
        w.services.githubHealth.recordGit("push", 128, "fatal: unable to access 'https://github.com/test-org/demo-app.git/': Could not resolve host: github.com")
        testApplication {
            application { agernModule(w.services) }
            val alice = login("alice", "pw-a")
            val st = alice.status()
            val v = st["verdict"]!!.jsonObject
            assertEquals("down", v["state"]!!.jsonPrimitive.content)
            assertEquals(
                "All 4 GitHub calls by Agern failed in the last 5 minutes (3 server errors, 1 timeout or network error).",
                v["reason"]!!.jsonPrimitive.content,
            )
            val issues = st["kinds"]!!.jsonArray.map { it.jsonObject }.first { it["kind"]!!.jsonPrimitive.content == "issues" }
            assertEquals("Issues and labels", issues["label"]!!.jsonPrimitive.content)
            assertEquals(3, issues["last15m"]!!.jsonObject["serverErrors"]!!.jsonPrimitive.int)
            assertEquals(3, issues["last24h"]!!.jsonObject["calls"]!!.jsonPrimitive.int)
            assertEquals("server_error", issues["lastError"]!!.jsonObject["class"]!!.jsonPrimitive.content)
            val git = st["kinds"]!!.jsonArray.map { it.jsonObject }.first { it["kind"]!!.jsonPrimitive.content == "git" }
            val msg = git["lastError"]!!.jsonObject["message"]!!.jsonPrimitive.content
            assertEquals("fatal: unable to access 'https://github.com/…': Could not resolve host: github.com", msg)
            assertFalse("demo-app" in st.toString())

            // brief: same verdict, no table, no rate limits.
            val brief = alice.status(brief = true)
            assertEquals("down", brief["verdict"]!!.jsonObject["state"]!!.jsonPrimitive.content)
            assertEquals(0, brief["kinds"]!!.jsonArray.size)
            assertEquals(JsonNull, brief["rateLimits"])
        }
    }

    @Test
    fun `own rate limits per user, other tokens anonymous`() {
        val w = world()
        val now = System.currentTimeMillis() / 1000
        fun observe(token: String, remaining: Int) = w.services.githubHealth.recordHttp(
            "/user", 200,
            { mapOf("x-ratelimit-limit" to "5000", "x-ratelimit-remaining" to "$remaining", "x-ratelimit-reset" to "${now + 1800}")[it] },
            null, GitHubHealth.fingerprint(token),
        )
        observe("fake-alice", 100)
        observe("fake-bob", 200)
        testApplication {
            application { agernModule(w.services) }
            val alice = login("alice", "pw-a")
            val bob = login("bob", "pw-b")
            // From the fake's /rate_limit.
            val own = alice.status()["rateLimits"]!!.jsonObject["own"]!!.jsonObject
            assertEquals(4987, own["rest"]!!.jsonObject["remaining"]!!.jsonPrimitive.int)
            assertEquals(5000, own["graphql"]!!.jsonObject["remaining"]!!.jsonPrimitive.int)

            // GitHub unreachable: /rate_limit fails, each user falls back to their own header observation (after the cache).
            w.github.unreachable = true
            val bobSt = bob.status()["rateLimits"]!!.jsonObject
            assertEquals(200, bobSt["own"]!!.jsonObject["rest"]!!.jsonObject["remaining"]!!.jsonPrimitive.int)
            assertEquals(JsonNull, bobSt["own"]!!.jsonObject["graphql"])
            assertEquals(1, bobSt["otherTokens"]!!.jsonPrimitive.int)
            assertEquals(100, bobSt["lowestOther"]!!.jsonObject["remaining"]!!.jsonPrimitive.int)
            val aliceSt = alice.status()["rateLimits"]!!.jsonObject
            assertEquals(4987, aliceSt["own"]!!.jsonObject["rest"]!!.jsonObject["remaining"]!!.jsonPrimitive.int, "cached for 60 s")
            assertEquals(200, aliceSt["lowestOther"]!!.jsonObject["remaining"]!!.jsonPrimitive.int)
            val body = bob.get("/api/github/status").bodyAsText()
            for (secret in listOf("alice", GitHubHealth.fingerprint("fake-alice"), GitHubHealth.fingerprint("fake-bob"), "fake-")) {
                assertFalse(secret in body, secret)
            }
        }
    }

    private val summary = """
        {"page":{"id":"kctbh9vrtdwd","name":"GitHub"},"status":{"indicator":"minor","description":"Partially Degraded Service"},
         "components":[{"name":"API Requests","status":"partial_outage","id":"x"},{"name":"Pages","status":"operational"},{"name":"Copilot","status":"degraded_performance"}],
         "incidents":[{"name":"Disruption with some GitHub services","status":"investigating","impact":"minor","shortlink":"https://stspg.io/xyz","updated_at":"2026-11-25T15:55:00.000Z"},
                      {"name":"Plain link","status":"monitoring","impact":"none","shortlink":"http://stspg.io/plain","updated_at":null}]}
    """.trimIndent()

    @Test
    fun `official status is parsed, cached for 60 seconds and raises the verdict`() {
        val hits = AtomicInteger()
        val w = world(statusServer(200, summary, hits))
        w.services.githubHealth.recordHttp("/repos/test-org/demo-app/pulls", 200, { null }, null, null)
        testApplication {
            application { agernModule(w.services) }
            val alice = login("alice", "pw-a")
            val st = alice.status()
            val o = st["official"]!!.jsonObject
            assertEquals("ok", o["state"]!!.jsonPrimitive.content)
            assertEquals("minor", o["indicator"]!!.jsonPrimitive.content)
            assertEquals("Partially Degraded Service", o["description"]!!.jsonPrimitive.content)
            val comps = o["components"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("API Requests", "Copilot"), comps.map { it["name"]!!.jsonPrimitive.content })
            assertEquals(listOf(true, false), comps.map { it["relevant"]!!.jsonPrimitive.content.toBoolean() })
            val inc = o["incidents"]!!.jsonArray.map { it.jsonObject }
            assertEquals("https://stspg.io/xyz", inc[0]["url"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, inc[1]["url"])
            assertEquals(JsonNull, inc[1]["updatedAt"])
            assertEquals(
                JsonObject(mapOf("state" to kotlinx.serialization.json.JsonPrimitive("degraded"),
                    "reason" to kotlinx.serialization.json.JsonPrimitive("GitHub reports problems with API Requests."))),
                st["verdict"],
            )
            alice.status(brief = true)
            assertEquals(1, hits.get(), "a second request within 60 s does not fetch again")
        }
    }

    @Test
    fun `an unreachable status page still answers 200`() {
        val down = world(statusServer(503, "<html>oops</html>"))
        val port = ServerSocket(0).use { it.localPort }
        val closed = world("http://127.0.0.1:$port/summary.json")
        val garbage = world(statusServer(200, "not json"))
        val huge = world(statusServer(200, "{\"status\":{},\"x\":\"" + "a".repeat(1100 * 1024) + "\"}"))
        for ((w, error) in listOf(down to "HTTP 503", closed to "ConnectException", garbage to null, huge to null)) {
            testApplication {
                application { agernModule(w.services) }
                val o = login("alice", "pw-a").status()["official"]!!.jsonObject
                assertEquals("unreachable", o["state"]!!.jsonPrimitive.content)
                val text = o["error"]!!.jsonPrimitive.content
                assertTrue(text.isNotBlank() && text.length <= 200)
                if (error != null) assertTrue(text.startsWith(error), text)
                assertFalse("127.0.0.1" in text)
                assertTrue(o["fetchedAt"] !is JsonNull)
            }
        }
    }

    @Test
    fun `a GitHub 5xx reaches the REST API and MCP tools with the new message`() {
        val w = world()
        testApplication {
            application { agernModule(w.services) }
            val alice = login("alice", "pw-a")
            // Primes the visibility cache, as a user who opened the project has.
            assertEquals(200, alice.get("/api/projects/demo-app").status.value)
            w.github.serverError = 502
            val r = alice.post("/api/projects/demo-app/issues") {
                header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"title":"x","body":"y","kind":"bug"}""")
            }
            assertEquals(502, r.status.value)
            val err = json(r.bodyAsText())["error"]!!.jsonObject
            assertEquals("github_error", err["code"]!!.jsonPrimitive.content)
            val msg = err["message"]!!.jsonPrimitive.content
            assertTrue(msg.startsWith("GitHub failed: request returned HTTP 502 (request id FAKE:0000:0000)"), msg)
            assertTrue("/github-status" in msg, msg)

            val text = mcpCreateIssue(w)
            assertTrue("\"isError\":true" in text, text)
            assertTrue(msg in text, text)
        }
    }

    @Test
    fun `a network error reaches the REST API and MCP tools as github_unreachable and stays transient`() {
        val w = world()
        testApplication {
            application { agernModule(w.services) }
            val alice = login("alice", "pw-a")
            assertEquals(200, alice.get("/api/projects/demo-app").status.value)
            w.github.unreachable = true
            val r = alice.post("/api/projects/demo-app/issues") {
                header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"title":"x","body":"y","kind":"bug"}""")
            }
            assertEquals(502, r.status.value)
            val err = json(r.bodyAsText())["error"]!!.jsonObject
            assertEquals("github_unreachable", err["code"]!!.jsonPrimitive.content)
            val expected = "GitHub could not be reached (ConnectException). This is a network or GitHub problem, not a problem with your token. " +
                "See the GitHub status page in Agern (/github-status)."
            assertEquals(expected, err["message"]!!.jsonPrimitive.content)

            val text = mcpCreateIssue(w)
            assertTrue("\"isError\":true" in text, text)
            assertTrue(expected in text, text)

            val e = assertFailsWith<ApiException> { runBlocking { w.services.factory.createIssue(w.user("alice"), "demo-app", "t", "b", "bug") } }
            assertEquals("github_unreachable", e.code)
            assertTrue(Transient.isTransient(e))
        }
    }

    private suspend fun ApplicationTestBuilder.mcpCreateIssue(w: TestWorld): String {
        val alice = w.user("alice")
        val session = w.services.sessions.create("demo-app", "chat", "opus", "idle", alice.id, null)
        val token = w.services.mcpRegistry.issue(McpGrant(session.id, alice.id, "demo-app", null, McpRegistry.CHAT_TOOLS))
        return createClient { }.post("/mcp") {
            header("Authorization", "Bearer $token"); contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"factory_create_issue","arguments":{"title":"Agent feature","body":"z","kind":"feature"}}}""")
        }.bodyAsText()
    }
}
