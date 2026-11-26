package dk.egernet.factory

import dk.egernet.factory.agent.AgentIdentity
import dk.egernet.factory.db.RunRecord
import dk.egernet.factory.git.GitOps
import dk.egernet.factory.pipeline.Orchestrator
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.request.setBody
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Git whose clone fails like git without a network while [failClone] is set. */
class FlakyCloneGit(private val inner: FakeGitOps) : GitOps by inner {
    @Volatile var failClone: String? = null
    val cloneAttempts = java.util.concurrent.atomic.AtomicInteger()
    override fun ensureClone(id: AgentIdentity, cloneUrl: String, dir: Path, home: Path, branch: String?) {
        cloneAttempts.incrementAndGet()
        failClone?.let { throw dk.egernet.factory.git.GitException("git clone failed: $it") }
        inner.ensureClone(id, cloneUrl, dir, home, branch)
    }
}

/**
 * A run that loses the network waits and retries instead of failing (docs/ARCHITECTURE.md §6 "Transient failures"):
 * set-up, stages, the spec comment, push, pull request and merge.
 */
class NetworkWaitTest {
    private val worlds = CopyOnWriteArrayList<TestWorld>()

    @AfterTest fun tearDown() {
        worlds.forEach { w -> runCatching { w.services.runs.list(null, activeOnly = true).forEach { r -> w.services.orchestrator.job(r.id)?.cancel() } } }
        worlds.forEach { it.close() }
    }

    /** Scripted behaviours per role, used once each in order; afterwards the role behaves normally. */
    private class Script {
        val plan = ConcurrentHashMap<String, ConcurrentLinkedQueue<(FakeProcess) -> Unit>>()
        fun next(role: String, b: (FakeProcess) -> Unit) { plan.getOrPut(role) { ConcurrentLinkedQueue() } += b }
    }

