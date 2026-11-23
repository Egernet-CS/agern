package dk.egernet.factory.db

import dk.egernet.factory.ScreenshotDto
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** Metadata of a stored stage screenshot (without its bytes). */
data class ScreenshotMeta(
    val id: UUID, val runId: UUID, val stageId: UUID, val caption: String, val mediaType: String, val size: Long,
    val sha256: String, val width: Int, val height: Int, val createdAt: Instant,
) {
    fun toDto() = ScreenshotDto(
        id = id.toString(), caption = caption, mediaType = mediaType, size = size, width = width, height = height,
        createdAt = createdAt.iso(),
    )
}

/** A new screenshot, checked and ready to store. */
class NewScreenshot(
    val caption: String, val mediaType: String, val data: ByteArray, val sha256: String, val width: Int, val height: Int,
)

sealed interface ScreenshotInsertResult {
    /** [position]: how many screenshots the stage has now, this one included. */
    data class Ok(val meta: ScreenshotMeta, val position: Int) : ScreenshotInsertResult
    data class TooMany(val count: Int) : ScreenshotInsertResult
}

/** Screenshots of UX and UI design reviewer stages (docs/ARCHITECTURE.md §6 "Screenshots"). */
class ScreenshotStore(private val db: Db) {
    /** Never `data`: metadata queries do not load the bytes. */
    private val cols = "id, run_id, stage_id, caption, media_type, size_bytes, sha256, width, height, created_at"

    private fun map(rs: ResultSet) = ScreenshotMeta(
        rs.uuid("id"), rs.uuid("run_id"), rs.uuid("stage_id"), rs.getString("caption"), rs.getString("media_type"),
        rs.getLong("size_bytes"), rs.getString("sha256"), rs.getInt("width"), rs.getInt("height"), rs.instant("created_at"),
    )

    /**
     * Stores a screenshot for [stageId] unless the stage already has [maxCount]. Checked under a per-stage lock,
     * so parallel calls cannot overshoot.
     */
    fun insert(runId: UUID, stageId: UUID, s: NewScreenshot, maxCount: Int): ScreenshotInsertResult {
        val id = UUID.randomUUID()
        /** (stored, count afterwards) */
        val (stored, count) = db.tx { c ->
            c.prepare("SELECT pg_advisory_xact_lock(hashtext(?))", arrayOf<Any?>("screenshots:$stageId")).use { it.executeQuery().close() }
            val n = c.prepare("SELECT count(*) FROM stage_screenshots WHERE stage_id = ?", arrayOf<Any?>(stageId))
                .use { ps -> ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
            if (n >= maxCount) return@tx false to n
            c.prepare(
                """INSERT INTO stage_screenshots(id, run_id, stage_id, caption, media_type, size_bytes, sha256, width, height, data)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                arrayOf<Any?>(id, runId, stageId, s.caption, s.mediaType, s.data.size.toLong(), s.sha256, s.width, s.height, s.data),
            ).use { it.executeUpdate() }
            true to n + 1
        }
        return if (stored) ScreenshotInsertResult.Ok(get(id)!!, count) else ScreenshotInsertResult.TooMany(count)
    }

    fun get(id: UUID): ScreenshotMeta? = db.queryOne("SELECT $cols FROM stage_screenshots WHERE id = ?", id, map = ::map)

    /** The image bytes. */
    fun data(id: UUID): ByteArray? = db.queryOne("SELECT data FROM stage_screenshots WHERE id = ?", id) { it.getBytes("data") }

    /** All screenshots of a run in attach order. */
    fun forRun(runId: UUID): List<ScreenshotMeta> =
        db.query("SELECT $cols FROM stage_screenshots WHERE run_id = ? ORDER BY ordinal", runId, map = ::map)
}
