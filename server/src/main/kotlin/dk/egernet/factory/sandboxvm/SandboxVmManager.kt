package dk.egernet.factory.sandboxvm

import dk.egernet.factory.AppJson
import dk.egernet.factory.ApiException
import dk.egernet.factory.Config
import dk.egernet.factory.DeployKeyDto
import dk.egernet.factory.OwnerDto
import dk.egernet.factory.RunSandboxVmDto
import dk.egernet.factory.RunStatus
import dk.egernet.factory.ReviewState
import dk.egernet.factory.SandboxApprovalDto
import dk.egernet.factory.SandboxReadyDto
import dk.egernet.factory.SandboxVmCheckDto
import dk.egernet.factory.SandboxVmDto
import dk.egernet.factory.SandboxVmLeaseDto
import dk.egernet.factory.SandboxVmPreviewDto
import dk.egernet.factory.SandboxVmStatusDto
import dk.egernet.factory.agent.Attachments
import dk.egernet.factory.conflict
import dk.egernet.factory.db.DeployKeyStore
import dk.egernet.factory.db.RunRecord
import dk.egernet.factory.db.RunStore
import dk.egernet.factory.db.SandboxVmApprovalStore
import dk.egernet.factory.db.SandboxVmLeaseRecord
import dk.egernet.factory.db.SandboxVmLeaseRecord.Companion.BUILD
import dk.egernet.factory.db.SandboxVmLeaseRecord.Companion.HELD
import dk.egernet.factory.db.SandboxVmLeaseRecord.Companion.PREVIEW
import dk.egernet.factory.db.SandboxVmLeaseRecord.Companion.RESETTING
import dk.egernet.factory.db.SandboxVmLeaseStore
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.db.iso
import dk.egernet.factory.deploy.DeployCancel
import dk.egernet.factory.deploy.DeployException
import dk.egernet.factory.deploy.DeployExecResult
import dk.egernet.factory.deploy.DeployIdentity
import dk.egernet.factory.deploy.DeployKeys
import dk.egernet.factory.deploy.DeployTimeout
import dk.egernet.factory.deploy.DeployTransport
import dk.egernet.factory.deploy.DeployUpload
import dk.egernet.factory.forbidden
import dk.egernet.factory.git.GitOps
import dk.egernet.factory.github.Access
import dk.egernet.factory.github.GitHubException
import dk.egernet.factory.notFound
import dk.egernet.factory.sandbox.SandboxAnswer
import dk.egernet.factory.security.SecretBox
import dk.egernet.factory.unprocessable
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sandbox VMs (docs/SANDBOX-VMS.md, docs/ARCHITECTURE.md §5 "Sandbox VMs"): a project's `.agern/sandbox-vm.yml`, its admin
 * approval, the run's decision, the lease that gives one run at a time a VM, the builder's tools, the human preview on
 * the run page and the admin operations. Agern connects with the project's deploy key (#88); the key never reaches an
 * agent. Every operation re-checks the approval and parses the definition from the approval's stored content.
 *
 * Like sandboxes and deploy targets this is NOT a boundary against the agent's OS user; it keeps the key out of the
 * agent's hands, pins the host key and limits the machine and commands to what an admin approved.
 */
