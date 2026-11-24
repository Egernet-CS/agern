package dk.egernet.factory.sandbox

import dk.egernet.factory.AppJson
import dk.egernet.factory.Config
import dk.egernet.factory.OwnerDto
import dk.egernet.factory.RunSandboxDto
import dk.egernet.factory.RunStatus
import dk.egernet.factory.SandboxApprovalDto
import dk.egernet.factory.SandboxContainerDto
import dk.egernet.factory.SandboxPlatformDto
import dk.egernet.factory.SandboxReadyDto
import dk.egernet.factory.SandboxUserDto
import dk.egernet.factory.SandboxConfig
import dk.egernet.factory.SandboxDefinitionDto
import dk.egernet.factory.SandboxLimitsDto
import dk.egernet.factory.SandboxServiceDto
import dk.egernet.factory.SandboxStatusDto
import dk.egernet.factory.PendingSandboxDto
import dk.egernet.factory.RunningSandboxDto
import dk.egernet.factory.SandboxOverviewDto
import dk.egernet.factory.SandboxServerLimitsDto
import dk.egernet.factory.agent.FeedItem
import dk.egernet.factory.conflict
import dk.egernet.factory.db.RunStore
import dk.egernet.factory.db.SandboxApprovalStore
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.db.iso
import dk.egernet.factory.forbidden
import dk.egernet.factory.git.GitOps
import dk.egernet.factory.github.Access
import dk.egernet.factory.github.GitHubException
import dk.egernet.factory.notFound
import dk.egernet.factory.unprocessable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.add
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.time.Instant

/** What a tool call answers: JSON or a message for the agent, and whether it is an error. */
data class SandboxAnswer(val isError: Boolean, val text: String)

/**
 * Per-run sandboxes (docs/ARCHITECTURE.md §5 "Sandboxes"): reads and checks a run's definition when it starts, creates
 * the containers lazily on the builder's first command, runs commands, enforces the concurrency cap and removes
 * everything when the run's build/review loop is over (and leftovers at server start).
 *
 * A run's sandbox is a private network with the services, plus one container per image that runs commands: the default
 * image and, on first use, each platform. Every such container takes one slot of FACTORY_SANDBOX_MAX_CONCURRENT.
 *
 * The sandbox gives the project its toolchain, sidecar services, resource limits and a clean environment. It is NOT a
 * security boundary against the agent: the agent runs on the host as the same OS user that owns the rootless containers.
 */
