package dk.egernet.factory.deploy

import dk.egernet.factory.ApiException
import dk.egernet.factory.Config
import dk.egernet.factory.DeployCheckDto
import dk.egernet.factory.DeployKeyDto
import dk.egernet.factory.DeployStatusDto
import dk.egernet.factory.DeployTargetDto
import dk.egernet.factory.OwnerDto
import dk.egernet.factory.SandboxApprovalDto
import dk.egernet.factory.agent.Attachments
import dk.egernet.factory.conflict
import dk.egernet.factory.db.DeployApprovalStore
import dk.egernet.factory.db.DeployKeyStore
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.db.iso
import dk.egernet.factory.forbidden
import dk.egernet.factory.github.Access
import dk.egernet.factory.github.GitHubException
import dk.egernet.factory.notFound
import dk.egernet.factory.sandbox.SandboxAnswer
import dk.egernet.factory.security.SecretBox
import dk.egernet.factory.unprocessable
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-project SSH deploy targets (docs/DEPLOY.md, docs/ARCHITECTURE.md §5 "Deploy targets"): the project's key pair,
 * the admin approval of `.agern/deploy.yml`, and the chat tools that make Agern connect on the agent's behalf. The agent
 * never gets the key; every command and copy is recorded in the chat's transcript.
 *
 * Like sandboxes this is NOT a boundary against the agent's OS user (agents run as the server's user); it keeps the key
 * out of the agent's hands, pins the host keys and limits the targets to what an admin approved.
 */
