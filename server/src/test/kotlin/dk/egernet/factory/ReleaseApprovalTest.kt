package dk.egernet.factory

import dk.egernet.factory.agent.FeedItem
import dk.egernet.factory.db.ReleaseRecord
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.mcp.McpGrant
import dk.egernet.factory.mcp.McpRegistry
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.nio.file.Files
import java.time.Duration
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

/** The release pull request, owner approval and the merge into the default branch (docs/ARCHITECTURE.md §6 "Releasing"). */
class ReleaseApprovalTest {
    private val clock = MutableClock(java.time.Instant.now())
    private val world = TestWorld(twoFactor = false, clock = clock)
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

    private fun issue(title: String, b: String = "ok", repo: String = "demo-app"): Int {
        behaviour[title] = b
        return gh.addIssue(repo, title, "body").number
    }

    private fun release(name: String, issues: List<Int>, repo: String = "demo-app", by: UserRecord = alice, description: String = ""): ReleaseRecord =
        runBlocking {
            val r = s.releases.create(by, repo, name, description)
            if (issues.isNotEmpty()) s.releases.assign(by, repo, issues, r.id)
            s.releaseStore.get(r.id)!!
        }

    private fun start(id: UUID, by: UserRecord = alice) = runBlocking { s.releases.start(by, id) }

    private fun awaitRelease(id: UUID, what: String = "condition", services: Services = s, cond: (ReleaseRecord) -> Boolean): ReleaseRecord =
        runBlocking {
            withTimeout(15_000) {
                while (true) {
                    val r = services.releaseStore.get(id)!!
                    if (cond(r)) return@withTimeout r
                    delay(20)
                }
                @Suppress("UNREACHABLE_CODE") error(what)
            }
        }

    /** Ready, with the pull request handled (opened when complete, or the notice set). */
    private fun awaitReady(id: UUID, services: Services = s) = awaitRelease(id, "ready", services) {
        it.status == ReleaseStatus.READY && (it.prNumber != null && it.mergeState != ReleaseMergeState.UNKNOWN || !it.complete || it.notice != null)
    }

    /** A release of [titles] built to `ready` through the real start. */
    private fun readyRelease(name: String, vararg titles: String, description: String = ""): ReleaseRecord {
        val nums = titles.map { issue(it) }
        val r = release(name, nums, description = description)
        start(r.id)
        return awaitReady(r.id)
    }

    /** Waits until [cond] holds (side effects that follow a state change, e.g. comments). */
    private fun eventually(what: String, cond: () -> Boolean) = runBlocking {
        withTimeoutOrNull(10_000) { while (!cond()) delay(20) } ?: error("not reached: $what")
    }

    private fun approve(id: UUID, by: UserRecord = alice) = runBlocking { s.releases.approve(by, id) }
    private fun check(id: UUID, by: UserRecord = alice) = runBlocking { s.releases.check(by, id) }
    private fun code(block: () -> Unit): String = assertFailsWith<ApiException> { block() }.code
    private fun issueState(n: Int, repo: String = "demo-app") = gh.client("fake-alice").getIssue("test-org", repo, n)!!.state
    private fun comments(n: Int, repo: String = "demo-app") = gh.client("fake-alice").listComments("test-org", repo, n).map { it.body ?: "" }

    // ------------------------------------------------------------ ready and the pull request

    @Test
    fun `a built release becomes ready, frees the project and opens one pull request listing its issues`() {
        val r = readyRelease("1.0", "Alpha", "Beta", description = "The first release.")
        val (a, b) = r.issues.map { it.number }
        assertEquals(ReleaseStatus.READY, r.status)
        assertNotNull(r.readyAt)
        val pr = r.prNumber!!
        assertTrue("pr demo-app release/1.0 -> main #$pr" in gh.actions, gh.actions.toString())
        assertEquals(1, gh.actions.count { it.startsWith("pr demo-app release/1.0 -> ") })
        assertEquals("https://github.com/test-org/demo-app/pull/$pr", r.prUrl)
        assertEquals("main", r.targetBranch)
        assertEquals(ReleaseMergeState.CLEAN, r.mergeState)
        assertEquals(ReleaseMergeMethod.REBASE, r.mergeMethod)
        assertNull(r.notice)
        val body = gh.pullBodies[pr]!!
        assertTrue(body.startsWith("Release **1.0** of test-org/demo-app, built by Agern for @alice."), body)
        assertTrue(body.contains("The first release."), body)
        val prs = r.issues.associate { it.number to it.prNumber!! }
        assertTrue(body.contains("- #$a Alpha (pull request #${prs[a]})\n- #$b Beta (pull request #${prs[b]})"), body)
        assertFalse(Regex("(?i)\\b(close[sd]?|fix(es|ed)?|resolve[sd]?) #").containsMatchIn(body), body)
        assertTrue(body.contains("/p/demo-app/releases/${r.id}"), body)
        assertEquals("Release 1.0", gh.pullMeta[pr]!!.title)
        // The ready comment, once, on the pull request.
        eventually("ready comment") { comments(pr).any { it.contains("is ready to release") } }
        assertEquals(1, comments(pr).count { it.startsWith("**Agern: release `1.0` is ready to release.** 2 issue(s) merged. @alice can approve it") })
        check(r.id)
        assertEquals(1, comments(pr).count { it.contains("is ready to release") }, "a repeated check posts nothing")
        assertEquals(0, gh.actions.count { it.startsWith("update-pr") }, "the body did not change")
        // Issues stay open; the project's building slot is free.
        assertEquals("open", issueState(a))
        assertEquals("open", issueState(b))
        val dto = r.toDto()
        assertEquals(prs[a], dto.issues.first().prNumber)
        assertNotNull(dto.issues.first().prUrl)
        val next = release("1.1", listOf(issue("Gamma")))
        assertEquals(ReleaseStatus.BUILDING, start(next.id).status)
    }

