package dk.egernet.factory.db

import dk.egernet.factory.AgentSessionDto
import dk.egernet.factory.AppJson
import dk.egernet.factory.EventDto
import dk.egernet.factory.FindingDto
import dk.egernet.factory.OwnerDto
import dk.egernet.factory.RunDto
import dk.egernet.factory.RunStatus
import dk.egernet.factory.SettingsDto
import dk.egernet.factory.SoftwareRequestDto
import dk.egernet.factory.StageDto
import dk.egernet.factory.UserDto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import java.sql.ResultSet
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val ISO_MILLIS = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)

/** ISO-8601 UTC with exactly three fraction digits, so timestamps also compare correctly as strings. */
fun Instant.iso(): String = ISO_MILLIS.format(this)

// ---------------------------------------------------------------- users

data class UserRecord(
    val id: UUID, val username: String, val displayName: String, val isAdmin: Boolean,
    val githubLogin: String?, val githubTokenEnc: ByteArray?,
    val claudeCredKind: String?, val claudeCredEnc: ByteArray?,
    val totpEnabled: Boolean = false, val totpSecretEnc: ByteArray? = null, val totpPendingEnc: ByteArray? = null,
    val totpLastStep: Long? = null, val totpFailures: Int = 0, val totpLockedUntil: Instant? = null,
    val githubTokenKind: String? = null, val githubTokenScopes: List<String>? = null,
    val imageProvider: String? = null, val imageModel: String? = null, val imageKeyEnc: ByteArray? = null,
) {
    fun toDto() = UserDto(
        id = id.toString(), username = username, displayName = displayName, isAdmin = isAdmin,
        githubLogin = githubLogin, hasGithubToken = githubTokenEnc != null,
        hasClaudeCredential = claudeCredEnc != null, claudeCredentialKind = if (claudeCredEnc != null) claudeCredKind else null,
        twoFactorEnabled = totpEnabled,
        twoFactorLockedUntil = totpLockedUntil?.takeIf { it.isAfter(Instant.now()) }?.iso(),
        githubTokenKind = if (githubTokenEnc != null) githubTokenKind else null,
        githubTokenScopes = if (githubTokenEnc != null) githubTokenScopes else null,
        imageProvider = if (imageKeyEnc != null) imageProvider else null,
        imageModel = if (imageKeyEnc != null) imageModel else null,
    )
    fun owner() = OwnerDto(username, displayName)
    override fun toString() = "UserRecord($username)"
}

class UserStore(private val db: Db) {
    private fun map(rs: ResultSet) = UserRecord(
        rs.uuid("id"), rs.getString("username"), rs.getString("display_name"), rs.getBoolean("is_admin"),
        rs.getString("github_login"), rs.getBytes("github_token_enc"),
        rs.getString("claude_cred_kind"), rs.getBytes("claude_cred_enc"),
        rs.getBoolean("totp_enabled"), rs.getBytes("totp_secret_enc"), rs.getBytes("totp_pending_enc"),
        rs.getLong("totp_last_step").let { if (rs.wasNull()) null else it }, rs.getInt("totp_failures"),
        rs.instantOrNull("totp_locked_until"),
        rs.getString("github_token_kind"),
        rs.getString("github_token_scopes")?.let { s -> s.split(',').filter { it.isNotEmpty() } },
        rs.getString("image_provider"), rs.getString("image_model"), rs.getBytes("image_key_enc"),
    )

    fun byId(id: UUID) = db.queryOne("SELECT * FROM users WHERE id = ?", id, map = ::map)
    fun byUsername(u: String) = db.queryOne("SELECT * FROM users WHERE username = ?", u, map = ::map)
    fun all() = db.query("SELECT * FROM users ORDER BY username", map = ::map)

    /** Create or refresh the row for a user who just logged in. */
    fun upsertOnLogin(username: String, displayName: String, isAdmin: Boolean): UserRecord {
        db.update(
            """INSERT INTO users(id, username, display_name, is_admin, last_login_at) VALUES (?, ?, ?, ?, now())
               ON CONFLICT (username) DO UPDATE SET display_name = EXCLUDED.display_name,
                 is_admin = EXCLUDED.is_admin, last_login_at = now()""",
            UUID.randomUUID(), username, displayName, isAdmin,
        )
        return byUsername(username)!!
    }

    /** [scopes]: classic token scopes (null = unknown, e.g. fine-grained); stored as a comma list. Not secret. */
    fun setGithub(id: UUID, login: String?, tokenEnc: ByteArray?, kind: String? = null, scopes: List<String>? = null) = db.update(
        "UPDATE users SET github_login = ?, github_token_enc = ?, github_token_kind = ?, github_token_scopes = ? WHERE id = ?",
        login, tokenEnc, kind, scopes?.joinToString(","), id,
    )

    /** Replaces (or with nulls removes) the Claude credential; the recorded limit windows belonged to the old one and go too. */
    fun setClaude(id: UUID, kind: String?, enc: ByteArray?): Int = db.tx { c ->
        val n = c.prepare("UPDATE users SET claude_cred_kind = ?, claude_cred_enc = ? WHERE id = ?", arrayOf<Any?>(kind, enc, id)).use { it.executeUpdate() }
        c.prepare("DELETE FROM claude_usage_windows WHERE user_id = ?", arrayOf<Any?>(id)).use { it.executeUpdate() }
        n
    }

    /** Replaces (or with nulls removes) the image provider setting; [keyEnc] is the encrypted API key. */
    fun setImageProvider(id: UUID, provider: String?, model: String?, keyEnc: ByteArray?) = db.update(
        "UPDATE users SET image_provider = ?, image_model = ?, image_key_enc = ? WHERE id = ?", provider, model, keyEnc, id,
    )

    /**
     * Replaces the stored image credential only if the row still holds [oldEnc] for [provider] (compare-and-set, so a
     * re-login or removal made meanwhile wins). Returns whether it changed.
     */
    fun replaceImageKey(id: UUID, provider: String, oldEnc: ByteArray, newEnc: ByteArray): Boolean = db.update(
        "UPDATE users SET image_key_enc = ? WHERE id = ? AND image_provider = ? AND image_key_enc = ?", newEnc, id, provider, oldEnc,
    ) > 0

