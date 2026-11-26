package dk.egernet.factory.agent

import dk.egernet.factory.AppJson
import dk.egernet.factory.AgentMessageDto
import dk.egernet.factory.ADVISOR_KIND
import dk.egernet.factory.AttachmentDto
import dk.egernet.factory.INTERACTIVE_KINDS
import dk.egernet.factory.STEERABLE_ROLES
import kotlinx.serialization.builtins.ListSerializer
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
import dk.egernet.factory.git.CheckoutState
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
import kotlinx.serialization.json.JsonArray
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

/** A steerable stage agent is warned when this share of the stage time limit has passed (docs/ARCHITECTURE.md §6). */
const val TIME_LIMIT_WARNING_PERCENT = 80L

/** CLI output that means the Claude credential was rejected (bad, revoked or expired token / key). */
private val AUTH_FAILURE = Regex(
    "(?i)invalid api key|please run /login|authentication[_ ](error|failed)|unauthori[sz]ed|oauth token (has )?(expired|revoked)|invalid (x-api-key|bearer token)",
)

/** The owner's Claude usage limit was reached; [resetsAt] when the CLI said when it resets, [type] e.g. `five_hour`. */
data class UsageLimit(val resetsAt: Instant?, val type: String?)

/**
 * Recognises a Claude usage limit in a stage's stream-json output (docs/ARCHITECTURE.md §6 "Releases", usage limit).
 * Signals: a `rate_limit_event` whose `rate_limit_info.status` is `rejected`, an `assistant` line with
 * `error: "rate_limit"`, or a failing `result` whose text says so. The reset time comes from any
 * `rate_limit_info.resetsAt` (epoch seconds, epoch milliseconds or ISO-8601). Unknown shapes are ignored, never thrown.
 */
class UsageLimitDetector {
    @Volatile var limited = false
        private set
    @Volatile var resetsAt: Instant? = null
        private set
    @Volatile var type: String? = null
        private set

