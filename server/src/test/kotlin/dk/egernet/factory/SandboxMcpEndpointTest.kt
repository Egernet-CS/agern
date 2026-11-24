package dk.egernet.factory

import dk.egernet.factory.agent.LocalProcessLauncher
import dk.egernet.factory.sandbox.SandboxDefinitions
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import dk.egernet.factory.mcp.McpGrant
import dk.egernet.factory.mcp.McpServer
import dk.egernet.factory.sandbox.ExecResult
import dk.egernet.factory.sandbox.SandboxManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole path a real agent takes: the fake CLI (src/test/resources/fake-claude) runs as a child process, reads its
 * `--mcp-config` and calls `factory_sandbox_info` / `factory_sandbox_exec` / `factory_sandbox_wait` over HTTP on the
 * server's loopback MCP endpoint; the server runs the command in the run's sandbox (the fake runtime).
 *
 * The fake CLI gives up on a socket that stays silent for 10 s, like a real client's first-byte and idle budgets. Slow
 * calls here take ~12 s; with the stream threshold and progress interval shortened they still arrive in one call.
 */
class SandboxMcpEndpointTest {
    private val port = ServerSocket(0).use { it.localPort }
    private val bin = Paths.get(javaClass.getResource("/fake-claude")!!.toURI()).toString()
    private val world = TestWorld(
        sandbox = true,
        tweak = { it.copy(port = port, host = "127.0.0.1", mcpStreamAfterMs = 200, mcpProgressEveryMs = 500) },
        launcher = LocalProcessLauncher(bin, System.getenv("PATH") ?: "/usr/local/bin:/usr/bin:/bin"),
    )
    private val server = embeddedServer(Netty, configure = { connector { host = "127.0.0.1"; this.port = this@SandboxMcpEndpointTest.port } }) {
        agernModule(world.services)
    }.start(wait = false)

    @AfterTest fun tearDown() {
        server.stop(100, 1000)
        world.close()
    }

    @Test
    fun `a builder process runs a command in its sandbox through the MCP endpoint`() {
        val def = "image: docker.io/library/debian:12\n"
        world.sandboxFile(def)
        val root = world.user("root", admin = true)
        runBlocking { world.services.sandboxes.approve(root, "demo-app", SandboxDefinitions.sha256(def)) }
        val issue = world.github.addIssue("demo-app", "Build in the sandbox", "Please build it. fake:sandbox")
        val alice = world.user("alice")
        val run = runBlocking { world.services.orchestrator.start(alice, "demo-app", issue.number, null) }
        val done = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN, timeoutMs = 60_000)
        assertEquals(RunStatus.MERGED, done.status, done.error)

        val rt = world.sandboxRuntime
        assertEquals(listOf("agern-run-${run.id}" to "echo built in the sandbox && ls"), rt.execs.map { it.first to it.second })
        assertTrue(rt.containers.isEmpty(), "removed after the merge")

