package dk.egernet.factory.agent

import dk.egernet.factory.AppJson
import dk.egernet.factory.Config
import dk.egernet.factory.EventDto
import dk.egernet.factory.SessionStatus
import dk.egernet.factory.conflict
import dk.egernet.factory.db.EventStore
import dk.egernet.factory.db.SessionRecord
import dk.egernet.factory.db.SessionStore
import dk.egernet.factory.db.SettingsStore
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.db.iso
import dk.egernet.factory.forbidden
import dk.egernet.factory.github.Access
import dk.egernet.factory.mcp.McpGrant
import dk.egernet.factory.mcp.McpRegistry
import dk.egernet.factory.notFound
import dk.egernet.factory.unprocessable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** CLI output that means the Claude credential was rejected (bad, revoked or expired token / key). */
private val AUTH_FAILURE = Regex(
    "(?i)invalid api key|please run /login|authentication[_ ](error|failed)|unauthori[sz]ed|oauth token (has )?(expired|revoked)|invalid (x-api-key|bearer token)",
)

/** Input for a one-shot pipeline agent. */
data class StageAgentRequest(
    val owner: UserRecord,
    val repo: String,
    val role: String,
    val model: String,
    val runId: UUID,
    val issueNumber: Int,
    val parentSessionId: UUID?,
    val workDir: Path,
    val systemPrompt: String,
    val prompt: String,
    val jsonSchema: String? = null,
    val disallowedTools: List<String> = emptyList(),
    val resumeClaudeSessionId: String? = null,
    val title: String? = null,
)

data class StageAgentResult(
    val session: SessionRecord,
    val claudeSessionId: String?,
    /** The CLI's final `result` object, if one arrived. */
    val result: JsonObject?,
    val ok: Boolean,
    val error: String?,
) {
    val resultText: String? get() = result?.get("result")?.jsonPrimitive?.contentOrNull
    val structuredOutput: JsonElement? get() = result?.get("structured_output")?.takeIf { it !is kotlinx.serialization.json.JsonNull }
}

/**
 * Owns all agent processes: chat sessions (long-lived, stream-json input) and one-shot pipeline agents.
 * Persists complete stream-json messages as events, broadcasts partial deltas live, tracks status/cost.
 */