class SandboxVmManager(
    private val cfg: Config,
    /** Null when FACTORY_SANDBOX_VM=off. Its own instance, so closing it ends only VM connections. */
    private val transport: DeployTransport?,
    private val keys: DeployKeyStore,
    private val box: SecretBox,
    private val approvals: SandboxVmApprovalStore,
    private val leaseStore: SandboxVmLeaseStore,
    private val runs: RunStore,
    private val access: Access,
    private val git: GitOps,
    /** Where operations run: independent of the tool call, so a dropped call neither aborts nor loses one. */
    private val scope: CoroutineScope,
    /** Appends a `system` event (payload as given) to an agent session's transcript. */
    private val publishSystem: (UUID, JsonObject) -> Unit,
    /** Announces a change of run [UUID]'s stored state on the live feed. */
    private val emitRun: (UUID) -> Unit = {},
    /** The git worktree of a run's change (a workspace run: the target member's) and the owner's home. */
    private val checkoutOf: (RunRecord) -> Pair<Path, Path>,
    /** The admins, who can approve definitions (shown to everyone on the status). */
    private val approvers: () -> List<OwnerDto> = { emptyList() },
    /** Development only (FACTORY_DEV_FAKE_GITHUB): where a run reads its definition instead of the base commit. */
    private val devDefinitionSource: ((String) -> String?)? = null,
    /** Pause between two runs of the readiness check. */
    private val readyPollMs: Long = 1000,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(SandboxVmManager::class.java)
    private val vc get() = cfg.sandboxVm
    private val publicUrl = cfg.publicUrl.trimEnd('/')

    val enabled: Boolean get() = vc.enabled && transport != null
    private val mode: String get() = if (enabled) vc.mode else "off"
    private val previewMinutes: Int get() = (vc.previewMs / 60_000).toInt()
    /** The time limit of one command and of one whole deploy: the VM's limit, never beyond a stage. */
    private val execCapMs: Long get() = minOf(vc.execTimeoutMs, cfg.stageTimeoutMs)

    private fun pageUrl(repo: String) = "$publicUrl/p/$repo/sandbox-vm"

    // ------------------------------------------------------------------ in-memory state

    /** One operation of a lease (a deploy or a command), foreground or background, under its [handle]. */
    private class Op(val handle: String, val kind: String, val background: Boolean) {
        val startedAt = System.currentTimeMillis()
        val result = CompletableDeferred<SandboxAnswer>()
        val cancel = DeployCancel()
        @Volatile var job: Job? = null
        val running: Boolean get() = !result.isCompleted
        val elapsedMs: Long get() = System.currentTimeMillis() - startedAt
    }

    /** A lease this server holds, mirroring its `sandbox_vm_leases` row. [definition]: null when it cannot be known any more. */
    private class Lease(
        val vmKey: String, val runId: UUID, val repo: String, val kind: String, val sha256: String,
        val definition: SandboxVmDefinition?, val acquiredAt: Instant,
    ) {
        /** One operation at a time: taken by the call (waiting in the foreground, `tryLock` in the background), released by the job. */
        val lock = Mutex()
        @Volatile var current: Op? = null
        @Volatile var latest: Op? = null
        /** The running operation and the last [KEPT_RESULTS] finished ones, oldest first. Guarded by itself. */
        val ops = LinkedHashMap<String, Op>()
        val ended = AtomicBoolean()
        /** The builder session that used the VM last (where a failed reset of a build lease is reported). */
        @Volatile var sessionId: UUID? = null
        @Volatile var deployedSha: String? = null
        @Volatile var expiry: Job? = null
        @Volatile var usedNoted = false
    }

    private val byKey = ConcurrentHashMap<String, Lease>()
    private val byRun = ConcurrentHashMap<UUID, Lease>()
    /** Serialises taking leases in this process (the database row decides between servers). */
    private val acquireLock = Any()
    private val decisionLocks = ConcurrentHashMap<UUID, Any>()
    private val random = SecureRandom()

    // ------------------------------------------------------------------ the run's decision

    /**
     * Decides whether run [runId] gets the sandbox VM: reads `.agern/sandbox-vm.yml` from [baseRef] of the repository at
     * [worktree] (the commit and repository the container sandbox reads its file from), validates it and checks the
     * approval of [definitionRepo]. Never throws; the decision is stored on the run and returned.
     */
    suspend fun prepare(runId: UUID, worktree: Path, baseRef: String, home: Path, definitionRepo: String): RunSandboxVmDto =
        withContext(Dispatchers.IO) {
            val dto = decide(worktree, baseRef, home, definitionRepo)
            runCatching { runs.setSandboxVm(runId, dto) }.onFailure { log.warn("Recording the sandbox VM decision of run {} failed: {}", runId, it.message) }
            dto
        }

    private fun decide(worktree: Path, baseRef: String, home: Path, repo: String): RunSandboxVmDto {
        val pm = previewMinutes
        if (!enabled) return RunSandboxVmDto("disabled", "Sandbox VMs are switched off on this server (FACTORY_SANDBOX_VM=off).", previewMinutes = pm)
        val content = try {
            devDefinitionSource?.invoke(repo) ?: git.readFile(worktree, baseRef, SANDBOX_VM_FILE, home, SandboxVmDefinitions.MAX_BYTES)
        } catch (e: Exception) {
            return RunSandboxVmDto("invalid", "Could not read $SANDBOX_VM_FILE from $baseRef: ${e.message}", previewMinutes = pm)
        } ?: return RunSandboxVmDto("none", "The repository has no $SANDBOX_VM_FILE on its default branch ($baseRef).", previewMinutes = pm)
        return when (val p = SandboxVmDefinitions.parse(content)) {
            is ParsedSandboxVm.Invalid -> RunSandboxVmDto("invalid", "$SANDBOX_VM_FILE on $baseRef is invalid: " + p.errors.joinToString("; "), p.sha256, previewMinutes = pm)
            is ParsedSandboxVm.Valid -> {
                val d = p.definition
                val approval = try {
                    approvals.get(repo)
                } catch (e: Exception) {
                    return RunSandboxVmDto("invalid", "Could not read the approval of $SANDBOX_VM_FILE: ${e.message}", p.sha256, previewMinutes = pm)
                }
                if (approval?.sha256 == p.sha256) {
                    RunSandboxVmDto(
                        "approved", "Sandbox VM definition ${p.sha256.take(12)} approved by ${approval.approvedByUsername}: ${d.name} (${d.user}@${d.host}:${d.port}).",
                        p.sha256, d.name, d.host, d.user, d.url, previewMinutes = pm,
                    )
                } else {
                    val why = if (approval == null) "no version of it has been approved by an admin"
                    else "it changed since an admin approved version ${approval.sha256.take(12)}"
                    RunSandboxVmDto("unapproved", "$SANDBOX_VM_FILE (${p.sha256.take(12)}) is not approved: $why. An admin can approve it in the project's sandbox VM settings.",
                        p.sha256, d.name, previewMinutes = pm)
                }
            }
        }
    }

    /** Changes run [runId]'s stored decision (one change at a time per run) and announces it; null when the run has none. */
    private fun update(runId: UUID, change: (RunSandboxVmDto) -> RunSandboxVmDto): RunSandboxVmDto? {
        val out = try {
            synchronized(decisionLocks.computeIfAbsent(runId) { Any() }) {
                val cur = runs.get(runId)?.sandboxVm ?: return null
                val next = change(cur)
                if (next != cur) runs.setSandboxVm(runId, next)
                next
            }
        } catch (e: Exception) {
            log.warn("Updating the sandbox VM state of run {} failed: {}", runId, e.message)
            return null
        }
        runCatching { emitRun(runId) }
        return out
    }

    // ------------------------------------------------------------------ the approval re-check

    private sealed interface Resolved {
        class Ok(val definition: SandboxVmDefinition, val sha256: String, val identity: DeployIdentity, val repo: String) : Resolved
        class Refused(val reason: String, val code: String) : Resolved
    }

    private val offReason = "Sandbox VMs are switched off on this server (FACTORY_SANDBOX_VM=off)."

    /** The repository whose file, approval and key a run uses: the target member of a workspace run. */
    private fun definitionRepo(run: RunRecord) = run.targetRepo ?: run.repo

    /**
     * Whether an operation of [run] may use the VM now: its decision is `approved`, the repository's current approval has
     * the same hash, and the project has a key. The definition comes from the approval's stored content. Blocking.
     */
    private fun resolve(run: RunRecord): Resolved {
        if (!enabled) return Resolved.Refused(offReason, "sandbox_vm_disabled")
        val repo = definitionRepo(run)
        val d = run.sandboxVm ?: return Resolved.Refused("This run has no sandbox VM.", "sandbox_vm_not_approved")
        if (d.state != "approved") return Resolved.Refused("No sandbox VM for this run: ${d.reason}", "sandbox_vm_not_approved")
        val approval = approvals.get(repo)
            ?: return Resolved.Refused("The approval of $SANDBOX_VM_FILE was revoked since the run started; an admin must approve it again: ${pageUrl(repo)}", "sandbox_vm_not_approved")
        if (approval.sha256 != d.sha256) {
            return Resolved.Refused(
                "The approval of $SANDBOX_VM_FILE changed since the run started (the run has ${d.sha256?.take(12)}, approved now is " +
                    "${approval.sha256.take(12)}); the VM is not available to this run: ${pageUrl(repo)}",
                "sandbox_vm_not_approved",
            )
        }
        val def = (SandboxVmDefinitions.parse(approval.content) as? ParsedSandboxVm.Valid)?.definition
            ?: return Resolved.Refused("The approved $SANDBOX_VM_FILE can no longer be read; an admin must approve it again: ${pageUrl(repo)}", "sandbox_vm_not_approved")
        val id = DeployKeys.identity(keys, box, repo) ?: return Resolved.Refused(noKey(repo), "sandbox_vm_key_missing")
        return Resolved.Ok(def, approval.sha256, id, repo)
    }

    private fun noKey(repo: String) =
        "This project has no deploy key yet, which the sandbox VM uses. An admin must generate it on the project's sandbox VM page: ${pageUrl(repo)}"

    // ------------------------------------------------------------------ leases

    /** Who blocks a lease: the row (null when it vanished meanwhile: being reset). */
    private class Busy(val holder: SandboxVmLeaseRecord?)

    /** The run's lease on [r]'s VM: re-used when it holds it, else taken atomically; or [Busy]. Blocking. */
    private fun acquire(run: RunRecord, kind: String, r: Resolved.Ok, startedBy: UUID?, expiresAt: Instant?): Any = synchronized(acquireLock) {
        val key = r.definition.vmKey
        byRun[run.id]?.let { l -> if (!l.ended.get() && l.vmKey == key) return l }
        if (leaseStore.acquire(key, r.repo, run.id, kind, r.sha256, startedBy, expiresAt)) {
            val l = Lease(key, run.id, r.repo, kind, r.sha256, r.definition, clock.instant())
            byKey[key] = l
            byRun[run.id] = l
            log.info("Sandbox VM {} leased to run {} of {} ({})", key, run.id, run.repo, kind)
            return l
        }
        Busy(leaseStore.get(key))
    }

    private fun busyText(def: SandboxVmDefinition, b: Busy): String {
        val h = b.holder
        return if (h == null || h.state == RESETTING) "The sandbox VM ${def.name} is being reset."
        else "The sandbox VM ${def.name} is in use by run ${h.runId} of ${h.repo} since ${h.acquiredAt.iso()}."
    }

    private val fallback = " Continue without it: verify in the container sandbox or on the host, and say in your summary that the VM check was skipped."

    /**
     * Ends [l]: never blocks, never throws. The lease becomes `resetting`, an operation in flight is cancelled, `reset`
     * runs once when [reset] is set (and the approval still matches and the key exists), then the row is deleted.
     */
    private fun end(l: Lease, reset: Boolean, why: String) {
        if (!l.ended.compareAndSet(false, true)) return
        byRun.remove(l.runId, l)
        l.expiry?.cancel()
        // ATOMIC: the row is deleted even when the scope is cancelled before the job starts.
        scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            try {
                runCatching { leaseStore.markResetting(l.vmKey, l.runId) }
                val cur = l.current
                if (cur != null) {
                    cur.result.complete(SandboxAnswer(true, LEASE_ENDED))
                    cur.cancel.cancel()
                    cur.job?.cancel()
                    // `reset` must not start while the cancelled operation still talks to the VM (at most 30 s).
                    withTimeoutOrNull(30_000) { cur.job?.join() }
                }
                if (reset) runCatching { runReset(l) }.onFailure { log.warn("Reset of sandbox VM {} failed: {}", l.vmKey, it.message) }
            } finally {
                runCatching { leaseStore.delete(l.vmKey, l.runId) }.onFailure { log.warn("Deleting the lease of sandbox VM {} failed: {}", l.vmKey, it.message) }
                byKey.remove(l.vmKey, l)
                log.info("Sandbox VM {} released by run {} ({}, {})", l.vmKey, l.runId, l.kind, why)
            }
        }
    }

    /** Runs the definition's `reset` for an ending lease, if it may; reports a failed or skipped reset. Blocking. */
    private fun runReset(l: Lease) {
        val def = l.definition
        if (def?.reset == null) {
            if (def == null) skipped(l, "the definition the lease was taken with is no longer approved")
            return
        }
        val t = transport ?: return skipped(l, "sandbox VMs are switched off")
        val approval = approvals.get(l.repo)
        if (approval?.sha256 != l.sha256) return skipped(l, "the approval was revoked or changed")
        val id = DeployKeys.identity(keys, box, l.repo) ?: return skipped(l, "the project has no deploy key")
        val started = System.nanoTime()
        val r = try {
            t.exec(def.endpoint, id, envLine(l.deployedSha, l.runId) + "\n" + def.reset, vc.execTimeoutMs)
        } catch (e: Exception) {
            val msg = (e as? DeployException)?.message ?: e.message ?: e.javaClass.simpleName
            log.warn("Reset of sandbox VM {} (run {}) failed: {}", l.vmKey, l.runId, msg)
            record(l.takeIf { it.kind == BUILD }?.sessionId, def, "reset", null, def.reset, l.deployedSha, null, false,
                (System.nanoTime() - started) / 1_000_000, "", false, msg, ok = false, text = "reset: $msg")
            return
        }
        val ok = !r.timedOut && r.exitCode == 0
        val outcome = outcomeOf(r, vc.execTimeoutMs)
        if (!ok) log.warn("Reset of sandbox VM {} (run {}) failed: {}", l.vmKey, l.runId, outcome)
        else log.info("Reset of sandbox VM {} (run {}): {} in {} ms", l.vmKey, l.runId, outcome, r.durationMs)
        record(l.takeIf { it.kind == BUILD }?.sessionId, def, "reset", null, def.reset, l.deployedSha, r.exitCode, r.timedOut,
            r.durationMs, r.output, r.outputTruncated, null, ok, "reset: $outcome in ${seconds(r.durationMs)}")
    }

    private fun skipped(l: Lease, why: String) {
        val def = l.definition
        if (def != null && def.reset == null) return
        log.warn("Reset of sandbox VM {} (run {}) skipped: {}", l.vmKey, l.runId, why)
        val sid = l.sessionId?.takeIf { l.kind == BUILD } ?: return
        runCatching {
            publishSystem(sid, buildJsonObject { put("level", "warn"); put("text", "Sandbox VM reset skipped: $why. The VM was released anyway.") })
        }
    }

    /** Ends [l] as a preview: the run's preview becomes `released` ([expired]: its time ran out). */
    private fun endPreview(l: Lease, expired: Boolean, why: String) {
        if (l.ended.get()) return
        end(l, reset = true, why)
        markReleased(l.runId, expired)
    }

    private fun markReleased(runId: UUID, expired: Boolean) {
        update(runId) { d ->
            val p = d.preview?.takeIf { it.state == "deploying" || it.state == "ready" } ?: return@update d
            d.copy(preview = p.copy(state = "released", endedAt = clock.instant().iso(), expired = expired))
        }
    }

    private fun armExpiry(l: Lease, at: Instant) {
        l.expiry?.cancel()
        l.expiry = scope.launch {
            delay((at.toEpochMilli() - clock.instant().toEpochMilli()).coerceAtLeast(0))
            withContext(Dispatchers.IO) { endPreview(l, expired = true, "the preview expired") }
        }
    }

    /** The pipeline's build/review loop is over (or failed, or was cancelled): ends the run's `build` lease. Never blocks. */
    fun releaseBuild(runId: UUID) {
        byRun[runId]?.takeIf { it.kind == BUILD }?.let { end(it, reset = true, "the build ended") }
    }

    /** The run ended: ends whatever lease it holds and closes its preview. Never blocks (beyond one database read). */
    fun releaseAll(runId: UUID) {
        val l = byRun[runId]
        if (l != null) {
            if (l.kind == PREVIEW) endPreview(l, false, "the run ended") else end(l, reset = true, "the run ended")
        } else if (enabled) {
            runCatching { if (runs.get(runId)?.sandboxVm?.preview?.state.let { it == "deploying" || it == "ready" }) markReleased(runId, false) }
        }
        decisionLocks.remove(runId)
    }

    /**
     * Called whenever run [run] changed: a preview ends when the run leaves `needs_human` (approved, cancelled, merged) or a
     * follow-up review starts (the commit on the VM is no longer the one being decided on).
     */
    fun onRunChanged(run: RunRecord) {
        val l = byRun[run.id]?.takeIf { it.kind == PREVIEW && !it.ended.get() } ?: return
        if (run.status != RunStatus.NEEDS_HUMAN || run.reviewState == ReviewState.RUNNING) {
            endPreview(l, false, "the run left needs_human")
        }
    }

    // ------------------------------------------------------------------ builder tools

    /** `factory_sandbox_vm_info`: never an error for an unavailable VM. */
    suspend fun info(runId: UUID): SandboxAnswer = withContext(Dispatchers.IO) {
        val run = runs.get(runId)
        val r = run?.let { resolve(it) } ?: Resolved.Refused("Unknown run.", "not_found")
        val own = byRun[runId]?.takeIf { !it.ended.get() }
        val obj = buildJsonObject {
            put("available", r is Resolved.Ok)
            put("state", run?.sandboxVm?.state ?: if (enabled) "none" else "disabled")
            put("reason", when (r) {
                is Resolved.Refused -> r.reason
                is Resolved.Ok -> "Approved by an admin. Agern holds the key and connects for you. Deploy your committed HEAD with " +
                    "factory_sandbox_vm_deploy, then verify with factory_sandbox_vm_exec."
            })
            val def = (r as? Resolved.Ok)?.definition
            if (def != null) {
                put("name", def.name); put("host", def.host); put("port", def.port); put("user", def.user)
                put("description", def.description); put("url", def.url)
                putJsonObject("commands") {
                    put("checkout", true)
                    put("setup", def.setup != null)
                    if (def.ready == null) put("ready", JsonNull)
                    else putJsonObject("ready") { put("command", def.ready.command); put("timeoutSeconds", def.ready.timeoutSeconds) }
                    put("reset", def.reset != null)
                }
                val holder = leaseStore.get(def.vmKey)
                putJsonObject("lease") {
                    put("heldByThisRun", holder != null && holder.runId == runId && holder.state == HELD)
                    if (holder != null && holder.runId != runId) {
                        putJsonObject("heldByOther") { put("runId", holder.runId.toString()); put("repo", holder.repo); put("since", holder.acquiredAt.iso()) }
                    } else put("heldByOther", JsonNull)
                    put("resetting", holder?.state == RESETTING)
                }
                put("deployedSha", own?.deployedSha)
                own?.current?.takeIf { it.running }?.let { c ->
                    putJsonObject("command") { put("handle", c.handle); put("running", true); put("elapsedMs", c.elapsedMs) }
                }
                put("note", "The VM is shared: only one run can use it at a time, and the next run's deploy replaces what you deployed. " +
                    "Commands run as ${def.user} in its home directory through the login shell (no PTY, no stdin). A command that times " +
                    "out has its connection closed but may keep running on the VM. Only committed changes are deployed.")
            }
            putJsonObject("limits") {
                put("execTimeoutSeconds", execCapMs / 1000); put("outputTailChars", OUTPUT_TAIL_CHARS)
                put("toolCallTimeoutSeconds", cfg.mcpToolTimeoutMs / 1000)
            }
        }
        SandboxAnswer(false, obj.toString())
    }

    /** What an operation of the builder needs: the run, the re-checked approval and the run's lease. */
    private class Ready(val run: RunRecord, val ok: Resolved.Ok, val lease: Lease)

    /** Re-checks the approval and finds (or, [take]: takes) the run's build lease; a [Ready] or the refusal text. Blocking. */
    private fun ready(runId: UUID, take: Boolean): Any {
        val run = runs.get(runId) ?: return "Unknown run."
        val r = resolve(run)
        if (r is Resolved.Refused) return r.reason
        r as Resolved.Ok
        if (!take) {
            val l = byRun[runId]?.takeIf { !it.ended.get() && it.vmKey == r.definition.vmKey } ?: return "This run does not hold the sandbox VM. Call factory_sandbox_vm_deploy first."
            return Ready(run, r, l)
        }
        return when (val got = acquire(run, BUILD, r, null, null)) {
            is Lease -> Ready(run, r, got)
            else -> busyText(r.definition, got as Busy) + fallback
        }
    }

    /** `factory_sandbox_vm_deploy`: takes the lease if needed and deploys the run's committed HEAD. */
    suspend fun deploy(runId: UUID, sessionId: UUID, background: Boolean): SandboxAnswer {
        val p = withContext(Dispatchers.IO) { ready(runId, take = true) }
        if (p !is Ready) return SandboxAnswer(true, p as String)
        noteUsed(p.lease)
        return startOp(p.lease, sessionId, "deploy", background) { op ->
            val started = System.nanoTime()
            val res = try {
                runInterruptible(Dispatchers.IO) { runDeploy(p.lease, p.ok, p.run, op.cancel, sessionId) }
            } catch (e: DeployException) {
                logOp(p, "deploy", "error (${e.stage})", started)
                return@startOp SandboxAnswer(true, "Sandbox VM deploy to ${p.ok.definition.name} failed: ${describe(e)}")
            }
            logOp(p, "deploy", if (res.ok) "ok" else "failed at ${res.failedStep}", started)
            SandboxAnswer(false, buildJsonObject {
                put("handle", op.handle)
                put("ok", res.ok); put("sha", res.sha); put("dirty", res.dirty); put("url", p.ok.definition.url)
                putJsonArray("steps") {
                    for (s in res.steps) addJsonObject {
                        put("step", s.step); put("ok", s.ok); put("exitCode", s.exitCode); put("timedOut", s.timedOut); put("durationMs", s.durationMs)
                    }
                }
                put("failedStep", res.failedStep)
                put("output", res.output); put("truncated", res.truncated); put("durationMs", res.durationMs)
                if (res.error != null) put("error", res.error)
                val notes = listOfNotNull(
                    if (res.dirty) "Your worktree has uncommitted changes; they were not deployed. Commit them and deploy again." else null,
                    if (!res.ok) "The deploy stopped at ${res.failedStep}. You still hold the VM: investigate with factory_sandbox_vm_exec." else null,
                )
                if (notes.isNotEmpty()) put("note", notes.joinToString(" "))
            }.toString())
        }
    }

    /** `factory_sandbox_vm_exec`: one command as one SSH exec; only while the run holds the lease. */
    suspend fun exec(runId: UUID, sessionId: UUID, command: String, timeoutSeconds: Long?, background: Boolean): SandboxAnswer {
        if (command.isBlank()) return SandboxAnswer(true, "command must not be empty")
        if (command.length > MAX_COMMAND_CHARS) return SandboxAnswer(true, "command is longer than $MAX_COMMAND_CHARS characters")
        if ('\u0000' in command) return SandboxAnswer(true, "command contains a NUL character")
        if (timeoutSeconds != null && timeoutSeconds <= 0) return SandboxAnswer(true, "timeoutSeconds must be a positive whole number")
        val timeoutMs = minOf(timeoutSeconds?.let { if (it > Long.MAX_VALUE / 1000) Long.MAX_VALUE else it * 1000 } ?: execCapMs, execCapMs)
        val p = withContext(Dispatchers.IO) { ready(runId, take = false) }
        if (p !is Ready) return SandboxAnswer(true, p as String)
        val def = p.ok.definition
        return startOp(p.lease, sessionId, "exec", background) { op ->
            val started = System.nanoTime()
            val r = try {
                runInterruptible(Dispatchers.IO) { transport!!.exec(def.endpoint, p.ok.identity, command, timeoutMs, op.cancel) }
            } catch (e: DeployException) {
                val what = describe(e)
                record(sessionId, def, "exec", null, command, null, null, false, (System.nanoTime() - started) / 1_000_000, "", false, what,
                    ok = false, text = "`${firstLine(command)}`: $what")
                logOp(p, "exec", "error (${e.stage})", started)
                return@startOp SandboxAnswer(true, "Sandbox VM exec on ${def.name} failed: $what")
            }
            if (op.cancel.cancelled) return@startOp SandboxAnswer(true, LEASE_ENDED)
            val outcome = outcomeOf(r, timeoutMs)
            record(sessionId, def, "exec", null, command, null, r.exitCode, r.timedOut, r.durationMs, r.output, r.outputTruncated, null,
                ok = !r.timedOut && r.exitCode == 0, text = "`${firstLine(command)}`: $outcome in ${seconds(r.durationMs)}")
            logOp(p, "exec", outcome, started)
            val tail = r.output.takeLast(OUTPUT_TAIL_CHARS)
            SandboxAnswer(false, buildJsonObject {
                put("handle", op.handle)
                put("exitCode", r.exitCode ?: -1)
                put("output", tail)
                put("truncated", r.outputTruncated || tail.length < r.output.length)
                put("durationMs", r.durationMs)
                put("timedOut", r.timedOut)
                if (r.timedOut) put("note", "Stopped after ${timeoutMs / 1000} s: the connection was closed, but the remote process may still be running.")
                else if (r.exitCode == null) put("note", "The remote command ended without an exit status" + (r.signal?.let { " (killed by signal $it)" } ?: "") + ".")
            }.toString())
        }
    }

    /** One line per operation in the server log: never the command or its output. */
    private fun logOp(p: Ready, kind: String, outcome: String, started: Long) {
        log.info("Sandbox VM {} of run {} ({}) on {}: {} in {} ms", kind, p.run.id, p.run.repo, p.lease.vmKey, outcome, (System.nanoTime() - started) / 1_000_000)
    }

    /**
     * Runs [body] as operation [kind] of [l] in a job on the manager's scope, one at a time per lease: in the foreground
     * the call waits for it, with [background] it returns the handle at once ([wait] gets the result).
     */
    private suspend fun startOp(l: Lease, sessionId: UUID?, kind: String, background: Boolean, body: suspend (Op) -> SandboxAnswer): SandboxAnswer {
        l.current?.takeIf { it.running && (background || it.background) }?.let { return busy(it) }
        if (background) {
            if (!l.lock.tryLock()) return l.current?.let { busy(it) } ?: SandboxAnswer(true, "Another sandbox VM operation of this run is " +
                "being started. Wait for it with factory_sandbox_vm_wait before starting another.")
        } else {
            l.lock.lock()
        }
        var handedOver = false
        try {
            if (l.ended.get()) return SandboxAnswer(true, "This run's sandbox VM lease has ended. Call factory_sandbox_vm_deploy to take the VM again.")
            if (sessionId != null) l.sessionId = sessionId
            val op = Op(newHandle(l), kind, background)
            synchronized(l.ops) { l.ops[op.handle] = op }
            l.latest = op
            l.current = op
            // ATOMIC: the job runs (and frees the lock) even when the scope is cancelled before it starts.
            op.job = scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    val answer = try {
                        body(op)
                    } catch (e: CancellationException) {
                        SandboxAnswer(true, LEASE_ENDED)
                    } catch (e: Exception) {
                        SandboxAnswer(true, "The sandbox VM operation could not be run: ${e.message ?: e.javaClass.simpleName}")
                    }
                    forget(l, op)
                    op.result.complete(answer)
                } finally {
                    op.result.complete(SandboxAnswer(true, "The sandbox VM operation could not be run: the server is shutting down."))
                    if (l.current === op) l.current = null
                    l.lock.unlock()
                }
            }
            handedOver = true
            if (!background) return op.result.await()
            return SandboxAnswer(false, buildJsonObject {
                put("handle", op.handle)
                put("running", true)
                put("note", "Started in the background. Call factory_sandbox_vm_wait to get the result; do not poll with sleep.")
            }.toString())
        } finally {
            if (!handedOver) l.lock.unlock()
        }
    }

    private fun busy(c: Op) = SandboxAnswer(true, "A sandbox VM operation of this run is still running (handle ${c.handle}, started " +
        "${c.elapsedMs / 1000} s ago). Wait for it with factory_sandbox_vm_wait before starting another.")

    private fun newHandle(l: Lease): String = synchronized(l.ops) {
        generateSequence { "vm-%08x".format(random.nextInt()) }.first { it !in l.ops }
    }

    private fun forget(l: Lease, ending: Op) = synchronized(l.ops) {
        val finished = l.ops.values.filter { it !== ending && !it.running }
        finished.take((finished.size - (KEPT_RESULTS - 1)).coerceAtLeast(0)).forEach { l.ops.remove(it.handle) }
    }

    /** `factory_sandbox_vm_wait`: the semantics of `factory_sandbox_wait`, for the run's lease. */
    suspend fun wait(runId: UUID, handle: String?, waitSeconds: Long?): SandboxAnswer {
        if (waitSeconds != null && waitSeconds < 0) return SandboxAnswer(true, "waitSeconds must be a whole number of seconds, 0 or more")
        val l = byRun[runId] ?: return SandboxAnswer(true, "This run holds no sandbox VM lease (it has not taken one, or it ended); " +
            "results are kept only while the lease lasts.")
        val wanted = handle?.trim()?.takeIf { it.isNotEmpty() }
        val op = if (wanted == null) l.latest ?: return SandboxAnswer(true, "No sandbox VM operation has been started in this run yet.")
        else synchronized(l.ops) { l.ops[wanted] } ?: return SandboxAnswer(true, "Unknown handle '${wanted.take(64)}'. Handles come from " +
            "factory_sandbox_vm_deploy and factory_sandbox_vm_exec; older results are dropped after $KEPT_RESULTS newer operations.")
        val answer = when {
            waitSeconds == null -> op.result.await()
            waitSeconds == 0L || !op.running -> if (op.running) null else op.result.await()
            else -> withTimeoutOrNull(minOf(waitSeconds * 1000, execCapMs)) { op.result.await() }
        } ?: return SandboxAnswer(false, buildJsonObject {
            put("handle", op.handle); put("running", true); put("elapsedMs", op.elapsedMs)
            put("note", "Still running. Call factory_sandbox_vm_wait again.")
        }.toString())
        if (answer.isError) return answer
        val done = AppJson.parseToJsonElement(answer.text).jsonObject
        return SandboxAnswer(false, buildJsonObject {
            put("handle", op.handle)
            put("running", false)
            for ((k, v) in done) if (k != "handle") put(k, v)
        }.toString())
    }

    /** Records on the run (once per lease) that its builder used the VM, so the run page can say. */
    private fun noteUsed(l: Lease) {
        if (l.usedNoted || l.kind != BUILD) return
        l.usedNoted = true
        update(l.runId) { if (it.used) it else it.copy(used = true) }
    }

    // ------------------------------------------------------------------ the deploy procedure

    private class Step(val step: String, val ok: Boolean, val exitCode: Int?, val timedOut: Boolean, val durationMs: Long, val output: String, val truncated: Boolean)

    private class Deployed(
        val ok: Boolean, val sha: String, val dirty: Boolean, val steps: List<Step>, val failedStep: String?, val output: String,
        val truncated: Boolean, val durationMs: Long, val error: String?,
    )

    /**
     * The deploy procedure (docs/SANDBOX-VMS.md "Deploying"): archive of the committed HEAD, upload, `checkout`, `setup`,
     * `ready`, within one deadline. A failing step is a result with `ok: false`; a connection, host key or authentication
     * failure is a [DeployException]. [sessionId]: the builder session whose transcript records each step (null: a preview).
     * Blocking; [cancel] aborts it.
     */
    private fun runDeploy(l: Lease, r: Resolved.Ok, run: RunRecord, cancel: DeployCancel, sessionId: UUID?): Deployed {
        val t = transport ?: throw DeployException("connect", offReason)
        val def = r.definition
        val started = System.nanoTime()
        val deadline = started + execCapMs * 1_000_000
        fun left(): Long = (deadline - System.nanoTime()) / 1_000_000
        fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000
        val (worktree, home) = checkoutOf(run)
        val sha = git.head(worktree, home).trim()
        if (!SHA_RE.matches(sha)) throw IllegalStateException("The run's HEAD could not be read")
        val dirty = runCatching { git.isDirty(worktree, home) }.getOrDefault(false)
        val steps = mutableListOf<Step>()
        fun step(s: Step, command: String?, error: String?, text: String): Step {
            steps += s
            record(sessionId, def, "deploy", s.step, command, sha, s.exitCode, s.timedOut, s.durationMs, s.output, s.truncated, error, s.ok, text)
            return s
        }
        fun finish(failed: Step?, error: String?): Deployed {
            val last = failed ?: steps.lastOrNull()
            val out = last?.output.orEmpty()
            val tail = out.takeLast(OUTPUT_TAIL_CHARS)
            return Deployed(failed == null, sha, dirty, steps.toList(), failed?.step, tail, (last?.truncated ?: false) || tail.length < out.length,
                ms(started), error)
        }
        // 1. Archive and 2. upload. The temp file is outside the worktree, owner-only, and always deleted.
        val tmp = try {
            Files.createTempFile("agern-sandbox-vm-", ".tar.gz", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } catch (e: UnsupportedOperationException) {
            Files.createTempFile("agern-sandbox-vm-", ".tar.gz")
        }
        try {
            val upStart = System.nanoTime()
            val failure: String? = try {
                git.archiveHead(worktree, sha, tmp, home)
                val size = Files.size(tmp)
                if (size > cfg.deploy.maxCopyBytes) {
                    "The archive of ${sha.take(7)} is ${Attachments.humanSize(size)}, larger than the upload limit of " +
                        "${Attachments.humanSize(cfg.deploy.maxCopyBytes)} (FACTORY_DEPLOY_MAX_COPY_MB)"
                } else {
                    val upload = DeployUpload(ARCHIVE_NAME, size, 0b110_000_000) { Files.newInputStream(tmp) }
                    val sent = t.copy(def.endpoint, r.identity, upload, ARCHIVE_NAME, left().coerceAtLeast(1), cancel)
                    step(Step("upload", true, null, false, ms(upStart), "Uploaded ${Attachments.humanSize(sent)} to ~/$ARCHIVE_NAME\n", false),
                        null, null, "upload of ${sha.take(7)}: ${Attachments.humanSize(sent)} in ${seconds(ms(upStart))}")
                    null
                }
            } catch (e: DeployException) {
                if (e.stage in CONNECT_STAGES) {
                    record(sessionId, def, "deploy", "upload", null, sha, null, false, ms(upStart), "", false, describe(e), false, "upload: ${describe(e)}")
                    throw e
                }
                e.message ?: e.stage
            } catch (e: DeployTimeout) {
                val s = step(Step("upload", false, null, true, ms(upStart), "", false), null, e.message, "upload: timed out")
                return finish(s, e.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (cancel.cancelled) throw CancellationException(LEASE_ENDED)
                "Upload failed: ${e.message ?: e.javaClass.simpleName}"
            }
            if (failure != null) {
                val s = step(Step("upload", false, null, false, ms(upStart), failure + "\n", false), null, failure, "upload: $failure")
                return finish(s, failure)
            }
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
        // 3. checkout and setup: one exec each.
        for ((name, command) in listOf("checkout" to def.checkout, "setup" to def.setup)) {
            if (command == null) continue
            if (cancel.cancelled) throw CancellationException(LEASE_ENDED)
            val remaining = left()
            if (remaining <= 0) {
                val s = step(Step(name, false, null, true, 0, "", false), command, "The deploy's time limit was reached", "$name: not started, time limit reached")
                return finish(s, "The deploy's time limit (${execCapMs / 1000} s) was reached before $name")
            }
            val st = System.nanoTime()
            val res = try {
                t.exec(def.endpoint, r.identity, envLine(sha, run.id) + "\n" + command, remaining, cancel)
            } catch (e: DeployException) {
                record(sessionId, def, "deploy", name, command, sha, null, false, ms(st), "", false, describe(e), false, "$name: ${describe(e)}")
                throw e
            }
            if (cancel.cancelled) throw CancellationException(LEASE_ENDED)
            val ok = !res.timedOut && res.exitCode == 0
            val outcome = outcomeOf(res, remaining)
            val s = step(Step(name, ok, res.exitCode, res.timedOut, res.durationMs, res.output, res.outputTruncated), command, null,
                "$name: $outcome in ${seconds(res.durationMs)}")
            if (!ok) return finish(s, "`$name` ended with $outcome")
            if (name == "checkout") {
                l.deployedSha = sha
                runCatching { leaseStore.setDeployed(l.vmKey, l.runId, sha) }
            }
        }
        // 4. ready: repeated until it passes, its timeout or the deadline.
        def.ready?.let { rd ->
            val st = System.nanoTime()
            val readyDeadline = minOf(st + rd.timeoutSeconds * 1_000_000_000L, deadline)
            var attempts = 0
            var last: DeployExecResult
            while (true) {
                if (cancel.cancelled) throw CancellationException(LEASE_ENDED)
                attempts++
                val remaining = ((readyDeadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1_000)
                last = try {
                    t.exec(def.endpoint, r.identity, envLine(sha, run.id) + "\n" + rd.command, remaining, cancel)
                } catch (e: DeployException) {
                    // A connection failure while polling is a failed attempt (the service may be restarting the network).
                    DeployExecResult(null, null, describe(e) + "\n", false, false, 0)
                }
                if (cancel.cancelled) throw CancellationException(LEASE_ENDED)
                if (!last.timedOut && last.exitCode == 0) break
                if (System.nanoTime() >= readyDeadline) {
                    val outcome = if (last.timedOut) "timed out" else last.exitCode?.let { "exit $it" } ?: "no exit status"
                    val s = step(Step("ready", false, last.exitCode, last.timedOut, ms(st), last.output, last.outputTruncated), rd.command, null,
                        "ready: not ready after $attempts attempt(s) in ${seconds(ms(st))}, last $outcome")
                    return finish(s, "`ready` did not pass within ${rd.timeoutSeconds} s ($attempts attempt(s); the last ended with $outcome)")
                }
                Thread.sleep(readyPollMs)
            }
            step(Step("ready", true, 0, false, ms(st), last.output, last.outputTruncated), rd.command, null,
                "ready: passed after $attempts attempt(s) in ${seconds(ms(st))}")
        }
        return finish(null, null)
    }

    /** One `system` event with a `sandboxVm` payload in [sessionId]'s transcript (nothing when null). Never throws. */
    private fun record(
        sessionId: UUID?, def: SandboxVmDefinition, kind: String, step: String?, command: String?, sha: String?, exitCode: Int?,
        timedOut: Boolean, ms: Long, output: String, truncated: Boolean, error: String?, ok: Boolean, text: String,
    ) {
        sessionId ?: return
        runCatching {
            publishSystem(sessionId, buildJsonObject {
                put("level", if (ok) "info" else "warn")
                put("text", "Sandbox VM [${def.name}] " + (if (kind == "deploy") "deploy " else "") + text)
                putJsonObject("sandboxVm") {
                    put("kind", kind); put("step", step); put("vm", def.name); put("host", def.host); put("port", def.port); put("user", def.user)
                    put("command", command); put("sha", sha)
                    put("exitCode", exitCode); put("timedOut", timedOut); put("durationMs", ms)
                    put("output", output.takeLast(dk.egernet.factory.sandbox.ExecLimits.LOG_CHARS)); put("outputTruncated", truncated)
                    if (error == null) put("error", JsonNull) else put("error", error)
                }
            })
        }.onFailure { log.warn("Recording a sandbox VM {} failed: {}", kind, it.message) }
    }

    // ------------------------------------------------------------------ human preview

    private suspend fun visibleRun(user: UserRecord, runId: UUID): RunRecord = withContext(Dispatchers.IO) {
        val r = runs.get(runId) ?: throw notFound("Run not found")
        if (!access.canSee(user, r.repo, r.contextRepos)) throw notFound("Run not found")
        r
    }

    /**
     * Owner or admin, only at `needs_human`: takes a `preview` lease and deploys the run's HEAD in the background; the run
     * (with its preview `deploying`) is returned at once. While the preview is `ready` it deploys again and renews it.
     */
    suspend fun startPreview(user: UserRecord, runId: UUID): RunRecord {
        val run = visibleRun(user, runId)
        if (run.ownerId != user.id && !user.isAdmin) throw forbidden("Only the run's owner or an admin can deploy it to the sandbox VM")
        if (!enabled) throw conflict(offReason, "sandbox_vm_disabled")
        if (run.status != RunStatus.NEEDS_HUMAN) throw conflict("Run is ${run.status}; only a run that needs a human can be tried on the sandbox VM", "run_not_waiting")
        val r = withContext(Dispatchers.IO) { resolve(run) }
        if (r is Resolved.Refused) throw conflict(r.reason, r.code)
        r as Resolved.Ok
        if (run.sandboxVm?.preview?.state == "deploying") throw conflict("This run is being deployed to the sandbox VM already", "sandbox_vm_busy")
        val now = clock.instant()
        val got = withContext(Dispatchers.IO) { acquire(run, PREVIEW, r, user.id, now.plusMillis(vc.previewMs)) }
        if (got is Busy) throw conflict(busyText(r.definition, got), "sandbox_vm_busy")
        val l = got as Lease
        if (l.kind != PREVIEW) throw conflict("The sandbox VM ${r.definition.name} is in use by this run's builder.", "sandbox_vm_busy")
        if (!l.lock.tryLock()) throw conflict("This run is being deployed to the sandbox VM already", "sandbox_vm_busy")
        var handedOver = false
        try {
            val (worktree, home) = checkoutOf(run)
            val sha = withContext(Dispatchers.IO) { runCatching { git.head(worktree, home).trim() }.getOrNull() }
            withContext(Dispatchers.IO) {
                update(runId) {
                    it.copy(preview = SandboxVmPreviewDto("deploying", sha, r.definition.url, OwnerDto(user.username, user.displayName), now.iso()))
                }
            }
            val op = Op(newHandle(l), "deploy", true)
            l.current = op
            op.job = scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
                val started = System.nanoTime()
                try {
                    val res = runInterruptible { runDeploy(l, r, run, op.cancel, null) }
                    if (l.ended.get()) return@launch
                    log.info("Sandbox VM preview of run {} ({}) on {}: {} in {} ms", runId, run.repo, l.vmKey,
                        if (res.ok) "ok" else "failed at ${res.failedStep}", (System.nanoTime() - started) / 1_000_000)
                    if (res.ok) {
                        val until = clock.instant().plusMillis(vc.previewMs)
                        runCatching { leaseStore.setExpiry(l.vmKey, l.runId, until) }
                        armExpiry(l, until)
                        update(runId) { d ->
                            d.copy(preview = (d.preview ?: SandboxVmPreviewDto("ready")).copy(
                                state = "ready", sha = res.sha, url = r.definition.url, expiresAt = until.iso(), endedAt = null, expired = false,
                                failedStep = null, error = null, output = null,
                            ))
                        }
                    } else {
                        previewFailed(l, res.failedStep, res.error ?: "failed", res.output)
                    }
                } catch (e: CancellationException) {
                    // The lease ended meanwhile; whoever ended it closed the preview.
                } catch (e: DeployException) {
                    log.info("Sandbox VM preview of run {} ({}) on {}: error ({})", runId, run.repo, l.vmKey, e.stage)
                    if (!l.ended.get()) previewFailed(l, e.stage, describe(e), null)
                } catch (e: Exception) {
                    log.warn("Sandbox VM preview of run {} failed: {}", runId, e.message)
                    if (!l.ended.get()) previewFailed(l, "deploy", e.message ?: e.javaClass.simpleName, null)
                } finally {
                    op.result.complete(SandboxAnswer(false, "{}"))
                    if (l.current === op) l.current = null
                    l.lock.unlock()
                }
            }
            handedOver = true
        } finally {
            if (!handedOver) l.lock.unlock()
        }
        return runs.get(runId)!!
    }

    private fun previewFailed(l: Lease, step: String?, error: String, output: String?) {
        end(l, reset = true, "the preview deploy failed")
        update(l.runId) { d ->
            d.copy(preview = (d.preview ?: SandboxVmPreviewDto("failed")).copy(
                state = "failed", failedStep = step, error = error, output = output?.takeLast(OUTPUT_TAIL_CHARS), expiresAt = null,
                endedAt = clock.instant().iso(),
            ))
        }
    }

    /** Owner or admin: ends the run's preview (the VM is reset and released). 404 when it has no active preview. */
    suspend fun releasePreview(user: UserRecord, runId: UUID): RunRecord {
        val run = visibleRun(user, runId)
        if (run.ownerId != user.id && !user.isAdmin) throw forbidden("Only the run's owner or an admin can release its sandbox VM")
        val l = byRun[runId]?.takeIf { it.kind == PREVIEW && !it.ended.get() }
        if (l == null) {
            val p = run.sandboxVm?.preview
            if (p?.state != "deploying" && p?.state != "ready") throw notFound("This run has no sandbox VM preview")
            withContext(Dispatchers.IO) { markReleased(runId, false) }
        } else {
            withContext(Dispatchers.IO) { endPreview(l, false, "released by ${user.username}") }
        }
        log.info("Sandbox VM preview of run {} released by {}", runId, user.username)
        return runs.get(runId)!!
    }

    // ------------------------------------------------------------------ project page and admin operations

    private class Read(val branch: String, val content: String?)

    /** The file on [repo]'s default branch, read with [user]'s token (404 when [user] cannot see the project). */
    private fun read(user: UserRecord, repo: String): Read {
        val r = access.requireVisible(user, repo)
        val content = try {
            access.requireClient(user).getFile(cfg.githubOrg, repo, SANDBOX_VM_FILE, r.defaultBranch)
        } catch (e: GitHubException) {
            if (e.status == 404) null else throw ApiException(HttpStatusCode.BadGateway, "github_error", e.message ?: "GitHub error")
        }
        return Read(r.defaultBranch, content)
    }

    /** The VM the page talks about: the current file's when valid, else the approved one's. */
    private fun pageDefinition(current: ParsedSandboxVm?, approvalContent: String?): SandboxVmDefinition? =
        (current as? ParsedSandboxVm.Valid)?.definition
            ?: approvalContent?.let { (SandboxVmDefinitions.parse(it) as? ParsedSandboxVm.Valid)?.definition }

    suspend fun status(user: UserRecord, repo: String): SandboxVmStatusDto = withContext(Dispatchers.IO) {
        val f = read(user, repo)
        val a = approvals.get(repo)
        val approval = a?.let { SandboxApprovalDto(it.sha256, it.content, OwnerDto(it.approvedByUsername, it.approvedByDisplayName), it.approvedAt.iso()) }
        val key = keys.get(repo)?.let { DeployKeyDto(it.publicKey, it.fingerprint, OwnerDto(it.createdByUsername, it.createdByDisplayName), it.createdAt.iso()) }
        val parsed = f.content?.let { SandboxVmDefinitions.parse(it) }
        val def = pageDefinition(parsed, a?.content)
        val lease = def?.let { leaseStore.get(it.vmKey) }?.let { l ->
            val run = runs.get(l.runId)
            val visible = run != null && access.canSee(user, run.repo, run.contextRepos)
            SandboxVmLeaseDto(l.kind, l.state, l.acquiredAt.iso(), l.expiresAt?.iso(), visible,
                if (visible) l.runId.toString() else null, if (visible) run?.repo else null, if (visible) run?.issueNumber else null)
        }
        val (state, errors) = when (parsed) {
            null -> "none" to emptyList()
            is ParsedSandboxVm.Invalid -> "invalid" to parsed.errors
            is ParsedSandboxVm.Valid -> (if (a?.sha256 == parsed.sha256) "approved" else "pending") to emptyList()
        }
        val vm = (parsed as? ParsedSandboxVm.Valid)?.definition?.let { d ->
            SandboxVmDto(d.name, d.host, d.port, d.user, d.hostKey.type, d.hostKey.fingerprint, d.description, d.url, d.checkout, d.setup,
                d.ready?.let { SandboxReadyDto(it.command, it.timeoutSeconds) }, d.reset)
        }
        SandboxVmStatusDto(repo, enabled, mode, SANDBOX_VM_FILE, f.branch, state, parsed?.sha256, f.content, errors, vm, approval, approvers(), key,
            lease, previewMinutes)
    }

    /** Admin only: approves exactly the content that hashes to [sha256], which must be what the default branch has now. */
    suspend fun approve(user: UserRecord, repo: String, sha256: String): SandboxVmStatusDto {
        val current = status(user, repo)
        if (!user.isAdmin) throw forbidden("Only an admin can approve a sandbox VM")
        if (current.state == "none" || current.content == null) throw notFound("$repo has no $SANDBOX_VM_FILE on ${current.branch}")
        if (current.sha256 != sha256.trim().lowercase()) {
            throw conflict("$SANDBOX_VM_FILE on ${current.branch} has changed (it is now ${current.sha256}); review it again", "sandbox_vm_definition_changed")
        }
        if (current.state == "invalid") throw unprocessable("The definition is invalid: " + current.errors.joinToString("; "), "sandbox_vm_definition_invalid")
        withContext(Dispatchers.IO) { approvals.approve(repo, current.sha256, current.content, user.id) }
        log.info("Sandbox VM definition {} of {} approved by {}", current.sha256, repo, user.username)
        return status(user, repo)
    }

    /**
     * Admin only: removes the approval. Takes effect at once: the repository's leases are deleted without running `reset`,
     * operations in flight are cancelled, and every later operation is refused.
     */
    suspend fun revoke(user: UserRecord, repo: String): SandboxVmStatusDto {
        withContext(Dispatchers.IO) { access.requireVisible(user, repo) }
        if (!user.isAdmin) throw forbidden("Only an admin can revoke the approval of a sandbox VM")
        withContext(Dispatchers.IO) {
            if (approvals.revoke(repo)) log.info("Sandbox VM approval of {} revoked by {}", repo, user.username)
            dropRepo(repo)
        }
        return status(user, repo)
    }

    /** Deletes [repo]'s leases at once, without `reset`; cancels what runs on them. Blocking. */
    private fun dropRepo(repo: String) {
        val rows = runCatching { leaseStore.deleteRepo(repo) }.getOrElse { log.warn("Deleting the sandbox VM leases of {} failed: {}", repo, it.message); emptyList() }
        for (row in rows) {
            val l = byKey[row.vmKey]?.takeIf { it.runId == row.runId }
            if (l != null) {
                l.ended.set(true)
                byRun.remove(l.runId, l)
                byKey.remove(l.vmKey, l)
                l.expiry?.cancel()
                l.current?.let { c -> c.result.complete(SandboxAnswer(true, LEASE_ENDED)); c.cancel.cancel(); c.job?.cancel() }
            }
            if (row.kind == PREVIEW) markReleased(row.runId, false)
            log.info("Sandbox VM lease of {} on {} deleted: the approval was revoked", row.runId, row.vmKey)
        }
    }

    /**
     * Admin only: connects to the VM, verifies its host key and authenticates; then runs `ready.command` once (60 s) when
     * the definition has one. Problems are an answer with `ok: false` and the stage that failed.
     */
    suspend fun check(user: UserRecord, repo: String): SandboxVmCheckDto {
        val st = status(user, repo)
        if (!user.isAdmin) throw forbidden("Only an admin can check the sandbox VM")
        val t = transport
        if (!enabled || t == null) throw conflict(offReason, "sandbox_vm_disabled")
        if (st.state != "approved") throw conflict("$SANDBOX_VM_FILE is not approved in its current version", "sandbox_vm_not_approved")
        val def = (SandboxVmDefinitions.parse(st.content!!) as ParsedSandboxVm.Valid).definition
        val id = withContext(Dispatchers.IO) { DeployKeys.identity(keys, box, repo) }
            ?: throw conflict("This project has no deploy key yet; generate it first", "sandbox_vm_key_missing")
        val started = System.nanoTime()
        fun ms() = (System.nanoTime() - started) / 1_000_000
        val dto = withContext(Dispatchers.IO) {
            try {
                t.check(def.endpoint, id)
            } catch (e: DeployException) {
                return@withContext SandboxVmCheckDto(false, e.stage.takeIf { it in CONNECT_STAGES } ?: "connect", e.message ?: e.stage, ms())
            } catch (e: Exception) {
                return@withContext SandboxVmCheckDto(false, "connect", e.message ?: e.javaClass.simpleName, ms())
            }
            val rd = def.ready ?: return@withContext SandboxVmCheckDto(true, "ok", "Reachable, host key matches, key accepted", ms())
            val r = try {
                t.exec(def.endpoint, id, envLine(null, null) + "\n" + rd.command, CHECK_READY_MS)
            } catch (e: DeployException) {
                return@withContext SandboxVmCheckDto(false, e.stage.takeIf { it in CONNECT_STAGES } ?: "connect", e.message ?: e.stage, ms())
            }
            val tail = r.output.takeLast(OUTPUT_TAIL_CHARS)
            if (!r.timedOut && r.exitCode == 0) SandboxVmCheckDto(true, "ok", "Reachable, host key matches, key accepted, ready check passed", ms(), 0, tail)
            else SandboxVmCheckDto(false, "ready", "Reachable, but the ready check ended with ${outcomeOf(r, CHECK_READY_MS)}", ms(), r.exitCode, tail)
        }
        log.info("Sandbox VM check of {} by {}: {} in {} ms", repo, user.username, dto.stage, dto.durationMs)
        return dto
    }

    /** Admin only: ends the VM's lease, whoever holds it (`reset` runs). 404 when the VM has none. */
    suspend fun forceRelease(user: UserRecord, repo: String): SandboxVmStatusDto {
        val st = status(user, repo)
        if (!user.isAdmin) throw forbidden("Only an admin can release the sandbox VM")
        withContext(Dispatchers.IO) {
            val a = approvals.get(repo)
            val parsed = st.content?.let { SandboxVmDefinitions.parse(it) }
            val def = pageDefinition(parsed, a?.content) ?: throw notFound("$repo has no sandbox VM")
            val row = leaseStore.get(def.vmKey) ?: throw notFound("The sandbox VM ${def.name} has no lease")
            val l = byKey[def.vmKey]?.takeIf { it.runId == row.runId }
            when {
                l != null && l.kind == PREVIEW -> endPreview(l, false, "released by admin ${user.username}")
                l != null -> end(l, reset = true, "released by admin ${user.username}")
                row.state == RESETTING -> {}
                else -> end(restored(row), reset = true, "released by admin ${user.username}").also {
                    if (row.kind == PREVIEW) markReleased(row.runId, false)
                }
            }
            log.info("Sandbox VM {} force-released by {}", def.vmKey, user.username)
        }
        return status(user, repo)
    }

    /** An in-memory lease for [row], with the definition of the approval it was taken with (null when that changed). */
    private fun restored(row: SandboxVmLeaseRecord): Lease {
        val a = approvals.get(row.repo)
        val def = a?.takeIf { it.sha256 == row.sha256 }?.let { (SandboxVmDefinitions.parse(it.content) as? ParsedSandboxVm.Valid)?.definition }
        return Lease(row.vmKey, row.runId, row.repo, row.kind, row.sha256, def, row.acquiredAt).also {
            it.deployedSha = row.deployedSha
            byKey[row.vmKey] = it
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Server start: keeps the `preview` leases of runs still at `needs_human` whose preview is ready and not expired (with
     * the expiry timer re-armed) and ends every other lease, asynchronously, so the start never waits for a VM.
     */
    fun recover() {
        val rows = runCatching { leaseStore.all() }.getOrElse { log.warn("Reading sandbox VM leases failed: {}", it.message); return }
        val now = clock.instant()
        for (row in rows) {
            runCatching {
                val run = runs.get(row.runId)
                val l = restored(row)
                val keep = row.kind == PREVIEW && row.state == HELD && run?.status == RunStatus.NEEDS_HUMAN &&
                    row.expiresAt?.isAfter(now) == true && run.sandboxVm?.preview?.state == "ready" && l.definition != null
                if (keep) {
                    byRun[row.runId] = l
                    armExpiry(l, row.expiresAt!!)
                    log.info("Sandbox VM preview of run {} on {} kept until {}", row.runId, row.vmKey, row.expiresAt)
                } else {
                    end(l, reset = true, "the server restarted")
                    if (row.kind == PREVIEW) markReleased(row.runId, row.expiresAt?.isAfter(now) == false)
                }
            }.onFailure { log.warn("Recovering the sandbox VM lease of {} failed: {}", row.vmKey, it.message) }
        }
    }

    /** Server shutdown: closes every open VM connection (leases stay in the database for [recover]). */
    fun shutdown() {
        runCatching { transport?.close() }
    }

    // ------------------------------------------------------------------ helpers

    private fun describe(e: DeployException): String = when (e.stage) {
        "connect" -> "could not connect: ${e.message}"
        else -> e.message ?: e.stage
    }

    private fun outcomeOf(r: DeployExecResult, timeoutMs: Long) = when {
        r.timedOut -> "timed out after ${timeoutMs / 1000} s"
        r.exitCode == null -> "no exit status" + (r.signal?.let { " (signal $it)" } ?: "")
        else -> "exit ${r.exitCode}"
    }

    private fun firstLine(command: String) = command.trim().lineSequence().first().take(200)
    private fun seconds(ms: Long) = "%.1f s".format(Locale.ROOT, ms / 1000.0)

    companion object {
        val TOOLS = setOf("factory_sandbox_vm_info", "factory_sandbox_vm_deploy", "factory_sandbox_vm_exec", "factory_sandbox_vm_wait")
        /** The archive's name in the SSH user's home directory. */
        const val ARCHIVE_NAME = "agern-sandbox-vm.tar.gz"
        const val MAX_COMMAND_CHARS = 32_768
        const val OUTPUT_TAIL_CHARS = 20_000
        const val KEPT_RESULTS = 20
        const val CHECK_READY_MS = 60_000L
        const val LEASE_ENDED = "The sandbox VM lease ended while the command ran."
        private val CONNECT_STAGES = setOf("connect", "host_key", "auth")
        private val SHA_RE = Regex("^[0-9a-f]{40}([0-9a-f]{24})?$")
        private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        /**
         * The first line of every definition command: the commit, the run and where the archive is. The values are
         * validated hex and a UUID, so they need no further quoting; anything else becomes empty.
         */
        fun envLine(sha: String?, runId: UUID?): String {
            val s = sha?.takeIf { SHA_RE.matches(it) }.orEmpty()
            val r = runId?.toString()?.takeIf { UUID_RE.matches(it) }.orEmpty()
            return "export AGERN_SHA='$s' AGERN_RUN_ID='$r' AGERN_ARCHIVE=\"\$HOME/$ARCHIVE_NAME\""
        }
    }
}
