package dk.egernet.factory.db

import dk.egernet.factory.IssueReleaseDto
import dk.egernet.factory.OwnerDto
import dk.egernet.factory.ReleaseDto
import dk.egernet.factory.ReleaseIssueDto
import dk.egernet.factory.ReleaseIssueState
import dk.egernet.factory.ReleaseRepoDto
import dk.egernet.factory.ReleaseChecksDto
import dk.egernet.factory.ReleaseChecksState
import dk.egernet.factory.ReleaseMergeState
import dk.egernet.factory.ReleaseMode
import dk.egernet.factory.ReleasePauseDto
import dk.egernet.factory.ReleaseStartFailureDto
import dk.egernet.factory.ReleaseStatus
import dk.egernet.factory.conflict
import dk.egernet.factory.forbidden
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

data class ReleaseIssueRecord(
    val number: Int, val title: String, val kind: String,
    val state: String = ReleaseIssueState.WAITING, val runId: UUID? = null, val note: String? = null,
    /** The pull request of [runId] into the release branch. */
    val prNumber: Int? = null, val prUrl: String? = null,
    /** `continue` or `step`: a waiting issue retried from [runId] (docs/ARCHITECTURE.md §6 "Retrying a run"). */
    val retryMode: String? = null,
    /** The member repository and directory [runId] targets; null without a run or for the project repository. */
    val targetRepo: String? = null, val targetDir: String? = null,
    /** The network wait of [runId] (with the run's status), if it has one. */
    val runWait: RunWait? = null, val runStatus: String? = null,
)

/**
 * A member repository in which Agern created `release/<name>` for a release (docs/ARCHITECTURE.md §6 "Several
 * repositories"), with that repository's release pull request: the same columns as the project repository's on
 * `releases`. [baseBranch]: the member's base branch, where its release pull request merges.
 */
data class ReleaseRepoRecord(
    val repo: String, val directory: String, val submodule: Boolean, val baseBranch: String,
    val branchCreated: Boolean = true, val branchDeleted: Boolean = false,
    val prNumber: Int? = null, val prUrl: String? = null, val prBodySha: String? = null,
    val mergeState: String = ReleaseMergeState.UNKNOWN, val mergeMethod: String? = null,
    val checksState: String = ReleaseChecksState.UNKNOWN, val checksSha: String? = null, val checksDetail: String? = null,
    val checksFailureSha: String? = null, val mergedAt: Instant? = null, val mergeSha: String? = null,
) {
    val merged: Boolean get() = mergedAt != null
}

