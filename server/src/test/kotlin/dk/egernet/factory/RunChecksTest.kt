package dk.egernet.factory

import dk.egernet.factory.db.ReleaseRecord
import dk.egernet.factory.db.RunRecord
import dk.egernet.factory.mcp.McpGrant
import dk.egernet.factory.mcp.McpRegistry
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Checks before a run is merged (docs/ARCHITECTURE.md §6 "Checks before a merge"). */
class RunChecksTest {
    private val clock = MutableClock()
    private val world = TestWorld(clock = clock, twoFactor = false)
    private val s = world.services
    private val gh = world.github
    private val alice = world.user("alice")
    private val extraServices = CopyOnWriteArrayList<Services>()

    @AfterTest fun tearDown() {
        extraServices.forEach { it.stop() }
        runBlocking {
            for (svc in extraServices) withTimeoutOrNull(10_000) { svc.scope.coroutineContext[kotlinx.coroutines.Job]!!.join() }
        }
        world.close()
    }

    /** Called on the builder's thread with the run's branch, before anything is pushed. */
    @Volatile private var onBuild: (String) -> Unit = {}
    /** Called on the approver's thread before it answers. */
    @Volatile private var beforeApproval: () -> Unit = {}
    @Volatile private var approverVerdict = "APPROVE"

