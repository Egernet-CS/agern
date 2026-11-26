package dk.egernet.factory

import dk.egernet.factory.db.ReleaseRecord
import dk.egernet.factory.db.UpdateStatus
import dk.egernet.factory.db.UpdateTrigger
import dk.egernet.factory.git.BaseMerge
import dk.egernet.factory.git.BranchComparison
import dk.egernet.factory.git.FileChange
import dk.egernet.factory.git.MergeState
import dk.egernet.factory.git.UPDATE_CONFLICT
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
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
 * "Update and merge" (docs/ARCHITECTURE.md §6 "Retrying a run"): a finished run whose merge was refused because its
 * target moved gets an update round on the same run. The git side is [FakeGitOps] here; the real git operations are
 * tested in [UpdateGitTest].
 */
class UpdateAndMergeTest {
    private val world = TestWorld(twoFactor = false)
    private val s = world.services
    private val gh = world.github
    private val git = world.git
    private val alice = world.user("alice")

    @AfterTest fun tearDown() {
        world.close()
    }

    private val dir = "server/src/main/resources/db/migration"
    private val base = listOf("$dir/V15__earlier.sql", "$dir/V16__other.sql")

    /** Prompts of update-round builders, and what each resumed. */
    private val updatePrompts = CopyOnWriteArrayList<String>()
    private val updateResumes = CopyOnWriteArrayList<String?>()
    /** Runs in an update-round builder (default: commits, i.e. moves HEAD). */
    @Volatile private var onUpdateBuilder: () -> Unit = { git.headSha = "resolved-${UUID.randomUUID()}".take(40) }
    /** Runs in the first approver (e.g. to make the pull request conflict before it is merged). */
    @Volatile private var onFirstApprover: () -> Unit = {}
    @Volatile private var approverSeen = 0
    @Volatile private var reviewChangesAfterUpdate = false
    @Volatile private var escalateAfterUpdate = false
    /** While set, an update-round builder blocks until it is killed. */
    @Volatile private var hangUpdateBuilder = false

    init {
        s.orchestrator.mergeablePollMillis = 10
        world.launcher.stage = { p ->
            val prompt = p.spec.prompt.orEmpty()
            when (p.spec.kind) {
                "spec-writer" -> { p.init(); p.result("## Spec\nDo the thing."); p.exit(0) }
                "builder" -> {
                    p.init()
                    if (prompt.contains("This is update round")) {
                        updatePrompts += prompt
                        updateResumes += p.spec.resumeClaudeSessionId
                        if (hangUpdateBuilder) {
                            while (!p.killed) Thread.sleep(20)
                        } else {
                            onUpdateBuilder()
                            p.result("Merged and resolved.")
                            p.exit(0)
                        }
                    } else {
                        p.result("Built it."); p.exit(0)
                    }
                }
                "approver" -> {
                    p.init()
                    if (approverSeen++ == 0) onFirstApprover()
                    val escalate = escalateAfterUpdate && prompt.contains("Update rounds used")
                    p.result(null, buildJsonObject { put("verdict", if (escalate) "ESCALATE" else "APPROVE"); put("summary", "decided") })
                    p.exit(0)
                }
                else -> {
                    p.init()
                    val changes = reviewChangesAfterUpdate && prompt.contains("after update round")
                    p.result(null, buildJsonObject {
                        put("verdict", if (changes) "CHANGES" else "PASS"); put("summary", "fine"); put("findings", buildJsonArray { })
                    })
                    p.exit(0)
                }
            }
        }
    }

    private fun clash(head: (String) -> Boolean, mine: String = "V16__mine.sql") {
        git.comparison = { _, h -> BranchComparison(base, if (head(h)) listOf(FileChange('A', "$dir/$mine")) else emptyList()) }
    }

    private fun start(issue: Int = 1) = runBlocking { s.orchestrator.start(alice, "demo-app", issue, null) }
    private fun await(id: UUID) = world.awaitRun(id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN, 15_000)
    private fun merges() = gh.actions.filter { it.startsWith("merge") }
    private fun update(id: UUID, user: dk.egernet.factory.db.UserRecord = alice) = runBlocking { s.factory.retryRun(user, id, RetryMode.UPDATE) }

    /** Waits for the update round started on [id] to end (needs_human or terminal again). */
    private fun awaitUpdated(id: UUID): dk.egernet.factory.db.RunRecord {
        eventually("the update round ends") { s.orchestrator.job(id) == null }
        return await(id)
    }

