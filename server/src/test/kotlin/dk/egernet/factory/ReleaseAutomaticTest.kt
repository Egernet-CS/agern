package dk.egernet.factory

import dk.egernet.factory.db.ReleaseRecord
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.github.ChecksSummary
import dk.egernet.factory.github.GhCheckRun
import dk.egernet.factory.github.GhCombinedStatus
import dk.egernet.factory.github.GhCommitStatus
import dk.egernet.factory.mcp.McpGrant
import dk.egernet.factory.mcp.McpRegistry
import dk.egernet.factory.pipeline.ReleaseModePolicy
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Release mode: manual or automatic release, and the checks on the release pull request
 * (docs/ARCHITECTURE.md §6 "Releasing", "Release mode", "Checks").
 */
class ReleaseAutomaticTest {
    private val world = TestWorld(twoFactor = false)
    private val s = world.services
    private val gh = world.github
    private val alice = world.user("alice")
    private val extraServices = CopyOnWriteArrayList<Services>()

    @AfterTest fun tearDown() {
        gates.keys.forEach { gates[it] = true }
        extraServices.forEach { it.stop() }
        world.close()
        runBlocking {
            for (svc in extraServices + s) withTimeoutOrNull(10_000) { svc.scope.coroutineContext[kotlinx.coroutines.Job]!!.join() }
        }
        (s.db.dataSource as java.io.Closeable).close()
    }

    // ------------------------------------------------------------ scripted agents, per issue title

    private val behaviour = ConcurrentHashMap<String, String>()
    private val gates = ConcurrentHashMap<String, Boolean>()

