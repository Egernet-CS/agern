package dk.egernet.factory

import dk.egernet.factory.agent.FeedItem
import dk.egernet.factory.db.ReleaseRecord
import dk.egernet.factory.pipeline.ReleaseBuilder
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Releases and transient failures (docs/ARCHITECTURE.md §6 "Transient failures", "Releases"). */
class ReleaseNetworkTest {
    private val worlds = CopyOnWriteArrayList<TestWorld>()

    @AfterTest fun tearDown() {
        gates.keys.forEach { gates[it] = true }
        worlds.forEach { it.close() }
    }

    /** Builder behaviour by issue title: `lost` loses the API once, `gate` waits for its gate. */
    private val behaviour = ConcurrentHashMap<String, String>()
    private val gates = ConcurrentHashMap<String, Boolean>()

    private inner class W(delays: List<Long> = listOf(60_000L), maxMs: Long = 3_600_000L) {
        val world = TestWorld(twoFactor = false, tweak = { it.copy(networkRetryMaxMs = maxMs, networkRetryDelaysMs = delays) }).also { worlds += it }
        val s = world.services
        val alice = world.user("alice")

        init {
            world.launcher.stage = { p ->
                val title = Regex("Issue #\\d+: (.+)").find(p.spec.prompt ?: "")?.groupValues?.get(1)?.trim() ?: ""
                when (p.spec.kind) {
                    "spec-writer" -> { p.init(); p.result("## Spec\nDo it."); p.exit(0) }
                    "builder" -> when (behaviour[title]) {
                        "lost" -> {
                            behaviour.remove(title)
                            p.init("claude-lost"); p.result("API Error: Can't reach the API server", isError = true); p.exit(1)
                        }
                        "gate" -> {
                            p.init()
                            while (!p.killed && gates[title] != true) Thread.sleep(10)
                            if (!p.killed) { p.result("Built it."); p.exit(0) }
                        }
                        else -> { p.init(); p.result("Built it."); p.exit(0) }
                    }
                    "approver" -> { p.init(); p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") }); p.exit(0) }
                    else -> {
                        p.init()
                        p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
                        p.exit(0)
                    }
                }
            }
        }

        fun issue(title: String, b: String? = null): Int {
            b?.let { behaviour[title] = it }
            return world.github.addIssue("demo-app", title, "body").number
        }

        fun release(name: String, issues: List<Int>): ReleaseRecord = runBlocking {
            val r = s.releases.create(alice, "demo-app", name, "")
            s.releases.assign(alice, "demo-app", issues, r.id)
            s.releaseStore.get(r.id)!!
        }

        fun get(id: UUID) = s.releaseStore.get(id)!!
        fun await(id: UUID, what: String, cond: (ReleaseRecord) -> Boolean): ReleaseRecord = runBlocking {
            withTimeout(15_000) {
                while (true) {
                    val r = get(id)
                    if (cond(r)) return@withTimeout r
                    delay(10)
                }
                @Suppress("UNREACHABLE_CODE") error(what)
            }
        }

        /** Starts [r], lets its first issue [first] (a gated builder) merge while the release is paused, then cuts the network and resumes. */
        fun pausedAfter(r: ReleaseRecord, firstTitle: String, first: Int) {
            runBlocking { s.releases.start(alice, r.id) }
            await(r.id, "first running") { it.issue(first).state == ReleaseIssueState.RUNNING }
            runBlocking { s.releases.pause(alice, r.id) }
            gates[firstTitle] = true
            await(r.id, "first merged") { it.issue(first).state == ReleaseIssueState.MERGED }
            world.github.unreachable = true
            runBlocking { s.releaseBuilder.resume(r.id) }
        }
    }

    private fun ReleaseRecord.issue(n: Int) = issues.single { it.number == n }

    @Test
    fun `a release whose run waits stays building, starts nothing and holds nothing`() {
        val w = W()
        val a = w.issue("Alpha", "lost")
        val b = w.issue("Beta")
        val r = w.release("1.0", listOf(a, b))
        val feed = CopyOnWriteArrayList<FeedItem>()
        val collector = w.s.scope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { w.s.bus.feed.collect { feed += it } }
        runBlocking { w.s.releases.start(w.alice, r.id) }
        val waiting = w.await(r.id, "run waiting") { it.toDto().issues.first().waiting != null }
        assertEquals(ReleaseStatus.BUILDING, waiting.status)
        assertEquals(ReleaseIssueState.RUNNING, waiting.issue(a).state)
        assertEquals(ReleaseIssueState.WAITING, waiting.issue(b).state)
        assertNull(waiting.issue(b).runId, "the next issue did not start")
        assertEquals(1, waiting.toDto().issues.first().waiting!!.attempt)
        val runId = waiting.issue(a).runId!!
        runBlocking {
            withTimeout(5_000) {
                while (feed.none { it is FeedItem.Release && it.release.id == r.id.toString() && it.release.issues.first().waiting != null }) delay(10)
            }
        }
        runBlocking { w.s.factory.retryRunNow(w.alice, runId) }
        val done = w.await(r.id, "both merged") { it.issue(a).state == ReleaseIssueState.MERGED && it.issue(b).state == ReleaseIssueState.MERGED }
        assertNull(done.toDto().issues.first().waiting)
        runBlocking { collector.cancelAndJoin() }
    }

    @Test
    fun `a release that cannot start its next issue pauses for the network and resumes by itself`() {
        val w = W()
        val a = w.issue("First", "gate")
        val b = w.issue("Second")
        val r = w.release("2.0", listOf(a, b))
        w.pausedAfter(r, "First", a)
        val paused = w.await(r.id, "network pause") { it.status == ReleaseStatus.PAUSED && it.pauseReason == PauseReason.NETWORK }
        assertEquals(ReleaseIssueState.WAITING, paused.issue(b).state)
        assertEquals(ReleaseBuilder.NETWORK_ISSUE_NOTE, paused.issue(b).note)
        assertEquals("Could not start #$b: ConnectException", paused.pauseNote)
        val pause = paused.toDto().pause!!
        assertEquals("network", pause.reason)
        assertEquals(1, pause.attempt)
        val since = assertNotNull(paused.networkSince)
        val resumeAt = assertNotNull(paused.resumeAt)
        assertTrue(resumeAt.isAfter(Instant.now().plusSeconds(50)))

        // Not due yet: nothing happens. Due, still unreachable: paused again, same since, attempt 2.
        runBlocking { w.s.releaseBuilder.resumeDue(Instant.now()) }
        assertEquals(ReleaseStatus.PAUSED, w.get(r.id).status)
        runBlocking { w.s.releaseBuilder.resumeDue(resumeAt.plusMillis(1)) }
        val again = w.await(r.id, "paused again") { it.status == ReleaseStatus.PAUSED && it.networkAttempts == 2 }
        assertEquals(since, again.networkSince)
        // The counters are in the database (they survive a restart).
        val fromDb = dk.egernet.factory.db.ReleaseStore(w.s.db).get(r.id)!!
        assertEquals(2, fromDb.networkAttempts)
        assertEquals(since, fromDb.networkSince)

        w.world.github.unreachable = false
        runBlocking { w.s.releaseBuilder.resumeDue(again.resumeAt!!.plusMillis(1)) }
        val done = w.await(r.id, "second merged") { it.issue(b).state == ReleaseIssueState.MERGED }
        assertNull(done.networkSince)
        assertEquals(0, done.networkAttempts)
        assertTrue(done.issues.none { it.state == ReleaseIssueState.HELD })
    }

    @Test
    fun `after the limit the issue is held and the build goes on`() {
        val w = W(delays = listOf(30L), maxMs = 120L)
        val a = w.issue("First", "gate")
        val b = w.issue("Second")
        val r = w.release("3.0", listOf(a, b))
        w.pausedAfter(r, "First", a)
        val held = runBlocking {
            withTimeout(15_000) {
                while (w.get(r.id).issue(b).state != ReleaseIssueState.HELD) {
                    w.s.releaseBuilder.resumeDue(Instant.now().plusSeconds(3600))
                    delay(20)
                }
                w.get(r.id)
            }
        }
        val note = held.issue(b).note!!
        assertTrue(note.startsWith("Could not start a run: ConnectException Gave up after waiting for the network for "), note)
        assertTrue(!note.contains("null"), note)
        assertNull(held.networkSince)
        w.world.github.unreachable = false
    }

    @Test
    fun `giving up holds the issue and moves on`() {
        val w = W()
        val a = w.issue("First", "gate")
        val b = w.issue("Second")
        val c = w.issue("Third")
        val r = w.release("4.0", listOf(a, b, c))
        w.pausedAfter(r, "First", a)
        w.await(r.id, "network pause") { it.pauseReason == PauseReason.NETWORK }
        w.world.github.unreachable = false
        val after = runBlocking { w.s.releases.giveUpNetwork(w.alice, r.id) }
        assertEquals(ReleaseIssueState.HELD, after.issue(b).state)
        assertEquals("Could not start a run: ConnectException Gave up waiting for the network (by @alice).", after.issue(b).note)
        w.await(r.id, "third merged") { it.issue(c).state == ReleaseIssueState.MERGED }
        val notWaiting = kotlin.test.assertFailsWith<ApiException> { runBlocking { w.s.releases.giveUpNetwork(w.alice, r.id) } }
        assertEquals("release_not_waiting_for_network", notWaiting.code)
        val bob = w.world.user("bob")
        assertEquals(403, kotlin.test.assertFailsWith<ApiException> { runBlocking { w.s.releases.giveUpNetwork(bob, r.id) } }.status.value)
    }

    @Test
    fun `a start failure that is not transient is held at once`() {
        val w = W()
        val a = w.issue("First", "gate")
        val b = w.issue("Second")
        val r = w.release("5.0", listOf(a, b))
        runBlocking { w.s.releases.start(w.alice, r.id) }
        w.await(r.id, "first running") { it.issue(a).state == ReleaseIssueState.RUNNING }
        runBlocking { w.s.releases.pause(w.alice, r.id) }
        gates["First"] = true
        w.await(r.id, "first merged") { it.issue(a).state == ReleaseIssueState.MERGED }
        w.world.github.serverError = 403
        runBlocking { w.s.releaseBuilder.resume(r.id) }
        val held = w.await(r.id, "held") { it.issue(b).state == ReleaseIssueState.HELD }
        assertTrue(held.issue(b).note!!.startsWith("Could not start a run: GitHubException: GitHub request failed: 403"), held.issue(b).note)
        assertEquals(0, held.networkAttempts)
        w.world.github.serverError = null
    }
}