    fun observe(obj: JsonObject) {
        try {
            val info = obj["rate_limit_info"] as? JsonObject
                ?: ((obj["message"] as? JsonObject)?.get("rate_limit_info") as? JsonObject)
            if (info != null) {
                parseResetsAt(info["resetsAt"])?.let { resetsAt = it }
                (info["rateLimitType"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { type = it }
            }
            when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
                "rate_limit_event" -> if ((info?.get("status") as? JsonPrimitive)?.contentOrNull == "rejected") limited = true
                "assistant" -> if ((obj["error"] as? JsonPrimitive)?.contentOrNull == "rate_limit") limited = true
                "result" -> {
                    val failed = (obj["is_error"] as? JsonPrimitive)?.booleanOrNull == true ||
                        ((obj["subtype"] as? JsonPrimitive)?.contentOrNull ?: "success") != "success"
                    val text = (obj["result"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (failed && text != null && LIMIT_TEXT.containsMatchIn(text)) limited = true
                }
            }
        } catch (e: Exception) {
            // Tolerant by design: the shapes were read from the CLI, not observed in a live limit.
        }
    }

    /** The usage limit, when one was seen and the stage [failed]. */
    fun result(failed: Boolean): UsageLimit? = if (failed && limited) UsageLimit(resetsAt, type) else null

    companion object {
        private val LIMIT_TEXT = Regex("(?i)usage limit|session limit|weekly limit|hit your limit")

        /** Epoch seconds, epoch milliseconds or an ISO-8601 string; anything else is unknown (null). */
        fun parseResetsAt(e: JsonElement?): Instant? {
            val p = e as? JsonPrimitive ?: return null
            if (p is kotlinx.serialization.json.JsonNull) return null
            return try {
                if (p.isString) {
                    val t = p.content.trim()
                    t.toLongOrNull()?.let { fromEpoch(it) } ?: Instant.parse(t)
                } else {
                    p.content.toDoubleOrNull()?.let { fromEpoch(it.toLong()) }
                }
            } catch (ex: Exception) {
                null
            }
        }

        private fun fromEpoch(n: Long): Instant? = when {
            n <= 0 -> null
            n >= 100_000_000_000L -> Instant.ofEpochMilli(n)
            else -> Instant.ofEpochSecond(n)
        }

        /** Detection over a whole stage output, for tests. */
        fun detect(lines: List<JsonObject>, failed: Boolean): UsageLimit? =
            UsageLimitDetector().apply { lines.forEach { observe(it) } }.result(failed)
    }
}

/** Input for a one-shot pipeline agent. */
data class StageAgentRequest(
    val owner: UserRecord,
    val repo: String,
    val role: String,
    val model: String,
    val runId: UUID,
    /** The run's issue; null for a run that belongs to no issue (a sync run). */
    val issueNumber: Int?,
    val parentSessionId: UUID?,
    val workDir: Path,
    val systemPrompt: String,
    val prompt: String,
    val jsonSchema: String? = null,
    val disallowedTools: List<String> = emptyList(),
    val resumeClaudeSessionId: String? = null,
    val title: String? = null,
    /** MCP tools on top of [McpRegistry.STAGE_TOOLS] (the builder's sandbox tools). */
    val extraTools: Set<String> = emptySet(),
    /** `system` events (level, text) put at the top of the stage's transcript, e.g. whether the run has a sandbox. */
    val notes: List<Pair<String, String>> = emptyList(),
    /** The member repositories of a workspace run, recorded on the session (docs/ARCHITECTURE.md §4 "Visibility"). */
    val contextRepos: List<String> = emptyList(),
)

/**
 * What an advisor process is launched with (docs/ARCHITECTURE.md §6 "Conversation at needs human"): the run's worktree,
 * the run its MCP grant is bound to, and the `<run_context>` block its first message carries.
 */
data class AdvisorLaunch(val workDir: Path, val runId: UUID, val context: String)

data class StageAgentResult(
    val session: SessionRecord,
    val claudeSessionId: String?,
    /** The CLI's final `result` object, if one arrived. */
    val result: JsonObject?,
    val ok: Boolean,
    val error: String?,
    /** Set only when the stage failed because the owner's Claude usage limit was reached. */
    val usageLimit: UsageLimit? = null,
    /** The stage failed because Claude rejected the owner's credential (or there was none). */
    val credentialRejected: Boolean = false,
    /** The stage was stopped at the stage time limit. */
    val timedOut: Boolean = false,
    /** The process started its conversation (emitted `init`); false when, e.g., a `--resume` failed at once. */
    val started: Boolean = false,
) {
    val resultText: String? get() = result?.get("result")?.jsonPrimitive?.contentOrNull
    val structuredOutput: JsonElement? get() = result?.get("structured_output")?.takeIf { it !is kotlinx.serialization.json.JsonNull }
}

/**
 * Subtypes of stream-json `system` events that only report progress (newer CLIs send `thinking_tokens` about every 100
 * thinking tokens). Like `stream_event` partials they are sent live and never stored: a run's log would fill with them.
 */
internal val TRANSIENT_SYSTEM_SUBTYPES = setOf("thinking_tokens")

/**
 * Top-level stream-json event types that only report progress and are, like `stream_event` partials, sent live and
 * never stored: `tool_progress` is a heartbeat the CLI sends every few seconds while a tool call runs
 * (`tool_use_id`, `tool_name`, `elapsed_time_seconds`, ...); `response_length` drives the CLI's streamed-characters
 * counter.
 */
internal val TRANSIENT_EVENT_TYPES = setOf("stream_event", "tool_progress", "response_length")

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
    private val attachments: Attachments,
    private val preferences: dk.egernet.factory.db.PreferenceStore,
    private val usageStore: dk.egernet.factory.db.ClaudeUsageStore,
    private val workspaceDefs: dk.egernet.factory.workspace.WorkspaceService? = null,
) {
    private val log = LoggerFactory.getLogger(AgentManager::class.java)

    /** Handles `/factory …` chat commands; wired after construction (orchestrator depends on this class). */
    var commandHandler: (suspend (session: SessionRecord, owner: UserRecord, text: String) -> String)? = null
    /** Called when the owner's non-command chat input was accepted (image budgets per chat message); wired like [commandHandler]. */
    var onChatInput: ((sessionId: UUID) -> Unit)? = null
    /** Called after a chat was closed and deleted (its generated images); blocking is fine. */
    var onChatClosed: ((sessionId: UUID) -> Unit)? = null

    /**
     * Supplies an advisor session's launch data (worktree, run, context), rebuilt from current data at each launch;
     * throws when the run cannot be talked to any more. Wired after construction, like [commandHandler].
     */
    var advisorLaunch: (suspend (session: SessionRecord, owner: UserRecord) -> AdvisorLaunch)? = null

    /**
     * Called after every turn (`result`) and every exit of an advisor process, before the session counts as idle again:
     * the orchestrator reconciles the run (a commit starts a follow-up review). Must not wait for the advisor.
     */
    var afterAdvisorTurn: (suspend (session: SessionRecord) -> Unit)? = null

    private class Live(
        val sessionId: UUID, val ownerId: UUID, val repo: String, val kind: String,
        val proc: AgentProcess, val mcpToken: String, val interactive: Boolean,
        val baseCost: Double, var turns: Int, var claudeSessionId: String?,
        /** The owner's encrypted Claude credential this process was launched with (limit windows are recorded only while it is current). */
        val credEnc: ByteArray?,
    ) {
        /** The process's `total_cost_usd` at its previous `result` (the CLI reports a running total per process). */
        @Volatile var procCost = 0.0
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
        /** Usage-limit signals of a pipeline stage (of the current turn, for an advisor). */
        @Volatile var usage = UsageLimitDetector()
        /** Advisor: the `<run_context>` block of this launch, and whether a message carrying it has been written. */
        var context: String? = null
        @Volatile var contextSent = false
        /** Chat: the "behind" checkout state (reason, HEAD, behind) this process was last told about, null when none. */
        var checkoutTold: String? = null
        /** Chat in a workspace: the member lines of `<checkout_status>` this process was last told, null when none. */
        var membersTold: String? = null
        /** Chat inputs (stream-json `message.content`) written to this process and not yet answered by a `result`. */
        val pending = java.util.Collections.synchronizedList(mutableListOf<JsonElement>())

        // ---- steerable pipeline stages (spec-writer, builder): stdin stays open while the agent works
        @Volatile var steerable = false
        /** Guards [inputClosed], [awaiting] and every write to stdin, so a message is either written before stdin closes or rejected. */
        val inputLock = Any()
        var inputClosed = false
        /** Messages written to stdin that the CLI has not replayed (picked up) yet, oldest first. A null id is the task prompt. */
        val awaiting = ArrayDeque<Pair<String?, String>>()
        var replaysSeen = 0
    }

    private val live = ConcurrentHashMap<UUID, Live>()
    private val chatLocks = ConcurrentHashMap<UUID, Mutex>()
    private val eventLocks = ConcurrentHashMap<UUID, Any>()
    private val slots = ConcurrentHashMap<UUID, Semaphore>()
    /** Chats that were closed (deleted): late background work for them is dropped. */
    private val closed = ConcurrentHashMap.newKeySet<UUID>()
    /**
     * Advisor sessions with a message in flight: from its acceptance until the turn (or the process) has ended and the
     * run has been reconciled. One message at a time per conversation.
     */
    private val advisorTurns = ConcurrentHashMap.newKeySet<UUID>()
    /** Advisor sessions whose run ended (cancel, merge): queued input for them is dropped, no process is started. */
    private val advisorsEnded = ConcurrentHashMap.newKeySet<UUID>()
    /** The last `checkout` event payload published per chat by this server process (not published twice in a row). */
    private val checkoutPublished = ConcurrentHashMap<UUID, JsonObject>()

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

    /** Appends and broadcasts an event. For a closed chat nothing is stored or sent (a transient `seq = -1` event is returned). */
    fun publish(sessionId: UUID, type: String, payload: JsonElement): EventDto =
        synchronized(eventLocks.computeIfAbsent(sessionId) { Any() }) {
            if (sessionId in closed) return@synchronized EventDto(-1, sessionId.toString(), Instant.now().iso(), type, payload)
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
        sessions.get(sessionId)?.let { bus.emitFeed(FeedItem.Session(dto(it), it.contextRepos)) }
    }

    // ------------------------------------------------------------ chat

    fun createChat(owner: UserRecord, repo: String, model: String?): SessionRecord {
        if (owner.claudeCredEnc == null) throw unprocessable("Register a Claude credential in Settings first", "claude_credential_required")
        // The owner's own chat model unless the call names one; the admin default otherwise.
        val m = model?.trim()?.takeIf { it.isNotEmpty() } ?: preferences.effective(owner.id).chatModel
        require(Regex("^[A-Za-z0-9._\\[\\]-]{1,100}$").matches(m)) { "Invalid model name" }
        val s = sessions.create(repo, "chat", m, SessionStatus.IDLE, owner.id, null)
        systemEvent(s.id, "info", "New chat in ${cfg.githubOrg}/$repo with model $m. Type /factory help for factory commands.")
        publishFeed(s.id)
        // The header shows the checkout's commit before the first message; a failure only gets logged.
        scope.launch(Dispatchers.IO) {
            try {
                val identity = chatIdentity(owner) ?: return@launch
                workspaces.ensureMain(identity, repo)
                ensureChatWorkspace(s.id, owner, identity, repo)
                syncCheckout(s.id, owner, repo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Preparing the checkout of chat {} failed: {}", s.id, e.message)
            }
        }
        return sessions.get(s.id)!!
    }

    /**
     * A chat's view of a workspace project (docs/ARCHITECTURE.md §5 "Workspace projects"): the members checked out for
     * [owner] and the system prompt's member list; null for a project that is not a workspace.
     */
    private data class ChatWorkspace(val checkedOut: List<MemberCheckout>, val note: String)

    /**
     * For a workspace project: clones every member [owner]'s token can read into the chat's checkout, records them on
     * chat [sessionId] (as a union) and returns them; null otherwise. Blocking.
     */
    private fun ensureChatWorkspace(sessionId: UUID, owner: UserRecord, identity: AgentIdentity, repo: String): ChatWorkspace? {
        // First, whatever the definition says (or whether it can be read at all): every member clone already in the
        // chat's working directory is readable by the agent.
        recordChatClones(sessionId, owner, repo)
        val defs = workspaceDefs ?: return null
        val state = defs.lookup(owner, repo) ?: return null
        val resolved = defs.resolve(owner, state)
        val wanted = resolved.filter { it.accessible && it.baseBranch != null }
            .map { MemberCheckout(it.directory, it.repository, it.baseBranch!!, it.member.submodule) }
        val wc = workspaces.ensureWorkspace(identity, repo, wanted)
        sessions.addContextRepos(sessionId, wc.checkedOut.map { it.repository })
        recordChatClones(sessionId, owner, repo)
        val left = resolved.filter { !it.accessible }.map { LeftOut(it.directory, it.repository, "your GitHub token cannot read it") } + wc.leftOut
        val note = buildString {
            if (!state.valid) {
                append("The workspace definition `${state.path}` is invalid (${state.firstProblem}), so only the workspace ")
                append("repository is checked out and no run can start until it is fixed.\n")
            }
            if (resolved.isNotEmpty()) append("Members (directory, repository, base branch, issue label):\n")
            wc.checkedOut.forEach { m ->
                val label = state.members.firstOrNull { it.directory == m.directory }?.label ?: m.repository
                val kind = if (m.submodule) " (a git submodule of the workspace repository, at the commit it pins)" else ""
                append("- `${m.directory}` — ${cfg.githubOrg}/${m.repository}, base `${m.baseBranch}`, label `$label`$kind\n")
            }
            left.forEach { append("- `${it.directory}` — ${cfg.githubOrg}/${it.repository}: not checked out (${it.reason})\n") }
        }
        return ChatWorkspace(wc.checkedOut, note)
    }

    /**
     * Syncs the members of a workspace chat with their base branches ([Workspaces.syncMembers]) and returns the member
     * lines of `<checkout_status>` for [l], or null when there is nothing new to tell (docs/ARCHITECTURE.md §5).
     * Never throws.
     */
    private suspend fun memberCheckoutNote(l: Live, owner: UserRecord, repo: String): String? = try {
        withContext(Dispatchers.IO) {
            val identity = chatIdentity(owner) ?: return@withContext null
            val ws = ensureChatWorkspace(l.sessionId, owner, identity, repo) ?: return@withContext null
            val lines = workspaces.syncMembers(identity, repo, ws.checkedOut).mapNotNull { (m, state) ->
                state?.let { st -> checkoutNoteBody(st)?.let { "Member `${m.directory}` (${m.repository}): $it" } }
            }
            val text = lines.joinToString("\n").takeIf { it.isNotEmpty() }
            if (text == l.membersTold) null else text.also { l.membersTold = it }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Syncing the workspace members of chat {} failed: {}", l.sessionId, e.message)
        null
    }

    /**
     * Records every member clone in [owner]'s checkout of [repo] on chat [sessionId] (`context_repos`, as a union): the
     * chat's agent can read them whether or not the current definition still lists them (docs/ARCHITECTURE.md §4).
     */
    private fun recordChatClones(sessionId: UUID, owner: UserRecord, repo: String) {
        sessions.addContextRepos(sessionId, workspaces.clonesOnDisk(owner.username, repo))
    }

    /** The identity a chat of [owner] runs as (as in [startChat]), or null without a Claude credential. */
    private fun chatIdentity(owner: UserRecord): AgentIdentity? {
        val cred = access.claudeCredential(owner) ?: return null
        return AgentIdentity(owner.username, owner.displayName, owner.githubLogin, access.githubToken(owner), cred)
    }

    /**
     * Syncs [owner]'s main clone of [repo] with its default branch ([Workspaces.syncMain]) and publishes the result as a
     * `checkout` event unless it equals the last one published for [sessionId]. Never throws (docs/ARCHITECTURE.md §5
     * "Workspaces"): any failure is logged and gives null.
     */
    private suspend fun syncCheckout(sessionId: UUID, owner: UserRecord, repo: String): CheckoutState? = try {
        withContext(Dispatchers.IO) {
            val branch = access.visibleRepo(owner, repo)?.defaultBranch
            val identity = chatIdentity(owner)
            if (branch == null || identity == null) null else workspaces.syncMain(identity, repo, branch)
        }?.also { publishCheckout(sessionId, it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Syncing the checkout of {} for chat {} failed: {}", repo, sessionId, e.message)
        null
    }

    private fun publishCheckout(sessionId: UUID, state: CheckoutState) {
        if (sessionId in closed) return
        val payload = buildJsonObject {
            put("branch", state.branch)
            put("defaultBranch", state.defaultBranch)
            put("commit", state.head)
            put("behind", state.behind)
            put("ahead", state.ahead)
            put("status", state.status)
            put("reason", state.reason)
            put("updatedFrom", state.updatedFrom)
        }
        if (checkoutPublished.put(sessionId, payload) != payload) publish(sessionId, "checkout", payload)
    }

    /**
     * The `<checkout_status>` note for [l]'s next message after a sync with [state]: always after a fast-forward, and
     * for a checkout that stays behind only when this process has not been told that state yet.
     */
    private fun checkoutNoteFor(l: Live, state: CheckoutState?): String? {
        if (state == null) return null
        return when (state.status) {
            "behind" -> {
                val told = "${state.reason}|${state.head}|${state.behind}"
                if (l.checkoutTold == told) null else checkoutNote(state).also { l.checkoutTold = told }
            }
            else -> {
                l.checkoutTold = null
                checkoutNote(state)
            }
        }
    }

    /**
     * Owner-only. Returns immediately; the turn runs in the background. [attachmentIds]: unsent uploads of this
     * session by [user], in order; they are bound to this message (marked sent) before it is accepted.
     */
    fun sendInput(user: UserRecord, s: SessionRecord, text: String, attachmentIds: List<UUID> = emptyList()) {
        if (s.ownerId != user.id) throw forbidden("Only the owner of this session can send input")
        if (s.kind != "chat") throw conflict("Only chat sessions accept input")
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachmentIds.isEmpty()) throw dk.egernet.factory.badRequest("text must not be empty")
        if (text.length > 100_000) throw dk.egernet.factory.badRequest("text is too long")
        if (attachmentIds.size > cfg.attachmentMaxCount) throw attachments.tooMany()

        val isCommand = trimmed == "/factory" || trimmed.startsWith("/factory ")
        if (isCommand && attachmentIds.isNotEmpty()) {
            throw unprocessable("/factory commands do not take attachments. Remove them, or send them in a normal message.", "attachments_not_allowed")
        }
        if (!isCommand && !isLive(s.id)) {
            if (user.claudeCredEnc == null) throw unprocessable("Register a Claude credential in Settings first", "claude_credential_required")
            if (slot(user.id).availablePermits == 0) throw conflict("You already have ${cfg.maxAgentsPerUser} agents running", "too_many_agents")
        }

        val attached = attachments.store.markSent(s.id, user.id, attachmentIds)
            ?: throw conflict("An attachment is unknown, belongs to another session, or was already sent", "attachment_unavailable")

        publish(s.id, "user", buildJsonObject {
            put("text", text); put("username", user.username)
            if (attached.isNotEmpty()) put("attachments", AppJson.encodeToJsonElement(ListSerializer(AttachmentDto.serializer()), attached.map { it.toDto() }))
        })
        sessions.setTitle(s.id, (trimmed.lineSequence().firstOrNull()?.takeIf { it.isNotEmpty() } ?: "Attachment: ${attached.first().name}").take(80))

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
        onChatInput?.invoke(s.id)

        scope.launch {
            val mutex = chatLocks.computeIfAbsent(s.id) { Mutex() }
            try {
                mutex.withLock {
                    // Closed while this input waited for the lock: drop it (no process, no files).
                    if (s.id in closed) return@withLock
                    val content = withContext(Dispatchers.IO) { attachments.content(text, attached) }
                    val l = live[s.id] ?: startChat(s.id, user)
                    val note = mergeNotes(checkoutNoteFor(l, syncCheckout(s.id, user, s.repo)), memberCheckoutNote(l, user, s.repo))
                    try {
                        deliver(l, if (note == null) content else withNote(content, note))
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

    /** The workspace's `<checkout_status>` note and the member lines in one block (either may be null). */
    private fun mergeNotes(note: String?, memberLines: String?): String? = when {
        memberLines == null -> note
        note == null -> "<checkout_status>\n$memberLines\n</checkout_status>"
        else -> note.removeSuffix("</checkout_status>").trimEnd() + "\n" + memberLines + "\n</checkout_status>"
    }

    /** [content] (a string, or content blocks with attachments) with [note] in front, for the agent only. */
    private fun withNote(content: JsonElement, note: String): JsonElement =
        if (content is JsonArray) JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", note) }) + content)
        else JsonPrimitive(note + "\n\n" + content.jsonPrimitive.content)

    /** One stream-json user message for the CLI's stdin. */
    private fun userLine(content: JsonElement): String = buildJsonObject {
        put("type", "user")
        putJsonObject("message") { put("role", "user"); put("content", content) }
    }.toString()

    private suspend fun deliver(l: Live, content: JsonElement) {
        l.turnActive = true
        l.lastUse = System.currentTimeMillis()
        l.pending += content
        val msg = userLine(content)
        // Running before the write: a fast agent can answer (and go idle) before this coroutine continues, and
        // setting the status afterwards would then leave the session "running" with nothing going on.
        val before = sessions.get(l.sessionId)?.status
        if (before != SessionStatus.RUNNING) setStatus(l.sessionId, SessionStatus.RUNNING)
        try {
            withContext(Dispatchers.IO) { l.proc.send(msg) }
        } catch (e: Exception) {
            if (before != null && before != SessionStatus.RUNNING && isLive(l.sessionId)) setStatus(l.sessionId, before)
            throw e
        }
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
            val advisor = s.kind == ADVISOR_KIND
            // An advisor works in its run's worktree with the run's context; a chat in the owner's main clone.
            val launch = if (advisor) (advisorLaunch ?: error("Advisors are not available")).invoke(s, owner) else null
            val workDir = launch?.workDir ?: withContext(Dispatchers.IO) { workspaces.ensureMain(identity, s.repo) }
            // A chat in a workspace gets the members its owner can read beside the workspace clone.
            val chatWs = if (advisor) null else withContext(Dispatchers.IO) {
                // Never fails quietly: a chat whose member clones cannot be recorded does not start.
                recordChatClones(sessionId, owner, s.repo)
                runCatching { ensureChatWorkspace(sessionId, owner, identity, s.repo) }
                    .onFailure { log.warn("Preparing the workspace of chat {} failed: {}", sessionId, it.message) }.getOrNull()
            }
            val advisorWs = if (advisor && s.contextRepos.isNotEmpty()) "" else null
            // The attachment files are a cache of the database: put back what a cleaned data dir lost.
            if (!advisor) withContext(Dispatchers.IO) { attachments.materialiseSession(sessionId) }
            val mayInstall = settings.get().agentsMayInstallSoftware
            val granted = if (advisor) McpRegistry.STAGE_TOOLS else McpRegistry.CHAT_TOOLS
            val tools = if (mayInstall) granted - "factory_request_software" else granted
            token = mcp.issue(McpGrant(sessionId, owner.id, s.repo, launch?.runId, tools))
            val spec = LaunchSpec(
                sessionId = sessionId, kind = s.kind, model = s.model, workDir = workDir, home = workspaces.home(owner.username),
                identity = identity, interactive = true, resumeClaudeSessionId = s.claudeSessionId,
                appendSystemPrompt = prompts.system(if (advisor) ADVISOR_KIND else "chat", s.repo, mayInstall, workspace = chatWs?.note ?: advisorWs),
                mcpConfigJson = mcpConfig(token),
                mcpToolTimeoutMs = cfg.mcpToolTimeoutMs,
            )
            val proc = withContext(Dispatchers.IO) { launcher.launch(spec) }
            if (s.claudeSessionId != null) systemEvent(sessionId, "info", "Resuming conversation ${s.claudeSessionId}")
            if (advisor) {
                systemEvent(
                    sessionId, "info",
                    "The agent was given this run's context: the issue, the spec, the builder's summary, the latest reviews, " +
                        "the approver's verdict and why the run needs a human.",
                )
            }
            val l = Live(sessionId, owner.id, s.repo, s.kind, proc, token, true, s.costUsd ?: 0.0, s.numTurns ?: 0, s.claudeSessionId, owner.claudeCredEnc)
            l.context = launch?.context
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
        // An advisor's run is reconciled before the session counts as idle (a commit of the last turn is reviewed).
        if (l.kind == ADVISOR_KIND) afterTurn(l.sessionId)
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
            // A failed resume is relaunched with the same input: that turn is still in flight.
            if (l.kind == ADVISOR_KIND && !relaunch) advisorTurns.remove(l.sessionId)
            // Completed last, so stop() returns the final status.
            l.exited.complete(code)
        }
        if (relaunch) relaunchFresh(l)
    }

    /** Runs [afterAdvisorTurn] for an advisor session; never throws. */
    private suspend fun afterTurn(sessionId: UUID) {
        val s = sessions.get(sessionId) ?: return
        try {
            withContext(NonCancellable) { afterAdvisorTurn?.invoke(s) }
        } catch (e: Exception) {
            log.warn("Reconciling the run of advisor {} failed: {}", sessionId, e.message)
        }
    }

    /** One fresh launch (no `--resume`) after a failed resume; re-delivers the inputs the dead process never answered. */
    private suspend fun relaunchFresh(dead: Live) {
        val id = dead.sessionId
        chatLocks.computeIfAbsent(id) { Mutex() }.withLock {
            if (id in closed) return
            val texts = synchronized(dead.pending) { dead.pending.toList() }
            if (texts.isEmpty() || id in advisorsEnded) {
                advisorTurns.remove(id)
                setStatus(id, SessionStatus.IDLE)
                return
            }
            try {
                val owner = access.userById(dead.ownerId) ?: error("owner no longer exists")
                val l = live[id] ?: startChat(id, owner)
                // An advisor's first pending input already carries the run's context.
                l.contextSent = true
                for (t in texts) deliver(l, t)
            } catch (e: Exception) {
                log.warn("Fresh relaunch of session {} failed: {}", id, e.message)
                systemEvent(id, "error", "Could not start a fresh conversation: ${e.message}")
                advisorTurns.remove(id)
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
        if (s.kind !in INTERACTIVE_KINDS) throw conflict("Pipeline agents are stopped by cancelling the run")
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

    // ------------------------------------------------------------ advisor (docs/ARCHITECTURE.md §6 "Conversation at needs human")

    /** The advisor session [sessionId] is mid-turn: a message is in flight (also while starting) or the run is being reconciled. */
    fun isAdvisorBusy(sessionId: UUID): Boolean =
        sessionId in advisorTurns || live[sessionId]?.turnActive == true ||
            sessions.get(sessionId)?.status in setOf(SessionStatus.STARTING, SessionStatus.RUNNING)

    /** Whether [user] could start one more agent process right now. */
    fun hasFreeSlot(user: UserRecord): Boolean = slot(user.id).availablePermits > 0

    /**
     * Accepts one message of the run's owner for its advisor and returns at once; the turn runs in the background.
     * The orchestrator has checked the run (owner, status, review); this refuses a second message while one is in
     * flight (409 `agent_busy`), then a missing credential and a full agent slot. The transcript shows [text] as
     * written; the agent gets it prefixed with who wrote it, and the first message of each launch carries the context.
     */
    fun sendToAdvisor(user: UserRecord, s: SessionRecord, text: String) {
        if (s.ownerId != user.id) throw forbidden("Only the owner of this run can talk to its agent")
        if (s.kind != ADVISOR_KIND) throw conflict("Not an advisor session")
        if (!advisorTurns.add(s.id)) throw conflict("The agent is still answering; wait for it or interrupt it", "agent_busy")
        try {
            if (live[s.id]?.turnActive == true) throw conflict("The agent is still answering; wait for it or interrupt it", "agent_busy")
            if (!isLive(s.id)) {
                if (user.claudeCredEnc == null) throw unprocessable("Register a Claude credential in Settings first", "claude_credential_required")
                if (!hasFreeSlot(user)) throw conflict("You already have ${cfg.maxAgentsPerUser} agents running", "too_many_agents")
            }
        } catch (e: Exception) {
            advisorTurns.remove(s.id)
            throw e
        }
        advisorsEnded.remove(s.id)
        publish(s.id, "user", buildJsonObject { put("text", text); put("username", user.username) })
        val forAgent = "Message from the run owner (${user.username}):\n\n$text"
        scope.launch {
            val mutex = chatLocks.computeIfAbsent(s.id) { Mutex() }
            try {
                mutex.withLock {
                    if (s.id in advisorsEnded) {
                        advisorTurns.remove(s.id)
                        return@withLock
                    }
                    val l = live[s.id] ?: startChat(s.id, user)
                    val content = if (!l.contextSent) {
                        l.contextSent = true
                        l.context.orEmpty() + "\n\n" + forAgent
                    } else {
                        forAgent
                    }
                    try {
                        deliver(l, JsonPrimitive(content))
                    } catch (e: Exception) {
                        // A resume launch that already died is relaunched by finishChat with this input.
                        if (l.resumed && !l.sawInit) return@withLock
                        throw e
                    }
                }
            } catch (e: Exception) {
                log.warn("Advisor input for session {} failed: {}", s.id, e.message)
                systemEvent(s.id, "error", "Could not deliver the message to the agent: ${e.message}")
                if (!isLive(s.id)) {
                    advisorTurns.remove(s.id)
                    setStatus(s.id, SessionStatus.IDLE)
                }
            }
        }
    }

    /**
     * Stops the advisor process of [sessionId], if any, and waits for it (and for its run's reconcile) to finish.
     * [ended] (cancel, merge): the run is over; the session ends `stopped` and queued input is dropped. Otherwise a
     * server-initiated stop: the session goes idle and resumes on the owner's next message.
     */
    suspend fun stopAdvisor(sessionId: UUID, ended: Boolean) {
        if (ended) advisorsEnded += sessionId
        chatLocks.computeIfAbsent(sessionId) { Mutex() }.withLock {
            val l = live[sessionId]
            if (l != null) {
                l.stopReason = if (ended) "stopped" else "server"
                if (ended) systemEvent(sessionId, "warn", "Agent stopped because the run ended")
                l.proc.closeInput()
                l.proc.kill()
                withContext(Dispatchers.IO) { withTimeoutOrNull(15_000) { l.exited.await() } }
            } else if (ended) {
                val st = sessions.get(sessionId)?.status
                if (st != null && st !in setOf(SessionStatus.DONE, SessionStatus.FAILED, SessionStatus.STOPPED)) {
                    setStatus(sessionId, SessionStatus.STOPPED, ended = true)
                }
            }
        }
    }

    /** The run of advisor [sessionId] was merged: the conversation is over (`done`). */
    fun endAdvisor(sessionId: UUID) {
        advisorsEnded += sessionId
        val st = sessions.get(sessionId)?.status ?: return
        if (st != SessionStatus.DONE) setStatus(sessionId, SessionStatus.DONE, ended = true)
    }

    /**
     * A message from the run's owner to a running spec-writer or builder (docs/ARCHITECTURE.md §6 "Messages to running
     * agents"). It is written to the agent's stdin at once and picked up by the CLI at its next step (after the current
     * tool call, or as a further turn when the agent was finishing); the `system` event carrying `messageId` confirms the
     * pickup. Race-free with the end of the stage: stdin is only closed under the same lock, and only when no written
     * message is still waiting, so an accepted message is never dropped silently.
     * [viaChatSession]: the owner's chat session whose agent forwarded it (MCP), or null when sent directly.
     */
    fun sendToStageAgent(user: UserRecord, s: SessionRecord, text: String, viaChatSession: UUID? = null): AgentMessageDto {
        if (s.kind == "chat") throw conflict("Chat sessions take input, not agent messages")
        if (s.kind == ADVISOR_KIND) throw conflict("Talk to the advisor through its run's conversation", "agent_does_not_accept_messages")
        if (s.ownerId != user.id) throw forbidden("Only the owner of this run can send messages to its agents")
        if (s.kind !in STEERABLE_ROLES) {
            throw conflict(
                "The ${s.kind} agent does not accept messages: reviewers and the approver answer in a fixed verdict format. " +
                    "Only a running spec-writer or builder can be messaged.",
                "agent_does_not_accept_messages",
            )
        }
        if (text.isBlank()) throw dk.egernet.factory.badRequest("text must not be empty")
        if (text.length > 100_000) throw dk.egernet.factory.badRequest("text is too long")
        fun notRunning(): Exception {
            val st = sessions.get(s.id)?.status ?: s.status
            val why = if (st == SessionStatus.STARTING || st == SessionStatus.RUNNING) "has not started yet" else "is no longer running (it is $st)"
            return conflict(
                "The ${s.kind} agent $why, so the message cannot be delivered. To change finished work, start a new run " +
                    "(or describe the change on the issue) instead.",
                "agent_not_running",
            )
        }
        val l = live[s.id]?.takeIf { it.steerable } ?: throw notRunning()
        val messageId = UUID.randomUUID().toString()
        // The agent sees who wrote it; the transcript shows the owner's text as written.
        val forAgent = "Message from the run owner (${user.username}) while you work — take it into account:\n\n$text"
        synchronized(l.inputLock) {
            if (l.inputClosed) throw notRunning()
            try {
                l.proc.send(userLine(JsonPrimitive(forAgent)))
            } catch (e: Exception) {
                throw notRunning()
            }
            l.awaiting.addLast(messageId to user.username)
            publish(s.id, "user", buildJsonObject {
                put("text", text); put("username", user.username); put("messageId", messageId)
                put("origin", if (viaChatSession == null) "direct" else "chat")
                viaChatSession?.let { put("viaSessionId", it.toString()) }
            })
        }
        log.info("Message {} queued for {} session {} (via {})", messageId, s.kind, s.id, viaChatSession ?: "direct")
        return AgentMessageDto(
            status = "queued", messageId = messageId, sessionId = s.id.toString(), runId = s.runId?.toString(), role = s.kind,
            note = "Queued — delivered at the agent's next step (after its current tool call, or as a further turn if it is finishing).",
        )
    }

    /** The CLI replayed a stdin message: it has been picked up. */
    private fun onReplay(l: Live) {
        synchronized(l.inputLock) {
            l.replaysSeen++
            val (id, username) = l.awaiting.removeFirstOrNull() ?: return
            if (id != null) {
                publish(l.sessionId, "system", buildJsonObject {
                    put("level", "info"); put("text", "Message from $username delivered to the agent."); put("messageId", id)
                })
            }
        }
    }

    /** Stage ended: refuse further messages and say which accepted ones the agent never picked up. */
    private fun closeSteering(l: Live?) {
        if (l == null || !l.steerable) return
        val left = synchronized(l.inputLock) {
            l.inputClosed = true
            l.awaiting.filter { it.first != null }.also { l.awaiting.clear() }
        }
        for ((id, username) in left) {
            publish(l.sessionId, "system", buildJsonObject {
                put("level", "warn"); put("text", "Message from $username was not delivered: the agent stopped before picking it up."); put("messageId", id)
            })
        }
    }

    /** Owner or admin. Kills the process; a stopped chat can be resumed by sending input again. */
    suspend fun stop(user: UserRecord, s: SessionRecord): SessionRecord {
        if (s.ownerId != user.id && !user.isAdmin) throw forbidden("Only the owner or an admin can stop this session")
        val l = live[s.id]
        if (l != null) {
            l.stopReason = "stopped"
            systemEvent(s.id, "warn", "Stopped by ${user.username}")
            killAndWait(l)
        } else if (s.status !in setOf(SessionStatus.DONE, SessionStatus.FAILED, SessionStatus.STOPPED)) {
            setStatus(s.id, SessionStatus.STOPPED, ended = true)
        }
        return sessions.get(s.id)!!
    }

    /** Kills [l]'s process as a user stop does and waits up to 10 s for it to exit; false if it has not exited by then. */
    private suspend fun killAndWait(l: Live): Boolean {
        l.stopReason = "stopped"
        l.proc.closeInput()
        l.proc.kill()
        return withContext(Dispatchers.IO) { withTimeoutOrNull(10_000) { l.exited.await() } } != null
    }

    /**
     * Owner or admin, chats only. Stops the agent process (if any), then permanently deletes the session with its
     * events, attachment rows and attachment files, disconnects its WebSocket watchers and announces it on the feed.
     * Runs started from the chat are not touched (`runs.started_by_session_id` becomes null).
     */
    suspend fun closeChat(user: UserRecord, s: SessionRecord) {
        if (s.ownerId != user.id && !user.isAdmin) throw forbidden("Only the owner or an admin can close this chat")
        if (s.kind != "chat") throw conflict("Only chat sessions can be closed; cancel the run to stop a pipeline agent", "not_a_chat")
        // Under the chat lock no input coroutine is starting or feeding a process meanwhile.
        chatLocks.computeIfAbsent(s.id) { Mutex() }.withLock {
            sessions.get(s.id) ?: throw notFound("Session not found")
            live[s.id]?.let { l ->
                if (!killAndWait(l)) throw conflict("The agent process did not stop; try again", "agent_not_stopped")
            }
            // The same lock as publish(): once the row is gone, no event can be appended for it any more.
            synchronized(eventLocks.computeIfAbsent(s.id) { Any() }) {
                closed += s.id
                val deleted = try {
                    sessions.delete(s.id)
                } catch (e: Exception) {
                    closed -= s.id
                    throw e
                }
                // Gone some other way: it stays marked closed, which is right for a missing row.
                if (!deleted) throw notFound("Session not found")
            }
        }
        withContext(Dispatchers.IO) {
            attachments.removeSession(s.id)
            onChatClosed?.let { hook -> runCatching { hook(s.id) }.onFailure { log.warn("Removing the images of chat {} failed: {}", s.id, it.message) } }
        }
        checkoutPublished.remove(s.id)
        bus.closeSession(s.id)
        bus.emitFeed(FeedItem.SessionClosed(s.repo, s.id.toString()))
        log.info("Chat {} in {} closed by {}", s.id, s.repo, user.username)
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
            contextRepos = req.contextRepos,
        )
        publishFeed(s.id)
        onSession(s)
        val slot = slot(req.owner.id)
        slot.acquire()
        var token: String? = null
        var l: Live? = null
        try {
            val cred = access.claudeCredential(req.owner)
                ?: return finishStage(s, null, "Run owner has no Claude credential", credentialRejected = true)
            val mayInstall = settings.get().agentsMayInstallSoftware
            val tools = (if (mayInstall) McpRegistry.STAGE_TOOLS - "factory_request_software" else McpRegistry.STAGE_TOOLS) + req.extraTools
            token = mcp.issue(McpGrant(s.id, req.owner.id, req.repo, req.runId, tools))
            val identity = AgentIdentity(req.owner.username, req.owner.displayName, req.owner.githubLogin, access.githubToken(req.owner), cred)
            val steerable = req.role in STEERABLE_ROLES
            val spec = LaunchSpec(
                sessionId = s.id, kind = req.role, model = req.model, workDir = req.workDir, home = workspaces.home(req.owner.username),
                identity = identity, interactive = false, steerable = steerable, resumeClaudeSessionId = req.resumeClaudeSessionId,
                appendSystemPrompt = req.systemPrompt, prompt = req.prompt, jsonSchema = req.jsonSchema,
                disallowedTools = req.disallowedTools, mcpConfigJson = mcpConfig(token),
                mcpToolTimeoutMs = cfg.mcpToolTimeoutMs,
            )
            for ((level, text) in req.notes) systemEvent(s.id, level, text)
            publish(s.id, "user", buildJsonObject { put("text", req.prompt); put("username", req.owner.username) })
            val proc = withContext(Dispatchers.IO) { launcher.launch(spec) }
            val lv = Live(s.id, req.owner.id, req.repo, req.role, proc, token, false, 0.0, 0, req.resumeClaudeSessionId, req.owner.claudeCredEnc)
            lv.steerable = steerable
            l = lv
            if (steerable) {
                // The task prompt is the first stream-json message; stdin stays open for the owner's messages.
                synchronized(lv.inputLock) {
                    proc.send(userLine(JsonPrimitive(req.prompt)))
                    lv.awaiting.addLast(null to req.owner.username)
                }
            }
            live[s.id] = lv
            setStatus(s.id, SessionStatus.RUNNING)
            // A steerable agent is told shortly before the time limit, so it can commit and leave a hand-over note.
            val warning = if (steerable) scope.launch {
                delay(cfg.stageTimeoutMs * TIME_LIMIT_WARNING_PERCENT / 100)
                warnTimeLimit(lv)
            } else null
            val code = try {
                withTimeoutOrNull(cfg.stageTimeoutMs) {
                    pump(lv)
                    proc.awaitExit()
                }
            } finally {
                warning?.cancel()
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
            val credentialRejected = !ok && (lv.authFailed || authProblem(proc.stderrTail()) != null)
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
            val stopped = lv.stopReason == "stopped" || lv.stopReason == "timeout"
            return finishStage(
                s, lv, err,
                usageLimit = if (stopped) null else lv.usage.result(failed = err != null),
                credentialRejected = !stopped && err != null && credentialRejected,
                timedOut = lv.stopReason == "timeout",
            )
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                l?.let { it.stopReason = "stopped"; it.proc.kill() }
                closeSteering(l)
                systemEvent(s.id, "warn", "Agent stopped because the run was cancelled")
                endStage(s.id, SessionStatus.STOPPED)
            }
            throw e
        } catch (e: Exception) {
            log.warn("Stage agent {} failed to run: {}", s.id, e.message)
            l?.proc?.kill()
            return finishStage(s, l, "Could not run agent: ${e.message}")
        } finally {
            // Normally endStage has already removed the session; if publishing its status threw, correct the feed here.
            if (live.remove(s.id) != null) runCatching { publishFeed(s.id) }
            token?.let { mcp.revoke(it) }
            slot.release()
        }
    }

    /**
     * Writes the time-limit warning to a running steerable agent's stdin, like an owner's message (docs/ARCHITECTURE.md §6
     * "Time limit"); its pickup is confirmed the same way. Nothing happens when stdin is already closed.
     */
    private fun warnTimeLimit(l: Live) {
        val left = describeDuration(cfg.stageTimeoutMs - cfg.stageTimeoutMs * TIME_LIMIT_WARNING_PERCENT / 100)
        val text = "Message from Agern: this stage stops at its time limit in about $left. Commit the work you have now " +
            "(a work-in-progress commit is fine) so nothing is lost. Put a hand-over note in that commit's message: what is " +
            "done, what is left, and how to verify it — a later run may continue from your commits. Then go on if time allows."
        val id = UUID.randomUUID().toString()
        synchronized(l.inputLock) {
            if (l.inputClosed) return
            try {
                l.proc.send(userLine(JsonPrimitive(text)))
            } catch (e: Exception) {
                return
            }
            l.awaiting.addLast(id to "Agern")
        }
        systemEvent(l.sessionId, "warn", "Stage time limit: ${TIME_LIMIT_WARNING_PERCENT} % used; asked the agent to commit and leave a hand-over note")
        log.info("Time-limit warning sent to {} session {}", l.kind, l.sessionId)
    }

    private fun finishStage(
        s: SessionRecord, l: Live?, error: String?, usageLimit: UsageLimit? = null, credentialRejected: Boolean = false,
        timedOut: Boolean = false,
    ): StageAgentResult {
        closeSteering(l)
        if (error != null) systemEvent(s.id, "error", error)
        val status = if (l?.stopReason == "stopped") SessionStatus.STOPPED else if (error == null) SessionStatus.DONE else SessionStatus.FAILED
        endStage(s.id, status)
        val rec = sessions.get(s.id)!!
        return StageAgentResult(
            rec, l?.claudeSessionId ?: rec.claudeSessionId, l?.lastResult, error == null, error, usageLimit, credentialRejected, timedOut,
            started = l?.sawInit == true,
        )
    }

    /**
     * Sets a stage agent's terminal status. The session leaves [live] first: [dto] reads `live`, so the feed item that
     * carries the terminal status must already say `live: false`, or clients keep counting the agent as active.
     */
    private fun endStage(sessionId: UUID, status: String) {
        live.remove(sessionId)
        setStatus(sessionId, status, ended = true)
    }

    private fun describeTimeout(): String = describeDuration(cfg.stageTimeoutMs)

    private fun describeDuration(ms: Long): String {
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

    /** Sends [obj] to the session's live subscribers only (seq -1): not stored, not counted in usage. */
    private fun emitLive(l: Live, obj: JsonObject) {
        bus.emit(l.sessionId, EventDto(-1, l.sessionId.toString(), Instant.now().iso(), "claude", obj))
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
            in TRANSIENT_EVENT_TYPES -> {
                emitLive(l, obj)
                return
            }
            "system" -> if (obj["subtype"]?.jsonPrimitive?.contentOrNull in TRANSIENT_SYSTEM_SUBTYPES) {
                emitLive(l, obj)
                return
            }
            "control_response", "control_request", "keep_alive" -> return
            // `--replay-user-messages` echoes each stdin message when the CLI picks it up; the factory's own `user`
            // event already shows it, so the echo only confirms delivery.
            "user" -> if (l.steerable && obj["isReplay"]?.jsonPrimitive?.booleanOrNull == true) {
                onReplay(l)
                return
            }
        }
        publish(l.sessionId, "claude", obj)
        if (!l.interactive || l.kind == ADVISOR_KIND) l.usage.observe(obj)
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "rate_limit_event" -> recordLimits(l, obj)
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
                if (cost != null) recordSpend(l, cost)
                if (l.interactive && l.kind == ADVISOR_KIND) {
                    advisorTurnEnded(l, obj)
                } else if (l.interactive) {
                    l.turnActive = false
                    l.lastUse = System.currentTimeMillis()
                    if (l.stopReason == null) setStatus(l.sessionId, SessionStatus.IDLE) else publishFeed(l.sessionId)
                } else {
                    publishFeed(l.sessionId)
                    // A steerable stage ends when a turn ends and no message is waiting: then stdin is closed. A message
                    // written before this point but not yet picked up makes the CLI run a further turn; its result
                    // decides again. (A CLI that never replays falls back to closing at the first result.)
                    val finished = !l.steerable || synchronized(l.inputLock) {
                        if (l.awaiting.isEmpty() || l.replaysSeen == 0) {
                            l.inputClosed = true
                            l.proc.closeInput()
                            true
                        } else {
                            false
                        }
                    }
                    // An agent should exit by itself after its final result; make sure it does.
                    if (finished) scope.launch {
                        delay(30_000)
                        if (!l.exited.isCompleted && live[l.sessionId] === l) l.proc.kill()
                    }
                }
            }
        }
    }

    /**
     * An advisor turn ended with [result]: say so when it failed on the owner's usage limit, then reconcile the run and
     * only after that let the session count as idle (docs/ARCHITECTURE.md §6 "Conversation at needs human").
     */
    private fun advisorTurnEnded(l: Live, result: JsonObject) {
        val failed = result["is_error"]?.jsonPrimitive?.booleanOrNull == true ||
            (result["subtype"]?.jsonPrimitive?.contentOrNull ?: "success") != "success"
        l.usage.result(failed)?.let { limit ->
            val reset = limit.resetsAt?.let { " It resets at ${it.iso()}." } ?: ""
            systemEvent(
                l.sessionId, "warn",
                "Your Claude usage limit was reached, so the agent could not answer.$reset Nothing is lost: the conversation continues with your next message.",
            )
        }
        l.usage = UsageLimitDetector()
        publishFeed(l.sessionId)
        scope.launch {
            afterTurn(l.sessionId)
            advisorTurns.remove(l.sessionId)
            l.turnActive = false
            l.lastUse = System.currentTimeMillis()
            if (l.stopReason == null && live[l.sessionId] === l) setStatus(l.sessionId, SessionStatus.IDLE) else publishFeed(l.sessionId)
        }
    }

    /**
     * Records the limit windows of a `rate_limit_event` for the session's owner (docs/ARCHITECTURE.md §5 "Claude usage").
     * Never fails the session: a malformed event or a database error is logged and otherwise ignored.
     */
    private fun recordLimits(l: Live, obj: JsonObject) {
        try {
            val cred = l.credEnc ?: return
            val info = obj["rate_limit_info"] as? JsonObject ?: return
            val windows = RateLimitWindows.parse(info)
            if (windows.isNotEmpty() && usageStore.recordWindows(l.ownerId, cred, windows) > 0) bus.emitFeed(FeedItem.Usage(l.ownerId))
        } catch (e: Exception) {
            log.warn("Could not record Claude usage limits of session {}: {}", l.sessionId, e.toString())
        }
    }

    /** Adds what this process cost since its previous `result` ([total] is the process's running total) to the owner's spend. */
    private fun recordSpend(l: Live, total: Double) {
        try {
            val delta = total - l.procCost
            l.procCost = total
            if (delta > 0.0) {
                usageStore.addSpend(l.ownerId, delta)
                bus.emitFeed(FeedItem.Usage(l.ownerId))
            }
        } catch (e: Exception) {
            log.warn("Could not record the Claude spend of session {}: {}", l.sessionId, e.toString())
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
