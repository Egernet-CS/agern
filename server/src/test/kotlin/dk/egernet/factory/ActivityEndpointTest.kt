package dk.egernet.factory

import dk.egernet.factory.api.isLoopbackAddress
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `GET /api/internal/activity`: the host-local idle signal the upgrade timer waits for. */
class ActivityEndpointTest {
    private val world = TestWorld()
    @AfterTest fun tearDown() = world.close()

    private fun activity(body: String) = AppJson.parseToJsonElement(body).jsonObject

    @Test
    fun `idle unless runs are in progress or agents are working, needs_human and idle chats do not count`() = testApplication {
        application { agernModule(world.services) }
        val c = createClient {}

        var a = activity(c.get("/api/internal/activity").bodyAsText())
        assertTrue(a["idle"]!!.jsonPrimitive.boolean)
        assertEquals(0, a["activeRuns"]!!.jsonPrimitive.int)
        assertEquals(0, a["busyAgents"]!!.jsonPrimitive.int)
        assertEquals(BuildInfo.version, a["version"]!!.jsonPrimitive.content)

        val alice = world.user("alice")
        val run = world.services.runs.createIfNoActive("demo-app", 1, "Do it", "task", 2, alice.id, null)!!
        a = activity(c.get("/api/internal/activity").bodyAsText())
        assertFalse(a["idle"]!!.jsonPrimitive.boolean)
        assertEquals(1, a["activeRuns"]!!.jsonPrimitive.int)

        // Waiting for a human survives a restart, so it does not block an upgrade.
        world.services.runs.setStatus(run.id, RunStatus.NEEDS_HUMAN, "waiting")
        assertTrue(activity(c.get("/api/internal/activity").bodyAsText())["idle"]!!.jsonPrimitive.boolean)

        val chat = world.services.sessions.create("demo-app", "chat", "sonnet", SessionStatus.RUNNING, alice.id, "chat")
        a = activity(c.get("/api/internal/activity").bodyAsText())
        assertFalse(a["idle"]!!.jsonPrimitive.boolean)
        assertEquals(1, a["busyAgents"]!!.jsonPrimitive.int)

        // A chat between turns is resumable after a restart.
        world.services.sessions.setStatus(chat.id, SessionStatus.IDLE)
        assertTrue(activity(c.get("/api/internal/activity").bodyAsText())["idle"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `requests relayed by a reverse proxy are refused like an unknown endpoint`() = testApplication {
        application { agernModule(world.services) }
        val c = createClient {}
        for (h in listOf("X-Forwarded-For", "Forwarded", "X-Forwarded-Host", "X-Real-IP")) {
            val r = c.get("/api/internal/activity") { header(h, "192.168.1.20") }
            assertEquals(404, r.status.value, h)
            assertEquals("not_found", activity(r.bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content, h)
        }
    }

    @Test
    fun `only loopback literals count as local`() {
        for (a in listOf("127.0.0.1", "127.1.2.3", "::1", "[::1]", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1", "localhost")) {
            assertTrue(isLoopbackAddress(a), a)
        }
        for (a in listOf("192.168.1.5", "10.0.0.1", "::ffff:192.168.1.5", "fe80::1", "0.0.0.0", "", "example.com", "127.0.0.1.nip.io")) {
            assertFalse(isLoopbackAddress(a), a)
        }
    }
}
