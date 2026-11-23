package dk.egernet.factory.images

/** What an agent asked for, already validated (docs/ARCHITECTURE.md §6 "Generated images"). */
data class ImageRequest(
    val prompt: String,
    val width: Int,
    val height: Int,
    /** `png`, `jpeg` or `webp`. */
    val format: String,
    val count: Int,
    val transparent: Boolean,
)

/**
 * An image-generation service. Implementations are the only code that knows a provider's name, endpoints and error
 * shapes; everything else works from [ImageService.catalogue]. Adding a provider means adding one implementation to
 * the list `Services` passes to [ImageService].
 *
 * The key is passed per call and must never appear in an exception message, a log line or a URL.
 */
interface ImageProvider {
    val id: String
    val label: String
    /** Suggestions for the Settings form; any model name matching the model pattern may be saved. */
    val models: List<String>
    val defaultModel: String
    val supportsTransparency: Boolean
    /** Shown in the empty key field, e.g. `sk-…`. */
    val keyPlaceholder: String

    /** Checks that [key] is accepted and knows [model]. Blocking; throws [ImageProviderException]. */
    fun validate(key: String, model: String)

    /** Generates the images (encoded bytes, in any order). Blocking; throws [ImageProviderException]. */
    fun generate(key: String, model: String, request: ImageRequest): List<ByteArray>

    /**
     * How a user connects: [API_KEY] (the Settings form validates and stores a key) or [DEVICE_LOGIN] (the user signs in
     * through the provider's own device-code flow; `PUT /api/me/image-provider` refuses it).
     */
    val auth: String get() = API_KEY

    /** Null when the provider can be used on this server, else the reason shown in Settings. May block briefly. */
    fun availability(): String? = null

    /**
     * [generate] for providers whose credential changes while used (e.g. refreshed login tokens): [renew] is called with
     * the replacement secret whenever it changed, before this returns or throws. [ImageService] always calls this one.
     */
    fun generate(key: String, model: String, request: ImageRequest, renew: (String) -> Unit): List<ByteArray> = generate(key, model, request)

    companion object {
        const val API_KEY = "api_key"
        const val DEVICE_LOGIN = "device_login"
    }
}

/**
 * A provider failure. [kind] is one of [KINDS]; [detail] is the provider's own one-line message (only used for
 * `invalid_request`), which the service redacts before using.
 */
class ImageProviderException(val kind: String, val detail: String? = null) : Exception(kind + (detail?.let { ": $it" } ?: "")) {
    companion object {
        const val INVALID_CREDENTIAL = "invalid_credential"
        const val QUOTA = "quota"
        const val RATE_LIMITED = "rate_limited"
        const val CONTENT_REFUSED = "content_refused"
        const val INVALID_REQUEST = "invalid_request"
        const val UNAVAILABLE = "unavailable"
        const val TIMEOUT = "timeout"
        const val NO_IMAGE = "no_image"
        /** Only from [ImageProvider.validate]. */
        const val UNKNOWN_MODEL = "unknown_model"
        val KINDS = setOf(INVALID_CREDENTIAL, QUOTA, RATE_LIMITED, CONTENT_REFUSED, INVALID_REQUEST, UNAVAILABLE, TIMEOUT, NO_IMAGE, UNKNOWN_MODEL)
    }
}
