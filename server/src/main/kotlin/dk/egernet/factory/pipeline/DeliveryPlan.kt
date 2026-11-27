package dk.egernet.factory.pipeline

/**
 * A spec that delivers only part of its issue (docs/ARCHITECTURE.md §6 "Partial delivery"): a `## Delivery plan` section
 * (outside fenced code blocks) with a `**This run:**` paragraph and a `**Remaining:**` list:
 *
 * ```
 * ## Delivery plan
 * **This run:** the first step.
 * **Remaining:**
 * - the second step
 * - the third step
 * ```
 *
 * The run is a partial delivery only when the list has at least one item. The text comes from the agent: it is capped
 * here and shown as plain text.
 */
object DeliveryPlan {
    const val MAX_DELIVERS = 1000
    const val MAX_ITEM = 300
    const val MAX_ITEMS = 20

    private val HEADING = Regex("""^##\s+Delivery plan\s*$""", RegexOption.IGNORE_CASE)
    private val SECTION_END = Regex("""^#{1,2}\s""")
    private val FENCE = Regex("""^\s{0,3}(```|~~~)""")
    private val THIS_RUN = Regex("""^\**This run:\**""", RegexOption.IGNORE_CASE)
    private val REMAINING = Regex("""^\**Remaining:\**\s*$""", RegexOption.IGNORE_CASE)
    private val ITEM = Regex("""^\s{0,3}(?:[-*+]|\d+[.)])\s+(.*)$""")
    private val SPACE = Regex("""\s+""")

    /** What the run delivers (may be empty) and the steps that remain, in order. */
    data class Delivery(val delivers: String, val remaining: List<String>) {
        /** Whether the plan makes the run a partial delivery: something remains. */
        val partial: Boolean get() = remaining.isNotEmpty()
    }

    /** The Delivery plan of [spec], or null when it has none. */
    fun parse(spec: String?): Delivery? {
        val lines = spec?.lines() ?: return null
        var inFence = false
        var start = -1
        for ((i, line) in lines.withIndex()) {
            if (FENCE.containsMatchIn(line)) { inFence = !inFence; continue }
            if (!inFence && HEADING.matches(line.trim())) { start = i + 1; break }
        }
        if (start < 0) return null
        val section = mutableListOf<String>()
        inFence = false
        for (line in lines.drop(start)) {
            if (FENCE.containsMatchIn(line)) inFence = !inFence
            if (!inFence && SECTION_END.containsMatchIn(line)) break
            section += line
        }
        return Delivery(delivers(section), remaining(section))
    }

    private fun collapse(s: String) = s.replace(SPACE, " ").trim()

    private fun delivers(section: List<String>): String {
        val at = section.indexOfFirst { THIS_RUN.containsMatchIn(it.trim()) }
        if (at < 0) return ""
        val parts = mutableListOf(THIS_RUN.replace(section[at].trim(), "").trimStart('*', ' '))
        for (line in section.drop(at + 1)) {
            if (line.isBlank() || REMAINING.matches(line.trim())) break
            parts += line
        }
        return collapse(parts.joinToString(" ")).take(MAX_DELIVERS)
    }

    private fun remaining(section: List<String>): List<String> {
        val at = section.indexOfFirst { REMAINING.matches(it.trim()) }
        if (at < 0) return emptyList()
        val items = mutableListOf<StringBuilder>()
        for (line in section.drop(at + 1)) {
            if (line.isBlank()) continue
            val item = ITEM.matchEntire(line)
            when {
                item != null -> items += StringBuilder(item.groupValues[1])
                line.first().isWhitespace() && items.isNotEmpty() -> items.last().append(' ').append(line.trim())
                else -> break
            }
        }
        return items.map { collapse(it.toString()).take(MAX_ITEM) }.filter { it.isNotEmpty() }.take(MAX_ITEMS)
    }
}
