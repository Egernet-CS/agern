package dk.egernet.factory.db

import dk.egernet.factory.ProjectSettingsDto
import java.sql.ResultSet
import java.util.UUID

/**
 * Per-project settings set by an admin (docs/ARCHITECTURE.md §6 "Merge policy", "Base branch"), one row per repository, keyed by its
 * name. A repository without a row has the defaults ([ProjectSettingsDto] with policy `automatic`, no reviewers and the default branch as base).
 */
class ProjectSettingsStore(private val db: Db) {
    private fun map(rs: ResultSet) = ProjectSettingsDto(
        rs.getString("merge_policy"), splitRepos(rs.getString("reviewers")), splitRepos(rs.getString("team_reviewers")),
        rs.getString("base_branch"),
    )

    /** The settings of [repo]: the stored row, or the defaults. */
    fun get(repo: String): ProjectSettingsDto =
        db.queryOne("SELECT merge_policy, reviewers, team_reviewers, base_branch FROM project_settings WHERE repo = ?", repo, map = ::map)
            ?: ProjectSettingsDto()

    /** Every stored row by repository, in one query (for the project list). */
    fun all(): Map<String, ProjectSettingsDto> =
        db.query("SELECT repo, merge_policy, reviewers, team_reviewers, base_branch FROM project_settings") { it.getString("repo") to map(it) }.toMap()

    /** Stores [settings] for [repo] (already validated), replacing earlier ones in the same statement. */
    fun put(repo: String, settings: ProjectSettingsDto, by: UUID) = db.update(
        """INSERT INTO project_settings(repo, merge_policy, reviewers, team_reviewers, base_branch, updated_by, updated_at)
           VALUES (?, ?, ?, ?, ?, ?, now())
           ON CONFLICT (repo) DO UPDATE SET merge_policy = EXCLUDED.merge_policy, reviewers = EXCLUDED.reviewers,
             team_reviewers = EXCLUDED.team_reviewers, base_branch = EXCLUDED.base_branch, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at""",
        repo, settings.mergePolicy, joinRepos(settings.reviewers), joinRepos(settings.teamReviewers), settings.baseBranch, by,
    )
}