    fun setTotpPending(id: UUID, enc: ByteArray?) = db.update("UPDATE users SET totp_pending_enc = ? WHERE id = ?", enc, id)

    fun confirmTotp(id: UUID, secretEnc: ByteArray, step: Long) = db.update(
        """UPDATE users SET totp_enabled = TRUE, totp_secret_enc = ?, totp_pending_enc = NULL, totp_last_step = ?,
           totp_failures = 0, totp_locked_until = NULL WHERE id = ?""", secretEnc, step, id,
    )

    /** Atomically records an accepted time step; false if that step (or a later one) was already used. */
    fun acceptTotpStep(id: UUID, step: Long): Boolean = db.update(
        "UPDATE users SET totp_last_step = ?, totp_failures = 0 WHERE id = ? AND (totp_last_step IS NULL OR totp_last_step < ?)",
        step, id, step,
    ) == 1

    fun resetTotpFailures(id: UUID) = db.update("UPDATE users SET totp_failures = 0 WHERE id = ?", id)

    /** Counts a failure; locks for [lockMinutes] when [maxFailures] is reached. Returns the lock end if now locked. */
    fun recordTotpFailure(id: UUID, maxFailures: Int, lockMinutes: Long): Instant? {
        db.update("UPDATE users SET totp_failures = totp_failures + 1 WHERE id = ?", id)
        val until = Instant.now().plus(lockMinutes, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS)
        return until.takeIf {
            db.update("UPDATE users SET totp_failures = 0, totp_locked_until = ? WHERE id = ? AND totp_failures >= ?", until, id, maxFailures) == 1
        }
    }

    fun resetTotp(id: UUID) = db.update(
        """UPDATE users SET totp_enabled = FALSE, totp_secret_enc = NULL, totp_pending_enc = NULL, totp_last_step = NULL,
           totp_failures = 0, totp_locked_until = NULL WHERE id = ?""", id,
    )
}

class TwoFactorStore(private val db: Db) {
    fun replaceRecoveryCodes(userId: UUID, hashes: List<String>) = db.tx { c ->
        c.prepare("DELETE FROM recovery_codes WHERE user_id = ?", arrayOf<Any?>(userId)).use { it.executeUpdate() }
        for (h in hashes) {
            c.prepare("INSERT INTO recovery_codes(id, user_id, code_hash) VALUES (?, ?, ?)", arrayOf<Any?>(UUID.randomUUID(), userId, h))
                .use { it.executeUpdate() }
        }
    }

    /** Marks a matching unused code as used. Single-use is enforced by the conditional update. */
    fun consumeRecoveryCode(userId: UUID, hash: String): Boolean {
        val rows = db.query("SELECT id, code_hash FROM recovery_codes WHERE user_id = ? AND used_at IS NULL", userId) {
            it.uuid("id") to it.getString("code_hash")
        }
        var match: UUID? = null
        for ((id, h) in rows) if (java.security.MessageDigest.isEqual(h.toByteArray(), hash.toByteArray())) match = id
        if (match == null) return false
        return db.update("UPDATE recovery_codes SET used_at = now() WHERE id = ? AND used_at IS NULL", match) == 1
    }

    fun deleteRecoveryCodes(userId: UUID) = db.update("DELETE FROM recovery_codes WHERE user_id = ?", userId)

    fun audit(userId: UUID?, username: String?, event: String, detail: String? = null, remote: String? = null) =
        db.update("INSERT INTO auth_audit(user_id, username, event, detail, remote) VALUES (?, ?, ?, ?, ?)", userId, username, event, detail, remote)

    fun auditEvents(userId: UUID): List<String> = db.query("SELECT event FROM auth_audit WHERE user_id = ? ORDER BY id", userId) { it.getString("event") }
}

// ---------------------------------------------------------------- web sessions

data class WebSession(val userId: UUID, val stage: String)

class WebSessionStore(private val db: Db) {
    /** [stage]: `totp` / `enroll` (pending second factor, short-lived) or `full`. */
    fun create(userId: UUID, cookieValue: String, stage: String) {
        val ttl = if (stage == "full") Instant.now().plus(14, ChronoUnit.DAYS) else Instant.now().plus(10, ChronoUnit.MINUTES)
        db.update(
            "INSERT INTO web_sessions(id, user_id, stage, expires_at) VALUES (?, ?, ?, ?)",
            dk.egernet.factory.security.Tokens.sha256Hex(cookieValue), userId, stage, ttl,
        )
    }

    fun find(cookieValue: String): WebSession? = db.queryOne(
        "SELECT user_id, stage FROM web_sessions WHERE id = ? AND expires_at > now()",
        dk.egernet.factory.security.Tokens.sha256Hex(cookieValue),
    ) { WebSession(it.uuid("user_id"), it.getString("stage")) }

    fun deleteAllFor(userId: UUID) = db.update("DELETE FROM web_sessions WHERE user_id = ?", userId)

    fun delete(cookieValue: String) =
        db.update("DELETE FROM web_sessions WHERE id = ?", dk.egernet.factory.security.Tokens.sha256Hex(cookieValue))

    fun purgeExpired() = db.update("DELETE FROM web_sessions WHERE expires_at <= now()")
}

// ---------------------------------------------------------------- agent sessions

/** `context_repos` as stored: comma-separated repository names. */
internal fun splitRepos(s: String?): List<String> = s?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
internal fun joinRepos(repos: Collection<String>): String = repos.distinct().joinToString(",")

