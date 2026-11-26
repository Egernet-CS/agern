package dk.egernet.factory

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dk.egernet.factory.deploy.DeployDefinitions
import dk.egernet.factory.deploy.DeployException
import dk.egernet.factory.deploy.DeployExecResult
import dk.egernet.factory.deploy.DeployManager
import dk.egernet.factory.mcp.McpGrant
import dk.egernet.factory.mcp.McpRegistry
import dk.egernet.factory.mcp.PersonalTokenRef
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The deploy tools: who gets them, their refusals, exec and copy through the MCP server, and the transcript record. */
class DeployToolTest {
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

    private val def = "targets:\n  kiosk:\n    host: kiosk.example.net\n    user: deploy\n    hostKey: ${HostKeys.ed25519()}\n" +
        "  lab:\n    host: lab.example.net\n    port: 2200\n    user: lab\n    hostKey: ${HostKeys.ed25519(2)}\n"

    private val deployTools = DeployManager.TOOLS

    /** A chat of [user] and a token with the chat's tools plus the deploy tools, as AgentManager grants them. */
    private fun chat(w: TestWorld, user: String = "alice"): Pair<UUID, String> {
        val u = w.user(user)
        val chat = w.services.agents.createChat(u, "demo-app", null)
        return chat.id to w.services.mcpRegistry.issue(McpGrant(chat.id, u.id, "demo-app", null, McpRegistry.CHAT_TOOLS + deployTools))
    }

    /** Approves [content] and generates the key, as an admin would on the Deploy page. */
    private fun ready(w: TestWorld, content: String = def) {
        w.deployFile(content)
        val root = w.user("root", admin = true)
        runBlocking {
            w.services.deploys.approve(root, "demo-app", DeployDefinitions.sha256(content))
            if (w.services.deploys.status(root, "demo-app").key == null) w.services.deploys.generateKey(root, "demo-app")
        }
    }

    private fun exec(w: TestWorld, token: String, target: String, command: String, timeout: Int? = null) =
        Mcp.call(w, token, "factory_deploy_exec", buildJsonObject {
            put("target", target); put("command", command); if (timeout != null) put("timeoutSeconds", timeout)
        })

    private fun copy(w: TestWorld, token: String, path: String, dest: String, target: String = "kiosk") =
        Mcp.call(w, token, "factory_deploy_copy", buildJsonObject { put("target", target); put("path", path); put("dest", dest) })

    private fun targets(w: TestWorld, token: String) = Mcp.call(w, token, "factory_deploy_targets", buildJsonObject {})

    private fun deployEvents(w: TestWorld, sessionId: UUID): List<JsonObject> =
        w.services.events.list(sessionId, 0, 1000).filter { it.type == "system" }.map { it.payload.jsonObject }.filter { it["deploy"] != null }

    private fun mainDir(w: TestWorld, user: String = "alice"): Path =
        Files.createDirectories(w.services.workspaces.mainDir(user, "demo-app"))

