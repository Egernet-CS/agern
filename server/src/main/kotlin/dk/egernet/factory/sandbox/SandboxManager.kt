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
import dk.egernet.factory.PreparedImageDto
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
import dk.egernet.factory.images.ImageService
import dk.egernet.factory.images.SafeFiles
import dk.egernet.factory.mcp.SandboxScope
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
 * Besides this build sandbox a run can have a review sandbox for one review round at a time: the same approved definition
 * (default image only) on its own network with its own services, the worktree mounted read-only, created on the first
 * command of the ux or ui-design reviewer and shared by both, removed when the round ends ([releaseReview]).
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
    /** Review sandboxes, by run: at most one per run, for one review round. */
    private val reviews = ConcurrentHashMap<UUID, RunSandbox>()

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
        /** How it was made: `reused`, `prepared`, `setup` or `plain` (see [RunSandboxDto.startedFrom]). */
        @Volatile var startedFrom: String = STARTED_PLAIN
        /** The prepared image's key (reused or stored), else null. */
        @Volatile var preparedKey: String? = null
        /** How long creating it took, in milliseconds. */
        @Volatile var createMs: Long = 0
    }

    /** What [resolvePrepared] decided for a new command container with a setup script. */
    private sealed interface Prep {
        /** Start from the prepared image [image] (key [key]) made at [createdAt]; setup does not run. */
        data class Reuse(val key: String, val image: String, val createdAt: Instant) : Prep
        /** Run setup, then commit to [image]; [lock] is held until that is over. [stale]: the expired image it replaces. */
        data class Prepare(val key: String, val image: String, val lock: Mutex, val stale: LocalImage?) : Prep
        /** Another sandbox has been preparing [key] for longer than the command limit. */
        data class Busy(val key: String) : Prep
    }

    /** One lock per prepared image name: only one sandbox of the server prepares it at a time. */
    private val preparing = ConcurrentHashMap<String, Mutex>()

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

    /**
     * A run's build sandbox, or ([review] true) its review sandbox, whose [parent] is the build sandbox entry it was made
     * from. [runDir]: the agents' working directory (where the review sandbox's output directory lives).
     */
    private class RunSandbox(
        val runId: UUID, val repo: String, val worktree: Path, val plan: Plan, @Volatile var info: RunSandboxDto,
        val runDir: Path? = null, val review: Boolean = false, val parent: RunSandbox? = null,
        /** The commit the definition was read from, and the git home: what a prepared image is made from ([prepareImage]). */
        val definitionRef: String? = null, val gitHome: Path? = null,
    ) {
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
        /** Preparation containers (and networks, same names) of this sandbox that exist now ([prepareImage]). */
        val preparing: MutableSet<String> = ConcurrentHashMap.newKeySet()
        /** When the network and services were created (null while there are none). */
        @Volatile var createdAt: Instant? = null
        /** Command containers that exist now, by platform key. */
        val boxes = ConcurrentHashMap<String, Box>()
        /** Slots of the concurrency cap this run holds: one per command container (or one being created). */
        val slotsHeld = AtomicInteger()
        /** Services whose readiness check passed since the services were (re)created. */
        val ready: MutableSet<String> = ConcurrentHashMap.newKeySet()
        @Volatile var released = false
        /** The session that used the sandbox last (where an admin's stop is reported). */
        @Volatile var sessionId: UUID? = null
        /** Bumped by every admin stop, so a creation or command in flight notices its containers are gone. */
        val generation = AtomicInteger()

        /** Review sandbox: the host directory mounted at [REVIEW_OUTPUT] (null: none, see [outputNote]). */
        @Volatile var outputDir: Path? = null
        /** Review sandbox: why it has no output directory. */
        @Volatile var outputNote: String? = null

        val created: Boolean get() = infra || boxes.isNotEmpty()
        val definition: SandboxDefinition? get() = (plan as? Plan.Available)?.definition
    }

    /** Every container a run can have, existing or not (removal ignores missing ones). */
    private fun names(rs: RunSandbox): List<String> {
        if (rs.review) {
            return listOf(reviewContainerName(rs.runId)) + rs.definition?.services.orEmpty().map { reviewServiceContainerName(rs.runId, it.name) } +
                prepareNames(rs)
        }
        val def = rs.definition ?: return listOf(containerName(rs.runId))
        return listOf(containerName(rs.runId)) + def.platforms.map { platformContainerName(rs.runId, it.name) } +
            def.services.map { serviceContainerName(rs.runId, it.name) } + prepareNames(rs)
    }

    /** The preparation containers that exist now (being prepared in: [prepareImage] removes them when it ends). */
    private fun prepareNames(rs: RunSandbox): List<String> = rs.preparing.toList()

    private fun boxName(rs: RunSandbox, key: String) = when {
        rs.review -> reviewContainerName(rs.runId)
        key == DEFAULT -> containerName(rs.runId)
        else -> platformContainerName(rs.runId, key)
    }

    private fun netName(rs: RunSandbox) = if (rs.review) reviewNetworkName(rs.runId) else networkName(rs.runId)

    private fun svcName(rs: RunSandbox, service: String) =
        if (rs.review) reviewServiceContainerName(rs.runId, service) else serviceContainerName(rs.runId, service)

    private fun announce(rs: RunSandbox, change: String) = emitFeed(FeedItem.Sandboxes(rs.repo, rs.runId.toString(), change))

    // ------------------------------------------------------------------ run start

    /**
     * Decides whether run [runId] gets a sandbox: reads `.agern/sandbox.yml` from [baseRef] (the commit the run's
     * worktree was created from, so a run cannot approve its own change to the file), validates it and checks the
     * approval. Never throws; the decision is stored on the run and returned. [definitionRepo]: the repository whose
     * approval counts (a workspace run's target member); [repo] stays the run's project.
     */
    suspend fun prepare(
        runId: UUID, repo: String, worktree: Path, baseRef: String, home: Path, definitionRepo: String = repo, runDir: Path = worktree,
    ): RunSandboxDto {
        suspend fun record(info: RunSandboxDto, available: Plan.Available?, repo: String, worktree: Path) =
            recordDecision(runId, info, available, repo, worktree, runDir, baseRef, home)
        if (!enabled) {
            return record(RunSandboxDto("disabled", "Sandboxes are switched off on this server (FACTORY_SANDBOX=off).", null, null), null, repo, worktree)
        }
        val content = try {
            withContext(Dispatchers.IO) {
                devDefinitionSource?.invoke(definitionRepo) ?: git.readFile(worktree, baseRef, SANDBOX_FILE, home, SandboxDefinitions.MAX_BYTES)
            }
        } catch (e: Exception) {
            return record(RunSandboxDto("invalid", "Could not read $SANDBOX_FILE from $baseRef: ${e.message}", null, null), null, repo, worktree)
        }
        if (content == null) {
            return record(RunSandboxDto("none", "The repository has no $SANDBOX_FILE on its default branch ($baseRef).", null, null), null, repo, worktree)
        }
        return when (val parsed = SandboxDefinitions.parse(content, sc)) {
            is ParsedDefinition.Invalid -> record(
                RunSandboxDto("invalid", "$SANDBOX_FILE on $baseRef is invalid: " + parsed.errors.joinToString("; "), parsed.sha256, null),
                null, repo, worktree,
            )
            is ParsedDefinition.Valid -> {
                // A workspace run's sandbox is approved per member repository (docs/ARCHITECTURE.md §6 "Workspace runs").
                val approval = withContext(Dispatchers.IO) { approvals.get(definitionRepo) }
                val def = parsed.definition
                if (approval != null && approval.sha256 == parsed.sha256) {
                    val platforms = if (def.platforms.isEmpty()) "" else "; platforms ${def.platforms.joinToString(", ") { "${it.name} (${it.image})" }}"
                    record(
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
                        RunSandboxDto("unapproved", "$SANDBOX_FILE (${parsed.sha256.take(12)}) is not approved: $why. An admin can approve it in the project's sandbox settings.", parsed.sha256, def.image),
                        null, repo, worktree,
                    )
                }
            }
        }
    }

    private suspend fun recordDecision(
        runId: UUID, info: RunSandboxDto, available: Plan.Available?, repo: String, worktree: Path, runDir: Path, definitionRef: String, home: Path,
    ): RunSandboxDto {
        withContext(Dispatchers.IO) { runs.setSandbox(runId, info) }
        if (enabled) sandboxes[runId] = RunSandbox(runId, repo, worktree, available ?: Plan.Unavailable(info.reason), info, runDir,
            definitionRef = definitionRef, gitHome = home)
        return info
    }

    // ------------------------------------------------------------------ tools

    private val execCapMs: Long get() = minOf(sc.execTimeoutMs, cfg.stageTimeoutMs)

    /**
     * `factory_sandbox_info` for an agent of run [runId] whose token has [toolScope]: the build sandbox (builder), the review
     * sandbox (ux and ui-design reviewers; never created here) or the plan (spec-writer, which cannot run anything).
     */
    suspend fun info(runId: UUID, toolScope: SandboxScope = SandboxScope.BUILD): SandboxAnswer {
        val build = sandboxes[runId]
        val plan = build?.plan
        val review = toolScope == SandboxScope.REVIEW
        // What `created`, `command`, readiness and capacity describe: the review sandbox (if it exists now) or the build one.
        val live: RunSandbox? = if (review) reviews[runId] else build
        // Exposed ports are known once the images are pulled, i.e. after the sandbox was created.
        val ports: Map<String, List<String>> = if (plan is Plan.Available && (build.infra || live?.infra == true)) withContext(Dispatchers.IO) {
            plan.definition.services.associate { it.name to runtime!!.exposedPorts(it.image) }
        } else emptyMap()
        val obj = buildJsonObject {
            put("available", plan is Plan.Available && !build.released)
            put("state", build?.info?.state ?: if (enabled) "none" else "disabled")
            put("reason", when {
                !enabled -> "Sandboxes are switched off on this server. Build and test on the host as usual."
                build == null -> "This run has no sandbox."
                build.released -> "The run's sandbox has been removed (the build/review part of the run is over)."
                else -> build.info.reason
            })
            if (plan is Plan.Available) {
                val def = plan.definition
                when (toolScope) {
                    SandboxScope.PLAN -> {
                        put("access", "plan")
                        put("note", "You cannot run commands in this sandbox; this is for planning the spec's Verification section. " +
                            "The builder runs commands in it (worktree read-write, on the platforms listed here). The ux and ui-design " +
                            "reviewers get a separate review sandbox from the same definition: default image only, own services, the " +
                            "worktree mounted read-only, shared by both for one review round.")
                    }
                    SandboxScope.REVIEW -> {
                        put("access", "review")
                        put("readOnly", true)
                        val out = live?.outputDir
                        val noteOut = live?.outputNote
                        if (live == null || out != null) {
                            putJsonObject("outputDir") { put("container", REVIEW_OUTPUT); put("host", REVIEW_OUTPUT_HOST) }
                        } else {
                            put("outputDir", kotlinx.serialization.json.JsonNull)
                            put("outputNote", noteOut ?: "This review sandbox has no output directory; screenshots cannot be taken inside it.")
                        }
                        put("note", "This is the run's review sandbox, shared with the other UI reviewer (ux, ui-design) of this round " +
                            "and removed when the round ends. $WORKDIR is the run's worktree, read-only: writes there fail (copy the tree " +
                            "to /tmp inside the container if the app must write into it). Files for the host, such as screenshots, go to " +
                            "$REVIEW_OUTPUT, which is $REVIEW_OUTPUT_HOST in your working directory. Start a long-running app detached " +
                            "inside a foreground command with every stream redirected, e.g. `nohup <start> > /tmp/app.log 2>&1 < /dev/null &` " +
                            "followed by a readiness probe; never with `background: true`, which blocks every other command of this " +
                            "sandbox. The other UI reviewer may already have started the app: check (e.g. probe its URL) before you start it.")
                    }
                    SandboxScope.BUILD -> {}
                }
                put("image", def.image)
                put("created", live?.boxes?.containsKey(DEFAULT) == true)
                put("workdir", WORKDIR)
                put("shell", "Commands run as `sh -lc <command>` in $WORKDIR, which is your working directory (the same files" +
                    (if (review) ", read-only here" else "") + ").")
                if (def.user != null) {
                    putJsonObject("user") {
                        put("name", live?.boxes?.values?.firstNotNullOfOrNull { it.user?.name } ?: def.user.name)
                        put("uid", def.user.uid)
                        put("note", if (review) "Commands run as this user, not root; the setup script ran as root. $WORKDIR is read-only."
                            else "Commands run as this user, not root; the setup script ran as root. Files you create in $WORKDIR belong to you on the host.")
                    }
                }
                if (!review) {
                    putJsonArray("platforms") {
                        for (p in def.platforms) addJsonObject {
                            put("name", p.name); put("image", p.image); put("setup", p.setup != null)
                            put("created", build.boxes.containsKey(p.name))
                        }
                    }
                    if (def.platforms.isNotEmpty()) {
                        put("platformNote", "Pass `platform` to factory_sandbox_exec to run a command in a platform's image (its own container, " +
                            "created on first use, same files, services and limits; caches are per platform). Without `platform` commands run in the " +
                            "default image. Run the test suite on every platform before you finish and report the result per platform.")
                    }
                }
                putJsonArray("services") {
                    for (s in def.services) addJsonObject {
                        put("name", s.name); put("host", s.name); put("image", s.image)
                        putJsonArray("ports") { ports[s.name].orEmpty().forEach { add(it) } }
                        put("readiness", when {
                            s.ready == null -> "unchecked"
                            live != null && s.name in live.ready -> "ready"
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
                live?.current?.takeIf { it.running }?.let { c ->
                    putJsonObject("command") { put("handle", c.handle); put("running", true); put("elapsedMs", c.elapsedMs) }
                }
                putJsonObject("capacity") { put("inUse", inUse()); put("max", sc.maxConcurrent); put("heldByThisRun", live?.slotsHeld?.get() ?: 0) }
            }
        }
        return SandboxAnswer(false, obj.toString())
    }

    /**
     * The review sandbox entry of run [build]'s run, made on first use from the build entry's plan. Null when the run's
     * sandbox has been released, also when that happens while the entry is made: [releaseNow] marks the build entry
     * released before it takes the review entry out, and this checks the mark after putting the entry in, so either the
     * release removes the new entry or this does.
     */
    private fun reviewSandbox(build: RunSandbox, plan: Plan.Available): RunSandbox? {
        if (build.released) return null
        val rs = reviews.computeIfAbsent(build.runId) {
            RunSandbox(build.runId, build.repo, build.worktree, plan, build.info, build.runDir, review = true, parent = build,
                definitionRef = build.definitionRef, gitHome = build.gitHome)
        }
        if (build.released || rs.parent !== build) {
            if (reviews.remove(build.runId, rs)) dropReview(rs)
            return null
        }
        return rs
    }

    /**
     * `factory_sandbox_exec`: runs [command] with `sh -lc` in the run's sandbox — in [platform]'s container when given —
     * creating it first if needed. With [toolScope] REVIEW that is the run's review sandbox (never the build sandbox), with
     * PLAN nothing runs. The full output (up to [ExecLimits.LOG_CHARS]) goes into [sessionId]'s transcript as a
     * `system` event; the agent gets the tail. The command runs in a job on the manager's scope under a handle: in the
     * foreground the call waits for it, with [background] it returns the handle at once ([wait] gets the result).
     */
    suspend fun exec(
        runId: UUID, sessionId: UUID, command: String, timeoutSeconds: Long?, platform: String? = null, background: Boolean = false,
        toolScope: SandboxScope = SandboxScope.BUILD,
    ): SandboxAnswer {
        if (!enabled) return SandboxAnswer(true, "Sandboxes are switched off on this server. Build and test on the host as usual.")
        if (toolScope == SandboxScope.PLAN) return SandboxAnswer(true, PLAN_ONLY)
        val build = sandboxes[runId] ?: return SandboxAnswer(true, "This run has no sandbox. Build and test on the host as usual.")
        val plan = build.plan
        if (plan !is Plan.Available) return SandboxAnswer(true, "No sandbox for this run: ${build.info.reason} Build and test on the host as usual.")
        val def = plan.definition
        val review = toolScope == SandboxScope.REVIEW
        val key = platform?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT
        if (review && key != DEFAULT) return SandboxAnswer(true, "The review sandbox has only the default image; leave platform out.")
        if (key != DEFAULT && def.platforms.none { it.name == key }) {
            return SandboxAnswer(true, "Unknown platform '$key'. " + if (def.platforms.isEmpty()) "This sandbox has no platforms; leave `platform` out."
                else "This sandbox has: $DEFAULT (${def.image}), " + def.platforms.joinToString(", ") { "${it.name} (${it.image})" } + ".")
        }
        if (command.isBlank()) return SandboxAnswer(true, "command must not be empty")
        if (command.length > 100_000 || '\u0000' in command) return SandboxAnswer(true, "command is too long or contains NUL")
        val cap = execCapMs
        if (timeoutSeconds != null && timeoutSeconds <= 0) return SandboxAnswer(true, "timeoutSeconds must be positive")
        val timeoutMs = minOf(timeoutSeconds?.let { it * 1000 } ?: cap, cap)
        val rs = if (review) reviewSandbox(build, plan) ?: return SandboxAnswer(true, "The run's sandbox has been removed.") else build
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
            if (rs.released) return SandboxAnswer(true, if (review) "The review sandbox has been removed (the review round is over)." else "The run's sandbox has been removed.")
            rs.sessionId = sessionId
            if (!rs.boxes.containsKey(key)) create(rs, plan, key, sessionId)?.let { return SandboxAnswer(true, it) }
            val box = rs.boxes[key] ?: return SandboxAnswer(true, STOPPED_WHILE_RUNNING)
            box.lastUsed = System.nanoTime()
            noteStartup(rs, box)
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
                if (platformLabel(rs, def, key) != null) put("platform", key)
                put("note", "Started in the background. Call factory_sandbox_wait to get the exit code and output; do not poll with sleep.")
            }.toString())
        } finally {
            if (!handedOver) rs.lock.unlock()
        }
    }

    /** The part of a command after the container exists: runs it, handles a timeout, logs it and builds the answer. */
    private suspend fun runCommand(rs: RunSandbox, def: SandboxDefinition, box: Box, cmd: Command, generation: Int): SandboxAnswer {
        val key = cmd.key
        val name = boxName(rs, key)
        val r = try {
            withContext(Dispatchers.IO) { runtime!!.exec(name, shellCommand(cmd.command), WORKDIR, cmd.timeoutMs, box.user) }
        } catch (e: Exception) {
            if (rs.generation.get() != generation) return SandboxAnswer(true, STOPPED_WHILE_RUNNING)
            return SandboxAnswer(true, "The sandbox command could not be run: ${e.message}")
        }
        box.lastUsed = System.nanoTime()
        if (rs.released) return SandboxAnswer(true, removedWhileRunning(rs))
        if (rs.generation.get() != generation) return SandboxAnswer(true, STOPPED_WHILE_RUNNING)
        if (r.timedOut) {
            // Kill whatever the command left running; files (and what setup installed) are kept.
            runCatching { withContext(Dispatchers.IO) { runtime!!.restartContainer(name) } }
                .onFailure { log.warn("Restarting sandbox {} of run {} after a timeout failed: {}", key, rs.runId, it.message) }
        }
        logEvent(cmd.sessionId, "exec", cmd.command, r, cmd.timeoutMs, platformLabel(rs, def, key), rs.review)
        val tail = r.output.takeLast(OUTPUT_TAIL_CHARS)
        return SandboxAnswer(false, buildJsonObject {
            put("handle", cmd.handle)
            put("exitCode", r.exitCode)
            put("output", tail)
            put("truncated", r.outputTruncated || tail.length < r.output.length)
            put("durationMs", r.durationMs)
            put("timedOut", r.timedOut)
            if (platformLabel(rs, def, key) != null) put("platform", key)
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
     * Handles are looked up only in the sandbox of [toolScope] (build or review), so a reviewer never reaches a builder's
     * command nor the reverse. Never takes the sandbox's command lock, so any number of waits can run at once.
     */
    suspend fun wait(runId: UUID, handle: String?, waitSeconds: Long?, toolScope: SandboxScope = SandboxScope.BUILD): SandboxAnswer {
        if (!enabled) return SandboxAnswer(true, "Sandboxes are switched off on this server. Build and test on the host as usual.")
        if (toolScope == SandboxScope.PLAN) return SandboxAnswer(true, PLAN_ONLY)
        val build = sandboxes[runId] ?: return SandboxAnswer(true, "This run has no sandbox. Build and test on the host as usual.")
        val plan = build.plan
        if (plan !is Plan.Available) return SandboxAnswer(true, "No sandbox for this run: ${build.info.reason} Build and test on the host as usual.")
        if (waitSeconds != null && waitSeconds < 0) return SandboxAnswer(true, "waitSeconds must be a whole number of seconds, 0 or more")
        val rs = if (toolScope == SandboxScope.REVIEW) {
            reviews[runId] ?: return SandboxAnswer(true, if (handle.isNullOrBlank()) "No command has been started in this round's review sandbox yet."
                else "Unknown handle '${handle.trim().take(64)}'. Handles come from factory_sandbox_exec in this round's review sandbox.")
        } else build
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

    /** What the transcript calls the container a command ran in: null when the definition has no platforms (or in review). */
    private fun platformLabel(rs: RunSandbox, def: SandboxDefinition, key: String): String? = if (rs.review || def.platforms.isEmpty()) null else key

    /** Records on the run where the builder ran commands (only when there are platforms), so the run page can say. */
    private suspend fun noteUsed(rs: RunSandbox, key: String) {
        if (rs.review) return
        val def = rs.definition ?: return
        if (def.platforms.isEmpty() || key in rs.info.platformsUsed) return
        rs.info = rs.info.copy(platformsUsed = rs.info.platformsUsed + key)
        runCatching { withContext(Dispatchers.IO) { runs.setSandbox(rs.runId, rs.info) } }
            .onFailure { log.warn("Recording the platforms used by run {} failed: {}", rs.runId, it.message) }
        emitRun(rs.runId)
    }

    /**
     * Records on the run (once, at the build sandbox's first command) how long the builder waited for it: from the first
     * builder stage's start to now, how long creating [box] took and whether it came from a prepared image.
     */
    private suspend fun noteStartup(rs: RunSandbox, box: Box) {
        if (rs.review || rs.info.startedFrom != null) return
        val startedAt = runCatching {
            withContext(Dispatchers.IO) { runs.stages(rs.runId).firstOrNull { it.role == "builder" && it.round == 0 }?.startedAt }
        }.getOrNull()
        val firstCommandMs = startedAt?.let { java.time.Duration.between(it, Instant.now()).toMillis().coerceAtLeast(0) }
        rs.info = rs.info.copy(firstCommandMs = firstCommandMs, createMs = box.createMs, startedFrom = box.startedFrom, preparedKey = box.preparedKey)
        log.info("Sandbox startup of run {}: first command after {} ms (creating the sandbox took {} ms, {}{})", rs.runId,
            firstCommandMs ?: "?", box.createMs, box.startedFrom, box.preparedKey?.let { ", prepared image $it" } ?: "")
        runCatching { withContext(Dispatchers.IO) { runs.setSandbox(rs.runId, rs.info) } }
            .onFailure { log.warn("Recording the sandbox startup of run {} failed: {}", rs.runId, it.message) }
        emitRun(rs.runId)
    }

    /** Records on the run (once) that its UI reviewers had a review sandbox, so the run page can say. */
    private suspend fun noteReviewUsed(build: RunSandbox) {
        if (build.info.reviewUsed) return
        build.info = build.info.copy(reviewUsed = true)
        runCatching { withContext(Dispatchers.IO) { runs.setSandbox(build.runId, build.info) } }
            .onFailure { log.warn("Recording the review sandbox of run {} failed: {}", build.runId, it.message) }
        emitRun(build.runId)
    }

    /**
     * Creates the review sandbox's output directory `<run dir>/.agern-run/review/` (git-ignored, never through a symlink).
     * When that is not possible the sandbox is made without it and [RunSandbox.outputNote] says why. Blocking.
     */
    private fun prepareOutputDir(rs: RunSandbox) {
        val root = rs.runDir
        if (root == null) {
            rs.outputDir = null
            rs.outputNote = "This review sandbox has no output directory (the run's working directory is unknown); screenshots cannot leave it."
            return
        }
        try {
            ImageService.ensureRunDir(root)
            rs.outputDir = SafeFiles.plainDirectories(root, ImageService.RUN_DIR, REVIEW_OUTPUT_DIR)
            rs.outputNote = null
        } catch (e: Exception) {
            log.warn("Creating the review output directory of run {} failed: {}", rs.runId, e.message)
            rs.outputDir = null
            rs.outputNote = "This review sandbox has no output directory: $REVIEW_OUTPUT_HOST could not be created safely in your working " +
                "directory (${e.message}). Screenshots cannot leave the sandbox; take them on the host instead."
        }
    }

    /**
     * Takes a slot of the concurrency cap for a new command container of [rs]. When none is free, the run's own other
     * command containers (idle: one command at a time per run) are removed, least recently used first, until one is.
     */
    private fun acquireSlot(rs: RunSandbox, key: String, sessionId: UUID): Boolean {
        if (rs.review) return acquireReviewSlot(rs, sessionId)
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

    /**
     * Takes a slot for the review sandbox [rs]. When none is free, the same run's idle build command containers are
     * removed, least recently used first — only while no build command runs or is being started (the build sandbox's lock
     * is free). Never waits.
     */
    private fun acquireReviewSlot(rs: RunSandbox, sessionId: UUID): Boolean {
        while (true) {
            if (slots.tryAcquire()) {
                rs.slotsHeld.incrementAndGet()
                return true
            }
            val build = rs.parent ?: return false
            if (!build.lock.tryLock()) return false
            try {
                val victim = build.boxes.values.minByOrNull { it.lastUsed } ?: return false
                removeBox(build, victim)
                publishSystem(sessionId, systemPayload("info", "Removed this run's idle `${victim.key}` container to make room for the " +
                    "review sandbox (the server's sandbox slots are all in use; FACTORY_SANDBOX_MAX_CONCURRENT=${sc.maxConcurrent}). " +
                    "It is created again when used."))
            } finally {
                build.lock.unlock()
            }
        }
    }

    /** Removes one command container and frees its slot; the network and services stay. */
    private fun removeBox(rs: RunSandbox, box: Box) {
        if (rs.boxes.remove(box.key, box)) {
            runCatching { runtime!!.removeContainers(listOf(boxName(rs, box.key))) }
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
     * services (waiting for their readiness checks) — prepares `user:`, runs the setup script. With a setup script and
     * prepared images on (FACTORY_SANDBOX_PREPARED_MAX_AGE_DAYS > 0) the container starts from the prepared image of its
     * key instead, without setup, or — when there is none yet — stores one after setup ([resolvePrepared]). Returns an
     * error message, or null. For a review sandbox the same steps make its own network, services and the read-only
     * command container.
     */
    private suspend fun create(rs: RunSandbox, plan: Plan.Available, key: String, sessionId: UUID): String? {
        val rt = runtime!!
        val def = plan.definition
        val platform = def.platforms.firstOrNull { it.name == key }
        val image = platform?.image ?: def.image
        val setup = if (platform != null) platform.setup else def.setup
        if (!withContext(Dispatchers.IO) { acquireSlot(rs, key, sessionId) }) {
            if (rs.review) {
                return "The server's sandboxes are all in use (${sc.maxConcurrent} of ${sc.maxConcurrent} containers). No review sandbox " +
                    "was created and nothing was run. $REVIEW_ON_HOST"
            }
            return "The server's sandboxes are all in use (${sc.maxConcurrent} of ${sc.maxConcurrent} containers, by other runs). " +
                "Nothing was run. Try again in a few minutes; meanwhile continue with work that needs no build."
        }
        val generation = rs.generation.get()
        val started = System.currentTimeMillis()
        val what = when {
            rs.review -> "the review sandbox"
            key == DEFAULT -> "the sandbox"
            else -> "platform `$key`"
        }
        val label = if (rs.review) "Review sandbox" else if (key == DEFAULT) "Sandbox" else "Platform `$key`"
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
        var prep: Prep? = null
        /** The image name's preparation lock, while this attempt holds it. */
        var held: Mutex? = null
        /** The image was prepared by this attempt (this container starts from it). */
        var preparedNow = false
        try {
            // Before the network and services: a sandbox waiting for another one's preparation holds nothing but its slot.
            if (setup != null && sc.preparedMaxAgeDays > 0) {
                val p = resolvePrepared(rs, plan, key, image, sessionId)
                prep = p
                when (p) {
                    is Prep.Busy -> {
                        undo()
                        val minutes = (execCapMs + 59_999) / 60_000
                        return "Another run is still preparing this project's sandbox image (more than $minutes minute${if (minutes == 1L) "" else "s"}). " +
                            "Nothing was run. " + if (rs.review) REVIEW_ON_HOST else "Try again in a few minutes; meanwhile continue with work that needs no build."
                    }
                    is Prep.Prepare -> {
                        held = p.lock
                        publishSystem(sessionId, systemPayload("info", "Preparing sandbox image (setup) …" + (p.stale?.let {
                            " The prepared image ${p.key} from ${it.createdAt.iso()} is older than ${sc.preparedMaxAgeDays} days."
                        } ?: "")))
                        val result = prepareImage(rs, plan, key, platform, image, setup, p, generation, sessionId)
                        if (rs.released) {
                            teardown(rs)
                            return if (rs.review) "The review round ended while its review sandbox was being created." else "The run ended while its sandbox was being created."
                        }
                        if (rs.generation.get() != generation) {
                            undo()
                            return "An admin stopped this run's ${if (rs.review) "review " else ""}sandbox while it was being created. Nothing was run; " +
                                "the next command creates it again."
                        }
                        when (result) {
                            is Prepared.Stored -> {
                                prep = Prep.Reuse(p.key, p.image, result.createdAt)
                                preparedNow = true
                            }
                            // Not stored (the commit failed): this container runs setup itself, as without prepared images.
                            Prepared.NotStored -> prep = null
                            is Prepared.Failed -> {
                                undo()
                                return result.message
                            }
                        }
                    }
                    is Prep.Reuse -> {}
                }
            }
            val reuse = prep as? Prep.Reuse
            withContext(Dispatchers.IO) {
                if (!rs.infra) {
                    rt.createNetwork(netName(rs), labels(rs.runId, rs.repo, if (rs.review) "review-network" else "network"))
                    rs.infra = true
                    rs.createdAt = Instant.now()
                    rs.ready.clear()
                    for (s in def.services) {
                        rt.startContainer(if (rs.review) reviewServiceSpec(rs.runId, rs.repo, s, def) else serviceSpec(rs.runId, rs.repo, s, def))
                    }
                }
                for (c in def.caches) {
                    val labels = linkedMapOf(LABEL_REPO to rs.repo, "agern.cache" to c)
                    if (platform != null) labels["agern.platform"] = platform.name
                    rt.ensureVolume(cacheVolumeName(rs.repo, c, platform?.name), labels)
                }
                val spec = if (rs.review) {
                    prepareOutputDir(rs)
                    reviewSpec(rs.runId, rs.repo, rs.worktree, def, rs.outputDir)
                } else {
                    commandSpec(rs.runId, rs.repo, rs.worktree, def, platform)
                }
                // From the prepared image: only the image changes; it exists only locally, so never pull it.
                rt.startContainer(if (reuse != null) spec.copy(image = reuse.image, pull = ContainerSpec.PULL_NEVER) else spec)
                box = Box(key, image).also { rs.boxes[key] = it }
            }
            announce(rs, "created")
            if (rs.released) {
                teardown(rs)
                return if (rs.review) "The review round ended while its review sandbox was being created." else "The run ended while its sandbox was being created."
            }
            if (rs.generation.get() != generation) {
                teardown(rs)
                return "An admin stopped this run's ${if (rs.review) "review " else ""}sandbox while it was being created. Nothing was run; " +
                    "the next command creates it again."
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
            val name = boxName(rs, key)
            val u = def.user
            if (u != null) {
                // Also from a prepared image: idempotent, it tells who commands run as and gives fresh cache volumes to the user.
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
            if (reuse != null) {
                box!!.startedFrom = if (preparedNow) STARTED_PREPARED else STARTED_REUSED
                box!!.preparedKey = reuse.key
                if (!preparedNow) publishSystem(sessionId, systemPayload("info", "$label from prepared image ${reuse.key} (prepared ${reuse.createdAt.iso()})"))
            } else if (setup != null) {
                val r = withContext(Dispatchers.IO) { rt.exec(name, shellCommand(setup), WORKDIR, execCapMs) }
                logEvent(sessionId, "setup", setup, r, execCapMs, platformLabel(rs, def, key), rs.review)
                if (r.timedOut || r.exitCode != 0) {
                    undo()
                    return "The ${if (rs.review) "review sandbox's" else if (key == DEFAULT) "sandbox's" else "platform `$key`'s"} setup script failed (" +
                        (if (r.timedOut) "timed out" else "exit ${r.exitCode}") +
                        "), so the container was removed. Output (tail):\n" + r.output.takeLast(4000) +
                        "\nThe definition comes from the default branch and needs an admin's approval to change; report the problem in your summary."
                }
                // Setup runs as root: hand what it left in the user's home and the worktree to the user (not into cache volumes).
                // The review sandbox's worktree is read-only: only the home.
                box!!.user?.let { eu ->
                    val paths = if (rs.review) listOf(eu.home) else listOf(eu.home, WORKDIR)
                    withContext(Dispatchers.IO) {
                        runCatching { rt.exec(name, listOf("sh", "-c", HAND_OVER_SCRIPT, "agern-hand-over", eu.uid.toString()) + paths, WORKDIR, 600_000) }
                    }
                }
                box!!.startedFrom = STARTED_SETUP
            }
            val who = box!!.user?.let { ", commands as ${it.name} (uid ${it.uid})" } ?: ""
            val readOnly = if (rs.review) ", $WORKDIR read-only" + (if (rs.outputDir != null) ", output in $REVIEW_OUTPUT_HOST" else ", no output directory") else ""
            val tookMs = System.currentTimeMillis() - started
            box!!.createMs = tookMs
            publishSystem(sessionId, systemPayload("info", "$label ready in " +
                "${tookMs / 1000.0} s: $image, ${PodmanRuntime.formatCpus(def.cpus)} CPUs, " +
                "${SandboxConfig.formatMemory(def.memoryBytes)} memory$who$readOnly."))
            if (rs.review) rs.parent?.let { noteReviewUsed(it) }
            return null
        } catch (e: Exception) {
            log.warn("Creating sandbox container {} of run {} failed: {}", key, rs.runId, e.message)
            undo()
            publishSystem(sessionId, systemPayload("warn", "Could not create $what: ${e.message}"))
            return "Could not create $what: ${e.message}"
        } finally {
            held?.unlock()
        }
    }

    /**
     * Decides how a command container with a setup script starts (docs/ARCHITECTURE.md §5 "Prepared images"): computes
     * the key from the local id of [image] (pulled first when the store lacks it), then reuses a prepared image no older
     * than the max age, or takes the image name's lock to prepare it. When another sandbox holds that lock it waits for
     * it (at most the command limit) and checks again: [Prep.Busy] after the wait.
     */
    private suspend fun resolvePrepared(rs: RunSandbox, plan: Plan.Available, key: String, image: String, sessionId: UUID): Prep {
        val rt = runtime!!
        val baseId = withContext(Dispatchers.IO) {
            val found = rt.imageInfo(image) ?: run {
                rt.pullImage(image)
                rt.imageInfo(image) ?: throw SandboxRuntimeException("image $image is not in the local store after pulling it")
            }
            found.id
        }
        val pkey = preparedKey(rs.repo, key, plan.sha256, baseId)
        val name = preparedImageName(rs.repo, pkey)
        fun usable(img: LocalImage?) = img != null && !preparedExpired(img.createdAt)
        withContext(Dispatchers.IO) { rt.imageInfo(name) }?.let { if (usable(it)) return Prep.Reuse(pkey, name, it.createdAt) }
        val lock = preparing.computeIfAbsent(name) { Mutex() }
        if (!lock.tryLock()) {
            publishSystem(sessionId, systemPayload("info", "Waiting for another sandbox to finish preparing image $pkey …"))
            withTimeoutOrNull(execCapMs) { lock.lock() } ?: return Prep.Busy(pkey)
        }
        val current = try {
            withContext(Dispatchers.IO) { rt.imageInfo(name) }
        } catch (e: Exception) {
            lock.unlock()
            throw e
        }
        if (usable(current)) {
            lock.unlock()
            return Prep.Reuse(pkey, name, current!!.createdAt)
        }
        return Prep.Prepare(pkey, name, lock, current)
    }

    /** What [prepareImage] achieved. */
    private sealed interface Prepared {
        data class Stored(val createdAt: Instant) : Prepared
        /** Setup passed but the commit failed: nothing stored. */
        data object NotStored : Prepared
        /** The error for the agent (setup, `user:` or the preparation container failed). */
        data class Failed(val message: String) : Prepared
    }

    /**
     * Prepares the image of [prep] in a **dedicated, throwaway container** (docs/ARCHITECTURE.md §5 "Prepared images"),
     * never in the run's own: its `/workspace` is a clean, read-only export of the commit the approved definition was read
     * from (only committed content of the base branch, nothing an agent wrote into a worktree), it has no cache volumes,
     * none of the run's services and a network of its own. So nothing an agent controls can steer the root setup whose
     * result later runs start from. Runs `prepare-user.sh` and setup there (the setup log goes to the transcript as
     * before), hands the home over, commits the container and removes it, its network and the export, whatever happens.
     */
    private suspend fun prepareImage(
        rs: RunSandbox, plan: Plan.Available, key: String, platform: SandboxPlatform?, image: String, setup: String, prep: Prep.Prepare,
        generation: Int, sessionId: UUID,
    ): Prepared {
        val rt = runtime!!
        val def = plan.definition
        val name = if (rs.review) reviewPrepareName(rs.runId) else prepareName(rs.runId, key)
        val ref = rs.definitionRef
        val home = rs.gitHome
        if (ref == null || home == null) return Prepared.Failed("Could not create the sandbox: the commit its definition came from is unknown.")
        val export = cfg.dataDir.resolve("sandbox-prepare").resolve(UUID.randomUUID().toString())
        fun gone() = rs.released || rs.generation.get() != generation
        try {
            val sha = try {
                withContext(Dispatchers.IO) {
                    java.nio.file.Files.createDirectories(export.parent)
                    git.exportTree(rs.worktree, ref, export, home)
                }
            } catch (e: Exception) {
                log.warn("Exporting {} of run {} to prepare a sandbox image failed: {}", ref, rs.runId, e.message)
                return Prepared.Failed("Could not prepare the sandbox image: exporting $ref failed (${e.message}).")
            }
            rs.preparing += name
            withContext(Dispatchers.IO) {
                rt.createNetwork(name, labels(rs.runId, rs.repo, "prepare-network"))
                rt.startContainer(prepareSpec(rs.runId, rs.repo, name, export, def, platform, image))
            }
            if (gone()) return Prepared.NotStored
            val u = def.user
            var home2: String? = null
            if (u != null) {
                val r = withContext(Dispatchers.IO) {
                    rt.exec(name, listOf("sh", "-c", PREPARE_USER_SCRIPT, "agern-user", u.uid.toString(), u.name.orEmpty()) + def.caches, WORKDIR, 120_000)
                }
                val line = r.output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("AGERN_USER ") }?.split(' ')
                if (r.timedOut || r.exitCode != 0 || line == null || line.size < 3) {
                    return Prepared.Failed("Could not prepare the sandbox's user '${u.display}' (" + (if (r.timedOut) "timed out" else "exit ${r.exitCode}") + "): " +
                        r.output.trim().takeLast(2000) + "\nThe definition comes from the default branch and needs an admin's approval to change; " +
                        "report the problem in your summary.")
                }
                home2 = line.drop(2).joinToString(" ")
            }
            val r = withContext(Dispatchers.IO) { rt.exec(name, shellCommand(setup), WORKDIR, execCapMs) }
            if (gone()) return Prepared.NotStored
            logEvent(sessionId, "setup", setup, r, execCapMs, platformLabel(rs, def, key), rs.review)
            if (r.timedOut || r.exitCode != 0) {
                return Prepared.Failed("The ${if (rs.review) "review sandbox's" else if (key == DEFAULT) "sandbox's" else "platform `$key`'s"} setup script failed (" +
                    (if (r.timedOut) "timed out" else "exit ${r.exitCode}") +
                    "), so the container was removed. Output (tail):\n" + r.output.takeLast(4000) +
                    "\nSetup runs in a clean, read-only copy of the definition's commit ($WORKDIR is read-only there, and it has no caches " +
                    "and no services). The definition comes from the default branch and needs an admin's approval to change; report the problem in your summary.")
            }
            // Setup runs as root: hand what it left in the user's home to the user (the export is read-only).
            if (u != null && home2 != null) {
                withContext(Dispatchers.IO) {
                    runCatching { rt.exec(name, listOf("sh", "-c", HAND_OVER_SCRIPT, "agern-hand-over", u.uid.toString(), home2), WORKDIR, 600_000) }
                }
            }
            if (gone()) return Prepared.NotStored
            val t0 = System.currentTimeMillis()
            try {
                withContext(Dispatchers.IO) { rt.commitContainer(name, prep.image, preparedLabels(prep.key, rs.repo, key, plan.sha256, image)) }
            } catch (e: Exception) {
                if (gone()) return Prepared.NotStored
                log.warn("Storing prepared image {} for run {} failed: {}", prep.image, rs.runId, e.message)
                publishSystem(sessionId, systemPayload("warn", "Could not store the prepared image ${prep.key}: ${e.message}. This sandbox works; " +
                    "the next one runs the setup script again."))
                return Prepared.NotStored
            }
            val createdAt = runCatching { withContext(Dispatchers.IO) { rt.imageInfo(prep.image)?.createdAt } }.getOrNull() ?: Instant.now()
            log.info("Prepared image {} stored for {} ({}) from {} {} in {} ms", prep.image, rs.repo, key, ref, sha, System.currentTimeMillis() - t0)
            publishSystem(sessionId, systemPayload("info", "Prepared image ${prep.key} stored in ${(System.currentTimeMillis() - t0) / 1000.0} s; " +
                "later sandboxes of this project start from it."))
            announce(rs, "prepared")
            // The commit moved the name to the new image; the expired one is now unused (best-effort, never forced).
            prep.stale?.let { old ->
                withContext(Dispatchers.IO) {
                    runCatching { if (rt.imageInfo(prep.image)?.id != old.id) rt.removeImage(old.id) }
                        .onFailure { log.debug("Removing the replaced prepared image {} failed: {}", old.id, it.message) }
                }
            }
            return Prepared.Stored(createdAt)
        } catch (e: Exception) {
            if (gone()) return Prepared.NotStored
            log.warn("Preparing sandbox image {} for run {} failed: {}", prep.image, rs.runId, e.message)
            return Prepared.Failed("Could not create ${if (rs.review) "the review sandbox" else if (key == DEFAULT) "the sandbox" else "platform `$key`"}: ${e.message}")
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { rt.removeContainers(listOf(name)) }.onFailure { log.warn("Removing preparation container {} failed: {}", name, it.message) }
                runCatching { rt.removeNetwork(name) }.onFailure { log.warn("Removing preparation network {} failed: {}", name, it.message) }
                runCatching { export.toFile().deleteRecursively() }
            }
            rs.preparing -= name
        }
    }

    private fun preparedExpired(createdAt: Instant): Boolean =
        sc.preparedMaxAgeDays <= 0 || createdAt.plus(java.time.Duration.ofDays(sc.preparedMaxAgeDays.toLong())).isBefore(Instant.now())

    /** Runs a service's readiness check until it passes; an error message (with the service's last log lines) or null. */
    private fun waitReady(rs: RunSandbox, s: SandboxService, ready: SandboxReady, generation: Int): String? {
        val rt = runtime!!
        val name = svcName(rs, s.name)
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
    private fun logEvent(sessionId: UUID, kind: String, command: String, r: ExecResult, timeoutMs: Long, platform: String?, review: Boolean = false) {
        val what = if (kind == "setup") "setup script" else "`" + command.lineSequence().first().take(200) + "`"
        val where = platform?.let { " [$it]" } ?: ""
        val outcome = if (r.timedOut) "timed out after ${timeoutMs / 1000} s" else "exit ${r.exitCode}"
        publishSystem(sessionId, buildJsonObject {
            put("level", if (r.timedOut || r.exitCode != 0) "warn" else "info")
            put("text", "${if (review) "Review sandbox" else "Sandbox"}$where $what: $outcome in ${r.durationMs / 1000.0} s")
            putJsonObject("sandbox") {
                put("kind", kind); put("command", command); put("exitCode", r.exitCode); put("timedOut", r.timedOut)
                put("durationMs", r.durationMs); put("output", r.output); put("outputTruncated", r.outputTruncated)
                put("platform", platform)
                if (review) put("review", true)
            }
        })
    }

    // ------------------------------------------------------------------ cleanup

    /** Removes the run's sandboxes (build and review: containers, networks) and frees their slots. Idempotent; never throws. */
    suspend fun release(runId: UUID) {
        if (!sandboxes.containsKey(runId) && !reviews.containsKey(runId)) return
        withContext(NonCancellable + Dispatchers.IO) { releaseNow(runId) }
    }

    /**
     * [release], blocking: what the worktree cleanup calls first, so a run's containers are always gone before its
     * worktree — which they bind-mount — is deleted (docs/ARCHITECTURE.md §5 "Lifecycle"). Afterwards no review sandbox
     * can be created for the run ([reviewSandbox]).
     */
    fun releaseNow(runId: UUID) {
        val rs = sandboxes.remove(runId)
        // Marked before the review entry is taken out: a review sandbox being made right now sees the mark and goes.
        rs?.released = true
        reviews.remove(runId)?.let { dropReview(it) }
        if (rs == null) return
        // The command dies with its container; waits on it learn so now, not when the runtime's exec returns.
        rs.current?.result?.complete(SandboxAnswer(true, REMOVED_WHILE_RUNNING))
        val had = rs.created
        teardown(rs)
        if (had) announce(rs, "removed")
    }

    /**
     * Removes run [runId]'s review sandbox, if any (containers, network; never cache volumes), and its output directory.
     * Called when the review round ends, however it ends. The next review round creates a fresh one. Never throws.
     */
    suspend fun releaseReview(runId: UUID) {
        val rs = reviews.remove(runId) ?: return
        withContext(NonCancellable + Dispatchers.IO) { dropReview(rs) }
    }

    /** Tears down a review sandbox that is no longer in [reviews]. Blocking. */
    private fun dropReview(rs: RunSandbox) {
        rs.released = true
        rs.current?.result?.complete(SandboxAnswer(true, removedWhileRunning(rs)))
        val had = rs.created
        teardown(rs)
        if (had) announce(rs, "removed")
        val out = rs.outputDir ?: return
        rs.outputDir = null
        // Only the directory this sandbox created, and only while `.agern-run` is still a real directory (no symlink).
        runCatching {
            val runDir = out.parent
            if (java.nio.file.Files.isDirectory(runDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) SafeFiles.deleteTree(out)
        }.onFailure { log.warn("Deleting the review output directory of run {} failed: {}", rs.runId, it.message) }
    }

    private fun teardown(rs: RunSandbox) {
        val rt = runtime ?: return
        if (rs.created || rs.slotsHeld.get() > 0) {
            runCatching { rt.removeContainers(names(rs)) }.onFailure { log.warn("Removing the sandbox of run {} failed: {}", rs.runId, it.message) }
            runCatching { rt.removeNetwork(netName(rs)) }.onFailure { log.warn("Removing the network of run {} failed: {}", rs.runId, it.message) }
            // A preparation's network goes when its preparation ends (prepareImage removes it whatever happens).
        }
        rs.boxes.clear()
        rs.infra = false
        rs.createdAt = null
        rs.ready.clear()
        val held = rs.slotsHeld.getAndSet(0)
        if (held > 0) slots.release(held)
    }

    private fun removedWhileRunning(rs: RunSandbox) = if (rs.review) REVIEW_REMOVED_WHILE_RUNNING else REMOVED_WHILE_RUNNING

    // ------------------------------------------------------------------ admin (docs/ARCHITECTURE.md §8)

    /**
     * Admin: removes run [runId]'s sandboxes now — the build sandbox and the review sandbox, whichever exist (all their
     * containers and networks; cache volumes stay) — and frees their slots. The run itself continues: the next
     * `factory_sandbox_exec` of the builder or a reviewer creates a fresh one, a command running at
     * that moment ends with an error saying so. 404 when the run has neither right now.
     */
    suspend fun stop(admin: UserRecord, runId: UUID) {
        if (!admin.isAdmin) throw forbidden("Only an admin can stop a sandbox")
        fun live(rs: RunSandbox?) = rs?.takeIf { !it.released && (it.created || it.slotsHeld.get() > 0) }
        val targets = listOfNotNull(live(sandboxes[runId]), live(reviews[runId]))
        if (targets.isEmpty()) throw notFound("Run $runId has no sandbox right now")
        for (rs in targets) {
            rs.generation.incrementAndGet()
            withContext(NonCancellable + Dispatchers.IO) { teardown(rs) }
            rs.current?.result?.complete(SandboxAnswer(true, STOPPED_WHILE_RUNNING))
            log.info("{} of run {} stopped by {}", if (rs.review) "Review sandbox" else "Sandbox", runId, admin.username)
            announce(rs, "stopped")
            rs.sessionId?.let {
                publishSystem(it, systemPayload("warn", if (rs.review) {
                    "An admin (${admin.username}) stopped this run's review sandbox: its containers and network were removed (cache " +
                        "volumes are kept). The review continues; the next review sandbox command creates a new review sandbox."
                } else {
                    "An admin (${admin.username}) stopped this run's sandbox: its containers and network were removed " +
                        "(cache volumes are kept). The run continues; the next sandbox command creates a new sandbox."
                }))
            }
        }
    }

    /** Admin: the runtime, its limits and the sandboxes that exist right now (oldest first), build and review together per run. */
    suspend fun overview(admin: UserRecord): SandboxOverviewDto = withContext(Dispatchers.IO) {
        val list = (sandboxes.keys + reviews.keys).distinct().mapNotNull { runId ->
            val b = sandboxes[runId]?.takeIf { it.created && !it.released }
            val r = reviews[runId]?.takeIf { it.created && !it.released }
            val any = b ?: r ?: return@mapNotNull null
            val def = (any.plan as? Plan.Available) ?: return@mapNotNull null
            val run = runs.get(runId) ?: return@mapNotNull null
            val at = listOfNotNull(b, r).flatMap { rs -> listOfNotNull(rs.createdAt) + rs.boxes.values.map { it.createdAt } }.minOrNull()
                ?: return@mapNotNull null
            val visible = access.canSee(admin, run.repo, run.contextRepos)
            val d = def.definition
            val containers = b?.boxes?.values.orEmpty().sortedBy { it.createdAt }.map { SandboxContainerDto(it.key, it.image, it.createdAt.iso()) } +
                r?.boxes?.values.orEmpty().map { SandboxContainerDto(it.key, it.image, it.createdAt.iso(), kind = "review") }
            val services = listOfNotNull(b, r).count { it.infra } * d.services.size
            RunningSandboxDto(
                runId = runId.toString(), repo = any.repo, visible = visible, issueNumber = run.issueNumber,
                issueTitle = if (visible) run.issueTitle else null, runStatus = run.status,
                owner = OwnerDto(run.ownerUsername, run.ownerDisplayName), image = d.image,
                services = d.services.map { it.name }, sha256 = def.sha256, createdAt = at.iso(),
                cpus = d.cpus, memory = SandboxConfig.formatMemory(d.memoryBytes),
                containers = containers, platforms = d.platforms.map { it.name },
                slots = (b?.slotsHeld?.get() ?: 0) + (r?.slotsHeld?.get() ?: 0),
                memoryTotal = SandboxConfig.formatMemory(d.memoryBytes * (containers.size + services).coerceAtLeast(1)),
            )
        }.sortedBy { it.createdAt }
        val prepared = preparedImages()
        SandboxOverviewDto(
            enabled = enabled, runtime = if (enabled) sc.mode else "off", inUse = inUse(),
            limits = SandboxServerLimitsDto(sc.maxCpus, SandboxConfig.formatMemory(sc.maxMemoryBytes), sc.maxConcurrent, sc.execTimeoutMs / 60_000,
                sc.registries, sc.maxPlatforms, sc.preparedMaxAgeDays),
            sandboxes = list,
            prepared = prepared,
            preparedBytes = prepared.sumOf { it.sizeBytes },
        )
    }

    /** The prepared images in the runtime's store, newest first; empty (and logged) when the runtime cannot list them. Blocking. */
    private fun preparedImages(): List<PreparedImageDto> {
        val rt = runtime?.takeIf { enabled } ?: return emptyList()
        val images = try {
            rt.listPreparedImages()
        } catch (e: Exception) {
            log.warn("Listing prepared sandbox images failed: {}", e.message)
            return emptyList()
        }
        return images.sortedByDescending { it.createdAt }.map { img ->
            val l = img.labels
            PreparedImageDto(
                repo = l["$LABEL_PREPARED.repo"].orEmpty(), platform = l["$LABEL_PREPARED.platform"] ?: DEFAULT,
                key = l[LABEL_PREPARED].orEmpty(), definition = l["$LABEL_PREPARED.definition"].orEmpty(),
                baseImage = l["$LABEL_PREPARED.base"].orEmpty(), createdAt = img.createdAt.iso(), sizeBytes = img.sizeBytes,
                expired = preparedExpired(img.createdAt),
            )
        }
    }

    /**
     * Admin: removes every prepared image no container uses (never forced); the next sandbox of each project runs its
     * setup script again and stores a new one. Answers the overview, where images in use are still listed.
     */
    suspend fun removePrepared(admin: UserRecord): SandboxOverviewDto {
        if (!admin.isAdmin) throw forbidden("Only an admin can remove prepared sandbox images")
        val rt = runtime?.takeIf { enabled }
        if (rt != null) {
            val (removed, kept) = withContext(Dispatchers.IO) {
                val images = try {
                    rt.listPreparedImages()
                } catch (e: Exception) {
                    log.warn("Listing prepared sandbox images for removal failed: {}", e.message)
                    throw dk.egernet.factory.ApiException(io.ktor.http.HttpStatusCode.BadGateway, "sandbox_runtime_error",
                        "Could not list the prepared images; the server log has the details.")
                }
                var removed = 0
                var kept = 0
                for (img in images) {
                    val ok = try {
                        rt.removeImage(img.id)
                    } catch (e: Exception) {
                        log.warn("Removing prepared image {} failed: {}", img.id, e.message)
                        false
                    }
                    if (ok) removed++ else kept++
                }
                removed to kept
            }
            log.info("{} removed {} prepared sandbox image(s); {} in use kept", admin.username, removed, kept)
            emitFeed(FeedItem.Sandboxes("", "", "prepared"))
        }
        return overview(admin)
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
     * tracks, or that are still active, are kept; a review sandbox only while this server tracks it (a review round never
     * survives a restart).
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
            fun gone(r: LabelledResource): Boolean = if (r.name.startsWith(REVIEW_PREFIX)) {
                runCatching { UUID.fromString(r.runId) }.getOrNull()?.let { reviews.containsKey(it) } != true
            } else r.runId !in keep
            val goneContainers = containers.filter(::gone).map { it.name }
            val goneNetworks = networks.filter(::gone).map { it.name }.distinct()
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
        for (id in (sandboxes.keys + reviews.keys).distinct()) release(id)
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
        /** The review sandbox (docs/ARCHITECTURE.md §5 "Review sandbox"): its own command container, network and services. */
        fun reviewContainerName(runId: UUID) = "$REVIEW_PREFIX$runId"
        fun reviewNetworkName(runId: UUID) = "$REVIEW_PREFIX$runId"
        fun reviewServiceContainerName(runId: UUID, service: String) = "$REVIEW_PREFIX$runId-$service"
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

        /**
         * The review sandbox's command container: the default image with the env, `user:`, limits and default cache volumes
         * of [commandSpec], on the review network, the worktree bind-mounted **read-only** at [WORKDIR] and [outputDir]
         * (when there is one) read-write at [REVIEW_OUTPUT], the only host directory it can write to.
         */
        fun reviewSpec(runId: UUID, repo: String, worktree: Path, def: SandboxDefinition, outputDir: Path?) = ContainerSpec(
            name = reviewContainerName(runId),
            image = def.image,
            labels = labels(runId, repo, "review"),
            network = reviewNetworkName(runId),
            env = def.env,
            mounts = listOf(SandboxMount(worktree.toAbsolutePath().normalize().toString(), WORKDIR, volume = false, readOnly = true)) +
                listOfNotNull(outputDir?.let { SandboxMount(it.toAbsolutePath().normalize().toString(), REVIEW_OUTPUT, volume = false) }) +
                def.caches.map { SandboxMount(cacheVolumeName(repo, it), it, volume = true) },
            workdir = WORKDIR, cpus = def.cpus, memoryBytes = def.memoryBytes, entrypoint = "sh", command = keepAlive,
            keepIdUid = def.user?.uid, user = if (def.user != null) "0:0" else null,
        )

        /** The throwaway container a prepared image is made in, and its network (same name): per run and platform. */
        fun prepareName(runId: UUID, platform: String) = "agern-prepare-$runId-$platform"
        /** The review sandbox's preparation container and network (the review prefix: removed like the review sandbox). */
        fun reviewPrepareName(runId: UUID) = "${REVIEW_PREFIX}prepare-$runId"

        /**
         * The preparation container ([prepareName]): [image] with the definition's env, `user:` mapping and limits, on its
         * own network [name], `/workspace` the read-only [export] of the definition's commit — no worktree, no cache volumes.
         */
        fun prepareSpec(runId: UUID, repo: String, name: String, export: Path, def: SandboxDefinition, platform: SandboxPlatform?, image: String) = ContainerSpec(
            name = name,
            image = image,
            labels = labels(runId, repo, "prepare").also { l -> platform?.let { l["agern.platform"] = it.name } },
            network = name,
            env = def.env,
            mounts = listOf(SandboxMount(export.toAbsolutePath().normalize().toString(), WORKDIR, volume = false, readOnly = true)),
            workdir = WORKDIR, cpus = def.cpus, memoryBytes = def.memoryBytes, entrypoint = "sh", command = keepAlive,
            keepIdUid = def.user?.uid, user = if (def.user != null) "0:0" else null,
        )

        fun reviewServiceSpec(runId: UUID, repo: String, svc: SandboxService, def: SandboxDefinition) = ContainerSpec(
            name = reviewServiceContainerName(runId, svc.name), image = svc.image,
            labels = labels(runId, repo, "review-service").also { it["agern.service"] = svc.name },
            network = reviewNetworkName(runId), networkAlias = svc.name, env = svc.env, cpus = def.cpus, memoryBytes = def.memoryBytes,
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
        /** Name prefix of every review sandbox container and network. */
        const val REVIEW_PREFIX = "agern-review-"
        /** Where the review sandbox's output directory is mounted inside it (`/run` cannot hold a cache path). */
        const val REVIEW_OUTPUT = "/run/agern-review"
        /** The output directory's name below `.agern-run/` in the run directory. */
        const val REVIEW_OUTPUT_DIR = "review"
        /** The output directory relative to the agents' working directory, as reviewers pass it to factory_attach_screenshot. */
        const val REVIEW_OUTPUT_HOST = ".agern-run/review"
        /** What a spec-writer (plan scope) is told when it tries to run something. */
        const val PLAN_ONLY = "The spec-writer cannot run commands in the sandbox; factory_sandbox_info is for planning the " +
            "spec's Verification section. The builder runs the commands."
        /** The key of the top-level image among a run's command containers. */
        const val DEFAULT = SandboxDefinitions.DEFAULT_PLATFORM

        /** `resources/sandbox/prepare-user.sh`: run as root with `sh -c <script> agern-user <uid> <name> <caches…>`. */
        val PREPARE_USER_SCRIPT: String = SandboxManager::class.java.getResource("/sandbox/prepare-user.sh")!!.readText()

        /** After setup (as root): files it left in the user's home or the worktree go to the user; `-xdev` skips cache volumes. */
        const val HAND_OVER_SCRIPT = "uid=\$1; shift; find \"\$@\" -xdev ! -user \"\$uid\" -exec chown -h \"\$uid:\$uid\" {} + 2>/dev/null || true"

        private const val REMOVED_WHILE_RUNNING = "The run's sandbox was removed while the command ran (the run ended)."
        private const val REVIEW_REMOVED_WHILE_RUNNING = "The review sandbox was removed while the command ran (the review round or the run ended)."
        /** Finished commands whose results a run keeps for `factory_sandbox_wait`. */
        const val KEPT_RESULTS = 20
        private const val STOPPED_WHILE_RUNNING = "An admin stopped this run's sandbox while the command ran, so it was killed. " +
            "The next command creates a new sandbox."
        /** The end of a review sandbox's refusal: the reviewer goes on without it. */
        private const val REVIEW_ON_HOST = "Review without it, on the host, as your role instructions describe; do not wait for a slot."

        const val STARTED_REUSED = "reused"
        const val STARTED_PREPARED = "prepared"
        const val STARTED_SETUP = "setup"
        const val STARTED_PLAIN = "plain"

        /** Bumped when the key's parts or the commit change meaning, so older prepared images are never reused. */
        private const val PREPARED_KEY_VERSION = "agern-prepared-v1"

        /**
         * A prepared image's key: the first 16 hex characters of SHA-256 over the format version, [repo], the platform
         * key, the approved definition's hash, the base image's local id and the hashes of the server's preparation
         * scripts (so a new Agern version with a changed `prepare-user.sh` prepares again).
         */
        fun preparedKey(
            repo: String, platform: String, definitionSha256: String, baseImageId: String,
            prepareUserScript: String = PREPARE_USER_SCRIPT, handOverScript: String = HAND_OVER_SCRIPT,
        ): String = SandboxDefinitions.sha256(listOf(
            PREPARED_KEY_VERSION, repo, platform, definitionSha256, baseImageId,
            SandboxDefinitions.sha256(prepareUserScript), SandboxDefinitions.sha256(handOverScript),
        ).joinToString("\n")).take(16)

        /** `localhost/agern-prepared/<slug>:<key>`; the slug is [repo] lower-cased with runs of other characters as `-`. */
        fun preparedImageName(repo: String, key: String): String {
            val slug = repo.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(100).trim('-').ifEmpty { "repo" }
            return "localhost/agern-prepared/$slug:$key"
        }

        /** The labels a prepared image is committed with ([LABEL_PREPARED] carries the key). */
        fun preparedLabels(key: String, repo: String, platform: String, definitionSha256: String, baseImage: String) = linkedMapOf(
            LABEL_PREPARED to key,
            "$LABEL_PREPARED.repo" to repo,
            "$LABEL_PREPARED.platform" to platform,
            "$LABEL_PREPARED.definition" to definitionSha256,
            "$LABEL_PREPARED.base" to baseImage,
        )
        /** How much of a command's output the agent gets back (the end of it). */
        const val OUTPUT_TAIL_CHARS = 20_000
        val TOOLS = setOf("factory_sandbox_info", "factory_sandbox_exec", "factory_sandbox_wait")
    }
}
