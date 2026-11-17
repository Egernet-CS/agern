package dk.egernet.factory

import dk.egernet.factory.agent.AgentManager
import dk.egernet.factory.db.RunRecord
import dk.egernet.factory.db.RunStore
import dk.egernet.factory.db.SessionRecord
import dk.egernet.factory.db.SoftwareRequestStore
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.github.Access
import dk.egernet.factory.github.GhIssue
import dk.egernet.factory.github.GhRepo
import dk.egernet.factory.github.GitHubException
import dk.egernet.factory.pipeline.Orchestrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Operations shared by the HTTP API, the MCP tools and `/factory` chat commands.
 * Every method acts as [UserRecord] `user`: GitHub calls use that user's token, and runs are owned by them.
 */
class FactoryService(
    private val cfg: Config,
    private val access: Access,
    private val runs: RunStore,
    private val orchestrator: Orchestrator,
    private val agents: AgentManager,
    private val softwareRequests: SoftwareRequestStore,
) {
    private val org get() = cfg.githubOrg

    private suspend fun <T> gh(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: GitHubException) {
            when (e.status) {
                401 -> throw unprocessable("GitHub rejected your token", "github_token_invalid")
                403, 404 -> throw notFound(e.message ?: "Not found")
                422 -> throw unprocessable(e.message ?: "GitHub rejected the request")
                else -> throw ApiException(io.ktor.http.HttpStatusCode.BadGateway, "github_error", e.message ?: "GitHub error")
            }
        }
    }

    fun issueDto(repo: String, i: GhIssue, withBody: Boolean, activeRunId: UUID?) = IssueDto(
        number = i.number, title = i.title, state = i.state, kind = issueKindFromLabels(i.labels.map { it.name }),
        labels = i.labels.map { LabelDto(it.name, it.color) }, author = i.user?.login ?: "", assignees = i.assignees.map { it.login },
        comments = i.comments, createdAt = i.createdAt, updatedAt = i.updatedAt, htmlUrl = i.htmlUrl,
        body = if (withBody) (i.body ?: "") else null, activeRunId = activeRunId?.toString(),
    )

    fun projectDto(r: GhRepo, agentCounts: Map<String, Int>, runCounts: Map<String, Int>) = ProjectDto(
        name = r.name, fullName = r.fullName, description = r.description, private = r.private, defaultBranch = r.defaultBranch,
        language = r.language, openIssues = r.openIssuesCount, pushedAt = r.pushedAt, htmlUrl = r.htmlUrl,
        activeAgents = agentCounts[r.name] ?: 0, activeRuns = runCounts[r.name] ?: 0,
    )

    suspend fun listProjects(user: UserRecord): List<ProjectDto> {
        val client = access.client(user) ?: return emptyList()
        val repos = gh { client.listOrgRepos(org) }.filter { !it.archived }
        access.remember(user, repos)
        val agentsByRepo = agents.liveCountByRepo()
        val runsByRepo = runs.countActiveByRepo()
        return repos.sortedByDescending { it.pushedAt ?: "" }.map { projectDto(it, agentsByRepo, runsByRepo) }
    }

    suspend fun getProject(user: UserRecord, repo: String): ProjectDto {
        val r = withContext(Dispatchers.IO) { access.requireVisible(user, repo) }
        return projectDto(r, agents.liveCountByRepo(), runs.countActiveByRepo())
    }

    suspend fun listIssues(user: UserRecord, repo: String, state: String): List<IssueDto> {
        if (state !in setOf("open", "closed", "all")) throw badRequest("state must be open, closed or all")
        withContext(Dispatchers.IO) { access.requireVisible(user, repo) }
        val client = access.requireClient(user)
        val active = runs.activeRunsByIssue(repo)
        return gh { client.listIssues(org, repo, state) }.map { issueDto(repo, it, false, active[it.number]) }
    }

    suspend fun getIssue(user: UserRecord, repo: String, number: Int): IssueDto {
        withContext(Dispatchers.IO) { access.requireVisible(user, repo) }
        val client = access.requireClient(user)
        val i = gh { client.getIssue(org, repo, number) } ?: throw notFound("Issue #$number not found")
        return issueDto(repo, i, true, runs.activeRunFor(repo, number))
    }

    /** Issue with its comments, as plain text for agents. */
    suspend fun issueForAgent(user: UserRecord, repo: String, number: Int): String {
        withContext(Dispatchers.IO) { access.requireVisible(user, repo) }
        val client = access.requireClient(user)
        val i = gh { client.getIssue(org, repo, number) } ?: throw notFound("Issue #$number not found")
        val comments = gh { client.listComments(org, repo, number) }
        return buildString {
            append("#${i.number} ${i.title} [${i.state}] kind=${issueKindFromLabels(i.labels.map { it.name })}\n")
            append("Labels: ${i.labels.joinToString(", ") { it.name }.ifEmpty { "-" }} · Author: @${i.user?.login} · ${i.htmlUrl}\n\n")
            append(i.body?.trim().orEmpty().ifEmpty { "(no description)" })
            if (comments.isNotEmpty()) {
                append("\n\n--- Comments ---\n")
                comments.forEach { append("\n@${it.user?.login} (${it.createdAt}):\n${it.body?.trim()}\n") }
            }
            runs.activeRunFor(repo, number)?.let { append("\n\nActive run: $it") }
        }
    }

    suspend fun createIssue(user: UserRecord, repo: String, title: String, body: String, kind: String): IssueDto {
        if (title.isBlank()) throw badRequest("title is required")
        if (title.length > 256) throw badRequest("title is too long")
        val labels = when (kind) {
            "bug" -> listOf("bug"); "feature" -> listOf("enhancement"); "task" -> emptyList()
            else -> throw badRequest("kind must be feature, bug or task")
        }
        withContext(Dispatchers.IO) { access.requireVisible(user, repo) }
        val client = access.requireClient(user)
        val i = gh { client.createIssue(org, repo, title.trim(), body, labels) }
        return issueDto(repo, i, true, null)
    }

    suspend fun startRun(user: UserRecord, repo: String, issueNumber: Int, startedBySession: UUID?): RunDto =
        runs.toDto(orchestrator.start(user, repo, issueNumber, startedBySession))

    /** Visibility-checked run lookup. */
    suspend fun visibleRun(user: UserRecord, runId: UUID): RunRecord {
        val r = runs.get(runId) ?: throw notFound("Run not found")
        if (!withContext(Dispatchers.IO) { access.canSee(user, r.repo) }) throw notFound("Run not found")
        return r
    }

    suspend fun listRuns(user: UserRecord, repo: String?, activeOnly: Boolean, statuses: Set<String>? = null): List<RunDto> {
        val list = runs.list(repo, activeOnly, statuses = statuses)
        val visible = withContext(Dispatchers.IO) { list.map { it.repo }.distinct().filter { access.canSee(user, it) }.toSet() }
        return list.filter { it.repo in visible }.map { runs.toDto(it) }
    }

    suspend fun approveRun(user: UserRecord, runId: UUID): RunDto {
        visibleRun(user, runId)
        return runs.toDto(orchestrator.approve(user, runId))
    }

    suspend fun cancelRun(user: UserRecord, runId: UUID): RunDto {
        visibleRun(user, runId)
        return runs.toDto(orchestrator.cancel(user, runId))
    }

    fun requestSoftware(user: UserRecord, repo: String, sessionId: UUID?, name: String, reason: String): SoftwareRequestDto {
        if (name.isBlank() || name.length > 200) throw badRequest("name is required (max 200 chars)")
        return softwareRequests.create(name.trim(), reason.take(4000), repo, user.username, sessionId)
    }

    // ------------------------------------------------------------ /factory chat commands

    suspend fun handleCommand(session: SessionRecord, owner: UserRecord, text: String): String {
        val parts = text.trim().split(Regex("\\s+")).drop(1)
        val cmd = parts.firstOrNull()?.lowercase() ?: "help"
        val repo = session.repo
        return when (cmd) {
            "help" -> HELP
            "run" -> {
                val n = parts.getOrNull(1)?.removePrefix("#")?.toIntOrNull() ?: return "Usage: /factory run <issue number>"
                val run = startRun(owner, repo, n, session.id)
                "Started run ${run.id} for #$n \"${run.issueTitle}\" (status ${run.status})."
            }
            "status" -> {
                val list = runs.list(repo, activeOnly = false, limit = 10)
                if (list.isEmpty()) "No runs in $repo yet." else list.joinToString("\n", "Runs in $repo (newest first):\n") {
                    "• ${it.id}  #${it.issueNumber} ${it.issueTitle} — ${it.status}" +
                        (if (it.round > 0) " (fix round ${it.round}/${it.maxRounds})" else "") +
                        (it.prUrl?.let { u -> " $u" } ?: "") + (it.error?.let { e -> "\n    $e" } ?: "")
                }
            }
            "approve" -> {
                val id = parts.getOrNull(1)?.let { parseUuid(it) } ?: run {
                    val candidates = runs.list(repo, activeOnly = true).filter { it.status == RunStatus.NEEDS_HUMAN && it.ownerId == owner.id }
                    when (candidates.size) {
                        0 -> return "None of your runs in $repo is waiting for approval."
                        1 -> candidates.first().id
                        else -> return "Several runs wait for approval; give the run id:\n" +
                            candidates.joinToString("\n") { "• ${it.id}  #${it.issueNumber} ${it.issueTitle}" }
                    }
                }
                val r = approveRun(owner, id)
                "Run ${r.id}: ${r.status}" + (r.error?.let { " — $it" } ?: "")
            }
            "cancel" -> {
                val id = parts.getOrNull(1)?.let { parseUuid(it) } ?: return "Usage: /factory cancel <runId>"
                val r = cancelRun(owner, id)
                "Run ${r.id}: ${r.status}"
            }
            else -> "Unknown command '$cmd'.\n$HELP"
        }
    }

    private fun parseUuid(s: String): UUID = try {
        UUID.fromString(s)
    } catch (e: IllegalArgumentException) {
        throw badRequest("'$s' is not a run id")
    }

    companion object {
        val HELP = """
            |Factory commands (handled by Agern, not sent to Claude):
            |  /factory run <issue>       start a pipeline run for an issue in this project
            |  /factory status            list recent runs in this project
            |  /factory approve [runId]   merge a run that waits for a human (owner only)
            |  /factory cancel <runId>    cancel a run (owner or admin)
            |  /factory help              this help
        """.trimMargin()
    }
}
