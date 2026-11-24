package dk.egernet.factory.db

import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** Metadata of a stored project icon (without its bytes). */
data class ProjectIconMeta(
    val repo: String, val mediaType: String, val size: Long, val sha256: String, val width: Int, val height: Int,
    val updatedBy: UUID, val updatedAt: Instant,
)

/** Project icons, one per repository, keyed by its name (docs/ARCHITECTURE.md §8 "Project icons"). */
class ProjectIconStore(private val db: Db) {
    /** Never `data`: metadata queries do not load the bytes. */
    private val cols = "repo, media_type, size_bytes, sha256, width, height, updated_by, updated_at"

    private fun map(rs: ResultSet) = ProjectIconMeta(
        rs.getString("repo"), rs.getString("media_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
        rs.getInt("width"), rs.getInt("height"), rs.uuid("updated_by"), rs.instant("updated_at"),
    )

    /** The SHA-256 of every stored icon by repository, in one query. */
    fun versions(): Map<String, String> =
        db.query("SELECT repo, sha256 FROM project_icons") { it.getString("repo") to it.getString("sha256") }.toMap()

    fun meta(repo: String): ProjectIconMeta? = db.queryOne("SELECT $cols FROM project_icons WHERE repo = ?", repo, map = ::map)

    /** The image bytes. */
    fun data(repo: String): ByteArray? = db.queryOne("SELECT data FROM project_icons WHERE repo = ?", repo) { it.getBytes("data") }

    /** Stores [bytes] as the icon of [repo], replacing an earlier one in the same statement. */
    fun put(repo: String, mediaType: String, bytes: ByteArray, sha256: String, width: Int, height: Int, by: UUID) = db.update(
        """INSERT INTO project_icons(repo, media_type, size_bytes, sha256, width, height, data, updated_by, updated_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
           ON CONFLICT (repo) DO UPDATE SET media_type = EXCLUDED.media_type, size_bytes = EXCLUDED.size_bytes,
             sha256 = EXCLUDED.sha256, width = EXCLUDED.width, height = EXCLUDED.height, data = EXCLUDED.data,
             updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at""",
        repo, mediaType, bytes.size.toLong(), sha256, width, height, bytes, by,
    )

    /** True when there was an icon to remove. */
    fun delete(repo: String): Boolean = db.update("DELETE FROM project_icons WHERE repo = ?", repo) > 0
}
