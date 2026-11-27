package dk.egernet.factory

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dk.egernet.factory.deploy.DeployException
import dk.egernet.factory.deploy.DeployExecResult
import dk.egernet.factory.mcp.McpGrant
import dk.egernet.factory.mcp.McpRegistry
import dk.egernet.factory.mcp.PersonalTokenRef
import dk.egernet.factory.mcp.SandboxScope
import dk.egernet.factory.sandboxvm.SandboxVmDefinitions
import dk.egernet.factory.sandboxvm.SandboxVmManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sandbox VM tools: who gets them, the refusals, the deploy procedure, exec, wait, the lease and its reset. */
class SandboxVmToolTest {
    private val worlds = CopyOnWriteArrayList<TestWorld>()
    private fun world(tweak: (Config) -> Config = { it }) = TestWorld(twoFactor = false, tweak = tweak).also { worlds += it }

    private val logs = ListAppender<ILoggingEvent>().also { it.start() }
    private val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

    init {
        rootLogger.addAppender(logs)
    }

    @AfterTest fun tearDown() {
        rootLogger.detachAppender(logs)
        worlds.forEach { it.close() }
    }

    private val def = """
        name: staging
        host: vm.example.net
        user: agern
        hostKey: ${HostKeys.ed25519()}
        url: https://vm.example.net/
        checkout: tar xzf "${'$'}AGERN_ARCHIVE" -C /srv/app
        setup: sudo systemctl restart app
        ready:
          command: curl -fsS http://127.0.0.1:8080/health
          timeoutSeconds: 5
        reset: /srv/app/bin/restore-test-db
    """.trimIndent() + "\n"

    private val sha = "0000000000000000000000000000000000000001"
    private val tools = SandboxVmManager.TOOLS

    /** Approves [content] for [repo] and generates the project's key, as an admin would. */
    private fun ready(w: TestWorld, content: String = def, repo: String = "demo-app", key: Boolean = true) {
        w.sandboxVmFile(content, repo)
        val root = w.user("root", admin = true)
        runBlocking {
            // root cannot see private-lab; approve through the stores there, as the page would for an admin who can.
            w.services.sandboxVms.let { vms ->
                if (repo == "demo-app") vms.approve(root, repo, SandboxVmDefinitions.sha256(content))
                else dk.egernet.factory.db.SandboxVmApprovalStore(w.services.db).approve(repo, SandboxVmDefinitions.sha256(content), content, root.id)
            }
            if (key && repo == "demo-app") w.services.deploys.generateKey(root, repo)
            if (key && repo != "demo-app") {
                val k = dk.egernet.factory.deploy.DeployKeys.generate("agern-deploy:test-org/$repo")
                w.services.db.update("INSERT INTO deploy_keys(repo, public_key, fingerprint, private_key_enc, created_by) VALUES (?, ?, ?, ?, ?)",
                    repo, k.publicKey, k.fingerprint, w.services.box.encrypt(k.privateKey, dk.egernet.factory.deploy.DeployKeys.aad(repo)), root.id)
            }
        }
    }

    private fun call(w: TestWorld, p: FakeProcess, tool: String, args: JsonObject = buildJsonObject {}) = Mcp.call(w, Mcp.token(p), tool, args)

    /**
     * Stages answer as usual; the builder runs [builder] (MCP calls) before its result. Records each stage's prompt and
     * listed tools by kind.
     */
    private class Recorded {
        val prompts = ConcurrentHashMap<String, String>()
        val tools = ConcurrentHashMap<String, List<String>>()
        val tokens = ConcurrentHashMap<String, String>()
    }

    private fun pipeline(w: TestWorld, verdict: String = "APPROVE", builder: (FakeProcess) -> Unit = {}): Recorded {
        val rec = Recorded()
        w.launcher.stage = { p ->
            rec.prompts[p.spec.kind] = p.spec.prompt.orEmpty()
            rec.tools[p.spec.kind] = Mcp.tools(w, Mcp.token(p))
            rec.tokens[p.spec.kind] = Mcp.token(p)
            p.init()
            when (p.spec.kind) {
                "spec-writer" -> p.result("## Spec\nDo it.")
                "builder" -> { builder(p); p.result("Built it.") }
                "approver" -> p.result(null, buildJsonObject { put("verdict", verdict); put("summary", "ok") })
                else -> p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
            }
            p.exit(0)
        }
        return rec
    }

    private fun start(w: TestWorld, repo: String = "demo-app", issue: Int = 1): UUID {
        val alice = w.user("alice")
        return runBlocking { w.services.orchestrator.start(alice, repo, issue, null) }.id
    }

