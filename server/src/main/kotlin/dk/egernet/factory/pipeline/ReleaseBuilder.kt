package dk.egernet.factory.pipeline

import dk.egernet.factory.ApiException
import dk.egernet.factory.Config
import dk.egernet.factory.PauseReason
import dk.egernet.factory.ReleaseIssueState
import dk.egernet.factory.ReleaseStatus
import dk.egernet.factory.RetryMode
import dk.egernet.factory.RunStatus
import dk.egernet.factory.agent.EventBus
import dk.egernet.factory.agent.FeedItem
import dk.egernet.factory.agent.UsageLimit
import dk.egernet.factory.conflict
import dk.egernet.factory.db.ReleaseRecord
import dk.egernet.factory.db.ReleaseStore
import dk.egernet.factory.db.RunStore
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.db.UserStore
import dk.egernet.factory.github.Access
import dk.egernet.factory.github.GitHubClient
import dk.egernet.factory.github.GitHubException
import dk.egernet.factory.unprocessable
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Builds started releases (docs/ARCHITECTURE.md §6 "Releases"): one release run at a time on `release/<name>`,
 * driven by events (start, a run settling, resume, retry, adding an issue, restart, the resume ticker) rather than a
 * long-lived coroutine per release. [advance] runs under a per-release mutex and always re-reads the database.
 *
 * Permission checks are the caller's ([dk.egernet.factory.ReleaseService]); this class only changes state.
 */
