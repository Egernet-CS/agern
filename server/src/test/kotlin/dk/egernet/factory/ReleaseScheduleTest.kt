package dk.egernet.factory

import dk.egernet.factory.db.ReleaseRecord
import dk.egernet.factory.db.ReleaseStore
import dk.egernet.factory.db.UserRecord
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A starter that records its calls, moves the release to `building` and can be told to refuse or do nothing. */
class RecordingStarter : ReleaseStarter {
    lateinit var store: ReleaseStore
    val calls = CopyOnWriteArrayList<Pair<UUID, String>>()
    @Volatile var throwing: Exception? = null
    @Volatile var noop = false

    override suspend fun start(release: ReleaseRecord, owner: UserRecord) {
        calls += release.id to owner.username
        throwing?.let { throw it }
        if (!noop) store.markBuilding(release.id)
    }
}

/** Scheduled start of releases (docs/ARCHITECTURE.md §6 "Releases", "Scheduled start"). */
class ReleaseScheduleTest {
    private val clock = MutableClock()
    private val starter = RecordingStarter()
    private val world = TestWorld(twoFactor = false, clock = clock, releaseStarter = starter)
    private val s = world.services
    private val extraServices = mutableListOf<Services>()

    init {
        starter.store = s.releaseStore
    }

    @AfterTest fun tearDown() {
        extraServices.forEach { it.stop() }
        // The extra services share the world's pool: wait until none of their coroutines uses it before it is closed.
        runBlocking {
            for (svc in extraServices) withTimeoutOrNull(10_000) { svc.scope.coroutineContext[kotlinx.coroutines.Job]!!.join() }
        }
        world.close()
    }

