package dk.egernet.factory.mcp

import dk.egernet.factory.db.McpTokenStore
import dk.egernet.factory.db.TwoFactorStore
import dk.egernet.factory.db.UserStore
import dk.egernet.factory.github.Access
import dk.egernet.factory.images.AttemptLimiter
import dk.egernet.factory.security.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The outcome of checking the bearer of a `POST /mcp` request. */
sealed interface McpAuth {
    data class Granted(val grant: McpGrant) : McpAuth
    /** 401 with JSON-RPC error -32001 and [message]. */
    data class Unauthorized(val message: String) : McpAuth
    /** 429: the client address guessed too often; it may try again at [until]. */
    data class Throttled(val until: Instant) : McpAuth
}

/**
 * Resolves the bearer of `POST /mcp` (docs/ARCHITECTURE.md §7): a live per-process token from [registry] first (never
 * throttled, so agents keep working whatever happens on 127.0.0.1); otherwise, unless the client address is blocked, a
 * personal token from [tokens], checked on every request (not revoked, not expired, its user exists and can still see
 * the project). Bearers that match nothing at all count as failed guesses per client address in [limiter].
 */
class McpAuthenticator(
    private val registry: McpRegistry,
    private val tokens: McpTokenStore,
    private val users: UserStore,
    private val access: Access,
    private val audit: TwoFactorStore,
    private val githubOrg: String,
    private val clock: Clock,
    val limiter: AttemptLimiter = AttemptLimiter(MAX_FAILURES, WINDOW, clock),
) {
    private val log = LoggerFactory.getLogger(McpAuthenticator::class.java)

    suspend fun authenticate(bearer: String?, remote: String): McpAuth {
        val token = bearer?.trim()?.takeIf { it.isNotEmpty() }
        token?.let { registry.lookup(it) }?.let { return McpAuth.Granted(it) }
        limiter.blockedUntil(remote)?.let { return McpAuth.Throttled(it) }
        if (token == null) return McpAuth.Unauthorized(UNAUTHORIZED)
        return withContext(Dispatchers.IO) { personal(token, remote) }
    }

    private fun personal(token: String, remote: String): McpAuth {
        val row = if (token.startsWith(PREFIX)) tokens.byHash(Tokens.sha256Hex(token)) else null
        if (row == null) {
            failed(remote)
            return McpAuth.Unauthorized(UNAUTHORIZED)
        }
        val now = clock.instant()
        if (row.revokedAt != null) return McpAuth.Unauthorized("This token was revoked")
        if (row.expired(now)) return McpAuth.Unauthorized("This token has expired")
        val user = users.byId(row.userId)
        if (user == null || !access.canSee(user, row.repo)) {
            return McpAuth.Unauthorized("The token's user can no longer see $githubOrg/${row.repo}")
        }
        tokens.touch(row.id, now)
        return McpAuth.Granted(
            McpGrant(null, user.id, row.repo, null, McpRegistry.PERSONAL_TOOLS, PersonalTokenRef(row.id, row.name)),
        )
    }

    private fun failed(remote: String) {
        val until = limiter.record(remote) ?: return
        log.warn("MCP: {} unknown bearer tokens from {} within {} min; refusing personal tokens from it until {}",
            MAX_FAILURES, remote, WINDOW.toMinutes(), until)
        runCatching { audit.audit(null, null, "mcp_token_locked", "until $until", remote.take(128)) }
            .onFailure { log.warn("Could not write the mcp_token_locked audit row: {}", it.message) }
    }

    companion object {
        /** Every personal token starts with this; nothing else is looked up in the database. */
        const val PREFIX = "agern_pat_"
        const val UNAUTHORIZED = "Unauthorized"
        const val MAX_FAILURES = 20
        val WINDOW: Duration = Duration.ofMinutes(15)

        /** A new personal token: [PREFIX] and 256 random bits (URL-safe base64). */
        fun newToken(): String = PREFIX + Tokens.random(32)
    }
}