    @Test
    fun `only chat sessions list and may call the deploy tools`() {
        val w = world()
        ready(w)
        // The grant a real chat process gets.
        val alice = w.user("alice")
        val chat = w.services.agents.createChat(alice, "demo-app", null)
        w.services.agents.sendInput(alice, chat, "hello")
        val proc = waitFor { w.launcher.launched.firstOrNull { it.spec.sessionId == chat.id } }
        val listed = Mcp.tools(w, Mcp.token(proc))
        assertTrue(listed.containsAll(deployTools), listed.toString())

        // Pipeline agents of a real run never list them, and the static sets do not contain them.
        val lists = ConcurrentHashMap<String, List<String>>()
        w.launcher.stage = { p ->
            lists[p.spec.kind] = Mcp.tools(w, Mcp.token(p))
            p.init()
            when (p.spec.kind) {
                "spec-writer" -> p.result("## Spec\nDo it.")
                "builder" -> p.result("Built it.")
                "approver" -> p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") })
                else -> p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
            }
            p.exit(0)
        }
        val run = runBlocking { w.services.orchestrator.start(alice, "demo-app", 1, null) }
        w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(setOf("spec-writer", "builder", "approver") + REVIEWER_ROLES, lists.keys)
        for ((role, tools) in lists) assertTrue(tools.none { it in deployTools }, "$role lists $tools")
        assertTrue(McpRegistry.CHAT_TOOLS.none { it in deployTools })
        assertTrue(McpRegistry.PERSONAL_TOOLS.none { it in deployTools })
        assertTrue(McpRegistry.STAGE_TOOLS.none { it in deployTools })

        // Hand-built grants that include the tools are refused on the server.
        val chatOnly = Mcp.Answer(true, "Deploy tools are only available in a project chat")
        val builder = w.services.runs.stages(run.id).single { it.role == "builder" }.sessionId!!
        val advisor = w.services.sessions.create("demo-app", ADVISOR_KIND, "m", "idle", alice.id, null, runId = run.id)
        val forged = listOf(
            McpGrant(builder, alice.id, "demo-app", run.id, deployTools),
            McpGrant(advisor.id, alice.id, "demo-app", null, deployTools),
            McpGrant(null, alice.id, "demo-app", null, deployTools),
            McpGrant(null, alice.id, "demo-app", null, McpRegistry.PERSONAL_TOOLS + deployTools, PersonalTokenRef(UUID.randomUUID(), "laptop")),
            McpGrant(chat.id, alice.id, "demo-app", run.id, deployTools),
        )
        for (g in forged) {
            val token = w.services.mcpRegistry.issue(g)
            assertEquals(chatOnly, exec(w, token, "kiosk", "echo hi"), g.toString())
            assertEquals(chatOnly, targets(w, token), g.toString())
            assertEquals(chatOnly, copy(w, token, "x", "/tmp/"), g.toString())
        }
        assertTrue(w.deployTransport.calls.isEmpty())
    }

    @Test
    fun `switched off, chats get no deploy tools and calls are refused`() {
        val w = world { it.copy(deploy = DeployConfig(mode = "off")) }
        ready(w)
        val alice = w.user("alice")
        val chat = w.services.agents.createChat(alice, "demo-app", null)
        w.services.agents.sendInput(alice, chat, "hello")
        val proc = waitFor { w.launcher.launched.firstOrNull { it.spec.sessionId == chat.id } }
        assertTrue(Mcp.tools(w, Mcp.token(proc)).none { it in deployTools })
        val (_, token) = chat(w)
        assertTrue(exec(w, token, "kiosk", "echo hi").let { it.isError && it.text.contains("FACTORY_DEPLOY=off") })
        targets(w, token).let { assertFalse(it.isError); assertEquals("false", it.json["available"]!!.jsonPrimitive.content) }
    }

    @Test
    fun `refusals for a missing, invalid, unapproved or changed file, no key and unknown targets`() {
        val w = world()
        val (_, token) = chat(w)
        val root = w.user("root", admin = true)

        fun refused(contains: String) {
            val e = exec(w, token, "kiosk", "echo hi")
            assertTrue(e.isError && e.text.contains(contains), e.text)
            val c = copy(w, token, "README.md", "/tmp/")
            assertTrue(c.isError, c.text)
            val t = targets(w, token)
            assertFalse(t.isError, t.text)
            assertEquals("false", t.json["available"]!!.jsonPrimitive.content)
            assertTrue(t.json["reason"]!!.jsonPrimitive.content.contains(contains), t.text)
        }
        Files.writeString(mainDir(w).resolve("README.md"), "hi")
        refused("has no .agern/deploy.yml")
        w.deployFile("targets: {}\n")
        refused("is invalid: targets: at least one")
        w.deployFile(def)
        refused("no version of it has been approved")
        refused("http://localhost:8080/p/demo-app/deploy")
        runBlocking { w.services.deploys.approve(root, "demo-app", DeployDefinitions.sha256(def)) }
        refused("no deploy key yet")
        runBlocking { w.services.deploys.generateKey(root, "demo-app") }
        assertFalse(exec(w, token, "kiosk", "echo hi").isError)
        // Any change, also one that does not touch the target, disables every target until it is approved again.
        w.deployFile(def + "# a comment\n")
        refused("it changed since an admin approved")
        w.deployFile(def)
        assertEquals(Mcp.Answer(true, "Unknown target 'nope'. This project's targets are: kiosk, lab."), exec(w, token, "nope", "echo hi"))
        assertEquals(listOf("exec kiosk echo hi"), w.deployTransport.calls)
        assertTrue(deployEvents(w, UUID.randomUUID()).isEmpty())
    }

