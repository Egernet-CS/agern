package dk.egernet.factory

import dk.egernet.factory.db.UserRecord
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Talking to an agent while a run waits at needs_human (docs/ARCHITECTURE.md §6 "Talking to an agent at needs human"). */
class RunConversationTest {
    private val world = TestWorld(twoFactor = false)
    private val s = world.services
    private val alice = world.user("alice")

    /** Follow-up reviewers (prompt contains "follow-up review"): their verdict, or "fail" / "block". */
    @Volatile private var followUp = "PASS"
    /** While false, blocking follow-up reviewers wait. */
    @Volatile private var release = false
    /** The issue title → the approver's verdict (default ESCALATE, so runs stop at needs_human). */
    private val approver = ConcurrentHashMap<String, String>()

    init {
        world.launcher.stage = ::behave
    }

    /** Every pipeline stage: all reviewers pass (code with a minor finding), the approver escalates, follow-up reviewers as [followUp]. */
    private fun behave(p: FakeProcess) {
        val role = p.spec.kind
        val prompt = p.spec.prompt ?: ""
        p.init()
        when {
            role == "spec-writer" -> p.result("## Spec\nDo the thing carefully.")
            role == "builder" -> p.result("Built it; added a test.")
            role == "approver" -> {
                val v = approver.entries.firstOrNull { prompt.contains(it.key) }?.value ?: "ESCALATE"
                p.result(null, buildJsonObject { put("verdict", v); put("summary", "unsure about the migration") })
            }
            "follow-up review" in prompt -> when (followUp) {
                "fail" -> { p.exit(1); return }
                "block" -> {
                    while (!release && !p.killed) Thread.sleep(10)
                    if (p.killed) return
                    p.result(null, review("PASS"))
                }
                else -> p.result(null, review(followUp))
            }
            else -> p.result(null, review("PASS", finding = role == "code"))
        }
        p.exit(0)
    }

    @AfterTest fun tearDown() {
        release = true
        world.close()
        // Wait until no coroutine of the stopped services still uses the database, then give the pool's connections back.
        runBlocking { kotlinx.coroutines.withTimeoutOrNull(10_000) { s.scope.coroutineContext[kotlinx.coroutines.Job]!!.join() } }
        (s.db.dataSource as java.io.Closeable).close()
    }

    private fun review(verdict: String, finding: Boolean = verdict == "CHANGES") = buildJsonObject {
        put("verdict", verdict); put("summary", "$verdict from the reviewer")
        put("findings", buildJsonArray {
            if (finding) addJsonObject { put("severity", "minor"); put("file", "src/App.kt"); put("detail", "rename the helper") }
        })
    }

