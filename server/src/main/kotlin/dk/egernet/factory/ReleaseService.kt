package dk.egernet.factory

import dk.egernet.factory.agent.EventBus
import dk.egernet.factory.agent.FeedItem
import dk.egernet.factory.db.IssueToAssign
import dk.egernet.factory.db.ReleaseActor
import dk.egernet.factory.db.ReleaseRecord
import dk.egernet.factory.db.ReleaseStore
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.db.releaseForbidden
import dk.egernet.factory.github.Access
import dk.egernet.factory.github.GitHubException
import dk.egernet.factory.pipeline.ReleaseBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Releases (docs/ARCHITECTURE.md §6 "Releases"), shared by the HTTP API, `/factory` commands and MCP tools.
 * Reading and editing a release in preparation is open to every user who can see its project; once started, changes
 * are limited to its owner, and admins may stop it (pause, cancel, take issues out, delete it when cancelled) but never
 * drive it. Visibility is checked first (404), then these rules (403). Every change is announced on the feed, once
 * per affected release. Building itself is [ReleaseBuilder]'s.
 */
class ReleaseService(
    private val cfg: Config,
    private val access: Access,
    private val store: ReleaseStore,
    private val bus: EventBus,
    private val builder: ReleaseBuilder,
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
        for (id in ids) builder.publish(id)
    }

    private fun actor(user: UserRecord) = ReleaseActor(user.id, user.isAdmin)
    private fun isOwner(user: UserRecord, r: ReleaseRecord) = r.ownerId == user.id

    /** Owner-only actions on a started release: they make agents run on the owner's credentials. */
    private fun requireOwner(user: UserRecord, r: ReleaseRecord) {
        if (!isOwner(user, r)) throw releaseForbidden(r.ownerUsername, adminAllowed = false)
    }

    /** Stopping actions on a started release: its owner or an admin. */
    private fun requireOwnerOrAdmin(user: UserRecord, r: ReleaseRecord) {
        if (!isOwner(user, r) && !user.isAdmin) throw releaseForbidden(r.ownerUsername, adminAllowed = true)
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
        val r = visible(user, id)
        if (r.started) requireOwnerOrAdmin(user, r)
        val n = validName(name)
        val d = validDescription(description)
        if (r.started && n != r.name) throw conflict("The name is fixed once the release is started", "release_name_locked")
        io { store.update(id, n, d) }
        val after = io { store.get(id) } ?: throw notFound("Release not found")
        bus.emitFeed(FeedItem.Release(after.toDto()))
        return after
    }

    /** Planned releases as before; a cancelled one by its owner or an admin (its branch and runs stay). */
    suspend fun delete(user: UserRecord, id: UUID) {
        val r = visible(user, id)
        if (r.status in ReleaseStatus.ACTIVE) throw conflict("Cancel the release first", "release_active")
        if (r.status == ReleaseStatus.CANCELLED) requireOwnerOrAdmin(user, r)
        if (io { store.delete(id) }) {
            bus.emitFeed(FeedItem.ReleaseDeleted(r.repo, r.id.toString()))
        } else if (io { store.get(id) }?.status in ReleaseStatus.ACTIVE) {
            throw conflict("Cancel the release first", "release_active")
        }
    }

    // ------------------------------------------------------------ building

    /** Starts a planned release; the caller becomes its owner (docs/ARCHITECTURE.md §6 "Releases", starting). */
    suspend fun start(user: UserRecord, id: UUID): ReleaseRecord {
        val r = visible(user, id)
        if (r.status != ReleaseStatus.PLANNING) throw conflict("The release is ${r.status}, not in preparation", "release_not_planning")
        if (user.claudeCredEnc == null) throw unprocessable("Register a Claude credential in Settings first", "claude_credential_required")
        val client = access.requireClient(user)
        if (r.issues.isEmpty()) throw conflict("Add an issue to the release first", "release_empty")
        io { store.activeIn(r.repo) }?.takeIf { it.id != r.id }?.let {
            throw conflict("Release \"${it.name}\" of this project is already ${it.status}; only one release can build at a time", "release_already_building")
        }
        val repo = io { access.requireVisible(user, r.repo) }
        return builder.start(user, r, client, repo.defaultBranch)
    }

    suspend fun pause(user: UserRecord, id: UUID): ReleaseRecord {
        val r = visible(user, id)
        if (r.started) requireOwnerOrAdmin(user, r)
        if (r.status != ReleaseStatus.BUILDING) throw conflict("The release is not building", "release_not_building")
        builder.pause(user, id)
        return io { store.get(id) } ?: throw notFound("Release not found")
    }

    suspend fun resume(user: UserRecord, id: UUID): ReleaseRecord {
        val r = visible(user, id)
        if (r.started) requireOwner(user, r)
        if (r.status != ReleaseStatus.PAUSED) throw conflict("The release is not paused", "release_not_paused")
        if (user.claudeCredEnc == null) throw unprocessable("Register a Claude credential in Settings first", "claude_credential_required")
        builder.resume(id)
        return io { store.get(id) } ?: throw notFound("Release not found")
    }

    suspend fun cancel(user: UserRecord, id: UUID): ReleaseRecord {
        val r = visible(user, id)
        if (r.started) requireOwnerOrAdmin(user, r)
        if (r.status !in ReleaseStatus.ACTIVE) throw conflict("The release is not building", "release_not_building")
        builder.cancel(user, id)
        return io { store.get(id) } ?: throw notFound("Release not found")
    }

    suspend fun retry(user: UserRecord, id: UUID, number: Int): ReleaseRecord {
        val r = visible(user, id)
        if (r.started) requireOwner(user, r)
        if (r.status !in ReleaseStatus.ACTIVE) throw conflict("The release is not building", "release_not_building")
        builder.retry(user, id, number)
        return io { store.get(id) } ?: throw notFound("Release not found")
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
            val res = io { store.unassign(repo, nums, actor(user)) }
            publish(res.changed)
            builder.cancelRuns(user, res.leftRuns)
            return AssignOutcome(null, emptyList(), emptyMap(), nums.filter { it in before })
        }
        val target = io { store.get(releaseId) }?.takeIf { it.repo == repo } ?: throw notFound("Release not found")
        // Checked again with the sources inside the store's transaction; here only to answer before any GitHub call.
        if (target.status == ReleaseStatus.CANCELLED) throw conflict("The release is cancelled; no issues can be added", "release_closed")
        if (target.status in ReleaseStatus.ACTIVE) requireOwner(user, target)
        val client = access.requireClient(user)
        val issues = nums.map { n ->
            val i = gh { client.getIssue(cfg.githubOrg, repo, n) } ?: throw notFound("Issue #$n not found")
            if (i.state != "open") throw conflict("Issue #$n is closed; only open issues can be planned in a release", "issue_closed")
            IssueToAssign(n, i.title, issueKindFromLabels(i.labels.map { it.name }))
        }
        val names = io { store.list(repo) }.associate { it.id to it.name }
        val res = io { store.assign(repo, target.id, issues, actor(user)) }
        publish(res.changed)
        builder.cancelRuns(user, res.leftRuns)
        if (res.added.isNotEmpty() && target.status == ReleaseStatus.BUILDING) builder.advance(target.id)
        return AssignOutcome(
            io { store.get(target.id) }, res.added,
            res.movedFrom.mapKeys { (id, _) -> names[id] ?: id.toString() }, emptyList(),
        )
    }

    suspend fun reorder(user: UserRecord, id: UUID, numbers: List<Int>): ReleaseRecord {
        val r = visible(user, id)
        if (numbers.size > 10_000) throw badRequest("Too many issue numbers")
        io { store.reorder(r.repo, id, numbers, actor(user)) }
        publish(listOf(id))
        return io { store.get(id) } ?: throw notFound("Release not found")
    }

    /** Release id and name per assigned issue, for [IssueDto.release]. */
    fun releaseByIssue(repo: String): Map<Int, IssueReleaseDto> =
        store.releaseByIssue(repo).mapValues { (_, v) -> IssueReleaseDto(v.first.toString(), v.second) }

    private fun statusText(status: String) = when (status) {
        ReleaseStatus.PLANNING -> "in preparation"
        else -> status
    }

    /** Plain-text overview for `/factory releases` and `factory_list_releases`. */
    suspend fun describe(user: UserRecord, repo: String, withIds: Boolean): String {
        val list = list(user, repo)
        if (list.isEmpty()) return "No releases in $repo yet."
        return list.joinToString("\n\n", "Releases in $repo (newest first):\n") { r ->
            val n = r.issues.size
            buildString {
                if (r.started) {
                    val merged = r.issues.count { it.state == ReleaseIssueState.MERGED }
                    append("• ${r.name} — ${statusText(r.status)}, $merged of $n merged")
                    r.ownerUsername?.let { append(", started by $it") }
                    r.branch?.let { append(", branch $it") }
                } else {
                    append("• ${r.name} — in preparation, $n issue${if (n == 1) "" else "s"}")
                }
                if (withIds) append(" (id ${r.id})")
                if (r.status == ReleaseStatus.PAUSED) r.pauseNote?.let { append("\n    Paused: $it") }
                r.issues.forEach {
                    append("\n    #${it.number} ${it.title}")
                    if (it.state != ReleaseIssueState.WAITING) {
                        append(" — ${it.state}")
                        if (it.state == ReleaseIssueState.HELD || it.state == ReleaseIssueState.SKIPPED) it.note?.let { n -> append(": $n") }
                    }
                }
            }
        }
    }
}
