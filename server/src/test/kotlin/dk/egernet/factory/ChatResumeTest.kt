package dk.egernet.factory

import dk.egernet.factory.agent.LocalProcessLauncher
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.github.FakeGitHub
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Chat sessions driven through the real process launcher and the fake CLI (src/test/resources/fake-claude). */
class ChatResumeTest {
    private val bin = Paths.get(javaClass.getResource("/fake-claude")!!.toURI()).toString()
    private val cfg = testConfig(Files.createTempDirectory("agern-chat"), twoFactor = false)
    private val s = Services(
        cfg, TestDb.fresh(), FakeGitHub.seeded("test-org"), FakeGitOps(),
        LocalProcessLauncher(bin, "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin"),
    )

    @AfterTest fun tearDown() = s.stop()

    private fun user(name: String, claude: String): UserRecord {
        val u = s.users.upsertOnLogin(name, name, false)
        s.users.setClaude(u.id, "oauth_token", s.box.encrypt(claude, name))
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

    @Test
    fun `a conversation that cannot be resumed is replaced by a fresh one, once, with the pending input`() {
        val alice = user("alice", "sk-ant-oat-alice")
        val chat = s.agents.createChat(alice, "demo-app", null)
        s.agents.sendInput(alice, chat, "hello")
        await(chat.id, "first reply") { ev -> ev.any { it.second.contains("You said: hello") } }
        await(chat.id, "idle") { s.sessions.get(chat.id)!!.status == SessionStatus.IDLE && s.agents.isLive(chat.id) }
        runBlocking { s.agents.stop(alice, s.sessions.get(chat.id)!!) }
        assertEquals(SessionStatus.STOPPED, s.sessions.get(chat.id)!!.status)
        assertTrue(texts(chat.id).none { it.first == "system" && it.second.contains("\"error\"") }, "a stop is not an error: ${texts(chat.id)}")

        // The stored conversation disappears (other CLI, cleaned home, ...): resume fails, a fresh one starts.
        s.sessions.setClaudeSessionId(chat.id, "gone-${UUID.randomUUID()}")
        s.agents.sendInput(alice, s.sessions.get(chat.id)!!, "are you there")
        await(chat.id, "reply after fresh start") { ev -> ev.any { it.second.contains("You said: are you there") } }
        val ev = texts(chat.id)
        assertTrue(ev.any { it.first == "system" && it.second.contains("Previous conversation could not be resumed") }, ev.toString())
        assertTrue(ev.none { it.first == "system" && it.second.contains("exited with code") }, ev.toString())
        val cid = s.sessions.get(chat.id)!!.claudeSessionId
        assertNotNull(cid)
        assertTrue(!cid.startsWith("gone-"))

        // The new conversation resumes normally next time.
        await(chat.id, "idle again") { s.sessions.get(chat.id)!!.status == SessionStatus.IDLE }
        runBlocking { s.agents.stop(alice, s.sessions.get(chat.id)!!) }
        s.agents.sendInput(alice, s.sessions.get(chat.id)!!, "third")
        await(chat.id, "resumed reply") { e -> e.any { it.second.contains("You said: third") } }
        assertEquals(1, texts(chat.id).count { it.second.contains("could not be resumed") })
        assertEquals(cid, s.sessions.get(chat.id)!!.claudeSessionId)
    }

    @Test
    fun `a rejected Claude credential tells the user to update it in Settings, without a retry loop`() {
        val bob = user("bob", "sk-ant-oat-invalid-bob")
        val chat = s.agents.createChat(bob, "demo-app", null)
        s.agents.sendInput(bob, chat, "hi")
        await(chat.id, "auth error") { ev -> ev.any { it.first == "system" && it.second.contains("Update your Claude credential in Settings") } }
        await(chat.id, "idle") { s.sessions.get(chat.id)!!.status == SessionStatus.IDLE && !s.agents.isLive(chat.id) }
        val ev = texts(chat.id)
        assertEquals(1, ev.count { it.second.contains("Update your Claude credential") }, ev.toString())
        assertTrue(ev.none { it.second.contains("exited with code") }, ev.toString())
        assertNotEquals(SessionStatus.RUNNING, s.sessions.get(chat.id)!!.status)
    }
}
