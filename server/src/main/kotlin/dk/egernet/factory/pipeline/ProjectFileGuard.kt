package dk.egernet.factory.pipeline

import dk.egernet.factory.files.ProjectFiles
import dk.egernet.factory.git.NewBlob
import java.nio.file.Path

/**
 * The check before every push of a run's branch (docs/ARCHITECTURE.md §6 "Project file guard"): a blob the push would
 * publish for the first time must not be one of the project's files. Git object ids are not SHA-256 of the content, so
 * a project file matches a blob when its git object id (computed in the repository's object format) equals the
 * blob's; only files whose size equals a new blob's size are hashed. Unpacked, re-compressed or modified copies are not
 * detected. Pure: the git calls are made by the caller.
 */
object ProjectFileGuard {
    /** New blobs of this size or more that are no project file get a warning (the push goes ahead). */
    const val LARGE_BYTES = 10L shl 20
    /** At most this many matches (and large blobs) are named in a text. */
    const val MAX_LISTED = 10

    /** A project file the guard compares with: [file] holds its bytes on the server. */
    data class Candidate(val name: String, val size: Long, val sha256: String, val file: Path)

    /** Project file [name] (content [sha256]) is committed as [path]. */
    data class Match(val name: String, val sha256: String, val path: String)

    /** The candidates worth hashing: one per content, and only those whose size equals some new blob's size. */
    fun toHash(blobs: List<NewBlob>, candidates: List<Candidate>): List<Candidate> {
        val sizes = blobs.map { it.size }.toSet()
        return candidates.filter { it.size in sizes }.distinctBy { it.sha256 }
    }

    /**
     * The new blobs that are project files: [ids] maps a candidate's SHA-256 to its git object id. Every blob that
     * matches is listed, with the first candidate name of that content.
     */
    fun matches(blobs: List<NewBlob>, candidates: List<Candidate>, ids: Map<String, String>): List<Match> {
        val byId = HashMap<String, Candidate>()
        for (c in candidates) ids[c.sha256]?.let { id -> byId.putIfAbsent(id.lowercase(), c) }
        return blobs.mapNotNull { b -> byId[b.sha.lowercase()]?.let { Match(it.name, it.sha256, b.path) } }
    }

    /** New blobs of [LARGE_BYTES] or more that are not among [matched] and not in [alreadyWarned], largest first. */
    fun large(blobs: List<NewBlob>, matched: List<Match>, alreadyWarned: Set<String> = emptySet()): List<NewBlob> {
        val paths = matched.map { it.path }.toSet()
        return blobs.filter { it.size >= LARGE_BYTES && it.path !in paths && it.sha !in alreadyWarned }.sortedByDescending { it.size }
    }

    /** Why the push did not happen (the run's error, the issue comment, the advisor's transcript). */
    fun blockedText(matches: List<Match>): String = buildString {
        append("Not pushed: the branch contains ")
        append(matches.take(MAX_LISTED).joinToString(", ") {
            "project file `${MigrationCheck.safe(it.name)}` (sha256 `${it.sha256.take(12)}`) committed as `${MigrationCheck.safe(it.path)}`"
        })
        if (matches.size > MAX_LISTED) append(", and ${matches.size - MAX_LISTED} more")
        append(". Project files must not be committed. Remove ")
        append(if (matches.size == 1) "it" else "them")
        append(" from every commit of the branch (a later commit that deletes ")
        append(if (matches.size == 1) "it" else "them")
        append(" is not enough), then use Retry → Continue, or talk to the agent on the run page.")
    }

    /** The `warn` event for large new files that are no project file. */
    fun largeText(blobs: List<NewBlob>): String = buildString {
        append("Large new file(s) in this branch: ")
        append(blobs.take(MAX_LISTED).joinToString(", ") { "`${MigrationCheck.safe(it.path)}` (${ProjectFiles.humanSize(it.size)})" })
        if (blobs.size > MAX_LISTED) append(", and ${blobs.size - MAX_LISTED} more")
        append(". If one is a vendor or licensed file, it belongs in the project's Files, not in git.")
    }

    /** The text when the guard itself could not run (the push does not happen). */
    fun failedText(why: String?): String =
        "Not pushed: the project-file check could not be made (${why?.take(500) ?: "unknown error"}). Nothing was pushed; retry the push."
}
