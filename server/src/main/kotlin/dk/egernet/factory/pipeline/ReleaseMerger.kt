package dk.egernet.factory.pipeline

import dk.egernet.factory.ApiException
import dk.egernet.factory.Config
import dk.egernet.factory.ReleaseMergeMethod
import dk.egernet.factory.ReleaseMergeState
import dk.egernet.factory.ReleaseStatus
import dk.egernet.factory.agent.EventBus
import dk.egernet.factory.agent.FeedItem
import dk.egernet.factory.agent.Workspaces
import dk.egernet.factory.conflict
import dk.egernet.factory.db.ReleaseRecord
import dk.egernet.factory.db.ReleaseStore
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.db.UserStore
import dk.egernet.factory.github.Access
import dk.egernet.factory.github.GhPullInfo
import dk.egernet.factory.github.GhRepo
import dk.egernet.factory.github.GitHubClient
import dk.egernet.factory.github.GitHubException
import dk.egernet.factory.notFound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
) {
    private val log = LoggerFactory.getLogger(ReleaseMerger::class.java)
    private val org get() = cfg.githubOrg

    /** Pause between reads while GitHub is still computing mergeability (tests shorten it). */
    @Volatile var mergeablePollMillis = 1000L

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
            locks.of(id).withLock { evaluateLocked(id, becameReady) }
        } catch (e: Exception) {
            log.warn("Release {} could not be evaluated: {}", id, e.message)
        }
        publish(id)
    }

    /** "Check again" by the owner ([owner] is the caller, already checked): [evaluate], answering the release. */
    suspend fun check(owner: UserRecord, id: UUID): ReleaseRecord {
        locks.of(id).withLock {
            val r = current(id)
            if (r.status != ReleaseStatus.READY) throw notReady(r)
            evaluateLocked(id, becameReady = false)
        }
        publish(id)
        return current(id)
    }

    /**
     * Merges a `ready`, complete, conflict-free release into the default branch as its [owner] (already checked to be
     * the owner): rebase (or a merge commit where rebase merging is disabled), never squash. Refusals change nothing
     * on GitHub. Serialised per release: a second approval finds the release `released` (409 `release_not_ready`).
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

    /** A `ready` (or building) release with a pull request was cancelled by [by]: one comment on that pull request. */
    suspend fun commentCancelled(id: UUID, by: UserRecord) {
        val r = io { store.get(id) } ?: return
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
    private fun pageUrl(r: ReleaseRecord) = "${cfg.publicUrl.trimEnd('/')}/p/${r.repo}/releases/${r.id}"

    private suspend fun setNotice(id: UUID, notice: String?) = io { store.setNotice(id, notice?.take(2000)) }

    private suspend fun evaluateLocked(id: UUID, becameReady: Boolean) {
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
        val repo = try {
            io { client.getRepo(org, r.repo) }
        } catch (e: GitHubException) {
            null
        } ?: run {
            setNotice(id, "Could not read ${r.repo} on GitHub with the owner's token; check again later.")
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
                setNotice(id, "Could not read pull request #$number: ${e.message}")
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
            setNotice(id, "Could not check pull request #$prNumber: ${e.message}")
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
        io { store.setMergeMethod(id, methodFor(repo)) }
        recordMergeState(withPr, state, client)
        setNotice(id, null)

        if (withPr.complete && state != ReleaseMergeState.CONFLICT && io { store.claimReadyAnnouncement(id) }) {
            bestEffort("comment on the release pull request") {
                io {
                    client.comment(
                        org, withPr.repo, prNumber,
                        "**Agern: release `${withPr.name}` is ready to release.** ${withPr.merged.size} issue(s) merged. " +
                            "@${login(owner)} can approve it on ${pageUrl(withPr)} or with `/factory release approve ${withPr.name}`.",
                    )
                }
            }
        }
    }

    /** Sets the merge state; on a change to `conflict`, comments once on the pull request. */
    private suspend fun recordMergeState(r: ReleaseRecord, state: String, client: GitHubClient) {
        if (!io { store.setMergeState(r.id, state) } || state != ReleaseMergeState.CONFLICT) return
        val pr = r.prNumber ?: current(r.id).prNumber ?: return
        val target = r.targetBranch ?: current(r.id).targetBranch ?: "the default branch"
        bestEffort("comment on the conflicting release pull request") {
            io {
                client.comment(
                    org, r.repo, pr,
                    "**Agern:** `${r.branch}` no longer merges cleanly into `$target`. Agern will not merge it. Resolve the " +
                        "conflicts on the release branch, then use \"Check again\" on the release page: ${pageUrl(r)}",
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
     * merge commit (which keeps them too, plus one merge commit) only where rebase merging is disabled; `unavailable`
     * when the repository allows neither. A setting GitHub leaves out counts as allowed.
     */
    private fun methodFor(repo: GhRepo): String = when {
        repo.allowRebaseMerge != false -> ReleaseMergeMethod.REBASE
        repo.allowMergeCommit != false -> ReleaseMergeMethod.MERGE
        else -> ReleaseMergeMethod.UNAVAILABLE
    }

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
            append(
                "\nApproved and merged from Agern by the release owner: ${pageUrl(r)}. " +
                    "Each issue stays one commit on `$target`.\n",
            )
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

        evaluateLocked(id, becameReady = false)
        val now = current(id)
        if (now.status == ReleaseStatus.RELEASED) return // it had been merged on GitHub; the check finished it
        if (now.status != ReleaseStatus.READY) throw notReady(now)
        val pr = now.prNumber ?: throw conflict(now.notice ?: "The release pull request could not be opened.", "release_merge_failed")
        val target = now.targetBranch ?: "the default branch"
        val conflictMsg = "`${now.branch}` no longer merges cleanly into `$target`. Nothing was merged."
        if (now.mergeState == ReleaseMergeState.CONFLICT) throw conflict(conflictMsg, "release_conflict")
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
            recordMergeState(now, ReleaseMergeState.CONFLICT, client)
            throw conflict(conflictMsg, "release_conflict")
        }
        val sha = try {
            io {
                client.mergePull(
                    org, now.repo, pr, method, if (method == ReleaseMergeMethod.MERGE) "Release ${now.name} (#$pr)" else null,
                    head?.head?.sha?.takeIf { it.isNotEmpty() },
                )
            }
        } catch (e: GitHubException) {
            throw mergeRefused(now, pr, method, e, client)
        }
        finishLocked(id, method, sha, owner)
    }

    private fun methodUnavailable(msg: String) = conflict(msg, "release_merge_method_unavailable")

    /** What a refused merge means: the method is not allowed, a conflict, the branch moved, or another refusal. */
    private suspend fun mergeRefused(r: ReleaseRecord, pr: Int, method: String, e: GitHubException, client: GitHubClient): ApiException {
        val msg = e.message ?: "GitHub refused the merge"
        val label = if (method == ReleaseMergeMethod.REBASE) "rebase merging" else "merge commits"
        if (e.status == 405 && msg.contains("not allowed", ignoreCase = true)) {
            // The repository forbids this method although its settings did not say so: never fall back to another one.
            val text = "GitHub does not allow $label in ${r.repo} ($msg). Allow rebase merging or merge commits in its GitHub " +
                "settings, then approve again. Nothing was merged."
            setNotice(r.id, text)
            io { store.setMergeMethod(r.id, null) }
            return methodUnavailable(text)
        }
        val after = runCatching { io { client.getPull(org, r.repo, pr) } }.getOrNull()
        if (after?.mergeable == false) {
            recordMergeState(r, ReleaseMergeState.CONFLICT, client)
            setNotice(r.id, "Merging pull request #$pr failed: $msg")
            return conflict("`${r.branch}` no longer merges cleanly into `${r.targetBranch}`. Nothing was merged.", "release_conflict")
        }
        val text = if (e.status == 409) "Merging pull request #$pr failed: the release branch changed since it was checked ($msg). Check again, then approve."
            else "Merging pull request #$pr failed: $msg"
        setNotice(r.id, text)
        return conflict(text, "release_merge_failed")
    }

    // ------------------------------------------------------------ after the merge

    /** `ready` → `released` (once), then [cleanupLocked]. */
    private suspend fun finishLocked(id: UUID, method: String?, sha: String?, by: UserRecord?) {
        if (!io { store.markReleased(id, method, sha, by?.id) }) return
        log.info("Release {} was merged into the default branch{}", id, if (by == null) " on GitHub" else " by ${by.username}")
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
        val failed = mutableListOf<String>()
        val target = r.targetBranch ?: "the default branch"
        val how = when (r.mergeMethod) {
            ReleaseMergeMethod.REBASE -> "rebased onto `$target`, one commit per issue"
            ReleaseMergeMethod.MERGE -> "merged into `$target` with a merge commit, one commit per issue"
            else -> "merged into `$target`"
        }
        val commit = r.mergeSha?.let { " (`$target` at $it)" } ?: ""
        val approval = r.releasedByUsername?.let { u -> " Approved by @${r.releasedById?.let { users.byId(it) }?.githubLogin ?: u}." } ?: " It was merged on GitHub."
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

    private inline fun bestEffort(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.warn("Release: could not {}: {}", what, e.message)
        }
    }

    companion object {
        fun statusLabel(status: String) = when (status) {
            ReleaseStatus.PLANNING -> "in preparation"
            ReleaseStatus.READY -> "ready to release"
            else -> status
        }
    }
}
