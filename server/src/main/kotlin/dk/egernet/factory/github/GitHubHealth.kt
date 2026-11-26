package dk.egernet.factory.github

import dk.egernet.factory.GitHubCallCountsDto
import dk.egernet.factory.GitHubLastErrorDto
import dk.egernet.factory.GitHubOfficialDto
import dk.egernet.factory.GitHubVerdictDto
import dk.egernet.factory.pipeline.Transient
import org.slf4j.LoggerFactory
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The outcome class of one call to GitHub (docs/ARCHITECTURE.md §6 "GitHub status"). */
enum class CallClass(val wire: String) {
    OK("ok"), SERVER_ERROR("server_error"), NETWORK("network"), RATE_LIMITED("rate_limited"), AUTH("auth"),
    /** Git only: any other non-zero exit (a refused push, a missing ref). A call, never a failure. */
    OTHER("other");

    val isError: Boolean get() = this == SERVER_ERROR || this == NETWORK || this == RATE_LIMITED || this == AUTH
}

/** One rate-limit observation of one token and resource. */
data class GhRateLimit(val limit: Int, val remaining: Int, val resetAt: Instant, val observedAt: Instant)

/** The caller's limits from `GET /rate_limit`: `resources.core` and `resources.graphql`. */
data class GhRateLimits(val rest: GhRateLimit?, val graphql: GhRateLimit?)

/** Per-kind counts of one window, by class. */
data class CallCounts(val calls: Int, val serverErrors: Int, val network: Int, val rateLimited: Int, val auth: Int, val ok: Int) {
    fun toDto() = GitHubCallCountsDto(calls, serverErrors, network, rateLimited, auth)
}

/** The last error of one kind; [message] is already scrubbed. */
data class LastCallError(val at: Instant, val cls: CallClass, val status: Int?, val requestId: String?, val message: String) {
    fun toDto() = GitHubLastErrorDto(at.toString(), cls.wire, status, requestId, message)
}

/** One kind's view: the last 15 minutes, the last 24 hours and its last error (null when none or older than 24 h). */
data class KindSnapshot(val kind: String, val label: String, val last15m: CallCounts, val last24h: CallCounts, val lastError: LastCallError?)

/**
 * What Agern itself sees of GitHub (docs/ARCHITECTURE.md §6 "GitHub status"): one outcome per HTTP exchange of
 * [HttpGitHub] and per `clone`/`fetch`/`push` of the git CLI, counted per kind in a ring of 1440 one-minute buckets
 * (24 h, exact counts, bounded memory), the last error per kind (scrubbed) and the rate-limit headers per token
 * fingerprint. In memory only; nothing about requests is kept beyond that: no bodies, tokens, URLs or users.
 * Recording is thread-safe and never throws into the caller.
 */
class GitHubHealth(val org: String, private val clock: Clock = Clock.systemUTC()) {
    private val log = LoggerFactory.getLogger(GitHubHealth::class.java)

    private class Ring {
        val minute = LongArray(BUCKETS) { Long.MIN_VALUE }
        val counts = Array(BUCKETS) { IntArray(CallClass.entries.size) }
        var lastError: LastCallError? = null
    }

    private val lock = Any()
    private val rings = KINDS.associate { it.first to Ring() }

    private class TokenLimits(var observedAt: Instant, val byResource: MutableMap<String, GhRateLimit> = HashMap())
    /** Fingerprint → limits; insertion order = order of the last observation (oldest first). */
    private val limits = LinkedHashMap<String, TokenLimits>()