data class SessionRecord(
    val id: UUID, val repo: String, val kind: String, val model: String, val status: String,
    val ownerId: UUID, val ownerUsername: String, val ownerDisplayName: String,
    val title: String?, val runId: UUID?, val parentSessionId: UUID?, val issueNumber: Int?,
    val claudeSessionId: String?, val costUsd: Double?, val numTurns: Int?, val lastActivity: String?,
    val createdAt: Instant, val updatedAt: Instant, val endedAt: Instant?,
    /** Member repositories of a workspace checked out for this session (docs/ARCHITECTURE.md §4 "Visibility"). */
    val contextRepos: List<String> = emptyList(),
) {
    fun toDto(live: Boolean) = AgentSessionDto(
        id = id.toString(), repo = repo, kind = kind, model = model, status = status,
        owner = OwnerDto(ownerUsername, ownerDisplayName), title = title, runId = runId?.toString(),
        parentSessionId = parentSessionId?.toString(), issueNumber = issueNumber,
        createdAt = createdAt.iso(), updatedAt = updatedAt.iso(), endedAt = endedAt?.iso(),
        costUsd = costUsd, numTurns = numTurns, lastActivity = lastActivity, live = live,
    )
}

class SessionStore(private val db: Db) {
    private val select = """SELECT s.*, u.username AS owner_username, u.display_name AS owner_display_name
        FROM agent_sessions s JOIN users u ON u.id = s.owner_id"""

    private fun map(rs: ResultSet) = SessionRecord(
        rs.uuid("id"), rs.getString("repo"), rs.getString("kind"), rs.getString("model"), rs.getString("status"),
        rs.uuid("owner_id"), rs.getString("owner_username"), rs.getString("owner_display_name"),
        rs.getString("title"), rs.uuidOrNull("run_id"), rs.uuidOrNull("parent_session_id"), rs.intOrNull("issue_number"),
        rs.getString("claude_session_id"), rs.doubleOrNull("cost_usd"), rs.intOrNull("num_turns"), rs.getString("last_activity"),
        rs.instant("created_at"), rs.instant("updated_at"), rs.instantOrNull("ended_at"),
        splitRepos(rs.getString("context_repos")),
    )

