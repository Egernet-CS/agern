package dk.egernet.factory

import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.mcp.McpRegistry
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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

/** Talking to an agent while a run needs a human (docs/ARCHITECTURE.md §6 "Conversation at needs human"). */
class RunConversationTest {
    private val world = TestWorld(twoFactor = false)
    private val s = world.services
    private val alice = world.user("alice")
    private val bob = world.user("bob")
    private val root = world.user("root", admin = true)
    private val extraServices = CopyOnWriteArrayList<Services>()

    /** How the reviewers of a follow-up round answer: PASS, CHANGES, fail or hang. */
    @Volatile private var followUpReview = "PASS"
    /** Every reviewer prompt of a follow-up round. */
    private val followUpPrompts = CopyOnWriteArrayList<String>()
    private val released = ConcurrentHashMap<FakeProcess, Boolean>()

    @AfterTest fun tearDown() {
        followUpReview = "PASS"
        extraServices.forEach { it.stop() }
        world.close()
        runBlocking {
            for (svc in extraServices + s) withTimeoutOrNull(10_000) { svc.scope.coroutineContext[kotlinx.coroutines.Job]!!.join() }
        }
        (s.db.dataSource as java.io.Closeable).close()
    }

    private fun review(verdict: String) = buildJsonObject {
        put("verdict", verdict); put("summary", "$verdict from the reviewer")
        put("findings", buildJsonArray {
            if (verdict == "CHANGES") add(buildJsonObject { put("severity", "major"); put("file", "App.kt"); put("detail", "Rename the thing") })
        })
    }

    init {
        world.launcher.stage = { p ->
            val prompt = p.spec.prompt.orEmpty()
            when (p.spec.kind) {
                "spec-writer" -> { p.init(); p.result("## Spec\nDo the thing."); p.followUpsUntilClosed() }
                "builder" -> { p.init(); p.result("Built it with care."); p.followUpsUntilClosed() }
                "approver" -> {
                    p.init(); p.result(null, buildJsonObject { put("verdict", "ESCALATE"); put("summary", "Unsure about the migration") }); p.exit(0)
                }
                else -> if ("after follow-up round" in prompt) {
                    followUpPrompts += prompt
                    when (followUpReview) {
                        "fail" -> { p.init(); p.exit(1) }
                        "hang" -> { p.init(); while (!p.killed) Thread.sleep(10) }
                        else -> { p.init(); p.result(null, review(followUpReview)); p.exit(0) }
                    }
                } else {
                    p.init(); p.result(null, review("PASS")); p.exit(0)
                }
            }
        }
    }

    // ------------------------------------------------------------ helpers

