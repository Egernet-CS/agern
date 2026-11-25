package dk.egernet.factory

import dk.egernet.factory.db.ReleaseRecord
import dk.egernet.factory.github.GhLabel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * In a workspace whose members come from `.gitmodules` alone, an issue without a member label is a run of the
 * workspace repository itself, as for a project that is one repository, and releases build such runs
 * (docs/ARCHITECTURE.md §6 "Workspace runs"). The fake GitHub's seeded `demo-shop`.
 */
class SubmoduleParentRunTest {
    private val world = TestWorld()
    private val s = world.services
    private val gh = world.github
    private val alice = world.user("alice")
    private val ws = "demo-shop"

    @AfterTest fun tearDown() = world.close()

    init {
        world.launcher.stage = { p ->
            p.init()
            when (p.spec.kind) {
                "spec-writer" -> p.result("## Spec\nDo it.")
                "builder" -> p.result("Built it.")
                "approver" -> p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") })
                else -> p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
            }
            p.exit(0)
        }
    }

    private fun start(n: Int, repo: String = ws) = runBlocking { s.orchestrator.start(alice, repo, n, null) }
    private fun await(id: UUID) = world.awaitRun(id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
    private fun code(block: () -> Unit) = assertFailsWith<ApiException> { block() }.code

    @Test
    fun `an issue without a member label changes the workspace repository itself`() {
        val n = gh.addIssue(ws, "Update the plan", "The plan in docs/ is out of date.").number
        val done = await(start(n).id)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertNull(done.targetRepo)
        assertNull(done.targetDir)
        assertTrue(gh.actions.any { it.startsWith("pr $ws factory/issue-$n-") }, gh.actions.toString())
        assertTrue(gh.actions.contains("close $ws#$n"), gh.actions.toString())
        // No pointer bump for a run of the workspace repository.
        assertTrue(gh.actions.none { it.startsWith("gitlink-commit") }, gh.actions.toString())
        val prompt = world.launcher.byRole("builder").single().spec.prompt.orEmpty()
        assertTrue(prompt.contains("Target: the workspace repository itself; submodules are read-only context."), prompt)
        assertTrue(prompt.contains("- `services/api` — test-org/demo-shop-api (label `demo-shop-api`)"), prompt)
        assertTrue(prompt.contains("- `vendor/icons` (not a member)"), prompt)
        assertFalse(prompt.contains("Target member directory"), prompt)
    }

    @Test
    fun `several member labels are still refused, and a workspace yml still needs one`() {
        val both = gh.addIssue(ws, "Both", "x", listOf("demo-shop-api", "demo-shop-web")).number
        assertEquals("member_label_ambiguous", code { start(both) })
        // demo-workspace is defined by .agern/workspace.yml: an unlabelled issue (#3) is refused as before.
        assertEquals("member_label_missing", code { start(3, "demo-workspace") })
        // Releases are available there; a member label is still needed to plan an issue.
        val r = runBlocking { s.releases.create(alice, "demo-workspace", "r", "") }
        assertEquals("member_label_missing", code { runBlocking { s.releases.assign(alice, "demo-workspace", listOf(3), r.id) } })
    }

    private fun awaitRelease(id: UUID, done: (ReleaseRecord) -> Boolean): ReleaseRecord {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val r = s.releaseStore.get(id)!!
            if (done(r)) return r
            Thread.sleep(20)
        }
        error("release ${s.releaseStore.get(id)} did not settle")
    }

    @Test
    fun `a release plans member issues and builds unlabelled issues in the workspace repository`() {
        val r = runBlocking { s.releases.create(alice, ws, "1.0", "") }
        // Issue #1 carries demo-shop-api's label: accepted (it is built in the member).
        runBlocking { s.releases.assign(alice, ws, listOf(1), r.id) }
        runBlocking { s.releases.assign(alice, ws, listOf(1), null) }
        val plain = gh.addIssue(ws, "Write the changelog", "x").number
        val labelledLater = gh.addIssue(ws, "Fix the API docs", "x").number
        runBlocking { s.releases.assign(alice, ws, listOf(plain, labelledLater), r.id) }
        // Two member labels added after planning hold the issue when its turn comes.
        synchronized(gh) {
            gh.repos.getValue(ws).issues.replaceAll {
                if (it.number == labelledLater) it.copy(labels = listOf(GhLabel("demo-shop-api"), GhLabel("demo-shop-web"))) else it
            }
        }
        runBlocking { s.releases.start(alice, r.id) }
        val settled = awaitRelease(r.id) { rel -> rel.issues.none { it.state == ReleaseIssueState.WAITING || it.state == ReleaseIssueState.RUNNING } }
        assertEquals(ReleaseIssueState.MERGED, settled.issues.single { it.number == plain }.state)
        val held = settled.issues.single { it.number == labelledLater }
        assertEquals(ReleaseIssueState.HELD, held.state)
        assertTrue(held.note!!.contains("carries the labels of several members"), held.note)
        val run = s.runs.list(ws, activeOnly = false).single { it.issueNumber == plain }
        assertNull(run.targetRepo)
        assertTrue(gh.actions.any { it.startsWith("pr $ws factory/release-1.0/issue-$plain-") }, gh.actions.toString())
        // Only the workspace repository was touched: no member branch, the release stays single-repository.
        assertTrue(settled.repos.isEmpty())
    }
}