data class ReleaseRecord(
    val id: UUID, val repo: String, val name: String, val description: String, val status: String,
    val createdBy: UUID, val createdByUsername: String, val createdByDisplayName: String,
    val createdAt: Instant, val updatedAt: Instant, val issues: List<ReleaseIssueRecord>,
    /** Who scheduled or started the release; its runs use this user's credentials. */
    val ownerId: UUID? = null, val ownerUsername: String? = null, val ownerDisplayName: String? = null,
    val branch: String? = null, val startedAt: Instant? = null, val endedAt: Instant? = null,
    val pauseReason: String? = null, val pauseNote: String? = null, val resumeAt: Instant? = null,
    val scheduledAt: Instant? = null,
    val waitingForId: UUID? = null, val waitingForName: String? = null,
    val startFailedAt: Instant? = null, val startFailedOwnerId: UUID? = null,
    val startFailedOwnerUsername: String? = null, val startFailedOwnerDisplayName: String? = null,
    val startFailedReason: String? = null,
    val prNumber: Int? = null, val prUrl: String? = null, val prBodySha: String? = null, val targetBranch: String? = null,
    val mergeState: String = ReleaseMergeState.UNKNOWN, val mergeMethod: String? = null, val notice: String? = null,
    val readyAt: Instant? = null, val readyAnnounced: Boolean = false,
    val releasedAt: Instant? = null, val releasedById: UUID? = null, val releasedByUsername: String? = null,
    val releasedByDisplayName: String? = null, val mergeSha: String? = null, val cleanupPending: Boolean = false,
    val mode: String = ReleaseMode.MANUAL, val releasedAutomatically: Boolean = false,
    val checksState: String = ReleaseChecksState.UNKNOWN, val checksSha: String? = null, val checksDetail: String? = null,
    val checksFailureSha: String? = null, val autoMergeFailedSha: String? = null,
    /** The member repositories of the release ([ReleaseRepoRecord]), in the order their branches were created. */
    val repos: List<ReleaseRepoRecord> = emptyList(),
    /** The project repository's pull request was merged on GitHub while member pull requests remained. */
    val prMergedAt: Instant? = null,
    /** The project repository's `release/<name>` was deleted by a cancel or delete. */
    val branchDeleted: Boolean = false,
    /** The first failed start of the current network streak (docs/ARCHITECTURE.md §6 "Transient failures"), and how many. */
    val networkSince: Instant? = null, val networkAttempts: Int = 0,
) {
    val automatic: Boolean get() = mode == ReleaseMode.AUTOMATIC

    /** A release with at least one member repository (docs/ARCHITECTURE.md §6 "Several repositories"). */
    val multiRepo: Boolean get() = repos.isNotEmpty()

    /**
     * The member repositories at least one merged issue was built in, in merge order: the build-order position of
     * each one's first merged issue.
     */
    val touchedRepos: List<ReleaseRepoRecord>
        get() {
            val order = merged.mapNotNull { it.targetRepo }.distinct()
            return order.mapNotNull { t -> repos.firstOrNull { it.repo == t } }
        }

    /**
     * Whether the project repository gets a release pull request: a merged issue was built in it, or a touched member
     * is a submodule (its pointer must move). Always true for a release that is not multi-repository.
     */
    val projectTouched: Boolean
        get() = !multiRepo || merged.any { it.targetRepo == null } || touchedRepos.any { it.submodule }

    /** `ready` and at least one of its release pull requests is merged (docs/ARCHITECTURE.md §6 "Several repositories"). */
    val partlyReleased: Boolean
        get() = status == ReleaseStatus.READY && multiRepo && (repos.any { it.merged } || prMergedAt != null)

    val started: Boolean get() = status in ReleaseStatus.STARTED

    val merged: List<ReleaseIssueRecord> get() = issues.filter { it.state == ReleaseIssueState.MERGED }
    val held: List<ReleaseIssueRecord> get() = issues.filter { it.state == ReleaseIssueState.HELD }
    val skipped: List<ReleaseIssueRecord> get() = issues.filter { it.state == ReleaseIssueState.SKIPPED }
    /** Ready and approvable: something was merged and nothing is held. */
    val complete: Boolean get() = merged.isNotEmpty() && held.isEmpty()

    fun toDto() = ReleaseDto(
        id.toString(), repo, name, description, status, OwnerDto(createdByUsername, createdByDisplayName),
        createdAt.iso(), updatedAt.iso(),
        issues.map {
            ReleaseIssueDto(
                it.number, it.title, it.kind, it.state, it.runId?.toString(), it.note, it.prNumber, it.prUrl, it.retryMode, it.targetRepo, it.targetDir,
                waiting = it.runWait?.takeIf { _ -> it.state == ReleaseIssueState.RUNNING && it.runStatus in WAITABLE_STATUSES }?.toDto(),
            )
        },
        owner = if (ownerId != null && ownerUsername != null) OwnerDto(ownerUsername, ownerDisplayName ?: ownerUsername) else null,
        branch = branch, startedAt = startedAt?.iso(), endedAt = endedAt?.iso(),
        pause = if (status == ReleaseStatus.PAUSED && pauseReason != null) {
            val network = pauseReason == dk.egernet.factory.PauseReason.NETWORK
            ReleasePauseDto(
                pauseReason, pauseNote, resumeAt?.iso(),
                since = networkSince?.takeIf { network }?.iso(), attempt = networkAttempts.takeIf { network && it > 0 },
            )
        } else null,
        scheduledAt = scheduledAt?.iso(),
        waitingFor = if (waitingForId != null && waitingForName != null) IssueReleaseDto(waitingForId.toString(), waitingForName) else null,
        startFailure = if (startFailedAt != null && startFailedReason != null) {
            ReleaseStartFailureDto(
                startFailedAt.iso(),
                OwnerDto(startFailedOwnerUsername ?: "unknown", startFailedOwnerDisplayName ?: startFailedOwnerUsername ?: "unknown"),
                startFailedReason,
            )
        } else null,
        prNumber = prNumber, prUrl = prUrl, targetBranch = targetBranch, mergeState = mergeState, mergeMethod = mergeMethod,
        notice = notice, readyAt = readyAt?.iso(), releasedAt = releasedAt?.iso(),
        releasedBy = if (releasedById != null && releasedByUsername != null) OwnerDto(releasedByUsername, releasedByDisplayName ?: releasedByUsername) else null,
        mergeCommit = mergeSha,
        mode = mode, releasedAutomatically = releasedAutomatically,
        checks = if (prNumber != null || checksSha != null) ReleaseChecksDto(checksState, checksDetail, checksSha) else null,
        repositories = repositoriesDto(),
    )

    /** [dk.egernet.factory.ReleaseDto.repositories]: empty unless multi-repository. */
    private fun repositoriesDto(): List<ReleaseRepoDto> {
        if (!multiRepo) return emptyList()
        val touched = touchedRepos
        val members = touched + repos.filter { r -> touched.none { it.repo == r.repo } }
        val branchName = branch ?: "release/$name"
        val out = members.map { m ->
            ReleaseRepoDto(
                m.repo, m.directory, m.submodule, m.baseBranch, branchName,
                state = when {
                    m.merged -> "merged"
                    m.prNumber != null -> "open"
                    else -> "none"
                },
                prNumber = m.prNumber, prUrl = m.prUrl, mergeState = m.mergeState, mergeMethod = m.mergeMethod,
                checks = if (m.prNumber != null || m.checksSha != null) ReleaseChecksDto(m.checksState, m.checksDetail, m.checksSha) else null,
                mergedAt = m.mergedAt?.iso(), mergeCommit = m.mergeSha,
                issues = merged.filter { it.targetRepo == m.repo }.map { it.number },
            )
        }
        val projectMerged = prMergedAt != null || (status == ReleaseStatus.RELEASED && prNumber != null)
        val project = ReleaseRepoDto(
            repo, null, false, targetBranch, branchName,
            state = when {
                projectMerged -> "merged"
                prNumber != null -> "open"
                else -> "none"
            },
            prNumber = prNumber, prUrl = prUrl, mergeState = mergeState, mergeMethod = mergeMethod,
            checks = if (prNumber != null || checksSha != null) ReleaseChecksDto(checksState, checksDetail, checksSha) else null,
            mergedAt = (prMergedAt ?: releasedAt?.takeIf { projectMerged })?.iso(), mergeCommit = mergeSha,
            issues = merged.filter { it.targetRepo == null }.map { it.number },
        )
        return out + project
    }
}

/** Who acts on a release, for the rules of started releases (docs/ARCHITECTURE.md §6 "Releases", access table). */
data class ReleaseActor(val id: UUID, val isAdmin: Boolean)

/** 403 for an action on a started release that only its owner (and, when [adminAllowed], an admin) may take. */
fun releaseForbidden(ownerUsername: String?, adminAllowed: Boolean) = forbidden(
    "Only ${ownerUsername ?: "the owner"}, who started this release, ${if (adminAllowed) "or an admin " else ""}can do this",
)

/** 409 `release_not_editable` for any change to a released release. */
fun releasedNotEditable(name: String) = conflict("Release \"$name\" is released and kept as history.", "release_not_editable")

/** The issue of a release that a run builds. */
data class ReleaseIssueRef(val releaseId: UUID, val number: Int, val state: String, val runId: UUID?)

/** An issue to put into a release, with the title and kind it has at that moment. */
data class IssueToAssign(val number: Int, val title: String, val kind: String)

/**
 * Releases and their planned issues (docs/ARCHITECTURE.md §6 "Releases"). An issue is in at most one release per repo
 * (primary key of `release_issues`); changes to assignments and order are serialised per repo by an advisory lock.
 */
class ReleaseStore(private val db: Db) {
    private val select = """SELECT r.*, u.username AS created_by_username, u.display_name AS created_by_display_name,
          o.username AS owner_username, o.display_name AS owner_display_name,
          fo.username AS start_failed_owner_username, fo.display_name AS start_failed_owner_display_name,
          w.name AS waiting_for_name, rb.username AS released_by_username, rb.display_name AS released_by_display_name
        FROM releases r JOIN users u ON u.id = r.created_by
        LEFT JOIN users o ON o.id = r.owner_id
        LEFT JOIN users rb ON rb.id = r.released_by
        LEFT JOIN users fo ON fo.id = r.start_failed_owner
        LEFT JOIN releases w ON w.id = r.waiting_for"""

