package dk.egernet.factory

import dk.egernet.factory.db.ReleaseIssueRecord
import dk.egernet.factory.db.ReleaseRecord
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Time and cost computed from runs and sessions (docs/ARCHITECTURE.md §6 "Time and cost"); no database. */
class TotalsTest {
    private val t0: Instant = Instant.parse("2026-01-01T10:00:00Z")
    private fun at(min: Long): Instant = t0.plusSeconds(min * 60)
    private val now = at(600)
    private fun min(n: Long) = n * 60_000

    private var seq = 0L
    private fun id(): UUID = UUID(0, ++seq)

    private fun run(
        issue: Int = 1, from: Long = 0, to: Long? = null, kind: String = RunKind.ISSUE, retry: Boolean = false,
        followUpFrom: Int? = null, updates: Set<Int> = emptySet(),
    ) = TotalsRun(id(), issue, kind, at(from), to?.let(::at), followUpFrom, retry, updates)

    private fun session(
        run: TotalsRun, kind: String = "builder", from: Long = 0, to: Long? = from + 10, cost: Double? = 1.0,
        round: Int? = 0, cred: String? = "oauth_token", turns: Int? = 2, status: String = if (to == null) "running" else "done",
    ) = TotalsSession(id(), run.id, kind, round, cred, cost, turns, status, at(from), to?.let(::at))

    private fun release(
        status: String, issues: List<Int>, started: Long? = 0, ready: Long? = null, released: Long? = null, ended: Long? = null,
    ) = ReleaseRecord(
        id = id(), repo = "demo-app", name = "1.0", description = "", status = status,
        createdBy = id(), createdByUsername = "alice", createdByDisplayName = "Alice", createdAt = t0, updatedAt = at(1000),
        issues = issues.map { ReleaseIssueRecord(it, "Issue $it", "feature") },
        startedAt = started?.let(::at), readyAt = ready?.let(::at), releasedAt = released?.let(::at), endedAt = ended?.let(::at),
    )

    private fun assertMoney(expected: Double, actual: Double) = assertEquals(expected, actual, 1e-9)

    @Test
    fun `the sum over several runs counts failed and retry runs, each session once`() {
        val failed = run(issue = 1, from = 0, to = 30)
        val retry = run(issue = 1, from = 40, to = 90, retry = true)
        val other = run(issue = 2, from = 100, to = 150)
        val sessions = listOf(
            session(failed, "spec-writer", 0, 10, 0.5),
            session(failed, "builder", 10, 30, 1.25),
            session(retry, "spec-writer", 40, 50, 0.5),
            session(retry, "builder", 50, 80, 2.0),
            session(retry, "code", 80, 90, 0.25),
            session(other, "builder", 100, 150, 3.0),
        )
        val t = Totals.forRelease(release(ReleaseStatus.RELEASED, listOf(1, 2), released = 200), listOf(failed, retry, other), sessions, now)
        assertMoney(7.5, t.cost.usd)
        assertEquals(6, t.cost.sessions)
        assertEquals(12, t.cost.turns)
        assertEquals(3, t.runs)
        val one = t.issues.single { it.number == 1 }
        assertEquals(2, one.runs)
        assertMoney(4.5, one.cost.usd)
        assertEquals(min(90), one.time.elapsedMs)
        assertEquals(min(80), one.time.workingMs)
        assertEquals(min(10), one.time.waitingMs)
        val retryRow = t.byWork.single { it.key == "retry" }
        assertMoney(2.75, retryRow.cost.usd)
        assertEquals(3, retryRow.cost.sessions)
        assertNull(t.other)
    }

    @Test
    fun `unknown and running sessions add nothing to the amount`() {
        val r = run(to = null)
        val sessions = listOf(
            session(r, "spec-writer", 0, 10, 0.5),
            session(r, "builder", 10, 20, null, status = "failed"),
            session(r, "security", 20, null, null),
        )
        val t = Totals.forRun(r, sessions, now)
        assertMoney(0.5, t.cost.usd)
        assertEquals(1, t.cost.unknownSessions)
        assertEquals(1, t.cost.runningSessions)
        assertEquals(3, t.cost.sessions)
        assertTrue(t.time.running)
        assertTrue(t.time.working)
        // The running session runs to now, the failed one has its end time.
        assertEquals(min(600), t.time.workingMs)
    }

    @Test
    fun `a session that ended without an end time has no length`() {
        val r = run(to = 100)
        val s = session(r, "builder", 0, null, null, status = "failed")
        val t = Totals.forRun(r, listOf(s), now)
        assertEquals(0, t.time.workingMs)
        assertEquals(1, t.cost.unknownSessions)
        assertFalse(t.time.working)
        assertFalse(t.time.running)
    }

