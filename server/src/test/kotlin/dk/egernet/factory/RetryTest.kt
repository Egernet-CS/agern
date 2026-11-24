package dk.egernet.factory

import dk.egernet.factory.db.ReleaseRecord
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Retrying a failed run: the failed step, continuing from its commits, or starting over (docs/ARCHITECTURE.md §6
 * "Retrying a run"). Real git in a temp dir (fake-GitHub mode: local clone, pushes through [FailingPushGit]).
 */
class RetryTest {
    /** A short stage time limit, so a hanging builder is stopped quickly (and warned at 80 % of it). */
    private val world = TestWorld(realGit = true, twoFactor = false, tweak = { it.copy(stageTimeoutMs = 2_500) })
    private val s = world.services
    private val alice = world.user("alice")
    private val git = world.realGitOps!!
    private val main: Path get() = s.workspaces.mainDir("alice", "demo-app")
    private val home: Path get() = s.workspaces.home("alice")

    @AfterTest fun tearDown() {
        world.close()
    }

    /**
     * Builder behaviour by issue title (the first builder of a title only; later builders just commit and finish):
     * `timeout` commits and then hangs until the time limit stops it; `unreachable` commits and fails with the CLI's
     * "Can't reach the API server"; `blocked` makes no commits.
     */
    private val first = ConcurrentHashMap<String, String>()
    /** What each builder saw: its prompt and `git log --format=%s` of its worktree when it started. */
    private val builderPrompts = CopyOnWriteArrayList<String>()
    private val builderLogs = CopyOnWriteArrayList<List<String>>()
    /** Stdin messages the hanging builder picked up after its task (the time-limit warning). */
    private val warnings = CopyOnWriteArrayList<String>()

