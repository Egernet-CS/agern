package dk.egernet.factory

import dk.egernet.factory.agent.EventBus
import dk.egernet.factory.agent.FeedItem
import dk.egernet.factory.db.ReleaseRecord
import dk.egernet.factory.db.ReleaseStore
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.db.UserStore
import dk.egernet.factory.github.Access
import dk.egernet.factory.github.GitHubException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Starts a due scheduled release now as [owner]. Bound to [ReleaseService.startScheduled] (the same start as "start
 * now", §6 "Releases"); tests may substitute it. Must move the release out of `scheduled`; throws ApiException to refuse.
 */
fun interface ReleaseStarter {
    suspend fun start(release: ReleaseRecord, owner: UserRecord)
}

/**
 * Scheduled start of releases (docs/ARCHITECTURE.md §6 "Releases", "Scheduled start"). Setting, changing and
 * cancelling a schedule and the handling of each due release are serialised by one mutex, and each re-reads the
 * release once it holds it. All state is in the database, so a restart only needs the loop started again; all time
 * comparisons use [clock].
 */
class ReleaseScheduler(
    private val cfg: Config,
    private val clock: Clock,
    private val store: ReleaseStore,
    private val users: UserStore,
    private val access: Access,
    private val bus: EventBus,
    private val starter: ReleaseStarter,
) {
    companion object {
        const val TICK_MILLIS = 15_000L
        val MAX_AHEAD: Duration = Duration.ofDays(366)
        private val REJECTED = setOf(401, 403, 404)
    }

    private val log = LoggerFactory.getLogger(ReleaseScheduler::class.java)
    private val mutex = Mutex()

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private suspend fun current(r: ReleaseRecord): ReleaseRecord = io { store.get(r.id) } ?: throw notFound("Release not found")

    private suspend fun publish(r: ReleaseRecord): ReleaseRecord {
        io { store.touch(r.id) }
        val now = current(r)
        bus.emitFeed(FeedItem.Release(now.toDto()))
        return now
    }

    private fun requireOwnerOrAdmin(user: UserRecord, r: ReleaseRecord) {
        if (!user.isAdmin && user.id != r.ownerId) {
            throw forbidden("Only ${r.ownerUsername ?: "its owner"} or an admin can change this schedule")
        }
    }

    /** Sets the start of a release in preparation (caller becomes the owner) or changes it (owner or admin). */
    suspend fun schedule(user: UserRecord, release: ReleaseRecord, startAt: Instant): ReleaseRecord {
        val now = clock.instant()
        if (!startAt.isAfter(now)) throw badRequest("The start time must be in the future")
        if (startAt.isAfter(now.plus(MAX_AHEAD))) throw badRequest("The start time is too far ahead (at most a year)")
        val at = startAt.truncatedTo(ChronoUnit.MILLIS)
        return mutex.withLock {
            val r = current(release)
            when (r.status) {
                ReleaseStatus.PLANNING -> {
                    if (user.claudeCredEnc == null) throw unprocessable("Register a Claude credential in Settings first", "claude_credential_required")
                    access.requireClient(user)
                    if (!io { store.schedule(r.id, user.id, at) }) throw notSchedulable(current(r).status)
                }
                ReleaseStatus.SCHEDULED -> {
                    requireOwnerOrAdmin(user, r)
                    if (!io { store.reschedule(r.id, at) }) throw notSchedulable(current(r).status)
                }
                else -> throw notSchedulable(r.status)
            }
            publish(r)
        }
    }

    private fun notSchedulable(status: String) =
        conflict("Release is $status; only a release in preparation can be scheduled", "release_not_schedulable")

    private fun notScheduled() = conflict("This release has no scheduled start", "release_not_scheduled")

    /** Cancels the scheduled start: the release is a shared plan again (owner cleared). */
    suspend fun cancel(user: UserRecord, release: ReleaseRecord): ReleaseRecord = mutex.withLock {
        val r = current(release)
        if (r.status != ReleaseStatus.SCHEDULED) throw notScheduled()
        requireOwnerOrAdmin(user, r)
        if (!io { store.unschedule(r.id) }) throw notScheduled()
        publish(r)
    }

    /** Handles every due release, oldest first. One failing release does not stop the others. */
    suspend fun tick() {
        val due = io { store.due(clock.instant()) }
        for (d in due) {
            try {
                mutex.withLock { handleDue(d) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Scheduled start of release {} ({}) could not be handled; retrying at the next tick", d.id, d.repo, e)
            }
        }
    }

    private suspend fun handleDue(release: ReleaseRecord) {
        val r = io { store.get(release.id) } ?: return
        val now = clock.instant()
        if (r.status != ReleaseStatus.SCHEDULED || r.scheduledAt == null || r.scheduledAt.isAfter(now)) return

        // 1. Waiting behind a release of the same project that is still building.
        val other = io { store.activeIn(r.repo) }?.takeIf { it.id != r.id }
        if (other != null) {
            if (io { store.setWaitingFor(r.id, other.id) }) publish(r)
            return
        }
        if (r.waitingForId != null && io { store.setWaitingFor(r.id, null) }) publish(r)

        // 2. The owner's credentials, checked afresh.
        val owner = r.ownerId?.let { id -> io { users.byId(id) } }
            ?: return failStart(r, "The owner's account no longer exists.")
        if (owner.claudeCredEnc == null) {
            return failStart(r, "${owner.username} has no Claude credential registered. Register one in Settings and schedule the release again.")
        }
        val token = io { access.githubToken(owner) }
            ?: return failStart(r, "${owner.username} has no GitHub token registered. Register one in Settings and schedule the release again.")
        val canRead = try {
            io {
                try {
                    access.github.client(token).getRepo(cfg.githubOrg, r.repo) != null
                } catch (e: GitHubException) {
                    if (e.status in REJECTED) false else throw e
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Transient (5xx, I/O): stay scheduled and due; the next tick tries again. No token in the message.
            log.warn("Could not check {}'s GitHub access to {} for release {}; retrying at the next tick: {}", owner.username, r.repo, r.id, e.message)
            return
        }
        if (!canRead) {
            return failStart(
                r, "GitHub rejected ${owner.username}'s token or it can no longer read ${r.repo}. " +
                    "Update the token in Settings and schedule the release again.",
            )
        }

        // 3. Start.
        val reason: String? = try {
            starter.start(r, owner)
            if (current(r).status == ReleaseStatus.SCHEDULED) "The release did not start." else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // Another release took the project's building slot in the meantime ("start now"): wait behind it instead.
            if (e.code == "release_already_building" && current(r).status == ReleaseStatus.SCHEDULED) return
            e.message
        } catch (e: Exception) {
            log.error("Starting release {} ({}) failed", r.id, r.repo, e)
            "Internal error while starting the release; see the server log."
        }
        if (reason != null) failStart(r, reason) else publish(r)
    }

    private suspend fun failStart(r: ReleaseRecord, reason: String) {
        if (io { store.failStart(r.id, clock.instant(), r.ownerId, reason) }) publish(r)
    }

    /** Ticks at once and then every 15 seconds, so a time missed while the server was down is handled at start. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Release scheduler tick failed", e)
            }
            delay(TICK_MILLIS)
        }
    }
}