    @Test
    fun `credential kinds are split and an unrecorded kind counts as subscription`() {
        val r = run(to = 100)
        val t = Totals.forRun(r, listOf(
            session(r, "spec-writer", 0, 10, 1.0, cred = "api_key"),
            session(r, "builder", 10, 20, 2.0, cred = "oauth_token"),
            session(r, "code", 20, 30, 0.5, cred = null),
        ), now)
        assertMoney(1.0, t.cost.apiKeyUsd)
        assertMoney(2.5, t.cost.subscriptionUsd)
        assertMoney(3.5, t.cost.usd)
    }

    @Test
    fun `working time is the union of overlapping and disjoint intervals, clipped to the window`() {
        val r = run(from = 0, to = 100)
        val sessions = listOf(
            session(r, "security", 10, 30),
            session(r, "ux", 20, 40),      // overlaps security: 10..40 = 30
            session(r, "code", 50, 60),    // disjoint: 10
            session(r, "approver", 90, 120), // clipped at 100: 10
            session(r, "advisor", 0, 100, round = null), // advisors are not working time
        )
        val t = Totals.forRun(r, sessions, now)
        assertEquals(min(100), t.time.elapsedMs)
        assertEquals(min(50), t.time.workingMs)
        assertEquals(t.time.elapsedMs - t.time.workingMs, t.time.waitingMs)
        // agentMs adds each agent's own time, not clipped: 20 + 20 + 10 + 30, and 0 for the advisor.
        assertEquals(min(80), t.byRole.sumOf { it.agentMs })
        assertEquals(0, t.byRole.single { it.key == "advisor" }.agentMs)
        assertEquals(0L, Totals.unionMs(emptyList(), 0, 10))
        assertEquals(13L, Totals.unionMs(listOf(5L to 10L, 0L to 3L, 8L to 20L, 1L to 2L), 0, 15)) // 0..3 + 5..15
    }

    @Test
    fun `release windows`() {
        val r = run(from = 10, to = 50)
        val sessions = listOf(session(r, "builder", 10, 40))
        fun t(rel: ReleaseRecord, n: Instant) = Totals.forRelease(rel, listOf(r), sessions, n).time

        val building = release(ReleaseStatus.BUILDING, listOf(1))
        val b1 = t(building, at(100))
        val b2 = t(building, at(160))
        assertTrue(b1.running && b2.running)
        assertEquals(min(100), b1.elapsedMs)
        assertEquals(min(160), b2.elapsedMs)
        assertEquals(min(30), b2.workingMs)
        assertEquals(min(130), b2.waitingMs)
        assertTrue(t(release(ReleaseStatus.PAUSED, listOf(1)), at(100)).running)

        val ready = release(ReleaseStatus.READY, listOf(1), ready = 60)
        assertEquals(min(60), t(ready, at(100)).elapsedMs)
        assertFalse(t(ready, at(100)).running)

        val released = release(ReleaseStatus.RELEASED, listOf(1), ready = 60, released = 80)
        assertEquals(t(released, at(100)), t(released, at(900)))
        assertEquals(min(80), t(released, at(100)).elapsedMs)
        assertFalse(t(released, at(100)).running)

        val cancelled = release(ReleaseStatus.CANCELLED, listOf(1), ended = 45)
        assertEquals(t(cancelled, at(100)), t(cancelled, at(900)))
        assertEquals(min(45), t(cancelled, at(100)).elapsedMs)
        assertEquals(min(30), t(cancelled, at(100)).workingMs)

        val planned = release(ReleaseStatus.PLANNING, listOf(1), started = null)
        val p = Totals.forRelease(planned, emptyList(), emptyList(), at(100))
        assertEquals(TotalsTimeDto(0, 0, 0, running = false, working = false), p.time)
        assertEquals(0, p.runs)
        assertEquals(0, p.issues.single().runs)
    }

    @Test
    fun `the issue window runs to now while a run is active and is capped by a finished release`() {
        val ended = run(issue = 1, from = 10, to = 30)
        val active = run(issue = 1, from = 40, to = null, retry = true)
        val sessions = listOf(session(ended, "builder", 10, 20), session(active, "builder", 40, null, null))
        val building = Totals.forRelease(release(ReleaseStatus.BUILDING, listOf(1, 2)), listOf(ended, active), sessions, at(100))
        val issue = building.issues.first()
        assertEquals(min(90), issue.time.elapsedMs)
        assertTrue(issue.time.running)
        assertTrue(issue.time.working)
        assertEquals(min(10 + 60), issue.time.workingMs)
        val none = building.issues[1]
        assertEquals(2, none.number)
        assertEquals(0, none.runs)
        assertEquals(0L, none.time.elapsedMs)
        assertMoney(0.0, none.cost.usd)

        val cancelled = Totals.forRelease(release(ReleaseStatus.CANCELLED, listOf(1), ended = 50), listOf(ended, active), sessions, at(100))
        assertEquals(min(40), cancelled.issues.single().time.elapsedMs)
        assertFalse(cancelled.issues.single().time.running)
    }

