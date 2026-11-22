package dk.egernet.factory.images

import dk.egernet.factory.AppJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * Shared HTTP plumbing of the image providers: never follows redirects (a key is never sent to another host), a 10 s
 * connect timeout and a per-request timeout. Exceptions never carry the key or the request headers.
 */
internal class ProviderHttp(private val timeout: Duration) {
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    data class Reply(val status: Int, val body: String) {
        val json: JsonObject? get() = runCatching { AppJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
    }

    fun send(request: HttpRequest.Builder): Reply {
        val req = request.timeout(timeout).build()
        return try {
            val res = client.send(req, HttpResponse.BodyHandlers.ofString())
            Reply(res.statusCode(), res.body() ?: "")
        } catch (e: HttpTimeoutException) {
            throw ImageProviderException(ImageProviderException.TIMEOUT, timeout.seconds.toString())
        } catch (e: java.io.IOException) {
            throw ImageProviderException(ImageProviderException.UNAVAILABLE)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ImageProviderException(ImageProviderException.UNAVAILABLE)
        }
    }
}

internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