    @Test
    fun `owner approval rebases one commit per issue, then closes the issues, deletes the branch and keeps the release`() = testApplication {
        application { agernModule(s) }
        val skippedIssue = issue("Closed early")
        val nums = listOf(issue("Alpha"), issue("Beta"), skippedIssue)
        val r0 = release("2.0", nums)
        // Closed before the start (skipped), reopened afterwards: the release must not close it.
        gh.client("fake-alice").closeIssue("test-org", "demo-app", skippedIssue)
        start(r0.id)
        val r = awaitReady(r0.id)
        assertEquals("skipped", r.issues.single { it.number == skippedIssue }.state)
        synchronized(gh) { gh.repos["demo-app"]!!.issues.replaceAll { if (it.number == skippedIssue) it.copy(state = "open") else it } }
        val (a, b) = nums
        val pr = r.prNumber!!
        assertTrue(gh.pullBodies[pr]!!.contains("Not included (skipped): #$skippedIssue"))
        assertEquals("open", issueState(a))

        val alice = login("alice", "pw-a")
        val res = alice.postJson("/api/releases/${r.id}/approve")
        assertEquals(200, res.status.value, res.bodyAsText())
        val o = json(res.bodyAsText()).jsonObject
        assertEquals("released", o.str("status"))
        assertEquals("alice", o["releasedBy"]!!.jsonObject.str("username"))
        assertNotNull(o["releasedAt"]!!.jsonPrimitive.content)
        assertEquals("rebase", o.str("mergeMethod"))
        val sha = o.str("mergeCommit")

        // One commit per issue on the default branch, in build order; never squashed into one.
        assertTrue("merge-rebase demo-app #$pr" in gh.actions, gh.actions.toString())
        assertTrue(gh.actions.none { it == "merge demo-app #$pr" || it.startsWith("merge-merge") })
        assertEquals(listOf("Initial commit", "Alpha (#$a)", "Beta (#$b)"), gh.commitsOf("demo-app", "main"))
        // Issues closed only now, with a comment naming the release and the commit; the skipped one stays open.
        for (n in listOf(a, b)) {
            assertEquals("closed", issueState(n))
            val c = comments(n).last()
            assertTrue(c.startsWith("**Agern: released** in release `2.0`: pull request https://github.com/test-org/demo-app/pull/$pr was rebased onto `main`"), c)
            assertTrue(c.contains(sha) && c.contains("@alice"), c)
        }
        assertTrue(gh.actions.indexOf("merge-rebase demo-app #$pr") < gh.actions.indexOf("close demo-app#$a"))
        assertEquals("open", issueState(skippedIssue))
        // The branch is deleted; the release is history.
        assertTrue("delete-branch demo-app release/2.0" in gh.actions)
        assertNull(gh.client("fake-alice").branchSha("test-org", "demo-app", "release/2.0"))
        val listed = json(alice.get("/api/projects/demo-app/releases").bodyAsText()).jsonArray.map { it.jsonObject }.single { it.str("id") == r.id.toString() }
        assertEquals("released", listed.str("status"))
        val stored = s.releaseStore.get(r.id)!!
        assertFalse(stored.cleanupPending)
        assertNull(stored.notice)
        assertNotNull(stored.endedAt)
        // A second approval: not ready any more.
        assertEquals("release_not_ready", errorCode(alice.postJson("/api/releases/${r.id}/approve")))
    }

