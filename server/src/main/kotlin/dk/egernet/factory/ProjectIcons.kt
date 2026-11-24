package dk.egernet.factory

import dk.egernet.factory.agent.Attachments
import dk.egernet.factory.agent.INLINE_IMAGE_TYPES
import dk.egernet.factory.agent.Screenshots
import dk.egernet.factory.agent.Sniff
import io.ktor.http.HttpStatusCode
import java.net.URLEncoder
import java.security.MessageDigest

/** An uploaded project icon that passed [ProjectIcons.validate]; [bytes] are stored unchanged. */
/** [width] and [height] are 0 for an SVG (vector, no pixel size). */
class ValidIcon(val mediaType: String, val bytes: ByteArray, val sha256: String, val width: Int, val height: Int)

/** Limits, validation and URLs of project icons (docs/ARCHITECTURE.md §8 "Project icons"). */
object ProjectIcons {
    /** Largest accepted upload: 1 MiB. */
    const val MAX_BYTES: Long = 1_048_576

    /** Largest accepted width and height in pixels, read from the header without decoding. */
    const val MAX_EDGE = 2048

    /** The types an icon can be stored and served as: the raster types and SVG. */
    val STORED_TYPES = INLINE_IMAGE_TYPES + SvgIcons.MEDIA_TYPE

    /**
     * The CSP of a served SVG icon: like the raster one, but without `'self'`, so an SVG opened directly in a tab can
     * load nothing from any origin; embedded raster `data:` images still render. `sandbox` and `default-src 'none'`
     * block script.
     */
    const val SVG_CSP = "default-src 'none'; img-src data:; style-src 'unsafe-inline'; sandbox"

    /** Longest accepted path of an icon file in the repository. */
    const val MAX_PATH = 1024

    /** The file name extension for a stored icon type. */
    fun extension(mediaType: String) = if (mediaType == SvgIcons.MEDIA_TYPE) "svg" else Screenshots.extension(mediaType)

    fun tooLarge() = ApiException(HttpStatusCode.PayloadTooLarge, "icon_too_large", "The icon is larger than ${Attachments.humanSize(MAX_BYTES)}")

    /**
     * Checks [bytes] in the documented order. The type comes from the content only: magic bytes for the raster types,
     * otherwise [SvgIcons.inspect]; never the file name or the client's `Content-Type`.
     */
    fun validate(bytes: ByteArray): ValidIcon {
        if (bytes.size > MAX_BYTES) throw tooLarge()
        if (bytes.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, "empty_icon", "The file is empty")
        val type = Sniff.mediaType(bytes)
        if (type !in INLINE_IMAGE_TYPES) {
            when (val svg = SvgIcons.inspect(bytes)) {
                SvgResult.NotSvg -> throw ApiException(
                    HttpStatusCode.UnsupportedMediaType, "unsupported_icon_type", "The icon must be a PNG, JPEG, WebP, GIF or SVG image",
                )
                is SvgResult.Refused -> throw unprocessable("The SVG cannot be used: ${svg.reason}", "invalid_icon")
                SvgResult.Valid -> return ValidIcon(SvgIcons.MEDIA_TYPE, bytes, sha256(bytes), 0, 0)
            }
        }
        val (w, h) = Sniff.dimensions(type, bytes)?.takeIf { (w, h) -> w > 0 && h > 0 }
            ?: throw unprocessable("The image could not be read", "invalid_icon")
        if (w > MAX_EDGE || h > MAX_EDGE) {
            throw unprocessable("The icon may be at most ${MAX_EDGE}×$MAX_EDGE pixels", "icon_dimensions_too_large")
        }
        return ValidIcon(type, bytes, sha256(bytes), w, h)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * The path of an icon file in the repository, as typed by an admin: trimmed, one leading `/` removed. Refused
     * (400) when empty, too long, or with a backslash, a control character or an empty, `.` or `..` segment.
     */
    fun repositoryPath(raw: String): String {
        val path = raw.trim().removePrefix("/")
        val bad = path.isEmpty() || path.length > MAX_PATH || path.any { it == '\\' || it.isISOControl() } ||
            path.split('/').any { it.isEmpty() || it == "." || it == ".." }
        if (bad) throw badRequest("path must be a file path inside the repository")
        return path
    }

    /** The cache-busting part of an icon URL. */
    fun version(sha256: String) = sha256.take(16)

    /** The URL the web loads the icon of [repo] from. */
    fun url(repo: String, sha256: String) =
        "/api/projects/${URLEncoder.encode(repo, Charsets.UTF_8).replace("+", "%20")}/icon?v=${version(sha256)}"
}
