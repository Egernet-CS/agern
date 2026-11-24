package dk.egernet.factory

import dk.egernet.factory.images.CodexImageProvider
import dk.egernet.factory.images.ImageProvider
import dk.egernet.factory.images.ImageRequest
import dk.egernet.factory.mcp.McpGrant
import dk.egernet.factory.mcp.McpRegistry
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
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
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Codex device login (`/api/me/image-provider/codex/login`) and generation with it (docs/ARCHITECTURE.md §4 "Codex login"). */
class CodexLoginTest {
    private val base: Path = Files.createTempDirectory("agern-codex-login").resolve("codex")
    private val env = ConcurrentHashMap<String, String>()
    private val codex = CodexImageProvider(CodexProviderTest.fakeCodex(), CodexProviderTest.PATH, base, Duration.ofSeconds(30), env)
    private val worlds = CopyOnWriteArrayList<TestWorld>()
    private fun world(vararg extra: ImageProvider) =
        TestWorld(twoFactor = false, imageProviders = listOf(codex, RecordingImageProvider()) + extra).also { worlds += it }

    @AfterTest fun tearDown() {
        worlds.forEach { it.close() }
        base.parent.toFile().deleteRecursively()
    }

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String): HttpClient {
        val c = createClient { install(HttpCookies) }
        assertEquals(200, c.post("/api/auth/login") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
        }.status.value)
        return c
    }

    private val bodies = CopyOnWriteArrayList<String>()
    private suspend fun HttpResponse.text(): String = bodyAsText().also { bodies += it }
    private fun json(s: String): JsonObject = AppJson.parseToJsonElement(s).jsonObject
    private fun JsonObject.str(k: String): String? = this[k]?.jsonPrimitive?.takeIf { it.isString }?.content

    private suspend fun HttpClient.startLogin() = post("/api/me/image-provider/codex/login") { header("X-Factory-Request", "1") }
    private suspend fun HttpClient.loginStatus() = json(get("/api/me/image-provider/codex/login").text())
    private suspend fun HttpClient.cancelLogin() = delete("/api/me/image-provider/codex/login") { header("X-Factory-Request", "1") }

    private suspend fun HttpClient.awaitStatus(vararg wanted: String): JsonObject {
        val end = System.currentTimeMillis() + 15_000
        while (true) {
            val s = loginStatus()
            if (s.str("status") in wanted || System.currentTimeMillis() > end) return s
            delay(100)
        }
    }

    private fun leftovers(): List<Path> = if (Files.isDirectory(base)) Files.list(base).use { it.toList() } else emptyList()

    private fun assertNoSecretsInBodies() {
        for (b in bodies) assertFalse("fake-refresh" in b || "refresh_token" in b, b)
    }

    @Test
    fun `a device login is started, shown to its owner, and stored encrypted on success`() = testApplication {
        val w = world()
        application { agernModule(w.services) }
        val alice = login("alice", "pw-a")
        assertEquals("none", alice.loginStatus().str("status"))

        val r = alice.startLogin()
        assertEquals(200, r.status.value, r.bodyAsText())
        val started = json(r.text())
        assertEquals("pending", started.str("status"))
        assertEquals("https://auth.openai.com/codex/device", started.str("verificationUrl"))
        assertEquals("FAKE-12345", started.str("userCode"))
        assertTrue(started.str("expiresAt") != null)

        val done = alice.awaitStatus("connected", "failed")
        assertEquals("connected", done.str("status"), done.toString())
        assertNull(done.str("userCode"))
        assertNull(done.str("verificationUrl"))
        val u = w.services.users.byUsername("alice")!!
        assertEquals("codex", u.imageProvider)
        assertEquals("default", u.imageModel)
        assertEquals("""{"tokens": {"refresh_token": "fake-refresh-1"}}""", w.services.box.decrypt(u.imageKeyEnc!!, "alice"))
        val me = json(alice.get("/api/me").text())
        assertEquals("codex", me.str("imageProvider"))
        assertEquals("default", me.str("imageModel"))
        // Bob never sees alice's login.
        assertEquals("none", login("bob", "pw-b").loginStatus().str("status"))
        assertTrue(leftovers().isEmpty())
        assertNoSecretsInBodies()
    }

    @Test
    fun `logins can be cancelled, replaced, and fail on a refused URL, a failing CLI or a missing code`() = testApplication {
        val w = world()
        application { agernModule(w.services) }
        val alice = login("alice", "pw-a")

        env["FAKE_CODEX_LOGIN"] = "wait"
        assertEquals("pending", json(alice.startLogin().text()).str("status"))
        assertEquals("none", json(alice.cancelLogin().text()).str("status"))
        assertEquals("none", alice.loginStatus().str("status"))
        assertEquals(200, alice.cancelLogin().status.value, "idempotent")

        // A second start replaces the first: one process (and directory) at a time.
        assertEquals("pending", json(alice.startLogin().text()).str("status"))
        assertEquals("pending", json(alice.startLogin().text()).str("status"))
        assertEquals(1, leftovers().size)
        // DELETE /me/image-provider cancels a pending login, too.
        assertEquals(200, alice.delete("/api/me/image-provider") { header("X-Factory-Request", "1") }.status.value)
        assertEquals("none", alice.loginStatus().str("status"))

        // (Another user from here on: five starts per user within 15 minutes.)
        val bob = login("bob", "pw-b")
        env["FAKE_CODEX_LOGIN"] = "fail"
        json(bob.startLogin().text())
        val failed = bob.awaitStatus("failed")
        assertEquals("failed", failed.str("status"))
        assertEquals("Codex sign-in failed. Try again.", failed.str("error"))

        env["FAKE_CODEX_LOGIN"] = "badurl"
        val bad = json(bob.startLogin().text())
        assertEquals("failed", bad.str("status"))
        assertNull(bad.str("userCode"))
        assertEquals("failed", bob.loginStatus().str("status"))
        assertNull(w.services.users.byUsername("bob")!!.imageKeyEnc)

        env["FAKE_CODEX_LOGIN"] = "nocode"
        val none = bob.startLogin()
        assertEquals(502, none.status.value)
        val err = json(none.text())["error"]!!.jsonObject
        assertEquals("codex_login_failed", err.str("code"))
        assertEquals(
            "Codex did not offer a device code. Enable device code login in your ChatGPT security settings and try again.",
            err.str("message"),
        )
        assertTrue(leftovers().isEmpty())
        assertNoSecretsInBodies()
    }

    @Test
    fun `login starts are limited, API keys are refused for Codex, and the routes need a session and the CSRF header`() = testApplication {
        val w = world()
        application { agernModule(w.services) }
        val alice = login("alice", "pw-a")
        env["FAKE_CODEX_LOGIN"] = "wait"
        repeat(5) { assertEquals(200, alice.startLogin().status.value) }
        val sixth = alice.startLogin()
        assertEquals(429, sixth.status.value)
        assertEquals("too_many_attempts", json(sixth.text())["error"]!!.jsonObject.str("code"))
        alice.cancelLogin()

        val put = alice.put("/api/me/image-provider") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json)
            setBody("""{"provider":"codex","model":"default","apiKey":"{}"}""")
        }
        assertEquals(409, put.status.value)
        val e = json(put.text())["error"]!!.jsonObject
        assertEquals("codex_login_required", e.str("code"))
        assertEquals("OpenAI Codex is connected by signing in with ChatGPT, not with an API key.", e.str("message"))

        assertEquals(401, createClient { }.post("/api/me/image-provider/codex/login") { header("X-Factory-Request", "1") }.status.value)
        assertEquals(401, createClient { }.get("/api/me/image-provider/codex/login").status.value)
        assertEquals(403, alice.post("/api/me/image-provider/codex/login").status.value)
        assertEquals(403, alice.delete("/api/me/image-provider/codex/login").status.value)
    }

    @Test
    fun `without the CLI the login routes answer 409`() = testApplication {
        val missing = CodexImageProvider("/nonexistent/codex", CodexProviderTest.PATH, base, Duration.ofSeconds(5))
        val w = TestWorld(twoFactor = false, imageProviders = listOf(missing)).also { worlds += it }
        application { agernModule(w.services) }
        val alice = login("alice", "pw-a")
        val r = alice.startLogin()
        assertEquals(409, r.status.value)
        assertEquals("image_provider_unavailable_here", json(r.text())["error"]!!.jsonObject.str("code"))
        val noCodex = TestWorld(twoFactor = false, imageProviders = listOf(RecordingImageProvider())).also { worlds += it }
        assertNull(noCodex.services.codexLogins)
    }

    private fun chatToken(w: TestWorld, user: String): String {
        val u = w.user(user)
        val chat = w.services.agents.createChat(u, "demo-app", null)
        return w.services.mcpRegistry.issue(McpGrant(chat.id, u.id, "demo-app", null, McpRegistry.CHAT_TOOLS))
    }

    @Test
    fun `a chat generates with the Codex login and keeps the refreshed login`() {
        val w = world()
        val token = chatToken(w, "alice")
        w.imageKey("alice", CodexProviderTest.LOGIN, provider = "codex", model = "default")
        val a = Mcp.generate(w, token, buildJsonObject { put("prompt", "fake:refresh A squirrel"); put("purpose", "app icon"); put("count", 2) })
        assertFalse(a.isError, a.text)
        assertEquals(2, a.json["images"].toString().split("\"id\"").size - 1)
        assertFalse("refresh" in a.text)
        val stored = w.services.box.decrypt(w.services.users.byUsername("alice")!!.imageKeyEnc!!, "alice")
        assertTrue("fake-refresh-2" in stored, stored)
        assertTrue(leftovers().isEmpty())
    }

    /** A device-login provider whose calls can be observed: concurrency, and a removal during the call. */
    private class SlowLogin : ImageProvider {
        override val id = "slow"
        override val label = "Slow login"
        override val models = listOf("default")
        override val defaultModel = "default"
        override val supportsTransparency = true
        override val keyPlaceholder = ""
        override val auth = ImageProvider.DEVICE_LOGIN
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val keys = CopyOnWriteArrayList<String>()
        @Volatile var during: () -> Unit = {}
        override fun validate(key: String, model: String) {}
        override fun generate(key: String, model: String, request: ImageRequest) = error("the renew overload is used")
        override fun generate(key: String, model: String, request: ImageRequest, renew: (String) -> Unit): List<ByteArray> {
            val n = inFlight.incrementAndGet()
            maxInFlight.accumulateAndGet(n) { a, b -> maxOf(a, b) }
            try {
                keys += key
                Thread.sleep(300)
                during()
                renew("$key+1")
                return listOf(png(request.width, request.height, false))
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    @Test
    fun `one user's device-login generations never overlap and read the login inside the lock`() {
        val slow = SlowLogin()
        val w = world(slow)
        val t1 = chatToken(w, "alice")
        val t2 = chatToken(w, "alice")
        w.imageKey("alice", "login-1", provider = "slow", model = "default")
        val answers = CopyOnWriteArrayList<Mcp.Answer>()
        val threads = listOf(t1, t2).map { t -> thread { answers += Mcp.generate(w, t) } }
        threads.forEach { it.join(20_000) }
        assertEquals(2, answers.size)
        assertTrue(answers.none { it.isError }, answers.toString())
        assertEquals(1, slow.maxInFlight.get())
        // The second call used the login the first one renewed.
        assertEquals(setOf("login-1", "login-1+1"), slow.keys.toSet())
        assertEquals("login-1+1+1", w.services.box.decrypt(w.services.users.byUsername("alice")!!.imageKeyEnc!!, "alice"))
    }

    @Test
    fun `a removal during the call wins over the refreshed login`() {
        val slow = SlowLogin()
        val w = world(slow)
        val token = chatToken(w, "alice")
        w.imageKey("alice", "login-1", provider = "slow", model = "default")
        val alice = w.services.users.byUsername("alice")!!
        slow.during = { w.services.users.setImageProvider(alice.id, null, null, null) }
        assertFalse(Mcp.generate(w, token).isError)
        assertNull(w.services.users.byUsername("alice")!!.imageKeyEnc)
        // A re-login during the call wins, too.
        w.imageKey("alice", "login-1", provider = "slow", model = "default")
        slow.during = { w.imageKey("alice", "login-new", provider = "slow", model = "default") }
        assertFalse(Mcp.generate(w, token).isError)
        assertEquals("login-new", w.services.box.decrypt(w.services.users.byUsername("alice")!!.imageKeyEnc!!, "alice"))
    }

    @Test
    fun `an expired Codex login is reported in plain words`() {
        val w = world()
        val token = chatToken(w, "alice")
        w.imageKey("alice", """{"tokens":{}}""", provider = "codex", model = "default")
        val q = Mcp.generate(w, token, buildJsonObject { put("prompt", "fake:limit"); put("purpose", "icon") })
        assertTrue(q.isError)
        assertEquals("Your OpenAI Codex account has no quota or credit left for image generation.", q.text)
        val n = Mcp.generate(w, token, buildJsonObject { put("prompt", "fake:none"); put("purpose", "icon") })
        assertEquals("OpenAI Codex answered without a usable image.", n.text)
        // A login that is no longer usable: the device-login wording.
        w.services.users.setImageProvider(w.services.users.byUsername("alice")!!.id, "codex", "default", w.services.box.encrypt("not json", "alice"))
        val bad = Mcp.generate(w, token)
        assertEquals("Your sign-in to OpenAI Codex is no longer valid. Connect it again under Settings → Image generation.", bad.text)
        assertTrue(leftovers().isEmpty())
    }
}