class SandboxManager(
    private val cfg: Config,
    /** null when sandboxes are switched off. */
    private val runtime: SandboxRuntime?,
    private val approvals: SandboxApprovalStore,
    private val runs: RunStore,
    private val access: Access,
    private val git: GitOps,
    /**
     * Where commands run (the application's scope): independent of the tool call that started them, so a dropped or
     * timed-out HTTP request never kills or loses a command.
     */
    private val scope: CoroutineScope,
    /** Appends a `system` event (payload as given) to an agent session's transcript. */
    private val publishSystem: (UUID, JsonObject) -> Unit,
    /** Announces approval changes on the live feed (`sandbox_approval`). */
    private val emitFeed: (FeedItem) -> Unit = {},
    /**
     * Development only (FACTORY_DEV_FAKE_GITHUB, whose workspaces are local `git init` repositories): where a run reads
     * its definition instead of the base commit, by repository. Null: from git, as in production.
     */
    private val devDefinitionSource: ((String) -> String?)? = null,
    /** The admins, who can approve definitions (shown to everyone on the status). */
    private val approvers: () -> List<OwnerDto> = { emptyList() },
    /** Announces a change of run [UUID]'s stored state (here: the platforms it used) on the live feed. */
    private val emitRun: (UUID) -> Unit = {},
    /** Pause between two runs of a service's readiness check. */
    private val readyPollMs: Long = 1000,
) {
    private val log = LoggerFactory.getLogger(SandboxManager::class.java)
    private val sc: SandboxConfig get() = cfg.sandbox

    val enabled: Boolean get() = sc.enabled && runtime != null

    private val slots = Semaphore(sc.maxConcurrent)
    private val sandboxes = ConcurrentHashMap<UUID, RunSandbox>()

    private sealed interface Plan {
        data class Available(val definition: SandboxDefinition, val sha256: String) : Plan
        data class Unavailable(val reason: String) : Plan
    }

    /** A container of the run that runs commands: the default image ([key] `default`) or a platform. */
    private class Box(val key: String, val image: String) {
        @Volatile var createdAt: Instant = Instant.now()
        @Volatile var lastUsed: Long = System.nanoTime()
        /** Who commands run as (null: the container's root), known once `user:` was prepared. */
        @Volatile var user: ExecUser? = null
    }

    /**
     * One command of a run, foreground or background, under its [handle]. [result] is completed with the answer the agent
     * gets (the output tail, or an error) when the command ends.
     */
    private class Command(
        val handle: String,
        val key: String,
        val command: String,
        val sessionId: UUID,
        val background: Boolean,
        val timeoutMs: Long,
        val startedAt: Long = System.currentTimeMillis(),
    ) {
        val result = CompletableDeferred<SandboxAnswer>()
        val running: Boolean get() = !result.isCompleted
        val elapsedMs: Long get() = System.currentTimeMillis() - startedAt
    }

    private class RunSandbox(val runId: UUID, val repo: String, val worktree: Path, val plan: Plan, @Volatile var info: RunSandboxDto) {
        /**
         * Serialises creation and commands: one command at a time per run. Taken by the tool call (waiting for a
         * foreground command, `tryLock` for a background one) and released by the command's job when it ends. Release,
         * stop and waits never take it.
         */
        val lock = Mutex()
        /** The command whose job runs now (null between commands). */
        @Volatile var current: Command? = null
        /** The most recently started command: what `factory_sandbox_wait` without a handle waits for. */
        @Volatile var latest: Command? = null
        /** The running command and the last [KEPT_RESULTS] finished ones, by handle, oldest first. Guarded by itself. */
        val commands = LinkedHashMap<String, Command>()
        /** The run's network and services exist. */
        @Volatile var infra = false
        /** When the network and services were created (null while there are none). */
        @Volatile var createdAt: Instant? = null
        /** Command containers that exist now, by platform key. */
        val boxes = ConcurrentHashMap<String, Box>()
        /** Slots of the concurrency cap this run holds: one per command container (or one being created). */
        val slotsHeld = AtomicInteger()
        /** Services whose readiness check passed since the services were (re)created. */
        val ready: MutableSet<String> = ConcurrentHashMap.newKeySet()
        @Volatile var released = false
        /** The builder session that used the sandbox last (where an admin's stop is reported). */
        @Volatile var sessionId: UUID? = null
        /** Bumped by every admin stop, so a creation or command in flight notices its containers are gone. */
        val generation = AtomicInteger()

        val created: Boolean get() = infra || boxes.isNotEmpty()
        val definition: SandboxDefinition? get() = (plan as? Plan.Available)?.definition
    }

    /** Every container a run can have, existing or not (removal ignores missing ones). */
    private fun names(rs: RunSandbox): List<String> {
        val def = rs.definition ?: return listOf(containerName(rs.runId))
        return listOf(containerName(rs.runId)) + def.platforms.map { platformContainerName(rs.runId, it.name) } +
            def.services.map { serviceContainerName(rs.runId, it.name) }
    }

    private fun boxName(runId: UUID, key: String) = if (key == DEFAULT) containerName(runId) else platformContainerName(runId, key)

    private fun announce(rs: RunSandbox, change: String) = emitFeed(FeedItem.Sandboxes(rs.repo, rs.runId.toString(), change))

    // ------------------------------------------------------------------ run start

    /**
     * Decides whether run [runId] gets a sandbox: reads `.agern/sandbox.yml` from [baseRef] (the commit the run's
     * worktree was created from, so a run cannot approve its own change to the file), validates it and checks the
     * approval. Never throws; the decision is stored on the run and returned.
     */
    suspend fun prepare(runId: UUID, repo: String, worktree: Path, baseRef: String, home: Path): RunSandboxDto {
        if (!enabled) {
            return record(runId, RunSandboxDto("disabled", "Sandboxes are switched off on this server (FACTORY_SANDBOX=off).", null, null), null, repo, worktree)
        }
        val content = try {
            withContext(Dispatchers.IO) {
                devDefinitionSource?.invoke(repo) ?: git.readFile(worktree, baseRef, SANDBOX_FILE, home, SandboxDefinitions.MAX_BYTES)
            }
        } catch (e: Exception) {
            return record(runId, RunSandboxDto("invalid", "Could not read $SANDBOX_FILE from $baseRef: ${e.message}", null, null), null, repo, worktree)
        }
        if (content == null) {
            return record(runId, RunSandboxDto("none", "The repository has no $SANDBOX_FILE on its default branch ($baseRef).", null, null), null, repo, worktree)
        }
        return when (val parsed = SandboxDefinitions.parse(content, sc)) {
            is ParsedDefinition.Invalid -> record(
                runId, RunSandboxDto("invalid", "$SANDBOX_FILE on $baseRef is invalid: " + parsed.errors.joinToString("; "), parsed.sha256, null),
                null, repo, worktree,
            )
            is ParsedDefinition.Valid -> {
                val approval = withContext(Dispatchers.IO) { approvals.get(repo) }
                val def = parsed.definition
                if (approval != null && approval.sha256 == parsed.sha256) {
                    val platforms = if (def.platforms.isEmpty()) "" else "; platforms ${def.platforms.joinToString(", ") { "${it.name} (${it.image})" }}"
                    record(
                        runId,
                        RunSandboxDto(
                            "approved",
                            "Sandbox definition ${parsed.sha256.take(12)} approved by ${approval.approvedByUsername}; image ${def.image}$platforms. " +
                                "It is created on the builder's first sandbox command.",
                            parsed.sha256, def.image, platforms = def.platforms.map { it.name },
                        ),
                        Plan.Available(def, parsed.sha256), repo, worktree,
                    )
                } else {
                    val why = if (approval == null) "no version of it has been approved by an admin"
                    else "it changed since an admin approved version ${approval.sha256.take(12)}"
                    record(
                        runId,
                        RunSandboxDto("unapproved", "$SANDBOX_FILE (${parsed.sha256.take(12)}) is not approved: $why. An admin can approve it in the project's sandbox settings.", parsed.sha256, def.image),
                        null, repo, worktree,
                    )
                }
            }
        }
    }

    private suspend fun record(runId: UUID, info: RunSandboxDto, available: Plan.Available?, repo: String, worktree: Path): RunSandboxDto {
        withContext(Dispatchers.IO) { runs.setSandbox(runId, info) }
        if (enabled) sandboxes[runId] = RunSandbox(runId, repo, worktree, available ?: Plan.Unavailable(info.reason), info)
        return info
    }

    // ------------------------------------------------------------------ tools

    private val execCapMs: Long get() = minOf(sc.execTimeoutMs, cfg.stageTimeoutMs)

    /** `factory_sandbox_info` for an agent of run [runId]. */
    suspend fun info(runId: UUID): SandboxAnswer {
        val rs = sandboxes[runId]
        val plan = rs?.plan
        // Exposed ports are known once the images are pulled, i.e. after the sandbox was created.
        val ports: Map<String, List<String>> = if (plan is Plan.Available && rs.infra) withContext(Dispatchers.IO) {
            plan.definition.services.associate { it.name to runtime!!.exposedPorts(it.image) }
        } else emptyMap()
        val obj = buildJsonObject {
            put("available", plan is Plan.Available && !rs.released)
            put("state", rs?.info?.state ?: if (enabled) "none" else "disabled")
            put("reason", when {
                !enabled -> "Sandboxes are switched off on this server. Build and test on the host as usual."
                rs == null -> "This run has no sandbox."
                rs.released -> "The run's sandbox has been removed (the build/review part of the run is over)."
                else -> rs.info.reason
            })
            if (plan is Plan.Available) {
                val def = plan.definition
                put("image", def.image)
                put("created", rs.boxes.containsKey(DEFAULT))
                put("workdir", WORKDIR)
                put("shell", "Commands run as `sh -lc <command>` in $WORKDIR, which is your working directory (the same files).")
                if (def.user != null) {
                    putJsonObject("user") {
                        put("name", rs.boxes.values.firstNotNullOfOrNull { it.user?.name } ?: def.user.name)
                        put("uid", def.user.uid)
                        put("note", "Commands run as this user, not root; the setup script ran as root. Files you create in $WORKDIR belong to you on the host.")
                    }
                }
                putJsonArray("platforms") {
                    for (p in def.platforms) addJsonObject {
                        put("name", p.name); put("image", p.image); put("setup", p.setup != null)
                        put("created", rs.boxes.containsKey(p.name))
                    }
                }
                if (def.platforms.isNotEmpty()) {
                    put("platformNote", "Pass `platform` to factory_sandbox_exec to run a command in a platform's image (its own container, " +
                        "created on first use, same files, services and limits; caches are per platform). Without `platform` commands run in the " +
                        "default image. Run the test suite on every platform before you finish and report the result per platform.")
                }
                putJsonArray("services") {
                    for (s in def.services) addJsonObject {
                        put("name", s.name); put("host", s.name); put("image", s.image)
                        putJsonArray("ports") { ports[s.name].orEmpty().forEach { add(it) } }
                        put("readiness", when {
                            s.ready == null -> "unchecked"
                            s.name in rs.ready -> "ready"
                            else -> "checked on creation"
                        })
                    }
                }
                if (def.services.any { it.ready == null }) {
                    put("servicesNote", "Services without a readiness check (`ready:` in $SANDBOX_FILE) may still be starting when your first " +
                        "command runs: retry a connection for a few seconds before you treat it as a failure.")
                }
                putJsonArray("caches") { def.caches.forEach { add(it) } }
                putJsonArray("env") { def.env.keys.forEach { add(it) } }
                put("setup", def.setup != null)
                putJsonObject("limits") {
                    put("cpus", def.cpus); put("memory", SandboxConfig.formatMemory(def.memoryBytes))
                    put("execTimeoutSeconds", execCapMs / 1000); put("outputTailChars", OUTPUT_TAIL_CHARS)
                    put("toolCallTimeoutSeconds", cfg.mcpToolTimeoutMs / 1000)
                }
                rs.current?.takeIf { it.running }?.let { c ->
                    putJsonObject("command") { put("handle", c.handle); put("running", true); put("elapsedMs", c.elapsedMs) }
                }
                putJsonObject("capacity") { put("inUse", inUse()); put("max", sc.maxConcurrent); put("heldByThisRun", rs.slotsHeld.get()) }
            }
        }
        return SandboxAnswer(false, obj.toString())
    }

    /**
     * `factory_sandbox_exec`: runs [command] with `sh -lc` in the run's sandbox — in [platform]'s container when given —
     * creating it first if needed. The full output (up to [ExecLimits.LOG_CHARS]) goes into [sessionId]'s transcript as a
     * `system` event; the agent gets the tail. The command runs in a job on the manager's scope under a handle: in the
     * foreground the call waits for it, with [background] it returns the handle at once ([wait] gets the result).
     */
    suspend fun exec(
        runId: UUID, sessionId: UUID, command: String, timeoutSeconds: Long?, platform: String? = null, background: Boolean = false,
    ): SandboxAnswer {
        if (!enabled) return SandboxAnswer(true, "Sandboxes are switched off on this server. Build and test on the host as usual.")
        val rs = sandboxes[runId] ?: return SandboxAnswer(true, "This run has no sandbox. Build and test on the host as usual.")
        val plan = rs.plan
        if (plan !is Plan.Available) return SandboxAnswer(true, "No sandbox for this run: ${rs.info.reason} Build and test on the host as usual.")
        val def = plan.definition
        val key = platform?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT
        if (key != DEFAULT && def.platforms.none { it.name == key }) {
            return SandboxAnswer(true, "Unknown platform '$key'. " + if (def.platforms.isEmpty()) "This sandbox has no platforms; leave `platform` out."
                else "This sandbox has: $DEFAULT (${def.image}), " + def.platforms.joinToString(", ") { "${it.name} (${it.image})" } + ".")
        }
        if (command.isBlank()) return SandboxAnswer(true, "command must not be empty")
        if (command.length > 100_000 || '\u0000' in command) return SandboxAnswer(true, "command is too long or contains NUL")
        val cap = execCapMs
        if (timeoutSeconds != null && timeoutSeconds <= 0) return SandboxAnswer(true, "timeoutSeconds must be positive")
        val timeoutMs = minOf(timeoutSeconds?.let { it * 1000 } ?: cap, cap)
        // One command at a time: a background command refuses everything else; a foreground one queues foreground calls.
        rs.current?.takeIf { it.running && (background || it.background) }?.let { return busy(it) }
        if (background) {
            if (!rs.lock.tryLock()) return rs.current?.let { busy(it) } ?: SandboxAnswer(true, "Another sandbox command of this run is " +
                "being started. Wait for it with factory_sandbox_wait before starting another.")
        } else {
            rs.lock.lock()
        }
        var handedOver = false
        try {
            if (rs.released) return SandboxAnswer(true, "The run's sandbox has been removed.")
            rs.sessionId = sessionId
            if (!rs.boxes.containsKey(key)) create(rs, plan, key, sessionId)?.let { return SandboxAnswer(true, it) }
            val box = rs.boxes[key] ?: return SandboxAnswer(true, STOPPED_WHILE_RUNNING)
            box.lastUsed = System.nanoTime()
            noteUsed(rs, key)
            val cmd = Command(newHandle(rs), key, command, sessionId, background, timeoutMs)
            synchronized(rs.commands) { rs.commands[cmd.handle] = cmd }
            rs.latest = cmd
            rs.current = cmd
            val generation = rs.generation.get()
            // ATOMIC: the job runs (and frees the lock) even when the scope is cancelled before it starts.
            scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    val answer = try {
                        runCommand(rs, def, box, cmd, generation)
                    } catch (e: Exception) {
                        SandboxAnswer(true, "The sandbox command could not be run: ${e.message}")
                    }
                    forget(rs, cmd)
                    cmd.result.complete(answer)
                } finally {
                    cmd.result.complete(SandboxAnswer(true, "The sandbox command could not be run: the server is shutting down."))
                    if (rs.current === cmd) rs.current = null
                    rs.lock.unlock()
                }
            }
            handedOver = true
            if (!background) return cmd.result.await()
            return SandboxAnswer(false, buildJsonObject {
                put("handle", cmd.handle)
                put("running", true)
                put("timeoutSeconds", timeoutMs / 1000)
                if (def.platforms.isNotEmpty()) put("platform", key)
                put("note", "Started in the background. Call factory_sandbox_wait to get the exit code and output; do not poll with sleep.")
            }.toString())
        } finally {
            if (!handedOver) rs.lock.unlock()
        }
    }

    /** The part of a command after the container exists: runs it, handles a timeout, logs it and builds the answer. */
    private suspend fun runCommand(rs: RunSandbox, def: SandboxDefinition, box: Box, cmd: Command, generation: Int): SandboxAnswer {
        val key = cmd.key
        val name = boxName(rs.runId, key)
        val r = try {
            withContext(Dispatchers.IO) { runtime!!.exec(name, shellCommand(cmd.command), WORKDIR, cmd.timeoutMs, box.user) }
        } catch (e: Exception) {
            if (rs.generation.get() != generation) return SandboxAnswer(true, STOPPED_WHILE_RUNNING)
            return SandboxAnswer(true, "The sandbox command could not be run: ${e.message}")
        }
        box.lastUsed = System.nanoTime()
        if (rs.released) return SandboxAnswer(true, REMOVED_WHILE_RUNNING)
        if (rs.generation.get() != generation) return SandboxAnswer(true, STOPPED_WHILE_RUNNING)
        if (r.timedOut) {
            // Kill whatever the command left running; files (and what setup installed) are kept.
            runCatching { withContext(Dispatchers.IO) { runtime!!.restartContainer(name) } }
                .onFailure { log.warn("Restarting sandbox {} of run {} after a timeout failed: {}", key, rs.runId, it.message) }
        }
        logEvent(cmd.sessionId, "exec", cmd.command, r, cmd.timeoutMs, platformLabel(def, key))
        val tail = r.output.takeLast(OUTPUT_TAIL_CHARS)
        return SandboxAnswer(false, buildJsonObject {
            put("handle", cmd.handle)
            put("exitCode", r.exitCode)
            put("output", tail)
            put("truncated", r.outputTruncated || tail.length < r.output.length)
            put("durationMs", r.durationMs)
            put("timedOut", r.timedOut)
            if (def.platforms.isNotEmpty()) put("platform", key)
            if (r.timedOut) put("note", "Stopped after ${cmd.timeoutMs / 1000} s; every process in the container was killed (files are kept).")
        }.toString())
    }

    private fun busy(c: Command) = SandboxAnswer(true, "A command is still running in this run's sandbox (handle ${c.handle}, started " +
        "${c.elapsedMs / 1000} s ago). Wait for it with factory_sandbox_wait before starting another.")

    private val random = SecureRandom()

    /** `cmd-` and 8 lower-case hex characters, unique among the run's commands. */
    private fun newHandle(rs: RunSandbox): String = synchronized(rs.commands) {
        generateSequence { "cmd-%08x".format(random.nextInt()) }.first { it !in rs.commands }
    }

    /**
     * Drops the oldest finished commands so that, with [ending] (about to finish), the run keeps [KEPT_RESULTS] results.
     * Called before [ending]'s result is completed, so a caller woken by it already sees the trimmed list.
     */
    private fun forget(rs: RunSandbox, ending: Command) = synchronized(rs.commands) {
        val finished = rs.commands.values.filter { it !== ending && !it.running }
        finished.take((finished.size - (KEPT_RESULTS - 1)).coerceAtLeast(0)).forEach { rs.commands.remove(it.handle) }
    }

    /**
     * `factory_sandbox_wait`: waits up to [waitSeconds] (null: until it ends; capped at the command limit) for the command
     * [handle] of run [runId] — blank: the most recently started one — and returns its result, or that it still runs.
     * Never takes the run's command lock, so any number of waits can run at once.
     */
    suspend fun wait(runId: UUID, handle: String?, waitSeconds: Long?): SandboxAnswer {
        if (!enabled) return SandboxAnswer(true, "Sandboxes are switched off on this server. Build and test on the host as usual.")
        val rs = sandboxes[runId] ?: return SandboxAnswer(true, "This run has no sandbox. Build and test on the host as usual.")
        val plan = rs.plan
        if (plan !is Plan.Available) return SandboxAnswer(true, "No sandbox for this run: ${rs.info.reason} Build and test on the host as usual.")
        if (waitSeconds != null && waitSeconds < 0) return SandboxAnswer(true, "waitSeconds must be a whole number of seconds, 0 or more")
        val wanted = handle?.trim()?.takeIf { it.isNotEmpty() }
        val cmd = if (wanted == null) rs.latest ?: return SandboxAnswer(true, "No sandbox command has been started in this run yet.")
        else synchronized(rs.commands) { rs.commands[wanted] } ?: return SandboxAnswer(true, "Unknown handle '${wanted.take(64)}'. Handles " +
            "come from factory_sandbox_exec; older results are dropped after $KEPT_RESULTS newer commands.")
        val answer = when {
            waitSeconds == null -> cmd.result.await()
            waitSeconds == 0L || !cmd.running -> if (cmd.running) null else cmd.result.await()
            else -> withTimeoutOrNull(minOf(waitSeconds * 1000, execCapMs)) { cmd.result.await() }
        } ?: return SandboxAnswer(false, buildJsonObject {
            put("handle", cmd.handle)
            put("running", true)
            put("elapsedMs", cmd.elapsedMs)
            put("note", "Still running. Call factory_sandbox_wait again.")
        }.toString())
        if (answer.isError) return answer
        val done = AppJson.parseToJsonElement(answer.text).jsonObject
        return SandboxAnswer(false, buildJsonObject {
            put("handle", cmd.handle)
            put("running", false)
            for ((k, v) in done) if (k != "handle") put(k, v)
        }.toString())
    }

    /** What the transcript calls the container a command ran in: null when the definition has no platforms. */
    private fun platformLabel(def: SandboxDefinition, key: String): String? = if (def.platforms.isEmpty()) null else key

    /** Records on the run where the builder ran commands (only when there are platforms), so the run page can say. */
    private suspend fun noteUsed(rs: RunSandbox, key: String) {
        val def = rs.definition ?: return
        if (def.platforms.isEmpty() || key in rs.info.platformsUsed) return
        rs.info = rs.info.copy(platformsUsed = rs.info.platformsUsed + key)
        runCatching { withContext(Dispatchers.IO) { runs.setSandbox(rs.runId, rs.info) } }
            .onFailure { log.warn("Recording the platforms used by run {} failed: {}", rs.runId, it.message) }
        emitRun(rs.runId)
    }

    /**
     * Takes a slot of the concurrency cap for a new command container of [rs]. When none is free, the run's own other
     * command containers (idle: one command at a time per run) are removed, least recently used first, until one is.
     */
    private fun acquireSlot(rs: RunSandbox, key: String, sessionId: UUID): Boolean {
        while (true) {
            if (slots.tryAcquire()) {
                rs.slotsHeld.incrementAndGet()
                return true
            }
            val victim = rs.boxes.values.filter { it.key != key }.minByOrNull { it.lastUsed } ?: return false
            removeBox(rs, victim)
            publishSystem(sessionId, systemPayload("info", "Removed this run's idle `${victim.key}` container to make room for `$key` " +
                "(the server's sandbox slots are all in use; FACTORY_SANDBOX_MAX_CONCURRENT=${sc.maxConcurrent}). It is created again when used."))
        }
    }

    /** Removes one command container and frees its slot; the network and services stay. */
    private fun removeBox(rs: RunSandbox, box: Box) {
        if (rs.boxes.remove(box.key, box)) {
            runCatching { runtime!!.removeContainers(listOf(boxName(rs.runId, box.key))) }
                .onFailure { log.warn("Removing sandbox container {} of run {} failed: {}", box.key, rs.runId, it.message) }
            releaseSlot(rs)
            announce(rs, "removed")
        }
    }

    private fun releaseSlot(rs: RunSandbox) {
        if (rs.slotsHeld.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) slots.release()
    }

    /**
     * Creates the command container [key] (`default` or a platform) — and first, when the run has none yet, its network,
     * services (waiting for their readiness checks) — prepares `user:`, runs the setup script. Returns an error message,
     * or null.
     */
    private suspend fun create(rs: RunSandbox, plan: Plan.Available, key: String, sessionId: UUID): String? {
        val rt = runtime!!
        val def = plan.definition
        val platform = def.platforms.firstOrNull { it.name == key }
        val image = platform?.image ?: def.image
        val setup = if (platform != null) platform.setup else def.setup
        if (!withContext(Dispatchers.IO) { acquireSlot(rs, key, sessionId) }) {
            return "The server's sandboxes are all in use (${sc.maxConcurrent} of ${sc.maxConcurrent} containers, by other runs). " +
                "Nothing was run. Try again in a few minutes; meanwhile continue with work that needs no build."
        }
        val generation = rs.generation.get()
        val started = System.currentTimeMillis()
        val what = if (key == DEFAULT) "the sandbox" else "platform `$key`"
        publishSystem(sessionId, systemPayload("info", "Creating $what: image $image" +
            (if (!rs.infra && def.services.isNotEmpty()) ", services ${def.services.joinToString(", ") { it.name }}" else "") + " …"))
        var box: Box? = null
        /** Removes what this attempt created: everything when the run has no other container, else only this one. */
        fun undo() {
            val others = rs.boxes.keys.any { it != key }
            if (!others) teardown(rs)
            else {
                val b = box
                if (b != null && rs.boxes[key] === b) removeBox(rs, b) else releaseSlot(rs)
            }
        }
        try {
            withContext(Dispatchers.IO) {
                if (!rs.infra) {
                    rt.createNetwork(networkName(rs.runId), labels(rs.runId, rs.repo, "network"))
                    rs.infra = true
                    rs.createdAt = Instant.now()
                    rs.ready.clear()
                    for (s in def.services) rt.startContainer(serviceSpec(rs.runId, rs.repo, s, def))
                }
                for (c in def.caches) {
                    val labels = linkedMapOf(LABEL_REPO to rs.repo, "agern.cache" to c)
                    if (platform != null) labels["agern.platform"] = platform.name
                    rt.ensureVolume(cacheVolumeName(rs.repo, c, platform?.name), labels)
                }
                rt.startContainer(commandSpec(rs.runId, rs.repo, rs.worktree, def, platform))
                box = Box(key, image).also { rs.boxes[key] = it }
            }
            announce(rs, "created")
            if (rs.released) {
                teardown(rs)
                return "The run ended while its sandbox was being created."
            }
            if (rs.generation.get() != generation) {
                teardown(rs)
                return "An admin stopped this run's sandbox while it was being created. Nothing was run; the next command creates it again."
            }
            // Services: wait for the ones with a readiness check (once per network; a platform reuses them).
            for (s in def.services) {
                val ready = s.ready ?: continue
                if (s.name in rs.ready) continue
                val error = withContext(Dispatchers.IO) { waitReady(rs, s, ready, generation) }
                if (error != null) {
                    undo()
                    publishSystem(sessionId, systemPayload("warn", error))
                    return "$error\nThe sandbox was removed; the next command tries again. If the service needs longer, raise " +
                        "`ready.timeoutSeconds` in $SANDBOX_FILE (needs an admin's approval); otherwise report the problem in your summary."
                }
            }
            val name = boxName(rs.runId, key)
            val u = def.user
            if (u != null) {
                val r = withContext(Dispatchers.IO) {
                    rt.exec(name, listOf("sh", "-c", PREPARE_USER_SCRIPT, "agern-user", u.uid.toString(), u.name.orEmpty()) + def.caches, WORKDIR, 120_000)
                }
                val line = r.output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("AGERN_USER ") }?.split(' ')
                if (r.timedOut || r.exitCode != 0 || line == null || line.size < 3) {
                    undo()
                    return "Could not prepare the sandbox's user '${u.display}' (" + (if (r.timedOut) "timed out" else "exit ${r.exitCode}") + "): " +
                        r.output.trim().takeLast(2000) + "\nThe definition comes from the default branch and needs an admin's approval to change; " +
                        "report the problem in your summary."
                }
                box!!.user = ExecUser(u.uid, u.uid, line[1], line.drop(2).joinToString(" "))
            }
            if (setup != null) {
                val r = withContext(Dispatchers.IO) { rt.exec(name, shellCommand(setup), WORKDIR, execCapMs) }
                logEvent(sessionId, "setup", setup, r, execCapMs, platformLabel(def, key))
                if (r.timedOut || r.exitCode != 0) {
                    undo()
                    return "The ${if (key == DEFAULT) "sandbox's" else "platform `$key`'s"} setup script failed (" +
                        (if (r.timedOut) "timed out" else "exit ${r.exitCode}") +
                        "), so the container was removed. Output (tail):\n" + r.output.takeLast(4000) +
                        "\nThe definition comes from the default branch and needs an admin's approval to change; report the problem in your summary."
                }
                // Setup runs as root: hand what it left in the user's home and the worktree to the user (not into cache volumes).
                box!!.user?.let { eu ->
                    withContext(Dispatchers.IO) {
                        runCatching { rt.exec(name, listOf("sh", "-c", HAND_OVER_SCRIPT, "agern-hand-over", eu.uid.toString(), eu.home, WORKDIR), WORKDIR, 600_000) }
                    }
                }
            }
            val who = box!!.user?.let { ", commands as ${it.name} (uid ${it.uid})" } ?: ""
            publishSystem(sessionId, systemPayload("info", "${if (key == DEFAULT) "Sandbox" else "Platform `$key`"} ready in " +
                "${(System.currentTimeMillis() - started) / 1000.0} s: $image, ${PodmanRuntime.formatCpus(def.cpus)} CPUs, " +
                "${SandboxConfig.formatMemory(def.memoryBytes)} memory$who."))
            return null
        } catch (e: Exception) {
            log.warn("Creating sandbox container {} of run {} failed: {}", key, rs.runId, e.message)
            undo()
            publishSystem(sessionId, systemPayload("warn", "Could not create $what: ${e.message}"))
            return "Could not create $what: ${e.message}"
        }
    }

    /** Runs a service's readiness check until it passes; an error message (with the service's last log lines) or null. */
    private fun waitReady(rs: RunSandbox, s: SandboxService, ready: SandboxReady, generation: Int): String? {
        val rt = runtime!!
        val name = serviceContainerName(rs.runId, s.name)
        val deadline = System.nanoTime() + ready.timeoutSeconds * 1_000_000_000L
        var last = ""
        while (true) {
            if (rs.released || rs.generation.get() != generation) return "The sandbox was removed while waiting for service '${s.name}'."
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000
            val r = try {
                rt.exec(name, listOf("sh", "-c", ready.command), "/", remainingMs.coerceIn(1_000, 10_000))
            } catch (e: Exception) {
                ExecResult(-1, e.message ?: "", false, false, 0)
            }
            if (!r.timedOut && r.exitCode == 0) {
                rs.ready += s.name
                return null
            }
            last = if (r.timedOut) "timed out" else "exit ${r.exitCode}" + r.output.trim().takeLast(300).let { if (it.isEmpty()) "" else ": $it" }
            if (System.nanoTime() >= deadline) break
            Thread.sleep(readyPollMs)
        }
        val logs = runCatching { rt.logs(name, 30) }.getOrDefault("").trim()
        return "Service '${s.name}' did not become ready within ${ready.timeoutSeconds} s: its check `${ready.command}` last ended with $last." +
            if (logs.isEmpty()) " The service printed nothing." else "\nIts last log lines:\n$logs"
    }

    private fun systemPayload(level: String, text: String) = buildJsonObject { put("level", level); put("text", text) }

    /** The full log of a setup script or command, kept in the agent's transcript for the run page. */
    private fun logEvent(sessionId: UUID, kind: String, command: String, r: ExecResult, timeoutMs: Long, platform: String?) {
        val what = if (kind == "setup") "setup script" else "`" + command.lineSequence().first().take(200) + "`"
        val where = platform?.let { " [$it]" } ?: ""
        val outcome = if (r.timedOut) "timed out after ${timeoutMs / 1000} s" else "exit ${r.exitCode}"
        publishSystem(sessionId, buildJsonObject {
            put("level", if (r.timedOut || r.exitCode != 0) "warn" else "info")
            put("text", "Sandbox$where $what: $outcome in ${r.durationMs / 1000.0} s")
            putJsonObject("sandbox") {
                put("kind", kind); put("command", command); put("exitCode", r.exitCode); put("timedOut", r.timedOut)
                put("durationMs", r.durationMs); put("output", r.output); put("outputTruncated", r.outputTruncated)
                put("platform", platform)
            }
        })
    }

    // ------------------------------------------------------------------ cleanup

    /** Removes the run's sandbox (containers, network) and frees its slots. Idempotent; never throws. */
    suspend fun release(runId: UUID) {
        if (!sandboxes.containsKey(runId)) return
        withContext(NonCancellable + Dispatchers.IO) { releaseNow(runId) }
    }

    /**
     * [release], blocking: what the worktree cleanup calls first, so a run's containers are always gone before its
     * worktree — which they bind-mount — is deleted (docs/ARCHITECTURE.md §5 "Lifecycle").
     */
    fun releaseNow(runId: UUID) {
        val rs = sandboxes.remove(runId) ?: return
        rs.released = true
        // The command dies with its container; waits on it learn so now, not when the runtime's exec returns.
        rs.current?.result?.complete(SandboxAnswer(true, REMOVED_WHILE_RUNNING))
        val had = rs.created
        teardown(rs)
        if (had) announce(rs, "removed")
    }

    private fun teardown(rs: RunSandbox) {
        val rt = runtime ?: return
        if (rs.created || rs.slotsHeld.get() > 0) {
            runCatching { rt.removeContainers(names(rs)) }.onFailure { log.warn("Removing the sandbox of run {} failed: {}", rs.runId, it.message) }
            runCatching { rt.removeNetwork(networkName(rs.runId)) }.onFailure { log.warn("Removing the network of run {} failed: {}", rs.runId, it.message) }
        }
        rs.boxes.clear()
        rs.infra = false
        rs.createdAt = null
        rs.ready.clear()
        val held = rs.slotsHeld.getAndSet(0)
        if (held > 0) slots.release(held)
    }

    // ------------------------------------------------------------------ admin (docs/ARCHITECTURE.md §8)

    /**
     * Admin: removes run [runId]'s sandbox now (all its containers and network; cache volumes stay) and frees its slots.
     * The run itself continues: the builder's next `factory_sandbox_exec` creates a fresh sandbox (setup runs again), a
     * command running at that moment ends with an error saying so. 404 when the run has no sandbox right now.
     */
    suspend fun stop(admin: UserRecord, runId: UUID) {
        if (!admin.isAdmin) throw forbidden("Only an admin can stop a sandbox")
        val rs = sandboxes[runId]?.takeIf { !it.released && (it.created || it.slotsHeld.get() > 0) }
            ?: throw notFound("Run $runId has no sandbox right now")
        rs.generation.incrementAndGet()
        withContext(NonCancellable + Dispatchers.IO) { teardown(rs) }
        rs.current?.result?.complete(SandboxAnswer(true, STOPPED_WHILE_RUNNING))
        log.info("Sandbox of run {} stopped by {}", runId, admin.username)
        announce(rs, "stopped")
        rs.sessionId?.let {
            publishSystem(it, systemPayload("warn", "An admin (${admin.username}) stopped this run's sandbox: its containers and network were removed " +
                "(cache volumes are kept). The run continues; the next sandbox command creates a new sandbox and runs the setup script again."))
        }
    }

    /** Admin: the runtime, its limits and the sandboxes that exist right now (oldest first). */
    suspend fun overview(admin: UserRecord): SandboxOverviewDto = withContext(Dispatchers.IO) {
        val list = sandboxes.values.filter { it.created && !it.released }.mapNotNull { rs ->
            val def = (rs.plan as? Plan.Available) ?: return@mapNotNull null
            val run = runs.get(rs.runId) ?: return@mapNotNull null
            val at = rs.createdAt ?: rs.boxes.values.minOfOrNull { it.createdAt } ?: return@mapNotNull null
            val visible = access.canSee(admin, rs.repo)
            val d = def.definition
            val containers = rs.boxes.values.sortedBy { it.createdAt }.map { SandboxContainerDto(it.key, it.image, it.createdAt.iso()) }
            RunningSandboxDto(
                runId = rs.runId.toString(), repo = rs.repo, visible = visible, issueNumber = run.issueNumber,
                issueTitle = if (visible) run.issueTitle else null, runStatus = run.status,
                owner = OwnerDto(run.ownerUsername, run.ownerDisplayName), image = d.image,
                services = d.services.map { it.name }, sha256 = def.sha256, createdAt = at.iso(),
                cpus = d.cpus, memory = SandboxConfig.formatMemory(d.memoryBytes),
                containers = containers, platforms = d.platforms.map { it.name }, slots = rs.slotsHeld.get(),
                memoryTotal = SandboxConfig.formatMemory(d.memoryBytes * (containers.size + (if (rs.infra) d.services.size else 0)).coerceAtLeast(1)),
            )
        }.sortedBy { it.createdAt }
        SandboxOverviewDto(
            enabled = enabled, runtime = if (enabled) sc.mode else "off", inUse = inUse(),
            limits = SandboxServerLimitsDto(sc.maxCpus, SandboxConfig.formatMemory(sc.maxMemoryBytes), sc.maxConcurrent, sc.execTimeoutMs / 60_000,
                sc.registries, sc.maxPlatforms),
            sandboxes = list,
        )
    }

    /**
     * Admin: the projects the admin can see whose `.agern/sandbox.yml` on the default branch is valid but not approved in
     * this exact version (one GitHub read per project; a project that cannot be read is left out).
     */
    suspend fun pending(admin: UserRecord): List<PendingSandboxDto> = withContext(Dispatchers.IO) {
        val client = access.client(admin) ?: return@withContext emptyList()
        val repos = try {
            client.listOrgRepos(cfg.githubOrg).filter { !it.archived }
        } catch (e: GitHubException) {
            throw dk.egernet.factory.ApiException(io.ktor.http.HttpStatusCode.BadGateway, "github_error", e.message ?: "GitHub error")
        }
        access.remember(admin, repos)
        repos.mapNotNull { r ->
            val content = runCatching { client.getFile(cfg.githubOrg, r.name, SANDBOX_FILE, r.defaultBranch) }.getOrNull() ?: return@mapNotNull null
            val p = SandboxDefinitions.parse(content, sc) as? ParsedDefinition.Valid ?: return@mapNotNull null
            val approved = approvals.get(r.name)?.sha256
            if (approved == p.sha256) null else PendingSandboxDto(r.name, p.sha256, p.definition.image, approved)
        }.sortedBy { it.repo }
    }

    /**
     * Removes containers and networks labelled `agern.run` whose run is not active (server start: whatever a crash or
     * restart left behind) — sandboxes, platform containers and services alike. Sandboxes of runs this server still
     * tracks, or that are still active, are kept.
     */
    suspend fun reapOrphans(): List<String> {
        val rt = runtime ?: return emptyList()
        if (!enabled) return emptyList()
        return withContext(Dispatchers.IO) {
            val containers = runCatching { rt.listContainers() }.getOrElse { log.warn("Listing sandboxes failed: {}", it.message); return@withContext emptyList() }
            val networks = runCatching { rt.listNetworks() }.getOrElse { log.warn("Listing sandbox networks failed: {}", it.message); emptyList() }
            val keep = (containers + networks).map { it.runId }.distinct().filter { id ->
                val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return@filter false
                sandboxes.containsKey(uuid) || runs.get(uuid)?.status in RunStatus.ACTIVE
            }.toSet()
            val goneContainers = containers.filter { it.runId !in keep }.map { it.name }
            val goneNetworks = networks.filter { it.runId !in keep }.map { it.name }.distinct()
            if (goneContainers.isNotEmpty()) runCatching { rt.removeContainers(goneContainers) }.onFailure { log.warn("Removing leftover sandboxes failed: {}", it.message) }
            for (n in goneNetworks) runCatching { rt.removeNetwork(n) }.onFailure { log.warn("Removing leftover network {} failed: {}", n, it.message) }
            if (goneContainers.isNotEmpty() || goneNetworks.isNotEmpty()) {
                log.info("Removed {} leftover sandbox container(s) and {} network(s) of runs that are not active", goneContainers.size, goneNetworks.size)
            }
            goneContainers + goneNetworks
        }
    }

    /** Server shutdown: best-effort removal of every sandbox this server created. */
    suspend fun shutdown() {
        for (id in sandboxes.keys.toList()) release(id)
    }

    /** Slots in use right now: command containers that exist (or are being created), over all runs. */
    fun inUse(): Int = sc.maxConcurrent - slots.availablePermits()

    // ------------------------------------------------------------------ approval API (docs/ARCHITECTURE.md §8)

    /** The definition on [repo]'s default branch and its approval, read with [user]'s token (404 when invisible). */
    suspend fun status(user: UserRecord, repo: String): SandboxStatusDto = withContext(Dispatchers.IO) {
        val r = access.requireVisible(user, repo)
        val content = try {
            access.requireClient(user).getFile(cfg.githubOrg, repo, SANDBOX_FILE, r.defaultBranch)
        } catch (e: GitHubException) {
            if (e.status == 404) null else throw dk.egernet.factory.ApiException(io.ktor.http.HttpStatusCode.BadGateway, "github_error", e.message ?: "GitHub error")
        }
        val approval = approvals.get(repo)?.let {
            SandboxApprovalDto(it.sha256, it.content, OwnerDto(it.approvedByUsername, it.approvedByDisplayName), it.approvedAt.iso())
        }
        val who = approvers()
        if (content == null) {
            return@withContext SandboxStatusDto(repo, enabled, SANDBOX_FILE, r.defaultBranch, "none", null, null, emptyList(), null, approval, who)
        }
        when (val p = SandboxDefinitions.parse(content, sc)) {
            is ParsedDefinition.Invalid ->
                SandboxStatusDto(repo, enabled, SANDBOX_FILE, r.defaultBranch, "invalid", p.sha256, content, p.errors, null, approval, who)
            is ParsedDefinition.Valid -> SandboxStatusDto(
                repo, enabled, SANDBOX_FILE, r.defaultBranch, if (approval?.sha256 == p.sha256) "approved" else "pending",
                p.sha256, content, emptyList(), dto(p.definition), approval, who,
            )
        }
    }

    /** Admin only: approves exactly the content that hashes to [sha256], which must be what the default branch has now. */
    suspend fun approve(user: UserRecord, repo: String, sha256: String): SandboxStatusDto {
        val current = status(user, repo)
        if (!user.isAdmin) throw forbidden("Only an admin can approve a sandbox definition")
        if (current.state == "none" || current.content == null) throw notFound("$repo has no $SANDBOX_FILE on ${current.branch}")
        if (current.sha256 != sha256.trim().lowercase()) {
            throw conflict("$SANDBOX_FILE on ${current.branch} has changed (it is now ${current.sha256}); review it again", "sandbox_definition_changed")
        }
        if (current.state == "invalid") throw unprocessable("The definition is invalid: " + current.errors.joinToString("; "), "sandbox_definition_invalid")
        withContext(Dispatchers.IO) { approvals.approve(repo, current.sha256, current.content, user.id) }
        log.info("Sandbox definition {} of {} approved by {}", current.sha256, repo, user.username)
        return status(user, repo).also { emitFeed(FeedItem.SandboxApproval(repo, it.approval)) }
    }

    /** Admin only: removes the repository's approval. Runs that already started keep the decision they made. */
    suspend fun revoke(user: UserRecord, repo: String): SandboxStatusDto {
        withContext(Dispatchers.IO) { access.requireVisible(user, repo) }
        if (!user.isAdmin) throw forbidden("Only an admin can revoke a sandbox approval")
        val removed = withContext(Dispatchers.IO) { approvals.revoke(repo) }
        if (removed) {
            log.info("Sandbox approval of {} revoked by {}", repo, user.username)
            emitFeed(FeedItem.SandboxApproval(repo, null))
        }
        return status(user, repo)
    }

    private fun dto(d: SandboxDefinition) = SandboxDefinitionDto(
        image = d.image, setup = d.setup, env = d.env,
        services = d.services.map { s -> SandboxServiceDto(s.name, s.image, s.env, s.ready?.let { SandboxReadyDto(it.command, it.timeoutSeconds) }) },
        caches = d.caches,
        platforms = d.platforms.map { SandboxPlatformDto(it.name, it.image, it.setup, it.setupInherited) },
        user = d.user?.let { SandboxUserDto(it.name, it.uid) },
        limits = SandboxLimitsDto(
            cpus = d.cpus, memory = SandboxConfig.formatMemory(d.memoryBytes), requestedCpus = d.requestedCpus,
            requestedMemory = d.requestedMemoryBytes?.let { SandboxConfig.formatMemory(it) }, capped = d.capped,
        ),
    )

    companion object {
        // Names and specs, deterministic per run (so cleanup never depends on in-memory state).

        fun containerName(runId: UUID) = "agern-run-$runId"
        fun networkName(runId: UUID) = "agern-run-$runId"
        fun serviceContainerName(runId: UUID, service: String) = "agern-run-$runId-$service"
        /** `--` cannot clash with a service's container: service names start with a letter. */
        fun platformContainerName(runId: UUID, platform: String) = "agern-run-$runId--$platform"
        /** Per project and, for a platform, per platform (another image's tools may not share a cache safely). */
        fun cacheVolumeName(repo: String, path: String, platform: String? = null) =
            "agern-cache-$repo-" + SandboxDefinitions.sha256(if (platform == null) path else "$platform $path").take(12)

        fun labels(runId: UUID, repo: String, role: String) = linkedMapOf(LABEL_RUN to runId.toString(), LABEL_REPO to repo, LABEL_ROLE to role)

        /** The command that keeps the sandbox alive; work happens through `exec`. Needs only a POSIX `sh` in the image. */
        val keepAlive = listOf("-c", "trap 'exit 0' TERM INT; while :; do sleep 3600 & wait \$!; done")

        fun mainSpec(runId: UUID, repo: String, worktree: Path, def: SandboxDefinition) = commandSpec(runId, repo, worktree, def, null)

        /**
         * A container that runs commands: the default image ([platform] null) or a platform's. Same worktree mount, env,
         * limits and network; caches in the platform's own volumes. With `user:` the service user is that uid inside
         * (`keep-id`), container root a subordinate id, and the keep-alive process runs as root (`--user 0:0`).
         */
        fun commandSpec(runId: UUID, repo: String, worktree: Path, def: SandboxDefinition, platform: SandboxPlatform?) = ContainerSpec(
            name = if (platform == null) containerName(runId) else platformContainerName(runId, platform.name),
            image = platform?.image ?: def.image,
            labels = if (platform == null) labels(runId, repo, "sandbox") else labels(runId, repo, "platform").also { it["agern.platform"] = platform.name },
            network = networkName(runId),
            env = def.env,
            mounts = listOf(SandboxMount(worktree.toAbsolutePath().normalize().toString(), WORKDIR, volume = false)) +
                def.caches.map { SandboxMount(cacheVolumeName(repo, it, platform?.name), it, volume = true) },
            workdir = WORKDIR, cpus = def.cpus, memoryBytes = def.memoryBytes, entrypoint = "sh", command = keepAlive,
            keepIdUid = def.user?.uid, user = if (def.user != null) "0:0" else null,
        )

        fun serviceSpec(runId: UUID, repo: String, svc: SandboxService, def: SandboxDefinition) = ContainerSpec(
            name = serviceContainerName(runId, svc.name), image = svc.image,
            labels = labels(runId, repo, "service").also { it["agern.service"] = svc.name },
            network = networkName(runId), networkAlias = svc.name, env = svc.env, cpus = def.cpus, memoryBytes = def.memoryBytes,
        )

        /**
         * `sh -lc <command>`, after the shell raised its `oom_score_adj` to 1000 (always allowed, unprivileged). When a
         * command exceeds the memory limit the kernel then kills the command's processes, never the sandbox's keep-alive
         * process (PID 1) — without this it does, if the command's memory is not freed before the next OOM (seen with
         * real Podman: the whole sandbox exited with 137). The command stays the last argument.
         */
        fun shellCommand(command: String) =
            listOf("sh", "-c", "{ echo 1000 > /proc/self/oom_score_adj; } 2>/dev/null; exec sh -lc \"\$1\"", "sh", command)

        const val WORKDIR = "/workspace"
        /** The key of the top-level image among a run's command containers. */
        const val DEFAULT = SandboxDefinitions.DEFAULT_PLATFORM

        /** `resources/sandbox/prepare-user.sh`: run as root with `sh -c <script> agern-user <uid> <name> <caches…>`. */
        val PREPARE_USER_SCRIPT: String = SandboxManager::class.java.getResource("/sandbox/prepare-user.sh")!!.readText()

        /** After setup (as root): files it left in the user's home or the worktree go to the user; `-xdev` skips cache volumes. */
        const val HAND_OVER_SCRIPT = "uid=\$1; shift; find \"\$@\" -xdev ! -user \"\$uid\" -exec chown -h \"\$uid:\$uid\" {} + 2>/dev/null || true"

        private const val REMOVED_WHILE_RUNNING = "The run's sandbox was removed while the command ran (the run ended)."
        /** Finished commands whose results a run keeps for `factory_sandbox_wait`. */
        const val KEPT_RESULTS = 20
        private const val STOPPED_WHILE_RUNNING = "An admin stopped this run's sandbox while the command ran, so it was killed. " +
            "The next command creates a new sandbox (the setup script runs again)."
        /** How much of a command's output the agent gets back (the end of it). */
        const val OUTPUT_TAIL_CHARS = 20_000
        val TOOLS = setOf("factory_sandbox_info", "factory_sandbox_exec", "factory_sandbox_wait")
    }
}
