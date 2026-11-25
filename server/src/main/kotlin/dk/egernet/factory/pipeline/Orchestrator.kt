package dk.egernet.factory.pipeline

import dk.egernet.factory.ADVISOR_KIND
import dk.egernet.factory.AppJson
import dk.egernet.factory.ReviewState
import dk.egernet.factory.SessionStatus
import dk.egernet.factory.badRequest
import dk.egernet.factory.agent.AdvisorLaunch
import dk.egernet.factory.db.EventStore
import dk.egernet.factory.db.SessionRecord
import dk.egernet.factory.db.SessionStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dk.egernet.factory.Config
import dk.egernet.factory.FailedStep
import dk.egernet.factory.FailureKind
import dk.egernet.factory.RetryMode
import dk.egernet.factory.RetryOptionDto
import dk.egernet.factory.RetryOptionsDto
import dk.egernet.factory.FindingDto
import dk.egernet.factory.REVIEWER_ROLES
import dk.egernet.factory.SCREENSHOT_ROLES
import dk.egernet.factory.RunStatus
import dk.egernet.factory.MergePolicy
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

/**
 * A stage (or the run's set-up, or a step after the builder) failed; carries what the stage agent reported about usage
 * limits and credentials, the [FailureKind] and, for a mechanical step after the builder, the [FailedStep].
 */
