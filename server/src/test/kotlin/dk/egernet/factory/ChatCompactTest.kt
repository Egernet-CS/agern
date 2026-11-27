package dk.egernet.factory

import dk.egernet.factory.agent.LocalProcessLauncher
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.github.FakeGitHub
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `/compact [what to keep]` in a chat, through the real process launcher and the fake CLI (src/test/resources/fake-claude). */
class ChatCompactTest {
    private val bin = Paths.get(javaClass.getResource("/fake-claude")!!.toURI()).toString()
    private val cfg = testConfig(Files.createTempDirectory("agern-compact"), twoFactor = false)
    private val s = Services(
        cfg, TestDb.fresh(), FakeGitHub.seeded("test-org"), FakeGitOps(),
        LocalProcessLauncher(bin, "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin"),
    )

    @AfterTest fun tearDown() = s.stopAndReleaseDb()

    private fun user(name: String): UserRecord {
        val u = s.users.upsertOnLogin(name, name, false)
        s.users.setClaude(u.id, "oauth_token", s.box.encrypt("sk-ant-oat-$name", name))
        s.users.setGithub(u.id, name, s.box.encrypt("fake-$name", name))
        return s.users.byId(u.id)!!
    }

    private fun texts(id: UUID) = s.events.list(id, 0, 1000).map { it.type to it.payload.toString() }

    private fun await(id: UUID, what: String, cond: (List<Pair<String, String>>) -> Boolean) = runBlocking {
        try {
            withTimeout(20_000) { while (!cond(texts(id))) delay(50) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for $what; events: ${texts(id)}")
        }
    }

    private fun idle(id: UUID) = await(id, "idle") { s.sessions.get(id)!!.status == SessionStatus.IDLE }

    private fun boundaries(id: UUID) = texts(id).count { it.first == "claude" && it.second.contains("\"compact_boundary\"") }

    /** A chat that has had one normal exchange ("hello"), idle. */
    private fun chatAfterHello(owner: UserRecord): UUID {
        val chat = s.agents.createChat(owner, "demo-app", null)
        s.agents.sendInput(owner, chat, "hello")
        await(chat.id, "first reply") { ev -> ev.any { it.second.contains("You said: hello") } }
        idle(chat.id)
        return chat.id
    }

    @Test
    fun `a fresh chat has nothing to compact and no user event is added`() {
        val alice = user("alice")
        val chat = s.agents.createChat(alice, "demo-app", null)
        val e = assertFailsWith<ApiException> { s.agents.sendInput(alice, chat, "/compact") }
        assertEquals(HttpStatusCode.Conflict, e.status)
        assertEquals("nothing_to_compact", e.code)
        assertTrue(texts(chat.id).none { it.first == "user" }, texts(chat.id).toString())
    }

    @Test
    fun `compact goes to the CLI verbatim and leaves title, turns and status as they were`() {
        val alice = user("alice")
        val id = chatAfterHello(alice)
        val turns = s.sessions.get(id)!!.numTurns
        s.agents.sendInput(alice, s.sessions.get(id)!!, "  /compact keep X ")
        await(id, "boundary") { boundaries(id) == 1 }
        await(id, "compact reply") { ev -> ev.any { it.second.contains("Compacted. Focus: keep X") } }
        idle(id)
        val ev = texts(id)
        val boundary = ev.single { it.second.contains("\"compact_boundary\"") }.second
        assertTrue(boundary.contains("\"trigger\":\"manual\""), boundary)
        assertTrue(boundary.contains("\"user_context\":\"keep X\""), boundary)
        assertTrue(ev.none { it.second.contains("You said: /compact") }, ev.toString())
        assertTrue(ev.none { it.second.contains("checkout_status") }, ev.toString())
        val after = s.sessions.get(id)!!
        assertEquals("hello", after.title)
        assertEquals(turns, after.numTurns)
        assertEquals(SessionStatus.IDLE, after.status)
    }

    @Test
    fun `compact with an attachment is refused and the attachment stays unsent`() {
        val alice = user("alice")
        val id = chatAfterHello(alice)
        val file = s.attachments.upload(id, alice.id, "notes.txt", "keep this\n".toByteArray())
        val before = texts(id).count { it.first == "user" }
        val e = assertFailsWith<ApiException> { s.agents.sendInput(alice, s.sessions.get(id)!!, "/compact", listOf(file.id)) }
        assertEquals(HttpStatusCode.UnprocessableEntity, e.status)
        assertEquals("attachments_not_allowed", e.code)
        assertNull(s.attachments.store.get(file.id)!!.sentAt)
        assertEquals(before, texts(id).count { it.first == "user" })
    }

    @Test
    fun `compact is refused while the agent is working`() {
        val alice = user("alice")
        val id = chatAfterHello(alice)
        s.agents.sendInput(alice, s.sessions.get(id)!!, "go slow")
        await(id, "slow turn running") { s.sessions.get(id)!!.status == SessionStatus.RUNNING }
        val before = texts(id).count { it.first == "user" }
        val e = assertFailsWith<ApiException> { s.agents.sendInput(alice, s.sessions.get(id)!!, "/compact") }
        assertEquals(HttpStatusCode.Conflict, e.status)
        assertEquals("agent_busy", e.code)
        assertEquals(before, texts(id).count { it.first == "user" })
        s.agents.interrupt(alice, s.sessions.get(id)!!)
        idle(id)
    }

    @Test
    fun `compact on a stopped chat resumes the conversation and compacts`() {
        val alice = user("alice")
        val id = chatAfterHello(alice)
        runBlocking { s.agents.stop(alice, s.sessions.get(id)!!) }
        assertEquals(SessionStatus.STOPPED, s.sessions.get(id)!!.status)
        s.agents.sendInput(alice, s.sessions.get(id)!!, "/compact")
        await(id, "boundary after resume") { boundaries(id) == 1 }
        idle(id)
        val ev = texts(id)
        assertTrue(ev.any { it.first == "system" && it.second.contains("Resuming conversation") }, ev.toString())
        assertTrue(ev.any { it.second.contains("Compacted.") }, ev.toString())
        assertEquals("hello", s.sessions.get(id)!!.title)
    }
}
