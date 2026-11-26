package dk.egernet.factory

import dk.egernet.factory.db.RunRecord
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.mcp.McpGrant
import dk.egernet.factory.mcp.McpRegistry
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The per-project merge policy (docs/ARCHITECTURE.md §6 "Merge policy"), with the fake launcher and fake GitHub. */
class MergePolicyTest {
    private val world = TestWorld(twoFactor = false)
    private val s = world.services
    private val gh = world.github
    private val alice = world.user("alice")
    private val bob = world.user("bob")
    private val root = world.user("root", admin = true)

    @AfterTest fun tearDown() = world.close()

    init {
        world.git.deleteWorktrees = true
    }

    private fun policy(p: String, repo: String = "demo-app", reviewers: List<String> = emptyList(), teams: List<String> = emptyList()) {
        s.projectSettings.put(repo, ProjectSettingsDto(p, reviewers, teams), root.id)
    }

    private fun review(verdict: String) = buildJsonObject {
        put("verdict", verdict); put("summary", "$verdict summary")
        put("findings", buildJsonArray {
            if (verdict == "CHANGES") addJsonObject { put("severity", "major"); put("file", "a.kt"); put("detail", "fix it") }
        })
    }

    private fun script(approverVerdict: String = "APPROVE", reviewVerdict: String = "PASS", gate: CountDownLatch? = null) {
        world.launcher.stage = { p ->
            p.init()
            when (p.spec.kind) {
                "spec-writer" -> p.result("## Spec\nDo the thing.")
                "builder" -> p.result("Built it.")
                "approver" -> {
                    gate?.await(10, TimeUnit.SECONDS)
                    p.result(null, buildJsonObject { put("verdict", approverVerdict); put("summary", "approver says $approverVerdict.") })
                }
                else -> p.result(null, review(reviewVerdict))
            }
            p.exit(0)
        }
    }

    private fun start(issue: Int = 1, repo: String = "demo-app", by: UserRecord = alice) =
        runBlocking { s.orchestrator.start(by, repo, issue, null) }

    private fun finished(run: RunRecord) = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
    private fun code(block: suspend () -> Unit): ApiException = assertFailsWith<ApiException> { runBlocking { block() } }
    private fun comments(issue: Int = 1, repo: String = "demo-app") = gh.repos.getValue(repo).comments[issue].orEmpty().mapNotNull { it.body }
    private fun merges() = gh.actions.filter { it.startsWith("merge") }
    private fun approverPrompt() = world.launcher.byRole("approver").last().spec.prompt!!
    private fun worktreeRemoved(run: RunRecord) = world.git.removedWorktrees.any { it.toString().contains(run.id.toString()) }

