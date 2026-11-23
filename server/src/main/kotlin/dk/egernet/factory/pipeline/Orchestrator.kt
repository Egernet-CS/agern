package dk.egernet.factory.pipeline

import dk.egernet.factory.AppJson
import dk.egernet.factory.Config
import dk.egernet.factory.FailedStep
import dk.egernet.factory.FailureKind
import dk.egernet.factory.RetryMode
import dk.egernet.factory.RetryOptionDto
import dk.egernet.factory.RetryOptionsDto
import dk.egernet.factory.FindingDto
import dk.egernet.factory.REVIEWER_ROLES
import dk.egernet.factory.RunStatus
import dk.egernet.factory.agent.AgentIdentity
import dk.egernet.factory.agent.AgentManager
import dk.egernet.factory.agent.EventBus
import dk.egernet.factory.agent.FeedItem
import dk.egernet.factory.agent.Prompts
import dk.egernet.factory.agent.StageAgentRequest
import dk.egernet.factory.agent.StageAgentResult
import dk.egernet.factory.agent.UsageLimit
import dk.egernet.factory.agent.Workspaces
import dk.egernet.factory.conflict
import dk.egernet.factory.db.RunRecord
import dk.egernet.factory.db.RunStore
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
    private val sessions: dk.egernet.factory.db.SessionStore,
) {
    private val log = LoggerFactory.getLogger(Orchestrator::class.java)
    private val jobs = ConcurrentHashMap<UUID, Job>()
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
    suspend fun startReleaseRun(
        owner: UserRecord, repo: String, issueNumber: Int, releaseId: UUID, baseBranch: String, continuesFrom: RunRecord? = null,
    ): RunRecord = create(owner, repo, issueNumber, null, releaseId, baseBranch, continuesFrom)

    /**
     * [continuesFrom]: a failed run whose branch the new run's worktree starts from and whose spec it reuses
     * (docs/ARCHITECTURE.md §6 "Retrying a run").
     */
    private suspend fun create(
        owner: UserRecord, repo: String, issueNumber: Int, startedBySession: UUID?, releaseId: UUID?, baseBranch: String?,
        continuesFrom: RunRecord? = null,
    ): RunRecord {
        if (owner.claudeCredEnc == null) throw unprocessable("Register a Claude credential in Settings first", "claude_credential_required")
        access.requireVisible(owner, repo)
        val client = access.requireClient(owner)
        val issue = withContext(Dispatchers.IO) { client.getIssue(org, repo, issueNumber) } ?: throw notFound("Issue #$issueNumber not found")
        // The starting user's own choices (else the admin defaults), fixed for this run: max rounds now, models per stage.
        val eff = preferences.effective(owner.id)
        val run = runs.createIfNoActive(
            repo, issueNumber, issue.title, issueKindFromLabels(issue.labels.map { it.name }), eff.maxFixRounds, owner.id, startedBySession,
            releaseId, baseBranch, continuesFrom?.id, continuesFrom?.spec,
        ) ?: throw conflict("Issue #$issueNumber already has an active run", "active_run_exists")
        for (role in listOf("spec-writer", "builder") + REVIEWER_ROLES + "approver") runs.createStage(run.id, role, 0, eff.model(role))
        publish(run.id)
        launchJob(run.id) { execute(run.id) }
        return runs.get(run.id)!!
    }

    private fun launchJob(runId: UUID, block: suspend () -> Unit) {
        val job = scope.launch { block() }
        jobs[runId] = job
        job.invokeOnCompletion { jobs.remove(runId) }
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
            workspaces.git.branchCommits(id, workspaces.mainDir(owner.username, run.repo), run.branch, base, workspaces.home(owner.username))
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
            if (run.prNumber == null) throw conflict("Run has no pull request to merge", "retry_mode_unavailable")
            runs.setStatus(run.id, RunStatus.APPROVING, clearError = true)
            publish(run.id)
            // The approver approved before the merge failed: merging again is its decision; otherwise it is the owner's.
            val approverApproved = runs.stages(run.id).lastOrNull { it.role == "approver" }?.verdict == "APPROVE"
            launchJob(run.id) {
                withContext(Dispatchers.IO) { merge(runs.get(run.id)!!, owner, approvedBy = if (approverApproved) null else owner.username) }
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

    /** Owner only, only in needs_human: squash-merge the PR and close the issue. */
    suspend fun approve(user: UserRecord, runId: UUID): RunRecord {
        val run = runs.get(runId) ?: throw notFound("Run not found")
        if (run.ownerId != user.id) throw forbidden("Only the owner of this run can approve it")
        if (run.status != RunStatus.NEEDS_HUMAN) throw conflict("Run is ${run.status}, not needs_human")
        if (run.prNumber == null) throw conflict("Run has no pull request to merge")
        runs.setStatus(runId, RunStatus.APPROVING, clearError = true)
        publish(runId)
        withContext(Dispatchers.IO) { merge(runs.get(runId)!!, user, approvedBy = user.username) }
        publish(runId)
        settled(runId)
        return runs.get(runId)!!
    }

    /** Owner or admin. */
    suspend fun cancel(user: UserRecord, runId: UUID): RunRecord {
        val run = runs.get(runId) ?: throw notFound("Run not found")
        if (run.ownerId != user.id && !user.isAdmin) throw forbidden("Only the owner or an admin can cancel this run")
        if (run.status in RunStatus.TERMINAL) throw conflict("Run is already ${run.status}")
        jobs[runId]?.let { it.cancel(); it.join() }
        sandboxes.release(runId)
        val after = runs.get(runId)!!
        if (after.status in RunStatus.TERMINAL) return after
        withContext(Dispatchers.IO) { cleanupWorktree(runId, merged = false) }
        runs.setStatus(runId, RunStatus.CANCELLED, "Cancelled by ${user.username}")
        runs.closeOpenStages(runId)
        val owner = users.byId(run.ownerId)!!
        withContext(Dispatchers.IO) {
            bestEffort { gh(owner).removeLabel(org, run.repo, run.issueNumber, "needs-human") }
            commentEnded(runId, "**Agern:** run `${run.id}` was cancelled by @${user.username}.")
        }
        publish(runId)
        settled(runId)
        return runs.get(runId)!!
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
        val worktree = workspaces.runDir(owner.username, run.repo, run.id)
        val baseRef = withContext(Dispatchers.IO) {
            val main = workspaces.ensureMain(id, run.repo)
            workspaces.withRepoLock(owner.username, run.repo) {
                // Leftovers of runs that have ended (e.g. after a crash) never block a new run; live runs keep theirs.
                workspaces.reapRunWorktrees(owner.username, run.repo) { it != run.id && keepWorktree(it) }
                try {
                    workspaces.git.createWorktree(id, main, worktree, branch, target, workspaces.home(owner.username), startFrom)
                } catch (e: dk.egernet.factory.git.GitException) {
                    if (startFrom == null) throw e
                    throw StageFailed("Could not start from branch $startFrom: ${e.message}")
                }
            }
        }
        if (!resuming) runs.setBranch(run.id, branch)
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
                disallowed = READ_ONLY_TOOLS)
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
                    extraTools = if (sandboxes.enabled) dk.egernet.factory.sandbox.SandboxManager.TOOLS else emptySet(),
                    notes = (ctx.sandbox?.takeIf { sandboxes.enabled }?.let { listOf((if (it.state == "approved") "info" else "warn") to "Sandbox: ${it.reason}") }
                        ?: emptyList()) + (if (round == 0 && previous != null) listOf("info" to "Continues run ${previous.id} from branch ${previous.branch} ($inherited commit(s))") else emptyList()))
                builderClaudeSession = b.claudeSessionId ?: builderClaudeSession
                builderSessionId = b.session.id
                builderSummary = b.resultText?.trim() ?: ""
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
                    val lead = if (releaseName != null) "Issue #${issue.number} · release `$releaseName`" else "Closes #${issue.number}"
                    val earlier = runs.earlierUnmergedPulls(run.repo, run.issueNumber, run.id)
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
                        client.findOpenPull(org, run.repo, branch) ?: client.createPull(
                            org, run.repo, branch, target, "${issue.title} (#${issue.number})",
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
                        val r = stage(run.id, role, round, owner, ctx, reviewPrompt(ctx, role, round, spec, builderSummary), schema = Schemas.REVIEW, disallowed = READ_ONLY_TOOLS)
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
            // A retry of the run can retry the merge (docs/ARCHITECTURE.md §6 "Retrying a run"); so can the owner's approval.
            runs.setFailure(run.id, null, FailedStep.MERGE)
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
        runs.setFailure(run.id, null, null)
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

    /** Round 0 of a run that continues [previous] from its commits (docs/ARCHITECTURE.md §6 "Retrying a run"). */
    private fun continuePrompt(c: RunContext, spec: String, previous: RunRecord, inherited: Int) = buildString {
        append("Continue the implementation of issue #${c.issue.number} in ${org}/${c.repo} according to the spec below.\n\n")
        append("An earlier run of this issue (`${previous.id}`) did not finish. ")
        append("It stopped with: ${previous.error?.trim()?.take(1500) ?: "(no reason recorded)"}\n\n")
        append("You are in a git worktree on branch `${c.branch}`. It starts at the tip of that run's branch `${previous.branch}`, ")
        append("so it already holds $inherited commit${if (inherited == 1) "" else "s"} on top of `${c.baseRef}`. ")
        append("Do not redo that work. First find out what exists: `git log ${c.baseRef}..HEAD` (a commit message may hold a hand-over ")
        append("note from the previous builder), `git diff ${c.baseRef}...HEAD` and `git status`. Then finish what is missing, ")
        append("run the checks the previous builder may not have completed, and commit. If `${c.baseRef}` has moved on since ")
        append("(`git log HEAD..${c.baseRef}`), merge it in and resolve any conflicts.\n")
        append("Do not push, open pull requests or merge: the factory does that after you finish.\n")
        append(sandboxLine(c)).append('\n')
        previous.builderSummary?.takeIf { it.isNotBlank() }?.let {
            append("<previous_builder_summary>\n").append(it.take(4000)).append("\n</previous_builder_summary>\n\n")
        }
        append(c.releaseNote).append(c.issueText)
        append("<spec>\n").append(spec).append("\n</spec>\n\n")
        append("When you are done, end with a short summary of what the branch now contains (the earlier commits and yours), ")
        append("how you verified it, and anything left open.")
    }

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

    private fun reviewPrompt(c: RunContext, role: String, round: Int, spec: String, builderSummary: String) = """
        |Review the changes on branch `${c.branch}` for issue #${c.issue.number} in ${org}/${c.repo}${if (round > 0) " (after fix round $round)" else ""}.
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

        fun firstParagraph(s: String): String = s.trim().split(Regex("\n\\s*\n")).first().take(500)
    }
}
