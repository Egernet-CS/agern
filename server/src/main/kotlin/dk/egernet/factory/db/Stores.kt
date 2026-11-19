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
) {
    fun toDto() = UserDto(
        id = id.toString(), username = username, displayName = displayName, isAdmin = isAdmin,
        githubLogin = githubLogin, hasGithubToken = githubTokenEnc != null,
        hasClaudeCredential = claudeCredEnc != null, claudeCredentialKind = if (claudeCredEnc != null) claudeCredKind else null,
        twoFactorEnabled = totpEnabled,
        twoFactorLockedUntil = totpLockedUntil?.takeIf { it.isAfter(Instant.now()) }?.iso(),
        githubTokenKind = if (githubTokenEnc != null) githubTokenKind else null,
        githubTokenScopes = if (githubTokenEnc != null) githubTokenScopes else null,
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

    fun setClaude(id: UUID, kind: String?, enc: ByteArray?) =
        db.update("UPDATE users SET claude_cred_kind = ?, claude_cred_enc = ? WHERE id = ?", kind, enc, id)

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

data class SessionRecord(
    val id: UUID, val repo: String, val kind: String, val model: String, val status: String,
    val ownerId: UUID, val ownerUsername: String, val ownerDisplayName: String,
    val title: String?, val runId: UUID?, val parentSessionId: UUID?, val issueNumber: Int?,
    val claudeSessionId: String?, val costUsd: Double?, val numTurns: Int?, val lastActivity: String?,
    val createdAt: Instant, val updatedAt: Instant, val endedAt: Instant?,
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
    )

    fun create(
        repo: String, kind: String, model: String, status: String, ownerId: UUID, title: String?,
        runId: UUID? = null, parentSessionId: UUID? = null, issueNumber: Int? = null,
    ): SessionRecord {
        val id = UUID.randomUUID()
        db.update(
            """INSERT INTO agent_sessions(id, repo, kind, model, status, owner_id, title, run_id, parent_session_id, issue_number)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            id, repo, kind, model, status, ownerId, title, runId, parentSessionId, issueNumber,
        )
        return get(id)!!
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

    /** On startup no process can be alive: chats become idle (resumable), stage agents failed. */
    fun recoverAfterRestart() {
        db.update("UPDATE agent_sessions SET status = 'idle', updated_at = now() WHERE kind = 'chat' AND status IN ('starting','running')")
        db.update(
            "UPDATE agent_sessions SET status = 'failed', updated_at = now(), ended_at = now() " +
                "WHERE kind <> 'chat' AND status IN ('starting','running','idle')",
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

    fun list(sessionId: UUID, after: Long, limit: Int): List<EventDto> = db.query(
        "SELECT seq, ts, type, payload::text AS p FROM events WHERE session_id = ? AND seq > ? ORDER BY seq LIMIT ?",
        sessionId, after, limit,
    ) { EventDto(it.getLong("seq"), sessionId.toString(), it.instant("ts").iso(), it.getString("type"), AppJson.parseToJsonElement(it.getString("p"))) }
}

// ---------------------------------------------------------------- runs & stages

data class StageRecord(
    val id: UUID, val runId: UUID, val role: String, val round: Int, val model: String, val status: String, val verdict: String?,
    val summary: String?, val findings: List<FindingDto>, val sessionId: UUID?, val startedAt: Instant?, val endedAt: Instant?,
) {
    fun toDto() = StageDto(
        id.toString(), role, round, model, status, verdict, summary, findings, sessionId?.toString(), startedAt?.iso(), endedAt?.iso(),
    )
}

data class RunRecord(
    val id: UUID, val repo: String, val issueNumber: Int, val issueTitle: String, val issueKind: String,
    val status: String, val round: Int, val maxRounds: Int, val branch: String?, val prNumber: Int?, val prUrl: String?,
    val ownerId: UUID, val ownerUsername: String, val ownerDisplayName: String, val startedBySessionId: UUID?,
    val error: String?, val createdAt: Instant, val updatedAt: Instant, val endedAt: Instant?,
)

class RunStore(private val db: Db) {
    private val findingsSer = ListSerializer(FindingDto.serializer())
    private val select = """SELECT r.*, u.username AS owner_username, u.display_name AS owner_display_name
        FROM runs r JOIN users u ON u.id = r.owner_id"""

    private fun map(rs: ResultSet) = RunRecord(
        rs.uuid("id"), rs.getString("repo"), rs.getInt("issue_number"), rs.getString("issue_title"), rs.getString("issue_kind"),
        rs.getString("status"), rs.getInt("round"), rs.getInt("max_rounds"), rs.getString("branch"), rs.intOrNull("pr_number"),
        rs.getString("pr_url"), rs.uuid("owner_id"), rs.getString("owner_username"), rs.getString("owner_display_name"),
        rs.uuidOrNull("started_by_session_id"), rs.getString("error"), rs.instant("created_at"), rs.instant("updated_at"),
        rs.instantOrNull("ended_at"),
    )

    private fun mapStage(rs: ResultSet) = StageRecord(
        rs.uuid("id"), rs.uuid("run_id"), rs.getString("role"), rs.getInt("round"), rs.getString("model"), rs.getString("status"),
        rs.getString("verdict"), rs.getString("summary"),
        AppJson.decodeFromString(findingsSer, rs.getString("findings_text")),
        rs.uuidOrNull("session_id"), rs.instantOrNull("started_at"), rs.instantOrNull("ended_at"),
    )

    /** Creates a run unless the issue already has an active one (checked under a row lock on the repo/issue). */
    fun createIfNoActive(
        repo: String, issueNumber: Int, issueTitle: String, issueKind: String, maxRounds: Int, ownerId: UUID, startedBy: UUID?,
    ): RunRecord? {
        val id = UUID.randomUUID()
        val inserted = db.tx { c ->
            c.prepare("SELECT pg_advisory_xact_lock(hashtext(?))", arrayOf<Any?>("$repo#$issueNumber")).use { it.executeQuery().close() }
            val active = c.prepare(
                "SELECT count(*) FROM runs WHERE repo = ? AND issue_number = ? AND status IN ('queued','spec','building','reviewing','approving','needs_human')",
                arrayOf<Any?>(repo, issueNumber),
            ).use { ps -> ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
            if (active > 0) return@tx false
            c.prepare(
                """INSERT INTO runs(id, repo, issue_number, issue_title, issue_kind, status, max_rounds, owner_id, started_by_session_id)
                   VALUES (?, ?, ?, ?, ?, 'queued', ?, ?, ?)""",
                arrayOf<Any?>(id, repo, issueNumber, issueTitle, issueKind, maxRounds, ownerId, startedBy),
            ).use { it.executeUpdate() }
            true
        }
        return if (inserted) get(id) else null
    }

    fun get(id: UUID) = db.queryOne("$select WHERE r.id = ?", id, map = ::map)

    /** [statuses]: when not null, only runs in one of these statuses (`active` = [RunStatus.ACTIVE]). */
    fun list(repo: String?, activeOnly: Boolean, limit: Int = 200, statuses: Set<String>? = null): List<RunRecord> {
        val where = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        if (repo != null) { where += "r.repo = ?"; params += repo }
        if (activeOnly) where += "r.status IN ('queued','spec','building','reviewing','approving','needs_human')"
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
        "SELECT id FROM runs WHERE repo = ? AND issue_number = ? AND status IN ('queued','spec','building','reviewing','approving','needs_human') ORDER BY created_at DESC LIMIT 1",
        repo, issueNumber,
    ) { it.uuid("id") }

    fun activeRunsByIssue(repo: String): Map<Int, UUID> = db.query(
        "SELECT issue_number, id FROM runs WHERE repo = ? AND status IN ('queued','spec','building','reviewing','approving','needs_human')",
        repo,
    ) { it.getInt("issue_number") to it.uuid("id") }.toMap()

    fun countActiveByRepo(): Map<String, Int> = db.query(
        "SELECT repo, count(*) AS n FROM runs WHERE status IN ('queued','spec','building','reviewing','approving','needs_human') GROUP BY repo",
    ) { it.getString("repo") to it.getInt("n") }.toMap()

    /**
     * Runs a restart would fail (see [failUnfinishedAfterRestart]): every active status except `needs_human`,
     * which survives a restart.
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

    fun setRound(id: UUID, round: Int) = db.update("UPDATE runs SET round = ?, updated_at = now() WHERE id = ?", round, id)
    fun setBranch(id: UUID, branch: String) = db.update("UPDATE runs SET branch = ?, updated_at = now() WHERE id = ?", branch, id)
    fun setPr(id: UUID, number: Int, url: String) =
        db.update("UPDATE runs SET pr_number = ?, pr_url = ?, updated_at = now() WHERE id = ?", number, url, id)

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

    /** Pending → skipped, running → failed. Used when a run ends abnormally. */
    fun closeOpenStages(runId: UUID) {
        db.update("UPDATE stages SET status = 'skipped', ended_at = now() WHERE run_id = ? AND status = 'pending'", runId)
        db.update("UPDATE stages SET status = 'failed', ended_at = now() WHERE run_id = ? AND status = 'running'", runId)
    }

    fun toDto(r: RunRecord) = RunDto(
        id = r.id.toString(), repo = r.repo, issueNumber = r.issueNumber, issueTitle = r.issueTitle, issueKind = r.issueKind,
        status = r.status, round = r.round, maxRounds = r.maxRounds, branch = r.branch, prNumber = r.prNumber, prUrl = r.prUrl,
        owner = OwnerDto(r.ownerUsername, r.ownerDisplayName), startedBySessionId = r.startedBySessionId?.toString(),
        error = r.error, createdAt = r.createdAt.iso(), updatedAt = r.updatedAt.iso(), endedAt = r.endedAt?.iso(),
        stages = stages(r.id).map { it.toDto() },
    )

    /** v1: runs cannot survive a restart. needs_human runs have no process and stay as they are. */
    fun failUnfinishedAfterRestart(): List<UUID> {
        val ids = db.query("SELECT id FROM runs WHERE status IN ('queued','spec','building','reviewing','approving')") { it.uuid("id") }
        for (id in ids) {
            setStatus(id, RunStatus.FAILED, "Server restarted while the run was in progress; start a new run.")
            closeOpenStages(id)
        }
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