    @Test
    fun `targets, exec and its transcript record`() {
        val w = world()
        ready(w)
        val (sessionId, token) = chat(w)
        val t = targets(w, token)
        assertFalse(t.isError)
        t.json.let {
            assertEquals("true", it["available"]!!.jsonPrimitive.content)
            assertEquals("approved", it["state"]!!.jsonPrimitive.content)
            val list = it["targets"]!!.jsonArray.map { e -> e.jsonObject }
            assertEquals(listOf("kiosk", "lab"), list.map { e -> e["name"]!!.jsonPrimitive.content })
            assertEquals("2200", list[1]["port"]!!.jsonPrimitive.content)
            assertFalse(list[0].containsKey("hostKey"))
            assertTrue(it["publicKeyFingerprint"]!!.jsonPrimitive.content.startsWith("SHA256:"))
            assertEquals("1800", it["limits"]!!.jsonObject["execTimeoutSeconds"]!!.jsonPrimitive.content)
            assertEquals("20000", it["limits"]!!.jsonObject["outputTailChars"]!!.jsonPrimitive.content)
        }

        w.deployTransport.onExec = { _, cmd, _ -> DeployExecResult(if (cmd.contains("fail")) 2 else 0, null, "x".repeat(25_000) + "\nend\n", false, false, 1234) }
        val ok = exec(w, token, "kiosk", "systemctl restart app\nsecond line")
        assertFalse(ok.isError, ok.text)
        ok.json.let {
            assertEquals("kiosk", it["target"]!!.jsonPrimitive.content)
            assertEquals("0", it["exitCode"]!!.jsonPrimitive.content)
            assertEquals(20_000, it["output"]!!.jsonPrimitive.content.length)
            assertTrue(it["output"]!!.jsonPrimitive.content.endsWith("\nend\n"))
            assertEquals("true", it["truncated"]!!.jsonPrimitive.content)
            assertEquals("false", it["timedOut"]!!.jsonPrimitive.content)
        }
        // A non-zero exit code is an answer, not a tool error.
        val failed = exec(w, token, "lab", "make fail")
        assertFalse(failed.isError)
        assertEquals("2", failed.json["exitCode"]!!.jsonPrimitive.content)

        val events = deployEvents(w, sessionId)
        assertEquals(2, events.size)
        events[0].let {
            assertEquals("info", it["level"]!!.jsonPrimitive.content)
            assertEquals("Deploy [kiosk] `systemctl restart app`: exit 0 in 1.2 s", it["text"]!!.jsonPrimitive.content)
            val d = it["deploy"]!!.jsonObject
            assertEquals("exec", d["kind"]!!.jsonPrimitive.content)
            assertEquals("kiosk.example.net", d["host"]!!.jsonPrimitive.content)
            assertEquals("22", d["port"]!!.jsonPrimitive.content)
            assertEquals("deploy", d["user"]!!.jsonPrimitive.content)
            assertEquals("systemctl restart app\nsecond line", d["command"]!!.jsonPrimitive.content)
            assertEquals(25_005, d["output"]!!.jsonPrimitive.content.length, "the full output goes to the transcript")
            assertEquals(JsonNull, d["error"])
            assertEquals("0", d["exitCode"]!!.jsonPrimitive.content)
        }
        assertEquals("warn", events[1]["level"]!!.jsonPrimitive.content)
        assertTrue(logs.list.any { it.formattedMessage.startsWith("Deploy exec on demo-app/kiosk by alice: exit 0 in") })
        assertTrue(logs.list.none { it.formattedMessage.contains("systemctl") }, "the command is never logged")
        assertNoPrivateKey(w, sessionId, listOf(t, ok, failed))
    }

