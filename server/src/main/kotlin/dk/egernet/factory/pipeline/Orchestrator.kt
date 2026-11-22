package dk.egernet.factory.pipeline

import dk.egernet.factory.AppJson
import dk.egernet.factory.ApiException
import dk.egernet.factory.ReviewState
import dk.egernet.factory.badRequest
import dk.egernet.factory.Config
import dk.egernet.factory.FindingDto
import dk.egernet.factory.REVIEWER_ROLES
import dk.egernet.factory.SCREENSHOT_ROLES
import dk.egernet.factory.RunStatus
import dk.egernet.factory.agent.AgentIdentity
import dk.egernet.factory.agent.AgentManager
import dk.egernet.factory.agent.EventBus
import dk.egernet.factory.agent.FeedItem
import dk.egernet.factory.agent.Prompts
import dk.egernet.factory.agent.Screenshots
import dk.egernet.factory.agent.StageAgentRequest
import dk.egernet.factory.agent.StageAgentResult
import dk.egernet.factory.agent.UsageLimit
import dk.egernet.factory.agent.Workspaces
import dk.egernet.factory.conflict
import dk.egernet.factory.db.RunRecord
import dk.egernet.factory.db.RunStore
import dk.egernet.factory.db.ScreenshotStore
import dk.egernet.factory.db.SettingsStore
import dk.egernet.factory.db.StageRecord
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.db.UserStore
import dk.egernet.factory.forbidden
import dk.egernet.factory.github.Access
import dk.egernet.factory.github.GhIssue
import dk.egernet.factory.github.GitHubClient
import dk.egernet.factory.issueKindFromLabels
import dk.egernet.factory.notFound
import dk.egernet.factory.unprocessable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Verdict schemas handed to `--json-schema`. */
object Schemas {
    val REVIEW = """{"type":"object","additionalProperties":false,"properties":{""" +
        """"verdict":{"type":"string","enum":["PASS","CHANGES"]},""" +
        """"summary":{"type":"string"},""" +
        """"findings":{"type":"array","items":{"type":"object","additionalProperties":false,"properties":{""" +
        """"severity":{"type":"string","enum":["blocker","major","minor","nit"]},""" +
        """"file":{"type":["string","null"]},"detail":{"type":"string"}},"required":["severity","detail"]}}},""" +
        """"required":["verdict","summary","findings"]}"""
    val APPROVAL = """{"type":"object","additionalProperties":false,"properties":{""" +
        """"verdict":{"type":"string","enum":["APPROVE","ESCALATE"]},"summary":{"type":"string"}},""" +
        """"required":["verdict","summary"]}"""
}

/** A stage (or the run's set-up) failed; carries what the stage agent reported about usage limits and credentials. */
private class StageFailed(
    message: String,
    val usageLimit: UsageLimit? = null,
    val credentialRejected: Boolean = false,
) : RuntimeException(message)

/**
 * How a release run ended, for the release builder (docs/ARCHITECTURE.md §6 "Releases"): [run] as it is now,
 * and whether it failed on the owner's usage limit or a rejected (or missing) Claude credential.
 */
data class RunOutcome(val run: RunRecord, val usageLimit: UsageLimit? = null, val credentialRejected: Boolean = false)

data class Verdict(val verdict: String, val summary: String, val findings: List<FindingDto>)

/**
 * The pipeline as a deterministic state machine:
 * spec-writer → builder → 4 reviewers (parallel) → [CHANGES: builder fix round, max N] → approver → merge | needs_human.
 */
