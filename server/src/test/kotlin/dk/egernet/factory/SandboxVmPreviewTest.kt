package dk.egernet.factory

import dk.egernet.factory.db.SandboxVmLeaseStore
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.deploy.DeployExecResult
import dk.egernet.factory.sandboxvm.SandboxVmDefinitions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Try it on the sandbox VM": permissions, the status precondition, its states, expiry, its end with the run, restart recovery. */
class SandboxVmPreviewTest {
    private val worlds = CopyOnWriteArrayList<TestWorld>()
    private fun world(tweak: (Config) -> Config = { it }) = TestWorld(twoFactor = false, tweak = tweak).also { worlds += it }

    @AfterTest fun tearDown() = worlds.forEach { it.close() }

    private val def = "name: staging\nhost: vm.example.net\nuser: agern\nhostKey: ${HostKeys.ed25519()}\nurl: https://vm.example.net/\n" +
        "checkout: tar xzf archive\nsetup: restart app\nreset: restore-db\n"

    private fun ready(w: TestWorld) {
        w.sandboxVmFile(def)
        val root = w.user("root", admin = true)
        runBlocking {
            w.services.sandboxVms.approve(root, "demo-app", SandboxVmDefinitions.sha256(def))
            w.services.deploys.generateKey(root, "demo-app")
        }
    }

    /** A run of alice's that waits for a human (the approver escalates). */
    private fun needsHuman(w: TestWorld, issue: Int = 1): UUID {
        w.launcher.stage = { p ->
            p.init()
            when (p.spec.kind) {
                "spec-writer" -> p.result("## Spec\nDo it.")
                "builder" -> p.result("Built it.")
                "approver" -> p.result(null, buildJsonObject { put("verdict", "ESCALATE"); put("summary", "Look at it") })
                else -> p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
            }
            p.exit(0)
        }
        val run = runBlocking { w.services.orchestrator.start(w.user("alice"), "demo-app", issue, null) }
        assertEquals(RunStatus.NEEDS_HUMAN, w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        return run.id
    }

    private fun preview(w: TestWorld, runId: UUID) = w.services.runs.get(runId)!!.sandboxVm?.preview
    private fun leases(w: TestWorld) = SandboxVmLeaseStore(w.services.db).all()
    private fun code(block: suspend () -> Unit) = assertFailsWith<ApiException> { runBlocking { block() } }.let { it.status.value to it.code }

    private fun eventually(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(20)
        }
    }

    private fun start(w: TestWorld, user: UserRecord, runId: UUID) = runBlocking { w.services.sandboxVms.startPreview(user, runId) }
    private fun release(w: TestWorld, user: UserRecord, runId: UUID) = runBlocking { w.services.sandboxVms.releasePreview(user, runId) }

    @Test
    fun `owner or admin start a preview of a waiting run, it becomes ready, is re-deployed and released with a reset`() {
        val w = world()
        ready(w)
        val runId = needsHuman(w)
        val alice = w.user("alice"); val bob = w.user("bob"); val root = w.user("root", admin = true)
        assertEquals(403 to "forbidden", code { w.services.sandboxVms.startPreview(bob, runId) })

        val started = start(w, alice, runId)
        assertEquals("deploying", started.sandboxVm!!.preview!!.state)
        eventually("ready") { preview(w, runId)?.state == "ready" }
        preview(w, runId)!!.let {
            assertEquals("0000000000000000000000000000000000000001", it.sha)
            assertEquals("https://vm.example.net/", it.url)
            assertEquals("alice", it.startedBy!!.username)
            assertNotNull(it.expiresAt)
        }
        assertEquals("preview", leases(w).single().kind)
        assertTrue(w.vmTransport.calls.any { it.startsWith("copy staging") })
        // Again while ready: deploys again and renews; an admin may do it too.
        val before = w.vmTransport.calls.count { it.endsWith("restart app") }
        start(w, root, runId)
        eventually("re-deployed") { w.vmTransport.calls.count { it.endsWith("restart app") } == before + 1 && preview(w, runId)?.state == "ready" }
        assertEquals(1, leases(w).size)

        assertEquals(403 to "forbidden", code { w.services.sandboxVms.releasePreview(bob, runId) })
        assertEquals("released", release(w, alice, runId).sandboxVm!!.preview!!.state)
        eventually("lease gone") { leases(w).isEmpty() }
        assertEquals(1, w.vmTransport.calls.count { it.endsWith("restore-db") })
        assertEquals(404 to "not_found", code { w.services.sandboxVms.releasePreview(alice, runId) })
    }

