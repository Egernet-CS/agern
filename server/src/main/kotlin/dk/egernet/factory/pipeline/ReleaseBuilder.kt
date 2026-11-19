package dk.egernet.factory.pipeline

import dk.egernet.factory.ApiException
import dk.egernet.factory.Config
import dk.egernet.factory.PauseReason
import dk.egernet.factory.ReleaseIssueState
import dk.egernet.factory.ReleaseStatus
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
) {
    private val log = LoggerFactory.getLogger(ReleaseBuilder::class.java)
    private val locks = ConcurrentHashMap<UUID, Mutex>()
    private val org get() = cfg.githubOrg

    private fun lock(id: UUID) = locks.computeIfAbsent(id) { Mutex() }
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /** Announces the release's current state on the feed. */
    suspend fun publish(id: UUID) {
        io { store.touch(id) }
        val r = io { store.get(id) } ?: return
        bus.emitFeed(FeedItem.Release(r.toDto()))
    }

    // ------------------------------------------------------------ commands

    /**
     * Claims the planned release [r] for [owner], creates `release/<name>` from [defaultBranch] with [client] (the
     * owner's token), skip-checks every issue and starts the first run. Any failure before the branch exists rolls the
     * claim back, so the release is `planning` again with no owner.
     */
    suspend fun start(owner: UserRecord, r: ReleaseRecord, client: GitHubClient, defaultBranch: String): ReleaseRecord {
        val branch = "release/${r.name}"
        if (!io { store.claim(r.id, owner.id, branch) }) {
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
            io { store.unclaim(r.id) }
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
     * `building` or `paused` → `cancelled`: the running issue is held, every active run of the release is cancelled
     * (as [user]), waiting issues stay waiting and nothing starts afterwards. The branch is left alone.
     */
    suspend fun cancel(user: UserRecord, id: UUID) {
        lock(id).withLock {
            if (!io { store.cancel(id) }) throw conflict("The release is not building", "release_not_building")
            // The issue row first: the run's own outcome then finds it held and leaves it so.
            io { store.setIssuesInState(id, ReleaseIssueState.RUNNING, ReleaseIssueState.HELD, "Cancelled with the release") }
        }
        publish(id)
        for (run in io { runs.activeByRelease(id) }) cancelRun(user, run.id)
    }

    /** A held or skipped issue is built again: back to `waiting`, its active run (if any) cancelled. */
    suspend fun retry(owner: UserRecord, id: UUID, number: Int) {
        val oldRun = lock(id).withLock {
            val r = io { store.get(id) } ?: throw dk.egernet.factory.notFound("Release not found")
            if (r.status !in ReleaseStatus.ACTIVE) throw conflict("The release is not building", "release_not_building")
            val issue = r.issues.firstOrNull { it.number == number } ?: throw dk.egernet.factory.notFound("Issue #$number is not in this release")
            if (issue.state != ReleaseIssueState.HELD && issue.state != ReleaseIssueState.SKIPPED) {
                throw conflict("Issue #$number is ${issue.state}; only held or skipped issues can be retried", "release_issue_not_held")
            }
            io { store.setIssue(id, number, ReleaseIssueState.WAITING, null, null) }
            issue.runId
        }
        publish(id)
        if (oldRun != null && io { runs.get(oldRun) }?.status in RunStatus.ACTIVE) cancelRun(owner, oldRun)
        advance(id)
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
     * whose run cannot start are passed over; a missing credential pauses the release.
     */
    suspend fun advance(id: UUID) {
        lock(id).withLock {
            while (true) {
                val r = io { store.get(id) } ?: return
                if (r.status != ReleaseStatus.BUILDING) return
                if (r.issues.any { it.state == ReleaseIssueState.RUNNING }) return
                val next = r.issues.firstOrNull { it.state == ReleaseIssueState.WAITING } ?: return
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
                    val run = orchestrator.startReleaseRun(owner, r.repo, next.number, r.id, r.branch ?: "release/${r.name}")
                    io { store.setIssue(id, next.number, ReleaseIssueState.RUNNING, null, run.id) }
                    publish(id)
                    return
                } catch (e: ApiException) {
                    if (e.code == "claude_credential_required") {
                        pauseForCredentials(r.id)
                        return
                    }
                    holdUnstarted(id, next.number, e.message)
                } catch (e: Exception) {
                    holdUnstarted(id, next.number, e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    private suspend fun holdUnstarted(id: UUID, number: Int, reason: String) {
        log.warn("Release {}: could not start a run for #{}: {}", id, number, reason)
        io { store.setIssue(id, number, ReleaseIssueState.HELD, "Could not start a run: ${reason.take(500)}", null) }
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
                            io { store.setIssue(ref.releaseId, ref.number, ReleaseIssueState.WAITING, USAGE_LIMIT_ISSUE_NOTE, run.id) }
                            val (pauseNote, resumeAt) = usageLimitPause(outcome.usageLimit, Instant.now())
                            pauseLocked(ref.releaseId, PauseReason.USAGE_LIMIT, pauseNote, resumeAt)
                        }
                        outcome.credentialRejected && run.status == RunStatus.FAILED -> {
                            io { store.setIssue(ref.releaseId, ref.number, ReleaseIssueState.WAITING, CREDENTIALS_ISSUE_NOTE, run.id) }
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
                else -> store.setIssue(ref.releaseId, ref.number, ReleaseIssueState.WAITING, RESTART_NOTE, ref.runId)
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
