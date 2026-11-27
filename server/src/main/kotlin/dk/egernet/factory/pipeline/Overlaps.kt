package dk.egernet.factory.pipeline

/**
 * Other active runs in the same project when a run starts (docs/ARCHITECTURE.md §6 "Other active runs"): whether the
 * issues reference each other and which files both specs name. Cheap text rules only; a warning, never a block.
 */
object Overlaps {
    /** At most this many other runs are listed, and this many shared files per run. */
    const val MAX_RUNS = 10
    const val MAX_FILES = 20

    /** Whether [text] names issue [number] as a whole token (`#12`, not `#123`, `a#12` or `org/repo#12`). */
    fun references(text: String?, number: Int): Boolean =
        text != null && number > 0 && Regex("""(?<![\w/])#$number\b""").containsMatchIn(text)

    private val BACKTICKED = Regex("`([^`\n]+)`")
    private val EXTENSION = Regex("""\.[A-Za-z0-9]{1,8}$""")
    private val LINE_SUFFIX = Regex(""":\d+$""")

    /**
     * The file paths a spec names: backticked tokens that contain `/`, have no whitespace, do not start with `http`, `-`
     * or `/api/`, and end in a file extension; a trailing `:<line>` is dropped.
     */
    fun paths(spec: String?): Set<String> {
        if (spec.isNullOrBlank()) return emptySet()
        return BACKTICKED.findAll(spec).mapNotNull { m ->
            val token = m.groupValues[1].trim().replace(LINE_SUFFIX, "")
            token.takeIf {
                '/' in it && it.none { c -> c.isWhitespace() } && !it.startsWith("http") && !it.startsWith("-") &&
                    !it.startsWith("/api/") && EXTENSION.containsMatchIn(it)
            }
        }.toSet()
    }

    /** The paths both specs name, sorted, at most [MAX_FILES]. */
    fun sharedFiles(a: String?, b: String?): List<String> = (paths(a) intersect paths(b)).sorted().take(MAX_FILES)
}

/**
 * A spec that reports its issue blocked on other open issues (docs/ARCHITECTURE.md §6 "Blocked on another issue"): its
 * first non-empty line is `Blocked on: #12` (several: `Blocked on: #12, #15`), optionally in bold.
 */
object BlockedMarker {
    private val LINE = Regex("""^\**Blocked on:\**\s*#\d+(\s*,\s*#\d+)*\s*$""", RegexOption.IGNORE_CASE)

    /** The issues [spec] says it is blocked on, and the first paragraph after the marker line (at most 500 characters). */
    data class Blocked(val issues: List<Int>, val reason: String)

    fun parse(spec: String?): Blocked? {
        val lines = spec?.lines() ?: return null
        val first = lines.indexOfFirst { it.isNotBlank() }
        if (first < 0) return null
        val line = lines[first].trim()
        if (!LINE.matches(line)) return null
        val issues = Regex("""#(\d+)""").findAll(line).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it > 0 }.distinct().toList()
        if (issues.isEmpty()) return null
        val rest = lines.drop(first + 1).joinToString("\n").trim()
        val reason = rest.split(Regex("\n\\s*\n")).firstOrNull().orEmpty().trim().replace(Regex("\\s+"), " ").take(500)
        return Blocked(issues, reason)
    }
}

/** The pull requests that came in with an update round, from the incoming first-parent commit subjects. */
object IncomingPulls {
    const val MAX = 10
    private val SQUASH = Regex("""\(#(\d+)\)\s*$""")
    private val MERGE = Regex("""^Merge pull request #(\d+) """)

    /** Distinct pull request numbers named by [subjects] (`… (#12)` or `Merge pull request #12 from …`), at most [MAX], each with its first subject. */
    fun numbers(subjects: List<String>): List<Pair<Int, String>> {
        val seen = LinkedHashMap<Int, String>()
        for (s in subjects) {
            val n = (SQUASH.find(s) ?: MERGE.find(s))?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (n > 0 && n !in seen) seen[n] = s
            if (seen.size >= MAX) break
        }
        return seen.map { it.key to it.value }
    }
}
