package dk.egernet.factory.github

import dk.egernet.factory.Config
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.db.UserStore
import dk.egernet.factory.forbidden
import dk.egernet.factory.notFound
import dk.egernet.factory.security.SecretBox
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ClaudeCredential(val kind: String, val value: String) {
    override fun toString() = "ClaudeCredential($kind, ***)"
}

/**
 * Decrypts per-user secrets on demand and answers visibility questions.
 * Visibility of a repo == the user's own GitHub token can read it (cached 60 s per user+repo).
 */
class Access(
    private val cfg: Config,
    private val users: UserStore,
    private val box: SecretBox,
    val github: GitHub,
    private val ttlMillis: Long = 60_000,
) {
    private data class Entry(val repo: GhRepo?, val expires: Long)
    private val cache = ConcurrentHashMap<Pair<UUID, String>, Entry>()

    val org get() = cfg.githubOrg

    fun githubToken(u: UserRecord): String? = u.githubTokenEnc?.let { box.decrypt(it, u.username) }

    fun claudeCredential(u: UserRecord): ClaudeCredential? {
        val enc = u.claudeCredEnc ?: return null
        return ClaudeCredential(u.claudeCredKind ?: "oauth_token", box.decrypt(enc, u.username))
    }

    fun client(u: UserRecord): GitHubClient? = githubToken(u)?.let { github.client(it) }

    fun requireClient(u: UserRecord): GitHubClient =
        client(u) ?: throw forbidden("Register a GitHub token in Settings first")

    fun invalidate(userId: UUID) {
        cache.keys.removeIf { it.first == userId }
    }

    /** Drops the cached entries of [repo] for all users, e.g. after its open-issue count changed. */
    fun forgetRepo(repo: String) {
        cache.keys.removeIf { it.second == repo }
    }

    /** The repository as seen with the user's token, or null if they cannot read it. */
    fun visibleRepo(u: UserRecord, repo: String): GhRepo? {
        val key = u.id to repo
        val now = System.currentTimeMillis()
        cache[key]?.let { if (it.expires > now) return it.repo }
        val client = client(u) ?: return null
        val r = try { client.getRepo(cfg.githubOrg, repo) } catch (e: GitHubException) { null }
        cache[key] = Entry(r, now + ttlMillis)
        return r
    }

    fun canSee(u: UserRecord, repo: String): Boolean = visibleRepo(u, repo) != null

    /**
     * A run, session or anything recorded with them (transcripts, images, screenshots) of project [repo] that had the
     * member repositories [alsoRepos] checked out: visible only to whoever can read [repo] and every one of them
     * (docs/ARCHITECTURE.md §4 "Visibility").
     */
    fun canSee(u: UserRecord, repo: String, alsoRepos: Collection<String>): Boolean =
        canSee(u, repo) && alsoRepos.all { canSee(u, it) }

    fun requireVisible(u: UserRecord, repo: String): GhRepo =
        visibleRepo(u, repo) ?: throw notFound("Project '$repo' not found")

    /** Records repos learned from a listing so later checks hit the cache. */
    fun remember(u: UserRecord, repos: List<GhRepo>) {
        val exp = System.currentTimeMillis() + ttlMillis
        for (r in repos) cache[u.id to r.name] = Entry(r, exp)
    }

    fun userById(id: UUID) = users.byId(id)
}
