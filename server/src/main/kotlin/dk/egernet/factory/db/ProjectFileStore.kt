package dk.egernet.factory.db

import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** A project file's metadata (the bytes are on disk, see [dk.egernet.factory.files.ProjectFiles]). */
data class ProjectFileRecord(
    val id: UUID, val repo: String, val name: String, val size: Long, val sha256: String, val version: Int,
    val note: String?, val downloadable: Boolean, val uploadedBy: UUID?, val uploadedByUsername: String?,
    val uploadedByDisplayName: String?, val uploadedAt: Instant,
)

/** An upload in flight; its bytes so far are the part file on disk. */
data class ProjectFileUploadRecord(
    val id: UUID, val repo: String, val name: String, val size: Long, val sha256: String?, val note: String?,
    val createdBy: UUID?, val createdByUsername: String?, val createdByDisplayName: String?,
    val createdAt: Instant, val touchedAt: Instant,
)

/** A user on a repository's file managers list. */
data class ProjectFileManager(val userId: UUID, val username: String, val displayName: String)

/** Project file metadata, uploads in flight and managers (docs/ARCHITECTURE.md §5 "Project files"). Plain JDBC. */
class ProjectFileStore(private val db: Db) {
    private val fileSelect = """SELECT f.*, u.username AS by_username, u.display_name AS by_display_name
        FROM project_files f LEFT JOIN users u ON u.id = f.uploaded_by"""
    private val uploadSelect = """SELECT p.*, u.username AS by_username, u.display_name AS by_display_name
        FROM project_file_uploads p LEFT JOIN users u ON u.id = p.created_by"""

    private fun mapFile(rs: ResultSet) = ProjectFileRecord(
        rs.uuid("id"), rs.getString("repo"), rs.getString("name"), rs.getLong("size_bytes"), rs.getString("sha256"),
        rs.getInt("version"), rs.getString("note"), rs.getBoolean("downloadable"), rs.uuidOrNull("uploaded_by"),
        rs.getString("by_username"), rs.getString("by_display_name"), rs.instant("uploaded_at"),
    )

    private fun mapUpload(rs: ResultSet) = ProjectFileUploadRecord(
        rs.uuid("id"), rs.getString("repo"), rs.getString("name"), rs.getLong("size_bytes"), rs.getString("sha256"),
        rs.getString("note"), rs.uuidOrNull("created_by"), rs.getString("by_username"), rs.getString("by_display_name"),
        rs.instant("created_at"), rs.instant("touched_at"),
    )

    // ---------------------------------------------------------------- files

    /** The repository's files, by name. */
    fun files(repo: String): List<ProjectFileRecord> = db.query("$fileSelect WHERE f.repo = ? ORDER BY lower(f.name)", repo, map = ::mapFile)

    fun file(repo: String, id: UUID): ProjectFileRecord? = db.queryOne("$fileSelect WHERE f.repo = ? AND f.id = ?", repo, id, map = ::mapFile)

    fun fileByName(repo: String, name: String): ProjectFileRecord? =
        db.queryOne("$fileSelect WHERE f.repo = ? AND lower(f.name) = lower(?)", repo, name, map = ::mapFile)

    /** Rows of [repo] that reference the content [sha256]. */
    fun countBySha(repo: String, sha256: String): Int =
        db.queryOne("SELECT count(*) AS n FROM project_files WHERE repo = ? AND sha256 = ?", repo, sha256) { it.getInt("n") } ?: 0

    /** Sum of the current sizes of [repo]'s files. */
    fun projectBytes(repo: String): Long =
        db.queryOne("SELECT COALESCE(sum(size_bytes), 0) AS n FROM project_files WHERE repo = ?", repo) { it.getLong("n") } ?: 0

    /** Sum of the current sizes of every repository's files. */
    fun totalBytes(): Long = db.queryOne("SELECT COALESCE(sum(size_bytes), 0) AS n FROM project_files") { it.getLong("n") } ?: 0

    /** Every repository that has files, with count and bytes. */
    fun perRepo(): List<Triple<String, Int, Long>> = db.query(
        "SELECT repo, count(*) AS n, sum(size_bytes) AS bytes FROM project_files GROUP BY repo ORDER BY repo",
    ) { Triple(it.getString("repo"), it.getInt("n"), it.getLong("bytes")) }

    /**
     * Publishes a completed upload in one transaction: inserts a new file (version 1) or replaces [replacing] (same id
     * and name, version + 1, note kept unless [note] is given), and deletes the upload row. Returns the file's id.
     */
    fun publish(
        repo: String, name: String, size: Long, sha256: String, note: String?, by: UUID?, uploadId: UUID, replacing: ProjectFileRecord?,
    ): UUID = db.tx { c ->
        val id = replacing?.id ?: UUID.randomUUID()
        if (replacing == null) {
            c.prepare(
                "INSERT INTO project_files(id, repo, name, size_bytes, sha256, version, note, uploaded_by, uploaded_at) VALUES (?, ?, ?, ?, ?, 1, ?, ?, now())",
                arrayOf(id, repo, name, size, sha256, note, by),
            ).use { it.executeUpdate() }
        } else {
            c.prepare(
                """UPDATE project_files SET size_bytes = ?, sha256 = ?, version = version + 1, note = COALESCE(?, note),
                   uploaded_by = ?, uploaded_at = now() WHERE id = ? AND repo = ?""",
                arrayOf(size, sha256, note, by, id, repo),
            ).use { check(it.executeUpdate() == 1) { "The replaced file no longer exists" } }
        }
        c.prepare("DELETE FROM project_file_uploads WHERE id = ?", arrayOf(uploadId)).use { it.executeUpdate() }
        id
    }

