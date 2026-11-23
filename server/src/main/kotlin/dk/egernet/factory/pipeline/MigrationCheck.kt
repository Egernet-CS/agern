package dk.egernet.factory.pipeline

import dk.egernet.factory.git.FileChange
import java.math.BigInteger

/**
 * Versioned Flyway migrations that collide when two branches are merged (docs/ARCHITECTURE.md §6 "Database
 * migrations"). Pure: the caller supplies the base branch's files and the head's changes (from git).
 *
 * The known layout is Flyway's default location: files at or below a directory `src/main/resources/db/migration`,
 * at the repository root or under a module (`server/src/main/resources/db/migration`). Each such directory is a
 * migration root of its own. A versioned migration is a file named `V<version>__<description>.sql`; its version is
 * one or more digit groups separated by `.` or `_`, compared as Flyway does (`V011` = `V11`, `V1_0` = `V1`,
 * `V1_2` < `V1_10`). Every other file (`R__…`, notes) is ignored.
 */
object MigrationCheck {
    const val ROOT = "src/main/resources/db/migration"
    // Whole-string matches, any character (a newline too) in names: file names come from repositories.
    private val NAME = Regex("V(\\d+(?:[._]\\d+)*)__.+\\.sql", RegexOption.DOT_MATCHES_ALL)
    private val LAYOUT = Regex("((?:.+/)?" + Regex.escape(ROOT) + ")/(?:.+/)?([^/]+)", RegexOption.DOT_MATCHES_ALL)

    /** Problems listed in a reason; the rest are counted. */
    const val MAX_LISTED = 5

    /** A migration version: its digit groups with trailing zero groups dropped, so equal versions are equal. */
    class Version(val text: String) : Comparable<Version> {
        private val parts: List<BigInteger> = text.split('.', '_').map { BigInteger(it) }.dropLastWhile { it.signum() == 0 }

        override fun compareTo(other: Version): Int {
            for (i in 0 until maxOf(parts.size, other.parts.size)) {
                val c = (parts.getOrNull(i) ?: BigInteger.ZERO).compareTo(other.parts.getOrNull(i) ?: BigInteger.ZERO)
                if (c != 0) return c
            }
            return 0
        }

        override fun equals(other: Any?) = other is Version && compareTo(other) == 0
        override fun hashCode() = parts.hashCode()
        override fun toString() = text
    }

    /** The version of a file named `V<version>__<description>.sql` ([name] may be a path), or null. */
    fun versionOf(name: String): Version? = NAME.matchEntire(name.substringAfterLast('/'))?.let { Version(it.groupValues[1]) }

    /** The migration root [path] lies in (known layout), or null when it is not under one. */
    fun rootOf(path: String): String? = LAYOUT.matchEntire(path)?.groupValues?.get(1)

    /** A path as it may appear in GitHub text: only `[A-Za-z0-9._/+-]`, the rest `?`, at most 200 characters. */
    fun safe(path: String): String = path.take(200).map { if (it.isLetterOrDigit() && it.code < 128 || it in "._/+-") it else '?' }.joinToString("")

    /**
     * Duplicate versions among [paths] (bare file names, or paths grouped by migration root): one message per version,
     * naming every file, e.g. `duplicate migration version 11: V11__a.sql and V11__b.sql`.
     */
    fun duplicates(paths: Collection<String>): List<String> =
        paths.mapNotNull { p -> versionOf(p)?.let { Triple(rootOf(p) ?: "", it, p) } }
            .groupBy { it.first to it.second }
            .filterValues { it.size > 1 }
            .map { (key, files) -> "duplicate migration version ${key.second}: " + files.map { safe(it.third) }.sorted().joinToString(" and ") }
            .sorted()

    /**
     * What merging the head would do wrong, given [baseFiles] (paths on the base branch's tip) and [changes] (what the
     * head changes relative to its merge base with the base, renames as a removal plus an addition). Per migration root:
     *  - an added migration with the same version as one on the base or another added one;
     *  - an added migration below the base's highest version;
     *  - a migration on the base that the head changes or removes.
     * Each problem is a phrase with the paths in backticks; empty when there is nothing wrong.
     */
    fun problems(baseFiles: Collection<String>, changes: Collection<FileChange>): List<String> {
        val out = mutableListOf<String>()
        val base = baseFiles.filter { rootOf(it) != null && versionOf(it) != null }.toSet()
        val relevant = changes.filter { rootOf(it.path) != null && versionOf(it.path) != null }
        for (root in relevant.mapNotNull { rootOf(it.path) }.distinct().sorted()) {
            val onBase = base.filter { rootOf(it) == root }.associateWith { versionOf(it)!! }
            val mine = relevant.filter { rootOf(it.path) == root }.sortedBy { it.path }
            val removed = mutableSetOf<String>()
            val added = mutableListOf<String>()
            for (c in mine) {
                when {
                    c.path in onBase && c.status == 'D' -> { out += "`${safe(c.path)}` was removed"; removed += c.path }
                    c.path in onBase && c.status == 'M' -> out += "`${safe(c.path)}` was changed"
                    // Added on both sides under the same name: git itself compares the two files.
                    c.path in onBase -> {}
                    c.status != 'D' -> added += c.path
                }
            }
            val highest = onBase.entries.maxWithOrNull(compareBy<Map.Entry<String, Version>> { it.value }.thenBy { it.key })
            for ((i, a) in added.withIndex()) {
                val v = versionOf(a)!!
                val same = onBase.entries.firstOrNull { it.key !in removed && it.value == v }?.key
                    ?: added.take(i).firstOrNull { versionOf(it) == v }
                when {
                    same != null -> out += "`${safe(a)}` has the same version ($v) as `${safe(same)}`"
                    highest != null && v < highest.value ->
                        out += "`${safe(a)}` (version $v) is not higher than `${safe(highest.key)}` (version ${highest.value})"
                }
            }
        }
        return out
    }

    /** [problems] joined with "; ", at most [MAX_LISTED] of them, then "(and N more)". */
    fun listed(problems: List<String>): String =
        problems.take(MAX_LISTED).joinToString("; ") + if (problems.size > MAX_LISTED) " (and ${problems.size - MAX_LISTED} more)" else ""

    /** Why a run's pull request #[pr] into [target] was not merged; the run's needs-human reason and a held issue's note. */
    fun runReason(target: String, pr: Int, problems: List<String>): String =
        "Database migration conflict with `$target`: ${listed(problems)}. Pull request #$pr was not merged. Migrations " +
            "already on `$target` must stay unchanged, and a new migration needs a version above all of them. Fix the " +
            "migration on the pull request's branch and approve again, or build the issue again from the current `$target` " +
            "(in a release: Retry)."

    /** Why the release branch [branch] is not merged into [target]: the release's notice and the approval refusal. */
    fun releaseReason(target: String, branch: String, problems: List<String>): String =
        "Database migration conflict with `$target`: ${listed(problems)}. The release is not merged. Fix the migrations on " +
            "`$branch` (merge `$target` into it; a new migration needs a version above every migration on `$target`, and " +
            "existing ones must stay unchanged), then use \"Check again\"."
}