class ReleaseBuilder(
    private val cfg: Config,
    private val store: ReleaseStore,
    private val runs: RunStore,
    private val users: UserStore,
    private val access: Access,
    private val orchestrator: Orchestrator,
    private val bus: EventBus,
    private val scope: CoroutineScope,
    private val locks: ReleaseLocks,
    private val merger: ReleaseMerger,
    /** Routes a member issue to its member repository, as for a single run (docs/ARCHITECTURE.md §6 "Workspace runs"). */
    private val workspaceDefs: dk.egernet.factory.workspace.WorkspaceService? = null,
) {
    private val log = LoggerFactory.getLogger(ReleaseBuilder::class.java)
    private val org get() = cfg.githubOrg

    private fun lock(id: UUID) = locks.of(id)
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /** Announces the release's current state on the feed. */
    suspend fun publish(id: UUID) {
        io { store.touch(id) }
        val r = io { store.get(id) } ?: return
        bus.emitFeed(FeedItem.Release(r.toDto()))
    }

    // ------------------------------------------------------------ commands

    /**
     * Claims the planned release [r] (`planning`, or `scheduled` for a scheduled start) for [owner], creates
     * `release/<name>` from [defaultBranch] with [client] (the owner's token), skip-checks every issue and starts the
     * first run. Any failure before the branch exists rolls the claim back to [r]'s state: `planning` with no owner,
     * or still `scheduled` (the scheduler then records the failed start).
     */
    suspend fun start(owner: UserRecord, r: ReleaseRecord, client: GitHubClient, defaultBranch: String): ReleaseRecord {
        val branch = "release/${r.name}"
        if (!io { store.claim(r.id, owner.id, branch, from = r.status) }) {
            if (r.status == ReleaseStatus.SCHEDULED) throw conflict("The release is no longer scheduled", "release_not_scheduled")
            throw conflict("The release is no longer in preparation", "release_not_planning")
        }
        try {
            io {
                val sha = client.branchSha(org, r.repo, defaultBranch)
                    ?: throw ApiException(HttpStatusCode.BadGateway, "github_error", "Branch $defaultBranch was not found in ${r.repo}")
                if (client.branchSha(org, r.repo, branch) != null) throw branchExists(branch)
                client.createBranch(org, r.repo, branch, sha)
            }
        } catch (e: Exception) {
            io { store.unclaim(r.id, r) }
            throw when {
                e is GitHubException && e.status == 422 -> branchExists(branch)
                e is GitHubException && e.status in setOf(401, 403, 404) ->
                    unprocessable("Your GitHub token cannot create branches in ${r.repo}.", "github_push_denied")
                e is GitHubException -> ApiException(HttpStatusCode.BadGateway, "github_error", e.message ?: "GitHub error")
                else -> e
            }
        }
        lock(r.id).withLock {
            val started = io { store.get(r.id) } ?: return@withLock
            for (i in started.issues) {
                if (i.state != ReleaseIssueState.WAITING) continue
                val reason = runCatching { io { skipReason(started, i.number, client) } }.getOrNull() ?: continue
                io { store.setIssue(r.id, i.number, ReleaseIssueState.SKIPPED, reason, null) }
            }
        }
        publish(r.id)
        advance(r.id)
        return io { store.get(r.id) }!!
    }

    private fun branchExists(branch: String) =
        conflict("Branch $branch already exists on GitHub; delete it or rename the release.", "release_branch_exists")

    /** `building` → `paused` by [user]; the running issue finishes, no further issue starts. */
    suspend fun pause(user: UserRecord, id: UUID) {
        if (!io { store.pause(id, PauseReason.MANUAL, "Paused by ${user.username}", null) }) {
            throw conflict("The release is not building", "release_not_building")
        }
        publish(id)
    }

    /** `paused` → `building`, and continue. */
    suspend fun resume(id: UUID) {
        if (!io { store.resume(id) }) throw conflict("The release is not paused", "release_not_paused")
        publish(id)
        advance(id)
    }

    /**
     * `building`, `paused` or `ready` → `cancelled`: the running issue is held, every active run of the release is
     * cancelled (as [user]), waiting issues stay waiting and nothing starts afterwards. The branch, and the release pull
     * request if there is one, are left alone; the pull request gets a comment saying Agern will not merge it. A
     * release of several repositories instead deletes `release/<name>` in every repository of the release
     * ([ReleaseMerger.commentCancelled]).
     */
    suspend fun cancel(user: UserRecord, id: UUID) {
        lock(id).withLock {
            if (!io { store.cancel(id) }) throw conflict("The release is not building", "release_not_building")
            // The issue row first: the run's own outcome then finds it held and leaves it so.
            io { store.setIssuesInState(id, ReleaseIssueState.RUNNING, ReleaseIssueState.HELD, "Cancelled with the release") }
        }
        publish(id)
        merger.commentCancelled(id, user)
        for (run in io { runs.activeByRelease(id) }) cancelRun(user, run.id)
    }

    /** A held or skipped issue of release [id], or 409 / 404 (the checks of [retry]). */
    private fun retryable(id: UUID, number: Int): Pair<ReleaseRecord, dk.egernet.factory.db.ReleaseIssueRecord> {
        val r = store.get(id) ?: throw dk.egernet.factory.notFound("Release not found")
        if (r.status !in ReleaseStatus.ACTIVE && r.status != ReleaseStatus.READY) throw conflict("The release is not building", "release_not_building")
        val issue = r.issues.firstOrNull { it.number == number } ?: throw dk.egernet.factory.notFound("Issue #$number is not in this release")
        if (issue.state != ReleaseIssueState.HELD && issue.state != ReleaseIssueState.SKIPPED) {
            throw conflict("Issue #$number is ${issue.state}; only held or skipped issues can be retried", "release_issue_not_held")
        }
        return r to issue
    }

    /** How the held issue #[number] of release [id] can be retried (from its linked run); null when it has none. */
    suspend fun retryOptions(id: UUID, number: Int): dk.egernet.factory.RetryOptionsDto? {
        val (_, issue) = io { retryable(id, number) }
        val run = issue.runId?.let { io { runs.get(it) } } ?: return null
        return io { orchestrator.retryOptions(run) }
    }

    /**
     * A held or skipped issue is built again (docs/ARCHITECTURE.md §6 "Retrying a run"). [requested] (null =
     * `start_over`): `start_over` — back to `waiting` with run link cleared, its active run (if any) cancelled;
     * `continue` — its active run (if any) is cancelled first, then it waits with the run linked and is built by a new run
     * that continues from that run's commits; `step` — it waits with the run linked and that run's failed step is retried
     * on the same run. Either way it is built in its turn ([advance]), so order and one-at-a-time hold. A `ready` release
     * builds again for it (409 `release_already_building` while another release of the project holds the slot).
     */
    suspend fun retry(owner: UserRecord, id: UUID, number: Int, requested: String? = null) {
        val (current, before) = io { retryable(id, number) }
        // Nothing is built again while some of its pull requests are merged (docs/ARCHITECTURE.md §6 "Several repositories").
        if (current.partlyReleased) throw ReleaseMerger.partlyMerged(current.name)
        val oldRunRecord = before.runId?.let { io { runs.get(it) } }
        val mode = if (oldRunRecord == null) {
            if (requested != null && requested != RetryMode.START_OVER && requested != RetryMode.RECOMMENDED) {
                if (requested !in RetryMode.ALL) throw dk.egernet.factory.badRequest("mode must be one of step, continue, start_over, recommended")
                throw conflict("Issue #$number has no run to continue or retry a step of", "retry_mode_unavailable")
            }
            RetryMode.START_OVER
        } else {
            orchestrator.resolveRetryMode(requested, io { orchestrator.retryOptions(oldRunRecord) })
        }
        if (mode == RetryMode.CONTINUE && oldRunRecord != null && oldRunRecord.status in RunStatus.ACTIVE) {
            // Before the issue waits, so the continuing run never meets the old one still active.
            cancelRun(owner, oldRunRecord.id)
        }
        val oldRun = lock(id).withLock {
            val (r, issue) = io { retryable(id, number) }
            if (mode != RetryMode.START_OVER && issue.runId != oldRunRecord?.id) {
                throw conflict("Issue #$number changed in the meantime; reload and try again", "release_issues_changed")
            }
            if (r.status == ReleaseStatus.READY) io { store.reopen(id) }
            when (mode) {
                RetryMode.START_OVER -> io { store.setIssue(id, number, ReleaseIssueState.WAITING, null, null) }
                RetryMode.CONTINUE -> io {
                    store.setIssue(id, number, ReleaseIssueState.WAITING, "Retry: continues from the commits of run ${issue.runId}", issue.runId, RetryMode.CONTINUE)
                }
                else -> io {
                    store.setIssue(id, number, ReleaseIssueState.WAITING, "Retry: the failed step of run ${issue.runId}", issue.runId, RetryMode.STEP)
                }
            }
            issue.runId
        }
        publish(id)
        if (mode == RetryMode.START_OVER && oldRun != null && io { runs.get(oldRun) }?.status in RunStatus.ACTIVE) cancelRun(owner, oldRun)
        advance(id)
    }

    /**
     * After an interruption the release builds itself again (usage limit, rejected credential, restart): `continue` when
     * the interrupted run is recommended to be continued (its branch has commits), else null (from scratch).
     */
    private fun autoRetryMode(run: dk.egernet.factory.db.RunRecord?): String? {
        if (run == null || run.status != RunStatus.FAILED) return null
        return runCatching { orchestrator.retryOptions(run) }.getOrNull()?.takeIf { it.recommended == RetryMode.CONTINUE }?.let { RetryMode.CONTINUE }
    }

    /** Cancels runs whose issue left a release (taken out or moved). */
    suspend fun cancelRuns(user: UserRecord, runIds: List<UUID>) {
        for (runId in runIds) if (io { runs.get(runId) }?.status in RunStatus.ACTIVE) cancelRun(user, runId)
    }

    private suspend fun cancelRun(user: UserRecord, runId: UUID) {
        try {
            orchestrator.cancel(user, runId)
        } catch (e: ApiException) {
            log.info("Run {} of a release was not cancelled: {}", runId, e.message)
        }
    }

    // ------------------------------------------------------------ building

    /** Why issue #[number] is skipped, or null when it can be built. */
    private fun skipReason(r: ReleaseRecord, number: Int, client: GitHubClient): String? {
        val gh = client.getIssue(org, r.repo, number)
        if (gh == null || gh.state != "open") return "Closed on GitHub"
        val active = runs.activeRunFor(r.repo, number)?.let { runs.get(it) }
        if (active != null && active.releaseId != r.id) return "Has an active run outside the release"
        return null
    }

    /**
     * Starts the next waiting issue when the release is `building` and none is running. Skipped issues and issues
     * whose run cannot start are passed over; a missing credential pauses the release. When nothing is left to build
     * (every issue merged, held or skipped) the release becomes `ready`; a `ready` release is then re-evaluated by
     * [ReleaseMerger.evaluate] (pull request, mergeability, notifications).
     */
    suspend fun advance(id: UUID) {
        var becameReady = false
        lock(id).withLock {
            while (true) {
                val r = io { store.get(id) } ?: return
                if (r.status != ReleaseStatus.BUILDING) break
                if (r.issues.any { it.state == ReleaseIssueState.RUNNING }) return
                val next = r.issues.firstOrNull { it.state == ReleaseIssueState.WAITING }
                if (next == null) {
                    becameReady = io { store.markReady(id) }
                    break
                }
                val owner = r.ownerId?.let { io { users.byId(it) } }
                if (owner?.claudeCredEnc == null) {
                    pauseForCredentials(r.id)
                    return
                }
                val client = access.client(owner)
                if (client == null) {
                    pauseLocked(r.id, PauseReason.CREDENTIALS, "The owner has no GitHub token; add one in Settings and resume.", null)
                    return
                }
                try {
                    val skip = io { skipReason(r, next.number, client) }
                    if (skip != null) {
                        io { store.setIssue(id, next.number, ReleaseIssueState.SKIPPED, skip, null) }
                        publish(id)
                        continue
                    }
                    val previous = if (next.retryMode != null) next.runId?.let { io { runs.get(it) } } else null
                    if (previous != null && next.retryMode == RetryMode.STEP && previous.status == RunStatus.MERGED) {
                        // Approved and merged by its owner while it waited.
                        io { store.setIssue(id, next.number, ReleaseIssueState.MERGED, null, previous.id) }
                        publish(id)
                        continue
                    }
                    val branch = r.branch ?: "release/${r.name}"
                    val run = when {
                        previous != null && next.retryMode == RetryMode.STEP -> orchestrator.retryStep(owner, previous)
                        previous != null && next.retryMode == RetryMode.CONTINUE -> {
                            // A continuation keeps the target of the run it continues; its member branch exists already.
                            val expected = Orchestrator.ExpectedTarget(previous.targetRepo)
                            if (previous.targetRepo != null) io { ensureMemberBranch(r, owner, client, previous.targetRepo, previous.targetDir) }
                            orchestrator.startReleaseRun(owner, r.repo, next.number, r.id, branch, continuesFrom = previous, expectedTarget = expected)
                        }
                        else -> {
                            val expected = io { prepareTarget(r, owner, client, next.number) }
                            orchestrator.startReleaseRun(owner, r.repo, next.number, r.id, branch, expectedTarget = expected)
                        }
                    }
                    io { store.setIssue(id, next.number, ReleaseIssueState.RUNNING, null, run.id) }
                    publish(id)
                    return
                } catch (e: ApiException) {
                    if (e.code == "claude_credential_required") {
                        pauseForCredentials(r.id)
                        return
                    }
                    holdUnstarted(id, next.number, e.message, next.runId.takeIf { next.retryMode != null })
                } catch (e: Exception) {
                    holdUnstarted(id, next.number, e.message ?: e.javaClass.simpleName, next.runId.takeIf { next.retryMode != null })
                }
            }
        }
        if (becameReady) {
            log.info("Release {} has nothing left to build: ready to release", id)
            publish(id)
        }
        if (io { store.get(id) }?.status == ReleaseStatus.READY) merger.evaluate(id, becameReady)
    }

    /**
     * The repository issue #[number] of [r] is built in, resolved once as for a single run (null in [Orchestrator.ExpectedTarget]:
     * the project repository); a member gets its release branch first ([ensureMemberBranch]). Throws what routing throws
     * (e.g. `member_label_missing` when the label changed after planning), which holds the issue. Blocking.
     */
    private fun prepareTarget(r: ReleaseRecord, owner: UserRecord, client: GitHubClient, number: Int): Orchestrator.ExpectedTarget {
        val defs = workspaceDefs ?: return Orchestrator.ExpectedTarget(null)
        val issue = client.getIssue(org, r.repo, number) ?: throw dk.egernet.factory.notFound("Issue #$number not found")
        val m = defs.memberFor(owner, r.repo, issue) ?: return Orchestrator.ExpectedTarget(null)
        ensureMemberBranch(r, owner, client, m.repository, m.directory, m.member.submodule, m.baseBranch)
        return Orchestrator.ExpectedTarget(m.repository)
    }

    /**
     * Makes sure `release/<name>` exists in member repository [repo] for release [r] (docs/ARCHITECTURE.md §6 "Several
     * repositories"): nothing when Agern created it already for this release; otherwise it is created from the tip of
     * the member's [baseBranch] with the owner's token and recorded. 409 `release_branch_exists` when the branch exists
     * there already (Agern never adopts a branch it did not create). Blocking.
     */
    private fun ensureMemberBranch(
        r: ReleaseRecord, owner: UserRecord, client: GitHubClient, repo: String, directory: String?,
        submodule: Boolean? = null, baseBranch: String? = null,
    ) {
        if (store.get(r.id)?.repos?.any { it.repo == repo } == true) return
        // Only a continuation gets here without a resolved member: its release branch was created with its first run.
        val dir = directory ?: throw conflict("The member directory of $org/$repo is unknown", "member_changed")
        val base = baseBranch ?: workspaceDefs?.resolved(owner, r.repo)?.firstOrNull { it.repository == repo }?.baseBranch
            ?: throw conflict("$org/$repo is no longer a member of the workspace", "member_changed")
        val sub = submodule ?: (workspaceDefs?.resolved(owner, r.repo)?.firstOrNull { it.repository == repo }?.member?.submodule ?: false)
        val branch = r.branch ?: "release/${r.name}"
        try {
            val sha = client.branchSha(org, repo, base)
                ?: throw ApiException(HttpStatusCode.BadGateway, "github_error", "Branch `$base` was not found in $org/$repo")
            if (client.branchSha(org, repo, branch) != null) throw memberBranchExists(branch, repo)
            client.createBranch(org, repo, branch, sha)
        } catch (e: GitHubException) {
            throw when {
                e.status == 422 -> memberBranchExists(branch, repo)
                e.status in setOf(401, 403, 404) -> unprocessable("Your GitHub token cannot create branches in $repo.", "github_push_denied")
                else -> ApiException(HttpStatusCode.BadGateway, "github_error", e.message ?: "GitHub error")
            }
        }
        store.insertRepo(r.id, repo, dir, sub, base)
        log.info("Release {}: created {} in {} from {}", r.id, branch, repo, base)
    }

    private fun memberBranchExists(branch: String, repo: String) = conflict(
        "Branch $branch already exists in $org/$repo; delete it or rename the release.", "release_branch_exists",
    )

    /** [keepRun]: the run a retry continued or resumed, kept linked so the issue can be retried again. */
    private suspend fun holdUnstarted(id: UUID, number: Int, reason: String, keepRun: UUID? = null) {
        log.warn("Release {}: could not start a run for #{}: {}", id, number, reason)
        io { store.setIssue(id, number, ReleaseIssueState.HELD, "Could not start a run: ${reason.take(500)}", keepRun) }
        publish(id)
    }

    private suspend fun pauseForCredentials(id: UUID) =
        pauseLocked(id, PauseReason.CREDENTIALS, CREDENTIALS_NOTE, null)

    private suspend fun pauseLocked(id: UUID, reason: String, note: String, resumeAt: Instant?) {
        if (io { store.pause(id, reason, note, resumeAt) }) publish(id)
    }

    /** Orchestrator callback: dispatched, never handled inline (cancel and retry call into the orchestrator). */
    fun onRunSettled(outcome: RunOutcome) {
        scope.launch {
            try {
                settle(outcome)
            } catch (e: Exception) {
                log.warn("Release run {} could not be settled: {}", outcome.run.id, e.message)
            }
        }
    }

    /**
     * Records how a release run ended on its issue and goes on with the build. Idempotent; runs that are no longer
     * linked to an issue (retried, taken out) are ignored. A held issue only changes through its approval.
     */
    suspend fun settle(outcome: RunOutcome) {
        val releaseId = outcome.run.releaseId ?: return
        lock(releaseId).withLock {
            val ref = io { store.issueByRun(outcome.run.id) } ?: return
            val run = io { runs.get(outcome.run.id) } ?: return
            val note = run.error?.take(1000) ?: "Run failed"
            when (ref.state) {
                ReleaseIssueState.RUNNING -> when (run.status) {
                    RunStatus.MERGED -> io { store.setIssue(ref.releaseId, ref.number, ReleaseIssueState.MERGED, null, run.id) }
                    RunStatus.NEEDS_HUMAN -> io { store.setIssue(ref.releaseId, ref.number, ReleaseIssueState.HELD, note, run.id) }
                    RunStatus.FAILED, RunStatus.CANCELLED -> when {
                        outcome.usageLimit != null && run.status == RunStatus.FAILED -> {
                            io { store.setIssue(ref.releaseId, ref.number, ReleaseIssueState.WAITING, USAGE_LIMIT_ISSUE_NOTE, run.id, autoRetryMode(run)) }
                            val (pauseNote, resumeAt) = usageLimitPause(outcome.usageLimit, Instant.now())
                            if (io { store.pause(ref.releaseId, PauseReason.USAGE_LIMIT, pauseNote, resumeAt) }) {
                                publish(ref.releaseId)
                                commentPaused(ref.releaseId, ref.number, pauseNote, resumeAt)
                            }
                        }
                        outcome.credentialRejected && run.status == RunStatus.FAILED -> {
                            io { store.setIssue(ref.releaseId, ref.number, ReleaseIssueState.WAITING, CREDENTIALS_ISSUE_NOTE, run.id, autoRetryMode(run)) }
                            pauseForCredentials(ref.releaseId)
                        }
                        else -> io { store.setIssue(ref.releaseId, ref.number, ReleaseIssueState.HELD, note, run.id) }
                    }
                    else -> return // still active: not settled yet
                }
                ReleaseIssueState.HELD -> when (run.status) {
                    // Approved by its owner after it was held.
                    RunStatus.MERGED -> io { store.setIssue(ref.releaseId, ref.number, ReleaseIssueState.MERGED, null, run.id) }
                    RunStatus.NEEDS_HUMAN -> io { store.setIssue(ref.releaseId, ref.number, ReleaseIssueState.HELD, note, run.id) }
                    else -> return
                }
                else -> return
            }
        }
        publish(releaseId)
        advance(releaseId)
    }

    /**
     * The owner will look at the issue the next morning (§6 "Notifications"): one comment on the interrupted issue when
     * the release pauses on the usage limit. Best effort, with the owner's token.
     */
    private suspend fun commentPaused(id: UUID, number: Int, note: String, resumeAt: Instant) {
        val r = io { store.get(id) } ?: return
        val client = r.ownerId?.let { io { users.byId(it) } }?.let { access.client(it) } ?: return
        try {
            io {
                client.comment(
                    org, r.repo, number,
                    "**Agern:** release `${r.name}` is paused. $note It resumes by itself at ${TIME.format(resumeAt)} and builds this issue again; " +
                        "the owner can also resume it on ${cfg.pageRef("p/${r.repo}/releases/${r.id}", "the page of release `${r.name}`")}",
                )
            }
        } catch (e: Exception) {
            log.warn("Release {}: could not comment on the pause: {}", id, e.message)
        }
    }

    // ------------------------------------------------------------ restart and automatic resume

    /**
     * After a restart (interrupted runs already failed): settles every `running` issue from its run, then continues
     * every `building` release. Paused releases stay paused; a due automatic resume is picked up by [resumeDue].
     */
    fun recoverAfterRestart() {
        for (ref in store.runningIssues()) {
            val run = ref.runId?.let { runs.get(it) }
            when (run?.status) {
                RunStatus.MERGED -> store.setIssue(ref.releaseId, ref.number, ReleaseIssueState.MERGED, null, run.id)
                RunStatus.NEEDS_HUMAN -> store.setIssue(ref.releaseId, ref.number, ReleaseIssueState.HELD, run.error ?: "Run failed", run.id)
                else -> store.setIssue(ref.releaseId, ref.number, ReleaseIssueState.WAITING, RESTART_NOTE, ref.runId, autoRetryMode(run))
            }
        }
        val building = store.building()
        if (building.isNotEmpty()) log.info("Continuing {} building release(s) after restart", building.size)
        scope.launch {
            for (id in building) {
                try {
                    publish(id)
                    advance(id)
                } catch (e: Exception) {
                    log.warn("Release {} could not continue after restart: {}", id, e.message)
                }
            }
            resumeDue()
        }
        merger.recoverAfterRestart()
    }

    /** Resumes paused releases whose automatic resume time has passed. */
    suspend fun resumeDue(now: Instant = Instant.now()) {
        for (id in io { store.dueForResume(now) }) {
            if (io { store.resume(id) }) {
                log.info("Release {} resumes automatically after the usage limit", id)
                publish(id)
                advance(id)
            }
        }
    }

    /** Calls [resumeDue] every 30 s. */
    fun startTicker() = scope.launch {
        while (isActive) {
            delay(30_000)
            try {
                resumeDue()
            } catch (e: Exception) {
                log.warn("Automatic release resume failed: {}", e.message)
            }
        }
    }

    companion object {
        const val CREDENTIALS_NOTE = "Claude rejected the owner's credential; update it in Settings and resume."
        const val USAGE_LIMIT_ISSUE_NOTE = "Interrupted by the Claude usage limit; it is built again"
        const val CREDENTIALS_ISSUE_NOTE = "Interrupted because Claude rejected the owner's credential; it is built again"
        const val RESTART_NOTE = "Interrupted by a server restart; it is built again"
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

        /** The pause note and automatic resume time for a usage limit seen at [now]. */
        fun usageLimitPause(limit: UsageLimit, now: Instant): Pair<String, Instant> {
            val kind = limit.type?.takeIf { it.matches(Regex("[a-z0-9_]{1,40}")) }?.replace('_', ' ')
            val reset = limit.resetsAt?.takeIf { it.isAfter(now) }
            val note = buildString {
                append("The owner's Claude usage limit")
                if (kind != null) append(" ($kind)")
                append(" was reached")
                if (reset != null) append("; it resets at ${TIME.format(reset)}") else append("; the reset time is unknown")
                append('.')
            }
            val resumeAt = (reset?.plus(1, ChronoUnit.MINUTES) ?: now.plus(30, ChronoUnit.MINUTES)).truncatedTo(ChronoUnit.MILLIS)
            return note to resumeAt
        }
    }
}
