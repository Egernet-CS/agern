package dk.egernet.factory

import dk.egernet.factory.mcp.McpGrant
import dk.egernet.factory.mcp.McpRegistry
import dk.egernet.factory.sandbox.ExecResult
import dk.egernet.factory.sandbox.SandboxDefinitions
import dk.egernet.factory.sandbox.SandboxManager
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
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

    @AfterTest fun tearDown() = worlds.forEach { it.close() }

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

    private fun execBg(w: TestWorld, p: FakeProcess, command: String, timeoutSeconds: Long? = null) =
        call(w, token(p), "factory_sandbox_exec", buildJsonObject {
            put("command", command); put("background", true); timeoutSeconds?.let { put("timeoutSeconds", it) }
        })

    private fun wait(w: TestWorld, p: FakeProcess, handle: String? = null, waitSeconds: Long? = null) =
        call(w, token(p), "factory_sandbox_wait", buildJsonObject { handle?.let { put("handle", it) }; waitSeconds?.let { put("waitSeconds", it) } })

    private fun ToolAnswer.str(k: String) = json[k]!!.jsonPrimitive.content

    private fun toolNames(w: TestWorld, token: String): List<String> =
        AppJson.parseToJsonElement(runBlocking { w.services.mcp.handle(token, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""") }.second!!)
            .jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    /**
     * Every stage passes unless [changes] says otherwise for (role, round); [builder] is the builder's work and returns its
     * summary, [reviewer] and [approver] run inside those stages before they answer.
     */
    private fun script(
        w: TestWorld, reviewer: (FakeProcess) -> Unit = {}, approver: (FakeProcess) -> Unit = {},
        changes: (String, Int) -> Boolean = { _, _ -> false }, builder: (FakeProcess) -> String = { "Built it." },
    ) {
        w.launcher.stage = { p ->
            when (val role = p.spec.kind) {
                "spec-writer" -> { p.init(); p.result("## Spec\nDo it."); p.exit(0) }
                "builder" -> { p.init(); val text = builder(p); p.result(text); p.exit(0) }
                "approver" -> { approver(p); p.init(); p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") }); p.exit(0) }
                else -> {
                    reviewer(p)
                    p.init()
                    val c = changes(role, round(p))
                    p.result(null, buildJsonObject {
                        put("verdict", if (c) "CHANGES" else "PASS"); put("summary", "$role ok")
                        put("findings", buildJsonArray {
                            if (c) addJsonObject { put("severity", "major"); put("file", "a.kt"); put("detail", "fix it") }
                        })
                    })
                    p.exit(0)
                }
            }
        }
    }

    /** The review or fix round a stage's prompt is for. */
    private fun round(p: FakeProcess) = Regex("fix round (\\d+)").find(p.spec.prompt ?: "")?.groupValues?.get(1)?.toInt() ?: 0

    private fun start(w: TestWorld, issue: Int = 1) = runBlocking { w.services.orchestrator.start(w.user("alice"), "demo-app", issue, null) }

    private fun builderEvents(w: TestWorld, runId: UUID) =
        w.services.runs.stages(runId).filter { it.role == "builder" }.flatMap { st -> w.services.events.list(st.sessionId!!, 0, 1000) }

    private fun systemTexts(w: TestWorld, runId: UUID) =
        builderEvents(w, runId).filter { it.type == "system" }.map { it.payload.jsonObject["text"]!!.jsonPrimitive.content }

    private fun stageEvents(w: TestWorld, runId: UUID, role: String, round: Int = 0) =
        w.services.runs.stages(runId).filter { it.role == role && it.round == round }.flatMap { st -> w.services.events.list(st.sessionId!!, 0, 1000) }

    private fun stageTexts(w: TestWorld, runId: UUID, role: String, round: Int = 0) =
        stageEvents(w, runId, role, round).filter { it.type == "system" }.map { it.payload.jsonObject["text"]!!.jsonPrimitive.content }

    private fun reviewNames(w: TestWorld) = w.sandboxRuntime.containers.keys.filter { it.startsWith("agern-review-") } +
        w.sandboxRuntime.networks.keys.filter { it.startsWith("agern-review-") }

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
        // The run's project files snapshot, read-only (docs/ARCHITECTURE.md §5 "Project files"), then the caches.
        val snapshot = w.services.projectFiles.snapshotDir("demo-app", id).toAbsolutePath().normalize().toString()
        assertEquals(SandboxMountView(snapshot, "/project-files", false, true), main.mounts[1].view())
        assertEquals(listOf("/root/.gradle"), main.mounts.drop(2).map { it.target })
        assertTrue(main.mounts.drop(2).all { it.volume && it.source.startsWith("agern-cache-demo-app-") })
        assertEquals("/workspace", main.workdir)
        assertEquals(mapOf("agern.run" to id.toString(), "agern.repo" to "demo-app", "agern.role" to "sandbox"), main.labels)
        assertEquals(mapOf("CI" to "true", "AGERN_PROJECT_FILES" to "/project-files"), main.env)
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
        assertEquals((McpRegistry.STAGE_TOOLS + dk.egernet.factory.images.ImageService.TOOLS).sorted(), tools.sorted(), "no sandbox tools; the image tool as for every builder")
        assertEquals("disabled", w.services.runs.toDto(done).sandbox!!.state)
        assertTrue(systemTexts(w, run.id).none { it.startsWith("Sandbox") })
        assertFalse(w.launcher.byRole("builder").single().written.first().contains("Sandbox:"))
    }

    @Test
    fun `each stage gets only the sandbox tools of its scope`() {
        val w = world()
        w.sandboxFile(definition)
        approve(w)
        val toolsByRole = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
        val refused = java.util.concurrent.ConcurrentHashMap<String, List<ToolAnswer>>()
        var specInfo: JsonObject? = null
        var startedDuringSpec = -1
        w.launcher.stage = { p ->
            val role = p.spec.kind
            toolsByRole[role] = toolNames(w, token(p))
            when (role) {
                "spec-writer" -> {
                    specInfo = call(w, token(p), "factory_sandbox_info").json
                    refused[role] = listOf(exec(w, p, "touch /workspace/pwned"), wait(w, p))
                    startedDuringSpec = w.sandboxRuntime.started.size
                }
                "security", "code", "approver" -> refused[role] = listOf(exec(w, p, "touch /workspace/pwned"), call(w, token(p), "factory_sandbox_info"))
            }
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
        fun sandboxTools(role: String) = toolsByRole[role]!!.filter { it.startsWith("factory_sandbox") }.toSet()
        assertEquals(setOf("factory_sandbox_info"), sandboxTools("spec-writer"))
        for (role in listOf("builder", "ux", "ui-design")) assertEquals(SandboxManager.TOOLS, sandboxTools(role), role)
        for (role in listOf("security", "code", "approver")) assertTrue(sandboxTools(role).isEmpty(), "$role: ${toolsByRole[role]}")

        // The spec-writer plans: info says so and creates nothing; exec and wait are refused.
        assertEquals("plan", specInfo!!["access"]!!.jsonPrimitive.content)
        assertEquals(true, specInfo!!["available"]!!.jsonPrimitive.booleanOrNull)
        assertTrue(specInfo!!["note"]!!.jsonPrimitive.content.contains("cannot run commands"))
        assertEquals(0, startedDuringSpec)
        // Not in its grant; and even a hand-built plan-scoped grant with every sandbox tool runs nothing.
        assertTrue(refused["spec-writer"]!!.all { it.isError && it.text.contains("not available to this agent") }, refused["spec-writer"].toString())
        val specSession = w.services.runs.stages(run.id).single { it.role == "spec-writer" }.sessionId!!
        val planToken = w.services.mcpRegistry.issue(McpGrant(specSession, w.user("alice").id, "demo-app", run.id, SandboxManager.TOOLS,
            sandboxScope = dk.egernet.factory.mcp.SandboxScope.PLAN))
        for (answer in listOf(call(w, planToken, "factory_sandbox_exec", buildJsonObject { put("command", "id") }), call(w, planToken, "factory_sandbox_wait"))) {
            assertTrue(answer.isError && answer.text.contains("cannot run commands"), answer.text)
        }
        for (role in listOf("security", "code", "approver")) {
            assertTrue(refused[role]!!.all { it.isError && it.text.contains("not available to this agent") }, "$role: ${refused[role]}")
        }
        assertTrue(w.sandboxRuntime.started.isEmpty() && w.sandboxRuntime.execs.isEmpty())

        // No tool of any stage, chat or personal token can create, change, approve or revoke a definition or approval.
        val everyTool = toolsByRole.values.flatten() + McpRegistry.CHAT_TOOLS + McpRegistry.PERSONAL_TOOLS
        assertEquals(SandboxManager.TOOLS, everyTool.filter { "sandbox" in it }.toSet())
        assertTrue(everyTool.none { "approv" in it && "sandbox" in it })

        // Prompts and transcripts.
        val specPrompt = w.launcher.byRole("spec-writer").single().spec.prompt!!
        assertTrue(specPrompt.contains("Sandbox: this run has one (image docker.io/library/eclipse-temurin:21-jdk). You cannot run commands in it; " +
            "call factory_sandbox_info"), specPrompt)
        assertTrue(stageTexts(w, run.id, "spec-writer").first().startsWith("Sandbox: Sandbox definition"))
        assertEquals("info", stageEvents(w, run.id, "spec-writer").first { it.type == "system" }.payload.jsonObject["level"]!!.jsonPrimitive.content)
        assertTrue(w.launcher.byRole("builder").single().spec.prompt!!.contains("When the spec has a Verification section"))
        for (role in listOf("ux", "ui-design")) {
            assertTrue(w.launcher.byRole(role).single().spec.prompt!!.contains("Review sandbox: this run has a read-only review sandbox"), role)
        }
        for (role in listOf("security", "code")) assertFalse(w.launcher.byRole(role).single().spec.prompt!!.contains("Review sandbox"), role)
    }

    @Test
    fun `without an approved definition only the spec-writer changes, and with sandboxes off nothing does`() {
        // Not approved: the spec-writer is told there is none; builder and reviewers are as before.
        val w = world()
        w.sandboxFile(definition)
        val tools = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
        var specInfo: JsonObject? = null
        script(w, reviewer = { p -> tools[p.spec.kind] = toolNames(w, token(p)) })
        val inner = w.launcher.stage
        w.launcher.stage = { p ->
            if (p.spec.kind == "spec-writer") {
                tools["spec-writer"] = toolNames(w, token(p))
                specInfo = call(w, token(p), "factory_sandbox_info").json
            }
            inner(p)
        }
        val run = start(w)
        assertEquals(RunStatus.MERGED, w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        assertEquals(listOf("factory_sandbox_info"), tools["spec-writer"]!!.filter { it.startsWith("factory_sandbox") })
        assertEquals(false, specInfo!!["available"]!!.jsonPrimitive.booleanOrNull)
        assertEquals("unapproved", specInfo!!["state"]!!.jsonPrimitive.content)
        for (role in REVIEWER_ROLES) assertTrue(tools[role]!!.none { it.startsWith("factory_sandbox") }, role)
        val specPrompt = w.launcher.byRole("spec-writer").single().spec.prompt!!
        assertTrue(specPrompt.contains("Sandbox: none for this run ("), specPrompt)
        assertTrue(specPrompt.contains("Do not ask for one"), specPrompt)
        val note = stageEvents(w, run.id, "spec-writer").first { it.type == "system" }.payload.jsonObject
        assertEquals("warn", note["level"]!!.jsonPrimitive.content)
        assertTrue(note["text"]!!.jsonPrimitive.content.startsWith("Sandbox: .agern/sandbox.yml"))
        for (role in SCREENSHOT_ROLES) assertFalse(w.launcher.byRole(role).single().spec.prompt!!.contains("Review sandbox"), role)
        assertTrue(w.sandboxRuntime.started.isEmpty())

        // Off: no tool, note or paragraph for any stage.
        val off = TestWorld().also { worlds += it }
        off.sandboxFile(definition)
        val offTools = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
        script(off, reviewer = { p -> offTools[p.spec.kind] = toolNames(off, token(p)) })
        val offInner = off.launcher.stage
        off.launcher.stage = { p -> offTools[p.spec.kind] = toolNames(off, token(p)); offInner(p) }
        val offRun = runBlocking { off.services.orchestrator.start(off.user("alice"), "demo-app", 1, null) }
        assertEquals(RunStatus.MERGED, off.awaitRun(offRun.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        assertTrue(offTools.values.flatten().none { it.startsWith("factory_sandbox") }, offTools.toString())
        for (role in listOf("spec-writer", "builder") + REVIEWER_ROLES) {
            val prompt = off.launcher.byRole(role).single().spec.prompt!!
            assertFalse(prompt.contains("Sandbox") || prompt.contains("sandbox"), "$role: $prompt")
            assertTrue(stageTexts(off, offRun.id, role).none { it.contains("andbox") }, role)
        }
        assertTrue(off.launcher.byRole("spec-writer").single().spec.prompt!!.contains("Explore it (read-only) before writing.\nYour final message"))
    }

    @Test
    fun `the ux and ui-design reviewers share one read-only review sandbox, removed when the round ends`() {
        val w = world()
        w.sandboxFile(definition)
        approve(w)
        val rt = w.sandboxRuntime
        var buildHandle = ""
        val bothAsked = CountDownLatch(2)
        val answers = java.util.concurrent.ConcurrentHashMap<String, ToolAnswer>()
        val infos = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()
        val foreign = java.util.concurrent.ConcurrentHashMap<String, ToolAnswer>()
        var platformRefusal: ToolAnswer? = null
        var outputDirDuringReview = false
        var reviewLeftAtApprover: List<String>? = null
        var outputDirAtApprover = true
        var outputDir: java.nio.file.Path? = null
        script(w, reviewer = { p ->
            val role = p.spec.kind
            if (role in SCREENSHOT_ROLES) {
                infos[role] = call(w, token(p), "factory_sandbox_info").json
                bothAsked.countDown()
                bothAsked.await(10, TimeUnit.SECONDS)
                answers[role] = exec(w, p, "echo $role")
                foreign[role] = wait(w, p, buildHandle, 0)
                if (role == "ux") {
                    platformRefusal = call(w, token(p), "factory_sandbox_exec", buildJsonObject { put("command", "id"); put("platform", "jdk17") })
                    outputDir = p.spec.workDir.resolve(".agern-run/review")
                    outputDirDuringReview = java.nio.file.Files.isDirectory(outputDir!!)
                }
            }
        }, approver = {
            reviewLeftAtApprover = reviewNames(w)
            outputDirAtApprover = java.nio.file.Files.exists(outputDir!!)
        }) { p -> buildHandle = exec(w, p, "make").str("handle"); "ok" }
        val run = start(w)
        val done = w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        val id = run.id

        // Created once, by the first reviewer command, beside the build sandbox: own network, services, names and labels.
        assertEquals(listOf("agern-run-$id-postgres", "agern-run-$id", "agern-review-$id-postgres", "agern-review-$id"), rt.started.map { it.name })
        assertTrue(rt.calls.contains("network create agern-review-$id"), rt.calls.toString())
        val svc = rt.started[2]
        assertEquals("agern-review-$id", svc.network)
        assertEquals("postgres", svc.networkAlias)
        assertEquals("review-service", svc.labels["agern.role"])
        val review = rt.started[3]
        assertEquals(mapOf("agern.run" to id.toString(), "agern.repo" to "demo-app", "agern.role" to "review"), review.labels)
        assertEquals("agern-review-$id", review.network)
        assertEquals("/workspace", review.workdir)
        assertEquals(mapOf("CI" to "true", "AGERN_PROJECT_FILES" to "/project-files"), review.env)
        val runDir = w.services.workspaces.runDir("alice", "demo-app", id).toAbsolutePath().normalize()
        assertEquals(SandboxMountView(runDir.toString(), "/workspace", false, true), review.mounts[0].view())
        val snapshot = w.services.projectFiles.snapshotDir("demo-app", id).toAbsolutePath().normalize().toString()
        assertEquals(SandboxMountView(snapshot, "/project-files", false, true), review.mounts[1].view())
        assertEquals(SandboxMountView(runDir.resolve(".agern-run/review").toString(), "/run/agern-review", false, false), review.mounts[2].view())
        assertEquals(rt.started[1].mounts.last(), review.mounts.last(), "the same default cache volume as the build sandbox")
        assertTrue(rt.started[1].mounts.none { it.readOnly && it.target != "/project-files" }, "the build sandbox stays read-write")

        // Commands: the reviewers' only in the review container, the builder's only in the build container.
        assertEquals(setOf("agern-review-$id"), rt.execs.filter { it.second.startsWith("echo ux") || it.second.startsWith("echo ui-design") }.map { it.first }.toSet())
        assertEquals(listOf("agern-run-$id"), rt.execs.filter { it.second == "make" }.map { it.first })
        assertEquals(listOf("agern-run-$id", "agern-review-$id"), rt.execs.filter { it.second == "echo setting up\n" }.map { it.first })
        for (role in SCREENSHOT_ROLES) {
            assertFalse(answers[role]!!.isError, answers[role]!!.text)
            assertTrue(foreign[role]!!.isError && foreign[role]!!.text.startsWith("Unknown handle 'cmd-"), foreign[role]!!.text)
            val info = infos[role]!!
            assertEquals("review", info["access"]!!.jsonPrimitive.content)
            assertEquals(true, info["readOnly"]!!.jsonPrimitive.booleanOrNull)
            assertEquals(false, info["created"]!!.jsonPrimitive.booleanOrNull)
            assertEquals("/run/agern-review", info["outputDir"]!!.jsonObject["container"]!!.jsonPrimitive.content)
            assertEquals(".agern-run/review", info["outputDir"]!!.jsonObject["host"]!!.jsonPrimitive.content)
            assertFalse(info.containsKey("platforms"))
        }
        assertTrue(platformRefusal!!.isError && platformRefusal!!.text == "The review sandbox has only the default image; leave platform out.", platformRefusal!!.text)
        assertTrue(outputDirDuringReview)

        // Logs: each command in its reviewer's transcript, marked as review; lifecycle and setup in the creator's.
        val ux = stageEvents(w, id, "ux").mapNotNull { it.payload.jsonObject["sandbox"]?.jsonObject }.filter { it["kind"]!!.jsonPrimitive.content == "exec" }
        assertEquals(listOf("echo ux"), ux.map { it["command"]!!.jsonPrimitive.content })
        assertEquals(true, ux.single()["review"]!!.jsonPrimitive.booleanOrNull)
        assertTrue(stageTexts(w, id, "ux").any { it.startsWith("Review sandbox `echo ux`: exit 0") }, stageTexts(w, id, "ux").toString())
        val both = stageTexts(w, id, "ux") + stageTexts(w, id, "ui-design")
        assertEquals(1, both.count { it.startsWith("Creating the review sandbox: image docker.io/library/eclipse-temurin:21-jdk, services postgres") }, both.toString())
        assertEquals(1, both.count { it.startsWith("Review sandbox ready in") }, both.toString())
        assertEquals(1, both.count { it.startsWith("Review sandbox setup script: exit 0") }, both.toString())
        assertTrue(systemTexts(w, id).none { it.contains("Review sandbox") })

        // Removed when the round ended, before the approver, with its output directory; the run records its use.
        assertEquals(emptyList(), reviewLeftAtApprover)
        assertFalse(outputDirAtApprover)
        assertTrue(rt.calls.contains("rm agern-review-$id agern-review-$id-postgres"), rt.calls.toString())
        assertTrue(rt.calls.contains("network rm agern-review-$id"), rt.calls.toString())
        assertTrue(rt.containers.isEmpty() && rt.networks.isEmpty(), rt.calls.toString())
        assertEquals(0, w.services.sandboxes.inUse())
        assertEquals(true, w.services.runs.toDto(done).sandbox!!.reviewUsed)
    }

    private data class SandboxMountView(val source: String, val target: String, val volume: Boolean, val readOnly: Boolean)
    private fun dk.egernet.factory.sandbox.SandboxMount.view() = SandboxMountView(source, target, volume, readOnly)

    @Test
    fun `each review round gets a fresh review sandbox, gone before the next builder round`() {
        val w = world()
        w.sandboxFile(definition)
        approve(w)
        val atBuilder = CopyOnWriteArrayList<List<String>>()
        script(w, reviewer = { p -> if (p.spec.kind == "ux") exec(w, p, "echo round ${round(p)}") }, changes = { role, r -> role == "ux" && r == 0 }) { _ ->
            atBuilder += reviewNames(w)
            "ok"
        }
        val run = start(w)
        assertEquals(RunStatus.MERGED, w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        assertEquals(listOf(emptyList(), emptyList()), atBuilder)
        assertEquals(2, w.sandboxRuntime.calls.count { it == "start agern-review-${run.id}" })
        assertEquals(listOf("echo round 0", "echo round 1"), w.sandboxRuntime.execs.filter { it.first == "agern-review-${run.id}" && it.second.startsWith("echo round") }.map { it.second })
        assertTrue(reviewNames(w).isEmpty())
        assertEquals(0, w.services.sandboxes.inUse())
    }

    @Test
    fun `the review sandbox is removed when a reviewer fails, the run is cancelled or a stage times out`() {
        // A reviewer fails after using it.
        val w = world()
        w.sandboxFile(definition)
        approve(w)
        w.launcher.stage = { p ->
            p.init()
            when (p.spec.kind) {
                "spec-writer", "builder" -> { p.result("ok"); p.exit(0) }
                "ux" -> {
                    exec(w, p, "echo ux")
                    if (p.spec.prompt.orEmpty().contains("<issue_body>\nhang\n</issue_body>")) {
                        while (!p.killed) Thread.sleep(10)
                    } else p.exit(1)
                }
                else -> { p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) }); p.exit(0) }
            }
        }
        val failed = start(w, 1)
        assertEquals(RunStatus.FAILED, w.awaitRun(failed.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        assertTrue(w.sandboxRuntime.calls.contains("start agern-review-${failed.id}"))
        assertTrue(reviewNames(w).isEmpty(), w.sandboxRuntime.calls.toString())
        assertEquals(0, w.services.sandboxes.inUse())

        // Cancelled while the reviewer works.
        w.github.repos["demo-app"]!!.issues.replaceAll { if (it.number == 2) it.copy(body = "hang") else it }
        val cancelled = start(w, 2)
        waitFor { w.sandboxRuntime.execs.any { it.first == "agern-review-${cancelled.id}" && it.second == "echo ux" } }
        runBlocking { w.services.orchestrator.cancel(w.user("alice"), cancelled.id) }
        assertEquals(RunStatus.CANCELLED, w.services.runs.get(cancelled.id)!!.status)
        assertTrue(reviewNames(w).isEmpty(), w.sandboxRuntime.calls.toString())
        assertEquals(0, w.services.sandboxes.inUse())

        // A reviewer's stage time limit.
        val t = world { it.copy(stageTimeoutMs = 1500) }
        t.sandboxFile(definition)
        approve(t)
        t.launcher.stage = { p ->
            p.init()
            when (p.spec.kind) {
                "spec-writer", "builder" -> { p.result("ok"); p.exit(0) }
                "ux" -> { exec(t, p, "echo ux"); while (!p.killed) Thread.sleep(10) }
                else -> { p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) }); p.exit(0) }
            }
        }
        val timedOut = start(t, 1)
        assertEquals(RunStatus.FAILED, t.awaitRun(timedOut.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        assertTrue(t.sandboxRuntime.calls.contains("start agern-review-${timedOut.id}"))
        assertTrue(t.sandboxRuntime.containers.isEmpty() && t.sandboxRuntime.networks.isEmpty(), t.sandboxRuntime.calls.toString())
        assertEquals(0, t.services.sandboxes.inUse())
    }

    @Test
    fun `no review sandbox survives the run's release, also when the release races its creation`() {
        val w = world()
        w.sandboxFile(definition)
        approve(w)
        val rt = w.sandboxRuntime
        val answers = CopyOnWriteArrayList<ToolAnswer>()
        script(w, reviewer = { p ->
            if (p.spec.kind == "ux") {
                rt.onStart = { spec ->
                    if (spec.name.startsWith("agern-review-") && spec.labels["agern.role"] == "review") {
                        runBlocking { w.services.sandboxes.release(UUID.fromString(spec.labels.getValue("agern.run"))) }
                    }
                }
                answers += exec(w, p, "echo racing")
                rt.onStart = {}
                answers += exec(w, p, "echo after the release")
                answers += wait(w, p)
            }
        })
        val run = start(w)
        assertEquals(RunStatus.MERGED, w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        assertTrue(answers[0].isError && answers[0].text.startsWith("The review round ended while its review sandbox was being created"), answers[0].text)
        assertTrue(answers[1].isError, answers[1].text)
        assertTrue(answers[2].isError, answers[2].text)
        assertEquals(1, rt.calls.count { it == "start agern-review-${run.id}" })
        assertTrue(rt.execs.none { it.first == "agern-review-${run.id}" && it.second.startsWith("echo") && it.second != "echo setting up\n" })
        assertTrue(rt.containers.isEmpty() && rt.networks.isEmpty(), rt.calls.toString())
        assertEquals(0, w.services.sandboxes.inUse())
    }

    @Test
    fun `at start leftover review sandboxes are removed, also of an active run`() {
        val w = world()
        val alice = w.user("alice")
        val active = w.services.runs.createIfNoActive("demo-app", 1, "x", "task", 2, alice.id, null)!!
        w.services.runs.setStatus(active.id, RunStatus.NEEDS_HUMAN)
        val rt = w.sandboxRuntime
        rt.containers["agern-run-${active.id}"] = active.id.toString()
        rt.networks["agern-run-${active.id}"] = active.id.toString()
        rt.containers["agern-review-${active.id}"] = active.id.toString()
        rt.containers["agern-review-${active.id}-postgres"] = active.id.toString()
        rt.networks["agern-review-${active.id}"] = active.id.toString()
        w.services.start()
        assertEquals(setOf("agern-run-${active.id}"), rt.containers.keys)
        assertEquals(setOf("agern-run-${active.id}"), rt.networks.keys)
    }

    @Test
    fun `without a free slot a reviewer is told at once to review on the host, and the run completes`() {
        val w = world { it.copy(sandbox = it.sandbox.copy(maxConcurrent = 1)) }
        w.sandboxFile(definition)
        approve(w)
        val aHolds = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val answers = CopyOnWriteArrayList<ToolAnswer>()
        var tookMs = -1L
        script(w, reviewer = { p ->
            if (p.spec.kind == "ux" && p.spec.prompt!!.contains("issue #2 ")) {
                val t0 = System.currentTimeMillis()
                answers += exec(w, p, "echo b")
                tookMs = System.currentTimeMillis() - t0
                releaseA.countDown()
            }
        }) { p ->
            if (p.spec.prompt!!.contains("Implement issue #1 ")) {
                exec(w, p, "build a")
                aHolds.countDown()
                releaseA.await(10, TimeUnit.SECONDS)
            }
            "ok"
        }
        val a = start(w, 1)
        assertTrue(aHolds.await(10, TimeUnit.SECONDS))
        val b = start(w, 2)
        for (r in listOf(a, b)) assertEquals(RunStatus.MERGED, w.awaitRun(r.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        assertEquals(
            "The server's sandboxes are all in use (1 of 1 containers). No review sandbox was created and nothing was run. Review without it, " +
                "on the host, as your role instructions describe; do not wait for a slot.",
            answers.single().text,
        )
        assertTrue(answers.single().isError)
        assertTrue(tookMs in 0..5_000, "answered at once: $tookMs ms")
        assertTrue(w.sandboxRuntime.calls.none { it == "start agern-review-${b.id}" })
        assertEquals(0, w.services.sandboxes.inUse())
    }

    @Test
    fun `the review sandbox takes the slot of the run's idle build container, but never of a running command`() {
        // Idle: the builder's container is removed to make room.
        val w = world { it.copy(sandbox = it.sandbox.copy(maxConcurrent = 1)) }
        w.sandboxFile(definition)
        approve(w)
        val answers = CopyOnWriteArrayList<ToolAnswer>()
        var inUseDuringReview = -1
        script(w, reviewer = { p ->
            if (p.spec.kind == "ux") {
                answers += exec(w, p, "echo ux")
                inUseDuringReview = w.services.sandboxes.inUse()
            }
        }) { p -> exec(w, p, "make"); "ok" }
        val run = start(w)
        assertEquals(RunStatus.MERGED, w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        assertFalse(answers.single().isError, answers.single().text)
        assertEquals(1, inUseDuringReview)
        val calls = w.sandboxRuntime.calls
        assertTrue(calls.indexOf("rm agern-run-${run.id}") in 0 until calls.indexOf("start agern-review-${run.id}"), calls.toString())
        assertTrue(stageTexts(w, run.id, "ux").any { it.startsWith("Removed this run's idle `default` container to make room for the review sandbox") })
        assertEquals(0, w.services.sandboxes.inUse())

        // Running: the builder's background command still runs, so nothing is evicted and the reviewer is told.
        val b = world { it.copy(sandbox = it.sandbox.copy(maxConcurrent = 1)) }
        b.sandboxFile(definition)
        approve(b)
        val finish = CountDownLatch(1)
        b.sandboxRuntime.onExec = { _, cmd, _ ->
            if (cmd == "long") finish.await(10, TimeUnit.SECONDS)
            ExecResult(0, "ran: $cmd\n", false, false, 5)
        }
        val bAnswers = CopyOnWriteArrayList<ToolAnswer>()
        script(b, reviewer = { p ->
            if (p.spec.kind == "ux") {
                bAnswers += exec(b, p, "echo ux")
                finish.countDown()
            }
        }) { p -> execBg(b, p, "long"); "ok" }
        val bRun = runBlocking { b.services.orchestrator.start(b.user("alice"), "demo-app", 1, null) }
        assertEquals(RunStatus.MERGED, b.awaitRun(bRun.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        assertTrue(bAnswers.single().isError && bAnswers.single().text.contains("No review sandbox was created"), bAnswers.single().text)
        assertTrue(b.sandboxRuntime.calls.none { it == "rm agern-run-${bRun.id}" || it == "start agern-review-${bRun.id}" }, b.sandboxRuntime.calls.toString())
        assertEquals(0, b.services.sandboxes.inUse())
    }

    @Test
    fun `an admin's stop removes both sandboxes and the overview lists the review container`() {
        val w = world()
        w.sandboxFile(definition)
        approve(w)
        val root = w.user("root", admin = true)
        val runIdRef = java.util.concurrent.atomic.AtomicReference<UUID>()
        var before: RunningSandboxDto? = null
        var reviewOnly: RunningSandboxDto? = null
        var leftAfterStop: Set<String>? = null
        var notFound: Throwable? = null
        script(w, reviewer = { p ->
            if (p.spec.kind == "ux") {
                waitFor { runIdRef.get() != null }
                val runId = runIdRef.get()!!
                exec(w, p, "echo first")
                before = runBlocking { w.services.sandboxes.overview(root) }.sandboxes.single()
                runBlocking { w.services.sandboxes.stop(root, runId) }
                leftAfterStop = w.sandboxRuntime.containers.keys.toSet()
                notFound = runCatching { runBlocking { w.services.sandboxes.stop(root, runId) } }.exceptionOrNull()
                exec(w, p, "echo again")
                reviewOnly = runBlocking { w.services.sandboxes.overview(root) }.sandboxes.single()
            }
        }) { p -> exec(w, p, "make"); "ok" }
        val run = start(w)
        runIdRef.set(run.id)
        assertEquals(RunStatus.MERGED, w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        val b = before!!
        assertEquals(listOf("build" to "default", "review" to "default"), b.containers.map { it.kind to it.platform })
        assertEquals(2, b.slots)
        assertEquals("4g", b.memoryTotal, "1g × (2 command containers + 2 services)")
        assertEquals(emptySet(), leftAfterStop)
        assertTrue(notFound is ApiException && (notFound as ApiException).status.value == 404, notFound.toString())
        assertTrue(systemTexts(w, run.id).any { it.startsWith("An admin (root) stopped this run's sandbox") })
        assertTrue(stageTexts(w, run.id, "ux").any { it.startsWith("An admin (root) stopped this run's review sandbox") })
        // Recreated by the next reviewer command; a run with only a review sandbox is listed.
        assertEquals(listOf("review"), reviewOnly!!.containers.map { it.kind })
        assertEquals(1, reviewOnly!!.slots)
        assertEquals(0, w.services.sandboxes.inUse())
    }

    @Test
    fun `an agent can only reach its own run's sandbox`() {
        val w = world()
        w.sandboxFile(definition)
        approve(w)
        val bothRunning = CountDownLatch(2)
        val handles = java.util.concurrent.ConcurrentHashMap<UUID, String>()
        val foreign = CopyOnWriteArrayList<ToolAnswer>()
        script(w) { p ->
            handles[p.spec.sessionId] = exec(w, p, "echo from ${p.spec.sessionId}").str("handle")
            bothRunning.countDown()
            bothRunning.await(10, TimeUnit.SECONDS)
            // The other run's handle is unknown here: handles are looked up only in the caller's own run.
            foreign += wait(w, p, handles.entries.single { it.key != p.spec.sessionId }.value, 0)
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
        assertEquals(2, foreign.size)
        assertTrue(foreign.all { it.isError && it.text.startsWith("Unknown handle 'cmd-") }, foreign.toString())
        // A token without a run (a chat), even if it were granted the tools, has no sandbox; dead tokens are refused.
        val alice = w.user("alice")
        val chat = w.services.agents.createChat(alice, "demo-app", null)
        val chatToken = w.services.mcpRegistry.issue(McpGrant(chat.id, alice.id, "demo-app", null, McpRegistry.CHAT_TOOLS + SandboxManager.TOOLS))
        val answer = call(w, chatToken, "factory_sandbox_exec", buildJsonObject { put("command", "id") })
        assertTrue(answer.isError && answer.text.contains("chats have none"), answer.text)
        val chatWait = call(w, chatToken, "factory_sandbox_wait")
        assertTrue(chatWait.isError && chatWait.text.contains("chats have none"), chatWait.text)
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
        // The sandbox reaper runs synchronously in start(), before the worktree reaper.
        assertEquals(setOf("agern-run-${active.id}", "agern-run-${active.id}-postgres"), rt.containers.keys)
        assertEquals(setOf("agern-run-${active.id}"), rt.networks.keys)
        assertEquals(RunStatus.FAILED, w.services.runs.get(inProgress.id)!!.status)
    }

    // ---------------------------------------------------------------- platforms, readiness, user (step 4)

    private val platformDefinition = """
        image: docker.io/library/eclipse-temurin:21-jdk
        setup: echo main setup
        user: builder
        services:
          postgres:
            image: docker.io/library/postgres:17
            ready: { command: "pg_isready -h 127.0.0.1", timeoutSeconds: 30 }
        caches: [ /home/builder/.gradle ]
        platforms:
          jdk17:
            image: docker.io/library/eclipse-temurin:17-jdk
          debian12:
            image: docker.io/library/debian:12
            setup: echo debian setup
    """.trimIndent() + "\n"

    @Test
    fun `platforms get their own containers on first use, share network and services, and are removed with the run`() {
        val w = world { it.copy(sandbox = it.sandbox.copy(maxConcurrent = 3)) }
        w.sandboxFile(platformDefinition)
        approve(w, platformDefinition)
        val rt = w.sandboxRuntime
        val answers = mutableListOf<ToolAnswer>()
        var info: JsonObject? = null
        script(w) { p ->
            info = call(w, token(p), "factory_sandbox_info").json
            answers += exec(w, p, "./gradlew test")
            answers += call(w, token(p), "factory_sandbox_exec", buildJsonObject { put("command", "./gradlew test"); put("platform", "jdk17") })
            answers += call(w, token(p), "factory_sandbox_exec", buildJsonObject { put("command", "make check"); put("platform", "debian12") })
            answers += call(w, token(p), "factory_sandbox_exec", buildJsonObject { put("command", "x"); put("platform", "windows") })
            answers += call(w, token(p), "factory_sandbox_exec", buildJsonObject { put("command", "uname"); put("platform", "default") })
            "Tested on every platform."
        }
        val run = start(w)
        val done = w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        val id = run.id

        val platforms = info!!["platforms"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("jdk17", "debian12"), platforms)
        assertEquals("builder", info!!["user"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("checked on creation", info!!["services"]!!.jsonArray[0].jsonObject["readiness"]!!.jsonPrimitive.content)
        assertTrue(answers.take(3).all { !it.isError }, answers.toString())
        assertEquals(listOf("default", "jdk17", "debian12"), answers.take(3).map { it.json["platform"]!!.jsonPrimitive.content })
        assertTrue(answers[3].isError && answers[3].text.contains("Unknown platform 'windows'") && answers[3].text.contains("jdk17"), answers[3].text)
        assertFalse(answers[4].isError, answers[4].text)

        // One network and one sidecar; one container per image, created in the order they were used.
        assertEquals(1, rt.calls.count { it.startsWith("network create") })
        assertEquals(listOf("agern-run-$id-postgres", "agern-run-$id", "agern-run-$id--jdk17", "agern-run-$id--debian12"), rt.started.map { it.name })
        val (main, jdk17, debian) = rt.started.drop(1)
        assertEquals("docker.io/library/eclipse-temurin:17-jdk", jdk17.image)
        assertEquals(mapOf("agern.run" to "$id", "agern.repo" to "demo-app", "agern.role" to "platform", "agern.platform" to "jdk17"), jdk17.labels)
        assertTrue(listOf(main, jdk17, debian).all { it.network == "agern-run-$id" && it.mounts[0].target == "/workspace" && it.keepIdUid == 1000 && it.user == "0:0" })
        // Caches per platform: same path, another volume.
        val volumes = listOf(main, jdk17, debian).map { c -> c.mounts.single { it.volume }.source }
        assertEquals(3, volumes.toSet().size, volumes.toString())
        assertTrue(listOf(main, jdk17, debian).all { c -> c.mounts.single { it.volume }.target == "/home/builder/.gradle" })

        // Setup per container: the platform without `setup:` inherits the top-level one; commands run as the user.
        assertEquals(
            listOf("agern-run-$id" to "echo main setup", "agern-run-$id" to "./gradlew test", "agern-run-$id--jdk17" to "echo main setup",
                "agern-run-$id--jdk17" to "./gradlew test", "agern-run-$id--debian12" to "echo debian setup", "agern-run-$id--debian12" to "make check",
                "agern-run-$id" to "uname"),
            rt.execs.map { it.first to it.second },
        )
        val setups = rt.execs.indices.filter { rt.execs[it].second.endsWith("setup") }
        assertTrue(setups.all { rt.execUsers[it] == null }, "setup runs as root")
        assertTrue(rt.execs.indices.filter { it !in setups }.all { rt.execUsers[it]?.let { u -> u.uid == 1000 && u.name == "builder" && u.home == "/home/builder" } == true })
        assertEquals(3, rt.internal.count { it.startsWith("prepare-user") })
        assertTrue(rt.internal.contains("prepare-user agern-run-$id 1000 builder /home/builder/.gradle"), rt.internal.toString())
        assertEquals(3, rt.internal.count { it.startsWith("hand-over") })
        // The readiness check ran once, before the first container was used (platforms reuse the service).
        assertEquals(listOf("ready agern-run-$id-postgres pg_isready -h 127.0.0.1"), rt.internal.filter { it.startsWith("ready") })

        // Everything is removed at the end, platform containers included; nothing is held.
        assertTrue(rt.containers.isEmpty() && rt.networks.isEmpty(), rt.calls.toString())
        assertTrue(rt.calls.contains("rm agern-run-$id agern-run-$id--jdk17 agern-run-$id--debian12 agern-run-$id-postgres"), rt.calls.toString())
        assertEquals(0, w.services.sandboxes.inUse())

        // The run records where commands ran; the transcript logs name the platform; the prompt asks for every platform.
        val dto = w.services.runs.toDto(done).sandbox!!
        assertEquals(listOf("jdk17", "debian12"), dto.platforms)
        assertEquals(listOf("default", "jdk17", "debian12"), dto.platformsUsed)
        val logs = builderEvents(w, id).mapNotNull { it.payload.jsonObject["sandbox"]?.jsonObject }
        assertEquals(listOf("default", "default", "jdk17", "jdk17", "debian12", "debian12", "default"), logs.map { it["platform"]!!.jsonPrimitive.content })
        val prompt = w.launcher.byRole("builder").single().written.first()
        assertTrue(prompt.contains("Platforms: `jdk17`, `debian12`") && prompt.contains("report the result per platform"), prompt)
    }

    @Test
    fun `every command container takes a slot, and a run makes room by removing its own idle ones`() {
        val w = world { it.copy(sandbox = it.sandbox.copy(maxConcurrent = 2)) }
        w.sandboxFile(platformDefinition)
        approve(w, platformDefinition)
        val rt = w.sandboxRuntime
        val aDone = CountDownLatch(1)
        val bAsked = CountDownLatch(1)
        val answersA = CopyOnWriteArrayList<ToolAnswer>()
        val answersB = CopyOnWriteArrayList<ToolAnswer>()
        var inUseWithTwo = -1
        script(w) { p ->
            if (p.spec.prompt!!.contains("Implement issue #1 ")) {
                answersA += exec(w, p, "a default")
                answersA += call(w, token(p), "factory_sandbox_exec", buildJsonObject { put("command", "a jdk17"); put("platform", "jdk17") })
                inUseWithTwo = w.services.sandboxes.inUse()
                // Both slots are this run's: the third container replaces the least recently used one (default).
                answersA += call(w, token(p), "factory_sandbox_exec", buildJsonObject { put("command", "a debian"); put("platform", "debian12") })
                aDone.countDown()
                bAsked.await(10, TimeUnit.SECONDS)
            } else {
                aDone.await(10, TimeUnit.SECONDS)
                answersB += exec(w, p, "b default")
                bAsked.countDown()
            }
            "ok"
        }
        val a = start(w, 1)
        assertTrue(aDone.await(10, TimeUnit.SECONDS))
        val b = start(w, 2)
        for (r in listOf(a, b)) assertEquals(RunStatus.MERGED, w.awaitRun(r.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        assertEquals(2, inUseWithTwo)
        assertTrue(answersA.none { it.isError }, answersA.toString())
        assertTrue(rt.calls.contains("rm agern-run-${a.id}"), rt.calls.toString())
        assertTrue(systemTexts(w, a.id).any { it.startsWith("Removed this run's idle `default` container to make room for `debian12`") })
        // Run B found no free slot and holds no container it could give up: busy.
        assertTrue(answersB.single().isError && answersB.single().text.contains("all in use"), answersB.toString())
        assertEquals(0, w.services.sandboxes.inUse())
    }

    @Test
    fun `creation waits for a service's readiness check and fails clearly when it never passes`() {
        val w = world()
        w.sandboxFile(platformDefinition)
        approve(w, platformDefinition)
        val rt = w.sandboxRuntime
        rt.onReady = { _, _, attempt -> if (attempt < 3) 2 else 0 }
        val answers = CopyOnWriteArrayList<ToolAnswer>()
        script(w) { p -> answers += exec(w, p, "make"); "ok" }
        val first = start(w, 1)
        assertEquals(RunStatus.MERGED, w.awaitRun(first.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        assertFalse(answers[0].isError, answers[0].text)
        assertEquals(3, rt.internal.count { it == "ready agern-run-${first.id}-postgres pg_isready -h 127.0.0.1" })
        assertEquals(listOf("echo main setup", "make"), rt.execs.map { it.second })

        // A check that never passes: the creation fails after its time limit, naming the service and showing its log.
        val quick = platformDefinition.replace("timeoutSeconds: 30", "timeoutSeconds: 1")
        w.sandboxFile(quick)
        approve(w, quick)
        rt.onReady = { _, _, _ -> 2 }
        val second = start(w, 2)
        assertEquals(RunStatus.MERGED, w.awaitRun(second.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        val failed = answers[1]
        assertTrue(failed.isError, failed.text)
        assertTrue(failed.text.contains("Service 'postgres' did not become ready within 1 s") && failed.text.contains("pg_isready -h 127.0.0.1"), failed.text)
        assertTrue(failed.text.contains("LOG: database system is starting up (agern-run-${second.id}-postgres)"), failed.text)
        assertTrue(systemTexts(w, second.id).any { it.startsWith("Service 'postgres' did not become ready") })
        assertTrue(rt.execs.none { it.first.startsWith("agern-run-${second.id}") }, "nothing ran in a sandbox whose service was not ready")
        assertTrue(rt.calls.contains("network rm agern-run-${second.id}"))
        assertEquals(0, w.services.sandboxes.inUse())
    }

    @Test
    fun `without a readiness check the agent is told that services may still be starting`() {
        val w = world()
        w.sandboxFile(definition)
        approve(w)
        var info: JsonObject? = null
        script(w) { p -> info = call(w, token(p), "factory_sandbox_info").json; "ok" }
        assertEquals(RunStatus.MERGED, w.awaitRun(start(w).id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        assertEquals("unchecked", info!!["services"]!!.jsonArray[0].jsonObject["readiness"]!!.jsonPrimitive.content)
        assertTrue(info!!["servicesNote"]!!.jsonPrimitive.content.contains("may still be starting"))
        assertEquals(null, info!!["user"])
        assertTrue(w.sandboxRuntime.internal.isEmpty())
    }

    @Test
    fun `at start leftover platform containers are removed too`() {
        val w = world()
        val orphan = UUID.randomUUID()
        val rt = w.sandboxRuntime
        for (n in listOf("agern-run-$orphan", "agern-run-$orphan--jdk17", "agern-run-$orphan-postgres")) rt.containers[n] = orphan.toString()
        rt.networks["agern-run-$orphan"] = orphan.toString()
        w.services.start()
        // The sandbox reaper runs synchronously in start(), before the worktree reaper.
        assertTrue(rt.containers.isEmpty() && rt.networks.isEmpty(), rt.calls.toString())
        assertTrue(rt.calls.any { it.startsWith("rm ") && it.contains("agern-run-$orphan--jdk17") }, rt.calls.toString())
    }

    @Test
    fun `a run's sandbox is always removed before its worktree, also by the reapers at server start`() {
        val w = world()
        w.git.deleteWorktrees = true
        w.sandboxFile(definition)
        approve(w)
        val rt = w.sandboxRuntime
        w.launcher.stage = { p ->
            p.init()
            when (p.spec.kind) {
                "spec-writer" -> { p.result("spec"); p.exit(0) }
                "builder" -> {
                    exec(w, p, "make")
                    when {
                        p.spec.prompt.orEmpty().contains("<issue_body>\nfail\n</issue_body>") -> p.exit(1)
                        p.spec.prompt.orEmpty().contains("<issue_body>\nhang\n</issue_body>") -> while (!p.killed) Thread.sleep(10)
                        else -> { p.result("built"); p.exit(0) }
                    }
                }
                "approver" -> { p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") }); p.exit(0) }
                else -> { p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) }); p.exit(0) }
            }
        }
        val issues = w.github.repos["demo-app"]!!.issues
        issues.replaceAll { when (it.number) { 2 -> it.copy(body = "fail"); 3 -> it.copy(body = "hang"); else -> it } }
        val merged = start(w, 1)
        assertEquals(RunStatus.MERGED, w.awaitRun(merged.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        val failed = start(w, 2)
        assertEquals(RunStatus.FAILED, w.awaitRun(failed.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        val cancelled = start(w, 3)
        waitFor { w.sandboxRuntime.execs.any { it.first == "agern-run-${cancelled.id}" } }
        runBlocking { w.services.orchestrator.cancel(w.user("alice"), cancelled.id) }

        for (run in listOf(merged, failed, cancelled)) {
            val worktree = w.services.workspaces.runDir("alice", "demo-app", run.id)
            assertTrue(worktree in w.git.removedWorktrees, "${run.id}: worktree not removed")
            assertEquals(listOf("agern-run-${run.id}" to true), rt.removedWithWorktree.filter { it.first == "agern-run-${run.id}" },
                "the sandbox of ${run.id} must be removed while its worktree still exists")
        }
        // Names and labels use the run id, never the (per-run) branch name.
        assertTrue(rt.started.all { c -> c.labels["agern.run"]?.let { id -> c.name.startsWith("agern-run-$id") } == true }, rt.started.map { it.name }.toString())

        // Server start: a leftover sandbox of a run that is no longer active, and its worktree on disk.
        val t = world()
        t.git.deleteWorktrees = true
        val alice = t.user("alice")
        val old = t.services.runs.createIfNoActive("demo-app", 1, "x", "task", 2, alice.id, null)!!
        t.services.runs.setStatus(old.id, RunStatus.BUILDING) // failed by the restart recovery
        val wt = java.nio.file.Files.createDirectories(t.services.workspaces.runDir("alice", "demo-app", old.id))
        val def = (SandboxDefinitions.parse(definition, SandboxConfig()) as dk.egernet.factory.sandbox.ParsedDefinition.Valid).definition
        t.sandboxRuntime.createNetwork("agern-run-${old.id}", SandboxManager.labels(old.id, "demo-app", "network"))
        t.sandboxRuntime.startContainer(SandboxManager.mainSpec(old.id, "demo-app", wt, def))
        t.services.start()
        assertEquals(RunStatus.FAILED, t.services.runs.get(old.id)!!.status)
        assertEquals(listOf("agern-run-${old.id}" to true), t.sandboxRuntime.removedWithWorktree.toList(), "sandbox reaper before worktree reaper")
        assertTrue(wt in t.git.removedWorktrees && !java.nio.file.Files.exists(wt), t.git.removedWorktrees.toString())
    }

    // ---------------------------------------------------------------- background commands and factory_sandbox_wait

    private val plain = "image: docker.io/library/debian:12\n"

    @Test
    fun `a background command returns a handle at once and factory_sandbox_wait returns its result`() {
        val w = world()
        w.sandboxFile(plain)
        approve(w, plain)
        val rt = w.sandboxRuntime
        val gate = CountDownLatch(1)
        rt.onExec = { _, cmd, _ ->
            if (cmd == "make long") {
                gate.await(10, TimeUnit.SECONDS)
                ExecResult(4, "long done\n", false, false, 1234)
            } else ExecResult(0, "ran: $cmd\n", false, false, 5)
        }
        val a = linkedMapOf<String, ToolAnswer>()
        var noneYet: ToolAnswer? = null
        script(w) { p ->
            noneYet = wait(w, p)
            a["start"] = execBg(w, p, "make long")
            val handle = a["start"]!!.str("handle")
            a["info"] = call(w, token(p), "factory_sandbox_info")
            a["wait0"] = wait(w, p, handle, 0)
            a["wait1"] = wait(w, p, handle, 1)
            a["latest0"] = wait(w, p, null, 0)
            a["fgDuring"] = exec(w, p, "echo hi")
            a["bgDuring"] = execBg(w, p, "echo hi")
            gate.countDown()
            a["done"] = wait(w, p, handle)
            a["again"] = wait(w, p, handle, 0)
            a["fg"] = exec(w, p, "echo after")
            a["latest"] = wait(w, p)
            a["unknown"] = wait(w, p, "cmd-nope" + "x".repeat(100))
            a["badWait"] = call(w, token(p), "factory_sandbox_wait", buildJsonObject { put("waitSeconds", -1) })
            a["badWait2"] = call(w, token(p), "factory_sandbox_wait", buildJsonObject { put("waitSeconds", "soon") })
            a["badBg"] = call(w, token(p), "factory_sandbox_exec", buildJsonObject { put("command", "x"); put("background", "yes") })
            "ok"
        }
        val run = start(w)
        assertEquals(RunStatus.MERGED, w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN, timeoutMs = 30_000).status)
        assertTrue(noneYet!!.isError && noneYet!!.text == "No sandbox command has been started in this run yet.", noneYet!!.text)

        val start = a["start"]!!
        assertFalse(start.isError, start.text)
        val handle = start.str("handle")
        assertTrue(Regex("cmd-[0-9a-f]{8}").matches(handle), handle)
        assertEquals("true", start.str("running"))
        assertEquals(30 * 60L, start.str("timeoutSeconds").toLong())
        assertTrue(start.str("note").contains("factory_sandbox_wait"))
        assertFalse(start.json.containsKey("platform"), "no platforms in this definition")

        val info = a["info"]!!.json
        assertEquals(handle, info["command"]!!.jsonObject["handle"]!!.jsonPrimitive.content)
        assertEquals(61 * 60L, info["limits"]!!.jsonObject["toolCallTimeoutSeconds"]!!.jsonPrimitive.content.toLong())

        for (k in listOf("wait0", "wait1", "latest0")) {
            val r = a[k]!!
            assertFalse(r.isError, "$k: ${r.text}")
            assertEquals(handle, r.str("handle"))
            assertEquals("true", r.str("running"), k)
            assertTrue(r.json.containsKey("elapsedMs") && !r.json.containsKey("exitCode"), r.text)
        }
        assertTrue(a["wait1"]!!.str("elapsedMs").toLong() >= 1000, a["wait1"]!!.text)
        for (k in listOf("fgDuring", "bgDuring")) {
            val r = a[k]!!
            assertTrue(r.isError && r.text.startsWith("A command is still running in this run's sandbox (handle $handle, started "), "$k: ${r.text}")
        }

        val done = a["done"]!!
        assertFalse(done.isError, done.text)
        assertEquals("false", done.str("running"))
        assertEquals(4, done.str("exitCode").toInt())
        assertEquals("long done\n", done.str("output"))
        assertEquals(1234L, done.str("durationMs").toLong())
        assertEquals("false", done.str("timedOut"))
        assertEquals("false", done.str("truncated"))
        assertEquals(done.text, a["again"]!!.text, "a finished handle answers the same every time")

        // Foreground calls get a handle too, and a wait without a handle resolves to the latest command.
        val fg = a["fg"]!!
        assertEquals(0, fg.str("exitCode").toInt())
        assertFalse(fg.json.containsKey("running"))
        assertEquals(fg.str("handle"), a["latest"]!!.str("handle"))
        assertEquals("ran: echo after\n", a["latest"]!!.str("output"))

        val unknown = a["unknown"]!!
        assertTrue(unknown.isError && unknown.text.startsWith("Unknown handle 'cmd-nope"), unknown.text)
        assertTrue(unknown.text.contains("x".repeat(56) + "'") && !unknown.text.contains("x".repeat(57)), "at most 64 characters echoed")
        for (k in listOf("badWait", "badWait2")) assertEquals("waitSeconds must be a whole number of seconds, 0 or more", a[k]!!.text)
        assertTrue(a["badBg"]!!.isError && a["badBg"]!!.text == "background must be true or false")

        // Only the commands that were started ran; the background one is logged to the transcript when it ended.
        assertEquals(listOf("make long", "echo after"), rt.execs.map { it.second })
        val logs = builderEvents(w, run.id).mapNotNull { it.payload.jsonObject["sandbox"]?.jsonObject }
        assertEquals(listOf("make long", "echo after"), logs.map { it["command"]!!.jsonPrimitive.content })
        assertTrue(systemTexts(w, run.id).any { it == "Sandbox `make long`: exit 4 in 1.234 s" }, systemTexts(w, run.id).toString())
        assertEquals(0, w.services.sandboxes.inUse())
    }

    @Test
    fun `background commands time out, and admin stop and release end their waits with the specified errors`() {
        val w = world()
        w.sandboxFile(plain)
        approve(w, plain)
        val root = w.user("root", admin = true)
        val rt = w.sandboxRuntime
        val hang = CountDownLatch(1)
        val hang2 = CountDownLatch(1)
        rt.onExec = { _, cmd, _ ->
            when {
                cmd == "slow" -> ExecResult(-1, "partial", false, true, 5_000)
                cmd == "hang 1" -> { hang.await(20, TimeUnit.SECONDS); ExecResult(0, "late\n", false, false, 1) }
                cmd == "hang 2" -> { hang2.await(20, TimeUnit.SECONDS); ExecResult(0, "late\n", false, false, 1) }
                else -> ExecResult(0, "ran: $cmd\n", false, false, 5)
            }
        }
        val inHang = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val inHang2 = CountDownLatch(1)
        val released = CountDownLatch(1)
        val a = linkedMapOf<String, ToolAnswer>()
        var runId: UUID? = null
        script(w) { p ->
            val slow = execBg(w, p, "slow", timeoutSeconds = 5)
            a["slowStart"] = slow
            a["slow"] = wait(w, p, slow.str("handle"))
            val h = execBg(w, p, "hang 1").str("handle")
            inHang.countDown()
            stopped.await(10, TimeUnit.SECONDS)
            a["afterStop"] = wait(w, p, h)
            a["afterStopAgain"] = wait(w, p, h, 0)
            hang.countDown()
            // The stopped command's job ends as soon as the runtime's exec returns; then the next one recreates the sandbox.
            var next: ToolAnswer
            do { next = execBg(w, p, "hang 2"); if (next.isError) Thread.sleep(20) } while (next.isError && next.text.startsWith("A command"))
            a["next"] = next
            inHang2.countDown()
            released.await(10, TimeUnit.SECONDS)
            a["released"] = wait(w, p, next.str("handle"), 0)
            "ok"
        }
        val run = start(w)
        runId = run.id
        assertTrue(inHang.await(10, TimeUnit.SECONDS))
        assertEquals(1, w.services.sandboxes.inUse())
        runBlocking { w.services.sandboxes.stop(root, run.id) }
        assertEquals(0, w.services.sandboxes.inUse())
        assertTrue(rt.containers.isEmpty(), rt.calls.toString())
        stopped.countDown()
        assertTrue(inHang2.await(10, TimeUnit.SECONDS))
        waitFor { rt.execs.count { it.second == "hang 2" } == 1 }
        // Started undispatched, the wait has reached its suspension point (awaiting the command's result) when async returns.
        val afterRelease = runBlocking {
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { w.services.sandboxes.wait(run.id, a["next"]!!.str("handle"), null) }
            assertTrue(waiting.isActive, "the wait is suspended on the running command")
            w.services.sandboxes.releaseNow(run.id)
            waiting.await()
        }
        released.countDown()
        hang2.countDown()
        assertEquals(0, w.services.sandboxes.inUse())
        assertTrue(rt.containers.isEmpty() && rt.networks.isEmpty(), rt.calls.toString())
        assertEquals(RunStatus.MERGED, w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN, timeoutMs = 30_000).status)

        assertEquals(5L, a["slowStart"]!!.str("timeoutSeconds").toLong())
        val slow = a["slow"]!!
        assertFalse(slow.isError, slow.text)
        assertEquals("true", slow.str("timedOut"))
        assertEquals(-1, slow.str("exitCode").toInt())
        assertTrue(slow.str("note").startsWith("Stopped after 5 s"), slow.text)
        assertEquals(1, rt.calls.count { it == "restart agern-run-$runId" }, rt.calls.toString())

        for (k in listOf("afterStop", "afterStopAgain")) {
            assertTrue(a[k]!!.isError && a[k]!!.text.startsWith("An admin stopped this run's sandbox while the command ran"), "$k: ${a[k]!!.text}")
        }
        assertFalse(a["next"]!!.isError, a["next"]!!.text)
        assertTrue(afterRelease.isError && afterRelease.text == "The run's sandbox was removed while the command ran (the run ended).",
            afterRelease.text)
        assertTrue(a["released"]!!.isError && a["released"]!!.text.startsWith("This run has no sandbox"), a["released"]!!.text)
        assertEquals(0, w.services.sandboxes.inUse())
    }

    @Test
    fun `a run keeps the results of its last 20 commands`() {
        val w = world()
        w.sandboxFile(plain)
        approve(w, plain)
        val handles = mutableListOf<String>()
        val a = mutableListOf<ToolAnswer>()
        script(w) { p ->
            repeat(22) { handles += exec(w, p, "echo $it").str("handle") }
            a += wait(w, p, handles[0], 0)
            a += wait(w, p, handles[1], 0)
            a += wait(w, p, handles[2], 0)
            a += wait(w, p, handles[21], 0)
            "ok"
        }
        val run = start(w)
        assertEquals(RunStatus.MERGED, w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        assertEquals(22, handles.toSet().size, "handles are unique")
        assertTrue(a[0].isError && a[1].isError, a.toString())
        assertEquals("ran: echo 2\n", a[2].str("output"))
        assertEquals("ran: echo 21\n", a[3].str("output"))
    }
}
