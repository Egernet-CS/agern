package dk.egernet.factory

import dk.egernet.factory.github.FakeGitHub
import dk.egernet.factory.workspace.WORKSPACE_FILE
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue routing, the pull request in the member repository and the merge (docs/ARCHITECTURE.md §6 "Workspace runs"). */
class WorkspaceRoutingTest {
    private val world = TestWorld()
    private val s = world.services
    private val alice = world.user("alice")
    private val bob = world.user("bob")
    private val ws = "demo-workspace"

    @AfterTest fun tearDown() = world.close()

    private fun script(approver: String = "APPROVE", beforeSpec: () -> Unit = {}) {
        world.launcher.stage = { p ->
            p.init()
            when (p.spec.kind) {
                "spec-writer" -> { beforeSpec(); p.result("## Spec\nDo it.") }
                "builder" -> p.result("Built it.")
                "approver" -> p.result(null, buildJsonObject { put("verdict", approver); put("summary", "approver: $approver") })
                else -> p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
            }
            p.exit(0)
        }
    }

    private fun start(n: Int, user: dk.egernet.factory.db.UserRecord = alice, repo: String = ws) =
        runBlocking { s.orchestrator.start(user, repo, n, null) }

    private fun refusal(n: Int, user: dk.egernet.factory.db.UserRecord = alice): ApiException =
        assertFailsWith<ApiException> { start(n, user) }

    @Test
    fun `an issue needs exactly one member label`() {
        script()
        val none = refusal(3)
        assertEquals(409, none.status.value)
        assertEquals("member_label_missing", none.code)
        assertEquals(
            "Issue #3 has no member label. Add exactly one of these labels on GitHub to say which repository it belongs to: " +
                "`app` (demo-ws-app), `demo-ws-tools` (demo-ws-tools), `lab` (demo-ws-lab). " +
                "You can set it with `gh issue edit 3 --repo test-org/demo-workspace --add-label \"<label>\"`.",
            none.message,
        )
        val several = refusal(4)
        assertEquals("member_label_ambiguous", several.code)
        assertEquals(
            "Issue #4 carries the labels of several members (app, demo-ws-tools). A run changes one member: keep exactly one of them. " +
                "You can set it with `gh issue edit 4 --repo test-org/demo-workspace --remove-label \"<label>\"`.",
            several.message,
        )
        // bob cannot read demo-ws-lab: 403, and no run was created for any refusal.
        val hidden = refusal(5, bob)
        assertEquals(403, hidden.status.value)
        assertEquals("member_not_accessible", hidden.code)
        assertEquals("Your GitHub token cannot read test-org/demo-ws-lab (member `lab`), so you cannot run this issue.", hidden.message)
        assertTrue(s.runs.list(ws, activeOnly = false).isEmpty())
    }

    @Test
    fun `an invalid definition refuses every run`() {
        world.github.setFile(ws, WORKSPACE_FILE, "members:\n  - { directory: ., repository: demo-ws-app }\n  - { what: 1 }")
        s.workspaceDefs.invalidate()
        val e = refusal(1)
        assertEquals(409, e.status.value)
        assertEquals("workspace_invalid", e.code)
        assertTrue(e.message.startsWith("The workspace definition .agern/workspace.yml is invalid: members[0].directory:"), e.message)
        assertTrue(e.message.endsWith(" more). Fix it on `main` first."), e.message)
        assertTrue(s.runs.list(ws, activeOnly = false).isEmpty())
    }

    @Test
    fun `a project without the file is unaffected`() {
        script()
        val run = start(1, repo = "demo-app")
        val done = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertNull(done.targetRepo)
        assertEquals("main", done.baseBranch)
        assertTrue(done.contextRepos.isEmpty())
        val pr = world.github.pullMeta[done.prNumber!!]!!
        assertEquals("demo-app" to "main", pr.repo to pr.base)
        assertTrue(world.github.pullBodies[done.prNumber!!]!!.startsWith("Closes #1\n"))
        assertTrue(world.github.actions.contains("comment demo-app#1 **Agern: merged** pull request #${done.prNumber} (approved by the approver agent)."))
    }

