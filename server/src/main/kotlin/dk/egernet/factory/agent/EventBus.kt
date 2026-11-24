package dk.egernet.factory.agent

import dk.egernet.factory.AgentSessionDto
import dk.egernet.factory.EventDto
import dk.egernet.factory.IssueDto
import dk.egernet.factory.RunDto
import dk.egernet.factory.db.iso
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonNull
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A change visible on the feed. [repo] is used for the per-user visibility filter, together with [alsoRepos]: the
 * member repositories a workspace run or session had checked out (docs/ARCHITECTURE.md §4 "Visibility").
 */
sealed class FeedItem(val repo: String, val alsoRepos: List<String> = emptyList()) {
    class Session(val session: AgentSessionDto, alsoRepos: List<String> = emptyList()) : FeedItem(session.repo, alsoRepos)
    class Run(val run: RunDto, alsoRepos: List<String> = emptyList()) : FeedItem(run.repo, alsoRepos)

    /** An issue created through Agern, in list shape (no body). */
    class Issue(repo: String, val issue: IssueDto) : FeedItem(repo)

    /** A chat session that was closed (deleted). */
    class SessionClosed(repo: String, val sessionId: String) : FeedItem(repo)

    /** A release after a change (created, edited, issues added, removed, moved or reordered, or its build state changed). */
    class Release(val release: dk.egernet.factory.ReleaseDto) : FeedItem(release.repo)

    /** A release that was deleted. */
    class ReleaseDeleted(repo: String, val releaseId: String) : FeedItem(repo)

    /** A project's sandbox approval after an admin approved or revoked it (null: revoked). */
    class SandboxApproval(repo: String, val approval: dk.egernet.factory.SandboxApprovalDto?) : FeedItem(repo)

    /**
     * A container of a run's sandbox was created or removed, or an admin stopped the sandbox ([change]: `created`,
     * `removed`, `stopped`). Sent to admins only (whatever they can see, like the overview), so the admin page updates live.
     */
    class Sandboxes(repo: String, val runId: String, val change: String) : FeedItem(repo)

    /**
     * The Claude usage of [userId] changed (limit windows recorded, spend added, credential changed). Private to that
     * user: sent only to their own feed connections, never filtered by repository, and it carries no data (the client
     * reloads `GET /api/me/usage`).
     */
    class Usage(val userId: UUID) : FeedItem("")
}

/** In-process fan-out of live events. Persisted state is in PostgreSQL; this only carries notifications. */
class EventBus {
    companion object {
        /** Type of the internal sentinel event that tells session subscribers the session was closed; never sent to clients. */
        const val CLOSED = "closed"
    }

    private val sessions = ConcurrentHashMap<UUID, MutableSharedFlow<EventDto>>()
    private val feedFlow = MutableSharedFlow<FeedItem>(extraBufferCapacity = 4096, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun session(id: UUID): SharedFlow<EventDto> = flow(id)

    private fun flow(id: UUID) = sessions.computeIfAbsent(id) {
        MutableSharedFlow(extraBufferCapacity = 4096, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }

    fun emit(id: UUID, e: EventDto) {
        flow(id).tryEmit(e)
    }

    /** Tells the session's current subscribers it is gone (sentinel [CLOSED] event) and forgets its flow. */
    fun closeSession(id: UUID) {
        sessions[id]?.tryEmit(EventDto(-1, id.toString(), Instant.now().iso(), CLOSED, JsonNull))
        sessions.remove(id)
    }

    val feed: SharedFlow<FeedItem> get() = feedFlow

    /** Number of live feed collectors (used by tests to wait for WebSocket subscriptions). */
    val feedSubscribers: StateFlow<Int> get() = feedFlow.subscriptionCount
    fun emitFeed(item: FeedItem) {
        feedFlow.tryEmit(item)
    }
}
