package dk.egernet.factory

import dk.egernet.factory.db.RunRecord
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.pipeline.Orchestrator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pull requests merged or closed on GitHub outside Agern (docs/ARCHITECTURE.md §6 "Pull requests merged or closed on
 * GitHub"), for every merge policy, with the fake launcher and fake GitHub.
 */
class OutsideMergeTest {
    private val world = TestWorld(twoFactor = false)
    private val s = world.services
    private val gh = world.github
    private val alice = world.user("alice")
    private val root = world.user("root", admin = true)
    private val latches = mutableListOf<CountDownLatch>()

    @AfterTest fun tearDown() {
        latches.forEach { while (it.count > 0) it.countDown() }
        world.close()
    }

    init {
        world.git.deleteWorktrees = true
        s.orchestrator.outsideCheckMillis = 0
    }

    private fun latch() = CountDownLatch(1).also { latches += it }

    private fun script(
        approver: String = "ESCALATE", spec: String = "## Spec\nDo the thing.",
        reviewGate: CountDownLatch? = null, approverGate: CountDownLatch? = null, approverStarted: CountDownLatch? = null,
    ) {
        world.launcher.stage = { p ->
            p.init()
            when (p.spec.kind) {
                "spec-writer" -> p.result(spec)
                "builder" -> p.result("Built it.")
                "approver" -> {
                    approverStarted?.countDown()
                    approverGate?.await(10, TimeUnit.SECONDS)
                    p.result(null, buildJsonObject { put("verdict", approver); put("summary", "approver says $approver.") })
                }
                else -> {
                    reviewGate?.let { g -> while (!p.killed && !g.await(10, TimeUnit.MILLISECONDS)) Unit }
                    p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "fine"); put("findings", buildJsonArray { }) })
                }
            }
            p.exit(0)
        }
    }

    private fun policy(p: String) = s.projectSettings.put("demo-app", ProjectSettingsDto(p), root.id)
    private fun start(issue: Int = 1, by: UserRecord = alice) = runBlocking { s.orchestrator.start(by, "demo-app", issue, null) }
    private fun finished(run: RunRecord) = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
    private fun code(block: suspend () -> Unit): ApiException = assertFailsWith<ApiException> { runBlocking { block() } }
    private fun comments(issue: Int = 1) = gh.repos.getValue("demo-app").comments[issue].orEmpty().mapNotNull { it.body }
    private fun merges() = gh.actions.filter { it.startsWith("merge ") }
    private fun worktreeRemoved(run: RunRecord) = world.git.removedWorktrees.any { it.toString().contains(run.id.toString()) }
    private fun get(run: RunRecord) = runBlocking { s.factory.getRun(alice, run.id) }
    private fun awaitStatus(run: RunRecord, status: String) = runBlocking {
        withTimeoutOrNull(10_000) { while (s.runs.get(run.id)!!.status != status) delay(10) }
        s.runs.get(run.id)!!
    }

    private fun assertEndedMergedOutside(run: RunRecord, actionsBefore: Int, by: String?) {
        val dto = get(run)
        assertEquals(RunStatus.MERGED, dto.status)
        assertTrue(dto.mergedOnGitHub)
        assertEquals(by, dto.mergedBy)
        assertNull(dto.error)
        val r = s.runs.get(run.id)!!
        assertNotNull(r.endedAt)
        assertTrue(worktreeRemoved(run))
        assertTrue(s.runs.stages(run.id).none { it.status == "pending" || it.status == "running" })
        assertNull(s.orchestrator.job(run.id))
        val after = gh.actions.drop(actionsBefore)
        assertTrue(after.contains("unlabel demo-app#1 needs-human"), after.toString())
        assertTrue(after.none { it.startsWith("close ") || it.startsWith("comment ") || it.startsWith("merge ") }, after.toString())
    }

    @Test
    fun `a needs_human run under automatic is merged on GitHub and ends merged on the next read`() {
        script()
        val run = finished(start())
        assertEquals(RunStatus.NEEDS_HUMAN, run.status)
        val before = gh.actions.size
        gh.mergeOnGitHub(run.prNumber!!, by = "carol")
        assertEndedMergedOutside(run, before, "carol")
        assertEquals("open", gh.repos.getValue("demo-app").issues.first { it.number == 1 }.state)
    }

    @Test
    fun `a needs_human run under owner_approves is merged on GitHub and ends merged`() {
        policy(MergePolicy.OWNER_APPROVES)
        script(approver = "APPROVE")
        val run = finished(start())
        assertEquals(RunStatus.NEEDS_HUMAN, run.status)
        val before = gh.actions.size
        gh.mergeOnGitHub(run.prNumber!!, by = null)
        assertEquals(Orchestrator.Outside.MERGED, runBlocking { s.orchestrator.checkOutside(run.id) })
        assertEndedMergedOutside(run, before, null)
    }

    @Test
    fun `a pull request closed on GitHub cancels the run`() {
        script()
        val run = finished(start())
        val before = gh.actions.size
        gh.closePull(run.prNumber!!)
        val dto = get(run)
        assertEquals(RunStatus.CANCELLED, dto.status)
        assertEquals("Pull request #${run.prNumber} was closed on GitHub without being merged.", dto.error)
        assertTrue(worktreeRemoved(run))
        val after = gh.actions.drop(before)
        assertTrue(after.none { it.startsWith("close ") || it.startsWith("comment ") }, after.toString())
    }

    @Test
    fun `approve, cancel and update after an outside merge or close do not merge again`() {
        script()
        val run = finished(start(issue = 1))
        gh.mergeOnGitHub(run.prNumber!!, by = "carol")
        val merges = merges().size
        val e = code { s.orchestrator.approve(alice, run.id) }
        assertEquals("pull_request_merged", e.code)
        assertEquals(
            "Pull request #${run.prNumber} was already merged on GitHub by @carol; run ${run.id} is merged and nothing was merged again.", e.message,
        )
        assertEquals(merges, merges().size)
        // Again, now that the run is already merged.
        assertEquals("pull_request_merged", code { s.orchestrator.approve(alice, run.id) }.code)
        assertEquals("pull_request_merged", code { s.orchestrator.updateFromBase(alice, run.id) }.code)
        assertEquals("Run is already merged", code { s.orchestrator.cancel(root, run.id) }.message)

        val chat = s.sessions.create("demo-app", "chat", "opus", "idle", alice.id, null)
        val closed = finished(start(issue = 2))
        gh.closePull(closed.prNumber!!)
        val reply = try { runBlocking { s.factory.handleCommand(chat, alice, "/factory approve ${closed.id}") } } catch (x: ApiException) { x.code + ": " + x.message }
        assertEquals("pull_request_closed: Pull request #${closed.prNumber} was closed on GitHub without being merged; run ${closed.id} is cancelled.", reply)
        assertEquals(RunStatus.CANCELLED, s.runs.get(closed.id)!!.status)
        assertEquals(merges, merges().size)
    }

    @Test
    fun `cancel after an outside merge answers that the run is merged`() {
        script()
        val run = finished(start())
        gh.mergeOnGitHub(run.prNumber!!)
        val e = code { s.orchestrator.cancel(alice, run.id) }
        assertEquals(409, e.status.value)
        assertEquals("Run is already merged", e.message)
    }

    @Test
    fun `a run that is reviewing ends merged when its pull request was merged on GitHub`() {
        val gate = latch()
        script(reviewGate = gate)
        val run = start()
        val reviewing = awaitStatus(run, RunStatus.REVIEWING)
        assertEquals(RunStatus.REVIEWING, reviewing.status)
        assertNotNull(reviewing.prNumber)
        gh.mergeOnGitHub(reviewing.prNumber!!, by = "carol")
        val dto = get(run)
        assertEquals(RunStatus.MERGED, dto.status)
        assertTrue(dto.mergedOnGitHub)
        assertNull(s.orchestrator.job(run.id))
        assertTrue(s.runs.stages(run.id).none { it.status == "pending" || it.status == "running" })
        assertTrue(worktreeRemoved(run))
        assertTrue(merges().isEmpty())
    }

    @Test
    fun `the merge gate reads the pull request first and starts no update round for an outside merge`() {
        val started = latch()
        val gate = latch()
        script(approver = "APPROVE", approverGate = gate, approverStarted = started)
        val run = start()
        assertTrue(started.await(10, TimeUnit.SECONDS))
        val approving = s.runs.get(run.id)!!
        assertEquals(RunStatus.APPROVING, approving.status)
        gh.mergeOnGitHub(approving.prNumber!!, by = "carol")
        // While approving, the read path leaves it to the gate.
        assertEquals(Orchestrator.Outside.NONE, runBlocking { s.orchestrator.checkOutside(run.id, force = true) })
        // The target moved (the outside merge): the gate would start an update round if it compared first.
        world.git.comparison = { _, _ -> dk.egernet.factory.git.BranchComparison(emptyList(), emptyList(), behind = 1) }
        gate.countDown()
        val done = finished(run)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertTrue(done.mergedOnGitHub)
        assertEquals("carol", done.mergedBy)
        assertTrue(merges().isEmpty(), gh.actions.toString())
        assertTrue(s.runs.updates(run.id).isEmpty())
        assertTrue(gh.actions.none { it.startsWith("close ") })
        assertTrue(comments().none { it.startsWith("**Agern: merged**") })
    }

    @Test
    fun `a partial run merged on GitHub gets the partial comment only`() {
        script(spec = PartialDeliveryTest.PLAN_SPEC)
        val run = finished(start())
        assertTrue(run.partial)
        val before = gh.actions.size
        gh.mergeOnGitHub(run.prNumber!!, by = "carol")
        assertEquals(RunStatus.MERGED, get(run).status)
        val after = gh.actions.drop(before)
        assertEquals(1, after.count { it.startsWith("comment ") }, after.toString())
        assertTrue(after.none { it.startsWith("close ") })
        assertEquals(
            "**Agern:** pull request #${run.prNumber} was merged on GitHub by @carol. This is a partial delivery: the issue stays open." +
                "\n\n**Delivered:** the first step.\n\n**Remaining:**\n- the second step\n- the third step",
            comments().last(),
        )
    }

    @Test
    fun `a new run starts for an issue whose active run was merged on GitHub`() {
        script()
        val run = finished(start())
        gh.mergeOnGitHub(run.prNumber!!)
        val next = start()
        assertTrue(next.id != run.id)
        assertEquals(RunStatus.MERGED, s.runs.get(run.id)!!.status)
        finished(next)
    }

    @Test
    fun `a failed read changes nothing and the throttle skips a second read`() {
        script()
        val run = finished(start())
        gh.mergeOnGitHub(run.prNumber!!)
        gh.getPullFailures = 1
        assertEquals(Orchestrator.Outside.NONE, runBlocking { s.orchestrator.checkOutside(run.id) })
        assertEquals(RunStatus.NEEDS_HUMAN, s.runs.get(run.id)!!.status)

        s.orchestrator.outsideCheckMillis = 60_000
        val reads = gh.pullReads.get()
        assertEquals(Orchestrator.Outside.NONE, runBlocking { s.orchestrator.checkOutside(run.id) })
        assertEquals(reads, gh.pullReads.get(), "throttled")
        assertEquals(RunStatus.NEEDS_HUMAN, get(run).status)
        // Before an action the check is forced.
        assertEquals("pull_request_merged", code { s.orchestrator.approve(alice, run.id) }.code)
    }

    @Test
    fun `a release run merged on GitHub settles its release issue as merged`() {
        script()
        val n = gh.addIssue("demo-app", "Release issue", "body").number
        val r = runBlocking {
            val rel = s.releases.create(alice, "demo-app", "o1", "")
            s.releases.assign(alice, "demo-app", listOf(n), rel.id)
            s.releases.start(alice, rel.id)
            rel
        }
        val run = runBlocking {
            var found: RunRecord? = null
            withTimeoutOrNull(10_000) {
                while (found == null) { found = s.runs.list("demo-app", false).firstOrNull { it.releaseId == r.id }; delay(20) }
            }
            found!!
        }
        val waiting = finished(run)
        assertEquals(RunStatus.NEEDS_HUMAN, waiting.status)
        gh.mergeOnGitHub(waiting.prNumber!!)
        assertEquals(RunStatus.MERGED, get(run).status)
        val state = runBlocking {
            withTimeoutOrNull(10_000) {
                while (s.releaseStore.get(r.id)!!.issues.single().state != ReleaseIssueState.MERGED) delay(20)
            }
            s.releaseStore.get(r.id)!!.issues.single().state
        }
        assertEquals(ReleaseIssueState.MERGED, state)
    }
}