    fun create(
        repo: String, kind: String, model: String, status: String, ownerId: UUID, title: String?,
        runId: UUID? = null, parentSessionId: UUID? = null, issueNumber: Int? = null, contextRepos: List<String> = emptyList(),
    ): SessionRecord {
        val id = UUID.randomUUID()
        db.update(
            """INSERT INTO agent_sessions(id, repo, kind, model, status, owner_id, title, run_id, parent_session_id, issue_number, context_repos)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            id, repo, kind, model, status, ownerId, title, runId, parentSessionId, issueNumber, joinRepos(contextRepos),
        )
        return get(id)!!
    }

    /** Adds [repos] to the session's recorded context repositories (a chat's checkouts, as a union). */
    fun addContextRepos(id: UUID, repos: Collection<String>) {
        if (repos.isEmpty()) return
        db.tx { c ->
            val current = c.prepare("SELECT context_repos FROM agent_sessions WHERE id = ? FOR UPDATE", arrayOf<Any?>(id))
                .use { ps -> ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null } } ?: return@tx
            val merged = (splitRepos(current) + repos).distinct()
            if (merged.size != splitRepos(current).size) {
                c.prepare("UPDATE agent_sessions SET context_repos = ? WHERE id = ?", arrayOf<Any?>(joinRepos(merged), id)).use { it.executeUpdate() }
            }
        }
    }

    fun get(id: UUID) = db.queryOne("$select WHERE s.id = ?", id, map = ::map)

    /** Deletes a chat session (events and attachments cascade). False when there is no such chat. */
    fun delete(id: UUID): Boolean = db.update("DELETE FROM agent_sessions WHERE id = ? AND kind = 'chat'", id) == 1

    fun list(repo: String?, kind: String?, activeOnly: Boolean): List<SessionRecord> {
        val where = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        if (repo != null) { where += "s.repo = ?"; params += repo }
        if (kind != null) { where += "s.kind = ?"; params += kind }
        if (activeOnly) where += "s.status IN ('starting','running','idle')"
        val w = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        return db.query("$select $w ORDER BY s.created_at DESC LIMIT 500", *params.toTypedArray(), map = ::map)
    }

    fun byRun(runId: UUID) = db.query("$select WHERE s.run_id = ? ORDER BY s.created_at", runId, map = ::map)

    fun setStatus(id: UUID, status: String, ended: Boolean = false) = db.update(
        "UPDATE agent_sessions SET status = ?, updated_at = now(), ended_at = CASE WHEN ? THEN now() ELSE ended_at END WHERE id = ?",
        status, ended, id,
    )

    fun reopen(id: UUID) = db.update("UPDATE agent_sessions SET ended_at = NULL, updated_at = now() WHERE id = ?", id)

    fun setClaudeSessionId(id: UUID, cid: String) =
        db.update("UPDATE agent_sessions SET claude_session_id = ?, updated_at = now() WHERE id = ?", cid, id)

    fun clearClaudeSessionId(id: UUID) =
        db.update("UPDATE agent_sessions SET claude_session_id = NULL, updated_at = now() WHERE id = ?", id)

    fun setUsage(id: UUID, cost: Double?, turns: Int?) =
        db.update("UPDATE agent_sessions SET cost_usd = ?, num_turns = ?, updated_at = now() WHERE id = ?", cost, turns, id)

    fun setActivity(id: UUID, activity: String?) =
        db.update("UPDATE agent_sessions SET last_activity = ?, updated_at = now() WHERE id = ?", activity, id)

    fun setTitle(id: UUID, title: String) =
        db.update("UPDATE agent_sessions SET title = ?, updated_at = now() WHERE id = ? AND title IS NULL", title, id)

    fun countActiveByRepo(): Map<String, Int> = db.query(
        "SELECT repo, count(*) AS n FROM agent_sessions WHERE status IN ('starting','running') GROUP BY repo",
    ) { it.getString("repo") to it.getInt("n") }.toMap()

    /** Agents doing work right now: a chat mid-turn or a pipeline agent starting/running (all repos). */
    fun countBusy(): Int =
        db.queryOne("SELECT count(*) AS n FROM agent_sessions WHERE status IN ('starting','running')") { it.getInt("n") } ?: 0

    /**
     * On startup no process can be alive: chats become idle (resumable), and so do the advisors of runs that still need
     * a human; every other unfinished session (stage agents, advisors of ended runs) fails.
     */
    fun recoverAfterRestart() {
        db.update("UPDATE agent_sessions SET status = 'idle', updated_at = now() WHERE kind = 'chat' AND status IN ('starting','running')")
        db.update(
            """UPDATE agent_sessions s SET status = 'idle', updated_at = now() FROM runs r
               WHERE s.kind = 'advisor' AND s.status IN ('starting','running','idle') AND r.id = s.run_id AND r.status = 'needs_human'""",
        )
        db.update(
            """UPDATE agent_sessions s SET status = 'failed', updated_at = now(), ended_at = now()
               WHERE s.kind <> 'chat' AND s.status IN ('starting','running','idle')
                 AND NOT (s.kind = 'advisor' AND EXISTS (SELECT 1 FROM runs r WHERE r.id = s.run_id AND r.status = 'needs_human'))""",
        )
    }
}

// ---------------------------------------------------------------- events

class EventStore(private val db: Db) {
    private val counters = ConcurrentHashMap<UUID, AtomicLong>()

    private fun counter(sessionId: UUID) = counters.computeIfAbsent(sessionId) {
        AtomicLong(db.queryOne("SELECT COALESCE(max(seq), 0) AS m FROM events WHERE session_id = ?", sessionId) { it.getLong("m") } ?: 0)
    }

    @Synchronized
    fun append(sessionId: UUID, type: String, payload: JsonElement): EventDto {
        val seq = counter(sessionId).incrementAndGet()
        val ts = Instant.now()
        db.update(
            "INSERT INTO events(session_id, seq, ts, type, payload) VALUES (?, ?, ?, ?, ?)",
            sessionId, seq, ts, type, JsonbParam(payload.toString()),
        )
        return EventDto(seq, sessionId.toString(), ts.iso(), type, payload)
    }

    /** The `result` text of the session's last `claude` event of type `result`, or null. */
    fun lastResultText(sessionId: UUID): String? = db.queryOne(
        """SELECT payload->>'result' AS r FROM events WHERE session_id = ? AND type = 'claude' AND payload->>'type' = 'result'
           ORDER BY seq DESC LIMIT 1""",
        sessionId,
    ) { it.getString("r") }

    fun list(sessionId: UUID, after: Long, limit: Int): List<EventDto> = db.query(
        "SELECT seq, ts, type, payload::text AS p FROM events WHERE session_id = ? AND seq > ? ORDER BY seq LIMIT ?",
        sessionId, after, limit,
    ) { EventDto(it.getLong("seq"), sessionId.toString(), it.instant("ts").iso(), it.getString("type"), AppJson.parseToJsonElement(it.getString("p"))) }
}

// ---------------------------------------------------------------- runs & stages

data class StageRecord(
    val id: UUID, val runId: UUID, val role: String, val round: Int, val model: String, val status: String, val verdict: String?,
    val summary: String?, val findings: List<FindingDto>, val sessionId: UUID?, val startedAt: Instant?, val endedAt: Instant?,
    /** A warning the server recorded on the stage (docs/ARCHITECTURE.md §6 "Screenshots"); null: none. */
    val warning: String? = null,
) {
    fun toDto(screenshots: List<dk.egernet.factory.ScreenshotDto> = emptyList(), images: List<dk.egernet.factory.GeneratedImageDto> = emptyList()) = StageDto(
        id.toString(), role, round, model, status, verdict, summary, findings, sessionId?.toString(), startedAt?.iso(), endedAt?.iso(),
        screenshots, images, warning,
    )
}

data class RunRecord(
    val id: UUID, val repo: String, val issueNumber: Int, val issueTitle: String, val issueKind: String,
    val status: String, val round: Int, val maxRounds: Int, val branch: String?, val prNumber: Int?, val prUrl: String?,
    val ownerId: UUID, val ownerUsername: String, val ownerDisplayName: String, val startedBySessionId: UUID?,
    val error: String?, val createdAt: Instant, val updatedAt: Instant, val endedAt: Instant?,
    /** JSON of [dk.egernet.factory.RunSandboxDto], set when the run prepared its workspace. */
    val sandboxJson: String? = null,
    /** Set for a release run (docs/ARCHITECTURE.md §6 "Releases"). */
    val releaseId: UUID? = null,
    /** The branch the run starts from and merges into; null = the repository's default branch. */
    val baseBranch: String? = null,
    /** The run's advisor session (docs/ARCHITECTURE.md §6 "Conversation at needs human"). */
    val advisorSessionId: UUID? = null,
    /** The worktree HEAD the last completed review saw; set when the conversation starts. */
    val reviewedSha: String? = null,
    /** [dk.egernet.factory.ReviewState]. */
    val reviewState: String = dk.egernet.factory.ReviewState.NONE,
    val followUpFrom: Int? = null,
    /** The failed run this one continues from (docs/ARCHITECTURE.md §6 "Retrying a run"). */
    val continuesRunId: UUID? = null,
    /** The spec the builder got: written by this run's spec-writer or reused from [continuesRunId]. */
    val spec: String? = null,
    /** The builder's last final message (for a step retry, whose reviewers need it). */
    val builderSummary: String? = null,
    /** [dk.egernet.factory.FailureKind] of a failed run. */
    val failureKind: String? = null,
    /** [dk.egernet.factory.FailedStep] that failed after the builder finished. */
    val failedStep: String? = null,
    /** A workspace run: the member repository it changes and its directory (docs/ARCHITECTURE.md §6 "Workspace runs"). */
    val targetRepo: String? = null,
    val targetDir: String? = null,
    /** Member repositories checked out for this run (docs/ARCHITECTURE.md §4 "Visibility"). */
    val contextRepos: List<String> = emptyList(),
    /** JSON of [dk.egernet.factory.RunChecksDto] (without the waiting times): the checks last evaluated before a merge. */
    val checksJson: String? = null,
    /** While `waiting_for_checks`: since when, and when it stops waiting (docs/ARCHITECTURE.md §6 "Checks before a merge"). */
    val checksWaitingSince: Instant? = null,
    val checksDeadline: Instant? = null,
    /** Who approved the merge that waits for its checks: a username, or null for the approver agent. */
    val mergeApprovedBy: String? = null,
) {
    /** [checksJson] decoded; null when there is none (or it cannot be read). */
    val checks: dk.egernet.factory.RunChecksDto?
        get() = checksJson?.let { runCatching { AppJson.decodeFromString(dk.egernet.factory.RunChecksDto.serializer(), it) }.getOrNull() }
}

class RunStore(private val db: Db) {
    private val screenshots = ScreenshotStore(db)
    private val images = ImageStore(db)
    private val findingsSer = ListSerializer(FindingDto.serializer())
    private val select = """SELECT r.*, u.username AS owner_username, u.display_name AS owner_display_name
        FROM runs r JOIN users u ON u.id = r.owner_id"""

    private fun map(rs: ResultSet) = RunRecord(
        rs.uuid("id"), rs.getString("repo"), rs.getInt("issue_number"), rs.getString("issue_title"), rs.getString("issue_kind"),
        rs.getString("status"), rs.getInt("round"), rs.getInt("max_rounds"), rs.getString("branch"), rs.intOrNull("pr_number"),
        rs.getString("pr_url"), rs.uuid("owner_id"), rs.getString("owner_username"), rs.getString("owner_display_name"),
        rs.uuidOrNull("started_by_session_id"), rs.getString("error"), rs.instant("created_at"), rs.instant("updated_at"),
        rs.instantOrNull("ended_at"), rs.getString("sandbox"), rs.uuidOrNull("release_id"), rs.getString("base_branch"),
        rs.uuidOrNull("advisor_session_id"), rs.getString("reviewed_sha"), rs.getString("review_state"), rs.intOrNull("follow_up_from"),
        rs.uuidOrNull("continues_run_id"), rs.getString("spec"), rs.getString("builder_summary"),
        rs.getString("failure_kind"), rs.getString("failed_step"),
        rs.getString("target_repo"), rs.getString("target_dir"), splitRepos(rs.getString("context_repos")),
        rs.getString("checks"), rs.instantOrNull("checks_waiting_since"), rs.instantOrNull("checks_deadline"),
        rs.getString("merge_approved_by"),
    )

    private fun mapStage(rs: ResultSet) = StageRecord(
        rs.uuid("id"), rs.uuid("run_id"), rs.getString("role"), rs.getInt("round"), rs.getString("model"), rs.getString("status"),
        rs.getString("verdict"), rs.getString("summary"),
        AppJson.decodeFromString(findingsSer, rs.getString("findings_text")),
        rs.uuidOrNull("session_id"), rs.instantOrNull("started_at"), rs.instantOrNull("ended_at"), rs.getString("warning"),
    )

    /**
     * Creates a run unless the issue already has an active one (checked under a row lock on the repo/issue).
     * A release run has [releaseId] and [baseBranch] (`release/<name>`).
     */
    fun createIfNoActive(
        repo: String, issueNumber: Int, issueTitle: String, issueKind: String, maxRounds: Int, ownerId: UUID, startedBy: UUID?,
        releaseId: UUID? = null, baseBranch: String? = null, continuesRunId: UUID? = null, spec: String? = null,
        targetRepo: String? = null, targetDir: String? = null, contextRepos: List<String> = emptyList(),
    ): RunRecord? {
        val id = UUID.randomUUID()
        val inserted = db.tx { c ->
            c.prepare("SELECT pg_advisory_xact_lock(hashtext(?))", arrayOf<Any?>("$repo#$issueNumber")).use { it.executeQuery().close() }
            val active = c.prepare(
                "SELECT count(*) FROM runs WHERE repo = ? AND issue_number = ? AND status IN ('queued','spec','building','reviewing','approving','waiting_for_checks','needs_human')",
                arrayOf<Any?>(repo, issueNumber),
            ).use { ps -> ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
            if (active > 0) return@tx false
            c.prepare(
                """INSERT INTO runs(id, repo, issue_number, issue_title, issue_kind, status, max_rounds, owner_id, started_by_session_id,
                     release_id, base_branch, continues_run_id, spec, target_repo, target_dir, context_repos)
                   VALUES (?, ?, ?, ?, ?, 'queued', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                arrayOf<Any?>(
                    id, repo, issueNumber, issueTitle, issueKind, maxRounds, ownerId, startedBy, releaseId, baseBranch, continuesRunId, spec,
                    targetRepo, targetDir, joinRepos(contextRepos),
                ),
            ).use { it.executeUpdate() }
            true
        }
        return if (inserted) get(id) else null
    }