    init {
        world.launcher.stage = { p ->
            val title = Regex("Issue #\\d+: (.+)").find(p.spec.prompt ?: "")?.groupValues?.get(1)?.trim() ?: ""
            when (p.spec.kind) {
                "spec-writer" -> { p.init(); p.result("## Spec\nBuild $title."); p.exit(0) }
                "builder" -> {
                    p.init()
                    builderPrompts += p.spec.prompt ?: ""
                    builderLogs += gitIn(p.spec.workDir, "log", "--format=%s").lines().filter { it.isNotBlank() }
                    when (first.remove(title)) {
                        "timeout" -> {
                            commit(p.spec.workDir, "part-1.txt", "feature part 1")
                            while (!p.killed) p.pickUp(50)?.let { warnings += it }
                        }
                        "unreachable" -> {
                            commit(p.spec.workDir, "part-1.txt", "feature part 1")
                            p.result("API Error: Can't reach the API server — check your internet or DNS", isError = true)
                            p.exit(1)
                        }
                        "blocked" -> { p.result("Blocked on #19: nothing to build yet."); p.exit(0) }
                        else -> {
                            commit(p.spec.workDir, "part-${builderPrompts.size}.txt", "feature finished")
                            p.result("Built it."); p.exit(0)
                        }
                    }
                }
                "approver" -> { p.init(); p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") }); p.exit(0) }
                else -> {
                    p.init()
                    p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
                    p.exit(0)
                }
            }
        }
    }

    private fun gitIn(dir: Path, vararg args: String): String {
        val pb = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid") + args)
            .directory(dir.toFile()).redirectErrorStream(true)
        pb.environment()["HOME"] = home.toString()
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${args.joinToString(" ")} failed: $out" }
        return out
    }

    private fun commit(dir: Path, file: String, message: String) {
        Files.writeString(dir.resolve(file), "$message\n")
        gitIn(dir, "add", file)
        gitIn(dir, "commit", "-q", "-m", message)
    }

    private fun issue(title: String, behaviour: String? = null): Int {
        behaviour?.let { first[title] = it }
        return world.github.addIssue("demo-app", title, "body").number
    }

    private fun start(n: Int) = runBlocking { s.orchestrator.start(alice, "demo-app", n, null) }
    private fun await(id: UUID) = world.awaitRun(id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN, 20_000)
    private fun options(id: UUID) = runBlocking { s.factory.retryOptions(alice, id) }
    private fun retry(id: UUID, mode: String?) = runBlocking { s.factory.retryRun(alice, UUID.fromString(id.toString()), mode) }
    private fun localBranches() = gitIn(main, "branch", "--format=%(refname:short)").lines().filter { it.isNotBlank() }
    private fun launched(role: String) = world.launcher.byRole(role).size

    /** Side effects follow status changes asynchronously: wait for [cond]. */
    private fun eventually(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!cond()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `a builder stopped by the time limit after committing is warned first and continued from its commits`() {
        val n = issue("Timed out feature", "timeout")
        val failed = await(start(n).id)
        assertEquals(RunStatus.FAILED, failed.status)
        assertEquals(FailureKind.TIME_LIMIT, failed.failureKind)
        assertNull(failed.failedStep)
        assertTrue(failed.error!!.contains("Timed out"), failed.error)
        // Warned at 80 % of the limit through the message mechanism.
        assertTrue(warnings.any { it.contains("time limit") && it.contains("hand-over note") }, warnings.toString())
        // The unpushed commit is kept on the local branch.
        assertTrue(failed.branch in localBranches(), localBranches().toString())

        val opts = options(failed.id)
        assertEquals(RetryMode.CONTINUE, opts.recommended, opts.reason)
        assertTrue(opts.reason.contains("time limit"), opts.reason)
        assertEquals(1, opts.commits)
        assertFalse(opts.options.single { it.mode == RetryMode.STEP }.available)

        val next = retry(failed.id, RetryMode.RECOMMENDED)
        assertNotEquals(failed.id.toString(), next.id)
        assertEquals(failed.id.toString(), next.continuesRunId)
        val done = await(UUID.fromString(next.id))
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertNotEquals(failed.branch, done.branch)
        // No new spec: the spec-writer ran once, and the continuation's stage says it was reused.
        assertEquals(1, launched("spec-writer"))
        assertEquals("skipped", s.runs.stages(done.id).single { it.role == "spec-writer" }.status)
        assertEquals(s.runs.get(failed.id)!!.spec, done.spec)
        // The second builder started on the earlier commit and was told what exists and why the run stopped.
        assertEquals(2, builderPrompts.size)
        assertEquals("feature part 1", builderLogs[1].first())
        assertTrue(builderPrompts[1].contains("Do not redo that work") && builderPrompts[1].contains("Timed out"), builderPrompts[1])
        assertTrue(builderPrompts[1].contains(failed.branch!!), builderPrompts[1])
        // One pull request with both commits' branch; its body links the run it continues.
        val pr = done.prNumber!!
        assertTrue(world.github.pullBodies[pr]!!.contains("Continues Agern run `${failed.id}`"), world.github.pullBodies[pr])
        assertEquals(done.id.toString(), s.runs.toDto(s.runs.get(failed.id)!!).continuedByRunId)
        eventually("continuation comment") { world.github.actions.any { it.contains("continues run `${failed.id}`") } }
    }

    @Test
    fun `starting over runs from scratch on a new branch and leaves the old branch as it was`() {
        val n = issue("Restart me", "unreachable")
        val failed = await(start(n).id)
        assertEquals(RunStatus.FAILED, failed.status)
        assertEquals(FailureKind.API_UNREACHABLE, failed.failureKind)
        assertEquals(RetryMode.CONTINUE, options(failed.id).recommended)
        val oldTip = gitIn(main, "rev-parse", failed.branch!!).trim()

        // No mode = start over (compatible with clients that send nothing).
        val next = retry(failed.id, null)
        assertNull(next.continuesRunId)
        val done = await(UUID.fromString(next.id))
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertEquals(2, launched("spec-writer"))
        assertTrue(builderLogs[1].none { it == "feature part 1" }, builderLogs[1].toString())
        assertTrue(failed.branch in localBranches(), localBranches().toString())
        assertEquals(oldTip, gitIn(main, "rev-parse", failed.branch!!).trim())
    }

    @Test
    fun `a builder that made no commits is started over, and continuing it is refused`() {
        val n = issue("Blocked one", "blocked")
        val failed = await(start(n).id)
        assertEquals(FailureKind.BLOCKED, failed.failureKind)
        val opts = options(failed.id)
        assertEquals(RetryMode.START_OVER, opts.recommended)
        assertTrue(opts.reason.contains("Blocked on #19"), opts.reason)
        assertFalse(opts.options.single { it.mode == RetryMode.CONTINUE }.available)
        assertEquals("retry_mode_unavailable", assertFailsWith<ApiException> { retry(failed.id, RetryMode.CONTINUE) }.code)
        assertEquals("retry_mode_unavailable", assertFailsWith<ApiException> { retry(failed.id, RetryMode.STEP) }.code)
        assertEquals(400, assertFailsWith<ApiException> { retry(failed.id, "sideways") }.status.value)
    }

    @Test
    fun `a refused push is retried as a step on the same run without a new spec or build`() {
        val n = issue("Workflow change")
        git.failPush = "remote: refusing to allow a Personal Access Token to create or update workflow `.github/workflows/ci.yml`"
        val failed = await(start(n).id)
        assertEquals(RunStatus.FAILED, failed.status)
        assertEquals(FailureKind.STEP, failed.failureKind)
        assertEquals(FailedStep.PUSH, failed.failedStep)
        assertNull(failed.prNumber)
        assertTrue(failed.error!!.contains("Pushing branch") && failed.error!!.contains("workflow"), failed.error)
        val opts = options(failed.id)
        assertEquals(RetryMode.STEP, opts.recommended, opts.reason)
        assertTrue(opts.reason.startsWith("The builder finished; only the push failed"), opts.reason)
        assertEquals(FailedStep.PUSH, opts.step)

        // While the token is still refused, the step fails again and stays retryable.
        val again = retry(failed.id, RetryMode.STEP)
        assertEquals(failed.id.toString(), again.id)
        val failedAgain = world.awaitRun(failed.id, setOf(RunStatus.FAILED), 20_000)
        assertEquals(FailedStep.PUSH, failedAgain.failedStep)

        git.failPush = null
        retry(failed.id, RetryMode.RECOMMENDED)
        val done = await(failed.id)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertNotNull(done.prNumber)
        assertEquals(listOf(failed.branch), git.pushes.toList())
        assertEquals(1, launched("spec-writer"))
        assertEquals(1, launched("builder"))
        assertTrue(s.runs.stages(done.id).all { it.status == "done" }, s.runs.stages(done.id).toString())
        eventually("step comment") { world.github.actions.any { it.contains("retrying the failed step (push) of run `${failed.id}`") } }
    }

    @Test
    fun `a failed pull request creation is retried as a step`() {
        val n = issue("PR fails")
        world.github.failCreatePull = true
        val failed = await(start(n).id)
        assertEquals(FailedStep.PULL_REQUEST, failed.failedStep)
        assertEquals(RetryMode.STEP, options(failed.id).recommended)
        world.github.failCreatePull = false
        retry(failed.id, RetryMode.STEP)
        val done = await(failed.id)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertEquals(1, launched("builder"))
    }

    @Test
    fun `only the owner retries, and only failed or cancelled single runs`() {
        val n = issue("Owner only", "unreachable")
        val failed = await(start(n).id)
        val bob = world.user("bob")
        val root = world.user("root", admin = true)
        assertEquals(403, assertFailsWith<ApiException> { runBlocking { s.factory.retryRun(bob, failed.id, null) } }.status.value)
        assertEquals(403, assertFailsWith<ApiException> { runBlocking { s.factory.retryRun(root, failed.id, null) } }.status.value)
        // Anyone who can see the run may read its options.
        assertEquals(RetryMode.CONTINUE, runBlocking { s.factory.retryOptions(bob, failed.id) }.recommended)

        val done = await(UUID.fromString(retry(failed.id, RetryMode.CONTINUE).id))
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertEquals("run_not_retryable", assertFailsWith<ApiException> { retry(done.id, null) }.code)
    }

    @Test
    fun `the factory command lists the options and retries`() {
        val n = issue("Chat retry", "unreachable")
        val failed = await(start(n).id)
        val chat = s.sessions.create("demo-app", "chat", "sonnet", SessionStatus.IDLE, alice.id, "chat", null, null, null)
        val listing = runBlocking { s.factory.handleCommand(chat, alice, "/factory retry ${failed.id}") }
        assertTrue(listing.contains("Recommended: continue") && listing.contains("/factory retry ${failed.id} continue"), listing)
        val reply = runBlocking { s.factory.handleCommand(chat, alice, "/factory retry ${failed.id} continue") }
        assertTrue(reply.contains("continuing from run ${failed.id}"), reply)
        val next = s.runs.list("demo-app", activeOnly = false).first()
        assertEquals(failed.id, next.continuesRunId)
        assertEquals(RunStatus.MERGED, await(next.id).status)
    }

    @Test
    fun `the REST endpoints answer options, refuse others and retry with or without a body`() = io.ktor.server.testing.testApplication {
        application { agernModule(s) }
        world.user("bob")
        suspend fun login(user: String, pw: String) = createClient { install(io.ktor.client.plugins.cookies.HttpCookies) }.also { c ->
            assertEquals(200, c.post("/api/auth/login") {
                header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
            }.status.value)
        }
        val a = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        val failed = await(start(issue("Rest retry", "unreachable")).id)

        val opts = a.get("/api/runs/${failed.id}/retry")
        assertEquals(200, opts.status.value)
        val o = AppJson.parseToJsonElement(opts.bodyAsText()).jsonObject
        assertEquals("continue", o["recommended"]!!.jsonPrimitive.content)
        assertEquals(3, o["options"]!!.jsonArray.size)

        val denied = bob.post("/api/runs/${failed.id}/retry") { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("{}") }
        assertEquals(403, denied.status.value)
        val bad = a.post("/api/runs/${failed.id}/retry") { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"mode":"nope"}""") }
        assertEquals(400, bad.status.value)
        val ok = a.post("/api/runs/${failed.id}/retry") { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"mode":"continue"}""") }
        assertEquals(200, ok.status.value, ok.bodyAsText())
        val run = AppJson.parseToJsonElement(ok.bodyAsText()).jsonObject
        assertEquals(failed.id.toString(), run["continuesRunId"]!!.jsonPrimitive.content)
        val done = await(UUID.fromString(run["id"]!!.jsonPrimitive.content))
        assertEquals(RunStatus.MERGED, done.status, done.error)

        // A release issue retried with no body at all starts over.
        val held = issue("Rest release", "unreachable")
        val r = release("rest", listOf(held))
        s.releases.start(alice, r.id)
        val heldRun = awaitRelease(r.id) { it.status == ReleaseStatus.READY }.issue(held).runId
        val relOpts = a.get("/api/releases/${r.id}/issues/$held/retry")
        assertEquals(200, relOpts.status.value)
        val plain = a.post("/api/releases/${r.id}/issues/$held/retry") { header("X-Factory-Request", "1") }
        assertEquals(200, plain.status.value, plain.bodyAsText())
        val after = awaitRelease(r.id) { it.status == ReleaseStatus.READY && it.issue(held).state == ReleaseIssueState.MERGED }
        assertNotEquals(heldRun, after.issue(held).runId)
        assertNull(s.runs.get(after.issue(held).runId!!)!!.continuesRunId)
    }