    @Test
    fun `timeouts, a missing exit status, connection failures and a busy target`() {
        val w = world()
        ready(w)
        val (sessionId, token) = chat(w)
        val timeouts = CopyOnWriteArrayList<Long>()
        w.deployTransport.onExec = { _, cmd, timeoutMs ->
            timeouts += timeoutMs
            when (cmd) {
                "long" -> DeployExecResult(null, null, "partial\n", false, true, timeoutMs)
                "killed" -> DeployExecResult(null, "KILL", "", false, false, 5)
                else -> DeployExecResult(0, null, "", false, false, 5)
            }
        }
        exec(w, token, "kiosk", "long", timeout = 5).let {
            assertFalse(it.isError)
            assertEquals("true", it.json["timedOut"]!!.jsonPrimitive.content)
            assertEquals("-1", it.json["exitCode"]!!.jsonPrimitive.content)
            assertContains(it.json["note"]!!.jsonPrimitive.content, "may still be running")
        }
        exec(w, token, "kiosk", "short", timeout = 999_999)
        assertEquals(listOf(5_000L, 30 * 60_000L), timeouts, "capped at the server's limit")
        exec(w, token, "kiosk", "killed").let {
            assertEquals("-1", it.json["exitCode"]!!.jsonPrimitive.content)
            assertContains(it.json["note"]!!.jsonPrimitive.content, "signal KILL")
        }
        for (bad in listOf(0, -3)) assertTrue(exec(w, token, "kiosk", "x", timeout = bad).isError)
        assertTrue(exec(w, token, "kiosk", "   ").isError)
        assertTrue(exec(w, token, "kiosk", "x".repeat(32_769)).isError)
        assertTrue(exec(w, token, "kiosk", "a\u0000b").isError)

        w.deployTransport.onExec = { _, _, _ -> throw DeployException("connect", "kiosk.example.net:22: Connection refused") }
        exec(w, token, "kiosk", "echo hi").let {
            assertTrue(it.isError)
            assertEquals("Deploy exec on kiosk failed: could not connect: kiosk.example.net:22: Connection refused", it.text)
        }
        w.deployTransport.onExec = { _, _, _ -> throw DeployException("host_key", "Host key of kiosk does not match the pinned key (presented SHA256:x)") }
        assertTrue(exec(w, token, "kiosk", "echo hi").text.contains("does not match the pinned key"))
        val events = deployEvents(w, sessionId)
        assertEquals(5, events.size, "every operation past argument validation is recorded, failed ones too")
        assertEquals("Deploy [kiosk] `long`: timed out after 5 s in 5.0 s", events[0]["text"]!!.jsonPrimitive.content)
        assertEquals("true", events[0]["deploy"]!!.jsonObject["timedOut"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, events[0]["deploy"]!!.jsonObject["exitCode"])
        events[3].let {
            assertEquals("warn", it["level"]!!.jsonPrimitive.content)
            assertEquals("Deploy [kiosk] `echo hi`: could not connect: kiosk.example.net:22: Connection refused", it["text"]!!.jsonPrimitive.content)
            assertEquals("could not connect: kiosk.example.net:22: Connection refused", it["deploy"]!!.jsonObject["error"]!!.jsonPrimitive.content)
            assertEquals("echo hi", it["deploy"]!!.jsonObject["command"]!!.jsonPrimitive.content)
        }

        // One operation per target: a second is refused at once, another target is not affected.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        w.deployTransport.onExec = { _, cmd, _ ->
            if (cmd == "slow") { entered.countDown(); release.await(10, TimeUnit.SECONDS) }
            DeployExecResult(0, null, "", false, false, 5)
        }
        var slow: Mcp.Answer? = null
        val th = thread { slow = exec(w, token, "kiosk", "slow") }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        assertEquals(Mcp.Answer(true, "Another deploy operation is running on kiosk; try again when it has finished"), exec(w, token, "kiosk", "echo"))
        assertFalse(exec(w, token, "lab", "echo").isError)
        release.countDown()
        th.join(10_000)
        assertFalse(slow!!.isError)
        assertFalse(exec(w, token, "kiosk", "echo").isError)
    }

