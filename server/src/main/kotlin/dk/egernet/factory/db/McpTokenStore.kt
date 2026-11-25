package dk.egernet.factory.db

import dk.egernet.factory.McpTokenDto
import dk.egernet.factory.OwnerDto
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** A personal MCP token as stored: never the token itself, only its hash (which is not exposed here either). */
data class McpTokenRecord(
    val id: UUID, val userId: UUID, val repo: String, val name: String,
    val createdAt: Instant, val expiresAt: Instant?, val lastUsedAt: Instant?, val revokedAt: Instant?,
    val ownerUsername: String, val ownerDisplayName: String,
) {
    fun expired(now: Instant): Boolean = expiresAt != null && !expiresAt.isAfter(now)

    fun toDto(now: Instant) = McpTokenDto(
        id = id.toString(), name = name, repo = repo, owner = OwnerDto(ownerUsername, ownerDisplayName),
        createdAt = createdAt.iso(), lastUsedAt = lastUsedAt?.iso(), expiresAt = expiresAt?.iso(), expired = expired(now),
    )
}

/** Personal MCP tokens (docs/ARCHITECTURE.md §7 "Personal tokens"), looked up by the SHA-256 of the bearer. */
class McpTokenStore(private val db: Db) {
    private val select = "SELECT t.*, u.username, u.display_name FROM mcp_tokens t JOIN users u ON u.id = t.user_id"

    private fun map(rs: ResultSet) = McpTokenRecord(
        rs.uuid("id"), rs.uuid("user_id"), rs.getString("repo"), rs.getString("name"),
        rs.instant("created_at"), rs.instantOrNull("expires_at"), rs.instantOrNull("last_used_at"), rs.instantOrNull("revoked_at"),
        rs.getString("username"), rs.getString("display_name"),
    )

    fun create(userId: UUID, repo: String, name: String, tokenHash: String, createdAt: Instant, expiresAt: Instant?): McpTokenRecord {
        val id = UUID.randomUUID()
        db.update(
            "INSERT INTO mcp_tokens(id, user_id, repo, name, token_hash, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, userId, repo, name, tokenHash, createdAt, expiresAt,
        )
        return byId(id)!!
    }

    fun byId(id: UUID): McpTokenRecord? = db.queryOne("$select WHERE t.id = ?", id, map = ::map)

    /** Any row with this hash, revoked and expired ones included (they are "known" to the throttle). */
    fun byHash(tokenHash: String): McpTokenRecord? = db.queryOne("$select WHERE t.token_hash = ?", tokenHash, map = ::map)

    /** The user's tokens that are not revoked, newest first. */
    fun listForUser(userId: UUID): List<McpTokenRecord> =
        db.query("$select WHERE t.user_id = ? AND t.revoked_at IS NULL ORDER BY t.created_at DESC, t.id", userId, map = ::map)

    /** Every user's tokens that are not revoked, newest first. */
    fun listAll(): List<McpTokenRecord> =
        db.query("$select WHERE t.revoked_at IS NULL ORDER BY t.created_at DESC, t.id", map = ::map)

    fun countActive(userId: UUID): Int =
        db.queryOne("SELECT count(*) AS n FROM mcp_tokens WHERE user_id = ? AND revoked_at IS NULL", userId) { it.getInt("n") } ?: 0

    /** True when [userId] has a token for [repo] that is not revoked and has this name (case-insensitive). */
    fun nameTaken(userId: UUID, repo: String, name: String): Boolean = db.queryOne(
        "SELECT 1 AS x FROM mcp_tokens WHERE user_id = ? AND repo = ? AND lower(name) = lower(?) AND revoked_at IS NULL",
        userId, repo, name,
    ) { true } ?: false

    /**
     * Marks the token revoked if it is not already; restricted to [userId]'s tokens when given. Returns the token as it
     * was before (null: no such token that is still active, or not that user's).
     */
    fun revoke(id: UUID, at: Instant, userId: UUID? = null): McpTokenRecord? {
        val before = byId(id)?.takeIf { it.revokedAt == null && (userId == null || it.userId == userId) } ?: return null
        val n = if (userId == null) {
            db.update("UPDATE mcp_tokens SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL", at, id)
        } else {
            db.update("UPDATE mcp_tokens SET revoked_at = ? WHERE id = ? AND user_id = ? AND revoked_at IS NULL", at, id, userId)
        }
        return before.takeIf { n > 0 }
    }

    /** Records a use, at most once per [minInterval] (the row is only written when the last use is older). */
    fun touch(id: UUID, now: Instant, minInterval: java.time.Duration = java.time.Duration.ofMinutes(1)): Boolean = db.update(
        "UPDATE mcp_tokens SET last_used_at = ? WHERE id = ? AND (last_used_at IS NULL OR last_used_at < ?)",
        now, id, now.minus(minInterval),
    ) > 0

    /** Deletes the rows revoked before [cutoff]; returns how many. */
    fun purgeRevokedBefore(cutoff: Instant): Int =
        db.update("DELETE FROM mcp_tokens WHERE revoked_at IS NOT NULL AND revoked_at < ?", cutoff)
}
