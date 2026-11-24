package dk.egernet.factory.pipeline

import dk.egernet.factory.git.GitlinkChange

/**
 * Submodule pointers that must not be merged (docs/ARCHITECTURE.md §6 "Submodule pointers"): a pull request that moves a
 * submodule to a commit its remote does not have would leave the target branch pointing at a commit no one can fetch.
 * Fails closed: a pointer whose commit cannot be checked blocks the merge too. Pure apart from [has].
 */
object SubmoduleCheck {
    /** A pointer names a commit the submodule's remote does not have. */
    const val MISSING = "submodule_commit_missing"
    /** Whether a pointer's commit exists could not be told (remote unreachable, no URL, git failing). */
    const val UNCHECKED = "submodule_check_failed"

    /** Pointers listed in a reason; the rest are counted. */
    const val MAX_LISTED = 5

    /** Why the merge is refused: [code] ([MISSING] or [UNCHECKED]) and a sentence naming the pointers ([text]). */
    data class Problem(val code: String, val text: String)

    /**
     * Checks every pointer in [changes] with [has] (does the submodule's remote at the URL have the commit? throws when it
     * cannot tell). Null when all commits exist; else the missing ones, or failing that the ones that could not be
     * checked, as a [Problem] for pull request [prRef] into [target]. [onError] hears why a check failed (for the log; the
     * texts never carry git's output or URLs, which may name hosts).
     */
    fun check(
        changes: List<GitlinkChange>, target: String, prRef: String,
        has: (GitlinkChange) -> Boolean, onError: (GitlinkChange, Exception) -> Unit = { _, _ -> },
    ): Problem? {
        val missing = mutableListOf<GitlinkChange>()
        val unchecked = mutableListOf<GitlinkChange>()
        for (c in changes) {
            if (c.url == null) {
                unchecked += c
                continue
            }
            try {
                if (!has(c)) missing += c
            } catch (e: Exception) {
                onError(c, e)
                unchecked += c
            }
        }
        if (missing.isNotEmpty()) {
            return Problem(
                MISSING,
                "Submodule commit missing: pull request $prRef moves ${list(missing)}, which the submodule's repository does " +
                    "not have, so merging it would leave `$target` pinned to a commit no one can fetch. Nothing was merged. " +
                    "Push that commit to the submodule's repository (or reset the pointer on the pull request's branch to a " +
                    "commit that is there), then approve again.",
            )
        }
        if (unchecked.isNotEmpty()) {
            return Problem(
                UNCHECKED,
                "Submodule commit not checked: pull request $prRef moves ${list(unchecked)}, and whether the submodule's " +
                    "repository has that commit could not be checked (its URL is missing from `.gitmodules`, or the repository " +
                    "could not be reached). Nothing was merged. Approve again once it can be checked.",
            )
        }
        return null
    }

    /** "submodule `a` to commit `<sha>`, …" with at most [MAX_LISTED] entries. */
    private fun list(changes: List<GitlinkChange>): String {
        val shown = changes.take(MAX_LISTED).joinToString(", ") { "`${safePath(it.path)}` to commit `${it.sha}`" }
        val more = changes.size - MAX_LISTED
        return (if (changes.size == 1) "submodule " else "submodules ") + shown + if (more > 0) " (and $more more)" else ""
    }

    /** A path from a repository as shown in texts: `[A-Za-z0-9._/+-]` only (the rest `?`), at most 200 characters. */
    private fun safePath(p: String) = p.map { if (it.isLetterOrDigit() && it.code < 128 || it in "._/+-") it else '?' }.joinToString("").take(200)
}