    @Test
    fun `every rule of the kind of work`() {
        val sync = run(issue = 0, kind = RunKind.SYNC, to = 10)
        val retryRun = run(issue = 2, retry = true, to = 10)
        val r = run(issue = 1, to = 100, followUpFrom = 4, updates = setOf(3))
        val s = mapOf(
            "sync" to session(sync, "builder"),
            "advisor" to session(r, "advisor", round = null),
            "retryRun" to session(retryRun, "builder"),
            "build" to session(r, "builder", from = 0, round = 0),
            "again" to session(r, "builder", from = 5, round = 0),
            "fix" to session(r, "builder", from = 20, round = 1),
            "fix2" to session(r, "security", from = 30, round = 2),
            "update" to session(r, "builder", from = 40, round = 3),
            "followUp" to session(r, "security", from = 50, round = 4),
            "followUp2" to session(r, "builder", from = 60, round = 5),
            "nullRound" to session(r, "spec-writer", from = 0, round = null),
        )
        val keys = Totals.workKeys(listOf(sync, retryRun, r), s.values.toList())
        fun k(name: String) = keys.getValue(s.getValue(name).id)
        assertEquals("sync", k("sync"))
        assertEquals("conversation", k("advisor"))
        assertEquals("retry", k("retryRun"))
        assertEquals("build", k("build"))
        assertEquals("retry", k("again"))
        assertEquals("fix", k("fix"))
        assertEquals("fix", k("fix2"))
        assertEquals("update", k("update"))
        assertEquals("conversation", k("followUp"))
        assertEquals("conversation", k("followUp2"))
        assertEquals("build", k("nullRound"))
    }

    @Test
    fun `the earliest of two sessions created at the same time is told apart by id`() {
        val r = run(to = 100)
        val a = session(r, "builder", from = 0)
        val b = session(r, "builder", from = 0)
        val keys = Totals.workKeys(listOf(r), listOf(b, a))
        assertEquals("build", keys[a.id])
        assertEquals("retry", keys[b.id])
    }

    @Test
    fun `runs outside the issue list are other`() {
        val listed = run(issue = 1, to = 10)
        val sync = run(issue = 0, kind = RunKind.SYNC, from = 20, to = 30)
        val takenOut = run(issue = 7, from = 5, to = 15)
        val sessions = listOf(session(listed, cost = 1.0), session(sync, from = 20, cost = 0.5), session(takenOut, from = 5, cost = 0.25))
        val t = Totals.forRelease(release(ReleaseStatus.BUILDING, listOf(1)), listOf(listed, sync, takenOut), sessions, now)
        assertEquals(2, t.other!!.runs)
        assertMoney(0.75, t.other!!.cost.usd)
        assertMoney(1.75, t.cost.usd)
        assertEquals(3, t.runs)
        assertEquals(1, t.issues.single().runs)
        assertEquals(listOf("build", "sync"), t.byWork.map { it.key })
    }

    @Test
    fun `rows are ordered and add up to the total`() {
        val r = run(to = 200)
        val sessions = listOf(
            session(r, "advisor", 0, 50, 0.3, round = null),
            session(r, "zeta", 0, 5, 0.1),
            session(r, "approver", 90, 95, 0.2),
            session(r, "code", 60, 70, 0.4),
            session(r, "alpha", 0, 5, 0.1),
            session(r, "builder", 10, 50, 1.7),
            session(r, "ux", 60, 70, 0.4, round = 1),
            session(r, "spec-writer", 0, 10, 0.6),
            session(r, "security", 60, 70, null, status = "failed"),
            session(r, "ui-design", 60, 70, 0.4),
        )
        val t = Totals.forRun(r, sessions, now)
        assertEquals(
            listOf("spec-writer", "builder", "security", "ux", "ui-design", "code", "approver", "advisor", "alpha", "zeta"),
            t.byRole.map { it.key },
        )
        assertEquals(listOf("build", "fix", "conversation"), t.byWork.map { it.key })
        for (rows in listOf(t.byRole, t.byWork)) {
            assertMoney(t.cost.usd, rows.sumOf { it.cost.usd })
            assertEquals(t.cost.sessions, rows.sumOf { it.cost.sessions })
            assertEquals(t.cost.unknownSessions, rows.sumOf { it.cost.unknownSessions })
        }
    }
}
