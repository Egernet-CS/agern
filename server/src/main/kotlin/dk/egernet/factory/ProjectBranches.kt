package dk.egernet.factory

import dk.egernet.factory.db.ProjectSettingsStore
import dk.egernet.factory.github.GhRepo

/**
 * The base branch of a project (docs/ARCHITECTURE.md §6 "Base branch"): the branch new single runs start from, open
 * their pull request against and merge into, and the branch the chat checkout follows. The single place that resolves
 * it, so that workspace members can reuse it later.
 */
class ProjectBranches(private val settings: ProjectSettingsStore?) {
    /** The base branch an admin configured for [repo]; null = the repository's default branch. Blocking. */
    fun configured(repo: String): String? = settings?.get(repo)?.baseBranch

    /** The base branch of [repo]: the configured one, else the repository's default branch. Blocking. */
    fun baseOf(repo: GhRepo): String = configured(repo.name) ?: repo.defaultBranch
}
