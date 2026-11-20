package dk.egernet.factory.agent

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/** One limit window as a `rate_limit_event` reported it; null fields were missing or unusable. */
data class ObservedWindow(val type: String, val utilization: Double?, val status: String?, val resetsAt: Instant?)

/**
 * Reads the limit windows out of a `rate_limit_event`'s `rate_limit_info` (docs/ARCHITECTURE.md §5 "Claude usage").
 * The shape comes from the CLI's own schema, not from a captured event, so everything is optional: what does not fit
 * is skipped, and nothing here throws.
 *
 * - The top-level `rateLimitType` / `utilization` / `status` / `resetsAt` describe the currently limiting window.
 * - Each object under `unifiedWindows` is a window of that key's type.
 * - The same type in both places: `unifiedWindows` wins field by field, the top level fills the gaps, and the status is
 *   `rejected` if either says so.
 */
object RateLimitWindows {
    const val MAX_WINDOWS = 20
    private val TYPE_RE = Regex("^[A-Za-z0-9_.-]{1,64}$")
    private const val MAX_STATUS = 40

    fun parse(info: JsonObject): List<ObservedWindow> = try {
        val byType = LinkedHashMap<String, ObservedWindow>()
        (info["unifiedWindows"] as? JsonObject)?.forEach { (type, v) ->
            if (v is JsonObject) window(type, v)?.let { byType[type] = it }
        }
        val top = (info["rateLimitType"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { window(it, info) }
        if (top != null) {
            val known = byType[top.type]
            byType[top.type] = if (known == null) top else ObservedWindow(
                top.type,
                known.utilization ?: top.utilization,
                if (known.status == "rejected" || top.status == "rejected") "rejected" else known.status ?: top.status,
                known.resetsAt ?: top.resetsAt,
            )
        }
        byType.values.take(MAX_WINDOWS)
    } catch (e: Exception) {
        emptyList()
    }

    private fun window(type: String, o: JsonObject): ObservedWindow? {
        if (!TYPE_RE.matches(type)) return null
        val w = ObservedWindow(type, utilization(o["utilization"]), status(o["status"]), UsageLimitDetector.parseResetsAt(o["resetsAt"]))
        return if (w.utilization == null && w.status == null && w.resetsAt == null) null else w
    }

    /** A finite JSON number ≥ 0 (a fraction; above 1 is kept). */
    private fun utilization(e: JsonElement?): Double? {
        val p = e as? JsonPrimitive ?: return null
        if (p is JsonNull || p.isString) return null
        return p.content.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
    }

    private fun status(e: JsonElement?): String? {
        val p = e as? JsonPrimitive ?: return null
        if (p is JsonNull || !p.isString) return null
        return p.content.takeIf { it.length <= MAX_STATUS }
    }
}
