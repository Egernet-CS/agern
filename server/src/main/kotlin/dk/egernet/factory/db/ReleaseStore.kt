package dk.egernet.factory.db

import dk.egernet.factory.OwnerDto
import dk.egernet.factory.ReleaseDto
import dk.egernet.factory.ReleaseIssueDto
import dk.egernet.factory.ReleaseStatus
import dk.egernet.factory.conflict
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

data class ReleaseIssueRecord(val number: Int, val title: String, val kind: String)

data class ReleaseRecord(
    val id: UUID, val repo: String, val name: String, val description: String, val status: String,
    val createdBy: UUID, val createdByUsername: String, val createdByDisplayName: String,
    val createdAt: Instant, val updatedAt: Instant, val issues: List<ReleaseIssueRecord>,
) {
    fun toDto() = ReleaseDto(
        id.toString(), repo, name, description, status, OwnerDto(createdByUsername, createdByDisplayName),
        createdAt.iso(), updatedAt.iso(), issues.map { ReleaseIssueDto(it.number, it.title, it.kind) },
    )
}

/** An issue to put into a release, with the title and kind it has at that moment. */
data class IssueToAssign(val number: Int, val title: String, val kind: String)

/**
 * Releases and their planned issues (docs/ARCHITECTURE.md §6 "Releases"). An issue is in at most one release per repo
 * (primary key of `release_issues`); changes to assignments and order are serialised per repo by an advisory lock.
 */
class ReleaseStore(private val db: Db) {
    private val select = """SELECT r.*, u.username AS created_by_username, u.display_name AS created_by_display_name
        FROM releases r JOIN users u ON u.id = r.created_by"""

    private fun mapRow(rs: ResultSet) = ReleaseRecord(
        rs.uuid("id"), rs.getString("repo"), rs.getString("name"), rs.getString("description"), rs.getString("status"),
        rs.uuid("created_by"), rs.getString("created_by_username"), rs.getString("created_by_display_name"),
        rs.instant("created_at"), rs.instant("updated_at"), emptyList(),
    )

    private fun issuesOf(ids: List<UUID>): Map<UUID, List<ReleaseIssueRecord>> {
        if (ids.isEmpty()) return emptyMap()
        return db.query(
            "SELECT release_id, issue_number, issue_title, issue_kind FROM release_issues WHERE release_id IN (" +
                ids.joinToString(",") { "?" } + ") ORDER BY position, added_at",
            *ids.toTypedArray(),
        ) { it.uuid("release_id") to ReleaseIssueRecord(it.getInt("issue_number"), it.getString("issue_title"), it.getString("issue_kind")) }
            .groupBy({ it.first }, { it.second })
    }

    private fun withIssues(list: List<ReleaseRecord>): List<ReleaseRecord> {
        val issues = issuesOf(list.map { it.id })
        return list.map { it.copy(issues = issues[it.id] ?: emptyList()) }
    }

    /** Runs [block], turning a violation of the unique name index into 409 `release_name_taken`. */
    private fun <T> uniqueName(name: String, block: () -> T): T = try {
        block()
    } catch (e: SQLException) {
        if (e.sqlState == "23505") throw conflict("A release named \"$name\" already exists in this project", "release_name_taken")
        throw e
    }

    fun create(repo: String, name: String, description: String, createdBy: UUID): ReleaseRecord {
        val id = UUID.randomUUID()
        uniqueName(name) {
            db.update(
                "INSERT INTO releases(id, repo, name, description, status, created_by) VALUES (?, ?, ?, ?, ?, ?)",
                id, repo, name, description, ReleaseStatus.PLANNING, createdBy,
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

    fun update(id: UUID, name: String, description: String) {
        uniqueName(name) {
            db.update(
                """UPDATE releases SET name = ?, description = ?,
                   updated_at = GREATEST(now(), date_trunc('milliseconds', updated_at) + interval '1 millisecond') WHERE id = ?""",
                name, description, id,
            )
        }
    }

    fun delete(id: UUID): Boolean = db.update("DELETE FROM releases WHERE id = ?", id) > 0

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

    private fun currentRelease(c: Connection, repo: String, number: Int): UUID? =
        c.prepare("SELECT release_id FROM release_issues WHERE repo = ? AND issue_number = ?", arrayOf<Any?>(repo, number)).use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) rs.uuid("release_id") else null }
        }

    /**
     * Puts [issues] into [releaseId], appended in the given order; issues in another release are moved, issues already
     * in this one keep their position. Returns the ids of all releases that changed, with the issues that left each
     * (`moved[from]`), so callers can describe the move.
     */
    fun assign(repo: String, releaseId: UUID, issues: List<IssueToAssign>, userId: UUID): AssignResult = db.tx { c ->
        lock(c, repo)
        val changed = linkedSetOf<UUID>()
        val movedFrom = linkedMapOf<UUID, MutableList<Int>>()
        val added = mutableListOf<Int>()
        var next = c.prepare("SELECT COALESCE(max(position) + 1, 0) FROM release_issues WHERE release_id = ?", arrayOf<Any?>(releaseId))
            .use { ps -> ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
        for (i in issues) {
            val current = currentRelease(c, repo, i.number)
            if (current == releaseId) continue
            if (current != null) {
                c.prepare("DELETE FROM release_issues WHERE repo = ? AND issue_number = ?", arrayOf<Any?>(repo, i.number)).use { it.executeUpdate() }
                changed += current
                movedFrom.getOrPut(current) { mutableListOf() } += i.number
            }
            c.prepare(
                """INSERT INTO release_issues(release_id, repo, issue_number, position, issue_title, issue_kind, added_by)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                arrayOf<Any?>(releaseId, repo, i.number, next++, i.title, i.kind, userId),
            ).use { it.executeUpdate() }
            added += i.number
        }
        if (added.isNotEmpty()) changed += releaseId
        AssignResult(changed.toList(), added, movedFrom)
    }

    data class AssignResult(val changed: List<UUID>, val added: List<Int>, val movedFrom: Map<UUID, List<Int>>)

    /** Takes [numbers] out of whatever release they are in. Returns the ids of the releases that changed. */
    fun unassign(repo: String, numbers: List<Int>): List<UUID> = db.tx { c ->
        lock(c, repo)
        val changed = linkedSetOf<UUID>()
        for (n in numbers) {
            val current = currentRelease(c, repo, n) ?: continue
            c.prepare("DELETE FROM release_issues WHERE repo = ? AND issue_number = ?", arrayOf<Any?>(repo, n)).use { it.executeUpdate() }
            changed += current
        }
        changed.toList()
    }

    /**
     * Sets the complete order of the release's issues. [numbers] must be exactly its current issues, each once;
     * otherwise nothing changes and 409 `release_issues_changed` is thrown.
     */
    fun reorder(repo: String, releaseId: UUID, numbers: List<Int>) = db.tx { c ->
        lock(c, repo)
        val current = c.prepare("SELECT issue_number FROM release_issues WHERE release_id = ?", arrayOf<Any?>(releaseId)).use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getInt(1)) } }
        }
        if (numbers.size != current.size || numbers.toSet() != current.toSet() || numbers.toSet().size != numbers.size) {
            throw conflict("The release's issues changed in the meantime; reload and try again.", "release_issues_changed")
        }
        numbers.forEachIndexed { pos, n ->
            c.prepare("UPDATE release_issues SET position = ? WHERE release_id = ? AND issue_number = ?", arrayOf<Any?>(pos, releaseId, n))
                .use { it.executeUpdate() }
        }
    }
}