    @Test
    fun `a labelled issue runs in its member repository against the member's base branch`() {
        script()
        val run = start(1)
        assertEquals("demo-ws-app", run.targetRepo)
        assertEquals("app", run.targetDir)
        assertEquals("dev", run.baseBranch)
        val done = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        val n = done.prNumber!!
        val pr = world.github.pullMeta[n]!!
        assertEquals("demo-ws-app", pr.repo)
        assertEquals("dev", pr.base)
        assertEquals(done.branch, pr.head)
        assertTrue(done.branch!!.startsWith("factory/issue-1-show-prices-with-currency-"), done.branch)
        assertEquals("Show prices with currency (test-org/demo-workspace#1)", pr.title)
        assertTrue(world.github.pullBodies[n]!!.startsWith("Closes test-org/demo-workspace#1\n"), world.github.pullBodies[n])
        // The squash commit on dev has the same title; the workspace issue is closed by Agern.
        assertEquals("Show prices with currency (test-org/demo-workspace#1)", world.github.commitsOf("demo-ws-app", "dev").last())
        val actions = world.github.actions.toList()
        assertTrue(actions.contains("merge demo-ws-app #$n"), actions.toString())
        assertTrue(actions.contains("close demo-workspace#1"), actions.toString())
        assertTrue(actions.contains("comment demo-workspace#1 **Agern: merged** pull request test-org/demo-ws-app#$n (approved by the approver agent)."), actions.toString())
        assertTrue(actions.any { it.startsWith("comment demo-workspace#1 **Agern: spec ready**") })
        assertTrue(actions.none { it.startsWith("comment demo-ws-app#1 ") || it.startsWith("close demo-ws-app") })
        // Checked out: the target and, read-only, every other member alice can read.
        assertEquals(listOf("demo-ws-app", "demo-ws-tools", "demo-ws-lab"), done.contextRepos)
        assertTrue(s.sessions.byRun(run.id).all { it.contextRepos == done.contextRepos })
    }

    @Test
    fun `agents work in the workspace root and are told about the layout`() {
        script()
        val run = start(1)
        world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        val root = s.workspaces.runDir("alice", ws, run.id)
        assertTrue(world.launcher.launched.all { it.spec.workDir == root }, world.launcher.launched.map { it.spec.workDir }.toString())
        assertTrue(world.git.worktrees.contains("${s.runs.get(run.id)!!.branch} <- dev"), world.git.worktrees.toString())
        val detached = world.git.detached.toList()
        assertTrue(detached.contains("$root @ main"), detached.toString())
        assertTrue(detached.contains("${root.resolve("tools/cli")} @ main"), detached.toString())
        assertTrue(detached.contains("${root.resolve("lab")} @ main"), detached.toString())
        val builder = world.launcher.byRole("builder").single().spec
        val prompt = builder.prompt!!
        assertTrue(prompt.contains("Workspace: test-org/demo-workspace — several repositories checked out side by side under your working directory.\n"), prompt)
        assertTrue(
            prompt.contains(
                "Target member directory: `app` (repository test-org/demo-ws-app, base branch `dev`). Run every git command for this " +
                    "change there, e.g. `git -C app status`. Only commits in `app/` become the pull request.\n",
            ),
            prompt,
        )
        assertTrue(prompt.contains("- `.` — test-org/demo-workspace (workspace root files)\n"))
        assertTrue(prompt.contains("- `tools/cli` — test-org/demo-ws-tools @ main\n"))
        assertTrue(prompt.contains("The issue is test-org/demo-workspace#1; the pull request is opened in test-org/demo-ws-app against `dev`."))
        val review = world.launcher.byRole("ux").single().spec.prompt!!
        assertTrue(review.contains("`git -C app diff origin/dev...HEAD`"), review)
        assertTrue(review.contains("`app/.agern/preview.md`"), review)
        assertTrue(world.launcher.byRole("approver").single().spec.prompt!!.contains("git -C app diff origin/dev...HEAD"))
        assertTrue(builder.appendSystemPrompt!!.contains("This project is a workspace: your working directory is a checkout of the workspace repository `test-org/demo-workspace`"))
        assertTrue(!builder.appendSystemPrompt!!.contains("Your working directory is a git checkout of it."))
    }

    @Test
    fun `a member the owner cannot read is left out and named in the prompt`() {
        script()
        val carol = world.user("carol")
        val run = start(2, carol)
        val done = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertEquals("tools/cli", done.targetDir)
        assertEquals("main", done.baseBranch)
        assertEquals(listOf("demo-ws-tools", "demo-ws-app"), done.contextRepos)
        val prompt = world.launcher.byRole("builder").single().spec.prompt!!
        assertTrue(prompt.contains("Not checked out: `lab` (your GitHub token cannot read it)"), prompt)
        assertTrue(world.git.detached.none { it.contains("/lab @") })
    }