    // ------------------------------------------------------------ releases

    private fun release(name: String, issues: List<Int>): ReleaseRecord = runBlocking {
        val r = s.releases.create(alice, "demo-app", name, "")
        s.releases.assign(alice, "demo-app", issues, r.id)
        s.releaseStore.get(r.id)!!
    }

    private fun awaitRelease(id: UUID, cond: (ReleaseRecord) -> Boolean): ReleaseRecord = runBlocking {
        withTimeout(30_000) {
            while (true) {
                val r = s.releaseStore.get(id)!!
                if (cond(r)) return@withTimeout r
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    private fun ReleaseRecord.issue(n: Int) = issues.single { it.number == n }

    @Test
    fun `a held issue of a release continues from its commits in its turn and keeps the release's order`() {
        val a = issue("Release alpha", "unreachable")
        val b = issue("Release beta")
        val r = release("3.0", listOf(a, b))
        runBlocking { s.releases.start(alice, r.id) }
        val ready = awaitRelease(r.id) { it.status == ReleaseStatus.READY }
        assertEquals(ReleaseIssueState.HELD, ready.issue(a).state)
        assertEquals(ReleaseIssueState.MERGED, ready.issue(b).state)
        val heldRun = ready.issue(a).runId!!

        val opts = runBlocking { s.releases.retryOptions(alice, r.id, a) }
        assertEquals(RetryMode.CONTINUE, opts.recommended, opts.reason)
        // The owner only.
        val bob = world.user("bob")
        assertEquals(403, assertFailsWith<ApiException> { runBlocking { s.releases.retry(bob, r.id, a, RetryMode.CONTINUE) } }.status.value)

        val after = runBlocking { s.releases.retry(alice, r.id, a, RetryMode.CONTINUE) }
        assertTrue(after.status == ReleaseStatus.BUILDING || after.status == ReleaseStatus.READY, after.status)
        val done = awaitRelease(r.id) { it.status == ReleaseStatus.READY && it.issue(a).state == ReleaseIssueState.MERGED }
        val newRun = s.runs.get(done.issue(a).runId!!)!!
        assertEquals(heldRun, newRun.continuesRunId)
        assertEquals("release/3.0", newRun.baseBranch)
        assertTrue(newRun.branch!!.startsWith("factory/release-3.0/issue-$a-"), newRun.branch)
        assertNull(done.issue(a).retryMode)
        assertEquals("feature part 1", builderLogs.last().first())
        assertEquals(2, launched("spec-writer"), "one spec per issue; the continuation reuses the held run's")
    }

    @Test
    fun `a held release issue whose push was refused is retried as a step on its own run`() {
        val a = issue("Release push")
        val r = release("3.1", listOf(a))
        git.failPush = "permission denied"
        runBlocking { s.releases.start(alice, r.id) }
        val ready = awaitRelease(r.id) { it.status == ReleaseStatus.READY }
        val held = ready.issue(a)
        assertEquals(ReleaseIssueState.HELD, held.state)
        assertEquals(RetryMode.STEP, runBlocking { s.releases.retryOptions(alice, r.id, a) }.recommended)

        git.failPush = null
        runBlocking { s.releases.retry(alice, r.id, a, RetryMode.STEP) }
        val done = awaitRelease(r.id) { it.status == ReleaseStatus.READY && it.issue(a).state == ReleaseIssueState.MERGED }
        assertEquals(held.runId, done.issue(a).runId)
        assertEquals(RunStatus.MERGED, s.runs.get(held.runId!!)!!.status)
        assertEquals(1, launched("builder"))
    }

    @Test
    fun `retrying a release issue without a mode starts it over, as before`() {
        val a = issue("Release over", "unreachable")
        val r = release("3.2", listOf(a))
        runBlocking { s.releases.start(alice, r.id) }
        val held = awaitRelease(r.id) { it.status == ReleaseStatus.READY }.issue(a)
        runBlocking { s.releases.retry(alice, r.id, a) }
        val done = awaitRelease(r.id) { it.status == ReleaseStatus.READY && it.issue(a).state == ReleaseIssueState.MERGED }
        val newRun = s.runs.get(done.issue(a).runId!!)!!
        assertNotEquals(held.runId, newRun.id)
        assertNull(newRun.continuesRunId)
        assertEquals(2, launched("spec-writer"))
    }
}
