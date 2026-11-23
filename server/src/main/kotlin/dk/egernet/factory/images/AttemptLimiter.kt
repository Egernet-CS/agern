package dk.egernet.factory.images

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * At most [max] recorded attempts per key within a sliding [window] (in memory; a restart forgets it). Used for
 * rejected image-provider keys and Codex login starts, per user (docs/ARCHITECTURE.md §6 "Generated images").
 */
class AttemptLimiter(private val max: Int, private val window: Duration, private val clock: Clock) {
    private val attempts = HashMap<Any, ArrayDeque<Instant>>()

    @Synchronized
    private fun fresh(key: Any, now: Instant): ArrayDeque<Instant>? {
        val q = attempts[key] ?: return null
        while (q.isNotEmpty() && !q.first().plus(window).isAfter(now)) q.removeFirst()
        if (q.isEmpty()) attempts.remove(key)
        return q.takeIf { it.isNotEmpty() }
    }

    /** When [key] may try again, if it is blocked now: the moment its oldest counted attempt leaves the window. */
    @Synchronized
    fun blockedUntil(key: Any): Instant? {
        val q = fresh(key, clock.instant()) ?: return null
        return if (q.size >= max) q.first().plus(window) else null
    }

    @Synchronized
    fun record(key: Any) {
        val now = clock.instant()
        if (attempts.size > 10_000) attempts.keys.toList().forEach { fresh(it, now) }
        fresh(key, now)
        attempts.getOrPut(key) { ArrayDeque() }.addLast(now)
    }
}
