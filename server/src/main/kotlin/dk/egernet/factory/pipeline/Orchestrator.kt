package dk.egernet.factory.pipeline

import dk.egernet.factory.AppJson
import dk.egernet.factory.Config
import dk.egernet.factory.FindingDto
import dk.egernet.factory.REVIEWER_ROLES
import dk.egernet.factory.RunStatus
import dk.egernet.factory.agent.AgentIdentity
import dk.egernet.factory.agent.AgentManager
import dk.egernet.factory.agent.EventBus
import dk.egernet.factory.agent.FeedItem
import dk.egernet.factory.agent.Prompts
import dk.egernet.factory.agent.StageAgentRequest
import dk.egernet.factory.agent.StageAgentResult
import dk.egernet.factory.agent.Workspaces
import dk.egernet.factory.conflict
import dk.egernet.factory.db.RunRecord
import dk.egernet.factory.db.RunStore
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

private class StageFailed(message: String) : RuntimeException(message)

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
) {
    private val log = LoggerFactory.getLogger(Orchestrator::class.java)
    private val jobs = ConcurrentHashMap<UUID, Job>()
    private val org get() = cfg.githubOrg

    fun job(runId: UUID): Job? = jobs[runId]

    fun publish(runId: UUID) {
        runs.touch(runId)
        runs.get(runId)?.let { bus.emitFeed(FeedItem.Run(runs.toDto(it))) }
    }

    // ------------------------------------------------------------ commands (ownership enforced here)

    /** Starts a run as [owner]. The owner's credentials are the only ones the run will ever use. */
    suspend fun start(owner: UserRecord, repo: String, issueNumber: Int, startedBySession: UUID?): RunRecord {
        if (owner.claudeCredEnc == null) throw unprocessable("Register a Claude credential in Settings first", "claude_credential_required")
        access.requireVisible(owner, repo)
        val client = access.requireClient(owner)
        val issue = withContext(Dispatchers.IO) { client.getIssue(org, repo, issueNumber) } ?: throw notFound("Issue #$issueNumber not found")
        val s = settings.get()
        val run = runs.createIfNoActive(
            repo, issueNumber, issue.title, issueKindFromLabels(issue.labels.map { it.name }), s.maxFixRounds, owner.id, startedBySession,
        ) ?: throw conflict("Issue #$issueNumber already has an active run", "active_run_exists")
        for (role in listOf("spec-writer", "builder") + REVIEWER_ROLES + "approver") runs.createStage(run.id, role, 0, modelFor(s, role))
        publish(run.id)
        val job = scope.launch { execute(run.id) }
        jobs[run.id] = job
        job.invokeOnCompletion { jobs.remove(run.id) }
        return runs.get(run.id)!!
    }

    /** Owner only, only in needs_human: squash-merge the PR and close the issue. */
    suspend fun approve(user: UserRecord, runId: UUID): RunRecord {
        val run = runs.get(runId) ?: throw notFound("Run not found")
        if (run.ownerId != user.id) throw forbidden("Only the owner of this run can approve it")
        if (run.status != RunStatus.NEEDS_HUMAN) throw conflict("Run is ${run.status}, not needs_human")
        if (run.prNumber == null) throw conflict("Run has no pull request to merge")
        runs.setStatus(runId, RunStatus.APPROVING, clearError = true)
        publish(runId)
        withContext(Dispatchers.IO) { merge(runs.get(runId)!!, user, approvedBy = user.username) }
        publish(runId)
        return runs.get(runId)!!
    }

    /** Owner or admin. */
    suspend fun cancel(user: UserRecord, runId: UUID): RunRecord {
        val run = runs.get(runId) ?: throw notFound("Run not found")
        if (run.ownerId != user.id && !user.isAdmin) throw forbidden("Only the owner or an admin can cancel this run")
        if (run.status in RunStatus.TERMINAL) throw conflict("Run is already ${run.status}")
        jobs[runId]?.let { it.cancel(); it.join() }
        val after = runs.get(runId)!!
        if (after.status in RunStatus.TERMINAL) return after
        runs.setStatus(runId, RunStatus.CANCELLED, "Cancelled by ${user.username}")
        runs.closeOpenStages(runId)
        val owner = users.byId(run.ownerId)!!
        withContext(Dispatchers.IO) {
            bestEffort { gh(owner).removeLabel(org, run.repo, run.issueNumber, "needs-human") }
            bestEffort { gh(owner).comment(org, run.repo, run.issueNumber, "**Agern:** run `${run.id}` was cancelled by @${user.username}.") }
            cleanupWorktree(runs.get(runId)!!, owner)
        }
        publish(runId)
        return runs.get(runId)!!
    }

    // ------------------------------------------------------------ the state machine

    private suspend fun execute(runId: UUID) {
        try {
            run(runId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Run {} failed: {}", runId, e.message)
            fail(runId, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fail(runId: UUID, reason: String) {
        runs.setStatus(runId, RunStatus.FAILED, reason)
        runs.closeOpenStages(runId)
        publish(runId)
    }

    private fun owner(run: RunRecord) = users.byId(run.ownerId) ?: error("Run owner no longer exists")
    private fun gh(owner: UserRecord): GitHubClient =
        access.client(owner) ?: throw StageFailed("The run owner has no GitHub token")

    private fun identity(owner: UserRecord) = AgentIdentity(
        owner.username, owner.displayName, owner.githubLogin, access.githubToken(owner),
        access.claudeCredential(owner) ?: throw StageFailed("The run owner has no Claude credential"),
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
        val s = settings.get()

        // Workspace: owner's clone + a worktree on the run branch.
        val repoInfo = withContext(Dispatchers.IO) { client.getRepo(org, run.repo) } ?: throw StageFailed("Repository not accessible")
        val issue = withContext(Dispatchers.IO) { client.getIssue(org, run.repo, run.issueNumber) } ?: throw StageFailed("Issue not found")
        val comments = withContext(Dispatchers.IO) { runCatching { client.listComments(org, run.repo, run.issueNumber) }.getOrDefault(emptyList()) }
        val branch = "factory/issue-${run.issueNumber}-${slug(issue.title)}"
        val worktree = workspaces.runDir(owner.username, run.repo, run.id)
        val baseRef = withContext(Dispatchers.IO) {
            val main = workspaces.ensureMain(id, run.repo)
            workspaces.withRepoLock(owner.username, run.repo) {
                workspaces.git.createWorktree(id, main, worktree, branch, repoInfo.defaultBranch, workspaces.home(owner.username))
            }
        }
        runs.setBranch(run.id, branch)
        publish(run.id)
        val ctx = RunContext(run.id, run.repo, issue, issueText(issue, comments), branch, baseRef, repoInfo.defaultBranch, worktree)

        // 1. Spec
        setStatus(run.id, RunStatus.SPEC)
        val specRes = stage(run.id, "spec-writer", 0, owner, ctx, specPrompt(ctx), model = s.pipelineModels["spec-writer"],
            disallowed = READ_ONLY_TOOLS)
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
                runs.createStage(run.id, "builder", round, modelFor(s, "builder"))
                REVIEWER_ROLES.forEach { runs.createStage(run.id, it, round, modelFor(s, it)) }
                publish(run.id)
            }
            val prompt = if (round == 0) buildPrompt(ctx, spec) else fixPrompt(ctx, round, s.maxFixRounds, findingsForBuilder)
            val b = stage(run.id, "builder", round, owner, ctx, prompt, model = s.pipelineModels["builder"],
                resume = builderClaudeSession, parent = builderSessionId)
            builderClaudeSession = b.claudeSessionId ?: builderClaudeSession
            builderSessionId = b.session.id
            val builderSummary = b.resultText?.trim() ?: ""
            val ahead = withContext(Dispatchers.IO) { workspaces.git.commitsAhead(worktree, baseRef, workspaces.home(owner.username)) }
            if (ahead == 0) {
                finishStage(run.id, "builder", round, null, "No commits on the branch", emptyList(), failed = true)
                throw StageFailed("The builder made no commits on $branch")
            }
            finishStage(run.id, "builder", round, null, firstParagraph(builderSummary), emptyList())

            withContext(Dispatchers.IO) {
                workspaces.git.push(id, worktree, branch, workspaces.home(owner.username))
                run = runs.get(run.id)!!
                if (run.prNumber == null) {
                    val pr = client.findOpenPull(org, run.repo, branch) ?: client.createPull(
                        org, run.repo, branch, repoInfo.defaultBranch, "${issue.title} (#${issue.number})",
                        "Closes #${issue.number}\n\nBuilt by Agern run `${run.id}` for @${owner.githubLogin ?: owner.username}.\n\n" +
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
                        val r = stage(run.id, role, round, owner, ctx, reviewPrompt(ctx, role, round, spec, builderSummary),
                            model = s.pipelineModels[role], schema = Schemas.REVIEW, disallowed = READ_ONLY_TOOLS)
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
            val changes = lastReviews.filterValues { it.verdict == "CHANGES" }
            if (changes.isEmpty()) break
            if (round >= s.maxFixRounds) {
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

        // 3. Approval
        setStatus(run.id, RunStatus.APPROVING)
        runs.setStageRound(run.id, "approver", round)
        run = runs.get(run.id)!!
        val a = stage(run.id, "approver", round, owner, ctx, approvalPrompt(ctx, spec, lastReviews, round, s.maxFixRounds, run.prUrl),
            model = s.pipelineModels["approver"], schema = Schemas.APPROVAL, disallowed = READ_ONLY_TOOLS)
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

    private data class RunContext(
        val runId: UUID, val repo: String, val issue: GhIssue, val issueText: String, val branch: String,
        val baseRef: String, val defaultBranch: String, val worktree: Path,
    )

    /** Runs one stage's agent and marks the stage running/failed. The caller records the outcome on success. */
    private suspend fun stage(
        runId: UUID, role: String, round: Int, owner: UserRecord, ctx: RunContext, prompt: String,
        model: String?, schema: String? = null, disallowed: List<String> = emptyList(),
        resume: String? = null, parent: UUID? = null,
    ): StageAgentResult {
        val st = stageRecord(runId, role, round)
        val s = settings.get()
        val res = agents.runStageAgent(
            StageAgentRequest(
                owner = owner, repo = ctx.repo, role = role, model = model ?: modelFor(s, role), runId = runId,
                issueNumber = ctx.issue.number, parentSessionId = parent, workDir = ctx.worktree,
                systemPrompt = prompts.system(role, ctx.repo, s.agentsMayInstallSoftware), prompt = prompt,
                jsonSchema = schema, disallowedTools = disallowed, resumeClaudeSessionId = resume,
                title = "$role · #${ctx.issue.number}" + if (round > 0) " · round $round" else "",
            ),
        ) { session ->
            runs.startStage(st.id, session.id, session.model)
            publish(runId)
        }
        if (!res.ok) {
            runs.finishStage(st.id, "failed", null, res.error, emptyList())
            publish(runId)
            throw StageFailed("$role failed: ${res.error}")
        }
        return res
    }

    private fun stageRecord(runId: UUID, role: String, round: Int): StageRecord =
        runs.stages(runId).lastOrNull { it.role == role && it.round == round } ?: runs.createStage(runId, role, round, modelFor(settings.get(), role))

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

    /** Squash-merges the PR and closes the issue. On failure the run goes (back) to needs_human. */
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
        bestEffort {
            client.comment(org, run.repo, run.issueNumber,
                "**Agern: merged** pull request #$pr" + (approvedBy?.let { " (approved by @$it)" } ?: " (approved by the approver agent)") + ".")
        }
        bestEffort { client.closeIssue(org, run.repo, run.issueNumber) }
        runs.setStatus(run.id, RunStatus.MERGED, clearError = true)
        cleanupWorktree(run, owner)
    }

    private fun cleanupWorktree(run: RunRecord, owner: UserRecord) {
        bestEffort {
            val main = workspaces.mainDir(owner.username, run.repo)
            val wt = workspaces.runDir(owner.username, run.repo, run.id)
            if (java.nio.file.Files.exists(wt)) workspaces.git.removeWorktree(main, wt, workspaces.home(owner.username))
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
        |${c.issueText}
        |Your working directory is a checkout of the repository at `${c.baseRef}`. Explore it (read-only) before writing.
        |Your final message must be the spec in Markdown and nothing else: it is posted verbatim as a comment on the issue
        |and handed to the builder.
    """.trimMargin()

    private fun buildPrompt(c: RunContext, spec: String) = """
        |Implement issue #${c.issue.number} in ${org}/${c.repo} according to the spec below.
        |
        |You are in a git worktree on branch `${c.branch}`, created from `${c.baseRef}`. Commit your work to this branch.
        |Do not push, open pull requests or merge: the factory does that after you finish.
        |
        |${c.issueText}
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
        append("so the reviewers see your reasoning.\n\n")
        for ((role, v) in changes) {
            append("### Reviewer: $role — ${v.verdict}\n${v.summary}\n")
            v.findings.forEach { f -> append("- [${f.severity}] ${f.file ?: "(general)"}: ${f.detail}\n") }
            append('\n')
        }
        append("End with a short summary of what you changed for each finding.")
    }

    private fun reviewPrompt(c: RunContext, role: String, round: Int, spec: String, builderSummary: String) = """
        |Review the changes on branch `${c.branch}` for issue #${c.issue.number} in ${org}/${c.repo}${if (round > 0) " (after fix round $round)" else ""}.
        |Your focus: **$role** (see your role instructions). You cannot edit files.
        |
        |Inspect the change with `git diff ${c.baseRef}...HEAD`, `git log ${c.baseRef}..HEAD` and by reading the code; run tests or
        |the app where that helps your review.
        |
        |${c.issueText}
        |<spec>
        |$spec
        |</spec>
        |
        |<builder_summary>
        |${builderSummary.ifEmpty { "(none)" }}
        |</builder_summary>
        |
        |Answer through the structured output: verdict `PASS` or `CHANGES`, a summary, and findings
        |(severity blocker|major|minor|nit, file or null, detail). Use `CHANGES` only for findings that must be fixed before merge.
    """.trimMargin()

    private fun approvalPrompt(c: RunContext, spec: String, reviews: Map<String, Verdict>, round: Int, maxRounds: Int, prUrl: String?) = buildString {
        append("Decide whether the pull request for issue #${c.issue.number} in ${org}/${c.repo} may be squash-merged into `${c.defaultBranch}`.\n")
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

        fun slug(title: String): String =
            title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-').ifEmpty { "work" }

        fun firstParagraph(s: String): String = s.trim().split(Regex("\n\\s*\n")).first().take(500)
    }
}
