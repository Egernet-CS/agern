package dk.egernet.factory.workspace

import dk.egernet.factory.ApiException
import dk.egernet.factory.conflict
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.github.Access
import dk.egernet.factory.github.GhIssue
import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A workspace project's definition as one user sees it: [members] when [valid], else the [errors]
 * (docs/ARCHITECTURE.md §5 "Workspace projects").
 */
data class WorkspaceState(val valid: Boolean, val members: List<WorkspaceMember>, val errors: List<String>) {
    /** The members whose label is one of [labels] (case-insensitive exact match); empty for an invalid definition. */
    fun membersLabelled(labels: Collection<String>): List<WorkspaceMember> {
        val set = labels.map { it.lowercase() }.toSet()
        return members.filter { it.label.lowercase() in set }
    }

    /** "<first problem>[ (and N more)]". */
    val firstProblem: String
        get() = errors.firstOrNull().orEmpty() + if (errors.size > 1) " (and ${errors.size - 1} more)" else ""
}

/**
 * A member as one user sees it: [baseBranch] is the effective base branch (declared, else the member repository's
 * default branch as the user's token sees it); null when [accessible] is false and no branch is declared.
 */
data class ResolvedMember(val member: WorkspaceMember, val baseBranch: String?, val accessible: Boolean) {
    val directory get() = member.directory
    val repository get() = member.repository
}

/** Reads, caches and resolves workspace definitions with the acting user's own token. */
class WorkspaceService(private val access: Access, private val ttlMillis: Long = 60_000) {
    private val log = LoggerFactory.getLogger(WorkspaceService::class.java)
    private data class Entry(val state: WorkspaceState?, val expires: Long)
    private val cache = ConcurrentHashMap<Pair<UUID, String>, Entry>()

    private val org get() = access.org

    /** Forgets every cached definition (tests change the file). */
    fun invalidate() = cache.clear()

    /**
     * The definition of [repo] as [user] sees it: null when [repo] is not a workspace (no file on its default branch,
     * or [user] cannot see the repository). The caller has checked visibility. Cached per user and repository. A read
     * that fails (other than "not found") is not cached and gives null, unless [strict]: then it throws, so a run
     * never starts as if the project were a single repository.
     */
    fun lookup(user: UserRecord, repo: String, strict: Boolean = false): WorkspaceState? {
        val key = user.id to repo
        val now = System.currentTimeMillis()
        cache[key]?.let { if (it.expires > now) return it.state }
        val r = access.visibleRepo(user, repo) ?: return null
        val client = access.client(user) ?: return null
        val content = try {
            // Never more than the size limit (+1 byte, to tell that it is too large) is read.
            client.getFileBytes(org, r.name, WORKSPACE_FILE, r.defaultBranch, WorkspaceDefinitions.MAX_BYTES + 1L)
                ?.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            log.warn("Reading {} of {} failed: {}", WORKSPACE_FILE, repo, e.message)
            if (strict) throw ApiException(HttpStatusCode.BadGateway, "github_error", "Could not read $WORKSPACE_FILE: ${e.message}")
            return null
        }
        val state = content?.let {
            when (val p = WorkspaceDefinitions.parse(it, org, r.name)) {
                is ParsedWorkspace.Valid -> WorkspaceState(true, p.members, emptyList())
                is ParsedWorkspace.Invalid -> WorkspaceState(false, emptyList(), p.errors)
            }
        }
        cache[key] = Entry(state, now + ttlMillis)
        return state
    }

    /** The members of [state] with the effective base branch and whether [user]'s own token can read them. */
    fun resolve(user: UserRecord, state: WorkspaceState): List<ResolvedMember> = state.members.map { m ->
        val seen = access.visibleRepo(user, m.repository)
        ResolvedMember(m, m.branch ?: seen?.defaultBranch, seen != null)
    }

    /** [lookup] then [resolve]; null when [repo] is not a workspace. */
    fun resolved(user: UserRecord, repo: String): List<ResolvedMember>? = lookup(user, repo)?.let { resolve(user, it) }

    /**
     * The member issue [issue] of workspace [repo] is routed to for [user] (docs/ARCHITECTURE.md §6 "Workspace runs"),
     * or null when [repo] is not a workspace. Throws 409 `workspace_invalid`, `member_label_missing`,
     * `member_label_ambiguous` or 403 `member_not_accessible`.
     */
    fun memberFor(user: UserRecord, repo: String, issue: GhIssue): ResolvedMember? {
        val state = lookup(user, repo, strict = true) ?: return null
        if (!state.valid) throw invalid(state, access.visibleRepo(user, repo)?.defaultBranch ?: "the default branch")
        val matching = state.membersLabelled(issue.labels.map { it.name })
        when {
            matching.isEmpty() -> throw conflict(
                "Issue #${issue.number} has no member label. Add exactly one of these labels on GitHub to say which repository " +
                    "it belongs to: " + state.members.joinToString(", ") { "`${it.label}` (${it.repository})" } + ".",
                "member_label_missing",
            )
            matching.size > 1 -> throw conflict(
                "Issue #${issue.number} carries the labels of several members (${matching.joinToString(", ") { it.label }}). " +
                    "A run changes one member: keep exactly one of them.",
                "member_label_ambiguous",
            )
        }
        val resolved = resolve(user, WorkspaceState(true, matching, emptyList())).single()
        if (!resolved.accessible || resolved.baseBranch == null) throw notAccessible(resolved.member)
        return resolved
    }

    fun notAccessible(m: WorkspaceMember) = ApiException(
        HttpStatusCode.Forbidden, "member_not_accessible",
        "Your GitHub token cannot read $org/${m.repository} (member `${m.directory}`), so you cannot run this issue.",
    )

    companion object {
        fun invalid(state: WorkspaceState, defaultBranch: String) = conflict(
            "The workspace definition $WORKSPACE_FILE is invalid: ${state.firstProblem}. Fix it on `$defaultBranch` first.",
            "workspace_invalid",
        )

        fun releasesUnsupported() = conflict("Releases are not available for workspace projects yet.", "workspace_releases_unsupported")
    }
}
