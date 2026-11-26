package dk.egernet.factory

import dk.egernet.factory.github.CallClass
import dk.egernet.factory.github.CallCounts
import dk.egernet.factory.github.GitHubFailure
import dk.egernet.factory.github.GitHubHealth
import dk.egernet.factory.github.OfficialGitHubStatus
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubHealthTest {
    private val clock = MutableClock(Instant.parse("2026-11-25T15:50:30Z"))
    private val health = GitHubHealth("acme", clock)
    private val noHeaders: (String) -> String? = { null }
    private fun headers(vararg h: Pair<String, String>): (String) -> String? = { name -> h.toMap()[name] }
    private fun kind(k: String) = health.snapshot().first { it.kind == k }

    @Test
    fun `kinds from paths`() {
        val cases = mapOf(
            "/repos/acme/app/issues?state=open&per_page=100" to "issues",
            "/repos/acme/app/issues/3/comments" to "issues",
            "/repos/acme/app/labels" to "issues",
            "/repos/acme/app/pulls?state=open" to "pulls",
            "/repos/acme/app/pulls/4/merge" to "pulls",
            "/repos/acme/app/releases/12" to "releases",
            "/repos/acme/app/actions/runs/1/rerun" to "actions",
            "/repos/acme/app/commits/abc/status?per_page=100" to "checks",
            "/repos/acme/app/commits/abc/check-runs?filter=latest" to "checks",
            "/repos/acme/app/check-suites/9" to "checks",
            "/repos/acme/app/statuses/abc" to "checks",
            "/repos/acme/app/contents/a/b.txt?ref=main" to "contents",
            "/repos/acme/app/git/refs" to "git_data",
            "/repos/acme/app/git/ref/heads/main" to "git_data",
            "/repos/acme/app/commits/abc" to "repos",
            "/repos/acme/app" to "repos",
            "/orgs/acme/repos?per_page=100" to "repos",
            "/graphql" to "graphql",
            "/user" to "other",
            "/rate_limit" to "other",
            "https://api.github.com/repositories/1/issues?page=2" to "other",
            "https://api.github.com/repos/acme/app/issues?page=2" to "issues",
            "https://ghe.example.com/api/v3/repos/acme/app/labels?page=3" to "issues",
        )
        for ((path, kind) in cases) assertEquals(kind, GitHubHealth.kindOf(path), path)
    }

    @Test
    fun `HTTP classes`() {
        assertEquals(CallClass.OK, GitHubHealth.classifyHttp(200, noHeaders, null))
        assertEquals(CallClass.OK, GitHubHealth.classifyHttp(404, noHeaders, "Not Found"))
        assertEquals(CallClass.OK, GitHubHealth.classifyHttp(422, noHeaders, "Validation Failed"))
        assertEquals(CallClass.AUTH, GitHubHealth.classifyHttp(401, noHeaders, "Bad credentials"))
        assertEquals(CallClass.AUTH, GitHubHealth.classifyHttp(403, noHeaders, "Resource not accessible by personal access token"))
        assertEquals(CallClass.RATE_LIMITED, GitHubHealth.classifyHttp(403, headers("x-ratelimit-remaining" to "0"), null))
        assertEquals(CallClass.RATE_LIMITED, GitHubHealth.classifyHttp(403, headers("retry-after" to "60"), null))
        assertEquals(CallClass.RATE_LIMITED, GitHubHealth.classifyHttp(403, noHeaders, "You have exceeded a secondary Rate Limit."))
        assertEquals(CallClass.RATE_LIMITED, GitHubHealth.classifyHttp(429, noHeaders, null))
        assertEquals(CallClass.SERVER_ERROR, GitHubHealth.classifyHttp(500, noHeaders, null))
        assertEquals(CallClass.SERVER_ERROR, GitHubHealth.classifyHttp(502, noHeaders, null))
    }

    @Test
    fun `git classes`() {
        assertEquals(CallClass.OK, GitHubHealth.classifyGit(0, ""))
        assertEquals(CallClass.NETWORK, GitHubHealth.classifyGit(128, "fatal: unable to access 'https://github.com/a/b.git/': Could not resolve host: github.com"))
        assertEquals(CallClass.SERVER_ERROR, GitHubHealth.classifyGit(128, "fatal: unable to access 'https://github.com/a/b.git/': The requested URL returned error: 503"))
        assertEquals(CallClass.AUTH, GitHubHealth.classifyGit(128, "remote: Invalid username or password.\nfatal: Authentication failed for 'https://github.com/a/b.git/'"))
        assertEquals(CallClass.RATE_LIMITED, GitHubHealth.classifyGit(128, "The requested URL returned error: 429"))
        assertEquals(CallClass.OTHER, GitHubHealth.classifyGit(1, " ! [rejected]        main -> main (non-fast-forward)\nerror: failed to push some refs"))
        assertEquals(CallClass.NETWORK, GitHubHealth.classifyGit(null, ""))
    }

    @Test
    fun `counts land in their windows and vanish after 24 hours`() {
        health.recordHttp("/repos/acme/app/issues", 500, noHeaders, "boom", null)
        health.recordHttp("/repos/acme/app/issues", 200, noHeaders, null, null)
        clock.advance(Duration.ofMinutes(20))
        health.recordHttp("/repos/acme/app/issues", 429, noHeaders, null, null)
        health.recordHttp("/repos/acme/app/issues", 401, noHeaders, null, null)
        health.recordHttpFailure("/repos/acme/app/issues", java.net.ConnectException())
        health.recordHttp("/repos/acme/app/issues/1", 404, noHeaders, "Not Found", null)
        val k = kind("issues")
        assertEquals(dk.egernet.factory.GitHubCallCountsDto(4, 0, 1, 1, 1), k.last15m.toDto())
        assertEquals(dk.egernet.factory.GitHubCallCountsDto(6, 1, 1, 1, 1), k.last24h.toDto())
        assertEquals(0, kind("pulls").last24h.calls)

        // 23 h 50 later the first two have left the 24 hours; reusing their bucket's slot does not count them again.
        clock.advance(Duration.ofHours(23).plusMinutes(50))
        health.recordHttp("/repos/acme/app/issues", 200, noHeaders, null, null)
        assertEquals(dk.egernet.factory.GitHubCallCountsDto(5, 0, 1, 1, 1), kind("issues").last24h.toDto())
        clock.advance(Duration.ofMinutes(20))
        assertEquals(1, kind("issues").last24h.calls)
    }

    @Test
    fun `the last error is kept per kind, scrubbed, and expires after 24 hours`() {
        health.recordHttp(
            "/repos/acme/app/issues", 502, headers("x-github-request-id" to "8F3A:1B2C:3D4E:5F60"),
            "Upstream acme/secret-repo failed at 10.0.0.5:3128 with ghp_abcdefghijklmnopqrstuvwxyz", null,
        )
        health.recordHttp("/repos/acme/app/pulls", 200, noHeaders, null, null)
        val e = kind("issues").lastError!!
        assertEquals(CallClass.SERVER_ERROR, e.cls)
        assertEquals(502, e.status)
        assertEquals("8F3A:1B2C:3D4E:5F60", e.requestId)
        assertEquals("HTTP 502: Upstream acme/[repository] failed at [address] with [redacted]", e.message)
        assertNull(kind("pulls").lastError)
        clock.advance(Duration.ofHours(25))
        assertNull(kind("issues").lastError)
    }

    @Test
    fun `request ids are validated`() {
        health.recordHttp("/repos/acme/app/issues", 500, headers("x-github-request-id" to "has spaces in it"), null, null)
        assertNull(kind("issues").lastError!!.requestId)
        health.recordHttp("/repos/acme/app/issues", 500, headers("x-github-request-id" to "A".repeat(101)), null, null)
        assertNull(kind("issues").lastError!!.requestId)
        assertEquals("A:B.c_d-1", GitHubHealth.validRequestId("A:B.c_d-1"))
    }

    @Test
    fun `git outcomes`() {
        health.recordGit("fetch", 128, "remote: x\nfatal: unable to access 'https://x-access-token:s3cret@github.com/acme/private-repo.git/': Could not resolve host: github.com\n")
        health.recordGit("push", 1, "error: failed to push some refs ([rejected])")
        health.recordGit("clone", null, "")
        val k = kind("git")
        assertEquals(dk.egernet.factory.GitHubCallCountsDto(3, 0, 2, 0, 0), k.last15m.toDto())
        assertEquals("git clone timed out", k.lastError!!.message)
    }

    @Test
    fun `the recorder never throws`() {
        health.recordHttp("/x", 500, { throw IllegalStateException("broken headers") }, null, "fp")
        health.recordGit("fetch", 1, "")
    }

    private fun counts(serverErrors: Int = 0, network: Int = 0, ok: Int = 0, other: Int = 0) =
        CallCounts(serverErrors + network + ok + other, serverErrors, network, other, 0, ok)

    private fun official(vararg components: Pair<String, Boolean>) = GitHubOfficialDto(
        "ok", "minor", "Partial outage", components.map { GitHubComponentDto(it.first, "degraded_performance", it.second) }, emptyList(), null, null,
    )

    @Test
    fun `verdict rules`() {
        val v = GitHubHealth::verdict
        assertEquals(GitHubVerdictDto("working", "Agern has made no GitHub calls in the last 15 minutes."), v(counts(), null, 0))
        assertEquals("working", v(counts(ok = 5), null, 5).state)
        assertEquals("Agern's calls to GitHub are succeeding.", v(counts(ok = 5), null, 5).reason)
        assertEquals(
            GitHubVerdictDto("degraded", "2 of 7 GitHub calls by Agern failed in the last 5 minutes (2 server errors, 0 timeouts or network errors)."),
            v(counts(serverErrors = 2, ok = 5), null, 7),
        )
        assertEquals(
            GitHubVerdictDto("down", "All 3 GitHub calls by Agern failed in the last 5 minutes (1 server error, 2 timeouts or network errors)."),
            v(counts(serverErrors = 1, network = 2), null, 3),
        )
        assertEquals("degraded", v(counts(serverErrors = 3, ok = 1), null, 4).state)
        assertEquals("working", v(counts(serverErrors = 1, ok = 3), null, 4).state)
        // Rate limits and auth failures never change it.
        assertEquals("working", v(CallCounts(10, 0, 0, 5, 5, 0), null, 10).state)
        // The official status: relevant components only.
        assertEquals(GitHubVerdictDto("degraded", "GitHub reports problems with Issues, Actions."), v(counts(ok = 1), official("Issues" to true, "Pages" to false, "Actions" to true), 1))
        assertEquals("working", v(counts(ok = 1), official("Pages" to false), 1).state)
        assertEquals(
            "2 of 2 GitHub calls by Agern failed in the last 5 minutes (2 server errors, 0 timeouts or network errors). GitHub reports problems with Issues.",
            v(counts(serverErrors = 2), official("Issues" to true), 2).reason,
        )
        assertEquals("working", v(counts(ok = 1), official("Issues" to true).copy(state = "unreachable"), 1).state)
    }

    @Test
    fun `verdict from recorded outcomes, and failures older than 5 minutes no longer count`() {
        repeat(3) { health.recordHttp("/repos/acme/app/issues", 502, noHeaders, null, null) }
        assertEquals("down", GitHubHealth.verdict(health.verdictCounts(), null, health.totals(15).calls).state)
        health.recordHttp("/repos/acme/app/pulls", 200, noHeaders, null, null)
        assertEquals("degraded", GitHubHealth.verdict(health.verdictCounts(), null, health.totals(15).calls).state)
        clock.advance(Duration.ofMinutes(6))
        assertEquals("working", GitHubHealth.verdict(health.verdictCounts(), null, health.totals(15).calls).state)
    }

    @Test
    fun scrubbing() {
        val s = { t: String -> GitHubHealth.scrub(t, "acme") }
        assertEquals("token [redacted]", s("token ghp_0123456789abcdefABCDEF"))
        assertEquals("[redacted] leaked", s("github_pat_11ABCDEFG0123456789_abcdefXYZ leaked"))
        assertEquals("Authorization: [redacted] sent", s("Authorization: Bearer abc.def-123 sent"))
        assertEquals("Bearer [redacted] and Basic [redacted]", s("Bearer abcdef and Basic dXNlcjpwYXNz"))
        assertEquals("your token expired", s("your token expired"))
        assertEquals("fatal: unable to access 'https://[redacted]@github.com/…': 503", s("fatal: unable to access 'https://x-access-token:secret@github.com/acme/private-repo.git/': 503"))
        assertEquals("https://github.com/…", s("https://github.com/acme/secret-repo.git/"))
        assertEquals("Not found: acme/[repository] (acme/[repository])", s("Not found: acme/private-repo (ACME/other.repo)"))
        assertEquals("proxy [address] and [address] or [address] at [address]", s("proxy 10.0.0.5:3128 and [fe80::1] or proxy.corp at localhost:8080"))
        assertEquals("cannot open [path]: denied", s("cannot open /var/lib/agern/workspaces/alice/x: denied"))
        assertEquals("'[path]'", s("'/home/alice/.git-credentials'"))
        assertEquals("line one line two", s("line one\n\u0007\tline   two\r\n"))
        val long = s("x".repeat(1000))
        assertEquals(200, long.length)
        assertTrue(long.endsWith("…"))
        assertEquals("HTTP 500", s("HTTP 500"))
    }

    @Test
    fun `rate limits per fingerprint`() {
        val fpA = GitHubHealth.fingerprint("ghp_tokenA")
        val fpB = GitHubHealth.fingerprint("ghp_tokenB")
        assertEquals(16, fpA.length)
        assertNotEquals(fpA, fpB)
        assertFalse("tokenA" in fpA)
        val reset = clock.instant().plusSeconds(1800).epochSecond.toString()
        health.recordHttp("/user", 200, headers("x-ratelimit-limit" to "5000", "x-ratelimit-remaining" to "4000", "x-ratelimit-reset" to reset), null, fpA)
        health.recordHttp("/graphql", 200, headers("x-ratelimit-limit" to "5000", "x-ratelimit-remaining" to "4990", "x-ratelimit-reset" to reset, "x-ratelimit-resource" to "graphql"), null, fpA)
        health.recordHttp("/user", 200, headers("x-ratelimit-limit" to "5000", "x-ratelimit-remaining" to "12", "x-ratelimit-reset" to reset), null, fpB)
        // B's limit resets in the past: not current.
        val fpC = GitHubHealth.fingerprint("ghp_tokenC")
        health.recordHttp("/user", 200, headers("x-ratelimit-limit" to "60", "x-ratelimit-remaining" to "1", "x-ratelimit-reset" to "1"), null, fpC)
        assertEquals(4000, health.rateLimit(fpA, "core")!!.remaining)
        assertEquals(4990, health.rateLimit(fpA, "graphql")!!.remaining)
        assertEquals(listOf(12), health.otherTokens(fpA).map { it.remaining })
        assertEquals(listOf(4000), health.otherTokens(fpB).map { it.remaining })
        assertEquals(2, health.otherTokens(null).size)
        clock.advance(Duration.ofHours(25))
        assertNull(health.rateLimit(fpA, "core"))
    }

    @Test
    fun `5xx and unreachable texts`() {
        val e = GitHubFailure.serverError("create issue", 500, "8F3A:1B2C:3D4E:5F60", null)
        assertEquals(
            "GitHub failed: create issue returned HTTP 500 (request id 8F3A:1B2C:3D4E:5F60). This is a problem at GitHub, not with Agern or your token. " +
                "See the GitHub status page in Agern (/github-status).",
            e.message,
        )
        assertEquals("8F3A:1B2C:3D4E:5F60", e.requestId)
        assertEquals(
            "GitHub failed: merge returned HTTP 502: Server Error. This is a problem at GitHub, not with Agern or your token. See the GitHub status page in Agern (/github-status).",
            GitHubFailure.serverError("merge", 502, null, "Server Error.").message,
        )
        assertEquals(
            "GitHub could not be reached (ConnectException). This is a network or GitHub problem, not a problem with your token. See the GitHub status page in Agern (/github-status).",
            GitHubFailure.unreachable(java.net.ConnectException()),
        )
        assertTrue(GitHubFailure.unreachable(java.net.UnknownHostException("api.github.internal"), "acme").contains("(UnknownHostException: [address])"))
    }

    @Test
    fun `the official summary is parsed and filtered`() {
        val body = """
            {"page":{"id":"x"},"status":{"indicator":"minor","description":"Partially Degraded Service"},
             "components":[{"name":"Git Operations","status":"operational"},{"name":"Pages","status":"major_outage"},
                           {"name":"Issues","status":"degraded_performance"}],
             "incidents":[{"name":"Incident with Issues","status":"investigating","impact":"minor","shortlink":"https://stspg.io/abc","updated_at":"2026-11-25T15:55:00Z"},
                          {"name":"Old","status":"resolved","shortlink":"https://stspg.io/old"},
                          {"name":"Evil\u0007link","status":"identified","shortlink":"javascript:alert(1)"}]}
        """.trimIndent()
        val o = OfficialGitHubStatus.parse(body, clock.instant())
        assertEquals("ok", o.state)
        assertEquals("minor", o.indicator)
        assertEquals(listOf(GitHubComponentDto("Issues", "degraded_performance", true), GitHubComponentDto("Pages", "major_outage", false)), o.components)
        assertEquals(listOf("Incident with Issues", "Evil link"), o.incidents.map { it.title })
        assertEquals("https://stspg.io/abc", o.incidents[0].url)
        assertNull(o.incidents[1].url)
        assertEquals("unknown", OfficialGitHubStatus.parse("""{"status":{"indicator":"weird"}}""", clock.instant()).indicator)
    }
}
