package dk.egernet.factory

import dk.egernet.factory.agent.FeedItem
import dk.egernet.factory.agent.UsageLimitDetector
import dk.egernet.factory.db.ReleaseRecord
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.pipeline.ReleaseBuilder
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Starting and building releases (docs/ARCHITECTURE.md §6 "Releases"). */
class ReleaseBuildTest {
    private val world = TestWorld(twoFactor = false)
    private val s = world.services
    private val alice = world.user("alice")
    private val extraServices = CopyOnWriteArrayList<Services>()

    @AfterTest fun tearDown() {
        // Let hanging fake agents go, then stop everything.
        gates.keys.forEach { gates[it] = true }
        extraServices.forEach { it.stop() }
        world.close()
        // Wait until no coroutine of the stopped services still uses the database, then give the pool's connections
        // back to PostgreSQL: every test has its own database.
        runBlocking {
            for (svc in extraServices + s) withTimeoutOrNull(10_000) { svc.scope.coroutineContext[kotlinx.coroutines.Job]!!.join() }
        }
        (s.db.dataSource as java.io.Closeable).close()
    }

    // ------------------------------------------------------------ scripted agents, per issue title

    /** How the builder (and approver) behave for an issue, by title. */
    private val behaviour = ConcurrentHashMap<String, String>()
    /** `gate` issues: the builder waits until its gate opens. */
    private val gates = ConcurrentHashMap<String, Boolean>()

    private fun review() = buildJsonObject { put("verdict", "PASS"); put("summary", "fine"); put("findings", buildJsonArray { }) }

    init {
        world.launcher.stage = { p ->
            val title = Regex("Issue #\\d+: (.+)").find(p.spec.prompt ?: "")?.groupValues?.get(1)?.trim() ?: ""
            val b = behaviour[title] ?: "ok"
            val role = p.spec.kind
            when {
                role == "builder" && b == "fail" -> { p.init(); p.exit(1) }
                role == "builder" && b == "hang" -> { p.init(); while (!p.killed) Thread.sleep(10) }
                role == "builder" && b == "gate" -> {
                    p.init()
                    while (!p.killed && gates[title] != true) Thread.sleep(10)
                    if (!p.killed) { p.result("Built it."); p.exit(0) }
                }
                role == "builder" && b == "usage" -> {
                    behaviour[title] = "ok" // only the first time
                    p.init()
                    p.emit(buildJsonObject {
                        put("type", "rate_limit_event")
                        putJsonObject("rate_limit_info") { put("status", "rejected"); put("resetsAt", resetAt.epochSecond); put("rateLimitType", "five_hour") }
                    })
                    p.result("You've hit your limit · resets 3pm", isError = true)
                    p.exit(1)
                }
                role == "builder" && b == "auth" -> { p.init(); p.result("Invalid API key · Please run /login", isError = true); p.exit(1) }
                role == "spec-writer" -> { p.init(); p.result("## Spec\nDo the thing."); p.exit(0) }
                role == "builder" -> { p.init(); p.result("Built it."); p.exit(0) }
                role == "approver" -> {
                    val v = if (b == "escalate") "ESCALATE" else "APPROVE"
                    p.init(); p.result(null, buildJsonObject { put("verdict", v); put("summary", "approver says $v") }); p.exit(0)
                }
                else -> { p.init(); p.result(null, review()); p.exit(0) }
            }
        }
    }

