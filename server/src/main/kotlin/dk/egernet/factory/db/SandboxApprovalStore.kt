package dk.egernet.factory.db

import java.time.Instant
import java.util.UUID

data class SandboxApprovalRecord(
    val repo: String, val sha256: String, val content: String,
    val approvedBy: UUID, val approvedByUsername: String, val approvedByDisplayName: String, val approvedAt: Instant,
)

/** Admin approvals of `.agern/sandbox.yml` by content hash, one per repository (docs/ARCHITECTURE.md §5 "Sandboxes"). */
class SandboxApprovalStore(private val db: Db) {
    fun get(repo: String): SandboxApprovalRecord? = db.queryOne(
        """SELECT a.*, u.username, u.display_name FROM sandbox_approvals a JOIN users u ON u.id = a.approved_by WHERE a.repo = ?""",
        repo,
    ) {
        SandboxApprovalRecord(
            it.getString("repo"), it.getString("sha256"), it.getString("content"), it.uuid("approved_by"),
            it.getString("username"), it.getString("display_name"), it.instant("approved_at"),
        )
    }

    /** Approves exactly [content] (whose hash is [sha256]) for [repo], replacing an earlier approval. */
    fun approve(repo: String, sha256: String, content: String, by: UUID) = db.update(
        """INSERT INTO sandbox_approvals(repo, sha256, content, approved_by, approved_at) VALUES (?, ?, ?, ?, now())
           ON CONFLICT (repo) DO UPDATE SET sha256 = EXCLUDED.sha256, content = EXCLUDED.content,
             approved_by = EXCLUDED.approved_by, approved_at = EXCLUDED.approved_at""",
        repo, sha256, content, by,
    )

    /** True when there was an approval to remove. */
    fun revoke(repo: String): Boolean = db.update("DELETE FROM sandbox_approvals WHERE repo = ?", repo) > 0
}