        // The builder's transcript shows the tool calls and their answers like any other tool round.
        val builder = world.services.runs.stages(run.id).single { it.role == "builder" }.sessionId!!
        val events = world.services.events.list(builder, 0, 1000)
        val toolResults = events.filter { it.type == "claude" && it.payload.jsonObject["type"]!!.jsonPrimitive.content == "user" }
            .flatMap { it.payload.jsonObject["message"]!!.jsonObject["content"]!!.jsonArray }
            .map { it.jsonObject["content"]!!.jsonPrimitive.content }
        val info = AppJson.parseToJsonElement(toolResults.single { it.contains("\"available\"") }).jsonObject
        assertEquals("true", info["available"]!!.jsonPrimitive.content)
        val exec = AppJson.parseToJsonElement(toolResults.single { it.contains("\"exitCode\"") }).jsonObject
        assertEquals("0", exec["exitCode"]!!.jsonPrimitive.content)
        assertEquals("ran: echo built in the sandbox && ls\n", exec["output"]!!.jsonPrimitive.content)
        val result = events.last { it.type == "claude" && it.payload.jsonObject["type"]!!.jsonPrimitive.content == "result" }
        assertTrue(result.payload.jsonObject["result"]!!.jsonPrimitive.content.contains("Sandbox exec: {\"handle\":\"cmd-"))
    }

    private val plain = "image: docker.io/library/debian:12\n"

    /** Starts a run of an issue whose body has [trigger] and returns it; the sandbox definition is approved. */
    private fun startRun(trigger: String): dk.egernet.factory.db.RunRecord {
        world.sandboxFile(plain)
        val root = world.user("root", admin = true)
        runBlocking { world.services.sandboxes.approve(root, "demo-app", SandboxDefinitions.sha256(plain)) }
        val issue = world.github.addIssue("demo-app", "Long build", "Please build it. $trigger")
        return runBlocking { world.services.orchestrator.start(world.user("alice"), "demo-app", issue.number, null) }
    }

    private fun builderResult(runId: java.util.UUID): String {
        val builder = world.services.runs.stages(runId).single { it.role == "builder" }.sessionId!!
        val result = world.services.events.list(builder, 0, 1000)
            .last { it.type == "claude" && it.payload.jsonObject["type"]!!.jsonPrimitive.content == "result" }
        return result.payload.jsonObject["result"]!!.jsonPrimitive.content
    }

    /** The JSON after "<label>: " on a line of the builder's result. */
    private fun reported(result: String, label: String) =
        AppJson.parseToJsonElement(result.lines().single { it.startsWith("$label: ") }.removePrefix("$label: ")).jsonObject

    private fun slowSleep() {
        world.sandboxRuntime.onExec = { _, cmd, _ ->
            if (cmd == "sleep long") Thread.sleep(12_000)
            ExecResult(7, "ran: $cmd\n", false, false, 12_000)
        }
    }

    @Test
    fun `a foreground command that outlasts the client's read timeout returns its result in one call`() {
        slowSleep()
        val run = startRun("fake:sandbox-long")
        val done = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN, timeoutMs = 90_000)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        val exec = reported(builderResult(run.id), "Sandbox exec")
        assertEquals(7, exec["exitCode"]!!.jsonPrimitive.content.toInt())
        assertEquals("ran: sleep long\n", exec["output"]!!.jsonPrimitive.content)
        assertTrue(exec["handle"]!!.jsonPrimitive.content.startsWith("cmd-"))
    }

    @Test
    fun `a background command and factory_sandbox_wait return the result through the wait`() {
        slowSleep()
        val run = startRun("fake:sandbox-background")
        val done = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN, timeoutMs = 90_000)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        val result = builderResult(run.id)
        val started = reported(result, "Sandbox start")
        assertEquals("true", started["running"]!!.jsonPrimitive.content)
        val waited = reported(result, "Sandbox wait")
        assertEquals(started["handle"], waited["handle"])
        assertEquals("false", waited["running"]!!.jsonPrimitive.content)
        assertEquals(7, waited["exitCode"]!!.jsonPrimitive.content.toInt())
        assertEquals("ran: sleep long\n", waited["output"]!!.jsonPrimitive.content)
        assertEquals("false", waited["timedOut"]!!.jsonPrimitive.content)
        assertEquals(12_000L, waited["durationMs"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `slow calls are streamed with progress notifications, fast ones and non-streaming clients get JSON`() {
        val gate = java.util.concurrent.CountDownLatch(1)
        world.sandboxRuntime.onExec = { _, cmd, _ ->
            if (cmd == "sleep long") gate.await(30, java.util.concurrent.TimeUnit.SECONDS)
            ExecResult(0, "ran: $cmd\n", false, false, 3)
        }
        val run = startRun("fake:sandbox-long")
        val runId = run.id
        runBlocking { withTimeout(30_000) { while (world.sandboxRuntime.execs.none { it.second == "sleep long" }) delay(20) } }
        val builder = world.services.runs.stages(runId).single { it.role == "builder" }.sessionId!!
        val alice = world.user("alice")
        val token = world.services.mcpRegistry.issue(McpGrant(builder, alice.id, "demo-app", runId, SandboxManager.TOOLS))
        val http = HttpClient.newHttpClient()
        fun post(body: String, accept: String) = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/mcp"))
            .header("Authorization", "Bearer $token").header("Content-Type", "application/json").header("Accept", accept)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val waitCall = """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"factory_sandbox_wait","arguments":{},"_meta":{"progressToken":"tok-1"}}}"""

        // Fast: JSON as before.
        val fast = http.send(post("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", "application/json, text/event-stream"), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, fast.statusCode())
        assertEquals("application/json", fast.headers().firstValue("Content-Type").get().substringBefore(';'))

        // Slow, from a client that does not take a stream: one JSON body at the end.
        val plainJson = http.sendAsync(post(waitCall, "application/json"), HttpResponse.BodyHandlers.ofString())
        // Slow, streaming, without a progress token: keep-alive comments instead.
        val noToken = http.sendAsync(post(waitCall.replace(""","_meta":{"progressToken":"tok-1"}""", ""), "text/event-stream"), HttpResponse.BodyHandlers.ofString())
        // Slow, streaming: the first bytes come before the call ends.
        val streamed = http.send(post(waitCall, "application/json, text/event-stream"), HttpResponse.BodyHandlers.ofInputStream())
        assertEquals(200, streamed.statusCode())
        assertEquals("text/event-stream", streamed.headers().firstValue("Content-Type").get().substringBefore(';'))
        assertEquals("no-cache", streamed.headers().firstValue("Cache-Control").get())
        val reader = streamed.body().bufferedReader()
        assertEquals(": agern", reader.readLine())
        assertEquals(1L, gate.count, "the call is still running")
        val lines = mutableListOf<String>()
        while (lines.count { it.contains("notifications/progress") } < 3) lines += reader.readLine() ?: break
        gate.countDown()
        while (true) lines += reader.readLine() ?: break

        val data = lines.filter { it.startsWith("data: ") }.map { AppJson.parseToJsonElement(it.removePrefix("data: ")).jsonObject }
        val progress = data.filter { it["method"]?.jsonPrimitive?.content == "notifications/progress" }.map { it["params"]!!.jsonObject }
        assertTrue(progress.size >= 3, lines.toString())
        assertTrue(progress.all { it["progressToken"]!!.jsonPrimitive.content == "tok-1" && it["progressToken"]!!.jsonPrimitive.isString })
        val values = progress.map { it["progress"]!!.jsonPrimitive.content.toLong() }
        assertEquals(values.sorted().distinct(), values, "strictly increasing")
        assertTrue(progress.first()["message"]!!.jsonPrimitive.content.startsWith("Still running ("))
        val answer = data.last()
        assertEquals("event: message", lines[lines.indexOfLast { it.startsWith("data: ") } - 1])
        assertEquals(9, answer["id"]!!.jsonPrimitive.content.toInt())
        val text = answer["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("ran: sleep long\n", AppJson.parseToJsonElement(text).jsonObject["output"]!!.jsonPrimitive.content)

        val other = plainJson.get(30, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(200, other.statusCode())
        assertEquals("application/json", other.headers().firstValue("Content-Type").get().substringBefore(';'))
        assertEquals(answer, AppJson.parseToJsonElement(other.body()).jsonObject, "the same JSON-RPC response")

        val kept = noToken.get(30, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals("text/event-stream", kept.headers().firstValue("Content-Type").get().substringBefore(';'))
        assertTrue(kept.body().startsWith(": agern\n\n") && kept.body().contains(": keep-alive\n\n"), kept.body())
        assertFalse(kept.body().contains("notifications/progress"))
        assertEquals(answer, AppJson.parseToJsonElement(kept.body().lines().single { it.startsWith("data: ") }.removePrefix("data: ")).jsonObject)

        assertEquals(RunStatus.MERGED, world.awaitRun(runId, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN, timeoutMs = 60_000).status)
    }

    @Test
    fun `progress tokens are taken only from a single request's _meta`() {
        assertEquals(JsonPrimitive("a"), McpServer.progressToken("""{"id":1,"params":{"_meta":{"progressToken":"a"}}}"""))
        assertEquals(JsonPrimitive(5), McpServer.progressToken("""{"id":1,"params":{"_meta":{"progressToken":5}}}"""))
        for (body in listOf("""[{"id":1,"params":{"_meta":{"progressToken":"a"}}}]""", """{"id":1,"params":{"_meta":{"progressToken":{"x":1}}}}""",
            """{"id":1,"params":{"_meta":{"progressToken":null}}}""", """{"id":1,"params":{}}""", "not json", """{"id":1,"params":{"_meta":{"progressToken":true}}}""")) {
            assertNull(McpServer.progressToken(body), body)
        }
    }
}
