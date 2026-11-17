package dk.egernet.factory

import dk.egernet.factory.auth.LoginThrottle
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoginThrottleTest {
    private var now = Instant.parse("2026-01-01T10:00:00Z")
    private val t = LoginThrottle(clock = { now })

    @Test
    fun `username lock after five failures, temporary and escalating but capped`() {
        repeat(4) { assertTrue(t.recordFailure("alice", "10.0.0.1").isEmpty()) }
        assertNull(t.lockedUntil("alice", "10.0.0.1"))
        val locks = t.recordFailure("Alice ", "10.0.0.2")
        assertEquals(listOf("user:alice"), locks.map { it.key }, "case and whitespace do not dodge the limit")
        assertEquals(now.plus(Duration.ofMinutes(1)), t.lockedUntil("alice", "10.9.9.9"), "locked from any address")
        assertNull(t.lockedUntil("bob", "10.0.0.1"), "other users are not affected")

        // After the lock expires, five more failures lock for twice as long, ... capped at 15 minutes.
        val expected = listOf(2L, 4L, 8L, 15L, 15L)
        for (m in expected) {
            now = t.lockedUntil("alice", "x")!!
            assertNull(t.lockedUntil("alice", "x"))
            repeat(4) { t.recordFailure("alice", "10.0.0.1") }
            t.recordFailure("alice", "10.0.0.1")
            assertEquals(now.plus(Duration.ofMinutes(m)), t.lockedUntil("alice", "x"))
        }

        // A quiet period forgets everything.
        now = now.plus(Duration.ofHours(1))
        assertNull(t.lockedUntil("alice", "10.0.0.1"))
        repeat(4) { t.recordFailure("alice", "10.0.0.3") }
        assertNull(t.lockedUntil("alice", "10.0.0.3"))
    }

    @Test
    fun `address lock after twenty failures across usernames, success only clears the username`() {
        for (i in 1..19) t.recordFailure("user$i", "10.0.0.66")
        assertNull(t.lockedUntil("someone", "10.0.0.66"))
        t.recordSuccess("user19")
        val locks = t.recordFailure("user20", "10.0.0.66")
        assertEquals(listOf("addr:10.0.0.66"), locks.map { it.key })
        assertNotNull(t.lockedUntil("anyone", "10.0.0.66"))
        assertNull(t.lockedUntil("anyone", "10.0.0.67"))

        repeat(4) { t.recordFailure("carol", "10.1.1.1") }
        t.recordSuccess("carol")
        repeat(4) { t.recordFailure("carol", "10.1.1.1") }
        assertNull(t.lockedUntil("carol", "10.1.1.2"), "a correct password resets the username counter")
    }

    @Test
    fun `failures outside the window are forgotten and the delay grows`() {
        repeat(3) { t.recordFailure("dave", "1.2.3.4") }
        assertEquals(Duration.ofMillis(750), t.failureDelay("dave"))
        now = now.plus(Duration.ofMinutes(16))
        repeat(4) { t.recordFailure("dave", "1.2.3.4") }
        assertNull(t.lockedUntil("dave", "1.2.3.4"))
        assertEquals(Duration.ofSeconds(1), t.failureDelay("dave"))
    }
}

class LoginThrottleApiTest {
    private var now = Instant.now()
    private val world = TestWorld(
        twoFactor = false,
        loginThrottle = LoginThrottle(stepDelay = Duration.ZERO, clock = { now }),
    )

    @AfterTest fun tearDown() = world.close()

    @Test
    fun `password step is rate limited with 429, lockedUntil and audit rows`() = testApplication {
        application { agernModule(world.services) }
        val c = createClient { install(HttpCookies) }
        suspend fun login(user: String, pw: String) = c.post("/api/auth/login") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json)
            setBody("""{"username":"$user","password":"$pw"}""")
        }
        assertEquals(200, login("alice", "pw-a").status.value)
        repeat(5) { assertEquals(401, login("alice", "wrong").status.value) }
        val refused = login("alice", "pw-a")
        assertEquals(429, refused.status.value, "even the right password is refused while locked")
        val err = AppJson.parseToJsonElement(refused.bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals("too_many_attempts", err["code"]!!.jsonPrimitive.content)
        val until = Instant.parse(err["lockedUntil"]!!.jsonPrimitive.content)
        assertTrue(until.isAfter(now))
        assertEquals(200, login("bob", "pw-b").status.value, "other users can still sign in")

        val alice = world.services.users.byUsername("alice")!!
        val audit = world.services.twoFactorStore.auditEvents(alice.id)
        assertEquals(5, audit.count { it == "login_failed" })
        assertTrue("login_locked" in audit, audit.toString())

        now = until.plusSeconds(1)
        assertEquals(200, login("alice", "pw-a").status.value, "the lock is temporary")
        assertEquals(200, c.get("/api/me").status.value)
    }
}
