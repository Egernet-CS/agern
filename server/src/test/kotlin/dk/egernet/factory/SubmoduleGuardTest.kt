package dk.egernet.factory

import dk.egernet.factory.db.ReleaseRecord
import dk.egernet.factory.git.GitException
import dk.egernet.factory.git.GitlinkChange
import dk.egernet.factory.git.UnpushedSubmodule
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Agern does not merge a pull request whose submodule pointer names a commit the submodule's remote does not have, and
 * saves submodule commits that exist only in a run's checkout (docs/ARCHITECTURE.md §6 "Submodule pointers"). The git
 * side is [FakeGitOps.gitlinks] / [FakeGitOps.remoteCommit] here and real git in [SubmodulePointerGitTest].
 */
class SubmoduleGuardTest {
    private val world = TestWorld(twoFactor = false)
    private val s = world.services
    private val gh = world.github
    private val alice = world.user("alice")

    private val sha = "1234567890abcdef1234567890abcdef12345678"
    private val url = "https://github.invalid/test-org/sub.git"

    @AfterTest fun tearDown() {
        world.close()
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

    /** Every branch moves submodule `libs/sub` to [sha]. */
    private fun pointer(u: String? = url) {
        world.git.gitlinks = { _, _ -> listOf(GitlinkChange("libs/sub", sha, u)) }
    }

    private fun start(issue: Int = 1) = runBlocking { s.orchestrator.start(alice, "demo-app", issue, null) }
    private fun await(id: UUID) = world.awaitRun(id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
    private fun merges() = gh.actions.filter { it.startsWith("merge") }
    private fun comments(n: Int) = gh.client("fake-alice").listComments("test-org", "demo-app", n).map { it.body ?: "" }
    private fun warnings(runId: UUID): List<String> {
        val builder = s.runs.stages(runId).filter { it.role == "builder" }.mapNotNull { it.sessionId }
        return builder.flatMap { sid -> s.events.list(sid, 0, 5000) }
            .filter { it.type == "system" && (it.payload as JsonObject)["level"]!!.jsonPrimitive.content == "warn" }
            .map { (it.payload as JsonObject)["text"]!!.jsonPrimitive.content }
    }

    @Test
    fun `a pointer to a commit the submodule remote lacks is not merged, and merges once the commit is pushed`() {
        pointer()
        world.git.remoteCommit = { u, c -> !(u == url && c == sha) }
        val run = start()
        val held = await(run.id)
        assertEquals(RunStatus.NEEDS_HUMAN, held.status)
        assertEquals(
            "Submodule commit missing: pull request #${held.prNumber} moves submodule `libs/sub` to commit `$sha`, which the " +
                "submodule's repository does not have, so merging it would leave `main` pinned to a commit no one can fetch. " +
                "Nothing was merged. Push that commit to the submodule's repository (or reset the pointer on the pull " +
                "request's branch to a commit that is there), then approve again.",
            held.error,
        )
        assertEquals(FailureKind.SUBMODULE_COMMIT_MISSING, held.failureKind)
        assertEquals(FailedStep.MERGE, held.failedStep)
        assertEquals(emptyList(), merges(), "nothing was merged")
        assertTrue(comments(1).any { it.startsWith("**Agern: needs a human.** Submodule commit missing:") }, comments(1).toString())

        // The owner's approval checks again: still missing, still not merged.
        runBlocking { s.orchestrator.approve(alice, run.id) }
        assertEquals(RunStatus.NEEDS_HUMAN, await(run.id).status)
        assertEquals(emptyList(), merges())

        // Pushed to the submodule's repository: the merge retry goes through.
        world.git.remoteCommit = { _, _ -> true }
        runBlocking { s.orchestrator.retryStep(alice, s.runs.get(run.id)!!) }
        assertEquals(RunStatus.MERGED, await(run.id).status)
        assertEquals(listOf("merge demo-app #${held.prNumber}"), merges())
    }

    @Test
    fun `a pointer to a commit the remote has merges`() {
        pointer()
        val checked = mutableListOf<String>()
        world.git.remoteCommit = { u, c -> checked += "$u $c"; true }
        assertEquals(RunStatus.MERGED, await(start().id).status)
        assertEquals(listOf("$url $sha"), checked)
        assertEquals(1, merges().size)
    }

    @Test
    fun `a pointer that cannot be checked is not merged either`() {
        pointer()
        world.git.remoteCommit = { _, _ -> throw GitException("could not resolve host") }
        val held = await(start(1).id)
        assertEquals(RunStatus.NEEDS_HUMAN, held.status)
        assertTrue(held.error!!.startsWith("Submodule commit not checked: pull request #${held.prNumber} moves submodule `libs/sub`"), held.error)
        assertTrue("could not resolve host" !in held.error!!, "git's output stays in the log")
        assertEquals(FailureKind.STEP, held.failureKind)

        // No URL for the submodule in .gitmodules, or the branches cannot be compared at all: refused too.
        pointer(u = null)
        val noUrl = await(start(2).id)
        assertTrue(noUrl.error!!.startsWith("Submodule commit not checked:"), noUrl.error)
        world.git.gitlinks = { _, _ -> throw GitException("git fetch failed") }
        val noCompare = await(start(gh.addIssue("demo-app", "Third", "body").number).id)
        assertTrue(noCompare.error!!.startsWith("Submodule pointers not checked:"), noCompare.error)
        assertEquals(emptyList(), merges())
    }

    @Test
    fun `submodule commits are warned about after the build and saved as a bundle when the run ends`() {
        world.git.unpushed = listOf(UnpushedSubmodule("libs/sub", sha, 2))
        val run = await(start().id)
        assertEquals(RunStatus.MERGED, run.status)
        val w = warnings(run.id)
        assertTrue(w.any { it.startsWith("Commits inside submodules: `libs/sub` (2 commit(s)) on none of the submodule's remote branches.") }, w.toString())
        val bundle = s.workspaces.bundleDir("alice", "demo-app", run.id).resolve("libs_sub.bundle")
        assertTrue(w.any { it.startsWith("Unpushed submodule commits saved: submodule `libs/sub` had 2 commit(s) up to `$sha`") && "`$bundle`" in it }, w.toString())
    }

    // ------------------------------------------------------------ releases

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

    @Test
    fun `a release whose pointer names a missing commit is refused with 409 until the commit is pushed`() {
        val n = gh.addIssue("demo-app", "Feature", "body").number
        // Only the release branch moves the pointer (its runs merged into it before the commit went missing).
        world.git.gitlinks = { _, head -> if (head == "release/r") listOf(GitlinkChange("libs/sub", sha, url)) else emptyList() }
        world.git.remoteCommit = { _, _ -> false }
        val r0 = runBlocking {
            val r = s.releases.create(alice, "demo-app", "r", "", null)
            s.releases.assign(alice, "demo-app", listOf(n), r.id)
            s.releases.start(alice, r.id)
            s.releaseStore.get(r.id)!!
        }
        val ready = awaitRelease(r0.id, "ready") { it.status == ReleaseStatus.READY && it.prNumber != null }
        val e = assertFailsWith<ApiException> { runBlocking { s.releases.approve(alice, ready.id) } }
        assertEquals("release_submodule_commit_missing", e.code)
        assertEquals(409, e.status.value)
        assertTrue(e.message!!.startsWith("Submodule commit missing: pull request #${ready.prNumber} moves submodule `libs/sub` to commit `$sha`"), e.message)
        assertTrue(merges().none { it.startsWith("merge-") }, merges().toString())
        assertEquals(e.message, s.releaseStore.get(ready.id)!!.notice)

        world.git.remoteCommit = { _, _ -> true }
        assertEquals(ReleaseStatus.RELEASED, runBlocking { s.releases.approve(alice, ready.id) }.status)
    }
}
