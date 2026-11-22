package dk.egernet.factory.db

import dk.egernet.factory.GeneratedImageDto
import dk.egernet.factory.ImageSelectionDto
import dk.egernet.factory.OwnerDto
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** Metadata of a generated image (without its bytes), with its owner and whether its selection is locked. */
data class ImageMeta(
    val id: UUID, val repo: String, val ownerId: UUID, val ownerUsername: String, val ownerDisplayName: String,
    val origin: String, val sessionId: UUID, val runId: UUID?, val requestId: UUID,
    val provider: String, val model: String, val prompt: String, val purpose: String,
    val mediaType: String, val size: Long, val sha256: String, val width: Int, val height: Int, val transparent: Boolean,
    val issueNumber: Int?, val name: String?, val selectedAt: Instant?, val usedByRunId: UUID?, val locked: Boolean,
    val createdAt: Instant,
) {
    val selected: Boolean get() = issueNumber != null

    fun toDto() = GeneratedImageDto(
        id = id.toString(), repo = repo, origin = origin, sessionId = sessionId.toString(), owner = OwnerDto(ownerUsername, ownerDisplayName),
        provider = provider, model = model, prompt = prompt, purpose = purpose, mediaType = mediaType, size = size,
        width = width, height = height, transparent = transparent, createdAt = createdAt.iso(),
        selection = if (issueNumber != null && name != null) ImageSelectionDto(issueNumber, name, usedByRunId?.toString(), locked) else null,
    )
}

/** A new image, checked (type sniffed, dimensions read) and ready to store. */
class NewImage(
    val repo: String, val ownerId: UUID, val origin: String, val sessionId: UUID, val runId: UUID?, val requestId: UUID,
    val provider: String, val model: String, val prompt: String, val purpose: String,
    val mediaType: String, val data: ByteArray, val sha256: String, val width: Int, val height: Int, val transparent: Boolean,
)

sealed interface SelectResult {
    data class Ok(val meta: ImageMeta) : SelectResult
    /** The issue already has [max] selected images. */
    data class TooMany(val max: Int) : SelectResult
    /** Not selectable (any more): gone, not the caller's, from a run, or its selection is locked. */
    data object Refused : SelectResult
}

/**
 * Generated images (docs/ARCHITECTURE.md §6 "Generated images"). Conditions of selection changes are part of the
 * statements' `WHERE` clauses, so a concurrent run claiming an image cannot be overtaken by a stale check.
 */
class ImageStore(private val db: Db) {
    /** Never `data`: metadata queries do not load the bytes. */
    private val select = """SELECT g.id, g.repo, g.owner_id, u.username AS owner_username, u.display_name AS owner_display_name,
          g.origin, g.session_id, g.run_id, g.request_id, g.provider, g.model, g.prompt, g.purpose, g.media_type, g.size_bytes,
          g.sha256, g.width, g.height, g.transparent, g.issue_number, g.name, g.selected_at, g.used_by_run_id, g.created_at,
          (g.used_by_run_id IS NOT NULL AND ur.status IS NOT NULL AND ur.status NOT IN ('failed', 'cancelled')) AS locked
        FROM generated_images g JOIN users u ON u.id = g.owner_id LEFT JOIN runs ur ON ur.id = g.used_by_run_id"""

    /** SQL condition (on the bare table) for "the selection is locked by a run that is not failed or cancelled". */
    private val lockedCond = """(used_by_run_id IS NOT NULL AND EXISTS (SELECT 1 FROM runs r WHERE r.id = used_by_run_id
        AND r.status NOT IN ('failed', 'cancelled')))"""

    private fun map(rs: ResultSet) = ImageMeta(
        rs.uuid("id"), rs.getString("repo"), rs.uuid("owner_id"), rs.getString("owner_username"), rs.getString("owner_display_name"),
        rs.getString("origin"), rs.uuid("session_id"), rs.uuidOrNull("run_id"), rs.uuid("request_id"),
        rs.getString("provider"), rs.getString("model"), rs.getString("prompt"), rs.getString("purpose"),
        rs.getString("media_type"), rs.getLong("size_bytes"), rs.getString("sha256"), rs.getInt("width"), rs.getInt("height"),
        rs.getBoolean("transparent"), rs.intOrNull("issue_number"), rs.getString("name"), rs.instantOrNull("selected_at"),
        rs.uuidOrNull("used_by_run_id"), rs.getBoolean("locked"), rs.instant("created_at"),
    )

