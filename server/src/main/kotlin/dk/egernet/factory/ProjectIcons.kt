package dk.egernet.factory

import dk.egernet.factory.agent.Attachments
import dk.egernet.factory.agent.INLINE_IMAGE_TYPES
import dk.egernet.factory.agent.Sniff
import io.ktor.http.HttpStatusCode
import java.net.URLEncoder
import java.security.MessageDigest

/** An uploaded project icon that passed [ProjectIcons.validate]; [bytes] are stored unchanged. */
class ValidIcon(val mediaType: String, val bytes: ByteArray, val sha256: String, val width: Int, val height: Int)

/** Limits, validation and URLs of project icons (docs/ARCHITECTURE.md §8 "Project icons"). */
object ProjectIcons {
    /** Largest accepted upload: 1 MiB. */
    const val MAX_BYTES: Long = 1_048_576

    /** Largest accepted width and height in pixels, read from the header without decoding. */
    const val MAX_EDGE = 2048

    fun tooLarge() = ApiException(HttpStatusCode.PayloadTooLarge, "icon_too_large", "The icon is larger than ${Attachments.humanSize(MAX_BYTES)}")

    /** Checks [bytes] in the documented order; the type comes from the magic bytes only. */
    fun validate(bytes: ByteArray): ValidIcon {
        if (bytes.size > MAX_BYTES) throw tooLarge()
        if (bytes.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, "empty_icon", "The file is empty")
        val type = Sniff.mediaType(bytes)
        if (type !in INLINE_IMAGE_TYPES) {
            throw ApiException(HttpStatusCode.UnsupportedMediaType, "unsupported_icon_type", "The icon must be a PNG, JPEG, WebP or GIF image")
        }
        val (w, h) = Sniff.dimensions(type, bytes)?.takeIf { (w, h) -> w > 0 && h > 0 }
            ?: throw unprocessable("The image could not be read", "invalid_icon")
        if (w > MAX_EDGE || h > MAX_EDGE) {
            throw unprocessable("The icon may be at most ${MAX_EDGE}×$MAX_EDGE pixels", "icon_dimensions_too_large")
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return ValidIcon(type, bytes, sha, w, h)
    }

    /** The cache-busting part of an icon URL. */
    fun version(sha256: String) = sha256.take(16)

    /** The URL the web loads the icon of [repo] from. */
    fun url(repo: String, sha256: String) =
        "/api/projects/${URLEncoder.encode(repo, Charsets.UTF_8).replace("+", "%20")}/icon?v=${version(sha256)}"
}