    @Test
    fun `needs human labels and comments on the workspace issue`() {
        script(approver = "ESCALATE")
        val run = start(1)
        val done = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.NEEDS_HUMAN, done.status)
        val actions = world.github.actions.toList()
        assertTrue(actions.contains("label demo-workspace#1 needs-human"), actions.toString())
        assertTrue(actions.any { it.startsWith("comment demo-workspace#1 **Agern: needs a human.**") })
        // The owner approves: the merge goes to the member, the label comes off the workspace issue.
        runBlocking { s.orchestrator.approve(alice, run.id) }
        assertEquals(RunStatus.MERGED, s.runs.get(run.id)!!.status)
        assertTrue(world.github.actions.contains("unlabel demo-workspace#1 needs-human"))
        assertTrue(world.github.actions.contains("merge demo-ws-app #${done.prNumber}"))
        assertTrue(world.github.actions.contains("close demo-workspace#1"))
    }

    @Test
    fun `the stored target survives a later edit of the file`() {
        val edited = CountDownLatch(1)
        script(beforeSpec = { edited.await(10, TimeUnit.SECONDS) })
        val run = start(1)
        // Renamed label and a new base branch after the run started: the run keeps what it stored.
        world.github.setFile(ws, WORKSPACE_FILE, FakeGitHub.DEMO_WORKSPACE_FILE.replace("label: app", "label: shop").replace("branch: dev", "branch: main"))
        s.workspaceDefs.invalidate()
        edited.countDown()
        val done = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertEquals("dev", world.github.pullMeta[done.prNumber!!]!!.base)
    }

    @Test
    fun `a step retry fails when its member is no longer declared`() {
        script()
        world.git.failPush = "remote rejected"
        val run = start(1)
        val failed = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(FailedStep.PUSH, failed.failedStep, failed.error)
        world.git.failPush = null
        world.github.setFile(ws, WORKSPACE_FILE, FakeGitHub.DEMO_WORKSPACE_FILE.replace("directory: app", "directory: shop"))
        s.workspaceDefs.invalidate()
        runBlocking { s.orchestrator.retry(alice, run.id, RetryMode.STEP) }
        val again = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.FAILED, again.status)
        assertEquals("Member `app` (demo-ws-app) is no longer part of the workspace definition", again.error)
    }

    @Test
    fun `releases are available for workspace projects`() {
        val r = runBlocking { s.releases.create(alice, ws, "1.0", "") }
        // An issue with one member label is planned; the routing refusals of runs apply, all or nothing.
        runBlocking { s.releases.assign(alice, ws, listOf(1), r.id) }
        assertEquals("member_label_missing", assertFailsWith<ApiException> { runBlocking { s.releases.assign(alice, ws, listOf(2, 3), r.id) } }.code)
        assertEquals(listOf(1), s.releaseStore.get(r.id)!!.issues.map { it.number })
        // An invalid definition refuses the start (after the release_empty check, before the claim).
        world.github.setFile(ws, WORKSPACE_FILE, "members: [")
        s.workspaceDefs.invalidate()
        val e = assertFailsWith<ApiException> { runBlocking { s.releases.start(alice, r.id) } }
        assertEquals("workspace_invalid", e.code)
        assertEquals(ReleaseStatus.PLANNING, s.releaseStore.get(r.id)!!.status)
        world.github.setFile(ws, WORKSPACE_FILE, FakeGitHub.DEMO_WORKSPACE_FILE)
        s.workspaceDefs.invalidate()
    }

    @Test
    fun `continue keeps the target of the run it continues`() {
        script(approver = "ESCALATE")
        val first = start(1)
        world.awaitRun(first.id, setOf(RunStatus.NEEDS_HUMAN))
        runBlocking { s.orchestrator.cancel(alice, first.id) }
        // The label moves to another member; continuing still works on demo-ws-app.
        world.github.repos.getValue(ws).issues.replaceAll { if (it.number == 1) it.copy(labels = emptyList()) else it }
        script()
        val next = runBlocking { s.orchestrator.retry(alice, first.id, RetryMode.CONTINUE) }
        assertEquals("demo-ws-app", next.targetRepo)
        assertEquals("app", next.targetDir)
        assertEquals("dev", next.baseBranch)
        val done = world.awaitRun(next.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertTrue(world.git.worktrees.contains("${done.branch} <- ${s.runs.get(first.id)!!.branch}"), world.git.worktrees.toString())
        // Earlier pull requests of the same member are named.
        assertTrue(world.github.pullBodies[done.prNumber!!]!!.contains("Earlier runs of this issue left #${s.runs.get(first.id)!!.prNumber} open"))
        // start_over looks at the labels again: there are none now.
        val e = assertFailsWith<ApiException> { runBlocking { s.orchestrator.retry(alice, first.id, RetryMode.START_OVER) } }
        assertEquals("member_label_missing", e.code)
    }
}