private class StageFailed(
    message: String,
    val usageLimit: UsageLimit? = null,
    val credentialRejected: Boolean = false,
    val kind: String = FailureKind.OTHER,
    val step: String? = null,
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
    private val sessions: SessionStore,
    private val events: EventStore,
    private val images: dk.egernet.factory.images.ImageService,
    private val workspaceDefs: dk.egernet.factory.workspace.WorkspaceService,
    /** The member repositories of releases: where a member release run's release is later merged (its base branch). */
    private val releaseStore: dk.egernet.factory.db.ReleaseStore? = null,
    /** The projects' settings: the merge policy a run snapshots and the reviewers of policy `github`; null = defaults. */
    private val projectSettings: dk.egernet.factory.db.ProjectSettingsStore? = null,
) {
    private val log = LoggerFactory.getLogger(Orchestrator::class.java)
    private val jobs = ConcurrentHashMap<UUID, Job>()
    /** Per run: serialises the conversation's commands (talk, review, approve's checks) against each other. */
    private val talkLocks = ConcurrentHashMap<UUID, Mutex>()
    /** Per run: serialises reconciles (after a turn and after an exit of the same process). */
    private val reconcileLocks = ConcurrentHashMap<UUID, Mutex>()
    /** Runs being cancelled or approved: no follow-up review may start for them meanwhile. */
    private val ending = ConcurrentHashMap.newKeySet<UUID>()
    private val org get() = cfg.githubOrg

    fun job(runId: UUID): Job? = jobs[runId]

    /** The settings of project [repo] as stored now (defaults without a row). */
    private fun settingsOf(repo: String): dk.egernet.factory.ProjectSettingsDto =
        projectSettings?.get(repo) ?: dk.egernet.factory.ProjectSettingsDto()

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
        runs.get(runId)?.let { bus.emitFeed(FeedItem.Run(runs.toDto(it), it.contextRepos)) }
    }

    // ------------------------------------------------------------ commands (ownership enforced here)

    /** Starts a run as [owner]. The owner's credentials are the only ones the run will ever use. */
    suspend fun start(owner: UserRecord, repo: String, issueNumber: Int, startedBySession: UUID?): RunRecord =
        create(owner, repo, issueNumber, startedBySession, releaseId = null, baseBranch = null)

    /**
     * Starts a release run as the release's [owner]: it branches from and merges into [baseBranch] (`release/<name>`,
     * in the target member's repository for a member issue) and does not close its issue (docs/ARCHITECTURE.md §6
     * "Releases"). [expectedTarget]: the member repository (null: the project repository) the release builder prepared
     * the release branch for; when the issue routes elsewhere now, nothing starts (409 `member_changed`).
     */
    suspend fun startReleaseRun(
        owner: UserRecord, repo: String, issueNumber: Int, releaseId: UUID, baseBranch: String, continuesFrom: RunRecord? = null,
        expectedTarget: ExpectedTarget? = null,
    ): RunRecord = create(owner, repo, issueNumber, null, releaseId, baseBranch, continuesFrom, expectedTarget)

    /** The target repository a release run is expected to have: [repo], or null for the project repository. */
    data class ExpectedTarget(val repo: String?)

    /**
     * [continuesFrom]: a failed run whose branch the new run's worktree starts from and whose spec it reuses
     * (docs/ARCHITECTURE.md §6 "Retrying a run").
     */
    private suspend fun create(
        owner: UserRecord, repo: String, issueNumber: Int, startedBySession: UUID?, releaseId: UUID?, baseBranch: String?,
        continuesFrom: RunRecord? = null, expectedTarget: ExpectedTarget? = null,
    ): RunRecord {
        if (owner.claudeCredEnc == null) throw unprocessable("Register a Claude credential in Settings first", "claude_credential_required")
        access.requireVisible(owner, repo)
        val client = access.requireClient(owner)
        val issue = withContext(Dispatchers.IO) { client.getIssue(org, repo, issueNumber) } ?: throw notFound("Issue #$issueNumber not found")
        // A workspace project: the member the run changes (docs/ARCHITECTURE.md §6 "Workspace runs"), fixed for the run.
        val member = withContext(Dispatchers.IO) { workspaceTarget(owner, repo, issue, continuesFrom) }
        if (expectedTarget != null && expectedTarget.repo != member?.first?.repository) {
            throw conflict(
                "Issue #$issueNumber now belongs to ${member?.first?.let { "member `${it.directory}` (${it.repository})" } ?: "the workspace repository"}, " +
                    "not to ${expectedTarget.repo ?: "the workspace repository"} as its release branch was prepared for; retry it.",
                "member_changed",
            )
        }
        // The starting user's own choices (else the admin defaults), fixed for this run: max rounds now, models per stage.
        val eff = preferences.effective(owner.id)
        // The merge policy in force now, fixed for the run (docs/ARCHITECTURE.md §6 "Merge policy"). A release run merges
        // into its release branch; the policy applies to the release's own merge instead.
        val policy = if (releaseId != null) MergePolicy.AUTOMATIC else settingsOf(repo).mergePolicy
        val run = runs.createIfNoActive(
            repo, issueNumber, issue.title, issueKindFromLabels(issue.labels.map { it.name }), eff.maxFixRounds, owner.id, startedBySession,
            // A release run's base is always its release branch (in the member repository for a member issue).
            releaseId, (if (releaseId != null) baseBranch else null) ?: member?.first?.baseBranch ?: baseBranch, continuesFrom?.id, continuesFrom?.spec, member?.first?.repository,
            member?.first?.directory, member?.second.orEmpty(), policy,
        ) ?: throw conflict("Issue #$issueNumber already has an active run", "active_run_exists")
        for (role in listOf("spec-writer", "builder") + REVIEWER_ROLES + "approver") runs.createStage(run.id, role, 0, eff.model(role))
        publish(run.id)
        launchJob(run.id) { execute(run.id) }
        return runs.get(run.id)!!
    }

    /**
     * The member a new run of [issue] in [repo] changes, or null for a project that is not a workspace (or a run of the
     * workspace repository itself). A release run is routed exactly as a single run; a continuation keeps the member of
     * the run it continues; otherwise the issue's member label decides ([dk.egernet.factory.workspace.WorkspaceService.memberFor]). With it: the repositories the run
     * will check out (the target and every member the owner can read), recorded at once so that nobody who cannot read
     * one of them sees the run before its set-up narrows them to what was checked out. Blocking.
     */
    private fun workspaceTarget(
        owner: UserRecord, repo: String, issue: GhIssue, continuesFrom: RunRecord?,
    ): Pair<dk.egernet.factory.agent.MemberCheckout, List<String>>? {
        val target = workspaceMember(owner, repo, issue, continuesFrom) ?: return null
        val readable = workspaceDefs.resolved(owner, repo).orEmpty().filter { it.accessible }.map { it.repository }
        return target to (listOf(target.repository) + readable).distinct()
    }

    private fun workspaceMember(
        owner: UserRecord, repo: String, issue: GhIssue, continuesFrom: RunRecord?,
    ): dk.egernet.factory.agent.MemberCheckout? {
        val prevRepo = continuesFrom?.targetRepo
        val prevDir = continuesFrom?.targetDir
        val prevBase = continuesFrom?.baseBranch
        if (prevRepo != null && prevDir != null && prevBase != null) {
            if (!access.canSee(owner, prevRepo)) {
                throw workspaceDefs.notAccessible(dk.egernet.factory.workspace.WorkspaceMember(prevDir, prevRepo, prevBase, prevRepo))
            }
            return dk.egernet.factory.agent.MemberCheckout(prevDir, prevRepo, prevBase)
        }
        val m = workspaceDefs.memberFor(owner, repo, issue) ?: return null
        return dk.egernet.factory.agent.MemberCheckout(m.directory, m.repository, m.baseBranch!!, m.member.submodule)
    }

    private fun launchJob(runId: UUID, block: suspend () -> Unit) {
        val job = scope.launch { block() }
        jobs[runId] = job
        job.invokeOnCompletion { jobs.remove(runId, job) }
    }

    // ------------------------------------------------------------ retry (docs/ARCHITECTURE.md §6 "Retrying a run")

    /**
     * How [run] can be retried and what is recommended, with a one-line reason. Looks at the run's branch in its owner's
     * clone (local branch, else the remote one). Blocking.
     */
    fun retryOptions(run: RunRecord): RetryOptionsDto {
        val owner = users.byId(run.ownerId)
        val commits = if (owner == null || run.branch == null) null else runCatching {
            val client = access.client(owner)
            val base = run.baseBranch ?: client?.let { c -> runCatching { c.getRepo(org, run.repo)?.defaultBranch }.getOrNull() } ?: "main"
            val id = runCatching { identity(owner) }.getOrNull()
            workspaces.git.branchCommits(id, gitDirOf(run, owner.username), run.branch, base, workspaces.home(owner.username))
        }.getOrNull()
        val hasCommits = (commits ?: 0) > 0
        val stepAvailable = when {
            run.status == RunStatus.FAILED && run.failedStep in setOf(FailedStep.PUSH, FailedStep.PULL_REQUEST) -> hasCommits
            run.status == RunStatus.NEEDS_HUMAN && run.failedStep == FailedStep.MERGE -> run.prNumber != null
            else -> false
        }
        val retryable = run.status == RunStatus.FAILED || run.status == RunStatus.CANCELLED || run.status == RunStatus.NEEDS_HUMAN
        val continueAvailable = retryable && hasCommits
        val stepName = when (run.failedStep) {
            FailedStep.PUSH -> "push"; FailedStep.PULL_REQUEST -> "pull request"; FailedStep.MERGE -> "merge"; else -> null
        }
        val error = run.error?.lineSequence()?.firstOrNull()?.trim()?.let { if (it.length > 160) it.take(157) + "…" else it }
        val n = commits ?: 0
        val withCommits = "$n commit${if (n == 1) "" else "s"}"
        val (recommended, reason) = when {
            stepAvailable -> RetryMode.STEP to "The builder finished; only the $stepName failed" + (error?.let { ": $it" } ?: ".")
            !continueAvailable -> RetryMode.START_OVER to when {
                run.failureKind == FailureKind.BLOCKED -> "The builder made no commits" + (run.error?.substringAfter("The builder said: ", "")
                    ?.takeIf { it.isNotBlank() }?.let { ": ${it.take(140)}" } ?: ".")
                run.branch == null -> "The run stopped before it had a branch."
                commits == null -> "The run's branch ${run.branch} was found neither locally nor on GitHub."
                else -> "The run's branch has no commits of its own."
            }
            run.status == RunStatus.CANCELLED -> RetryMode.START_OVER to "The run was cancelled; its branch has $withCommits if you want to continue."
            run.failureKind == FailureKind.BLOCKED -> RetryMode.START_OVER to "The builder reported that it could not go on" + (error?.let { ": $it" } ?: ".")
            run.failureKind in FailureKind.INTERRUPTED -> RetryMode.CONTINUE to
                "Stopped by ${interruption(run.failureKind)} after $withCommits; continue from them."
            run.status == RunStatus.NEEDS_HUMAN -> RetryMode.CONTINUE to "The branch has $withCommits; a new builder continues from them" +
                (error?.let { " ($it)" } ?: "") + "."
            else -> RetryMode.CONTINUE to "The run failed after $withCommits" + (error?.let { " ($it)" } ?: "") + "; continue from them."
        }
        val options = listOf(
            RetryOptionDto(
                RetryMode.STEP, stepAvailable,
                if (stepAvailable) "Retry the $stepName on this run and continue the pipeline from there; no new spec or build."
                else "Only when the builder finished and a later step (push, pull request, merge) failed.",
            ),
            RetryOptionDto(
                RetryMode.CONTINUE, continueAvailable,
                if (continueAvailable) "A new run starts from branch ${run.branch} ($withCommits), reuses the spec and finishes the work."
                else "Only when the run's branch has commits of its own.",
            ),
            RetryOptionDto(RetryMode.START_OVER, true, "A new run on a new branch from scratch; the old branch is left as it is."),
        )
        return RetryOptionsDto(
            run.id.toString(), recommended, reason, options, step = run.failedStep?.takeIf { stepAvailable }, commits = commits,
            releaseId = run.releaseId?.toString(),
        )
    }

    private fun interruption(kind: String?) = when (kind) {
        FailureKind.TIME_LIMIT -> "the stage time limit"
        FailureKind.USAGE_LIMIT -> "the Claude usage limit"
        FailureKind.RESTART -> "a server restart"
        FailureKind.API_UNREACHABLE -> "the Claude API being unreachable"
        else -> "an interruption"
    }

    /**
     * [requested] (null = `start_over`, `recommended` = what [opts] recommends) checked against [opts]: 400 for an
     * unknown mode, 409 `retry_mode_unavailable` for one that is not possible for the run now.
     */
    fun resolveRetryMode(requested: String?, opts: RetryOptionsDto): String {
        val mode = when (requested) {
            null, "" -> RetryMode.START_OVER
            RetryMode.RECOMMENDED -> opts.recommended
            in RetryMode.ALL -> requested
            else -> throw dk.egernet.factory.badRequest("mode must be one of step, continue, start_over, recommended")
        }
        val opt = opts.options.first { it.mode == mode }
        if (!opt.available) throw conflict("Cannot retry with '$mode': ${opt.detail}", "retry_mode_unavailable")
        return mode
    }

    /**
     * Retries a single (non-release) failed or cancelled run as its owner (docs/ARCHITECTURE.md §6 "Retrying a run"):
     * the failed step on the same run, a new run continuing from its commits, or a new run from scratch. Returns the run
     * that now works on the issue.
     */
    suspend fun retry(user: UserRecord, runId: UUID, requested: String?): RunRecord {
        val run = runs.get(runId) ?: throw notFound("Run not found")
        if (run.ownerId != user.id) throw forbidden("Only the owner of this run can retry it")
        if (run.releaseId != null) {
            throw conflict("This run builds an issue of a release; retry the issue on the release's page", "release_run")
        }
        if (run.status != RunStatus.FAILED && run.status != RunStatus.CANCELLED) {
            throw conflict("Run is ${run.status}; only failed or cancelled runs can be retried", "run_not_retryable")
        }
        val opts = withContext(Dispatchers.IO) { retryOptions(run) }
        return when (resolveRetryMode(requested, opts)) {
            RetryMode.STEP -> retryStep(user, run)
            RetryMode.CONTINUE -> create(user, run.repo, run.issueNumber, null, null, null, continuesFrom = run)
            else -> create(user, run.repo, run.issueNumber, null, null, null)
        }
    }

    /**
     * Retries the failed step of [run] as [owner] (its owner): a failed push or pull request reactivates the failed run,
     * re-creates its worktree on its own branch and goes on from the push; a failed merge of a `needs_human` run merges
     * again. 409 `active_run_exists` when the issue got another active run meanwhile.
     */
    suspend fun retryStep(owner: UserRecord, run: RunRecord): RunRecord {
        if (owner.claudeCredEnc == null) throw unprocessable("Register a Claude credential in Settings first", "claude_credential_required")
        if (run.status == RunStatus.NEEDS_HUMAN && run.failedStep == FailedStep.MERGE) {
            if (run.mergePolicy == MergePolicy.GITHUB) throw mergeOnGitHub(run)
            if (run.prNumber == null) throw conflict("Run has no pull request to merge", "retry_mode_unavailable")
            // The same gate as an approval: changes from the conversation must have been reviewed.
            startMerging(run.id)
            publish(run.id)
            // The approver approved before the merge failed: merging again is its decision; otherwise it is the owner's.
            // Under any other policy than automatic the approver never decides: the owner retrying it does.
            val approverApproved = run.mergePolicy == MergePolicy.AUTOMATIC &&
                runs.stages(run.id).lastOrNull { it.role == "approver" }?.verdict == "APPROVE"
            launchJob(run.id) {
                withContext(Dispatchers.IO) { merge(runs.get(run.id)!!, owner, approvedBy = if (approverApproved) null else owner.username) }
                runs.get(run.id)?.takeIf { it.status == RunStatus.MERGED }?.advisorSessionId?.let { agents.endAdvisor(it) }
                publish(run.id)
                settled(run.id)
            }
            return runs.get(run.id)!!
        }
        val step = run.failedStep?.takeIf { it == FailedStep.PUSH || it == FailedStep.PULL_REQUEST }
            ?: throw conflict("The run has no failed step to retry", "retry_mode_unavailable")
        if (!runs.reactivate(run.id)) {
            val current = runs.get(run.id)
            if (current?.status !in setOf(RunStatus.FAILED, RunStatus.CANCELLED)) throw conflict("Run is ${current?.status}", "run_not_retryable")
            throw conflict("Issue #${run.issueNumber} already has an active run", "active_run_exists")
        }
        runs.reopenSkippedStages(run.id)
        publish(run.id)
        launchJob(run.id) { execute(run.id, step) }
        return runs.get(run.id)!!
    }

    /**
     * Owner only, only in needs_human: squash-merge the PR and close the issue. Never for a run of policy `github`
     * (409 `merge_on_github`, whatever its status; docs/ARCHITECTURE.md §6 "Merge policy").
     */
    suspend fun approve(user: UserRecord, runId: UUID): RunRecord {
        val run = runs.get(runId) ?: throw notFound("Run not found")
        if (run.ownerId != user.id) throw forbidden("Only the owner of this run can approve it")
        if (run.mergePolicy == MergePolicy.GITHUB) throw mergeOnGitHub(run)
        if (run.status != RunStatus.NEEDS_HUMAN) throw conflict("Run is ${run.status}, not needs_human")
        if (run.prNumber == null) throw conflict("Run has no pull request to merge")
        startMerging(runId)
        publish(runId)
        withContext(Dispatchers.IO) { merge(runs.get(runId)!!, user, approvedBy = user.username) }
        runs.get(runId)?.takeIf { it.status == RunStatus.MERGED }?.advisorSessionId?.let { agents.endAdvisor(it) }
        publish(runId)
        settled(runId)
        return runs.get(runId)!!
    }

    /**
     * Moves a `needs_human` run to `approving` before it is merged, by the owner's approval or a retry of a failed merge.
     * Changes made in the conversation are reviewed before the run can be merged (docs/ARCHITECTURE.md §6 "Conversation at
     * needs human"): enforced here, from server state, for every way to merge a run that needs a human.
     */
    private suspend fun startMerging(runId: UUID) {
        talkLocks.computeIfAbsent(runId) { Mutex() }.withLock {
            val r = runs.get(runId)!!
            if (r.status != RunStatus.NEEDS_HUMAN) throw conflict("Run is ${r.status}, not needs_human")
            if (r.reviewState == ReviewState.RUNNING) {
                throw conflict("The changes from the conversation are being reviewed; approve when the reviews are in", "review_in_progress")
            }
            r.advisorSessionId?.let { if (agents.isAdvisorBusy(it)) throw agentBusy() }
            if (r.reviewState == ReviewState.REQUIRED) throw reviewRequired()
            if (r.reviewedSha != null && headOf(r).let { it != null && it != r.reviewedSha }) {
                // A commit the review has not seen (e.g. a restart in the middle of a turn): it must be reviewed first.
                runs.casReviewState(runId, ReviewState.NONE, ReviewState.REQUIRED)
                publish(runId)
                throw reviewRequired()
            }
            ending += runId
            try {
                r.advisorSessionId?.let { agents.stopAdvisor(it, ended = false) }
                runs.setStatus(runId, RunStatus.APPROVING, clearError = true)
            } finally {
                ending -= runId
            }
        }
    }

    /** The refusal of every way to merge a run of policy `github` (409 `merge_on_github`). */
    private fun mergeOnGitHub(run: RunRecord) = conflict(
        "This project merges on GitHub: Agern does not merge. Review and merge pull request ${run.prUrl ?: "(none)"} on GitHub.",
        "merge_on_github",
    )

    /**
     * Ends a run that is not over yet, as cancel does: the pipeline or a follow-up review is cancelled and joined, the
     * advisor (mid-turn included) is stopped for good and the sandbox released, so nothing works in the worktree after
     * this. Then, unless [proceed] refuses the run as it is now (e.g. it ended meanwhile), the worktree is removed
     * ([merged]: its branch is merged), [finish] sets the new status and open stages are closed. False when skipped.
     */
    private suspend fun endRun(runId: UUID, merged: Boolean, proceed: (RunRecord) -> Boolean, finish: () -> Unit): Boolean {
        ending += runId
        try {
            jobs[runId]?.let { it.cancel(); it.join() }
            runs.get(runId)?.advisorSessionId?.let { agents.stopAdvisor(it, ended = true) }
            sandboxes.release(runId)
            val after = runs.get(runId) ?: return false
            if (!proceed(after)) return false
            withContext(Dispatchers.IO) { cleanupWorktree(runId, merged) }
            finish()
        } finally {
            ending -= runId
        }
        runs.closeOpenStages(runId)
        return true
    }

    /** Owner or admin. */
    suspend fun cancel(user: UserRecord, runId: UUID): RunRecord {
        val run = runs.get(runId) ?: throw notFound("Run not found")
        if (run.ownerId != user.id && !user.isAdmin) throw forbidden("Only the owner or an admin can cancel this run")
        if (run.status in RunStatus.TERMINAL) throw conflict("Run is already ${run.status}")
        val ended = endRun(runId, merged = false, proceed = { it.status !in RunStatus.TERMINAL }) {
            runs.setStatus(runId, RunStatus.CANCELLED, "Cancelled by ${user.username}")
        }
        if (!ended) return runs.get(runId)!!
        val owner = users.byId(run.ownerId)!!
        withContext(Dispatchers.IO) {
            bestEffort { gh(owner).removeLabel(org, run.repo, run.issueNumber, "needs-human") }
            commentEnded(runId, "**Agern:** run `${run.id}` was cancelled by @${user.username}.")
        }
        publish(runId)
        settled(runId)
        return runs.get(runId)!!
    }

    // ------------------------------------------------------------ conversation at needs human (docs/ARCHITECTURE.md §6)

    private fun agentBusy() = conflict("The agent is still answering; wait for it or interrupt it", "agent_busy")
    private fun reviewRequired() =
        conflict("Changes from the conversation have not been reviewed yet; review them again before approving", "review_required")
    private fun reviewInProgress() =
        conflict("The changes from the conversation are being reviewed; continue when the reviews are in", "review_in_progress")

    private fun requireWaiting(run: RunRecord) {
        if (run.status != RunStatus.NEEDS_HUMAN) {
            throw conflict("Run is ${run.status}; the agent can only be talked to while the run needs a human", "run_not_waiting")
        }
    }

    /** The git worktree of the run's change: the run directory, or the target member's worktree in it for a workspace run. */
    private fun worktreeOf(run: RunRecord): Path {
        val root = workspaces.runDir(run.ownerUsername, run.repo, run.id)
        return run.targetDir?.let { workspaces.memberPath(root, it) } ?: root
    }

    /** The clone the run's branch lives in: the owner's clone of the project, or of the target member for a workspace run. */
    private fun gitDirOf(run: RunRecord, username: String): Path {
        val main = workspaces.mainDir(username, run.repo)
        return run.targetDir?.let { workspaces.memberPath(main, it) } ?: main
    }

    /** The repository of the run's branch and pull request: the target member for a workspace run. */
    private fun prRepo(run: RunRecord): String = run.targetRepo ?: run.repo

    /** How issue comments name pull request [pr]: `#<pr>`, or `<org>/<member>#<pr>` for a workspace run. */
    private fun prRef(run: RunRecord, pr: Int): String = if (run.targetRepo != null) "$org/${run.targetRepo}#$pr" else "#$pr"

    /** The pull request (and squash commit) title: `<title> (#<n>)`, or `<title> (<org>/<workspace>#<n>)`. */
    private fun prTitle(run: RunRecord, title: String): String =
        if (run.targetRepo != null) "$title ($org/${run.repo}#${run.issueNumber})" else "$title (#${run.issueNumber})"

    /** The worktree's HEAD, or null when it cannot be read (e.g. the worktree is gone). Blocking-safe. */
    private suspend fun headOf(run: RunRecord): String? = withContext(Dispatchers.IO) {
        val wt = worktreeOf(run)
        if (!java.nio.file.Files.isDirectory(wt)) null
        else runCatching { workspaces.git.head(wt, workspaces.home(run.ownerUsername)) }
            .onFailure { log.warn("Could not read HEAD of run {}: {}", run.id, it.message) }.getOrNull()
    }

    /**
     * The owner's message for the run's advisor. Owner only (admins included in the refusal), only at `needs_human`,
     * one message at a time. The first message creates the run's advisor session. Returns its id.
     */
    suspend fun talk(user: UserRecord, runId: UUID, text: String): UUID {
        val run = runs.get(runId) ?: throw notFound("Run not found")
        if (run.ownerId != user.id) throw forbidden("Only the owner of this run can talk to its agent")
        requireWaiting(run)
        if (text.isBlank()) throw badRequest("text must not be empty")
        if (text.length > 100_000) throw badRequest("text is too long")
        return talkLocks.computeIfAbsent(runId) { Mutex() }.withLock {
            val r = runs.get(runId) ?: throw notFound("Run not found")
            requireWaiting(r)
            if (r.reviewState == ReviewState.RUNNING) throw reviewInProgress()
            r.advisorSessionId?.let { if (agents.isAdvisorBusy(it)) throw agentBusy() }
            if (user.claudeCredEnc == null) throw unprocessable("Register a Claude credential in Settings first", "claude_credential_required")
            if (r.advisorSessionId?.let { agents.isLive(it) } != true && !agents.hasFreeSlot(user)) {
                throw conflict("You already have ${cfg.maxAgentsPerUser} agents running", "too_many_agents")
            }
            val worktree = worktreeOf(r)
            if (!withContext(Dispatchers.IO) { java.nio.file.Files.isDirectory(worktree) }) {
                throw conflict("The run's worktree is gone, so the agent cannot work on it; start a new run instead", "run_worktree_missing")
            }
            val sessionId = r.advisorSessionId ?: createAdvisor(r, user)
            agents.sendToAdvisor(user, sessions.get(sessionId) ?: throw notFound("Agent session not found"), text)
            sessionId
        }
    }

    /** Creates the run's advisor session (called under the run's talk lock) and records the reviewed commit. */
    private suspend fun createAdvisor(run: RunRecord, owner: UserRecord): UUID {
        val head = headOf(run)
        val model = preferences.effective(owner.id).model("builder")
        val s = sessions.create(
            run.repo, ADVISOR_KIND, model, SessionStatus.IDLE, owner.id, "advisor · #${run.issueNumber}", run.id, null, run.issueNumber,
            contextRepos = run.contextRepos,
        )
        val bound = runs.bindAdvisor(run.id, s.id, head) ?: s.id
        agents.publishFeed(bound)
        publish(run.id)
        return bound
    }

    /** "Review again": owner only, at `needs_human`, when changes from the conversation have not been reviewed. */
    suspend fun review(user: UserRecord, runId: UUID): RunRecord {
        val run = runs.get(runId) ?: throw notFound("Run not found")
        if (run.ownerId != user.id) throw forbidden("Only the owner of this run can have its changes reviewed")
        requireWaiting(run)
        talkLocks.computeIfAbsent(runId) { Mutex() }.withLock {
            reconcileLocks.computeIfAbsent(runId) { Mutex() }.withLock {
                val r = runs.get(runId) ?: throw notFound("Run not found")
                requireWaiting(r)
                if (r.reviewState == ReviewState.RUNNING) throw reviewInProgress()
                r.advisorSessionId?.let { if (agents.isAdvisorBusy(it)) throw agentBusy() }
                if (user.claudeCredEnc == null) throw unprocessable("Register a Claude credential in Settings first", "claude_credential_required")
                val head = headOf(r)
                if (r.advisorSessionId == null || r.reviewedSha == null || (r.reviewState == ReviewState.NONE && head == r.reviewedSha)) {
                    throw conflict("There are no unreviewed changes from the conversation", "nothing_to_review")
                }
                if (!runs.casReviewState(runId, r.reviewState, ReviewState.RUNNING)) throw reviewInProgress()
                launchFollowUp(runId)
            }
        }
        publish(runId)
        return runs.get(runId)!!
    }

    /**
     * After every advisor turn and exit: a `needs_human` run whose worktree HEAD moved past the reviewed commit needs a
     * follow-up review, which starts at once. Idempotent; a no-op while a review runs or the run is ending.
     */
    suspend fun reconcile(runId: UUID) {
        reconcileLocks.computeIfAbsent(runId) { Mutex() }.withLock {
            val run = runs.get(runId) ?: return
            if (run.status != RunStatus.NEEDS_HUMAN || runId in ending || run.reviewedSha == null) return
            if (run.reviewState == ReviewState.RUNNING) return
            val head = headOf(run) ?: return
            if (head == run.reviewedSha) return
            if (!runs.casReviewState(runId, run.reviewState, ReviewState.RUNNING)) return
            launchFollowUp(runId)
        }
        publish(runId)
    }

    /** Starts the follow-up review job, registered in [jobs] so that cancel stops it. */
    private fun launchFollowUp(runId: UUID) {
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { followUp(runId) }
        jobs[runId] = job
        job.invokeOnCompletion { jobs.remove(runId, job) }
        job.start()
    }

    /**
     * The follow-up review of changes made in the conversation: push, then the four reviewers in a new round, exactly
     * as in the pipeline. Never fails the run, never merges, and leaves the worktree; a failure leaves the review
     * `required` for "Review again".
     */
    private suspend fun followUp(runId: UUID) {
        val start = runs.get(runId) ?: return
        val advisor = start.advisorSessionId
        try {
            publish(runId)
            // The advisor must not commit while the reviewers read the tree; it resumes on the owner's next message.
            advisor?.let { agents.stopAdvisor(it, ended = false) }
            val owner = owner(start)
            val id = identity(owner)
            val client = gh(owner)
            val ctx = runContext(start, owner)
            val home = workspaces.home(owner.username)
            val head = withContext(Dispatchers.IO) { workspaces.git.head(ctx.worktree, home) }
            withContext(Dispatchers.IO) { workspaces.git.push(id, ctx.worktree, ctx.branch, home) }
            val round = (runs.stages(runId).maxOfOrNull { it.round } ?: 0) + 1
            val eff = preferences.effective(owner.id)
            REVIEWER_ROLES.forEach { runs.createStage(runId, it, round, eff.model(it)) }
            runs.startFollowUp(runId, round)
            publish(runId)
            val n = round - (runs.get(runId)!!.followUpFrom ?: round) + 1
            val spec = specText(runId).orEmpty()
            val summary = advisor?.let { events.lastResultText(it) }?.trim().orEmpty()
            val reviews = coroutineScope {
                REVIEWER_ROLES.map { role ->
                    async {
                        val r = stage(runId, role, round, owner, ctx, reviewPrompt(ctx, role, round, spec, summary, followUp = n), schema = Schemas.REVIEW,
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
            }.let { list ->
                if (list.any { it == null }) throw StageFailed("A reviewer did not return a valid verdict")
                list.filterNotNull().toMap()
            }
            withContext(Dispatchers.IO) { bestEffort { screenshotNote(runs.get(runId)!!, client, round) } }
            withContext(Dispatchers.IO) { bestEffort { uiScreenshotWarning(runs.get(runId)!!, owner, ctx, client, round) } }
            val sha7 = head.take(7)
            val changes = REVIEWER_ROLES.filter { reviews[it]?.verdict == "CHANGES" }
            val verdicts = REVIEWER_ROLES.joinToString(", ") { "$it ${reviews.getValue(it).verdict}" }
            runs.setReviewedSha(runId, head)
            val reason = originalReason(runs.get(runId)!!.error)
            val outcome = if (changes.isEmpty()) "all four reviewers pass." else "${changes.joinToString(", ")} still request changes."
            runs.setError(runId, (if (reason.isEmpty()) "" else "$reason\n\n") + "**Follow-up review $n** (commit `$sha7`): $outcome")
            advisor?.let { agents.systemEvent(it, "info", "Follow-up review $n of commit $sha7: $verdicts.") }
            // Last: `none` tells clients (and approve) that the review and everything it records is complete.
            runs.setReviewState(runId, ReviewState.NONE)
            publish(runId)
            val pr = runs.get(runId)?.prNumber
            if (pr != null) withContext(Dispatchers.IO) {
                bestEffort {
                    client.comment(org, prRepo(start), pr,
                        "**Agern:** changes requested by the run owner in a conversation were pushed ($sha7) and reviewed again: $verdicts.")
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runs.casReviewState(runId, ReviewState.RUNNING, ReviewState.REQUIRED) }
            throw e
        } catch (e: Exception) {
            log.warn("Follow-up review of run {} failed: {}", runId, e.message)
            runs.closeOpenStages(runId)
            runs.casReviewState(runId, ReviewState.RUNNING, ReviewState.REQUIRED)
            val why = e.message ?: e.javaClass.simpleName
            advisor?.let {
                agents.systemEvent(
                    it, "error",
                    "The follow-up review of the changes did not complete: $why. The run still needs a human; use \"Review again\" " +
                        "on the run page to review the changes before approving.",
                )
            }
            publish(runId)
        }
    }

    /** The needs-human reason without the follow-up paragraphs added after it. */
    private fun originalReason(error: String?): String = (error ?: "").substringBefore("\n\n**Follow-up review ").trim()

    /** The final result text of the run's spec-writer. */
    private fun specText(runId: UUID): String? =
        runs.stages(runId).firstOrNull { it.role == "spec-writer" }?.sessionId?.let { events.lastResultText(it) }

    /** Launch data for the run's advisor, rebuilt from current data (AgentManager.advisorLaunch). */
    suspend fun advisorLaunch(session: SessionRecord, owner: UserRecord): AdvisorLaunch {
        val run = session.runId?.let { runs.get(it) } ?: throw notFound("Run not found")
        requireWaiting(run)
        val ctx = runContext(run, owner)
        if (!withContext(Dispatchers.IO) { java.nio.file.Files.isDirectory(ctx.worktree) }) {
            throw conflict("The run's worktree is gone, so the agent cannot work on it", "run_worktree_missing")
        }
        return AdvisorLaunch(ctx.root, run.id, advisorContext(runs.get(run.id)!!, ctx))
    }

    /**
     * The run's [RunContext], rebuilt from the run record, GitHub (issue, comments, default branch) and the stored
     * sandbox decision. When GitHub cannot be read, the stored title stands in for the issue.
     */
    private suspend fun runContext(run: RunRecord, owner: UserRecord): RunContext = withContext(Dispatchers.IO) {
        val client = access.client(owner)
        val repoInfo = runCatching { client?.getRepo(org, run.repo) }.getOrNull()
        val issue = runCatching { client?.getIssue(org, run.repo, run.issueNumber) }.getOrNull()
        val comments = if (issue == null) emptyList() else runCatching { client?.listComments(org, run.repo, run.issueNumber) }.getOrNull().orEmpty()
        val text = if (issue != null) issueText(issue, comments)
            else "Issue #${run.issueNumber}: ${run.issueTitle}\n(The issue body is unavailable: GitHub could not be read.)\n"
        // A member release run's release is later merged into the member's base branch, not the workspace's default branch.
        val defaultBranch = runCatching { memberReleaseBase(run) }.getOrNull() ?: repoInfo?.defaultBranch ?: "main"
        val target = run.baseBranch ?: defaultBranch
        val releaseName = run.baseBranch?.takeIf { run.releaseId != null }?.removePrefix("release/")
        val block = run.targetRepo?.let { tr ->
            // Rebuilt from the current definition: the members recorded as checked out for the run are its context.
            val all = runCatching { workspaceDefs.resolved(owner, run.repo) }.getOrNull().orEmpty()
            val submoduleTarget = all.any { it.repository == tr && it.directory == run.targetDir && it.member.submodule }
            val resolved = all.filter { it.repository != tr }
            val others = resolved.filter { it.repository in run.contextRepos && it.baseBranch != null }
                .map { dk.egernet.factory.agent.MemberCheckout(it.directory, it.repository, it.baseBranch!!, it.member.submodule) }
            val left = resolved.filter { it.repository !in run.contextRepos }.map {
                dk.egernet.factory.agent.LeftOut(it.directory, it.repository, if (it.accessible) "not checked out for this run" else "your GitHub token cannot read it")
            }
            workspaceBlock(
                run.repo, run.issueNumber, dk.egernet.factory.agent.MemberCheckout(run.targetDir ?: "", tr, target, submoduleTarget), others, left,
                inRelease = releaseName != null,
            )
        } ?: parentBlock(owner, run.repo)
        RunContext(
            run.id, run.repo,
            issue ?: GhIssue(run.issueNumber, run.issueTitle, "open", createdAt = "", updatedAt = "", htmlUrl = ""),
            text, run.branch ?: "", workspaces.git.baseRef(target), target, worktreeOf(run), releaseName, defaultBranch,
            run.sandboxJson?.let { runCatching { AppJson.decodeFromString(dk.egernet.factory.RunSandboxDto.serializer(), it) }.getOrNull() },
            root = workspaces.runDir(run.ownerUsername, run.repo, run.id), gitRepo = prRepo(run), memberDir = run.targetDir,
            workspaceBlock = block, contextRepos = run.contextRepos,
        )
    }

    /**
     * The block every task prompt of a workspace run carries (docs/ARCHITECTURE.md §6 "Workspace runs"): the layout,
     * the target member, the read-only context and where the issue and the pull request are.
     */
    private fun workspaceBlock(
        wsRepo: String, issueNumber: Int, t: dk.egernet.factory.agent.MemberCheckout,
        others: List<dk.egernet.factory.agent.MemberCheckout>, leftOut: List<dk.egernet.factory.agent.LeftOut>, inRelease: Boolean = false,
    ): String = buildString {
        append("Workspace: $org/$wsRepo — several repositories checked out side by side under your working directory.\n")
        append("Target member directory: `${t.directory}` (repository $org/${t.repository}, base branch `${t.baseBranch}`). ")
        append("Run every git command for this change there, e.g. `git -C ${t.directory} status`. ")
        append("Only commits in `${t.directory}/` become the pull request.\n")
        if (t.submodule) {
            append("`${t.directory}` is a git submodule of $org/$wsRepo, checked out on the run branch from `${t.baseBranch}` (not at the ")
            append("commit the workspace pins). Change only this submodule. Never commit the submodule pointer (gitlink) in the ")
            append("workspace repository and do not run `git submodule update` in the root (it would reset `${t.directory}`): Agern ")
            if (inRelease) append("moves the pointer in $org/$wsRepo when the release is released.\n")
            else append("bumps the pointer in $org/$wsRepo after the pull request is merged.\n")
        }
        append("Other checkouts are read-only context; do not edit, commit or push there:\n")
        append("- `.` — $org/$wsRepo (workspace root files)\n")
        others.forEach {
            if (it.submodule) append("- `${it.directory}` — $org/${it.repository} @ the commit the workspace pins (submodule)\n")
            else append("- `${it.directory}` — $org/${it.repository} @ ${it.baseBranch}\n")
        }
        if (leftOut.isNotEmpty()) append("Not checked out: " + leftOut.joinToString(", ") { "`${it.directory}` (${it.reason})" } + "\n")
        append("The issue is $org/$wsRepo#$issueNumber; the pull request is opened in $org/${t.repository} against `${t.baseBranch}`.\n")
    }

    /**
     * The block of a run that changes the workspace repository itself in a workspace whose members come from
     * `.gitmodules` alone (docs/ARCHITECTURE.md §6 "Workspace runs"); empty for every other run. Blocking; never throws.
     */
    private fun parentBlock(owner: UserRecord, repo: String): String {
        val state = runCatching { workspaceDefs.lookup(owner, repo) }.getOrNull()?.takeIf { it.valid && it.fromSubmodules } ?: return ""
        return buildString {
            append("Workspace: $org/$repo — its member repositories are git submodules under your working directory.\n")
            append("Target: the workspace repository itself; submodules are read-only context. Do not commit inside a submodule ")
            append("and do not move a submodule pointer (gitlink): a change to a member needs an issue with that member's label.\n")
            append("Submodules (at the commit the workspace pins):\n")
            state.members.forEach { append("- `${it.directory}` — $org/${it.repository} (label `${it.label}`)\n") }
            state.contextOnly.forEach { append("- `${it.path}` (not a member)\n") }
            append("The issue and the pull request are in $org/$repo.\n")
        }
    }

    /** Human name of a stage round: build, fix round N or follow-up N. */
    private fun roundName(run: RunRecord, round: Int): String {
        val from = run.followUpFrom
        return when {
            from != null && round >= from -> "follow-up ${round - from + 1}"
            round == 0 -> "build"
            else -> "fix round $round"
        }
    }

    /** The `<run_context>` block an advisor's first message carries. Untrusted parts are data for the agent. */
    private fun advisorContext(run: RunRecord, c: RunContext): String = buildString {
        val stages = runs.stages(run.id)
        append("<run_context>\n")
        append("Run `${run.id}` in ${org}/${c.repo} for issue #${run.issueNumber}. Status: needs a human.\n")
        append("Needs a human because: ${run.error?.trim().orEmpty().ifEmpty { "(no reason recorded)" }}\n")
        append("Branch `${c.branch}`, created from `${c.baseRef}`; pull request ${run.prUrl ?: "(none)"}; it merges into `${c.targetBranch}`.\n")
        append(c.releaseNote)
        append(c.workspaceBlock)
        append("Inspect the change with `${c.git} diff ${c.baseRef}...HEAD` and `${c.git} log ${c.baseRef}..HEAD`, and by reading the code.\n")
        append("This run has no sandbox at this point: build and run checks on the host.\n\n")
        append(c.issueText)
        append("\n<spec>\n").append(specText(run.id)?.trim().orEmpty().ifEmpty { "(none)" }).append("\n</spec>\n")
        val builder = stages.filter { it.role == "builder" && it.sessionId != null }.maxByOrNull { it.round }
        val summary = builder?.sessionId?.let { events.lastResultText(it) }?.trim().orEmpty()
        append("\n<builder_summary>\n").append(summary.ifEmpty { "(none)" }).append("\n</builder_summary>\n")
        append("\n<reviews>\n")
        for (role in REVIEWER_ROLES) {
            val st = stages.filter { it.role == role && it.verdict != null }.maxByOrNull { it.round }
            if (st == null) {
                append("### $role — no verdict\n\n")
                continue
            }
            append("### $role — ${roundName(run, st.round)} — ${st.verdict}\n${st.summary.orEmpty()}\n")
            st.findings.forEach { f -> append("- [${f.severity}] ${f.file ?: "(general)"}: ${f.detail}\n") }
            append('\n')
        }
        append("</reviews>\n")
        val approver = stages.lastOrNull { it.role == "approver" }
        append("\n<approver>\n")
        if (approver?.verdict != null) append("${approver.verdict}: ${approver.summary.orEmpty()}\n")
        else append("The approver did not run (${approver?.status ?: "no stage"}).\n")
        append("</approver>\n")
        append("</run_context>")
    }

    // ------------------------------------------------------------ the state machine

    /** [step]: a step retry, which resumes the run at that step ([FailedStep.PUSH] or [FailedStep.PULL_REQUEST]). */
    private suspend fun execute(runId: UUID, step: String? = null) {
        try {
            run(runId, step)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Run {} failed: {}", runId, e.message)
            val reason = e.message ?: e.javaClass.simpleName
            val sf = e as? StageFailed
            val kind = sf?.kind?.takeIf { it != FailureKind.OTHER }
                ?: if (e is java.io.IOException || FailureKind.looksUnreachable(reason)) FailureKind.API_UNREACHABLE else FailureKind.OTHER
            runs.setFailure(runId, kind, sf?.step)
            // Clean up first, so a run shown as ended has left nothing behind: the sandbox (it bind-mounts the worktree),
            // then the worktree.
            withContext(NonCancellable) { sandboxes.release(runId) }
            withContext(Dispatchers.IO) { cleanupWorktree(runId, merged = false) }
            fail(runId, reason)
            withContext(Dispatchers.IO) { commentEnded(runId, "**Agern:** run `$runId` failed: $reason") }
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
            "retrying the run in Agern can retry the failed step, continue from its commits on a new branch, or start over."
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
        access.claudeCredential(owner) ?: throw StageFailed("The run owner has no Claude credential", credentialRejected = true, kind = FailureKind.CREDENTIALS),
    )

    private fun setStatus(runId: UUID, status: String) {
        runs.setStatus(runId, status)
        publish(runId)
    }

    /**
     * The pipeline of one run. [step] set: a step retry that resumes the (reactivated) run at the push or pull request of
     * its current round, with its own branch, spec and builder summary. A run that continues another ([RunRecord.continuesRunId])
     * starts its worktree from that run's branch and reuses its spec.
     */
    private suspend fun run(runId: UUID, step: String? = null) {
        var run = runs.get(runId)!!
        val owner = owner(run)
        val id = identity(owner)
        val client = gh(owner)
        val maxRounds = run.maxRounds
        val resuming = step != null
        val previous = if (resuming) null else run.continuesRunId?.let { runs.get(it) }

        // Workspace: owner's clone + a worktree on the run branch.
        val repoInfo = withContext(Dispatchers.IO) { client.getRepo(org, run.repo) } ?: throw StageFailed("Repository not accessible")
        val issue = withContext(Dispatchers.IO) { client.getIssue(org, run.repo, run.issueNumber) } ?: throw StageFailed("Issue not found")
        val comments = withContext(Dispatchers.IO) { runCatching { client.listComments(org, run.repo, run.issueNumber) }.getOrDefault(emptyList()) }
        // A release run starts from and merges into its release branch; a single run uses the default branch.
        val target = run.baseBranch ?: repoInfo.defaultBranch
        val releaseName = run.baseBranch?.takeIf { run.releaseId != null }?.removePrefix("release/")
        val branch = if (resuming) run.branch ?: throw StageFailed("The run has no branch to retry") else runBranch(run.issueNumber, issue.title, releaseName, run.id)
        // A step retry re-creates the worktree on its own branch; a continuation starts at the tip of the earlier run's branch.
        val startFrom = if (resuming) branch else previous?.branch
        // The agents' working directory; for a workspace run the change is in the target member's worktree inside it.
        val root = workspaces.runDir(owner.username, run.repo, run.id)
        val home = workspaces.home(owner.username)
        val setUp = withContext(Dispatchers.IO) {
            if (run.targetRepo != null) setUpWorkspace(run, owner, id, repoInfo.defaultBranch, target, branch, startFrom)
            else {
                val main = workspaces.ensureMain(id, run.repo)
                val base = workspaces.withRepoLock(owner.username, run.repo) {
                    // Leftovers of runs that have ended (e.g. after a crash) never block a new run; live runs keep theirs.
                    workspaces.reapRunWorktrees(owner.username, run.repo) { it != run.id && keepWorktree(it) }
                    try {
                        workspaces.git.createWorktree(id, main, root, branch, target, home, startFrom)
                    } catch (e: dk.egernet.factory.git.GitException) {
                        if (startFrom == null) throw e
                        throw StageFailed("Could not start from branch $startFrom: ${e.message}")
                    }
                }
                val subs = workspaces.git.updateSubmodules(id, root, home)?.let { listOf(run.repo to it) }.orEmpty()
                WorkspaceSetUp(root, base, parentBlock(owner, run.repo), subs)
            }
        }
        val worktree = setUp.worktree
        val baseRef = setUp.baseRef
        if (!resuming) runs.setBranch(run.id, branch)
        // The sandbox decision is made once, from the base commit (a run cannot approve its own change to the file). A
        // release run reads it from the default branch, as fetched with the clone, not from the release branch it starts
        // from: earlier runs of the release merge into that branch without a human, and approvals are of the default
        // branch's file (docs/ARCHITECTURE.md §5 "The run's decision"). baseRef is `origin/<target>` (or `<target>` for
        // the fake GitHub's local clones), so the same form names the default branch. A workspace run's sandbox is the
        // target member's: its file at the member's base ref, approved for the member repository; for a member release
        // run that is the member's base branch (fetched), never its release branch.
        val memberBase = memberReleaseBase(run)
        if (run.releaseId != null && run.targetRepo != null && memberBase == null) {
            throw StageFailed("The release branch of $org/${run.targetRepo} is not recorded for this release")
        }
        val definitionRef = when {
            memberBase != null -> {
                withContext(Dispatchers.IO) {
                    workspaces.git.fetchBranch(id, workspaces.memberPath(workspaces.mainDir(owner.username, run.repo), run.targetDir!!), memberBase, home)
                }
                baseRef.removeSuffix(target) + memberBase
            }
            run.targetRepo != null || run.baseBranch == null || target == repoInfo.defaultBranch -> baseRef
            else -> baseRef.removeSuffix(target) + repoInfo.defaultBranch
        }
        val sandbox = sandboxes.prepare(run.id, run.repo, worktree, definitionRef, home, definitionRepo = prRepo(run))
        // Images selected for the issue in a chat go into the agents' working directory (docs/ARCHITECTURE.md §6 "Generated images").
        val prepared = withContext(Dispatchers.IO) { images.handOver(runs.get(run.id)!!, root) }
        publish(run.id)
        val ctx = RunContext(
            run.id, run.repo, issue, issueText(issue, comments), branch, baseRef, target, worktree, releaseName, memberBase ?: repoInfo.defaultBranch, sandbox,
            prepared, root = root, gitRepo = prRepo(run), memberDir = run.targetDir, workspaceBlock = setUp.block,
            contextRepos = runs.get(run.id)!!.contextRepos,
        )
        val imageNotes = (if (prepared.isEmpty()) emptyList()
            else listOf("info" to "Prepared images: ${prepared.size} (${prepared.joinToString(", ") { it.meta.name ?: it.meta.purpose }})")) +
            setUp.submoduleErrors.map { (where, out) -> "warn" to "Submodules of `$where` could not be fetched: ${out.trim().takeLast(300)}" }
        val inherited = if (previous == null) 0 else withContext(Dispatchers.IO) {
            workspaces.git.commitsAhead(worktree, baseRef, workspaces.home(owner.username))
        }

        // 1. Spec: written by the spec-writer, or the spec of the run this one retries or continues.
        val spec: String
        val stored = runs.get(run.id)!!.spec?.takeIf { it.isNotBlank() }
        if (stored != null) {
            spec = stored
            if (resuming) {
                bestEffort {
                    withContext(Dispatchers.IO) {
                        client.comment(org, run.repo, run.issueNumber,
                            "**Agern:** retrying the failed step (${stepLabel(step)}) of run `${run.id}` on branch `$branch`.")
                    }
                }
            } else {
                runs.stages(run.id).filter { it.role == "spec-writer" && it.status == "pending" }.forEach {
                    runs.finishStage(it.id, "skipped", null, "Spec reused from run ${previous?.id ?: run.continuesRunId}", emptyList())
                }
                publish(run.id)
                bestEffort {
                    withContext(Dispatchers.IO) {
                        client.comment(org, run.repo, run.issueNumber,
                            "**Agern:** run `${run.id}` continues run `${previous?.id}` from its branch `${previous?.branch}` " +
                                "($inherited commit${if (inherited == 1) "" else "s"}); the spec of that run is reused.")
                    }
                }
            }
        } else {
            if (resuming) throw StageFailed("The run has no spec to retry with")
            setStatus(run.id, RunStatus.SPEC)
            val specRes = stage(run.id, "spec-writer", 0, owner, ctx, specPrompt(ctx),
                disallowed = READ_ONLY_TOOLS, extraTools = dk.egernet.factory.images.ImageService.TOOLS, notes = imageNotes)
            spec = specRes.resultText?.trim().takeUnless { it.isNullOrEmpty() } ?: throw StageFailed("spec-writer produced no spec")
            runs.setSpec(run.id, spec)
            finishStage(run.id, "spec-writer", 0, null, firstParagraph(spec), emptyList())
            withContext(Dispatchers.IO) {
                client.comment(org, run.repo, run.issueNumber, "**Agern: spec ready** (run `${run.id}`)\n\n$spec")
            }
        }

        // 2. Build / review loop
        var round = if (resuming) run.round else 0
        var builderClaudeSession: String? = null
        var builderSessionId: UUID? = null
        if (resuming) {
            // A later fix round resumes the builder conversation of the round whose step failed.
            runs.stages(run.id).lastOrNull { it.role == "builder" && it.round == round }?.sessionId?.let { sid ->
                builderSessionId = sid
                builderClaudeSession = sessions.get(sid)?.claudeSessionId
            }
        }
        var skipBuild = resuming
        var findingsForBuilder: Map<String, Verdict> = emptyMap()
        var lastReviews: Map<String, Verdict>
        while (true) {
            runs.setRound(run.id, round)
            val builderSummary: String
            if (skipBuild) {
                builderSummary = runs.get(run.id)!!.builderSummary ?: ""
                skipBuild = false
            } else {
                setStatus(run.id, RunStatus.BUILDING)
                if (round > 0) {
                    val eff = preferences.effective(owner.id)
                    runs.createStage(run.id, "builder", round, eff.model("builder"))
                    REVIEWER_ROLES.forEach { runs.createStage(run.id, it, round, eff.model(it)) }
                    publish(run.id)
                }
                val prompt = when {
                    round > 0 -> fixPrompt(ctx, round, maxRounds, findingsForBuilder)
                    previous != null -> continuePrompt(ctx, spec, previous, inherited)
                    else -> buildPrompt(ctx, spec)
                }
                val b = stage(run.id, "builder", round, owner, ctx, prompt,
                    resume = builderClaudeSession, parent = builderSessionId,
                    extraTools = (if (sandboxes.enabled) dk.egernet.factory.sandbox.SandboxManager.TOOLS else emptySet()) +
                        dk.egernet.factory.images.ImageService.TOOLS,
                    notes = (ctx.sandbox?.takeIf { sandboxes.enabled }?.let { listOf((if (it.state == "approved") "info" else "warn") to "Sandbox: ${it.reason}") }
                        ?: emptyList()) + (if (round == 0) imageNotes else emptyList()) +
                        (if (round == 0 && previous != null) listOf("info" to "Continues run ${previous.id} from branch ${previous.branch} ($inherited commit(s))") else emptyList()))
                builderClaudeSession = b.claudeSessionId ?: builderClaudeSession
                builderSessionId = b.session.id
                builderSummary = b.resultText?.trim() ?: ""
                withContext(Dispatchers.IO) { submoduleCommitsWarning(root, worktree, home) }?.let { agents.systemEvent(b.session.id, "warn", it) }
                val ahead = withContext(Dispatchers.IO) { workspaces.git.commitsAhead(worktree, baseRef, workspaces.home(owner.username)) }
                if (ahead == 0) {
                    // The builder's own words say why (e.g. the issue is blocked on another one); show them with the failure.
                    val why = firstParagraph(builderSummary).takeIf { it.isNotEmpty() }
                    finishStage(run.id, "builder", round, null, why?.let { "No commits on the branch. $it" } ?: "No commits on the branch", emptyList(), failed = true)
                    throw StageFailed("The builder made no commits on $branch" + (why?.let { ". The builder said: $it" } ?: ""), kind = FailureKind.BLOCKED)
                }
                finishStage(run.id, "builder", round, null, firstParagraph(builderSummary), emptyList())
                runs.setBuilderSummary(run.id, builderSummary)
            }

            withContext(Dispatchers.IO) {
                try {
                    workspaces.git.push(id, worktree, branch, workspaces.home(owner.username))
                } catch (e: Exception) {
                    throw StageFailed("Pushing branch $branch failed: ${e.message}", kind = FailureKind.STEP, step = FailedStep.PUSH)
                }
                run = runs.get(run.id)!!
                if (run.prNumber == null) {
                    // A release run's pull request must not close the issue: that happens when the release is merged.
                    val lead = when {
                        releaseName != null && run.targetRepo != null -> "Issue $org/${run.repo}#${issue.number} · release `$releaseName`"
                        releaseName != null -> "Issue #${issue.number} · release `$releaseName`"
                        run.targetRepo != null -> "Closes $org/${run.repo}#${issue.number}"
                        else -> "Closes #${issue.number}"
                    }
                    val earlier = runs.earlierUnmergedPulls(run.repo, run.issueNumber, run.id, run.targetRepo)
                        .takeIf { it.isNotEmpty() }
                        ?.let { prs -> "Earlier runs of this issue left " + prs.joinToString(", ") { "#$it" } + " open; close them if this one supersedes them.\n\n" }
                        .orEmpty()
                    val continues = runs.get(run.id)!!.continuesRunId?.let { prevId ->
                        val prev = runs.get(prevId)
                        "Continues Agern run `$prevId`" + (prev?.branch?.let { " (branch `$it`)" } ?: "") +
                            (prev?.error?.let { ", which stopped: ${it.lineSequence().first().take(300)}" } ?: "") +
                            ". Its commits are part of this pull request.\n\n"
                    }.orEmpty()
                    val pr = try {
                        client.findOpenPull(org, prRepo(run), branch) ?: client.createPull(
                            org, prRepo(run), branch, target, prTitle(run, issue.title),
                            "$lead\n\nBuilt by Agern run `${run.id}` for @${owner.githubLogin ?: owner.username}.\n\n" + continues + earlier +
                                "<details><summary>Spec</summary>\n\n$spec\n\n</details>",
                        )
                    } catch (e: Exception) {
                        throw StageFailed("Opening the pull request for $branch failed: ${e.message}", kind = FailureKind.STEP, step = FailedStep.PULL_REQUEST)
                    }
                    runs.setPr(run.id, pr.number, pr.htmlUrl)
                    bestEffort { client.comment(org, run.repo, run.issueNumber, "**Agern:** pull request ${pr.htmlUrl} is open; reviews are running.") }
                }
            }
            publish(run.id)

            // Reviews in parallel
            setStatus(run.id, RunStatus.REVIEWING)
            lastReviews = coroutineScope {
                REVIEWER_ROLES.map { role ->
                    async {
                        val r = stage(run.id, role, round, owner, ctx, reviewPrompt(ctx, role, round, spec, builderSummary), schema = Schemas.REVIEW,
                            disallowed = READ_ONLY_TOOLS, extraTools = if (role in SCREENSHOT_ROLES) Screenshots.TOOLS else emptySet())
                        val v = parseVerdict(r, setOf("PASS", "CHANGES"))
                        if (v == null) {
                            finishStage(run.id, role, round, null, "No valid verdict in the agent's output", emptyList(), failed = true)
                            null
                        } else {
                            finishStage(run.id, role, round, v.verdict, v.summary, v.findings)
                            role to v
                        }
                    }
                }.awaitAll()
            }.let { list ->
                if (list.any { it == null }) throw StageFailed("A reviewer did not return a valid verdict")
                list.filterNotNull().toMap()
            }
            withContext(Dispatchers.IO) { bestEffort { screenshotNote(runs.get(run.id)!!, client, round) } }
            withContext(Dispatchers.IO) { bestEffort { uiScreenshotWarning(runs.get(run.id)!!, owner, ctx, client, round) } }
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
        val a = stage(run.id, "approver", round, owner, ctx, approvalPrompt(ctx, spec, lastReviews, round, maxRounds, run.prUrl, run.mergePolicy), schema = Schemas.APPROVAL, disallowed = READ_ONLY_TOOLS)
        val v = parseVerdict(a, setOf("APPROVE", "ESCALATE"))
        if (v == null) {
            finishStage(run.id, "approver", round, null, "No valid verdict in the agent's output", emptyList(), failed = true)
            throw StageFailed("The approver did not return a valid verdict")
        }
        finishStage(run.id, "approver", round, v.verdict, v.summary, emptyList())
        // What the verdict does follows the policy stored on the run, never the project's current one.
        when (runs.get(run.id)!!.mergePolicy) {
            MergePolicy.OWNER_APPROVES -> needsHuman(
                runs.get(run.id)!!, owner,
                if (v.verdict == "APPROVE") "The approver recommends merging: ${v.summary} This project requires the run owner's approval before every merge."
                else "The approver escalated: ${v.summary}",
            )
            MergePolicy.GITHUB -> withContext(Dispatchers.IO) { readyForGitHub(runs.get(run.id)!!, owner, v) }
            else -> if (v.verdict == "APPROVE") {
                withContext(Dispatchers.IO) { merge(runs.get(run.id)!!, owner, approvedBy = null) }
                publish(run.id)
            } else {
                needsHuman(runs.get(run.id)!!, owner, "The approver escalated: ${v.summary}")
            }
        }
    }

    /**
     * Policy `github` after the approver (docs/ARCHITECTURE.md §6 "Merge policy"): requests the project's reviewers on
     * the pull request (best effort, the owner's own login left out), posts the approver's assessment, removes the
     * `needs-human` label, removes the worktree and ends the run `ready`. Nothing is merged and the issue stays open; the
     * follower ([followOnce]) ends the run when the pull request is merged or closed on GitHub. Blocking.
     */
    private fun readyForGitHub(run: RunRecord, owner: UserRecord, v: Verdict) {
        val client = gh(owner)
        val pr = run.prNumber ?: run { needsHuman(run, owner, "There is no pull request to review."); return }
        val settings = settingsOf(run.repo)
        val own = owner.githubLogin
        // GitHub refuses a review request from the pull request's author.
        val logins = settings.reviewers.filter { own == null || !it.equals(own, ignoreCase = true) }
        val teams = settings.teamReviewers
        var note = ""
        if (logins.isNotEmpty() || teams.isNotEmpty()) {
            note = try {
                client.requestReviewers(org, prRepo(run), pr, logins, teams)
                " Reviewers requested: " + (logins.map { "@$it" } + teams.map { "team `$it`" }).joinToString(", ") + "."
            } catch (e: Exception) {
                log.warn("Run {}: requesting reviewers on {} failed: {}", run.id, prRef(run, pr), e.message)
                " Requesting reviewers failed: ${(e.message ?: e.javaClass.simpleName).take(300)}."
            }
        }
        val text = "**Agern: ready for review.** Pull request ${run.prUrl} passed all reviewers. Approver's assessment " +
            "(${v.verdict}): ${v.summary}\n\nThis project merges on GitHub: review and merge the pull request there. Agern does " +
            "not merge it and follows it until it is merged or closed.$note"
        bestEffort { client.comment(org, run.repo, run.issueNumber, text) }
        if (run.targetRepo != null) bestEffort { client.comment(org, prRepo(run), pr, text) }
        bestEffort { client.removeLabel(org, run.repo, run.issueNumber, "needs-human") }
        // The pushed branch and the open pull request stay; the local worktree is no longer needed.
        cleanupWorktree(run.id, merged = false)
        runs.setFailure(run.id, null, null)
        runs.setStatus(run.id, RunStatus.READY, clearError = true)
        publish(run.id)
    }

    /** The result of a run's checkout: the change's [worktree], its [baseRef], the workspace [block] and submodule errors. */
    private data class WorkspaceSetUp(val worktree: Path, val baseRef: String, val block: String, val submoduleErrors: List<Pair<String, String>>)

    /**
     * The base branch of the member repository a member release run is built in (`release_repos.base_branch`): where its
     * release is later merged in that repository. Null for every other run. Blocking.
     */
    private fun memberReleaseBase(run: RunRecord): String? {
        val releaseId = run.releaseId ?: return null
        val tr = run.targetRepo ?: return null
        return releaseStore?.repoBase(releaseId, tr)
    }

    /**
     * Sets up a workspace run's checkout (docs/ARCHITECTURE.md §5 "Workspace projects"): the stored target must still be
     * a declared member the owner can read; the owner's readable members are cloned and checked out beside it, and the
     * repositories checked out are recorded on the run. Blocking; throws [StageFailed].
     */
    private fun setUpWorkspace(
        run: RunRecord, owner: UserRecord, id: AgentIdentity, defaultBranch: String, base: String, branch: String, startFrom: String?,
    ): WorkspaceSetUp {
        val tRepo = run.targetRepo!!
        val tDir = run.targetDir ?: throw StageFailed("The run has no member directory")
        val gone = "Member `$tDir` ($tRepo) is no longer part of the workspace definition"
        val state = try {
            workspaceDefs.lookup(owner, run.repo, strict = true)
        } catch (e: Exception) {
            throw StageFailed("Reading the workspace definition failed: ${e.message}")
        }
        val declared = state?.takeIf { it.valid }?.members?.firstOrNull { it.directory == tDir && it.repository == tRepo } ?: throw StageFailed(gone)
        val resolved = workspaceDefs.resolve(owner, state)
        if (resolved.firstOrNull { it.member == declared }?.accessible != true) throw StageFailed(workspaceDefs.notAccessible(declared).message)
        val target = dk.egernet.factory.agent.MemberCheckout(tDir, tRepo, base, declared.submodule)
        val others = resolved.filter { it.member != declared && it.accessible && it.baseBranch != null }
            .map { dk.egernet.factory.agent.MemberCheckout(it.directory, it.repository, it.baseBranch!!, it.member.submodule) }
        val unreadable = resolved.filter { !it.accessible }
            .map { dk.egernet.factory.agent.LeftOut(it.directory, it.repository, "your GitHub token cannot read it") }
        workspaces.ensureMain(id, run.repo)
        val wc = workspaces.ensureWorkspace(id, run.repo, listOf(target) + others)
        wc.leftOut.firstOrNull { it.directory == tDir }?.let { throw StageFailed("Member `$tDir` ($tRepo) could not be checked out: ${it.reason}") }
        val co = workspaces.withRepoLock(owner.username, run.repo) {
            workspaces.reapRunWorktrees(owner.username, run.repo) { it != run.id && keepWorktree(it) }
            try {
                workspaces.prepareRunCheckout(
                    id, run.repo, run.id, defaultBranch, target, branch, startFrom, others.filter { o -> wc.checkedOut.any { it.directory == o.directory } },
                )
            } catch (e: Exception) {
                if (startFrom != null) throw StageFailed("Could not start from branch $startFrom in member `$tDir`: ${e.message}")
                throw StageFailed("Member `$tDir` ($tRepo) could not be checked out: ${e.message}")
            }
        }
        runs.setContextRepos(run.id, co.members.map { it.repository })
        val left = unreadable + wc.leftOut + co.leftOut
        val block = workspaceBlock(run.repo, run.issueNumber, target, co.members.drop(1), left, inRelease = run.releaseId != null)
        return WorkspaceSetUp(co.worktree, co.baseRef, block, co.submoduleErrors)
    }

    /**
     * After a review round: one comment on the pull request listing the screenshots the reviewers of [round] attached,
     * pointing at the run page (docs/ARCHITECTURE.md §6 "Screenshots"). Nothing for a round without screenshots; the
     * images themselves never go to GitHub. Blocking.
     */
    private fun screenshotNote(run: RunRecord, client: GitHubClient, round: Int) {
        val pr = run.prNumber ?: return
        screenshotNoteText(cfg, run.repo, run.id, round, runs.stages(run.id), screenshots.forRun(run.id))
            ?.let { client.comment(org, prRepo(run), pr, it) }
    }

    /**
     * After a review round: when the round's diff touches UI files ([UiChanges]) and neither the ux nor the ui-design
     * stage of [round] attached a screenshot, records the warning "UI change reviewed without screenshots" on both
     * stages and comments it on the pull request (docs/ARCHITECTURE.md §6 "Screenshots"). A warning only: the run goes
     * on as before. Blocking.
     */
    private fun uiScreenshotWarning(run: RunRecord, owner: UserRecord, ctx: RunContext, client: GitHubClient, round: Int) {
        val stages = runs.stages(run.id).filter { it.round == round && it.role in SCREENSHOT_ROLES }
        if (stages.isEmpty()) return
        val ids = stages.map { it.id }.toSet()
        if (screenshots.forRun(run.id).any { it.stageId in ids }) return
        val home = workspaces.home(owner.username)
        val changed = workspaces.git.changedFiles(ctx.worktree, ctx.baseRef, home) ?: return
        val override = runCatching {
            workspaces.git.readFile(ctx.worktree, ctx.baseRef, UiChanges.UI_FILES, home, UiChanges.MAX_UI_FILES_BYTES)
        }.getOrElse { e -> log.warn("Reading {} of {} failed, using the default UI rules: {}", UiChanges.UI_FILES, run.repo, e.message); null }
        val ui = UiChanges.uiFiles(changed, UiChanges.Rules.parse(override))
        if (ui.isEmpty()) return
        val text = UiChanges.warningText(ui)
        stages.forEach { runs.setStageWarning(it.id, text) }
        publish(run.id)
        val pr = run.prNumber ?: return
        bestEffort { client.comment(org, prRepo(run), pr, uiWarningComment(cfg, run.repo, run.id, round, ui)) }
    }

    private fun stepLabel(step: String?) = when (step) {
        FailedStep.PUSH -> "push"; FailedStep.PULL_REQUEST -> "pull request"; FailedStep.MERGE -> "merge"; else -> step ?: "step"
    }

    /** [targetBranch]: where the pull request merges; [releaseName] is set for a release run. */
    private data class RunContext(
        val runId: UUID, val repo: String, val issue: GhIssue, val issueText: String, val branch: String,
        val baseRef: String, val targetBranch: String, val worktree: Path,
        val releaseName: String? = null, val defaultBranch: String = targetBranch,
        /** The run's sandbox decision; null when sandboxes are switched off. */
        val sandbox: dk.egernet.factory.RunSandboxDto? = null,
        /** Images selected for the issue in a chat, written into the working directory; empty for most runs. */
        val prepared: List<dk.egernet.factory.images.PreparedImage> = emptyList(),
        /** The agents' working directory: [worktree], or the workspace root around it for a workspace run. */
        val root: Path = worktree,
        /** The repository of the branch and pull request: [repo], or the target member's. */
        val gitRepo: String = repo,
        /** The target member's directory under [root] for a workspace run, else null. */
        val memberDir: String? = null,
        /** The workspace block of the task prompts (empty for a single-repository run). */
        val workspaceBlock: String = "",
        /** The member repositories checked out for the run (recorded on its sessions). */
        val contextRepos: List<String> = emptyList(),
    ) {
        /** `git`, or `git -C <member directory>` for a workspace run. */
        val git: String get() = if (memberDir == null) "git" else "git -C $memberDir"

        /** The `<images>` block for the spec-writer's and builder's task prompts; empty (no change at all) without images. */
        val imagesBlock: String
            get() = if (prepared.isEmpty()) "" else dk.egernet.factory.images.ImageService.promptBlock(prepared)

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
                issueNumber = ctx.issue.number, parentSessionId = parent, workDir = ctx.root,
                systemPrompt = prompts.system(role, ctx.repo, s.agentsMayInstallSoftware, workspace = if (ctx.memberDir != null) "" else null),
                prompt = prompt, contextRepos = ctx.contextRepos,
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
            val kind = when {
                res.timedOut -> FailureKind.TIME_LIMIT
                res.usageLimit != null -> FailureKind.USAGE_LIMIT
                res.credentialRejected -> FailureKind.CREDENTIALS
                FailureKind.looksUnreachable(res.error) -> FailureKind.API_UNREACHABLE
                else -> FailureKind.OTHER
            }
            throw StageFailed("$role failed: ${res.error}", res.usageLimit, res.credentialRejected, kind)
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
                "**Agern: needs a human.** $reason\n\nPull request: ${run.prUrl ?: "(none)"}\n\n" + if (run.mergePolicy == MergePolicy.GITHUB) {
                    "This project merges on GitHub: the pull request can be reviewed and merged there; Agern does not merge it."
                } else {
                    "The run owner (@${owner.githubLogin ?: owner.username}) can merge it with `/factory approve ${run.id}` in the Agern chat or the Approve button on the run page."
                },
            )
        }
        runs.setStatus(run.id, RunStatus.NEEDS_HUMAN, reason)
        publish(run.id)
    }

    /**
     * Squash-merges the PR and closes the issue; a release run's issue stays open until the release is merged.
     * On failure, or when its database migrations collide with the target's, the run goes (back) to needs_human.
     */
    private fun merge(run: RunRecord, owner: UserRecord, approvedBy: String?) {
        // Defence in depth (docs/ARCHITECTURE.md §6 "Merge policy"): the run's policy decides, whoever called.
        if (run.mergePolicy == MergePolicy.GITHUB || (run.mergePolicy == MergePolicy.OWNER_APPROVES && approvedBy == null)) {
            log.error("Run {}: refusing to merge under merge policy {} (approved by {})", run.id, run.mergePolicy, approvedBy)
            needsHuman(run, owner, "Not merged: this run's merge policy (${run.mergePolicy}) does not allow it.")
            return
        }
        val client = gh(owner)
        val pr = run.prNumber ?: run { needsHuman(run, owner, "There is no pull request to merge."); return }
        migrationConflict(run, owner, client, pr)?.let { reason ->
            // Not a failed step: merging again cannot help until the branch's migrations change.
            runs.setFailure(run.id, null, null)
            needsHuman(run, owner, reason)
            return
        }
        submoduleProblem(run, owner, client, pr)?.let { p ->
            // A failed merge step: once the submodule commit is pushed, a retry of the merge (or an approval) checks again.
            runs.setFailure(run.id, if (p.code == SubmoduleCheck.MISSING) FailureKind.SUBMODULE_COMMIT_MISSING else FailureKind.STEP, FailedStep.MERGE)
            needsHuman(run, owner, p.text)
            return
        }
        try {
            client.squashMerge(org, prRepo(run), pr, prTitle(run, run.issueTitle))
        } catch (e: Exception) {
            // A retry of the run can retry the merge (docs/ARCHITECTURE.md §6 "Retrying a run"); so can the owner's approval.
            runs.setFailure(run.id, null, FailedStep.MERGE)
            needsHuman(run, owner, "Merging pull request ${prRef(run, pr)} failed: ${e.message}")
            return
        }
        bestEffort { client.removeLabel(org, run.repo, run.issueNumber, "needs-human") }
        val approval = approvedBy?.let { " (approved by @$it)" } ?: " (approved by the approver agent)"
        if (run.releaseId != null && run.baseBranch != null && run.targetRepo != null) {
            bestEffort {
                client.comment(org, run.repo, run.issueNumber,
                    "**Agern: merged** pull request ${prRef(run, pr)} into `${run.baseBranch}` of $org/${run.targetRepo}$approval. " +
                        "The issue stays open until the release is released.")
            }
        } else if (run.releaseId != null && run.baseBranch != null) {
            val defaultBranch = runCatching { client.getRepo(org, run.repo)?.defaultBranch }.getOrNull() ?: "main"
            bestEffort {
                client.comment(org, run.repo, run.issueNumber,
                    "**Agern: merged** pull request #$pr into `${run.baseBranch}`$approval. " +
                        "The issue stays open until the release is merged into `$defaultBranch`.")
            }
        } else {
            bestEffort { client.comment(org, run.repo, run.issueNumber, "**Agern: merged** pull request ${prRef(run, pr)}$approval.") }
            bestEffort { client.closeIssue(org, run.repo, run.issueNumber) }
        }
        cleanupWorktree(run.id, merged = true)
        // Before the run counts as merged, so that its outcome is on the issue when the run settles; never fails the run.
        if (run.targetRepo != null && run.releaseId == null) {
            // Single runs merge on approval (the approver's or the owner's): the pointer bump follows the same way.
            bumpPointerSafely(run, client, pr, autoMerge = true)
        }
        runs.setFailure(run.id, null, null)
        runs.setStatus(run.id, RunStatus.MERGED, clearError = true)
    }

    /** [bumpSubmodulePointer] that never fails: a failure is logged and reported on the issue and in the transcript. */
    private fun bumpPointerSafely(run: RunRecord, client: GitHubClient, pr: Int, autoMerge: Boolean) {
        try {
            bumpSubmodulePointer(run, client, pr, autoMerge)
        } catch (e: Exception) {
            log.warn("Run {}: bumping the submodule pointer failed: {}", run.id, e.message)
            pointerNotice(run, client, "warn", "**Agern: submodule pointer not bumped.** Moving `${run.targetDir}` in " +
                "$org/${run.repo} to the merge of ${prRef(run, pr)} failed: ${e.message?.take(300)}. Bump it by hand.")
        }
    }

    // ------------------------------------------------------------ following pull requests on GitHub (§6 "Merge policy")

    /** How often [startFollower] runs a round of [followOnce]; tests shorten it or call [followOnce] directly. */
    @Volatile var followMillis = 60_000L
    private val followLock = Mutex()

    /** Every [followMillis], one round of [followOnce], in the services' scope (stopping them cancels it). */
    fun startFollower(): Job = scope.launch {
        while (isActive) {
            delay(followMillis)
            try {
                followOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Following pull requests on GitHub failed: {}", e.message)
            }
        }
    }

    /**
     * One round of the follower: every run that has a pull request and is `ready`, or `needs_human` under policy
     * `github`, has its pull request read once with its owner's token. Merged → `merged` (on GitHub, by whom GitHub
     * says); closed → `cancelled`; open or unreadable → unchanged (tried again next round). One run's failure never stops
     * the round.
     */
    suspend fun followOnce() = followLock.withLock {
        for (run in withContext(Dispatchers.IO) { runs.followable() }) {
            try {
                follow(run)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Following the pull request of run {} failed: {}", run.id, e.message)
            }
        }
    }

    private suspend fun follow(run: RunRecord) {
        val pr = run.prNumber ?: return
        val owner = users.byId(run.ownerId) ?: run { log.warn("Run {}: its owner no longer exists; not followed", run.id); return }
        val client = access.client(owner) ?: run { log.info("Run {}: its owner has no GitHub token; not followed now", run.id); return }
        val info = withContext(Dispatchers.IO) { client.getPull(org, prRepo(run), pr) }
            ?: run { log.info("Run {}: pull request {} could not be read", run.id, prRef(run, pr)); return }
        if (info.state == "open" && !info.merged) return
        val merged = info.merged
        val by = info.mergedBy?.login
        val closedText = "Pull request ${prRef(run, pr)} was closed on GitHub without being merged."
        val changed = when (run.status) {
            RunStatus.READY -> withContext(Dispatchers.IO) {
                if (merged) runs.readyToMerged(run.id, by) else runs.readyToCancelled(run.id, closedText)
            }
            RunStatus.NEEDS_HUMAN -> endRun(run.id, merged, proceed = { it.status == RunStatus.NEEDS_HUMAN }) {
                if (merged) {
                    runs.setMergedOnGitHub(run.id, by)
                    runs.setFailure(run.id, null, null)
                    runs.setStatus(run.id, RunStatus.MERGED, clearError = true)
                } else {
                    runs.setStatus(run.id, RunStatus.CANCELLED, closedText)
                }
            }
            else -> false
        }
        if (!changed) return
        log.info("Run {}: pull request {} was {} on GitHub", run.id, prRef(run, pr), if (merged) "merged" else "closed")
        withContext(Dispatchers.IO) {
            bestEffort { client.removeLabel(org, run.repo, run.issueNumber, "needs-human") }
            // Agern did not merge it: the pointer bump is opened for a human, never merged.
            if (merged && run.targetRepo != null && run.releaseId == null) bumpPointerSafely(run, client, pr, autoMerge = false)
        }
        publish(run.id)
    }

    /**
     * After the pull request [pr] of a workspace run whose target is a submodule merged (docs/ARCHITECTURE.md §6
     * "Workspace runs"): a commit on a branch of the workspace repository moves the gitlink at the target's directory to
     * the merge commit, opened as a pull request against the default branch and squash-merged when [autoMerge]. The
     * merge commit must exist in the member repository. A merge that fails (e.g. the default branch moved and the same
     * gitlink changed) is retried once from the new tip of the default branch; after that the pull request is left
     * open. The outcome goes to the workspace issue and the run's transcript. Nothing for other runs. Blocking.
     */
    private fun bumpSubmodulePointer(run: RunRecord, client: GitHubClient, pr: Int, autoMerge: Boolean) {
        val owner = users.byId(run.ownerId) ?: return
        val member = runCatching { workspaceDefs.lookup(owner, run.repo) }.getOrNull()?.takeIf { it.valid }?.members
            ?.firstOrNull { it.directory == run.targetDir && it.repository == run.targetRepo && it.submodule } ?: return
        val ws = run.repo
        val dir = member.directory
        val sha = client.getPull(org, member.repository, pr)?.mergeCommitSha
            ?: throw IllegalStateException("GitHub did not report the merge commit of ${prRef(run, pr)}")
        // The merge guard (#90): never point the workspace at a commit its member repository does not have.
        if (!client.commitExists(org, member.repository, sha)) {
            pointerNotice(run, client, "warn", "**Agern: submodule pointer not bumped.** The merge commit `${sha.take(12)}` of " +
                "${prRef(run, pr)} is not in $org/${member.repository}, so `$dir` in $org/$ws was left where it is.")
            return
        }
        val defaultBranch = client.getRepo(org, ws)?.defaultBranch ?: throw IllegalStateException("$org/$ws is not readable")
        if (client.gitlinkAt(org, ws, dir, defaultBranch) == sha) {
            pointerNotice(run, client, "info", "**Agern: submodule pointer** `$dir` in $org/$ws already points at `${sha.take(12)}`.")
            return
        }
        val branch = "factory/bump-${dir.replace('/', '-')}-${run.id.toString().take(8)}"
        val title = "Bump $dir to ${sha.take(12)} ($org/${member.repository}#$pr)"
        val body = "Moves the submodule `$dir` to $org/${member.repository}@$sha, the merge of $org/${member.repository}#$pr " +
            "for $org/$ws#${run.issueNumber}.\n\nOpened by Agern for run `${run.id}`."
        var pull: dk.egernet.factory.github.GhPull? = null
        var lastError: String? = null
        for (attempt in 0..1) {
            // From the current tip of the default branch each time: a retry rebases the bump onto what moved meanwhile.
            val tip = client.branchSha(org, ws, defaultBranch) ?: throw IllegalStateException("Branch $defaultBranch of $org/$ws not found")
            val commit = client.createGitlinkCommit(org, ws, tip, dir, sha, title)
            if (client.branchSha(org, ws, branch) == null) client.createBranch(org, ws, branch, commit)
            else client.forceUpdateBranch(org, ws, branch, commit)
            val p = pull ?: client.findOpenPull(org, ws, branch) ?: client.createPull(org, ws, branch, defaultBranch, title, body)
            pull = p
            if (!autoMerge) {
                pointerNotice(run, client, "info", "**Agern: submodule pointer** `$dir` → `${sha.take(12)}`: pull request ${p.htmlUrl} " +
                    "is open for a human to merge.")
                return
            }
            // The merge guard of every run (docs/ARCHITECTURE.md §6 "Submodule pointers") for the pointer Agern moved itself.
            bumpProblem(owner, ws, defaultBranch, branch, "$org/$ws#${p.number}")?.let { problem ->
                pointerNotice(run, client, "warn", "**Agern: submodule pointer** `$dir` → `${sha.take(12)}`: pull request ${p.htmlUrl} " +
                    "was not merged. ${problem.text}")
                return
            }
            try {
                client.squashMerge(org, ws, p.number, title)
                bestEffort { client.deleteBranch(org, ws, branch) }
                pointerNotice(run, client, "info", "**Agern: submodule pointer** `$dir` → `${sha.take(12)}`: merged pull request ${p.htmlUrl}.")
                return
            } catch (e: Exception) {
                lastError = e.message
                log.info("Run {}: merging pointer bump #{} failed (attempt {}): {}", run.id, p.number, attempt + 1, e.message)
            }
        }
        pointerNotice(run, client, "warn", "**Agern: submodule pointer** `$dir` → `${sha.take(12)}`: pull request ${pull?.htmlUrl} " +
            "could not be merged (${lastError?.take(300)}); it is left open for a human.")
    }

    /**
     * [SubmoduleCheck] of the pointer bump on [branch] of workspace [ws] against [target], in the owner's workspace clone;
     * null when it may be merged. Fails closed like the runs' check. Blocking; never throws.
     */
    private fun bumpProblem(owner: UserRecord, ws: String, target: String, branch: String, prRef: String): SubmoduleCheck.Problem? {
        val unchecked = SubmoduleCheck.Problem(SubmoduleCheck.UNCHECKED, "Its submodule pointer could not be checked.")
        return try {
            val home = workspaces.home(owner.username)
            val token = access.githubToken(owner)
            val links = workspaces.withRepoLock(owner.username, ws) {
                workspaces.git.changedGitlinks(workspaces.mainDir(owner.username, ws), target, branch, home, token)
            } ?: return unchecked
            SubmoduleCheck.check(links, target, prRef, has = { workspaces.git.remoteHasCommit(it.url!!, it.sha, home, token) })
        } catch (e: Exception) {
            log.warn("Checking the pointer bump {} of {} failed: {}", branch, ws, e.message)
            unchecked
        }
    }

    /** The outcome of a pointer bump: a comment on the workspace issue and an event in the run's latest transcript. */
    private fun pointerNotice(run: RunRecord, client: GitHubClient, level: String, text: String) {
        bestEffort { client.comment(org, run.repo, run.issueNumber, text) }
        runs.stages(run.id).lastOrNull { it.sessionId != null }?.sessionId?.let { sid ->
            bestEffort { agents.systemEvent(sid, level, text.replace("**", "")) }
        }
    }

    /**
     * Why pull request #[pr] must not be merged because of its database migrations (docs/ARCHITECTURE.md §6 "Database
     * migrations"), or null. Compares the run's branch with its target in the owner's clone, with git. A check that
     * cannot be made is logged and lets the merge go ahead: CI is the strict gate. Blocking; never throws.
     */
    private fun migrationConflict(run: RunRecord, owner: UserRecord, client: GitHubClient, pr: Int): String? = try {
        val branch = run.branch
        val target = run.baseBranch ?: client.getRepo(org, run.repo)?.defaultBranch
        if (branch == null || target == null) null else {
            val cmp = workspaces.withRepoLock(owner.username, run.repo) {
                workspaces.git.compareBranches(
                    gitDirOf(run, owner.username), target, branch, workspaces.home(owner.username),
                    access.githubToken(owner), MigrationCheck::rootOf,
                )
            }
            if (cmp == null) log.warn("Run {}: the database migrations of {} could not be compared with {}", run.id, branch, target)
            cmp?.let { MigrationCheck.problems(it.baseFiles, it.changes) }?.takeIf { it.isNotEmpty() }
                ?.let { MigrationCheck.runReason(target, pr, it, prRef(run, pr)) }
        }
    } catch (e: Exception) {
        log.warn("Run {}: checking the database migrations failed, merging without the check: {}", run.id, e.message)
        null
    }

    /**
     * Why pull request #[pr] must not be merged because of its submodule pointers (docs/ARCHITECTURE.md §6 "Submodule
     * pointers"), or null. Every pointer the run's branch moves must name a commit its submodule's remote has. Fails
     * closed: a check that cannot be made refuses the merge too. Blocking; never throws.
     */
    private fun submoduleProblem(run: RunRecord, owner: UserRecord, client: GitHubClient, pr: Int): SubmoduleCheck.Problem? {
        val unchecked = SubmoduleCheck.Problem(
            SubmoduleCheck.UNCHECKED,
            "Submodule pointers not checked: the submodule pointers of pull request ${prRef(run, pr)} could not be compared " +
                "with its target. Nothing was merged. Approve again (or retry the merge) to check again.",
        )
        return try {
            val branch = run.branch ?: return null
            val target = run.baseBranch ?: client.getRepo(org, run.repo)?.defaultBranch ?: return unchecked
            val home = workspaces.home(owner.username)
            val token = access.githubToken(owner)
            val links = workspaces.withRepoLock(owner.username, run.repo) {
                workspaces.git.changedGitlinks(gitDirOf(run, owner.username), target, branch, home, token)
            } ?: return unchecked.also { log.warn("Run {}: the submodule pointers of {} could not be compared with {}", run.id, branch, target) }
            SubmoduleCheck.check(
                links, target, prRef(run, pr), has = { workspaces.git.remoteHasCommit(it.url!!, it.sha, home, token) },
                onError = { c, e -> log.warn("Run {}: checking submodule {} at {} failed: {}", run.id, c.path, c.sha, e.message) },
            )
        } catch (e: Exception) {
            log.warn("Run {}: checking the submodule pointers failed, not merging: {}", run.id, e.message)
            unchecked
        }
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
        // Member worktrees of a workspace run first, then the run's own worktree.
        var bundles = emptyList<dk.egernet.factory.agent.SubmoduleBundle>()
        bestEffort { bundles = workspaces.removeRunCheckout(owner.username, run.repo, run.id, keepUnpushed = !merged) }
        if (bundles.isEmpty()) return
        // Told in the transcript of the run's last builder (docs/ARCHITECTURE.md §6 "Submodule pointers").
        val builder = runs.stages(runId).filter { it.role == "builder" && it.sessionId != null }.maxByOrNull { it.round }?.sessionId ?: return
        for (b in bundles) {
            bestEffort {
                agents.systemEvent(
                    builder, "warn",
                    "Unpushed submodule commits saved: submodule `${b.path}` had ${b.commits} commit(s) up to `${b.head}` that are on " +
                        "none of its remote branches, and the run's checkout was removed. They are saved in the git bundle " +
                        "`${b.file}` on the Agern server. To recover them, run `git fetch <bundle>` in a clone of the submodule " +
                        "(`git -C ${b.path} fetch ${b.file}`) and push `FETCH_HEAD` to a branch of its repository.",
                )
            }
        }
    }

    /**
     * The `warn` text for submodules in which the builder committed (docs/ARCHITECTURE.md §6 "Submodule pointers"): in the
     * run's checkout [root] and its target worktree [worktree] (the same directory outside workspaces; never the target
     * itself when it is a submodule of the root); null when none.
     */
    private fun submoduleCommitsWarning(root: Path, worktree: Path, home: Path): String? {
        // A submodule target's worktree is a submodule of the root too: its commits are the run's own (pushed by Agern).
        val target = if (worktree == root) null else root.relativize(worktree).toString()
        val found = workspaces.git.unpushedSubmodules(root, home).filter { it.path != target }.map { it.path to it.commits } +
            if (worktree == root) emptyList() else workspaces.git.unpushedSubmodules(worktree, home).map { "${root.relativize(worktree)}/${it.path}" to it.commits }
        if (found.isEmpty()) return null
        return "Commits inside submodules: " + found.joinToString(", ") { (path, n) -> "`$path` ($n commit(s))" } +
            " on none of the submodule's remote branches. Agern pushes only the run's own branch, so these commits are not " +
            "published, and a pull request whose submodule pointer names them cannot be merged. Revert the submodule change, " +
            "or push it to the submodule's repository first."
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
        |${c.workspaceBlock}${c.issueText}${c.imagesBlock}
        |${if (c.memberDir == null) "Your working directory is a checkout of the repository at `${c.baseRef}`." else "The target member `${c.memberDir}` is checked out at `${c.baseRef}`."} Explore it (read-only) before writing.
        |Your final message must be the spec in Markdown and nothing else: it is posted verbatim as a comment on the issue
        |and handed to the builder.
    """.trimMargin()

    private fun buildPrompt(c: RunContext, spec: String) = """
        |Implement issue #${c.issue.number} in ${org}/${c.repo} according to the spec below.
        |
        |${c.workspaceBlock}${worktreeSentence(c)} on branch `${c.branch}`, created from `${c.baseRef}`. Commit your work to this branch.
        |Do not push, open pull requests or merge: the factory does that after you finish.
        |${migrationLine(c)}
        |${sandboxLine(c)}
        |${c.releaseNote}${c.issueText}${c.imagesBlock}
        |<spec>
        |$spec
        |</spec>
        |
        |When you are done, end with a short summary of what you changed, how you verified it, and anything left open.
    """.trimMargin()

    /** Where the builder's worktree is: the working directory, or the target member's directory of a workspace run. */
    private fun worktreeSentence(c: RunContext) =
        if (c.memberDir == null) "You are in a git worktree" else "The target member `${c.memberDir}` is a git worktree"

    /** Round 0 of a run that continues [previous] from its commits (docs/ARCHITECTURE.md §6 "Retrying a run"). */
    private fun continuePrompt(c: RunContext, spec: String, previous: RunRecord, inherited: Int) = buildString {
        append("Continue the implementation of issue #${c.issue.number} in ${org}/${c.repo} according to the spec below.\n\n")
        append("An earlier run of this issue (`${previous.id}`) did not finish. ")
        append("It stopped with: ${previous.error?.trim()?.take(1500) ?: "(no reason recorded)"}\n\n")
        append(c.workspaceBlock)
        append("${worktreeSentence(c)} on branch `${c.branch}`. It starts at the tip of that run's branch `${previous.branch}`, ")
        append("so it already holds $inherited commit${if (inherited == 1) "" else "s"} on top of `${c.baseRef}`. ")
        append("Do not redo that work. First find out what exists: `${c.git} log ${c.baseRef}..HEAD` (a commit message may hold a hand-over ")
        append("note from the previous builder), `${c.git} diff ${c.baseRef}...HEAD` and `${c.git} status`. Then finish what is missing, ")
        append("run the checks the previous builder may not have completed, and commit. If `${c.baseRef}` has moved on since ")
        append("(`${c.git} log HEAD..${c.baseRef}`), merge it in and resolve any conflicts.\n")
        append("Do not push, open pull requests or merge: the factory does that after you finish.\n")
        append(migrationLine(c)).append('\n')
        append(sandboxLine(c)).append('\n')
        previous.builderSummary?.takeIf { it.isNotBlank() }?.let {
            append("<previous_builder_summary>\n").append(it.take(4000)).append("\n</previous_builder_summary>\n\n")
        }
        append(c.releaseNote).append(c.issueText).append(c.imagesBlock)
        append("<spec>\n").append(spec).append("\n</spec>\n\n")
        append("When you are done, end with a short summary of what the branch now contains (the earlier commits and yours), ")
        append("how you verified it, and anything left open.")
    }

    private fun fixPrompt(c: RunContext, round: Int, maxRounds: Int, changes: Map<String, Verdict>) = buildString {
        append("The reviewers requested changes. This is fix round $round of at most $maxRounds.\n")
        append(c.workspaceBlock)
        append("Address every finding below on the same branch (`${c.branch}`), re-run the relevant checks, and commit. ")
        append("If you are convinced a finding is wrong, do not change the code for it; explain why in your final summary ")
        append("so the reviewers see your reasoning.\n")
        append(migrationLine(c)).append('\n')
        append(sandboxLine(c)).append('\n')
        for ((role, v) in changes) {
            append("### Reviewer: $role — ${v.verdict}\n${v.summary}\n")
            v.findings.forEach { f -> append("- [${f.severity}] ${f.file ?: "(general)"}: ${f.detail}\n") }
            append('\n')
        }
        append("End with a short summary of what you changed for each finding.")
    }

    /**
     * The builder's rule for database migration versions, naming the branch the pull request merges into
     * (docs/ARCHITECTURE.md §6 "Database migrations"); a release run's versions must also clear the default branch.
     */
    private fun migrationLine(c: RunContext): String = buildString {
        append("Database migrations: the pull request merges into `${c.targetBranch}`. If the change adds a versioned migration ")
        append("(e.g. Flyway `V<n>__name.sql`), take its version from `${c.baseRef}` as it is now (fetch it first): the next version ")
        append("above every migration there. Check it again before you finish and after any rebase or merge of `${c.targetBranch}`, ")
        append("and never change a migration that exists there.")
        if (c.targetBranch != c.defaultBranch) {
            append(" The release is later merged into `${c.defaultBranch}`, so the version must also be above every migration on ")
            append("`${c.defaultBranch}`.")
        }
        append('\n')
    }

    /** One paragraph for the builder about the run's sandbox (empty line when sandboxes are off). */
    private fun sandboxLine(c: RunContext): String {
        val sb = c.sandbox?.takeIf { sandboxes.enabled } ?: return ""
        return if (sb.state == "approved") {
            "\nSandbox: this run has one (image `${sb.image}`). Build and test inside it with `factory_sandbox_exec` instead of on the host " +
                "(long commands: `background: true`, then `factory_sandbox_wait`); `factory_sandbox_info` lists its services and limits.\n" +
                (if (sb.platforms.isEmpty()) "" else "Platforms: ${sb.platforms.joinToString(", ") { "`$it`" }} (other images; " +
                    "`factory_sandbox_exec` with `platform`). Run the test suite in the default image and on every platform before you finish, " +
                    "and report the result per platform in your summary.\n")
        } else {
            "\nSandbox: none for this run (${sb.reason}). Build and test on the host as usual.\n"
        }
    }

    /** [followUp]: the follow-up round number when this reviews changes from the conversation ([builderSummary] is then the advisor's). */
    private fun reviewPrompt(c: RunContext, role: String, round: Int, spec: String, builderSummary: String, followUp: Int? = null): String {
        val after = when {
            followUp != null -> " (after follow-up round $followUp: changes the run owner requested in a conversation)"
            round > 0 -> " (after fix round $round)"
            else -> ""
        }
        val tag = if (followUp != null) "advisor_summary" else "builder_summary"
        return """
        |Review the changes on branch `${c.branch}` for issue #${c.issue.number} in ${org}/${c.repo}$after.
        |Your focus: **$role** (see your role instructions). You cannot edit files.
        |
        |${c.workspaceBlock}Inspect the change with `${c.git} diff ${c.baseRef}...HEAD`, `${c.git} log ${c.baseRef}..HEAD` and by reading the code; run tests or
        |the app where that helps your review.
        |${if (role in SCREENSHOT_ROLES) uiReviewNote(c) else ""}
        |${c.releaseNote}${c.issueText}
        |<spec>
        |$spec
        |</spec>
        |
        |<$tag>
        |${builderSummary.ifEmpty { "(none)" }}
        |</$tag>
        |
        |Answer through the structured output: verdict `PASS` or `CHANGES`, a summary, and findings
        |(severity blocker|major|minor|nit, file or null, detail). Use `CHANGES` only for findings that must be fixed before merge.
    """.trimMargin()
    }

    /** For the ux and ui-design reviewers: where to start, and the port that is never theirs (docs/ARCHITECTURE.md §6 "Screenshots"). */
    private fun uiReviewNote(c: RunContext) =
        "If the change touches a UI, run the app and attach screenshots as your role instructions say (read " +
            "`${c.memberDir?.let { "$it/" }.orEmpty()}.agern/preview.md` " +
            "first if the repository has one). Agern itself listens on port ${cfg.port} on this host: never start anything on " +
            "that port, never stop or call that server, and never use a production service.\n"

    private fun approvalPrompt(
        c: RunContext, spec: String, reviews: Map<String, Verdict>, round: Int, maxRounds: Int, prUrl: String?, policy: String,
    ) = buildString {
        append("Decide whether the pull request for issue #${c.issue.number} in ${org}/${c.repo} may be squash-merged into `${c.targetBranch}`.\n")
        append(c.releaseNote)
        append(policyParagraph(c, policy)).append('\n')
        append("Branch `${c.branch}`; pull request ${prUrl ?: "(none)"}; fix rounds used: $round of $maxRounds.\n")
        append(c.workspaceBlock)
        append("Inspect the change with `${c.git} diff ${c.baseRef}...HEAD`. You cannot edit files.\n\n")
        append(c.issueText).append("<spec>\n").append(spec).append("\n</spec>\n\n<reviews>\n")
        for ((role, v) in reviews) {
            append("### $role — ${v.verdict}\n${v.summary}\n")
            v.findings.forEach { f -> append("- [${f.severity}] ${f.file ?: "(general)"}: ${f.detail}\n") }
            append('\n')
        }
        append("</reviews>\n\nAnswer through the structured output: verdict `APPROVE` or `ESCALATE` and a summary.")
    }

    /** What the approver's verdict does under the run's merge [policy] (docs/ARCHITECTURE.md §6 "Merge policy"). */
    private fun policyParagraph(c: RunContext, policy: String): String = when (policy) {
        MergePolicy.OWNER_APPROVES ->
            "Merge policy: the run owner approves every merge. You cannot merge: your verdict is a recommendation to the owner. " +
                "`APPROVE` = you recommend merging; `ESCALATE` = you recommend not merging as it stands. Write the summary for the " +
                "owner: what you checked, the risks, and what they should look at before merging."
        MergePolicy.GITHUB ->
            "Merge policy: merged on GitHub by the team. Agern never merges this pull request: your verdict is a recommendation " +
                "that is posted for the human reviewers. `APPROVE` = you recommend merging; `ESCALATE` = you recommend that the " +
                "reviewers look closely before merging. Write the summary for those reviewers."
        else -> if (c.releaseName != null) {
            "Merge policy: automatic. `APPROVE` squash-merges the pull request into the release branch `${c.targetBranch}` " +
                "immediately, without a human looking at it (the issue stays open until the release is merged); `ESCALATE` asks " +
                "the run owner to decide."
        } else {
            "Merge policy: automatic. `APPROVE` squash-merges the pull request and closes the issue immediately, without a human " +
                "looking at it; `ESCALATE` asks the run owner to decide."
        }
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

        fun firstParagraph(s: String): String = s.trim().split(Regex("\n\\s*\n")).first().take(500)

        /**
         * The pull request note for the screenshots of review [round] (null when there are none): the count, a pointer to
         * the run page ([Config.pageRef], so an internal address is never published) and one line per screenshot,
         * reviewers in [REVIEWER_ROLES] order, each in attach order. Captions are single-line already (sanitised on attach).
         */
        /** The pull request comment for [UiChanges.WARNING_TITLE] in review [round]. */
        fun uiWarningComment(cfg: Config, repo: String, runId: UUID, round: Int, uiFiles: List<String>): String {
            val what = if (round == 0) "build" else "fix round $round"
            val ref = cfg.pageRef("p/$repo/runs/$runId", "the page of run `$runId`")
            val n = uiFiles.size
            val list = uiFiles.take(10).joinToString("\n") { "- `$it`" } + if (n > 10) "\n- … and ${n - 10} more" else ""
            return "**Agern: warning — ${UiChanges.WARNING_TITLE}** ($what). This change touches $n UI " +
                "${if (n == 1) "file" else "files"}, but neither the ux nor the ui-design reviewer attached a screenshot, so " +
                "nobody has looked at the result yet. Their summaries on $ref should say what they tried. The run is not " +
                "failed for this.\n\n$list"
        }

        fun screenshotNoteText(
            cfg: Config, repo: String, runId: UUID, round: Int, stages: List<StageRecord>, shots: List<dk.egernet.factory.db.ScreenshotMeta>,
        ): String? {
            val byStage = shots.groupBy { it.stageId }
            val lines = REVIEWER_ROLES.flatMap { role ->
                stages.filter { it.role == role && it.round == round }.flatMap { st -> byStage[st.id].orEmpty().map { role to it.caption } }
            }
            if (lines.isEmpty()) return null
            val n = lines.size
            val what = if (round == 0) "build" else "fix round $round"
            val ref = cfg.pageRef("p/$repo/runs/$runId", "the page of run `$runId`")
            return "**Agern: screenshots** — the reviewers attached $n ${if (n == 1) "screenshot" else "screenshots"} of this change " +
                "($what). See them on $ref:\n\n" + lines.joinToString("\n") { (role, caption) -> "- **$role:** $caption" }
        }
    }
}
