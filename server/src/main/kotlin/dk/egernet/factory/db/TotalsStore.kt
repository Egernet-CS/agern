package dk.egernet.factory.db

import dk.egernet.factory.TotalsRun
import dk.egernet.factory.TotalsSession
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** The runs and agent sessions of a scope, for [dk.egernet.factory.Totals]. */
data class TotalsRows(val runs: List<TotalsRun>, val sessions: List<TotalsSession>)

/**
 * Loads what the time and cost totals need (docs/ARCHITECTURE.md §6 "Time and cost"): three statements per scope, no
 * matter how many runs, sessions or issues it has.
 */
class TotalsStore(private val db: Db) {
    /** SQL statements issued so far (tests check that a scope costs a fixed number). */
    val statements = AtomicLong()

    /** Every run with `release_id` = [releaseId] (issue and sync runs, any status). */
    fun release(releaseId: UUID): TotalsRows = load("r.release_id = ?", releaseId)

    /** The run [runId] alone; empty when it does not exist. */
    fun run(runId: UUID): TotalsRows = load("r.id = ?", runId)

    private fun load(where: String, param: UUID): TotalsRows {
        // `where` is one of the two constants above; the value is always a bound parameter.
        statements.incrementAndGet()
        val updates = db.query(
            "SELECT u.run_id, u.round FROM run_updates u JOIN runs r ON r.id = u.run_id WHERE $where",
            param,
        ) { it.uuid("run_id") to it.getInt("round") }.groupBy({ it.first }, { it.second })
        statements.incrementAndGet()
        val runs = db.query(
            """SELECT r.id, r.issue_number, r.kind, r.created_at, r.ended_at, r.follow_up_from,
                      (r.kind = 'issue' AND EXISTS (
                          SELECT 1 FROM runs p
                           WHERE p.repo = r.repo AND p.issue_number = r.issue_number AND p.kind = 'issue'
                             AND p.release_id IS NOT DISTINCT FROM r.release_id AND p.created_at < r.created_at
                      )) AS retry_run
                 FROM runs r WHERE $where ORDER BY r.created_at, r.id""",
            param,
        ) { rs -> mapRun(rs, updates) }
        statements.incrementAndGet()
        val sessions = db.query(
            """SELECT s.id, s.run_id, s.kind, s.round, s.cred_kind, s.cost_usd, s.num_turns, s.status, s.created_at, s.ended_at
                 FROM agent_sessions s JOIN runs r ON r.id = s.run_id
                WHERE $where ORDER BY s.created_at, s.id""",
            param,
            map = ::mapSession,
        )
        return TotalsRows(runs, sessions)
    }

    private fun mapRun(rs: ResultSet, updates: Map<UUID, List<Int>>): TotalsRun {
        val id = rs.uuid("id")
        return TotalsRun(
            id = id, issueNumber = rs.getInt("issue_number"), kind = rs.getString("kind"),
            createdAt = rs.instant("created_at"), endedAt = rs.instantOrNull("ended_at"),
            followUpFrom = rs.intOrNull("follow_up_from"), retryRun = rs.getBoolean("retry_run"),
            updateRounds = updates[id].orEmpty().toSet(),
        )
    }

    private fun mapSession(rs: ResultSet) = TotalsSession(
        id = rs.uuid("id"), runId = rs.uuid("run_id"), kind = rs.getString("kind"), round = rs.intOrNull("round"),
        credKind = rs.getString("cred_kind"), costUsd = rs.doubleOrNull("cost_usd"), numTurns = rs.intOrNull("num_turns"),
        status = rs.getString("status"), createdAt = rs.instant("created_at"), endedAt = rs.instantOrNull("ended_at"),
    )
}