    @Test
    fun `the merge method follows the repository's settings and a forbidden method is a clear error, never a fallback`() {
        // Rebase merging disabled: a merge commit (each issue's commit stays, plus the merge commit).
        gh.allowRebaseMerge = false
        val m = readyRelease("m", "Uno")
        assertEquals(ReleaseMergeMethod.MERGE, m.mergeMethod)
        approve(m.id)
        assertTrue("merge-merge demo-app #${m.prNumber}" in gh.actions)
        val n = m.issues.single().number
        assertEquals(listOf("Initial commit", "Uno (#$n)", "Release m (#${m.prNumber})"), gh.commitsOf("demo-app", "main"))

        // Neither allowed: refused before anything is merged.
        gh.allowMergeCommit = false
        val none = readyRelease("none", "Dos")
        assertEquals(ReleaseMergeMethod.UNAVAILABLE, none.mergeMethod)
        val e = assertFailsWith<ApiException> { approve(none.id) }
        assertEquals("release_merge_method_unavailable", e.code)
        assertTrue(e.message.contains("only allows squash merges"), e.message)
        assertEquals(1, gh.actions.count { it.startsWith("merge-") }, "nothing merged")
        assertEquals(ReleaseStatus.READY, s.releaseStore.get(none.id)!!.status)

        // The settings are hidden from the token (null = allowed) but GitHub refuses rebase: a clear error, no merge commit.
        gh.hideMergeSettings = true
        gh.allowMergeCommit = true
        val hidden = check(none.id)
        assertEquals(ReleaseMergeMethod.REBASE, hidden.mergeMethod)
        val refused = assertFailsWith<ApiException> { approve(none.id) }
        assertEquals("release_merge_method_unavailable", refused.code)
        assertTrue(refused.message.contains("does not allow rebase merging") && refused.message.contains("Nothing was merged"), refused.message)
        assertEquals(1, gh.actions.count { it.startsWith("merge-") }, "no fallback to a merge commit")
        val after = s.releaseStore.get(none.id)!!
        assertEquals(ReleaseStatus.READY, after.status)
        assertTrue(after.notice!!.contains("does not allow rebase merging"), after.notice)
        assertTrue(gh.actions.none { it == "close demo-app#${none.issues.single().number}" || it.startsWith("delete-branch demo-app release/none") })
    }

    @Test
    fun `only the owner approves and checks, over HTTP`() = testApplication {
        application { agernModule(s) }
        world.user("bob")
        world.user("root", admin = true)
        val r = readyRelease("own", "Mine")
        val lab = release("lab", emptyList(), repo = "private-lab")
        val a = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        val root = login("root", "pw-r")
        for (path in listOf("approve", "check")) {
            val res = bob.postJson("/api/releases/${r.id}/$path")
            assertEquals(403, res.status.value, path)
            assertEquals("forbidden", errorCode(res))
            assertTrue(res.bodyAsText().contains("Only alice, who started this release, can do this"))
            assertEquals(403, root.postJson("/api/releases/${r.id}/$path").status.value, "admins may stop, never drive: $path")
            assertEquals(404, bob.postJson("/api/releases/${lab.id}/$path").status.value, path)
            assertEquals(403, a.postJson("/api/releases/${r.id}/$path", csrf = false).status.value, path)
            assertEquals("release_not_ready", errorCode(a.postJson("/api/releases/${lab.id}/$path")), path)
        }
        assertTrue(gh.actions.none { it.startsWith("merge-") })
        val checked = a.postJson("/api/releases/${r.id}/check")
        assertEquals(200, checked.status.value)
        assertEquals("clean", json(checked.bodyAsText()).jsonObject.str("mergeState"))
        // An admin may still cancel it (stop, never drive).
        assertEquals("cancelled", json(root.postJson("/api/releases/${r.id}/cancel").bodyAsText()).jsonObject.str("status"))
        assertEquals("release_not_ready", errorCode(a.postJson("/api/releases/${r.id}/approve")))
    }

    @Test
    fun `a conflicting release branch is not merged until the conflict is resolved`() {
        val n = issue("Clash", "gate")
        val r0 = release("c", listOf(n))
        start(r0.id)
        gh.conflicting += "demo-app:release/c"
        gates["Clash"] = true
        val r = awaitReady(r0.id)
        val pr = r.prNumber!!
        assertEquals(ReleaseMergeState.CONFLICT, r.mergeState)
        val conflictComments = { comments(pr).count { it.startsWith("**Agern:** `release/c` no longer merges cleanly into `main`") } }
        eventually("conflict comment") { conflictComments() > 0 }
        assertEquals(1, conflictComments())
        assertEquals(0, comments(pr).count { it.contains("is ready to release") }, "not announced while it conflicts")
        val e = assertFailsWith<ApiException> { approve(r.id) }
        assertEquals("release_conflict", e.code)
        assertEquals("`release/c` no longer merges cleanly into `main`. Nothing was merged.", e.message)
        assertTrue(gh.actions.none { it.startsWith("merge-") || it.startsWith("close ") || it.startsWith("delete-branch") }, gh.actions.toString())
        assertEquals(ReleaseStatus.READY, s.releaseStore.get(r.id)!!.status)
        assertEquals("open", issueState(n))
        assertEquals(ReleaseMergeState.CONFLICT, check(r.id).mergeState)
        assertEquals(1, conflictComments(), "the same state posts nothing again")

        // Resolved on the branch: check again, then approve.
        gh.conflicting -= "demo-app:release/c"
        assertEquals(ReleaseMergeState.CLEAN, check(r.id).mergeState)
        eventually("ready comment") { comments(pr).any { it.contains("is ready to release") } }
        assertEquals(1, comments(pr).count { it.contains("is ready to release") })
        assertEquals(ReleaseStatus.RELEASED, approve(r.id).status)
        assertEquals("closed", issueState(n))
    }

