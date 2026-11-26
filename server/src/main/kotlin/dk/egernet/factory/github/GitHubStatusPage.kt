package dk.egernet.factory.github

import dk.egernet.factory.AppJson
import dk.egernet.factory.GitHubComponentDto
import dk.egernet.factory.GitHubIncidentDto
import dk.egernet.factory.GitHubKindDto
import dk.egernet.factory.GitHubOfficialDto
import dk.egernet.factory.GitHubOwnRateLimitsDto
import dk.egernet.factory.GitHubRateLimitDto
import dk.egernet.factory.GitHubRateLimitsDto
import dk.egernet.factory.GitHubStatusDto
import dk.egernet.factory.db.UserRecord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * GitHub's official status (githubstatus.com's `summary.json`, docs/ARCHITECTURE.md §6 "GitHub status"), fetched by the
 * server without credentials and cached for 60 s, failures included; concurrent callers share one fetch. Blocking:
 * call it from `Dispatchers.IO`. [url] null: switched off (state `disabled`, no request is ever made).
 */
class OfficialGitHubStatus(
    private val url: String?,
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofSeconds(60),
) {
    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build()
    }
    private val lock = Any()
    @Volatile private var cached: GitHubOfficialDto? = null
    @Volatile private var cachedAt: Instant = Instant.MIN

    fun get(): GitHubOfficialDto {
        if (url == null) return DISABLED
        fresh()?.let { return it }
        synchronized(lock) {
            fresh()?.let { return it }
            val now = clock.instant()
            val result = fetch(url, now)
            cached = result
            cachedAt = now
            return result
        }
    }

    private fun fresh(): GitHubOfficialDto? = cached?.takeIf { clock.instant().isBefore(cachedAt.plus(ttl)) }

    private fun fetch(url: String, now: Instant): GitHubOfficialDto {
        fun failed(error: String) = GitHubOfficialDto("unreachable", null, null, emptyList(), emptyList(), now.toString(), GitHubHealth.scrub(error, ""))
        val body = try {
            val req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET()
                .header("Accept", "application/json").header("User-Agent", "agern").build()
            val r = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
            r.body().use { stream ->
                if (r.statusCode() !in 200..299) return failed("HTTP ${r.statusCode()}")
                val bytes = stream.readNBytes(MAX_BYTES + 1)
                if (bytes.size > MAX_BYTES) return failed("The answer is larger than 1 MiB")
                String(bytes, Charsets.UTF_8)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return failed(e.javaClass.simpleName)
        } catch (e: Exception) {
            return failed(GitHubHealth.describe(e))
        }
        return try {
            parse(body, now)
        } catch (e: Exception) {
            failed("The answer could not be read")
        }
    }

    @Serializable private data class Summary(val status: SummaryStatus, val components: List<Component> = emptyList(), val incidents: List<Incident> = emptyList())
    @Serializable private data class SummaryStatus(val indicator: String? = null, val description: String? = null)
    @Serializable private data class Component(val name: String = "", val status: String = "")
    @Serializable
    private data class Incident(
        val name: String = "", val status: String = "", val impact: String? = null, val shortlink: String? = null,
        @SerialName("updated_at") val updatedAt: String? = null,
    )

    companion object {
        const val DEFAULT_URL = "https://www.githubstatus.com/api/v2/summary.json"
        private const val MAX_BYTES = 1 shl 20
        val DISABLED = GitHubOfficialDto("disabled", null, null, emptyList(), emptyList(), null, null)
        private val INDICATORS = setOf("none", "minor", "major", "critical")
        /** The components Agern depends on: only these raise the verdict to degraded. */
        val RELEVANT = setOf("api requests", "issues", "pull requests", "actions", "git operations", "webhooks")
        private val CONTROL = Regex("\\p{Cc}+")

        private fun clean(s: String?, max: Int): String? = s?.let { CONTROL.replace(it, " ").trim().take(max) }

        /** The status from a `summary.json` body; throws when it cannot be read. */
        fun parse(body: String, now: Instant): GitHubOfficialDto {
            val s = AppJson.decodeFromString(Summary.serializer(), body)
            val components = s.components.filter { it.status != "operational" && it.name.isNotBlank() }.map {
                val name = clean(it.name, 200)!!
                GitHubComponentDto(name, clean(it.status, 50)!!, name.lowercase() in RELEVANT)
            }.sortedByDescending { it.relevant }.take(50)
            val incidents = s.incidents.filter { it.status != "resolved" && it.status != "postmortem" }.take(20).map {
                val link = it.shortlink?.trim()?.takeIf { l -> l.length <= 500 && l.startsWith("https://") && CONTROL.find(l) == null && ' ' !in l }
                GitHubIncidentDto(clean(it.name, 200)!!, clean(it.status, 50)!!, clean(it.impact, 50), clean(it.updatedAt, 50), link)
            }
            return GitHubOfficialDto(
                "ok", s.status.indicator?.takeIf { it in INDICATORS } ?: "unknown", clean(s.status.description, 300),
                components, incidents, now.toString(), null,
            )
        }
    }
}

/**
 * Assembles `GET /api/github/status` (docs/ARCHITECTURE.md §6 "GitHub status", §8): the official status, what [health]
 * recorded, the verdict and the rate limits. The caller's own limits come from `GET /rate_limit` with their token
 * (cached 60 s per user); other tokens appear only as a count and one anonymous minimum. Blocking.
 */
class GitHubStatusService(
    private val health: GitHubHealth,
    private val official: OfficialGitHubStatus,
    private val access: Access,
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class Own(val fingerprint: String, val at: Instant, val limits: GhRateLimits?)
    private val own = ConcurrentHashMap<UUID, Own>()

    fun status(user: UserRecord, brief: Boolean): GitHubStatusDto {
        val off = official.get()
        val calls15m = health.totals(15).calls
        val verdict = GitHubHealth.verdict(health.verdictCounts(), off, calls15m)
        val kinds = if (brief) emptyList() else health.snapshot().map {
            GitHubKindDto(it.kind, it.label, it.last15m.toDto(), it.last24h.toDto(), it.lastError?.toDto())
        }
        return GitHubStatusDto(verdict, clock.instant().toString(), off, kinds, if (brief) null else rateLimits(user))
    }

    private fun rateLimits(user: UserRecord): GitHubRateLimitsDto {
        val token = runCatching { access.githubToken(user) }.getOrNull()
        val fp = token?.let { GitHubHealth.fingerprint(it) }
        val ownDto = if (token == null || fp == null) null else {
            val limits = ownLimits(user.id, token, fp)
            GitHubOwnRateLimitsDto(
                (limits?.rest ?: health.rateLimit(fp, "core"))?.toDto(),
                (limits?.graphql ?: health.rateLimit(fp, "graphql"))?.toDto(),
            )
        }
        val others = health.otherTokens(fp)
        val lowest = others.minByOrNull { if (it.limit > 0) it.remaining.toDouble() / it.limit else 0.0 }
        return GitHubRateLimitsDto(ownDto, others.size, lowest?.toDto())
    }

    private fun ownLimits(userId: UUID, token: String, fp: String): GhRateLimits? {
        val now = clock.instant()
        own[userId]?.takeIf { it.fingerprint == fp && now.isBefore(it.at.plusSeconds(60)) }?.let { return it.limits }
        val limits = runCatching { access.github.client(token).rateLimit() }.getOrNull()
        own[userId] = Own(fp, now, limits)
        return limits
    }

    private fun GhRateLimit.toDto() = GitHubRateLimitDto(limit, remaining, resetAt.toString(), observedAt.toString())
}