    fun get(id: UUID) = db.queryOne("$select WHERE r.id = ?", id, map = ::map)

    /**
     * A failed (or cancelled) run becomes active again for a step retry: `queued`, error, end time and failure cleared.
     * Refused (false) when the run is not failed/cancelled any more or the issue has another active run; checked under
     * the same lock as [createIfNoActive].
     */
    fun reactivate(id: UUID): Boolean = db.tx { c ->
        val run = get(id) ?: return@tx false
        c.prepare("SELECT pg_advisory_xact_lock(hashtext(?))", arrayOf<Any?>("${run.repo}#${run.issueNumber}")).use { it.executeQuery().close() }
        val active = c.prepare(
            "SELECT count(*) FROM runs WHERE repo = ? AND issue_number = ? AND id <> ? AND status IN ('queued','spec','building','reviewing','approving','waiting_for_checks','needs_human')",
            arrayOf<Any?>(run.repo, run.issueNumber, id),
        ).use { ps -> ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
        if (active > 0) return@tx false
        c.prepare(
            """UPDATE runs SET status = 'queued', error = NULL, ended_at = NULL, failure_kind = NULL, failed_step = NULL, updated_at = now()
               WHERE id = ? AND status IN ('failed','cancelled')""",
            arrayOf<Any?>(id),
        ).use { it.executeUpdate() } == 1
    }

    /** The newest run that continues [id], if any. */
    fun continuedBy(id: UUID): UUID? = db.queryOne(
        "SELECT id FROM runs WHERE continues_run_id = ? ORDER BY created_at DESC LIMIT 1", id,
    ) { it.uuid("id") }

    fun setSpec(id: UUID, spec: String) = db.update("UPDATE runs SET spec = ?, updated_at = now() WHERE id = ?", spec, id)
    fun setBuilderSummary(id: UUID, summary: String) =
        db.update("UPDATE runs SET builder_summary = ?, updated_at = now() WHERE id = ?", summary, id)
    fun setFailure(id: UUID, kind: String?, step: String?) =
        db.update("UPDATE runs SET failure_kind = ?, failed_step = ?, updated_at = now() WHERE id = ?", kind, step, id)

    /** For a step retry: the stages a failure skipped (reviewers, approver) are pending again. */
    fun reopenSkippedStages(runId: UUID) = db.update(
        "UPDATE stages SET status = 'pending', ended_at = NULL WHERE run_id = ? AND status = 'skipped' AND role <> 'spec-writer'", runId,
    )

    /** [statuses]: when not null, only runs in one of these statuses (`active` = [RunStatus.ACTIVE]). */
    fun list(repo: String?, activeOnly: Boolean, limit: Int = 200, statuses: Set<String>? = null): List<RunRecord> {
        val where = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        if (repo != null) { where += "r.repo = ?"; params += repo }
        if (activeOnly) where += "r.status IN ('queued','spec','building','reviewing','approving','waiting_for_checks','needs_human')"
        if (statuses != null) {
            if (statuses.isEmpty()) return emptyList()
            where += "r.status IN (" + statuses.joinToString(",") { "?" } + ")"
            params.addAll(statuses)
        }
        val w = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        params += limit
        return db.query("$select $w ORDER BY r.created_at DESC LIMIT ?", *params.toTypedArray(), map = ::map)
    }

    fun activeRunFor(repo: String, issueNumber: Int): UUID? = db.queryOne(
        "SELECT id FROM runs WHERE repo = ? AND issue_number = ? AND status IN ('queued','spec','building','reviewing','approving','waiting_for_checks','needs_human') ORDER BY created_at DESC LIMIT 1",
        repo, issueNumber,
    ) { it.uuid("id") }

    /** Active runs (including `needs_human`) of a release. */
    fun activeByRelease(releaseId: UUID): List<RunRecord> = db.query(
        "$select WHERE r.release_id = ? AND r.status IN ('queued','spec','building','reviewing','approving','waiting_for_checks','needs_human') ORDER BY r.created_at",
        releaseId, map = ::map,
    )

    /** Pull request numbers of earlier runs of the issue that ended without merging (their PRs may still be open). */
    fun earlierUnmergedPulls(repo: String, issueNumber: Int, exceptRunId: UUID, targetRepo: String? = null): List<Int> = db.query(
        """SELECT DISTINCT pr_number FROM runs WHERE repo = ? AND issue_number = ? AND id <> ? AND pr_number IS NOT NULL
           AND status IN ('failed','cancelled') AND target_repo IS NOT DISTINCT FROM CAST(? AS text) ORDER BY pr_number""",
        repo, issueNumber, exceptRunId, targetRepo,
    ) { it.getInt("pr_number") }

    fun activeRunsByIssue(repo: String): Map<Int, UUID> = db.query(
        "SELECT issue_number, id FROM runs WHERE repo = ? AND status IN ('queued','spec','building','reviewing','approving','waiting_for_checks','needs_human')",
        repo,
    ) { it.getInt("issue_number") to it.uuid("id") }.toMap()

    fun countActiveByRepo(): Map<String, Int> = db.query(
        "SELECT repo, count(*) AS n FROM runs WHERE status IN ('queued','spec','building','reviewing','approving','waiting_for_checks','needs_human') GROUP BY repo",
    ) { it.getString("repo") to it.getInt("n") }.toMap()

    /**
     * Runs a restart would fail (see [failUnfinishedAfterRestart]): every active status except `needs_human` and
     * `waiting_for_checks`, which survive a restart.
     */
    fun countInProgress(): Int =
        db.queryOne("SELECT count(*) AS n FROM runs WHERE status IN ('queued','spec','building','reviewing','approving')") { it.getInt("n") } ?: 0

    fun setStatus(id: UUID, status: String, error: String? = null, clearError: Boolean = false) = db.update(
        """UPDATE runs SET status = ?, error = CASE WHEN ? THEN NULL ELSE COALESCE(?, error) END, updated_at = now(),
           ended_at = CASE WHEN ? THEN now() ELSE ended_at END WHERE id = ?""",
        status, clearError, error, status in RunStatus.TERMINAL, id,
    )

    fun setStageRound(runId: UUID, role: String, round: Int) = db.update(
        "UPDATE stages SET round = ? WHERE run_id = ? AND role = ? AND status = 'pending'", round, runId, role,
    )

    /**
     * Marks the run as changed (stage updates included) so `updatedAt` orders the versions clients see;
     * strictly increasing at millisecond resolution, the precision of the API's timestamps.
     */
    fun touch(id: UUID) = db.update(
        "UPDATE runs SET updated_at = GREATEST(now(), date_trunc('milliseconds', updated_at) + interval '1 millisecond') WHERE id = ?", id,
    )

    fun setSandbox(id: UUID, info: dk.egernet.factory.RunSandboxDto) = db.update(
        "UPDATE runs SET sandbox = ?, updated_at = now() WHERE id = ?",
        JsonbParam(AppJson.encodeToString(dk.egernet.factory.RunSandboxDto.serializer(), info)), id,
    )

    /** Records the checks last evaluated before a merge (the waiting times live in their own columns). */
    fun setChecks(id: UUID, checks: dk.egernet.factory.RunChecksDto) = db.update(
        "UPDATE runs SET checks = ?, updated_at = now() WHERE id = ?",
        JsonbParam(AppJson.encodeToString(dk.egernet.factory.RunChecksDto.serializer(), checks.copy(waitingSince = null, deadline = null))), id,
    )

    /**
     * When Agern first saw [sha] as the head of the run's pull request: recorded as [now] the first time (a new head
     * starts again), so the settle period survives restarts. [force] records [now] even for the same head (a push).
     */
    fun headSeenAt(id: UUID, sha: String, now: Instant, force: Boolean = false): Instant {
        db.update(
            """UPDATE runs SET checks_head_sha = ?, checks_head_seen_at = ? WHERE id = ?
               AND (? OR checks_head_sha IS DISTINCT FROM ? OR checks_head_seen_at IS NULL)""",
            sha, now, id, force, sha,
        )
        return db.queryOne("SELECT checks_head_seen_at FROM runs WHERE id = ?", id) { it.instantOrNull("checks_head_seen_at") } ?: now
    }

    /** Who approved the merge (null: the approver agent); kept for the merge comment after a wait or a restart. */
    fun setMergeApprovedBy(id: UUID, username: String?) =
        db.update("UPDATE runs SET merge_approved_by = ? WHERE id = ?", username, id)

    /**
     * The run starts waiting for its checks: status `waiting_for_checks` (error cleared), since [since] until [deadline].
     * False when the run is not `approving` (or already waiting) any more, e.g. cancelled meanwhile.
     */
    fun startWaitingForChecks(id: UUID, since: Instant, deadline: Instant): Boolean = db.update(
        """UPDATE runs SET status = 'waiting_for_checks', error = NULL, checks_waiting_since = ?, checks_deadline = ?, updated_at = now()
           WHERE id = ? AND status IN ('approving','waiting_for_checks')""",
        since, deadline, id,
    ) == 1

    /** The waiting times are cleared once the run stops waiting. */
    fun clearChecksWait(id: UUID) =
        db.update("UPDATE runs SET checks_waiting_since = NULL, checks_deadline = NULL WHERE id = ?", id)

    /** Runs in `waiting_for_checks` (resumed after a restart). */
    fun waitingForChecks(): List<RunRecord> = db.query("$select WHERE r.status = 'waiting_for_checks' ORDER BY r.created_at", map = ::map)

    fun setRound(id: UUID, round: Int) = db.update("UPDATE runs SET round = ?, updated_at = now() WHERE id = ?", round, id)
    fun setContextRepos(id: UUID, repos: List<String>) =
        db.update("UPDATE runs SET context_repos = ?, updated_at = now() WHERE id = ?", joinRepos(repos), id)
    fun setBranch(id: UUID, branch: String) = db.update("UPDATE runs SET branch = ?, updated_at = now() WHERE id = ?", branch, id)
    fun setPr(id: UUID, number: Int, url: String) =
        db.update("UPDATE runs SET pr_number = ?, pr_url = ?, updated_at = now() WHERE id = ?", number, url, id)

    /**
     * Binds [sessionId] as the run's advisor unless it already has one (concurrent first messages); records
     * [headSha] as the reviewed commit when none is recorded yet. Returns the run's advisor session id afterwards.
     */
    fun bindAdvisor(id: UUID, sessionId: UUID, headSha: String?): UUID? {
        db.update(
            """UPDATE runs SET advisor_session_id = ?, reviewed_sha = COALESCE(reviewed_sha, ?), updated_at = now()
               WHERE id = ? AND advisor_session_id IS NULL""",
            sessionId, headSha, id,
        )
        return get(id)?.advisorSessionId
    }

    fun setReviewState(id: UUID, state: String) =
        db.update("UPDATE runs SET review_state = ?, updated_at = now() WHERE id = ?", state, id)

    /** Moves the review state from [from] to [to]; false when it was not [from] (another caller won). */
    fun casReviewState(id: UUID, from: String, to: String): Boolean =
        db.update("UPDATE runs SET review_state = ?, updated_at = now() WHERE id = ? AND review_state = ?", to, id, from) == 1

    fun setReviewedSha(id: UUID, sha: String) =
        db.update("UPDATE runs SET reviewed_sha = ?, updated_at = now() WHERE id = ?", sha, id)

    /** A follow-up round starts: the run's round becomes [round], and the first one is remembered. */
    fun startFollowUp(id: UUID, round: Int) = db.update(
        "UPDATE runs SET round = ?, follow_up_from = COALESCE(follow_up_from, ?), updated_at = now() WHERE id = ?", round, round, id,
    )

    fun setError(id: UUID, error: String) = db.update("UPDATE runs SET error = ?, updated_at = now() WHERE id = ?", error, id)

    fun createStage(runId: UUID, role: String, round: Int, model: String): StageRecord {
        val id = UUID.randomUUID()
        db.update("INSERT INTO stages(id, run_id, role, round, model, status) VALUES (?, ?, ?, ?, ?, 'pending')", id, runId, role, round, model)
        return stage(id)!!
    }

    fun stage(id: UUID) = db.queryOne("SELECT *, findings::text AS findings_text FROM stages WHERE id = ?", id, map = ::mapStage)
    fun stages(runId: UUID) = db.query(
        """SELECT *, findings::text AS findings_text FROM stages WHERE run_id = ?
           ORDER BY round, CASE role WHEN 'spec-writer' THEN 0 WHEN 'builder' THEN 1 WHEN 'security' THEN 2 WHEN 'ux' THEN 3
             WHEN 'ui-design' THEN 4 WHEN 'code' THEN 5 ELSE 6 END, ordinal""", runId, map = ::mapStage,
    )

    fun startStage(id: UUID, sessionId: UUID, model: String) = db.update(
        "UPDATE stages SET status = 'running', session_id = ?, model = ?, started_at = now() WHERE id = ?", sessionId, model, id,
    )

    fun finishStage(id: UUID, status: String, verdict: String?, summary: String?, findings: List<FindingDto>) = db.update(
        "UPDATE stages SET status = ?, verdict = ?, summary = ?, findings = ?, ended_at = now() WHERE id = ?",
        status, verdict, summary, JsonbParam(AppJson.encodeToString(findingsSer, findings)), id,
    )

    /** Records a warning on a stage (docs/ARCHITECTURE.md §6 "Screenshots"); it never changes the stage's status. */
    fun setStageWarning(id: UUID, warning: String?) = db.update("UPDATE stages SET warning = ? WHERE id = ?", warning, id)

    /** Pending → skipped, running → failed. Used when a run ends abnormally. */
    fun closeOpenStages(runId: UUID) {
        db.update("UPDATE stages SET status = 'skipped', ended_at = now() WHERE run_id = ? AND status = 'pending'", runId)
        db.update("UPDATE stages SET status = 'failed', ended_at = now() WHERE run_id = ? AND status = 'running'", runId)
    }

    /** Stage screenshots are read with one query per run, never one per stage. */
    fun toDto(r: RunRecord): RunDto {
        val shots = screenshots.forRun(r.id).groupBy({ it.stageId }, { it.toDto() })
        val imgs = images.forRun(r.id).groupBy({ it.sessionId }, { it.toDto() })
        val stageRows = stages(r.id)
        return RunDto(
            id = r.id.toString(), repo = r.repo, issueNumber = r.issueNumber, issueTitle = r.issueTitle, issueKind = r.issueKind,
            status = r.status, round = r.round, maxRounds = r.maxRounds, branch = r.branch, prNumber = r.prNumber, prUrl = r.prUrl,
            owner = OwnerDto(r.ownerUsername, r.ownerDisplayName), startedBySessionId = r.startedBySessionId?.toString(),
            error = r.error, createdAt = r.createdAt.iso(), updatedAt = r.updatedAt.iso(), endedAt = r.endedAt?.iso(),
            stages = stageRows.map { it.toDto(shots[it.id].orEmpty(), it.sessionId?.let { s -> imgs[s] }.orEmpty()) },
            sandbox = r.sandboxJson?.let { runCatching { AppJson.decodeFromString(dk.egernet.factory.RunSandboxDto.serializer(), it) }.getOrNull() },
            releaseId = r.releaseId?.toString(), baseBranch = r.baseBranch,
            conversation = dk.egernet.factory.RunConversationDto(r.advisorSessionId?.toString(), r.reviewState, r.followUpFrom),
            continuesRunId = r.continuesRunId?.toString(), continuedByRunId = continuedBy(r.id)?.toString(),
            failureKind = r.failureKind, failedStep = r.failedStep,
            targetRepo = r.targetRepo, targetDirectory = r.targetDir,
            checks = r.checks?.let { c ->
                if (r.status == RunStatus.WAITING_FOR_CHECKS) c.copy(waitingSince = r.checksWaitingSince?.iso(), deadline = r.checksDeadline?.iso())
                else c.copy(waitingSince = null, deadline = null)
            },
            warnings = stageRows.filter { it.warning != null }.groupBy { it.round }.toSortedMap()
                .map { (round, list) -> dk.egernet.factory.RunWarningDto(round, list.map { it.role }, list.first().warning!!) },
        )
    }

    /**
     * v1: runs cannot survive a restart. needs_human runs have no process and stay as they are; runs waiting for checks
     * are resumed ([dk.egernet.factory.pipeline.Orchestrator.resumeChecksWaits]).
     */
    fun failUnfinishedAfterRestart(): List<UUID> {
        val ids = db.query("SELECT id FROM runs WHERE status IN ('queued','spec','building','reviewing','approving')") { it.uuid("id") }
        for (id in ids) {
            setStatus(id, RunStatus.FAILED, "Server restarted while the run was in progress; retry it to continue or start over.")
            setFailure(id, dk.egernet.factory.FailureKind.RESTART, null)
            closeOpenStages(id)
        }
        return ids
    }

    /**
     * After a restart, for runs that survived it (`needs_human`): an interrupted follow-up review must be started again
     * (`running` → `required`), and no stage of theirs is left open. Returns the runs whose review was interrupted.
     */
    fun recoverConversationsAfterRestart(): List<UUID> {
        val ids = db.query("SELECT id FROM runs WHERE review_state = 'running'") { it.uuid("id") }
        db.update("UPDATE runs SET review_state = 'required', updated_at = now() WHERE review_state = 'running'")
        db.query("SELECT id FROM runs WHERE status = 'needs_human'") { it.uuid("id") }.forEach { closeOpenStages(it) }
        return ids
    }
}

// ---------------------------------------------------------------- settings

class SettingsStore(private val db: Db) {
    @Volatile private var cached: SettingsDto? = null

