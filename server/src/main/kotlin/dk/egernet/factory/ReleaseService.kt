package dk.egernet.factory

import dk.egernet.factory.agent.EventBus
import dk.egernet.factory.agent.FeedItem
import dk.egernet.factory.db.IssueToAssign
import dk.egernet.factory.db.ReleaseRecord
import dk.egernet.factory.db.ReleaseStore
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.github.Access
import dk.egernet.factory.github.GitHubException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Release planning (docs/ARCHITECTURE.md §6 "Releases"), shared by the HTTP API, `/factory` commands and MCP tools.
 * Reading and editing a release in preparation is open to every user who can see its project. Every change is
 * announced on the feed, once per affected release.
 */
class ReleaseService(
    private val cfg: Config,
    private val access: Access,
    private val store: ReleaseStore,
    private val bus: EventBus,
) {
    companion object {
        /** Safe as the git branch `release/<name>` and in URLs. */
        private val NAME_RE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$")
        const val NAME_RULE = "A release name is 1 to 50 letters, digits, '.', '_' or '-', starts with a letter or digit, " +
            "and must not contain '..' or end with '.' or '.lock'."
        const val MAX_DESCRIPTION = 2000
        const val MAX_ISSUES_PER_REQUEST = 100

        fun validName(raw: String): String {
            val name = raw.trim()
            if (!NAME_RE.matches(name) || name.contains("..") || name.endsWith(".") || name.endsWith(".lock")) throw badRequest(NAME_RULE)
            return name
        }

        fun validDescription(raw: String): String {
            val d = raw.trim()
            if (d.length > MAX_DESCRIPTION) throw badRequest("The description is too long (at most $MAX_DESCRIPTION characters)")
            return d
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private suspend fun <T> gh(block: () -> T): T = io {
        try {
            block()
        } catch (e: GitHubException) {
            when (e.status) {
                401 -> throw unprocessable("GitHub rejected your token", "github_token_invalid")
                403, 404 -> throw notFound(e.message ?: "Not found")
                422 -> throw unprocessable(e.message ?: "GitHub rejected the request")
                else -> throw ApiException(io.ktor.http.HttpStatusCode.BadGateway, "github_error", e.message ?: "GitHub error")
            }
        }
    }

    private suspend fun requireVisible(user: UserRecord, repo: String) {
        io { access.requireVisible(user, repo) }
    }

    /** The release by id, if [user] can see its project; 404 otherwise (never 403). */
    suspend fun visible(user: UserRecord, id: UUID): ReleaseRecord {
        val r = io { store.get(id) } ?: throw notFound("Release not found")
        if (!io { access.canSee(user, r.repo) }) throw notFound("Release not found")
        return r
    }

    private suspend fun publish(ids: Collection<UUID>) {
        for (id in ids) {
            io { store.touch(id) }
            val r = io { store.get(id) } ?: continue
            bus.emitFeed(FeedItem.Release(r.toDto()))
        }
    }

    suspend fun list(user: UserRecord, repo: String): List<ReleaseRecord> {
        requireVisible(user, repo)
        return io { store.list(repo) }
    }

    /** A release of [repo] by name (case-insensitive) or id; 404 naming the existing releases otherwise. */
    suspend fun find(user: UserRecord, repo: String, nameOrId: String): ReleaseRecord {
        requireVisible(user, repo)
        val key = nameOrId.trim()
        runCatching { UUID.fromString(key) }.getOrNull()?.let { id -> io { store.get(id) }?.takeIf { it.repo == repo }?.let { return it } }
        io { store.byName(repo, key) }?.let { return it }
        val names = io { store.list(repo) }.map { it.name }
        throw notFound(
            "No release \"$key\" in $repo. " +
                if (names.isEmpty()) "There are no releases yet." else "Releases: ${names.joinToString(", ")}.",
        )
    }

    suspend fun create(user: UserRecord, repo: String, name: String, description: String): ReleaseRecord {
        requireVisible(user, repo)
        val n = validName(name)
        val d = validDescription(description)
        val r = io { store.create(repo, n, d, user.id) }
        bus.emitFeed(FeedItem.Release(r.toDto()))
        return r
    }

    suspend fun update(user: UserRecord, id: UUID, name: String, description: String): ReleaseRecord {
        visible(user, id)
        val n = validName(name)
        val d = validDescription(description)
        io { store.update(id, n, d) }
        val r = io { store.get(id) } ?: throw notFound("Release not found")
        bus.emitFeed(FeedItem.Release(r.toDto()))
        return r
    }

    suspend fun delete(user: UserRecord, id: UUID) {
        val r = visible(user, id)
        if (io { store.delete(id) }) bus.emitFeed(FeedItem.ReleaseDeleted(r.repo, r.id.toString()))
    }

    /** What an assignment did, for `/factory` and MCP replies. */
    data class AssignOutcome(val release: ReleaseRecord?, val added: List<Int>, val movedFrom: Map<String, List<Int>>, val removed: List<Int>)

    private fun validNumbers(numbers: List<Int>): List<Int> {
        if (numbers.isEmpty() || numbers.size > MAX_ISSUES_PER_REQUEST) throw badRequest("Give 1 to $MAX_ISSUES_PER_REQUEST issue numbers")
        if (numbers.any { it <= 0 }) throw badRequest("Issue numbers must be positive")
        return numbers.distinct()
    }

    /**
     * Puts the issues into [releaseId] (moving them from another release), or takes them out of their release when
     * [releaseId] is null. All-or-nothing: every issue is checked on GitHub (exists, open) before anything changes.
     */
    suspend fun assign(user: UserRecord, repo: String, numbers: List<Int>, releaseId: UUID?): AssignOutcome {
        requireVisible(user, repo)
        val nums = validNumbers(numbers)
        if (releaseId == null) {
            val before = io { store.releaseByIssue(repo) }
            val changed = io { store.unassign(repo, nums) }
            publish(changed)
            return AssignOutcome(null, emptyList(), emptyMap(), nums.filter { it in before })
        }
        val target = io { store.get(releaseId) }?.takeIf { it.repo == repo } ?: throw notFound("Release not found")
        val client = access.requireClient(user)
        val issues = nums.map { n ->
            val i = gh { client.getIssue(cfg.githubOrg, repo, n) } ?: throw notFound("Issue #$n not found")
            if (i.state != "open") throw conflict("Issue #$n is closed; only open issues can be planned in a release", "issue_closed")
            IssueToAssign(n, i.title, issueKindFromLabels(i.labels.map { it.name }))
        }
        val names = io { store.list(repo) }.associate { it.id to it.name }
        val res = io { store.assign(repo, target.id, issues, user.id) }
        publish(res.changed)
        return AssignOutcome(
            io { store.get(target.id) }, res.added,
            res.movedFrom.mapKeys { (id, _) -> names[id] ?: id.toString() }, emptyList(),
        )
    }

    suspend fun reorder(user: UserRecord, id: UUID, numbers: List<Int>): ReleaseRecord {
        val r = visible(user, id)
        if (numbers.size > 10_000) throw badRequest("Too many issue numbers")
        io { store.reorder(r.repo, id, numbers) }
        publish(listOf(id))
        return io { store.get(id) } ?: throw notFound("Release not found")
    }

    /** Release id and name per assigned issue, for [IssueDto.release]. */
    fun releaseByIssue(repo: String): Map<Int, IssueReleaseDto> =
        store.releaseByIssue(repo).mapValues { (_, v) -> IssueReleaseDto(v.first.toString(), v.second) }

    /** Plain-text overview for `/factory releases` and `factory_list_releases`. */
    suspend fun describe(user: UserRecord, repo: String, withIds: Boolean): String {
        val list = list(user, repo)
        if (list.isEmpty()) return "No releases in $repo yet."
        return list.joinToString("\n\n", "Releases in $repo (newest first):\n") { r ->
            val n = r.issues.size
            buildString {
                append("• ${r.name} — in preparation, $n issue${if (n == 1) "" else "s"}")
                if (withIds) append(" (id ${r.id})")
                r.issues.forEach { append("\n    #${it.number} ${it.title}") }
            }
        }
    }
}
