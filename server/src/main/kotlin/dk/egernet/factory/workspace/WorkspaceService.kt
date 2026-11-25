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
 * (docs/ARCHITECTURE.md §5 "Workspace projects"). [path]: the file it comes from, `.agern/workspace.yml` or (members
 * derived from the submodules) `.gitmodules`. [contextOnly]: submodules that are not members (fetched with the
 * workspace repository, never a run's target).
 */
data class WorkspaceState(
    val valid: Boolean, val members: List<WorkspaceMember>, val errors: List<String>, val path: String = WORKSPACE_FILE,
    val contextOnly: List<ResolvedSubmodule> = emptyList(),
) {
    /** The members whose label is one of [labels] (case-insensitive exact match); empty for an invalid definition. */
    fun membersLabelled(labels: Collection<String>): List<WorkspaceMember> {
        val set = labels.map { it.lowercase() }.toSet()
        return members.filter { it.label.lowercase() in set }
    }

    /**
     * The members come from `.gitmodules` alone (no `.agern/workspace.yml`): an issue without a member label is a run
     * of the workspace repository itself, and releases (of such runs) are available.
     */
    val fromSubmodules: Boolean get() = path == GITMODULES_FILE

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

/**
 * Reads, caches and resolves workspace definitions with the acting user's own token. [gitHost]: the git host's HTTPS
 * base (`https://github.com/`); submodule URLs on it name repositories of the organization.
 */
class WorkspaceService(private val access: Access, private val ttlMillis: Long = 60_000, private val gitHost: String = "https://github.com/") {
    private val log = LoggerFactory.getLogger(WorkspaceService::class.java)
    private data class Entry(val state: WorkspaceState?, val expires: Long)
    private val cache = ConcurrentHashMap<Pair<UUID, String>, Entry>()

    private val org get() = access.org

    /** Forgets every cached definition (tests change the file). */
    fun invalidate() = cache.clear()

    /**
     * The definition of [repo] as [user] sees it: null when [repo] is not a workspace (neither `.agern/workspace.yml`
     * nor a `.gitmodules` with a submodule of the organization on its default branch, or [user] cannot see the
     * repository). `.agern/workspace.yml` wins over `.gitmodules`; its members may be submodules. The caller has
     * checked visibility. Cached per user and repository. A read that fails (other than "not found") is not cached
     * and gives null, unless [strict]: then it throws, so a run never starts as if the project were a single repository.
     */
    fun lookup(user: UserRecord, repo: String, strict: Boolean = false): WorkspaceState? {
        val key = user.id to repo
        val now = System.currentTimeMillis()
        cache[key]?.let { if (it.expires > now) return it.state }
        val r = access.visibleRepo(user, repo) ?: return null
        val client = access.client(user) ?: return null
        fun read(path: String, max: Int): String? = try {
            // Never more than the size limit (+1 byte, to tell that it is too large) is read.
            client.getFileBytes(org, r.name, path, r.defaultBranch, max + 1L)?.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            log.warn("Reading {} of {} failed: {}", path, repo, e.message)
            if (strict) throw ApiException(HttpStatusCode.BadGateway, "github_error", "Could not read $path: ${e.message}")
            throw ReadFailed()
        }
        val state = try {
            val definition = read(WORKSPACE_FILE, WorkspaceDefinitions.MAX_BYTES)
            val submodules = read(GITMODULES_FILE, Gitmodules.MAX_BYTES)
                ?.let { Gitmodules.resolve(it, org, r.name, gitHost) }.orEmpty()
            when {
                definition != null -> when (val p = WorkspaceDefinitions.parse(definition, org, r.name, submodules, r.defaultBranch)) {
                    is ParsedWorkspace.Valid -> WorkspaceState(true, p.members, emptyList(),
                        contextOnly = submodules.filter { s -> p.members.none { it.directory == s.path } })
                    is ParsedWorkspace.Invalid -> WorkspaceState(false, emptyList(), p.errors)
                }
                else -> Gitmodules.members(submodules, r.name, r.defaultBranch).takeIf { it.members.isNotEmpty() }
                    ?.let { WorkspaceState(true, it.members, emptyList(), GITMODULES_FILE, it.contextOnly) }
            }
        } catch (_: ReadFailed) {
            return null
        }
        cache[key] = Entry(state, now + ttlMillis)
        return state
    }

    private class ReadFailed : RuntimeException()

    /** The members of [state] with the effective base branch and whether [user]'s own token can read them. */
    fun resolve(user: UserRecord, state: WorkspaceState): List<ResolvedMember> = state.members.map { m ->
        val seen = access.visibleRepo(user, m.repository)
        ResolvedMember(m, m.branch ?: seen?.defaultBranch, seen != null)
    }

    /** [lookup] then [resolve]; null when [repo] is not a workspace. */
    fun resolved(user: UserRecord, repo: String): List<ResolvedMember>? = lookup(user, repo)?.let { resolve(user, it) }

    /**
     * The member issue [issue] of workspace [repo] is routed to for [user] (docs/ARCHITECTURE.md §6 "Workspace runs"),
     * or null when [repo] is not a workspace, or when its members come from `.gitmodules` alone and the issue carries
     * no member label (the run changes the workspace repository itself). Throws 409 `workspace_invalid`,
     * `member_label_missing` (`.agern/workspace.yml` only), `member_label_ambiguous` or 403 `member_not_accessible`.
     */
    fun memberFor(user: UserRecord, repo: String, issue: GhIssue): ResolvedMember? {
        val state = lookup(user, repo, strict = true) ?: return null
        if (!state.valid) throw invalid(state, access.visibleRepo(user, repo)?.defaultBranch ?: "the default branch")
        val matching = state.membersLabelled(issue.labels.map { it.name })
        when {
            matching.isEmpty() && state.fromSubmodules -> return null
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
            "The workspace definition ${state.path} is invalid: ${state.firstProblem}. Fix it on `$defaultBranch` first.",
            "workspace_invalid",
        )

        /** An issue of a submodule workspace with a member label cannot be built by a release (it builds the workspace repository). */
        fun memberInRelease(number: Int, m: WorkspaceMember) = conflict(
            "Issue #$number carries the label of member `${m.directory}` (${m.repository}). A release builds the workspace " +
                "repository itself: remove the label, or run the issue on its own.",
            "member_in_release",
        )

        fun releasesUnsupported() = conflict("Releases are not available for workspace projects yet.", "workspace_releases_unsupported")
    }
}
