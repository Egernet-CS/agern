package dk.egernet.factory

import dk.egernet.factory.agent.StageAgentResult
import dk.egernet.factory.agent.UsageLimit
import dk.egernet.factory.db.SessionRecord
import dk.egernet.factory.git.GitException
import dk.egernet.factory.github.GitHubException
import dk.egernet.factory.pipeline.StageFailed
import dk.egernet.factory.pipeline.Transient
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which failures count as transient (docs/ARCHITECTURE.md §6 "Transient failures"), and how they are named. */
class TransientTest {
    private fun transient(e: Throwable) = Transient.isTransient(e)

    @Test
    fun `network exceptions are transient, file system errors are not`() {
        val yes = listOf(
            java.net.ConnectException(), java.net.UnknownHostException("api.github.com"), java.net.NoRouteToHostException(),
            java.net.SocketTimeoutException("Read timed out"), java.net.http.HttpTimeoutException("request timed out"),
            java.net.SocketException("Connection reset"), javax.net.ssl.SSLException("handshake"), java.io.IOException("closed"),
            java.io.UncheckedIOException(java.net.ConnectException()), java.nio.channels.UnresolvedAddressException(),
        )
        yes.forEach { assertTrue(transient(it), it.toString()) }
        val no = listOf(
            java.nio.file.NoSuchFileException("/x"), java.nio.file.AccessDeniedException("/x"), java.io.FileNotFoundException("/x"),
            java.io.UncheckedIOException(java.nio.file.NoSuchFileException("/x")), IllegalStateException("bug"), RuntimeException(),
            kotlinx.coroutines.CancellationException("cancelled"),
        )
        no.forEach { assertFalse(transient(it), it.toString()) }
    }

    @Test
    fun `causes are followed, but not too deep`() {
        assertTrue(transient(RuntimeException("wrapper", IllegalStateException("inner", java.net.ConnectException()))))
        assertTrue(transient(ApiException(HttpStatusCode.BadGateway, "github_error", "GitHub error").apply { initCause(GitHubException(502, "Bad Gateway")) }))
        assertFalse(transient(ApiException(HttpStatusCode.BadGateway, "github_error", "GitHub error")))
        var deep: Throwable = java.net.ConnectException()
        repeat(12) { deep = RuntimeException("level $it", deep) }
        assertFalse(transient(deep), "more than 10 levels")
    }

    @Test
    fun `GitHub 5xx is transient, 4xx is not`() {
        assertTrue(transient(GitHubException(502, "Bad Gateway")))
        assertTrue(transient(GitHubException(500, "x")))
        assertTrue(transient(GitHubException(503, "x")))
        assertFalse(transient(GitHubException(403, "Resource not accessible")))
        assertFalse(transient(GitHubException(422, "Validation Failed")))
        assertFalse(transient(GitHubException(404, "Not Found")))
    }

    @Test
    fun `git messages about the network are transient, refusals are not`() {
        val yes = listOf(
            "git push failed: fatal: unable to access 'https://github.invalid/x.git/': Could not resolve host: github.invalid",
            "git fetch failed: fatal: unable to access 'https://github.com/o/r.git/': Failed to connect to github.com port 443",
            "ssh: connect to host github.com port 22: Connection timed out",
            "ssh: connect to host github.com port 22: Connection refused",
            "fatal: read error: Connection reset by peer", "connect: Network is unreachable", "No route to host",
            "Temporary failure in name resolution", "Operation timed out after 300000 milliseconds",
            "fatal: unable to access 'x': The requested URL returned error: 503",
            "fatal: the remote end hung up unexpectedly", "fatal: early EOF", "error: RPC failed; curl 56",
            "gnutls_handshake() failed", "OpenSSL SSL_connect: SSL_ERROR_SYSCALL", "TLS connection was non-properly terminated",
            "git fetch timed out", "git clone timed out",
        )
        yes.forEach { assertTrue(transient(GitException(it)), it) }
        val no = listOf(
            "git push failed: remote: Permission to x denied to alice.\nfatal: unable to access 'x': The requested URL returned error: 403",
            "remote: Permission to o/r.git denied to alice.\nfatal: the remote end hung up unexpectedly",
            "! [remote rejected] main -> main (protected branch hook declined)",
            "! [rejected] x -> x (non-fast-forward)", "fatal: The requested URL returned error: 404",
            "fatal: not a git repository", "error: pathspec 'x' did not match",
        )
        no.forEach { assertFalse(transient(GitException(it)), it) }
    }

