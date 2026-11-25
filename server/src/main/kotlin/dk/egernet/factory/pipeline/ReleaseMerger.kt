package dk.egernet.factory.pipeline

import dk.egernet.factory.ApiException
import dk.egernet.factory.Config
import dk.egernet.factory.ReleaseChecksState
import dk.egernet.factory.ReleaseMergeMethod
import dk.egernet.factory.ReleaseMergeState
import dk.egernet.factory.ReleaseMode
import dk.egernet.factory.ReleaseStatus
import dk.egernet.factory.agent.EventBus
import dk.egernet.factory.agent.FeedItem
import dk.egernet.factory.agent.Workspaces
import dk.egernet.factory.conflict
import dk.egernet.factory.db.ReleaseRecord
import dk.egernet.factory.db.ReleaseRepoRecord
import dk.egernet.factory.db.ReleaseStore
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.db.UserStore
import dk.egernet.factory.github.Access
import dk.egernet.factory.github.ChecksSummary
import dk.egernet.factory.github.GhPullInfo
import dk.egernet.factory.github.GhRepo
import dk.egernet.factory.github.GitHubClient
import dk.egernet.factory.github.GitHubException
import dk.egernet.factory.notFound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a project allows automatic releases (docs/ARCHITECTURE.md §6 "Release mode"). The seam for #24: a project
 * that requires a human approval refuses automatic here, both when the mode is set and before an automatic merge.
 * Until #24 exists every project allows it.
 */
fun interface ReleaseModePolicy {
    fun allowsAutomatic(repo: String): Boolean

    companion object {
        val ALLOW_ALL = ReleaseModePolicy { true }
    }
}

/** One mutex per release, shared by [ReleaseBuilder] and [ReleaseMerger]: every state change of a release is serialised. */
class ReleaseLocks {
    private val locks = ConcurrentHashMap<UUID, Mutex>()
    fun of(id: UUID): Mutex = locks.computeIfAbsent(id) { Mutex() }
}

/**
 * Releasing (docs/ARCHITECTURE.md §6 "Releases", "Releasing"): the release pull request `release/<name>` → default
 * branch, its mergeability, the owner's approval, the merge that keeps one commit per issue, and what follows it.
 * Every GitHub call uses the release owner's token. Permission checks are the caller's ([dk.egernet.factory.ReleaseService]);
 * everything here runs under the release's mutex and re-reads the database.
 */
