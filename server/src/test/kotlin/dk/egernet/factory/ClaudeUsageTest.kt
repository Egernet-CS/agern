package dk.egernet.factory

import dk.egernet.factory.agent.RateLimitWindows
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.db.iso
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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each user's own Claude usage: limit windows from `rate_limit_event`, daily spend, `GET /api/me/usage`, the `usage` frame. */
class ClaudeUsageTest {
    private val clock = MutableClock(Instant.parse("2026-03-15T12:00:00Z"))
    private val world = TestWorld(twoFactor = false, clock = clock)
    private val s = world.services
    private val alice = world.user("alice")
    private val bob = world.user("bob")

    /**
     * Chats are still live at the end of most tests: stop them and let their background bookkeeping finish before the
     * pool is closed (the embedded PostgreSQL allows only so many connections, so the pool must go).
     */
    @AfterTest fun tearDown() {
        s.agents.shutdown()
        runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(5_000) {
                while (s.scope.coroutineContext[kotlinx.coroutines.Job]!!.children.any { it.isActive }) delay(10)
            }
        }
        world.close()
    }

    private fun json(r: String) = AppJson.parseToJsonElement(r).jsonObject
    private val resets = Instant.parse("2026-03-15T15:00:00Z")

    private fun event(info: JsonObject?) = buildJsonObject {
        put("type", "rate_limit_event"); put("uuid", "u-1"); put("session_id", "claude-1")
        if (info != null) put("rate_limit_info", info)
    }

    private fun windows(fiveHour: Double = 0.42, sevenDay: Double = 0.18, status: String = "allowed") = buildJsonObject {
        put("status", status)
        putJsonObject("unifiedWindows") {
            putJsonObject("five_hour") { put("utilization", fiveHour); put("resetsAt", resets.epochSecond); put("status", status) }
            putJsonObject("seven_day") { put("utilization", sevenDay); put("resetsAt", resets.plus(Duration.ofDays(3)).epochSecond); put("status", "allowed") }
        }
    }

    private fun costResult(total: Double) = buildJsonObject {
        put("type", "result"); put("subtype", "success"); put("is_error", false); put("result", "ok"); put("num_turns", 1); put("total_cost_usd", total)
    }

    private suspend fun eventually(what: String, cond: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!cond()) delay(10) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for: $what")
        }
    }

    private fun usage(u: UserRecord) = s.usage.usage(s.users.byId(u.id)!!)
    private fun close(a: Double, b: Double) = Math.abs(a - b) < 1e-9

    /** A chat of [u] after its first (echo) turn: costs 0.01. */
    private suspend fun chatProcess(u: UserRecord): Pair<dk.egernet.factory.db.SessionRecord, FakeProcess> {
        val chat = s.agents.createChat(u, "demo-app", null)
        s.agents.sendInput(u, chat, "hello")
        lateinit var p: FakeProcess
        eventually("chat launched") { world.launcher.launched.firstOrNull { it.spec.sessionId == chat.id }?.also { p = it } != null }
        eventually("first turn recorded") { s.sessions.get(chat.id)?.costUsd != null }
        return chat to p
    }

    /** Emits [obj] and then a result raising the process total to [marker], and waits until that result was handled. */
    private suspend fun emitAndSettle(p: FakeProcess, obj: JsonObject, u: UserRecord, marker: Double) {
        p.emit(obj)
        p.emit(costResult(marker))
        eventually("result handled") { close(usage(u).spend.todayUsd, marker) }
    }

    // ------------------------------------------------------------ parser

    @Test
    fun `parser - top-level, unifiedWindows and both merged`() {
        val top = RateLimitWindows.parse(buildJsonObject {
            put("status", "allowed_warning"); put("rateLimitType", "five_hour"); put("utilization", 0.9); put("resetsAt", 1_800_000_000)
        })
        assertEquals(1, top.size)
        assertEquals("five_hour", top[0].type)
        assertEquals(0.9, top[0].utilization)
        assertEquals("allowed_warning", top[0].status)
        assertEquals(Instant.ofEpochSecond(1_800_000_000), top[0].resetsAt)

        val unified = RateLimitWindows.parse(windows())
        assertEquals(listOf("five_hour", "seven_day"), unified.map { it.type })
        assertEquals(0.42, unified[0].utilization)
        assertNull(unified.firstOrNull { it.type == "five_hour" && it.status != "allowed" })

        // Same type in both places: unifiedWindows wins per field, the top level fills gaps, rejected from either side wins.
        val both = RateLimitWindows.parse(buildJsonObject {
            put("status", "rejected"); put("rateLimitType", "five_hour"); put("utilization", 1.0); put("resetsAt", 1_800_000_000)
            putJsonObject("unifiedWindows") {
                putJsonObject("five_hour") { put("utilization", 0.97); put("status", "allowed_warning") }
            }
        }).single()
        assertEquals(0.97, both.utilization)
        assertEquals("rejected", both.status)
        assertEquals(Instant.ofEpochSecond(1_800_000_000), both.resetsAt)
    }

    @Test
    fun `parser - unknown types kept, invalid ones and all-null windows skipped`() {
        val w = RateLimitWindows.parse(buildJsonObject {
            put("rateLimitType", "brand_new.window"); put("utilization", 0.5); put("status", "something_new")
            putJsonObject("unifiedWindows") {
                putJsonObject("bad type!") { put("utilization", 0.1) }
                putJsonObject("<script>") { put("utilization", 0.1) }
                putJsonObject("x".repeat(65)) { put("utilization", 0.1) }
                putJsonObject("empty") { put("other", 1) }
                put("not_an_object", 0.3)
                putJsonObject("monthly") { put("utilization", 0.2) }
            }
        })
        assertEquals(setOf("monthly", "brand_new.window"), w.map { it.type }.toSet())
        assertEquals("something_new", w.single { it.type == "brand_new.window" }.status)
        // Invalid top-level type: nothing.
        assertEquals(emptyList(), RateLimitWindows.parse(buildJsonObject { put("rateLimitType", "a b"); put("utilization", 0.5) }))
    }

    @Test
    fun `parser - field types`() {
        fun one(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
            RateLimitWindows.parse(buildJsonObject { put("rateLimitType", "five_hour"); put("status", "allowed"); build() }).single()
        assertNull(one { put("utilization", "0.5") }.utilization)
        assertNull(one { put("utilization", -0.1) }.utilization)
        assertNull(one { put("utilization", true) }.utilization)
        assertNull(one { put("utilization", JsonNull) }.utilization)
        assertNull(one { put("utilization", buildJsonArray { }) }.utilization)
        assertEquals(1.3, one { put("utilization", 1.3) }.utilization, "above 1 is kept")
        assertEquals(Instant.ofEpochSecond(1_800_000_000), one { put("resetsAt", 1_800_000_000) }.resetsAt)
        assertEquals(Instant.ofEpochMilli(1_800_000_000_123), one { put("resetsAt", 1_800_000_000_123) }.resetsAt)
        assertEquals(Instant.parse("2026-03-15T15:00:00Z"), one { put("resetsAt", "2026-03-15T15:00:00Z") }.resetsAt)
        assertNull(one { put("resetsAt", "soon") }.resetsAt)
        assertNull(one { put("utilization", 0.5); put("status", 3) }.status)
        assertNull(one { put("utilization", 0.5); put("status", "x".repeat(41)) }.status)
        // NaN cannot be written as JSON; a lenient number that is not finite is still rejected.
        assertNull(RateLimitWindows.parse(JsonObject(mapOf("rateLimitType" to JsonPrimitive("w"), "utilization" to JsonPrimitive(Double.NaN)))).firstOrNull()?.utilization)
        // A window with nothing usable is skipped.
        assertEquals(emptyList(), RateLimitWindows.parse(buildJsonObject { put("rateLimitType", "five_hour"); put("utilization", "x"); put("status", 1) }))
        // At most 20 windows per event.
        val many = RateLimitWindows.parse(buildJsonObject {
            putJsonObject("unifiedWindows") { for (i in 1..30) putJsonObject("w$i") { put("utilization", 0.1) } }
        })
        assertEquals(20, many.size)
    }

    // ------------------------------------------------------------ recording

    @Test
    fun `a chat event updates the owner's windows and nobody else's`() = runBlocking<Unit> {
        val (_, p) = chatProcess(alice)
        p.emit(event(windows()))
        eventually("windows stored") { usage(alice).windows.size == 2 }
        val u = usage(alice)
        assertEquals("oauth_token", u.credentialKind)
        val five = u.windows.single { it.type == "five_hour" }
        assertEquals(0.42, five.utilization)
        assertEquals("allowed", five.status)
        assertEquals(resets.iso(), five.resetsAt)
        assertEquals(clock.now.iso(), five.observedAt)
        assertEquals(listOf("five_hour", "seven_day"), u.windows.map { it.type }, "ordered by type")
        assertEquals(emptyList(), usage(bob).windows)
        // The event is still in the transcript.
        val sid = p.spec.sessionId
        assertTrue(s.events.list(sid, 0, 1000).any { it.type == "claude" && it.payload.toString().contains("rate_limit_event") })
    }

    @Test
    fun `upsert - later values replace, a missing utilization is kept only for the same reset time`() = runBlocking<Unit> {
        val (_, p) = chatProcess(alice)
        p.emit(event(windows(fiveHour = 0.42)))
        eventually("first") { usage(alice).windows.size == 2 }
        clock.advance(Duration.ofMinutes(5))
        p.emit(event(windows(fiveHour = 0.55, status = "allowed_warning")))
        eventually("replaced") { usage(alice).windows.single { it.type == "five_hour" }.utilization == 0.55 }
        assertEquals("allowed_warning", usage(alice).windows.single { it.type == "five_hour" }.status)
        assertEquals(clock.now.iso(), usage(alice).windows.single { it.type == "five_hour" }.observedAt)

        // Same reset time, no utilization: the percentage stays, the status is replaced.
        p.emit(event(buildJsonObject { put("status", "allowed"); put("rateLimitType", "five_hour"); put("resetsAt", resets.epochSecond) }))
        eventually("status replaced") { usage(alice).windows.single { it.type == "five_hour" }.status == "allowed" }
        assertEquals(0.55, usage(alice).windows.single { it.type == "five_hour" }.utilization)

        // No utilization and no reset time: kept as well.
        p.emit(event(buildJsonObject { put("status", "allowed_warning"); put("rateLimitType", "five_hour") }))
        eventually("status again") { usage(alice).windows.single { it.type == "five_hour" }.status == "allowed_warning" }
        assertEquals(0.55, usage(alice).windows.single { it.type == "five_hour" }.utilization)
        assertEquals(resets.iso(), usage(alice).windows.single { it.type == "five_hour" }.resetsAt, "an unknown reset time keeps the stored one")

        // A new reset time without utilization: the old percentage belonged to the old window, so it is cleared.
        val later = resets.plus(Duration.ofHours(5))
        p.emit(event(buildJsonObject { put("status", "allowed"); put("rateLimitType", "five_hour"); put("resetsAt", later.epochSecond) }))
        eventually("new window") { usage(alice).windows.single { it.type == "five_hour" }.resetsAt == later.iso() }
        assertNull(usage(alice).windows.single { it.type == "five_hour" }.utilization)
    }

    @Test
    fun `an unknown window type is stored and returned as it is`() = runBlocking<Unit> {
        val (_, p) = chatProcess(alice)
        p.emit(event(buildJsonObject {
            put("status", "allowed"); put("rateLimitType", "seven_day_haiku"); put("utilization", 0.3)
            putJsonObject("unifiedWindows") { putJsonObject("fortnight") { put("utilization", 0.1); put("resetsAt", resets.epochSecond) } }
        }))
        eventually("stored") { usage(alice).windows.size == 2 }
        assertEquals(listOf("fortnight", "seven_day_haiku"), usage(alice).windows.map { it.type })
    }

    @Test
    fun `a stage agent's event is recorded for the run owner and malformed events change nothing`() {
        world.launcher.stage = { p ->
            p.init()
            // Malformed first: none of these may disturb the stage.
            p.emit(event(null))
            p.emit(buildJsonObject { put("type", "rate_limit_event"); put("rate_limit_info", "five_hour") })
            p.emit(buildJsonObject { put("type", "rate_limit_event"); put("rate_limit_info", buildJsonArray { }) })
            p.emit(event(buildJsonObject { put("rateLimitType", 7); put("utilization", "lots"); putJsonObject("unifiedWindows") { put("five_hour", "x") } }))
            p.emit(event(buildJsonObject { put("rateLimitType", "no spaces allowed"); put("utilization", 0.5) }))
            if (p.spec.kind == "builder") p.emit(event(windows(fiveHour = 0.61)))
            when (p.spec.kind) {
                "spec-writer" -> p.result("## Spec")
                "builder" -> p.result("Built.")
                "approver" -> p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") })
                else -> p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
            }
            p.exit(0)
        }
        val r = runBlocking { s.orchestrator.start(alice, "demo-app", 1, null) }
        val done = world.awaitRun(r.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertEquals(0.61, usage(alice).windows.single { it.type == "five_hour" }.utilization)
        assertEquals(emptyList(), usage(bob).windows)
        // The malformed events are still in the transcript of a stage.
        val spec = s.sessions.byRun(r.id).first { it.kind == "spec-writer" }
        assertEquals(5, s.events.list(spec.id, 0, 1000).count { it.type == "claude" && it.payload.toString().contains("rate_limit_event") })
        // Stage costs count as spend: 0.01 per stage.
        assertTrue(usage(alice).spend.todayUsd > 0.05, usage(alice).spend.toString())
    }

    @Test
    fun `a process on an API key or on a replaced credential records no windows`() = runBlocking<Unit> {
        val carl = world.user("carl", claude = false)
        s.users.setClaude(carl.id, "api_key", s.box.encrypt("sk-ant-api-carl", "carl"))
        val (_, cp) = chatProcess(s.users.byId(carl.id)!!)
        emitAndSettle(cp, event(windows()), carl, 0.02)
        assertEquals("api_key", usage(carl).credentialKind)
        assertEquals(0, s.db.queryOne("SELECT count(*) AS n FROM claude_usage_windows WHERE user_id = ?", carl.id) { it.getInt("n") })

        val (_, ap) = chatProcess(alice)
        s.users.setClaude(alice.id, "oauth_token", s.box.encrypt("sk-ant-oat-alice-new", "alice"))
        emitAndSettle(ap, event(windows()), alice, 0.02)
        assertEquals(emptyList(), usage(alice).windows)
        // Removed credential: nothing either.
        s.users.setClaude(alice.id, null, null)
        emitAndSettle(ap, event(windows()), alice, 0.03)
        assertEquals(0, s.db.queryOne("SELECT count(*) AS n FROM claude_usage_windows WHERE user_id = ?", alice.id) { it.getInt("n") })
    }

    @Test
    fun `no more than 20 window types are kept per user`() = runBlocking<Unit> {
        val (_, p) = chatProcess(alice)
        p.emit(event(buildJsonObject { putJsonObject("unifiedWindows") { for (i in 10..29) putJsonObject("w$i") { put("utilization", 0.1) } } }))
        eventually("20 stored") { usage(alice).windows.size == 20 }
        p.emit(event(buildJsonObject {
            putJsonObject("unifiedWindows") { putJsonObject("w10") { put("utilization", 0.9) }; putJsonObject("extra") { put("utilization", 0.2) } }
        }))
        eventually("existing one updated") { usage(alice).windows.single { it.type == "w10" }.utilization == 0.9 }
        assertTrue(usage(alice).windows.none { it.type == "extra" })
    }

    // ------------------------------------------------------------ spend

    @Test
    fun `spend - per-result increases, per user, kept when the chat is closed`() = runBlocking<Unit> {
        val (chat, p) = chatProcess(alice) // first turn: 0.01
        p.emit(costResult(0.03))           // cumulative: +0.02
        eventually("0.03") { close(usage(alice).spend.todayUsd, 0.03) }
        p.emit(costResult(0.03))           // no increase
        p.emit(costResult(0.025))          // a lower total never reduces the spend
        p.emit(costResult(0.04))           // the increase since the previous result: +0.015
        eventually("0.045") { close(usage(alice).spend.todayUsd, 0.045) }
        assertTrue(close(usage(alice).spend.monthUsd, 0.045))
        assertEquals(0.0, usage(bob).spend.todayUsd)
        assertEquals("2026-03-15", usage(alice).spend.day)
        assertEquals("2026-03", usage(alice).spend.month)

        s.agents.closeChat(alice, s.sessions.get(chat.id)!!)
        assertTrue(close(usage(alice).spend.todayUsd, 0.045), "closing the chat keeps the spend")
    }

    @Test
    fun `spend - today and this calendar month, UTC`() {
        clock.now = Instant.parse("2026-02-28T23:30:00Z")
        s.usage.addSpend(alice.id, 1.0)       // previous month
        clock.now = Instant.parse("2026-03-01T00:10:00Z")
        s.usage.addSpend(alice.id, 2.0)       // earlier day of this month
        clock.now = Instant.parse("2026-03-02T09:00:00Z")
        s.usage.addSpend(alice.id, 4.0)
        s.usage.addSpend(alice.id, 0.5)
        s.usage.addSpend(alice.id, -3.0)      // ignored
        val sp = usage(alice).spend
        assertEquals(4.5, sp.todayUsd)
        assertEquals(6.5, sp.monthUsd)
        assertEquals("2026-03-02", sp.day)
        assertEquals("2026-03", sp.month)
    }

    // ------------------------------------------------------------ API

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String): HttpClient {
        val c = createClient { install(HttpCookies); install(WebSockets) }
        assertEquals(200, c.post("/api/auth/login") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
        }.status.value)
        return c
    }

    @Test
    fun `the endpoint answers the caller's own usage only`() = testApplication {
        application { agernModule(s) }
        assertEquals(401, createClient { }.get("/api/me/usage").status.value)

        val (_, p) = chatProcess(alice)
        p.emit(event(windows()))
        eventually("stored") { usage(alice).windows.size == 2 }

        val a = login("alice", "pw-a")
        val b = login("bob", "pw-b")
        val ua = json(a.get("/api/me/usage").bodyAsText())
        assertEquals("oauth_token", ua["credentialKind"]!!.jsonPrimitive.content)
        assertEquals(2, ua["windows"]!!.jsonArray.size)
        assertEquals(0.42, ua["windows"]!!.jsonArray[0].jsonObject["utilization"]!!.jsonPrimitive.content.toDouble())
        assertEquals(0.01, ua["spend"]!!.jsonObject["todayUsd"]!!.jsonPrimitive.content.toDouble())
        assertEquals("2026-03-15", ua["spend"]!!.jsonObject["day"]!!.jsonPrimitive.content)
        // A parameter naming someone else is ignored: still the caller's own data.
        val ub = json(b.get("/api/me/usage?user=alice&userId=${alice.id}").bodyAsText())
        assertEquals(0, ub["windows"]!!.jsonArray.size)
        assertEquals(0.0, ub["spend"]!!.jsonObject["todayUsd"]!!.jsonPrimitive.content.toDouble())
        // No such route for admins either.
        val root = login("root", "pw-r")
        assertEquals(404, root.get("/api/admin/users/${alice.id}/usage").status.value)
        assertEquals(404, root.get("/api/users/${alice.id}/usage").status.value)
        assertTrue(!root.get("/api/me/usage").bodyAsText().contains("five_hour"))

        // No credential: kind null, no windows, the spend still shown.
        s.users.setClaude(alice.id, null, null)
        val none = json(a.get("/api/me/usage").bodyAsText())
        assertEquals(JsonNull, none["credentialKind"])
        assertEquals(0, none["windows"]!!.jsonArray.size)
        assertEquals(0.01, none["spend"]!!.jsonObject["todayUsd"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `replacing or removing the credential clears the windows and keeps the spend`() = testApplication {
        application { agernModule(s) }
        val a = login("alice", "pw-a")
        suspend fun putCred(kind: String, v: String) = assertEquals(200, a.put("/api/me/claude-credential") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"kind":"$kind","value":"$v"}""")
        }.status.value)
        suspend fun record(n: Int) {
            val fresh = s.users.byId(alice.id)!!
            val (_, p) = chatProcess(fresh)
            p.emit(event(windows()))
            eventually("stored $n") { usage(alice).windows.size == 2 }
        }
        record(1)
        putCred("oauth_token", "sk-ant-oat-alice-other") // same kind
        assertEquals(0, json(a.get("/api/me/usage").bodyAsText())["windows"]!!.jsonArray.size)
        val spend = usage(alice).spend.todayUsd
        assertTrue(spend > 0.0)

        record(2)
        putCred("api_key", "sk-ant-api-alice") // other kind
        assertEquals(0, s.db.queryOne("SELECT count(*) AS n FROM claude_usage_windows WHERE user_id = ?", alice.id) { it.getInt("n") })
        putCred("oauth_token", "sk-ant-oat-alice-third")

        record(3)
        assertEquals(200, a.delete("/api/me/claude-credential") { header("X-Factory-Request", "1") }.status.value)
        assertEquals(0, s.db.queryOne("SELECT count(*) AS n FROM claude_usage_windows WHERE user_id = ?", alice.id) { it.getInt("n") })
        assertTrue(close(usage(alice).spend.todayUsd, spend + 0.02), "spend is kept: ${usage(alice).spend}")
    }

    @Test
    fun `the usage frame reaches only the affected user's feed`() = testApplication {
        application { agernModule(s) }
        val a = login("alice", "pw-a")
        val b = login("bob", "pw-b")
        for (c in listOf(a, b)) c.put("/api/me/github-token") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"token":"fake-x"}""")
        }
        val aFeed = a.webSocketSession("/api/ws/feed")
        val bFeed = b.webSocketSession("/api/ws/feed")
        withTimeout(10_000) { while (s.bus.feedSubscribers.value < 2) delay(10) }

        suspend fun DefaultClientWebSocketSession.until(pred: (JsonObject) -> Boolean): List<JsonObject> = withTimeout(10_000) {
            val seen = mutableListOf<JsonObject>()
            while (true) {
                val f = incoming.receive() as? Frame.Text ?: continue
                val o = json(f.readText())
                seen += o
                if (pred(o)) return@withTimeout seen
            }
            @Suppress("UNREACHABLE_CODE") seen
        }

        val (_, p) = chatProcess(s.users.byId(alice.id)!!)
        p.emit(event(windows()))
        // Alice hears about it (spend of the first turn and the recorded windows).
        val aSeen = aFeed.until { it["type"]!!.jsonPrimitive.content == "usage" }
        assertEquals(setOf("type"), aSeen.last().filterValues { it != JsonNull }.keys, "the frame carries no data")
        eventually("stored") { usage(alice).windows.size == 2 }

        // Bob: a later public frame (his own new chat) arrives without any usage frame before it.
        val bobChat = s.agents.createChat(s.users.byId(bob.id)!!, "demo-app", null)
        val bSeen = bFeed.until { it["type"]!!.jsonPrimitive.content == "session" && it["session"]!!.jsonObject["id"]!!.jsonPrimitive.content == bobChat.id.toString() }
        assertTrue(bSeen.none { it["type"]!!.jsonPrimitive.content == "usage" }, bSeen.toString())

        // A credential change notifies its owner.
        assertEquals(200, a.delete("/api/me/claude-credential") { header("X-Factory-Request", "1") }.status.value)
        aFeed.until { it["type"]!!.jsonPrimitive.content == "usage" }
        aFeed.close(); bFeed.close()
    }
}