    @Test
    fun `copy sends a regular file inside the working directory and refuses everything else`() {
        val w = world { it.copy(deploy = DeployConfig(maxCopyBytes = 1024)) }
        ready(w)
        val (sessionId, token) = chat(w)
        val dir = mainDir(w)
        Files.createDirectories(dir.resolve("dist/sub"))
        val file = dir.resolve("dist/app.tar.gz")
        Files.write(file, ByteArray(300) { it.toByte() })
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-x---"))
        val ok = copy(w, token, "dist/app.tar.gz", "/opt/app/")
        assertFalse(ok.isError, ok.text)
        ok.json.let {
            assertEquals("kiosk", it["target"]!!.jsonPrimitive.content); assertEquals("/opt/app/", it["dest"]!!.jsonPrimitive.content)
            assertEquals("300", it["bytes"]!!.jsonPrimitive.content)
        }
        val sent = w.deployTransport.copies.single()
        assertEquals("app.tar.gz", sent.name); assertEquals(0b111_101_000, sent.mode); assertEquals(300, sent.content.size)
        // Absolute inside the directory is fine.
        assertFalse(copy(w, token, file.toString(), "/opt/app/app.tar.gz").isError)
        val ev = deployEvents(w, sessionId).first()
        assertEquals("Deploy [kiosk] copy dist/app.tar.gz → /opt/app/: 300 B in", ev["text"]!!.jsonPrimitive.content.substringBeforeLast(' ').substringBeforeLast(' '))
        ev["deploy"]!!.jsonObject.let {
            assertEquals("copy", it["kind"]!!.jsonPrimitive.content); assertEquals("dist/app.tar.gz", it["path"]!!.jsonPrimitive.content)
            assertEquals("/opt/app/", it["dest"]!!.jsonPrimitive.content); assertEquals("300", it["bytes"]!!.jsonPrimitive.content)
        }

        // Outside, through a symlink, a directory, too large, missing.
        val outside = Files.createTempFile("secret", ".txt").also { Files.writeString(it, "secret") }
        Files.createSymbolicLink(dir.resolve("link.txt"), outside)
        Files.write(dir.resolve("big.bin"), ByteArray(1025))
        for ((path, msg) in listOf(
            outside.toString() to "inside your working directory", "../../etc/passwd" to "", "link.txt" to "inside your working directory",
            "dist" to "pack it into an archive first", "big.bin" to "larger than 1.0 KB", "nope.txt" to "No such file", "" to "path is required",
        )) {
            val r = copy(w, token, path, "/opt/app/")
            assertTrue(r.isError && r.text.contains(msg), "$path: ${r.text}")
        }
        for (dest in listOf("/opt/my app/", "-r", "/opt/\$(id)", "/opt/a;b", "/opt/a`b`", "", "x".repeat(4097), "/opt/a\nb")) {
            val r = copy(w, token, "dist/app.tar.gz", dest)
            assertTrue(r.isError && r.text.startsWith("dest must be"), "$dest: ${r.text}")
        }
        assertEquals(2, w.deployTransport.copies.size, "refused copies never connect")
        assertEquals(2, deployEvents(w, sessionId).size)
        Files.deleteIfExists(outside)
    }

    @Test
    fun `a rotated key is used by the next operation`() {
        val w = world()
        ready(w)
        val (_, token) = chat(w)
        val root = w.user("root", admin = true)
        exec(w, token, "kiosk", "echo")
        val before = runBlocking { w.services.deploys.status(root, "demo-app").key!!.fingerprint }
        runBlocking { w.services.deploys.rotateKey(root, "demo-app") }
        val after = runBlocking { w.services.deploys.status(root, "demo-app").key!!.fingerprint }
        exec(w, token, "kiosk", "echo")
        assertEquals(listOf(before, after), w.deployTransport.identities)
        // The approval is not affected.
        assertEquals("approved", runBlocking { w.services.deploys.status(root, "demo-app").state })
        assertTrue(logs.list.any { it.formattedMessage.contains("rotated to $after by root") })
    }

    /** No tool answer, transcript event or log line contains the private key. */
    private fun assertNoPrivateKey(w: TestWorld, sessionId: UUID, answers: List<Mcp.Answer>) {
        val enc = w.services.db.queryOne("SELECT private_key_enc FROM deploy_keys WHERE repo = 'demo-app'") { it.getBytes(1) }!!
        val plain = w.services.box.decrypt(enc, "deploy-key:demo-app")
        val lines = plain.lines().filter { it.isNotBlank() && !it.startsWith("-----") }
        val texts = answers.map { it.text } + w.services.events.list(sessionId, 0, 10_000).map { it.payload.toString() } +
            logs.list.map { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") }
        for (t in texts) {
            assertFalse(t.contains("PRIVATE KEY"), t)
            for (l in lines) assertFalse(t.contains(l), "private key material in: ${t.take(200)}")
        }
    }

    private fun <T : Any> waitFor(timeoutMs: Long = 10_000, get: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { get()?.let { return it }; Thread.sleep(20) }
        error("timed out")
    }
}
