package dk.egernet.factory

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `PUT/DELETE /api/me/image-provider` and the catalogue in `GET /api/config` (docs/ARCHITECTURE.md §6 "Generated images"). */
class ImageProviderSettingsTest {
    private val provider = RecordingImageProvider()
    private val w = TestWorld(twoFactor = false, imageProviders = listOf(provider))
    @AfterTest fun tearDown() = w.close()

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String): HttpClient {
        val c = createClient { install(HttpCookies) }
        assertEquals(200, c.post("/api/auth/login") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
        }.status.value)
        return c
    }

    private suspend fun HttpClient.save(provider: String, model: String, key: String, csrf: Boolean = true): HttpResponse =
        put("/api/me/image-provider") {
            if (csrf) header("X-Factory-Request", "1")
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"$provider","model":"$model","apiKey":"$key"}""")
        }

    private fun json(s: String) = AppJson.parseToJsonElement(s).jsonObject
    private fun error(r: String) = json(r)["error"]!!.jsonObject.let { it["code"]!!.jsonPrimitive.content to it["message"]!!.jsonPrimitive.content }
    private fun stored() = w.services.users.byUsername("alice")!!

    @Test
    fun `save, replace and remove the provider, the key never comes back`() = testApplication {
        application { agernModule(w.services) }
        val alice = login("alice", "pw-a")
        val bodies = mutableListOf<String>()

        val cfg = alice.get("/api/config").bodyAsText().also { bodies += it }
        val images = json(cfg)["images"]!!.jsonObject
        val p = images["providers"]!!.jsonArray.single().jsonObject
        assertEquals("rec", p["id"]!!.jsonPrimitive.content)
        assertEquals("Recorder", p["label"]!!.jsonPrimitive.content)
        assertEquals(listOf("maxPerRequest", "maxPerTurn", "maxPerStage", "maxEdge"), images["limits"]!!.jsonObject.keys.toList())

        val r1 = alice.save("rec", " rec-2 ", " rec-first-key ")
        assertEquals(200, r1.status.value, r1.bodyAsText())
        bodies += r1.bodyAsText()
        assertEquals("rec", json(bodies.last())["imageProvider"]!!.jsonPrimitive.content)
        assertEquals("rec-2", json(bodies.last())["imageModel"]!!.jsonPrimitive.content)
        assertEquals(listOf("rec-first-key" to "rec-2"), provider.validations.map { it.key to it.model })
        val enc = stored().imageKeyEnc!!
        assertFalse(enc.contentEquals("rec-first-key".toByteArray()))
        assertEquals("rec-first-key", w.services.box.decrypt(enc, "alice"))
        assertFailsWith<Exception> { w.services.box.decrypt(enc, "bob") }

        bodies += alice.save("rec", "rec-1", "rec-second-key").bodyAsText()
        assertEquals("rec-second-key", w.services.box.decrypt(stored().imageKeyEnc!!, "alice"))
        bodies += alice.get("/api/me").bodyAsText()
        assertEquals("rec-1", json(bodies.last())["imageModel"]!!.jsonPrimitive.content)

        val del = alice.delete("/api/me/image-provider") { header("X-Factory-Request", "1") }
        assertEquals(200, del.status.value)
        bodies += del.bodyAsText()
        assertTrue(json(bodies.last())["imageProvider"].toString() == "null")
        assertNull(stored().imageKeyEnc)
        assertNull(stored().imageProvider)
        assertEquals(200, alice.delete("/api/me/image-provider") { header("X-Factory-Request", "1") }.status.value, "idempotent")

        for (b in bodies) {
            assertFalse("rec-first-key" in b || "rec-second-key" in b, b)
            assertFalse("imageKey" in b, b)
        }
    }

    @Test
    fun `every validation failure answers its code and text and stores nothing`() = testApplication {
        application { agernModule(w.services) }
        val alice = login("alice", "pw-a")
        suspend fun refused(status: Int, code: String, text: String?, r: HttpResponse) {
            assertEquals(status, r.status.value, r.bodyAsText())
            val (c, m) = error(r.bodyAsText())
            assertEquals(code, c)
            if (text != null) assertEquals(text, m)
        }
        refused(400, "bad_request", null, alice.save("other", "rec-1", "rec-key"))
        refused(400, "bad_request", null, alice.save("rec", "bad model!", "rec-key"))
        refused(400, "bad_request", null, alice.save("rec", "", "rec-key"))
        refused(422, "image_key_invalid", "The API key looks malformed", alice.save("rec", "rec-1", "two words"))
        refused(422, "image_key_invalid", "The API key looks malformed", alice.save("rec", "rec-1", "  "))
        assertTrue(provider.validations.isEmpty(), "malformed input never reaches the provider")
        refused(422, "image_key_invalid", "Recorder rejected the API key. Check that it is complete and active.", alice.save("rec", "rec-1", "bad-key"))
        refused(422, "image_model_unknown", "Recorder does not know the model 'nope' for this key.", alice.save("rec", "nope", "rec-key"))
        refused(502, "image_provider_unavailable", "Could not reach Recorder to check the key. Nothing was saved.", alice.save("rec", "rec-1", "down-key"))
        assertNull(stored().imageKeyEnc)
        assertNull(stored().imageProvider)

        // Authentication and the CSRF header.
        assertEquals(401, createClient { }.put("/api/me/image-provider") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"provider":"rec","model":"rec-1","apiKey":"k"}""")
        }.status.value)
        assertEquals(403, alice.save("rec", "rec-1", "rec-key", csrf = false).status.value)
        assertEquals(403, alice.delete("/api/me/image-provider").status.value)
        assertNull(stored().imageKeyEnc)
    }

    @Test
    fun `the default catalogue offers OpenAI, Gemini and Codex, plus the fake provider only in fake-GitHub mode`() {
        val plain = TestWorld(twoFactor = false, tweak = { it.copy(codexBin = "/nonexistent/codex") })
        val fake = TestWorld(twoFactor = false, tweak = { it.copy(fakeGithub = true, codexBin = CodexProviderTest.fakeCodex()) })
        try {
            assertEquals(listOf("openai", "gemini", "codex"), plain.services.images.catalogue().providers.map { it.id })
            assertEquals(listOf("openai", "gemini", "codex", "fake"), fake.services.images.catalogue().providers.map { it.id })
            val missing = plain.services.images.catalogue().providers
            assertEquals(
                listOf(Triple("api_key", true, null), Triple("api_key", true, null), Triple("device_login", false, "The Codex CLI is not installed on this server.")),
                missing.map { Triple(it.auth, it.available, it.unavailableReason) },
            )
            val codex = fake.services.images.catalogue().providers.single { it.id == "codex" }
            assertEquals(listOf("OpenAI Codex", "default", "true", "true"), listOf(codex.label, codex.defaultModel, "${codex.available}", "${codex.supportsTransparency}"))
            assertEquals(listOf("default"), codex.models)
            assertNull(codex.unavailableReason)
        } finally {
            plain.close(); fake.close()
        }
    }

    @Test
    fun `five rejected keys within 15 minutes block further saves without calling the provider`() {
        val clock = MutableClock(java.time.Instant.parse("2026-11-22T10:00:00Z"))
        val p = RecordingImageProvider()
        val tw = TestWorld(twoFactor = false, imageProviders = listOf(p), clock = clock)
        try {
            testApplication {
                application { agernModule(tw.services) }
                val alice = login("alice", "pw-a")
                // Malformed input is not counted.
                repeat(3) { assertEquals(422, alice.save("rec", "rec-1", "two words").status.value) }
                assertEquals(400, alice.save("rec", "bad model!", "rec-key").status.value)
                repeat(4) { assertEquals(422, alice.save("rec", "rec-1", "bad-$it").status.value) }
                clock.advance(java.time.Duration.ofMinutes(5))
                // A success in between does not reset the counter.
                assertEquals(200, alice.save("rec", "rec-1", "rec-good").status.value)
                assertEquals(422, alice.save("rec", "rec-1", "bad-5").status.value)
                val calls = p.validations.size
                val blocked = alice.save("rec", "rec-1", "rec-good-2")
                assertEquals(429, blocked.status.value)
                val err = json(blocked.bodyAsText())["error"]!!.jsonObject
                assertEquals("too_many_attempts", err["code"]!!.jsonPrimitive.content)
                assertEquals("Too many rejected API keys. Try again later.", err["message"]!!.jsonPrimitive.content)
                assertEquals("2026-11-22T10:15:00.000Z", err["lockedUntil"]!!.jsonPrimitive.content)
                assertEquals(calls, p.validations.size, "no provider call while blocked")
                // Other users are not affected.
                assertEquals(200, login("bob", "pw-b").save("rec", "rec-1", "rec-bob").status.value)
                // When the oldest rejections leave the window, saving works again.
                clock.advance(java.time.Duration.ofMinutes(10))
                assertEquals(200, alice.save("rec", "rec-1", "rec-good-2").status.value)
            }
        } finally {
            tw.close()
        }
    }

    @Test
    fun `image limits are read from the environment and range-checked`() {
        val base = mapOf("FACTORY_MASTER_KEY" to java.util.Base64.getEncoder().encodeToString(ByteArray(32)), "FACTORY_GITHUB_ORG" to "o")
        assertEquals(ImageLimits(4, 8, 12, 2048), Config.fromEnv(base).imageLimits)
        val c = Config.fromEnv(base + mapOf(
            "FACTORY_IMAGE_MAX_PER_REQUEST" to "10", "FACTORY_IMAGE_MAX_PER_TURN" to "100", "FACTORY_IMAGE_MAX_PER_STAGE" to "1", "FACTORY_IMAGE_MAX_EDGE" to "4096",
        ))
        assertEquals(ImageLimits(10, 100, 1, 4096), c.imageLimits)
        for ((k, v) in listOf(
            "FACTORY_IMAGE_MAX_PER_REQUEST" to "0", "FACTORY_IMAGE_MAX_PER_REQUEST" to "11", "FACTORY_IMAGE_MAX_PER_TURN" to "101",
            "FACTORY_IMAGE_MAX_PER_STAGE" to "201", "FACTORY_IMAGE_MAX_PER_STAGE" to "0", "FACTORY_IMAGE_MAX_EDGE" to "255",
            "FACTORY_IMAGE_MAX_EDGE" to "4097", "FACTORY_IMAGE_MAX_EDGE" to "big",
        )) {
            val e = assertFailsWith<IllegalArgumentException>("$k=$v") { Config.fromEnv(base + (k to v)) }
            assertTrue(e.message!!.contains(k), e.message)
        }
    }
}
