package dk.egernet.factory

import dk.egernet.factory.db.RunRecord
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.mcp.McpGrant
import dk.egernet.factory.mcp.McpRegistry
import dk.egernet.factory.pipeline.DeliveryPlan
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Partial delivery (docs/ARCHITECTURE.md §6 "Partial delivery"), with the fake launcher and fake GitHub. */
class PartialDeliveryTest {
    private val world = TestWorld(twoFactor = false)
    private val s = world.services
    private val gh = world.github
    private val alice = world.user("alice")
    private val bob = world.user("bob")
    private val root = world.user("root", admin = true)

    @AfterTest fun tearDown() = world.close()

    init {
        world.git.deleteWorktrees = true
        s.orchestrator.outsideCheckMillis = 0
    }

    private val plainSpec = "## Summary\nDo the thing.\n\n## Acceptance criteria\n- [ ] done\n"

    /** [spec] for the spec-writer; [escalate]: the approver escalates. */
    private fun script(spec: String = PLAN_SPEC, escalate: Boolean = false) {
        world.launcher.stage = { p ->
            p.init()
            when (p.spec.kind) {
                "spec-writer" -> p.result(spec)
                "builder" -> p.result("Built it.")
                "approver" -> {
                    val v = if (escalate) "ESCALATE" else "APPROVE"
                    p.result(null, buildJsonObject { put("verdict", v); put("summary", "approver says $v.") })
                }
                else -> p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "fine"); put("findings", buildJsonArray { }) })
            }
            p.exit(0)
        }
    }

    private fun start(issue: Int = 1, by: UserRecord = alice) = runBlocking { s.orchestrator.start(by, "demo-app", issue, null) }
    private fun finished(run: RunRecord) = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
    private fun code(block: suspend () -> Unit): ApiException = assertFailsWith<ApiException> { runBlocking { block() } }
    private fun comments(issue: Int = 1) = gh.repos.getValue("demo-app").comments[issue].orEmpty().mapNotNull { it.body }
    private fun lead(run: RunRecord) = gh.pullBodies.getValue(s.runs.get(run.id)!!.prNumber!!).lineSequence().first()
    private fun approverPrompt() = world.launcher.byRole("approver").last().spec.prompt!!
    private fun events(run: RunRecord, role: String): List<String> {
        val sid = s.runs.stages(run.id).first { it.role == role && it.sessionId != null }.sessionId!!
        return s.events.list(sid, 0, 10_000).filter { it.type == "system" }.map { it.payload.jsonObject["text"]!!.jsonPrimitive.content }
    }

    // ------------------------------------------------------------ the parser

    @Test
    fun `DeliveryPlan parses the section`() {
        assertNull(DeliveryPlan.parse(null))
        assertNull(DeliveryPlan.parse("## Summary\nNo plan here.\n"))
        assertEquals(DeliveryPlan.Delivery("the first step.", listOf("the second step", "the third step")), DeliveryPlan.parse(PLAN_SPEC))

        // Plain labels, a numbered list, continuation lines, a case-insensitive heading; the section ends at the next heading.
        val plain = DeliveryPlan.parse(
            "## delivery PLAN\nThis run: the API\nand its tests.\n\nRemaining:\n1. the web page,\n   with a dialog\n2) the docs\n" +
                "## Design\n- not an item\n",
        )!!
        assertEquals("the API and its tests.", plain.delivers)
        assertEquals(listOf("the web page, with a dialog", "the docs"), plain.remaining)
        assertTrue(plain.partial)

        // A heading inside a code fence is not the section.
        assertNull(DeliveryPlan.parse("## Summary\n```\n## Delivery plan\n**Remaining:**\n- x\n```\n"))
        val afterFence = DeliveryPlan.parse("```\n## Delivery plan\n```\n## Delivery plan\n**Remaining:**\n* real\n")!!
        assertEquals(listOf("real"), afterFence.remaining)
        assertEquals("", afterFence.delivers)

        // No remaining items: not partial.
        val none = DeliveryPlan.parse("## Delivery plan\n**This run:** everything.\n")!!
        assertEquals("everything.", none.delivers)
        assertFalse(none.partial)

        // The caps.
        val long = "x".repeat(2000)
        val capped = DeliveryPlan.parse("## Delivery plan\n**This run:** $long\n**Remaining:**\n" + (1..30).joinToString("") { "- $it $long\n" })!!
        assertEquals(DeliveryPlan.MAX_DELIVERS, capped.delivers.length)
        assertEquals(DeliveryPlan.MAX_ITEMS, capped.remaining.size)
        assertTrue(capped.remaining.all { it.length == DeliveryPlan.MAX_ITEM })
    }

    // ------------------------------------------------------------ the pipeline

    @Test
    fun `a spec with a delivery plan makes a partial run that merges and leaves the issue open`() {
        script()
        val run = finished(start())
        assertEquals(RunStatus.MERGED, run.status, run.error)
        assertTrue(run.partial)
        assertNull(run.partialSetBy)
        val dto = s.runs.toDto(run)
        assertTrue(dto.partial)
        assertEquals(RunDeliveryDto("the first step.", listOf("the second step", "the third step")), dto.delivery)
        assertEquals("Part of #1", lead(run))
        assertTrue(gh.actions.none { it.startsWith("close ") }, gh.actions.toString())
        assertTrue(events(run, "spec-writer").any {
            it == "Partial delivery: this run covers part of #1 (2 step(s) remain); the issue stays open after the merge."
        })
        val p = approverPrompt()
        assertTrue(p.contains("Partial delivery: this run delivers only the part of issue #1 that the spec's Delivery plan names under \"This run\"."), p)
        assertTrue(p.contains("The pull request says `Part of #1` and the issue stays open after the merge."), p)
        assertTrue(p.contains("`APPROVE` squash-merges the pull request immediately, without a human looking at it (the issue stays open: partial delivery)"), p)
        val merged = comments().filter { it.startsWith("**Agern: merged**") }
        assertEquals(1, merged.size, comments().toString())
        assertEquals(
            "**Agern: merged** pull request #${run.prNumber} (approved by the approver agent). This is a partial delivery: the issue stays open." +
                "\n\n**Delivered:** the first step.\n\n**Remaining:**\n- the second step\n- the third step",
            merged.single(),
        )
        assertEquals("open", gh.repos.getValue("demo-app").issues.first { it.number == 1 }.state)
    }

    @Test
    fun `a complete run still closes the issue`() {
        script(plainSpec)
        val run = finished(start())
        assertEquals(RunStatus.MERGED, run.status, run.error)
        assertFalse(run.partial)
        assertNull(s.runs.toDto(run).delivery)
        assertEquals("Closes #1", lead(run))
        assertTrue(gh.actions.contains("close demo-app#1"))
        assertTrue(comments().contains("**Agern: merged** pull request #${run.prNumber} (approved by the approver agent)."), comments().toString())
        assertFalse(approverPrompt().contains("Partial delivery"))
    }

    @Test
    fun `a plan without remaining steps is a complete delivery, with a warning`() {
        script("## Summary\nAll.\n\n## Delivery plan\n**This run:** everything.\n\n## Design\nx\n")
        val run = finished(start())
        assertEquals(RunStatus.MERGED, run.status)
        assertFalse(run.partial)
        assertTrue(gh.actions.contains("close demo-app#1"))
        assertTrue(events(run, "spec-writer").contains("The spec has a Delivery plan without remaining steps; the run is treated as a complete delivery."))
    }

    @Test
    fun `the owner switches an escalated run to partial and back, then merges it keeping the issue open`() {
        script(plainSpec, escalate = true)
        val run = finished(start())
        assertEquals(RunStatus.NEEDS_HUMAN, run.status)
        assertEquals("Closes #1", lead(run))
        assertFalse(comments().last().contains("partial delivery"))

        val partial = runBlocking { s.orchestrator.setPartial(alice, run.id, true) }
        assertTrue(partial.partial)
        assertEquals("alice", partial.partialSetBy)
        assertEquals("Part of #1", lead(run))
        assertTrue(gh.pullMeta.getValue(run.prNumber!!).title.isNotEmpty())
        // The same value again changes nothing on GitHub.
        val updates = gh.actions.count { it.startsWith("update-pr") }
        runBlocking { s.orchestrator.setPartial(alice, run.id, true) }
        assertEquals(updates, gh.actions.count { it.startsWith("update-pr") })

        assertFalse(runBlocking { s.orchestrator.setPartial(alice, run.id, false) }.partial)
        assertEquals("Closes #1", lead(run))
        runBlocking { s.orchestrator.setPartial(alice, run.id, true) }

        val merged = runBlocking { s.orchestrator.approve(alice, run.id) }
        assertEquals(RunStatus.MERGED, merged.status)
        assertTrue(gh.actions.none { it.startsWith("close ") })
        assertEquals(
            "**Agern: merged** pull request #${run.prNumber} (approved by @alice). This is a partial delivery: the issue stays open." +
                "\n\nThe run owner (@alice) marked this run as a partial delivery; the spec names no remaining steps.",
            comments().single { it.startsWith("**Agern: merged**") },
        )
    }

    @Test
    fun `switching is refused for others, outside needs_human, for release runs, and when GitHub fails`() {
        script(plainSpec, escalate = true)
        val run = finished(start())
        assertEquals("forbidden", code { s.orchestrator.setPartial(bob, run.id, true) }.code)
        assertEquals("forbidden", code { s.orchestrator.setPartial(root, run.id, true) }.code)

        gh.updatePullFailure = 502
        val failed = code { s.orchestrator.setPartial(alice, run.id, true) }
        assertEquals(502, failed.status.value)
        assertFalse(s.runs.get(run.id)!!.partial)
        assertEquals("Closes #1", lead(run))
        // An approval that chooses "keep open" fails the same way and merges nothing.
        assertEquals(502, code { s.orchestrator.approve(alice, run.id, closeIssue = false) }.status.value)
        assertEquals(RunStatus.NEEDS_HUMAN, s.runs.get(run.id)!!.status)
        assertTrue(gh.actions.none { it.startsWith("merge ") })
        gh.updatePullFailure = null

        runBlocking { s.orchestrator.approve(alice, run.id) }
        assertEquals("run_not_waiting", code { s.orchestrator.setPartial(alice, run.id, true) }.code)
    }

    @Test
    fun `a body whose first line is not Agern's lead is left untouched`() {
        script(plainSpec, escalate = true)
        val run = finished(start())
        gh.pullBodies[run.prNumber!!] = "Fixes #1 by hand\n\nrest"
        assertTrue(runBlocking { s.orchestrator.setPartial(alice, run.id, true) }.partial)
        assertEquals("Fixes #1 by hand\n\nrest", gh.pullBodies[run.prNumber!!])
    }

    @Test
    fun `approve chooses the issue's fate`() {
        script(plainSpec, escalate = true)
        val keep = finished(start(issue = 1))
        val merged = runBlocking { s.orchestrator.approve(alice, keep.id, closeIssue = false) }
        assertEquals(RunStatus.MERGED, merged.status)
        assertTrue(merged.partial)
        assertEquals("alice", merged.partialSetBy)
        assertTrue(gh.actions.none { it == "close demo-app#1" })

        script(PLAN_SPEC, escalate = true)
        val close = finished(start(issue = 2))
        assertTrue(close.partial)
        assertTrue(comments(2).any { it.contains("This run is a partial delivery: merging it keeps the issue open.") }, comments(2).toString())
        val closed = runBlocking { s.orchestrator.approve(alice, close.id, closeIssue = true) }
        assertEquals(RunStatus.MERGED, closed.status)
        assertFalse(closed.partial)
        assertTrue(gh.actions.contains("close demo-app#2"))
        assertEquals("Closes #2", lead(close))
        assertEquals("**Agern: merged** pull request #${close.prNumber} (approved by @alice).", comments(2).single { it.startsWith("**Agern: merged**") })
    }

    @Test
    fun `the chat command and the MCP tool choose the issue's fate`() {
        script(plainSpec, escalate = true)
        val chat = s.sessions.create("demo-app", "chat", "opus", "idle", alice.id, null)
        val a = finished(start(issue = 1))
        assertEquals(
            "Usage: /factory approve [runId] [update] [keep-open|close]",
            runBlocking { s.factory.handleCommand(chat, alice, "/factory approve ${a.id} keep-open close") },
        )
        val reply = runBlocking { s.factory.handleCommand(chat, alice, "/factory approve KEEP-OPEN ${a.id}") }
        assertEquals("Run ${a.id}: merged — issue #1 stays open (partial delivery)", reply)
        assertTrue(gh.actions.none { it == "close demo-app#1" })
        assertTrue(FactoryService.HELP.contains("keep-open|close"))

        val b = finished(start(issue = 2))
        val token = s.mcpRegistry.issue(McpGrant(chat.id, alice.id, "demo-app", null, McpRegistry.CHAT_TOOLS))
        val tools = runBlocking { AppJson.parseToJsonElement(s.mcp.handle(token, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").second!!) }
            .jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
        val approve = tools.single { it["name"]!!.jsonPrimitive.content == "factory_approve_run" }
        assertTrue("closeIssue" in approve["inputSchema"]!!.jsonObject["properties"]!!.jsonObject.keys)
        val call = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"factory_approve_run","arguments":{"runId":"${b.id}","closeIssue":false}}}"""
        val answer = runBlocking { s.mcp.handle(token, call).second!! }
        assertTrue(answer.contains("Run ${b.id}: merged — issue #2 stays open (partial delivery)"), answer)
        assertTrue(gh.actions.none { it == "close demo-app#2" })

        // Without a choice: unchanged, the issue is closed.
        val c = finished(start(issue = 3))
        val plain = """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"factory_approve_run","arguments":{"runId":"${c.id}"}}}"""
        assertTrue(runBlocking { s.mcp.handle(token, plain).second!! }.contains("Run ${c.id}: merged"))
        assertTrue(gh.actions.contains("close demo-app#3"))
    }

    // ------------------------------------------------------------ HTTP

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String): HttpClient {
        val c = createClient { install(HttpCookies) }
        c.post("/api/auth/login") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
        }
        c.put("/api/me/github-token") { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"token":"fake-$user"}""") }
        return c
    }

    @Test
    fun `the HTTP endpoints switch and approve with a choice`() = testApplication {
        application { agernModule(s) }
        script(plainSpec, escalate = true)
        val a = login("alice", "pw-a")
        val b = login("bob", "pw-b")
        val run = finished(start())
        val put = { c: HttpClient, body: String -> runBlocking { c.put("/api/runs/${run.id}/partial") { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody(body) } } }
        assertEquals(403, put(b, """{"partial":true}""").status.value)
        val ok = put(a, """{"partial":true}""")
        assertEquals(200, ok.status.value, ok.bodyAsText())
        val dto = AppJson.parseToJsonElement(ok.bodyAsText()).jsonObject
        assertEquals("true", dto["partial"]!!.jsonPrimitive.content)
        assertEquals("alice", dto["partialSetBy"]!!.jsonPrimitive.content)
        val missing = a.put("/api/runs/00000000-0000-0000-0000-000000000000/partial") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"partial":true}""")
        }
        assertEquals(404, missing.status.value)

        // Approve with a body: closeIssue true makes it complete again and closes the issue.
        val approved = a.post("/api/runs/${run.id}/approve") { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"closeIssue":true}""") }
        assertEquals(200, approved.status.value, approved.bodyAsText())
        assertTrue(gh.actions.contains("close demo-app#1"))

        // Without a body: as before.
        val other = finished(start(issue = 2))
        val plain = a.post("/api/runs/${other.id}/approve") { header("X-Factory-Request", "1") }
        assertEquals(200, plain.status.value, plain.bodyAsText())
        assertTrue(gh.actions.contains("close demo-app#2"))
        val bad = a.post("/api/runs/${other.id}/approve") { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"closeIssue":"maybe"}""") }
        assertEquals(400, bad.status.value)
    }

    // ------------------------------------------------------------ releases

    @Test
    fun `a release run with a delivery plan is not partial and closes with its release`() {
        script()
        val n = gh.addIssue("demo-app", "Release issue", "body").number
        val r = runBlocking {
            val rel = s.releases.create(alice, "demo-app", "p1", "")
            s.releases.assign(alice, "demo-app", listOf(n), rel.id)
            s.releases.start(alice, rel.id)
            rel
        }
        val run = runBlocking {
            var found: RunRecord? = null
            kotlinx.coroutines.withTimeoutOrNull(10_000) {
                while (found == null) { found = s.runs.list("demo-app", false).firstOrNull { it.releaseId == r.id }; kotlinx.coroutines.delay(20) }
            }
            found!!
        }
        val done = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertFalse(done.partial)
        assertNotNull(done.delivery)
        assertTrue(events(done, "spec-writer").contains(
            "The spec plans a partial delivery, which is not supported in a release: the issue is closed when the release is merged.",
        ))
        assertFalse(gh.pullBodies.getValue(done.prNumber!!).startsWith("Part of"))
        assertEquals("partial_not_supported", code { s.orchestrator.setPartial(alice, run.id, true) }.code)
    }

    companion object {
        const val PLAN_SPEC = "## Summary\nThe first step.\n\n## Delivery plan\n**This run:** the first step.\n**Remaining:**\n" +
            "- the second step\n- the third step\n\n## Acceptance criteria\n- [ ] done\n"
    }
}
