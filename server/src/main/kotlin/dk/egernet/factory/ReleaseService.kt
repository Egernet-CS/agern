package dk.egernet.factory

import dk.egernet.factory.agent.EventBus
import dk.egernet.factory.agent.FeedItem
import dk.egernet.factory.db.IssueToAssign
import dk.egernet.factory.db.ReleaseActor
import dk.egernet.factory.db.ReleaseRecord
import dk.egernet.factory.db.ReleaseStore
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.db.releaseForbidden
import dk.egernet.factory.db.releasedNotEditable
import dk.egernet.factory.github.Access
import dk.egernet.factory.github.GitHubException
import dk.egernet.factory.pipeline.ReleaseBuilder
import dk.egernet.factory.pipeline.ReleaseMerger
import dk.egernet.factory.pipeline.ReleaseModePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeParseException
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
    private val scheduler: ReleaseScheduler,
    private val merger: ReleaseMerger,
    private val modePolicy: ReleaseModePolicy = ReleaseModePolicy.ALLOW_ALL,
    private val workspaceDefs: dk.egernet.factory.workspace.WorkspaceService? = null,
    /** The projects' settings: a project that merges on GitHub refuses Agern's release approval; null = defaults. */
    private val projectSettings: dk.egernet.factory.db.ProjectSettingsStore? = null,
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

        fun validMode(raw: String?): String {
            val m = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return ReleaseMode.MANUAL
            if (m !in ReleaseMode.ALL) throw badRequest("The release mode must be \"manual\" or \"automatic\"")
            return m
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
        } catch (e: java.nio.file.FileSystemException) {
            throw e
        } catch (e: java.io.FileNotFoundException) {
            throw e
        } catch (e: java.io.IOException) {
            throw githubUnreachable(e)
        } catch (e: java.nio.channels.UnresolvedAddressException) {
            throw githubUnreachable(e)
        }
    }

    /** A network error or timeout talking to GitHub: 502 `github_unreachable`, keeping [e] as the cause (still transient). */
    private fun githubUnreachable(e: Throwable) =
        ApiException(io.ktor.http.HttpStatusCode.BadGateway, "github_unreachable", dk.egernet.factory.github.GitHubFailure.unreachable(e, cfg.githubOrg))
            .apply { initCause(e) }

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

    suspend fun create(user: UserRecord, repo: String, name: String, description: String, mode: String? = null): ReleaseRecord {
        requireVisible(user, repo)
        val n = validName(name)
        val d = validDescription(description)
        val m = validMode(mode)
        if (m == ReleaseMode.AUTOMATIC) requireAutomaticAllowed(repo)
        val r = io { store.create(repo, n, d, user.id, m) }
        bus.emitFeed(FeedItem.Release(r.toDto()))
        return r
    }

    suspend fun update(user: UserRecord, id: UUID, name: String, description: String): ReleaseRecord {
        val r = visible(user, id)
        if (r.status == ReleaseStatus.RELEASED) throw releasedNotEditable(r.name)
        if (r.started) requireOwnerOrAdmin(user, r)
        val n = validName(name)
        val d = validDescription(description)
        if (r.started && n != r.name) throw conflict("The name is fixed once the release is started", "release_name_locked")
        io { store.update(id, n, d) }
        val after = io { store.get(id) } ?: throw notFound("Release not found")
        bus.emitFeed(FeedItem.Release(after.toDto()))
        // The release pull request's body shows the description.
        if (after.status == ReleaseStatus.READY) builder.advance(id)
        return after
    }

    /**
     * Planned releases as before; a cancelled one by its owner or an admin. Its runs stay; so does its branch, except
     * for a release of several repositories: every `release/<name>` its cancel could not delete is tried once more (best
     * effort) before the release goes.
     */
    suspend fun delete(user: UserRecord, id: UUID) {
        val r = visible(user, id)
        val scheduled = conflict("Cancel the scheduled start before deleting this release", "release_scheduled")
        val active = conflict("Cancel the release first", "release_active")
        if (r.status == ReleaseStatus.SCHEDULED) throw scheduled
        if (r.status == ReleaseStatus.RELEASED) throw releasedNotEditable(r.name)
        if (r.status in ReleaseStatus.ACTIVE || r.status == ReleaseStatus.READY) throw active
        if (r.status == ReleaseStatus.CANCELLED) requireOwnerOrAdmin(user, r)
        if (r.status == ReleaseStatus.CANCELLED && r.multiRepo) merger.retryBranchDeletion(id)
        if (io { store.delete(id) }) {
            bus.emitFeed(FeedItem.ReleaseDeleted(r.repo, r.id.toString()))
        } else {
            // Scheduled or started in the meantime.
            val now = io { store.get(id) }?.status
            if (now == ReleaseStatus.SCHEDULED) throw scheduled
            if (now in ReleaseStatus.ACTIVE || now == ReleaseStatus.READY) throw active
        }
    }

    /**
     * A project that requires a human approval (merge policy other than `automatic`, docs/ARCHITECTURE.md §6 "Merge
     * policy") refuses automatic releases (409 `release_mode_not_allowed`).
     */
    private fun requireAutomaticAllowed(repo: String) {
        if (!modePolicy.allowsAutomatic(repo)) {
            throw conflict("This project requires a human approval; its releases cannot be released automatically.", "release_mode_not_allowed")
        }
    }

    /**
     * Sets the release mode (docs/ARCHITECTURE.md §6 "Release mode"). Planned (`planning`): anyone who can see the
     * project, as for every part of the plan — whoever starts it sees the mode and starts it with their own token.
     * Scheduled or started: switching to automatic is for the owner only (it merges with the owner's token); switching
     * back to manual is for the owner or an admin (it only stops something). `released`: 409 `release_not_editable`;
     * `cancelled`: 409 `release_closed`. Never offered to agents: there is no MCP tool for it.
     */
    suspend fun setMode(user: UserRecord, id: UUID, rawMode: String?): ReleaseRecord {
        val r = visible(user, id)
        val mode = validMode(rawMode ?: throw badRequest("mode is required"))
        if (r.status == ReleaseStatus.RELEASED) throw releasedNotEditable(r.name)
        if (r.status == ReleaseStatus.CANCELLED) throw conflict("The release is cancelled", "release_closed")
        if (r.status != ReleaseStatus.PLANNING) {
            if (mode == ReleaseMode.AUTOMATIC) requireOwner(user, r) else requireOwnerOrAdmin(user, r)
        }
        if (mode == ReleaseMode.AUTOMATIC) requireAutomaticAllowed(r.repo)
        if (r.mode == mode) return r
        if (!merger.setMode(id, mode)) {
            val now = io { store.get(id) } ?: throw notFound("Release not found")
            if (now.status == ReleaseStatus.RELEASED) throw releasedNotEditable(now.name)
            throw conflict("The release is cancelled", "release_closed")
        }
        return io { store.get(id) } ?: throw notFound("Release not found")
    }

    // ------------------------------------------------------------ building

    /** Starts a planned release; the caller becomes its owner (docs/ARCHITECTURE.md §6 "Releases", starting). */
    suspend fun start(user: UserRecord, id: UUID): ReleaseRecord {
        val r = visible(user, id)
        if (r.status == ReleaseStatus.SCHEDULED) {
            throw conflict("The release is scheduled to start at ${r.scheduledAt}; cancel the scheduled start to start it now", "release_not_planning")
        }
        if (r.status != ReleaseStatus.PLANNING) throw conflict("The release is ${r.status}, not in preparation", "release_not_planning")
        return startAs(user, r)
    }

    /**
     * The scheduled start of a due release (§6 "Scheduled start"), called by the scheduler as the release's [owner]:
     * the same checks and the same start as "start now", except that the release must be `scheduled`.
     */
    suspend fun startScheduled(owner: UserRecord, id: UUID): ReleaseRecord {
        val r = visible(owner, id)
        if (r.status != ReleaseStatus.SCHEDULED) throw conflict("The release is ${r.status}, not scheduled", "release_not_scheduled")
        return startAs(owner, r)
    }

    /**
     * Checks [user]'s credentials, the issues, the workspace definition (409 `workspace_invalid` when the project is a
     * workspace whose definition is invalid) and the project's building slot, then builds [r] as [user].
     */
    private suspend fun startAs(user: UserRecord, r: ReleaseRecord): ReleaseRecord {
        if (user.claudeCredEnc == null) throw unprocessable("Register a Claude credential in Settings first", "claude_credential_required")
        val client = access.requireClient(user)
        if (r.issues.isEmpty()) throw conflict("Add an issue to the release first", "release_empty")
        workspaceDefs?.let { d -> io { d.lookup(user, r.repo, strict = true) } }?.takeIf { !it.valid }?.let { state ->
            val branch = io { access.visibleRepo(user, r.repo) }?.defaultBranch ?: "the default branch"
            throw dk.egernet.factory.workspace.WorkspaceService.invalid(state, branch)
        }
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

    /**
     * "Give up" on a release waiting for the network (docs/ARCHITECTURE.md §6 "Transient failures"): the issue that could
     * not start is held and the release goes on. Owner or admin, as [pause]; 409 `release_not_waiting_for_network`.
     */
    suspend fun giveUpNetwork(user: UserRecord, id: UUID): ReleaseRecord {
        val r = visible(user, id)
        if (r.started) requireOwnerOrAdmin(user, r)
        if (r.status != ReleaseStatus.PAUSED || r.pauseReason != dk.egernet.factory.PauseReason.NETWORK) {
            throw conflict("The release is not waiting for the network", "release_not_waiting_for_network")
        }
        builder.giveUpNetwork(user, id)
        return io { store.get(id) } ?: throw notFound("Release not found")
    }

    suspend fun cancel(user: UserRecord, id: UUID): ReleaseRecord {
        val r = visible(user, id)
        if (r.started) requireOwnerOrAdmin(user, r)
        if (r.status == ReleaseStatus.RELEASED) throw releasedNotEditable(r.name)
        if (r.status !in ReleaseStatus.ACTIVE && r.status != ReleaseStatus.READY) throw conflict("The release is not building", "release_not_building")
        builder.cancel(user, id)
        return io { store.get(id) } ?: throw notFound("Release not found")
    }

    /**
     * Retries a held or skipped issue; [mode] null = `start_over` (docs/ARCHITECTURE.md §6 "Retrying a run"). Owner only;
     * continuing and step retries need the owner's Claude credential (422) like any run.
     */
    suspend fun retry(user: UserRecord, id: UUID, number: Int, mode: String? = null): ReleaseRecord {
        val r = visible(user, id)
        if (r.started) requireOwner(user, r)
        if (r.status == ReleaseStatus.RELEASED) throw releasedNotEditable(r.name)
        if (r.status !in ReleaseStatus.ACTIVE && r.status != ReleaseStatus.READY) throw conflict("The release is not building", "release_not_building")
        builder.retry(user, id, number, mode)
        return io { store.get(id) } ?: throw notFound("Release not found")
    }

    /**
     * How a held or skipped issue can be retried: the options of its linked run, or only `start_over` when it has none.
     * Anyone who can see the release may ask; only the owner may retry.
     */
    suspend fun retryOptions(user: UserRecord, id: UUID, number: Int): dk.egernet.factory.RetryOptionsDto {
        val r = visible(user, id)
        if (r.status == ReleaseStatus.RELEASED) throw releasedNotEditable(r.name)
        return builder.retryOptions(id, number) ?: dk.egernet.factory.RetryOptionsDto(
            runId = "", recommended = dk.egernet.factory.RetryMode.START_OVER, reason = "The issue has no run to continue from.",
            options = listOf(
                dk.egernet.factory.RetryOptionDto(dk.egernet.factory.RetryMode.UPDATE, false, "The issue has no run."),
                dk.egernet.factory.RetryOptionDto(dk.egernet.factory.RetryMode.STEP, false, "The issue has no run."),
                dk.egernet.factory.RetryOptionDto(dk.egernet.factory.RetryMode.CONTINUE, false, "The issue has no run."),
                dk.egernet.factory.RetryOptionDto(dk.egernet.factory.RetryMode.START_OVER, true, "A new run from scratch."),
            ),
            releaseId = id.toString(),
        )
    }

    // ------------------------------------------------------------ releasing (§6 "Releasing")

    /**
     * Approves a `ready` release: merges its pull request into the default branch (one commit per issue), closes its
     * issues and deletes its branch. Only the release's owner, admins included in the refusal (§4: they may stop, never
     * drive); visibility first (404).
     */
    suspend fun approve(user: UserRecord, id: UUID): ReleaseRecord {
        val r = visible(user, id)
        if (!r.started) throw conflict("Release is ${ReleaseMerger.statusLabel(r.status)}, not ready to release", "release_not_ready")
        requireOwner(user, r)
        if (io { projectSettings?.get(r.repo)?.mergePolicy } == MergePolicy.GITHUB) {
            throw conflict(
                "This project merges on GitHub: Agern does not merge. Review and merge the release pull request on GitHub.",
                "merge_on_github",
            )
        }
        access.requireClient(user)
        return merger.approve(user, id)
    }

    /** "Check again": re-reads the release pull request (and opens it when missing). Owner only, `ready` only. */
    suspend fun check(user: UserRecord, id: UUID): ReleaseRecord {
        val r = visible(user, id)
        if (!r.started) throw conflict("Release is ${ReleaseMerger.statusLabel(r.status)}, not ready to release", "release_not_ready")
        requireOwner(user, r)
        access.requireClient(user)
        return merger.check(user, id)
    }

    /** A sync run that was started: the release afterwards, the run and the branch it merges ([target]). */
    data class SyncStarted(val release: ReleaseRecord, val run: dk.egernet.factory.db.RunRecord, val target: String)

    /**
     * "Bring up to date" (docs/ARCHITECTURE.md §6 "Bringing a release up to date"): starts a sync run that merges the
     * default branch into `release/<name>`. Visibility first (404), then `ready` (409 `release_not_ready`), the release
     * owner or an admin (403), a single repository (409 `release_sync_unsupported`), the caller's credentials (422 /
     * as [Access.requireClient]) and a repository that allows merge commits (409
     * `release_sync_merge_commits_disallowed`); under the release's mutex: still `ready`, no sync run in progress (409
     * `release_sync_running`) and something to do (409 `release_up_to_date`). The run belongs to the caller and uses only
     * the caller's credentials, also when an admin starts it.
     */
    suspend fun sync(user: UserRecord, id: UUID): SyncStarted {
        val r = visible(user, id)
        if (!r.started || r.status != ReleaseStatus.READY) {
            throw conflict("Release is ${ReleaseMerger.statusLabel(r.status)}, not ready to release", "release_not_ready")
        }
        requireOwnerOrAdmin(user, r)
        val branch = r.branch ?: "release/${r.name}"
        if (r.multiRepo) {
            throw conflict(
                "Bringing a release of several repositories up to date is not supported yet; merge the base branch into `$branch` " +
                    "in each repository by hand.",
                "release_sync_unsupported",
            )
        }
        if (user.claudeCredEnc == null) throw unprocessable("Register a Claude credential in Settings first", "claude_credential_required")
        val client = access.requireClient(user)
        val repo = gh { client.getRepo(cfg.githubOrg, r.repo) } ?: throw notFound("Repository not found")
        if (repo.allowMergeCommit == false) {
            throw conflict(
                "${r.repo} does not allow merge commits, and a release branch that contains a merge commit cannot be rebased; " +
                    "allow merge commits in the repository's GitHub settings first.",
                "release_sync_merge_commits_disallowed",
            )
        }
        val run = merger.startSync(id) { now -> builder.startSyncRun(user, now, repo.defaultBranch) }
        return SyncStarted(io { store.get(id) } ?: throw notFound("Release not found"), run, repo.defaultBranch)
    }

    /** Sets or changes the scheduled start (§6 "Scheduled start"); [startAt] is an ISO-8601 instant. */
    suspend fun schedule(user: UserRecord, id: UUID, startAt: String): ReleaseRecord {
        val r = visible(user, id)
        val at = try {
            Instant.parse(startAt.trim())
        } catch (e: DateTimeParseException) {
            throw badRequest("startAt must be an ISO-8601 time")
        }
        return scheduler.schedule(user, r, at)
    }

    /** Cancels the scheduled start; the release is in preparation again. */
    suspend fun cancelSchedule(user: UserRecord, id: UUID): ReleaseRecord = scheduler.cancel(user, visible(user, id))

    /** What an assignment did, for `/factory` and MCP replies. */
    data class AssignOutcome(val release: ReleaseRecord?, val added: List<Int>, val movedFrom: Map<String, List<Int>>, val removed: List<Int>)

    private fun validNumbers(numbers: List<Int>): List<Int> {
        if (numbers.isEmpty() || numbers.size > MAX_ISSUES_PER_REQUEST) throw badRequest("Give 1 to $MAX_ISSUES_PER_REQUEST issue numbers")
        if (numbers.any { it <= 0 }) throw badRequest("Issue numbers must be positive")
        return numbers.distinct()
    }

    /**
     * Puts the issues into [releaseId] (moving them from another release), or takes them out of their release when
     * [releaseId] is null. All-or-nothing: every issue is checked on GitHub (exists, open) and, in a workspace project,
     * routed to a member as a run would be ([dk.egernet.factory.workspace.WorkspaceService.memberFor]: 409
     * `workspace_invalid`, `member_label_missing`, `member_label_ambiguous`, 403 `member_not_accessible`) before
     * anything changes. A release that is partly released takes no new issue (409 `release_partially_merged`).
     */
    suspend fun assign(user: UserRecord, repo: String, numbers: List<Int>, releaseId: UUID?): AssignOutcome {
        requireVisible(user, repo)
        val nums = validNumbers(numbers)
        if (releaseId == null) {
            val before = io { store.releaseByIssue(repo) }
            val res = io { store.unassign(repo, nums, actor(user)) }
            publish(res.changed)
            builder.cancelRuns(user, res.leftRuns)
            // A building release may now have nothing left to build; a ready one is re-evaluated (it may be complete now).
            for (id in res.changed) builder.advance(id)
            return AssignOutcome(null, emptyList(), emptyMap(), nums.filter { it in before })
        }
        val target = io { store.get(releaseId) }?.takeIf { it.repo == repo } ?: throw notFound("Release not found")
        // Checked again with the sources inside the store's transaction; here only to answer before any GitHub call.
        if (target.status == ReleaseStatus.CANCELLED) throw conflict("The release is cancelled; no issues can be added", "release_closed")
        if (target.status == ReleaseStatus.RELEASED) throw releasedNotEditable(target.name)
        if (target.status in ReleaseStatus.ACTIVE || target.status == ReleaseStatus.READY) requireOwner(user, target)
        if (target.partlyReleased) throw ReleaseMerger.partlyMerged(target.name)
        val client = access.requireClient(user)
        val found = nums.map { n ->
            val i = gh { client.getIssue(cfg.githubOrg, repo, n) } ?: throw notFound("Issue #$n not found")
            if (i.state != "open") throw conflict("Issue #$n is closed; only open issues can be planned in a release", "issue_closed")
            i
        }
        // A workspace project: each issue must be buildable, i.e. routed to exactly one member (or, in a submodule
        // workspace, to the workspace repository itself), exactly as a run of it would be (docs/ARCHITECTURE.md §6).
        workspaceDefs?.let { d -> io { found.forEach { d.memberFor(user, repo, it) } } }
        val issues = found.map { i -> IssueToAssign(i.number, i.title, issueKindFromLabels(i.labels.map { it.name })) }
        val names = io { store.list(repo) }.associate { it.id to it.name }
        val res = io { store.assign(repo, target.id, issues, actor(user)) }
        publish(res.changed)
        builder.cancelRuns(user, res.leftRuns)
        // New issues of a building (or reopened ready) release are built; sources may have nothing left to build.
        for (id in res.changed) builder.advance(id)
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

    /** The state of a release that is not started, for [describe]. */
    private fun state(r: ReleaseRecord): String = when (r.status) {
        ReleaseStatus.PLANNING -> "in preparation" + (r.startFailedReason?.let { " (scheduled start failed: $it)" } ?: "")
        ReleaseStatus.SCHEDULED -> buildString {
            append("scheduled for ${r.scheduledAt} by ${r.ownerUsername ?: "unknown"}")
            r.waitingForName?.let { append(", waiting for release \"$it\"") }
        }
        else -> r.status
    }

    private fun statusText(status: String) = ReleaseMerger.statusLabel(status)

    /**
     * `<org>/<repo>#<n> <state>[, <checks detail>]` per release pull request of a multi-repository release that [user]
     * can read; null for none. Blocking (visibility is cached).
     */
    private fun pullsLine(user: UserRecord, r: ReleaseRecord): String? =
        r.toDto().forViewer { access.canSee(user, it) }.repositories.filter { it.prNumber != null }.takeIf { it.isNotEmpty() }
        ?.joinToString("; ") { p ->
            "${cfg.githubOrg}/${p.repo}#${p.prNumber} ${p.state}" +
                (p.checks?.detail?.takeIf { p.state == "open" }?.let { ", $it" } ?: "")
        }

    /** The latest sync run of a `ready` release for [describe]; null when there is none or it is done. */
    private fun syncLine(r: ReleaseRecord): String? {
        val s = r.sync ?: return null
        val error = s.error?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return when (r.syncState) {
            ReleaseSyncState.RUNNING -> "running (run ${s.id})"
            ReleaseSyncState.FAILED -> "failed, nothing pushed: $error"
            ReleaseSyncState.UNREVIEWED -> "pushed but not fully reviewed: $error"
            else -> null
        }
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
                    append("• ${r.name} — ${state(r)}, $n issue${if (n == 1) "" else "s"}")
                }
                val multi = r.multiRepo && (r.status == ReleaseStatus.READY || r.status == ReleaseStatus.RELEASED)
                if (r.status == ReleaseStatus.READY) {
                    if (!multi) r.prUrl?.let { append(", pull request $it") }
                    if (r.held.isNotEmpty()) append(" — ${r.held.size} held")
                    else if (r.mergeState == ReleaseMergeState.CONFLICT) append(" — conflicts with ${r.targetBranch ?: "the default branch"}")
                }
                if (r.status == ReleaseStatus.RELEASED) {
                    if (!multi) r.prUrl?.let { append(", pull request $it") }
                    r.releasedAt?.let { append(", released $it") }
                }
                if (r.automatic && r.status !in ReleaseStatus.FINISHED) append(", releases automatically when ready")
                if (r.status == ReleaseStatus.RELEASED && r.releasedAutomatically) append(", released automatically")
                if (withIds) append(" (id ${r.id})")
                if (multi) pullsLine(user, r)?.let { append("\n    Pull requests: $it") }
                r.notice?.takeIf { r.status == ReleaseStatus.READY || r.status == ReleaseStatus.RELEASED }?.let { append("\n    Notice: $it") }
                if (r.status == ReleaseStatus.READY) syncLine(r)?.let { append("\n    Sync: $it") }
                if (r.status == ReleaseStatus.PAUSED) r.pauseNote?.let { append("\n    Paused: $it") }
                r.issues.forEach {
                    append("\n    #${it.number} ${it.title}")
                    it.targetDir?.let { d -> append(" [member $d]") }
                    if (it.state != ReleaseIssueState.WAITING) {
                        append(" — ${it.state}")
                        if (it.state == ReleaseIssueState.HELD || it.state == ReleaseIssueState.SKIPPED) it.note?.let { n -> append(": $n") }
                    }
                }
            }
        }
    }
}
