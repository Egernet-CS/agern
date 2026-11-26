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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
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
import java.time.Instant
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
internal class StageFailed(
    message: String,
    val usageLimit: UsageLimit? = null,
    val credentialRejected: Boolean = false,
    val kind: String = FailureKind.OTHER,
    val step: String? = null,
    cause: Throwable? = null,
    /** The run waited for the network and gave up (or reached the limit): never retried again ([Transient]). */
    val gaveUp: Boolean = false,
) : RuntimeException(message, cause)

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

    /**
     * Called when a release run settles: at the end of its pipeline (not when its coroutine is cancelled), after
     * `approve` and after `cancel`. The receiver must not block or call back into the orchestrator inline.
     */
    @Volatile var onReleaseRunSettled: ((RunOutcome) -> Unit)? = null

    /**
     * Called with the release id after a sync run pushed to its release branch, so the release is re-evaluated
     * (docs/ARCHITECTURE.md §6 "Bringing a release up to date"). The receiver must not block or call back inline.
     */
    @Volatile var onReleaseBranchPushed: ((UUID) -> Unit)? = null

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

    // ------------------------------------------------------------ waiting for the network (docs/ARCHITECTURE.md §6 "Transient failures")

    /** How a sleeping wait ends early: "Retry now" ([giveUpBy] null) or "Give up" by [giveUpBy]. */
    private class WakeUp(val giveUpBy: String?)

    /** One sleep before the next attempt, shared by every operation of the run that fails meanwhile. */
    private class Sleep(val nextAt: Instant) {
        val signal = CompletableDeferred<WakeUp>()
    }

    /**
     * A run's current streak of transient failures: when it began, how many attempts failed, the sleep in progress (if
     * any) and who gave up. Lives only while the run's pipeline does (removed in [execute]).
     */
    private class NetWait {
        val lock = Mutex()
        var since: Instant? = null
        var attempts = 0
        var sleep: Sleep? = null
        var gaveUpBy: String? = null
    }

    private val netWaits = ConcurrentHashMap<UUID, NetWait>()

    /**
     * Called with the release id when a release run starts or stops waiting for the network, so the release is
     * published. The receiver must not block or call back into the orchestrator inline.
     */
    @Volatile var onReleaseRunWaitChanged: ((UUID) -> Unit)? = null

    private fun waitChanged(runId: UUID) {
        publish(runId)
        val releaseId = runs.get(runId)?.releaseId ?: return
        try {
            onReleaseRunWaitChanged?.invoke(releaseId)
        } catch (e: Exception) {
            log.warn("Release callback for the network wait of run {} failed: {}", runId, Transient.describe(e))
        }
    }

    /** A failure as the run's wait shows it: a [StageFailed]'s own sentence, else the exception named with its class. */
    private fun describeFailure(e: Throwable): String = (e as? StageFailed)?.message ?: Transient.describe(e)

    /**
     * After an operation of run [runId] failed transiently ([original], described by [detail]): waits until its next
     * attempt is due, "Retry now" is pressed, or the run is cancelled; joins the sleep in progress if there is one (not
     * counted as another attempt). Throws instead when waiting is off ([original] itself), when the owner gave up, or when
     * the wait would start after [Config.networkRetryMaxMs] (a [StageFailed] with [StageFailed.gaveUp]). [sessionId]:
     * the stage agent whose transcript is told.
     */
    private suspend fun awaitNetwork(runId: UUID, detail: String, sessionId: UUID?, original: Exception) {
        if (cfg.networkRetryMaxMs <= 0) throw original
        val w = netWaits.computeIfAbsent(runId) { NetWait() }
        val text = detail.trim().ifEmpty { Transient.describe(original) }.take(500)
        var started = false
        val sleep: Sleep
        val attempt: Int
        w.lock.withLock {
            w.gaveUpBy?.let { throw gaveUp(original, it, w.attempts) }
            val current = w.sleep
            if (current != null) {
                sleep = current
            } else {
                val now = Instant.now()
                val since = w.since ?: now
                val n = w.attempts + 1
                if (!now.isBefore(since.plusMillis(cfg.networkRetryMaxMs))) {
                    throw waitEnded(original, "Gave up after waiting for the network for ${humanDuration(now.toEpochMilli() - since.toEpochMilli())} ($n attempts).")
                }
                val delays = cfg.networkRetryDelaysMs.ifEmpty { listOf(60_000L) }
                val s = Sleep(now.plusMillis(delays[minOf(n, delays.size) - 1]))
                w.since = since
                w.attempts = n
                w.sleep = s
                runs.setWait(runId, since, s.nextAt, n, text)
                sleep = s
                started = true
            }
            attempt = w.attempts
        }
        if (started) {
            log.info("Run {} waits for the network until {} (attempt {}): {}", runId, sleep.nextAt, attempt, text)
            waitChanged(runId)
        }
        sessionId?.let {
            bestEffort {
                agents.systemEvent(
                    it, "warn",
                    "Lost the connection: ${text.trimEnd('.')}. Waiting for the network; next try at ${WAIT_TIME.format(sleep.nextAt)} (attempt ${attempt + 1}).",
                )
            }
        }
        val wake = withTimeoutOrNull(java.time.Duration.between(Instant.now(), sleep.nextAt).toMillis().coerceAtLeast(1)) { sleep.signal.await() }
        var ended = false
        w.lock.withLock {
            if (wake?.giveUpBy != null && w.gaveUpBy == null) w.gaveUpBy = wake.giveUpBy
            if (w.sleep === sleep) {
                w.sleep = null
                runs.clearWait(runId)
                ended = true
            }
        }
        if (ended) waitChanged(runId)
        w.gaveUpBy?.let { throw gaveUp(original, it, w.attempts) }
    }

    /** The streak of transient failures ends with the first success of a retried operation (unless a sleep is in progress). */
    private suspend fun endStreak(runId: UUID) {
        val w = netWaits[runId] ?: return
        w.lock.withLock {
            if (w.sleep == null) {
                w.since = null
                w.attempts = 0
            }
        }
    }

    private fun gaveUp(original: Exception, username: String, attempts: Int) =
        waitEnded(original, "Gave up waiting for the network (by @$username, after $attempts attempt${if (attempts == 1) "" else "s"}).")

    /**
     * The failure a run ends with when it stops waiting: [original]'s reason with [suffix], kind `api_unreachable` (or
     * the failed step of a push or pull request), never retried again.
     */
    private fun waitEnded(original: Exception, suffix: String): StageFailed {
        val step = (original as? StageFailed)?.takeIf { it.kind == FailureKind.STEP }
        return StageFailed(
            "${describeFailure(original).trimEnd()} $suffix", kind = step?.kind ?: FailureKind.API_UNREACHABLE, step = step?.step,
            cause = original, gaveUp = true,
        )
    }

    /**
     * Runs [block] and, while it fails transiently ([Transient.isTransient]), waits for the network ([awaitNetwork]) and
     * runs it again, with [beforeRetry] first. The first success after a retry ends the run's streak.
     */
    private suspend fun <T> withNetworkRetry(runId: UUID, beforeRetry: suspend () -> Unit = {}, block: suspend () -> T): T {
        var retried = false
        while (true) {
            try {
                if (retried) beforeRetry()
                val value = block()
                if (retried) endStreak(runId)
                return value
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!Transient.isTransient(e)) throw e
                awaitNetwork(runId, describeFailure(e), null, e)
                retried = true
            }
        }
    }

    /** The sleep run [runId] is in, or 404/403/409 (owner or admin, as [cancel]). */
    private suspend fun pendingSleep(user: UserRecord, runId: UUID): Pair<NetWait, Sleep> {
        val run = runs.get(runId) ?: throw notFound("Run not found")
        if (run.ownerId != user.id && !user.isAdmin) throw forbidden("Only the owner or an admin can do this for this run")
        val w = netWaits[runId]
        val sleep = w?.lock?.withLock { w.sleep }
        if (w == null || sleep == null || run.status !in RunStatus.ACTIVE || run.status == RunStatus.NEEDS_HUMAN) {
            throw conflict("The run is not waiting for the network", "run_not_waiting_for_network")
        }
        return w to sleep
    }

    /** "Retry now": the waiting run tries its operation again at once. Owner or admin. */
    suspend fun retryNow(user: UserRecord, runId: UUID): RunRecord {
        val (w, sleep) = pendingSleep(user, runId)
        if (!sleep.signal.complete(WakeUp(null))) throw conflict("The run is not waiting for the network", "run_not_waiting_for_network")
        // Until the sleeping pipeline has ended the wait, so the answer no longer shows it.
        withTimeoutOrNull(5_000) { while (w.lock.withLock { w.sleep === sleep }) kotlinx.coroutines.delay(10) }
        return runs.get(runId)!!
    }

    /** "Give up": the waiting run fails now, as it would have without waiting. Owner or admin. Returns it once failed. */
    suspend fun giveUp(user: UserRecord, runId: UUID): RunRecord {
        val (_, sleep) = pendingSleep(user, runId)
        if (!sleep.signal.complete(WakeUp(user.username))) throw conflict("The run is not waiting for the network", "run_not_waiting_for_network")
        log.info("Run {}: {} gave up waiting for the network", runId, user.username)
        jobs[runId]?.join()
        return runs.get(runId)!!
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
     * Starts a sync run as [owner] (the release owner or an admin, already checked; the run uses only [owner]'s
     * credentials): it merges [defaultBranch] into the release branch of [release] (docs/ARCHITECTURE.md §6 "Bringing a
     * release up to date"). The caller holds the release's mutex. 409 `release_sync_running` when the release already
     * has a sync run in progress.
     */
    suspend fun startSync(owner: UserRecord, release: dk.egernet.factory.db.ReleaseRecord, defaultBranch: String): RunRecord {
        val releaseBranch = release.branch ?: "release/${release.name}"
        val id = UUID.randomUUID()
        // Built from the validated release name and a UUID prefix only; a local working branch, never pushed as such.
        val branch = "factory/sync-${release.name}-${id.toString().take(8)}"
        val eff = preferences.effective(owner.id)
        val run = withContext(Dispatchers.IO) {
            runs.createSync(
                id, release.repo, "Bring $releaseBranch up to date with $defaultBranch", eff.maxFixRounds, owner.id, release.id, releaseBranch, branch,
            )
        } ?: throw syncRunning(release.name, runs.latestSync(release.id)?.id)
        runs.finishStage(runs.createStage(run.id, "spec-writer", 0, eff.model("spec-writer")).id, "skipped", null, SYNC_SKIPPED_STAGE, emptyList())
        for (role in listOf("builder") + REVIEWER_ROLES) runs.createStage(run.id, role, 0, eff.model(role))
        runs.finishStage(runs.createStage(run.id, "approver", 0, eff.model("approver")).id, "skipped", null, SYNC_SKIPPED_STAGE, emptyList())
        publish(run.id)
        launchJob(run.id) { execute(run.id) }
        return runs.get(run.id)!!
    }

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
        val run = runs.createIfNoActive(
            repo, issueNumber, issue.title, issueKindFromLabels(issue.labels.map { it.name }), eff.maxFixRounds, owner.id, startedBySession,
            // A release run's base is always its release branch (in the member repository for a member issue).
            releaseId, (if (releaseId != null) baseBranch else null) ?: member?.first?.baseBranch ?: baseBranch, continuesFrom?.id, continuesFrom?.spec, member?.first?.repository,
            member?.first?.directory, member?.second.orEmpty(),
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
        if (run.isSync) {
            return RetryOptionsDto(
                run.id.toString(), RetryMode.START_OVER, SYNC_NOT_RETRIED,
                RetryMode.ALL.map { RetryOptionDto(it, false, SYNC_NOT_RETRIED) }, releaseId = run.releaseId?.toString(),
            )
        }
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
        if (run.isSync) throw syncNotRetried()
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
        if (run.isSync) throw syncNotRetried()
        if (owner.claudeCredEnc == null) throw unprocessable("Register a Claude credential in Settings first", "claude_credential_required")
        if (run.status == RunStatus.NEEDS_HUMAN && run.failedStep == FailedStep.MERGE) {
            if (run.prNumber == null) throw conflict("Run has no pull request to merge", "retry_mode_unavailable")
            // The same gate as an approval: changes from the conversation must have been reviewed.
            startMerging(run.id)
            publish(run.id)
            // The approver approved before the merge failed: merging again is its decision; otherwise it is the owner's.
            val approverApproved = runs.stages(run.id).lastOrNull { it.role == "approver" }?.verdict == "APPROVE"
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

    /** Owner only, only in needs_human: squash-merge the PR and close the issue. */
    suspend fun approve(user: UserRecord, runId: UUID): RunRecord {
        val run = runs.get(runId) ?: throw notFound("Run not found")
        if (run.ownerId != user.id) throw forbidden("Only the owner of this run can approve it")
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

    /**
     * Owner or admin. [byRelease]: cancelled because its release was cancelled ([user] may cancel the release, which was
     * checked by the caller), so a sync run an admin started is stopped as well when the owner cancels the release.
     */
    suspend fun cancel(user: UserRecord, runId: UUID, byRelease: Boolean = false): RunRecord {
        val run = runs.get(runId) ?: throw notFound("Run not found")
        if (run.ownerId != user.id && !user.isAdmin && !byRelease) throw forbidden("Only the owner or an admin can cancel this run")
        if (run.status in RunStatus.TERMINAL) throw conflict("Run is already ${run.status}")
        ending += runId
        try {
            // The pipeline or a follow-up review, then the advisor (mid-turn included): nothing works in the worktree after this.
            jobs[runId]?.let { it.cancel(); it.join() }
            runs.get(runId)?.advisorSessionId?.let { agents.stopAdvisor(it, ended = true) }
            sandboxes.release(runId)
            val after = runs.get(runId)!!
            if (after.status in RunStatus.TERMINAL) return after
            withContext(Dispatchers.IO) { cleanupWorktree(runId, merged = false) }
            runs.setStatus(runId, RunStatus.CANCELLED, "Cancelled by ${user.username}")
        } finally {
            ending -= runId
        }
        runs.closeOpenStages(runId)
        val owner = users.byId(run.ownerId)!!
        withContext(Dispatchers.IO) {
            // A sync run belongs to no issue: no label, and its comment goes to the release pull request (commentEnded).
            if (!run.isSync) bestEffort { gh(owner).removeLabel(org, run.repo, run.issueNumber, "needs-human") }
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
            if (runs.get(runId)?.isSync == true) runSync(runId) else run(runId, step)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val sf = e as? StageFailed
            // The log names the exception's class; the run's error keeps a message people can read, and is the class
            // name (never "null") for an exception without one, e.g. java.net.ConnectException from HttpClient.
            log.warn("Run {} failed: {}", runId, Transient.describe(e))
            val reason = sf?.message ?: e.message?.takeIf { it.isNotBlank() } ?: Transient.describe(e)
            val kind = sf?.kind?.takeIf { it != FailureKind.OTHER }
                ?: if (Transient.isTransient(e) || FailureKind.looksUnreachable(reason)) FailureKind.API_UNREACHABLE else FailureKind.OTHER
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
            netWaits.remove(runId)
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
        if (run.isSync) {
            syncEndedComment(run, owner)
            return
        }
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

        // Workspace: owner's clone + a worktree on the run branch. Retried as a whole when the network fails, after
        // removing what the failed attempt checked out (docs/ARCHITECTURE.md §6 "Transient failures").
        val releaseName = run.baseBranch?.takeIf { run.releaseId != null }?.removePrefix("release/")
        // The agents' working directory; for a workspace run the change is in the target member's worktree inside it.
        val root = workspaces.runDir(owner.username, run.repo, run.id)
        val home = workspaces.home(owner.username)
        val (repoInfo, issue, comments, branch, target, setUp) = withNetworkRetry(
            runId, beforeRetry = { withContext(Dispatchers.IO) { cleanupWorktree(runId, merged = false) } },
        ) { setUpRun(run, owner, id, client, resuming, previous, releaseName, root, home) }
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
            withNetworkRetry(run.id) {
                withContext(Dispatchers.IO) {
                    client.comment(org, run.repo, run.issueNumber, "**Agern: spec ready** (run `${run.id}`)\n\n$spec")
                }
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
                withNetworkRetry(run.id) {
                    try {
                        workspaces.git.push(id, worktree, branch, workspaces.home(owner.username))
                    } catch (e: Exception) {
                        throw StageFailed("Pushing branch $branch failed: ${e.message}", kind = FailureKind.STEP, step = FailedStep.PUSH, cause = e)
                    }
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
                    val pr = withNetworkRetry(run.id) {
                        try {
                            client.findOpenPull(org, prRepo(run), branch) ?: client.createPull(
                                org, prRepo(run), branch, target, prTitle(run, issue.title),
                                "$lead\n\nBuilt by Agern run `${run.id}` for @${owner.githubLogin ?: owner.username}.\n\n" + continues + earlier +
                                    "<details><summary>Spec</summary>\n\n$spec\n\n</details>",
                            )
                        } catch (e: Exception) {
                            throw StageFailed(
                                "Opening the pull request for $branch failed: ${e.message ?: e.javaClass.simpleName}",
                                kind = FailureKind.STEP, step = FailedStep.PULL_REQUEST, cause = e,
                            )
                        }
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
        val a = stage(run.id, "approver", round, owner, ctx, approvalPrompt(ctx, spec, lastReviews, round, maxRounds, run.prUrl), schema = Schemas.APPROVAL, disallowed = READ_ONLY_TOOLS)
        val v = parseVerdict(a, setOf("APPROVE", "ESCALATE"))
        if (v == null) {
            finishStage(run.id, "approver", round, null, "No valid verdict in the agent's output", emptyList(), failed = true)
            throw StageFailed("The approver did not return a valid verdict")
        }
        finishStage(run.id, "approver", round, v.verdict, v.summary, emptyList())
        if (v.verdict == "APPROVE") {
            mergeApproved(run.id, owner, client)
            publish(run.id)
        } else {
            needsHuman(runs.get(run.id)!!, owner, "The approver escalated: ${v.summary}")
        }
    }

    // ------------------------------------------------------------ sync runs (docs/ARCHITECTURE.md §6 "Bringing a release up to date")

    /** What a sync run's set-up read and checked out (see [setUpSync]). */
    private data class SyncSetUp(
        val repoInfo: dk.egernet.factory.github.GhRepo, val baseRef: String, val defaultRef: String, val start: String,
        val submoduleErrors: List<Pair<String, String>>,
    )

    /**
     * The pipeline of a sync run: the builder merges the default branch into a worktree at the tip of the release
     * branch, the server verifies the result with git and pushes it to the release branch as a fast-forward, then the
     * four reviewers review the merge, with fix rounds. Never touches the default branch, never forces.
     */
    private suspend fun runSync(runId: UUID) {
        val run = runs.get(runId)!!
        val owner = owner(run)
        val id = identity(owner)
        val client = gh(owner)
        val releaseId = run.releaseId ?: throw StageFailed("The run belongs to no release")
        val release = withContext(Dispatchers.IO) { releaseStore?.get(releaseId) } ?: throw StageFailed("The release no longer exists")
        val releaseBranch = run.baseBranch ?: throw StageFailed("The run has no release branch")
        val branch = run.branch ?: throw StageFailed("The run has no working branch")
        val root = workspaces.runDir(owner.username, run.repo, run.id)
        val home = workspaces.home(owner.username)
        val setUp = withNetworkRetry(runId, beforeRetry = { withContext(Dispatchers.IO) { cleanupWorktree(runId, merged = false) } }) {
            setUpSync(run, owner, id, client, root, home)
        }
        val target = setUp.repoInfo.defaultBranch
        val defaultRef = setUp.defaultRef
        val start = setUp.start
        // The sandbox decision is the default branch's, as for release runs (docs/ARCHITECTURE.md §5 "The run's decision").
        val sandbox = sandboxes.prepare(run.id, run.repo, root, defaultRef, home)
        publish(run.id)
        val ctx = RunContext(
            run.id, run.repo, null, "", branch, setUp.baseRef, releaseBranch, root, release.name, target, sandbox, root = root,
        )
        val notes = (sandbox.takeIf { sandboxes.enabled }?.let { listOf((if (it.state == "approved") "info" else "warn") to "Sandbox: ${it.reason}") }
            ?: emptyList()) + setUp.submoduleErrors.map { (where, out) -> "warn" to "Submodules of `$where` could not be fetched: ${out.trim().takeLast(300)}" }
        val releaseBlock = releaseBlock(release)
        val maxRounds = run.maxRounds
        var round = 0
        var builderClaudeSession: String? = null
        var builderSessionId: UUID? = null
        var findings: Map<String, Verdict> = emptyMap()
        var lastPushed: String? = null
        while (true) {
            runs.setRound(run.id, round)
            setStatus(run.id, RunStatus.BUILDING)
            if (round > 0) {
                val eff = preferences.effective(owner.id)
                runs.createStage(run.id, "builder", round, eff.model("builder"))
                REVIEWER_ROLES.forEach { runs.createStage(run.id, it, round, eff.model(it)) }
                publish(run.id)
            }
            val prompt = if (round == 0) syncBuildPrompt(ctx, defaultRef, start, releaseBlock)
                else syncFixPrompt(ctx, defaultRef, round, maxRounds, findings)
            val b = try {
                stage(
                    run.id, "builder", round, owner, ctx, prompt, resume = builderClaudeSession, parent = builderSessionId,
                    extraTools = if (sandboxes.enabled) dk.egernet.factory.sandbox.SandboxManager.TOOLS else emptySet(),
                    notes = if (round == 0) notes else emptyList(),
                )
            } catch (e: StageFailed) {
                throw StageFailed(
                    syncFailure(releaseBranch, target, e.message ?: "the builder failed", lastPushed, null),
                    e.usageLimit, e.credentialRejected, e.kind, cause = e, gaveUp = e.gaveUp,
                )
            }
            builderClaudeSession = b.claudeSessionId ?: builderClaudeSession
            builderSessionId = b.session.id
            val summary = b.resultText?.trim().orEmpty()
            runs.setBuilderSummary(run.id, summary)
            val head = verifySync(run.id, round, root, home, lastPushed ?: start, defaultRef, releaseBranch, target, lastPushed, summary)
            if (head == start && lastPushed == null) {
                finishStage(run.id, "builder", round, null, "Already up to date; nothing to push", emptyList())
                REVIEWER_ROLES.forEach { skipStage(run.id, it) }
                sandboxes.release(run.id)
                withContext(Dispatchers.IO) { cleanupWorktree(run.id, merged = true) }
                runs.setFailure(run.id, null, null)
                runs.setStatus(run.id, RunStatus.MERGED, clearError = true)
                publish(run.id)
                return
            }
            finishStage(run.id, "builder", round, null, firstParagraph(summary), emptyList())
            if (head != lastPushed) {
                pushSync(run.id, id, root, home, releaseBranch, target, head, lastPushed)
                lastPushed = head
                syncPushedComment(runs.get(run.id)!!, owner, client, release, target, head, round)
            }

            setStatus(run.id, RunStatus.REVIEWING)
            val reviews = coroutineScope {
                REVIEWER_ROLES.map { role ->
                    async {
                        val r = stage(run.id, role, round, owner, ctx, syncReviewPrompt(ctx, role, round, defaultRef, start, head, summary, releaseBlock),
                            schema = Schemas.REVIEW, disallowed = READ_ONLY_TOOLS)
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
                if (list.any { it == null }) throw StageFailed("A reviewer did not return a valid verdict on the merge of `$target` into `$releaseBranch`")
                list.filterNotNull().toMap()
            }
            val changes = reviews.filterValues { it.verdict == "CHANGES" }
            if (changes.isEmpty()) break
            if (round >= maxRounds) {
                throw StageFailed(
                    "Reviewers still request changes on the merge of `$target` into `$releaseBranch` after $round fix round(s): " +
                        "${changes.keys.joinToString(", ")}. The merge is already on `$releaseBranch` (commit `${head.take(7)}`); look at " +
                        "the findings before releasing.",
                )
            }
            findings = changes
            round++
        }
        sandboxes.release(run.id)
        withContext(Dispatchers.IO) { cleanupWorktree(run.id, merged = true) }
        runs.setFailure(run.id, null, null)
        runs.setStatus(run.id, RunStatus.MERGED, clearError = true)
        publish(run.id)
    }

    /** The set-up of a sync run: one unit for the network retry (a failed attempt's worktree is removed first). */
    private suspend fun setUpSync(run: RunRecord, owner: UserRecord, id: AgentIdentity, client: GitHubClient, root: Path, home: Path): SyncSetUp =
        withContext(Dispatchers.IO) {
            val repoInfo = client.getRepo(org, run.repo) ?: throw StageFailed("Repository not accessible")
            val releaseBranch = run.baseBranch!!
            val main = workspaces.ensureMain(id, run.repo)
            val base = workspaces.withRepoLock(owner.username, run.repo) {
                workspaces.reapRunWorktrees(owner.username, run.repo) { it != run.id && keepWorktree(it) }
                // At the up-to-date tip of the release branch.
                workspaces.git.createWorktree(id, main, root, run.branch!!, releaseBranch, home)
            }
            workspaces.git.fetchBranch(id, main, repoInfo.defaultBranch, home)
            val subs = workspaces.git.updateSubmodules(id, root, home)?.let { listOf(run.repo to it) }.orEmpty()
            SyncSetUp(repoInfo, base, workspaces.git.baseRef(repoInfo.defaultBranch), workspaces.git.head(root, home), subs)
        }

    /**
     * The server's own check of a sync builder's result, before anything is pushed: [mustContain] (the start commit, or
     * the commit pushed last) and [defaultRef] are ancestors of `HEAD`, the merge is finished, and the release's
     * migrations do not collide with the default branch's. Answers `HEAD`; fails the builder stage and throws otherwise.
     */
    private suspend fun verifySync(
        runId: UUID, round: Int, root: Path, home: Path, mustContain: String, defaultRef: String, releaseBranch: String, target: String,
        lastPushed: String?, summary: String,
    ): String {
        val (check, reason) = withContext(Dispatchers.IO) {
            val check = workspaces.git.mergeState(root, mustContain, defaultRef, home)
            val reason = when {
                !check.containsStart -> "the branch no longer contains the release branch's commits (history was rewritten)"
                !check.containsMerged -> "`$target` was not merged into it"
                check.unfinished -> "the merge is not finished (unmerged paths, a merge in progress or uncommitted changes)"
                else -> {
                    val cmp = workspaces.git.compareWorktree(root, defaultRef, home, MigrationCheck::rootOf)
                    if (cmp == null) "its database migrations could not be compared with `$target`"
                    else MigrationCheck.problems(cmp.baseFiles, cmp.changes).takeIf { it.isNotEmpty() }
                        ?.let { "database migration conflict: ${MigrationCheck.listed(it)}" }
                }
            }
            check to reason
        }
        if (reason == null) return check.head
        finishStage(runId, "builder", round, null, "Not pushed: $reason", emptyList(), failed = true)
        throw StageFailed(syncFailure(releaseBranch, target, reason, lastPushed, summary))
    }

    /** The error of a sync run that stopped before (or between) pushes, with the agent's own words when it said something. */
    private fun syncFailure(releaseBranch: String, target: String, reason: String, lastPushed: String?, summary: String?): String {
        val pushed = if (lastPushed == null) "Nothing was pushed."
            else "Nothing more was pushed; the merge pushed earlier (commit `${lastPushed.take(7)}`) stays on `$releaseBranch`."
        val said = summary?.trim()?.split(Regex("\n\\s*\n"))?.first()?.trim()?.take(1000)?.takeIf { it.isNotEmpty() }
        return "Could not bring `$releaseBranch` up to date with `$target`: ${reason.trimEnd('.')}. $pushed" + (said?.let { " The agent said: $it" } ?: "")
    }

    /**
     * Pushes the sync run's `HEAD` to [releaseBranch] as a fast-forward only (never forced), waiting for the network on
     * transient failures; records it on the run and has the release re-evaluated.
     */
    private suspend fun pushSync(
        runId: UUID, id: AgentIdentity, root: Path, home: Path, releaseBranch: String, target: String, head: String, lastPushed: String?,
    ) {
        withContext(Dispatchers.IO) {
            withNetworkRetry(runId) {
                try {
                    workspaces.git.pushFastForward(id, root, releaseBranch, home)
                } catch (e: dk.egernet.factory.git.PushRejectedException) {
                    throw StageFailed(
                        "Pushing to `$releaseBranch` was refused because the branch changed meanwhile: ${e.output.take(500)}. " +
                            (if (lastPushed == null) "Nothing was pushed" else "Nothing more was pushed") + "; use \"Bring up to date\" again.",
                        cause = e,
                    )
                } catch (e: dk.egernet.factory.git.GitException) {
                    if (Transient.isTransient(e)) throw e
                    throw StageFailed(syncFailure(releaseBranch, target, "pushing failed: ${e.message?.take(500)}", lastPushed, null), cause = e)
                }
            }
        }
        runs.setSyncPushed(runId, head)
        publish(runId)
        runs.get(runId)?.releaseId?.let { rid ->
            try {
                onReleaseBranchPushed?.invoke(rid)
            } catch (e: Exception) {
                log.warn("Release callback after the push of run {} failed: {}", runId, e.message)
            }
        }
    }

    /** After a sync run's push: one comment on the release pull request (best effort, with the run owner's token). */
    private suspend fun syncPushedComment(
        run: RunRecord, owner: UserRecord, client: GitHubClient, release: dk.egernet.factory.db.ReleaseRecord, target: String, head: String, round: Int,
    ) {
        val pr = withContext(Dispatchers.IO) { releaseStore?.get(release.id)?.prNumber } ?: return
        val branch = run.baseBranch ?: return
        val ref = cfg.pageRef("p/${run.repo}/runs/${run.id}", "the page of run `${run.id}`")
        val text = if (round == 0) {
            "**Agern:** merged `$target` into `$branch` (commit `${head.take(7)}`, started by @${owner.githubLogin ?: owner.username}) " +
                "to bring the release up to date. Reviewers are looking at the conflict resolution: $ref"
        } else {
            "**Agern:** pushed fix round $round of the merge of `$target` into `$branch` (commit `${head.take(7)}`). Reviewers are " +
                "looking at it again: $ref"
        }
        withContext(Dispatchers.IO) { bestEffort { client.comment(org, run.repo, pr, text) } }
    }

    /**
     * A sync run ended failed or cancelled: when it had pushed, one comment on the release pull request (its review did
     * not pass or did not finish). A sync run belongs to no issue, so nothing goes to an issue. Blocking.
     */
    private fun syncEndedComment(run: RunRecord, owner: UserRecord) {
        val pushed = run.syncPushedSha ?: return
        val release = run.releaseId?.let { releaseStore?.get(it) } ?: return
        val pr = release.prNumber ?: return
        val target = release.targetBranch ?: "the default branch"
        val why = run.error?.lineSequence()?.firstOrNull()?.trim()?.trimEnd('.')?.take(500) ?: "the run ended"
        val ref = cfg.pageRef("p/${run.repo}/runs/${run.id}", "the page of run `${run.id}`")
        bestEffort {
            gh(owner).comment(
                org, run.repo, pr,
                "**Agern:** the review of the merge of `$target` into `${run.baseBranch}` (commit `${pushed.take(7)}`) did not pass or " +
                    "did not finish: $why. $ref",
            )
        }
    }

    /** The release as data for a sync run's prompts: its name, description and merged issues. */
    private fun releaseBlock(r: dk.egernet.factory.db.ReleaseRecord): String = buildString {
        append("<release>\n")
        append("Name: ${r.name}\n")
        append("Description: ${r.description.trim().ifEmpty { "(none)" }}\n")
        append("Merged issues:\n")
        if (r.merged.isEmpty()) append("(none)\n")
        r.merged.forEach { append("- #${it.number} ${it.title}\n") }
        append("</release>\n")
    }

    /** What a run's set-up read and checked out (see [setUpRun]). */
    private data class RunSetUp(
        val repoInfo: dk.egernet.factory.github.GhRepo, val issue: GhIssue, val comments: List<dk.egernet.factory.github.GhComment>,
        val branch: String, val target: String, val checkout: WorkspaceSetUp,
    )

    /**
     * The set-up of a run: the repository and issue from GitHub, the owner's clone and the run's worktree on its branch
     * (or the workspace checkout). One unit for the network retry: a failed attempt's checkout is removed before the next.
     */
    private suspend fun setUpRun(
        run: RunRecord, owner: UserRecord, id: AgentIdentity, client: GitHubClient, resuming: Boolean, previous: RunRecord?,
        releaseName: String?, root: Path, home: Path,
    ): RunSetUp = withContext(Dispatchers.IO) {
        val repoInfo = client.getRepo(org, run.repo) ?: throw StageFailed("Repository not accessible")
        val issue = client.getIssue(org, run.repo, run.issueNumber) ?: throw StageFailed("Issue not found")
        val comments = runCatching { client.listComments(org, run.repo, run.issueNumber) }.getOrDefault(emptyList())
        // A release run starts from and merges into its release branch; a single run uses the default branch.
        val target = run.baseBranch ?: repoInfo.defaultBranch
        val branch = if (resuming) run.branch ?: throw StageFailed("The run has no branch to retry") else runBranch(run.issueNumber, issue.title, releaseName, run.id)
        // A step retry re-creates the worktree on its own branch; a continuation starts at the tip of the earlier run's branch.
        val startFrom = if (resuming) branch else previous?.branch
        val checkout = if (run.targetRepo != null) setUpWorkspace(run, owner, id, repoInfo.defaultBranch, target, branch, startFrom)
        else {
            val main = workspaces.ensureMain(id, run.repo)
            val base = workspaces.withRepoLock(owner.username, run.repo) {
                // Leftovers of runs that have ended (e.g. after a crash) never block a new run; live runs keep theirs.
                workspaces.reapRunWorktrees(owner.username, run.repo) { it != run.id && keepWorktree(it) }
                try {
                    workspaces.git.createWorktree(id, main, root, branch, target, home, startFrom)
                } catch (e: dk.egernet.factory.git.GitException) {
                    if (startFrom == null) throw e
                    throw StageFailed("Could not start from branch $startFrom: ${e.message}", cause = e)
                }
            }
            val subs = workspaces.git.updateSubmodules(id, root, home)?.let { listOf(run.repo to it) }.orEmpty()
            WorkspaceSetUp(root, base, parentBlock(owner, run.repo), subs)
        }
        RunSetUp(repoInfo, issue, comments, branch, target, checkout)
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
            throw StageFailed("Reading the workspace definition failed: ${e.message}", cause = e)
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
                if (startFrom != null) throw StageFailed("Could not start from branch $startFrom in member `$tDir`: ${e.message}", cause = e)
                throw StageFailed("Member `$tDir` ($tRepo) could not be checked out: ${e.message}", cause = e)
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
        /** The run's issue; null for a sync run, which belongs to no issue. */
        val runId: UUID, val repo: String, val issue: GhIssue?, val issueText: String, val branch: String,
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
        /** The issue's number (0 for a sync run, whose prompts never name one). */
        val issueNumber: Int get() = issue?.number ?: 0

        /** How stage sessions name the run: `#<issue>`, or `sync <release name>` for a sync run. */
        val tag: String get() = issue?.let { "#${it.number}" } ?: "sync ${releaseName ?: ""}".trim()

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

    /**
     * Runs one stage's agent and marks the stage running/failed. The caller records the outcome on success.
     *
     * An agent that lost the Claude API ([Transient.isTransient]) does not fail the stage: the run waits for the
     * network ([awaitNetwork]) and the stage runs again in a new session of the same stage row, resuming the failed
     * attempt's Claude conversation with [CONTINUE_PROMPT] when it reported one, else with the original prompt. A resume
     * that fails without ever starting is followed by one fresh attempt with the original prompt
     * (docs/ARCHITECTURE.md §6 "Transient failures").
     */
    private suspend fun stage(
        runId: UUID, role: String, round: Int, owner: UserRecord, ctx: RunContext, prompt: String,
        schema: String? = null, disallowed: List<String> = emptyList(),
        resume: String? = null, parent: UUID? = null,
        extraTools: Set<String> = emptySet(), notes: List<Pair<String, String>> = emptyList(),
    ): StageAgentResult {
        val st = stageRecord(runId, role, round)
        var attemptPrompt = prompt
        var attemptResume = resume
        var attemptParent = parent
        var resumedRetry = false
        var retried = false
        var res: StageAgentResult
        while (true) {
            val s = settings.get()
            res = agents.runStageAgent(
                StageAgentRequest(
                    // The model recorded on the stage when it was created, so Stage.model is what actually ran.
                    owner = owner, repo = ctx.repo, role = role, model = st.model, runId = runId,
                    issueNumber = ctx.issue?.number, parentSessionId = attemptParent, workDir = ctx.root,
                    systemPrompt = prompts.system(role, ctx.repo, s.agentsMayInstallSoftware, workspace = if (ctx.memberDir != null) "" else null),
                    prompt = attemptPrompt, contextRepos = ctx.contextRepos,
                    jsonSchema = schema, disallowedTools = disallowed, resumeClaudeSessionId = attemptResume,
                    title = "$role · ${ctx.tag}" + if (round > 0) " · round $round" else "",
                    extraTools = extraTools, notes = notes,
                ),
            ) { session ->
                runs.startStage(st.id, session.id, session.model)
                publish(runId)
            }
            if (res.ok) {
                if (retried) endStreak(runId)
                return res
            }
            if (Transient.isTransient(res)) {
                // The stage stays `running` while the run waits; it fails only if the run gives up.
                val error = res.error.orEmpty()
                awaitNetwork(runId, error, res.session.id, StageFailed("$role failed: $error", kind = FailureKind.API_UNREACHABLE))
                attemptParent = res.session.id
                val claude = res.claudeSessionId
                if (claude != null) {
                    attemptPrompt = CONTINUE_PROMPT
                    attemptResume = claude
                    resumedRetry = true
                } else {
                    attemptPrompt = prompt
                    attemptResume = resume
                    resumedRetry = false
                }
                retried = true
                continue
            }
            if (resumedRetry && !res.started && res.result == null) {
                // The resumed conversation could not be started at all: run the stage once more, fresh.
                log.info("Run {}: resuming the {} conversation failed without starting; running the stage again", runId, role)
                attemptParent = res.session.id
                attemptPrompt = prompt
                attemptResume = resume
                resumedRetry = false
                continue
            }
            break
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
     * On failure, or when its database migrations collide with the target's, the run goes (back) to needs_human.
     */
    private fun merge(run: RunRecord, owner: UserRecord, approvedBy: String?, rethrowTransient: Boolean = false) {
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
            // The pipeline's own merge waits for the network instead (docs/ARCHITECTURE.md §6 "Transient failures").
            if (rethrowTransient && Transient.isTransient(e)) throw e
            mergeFailed(run, owner, pr, e.message ?: Transient.describe(e))
            return
        }
        finishMerge(run, owner, client, pr, approvedBy)
    }

    /** A failed squash merge: the run needs a human, with the merge as its failed step. */
    private fun mergeFailed(run: RunRecord, owner: UserRecord, pr: Int, why: String) {
        // A retry of the run can retry the merge (docs/ARCHITECTURE.md §6 "Retrying a run"); so can the owner's approval.
        runs.setFailure(run.id, null, FailedStep.MERGE)
        needsHuman(run, owner, "Merging pull request ${prRef(run, pr)} failed: $why")
    }

    /**
     * The pipeline's merge after the approver approved: as [merge], but a squash merge that fails for lack of network
     * waits and tries again. Before each retry the pull request is read: when it was merged meanwhile (e.g. the failed
     * request went through), the run continues as merged. Giving up or reaching the limit ends as any failed merge does:
     * the run needs a human, with the merge as its failed step.
     */
    private suspend fun mergeApproved(runId: UUID, owner: UserRecord, client: GitHubClient) {
        var retried = false
        while (true) {
            try {
                val run = runs.get(runId)!!
                val pr = run.prNumber
                val mergedMeanwhile = retried && pr != null && try {
                    withContext(Dispatchers.IO) { client.getPull(org, prRepo(run), pr) }?.merged == true
                } catch (e: Exception) {
                    // A pull request that cannot be read for another reason is merged as usual (which reports any problem).
                    if (e is CancellationException || Transient.isTransient(e)) throw e
                    false
                }
                if (mergedMeanwhile && pr != null) {
                    withContext(Dispatchers.IO) { finishMerge(run, owner, client, pr, approvedBy = null) }
                } else {
                    withContext(Dispatchers.IO) { merge(run, owner, approvedBy = null, rethrowTransient = cfg.networkRetryMaxMs > 0) }
                }
                if (retried) endStreak(runId)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!Transient.isTransient(e)) throw e
                try {
                    awaitNetwork(runId, describeFailure(e), null, e)
                } catch (gaveUp: StageFailed) {
                    val run = runs.get(runId)!!
                    withContext(Dispatchers.IO) { mergeFailed(run, owner, run.prNumber ?: 0, gaveUp.message ?: Transient.describe(e)) }
                    return
                }
                retried = true
            }
        }
    }

    /**
     * Everything after a successful squash merge of pull request [pr]: comments, closing the issue (single runs), the
     * worktree, a submodule pointer bump, and the run becomes `merged`.
     */
    private fun finishMerge(run: RunRecord, owner: UserRecord, client: GitHubClient, pr: Int, approvedBy: String?) {
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
            try {
                bumpSubmodulePointer(run, client, pr, autoMerge = true)
            } catch (e: Exception) {
                log.warn("Run {}: bumping the submodule pointer failed: {}", run.id, e.message)
                pointerNotice(run, client, "warn", "**Agern: submodule pointer not bumped.** Moving `${run.targetDir}` in " +
                    "$org/${run.repo} to the merge of ${prRef(run, pr)} failed: ${e.message?.take(300)}. Bump it by hand.")
            }
        }
        runs.setFailure(run.id, null, null)
        runs.setStatus(run.id, RunStatus.MERGED, clearError = true)
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
        |Implement issue #${c.issueNumber} in ${org}/${c.repo} according to the spec below.
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
        append("Continue the implementation of issue #${c.issueNumber} in ${org}/${c.repo} according to the spec below.\n\n")
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

    /** The task of a sync run's builder (docs/ARCHITECTURE.md §6 "Bringing a release up to date"). */
    private fun syncBuildPrompt(c: RunContext, defaultRef: String, start: String, releaseBlock: String) = buildString {
        append("Bring the release branch `${c.targetBranch}` of ${org}/${c.repo} up to date with `${c.defaultBranch}`.\n\n")
        append("You are in a git worktree on branch `${c.branch}`, at the tip of `${c.targetBranch}` (commit `$start`). ")
        append("The default branch is `$defaultRef`; it is already fetched.\n\n")
        append("1. Merge it with `git merge --no-ff $defaultRef`: a merge commit. Never rebase, reset, amend or rewrite existing ")
        append("commits (except to put the branch back as in step 5), and do not push: Agern checks the result and pushes it.\n")
        append("2. Resolve every conflict keeping the behaviour of both sides. Then check the semantic combinations beyond the textual ")
        append("conflicts: symbols one side renamed or removed that the other side uses, changed signatures, registrations duplicated ")
        append("by the merge, configuration and routes added on both sides.\n")
        append("3. Database migrations: list the migrations on `$defaultRef`. Every migration `${c.targetBranch}` adds that is not on ")
        append("`$defaultRef` must get a version above the highest there: rename it in a commit after the merge, and update references ")
        append("to it. Never change, rename or remove a migration that exists on `$defaultRef`. If the repository has ")
        append("`scripts/check-migrations.sh`, `scripts/check-migrations.sh $defaultRef` must pass.\n")
        append("4. Build the project and run its tests.\n")
        append(sandboxLine(c))
        append("5. If it cannot be done safely (a conflict you cannot resolve with confidence, or checks you cannot get to pass), put ")
        append("the branch back (`git merge --abort`, or `git reset --hard $start`) and explain precisely what is left. Nothing is ")
        append("pushed then.\n\n")
        append("End with a summary: per conflicted file how you resolved it, the migrations you renumbered (old → new), and the ")
        append("checks you ran with their result.\n\n")
        append(releaseBlock)
    }

    /** A fix round of a sync run: the merge is already pushed, so fixes go on top. */
    private fun syncFixPrompt(c: RunContext, defaultRef: String, round: Int, maxRounds: Int, changes: Map<String, Verdict>) = buildString {
        append("The reviewers requested changes to the merge of `$defaultRef` into `${c.targetBranch}`. This is fix round $round of at most $maxRounds.\n")
        append("Address every finding below on the same branch (`${c.branch}`) and re-run the relevant checks. Commit fixes on top; never ")
        append("amend, rebase or reset: the merge is already pushed. Do not push. The migration rule still holds: this release's own ")
        append("migrations need versions above every migration on `$defaultRef`, and those must stay unchanged. If you are convinced a ")
        append("finding is wrong, do not change the code for it; explain why in your final summary.\n")
        append(sandboxLine(c)).append('\n')
        for ((role, v) in changes) {
            append("### Reviewer: $role — ${v.verdict}\n${v.summary}\n")
            v.findings.forEach { f -> append("- [${f.severity}] ${f.file ?: "(general)"}: ${f.detail}\n") }
            append('\n')
        }
        append("End with a short summary of what you changed for each finding.")
    }

    /** A reviewer's task in a sync run: only the merge (its conflict resolution and what followed it) is reviewed. */
    private fun syncReviewPrompt(
        c: RunContext, role: String, round: Int, defaultRef: String, start: String, head: String, builderSummary: String, releaseBlock: String,
    ) = buildString {
        append("Review the merge of `${c.defaultBranch}` into `${c.targetBranch}` in ${org}/${c.repo}")
        append(if (round > 0) " (after fix round $round).\n" else ".\n")
        append("Your focus: **$role** (see your role instructions). You cannot edit files.\n\n")
        append("`$defaultRef` was merged into `${c.targetBranch}` to bring the release up to date. Before the merge the release branch ")
        append("was at `$start`; `HEAD` is now `$head`.\n")
        append("For each merge commit in `git rev-list --merges $start..HEAD`, look at the conflict resolution with ")
        append("`git show --remerge-diff <merge>` and `git show --cc <merge>`, and at the commits after it with `git log -p <merge>..HEAD`.\n")
        append("Judge only the merge: is the behaviour of both sides kept, do the combined changes still fit together (renamed or ")
        append("removed symbols, changed signatures, duplicated registrations, configuration and routes added on both sides), are this ")
        append("release's own migrations renumbered above every migration on `$defaultRef`, and is none of `$defaultRef`'s migrations ")
        append("touched? Do not review the features themselves: they were reviewed when they were built.\n\n")
        append(releaseBlock)
        append("\n<builder_summary>\n").append(builderSummary.ifEmpty { "(none)" }).append("\n</builder_summary>\n\n")
        append("Answer through the structured output: verdict `PASS` or `CHANGES`, a summary, and findings ")
        append("(severity blocker|major|minor|nit, file or null, detail). Use `CHANGES` only for findings that must be fixed before release.")
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
        |Review the changes on branch `${c.branch}` for issue #${c.issueNumber} in ${org}/${c.repo}$after.
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

    private fun approvalPrompt(c: RunContext, spec: String, reviews: Map<String, Verdict>, round: Int, maxRounds: Int, prUrl: String?) = buildString {
        append("Decide whether the pull request for issue #${c.issueNumber} in ${org}/${c.repo} may be squash-merged into `${c.targetBranch}`.\n")
        append(c.releaseNote)
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

    companion object {
        val READ_ONLY_TOOLS = listOf("Edit", "Write", "NotebookEdit")

        /** The summary of the spec-writer and approver stages of a sync run. */
        const val SYNC_SKIPPED_STAGE = "Not used when bringing a release up to date"

        /** Why a sync run is never retried. */
        const val SYNC_NOT_RETRIED = "A run that brings a release up to date is not retried; use \"Bring up to date\" on the release again."

        fun syncNotRetried() = conflict(SYNC_NOT_RETRIED, "sync_run")

        /** 409 `release_sync_running` for a second sync of release [name] while run [runId] is in progress. */
        fun syncRunning(name: String, runId: UUID?) =
            conflict("Release \"$name\" is already being brought up to date (run ${runId ?: "unknown"}).", "release_sync_running")

        /** The prompt of a stage resumed after the Claude API was unreachable (docs/ARCHITECTURE.md §6 "Transient failures"). */
        const val CONTINUE_PROMPT = "Message from Agern: the connection to the Claude API was lost while you were working and is " +
            "available again. Continue the task from where you stopped; your working directory is as you left it. End with " +
            "the final answer the task asks for."

        private val WAIT_TIME = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(java.time.ZoneOffset.UTC)

        /** [ms] for people: `3 s`, `12 min`, `2 h 5 min`. */
        fun humanDuration(ms: Long): String {
            val s = maxOf(0L, ms) / 1000
            return when {
                s < 60 -> "$s s"
                s < 3600 -> "${s / 60} min"
                else -> "${s / 3600} h" + (if ((s % 3600) / 60 > 0) " ${(s % 3600) / 60} min" else "")
            }
        }

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
