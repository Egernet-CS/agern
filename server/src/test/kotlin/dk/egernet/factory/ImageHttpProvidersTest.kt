package dk.egernet.factory

import com.sun.net.httpserver.HttpServer
import dk.egernet.factory.images.GeminiImageProvider
import dk.egernet.factory.images.ImageProviderException
import dk.egernet.factory.images.ImageRequest
import dk.egernet.factory.images.OpenAiImageProvider
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The OpenAI and Gemini providers against a local stub of their HTTP APIs. */
class ImageHttpProvidersTest {
    private data class Seen(val method: String, val path: String, val headers: Map<String, String>, val body: String)
    private data class Reply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap(), val delayMs: Long = 0)

    private val seen = CopyOnWriteArrayList<Seen>()
    @Volatile private var reply: (Seen) -> Reply = { Reply(200, "{}") }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val s = Seen(
                ex.requestMethod, ex.requestURI.path,
                ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") },
                ex.requestBody.readBytes().decodeToString(),
            )
            seen += s
            val r = reply(s)
            if (r.delayMs > 0) Thread.sleep(r.delayMs)
            r.headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            val bytes = r.body.toByteArray()
            ex.sendResponseHeaders(r.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    @AfterTest fun stop() = server.stop(0)

    private val key = "sk-test-secret-123"
    private val img = png(32, 32)
    private val b64 = Base64.getEncoder().encodeToString(img)
    private fun req(transparent: Boolean = false, w: Int = 1000, h: Int = 1000, count: Int = 1, format: String = "png") =
        ImageRequest("A squirrel", w, h, format, count, transparent)

    private fun kind(block: () -> Unit): String {
        val e = assertFailsWith<ImageProviderException> { block() }
        assertFalse(key in (e.message ?: ""), "the key never appears in an exception")
        return e.kind
    }

    @Test
    fun `OpenAI validates, generates and maps errors`() {
        val p = OpenAiImageProvider(base)
        reply = { Reply(200, """{"id":"gpt-image-2"}""") }
        p.validate(key, "gpt-image-2")
        assertEquals("GET" to "/v1/models/gpt-image-2", seen.last().method to seen.last().path)
        assertEquals("Bearer $key", seen.last().headers["authorization"])
        reply = { Reply(401, """{"error":{"message":"Incorrect API key provided: $key"}}""") }
        assertEquals(ImageProviderException.INVALID_CREDENTIAL, kind { p.validate(key, "gpt-image-2") })
        reply = { Reply(404, """{"error":{"message":"nope"}}""") }
        assertEquals(ImageProviderException.UNKNOWN_MODEL, kind { p.validate(key, "gpt-image-x") })

        reply = { Reply(200, """{"created":1,"data":[{"b64_json":"$b64"},{"b64_json":"$b64"}]}""") }
        val out = p.generate(key, "gpt-image-2", req(transparent = true, w = 1000, h = 1530, count = 2, format = "webp"))
        assertEquals(2, out.size)
        assertTrue(out[0].contentEquals(img))
        val s = seen.last()
        assertEquals("POST" to "/v1/images/generations", s.method to s.path)
        val body = AppJson.parseToJsonElement(s.body).jsonObject
        assertEquals("gpt-image-2", body["model"]!!.jsonPrimitive.content)
        assertEquals("1008x1536", body["size"]!!.jsonPrimitive.content, "edges rounded to multiples of 16")
        assertEquals("2", body["n"]!!.jsonPrimitive.content)
        assertEquals("webp", body["output_format"]!!.jsonPrimitive.content)
        assertEquals("transparent", body["background"]!!.jsonPrimitive.content)
        assertFalse(key in s.body, "the key travels only in a header")
        p.generate(key, "gpt-image-2", req())
        assertFalse("background" in AppJson.parseToJsonElement(seen.last().body).jsonObject)

        fun err(status: Int, type: String = "", code: String = "", msg: String = "m") =
            Reply(status, """{"error":{"message":"$msg","type":"$type","code":"$code"}}""")
        val cases = listOf(
            err(401) to ImageProviderException.INVALID_CREDENTIAL,
            err(429, code = "insufficient_quota") to ImageProviderException.QUOTA,
            err(429, type = "organization_spend_limit_exceeded") to ImageProviderException.QUOTA,
            err(429, code = "rate_limit_exceeded") to ImageProviderException.RATE_LIMITED,
            err(400, code = "moderation_blocked") to ImageProviderException.CONTENT_REFUSED,
            err(400, msg = "Your request was rejected by the safety system") to ImageProviderException.CONTENT_REFUSED,
            err(400, msg = "Invalid size") to ImageProviderException.INVALID_REQUEST,
            err(503) to ImageProviderException.UNAVAILABLE,
        )
        for ((r, k) in cases) {
            reply = { r }
            assertEquals(k, kind { p.generate(key, "gpt-image-2", req()) }, r.toString())
        }
        reply = { Reply(200, """{"data":[]}""") }
        assertTrue(p.generate(key, "gpt-image-2", req()).isEmpty(), "no images: the service answers no_image")
        reply = { err(400, msg = "Invalid size") }
        assertEquals("Invalid size", assertFailsWith<ImageProviderException> { p.generate(key, "gpt-image-2", req()) }.detail)
    }

    @Test
    fun `redirects are not followed, timeouts and unreachable hosts are reported`() {
        reply = { Reply(302, "", mapOf("Location" to "$base/elsewhere")) }
        val p = OpenAiImageProvider(base)
        assertEquals(ImageProviderException.UNAVAILABLE, kind { p.generate(key, "gpt-image-2", req()) })
        assertTrue(seen.none { it.path == "/elsewhere" }, "the key is never sent to another location")

        reply = { Reply(200, "{}", delayMs = 1500) }
        val slow = OpenAiImageProvider(base, Duration.ofMillis(300))
        val e = assertFailsWith<ImageProviderException> { slow.generate(key, "gpt-image-2", req()) }
        assertEquals(ImageProviderException.TIMEOUT, e.kind)

        val closed = OpenAiImageProvider("http://127.0.0.1:1")
        assertEquals(ImageProviderException.UNAVAILABLE, kind { closed.validate(key, "gpt-image-2") })
    }

    @Test
    fun `Gemini validates, generates one request per image and maps errors`() {
        val p = GeminiImageProvider(base)
        reply = { Reply(200, """{"name":"models/gemini-3.1-flash-image"}""") }
        p.validate(key, "gemini-3.1-flash-image")
        assertEquals("/v1beta/models/gemini-3.1-flash-image", seen.last().path)
        assertEquals(key, seen.last().headers["x-goog-api-key"])
        for (status in listOf(400, 401, 403)) {
            reply = { Reply(status, """{"error":{"message":"API key not valid"}}""") }
            assertEquals(ImageProviderException.INVALID_CREDENTIAL, kind { p.validate(key, "gemini-3.1-flash-image") })
        }
        reply = { Reply(404, "{}") }
        assertEquals(ImageProviderException.UNKNOWN_MODEL, kind { p.validate(key, "nope") })

        val ok = """{"status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"Here"},
            {"type":"image","data":"AAAA"},{"type":"image","data":"$b64"}]}]}"""
        reply = { Reply(200, ok) }
        seen.clear()
        val out = p.generate(key, "gemini-3.1-flash-image", req(w = 1920, h = 1080, count = 2, format = "jpeg"))
        assertEquals(2, out.size)
        assertTrue(out[0].contentEquals(img), "the last image block")
        assertEquals(2, seen.size)
        val s = seen.last()
        assertEquals("POST" to "/v1beta/interactions", s.method to s.path)
        assertEquals("2026-05-20", s.headers["api-revision"])
        assertEquals(key, s.headers["x-goog-api-key"])
        assertFalse(key in s.body)
        val body = AppJson.parseToJsonElement(s.body).jsonObject
        val rf = body["response_format"]!!.jsonObject
        assertEquals("image/jpeg", rf["mime_type"]!!.jsonPrimitive.content)
        assertEquals("16:9", rf["aspect_ratio"]!!.jsonPrimitive.content)
        assertEquals("2K", rf["image_size"]!!.jsonPrimitive.content)
        assertEquals("false", body["store"]!!.jsonPrimitive.content)
        assertEquals("A squirrel", body["input"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("1:1", GeminiImageProvider.aspectRatio(512, 512))
        assertEquals("21:9", GeminiImageProvider.aspectRatio(2100, 900))

        val cases = listOf(
            Reply(400, """{"error":{"message":"API key not valid. Please pass a valid API key."}}""") to ImageProviderException.INVALID_CREDENTIAL,
            Reply(403, "{}") to ImageProviderException.INVALID_CREDENTIAL,
            Reply(429, "{}") to ImageProviderException.QUOTA,
            Reply(400, """{"error":{"message":"Bad aspect"}}""") to ImageProviderException.INVALID_REQUEST,
            Reply(500, "{}") to ImageProviderException.UNAVAILABLE,
            Reply(200, """{"status":"failed","errors":[{"message":"Blocked for safety"}]}""") to ImageProviderException.CONTENT_REFUSED,
            Reply(200, """{"status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"I cannot"}]}]}""") to
                ImageProviderException.NO_IMAGE,
        )
        for ((r, k) in cases) {
            reply = { r }
            assertEquals(k, kind { p.generate(key, "gemini-3.1-flash-image", req()) }, r.toString())
        }
    }
}
