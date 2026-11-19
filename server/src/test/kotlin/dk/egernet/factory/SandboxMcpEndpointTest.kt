package dk.egernet.factory

import dk.egernet.factory.agent.LocalProcessLauncher
import dk.egernet.factory.sandbox.SandboxDefinitions
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole path a real agent takes: the fake CLI (src/test/resources/fake-claude) runs as a child process, reads its
 * `--mcp-config` and calls `factory_sandbox_info` / `factory_sandbox_exec` over HTTP on the server's loopback MCP
 * endpoint; the server runs the command in the run's sandbox (the fake runtime).
 */
class SandboxMcpEndpointTest {
    private val port = ServerSocket(0).use { it.localPort }
    private val bin = Paths.get(javaClass.getResource("/fake-claude")!!.toURI()).toString()
    private val world = TestWorld(
        sandbox = true,
        tweak = { it.copy(port = port, host = "127.0.0.1") },
        launcher = LocalProcessLauncher(bin, System.getenv("PATH") ?: "/usr/local/bin:/usr/bin:/bin"),
    )
    private val server = embeddedServer(Netty, configure = { connector { host = "127.0.0.1"; this.port = this@SandboxMcpEndpointTest.port } }) {
        agernModule(world.services)
    }.start(wait = false)

    @AfterTest fun tearDown() {
        server.stop(100, 1000)
        world.closeAndReleaseDb()
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
        assertTrue(result.payload.jsonObject["result"]!!.jsonPrimitive.content.contains("Sandbox exec: {\"exitCode\":0"))
    }
}