    fun insert(i: NewImage): ImageMeta {
        val id = UUID.randomUUID()
        db.update(
            """INSERT INTO generated_images(id, repo, owner_id, origin, session_id, run_id, request_id, provider, model, prompt, purpose,
                 media_type, size_bytes, sha256, width, height, transparent, data)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            id, i.repo, i.ownerId, i.origin, i.sessionId, i.runId, i.requestId, i.provider, i.model, i.prompt, i.purpose,
            i.mediaType, i.data.size.toLong(), i.sha256, i.width, i.height, i.transparent, i.data,
        )
        return get(id)!!
    }

    fun get(id: UUID): ImageMeta? = db.queryOne("$select WHERE g.id = ?", id, map = ::map)

    fun data(id: UUID): ByteArray? = db.queryOne("SELECT data FROM generated_images WHERE id = ?", id) { it.getBytes("data") }

    fun forSession(sessionId: UUID): List<ImageMeta> = db.query("$select WHERE g.session_id = ? ORDER BY g.ordinal", sessionId, map = ::map)

    fun forRun(runId: UUID): List<ImageMeta> = db.query("$select WHERE g.run_id = ? ORDER BY g.ordinal", runId, map = ::map)

    fun forIssue(repo: String, issue: Int): List<ImageMeta> =
        db.query("$select WHERE g.repo = ? AND g.issue_number = ? ORDER BY g.selected_at, g.ordinal", repo, issue, map = ::map)

    /** Selected images per issue of [repo]. */
    fun countsByIssue(repo: String): Map<Int, Int> = db.query(
        "SELECT issue_number, count(*) AS n FROM generated_images WHERE repo = ? AND issue_number IS NOT NULL GROUP BY issue_number", repo,
    ) { it.getInt("issue_number") to it.getInt("n") }.toMap()

    /**
     * Selects [id] for [issue] under [name] (or moves/renames it): only the owner's chat images whose selection is not
     * locked, and only while the issue has fewer than [max] other selected images. A change of a selection taken by a
     * failed or cancelled run frees it from that run.
     */
    fun select(id: UUID, ownerId: UUID, repo: String, issue: Int, name: String, max: Int): SelectResult {
        val res = db.tx { c ->
            c.prepare("SELECT pg_advisory_xact_lock(hashtext(?))", arrayOf<Any?>("images:$repo#$issue")).use { it.executeQuery().close() }
            val others = c.prepare(
                "SELECT count(*) FROM generated_images WHERE repo = ? AND issue_number = ? AND id <> ?", arrayOf<Any?>(repo, issue, id),
            ).use { ps -> ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
            if (others >= max) return@tx SelectResult.TooMany(max)
            val n = c.prepare(
                """UPDATE generated_images SET issue_number = ?, name = ?, selected_at = now(), used_by_run_id = NULL
                   WHERE id = ? AND owner_id = ? AND repo = ? AND origin = 'chat' AND NOT $lockedCond""",
                arrayOf<Any?>(issue, name, id, ownerId, repo),
            ).use { it.executeUpdate() }
            if (n == 1) null else SelectResult.Refused
        }
        return res ?: SelectResult.Ok(get(id)!!)
    }

    /** Unselects the owner's chat image unless its selection is locked. True when it is (now) unselected. */
    fun unselect(id: UUID, ownerId: UUID): Boolean = db.update(
        """UPDATE generated_images SET issue_number = NULL, name = NULL, selected_at = NULL, used_by_run_id = NULL
           WHERE id = ? AND owner_id = ? AND origin = 'chat' AND NOT $lockedCond""",
        id, ownerId,
    ) == 1

    /** Deletes the owner's unselected chat image. */
    fun deleteUnselected(id: UUID, ownerId: UUID): Boolean = db.update(
        "DELETE FROM generated_images WHERE id = ? AND owner_id = ? AND origin = 'chat' AND issue_number IS NULL", id, ownerId,
    ) == 1

    /**
     * A run of ([repo], [issue]) takes the issue's selected images: those not used by another run that is still
     * active or merged. Returns what the run holds now, in selection order.
     */
    fun claimForRun(repo: String, issue: Int, runId: UUID): List<ImageMeta> {
        db.update(
            """UPDATE generated_images SET used_by_run_id = ?
               WHERE repo = ? AND issue_number = ? AND (used_by_run_id = ? OR NOT $lockedCond)""",
            runId, repo, issue, runId,
        )
        return db.query("$select WHERE g.used_by_run_id = ? ORDER BY g.selected_at, g.ordinal", runId, map = ::map)
    }

    /** When a chat is closed: its images that are not selected for an issue. Returns how many went. */
    fun deleteUnselectedOfSession(sessionId: UUID): Int =
        db.update("DELETE FROM generated_images WHERE session_id = ? AND issue_number IS NULL", sessionId)
}