    /** One HTTP answer of GitHub's API for [path] (relative to the API base, or absolute). */
    fun recordHttp(path: String, status: Int, headers: (String) -> String?, bodyMessage: String?, tokenFingerprint: String?) = safely {
        val now = clock.instant()
        val cls = classifyHttp(status, headers, bodyMessage)
        val error = if (cls.isError) {
            val text = "HTTP $status" + (bodyMessage?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
            LastCallError(now, cls, status, validRequestId(headers(REQUEST_ID_HEADER)), scrub(text, org))
        } else null
        add(kindOf(path), cls, now, error)
        if (tokenFingerprint != null) observeLimits(tokenFingerprint, headers, now)
    }

    /** An HTTP exchange that ended in an exception (no answer). Only network failures are recorded. */
    fun recordHttpFailure(path: String, e: Throwable) = safely {
        if (e !is java.io.IOException && e !is java.nio.channels.UnresolvedAddressException) return@safely
        val now = clock.instant()
        add(kindOf(path), CallClass.NETWORK, now, LastCallError(now, CallClass.NETWORK, null, null, scrub(describe(e), org)))
    }

    /** One git `clone`/`fetch`/`push` against the remote; [exitCode] null = timed out. */
    fun recordGit(command: String, exitCode: Int?, output: String) = safely {
        val now = clock.instant()
        val cls = classifyGit(exitCode, output)
        val error = if (cls.isError) {
            val status = Regex("returned error: (\\d{3})").find(output)?.groupValues?.get(1)?.toIntOrNull()
            LastCallError(now, cls, status, null, scrub(gitErrorText(command, exitCode, output), org))
        } else null
        add(GIT_KIND, cls, now, error)
    }

    private fun add(kind: String, cls: CallClass, now: Instant, error: LastCallError?) {
        val minute = now.epochSecond.floorDiv(60)
        synchronized(lock) {
            val r = rings[kind] ?: rings.getValue("other")
            val i = minute.mod(BUCKETS.toLong()).toInt()
            if (r.minute[i] != minute) {
                r.minute[i] = minute
                r.counts[i].fill(0)
            }
            r.counts[i][cls.ordinal]++
            if (error != null) r.lastError = error
        }
    }

    private fun observeLimits(fp: String, headers: (String) -> String?, now: Instant) {
        val limit = headers("x-ratelimit-limit")?.trim()?.toIntOrNull() ?: return
        val remaining = headers("x-ratelimit-remaining")?.trim()?.toIntOrNull() ?: return
        val reset = headers("x-ratelimit-reset")?.trim()?.toLongOrNull() ?: return
        val resource = headers("x-ratelimit-resource")?.trim()?.lowercase()?.takeIf { RESOURCE_RE.matches(it) } ?: "core"
        synchronized(lock) {
            val t = limits.remove(fp) ?: TokenLimits(now)
            t.observedAt = now
            t.byResource[resource] = GhRateLimit(limit, remaining, Instant.ofEpochSecond(reset), now)
            limits[fp] = t
            while (limits.size > MAX_TOKENS) limits.remove(limits.keys.first())
        }
    }

    private fun dropOldLimits(now: Instant) {
        val cutoff = now.minus(DAY)
        limits.entries.removeIf { !it.value.observedAt.isAfter(cutoff) }
        for (t in limits.values) t.byResource.entries.removeIf { !it.value.observedAt.isAfter(cutoff) }
    }

    /** The last header observation of [fingerprint] for [resource] (`core`, `graphql`, …), at most 24 h old. */
    fun rateLimit(fingerprint: String, resource: String): GhRateLimit? = synchronized(lock) {
        dropOldLimits(clock.instant())
        limits[fingerprint]?.byResource?.get(resource)
    }

    /** The `core` observations of every token but [exceptFingerprint] whose reset lies in the future. */
    fun otherTokens(exceptFingerprint: String?): List<GhRateLimit> = synchronized(lock) {
        val now = clock.instant()
        dropOldLimits(now)
        limits.filterKeys { it != exceptFingerprint }.values.mapNotNull { it.byResource["core"] }.filter { it.resetAt.isAfter(now) }
    }

    private fun counts(r: Ring, minutes: Long, now: Instant): CallCounts {
        val sums = IntArray(CallClass.entries.size)
        val nowMinute = now.epochSecond.floorDiv(60)
        val cutoff = now.minus(Duration.ofMinutes(minutes))
        for (i in 0 until BUCKETS) {
            val m = r.minute[i]
            if (m == Long.MIN_VALUE || m > nowMinute) continue
            if (!Instant.ofEpochSecond(m * 60).isAfter(cutoff)) continue
            for (c in sums.indices) sums[c] += r.counts[i][c]
        }
        return CallCounts(
            calls = sums.sum(), serverErrors = sums[CallClass.SERVER_ERROR.ordinal], network = sums[CallClass.NETWORK.ordinal],
            rateLimited = sums[CallClass.RATE_LIMITED.ordinal], auth = sums[CallClass.AUTH.ordinal], ok = sums[CallClass.OK.ordinal],
        )
    }

    /** Every kind in display order, with its 15-minute and 24-hour counts and its last error. */
    fun snapshot(): List<KindSnapshot> = synchronized(lock) {
        val now = clock.instant()
        KINDS.map { (kind, label) ->
            val r = rings.getValue(kind)
            KindSnapshot(kind, label, counts(r, 15, now), counts(r, 24 * 60, now), r.lastError?.takeIf { it.at.isAfter(now.minus(DAY)) })
        }
    }

    /** All kinds together over the last [minutes] minutes (5 for the verdict, 15 for "no calls"). */
    fun totals(minutes: Long = VERDICT_MINUTES): CallCounts = synchronized(lock) {
        val now = clock.instant()
        rings.values.map { counts(it, minutes, now) }.fold(CallCounts(0, 0, 0, 0, 0, 0)) { a, b ->
            CallCounts(a.calls + b.calls, a.serverErrors + b.serverErrors, a.network + b.network, a.rateLimited + b.rateLimited, a.auth + b.auth, a.ok + b.ok)
        }
    }

    /** The counts the verdict is computed from: the last 5 minutes over all kinds. */
    fun verdictCounts(): CallCounts = totals(VERDICT_MINUTES)

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            runCatching { log.debug("Recording a GitHub call failed: {}", e.toString()) }
        }
    }

    companion object {
        const val BUCKETS = 24 * 60
        const val VERDICT_MINUTES = 5L
        const val MAX_TOKENS = 200
        const val GIT_KIND = "git"
        const val REQUEST_ID_HEADER = "x-github-request-id"
        private val DAY: Duration = Duration.ofHours(24)
        private val RESOURCE_RE = Regex("[a-z_]{1,40}")

        /** Every kind with its label, in the order the status page lists them. */
        val KINDS: List<Pair<String, String>> = listOf(
            "issues" to "Issues and labels",
            "pulls" to "Pull requests",
            "contents" to "Contents",
            "git_data" to "Branches and commits (API)",
            "checks" to "Checks and statuses",
            "repos" to "Repositories",
            "releases" to "Releases",
            "actions" to "Actions",
            "graphql" to "GraphQL",
            GIT_KIND to "Git (clone, fetch, push)",
            "other" to "Other",
        )

        private val REPO_PREFIX = Regex("^/repos/[^/]+/[^/]+")
        private val COMMIT_CHECKS = Regex("^/commits/[^/]+/(status|statuses|check-runs|check-suites)(/.*)?$")

        /** The kind of a REST call from its path (relative, or an absolute pagination URL). */
        fun kindOf(path: String): String {
            var p = path
            if (p.startsWith("http")) p = runCatching { URI(p).rawPath }.getOrNull() ?: ""
            p = p.substringBefore('?').substringBefore('#')
            // GitHub Enterprise: the API lives under /api/v3 (GraphQL under /api/graphql).
            if (p.startsWith("/api/v3/") || p == "/api/v3") p = p.removePrefix("/api/v3")
            else if (p == "/api/graphql") p = "/graphql"
            if (p == "/graphql" || p.startsWith("/graphql/")) return "graphql"
            val m = REPO_PREFIX.find(p)
            if (m == null) return if (seg(p, "orgs") || seg(p, "repos")) "repos" else "other"
            val rest = p.substring(m.range.last + 1)
            return when {
                seg(rest, "issues") || seg(rest, "labels") -> "issues"
                seg(rest, "pulls") -> "pulls"
                seg(rest, "releases") -> "releases"
                seg(rest, "actions") -> "actions"
                COMMIT_CHECKS.matches(rest) || seg(rest, "check-runs") || seg(rest, "check-suites") || seg(rest, "statuses") -> "checks"
                seg(rest, "contents") -> "contents"
                rest.startsWith("/git/") -> "git_data"
                else -> "repos"
            }
        }

        /** True when [p] is `/<name>` or starts with `/<name>/`. */
        private fun seg(p: String, name: String) = p == "/$name" || p.startsWith("/$name/")

        /** The class of an HTTP answer; [bodyMessage] is GitHub's `message` (only needed for a 403). */
        fun classifyHttp(status: Int, headers: (String) -> String?, bodyMessage: String?): CallClass = when {
            status in 500..599 -> CallClass.SERVER_ERROR
            status == 429 -> CallClass.RATE_LIMITED
            status == 403 && (headers("x-ratelimit-remaining")?.trim() == "0" || headers("retry-after") != null ||
                bodyMessage?.contains("rate limit", ignoreCase = true) == true) -> CallClass.RATE_LIMITED
            status == 401 || status == 403 -> CallClass.AUTH
            else -> CallClass.OK
        }

        private val GIT_SERVER_ERROR = Regex("The requested URL returned error: 5\\d\\d", RegexOption.IGNORE_CASE)
        private val GIT_RATE_LIMITED = Regex("returned error: 429", RegexOption.IGNORE_CASE)
        private val GIT_AUTH = Regex("Authentication failed|could not read Username|returned error: 40[13]", RegexOption.IGNORE_CASE)

        /** The class of one git command against the remote; [exitCode] null = timed out. */
        fun classifyGit(exitCode: Int?, output: String): CallClass = when {
            exitCode == null -> CallClass.NETWORK
            exitCode == 0 -> CallClass.OK
            GIT_SERVER_ERROR.containsMatchIn(output) -> CallClass.SERVER_ERROR
            GIT_RATE_LIMITED.containsMatchIn(output) -> CallClass.RATE_LIMITED
            GIT_AUTH.containsMatchIn(output) -> CallClass.AUTH
            Transient.isNetworkGitMessage(output) -> CallClass.NETWORK
            else -> CallClass.OTHER
        }

        /** Git's error in one line: the last `fatal:`/`error:` line, else the last non-empty line, else the timeout. */
        fun gitErrorText(command: String, exitCode: Int?, output: String): String {
            val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
            return lines.lastOrNull { it.startsWith("fatal:") || it.startsWith("error:") } ?: lines.lastOrNull() ?: "git $command timed out"
        }

        /** `<SimpleClassName>[: <message>]` of an exception (not yet scrubbed). */
        fun describe(e: Throwable): String {
            val name = e.javaClass.simpleName.ifEmpty { e.javaClass.name }
            val msg = e.message?.trim()
            return if (msg.isNullOrEmpty()) name else "$name: $msg"
        }

        private val REQUEST_ID_RE = Regex("[A-Za-z0-9:._-]{1,100}")

        /** GitHub's `x-github-request-id` when it looks like one; null otherwise. */
        fun validRequestId(v: String?): String? = v?.trim()?.takeIf { REQUEST_ID_RE.matches(it) }

        /** The first 16 hex characters of SHA-256 of [token]: a map key that never reveals the token. */
        fun fingerprint(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)

        private const val MAX_TEXT = 200
        private val WHITESPACE = Regex("[\\p{Cc}\\p{Z}\\s]+")
        private val GH_TOKEN = Regex("gh[pousr]_[A-Za-z0-9]{10,}")
        private val GH_PAT = Regex("github_pat_[A-Za-z0-9_]{10,}")
        private val AUTH_HEADER = Regex("(?i)(authorization:\\s*)(?:(?:bearer|token|basic)\\s+)?\\S+")
        private val AUTH_SCHEME = Regex("(?i)\\b(bearer|basic)\\s+(?!\\[redacted])[A-Za-z0-9._~+/=:-]+")
        /** `token <value>`: only values that look like a secret (8+ characters with a digit or `_`), not "token expired". */
        private val TOKEN_WORD = Regex("(?i)\\b(token)\\s+(?!\\[redacted])(?=[A-Za-z0-9._~+/=:-]*[0-9_])[A-Za-z0-9._~+/=:-]{8,}")
        private val USERINFO = Regex("(?i)\\b([a-z][a-z0-9+.-]*://)[^/@\\s]+@")
        private val URL = Regex("(?i)\\b([a-z][a-z0-9+.-]*://)([^/\\s?#'\"<>]+)([/?#][^\\s'\"<>]*)?")
        private val IPV4 = Regex("(?<![\\w.])\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?(?![\\w])")
        private val IPV6 = Regex("\\[[0-9A-Fa-f.]*:[0-9A-Fa-f:.%\\w]*](?::\\d{1,5})?")
        private val LOCALHOST = Regex("(?i)(?<![\\w.-])localhost(?::\\d{1,5})?(?![\\w-])")
        private val INTERNAL_HOST = Regex(
            "(?i)(?<![\\w.-])[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*" +
                "\\.(?:local|internal|lan|corp|home\\.arpa|localdomain)(?::\\d{1,5})?(?![\\w-])",
        )
        private val FS_PATH = Regex("(^|[\\s'\"])/(?:home|var|tmp|srv|opt|root|data|mnt|usr|etc|Users)/(?:[^\\s'\"]*[^\\s'\":,.;)\\]])?")

        /**
         * [text] safe to store and show to every user: one line, no credentials, URLs reduced to scheme and host, no
         * `<org>/<repo>` names, no internal addresses or filesystem paths, at most 200 characters.
         */
        fun scrub(text: String, org: String): String {
            var s = WHITESPACE.replace(text, " ").trim()
            s = GH_PAT.replace(s, "[redacted]")
            s = GH_TOKEN.replace(s, "[redacted]")
            s = AUTH_HEADER.replace(s) { it.groupValues[1] + "[redacted]" }
            s = AUTH_SCHEME.replace(s) { it.groupValues[1] + " [redacted]" }
            s = TOKEN_WORD.replace(s) { it.groupValues[1] + " [redacted]" }
            s = USERINFO.replace(s) { it.groupValues[1] + "[redacted]@" }
            s = URL.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + (if (m.groupValues[3].isNotEmpty()) "/…" else "") }
            if (org.isNotBlank()) {
                s = Regex("(?i)(?<![A-Za-z0-9._-])" + Regex.escape(org) + "/[A-Za-z0-9._-]+").replace(s) { "$org/[repository]" }
            }
            s = IPV6.replace(s, "[address]")
            s = IPV4.replace(s, "[address]")
            s = LOCALHOST.replace(s, "[address]")
            s = INTERNAL_HOST.replace(s, "[address]")
            s = FS_PATH.replace(s) { it.groupValues[1] + "[path]" }
            s = s.trim()
            return if (s.length > MAX_TEXT) s.take(MAX_TEXT - 1).trimEnd() + "…" else s
        }

        /**
         * The verdict (docs/ARCHITECTURE.md §6 "GitHub status") from the last 5 minutes of [counts], the official status
         * and the number of calls in the last 15 minutes. Rate-limit, auth and other outcomes never change it.
         */
        fun verdict(counts: CallCounts, official: GitHubOfficialDto?, calls15m: Int): GitHubVerdictDto {
            val f = counts.serverErrors + counts.network
            val failed = "(${plural(counts.serverErrors, "server error", "server errors")}, " +
                "${plural(counts.network, "timeout or network error", "timeouts or network errors")})"
            val troubled = if (official?.state == "ok") official.components.filter { it.relevant }.map { it.name } else emptyList()
            val officialSentence = if (troubled.isEmpty()) null else "GitHub reports problems with ${troubled.joinToString(", ")}."
            return when {
                f >= 3 && counts.ok == 0 ->
                    GitHubVerdictDto("down", "All $f GitHub calls by Agern failed in the last 5 minutes $failed.")
                f >= 2 -> GitHubVerdictDto(
                    "degraded",
                    "$f of ${counts.calls} GitHub calls by Agern failed in the last 5 minutes $failed." + (officialSentence?.let { " $it" } ?: ""),
                )
                officialSentence != null -> GitHubVerdictDto("degraded", officialSentence)
                calls15m == 0 -> GitHubVerdictDto("working", "Agern has made no GitHub calls in the last 15 minutes.")
                else -> GitHubVerdictDto("working", "Agern's calls to GitHub are succeeding.")
            }
        }

        private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
    }
}