    private fun mapRow(rs: ResultSet) = ReleaseRecord(
        rs.uuid("id"), rs.getString("repo"), rs.getString("name"), rs.getString("description"), rs.getString("status"),
        rs.uuid("created_by"), rs.getString("created_by_username"), rs.getString("created_by_display_name"),
        rs.instant("created_at"), rs.instant("updated_at"), emptyList(),
        ownerId = rs.uuidOrNull("owner_id"), ownerUsername = rs.getString("owner_username"),
        ownerDisplayName = rs.getString("owner_display_name"),
        branch = rs.getString("branch"), startedAt = rs.instantOrNull("started_at"), endedAt = rs.instantOrNull("ended_at"),
        pauseReason = rs.getString("pause_reason"), pauseNote = rs.getString("pause_note"), resumeAt = rs.instantOrNull("resume_at"),
        scheduledAt = rs.instantOrNull("scheduled_at"),
        waitingForId = rs.uuidOrNull("waiting_for"), waitingForName = rs.getString("waiting_for_name"),
        startFailedAt = rs.instantOrNull("start_failed_at"), startFailedOwnerId = rs.uuidOrNull("start_failed_owner"),
        startFailedOwnerUsername = rs.getString("start_failed_owner_username"),
        startFailedOwnerDisplayName = rs.getString("start_failed_owner_display_name"),
        startFailedReason = rs.getString("start_failed_reason"),
        prNumber = rs.intOrNull("pr_number"), prUrl = rs.getString("pr_url"), prBodySha = rs.getString("pr_body_sha"),
        targetBranch = rs.getString("target_branch"), mergeState = rs.getString("merge_state"),
        mergeMethod = rs.getString("merge_method"), notice = rs.getString("notice"),
        readyAt = rs.instantOrNull("ready_at"), readyAnnounced = rs.getBoolean("ready_announced"),
        releasedAt = rs.instantOrNull("released_at"), releasedById = rs.uuidOrNull("released_by"),
        releasedByUsername = rs.getString("released_by_username"), releasedByDisplayName = rs.getString("released_by_display_name"),
        mergeSha = rs.getString("merge_sha"), cleanupPending = rs.getBoolean("cleanup_pending"),
        mode = rs.getString("release_mode"), releasedAutomatically = rs.getBoolean("released_automatically"),
        checksState = rs.getString("checks_state"), checksSha = rs.getString("checks_sha"), checksDetail = rs.getString("checks_detail"),
        checksFailureSha = rs.getString("checks_failure_sha"), autoMergeFailedSha = rs.getString("auto_merge_failed_sha"),
        prMergedAt = rs.instantOrNull("pr_merged_at"), branchDeleted = rs.getBoolean("branch_deleted"),
        networkSince = rs.instantOrNull("network_since"), networkAttempts = rs.getInt("network_attempts"),
    )

    private fun mapRepo(rs: ResultSet) = ReleaseRepoRecord(
        rs.getString("repo"), rs.getString("directory"), rs.getBoolean("submodule"), rs.getString("base_branch"),
        branchCreated = rs.getBoolean("branch_created"), branchDeleted = rs.getBoolean("branch_deleted"),
        prNumber = rs.intOrNull("pr_number"), prUrl = rs.getString("pr_url"), prBodySha = rs.getString("pr_body_sha"),
        mergeState = rs.getString("merge_state"), mergeMethod = rs.getString("merge_method"),
        checksState = rs.getString("checks_state"), checksSha = rs.getString("checks_sha"), checksDetail = rs.getString("checks_detail"),
        checksFailureSha = rs.getString("checks_failure_sha"), mergedAt = rs.instantOrNull("merged_at"), mergeSha = rs.getString("merge_sha"),
    )

    private fun reposOf(ids: List<UUID>): Map<UUID, List<ReleaseRepoRecord>> {
        if (ids.isEmpty()) return emptyMap()
        return db.query(
            "SELECT * FROM release_repos WHERE release_id IN (" + ids.joinToString(",") { "?" } + ") ORDER BY created_at, repo",
            *ids.toTypedArray(),
        ) { it.uuid("release_id") to mapRepo(it) }
            .groupBy({ it.first }, { it.second })
    }

    private fun mapIssue(rs: ResultSet) = ReleaseIssueRecord(
        rs.getInt("issue_number"), rs.getString("issue_title"), rs.getString("issue_kind"),
        rs.getString("state"), rs.uuidOrNull("run_id"), rs.getString("note"),
        rs.intOrNull("pr_number"), rs.getString("pr_url"), rs.getString("retry_mode"),
        rs.getString("target_repo"), rs.getString("target_dir"),
        rs.runWait(), rs.getString("run_status"),
    )

    private fun issuesOf(ids: List<UUID>): Map<UUID, List<ReleaseIssueRecord>> {
        if (ids.isEmpty()) return emptyMap()
        return db.query(
            "SELECT ri.release_id, ri.issue_number, ri.issue_title, ri.issue_kind, ri.state, ri.run_id, ri.note, ri.retry_mode, ru.pr_number, ru.pr_url, " +
                "ru.target_repo, ru.target_dir, ru.status AS run_status, ru.wait_reason, ru.wait_since, ru.wait_next_at, ru.wait_attempts, ru.wait_detail " +
                "FROM release_issues ri LEFT JOIN runs ru ON ru.id = ri.run_id WHERE ri.release_id IN (" +
                ids.joinToString(",") { "?" } + ") ORDER BY ri.position, ri.added_at",
            *ids.toTypedArray(),
        ) { it.uuid("release_id") to mapIssue(it) }
            .groupBy({ it.first }, { it.second })
    }

    private fun withIssues(list: List<ReleaseRecord>): List<ReleaseRecord> {
        val issues = issuesOf(list.map { it.id })
        val repos = reposOf(list.map { it.id })
        return list.map { it.copy(issues = issues[it.id] ?: emptyList(), repos = repos[it.id] ?: emptyList()) }
    }

    /** Runs [block], turning a violation of the unique name index into 409 `release_name_taken`. */
    private fun <T> uniqueName(name: String, block: () -> T): T = try {
        block()
    } catch (e: SQLException) {
        if (e.sqlState == "23505") throw conflict("A release named \"$name\" already exists in this project", "release_name_taken")
        throw e
    }

