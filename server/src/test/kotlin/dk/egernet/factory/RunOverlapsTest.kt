package dk.egernet.factory

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Warn early (docs/ARCHITECTURE.md §6 "Other active runs") and "Blocked on" specs (§6 "Blocked on another issue"): a run
 * lists the other active runs of its project and the files their specs share; a spec blocked on an open issue stops the
 * run before the builder.
 */
class RunOverlapsTest {
    private val world = TestWorld(twoFactor = false)
    private val s = world.services
    private val gh = world.github
    private val alice = world.user("alice")

    /** The spec each issue's spec-writer writes, by issue title. */
    private val specs = ConcurrentHashMap<String, String>()
    /** Issue titles whose builder hangs until killed. */
    private val hang = ConcurrentHashMap.newKeySet<String>()

    @AfterTest fun tearDown() {
        world.close()
    }

    init {
        world.launcher.stage = { p ->
            val prompt = p.spec.prompt.orEmpty()
            p.init()
            when (p.spec.kind) {
                "spec-writer" -> p.result(specs.entries.firstOrNull { prompt.contains(it.key) }?.value ?: "## Spec\nDo it.")
                "builder" -> {
                    if (hang.any { prompt.contains(it) }) while (!p.killed) Thread.sleep(20)
                    p.result("Built it.")
                }
                "approver" -> p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "fine") })
                else -> p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "fine"); put("findings", buildJsonArray { }) })
            }
            p.exit(0)
        }
    }

    private fun await(id: java.util.UUID) = world.awaitRun(id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN, 15_000)

    @Test
    fun `a run started next to another lists it, with references and shared files, and still runs`() {
        val first = gh.addIssue("demo-app", "First change", "body").number
        specs["First change"] = "## Spec\nChange `web/src/pages/Run.tsx` and `server/src/Main.kt`."
        hang += "First change"
        val r1 = runBlocking { s.factory.startRun(alice, "demo-app", first, null) }
        assertTrue(r1.overlaps.isEmpty())
        world.awaitRun(java.util.UUID.fromString(r1.id), setOf(RunStatus.BUILDING))

        val second = gh.addIssue("demo-app", "Second change", "Builds on #$first.").number
        specs["Second change"] = "## Spec\nAlso touch `web/src/pages/Run.tsx` and `web/src/api/types.ts`."
        val chat = s.sessions.create("demo-app", "chat", "sonnet", SessionStatus.IDLE, alice.id, "chat", null, null, null)
        val reply = runBlocking { s.factory.handleCommand(chat, alice, "/factory run $second") }
        val r2 = s.runs.list("demo-app", activeOnly = true).first { it.issueNumber == second }
        val o = s.runs.toDto(r2).overlaps.single()
        assertEquals(r1.id, o.runId)
        assertEquals(first, o.issueNumber)
        assertEquals("First change", o.issueTitle)
        assertEquals("alice", o.owner)
        assertTrue(o.references)
        assertTrue(
            reply.endsWith(
                " Other active runs in this project: #$first First change (run ${r1.id}, building, @alice) — referenced by this issue. " +
                    "Their changes may conflict with this one; Agern brings the branch up to date before merging.",
            ),
            reply,
        )
        // After the spec: the files both specs name.
        val done = await(r2.id)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertEquals(listOf("web/src/pages/Run.tsx"), s.runs.toDto(done).overlaps.single().files)
        runBlocking { s.orchestrator.cancel(alice, java.util.UUID.fromString(r1.id)) }
        // A third issue that references nothing: listed without the mark (the second is no longer active).
        val third = gh.addIssue("demo-app", "Third change", "body").number
        hang += "Third change"
        val r3 = runBlocking { s.factory.startRun(alice, "demo-app", third, null) }
        assertTrue(r3.overlaps.isEmpty(), r3.overlaps.toString())
        runBlocking { s.orchestrator.cancel(alice, java.util.UUID.fromString(r3.id)) }
        assertEquals("", FactoryService.overlapSentence(r3))
    }

    @Test
    fun `a spec blocked on an open issue stops the run before the builder, a closed one does not`() {
        val blocker = gh.addIssue("demo-app", "Foundation", "body").number
        val n = gh.addIssue("demo-app", "Needs foundation", "body").number
        specs["Needs foundation"] = "Blocked on: #$blocker\n\nIt needs the API that #$blocker adds.\n\n## Summary\nLater."
        val run = await(runBlocking { s.orchestrator.start(alice, "demo-app", n, null) }.id)
        assertEquals(RunStatus.FAILED, run.status)
        assertEquals(FailureKind.BLOCKED, run.failureKind)
        assertEquals(
            "Blocked on #$blocker (open): It needs the API that #$blocker adds. Start the run again when the blocking issue is merged.",
            run.error,
        )
        assertTrue(world.launcher.byRole("builder").isEmpty(), "no builder ran")
        val stages = s.runs.stages(run.id)
        assertEquals("done", stages.single { it.role == "spec-writer" }.status)
        assertTrue(stages.filter { it.role != "spec-writer" }.all { it.status == "skipped" }, stages.toString())
        val opts = runBlocking { s.factory.retryOptions(alice, run.id) }
        assertEquals(RetryMode.START_OVER, opts.recommended)
        assertEquals(run.error, opts.reason)

        gh.client("fake-alice").closeIssue("test-org", "demo-app", blocker)
        val again = await(runBlocking { s.orchestrator.start(alice, "demo-app", n, null) }.id)
        assertEquals(RunStatus.MERGED, again.status, again.error)
        assertFalse(world.launcher.byRole("builder").isEmpty())
    }
}
