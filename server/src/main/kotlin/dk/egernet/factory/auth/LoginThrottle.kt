package dk.egernet.factory.auth

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Rate limit for the password step of login, per username and per client address.
 *
 * - Every failure counts against both keys; failures older than [window] are forgotten.
 * - [userMaxFailures] failures for a username (or [addrMaxFailures] from one address) lock that key
 *   temporarily. The lock doubles with each repeated lock (from [firstLock]) but never exceeds
 *   [maxLock], so an attacker can delay a real user, never lock them out for good.
 * - While a key is locked, login attempts are refused before any password is checked.
 * - Each failure also earns a small, growing response delay ([stepDelay] per recent failure, capped).
 * - A successful password clears the username's counter (not the address's, so one valid account
 *   cannot be used to reset an address that is guessing other users' passwords).
 *
 * State is in memory: a restart forgets it, which only ever helps the legitimate user.
 */
class LoginThrottle(
    val userMaxFailures: Int = 5,
    val addrMaxFailures: Int = 20,
    val window: Duration = Duration.ofMinutes(15),
    val firstLock: Duration = Duration.ofMinutes(1),
    val maxLock: Duration = Duration.ofMinutes(15),
    val stepDelay: Duration = Duration.ofMillis(250),
    val maxDelay: Duration = Duration.ofSeconds(2),
    private val clock: () -> Instant = Instant::now,
) {
    private class Counter(var failures: Int = 0, var windowStart: Instant, var lockedUntil: Instant? = null, var locks: Int = 0)

    /** A lock that was just imposed (for the audit log). */
    data class Lock(val key: String, val until: Instant)

    private val counters = ConcurrentHashMap<String, Counter>()

    private fun userKey(username: String) = "user:" + username.trim().lowercase().take(128)
    private fun addrKey(addr: String) = "addr:" + addr.take(128)

    /** Forgets stale failures; returns the counter only if it still matters. */
    private fun fresh(key: String, now: Instant): Counter? {
        val c = counters[key] ?: return null
        synchronized(c) {
            val lock = c.lockedUntil
            if (lock != null && !now.isBefore(lock)) c.lockedUntil = null
            if (c.lockedUntil == null && Duration.between(c.windowStart, now) > window) {
                c.failures = 0
                c.windowStart = now
                // The escalation is forgotten once a whole window has passed since the last lock ended.
                if (lock == null || Duration.between(lock, now) > window) c.locks = 0
            }
            if (c.failures == 0 && c.locks == 0 && c.lockedUntil == null) {
                counters.remove(key, c)
                return null
            }
        }
        return c
    }

    /** When login is refused for this username or address, the moment the (longest) lock ends; else null. */
    fun lockedUntil(username: String, addr: String): Instant? {
        val now = clock()
        return listOf(userKey(username), addrKey(addr)).mapNotNull { fresh(it, now)?.lockedUntil }.maxOrNull()
    }

    /** Records a wrong password; returns the locks this failure triggered. */
    fun recordFailure(username: String, addr: String): List<Lock> {
        val now = clock()
        if (counters.size > 100_000) prune(now)
        val locks = mutableListOf<Lock>()
        for ((key, max) in listOf(userKey(username) to userMaxFailures, addrKey(addr) to addrMaxFailures)) {
            fresh(key, now)
            val c = counters.computeIfAbsent(key) { Counter(windowStart = now) }
            synchronized(c) {
                c.failures++
                if (c.failures >= max) {
                    val factor = 1L shl c.locks.coerceAtMost(20)
                    val d = firstLock.multipliedBy(factor).let { if (it > maxLock) maxLock else it }
                    c.lockedUntil = now.plus(d)
                    c.locks++
                    c.failures = 0
                    c.windowStart = now
                    locks += Lock(key, c.lockedUntil!!)
                }
            }
        }
        return locks
    }

    fun recordSuccess(username: String) {
        counters.remove(userKey(username))
    }

    /** Delay before answering a failed attempt: grows with the username's recent failures. */
    fun failureDelay(username: String): Duration {
        val n = fresh(userKey(username), clock())?.failures ?: 0
        val d = stepDelay.multipliedBy(n.toLong().coerceAtLeast(1))
        return if (d > maxDelay) maxDelay else d
    }

    private fun prune(now: Instant) {
        for (k in counters.keys) fresh(k, now)
    }
}