    private fun needsHuman(owner: UserRecord = alice, repo: String = "demo-app", issue: Int = 1): UUID {
        val run = runBlocking { s.orchestrator.start(owner, repo, issue, null) }
        val r = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.NEEDS_HUMAN, r.status, r.error)
        return run.id
    }

    private fun talk(user: UserRecord, runId: UUID, text: String) = runBlocking { s.factory.talkToRun(user, runId, text) }
    private fun code(block: () -> Unit) = assertFailsWith<ApiException> { block() }.let { it.status.value to it.code }
    private fun events(sessionId: UUID) = s.events.list(sessionId, 0, 5000)
    private fun payload(e: EventDto) = e.payload as JsonObject

    private fun eventually(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(10)
        }
    }

    /** Waits until the advisor's turn (and the run's reconcile) has ended. */
    private fun awaitIdle(sessionId: UUID) = eventually("advisor idle") {
        !s.agents.isAdvisorBusy(sessionId) && s.sessions.get(sessionId)!!.status == SessionStatus.IDLE
    }

    private fun awaitReview(runId: UUID, state: String) = eventually("review $state") { s.runs.get(runId)!!.reviewState == state }

    private fun advisorProcs() = world.launcher.byRole("advisor")

    /** The advisor commits when asked for a change (the worktree HEAD moves). */
    private fun committingAdvisor(sha: String = "1111111222222233333334444444555555566666") {
        world.launcher.advisor = { p, text ->
            p.init(p.spec.resumeClaudeSessionId ?: "advisor-claude-${p.spec.sessionId}")
            if ("please change" in text) world.git.headSha = sha
            p.assistantText("done: $text")
            p.result("Changed it as asked.")
        }
    }

    // ------------------------------------------------------------ questions

    @Test
    fun `the owner asks a question, the agent gets the run's context in the worktree and nothing changes`() {
        val runId = needsHuman()
        val before = s.runs.get(runId)!!
        val stagesBefore = s.runs.stages(runId).size
        val res = talk(alice, runId, "Why did the approver escalate?")
        assertEquals("accepted", res.status)
        val sid = UUID.fromString(res.sessionId)
        awaitIdle(sid)

        val run = s.runs.get(runId)!!
        assertEquals(sid, run.advisorSessionId)
        assertEquals(RunStatus.NEEDS_HUMAN, run.status)
        assertEquals(ReviewState.NONE, run.reviewState)
        assertEquals(world.git.headSha, run.reviewedSha)
        assertEquals(stagesBefore, s.runs.stages(runId).size, "a question creates no stages")
        assertEquals(before.error, run.error)
        val dto = s.runs.toDto(run)
        assertEquals(sid.toString(), dto.conversation.sessionId)
        assertEquals("none", dto.conversation.review)

        val session = s.sessions.get(sid)!!
        assertEquals(ADVISOR_KIND, session.kind)
        assertEquals(runId, session.runId)
        assertEquals(1, session.issueNumber)
        assertEquals("advisor · #1", session.title)
        assertEquals(alice.id, session.ownerId)
        assertEquals(s.preferences.effective(alice.id).model("builder"), session.model)

        val p = advisorProcs().single()
        assertTrue(p.spec.interactive)
        assertFalse(p.spec.steerable)
        assertEquals(s.workspaces.runDir("alice", "demo-app", runId), p.spec.workDir)
        assertTrue(p.spec.appendSystemPrompt!!.startsWith("# Role: advisor"))
        assertNull(p.spec.prompt, "the context goes over stdin, never in argv")
        // The grant: stage tools only, bound to the run.
        val token = Regex("Bearer ([^\"]+)").find(p.spec.mcpConfigJson)!!.groupValues[1]
        val grant = s.mcpRegistry.lookup(token)!!
        assertEquals(runId, grant.runId)
        assertEquals(McpRegistry.STAGE_TOOLS, grant.tools)
        assertFalse(grant.tools.any { it.contains("approve") || it.contains("cancel") || it.contains("start") || it.contains("sandbox") })

        // The first message carries the context; the transcript shows only the owner's words.
        val first = world.launcher.chatInputs.last().jsonPrimitive.content
        assertTrue(first.startsWith("<run_context>"), first)
        for (part in listOf(
            "Needs a human because: The approver escalated: Unsure about the migration", "<spec>\n## Spec\nDo the thing.\n</spec>",
            "Built it with care.", "### security — build — PASS", "### code — build — PASS", "ESCALATE: Unsure about the migration",
            "git diff origin/main...HEAD", "git log origin/main..HEAD", "no sandbox", run.prUrl!!, run.branch!!, "<issue_body>",
        )) assertTrue(part in first, "context lacks: $part\n$first")
        assertTrue(first.endsWith("</run_context>\n\nMessage from the run owner (alice):\n\nWhy did the approver escalate?"), first)
        val user = events(sid).single { it.type == "user" }
        assertEquals("Why did the approver escalate?", payload(user)["text"]!!.jsonPrimitive.content)
        assertEquals("alice", payload(user)["username"]!!.jsonPrimitive.content)
        assertTrue(events(sid).any { it.type == "system" && payload(it)["text"]!!.jsonPrimitive.content.contains("given this run's context") })
        assertTrue(events(sid).any { it.type == "claude" && it.payload.toString().contains("echo: <run_context>") })

        // Nothing on GitHub.
        assertFalse(world.github.actions.any { it.contains("Why did the approver") })
        // Spend is recorded for the owner, as for every agent.
        assertTrue(s.usage.usage(alice).spend.todayUsd > 0.0)
    }

    @Test
    fun `a second message reuses the session and process, after a stop it resumes with --resume and the context again`() {
        val runId = needsHuman()
        val sid = UUID.fromString(talk(alice, runId, "first").sessionId)
        awaitIdle(sid)
        assertEquals(sid.toString(), talk(alice, runId, "second").sessionId)
        awaitIdle(sid)
        assertEquals(1, advisorProcs().size)
        assertEquals("Message from the run owner (alice):\n\nsecond", world.launcher.chatInputs.last().jsonPrimitive.content)

        runBlocking { s.agents.stop(alice, s.sessions.get(sid)!!) }
        assertEquals(SessionStatus.STOPPED, s.sessions.get(sid)!!.status)
        talk(alice, runId, "third")
        awaitIdle(sid)
        val resumed = advisorProcs().last()
        assertEquals(2, advisorProcs().size)
        assertEquals("advisor-claude-$sid", resumed.spec.resumeClaudeSessionId)
        val third = world.launcher.chatInputs.last().jsonPrimitive.content
        assertTrue(third.startsWith("<run_context>") && third.endsWith("Message from the run owner (alice):\n\nthird"), third)
        assertEquals(2, events(sid).count { it.type == "system" && payload(it)["text"]!!.jsonPrimitive.content.contains("given this run's context") })
        assertEquals(3, events(sid).count { it.type == "user" })
    }

    // ------------------------------------------------------------ who may talk, and refusals

    @Test
    fun `only the owner may talk or review, admins and others get 403, invisible runs 404, and nothing starts`() {
        val runId = needsHuman()
        assertEquals(403 to "forbidden", code { talk(bob, runId, "let me in") })
        assertEquals(403 to "forbidden", code { talk(root, runId, "admins stop, never drive") })
        assertEquals(403 to "forbidden", code { runBlocking { s.factory.reviewRun(bob, runId) } })
        assertEquals(403 to "forbidden", code { runBlocking { s.factory.reviewRun(root, runId) } })
        val lab = needsHuman(repo = "private-lab", issue = world.github.addIssue("private-lab", "Lab", "x").number)
        assertEquals(404 to "not_found", code { talk(bob, lab, "peek") })
        assertEquals(404 to "not_found", code { runBlocking { s.factory.reviewRun(bob, lab) } })
        assertTrue(advisorProcs().isEmpty())
        assertNull(s.runs.get(runId)!!.advisorSessionId)
        assertTrue(s.sessions.byRun(runId).none { it.kind == ADVISOR_KIND })
    }

    @Test
    fun `messages are refused with the documented codes`() {
        val runId = needsHuman()
        assertEquals(400 to "bad_request", code { talk(alice, runId, "   ") })
        assertEquals(400 to "bad_request", code { talk(alice, runId, "x".repeat(100_001)) })

        // Mid-turn: one message in flight; concurrent sends give one 202 and one 409.
        val gate = java.util.concurrent.CountDownLatch(1)
        world.launcher.advisor = { p, text -> p.init(); gate.await(); p.result("answer to $text") }
        val results = (1..2).map { i -> Thread { runCatching { talk(alice, runId, "concurrent $i") }.also { r -> outcomes += r } } }
        results.forEach { it.start() }
        results.forEach { it.join() }
        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals("agent_busy", (outcomes.single { it.isFailure }.exceptionOrNull() as ApiException).code)
        val sid = s.runs.get(runId)!!.advisorSessionId!!
        assertEquals(1, s.sessions.byRun(runId).count { it.kind == ADVISOR_KIND })
        assertEquals(409 to "agent_busy", code { talk(alice, runId, "again") })
        assertEquals(409 to "agent_busy", code { runBlocking { s.orchestrator.approve(alice, runId) } })
        gate.countDown()
        awaitIdle(sid)

        // A follow-up review in progress.
        world.launcher.advisor = { p, _ -> p.init(); p.result("ok") }
        s.runs.setReviewState(runId, ReviewState.RUNNING)
        assertEquals(409 to "review_in_progress", code { talk(alice, runId, "x") })
        s.runs.setReviewState(runId, ReviewState.NONE)

        // No credential.
        s.users.setClaude(alice.id, null, null)
        assertEquals(422 to "claude_credential_required", code { talk(s.users.byId(alice.id)!!, runId, "x") })
        s.users.setClaude(alice.id, "oauth_token", s.box.encrypt("sk-ant-oat-alice-secret", "alice"))
        val a = s.users.byId(alice.id)!!

        // No free slot (the advisor process is stopped, all slots taken by other agents).
        runBlocking { s.agents.stop(a, s.sessions.get(sid)!!) }
        val chats = (1..world.cfg.maxAgentsPerUser).map { s.agents.createChat(a, "demo-app", null) }
        chats.forEach { s.agents.sendInput(a, it, "hold the slot") }
        eventually("chats live") { chats.all { s.agents.isLive(it.id) } }
        assertEquals(409 to "too_many_agents", code { talk(a, runId, "x") })
        chats.forEach { runBlocking { s.agents.stop(a, it) } }

        // The worktree is gone.
        val wt = s.workspaces.runDir("alice", "demo-app", runId)
        wt.toFile().deleteRecursively()
        assertEquals(409 to "run_worktree_missing", code { talk(a, runId, "x") })
        java.nio.file.Files.createDirectories(wt)

        // Not waiting any more.
        runBlocking { s.orchestrator.cancel(a, runId) }
        assertEquals(409 to "run_not_waiting", code { talk(a, runId, "x") })
        assertEquals(409 to "run_not_waiting", code { runBlocking { s.orchestrator.review(a, runId) } })
    }

    private val outcomes = CopyOnWriteArrayList<Result<TalkAcceptedDto>>()

    // ------------------------------------------------------------ changes and the follow-up review

    @Test
    fun `a committed change is pushed and reviewed by four reviewers before approval is possible`() {
        committingAdvisor()
        val runId = needsHuman()
        val base = s.runs.get(runId)!!
        val pushesBefore = world.git.pushes.size
        val maxRound = s.runs.stages(runId).maxOf { it.round }
        followUpReview = "hang"
        val sid = UUID.fromString(talk(alice, runId, "please change the migration").sessionId)

        awaitReview(runId, ReviewState.RUNNING)
        eventually("four follow-up reviewers") { followUpPrompts.size == 4 }
        val running = s.runs.get(runId)!!
        assertEquals(RunStatus.NEEDS_HUMAN, running.status)
        assertEquals(maxRound + 1, running.round)
        assertEquals(maxRound + 1, running.followUpFrom)
        assertEquals(REVIEWER_ROLES.toSet(), s.runs.stages(runId).filter { it.round == maxRound + 1 }.map { it.role }.toSet())
        assertEquals(base.branch, world.git.pushes.drop(pushesBefore).single())
        assertTrue(followUpPrompts.all { "after follow-up round 1: changes the run owner requested in a conversation" in it })
        assertTrue(followUpPrompts.all { "<advisor_summary>\nChanged it as asked.\n</advisor_summary>" in it })
        assertEquals(409 to "review_in_progress", code { runBlocking { s.orchestrator.approve(alice, runId) } })
        assertEquals(409 to "review_in_progress", code { talk(alice, runId, "more") })
        assertEquals(409 to "review_in_progress", code { runBlocking { s.orchestrator.review(alice, runId) } })
        // The advisor process was stopped for the review (resumable).
        eventually("advisor stopped") { !s.agents.isLive(sid) }
        assertEquals(SessionStatus.IDLE, s.sessions.get(sid)!!.status)

        // Let the reviewers finish.
        followUpReview = "PASS"
        world.launcher.launched.filter { "after follow-up round" in it.spec.prompt.orEmpty() }.forEach { p ->
            p.result(null, review("PASS")); p.exit(0)
        }
        awaitReview(runId, ReviewState.NONE)
        val done = s.runs.get(runId)!!
        assertEquals("1111111222222233333334444444555555566666", done.reviewedSha)
        assertEquals(
            "The approver escalated: Unsure about the migration\n\n**Follow-up review 1** (commit `1111111`): all four reviewers pass.",
            done.error,
        )
        assertTrue(events(sid).any { it.type == "system" && payload(it)["text"]!!.jsonPrimitive.content.startsWith("Follow-up review 1 of commit 1111111: security PASS") })
        assertTrue(world.github.actions.any { it.contains("were pushed (1111111) and reviewed again: security PASS, ux PASS, ui-design PASS, code PASS.") })

        // Approve merges and ends the conversation.
        val merged = runBlocking { s.orchestrator.approve(alice, runId) }
        assertEquals(RunStatus.MERGED, merged.status)
        assertEquals(SessionStatus.DONE, s.sessions.get(sid)!!.status)
        assertNotNull(s.sessions.get(sid)!!.endedAt)
    }

    @Test
    fun `CHANGES in a follow-up review do not block approval, and the error says who still wants changes`() {
        committingAdvisor()
        val runId = needsHuman()
        followUpReview = "CHANGES"
        talk(alice, runId, "please change it")
        awaitReview(runId, ReviewState.RUNNING)
        awaitReview(runId, ReviewState.NONE)
        assertTrue(s.runs.get(runId)!!.error!!.endsWith("**Follow-up review 1** (commit `1111111`): security, ux, ui-design, code still request changes."))
        // A second change: the error keeps the original reason, with only the latest follow-up paragraph.
        followUpReview = "PASS"
        val sid = s.runs.get(runId)!!.advisorSessionId!!
        committingAdvisor("2222222333333344444445555555666666677777")
        talk(alice, runId, "please change again")
        eventually("second follow-up") { s.runs.get(runId)!!.reviewedSha == "2222222333333344444445555555666666677777" }
        assertEquals(
            "The approver escalated: Unsure about the migration\n\n**Follow-up review 2** (commit `2222222`): all four reviewers pass.",
            s.runs.get(runId)!!.error,
        )
        awaitIdle(sid)
        assertEquals(RunStatus.MERGED, runBlocking { s.orchestrator.approve(alice, runId) }.status)
    }

    @Test
    fun `a failed follow-up review leaves the run waiting with review required, review again works`() {
        committingAdvisor()
        val runId = needsHuman()
        followUpReview = "fail"
        val sid = UUID.fromString(talk(alice, runId, "please change it").sessionId)
        awaitReview(runId, ReviewState.RUNNING)
        awaitReview(runId, ReviewState.REQUIRED)
        val r = s.runs.get(runId)!!
        assertEquals(RunStatus.NEEDS_HUMAN, r.status)
        assertTrue(java.nio.file.Files.isDirectory(s.workspaces.runDir("alice", "demo-app", runId)))
        assertTrue(s.runs.stages(runId).none { it.status in setOf("running", "pending") })
        assertTrue(events(sid).any { it.type == "system" && payload(it)["level"]!!.jsonPrimitive.content == "error" && it.payload.toString().contains("Review again") })
        assertEquals(409 to "review_required", code { runBlocking { s.orchestrator.approve(alice, runId) } })
        assertEquals(403 to "forbidden", code { runBlocking { s.orchestrator.review(root, runId) } })

        followUpReview = "PASS"
        val again = runBlocking { s.orchestrator.review(alice, runId) }
        assertEquals(ReviewState.RUNNING, again.reviewState)
        awaitReview(runId, ReviewState.NONE)
        assertEquals(2, s.runs.get(runId)!!.round - s.runs.get(runId)!!.followUpFrom!! + 1, "review again is follow-up round 2")
        assertEquals(409 to "nothing_to_review", code { runBlocking { s.orchestrator.review(alice, runId) } })
        assertEquals(RunStatus.MERGED, runBlocking { s.orchestrator.approve(alice, runId) }.status)
    }

    @Test
    fun `review again needs a conversation, and approve catches a HEAD the review has not seen`() {
        val runId = needsHuman()
        assertEquals(409 to "nothing_to_review", code { runBlocking { s.orchestrator.review(alice, runId) } })
        val sid = UUID.fromString(talk(alice, runId, "hello").sessionId)
        awaitIdle(sid)
        assertEquals(409 to "nothing_to_review", code { runBlocking { s.orchestrator.review(alice, runId) } })
        // A commit the server has not reconciled (e.g. a restart mid-turn): approve refuses and requires a review.
        world.git.headSha = "feedfacefeedfacefeedfacefeedfacefeedface"
        assertEquals(409 to "review_required", code { runBlocking { s.orchestrator.approve(alice, runId) } })
        assertEquals(ReviewState.REQUIRED, s.runs.get(runId)!!.reviewState)
        runBlocking { s.orchestrator.review(alice, runId) }
        awaitReview(runId, ReviewState.NONE)
        assertEquals(RunStatus.MERGED, runBlocking { s.orchestrator.approve(alice, runId) }.status)
    }

    // ------------------------------------------------------------ cancel, approve, restart

    @Test
    fun `cancel during a turn stops the agent and ends the run cancelled`() {
        val runId = needsHuman()
        world.launcher.advisor = { p, _ -> p.init(); p.assistantText("thinking") }
        val sid = UUID.fromString(talk(alice, runId, "think long").sessionId)
        eventually("advisor mid-turn") { s.agents.isLive(sid) && s.sessions.get(sid)!!.status == SessionStatus.RUNNING }
        val r = runBlocking { s.orchestrator.cancel(root, runId) }
        assertEquals(RunStatus.CANCELLED, r.status)
        assertTrue(advisorProcs().single().killed)
        assertFalse(s.agents.isLive(sid))
        assertEquals(SessionStatus.STOPPED, s.sessions.get(sid)!!.status)
        assertEquals(ReviewState.NONE, s.runs.get(runId)!!.reviewState)
    }

    @Test
    fun `cancel during a follow-up review stops the reviewers and ends the run cancelled`() {
        committingAdvisor()
        val runId = needsHuman()
        followUpReview = "hang"
        val sid = UUID.fromString(talk(alice, runId, "please change").sessionId)
        eventually("four follow-up reviewers") { followUpPrompts.size == 4 }
        val r = runBlocking { s.orchestrator.cancel(alice, runId) }
        assertEquals(RunStatus.CANCELLED, r.status)
        assertTrue(world.launcher.launched.filter { "after follow-up round" in it.spec.prompt.orEmpty() }.all { it.killed })
        assertTrue(s.runs.stages(runId).none { it.status == "running" || it.status == "pending" })
        assertNull(s.orchestrator.job(runId))
        assertEquals(SessionStatus.STOPPED, s.sessions.get(sid)!!.status)
    }

    @Test
    fun `approve stops the idle agent process and ends the session done`() {
        val runId = needsHuman()
        val sid = UUID.fromString(talk(alice, runId, "is it safe?").sessionId)
        awaitIdle(sid)
        assertTrue(s.agents.isLive(sid))
        assertEquals(RunStatus.MERGED, runBlocking { s.factory.approveRun(alice, runId) }.status)
        assertTrue(advisorProcs().single().killed)
        assertFalse(s.agents.isLive(sid))
        assertEquals(SessionStatus.DONE, s.sessions.get(sid)!!.status)
        assertEquals(409 to "run_not_waiting", code { talk(alice, runId, "x") })
    }

    @Test
    fun `after a restart the advisor is idle and resumable, an interrupted review is required, and no stage stays running`() {
        committingAdvisor()
        val runId = needsHuman()
        followUpReview = "hang"
        val sid = UUID.fromString(talk(alice, runId, "please change").sessionId)
        eventually("four follow-up reviewers") { followUpPrompts.size == 4 }
        s.stop()
        // The advisor was mid-turn as far as the database knows.
        s.sessions.setStatus(sid, SessionStatus.RUNNING)
        val s2 = Services(world.cfg, s.db, world.github, world.git, world.launcher)
        extraServices += s2
        s2.start()
        val r = s2.runs.get(runId)!!
        assertEquals(RunStatus.NEEDS_HUMAN, r.status)
        assertEquals(ReviewState.REQUIRED, r.reviewState)
        assertTrue(s2.runs.stages(runId).none { it.status == "running" || it.status == "pending" })
        assertEquals(SessionStatus.IDLE, s2.sessions.get(sid)!!.status)
        assertTrue(s2.sessions.byRun(runId).filter { it.kind != ADVISOR_KIND }.none { it.status in setOf("running", "starting", "idle") })
        assertEquals(409 to "review_required", code { runBlocking { s2.orchestrator.approve(alice, runId) } })

        // The conversation resumes with the stored Claude session.
        followUpReview = "PASS"
        world.launcher.advisor = { p, _ -> p.init(); p.result("back") }
        val launches = advisorProcs().size
        runBlocking { s2.factory.talkToRun(alice, runId, "still there?") }
        eventually("resumed") { advisorProcs().size == launches + 1 }
        assertEquals("advisor-claude-$sid", advisorProcs().last().spec.resumeClaudeSessionId)
        // The unreviewed commit is still caught: the turn's reconcile starts the review again.
        eventually("reviewed after restart") { s2.runs.get(runId)!!.reviewState == ReviewState.NONE }
    }

    @Test
    fun `a release run stays held while the owner talks and the change is reviewed`() {
        committingAdvisor()
        val number = world.github.addIssue("demo-app", "Doubtful", "body").number
        val release = runBlocking {
            val r = s.releases.create(alice, "demo-app", "talk", "")
            s.releases.assign(alice, "demo-app", listOf(number), r.id)
            s.releases.start(alice, r.id)
            r
        }
        eventually("held") { s.releaseStore.get(release.id)!!.issues.single().state == "held" }
        val runId = s.releaseStore.get(release.id)!!.issues.single().runId!!
        followUpReview = "hang"
        talk(alice, runId, "please change")
        eventually("four follow-up reviewers") { followUpPrompts.size == 4 }
        assertEquals("held", s.releaseStore.get(release.id)!!.issues.single().state)
        world.launcher.launched.filter { "after follow-up round" in it.spec.prompt.orEmpty() }.forEach { p -> p.result(null, review("PASS")); p.exit(0) }
        awaitReview(runId, ReviewState.NONE)
        Thread.sleep(200)
        assertEquals("held", s.releaseStore.get(release.id)!!.issues.single().state)
        assertEquals(RunStatus.NEEDS_HUMAN, s.runs.get(runId)!!.status)
    }

    @Test
    fun `a usage limit ends the turn with a notice and loses nothing`() {
        val runId = needsHuman()
        world.launcher.advisor = { p, _ ->
            p.init()
            p.emit(buildJsonObject {
                put("type", "rate_limit_event")
                putJsonObject("rate_limit_info") { put("status", "rejected"); put("resetsAt", 1893456000); put("rateLimitType", "five_hour") }
            })
            p.result("You've hit your limit · resets 3pm", isError = true)
        }
        val sid = UUID.fromString(talk(alice, runId, "question").sessionId)
        awaitIdle(sid)
        val warn = events(sid).single { it.type == "system" && payload(it)["level"]!!.jsonPrimitive.content == "warn" }
        val text = payload(warn)["text"]!!.jsonPrimitive.content
        assertTrue("usage limit" in text && "2030-01-01" in text && "next message" in text, text)
        world.launcher.advisor = { p, _ -> p.init(); p.result("answer") }
        talk(alice, runId, "question again")
        awaitIdle(sid)
        assertEquals(1, events(sid).count { it.type == "system" && payload(it)["level"]!!.jsonPrimitive.content == "warn" })
    }

    // ------------------------------------------------------------ HTTP

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String): HttpClient {
        val c = createClient { install(HttpCookies) }
        assertEquals(200, c.post("/api/auth/login") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
        }.status.value)
        return c
    }

    private suspend fun HttpClient.postJson(path: String, body: String = "{}", csrf: Boolean = true): HttpResponse =
        post(path) { if (csrf) header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody(body) }

    private fun errorCode(body: String) =
        AppJson.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    @Test
    fun `the HTTP routes enforce CSRF, ownership and the session input route`() = testApplication {
        application { agernModule(s) }
        val runId = needsHuman()
        val a = login("alice", "pw-a")
        val b = login("bob", "pw-b")
        val r = login("root", "pw-r")
        assertEquals(403, a.postJson("/api/runs/$runId/conversation", """{"text":"hi"}""", csrf = false).status.value)
        assertTrue(advisorProcs().isEmpty())
        val bobRes = b.postJson("/api/runs/$runId/conversation", """{"text":"hi"}""")
        assertEquals(403, bobRes.status.value)
        assertEquals("forbidden", errorCode(bobRes.bodyAsText()))
        assertEquals(403, r.postJson("/api/runs/$runId/conversation", """{"text":"hi"}""").status.value)
        assertEquals(403, b.postJson("/api/runs/$runId/review").status.value)
        assertEquals(403, r.postJson("/api/runs/$runId/review").status.value)
        assertEquals(404, a.postJson("/api/runs/${UUID.randomUUID()}/conversation", """{"text":"hi"}""").status.value)

        val ok = a.postJson("/api/runs/$runId/conversation", """{"text":"hi"}""")
        assertEquals(202, ok.status.value, ok.bodyAsText())
        val body = AppJson.parseToJsonElement(ok.bodyAsText()).jsonObject
        assertEquals("accepted", body["status"]!!.jsonPrimitive.content)
        val sid = UUID.fromString(body["sessionId"]!!.jsonPrimitive.content)
        awaitIdle(sid)
        val runJson = AppJson.parseToJsonElement(a.postJson("/api/runs/$runId/review").bodyAsText()).jsonObject
        assertEquals("nothing_to_review", runJson["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)

        // The session routes: input behaves like the run route (text only), interrupt is the owner's.
        val att = a.postJson("/api/sessions/$sid/input", """{"text":"x","attachmentIds":["${UUID.randomUUID()}"]}""")
        assertEquals(422, att.status.value)
        assertEquals("attachments_not_allowed", errorCode(att.bodyAsText()))
        assertEquals(403, b.postJson("/api/sessions/$sid/input", """{"text":"x"}""").status.value)
        assertEquals(403, b.postJson("/api/sessions/$sid/interrupt").status.value)
        val viaSession = a.postJson("/api/sessions/$sid/input", """{"text":"via the session"}""")
        assertEquals(202, viaSession.status.value, viaSession.bodyAsText())
        awaitIdle(sid)
        assertEquals(202, a.postJson("/api/sessions/$sid/interrupt").status.value)
        val run = AppJson.parseToJsonElement(a.postJson("/api/runs/$runId/approve").bodyAsText()).jsonObject
        assertEquals("merged", run["status"]!!.jsonPrimitive.contentOrNull)
        assertEquals(sid.toString(), run["conversation"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
    }
}