    private fun vmEvents(w: TestWorld, runId: UUID): List<JsonObject> {
        val builder = w.services.runs.stages(runId).filter { it.role == "builder" }.mapNotNull { it.sessionId }
        return builder.flatMap { sid -> w.services.events.list(sid, 0, 1000) }.filter { it.type == "system" }
            .map { it.payload.jsonObject }.filter { it["sandboxVm"] != null }
    }

    private fun awaitNoLease(w: TestWorld) = runBlocking {
        withTimeout(10_000) { while (w.services.db.queryOne("SELECT count(*) AS n FROM sandbox_vm_leases") { it.getInt("n") } != 0) delay(20) }
    }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test
    fun `the builder of an approved run deploys HEAD, runs commands and the lease is reset when the run ends`() {
        val w = world()
        ready(w)
        val answers = ConcurrentHashMap<String, Mcp.Answer>()
        val rec = pipeline(w) { p ->
            answers["info"] = call(w, p, "factory_sandbox_vm_info")
            answers["execEarly"] = call(w, p, "factory_sandbox_vm_exec", buildJsonObject { put("command", "uptime") })
            answers["deploy"] = call(w, p, "factory_sandbox_vm_deploy")
            answers["exec"] = call(w, p, "factory_sandbox_vm_exec", buildJsonObject { put("command", "echo checked on the vm") })
            answers["bg"] = call(w, p, "factory_sandbox_vm_exec", buildJsonObject { put("command", "make e2e"); put("background", true) })
            answers["wait"] = call(w, p, "factory_sandbox_vm_wait", buildJsonObject { put("handle", answers["bg"]!!.json.s("handle")) })
            answers["info2"] = call(w, p, "factory_sandbox_vm_info")
        }
        val runId = start(w)
        w.awaitRun(runId, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)

        // Only the builder lists the tools; its prompt tells it about the VM.
        assertTrue(rec.tools["builder"]!!.containsAll(tools), rec.tools["builder"].toString())
        for ((kind, list) in rec.tools) if (kind != "builder") assertTrue(list.none { it in tools }, "$kind lists $list")
        assertContains(rec.prompts["builder"]!!, "Sandbox VM: the project has a shared sandbox VM `staging` (`agern@vm.example.net`, https://vm.example.net/)")
        assertContains(rec.prompts["spec-writer"]!!, "Sandbox VM: the project has a shared sandbox VM `staging`")

        answers["info"]!!.let {
            assertFalse(it.isError, it.text)
            assertEquals("true", it.json.s("available")); assertEquals("staging", it.json.s("name"))
            assertEquals("false", it.json["lease"]!!.jsonObject.s("heldByThisRun"))
        }
        assertEquals(Mcp.Answer(true, "This run does not hold the sandbox VM. Call factory_sandbox_vm_deploy first."), answers["execEarly"])
        val d = answers["deploy"]!!
        assertFalse(d.isError, d.text)
        assertEquals("true", d.json.s("ok")); assertEquals(sha, d.json.s("sha")); assertEquals("false", d.json.s("dirty"))
        assertEquals("https://vm.example.net/", d.json.s("url"))
        assertEquals(listOf("upload", "checkout", "setup", "ready"), d.json["steps"]!!.jsonArray.map { it.jsonObject.s("step") })

        // Upload of the archive, then the commands in order, each with the export line.
        val prefix = "export AGERN_SHA='$sha' AGERN_RUN_ID='$runId' AGERN_ARCHIVE=\"\$HOME/agern-sandbox-vm.tar.gz\"\n"
        val calls = w.vmTransport.calls.toList()
        assertEquals("copy staging agern-sandbox-vm.tar.gz agern-sandbox-vm.tar.gz", calls[0])
        assertEquals("exec staging ${prefix}tar xzf \"\$AGERN_ARCHIVE\" -C /srv/app", calls[1])
        assertEquals("exec staging ${prefix}sudo systemctl restart app", calls[2])
        assertEquals("exec staging ${prefix}curl -fsS http://127.0.0.1:8080/health", calls[3])
        assertEquals("exec staging echo checked on the vm", calls[4])
        w.vmTransport.copies.single().let {
            assertEquals("archive of $sha", String(it.content)); assertEquals(0b110_000_000, it.mode)
        }
        assertTrue(w.git.archives.single().startsWith("$sha "))
        assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of(w.git.archives.single().substringAfter(' '))), "the archive is deleted")

        assertFalse(answers["exec"]!!.isError)
        assertEquals("0", answers["exec"]!!.json.s("exitCode"))
        assertEquals("true", answers["bg"]!!.json.s("running"))
        answers["wait"]!!.json.let { assertEquals("false", it.s("running")); assertEquals("0", it.s("exitCode")); assertContains(it.s("output"), "ran: make e2e") }
        answers["info2"]!!.json.let {
            assertEquals("true", it["lease"]!!.jsonObject.s("heldByThisRun")); assertEquals(sha, it.s("deployedSha"))
        }

        // Each step and command is a transcript block.
        val events = vmEvents(w, runId)
        assertEquals(listOf("upload", "checkout", "setup", "ready"), events.filter { it["sandboxVm"]!!.jsonObject.s("kind") == "deploy" }
            .map { it["sandboxVm"]!!.jsonObject.s("step") })
        assertEquals(2, events.count { it["sandboxVm"]!!.jsonObject.s("kind") == "exec" })
        events.first { it["sandboxVm"]!!.jsonObject.s("step") == "setup" }["sandboxVm"]!!.jsonObject.let {
            assertEquals("vm.example.net", it.s("host")); assertEquals("staging", it.s("vm")); assertEquals("sudo systemctl restart app", it.s("command"))
        }
        assertEquals(true, w.services.runs.get(runId)!!.sandboxVm!!.used)

        // The build lease ended with the loop: reset ran once, the row is gone.
        awaitNoLease(w)
        assertEquals(1, w.vmTransport.calls.count { it.endsWith("/srv/app/bin/restore-test-db") })
        assertTrue(logs.list.none { it.formattedMessage.contains("systemctl") || it.formattedMessage.contains("checked on the vm") }, "commands are never logged")
        assertNoPrivateKey(w, runId, answers.values.toList())
    }

    private fun assertNoPrivateKey(w: TestWorld, runId: UUID, answers: List<Mcp.Answer>) {
        val texts = answers.map { it.text } + vmEvents(w, runId).map { it.toString() } + logs.list.map { it.formattedMessage }
        for (t in texts) {
            assertFalse(t.contains("PRIVATE KEY"), t)
            assertFalse(t.contains("b3BlbnNzaC1rZXktdjEA"), t)
        }
    }

    @Test
    fun `runs whose file is not approved get no tools and an unchanged prompt`() {
        val w = world()
        w.sandboxVmFile(def)
        val rec = pipeline(w)
        val runId = start(w)
        w.awaitRun(runId, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals("unapproved", w.services.runs.get(runId)!!.sandboxVm!!.state)
        for ((kind, list) in rec.tools) assertTrue(list.none { it in tools }, "$kind lists $list")
        assertFalse(rec.prompts["builder"]!!.contains("Sandbox VM"))
        assertFalse(rec.prompts["spec-writer"]!!.contains("Sandbox VM"))

        // No file at all: state none; switched off: disabled, also no tools.
        val w2 = world()
        val rec2 = pipeline(w2)
        val r2 = start(w2)
        w2.awaitRun(r2, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals("none", w2.services.runs.get(r2)!!.sandboxVm!!.state)
        assertFalse(rec2.prompts["builder"]!!.contains("Sandbox VM"))

        val w3 = world { it.copy(sandboxVm = SandboxVmConfig(mode = "off")) }
        ready(w3)
        val rec3 = pipeline(w3)
        val r3 = start(w3)
        w3.awaitRun(r3, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals("disabled", w3.services.runs.get(r3)!!.sandboxVm!!.state)
        assertTrue(rec3.tools["builder"]!!.none { it in tools })
        assertFalse(rec3.prompts["builder"]!!.contains("Sandbox VM"))
    }

    @Test
    fun `the tools are refused on the server for everyone but a run's builder`() {
        val w = world()
        ready(w)
        pipeline(w)
        val runId = start(w)
        w.awaitRun(runId, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        val alice = w.user("alice")
        val stage = { role: String -> w.services.runs.stages(runId).first { it.role == role }.sessionId!! }
        val chat = w.services.agents.createChat(alice, "demo-app", null)
        val advisor = w.services.sessions.create("demo-app", ADVISOR_KIND, "m", "idle", alice.id, null, runId = runId)
        val forged = listOf(
            McpGrant(chat.id, alice.id, "demo-app", null, McpRegistry.CHAT_TOOLS + tools),
            McpGrant(null, alice.id, "demo-app", null, McpRegistry.PERSONAL_TOOLS + tools, PersonalTokenRef(UUID.randomUUID(), "laptop")),
            McpGrant(stage("spec-writer"), alice.id, "demo-app", runId, tools, sandboxScope = SandboxScope.PLAN),
            McpGrant(stage("spec-writer"), alice.id, "demo-app", runId, tools),
            McpGrant(stage("security"), alice.id, "demo-app", runId, tools, sandboxScope = SandboxScope.REVIEW),
            McpGrant(stage("ux"), alice.id, "demo-app", runId, tools),
            McpGrant(stage("approver"), alice.id, "demo-app", runId, tools),
            McpGrant(advisor.id, alice.id, "demo-app", runId, tools),
            McpGrant(stage("builder"), alice.id, "demo-app", null, tools),
            McpGrant(stage("builder"), alice.id, "demo-app", runId, tools, sandboxScope = SandboxScope.REVIEW),
        )
        val refused = Mcp.Answer(true, "Sandbox VM tools are only available to a run's builder")
        for (g in forged) {
            val token = w.services.mcpRegistry.issue(g)
            for (t in tools) assertEquals(refused, Mcp.call(w, token, t, buildJsonObject { put("command", "id") }), "$t $g")
        }
        assertTrue(w.vmTransport.calls.isEmpty())
        assertTrue(McpRegistry.CHAT_TOOLS.none { it in tools } && McpRegistry.PERSONAL_TOOLS.none { it in tools } && McpRegistry.STAGE_TOOLS.none { it in tools })
    }

    @Test
    fun `a failing step stops the deploy, dirty is reported, and connection failures are tool errors`() {
        val w = world()
        ready(w)
        w.git.dirty = true
        w.vmTransport.onExec = { _, cmd, _ ->
            if (cmd.endsWith("restart app")) DeployExecResult(3, null, "unit app.service failed\n", false, false, 9)
            else DeployExecResult(0, null, "ok\n", false, false, 1)
        }
        val answers = ConcurrentHashMap<String, Mcp.Answer>()
        pipeline(w) { p ->
            answers["deploy"] = call(w, p, "factory_sandbox_vm_deploy")
            answers["exec"] = call(w, p, "factory_sandbox_vm_exec", buildJsonObject { put("command", "journalctl -u app") })
            w.vmTransport.onExec = { _, _, _ -> throw DeployException("host_key", "Host key of staging does not match the pinned key") }
            answers["broken"] = call(w, p, "factory_sandbox_vm_exec", buildJsonObject { put("command", "true") })
            answers["bad"] = call(w, p, "factory_sandbox_vm_exec", buildJsonObject { put("command", " ") })
            // The builder commits before it ends, so it is not resumed to finish.
            w.git.dirty = false
        }
        val runId = start(w)
        w.awaitRun(runId, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        answers["deploy"]!!.let {
            assertFalse(it.isError, it.text)
            assertEquals("false", it.json.s("ok")); assertEquals("setup", it.json.s("failedStep")); assertEquals("true", it.json.s("dirty"))
            assertContains(it.json.s("output"), "unit app.service failed")
            assertEquals(listOf("upload", "checkout", "setup"), it.json["steps"]!!.jsonArray.map { s -> s.jsonObject.s("step") })
            assertContains(it.json.s("note"), "uncommitted changes")
        }
        assertFalse(answers["exec"]!!.isError, "the lease stays after a failed step")
        assertTrue(answers["broken"]!!.isError); assertContains(answers["broken"]!!.text, "does not match the pinned key")
        assertTrue(answers["bad"]!!.isError)
        assertTrue(w.vmTransport.calls.none { it.contains("curl") }, "ready never ran")
        assertEquals("warn", vmEvents(w, runId).first { it["sandboxVm"]!!.jsonObject["step"]?.jsonPrimitive?.content == "setup" }.s("level"))
    }

    @Test
    fun `a busy VM is refused at once across runs and repositories, and freed when the holder ends`() {
        val w = world()
        ready(w)
        ready(w, repo = "private-lab")
        val holding = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val answers = ConcurrentHashMap<String, Mcp.Answer>()
        w.launcher.stage = { p ->
            p.init()
            val lab = p.spec.workDir.toString().contains("private-lab")
            when (p.spec.kind) {
                "spec-writer" -> p.result("## Spec\nDo it.")
                "builder" -> {
                    if (!lab) {
                        answers["a"] = call(w, p, "factory_sandbox_vm_deploy")
                        holding.countDown()
                        letGo.await(20, TimeUnit.SECONDS)
                    } else {
                        holding.await(20, TimeUnit.SECONDS)
                        answers["b"] = call(w, p, "factory_sandbox_vm_deploy")
                        letGo.countDown()
                    }
                    p.result("Built it.")
                }
                "approver" -> p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") })
                else -> p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
            }
            p.exit(0)
        }
        val a = start(w)
        val b = start(w, repo = "private-lab")
        w.awaitRun(a, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        w.awaitRun(b, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertFalse(answers["a"]!!.isError, answers["a"]!!.text)
        answers["b"]!!.let {
            assertTrue(it.isError)
            assertContains(it.text, "The sandbox VM staging is in use by run $a of demo-app since")
            assertContains(it.text, "Continue without it")
        }
        awaitNoLease(w)
        // Afterwards another run can take it.
        pipeline(w) { p -> answers["c"] = call(w, p, "factory_sandbox_vm_deploy") }
        val c = start(w, issue = 2)
        w.awaitRun(c, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertFalse(answers["c"]!!.isError, answers["c"]!!.text)
    }

    @Test
    fun `cancelling a run ends its lease, cancels the command in flight and resets the VM`() {
        val w = world()
        ready(w)
        val running = CountDownLatch(1)
        w.vmTransport.onExec = { _, cmd, _ ->
            if (cmd == "sleep long") { running.countDown(); Thread.sleep(30_000) }
            DeployExecResult(0, null, "", false, false, 1)
        }
        val answers = ConcurrentHashMap<String, Mcp.Answer>()
        pipeline(w) { p ->
            call(w, p, "factory_sandbox_vm_deploy")
            answers["long"] = call(w, p, "factory_sandbox_vm_exec", buildJsonObject { put("command", "sleep long") })
        }
        val runId = start(w)
        assertTrue(running.await(10, TimeUnit.SECONDS))
        val started = System.nanoTime()
        runBlocking { w.services.orchestrator.cancel(w.user("alice"), runId) }
        awaitNoLease(w)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 15_000, "the command did not hold up the end of the lease")
        assertEquals(listOf("sleep long"), w.vmTransport.cancelledExecs.toList())
        assertEquals(1, w.vmTransport.calls.count { it.endsWith("restore-test-db") })
        answers["long"]?.let { assertTrue(it.isError) }
    }

    @Test
    fun `a failed run ends its lease and resets the VM`() {
        val w = world()
        ready(w)
        w.launcher.stage = { p ->
            p.init()
            when (p.spec.kind) {
                "spec-writer" -> { p.result("## Spec\nDo it."); p.exit(0) }
                "builder" -> { call(w, p, "factory_sandbox_vm_deploy"); p.exit(1) }
                else -> { p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) }); p.exit(0) }
            }
        }
        val runId = start(w)
        assertEquals(RunStatus.FAILED, w.awaitRun(runId, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        awaitNoLease(w)
        assertEquals(1, w.vmTransport.calls.count { it.endsWith("restore-test-db") })
    }

    @Test
    fun `revoking the approval during a run removes the lease without reset, and a missing key is refused`() {
        val w = world()
        ready(w)
        val root = w.user("root", admin = true)
        val answers = ConcurrentHashMap<String, Mcp.Answer>()
        pipeline(w) { p ->
            answers["deploy"] = call(w, p, "factory_sandbox_vm_deploy")
            runBlocking { w.services.sandboxVms.revoke(root, "demo-app") }
            answers["leases"] = Mcp.Answer(false, w.services.db.queryOne("SELECT count(*) AS n FROM sandbox_vm_leases") { it.getInt("n") }.toString())
            answers["exec"] = call(w, p, "factory_sandbox_vm_exec", buildJsonObject { put("command", "true") })
            answers["deploy2"] = call(w, p, "factory_sandbox_vm_deploy")
            answers["info"] = call(w, p, "factory_sandbox_vm_info")
        }
        val runId = start(w)
        w.awaitRun(runId, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertFalse(answers["deploy"]!!.isError)
        assertEquals("0", answers["leases"]!!.text)
        assertTrue(answers["exec"]!!.isError); assertContains(answers["exec"]!!.text, "revoked")
        assertTrue(answers["deploy2"]!!.isError); assertContains(answers["deploy2"]!!.text, "revoked")
        assertEquals("false", answers["info"]!!.json.s("available"))
        assertTrue(w.vmTransport.calls.none { it.endsWith("restore-test-db") }, "no reset after a revocation")

        // No key: refused with the page's URL.
        val w2 = world()
        ready(w2, key = false)
        pipeline(w2) { p -> answers["nokey"] = call(w2, p, "factory_sandbox_vm_deploy") }
        val r2 = start(w2)
        w2.awaitRun(r2, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertTrue(answers["nokey"]!!.isError)
        assertContains(answers["nokey"]!!.text, "no deploy key yet")
        assertContains(answers["nokey"]!!.text, "http://localhost:8080/p/demo-app/sandbox-vm")
        assertNull(w2.services.db.queryOne("SELECT vm_key FROM sandbox_vm_leases") { it.getString("vm_key") })
    }
}
