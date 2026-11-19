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

/** A change visible on the feed. [repo] is used for the per-user visibility filter. */
sealed class FeedItem(val repo: String) {
    class Session(val session: AgentSessionDto) : FeedItem(session.repo)
    class Run(val run: RunDto) : FeedItem(run.repo)

    /** An issue created through Agern, in list shape (no body). */
    class Issue(repo: String, val issue: IssueDto) : FeedItem(repo)

    /** A chat session that was closed (deleted). */
    class SessionClosed(repo: String, val sessionId: String) : FeedItem(repo)
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
