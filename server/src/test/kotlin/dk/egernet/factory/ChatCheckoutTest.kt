package dk.egernet.factory

import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.git.CliGitOps
import dk.egernet.factory.github.FakeGitHub
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A chat keeps the owner's main checkout in step with `origin/<default branch>` (docs/ARCHITECTURE.md §5 "Workspaces"):
 * real git with a local bare repository as `origin`, the fake GitHub and the fake launcher.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatCheckoutTest {
    // One server (and database pool) for the whole class; every test has its own user, clone and origin.
    private val tmp: Path = Files.createTempDirectory("agern-chat-checkout")
    private val cfg = testConfig(Files.createDirectories(tmp.resolve("data")), twoFactor = false)
    private val launcher = FakeLauncher()
    private val s = Services(
        cfg, TestDb.fresh(), FakeGitHub.seeded("test-org"),
        CliGitOps(System.getenv("PATH") ?: "/usr/bin:/bin", "https://github.invalid/", localOnly = false), launcher,
    ).also { it.workspaces.mainFetchMinIntervalMs = 0 }
    private val cases = AtomicInteger()
    private lateinit var origin: Path
    private lateinit var upstream: Path
    private lateinit var main: Path
    private lateinit var alice: UserRecord

    @BeforeTest fun setUp() {
        val n = cases.incrementAndGet()
        val work = Files.createDirectories(tmp.resolve("case$n"))
        origin = work.resolve("origin.git")
        upstream = work.resolve("upstream")
        alice = user("alice$n")
        main = s.workspaces.mainDir(alice.username, "demo-app")
        launcher.chatInputs.clear()
        gitIn(work, "init", "-q", "--bare", "-b", "main", origin.toString())
        Files.createDirectories(upstream)
        gitIn(upstream, "init", "-q", "-b", "main")
        gitIn(upstream, "remote", "add", "origin", origin.toString())
        Files.writeString(upstream.resolve("README.md"), "# demo\n")
        gitIn(upstream, "add", "README.md")
        gitIn(upstream, "commit", "-q", "-m", "Initial commit")
        gitIn(upstream, "push", "-q", "origin", "main")
        Files.createDirectories(main.parent)
        // Already cloned: ensureClone only fetches.
        gitIn(work, "clone", "-q", origin.toString(), main.toString())
    }

    @AfterAll fun tearDown() {
        s.stopAndReleaseDb()
        tmp.toFile().deleteRecursively()
    }

    private fun gitIn(cwd: Path, vararg args: String): String {
        val pb = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false") + args)
            .directory(cwd.toFile()).redirectErrorStream(true)
        pb.environment()["HOME"] = tmp.toString()
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${args.joinToString(" ")} failed: $out" }
        return out
    }

    private fun head(dir: Path) = gitIn(dir, "rev-parse", "HEAD").trim()

    private fun pushCommit(file: String = "feature.txt"): String {
        Files.writeString(upstream.resolve(file), "new $file\n")
        gitIn(upstream, "add", file)
        gitIn(upstream, "commit", "-q", "-m", "Add $file")
        gitIn(upstream, "push", "-q", "origin", "main")
        return head(upstream)
    }

    private fun user(name: String): UserRecord {
        val u = s.users.upsertOnLogin(name, name, false)
        s.users.setClaude(u.id, "oauth_token", s.box.encrypt("sk-ant-oat-$name", name))
        s.users.setGithub(u.id, name, s.box.encrypt("fake-$name", name))
        return s.users.byId(u.id)!!
    }

    private fun checkoutEvents(id: UUID): List<JsonObject> =
        s.events.list(id, 0, 1000).filter { it.type == "checkout" }.map { it.payload.jsonObject }

    private fun await(what: String, cond: () -> Boolean) = runBlocking {
        try {
            withTimeout(20_000) { while (!cond()) delay(50) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for $what")
        }
    }

    /** Sends [text] and waits for the fake agent's answer (and the chat to be idle again). */
    private fun send(u: UserRecord, chatId: UUID, text: String, attachments: List<UUID> = emptyList()) {
        val before = launcher.chatInputs.size
        s.agents.sendInput(u, s.sessions.get(chatId)!!, text, attachments)
        await("the reply to '$text'") {
            launcher.chatInputs.size > before && s.sessions.get(chatId)!!.status == SessionStatus.IDLE
        }
    }

    private fun str(o: JsonObject, key: String) = o[key]?.jsonPrimitive?.contentOrNull

    @Test
    fun `a new chat fast-forwards a clean checkout to the merged code`() {
        val tip = pushCommit()
        val chat = s.agents.createChat(alice, "demo-app", null)
        send(alice, chat.id, "what is new")
        assertEquals(tip, head(main))
        assertTrue(Files.exists(main.resolve("feature.txt")))
        await("a checkout event at the tip") { checkoutEvents(chat.id).any { str(it, "commit") == tip } }
        val ev = checkoutEvents(chat.id).last()
        assertTrue(str(ev, "status") in setOf("updated", "current"), ev.toString())
        assertEquals("main", str(ev, "branch"))
        assertEquals("main", str(ev, "defaultBranch"))
        assertEquals(0, ev["behind"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `a dirty checkout is never modified and the agent is told once per launch`() {
        val old = head(main)
        pushCommit()
        Files.writeString(main.resolve("README.md"), "# local edit\n")
        val index = gitIn(main, "ls-files", "--stage")
        val chat = s.agents.createChat(alice, "demo-app", null)
        send(alice, chat.id, "what is new")

        assertEquals(old, head(main))
        assertEquals(index, gitIn(main, "ls-files", "--stage"))
        assertEquals("# local edit\n", Files.readString(main.resolve("README.md")))
        assertFalse(Files.exists(main.resolve("feature.txt")))

        val input = launcher.chatInputs.first().jsonPrimitive.content
        assertTrue(input.startsWith("<checkout_status>"), input)
        assertTrue(input.contains("1 commit behind") && input.contains("uncommitted changes"), input)
        assertTrue(input.endsWith("</checkout_status>\n\nwhat is new"), input)
        val events = checkoutEvents(chat.id)
        assertEquals(1, events.size, events.toString())
        assertEquals("behind", str(events[0], "status"))
        assertEquals("local_changes", str(events[0], "reason"))
        assertEquals(1, events[0]["behind"]?.jsonPrimitive?.intOrNull)
        assertEquals(old, str(events[0], "commit"))
        val user = s.events.list(chat.id, 0, 1000).single { it.type == "user" }
        assertEquals("what is new", str(user.payload.jsonObject, "text"))

        // The same state again: no note, no second event.
        send(alice, chat.id, "and now?")
        assertEquals("and now?", launcher.chatInputs[1].jsonPrimitive.content)
        assertEquals(1, checkoutEvents(chat.id).size)
    }

    @Test
    fun `a message to a live process fast-forwards when origin moved and tells the agent`() {
        val chat = s.agents.createChat(alice, "demo-app", null)
        send(alice, chat.id, "one")
        assertEquals("one", launcher.chatInputs[0].jsonPrimitive.content)
        assertTrue(s.agents.isLive(chat.id))
        val old = head(main)
        val tip = pushCommit()
        send(alice, chat.id, "two")
        assertEquals(tip, head(main))
        val input = launcher.chatInputs[1].jsonPrimitive.content
        assertTrue(input.startsWith("<checkout_status>"), input)
        assertTrue(input.contains("from ${old.take(7)} to ${tip.take(7)} (1 new commit from origin/main)"), input)
        assertTrue(input.endsWith("\n\ntwo"), input)
        val ev = checkoutEvents(chat.id).last()
        assertEquals("updated", str(ev, "status"))
        assertEquals(old, str(ev, "updatedFrom"))
        assertEquals(tip, str(ev, "commit"))
    }

    @Test
    fun `with an attachment the note is the first content block`() {
        pushCommit()
        Files.writeString(main.resolve("untracked.txt"), "mine\n")
        val chat = s.agents.createChat(alice, "demo-app", null)
        val file = s.attachments.upload(chat.id, alice.id, "notes.txt", "hello agent\n".toByteArray())
        send(alice, chat.id, "see attached", listOf(file.id))
        val blocks = launcher.chatInputs.first() as JsonArray
        val first = blocks[0].jsonObject
        assertEquals("text", str(first, "type"))
        assertTrue(str(first, "text")!!.startsWith("<checkout_status>"), first.toString())
        assertTrue(blocks.drop(1).any { str(it.jsonObject, "text") == "see attached" }, blocks.toString())
        assertTrue(Files.exists(main.resolve("untracked.txt")))
    }
}
