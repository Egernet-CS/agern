package dk.egernet.factory

import com.sun.net.httpserver.HttpServer
import dk.egernet.factory.github.CallClass
import dk.egernet.factory.github.GitHubException
import dk.egernet.factory.github.GitHubHealth
import dk.egernet.factory.github.HttpGitHub
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** [HttpGitHub] against a local HTTP server: every exchange is recorded once, and 5xx answers carry the new message. */
class HttpGitHubRecordingTest {
    private val health = GitHubHealth("acme")
    private val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val reset = (System.currentTimeMillis() / 1000 + 3600).toString()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            requests += "${ex.requestMethod} ${ex.requestURI}"
            val path = ex.requestURI.path
            ex.responseHeaders.add("x-ratelimit-limit", "5000")
            ex.responseHeaders.add("x-ratelimit-remaining", "4321")
            ex.responseHeaders.add("x-ratelimit-reset", reset)
            val (status, out) = when {
                ex.requestMethod == "POST" && path.endsWith("/issues") -> {
                    ex.responseHeaders.add("x-github-request-id", "8F3A:1B2C:3D4E:5F60")
                    500 to """{"message":"Server Error"}"""
                }
                path.endsWith("/issues/7") -> 404 to """{"message":"Not Found"}"""
                path.endsWith("/comments") -> 422 to """{"message":"Validation Failed"}"""
                path == "/rate_limit" -> 200 to
                    """{"resources":{"core":{"limit":5000,"remaining":4321,"reset":$reset,"used":679},"graphql":{"limit":5000,"remaining":4999,"reset":$reset,"used":1}}}"""
                path.contains("/contents/") -> {
                    ex.responseHeaders.add("x-github-request-id", "ABCD:1")
                    503 to """{"message":"Unavailable"}"""
                }
                else -> 200 to "{}"
            }
            val bytes = out.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val api = "http://127.0.0.1:${server.address.port}"
    private val token = "ghp_SECRETtoken0123456789"
    private val client = HttpGitHub(api, "https://github.com", health).client(token)

    @AfterTest fun tearDown() = server.stop(0)

    private fun kind(k: String) = health.snapshot().first { it.kind == k }

    @Test
    fun `a 5xx on create issue carries the request id, the status page, and is recorded once`() {
        val e = assertFailsWith<GitHubException> { client.createIssue("acme", "app", "t", "b", emptyList()) }
        assertEquals(500, e.status)
        assertEquals("8F3A:1B2C:3D4E:5F60", e.requestId)
        assertEquals(
            "GitHub failed: create issue returned HTTP 500 (request id 8F3A:1B2C:3D4E:5F60): Server Error. This is a problem at GitHub, " +
                "not with Agern or your token. See the GitHub status page in Agern (/github-status).",
            e.message,
        )
        val issues = kind("issues")
        assertEquals(1, issues.last15m.calls)
        assertEquals(1, issues.last15m.serverErrors)
        assertEquals(CallClass.SERVER_ERROR, issues.lastError!!.cls)
        assertEquals("8F3A:1B2C:3D4E:5F60", issues.lastError!!.requestId)
        assertEquals("HTTP 500: Server Error", issues.lastError!!.message)
        // The headers' rate limit is kept per token fingerprint.
        assertEquals(4321, health.rateLimit(GitHubHealth.fingerprint(token), "core")!!.remaining)
    }

    @Test
    fun `404 counts as a successful call, 422 keeps its message`() {
        assertNull(client.getIssue("acme", "app", 7))
        assertEquals(dk.egernet.factory.GitHubCallCountsDto(1, 0, 0, 0, 0), kind("issues").last15m.toDto())
        val e = assertFailsWith<GitHubException> { client.comment("acme", "app", 3, "x") }
        assertEquals("GitHub comment failed: 422 Validation Failed", e.message)
        assertNull(kind("issues").lastError)
    }

    @Test
    fun `getFileBytes is recorded through the same function`() {
        val e = assertFailsWith<GitHubException> { client.getFileBytes("acme", "app", "a/b.png", "main", 1000) }
        assertEquals(503, e.status)
        assertEquals("ABCD:1", e.requestId)
        assertEquals(1, kind("contents").last15m.serverErrors)
        assertEquals(1, requests.size)
    }

    @Test
    fun `a closed port records a network failure and rethrows the IOException`() {
        val port = ServerSocket(0).use { it.localPort }
        val c = HttpGitHub("http://127.0.0.1:$port", "https://github.com", health).client(token)
        assertFailsWith<java.io.IOException> { c.getRepo("acme", "app") }
        val repos = kind("repos")
        assertEquals(1, repos.last15m.network)
        assertEquals(CallClass.NETWORK, repos.lastError!!.cls)
    }

    @Test
    fun `rate limits are parsed and the token never reaches the snapshot`() {
        val limits = client.rateLimit()!!
        assertEquals(4321, limits.rest!!.remaining)
        assertEquals(4999, limits.graphql!!.remaining)
        assertEquals(1, kind("other").last15m.calls)
        runCatching { client.createIssue("acme", "app", "t", "b", emptyList()) }
        val dump = health.snapshot().toString() + health.otherTokens(null).toString()
        assertFalse("SECRET" in dump)
        assertFalse(token in dump)
    }
}
