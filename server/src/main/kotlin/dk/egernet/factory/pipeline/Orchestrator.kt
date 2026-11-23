package dk.egernet.factory.pipeline

import dk.egernet.factory.ADVISOR_KIND
import dk.egernet.factory.AppJson
import dk.egernet.factory.ReviewState
import dk.egernet.factory.SessionStatus
import dk.egernet.factory.badRequest
import dk.egernet.factory.agent.AdvisorLaunch
import dk.egernet.factory.db.EventStore
import dk.egernet.factory.db.SessionRecord
import dk.egernet.factory.db.SessionStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dk.egernet.factory.Config
import dk.egernet.factory.FindingDto
import dk.egernet.factory.REVIEWER_ROLES
import dk.egernet.factory.SCREENSHOT_ROLES
import dk.egernet.factory.RunStatus
import dk.egernet.factory.agent.AgentIdentity
import dk.egernet.factory.agent.AgentManager
import dk.egernet.factory.agent.EventBus
import dk.egernet.factory.agent.FeedItem
import dk.egernet.factory.agent.Prompts
import dk.egernet.factory.agent.Screenshots
import dk.egernet.factory.agent.StageAgentRequest
import dk.egernet.factory.agent.StageAgentResult
import dk.egernet.factory.agent.UsageLimit
import dk.egernet.factory.agent.Workspaces
import dk.egernet.factory.conflict
import dk.egernet.factory.db.RunRecord
import dk.egernet.factory.db.RunStore
import dk.egernet.factory.db.ScreenshotStore
import dk.egernet.factory.db.SettingsStore
import dk.egernet.factory.db.StageRecord
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.db.UserStore
import dk.egernet.factory.forbidden
import dk.egernet.factory.github.Access
import dk.egernet.factory.github.GhIssue
import dk.egernet.factory.github.GitHubClient
import dk.egernet.factory.issueKindFromLabels
import dk.egernet.factory.notFound
import dk.egernet.factory.unprocessable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Verdict schemas handed to `--json-schema`. */
object Schemas {
    val REVIEW = """{"type":"object","additionalProperties":false,"properties":{""" +
        """"verdict":{"type":"string","enum":["PASS","CHANGES"]},""" +
        """"summary":{"type":"string"},""" +
        """"findings":{"type":"array","items":{"type":"object","additionalProperties":false,"properties":{""" +
        """"severity":{"type":"string","enum":["blocker","major","minor","nit"]},""" +
        """"file":{"type":["string","null"]},"detail":{"type":"string"}},"required":["severity","detail"]}}},""" +
        """"required":["verdict","summary","findings"]}"""
    val APPROVAL = """{"type":"object","additionalProperties":false,"properties":{""" +
        """"verdict":{"type":"string","enum":["APPROVE","ESCALATE"]},"summary":{"type":"string"}},""" +
        """"required":["verdict","summary"]}"""
}

/** A stage (or the run's set-up) failed; carries what the stage agent reported about usage limits and credentials. */
private class StageFailed(
    message: String,
    val usageLimit: UsageLimit? = null,
    val credentialRejected: Boolean = false,
) : RuntimeException(message)

/**
 * How a release run ended, for the release builder (docs/ARCHITECTURE.md §6 "Releases"): [run] as it is now,
 * and whether it failed on the owner's usage limit or a rejected (or missing) Claude credential.
 */
data class RunOutcome(val run: RunRecord, val usageLimit: UsageLimit? = null, val credentialRejected: Boolean = false)

data class Verdict(val verdict: String, val summary: String, val findings: List<FindingDto>)

/**
 * The pipeline as a deterministic state machine:
 * spec-writer → builder → 4 reviewers (parallel) → [CHANGES: builder fix round, max N] → approver → merge | needs_human.
 */
