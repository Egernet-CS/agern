package dk.egernet.factory

import dk.egernet.factory.db.ReleaseRecord
import dk.egernet.factory.db.RunRecord
import dk.egernet.factory.db.RunStore
import dk.egernet.factory.pipeline.Orchestrator
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.git.BranchComparison
import dk.egernet.factory.git.FileChange
import dk.egernet.factory.git.MergeCheck
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Bring up to date": sync runs that merge the default branch into a release branch (docs/ARCHITECTURE.md §6). */
class ReleaseSyncTest {
    private val world = TestWorld(twoFactor = false)
    private val s = world.services
    private val gh = world.github
    private val git = world.git
    private val alice = world.user("alice")
    private val dir = "server/src/main/resources/db/migration"

    @AfterTest fun tearDown() {
        gate.set(1)
        world.close()
    }

    // ------------------------------------------------------------ scripted agents

    /** What the sync builder does in each round (round → action); default: commit a merge (move the head). */
    private val syncBuilder = ConcurrentHashMap<Int, (FakeProcess) -> Unit>()
    /** The sync reviewers' verdict per round; default PASS. */
    private val syncVerdict = ConcurrentHashMap<Int, String>()
    /** While 0, the sync builder of round 0 waits. */
    private val gate = AtomicInteger(1)
    private val heads = AtomicInteger(100)

    private fun newHead() = "%040x".format(heads.incrementAndGet())

    private fun isSync(p: FakeProcess) = p.spec.prompt.orEmpty().let {
        it.startsWith("Bring the release branch") || it.startsWith("The reviewers requested changes to the merge") || it.startsWith("Review the merge of")
    }

    init {
        s.releaseMerger.mergeablePollMillis = 20
        s.releaseMerger.checksSettleSeconds = 0
        world.launcher.stage = { p ->
            val prompt = p.spec.prompt.orEmpty()
            when {
                p.spec.kind == "spec-writer" -> { p.init(); p.result("## Spec\nDo it."); p.exit(0) }
                p.spec.kind == "builder" && isSync(p) -> {
                    p.init()
                    val round = if (prompt.startsWith("The reviewers")) Regex("fix round (\\d+)").find(prompt)!!.groupValues[1].toInt() else 0
                    if (round == 0) while (gate.get() == 0 && !p.killed) Thread.sleep(10)
                    (syncBuilder[round] ?: { _: FakeProcess -> git.headSha = newHead() }).invoke(p)
                    if (!p.killed) { p.result("Merged main; resolved README.md.\n\nMore details."); p.exit(0) }
                }
                p.spec.kind == "builder" -> { p.init(); p.result("Built it."); p.exit(0) }
                p.spec.kind == "approver" -> {
                    p.init(); p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") }); p.exit(0)
                }
                else -> {
                    p.init()
                    val round = Regex("after fix round (\\d+)").find(prompt)?.groupValues?.get(1)?.toInt() ?: 0
                    val v = if (isSync(p)) syncVerdict[round] ?: "PASS" else "PASS"
                    p.result(null, buildJsonObject {
                        put("verdict", v); put("summary", "says $v")
                        put("findings", buildJsonArray { if (v == "CHANGES") add(buildJsonObject { put("severity", "major"); put("detail", "lost a route") }) })
                    })
                    p.exit(0)
                }
            }
        }
    }

    private fun issue(title: String) = gh.addIssue("demo-app", title, "body").number