    private val resetAt: Instant = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)

    /** A new open issue in [repo] whose agents behave as [b]. */
    private fun issue(title: String, b: String = "ok", repo: String = "demo-app"): Int {
        behaviour[title] = b
        return world.github.addIssue(repo, title, "body").number
    }

    private fun release(name: String, issues: List<Int>, repo: String = "demo-app", by: UserRecord = alice): ReleaseRecord = runBlocking {
        val r = s.releases.create(by, repo, name, "")
        if (issues.isNotEmpty()) s.releases.assign(by, repo, issues, r.id)
        s.releaseStore.get(r.id)!!
    }

    private fun start(id: UUID, by: UserRecord = alice) = runBlocking { s.releases.start(by, id) }

    private fun awaitRelease(id: UUID, what: String = "condition", timeoutMs: Long = 15_000, services: Services = s, cond: (ReleaseRecord) -> Boolean): ReleaseRecord =
        runBlocking {
            withTimeout(timeoutMs) {
                while (true) {
                    val r = services.releaseStore.get(id)!!
                    if (cond(r)) return@withTimeout r
                    delay(20)
                }
                @Suppress("UNREACHABLE_CODE") error(what)
            }
        }

    private fun ReleaseRecord.state(n: Int) = issues.single { it.number == n }.state
    private fun ReleaseRecord.issue(n: Int) = issues.single { it.number == n }

    private fun awaitStates(id: UUID, vararg states: Pair<Int, String>, services: Services = s) =
        awaitRelease(id, "states ${states.toList()}", services = services) { r -> states.all { (n, st) -> r.state(n) == st } }

    private fun awaitBuilder(title: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (world.launcher.byRole("builder").none { (it.spec.prompt ?: "").contains(title) && !it.killed }) {
            check(System.currentTimeMillis() < deadline) { "no builder for $title" }
            Thread.sleep(10)
        }
    }

    private fun code(block: () -> Unit): String = assertFailsWith<ApiException> { block() }.code

    // ------------------------------------------------------------ start

    @Test
    fun `start makes the caller the owner, creates the branch, and refuses in the documented cases`() {
        val empty = release("empty", emptyList())
        assertEquals("release_empty", code { start(empty.id) })

        val a = issue("First", "gate")
        val r = release("1.4", listOf(a))
        val carol = world.user("carol", claude = false)
        assertEquals("claude_credential_required", code { start(r.id, carol) })
        // Without a GitHub token nothing is visible: 404 comes first.
        val dan = world.user("dan", github = false)
        assertEquals(404, assertFailsWith<ApiException> { start(r.id, dan) }.status.value)

        val started = start(r.id)
        assertEquals(ReleaseStatus.BUILDING, started.status)
        assertEquals(alice.id, started.ownerId)
        assertEquals("release/1.4", started.branch)
        assertNotNull(started.startedAt)
        assertTrue("branch demo-app release/1.4" in world.github.actions, world.github.actions.toString())
        assertEquals("release_not_planning", code { start(r.id) })

        // One building release per project; another project is free.
        val other = release("1.5", listOf(issue("Second")))
        val busy = assertFailsWith<ApiException> { start(other.id) }
        assertEquals("release_already_building", busy.code)
        assertTrue(busy.message.contains("1.4"), busy.message)
        val lab = release("lab", listOf(1), repo = "private-lab")
        assertEquals(ReleaseStatus.BUILDING, start(lab.id).status)
        gates["First"] = true
        awaitStates(r.id, a to "merged")
        runBlocking { s.releases.cancel(alice, r.id); s.releases.cancel(alice, lab.id) }

        // A refused branch rolls the claim back.
        world.github.failCreateBranch = true
        assertEquals("github_push_denied", code { start(other.id) })
        val back = s.releaseStore.get(other.id)!!
        assertEquals(ReleaseStatus.PLANNING, back.status)
        assertNull(back.ownerId)
        assertNull(back.branch)
        assertNull(back.startedAt)
        world.github.failCreateBranch = false
        world.github.branches.getOrPut("demo-app") { mutableSetOf() } += "release/1.5"
        assertEquals("release_branch_exists", code { start(other.id) })
        assertEquals(ReleaseStatus.PLANNING, s.releaseStore.get(other.id)!!.status)
        assertNull(s.releaseStore.get(other.id)!!.ownerId)
    }

    // ------------------------------------------------------------ building

    @Test
    fun `issues are built one at a time on the release branch and stay open`() {
        val nums = listOf(issue("Alpha"), issue("Beta"), issue("Gamma"))
        val r = release("2.0", nums)
        start(r.id)
        val done = awaitStates(r.id, *nums.map { it to "merged" }.toTypedArray())
        val runs = done.issues.map { s.runs.get(it.runId!!)!! }
        assertEquals(nums, runs.map { it.issueNumber })
        for (run in runs) {
            assertEquals(RunStatus.MERGED, run.status)
            assertEquals(r.id, run.releaseId)
            assertEquals("release/2.0", run.baseBranch)
            assertTrue(run.branch!!.startsWith("factory/release-2.0/issue-${run.issueNumber}-"), run.branch)
            assertEquals(r.id.toString(), s.runs.toDto(run).releaseId)
            assertEquals("release/2.0", s.runs.toDto(run).baseBranch)
            assertTrue(world.github.pullBodies[run.prNumber!!]!!.startsWith("Issue #${run.issueNumber} · release `2.0`"))
            assertFalse(world.github.pullBodies[run.prNumber!!]!!.contains("Closes"))
        }
        // Never two runs of the release at once: each starts after the previous one ended.
        runs.zipWithNext().forEach { (a, b) -> assertTrue(!b.createdAt.isBefore(a.endedAt!!), "$a / $b") }
        assertEquals(runs.map { "${it.branch} <- release/2.0" }, world.git.worktrees.toList())
        val actions = world.github.actions.toList()
        for (run in runs) assertTrue("pr demo-app ${run.branch} -> release/2.0 #${run.prNumber}" in actions, actions.toString())
        assertEquals(3, actions.count { it.startsWith("merge demo-app #") })
        assertTrue(actions.none { it.startsWith("close ") }, actions.toString())
        assertTrue(actions.any { it.startsWith("comment demo-app#${nums[0]} **Agern: merged** pull request #${runs[0].prNumber} into `release/2.0`") }, actions.toString())
        nums.forEach { assertEquals("open", world.github.client("fake-alice").getIssue("test-org", "demo-app", it)!!.state) }
        // The builder is told where its work goes.
        assertTrue(world.launcher.byRole("approver").all { (it.spec.prompt ?: "").contains("squash-merged into `release/2.0`") })
        assertTrue(world.launcher.byRole("builder").all { (it.spec.prompt ?: "").contains("release branch `release/2.0`") })

        // Nothing left to build: ready to release (step 3); a single run in the same world targets main and closes its issue.
        awaitRelease(r.id, "ready") { it.status == ReleaseStatus.READY }
        val single = issue("Single")
        val run = runBlocking { s.orchestrator.start(alice, "demo-app", single, null) }
        val merged = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, merged.status, merged.error)
        assertNull(merged.releaseId)
        assertNull(merged.baseBranch)
        assertTrue(merged.branch!!.startsWith("factory/issue-$single-"))
        assertTrue(world.github.actions.any { it.startsWith("pr demo-app ${merged.branch} -> main") })
        assertTrue(world.github.pullBodies[merged.prNumber!!]!!.startsWith("Closes #$single"))
        assertTrue("close demo-app#$single" in world.github.actions)
        assertEquals("${merged.branch} <- main", world.git.worktrees.last())

        // Adding an issue to a ready release builds again.
        val late = issue("Late")
        runBlocking { s.releases.assign(alice, "demo-app", listOf(late), r.id) }
        awaitStates(r.id, late to "merged")
    }

    @Test
    fun `closed issues and issues with an active single run are skipped`() {
        val ok = issue("Fine")
        val closed = issue("Closed one")
        val busy = issue("Busy", "hang")
        val r = release("skip", listOf(closed, busy, ok))
        world.github.client("fake-alice").closeIssue("test-org", "demo-app", closed)
        val single = runBlocking { s.orchestrator.start(alice, "demo-app", busy, null) }
        start(r.id)
        val done = awaitStates(r.id, ok to "merged")
        assertEquals("skipped", done.state(closed))
        assertEquals("Closed on GitHub", done.issue(closed).note)
        assertEquals("skipped", done.state(busy))
        assertEquals("Has an active run outside the release", done.issue(busy).note)
        runBlocking { s.orchestrator.cancel(alice, single.id) }
    }

    @Test
    fun `failed and escalated issues are held, approve, retry and take out`() {
        val broken = issue("Broken", "fail")
        val doubtful = issue("Doubtful", "escalate")
        val fine = issue("Fine")
        val r = release("held", listOf(broken, doubtful, fine))
        start(r.id)
        val after = awaitStates(r.id, broken to "held", doubtful to "held", fine to "merged")
        assertTrue(after.issue(broken).note!!.contains("builder failed"), after.issue(broken).note)
        assertTrue(after.issue(doubtful).note!!.contains("approver escalated"), after.issue(doubtful).note)
        assertEquals(RunStatus.NEEDS_HUMAN, s.runs.get(after.issue(doubtful).runId!!)!!.status)

        // Approving the held run merges it into the release.
        runBlocking { s.orchestrator.approve(alice, after.issue(doubtful).runId!!) }
        awaitStates(r.id, doubtful to "merged")

        // Retry builds it again.
        behaviour["Broken"] = "ok"
        val retried = runBlocking { s.releases.retry(alice, r.id, broken) }
        assertTrue(retried.state(broken) in setOf("waiting", "running"))
        assertEquals("release_issue_not_held", code { runBlocking { s.releases.retry(alice, r.id, fine) } })
        awaitStates(r.id, broken to "merged")

        // Taking out a held issue cancels its run.
        val another = issue("Another", "escalate")
        runBlocking { s.releases.assign(alice, "demo-app", listOf(another), r.id) }
        val held = awaitStates(r.id, another to "held")
        val heldRun = held.issue(another).runId!!
        runBlocking { s.releases.assign(alice, "demo-app", listOf(another), null) }
        assertEquals(RunStatus.CANCELLED, world.awaitRun(heldRun, setOf(RunStatus.CANCELLED)).status)
        assertTrue(s.releaseStore.get(r.id)!!.issues.none { it.number == another })
    }

    @Test
    fun `cancelling the running run holds its issue and the build continues`() {
        val stuck = issue("Stuck", "hang")
        val next = issue("Next")
        val r = release("cr", listOf(stuck, next))
        start(r.id)
        val running = awaitStates(r.id, stuck to "running")
        awaitBuilder("Stuck")
        runBlocking { s.orchestrator.cancel(alice, running.issue(stuck).runId!!) }
        val done = awaitStates(r.id, stuck to "held", next to "merged")
        assertEquals("Cancelled by alice", done.issue(stuck).note)
    }

    @Test
    fun `pause lets the running issue finish and starts no other, resume continues`() {
        val first = issue("Slow first", "gate")
        val second = issue("Second")
        val r = release("p", listOf(first, second))
        start(r.id)
        awaitStates(r.id, first to "running")
        awaitBuilder("Slow first")
        val paused = runBlocking { s.releases.pause(alice, r.id) }
        assertEquals(ReleaseStatus.PAUSED, paused.status)
        assertEquals("manual", paused.pauseReason)
        assertEquals("Paused by alice", paused.pauseNote)
        assertEquals("release_not_building", code { runBlocking { s.releases.pause(alice, r.id) } })
        gates["Slow first"] = true
        awaitStates(r.id, first to "merged")
        Thread.sleep(300)
        val still = s.releaseStore.get(r.id)!!
        assertEquals(ReleaseStatus.PAUSED, still.status)
        assertEquals("waiting", still.state(second))
        assertNull(s.runs.activeRunFor("demo-app", second))
        assertTrue(s.runs.list("demo-app", false).none { it.issueNumber == second })
        val resumed = runBlocking { s.releases.resume(alice, r.id) }
        assertNull(resumed.pauseReason)
        assertEquals("release_not_paused", code { runBlocking { s.releases.resume(alice, r.id) } })
        awaitStates(r.id, second to "merged")
    }

    @Test
    fun `cancel stops the running run, leaves waiting issues and frees the project`() {
        val stuck = issue("Stuck", "hang")
        val waiting = issue("Waiting")
        val r = release("c", listOf(stuck, waiting))
        start(r.id)
        val running = awaitStates(r.id, stuck to "running")
        awaitBuilder("Stuck")
        val cancelled = runBlocking { s.releases.cancel(alice, r.id) }
        assertEquals(ReleaseStatus.CANCELLED, cancelled.status)
        assertNotNull(cancelled.endedAt)
        assertEquals(RunStatus.CANCELLED, s.runs.get(running.issue(stuck).runId!!)!!.status)
        Thread.sleep(300)
        val after = s.releaseStore.get(r.id)!!
        assertEquals("held", after.state(stuck))
        assertEquals("Cancelled with the release", after.issue(stuck).note)
        assertEquals("waiting", after.state(waiting))
        assertTrue(s.runs.list("demo-app", false).none { it.issueNumber == waiting })
        assertEquals("release_not_building", code { runBlocking { s.releases.cancel(alice, r.id) } })
        val other = release("next", listOf(issue("Other")))
        assertEquals(ReleaseStatus.BUILDING, start(other.id).status)
    }

    // ------------------------------------------------------------ usage limit and credentials

    @Test
    fun `a usage limit pauses the release until resumeAt`() {
        val limited = issue("Limited", "usage")
        val r = release("u", listOf(limited))
        start(r.id)
        val paused = awaitRelease(r.id, "paused") { it.status == ReleaseStatus.PAUSED }
        assertEquals("usage_limit", paused.pauseReason)
        assertEquals(resetAt.plus(1, ChronoUnit.MINUTES), paused.resumeAt)
        assertTrue(paused.pauseNote!!.contains("five hour") && paused.pauseNote!!.contains("resets at"), paused.pauseNote)
        assertEquals("waiting", paused.state(limited))
        assertEquals(ReleaseBuilder.USAGE_LIMIT_ISSUE_NOTE, paused.issue(limited).note)
        assertEquals(RunStatus.FAILED, s.runs.get(paused.issue(limited).runId!!)!!.status)
        val dto = paused.toDto()
        assertEquals("usage_limit", dto.pause!!.reason)
        assertNotNull(dto.pause!!.resumeAt)

        runBlocking { s.releaseBuilder.resumeDue(Instant.now()) }
        assertEquals(ReleaseStatus.PAUSED, s.releaseStore.get(r.id)!!.status, "not due yet")
        runBlocking { s.releaseBuilder.resumeDue(paused.resumeAt!!.plusSeconds(1)) }
        assertEquals(ReleaseStatus.BUILDING, s.releaseStore.get(r.id)!!.status)
        awaitStates(r.id, limited to "merged")
    }

    @Test
    fun `usage limit pause note and resume time`() {
        val now = Instant.parse("2026-11-19T12:00:00Z")
        val (note, at) = ReleaseBuilder.usageLimitPause(dk.egernet.factory.agent.UsageLimit(Instant.parse("2026-11-19T15:00:00Z"), "five_hour"), now)
        assertEquals("The owner's Claude usage limit (five hour) was reached; it resets at 2026-11-19 15:00 UTC.", note)
        assertEquals(Instant.parse("2026-11-19T15:01:00Z"), at)
        val (n2, at2) = ReleaseBuilder.usageLimitPause(dk.egernet.factory.agent.UsageLimit(Instant.parse("2026-11-19T11:00:00Z"), null), now)
        assertTrue(n2.contains("unknown"), n2)
        assertEquals(now.plus(30, ChronoUnit.MINUTES), at2)
    }

    @Test
    fun `usage limit detection`() {
        fun o(json: String) = AppJson.parseToJsonElement(json).jsonObject
        val failing = o("""{"type":"result","subtype":"error_during_execution","is_error":true,"result":"boom"}""")
        // The three signals.
        val event = UsageLimitDetector.detect(
            listOf(o("""{"type":"rate_limit_event","rate_limit_info":{"status":"rejected","resetsAt":1795000000,"rateLimitType":"five_hour"}}"""), failing), failed = true,
        )
        assertEquals(dk.egernet.factory.agent.UsageLimit(Instant.ofEpochSecond(1795000000), "five_hour"), event)
        assertNotNull(UsageLimitDetector.detect(listOf(o("""{"type":"assistant","error":"rate_limit","message":{}}"""), failing), failed = true))
        assertNotNull(UsageLimitDetector.detect(listOf(o("""{"type":"result","is_error":true,"result":"Claude AI usage limit reached|1795000000"}""")), failed = true))
        assertNotNull(UsageLimitDetector.detect(listOf(o("""{"type":"result","is_error":true,"result":"You've hit your limit · resets 3pm"}""")), failed = true))
        // Not a limit: an allowed event, a successful stage, unrelated failures.
        assertNull(UsageLimitDetector.detect(listOf(o("""{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","resetsAt":1795000000}}"""), failing), failed = true))
        assertNull(UsageLimitDetector.detect(listOf(o("""{"type":"rate_limit_event","rate_limit_info":{"status":"rejected"}}""")), failed = false))
        assertNull(UsageLimitDetector.detect(listOf(failing), failed = true))
        assertNull(UsageLimitDetector.detect(listOf(o("""{"type":"result","is_error":false,"result":"I hit your limit"}""")), failed = true))
        // resetsAt shapes: seconds, milliseconds, ISO; anything else unknown.
        fun reset(v: String) = UsageLimitDetector.parseResetsAt(AppJson.parseToJsonElement(v))
        assertEquals(Instant.ofEpochSecond(1795000000), reset("1795000000"))
        assertEquals(Instant.ofEpochMilli(1795000000123), reset("1795000000123"))
        assertEquals(Instant.parse("2026-11-19T15:00:00Z"), reset("\"2026-11-19T15:00:00Z\""))
        assertEquals(Instant.ofEpochSecond(1795000000), reset("\"1795000000\""))
        for (bad in listOf("\"soon\"", "null", "{}", "[1]", "-5", "true")) assertNull(reset(bad), bad)
        // Malformed input never throws and yields an unknown reset time.
        val odd = UsageLimitDetector.detect(
            listOf(
                o("""{"type":"rate_limit_event","rate_limit_info":"nope"}"""),
                o("""{"type":"rate_limit_event","rate_limit_info":{"status":["rejected"],"resetsAt":{"x":1}}}"""),
                o("""{"type":["x"]}"""),
                o("""{"type":"rate_limit_event","rate_limit_info":{"status":"rejected","resetsAt":"later","rateLimitType":7}}"""),
            ),
            failed = true,
        )
        assertEquals(dk.egernet.factory.agent.UsageLimit(null, null), odd)
    }

    @Test
    fun `a rejected credential pauses the release`() {
        val bad = issue("Bad key", "auth")
        val r = release("k", listOf(bad))
        start(r.id)
        val paused = awaitRelease(r.id, "paused") { it.status == ReleaseStatus.PAUSED }
        assertEquals("credentials", paused.pauseReason)
        assertEquals(ReleaseBuilder.CREDENTIALS_NOTE, paused.pauseNote)
        assertNull(paused.resumeAt)
        assertEquals("waiting", paused.state(bad))
    }

    // ------------------------------------------------------------ restart

    @Test
    fun `after a restart a building release rebuilds the interrupted issue and a paused one stays paused`() {
        val stuck = issue("Interrupted", "hang")
        val next = issue("After restart")
        val r = release("rs", listOf(stuck, next))
        start(r.id)
        val running = awaitStates(r.id, stuck to "running")
        awaitBuilder("Interrupted")
        val firstRun = running.issue(stuck).runId!!

        val labIssue = world.github.addIssue("private-lab", "Lab work", "body").number
        behaviour["Lab work"] = "gate"
        val lab = release("lab", listOf(labIssue), repo = "private-lab")
        start(lab.id)
        awaitStates(lab.id, labIssue to "running")
        awaitBuilder("Lab work")
        runBlocking { s.releases.pause(alice, lab.id) }

        s.stop()
        behaviour["Interrupted"] = "ok"
        val s2 = Services(world.cfg, s.db, world.github, world.git, world.launcher)
        extraServices += s2
        s2.start()
        assertEquals(RunStatus.FAILED, s2.runs.get(firstRun)!!.status)
        val done = awaitStates(r.id, stuck to "merged", next to "merged", services = s2)
        assertTrue(done.issue(stuck).runId != firstRun)
        Thread.sleep(300)
        val labAfter = s2.releaseStore.get(lab.id)!!
        assertEquals(ReleaseStatus.PAUSED, labAfter.status)
        assertEquals("waiting", labAfter.state(labIssue))
        assertEquals(ReleaseBuilder.RESTART_NOTE, labAfter.issue(labIssue).note)
        assertTrue(s2.runs.list("private-lab", activeOnly = true).isEmpty())
    }

    // ------------------------------------------------------------ feed

    @Test
    fun `every state change is announced on the feed`() = runBlocking {
        val frames = CopyOnWriteArrayList<ReleaseDto>()
        val collector = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            s.bus.feed.collect { if (it is FeedItem.Release) frames += it.release }
        }
        val a = issue("Feed one")
        val r = release("f", listOf(a))
        s.releases.start(alice, r.id)
        awaitStates(r.id, a to "merged")
        withTimeout(5_000) { while (frames.none { f -> f.issues.any { it.state == "merged" } }) delay(10) }
        collector.cancelAndJoin()
        val mine = frames.filter { it.id == r.id.toString() }
        assertTrue(mine.any { it.status == "building" && it.owner?.username == "alice" && it.branch == "release/f" })
        assertTrue(mine.any { f -> f.issues.singleOrNull()?.let { it.state == "running" && it.runId != null } == true })
        assertTrue(mine.last().issues.single().state == "merged")
        assertEquals(mine.map { it.updatedAt }.sorted(), mine.map { it.updatedAt })
    }

    // ------------------------------------------------------------ HTTP: permissions and editing rules

    private fun json(r: String) = AppJson.parseToJsonElement(r)
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun errorCode(r: HttpResponse) = runBlocking { json(r.bodyAsText()).jsonObject["error"]!!.jsonObject.str("code") }

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String): HttpClient {
        val c = createClient { install(HttpCookies) }
        assertEquals(200, c.post("/api/auth/login") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
        }.status.value)
        return c
    }

    private suspend fun HttpClient.postJson(path: String, body: String = "{}", csrf: Boolean = true): HttpResponse =
        post(path) { if (csrf) header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.putJson(path: String, body: String): HttpResponse =
        put(path) { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.del(path: String): HttpResponse = delete(path) { header("X-Factory-Request", "1") }

    private suspend fun HttpClient.assign(numbers: List<Int>, releaseId: String?, repo: String = "demo-app"): HttpResponse =
        postJson("/api/projects/$repo/releases/assign", """{"issueNumbers":$numbers,"releaseId":${releaseId?.let { "\"$it\"" } ?: "null"}}""")

    @Test
    fun `permissions of a started release`() = testApplication {
        application { agernModule(s) }
        world.user("bob")
        world.user("root", admin = true)
        val a = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        val root = login("root", "pw-r")
        val stuck = issue("Long", "hang")
        val second = issue("Second")
        val third = issue("Third")
        val r = release("perm", listOf(stuck, second))
        val id = r.id.toString()

        assertEquals(403, a.postJson("/api/releases/$id/start", csrf = false).status.value)
        val started = a.postJson("/api/releases/$id/start")
        assertEquals(200, started.status.value, started.bodyAsText())
        val body = json(started.bodyAsText()).jsonObject
        assertEquals("building", body.str("status"))
        assertEquals("alice", body["owner"]!!.jsonObject.str("username"))
        assertEquals("release/perm", body.str("branch"))
        awaitStates(r.id, stuck to "running")

        // Bob may change nothing.
        for (path in listOf("pause", "resume", "cancel", "issues/$second/retry")) {
            val res = bob.postJson("/api/releases/$id/$path")
            assertEquals(403, res.status.value, path)
            assertEquals("forbidden", errorCode(res))
        }
        assertTrue(bob.postJson("/api/releases/$id/pause").bodyAsText().contains("Only alice, who started this release, or an admin can do this"))
        assertTrue(bob.postJson("/api/releases/$id/resume").bodyAsText().contains("Only alice, who started this release, can do this"))
        assertEquals(403, bob.assign(listOf(third), id).status.value)
        assertEquals(403, bob.assign(listOf(second), null).status.value)
        assertEquals(403, bob.putJson("/api/releases/$id/issues/order", """{"issueNumbers":[$stuck,$second]}""").status.value)
        assertEquals(403, bob.putJson("/api/releases/$id", """{"name":"perm","description":"x"}""").status.value)

        // Admins may stop, never drive.
        assertEquals(403, root.assign(listOf(third), id).status.value)
        assertEquals(403, root.putJson("/api/releases/$id/issues/order", """{"issueNumbers":[$stuck,$second]}""").status.value)
        assertEquals(403, root.postJson("/api/releases/$id/issues/$second/retry").status.value)
        assertEquals(200, root.putJson("/api/releases/$id", """{"name":"perm","description":"by root"}""").status.value)
        assertEquals("paused", json(root.postJson("/api/releases/$id/pause").bodyAsText()).jsonObject.str("status"))
        assertEquals(403, root.postJson("/api/releases/$id/resume").status.value)
        assertEquals("building", json(a.postJson("/api/releases/$id/resume").bodyAsText()).jsonObject.str("status"))
        assertEquals(200, root.assign(listOf(second), null).status.value)
        assertEquals(listOf(stuck), s.releaseStore.get(r.id)!!.issues.map { it.number })
        assertEquals("release_active", errorCode(root.del("/api/releases/$id")))
        assertEquals("cancelled", json(root.postJson("/api/releases/$id/cancel").bodyAsText()).jsonObject.str("status"))
        assertEquals(403, bob.del("/api/releases/$id").status.value)
        assertEquals(204, root.del("/api/releases/$id").status.value)
        // The runs stay, without their release.
        val runs = s.runs.list("demo-app", false).filter { it.issueNumber == stuck }
        assertTrue(runs.isNotEmpty() && runs.all { it.releaseId == null && it.baseBranch == "release/perm" })

        // A release in a project bob cannot see: 404 before anything else.
        val lab = release("lab", listOf(1), repo = "private-lab")
        val labId = lab.id.toString()
        for (path in listOf("start", "pause", "resume", "cancel", "issues/1/retry")) {
            assertEquals(404, bob.postJson("/api/releases/$labId/$path").status.value, path)
        }
        assertEquals(400, a.postJson("/api/releases/$labId/issues/0/retry").status.value)
        assertEquals(404, a.postJson("/api/releases/not-a-uuid/start").status.value)
    }

    @Test
    fun `editing rules of started releases`() = testApplication {
        application { agernModule(s) }
        val a = login("alice", "pw-a")
        val stuck = issue("Running one", "hang")
        val w1 = issue("Waiting one")
        val w2 = issue("Waiting two")
        val r = release("edit", listOf(stuck, w1, w2))
        val id = r.id.toString()
        assertEquals(200, a.postJson("/api/releases/$id/start").status.value)
        awaitStates(r.id, stuck to "running")

        val rename = a.putJson("/api/releases/$id", """{"name":"other","description":""}""")
        assertEquals(409, rename.status.value)
        assertEquals("release_name_locked", errorCode(rename))
        assertEquals("Now with words", json(a.putJson("/api/releases/$id", """{"name":"edit","description":"Now with words"}""").bodyAsText()).jsonObject.str("description"))
        assertEquals("release_active", errorCode(a.del("/api/releases/$id")))

        assertEquals("release_issue_started", errorCode(a.assign(listOf(stuck), null)))
        assertEquals("release_issue_started", errorCode(a.putJson("/api/releases/$id/issues/order", """{"issueNumbers":[$w1,$stuck,$w2]}""")))
        assertEquals(200, a.putJson("/api/releases/$id/issues/order", """{"issueNumbers":[$stuck,$w2,$w1]}""").status.value)
        assertEquals(listOf(stuck, w2, w1), s.releaseStore.get(r.id)!!.issues.map { it.number })
        // Moving a running issue into another release is refused as a whole.
        val planned = release("planned", emptyList())
        val moved = a.assign(listOf(w1, stuck), planned.id.toString())
        assertEquals("release_issue_started", errorCode(moved))
        assertTrue(s.releaseStore.get(planned.id)!!.issues.isEmpty())
        assertEquals(3, s.releaseStore.get(r.id)!!.issues.size)
        // A waiting one may leave; new issues are appended as waiting.
        assertEquals(200, a.assign(listOf(w1), planned.id.toString()).status.value)
        val fresh = issue("Fresh")
        assertEquals(200, a.assign(listOf(fresh), id).status.value)
        val now = s.releaseStore.get(r.id)!!
        assertEquals(listOf(stuck, w2, fresh), now.issues.map { it.number })
        assertEquals("waiting", now.state(fresh))

        assertEquals(200, a.postJson("/api/releases/$id/cancel").status.value)
        assertEquals("release_closed", errorCode(a.assign(listOf(w1), id)))
        assertEquals("release_closed", errorCode(a.putJson("/api/releases/$id/issues/order", """{"issueNumbers":[$stuck,$w2,$fresh]}""")))
        assertEquals("release_not_building", errorCode(a.postJson("/api/releases/$id/pause")))
        assertEquals("release_not_planning", errorCode(a.postJson("/api/releases/$id/start")))
        // From a cancelled release any issue may leave.
        assertEquals(200, a.assign(listOf(stuck), null).status.value)
        assertEquals(204, a.del("/api/releases/$id").status.value)
    }
}