    @Test
    fun `mergeability still being computed is read again, and a conflict found at merge time is reported`() {
        val r0 = release("slow", listOf(issue("Slow", "gate")))
        start(r0.id)
        gh.mergeableComputing = 2
        gates["Slow"] = true
        val r = awaitReady(r0.id)
        assertEquals(ReleaseMergeState.CLEAN, r.mergeState)
        assertEquals(0, gh.mergeableComputing)
        // Still computing after all re-reads: unknown, and approval leaves the decision to GitHub.
        gh.mergeableComputing = 10
        assertEquals(ReleaseMergeState.UNKNOWN, check(r.id).mergeState)
        gh.mergeableComputing = 0
        // A conflict that appears between the check and the merge.
        gh.mergeableComputing = 5
        gh.conflicting += "demo-app:release/slow"
        assertEquals("release_conflict", code { approve(r.id) })
        assertEquals(ReleaseMergeState.CONFLICT, s.releaseStore.get(r.id)!!.mergeState)
        assertTrue(gh.actions.none { it.startsWith("merge-") })
    }

    @Test
    fun `a merge GitHub refuses for another reason leaves the release ready with a notice`() {
        val r = readyRelease("f", "Fine")
        gh.failMerge = true
        val e = assertFailsWith<ApiException> { approve(r.id) }
        assertEquals("release_merge_failed", e.code)
        val after = s.releaseStore.get(r.id)!!
        assertEquals(ReleaseStatus.READY, after.status)
        assertEquals("Merging pull request #${r.prNumber} failed: Pull Request is not mergeable", after.notice)
        assertEquals("open", issueState(r.issues.single().number))
        gh.failMerge = false
        val done = approve(r.id)
        assertEquals(ReleaseStatus.RELEASED, done.status)
        assertNull(done.notice)
    }

    @Test
    fun `held issues make a ready release incomplete until they are approved or moved out`() {
        val ok = issue("Solid")
        val doubtful = issue("Doubtful", "escalate")
        val r0 = release("h", listOf(ok, doubtful))
        start(r0.id)
        val r = awaitReady(r0.id)
        assertEquals("held", r.issues.single { it.number == doubtful }.state)
        assertFalse(r.complete)
        assertNull(r.prNumber, "no pull request while an issue is held")
        eventually("held comment") { comments(doubtful).any { it.contains("this issue is held") } }
        assertEquals(1, comments(doubtful).count { it.startsWith("**Agern:** release `h` has finished building, but this issue is held.") })
        val e = assertFailsWith<ApiException> { approve(r.id) }
        assertEquals("release_incomplete", e.code)
        assertTrue(e.message.startsWith("1 issue(s) are held: #$doubtful."), e.message)
        // The owner approves the held run: it merges into the release branch, and the release is complete.
        runBlocking { s.orchestrator.approve(alice, r.issues.single { it.number == doubtful }.runId!!) }
        val complete = awaitRelease(r.id, "complete with a pull request") { it.complete && it.prNumber != null && it.mergeState == ReleaseMergeState.CLEAN }
        assertTrue(gh.pullBodies[complete.prNumber!!]!!.contains("#$doubtful Doubtful"))
        assertEquals(ReleaseStatus.RELEASED, approve(r.id).status)

        // Moving a held issue out also completes a release.
        val ok2 = issue("Solid two")
        val held2 = issue("Doubtful two", "escalate")
        val r2 = release("h2", listOf(ok2, held2))
        start(r2.id)
        val ready2 = awaitReady(r2.id)
        val heldRun = ready2.issues.single { it.number == held2 }.runId!!
        runBlocking { s.releases.assign(alice, "demo-app", listOf(held2), null) }
        assertEquals(RunStatus.CANCELLED, world.awaitRun(heldRun, setOf(RunStatus.CANCELLED)).status)
        val out = awaitRelease(r2.id, "pull request") { it.prNumber != null && it.mergeState == ReleaseMergeState.CLEAN }
        assertEquals(listOf(ok2), out.issues.map { it.number })
        assertFalse(gh.pullBodies[out.prNumber!!]!!.contains("#$held2"))
        assertEquals(ReleaseStatus.RELEASED, approve(r2.id).status)
        assertEquals("open", issueState(held2), "an issue moved out is not released")
    }

