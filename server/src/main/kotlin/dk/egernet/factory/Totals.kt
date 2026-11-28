package dk.egernet.factory

import dk.egernet.factory.db.ReleaseRecord
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToLong

/** A run as the totals need it ([dk.egernet.factory.db.TotalsStore]). */
data class TotalsRun(
    val id: UUID,
    val issueNumber: Int,
    /** [RunKind]. */
    val kind: String,
    val createdAt: Instant,
    val endedAt: Instant?,
    val followUpFrom: Int?,
    /** An earlier issue run of the same issue exists (same repository, same release or both single). */
    val retryRun: Boolean,
    /** The stage rounds of the run's update rounds (`run_updates.round`). */
    val updateRounds: Set<Int> = emptySet(),
)

/** An agent session as the totals need it. */
data class TotalsSession(
    val id: UUID,
    val runId: UUID,
    /** The role (`builder`, `security`, …) or `advisor`. */
    val kind: String,
    val round: Int?,
    /** `oauth_token`, `api_key`, or null when not recorded. */
    val credKind: String?,
    val costUsd: Double?,
    val numTurns: Int?,
    val status: String,
    val createdAt: Instant,
    val endedAt: Instant?,
)

/**
 * Time and cost of a run or a release, computed from its runs and agent sessions (docs/ARCHITECTURE.md §6 "Time and
 * cost"). Pure: the store loads the rows, "now" comes from the caller.
 */
object Totals {
    const val ADVISOR = "advisor"

    /** Order of the role rows; other kinds follow alphabetically. */
    val ROLE_ORDER = listOf("spec-writer", "builder", "security", "ux", "ui-design", "code", "approver", ADVISOR)

    object Work {
        const val BUILD = "build"; const val FIX = "fix"; const val UPDATE = "update"; const val RETRY = "retry"
        const val CONVERSATION = "conversation"; const val SYNC = "sync"
        val ORDER = listOf(BUILD, FIX, UPDATE, RETRY, CONVERSATION, SYNC)
    }

    private val ACTIVE = setOf(SessionStatus.STARTING, SessionStatus.RUNNING)

    private data class Window(val start: Instant, val end: Instant, val running: Boolean) {
        val ms: Long get() = maxOf(0L, end.toEpochMilli() - start.toEpochMilli())
    }

    fun forRun(run: TotalsRun, sessions: List<TotalsSession>, now: Instant): RunTotalsDto {
        val own = sessions.filter { it.runId == run.id }
        val window = Window(run.createdAt, run.endedAt ?: now, run.endedAt == null)
        val work = workKeys(listOf(run), own)
        return RunTotalsDto(
            runId = run.id.toString(),
            time = time(window, own, now),
            cost = cost(own),
            byRole = byRole(own, now),
            byWork = byWork(own, work, now),
        )
    }

    fun forRelease(release: ReleaseRecord, runs: List<TotalsRun>, sessions: List<TotalsSession>, now: Instant): ReleaseTotalsDto {
        val runIds = runs.map { it.id }.toSet()
        val scope = sessions.filter { it.runId in runIds }
        val window = releaseWindow(release, now)
        val byRun = scope.groupBy { it.runId }
        val work = workKeys(runs, scope)
        val listed = release.issues.map { it.number }.toSet()

        val issues = release.issues.map { issue ->
            val issueRuns = runs.filter { it.kind == RunKind.ISSUE && it.issueNumber == issue.number }
            if (issueRuns.isEmpty()) {
                IssueTotalsDto(issue.number, 0, TotalsTimeDto(0, 0, 0, running = false, working = false), cost(emptyList()))
            } else {
                val issueSessions = issueRuns.flatMap { byRun[it.id].orEmpty() }
                IssueTotalsDto(issue.number, issueRuns.size, time(issueWindow(issueRuns, window, release, now), issueSessions, now), cost(issueSessions))
            }
        }
        val otherRuns = runs.filter { !(it.kind == RunKind.ISSUE && it.issueNumber in listed) }
        val other = if (otherRuns.isEmpty()) null else OtherTotalsDto(otherRuns.size, cost(otherRuns.flatMap { byRun[it.id].orEmpty() }))

        return ReleaseTotalsDto(
            releaseId = release.id.toString(),
            time = if (window == null) TotalsTimeDto(0, 0, 0, running = false, working = false) else time(window, scope, now),
            cost = cost(scope),
            runs = runs.size,
            runIds = runs.map { it.id.toString() },
            issues = issues,
            other = other,
            byRole = byRole(scope, now),
            byWork = byWork(scope, work, now),
        )
    }

    // ------------------------------------------------------------ windows

    /** Null for a release that was never started. Ends that are not "now" never depend on the clock. */
    private fun releaseWindow(r: ReleaseRecord, now: Instant): Window? {
        val start = r.startedAt ?: return null
        val end: Instant? = when (r.status) {
            ReleaseStatus.RELEASED -> r.releasedAt ?: r.endedAt ?: r.updatedAt
            ReleaseStatus.CANCELLED -> r.endedAt ?: r.updatedAt
            ReleaseStatus.READY -> r.readyAt
            else -> null
        }
        return Window(start, end ?: now, end == null)
    }

