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

    /**
     * Where a run stands with its update rounds ("Update and merge", docs/ARCHITECTURE.md §6 "Retrying a run"): [used] of
     * [max] started. With [max] 0 the feature is off.
     */
    data class Rounds(val used: Int, val max: Int) {
        val left: Int get() = (max - used).coerceAtLeast(0)
        val available: Boolean get() = used < max
    }

    /**
     * The end of a merge refusal that an update round can resolve: "Update and merge" while rounds are left ([what] it
     * lets the builder do, or [fix] by hand), the limit when they are used up ([fixAtLimit] by hand), else [off] (the
     * feature is off).
     */
    fun updateEnding(target: String, rounds: Rounds, what: String, fix: String, off: String, fixAtLimit: String = fix): String = when {
        rounds.max <= 0 -> off
        rounds.available -> "Use \"Update and merge\" to have Agern merge `$target` into the branch and let the builder $what " +
            "(update round ${rounds.used + 1} of ${rounds.max}), or $fix and approve again."
        else -> "The limit of ${rounds.max} update round(s) is reached: $fixAtLimit and approve again, or build the issue again from the " +
            "current `$target` (in a release: Retry)."
    }

    /**
     * Why a run's pull request #[pr] into [target] was not merged; the run's needs-human reason and a held issue's note.
     * [rounds]: the run's update rounds, for the way forward the reason names.
     */
    fun runReason(target: String, pr: Int, problems: List<String>, prRef: String = "#$pr", rounds: Rounds = Rounds(0, 0)): String =
        "Database migration conflict with `$target`: ${listed(problems)}. Pull request $prRef was not merged. Migrations " +
            "already on `$target` must stay unchanged, and a new migration needs a version above all of them. " +
            updateEnding(
                target, rounds, "renumber the migration", "fix the migration on the pull request's branch",
                "Fix the migration on the pull request's branch and approve again, or build the issue again from the current " +
                    "`$target` (in a release: Retry).",
            )

    /** Why a run's pull request [prRef] was not merged because it conflicts with [target] or is behind it ([why]: GitHub's answer). */
    fun mergeConflictReason(target: String, prRef: String, why: String, rounds: Rounds = Rounds(0, 0)): String =
        "Merging pull request $prRef failed: $why. The branch conflicts with `$target` or is behind it. " +
            updateEnding(
                target, rounds, "resolve the conflicts", "resolve them on the pull request's branch",
                "Resolve the conflicts on the pull request's branch and approve again, or build the issue again from the current " +
                    "`$target` (in a release: Retry).",
                fixAtLimit = "resolve the conflicts on the pull request's branch",
            )

    /** One migration of the run to renumber: [path] gets [version] ([newPath]); [kept] on the target keeps [keptVersion]. */
    data class Renumber(val path: String, val version: String, val newPath: String, val kept: String?, val keptVersion: String?)

    /**
     * How the run's migrations fit the target again ([plan]): the ones to renumber, the first free version on the target
     * per migration root, and the target's migrations the run changed or removed (to be restored as they are there).
     */
    data class Plan(val renumber: List<Renumber>, val nextFree: Map<String, String>, val restore: List<String>) {
        val isEmpty: Boolean get() = renumber.isEmpty() && restore.isEmpty()
    }

    /**
     * The version after [v]: its last digit group plus one, with its width of leading zeros and its separators kept
     * (`25` → `26`, `009` → `010`, `1_9` → `1_10`, `1.2` → `1.3`).
     */
    fun nextVersion(v: String): String {
        val m = Regex("(\\d+)$").find(v) ?: return "1"
        val digits = m.groupValues[1]
        val next = BigInteger(digits).add(BigInteger.ONE).toString().padStart(digits.length, '0')
        return v.substring(0, m.range.first) + next
    }

    /**
     * The renumbering that resolves [problems] for the same input: per migration root, every added migration that
     * [problems] flags (same version as one on the base or an earlier added one, or below the base's highest) gets a new
     * version, in order of (version, path), counting up from the highest of the base's versions and of the added ones
     * that keep theirs. Pure.
     */
    fun plan(baseFiles: Collection<String>, changes: Collection<FileChange>): Plan {
        val renumber = mutableListOf<Renumber>()
        val nextFree = sortedMapOf<String, String>()
        val restore = mutableListOf<String>()
        val base = baseFiles.filter { rootOf(it) != null && versionOf(it) != null }.toSet()
        val relevant = changes.filter { rootOf(it.path) != null && versionOf(it.path) != null }
        for (root in relevant.mapNotNull { rootOf(it.path) }.distinct().sorted()) {
            val onBase = base.filter { rootOf(it) == root }.associateWith { versionOf(it)!! }
            val mine = relevant.filter { rootOf(it.path) == root }.sortedBy { it.path }
            val removed = mutableSetOf<String>()
            val added = mutableListOf<String>()
            for (c in mine) {
                when {
                    c.path in onBase && (c.status == 'D' || c.status == 'M') -> { restore += c.path; if (c.status == 'D') removed += c.path }
                    c.path in onBase -> {}
                    c.status != 'D' -> added += c.path
                }
            }
            val highest = onBase.entries.maxWithOrNull(compareBy<Map.Entry<String, Version>> { it.value }.thenBy { it.key })
            nextFree[root] = highest?.let { nextVersion(it.value.text) } ?: "1"
            val flagged = mutableListOf<Pair<String, String?>>()
            for ((i, a) in added.withIndex()) {
                val v = versionOf(a)!!
                val same = onBase.entries.firstOrNull { it.key !in removed && it.value == v }?.key
                val sameAdded = added.take(i).firstOrNull { versionOf(it) == v }
                when {
                    same != null -> flagged += a to same
                    sameAdded != null -> flagged += a to null
                    highest != null && v < highest.value -> flagged += a to null
                }
            }
            if (flagged.isEmpty()) continue
            val flaggedPaths = flagged.map { it.first }.toSet()
            val keep = added.filter { it !in flaggedPaths }.map { versionOf(it)!! }
            var current: Version? = (listOfNotNull(highest?.value) + keep).maxWithOrNull(compareBy { it })
            // The base's text wins over an added file's when both have the highest version (its width is the target's).
            if (highest != null && current == highest.value) current = highest.value
            for ((a, same) in flagged.sortedWith(compareBy<Pair<String, String?>> { versionOf(it.first)!! }.thenBy { it.first })) {
                val next = current?.let { nextVersion(it.text) } ?: "1"
                current = Version(next)
                val name = a.substringAfterLast('/')
                val newPath = a.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } + "V${next}__" + name.substringAfter("__")
                val kept = same ?: highest?.key
                renumber += Renumber(a, next, newPath, kept, kept?.let { versionOf(it)?.text })
            }
        }
        return Plan(renumber, nextFree, restore)
    }

    /** Why the release branch [branch] is not merged into [target]: the release's notice and the approval refusal. */
    fun releaseReason(target: String, branch: String, problems: List<String>): String =
        "Database migration conflict with `$target`: ${listed(problems)}. The release is not merged. Fix the migrations on " +
            "`$branch` (merge `$target` into it; a new migration needs a version above every migration on `$target`, and " +
            "existing ones must stay unchanged), then use \"Check again\"."
}
