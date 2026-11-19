package dk.egernet.factory.agent

import dk.egernet.factory.AgentSessionDto
import dk.egernet.factory.EventDto
import dk.egernet.factory.IssueDto
import dk.egernet.factory.RunDto
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A change visible on the feed. [repo] is used for the per-user visibility filter. */
sealed class FeedItem(val repo: String) {
    class Session(val session: AgentSessionDto) : FeedItem(session.repo)
    class Run(val run: RunDto) : FeedItem(run.repo)

    /** An issue created through Agern, in list shape (no body). */
    class Issue(repo: String, val issue: IssueDto) : FeedItem(repo)
}

/** In-process fan-out of live events. Persisted state is in PostgreSQL; this only carries notifications. */
class EventBus {
    private val sessions = ConcurrentHashMap<UUID, MutableSharedFlow<EventDto>>()
    private val feedFlow = MutableSharedFlow<FeedItem>(extraBufferCapacity = 4096, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun session(id: UUID): SharedFlow<EventDto> = flow(id)

    private fun flow(id: UUID) = sessions.computeIfAbsent(id) {
        MutableSharedFlow(extraBufferCapacity = 4096, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }

    fun emit(id: UUID, e: EventDto) {
        flow(id).tryEmit(e)
    }

    val feed: SharedFlow<FeedItem> get() = feedFlow

    /** Number of live feed collectors (used by tests to wait for WebSocket subscriptions). */
    val feedSubscribers: StateFlow<Int> get() = feedFlow.subscriptionCount
    fun emitFeed(item: FeedItem) {
        feedFlow.tryEmit(item)
    }
}