class DeployManager(
    private val cfg: Config,
    /** Null when FACTORY_DEPLOY=off. */
    private val transport: DeployTransport?,
    private val keys: DeployKeyStore,
    private val approvals: DeployApprovalStore,
    private val access: Access,
    private val box: SecretBox,
    /** Where operations run: independent of the tool call, so a dropped HTTP request never aborts or loses one. */
    private val scope: CoroutineScope,
    /** Appends a `system` event (payload as given) to a chat session's transcript. */
    private val publishSystem: (UUID, JsonObject) -> Unit,
    /** The chat's working directory for [UserRecord] in a repository: where `factory_deploy_copy` takes files from. */
    private val chatDir: (UserRecord, String) -> Path,
    /** The admins, who can approve definitions and manage keys (shown to everyone on the status). */
    private val approvers: () -> List<OwnerDto> = { emptyList() },
) {
    private val log = LoggerFactory.getLogger(DeployManager::class.java)
    private val dc get() = cfg.deploy
    private val publicUrl = cfg.publicUrl.trimEnd('/')

    val enabled: Boolean get() = dc.enabled && transport != null

    /** (repo, target) pairs with an operation running: one at a time per target. */
    private val busy: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun pageUrl(repo: String) = "$publicUrl/p/$repo/deploy"

    // ------------------------------------------------------------------ status and approval (docs/ARCHITECTURE.md §8)

    private class Read(val branch: String, val content: String?)

    /** The file on [repo]'s default branch, read with [user]'s token (404 when [user] cannot see the project). */
    private fun read(user: UserRecord, repo: String): Read {
        val r = access.requireVisible(user, repo)
        val content = try {
            access.requireClient(user).getFile(cfg.githubOrg, repo, DEPLOY_FILE, r.defaultBranch)
        } catch (e: GitHubException) {
            if (e.status == 404) null else throw ApiException(HttpStatusCode.BadGateway, "github_error", e.message ?: "GitHub error")
        }
        return Read(r.defaultBranch, content)
    }

    suspend fun status(user: UserRecord, repo: String): DeployStatusDto = withContext(Dispatchers.IO) {
        val f = read(user, repo)
        val approval = approvals.get(repo)?.let {
            SandboxApprovalDto(it.sha256, it.content, OwnerDto(it.approvedByUsername, it.approvedByDisplayName), it.approvedAt.iso())
        }
        val key = keys.get(repo)?.let { DeployKeyDto(it.publicKey, it.fingerprint, OwnerDto(it.createdByUsername, it.createdByDisplayName), it.createdAt.iso()) }
        val mode = if (enabled) dc.mode else "off"
        fun dto(state: String, sha: String?, errors: List<String>, targets: List<DeployTargetDto>) = DeployStatusDto(
            repo, enabled, mode, DEPLOY_FILE, f.branch, state, sha, f.content, errors, targets, approval, approvers(), key,
        )
        val content = f.content ?: return@withContext dto("none", null, emptyList(), emptyList())
        when (val p = DeployDefinitions.parse(content)) {
            is ParsedDeploy.Invalid -> dto("invalid", p.sha256, p.errors, emptyList())
            is ParsedDeploy.Valid -> dto(
                if (approval?.sha256 == p.sha256) "approved" else "pending", p.sha256, emptyList(),
                p.definition.targets.map { DeployTargetDto(it.name, it.host, it.port, it.user, it.hostKey.type, it.hostKey.fingerprint, it.description) },
            )
        }
    }

    /** Admin only: approves exactly the content that hashes to [sha256], which must be what the default branch has now. */
    suspend fun approve(user: UserRecord, repo: String, sha256: String): DeployStatusDto {
        val current = status(user, repo)
        if (!user.isAdmin) throw forbidden("Only an admin can approve deploy targets")
        if (current.state == "none" || current.content == null) throw notFound("$repo has no $DEPLOY_FILE on ${current.branch}")
        if (current.sha256 != sha256.trim().lowercase()) {
            throw conflict("$DEPLOY_FILE on ${current.branch} has changed (it is now ${current.sha256}); review it again", "deploy_definition_changed")
        }
        if (current.state == "invalid") throw unprocessable("The definition is invalid: " + current.errors.joinToString("; "), "deploy_definition_invalid")
        withContext(Dispatchers.IO) { approvals.approve(repo, current.sha256, current.content, user.id) }
        log.info("Deploy definition {} of {} approved by {}", current.sha256, repo, user.username)
        return status(user, repo)
    }

    /** Admin only: removes the approval; no target of the project is usable until a version is approved again. */
    suspend fun revoke(user: UserRecord, repo: String): DeployStatusDto {
        withContext(Dispatchers.IO) { access.requireVisible(user, repo) }
        if (!user.isAdmin) throw forbidden("Only an admin can revoke the approval of deploy targets")
        if (withContext(Dispatchers.IO) { approvals.revoke(repo) }) log.info("Deploy approval of {} revoked by {}", repo, user.username)
        return status(user, repo)
    }

    // ------------------------------------------------------------------ key

    private fun comment(repo: String) = "agern-deploy:${cfg.githubOrg}/$repo"
    private fun aad(repo: String) = DeployKeys.aad(repo)

    /** Admin only: generates the project's first key pair (409 `deploy_key_exists` when it has one). */
    suspend fun generateKey(user: UserRecord, repo: String): DeployStatusDto {
        withContext(Dispatchers.IO) { access.requireVisible(user, repo) }
        if (!user.isAdmin) throw forbidden("Only an admin can generate the deploy key")
        val exists = conflict("$repo already has a deploy key; rotate it to replace it", "deploy_key_exists")
        if (withContext(Dispatchers.IO) { keys.get(repo) } != null) throw exists
        val k = DeployKeys.generate(comment(repo))
        val stored = withContext(Dispatchers.IO) { keys.insert(repo, k.publicKey, k.fingerprint, box.encrypt(k.privateKey, aad(repo)), user.id) }
        if (!stored) throw exists
        log.info("Deploy key {} of {} generated by {}", k.fingerprint, repo, user.username)
        return status(user, repo)
    }

    /** Admin only: replaces the key pair at once (404 when there is none); operations that start afterwards use the new one. */
    suspend fun rotateKey(user: UserRecord, repo: String): DeployStatusDto {
        withContext(Dispatchers.IO) { access.requireVisible(user, repo) }
        if (!user.isAdmin) throw forbidden("Only an admin can rotate the deploy key")
        val k = DeployKeys.generate(comment(repo))
        val replaced = withContext(Dispatchers.IO) { keys.replace(repo, k.publicKey, k.fingerprint, box.encrypt(k.privateKey, aad(repo)), user.id) }
        if (!replaced) throw notFound("$repo has no deploy key to rotate; generate one first")
        log.info("Deploy key of {} rotated to {} by {}", repo, k.fingerprint, user.username)
        return status(user, repo)
    }

    /** The decrypted key, in memory for one operation; null when the project has none. */
    private fun identity(repo: String): DeployIdentity? = DeployKeys.identity(keys, box, repo)

    // ------------------------------------------------------------------ check

    /**
     * Admin only: connects to target [name], verifies its host key, authenticates and disconnects, running nothing (so it
     * works with a forced `command=` in authorized_keys). Connection problems are an answer with `ok: false`.
     */
    suspend fun check(user: UserRecord, repo: String, name: String): DeployCheckDto {
        val st = status(user, repo)
        if (!user.isAdmin) throw forbidden("Only an admin can check deploy targets")
        val t = transport
        if (!enabled || t == null) throw conflict("Deploy targets are switched off on this server (FACTORY_DEPLOY=off)", "deploy_disabled")
        if (st.state != "approved") throw conflict("$DEPLOY_FILE is not approved in its current version", "deploy_not_approved")
        val parsed = DeployDefinitions.parse(st.content!!) as ParsedDeploy.Valid
        val target = parsed.definition.target(name) ?: throw notFound("$DEPLOY_FILE has no target '$name'")
        val id = withContext(Dispatchers.IO) { identity(repo) }
            ?: throw conflict("This project has no deploy key yet; generate it first", "deploy_key_missing")
        val lock = lockKey(repo, name)
        if (!busy.add(lock)) throw conflict(busyMessage(name), "deploy_busy")
        val started = System.nanoTime()
        try {
            val result = withContext(Dispatchers.IO) {
                try {
                    t.check(DeployEndpoint(target), id)
                    null
                } catch (e: DeployException) {
                    e
                } catch (e: Exception) {
                    DeployException("connect", e.message ?: e.javaClass.simpleName)
                }
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            log.info("Deploy check of {}/{} by {}: {} in {} ms", repo, name, user.username, result?.stage ?: "ok", ms)
            return if (result == null) DeployCheckDto(name, true, "ok", "Reachable, host key matches, key accepted", ms)
            else DeployCheckDto(name, false, result.stage, result.message ?: result.stage, ms)
        } finally {
            busy.remove(lock)
        }
    }

    private fun lockKey(repo: String, target: String) = "$repo\u0000$target"
    private fun busyMessage(target: String) = "Another deploy operation is running on $target; try again when it has finished"

    // ------------------------------------------------------------------ tools

    private sealed interface Resolved {
        class Ok(val definition: DeployDefinition, val identity: DeployIdentity?, val sha256: String) : Resolved
        class Unavailable(val state: String, val reason: String) : Resolved
    }

    private val offReason = "Deploy targets are switched off on this server (FACTORY_DEPLOY=off)."

    /** Reads, parses and checks the definition afresh, as the chat's owner, and loads the key. */
    private fun resolve(owner: UserRecord, repo: String): Resolved {
        if (!enabled) return Resolved.Unavailable("disabled", offReason)
        val f = try {
            read(owner, repo)
        } catch (e: ApiException) {
            return Resolved.Unavailable("unreadable", "Could not read $DEPLOY_FILE: ${e.message}")
        }
        val content = f.content ?: return Resolved.Unavailable(
            "none", "This project has no $DEPLOY_FILE on its default branch (${f.branch}), so it has no deploy targets. See ${pageUrl(repo)}",
        )
        val valid = when (val p = DeployDefinitions.parse(content)) {
            is ParsedDeploy.Invalid -> return Resolved.Unavailable(
                "invalid", "$DEPLOY_FILE on ${f.branch} is invalid: ${p.errors.joinToString("; ")}",
            )
            is ParsedDeploy.Valid -> p
        }
        val approval = approvals.get(repo)
        if (approval?.sha256 != valid.sha256) {
            val why = if (approval == null) "no version of it has been approved" else "it changed since an admin approved version ${approval.sha256.take(12)}"
            return Resolved.Unavailable(
                "pending", "$DEPLOY_FILE (${valid.sha256.take(12)}) is not approved: $why. An admin must approve it on the project's Deploy page: ${pageUrl(repo)}",
            )
        }
        return Resolved.Ok(valid.definition, identity(repo), valid.sha256)
    }

    private fun noKey(repo: String) = "This project has no deploy key yet. An admin must generate it on the project's Deploy page: ${pageUrl(repo)}"

    private fun unknownTarget(def: DeployDefinition, name: String) =
        "Unknown target '${name.take(64)}'. This project's targets are: ${def.targets.joinToString(", ") { it.name }}."

    /** `factory_deploy_targets`: never an error for a missing or unapproved file (`available: false` with the reason). */
    suspend fun targets(owner: UserRecord, repo: String): SandboxAnswer = withContext(Dispatchers.IO) {
        val r = resolve(owner, repo)
        val obj = buildJsonObject {
            when (r) {
                is Resolved.Unavailable -> {
                    put("available", false); put("state", r.state); put("reason", r.reason)
                    putJsonArray("targets") {}
                    put("publicKeyFingerprint", keys.get(repo)?.fingerprint)
                }
                is Resolved.Ok -> {
                    put("available", r.identity != null)
                    put("state", "approved")
                    put("reason", if (r.identity == null) noKey(repo) else
                        "Approved by an admin. Agern holds the key and connects for you; use these targets only when the person in the chat asks to deploy.")
                    putJsonArray("targets") {
                        for (t in r.definition.targets) addJsonObject {
                            put("name", t.name); put("host", t.host); put("port", t.port); put("user", t.user); put("description", t.description)
                        }
                    }
                    put("publicKeyFingerprint", r.identity?.fingerprint)
                }
            }
            putJsonObject("limits") {
                put("execTimeoutSeconds", dc.execTimeoutMs / 1000); put("maxCopyBytes", dc.maxCopyBytes); put("outputTailChars", OUTPUT_TAIL_CHARS)
            }
        }
        SandboxAnswer(false, obj.toString())
    }

    /** What one operation needs once the definition, target and key are known. */
    private class Op(val target: DeployTarget, val identity: DeployIdentity)

    private fun prepare(owner: UserRecord, repo: String, name: String): Any {
        val r = resolve(owner, repo)
        if (r is Resolved.Unavailable) return r.reason
        r as Resolved.Ok
        val t = r.definition.target(name.trim()) ?: return unknownTarget(r.definition, name)
        val id = r.identity ?: return noKey(repo)
        return Op(t, id)
    }

    /** `factory_deploy_exec`: runs [command] on target [name] as one SSH exec request. */
    suspend fun exec(owner: UserRecord, repo: String, sessionId: UUID, name: String, command: String, timeoutSeconds: Long?): SandboxAnswer {
        if (command.isBlank()) return SandboxAnswer(true, "command must not be empty")
        if (command.length > MAX_COMMAND_CHARS) return SandboxAnswer(true, "command is longer than $MAX_COMMAND_CHARS characters")
        if ('\u0000' in command) return SandboxAnswer(true, "command contains a NUL character")
        if (timeoutSeconds != null && timeoutSeconds <= 0) return SandboxAnswer(true, "timeoutSeconds must be a positive whole number")
        val timeoutMs = minOf(timeoutSeconds?.let { if (it > Long.MAX_VALUE / 1000) Long.MAX_VALUE else it * 1000 } ?: dc.execTimeoutMs, dc.execTimeoutMs)
        val op = withContext(Dispatchers.IO) { prepare(owner, repo, name) }
        if (op !is Op) return SandboxAnswer(true, op as String)
        return run(owner, repo, sessionId, op, "exec", "`${firstLine(command)}`", { put("command", command) }) { t ->
            val r = t.exec(DeployEndpoint(op.target), op.identity, command, timeoutMs)
            val tail = r.output.takeLast(OUTPUT_TAIL_CHARS)
            val noStatus = !r.timedOut && r.exitCode == null
            val outcome = when {
                r.timedOut -> "timed out after ${timeoutMs / 1000} s"
                noStatus -> "ended without an exit status" + (r.signal?.let { " (signal $it)" } ?: "")
                else -> "exit ${r.exitCode}"
            }
            Outcome(
                answer = SandboxAnswer(false, buildJsonObject {
                    put("target", op.target.name)
                    put("exitCode", r.exitCode ?: -1)
                    put("output", tail)
                    put("truncated", r.outputTruncated || tail.length < r.output.length)
                    put("durationMs", r.durationMs)
                    put("timedOut", r.timedOut)
                    if (r.timedOut) put("note", "Stopped after ${timeoutMs / 1000} s: the connection was closed, but the remote process may still be running.")
                    else if (noStatus) put("note", "The remote command ended without an exit status" +
                        (r.signal?.let { " (killed by signal $it)" } ?: "") + ".")
                }.toString()),
                ok = !r.timedOut && r.exitCode == 0,
                text = "`${firstLine(command)}`: $outcome in ${seconds(r.durationMs)}",
                logOutcome = outcome,
                payload = payload("exec", op.target, r.exitCode, r.timedOut, r.durationMs, r.output, r.outputTruncated, null) { put("command", command) },
            )
        }
    }

    /** `factory_deploy_copy`: uploads one regular file of the chat's working directory to [dest] on target [name]. */
    suspend fun copy(owner: UserRecord, repo: String, sessionId: UUID, name: String, path: String, dest: String): SandboxAnswer {
        if (!DEST_RE.matches(dest) || dest.startsWith("-")) {
            return SandboxAnswer(true, "dest must be a remote path of letters, digits and . _ / @ % + = : , ~ - (no spaces or shell " +
                "characters, at most 4096 characters, not starting with '-')")
        }
        val local = withContext(Dispatchers.IO) { localFile(owner, repo, path) }
        if (local is String) return SandboxAnswer(true, local)
        local as LocalFile
        val op = withContext(Dispatchers.IO) { prepare(owner, repo, name) }
        if (op !is Op) return SandboxAnswer(true, op as String)
        val upload = DeployUpload(local.real.fileName.toString(), local.size, local.mode) { Files.newInputStream(local.real, LinkOption.NOFOLLOW_LINKS) }
        return run(owner, repo, sessionId, op, "copy", "copy ${local.display} → $dest", { put("path", local.display); put("dest", dest) }) { t ->
            val started = System.nanoTime()
            val bytes = t.copy(DeployEndpoint(op.target), op.identity, upload, dest, dc.execTimeoutMs)
            val ms = (System.nanoTime() - started) / 1_000_000
            Outcome(
                answer = SandboxAnswer(false, buildJsonObject {
                    put("target", op.target.name); put("dest", dest); put("bytes", bytes); put("durationMs", ms)
                }.toString()),
                ok = true,
                text = "copy ${local.display} → $dest: ${Attachments.humanSize(bytes)} in ${seconds(ms)}",
                logOutcome = "copied $bytes bytes",
                payload = payload("copy", op.target, null, false, ms, "", false, null) {
                    put("path", local.display); put("dest", dest); put("bytes", bytes)
                },
            )
        }
    }

    private class LocalFile(val real: Path, val display: String, val size: Long, val mode: Int)

    /** Resolves [given] inside the chat's working directory (symlinks resolved): a [LocalFile] or a refusal. */
    private fun localFile(owner: UserRecord, repo: String, given: String): Any {
        if (given.isBlank()) return "path is required"
        val outside = "path must be a file inside your working directory"
        val root = runCatching { chatDir(owner, repo).toRealPath() }.getOrElse { return "Your working directory does not exist yet" }
        val candidate = try {
            Paths.get(given).let { if (it.isAbsolute) it else root.resolve(it) }
        } catch (e: InvalidPathException) {
            return outside
        }
        val real = try {
            candidate.toRealPath()
        } catch (e: NoSuchFileException) {
            return "No such file: ${given.take(200)}"
        } catch (e: Exception) {
            return outside
        }
        if (!real.startsWith(root)) return outside
        if (Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) return "${given.take(200)} is a directory; pack it into an archive first (e.g. tar czf app.tar.gz dir/) and copy that"
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) return outside
        val name = real.fileName.toString()
        if (name.any { it.isISOControl() }) return "The file's name contains control characters; rename it first"
        val size = Files.size(real)
        if (size > dc.maxCopyBytes) return "The file is larger than ${Attachments.humanSize(dc.maxCopyBytes)} (FACTORY_DEPLOY_MAX_COPY_MB)"
        val mode = runCatching { Files.getPosixFilePermissions(real, LinkOption.NOFOLLOW_LINKS) }.getOrNull()?.let(::modeOf) ?: 0b110_100_100
        return LocalFile(real, root.relativize(real).toString(), size, mode)
    }

    private class Outcome(val answer: SandboxAnswer, val ok: Boolean, val text: String, val logOutcome: String, val payload: JsonObject)

    /**
     * Runs one operation on [op]'s target in a job on the manager's scope (one at a time per target). The job records it
     * in [sessionId]'s transcript before the answer is returned, so a dropped tool call neither aborts nor loses it.
     */
    private suspend fun run(
        owner: UserRecord, repo: String, sessionId: UUID, op: Op, kind: String, label: String,
        details: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit, block: (DeployTransport) -> Outcome,
    ): SandboxAnswer {
        val t = transport ?: return SandboxAnswer(true, offReason)
        val lock = lockKey(repo, op.target.name)
        if (!busy.add(lock)) return SandboxAnswer(true, busyMessage(op.target.name))
        val result = CompletableDeferred<SandboxAnswer>()
        // ATOMIC: the job runs (and frees the lock) even when the scope is cancelled before it starts.
        scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            val started = System.nanoTime()
            try {
                val outcome = try {
                    block(t)
                } catch (e: Exception) {
                    failed(op.target, kind, label, details, e, (System.nanoTime() - started) / 1_000_000)
                }
                runCatching {
                    publishSystem(sessionId, buildJsonObject {
                        put("level", if (outcome.ok) "info" else "warn")
                        put("text", "Deploy [${op.target.name}] ${outcome.text}")
                        put("deploy", outcome.payload)
                    })
                }.onFailure { log.warn("Recording a deploy {} on {}/{} failed: {}", kind, repo, op.target.name, it.message) }
                log.info("Deploy {} on {}/{} by {}: {} in {} ms", kind, repo, op.target.name, owner.username, outcome.logOutcome,
                    (System.nanoTime() - started) / 1_000_000)
                result.complete(outcome.answer)
            } finally {
                busy.remove(lock)
                result.complete(SandboxAnswer(true, "The deploy operation could not be run: the server is shutting down."))
            }
        }
        return result.await()
    }

    /** The outcome of an operation that failed before or while it ran (connection, host key, authentication, I/O). */
    private fun failed(
        target: DeployTarget, kind: String, label: String, details: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit, e: Exception, ms: Long,
    ): Outcome {
        val (stage, msg) = when (e) {
            is DeployException -> e.stage to (e.message ?: e.stage)
            is DeployTimeout -> "timeout" to (e.message ?: "timed out")
            else -> "error" to (e.message ?: e.javaClass.simpleName)
        }
        val what = when (stage) {
            "connect" -> "could not connect: $msg"
            "host_key", "auth" -> msg
            else -> "failed: $msg"
        }
        return Outcome(
            answer = SandboxAnswer(true, "Deploy $kind on ${target.name} failed: $what"),
            ok = false,
            text = "$label: $what",
            logOutcome = "error ($stage)",
            payload = payload(kind, target, null, stage == "timeout", ms, "", false, what, details),
        )
    }

    private fun payload(
        kind: String, t: DeployTarget, exitCode: Int?, timedOut: Boolean, ms: Long, output: String, truncated: Boolean, error: String?,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) = buildJsonObject {
        put("kind", kind); put("target", t.name); put("host", t.host); put("port", t.port); put("user", t.user)
        extra()
        put("exitCode", exitCode); put("timedOut", timedOut); put("durationMs", ms)
        put("output", output); put("outputTruncated", truncated)
        if (error == null) put("error", JsonNull) else put("error", error)
    }

    private fun firstLine(command: String) = command.trim().lineSequence().first().take(200)
    private fun seconds(ms: Long) = "%.1f s".format(Locale.ROOT, ms / 1000.0)

    /** Server shutdown: closes every open connection. */
    fun shutdown() {
        runCatching { transport?.close() }
    }

    companion object {
        val TOOLS = setOf("factory_deploy_targets", "factory_deploy_exec", "factory_deploy_copy")
        const val MAX_COMMAND_CHARS = 32_768
        /** How much of a command's output the agent gets back (the end of it). */
        const val OUTPUT_TAIL_CHARS = 20_000
        val DEST_RE = Regex("^[A-Za-z0-9._/@%+=:,~-]{1,4096}$")

        fun modeOf(perms: Set<PosixFilePermission>): Int = perms.sumOf { p ->
            when (p) {
                PosixFilePermission.OWNER_READ -> 0b100_000_000
                PosixFilePermission.OWNER_WRITE -> 0b010_000_000
                PosixFilePermission.OWNER_EXECUTE -> 0b001_000_000
                PosixFilePermission.GROUP_READ -> 0b000_100_000
                PosixFilePermission.GROUP_WRITE -> 0b000_010_000
                PosixFilePermission.GROUP_EXECUTE -> 0b000_001_000
                PosixFilePermission.OTHERS_READ -> 0b000_000_100
                PosixFilePermission.OTHERS_WRITE -> 0b000_000_010
                PosixFilePermission.OTHERS_EXECUTE -> 0b000_000_001
            }.toInt()
        }
    }
}