    fun create(repo: String, name: String, description: String, createdBy: UUID, mode: String = ReleaseMode.MANUAL): ReleaseRecord {
        val id = UUID.randomUUID()
        uniqueName(name) {
            db.update(
                "INSERT INTO releases(id, repo, name, description, status, created_by, release_mode) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, repo, name, description, ReleaseStatus.PLANNING, createdBy, mode,
            )
        }
        return get(id)!!
    }

    fun get(id: UUID): ReleaseRecord? = db.queryOne("$select WHERE r.id = ?", id, map = ::mapRow)?.let { withIssues(listOf(it)).first() }

    /** Newest first. */
    fun list(repo: String): List<ReleaseRecord> =
        withIssues(db.query("$select WHERE r.repo = ? ORDER BY r.created_at DESC, r.id", repo, map = ::mapRow))

    fun byName(repo: String, name: String): ReleaseRecord? =
        db.queryOne("$select WHERE r.repo = ? AND lower(r.name) = lower(?)", repo, name, map = ::mapRow)?.let { withIssues(listOf(it)).first() }

    /** Renames only while planned (`planning` or `scheduled`): a started release keeps its name (its branch is named after it). */
    fun update(id: UUID, name: String, description: String) {
        val n = uniqueName(name) {
            db.update(
                """UPDATE releases SET name = CASE WHEN status IN ('planning', 'scheduled') THEN ? ELSE name END, description = ?,
                   updated_at = GREATEST(now(), date_trunc('milliseconds', updated_at) + interval '1 millisecond')
                   WHERE id = ? AND (status IN ('planning', 'scheduled') OR name = ?)""",
                name, description, id, name,
            )
        }
        if (n == 0 && get(id) != null) throw conflict("The name is fixed once the release is started", "release_name_locked")
    }

    /** Deletes a release in preparation or cancelled; never one that is scheduled (cancel the schedule first), building or paused. */
    fun delete(id: UUID): Boolean = db.update("DELETE FROM releases WHERE id = ? AND status IN ('planning', 'cancelled')", id) > 0

    // ------------------------------------------------------------ building (docs/ARCHITECTURE.md §6 "Releases")

    /**
     * Claims a planned release for building: [ownerId] becomes its owner. [from] is `planning` (start now) or
     * `scheduled` (the scheduler; the owner stays the one who scheduled it). False when it is no longer in [from];
     * 409 `release_already_building` when another release of the project holds the building slot.
     */
    fun claim(id: UUID, ownerId: UUID, branch: String, from: String = ReleaseStatus.PLANNING): Boolean = try {
        db.update(
            """UPDATE releases SET status = 'building', owner_id = ?, branch = ?, started_at = now(), ended_at = NULL,
               pause_reason = NULL, pause_note = NULL, resume_at = NULL, scheduled_at = NULL, waiting_for = NULL
               WHERE id = ? AND status = ?""",
            ownerId, branch, id, from,
        ) == 1
    } catch (e: SQLException) {
        if (e.sqlState == "23505") throw conflict("Another release of this project is already building", "release_already_building")
        throw e
    }

    /**
     * Undoes [claim] after the branch could not be created: back to [before]'s status, owner and scheduled time
     * (`planning` with no owner, or still `scheduled`, so the scheduler records the failed start).
     */
    fun unclaim(id: UUID, before: ReleaseRecord) = db.update(
        """UPDATE releases SET status = ?, owner_id = ?, scheduled_at = ?, branch = NULL, started_at = NULL
           WHERE id = ? AND status = 'building'""",
        before.status, if (before.status == ReleaseStatus.SCHEDULED) before.ownerId else null,
        if (before.status == ReleaseStatus.SCHEDULED) before.scheduledAt else null, id,
    )

    /** The other release of [repo] that holds the building slot, if any. */
    fun activeIn(repo: String): ReleaseRecord? =
        db.queryOne("$select WHERE r.repo = ? AND r.status IN ('building', 'paused')", repo, map = ::mapRow)

    /** `building` → `paused`. False when the release is not building. */
    fun pause(id: UUID, reason: String, note: String?, resumeAt: Instant?): Boolean = db.update(
        "UPDATE releases SET status = 'paused', pause_reason = ?, pause_note = ?, resume_at = ? WHERE id = ? AND status = 'building'",
        reason, note, resumeAt, id,
    ) == 1

    /** `paused` → `building`, clearing the pause. False when the release is not paused. */
    fun resume(id: UUID): Boolean = db.update(
        "UPDATE releases SET status = 'building', pause_reason = NULL, pause_note = NULL, resume_at = NULL WHERE id = ? AND status = 'paused'", id,
    ) == 1

    /** `building`, `paused` or `ready` → `cancelled`. False otherwise. */
    fun cancel(id: UUID): Boolean = db.update(
        """UPDATE releases SET status = 'cancelled', ended_at = now(), pause_reason = NULL, pause_note = NULL, resume_at = NULL,
             network_since = NULL, network_attempts = 0
           WHERE id = ? AND status IN ('building', 'paused', 'ready')""", id,
    ) == 1

    /** A start failed transiently: the network streak began at [since] and has [attempts] failed starts. */
    fun noteNetworkFailure(id: UUID, since: Instant, attempts: Int) = db.update(
        "UPDATE releases SET network_since = ?, network_attempts = ? WHERE id = ?", since, attempts, id,
    )

    /** The network streak ends (a run started, an issue was skipped or held). */
    fun clearNetwork(id: UUID) = db.update(
        "UPDATE releases SET network_since = NULL, network_attempts = 0 WHERE id = ? AND (network_since IS NOT NULL OR network_attempts <> 0)", id,
    )

    // ------------------------------------------------------------ releasing (docs/ARCHITECTURE.md §6 "Releasing")

    /** `building` → `ready` (nothing left to build). The "ready" comment is due again and mergeability unknown. */
    fun markReady(id: UUID): Boolean = db.update(
        """UPDATE releases SET status = 'ready', ready_at = now(), ready_announced = false, merge_state = 'unknown'
           WHERE id = ? AND status = 'building'""", id,
    ) == 1

    /**
     * `ready` → `building` again (an issue was added or retried). Needs the project's building slot: 409
     * `release_already_building` when another release of the project holds it. False when the release is not ready.
     */
    fun reopen(id: UUID): Boolean = try {
        db.update("UPDATE releases SET status = 'building', ready_at = NULL WHERE id = ? AND status = 'ready'", id) == 1
    } catch (e: SQLException) {
        if (e.sqlState == "23505") throw slotTaken()
        throw e
    }

