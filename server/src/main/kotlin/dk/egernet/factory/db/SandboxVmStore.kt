package dk.egernet.factory.db

import java.time.Instant
import java.util.UUID

/** Admin approvals of `.agern/sandbox-vm.yml` by content hash, one per repository; the same shape as [DeployApprovalStore]. */
class SandboxVmApprovalStore(private val db: Db) {
    fun get(repo: String): SandboxApprovalRecord? = db.queryOne(
        """SELECT a.*, u.username, u.display_name FROM sandbox_vm_approvals a JOIN users u ON u.id = a.approved_by WHERE a.repo = ?""",
        repo,
    ) {
        SandboxApprovalRecord(
            it.getString("repo"), it.getString("sha256"), it.getString("content"), it.uuid("approved_by"),
            it.getString("username"), it.getString("display_name"), it.instant("approved_at"),
        )
    }

    fun approve(repo: String, sha256: String, content: String, by: UUID) = db.update(
        """INSERT INTO sandbox_vm_approvals(repo, sha256, content, approved_by, approved_at) VALUES (?, ?, ?, ?, now())
           ON CONFLICT (repo) DO UPDATE SET sha256 = EXCLUDED.sha256, content = EXCLUDED.content,
             approved_by = EXCLUDED.approved_by, approved_at = EXCLUDED.approved_at""",
        repo, sha256, content, by,
    )

    fun revoke(repo: String): Boolean = db.update("DELETE FROM sandbox_vm_approvals WHERE repo = ?", repo) > 0
}

/** One VM's lease (docs/ARCHITECTURE.md §5 "Sandbox VMs"). */
data class SandboxVmLeaseRecord(
    val vmKey: String, val repo: String, val runId: UUID, val kind: String, val state: String, val sha256: String,
    val deployedSha: String?, val startedBy: UUID?, val acquiredAt: Instant, val expiresAt: Instant?,
) {
    companion object {
        const val BUILD = "build"
        const val PREVIEW = "preview"
        const val HELD = "held"
        const val RESETTING = "resetting"
    }
}

/** Sandbox VM leases: at most one per VM, taken atomically. */
class SandboxVmLeaseStore(private val db: Db) {
    private fun map(rs: java.sql.ResultSet) = SandboxVmLeaseRecord(
        rs.getString("vm_key"), rs.getString("repo"), rs.uuid("run_id"), rs.getString("kind"), rs.getString("state"),
        rs.getString("sha256"), rs.getString("deployed_sha"), rs.uuidOrNull("started_by"), rs.instant("acquired_at"),
        rs.instantOrNull("expires_at"),
    )

    fun get(vmKey: String): SandboxVmLeaseRecord? = db.queryOne("SELECT * FROM sandbox_vm_leases WHERE vm_key = ?", vmKey, map = ::map)

    fun all(): List<SandboxVmLeaseRecord> = db.query("SELECT * FROM sandbox_vm_leases ORDER BY acquired_at", map = ::map)

    fun forRepo(repo: String): List<SandboxVmLeaseRecord> = db.query("SELECT * FROM sandbox_vm_leases WHERE repo = ?", repo, map = ::map)

    /** Takes the VM's lease; false when it exists already (held by anyone, or resetting). */
    fun acquire(
        vmKey: String, repo: String, runId: UUID, kind: String, sha256: String, startedBy: UUID?, expiresAt: Instant?,
    ): Boolean = db.update(
        """INSERT INTO sandbox_vm_leases(vm_key, repo, run_id, kind, state, sha256, started_by, acquired_at, expires_at)
           VALUES (?, ?, ?, ?, 'held', ?, ?, now(), ?) ON CONFLICT (vm_key) DO NOTHING""",
        vmKey, repo, runId, kind, sha256, startedBy, expiresAt,
    ) > 0

    fun setDeployed(vmKey: String, runId: UUID, sha: String) =
        db.update("UPDATE sandbox_vm_leases SET deployed_sha = ? WHERE vm_key = ? AND run_id = ?", sha, vmKey, runId)

    fun setExpiry(vmKey: String, runId: UUID, expiresAt: Instant) =
        db.update("UPDATE sandbox_vm_leases SET expires_at = ? WHERE vm_key = ? AND run_id = ?", expiresAt, vmKey, runId)

    /** Marks the lease of [runId] on [vmKey] as resetting; false when it is not held by that run (any more). */
    fun markResetting(vmKey: String, runId: UUID): Boolean =
        db.update("UPDATE sandbox_vm_leases SET state = 'resetting' WHERE vm_key = ? AND run_id = ? AND state = 'held'", vmKey, runId) > 0

    fun delete(vmKey: String, runId: UUID): Boolean =
        db.update("DELETE FROM sandbox_vm_leases WHERE vm_key = ? AND run_id = ?", vmKey, runId) > 0

    /** Deletes every lease taken with [repo]'s approval; returns them. */
    fun deleteRepo(repo: String): List<SandboxVmLeaseRecord> =
        db.query("DELETE FROM sandbox_vm_leases WHERE repo = ? RETURNING *", repo, map = ::map)
}