    private fun waitingRun(issue: Int = 1, owner: UserRecord = alice, repo: String = "demo-app"): UUID {
        val run = runBlocking { s.orchestrator.start(owner, repo, issue, null) }
        val r = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.NEEDS_HUMAN, r.status, r.error)
        assertEquals("head-1", r.headSha)
        assertEquals("head-1", r.reviewedSha)
        return run.id
    }

    private fun talk(runId: UUID, text: String, user: UserRecord = alice) = runBlocking { s.orchestrator.talk(user, runId, text) }
    private fun code(block: () -> Unit) = assertFailsWith<ApiException> { block() }.let { it.status.value to it.code }
    private fun dto(runId: UUID) = s.runs.toDto(s.runs.get(runId)!!)

    private fun <T> await(what: String, timeoutMs: Long = 10_000, f: () -> T?): T = runBlocking {
        withTimeout(timeoutMs) {
            while (true) {
                f()?.let { return@withTimeout it }
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE") error(what)
        }
    }

    private fun session(runId: UUID) = s.sessions.get(s.runs.get(runId)!!.assistantSessionId!!)!!
    private fun awaitIdle(runId: UUID) = await("assistant idle") { session(runId).takeIf { it.status == SessionStatus.IDLE && !s.agents.isAssistantBusy(it.id) } }
    private fun awaitState(runId: UUID, state: String) = await("reviewState $state") { dto(runId).takeIf { it.reviewState == state } }
    private fun events(sid: UUID) = s.events.list(sid, 0, 1000)
    private fun texts(sid: UUID, type: String) = events(sid).filter { it.type == type }.map { (it.payload as JsonObject)["text"]?.jsonPrimitive?.content }
    private fun inputText(i: Int = -1): String {
        val list = world.launcher.chatInputs
        return list[if (i < 0) list.size + i else i].jsonPrimitive.content
    }
    /** The agent "commits" while answering a message containing [trigger]. */
    private fun commitOn(trigger: String, newHead: String) {
        world.launcher.beforeEcho = { _, text -> if (trigger in text) world.git.head = newHead; true }
    }

    @Test
    fun `a question changes nothing and the agent gets the run's context once`() {
        val runId = waitingRun()
        val before = s.runs.get(runId)!!
        val stagesBefore = s.runs.stages(runId)
        val spendBefore = s.usage.usage(alice).spend.todayUsd

        val after = talk(runId, "Why did the approver escalate?")
        assertNotNull(after.assistantSessionId)
        awaitIdle(runId)
        val sess = session(runId)
        assertEquals(ASSISTANT_KIND, sess.kind)
        assertEquals(runId, sess.runId)
        assertEquals(1, sess.issueNumber)
        assertEquals("assistant · #1", sess.title)
        assertEquals("opus", sess.model, "the owner's builder model")

        val first = inputText()
        assertTrue(first.startsWith("<run_context>"), first)
        assertTrue(first.contains("Why it needs a human: The approver escalated: unsure about the migration"), first)
        assertTrue(first.contains("<spec>\n## Spec\nDo the thing carefully.\n</spec>"), first)
        assertTrue(first.contains("<builder_summary>\nBuilt it; added a test.\n</builder_summary>"), first)
        assertTrue(first.contains("<reviews round=\"build\">") && first.contains("### code — PASS") && first.contains("- [minor] src/App.kt: rename the helper"), first)
        assertTrue(first.contains("<approver>\nESCALATE\nunsure about the migration"), first)
        assertTrue(first.contains("git diff origin/main...HEAD"), first)
        assertTrue(first.endsWith("Message from the run owner (alice):\n\nWhy did the approver escalate?"), first)
        // The transcript shows only the owner's text.
        assertEquals(listOf("Why did the approver escalate?"), texts(sess.id, "user"))

        // The run is untouched.
        val now = s.runs.get(runId)!!
        assertEquals(RunStatus.NEEDS_HUMAN, now.status)
        assertEquals(before.error, now.error)
        assertEquals(before.prNumber, now.prNumber)
        assertEquals(stagesBefore.map { it.id to it.status }, s.runs.stages(runId).map { it.id to it.status })
        assertEquals("current", dto(runId).reviewState)
        assertEquals(1, world.git.pushes.size, "no push for a question")

        // The worktree, the stage tools only and the assistant prompt.
        val proc = world.launcher.byRole(ASSISTANT_KIND).single()
        assertEquals(s.workspaces.runDir("alice", "demo-app", runId), proc.spec.workDir)
        assertTrue(proc.spec.interactive)
        assertTrue(proc.spec.appendSystemPrompt!!.startsWith("# Role: assistant"))
        val token = Regex("Bearer ([^\"]+)").find(proc.spec.mcpConfigJson)!!.groupValues[1]
        val tools = runBlocking { s.mcp.handle(token, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").second!! }
        assertTrue(tools.contains("factory_get_issue"), tools)
        for (t in listOf("factory_approve_run", "factory_cancel_run", "factory_start_run", "factory_send_to_agent")) assertFalse(tools.contains(t), t)

        // A follow-up message goes to the same process without the context.
        talk(runId, "And the tests?")
        awaitIdle(runId)
        assertEquals("Message from the run owner (alice):\n\nAnd the tests?", inputText())
        assertEquals(1, world.launcher.byRole(ASSISTANT_KIND).size)
        assertTrue(s.usage.usage(alice).spend.todayUsd > spendBefore, "the conversation's cost is the owner's spend")
    }

    @Test
    fun `a commit is pushed and reviewed again before approval, and approve closes the conversation`() {
        val runId = waitingRun()
        followUp = "block"
        commitOn("please commit", "head-2")
        talk(runId, "Rename the helper, please commit")
        awaitState(runId, "reviewing")
        val branch = s.runs.get(runId)!!.branch!!
        assertEquals(listOf(branch, branch), world.git.pushes.toList())
        val round = s.runs.stages(runId).filter { it.round == 1 }
        assertEquals(REVIEWER_ROLES.toSet(), round.map { it.role }.toSet())
        assertEquals(RunStatus.NEEDS_HUMAN, s.runs.get(runId)!!.status)
        assertEquals(0, s.runs.get(runId)!!.round, "Run.round is not changed")

        // While the reviewers run: no message, no approval, no second review.
        assertEquals(409 to "reviews_running", code { talk(runId, "more?") })
        assertEquals(409 to "reviews_running", code { runBlocking { s.orchestrator.approve(alice, runId) } })
        assertEquals(409 to "reviews_running", code { runBlocking { s.orchestrator.review(alice, runId) } })

        release = true
        awaitState(runId, "current")
        val r = s.runs.get(runId)!!
        assertEquals("head-2", r.headSha)
        assertEquals("head-2", r.reviewedSha)
        assertEquals(RunStatus.NEEDS_HUMAN, r.status)
        assertTrue(world.launcher.byRole("security").last().spec.prompt!!.contains(
            "(follow-up review 1: the run owner asked for a change while the run waits at needs human)"))
        assertTrue(world.launcher.byRole("security").last().spec.prompt!!.contains("echo: "), "builder_summary = the assistant's last answer")

        // The next message carries the review results.
        talk(runId, "What did they say?")
        awaitIdle(runId)
        assertTrue(inputText().startsWith("<review_results round=\"follow-up review 1\">\n### security — PASS"), inputText())

        val sid = session(runId).id
        val merged = runBlocking { s.orchestrator.approve(alice, runId) }
        assertEquals(RunStatus.MERGED, merged.status)
        assertEquals(SessionStatus.STOPPED, s.sessions.get(sid)!!.status)
        assertTrue(texts(sid, "system").contains("The run was approved by alice; this conversation is closed."))
        assertEquals(409 to "run_not_waiting", code { talk(runId, "hello?") })
    }

    @Test
    fun `approve is refused while the agent works, while reviews run and until the head is reviewed`() {
        val runId = waitingRun()
        // Mid-turn: the agent does not answer yet.
        var hold: FakeProcess? = null
        world.launcher.beforeEcho = { p, _ -> hold = p; false }
        talk(runId, "think about it")
        await("delivered") { hold }
        assertEquals(409 to "agent_busy", code { runBlocking { s.orchestrator.approve(alice, runId) } })
        assertEquals(409 to "agent_busy", code { talk(runId, "again") })
        assertEquals(409 to "agent_busy", code { runBlocking { s.orchestrator.review(alice, runId) } })
        world.launcher.beforeEcho = null
        hold!!.init(); hold!!.result("thought about it")
        awaitIdle(runId)

        // A commit Agern has not seen yet (e.g. after a restart): approve pushes it and starts a review instead.
        followUp = "block"
        world.git.head = "head-3"
        assertEquals(409 to "changes_not_reviewed", code { runBlocking { s.orchestrator.approve(alice, runId) } })
        assertEquals("reviewing", dto(runId).reviewState)
        release = true
        awaitState(runId, "current")
        assertEquals(RunStatus.MERGED, runBlocking { s.orchestrator.approve(alice, runId) }.status)
    }

    @Test
    fun `follow-up CHANGES still count as reviewed`() {
        val runId = waitingRun()
        followUp = "CHANGES"
        commitOn("commit", "head-2")
        talk(runId, "commit a fix")
        await("follow-up round done") { s.runs.stages(runId).filter { it.round == 1 }.takeIf { l -> l.size == 4 && l.all { it.status == "done" } } }
        awaitState(runId, "current")
        assertEquals(RunStatus.MERGED, runBlocking { s.orchestrator.approve(alice, runId) }.status)
    }

    @Test
    fun `a failed follow-up reviewer leaves the change unreviewed until Review changes`() {
        val runId = waitingRun()
        followUp = "fail"
        commitOn("commit", "head-2")
        talk(runId, "commit it")
        awaitState(runId, "unreviewed")
        val stages = s.runs.stages(runId).filter { it.round == 1 }
        assertTrue(stages.none { it.status in setOf("pending", "running") }, stages.toString())
        assertTrue(stages.any { it.status == "failed" }, stages.toString())
        assertEquals("head-1", s.runs.get(runId)!!.reviewedSha)
        assertEquals(RunStatus.NEEDS_HUMAN, s.runs.get(runId)!!.status)
        assertEquals(409 to "changes_not_reviewed", code { runBlocking { s.orchestrator.approve(alice, runId) } }.also {
            // approve started a new round itself; let it fail again
            awaitState(runId, "unreviewed")
        })

        followUp = "PASS"
        val r = runBlocking { s.orchestrator.review(alice, runId) }
        assertEquals("reviewing", s.runs.toDto(r).reviewState)
        awaitState(runId, "current")
        assertEquals(3, s.runs.stages(runId).maxOf { it.round }, "rounds 1 and 2 failed, round 3 passed")
        assertEquals(409 to "nothing_to_review", code { runBlocking { s.orchestrator.review(alice, runId) } })
    }

    @Test
    fun `a failed push is reported in the conversation and retried with Review changes`() {
        val runId = waitingRun()
        world.git.pushFailure = "remote rejected"
        commitOn("commit", "head-2")
        talk(runId, "commit it")
        val sid = await("push error") { s.runs.get(runId)!!.assistantSessionId?.takeIf { id -> texts(id, "system").any { it?.contains("Could not push") == true } } }
        val branch = s.runs.get(runId)!!.branch
        assertTrue(texts(sid, "system").contains("Could not push the change to `$branch`: remote rejected. Use “Review changes” to try again."), texts(sid, "system").toString())
        assertEquals("unreviewed", dto(runId).reviewState)
        assertTrue(s.runs.stages(runId).none { it.round == 1 })
        assertEquals(502 to "github_error", code { runBlocking { s.orchestrator.review(alice, runId) } })

        world.git.pushFailure = null
        runBlocking { s.orchestrator.review(alice, runId) }
        awaitState(runId, "current")
    }

    @Test
    fun `uncommitted changes are pointed out after a turn`() {
        val runId = waitingRun()
        world.launcher.beforeEcho = { _, _ -> world.git.dirty = true; true }
        talk(runId, "edit something")
        val sid = await("warning") { s.runs.get(runId)!!.assistantSessionId?.takeIf { id -> texts(id, "system").any { it?.contains("uncommitted") == true } } }
        assertTrue(texts(sid, "system").contains("The worktree has uncommitted changes. They are not part of the pull request; ask the agent to commit them."))
        assertEquals("current", dto(runId).reviewState)
    }

    @Test
    fun `refusals - wrong status, blank and long text, other users, stage agent messages`() {
        val runId = waitingRun()
        val bob = world.user("bob")
        val root = world.user("root", admin = true)
        assertEquals(403 to "forbidden", code { talk(runId, "hi", bob) })
        assertEquals(403 to "forbidden", code { talk(runId, "hi", root) })
        assertEquals(403 to "forbidden", code { runBlocking { s.orchestrator.review(root, runId) } })
        assertEquals(400, code { talk(runId, "   ") }.first)
        assertEquals(400, code { talk(runId, "x".repeat(100_001)) }.first)
        assertNull(s.runs.get(runId)!!.assistantSessionId, "nothing was created")

        talk(runId, "hello")
        awaitIdle(runId)
        val sess = session(runId)
        // Not through the stage-agent message path, and admins cannot interrupt.
        assertEquals(409 to "agent_does_not_accept_messages", code { s.agents.sendToStageAgent(alice, sess, "x") })
        assertEquals(409 to "agent_does_not_accept_messages", code {
            runBlocking { s.factory.sendToAgent(alice, null, null, sess.id, "x", null) }
        })
        assertEquals(403, code { s.agents.interrupt(root, sess) }.first)

        // Other statuses.
        val running = runBlocking { s.orchestrator.start(alice, "demo-app", 2, null) }
        world.awaitRun(running.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        runBlocking { s.orchestrator.cancel(alice, running.id) }
        assertEquals(409 to "run_not_waiting", code { talk(running.id, "hi") })
        assertEquals(409 to "run_not_waiting", code { runBlocking { s.orchestrator.review(alice, running.id) } })
    }

    @Test
    fun `cancel during a follow-up round stops the reviewers and the assistant and removes the worktree`() {
        val runId = waitingRun()
        world.git.deleteWorktrees = true
        followUp = "block"
        commitOn("commit", "head-2")
        talk(runId, "commit it")
        awaitState(runId, "reviewing")
        await("reviewers running") { REVIEWER_ROLES.takeIf { roles -> roles.all { world.launcher.byRole(it).size == 2 } } }
        val sid = session(runId).id
        val bob = world.user("bob")
        assertEquals(403, code { runBlocking { s.orchestrator.cancel(bob, runId) } }.first)
        val cancelled = runBlocking { s.orchestrator.cancel(alice, runId) }
        assertEquals(RunStatus.CANCELLED, cancelled.status)
        assertTrue(REVIEWER_ROLES.all { role -> world.launcher.byRole(role).last().killed },
            REVIEWER_ROLES.map { role -> world.launcher.byRole(role).map { it.killed } }.toString())
        assertTrue(s.runs.stages(runId).none { it.status in setOf("pending", "running") })
        assertEquals(SessionStatus.STOPPED, s.sessions.get(sid)!!.status)
        assertTrue(texts(sid, "system").contains("The run was cancelled by alice; this conversation is closed."))
        assertTrue(world.git.removedWorktrees.contains(s.workspaces.runDir("alice", "demo-app", runId)))
        assertNull(dto(runId).reviewState)
    }

    @Test
    fun `an admin may stop the assistant and cancel the run, never drive`() {
        val runId = waitingRun()
        talk(runId, "hello")
        awaitIdle(runId)
        val root = world.user("root", admin = true)
        val stopped = runBlocking { s.agents.stop(root, session(runId)) }
        assertEquals(SessionStatus.STOPPED, stopped.status)
        // The owner's next message resumes the conversation.
        talk(runId, "still there?")
        awaitIdle(runId)
        val procs = world.launcher.byRole(ASSISTANT_KIND)
        assertEquals(2, procs.size)
        assertNotNull(procs.last().spec.resumeClaudeSessionId)
        assertEquals("Message from the run owner (alice):\n\nstill there?", inputText())
        assertEquals(RunStatus.CANCELLED, runBlocking { s.orchestrator.cancel(root, runId) }.status)
    }

    @Test
    fun `a failed resume starts a fresh conversation with the full context`() {
        val runId = waitingRun()
        talk(runId, "first")
        awaitIdle(runId)
        runBlocking { s.agents.stop(alice, session(runId)) }
        // The resumed process dies before its init event (the CLI no longer has the conversation).
        world.launcher.beforeEcho = { p, _ -> if (p.spec.resumeClaudeSessionId != null) { p.exit(1); false } else true }
        talk(runId, "second")
        await("fresh answer") { world.launcher.byRole(ASSISTANT_KIND).takeIf { it.size == 3 } }
        awaitIdle(runId)
        val contexts = world.launcher.chatInputs.map { it.jsonPrimitive.content }.filter { it.startsWith("<run_context>") }
        assertEquals(2, contexts.size, "the first message and the re-delivered one")
        assertTrue(contexts.last().endsWith("Message from the run owner (alice):\n\nsecond"))
        assertNull(world.launcher.byRole(ASSISTANT_KIND).last().spec.resumeClaudeSessionId)
    }

    @Test
    fun `after a restart the run waits, the session is idle and an interrupted round is unreviewed`() {
        val runId = waitingRun()
        talk(runId, "hello")
        awaitIdle(runId)
        followUp = "block"
        commitOn("commit", "head-2")
        talk(runId, "commit it")
        awaitState(runId, "reviewing")
        await("reviewers running") { s.runs.stages(runId).filter { it.round == 1 }.takeIf { l -> l.all { it.status == "running" && it.sessionId != null } } }
        // No process survives a restart.
        s.agents.shutdown()
        runBlocking { s.orchestrator.job(runId)?.join() }
        val sid = session(runId).id
        await("assistant process gone") { sid.takeIf { !s.agents.isLive(it) && s.sessions.get(it)!!.status == SessionStatus.IDLE } }
        s.sessions.setStatus(sid, SessionStatus.RUNNING)
        // As if the server had died mid-round: the round's stages are still open in the database.
        s.runs.stages(runId).filter { it.round == 1 }.forEach { s.runs.startStage(it.id, it.sessionId!!, it.model) }
        s.recoverAfterRestart()

        val r = s.runs.get(runId)!!
        assertEquals(RunStatus.NEEDS_HUMAN, r.status)
        assertEquals(SessionStatus.IDLE, s.sessions.get(sid)!!.status)
        assertTrue(s.runs.stages(runId).filter { it.round == 1 }.none { it.status in setOf("pending", "running") })
        assertEquals("unreviewed", dto(runId).reviewState)

        // The next message starts the missing review first; afterwards the conversation resumes.
        followUp = "PASS"
        assertEquals(409 to "reviews_running", code { talk(runId, "back?") })
        awaitState(runId, "current")
        talk(runId, "back?")
        awaitIdle(runId)
        assertNotNull(world.launcher.byRole(ASSISTANT_KIND).last().spec.resumeClaudeSessionId)
    }

    @Test
    fun `a held release issue stays held during the conversation and merges on approval`() {
        val n = world.github.addIssue("demo-app", "Release item", "body").number
        val rel = runBlocking { s.releases.create(alice, "demo-app", "Summer", "") }
        runBlocking { s.releases.assign(alice, "demo-app", listOf(n), rel.id) }
        runBlocking { s.releases.start(alice, rel.id) }
        val runId = await("held") {
            s.releaseStore.get(rel.id)!!.issues.single { it.number == n }.takeIf { it.state == "held" }?.runId
        }
        assertEquals(RunStatus.NEEDS_HUMAN, s.runs.get(runId)!!.status)
        followUp = "block"
        commitOn("commit", "head-2")
        talk(runId, "commit it")
        awaitState(runId, "reviewing")
        assertEquals("held", s.releaseStore.get(rel.id)!!.issues.single { it.number == n }.state)
        release = true
        awaitState(runId, "current")
        Thread.sleep(200)
        assertEquals("held", s.releaseStore.get(rel.id)!!.issues.single { it.number == n }.state)
        runBlocking { s.orchestrator.approve(alice, runId) }
        await("merged") { s.releaseStore.get(rel.id)!!.issues.single { it.number == n }.takeIf { it.state == "merged" } }
    }

    // ------------------------------------------------------------ HTTP

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String): HttpClient {
        val c = createClient { install(HttpCookies) }
        assertEquals(200, c.postJson("/api/auth/login", """{"username":"$user","password":"$pw"}""").status.value)
        assertEquals(200, c.put("/api/me/github-token") { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"token":"fake-$user"}""") }.status.value)
        return c
    }

    private suspend fun HttpClient.postJson(path: String, body: String = "{}"): HttpResponse =
        post(path) { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun errorCode(r: HttpResponse) =
        AppJson.parseToJsonElement(r.bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    @Test
    fun `over HTTP only the owner talks, reviews and interrupts, an admin may stop, others cannot see private runs`() = testApplication {
        application { agernModule(s) }
        val alice = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        val root = login("root", "pw-r")
        world.user("bob")
        world.user("root", admin = true)
        val runId = waitingRun()

        assertEquals(401, createClient { }.post("/api/runs/$runId/messages") { header("X-Factory-Request", "1") }.status.value)
        val r = alice.postJson("/api/runs/$runId/messages", """{"text":"why?"}""")
        assertEquals(202, r.status.value, r.bodyAsText())
        val body = AppJson.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("needs_human", body["status"]!!.jsonPrimitive.content)
        val sid = body["assistantSessionId"]!!.jsonPrimitive.content
        assertEquals("current", body["reviewState"]!!.jsonPrimitive.content)
        awaitIdle(runId)

        for (c in listOf(bob, root)) {
            assertEquals(403, c.postJson("/api/runs/$runId/messages", """{"text":"hijack"}""").status.value)
            assertEquals(403, c.postJson("/api/runs/$runId/review").status.value)
            assertEquals(403, c.postJson("/api/sessions/$sid/input", """{"text":"hijack"}""").status.value)
            assertEquals(403, c.postJson("/api/sessions/$sid/interrupt").status.value)
        }
        assertTrue(events(UUID.fromString(sid)).none { it.payload.toString().contains("hijack") })

        // The session's input endpoint is the same conversation; text only; it cannot be deleted.
        val viaInput = alice.postJson("/api/sessions/$sid/input", """{"text":"via input"}""")
        assertEquals(202, viaInput.status.value, viaInput.bodyAsText())
        awaitIdle(runId)
        assertEquals("Message from the run owner (alice):\n\nvia input", inputText())
        val att = alice.postJson("/api/sessions/$sid/input", """{"text":"x","attachmentIds":["${UUID.randomUUID()}"]}""")
        assertEquals(422, att.status.value)
        assertEquals("attachments_not_allowed", errorCode(att))
        val del = alice.delete("/api/sessions/$sid") { header("X-Factory-Request", "1") }
        assertEquals(409, del.status.value)
        assertEquals("not_a_chat", errorCode(del))
        val nothing = alice.postJson("/api/runs/$runId/review")
        assertEquals(409, nothing.status.value)
        assertEquals("nothing_to_review", errorCode(nothing))

        // An admin may stop the process.
        assertEquals(200, root.postJson("/api/sessions/$sid/stop").status.value)

        // A run in a repository bob cannot see: 404 for him.
        val priv = waitingRun(issue = 1, repo = "private-lab")
        assertEquals(404, bob.postJson("/api/runs/$priv/messages", """{"text":"x"}""").status.value)
        assertEquals(404, bob.postJson("/api/runs/$priv/review").status.value)
        val wrong = alice.postJson("/api/runs/${UUID.randomUUID()}/messages", """{"text":"x"}""")
        assertEquals(404, wrong.status.value)
    }
}