    private fun slotTaken() = conflict(
        "Another release of this project is building; it must finish before this one can build again", "release_already_building",
    )

    // A release pull request lives on `releases` (the project repository's) or on a `release_repos` row (a member's):
    // the setters below take [memberRepo] (null: the project repository) and change exactly the same columns.

    fun setPull(id: UUID, number: Int, url: String, bodySha: String, targetBranch: String, memberRepo: String? = null) =
        if (memberRepo == null) db.update(
            """UPDATE releases SET pr_number = ?, pr_url = ?, pr_body_sha = ?, target_branch = ?, merge_state = 'unknown',
               checks_state = 'unknown', checks_sha = NULL, checks_detail = NULL, checks_head_seen_at = NULL WHERE id = ?""",
            number, url, bodySha, targetBranch, id,
        ) else db.update(
            """UPDATE release_repos SET pr_number = ?, pr_url = ?, pr_body_sha = ?, merge_state = 'unknown',
               checks_state = 'unknown', checks_sha = NULL, checks_detail = NULL, checks_head_seen_at = NULL
               WHERE release_id = ? AND repo = ?""",
            number, url, bodySha, id, memberRepo,
        )

    /** Forgets a release pull request that was closed on GitHub without being merged (the announcement is due again). */
    fun clearPull(id: UUID, memberRepo: String? = null) = if (memberRepo == null) db.update(
        """UPDATE releases SET pr_number = NULL, pr_url = NULL, pr_body_sha = NULL, merge_state = 'unknown', ready_announced = false,
           checks_state = 'unknown', checks_sha = NULL, checks_detail = NULL, checks_head_seen_at = NULL WHERE id = ?""", id,
    ) else db.tx { c ->
        c.prepare(
            """UPDATE release_repos SET pr_number = NULL, pr_url = NULL, pr_body_sha = NULL, merge_state = 'unknown',
               checks_state = 'unknown', checks_sha = NULL, checks_detail = NULL, checks_head_seen_at = NULL
               WHERE release_id = ? AND repo = ?""", arrayOf<Any?>(id, memberRepo),
        ).use { it.executeUpdate() }
        c.prepare("UPDATE releases SET ready_announced = false WHERE id = ?", arrayOf<Any?>(id)).use { it.executeUpdate() }
    }

    /**
     * Sets the release mode while the release is not finished; false when it is `released` or `cancelled` (or gone).
     * Callers hold the release's mutex, so an automatic merge never starts after a switch to manual has returned.
     */
    fun setMode(id: UUID, mode: String): Boolean = db.update(
        "UPDATE releases SET release_mode = ? WHERE id = ? AND status NOT IN ('released', 'cancelled')", mode, id,
    ) == 1

    /** `WHERE` of one pull request's row: the release's, or a member repository's. */
    private fun slot(memberRepo: String?) =
        if (memberRepo == null) "releases" to "id = ?" else "release_repos" to "release_id = ? AND repo = ?"

    private fun slotArgs(id: UUID, memberRepo: String?): Array<Any?> = if (memberRepo == null) arrayOf(id) else arrayOf(id, memberRepo)

    /** The checks on the pull request's head commit [sha] as last read; true when the state or detail changed. */
    fun setChecks(id: UUID, state: String, sha: String?, detail: String?, memberRepo: String? = null): Boolean {
        val (table, where) = slot(memberRepo)
        return db.update(
            """UPDATE $table SET checks_state = ?, checks_sha = ?, checks_detail = ? WHERE $where
               AND (checks_state <> ? OR checks_sha IS DISTINCT FROM ? OR checks_detail IS DISTINCT FROM ?)""",
            state, sha, detail, *slotArgs(id, memberRepo), state, sha, detail,
        ) == 1
    }

    /**
     * When Agern first saw [sha] as the release pull request's head: recorded as [now] the first time (a new head, or a
     * new pull request, starts again), so the settle period survives restarts.
     */
    fun headSeenAt(id: UUID, sha: String, now: Instant, memberRepo: String? = null): Instant {
        val (table, where) = slot(memberRepo)
        db.update(
            """UPDATE $table SET checks_sha = ?, checks_head_seen_at = ? WHERE $where
               AND (checks_sha IS DISTINCT FROM ? OR checks_head_seen_at IS NULL)""",
            sha, now, *slotArgs(id, memberRepo), sha,
        )
        return db.queryOne("SELECT checks_head_seen_at FROM $table WHERE $where", *slotArgs(id, memberRepo)) {
            it.instantOrNull("checks_head_seen_at")
        } ?: now
    }

    /** Claims the one "checks failed" comment for head commit [sha]; true for exactly one caller per commit. */
    fun claimChecksFailure(id: UUID, sha: String, memberRepo: String? = null): Boolean {
        val (table, where) = slot(memberRepo)
        return db.update(
            "UPDATE $table SET checks_failure_sha = ? WHERE $where AND checks_failure_sha IS DISTINCT FROM ?",
            sha, *slotArgs(id, memberRepo), sha,
        ) == 1
    }

    /** Records that an automatic merge of head commit [sha] was refused (it is not tried again for that commit). */
    fun setAutoMergeFailed(id: UUID, sha: String?) = db.update("UPDATE releases SET auto_merge_failed_sha = ? WHERE id = ?", sha, id)

    fun setPullBody(id: UUID, bodySha: String, memberRepo: String? = null): Int {
        val (table, where) = slot(memberRepo)
        return db.update("UPDATE $table SET pr_body_sha = ? WHERE $where", bodySha, *slotArgs(id, memberRepo))
    }

    fun setTarget(id: UUID, targetBranch: String) = db.update("UPDATE releases SET target_branch = ? WHERE id = ?", targetBranch, id)

    /** Sets the merge state; true only when it changed (so a transition is acted on once). */
    fun setMergeState(id: UUID, state: String, memberRepo: String? = null): Boolean {
        val (table, where) = slot(memberRepo)
        return db.update("UPDATE $table SET merge_state = ? WHERE $where AND merge_state <> ?", state, *slotArgs(id, memberRepo), state) == 1
    }

    fun setMergeMethod(id: UUID, method: String?, memberRepo: String? = null): Int {
        val (table, where) = slot(memberRepo)
        return db.update("UPDATE $table SET merge_method = ? WHERE $where", method, *slotArgs(id, memberRepo))
    }

    // ------------------------------------------------------------ member repositories (§6 "Several repositories")