    private fun json(r: String) = AppJson.parseToJsonElement(r)
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun errorOf(r: HttpResponse) = runBlocking { json(r.bodyAsText()).jsonObject["error"]!!.jsonObject }

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String, claude: Boolean = true): HttpClient {
        val c = createClient { install(HttpCookies); install(WebSockets) }
        assertEquals(200, c.post("/api/auth/login") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
        }.status.value)
        c.put("/api/me/github-token") { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"token":"fake-$user"}""") }
        if (claude) {
            c.put("/api/me/claude-credential") {
                header("X-Factory-Request", "1"); contentType(ContentType.Application.Json)
                setBody("""{"kind":"oauth_token","value":"sk-ant-oat-$user"}""")
            }
        }
        return c
    }

    private suspend fun HttpClient.createRelease(repo: String, name: String): String {
        val r = post("/api/projects/$repo/releases") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"name":"$name"}""")
        }
        assertEquals(201, r.status.value, r.bodyAsText())
        return json(r.bodyAsText()).jsonObject.str("id")
    }

    private suspend fun HttpClient.schedule(id: String, startAt: String, csrf: Boolean = true): HttpResponse =
        put("/api/releases/$id/schedule") {
            if (csrf) header("X-Factory-Request", "1")
            contentType(ContentType.Application.Json); setBody("""{"startAt":"$startAt"}""")
        }

    private suspend fun HttpClient.unschedule(id: String, csrf: Boolean = true): HttpResponse =
        delete("/api/releases/$id/schedule") { if (csrf) header("X-Factory-Request", "1") }

    private fun HttpResponse.obj(): JsonObject = runBlocking { json(bodyAsText()).jsonObject }

    private fun later(d: Duration = Duration.ofHours(1)): Instant = clock.now.plus(d)

    // ------------------------------------------------------------- direct (service-level) helpers

    private fun release(user: UserRecord, repo: String, name: String) = runBlocking { s.releases.create(user, repo, name, "") }
    private fun schedule(user: UserRecord, r: ReleaseRecord, at: Instant) = runBlocking { s.releases.schedule(user, r.id, at.toString()) }
    private fun get(r: ReleaseRecord) = s.releaseStore.get(r.id)!!
    private fun tick() = runBlocking { s.releaseScheduler.tick() }
    private fun setStatus(r: ReleaseRecord, status: String) = s.db.update("UPDATE releases SET status = ? WHERE id = ?", status, r.id)

    @Test
    fun `set, change and cancel over HTTP with validation`() = testApplication {
        application { agernModule(s) }
        val alice = login("alice", "pw-a")
        val id = alice.createRelease("demo-app", "1.4")
        val at = later(Duration.ofDays(1))

        val r = alice.schedule(id, at.toString())
        assertEquals(200, r.status.value, r.bodyAsText())
        val o = r.obj()
        assertEquals("scheduled", o.str("status"))
        assertEquals("alice", o["owner"]!!.jsonObject.str("username"))
        assertEquals(at.toString(), Instant.parse(o.str("scheduledAt")).toString())
        assertEquals(JsonNull, o["waitingFor"])
        assertEquals(JsonNull, o["startFailure"])
        assertTrue(world.github.actions.isEmpty(), world.github.actions.toString())
        assertTrue(s.runs.list(null, false).isEmpty())

        // Validation: unparsable, past, now, too far ahead.
        for (bad in listOf("tomorrow", "2026-01-02T22:00", "", clock.now.minusSeconds(1).toString(), clock.now.toString(), clock.now.plus(Duration.ofDays(367)).toString())) {
            val b = alice.schedule(id, bad)
            assertEquals(400, b.status.value, "startAt '$bad': ${b.bodyAsText()}")
        }
        assertEquals("startAt must be an ISO-8601 time", errorOf(alice.schedule(id, "nope")).str("message"))
        assertEquals("The start time must be in the future", errorOf(alice.schedule(id, clock.now.toString())).str("message"))
        assertEquals("The start time is too far ahead (at most a year)", errorOf(alice.schedule(id, clock.now.plus(Duration.ofDays(367)).toString())).str("message"))
        assertEquals(200, alice.schedule(id, clock.now.plus(Duration.ofDays(366)).toString()).status.value)

        // Change: milliseconds kept, finer precision truncated.
        val changed = alice.schedule(id, "2026-01-03T10:15:30.123456Z").obj()
        assertEquals(Instant.parse("2026-01-03T10:15:30.123Z"), Instant.parse(changed.str("scheduledAt")))
        assertEquals("alice", changed["owner"]!!.jsonObject.str("username"))

        // Cancel.
        val cancelled = alice.unschedule(id)
        assertEquals(200, cancelled.status.value, cancelled.bodyAsText())
        val c = cancelled.obj()
        assertEquals("planning", c.str("status"))
        assertEquals(JsonNull, c["owner"])
        assertEquals(JsonNull, c["scheduledAt"])
        val again = alice.unschedule(id)
        assertEquals(409, again.status.value)
        assertEquals("release_not_scheduled", errorOf(again).str("code"))

        // A release in another state cannot be scheduled.
        setStatus(s.releaseStore.get(UUID.fromString(id))!!, ReleaseStatus.BUILDING)
        val building = alice.schedule(id, later().toString())
        assertEquals(409, building.status.value)
        assertEquals("release_not_schedulable", errorOf(building).str("code"))
        assertEquals("Release is building; only a release in preparation can be scheduled", errorOf(building).str("message"))
        assertEquals("release_not_scheduled", errorOf(alice.unschedule(id)).str("code"))
    }

    @Test
    fun `credentials are required to schedule`() = testApplication {
        application { agernModule(s) }
        val bob = login("bob", "pw-b", claude = false)
        val id = bob.createRelease("demo-app", "1.4")
        val r = bob.schedule(id, later().toString())
        assertEquals(422, r.status.value)
        assertEquals("claude_credential_required", errorOf(r).str("code"))
        assertEquals("planning", s.releaseStore.get(UUID.fromString(id))!!.status)
    }

    @Test
    fun `permissions, visibility and csrf`() = testApplication {
        application { agernModule(s) }
        val alice = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        val root = login("root", "pw-r")
        val id = alice.createRelease("demo-app", "1.4")
        assertEquals(200, alice.schedule(id, later().toString()).status.value)

        val forbidden = bob.schedule(id, later(Duration.ofHours(2)).toString())
        assertEquals(403, forbidden.status.value)
        assertEquals("Only alice or an admin can change this schedule", errorOf(forbidden).str("message"))
        assertEquals(403, bob.unschedule(id).status.value)

        // An admin may change the time; the owner stays.
        val byAdmin = root.schedule(id, later(Duration.ofHours(3)).toString())
        assertEquals(200, byAdmin.status.value, byAdmin.bodyAsText())
        assertEquals("alice", byAdmin.obj()["owner"]!!.jsonObject.str("username"))
        assertEquals(later(Duration.ofHours(3)), Instant.parse(byAdmin.obj().str("scheduledAt")))
        assertEquals(200, alice.schedule(id, later(Duration.ofHours(4)).toString()).status.value)
        assertEquals(200, root.unschedule(id).status.value)

        // Invisible repo: 404 on both routes.
        val lab = alice.createRelease("private-lab", "secret")
        assertEquals(404, bob.schedule(lab, later().toString()).status.value)
        assertEquals(404, bob.schedule(lab, "nonsense").status.value)
        assertEquals(200, alice.schedule(lab, later().toString()).status.value)
        assertEquals(404, bob.unschedule(lab).status.value)

        // CSRF header required.
        assertEquals(403, alice.schedule(id, later().toString(), csrf = false).status.value)
        assertEquals(403, alice.unschedule(lab, csrf = false).status.value)
        assertEquals("scheduled", s.releaseStore.get(UUID.fromString(lab))!!.status)
        assertEquals(401, createClient { }.unschedule(lab).status.value)
    }

    @Test
    fun `delete is refused while scheduled, planning edits still work`() = testApplication {
        application { agernModule(s) }
        val alice = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        val id = alice.createRelease("demo-app", "1.4")
        assertEquals(200, alice.schedule(id, later().toString()).status.value)

        val del = alice.delete("/api/releases/$id") { header("X-Factory-Request", "1") }
        assertEquals(409, del.status.value)
        assertEquals("release_scheduled", errorOf(del).str("code"))
        assertEquals("Cancel the scheduled start before deleting this release", errorOf(del).str("message"))

        // Everyone who can see the project still plans: assign, reorder, rename.
        val assign = bob.post("/api/projects/demo-app/releases/assign") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"issueNumbers":[1,2],"releaseId":"$id"}""")
        }
        assertEquals(200, assign.status.value, assign.bodyAsText())
        assertEquals(200, bob.put("/api/releases/$id/issues/order") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"issueNumbers":[2,1]}""")
        }.status.value)
        val renamed = bob.put("/api/releases/$id") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"name":"1.4.1","description":"x"}""")
        }
        assertEquals(200, renamed.status.value)
        assertEquals("scheduled", renamed.obj().str("status"))
        val rec = s.releaseStore.get(UUID.fromString(id))!!
        assertEquals(listOf(2, 1), rec.issues.map { it.number })
        assertEquals("alice", rec.ownerUsername)

        assertEquals(200, alice.unschedule(id).status.value)
        assertEquals(204, alice.delete("/api/releases/$id") { header("X-Factory-Request", "1") }.status.value)
    }

    @Test
    fun `nothing before the time, exactly one start at it`() {
        val alice = world.user("alice")
        val r = release(alice, "demo-app", "1.4")
        val at = later()
        schedule(alice, r, at)
        clock.now = at.minusMillis(1)
        tick()
        assertTrue(starter.calls.isEmpty())
        assertEquals("scheduled", get(r).status)

        clock.now = at
        tick()
        assertEquals(listOf(r.id to "alice"), starter.calls.toList())
        val started = get(r)
        assertEquals("building", started.status)
        assertNull(started.scheduledAt)
        assertEquals("alice", started.ownerUsername)
        clock.advance(Duration.ofMinutes(1))
        tick()
        tick()
        assertEquals(1, starter.calls.size)
    }

    @Test
    fun `a due release waits behind a building or paused one of the same project`() {
        val alice = world.user("alice")
        val busy = release(alice, "demo-app", "busy")
        val r = release(alice, "demo-app", "1.4")
        val other = release(alice, "private-lab", "lab")
        setStatus(busy, ReleaseStatus.BUILDING)
        val at = later()
        schedule(alice, r, at)
        schedule(alice, other, at)
        clock.now = at.plusSeconds(1)
        tick()
        assertEquals(listOf(other.id to "alice"), starter.calls.toList(), "another repo is not held back")
        val waiting = get(r)
        assertEquals("scheduled", waiting.status)
        assertEquals(busy.id, waiting.waitingForId)
        assertEquals("busy", waiting.toDto().waitingFor!!.name)

        setStatus(busy, ReleaseStatus.PAUSED)
        tick()
        assertEquals(1, starter.calls.size)
        assertEquals(busy.id, get(r).waitingForId)

        setStatus(busy, ReleaseStatus.PLANNING)
        tick()
        assertEquals(r.id to "alice", starter.calls.last())
        assertEquals("building", get(r).status)
        assertNull(get(r).waitingForId)
    }

    @Test
    fun `two due releases of one project start in time order`() {
        val alice = world.user("alice")
        val bob = world.user("bob")
        val second = release(alice, "demo-app", "second")
        val first = release(bob, "demo-app", "first")
        schedule(alice, second, later(Duration.ofMinutes(2)))
        schedule(bob, first, later(Duration.ofMinutes(1)))
        clock.advance(Duration.ofMinutes(5))
        tick()
        assertEquals(listOf(first.id to "bob"), starter.calls.toList())
        assertEquals(first.id, get(second).waitingForId)
        setStatus(first, "released")
        tick()
        assertEquals(listOf(first.id to "bob", second.id to "alice"), starter.calls.toList())
    }

    @Test
    fun `missing or rejected credentials at due time fail the start`() {
        val alice = world.user("alice")
        val bob = world.user("bob")
        val carol = world.user("carol")
        val a = release(alice, "demo-app", "a")
        val b = release(bob, "demo-app", "b")
        val c = release(carol, "demo-app", "c")
        val at = later()
        schedule(alice, a, at)
        schedule(bob, b, at.plusMillis(1))
        schedule(carol, c, at.plusMillis(2))
        s.users.setClaude(alice.id, null, null)
        s.users.setGithub(bob.id, null, null)
        s.users.setGithub(carol.id, "carol", s.box.encrypt("ghp_revoked", "carol"))
        clock.now = at.plusSeconds(5)
        tick()
        assertTrue(starter.calls.isEmpty())
        val reasons = listOf(a, b, c).map { get(it) }.onEach {
            assertEquals("planning", it.status)
            assertNull(it.ownerId)
            assertNull(it.scheduledAt)
            assertEquals(clock.now, it.startFailedAt)
        }.associate { it.startFailedOwnerUsername to it.startFailedReason }
        assertEquals<Map<String?, String?>>(
            mapOf(
                "alice" to "alice has no Claude credential registered. Register one in Settings and schedule the release again.",
                "bob" to "bob has no GitHub token registered. Register one in Settings and schedule the release again.",
                "carol" to "GitHub rejected carol's token or it can no longer read demo-app. Update the token in Settings and schedule the release again.",
            ),
            reasons,
        )
        val dto = get(a).toDto().startFailure!!
        assertEquals("alice", dto.owner.username)

        // No automatic retry; scheduling again clears the failure.
        tick()
        assertTrue(starter.calls.isEmpty())
        s.users.setGithub(carol.id, "carol", s.box.encrypt("fake-carol", "carol"))
        s.access.invalidate(carol.id)
        val again = schedule(s.users.byId(carol.id)!!, get(c), later())
        assertNull(again.startFailedReason)
    }

    @Test
    fun `a token that can no longer read the repo fails the start`() {
        val alice = world.user("alice")
        val r = release(alice, "private-lab", "lab")
        schedule(alice, r, later())
        // Alice's token now only grants public repositories.
        s.users.setGithub(alice.id, "alice", s.box.encrypt("fake-alice~read:org", "alice"))
        clock.advance(Duration.ofHours(2))
        tick()
        assertEquals("planning", get(r).status)
        assertTrue(get(r).startFailedReason!!.startsWith("GitHub rejected alice's token"))
    }

    @Test
    fun `starter refusals, a starter that does nothing and internal errors become start failures`() {
        val alice = world.user("alice")
        val a = release(alice, "demo-app", "a")
        schedule(alice, a, later())
        clock.advance(Duration.ofHours(2))
        starter.throwing = conflict("Nothing to build", "release_empty")
        tick()
        assertEquals("planning", get(a).status)
        assertEquals("Nothing to build", get(a).startFailedReason)

        starter.throwing = null
        starter.noop = true
        schedule(alice, get(a), later())
        clock.advance(Duration.ofHours(2))
        tick()
        assertEquals("The release did not start.", get(a).startFailedReason)

        starter.noop = false
        starter.throwing = IllegalStateException("boom with secret details")
        val b = release(alice, "demo-app", "b")
        schedule(alice, get(a), later())
        schedule(alice, b, later(Duration.ofMinutes(61)))
        clock.advance(Duration.ofHours(2))
        tick()
        // One failing release does not stop the tick: both were tried.
        assertEquals(4, starter.calls.size)
        listOf(a, b).forEach { assertEquals("Internal error while starting the release; see the server log.", get(it).startFailedReason) }
    }

    @Test
    fun `a transient GitHub error keeps the release scheduled and retries`() {
        val alice = world.user("alice")
        val r = release(alice, "demo-app", "1.4")
        schedule(alice, r, later())
        clock.advance(Duration.ofHours(2))
        world.github.getRepoFailure = 503
        tick()
        tick()
        assertEquals("scheduled", get(r).status)
        assertNull(get(r).startFailedReason)
        assertTrue(starter.calls.isEmpty())
        world.github.getRepoFailure = null
        tick()
        assertEquals("building", get(r).status)
        assertEquals(1, starter.calls.size)
    }

    @Test
    fun `a schedule survives a restart and a missed time is started`() {
        val alice = world.user("alice")
        val r = release(alice, "demo-app", "1.4")
        val at = later()
        schedule(alice, r, at)
        // Shut the first server down, but keep the pool: the restarted one below works on the same database.
        s.stop()

        val restartClock = MutableClock(at.plus(Duration.ofHours(8)))
        val again = Services(s.cfg, s.db, world.github, world.git, world.launcher, clock = restartClock, releaseStarter = starter)
        extraServices += again
        again.recoverAfterRestart()
        assertEquals("scheduled", again.releaseStore.get(r.id)!!.status)
        runBlocking { again.releaseScheduler.tick() }
        assertEquals(listOf(r.id to "alice"), starter.calls.toList())
        assertEquals("building", again.releaseStore.get(r.id)!!.status)
    }

    @Test
    fun `a cancel during a start waits and then finds no schedule`() = runBlocking {
        val alice = world.user("alice")
        val r = release(alice, "demo-app", "1.4")
        schedule(alice, r, later())
        clock.advance(Duration.ofHours(2))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = ReleaseStarter { rel, _ -> entered.complete(Unit); gate.await(); s.releaseStore.markBuilding(rel.id) }
        val sched = ReleaseScheduler(s.cfg, clock, s.releaseStore, s.users, s.access, s.bus, slow)
        val ticking = launch(kotlinx.coroutines.Dispatchers.Default) { sched.tick() }
        entered.await()
        val cancel = async(kotlinx.coroutines.Dispatchers.Default) { runCatching { sched.cancel(alice, get(r)) } }
        delay(100)
        assertTrue(cancel.isActive, "cancel waits for the start in progress")
        gate.complete(Unit)
        ticking.join()
        val e = cancel.await().exceptionOrNull() as ApiException
        assertEquals("release_not_scheduled", e.code)
        assertEquals("building", get(r).status)
    }

    @Test
    fun `feed frames for set, waiting once, failure and cancel, to viewers only`() = testApplication {
        application { agernModule(s) }
        val alice = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        bob.get("/api/projects/demo-app")
        val aliceFeed = alice.webSocketSession("/api/ws/feed")
        val bobFeed = bob.webSocketSession("/api/ws/feed")
        withTimeout(10_000) { while (s.bus.feedSubscribers.value < 2) delay(10) }

        suspend fun next(ws: DefaultClientWebSocketSession, ms: Long = 10_000): JsonObject? = withTimeoutOrNull(ms) {
            while (true) {
                val f = ws.incoming.receive() as? Frame.Text ?: continue
                val o = json(f.readText()).jsonObject
                if (o.str("type") == "release") return@withTimeoutOrNull o["release"]!!.jsonObject
            }
            @Suppress("UNREACHABLE_CODE") null
        }

        val lab = alice.createRelease("private-lab", "secret")
        assertNotNull(next(aliceFeed))
        val busy = alice.createRelease("demo-app", "busy")
        val id = alice.createRelease("demo-app", "1.4")
        assertEquals("busy", next(aliceFeed)!!.str("name"))
        assertEquals("1.4", next(aliceFeed)!!.str("name"))
        assertEquals("busy", next(bobFeed)!!.str("name"), "bob does not see private-lab")
        assertEquals("1.4", next(bobFeed)!!.str("name"))

        alice.schedule(lab, later().toString())
        assertEquals("scheduled", next(aliceFeed)!!.str("status"))
        val set = alice.schedule(id, later().toString()).obj()
        val f1 = next(aliceFeed)!!
        assertEquals("scheduled", f1.str("status"))
        assertEquals(id, f1.str("id"))
        val b1 = next(bobFeed)!!
        assertEquals(id, b1.str("id"))
        assertEquals("scheduled", b1.str("status"))

        setStatus(s.releaseStore.get(UUID.fromString(busy))!!, ReleaseStatus.BUILDING)
        alice.unschedule(lab)
        assertEquals(lab, next(aliceFeed)!!.str("id"))
        clock.advance(Duration.ofHours(2))
        s.releaseScheduler.tick()
        val waiting = next(bobFeed)!!
        assertEquals("busy", waiting["waitingFor"]!!.jsonObject.str("name"))
        assertTrue(waiting.str("updatedAt") > set.str("updatedAt"))
        s.releaseScheduler.tick()
        s.releaseScheduler.tick()
        assertNull(next(bobFeed, 500), "waiting is announced once, not on every tick")

        starter.throwing = unprocessable("Not today", "nope")
        setStatus(s.releaseStore.get(UUID.fromString(busy))!!, ReleaseStatus.PLANNING)
        s.releaseScheduler.tick()
        val cleared = next(bobFeed)!!
        assertEquals(JsonNull, cleared["waitingFor"])
        val failed = next(bobFeed)!!
        assertEquals("planning", failed.str("status"))
        assertEquals("Not today", failed["startFailure"]!!.jsonObject.str("reason"))
        assertEquals("alice", failed["startFailure"]!!.jsonObject["owner"]!!.jsonObject.str("username"))

        alice.schedule(id, later().toString())
        assertEquals("scheduled", next(bobFeed)!!.str("status"))
        alice.unschedule(id)
        val cancelled = next(bobFeed)!!
        assertEquals("planning", cancelled.str("status"))
        assertEquals(JsonNull, cancelled["owner"])
    }

    @Test
    fun `describe shows scheduled, waiting and failed`() = runBlocking {
        val alice = world.user("alice")
        val busy = release(alice, "demo-app", "busy")
        val r = release(alice, "demo-app", "1.4")
        val failed = release(alice, "demo-app", "1.3")
        val at = Instant.parse("2026-01-01T22:00:00Z")
        schedule(alice, r, at)
        var text = s.releases.describe(alice, "demo-app", false)
        assertTrue(text.contains("1.4 — scheduled for 2026-01-01T22:00:00Z by alice, 0 issues"), text)
        assertTrue(text.contains("busy — in preparation, 0 issues"), text)

        setStatus(busy, ReleaseStatus.BUILDING)
        clock.now = at
        tick()
        text = s.releases.describe(alice, "demo-app", false)
        assertTrue(text.contains("1.4 — scheduled for 2026-01-01T22:00:00Z by alice, waiting for release \"busy\", 0 issues"), text)
        assertTrue(text.contains("busy — building, 0 of 0 merged"), text) // step 2's text for a started release

        schedule(alice, failed, at.plusSeconds(60))
        s.users.setClaude(alice.id, null, null)
        setStatus(busy, ReleaseStatus.PLANNING)
        clock.now = at.plusSeconds(60)
        tick()
        text = s.releases.describe(alice, "demo-app", false)
        assertTrue(text.contains("1.3 — in preparation (scheduled start failed: alice has no Claude credential registered."), text)
        assertTrue(text.contains("1.4 — in preparation (scheduled start failed: alice has no Claude"), text)
        val chat = s.sessions.create("demo-app", "chat", "opus", "idle", alice.id, null)
        assertTrue(s.factory.handleCommand(chat, alice, "/factory releases").contains("1.3 — in preparation (scheduled start failed:"))
        Unit
    }
}