    private fun awaitRelease(id: UUID, what: String = "condition", cond: (ReleaseRecord) -> Boolean): ReleaseRecord = runBlocking {
        withTimeout(15_000) {
            while (true) {
                val r = s.releaseLocks.of(id).withLock { s.releaseStore.get(id)!! }
                if (cond(r)) return@withTimeout r
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE") error(what)
        }
    }

    private fun readyRelease(name: String, by: UserRecord = alice, mode: String = ReleaseMode.MANUAL, conflict: Boolean = false): ReleaseRecord {
        if (conflict) gh.conflicting += "demo-app:release/$name"
        val r = runBlocking {
            val r = s.releases.create(by, "demo-app", name, "Desc of $name", mode)
            s.releases.assign(by, "demo-app", listOf(issue("Feature of $name")), r.id)
            s.releases.start(by, r.id)
        }
        return awaitRelease(r.id, "ready") { it.status == ReleaseStatus.READY && it.prNumber != null && it.mergeState != ReleaseMergeState.UNKNOWN }
    }

    /** A ready release whose pull request conflicts; pushing the sync clears the conflict, as GitHub would. */
    private fun conflicting(name: String, mode: String = ReleaseMode.MANUAL): ReleaseRecord {
        // Conflicting before it is ready, so an automatic release is not merged first.
        val r = readyRelease(name, mode = mode, conflict = true)
        git.beforeFastForward = { b -> gh.conflicting.remove("demo-app:$b") }
        val c = check(r.id)
        assertEquals(ReleaseMergeState.CONFLICT, c.mergeState)
        assertTrue(c.syncNeeded, c.toString())
        return c
    }

    private fun check(id: UUID, by: UserRecord = alice) = runBlocking { s.releases.check(by, id) }
    private fun sync(id: UUID, by: UserRecord = alice) = runBlocking { s.releases.sync(by, id) }
    private fun code(block: () -> Unit): String = assertFailsWith<ApiException> { block() }.code
    private fun comments(n: Int) = gh.client("fake-alice").listComments("test-org", "demo-app", n).map { it.body ?: "" }
    private fun syncRun(r: ReleaseRecord): RunRecord = s.runs.latestSync(r.id)!!
    private fun awaitSync(r: ReleaseRecord, statuses: Set<String> = RunStatus.TERMINAL) = world.awaitRun(syncRun(r).id, statuses, 15_000)
    private fun syncStages(run: RunRecord) = s.runs.stages(run.id)
    private fun syncProcs() = world.launcher.launched.filter { isSync(it) }

    private fun eventually(what: String, cond: () -> Boolean) = runBlocking {
        withTimeout(10_000) { while (!cond()) delay(20) }
    }

    // ------------------------------------------------------------ the paths

    @Test
    fun `a conflicting release is merged up to date, pushed fast-forward, reviewed and ready again`() {
        val r = conflicting("x")
        val pr = r.prNumber!!
        // The conflict comment points at "Bring up to date".
        eventually("conflict comment") { comments(pr).any { it.contains("no longer merges cleanly") } }
        assertTrue(comments(pr).single { it.contains("no longer merges cleanly") }.contains("Use \"Bring up to date\" on the release page (or `/factory release sync x`)"))
        val before = gh.actions.size
        val started = sync(r.id)
        assertEquals("main", started.target)
        val run = awaitSync(r)
        assertEquals(RunStatus.MERGED, run.status, run.error)
        assertEquals(RunKind.SYNC, run.kind)
        assertEquals(0, run.issueNumber)
        assertEquals("Bring release/x up to date with main", run.issueTitle)
        assertEquals("release/x", run.baseBranch)
        assertTrue(run.branch!!.startsWith("factory/sync-x-"), run.branch)
        assertEquals(alice.id, run.ownerId)
        assertNull(run.prNumber)
        // Builder and four reviewers ran; spec-writer and approver were skipped.
        val stages = syncStages(run)
        assertEquals(Orchestrator.SYNC_SKIPPED_STAGE, stages.single { it.role == "spec-writer" }.summary)
        assertEquals("skipped", stages.single { it.role == "approver" }.status)
        assertEquals("done", stages.single { it.role == "builder" }.status)
        assertEquals(4, stages.count { it.role in REVIEWER_ROLES && it.verdict == "PASS" })
        assertEquals(5, syncProcs().size)
        // Sessions belong to no issue.
        val sessions = s.sessions.byRun(run.id)
        assertTrue(sessions.isNotEmpty() && sessions.all { it.issueNumber == null }, sessions.toString())
        assertTrue(sessions.any { it.title == "builder · sync x" }, sessions.map { it.title }.toString())
        // One fast-forward push of release/x, nothing forced, nothing merged into main.
        assertEquals(listOf("release/x ${run.syncPushedSha}"), git.ffPushes)
        assertFalse("release/x" in git.pushes)
        assertNotNull(run.syncPushedSha)
        assertTrue(git.worktrees.any { it.startsWith("factory/sync-x-") && it.endsWith("<- release/x") }, git.worktrees.toString())
        val sinceSync = gh.actions.drop(before)
        assertTrue(sinceSync.none { it.startsWith("merge-") || it.startsWith("merge ") || it.startsWith("update-branch") }, sinceSync.toString())
        eventually("pushed comment") { comments(pr).any { it.startsWith("**Agern:** merged `main` into `release/x`") } }
        assertTrue(comments(pr).single { it.startsWith("**Agern:** merged `main`") }.contains("started by @alice"))
        // No comment or label on issue 0.
        assertTrue(gh.actions.none { it.contains("demo-app#0") }, gh.actions.toString())
        val after = awaitRelease(r.id, "clean and done") { it.mergeState == ReleaseMergeState.CLEAN && it.syncState == ReleaseSyncState.DONE }
        assertEquals(ReleaseStatus.READY, after.status)
        assertFalse(after.syncNeeded)
        assertEquals(run.id.toString(), after.toDto().sync!!.runId)
        assertEquals("done", after.toDto().sync!!.state)
        // The run's DTO.
        val dto = s.runs.toDto(s.runs.get(run.id)!!)
        assertEquals("sync", dto.kind)
        assertEquals(run.syncPushedSha, dto.syncPushedSha)
    }

    @Test
    fun `a release that is only behind may be brought up to date, an up-to-date one may not`() {
        git.comparison = { base, head -> if (base == "main" && head == "release/b") BranchComparison(emptyList(), emptyList(), behind = 3) else null }
        val r = readyRelease("b")
        val c = check(r.id)
        assertEquals(3, c.behindBy)
        assertTrue(c.syncNeeded)
        assertEquals(3, c.toDto().behindBy)
        git.comparison = { _, _ -> BranchComparison(emptyList(), emptyList(), behind = 0) }
        val clean = check(r.id)
        assertEquals(0, clean.behindBy)
        assertFalse(clean.syncNeeded)
        assertEquals("release_up_to_date", code { sync(r.id) })
        assertNull(s.runs.latestSync(r.id))
    }

    @Test
    fun `colliding migrations are pushed only once the merge clears them`() {
        git.comparison = { _, head ->
            if (head == "release/m") BranchComparison(listOf("$dir/V2__theirs.sql"), listOf(FileChange('A', "$dir/V2__mine.sql")), behind = 1) else null
        }
        val r = readyRelease("m")
        val c = check(r.id)
        assertTrue(c.migrationConflict && c.syncNeeded, c.toString())
        assertTrue(c.notice!!.contains("/factory release sync m"), c.notice)
        // The builder leaves the collision: nothing is pushed.
        git.worktreeComparison = { BranchComparison(listOf("$dir/V2__theirs.sql"), listOf(FileChange('A', "$dir/V2__mine.sql"))) }
        sync(r.id)
        val failed = awaitSync(r)
        assertEquals(RunStatus.FAILED, failed.status)
        assertTrue(
            failed.error!!.startsWith(
                "Could not bring `release/m` up to date with `main`: database migration conflict: `$dir/V2__mine.sql` has the same version (2) as " +
                    "`$dir/V2__theirs.sql`. Nothing was pushed. The agent said: Merged main; resolved README.md.",
            ),
            failed.error,
        )
        assertTrue(git.ffPushes.isEmpty())
        assertEquals("failed", awaitRelease(r.id) { it.syncState == ReleaseSyncState.FAILED }.toDto().sync!!.state)
        // Renumbered: pushed.
        git.worktreeComparison = { BranchComparison(listOf("$dir/V2__theirs.sql"), listOf(FileChange('A', "$dir/V3__mine.sql"))) }
        sync(r.id)
        assertEquals(RunStatus.MERGED, awaitSync(r).status)
        assertEquals(1, git.ffPushes.size)
    }

    private fun failsWith(name: String, setUp: () -> Unit, expected: String) {
        val r = conflicting(name)
        setUp()
        sync(r.id)
        val run = awaitSync(r)
        assertEquals(RunStatus.FAILED, run.status)
        assertTrue(run.error!!.startsWith(expected), run.error)
        assertTrue(git.ffPushes.isEmpty(), git.ffPushes.toString())
        assertNull(run.syncPushedSha)
        val after = awaitRelease(r.id) { it.syncState == ReleaseSyncState.FAILED }
        assertEquals(ReleaseStatus.READY, after.status)
        assertEquals(ReleaseMergeState.CONFLICT, after.mergeState)
        assertTrue(after.syncNeeded)
        assertTrue(gh.actions.none { it.contains("demo-app#0") })
    }

    @Test
    fun `not merging the default branch fails the run without a push`() = failsWith(
        "n", { git.merge = { _, _ -> MergeCheck(git.headSha, containsStart = true, containsMerged = false, unfinished = false) } },
        "Could not bring `release/n` up to date with `main`: `main` was not merged into it. Nothing was pushed.",
    )

    @Test
    fun `an unfinished merge fails the run without a push`() = failsWith(
        "u", { git.merge = { _, _ -> MergeCheck(git.headSha, containsStart = true, containsMerged = true, unfinished = true) } },
        "Could not bring `release/u` up to date with `main`: the merge is not finished (unmerged paths, a merge in progress or uncommitted changes). Nothing was pushed.",
    )

    @Test
    fun `rewritten history fails the run without a push`() = failsWith(
        "w", { git.merge = { _, _ -> MergeCheck(git.headSha, containsStart = false, containsMerged = true, unfinished = false) } },
        "Could not bring `release/w` up to date with `main`: the branch no longer contains the release branch's commits (history was rewritten). Nothing was pushed.",
    )

    @Test
    fun `a builder that fails ends the run without a push`() = failsWith(
        "f", { syncBuilder[0] = { p -> p.result("boom", isError = true); p.exit(1) } },
        "Could not bring `release/f` up to date with `main`: builder failed",
    )

    @Test
    fun `a release branch that moved meanwhile refuses the push`() = failsWith(
        "p", { git.rejectFastForward = "! [rejected] HEAD -> release/p (fetch first)" },
        "Pushing to `release/p` was refused because the branch changed meanwhile: ! [rejected] HEAD -> release/p (fetch first). Nothing was pushed; " +
            "use \"Bring up to date\" again.",
    )

    @Test
    fun `an already up-to-date branch is not pushed and not reviewed`() {
        val r = conflicting("a")
        syncBuilder[0] = { _ -> }
        sync(r.id)
        val run = awaitSync(r)
        assertEquals(RunStatus.MERGED, run.status, run.error)
        assertEquals("Already up to date; nothing to push", syncStages(run).single { it.role == "builder" }.summary)
        assertTrue(syncStages(run).filter { it.role in REVIEWER_ROLES }.all { it.status == "skipped" })
        assertTrue(git.ffPushes.isEmpty())
        assertEquals(1, syncProcs().size)
    }

    @Test
    fun `changes requested on the merge get a fix round and a second push`() {
        val r = conflicting("c")
        syncVerdict[0] = "CHANGES"
        sync(r.id)
        val run = awaitSync(r)
        assertEquals(RunStatus.MERGED, run.status, run.error)
        assertEquals(2, git.ffPushes.size)
        val fix = syncProcs().single { it.spec.prompt!!.startsWith("The reviewers requested changes") }.spec.prompt!!
        assertTrue(fix.contains("never amend, rebase or reset: the merge is already pushed"), fix)
        assertTrue(fix.contains("lost a route"))
        // The fix round must keep the commit pushed last.
        assertTrue(git.mergeChecks.last().startsWith(git.ffPushes.first().substringAfter(' ')), git.mergeChecks.toString())
    }

    @Test
    fun `reviewers that keep requesting changes leave the merge unreviewed and hold an automatic release`() {
        val r = conflicting("q", mode = ReleaseMode.AUTOMATIC)
        for (i in 0..5) syncVerdict[i] = "CHANGES"
        sync(r.id)
        val run = awaitSync(r)
        assertEquals(RunStatus.FAILED, run.status)
        assertTrue(
            run.error!!.startsWith(
                "Reviewers still request changes on the merge of `main` into `release/q` after 2 fix round(s): security, ux, ui-design, code. " +
                    "The merge is already on `release/q` (commit `${run.syncPushedSha!!.take(7)}`)",
            ),
            run.error,
        )
        val held = awaitRelease(r.id, "unreviewed notice") { it.syncState == ReleaseSyncState.UNREVIEWED && it.notice != null }
        runBlocking { s.releaseMerger.tick() }
        val still = s.releaseStore.get(r.id)!!
        assertEquals(ReleaseStatus.READY, still.status)
        assertTrue(still.notice!!.startsWith("The merge of `main` into `release/q` (run ${run.id}) was pushed but its review did not pass"), still.notice)
        assertEquals("unreviewed", held.toDto().sync!!.state)
        assertTrue(gh.actions.none { it.startsWith("merge-") })
        eventually("unreviewed comment") { comments(r.prNumber!!).any { it.startsWith("**Agern:** the review of the merge of `main` into `release/q`") } }
        // A person may still approve it.
        assertEquals(ReleaseStatus.RELEASED, runBlocking { s.releases.approve(alice, r.id) }.status)
        val described = runBlocking { s.releases.describe(alice, "demo-app", false) }
        assertFalse(described.contains("Sync:"), "released: no sync line")
    }

    @Test
    fun `while a sync runs, approval and a second sync are refused and an automatic release waits`() {
        val r = conflicting("h", mode = ReleaseMode.AUTOMATIC)
        gate.set(0)
        sync(r.id)
        eventually("builder running") { syncProcs().isNotEmpty() }
        assertEquals("release_sync_running", code { runBlocking { s.releases.approve(alice, r.id) } })
        assertEquals("release_sync_running", code { sync(r.id) })
        val described = runBlocking { s.releases.describe(alice, "demo-app", false) }
        assertTrue(described.contains("    Sync: running (run ${syncRun(r).id})"), described)
        // Green and clean meanwhile (as after the push), yet not merged while the sync runs.
        gh.conflicting.clear()
        runBlocking { s.releaseMerger.tick() }
        check(r.id)
        assertEquals(ReleaseStatus.READY, s.releaseStore.get(r.id)!!.status)
        gate.set(1)
        assertEquals(RunStatus.MERGED, awaitSync(r).status)
        awaitRelease(r.id, "released after the sync") { it.status == ReleaseStatus.RELEASED }
    }

    @Test
    fun `cancelling the release cancels its sync run, which is never retried`() {
        val r = conflicting("k")
        gate.set(0)
        sync(r.id)
        eventually("builder running") { syncProcs().isNotEmpty() }
        runBlocking { s.releases.cancel(alice, r.id) }
        val run = awaitSync(r)
        assertEquals(RunStatus.CANCELLED, run.status)
        assertEquals("sync_run", code { runBlocking { s.orchestrator.retry(alice, run.id, null) } })
        val opts = s.orchestrator.retryOptions(run)
        assertTrue(opts.options.none { it.available })
        assertEquals(Orchestrator.SYNC_NOT_RETRIED, opts.reason)
        assertTrue(gh.actions.none { it.contains("demo-app#0") }, gh.actions.toString())
    }

    @Test
    fun `an owner cancelling the release also cancels the sync run an admin started`() {
        val root = world.user("root", admin = true)
        val r = conflicting("ad")
        gate.set(0)
        sync(r.id, by = root)
        eventually("builder running") { syncProcs().isNotEmpty() }
        assertEquals("root", syncProcs().first().spec.identity.username)
        runBlocking { s.releases.cancel(alice, r.id) }
        assertEquals(RunStatus.CANCELLED, awaitSync(r).status)
    }

    @Test
    fun `a restart fails an unfinished sync run with its own text`() {
        val r = conflicting("rs")
        gate.set(0)
        sync(r.id)
        eventually("builder running") { syncProcs().isNotEmpty() }
        val ids = s.runs.failUnfinishedAfterRestart()
        val run = syncRun(r)
        assertTrue(run.id in ids)
        assertEquals(RunStore.SYNC_RESTART_ERROR, run.error)
        assertEquals(
            "Server restarted while the release branch was being brought up to date; use \"Bring up to date\" again.", run.error,
        )
    }

    @Test
    fun `factory release sync starts the same run and describe shows a failed sync`() {
        val r = conflicting("cmd")
        val chat = s.sessions.create("demo-app", "chat", "sonnet", SessionStatus.RUNNING, alice.id, "chat")
        suspend fun cmd(t: String) = try { s.factory.handleCommand(chat, alice, t) } catch (e: ApiException) { e.message }
        assertEquals("Usage: /factory release sync <name>", runBlocking { cmd("/factory release sync") })
        assertTrue(runBlocking { cmd("/factory help") }!!.contains("/factory release sync <name>"))
        git.merge = { _, _ -> MergeCheck(git.headSha, containsStart = true, containsMerged = false, unfinished = false) }
        val reply = runBlocking { cmd("/factory release sync cmd") }!!
        val run = syncRun(r)
        assertEquals(
            "Bringing release \"cmd\" up to date with main: run ${run.id} merges main into release/cmd, resolves conflicts and renumbers " +
                "migrations. Nothing is merged into main.",
            reply,
        )
        awaitSync(r)
        awaitRelease(r.id) { it.syncState == ReleaseSyncState.FAILED }
        val described = runBlocking { s.releases.describe(alice, "demo-app", false) }
        assertTrue(described.contains("    Sync: failed, nothing pushed: Could not bring `release/cmd` up to date with `main`"), described)
        assertTrue(runBlocking { cmd("/factory retry ${run.id}") }!!.contains("is not retried"))
        assertTrue(runBlocking { cmd("/factory status") }!!.contains("sync Bring release/cmd up to date with main"))
    }

    // ------------------------------------------------------------ over HTTP

    @Test
    fun `who may bring a release up to date, over HTTP`() = testApplication {
        application { agernModule(s) }
        world.user("bob")
        world.user("root", admin = true)
        val r = conflicting("web")
        val a = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        val root = login("root", "pw-r")
        // Another user: 403; an invisible project: 404.
        val res = bob.postJson("/api/releases/${r.id}/sync")
        assertEquals(403, res.status.value)
        assertTrue(res.bodyAsText().contains("Only alice, who started this release, or an admin can do this"), res.bodyAsText())
        val lab = runBlocking { s.releases.create(alice, "private-lab", "lab", "") }
        assertEquals(404, bob.postJson("/api/releases/${lab.id}/sync").status.value)
        assertEquals("release_not_ready", errorCode(a.postJson("/api/releases/${lab.id}/sync")))
        assertEquals(403, a.postJson("/api/releases/${r.id}/sync", csrf = false).status.value)
        // Merge commits not allowed: refused before anything starts.
        gh.allowMergeCommit = false
        assertEquals("release_sync_merge_commits_disallowed", errorCode(a.postJson("/api/releases/${r.id}/sync")))
        gh.allowMergeCommit = true
        // The admin: allowed, and the run is the admin's, on the admin's credentials.
        gate.set(0)
        val ok = root.postJson("/api/releases/${r.id}/sync")
        assertEquals(200, ok.status.value, ok.bodyAsText())
        val body = json(ok.bodyAsText())
        assertEquals("running", body["sync"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        assertEquals("root", body["sync"]!!.jsonObject["by"]!!.jsonObject["username"]!!.jsonPrimitive.content)
        val run = syncRun(r)
        assertEquals("root", run.ownerUsername)
        eventually("builder running") { syncProcs().isNotEmpty() }
        assertEquals("root", syncProcs().first().spec.identity.username)
        assertEquals("release_sync_running", errorCode(a.postJson("/api/releases/${r.id}/sync")))
        // The run page: kind sync, no retry.
        val runJson = json(a.get("/api/runs/${run.id}").bodyAsText())
        assertEquals("sync", runJson["kind"]!!.jsonPrimitive.content)
        assertEquals(0, runJson["issueNumber"]!!.jsonPrimitive.content.toInt())
        assertEquals("sync_run", errorCode(root.postJson("/api/runs/${run.id}/retry")))
        gate.set(1)
        awaitSync(r)
        // The owner may as well; a missing Claude credential is 422.
        val r2 = conflicting("web2")
        assertEquals(200, a.postJson("/api/releases/${r2.id}/sync").status.value)
        val carlRelease = runBlocking {
            val rr = s.releases.create(alice, "demo-app", "carl", "")
            rr
        }
        assertEquals("release_not_ready", errorCode(a.postJson("/api/releases/${carlRelease.id}/sync")))
        awaitSync(r2)
    }

    @Test
    fun `a missing Claude credential and a release of several repositories are refused`() {
        val r = conflicting("cred")
        val noClaude = world.user("dora", admin = true, claude = false)
        assertEquals("claude_credential_required", code { sync(r.id, by = noClaude) })
        s.releaseStore.insertRepo(r.id, "member-lib", "lib", false, "main")
        assertEquals("release_sync_unsupported", code { sync(r.id) })
        assertNull(s.runs.latestSync(r.id))
    }

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String): HttpClient {
        val c = createClient { install(HttpCookies) }
        assertEquals(200, c.post("/api/auth/login") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
        }.status.value)
        return c
    }

    private suspend fun HttpClient.postJson(path: String, body: String = "{}", csrf: Boolean = true): HttpResponse =
        post(path) { if (csrf) header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody(body) }

    private fun json(t: String) = AppJson.parseToJsonElement(t).jsonObject
    private suspend fun errorCode(r: HttpResponse) = json(r.bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
}