    /**
     * Records that Agern created `release/<name>` in member repository [repo] from [baseBranch]; false when a row for it
     * exists already.
     */
    fun insertRepo(id: UUID, repo: String, directory: String, submodule: Boolean, baseBranch: String): Boolean = db.update(
        """INSERT INTO release_repos(release_id, repo, directory, submodule, base_branch, branch_created) VALUES (?, ?, ?, ?, ?, true)
           ON CONFLICT (release_id, repo) DO NOTHING""",
        id, repo, directory, submodule, baseBranch,
    ) == 1

    /** The base branch recorded for member repository [repo] of release [id], or null. */
    fun repoBase(id: UUID, repo: String): String? =
        db.queryOne("SELECT base_branch FROM release_repos WHERE release_id = ? AND repo = ?", id, repo) { it.getString("base_branch") }

    /** Member repository [repo]'s release pull request was merged: [sha] is its base branch's commit after the merge. Once. */
    fun setRepoMerged(id: UUID, repo: String, sha: String?): Boolean = db.update(
        """UPDATE release_repos SET merged_at = now(), merge_sha = ?, merge_state = 'clean' WHERE release_id = ? AND repo = ?
           AND merged_at IS NULL""",
        sha, id, repo,
    ) == 1

    /** `release/<name>` was deleted (or found missing) in member repository [repo], or with null in the project repository. */
    fun setBranchDeleted(id: UUID, memberRepo: String? = null) = if (memberRepo == null) {
        db.update("UPDATE releases SET branch_deleted = true WHERE id = ?", id)
    } else {
        db.update("UPDATE release_repos SET branch_deleted = true WHERE release_id = ? AND repo = ?", id, memberRepo)
    }

    /** The project repository's pull request was merged on GitHub while member pull requests remain (once). */
    fun setProjectMergedEarly(id: UUID, sha: String?): Boolean = db.update(
        "UPDATE releases SET pr_merged_at = now(), merge_sha = ? WHERE id = ? AND pr_merged_at IS NULL", sha, id,
    ) == 1

    fun setNotice(id: UUID, notice: String?) = db.update("UPDATE releases SET notice = ? WHERE id = ?", notice, id)

    /** Claims the "ready to release" announcement of the current ready period; true for exactly one caller. */
    fun claimReadyAnnouncement(id: UUID): Boolean =
        db.update("UPDATE releases SET ready_announced = true WHERE id = ? AND status = 'ready' AND NOT ready_announced", id) == 1

    /**
     * `ready` → `released` after the merge: true for exactly one caller, who then closes the issues and deletes the
     * branch ([cleanupPending] until [finishCleanup]). [by] is null when the pull request was merged on GitHub.
     */
    fun markReleased(id: UUID, method: String?, sha: String?, by: UUID?, automatic: Boolean = false): Boolean = db.update(
        """UPDATE releases SET status = 'released', released_at = now(), ended_at = now(), released_by = CAST(? AS uuid),
           released_automatically = ?,
           merge_method = COALESCE(CAST(? AS text), merge_method), merge_sha = ?, merge_state = 'clean', notice = NULL, cleanup_pending = true
           WHERE id = ? AND status = 'ready'""",
        by, automatic, method, sha, id,
    ) == 1

    fun finishCleanup(id: UUID, notice: String?) =
        db.update("UPDATE releases SET cleanup_pending = false, notice = ? WHERE id = ?", notice, id)

    /** Ids of `ready` releases (re-checked after a restart). */
    fun readyIds(): List<UUID> = db.query("SELECT id FROM releases WHERE status = 'ready'") { it.uuid("id") }

    /**
     * Ids of the `ready` releases the periodic re-evaluation looks at: every automatic one (it may become green), manual
     * ones whose checks are pending (so the release page shows when they finish), and every other one with a pull
     * request (reconciled only: it may have been merged or closed on GitHub). For a release of several repositories,
     * the checks and pull requests of its member repositories count too.
     */
    fun periodicIds(): List<UUID> = db.query(
        "SELECT id FROM releases r WHERE status = 'ready' AND (release_mode = 'automatic' OR checks_state IN ('pending', 'awaiting') " +
            "OR pr_number IS NOT NULL OR EXISTS (SELECT 1 FROM release_repos rr WHERE rr.release_id = r.id AND rr.merged_at IS NULL " +
            "AND (rr.pr_number IS NOT NULL OR rr.checks_state IN ('pending', 'awaiting')))) ORDER BY ready_at, id",
    ) { it.uuid("id") }

    /** Ids of released releases whose clean-up did not finish (a restart in between). */
    fun cleanupPending(): List<UUID> =
        db.query("SELECT id FROM releases WHERE status = 'released' AND cleanup_pending") { it.uuid("id") }

    /**
     * Sets the state, note, run link and retry mode of one issue of a release. [retryMode] (`continue` or `step`, for a
     * `waiting` issue retried from [runId]) is cleared by every other change.
     */
    fun setIssue(releaseId: UUID, number: Int, state: String, note: String?, runId: UUID?, retryMode: String? = null) = db.update(
        "UPDATE release_issues SET state = ?, note = ?, run_id = ?, retry_mode = ? WHERE release_id = ? AND issue_number = ?",
        state, note, runId, retryMode, releaseId, number,
    )

    /** Sets state and note of the issues of [releaseId] that are in [from]; returns how many changed. */
    fun setIssuesInState(releaseId: UUID, from: String, state: String, note: String?) = db.update(
        "UPDATE release_issues SET state = ?, note = ?, retry_mode = NULL WHERE release_id = ? AND state = ?", state, note, releaseId, from,
    )

    /** The release issue linked to [runId] (its latest release run), if any. */
    fun issueByRun(runId: UUID): ReleaseIssueRef? = db.queryOne(
        "SELECT release_id, issue_number, state, run_id FROM release_issues WHERE run_id = ?", runId,
    ) { ReleaseIssueRef(it.uuid("release_id"), it.getInt("issue_number"), it.getString("state"), it.uuidOrNull("run_id")) }

    /** Every release issue that is `running` (for recovery after a restart). */
    fun runningIssues(): List<ReleaseIssueRef> = db.query(
        "SELECT release_id, issue_number, state, run_id FROM release_issues WHERE state = 'running'",
    ) { ReleaseIssueRef(it.uuid("release_id"), it.getInt("issue_number"), it.getString("state"), it.uuidOrNull("run_id")) }

    /** Ids of all `building` releases. */
    fun building(): List<UUID> = db.query("SELECT id FROM releases WHERE status = 'building'") { it.uuid("id") }