    @Test
    fun `a release whose issues were all skipped has nothing to release`() {
        val n = issue("Gone")
        val r0 = release("empty", listOf(n))
        gh.client("fake-alice").closeIssue("test-org", "demo-app", n)
        start(r0.id)
        val r = awaitReady(r0.id)
        assertEquals("skipped", r.issues.single().state)
        assertNull(r.prNumber)
        val e = assertFailsWith<ApiException> { approve(r.id) }
        assertEquals("release_empty", e.code)
        assertEquals("Nothing was merged into the release branch, so there is nothing to release.", e.message)
        assertTrue(gh.actions.none { it.startsWith("pr demo-app release/empty") })
    }

    @Test
    fun `two approvals at the same time merge once`() {
        val r = readyRelease("twice", "Once")
        val bob = world.user("bob")
        val outcomes = runBlocking {
            (1..2).map { async(Dispatchers.Default) { runCatching { s.releases.approve(alice, r.id) } } }.awaitAll()
        }
        assertEquals(1, outcomes.count { it.isSuccess }, outcomes.toString())
        assertEquals("release_not_ready", (outcomes.single { it.isFailure }.exceptionOrNull() as ApiException).code)
        assertEquals(1, gh.actions.count { it == "merge-rebase demo-app #${r.prNumber}" })
        assertEquals(1, gh.actions.count { it == "close demo-app#${r.issues.single().number}" })
        assertEquals("forbidden", code { runBlocking { s.releases.approve(bob, r.id) } })
    }

    @Test
    fun `a pull request merged or closed on GitHub is reconciled by a check`() {
        val r = readyRelease("gh", "Outside")
        gh.closePull(r.prNumber!!)
        val reopened = check(r.id)
        assertNotNull(reopened.prNumber)
        assertTrue(reopened.prNumber != r.prNumber, "a closed pull request is replaced by a new one")
        assertEquals(ReleaseStatus.READY, reopened.status)

        gh.mergeOnGitHub(reopened.prNumber!!)
        val done = check(r.id)
        assertEquals(ReleaseStatus.RELEASED, done.status)
        assertNull(done.releasedById, "merged outside Agern")
        assertNotNull(done.mergeSha)
        assertEquals("closed", issueState(r.issues.single().number))
        assertTrue(comments(r.issues.single().number).last().contains("It was merged on GitHub."))
        assertTrue("delete-branch demo-app release/gh" in gh.actions)
        assertTrue(gh.actions.none { it.startsWith("merge-") }, "Agern did not merge it")
        assertEquals("release_not_ready", code { check(r.id) })
    }

    // ------------------------------------------------------------ editing rules, cancel, names

    @Test
    fun `editing a ready release keeps its pull request up to date and a released one is read-only`() = testApplication {
        application { agernModule(s) }
        val a = login("alice", "pw-a")
        val r = readyRelease("e", "First")
        val pr = r.prNumber!!
        val first = r.issues.single().number

        assertEquals("release_name_locked", errorCode(a.putJson("/api/releases/${r.id}", """{"name":"other","description":""}""")))
        assertEquals(200, a.putJson("/api/releases/${r.id}", """{"name":"e","description":"Now described"}""").status.value)
        awaitRelease(r.id, "body updated") { gh.pullBodies[pr]!!.contains("Now described") }
        assertTrue("update-pr demo-app #$pr" in gh.actions)
        assertEquals("release_active", errorCode(a.del("/api/releases/${r.id}")))
        assertEquals("release_issue_not_held", errorCode(a.postJson("/api/releases/${r.id}/issues/$first/retry")))
        assertEquals("release_not_building", errorCode(a.postJson("/api/releases/${r.id}/pause")))
        assertEquals("release_issue_started", errorCode(a.assign(listOf(first), null)), "a merged issue cannot leave")

        // Adding an issue builds again; the same pull request then lists it.
        val second = issue("Second")
        assertEquals(200, a.assign(listOf(second), r.id.toString()).status.value)
        val again = awaitRelease(r.id, "ready again") { it.status == ReleaseStatus.READY && it.issues.all { i -> i.state == "merged" } && it.mergeState == ReleaseMergeState.CLEAN }
        assertEquals(pr, again.prNumber)
        awaitRelease(r.id, "body lists both") { gh.pullBodies[pr]!!.contains("#$second Second") }
        eventually("announced again") { comments(pr).count { it.contains("is ready to release") } == 2 }

        assertEquals(200, a.postJson("/api/releases/${r.id}/approve").status.value)
        val other = release("other", listOf(issue("Elsewhere")))
        for (res in listOf(
            a.putJson("/api/releases/${r.id}", """{"name":"e","description":"changed"}"""),
            a.del("/api/releases/${r.id}"),
            a.assign(listOf(other.issues.single().number), r.id.toString()),
            a.assign(listOf(first), null),
            a.putJson("/api/releases/${r.id}/issues/order", """{"issueNumbers":[$second,$first]}"""),
            a.postJson("/api/releases/${r.id}/cancel"),
            a.postJson("/api/releases/${r.id}/issues/$first/retry"),
        )) {
            assertEquals(409, res.status.value, res.bodyAsText())
            assertEquals("release_not_editable", errorCode(res), res.bodyAsText())
        }
        assertTrue(a.postJson("/api/releases/${r.id}/cancel").bodyAsText().contains("is released and kept as history"))
        // A closed issue cannot be planned at all; reopened, it still cannot leave the released release.
        assertEquals("issue_closed", errorCode(a.assign(listOf(first), other.id.toString())))
        synchronized(gh) { gh.repos["demo-app"]!!.issues.replaceAll { if (it.number == first) it.copy(state = "open") else it } }
        assertEquals("release_not_editable", errorCode(a.assign(listOf(first), other.id.toString())))
        // The name stays taken: a released release is history.
        assertEquals("release_name_taken", errorCode(a.postJson("/api/projects/demo-app/releases", """{"name":"E"}""")))
    }

