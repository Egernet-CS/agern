package dk.egernet.factory

import dk.egernet.factory.agent.AgentManager
import dk.egernet.factory.agent.EventBus
import dk.egernet.factory.agent.FeedItem
import dk.egernet.factory.db.RunRecord
import dk.egernet.factory.db.RunStore
import dk.egernet.factory.db.SessionRecord
import dk.egernet.factory.db.SessionStore
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
    private val sessions: SessionStore,
    private val bus: EventBus,
    private val releases: ReleaseService,
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

    fun issueDto(repo: String, i: GhIssue, withBody: Boolean, activeRunId: UUID?, release: IssueReleaseDto? = null) = IssueDto(
        number = i.number, title = i.title, state = i.state, kind = issueKindFromLabels(i.labels.map { it.name }),
        labels = i.labels.map { LabelDto(it.name, it.color) }, author = i.user?.login ?: "", assignees = i.assignees.map { it.login },
        comments = i.comments, createdAt = i.createdAt, updatedAt = i.updatedAt, htmlUrl = i.htmlUrl,
        body = if (withBody) (i.body ?: "") else null, activeRunId = activeRunId?.toString(), release = release,
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
        val planned = withContext(Dispatchers.IO) { releases.releaseByIssue(repo) }
        return gh { client.listIssues(org, repo, state) }.map { issueDto(repo, it, false, active[it.number], planned[it.number]) }
    }

    suspend fun getIssue(user: UserRecord, repo: String, number: Int): IssueDto {
        withContext(Dispatchers.IO) { access.requireVisible(user, repo) }
        val client = access.requireClient(user)
        val i = gh { client.getIssue(org, repo, number) } ?: throw notFound("Issue #$number not found")
        val planned = withContext(Dispatchers.IO) { releases.releaseByIssue(repo) }
        return issueDto(repo, i, true, runs.activeRunFor(repo, number), planned[number])
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
        val dto = issueDto(repo, i, true, null)
        // Fresh openIssues on the next project fetch, then announce it to everyone who can see the repo.
        access.forgetRepo(repo)
        bus.emitFeed(FeedItem.Issue(repo, dto.copy(body = null)))
        return dto
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

    /**
     * A message for a running pipeline agent, addressed by run + role (role omitted: the run's running spec-writer or
     * builder) or by its session id. Visibility (404), then ownership (403, admins included), then role and liveness (409)
     * are checked; [repo] (an MCP grant's repository) restricts the target to that project. [viaChatSession] is the
     * forwarding chat session, if any.
     */
    suspend fun sendToAgent(
        user: UserRecord, runId: UUID?, role: String?, sessionId: UUID?, text: String, viaChatSession: UUID?, repo: String? = null,
    ): AgentMessageDto {
        val target: SessionRecord = when {
            sessionId != null -> {
                val rec = sessions.get(sessionId)?.takeIf { it.repo == (repo ?: it.repo) && it.kind != "chat" }
                    ?: throw notFound("Agent session not found")
                if (!withContext(Dispatchers.IO) { access.canSee(user, rec.repo) }) throw notFound("Agent session not found")
                rec
            }
            runId != null -> {
                val run = visibleRun(user, runId)
                if (repo != null && run.repo != repo) throw notFound("Run not found in this project")
                if (run.ownerId != user.id) throw forbidden("Only the owner of this run can send messages to its agents")
                if (role != null && role !in PIPELINE_ROLES) throw badRequest("Unknown role '$role'")
                val stages = runs.stages(run.id)
                val stage = if (role != null) {
                    stages.filter { it.role == role }.maxByOrNull { it.round }
                } else {
                    stages.lastOrNull { it.status == "running" && it.role in STEERABLE_ROLES }
                }
                if (role != null && role !in STEERABLE_ROLES) {
                    throw conflict(
                        "The $role agent does not accept messages: reviewers and the approver answer in a fixed verdict format. " +
                            "Only a running spec-writer or builder can be messaged.",
                        "agent_does_not_accept_messages",
                    )
                }
                val sid = stage?.sessionId ?: throw conflict(
                    "No ${role ?: "spec-writer or builder"} agent is running in run ${run.id} (run status ${run.status}). " +
                        "Messages can only be delivered to a running agent; to change finished work, start a new run.",
                    "agent_not_running",
                )
                sessions.get(sid) ?: throw notFound("Agent session not found")
            }
            else -> throw badRequest("Give runId (and optionally role) or sessionId")
        }
        return agents.sendToStageAgent(user, target, text, viaChatSession)
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
            "releases" -> releases.describe(owner, repo, withIds = false)
            "release" -> releaseCommand(owner, repo, parts.drop(1))
            else -> "Unknown command '$cmd'.\n$HELP"
        }
    }

    /** `/factory release create|add|remove …` (docs/ARCHITECTURE.md §7). */
    private suspend fun releaseCommand(owner: UserRecord, repo: String, args: List<String>): String {
        val usage = "Usage: /factory release create <name> [description] | add <name> <issue>… | remove <issue>… | " +
            "approve <name> | mode <name> manual|automatic"
        return when (args.firstOrNull()?.lowercase()) {
            "mode" -> {
                val modeUsage = "Usage: /factory release mode <name> manual|automatic"
                val name = args.getOrNull(1) ?: return modeUsage
                val mode = args.getOrNull(2)?.lowercase()?.takeIf { it in ReleaseMode.ALL && args.size == 3 } ?: return modeUsage
                modeReply(releases.setMode(owner, releases.find(owner, repo, name).id, mode))
            }
            "approve" -> {
                val name = args.getOrNull(1) ?: return "Usage: /factory release approve <name>"
                approvedReply(releases.approve(owner, releases.find(owner, repo, name).id))
            }
            "create" -> {
                val name = args.getOrNull(1) ?: return "Usage: /factory release create <name> [description]"
                val r = releases.create(owner, repo, name, args.drop(2).joinToString(" "))
                "Created release \"${r.name}\"."
            }
            "add" -> {
                val name = args.getOrNull(1) ?: return "Usage: /factory release add <name> <issue> [<issue>…]"
                val numbers = parseIssueTokens(args.drop(2)) ?: return "Usage: /factory release add <name> <issue> [<issue>…]"
                val target = releases.find(owner, repo, name)
                val out = releases.assign(owner, repo, numbers, target.id)
                assignReply(target.name, out)
            }
            "remove" -> {
                val numbers = parseIssueTokens(args.drop(1)) ?: return "Usage: /factory release remove <issue> [<issue>…]"
                val out = releases.assign(owner, repo, numbers, null)
                if (out.removed.isEmpty()) "None of ${numbers.joinToString(", ") { "#$it" }} is in a release."
                else "Removed ${out.removed.joinToString(", ") { "#$it" }} from ${if (out.removed.size == 1) "its release" else "their releases"}."
            }
            else -> usage
        }
    }

    private fun parseUuid(s: String): UUID = try {
        UUID.fromString(s)
    } catch (e: IllegalArgumentException) {
        throw badRequest("'$s' is not a run id")
    }

    companion object {
        /** `5`, `#5` and `#5,`; null when there is none or one is not an issue number. */
        fun parseIssueTokens(tokens: List<String>): List<Int>? {
            val nums = tokens.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
                .map { t -> t.removePrefix("#").toIntOrNull()?.takeIf { it > 0 } ?: return null }
            return nums.ifEmpty { null }
        }

        /** The reply to an approval (`/factory release approve`, `factory_approve_release`). */
        fun approvedReply(r: dk.egernet.factory.db.ReleaseRecord): String {
            val closed = r.merged.size
            val target = r.targetBranch ?: "the default branch"
            return "Release \"${r.name}\" is released: pull request ${r.prUrl ?: "#${r.prNumber}"} merged into $target, " +
                "$closed issue${if (closed == 1) "" else "s"} closed." + (r.notice?.let { " $it" } ?: "")
        }

        /** The reply to `/factory release mode`. */
        fun modeReply(r: dk.egernet.factory.db.ReleaseRecord): String =
            if (r.automatic) "Release \"${r.name}\" is released automatically: once it is ready, complete and merges cleanly, and all " +
                "checks on its pull request have passed, Agern merges it with the owner's GitHub token."
            else "Release \"${r.name}\" waits for its owner's approval once it is ready (manual)."

        /** E.g. `Release "1.4": added #5, #6 (now 4 issues).` plus moves from other releases. */
        fun assignReply(name: String, out: ReleaseService.AssignOutcome): String {
            val total = out.release?.issues?.size ?: 0
            val now = "now $total issue${if (total == 1) "" else "s"}"
            if (out.added.isEmpty()) return "Release \"$name\": nothing changed, the issues are already in it ($now)."
            return buildString {
                append("Release \"$name\": added ${out.added.joinToString(", ") { "#$it" }} ($now).")
                out.movedFrom.forEach { (from, nums) -> append(" Moved ${nums.joinToString(", ") { "#$it" }} from release \"$from\".") }
            }
        }

        val HELP = """
            |Factory commands (handled by Agern, not sent to Claude):
            |  /factory run <issue>       start a pipeline run for an issue in this project
            |  /factory status            list recent runs in this project
            |  /factory approve [runId]   merge a run that waits for a human (owner only)
            |  /factory cancel <runId>    cancel a run (owner or admin)
            |  /factory releases          list the releases planned in this project
            |  /factory release create <name> [description]   plan a new release
            |  /factory release add <name> <issue>…            put issues into a release (moves them from another)
            |  /factory release remove <issue>…                take issues out of their release
            |  /factory release approve <name>                 merge a release that is ready to release (owner only)
            |  /factory release mode <name> manual|automatic   release it on approval (default) or automatically when green
            |  /factory help              this help
            |To steer a running spec-writer or builder, select it on the run page and type below its transcript,
            |or ask the chat agent to pass a message on (it uses the factory_send_to_agent tool).
        """.trimMargin()
    }
}