    /** Ids of paused releases whose automatic resume is due at [now]. */
    fun dueForResume(now: Instant): List<UUID> =
        db.query("SELECT id FROM releases WHERE status = 'paused' AND resume_at IS NOT NULL AND resume_at <= ?", now) { it.uuid("id") }

    // ---------------------------------------------------------------- scheduled start (§6 "Scheduled start")
    // Every state change is conditional on the expected status and reports whether it applied.

    private val bump = "updated_at = GREATEST(now(), date_trunc('milliseconds', updated_at) + interval '1 millisecond')"

    /** `planning` → `scheduled` with [ownerId] as owner; clears an earlier start failure. */
    fun schedule(id: UUID, ownerId: UUID, at: Instant): Boolean = db.update(
        """UPDATE releases SET status = ?, owner_id = ?, scheduled_at = ?, waiting_for = NULL,
           start_failed_at = NULL, start_failed_owner = NULL, start_failed_reason = NULL, $bump
           WHERE id = ? AND status = ?""",
        ReleaseStatus.SCHEDULED, ownerId, at, id, ReleaseStatus.PLANNING,
    ) > 0

    /** A new time for a `scheduled` release; the owner stays. */
    fun reschedule(id: UUID, at: Instant): Boolean = db.update(
        "UPDATE releases SET scheduled_at = ?, waiting_for = NULL, $bump WHERE id = ? AND status = ?",
        at, id, ReleaseStatus.SCHEDULED,
    ) > 0

    /** `scheduled` → `planning`; the owner, time and waiting are cleared, an earlier start failure is kept. */
    fun unschedule(id: UUID): Boolean = db.update(
        "UPDATE releases SET status = ?, owner_id = NULL, scheduled_at = NULL, waiting_for = NULL, $bump WHERE id = ? AND status = ?",
        ReleaseStatus.PLANNING, id, ReleaseStatus.SCHEDULED,
    ) > 0

    /** Scheduled releases whose time has come, oldest first. */
    fun due(now: Instant): List<ReleaseRecord> = withIssues(
        db.query(
            "$select WHERE r.status = ? AND r.scheduled_at <= ? ORDER BY r.scheduled_at, r.created_at, r.id",
            ReleaseStatus.SCHEDULED, now, map = ::mapRow,
        ),
    )

    /** Sets or clears what a `scheduled` release waits behind; true only if the value changed. */
    fun setWaitingFor(id: UUID, otherId: UUID?): Boolean = db.update(
        "UPDATE releases SET waiting_for = CAST(? AS uuid) WHERE id = ? AND status = ? AND waiting_for IS DISTINCT FROM CAST(? AS uuid)",
        otherId, id, ReleaseStatus.SCHEDULED, otherId,
    ) > 0

    /** `scheduled` → `planning`, recording why the scheduled start did not happen. */
    fun failStart(id: UUID, at: Instant, ownerId: UUID?, reason: String): Boolean = db.update(
        """UPDATE releases SET status = ?, owner_id = NULL, scheduled_at = NULL, waiting_for = NULL,
           start_failed_at = ?, start_failed_owner = ?, start_failed_reason = ?, $bump
           WHERE id = ? AND status = ?""",
        ReleaseStatus.PLANNING, at, ownerId, reason, id, ReleaseStatus.SCHEDULED,
    ) > 0

    /** `scheduled` → `building`, keeping the owner. For [dk.egernet.factory.ReleaseStarter]s and tests. */
    fun markBuilding(id: UUID): Boolean = db.update(
        "UPDATE releases SET status = ?, scheduled_at = NULL, waiting_for = NULL, $bump WHERE id = ? AND status = ?",
        ReleaseStatus.BUILDING, id, ReleaseStatus.SCHEDULED,
    ) > 0

    /** Release id and name per assigned issue number of [repo]. */
    fun releaseByIssue(repo: String): Map<Int, Pair<UUID, String>> = db.query(
        "SELECT ri.issue_number, r.id, r.name FROM release_issues ri JOIN releases r ON r.id = ri.release_id WHERE ri.repo = ?", repo,
    ) { it.getInt("issue_number") to (it.uuid("id") to it.getString("name")) }.toMap()

    /**
     * Marks the release as changed; `updated_at` strictly increases at millisecond resolution (as `RunStore.touch`),
     * so clients can order the versions they receive.
     */
    fun touch(id: UUID) = db.update(
        "UPDATE releases SET updated_at = GREATEST(now(), date_trunc('milliseconds', updated_at) + interval '1 millisecond') WHERE id = ?", id,
    )

    private fun lock(c: Connection, repo: String) {
        c.prepare("SELECT pg_advisory_xact_lock(hashtext(?))", arrayOf<Any?>("releases:$repo")).use { it.executeQuery().close() }
    }

    private data class Meta(val status: String, val ownerId: UUID?, val ownerUsername: String?, val name: String)

    private fun meta(c: Connection, id: UUID): Meta? = c.prepare(
        "SELECT r.status, r.owner_id, o.username, r.name FROM releases r LEFT JOIN users o ON o.id = r.owner_id WHERE r.id = ?", arrayOf<Any?>(id),
    ).use { ps -> ps.executeQuery().use { rs -> if (rs.next()) Meta(rs.getString(1), rs.uuidOrNull("owner_id"), rs.getString(3), rs.getString(4)) else null } }

    private data class Current(val releaseId: UUID, val state: String, val runId: UUID?)

