package dk.egernet.factory.agent

import dk.egernet.factory.git.CheckoutState

/** Branch names in checkout notes are cut to this many characters. */
private const val NOTE_BRANCH_MAX = 100

private fun short(sha: String) = sha.take(7)
private fun commits(n: Int) = if (n == 1) "$n commit" else "$n commits"
private fun branchName(b: String) = b.take(NOTE_BRANCH_MAX)

/** Why a checkout that is behind was not updated, for the agent (docs/ARCHITECTURE.md §5 "Workspaces"). */
fun checkoutWhy(state: CheckoutState): String {
    val b = branchName(state.defaultBranch)
    return when (state.reason) {
        "other_branch" ->
            if (state.branch == null) "a detached HEAD is checked out, not `$b`"
            else "branch `${branchName(state.branch)}` is checked out, not `$b`"
        "local_changes" -> "it has uncommitted changes"
        "local_commits" -> "it has ${state.ahead} local ${if (state.ahead == 1) "commit" else "commits"} that are not on origin/$b"
        else -> "git could not fast-forward it"
    }
}

/**
 * The `<checkout_status>` note prepended to a chat message for the agent after a sync, or null when there is nothing
 * to tell (status `current`).
 */
fun checkoutNote(state: CheckoutState): String? {
    val b = branchName(state.defaultBranch)
    val body = when (state.status) {
        "updated" -> {
            val from = state.updatedFrom ?: return null
            "Agern fast-forwarded this checkout of `$b` from ${short(from)} to ${short(state.head)} " +
                "(${state.newCommits} new ${if (state.newCommits == 1) "commit" else "commits"} from origin/$b). Files you read earlier in this conversation may have changed: " +
                "read them again before relying on them."
        }
        "behind" ->
            "This checkout is ${commits(state.behind)} behind origin/$b (HEAD ${short(state.head)}) and Agern did not update it " +
                "because ${checkoutWhy(state)}. The code you read here may be out of date: say so when you answer questions about " +
                "the current state of the project, and use `git log HEAD..origin/$b` or `git show origin/$b:<path>` to look at " +
                "the current code. Do not pull, merge, reset or stash on your own; ask the person first."
        else -> return null
    }
    return "<checkout_status>\n$body\n</checkout_status>"
}
