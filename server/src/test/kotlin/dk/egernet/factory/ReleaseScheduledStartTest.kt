package dk.egernet.factory

import dk.egernet.factory.db.ReleaseRecord
import dk.egernet.factory.db.UserRecord
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A scheduled start goes through the real start of step 2 (docs/ARCHITECTURE.md §6 "Releases", "Scheduled start"):
 * no substituted starter, the default binding to [ReleaseService.startScheduled].
 */
class ReleaseScheduledStartTest {
    private val clock = MutableClock()
    private val world = TestWorld(twoFactor = false, clock = clock)
    private val s = world.services
    private val alice = world.user("alice")
    private val bob = world.user("bob")

    init {
        // Builders hang until killed, so a started release keeps its first issue running.
        world.launcher.stage = { p ->
            when (p.spec.kind) {
                "spec-writer" -> { p.init(); p.result("## Spec\nDo it."); p.exit(0) }
                else -> { p.init(); while (!p.killed) Thread.sleep(10) }
            }
        }
    }

    @AfterTest fun tearDown() {
        world.close()
    }

    private fun issue(title: String) = world.github.addIssue("demo-app", title, "body").number

    private fun release(name: String, issues: List<Int>, by: UserRecord = alice): ReleaseRecord = runBlocking {
        val r = s.releases.create(by, "demo-app", name, "")
        if (issues.isNotEmpty()) s.releases.assign(by, "demo-app", issues, r.id)
        s.releaseStore.get(r.id)!!
    }

    private fun schedule(r: ReleaseRecord, by: UserRecord = alice) =
        runBlocking { s.releases.schedule(by, r.id, clock.now.plus(Duration.ofHours(1)).toString()) }

    private fun tick() = runBlocking { s.releaseScheduler.tick() }
    private fun get(r: ReleaseRecord) = s.releaseStore.get(r.id)!!

    private fun awaitIssue(r: ReleaseRecord, number: Int, state: String) = runBlocking {
        withTimeout(10_000) {
            while (get(r).issues.single { it.number == number }.state != state) kotlinx.coroutines.delay(20)
        }
    }

    @Test
    fun `a due scheduled release starts through the real start and builds its first issue`() {
        val n = issue("First")
        val r = release("1.4", listOf(n))
        schedule(r)
        assertEquals(409, assertFailsWith<ApiException> { runBlocking { s.releases.start(alice, r.id) } }.status.value,
            "start now refuses a scheduled release; cancel the schedule first")

        clock.advance(Duration.ofHours(2))
        tick()
        val started = get(r)
        assertEquals(ReleaseStatus.BUILDING, started.status)
        assertEquals(alice.id, started.ownerId, "the user who scheduled it owns the build")
        assertEquals("release/1.4", started.branch)
        assertNotNull(started.startedAt)
        assertNull(started.scheduledAt)
        assertNull(started.startFailedReason)
        assertTrue("branch demo-app release/1.4" in world.github.actions, world.github.actions.toString())
        awaitIssue(r, n, ReleaseIssueState.RUNNING)
        val run = s.runs.get(get(r).issues.single().runId!!)!!
        assertEquals(r.id, run.releaseId)
        assertEquals("release/1.4", run.baseBranch)
        // The run's own branch (one per run, docs/ARCHITECTURE.md §6 "Worktrees and branches").
        val branch = s.runs.get(run.id)!!.branch
        assertTrue(branch == null || branch.matches(Regex("factory/release-1\\.4/issue-$n-.+-${run.id.toString().take(8)}")), branch)
        assertEquals("alice", run.ownerUsername)
        // Further ticks do nothing.
        tick()
        assertEquals(1, world.github.actions.count { it == "branch demo-app release/1.4" })
    }

    @Test
    fun `a due release waits while another release of the project is building, then starts`() {
        val busy = release("busy", listOf(issue("Busy work")), by = bob)
        runBlocking { s.releases.start(bob, busy.id) }
        val n = issue("Later work")
        val r = release("1.4", listOf(n))
        schedule(r)
        clock.advance(Duration.ofHours(2))
        tick()
        assertEquals(ReleaseStatus.SCHEDULED, get(r).status)
        assertEquals(busy.id, get(r).waitingForId)
        assertNull(get(r).branch)

        runBlocking { s.releases.cancel(bob, busy.id) }
        tick()
        assertEquals(ReleaseStatus.BUILDING, get(r).status)
        assertNull(get(r).waitingForId)
        assertEquals(alice.id, get(r).ownerId)
        awaitIssue(r, n, ReleaseIssueState.RUNNING)
    }

    @Test
    fun `a start that fails for lack of network stays scheduled and starts at a later tick`() {
        val n = issue("Flaky network")
        val r = release("1.6", listOf(n))
        schedule(r)
        clock.advance(Duration.ofHours(2))
        world.github.createBranchFailure = 503
        tick()
        val still = get(r)
        assertEquals(ReleaseStatus.SCHEDULED, still.status)
        assertNull(still.startFailedReason)
        assertNotNull(still.scheduledAt)
        tick()
        assertEquals(ReleaseStatus.SCHEDULED, get(r).status)
        world.github.createBranchFailure = null
        tick()
        assertEquals(ReleaseStatus.BUILDING, get(r).status)
        assertNull(get(r).startFailedReason)
        awaitIssue(r, n, ReleaseIssueState.RUNNING)
    }

    @Test
    fun `refusals of the real start become start failures`() {
        val empty = release("empty", emptyList())
        schedule(empty)
        val n = issue("Some work")
        val taken = release("taken", listOf(n))
        schedule(taken, by = bob)
        world.github.branches.getOrPut("demo-app") { mutableSetOf() } += "release/taken"

        clock.advance(Duration.ofHours(2))
        tick()
        val e = get(empty)
        assertEquals(ReleaseStatus.PLANNING, e.status)
        assertEquals("Add an issue to the release first", e.startFailedReason)
        assertNull(e.ownerId)
        val t = get(taken)
        assertEquals(ReleaseStatus.PLANNING, t.status, "the claim is rolled back and the failure recorded")
        assertEquals("Branch release/taken already exists on GitHub; delete it or rename the release.", t.startFailedReason)
        assertEquals(bob.id, t.startFailedOwnerId)
        assertNull(t.ownerId)
        assertNull(t.branch)
        assertNull(t.startedAt)
        assertTrue(s.runs.list("demo-app", activeOnly = false).none { it.releaseId == t.id || it.releaseId == e.id })
        // Nothing holds the project's building slot afterwards.
        assertNull(s.releaseStore.activeIn("demo-app"))
    }
}