    init {
        s.orchestrator.checksPollMillis = 20
        s.releaseMerger.checksSettleSeconds = 0
        world.launcher.stage = { p ->
            when (p.spec.kind) {
                "spec-writer" -> { p.init(); p.result("## Spec\nDo the thing."); p.exit(0) }
                "builder" -> {
                    Regex("on branch `([^`]+)`").find(p.spec.prompt ?: "")?.groupValues?.get(1)?.let { onBuild(it) }
                    p.init(); p.result("Built it."); p.exit(0)
                }
                "approver" -> {
                    beforeApproval()
                    p.init()
                    p.result(null, buildJsonObject { put("verdict", approverVerdict); put("summary", "approver says $approverVerdict") })
                    p.exit(0)
                }
                else -> {
                    p.init()
                    p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "fine"); put("findings", buildJsonArray { }) })
                    p.exit(0)
                }
            }
        }
    }

    private fun issue(title: String) = gh.addIssue("demo-app", title, "body").number
    private fun start(n: Int) = runBlocking { s.orchestrator.start(alice, "demo-app", n, null) }
    private fun await(id: UUID, vararg statuses: String) = world.awaitRun(id, statuses.toSet())
    private fun ended(id: UUID) = await(id, RunStatus.MERGED, RunStatus.NEEDS_HUMAN, RunStatus.FAILED, RunStatus.CANCELLED)
    private fun headOf(run: RunRecord) = "sha-${run.branch}-${gh.commitsOf("demo-app", run.branch!!).size}"
    private fun comments(n: Int) = gh.client("fake-alice").listComments("test-org", "demo-app", n).map { it.body ?: "" }
    private fun code(block: suspend () -> Unit) = assertFailsWith<ApiException> { runBlocking { block() } }.let { it.status.value to it.code }

    /** A run whose approver approved and that now waits for its checks; [setUp] sets the checks on its branch first. */
    private fun waitingRun(title: String, setUp: (String) -> Unit = { gh.setCheckRun("demo-app", it, "build", "in_progress") }): RunRecord {
        onBuild = setUp
        val run = start(issue(title))
        return await(run.id, RunStatus.WAITING_FOR_CHECKS)
    }

    private fun eventually(what: String, cond: () -> Boolean) = runBlocking {
        withTimeoutOrNull(10_000) { while (!cond()) delay(20) } ?: error("not reached: $what")
    }

    // ------------------------------------------------------------ outcomes

    @Test
    fun `passed checks merge at once, pinned to the evaluated head`() {
        onBuild = { gh.setCheckRun("demo-app", it, "build", "completed", "success") }
        val run = ended(start(issue("Green")).id)
        assertEquals(RunStatus.MERGED, run.status, run.error)
        assertEquals(headOf(run), gh.squashMergeShas[run.prNumber!!])
        val checks = s.runs.toDto(run).checks!!
        assertEquals("passed", checks.state)
        assertEquals(headOf(run), checks.sha)
        assertNull(checks.waitingSince)
        assertTrue(comments(run.issueNumber).any { it.contains("**Agern: merged**") && it.contains("approved by the approver agent") })
    }

    @Test
    fun `pending checks wait and merge by themselves when they pass`() {
        val run = waitingRun("Pending")
        val dto = s.runs.toDto(run)
        assertEquals("pending", dto.checks!!.state)
        assertEquals(listOf(RunCheckItemDto("build", "pending")), dto.checks!!.items)
        assertNotNull(dto.checks!!.waitingSince)
        assertEquals(clock.now.plus(Duration.ofMinutes(60)).toString().take(16), dto.checks!!.deadline!!.take(16))
        assertNotNull(s.orchestrator.job(run.id), "a job waits for the checks")
        assertTrue(gh.actions.none { it.startsWith("merge demo-app") })
        gh.setCheckRun("demo-app", run.branch!!, "build", "completed", "success")
        val done = ended(run.id)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertEquals(headOf(done), gh.squashMergeShas[done.prNumber!!])
        assertNull(s.runs.toDto(done).checks!!.deadline)
    }

    @Test
    fun `the wait ends in needs_human after the time limit`() {
        val run = waitingRun("Slow")
        clock.advance(Duration.ofMinutes(61))
        val done = ended(run.id)
        assertEquals(RunStatus.NEEDS_HUMAN, done.status)
        assertEquals(FailureKind.CHECKS, done.failureKind)
        assertEquals(FailedStep.MERGE, done.failedStep)
        assertTrue(done.error!!.contains("did not finish within 60 minutes"), done.error)
        assertTrue(done.error!!.contains("Approve again to wait again, or merge anyway on the run page."), done.error)
        assertTrue(gh.actions.none { it.startsWith("merge demo-app") })
    }

    @Test
    fun `failed checks need a human, are named on the issue and the pull request, and a later approval merges`() {
        onBuild = {
            gh.setCheckRun("demo-app", it, "build", "completed", "failure", url = "https://github.com/test-org/demo-app/runs/1")
            gh.setStatus("demo-app", it, "ci/lint", "success")
        }
        val run = ended(start(issue("Red")).id)
        assertEquals(RunStatus.NEEDS_HUMAN, run.status)
        assertEquals(FailureKind.CHECKS, run.failureKind)
        assertEquals(FailedStep.MERGE, run.failedStep)
        assertTrue(run.error!!.contains("[build](https://github.com/test-org/demo-app/runs/1) (failure)"), run.error)
        assertTrue(run.error!!.contains("`${headOf(run).take(7)}`"), run.error)
        assertFalse(run.error!!.contains("did not run to completion"))
        assertTrue(comments(run.issueNumber).any { it.startsWith("**Agern: needs a human.**") && it.contains("[build]") })
        assertTrue(comments(run.prNumber!!).any { it.startsWith("**Agern: not merged.**") && it.contains("[build]") })
        assertTrue(gh.actions.none { it.startsWith("merge demo-app") })
        val checks = s.runs.toDto(run).checks!!
        assertEquals("failed", checks.state)
        assertEquals(listOf(RunCheckItemDto("build", "failed", "failure", "https://github.com/test-org/demo-app/runs/1")), checks.items)

        // Approving again evaluates the current head from scratch.
        gh.setCheckRun("demo-app", run.branch!!, "build", "completed", "success")
        val merged = runBlocking { s.orchestrator.approve(alice, run.id) }
        assertEquals(RunStatus.MERGED, merged.status, merged.error)
        assertNull(merged.failureKind)
        assertTrue(comments(run.issueNumber).any { it.contains("**Agern: merged**") && it.contains("(approved by @alice)") })
    }

    @Test
    fun `startup failures and cancellations fail with a re-run hint, an unstarted workflow waits`() {
        onBuild = { gh.setWorkflowRun("demo-app", it, "CI", "completed", "startup_failure", url = "https://github.com/test-org/demo-app/actions/runs/7") }
        val startup = ended(start(issue("Startup")).id)
        assertEquals(RunStatus.NEEDS_HUMAN, startup.status)
        assertTrue(startup.error!!.contains("[CI](https://github.com/test-org/demo-app/actions/runs/7) (startup\\_failure)"), startup.error)
        assertTrue(startup.error!!.contains("Some checks did not run to completion"), startup.error)

        onBuild = { gh.setCheckRun("demo-app", it, "build", "completed", "cancelled") }
        val cancelled = ended(start(issue("Cancelled")).id)
        assertEquals(RunStatus.NEEDS_HUMAN, cancelled.status)
        assertTrue(cancelled.error!!.contains("Re-run them on GitHub, then approve again."), cancelled.error)

        // Queued, not started: no check runs at all, only the workflow run shows it.
        val queued = waitingRun("Queued") { gh.setWorkflowRun("demo-app", it, "CI", "queued") }
        assertEquals("pending", s.runs.toDto(queued).checks!!.state)
        gh.setWorkflowRun("demo-app", queued.branch!!, "CI", "completed", "success")
        assertEquals(RunStatus.MERGED, ended(queued.id).status)
    }

    @Test
    fun `when the head moves while waiting the new head's checks decide`() {
        val red = waitingRun("Moves red")
        val firstHead = headOf(red)
        gh.addCommit("demo-app", red.branch!!, "a human fix")
        gh.setCheckRun("demo-app", red.branch!!, "build", "completed", "failure")
        val blocked = ended(red.id)
        assertEquals(RunStatus.NEEDS_HUMAN, blocked.status)
        assertEquals(headOf(blocked), s.runs.toDto(blocked).checks!!.sha)
        assertTrue(headOf(blocked) != firstHead)

        val green = waitingRun("Moves green")
        gh.addCommit("demo-app", green.branch!!, "another commit")
        gh.setCheckRun("demo-app", green.branch!!, "build", "completed", "success")
        val merged = ended(green.id)
        assertEquals(RunStatus.MERGED, merged.status, merged.error)
        assertEquals("sha-${green.branch}-1", gh.squashMergeShas[merged.prNumber!!])
    }

    @Test
    fun `no checks wait out the settle period, counted from the push`() {
        s.orchestrator.checksSettleSeconds = 60
        val run = waitingRun("No checks") { }
        val dto = s.runs.toDto(run)
        assertEquals("awaiting", dto.checks!!.state)
        assertTrue(dto.checks!!.detail.startsWith("No checks yet"), dto.checks!!.detail)
        clock.advance(Duration.ofSeconds(61))
        val merged = ended(run.id)
        assertEquals(RunStatus.MERGED, merged.status, merged.error)
        assertEquals("none", s.runs.toDto(merged).checks!!.state)

        // Pushed by Agern longer ago than the settle period: merged at the first evaluation.
        onBuild = { branch -> world.git.headSha = "sha-$branch-0" }
        beforeApproval = { clock.advance(Duration.ofSeconds(90)) }
        val pushed = ended(start(issue("Pushed long ago")).id)
        assertEquals(RunStatus.MERGED, pushed.status, pushed.error)
        assertEquals("none", s.runs.toDto(pushed).checks!!.state)
    }

    @Test
    fun `with the settle period at 0 a run without checks merges at once`() {
        val run = ended(start(issue("Zero settle")).id)
        assertEquals(RunStatus.MERGED, run.status, run.error)
        assertEquals("none", s.runs.toDto(run).checks!!.state)
    }

    @Test
    fun `a refused checks read needs a human at once, a transient one waits`() {
        onBuild = { gh.checksFailure = 403 }
        val run = ended(start(issue("Refused")).id)
        assertEquals(RunStatus.NEEDS_HUMAN, run.status)
        assertEquals(FailureKind.CHECKS, run.failureKind)
        assertTrue(run.error!!.contains("Could not read the checks of #${run.prNumber}"), run.error)
        assertTrue(run.error!!.contains("needs read access to commit statuses, checks and Actions"), run.error)

        val transient = waitingRun("Transient") { gh.checksFailure = 502 }
        assertEquals("unknown", s.runs.toDto(transient).checks!!.state)
        gh.checksFailure = null
        assertEquals(RunStatus.MERGED, ended(transient.id).status)
    }

    @Test
    fun `a pull request closed on GitHub while waiting is not merged and cancels the run`() {
        val run = waitingRun("Closed")
        gh.closePull(run.prNumber!!)
        val done = ended(run.id)
        assertEquals(RunStatus.CANCELLED, done.status)
        assertEquals("Pull request #${run.prNumber} was closed on GitHub without being merged.", done.error)
        assertTrue(gh.actions.none { it.startsWith("merge demo-app") })
    }

    @Test
    fun `a pull request merged on GitHub while waiting ends the run merged on GitHub`() {
        val run = waitingRun("Merged outside")
        gh.mergeOnGitHub(run.prNumber!!, by = "carol")
        val done = ended(run.id)
        assertEquals(RunStatus.MERGED, done.status)
        assertTrue(done.mergedOnGitHub)
        assertEquals("carol", done.mergedBy)
        assertTrue(gh.actions.none { it.startsWith("merge demo-app") || it.startsWith("close demo-app") })
    }

    // ------------------------------------------------------------ waiting state

    @Test
    fun `cancel stops the wait`() {
        val run = waitingRun("Cancel me")
        val cancelled = runBlocking { s.orchestrator.cancel(alice, run.id) }
        assertEquals(RunStatus.CANCELLED, cancelled.status)
        assertNull(s.orchestrator.job(run.id))
        gh.setCheckRun("demo-app", run.branch!!, "build", "completed", "success")
        runBlocking { delay(200) }
        assertEquals(RunStatus.CANCELLED, s.runs.get(run.id)!!.status)
        assertTrue(gh.actions.none { it.startsWith("merge demo-app") })
    }

    @Test
    fun `a waiting run is active, refuses the advisor and blocks another run of the issue`() {
        val run = waitingRun("Active")
        assertEquals(409 to "run_not_waiting", code { s.orchestrator.talk(alice, run.id, "hi") })
        assertEquals(409 to "active_run_exists", code { s.orchestrator.start(alice, "demo-app", run.issueNumber, null) })
        assertTrue(s.runs.list("demo-app", activeOnly = true).any { it.id == run.id })
        assertTrue(s.runs.list(null, activeOnly = false, statuses = setOf(RunStatus.WAITING_FOR_CHECKS)).any { it.id == run.id })
        assertEquals(0, s.runs.countInProgress())
    }

    @Test
    fun `the owner's approval waits too, and the replies say so`() {
        approverVerdict = "ESCALATE"
        onBuild = { gh.setCheckRun("demo-app", it, "build", "queued") }
        val run = ended(start(issue("Escalated")).id)
        assertEquals(RunStatus.NEEDS_HUMAN, run.status)
        val chat = s.sessions.create("demo-app", "chat", "opus", "idle", alice.id, null)
        val reply = runBlocking { s.factory.handleCommand(chat, alice, "/factory approve ${run.id}") }
        assertEquals(
            "Run ${run.id} is waiting for checks on `${headOf(run).take(7)}` (Pending: build); it merges by itself when they pass.", reply,
        )
        assertEquals(RunStatus.WAITING_FOR_CHECKS, s.runs.get(run.id)!!.status)
        gh.setCheckRun("demo-app", run.branch!!, "build", "completed", "success")
        val merged = ended(run.id)
        assertEquals(RunStatus.MERGED, merged.status, merged.error)
        assertTrue(comments(run.issueNumber).any { it.contains("**Agern: merged**") && it.contains("(approved by @alice)") })
    }

    @Test
    fun `factory_approve_run has no way to override the checks`() {
        val chat = s.sessions.create("demo-app", "chat", "opus", "idle", alice.id, null)
        val token = s.mcpRegistry.issue(McpGrant(chat.id, alice.id, "demo-app", null, McpRegistry.CHAT_TOOLS))
        val tools = runBlocking { AppJson.parseToJsonElement(s.mcp.handle(token, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").second!!) }
            .jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
        val approve = tools.single { it["name"]!!.jsonPrimitive.content == "factory_approve_run" }
        // `update` starts "Update and merge" (a new review and approval); it never merges past the checks.
        // `closeIssue` only chooses the issue's fate (docs/ARCHITECTURE.md §6 "Partial delivery").
        assertEquals(setOf("runId", "update", "closeIssue"), approve["inputSchema"]!!.jsonObject["properties"]!!.jsonObject.keys)
        assertTrue(approve["description"]!!.jsonPrimitive.content.contains("waits for the checks"))
        assertTrue(tools.none { t -> t["name"]!!.jsonPrimitive.content.let { it.contains("anyway") || it.contains("override") } })
        val help = runBlocking { s.factory.handleCommand(chat, alice, "/factory help") }
        assertFalse(help.contains("anyway"), help)
    }

    @Test
    fun `a waiting run survives a restart and goes on waiting`() {
        val run = waitingRun("Restart")
        val deadline = s.runs.get(run.id)!!.checksDeadline
        s.stop()
        assertEquals(RunStatus.WAITING_FOR_CHECKS, s.runs.get(run.id)!!.status)
        val s2 = Services(world.cfg, s.db, gh, world.git, world.launcher, clock = clock)
        extraServices += s2
        s2.orchestrator.checksPollMillis = 20
        s2.recoverAfterRestart()
        val after = s2.runs.get(run.id)!!
        assertEquals(RunStatus.WAITING_FOR_CHECKS, after.status)
        assertEquals(deadline, after.checksDeadline)
        assertNotNull(s2.orchestrator.job(run.id))
        gh.setCheckRun("demo-app", run.branch!!, "build", "completed", "success")
        val done = runBlocking {
            withTimeoutOrNull(10_000) {
                while (s2.runs.get(run.id)!!.status != RunStatus.MERGED) delay(20)
            }
            s2.runs.get(run.id)!!
        }
        assertEquals(RunStatus.MERGED, done.status, done.error)
    }

    // ------------------------------------------------------------ merge anyway

    @Test
    fun `the owner merges anyway from needs_human, recorded on the run, the pull request and the issue`() {
        world.user("bob")
        val bob = s.users.byUsername("bob")!!
        onBuild = { gh.setCheckRun("demo-app", it, "e2e", "completed", "failure") }
        val run = ended(start(issue("Override")).id)
        assertEquals(RunStatus.NEEDS_HUMAN, run.status)
        val sha = headOf(run)
        assertEquals(403 to "forbidden", code { s.orchestrator.mergeAnyway(bob, run.id, sha) })
        assertEquals(409 to "checks_changed", code { s.orchestrator.mergeAnyway(alice, run.id, "sha-other") })
        val merged = runBlocking { s.orchestrator.mergeAnyway(alice, run.id, sha) }
        assertEquals(RunStatus.MERGED, merged.status, merged.error)
        assertEquals(sha, gh.squashMergeShas[run.prNumber!!])
        val checks = s.runs.toDto(merged).checks!!
        assertEquals("alice", checks.overriddenBy)
        assertNotNull(checks.overriddenAt)
        assertEquals("Failed: e2e", checks.detail)
        assertTrue(comments(run.prNumber!!).any { it == "**Agern:** @alice merged this pull request although its checks had not passed on `${sha.take(7)}` (Failed: e2e)." })
        assertTrue(comments(run.issueNumber).any { it.contains("**Agern: merged**") && it.contains("(approved by @alice, despite checks: Failed: e2e)") })
        // Nothing left to override.
        assertEquals(409 to "run_not_overridable", code { s.orchestrator.mergeAnyway(alice, run.id, sha) })
    }

    @Test
    fun `the owner merges anyway while waiting, and a head that moved since is not merged`() {
        val run = waitingRun("Override waiting")
        val merged = runBlocking { s.orchestrator.mergeAnyway(alice, run.id, headOf(run)) }
        assertEquals(RunStatus.MERGED, merged.status, merged.error)
        assertNull(s.orchestrator.job(run.id))
        assertEquals("alice", s.runs.toDto(merged).checks!!.overriddenBy)

        onBuild = { gh.setCheckRun("demo-app", it, "build", "completed", "failure") }
        val red = ended(start(issue("Override moved")).id)
        val seen = headOf(red)
        gh.addCommit("demo-app", red.branch!!, "pushed after the owner looked")
        val after = runBlocking { s.orchestrator.mergeAnyway(alice, red.id, seen) }
        assertEquals(RunStatus.NEEDS_HUMAN, after.status)
        assertEquals("The pull request's head changed since you confirmed; approve again to evaluate the new commit.", after.error)
        assertNull(gh.squashMergeShas[red.prNumber!!])
    }

    @Test
    fun `merge anyway over HTTP is owner only and bound to the head`() = io.ktor.server.testing.testApplication {
        application { agernModule(s) }
        world.user("bob")
        suspend fun login(user: String, pw: String) = createClient { install(io.ktor.client.plugins.cookies.HttpCookies) }.also { c ->
            assertEquals(200, c.post("/api/auth/login") {
                header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
            }.status.value)
        }
        val a = login("alice", "pw-a")
        val b = login("bob", "pw-b")
        onBuild = { gh.setCheckRun("demo-app", it, "build", "completed", "failure") }
        val run = ended(start(issue("Override HTTP")).id)
        suspend fun post(c: io.ktor.client.HttpClient, sha: String) = c.post("/api/runs/${run.id}/merge-anyway") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"headSha":"$sha"}""")
        }
        assertEquals(403, post(b, headOf(run)).status.value)
        val changed = post(a, "abc")
        assertEquals(409, changed.status.value)
        assertTrue(changed.bodyAsText().contains("checks_changed"))
        val ok = post(a, headOf(run))
        assertEquals(200, ok.status.value, ok.bodyAsText())
        val dto = AppJson.parseToJsonElement(ok.bodyAsText()).jsonObject
        assertEquals("merged", dto["status"]!!.jsonPrimitive.content)
        assertEquals("alice", dto["checks"]!!.jsonObject["overriddenBy"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------ release runs

    private fun release(name: String, issues: List<Int>): ReleaseRecord = runBlocking {
        val r = s.releases.create(alice, "demo-app", name, "")
        s.releases.assign(alice, "demo-app", issues, r.id)
        s.releaseStore.get(r.id)!!
    }

    private fun ReleaseRecord.issue(n: Int) = issues.single { it.number == n }

    @Test
    fun `release runs pass the same gate, their issue stays running while they wait`() {
        val red = issue("Release red")
        val green = issue("Release green")
        onBuild = { branch ->
            if (branch.contains("release-red")) gh.setCheckRun("demo-app", branch, "build", "completed", "failure")
            else gh.setCheckRun("demo-app", branch, "build", "in_progress")
        }
        val r = release("gate", listOf(red, green))
        runBlocking { s.releases.start(alice, r.id) }
        // The failed one is held; the release goes on with the next issue, which waits for its checks.
        eventually("red held") { s.releaseStore.get(r.id)!!.issue(red).state == ReleaseIssueState.HELD }
        val redRun = s.runs.get(s.releaseStore.get(r.id)!!.issue(red).runId!!)!!
        assertEquals(FailureKind.CHECKS, redRun.failureKind)
        eventually("green waiting") {
            s.releaseStore.get(r.id)!!.issue(green).runId?.let { s.runs.get(it)?.status } == RunStatus.WAITING_FOR_CHECKS
        }
        val greenRun = s.runs.get(s.releaseStore.get(r.id)!!.issue(green).runId!!)!!
        assertEquals(ReleaseIssueState.RUNNING, s.releaseStore.get(r.id)!!.issue(green).state)
        assertTrue(gh.actions.none { it == "merge demo-app #${greenRun.prNumber}" })
        gh.setCheckRun("demo-app", greenRun.branch!!, "build", "completed", "success")
        eventually("green merged") { s.releaseStore.get(r.id)!!.issue(green).state == ReleaseIssueState.MERGED }
        assertEquals(headOf(greenRun), gh.squashMergeShas[greenRun.prNumber!!])
        assertTrue(comments(green).any { it.contains("**Agern: merged** pull request #${greenRun.prNumber} into `release/gate`") })
    }

    @Test
    fun `the wait limit is configured in minutes`() {
        val base = mapOf("FACTORY_MASTER_KEY" to java.util.Base64.getEncoder().encodeToString(ByteArray(32)), "FACTORY_GITHUB_ORG" to "o")
        assertEquals(60L, Config.fromEnv(base).runChecksWaitMinutes)
        assertEquals(5L, Config.fromEnv(base + ("FACTORY_RUN_CHECKS_WAIT_MINUTES" to "5")).runChecksWaitMinutes)
        for (bad in listOf("0", "1441", "soon")) {
            assertFailsWith<IllegalArgumentException> { Config.fromEnv(base + ("FACTORY_RUN_CHECKS_WAIT_MINUTES" to bad)) }
        }
    }
}