    private fun eventually(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!cond()) {
            check(System.nanoTime() < deadline) { "timed out: $what" }
            Thread.sleep(20)
        }
    }

    private fun release(name: String, issues: List<Int>): ReleaseRecord = runBlocking {
        val r = s.releases.create(alice, "demo-app", name, "")
        s.releases.assign(alice, "demo-app", issues, r.id)
        s.releases.start(alice, r.id)
        s.releaseStore.get(r.id)!!
    }

    private fun awaitRelease(id: UUID, what: String, cond: (ReleaseRecord) -> Boolean): ReleaseRecord = runBlocking {
        withTimeoutOrNull(20_000) {
            while (true) {
                val r = s.releaseStore.get(id)!!
                if (cond(r)) return@withTimeoutOrNull r
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: error("not reached: $what (${s.releaseStore.get(id)})")
    }

    private fun ReleaseRecord.issue(n: Int) = issues.single { it.number == n }

    // ------------------------------------------------------------ migration clash, automatic in a release

    @Test
    fun `a release run whose migration clashes is updated automatically, renumbered and merged`() {
        val ok = gh.addIssue("demo-app", "Solid", "body").number
        val n = gh.addIssue("demo-app", "Clash", "body").number
        clash({ it.startsWith("factory/release-u/issue-$n-") })
        onUpdateBuilder = {
            git.headSha = "renumbered-000000000000000000000000000000"
            clash({ it.startsWith("factory/release-u/issue-$n-") }, mine = "V17__mine.sql")
        }
        val r = release("u", listOf(ok, n))
        val done = awaitRelease(r.id, "both merged") { it.issue(ok).state == ReleaseIssueState.MERGED && it.issue(n).state == ReleaseIssueState.MERGED }
        val run = s.runs.get(done.issue(n).runId!!)!!
        assertEquals(RunStatus.MERGED, run.status)
        assertEquals(1, run.updateRounds)
        assertEquals(1, git.baseMerges.size)
        assertEquals("release/u", git.baseMerges.single())

        // The builder resumed its conversation and was told exactly what to renumber.
        val prompt = updatePrompts.single()
        val firstBuilder = s.runs.stages(run.id).first { it.role == "builder" && it.round == 0 }
        assertEquals(s.sessions.get(firstBuilder.sessionId!!)!!.claudeSessionId, updateResumes.single())
        assertTrue(prompt.contains("`$dir/V16__mine.sql` → version 17 (`V17__mine.sql`); `$dir/V16__other.sql` on `release/u` keeps version 16"), prompt)
        assertTrue(prompt.contains("The next free version on `release/u` is 17."), prompt)
        assertTrue(prompt.contains("<merged_commits>\nMerged elsewhere (#99)\n</merged_commits>"), prompt)
        assertFalse(prompt.contains("<spec>"), "a resumed builder is not given the spec again")

        // A new round: builder, four reviewers and an approver, after the build.
        val round = s.runs.updates(run.id).single().round
        assertEquals(listOf("builder") + REVIEWER_ROLES + "approver", s.runs.stages(run.id).filter { it.round == round }.map { it.role })
        assertTrue(s.runs.stages(run.id).filter { it.round == round }.all { it.status == "done" })
        val dto = s.runs.toDto(run)
        val u = dto.updates.single()
        assertEquals(UpdateStatus.RESOLVED, u.status)
        assertEquals(UpdateTrigger.RELEASE, u.trigger)
        assertEquals(listOf("`$dir/V16__mine.sql` has the same version (16) as `$dir/V16__other.sql`"), u.migrations)
        assertTrue(u.summary.startsWith("Update round 1 of 2 (automatic): merged `release/u` (1 commit); 1 migration problem; builder added 1 commit"), u.summary)
        assertEquals(u.summary, done.toDto().issues.single { it.number == n }.updateSummary)
        assertEquals(2, merges().size)
    }

    @Test
    fun `an automatic update that fails holds the issue with the reason, and no second automatic round starts`() {
        val n = gh.addIssue("demo-app", "Stuck", "body").number
        clash({ it.startsWith("factory/release-f/issue-$n-") })
        // The builder commits, but the migration still clashes after the push.
        val r = release("f", listOf(n))
        val held = awaitRelease(r.id, "held") { it.issue(n).state == ReleaseIssueState.HELD }
        val issue = held.issue(n)
        assertTrue(issue.note!!.startsWith("Update round 1 of 2 with `release/f` did not finish: the migrations still conflict with `release/f`."), issue.note)
        assertTrue(issue.note!!.contains("Migrations: `$dir/V16__mine.sql` has the same version (16)"), issue.note)
        val run = s.runs.get(issue.runId!!)!!
        assertEquals(1, run.updateRounds)
        assertEquals(FailureKind.MIGRATION_CONFLICT, run.failureKind)
        assertTrue(run.updateAvailable, "the owner may start the second round by hand")
        Thread.sleep(300)
        assertEquals(1, updatePrompts.size, "no second automatic round")
        assertTrue(issue.updateAvailable && issue.updateRounds == 1 && issue.maxUpdateRounds == 2, issue.toString())

        // The owner's update in the issue's turn: the same run, and the issue ends merged.
        onUpdateBuilder = { git.headSha = "fixed-0000000000000000000000000000000000"; clash({ false }) }
        val opts = runBlocking { s.releases.retryOptions(alice, r.id, n) }
        assertEquals(RetryMode.UPDATE, opts.recommended, opts.reason)
        runBlocking { s.releases.retry(alice, r.id, n, RetryMode.UPDATE) }
        val done = awaitRelease(r.id, "merged") { it.issue(n).state == ReleaseIssueState.MERGED }
        assertEquals(run.id, done.issue(n).runId)
        assertEquals(2, s.runs.get(run.id)!!.updateRounds)
        assertEquals(listOf(UpdateTrigger.RELEASE, UpdateTrigger.OWNER), s.runs.updates(run.id).map { it.trigger })
    }

    @Test
    fun `a held release issue can still be started over or continued`() {
        val n = gh.addIssue("demo-app", "Over", "body").number
        clash({ it.startsWith("factory/release-o/issue-$n-") })
        val r = release("o", listOf(n))
        val held = awaitRelease(r.id, "held") { it.issue(n).state == ReleaseIssueState.HELD }.issue(n)
        clash({ false })
        runBlocking { s.releases.retry(alice, r.id, n, RetryMode.START_OVER) }
        val done = awaitRelease(r.id, "merged") { it.issue(n).state == ReleaseIssueState.MERGED }
        assertNotEquals(held.runId, done.issue(n).runId)
        assertEquals(RunStatus.CANCELLED, s.runs.get(held.runId!!)!!.status)
        assertEquals(2, world.launcher.byRole("spec-writer").size)
    }

    // ------------------------------------------------------------ textual conflict, single run

    private fun conflictOnMerge() {
        onFirstApprover = {
            val run = s.runs.list("demo-app", activeOnly = true).first()
            gh.conflicting += "demo-app:${run.branch}"
        }
    }

    @Test
    fun `a pull request that conflicts with its target is updated, resolved and merged`() {
        conflictOnMerge()
        val run = await(start().id)
        assertEquals(RunStatus.NEEDS_HUMAN, run.status, run.error)
        assertEquals(FailureKind.MERGE_CONFLICT, run.failureKind)
        assertEquals(FailedStep.MERGE, run.failedStep)
        assertTrue(run.updateAvailable)
        assertEquals(
            "Merging pull request #${run.prNumber} failed: Pull Request has merge conflicts. The branch conflicts with `main` or is behind it. " +
                "Use \"Update and merge\" to have Agern merge `main` into the branch and let the builder resolve the conflicts (update round " +
                "1 of 2), or resolve them on the pull request's branch and approve again.",
            run.error,
        )
        val opts = runBlocking { s.factory.retryOptions(alice, run.id) }
        assertEquals(RetryMode.UPDATE, opts.recommended)
        assertEquals(listOf(RetryMode.UPDATE, RetryMode.STEP, RetryMode.CONTINUE, RetryMode.START_OVER), opts.options.map { it.mode })
        assertTrue(opts.options.first().available)

        git.baseMerge = { _, _ ->
            BaseMerge(UPDATE_CONFLICT, "t".repeat(40), git.headSha, git.headSha, 3, listOf("A (#5)", "B (#6)", "C (#7)"), listOf("src/App.kt", "README.md"))
        }
        onUpdateBuilder = { git.headSha = "merged-by-builder-000000000000000000000"; gh.conflicting.clear() }
        val started = update(run.id)
        assertEquals(run.id.toString(), started.id)
        assertEquals(RunStatus.BUILDING, started.status)
        val done = awaitUpdated(run.id)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        val prompt = updatePrompts.single()
        assertTrue(prompt.contains("- `src/App.kt`\n- `README.md`\n"), prompt)
        assertTrue(prompt.contains("finish the merge with `git commit`"), prompt)
        assertEquals(listOf("A (#5)", "B (#6)", "C (#7)"), s.runs.toDto(done).updates.single().incoming)
        assertEquals("update 1", "update ${s.runs.updates(run.id).single().number}")
    }

    @Test
    fun `a merge that fails without a conflict is a step to retry, as before`() {
        gh.failMerge = true
        val run = await(start().id)
        assertEquals(FailedStep.MERGE, run.failedStep)
        assertNull(run.failureKind)
        assertFalse(run.updateAvailable)
        assertEquals(RetryMode.STEP, runBlocking { s.factory.retryOptions(alice, run.id) }.recommended)
        assertEquals("retry_mode_unavailable", assertFailsWith<ApiException> { update(run.id) }.code)
        assertEquals("run_not_retryable", assertFailsWith<ApiException> { runBlocking { s.factory.retryRun(alice, run.id, RetryMode.CONTINUE) } }.code)
    }

    // ------------------------------------------------------------ failures and the limit

    @Test
    fun `an unresolved conflict needs a human again with the files named, and the worktree is restored`() {
        conflictOnMerge()
        val run = await(start().id)
        val before = git.headSha
        git.baseMerge = { _, _ -> BaseMerge(UPDATE_CONFLICT, "t".repeat(40), git.headSha, git.headSha, 1, listOf("X"), listOf("src/A.kt", "src/B.kt")) }
        git.mergeStateAnswer = { _, _ -> MergeState(true, listOf("src/B.kt"), true, true, git.headSha) }
        onUpdateBuilder = {}
        update(run.id)
        val after = awaitUpdated(run.id)
        assertEquals(RunStatus.NEEDS_HUMAN, after.status)
        assertEquals(
            "Update round 1 of 2 with `main` did not finish: the builder left the merge unfinished. The builder said: Merged and resolved. " +
                "Conflicting files: `src/A.kt`, `src/B.kt`. Pull request #${run.prNumber} was not merged. \"Update and merge\" can try again " +
                "(1 round(s) left), or resolve it on the pull request's branch and approve again.",
            after.error,
        )
        assertEquals(listOf(before), git.restores)
        assertEquals(FailureKind.MERGE_CONFLICT, after.failureKind)
        assertEquals(FailedStep.MERGE, after.failedStep)
        assertTrue(after.updateAvailable)
        val u = s.runs.updates(run.id).single()
        assertEquals(UpdateStatus.FAILED, u.status)
        assertEquals(listOf("src/A.kt", "src/B.kt"), u.conflicts)
        assertTrue(s.runs.stages(run.id).filter { it.round == u.round && it.role != "builder" }.all { it.status == "skipped" })
        assertEquals(emptyList(), merges())
        // Not pushed again: the push count is that of the build.
        assertEquals(1, git.pushes.size)
    }

    @Test
    fun `after the limit no update is offered and the reason says so`() {
        conflictOnMerge()
        val run = await(start().id)
        git.mergeStateAnswer = { _, _ -> MergeState(false, emptyList(), false, true, "0".repeat(40)) }
        git.baseMerge = { _, _ -> BaseMerge(UPDATE_CONFLICT, "t".repeat(40), "0".repeat(40), "0".repeat(40), 1, emptyList(), listOf("a.txt")) }
        onUpdateBuilder = {} // no commit: the round fails
        update(run.id)
        assertTrue(awaitUpdated(run.id).updateAvailable)
        update(run.id)
        val last = awaitUpdated(run.id)
        assertEquals(2, last.updateRounds)
        assertFalse(last.updateAvailable)
        assertTrue(last.error!!.startsWith("Update round 2 of 2 with `main` did not finish: the builder made no commits."), last.error)
        assertTrue(last.error!!.endsWith(
            "The limit of 2 update round(s) is reached: resolve it on the pull request's branch and approve again, or build the issue " +
                "again from the current `main` (in a release: Retry).",
        ), last.error)
        val e = assertFailsWith<ApiException> { update(run.id) }
        assertEquals("retry_mode_unavailable", e.code)
        val opts = runBlocking { s.factory.retryOptions(alice, run.id) }
        assertEquals("The limit of 2 update round(s) is reached.", opts.options.first { it.mode == RetryMode.UPDATE }.detail)
        assertEquals(RetryMode.STEP, opts.recommended)
        // Fix rounds are not consumed.
        assertEquals(0, s.runs.stages(run.id).count { it.role == "builder" && it.round in 1..last.maxRounds && s.runs.updates(run.id).none { u -> u.round == it.round } })
    }

    @Test
    fun `reviewers requesting changes or an escalating approver after an update need a human`() {
        conflictOnMerge()
        val run = await(start().id)
        onUpdateBuilder = { git.headSha = "c".repeat(40) }
        reviewChangesAfterUpdate = true
        update(run.id)
        val after = awaitUpdated(run.id)
        assertEquals(RunStatus.NEEDS_HUMAN, after.status)
        assertEquals("Reviewers request changes after update round 1: security, ux, ui-design, code.", after.error)
        assertNull(after.failureKind)
        val round = s.runs.updates(run.id).single().round
        assertEquals("skipped", s.runs.stages(run.id).single { it.role == "approver" && it.round == round }.status)
        assertEquals(1, updatePrompts.size, "no fix round follows")
        assertEquals(UpdateStatus.RESOLVED, s.runs.updates(run.id).single().status)

        // Another run, whose approver escalates after the update.
        reviewChangesAfterUpdate = false
        escalateAfterUpdate = true
        approverSeen = 0
        val n2 = gh.addIssue("demo-app", "Second", "body").number
        val run2 = await(start(n2).id)
        update(run2.id)
        val esc = awaitUpdated(run2.id)
        assertEquals(RunStatus.NEEDS_HUMAN, esc.status)
        assertEquals("The approver escalated: decided", esc.error)
    }

    // ------------------------------------------------------------ gates and permissions

    @Test
    fun `only the owner starts an update, with a credential, and not while something else works on the run`() {
        conflictOnMerge()
        val run = await(start().id)
        val bob = world.user("bob")
        val root = world.user("root", admin = true)
        assertEquals(403, assertFailsWith<ApiException> { update(run.id, bob) }.status.value)
        assertEquals(403, assertFailsWith<ApiException> { update(run.id, root) }.status.value)

        s.users.setClaude(alice.id, null, null)
        val noCred = s.users.byId(alice.id)!!
        assertEquals("claude_credential_required", assertFailsWith<ApiException> { update(run.id, noCred) }.code)
        val cred = world.user("alice")

        s.runs.setReviewState(run.id, ReviewState.RUNNING)
        assertEquals("review_in_progress", assertFailsWith<ApiException> { update(run.id, cred) }.code)
        s.runs.setReviewState(run.id, ReviewState.NONE)

        val wt = s.workspaces.runDir("alice", "demo-app", run.id)
        val moved = wt.resolveSibling("moved-away")
        java.nio.file.Files.move(wt, moved)
        assertEquals("run_worktree_missing", assertFailsWith<ApiException> { update(run.id, cred) }.code)
        java.nio.file.Files.move(moved, wt)

        // Two starts at once: one wins.
        hangUpdateBuilder = true
        val results = runBlocking {
            coroutineScope {
                (1..2).map { async { runCatching { s.orchestrator.update(cred, run.id) } } }.awaitAll()
            }
        }
        assertEquals(1, results.count { it.isSuccess }, results.toString())
        assertEquals("retry_mode_unavailable", (results.first { it.isFailure }.exceptionOrNull() as ApiException).code)
        assertEquals(1, s.runs.get(run.id)!!.updateRounds)

        // Cancel in the middle of the round.
        eventually("the update builder runs") { updatePrompts.isNotEmpty() }
        val cancelled = runBlocking { s.orchestrator.cancel(cred, run.id) }
        assertEquals(RunStatus.CANCELLED, cancelled.status)
        assertEquals(UpdateStatus.CANCELLED, s.runs.updates(run.id).single().status)
    }

    @Test
    fun `a restart fails a run in an update round and its update`() {
        conflictOnMerge()
        val run = await(start().id)
        hangUpdateBuilder = true
        update(run.id)
        eventually("the update builder runs") { updatePrompts.isNotEmpty() }
        val ids = s.runs.failUnfinishedAfterRestart()
        assertTrue(run.id in ids)
        val after = s.runs.get(run.id)!!
        assertEquals(RunStatus.FAILED, after.status)
        assertEquals(FailureKind.RESTART, after.failureKind)
        val u = s.runs.updates(run.id).single()
        assertEquals(UpdateStatus.FAILED, u.status)
        assertEquals("Interrupted by a server restart", u.error)
        runBlocking { s.orchestrator.job(run.id)?.cancel() }
    }

    // ------------------------------------------------------------ chat and MCP

    @Test
    fun `the factory commands and the tool start an update, and a plain approval hints at it`() {
        conflictOnMerge()
        val run = await(start().id)
        val chat = s.sessions.create("demo-app", "chat", "sonnet", SessionStatus.IDLE, alice.id, "chat", null, null, null)
        // A plain approval merges as it is: refused again, with the hint.
        val plain = runBlocking { s.factory.handleCommand(chat, alice, "/factory approve ${run.id}") }
        assertTrue(plain.endsWith("\nUpdate and merge is available: /factory approve ${run.id} update"), plain)
        val listing = runBlocking { s.factory.handleCommand(chat, alice, "/factory retry ${run.id}") }
        assertTrue(listing.contains("Recommended: update"), listing)

        onUpdateBuilder = { git.headSha = "d".repeat(40) } // the conflict stays: the round's merge is refused again
        val reply = runBlocking { s.factory.handleCommand(chat, alice, "/factory approve ${run.id} update") }
        assertEquals("Run ${run.id}: updating with main and merging (update round 1 of 2).", reply)
        assertEquals(RunStatus.NEEDS_HUMAN, awaitUpdated(run.id).status)
        val again = s.runs.get(run.id)!!
        assertEquals(FailureKind.MERGE_CONFLICT, again.failureKind)
        assertTrue(again.error!!.contains("(update round 2 of 2)"), again.error)

        val viaRetry = runBlocking { s.factory.handleCommand(chat, alice, "/factory retry ${run.id} update") }
        assertEquals("Run ${run.id}: updating with main and merging (update round 2 of 2).", viaRetry)
        val last = awaitUpdated(run.id)
        assertTrue(last.error!!.contains("The limit of 2 update round(s) is reached"), last.error)
        val e = assertFailsWith<ApiException> { runBlocking { s.factory.approveRunTool(alice, "demo-app", run.id, update = true) } }
        assertEquals("retry_mode_unavailable", e.code)
    }

    @Test
    fun `the tool's update parameter starts the round`() {
        conflictOnMerge()
        val run = await(start().id)
        gh.conflicting.clear()
        val reply = runBlocking { s.factory.approveRunTool(alice, "demo-app", run.id, update = true) }
        assertEquals("Run ${run.id}: updating with main and merging (update round 1 of 2).", reply)
        assertEquals(RunStatus.MERGED, awaitUpdated(run.id).status)
    }
}

/** With FACTORY_MAX_UPDATE_ROUNDS=0 nothing is offered and the old reason stays. */
class UpdateRoundsOffTest {
    private val world = TestWorld(twoFactor = false, tweak = { it.copy(maxUpdateRounds = 0) })
    private val s = world.services
    private val alice = world.user("alice")

    @AfterTest fun tearDown() {
        world.close()
    }

    @Test
    fun `switched off, a migration conflict keeps the old reason and offers no update`() {
        world.launcher.stage = { p ->
            p.init()
            when (p.spec.kind) {
                "spec-writer", "builder" -> p.result("ok")
                "approver" -> p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "fine") })
                else -> p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "fine"); put("findings", buildJsonArray { }) })
            }
            p.exit(0)
        }
        val dir = "server/src/main/resources/db/migration"
        world.git.comparison = { _, h ->
            BranchComparison(listOf("$dir/V16__other.sql"), if (h.startsWith("factory/")) listOf(FileChange('A', "$dir/V16__mine.sql")) else emptyList())
        }
        val run = world.awaitRun(runBlocking { s.orchestrator.start(alice, "demo-app", 1, null) }.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.NEEDS_HUMAN, run.status)
        assertEquals(FailureKind.MIGRATION_CONFLICT, run.failureKind)
        assertEquals(0, run.maxUpdateRounds)
        assertFalse(run.updateAvailable)
        assertTrue(run.error!!.endsWith(
            "Fix the migration on the pull request's branch and approve again, or build the issue again from the current `main` (in a release: Retry).",
        ), run.error)
        val opts = runBlocking { s.factory.retryOptions(alice, run.id) }
        assertFalse(opts.options.first { it.mode == RetryMode.UPDATE }.available)
        assertNotEquals(RetryMode.UPDATE, opts.recommended)
        assertNotNull(opts.reason)
    }
}