class AgentManager(
    private val cfg: Config,
    private val sessions: SessionStore,
    private val events: EventStore,
    private val access: Access,
    private val launcher: AgentLauncher,
    private val bus: EventBus,
    private val mcp: McpRegistry,
    private val settings: SettingsStore,
    private val prompts: Prompts,
    private val workspaces: Workspaces,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(AgentManager::class.java)

    /** Handles `/factory …` chat commands; wired after construction (orchestrator depends on this class). */
    var commandHandler: (suspend (session: SessionRecord, owner: UserRecord, text: String) -> String)? = null

    private class Live(
        val sessionId: UUID, val ownerId: UUID, val repo: String, val kind: String,
        val proc: AgentProcess, val mcpToken: String, val interactive: Boolean,
        val baseCost: Double, var turns: Int, var claudeSessionId: String?,
    ) {
        @Volatile var turnActive = false
        @Volatile var lastUse = System.currentTimeMillis()
        @Volatile var stopReason: String? = null
        @Volatile var lastResult: JsonObject? = null
        val exited = CompletableDeferred<Int>()
        /** Launched with `--resume`; a launch that dies before its init event could not resume. */
        val resumed: Boolean get() = resumedFrom != null
        var resumedFrom: String? = null
        @Volatile var sawInit = false
        @Volatile var authFailed = false
        /** Chat inputs written to this process and not yet answered by a `result`. */
        val pending = java.util.Collections.synchronizedList(mutableListOf<String>())
    }

    private val live = ConcurrentHashMap<UUID, Live>()
    private val chatLocks = ConcurrentHashMap<UUID, Mutex>()
    private val eventLocks = ConcurrentHashMap<UUID, Any>()
    private val slots = ConcurrentHashMap<UUID, Semaphore>()

    private fun slot(userId: UUID) = slots.computeIfAbsent(userId) { Semaphore(cfg.maxAgentsPerUser) }

    fun isLive(sessionId: UUID) = live.containsKey(sessionId)

    /** The API view of a session, including whether a process is alive for it right now. */
    fun dto(s: SessionRecord) = s.toDto(isLive(s.id))
    fun liveCountByRepo(): Map<String, Int> = live.values.groupingBy { it.repo }.eachCount()

    /**
     * An `active` session for listings (`GET /api/agents?active=true`): it has a live process, or it is
     * `starting`/`running` (e.g. a pipeline agent waiting for a free slot).
     */
    fun isActive(s: SessionRecord) = isLive(s.id) || s.status in setOf(SessionStatus.STARTING, SessionStatus.RUNNING)

    // ------------------------------------------------------------ events & status

    fun publish(sessionId: UUID, type: String, payload: JsonElement): EventDto =
        synchronized(eventLocks.computeIfAbsent(sessionId) { Any() }) {
            val e = events.append(sessionId, type, payload)
            bus.emit(sessionId, e)
            e
        }

    fun systemEvent(sessionId: UUID, level: String, text: String) =
        publish(sessionId, "system", buildJsonObject { put("level", level); put("text", text) })

    private fun setStatus(sessionId: UUID, status: String, ended: Boolean = false) {
        sessions.setStatus(sessionId, status, ended)
        publish(sessionId, "status", buildJsonObject { put("status", status) })
        publishFeed(sessionId)
    }

    fun publishFeed(sessionId: UUID) {
        sessions.get(sessionId)?.let { bus.emitFeed(FeedItem.Session(dto(it))) }
    }

    // ------------------------------------------------------------ chat

    fun createChat(owner: UserRecord, repo: String, model: String?): SessionRecord {
        if (owner.claudeCredEnc == null) throw unprocessable("Register a Claude credential in Settings first", "claude_credential_required")
        val m = model?.trim()?.takeIf { it.isNotEmpty() } ?: settings.get().chatModel
        require(Regex("^[A-Za-z0-9._\\[\\]-]{1,100}$").matches(m)) { "Invalid model name" }
        val s = sessions.create(repo, "chat", m, SessionStatus.IDLE, owner.id, null)
        systemEvent(s.id, "info", "New chat in ${cfg.githubOrg}/$repo with model $m. Type /factory help for factory commands.")
        publishFeed(s.id)
        return sessions.get(s.id)!!
    }

    /** Owner-only. Returns immediately; the turn runs in the background. */
    fun sendInput(user: UserRecord, s: SessionRecord, text: String) {
        if (s.ownerId != user.id) throw forbidden("Only the owner of this session can send input")
        if (s.kind != "chat") throw conflict("Only chat sessions accept input")
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw dk.egernet.factory.badRequest("text must not be empty")
        if (text.length > 100_000) throw dk.egernet.factory.badRequest("text is too long")

        val isCommand = trimmed == "/factory" || trimmed.startsWith("/factory ")
        if (!isCommand && !isLive(s.id)) {
            if (user.claudeCredEnc == null) throw unprocessable("Register a Claude credential in Settings first", "claude_credential_required")
            if (slot(user.id).availablePermits == 0) throw conflict("You already have ${cfg.maxAgentsPerUser} agents running", "too_many_agents")
        }

        publish(s.id, "user", buildJsonObject { put("text", text); put("username", user.username) })
        sessions.setTitle(s.id, trimmed.lineSequence().first().take(80))

        if (isCommand) {
            scope.launch {
                val reply = try {
                    commandHandler?.invoke(s, user, trimmed) ?: "Factory commands are not available."
                } catch (e: dk.egernet.factory.ApiException) {
                    e.message
                } catch (e: Exception) {
                    log.warn("factory command failed", e)
                    "Command failed: ${e.message}"
                }
                systemEvent(s.id, "info", reply)
                publishFeed(s.id)
            }
            return
        }

        scope.launch {
            val mutex = chatLocks.computeIfAbsent(s.id) { Mutex() }
            try {
                mutex.withLock {
                    val l = live[s.id] ?: startChat(s.id, user)
                    try {
                        deliver(l, text)
                    } catch (e: Exception) {
                        // A resume launch that already died is relaunched by finishChat with this input.
                        if (l.resumed && !l.sawInit) return@withLock
                        throw e
                    }
                }
            } catch (e: Exception) {
                log.warn("Chat input for session {} failed: {}", s.id, e.message)
                systemEvent(s.id, "error", "Could not deliver input to the agent: ${e.message}")
                if (!isLive(s.id)) setStatus(s.id, SessionStatus.IDLE)
            }
        }
    }

    private suspend fun deliver(l: Live, text: String) {
        l.turnActive = true
        l.lastUse = System.currentTimeMillis()
        l.pending += text
        val msg = buildJsonObject {
            put("type", "user")
            putJsonObject("message") { put("role", "user"); put("content", text) }
        }
        withContext(Dispatchers.IO) { l.proc.send(msg.toString()) }
        if (sessions.get(l.sessionId)?.status != SessionStatus.RUNNING) setStatus(l.sessionId, SessionStatus.RUNNING)
    }

    private suspend fun startChat(sessionId: UUID, owner: UserRecord): Live {
        val s = sessions.get(sessionId) ?: throw notFound("Session not found")
        val cred = access.claudeCredential(owner) ?: throw unprocessable("No Claude credential", "claude_credential_required")
        val slot = slot(owner.id)
        if (!slot.tryAcquire()) throw conflict("You already have ${cfg.maxAgentsPerUser} agents running", "too_many_agents")
        var token: String? = null
        try {
            if (s.endedAt != null) sessions.reopen(sessionId)
            setStatus(sessionId, SessionStatus.STARTING)
            val identity = AgentIdentity(owner.username, owner.displayName, owner.githubLogin, access.githubToken(owner), cred)
            val workDir = withContext(Dispatchers.IO) { workspaces.ensureMain(identity, s.repo) }
            val mayInstall = settings.get().agentsMayInstallSoftware
            val tools = if (mayInstall) McpRegistry.CHAT_TOOLS - "factory_request_software" else McpRegistry.CHAT_TOOLS
            token = mcp.issue(McpGrant(sessionId, owner.id, s.repo, null, tools))
            val spec = LaunchSpec(
                sessionId = sessionId, kind = "chat", model = s.model, workDir = workDir, home = workspaces.home(owner.username),
                identity = identity, interactive = true, resumeClaudeSessionId = s.claudeSessionId,
                appendSystemPrompt = prompts.system("chat", s.repo, mayInstall), mcpConfigJson = mcpConfig(token),
            )
            val proc = withContext(Dispatchers.IO) { launcher.launch(spec) }
            if (s.claudeSessionId != null) systemEvent(sessionId, "info", "Resuming conversation ${s.claudeSessionId}")
            val l = Live(sessionId, owner.id, s.repo, "chat", proc, token, true, s.costUsd ?: 0.0, s.numTurns ?: 0, s.claudeSessionId)
            l.resumedFrom = s.claudeSessionId
            live[sessionId] = l
            scope.launch { pump(l); finishChat(l, slot) }
            return l
        } catch (e: Throwable) {
            slot.release()
            token?.let { mcp.revoke(it) }
            throw e
        }
    }

    private suspend fun finishChat(l: Live, slot: Semaphore) {
        val code = runCatching { l.proc.awaitExit() }.getOrDefault(-1)
        live.remove(l.sessionId)
        mcp.revoke(l.mcpToken)
        slot.release()
        var relaunch = false
        try {
            val stderr = scrub(l.proc.stderrTail(), l)
            when {
                l.stopReason == "stopped" -> setStatus(l.sessionId, SessionStatus.STOPPED, ended = true)
                l.stopReason == "idle" -> {
                    systemEvent(l.sessionId, "info", "Agent process stopped after ${cfg.chatIdleMinutes} idle minutes; it resumes on your next input.")
                    setStatus(l.sessionId, SessionStatus.IDLE)
                }
                l.stopReason != null -> {
                    // Server-initiated (e.g. shutdown): not an error of the agent.
                    systemEvent(l.sessionId, "info", "Agent process stopped by the server; it resumes on your next input.")
                    setStatus(l.sessionId, SessionStatus.IDLE)
                }
                code != 0 && l.resumed && !l.sawInit && !l.authFailed && authProblem(stderr) == null -> {
                    // `--resume` of a conversation the CLI no longer has (e.g. "No conversation found with session ID").
                    log.warn("Session {}: resuming conversation {} failed (exit {}); starting a fresh one", l.sessionId, l.resumedFrom, code)
                    sessions.clearClaudeSessionId(l.sessionId)
                    systemEvent(
                        l.sessionId, "warn",
                        "Previous conversation could not be resumed — starting a fresh one (earlier context is not available to the agent).",
                    )
                    relaunch = true
                }
                code != 0 -> {
                    when {
                        l.authFailed -> {} // already explained when the CLI reported it
                        authProblem(stderr) != null -> systemEvent(l.sessionId, "error", authMessage(authProblem(stderr)!!))
                        code == -1 || code == 137 || code == 143 ->
                            systemEvent(l.sessionId, "warn", "Agent process was stopped (exit code $code); it resumes on your next input.")
                        else -> systemEvent(l.sessionId, "error", "Agent process exited with code $code. $stderr".trim())
                    }
                    setStatus(l.sessionId, SessionStatus.IDLE)
                }
                else -> setStatus(l.sessionId, SessionStatus.IDLE)
            }
        } finally {
            // Completed last, so stop() returns the final status.
            l.exited.complete(code)
        }
        if (relaunch) relaunchFresh(l)
    }

    /** One fresh launch (no `--resume`) after a failed resume; re-delivers the inputs the dead process never answered. */
    private suspend fun relaunchFresh(dead: Live) {
        val id = dead.sessionId
        chatLocks.computeIfAbsent(id) { Mutex() }.withLock {
            val texts = synchronized(dead.pending) { dead.pending.toList() }
            if (texts.isEmpty()) {
                setStatus(id, SessionStatus.IDLE)
                return
            }
            try {
                val owner = access.userById(dead.ownerId) ?: error("owner no longer exists")
                val l = live[id] ?: startChat(id, owner)
                for (t in texts) deliver(l, t)
            } catch (e: Exception) {
                log.warn("Fresh relaunch of session {} failed: {}", id, e.message)
                systemEvent(id, "error", "Could not start a fresh conversation: ${e.message}")
                if (!isLive(id)) setStatus(id, SessionStatus.IDLE)
            }
        }
    }

    /** The first line of agent output that says the Claude credential was rejected, or null. */
    private fun authProblem(text: String?): String? =
        text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() && AUTH_FAILURE.containsMatchIn(it) }?.take(200)

    private fun authMessage(detail: String) =
        "Claude rejected your credential ($detail). Update your Claude credential in Settings → Claude, then try again."

    fun interrupt(user: UserRecord, s: SessionRecord) {
        if (s.ownerId != user.id) throw forbidden("Only the owner of this session can interrupt it")
        if (s.kind != "chat") throw conflict("Pipeline agents are stopped by cancelling the run")
        val l = live[s.id] ?: return
        if (!l.turnActive) return
        val req = buildJsonObject {
            put("type", "control_request")
            put("request_id", "interrupt-${UUID.randomUUID()}")
            putJsonObject("request") { put("subtype", "interrupt") }
        }
        runCatching { l.proc.send(req.toString()) }.onFailure { l.proc.kill() }
        systemEvent(s.id, "info", "Interrupted by ${user.username}")
    }

    /** Owner or admin. Kills the process; a stopped chat can be resumed by sending input again. */
    suspend fun stop(user: UserRecord, s: SessionRecord): SessionRecord {
        if (s.ownerId != user.id && !user.isAdmin) throw forbidden("Only the owner or an admin can stop this session")
        val l = live[s.id]
        if (l != null) {
            l.stopReason = "stopped"
            systemEvent(s.id, "warn", "Stopped by ${user.username}")
            l.proc.closeInput()
            l.proc.kill()
            withContext(Dispatchers.IO) { kotlinx.coroutines.withTimeoutOrNull(10_000) { l.exited.await() } }
        } else if (s.status !in setOf(SessionStatus.DONE, SessionStatus.FAILED, SessionStatus.STOPPED)) {
            setStatus(s.id, SessionStatus.STOPPED, ended = true)
        }
        return sessions.get(s.id)!!
    }

    /** Stops chat processes that have been idle for FACTORY_CHAT_IDLE_MINUTES. */
    fun startIdleReaper() = scope.launch {
        while (isActive) {
            delay(30_000)
            val cutoff = System.currentTimeMillis() - cfg.chatIdleMinutes * 60_000
            for (l in live.values) {
                if (l.interactive && !l.turnActive && l.lastUse < cutoff && l.stopReason == null) {
                    l.stopReason = "idle"
                    l.proc.closeInput()
                    l.proc.kill()
                }
            }
        }
    }

    // ------------------------------------------------------------ pipeline agents

    /**
     * Runs one pipeline agent to completion. [onSession] is called as soon as the session row exists.
     * Cancelling the calling coroutine kills the process.
     */
    suspend fun runStageAgent(req: StageAgentRequest, onSession: (SessionRecord) -> Unit = {}): StageAgentResult {
        val s = sessions.create(
            req.repo, req.role, req.model, SessionStatus.STARTING, req.owner.id, req.title, req.runId, req.parentSessionId, req.issueNumber,
        )
        publishFeed(s.id)
        onSession(s)
        val slot = slot(req.owner.id)
        slot.acquire()
        var token: String? = null
        var l: Live? = null
        try {
            val cred = access.claudeCredential(req.owner)
                ?: return finishStage(s, null, "Run owner has no Claude credential")
            val mayInstall = settings.get().agentsMayInstallSoftware
            val tools = if (mayInstall) McpRegistry.STAGE_TOOLS - "factory_request_software" else McpRegistry.STAGE_TOOLS
            token = mcp.issue(McpGrant(s.id, req.owner.id, req.repo, req.runId, tools))
            val identity = AgentIdentity(req.owner.username, req.owner.displayName, req.owner.githubLogin, access.githubToken(req.owner), cred)
            val spec = LaunchSpec(
                sessionId = s.id, kind = req.role, model = req.model, workDir = req.workDir, home = workspaces.home(req.owner.username),
                identity = identity, interactive = false, resumeClaudeSessionId = req.resumeClaudeSessionId,
                appendSystemPrompt = req.systemPrompt, prompt = req.prompt, jsonSchema = req.jsonSchema,
                disallowedTools = req.disallowedTools, mcpConfigJson = mcpConfig(token),
            )
            publish(s.id, "user", buildJsonObject { put("text", req.prompt); put("username", req.owner.username) })
            val proc = withContext(Dispatchers.IO) { launcher.launch(spec) }
            val lv = Live(s.id, req.owner.id, req.repo, req.role, proc, token, false, 0.0, 0, req.resumeClaudeSessionId)
            l = lv
            live[s.id] = lv
            setStatus(s.id, SessionStatus.RUNNING)
            val code = withTimeoutOrNull(cfg.stageTimeoutMs) {
                pump(lv)
                proc.awaitExit()
            } ?: withContext(NonCancellable) {
                lv.stopReason = "timeout"
                systemEvent(s.id, "warn", "Stage time limit of ${describeTimeout()} reached; stopping the agent")
                proc.closeInput()
                proc.kill()
                withTimeoutOrNull(10_000) { proc.awaitExit() } ?: -1
            }
            val result = lv.lastResult
            val ok = result != null && result["is_error"]?.jsonPrimitive?.booleanOrNull != true &&
                (result["subtype"]?.jsonPrimitive?.contentOrNull ?: "success") == "success"
            val err = when {
                lv.stopReason == "stopped" -> "Agent was stopped"
                lv.stopReason == "timeout" -> "Timed out: the stage ran longer than ${describeTimeout()} " +
                    "(FACTORY_STAGE_TIMEOUT_MINUTES) and its agent was stopped"
                ok -> null
                lv.authFailed -> authMessage(authProblem(result?.get("result")?.jsonPrimitive?.contentOrNull) ?: "authentication failed")
                authProblem(proc.stderrTail()) != null -> authMessage(scrub(authProblem(proc.stderrTail())!!, lv))
                result != null -> "Agent finished with ${result["subtype"]?.jsonPrimitive?.contentOrNull ?: "an error"}: " +
                    (result["result"]?.jsonPrimitive?.contentOrNull ?: "").take(500)
                else -> "Agent exited with code $code without a result. ${scrub(proc.stderrTail(), lv)}".trim()
            }
            return finishStage(s, lv, err)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                l?.let { it.stopReason = "stopped"; it.proc.kill() }
                systemEvent(s.id, "warn", "Agent stopped because the run was cancelled")
                setStatus(s.id, SessionStatus.STOPPED, ended = true)
            }
            throw e
        } catch (e: Exception) {
            log.warn("Stage agent {} failed to run: {}", s.id, e.message)
            l?.proc?.kill()
            return finishStage(s, l, "Could not run agent: ${e.message}")
        } finally {
            live.remove(s.id)
            token?.let { mcp.revoke(it) }
            slot.release()
        }
    }

    private fun finishStage(s: SessionRecord, l: Live?, error: String?): StageAgentResult {
        if (error != null) systemEvent(s.id, "error", error)
        val status = if (l?.stopReason == "stopped") SessionStatus.STOPPED else if (error == null) SessionStatus.DONE else SessionStatus.FAILED
        setStatus(s.id, status, ended = true)
        val rec = sessions.get(s.id)!!
        return StageAgentResult(rec, l?.claudeSessionId ?: rec.claudeSessionId, l?.lastResult, error == null, error)
    }

    private fun describeTimeout(): String {
        val ms = cfg.stageTimeoutMs
        return if (ms % 60_000 == 0L) "${ms / 60_000} minute" + (if (ms == 60_000L) "" else "s") else "${ms / 1000.0} seconds"
    }

    // ------------------------------------------------------------ stdout handling

    private suspend fun pump(l: Live) {
        for (line in l.proc.lines) {
            if (line.isBlank()) continue
            try {
                handleLine(l, line)
            } catch (e: Exception) {
                log.warn("Failed to handle agent output for {}: {}", l.sessionId, e.message)
            }
        }
    }

    private fun handleLine(l: Live, line: String) {
        val obj = try {
            AppJson.parseToJsonElement(line) as? JsonObject
        } catch (e: Exception) {
            null
        }
        if (obj == null) {
            systemEvent(l.sessionId, "warn", "Unparseable agent output: ${scrub(line.take(500), l)}")
            return
        }
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "stream_event" -> {
                bus.emit(l.sessionId, EventDto(-1, l.sessionId.toString(), Instant.now().iso(), "claude", obj))
                return
            }
            "control_response", "control_request", "keep_alive" -> return
        }
        publish(l.sessionId, "claude", obj)
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "system" -> if (obj["subtype"]?.jsonPrimitive?.contentOrNull == "init") {
                l.sawInit = true
                val cid = obj["session_id"]?.jsonPrimitive?.contentOrNull
                if (cid != null && cid != l.claudeSessionId) {
                    l.claudeSessionId = cid
                    sessions.setClaudeSessionId(l.sessionId, cid)
                }
            }
            "assistant" -> describeActivity(obj)?.let {
                sessions.setActivity(l.sessionId, it)
                publishFeed(l.sessionId)
            }
            "result" -> {
                l.lastResult = obj
                synchronized(l.pending) { if (l.pending.isNotEmpty()) l.pending.removeAt(0) }
                if (obj["is_error"]?.jsonPrimitive?.booleanOrNull == true) {
                    authProblem(obj["result"]?.jsonPrimitive?.contentOrNull)?.let {
                        l.authFailed = true
                        if (l.interactive) systemEvent(l.sessionId, "error", authMessage(it))
                    }
                }
                val cost = obj["total_cost_usd"]?.jsonPrimitive?.doubleOrNull
                l.turns += obj["num_turns"]?.jsonPrimitive?.intOrNull ?: 0
                sessions.setUsage(l.sessionId, cost?.let { l.baseCost + it }, l.turns)
                if (l.interactive) {
                    l.turnActive = false
                    l.lastUse = System.currentTimeMillis()
                    if (l.stopReason == null) setStatus(l.sessionId, SessionStatus.IDLE) else publishFeed(l.sessionId)
                } else {
                    publishFeed(l.sessionId)
                    // A one-shot agent should exit by itself after its result; make sure it does.
                    scope.launch {
                        delay(30_000)
                        if (!l.exited.isCompleted && live[l.sessionId] === l) l.proc.kill()
                    }
                }
            }
        }
    }

    /** Short text for `lastActivity`, e.g. "Bash: ./gradlew test". */
    private fun describeActivity(obj: JsonObject): String? {
        val content = obj["message"]?.jsonObject?.get("content")?.let { runCatching { it.jsonArray }.getOrNull() } ?: return null
        var text: String? = null
        for (block in content.reversed()) {
            val b = block as? JsonObject ?: continue
            when (b["type"]?.jsonPrimitive?.contentOrNull) {
                "tool_use" -> {
                    val name = b["name"]?.jsonPrimitive?.contentOrNull ?: "tool"
                    val input = b["input"] as? JsonObject
                    val arg = listOf("command", "file_path", "path", "pattern", "url", "description", "prompt")
                        .firstNotNullOfOrNull { k -> (input?.get(k) as? JsonPrimitive)?.contentOrNull }
                    return (if (arg != null) "$name: ${arg.lineSequence().first()}" else name).take(120)
                }
                "text" -> if (text == null) text = b["text"]?.jsonPrimitive?.contentOrNull?.trim()?.lineSequence()?.firstOrNull()
            }
        }
        return text?.takeIf { it.isNotEmpty() }?.take(120)
    }

    /** Never let a known secret leak into an event or log through stderr. */
    private fun scrub(text: String, l: Live?): String {
        var t = text
        if (l != null) {
            t = t.replace(l.mcpToken, "***")
            access.userById(l.ownerId)?.let { u ->
                access.githubToken(u)?.let { if (it.length >= 8) t = t.replace(it, "***") }
                access.claudeCredential(u)?.value?.let { if (it.length >= 8) t = t.replace(it, "***") }
            }
        }
        return t
    }

    private fun mcpConfig(token: String): String = buildJsonObject {
        putJsonObject("mcpServers") {
            putJsonObject("factory") {
                put("type", "http")
                put("url", cfg.mcpUrl)
                putJsonObject("headers") { put("Authorization", "Bearer $token") }
            }
        }
    }.toString()

    /** Kills every process (server shutdown). */
    fun shutdown() {
        for (l in live.values) {
            l.stopReason = l.stopReason ?: "shutdown"
            l.proc.kill()
        }
    }
}