    private inner class W(
        delays: List<Long> = listOf(60_000L), maxMs: Long = 3_600_000L, maxAgents: Int = 8, val flaky: Boolean = false,
    ) {
        val flakyGit = FakeGitOps()
        val clone = FlakyCloneGit(flakyGit)
        val world = TestWorld(
            twoFactor = false, gitOps = if (flaky) clone else null,
            tweak = { it.copy(networkRetryMaxMs = maxMs, networkRetryDelaysMs = delays, maxAgentsPerUser = maxAgents) },
        ).also { worlds += it }
        val git get() = if (flaky) flakyGit else world.git
        val s = world.services
        val alice = world.user("alice")
        val script = Script()

        init {
            world.launcher.stage = { p ->
                val b = script.plan[p.spec.kind]?.poll()
                if (b != null) b(p) else normal(p)
            }
        }

        fun normal(p: FakeProcess) {
            when (p.spec.kind) {
                "spec-writer" -> { p.init(); p.result("## Spec\nDo it."); p.exit(0) }
                "builder" -> { p.init(); p.result("Built it."); p.exit(0) }
                "approver" -> { p.init(); p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") }); p.exit(0) }
                else -> {
                    p.init()
                    p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
                    p.exit(0)
                }
            }
        }

        fun start(title: String = "Feature ${UUID.randomUUID().toString().take(6)}"): RunRecord {
            val n = world.github.addIssue("demo-app", title, "body").number
            return runBlocking { s.orchestrator.start(alice, "demo-app", n, null) }
        }

        fun run(id: UUID) = s.runs.get(id)!!
        fun dto(id: UUID) = s.runs.toDto(run(id))
        fun awaitEnd(id: UUID) = world.awaitRun(id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN, 20_000)
        fun awaitWaiting(id: UUID, attempt: Int = 1): RunWaitDto {
            eventually("run waiting (attempt $attempt)") { dto(id).waiting?.attempt == attempt }
            return dto(id).waiting!!
        }
        fun retryNow(id: UUID) = runBlocking { s.factory.retryRunNow(alice, id) }
        fun builders() = world.launcher.byRole("builder")
    }

    private fun eventually(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!cond()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    private val unreachableText = "API Error: Can't reach the API server - check your internet or DNS"

    /** A builder that started its conversation as [claude] and then lost the API. */
    private fun lostBuilder(claude: String? = "claude-first"): (FakeProcess) -> Unit = { p ->
        if (claude != null) p.init(claude)
        p.result(unreachableText, isError = true)
        p.exit(1)
    }

    @Test
    fun `a builder that loses the API waits, keeps its state and resumes its conversation on retry now`() {
        val w = W()
        w.script.next("builder") { p -> w.world.github.unreachable = true; lostBuilder()(p) }
        val run = w.start()
        val wait = w.awaitWaiting(run.id)
        val r = w.run(run.id)
        val dto = w.dto(run.id)
        assertEquals(RunStatus.BUILDING, r.status)
        assertEquals("network", wait.reason)
        assertTrue(wait.detail.contains("Can't reach the API server"), wait.detail)
        assertTrue(Instant.parse(wait.nextAttemptAt).isAfter(Instant.parse(wait.since).plusSeconds(50)), wait.toString())
        assertEquals("running", dto.stages.single { it.role == "builder" }.status)
        assertTrue(Files.isDirectory(w.s.workspaces.runDir("alice", "demo-app", run.id)), "the worktree stays")
        assertNull(r.failureKind)
        // The transcript of the failed attempt says what happens next.
        val failedSession = w.builders().single().spec.sessionId
        assertTrue(w.s.events.list(failedSession, 0, 100).any { it.type == "system" && it.payload.toString().contains("Waiting for the network; next try at") })

        w.world.github.unreachable = false
        val after = w.retryNow(run.id)
        assertNull(after.waiting, "retry now ends the wait")
        val done = w.awaitEnd(run.id)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertNull(w.dto(run.id).waiting)
        val second = w.builders()[1]
        assertEquals("claude-first", second.spec.resumeClaudeSessionId)
        assertEquals(Orchestrator.CONTINUE_PROMPT, second.spec.prompt)
        assertEquals(failedSession, w.s.sessions.get(second.spec.sessionId)!!.parentSessionId)
        // One stage row, now done with the second session.
        val stage = w.s.runs.stages(run.id).single { it.role == "builder" }
        assertEquals("done", stage.status)
        assertEquals(second.spec.sessionId, stage.sessionId)
    }

    @Test
    fun `waits retry by themselves with backoff, keep since and count attempts`() {
        val w = W(delays = listOf(60L, 60_000L))
        // No Claude session id: the retry runs the original prompt again.
        w.script.next("builder", lostBuilder(claude = null))
        w.script.next("builder", lostBuilder(claude = null))
        val run = w.start()
        val wait = w.awaitWaiting(run.id, attempt = 2)
        assertEquals(2, w.builders().size, "the first wait ended by itself")
        val since = Instant.parse(wait.since)
        val next = Instant.parse(wait.nextAttemptAt)
        // since is the first failure: at least the first delay before the second failure (next try = 2nd failure + 60 s).
        assertTrue(!since.isAfter(next.minusMillis(60_000 + 50)), "$since / $next")
        assertEquals(w.builders()[0].spec.prompt, w.builders()[1].spec.prompt)
        assertNull(w.builders()[1].spec.resumeClaudeSessionId)
        w.retryNow(run.id)
        assertEquals(RunStatus.MERGED, w.awaitEnd(run.id).status)
        assertEquals(3, w.builders().size)
    }

    @Test
    fun `a resume that cannot start is followed by one fresh attempt with the original prompt`() {
        val w = W(delays = listOf(30L))
        w.script.next("builder", lostBuilder("claude-gone"))
        w.script.next("builder") { p -> p.exit(1) } // --resume refused at once: no init, no result
        val run = w.start()
        val done = w.awaitEnd(run.id)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        val b = w.builders()
        assertEquals(3, b.size)
        assertEquals("claude-gone", b[1].spec.resumeClaudeSessionId)
        assertNull(b[2].spec.resumeClaudeSessionId)
        assertEquals(b[0].spec.prompt, b[2].spec.prompt)
    }

    @Test
    fun `GitHub failing during set-up waits and the set-up runs again`() {
        val w = W()
        // Warm the visibility cache, so only the run's own set-up reads the repository.
        assertTrue(w.s.access.canSee(w.alice, "demo-app"))
        w.world.github.getRepoFailure = 503
        val run = w.start()
        val wait = w.awaitWaiting(run.id)
        assertEquals(RunStatus.QUEUED, w.run(run.id).status)
        assertTrue(wait.detail.contains("503"), wait.detail)
        w.world.github.getRepoFailure = null
        w.retryNow(run.id)
        assertEquals(RunStatus.MERGED, w.awaitEnd(run.id).status)
    }

    @Test
    fun `git failing to clone during set-up waits and retries`() {
        val w = W(flaky = true)
        w.clone.failClone = "fatal: unable to access 'https://github.invalid/x.git/': Could not resolve host: github.invalid"
        val run = w.start()
        w.awaitWaiting(run.id)
        w.clone.failClone = null
        w.retryNow(run.id)
        assertEquals(RunStatus.MERGED, w.awaitEnd(run.id).status, w.run(run.id).error)
        assertEquals(2, w.clone.cloneAttempts.get())
    }

    @Test
    fun `GitHub unreachable while opening the pull request waits`() {
        val w = W()
        w.script.next("builder") { p -> w.world.github.unreachable = true; w.normal(p) }
        val run = w.start()
        val wait = w.awaitWaiting(run.id)
        assertTrue(wait.detail.contains("Opening the pull request"), wait.detail)
        assertTrue(wait.detail.contains("ConnectException"), wait.detail)
        assertEquals(RunStatus.BUILDING, w.run(run.id).status)
        w.world.github.unreachable = false
        w.retryNow(run.id)
        val done = w.awaitEnd(run.id)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertNotNull(done.prNumber)
    }

    @Test
    fun `a transient push failure waits, a refused push fails at once`() {
        val w = W()
        w.script.next("builder") { p ->
            w.git.failPush = "fatal: unable to access 'https://github.invalid/x.git/': Could not resolve host: github.invalid"
            w.normal(p)
        }
        val run = w.start()
        w.awaitWaiting(run.id)
        w.git.failPush = null
        w.retryNow(run.id)
        assertEquals(RunStatus.MERGED, w.awaitEnd(run.id).status)

        w.script.next("builder") { p -> w.git.failPush = "remote: Permission to x denied to alice."; w.normal(p) }
        val refused = w.start()
        val failed = w.awaitEnd(refused.id)
        assertEquals(RunStatus.FAILED, failed.status)
        assertEquals(FailedStep.PUSH, failed.failedStep)
        assertEquals(FailureKind.STEP, failed.failureKind)
        assertNull(failed.wait)
        w.git.failPush = null
    }

    @Test
    fun `a transient merge failure waits, and a merge that went through is recognised`() {
        val w = W()
        w.script.next("approver") { p -> w.world.github.mergeFailure = 502; w.normal(p) }
        val run = w.start()
        w.awaitWaiting(run.id)
        assertEquals(RunStatus.APPROVING, w.run(run.id).status)
        w.world.github.mergeFailure = null
        w.retryNow(run.id)
        assertEquals(RunStatus.MERGED, w.awaitEnd(run.id).status)

        // The merge happened, but the answer was lost: the retry reads the pull request and does not merge again.
        w.script.next("approver") { p -> w.world.github.mergeFailure = 502; w.world.github.mergeFailureAfterMerging = true; w.normal(p) }
        val second = w.start()
        w.awaitWaiting(second.id)
        w.world.github.mergeFailure = null
        w.world.github.mergeFailureAfterMerging = false
        w.retryNow(second.id)
        val done = w.awaitEnd(second.id)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertEquals(1, w.world.github.actions.count { it == "merge demo-app #${done.prNumber}" })
    }

    @Test
    fun `reviewers failing together share one wait and passed reviewers are not run again`() {
        val w = W()
        w.script.next("security", lostBuilder("claude-sec"))
        w.script.next("ux", lostBuilder("claude-ux"))
        val run = w.start()
        eventually("both failed") { w.world.launcher.byRole("security").size == 1 && w.world.launcher.byRole("ux").size == 1 && w.dto(run.id).waiting != null }
        eventually("other reviewers done") {
            w.s.runs.stages(run.id).filter { it.role in setOf("ui-design", "code") }.all { it.status == "done" }
        }
        Thread.sleep(100)
        assertEquals(1, w.dto(run.id).waiting!!.attempt, "the second failure joined the first wait")
        assertEquals(RunStatus.REVIEWING, w.run(run.id).status)
        w.retryNow(run.id)
        assertEquals(RunStatus.MERGED, w.awaitEnd(run.id).status)
        assertEquals(2, w.world.launcher.byRole("security").size)
        assertEquals(2, w.world.launcher.byRole("ux").size)
        assertEquals(1, w.world.launcher.byRole("ui-design").size)
        assertEquals(1, w.world.launcher.byRole("code").size)
    }

    @Test
    fun `giving up fails the run through the normal failure path`() {
        val w = W()
        w.script.next("builder", lostBuilder())
        val run = w.start()
        w.awaitWaiting(run.id)
        val failed = runBlocking { w.s.factory.giveUpRun(w.alice, run.id) }
        assertEquals(RunStatus.FAILED, failed.status)
        assertNull(failed.waiting)
        assertTrue(failed.error!!.endsWith("Gave up waiting for the network (by @alice, after 1 attempt)."), failed.error)
        assertTrue(failed.error!!.contains("Can't reach the API server"), failed.error)
        assertEquals(FailureKind.API_UNREACHABLE, failed.failureKind)
        assertEquals("failed", failed.stages.single { it.role == "builder" }.status)
        assertTrue(w.world.github.actions.any { it.startsWith("comment demo-app#${run.issueNumber} **Agern:** run `${run.id}` failed:") })
        eventually("worktree removed") { w.s.orchestrator.job(run.id) == null }
    }

    @Test
    fun `the limit fails the run with the original reason`() {
        val w = W(delays = listOf(40L), maxMs = 150L)
        repeat(20) { w.script.next("builder", lostBuilder(null)) }
        val run = w.start()
        val failed = w.awaitEnd(run.id)
        assertEquals(RunStatus.FAILED, failed.status)
        assertEquals(FailureKind.API_UNREACHABLE, failed.failureKind)
        assertTrue(Regex("Gave up after waiting for the network for \\d+ s \\(\\d+ attempts\\)\\.$").containsMatchIn(failed.error!!), failed.error)
        assertTrue(w.builders().size >= 2)
        assertNull(failed.wait)
    }

    @Test
    fun `with waiting switched off nothing waits`() {
        val w = W(maxMs = 0)
        w.script.next("builder", lostBuilder())
        val failed = w.awaitEnd(w.start().id)
        assertEquals(RunStatus.FAILED, failed.status)
        assertEquals(FailureKind.API_UNREACHABLE, failed.failureKind)
        assertEquals("builder failed: Agent finished with error_during_execution: $unreachableText", failed.error)
        assertEquals(1, w.builders().size)
    }

    @Test
    fun `cancelling a waiting run cancels it`() {
        val w = W()
        w.script.next("builder", lostBuilder())
        val run = w.start()
        w.awaitWaiting(run.id)
        val cancelled = runBlocking { w.s.orchestrator.cancel(w.alice, run.id) }
        assertEquals(RunStatus.CANCELLED, cancelled.status)
        assertNull(cancelled.wait)
        assertNull(w.dto(run.id).waiting)
    }

    @Test
    fun `a waiting run holds no agent slot`() {
        val w = W(maxAgents = 1)
        w.script.next("builder", lostBuilder())
        val run = w.start()
        w.awaitWaiting(run.id)
        eventually("slot free") { w.s.agents.hasFreeSlot(w.alice) }
        w.retryNow(run.id)
        assertEquals(RunStatus.MERGED, w.awaitEnd(run.id).status)
    }

    @Test
    fun `failures of the work never wait`() {
        val w = W()
        // A builder without commits.
        w.git.commits = 0
        val blocked = w.awaitEnd(w.start().id)
        assertEquals(FailureKind.BLOCKED, blocked.failureKind)
        assertNull(blocked.wait)
        w.git.commits = 1
        // A reviewer without a valid verdict.
        w.script.next("code") { p -> p.init(); p.result("no json here"); p.exit(0) }
        val noVerdict = w.awaitEnd(w.start().id)
        assertEquals(RunStatus.FAILED, noVerdict.status)
        assertTrue(noVerdict.error!!.contains("valid verdict"), noVerdict.error)
        // An approver without a valid verdict.
        w.script.next("approver") { p -> p.init(); p.result("maybe"); p.exit(0) }
        val noApproval = w.awaitEnd(w.start().id)
        assertEquals(RunStatus.FAILED, noApproval.status)
        // A rejected credential.
        w.script.next("builder") { p -> p.init(); p.result("Invalid API key · Please run /login", isError = true); p.exit(1) }
        val creds = w.awaitEnd(w.start().id)
        assertEquals(FailureKind.CREDENTIALS, creds.failureKind)
        // A failing builder process.
        w.script.next("builder") { p -> p.init(); p.exit(1) }
        val crashed = w.awaitEnd(w.start().id)
        assertEquals(RunStatus.FAILED, crashed.status)
        listOf(blocked, noVerdict, noApproval, creds, crashed).forEach { assertNull(w.run(it.id).wait, it.error) }
        assertEquals(5, w.builders().size, "no stage ran twice")
    }

    @Test
    fun `the run endpoints check visibility, permission and state`() = io.ktor.server.testing.testApplication {
        val w = W()
        application { agernModule(w.s) }
        w.world.user("bob")
        w.world.user("root", admin = true)
        suspend fun login(user: String, pw: String) = createClient { install(io.ktor.client.plugins.cookies.HttpCookies) }.also { c ->
            assertEquals(200, c.post("/api/auth/login") {
                header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
            }.status.value)
        }
        suspend fun HttpClient.act(id: UUID, what: String) = post("/api/runs/$id/network/$what") { header("X-Factory-Request", "1") }
        val a = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        val root = login("root", "pw-r")
        w.script.next("builder", lostBuilder())
        w.script.next("builder", lostBuilder())
        val run = w.start()
        w.awaitWaiting(run.id)

        assertEquals(404, a.act(UUID.randomUUID(), "retry").status.value)
        assertEquals(403, bob.act(run.id, "retry").status.value)
        assertEquals(403, bob.act(run.id, "give-up").status.value)
        assertEquals(403, a.post("/api/runs/${run.id}/network/retry").status.value, "CSRF header required")
        val retried = a.act(run.id, "retry")
        assertEquals(200, retried.status.value, retried.bodyAsText())
        // No longer waiting on attempt 1. The scripted builder fails again at once, so the answer may already show attempt 2.
        val waitingAfterRetry = AppJson.parseToJsonElement(retried.bodyAsText()).jsonObject["waiting"]
        assertTrue(
            waitingAfterRetry == null || waitingAfterRetry is kotlinx.serialization.json.JsonNull ||
                waitingAfterRetry.jsonObject["attempt"]?.jsonPrimitive?.content != "1",
            "still waiting on attempt 1 after retry: $waitingAfterRetry",
        )
        // Fails again: an admin may give up.
        w.awaitWaiting(run.id, attempt = 2)
        val gaveUp = root.act(run.id, "give-up")
        assertEquals(200, gaveUp.status.value, gaveUp.bodyAsText())
        val body = AppJson.parseToJsonElement(gaveUp.bodyAsText()).jsonObject
        assertEquals("failed", body["status"]!!.jsonPrimitive.content)
        assertTrue(body["error"]!!.jsonPrimitive.content.contains("(by @root, after 2 attempts)"))
        val notWaiting = a.act(run.id, "retry")
        assertEquals(409, notWaiting.status.value)
        assertTrue(notWaiting.bodyAsText().contains("run_not_waiting_for_network"), notWaiting.bodyAsText())
        assertEquals(409, a.act(run.id, "give-up").status.value)
        assertFailsWith<ApiException> { runBlocking { w.s.factory.retryRunNow(w.alice, run.id) } }
    }
}
