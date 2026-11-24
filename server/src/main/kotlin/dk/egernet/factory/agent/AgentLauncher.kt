package dk.egernet.factory.agent

import dk.egernet.factory.github.ClaudeCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Who an agent runs as. Holds secrets: never log it (toString is redacted). */
data class AgentIdentity(
    val username: String,
    val displayName: String,
    val githubLogin: String?,
    val githubToken: String?,
    val claude: ClaudeCredential,
) {
    override fun toString() = "AgentIdentity($username)"
}

data class LaunchSpec(
    val sessionId: UUID,
    /** `chat` or a pipeline role. */
    val kind: String,
    val model: String,
    val workDir: Path,
    val home: Path,
    val identity: AgentIdentity,
    /** Chat: `--input-format stream-json --include-partial-messages`, process stays alive between turns. */
    val interactive: Boolean,
    /**
     * Pipeline stage that accepts messages from its owner while it works (spec-writer, builder):
     * `--input-format stream-json --replay-user-messages`. The task prompt is NOT written by the launcher; the
     * caller sends it as the first stream-json user message and closes stdin when the stage is finished.
     */
    val steerable: Boolean = false,
    val resumeClaudeSessionId: String? = null,
    val appendSystemPrompt: String? = null,
    /** Task prompt of a pipeline stage. One-shot stages: written to stdin as text, which is then closed. Null for chats. */
    val prompt: String? = null,
    val jsonSchema: String? = null,
    val disallowedTools: List<String> = emptyList(),
    /** Full JSON for `--mcp-config` (contains the per-process bearer token). */
    val mcpConfigJson: String,
    /** Host prefix for git's http.extraheader, e.g. `https://github.com/`. */
    val gitHost: String = "https://github.com/",
    /**
     * The CLI's MCP tool-call and idle timeouts in milliseconds ([dk.egernet.factory.Config.mcpToolTimeoutMs]), so a
     * long sandbox command is not abandoned by the client (docs/ARCHITECTURE.md §6 "Agent environment").
     */
    val mcpToolTimeoutMs: Long = DEFAULT_MCP_TOOL_TIMEOUT_MS,
) {
    companion object {
        /** What `Config()` with its defaults gives: the 60-minute stage limit plus one minute. */
        const val DEFAULT_MCP_TOOL_TIMEOUT_MS = 61 * 60_000L
    }
}

/** A running agent process. */
interface AgentProcess {
    /** stdout, one JSON object per line. Closed when the process exits. */
    val lines: ReceiveChannel<String>
    /** Writes one line to stdin (interactive sessions). */
    fun send(line: String)
    fun closeInput()
    fun kill()
    suspend fun awaitExit(): Int
    /** Last part of stderr, for error messages. */
    fun stderrTail(): String
}

/** Starts agent processes. The local launcher can later be replaced by a container- or sudo-based one. */
interface AgentLauncher {
    fun launch(spec: LaunchSpec): AgentProcess
}

object AgentCommand {
    /** Command line for the Claude CLI. The MCP config is passed as a file path so the token is not in argv. */
    fun args(claudeBin: String, spec: LaunchSpec, mcpConfigPath: String): List<String> {
        val a = mutableListOf(
            claudeBin, "-p",
            "--output-format", "stream-json", "--verbose",
            "--model", spec.model,
            "--permission-mode", "bypassPermissions",
            "--strict-mcp-config",
            "--mcp-config", mcpConfigPath,
        )
        if (spec.interactive) a += listOf("--input-format", "stream-json", "--include-partial-messages")
        else if (spec.steerable) a += listOf("--input-format", "stream-json", "--replay-user-messages")
        spec.resumeClaudeSessionId?.let { a += listOf("--resume", it) }
        spec.appendSystemPrompt?.let { a += listOf("--append-system-prompt", it) }
        spec.jsonSchema?.let { a += listOf("--json-schema", it) }
        if (spec.disallowedTools.isNotEmpty()) a += listOf("--disallowedTools", spec.disallowedTools.joinToString(","))
        return a
    }

    /**
     * The agent environment, built from scratch. Nothing from the server's environment is inherited
     * (it contains the master key and DB password) except the PATH value.
     */
    fun environment(spec: LaunchSpec, path: String): Map<String, String> {
        val env = linkedMapOf(
            "PATH" to path,
            "HOME" to spec.home.toString(),
            "LANG" to "en_US.UTF-8",
            "TERM" to "dumb",
            // Claude CLI (checked against 2.1.328): both in milliseconds. Without them the client gives up on a slow
            // tool call (a build in the sandbox) while the server still runs it.
            "MCP_TOOL_TIMEOUT" to spec.mcpToolTimeoutMs.toString(),
            "CLAUDE_CODE_MCP_TOOL_IDLE_TIMEOUT" to spec.mcpToolTimeoutMs.toString(),
        )
        when (spec.identity.claude.kind) {
            "api_key" -> env["ANTHROPIC_API_KEY"] = spec.identity.claude.value
            else -> env["CLAUDE_CODE_OAUTH_TOKEN"] = spec.identity.claude.value
        }
        env += gitEnvironment(spec.identity, spec.gitHost)
        return env
    }

