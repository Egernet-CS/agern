package dk.egernet.factory

import dk.egernet.factory.db.RunRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * After a run on a submodule member merges, Agern moves the workspace's gitlink in a pull request of its own
 * (docs/ARCHITECTURE.md §6 "Workspace runs"). The fake GitHub's seeded `demo-shop` (members from `.gitmodules`).
 */
class PointerBumpTest {
    private val world = TestWorld()
    private val s = world.services
    private val gh = world.github
    private val alice = world.user("alice")
    private val ws = "demo-shop"

    @AfterTest fun tearDown() = world.close()

    private fun script(approver: String = "APPROVE") {
        world.launcher.stage = { p ->
            p.init()
            when (p.spec.kind) {
                "spec-writer" -> p.result("## Spec\nDo it.")
                "builder" -> p.result("Built it.")
                "approver" -> p.result(null, buildJsonObject { put("verdict", approver); put("summary", "approver: $approver") })
                else -> p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
            }
            p.exit(0)
        }
    }

    private fun run(issue: Int = 1): RunRecord {
        script()
        val r = runBlocking { s.orchestrator.start(alice, ws, issue, null) }
        val done = world.awaitRun(r.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        return done
    }

    private fun comments(issue: Int = 1) = gh.repos.getValue(ws).comments[issue].orEmpty().mapNotNull { it.body }
    private fun pointer(path: String) = gh.gitlinks["$ws:main"]?.get(path)
    /** The merge commit of the member pull request (`sha-<base>-<n>` in the fake). */
    private fun mergeSha(run: RunRecord) = gh.pullMeta.getValue(run.prNumber!!).mergeCommit!!
    private fun transcript(run: RunRecord): String {
        val sid = s.runs.stages(run.id).last { it.sessionId != null }.sessionId!!
        return s.events.list(sid, 0, 1000).joinToString("\n") { it.payload.toString() }
    }

    @Test
    fun `issues are routed by the label of a submodule member`() {
        val n = gh.addIssue(ws, "Untargeted", "Which repository?").number
        val e = kotlin.test.assertFailsWith<ApiException> { runBlocking { s.orchestrator.start(alice, ws, n, null) } }
        assertEquals("member_label_missing", e.code)
        assertEquals(
            "Issue #$n has no member label. Add exactly one of these labels on GitHub to say which repository it belongs to: " +
                "`demo-shop-api` (demo-shop-api), `demo-shop-web` (demo-shop-web).",
            e.message,
        )
    }

    @Test
    fun `the pointer moves to the merge commit in a merged pull request of the workspace`() {
        val done = run()
        assertEquals("demo-shop-api", done.targetRepo)
        val sha = mergeSha(done)
        assertEquals(sha, pointer("services/api"))
        val bump = gh.pullMeta.values.single { it.repo == ws }
        assertTrue(bump.head.startsWith("factory/bump-services-api-${done.id.toString().take(8)}"), bump.head)
        assertEquals("main", bump.base)
        assertTrue(bump.merged)
        assertEquals("Bump services/api to ${sha.take(12)} (test-org/demo-shop-api#${done.prNumber})", bump.title)
        assertTrue(gh.actions.contains("delete-branch $ws ${bump.head}"), gh.actions.toString())
        // Recorded on the run: an issue comment and an event in its transcript.
        assertTrue(comments().any { it.startsWith("**Agern: submodule pointer** `services/api` → `${sha.take(12)}`: merged pull request") }, comments().toString())
        assertTrue(transcript(done).contains("submodule pointer"), transcript(done))
        // The other member's pointer did not move.
        assertEquals("0".repeat(39) + "2", pointer("web"))
    }

    @Test
    fun `the bump is refused when the member repository does not have the commit`() {
        // The member's first merge in the fake is `sha-main-2`.
        gh.missingCommits += "demo-shop-api:sha-main-2"
        val done = run()
        assertEquals("sha-main-2", mergeSha(done))
        assertEquals("0".repeat(39) + "1", pointer("services/api"))
        assertTrue(gh.actions.none { it.startsWith("gitlink-commit") }, gh.actions.toString())
        assertTrue(comments().any { it.startsWith("**Agern: submodule pointer not bumped.** The merge commit `sha-main-2`") }, comments().toString())
        assertTrue(gh.pullMeta.values.none { it.repo == ws })
    }

    @Test
    fun `the merge guard checks the bump before it is merged`() {
        // The bump branch moves services/api to a commit the submodule's remote does not have (as git would see it).
        world.git.gitlinks = { _, head ->
            if (head.startsWith("factory/bump-")) listOf(dk.egernet.factory.git.GitlinkChange("services/api", "a".repeat(40), "https://github.invalid/x.git"))
            else emptyList()
        }
        world.git.remoteCommit = { _, _ -> false }
        run()
        assertEquals("0".repeat(39) + "1", pointer("services/api"))
        val bump = gh.pullMeta.values.single { it.repo == ws }
        assertEquals("open", bump.state)
        assertTrue(comments().any { it.contains("was not merged. Submodule commit missing") }, comments().toString())
    }

    @Test
    fun `a bump whose merge fails is retried once from the new default branch`() {
        gh.failNextMerges[ws] = 1
        val done = run()
        assertEquals(mergeSha(done), pointer("services/api"))
        assertEquals(2, gh.actions.count { it.startsWith("gitlink-commit $ws services/api") }, gh.actions.toString())
        assertEquals(1, gh.actions.count { it.startsWith("update-branch $ws factory/bump-") }, gh.actions.toString())
        assertEquals(1, gh.pullMeta.values.count { it.repo == ws })
    }

    @Test
    fun `a bump that cannot be merged is left open for a human`() {
        gh.failNextMerges[ws] = 2
        val done = run()
        assertEquals("0".repeat(39) + "1", pointer("services/api"))
        val bump = gh.pullMeta.values.single { it.repo == ws }
        assertEquals("open", bump.state)
        assertTrue(comments().any { it.contains("could not be merged") && it.contains("left open for a human") }, comments().toString())
        // The run itself merged.
        assertEquals(RunStatus.MERGED, s.runs.get(done.id)!!.status)
    }

    @Test
    fun `nothing to do when the pointer is already there, and nothing for a cloned member`() {
        // The fake's first merge of demo-shop-web is `sha-main-2` too: let the workspace already point there.
        gh.gitlinks.getValue("$ws:main")["web"] = "sha-main-2"
        run(issue = 2)
        assertTrue(gh.actions.none { it.startsWith("gitlink-commit") }, gh.actions.toString())
        assertTrue(comments(2).any { it.contains("already points at") }, comments(2).toString())

        // demo-workspace's members are cloned, not submodules: no bump.
        script()
        val r = runBlocking { s.orchestrator.start(alice, "demo-workspace", 1, null) }
        assertEquals(RunStatus.MERGED, world.awaitRun(r.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        assertTrue(gh.pullMeta.values.none { it.repo == "demo-workspace" })
        assertTrue(gh.repos.getValue("demo-workspace").comments[1].orEmpty().none { it.body!!.contains("submodule pointer") })
    }
}