    private fun currentRelease(c: Connection, repo: String, number: Int): Current? =
        c.prepare("SELECT release_id, state, run_id FROM release_issues WHERE repo = ? AND issue_number = ?", arrayOf<Any?>(repo, number)).use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) Current(rs.uuid("release_id"), rs.getString("state"), rs.uuidOrNull("run_id")) else null }
        }

    /** Checks that [actor] may take issue #[number] (in [state]) out of a release described by [m]. */
    private fun checkLeave(m: Meta, actor: ReleaseActor, number: Int, state: String) {
        if (m.status in ReleaseStatus.PLANNED) return
        if (m.status == ReleaseStatus.RELEASED) throw releasedNotEditable(m.name)
        if (m.ownerId != actor.id && !actor.isAdmin) throw releaseForbidden(m.ownerUsername, adminAllowed = true)
        if (m.status != ReleaseStatus.CANCELLED && state !in LEAVABLE) {
            throw conflict("Issue #$number is $state in a started release and cannot be taken out", "release_issue_started")
        }
    }

    /**
     * Puts [issues] into [releaseId], appended in the given order; issues in another release are moved, issues already
     * in this one keep their position. Returns the ids of all releases that changed, with the issues that left each
     * (`moved[from]`), so callers can describe the move. The rules of started releases are checked for the target and
     * every source in the same transaction: all or nothing.
     */
    fun assign(repo: String, releaseId: UUID, issues: List<IssueToAssign>, actor: ReleaseActor): AssignResult = db.tx { c ->
        lock(c, repo)
        val target = meta(c, releaseId) ?: throw dk.egernet.factory.notFound("Release not found")
        when (target.status) {
            ReleaseStatus.CANCELLED -> throw conflict("The release is cancelled; no issues can be added", "release_closed")
            ReleaseStatus.RELEASED -> throw releasedNotEditable(target.name)
            in ReleaseStatus.ACTIVE, ReleaseStatus.READY ->
                if (target.ownerId != actor.id) throw releaseForbidden(target.ownerUsername, adminAllowed = false)
        }
        val changed = linkedSetOf<UUID>()
        val movedFrom = linkedMapOf<UUID, MutableList<Int>>()
        val added = mutableListOf<Int>()
        val leftRuns = mutableListOf<UUID>()
        var next = c.prepare("SELECT COALESCE(max(position) + 1, 0) FROM release_issues WHERE release_id = ?", arrayOf<Any?>(releaseId))
            .use { ps -> ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
        for (i in issues) {
            val current = currentRelease(c, repo, i.number)
            if (current?.releaseId == releaseId) continue
            if (current != null) {
                checkLeave(meta(c, current.releaseId)!!, actor, i.number, current.state)
                c.prepare("DELETE FROM release_issues WHERE repo = ? AND issue_number = ?", arrayOf<Any?>(repo, i.number)).use { it.executeUpdate() }
                changed += current.releaseId
                movedFrom.getOrPut(current.releaseId) { mutableListOf() } += i.number
                current.runId?.let { leftRuns += it }
            }
            c.prepare(
                """INSERT INTO release_issues(release_id, repo, issue_number, position, issue_title, issue_kind, added_by)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                arrayOf<Any?>(releaseId, repo, i.number, next++, i.title, i.kind, actor.id),
            ).use { it.executeUpdate() }
            added += i.number
        }
        if (added.isNotEmpty()) changed += releaseId
        // A ready release builds again for its new issues (needs the project's building slot; all or nothing).
        if (added.isNotEmpty() && target.status == ReleaseStatus.READY) {
            try {
                c.prepare("UPDATE releases SET status = 'building', ready_at = NULL WHERE id = ? AND status = 'ready'", arrayOf<Any?>(releaseId))
                    .use { it.executeUpdate() }
            } catch (e: SQLException) {
                if (e.sqlState == "23505") throw slotTaken()
                throw e
            }
        }
        AssignResult(changed.toList(), added, movedFrom, leftRuns)
    }

    /** [leftRuns]: the release runs linked to issues that left a release (callers cancel those still active). */
    data class AssignResult(val changed: List<UUID>, val added: List<Int>, val movedFrom: Map<UUID, List<Int>>, val leftRuns: List<UUID> = emptyList())

    /** What [unassign] did: the releases that changed and the release runs of the issues taken out. */
    data class UnassignResult(val changed: List<UUID>, val leftRuns: List<UUID>)

    /** Takes [numbers] out of whatever release they are in (all or nothing, checking the rules of started releases). */
    fun unassign(repo: String, numbers: List<Int>, actor: ReleaseActor): UnassignResult = db.tx { c ->
        lock(c, repo)
        val changed = linkedSetOf<UUID>()
        val leftRuns = mutableListOf<UUID>()
        for (n in numbers) {
            val current = currentRelease(c, repo, n) ?: continue
            checkLeave(meta(c, current.releaseId)!!, actor, n, current.state)
            c.prepare("DELETE FROM release_issues WHERE repo = ? AND issue_number = ?", arrayOf<Any?>(repo, n)).use { it.executeUpdate() }
            changed += current.releaseId
            current.runId?.let { leftRuns += it }
        }
        UnassignResult(changed.toList(), leftRuns)
    }

    /**
     * Sets the complete order of the release's issues. [numbers] must be exactly its current issues, each once;
     * otherwise nothing changes and 409 `release_issues_changed` is thrown. In a building or paused release only its
     * owner may reorder, and only among waiting issues: every other issue keeps its index (409 `release_issue_started`).
     */
    fun reorder(repo: String, releaseId: UUID, numbers: List<Int>, actor: ReleaseActor) = db.tx { c ->
        lock(c, repo)
        val m = meta(c, releaseId) ?: throw dk.egernet.factory.notFound("Release not found")
        if (m.status == ReleaseStatus.CANCELLED) throw conflict("The release is cancelled", "release_closed")
        if (m.status == ReleaseStatus.RELEASED) throw releasedNotEditable(m.name)
        val started = m.status in ReleaseStatus.ACTIVE || m.status == ReleaseStatus.READY
        if (started && m.ownerId != actor.id) throw releaseForbidden(m.ownerUsername, adminAllowed = false)
        val current = c.prepare(
            "SELECT issue_number, state FROM release_issues WHERE release_id = ? ORDER BY position, added_at", arrayOf<Any?>(releaseId),
        ).use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getInt(1) to rs.getString(2)) } }
        }
        val currentNumbers = current.map { it.first }
        if (numbers.size != current.size || numbers.toSet() != currentNumbers.toSet() || numbers.toSet().size != numbers.size) {
            throw conflict("The release's issues changed in the meantime; reload and try again.", "release_issues_changed")
        }
        if (started) {
            current.forEachIndexed { i, (n, state) ->
                if (state != ReleaseIssueState.WAITING && numbers[i] != n) {
                    throw conflict("Issue #$n is $state; only waiting issues can be reordered", "release_issue_started")
                }
            }
        }
        numbers.forEachIndexed { pos, n ->
            c.prepare("UPDATE release_issues SET position = ? WHERE release_id = ? AND issue_number = ?", arrayOf<Any?>(pos, releaseId, n))
                .use { it.executeUpdate() }
        }
    }

    private companion object {
        /** Issue states that may leave a building or paused release. */
        val LEAVABLE = setOf(ReleaseIssueState.WAITING, ReleaseIssueState.HELD, ReleaseIssueState.SKIPPED)
    }
}
