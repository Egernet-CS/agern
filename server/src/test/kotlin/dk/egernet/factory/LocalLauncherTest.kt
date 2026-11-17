package dk.egernet.factory

import dk.egernet.factory.agent.AgentIdentity
import dk.egernet.factory.agent.AgentProcess
import dk.egernet.factory.agent.LaunchSpec
import dk.egernet.factory.agent.LocalProcessLauncher
import dk.egernet.factory.github.ClaudeCredential
import dk.egernet.factory.pipeline.Schemas
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Runs the fake CLI (src/test/resources/fake-claude) through the real process launcher. */
class LocalLauncherTest {
    private val bin = Paths.get(javaClass.getResource("/fake-claude")!!.toURI()).toString()
    private val launcher = LocalProcessLauncher(bin, "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin")
    private val tmp = Files.createTempDirectory("agern-launch")

    private fun spec(interactive: Boolean, role: String, schema: String? = null, prompt: String? = null) = LaunchSpec(
        sessionId = UUID.randomUUID(), kind = role, model = "fake", workDir = tmp.resolve("work"), home = tmp.resolve("home"),
        identity = AgentIdentity("alice", "Alice", "alice", "ghp_x", ClaudeCredential("oauth_token", "sk-ant-oat-x")),
        interactive = interactive, appendSystemPrompt = "# Role: $role\n", prompt = prompt, jsonSchema = schema,
        mcpConfigJson = """{"mcpServers":{}}""",
    )

    private suspend fun readUntilResult(p: AgentProcess): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        for (line in p.lines) {
            val o = AppJson.parseToJsonElement(line).jsonObject
            out += o
            if (o["type"]?.jsonPrimitive?.contentOrNull == "result") break
        }
        return out
    }

    @Test
    fun `one-shot reviewer returns structured output`() = runBlocking {
        val p = launcher.launch(spec(false, "security", Schemas.REVIEW, "please review"))
        val lines = withTimeout(20_000) { readUntilResult(p) }
        assertEquals("init", lines.first()["subtype"]!!.jsonPrimitive.content)
        val result = lines.last()
        assertEquals("PASS", result["structured_output"]!!.jsonObject["verdict"]!!.jsonPrimitive.content)
        assertEquals(0, withTimeout(10_000) { p.awaitExit() })
        assertTrue(Files.list(tmp.resolve("home/.agern")).use { it.count() } == 0L, "MCP config file removed after exit")
        Unit
    }

    @Test
    fun `chat process gets a scratch environment and streams deltas`() = runBlocking {
        val p = launcher.launch(spec(true, "chat"))
        p.send("""{"type":"user","message":{"role":"user","content":"show env"}}""")
        val lines = withTimeout(20_000) { readUntilResult(p) }
        assertTrue(lines.any { it["type"]!!.jsonPrimitive.content == "stream_event" })
        val text = lines.last()["result"]!!.jsonPrimitive.content
        val names = text.substringAfter("ENV: ").trim().split(',').toSet()
        val allowed = setOf(
            "PATH", "HOME", "LANG", "TERM", "CLAUDE_CODE_OAUTH_TOKEN", "GIT_TERMINAL_PROMPT", "GIT_AUTHOR_NAME", "GIT_COMMITTER_NAME",
            "GIT_AUTHOR_EMAIL", "GIT_COMMITTER_EMAIL", "GIT_CONFIG_COUNT", "GIT_CONFIG_KEY_0", "GIT_CONFIG_VALUE_0", "GH_TOKEN",
            "__CF_USER_TEXT_ENCODING", // added by macOS to every process
        )
        assertTrue(allowed.containsAll(names), "unexpected env vars: ${names - allowed}")
        assertTrue("CLAUDE_CODE_OAUTH_TOKEN" in names)
        p.closeInput()
        assertNotNull(withTimeout(10_000) { p.awaitExit() })
        Unit
    }
}
