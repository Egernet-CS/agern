package dk.egernet.factory

import dk.egernet.factory.db.ReleaseRecord
import dk.egernet.factory.git.BranchComparison
import dk.egernet.factory.git.FileChange
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Agern does not merge a run's pull request, or a release, whose database migrations collide with the target branch's
 * (docs/ARCHITECTURE.md §6 "Database migrations"). The comparison itself is [FakeGitOps.comparison]; the real git side
 * is [MigrationGitTest].
 */
class MigrationGuardTest {
    private val clock = MutableClock(java.time.Instant.now())
    private val world = TestWorld(twoFactor = false, clock = clock)
    private val s = world.services
    private val gh = world.github
    private val alice = world.user("alice")

    @AfterTest fun tearDown() {
        world.close()
        runBlocking { withTimeoutOrNull(10_000) { s.scope.coroutineContext[kotlinx.coroutines.Job]!!.join() } }
        (s.db.dataSource as java.io.Closeable).close()
    }

    init {
        s.releaseMerger.mergeablePollMillis = 20
        s.releaseMerger.checksSettleSeconds = 0
        world.launcher.stage = { p ->
            when (p.spec.kind) {
                "spec-writer" -> { p.init(); p.result("## Spec\nDo the thing."); p.exit(0) }
                "builder" -> { p.init(); p.result("Built it."); p.exit(0) }
                "approver" -> { p.init(); p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "fine") }); p.exit(0) }
                else -> {
                    p.init()
                    p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "fine"); put("findings", buildJsonArray { }) })
                    p.exit(0)
                }
            }
        }
    }

    private val dir = "server/src/main/resources/db/migration"
    private val base = listOf("$dir/V15__earlier.sql", "$dir/V16__other.sql", "README.md")

    /** The comparison for heads matching [head] (all others: nothing changed). */
    private fun compare(head: (String) -> Boolean, vararg changes: FileChange) {
        world.git.comparison = { _, h -> BranchComparison(base, if (head(h)) changes.toList() else emptyList()) }
    }

    private fun start(issue: Int = 1) = runBlocking { s.orchestrator.start(alice, "demo-app", issue, null) }
    private fun await(id: UUID) = world.awaitRun(id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
    private fun comments(n: Int) = gh.client("fake-alice").listComments("test-org", "demo-app", n).map { it.body ?: "" }
    private fun merges() = gh.actions.filter { it.startsWith("merge") }

    private fun awaitRelease(id: UUID, what: String, cond: (ReleaseRecord) -> Boolean): ReleaseRecord = runBlocking {
        withTimeoutOrNull(15_000) {
            while (true) {
                val r = s.releaseStore.get(id)!!
                if (cond(r)) return@withTimeoutOrNull r
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: error("not reached: $what (${s.releaseStore.get(id)})")
    }

    // ------------------------------------------------------------ a run's pull request

    @Test
    fun `a run adding a migration version the target already has is held, and merges once it is renumbered`() {
        compare({ it.startsWith("factory/issue-1-") }, FileChange('A', "$dir/V16__mine.sql"))
        val run = start()
        val held = await(run.id)
        assertEquals(RunStatus.NEEDS_HUMAN, held.status)
        assertEquals(
            "Database migration conflict with `main`: `$dir/V16__mine.sql` has the same version (16) as `$dir/V16__other.sql`. " +
                "Pull request #${held.prNumber} was not merged. Migrations already on `main` must stay unchanged, and a new " +
                "migration needs a version above all of them. Fix the migration on the pull request's branch and approve " +
                "again, or build the issue again from the current `main` (in a release: Retry).",
            held.error,
        )
        assertEquals(emptyList(), merges(), "nothing was merged")
        assertTrue("${held.branch} -> main" in world.git.comparisons, world.git.comparisons.toString())
        assertTrue(gh.actions.any { it.startsWith("label demo-app#1") && it.contains("needs-human") }, gh.actions.toString())
        assertTrue(comments(1).any { it.startsWith("**Agern: needs a human.** Database migration conflict with `main`") })

        // Approving without a fix holds it again.
        runBlocking { s.orchestrator.approve(alice, run.id) }
        assertEquals(RunStatus.NEEDS_HUMAN, await(run.id).status)
        assertEquals(emptyList(), merges())

        // Renumbered on the branch: the approval merges.
        compare({ it == held.branch }, FileChange('A', "$dir/V17__mine.sql"))
        runBlocking { s.orchestrator.approve(alice, run.id) }
        assertEquals(RunStatus.MERGED, await(run.id).status)
        assertEquals(listOf("merge demo-app #${held.prNumber}"), merges())
    }

    @Test
    fun `a lower version or a changed base migration is held too`() {
        world.git.comparison = { _, _ -> BranchComparison(base, listOf(FileChange('A', "$dir/V14__late.sql"), FileChange('M', "$dir/V15__earlier.sql"))) }
        val held = await(start().id)
        assertEquals(RunStatus.NEEDS_HUMAN, held.status)
        assertTrue(
            held.error!!.startsWith(
                "Database migration conflict with `main`: `$dir/V15__earlier.sql` was changed; `$dir/V14__late.sql` " +
                    "(version 14) is not higher than `$dir/V16__other.sql` (version 16). Pull request #",
            ),
            held.error,
        )
        assertEquals(emptyList(), merges())
    }

    @Test
    fun `a run with a higher version, no migration, or no possible comparison merges as before`() {
        compare({ true }, FileChange('A', "$dir/V17__mine.sql"), FileChange('M', "README.md"))
        assertEquals(RunStatus.MERGED, await(start(1).id).status)
        world.git.comparison = { _, _ -> BranchComparison(base, listOf(FileChange('M', "src/App.kt"))) }
        assertEquals(RunStatus.MERGED, await(start(2).id).status)
        // No clone or branch to compare (null), or git failing: logged, and the merge goes ahead (CI is the strict gate).
        world.git.comparison = { _, _ -> null }
        assertEquals(RunStatus.MERGED, await(start(3).id).status)
        world.git.comparison = { _, _ -> throw dk.egernet.factory.git.GitException("git fetch failed") }
        assertEquals(RunStatus.MERGED, await(start(gh.addIssue("demo-app", "Fourth", "body").number).id).status)
        assertEquals(4, merges().size)
    }

    // ------------------------------------------------------------ releases

    private fun release(name: String, issues: List<Int>, mode: String? = null): ReleaseRecord = runBlocking {
        val r = s.releases.create(alice, "demo-app", name, "", mode)
        s.releases.assign(alice, "demo-app", issues, r.id)
        runBlocking { s.releases.start(alice, r.id) }
        s.releaseStore.get(r.id)!!
    }

    @Test
    fun `a release run whose migration collides with the release branch holds its issue with the reason`() {
        val ok = gh.addIssue("demo-app", "Solid", "body").number
        val clash = gh.addIssue("demo-app", "Clash", "body").number
        compare({ it.startsWith("factory/release-h/issue-$clash-") }, FileChange('A', "$dir/V16__mine.sql"))
        val r = release("h", listOf(ok, clash))
        val ready = awaitRelease(r.id, "ready") { it.status == ReleaseStatus.READY }
        assertEquals(ReleaseIssueState.MERGED, ready.issues.single { it.number == ok }.state)
        val issue = ready.issues.single { it.number == clash }
        assertEquals(ReleaseIssueState.HELD, issue.state)
        assertTrue(issue.note!!.startsWith("Database migration conflict with `release/h`: `$dir/V16__mine.sql` has the same version (16)"), issue.note)
        assertNull(ready.prNumber, "an incomplete release opens no pull request")
        assertEquals("release_incomplete", assertFailsWith<ApiException> { runBlocking { s.releases.approve(alice, r.id) } }.code)
    }

    @Test
    fun `a ready release whose branch collides with the default branch is not merged until it is fixed`() {
        val n = gh.addIssue("demo-app", "Feature", "body").number
        compare({ it == "release/s" }, FileChange('A', "$dir/V16__mine.sql"))
        val r0 = release("s", listOf(n), mode = ReleaseMode.AUTOMATIC)
        val r = awaitRelease(r0.id, "ready with a notice") { it.status == ReleaseStatus.READY && it.notice != null }
        val pr = r.prNumber!!
        val notice = "Database migration conflict with `main`: `$dir/V16__mine.sql` has the same version (16) as " +
            "`$dir/V16__other.sql`. The release is not merged. Fix the migrations on `release/s` (merge `main` into it; a new " +
            "migration needs a version above every migration on `main`, and existing ones must stay unchanged), then use \"Check again\"."
        assertEquals(notice, r.notice)
        assertTrue("release/s -> main" in world.git.comparisons, world.git.comparisons.toString())
        // Automatic and green, yet not merged, and not announced as ready.
        runBlocking { s.releaseMerger.tick() }
        assertEquals(ReleaseStatus.READY, s.releaseStore.get(r.id)!!.status)
        assertTrue(merges().none { it.startsWith("merge-") }, merges().toString())
        val prComments = { gh.client("fake-alice").listComments("test-org", "demo-app", pr).map { it.body ?: "" } }
        assertTrue(prComments().none { it.contains("is ready to release") }, prComments().toString())
        // Approval refuses, naming the problem.
        val e = assertFailsWith<ApiException> { runBlocking { s.releases.approve(alice, r.id) } }
        assertEquals("release_migration_conflict", e.code)
        assertEquals("$notice Nothing was merged.", e.message)
        assertTrue(merges().none { it.startsWith("merge-") })

        // Fixed on the release branch: "Check again" clears the notice and the automatic release goes ahead.
        compare({ it == "release/s" }, FileChange('A', "$dir/V17__mine.sql"))
        runBlocking { s.releases.check(alice, r.id) }
        val done = awaitRelease(r.id, "released") { it.status == ReleaseStatus.RELEASED }
        assertTrue("merge-rebase demo-app #$pr" in merges(), merges().toString())
        assertTrue(done.notice == null || !done.notice!!.startsWith("Database migration"), done.notice)
    }

    @Test
    fun `a manual release is approved once its migrations no longer collide`() {
        val n = gh.addIssue("demo-app", "Feature", "body").number
        compare({ it == "release/m" }, FileChange('D', "$dir/V15__earlier.sql"))
        val r0 = release("m", listOf(n))
        val r = awaitRelease(r0.id, "ready with a notice") { it.status == ReleaseStatus.READY && it.notice != null }
        assertTrue(r.notice!!.startsWith("Database migration conflict with `main`: `$dir/V15__earlier.sql` was removed."), r.notice)
        assertEquals("release_migration_conflict", assertFailsWith<ApiException> { runBlocking { s.releases.approve(alice, r.id) } }.code)
        world.git.comparison = { _, _ -> BranchComparison(base, emptyList()) }
        assertNull(runBlocking { s.releases.check(alice, r.id) }.notice)
        assertEquals(ReleaseStatus.RELEASED, runBlocking { s.releases.approve(alice, r.id) }.status)
    }
}
