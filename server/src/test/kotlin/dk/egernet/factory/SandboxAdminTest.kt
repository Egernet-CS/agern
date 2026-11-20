package dk.egernet.factory

import dk.egernet.factory.agent.FeedItem
import dk.egernet.factory.github.FakeGitHub
import dk.egernet.factory.sandbox.DevFakeSandboxRuntime
import dk.egernet.factory.sandbox.SANDBOX_FILE
import dk.egernet.factory.sandbox.SandboxDefinitions
import dk.egernet.factory.sandbox.SandboxManager
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Admin view of sandboxes (list, stop, pending definitions), the approval feed frame and the dev fake mode. */
class SandboxAdminTest {
    private val world = TestWorld(twoFactor = false, sandbox = true)
    private val s = world.services

    @AfterTest fun tearDown() = world.closeAndReleaseDb()

    private val definition = "image: docker.io/library/node:22\nsetup: echo setting up\nservices:\n  db:\n    image: docker.io/library/postgres:17\nresources: { cpus: 1, memory: 1g }\n"

    private fun approve(content: String = definition) {
        val root = world.user("root", admin = true)
        runBlocking { s.sandboxes.approve(root, "demo-app", SandboxDefinitions.sha256(content)) }
    }

    private fun token(p: FakeProcess): String = AppJson.parseToJsonElement(p.spec.mcpConfigJson).jsonObject["mcpServers"]!!.jsonObject["factory"]!!
        .jsonObject["headers"]!!.jsonObject["Authorization"]!!.jsonPrimitive.content.removePrefix("Bearer ")

    private fun exec(p: FakeProcess, command: String): Pair<Boolean, String> {
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
            putJsonObject("params") { put("name", "factory_sandbox_exec"); putJsonObject("arguments") { put("command", command) } }
        }
        val res = AppJson.parseToJsonElement(runBlocking { s.mcp.handle(token(p), body.toString()) }.second!!).jsonObject["result"]!!.jsonObject
        return (res["isError"]?.jsonPrimitive?.booleanOrNull == true) to res["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
    }