    fun get(): SettingsDto = cached ?: (
        db.queryOne("SELECT value::text AS v FROM settings WHERE id = 1") { AppJson.decodeFromString(SettingsDto.serializer(), it.getString("v")) }
            ?: SettingsDto()
        ).also { cached = it }

    fun put(s: SettingsDto): SettingsDto {
        db.update(
            "INSERT INTO settings(id, value) VALUES (1, ?) ON CONFLICT (id) DO UPDATE SET value = EXCLUDED.value",
            JsonbParam(AppJson.encodeToString(SettingsDto.serializer(), s)),
        )
        cached = s
        return s
    }
}

// ---------------------------------------------------------------- software requests

class SoftwareRequestStore(private val db: Db) {
    private fun map(rs: ResultSet) = SoftwareRequestDto(
        rs.uuid("id").toString(), rs.getString("name"), rs.getString("reason"), rs.getString("repo"), rs.getString("requested_by"),
        rs.uuidOrNull("session_id")?.toString(), rs.getString("status"), rs.getString("note"),
        rs.instant("created_at").iso(), rs.instantOrNull("resolved_at")?.iso(),
    )

    fun create(name: String, reason: String, repo: String, requestedBy: String, sessionId: UUID?): SoftwareRequestDto {
        val id = UUID.randomUUID()
        db.update(
            "INSERT INTO software_requests(id, name, reason, repo, requested_by, session_id) VALUES (?, ?, ?, ?, ?, ?)",
            id, name, reason, repo, requestedBy, sessionId,
        )
        return get(id)!!
    }

    fun get(id: UUID) = db.queryOne("SELECT * FROM software_requests WHERE id = ?", id, map = ::map)
    fun all() = db.query("SELECT * FROM software_requests ORDER BY created_at DESC", map = ::map)

    fun resolve(id: UUID, status: String, note: String?): SoftwareRequestDto? {
        db.update(
            "UPDATE software_requests SET status = ?, note = ?, resolved_at = CASE WHEN ? = 'pending' THEN NULL ELSE now() END WHERE id = ?",
            status, note, status, id,
        )
        return get(id)
    }
}
