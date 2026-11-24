package dk.egernet.factory

import dk.egernet.factory.db.ReleaseRecord
import dk.egernet.factory.sandbox.SANDBOX_FILE
import dk.egernet.factory.sandbox.SandboxDefinitions
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Releases (docs/ARCHITECTURE.md §6 "Releases") and sandboxes (§5 "Sandboxes") together. */
class ReleaseSandboxTest {
    private val clock = MutableClock(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
    private val world = TestWorld(twoFactor = false, sandbox = true, clock = clock)
    private val s = world.services
    private val alice = world.user("alice")
    private val rt = world.sandboxRuntime

    @AfterTest fun tearDown() {
        hang.clear()
        world.close()
    }

    private val definition = "image: docker.io/library/eclipse-temurin:21-jdk\nsetup: echo setup\n" +
        "services:\n  postgres:\n    image: docker.io/library/postgres:17\n"

    /** Issue titles whose builder hangs (after its sandbox command) until killed. */
    private val hang: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** For each builder: its run's own containers, and every container that existed, when its command ran. */
    private val seen = CopyOnWriteArrayList<Pair<String, Set<String>>>()

    private fun token(p: FakeProcess): String = AppJson.parseToJsonElement(p.spec.mcpConfigJson).jsonObject["mcpServers"]!!.jsonObject["factory"]!!
        .jsonObject["headers"]!!.jsonObject["Authorization"]!!.jsonPrimitive.content.removePrefix("Bearer ")

    private fun exec(p: FakeProcess, command: String): Pair<Boolean, String> {
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
            putJsonObject("params") { put("name", "factory_sandbox_exec"); putJsonObject("arguments") { put("command", command) } }
        }
        val res = AppJson.parseToJsonElement(runBlocking { s.mcp.handle(token(p), body.toString()) }.second!!).jsonObject["result"]!!.jsonObject
        return (res["isError"]?.jsonPrimitive?.content == "true") to res["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
    }

    init {
        world.launcher.stage = { p ->
            val title = Regex("Issue #\\d+: (.+)").find(p.spec.prompt ?: "")?.groupValues?.get(1)?.trim() ?: ""
            p.init()
            when (p.spec.kind) {
                "spec-writer" -> { p.result("## Spec\nDo it."); p.exit(0) }
                "builder" -> {
                    val (err, text) = exec(p, "make test $title")
                    check(!err) { text }
                    seen += title to rt.containers.keys.toSet()
                    if (title in hang) { while (!p.killed) Thread.sleep(10) } else { p.result("Built and tested in the sandbox."); p.exit(0) }
                }
                "approver" -> { p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") }); p.exit(0) }
                else -> { p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) }); p.exit(0) }
            }
        }
        world.sandboxFile(definition)
        runBlocking { s.sandboxes.approve(world.user("root", admin = true), "demo-app", SandboxDefinitions.sha256(definition)) }
    }

    private fun release(name: String, issues: List<Int>): ReleaseRecord = runBlocking {
        val r = s.releases.create(alice, "demo-app", name, "")
        s.releases.assign(alice, "demo-app", issues, r.id)
        s.releases.start(alice, r.id)
        s.releaseStore.get(r.id)!!
    }

    private fun awaitRelease(id: UUID, cond: (ReleaseRecord) -> Boolean): ReleaseRecord = runBlocking {
        withTimeout(20_000) {
            while (true) {
                val r = s.releaseStore.get(id)!!
                if (cond(r)) return@withTimeout r
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE") error("timeout")
        }
    }

    @Test
    fun `each release run gets its own sandbox from the default branch's definition, removed before the next issue starts`() {
        val a = world.github.addIssue("demo-app", "First", "body").number
        val b = world.github.addIssue("demo-app", "Second", "body").number
        val r = release("2.0", listOf(a, b))
        val done = awaitRelease(r.id) { rel -> rel.issues.all { it.state == "merged" } }

        val runIds = listOf(a, b).map { n -> done.issues.single { it.number == n }.runId!! }
        for (id in runIds) {
            val run = s.runs.get(id)!!
            assertEquals(RunStatus.MERGED, run.status)
            assertEquals("release/2.0", run.baseBranch)
            val sb = s.runs.toDto(run).sandbox!!
            assertEquals("approved", sb.state, sb.reason)
            assertEquals(SandboxDefinitions.sha256(definition), sb.sha256)
        }
        // The definition comes from the default branch, not from the release branch the runs start from.
        assertTrue(world.git.worktrees.all { it.endsWith("<- release/2.0") }, world.git.worktrees.toString())
        assertEquals(listOf("origin/main:$SANDBOX_FILE", "origin/main:$SANDBOX_FILE"), world.git.reads.toList())

        // Each run's builder used its own sandbox; while the second ran, the first's was already gone.
        assertEquals(runIds.map { "agern-run-$it" }, rt.execs.filter { it.second.startsWith("make test") }.map { it.first })
        val (_, whileSecond) = seen.single { it.first == "Second" }
        assertTrue(whileSecond.none { it.contains(runIds[0].toString()) }, whileSecond.toString())
        assertTrue(whileSecond.contains("agern-run-${runIds[1]}"), whileSecond.toString())
        // Nothing is left and no slot is held; the issues stay open (release runs do not close them).
        assertTrue(rt.containers.isEmpty() && rt.networks.isEmpty(), rt.calls.toString())
        assertEquals(0, s.sandboxes.inUse())
        assertTrue(listOf(a, b).all { n -> world.github.repos["demo-app"]!!.issues.single { it.number == n }.state == "open" })
    }

    @Test
    fun `cancelling a release removes the sandbox of its running issue`() {
        val a = world.github.addIssue("demo-app", "Stuck", "body").number
        val b = world.github.addIssue("demo-app", "Never", "body").number
        hang += "Stuck"
        val r = release("2.1", listOf(a, b))
        val running = awaitRelease(r.id) { rel -> rel.issues.single { it.number == a }.runId != null && seen.any { it.first == "Stuck" } }
        val runId = running.issues.single { it.number == a }.runId!!
        assertTrue(rt.containers.containsKey("agern-run-$runId"))
        assertEquals(1, s.sandboxes.inUse())

        runBlocking { s.releases.cancel(alice, r.id) }
        val cancelled = s.releaseStore.get(r.id)!!
        assertEquals(ReleaseStatus.CANCELLED, cancelled.status)
        assertEquals(RunStatus.CANCELLED, s.runs.get(runId)!!.status)
        assertFalse(rt.containers.keys.any { it.startsWith("agern-run-$runId") }, rt.calls.toString())
        assertFalse(rt.networks.containsKey("agern-run-$runId"))
        assertEquals(0, s.sandboxes.inUse())
        assertTrue(seen.none { it.first == "Never" }, "no further issue started after the cancel")
    }

    @Test
    fun `a release started by the scheduler gets sandboxes exactly like one started by hand`() {
        val a = world.github.addIssue("demo-app", "Scheduled one", "body").number
        val b = world.github.addIssue("demo-app", "Scheduled two", "body").number
        val r = runBlocking {
            val r = s.releases.create(alice, "demo-app", "3.0", "")
            s.releases.assign(alice, "demo-app", listOf(a, b), r.id)
            s.releases.schedule(alice, r.id, clock.instant().plus(java.time.Duration.ofHours(1)).toString())
        }
        assertEquals("scheduled", r.status)
        assertTrue(rt.started.isEmpty(), "nothing runs before the start time")
        clock.advance(java.time.Duration.ofHours(2))
        runBlocking { s.releaseScheduler.tick() }
        val done = awaitRelease(r.id) { rel -> rel.issues.all { it.state == "merged" } }
        assertEquals("release/3.0", done.branch)
        val runIds = listOf(a, b).map { n -> done.issues.single { it.number == n }.runId!! }
        for (id in runIds) {
            val sb = s.runs.toDto(s.runs.get(id)!!).sandbox!!
            assertEquals("approved", sb.state, sb.reason)
        }
        assertEquals(listOf("origin/main:$SANDBOX_FILE", "origin/main:$SANDBOX_FILE"), world.git.reads.toList(), "definition from the default branch")
        assertEquals(runIds.map { "agern-run-$it" }, rt.execs.filter { it.second.startsWith("make test") }.map { it.first })
        assertTrue(seen.single { it.first == "Scheduled two" }.second.none { it.contains(runIds[0].toString()) })
        assertTrue(rt.containers.isEmpty() && rt.networks.isEmpty(), rt.calls.toString())
        assertEquals(0, s.sandboxes.inUse())
    }
}