    private fun script(builder: (FakeProcess) -> String) {
        world.launcher.stage = { p ->
            when (p.spec.kind) {
                "spec-writer" -> { p.init(); p.result("## Spec"); p.exit(0) }
                "builder" -> { p.init(); val t = builder(p); p.result(t); p.exit(0) }
                "approver" -> { p.init(); p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") }); p.exit(0) }
                else -> { p.init(); p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) }); p.exit(0) }
            }
        }
    }

    @Test
    fun `admins list running sandboxes and stop one, the run continues and the next command recreates it`() {
        world.sandboxFile(definition)
        approve()
        val root = world.user("root", admin = true)
        val rt = world.sandboxRuntime
        val created = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val answers = mutableListOf<Pair<Boolean, String>>()
        script { p ->
            answers += exec(p, "make build")
            created.countDown()
            stopped.await(10, TimeUnit.SECONDS)
            answers += exec(p, "make test")
            "built"
        }
        val run = runBlocking { s.orchestrator.start(world.user("alice"), "demo-app", 1, null) }
        assertTrue(created.await(10, TimeUnit.SECONDS))

        val overview = runBlocking { s.sandboxes.overview(root) }
        assertTrue(overview.enabled)
        assertEquals("podman", overview.runtime)
        assertEquals(1, overview.inUse)
        assertEquals(2, overview.limits.maxConcurrent)
        val sb = overview.sandboxes.single()
        assertEquals(run.id.toString(), sb.runId)
        assertEquals("demo-app", sb.repo)
        assertEquals("alice", sb.owner.username)
        assertEquals("docker.io/library/node:22", sb.image)
        assertEquals(listOf("db"), sb.services)
        assertEquals(SandboxDefinitions.sha256(definition), sb.sha256)
        assertEquals("1g", sb.memory)
        assertEquals(1.0, sb.cpus)
        assertTrue(sb.visible)

        // Stop: containers and network go, the slot is freed, the builder's transcript says so.
        runBlocking { s.sandboxes.stop(root, run.id) }
        assertTrue(rt.containers.isEmpty() && rt.networks.isEmpty(), rt.calls.toString())
        assertEquals(0, s.sandboxes.inUse())
        assertTrue(runBlocking { s.sandboxes.overview(root) }.sandboxes.isEmpty())
        assertFailsWith<ApiException> { runBlocking { s.sandboxes.stop(root, run.id) } }.also { assertEquals(404, it.status.value) }
        stopped.countDown()

        // The run goes on; the next command created a fresh sandbox (setup ran again) and it is removed at the end.
        val done = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertEquals(listOf(false, false), answers.map { it.first }, answers.toString())
        assertEquals(listOf("echo setting up", "make build", "echo setting up", "make test"), rt.execs.map { it.second })
        val builder = s.runs.stages(run.id).single { it.role == "builder" }.sessionId!!
        val texts = s.events.list(builder, 0, 1000).filter { it.type == "system" }.map { it.payload.jsonObject["text"]!!.jsonPrimitive.content }
        assertTrue(texts.any { it.startsWith("An admin (root) stopped this run's sandbox") }, texts.toString())
        assertEquals(0, s.sandboxes.inUse())
    }

    @Test
    fun `the overview shows platform containers, their slots and memory, and admins get live frames`() {
        val withPlatform = definition + "platforms:\n  node20:\n    image: docker.io/library/node:20\n"
        world.sandboxFile(withPlatform)
        approve(withPlatform)
        val root = world.user("root", admin = true)
        val rt = world.sandboxRuntime
        val frames = java.util.concurrent.CopyOnWriteArrayList<String>()
        val collector = s.scope.async(start = CoroutineStart.UNDISPATCHED) {
            s.bus.feed.filterIsInstance<FeedItem.Sandboxes>().collect { frames += it.change }
        }
        val both = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        script { p ->
            exec(p, "npm test")
            val body = buildJsonObject {
                put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
                putJsonObject("params") { put("name", "factory_sandbox_exec"); putJsonObject("arguments") { put("command", "npm test"); put("platform", "node20") } }
            }
            runBlocking { s.mcp.handle(token(p), body.toString()) }
            both.countDown()
            stopped.await(10, TimeUnit.SECONDS)
            "built"
        }
        val run = runBlocking { s.orchestrator.start(world.user("alice"), "demo-app", 1, null) }
        assertTrue(both.await(10, TimeUnit.SECONDS))
        val sb = runBlocking { s.sandboxes.overview(root) }.sandboxes.single()
        assertEquals(listOf("default", "node20"), sb.containers.map { it.platform })
        assertEquals(listOf("docker.io/library/node:22", "docker.io/library/node:20"), sb.containers.map { it.image })
        assertEquals(listOf("node20"), sb.platforms)
        assertEquals(2, sb.slots)
        assertEquals("3g", sb.memoryTotal, "1g each: two command containers and one service")
        assertEquals(2, runBlocking { s.sandboxes.overview(root) }.inUse)
        assertEquals(4, runBlocking { s.sandboxes.overview(root) }.limits.maxPlatforms)

        // Stop removes the platform container too and frees both slots.
        runBlocking { s.sandboxes.stop(root, run.id) }
        assertTrue(rt.containers.isEmpty(), rt.calls.toString())
        assertTrue(rt.calls.contains("rm agern-run-${run.id} agern-run-${run.id}--node20 agern-run-${run.id}-db"), rt.calls.toString())
        assertEquals(0, s.sandboxes.inUse())
        stopped.countDown()
        world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        runBlocking { withTimeout(5_000) { while (frames.size < 3) kotlinx.coroutines.delay(20) } }
        collector.cancel()
        assertEquals(listOf("created", "created", "stopped"), frames.take(3))
    }

    @Test
    fun `a command running while an admin stops the sandbox ends with an error saying so`() {
        world.sandboxFile(definition)
        approve()
        val root = world.user("root", admin = true)
        val inCommand = CountDownLatch(1)
        val release = CountDownLatch(1)
        world.sandboxRuntime.onExec = { _, cmd, _ ->
            if (cmd == "long") { inCommand.countDown(); release.await(10, TimeUnit.SECONDS) }
            dk.egernet.factory.sandbox.ExecResult(0, "ok\n", false, false, 5)
        }
        var answer: Pair<Boolean, String>? = null
        script { p -> answer = exec(p, "long"); "built" }
        val run = runBlocking { s.orchestrator.start(world.user("alice"), "demo-app", 1, null) }
        assertTrue(inCommand.await(10, TimeUnit.SECONDS))
        runBlocking { s.sandboxes.stop(root, run.id) }
        release.countDown()
        world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertTrue(answer!!.first && answer!!.second.contains("An admin stopped this run's sandbox while the command ran"), answer.toString())
    }

    @Test
    fun `pending definitions, the approval feed frame and admin-only routes`() = testApplication {
        application { agernModule(s) }
        val root = login("root", "pw-r")
        val alice = login("alice", "pw-a")
        assertEquals("[]", root.get("/api/admin/sandboxes/pending").bodyAsText())
        // Everyone who can see the project learns who can approve.
        AppJson.parseToJsonElement(alice.get("/api/projects/demo-app/sandbox").bodyAsText()).jsonObject["approvers"]!!.jsonArray.let {
            assertEquals(listOf("root"), it.map { o -> o.jsonObject["username"]!!.jsonPrimitive.content })
        }

        world.sandboxFile(definition)
        val sha1 = SandboxDefinitions.sha256(definition)
        root.get("/api/admin/sandboxes/pending").bodyAsText().let { body ->
            val list = AppJson.parseToJsonElement(body).jsonArray
            assertEquals(1, list.size, body)
            val p = list[0].jsonObject
            assertEquals("demo-app", p["repo"]!!.jsonPrimitive.content)
            assertEquals(sha1, p["sha256"]!!.jsonPrimitive.content)
            assertEquals("docker.io/library/node:22", p["image"]!!.jsonPrimitive.content)
            assertEquals(kotlinx.serialization.json.JsonNull, p["approvedSha256"])
        }

        // Approving emits a `sandbox_approval` feed item with the approval; it leaves the pending list.
        val frame = s.scope.async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { s.bus.feed.filterIsInstance<FeedItem.SandboxApproval>().first() } }
        assertEquals(200, root.post("/api/projects/demo-app/sandbox/approval") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"sha256":"$sha1"}""")
        }.status.value)
        frame.await().let {
            assertEquals("demo-app", it.repo)
            assertEquals(sha1, it.approval!!.sha256)
            assertEquals("root", it.approval!!.approvedBy.username)
        }
        assertEquals("[]", root.get("/api/admin/sandboxes/pending").bodyAsText())

        // A changed file is pending again, naming the approved version.
        val v2 = definition + "caches: [ /root/.npm ]\n"
        world.sandboxFile(v2)
        AppJson.parseToJsonElement(root.get("/api/admin/sandboxes/pending").bodyAsText()).jsonArray.single().jsonObject.let {
            assertEquals(SandboxDefinitions.sha256(v2), it["sha256"]!!.jsonPrimitive.content)
            assertEquals(sha1, it["approvedSha256"]!!.jsonPrimitive.content)
        }

        // Revoking emits the frame with a null approval.
        val revoked = s.scope.async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { s.bus.feed.filterIsInstance<FeedItem.SandboxApproval>().first() } }
        assertEquals(200, root.delete("/api/projects/demo-app/sandbox/approval") { header("X-Factory-Request", "1") }.status.value)
        assertNull(revoked.await().approval)

        // The overview and stop are admin only; stopping a run without a sandbox is 404.
        val overview = AppJson.parseToJsonElement(root.get("/api/admin/sandboxes").bodyAsText()).jsonObject
        assertEquals(true, overview["enabled"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(0, overview["sandboxes"]!!.jsonArray.size)
        assertEquals("4g", overview["limits"]!!.jsonObject["maxMemory"]!!.jsonPrimitive.content)
        assertEquals(403, alice.get("/api/admin/sandboxes").status.value)
        assertEquals(403, alice.get("/api/admin/sandboxes/pending").status.value)
        val someRun = java.util.UUID.randomUUID()
        assertEquals(403, alice.delete("/api/admin/sandboxes/$someRun") { header("X-Factory-Request", "1") }.status.value)
        assertEquals(404, root.delete("/api/admin/sandboxes/$someRun") { header("X-Factory-Request", "1") }.status.value)
    }

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String): HttpClient {
        val c = createClient { install(HttpCookies) }
        assertEquals(200, c.post("/api/auth/login") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
        }.status.value)
        c.put("/api/me/github-token") { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"token":"fake-$user"}""") }
        return c
    }

    @Test
    fun `FACTORY_SANDBOX=fake needs the fake GitHub, which serves files from its directory`() {
        val base = mapOf(
            "FACTORY_MASTER_KEY" to "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "FACTORY_GITHUB_ORG" to "o", "FACTORY_AUTH" to "dev",
            "FACTORY_SANDBOX" to "fake",
        )
        assertFailsWith<IllegalArgumentException> { Config.fromEnv(base) }
        assertEquals("fake", Config.fromEnv(base + ("FACTORY_DEV_FAKE_GITHUB" to "true")).sandbox.mode)

        val gh = FakeGitHub.seeded("o")
        val dir = Files.createTempDirectory("fake-gh")
        gh.filesDir = dir
        assertNull(gh.fileOf("demo-app", SANDBOX_FILE))
        Files.createDirectories(dir.resolve("demo-app/.agern"))
        Files.writeString(dir.resolve("demo-app/$SANDBOX_FILE"), definition)
        assertEquals(definition, gh.fileOf("demo-app", SANDBOX_FILE))
        assertEquals(definition, gh.client("fake-bob").getFile("o", "demo-app", SANDBOX_FILE, "main"))
        assertNull(gh.fileOf("demo-app", "../demo-app/$SANDBOX_FILE"))
    }

    @Test
    fun `the development fake runtime answers a few commands without running anything`() {
        val rt = DevFakeSandboxRuntime()
        val wt = Files.createTempDirectory("wt").also { Files.writeString(it.resolve("README.md"), "x") }
        rt.startContainer(SandboxManager.mainSpec(java.util.UUID.randomUUID(), "demo-app", wt,
            (SandboxDefinitions.parse("image: docker.io/library/debian:12\n", SandboxConfig()) as dk.egernet.factory.sandbox.ParsedDefinition.Valid).definition))
        val name = rt.listContainers().single().name
        val r = rt.exec(name, listOf("sh", "-lc", "echo hello world && ls && seq 3"), "/workspace", 10_000)
        assertEquals("hello world\nREADME.md\n1\n2\n3\n", r.output)
        assertEquals(0, r.exitCode)
        assertEquals(2, rt.exec(name, listOf("sh", "-lc", "echo a; exit 2"), "/workspace", 10_000).exitCode)
        assertTrue(rt.exec(name, listOf("sh", "-lc", "sleep 5"), "/workspace", 50).timedOut)
        assertTrue(rt.exec(name, listOf("sh", "-lc", "./gradlew test"), "/workspace", 1000).output.contains("would run: ./gradlew test"))
        rt.removeContainers(listOf(name))
        assertFalse(rt.listContainers().any())
    }
}