    private fun issueWindow(runs: List<TotalsRun>, release: Window?, r: ReleaseRecord, now: Instant): Window {
        val start = runs.minOf { it.createdAt }
        val allEnded = runs.all { it.endedAt != null }
        var end = if (allEnded) runs.maxOf { it.endedAt!! } else now
        var running = !allEnded
        if (release != null && r.status in setOf(ReleaseStatus.RELEASED, ReleaseStatus.CANCELLED) && end > release.end) {
            end = release.end
            running = false
        }
        return Window(start, maxOf(start, end), running)
    }

    // ------------------------------------------------------------ time

    /** The interval of a stage session; null for one that ended without an end time (zero length). */
    private fun interval(s: TotalsSession, now: Instant): Pair<Long, Long>? {
        val end = s.endedAt ?: if (s.status in ACTIVE) now else return null
        val a = s.createdAt.toEpochMilli()
        val b = end.toEpochMilli()
        return if (b > a) a to b else null
    }

    private fun agentMs(s: TotalsSession, now: Instant): Long =
        if (s.kind == ADVISOR) 0 else interval(s, now)?.let { it.second - it.first } ?: 0

    /** The length of the union of the stage sessions' intervals, clipped to [w]. */
    internal fun unionMs(intervals: List<Pair<Long, Long>>, from: Long, to: Long): Long {
        val clipped = intervals.mapNotNull { (a, b) ->
            val x = maxOf(a, from)
            val y = minOf(b, to)
            if (y > x) x to y else null
        }.sortedBy { it.first }
        var total = 0L
        var curStart = Long.MIN_VALUE
        var curEnd = Long.MIN_VALUE
        for ((a, b) in clipped) {
            if (a > curEnd) {
                if (curEnd > curStart) total += curEnd - curStart
                curStart = a
                curEnd = b
            } else if (b > curEnd) {
                curEnd = b
            }
        }
        if (curEnd > curStart) total += curEnd - curStart
        return total
    }

    private fun time(w: Window, sessions: List<TotalsSession>, now: Instant): TotalsTimeDto {
        val stage = sessions.filter { it.kind != ADVISOR }
        val elapsed = w.ms
        val working = unionMs(stage.mapNotNull { interval(it, now) }, w.start.toEpochMilli(), w.start.toEpochMilli() + elapsed)
        return TotalsTimeDto(
            elapsedMs = elapsed,
            workingMs = working,
            waitingMs = maxOf(0L, elapsed - working),
            running = w.running,
            working = stage.any { it.status in ACTIVE },
        )
    }

    // ------------------------------------------------------------ cost

    private fun round6(v: Double): Double = (v * 1_000_000).roundToLong() / 1_000_000.0

    internal fun cost(sessions: List<TotalsSession>): CostDto {
        var api = 0.0
        var sub = 0.0
        var running = 0
        var unknown = 0
        for (s in sessions) {
            val c = s.costUsd
            when {
                c != null -> if (s.credKind == "api_key") api += c else sub += c
                s.status in ACTIVE -> running++
                else -> unknown++
            }
        }
        val apiR = round6(api)
        val subR = round6(sub)
        return CostDto(
            usd = round6(apiR + subR), apiKeyUsd = apiR, subscriptionUsd = subR,
            sessions = sessions.size, turns = sessions.sumOf { it.numTurns ?: 0 },
            runningSessions = running, unknownSessions = unknown,
        )
    }

    // ------------------------------------------------------------ breakdowns

    /** The kind of work of every session in [sessions] (docs/ARCHITECTURE.md §6 "Time and cost", first rule wins). */
    internal fun workKeys(runs: List<TotalsRun>, sessions: List<TotalsSession>): Map<UUID, String> {
        val runById = runs.associateBy { it.id }
        val firstOfAttempt = sessions
            .groupBy { Triple(it.runId, it.kind, it.round) }
            .mapValues { (_, list) -> list.minWith(compareBy<TotalsSession> { it.createdAt }.thenBy { it.id }).id }
        return sessions.associate { s ->
            val run = runById[s.runId]
            val round = s.round ?: 0
            s.id to when {
                run?.kind == RunKind.SYNC -> Work.SYNC
                s.kind == ADVISOR -> Work.CONVERSATION
                run?.retryRun == true || firstOfAttempt[Triple(s.runId, s.kind, s.round)] != s.id -> Work.RETRY
                run != null && s.round != null && s.round in run.updateRounds -> Work.UPDATE
                run?.followUpFrom != null && round >= run.followUpFrom -> Work.CONVERSATION
                round == 0 -> Work.BUILD
                else -> Work.FIX
            }
        }
    }

    private fun row(key: String, sessions: List<TotalsSession>, now: Instant) =
        TotalsRowDto(key, cost(sessions), sessions.sumOf { agentMs(it, now) })

    internal fun byRole(sessions: List<TotalsSession>, now: Instant): List<TotalsRowDto> {
        val groups = sessions.groupBy { it.kind }
        val known = ROLE_ORDER.filter { it in groups }
        val others = (groups.keys - ROLE_ORDER.toSet()).sorted()
        return (known + others).map { row(it, groups.getValue(it), now) }
    }

    private fun byWork(sessions: List<TotalsSession>, work: Map<UUID, String>, now: Instant): List<TotalsRowDto> {
        val groups = sessions.groupBy { work.getValue(it.id) }
        return Work.ORDER.filter { it in groups }.map { row(it, groups.getValue(it), now) }
    }
}
