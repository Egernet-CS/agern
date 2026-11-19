package dk.egernet.factory

import dk.egernet.factory.mcp.McpGrant
import dk.egernet.factory.mcp.McpRegistry
import dk.egernet.factory.sandbox.ExecResult
import dk.egernet.factory.sandbox.SandboxDefinitions
import dk.egernet.factory.sandbox.SandboxManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Per-run sandboxes with the fake runtime (docs/ARCHITECTURE.md §5 "Sandboxes"). */
class SandboxTest {
    private val worlds = CopyOnWriteArrayList<TestWorld>()

    @AfterTest fun tearDown() = worlds.forEach { it.closeAndReleaseDb() }

    private fun world(tweak: (Config) -> Config = { it }) = TestWorld(sandbox = true, tweak = tweak).also { worlds += it }

    private val definition = """
        image: docker.io/library/eclipse-temurin:21-jdk
        setup: |
          echo setting up
        env: { CI: "true" }
        services:
          postgres:
            image: docker.io/library/postgres:17
            env: { POSTGRES_PASSWORD: dev }
        caches: [ /root/.gradle ]
        resources: { cpus: 1, memory: 1g }
    """.trimIndent() + "\n"

    private fun approve(w: TestWorld, content: String = definition) {
        val root = w.user("root", admin = true)
        runBlocking { w.services.sandboxes.approve(root, "demo-app", SandboxDefinitions.sha256(content)) }
    }

    // ---------------------------------------------------------------- MCP calls the way an agent makes them

    private fun token(p: FakeProcess): String = AppJson.parseToJsonElement(p.spec.mcpConfigJson).jsonObject["mcpServers"]!!.jsonObject["factory"]!!
        .jsonObject["headers"]!!.jsonObject["Authorization"]!!.jsonPrimitive.content.removePrefix("Bearer ")

    private data class ToolAnswer(val isError: Boolean, val text: String) {
        val json: JsonObject get() = AppJson.parseToJsonElement(text).jsonObject
    }

    private fun call(w: TestWorld, token: String, tool: String, args: JsonObject = JsonObject(emptyMap())): ToolAnswer {
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
            putJsonObject("params") { put("name", tool); put("arguments", args) }
        }
        val res = AppJson.parseToJsonElement(runBlocking { w.services.mcp.handle(token, body.toString()) }.second!!).jsonObject["result"]!!.jsonObject
        return ToolAnswer(res["isError"]?.jsonPrimitive?.booleanOrNull == true, res["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    private fun exec(w: TestWorld, p: FakeProcess, command: String, timeoutSeconds: Long? = null) = call(w, token(p), "factory_sandbox_exec", buildJsonObject {
        put("command", command); timeoutSeconds?.let { put("timeoutSeconds", it) }
    })

