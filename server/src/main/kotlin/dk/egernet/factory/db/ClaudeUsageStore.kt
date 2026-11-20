package dk.egernet.factory.db

import dk.egernet.factory.ClaudeUsageDto
import dk.egernet.factory.UsageSpendDto
import dk.egernet.factory.UsageWindowDto
import dk.egernet.factory.agent.ObservedWindow
import dk.egernet.factory.agent.RateLimitWindows
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Each user's own Claude usage (docs/ARCHITECTURE.md §5 "Claude usage"): the latest value per limit window, recorded
 * from `rate_limit_event`s, and the daily spend through Agern. Days are UTC. Only ever read for the user it belongs to.
 */
class ClaudeUsageStore(private val db: Db, private val clock: Clock) {

    /**
     * Upserts [windows] for [userId], but only while the user's current credential is still the OAuth token the agent was
     * launched with ([credEnc], compared inside SQL): a process on a replaced or removed credential, or on an API key,
     * records nothing. A new window type is not added once the user has [RateLimitWindows.MAX_WINDOWS]. Per window:
     * status and observation time are replaced; the reset time only by a known one; a missing utilization keeps the
     * stored one only while the reset time is unchanged. Returns the number of windows written.
     */
    fun recordWindows(userId: UUID, credEnc: ByteArray, windows: List<ObservedWindow>): Int {
        if (windows.isEmpty()) return 0
        val now = clock.instant()
        var written = 0
        for (w in windows.take(RateLimitWindows.MAX_WINDOWS)) {
            written += db.update(
                """INSERT INTO claude_usage_windows(user_id, window_type, utilization, status, resets_at, observed_at)
                   SELECT ?, ?, CAST(? AS DOUBLE PRECISION), CAST(? AS TEXT), CAST(? AS TIMESTAMPTZ), ?
                   WHERE EXISTS (SELECT 1 FROM users WHERE id = ? AND claude_cred_kind = 'oauth_token' AND claude_cred_enc = ?)
                     AND (EXISTS (SELECT 1 FROM claude_usage_windows WHERE user_id = ? AND window_type = ?)
                          OR (SELECT count(*) FROM claude_usage_windows WHERE user_id = ?) < ?)
                   ON CONFLICT (user_id, window_type) DO UPDATE SET
                     status = EXCLUDED.status,
                     observed_at = EXCLUDED.observed_at,
                     resets_at = COALESCE(EXCLUDED.resets_at, claude_usage_windows.resets_at),
                     utilization = CASE
                       WHEN EXCLUDED.utilization IS NOT NULL THEN EXCLUDED.utilization
                       WHEN EXCLUDED.resets_at IS NULL OR EXCLUDED.resets_at = claude_usage_windows.resets_at THEN claude_usage_windows.utilization
                       ELSE NULL END""",
                userId, w.type, w.utilization, w.status, w.resetsAt, now,
                userId, credEnc,
                userId, w.type, userId, RateLimitWindows.MAX_WINDOWS,
            )
        }
        return written
    }

    /** Adds [usd] to the user's spend of the current UTC day. */
    fun addSpend(userId: UUID, usd: Double) {
        if (!(usd > 0.0) || !usd.isFinite()) return
        db.update(
            """INSERT INTO claude_spend_daily(user_id, day, cost_usd) VALUES (?, CAST(? AS DATE), ?)
               ON CONFLICT (user_id, day) DO UPDATE SET cost_usd = claude_spend_daily.cost_usd + EXCLUDED.cost_usd""",
            userId, today().toString(), usd,
        )
    }

    /** [user]'s own usage: the limit windows (OAuth token only) and the spend of today and this month. */
    fun usage(user: UserRecord): ClaudeUsageDto {
        val kind = if (user.claudeCredEnc != null) user.claudeCredKind else null
        val windows = if (kind != "oauth_token") emptyList() else db.query(
            "SELECT window_type, utilization, status, resets_at, observed_at FROM claude_usage_windows WHERE user_id = ? ORDER BY window_type",
            user.id,
        ) {
            UsageWindowDto(
                it.getString("window_type"), it.doubleOrNull("utilization"), it.getString("status"),
                it.instantOrNull("resets_at")?.iso(), it.instant("observed_at").iso(),
            )
        }
        val day = today()
        val monthStart = day.withDayOfMonth(1)
        val (todayUsd, monthUsd) = db.queryOne(
            """SELECT COALESCE(sum(cost_usd) FILTER (WHERE day = CAST(? AS DATE)), 0) AS today,
                      COALESCE(sum(cost_usd), 0) AS month
               FROM claude_spend_daily WHERE user_id = ? AND day >= CAST(? AS DATE) AND day < CAST(? AS DATE)""",
            day.toString(), user.id, monthStart.toString(), monthStart.plusMonths(1).toString(),
        ) { it.getDouble("today") to it.getDouble("month") } ?: (0.0 to 0.0)
        return ClaudeUsageDto(kind, windows, UsageSpendDto(todayUsd, monthUsd, day.toString(), day.toString().substring(0, 7)))
    }

    private fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
}
