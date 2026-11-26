package dk.egernet.factory.pipeline

import dk.egernet.factory.FailureKind
import dk.egernet.factory.agent.StageAgentResult
import dk.egernet.factory.git.GitException
import dk.egernet.factory.github.GitHubException
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Which failures come from the environment rather than the work (docs/ARCHITECTURE.md §6 "Transient failures"): no
 * network or DNS, GitHub or the Claude API unreachable, GitHub 5xx. A run waits and retries them instead of failing; a
 * release pauses with reason `network` instead of holding the issue. Everything else fails as before.
 */
object Transient {
    /** How deep the cause chain is followed. */
    private const val MAX_DEPTH = 10

    /** Git's own words for a network problem (CliGitOps puts git's output into the [GitException] message). */
    private val GIT_NETWORK_RE = Regex(
        "(?i)Could not resolve host|Failed to connect to|Connection timed out|Connection refused|Connection reset|" +
            "Network is unreachable|No route to host|Temporary failure in name resolution|Operation timed out|" +
            "The requested URL returned error: 5\\d\\d|remote end hung up unexpectedly|early EOF|RPC failed|" +
            "gnutls_handshake|SSL_connect|TLS connection|git \\S+ timed out",
    )

    /** A refused push: never transient, even when git also reports the connection closing afterwards. */
    private val GIT_REFUSED_RE = Regex("(?i)Permission to \\S+ denied|protected branch|non-fast-forward|\\[rejected]|\\[remote rejected]")

    /** [StageFailed] kinds that are decided by the work (or the owner's account) and never retried. */
    private val FINAL_KINDS = setOf(FailureKind.BLOCKED, FailureKind.CREDENTIALS, FailureKind.USAGE_LIMIT, FailureKind.TIME_LIMIT)

    /** True when [e], or a cause along its chain (at most [MAX_DEPTH] levels), is a transient failure. */
    fun isTransient(e: Throwable): Boolean {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < MAX_DEPTH && seen.add(cur)) {
            when {
                cur is CancellationException -> return false
                cur is StageFailed && (cur.kind in FINAL_KINDS || cur.gaveUp) -> return false
                direct(cur) -> return true
            }
            cur = cur.cause
            depth++
        }
        return false
    }

    private fun direct(e: Throwable): Boolean = when (e) {
        is java.nio.file.FileSystemException, is java.io.FileNotFoundException -> false
        is java.io.IOException -> true
        is java.nio.channels.UnresolvedAddressException -> true
        is GitHubException -> e.status in 500..599
        is GitException -> isNetworkGitMessage(e.message)
        else -> false
    }

    /** True when git's output in [message] says the network failed (and not that the remote refused). */
    fun isNetworkGitMessage(message: String?): Boolean =
        message != null && GIT_NETWORK_RE.containsMatchIn(message) && !GIT_REFUSED_RE.containsMatchIn(message)

    /**
     * A stage agent that failed because the Claude API could not be reached: not stopped at the time limit, not a usage
     * limit or rejected credential, and its error matches [FailureKind.looksUnreachable].
     */
    fun isTransient(res: StageAgentResult): Boolean =
        !res.ok && !res.timedOut && res.usageLimit == null && !res.credentialRejected && FailureKind.looksUnreachable(res.error)

    /**
     * [e] for logs and notes: `<SimpleClassName>: <message>` (or the class name alone when the message is null or
     * blank), followed by ` (caused by <SimpleClassName>[: <message>])` for the first cause of a different class.
     * Never the string `null`.
     */
    fun describe(e: Throwable): String {
        val head = one(e)
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>()).apply { add(e) }
        var cur = e.cause
        var depth = 0
        while (cur != null && depth < MAX_DEPTH && seen.add(cur)) {
            if (cur.javaClass != e.javaClass) return "$head (caused by ${one(cur)})"
            cur = cur.cause
            depth++
        }
        return head
    }

    private fun one(e: Throwable): String {
        val name = e.javaClass.simpleName.ifEmpty { e.javaClass.name }
        val msg = e.message?.trim()
        return if (msg.isNullOrEmpty()) name else "$name: $msg"
    }
}
