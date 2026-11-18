package dk.egernet.factory.db

import dk.egernet.factory.AttachmentDto
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** Metadata of a stored attachment (without its bytes). */
data class AttachmentMeta(
    val id: UUID, val sessionId: UUID, val uploaderId: UUID, val name: String, val mediaType: String, val kind: String,
    val size: Long, val sha256: String, val width: Int?, val height: Int?,
    val inlineMode: String, val inlineMediaType: String?, val inlineWidth: Int?, val inlineHeight: Int?, val note: String?,
    val createdAt: Instant, val sentAt: Instant?,
) {
    fun toDto() = AttachmentDto(
        id = id.toString(), name = name, mediaType = mediaType, size = size, kind = kind,
        width = width, height = height, inline = inlineMode, note = note,
    )
}

/** A new attachment, analysed and ready to store. */
class NewAttachment(
    val name: String, val mediaType: String, val kind: String, val data: ByteArray, val sha256: String,
    val width: Int?, val height: Int?,
    val inlineMode: String, val inlineData: ByteArray?, val inlineMediaType: String?, val inlineWidth: Int?, val inlineHeight: Int?,
    val note: String?,
)

sealed interface InsertResult {
    data class Ok(val meta: AttachmentMeta) : InsertResult
    data object TooMany : InsertResult
    data object TooLarge : InsertResult
}

class AttachmentStore(private val db: Db) {
    private val cols = """id, session_id, uploader_id, name, media_type, kind, size_bytes, sha256, width, height,
        inline_mode, inline_media_type, inline_width, inline_height, note, created_at, sent_at"""

    private fun map(rs: ResultSet) = AttachmentMeta(
        rs.uuid("id"), rs.uuid("session_id"), rs.uuid("uploader_id"), rs.getString("name"), rs.getString("media_type"),
        rs.getString("kind"), rs.getLong("size_bytes"), rs.getString("sha256"), rs.intOrNull("width"), rs.intOrNull("height"),
        rs.getString("inline_mode"), rs.getString("inline_media_type"), rs.intOrNull("inline_width"), rs.intOrNull("inline_height"),
        rs.getString("note"), rs.instant("created_at"), rs.instantOrNull("sent_at"),
    )

    /**
     * Stores an unsent attachment unless the uploader's unsent attachments in this session would then exceed
     * [maxCount] or [maxTotalBytes] (one message's worth). Checked under a per-session lock, so parallel uploads
     * cannot overshoot.
     */
    fun insertUnsent(sessionId: UUID, uploaderId: UUID, a: NewAttachment, maxCount: Int, maxTotalBytes: Long): InsertResult {
        val id = UUID.randomUUID()
        val outcome = db.tx { c ->
            c.prepare("SELECT pg_advisory_xact_lock(hashtext(?))", arrayOf<Any?>("attachments:$sessionId")).use { it.executeQuery().close() }
            val (n, total) = c.prepare(
                "SELECT count(*), COALESCE(sum(size_bytes), 0) FROM attachments WHERE session_id = ? AND uploader_id = ? AND sent_at IS NULL",
                arrayOf<Any?>(sessionId, uploaderId),
            ).use { ps -> ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) to rs.getLong(2) } }
            when {
                n >= maxCount -> InsertResult.TooMany
                total + a.data.size > maxTotalBytes -> InsertResult.TooLarge
                else -> {
                    c.prepare(
                        """INSERT INTO attachments(id, session_id, uploader_id, name, media_type, kind, size_bytes, sha256, width, height, data,
                             inline_mode, inline_data, inline_media_type, inline_width, inline_height, note)
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                        arrayOf<Any?>(
                            id, sessionId, uploaderId, a.name, a.mediaType, a.kind, a.data.size.toLong(), a.sha256, a.width, a.height, a.data,
                            a.inlineMode, a.inlineData, a.inlineMediaType, a.inlineWidth, a.inlineHeight, a.note,
                        ),
                    ).use { it.executeUpdate() }
                    null
                }
            }
        }
        return outcome ?: InsertResult.Ok(get(id)!!)
    }

    fun get(id: UUID): AttachmentMeta? = db.queryOne("SELECT $cols FROM attachments WHERE id = ?", id, map = ::map)

    fun forSession(sessionId: UUID, sentOnly: Boolean = true): List<AttachmentMeta> = db.query(
        "SELECT $cols FROM attachments WHERE session_id = ?" + (if (sentOnly) " AND sent_at IS NOT NULL" else "") + " ORDER BY created_at",
        sessionId, map = ::map,
    )

    /** The original bytes. */
    fun data(id: UUID): ByteArray? = db.queryOne("SELECT data FROM attachments WHERE id = ?", id) { it.getBytes("data") }

    /** The bytes the model sees inline: the downscaled copy if there is one, else the original. */
    fun inlineData(id: UUID): ByteArray? =
        db.queryOne("SELECT COALESCE(inline_data, data) AS d FROM attachments WHERE id = ?", id) { it.getBytes("d") }

    /**
     * Marks [ids] as sent in [sessionId] by [uploaderId], all or nothing. Returns them in the given order,
     * or null if any is unknown, someone else's, of another session or already sent.
     */
    fun markSent(sessionId: UUID, uploaderId: UUID, ids: List<UUID>): List<AttachmentMeta>? {
        if (ids.isEmpty()) return emptyList()
        if (ids.toSet().size != ids.size) return null
        val ok = db.tx { c ->
            val placeholders = ids.joinToString(",") { "?" }
            val n = c.prepare(
                "UPDATE attachments SET sent_at = now() WHERE session_id = ? AND uploader_id = ? AND sent_at IS NULL AND id IN ($placeholders)",
                arrayOf<Any?>(sessionId, uploaderId, *ids.toTypedArray()),
            ).use { it.executeUpdate() }
            if (n != ids.size) {
                c.rollback()
                false
            } else true
        }
        if (!ok) return null
        val byId = db.query("SELECT $cols FROM attachments WHERE id IN (${ids.joinToString(",") { "?" }})", *ids.toTypedArray(), map = ::map)
            .associateBy { it.id }
        return ids.map { byId.getValue(it) }
    }

    /** Deletes an unsent attachment of [uploaderId]; false if there is none (or it was already sent). */
    fun deleteUnsent(sessionId: UUID, uploaderId: UUID, id: UUID): Boolean = db.update(
        "DELETE FROM attachments WHERE id = ? AND session_id = ? AND uploader_id = ? AND sent_at IS NULL", id, sessionId, uploaderId,
    ) == 1

    /** Uploaded but never sent, older than [before]: abandoned drafts. */
    fun purgeUnsent(before: Instant): Int = db.update("DELETE FROM attachments WHERE sent_at IS NULL AND created_at < ?", before)
}
