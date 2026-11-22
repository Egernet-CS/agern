package dk.egernet.factory.images

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration
import java.util.Base64

/** OpenAI image models through the Images API, with the user's API key. */
class OpenAiImageProvider(
    private val baseUrl: String = "https://api.openai.com",
    timeout: Duration = Duration.ofSeconds(300),
) : ImageProvider {
    override val id = "openai"
    override val label = "OpenAI"
    override val models = listOf("gpt-image-2.5-flare", "gpt-image-2.5-sunburst", "gpt-image-2", "gpt-image-1.5", "gpt-image-1-mini")
    override val defaultModel = "gpt-image-2.5-flare"
    override val supportsTransparency = true
    override val keyPlaceholder = "sk-…"

    private val http = ProviderHttp(timeout)
    private val base = baseUrl.trimEnd('/')

    override fun validate(key: String, model: String) {
        val r = http.send(HttpRequest.newBuilder(URI.create("$base/v1/models/$model")).header("Authorization", "Bearer $key").GET())
        when {
            r.status in 200..299 -> return
            r.status == 401 -> throw ImageProviderException(ImageProviderException.INVALID_CREDENTIAL)
            r.status == 404 -> throw ImageProviderException(ImageProviderException.UNKNOWN_MODEL)
            else -> throw error(r)
        }
    }

    override fun generate(key: String, model: String, request: ImageRequest): List<ByteArray> {
        fun edge(n: Int) = maxOf(16, (n + 8) / 16 * 16)
        val body = buildJsonObject {
            put("model", model); put("prompt", request.prompt); put("n", request.count)
            put("size", "${edge(request.width)}x${edge(request.height)}"); put("output_format", request.format)
            if (request.transparent) put("background", "transparent")
        }
        val r = http.send(
            HttpRequest.newBuilder(URI.create("$base/v1/images/generations"))
                .header("Authorization", "Bearer $key").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())),
        )
        if (r.status !in 200..299) throw error(r)
        val data = r.json?.get("data") as? JsonArray ?: throw ImageProviderException(ImageProviderException.NO_IMAGE)
        return data.mapNotNull { el -> el.obj()?.get("b64_json").str()?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } }
    }

    private fun error(r: ProviderHttp.Reply): ImageProviderException {
        val e = r.json?.get("error").obj()
        val type = e?.get("type").str() ?: ""
        val code = e?.get("code").str() ?: ""
        val message = e?.get("message").str() ?: ""
        return when {
            r.status == 401 -> ImageProviderException(ImageProviderException.INVALID_CREDENTIAL)
            r.status == 429 && (type in QUOTA_CODES || code in QUOTA_CODES) -> ImageProviderException(ImageProviderException.QUOTA)
            r.status == 429 -> ImageProviderException(ImageProviderException.RATE_LIMITED)
            r.status == 400 && (CONTENT.containsMatchIn(code) || CONTENT.containsMatchIn(message)) ->
                ImageProviderException(ImageProviderException.CONTENT_REFUSED)
            r.status in 400..499 -> ImageProviderException(ImageProviderException.INVALID_REQUEST, message.ifEmpty { "HTTP ${r.status}" })
            else -> ImageProviderException(ImageProviderException.UNAVAILABLE)
        }
    }

    private companion object {
        val QUOTA_CODES = setOf(
            "insufficient_quota", "credit_balance_exhausted", "organization_spend_limit_exceeded", "project_spend_limit_exceeded",
            "organization_usage_limit_exceeded",
        )
        val CONTENT = Regex("(?i)moderation|content.?policy|safety")
    }
}