class ReleaseMerger(
    private val cfg: Config,
    private val store: ReleaseStore,
    private val users: UserStore,
    private val access: Access,
    private val workspaces: Workspaces,
    private val bus: EventBus,
    private val locks: ReleaseLocks,
    private val scope: CoroutineScope,
    private val policy: ReleaseModePolicy = ReleaseModePolicy.ALLOW_ALL,
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(ReleaseMerger::class.java)
    private val org get() = cfg.githubOrg

    /** Pause between reads while GitHub is still computing mergeability (tests shorten it). */
    @Volatile var mergeablePollMillis = 1000L

    /**
     * How long a head commit must have had no checks before "no checks" counts as green
     * (FACTORY_RELEASE_CHECKS_SETTLE_SECONDS; tests change it).
     */
    @Volatile var checksSettleSeconds = cfg.releaseChecksSettleSeconds

    /** Interval of the periodic re-evaluation ([startTicker]); tests shorten it before starting the ticker. */
    @Volatile var periodicMillis = 60_000L

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    suspend fun publish(id: UUID) {
        io { store.touch(id) }
        val r = io { store.get(id) } ?: return
        bus.emitFeed(FeedItem.Release(r.toDto()))
    }

    private suspend fun current(id: UUID): ReleaseRecord = io { store.get(id) } ?: throw notFound("Release not found")

    // ------------------------------------------------------------ entry points

    /**
     * Re-evaluates a `ready` release: reconciles its pull request with GitHub, opens it when the release is complete,
     * keeps its body up to date, checks mergeability and posts the notifications that are due. [becameReady]: the
     * release has just become ready (held issues are told so). No-op for other states. Never throws.
     */
    suspend fun evaluate(id: UUID, becameReady: Boolean = false) {
        try {
            locks.of(id).withLock { evaluateLocked(id, becameReady, Trigger.EVENT) }
        } catch (e: Exception) {
            log.warn("Release {} could not be evaluated: {}", id, e.message)
        }
        publish(id)
    }

    /** [evaluate] in the background (after a change whose caller should not wait for GitHub). */
    fun evaluateLater(id: UUID) {
        scope.launch { evaluate(id) }
    }

    /**
     * "Check again" by the owner ([owner] is the caller, already checked): [evaluate], answering the release. An
     * automatic release whose automatic merge GitHub refused for the current head commit is tried again.
     */
    suspend fun check(owner: UserRecord, id: UUID): ReleaseRecord {
        locks.of(id).withLock {
            val r = current(id)
            if (r.status != ReleaseStatus.READY) throw notReady(r)
            evaluateLocked(id, becameReady = false, Trigger.CHECK)
        }
        publish(id)
        return current(id)
    }

    /**
     * Sets the release mode under the release's mutex, so no automatic merge starts after a switch to manual has
     * returned. False when the release is finished. A `ready` release is re-evaluated afterwards (its pull request body
     * names the mode; switched to automatic it may be merged at once). Permission checks are the caller's.
     */
    suspend fun setMode(id: UUID, mode: String): Boolean {
        val changed = locks.of(id).withLock {
            val ok = io { store.setMode(id, mode) }
            // Switching to automatic is an explicit decision: an earlier refused automatic merge is tried again.
            if (ok && mode == ReleaseMode.AUTOMATIC) io { store.setAutoMergeFailed(id, null) }
            ok
        }
        if (!changed) return false
        publish(id)
        if (io { store.get(id) }?.status == ReleaseStatus.READY) evaluateLater(id)
        return true
    }

    /**
     * The periodic re-evaluation: every [periodicMillis], each `ready` release that is automatic (it may have become
     * green: there is no webhook) or whose checks are pending (so the page shows when they finish) is re-evaluated, and
     * every other `ready` release with a pull request is reconciled ([reconcileLocked]: merged or closed on GitHub, its
     * mergeability; never a merge). A release is only published when something changed. Runs in [scope], so stopping the
     * services cancels it.
     */
    fun startTicker(): Job = scope.launch {
        while (isActive) {
            delay(periodicMillis)
            try {
                tick()
            } catch (e: Exception) {
                log.warn("Periodic release re-evaluation failed: {}", e.message)
            }
        }
    }

    /** One round of the periodic re-evaluation (see [startTicker]). */
    suspend fun tick() {
        for (id in io { store.periodicIds() }) {
            try {
                val (before, after) = locks.of(id).withLock {
                    val b = io { store.get(id) }
                    if (b != null && fullPeriodic(b)) evaluateLocked(id, becameReady = false, Trigger.PERIODIC)
                    else reconcileLocked(id)
                    b to io { store.get(id) }
                }
                if (before?.copy(updatedAt = Instant.EPOCH) != after?.copy(updatedAt = Instant.EPOCH)) publish(id)
            } catch (e: Exception) {
                log.warn("Release {} could not be re-evaluated: {}", id, e.message)
            }
        }
    }

    /**
     * Whether the periodic re-evaluation evaluates [r] in full: automatic (it may be merged once green) or with checks
     * still pending. Every other ready release with a pull request (a manual one) is only reconciled.
     */
    private fun fullPeriodic(r: ReleaseRecord) =
        r.automatic || r.checksState == ReleaseChecksState.PENDING || r.checksState == ReleaseChecksState.AWAITING ||
            r.repos.any { !it.merged && (it.checksState == ReleaseChecksState.PENDING || it.checksState == ReleaseChecksState.AWAITING) }

    /**
     * The periodic look at a manual `ready` release with a pull request: one read of the pull request. Merged on GitHub
     * (by a person) finishes the release as a check would; closed without merging forgets the pull request with a
     * notice; open refreshes its mergeability, and a new head commit gets a full re-evaluation (its checks, the merge
     * method). It never merges, comments only through [recordMergeState] (once per change to a conflict) and a failed
     * read changes nothing (the next round tries again).
     */
    private suspend fun reconcileLocked(id: UUID) {
        val r = io { store.get(id) } ?: return
        if (r.status != ReleaseStatus.READY) return
        if (r.multiRepo) {
            reconcileMultiLocked(r)
            return
        }
        val number = r.prNumber ?: return
        val client = ownerClient(r) ?: return // a check or an approval says so
        val info = try {
            io { client.getPull(org, r.repo, number) }
        } catch (e: GitHubException) {
            log.info("Release {}: could not read pull request #{} (tried again next round): {}", id, number, e.message)
            return
        }
        when {
            info != null && info.merged -> finishLocked(id, method = null, sha = info.mergeCommitSha, by = null)
            info == null || info.state != "open" -> {
                log.info("Release {}: pull request #{} was closed without merging; forgetting it", id, number)
                io { store.clearPull(id) }
                setNotice(
                    id, "Pull request #$number was closed on GitHub without being merged. Use \"Check again\" to open a new " +
                        "one, or cancel the release.",
                )
            }
            info.head.sha.isNotEmpty() && info.head.sha != r.checksSha -> evaluateLocked(id, becameReady = false, Trigger.PERIODIC)
            else -> info.mergeable?.let { recordMergeState(projectSlot(r), if (it) ReleaseMergeState.CLEAN else ReleaseMergeState.CONFLICT, client) }
        }
    }

    /**
     * A GitHub read that failed during an evaluation. The periodic re-evaluation only logs it (the next round tries
     * again), so a transient error never replaces a useful notice; every other trigger shows [text].
     */
    private suspend fun readFailed(id: UUID, trigger: Trigger, text: String) {
        if (trigger == Trigger.PERIODIC) log.info("Release {}: {} (tried again next round)", id, text)
        else setNotice(id, text)
    }

    /** What started an evaluation: decides whether it may merge an automatic release. */
    private enum class Trigger {
        /** A state change, a restart or the periodic re-evaluation: may merge an automatic release. */
        EVENT,
        PERIODIC,
        /** The owner's "Check again": may merge, and retries an automatic merge GitHub refused for the same commit. */
        CHECK,
        /** Inside an approval: never merges by itself (the approval does). */
        APPROVAL,
    }

    /**
     * Merges a `ready`, complete, conflict-free release into the default branch as its [owner] (already checked to be
     * the owner): rebase (or a merge commit where rebase merging is disabled or GitHub cannot rebase the branch), never
     * squash. Refusals change nothing on GitHub. Serialised per release: a second approval finds the release `released` (409 `release_not_ready`).
     */
    suspend fun approve(owner: UserRecord, id: UUID): ReleaseRecord {
        try {
            locks.of(id).withLock { approveLocked(owner, id) }
        } finally {
            publish(id)
        }
        return current(id)
    }

    /** After a restart: every `ready` release is re-evaluated and every unfinished clean-up after a release completed. */
    fun recoverAfterRestart() {
        val ready = store.readyIds()
        val pending = store.cleanupPending()
        if (ready.isEmpty() && pending.isEmpty()) return
        scope.launch {
            for (id in pending) {
                try {
                    locks.of(id).withLock { cleanupLocked(id) }
                    publish(id)
                } catch (e: Exception) {
                    log.warn("Release {}: clean-up after the merge failed again: {}", id, e.message)
                }
            }
            for (id in ready) evaluate(id)
        }
    }

    /**
     * A `ready` (or building) release with a pull request was cancelled by [by]: one comment on that pull request. A
     * release of several repositories also loses `release/<name>` in every repository of the release ([cancelMultiLocked]).
     */
    suspend fun commentCancelled(id: UUID, by: UserRecord) {
        val r = io { store.get(id) } ?: return
        if (r.multiRepo) {
            locks.of(id).withLock { cancelMultiLocked(id, by) }
            publish(id)
            return
        }
        val pr = r.prNumber ?: return
        val client = ownerClient(r) ?: return
        bestEffort("comment on the cancelled release's pull request") {
            io {
                client.comment(
                    org, r.repo, pr,
                    "**Agern:** release `${r.name}` was cancelled in Agern by @${by.githubLogin ?: by.username}. Agern will not merge " +
                        "this pull request; it and the branch `${r.branch}` are left as they are.",
                )
            }
        }
    }

    // ------------------------------------------------------------ evaluation

    private fun ownerOf(r: ReleaseRecord): UserRecord? = r.ownerId?.let { users.byId(it) }
    private fun ownerClient(r: ReleaseRecord): GitHubClient? = ownerOf(r)?.let { access.client(it) }
    private fun login(u: UserRecord?) = u?.let { it.githubLogin ?: it.username } ?: "the owner"
    /** The release page as GitHub text may name it: a link, or only its name when this installation has an internal address. */
    private fun pageUrl(r: ReleaseRecord) = cfg.pageRef("p/${r.repo}/releases/${r.id}", "the page of release `${r.name}`")

    private suspend fun setNotice(id: UUID, notice: String?) = io { store.setNotice(id, notice?.take(2000)) }

    private suspend fun evaluateLocked(id: UUID, becameReady: Boolean, trigger: Trigger) {
        val r = io { store.get(id) } ?: return
        if (r.status != ReleaseStatus.READY) return
        val owner = io { ownerOf(r) }
        val client = owner?.let { access.client(it) }
        if (client == null) {
            setNotice(id, "The release owner has no GitHub token; they must add one in Settings, then check again.")
            return
        }
        if (becameReady && r.held.isNotEmpty()) {
            for (i in r.held) bestEffort("comment on held issue #${i.number}") {
                io {
                    client.comment(
                        org, r.repo, i.number,
                        "**Agern:** release `${r.name}` has finished building, but this issue is held. The release cannot be " +
                            "released until its run is approved or retried, or the issue is moved out: ${pageUrl(r)}",
                    )
                }
            }
        }
        if (r.multiRepo) {
            evaluateMultiLocked(id, owner, client, trigger)
            return
        }
        val repo = try {
            io { client.getRepo(org, r.repo) }
        } catch (e: GitHubException) {
            null
        } ?: run {
            readFailed(id, trigger, "Could not read ${r.repo} on GitHub with the owner's token; check again later.")
            return
        }
        if (r.targetBranch != repo.defaultBranch) io { store.setTarget(id, repo.defaultBranch) }

        // Reconcile the pull request with GitHub.
        var existing: Int? = r.prNumber
        if (existing != null) {
            val number: Int = existing
            val info = try {
                io { client.getPull(org, r.repo, number) }
            } catch (e: GitHubException) {
                readFailed(id, trigger, "Could not read pull request #$number: ${e.message}")
                return
            }
            when {
                info != null && info.merged -> {
                    // Merged by a person on GitHub: finish exactly as after our own merge.
                    finishLocked(id, method = null, sha = info.mergeCommitSha, by = null)
                    return
                }
                info == null || info.state != "open" -> {
                    log.info("Release {}: pull request #{} was closed without merging; forgetting it", id, number)
                    io { store.clearPull(id) }
                    existing = null
                }
            }
        }

        val now = current(id)
        if (existing == null && !now.complete) {
            // Incomplete or empty: no pull request yet. The release page says why.
            io { store.setMergeState(id, ReleaseMergeState.UNKNOWN) }
            setNotice(id, null)
            return
        }
        val (title, body) = pullText(now, owner, repo.defaultBranch)
        val bodySha = sha256(title + "\n" + body)
        val prNumber: Int = existing ?: run {
            val branch = now.branch ?: "release/${now.name}"
            val (pr, created) = try {
                io {
                    client.findOpenPull(org, now.repo, branch)?.let { it to false }
                        ?: (client.createPull(org, now.repo, branch, repo.defaultBranch, title, body) to true)
                }
            } catch (e: Exception) {
                setNotice(id, "Could not open the release pull request: ${e.message}")
                return
            }
            io { store.setPull(id, pr.number, pr.htmlUrl, if (created) bodySha else "", repo.defaultBranch) }
            pr.number
        }
        val withPr = current(id)
        if (withPr.prBodySha != bodySha) {
            try {
                io { client.updatePull(org, withPr.repo, prNumber, title, body) }
                io { store.setPullBody(id, bodySha) }
            } catch (e: Exception) {
                log.warn("Release {}: could not update pull request #{}: {}", id, prNumber, e.message)
            }
        }

        val info = try {
            readMergeable(client, withPr.repo, prNumber)
        } catch (e: GitHubException) {
            readFailed(id, trigger, "Could not check pull request #$prNumber: ${e.message}")
            return
        }
        if (info?.merged == true) {
            finishLocked(id, method = null, sha = info.mergeCommitSha, by = null)
            return
        }
        val state = when (info?.mergeable) {
            true -> ReleaseMergeState.CLEAN
            false -> ReleaseMergeState.CONFLICT
            else -> ReleaseMergeState.UNKNOWN
        }
        // The method first: whoever sees the merge state also sees how it would be merged.
        val method = methodFor(repo, info?.rebaseable)
        io { store.setMergeMethod(id, method) }
        // Mergeability GitHub is still computing does not replace a known state in the periodic round: a conflict that
        // reads unknown once and then conflict again would otherwise be commented on twice.
        if (state != ReleaseMergeState.UNKNOWN || trigger != Trigger.PERIODIC) recordMergeState(projectSlot(withPr), state, client)
        setNotice(id, null)
        val headSha = if (info != null && info.state == "open") info.head.sha.takeIf { it.isNotEmpty() } else null
        val checks = headSha?.let { readChecks(projectSlot(withPr), client, it) }
        // Database migrations that collide with the target's hold the release like a conflict: no announcement, no
        // automatic merge, and approval refuses (docs/ARCHITECTURE.md §6 "Database migrations").
        if (headSha != null && owner != null) {
            migrationConflict(projectSlot(withPr), owner, repo.defaultBranch)?.let {
                setNotice(id, it)
                return
            }
        }
        // Rebase is impossible and merge commits are not allowed: no method works (Agern never squashes). Held like a
        // conflict: no announcement, no automatic merge; approval answers the same.
        if (info?.rebaseable == false && method == ReleaseMergeMethod.REBASE) {
            setNotice(id, cannotRebaseText(projectSlot(withPr), repo.defaultBranch, before = true))
            return
        }

        if (withPr.complete && state != ReleaseMergeState.CONFLICT && io { store.claimReadyAnnouncement(id) }) {
            bestEffort("comment on the release pull request") {
                io { client.comment(org, withPr.repo, prNumber, readyText(withPr, owner)) }
            }
        }
        if (trigger != Trigger.APPROVAL && owner != null && headSha != null && checks != null) {
            automaticLocked(id, owner, client, prNumber, state, headSha, checks, retry = trigger == Trigger.CHECK)
        }
    }

    /** The "ready to release" announcement on a release pull request. */
    private fun readyText(r: ReleaseRecord, owner: UserRecord?) =
        "**Agern: release `${r.name}` is ready to release.** ${r.merged.size} issue(s) merged. " + if (r.automatic) {
            "It is set to release automatically: Agern merges it for @${login(owner)} once it merges cleanly and all " +
                "checks on this pull request have passed. It can be switched to manual approval on ${pageUrl(r)}."
        } else {
            "@${login(owner)} can approve it on ${pageUrl(r)} or with `/factory release approve ${r.name}`."
        }

    /**
     * Reads and stores the checks on head commit [sha]; a read GitHub refuses is `unknown` with the reason. No checks
     * on a head first seen less than [checksSettleSeconds] ago is `awaiting` (CI registers its checks some seconds after
     * a push, so "none" right after opening the pull request or a new commit means nothing yet), not `none`.
     */
    private suspend fun readChecks(r: Slot, client: GitHubClient, sha: String): ChecksSummary {
        // The database keeps microseconds: compare at that precision, or a rounded-up "seen" lies after "now".
        val now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        val seen = io { store.headSeenAt(r.id, sha, now, r.memberRepo) }
        var summary = try {
            io { ChecksSummary.of(client.combinedStatus(org, r.repo, sha), client.checkRuns(org, r.repo, sha)) }
        } catch (e: GitHubException) {
            ChecksSummary(ReleaseChecksState.UNKNOWN, "Could not read the checks: ${e.message}".take(500))
        }
        val settled = seen.plusSeconds(checksSettleSeconds)
        if (summary.state == ReleaseChecksState.NONE && now.isBefore(settled)) {
            summary = ChecksSummary(
                ReleaseChecksState.AWAITING,
                "No checks yet; waiting for them to appear until ${settled.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)} " +
                    "(after that, no checks counts as green)",
            )
        }
        io { store.setChecks(r.id, summary.state, sha, summary.detail, r.memberRepo) }
        return summary
    }

    /**
     * Automatic release (docs/ARCHITECTURE.md §6 "Release mode"): merges the release exactly as an approval by its
     * [owner] would — same token, method and clean-up — when it is automatic, complete, clean, has a usable method and
     * its checks on [headSha] are green. Every blocker leaves it `ready`; nothing here overrides one. Decided by these
     * rules alone, never by an agent.
     */
    private suspend fun automaticLocked(
        id: UUID, owner: UserRecord, client: GitHubClient, pr: Int, state: String, headSha: String, checks: ChecksSummary, retry: Boolean,
    ) {
        val r = current(id)
        if (r.status != ReleaseStatus.READY || !r.automatic || !r.complete) return
        if (!policy.allowsAutomatic(r.repo)) {
            setNotice(id, "This project requires a human approval: the release is not merged automatically. Approve it by hand.")
            return
        }
        // A conflict has its own comment and banner; unknown mergeability (GitHub still computing) waits.
        if (state != ReleaseMergeState.CLEAN) return
        val method = r.mergeMethod?.takeIf { it == ReleaseMergeMethod.REBASE || it == ReleaseMergeMethod.MERGE } ?: return
        when (checks.state) {
            ReleaseChecksState.FAILED -> {
                setNotice(
                    id, "Checks failed on pull request #$pr (${checks.detail}), so it is not released automatically. Fix the " +
                        "release branch or re-run the checks; the owner can also approve it by hand.",
                )
                if (io { store.claimChecksFailure(id, headSha) }) {
                    bestEffort("comment on the failed checks") {
                        io {
                            client.comment(
                                org, r.repo, pr,
                                "**Agern:** checks failed on this release pull request (${checks.detail}). Release `${r.name}` is set " +
                                    "to release automatically, so Agern will not merge it while a check fails. Fix the release branch or " +
                                    "re-run the checks; @${login(owner)} can also approve it by hand on ${pageUrl(r)}",
                            )
                        }
                    }
                }
                return
            }
            ReleaseChecksState.UNKNOWN -> {
                setNotice(id, "${checks.detail} It is not released automatically until they can be read.")
                return
            }
            !in ReleaseChecksState.GREEN -> return // pending: the periodic re-evaluation looks again
        }
        if (r.autoMergeFailedSha == headSha && !retry) {
            setNotice(
                id, "GitHub refused the automatic merge of the release branch's current commit; Agern does not try it again " +
                    "for this commit. Use \"Check again\" to retry, or approve it.",
            )
            return
        }
        log.info("Release {} is ready, clean and green: merging it automatically for {}", id, owner.username)
        try {
            mergeLocked(owner, r, pr, method, headSha, client, automatic = true)
        } catch (e: ApiException) {
            io { store.setAutoMergeFailed(id, headSha) }
            log.warn("Release {}: the automatic merge was refused: {}", id, e.message)
            if (e.code != "release_conflict") {
                bestEffort("comment on the refused automatic merge") {
                    io {
                        client.comment(
                            org, r.repo, pr,
                            "**Agern:** merging release `${r.name}` automatically failed: ${e.message} Agern will not try again for " +
                                "this commit; @${login(owner)} can use \"Check again\" or approve it on ${pageUrl(r)}",
                        )
                    }
                }
            }
        }
    }

    /**
     * Why the release branch must not be merged into [target] because of its database migrations, or null. Compares
     * the two branches with git in [owner]'s clone (the one the release's runs were built in). A comparison that cannot
     * be made is logged and blocks nothing: CI is the strict gate.
     */
    private suspend fun migrationConflict(r: Slot, owner: UserRecord, target: String): String? {
        val branch = r.branch ?: return null
        return io {
            try {
                val cmp = workspaces.withRepoLock(owner.username, r.project) {
                    workspaces.git.compareBranches(
                        cloneOf(owner, r), target, branch, workspaces.home(owner.username),
                        access.githubToken(owner), MigrationCheck::rootOf,
                    )
                }
                if (cmp == null) log.warn("Release {}: the database migrations of {} could not be compared with {}", r.id, branch, target)
                cmp?.let { MigrationCheck.problems(it.baseFiles, it.changes) }?.takeIf { it.isNotEmpty() }
                    ?.let { MigrationCheck.releaseReason(target, branch, it) }
            } catch (e: Exception) {
                log.warn("Release {}: checking the database migrations failed: {}", r.id, e.message)
                null
            }
        }
    }

    /**
     * Why release pull request #[pr] must not be merged because of its submodule pointers, or null. Compares the release
     * branch with its target in [owner]'s clone; fails closed (a check that cannot be made refuses the merge).
     */
    private suspend fun submoduleProblem(r: Slot, owner: UserRecord, pr: Int): SubmoduleCheck.Problem? = io {
        val unchecked = SubmoduleCheck.Problem(
            SubmoduleCheck.UNCHECKED,
            "Submodule pointers not checked: the submodule pointers of pull request ${prRef(r, pr)} could not be compared with its " +
                "target. Nothing was merged. Check again, then approve.",
        )
        try {
            val branch = r.branch
            val target = r.target
            if (branch == null || target == null) unchecked
            else {
                val home = workspaces.home(owner.username)
                val token = access.githubToken(owner)
                val links = workspaces.withRepoLock(owner.username, r.project) {
                    workspaces.git.changedGitlinks(cloneOf(owner, r), target, branch, home, token)
                }
                if (links == null) unchecked
                else SubmoduleCheck.check(
                    links, target, prRef(r, pr), has = { workspaces.git.remoteHasCommit(it.url!!, it.sha, home, token) },
                    onError = { c, e -> log.warn("Release {}: checking submodule {} at {} failed: {}", r.id, c.path, c.sha, e.message) },
                )
            }
        } catch (e: Exception) {
            log.warn("Release {}: checking the submodule pointers failed, not merging: {}", r.id, e.message)
            unchecked
        }
    }

    /** Sets the merge state; on a change to `conflict`, comments once on the pull request. */
    private suspend fun recordMergeState(r: Slot, state: String, client: GitHubClient) {
        if (!io { store.setMergeState(r.id, state, r.memberRepo) } || state != ReleaseMergeState.CONFLICT) return
        val fresh = slotOf(current(r.id), r.memberRepo)
        val pr = r.prNumber ?: fresh?.prNumber ?: return
        val target = r.target ?: fresh?.target ?: "the default branch"
        bestEffort("comment on the conflicting release pull request") {
            io {
                client.comment(
                    org, r.repo, pr,
                    "**Agern:** ${branchText(r)} no longer merges cleanly into `$target`. Agern will not merge it. Resolve the " +
                        "conflicts on the release branch, then use \"Check again\" on the release page: ${pageUrl(r.release)}",
                )
            }
        }
    }

    /** Reads the pull request, re-reading up to 4 times while GitHub is still computing its mergeability. */
    private suspend fun readMergeable(client: GitHubClient, repo: String, number: Int): GhPullInfo? {
        var info = io { client.getPull(org, repo, number) }
        var tries = 0
        while (info != null && info.state == "open" && info.mergeable == null && tries < 4) {
            delay(mergeablePollMillis)
            info = io { client.getPull(org, repo, number) }
            tries++
        }
        return info
    }

    /**
     * The merge method approval uses: `rebase` keeps each issue's squash commit as one commit and the history linear; a
     * merge commit (which keeps them too, plus one merge commit) where rebase merging is disabled or GitHub reports the
     * pull request as not [rebaseable] (e.g. the default branch was merged into the release branch to resolve a
     * conflict); `unavailable` when the repository allows neither. A setting GitHub leaves out counts as allowed, and so
     * does a rebaseability it has not computed yet (null). Not rebaseable without merge commits stays `rebase`: GitHub
     * may compute it differently once the branch changes, and the evaluation holds the release with a notice meanwhile.
     */
    private fun methodFor(repo: GhRepo, rebaseable: Boolean?): String = when {
        repo.allowRebaseMerge != false && (rebaseable != false || repo.allowMergeCommit == false) -> ReleaseMergeMethod.REBASE
        repo.allowMergeCommit != false -> ReleaseMergeMethod.MERGE
        else -> ReleaseMergeMethod.UNAVAILABLE
    }

    /** Why a release GitHub cannot rebase cannot be merged at all: merge commits are not allowed either. */
    private fun cannotRebaseText(r: Slot, target: String, before: Boolean): String =
        "GitHub cannot rebase `${r.branch}` onto `$target` (it contains merge commits, e.g. from merging `$target` into it), " +
            "and ${r.repo} does not allow merge commits; Agern never squashes a release. Allow merge commits in the " +
            "repository's GitHub settings, or rebuild the release branch without merge commits, then " +
            (if (before) "use \"Check again\"." else "approve again. Nothing was merged.")

    /** GitHub's refusal of a rebase merge because the branch cannot be rebased (405 "This branch can't be rebased"). */
    private fun cannotRebase(e: GitHubException) =
        e.status == 405 && Regex("can.?t be rebased|cannot be rebased", RegexOption.IGNORE_CASE).containsMatchIn(e.message ?: "")

    /** Title and body of the release pull request (no closing keywords: Agern closes the issues itself). */
    private fun pullText(r: ReleaseRecord, owner: UserRecord?, target: String): Pair<String, String> {
        val body = buildString {
            append("Release **${r.name}** of $org/${r.repo}, built by Agern for @${login(owner)}.\n\n")
            if (r.description.isNotBlank()) append(r.description.trim()).append("\n\n")
            append("## Issues\n\n")
            for (i in r.merged) {
                append("- #${i.number} ${i.title}")
                i.prNumber?.let { append(" (pull request #$it)") }
                append('\n')
            }
            if (r.merged.isEmpty()) append("(none merged yet)\n")
            if (r.held.isNotEmpty()) append("\nHeld (not in the release branch yet): ${r.held.joinToString(", ") { "#${it.number}" }}\n")
            if (r.skipped.isNotEmpty()) append("\nNot included (skipped): ${r.skipped.joinToString(", ") { "#${it.number}" }}\n")
            if (r.automatic) {
                append(
                    "\nRelease mode: **automatic**. Agern merges this pull request for the release owner once it merges cleanly and " +
                        "all checks on it have passed: ${pageUrl(r)}. Each issue stays one commit on `$target`.\n",
                )
            } else {
                append(
                    "\nRelease mode: **manual**. Approved and merged from Agern by the release owner: ${pageUrl(r)}. " +
                        "Each issue stays one commit on `$target`.\n",
                )
            }
        }
        return "Release ${r.name}" to body
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------ approval

    private fun notReady(r: ReleaseRecord) = conflict("Release is ${statusLabel(r.status)}, not ready to release", "release_not_ready")

    private suspend fun approveLocked(owner: UserRecord, id: UUID) {
        val r = current(id)
        if (r.status != ReleaseStatus.READY) throw notReady(r)
        if (r.held.isNotEmpty()) {
            throw conflict(
                "${r.held.size} issue(s) are held: ${r.held.joinToString(", ") { "#${it.number}" }}. " +
                    "Approve or retry their runs, or move them out of the release first.",
                "release_incomplete",
            )
        }
        if (r.merged.isEmpty()) throw conflict("Nothing was merged into the release branch, so there is nothing to release.", "release_empty")
        val client = access.client(owner) ?: throw dk.egernet.factory.unprocessable("Register a GitHub token in Settings first", "github_token_missing")
        if (r.multiRepo) {
            approveMultiLocked(owner, id, client)
            return
        }

        evaluateLocked(id, becameReady = false, Trigger.APPROVAL)
        val now = current(id)
        if (now.status == ReleaseStatus.RELEASED) return // it had been merged on GitHub; the check finished it
        if (now.status != ReleaseStatus.READY) throw notReady(now)
        val pr = now.prNumber ?: throw conflict(now.notice ?: "The release pull request could not be opened.", "release_merge_failed")
        val target = now.targetBranch ?: "the default branch"
        val conflictMsg = "`${now.branch}` no longer merges cleanly into `$target`. Nothing was merged."
        if (now.mergeState == ReleaseMergeState.CONFLICT) throw conflict(conflictMsg, "release_conflict")
        now.targetBranch?.let { migrationConflict(projectSlot(now), owner, it) }?.let { throw conflict("$it Nothing was merged.", "release_migration_conflict") }
        val method = when (now.mergeMethod) {
            ReleaseMergeMethod.REBASE, ReleaseMergeMethod.MERGE -> now.mergeMethod
            ReleaseMergeMethod.UNAVAILABLE -> throw methodUnavailable(
                "This repository only allows squash merges. Allow rebase merging or merge commits in its GitHub settings to release.",
            )
            else -> throw conflict(now.notice ?: "Could not read the repository's merge settings; check again.", "release_merge_failed")
        }
        // A fresh read for the head commit: GitHub refuses the merge if the release branch moves after this.
        val head = io { client.getPull(org, now.repo, pr) }
        if (head?.mergeable == false) {
            recordMergeState(projectSlot(now), ReleaseMergeState.CONFLICT, client)
            throw conflict(conflictMsg, "release_conflict")
        }
        if (head?.rebaseable == false && method == ReleaseMergeMethod.REBASE) {
            // The evaluation found no method that works (merge commits are not allowed): refuse before merging.
            val text = cannotRebaseText(projectSlot(now), target, before = false)
            setNotice(id, text)
            throw conflict(text, "release_merge_failed")
        }
        mergeLocked(owner, now, pr, method, head?.head?.sha?.takeIf { it.isNotEmpty() }, client, automatic = false)
    }

    /**
     * The merge itself, shared by an approval and an automatic release: [method] (never squash), refused by GitHub if
     * the head moved from [headSha], then [finishLocked] with the method actually used. A rebase GitHub refuses because
     * the branch cannot be rebased (405 "can't be rebased", e.g. its rebaseability was not computed yet when it was
     * read) is tried once more with a merge commit where the repository allows one. A refusal throws the
     * [ApiException] an approval answers. First, every submodule pointer the release moves must name a commit its
     * submodule's remote has (409 `release_submodule_commit_missing`, or `release_submodule_check_failed` when that
     * cannot be checked; docs/ARCHITECTURE.md §6 "Submodule pointers").
     */
    private suspend fun mergeLocked(
        owner: UserRecord, now: ReleaseRecord, pr: Int, method: String, headSha: String?, client: GitHubClient, automatic: Boolean,
    ) {
        val (used, sha) = mergeSlot(owner, projectSlot(now), pr, method, headSha, client)
        finishLocked(now.id, used, sha, owner, automatic)
    }

    /**
     * Merges one release pull request [pr] of [now] (the guard, the merge, the single "can't be rebased" fallback; see
     * [mergeLocked]) and answers the method actually used and the commit GitHub reported. Throws the [ApiException] an
     * approval answers.
     */
    private suspend fun mergeSlot(
        owner: UserRecord, now: Slot, pr: Int, method: String, headSha: String?, client: GitHubClient,
    ): Pair<String, String?> {
        submoduleProblem(now, owner, pr)?.let { p ->
            setNotice(now.id, p.text)
            throw conflict(p.text, "release_${p.code}")
        }
        suspend fun merge(m: String) = io {
            client.mergePull(org, now.repo, pr, m, if (m == ReleaseMergeMethod.MERGE) "Release ${now.release.name} (#$pr)" else null, headSha)
        }
        var used = method
        val sha = try {
            merge(method)
        } catch (e: GitHubException) {
            if (method != ReleaseMergeMethod.REBASE || !cannotRebase(e)) throw mergeRefused(now, pr, method, e, client)
            val repo = try {
                io { client.getRepo(org, now.repo) }
            } catch (re: GitHubException) {
                null
            }
            val target = now.target ?: repo?.defaultBranch ?: "the default branch"
            if (repo == null || repo.allowMergeCommit == false) {
                val text = if (repo == null) "Merging pull request ${prRef(now, pr)} failed: ${e.message} (and ${now.repo}'s merge settings could " +
                    "not be read to try a merge commit instead). Check again, then approve."
                    else cannotRebaseText(now, target, before = false)
                setNotice(now.id, text)
                throw conflict(text, "release_merge_failed")
            }
            log.info("Release {}: GitHub cannot rebase pull request #{}; merging it with a merge commit instead", now.id, pr)
            used = ReleaseMergeMethod.MERGE
            try {
                merge(used)
            } catch (e2: GitHubException) {
                throw mergeRefused(now, pr, used, e2, client)
            }
        }
        if (used != method) io { store.setMergeMethod(now.id, used, now.memberRepo) }
        return used to sha
    }

    private fun methodUnavailable(msg: String) = conflict(msg, "release_merge_method_unavailable")

    /** What a refused merge means: the method is not allowed, a conflict, the branch moved, or another refusal. */
    private suspend fun mergeRefused(r: Slot, pr: Int, method: String, e: GitHubException, client: GitHubClient): ApiException {
        val msg = e.message ?: "GitHub refused the merge"
        val label = if (method == ReleaseMergeMethod.REBASE) "rebase merging" else "merge commits"
        if (e.status == 405 && msg.contains("not allowed", ignoreCase = true)) {
            // The repository forbids this method although its settings did not say so: never fall back to another one.
            val text = "GitHub does not allow $label in ${r.repo} ($msg). Allow rebase merging or merge commits in its GitHub " +
                "settings, then approve again. Nothing was merged."
            setNotice(r.id, text)
            io { store.setMergeMethod(r.id, null, r.memberRepo) }
            return methodUnavailable(text)
        }
        val after = runCatching { io { client.getPull(org, r.repo, pr) } }.getOrNull()
        if (after?.mergeable == false) {
            recordMergeState(r, ReleaseMergeState.CONFLICT, client)
            setNotice(r.id, "Merging pull request ${prRef(r, pr)} failed: $msg")
            return conflict("${branchText(r)} no longer merges cleanly into `${r.target}`. Nothing was merged.", "release_conflict")
        }
        val text = if (e.status == 409) "Merging pull request ${prRef(r, pr)} failed: the release branch changed since it was checked ($msg). Check again, then approve."
            else "Merging pull request ${prRef(r, pr)} failed: $msg"
        setNotice(r.id, text)
        return conflict(text, "release_merge_failed")
    }

    // ------------------------------------------------------------ one release pull request

    /**
     * One release pull request of [release] (docs/ARCHITECTURE.md §6 "Several repositories"): the project repository's
     * (its columns on `releases`, [row] null) or a member repository's (a `release_repos` row). The per-pull-request
     * steps (reading checks, merge state, the guards, the merge) run against either.
     */
    private class Slot(val release: ReleaseRecord, val row: ReleaseRepoRecord?) {
        val id: UUID get() = release.id
        /** Null for the project repository: what the store's setters take. */
        val memberRepo: String? get() = row?.repo
        val repo: String get() = row?.repo ?: release.repo
        /** The project (workspace) repository, whose clone holds the member clones. */
        val project: String get() = release.repo
        val branch: String? get() = if (row == null) release.branch else release.branch ?: "release/${release.name}"
        /** The branch the release pull request merges into. */
        val target: String? get() = if (row == null) release.targetBranch else row.baseBranch
        val prNumber: Int? get() = if (row == null) release.prNumber else row.prNumber
        val prUrl: String? get() = if (row == null) release.prUrl else row.prUrl
        val prBodySha: String? get() = if (row == null) release.prBodySha else row.prBodySha
        val mergeState: String get() = row?.mergeState ?: release.mergeState
        val mergeMethod: String? get() = if (row == null) release.mergeMethod else row.mergeMethod
        val checksSha: String? get() = if (row == null) release.checksSha else row.checksSha
        val checksState: String get() = row?.checksState ?: release.checksState
    }

    private fun projectSlot(r: ReleaseRecord) = Slot(r, null)

    /** The slot of [memberRepo] (null: the project repository) in [r]; null when [r] has no such member repository. */
    private fun slotOf(r: ReleaseRecord, memberRepo: String?): Slot? =
        if (memberRepo == null) projectSlot(r) else r.repos.firstOrNull { it.repo == memberRepo }?.let { Slot(r, it) }

    /** The owner's clone the slot's branches are compared in: the workspace clone, or the member's clone inside it. */
    private fun cloneOf(owner: UserRecord, s: Slot): java.nio.file.Path {
        val main = workspaces.mainDir(owner.username, s.project)
        return s.row?.let { workspaces.memberPath(main, it.directory) } ?: main
    }

    /** "`release/x`", or "`release/x` of org/member" for a member repository. */
    private fun branchText(s: Slot) = if (s.row == null) "`${s.branch}`" else "`${s.branch}` of $org/${s.repo}"

    /** "#n", or "org/member#n" for a member repository. */
    private fun prRef(s: Slot, pr: Int) = if (s.row == null) "#$pr" else "$org/${s.repo}#$pr"

    // ------------------------------------------------------------ several repositories (§6 "Several repositories")

    /** What the evaluation of one release pull request found ([evaluateSlot]). [problem]: what keeps it from being merged. */
    private class SlotState(
        val slot: Slot, val pr: Int?, val mergeState: String, val headSha: String?, val checks: ChecksSummary?,
        val problem: String?, val mergedOnGitHub: Boolean = false,
    )

    /**
     * [evaluateLocked] of a release of several repositories: the member pull requests in merge order, the submodule
     * pointers on the project repository's release branch, then its pull request when it is touched. Ready to release
     * only when every touched repository's pull request is open (or merged) and none has a conflict, a migration
     * conflict or an unusable merge method. An automatic release that is partly released is only merged by "Check again".
     */
    private suspend fun evaluateMultiLocked(id: UUID, owner: UserRecord, client: GitHubClient, trigger: Trigger) {
        val r = current(id)
        if (!r.complete) {
            io { store.setMergeState(id, ReleaseMergeState.UNKNOWN) }
            setNotice(id, null)
            return
        }
        val projectRepo = try {
            io { client.getRepo(org, r.repo) }
        } catch (e: GitHubException) {
            null
        } ?: run {
            readFailed(id, trigger, "Could not read ${r.repo} on GitHub with the owner's token; check again later.")
            return
        }
        if (r.targetBranch != projectRepo.defaultBranch) io { store.setTarget(id, projectRepo.defaultBranch) }
        val states = mutableListOf<SlotState>()
        for (row in r.touchedRepos) {
            if (row.merged) continue
            val ghRepo = try {
                io { client.getRepo(org, row.repo) }
            } catch (e: GitHubException) {
                null
            } ?: run {
                readFailed(id, trigger, "Could not read $org/${row.repo} on GitHub with the owner's token; check again later.")
                return
            }
            states += evaluateSlot(id, row.repo, owner, client, ghRepo, trigger) ?: return
        }
        // The pointers before the project repository's pull request is opened or read, so that it shows them.
        var pointerProblem: String? = null
        val withMembers = current(id)
        if (withMembers.projectTouched && withMembers.prMergedAt == null) {
            pointerProblem = movePointers(withMembers, client, onlyMerged = false)
            states += evaluateSlot(id, null, owner, client, projectRepo, trigger) ?: return
        }
        if (states.any { it.mergedOnGitHub } && finishIfAllMerged(id)) return
        val open = states.filter { !it.mergedOnGitHub }
        syncBodies(id, owner, client)
        val now = current(id)
        val problem = open.firstNotNullOfOrNull { it.problem } ?: pointerProblem
        val ready = problem == null && open.all { it.pr != null && it.mergeState != ReleaseMergeState.CONFLICT }
        setNotice(
            id,
            problem ?: now.prMergedAt?.let { earlyMergeNotice(now) } ?: if (now.partlyReleased) {
                now.notice?.takeIf { it.startsWith(PARTIAL_NOTICE_START) } ?: partialReport(now, emptyList()).first
            } else null,
        )
        if (ready && open.isNotEmpty() && io { store.claimReadyAnnouncement(id) }) {
            for (st in open) {
                val pr = st.pr ?: continue
                bestEffort("comment on the release pull request") { io { client.comment(org, st.slot.repo, pr, readyText(now, owner)) } }
            }
        }
        if (trigger != Trigger.APPROVAL && (trigger == Trigger.CHECK || !now.partlyReleased)) {
            automaticMultiLocked(id, owner, client, open, ready, retry = trigger == Trigger.CHECK)
        }
    }

    /**
     * Reconciles, opens (from `release/<name>` to its target), reads and checks one release pull request: the same
     * steps as the single pull request of [evaluateLocked]. Null when a read failed (a notice says so, except in the
     * periodic round). [ghRepo]: the repository as the owner's token reads it (its merge settings).
     */
    private suspend fun evaluateSlot(
        id: UUID, memberRepo: String?, owner: UserRecord, client: GitHubClient, ghRepo: GhRepo, trigger: Trigger,
    ): SlotState? {
        val slot = slotOf(current(id), memberRepo) ?: return null
        val target = slot.target ?: ghRepo.defaultBranch
        var existing = slot.prNumber
        val known = existing
        if (known != null) {
            val info = try {
                io { client.getPull(org, slot.repo, known) }
            } catch (e: GitHubException) {
                readFailed(id, trigger, "Could not read pull request ${prRef(slot, known)}: ${e.message}")
                return null
            }
            when {
                info != null && info.merged -> {
                    recordMergedOnGitHub(slot, info.mergeCommitSha)
                    return SlotState(slot, known, ReleaseMergeState.CLEAN, null, null, null, mergedOnGitHub = true)
                }
                info == null || info.state != "open" -> {
                    log.info("Release {}: pull request {} was closed without merging; forgetting it", id, prRef(slot, known))
                    io { store.clearPull(id, memberRepo) }
                    existing = null
                }
            }
        }
        val prNumber: Int = existing ?: run {
            val now = current(id)
            val (title, body) = multiPullText(now, slotOf(now, memberRepo)!!, owner)
            val branch = now.branch ?: "release/${now.name}"
            val (pr, created) = try {
                io {
                    client.findOpenPull(org, slot.repo, branch)?.let { it to false }
                        ?: (client.createPull(org, slot.repo, branch, target, title, body) to true)
                }
            } catch (e: Exception) {
                return SlotState(slot, null, ReleaseMergeState.UNKNOWN, null, null, "Could not open the release pull request in $org/${slot.repo}: ${e.message}")
            }
            io { store.setPull(id, pr.number, pr.htmlUrl, if (created) sha256(title + "\n" + body) else "", target, memberRepo) }
            pr.number
        }
        val info = try {
            readMergeable(client, slot.repo, prNumber)
        } catch (e: GitHubException) {
            readFailed(id, trigger, "Could not check pull request ${prRef(slot, prNumber)}: ${e.message}")
            return null
        }
        if (info?.merged == true) {
            recordMergedOnGitHub(slot, info.mergeCommitSha)
            return SlotState(slot, prNumber, ReleaseMergeState.CLEAN, null, null, null, mergedOnGitHub = true)
        }
        val state = when (info?.mergeable) {
            true -> ReleaseMergeState.CLEAN
            false -> ReleaseMergeState.CONFLICT
            else -> ReleaseMergeState.UNKNOWN
        }
        val method = methodFor(ghRepo, info?.rebaseable)
        io { store.setMergeMethod(id, method, memberRepo) }
        val withPr = slotOf(current(id), memberRepo)!!
        if (state != ReleaseMergeState.UNKNOWN || trigger != Trigger.PERIODIC) recordMergeState(withPr, state, client)
        val headSha = if (info != null && info.state == "open") info.head.sha.takeIf { it.isNotEmpty() } else null
        val checks = headSha?.let { readChecks(withPr, client, it) }
        val problem = (if (headSha != null) migrationConflict(withPr, owner, target) else null)
            ?: (if (info?.rebaseable == false && method == ReleaseMergeMethod.REBASE) cannotRebaseText(withPr, target, before = true) else null)
            ?: (if (method == ReleaseMergeMethod.UNAVAILABLE) squashOnlyText(withPr) else null)
        return SlotState(withPr, prNumber, state, headSha, checks, problem)
    }

    private fun squashOnlyText(s: Slot) =
        "$org/${s.repo} only allows squash merges. Allow rebase merging or merge commits in its GitHub settings to release."

    /**
     * A release pull request a person merged on GitHub: a member's is recorded as merged with its merge commit; the
     * project repository's while a touched member is not merged yet is recorded too (docs/ARCHITECTURE.md §6 "Several
     * repositories": its submodule pointers must then be bumped by hand).
     */
    private suspend fun recordMergedOnGitHub(s: Slot, sha: String?) {
        log.info("Release {}: pull request of {} was merged on GitHub", s.id, s.repo)
        if (s.row != null) io { store.setRepoMerged(s.id, s.repo, sha) } else io { store.setProjectMergedEarly(s.id, sha) }
    }

    /** Finishes the release as merged on GitHub when every touched repository's pull request is merged; true if so. */
    private suspend fun finishIfAllMerged(id: UUID): Boolean {
        val r = current(id)
        if (r.status != ReleaseStatus.READY || !r.multiRepo) return false
        val membersDone = r.touchedRepos.all { it.merged }
        val projectDone = !r.projectTouched || r.prMergedAt != null
        if (!membersDone || !projectDone || (r.touchedRepos.isEmpty() && r.prMergedAt == null)) return false
        finishLocked(id, method = null, sha = r.mergeSha, by = null)
        return true
    }

    private fun earlyMergeNotice(r: ReleaseRecord) =
        "Pull request #${r.prNumber} of $org/${r.repo} was merged on GitHub before the member pull requests. Approve to merge " +
            "those; the submodule pointers must then be bumped by hand."

    /** Keeps the title and body of every open release pull request up to date (by hash), once all are opened. */
    private suspend fun syncBodies(id: UUID, owner: UserRecord, client: GitHubClient) {
        val r = current(id)
        val slots = r.touchedRepos.filter { !it.merged }.map { Slot(r, it) } +
            (if (r.projectTouched && r.prMergedAt == null) listOf(projectSlot(r)) else emptyList())
        for (s in slots) {
            val pr = s.prNumber ?: continue
            val (title, body) = multiPullText(r, s, owner)
            val sha = sha256(title + "\n" + body)
            if (s.prBodySha == sha) continue
            try {
                io { client.updatePull(org, s.repo, pr, title, body) }
                io { store.setPullBody(id, sha, s.memberRepo) }
            } catch (e: Exception) {
                log.warn("Release {}: could not update pull request {}: {}", id, prRef(s, pr), e.message)
            }
        }
    }

    /**
     * Title and body of a release pull request of a release of several repositories (no closing keywords): a member's
     * lists the issues built in it; the project repository's is the single release's text with the submodule pointers.
     */
    private fun multiPullText(r: ReleaseRecord, s: Slot, owner: UserRecord?): Pair<String, String> {
        val others = r.toDto().repositories.filter { it.repo != s.repo && it.prUrl != null }.mapNotNull { it.prUrl }
        val doNotMerge = "Do not merge it by hand: Agern merges the member pull requests first and then moves the submodule pointers."
        val target = s.target ?: "the default branch"
        val body = buildString {
            val row = s.row
            if (row != null) {
                append("Release **${r.name}** of $org/${r.repo}: the part in $org/${row.repo}, built by Agern for @${login(owner)}.\n\n")
                if (r.description.isNotBlank()) append(r.description.trim()).append("\n\n")
                append("## Issues\n\n")
                for (i in r.merged.filter { it.targetRepo == row.repo }) {
                    append("- $org/${r.repo}#${i.number} ${i.title}")
                    i.prNumber?.let { append(" (pull request #$it)") }
                    append('\n')
                }
            } else {
                append("Release **${r.name}** of $org/${r.repo}, built by Agern for @${login(owner)}.\n\n")
                if (r.description.isNotBlank()) append(r.description.trim()).append("\n\n")
                append("## Issues\n\n")
                for (i in r.merged) {
                    append("- #${i.number} ${i.title}")
                    i.prNumber?.let { append(if (i.targetRepo != null) " (pull request $org/${i.targetRepo}#$it)" else " (pull request #$it)") }
                    append('\n')
                }
                if (r.skipped.isNotEmpty()) append("\nNot included (skipped): ${r.skipped.joinToString(", ") { "#${it.number}" }}\n")
                val subs = r.touchedRepos.filter { it.submodule }
                if (subs.isNotEmpty()) {
                    append("\n## Submodule pointers\n\n")
                    for (m in subs) {
                        append("- `${m.directory}` → $org/${m.repo} `${s.branch}`")
                        m.prUrl?.let { append(" (pull request $it)") }
                        append('\n')
                    }
                }
            }
            if (others.isNotEmpty()) {
                append("\n## Other pull requests of this release\n\n")
                others.forEach { append("- $it\n") }
            }
            val mode = if (r.automatic) {
                "\nRelease mode: **automatic**. Agern merges this pull request together with the other pull requests of the release " +
                    "for the release owner once they merge cleanly and all checks on them have passed: ${pageUrl(r)}. "
            } else {
                "\nRelease mode: **manual**. Approved and merged from Agern by the release owner: ${pageUrl(r)}. "
            }
            append(mode).append("Each issue stays one commit on `$target`. ").append(doNotMerge).append('\n')
        }
        return "Release ${r.name}" to body
    }

    /**
     * Moves the gitlink of every touched submodule member on the project repository's `release/<name>` to the member's
     * merge commit when merged, else (unless [onlyMerged]) to the tip of its `release/<name>`: one commit with every
     * gitlink that differs, put on the branch's tip without force. A refused update (the branch moved) is tried once
     * more from the new tip. Null when done (or nothing to move), else the problem.
     */
    private suspend fun movePointers(r: ReleaseRecord, client: GitHubClient, onlyMerged: Boolean): String? {
        val branch = r.branch ?: return null
        val subs = r.touchedRepos.filter { it.submodule }
        if (subs.isEmpty()) return null
        val fail = "Could not move the submodule pointers on `$branch`"
        val desired = linkedMapOf<String, String>()
        val mergeCommits = mutableSetOf<String>()
        try {
            for (m in subs) {
                val sha = when {
                    m.merged -> m.mergeSha ?: return "$fail: GitHub did not report the merge commit of $org/${m.repo}#${m.prNumber}"
                    onlyMerged -> return "$fail: the pull request of $org/${m.repo} is not merged"
                    else -> io { client.branchSha(org, m.repo, branch) } ?: return "$fail: `$branch` was not found in $org/${m.repo}"
                }
                desired[m.directory] = sha
                if (m.merged) mergeCommits += m.directory
            }
        } catch (e: GitHubException) {
            return "$fail: ${e.message}"
        }
        var lastError: String? = null
        for (attempt in 0..1) {
            try {
                val tip = io { client.branchSha(org, r.repo, branch) } ?: return "$fail: the branch was not found in $org/${r.repo}"
                val changed = linkedMapOf<String, String>()
                for ((dir, sha) in desired) if (io { client.gitlinkAt(org, r.repo, dir, branch) } != sha) changed[dir] = sha
                if (changed.isEmpty()) return null
                val message = if (changed.keys.all { it in mergeCommits }) {
                    "Release ${r.name}: bump " + changed.entries.joinToString(", ") { "${it.key} to ${it.value.take(12)}" }
                } else {
                    "Release ${r.name}: point ${changed.keys.joinToString(", ")} at $branch"
                }
                val commit = io { client.createGitlinksCommit(org, r.repo, tip, changed, message) }
                io { client.forceUpdateBranch(org, r.repo, branch, commit, force = false) }
                log.info("Release {}: moved the submodule pointers {} on {}", r.id, changed.keys, branch)
                return null
            } catch (e: GitHubException) {
                lastError = e.message
                log.info("Release {}: moving the submodule pointers failed (attempt {}): {}", r.id, attempt + 1, e.message)
            }
        }
        return "$fail: $lastError"
    }

    /**
     * The periodic look at a manual release of several repositories: one read of each open release pull request.
     * Anything merged, closed or with a new head commit gets a full re-evaluation (which never merges a manual release);
     * otherwise only the mergeability is refreshed.
     */
    private suspend fun reconcileMultiLocked(r: ReleaseRecord) {
        val client = ownerClient(r) ?: return
        val slots = r.touchedRepos.filter { !it.merged }.map { Slot(r, it) } +
            (if (r.projectTouched && r.prMergedAt == null) listOf(projectSlot(r)) else emptyList())
        for (s in slots) {
            val pr = s.prNumber ?: continue
            val info = try {
                io { client.getPull(org, s.repo, pr) }
            } catch (e: GitHubException) {
                log.info("Release {}: could not read pull request {} (tried again next round): {}", r.id, prRef(s, pr), e.message)
                return
            }
            if (info == null || info.merged || info.state != "open" || (info.head.sha.isNotEmpty() && info.head.sha != s.checksSha)) {
                evaluateLocked(r.id, becameReady = false, Trigger.PERIODIC)
                return
            }
            info.mergeable?.let { recordMergeState(s, if (it) ReleaseMergeState.CLEAN else ReleaseMergeState.CONFLICT, client) }
        }
    }

    /** One release pull request approval will merge: [headSha] as read just before (GitHub refuses if it moved). */
    private class Planned(val slot: Slot, val pr: Int, val method: String, val headSha: String?)

    /** The pull request an approval or automatic release last tried (named in the comment on a refused automatic merge). */
    private class Attempt(var slot: Slot? = null, var pr: Int? = null)

    /**
     * Approval of a release of several repositories (docs/ARCHITECTURE.md §6 "Several repositories"): every refusal
     * that changes nothing on GitHub is checked for all unmerged release pull requests first ([planMerges]), then
     * [mergeMulti] merges them in order. Merged pull requests are never merged again.
     */
    private suspend fun approveMultiLocked(owner: UserRecord, id: UUID, client: GitHubClient) {
        evaluateLocked(id, becameReady = false, Trigger.APPROVAL)
        val now = current(id)
        if (now.status == ReleaseStatus.RELEASED) return
        if (now.status != ReleaseStatus.READY) throw notReady(now)
        val attempt = Attempt()
        val plan = try {
            planMerges(now, owner, client, attempt)
        } catch (e: ApiException) {
            throw stopPartway(id, client, attempt, e, emptyList())
        }
        mergeMulti(owner, id, plan, client, automatic = false, attempt)
    }

    /**
     * The pull requests still to merge, members in merge order and then the project repository's, each checked as an
     * approval of a single release checks its one: it exists, no conflict, no migration conflict, a usable method and a
     * fresh head read. Throws the refusal (today's codes; the messages name a member repository).
     */
    private suspend fun planMerges(now: ReleaseRecord, owner: UserRecord, client: GitHubClient, attempt: Attempt): List<Planned> {
        val slots = now.touchedRepos.filter { !it.merged }.map { Slot(now, it) } +
            (if (now.projectTouched && now.prMergedAt == null) listOf(projectSlot(now)) else emptyList())
        val plan = mutableListOf<Planned>()
        for (slot in slots) {
            attempt.slot = slot
            attempt.pr = slot.prNumber
            val pr = slot.prNumber
                ?: throw conflict(now.notice ?: "The release pull request of $org/${slot.repo} could not be opened.", "release_merge_failed")
            val target = slot.target ?: "the default branch"
            val conflictMsg = "${branchText(slot)} no longer merges cleanly into `$target`. Nothing was merged."
            if (slot.mergeState == ReleaseMergeState.CONFLICT) throw conflict(conflictMsg, "release_conflict")
            slot.target?.let { migrationConflict(slot, owner, it) }?.let { throw conflict("$it Nothing was merged.", "release_migration_conflict") }
            val method = when (slot.mergeMethod) {
                ReleaseMergeMethod.REBASE, ReleaseMergeMethod.MERGE -> slot.mergeMethod!!
                ReleaseMergeMethod.UNAVAILABLE -> throw methodUnavailable(squashOnlyText(slot))
                else -> throw conflict(now.notice ?: "Could not read the merge settings of $org/${slot.repo}; check again.", "release_merge_failed")
            }
            val head = io { client.getPull(org, slot.repo, pr) }
            if (head?.mergeable == false) {
                recordMergeState(slot, ReleaseMergeState.CONFLICT, client)
                throw conflict(conflictMsg, "release_conflict")
            }
            if (head?.rebaseable == false && method == ReleaseMergeMethod.REBASE) {
                val text = cannotRebaseText(slot, target, before = false)
                setNotice(now.id, text)
                throw conflict(text, "release_merge_failed")
            }
            plan += Planned(slot, pr, method, head?.head?.sha?.takeIf { it.isNotEmpty() })
        }
        attempt.slot = null
        attempt.pr = null
        return plan
    }

    /**
     * Merges [plan] in order: each member pull request (its submodule guard first; stored as merged at once), then, when
     * the project repository is touched, the pointers are moved to the members' merge commits, read back and checked,
     * the release guard runs and its pull request is merged with its fresh head. Any failure stops at once
     * ([stopPartway]); nothing is retried or reverted.
     */
    private suspend fun mergeMulti(owner: UserRecord, id: UUID, plan: List<Planned>, client: GitHubClient, automatic: Boolean, attempt: Attempt) {
        val members = plan.filter { it.slot.row != null }
        for ((i, p) in members.withIndex()) {
            attempt.slot = p.slot
            attempt.pr = p.pr
            try {
                val (_, sha) = mergeSlot(owner, p.slot, p.pr, p.method, p.headSha, client)
                io { store.setRepoMerged(id, p.slot.repo, sha) }
                log.info("Release {}: merged {} into {} of {}", id, prRef(p.slot, p.pr), p.slot.target, p.slot.repo)
            } catch (e: Exception) {
                throw stopPartway(id, client, attempt, e, members.drop(i + 1) + plan.filter { it.slot.row == null })
            }
        }
        val project = plan.firstOrNull { it.slot.row == null }
        if (project == null) {
            finishLocked(id, method = null, sha = current(id).mergeSha, by = owner, automatic = automatic)
            return
        }
        attempt.slot = project.slot
        attempt.pr = project.pr
        try {
            val r = current(id)
            val branch = r.branch ?: "release/${r.name}"
            movePointers(r, client, onlyMerged = true)?.let { throw conflict(it, "release_merge_failed") }
            for (m in r.touchedRepos.filter { it.submodule }) {
                val sha = m.mergeSha ?: throw conflict("GitHub did not report the merge commit of $org/${m.repo}.", "release_merge_failed")
                val at = io { client.gitlinkAt(org, r.repo, m.directory, branch) }
                if (at != sha || !io { client.commitExists(org, m.repo, sha) }) {
                    throw conflict(
                        "The submodule pointer `${m.directory}` on `$branch` is not at the merge commit `${sha.take(12)}` of $org/${m.repo}.",
                        "release_submodule_commit_missing",
                    )
                }
            }
            val fresh = readMergeable(client, r.repo, project.pr)
            if (fresh?.mergeable == false) {
                recordMergeState(projectSlot(r), ReleaseMergeState.CONFLICT, client)
                throw conflict("`$branch` no longer merges cleanly into `${r.targetBranch}`.", "release_conflict")
            }
            val (used, sha) = mergeSlot(owner, projectSlot(r), project.pr, project.method, fresh?.head?.sha?.takeIf { it.isNotEmpty() }, client)
            finishLocked(id, used, sha, owner, automatic)
        } catch (e: Exception) {
            throw stopPartway(id, client, attempt, e, emptyList())
        }
    }

    /**
     * What a failure during [planMerges] or [mergeMulti] answers. Before anything of the release was merged: the
     * refusal itself (today's codes). After: 409 `release_partially_merged` with the per-repository report, which also
     * becomes the notice and one comment on every release pull request that is still open. The release stays `ready`.
     */
    private suspend fun stopPartway(id: UUID, client: GitHubClient, attempt: Attempt, e: Exception, notAttempted: List<Planned>): Exception {
        val r = current(id)
        if (!r.multiRepo || !(r.repos.any { it.merged } || r.prMergedAt != null)) {
            return e as? ApiException ?: ApiException(io.ktor.http.HttpStatusCode.BadGateway, "github_error", e.message ?: "GitHub error")
        }
        log.warn("Release {}: releasing stopped partway: {}", id, e.message)
        val reason = (e.message ?: e.javaClass.simpleName).removeSuffix(" Nothing was merged.").trim()
        val failed = attempt.slot?.let { slotOf(r, it.memberRepo) }?.let { s -> Triple(s, attempt.pr ?: s.prNumber, reason) }
        val (notice, comment) = partialReport(r, listOfNotNull(failed) + notAttempted.map { Triple(it.slot, it.pr, "not attempted") })
        setNotice(id, notice)
        val open = r.touchedRepos.filter { !it.merged }.map { Slot(r, it) } +
            (if (r.projectTouched && r.prMergedAt == null) listOf(projectSlot(r)) else emptyList())
        for (s in open) {
            val pr = s.prNumber ?: continue
            bestEffort("comment on the partly merged release") { io { client.comment(org, s.repo, pr, comment) } }
        }
        return conflict(notice, "release_partially_merged")
    }

    /** The "Merged: …" part of the per-repository report; null when nothing of [r] is merged. */
    private fun mergedPart(r: ReleaseRecord): String? {
        val merged = r.touchedRepos.filter { it.merged }.map { "$org/${it.repo}#${it.prNumber} (`${it.baseBranch}` at `${it.mergeSha?.take(12)}`)" } +
            r.repos.filter { it.merged && r.touchedRepos.none { t -> t.repo == it.repo } }.map { "$org/${it.repo}#${it.prNumber}" } +
            (if (r.prMergedAt != null) listOf("$org/${r.repo}#${r.prNumber} (`${r.targetBranch}` at `${r.mergeSha?.take(12)}`)") else emptyList())
        return merged.takeIf { it.isNotEmpty() }?.joinToString(", ", "Merged: ", ".")
    }

    /**
     * The report of a release that stopped partway, as notice and as comment: what is merged, and per pull request
     * that is not, why ([failures], in order; every other unmerged one is "not merged yet").
     */
    private fun partialReport(r: ReleaseRecord, failures: List<Triple<Slot, Int?, String>>): Pair<String, String> {
        val listed = failures.map { it.first.repo }.toSet()
        val rest = (r.touchedRepos.filter { !it.merged }.map { Slot(r, it) } +
            (if (r.projectTouched && r.prMergedAt == null) listOf(projectSlot(r)) else emptyList()))
            .filter { it.repo !in listed }.map { Triple(it, it.prNumber, "not merged yet") }
        val notMerged = (failures + rest).joinToString("; ") { (s, pr, why) -> "$org/${s.repo}${pr?.let { "#$it" } ?: ""}: $why" }
        val text = "releasing `${r.name}` stopped partway. ${mergedPart(r) ?: "Merged: nothing."} Not merged: $notMerged. The pull " +
            "requests that are not merged are left open for a human: fix the problem, then approve again on ${pageUrl(r)} (Agern " +
            "continues with what is not merged), or merge them by hand."
        return text.replaceFirstChar { it.uppercase() } to "**Agern:** $text"
    }

    /**
     * Automatic release of several repositories: merged exactly as an approval would ([planMerges], [mergeMulti]) when
     * ready, every open release pull request merges cleanly with a usable method and all their checks are green. A
     * refusal is not tried again for the same set of head commits (unless [retry]).
     */
    private suspend fun automaticMultiLocked(
        id: UUID, owner: UserRecord, client: GitHubClient, states: List<SlotState>, ready: Boolean, retry: Boolean,
    ) {
        val r = current(id)
        if (r.status != ReleaseStatus.READY || !r.automatic || !r.complete) return
        if (!policy.allowsAutomatic(r.repo)) {
            setNotice(id, "This project requires a human approval: the release is not merged automatically. Approve it by hand.")
            return
        }
        if (!ready) return
        for (st in states) {
            if (st.mergeState != ReleaseMergeState.CLEAN) return
            val m = slotOf(r, st.slot.memberRepo)?.mergeMethod
            if (m != ReleaseMergeMethod.REBASE && m != ReleaseMergeMethod.MERGE) return
            val checks = st.checks ?: return
            val pr = st.pr ?: return
            val head = st.headSha ?: return
            when (checks.state) {
                ReleaseChecksState.FAILED -> {
                    setNotice(
                        id, "Checks failed on pull request $org/${st.slot.repo}#$pr (${checks.detail}), so it is not released automatically. " +
                            "Fix the release branch or re-run the checks; the owner can also approve it by hand.",
                    )
                    if (io { store.claimChecksFailure(id, head, st.slot.memberRepo) }) {
                        bestEffort("comment on the failed checks") {
                            io {
                                client.comment(
                                    org, st.slot.repo, pr,
                                    "**Agern:** checks failed on this release pull request (${checks.detail}). Release `${r.name}` is set " +
                                        "to release automatically, so Agern will not merge it while a check fails. Fix the release branch or " +
                                        "re-run the checks; @${login(owner)} can also approve it by hand on ${pageUrl(r)}",
                                )
                            }
                        }
                    }
                    return
                }
                ReleaseChecksState.UNKNOWN -> {
                    setNotice(id, "${checks.detail} It is not released automatically until they can be read.")
                    return
                }
                !in ReleaseChecksState.GREEN -> return
            }
        }
        val key = sha256(states.map { "${it.slot.repo}:${it.headSha}" }.sorted().joinToString("\n"))
        if (r.autoMergeFailedSha == key && !retry) {
            setNotice(
                id, "GitHub refused the automatic merge of the release branches' current commits; Agern does not try it again " +
                    "for these commits. Use \"Check again\" to retry, or approve it.",
            )
            return
        }
        log.info("Release {} is ready, clean and green in every repository: merging it automatically for {}", id, owner.username)
        val attempt = Attempt()
        try {
            val plan = try {
                planMerges(current(id), owner, client, attempt)
            } catch (e: ApiException) {
                throw stopPartway(id, client, attempt, e, emptyList())
            }
            mergeMulti(owner, id, plan, client, automatic = true, attempt)
        } catch (e: ApiException) {
            io { store.setAutoMergeFailed(id, key) }
            log.warn("Release {}: the automatic merge was refused: {}", id, e.message)
            val s = attempt.slot
            val pr = attempt.pr
            if (e.code != "release_conflict" && e.code != "release_partially_merged" && s != null && pr != null) {
                bestEffort("comment on the refused automatic merge") {
                    io {
                        client.comment(
                            org, s.repo, pr,
                            "**Agern:** merging release `${r.name}` automatically failed: ${e.message} Agern will not try again for " +
                                "these commits; @${login(owner)} can use \"Check again\" or approve it on ${pageUrl(r)}",
                        )
                    }
                }
            }
        }
    }

    /**
     * After a release of several repositories was merged: each merged issue is closed with a comment naming the release
     * pull request of its repository, that repository's base branch and merge commit; `release/<name>` is deleted in the
     * project repository and every member repository where Agern created it (except the submodule members' when the
     * project repository's pull request was merged on GitHub first: their pointers must be bumped by hand) and forgotten
     * in the owner's clones. Failures and what is left to do by hand are the notice.
     */
    private suspend fun cleanupMultiLocked(r: ReleaseRecord, owner: UserRecord, client: GitHubClient) {
        val failed = mutableListOf<String>()
        val approval = approvalText(r)
        for (i in r.merged) {
            val row = i.targetRepo?.let { t -> r.repos.firstOrNull { it.repo == t } }
            val (url, base, sha, method) = if (row != null) {
                listOf(row.prUrl ?: "$org/${row.repo}#${row.prNumber}", row.baseBranch, row.mergeSha, row.mergeMethod)
            } else {
                listOf(r.prUrl ?: "#${r.prNumber}", r.targetBranch ?: "the default branch", r.mergeSha, r.mergeMethod)
            }
            val target = base ?: "the default branch"
            val commit = sha?.let { " (`$target` at $it)" } ?: ""
            try {
                io {
                    val gh = client.getIssue(org, r.repo, i.number)
                    if (gh != null && gh.state == "open") {
                        client.comment(
                            org, r.repo, i.number,
                            "**Agern: released** in release `${r.name}`: pull request $url was ${howMerged(method, target)}$commit.$approval",
                        )
                        client.closeIssue(org, r.repo, i.number)
                    }
                }
            } catch (e: Exception) {
                failed += "could not close #${i.number}: ${e.message}"
            }
        }
        val keep = if (r.prMergedAt != null) r.touchedRepos.filter { it.submodule }.map { it.repo }.toSet() else emptySet()
        failed += deleteReleaseBranches(r, client, owner, keep).map { "could not delete the branch ${r.branch} in $it" }
        val branch = r.branch ?: "release/${r.name}"
        val byHand = r.touchedRepos.filter { it.repo in keep }.joinToString("; ") {
            "`${it.directory}`: bump to `${it.mergeSha?.take(12)}`, then delete `$branch` in $org/${it.repo}"
        }
        val notice = listOfNotNull(
            failed.takeIf { it.isNotEmpty() }?.joinToString("; ", "Released, but ", "."),
            byHand.takeIf { it.isNotEmpty() }?.let {
                "The workspace pull request was merged on GitHub before the member pull requests, so the submodule pointers must be bumped by hand: $it."
            },
        ).joinToString(" ").takeIf { it.isNotEmpty() }
        io { store.finishCleanup(r.id, notice?.take(2000)) }
    }

    /**
     * Deletes `release/<name>` in the project repository and in every member repository of [r] where Agern created it
     * (except [keep]), recording each deletion, and forgets it in the owner's clones. Answers "<repo>: <message>" per
     * failure. Only ever deletes `release/<the release's name>`, only in those repositories.
     */
    private suspend fun deleteReleaseBranches(
        r: ReleaseRecord, client: GitHubClient, owner: UserRecord?, keep: Set<String> = emptySet(),
    ): List<String> {
        val branch = "release/${r.name}"
        if (r.branch != branch) return emptyList()
        val failed = mutableListOf<String>()
        if (!r.branchDeleted) {
            try {
                io { client.deleteBranch(org, r.repo, branch) }
                io { store.setBranchDeleted(r.id) }
            } catch (e: Exception) {
                failed += "${r.repo}: ${e.message}"
            }
            owner?.let { o -> io { runCatching { workspaces.forgetBranch(o.username, r.repo, branch) } } }
        }
        for (row in r.repos) {
            if (!row.branchCreated || row.branchDeleted || row.repo in keep) continue
            try {
                io { client.deleteBranch(org, row.repo, branch) }
                io { store.setBranchDeleted(r.id, row.repo) }
            } catch (e: Exception) {
                failed += "${row.repo}: ${e.message}"
            }
            owner?.let { o -> io { runCatching { workspaces.forgetMemberBranch(o.username, r.repo, row.directory, branch) } } }
        }
        return failed
    }

    /**
     * Cancel of a release of several repositories (the status is already `cancelled`): one comment on each open release
     * pull request, then `release/<name>` is deleted in every repository of the release, so a workspace branch with
     * pointer commits never stays mergeable once the member branches are gone. Failures become the notice.
     */
    private suspend fun cancelMultiLocked(id: UUID, by: UserRecord) {
        val r = current(id)
        val owner = io { ownerOf(r) }
        val client = owner?.let { access.client(it) }
        val branch = r.branch ?: "release/${r.name}"
        if (client == null) {
            setNotice(id, "Cancelled, but the owner has no GitHub token: `$branch` was not deleted in any repository.")
            return
        }
        val text = "**Agern:** release `${r.name}` was cancelled in Agern by @${by.githubLogin ?: by.username}. Agern will not merge " +
            "this pull request. The branch `$branch` is deleted in every repository of the release, which closes this pull " +
            "request; the issues' own pull requests remain as the record of what was built."
        for (row in r.repos) {
            val pr = row.prNumber ?: continue
            if (!row.merged) bestEffort("comment on the cancelled release's pull request") { io { client.comment(org, row.repo, pr, text) } }
        }
        r.prNumber?.takeIf { r.prMergedAt == null }?.let { pr ->
            bestEffort("comment on the cancelled release's pull request") { io { client.comment(org, r.repo, pr, text) } }
        }
        val failed = deleteReleaseBranches(r, client, owner)
        val notice = listOfNotNull(
            mergedPart(r),
            failed.takeIf { it.isNotEmpty() }?.joinToString("; ", "Cancelled, but could not delete `$branch` in "),
        ).joinToString(" ").takeIf { it.isNotEmpty() }
        setNotice(id, notice)
    }

    /**
     * Before a cancelled release of several repositories is deleted: every `release/<name>` its cancel could not delete
     * is tried once more, with the owner's token. Best effort, logged.
     */
    suspend fun retryBranchDeletion(id: UUID) {
        locks.of(id).withLock {
            val r = io { store.get(id) } ?: return@withLock
            if (r.status != ReleaseStatus.CANCELLED || !r.multiRepo) return@withLock
            val owner = io { ownerOf(r) }
            val client = owner?.let { access.client(it) } ?: return@withLock
            deleteReleaseBranches(r, client, owner).forEach { log.warn("Release {}: could not delete its branch in {}", id, it) }
        }
    }

    // ------------------------------------------------------------ after the merge

    /** `ready` → `released` (once), then [cleanupLocked]. */
    private suspend fun finishLocked(id: UUID, method: String?, sha: String?, by: UserRecord?, automatic: Boolean = false) {
        if (!io { store.markReleased(id, method, sha, by?.id, automatic) }) return
        log.info(
            "Release {} was merged into the default branch{}", id,
            when {
                by == null -> " on GitHub"
                automatic -> " automatically for ${by.username}"
                else -> " by ${by.username}"
            },
        )
        cleanupLocked(id)
    }

    /**
     * Closes every merged issue that is still open, with a comment naming the release and the commit, deletes
     * `release/<name>` on GitHub and forgets it in the owner's clone. Best effort and idempotent (it runs again after a
     * restart while [ReleaseRecord.cleanupPending]); what failed is kept as the release's notice.
     */
    private suspend fun cleanupLocked(id: UUID) {
        val r = current(id)
        if (r.status != ReleaseStatus.RELEASED) return
        val owner = io { ownerOf(r) }
        val client = owner?.let { access.client(it) }
        if (client == null) {
            setNotice(id, "Released, but the owner has no GitHub token: the issues were not closed and the branch was not deleted.")
            return
        }
        if (r.multiRepo) {
            cleanupMultiLocked(r, owner, client)
            return
        }
        val failed = mutableListOf<String>()
        val target = r.targetBranch ?: "the default branch"
        val how = howMerged(r.mergeMethod, target)
        val commit = r.mergeSha?.let { " (`$target` at $it)" } ?: ""
        val approval = approvalText(r)
        for (i in r.merged) {
            try {
                io {
                    val gh = client.getIssue(org, r.repo, i.number)
                    if (gh != null && gh.state == "open") {
                        client.comment(
                            org, r.repo, i.number,
                            "**Agern: released** in release `${r.name}`: pull request ${r.prUrl ?: "#${r.prNumber}"} was $how$commit.$approval",
                        )
                        client.closeIssue(org, r.repo, i.number)
                    }
                }
            } catch (e: Exception) {
                failed += "could not close #${i.number}: ${e.message}"
            }
        }
        val branch = r.branch
        if (branch != null && branch.startsWith("release/") && branch == "release/${r.name}") {
            try {
                io { client.deleteBranch(org, r.repo, branch) }
            } catch (e: Exception) {
                failed += "could not delete the branch $branch: ${e.message}"
            }
            io { runCatching { workspaces.forgetBranch(owner.username, r.repo, branch) } }
        }
        io { store.finishCleanup(id, failed.takeIf { it.isNotEmpty() }?.joinToString("; ", "Released, but ")?.take(2000)) }
    }

    /** " Approved by @x.", " Released automatically …" or " It was merged on GitHub." for the closing comments. */
    private fun approvalText(r: ReleaseRecord): String {
        val releaser = r.releasedByUsername?.let { u -> r.releasedById?.let { users.byId(it) }?.githubLogin ?: u }
        return when {
            releaser == null -> " It was merged on GitHub."
            r.releasedAutomatically -> " Released automatically by Agern for @$releaser (the release was set to automatic)."
            else -> " Approved by @$releaser."
        }
    }

    /** How a release pull request was merged into [target], for the closing comments. */
    private fun howMerged(method: String?, target: String) = when (method) {
        ReleaseMergeMethod.REBASE -> "rebased onto `$target`, one commit per issue"
        ReleaseMergeMethod.MERGE -> "merged into `$target` with a merge commit, one commit per issue"
        else -> "merged into `$target`"
    }

    private inline fun bestEffort(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.warn("Release: could not {}: {}", what, e.message)
        }
    }

    companion object {
        /** How the notice of a release that stopped partway starts. */
        private const val PARTIAL_NOTICE_START = "Releasing `"

        /** 409 `release_partially_merged`: adding or retrying an issue while a release is partly released. */
        fun partlyMerged(name: String) =
            conflict("Release \"$name\" is partly merged; finish releasing it first.", "release_partially_merged")

        fun statusLabel(status: String) = when (status) {
            ReleaseStatus.PLANNING -> "in preparation"
            ReleaseStatus.READY -> "ready to release"
            else -> status
        }
    }
}