    private fun toolNames(w: TestWorld, token: String): List<String> =
        AppJson.parseToJsonElement(runBlocking { w.services.mcp.handle(token, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""") }.second!!)
            .jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    /** Every stage passes; [builder] is the builder's work and returns its summary. */
    private fun script(w: TestWorld, reviewer: (FakeProcess) -> Unit = {}, builder: (FakeProcess) -> String = { "Built it." }) {
        w.launcher.stage = { p ->
            when (val role = p.spec.kind) {
                "spec-writer" -> { p.init(); p.result("## Spec\nDo it."); p.exit(0) }
                "builder" -> { p.init(); val text = builder(p); p.result(text); p.exit(0) }
                "approver" -> { p.init(); p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") }); p.exit(0) }
                else -> {
                    reviewer(p)
                    p.init()
                    p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "$role ok"); put("findings", buildJsonArray { }) })
                    p.exit(0)
                }
            }
        }
    }

    private fun start(w: TestWorld, issue: Int = 1) = runBlocking { w.services.orchestrator.start(w.user("alice"), "demo-app", issue, null) }

    private fun builderEvents(w: TestWorld, runId: UUID) =
        w.services.runs.stages(runId).filter { it.role == "builder" }.flatMap { st -> w.services.events.list(st.sessionId!!, 0, 1000) }

    private fun systemTexts(w: TestWorld, runId: UUID) =
        builderEvents(w, runId).filter { it.type == "system" }.map { it.payload.jsonObject["text"]!!.jsonPrimitive.content }

    private fun waitFor(timeoutMs: Long = 10_000, cond: () -> Boolean) = runBlocking {
        withTimeout(timeoutMs) { while (!cond()) kotlinx.coroutines.delay(20) }
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun `an approved definition gives the builder a lazily created sandbox, removed after the merge`() {
        val w = world()
        w.sandboxFile(definition)
        approve(w)
        val rt = w.sandboxRuntime
        var startedBeforeFirstExec = -1
        var info: JsonObject? = null
        val answers = mutableListOf<ToolAnswer>()
        script(w) { p ->
            startedBeforeFirstExec = rt.started.size
            info = call(w, token(p), "factory_sandbox_info").json
            answers += exec(w, p, "./gradlew test")
            answers += exec(w, p, "npm test")
            "Built and tested in the sandbox."
        }
        val run = start(w)
        val done = w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)

        // Lazy: nothing existed before the builder's first command.
        assertEquals(0, startedBeforeFirstExec)
        assertEquals(true, info!!["available"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(false, info!!["created"]!!.jsonPrimitive.booleanOrNull)
        assertEquals("docker.io/library/eclipse-temurin:21-jdk", info!!["image"]!!.jsonPrimitive.content)
        assertEquals("postgres", info!!["services"]!!.jsonArray[0].jsonObject["host"]!!.jsonPrimitive.content)
        for (a in answers) {
            assertFalse(a.isError, a.text)
            assertEquals(0, a.json["exitCode"]!!.jsonPrimitive.content.toInt())
            assertEquals(false, a.json["truncated"]!!.jsonPrimitive.booleanOrNull)
        }
        assertEquals("ran: ./gradlew test\n", answers[0].json["output"]!!.jsonPrimitive.content)

        // Created once: network, cache volume, the sidecar and the sandbox; setup ran before the first command.
        val id = run.id
        assertEquals(listOf("agern-run-$id-postgres", "agern-run-$id"), rt.started.map { it.name })
        val main = rt.started.last()
        val worktree = w.services.workspaces.runDir("alice", "demo-app", id).toAbsolutePath().normalize().toString()
        assertEquals(worktree, main.mounts[0].source)
        assertEquals("/workspace", main.mounts[0].target)
        assertEquals(listOf("/root/.gradle"), main.mounts.drop(1).map { it.target })
        assertTrue(main.mounts.drop(1).all { it.volume && it.source.startsWith("agern-cache-demo-app-") })
        assertEquals("/workspace", main.workdir)
        assertEquals(mapOf("agern.run" to id.toString(), "agern.repo" to "demo-app", "agern.role" to "sandbox"), main.labels)
        assertEquals(mapOf("CI" to "true"), main.env)
        assertEquals(1.0, main.cpus)
        assertEquals(1L shl 30, main.memoryBytes)
        assertEquals("postgres", rt.started.first().networkAlias)
        assertTrue(rt.started.all { it.network == "agern-run-$id" })
        // Nothing of the owner's credentials reaches a container.
        val envValues = rt.started.flatMap { it.env.values + it.env.keys }
        assertFalse(envValues.any { it.contains("sk-ant") || it.contains("fake-alice") || it.contains("TOKEN") }, envValues.toString())
        assertEquals(listOf("echo setting up\n", "./gradlew test", "npm test"), rt.execs.map { it.second })
        assertTrue(rt.execs.all { it.first == "agern-run-$id" })

        // Removed when the run merged.
        assertTrue(rt.containers.isEmpty() && rt.networks.isEmpty(), rt.calls.toString())
        assertTrue(rt.calls.contains("rm agern-run-$id agern-run-$id-postgres"), rt.calls.toString())
        assertEquals(0, w.services.sandboxes.inUse())

        // The decision is on the run, the builder's transcript says so and keeps the full logs.
        val dto = w.services.runs.toDto(done).sandbox!!
        assertEquals("approved", dto.state)
        assertEquals(SandboxDefinitions.sha256(definition), dto.sha256)
        val texts = systemTexts(w, id)
        assertTrue(texts.first().startsWith("Sandbox: Sandbox definition"), texts.toString())
        assertTrue(texts.any { it.startsWith("Sandbox ready") }, texts.toString())
        val logs = builderEvents(w, id).mapNotNull { it.payload.jsonObject["sandbox"]?.jsonObject }
        assertEquals(listOf("setup", "exec", "exec"), logs.map { it["kind"]!!.jsonPrimitive.content })
        assertEquals("ran: npm test\n", logs.last()["output"]!!.jsonPrimitive.content)
        val prompt = w.launcher.byRole("builder").single().written.first()
        assertTrue(prompt.contains("Sandbox: this run has one"), prompt)
    }

    @Test
    fun `output is truncated to its tail, the full log is kept, and timeouts are capped and restart the sandbox`() {
        val w = world()
        w.sandboxFile("image: docker.io/library/debian:12\n")
        approve(w, "image: docker.io/library/debian:12\n")
        val rt = w.sandboxRuntime
        rt.onExec = { _, cmd, _ ->
            when {
                cmd.contains("big") -> ExecResult(0, "x".repeat(30_000) + "END", false, false, 7)
                cmd.contains("slow") -> ExecResult(-1, "partial output", false, true, 5_000)
                else -> ExecResult(3, "failed", false, false, 1)
            }
        }
        val answers = mutableListOf<ToolAnswer>()
        script(w) { p ->
            answers += exec(w, p, "make big")
            answers += exec(w, p, "make slow", timeoutSeconds = 5)
            answers += exec(w, p, "false", timeoutSeconds = 999_999)
            answers += exec(w, p, "false", timeoutSeconds = 0)
            answers += exec(w, p, "   ")
            "done"
        }
        val run = start(w)
        assertEquals(RunStatus.MERGED, w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        val big = answers[0].json
        assertEquals(SandboxManager.OUTPUT_TAIL_CHARS, big["output"]!!.jsonPrimitive.content.length)
        assertTrue(big["output"]!!.jsonPrimitive.content.endsWith("END"))
        assertEquals(true, big["truncated"]!!.jsonPrimitive.booleanOrNull)
        val slow = answers[1].json
        assertEquals(true, slow["timedOut"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(-1, slow["exitCode"]!!.jsonPrimitive.content.toInt())
        assertTrue(rt.calls.contains("restart agern-run-${run.id}"), rt.calls.toString())
        assertEquals(3, answers[2].json["exitCode"]!!.jsonPrimitive.content.toInt())
        assertTrue(answers[3].isError && answers[4].isError)
        // Timeouts: as asked, but never above the server's exec limit (30 min, itself capped by the 60 min stage limit).
        assertEquals(listOf(30 * 60_000L, 5_000L, 30 * 60_000L), rt.execs.map { it.third })
        // The transcript keeps all of it.
        val log = builderEvents(w, run.id).mapNotNull { it.payload.jsonObject["sandbox"]?.jsonObject }.first()
        assertEquals(30_003, log["output"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun `without an approved, valid definition the run proceeds without a sandbox and says why`() {
        val w = world()
        val rt = w.sandboxRuntime
        val answers = mutableListOf<Pair<ToolAnswer, ToolAnswer>>()
        script(w) { p ->
            answers += exec(w, p, "./gradlew test") to call(w, token(p), "factory_sandbox_info")
            "Built on the host."
        }
        data class Case(val issue: Int, val state: String, val reason: String, val setup: () -> Unit)
        val cases = listOf(
            Case(1, "none", "has no .agern/sandbox.yml") { w.sandboxFile(null) },
            Case(2, "unapproved", "no version of it has been approved") { w.sandboxFile(definition) },
            Case(3, "invalid", "is not fully qualified") { w.sandboxFile("image: debian:12\n") },
            Case(1, "unapproved", "it changed since an admin approved") {
                w.sandboxFile(definition); approve(w); w.sandboxFile(definition + "# changed by a run\n")
            },
        )
        for ((i, c) in cases.withIndex()) {
            c.setup()
            val run = start(w, c.issue)
            val done = w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
            assertEquals(RunStatus.MERGED, done.status, "${c.state}: ${done.error}")
            val dto = w.services.runs.toDto(done).sandbox!!
            assertEquals(c.state, dto.state)
            assertTrue(dto.reason.contains(c.reason), dto.reason)
            val (execAnswer, info) = answers[i]
            assertTrue(execAnswer.isError && execAnswer.text.contains(c.reason), execAnswer.text)
            assertEquals(false, info.json["available"]!!.jsonPrimitive.booleanOrNull)
            val first = builderEvents(w, run.id).first { it.type == "system" }.payload.jsonObject
            assertEquals("warn", first["level"]!!.jsonPrimitive.content)
            assertTrue(first["text"]!!.jsonPrimitive.content.contains(c.reason))
            assertTrue(w.launcher.byRole("builder").last().written.first().contains("Sandbox: none for this run"))
        }
        assertTrue(rt.started.isEmpty() && rt.execs.isEmpty(), rt.calls.toString())
    }

    @Test
    fun `with sandboxes switched off there are no tools, no notes and the decision is disabled`() {
        val w = TestWorld().also { worlds += it }
        w.sandboxFile(definition)
        var tools = emptyList<String>()
        w.launcher.stage = { p ->
            if (p.spec.kind == "builder") tools = toolNames(w, token(p))
            p.init()
            when (p.spec.kind) {
                "approver" -> p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") })
                "spec-writer", "builder" -> p.result("ok")
                else -> p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
            }
            p.exit(0)
        }
        val run = start(w)
        val done = w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status)
        assertEquals(McpRegistry.STAGE_TOOLS.sorted(), tools.sorted())
        assertEquals("disabled", w.services.runs.toDto(done).sandbox!!.state)
        assertTrue(systemTexts(w, run.id).none { it.startsWith("Sandbox") })
        assertFalse(w.launcher.byRole("builder").single().written.first().contains("Sandbox:"))
    }

    @Test
    fun `only the builder gets the sandbox tools, reviewers cannot run anything in it`() {
        val w = world()
        w.sandboxFile(definition)
        approve(w)
        val toolsByRole = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
        val reviewerExec = CopyOnWriteArrayList<ToolAnswer>()
        w.launcher.stage = { p ->
            val role = p.spec.kind
            toolsByRole[role] = toolNames(w, token(p))
            if (role != "builder") reviewerExec += exec(w, p, "touch /workspace/pwned")
            p.init()
            when (role) {
                "approver" -> p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") })
                "spec-writer", "builder" -> p.result("ok")
                else -> p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
            }
            p.exit(0)
        }
        val run = start(w)
        assertEquals(RunStatus.MERGED, w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        assertTrue(toolsByRole["builder"]!!.containsAll(SandboxManager.TOOLS))
        for (role in listOf("spec-writer", "security", "ux", "ui-design", "code", "approver")) {
            assertTrue(toolsByRole[role]!!.none { it.startsWith("factory_sandbox") }, "$role: ${toolsByRole[role]}")
        }
        assertEquals(6, reviewerExec.size)
        assertTrue(reviewerExec.all { it.isError && it.text.contains("not available to this agent") })
        assertTrue(w.sandboxRuntime.execs.isEmpty() && w.sandboxRuntime.started.isEmpty())
    }

    @Test
    fun `an agent can only reach its own run's sandbox`() {
        val w = world()
        w.sandboxFile(definition)
        approve(w)
        val bothRunning = CountDownLatch(2)
        script(w) { p ->
            exec(w, p, "echo from ${p.spec.sessionId}")
            bothRunning.countDown()
            bothRunning.await(10, TimeUnit.SECONDS)
            "ok"
        }
        val a = start(w, 1)
        val b = start(w, 2)
        for (r in listOf(a, b)) assertEquals(RunStatus.MERGED, w.awaitRun(r.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        val builders = listOf(a, b).associate { r -> r.id to w.services.runs.stages(r.id).single { it.role == "builder" }.sessionId!! }
        for ((runId, session) in builders) {
            val mine = w.sandboxRuntime.execs.filter { it.second == "echo from $session" }
            assertEquals(listOf("agern-run-$runId"), mine.map { it.first })
        }
        // A token without a run (a chat), even if it were granted the tools, has no sandbox; dead tokens are refused.
        val alice = w.user("alice")
        val chat = w.services.agents.createChat(alice, "demo-app", null)
        val chatToken = w.services.mcpRegistry.issue(McpGrant(chat.id, alice.id, "demo-app", null, McpRegistry.CHAT_TOOLS + SandboxManager.TOOLS))
        val answer = call(w, chatToken, "factory_sandbox_exec", buildJsonObject { put("command", "id") })
        assertTrue(answer.isError && answer.text.contains("chats have none"), answer.text)
        val dead = w.launcher.byRole("builder").first()
        assertEquals(401, runBlocking { w.services.mcp.handle(token(dead), """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""") }.first)
    }

    @Test
    fun `the concurrency cap answers busy and frees the slot when a run ends`() {
        val w = world { it.copy(sandbox = it.sandbox.copy(maxConcurrent = 1)) }
        w.sandboxFile(definition)
        approve(w)
        val aHasSandbox = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val retryB = CountDownLatch(1)
        val bAnswers = CopyOnWriteArrayList<ToolAnswer>()
        script(w) { p ->
            if (p.spec.prompt!!.contains("Implement issue #1 ")) {
                exec(w, p, "build a")
                aHasSandbox.countDown()
                releaseA.await(10, TimeUnit.SECONDS)
            } else {
                bAnswers += exec(w, p, "build b")
                releaseA.countDown()
                retryB.await(10, TimeUnit.SECONDS)
                bAnswers += exec(w, p, "build b again")
            }
            "ok"
        }
        val a = start(w, 1)
        assertTrue(aHasSandbox.await(10, TimeUnit.SECONDS))
        assertEquals(1, w.services.sandboxes.inUse())
        val b = start(w, 2)
        assertEquals(RunStatus.MERGED, w.awaitRun(a.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        waitFor { bAnswers.size == 1 }
        assertTrue(bAnswers[0].isError && bAnswers[0].text.contains("all in use"), bAnswers[0].text)
        retryB.countDown()
        assertEquals(RunStatus.MERGED, w.awaitRun(b.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        assertFalse(bAnswers[1].isError, bAnswers[1].text)
        assertEquals(listOf("build a", "build b again"), w.sandboxRuntime.execs.map { it.second }.filter { it.startsWith("build") })
        assertEquals(0, w.services.sandboxes.inUse())
    }

    @Test
    fun `the sandbox is removed when the run fails, is cancelled or times out`() {
        // Failure: the builder exits without a result after using the sandbox.
        val w = world()
        w.sandboxFile(definition)
        approve(w)
        w.launcher.stage = { p ->
            p.init()
            when (p.spec.kind) {
                "spec-writer" -> { p.result("spec"); p.exit(0) }
                "builder" -> {
                    exec(w, p, "make")
                    if (p.spec.prompt.orEmpty().contains("<issue_body>\nhang\n</issue_body>")) {
                        while (!p.killed) Thread.sleep(10)
                    } else p.exit(1)
                }
                else -> { p.result("x"); p.exit(0) }
            }
        }
        val failed = start(w, 1)
        val f = w.awaitRun(failed.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.FAILED, f.status)
        assertTrue(w.sandboxRuntime.calls.contains("rm agern-run-${failed.id} agern-run-${failed.id}-postgres"), w.sandboxRuntime.calls.toString())

        // Cancel while the builder works.
        w.github.repos["demo-app"]!!.issues.replaceAll { if (it.number == 2) it.copy(body = "hang") else it }
        val cancelled = start(w, 2)
        waitFor { w.sandboxRuntime.containers.containsKey("agern-run-${cancelled.id}") && w.sandboxRuntime.execs.any { it.first == "agern-run-${cancelled.id}" } }
        runBlocking { w.services.orchestrator.cancel(w.user("alice"), cancelled.id) }
        assertEquals(RunStatus.CANCELLED, w.services.runs.get(cancelled.id)!!.status)
        assertFalse(w.sandboxRuntime.containers.containsKey("agern-run-${cancelled.id}"))
        assertFalse(w.sandboxRuntime.networks.containsKey("agern-run-${cancelled.id}"))
        assertEquals(0, w.services.sandboxes.inUse())

        // Stage time limit: the builder hangs after using the sandbox; the exec limit is capped by the stage limit.
        val t = world { it.copy(stageTimeoutMs = 1500) }
        t.sandboxFile(definition)
        approve(t)
        t.launcher.stage = { p ->
            p.init()
            when (p.spec.kind) {
                "spec-writer" -> { p.result("spec"); p.exit(0) }
                "builder" -> { exec(t, p, "make"); while (!p.killed) Thread.sleep(10) }
                else -> { p.result("x"); p.exit(0) }
            }
        }
        val timedOut = start(t, 1)
        val to = t.awaitRun(timedOut.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.FAILED, to.status)
        assertTrue(to.error!!.contains("Timed out"), to.error)
        assertTrue(t.sandboxRuntime.containers.isEmpty() && t.sandboxRuntime.networks.isEmpty(), t.sandboxRuntime.calls.toString())
        assertEquals(1500L, t.sandboxRuntime.execs.first { it.second == "make" }.third)
    }

    @Test
    fun `a sandbox that cannot be created or set up is removed and the slot freed`() {
        val w = world()
        w.sandboxFile(definition)
        approve(w)
        val answers = CopyOnWriteArrayList<ToolAnswer>()
        script(w) { p ->
            answers += exec(w, p, "make")           // the sidecar's image cannot be pulled
            w.sandboxRuntime.failStart = null
            w.sandboxRuntime.onExec = { _, cmd, _ -> if (cmd.startsWith("echo setting up")) ExecResult(2, "E: unable to locate package", false, false, 3) else ExecResult(0, "", false, false, 1) }
            answers += exec(w, p, "make")           // setup fails
            w.sandboxRuntime.onExec = { _, _, _ -> ExecResult(0, "fine", false, false, 1) }
            answers += exec(w, p, "make")           // a later call tries again and works
            "ok"
        }
        w.sandboxRuntime.failStart = "postgres"
        val run = start(w)
        assertEquals(RunStatus.MERGED, w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        assertTrue(answers[0].isError && answers[0].text.contains("Could not create the sandbox: image pull failed"), answers[0].text)
        assertTrue(answers[1].isError && answers[1].text.contains("setup script failed (exit 2)") && answers[1].text.contains("unable to locate package"), answers[1].text)
        assertFalse(answers[2].isError, answers[2].text)
        assertEquals(3, w.sandboxRuntime.calls.count { it == "network rm agern-run-${run.id}" })
        assertEquals(0, w.services.sandboxes.inUse())
    }

    @Test
    fun `at start leftovers of runs that are not active are removed and active ones kept`() {
        val w = world()
        val alice = w.user("alice")
        val active = w.services.runs.createIfNoActive("demo-app", 1, "x", "task", 2, alice.id, null)!!
        w.services.runs.setStatus(active.id, RunStatus.NEEDS_HUMAN)
        val finished = w.services.runs.createIfNoActive("demo-app", 2, "y", "task", 2, alice.id, null)!!
        w.services.runs.setStatus(finished.id, RunStatus.MERGED)
        val inProgress = w.services.runs.createIfNoActive("demo-app", 3, "z", "task", 2, alice.id, null)!!
        w.services.runs.setStatus(inProgress.id, RunStatus.BUILDING)   // failed by the restart recovery, so a leftover
        val orphan = UUID.randomUUID()
        val rt = w.sandboxRuntime
        for (id in listOf(active.id, finished.id, inProgress.id, orphan)) {
            rt.containers["agern-run-$id"] = id.toString()
            rt.containers["agern-run-$id-postgres"] = id.toString()
            rt.networks["agern-run-$id"] = id.toString()
        }
        rt.containers["agern-run-junk"] = "not-a-run-id"
        w.services.start()
        runBlocking { withTimeout(10_000) { w.services.sandboxReaper!!.join() } }
        assertEquals(setOf("agern-run-${active.id}", "agern-run-${active.id}-postgres"), rt.containers.keys)
        assertEquals(setOf("agern-run-${active.id}"), rt.networks.keys)
        assertEquals(RunStatus.FAILED, w.services.runs.get(inProgress.id)!!.status)
    }
}