    @Test
    fun `cancelling a ready release leaves its pull request and branch, and frees the name once deleted`() {
        val r = readyRelease("x", "Abandoned")
        val pr = r.prNumber!!
        runBlocking { s.releases.cancel(alice, r.id) }
        assertEquals(ReleaseStatus.CANCELLED, s.releaseStore.get(r.id)!!.status)
        assertEquals(1, comments(pr).count { it.startsWith("**Agern:** release `x` was cancelled in Agern by @alice. Agern will not merge this pull request") })
        assertEquals("open", gh.pullMeta[pr]!!.state)
        assertNotNull(gh.client("fake-alice").branchSha("test-org", "demo-app", "release/x"), "the branch stays")
        assertTrue(gh.actions.none { it.startsWith("merge-") || it.startsWith("delete-branch") })
        assertEquals("open", issueState(r.issues.single().number))

        // The name is free again once the cancelled release is deleted; the old branch must go first (by a person).
        runBlocking { s.releases.delete(alice, r.id) }
        val again = release("x", listOf(issue("Second try")))
        assertEquals("release_branch_exists", code { start(again.id) })
        gh.client("fake-alice").deleteBranch("test-org", "demo-app", "release/x")
        assertEquals("closed", gh.pullMeta[pr]!!.state, "GitHub closes a pull request whose branch is deleted")
        start(again.id)
        val ready = awaitReady(again.id)
        assertTrue(ready.prNumber != pr)
        assertEquals(ReleaseStatus.RELEASED, approve(again.id).status)
    }

    // ------------------------------------------------------------ feed, commands, tools