    // ------------------------------------------------------------ settings API

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String): HttpClient {
        val c = createClient { install(HttpCookies) }
        assertEquals(200, c.post("/api/auth/login") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
        }.status.value)
        c.put("/api/me/github-token") { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"token":"fake-$user"}""") }
        return c
    }

    private suspend fun HttpClient.putSettings(body: String, repo: String = "demo-app"): HttpResponse = put("/api/projects/$repo/settings") {
        header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody(body)
    }

    private fun json(t: String) = AppJson.parseToJsonElement(t).jsonObject
    private fun JsonObject.settings() = this["settings"]!!.jsonObject
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.list(k: String) = this[k]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `settings are admin-only, validated, stored and shown on the project`() = testApplication {
        application { agernModule(s) }
        val admin = login("root", "pw-r")
        val user = login("alice", "pw-a")

        // Defaults without a row, in the detail and the list.
        val d = json(user.get("/api/projects/demo-app").bodyAsText()).settings()
        assertEquals("automatic", d.str("mergePolicy"))
        assertEquals(emptyList(), d.list("reviewers"))
        val all = AppJson.parseToJsonElement(user.get("/api/projects").bodyAsText()).jsonArray.map { it.jsonObject }
        assertTrue(all.all { it.settings().str("mergePolicy") == "automatic" })

        assertEquals(403, user.putSettings("""{"mergePolicy":"github"}""").status.value)
        assertEquals(404, admin.putSettings("""{"mergePolicy":"github"}""", repo = "private-lab").status.value)
        for (bad in listOf(
            """{"mergePolicy":"never"}""", """{}""",
            """{"mergePolicy":"github","reviewers":["bad login"]}""",
            """{"mergePolicy":"github","reviewers":["-dash"]}""",
            """{"mergePolicy":"github","teamReviewers":["team/x"]}""",
            """{"mergePolicy":"github","reviewers":[${(1..16).joinToString(",") { "\"u$it\"" }}]}""",
        )) {
            val r = admin.putSettings(bad)
            assertEquals(400, r.status.value, bad + " " + r.bodyAsText())
        }
        val bad = admin.putSettings("""{"mergePolicy":"github","reviewers":["ok","<script>"]}""")
        assertTrue(bad.bodyAsText().contains("is not a valid GitHub login"), bad.bodyAsText())
        assertEquals("automatic", s.projectSettings.get("demo-app").mergePolicy, "a refused request changes nothing")

        val ok = admin.putSettings("""{"mergePolicy":"github","reviewers":[" @Carol ","carol","","dave"],"teamReviewers":["core.team"]}""")
        assertEquals(200, ok.status.value, ok.bodyAsText())
        val stored = json(ok.bodyAsText()).settings()
        assertEquals("github", stored.str("mergePolicy"))
        assertEquals(listOf("Carol", "dave"), stored.list("reviewers"))
        assertEquals(listOf("core.team"), stored.list("teamReviewers"))
        assertEquals(stored, json(user.get("/api/projects/demo-app").bodyAsText()).settings())

        // Omitted lists are empty lists.
        val owner = json(admin.putSettings("""{"mergePolicy":"owner_approves"}""").bodyAsText()).settings()
        assertEquals("owner_approves", owner.str("mergePolicy"))
        assertEquals(emptyList(), owner.list("reviewers"))
        assertEquals(emptyList(), owner.list("teamReviewers"))
    }

    // ------------------------------------------------------------ owner approves

    @Test
    fun `owner_approves - APPROVE does not merge, the owner approves, nobody else`() {
        policy(MergePolicy.OWNER_APPROVES)
        script()
        val run = start()
        assertEquals(MergePolicy.OWNER_APPROVES, run.mergePolicy)
        val waiting = finished(run)
        assertEquals(RunStatus.NEEDS_HUMAN, waiting.status)
        assertEquals(
            "The approver recommends merging: approver says APPROVE. This project requires the run owner's approval before every merge.",
            waiting.error,
        )
        assertEquals("APPROVE", s.runs.stages(run.id).single { it.role == "approver" }.verdict)
        assertTrue(merges().isEmpty(), gh.actions.toString())
        assertTrue(approverPrompt().contains("Merge policy: the run owner approves every merge. You cannot merge"))

        assertEquals(403, code { s.orchestrator.approve(bob, run.id) }.status.value)
        assertEquals(403, code { s.orchestrator.approve(root, run.id) }.status.value)
        val merged = runBlocking { s.orchestrator.approve(alice, run.id) }
        assertEquals(RunStatus.MERGED, merged.status)
        assertTrue(gh.actions.contains("close demo-app#1"))
        assertTrue(comments().any { it.startsWith("**Agern: merged**") && it.contains("(approved by @alice)") }, comments().toString())
    }

    @Test
    fun `owner_approves - ESCALATE waits with the escalation and the owner merges`() {
        policy(MergePolicy.OWNER_APPROVES)
        script(approverVerdict = "ESCALATE")
        val waiting = finished(start())
        assertEquals(RunStatus.NEEDS_HUMAN, waiting.status)
        assertEquals("The approver escalated: approver says ESCALATE.", waiting.error)
        assertEquals(RunStatus.MERGED, runBlocking { s.orchestrator.approve(alice, waiting.id) }.status)
    }

    // ------------------------------------------------------------ merge on GitHub

    @Test
    fun `github - a passing run ends ready, nothing merges by any path, and a new run may start`() {
        policy(MergePolicy.GITHUB)
        script()
        val run = start()
        val ready = finished(run)
        assertEquals(RunStatus.READY, ready.status, ready.error)
        assertNull(ready.error)
        assertNotNull(ready.endedAt)
        assertTrue(merges().isEmpty(), gh.actions.toString())
        assertEquals("open", gh.pullMeta.getValue(ready.prNumber!!).state)
        assertEquals("open", gh.client("fake-alice").getIssue("test-org", "demo-app", 1)!!.state)
        assertTrue(gh.actions.none { it == "close demo-app#1" })
        val c = comments().single { it.startsWith("**Agern: ready for review.**") }
        assertTrue(c.contains("Pull request ${ready.prUrl} passed all reviewers. Approver's assessment (APPROVE): approver says APPROVE."), c)
        assertTrue(c.contains("This project merges on GitHub: review and merge the pull request there."), c)
        assertTrue(worktreeRemoved(ready), "the worktree is removed")
        assertTrue(approverPrompt().contains("Merge policy: merged on GitHub by the team."))

        // Every way to merge answers merge_on_github.
        val e = code { s.orchestrator.approve(alice, run.id) }
        assertEquals("merge_on_github", e.code)
        assertTrue(e.message!!.contains("Review and merge pull request ${ready.prUrl} on GitHub"), e.message)
        val chat = s.sessions.create("demo-app", "chat", "opus", "idle", alice.id, null)
        val reply = try { runBlocking { s.factory.handleCommand(chat, alice, "/factory approve ${run.id}") } } catch (x: ApiException) { x.message }
        assertTrue(reply!!.startsWith("This project merges on GitHub"), reply)
        val token = s.mcpRegistry.issue(McpGrant(chat.id, alice.id, "demo-app", null, McpRegistry.CHAT_TOOLS))
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"factory_approve_run","arguments":{"runId":"${run.id}"}}}"""
        val result = json(runBlocking { s.mcp.handle(token, body) }.second!!)["result"]!!.jsonObject
        assertEquals("true", result["isError"]?.jsonPrimitive?.content)
        assertTrue(result["content"]!!.jsonArray[0].jsonObject.str("text").contains("This project merges on GitHub"), result.toString())
        assertEquals("Run is already ready", code { s.orchestrator.cancel(alice, run.id) }.message)
        assertTrue(merges().isEmpty())

        // Not active: a new run of the issue starts, and its pull request names the earlier one.
        val second = finished(start())
        assertEquals(RunStatus.READY, second.status)
        assertTrue(gh.pullBodies.getValue(second.prNumber!!).contains("Earlier runs of this issue left #${ready.prNumber} open"))
    }

    @Test
    fun `github - ESCALATE also ends ready, with the assessment posted`() {
        policy(MergePolicy.GITHUB)
        script(approverVerdict = "ESCALATE")
        val ready = finished(start())
        assertEquals(RunStatus.READY, ready.status)
        assertTrue(comments().any { it.contains("Approver's assessment (ESCALATE): approver says ESCALATE.") })
        assertTrue(merges().isEmpty())
    }

    @Test
    fun `github - reviewers are requested without the owner, and a failed request is tolerated`() {
        policy(MergePolicy.GITHUB, reviewers = listOf("Alice", "carol"), teams = listOf("core"))
        script()
        val ready = finished(start())
        assertEquals(RunStatus.READY, ready.status)
        assertTrue(gh.actions.contains("reviewers demo-app #${ready.prNumber} carol teams=core"), gh.actions.toString())
        assertTrue(comments().any { it.endsWith("Reviewers requested: @carol, team `core`.") }, comments().toString())

        gh.requestReviewersFailure = 422
        val other = finished(start(issue = 2))
        assertEquals(RunStatus.READY, other.status)
        assertTrue(comments(2).any { it.contains("Requesting reviewers failed: Reviews may only be requested from collaborators.") }, comments(2).toString())
    }

    @Test
    fun `github - at needs_human approve is refused, cancel works`() {
        policy(MergePolicy.GITHUB)
        script(reviewVerdict = "CHANGES")
        val waiting = finished(start())
        assertEquals(RunStatus.NEEDS_HUMAN, waiting.status)
        assertTrue(comments().any { it.startsWith("**Agern: needs a human.**") && it.endsWith("Agern does not merge it.") }, comments().toString())
        assertEquals("merge_on_github", code { s.orchestrator.approve(alice, waiting.id) }.code)
        assertEquals("merge_on_github", code { s.orchestrator.mergeAnyway(alice, waiting.id, "abc") }.code)
        // A step retry of a merge is refused too.
        s.runs.setFailure(waiting.id, null, FailedStep.MERGE)
        assertEquals("merge_on_github", code { s.orchestrator.retryStep(alice, s.runs.get(waiting.id)!!) }.code)
        assertEquals(RunStatus.CANCELLED, runBlocking { s.orchestrator.cancel(alice, waiting.id) }.status)
        assertTrue(merges().isEmpty())
    }

    @Test
    fun `merge refuses a github run and an agent-approved owner_approves run even when called directly`() {
        policy(MergePolicy.GITHUB)
        script(reviewVerdict = "CHANGES")
        val gRun = finished(start())
        policy(MergePolicy.OWNER_APPROVES)
        script()
        val oRun = finished(start(issue = 2))
        // One evaluation before a merge, as the approval step, approve and a merge retry run it (approved by whom is on the run).
        val mergeStep = s.orchestrator.javaClass.getDeclaredMethod(
            "mergeStep", java.util.UUID::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        )
        mergeStep.isAccessible = true
        s.runs.setMergeApprovedBy(gRun.id, "alice")
        mergeStep.invoke(s.orchestrator, gRun.id, false, false)
        s.runs.setMergeApprovedBy(oRun.id, null)
        mergeStep.invoke(s.orchestrator, oRun.id, false, false)
        assertTrue(merges().isEmpty(), gh.actions.toString())
        assertEquals("Not merged: this run's merge policy (github) does not allow it.", s.runs.get(gRun.id)!!.error)
        assertEquals("Not merged: this run's merge policy (owner_approves) does not allow it.", s.runs.get(oRun.id)!!.error)
        assertEquals(RunStatus.NEEDS_HUMAN, s.runs.get(oRun.id)!!.status)
    }

    // ------------------------------------------------------------ snapshot

    @Test
    fun `a policy changed mid-run does not change the run`() {
        // While the approver works: the run keeps `automatic` and merges.
        val gate = CountDownLatch(1)
        script(gate = gate)
        val run = start()
        world.awaitRun(run.id, setOf(RunStatus.APPROVING))
        policy(MergePolicy.GITHUB)
        gate.countDown()
        val merged = finished(run)
        assertEquals(RunStatus.MERGED, merged.status, merged.error)
        assertEquals(MergePolicy.AUTOMATIC, merged.mergePolicy)

        // While waiting for the owner: still owner_approves, so it can be approved even though the project now merges on GitHub.
        policy(MergePolicy.OWNER_APPROVES)
        script()
        val waiting = finished(start(issue = 2))
        assertEquals(RunStatus.NEEDS_HUMAN, waiting.status)
        policy(MergePolicy.GITHUB)
        assertEquals(MergePolicy.OWNER_APPROVES, s.runs.get(waiting.id)!!.mergePolicy)
        assertEquals(RunStatus.MERGED, runBlocking { s.orchestrator.approve(alice, waiting.id) }.status)

        // And the other way round: a github run stays github after the project goes back to automatic.
        script()
        val ready = finished(start(issue = 3))
        policy(MergePolicy.AUTOMATIC)
        assertEquals(RunStatus.READY, ready.status)
        assertEquals("merge_on_github", code { s.orchestrator.approve(alice, ready.id) }.code)
    }

    @Test
    fun `the approver's prompt states the automatic policy`() {
        script()
        assertEquals(RunStatus.MERGED, finished(start()).status)
        assertTrue(approverPrompt().contains("Merge policy: automatic. `APPROVE` squash-merges the pull request and closes the issue immediately"))
    }

    // ------------------------------------------------------------ follower

    @Test
    fun `the follower ends ready runs when their pull request is merged or closed on GitHub`() {
        policy(MergePolicy.GITHUB)
        script()
        val a = finished(start(issue = 1))
        val b = finished(start(issue = 2))
        val c = finished(start(issue = 3))
        listOf(a, b, c).forEach { assertEquals(RunStatus.READY, it.status) }
        val actionsBefore = gh.actions.size

        // A failing read changes nothing.
        gh.mergeOnGitHub(a.prNumber!!, by = "carol")
        gh.getPullFailures = 3
        runBlocking { s.orchestrator.followOnce() }
        assertEquals(RunStatus.READY, s.runs.get(a.id)!!.status)

        gh.closePull(b.prNumber!!)
        runBlocking { s.orchestrator.followOnce() }
        val merged = s.runs.get(a.id)!!
        assertEquals(RunStatus.MERGED, merged.status)
        assertTrue(merged.mergedOnGitHub)
        assertEquals("carol", merged.mergedBy)
        assertEquals(a.endedAt, merged.endedAt)
        val dto = s.runs.toDto(merged)
        assertTrue(dto.mergedOnGitHub)
        assertEquals("carol", dto.mergedBy)
        val closed = s.runs.get(b.id)!!
        assertEquals(RunStatus.CANCELLED, closed.status)
        assertEquals("Pull request #${b.prNumber} was closed on GitHub without being merged.", closed.error)
        assertEquals(RunStatus.READY, s.runs.get(c.id)!!.status, "still open")
        // Agern neither closed the issue nor commented.
        val after = gh.actions.drop(actionsBefore)
        assertTrue(after.none { it.startsWith("close ") || it.startsWith("comment ") || it.startsWith("merge") }, after.toString())
    }

    @Test
    fun `the follower ends a github run waiting in needs_human`() {
        policy(MergePolicy.GITHUB)
        script(reviewVerdict = "CHANGES")
        val waiting = finished(start())
        assertEquals(RunStatus.NEEDS_HUMAN, waiting.status)
        assertFalse(worktreeRemoved(waiting))
        gh.mergeOnGitHub(waiting.prNumber!!, by = null)
        runBlocking { s.orchestrator.followOnce() }
        val done = s.runs.get(waiting.id)!!
        assertEquals(RunStatus.MERGED, done.status)
        assertTrue(done.mergedOnGitHub)
        assertNull(done.mergedBy)
        assertNull(done.error)
        assertNotNull(done.endedAt)
        assertTrue(worktreeRemoved(waiting))
        assertTrue(gh.actions.contains("unlabel demo-app#1 needs-human"))
        assertTrue(s.runs.stages(waiting.id).none { it.status == "pending" || it.status == "running" })
    }

    @Test
    fun `a github submodule run merged on GitHub opens the pointer bump and leaves it for a human`() {
        val ws = "demo-shop"
        policy(MergePolicy.GITHUB, repo = ws)
        script()
        val ready = finished(start(repo = ws))
        assertEquals(RunStatus.READY, ready.status, ready.error)
        assertEquals("demo-shop-api", ready.targetRepo)
        // The assessment goes to the workspace issue and to the member's pull request.
        assertTrue(comments(repo = ws).any { it.startsWith("**Agern: ready for review.**") })
        assertTrue(gh.actions.any { it.startsWith("comment demo-shop-api#${ready.prNumber} **Agern: ready for review.**") }, gh.actions.toString())
        gh.mergeOnGitHub(ready.prNumber!!)
        runBlocking { s.orchestrator.followOnce() }
        assertEquals(RunStatus.MERGED, s.runs.get(ready.id)!!.status)
        val bump = gh.pullMeta.values.single { it.repo == ws }
        assertEquals("open", bump.state)
        assertFalse(bump.merged)
        assertTrue(comments(repo = ws).any { it.contains("is open for a human to merge") }, comments(repo = ws).toString())
    }
}
