package dk.egernet.factory.workspace

import dk.egernet.factory.ApiException
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.github.Access
import dk.egernet.factory.github.GitHubException
import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Makes sure the issue labels of a workspace's members exist in the workspace repository (docs/ARCHITECTURE.md §5
 * "Workspace projects", "Member labels"), with the acting user's own token. Existing labels are never changed.
 */
class MemberLabels(private val access: Access, private val ttlMillis: Long = 5 * 60_000) {
    private val log = LoggerFactory.getLogger(MemberLabels::class.java)

    /** [created]: labels this call created; [missing]: member labels that still do not exist (creation refused). */
    data class Outcome(val created: List<String>, val missing: List<String>, val forbidden: Boolean)

    private data class Entry(val definition: String, val missing: List<String>, val expires: Long)
    private val cache = ConcurrentHashMap<Pair<UUID, String>, Entry>()

    fun invalidate() = cache.clear()

    /**
     * The member labels of [state] missing in [repo] for [user], after creating the missing ones once: at most one
     * attempt per user, repository and definition (member labels and directories) within [ttlMillis]. Empty for an
     * invalid definition or when GitHub cannot be read. Blocking; never throws.
     */
    fun missing(user: UserRecord, repo: String, state: WorkspaceState): List<String> {
        if (!state.valid || state.members.isEmpty()) return emptyList()
        val key = user.id to repo
        val def = definitionKey(state)
        cache[key]?.takeIf { it.definition == def && it.expires > System.currentTimeMillis() }?.let { return it.missing }
        return try {
            ensure(user, repo, state).missing
        } catch (e: Exception) {
            log.warn("Checking the member labels of {} failed: {}", repo, e.message)
            emptyList()
        }
    }

    /**
     * Lists [repo]'s labels and creates every member label of [state] that is missing (name = the member's label, a
     * colour derived from it, a description naming the member). A label that exists meanwhile (422) counts as there;
     * 403/404 (the token may not create labels) leaves it missing with [Outcome.forbidden]. Remembers the result for
     * [missing]. Blocking; throws [GitHubException] when the labels cannot be listed.
     */
    fun ensure(user: UserRecord, repo: String, state: WorkspaceState): Outcome {
        val client = access.client(user) ?: throw ApiException(HttpStatusCode.UnprocessableEntity, "github_token_required", "Register a GitHub token first")
        val existing = client.listLabels(access.org, repo).map { it.name.lowercase() }.toSet()
        val wanted = state.members.distinctBy { it.label.lowercase() }.filter { it.label.lowercase() !in existing }
        val created = mutableListOf<String>()
        val missing = mutableListOf<String>()
        var forbidden = false
        for (m in wanted) {
            try {
                if (client.createLabel(access.org, repo, m.label, color(m.label), description(m))) {
                    created += m.label
                    log.info("Created the member label {} in {}", m.label, repo)
                }
            } catch (e: GitHubException) {
                if (e.status == 403 || e.status == 404) forbidden = true
                log.warn("Creating the member label {} in {} failed: {}", m.label, repo, e.message)
                missing += m.label
            }
        }
        cache[user.id to repo] = Entry(definitionKey(state), missing, System.currentTimeMillis() + ttlMillis)
        return Outcome(created, missing, forbidden)
    }

    private fun definitionKey(state: WorkspaceState) = state.members.joinToString("\n") { "${it.label}\u0000${it.directory}\u0000${it.repository}" }

    companion object {
        /** A stable colour for [label]: 6 hex digits from its SHA-256, darkened a little so white text stays readable. */
        fun color(label: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(label.lowercase().toByteArray(Charsets.UTF_8))
            return (0..2).joinToString("") { "%02x".format((d[it].toInt() and 0xff) * 3 / 4) }
        }

        /** "Runs change the member `<directory>` (<repository>)", at most 100 characters (GitHub's limit). */
        fun description(m: WorkspaceMember) = "Runs change the member ${m.directory} (${m.repository})".take(100)
    }
}
