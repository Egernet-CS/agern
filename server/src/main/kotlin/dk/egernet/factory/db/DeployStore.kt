package dk.egernet.factory.db

import java.time.Instant
import java.util.UUID

/** A project's deploy key pair. [privateKeyEnc] is SecretBox ciphertext; it is never shown or logged. */
class DeployKeyRecord(
    val repo: String, val publicKey: String, val fingerprint: String, val privateKeyEnc: ByteArray,
    val createdBy: UUID, val createdByUsername: String, val createdByDisplayName: String, val createdAt: Instant,
) {
    override fun toString() = "DeployKeyRecord(repo=$repo, fingerprint=$fingerprint, createdBy=$createdByUsername)"
}

/** Deploy keys, one per repository (docs/ARCHITECTURE.md §5 "Deploy targets"). */
class DeployKeyStore(private val db: Db) {
    fun get(repo: String): DeployKeyRecord? = db.queryOne(
        """SELECT k.*, u.username, u.display_name FROM deploy_keys k JOIN users u ON u.id = k.created_by WHERE k.repo = ?""",
        repo,
    ) {
        DeployKeyRecord(
            it.getString("repo"), it.getString("public_key"), it.getString("fingerprint"), it.getBytes("private_key_enc"),
            it.uuid("created_by"), it.getString("username"), it.getString("display_name"), it.instant("created_at"),
        )
    }

    /** Stores the repository's first key; false when it already has one. */
    fun insert(repo: String, publicKey: String, fingerprint: String, privateKeyEnc: ByteArray, by: UUID): Boolean = db.update(
        """INSERT INTO deploy_keys(repo, public_key, fingerprint, private_key_enc, created_by, created_at)
           VALUES (?, ?, ?, ?, ?, now()) ON CONFLICT (repo) DO NOTHING""",
        repo, publicKey, fingerprint, privateKeyEnc, by,
    ) > 0

    /** Replaces the repository's key in one statement (the old private key is gone at once); false when it has none. */
    fun replace(repo: String, publicKey: String, fingerprint: String, privateKeyEnc: ByteArray, by: UUID): Boolean = db.update(
        """UPDATE deploy_keys SET public_key = ?, fingerprint = ?, private_key_enc = ?, created_by = ?, created_at = now() WHERE repo = ?""",
        publicKey, fingerprint, privateKeyEnc, by, repo,
    ) > 0
}

/** Admin approvals of `.agern/deploy.yml` by content hash, one per repository; the same shape as [SandboxApprovalStore]. */
class DeployApprovalStore(private val db: Db) {
    fun get(repo: String): SandboxApprovalRecord? = db.queryOne(
        """SELECT a.*, u.username, u.display_name FROM deploy_approvals a JOIN users u ON u.id = a.approved_by WHERE a.repo = ?""",
        repo,
    ) {
        SandboxApprovalRecord(
            it.getString("repo"), it.getString("sha256"), it.getString("content"), it.uuid("approved_by"),
            it.getString("username"), it.getString("display_name"), it.instant("approved_at"),
        )
    }

    fun approve(repo: String, sha256: String, content: String, by: UUID) = db.update(
        """INSERT INTO deploy_approvals(repo, sha256, content, approved_by, approved_at) VALUES (?, ?, ?, ?, now())
           ON CONFLICT (repo) DO UPDATE SET sha256 = EXCLUDED.sha256, content = EXCLUDED.content,
             approved_by = EXCLUDED.approved_by, approved_at = EXCLUDED.approved_at""",
        repo, sha256, content, by,
    )

    fun revoke(repo: String): Boolean = db.update("DELETE FROM deploy_approvals WHERE repo = ?", repo) > 0
}