/**
 * The texts of GitHub failures (docs/ARCHITECTURE.md §6 "GitHub status"): built at the source, so the API, MCP tool
 * errors, run failure reasons and release notes all show the same words and point at the status page.
 */
object GitHubFailure {
    const val STATUS_PAGE = "/github-status"
    /** Appended to a [dk.egernet.factory.git.GitException] when git's output says GitHub or the network failed. */
    const val GIT_HINT = " GitHub or the network failed; see the GitHub status page in Agern ($STATUS_PAGE)."

    /** A GitHub answer with status ≥ 500 (the message of statuses below 500 stays as it was). */
    fun serverError(what: String, status: Int, requestId: String?, githubMessage: String?, org: String = ""): GitHubException {
        val id = GitHubHealth.validRequestId(requestId)
        val msg = githubMessage?.let { GitHubHealth.scrub(it, org) }?.trim()?.trimEnd('.')?.takeIf { it.isNotBlank() }
        val text = "GitHub failed: $what returned HTTP $status" + (id?.let { " (request id $it)" } ?: "") + (msg?.let { ": $it" } ?: "") +
            ". This is a problem at GitHub, not with Agern or your token. See the GitHub status page in Agern ($STATUS_PAGE)."
        return GitHubException(status, text, id)
    }

    /** The message for a network error or timeout talking to GitHub. */
    fun unreachable(e: Throwable, org: String = ""): String =
        "GitHub could not be reached (${GitHubHealth.scrub(GitHubHealth.describe(e), org)}). This is a network or GitHub problem, " +
            "not a problem with your token. See the GitHub status page in Agern ($STATUS_PAGE)."
}
