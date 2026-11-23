package dk.egernet.factory.images

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration
import java.util.Base64
import kotlin.math.abs
import kotlin.math.ln

/** Google Gemini image models through the Interactions API, with the user's API key. No transparent backgrounds. */
class GeminiImageProvider(
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    timeout: Duration = Duration.ofSeconds(300),
) : ImageProvider {
    override val id = "gemini"
    override val label = "Google Gemini"
    override val models = listOf("gemini-3.1-flash-image", "gemini-3-pro-image", "gemini-2.5-flash-image")
    override val defaultModel = "gemini-3.1-flash-image"
    override val supportsTransparency = false
    override val keyPlaceholder = "AIza…"

    private val http = ProviderHttp(timeout)
    private val base = baseUrl.trimEnd('/')

    override fun validate(key: String, model: String) {
        val r = http.send(HttpRequest.newBuilder(URI.create("$base/v1beta/models/$model")).header("x-goog-api-key", key).GET())
        when {
            r.status in 200..299 -> return
            r.status in setOf(400, 401, 403) -> throw ImageProviderException(ImageProviderException.INVALID_CREDENTIAL)
            r.status == 404 -> throw ImageProviderException(ImageProviderException.UNKNOWN_MODEL)
            else -> throw error(r)
        }
    }

    override fun generate(key: String, model: String, request: ImageRequest): List<ByteArray> {
        val longest = maxOf(request.width, request.height)
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("input") { addJsonObject { put("type", "text"); put("text", request.prompt) } }
            putJsonObject("response_format") {
                put("type", "image")
                put("mime_type", if (request.format == "jpeg") "image/jpeg" else "image/png")
                put("aspect_ratio", aspectRatio(request.width, request.height))
                put("image_size", if (longest <= 1024) "1K" else if (longest <= 2048) "2K" else "4K")
            }
            put("store", false)
        }.toString()
        return (0 until request.count).map { one(key, body) }
    }

    private fun one(key: String, body: String): ByteArray {
        val r = http.send(
            HttpRequest.newBuilder(URI.create("$base/v1beta/interactions"))
                .header("x-goog-api-key", key).header("Api-Revision", "2026-05-20").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)),
        )
        if (r.status !in 200..299) throw error(r)
        val o = r.json ?: throw ImageProviderException(ImageProviderException.NO_IMAGE)
        val blocks = (o["steps"] as? JsonArray).orEmpty().mapNotNull { it.obj() }.filter { it["type"].str() == "model_output" }
            .flatMap { (it["content"] as? JsonArray).orEmpty().mapNotNull { b -> b.obj() } }
        val image = blocks.lastOrNull { it["type"].str() == "image" }?.get("data").str()
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        if (o["status"].str() == "failed" || image == null) {
            val said = (o["errors"]?.toString() ?: "") + " " + blocks.filter { it["type"].str() == "text" }.joinToString(" ") { it["text"].str() ?: "" }
            throw ImageProviderException(if (REFUSED.containsMatchIn(said)) ImageProviderException.CONTENT_REFUSED else ImageProviderException.NO_IMAGE)
        }
        return image
    }

    private fun error(r: ProviderHttp.Reply): ImageProviderException {
        val message = r.json?.get("error").obj()?.get("message").str() ?: ""
        return when {
            (r.status == 400 && "API key not valid" in message) || r.status == 401 || r.status == 403 ->
                ImageProviderException(ImageProviderException.INVALID_CREDENTIAL)
            r.status == 429 -> ImageProviderException(ImageProviderException.QUOTA)
            r.status in 400..499 -> ImageProviderException(ImageProviderException.INVALID_REQUEST, message.ifEmpty { "HTTP ${r.status}" })
            else -> ImageProviderException(ImageProviderException.UNAVAILABLE)
        }
    }

    companion object {
        private val REFUSED = Regex("(?i)safety|blocked|prohibited|policy")
        private val RATIOS = listOf("1:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4", "9:16", "16:9", "21:9")

        /** The supported aspect ratio nearest to width:height (compared on a log scale). */
        fun aspectRatio(width: Int, height: Int): String {
            val want = ln(width.toDouble() / height)
            return RATIOS.minBy { r -> val (a, b) = r.split(':').map { it.toDouble() }; abs(ln(a / b) - want) }
        }
    }
}