    /** Git authentication through environment-only config; nothing is written to .git/config or URLs. */
    fun gitEnvironment(id: AgentIdentity, gitHost: String): Map<String, String> {
        val env = linkedMapOf(
            "GIT_TERMINAL_PROMPT" to "0",
            "GIT_AUTHOR_NAME" to id.displayName,
            "GIT_COMMITTER_NAME" to id.displayName,
            "GIT_AUTHOR_EMAIL" to authorEmail(id),
            "GIT_COMMITTER_EMAIL" to authorEmail(id),
        )
        id.githubToken?.let { env.putAll(gitTokenEnvironment(it, gitHost)) }
        return env
    }

    /** The part of [gitEnvironment] that authenticates with a GitHub [token] (also used without an agent identity). */
    fun gitTokenEnvironment(token: String, gitHost: String): Map<String, String> {
        val basic = Base64.getEncoder().encodeToString("x-access-token:$token".toByteArray())
        return linkedMapOf(
            "GIT_CONFIG_COUNT" to "1",
            "GIT_CONFIG_KEY_0" to "http.$gitHost.extraheader",
            "GIT_CONFIG_VALUE_0" to "AUTHORIZATION: basic $basic",
            "GH_TOKEN" to token,
        )
    }

    private fun authorEmail(id: AgentIdentity) =
        if (id.githubLogin != null) "${id.githubLogin}@users.noreply.github.com" else "${id.username}@agern.local"
}

/** Runs the Claude CLI as a child process of the server. */
class LocalProcessLauncher(private val claudeBin: String, private val agentPath: String) : AgentLauncher {
    private val log = LoggerFactory.getLogger(LocalProcessLauncher::class.java)

    override fun launch(spec: LaunchSpec): AgentProcess {
        Files.createDirectories(spec.home)
        Files.createDirectories(spec.workDir)
        val mcpDir = spec.home.resolve(".agern").also { Files.createDirectories(it) }
        val mcpFile = mcpDir.resolve("mcp-${spec.sessionId}-${UUID.randomUUID()}.json")
        Files.createFile(mcpFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.writeString(mcpFile, spec.mcpConfigJson)

        val pb = ProcessBuilder(AgentCommand.args(claudeBin, spec, mcpFile.toString()))
            .directory(spec.workDir.toFile())
        pb.environment().clear()
        pb.environment().putAll(AgentCommand.environment(spec, agentPath))
        log.info("Launching agent session={} kind={} model={} resume={} cwd={}", spec.sessionId, spec.kind, spec.model,
            spec.resumeClaudeSessionId != null, spec.workDir)
        val proc = try {
            pb.start()
        } catch (e: Exception) {
            Files.deleteIfExists(mcpFile)
            throw e
        }
        return LocalAgentProcess(proc, spec.prompt.takeUnless { spec.interactive || spec.steerable }, mcpFile)
    }
}

private class LocalAgentProcess(private val proc: Process, prompt: String?, private val mcpFile: Path) : AgentProcess {
    private val out = Channel<String>(Channel.UNLIMITED)
    private val stdin: BufferedWriter = proc.outputStream.bufferedWriter(Charsets.UTF_8)
    private val stderr = StringBuilder()

    init {
        Thread({
            try {
                proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { seq -> seq.forEach { out.trySend(it) } }
            } catch (_: Exception) {
            } finally {
                out.close()
            }
        }, "agent-stdout-${proc.pid()}").apply { isDaemon = true }.start()
        Thread({
            try {
                proc.errorStream.bufferedReader(Charsets.UTF_8).useLines { seq ->
                    seq.forEach { line ->
                        synchronized(stderr) {
                            stderr.append(line).append('\n')
                            if (stderr.length > 8000) stderr.delete(0, stderr.length - 4000)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }, "agent-stderr-${proc.pid()}").apply { isDaemon = true }.start()
        proc.onExit().thenRun { runCatching { Files.deleteIfExists(mcpFile) } }
        if (prompt != null) {
            try {
                stdin.write(prompt)
                stdin.close()
            } catch (_: Exception) {
            }
        }
    }

    override val lines: ReceiveChannel<String> get() = out

    @Synchronized
    override fun send(line: String) {
        stdin.write(line)
        stdin.write("\n")
        stdin.flush()
    }

    @Synchronized
    override fun closeInput() {
        runCatching { stdin.close() }
    }

    override fun kill() {
        if (!proc.isAlive) return
        proc.descendants().forEach { it.destroy() }
        proc.destroy()
        Thread {
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.descendants().forEach { it.destroyForcibly() }
                proc.destroyForcibly()
            }
        }.apply { isDaemon = true }.start()
    }

    override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) {
        val code = proc.waitFor()
        runCatching { Files.deleteIfExists(mcpFile) }
        code
    }

    override fun stderrTail(): String = synchronized(stderr) { stderr.toString().takeLast(2000) }
}