    /** Changes the note and/or the download switch; null leaves a field as it is. */
    fun update(repo: String, id: UUID, note: String?, clearNote: Boolean, downloadable: Boolean?): Boolean = db.update(
        """UPDATE project_files SET note = CASE WHEN ? THEN NULL ELSE COALESCE(?, note) END,
           downloadable = COALESCE(?, downloadable) WHERE repo = ? AND id = ?""",
        clearNote, note, downloadable, repo, id,
    ) > 0

    fun delete(repo: String, id: UUID): Boolean = db.update("DELETE FROM project_files WHERE repo = ? AND id = ?", repo, id) > 0

    /** Admin purge: deletes every file and upload row of [repo]; returns the files' count and bytes. */
    fun purge(repo: String): Pair<Int, Long> = db.tx { c ->
        val stats = c.prepare("SELECT count(*) AS n, COALESCE(sum(size_bytes), 0) AS bytes FROM project_files WHERE repo = ?", arrayOf(repo)).use { ps ->
            ps.executeQuery().use { rs -> rs.next(); rs.getInt("n") to rs.getLong("bytes") }
        }
        c.prepare("DELETE FROM project_files WHERE repo = ?", arrayOf(repo)).use { it.executeUpdate() }
        c.prepare("DELETE FROM project_file_uploads WHERE repo = ?", arrayOf(repo)).use { it.executeUpdate() }
        stats
    }

    // ---------------------------------------------------------------- uploads

    fun uploads(repo: String): List<ProjectFileUploadRecord> =
        db.query("$uploadSelect WHERE p.repo = ? ORDER BY p.created_at", repo, map = ::mapUpload)

    fun allUploads(): List<ProjectFileUploadRecord> = db.query("$uploadSelect ORDER BY p.created_at", map = ::mapUpload)

    fun upload(repo: String, id: UUID): ProjectFileUploadRecord? =
        db.queryOne("$uploadSelect WHERE p.repo = ? AND p.id = ?", repo, id, map = ::mapUpload)

    fun uploadByName(repo: String, name: String): ProjectFileUploadRecord? =
        db.queryOne("$uploadSelect WHERE p.repo = ? AND lower(p.name) = lower(?)", repo, name, map = ::mapUpload)

    /** Declared sizes of open uploads: of [repo] (or of all repositories when null), except [except]. */
    fun openUploadBytes(repo: String?, except: UUID?): Long =
        if (repo == null) db.queryOne("SELECT COALESCE(sum(size_bytes), 0) AS n FROM project_file_uploads WHERE id IS DISTINCT FROM ?::uuid", except) { it.getLong("n") } ?: 0
        else db.queryOne("SELECT COALESCE(sum(size_bytes), 0) AS n FROM project_file_uploads WHERE repo = ? AND id IS DISTINCT FROM ?::uuid", repo, except) { it.getLong("n") } ?: 0

    fun createUpload(id: UUID, repo: String, name: String, size: Long, sha256: String?, note: String?, by: UUID) = db.update(
        "INSERT INTO project_file_uploads(id, repo, name, size_bytes, sha256, note, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
        id, repo, name, size, sha256, note, by,
    )

    fun touchUpload(id: UUID) = db.update("UPDATE project_file_uploads SET touched_at = now() WHERE id = ?", id)

    fun deleteUpload(id: UUID): Boolean = db.update("DELETE FROM project_file_uploads WHERE id = ?", id) > 0

    // ---------------------------------------------------------------- managers

    fun managers(repo: String): List<ProjectFileManager> = db.query(
        """SELECT m.user_id, u.username, u.display_name FROM project_file_managers m JOIN users u ON u.id = m.user_id
           WHERE m.repo = ? ORDER BY u.username""",
        repo,
    ) { ProjectFileManager(it.uuid("user_id"), it.getString("username"), it.getString("display_name")) }

    fun isManager(repo: String, userId: UUID): Boolean =
        db.queryOne("SELECT 1 AS x FROM project_file_managers WHERE repo = ? AND user_id = ?", repo, userId) { true } ?: false

    /** Replaces [repo]'s managers list with [userIds]. */
    fun setManagers(repo: String, userIds: List<UUID>, by: UUID) = db.tx { c ->
        c.prepare("DELETE FROM project_file_managers WHERE repo = ?", arrayOf(repo)).use { it.executeUpdate() }
        for (u in userIds.distinct()) {
            c.prepare("INSERT INTO project_file_managers(repo, user_id, added_by) VALUES (?, ?, ?)", arrayOf(repo, u, by)).use { it.executeUpdate() }
        }
    }
}