    init {
        s.releaseMerger.mergeablePollMillis = 20
        world.launcher.stage = { p ->
            val title = Regex("Issue #\\d+: (.+)").find(p.spec.prompt ?: "")?.groupValues?.get(1)?.trim() ?: ""
            val b = behaviour[title] ?: "ok"
            when (p.spec.kind) {
                "spec-writer" -> { p.init(); p.result("## Spec\nDo the thing."); p.exit(0) }
                "builder" -> { p.init(); p.result("Built it."); p.exit(0) }
                "approver" -> {
                    p.init()
                    if (b == "gate") while (!p.killed && gates[title] != true) Thread.sleep(10)
                    val v = if (b == "escalate") "ESCALATE" else "APPROVE"
                    p.result(null, buildJsonObject { put("verdict", v); put("summary", "approver says $v") }); p.exit(0)
                }
                else -> {
                    p.init()
                    p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "fine"); put("findings", buildJsonArray { }) })
                    p.exit(0)
                }
            }
        }
    }

    private fun issue(title: String, b: String = "ok"): Int {
        behaviour[title] = b
        return gh.addIssue("demo-app", title, "body").number
    }

    private fun release(name: String, issues: List<Int>, mode: String? = null, by: UserRecord = alice): ReleaseRecord = runBlocking {
        val r = s.releases.create(by, "demo-app", name, "", mode)
        if (issues.isNotEmpty()) s.releases.assign(by, "demo-app", issues, r.id)
        s.releaseStore.get(r.id)!!
    }

    private fun start(id: UUID) = runBlocking { s.releases.start(alice, id) }

    private fun awaitRelease(id: UUID, what: String, services: Services = s, cond: (ReleaseRecord) -> Boolean): ReleaseRecord =
        runBlocking {
            withTimeoutOrNull(15_000) {
                while (true) {
                    val r = services.releaseStore.get(id)!!
                    if (cond(r)) return@withTimeoutOrNull r
                    delay(20)
                }
                @Suppress("UNREACHABLE_CODE") null
            } ?: error("not reached: $what (${services.releaseStore.get(id)})")
        }

    /** Ready, with the pull request open and its checks read (the automatic decision follows under the same mutex). */
    private fun awaitChecked(id: UUID, state: String) = awaitRelease(id, "ready with checks $state") {
        // checksSha: read for the head commit (`unknown` is also the state before the first read).
        it.status == ReleaseStatus.READY && it.prNumber != null && it.checksSha != null && it.checksState == state
    }

    private fun awaitReleased(id: UUID, services: Services = s) =
        awaitRelease(id, "released", services) { it.status == ReleaseStatus.RELEASED && !it.cleanupPending }

    /** Waits until [cond] holds (side effects that follow a state change, e.g. comments). */
    private fun eventually(what: String, cond: () -> Boolean) = runBlocking {
        withTimeoutOrNull(10_000) { while (!cond()) delay(20) } ?: error("not reached: $what")
    }

    /** One periodic re-evaluation; it takes each release's mutex, so an evaluation still running finishes first. */
    private fun tick(services: Services = s) = runBlocking { services.releaseMerger.tick() }
    private fun merges() = gh.actions.count { it.startsWith("merge-") }
    private fun comments(n: Int) = gh.client("fake-alice").listComments("test-org", "demo-app", n).map { it.body ?: "" }
    private fun issueState(n: Int) = gh.client("fake-alice").getIssue("test-org", "demo-app", n)!!.state
    private fun code(block: () -> Unit): String = kotlin.test.assertFailsWith<ApiException> { block() }.code

    // ------------------------------------------------------------ the two modes

    @Test
    fun `a manual release is never merged without approval, even when everything is green`() {
        val n = issue("Manual one")
        gh.setCheckRun("demo-app", "release/m", "build", "completed", "success")
        val r0 = release("m", listOf(n))
        assertEquals(ReleaseMode.MANUAL, r0.mode)
        start(r0.id)
        val r = awaitChecked(r0.id, ReleaseChecksState.PASSED)
        assertEquals("1 check passed", r.checksDetail)
        assertEquals(ReleaseMergeState.CLEAN, r.mergeState)
        assertTrue(gh.pullBodies[r.prNumber!!]!!.contains("Release mode: **manual**. Approved and merged from Agern by the release owner"))
        eventually("ready comment") { comments(r.prNumber!!).any { it.contains("is ready to release") } }
        assertTrue(comments(r.prNumber!!).single { it.contains("is ready to release") }.contains("can approve it on"))
        repeat(3) { tick() }
        runBlocking { s.releases.check(alice, r.id) }
        assertEquals(ReleaseStatus.READY, s.releaseStore.get(r.id)!!.status)
        assertEquals(0, merges())
        assertEquals("open", issueState(n))
        val done = runBlocking { s.releases.approve(alice, r.id) }
        assertEquals(ReleaseStatus.RELEASED, done.status)
        assertFalse(done.releasedAutomatically)
        assertEquals(alice.id, done.releasedById)
    }

    @Test
    fun `an automatic release is merged once ready, complete, clean and green, exactly as an approval by its owner`() {
        val nums = listOf(issue("Auto one"), issue("Auto two"))
        val r0 = release("a", nums, mode = ReleaseMode.AUTOMATIC)
        assertEquals(ReleaseMode.AUTOMATIC, r0.mode)
        start(r0.id)
        val done = awaitReleased(r0.id)
        val pr = done.prNumber!!
        // The same merge as an approval: rebase, one commit per issue, as the owner.
        assertEquals(1, gh.actions.count { it == "merge-rebase demo-app #$pr" })
        assertEquals(listOf("Initial commit", "Auto one (#${nums[0]})", "Auto two (#${nums[1]})"), gh.commitsOf("demo-app", "main"))
        assertEquals(alice.id, done.releasedById)
        assertTrue(done.releasedAutomatically)
        assertEquals(ReleaseMergeMethod.REBASE, done.mergeMethod)
        assertEquals(ReleaseChecksState.NONE, done.checksState, "a repository without checks counts as green")
        val dto = done.toDto()
        assertEquals("alice", dto.releasedBy?.username)
        assertTrue(dto.releasedAutomatically)
        assertEquals("automatic", dto.mode)
        // The same clean-up: issues closed with a comment that says it was automatic, branch deleted.
        for (n in nums) {
            assertEquals("closed", issueState(n))
            val c = comments(n).last()
            assertTrue(c.startsWith("**Agern: released** in release `a`") && c.contains("Released automatically by Agern for @alice"), c)
        }
        assertTrue("delete-branch demo-app release/a" in gh.actions)
        assertTrue(gh.pullBodies[pr]!!.contains("Release mode: **automatic**. Agern merges this pull request for the release owner"))
        eventually("ready comment") { comments(pr).any { it.contains("is ready to release") } }
        assertTrue(comments(pr).single { it.contains("is ready to release") }.contains("It is set to release automatically"))
        // Nothing more happens afterwards.
        tick()
        assertEquals(1, merges())
    }

    @Test
    fun `pending checks wait, and the periodic re-evaluation merges once they have passed`() {
        gh.setCheckRun("demo-app", "release/p", "build", "in_progress")
        gh.setStatus("demo-app", "release/p", "ci/lint", "success")
        val r0 = release("p", listOf(issue("Waits for CI")), mode = ReleaseMode.AUTOMATIC)
        start(r0.id)
        val r = awaitChecked(r0.id, ReleaseChecksState.PENDING)
        assertEquals("Pending: build", r.checksDetail)
        assertNotNull(r.checksSha)
        tick()
        tick()
        assertEquals(ReleaseStatus.READY, s.releaseStore.get(r.id)!!.status)
        assertEquals(0, merges())
        assertNull(s.releaseStore.get(r.id)!!.notice, "waiting is not a problem")

        // The ticker (shortened) notices when the checks finish: there is no webhook.
        s.releaseMerger.periodicMillis = 50
        val ticker = s.releaseMerger.startTicker()
        try {
            gh.setCheckRun("demo-app", "release/p", "build", "completed", "success")
            val done = awaitReleased(r.id)
            assertTrue(done.releasedAutomatically)
            assertEquals(ReleaseChecksState.PASSED, done.checksState)
            assertEquals("2 checks passed", done.checksDetail)
        } finally {
            ticker.cancel()
        }
        assertEquals(1, merges())
    }

    @Test
    fun `failed checks leave an automatic release ready with a notice and one comment, until they pass`() {
        gh.setStatus("demo-app", "release/f", "ci/build", "failure")
        val r0 = release("f", listOf(issue("Breaks CI")), mode = ReleaseMode.AUTOMATIC)
        start(r0.id)
        val r = awaitChecked(r0.id, ReleaseChecksState.FAILED)
        val pr = r.prNumber!!
        val failureComments = { comments(pr).count { it.startsWith("**Agern:** checks failed on this release pull request (Failed: ci/build).") } }
        eventually("failure comment") { failureComments() == 1 }
        val withNotice = awaitRelease(r.id, "notice") { it.notice != null }
        assertTrue(withNotice.notice!!.startsWith("Checks failed on pull request #$pr (Failed: ci/build)"), withNotice.notice)
        repeat(3) { tick() }
        runBlocking { s.releases.check(alice, r.id) }
        assertEquals(1, failureComments(), "one comment per head commit")
        assertEquals(ReleaseStatus.READY, s.releaseStore.get(r.id)!!.status)
        assertEquals(0, merges())

        // Re-run and green: released automatically on the next re-evaluation.
        gh.setStatus("demo-app", "release/f", "ci/build", "success")
        tick()
        val done = awaitReleased(r.id)
        assertTrue(done.releasedAutomatically)
        assertNull(done.notice)
        assertEquals(1, merges())
    }

    @Test
    fun `a manual release shows its checks and can be approved although a check failed`() {
        gh.setCheckRun("demo-app", "release/mf", "tests", "completed", "failure")
        gh.setCheckRun("demo-app", "release/mf", "lint", "completed", "skipped")
        val r0 = release("mf", listOf(issue("Red but wanted")))
        start(r0.id)
        val r = awaitChecked(r0.id, ReleaseChecksState.FAILED)
        assertEquals("Failed: tests", r.checksDetail)
        assertEquals(ReleaseChecksDto("failed", "Failed: tests", r.checksSha), r.toDto().checks)
        tick()
        assertNull(s.releaseStore.get(r.id)!!.notice, "manual: shown on the page, no notice")
        assertEquals(0, comments(r.prNumber!!).count { it.contains("checks failed") }, "manual: no comment")
        val done = runBlocking { s.releases.approve(alice, r.id) }
        assertEquals(ReleaseStatus.RELEASED, done.status)
        assertFalse(done.releasedAutomatically)
    }

    @Test
    fun `checks that cannot be read hold an automatic release with a notice`() {
        gh.checksFailure = 403
        val r0 = release("u", listOf(issue("Unreadable")), mode = ReleaseMode.AUTOMATIC)
        start(r0.id)
        val r = awaitChecked(r0.id, ReleaseChecksState.UNKNOWN)
        assertTrue(r.checksDetail!!.startsWith("Could not read the checks: "), r.checksDetail)
        val noticed = awaitRelease(r.id, "notice") { it.notice != null }
        assertTrue(noticed.notice!!.contains("It is not released automatically until they can be read"), noticed.notice)
        tick()
        assertEquals(0, merges())
        gh.checksFailure = null
        tick()
        assertTrue(awaitReleased(r.id).releasedAutomatically)
    }

    // ------------------------------------------------------------ blockers are never overridden

    @Test
    fun `held issues block an automatic release until they are resolved, then it releases by itself`() {
        val ok = issue("Solid")
        val doubtful = issue("Doubtful", "escalate")
        val r0 = release("h", listOf(ok, doubtful), mode = ReleaseMode.AUTOMATIC)
        start(r0.id)
        val r = awaitRelease(r0.id, "ready, incomplete") { it.status == ReleaseStatus.READY && it.held.isNotEmpty() }
        repeat(2) { tick() }
        assertNull(s.releaseStore.get(r.id)!!.prNumber)
        assertEquals(0, merges())
        // The owner approves the held run: it merges into the release branch, and the release goes out by itself.
        runBlocking { s.orchestrator.approve(alice, r.issues.single { it.number == doubtful }.runId!!) }
        val done = awaitReleased(r.id)
        assertTrue(done.releasedAutomatically)
        assertEquals("closed", issueState(doubtful))
        assertEquals(1, merges())
    }

    @Test
    fun `a conflict blocks an automatic release until it is resolved`() {
        val n = issue("Clash", "gate")
        val r0 = release("c", listOf(n), mode = ReleaseMode.AUTOMATIC)
        start(r0.id)
        gh.conflicting += "demo-app:release/c"
        gates["Clash"] = true
        val r = awaitRelease(r0.id, "conflict") { it.status == ReleaseStatus.READY && it.mergeState == ReleaseMergeState.CONFLICT }
        eventually("conflict comment") { comments(r.prNumber!!).any { it.contains("no longer merges cleanly") } }
        repeat(2) { tick() }
        assertEquals(ReleaseStatus.READY, s.releaseStore.get(r.id)!!.status)
        assertEquals(0, merges())
        gh.conflicting -= "demo-app:release/c"
        tick()
        assertTrue(awaitReleased(r.id).releasedAutomatically)
    }

    @Test
    fun `a repository that only allows squash merges blocks an automatic release`() {
        gh.allowRebaseMerge = false
        gh.allowMergeCommit = false
        val r0 = release("sq", listOf(issue("Squash only")), mode = ReleaseMode.AUTOMATIC)
        start(r0.id)
        val r = awaitChecked(r0.id, ReleaseChecksState.NONE)
        assertEquals(ReleaseMergeMethod.UNAVAILABLE, r.mergeMethod)
        repeat(2) { tick() }
        assertEquals(ReleaseStatus.READY, s.releaseStore.get(r.id)!!.status)
        assertEquals(0, merges())
        // Merge commits allowed: the documented second choice, as for an approval.
        gh.allowMergeCommit = true
        tick()
        awaitReleased(r.id)
        assertEquals(1, gh.actions.count { it == "merge-merge demo-app #${r.prNumber}" })
    }

    @Test
    fun `an owner without a GitHub token blocks an automatic release`() {
        gh.setStatus("demo-app", "release/t", "ci", "pending")
        val r0 = release("t", listOf(issue("Tokenless")), mode = ReleaseMode.AUTOMATIC)
        start(r0.id)
        awaitChecked(r0.id, ReleaseChecksState.PENDING)
        tick() // the evaluation that saw the pending checks has finished
        val token = s.users.byId(alice.id)!!.githubTokenEnc
        s.users.setGithub(alice.id, null, null)
        gh.setStatus("demo-app", "release/t", "ci", "success")
        tick()
        val blocked = s.releaseStore.get(r0.id)!!
        assertEquals(ReleaseStatus.READY, blocked.status)
        assertTrue(blocked.notice!!.contains("no GitHub token"), blocked.notice)
        assertEquals(0, merges())
        s.users.setGithub(alice.id, "alice", token)
        tick()
        assertTrue(awaitReleased(r0.id).releasedAutomatically)
    }

    @Test
    fun `a merge GitHub refuses is not retried automatically for the same commit, but check again retries it`() {
        gh.setStatus("demo-app", "release/rf", "ci", "pending")
        val r0 = release("rf", listOf(issue("Refused")), mode = ReleaseMode.AUTOMATIC)
        start(r0.id)
        val r = awaitChecked(r0.id, ReleaseChecksState.PENDING)
        tick()
        gh.failMerge = true
        gh.setStatus("demo-app", "release/rf", "ci", "success")
        tick()
        val refused = s.releaseStore.get(r.id)!!
        assertEquals(ReleaseStatus.READY, refused.status)
        assertEquals("Merging pull request #${r.prNumber} failed: Pull Request is not mergeable", refused.notice)
        val refusedComments = { comments(r.prNumber!!).count { it.startsWith("**Agern:** merging release `rf` automatically failed") } }
        assertEquals(1, refusedComments())
        gh.failMerge = false
        repeat(2) { tick() }
        val waiting = s.releaseStore.get(r.id)!!
        assertEquals(ReleaseStatus.READY, waiting.status, "not tried again for the same commit")
        assertTrue(waiting.notice!!.startsWith("GitHub refused the automatic merge"), waiting.notice)
        assertEquals(1, refusedComments())
        val done = runBlocking { s.releases.check(alice, r.id) }
        assertEquals(ReleaseStatus.RELEASED, done.status)
        assertTrue(done.releasedAutomatically)
    }

    // ------------------------------------------------------------ restart and concurrency

    @Test
    fun `after a restart an automatic release that became green is merged`() {
        gh.setStatus("demo-app", "release/rs", "ci", "pending")
        val r0 = release("rs", listOf(issue("Across a restart")), mode = ReleaseMode.AUTOMATIC)
        start(r0.id)
        awaitChecked(r0.id, ReleaseChecksState.PENDING)
        tick()
        s.stop()
        gh.setStatus("demo-app", "release/rs", "ci", "success")
        val s2 = Services(world.cfg, s.db, gh, world.git, world.launcher)
        extraServices += s2
        s2.releaseMerger.mergeablePollMillis = 20
        s2.start()
        val done = awaitReleased(r0.id, s2)
        assertTrue(done.releasedAutomatically)
        assertEquals(alice.id, done.releasedById)
        assertEquals(1, merges())
    }

    @Test
    fun `an automatic release is merged exactly once when re-evaluations, a check and an approval race`() {
        gh.setStatus("demo-app", "release/race", "ci", "pending")
        val n = issue("Raced")
        val r0 = release("race", listOf(n), mode = ReleaseMode.AUTOMATIC)
        start(r0.id)
        awaitChecked(r0.id, ReleaseChecksState.PENDING)
        tick()
        gh.setStatus("demo-app", "release/race", "ci", "success")
        val outcomes = runBlocking {
            val jobs = (1..3).map { async(Dispatchers.Default) { runCatching { s.releaseMerger.tick() } } } +
                async(Dispatchers.Default) { runCatching { s.releaseMerger.evaluate(r0.id) } } +
                async(Dispatchers.Default) { runCatching { s.releases.check(alice, r0.id) } } +
                async(Dispatchers.Default) { runCatching { s.releases.approve(alice, r0.id) } }
            jobs.awaitAll()
        }
        for (o in outcomes) {
            o.exceptionOrNull()?.let { assertEquals("release_not_ready", (it as ApiException).code, it.toString()) }
        }
        awaitReleased(r0.id)
        assertEquals(1, merges(), gh.actions.toString())
        assertEquals(1, gh.actions.count { it == "close demo-app#$n" })
        assertEquals(1, comments(n).count { it.startsWith("**Agern: released**") })
    }

    // ------------------------------------------------------------ the setting

    @Test
    fun `the mode is chosen at creation and changed over HTTP by the people the rules allow`() = testApplication {
        application { agernModule(s) }
        world.user("bob")
        world.user("root", admin = true)
        val a = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        val root = login("root", "pw-r")

        val created = a.postJson("/api/projects/demo-app/releases", """{"name":"h1","mode":"automatic"}""")
        assertEquals(201, created.status.value, created.bodyAsText())
        assertEquals("automatic", json(created.bodyAsText()).jsonObject.str("mode"))
        assertEquals("manual", json(a.postJson("/api/projects/demo-app/releases", """{"name":"h2"}""").bodyAsText()).jsonObject.str("mode"))
        assertEquals(400, a.postJson("/api/projects/demo-app/releases", """{"name":"h3","mode":"sometimes"}""").status.value)
        val id = json(created.bodyAsText()).jsonObject.str("id")
        // Planned: anyone who can see the project, in both directions.
        assertEquals("manual", json(bob.putJson("/api/releases/$id/mode", """{"mode":"manual"}""").bodyAsText()).jsonObject.str("mode"))
        assertEquals("automatic", json(bob.putJson("/api/releases/$id/mode", """{"mode":"automatic"}""").bodyAsText()).jsonObject.str("mode"))
        assertEquals(400, bob.putJson("/api/releases/$id/mode", """{"mode":"later"}""").status.value)
        assertEquals(400, bob.putJson("/api/releases/$id/mode", """{}""").status.value)

        // Started (building, then ready with pending checks): automatic is the owner's alone; manual also an admin's.
        gh.setStatus("demo-app", "release/h1", "ci", "pending")
        assertEquals(200, a.postJson("/api/projects/demo-app/releases/assign", """{"issueNumbers":[${issue("Over HTTP")}],"releaseId":"$id"}""").status.value)
        assertEquals(200, a.postJson("/api/releases/$id/start").status.value)
        awaitChecked(UUID.fromString(id), ReleaseChecksState.PENDING)
        assertEquals(403, bob.putJson("/api/releases/$id/mode", """{"mode":"manual"}""").status.value)
        assertEquals("manual", json(root.putJson("/api/releases/$id/mode", """{"mode":"manual"}""").bodyAsText()).jsonObject.str("mode"))
        val adminAuto = root.putJson("/api/releases/$id/mode", """{"mode":"automatic"}""")
        assertEquals(403, adminAuto.status.value)
        assertTrue(adminAuto.bodyAsText().contains("Only alice, who started this release, can do this"), adminAuto.bodyAsText())
        assertEquals(403, bob.putJson("/api/releases/$id/mode", """{"mode":"automatic"}""").status.value)
        val pr = s.releaseStore.get(UUID.fromString(id))!!.prNumber!!
        awaitRelease(UUID.fromString(id), "body says manual") { gh.pullBodies[pr]!!.contains("Release mode: **manual**") }

        // Manual and green: waits. The owner switches to automatic: released.
        gh.setStatus("demo-app", "release/h1", "ci", "success")
        tick()
        assertEquals(ReleaseStatus.READY, s.releaseStore.get(UUID.fromString(id))!!.status)
        assertEquals(ReleaseChecksState.PASSED, s.releaseStore.get(UUID.fromString(id))!!.checksState, "pending manual checks are polled")
        assertEquals(200, a.putJson("/api/releases/$id/mode", """{"mode":"automatic"}""").status.value)
        val done = awaitReleased(UUID.fromString(id))
        assertTrue(done.releasedAutomatically)
        // Finished: no changes.
        assertEquals("release_not_editable", errorCode(a.putJson("/api/releases/$id/mode", """{"mode":"manual"}""")))
        val other = json(a.postJson("/api/projects/demo-app/releases", """{"name":"h4"}""").bodyAsText()).jsonObject.str("id")
        assertEquals(200, a.postJson("/api/projects/demo-app/releases/assign", """{"issueNumbers":[${issue("Cancelled one", "gate")}],"releaseId":"$other"}""").status.value)
        assertEquals(200, a.postJson("/api/releases/$other/start").status.value)
        assertEquals(200, a.postJson("/api/releases/$other/cancel").status.value)
        assertEquals("release_closed", errorCode(a.putJson("/api/releases/$other/mode", """{"mode":"automatic"}""")))
    }

    @Test
    fun `switching an automatic release to manual stops the automatic merge`() {
        gh.setStatus("demo-app", "release/sw", "ci", "pending")
        val r0 = release("sw", listOf(issue("Switched")), mode = ReleaseMode.AUTOMATIC)
        start(r0.id)
        val r = awaitChecked(r0.id, ReleaseChecksState.PENDING)
        assertEquals(ReleaseMode.MANUAL, runBlocking { s.releases.setMode(alice, r.id, "manual") }.mode)
        gh.setStatus("demo-app", "release/sw", "ci", "success")
        repeat(2) { tick() }
        val after = s.releaseStore.get(r.id)!!
        assertEquals(ReleaseStatus.READY, after.status)
        assertEquals(ReleaseChecksState.PASSED, after.checksState)
        assertEquals(0, merges())
        eventually("body says manual") { gh.pullBodies[r.prNumber!!]!!.contains("Release mode: **manual**") }
    }

    @Test
    fun `factory release mode, and no agent tool can make a release automatic`() = runBlocking {
        val bob = world.user("bob")
        val chat = s.sessions.create("demo-app", "chat", "opus", "idle", alice.id, null)
        suspend fun cmd(t: String, who: UserRecord = alice) = try { s.factory.handleCommand(chat, who, t) } catch (e: ApiException) { e.message }

        assertTrue(cmd("/factory help").contains("/factory release mode <name> manual|automatic"))
        release("cm", emptyList())
        assertEquals("Usage: /factory release mode <name> manual|automatic", cmd("/factory release mode cm"))
        assertEquals("Usage: /factory release mode <name> manual|automatic", cmd("/factory release mode cm soon"))
        assertTrue(cmd("/factory release mode CM automatic").startsWith("Release \"cm\" is released automatically"))
        assertEquals(ReleaseMode.AUTOMATIC, s.releaseStore.byName("demo-app", "cm")!!.mode)
        assertTrue(cmd("/factory releases").contains("• cm — in preparation, 0 issues, releases automatically when ready"))
        assertEquals("Release \"cm\" waits for its owner's approval once it is ready (manual).", cmd("/factory release mode cm manual", bob))

        // MCP: no tool sets the mode, and creating a release through the chat agent always makes a manual one.
        val token = s.mcpRegistry.issue(McpGrant(chat.id, alice.id, "demo-app", null, McpRegistry.CHAT_TOOLS))
        suspend fun rpc(body: String) = AppJson.parseToJsonElement(s.mcp.handle(token, body).second!!).jsonObject
        val tools = rpc("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
        assertTrue("factory_approve_release" in tools.map { it.str("name") })
        assertTrue(tools.none { it.str("name").contains("mode") || it.toString().contains("automatic") }, tools.toString())
        rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"factory_create_release","arguments":{"name":"by-agent","mode":"automatic"}}}""")
        assertEquals(ReleaseMode.MANUAL, s.releaseStore.byName("demo-app", "by-agent")!!.mode)
    }

    @Test
    fun `a project that requires a human approval refuses automatic releases (the seam for #24)`() {
        val strict = Services(world.cfg, s.db, gh, world.git, world.launcher, releaseModePolicy = ReleaseModePolicy { false })
        extraServices += strict
        strict.releaseMerger.mergeablePollMillis = 20
        assertEquals("release_mode_not_allowed", code { runBlocking { strict.releases.create(alice, "demo-app", "no", "", "automatic") } })
        val planned = runBlocking { strict.releases.create(alice, "demo-app", "no", "") }
        assertEquals("release_mode_not_allowed", code { runBlocking { strict.releases.setMode(alice, planned.id, "automatic") } })

        // An automatic release made before the project became strict is not merged automatically by it.
        gh.setStatus("demo-app", "release/before", "ci", "pending")
        val r0 = release("before", listOf(issue("Made before")), mode = ReleaseMode.AUTOMATIC)
        start(r0.id)
        awaitChecked(r0.id, ReleaseChecksState.PENDING)
        tick()
        gh.setStatus("demo-app", "release/before", "ci", "success")
        runBlocking { strict.releaseMerger.evaluate(r0.id) }
        val r = s.releaseStore.get(r0.id)!!
        assertEquals(ReleaseStatus.READY, r.status)
        assertTrue(r.notice!!.contains("requires a human approval"), r.notice)
        assertEquals(0, merges())
    }

    @Test
    fun `checks add up as GitHub reports them`() {
        fun of(statuses: List<Pair<String, String>>, runs: List<GhCheckRun>, total: Int = statuses.size, state: String = "pending") =
            ChecksSummary.of(GhCombinedStatus(state, total, statuses.map { GhCommitStatus(it.first, it.second) }), runs)
        assertEquals(ChecksSummary("none", "No checks"), of(emptyList(), emptyList()))
        assertEquals("passed", of(listOf("ci" to "success"), listOf(GhCheckRun("b", "completed", "neutral"), GhCheckRun("c", "completed", "skipped"))).state)
        assertEquals(ChecksSummary("pending", "Pending: ci, b"), of(listOf("ci" to "pending"), listOf(GhCheckRun("b", "queued"))))
        assertEquals(ChecksSummary("failed", "Failed: ci, x"), of(listOf("ci" to "error"), listOf(GhCheckRun("x", "completed", "timed_out"), GhCheckRun("y", "in_progress"))))
        for (c in listOf("failure", "cancelled", "action_required", "stale")) {
            assertEquals("failed", of(emptyList(), listOf(GhCheckRun("r", "completed", c))).state, c)
        }
        // More statuses than listed: the combined state decides for the rest.
        assertEquals(ChecksSummary("failed", "Failed: commit status"), of(listOf("a" to "success"), emptyList(), total = 150, state = "failure"))
    }

    @Test
    fun `the fake GitHub reads checks from its development directory`() {
        val dir = java.nio.file.Files.createTempDirectory("agern-checks")
        try {
            gh.checksDir = dir
            java.nio.file.Files.createDirectories(dir.resolve("demo-app/release"))
            java.nio.file.Files.writeString(
                dir.resolve("demo-app/release/x.json"),
                """{"statuses":{"ci/e2e":"pending"},"checkRuns":[{"name":"build","status":"completed","conclusion":"failure"}]}""",
            )
            val c = gh.client("fake-alice")
            val sha = "sha-release/x-3"
            assertEquals(ChecksSummary("failed", "Failed: build"), ChecksSummary.of(c.combinedStatus("test-org", "demo-app", sha), c.checkRuns("test-org", "demo-app", sha)))
            assertEquals("none", ChecksSummary.of(c.combinedStatus("test-org", "demo-app", "sha-release/y-1"), c.checkRuns("test-org", "demo-app", "sha-release/y-1")).state)
            // Set in memory, the test's own checks win.
            gh.setStatus("demo-app", "release/x", "ci/e2e", "success")
            assertEquals(listOf(GhCommitStatus("ci/e2e", "success")), c.combinedStatus("test-org", "demo-app", sha).statuses)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------ HTTP helpers

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

    private suspend fun HttpClient.postJson(path: String, body: String = "{}"): HttpResponse =
        post(path) { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.putJson(path: String, body: String): HttpResponse =
        put(path) { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody(body) }
}