class Orchestrator(
    private val cfg: Config,
    private val runs: RunStore,
    private val users: UserStore,
    private val access: Access,
    private val agents: AgentManager,
    private val workspaces: Workspaces,
    private val settings: SettingsStore,
    private val prompts: Prompts,
    private val bus: EventBus,
    private val scope: CoroutineScope,
    private val preferences: dk.egernet.factory.db.PreferenceStore,
    private val sandboxes: dk.egernet.factory.sandbox.SandboxManager,
    private val screenshots: ScreenshotStore,
    private val sessions: SessionStore,
    private val events: EventStore,
    private val images: dk.egernet.factory.images.ImageService,
) {
    private val log = LoggerFactory.getLogger(Orchestrator::class.java)
    private val jobs = ConcurrentHashMap<UUID, Job>()
    /** Per run: serialises the conversation's commands (talk, review, approve's checks) against each other. */
    private val talkLocks = ConcurrentHashMap<UUID, Mutex>()
    /** Per run: serialises reconciles (after a turn and after an exit of the same process). */
    private val reconcileLocks = ConcurrentHashMap<UUID, Mutex>()
    /** Runs being cancelled or approved: no follow-up review may start for them meanwhile. */
    private val ending = ConcurrentHashMap.newKeySet<UUID>()
    private val org get() = cfg.githubOrg

    fun job(runId: UUID): Job? = jobs[runId]

    /**
     * Called when a release run settles: at the end of its pipeline (not when its coroutine is cancelled), after
     * `approve` and after `cancel`. The receiver must not block or call back into the orchestrator inline.
     */
    @Volatile var onReleaseRunSettled: ((RunOutcome) -> Unit)? = null

    private fun settled(runId: UUID, usageLimit: UsageLimit? = null, credentialRejected: Boolean = false) {
        val run = runs.get(runId) ?: return
        if (run.releaseId == null) return
        try {
            onReleaseRunSettled?.invoke(RunOutcome(run, usageLimit, credentialRejected))
        } catch (e: Exception) {
            log.warn("Release callback for run {} failed: {}", runId, e.message)
        }
    }

    fun publish(runId: UUID) {
        runs.touch(runId)
        runs.get(runId)?.let { bus.emitFeed(FeedItem.Run(runs.toDto(it))) }
    }

    // ------------------------------------------------------------ commands (ownership enforced here)

    /** Starts a run as [owner]. The owner's credentials are the only ones the run will ever use. */
    suspend fun start(owner: UserRecord, repo: String, issueNumber: Int, startedBySession: UUID?): RunRecord =
        create(owner, repo, issueNumber, startedBySession, releaseId = null, baseBranch = null)

    /**
     * Starts a release run as the release's [owner]: it branches from and merges into [baseBranch] (`release/<name>`)
     * and does not close its issue (docs/ARCHITECTURE.md §6 "Releases").
     */
    suspend fun startReleaseRun(owner: UserRecord, repo: String, issueNumber: Int, releaseId: UUID, baseBranch: String): RunRecord =
        create(owner, repo, issueNumber, null, releaseId, baseBranch)

    private suspend fun create(
        owner: UserRecord, repo: String, issueNumber: Int, startedBySession: UUID?, releaseId: UUID?, baseBranch: String?,
    ): RunRecord {
        if (owner.claudeCredEnc == null) throw unprocessable("Register a Claude credential in Settings first", "claude_credential_required")
        access.requireVisible(owner, repo)
        val client = access.requireClient(owner)
        val issue = withContext(Dispatchers.IO) { client.getIssue(org, repo, issueNumber) } ?: throw notFound("Issue #$issueNumber not found")
        // The starting user's own choices (else the admin defaults), fixed for this run: max rounds now, models per stage.
        val eff = preferences.effective(owner.id)
        val run = runs.createIfNoActive(
            repo, issueNumber, issue.title, issueKindFromLabels(issue.labels.map { it.name }), eff.maxFixRounds, owner.id, startedBySession,
            releaseId, baseBranch,
        ) ?: throw conflict("Issue #$issueNumber already has an active run", "active_run_exists")
        for (role in listOf("spec-writer", "builder") + REVIEWER_ROLES + "approver") runs.createStage(run.id, role, 0, eff.model(role))
        publish(run.id)
        val job = scope.launch { execute(run.id) }
        jobs[run.id] = job
        job.invokeOnCompletion { jobs.remove(run.id, job) }
        return runs.get(run.id)!!
    }

    /** Owner only, only in needs_human: squash-merge the PR and close the issue. */
    suspend fun approve(user: UserRecord, runId: UUID): RunRecord {
        val run = runs.get(runId) ?: throw notFound("Run not found")
        if (run.ownerId != user.id) throw forbidden("Only the owner of this run can approve it")
        if (run.status != RunStatus.NEEDS_HUMAN) throw conflict("Run is ${run.status}, not needs_human")
        if (run.prNumber == null) throw conflict("Run has no pull request to merge")
        // Changes made in the conversation are reviewed before the run can be approved (docs/ARCHITECTURE.md §6
        // "Conversation at needs human"): enforced here, from server state, for every way to approve.
        talkLocks.computeIfAbsent(runId) { Mutex() }.withLock {
            val r = runs.get(runId)!!
            if (r.status != RunStatus.NEEDS_HUMAN) throw conflict("Run is ${r.status}, not needs_human")
            if (r.reviewState == ReviewState.RUNNING) {
                throw conflict("The changes from the conversation are being reviewed; approve when the reviews are in", "review_in_progress")
            }
            r.advisorSessionId?.let { if (agents.isAdvisorBusy(it)) throw agentBusy() }
            if (r.reviewState == ReviewState.REQUIRED) throw reviewRequired()
            if (r.reviewedSha != null && headOf(r).let { it != null && it != r.reviewedSha }) {
                // A commit the review has not seen (e.g. a restart in the middle of a turn): it must be reviewed first.
                runs.casReviewState(runId, ReviewState.NONE, ReviewState.REQUIRED)
                publish(runId)
                throw reviewRequired()
            }
            ending += runId
            try {
                r.advisorSessionId?.let { agents.stopAdvisor(it, ended = false) }
                runs.setStatus(runId, RunStatus.APPROVING, clearError = true)
            } finally {
                ending -= runId
            }
        }
        publish(runId)
        withContext(Dispatchers.IO) { merge(runs.get(runId)!!, user, approvedBy = user.username) }
        runs.get(runId)?.takeIf { it.status == RunStatus.MERGED }?.advisorSessionId?.let { agents.endAdvisor(it) }
        publish(runId)
        settled(runId)
        return runs.get(runId)!!
    }

    /** Owner or admin. */
    suspend fun cancel(user: UserRecord, runId: UUID): RunRecord {
        val run = runs.get(runId) ?: throw notFound("Run not found")
        if (run.ownerId != user.id && !user.isAdmin) throw forbidden("Only the owner or an admin can cancel this run")
        if (run.status in RunStatus.TERMINAL) throw conflict("Run is already ${run.status}")
        ending += runId
        try {
            // The pipeline or a follow-up review, then the advisor (mid-turn included): nothing works in the worktree after this.
            jobs[runId]?.let { it.cancel(); it.join() }
            runs.get(runId)?.advisorSessionId?.let { agents.stopAdvisor(it, ended = true) }
            sandboxes.release(runId)
            val after = runs.get(runId)!!
            if (after.status in RunStatus.TERMINAL) return after
            withContext(Dispatchers.IO) { cleanupWorktree(runId, merged = false) }
            runs.setStatus(runId, RunStatus.CANCELLED, "Cancelled by ${user.username}")
        } finally {
            ending -= runId
        }
        runs.closeOpenStages(runId)
        val owner = users.byId(run.ownerId)!!
        withContext(Dispatchers.IO) {
            bestEffort { gh(owner).removeLabel(org, run.repo, run.issueNumber, "needs-human") }
            commentEnded(runId, "**Agern:** run `${run.id}` was cancelled by @${user.username}.")
        }
        publish(runId)
        settled(runId)
        return runs.get(runId)!!
    }

    // ------------------------------------------------------------ conversation at needs human (docs/ARCHITECTURE.md §6)

    private fun agentBusy() = conflict("The agent is still answering; wait for it or interrupt it", "agent_busy")
    private fun reviewRequired() =
        conflict("Changes from the conversation have not been reviewed yet; review them again before approving", "review_required")
    private fun reviewInProgress() =
        conflict("The changes from the conversation are being reviewed; continue when the reviews are in", "review_in_progress")

    private fun requireWaiting(run: RunRecord) {
        if (run.status != RunStatus.NEEDS_HUMAN) {
            throw conflict("Run is ${run.status}; the agent can only be talked to while the run needs a human", "run_not_waiting")
        }
    }

    private fun worktreeOf(run: RunRecord): Path = workspaces.runDir(run.ownerUsername, run.repo, run.id)

    /** The worktree's HEAD, or null when it cannot be read (e.g. the worktree is gone). Blocking-safe. */
    private suspend fun headOf(run: RunRecord): String? = withContext(Dispatchers.IO) {
        val wt = worktreeOf(run)
        if (!java.nio.file.Files.isDirectory(wt)) null
        else runCatching { workspaces.git.head(wt, workspaces.home(run.ownerUsername)) }
            .onFailure { log.warn("Could not read HEAD of run {}: {}", run.id, it.message) }.getOrNull()
    }

    /**
     * The owner's message for the run's advisor. Owner only (admins included in the refusal), only at `needs_human`,
     * one message at a time. The first message creates the run's advisor session. Returns its id.
     */
    suspend fun talk(user: UserRecord, runId: UUID, text: String): UUID {
        val run = runs.get(runId) ?: throw notFound("Run not found")
        if (run.ownerId != user.id) throw forbidden("Only the owner of this run can talk to its agent")
        requireWaiting(run)
        if (text.isBlank()) throw badRequest("text must not be empty")
        if (text.length > 100_000) throw badRequest("text is too long")
        return talkLocks.computeIfAbsent(runId) { Mutex() }.withLock {
            val r = runs.get(runId) ?: throw notFound("Run not found")
            requireWaiting(r)
            if (r.reviewState == ReviewState.RUNNING) throw reviewInProgress()
            r.advisorSessionId?.let { if (agents.isAdvisorBusy(it)) throw agentBusy() }
            if (user.claudeCredEnc == null) throw unprocessable("Register a Claude credential in Settings first", "claude_credential_required")
            if (r.advisorSessionId?.let { agents.isLive(it) } != true && !agents.hasFreeSlot(user)) {
                throw conflict("You already have ${cfg.maxAgentsPerUser} agents running", "too_many_agents")
            }
            val worktree = worktreeOf(r)
            if (!withContext(Dispatchers.IO) { java.nio.file.Files.isDirectory(worktree) }) {
                throw conflict("The run's worktree is gone, so the agent cannot work on it; start a new run instead", "run_worktree_missing")
            }
            val sessionId = r.advisorSessionId ?: createAdvisor(r, user)
            agents.sendToAdvisor(user, sessions.get(sessionId) ?: throw notFound("Agent session not found"), text)
            sessionId
        }
    }

    /** Creates the run's advisor session (called under the run's talk lock) and records the reviewed commit. */
    private suspend fun createAdvisor(run: RunRecord, owner: UserRecord): UUID {
        val head = headOf(run)
        val model = preferences.effective(owner.id).model("builder")
        val s = sessions.create(
            run.repo, ADVISOR_KIND, model, SessionStatus.IDLE, owner.id, "advisor · #${run.issueNumber}", run.id, null, run.issueNumber,
        )
        val bound = runs.bindAdvisor(run.id, s.id, head) ?: s.id
        agents.publishFeed(bound)
        publish(run.id)
        return bound
    }

    /** "Review again": owner only, at `needs_human`, when changes from the conversation have not been reviewed. */
    suspend fun review(user: UserRecord, runId: UUID): RunRecord {
        val run = runs.get(runId) ?: throw notFound("Run not found")
        if (run.ownerId != user.id) throw forbidden("Only the owner of this run can have its changes reviewed")
        requireWaiting(run)
        talkLocks.computeIfAbsent(runId) { Mutex() }.withLock {
            reconcileLocks.computeIfAbsent(runId) { Mutex() }.withLock {
                val r = runs.get(runId) ?: throw notFound("Run not found")
                requireWaiting(r)
                if (r.reviewState == ReviewState.RUNNING) throw reviewInProgress()
                r.advisorSessionId?.let { if (agents.isAdvisorBusy(it)) throw agentBusy() }
                if (user.claudeCredEnc == null) throw unprocessable("Register a Claude credential in Settings first", "claude_credential_required")
                val head = headOf(r)
                if (r.advisorSessionId == null || r.reviewedSha == null || (r.reviewState == ReviewState.NONE && head == r.reviewedSha)) {
                    throw conflict("There are no unreviewed changes from the conversation", "nothing_to_review")
                }
                if (!runs.casReviewState(runId, r.reviewState, ReviewState.RUNNING)) throw reviewInProgress()
                launchFollowUp(runId)
            }
        }
        publish(runId)
        return runs.get(runId)!!
    }

    /**
     * After every advisor turn and exit: a `needs_human` run whose worktree HEAD moved past the reviewed commit needs a
     * follow-up review, which starts at once. Idempotent; a no-op while a review runs or the run is ending.
     */
    suspend fun reconcile(runId: UUID) {
        reconcileLocks.computeIfAbsent(runId) { Mutex() }.withLock {
            val run = runs.get(runId) ?: return
            if (run.status != RunStatus.NEEDS_HUMAN || runId in ending || run.reviewedSha == null) return
            if (run.reviewState == ReviewState.RUNNING) return
            val head = headOf(run) ?: return
            if (head == run.reviewedSha) return
            if (!runs.casReviewState(runId, run.reviewState, ReviewState.RUNNING)) return
            launchFollowUp(runId)
        }
        publish(runId)
    }

    /** Starts the follow-up review job, registered in [jobs] so that cancel stops it. */
    private fun launchFollowUp(runId: UUID) {
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { followUp(runId) }
        jobs[runId] = job
        job.invokeOnCompletion { jobs.remove(runId, job) }
        job.start()
    }

    /**
     * The follow-up review of changes made in the conversation: push, then the four reviewers in a new round, exactly
     * as in the pipeline. Never fails the run, never merges, and leaves the worktree; a failure leaves the review
     * `required` for "Review again".
     */
    private suspend fun followUp(runId: UUID) {
        val start = runs.get(runId) ?: return
        val advisor = start.advisorSessionId
        try {
            publish(runId)
            // The advisor must not commit while the reviewers read the tree; it resumes on the owner's next message.
            advisor?.let { agents.stopAdvisor(it, ended = false) }
            val owner = owner(start)
            val id = identity(owner)
            val client = gh(owner)
            val ctx = runContext(start, owner)
            val home = workspaces.home(owner.username)
            val head = withContext(Dispatchers.IO) { workspaces.git.head(ctx.worktree, home) }
            withContext(Dispatchers.IO) { workspaces.git.push(id, ctx.worktree, ctx.branch, home) }
            val round = (runs.stages(runId).maxOfOrNull { it.round } ?: 0) + 1
            val eff = preferences.effective(owner.id)
            REVIEWER_ROLES.forEach { runs.createStage(runId, it, round, eff.model(it)) }
            runs.startFollowUp(runId, round)
            publish(runId)
            val n = round - (runs.get(runId)!!.followUpFrom ?: round) + 1
            val spec = specText(runId).orEmpty()
            val summary = advisor?.let { events.lastResultText(it) }?.trim().orEmpty()
            val reviews = coroutineScope {
                REVIEWER_ROLES.map { role ->
                    async {
                        val r = stage(runId, role, round, owner, ctx, reviewPrompt(ctx, role, round, spec, summary, followUp = n), schema = Schemas.REVIEW,
                            disallowed = READ_ONLY_TOOLS, extraTools = if (role in SCREENSHOT_ROLES) Screenshots.TOOLS else emptySet())
                        val v = parseVerdict(r, setOf("PASS", "CHANGES"))
                        if (v == null) {
                            finishStage(runId, role, round, null, "No valid verdict in the agent's output", emptyList(), failed = true)
                            null
                        } else {
                            finishStage(runId, role, round, v.verdict, v.summary, v.findings)
                            role to v
                        }
                    }
                }.awaitAll()
            }.let { list ->
                if (list.any { it == null }) throw StageFailed("A reviewer did not return a valid verdict")
                list.filterNotNull().toMap()
            }
            withContext(Dispatchers.IO) { bestEffort { screenshotNote(runs.get(runId)!!, client, round) } }
            val sha7 = head.take(7)
            val changes = REVIEWER_ROLES.filter { reviews[it]?.verdict == "CHANGES" }
            val verdicts = REVIEWER_ROLES.joinToString(", ") { "$it ${reviews.getValue(it).verdict}" }
            runs.setReviewedSha(runId, head)
            val reason = originalReason(runs.get(runId)!!.error)
            val outcome = if (changes.isEmpty()) "all four reviewers pass." else "${changes.joinToString(", ")} still request changes."
            runs.setError(runId, (if (reason.isEmpty()) "" else "$reason\n\n") + "**Follow-up review $n** (commit `$sha7`): $outcome")
            advisor?.let { agents.systemEvent(it, "info", "Follow-up review $n of commit $sha7: $verdicts.") }
            // Last: `none` tells clients (and approve) that the review and everything it records is complete.
            runs.setReviewState(runId, ReviewState.NONE)
            publish(runId)
            val pr = runs.get(runId)?.prNumber
            if (pr != null) withContext(Dispatchers.IO) {
                bestEffort {
                    client.comment(org, start.repo, pr,
                        "**Agern:** changes requested by the run owner in a conversation were pushed ($sha7) and reviewed again: $verdicts.")
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runs.casReviewState(runId, ReviewState.RUNNING, ReviewState.REQUIRED) }
            throw e
        } catch (e: Exception) {
            log.warn("Follow-up review of run {} failed: {}", runId, e.message)
            runs.closeOpenStages(runId)
            runs.casReviewState(runId, ReviewState.RUNNING, ReviewState.REQUIRED)
            val why = e.message ?: e.javaClass.simpleName
            advisor?.let {
                agents.systemEvent(
                    it, "error",
                    "The follow-up review of the changes did not complete: $why. The run still needs a human; use \"Review again\" " +
                        "on the run page to review the changes before approving.",
                )
            }
            publish(runId)
        }
    }

    /** The needs-human reason without the follow-up paragraphs added after it. */
    private fun originalReason(error: String?): String = (error ?: "").substringBefore("\n\n**Follow-up review ").trim()

    /** The final result text of the run's spec-writer. */
    private fun specText(runId: UUID): String? =
        runs.stages(runId).firstOrNull { it.role == "spec-writer" }?.sessionId?.let { events.lastResultText(it) }

    /** Launch data for the run's advisor, rebuilt from current data (AgentManager.advisorLaunch). */
    suspend fun advisorLaunch(session: SessionRecord, owner: UserRecord): AdvisorLaunch {
        val run = session.runId?.let { runs.get(it) } ?: throw notFound("Run not found")
        requireWaiting(run)
        val ctx = runContext(run, owner)
        if (!withContext(Dispatchers.IO) { java.nio.file.Files.isDirectory(ctx.worktree) }) {
            throw conflict("The run's worktree is gone, so the agent cannot work on it", "run_worktree_missing")
        }
        return AdvisorLaunch(ctx.worktree, run.id, advisorContext(runs.get(run.id)!!, ctx))
    }

    /**
     * The run's [RunContext], rebuilt from the run record, GitHub (issue, comments, default branch) and the stored
     * sandbox decision. When GitHub cannot be read, the stored title stands in for the issue.
     */
    private suspend fun runContext(run: RunRecord, owner: UserRecord): RunContext = withContext(Dispatchers.IO) {
        val client = access.client(owner)
        val repoInfo = runCatching { client?.getRepo(org, run.repo) }.getOrNull()
        val issue = runCatching { client?.getIssue(org, run.repo, run.issueNumber) }.getOrNull()
        val comments = if (issue == null) emptyList() else runCatching { client?.listComments(org, run.repo, run.issueNumber) }.getOrNull().orEmpty()
        val text = if (issue != null) issueText(issue, comments)
            else "Issue #${run.issueNumber}: ${run.issueTitle}\n(The issue body is unavailable: GitHub could not be read.)\n"
        val defaultBranch = repoInfo?.defaultBranch ?: "main"
        val target = run.baseBranch ?: defaultBranch
        val releaseName = run.baseBranch?.takeIf { run.releaseId != null }?.removePrefix("release/")
        RunContext(
            run.id, run.repo,
            issue ?: GhIssue(run.issueNumber, run.issueTitle, "open", createdAt = "", updatedAt = "", htmlUrl = ""),
            text, run.branch ?: "", workspaces.git.baseRef(target), target, worktreeOf(run), releaseName, defaultBranch,
            run.sandboxJson?.let { runCatching { AppJson.decodeFromString(dk.egernet.factory.RunSandboxDto.serializer(), it) }.getOrNull() },
        )
    }

    /** Human name of a stage round: build, fix round N or follow-up N. */
    private fun roundName(run: RunRecord, round: Int): String {
        val from = run.followUpFrom
        return when {
            from != null && round >= from -> "follow-up ${round - from + 1}"
            round == 0 -> "build"
            else -> "fix round $round"
        }
    }

    /** The `<run_context>` block an advisor's first message carries. Untrusted parts are data for the agent. */
    private fun advisorContext(run: RunRecord, c: RunContext): String = buildString {
        val stages = runs.stages(run.id)
        append("<run_context>\n")
        append("Run `${run.id}` in ${org}/${c.repo} for issue #${run.issueNumber}. Status: needs a human.\n")
        append("Needs a human because: ${run.error?.trim().orEmpty().ifEmpty { "(no reason recorded)" }}\n")
        append("Branch `${c.branch}`, created from `${c.baseRef}`; pull request ${run.prUrl ?: "(none)"}; it merges into `${c.targetBranch}`.\n")
        append(c.releaseNote)
        append("Inspect the change with `git diff ${c.baseRef}...HEAD` and `git log ${c.baseRef}..HEAD`, and by reading the code.\n")
        append("This run has no sandbox at this point: build and run checks on the host.\n\n")
        append(c.issueText)
        append("\n<spec>\n").append(specText(run.id)?.trim().orEmpty().ifEmpty { "(none)" }).append("\n</spec>\n")
        val builder = stages.filter { it.role == "builder" && it.sessionId != null }.maxByOrNull { it.round }
        val summary = builder?.sessionId?.let { events.lastResultText(it) }?.trim().orEmpty()
        append("\n<builder_summary>\n").append(summary.ifEmpty { "(none)" }).append("\n</builder_summary>\n")
        append("\n<reviews>\n")
        for (role in REVIEWER_ROLES) {
            val st = stages.filter { it.role == role && it.verdict != null }.maxByOrNull { it.round }
            if (st == null) {
                append("### $role — no verdict\n\n")
                continue
            }
            append("### $role — ${roundName(run, st.round)} — ${st.verdict}\n${st.summary.orEmpty()}\n")
            st.findings.forEach { f -> append("- [${f.severity}] ${f.file ?: "(general)"}: ${f.detail}\n") }
            append('\n')
        }
        append("</reviews>\n")
        val approver = stages.lastOrNull { it.role == "approver" }
        append("\n<approver>\n")
        if (approver?.verdict != null) append("${approver.verdict}: ${approver.summary.orEmpty()}\n")
        else append("The approver did not run (${approver?.status ?: "no stage"}).\n")
        append("</approver>\n")
        append("</run_context>")
    }

    // ------------------------------------------------------------ the state machine

    private suspend fun execute(runId: UUID) {
        try {
            run(runId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Run {} failed: {}", runId, e.message)
            val reason = e.message ?: e.javaClass.simpleName
            // Clean up first, so a run shown as ended has left nothing behind: the sandbox (it bind-mounts the worktree),
            // then the worktree.
            withContext(NonCancellable) { sandboxes.release(runId) }
            withContext(Dispatchers.IO) { cleanupWorktree(runId, merged = false) }
            fail(runId, reason)
            withContext(Dispatchers.IO) { commentEnded(runId, "**Agern:** run `$runId` failed: $reason") }
            val sf = e as? StageFailed
            settled(runId, sf?.usageLimit, sf?.credentialRejected == true)
            return
        } finally {
            // Safety net; normally released already (end of the build/review loop, failure, cancel) — and always before
            // settled(), so the next run of a release finds the slot free.
            withContext(NonCancellable) { sandboxes.release(runId) }
        }
        settled(runId)
    }

    private fun fail(runId: UUID, reason: String) {
        runs.setStatus(runId, RunStatus.FAILED, reason)
        runs.closeOpenStages(runId)
        publish(runId)
    }

    /**
     * A run ended failed or cancelled: comment on the issue, naming the pull request it leaves behind. Its pushed branch
     * and open pull request stay on GitHub untouched — they may hold useful commits — and a new run never reuses them
     * (docs/ARCHITECTURE.md §6 "Worktrees and branches"). Blocking.
     */
    private fun commentEnded(runId: UUID, headline: String) {
        val run = runs.get(runId) ?: return
        val owner = users.byId(run.ownerId) ?: return
        val left = if (run.prUrl == null) "" else "\n\nIts pull request ${run.prUrl} (branch `${run.branch}`) is left open as it is; " +
            "a new run of this issue starts on a new branch with a new pull request."
        bestEffort { gh(owner).comment(org, run.repo, run.issueNumber, headline + left) }
    }

    /** Live = has a coroutine in this server or an active status (a `needs_human` run still owns its worktree). */
    private fun isLive(runId: UUID): Boolean = jobs.containsKey(runId) || runs.get(runId)?.status in RunStatus.ACTIVE

    /**
     * The worktree reapers' test: a run that is not live keeps nothing — and its sandbox (normally long gone) is removed
     * here, before the reaper deletes the worktree the sandbox would bind-mount.
     */
    private fun keepWorktree(runId: UUID): Boolean = isLive(runId).also { live -> if (!live) sandboxes.releaseNow(runId) }

    /** At server start: removes the worktrees of runs that have ended (and prunes stale worktree entries). Blocking. */
    fun reapWorktrees(): List<UUID> = workspaces.reapAllRunWorktrees(::keepWorktree)

    private fun owner(run: RunRecord) = users.byId(run.ownerId) ?: error("Run owner no longer exists")
    private fun gh(owner: UserRecord): GitHubClient =
        access.client(owner) ?: throw StageFailed("The run owner has no GitHub token")

    private fun identity(owner: UserRecord) = AgentIdentity(
        owner.username, owner.displayName, owner.githubLogin, access.githubToken(owner),
        access.claudeCredential(owner) ?: throw StageFailed("The run owner has no Claude credential", credentialRejected = true),
    )

    private fun setStatus(runId: UUID, status: String) {
        runs.setStatus(runId, status)
        publish(runId)
    }

    private suspend fun run(runId: UUID) {
        var run = runs.get(runId)!!
        val owner = owner(run)
        val id = identity(owner)
        val client = gh(owner)
        val maxRounds = run.maxRounds

        // Workspace: owner's clone + a worktree on the run branch.
        val repoInfo = withContext(Dispatchers.IO) { client.getRepo(org, run.repo) } ?: throw StageFailed("Repository not accessible")
        val issue = withContext(Dispatchers.IO) { client.getIssue(org, run.repo, run.issueNumber) } ?: throw StageFailed("Issue not found")
        val comments = withContext(Dispatchers.IO) { runCatching { client.listComments(org, run.repo, run.issueNumber) }.getOrDefault(emptyList()) }
        // A release run starts from and merges into its release branch; a single run uses the default branch.
        val target = run.baseBranch ?: repoInfo.defaultBranch
        val releaseName = run.baseBranch?.takeIf { run.releaseId != null }?.removePrefix("release/")
        val branch = runBranch(run.issueNumber, issue.title, releaseName, run.id)
        val worktree = workspaces.runDir(owner.username, run.repo, run.id)
        val baseRef = withContext(Dispatchers.IO) {
            val main = workspaces.ensureMain(id, run.repo)
            workspaces.withRepoLock(owner.username, run.repo) {
                // Leftovers of runs that have ended (e.g. after a crash) never block a new run; live runs keep theirs.
                workspaces.reapRunWorktrees(owner.username, run.repo) { it != run.id && keepWorktree(it) }
                workspaces.git.createWorktree(id, main, worktree, branch, target, workspaces.home(owner.username))
            }
        }
        runs.setBranch(run.id, branch)
        // The sandbox decision is made once, from the base commit (a run cannot approve its own change to the file). A
        // release run reads it from the default branch, as fetched with the clone, not from the release branch it starts
        // from: earlier runs of the release merge into that branch without a human, and approvals are of the default
        // branch's file (docs/ARCHITECTURE.md §5 "The run's decision"). baseRef is `origin/<target>` (or `<target>` for
        // the fake GitHub's local clones), so the same form names the default branch.
        val definitionRef = if (run.baseBranch == null || target == repoInfo.defaultBranch) baseRef
            else baseRef.removeSuffix(target) + repoInfo.defaultBranch
        val sandbox = sandboxes.prepare(run.id, run.repo, worktree, definitionRef, workspaces.home(owner.username))
        // Images selected for the issue in a chat go into the worktree (docs/ARCHITECTURE.md §6 "Generated images").
        val prepared = withContext(Dispatchers.IO) { images.handOver(runs.get(run.id)!!, worktree) }
        publish(run.id)
        val ctx = RunContext(
            run.id, run.repo, issue, issueText(issue, comments), branch, baseRef, target, worktree, releaseName, repoInfo.defaultBranch, sandbox,
            prepared,
        )
        val imageNotes = if (prepared.isEmpty()) emptyList()
            else listOf("info" to "Prepared images: ${prepared.size} (${prepared.joinToString(", ") { it.meta.name ?: it.meta.purpose }})")

        // 1. Spec
        setStatus(run.id, RunStatus.SPEC)
        val specRes = stage(run.id, "spec-writer", 0, owner, ctx, specPrompt(ctx),
            disallowed = READ_ONLY_TOOLS, extraTools = dk.egernet.factory.images.ImageService.TOOLS, notes = imageNotes)
        val spec = specRes.resultText?.trim().takeUnless { it.isNullOrEmpty() } ?: throw StageFailed("spec-writer produced no spec")
        finishStage(run.id, "spec-writer", 0, null, firstParagraph(spec), emptyList())
        withContext(Dispatchers.IO) {
            client.comment(org, run.repo, run.issueNumber, "**Agern: spec ready** (run `${run.id}`)\n\n$spec")
        }

        // 2. Build / review loop
        var round = 0
        var builderClaudeSession: String? = null
        var builderSessionId: UUID? = null
        var findingsForBuilder: Map<String, Verdict> = emptyMap()
        var lastReviews: Map<String, Verdict>
        while (true) {
            runs.setRound(run.id, round)
            setStatus(run.id, RunStatus.BUILDING)
            if (round > 0) {
                val eff = preferences.effective(owner.id)
                runs.createStage(run.id, "builder", round, eff.model("builder"))
                REVIEWER_ROLES.forEach { runs.createStage(run.id, it, round, eff.model(it)) }
                publish(run.id)
            }
            val prompt = if (round == 0) buildPrompt(ctx, spec) else fixPrompt(ctx, round, maxRounds, findingsForBuilder)
            val b = stage(run.id, "builder", round, owner, ctx, prompt,
                resume = builderClaudeSession, parent = builderSessionId,
                extraTools = (if (sandboxes.enabled) dk.egernet.factory.sandbox.SandboxManager.TOOLS else emptySet()) +
                    dk.egernet.factory.images.ImageService.TOOLS,
                notes = (ctx.sandbox?.takeIf { sandboxes.enabled }?.let { listOf((if (it.state == "approved") "info" else "warn") to "Sandbox: ${it.reason}") }
                    ?: emptyList()) + (if (round == 0) imageNotes else emptyList()))
            builderClaudeSession = b.claudeSessionId ?: builderClaudeSession
            builderSessionId = b.session.id
            val builderSummary = b.resultText?.trim() ?: ""
            val ahead = withContext(Dispatchers.IO) { workspaces.git.commitsAhead(worktree, baseRef, workspaces.home(owner.username)) }
            if (ahead == 0) {
                // The builder's own words say why (e.g. the issue is blocked on another one); show them with the failure.
                val why = firstParagraph(builderSummary).takeIf { it.isNotEmpty() }
                finishStage(run.id, "builder", round, null, why?.let { "No commits on the branch. $it" } ?: "No commits on the branch", emptyList(), failed = true)
                throw StageFailed("The builder made no commits on $branch" + (why?.let { ". The builder said: $it" } ?: ""))
            }
            finishStage(run.id, "builder", round, null, firstParagraph(builderSummary), emptyList())

            withContext(Dispatchers.IO) {
                workspaces.git.push(id, worktree, branch, workspaces.home(owner.username))
                run = runs.get(run.id)!!
                if (run.prNumber == null) {
                    // A release run's pull request must not close the issue: that happens when the release is merged.
                    val lead = if (releaseName != null) "Issue #${issue.number} · release `$releaseName`" else "Closes #${issue.number}"
                    val earlier = runs.earlierUnmergedPulls(run.repo, run.issueNumber, run.id)
                        .takeIf { it.isNotEmpty() }
                        ?.let { prs -> "Earlier runs of this issue left " + prs.joinToString(", ") { "#$it" } + " open; close them if this one supersedes them.\n\n" }
                        .orEmpty()
                    val pr = client.findOpenPull(org, run.repo, branch) ?: client.createPull(
                        org, run.repo, branch, target, "${issue.title} (#${issue.number})",
                        "$lead\n\nBuilt by Agern run `${run.id}` for @${owner.githubLogin ?: owner.username}.\n\n" + earlier +
                            "<details><summary>Spec</summary>\n\n$spec\n\n</details>",
                    )
                    runs.setPr(run.id, pr.number, pr.htmlUrl)
                    bestEffort { client.comment(org, run.repo, run.issueNumber, "**Agern:** pull request ${pr.htmlUrl} is open; reviews are running.") }
                }
            }
            publish(run.id)

            // Reviews in parallel
            setStatus(run.id, RunStatus.REVIEWING)
            lastReviews = coroutineScope {
                REVIEWER_ROLES.map { role ->
                    async {
                        val r = stage(run.id, role, round, owner, ctx, reviewPrompt(ctx, role, round, spec, builderSummary), schema = Schemas.REVIEW,
                            disallowed = READ_ONLY_TOOLS, extraTools = if (role in SCREENSHOT_ROLES) Screenshots.TOOLS else emptySet())
                        val v = parseVerdict(r, setOf("PASS", "CHANGES"))
                        if (v == null) {
                            finishStage(run.id, role, round, null, "No valid verdict in the agent's output", emptyList(), failed = true)
                            null
                        } else {
                            finishStage(run.id, role, round, v.verdict, v.summary, v.findings)
                            role to v
                        }
                    }
                }.awaitAll()
            }.let { list ->
                if (list.any { it == null }) throw StageFailed("A reviewer did not return a valid verdict")
                list.filterNotNull().toMap()
            }
            withContext(Dispatchers.IO) { bestEffort { screenshotNote(runs.get(run.id)!!, client, round) } }
            val changes = lastReviews.filterValues { it.verdict == "CHANGES" }
            if (changes.isEmpty()) break
            if (round >= maxRounds) {
                sandboxes.release(run.id)
                skipStage(run.id, "approver")
                needsHuman(
                    runs.get(run.id)!!, owner,
                    "Reviewers still request changes after $round fix round(s): " + changes.keys.joinToString(", ") + ".",
                )
                return
            }
            findingsForBuilder = changes
            round++
        }

        // Only the builder uses the sandbox: it is removed as soon as no fix round can follow.
        sandboxes.release(run.id)

        // 3. Approval
        setStatus(run.id, RunStatus.APPROVING)
        runs.setStageRound(run.id, "approver", round)
        run = runs.get(run.id)!!
        val a = stage(run.id, "approver", round, owner, ctx, approvalPrompt(ctx, spec, lastReviews, round, maxRounds, run.prUrl), schema = Schemas.APPROVAL, disallowed = READ_ONLY_TOOLS)
        val v = parseVerdict(a, setOf("APPROVE", "ESCALATE"))
        if (v == null) {
            finishStage(run.id, "approver", round, null, "No valid verdict in the agent's output", emptyList(), failed = true)
            throw StageFailed("The approver did not return a valid verdict")
        }
        finishStage(run.id, "approver", round, v.verdict, v.summary, emptyList())
        if (v.verdict == "APPROVE") {
            withContext(Dispatchers.IO) { merge(runs.get(run.id)!!, owner, approvedBy = null) }
            publish(run.id)
        } else {
            needsHuman(runs.get(run.id)!!, owner, "The approver escalated: ${v.summary}")
        }
    }

    /**
     * After a review round: one comment on the pull request listing the screenshots the reviewers of [round] attached,
     * pointing at the run page (docs/ARCHITECTURE.md §6 "Screenshots"). Nothing for a round without screenshots; the
     * images themselves never go to GitHub. Blocking.
     */
    private fun screenshotNote(run: RunRecord, client: GitHubClient, round: Int) {
        val pr = run.prNumber ?: return
        screenshotNoteText(cfg, run.repo, run.id, round, runs.stages(run.id), screenshots.forRun(run.id))
            ?.let { client.comment(org, run.repo, pr, it) }
    }

    /** [targetBranch]: where the pull request merges; [releaseName] is set for a release run. */
    private data class RunContext(
        val runId: UUID, val repo: String, val issue: GhIssue, val issueText: String, val branch: String,
        val baseRef: String, val targetBranch: String, val worktree: Path,
        val releaseName: String? = null, val defaultBranch: String = targetBranch,
        /** The run's sandbox decision; null when sandboxes are switched off. */
        val sandbox: dk.egernet.factory.RunSandboxDto? = null,
        /** Images selected for the issue in a chat, written into the worktree; empty for most runs. */
        val prepared: List<dk.egernet.factory.images.PreparedImage> = emptyList(),
    ) {
        /** The `<images>` block for the spec-writer's and builder's task prompts; empty (no change at all) without images. */
        val imagesBlock: String
            get() = if (prepared.isEmpty()) "" else dk.egernet.factory.images.ImageService.promptBlock(prepared)

        /** For release runs: the sentence that names the release branch as the merge target. */
        val releaseNote: String
            get() = if (releaseName == null) "" else
                "This issue is part of release `$releaseName`: the pull request is squash-merged into the release branch " +
                    "`$targetBranch` (not into `$defaultBranch`), and the issue stays open until the release is merged.\n"
    }

    /** Runs one stage's agent and marks the stage running/failed. The caller records the outcome on success. */
    private suspend fun stage(
        runId: UUID, role: String, round: Int, owner: UserRecord, ctx: RunContext, prompt: String,
        schema: String? = null, disallowed: List<String> = emptyList(),
        resume: String? = null, parent: UUID? = null,
        extraTools: Set<String> = emptySet(), notes: List<Pair<String, String>> = emptyList(),
    ): StageAgentResult {
        val st = stageRecord(runId, role, round)
        val s = settings.get()
        val res = agents.runStageAgent(
            StageAgentRequest(
                // The model recorded on the stage when it was created, so Stage.model is what actually ran.
                owner = owner, repo = ctx.repo, role = role, model = st.model, runId = runId,
                issueNumber = ctx.issue.number, parentSessionId = parent, workDir = ctx.worktree,
                systemPrompt = prompts.system(role, ctx.repo, s.agentsMayInstallSoftware), prompt = prompt,
                jsonSchema = schema, disallowedTools = disallowed, resumeClaudeSessionId = resume,
                title = "$role · #${ctx.issue.number}" + if (round > 0) " · round $round" else "",
                extraTools = extraTools, notes = notes,
            ),
        ) { session ->
            runs.startStage(st.id, session.id, session.model)
            publish(runId)
        }
        if (!res.ok) {
            runs.finishStage(st.id, "failed", null, res.error, emptyList())
            publish(runId)
            throw StageFailed("$role failed: ${res.error}", res.usageLimit, res.credentialRejected)
        }
        return res
    }

    private fun stageRecord(runId: UUID, role: String, round: Int): StageRecord =
        runs.stages(runId).lastOrNull { it.role == role && it.round == round }
            ?: runs.createStage(runId, role, round, preferences.effective(runs.get(runId)!!.ownerId).model(role))

    private fun finishStage(runId: UUID, role: String, round: Int, verdict: String?, summary: String?, findings: List<FindingDto>, failed: Boolean = false) {
        val st = stageRecord(runId, role, round)
        runs.finishStage(st.id, if (failed) "failed" else "done", verdict, summary, findings)
        publish(runId)
    }

    private fun skipStage(runId: UUID, role: String) {
        runs.stages(runId).filter { it.role == role && it.status == "pending" }.forEach {
            runs.finishStage(it.id, "skipped", null, null, emptyList())
        }
        publish(runId)
    }

    private fun needsHuman(run: RunRecord, owner: UserRecord, reason: String) {
        val client = gh(owner)
        bestEffort { client.addLabels(org, run.repo, run.issueNumber, listOf("needs-human")) }
        bestEffort {
            client.comment(
                org, run.repo, run.issueNumber,
                "**Agern: needs a human.** $reason\n\nPull request: ${run.prUrl ?: "(none)"}\n\n" +
                    "The run owner (@${owner.githubLogin ?: owner.username}) can merge it with `/factory approve ${run.id}` in the Agern chat or the Approve button on the run page.",
            )
        }
        runs.setStatus(run.id, RunStatus.NEEDS_HUMAN, reason)
        publish(run.id)
    }

    /**
     * Squash-merges the PR and closes the issue; a release run's issue stays open until the release is merged.
     * On failure the run goes (back) to needs_human.
     */
    private fun merge(run: RunRecord, owner: UserRecord, approvedBy: String?) {
        val client = gh(owner)
        val pr = run.prNumber ?: run { needsHuman(run, owner, "There is no pull request to merge."); return }
        try {
            client.squashMerge(org, run.repo, pr, "${run.issueTitle} (#${run.issueNumber})")
        } catch (e: Exception) {
            needsHuman(run, owner, "Merging pull request #$pr failed: ${e.message}")
            return
        }
        bestEffort { client.removeLabel(org, run.repo, run.issueNumber, "needs-human") }
        val approval = approvedBy?.let { " (approved by @$it)" } ?: " (approved by the approver agent)"
        if (run.releaseId != null && run.baseBranch != null) {
            val defaultBranch = runCatching { client.getRepo(org, run.repo)?.defaultBranch }.getOrNull() ?: "main"
            bestEffort {
                client.comment(org, run.repo, run.issueNumber,
                    "**Agern: merged** pull request #$pr into `${run.baseBranch}`$approval. " +
                        "The issue stays open until the release is merged into `$defaultBranch`.")
            }
        } else {
            bestEffort { client.comment(org, run.repo, run.issueNumber, "**Agern: merged** pull request #$pr$approval.") }
            bestEffort { client.closeIssue(org, run.repo, run.issueNumber) }
        }
        cleanupWorktree(run.id, merged = true)
        runs.setStatus(run.id, RunStatus.MERGED, clearError = true)
    }

    /**
     * Removes the run's sandbox, then its worktree and its local branch. A merged run's branch always goes (its work is merged); an
     * unmerged one's is kept only if it holds commits that exist nowhere else (never pushed). Blocking.
     */
    private fun cleanupWorktree(runId: UUID, merged: Boolean) {
        // The sandbox bind-mounts the worktree: its containers go first (normally already gone; never recreated after).
        sandboxes.releaseNow(runId)
        val run = runs.get(runId) ?: return
        val owner = users.byId(run.ownerId) ?: return
        bestEffort {
            workspaces.withRepoLock(owner.username, run.repo) {
                workspaces.git.removeWorktree(
                    workspaces.mainDir(owner.username, run.repo), workspaces.runDir(owner.username, run.repo, run.id),
                    workspaces.home(owner.username), keepUnpushed = !merged,
                )
            }
        }
    }

    private inline fun bestEffort(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.warn("Best-effort GitHub/git call failed: {}", e.message)
        }
    }

    // ------------------------------------------------------------ verdict parsing

    fun parseVerdict(r: StageAgentResult, allowed: Set<String>): Verdict? {
        val candidates = mutableListOf<JsonElement>()
        r.structuredOutput?.let { candidates += it }
        r.resultText?.let { t ->
            runCatching { candidates += AppJson.parseToJsonElement(t.trim()) }
            Regex("\\{[\\s\\S]*}").find(t)?.let { m -> runCatching { candidates += AppJson.parseToJsonElement(m.value) } }
        }
        for (c in candidates) {
            val o = c as? JsonObject ?: continue
            val verdict = o["verdict"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: continue
            if (verdict !in allowed) continue
            val summary = o["summary"]?.jsonPrimitive?.contentOrNull ?: ""
            val findings = runCatching {
                o["findings"]?.jsonArray?.mapNotNull { f ->
                    val fo = f as? JsonObject ?: return@mapNotNull null
                    FindingDto(
                        severity = fo["severity"]?.jsonPrimitive?.contentOrNull ?: "minor",
                        file = fo["file"]?.jsonPrimitive?.contentOrNull,
                        detail = fo["detail"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    )
                } ?: emptyList()
            }.getOrDefault(emptyList())
            return Verdict(verdict, summary, findings)
        }
        return null
    }

    // ------------------------------------------------------------ prompts

    private fun issueText(issue: GhIssue, comments: List<dk.egernet.factory.github.GhComment>) = buildString {
        append("Issue #${issue.number}: ${issue.title}\n")
        append("Kind: ${issueKindFromLabels(issue.labels.map { it.name })}")
        if (issue.labels.isNotEmpty()) append(" · Labels: ${issue.labels.joinToString(", ") { it.name }}")
        append(" · Author: @${issue.user?.login ?: "unknown"}\n\n")
        append("<issue_body>\n${issue.body?.trim().orEmpty().ifEmpty { "(no description)" }}\n</issue_body>\n")
        val human = comments.filter { !(it.body ?: "").startsWith("**Agern") }
        if (human.isNotEmpty()) {
            append("\n<issue_comments>\n")
            human.takeLast(30).forEach { append("@${it.user?.login ?: "?"} (${it.createdAt}):\n${it.body?.trim()}\n\n") }
            append("</issue_comments>\n")
        }
    }

    private fun specPrompt(c: RunContext) = """
        |Write the implementation spec for this issue in ${org}/${c.repo}.
        |
        |${c.issueText}${c.imagesBlock}
        |Your working directory is a checkout of the repository at `${c.baseRef}`. Explore it (read-only) before writing.
        |Your final message must be the spec in Markdown and nothing else: it is posted verbatim as a comment on the issue
        |and handed to the builder.
    """.trimMargin()

    private fun buildPrompt(c: RunContext, spec: String) = """
        |Implement issue #${c.issue.number} in ${org}/${c.repo} according to the spec below.
        |
        |You are in a git worktree on branch `${c.branch}`, created from `${c.baseRef}`. Commit your work to this branch.
        |Do not push, open pull requests or merge: the factory does that after you finish.
        |${migrationLine(c)}
        |${sandboxLine(c)}
        |${c.releaseNote}${c.issueText}${c.imagesBlock}
        |<spec>
        |$spec
        |</spec>
        |
        |When you are done, end with a short summary of what you changed, how you verified it, and anything left open.
    """.trimMargin()

    private fun fixPrompt(c: RunContext, round: Int, maxRounds: Int, changes: Map<String, Verdict>) = buildString {
        append("The reviewers requested changes. This is fix round $round of at most $maxRounds.\n")
        append("Address every finding below on the same branch (`${c.branch}`), re-run the relevant checks, and commit. ")
        append("If you are convinced a finding is wrong, do not change the code for it; explain why in your final summary ")
        append("so the reviewers see your reasoning.\n")
        append(migrationLine(c)).append('\n')
        append(sandboxLine(c)).append('\n')
        for ((role, v) in changes) {
            append("### Reviewer: $role — ${v.verdict}\n${v.summary}\n")
            v.findings.forEach { f -> append("- [${f.severity}] ${f.file ?: "(general)"}: ${f.detail}\n") }
            append('\n')
        }
        append("End with a short summary of what you changed for each finding.")
    }

    /**
     * The builder's rule for database migration versions, naming the branch the pull request merges into
     * (docs/ARCHITECTURE.md §6 "Database migrations"); a release run's versions must also clear the default branch.
     */
    private fun migrationLine(c: RunContext): String = buildString {
        append("Database migrations: the pull request merges into `${c.targetBranch}`. If the change adds a versioned migration ")
        append("(e.g. Flyway `V<n>__name.sql`), take its version from `${c.baseRef}` as it is now (fetch it first): the next version ")
        append("above every migration there. Check it again before you finish and after any rebase or merge of `${c.targetBranch}`, ")
        append("and never change a migration that exists there.")
        if (c.targetBranch != c.defaultBranch) {
            append(" The release is later merged into `${c.defaultBranch}`, so the version must also be above every migration on ")
            append("`${c.defaultBranch}`.")
        }
        append('\n')
    }

    /** One paragraph for the builder about the run's sandbox (empty line when sandboxes are off). */
    private fun sandboxLine(c: RunContext): String {
        val sb = c.sandbox?.takeIf { sandboxes.enabled } ?: return ""
        return if (sb.state == "approved") {
            "\nSandbox: this run has one (image `${sb.image}`). Build and test inside it with `factory_sandbox_exec` instead of on the host; " +
                "`factory_sandbox_info` lists its services and limits.\n" +
                (if (sb.platforms.isEmpty()) "" else "Platforms: ${sb.platforms.joinToString(", ") { "`$it`" }} (other images; " +
                    "`factory_sandbox_exec` with `platform`). Run the test suite in the default image and on every platform before you finish, " +
                    "and report the result per platform in your summary.\n")
        } else {
            "\nSandbox: none for this run (${sb.reason}). Build and test on the host as usual.\n"
        }
    }

    /** [followUp]: the follow-up round number when this reviews changes from the conversation ([builderSummary] is then the advisor's). */
    private fun reviewPrompt(c: RunContext, role: String, round: Int, spec: String, builderSummary: String, followUp: Int? = null): String {
        val after = when {
            followUp != null -> " (after follow-up round $followUp: changes the run owner requested in a conversation)"
            round > 0 -> " (after fix round $round)"
            else -> ""
        }
        val tag = if (followUp != null) "advisor_summary" else "builder_summary"
        return """
        |Review the changes on branch `${c.branch}` for issue #${c.issue.number} in ${org}/${c.repo}$after.
        |Your focus: **$role** (see your role instructions). You cannot edit files.
        |
        |Inspect the change with `git diff ${c.baseRef}...HEAD`, `git log ${c.baseRef}..HEAD` and by reading the code; run tests or
        |the app where that helps your review.
        |
        |${c.releaseNote}${c.issueText}
        |<spec>
        |$spec
        |</spec>
        |
        |<$tag>
        |${builderSummary.ifEmpty { "(none)" }}
        |</$tag>
        |
        |Answer through the structured output: verdict `PASS` or `CHANGES`, a summary, and findings
        |(severity blocker|major|minor|nit, file or null, detail). Use `CHANGES` only for findings that must be fixed before merge.
    """.trimMargin()
    }

    private fun approvalPrompt(c: RunContext, spec: String, reviews: Map<String, Verdict>, round: Int, maxRounds: Int, prUrl: String?) = buildString {
        append("Decide whether the pull request for issue #${c.issue.number} in ${org}/${c.repo} may be squash-merged into `${c.targetBranch}`.\n")
        append(c.releaseNote)
        append("Branch `${c.branch}`; pull request ${prUrl ?: "(none)"}; fix rounds used: $round of $maxRounds.\n")
        append("Inspect the change with `git diff ${c.baseRef}...HEAD`. You cannot edit files.\n\n")
        append(c.issueText).append("<spec>\n").append(spec).append("\n</spec>\n\n<reviews>\n")
        for ((role, v) in reviews) {
            append("### $role — ${v.verdict}\n${v.summary}\n")
            v.findings.forEach { f -> append("- [${f.severity}] ${f.file ?: "(general)"}: ${f.detail}\n") }
            append('\n')
        }
        append("</reviews>\n\nAnswer through the structured output: verdict `APPROVE` or `ESCALATE` and a summary.")
    }

    companion object {
        val READ_ONLY_TOOLS = listOf("Edit", "Write", "NotebookEdit")

        fun modelFor(s: dk.egernet.factory.SettingsDto, role: String): String =
            s.pipelineModels[role] ?: dk.egernet.factory.DEFAULT_PIPELINE_MODELS[role] ?: "sonnet"

        /**
         * `factory/issue-<n>-<slug>-<run>`; a release run's is `factory/release-<name>/issue-<n>-<slug>-<run>`, where
         * `<run>` is the first 8 hex digits of the run id. Every run has its own branch, so a run never reuses (or
         * force-pushes over) the branch, pull request or worktree of an earlier run of the same issue.
         */
        fun runBranch(issueNumber: Int, title: String, releaseName: String?, runId: UUID): String =
            (if (releaseName != null) "factory/release-$releaseName/" else "factory/") +
                "issue-$issueNumber-${slug(title)}-${runId.toString().take(8)}"

        fun slug(title: String): String =
            title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-').ifEmpty { "work" }

        fun firstParagraph(s: String): String = s.trim().split(Regex("\n\\s*\n")).first().take(500)

        /**
         * The pull request note for the screenshots of review [round] (null when there are none): the count, a pointer to
         * the run page ([Config.pageRef], so an internal address is never published) and one line per screenshot,
         * reviewers in [REVIEWER_ROLES] order, each in attach order. Captions are single-line already (sanitised on attach).
         */
        fun screenshotNoteText(
            cfg: Config, repo: String, runId: UUID, round: Int, stages: List<StageRecord>, shots: List<dk.egernet.factory.db.ScreenshotMeta>,
        ): String? {
            val byStage = shots.groupBy { it.stageId }
            val lines = REVIEWER_ROLES.flatMap { role ->
                stages.filter { it.role == role && it.round == round }.flatMap { st -> byStage[st.id].orEmpty().map { role to it.caption } }
            }
            if (lines.isEmpty()) return null
            val n = lines.size
            val what = if (round == 0) "build" else "fix round $round"
            val ref = cfg.pageRef("p/$repo/runs/$runId", "the page of run `$runId`")
            return "**Agern: screenshots** — the reviewers attached $n ${if (n == 1) "screenshot" else "screenshots"} of this change " +
                "($what). See them on $ref:\n\n" + lines.joinToString("\n") { (role, caption) -> "- **$role:** $caption" }
        }
    }
}