    @Test
    fun `ready and released are announced on the feed`() = runBlocking {
        val frames = CopyOnWriteArrayList<ReleaseDto>()
        val collector = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            s.bus.feed.collect { if (it is FeedItem.Release) frames += it.release }
        }
        val r = readyRelease("feed", "Framed")
        approve(r.id)
        withTimeout(5_000) { while (frames.none { it.status == "released" && it.id == r.id.toString() }) delay(10) }
        collector.cancelAndJoin()
        val mine = frames.filter { it.id == r.id.toString() }
        val ready = mine.first { it.status == "ready" }
        assertNotNull(mine.firstOrNull { it.status == "ready" && it.prNumber != null && it.mergeState == "clean" })
        assertTrue(mine.indexOf(ready) < mine.indexOfFirst { it.status == "released" })
        assertEquals(mine.map { it.updatedAt }.sorted(), mine.map { it.updatedAt })
        assertEquals("alice", mine.last().releasedBy?.username)
    }

    @Test
    fun `factory release approve and the factory_approve_release tool`() = runBlocking {
        val bob = world.user("bob")
        val chat = s.sessions.create("demo-app", "chat", "opus", "idle", alice.id, null)
        val bobChat = s.sessions.create("demo-app", "chat", "opus", "idle", bob.id, null)
        suspend fun cmd(t: String, who: UserRecord = alice, session: dk.egernet.factory.db.SessionRecord = chat) =
            try { s.factory.handleCommand(session, who, t) } catch (e: ApiException) { e.message }

        assertTrue(cmd("/factory help").contains("/factory release approve <name>"))
        assertEquals("Usage: /factory release approve <name>", cmd("/factory release approve"))
        val r = readyRelease("cmd", "By command")
        val listing = cmd("/factory releases")
        assertTrue(listing.contains("• cmd — ready to release, 1 of 1 merged") && listing.contains("pull request ${r.prUrl}"), listing)
        assertTrue(cmd("/factory release approve cmd", bob, bobChat).contains("Only alice, who started this release, can do this"))
        assertEquals(
            "Release \"cmd\" is released: pull request ${r.prUrl} merged into main, 1 issue closed.",
            cmd("/factory release approve CMD"),
        )
        assertTrue(cmd("/factory releases").contains("• cmd — released, 1 of 1 merged"))

        // MCP: chat tokens only.
        val chatToken = s.mcpRegistry.issue(McpGrant(chat.id, alice.id, "demo-app", null, McpRegistry.CHAT_TOOLS))
        val stageToken = s.mcpRegistry.issue(McpGrant(chat.id, alice.id, "demo-app", UUID.randomUUID(), McpRegistry.STAGE_TOOLS))
        val runChat = s.mcpRegistry.issue(McpGrant(chat.id, alice.id, "demo-app", UUID.randomUUID(), McpRegistry.CHAT_TOOLS))
        val bobToken = s.mcpRegistry.issue(McpGrant(bobChat.id, bob.id, "demo-app", null, McpRegistry.CHAT_TOOLS))
        suspend fun rpc(token: String, body: String) = json(s.mcp.handle(token, body).second!!).jsonObject
        suspend fun call(token: String, args: String): JsonObject =
            rpc(token, """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"factory_approve_release","arguments":$args}}""")["result"]!!.jsonObject
        fun JsonObject.text() = this["content"]!!.jsonArray[0].jsonObject.str("text")
        fun JsonObject.isError() = this["isError"]?.jsonPrimitive?.content == "true"
        fun names(token: String) = runBlocking {
            rpc(token, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject.str("name") }
        }
        assertTrue("factory_approve_release" in names(chatToken))
        assertFalse("factory_approve_release" in names(stageToken))

        val t = readyRelease("tool", "By tool")
        assertTrue(call(stageToken, """{"release":"tool"}""").isError())
        assertTrue(call(runChat, """{"release":"tool"}""").isError())
        val refused = call(bobToken, """{"release":"tool"}""")
        assertTrue(refused.isError() && refused.text().contains("Only alice"), refused.toString())
        assertTrue(call(chatToken, "{}").isError())
        assertEquals(ReleaseStatus.READY, s.releaseStore.get(t.id)!!.status)
        assertEquals("Release \"tool\" is released: pull request ${t.prUrl} merged into main, 1 issue closed.", call(chatToken, """{"release":"${t.id}"}""").text())
        assertEquals(ReleaseStatus.RELEASED, s.releaseStore.get(t.id)!!.status)
    }

    // ------------------------------------------------------------ scheduled start, restart, workspaces

    @Test
    fun `a scheduled release reaches the same ready and approval flow`() {
        val n = issue("Overnight")
        val r = release("night", listOf(n))
        runBlocking { s.releases.schedule(alice, r.id, clock.now.plus(Duration.ofHours(1)).toString()) }
        clock.advance(Duration.ofHours(2))
        runBlocking { s.releaseScheduler.tick() }
        val ready = awaitReady(r.id)
        assertEquals(alice.id, ready.ownerId)
        assertNotNull(ready.prNumber)
        assertEquals(ReleaseStatus.RELEASED, approve(r.id).status)
        assertEquals("closed", issueState(n))
    }

    @Test
    fun `after a restart every new state carries on`() {
        // (1) Ready, but the pull request could not be opened before the restart.
        val noPr = issue("No pull request yet", "gate")
        val r1 = release("r1", listOf(noPr))
        start(r1.id)
        // Its run's own pull request exists once the approver runs.
        eventually("approver of r1") { world.launcher.byRole("approver").any { (it.spec.prompt ?: "").contains("No pull request yet") } }
        gh.failCreatePull = true
        gates["No pull request yet"] = true
        val stuck = awaitRelease(r1.id, "notice") { it.status == ReleaseStatus.READY && it.notice != null }
        assertTrue(stuck.notice!!.startsWith("Could not open the release pull request"), stuck.notice)
        assertNull(stuck.prNumber)
        gh.failCreatePull = false
        // (2) Ready, and its pull request is merged on GitHub while the server is down.
        val r2 = readyRelease("r2", "Merged while down")
        // (3) Released, but the clean-up (closing issues, deleting the branch) did not happen before the restart.
        val r3 = readyRelease("r3", "Cleanup pending")
        // (4) Released and done: untouched.
        val r4 = readyRelease("r4", "Done before")
        approve(r4.id)
        val actionsBefore = gh.actions.size

        s.stop()
        gh.mergeOnGitHub(r2.prNumber!!)
        assertTrue(s.releaseStore.markReleased(r3.id, "rebase", "sha-x", alice.id))
        val s2 = Services(world.cfg, s.db, gh, world.git, world.launcher)
        extraServices += s2
        s2.releaseMerger.mergeablePollMillis = 20
        s2.start()

        val after1 = awaitRelease(r1.id, "pull request after restart", s2) { it.prNumber != null && it.mergeState == ReleaseMergeState.CLEAN }
        assertNull(after1.notice)
        assertEquals(ReleaseStatus.READY, after1.status)
        val after2 = awaitRelease(r2.id, "released after restart", s2) { it.status == ReleaseStatus.RELEASED && !it.cleanupPending }
        assertNull(after2.releasedById)
        assertEquals("closed", issueState(after2.issues.single().number))
        val after3 = awaitRelease(r3.id, "clean-up after restart", s2) { !it.cleanupPending }
        assertEquals("closed", issueState(after3.issues.single().number))
        assertTrue("delete-branch demo-app release/r3" in gh.actions)
        assertEquals(1, comments(after3.issues.single().number).count { it.startsWith("**Agern: released**") })
        assertEquals(1, comments(r4.issues.single().number).count { it.startsWith("**Agern: released**") }, "a finished release is left alone")
        assertTrue(gh.actions.drop(actionsBefore).none { it.contains("release/r4") })
        assertEquals(ReleaseStatus.RELEASED, runBlocking { s2.releases.approve(alice, r1.id) }.status)
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

    private suspend fun HttpClient.postJson(path: String, body: String = "{}", csrf: Boolean = true): HttpResponse =
        post(path) { if (csrf) header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.putJson(path: String, body: String): HttpResponse =
        put(path) { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.del(path: String): HttpResponse = delete(path) { header("X-Factory-Request", "1") }

    private suspend fun HttpClient.assign(numbers: List<Int>, releaseId: String?, repo: String = "demo-app"): HttpResponse =
        postJson("/api/projects/$repo/releases/assign", """{"issueNumbers":$numbers,"releaseId":${releaseId?.let { "\"$it\"" } ?: "null"}}""")
}

/** Releasing leaves no worktree or local branch behind, with the real git CLI (docs/ARCHITECTURE.md §6 "Worktrees and branches"). */
class ReleaseWorkspaceTest {
    private val world = TestWorld(twoFactor = false, realGit = true)
    private val s = world.services
    private val alice = world.user("alice")

    @AfterTest fun tearDown() = world.closeAndReleaseDb()

    private fun git(dir: java.nio.file.Path, vararg args: String): String {
        val pb = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid") + args)
            .directory(dir.toFile()).redirectErrorStream(true)
        pb.environment()["HOME"] = s.workspaces.home("alice").toString()
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "git ${args.joinToString(" ")}: $out" }
        return out
    }

    @Test
    fun `a released release leaves no worktree or local branch behind`() {
        s.releaseMerger.mergeablePollMillis = 20
        world.launcher.stage = { p ->
            when (p.spec.kind) {
                "spec-writer" -> { p.init(); p.result("## Spec\nDo it."); p.exit(0) }
                "builder" -> { p.init(); git(p.spec.workDir, "commit", "--allow-empty", "-q", "-m", "work"); p.result("Built."); p.exit(0) }
                "approver" -> { p.init(); p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") }); p.exit(0) }
                else -> {
                    p.init(); p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) }); p.exit(0)
                }
            }
        }
        val nums = listOf("One", "Two").map { world.github.addIssue("demo-app", it, "body").number }
        val r = runBlocking {
            val created = s.releases.create(alice, "demo-app", "1.0", "")
            s.releases.assign(alice, "demo-app", nums, created.id)
            s.releases.start(alice, created.id)
        }
        val ready = runBlocking {
            withTimeout(30_000) {
                while (true) {
                    val x = s.releaseStore.get(r.id)!!
                    if (x.status == ReleaseStatus.READY && x.prNumber != null) return@withTimeout x
                    delay(20)
                }
                @Suppress("UNREACHABLE_CODE") error("ready")
            }
        }
        val main = s.workspaces.mainDir("alice", "demo-app")
        // Fake-GitHub mode creates the release branch locally for the runs (docs §6 "Fake-GitHub mode").
        assertTrue(git(main, "branch", "--list", "release/1.0").isNotBlank())
        assertEquals(ReleaseStatus.RELEASED, runBlocking { s.releases.approve(alice, ready.id) }.status)
        assertEquals("", git(main, "branch", "--list", "release/1.0").trim(), "the release branch is forgotten locally too")
        assertEquals("", git(main, "branch", "--list", "factory/*").trim(), "no run branch is left")
        assertEquals(1, git(main, "worktree", "list").lines().count { it.isNotBlank() }, "only the main working tree")
        val runsDir = s.workspaces.repoDir("alice", "demo-app").resolve("runs")
        assertTrue(!Files.exists(runsDir) || Files.list(runsDir).use { it.count() } == 0L)
    }
}
