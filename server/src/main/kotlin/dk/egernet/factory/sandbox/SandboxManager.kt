package dk.egernet.factory.sandbox

import dk.egernet.factory.Config
import dk.egernet.factory.OwnerDto
import dk.egernet.factory.RunSandboxDto
import dk.egernet.factory.RunStatus
import dk.egernet.factory.SandboxApprovalDto
import dk.egernet.factory.SandboxConfig
import dk.egernet.factory.SandboxDefinitionDto
import dk.egernet.factory.SandboxLimitsDto
import dk.egernet.factory.SandboxServiceDto
import dk.egernet.factory.SandboxStatusDto
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.add
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** What a tool call answers: JSON or a message for the agent, and whether it is an error. */
data class SandboxAnswer(val isError: Boolean, val text: String)

/**
 * Per-run sandboxes (docs/ARCHITECTURE.md §5 "Sandboxes"): reads and checks a run's definition when it starts, creates
 * the containers lazily on the builder's first command, runs commands, enforces the concurrency cap and removes
 * everything when the run's build/review loop is over (and leftovers at server start).
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
    /** Appends a `system` event (payload as given) to an agent session's transcript. */
    private val publishSystem: (UUID, JsonObject) -> Unit,
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

    private class RunSandbox(val runId: UUID, val repo: String, val worktree: Path, val plan: Plan, val info: RunSandboxDto) {
        /** Serialises creation and commands: one command at a time per run. Release never takes it. */
        val lock = Mutex()
        val slotHeld = AtomicBoolean(false)
        @Volatile var created = false
        @Volatile var released = false
    }

    private fun names(rs: RunSandbox): List<String> {
        val def = (rs.plan as? Plan.Available)?.definition ?: return listOf(containerName(rs.runId))
        return listOf(containerName(rs.runId)) + def.services.map { serviceContainerName(rs.runId, it.name) }
    }

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
            withContext(Dispatchers.IO) { git.readFile(worktree, baseRef, SANDBOX_FILE, home, SandboxDefinitions.MAX_BYTES) }
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
                if (approval != null && approval.sha256 == parsed.sha256) {
                    record(
                        runId,
                        RunSandboxDto(
                            "approved",
                            "Sandbox definition ${parsed.sha256.take(12)} approved by ${approval.approvedByUsername}; image ${parsed.definition.image}. " +
                                "It is created on the builder's first sandbox command.",
                            parsed.sha256, parsed.definition.image,
                        ),
                        Plan.Available(parsed.definition, parsed.sha256), repo, worktree,
                    )
                } else {
                    val why = if (approval == null) "no version of it has been approved by an admin"
                    else "it changed since an admin approved version ${approval.sha256.take(12)}"
                    record(
                        runId,
                        RunSandboxDto("unapproved", "$SANDBOX_FILE (${parsed.sha256.take(12)}) is not approved: $why. An admin can approve it in the project's sandbox settings.", parsed.sha256, parsed.definition.image),
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
        val ports: Map<String, List<String>> = if (plan is Plan.Available && rs.created) withContext(Dispatchers.IO) {
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
            if (plan is Plan.Available && rs != null) {
                val def = plan.definition
                put("image", def.image)
                put("created", rs.created)
                put("workdir", WORKDIR)
                put("shell", "Commands run as `sh -lc <command>` in $WORKDIR, which is your working directory (the same files).")
                putJsonArray("services") {
                    for (s in def.services) addJsonObject {
                        put("name", s.name); put("host", s.name); put("image", s.image)
                        putJsonArray("ports") { ports[s.name].orEmpty().forEach { add(it) } }
                    }
                }
                putJsonArray("caches") { def.caches.forEach { add(it) } }
                putJsonArray("env") { def.env.keys.forEach { add(it) } }
                put("setup", def.setup != null)
                putJsonObject("limits") {
                    put("cpus", def.cpus); put("memory", SandboxConfig.formatMemory(def.memoryBytes))
                    put("execTimeoutSeconds", execCapMs / 1000); put("outputTailChars", OUTPUT_TAIL_CHARS)
                }
                putJsonObject("capacity") { put("inUse", sc.maxConcurrent - slots.availablePermits()); put("max", sc.maxConcurrent) }
            }
        }
        return SandboxAnswer(false, obj.toString())
    }

    /**
     * `factory_sandbox_exec`: runs [command] with `sh -lc` in the run's sandbox, creating it first if needed. The full
     * output (up to [ExecLimits.LOG_CHARS]) goes into [sessionId]'s transcript as a `system` event; the agent gets the tail.
     */
    suspend fun exec(runId: UUID, sessionId: UUID, command: String, timeoutSeconds: Long?): SandboxAnswer {
        if (!enabled) return SandboxAnswer(true, "Sandboxes are switched off on this server. Build and test on the host as usual.")
        val rs = sandboxes[runId] ?: return SandboxAnswer(true, "This run has no sandbox. Build and test on the host as usual.")
        val plan = rs.plan
        if (plan !is Plan.Available) return SandboxAnswer(true, "No sandbox for this run: ${rs.info.reason} Build and test on the host as usual.")
        if (command.isBlank()) return SandboxAnswer(true, "command must not be empty")
        if (command.length > 100_000 || '\u0000' in command) return SandboxAnswer(true, "command is too long or contains NUL")
        val cap = execCapMs
        if (timeoutSeconds != null && timeoutSeconds <= 0) return SandboxAnswer(true, "timeoutSeconds must be positive")
        val timeoutMs = minOf(timeoutSeconds?.let { it * 1000 } ?: cap, cap)
        return rs.lock.withLock {
            if (rs.released) return@withLock SandboxAnswer(true, "The run's sandbox has been removed.")
            if (!rs.created) create(rs, plan, sessionId)?.let { return@withLock SandboxAnswer(true, it) }
            val r = try {
                withContext(Dispatchers.IO) { runtime!!.exec(containerName(runId), listOf("sh", "-lc", command), WORKDIR, timeoutMs) }
            } catch (e: Exception) {
                return@withLock SandboxAnswer(true, "The sandbox command could not be run: ${e.message}")
            }
            if (rs.released) return@withLock SandboxAnswer(true, "The run's sandbox was removed while the command ran (the run ended).")
            if (r.timedOut) {
                // Kill whatever the command left running; files (and what setup installed) are kept.
                runCatching { withContext(Dispatchers.IO) { runtime!!.restartContainer(containerName(runId)) } }
                    .onFailure { log.warn("Restarting sandbox of run {} after a timeout failed: {}", runId, it.message) }
            }
            logEvent(sessionId, "exec", command, r, timeoutMs)
            val tail = r.output.takeLast(OUTPUT_TAIL_CHARS)
            SandboxAnswer(false, buildJsonObject {
                put("exitCode", r.exitCode)
                put("output", tail)
                put("truncated", r.outputTruncated || tail.length < r.output.length)
                put("durationMs", r.durationMs)
                put("timedOut", r.timedOut)
                if (r.timedOut) put("note", "Stopped after ${timeoutMs / 1000} s; every process in the sandbox was killed (files are kept).")
            }.toString())
        }
    }

    /** Creates network, cache volumes, sidecars and the sandbox, then runs setup. Returns an error message, or null. */
    private suspend fun create(rs: RunSandbox, plan: Plan.Available, sessionId: UUID): String? {
        val rt = runtime!!
        if (!slots.tryAcquire()) {
            return "The server's sandboxes are all in use (${sc.maxConcurrent} of ${sc.maxConcurrent}, by other runs). " +
                "Nothing was run. Try again in a few minutes; meanwhile continue with work that needs no build."
        }
        rs.slotHeld.set(true)
        val def = plan.definition
        val started = System.currentTimeMillis()
        publishSystem(sessionId, systemPayload("info", "Creating the sandbox: image ${def.image}" +
            (if (def.services.isNotEmpty()) ", services ${def.services.joinToString(", ") { it.name }}" else "") + " …"))
        try {
            withContext(Dispatchers.IO) {
                rt.createNetwork(networkName(rs.runId), labels(rs.runId, rs.repo, "network"))
                for (c in def.caches) rt.ensureVolume(cacheVolumeName(rs.repo, c), linkedMapOf(LABEL_REPO to rs.repo, "agern.cache" to c))
                for (s in def.services) rt.startContainer(serviceSpec(rs.runId, rs.repo, s, def))
                rt.startContainer(mainSpec(rs.runId, rs.repo, rs.worktree, def))
            }
            rs.created = true
            if (rs.released) {
                teardown(rs)
                return "The run ended while its sandbox was being created."
            }
            if (def.setup != null) {
                val r = withContext(Dispatchers.IO) { rt.exec(containerName(rs.runId), listOf("sh", "-lc", def.setup), WORKDIR, execCapMs) }
                logEvent(sessionId, "setup", def.setup, r, execCapMs)
                if (r.timedOut || r.exitCode != 0) {
                    teardown(rs)
                    return "The sandbox's setup script failed (" + (if (r.timedOut) "timed out" else "exit ${r.exitCode}") +
                        "), so the sandbox was removed. Output (tail):\n" + r.output.takeLast(4000) +
                        "\nThe definition comes from the default branch and needs an admin's approval to change; report the problem in your summary."
                }
            }
            publishSystem(sessionId, systemPayload("info", "Sandbox ready in ${(System.currentTimeMillis() - started) / 1000.0} s: ${def.image}, " +
                "${PodmanRuntime.formatCpus(def.cpus)} CPUs, ${SandboxConfig.formatMemory(def.memoryBytes)} memory."))
            return null
        } catch (e: Exception) {
            log.warn("Creating the sandbox of run {} failed: {}", rs.runId, e.message)
            teardown(rs)
            publishSystem(sessionId, systemPayload("warn", "Could not create the sandbox: ${e.message}"))
            return "Could not create the sandbox: ${e.message}"
        }
    }

    private fun systemPayload(level: String, text: String) = buildJsonObject { put("level", level); put("text", text) }

    /** The full log of a setup script or command, kept in the agent's transcript for the run page. */
    private fun logEvent(sessionId: UUID, kind: String, command: String, r: ExecResult, timeoutMs: Long) {
        val what = if (kind == "setup") "setup script" else "`" + command.lineSequence().first().take(200) + "`"
        val outcome = if (r.timedOut) "timed out after ${timeoutMs / 1000} s" else "exit ${r.exitCode}"
        publishSystem(sessionId, buildJsonObject {
            put("level", if (r.timedOut || r.exitCode != 0) "warn" else "info")
            put("text", "Sandbox $what: $outcome in ${r.durationMs / 1000.0} s")
            putJsonObject("sandbox") {
                put("kind", kind); put("command", command); put("exitCode", r.exitCode); put("timedOut", r.timedOut)
                put("durationMs", r.durationMs); put("output", r.output); put("outputTruncated", r.outputTruncated)
            }
        })
    }

    // ------------------------------------------------------------------ cleanup

    /** Removes the run's sandbox (containers, network) and frees its slot. Idempotent; never throws. */
    suspend fun release(runId: UUID) {
        val rs = sandboxes.remove(runId) ?: return
        rs.released = true
        withContext(NonCancellable + Dispatchers.IO) { teardown(rs) }
    }

    private fun teardown(rs: RunSandbox) {
        val rt = runtime ?: return
        if (rs.created || rs.slotHeld.get()) {
            runCatching { rt.removeContainers(names(rs)) }.onFailure { log.warn("Removing the sandbox of run {} failed: {}", rs.runId, it.message) }
            runCatching { rt.removeNetwork(networkName(rs.runId)) }.onFailure { log.warn("Removing the network of run {} failed: {}", rs.runId, it.message) }
        }
        rs.created = false
        if (rs.slotHeld.getAndSet(false)) slots.release()
    }

    /**
     * Removes containers and networks labelled `agern.run` whose run is not active (server start: whatever a crash or
     * restart left behind). Sandboxes of runs this server still tracks, or that are still active, are kept.
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

    /** Sandboxes that exist right now (created and not yet removed). */
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
        if (content == null) {
            return@withContext SandboxStatusDto(repo, enabled, SANDBOX_FILE, r.defaultBranch, "none", null, null, emptyList(), null, approval)
        }
        when (val p = SandboxDefinitions.parse(content, sc)) {
            is ParsedDefinition.Invalid ->
                SandboxStatusDto(repo, enabled, SANDBOX_FILE, r.defaultBranch, "invalid", p.sha256, content, p.errors, null, approval)
            is ParsedDefinition.Valid -> SandboxStatusDto(
                repo, enabled, SANDBOX_FILE, r.defaultBranch, if (approval?.sha256 == p.sha256) "approved" else "pending",
                p.sha256, content, emptyList(), dto(p.definition), approval,
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
        return status(user, repo)
    }

    /** Admin only: removes the repository's approval. Runs that already started keep the decision they made. */
    suspend fun revoke(user: UserRecord, repo: String): SandboxStatusDto {
        withContext(Dispatchers.IO) { access.requireVisible(user, repo) }
        if (!user.isAdmin) throw forbidden("Only an admin can revoke a sandbox approval")
        if (withContext(Dispatchers.IO) { approvals.revoke(repo) }) log.info("Sandbox approval of {} revoked by {}", repo, user.username)
        return status(user, repo)
    }

    private fun dto(d: SandboxDefinition) = SandboxDefinitionDto(
        image = d.image, setup = d.setup, env = d.env, services = d.services.map { SandboxServiceDto(it.name, it.image, it.env) },
        caches = d.caches,
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
        fun cacheVolumeName(repo: String, path: String) = "agern-cache-$repo-" + SandboxDefinitions.sha256(path).take(12)

        fun labels(runId: UUID, repo: String, role: String) = linkedMapOf(LABEL_RUN to runId.toString(), LABEL_REPO to repo, LABEL_ROLE to role)

        /** The command that keeps the sandbox alive; work happens through `exec`. Needs only a POSIX `sh` in the image. */
        val keepAlive = listOf("-c", "trap 'exit 0' TERM INT; while :; do sleep 3600 & wait \$!; done")

        fun mainSpec(runId: UUID, repo: String, worktree: Path, def: SandboxDefinition) = ContainerSpec(
            name = containerName(runId), image = def.image, labels = labels(runId, repo, "sandbox"), network = networkName(runId),
            env = def.env,
            mounts = listOf(SandboxMount(worktree.toAbsolutePath().normalize().toString(), WORKDIR, volume = false)) +
                def.caches.map { SandboxMount(cacheVolumeName(repo, it), it, volume = true) },
            workdir = WORKDIR, cpus = def.cpus, memoryBytes = def.memoryBytes, entrypoint = "sh", command = keepAlive,
        )

        fun serviceSpec(runId: UUID, repo: String, svc: SandboxService, def: SandboxDefinition) = ContainerSpec(
            name = serviceContainerName(runId, svc.name), image = svc.image,
            labels = labels(runId, repo, "service").also { it["agern.service"] = svc.name },
            network = networkName(runId), networkAlias = svc.name, env = svc.env, cpus = def.cpus, memoryBytes = def.memoryBytes,
        )

        const val WORKDIR = "/workspace"
        /** How much of a command's output the agent gets back (the end of it). */
        const val OUTPUT_TAIL_CHARS = 20_000
        val TOOLS = setOf("factory_sandbox_info", "factory_sandbox_exec")
    }
}