class Orchestrator(
    private val cfg: Config,
    private val runs: RunStore,
    private val users: UserStore,
    private val access: Access,
    private val agents: AgentManager,
    private val workspaces: Workspaces,
    private val settings: SettingsStore,
    private val prompts: Prompts,
    private val bus: EventBus,
    private val scope: CoroutineScope,
    private val preferences: dk.egernet.factory.db.PreferenceStore,
    private val sandboxes: dk.egernet.factory.sandbox.SandboxManager,
    private val screenshots: ScreenshotStore,
    private val sessions: dk.egernet.factory.db.SessionStore,
    private val events: dk.egernet.factory.db.EventStore,
) {
    private val log = LoggerFactory.getLogger(Orchestrator::class.java)
    private val jobs = ConcurrentHashMap<UUID, Job>()
    /** Guards a waiting run's conversation: messages, review starts, the approve checks and the after-turn sync. */
    private val runLocks = ConcurrentHashMap<UUID, Mutex>()
    private fun runLock(runId: UUID) = runLocks.computeIfAbsent(runId) { Mutex() }
    /** The follow-up review round each run's conversation was last told about (in memory; re-sent after a restart). */
    private val toldRound = ConcurrentHashMap<UUID, Int>()
    /** The head for which the uncommitted-changes warning was last given, per run (so it is not repeated every turn). */
    private val dirtyWarned = ConcurrentHashMap<UUID, String>()
    private val org get() = cfg.githubOrg

    fun job(runId: UUID): Job? = jobs[runId]

    /**
     * Called when a release run settles: at the end of its pipeline (not when its coroutine is cancelled), after
     * `approve` and after `cancel`. The receiver must not block or call back into the orchestrator inline.
     */
    @Volatile var onReleaseRunSettled: ((RunOutcome) -> Unit)? = null

    private fun settled(runId: UUID, usageLimit: UsageLimit? = null, credentialRejected: Boolean = false) {
        val run = runs.get(runId) ?: return
        if (run.releaseId == null) return
        try {
            onReleaseRunSettled?.invoke(RunOutcome(run, usageLimit, credentialRejected))
        } catch (e: Exception) {
            log.warn("Release callback for run {} failed: {}", runId, e.message)
        }
    }

    fun publish(runId: UUID) {
        runs.touch(runId)
        runs.get(runId)?.let { bus.emitFeed(FeedItem.Run(runs.toDto(it))) }
    }

    // ------------------------------------------------------------ commands (ownership enforced here)

    /** Starts a run as [owner]. The owner's credentials are the only ones the run will ever use. */
    suspend fun start(owner: UserRecord, repo: String, issueNumber: Int, startedBySession: UUID?): RunRecord =
        create(owner, repo, issueNumber, startedBySession, releaseId = null, baseBranch = null)

    /**
     * Starts a release run as the release's [owner]: it branches from and merges into [baseBranch] (`release/<name>`)
     * and does not close its issue (docs/ARCHITECTURE.md §6 "Releases").
     */
    suspend fun startReleaseRun(owner: UserRecord, repo: String, issueNumber: Int, releaseId: UUID, baseBranch: String): RunRecord =
        create(owner, repo, issueNumber, null, releaseId, baseBranch)

    private suspend fun create(
        owner: UserRecord, repo: String, issueNumber: Int, startedBySession: UUID?, releaseId: UUID?, baseBranch: String?,
    ): RunRecord {
        if (owner.claudeCredEnc == null) throw unprocessable("Register a Claude credential in Settings first", "claude_credential_required")
        access.requireVisible(owner, repo)
        val client = access.requireClient(owner)
        val issue = withContext(Dispatchers.IO) { client.getIssue(org, repo, issueNumber) } ?: throw notFound("Issue #$issueNumber not found")
        // The starting user's own choices (else the admin defaults), fixed for this run: max rounds now, models per stage.
        val eff = preferences.effective(owner.id)
        val run = runs.createIfNoActive(
            repo, issueNumber, issue.title, issueKindFromLabels(issue.labels.map { it.name }), eff.maxFixRounds, owner.id, startedBySession,
            releaseId, baseBranch,
        ) ?: throw conflict("Issue #$issueNumber already has an active run", "active_run_exists")
        for (role in listOf("spec-writer", "builder") + REVIEWER_ROLES + "approver") runs.createStage(run.id, role, 0, eff.model(role))
        publish(run.id)
        val job = scope.launch { execute(run.id) }
        jobs[run.id] = job
        job.invokeOnCompletion { jobs.remove(run.id) }
        return runs.get(run.id)!!
    }

    /**
     * Owner only, only in needs_human: squash-merge the PR and close the issue. Refused while the run assistant is
     * mid-turn, while reviews run, and until the branch head has been reviewed (docs/ARCHITECTURE.md §6 "Talking to an
     * agent at needs human").
     */
    suspend fun approve(user: UserRecord, runId: UUID): RunRecord {
        val run = runs.get(runId) ?: throw notFound("Run not found")
        if (run.ownerId != user.id) throw forbidden("Only the owner of this run can approve it")
        if (run.status != RunStatus.NEEDS_HUMAN) throw conflict("Run is ${run.status}, not needs_human")
        if (run.prNumber == null) throw conflict("Run has no pull request to merge")
        val assistant = runLock(runId).withLock {
            val now = runs.get(runId) ?: throw notFound("Run not found")
            if (now.status != RunStatus.NEEDS_HUMAN) throw conflict("Run is ${now.status}, not needs_human")
            now.assistantSessionId?.let { if (agents.isAssistantBusy(it)) throw agentBusy() }
            if (reviewRunning(runId)) throw reviewsRunning()
            val sync = syncLocked(runId)
            val after = runs.get(runId)!!
            if (runs.reviewState(after, runs.stages(runId)) != ReviewState.CURRENT) {
                publish(runId)
                val why = when (sync) {
                    is Sync.PushFailed -> " Pushing it to `${after.branch}` failed: ${sync.reason}."
                    is Sync.ReviewStarted -> " A review of it has started."
                    else -> ""
                }
                throw conflict("The latest change on the branch has not been reviewed yet.$why", "changes_not_reviewed")
            }
            after.assistantSessionId?.let { agents.endRunAssistant(it, null) }
            runs.setStatus(runId, RunStatus.APPROVING, clearError = true)
            after.assistantSessionId
        }
        publish(runId)
        withContext(Dispatchers.IO) { merge(runs.get(runId)!!, user, approvedBy = user.username) }
        if (assistant != null && runs.get(runId)?.status == RunStatus.MERGED) {
            agents.systemEvent(assistant, "info", "The run was approved by ${user.username}; this conversation is closed.")
        }
        publish(runId)
        settled(runId)
        return runs.get(runId)!!
    }

    /** Owner or admin. */
    suspend fun cancel(user: UserRecord, runId: UUID): RunRecord {
        val run = runs.get(runId) ?: throw notFound("Run not found")
        if (run.ownerId != user.id && !user.isAdmin) throw forbidden("Only the owner or an admin can cancel this run")
        if (run.status in RunStatus.TERMINAL) throw conflict("Run is already ${run.status}")
        // Under the run lock: a conversation's after-turn sync (e.g. of the assistant stopped below) cannot start a
        // new follow-up review round before the run is cancelled.
        runLock(runId).withLock {
            // Also a follow-up review round of a waiting run (its coroutine is in jobs too).
            jobs[runId]?.let { it.cancel(); it.join() }
            sandboxes.release(runId)
            val after = runs.get(runId)!!
            if (after.status in RunStatus.TERMINAL) return after
            // The run assistant works in the worktree: its process goes first.
            after.assistantSessionId?.let { agents.endRunAssistant(it, "The run was cancelled by ${user.username}; this conversation is closed.") }
            withContext(Dispatchers.IO) { cleanupWorktree(runId, merged = false) }
            runs.setStatus(runId, RunStatus.CANCELLED, "Cancelled by ${user.username}")
            runs.closeOpenStages(runId)
        }
        val owner = users.byId(run.ownerId)!!
        withContext(Dispatchers.IO) {
            bestEffort { gh(owner).removeLabel(org, run.repo, run.issueNumber, "needs-human") }
            commentEnded(runId, "**Agern:** run `${run.id}` was cancelled by @${user.username}.")
        }
        publish(runId)
        settled(runId)
        return runs.get(runId)!!
    }

    // ------------------------------------------------------------ talking to an agent at needs_human
    // docs/ARCHITECTURE.md §6 "Talking to an agent at needs human". The run never leaves needs_human here.

    private sealed interface Sync {
        object Nothing : Sync
        object ReviewStarted : Sync
        class PushFailed(val reason: String) : Sync
    }

    private fun agentBusy() = conflict("The run assistant is still working; wait for its answer or interrupt it", "agent_busy")
    private fun reviewsRunning() =
        conflict("The reviewers are checking the latest change; continue when they are done", "reviews_running")

    /** A reviewer stage of the run is pending or running (a follow-up round, at needs_human). */
    private fun reviewRunning(runId: UUID) =
        runs.stages(runId).any { it.role in REVIEWER_ROLES && it.status in setOf("pending", "running") }

    /** The run for its owner's conversation: 403 for anyone else (admins included), 409 unless it waits at needs_human. */
    private fun waitingRun(user: UserRecord, runId: UUID, what: String): RunRecord {
        val run = runs.get(runId) ?: throw notFound("Run not found")
        if (run.ownerId != user.id) throw forbidden("Only the owner of this run can $what")
        if (run.status != RunStatus.NEEDS_HUMAN) {
            throw conflict("Run is ${run.status}; its agent can only be talked to while it waits at needs_human", "run_not_waiting")
        }
        return run
    }

    /**
     * The owner's message to the run's assistant. Never changes the run's status, error, stages or pull request; a
     * commit the agent makes is pushed and reviewed after its turn (see [afterAssistantTurn]).
     */
    suspend fun talk(user: UserRecord, runId: UUID, text: String): RunRecord {
        runLock(runId).withLock {
            val run = waitingRun(user, runId, "talk to its agent")
            val sid = run.assistantSessionId
            if (sid != null && agents.isAssistantBusy(sid)) throw agentBusy()
            if (reviewRunning(runId)) throw reviewsRunning()
            if (text.isBlank()) throw badRequest("text must not be empty")
            if (text.length > 100_000) throw badRequest("text is too long")
            agents.requireLaunchable(user, sid)
            val worktree = workspaces.runDir(user.username, run.repo, run.id)
            if (!java.nio.file.Files.isDirectory(worktree)) throw conflict("The run's worktree no longer exists", "worktree_missing")
            when (val sync = syncLocked(runId)) {
                is Sync.ReviewStarted -> {
                    publish(runId)
                    throw conflict("The branch has commits that were not reviewed; the reviewers are checking them now", "reviews_running")
                }
                is Sync.PushFailed -> {
                    publish(runId)
                    throw ApiException(io.ktor.http.HttpStatusCode.BadGateway, "github_error",
                        "Could not push the change to `${run.branch}`: ${sync.reason}")
                }
                else -> {}
            }
            val session = sid?.let { sessions.get(it) } ?: run {
                val model = preferences.effective(user.id).model("builder")
                agents.createRunAssistant(user, run.repo, run.id, run.issueNumber, model).also { runs.setAssistantSession(run.id, it.id) }
            }
            agents.sendToRunAssistant(user, session, text)
        }
        publish(runId)
        return runs.get(runId)!!
    }

    /** "Review changes": pushes the branch head and starts a follow-up review round when it has not been reviewed. */
    suspend fun review(user: UserRecord, runId: UUID): RunRecord {
        runLock(runId).withLock {
            val run = waitingRun(user, runId, "start its reviews")
            run.assistantSessionId?.let { if (agents.isAssistantBusy(it)) throw agentBusy() }
            if (reviewRunning(runId)) throw reviewsRunning()
            when (val sync = syncLocked(runId)) {
                is Sync.ReviewStarted -> {}
                is Sync.PushFailed -> {
                    publish(runId)
                    throw ApiException(io.ktor.http.HttpStatusCode.BadGateway, "github_error",
                        "Could not push the change to `${run.branch}`: ${sync.reason}")
                }
                else -> throw conflict("The branch head has already been reviewed", "nothing_to_review")
            }
        }
        publish(runId)
        return runs.get(runId)!!
    }

    /**
     * The assistant's turn ended (result, process exit or failed delivery): push and review what it committed, and warn
     * about uncommitted changes. Does nothing when it is mid-turn again or the run no longer waits.
     */
    suspend fun afterAssistantTurn(runId: UUID) {
        runLock(runId).withLock {
            val run = runs.get(runId) ?: return
            if (run.status != RunStatus.NEEDS_HUMAN) return
            val sid = run.assistantSessionId ?: return
            if (agents.isAssistantBusy(sid)) return
            val sync = syncLocked(runId)
            if (sync is Sync.PushFailed) {
                agents.systemEvent(sid, "error", "Could not push the change to `${run.branch}`: ${sync.reason}. Use “Review changes” to try again.")
            }
            val owner = users.byId(run.ownerId) ?: return
            val worktree = workspaces.runDir(owner.username, run.repo, run.id)
            val home = workspaces.home(owner.username)
            val (dirty, head) = withContext(Dispatchers.IO) {
                if (!java.nio.file.Files.isDirectory(worktree)) false to null
                else workspaces.git.hasUncommittedChanges(worktree, home) to workspaces.git.headSha(worktree, home)
            }
            if (!dirty) {
                dirtyWarned.remove(runId)
            } else if (dirtyWarned.put(runId, head ?: "") != (head ?: "")) {
                agents.systemEvent(sid, "warn", "The worktree has uncommitted changes. They are not part of the pull request; ask the agent to commit them.")
            }
        }
        publish(runId)
    }

    /**
     * Reads the worktree's head; a head that has not been reviewed is pushed and a follow-up review round starts. The
     * first use on a run from before head tracking records the current head as reviewed. Call under the run lock.
     */
    private suspend fun syncLocked(runId: UUID): Sync {
        val run = runs.get(runId) ?: return Sync.Nothing
        if (run.status != RunStatus.NEEDS_HUMAN || reviewRunning(runId)) return Sync.Nothing
        val owner = users.byId(run.ownerId) ?: return Sync.Nothing
        val home = workspaces.home(owner.username)
        val worktree = workspaces.runDir(owner.username, run.repo, run.id)
        val head = withContext(Dispatchers.IO) {
            if (java.nio.file.Files.isDirectory(worktree)) workspaces.git.headSha(worktree, home) else null
        }
        if (run.headSha == null) {
            if (head != null) runs.setHead(runId, head, reviewed = true)
            return Sync.Nothing
        }
        val newHead = head ?: run.headSha
        if (newHead == run.reviewedSha) {
            if (newHead != run.headSha) runs.setHead(runId, newHead)
            return Sync.Nothing
        }
        val branch = run.branch
        val pushError = if (branch == null) "the run has no branch" else try {
            val id = identity(owner)
            withContext(Dispatchers.IO) { workspaces.git.push(id, worktree, branch, home) }
            null
        } catch (e: Exception) {
            log.warn("Pushing run {} after a conversation turn failed: {}", runId, e.message)
            e.message ?: e.javaClass.simpleName
        }
        if (pushError != null) {
            if (newHead != run.headSha) runs.setHead(runId, newHead)
            return Sync.PushFailed(pushError)
        }
        // The stages first, then the head: the run never shows as unreviewed while its review is being started.
        startFollowUpReview(run, owner, newHead)
        if (newHead != run.headSha) runs.setHead(runId, newHead)
        return Sync.ReviewStarted
    }

    /** Four reviewer stages in a new round (owner's effective models) and the coroutine that runs them. */
    private fun startFollowUpReview(run: RunRecord, owner: UserRecord, head: String) {
        val round = (runs.stages(run.id).maxOfOrNull { it.round } ?: 0) + 1
        val eff = preferences.effective(owner.id)
        REVIEWER_ROLES.forEach { runs.createStage(run.id, it, round, eff.model(it)) }
        val job = scope.launch { followUpReview(run.id, round, head) }
        jobs[run.id] = job
        job.invokeOnCompletion { jobs.remove(run.id, job) }
    }

    /**
     * A follow-up review round of a waiting run: the reviewers as in the pipeline. When all four return a verdict (PASS
     * or CHANGES), [head] counts as reviewed. Never changes the run's status or error, never runs the approver, never
     * merges and never calls [settled]; whatever it leaves open is closed at the end.
     */
    private suspend fun followUpReview(runId: UUID, round: Int, head: String) {
        try {
            val run = runs.get(runId) ?: return
            val owner = owner(run)
            val client = access.client(owner)
            val ctx = conversationContext(run, owner)
            val stages = runs.stages(runId)
            val label = roundLabel(stages, round)
            val summary = run.assistantSessionId?.let { events.lastResultText(it) }?.trim().orEmpty()
            val reviews = reviewRound(
                runId, round, owner, ctx, specText(stages), summary,
                "$label: the run owner asked for a change while the run waits at needs human",
            )
            if (reviews != null && runs.get(runId)?.status == RunStatus.NEEDS_HUMAN) {
                runs.setReviewed(runId, head)
                publish(runId)
                if (client != null) withContext(Dispatchers.IO) { bestEffort { screenshotNote(runs.get(runId)!!, client, round) } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Follow-up review of run {} failed: {}", runId, e.message)
        } finally {
            withContext(NonCancellable) {
                runs.stages(runId).filter { it.round == round && it.status in setOf("pending", "running") }.forEach {
                    runs.finishStage(it.id, if (it.status == "pending") "skipped" else "failed", null, it.summary, it.findings)
                }
                publish(runId)
            }
        }
    }

    /** The spec as the spec-writer wrote it (its session's last result), or its stage summary. */
    private fun specText(stages: List<StageRecord>): String {
        val st = stages.lastOrNull { it.role == "spec-writer" } ?: return "(no spec)"
        return st.sessionId?.let { events.lastResultText(it) }?.trim()?.takeIf { it.isNotEmpty() } ?: st.summary ?: "(no spec)"
    }

    /** A [RunContext] rebuilt from the database and GitHub for a waiting run (no sandbox: the assistant has none). */
    private suspend fun conversationContext(run: RunRecord, owner: UserRecord): RunContext = withContext(Dispatchers.IO) {
        val client = access.client(owner)
        val repoInfo = client?.let { runCatching { it.getRepo(org, run.repo) }.getOrNull() }
        val issue = client?.let { runCatching { it.getIssue(org, run.repo, run.issueNumber) }.getOrNull() }
        val comments = if (issue == null) emptyList() else runCatching { client.listComments(org, run.repo, run.issueNumber) }.getOrDefault(emptyList())
        val defaultBranch = repoInfo?.defaultBranch ?: "main"
        val target = run.baseBranch ?: defaultBranch
        val releaseName = run.baseBranch?.takeIf { run.releaseId != null }?.removePrefix("release/")
        val text = if (issue != null) issueText(issue, comments) else "Issue #${run.issueNumber}: ${run.issueTitle}\n"
        RunContext(
            run.id, run.repo, issue ?: GhIssue(run.issueNumber, run.issueTitle, "open", createdAt = "", updatedAt = "", htmlUrl = ""),
            text, run.branch ?: "", workspaces.git.baseRef(target), target, workspaces.runDir(owner.username, run.repo, run.id),
            releaseName, defaultBranch,
        )
    }

    /** The highest round in which all four reviewers returned a verdict, or null. */
    private fun latestReviewedRound(stages: List<StageRecord>): Int? =
        stages.filter { it.role in REVIEWER_ROLES }.groupBy { it.round }
            .filterValues { list -> REVIEWER_ROLES.all { r -> list.any { it.role == r && it.status == "done" && it.verdict != null } } }
            .keys.maxOrNull()

    private fun reviewsText(stages: List<StageRecord>, round: Int): String = buildString {
        for (role in REVIEWER_ROLES) {
            val st = stages.lastOrNull { it.role == role && it.round == round } ?: continue
            append("### $role — ${st.verdict}\n${st.summary.orEmpty()}\n")
            st.findings.forEach { f -> append("- [${f.severity}] ${f.file ?: "(general)"}: ${f.detail}\n") }
            append('\n')
        }
    }

    /**
     * What the run assistant is told before the owner's message (wired into [AgentManager.assistantContext]): with
     * [full] the whole `<run_context>`; otherwise the `<review_results>` of a follow-up round it was not told about yet,
     * or an empty string. Issue, comments and findings are delimited data.
     */
    suspend fun assistantContext(runId: UUID, full: Boolean): String {
        val run = runs.get(runId) ?: return ""
        val stages = runs.stages(runId)
        val latest = latestReviewedRound(stages)
        if (!full) {
            if (latest == null || latest <= (toldRound[runId] ?: -1)) return ""
            if (stages.any { it.role == "builder" && it.round == latest }) return ""
            toldRound[runId] = latest
            return "<review_results round=\"${roundLabel(stages, latest)}\">\n${reviewsText(stages, latest)}</review_results>\n\n"
        }
        toldRound[runId] = latest ?: -1
        val owner = users.byId(run.ownerId) ?: return ""
        val ctx = conversationContext(run, owner)
        val builder = stages.lastOrNull { it.role == "builder" && it.sessionId != null }
        val builderSummary = builder?.sessionId?.let { events.lastResultText(it) }?.trim()?.takeIf { it.isNotEmpty() } ?: builder?.summary ?: "(none)"
        val approver = stages.lastOrNull { it.role == "approver" }
        return buildString {
            append("<run_context>\n")
            append("Run owner: ${owner.username} · run ${run.id} · repository $org/${run.repo} · issue #${run.issueNumber}\n")
            append("Why it needs a human: ${run.error ?: "(no reason recorded)"}\n")
            append("Branch `${ctx.branch}`, based on `${ctx.baseRef}`; pull request ${run.prUrl ?: "(none)"}; it merges into `${ctx.targetBranch}`.\n")
            append(ctx.releaseNote)
            append("Inspect the change with `git diff ${ctx.baseRef}...HEAD` and `git log ${ctx.baseRef}..HEAD`.\n\n")
            append(ctx.issueText)
            append("\n<spec>\n").append(specText(stages)).append("\n</spec>\n\n")
            append("<builder_summary>\n").append(builderSummary).append("\n</builder_summary>\n\n")
            if (latest != null) append("<reviews round=\"${roundLabel(stages, latest)}\">\n").append(reviewsText(stages, latest)).append("</reviews>\n\n")
            else append("<reviews>\n(no complete review round)\n</reviews>\n\n")
            append("<approver>\n")
            if (approver?.verdict != null) append("${approver.verdict}\n${approver.summary.orEmpty()}\n") else append("did not run\n")
            append("</approver>\n</run_context>\n\n")
        }
    }

    // ------------------------------------------------------------ the state machine

    private suspend fun execute(runId: UUID) {
        try {
            run(runId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Run {} failed: {}", runId, e.message)
            val reason = e.message ?: e.javaClass.simpleName
            // Clean up first, so a run shown as ended has left nothing behind: the sandbox (it bind-mounts the worktree),
            // then the worktree.
            withContext(NonCancellable) { sandboxes.release(runId) }
            withContext(Dispatchers.IO) { cleanupWorktree(runId, merged = false) }
            fail(runId, reason)
            withContext(Dispatchers.IO) { commentEnded(runId, "**Agern:** run `$runId` failed: $reason") }
            val sf = e as? StageFailed
            settled(runId, sf?.usageLimit, sf?.credentialRejected == true)
            return
        } finally {
            // Safety net; normally released already (end of the build/review loop, failure, cancel) — and always before
            // settled(), so the next run of a release finds the slot free.
            withContext(NonCancellable) { sandboxes.release(runId) }
        }
        settled(runId)
    }

    private fun fail(runId: UUID, reason: String) {
        runs.setStatus(runId, RunStatus.FAILED, reason)
        runs.closeOpenStages(runId)
        publish(runId)
    }

    /**
     * A run ended failed or cancelled: comment on the issue, naming the pull request it leaves behind. Its pushed branch
     * and open pull request stay on GitHub untouched — they may hold useful commits — and a new run never reuses them
     * (docs/ARCHITECTURE.md §6 "Worktrees and branches"). Blocking.
     */
    private fun commentEnded(runId: UUID, headline: String) {
        val run = runs.get(runId) ?: return
        val owner = users.byId(run.ownerId) ?: return
        val left = if (run.prUrl == null) "" else "\n\nIts pull request ${run.prUrl} (branch `${run.branch}`) is left open as it is; " +
            "a new run of this issue starts on a new branch with a new pull request."
        bestEffort { gh(owner).comment(org, run.repo, run.issueNumber, headline + left) }
    }

    /** Live = has a coroutine in this server or an active status (a `needs_human` run still owns its worktree). */
    private fun isLive(runId: UUID): Boolean = jobs.containsKey(runId) || runs.get(runId)?.status in RunStatus.ACTIVE

    /**
     * The worktree reapers' test: a run that is not live keeps nothing — and its sandbox (normally long gone) is removed
     * here, before the reaper deletes the worktree the sandbox would bind-mount.
     */
    private fun keepWorktree(runId: UUID): Boolean = isLive(runId).also { live -> if (!live) sandboxes.releaseNow(runId) }

    /** At server start: removes the worktrees of runs that have ended (and prunes stale worktree entries). Blocking. */
    fun reapWorktrees(): List<UUID> = workspaces.reapAllRunWorktrees(::keepWorktree)

    private fun owner(run: RunRecord) = users.byId(run.ownerId) ?: error("Run owner no longer exists")
    private fun gh(owner: UserRecord): GitHubClient =
        access.client(owner) ?: throw StageFailed("The run owner has no GitHub token")

    private fun identity(owner: UserRecord) = AgentIdentity(
        owner.username, owner.displayName, owner.githubLogin, access.githubToken(owner),
        access.claudeCredential(owner) ?: throw StageFailed("The run owner has no Claude credential", credentialRejected = true),
    )

    private fun setStatus(runId: UUID, status: String) {
        runs.setStatus(runId, status)
        publish(runId)
    }

    private suspend fun run(runId: UUID) {
        var run = runs.get(runId)!!
        val owner = owner(run)
        val id = identity(owner)
        val client = gh(owner)
        val maxRounds = run.maxRounds

        // Workspace: owner's clone + a worktree on the run branch.
        val repoInfo = withContext(Dispatchers.IO) { client.getRepo(org, run.repo) } ?: throw StageFailed("Repository not accessible")
        val issue = withContext(Dispatchers.IO) { client.getIssue(org, run.repo, run.issueNumber) } ?: throw StageFailed("Issue not found")
        val comments = withContext(Dispatchers.IO) { runCatching { client.listComments(org, run.repo, run.issueNumber) }.getOrDefault(emptyList()) }
        // A release run starts from and merges into its release branch; a single run uses the default branch.
        val target = run.baseBranch ?: repoInfo.defaultBranch
        val releaseName = run.baseBranch?.takeIf { run.releaseId != null }?.removePrefix("release/")
        val branch = runBranch(run.issueNumber, issue.title, releaseName, run.id)
        val worktree = workspaces.runDir(owner.username, run.repo, run.id)
        val baseRef = withContext(Dispatchers.IO) {
            val main = workspaces.ensureMain(id, run.repo)
            workspaces.withRepoLock(owner.username, run.repo) {
                // Leftovers of runs that have ended (e.g. after a crash) never block a new run; live runs keep theirs.
                workspaces.reapRunWorktrees(owner.username, run.repo) { it != run.id && keepWorktree(it) }
                workspaces.git.createWorktree(id, main, worktree, branch, target, workspaces.home(owner.username))
            }
        }
        runs.setBranch(run.id, branch)
        // The sandbox decision is made once, from the base commit (a run cannot approve its own change to the file). A
        // release run reads it from the default branch, as fetched with the clone, not from the release branch it starts
        // from: earlier runs of the release merge into that branch without a human, and approvals are of the default
        // branch's file (docs/ARCHITECTURE.md §5 "The run's decision"). baseRef is `origin/<target>` (or `<target>` for
        // the fake GitHub's local clones), so the same form names the default branch.
        val definitionRef = if (run.baseBranch == null || target == repoInfo.defaultBranch) baseRef
            else baseRef.removeSuffix(target) + repoInfo.defaultBranch
        val sandbox = sandboxes.prepare(run.id, run.repo, worktree, definitionRef, workspaces.home(owner.username))
        publish(run.id)
        val ctx = RunContext(
            run.id, run.repo, issue, issueText(issue, comments), branch, baseRef, target, worktree, releaseName, repoInfo.defaultBranch, sandbox,
        )

        // 1. Spec
        setStatus(run.id, RunStatus.SPEC)
        val specRes = stage(run.id, "spec-writer", 0, owner, ctx, specPrompt(ctx),
            disallowed = READ_ONLY_TOOLS)
        val spec = specRes.resultText?.trim().takeUnless { it.isNullOrEmpty() } ?: throw StageFailed("spec-writer produced no spec")
        finishStage(run.id, "spec-writer", 0, null, firstParagraph(spec), emptyList())
        withContext(Dispatchers.IO) {
            client.comment(org, run.repo, run.issueNumber, "**Agern: spec ready** (run `${run.id}`)\n\n$spec")
        }

        // 2. Build / review loop
        var round = 0
        var builderClaudeSession: String? = null
        var builderSessionId: UUID? = null
        var findingsForBuilder: Map<String, Verdict> = emptyMap()
        var lastReviews: Map<String, Verdict>
        while (true) {
            runs.setRound(run.id, round)
            setStatus(run.id, RunStatus.BUILDING)
            if (round > 0) {
                val eff = preferences.effective(owner.id)
                runs.createStage(run.id, "builder", round, eff.model("builder"))
                REVIEWER_ROLES.forEach { runs.createStage(run.id, it, round, eff.model(it)) }
                publish(run.id)
            }
            val prompt = if (round == 0) buildPrompt(ctx, spec) else fixPrompt(ctx, round, maxRounds, findingsForBuilder)
            val b = stage(run.id, "builder", round, owner, ctx, prompt,
                resume = builderClaudeSession, parent = builderSessionId,
                extraTools = if (sandboxes.enabled) dk.egernet.factory.sandbox.SandboxManager.TOOLS else emptySet(),
                notes = ctx.sandbox?.takeIf { sandboxes.enabled }?.let { listOf((if (it.state == "approved") "info" else "warn") to "Sandbox: ${it.reason}") }
                    ?: emptyList())
            builderClaudeSession = b.claudeSessionId ?: builderClaudeSession
            builderSessionId = b.session.id
            val builderSummary = b.resultText?.trim() ?: ""
            val ahead = withContext(Dispatchers.IO) { workspaces.git.commitsAhead(worktree, baseRef, workspaces.home(owner.username)) }
            if (ahead == 0) {
                // The builder's own words say why (e.g. the issue is blocked on another one); show them with the failure.
                val why = firstParagraph(builderSummary).takeIf { it.isNotEmpty() }
                finishStage(run.id, "builder", round, null, why?.let { "No commits on the branch. $it" } ?: "No commits on the branch", emptyList(), failed = true)
                throw StageFailed("The builder made no commits on $branch" + (why?.let { ". The builder said: $it" } ?: ""))
            }
            finishStage(run.id, "builder", round, null, firstParagraph(builderSummary), emptyList())

            val head = withContext(Dispatchers.IO) {
                workspaces.git.push(id, worktree, branch, workspaces.home(owner.username))
                workspaces.git.headSha(worktree, workspaces.home(owner.username))
            }
            runs.setHead(run.id, head)
            withContext(Dispatchers.IO) {
                run = runs.get(run.id)!!
                if (run.prNumber == null) {
                    // A release run's pull request must not close the issue: that happens when the release is merged.
                    val lead = if (releaseName != null) "Issue #${issue.number} · release `$releaseName`" else "Closes #${issue.number}"
                    val earlier = runs.earlierUnmergedPulls(run.repo, run.issueNumber, run.id)
                        .takeIf { it.isNotEmpty() }
                        ?.let { prs -> "Earlier runs of this issue left " + prs.joinToString(", ") { "#$it" } + " open; close them if this one supersedes them.\n\n" }
                        .orEmpty()
                    val pr = client.findOpenPull(org, run.repo, branch) ?: client.createPull(
                        org, run.repo, branch, target, "${issue.title} (#${issue.number})",
                        "$lead\n\nBuilt by Agern run `${run.id}` for @${owner.githubLogin ?: owner.username}.\n\n" + earlier +
                            "<details><summary>Spec</summary>\n\n$spec\n\n</details>",
                    )
                    runs.setPr(run.id, pr.number, pr.htmlUrl)
                    bestEffort { client.comment(org, run.repo, run.issueNumber, "**Agern:** pull request ${pr.htmlUrl} is open; reviews are running.") }
                }
            }
            publish(run.id)

            // Reviews in parallel
            setStatus(run.id, RunStatus.REVIEWING)
            lastReviews = reviewRound(run.id, round, owner, ctx, spec, builderSummary, roundNote = null)
                ?: throw StageFailed("A reviewer did not return a valid verdict")
            runs.setReviewed(run.id, head)
            withContext(Dispatchers.IO) { bestEffort { screenshotNote(runs.get(run.id)!!, client, round) } }
            val changes = lastReviews.filterValues { it.verdict == "CHANGES" }
            if (changes.isEmpty()) break
            if (round >= maxRounds) {
                sandboxes.release(run.id)
                skipStage(run.id, "approver")
                needsHuman(
                    runs.get(run.id)!!, owner,
                    "Reviewers still request changes after $round fix round(s): " + changes.keys.joinToString(", ") + ".",
                )
                return
            }
            findingsForBuilder = changes
            round++
        }

        // Only the builder uses the sandbox: it is removed as soon as no fix round can follow.
        sandboxes.release(run.id)

        // 3. Approval
        setStatus(run.id, RunStatus.APPROVING)
        runs.setStageRound(run.id, "approver", round)
        run = runs.get(run.id)!!
        val a = stage(run.id, "approver", round, owner, ctx, approvalPrompt(ctx, spec, lastReviews, round, maxRounds, run.prUrl), schema = Schemas.APPROVAL, disallowed = READ_ONLY_TOOLS)
        val v = parseVerdict(a, setOf("APPROVE", "ESCALATE"))
        if (v == null) {
            finishStage(run.id, "approver", round, null, "No valid verdict in the agent's output", emptyList(), failed = true)
            throw StageFailed("The approver did not return a valid verdict")
        }
        finishStage(run.id, "approver", round, v.verdict, v.summary, emptyList())
        if (v.verdict == "APPROVE") {
            withContext(Dispatchers.IO) { merge(runs.get(run.id)!!, owner, approvedBy = null) }
            publish(run.id)
        } else {
            needsHuman(runs.get(run.id)!!, owner, "The approver escalated: ${v.summary}")
        }
    }

    /**
     * The four reviewers of [round] in parallel; null when one of them returned no valid verdict (its stage is failed).
     * A reviewer whose agent fails throws [StageFailed]. [roundNote] describes a follow-up round in the prompt.
     */
    private suspend fun reviewRound(
        runId: UUID, round: Int, owner: UserRecord, ctx: RunContext, spec: String, builderSummary: String, roundNote: String?,
    ): Map<String, Verdict>? = coroutineScope {
        REVIEWER_ROLES.map { role ->
            async {
                val r = stage(runId, role, round, owner, ctx, reviewPrompt(ctx, role, round, spec, builderSummary, roundNote), schema = Schemas.REVIEW,
                    disallowed = READ_ONLY_TOOLS, extraTools = if (role in SCREENSHOT_ROLES) Screenshots.TOOLS else emptySet())
                val v = parseVerdict(r, setOf("PASS", "CHANGES"))
                if (v == null) {
                    finishStage(runId, role, round, null, "No valid verdict in the agent's output", emptyList(), failed = true)
                    null
                } else {
                    finishStage(runId, role, round, v.verdict, v.summary, v.findings)
                    role to v
                }
            }
        }.awaitAll()
    }.let { list -> if (list.any { it == null }) null else list.filterNotNull().toMap() }

    /**
     * After a review round: one comment on the pull request listing the screenshots the reviewers of [round] attached,
     * pointing at the run page (docs/ARCHITECTURE.md §6 "Screenshots"). Nothing for a round without screenshots; the
     * images themselves never go to GitHub. Blocking.
     */
    private fun screenshotNote(run: RunRecord, client: GitHubClient, round: Int) {
        val pr = run.prNumber ?: return
        screenshotNoteText(cfg, run.repo, run.id, round, runs.stages(run.id), screenshots.forRun(run.id))
            ?.let { client.comment(org, run.repo, pr, it) }
    }

    /** [targetBranch]: where the pull request merges; [releaseName] is set for a release run. */
    private data class RunContext(
        val runId: UUID, val repo: String, val issue: GhIssue, val issueText: String, val branch: String,
        val baseRef: String, val targetBranch: String, val worktree: Path,
        val releaseName: String? = null, val defaultBranch: String = targetBranch,
        /** The run's sandbox decision; null when sandboxes are switched off. */
        val sandbox: dk.egernet.factory.RunSandboxDto? = null,
    ) {
        /** For release runs: the sentence that names the release branch as the merge target. */
        val releaseNote: String
            get() = if (releaseName == null) "" else
                "This issue is part of release `$releaseName`: the pull request is squash-merged into the release branch " +
                    "`$targetBranch` (not into `$defaultBranch`), and the issue stays open until the release is merged.\n"
    }

    /** Runs one stage's agent and marks the stage running/failed. The caller records the outcome on success. */
    private suspend fun stage(
        runId: UUID, role: String, round: Int, owner: UserRecord, ctx: RunContext, prompt: String,
        schema: String? = null, disallowed: List<String> = emptyList(),
        resume: String? = null, parent: UUID? = null,
        extraTools: Set<String> = emptySet(), notes: List<Pair<String, String>> = emptyList(),
    ): StageAgentResult {
        val st = stageRecord(runId, role, round)
        val s = settings.get()
        val res = agents.runStageAgent(
            StageAgentRequest(
                // The model recorded on the stage when it was created, so Stage.model is what actually ran.
                owner = owner, repo = ctx.repo, role = role, model = st.model, runId = runId,
                issueNumber = ctx.issue.number, parentSessionId = parent, workDir = ctx.worktree,
                systemPrompt = prompts.system(role, ctx.repo, s.agentsMayInstallSoftware), prompt = prompt,
                jsonSchema = schema, disallowedTools = disallowed, resumeClaudeSessionId = resume,
                title = "$role · #${ctx.issue.number}" + if (round > 0) " · round $round" else "",
                extraTools = extraTools, notes = notes,
            ),
        ) { session ->
            runs.startStage(st.id, session.id, session.model)
            publish(runId)
        }
        if (!res.ok) {
            runs.finishStage(st.id, "failed", null, res.error, emptyList())
            publish(runId)
            throw StageFailed("$role failed: ${res.error}", res.usageLimit, res.credentialRejected)
        }
        return res
    }

    private fun stageRecord(runId: UUID, role: String, round: Int): StageRecord =
        runs.stages(runId).lastOrNull { it.role == role && it.round == round }
            ?: runs.createStage(runId, role, round, preferences.effective(runs.get(runId)!!.ownerId).model(role))

    private fun finishStage(runId: UUID, role: String, round: Int, verdict: String?, summary: String?, findings: List<FindingDto>, failed: Boolean = false) {
        val st = stageRecord(runId, role, round)
        runs.finishStage(st.id, if (failed) "failed" else "done", verdict, summary, findings)
        publish(runId)
    }

    private fun skipStage(runId: UUID, role: String) {
        runs.stages(runId).filter { it.role == role && it.status == "pending" }.forEach {
            runs.finishStage(it.id, "skipped", null, null, emptyList())
        }
        publish(runId)
    }

    private fun needsHuman(run: RunRecord, owner: UserRecord, reason: String) {
        val client = gh(owner)
        bestEffort { client.addLabels(org, run.repo, run.issueNumber, listOf("needs-human")) }
        bestEffort {
            client.comment(
                org, run.repo, run.issueNumber,
                "**Agern: needs a human.** $reason\n\nPull request: ${run.prUrl ?: "(none)"}\n\n" +
                    "The run owner (@${owner.githubLogin ?: owner.username}) can merge it with `/factory approve ${run.id}` in the Agern chat or the Approve button on the run page.",
            )
        }
        runs.setStatus(run.id, RunStatus.NEEDS_HUMAN, reason)
        publish(run.id)
    }

    /**
     * Squash-merges the PR and closes the issue; a release run's issue stays open until the release is merged.
     * On failure the run goes (back) to needs_human.
     */
    private fun merge(run: RunRecord, owner: UserRecord, approvedBy: String?) {
        val client = gh(owner)
        val pr = run.prNumber ?: run { needsHuman(run, owner, "There is no pull request to merge."); return }
        try {
            client.squashMerge(org, run.repo, pr, "${run.issueTitle} (#${run.issueNumber})")
        } catch (e: Exception) {
            needsHuman(run, owner, "Merging pull request #$pr failed: ${e.message}")
            return
        }
        bestEffort { client.removeLabel(org, run.repo, run.issueNumber, "needs-human") }
        val approval = approvedBy?.let { " (approved by @$it)" } ?: " (approved by the approver agent)"
        if (run.releaseId != null && run.baseBranch != null) {
            val defaultBranch = runCatching { client.getRepo(org, run.repo)?.defaultBranch }.getOrNull() ?: "main"
            bestEffort {
                client.comment(org, run.repo, run.issueNumber,
                    "**Agern: merged** pull request #$pr into `${run.baseBranch}`$approval. " +
                        "The issue stays open until the release is merged into `$defaultBranch`.")
            }
        } else {
            bestEffort { client.comment(org, run.repo, run.issueNumber, "**Agern: merged** pull request #$pr$approval.") }
            bestEffort { client.closeIssue(org, run.repo, run.issueNumber) }
        }
        cleanupWorktree(run.id, merged = true)
        runs.setStatus(run.id, RunStatus.MERGED, clearError = true)
    }

    /**
     * Removes the run's sandbox, then its worktree and its local branch. A merged run's branch always goes (its work is merged); an
     * unmerged one's is kept only if it holds commits that exist nowhere else (never pushed). Blocking.
     */
    private fun cleanupWorktree(runId: UUID, merged: Boolean) {
        // The sandbox bind-mounts the worktree: its containers go first (normally already gone; never recreated after).
        sandboxes.releaseNow(runId)
        val run = runs.get(runId) ?: return
        val owner = users.byId(run.ownerId) ?: return
        bestEffort {
            workspaces.withRepoLock(owner.username, run.repo) {
                workspaces.git.removeWorktree(
                    workspaces.mainDir(owner.username, run.repo), workspaces.runDir(owner.username, run.repo, run.id),
                    workspaces.home(owner.username), keepUnpushed = !merged,
                )
            }
        }
    }

    private inline fun bestEffort(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.warn("Best-effort GitHub/git call failed: {}", e.message)
        }
    }

    // ------------------------------------------------------------ verdict parsing

    fun parseVerdict(r: StageAgentResult, allowed: Set<String>): Verdict? {
        val candidates = mutableListOf<JsonElement>()
        r.structuredOutput?.let { candidates += it }
        r.resultText?.let { t ->
            runCatching { candidates += AppJson.parseToJsonElement(t.trim()) }
            Regex("\\{[\\s\\S]*}").find(t)?.let { m -> runCatching { candidates += AppJson.parseToJsonElement(m.value) } }
        }
        for (c in candidates) {
            val o = c as? JsonObject ?: continue
            val verdict = o["verdict"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: continue
            if (verdict !in allowed) continue
            val summary = o["summary"]?.jsonPrimitive?.contentOrNull ?: ""
            val findings = runCatching {
                o["findings"]?.jsonArray?.mapNotNull { f ->
                    val fo = f as? JsonObject ?: return@mapNotNull null
                    FindingDto(
                        severity = fo["severity"]?.jsonPrimitive?.contentOrNull ?: "minor",
                        file = fo["file"]?.jsonPrimitive?.contentOrNull,
                        detail = fo["detail"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    )
                } ?: emptyList()
            }.getOrDefault(emptyList())
            return Verdict(verdict, summary, findings)
        }
        return null
    }

    // ------------------------------------------------------------ prompts

    private fun issueText(issue: GhIssue, comments: List<dk.egernet.factory.github.GhComment>) = buildString {
        append("Issue #${issue.number}: ${issue.title}\n")
        append("Kind: ${issueKindFromLabels(issue.labels.map { it.name })}")
        if (issue.labels.isNotEmpty()) append(" · Labels: ${issue.labels.joinToString(", ") { it.name }}")
        append(" · Author: @${issue.user?.login ?: "unknown"}\n\n")
        append("<issue_body>\n${issue.body?.trim().orEmpty().ifEmpty { "(no description)" }}\n</issue_body>\n")
        val human = comments.filter { !(it.body ?: "").startsWith("**Agern") }
        if (human.isNotEmpty()) {
            append("\n<issue_comments>\n")
            human.takeLast(30).forEach { append("@${it.user?.login ?: "?"} (${it.createdAt}):\n${it.body?.trim()}\n\n") }
            append("</issue_comments>\n")
        }
    }

    private fun specPrompt(c: RunContext) = """
        |Write the implementation spec for this issue in ${org}/${c.repo}.
        |
        |${c.issueText}
        |Your working directory is a checkout of the repository at `${c.baseRef}`. Explore it (read-only) before writing.
        |Your final message must be the spec in Markdown and nothing else: it is posted verbatim as a comment on the issue
        |and handed to the builder.
    """.trimMargin()

    private fun buildPrompt(c: RunContext, spec: String) = """
        |Implement issue #${c.issue.number} in ${org}/${c.repo} according to the spec below.
        |
        |You are in a git worktree on branch `${c.branch}`, created from `${c.baseRef}`. Commit your work to this branch.
        |Do not push, open pull requests or merge: the factory does that after you finish.
        |${sandboxLine(c)}
        |${c.releaseNote}${c.issueText}
        |<spec>
        |$spec
        |</spec>
        |
        |When you are done, end with a short summary of what you changed, how you verified it, and anything left open.
    """.trimMargin()

    private fun fixPrompt(c: RunContext, round: Int, maxRounds: Int, changes: Map<String, Verdict>) = buildString {
        append("The reviewers requested changes. This is fix round $round of at most $maxRounds.\n")
        append("Address every finding below on the same branch (`${c.branch}`), re-run the relevant checks, and commit. ")
        append("If you are convinced a finding is wrong, do not change the code for it; explain why in your final summary ")
        append("so the reviewers see your reasoning.\n")
        append(sandboxLine(c)).append('\n')
        for ((role, v) in changes) {
            append("### Reviewer: $role — ${v.verdict}\n${v.summary}\n")
            v.findings.forEach { f -> append("- [${f.severity}] ${f.file ?: "(general)"}: ${f.detail}\n") }
            append('\n')
        }
        append("End with a short summary of what you changed for each finding.")
    }

    /** One paragraph for the builder about the run's sandbox (empty line when sandboxes are off). */
    private fun sandboxLine(c: RunContext): String {
        val sb = c.sandbox?.takeIf { sandboxes.enabled } ?: return ""
        return if (sb.state == "approved") {
            "\nSandbox: this run has one (image `${sb.image}`). Build and test inside it with `factory_sandbox_exec` instead of on the host; " +
                "`factory_sandbox_info` lists its services and limits.\n" +
                (if (sb.platforms.isEmpty()) "" else "Platforms: ${sb.platforms.joinToString(", ") { "`$it`" }} (other images; " +
                    "`factory_sandbox_exec` with `platform`). Run the test suite in the default image and on every platform before you finish, " +
                    "and report the result per platform in your summary.\n")
        } else {
            "\nSandbox: none for this run (${sb.reason}). Build and test on the host as usual.\n"
        }
    }

    private fun reviewPrompt(c: RunContext, role: String, round: Int, spec: String, builderSummary: String, roundNote: String? = null) = """
        |Review the changes on branch `${c.branch}` for issue #${c.issue.number} in ${org}/${c.repo}${
            if (roundNote != null) " ($roundNote)" else if (round > 0) " (after fix round $round)" else ""}.
        |Your focus: **$role** (see your role instructions). You cannot edit files.
        |
        |Inspect the change with `git diff ${c.baseRef}...HEAD`, `git log ${c.baseRef}..HEAD` and by reading the code; run tests or
        |the app where that helps your review.
        |
        |${c.releaseNote}${c.issueText}
        |<spec>
        |$spec
        |</spec>
        |
        |<builder_summary>
        |${builderSummary.ifEmpty { "(none)" }}
        |</builder_summary>
        |
        |Answer through the structured output: verdict `PASS` or `CHANGES`, a summary, and findings
        |(severity blocker|major|minor|nit, file or null, detail). Use `CHANGES` only for findings that must be fixed before merge.
    """.trimMargin()

    private fun approvalPrompt(c: RunContext, spec: String, reviews: Map<String, Verdict>, round: Int, maxRounds: Int, prUrl: String?) = buildString {
        append("Decide whether the pull request for issue #${c.issue.number} in ${org}/${c.repo} may be squash-merged into `${c.targetBranch}`.\n")
        append(c.releaseNote)
        append("Branch `${c.branch}`; pull request ${prUrl ?: "(none)"}; fix rounds used: $round of $maxRounds.\n")
        append("Inspect the change with `git diff ${c.baseRef}...HEAD`. You cannot edit files.\n\n")
        append(c.issueText).append("<spec>\n").append(spec).append("\n</spec>\n\n<reviews>\n")
        for ((role, v) in reviews) {
            append("### $role — ${v.verdict}\n${v.summary}\n")
            v.findings.forEach { f -> append("- [${f.severity}] ${f.file ?: "(general)"}: ${f.detail}\n") }
            append('\n')
        }
        append("</reviews>\n\nAnswer through the structured output: verdict `APPROVE` or `ESCALATE` and a summary.")
    }

    companion object {
        val READ_ONLY_TOOLS = listOf("Edit", "Write", "NotebookEdit")

        fun modelFor(s: dk.egernet.factory.SettingsDto, role: String): String =
            s.pipelineModels[role] ?: dk.egernet.factory.DEFAULT_PIPELINE_MODELS[role] ?: "sonnet"

        /**
         * `factory/issue-<n>-<slug>-<run>`; a release run's is `factory/release-<name>/issue-<n>-<slug>-<run>`, where
         * `<run>` is the first 8 hex digits of the run id. Every run has its own branch, so a run never reuses (or
         * force-pushes over) the branch, pull request or worktree of an earlier run of the same issue.
         */
        fun runBranch(issueNumber: Int, title: String, releaseName: String?, runId: UUID): String =
            (if (releaseName != null) "factory/release-$releaseName/" else "factory/") +
                "issue-$issueNumber-${slug(title)}-${runId.toString().take(8)}"

        fun slug(title: String): String =
            title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-').ifEmpty { "work" }

        /**
         * How a round is named: `build` (0), `fix round <n>` (it has a builder stage) or `follow-up review <k>` (a review
         * round of a waiting run without a builder; k counts from the last builder round).
         */
        fun roundLabel(stages: List<StageRecord>, round: Int): String {
            if (round == 0) return "build"
            if (stages.any { it.role == "builder" && it.round == round }) return "fix round $round"
            val lastBuilder = stages.filter { it.role == "builder" && it.round < round }.maxOfOrNull { it.round } ?: 0
            return "follow-up review ${round - lastBuilder}"
        }

        fun firstParagraph(s: String): String = s.trim().split(Regex("\n\\s*\n")).first().take(500)

        /**
         * The pull request note for the screenshots of review [round] (null when there are none): the count, a pointer to
         * the run page ([Config.pageRef], so an internal address is never published) and one line per screenshot,
         * reviewers in [REVIEWER_ROLES] order, each in attach order. Captions are single-line already (sanitised on attach).
         */
        fun screenshotNoteText(
            cfg: Config, repo: String, runId: UUID, round: Int, stages: List<StageRecord>, shots: List<dk.egernet.factory.db.ScreenshotMeta>,
        ): String? {
            val byStage = shots.groupBy { it.stageId }
            val lines = REVIEWER_ROLES.flatMap { role ->
                stages.filter { it.role == role && it.round == round }.flatMap { st -> byStage[st.id].orEmpty().map { role to it.caption } }
            }
            if (lines.isEmpty()) return null
            val n = lines.size
            val what = roundLabel(stages, round)
            val ref = cfg.pageRef("p/$repo/runs/$runId", "the page of run `$runId`")
            return "**Agern: screenshots** — the reviewers attached $n ${if (n == 1) "screenshot" else "screenshots"} of this change " +
                "($what). See them on $ref:\n\n" + lines.joinToString("\n") { (role, caption) -> "- **$role:** $caption" }
        }
    }
}