    @Test
    fun `preconditions, a busy VM and a failed deploy`() {
        val w = world()
        ready(w)
        val alice = w.user("alice")
        val runId = needsHuman(w)
        // Another run (here: a stale row of the same VM held by another run) makes the VM busy.
        val other = needsHuman(w, issue = 2)
        SandboxVmLeaseStore(w.services.db).acquire("vm.example.net:22", "demo-app", other, "build", "x", null, null)
        assertEquals(409 to "sandbox_vm_busy", code { w.services.sandboxVms.startPreview(alice, runId) })
        w.services.db.update("DELETE FROM sandbox_vm_leases")

        w.vmTransport.onExec = { _, cmd, _ ->
            if (cmd.endsWith("restart app")) DeployExecResult(1, null, "app failed to start\n", false, false, 3) else DeployExecResult(0, null, "", false, false, 1)
        }
        start(w, alice, runId)
        eventually("failed") { preview(w, runId)?.state == "failed" }
        preview(w, runId)!!.let {
            assertEquals("setup", it.failedStep); assertContains(it.output!!, "app failed to start"); assertNotNull(it.error)
        }
        eventually("lease gone after the failure") { leases(w).isEmpty() }
        assertEquals(1, w.vmTransport.calls.count { it.endsWith("restore-db") })

        // Not waiting for a human: 409 run_not_waiting; off: 409 sandbox_vm_disabled.
        runBlocking { w.services.orchestrator.cancel(alice, other) }
        assertEquals(409 to "run_not_waiting", code { w.services.sandboxVms.startPreview(alice, other) })
        val off = world { it.copy(sandboxVm = SandboxVmConfig(mode = "off")) }
        val offRun = needsHuman(off)
        assertEquals(409 to "sandbox_vm_disabled", code { off.services.sandboxVms.startPreview(off.user("alice"), offRun) })
        // No key: 409 sandbox_vm_key_missing.
        val nokey = world()
        nokey.sandboxVmFile(def)
        runBlocking { nokey.services.sandboxVms.approve(nokey.user("root", admin = true), "demo-app", SandboxVmDefinitions.sha256(def)) }
        val nkRun = needsHuman(nokey)
        assertEquals(409 to "sandbox_vm_key_missing", code { nokey.services.sandboxVms.startPreview(nokey.user("alice"), nkRun) })
    }

    @Test
    fun `a preview expires, and ends when the run is approved`() {
        val w = world { it.copy(sandboxVm = SandboxVmConfig(previewMs = 400)) }
        ready(w)
        val alice = w.user("alice")
        val runId = needsHuman(w)
        start(w, alice, runId)
        eventually("expired") { preview(w, runId)?.state == "released" }
        assertEquals(true, preview(w, runId)!!.expired)
        eventually("lease gone") { leases(w).isEmpty() }

        val w2 = world()
        ready(w2)
        val a2 = w2.user("alice")
        val r2 = needsHuman(w2)
        start(w2, a2, r2)
        eventually("ready") { preview(w2, r2)?.state == "ready" }
        runBlocking { w2.services.orchestrator.approve(a2, r2) }
        eventually("released with the approval") { preview(w2, r2)?.state == "released" }
        assertEquals(false, preview(w2, r2)!!.expired)
        eventually("lease gone") { leases(w2).isEmpty() }
        assertEquals(1, w2.vmTransport.calls.count { it.endsWith("restore-db") })
    }

    @Test
    fun `at server start a ready preview of a waiting run survives, every other lease ends`() {
        val w = world()
        ready(w)
        val alice = w.user("alice")
        val runId = needsHuman(w)
        start(w, alice, runId)
        eventually("ready") { preview(w, runId)?.state == "ready" }
        val store = SandboxVmLeaseStore(w.services.db)
        val kept = store.all().single()
        // A second VM's build lease of a run that ended (seeded as a restart would find it).
        w.services.db.update("INSERT INTO sandbox_vm_leases(vm_key, repo, run_id, kind, state, sha256) VALUES ('other.example.net:22', 'demo-app', ?, 'build', 'held', 'x')", runId)
        // Recovery as at a restart: a fresh manager on the same database.
        val s = w.services
        val fresh = dk.egernet.factory.sandboxvm.SandboxVmManager(
            s.cfg, w.vmTransport, dk.egernet.factory.db.DeployKeyStore(s.db), s.box, dk.egernet.factory.db.SandboxVmApprovalStore(s.db), store,
            s.runs, s.access, s.git, s.scope, publishSystem = { _, _ -> },
            checkoutOf = { run -> s.workspaces.runDir(run.ownerUsername, run.repo, run.id) to s.workspaces.home(run.ownerUsername) },
        )
        fresh.recover()
        eventually("the other lease ends") { store.all().map { it.vmKey } == listOf(kept.vmKey) }
        assertEquals(kept.expiresAt, store.all().single().expiresAt)
        assertEquals("ready", preview(w, runId)!!.state)
        // The kept lease is usable: releasing it through the fresh manager resets it.
        runBlocking { fresh.releasePreview(alice, runId) }
        eventually("released") { store.all().isEmpty() }

        // An expired preview lease ends at start.
        w.services.db.update("INSERT INTO sandbox_vm_leases(vm_key, repo, run_id, kind, state, sha256, expires_at) VALUES ('vm.example.net:22', 'demo-app', ?, 'preview', 'held', ?, ?)",
            runId, kept.sha256, Instant.now().minusSeconds(60))
        fresh.recover()
        eventually("expired lease ends") { store.all().isEmpty() }
        assertNull(store.get("vm.example.net:22"))
    }
}
