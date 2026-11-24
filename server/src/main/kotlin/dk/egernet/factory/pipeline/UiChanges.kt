package dk.egernet.factory.pipeline

/**
 * Tells whether a run's diff touches a user interface, for the warning "UI change reviewed without screenshots"
 * (docs/ARCHITECTURE.md §6 "Screenshots"). A simple path heuristic, deliberately: it only decides whether the server
 * warns, never whether a run passes.
 *
 * Default rules ([isUiFileByDefault]): web components, styles, markup and server-side templates, except tests.
 * A project can replace them with [UI_FILES] on its default branch: one glob per line (`#` starts a comment line),
 * `!glob` excludes. A glob without `/` matches the file name in any directory; `*` and `?` stay within one path
 * segment, `**` spans segments, a trailing `/` means everything under that directory. A file with only exclusions
 * keeps the defaults minus those; a file without any rule (empty, or comments only) turns the warning off.
 */
object UiChanges {
    /** The per-project override, read from the run's base ref (so a run cannot switch off its own warning). */
    const val UI_FILES = ".agern/ui-files"
    const val MAX_UI_FILES_BYTES = 16 * 1024

    /** Components, styles and markup. */
    val UI_EXTENSIONS = setOf("tsx", "jsx", "vue", "svelte", "astro", "css", "scss", "sass", "less", "styl", "html", "htm")

    /** Server-side and static-site templates. */
    val TEMPLATE_EXTENSIONS = setOf(
        "hbs", "handlebars", "mustache", "ejs", "erb", "njk", "twig", "liquid", "j2", "jinja", "jinja2", "pug", "jte",
        "ftl", "cshtml", "razor", "heex", "eex",
    )

    /** Plain `.ts`/`.js` files count only inside these directories (typical homes of UI code). */
    val UI_SCRIPT_DIRS = setOf("components", "pages", "views", "screens", "layouts", "templates")
    private val SCRIPT_EXTENSIONS = setOf("ts", "js", "mjs")

    /** Directories and name parts that mark tests, which never count as a UI change. */
    private val TEST_DIRS = setOf("test", "tests", "__tests__", "e2e", "spec", "specs", "cypress", "playwright")
    private val TEST_NAME = Regex("""\.(test|spec|stories)\.[^.]+$""")

    fun isUiFileByDefault(path: String): Boolean {
        val segments = path.split('/')
        val name = segments.last()
        val dirs = segments.dropLast(1).map { it.lowercase() }
        if (dirs.any { it in TEST_DIRS } || TEST_NAME.containsMatchIn(name)) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in UI_EXTENSIONS || ext in TEMPLATE_EXTENSIONS) return true
        if (ext in SCRIPT_EXTENSIONS && dirs.any { it in UI_SCRIPT_DIRS }) return true
        return dirs.any { it == "templates" } && ext !in setOf("kt", "java", "py", "go", "rs", "rb", "php", "cs", "md")
    }

    /** Decides per path: the defaults, or the rules of a project's [UI_FILES]. */
    class Rules private constructor(private val include: List<Regex>?, private val exclude: List<Regex>) {
        fun isUi(path: String): Boolean {
            val p = path.trimStart('/')
            if (exclude.any { it.matches(p) }) return false
            return include?.any { it.matches(p) } ?: isUiFileByDefault(p)
        }

        /** True when nothing can count as UI (an override without any rule). */
        val off: Boolean get() = include != null && include.isEmpty()

        companion object {
            val DEFAULT = Rules(null, emptyList())

            /** The rules of a [UI_FILES] file's [text]; null text means the file does not exist (the defaults). */
            fun parse(text: String?): Rules {
                if (text == null) return DEFAULT
                val inc = mutableListOf<Regex>()
                val exc = mutableListOf<Regex>()
                text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
                    if (line.startsWith("!")) line.drop(1).trim().takeIf { it.isNotEmpty() }?.let { exc += glob(it) }
                    else inc += glob(line)
                }
                return Rules(if (inc.isEmpty() && exc.isNotEmpty()) null else inc, exc)
            }
        }
    }

    /** A glob as a regex over a repository-relative path. */
    fun glob(pattern: String): Regex {
        val p = pattern.trim().trimStart('/')
        val anywhere = '/' !in p.trimEnd('/')
        val body = buildString {
            var i = 0
            while (i < p.length) {
                val c = p[i]
                when {
                    c == '*' && p.startsWith("**/", i) -> { append("(?:.*/)?"); i += 3; continue }
                    c == '*' && p.startsWith("**", i) -> { append(".*"); i += 2; continue }
                    c == '*' -> append("[^/]*")
                    c == '?' -> append("[^/]")
                    else -> append(Regex.escape(c.toString()))
                }
                i++
            }
        }
        // A trailing slash names a directory: everything under it.
        val full = if (p.endsWith("/")) "$body.*" else body
        return Regex(if (anywhere) "(?:.*/)?$full" else full)
    }

    /** The changed paths that count as UI under [rules], in diff order. */
    fun uiFiles(changed: List<String>, rules: Rules = Rules.DEFAULT): List<String> = changed.filter { rules.isUi(it) }

    const val WARNING_TITLE = "UI change reviewed without screenshots"

    /** The text stored on the ux and ui-design stages of the round (and shown on the run page). */
    fun warningText(uiFiles: List<String>): String {
        val n = uiFiles.size
        val shown = uiFiles.take(3).joinToString(", ")
        val more = if (n > 3) ", and ${n - 3} more" else ""
        return "$WARNING_TITLE: the change touches $n UI ${if (n == 1) "file" else "files"} ($shown$more), but neither the ux " +
            "nor the ui-design reviewer attached a screenshot. Their summaries should say what they tried and what failed."
    }
}