    @Test
    fun `StageFailed is transient only through its cause, never for final kinds or after giving up`() {
        assertFalse(transient(StageFailed("no commits", kind = FailureKind.BLOCKED, cause = java.net.ConnectException())))
        assertFalse(transient(StageFailed("creds", kind = FailureKind.CREDENTIALS, cause = java.net.ConnectException())))
        assertFalse(transient(StageFailed("usage", kind = FailureKind.USAGE_LIMIT, cause = java.net.ConnectException())))
        assertFalse(transient(StageFailed("time", kind = FailureKind.TIME_LIMIT, cause = java.net.ConnectException())))
        assertTrue(transient(StageFailed("push", kind = FailureKind.STEP, step = FailedStep.PUSH, cause = GitException("Could not resolve host: x"))))
        assertFalse(transient(StageFailed("push", kind = FailureKind.STEP, step = FailedStep.PUSH, cause = GitException("Permission to x denied to alice."))))
        assertFalse(transient(StageFailed("push", kind = FailureKind.STEP, step = FailedStep.PUSH)))
        assertTrue(transient(StageFailed("other", cause = java.net.UnknownHostException())))
        assertFalse(transient(StageFailed("other")))
        assertFalse(transient(StageFailed("gave up", kind = FailureKind.API_UNREACHABLE, cause = java.net.ConnectException(), gaveUp = true)))
    }

    private fun result(
        error: String?, ok: Boolean = false, timedOut: Boolean = false, usage: UsageLimit? = null, creds: Boolean = false,
    ): StageAgentResult {
        val now = Instant.now()
        val s = SessionRecord(
            UUID.randomUUID(), "demo-app", "builder", "sonnet", "failed", UUID.randomUUID(), "alice", "Alice", null, null, null, null,
            null, 0.0, 0, null, now, now, null,
        )
        return StageAgentResult(s, null, null, ok, error, usage, creds, timedOut)
    }

    @Test
    fun `stage agent results`() {
        val unreachable = "Agent finished with error_during_execution: API Error: Can't reach the API server - check your internet or DNS"
        assertTrue(Transient.isTransient(result(unreachable)))
        assertTrue(Transient.isTransient(result("Agent exited with code 1 without a result. getaddrinfo ENOTFOUND api.anthropic.com")))
        assertFalse(Transient.isTransient(result(unreachable, timedOut = true)))
        assertFalse(Transient.isTransient(result(unreachable, usage = UsageLimit(null, "five_hour"))))
        assertFalse(Transient.isTransient(result(unreachable, creds = true)))
        assertFalse(Transient.isTransient(result(null, ok = true)))
        assertFalse(Transient.isTransient(result("Agent exited with code 1 without a result. fake stderr")))
        assertFalse(Transient.isTransient(result(null)))
    }

    @Test
    fun `describe names the class and never says null`() {
        assertEquals("ConnectException", Transient.describe(java.net.ConnectException()))
        assertEquals("ConnectException", Transient.describe(java.net.ConnectException("  ")))
        assertEquals("UnknownHostException: api.github.com", Transient.describe(java.net.UnknownHostException("api.github.com")))
        assertEquals(
            "IllegalStateException: setting up (caused by ConnectException)",
            Transient.describe(IllegalStateException("setting up", java.net.ConnectException())),
        )
        assertEquals(
            "RuntimeException (caused by GitHubException: Bad Gateway)",
            Transient.describe(RuntimeException(null as String?, RuntimeException(null as String?, GitHubException(502, "Bad Gateway")))),
        )
        assertFalse(Transient.describe(RuntimeException(null as String?)).contains("null"))
    }
}
