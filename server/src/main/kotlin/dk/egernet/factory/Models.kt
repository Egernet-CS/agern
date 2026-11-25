package dk.egernet.factory

import dk.egernet.factory.db.iso
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@OptIn(ExperimentalSerializationApi::class)
val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

/** Thrown anywhere in request handling; rendered as `{ error: { code, message } }`. */
class ApiException(
    val status: HttpStatusCode, val code: String, override val message: String,
    /** Only for `locked` / `too_many_attempts`: when the lock ends (ISO-8601). */
    val lockedUntil: String? = null,
) : RuntimeException(message)

fun badRequest(msg: String) = ApiException(HttpStatusCode.BadRequest, "bad_request", msg)
fun unauthorized(msg: String = "Not logged in") = ApiException(HttpStatusCode.Unauthorized, "unauthorized", msg)
fun forbidden(msg: String) = ApiException(HttpStatusCode.Forbidden, "forbidden", msg)
fun notFound(msg: String) = ApiException(HttpStatusCode.NotFound, "not_found", msg)
fun conflict(msg: String, code: String = "conflict") = ApiException(HttpStatusCode.Conflict, code, msg)
fun locked(msg: String, until: java.time.Instant? = null) =
    ApiException(HttpStatusCode.Locked, "locked", msg, until?.iso())
fun tooManyAttempts(msg: String, until: java.time.Instant) =
    ApiException(HttpStatusCode.TooManyRequests, "too_many_attempts", msg, until.iso())
fun unprocessable(msg: String, code: String = "unprocessable") = ApiException(HttpStatusCode.UnprocessableEntity, code, msg)

@Serializable data class ErrorBody(val error: ErrorDetail)
@Serializable
data class ErrorDetail(
    val code: String, val message: String,
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER) val lockedUntil: String? = null,
)

@Serializable
data class UserDto(
    val id: String, val username: String, val displayName: String, val isAdmin: Boolean,
    val githubLogin: String?, val hasGithubToken: Boolean,
    val hasClaudeCredential: Boolean, val claudeCredentialKind: String?,
    val twoFactorEnabled: Boolean,
    /** Set while the second factor is locked after too many wrong codes. */
    val twoFactorLockedUntil: String?,
    /** `classic` (ghp_…) or `fine_grained` (github_pat_…); null without a token or when unknown. */
    val githubTokenKind: String?,
    /** Classic tokens: granted OAuth scopes, e.g. `["read:org","repo"]`; null without a token or for fine-grained tokens. */
    val githubTokenScopes: List<String>?,
    /** The image provider id and model (docs/ARCHITECTURE.md §6 "Generated images"); null without a stored key. Never the key. */
    val imageProvider: String? = null,
    val imageModel: String? = null,
)

@Serializable
data class LoginStepDto(
    val next: String,
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER) val secret: String? = null,
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER) val otpauthUri: String? = null,
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER) val user: UserDto? = null,
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER) val recoveryCodes: List<String>? = null,
)

@Serializable
data class ProjectDto(
    val name: String, val fullName: String, val description: String?, val private: Boolean,
    val defaultBranch: String, val language: String?, val openIssues: Int, val pushedAt: String?,
    val htmlUrl: String, val activeAgents: Int, val activeRuns: Int,
    /** `/api/projects/<repo>/icon?v=<version>`, or null without an icon (docs/ARCHITECTURE.md §8 "Project icons"). */
    val iconUrl: String? = null,
    /** The workspace definition (docs/ARCHITECTURE.md §5 "Workspace projects"); filled by `GET /api/projects/{repo}` only. */
    val workspace: WorkspaceDto? = null,
    /** The project's settings (docs/ARCHITECTURE.md §6 "Merge policy"); defaults without a stored row. */
    val settings: ProjectSettingsDto = ProjectSettingsDto(),
)

/**
 * Per-project settings set by an admin (docs/ARCHITECTURE.md §6 "Merge policy"): the [MergePolicy] and, used only with
 * [MergePolicy.GITHUB], the GitHub logins and team slugs requested as reviewers on a run's pull request.
 */
@Serializable
data class ProjectSettingsDto(
    val mergePolicy: String = MergePolicy.AUTOMATIC,
    val reviewers: List<String> = emptyList(),
    val teamReviewers: List<String> = emptyList(),
)

/** `PUT /api/projects/{repo}/settings`. Omitted lists are empty lists. */
@Serializable
data class ProjectSettingsReq(val mergePolicy: String? = null, val reviewers: List<String>? = null, val teamReviewers: List<String>? = null)

/** How a project's runs reach the default branch (docs/ARCHITECTURE.md §6 "Merge policy"). */
object MergePolicy {
    /** The approver agent may merge (the default). */
    const val AUTOMATIC = "automatic"
    /** The approver only recommends; every merge waits in `needs_human` for the run owner's approval. */
    const val OWNER_APPROVES = "owner_approves"
    /** Agern never merges: a passing run ends `ready` and the team merges the pull request on GitHub. */
    const val GITHUB = "github"
    val ALL = setOf(AUTOMATIC, OWNER_APPROVES, GITHUB)
}

/**
 * [path]: where the definition comes from, `.agern/workspace.yml` or `.gitmodules` (members derived from the
 * submodules). [contextOnly]: the paths of submodules that are not members (fetched, never a run's target).
 */
@Serializable
data class WorkspaceDto(
    val path: String, val valid: Boolean, val errors: List<String>, val members: List<WorkspaceMemberDto>,
    val contextOnly: List<String> = emptyList(),
    /** Member labels that do not exist in the workspace repository (creating them failed); empty when all exist. */
    val missingLabels: List<String> = emptyList(),
)

/** `POST /api/projects/{repo}/workspace/labels`: the labels created now, and those still missing. */
@Serializable
data class MemberLabelsDto(val created: List<String>, val missing: List<String>)

/**
 * [branch]: the effective base branch, or the declared one (possibly null) for a member the caller cannot read.
 * [submodule]: the member is a git submodule of the workspace repository (Agern bumps its pointer after a merge).
 */
@Serializable
data class WorkspaceMemberDto(
    val directory: String, val repository: String, val branch: String?, val label: String, val accessible: Boolean,
    val submodule: Boolean = false,
)

/** A member whose label an issue carries. */
@Serializable
data class IssueMemberDto(val directory: String, val repository: String)

@Serializable data class LabelDto(val name: String, val color: String)

@Serializable
data class IssueDto(
    val number: Int, val title: String, val state: String, val kind: String,
    val labels: List<LabelDto>, val author: String, val assignees: List<String>, val comments: Int,
    val createdAt: String, val updatedAt: String, val htmlUrl: String,
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER) val body: String? = null,
    val activeRunId: String?,
    /** The release the issue is planned in, or null (docs/ARCHITECTURE.md §6 "Releases"). */
    val release: IssueReleaseDto? = null,
    /** Images selected for this issue in a chat (docs/ARCHITECTURE.md §6 "Generated images"). */
    val imageCount: Int = 0,
    /** Workspace projects: the members whose label the issue carries; empty otherwise. */
    val workspaceMembers: List<IssueMemberDto> = emptyList(),
)

@Serializable data class IssueReleaseDto(val id: String, val name: String)

/**
 * An issue as planned in a release: title and kind as they were when it was assigned, and its build state
 * ([ReleaseIssueState]) with the issue's latest release run and why it is held or skipped.
 */
@Serializable
data class ReleaseIssueDto(
    val number: Int, val title: String, val kind: String,
    val state: String = ReleaseIssueState.WAITING, val runId: String? = null, val note: String? = null,
    /** The pull request of [runId] (into the release branch), if it has one. */
    val prNumber: Int? = null, val prUrl: String? = null,
    /** A waiting issue that was retried with `continue` or `step` from [runId]; null = built from scratch. */
    val retryMode: String? = null,
    /** The member repository and directory its linked run targets; null without a run or for the project repository. */
    val targetRepo: String? = null, val targetDir: String? = null,
)

/** Why a release is paused; [resumeAt] is set only for an automatic resume after a usage limit. */
@Serializable data class ReleasePauseDto(val reason: String, val note: String?, val resumeAt: String?)

@Serializable
data class ReleaseDto(
    val id: String, val repo: String, val name: String, val description: String, val status: String,
    val createdBy: OwnerDto, val createdAt: String, val updatedAt: String,
    /** In build order. */
    val issues: List<ReleaseIssueDto>,
    /** Who scheduled or started the release (its runs use this user's credentials); null while it is planned. */
    val owner: OwnerDto? = null,
    /** `release/<name>` once started. */
    val branch: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    /** Set while the release is `paused`. */
    val pause: ReleasePauseDto? = null,
    /** ISO instant of the scheduled start; non-null exactly while `scheduled`. */
    val scheduledAt: String? = null,
    /** The release this one waits behind (only while `scheduled` and due). */
    val waitingFor: IssueReleaseDto? = null,
    /** The last scheduled start that did not happen; kept until the release is scheduled again. */
    val startFailure: ReleaseStartFailureDto? = null,
    /** The release pull request (`release/<name>` → [targetBranch]); opened when the release is ready and complete. */
    val prNumber: Int? = null,
    val prUrl: String? = null,
    /** The default branch the release pull request merges into; null until it is known. */
    val targetBranch: String? = null,
    /** `unknown`, `clean` or `conflict`: whether the release pull request merges cleanly, as last checked. */
    val mergeState: String = ReleaseMergeState.UNKNOWN,
    /** `rebase`, `merge` or `unavailable` (only squash merges allowed): how approval will merge, or how it merged. */
    val mergeMethod: String? = null,
    /** The last problem to show on the release page (opening or merging the pull request, closing issues). */
    val notice: String? = null,
    val readyAt: String? = null,
    val releasedAt: String? = null,
    /** Who approved the release (for an automatic release: its owner); null when merged on GitHub outside Agern. */
    val releasedBy: OwnerDto? = null,
    /** The default branch's commit after the release merge. */
    val mergeCommit: String? = null,
    /** [ReleaseMode]: `manual` (waits for the owner's approval) or `automatic` (Agern merges it once it is green). */
    val mode: String = ReleaseMode.MANUAL,
    /** True when Agern merged it by itself (automatic mode); [releasedBy] is then the owner. */
    val releasedAutomatically: Boolean = false,
    /** The checks on the release pull request's head commit, as last read; null until a pull request exists. */
    val checks: ReleaseChecksDto? = null,
    /**
     * Every repository of a release that spans several (docs/ARCHITECTURE.md §6 "Several repositories"): the member
     * repositories in merge order (untouched ones last), then the project repository. Empty for every other release.
     */
    val repositories: List<ReleaseRepoDto> = emptyList(),
    /** The project's current [MergePolicy], so the release page can explain why it cannot be approved or automatic. */
    val mergePolicy: String = MergePolicy.AUTOMATIC,
) {
    /**
     * This release as [canSee] lets a viewer see it: a member repository the viewer cannot read keeps its name and
     * directory, but loses its pull request link, checks and merge commit, and so do the issues built in it.
     */
    fun forViewer(canSee: (String) -> Boolean): ReleaseDto {
        if (repositories.isEmpty()) return this
        val hidden = repositories.filter { it.directory != null && !canSee(it.repo) }.map { it.repo }.toSet()
        if (hidden.isEmpty()) return this
        return copy(
            repositories = repositories.map {
                if (it.repo !in hidden) it else it.copy(accessible = false, prNumber = null, prUrl = null, checks = null, mergeCommit = null)
            },
            issues = issues.map { if (it.targetRepo in hidden) it.copy(prNumber = null, prUrl = null) else it },
        )
    }
}

/**
 * One repository of a release that spans several: its `release/<name>` [branch] → [baseBranch], and its release pull
 * request. [directory] is null for the project repository. [state]: `none` (not opened yet), `open` or `merged`.
 * [issues]: the merged issues built in it. [accessible] is false (and the pull request's details null) for a viewer
 * who cannot read the repository.
 */
@Serializable
data class ReleaseRepoDto(
    val repo: String, val directory: String?, val submodule: Boolean, val baseBranch: String?, val branch: String,
    val state: String, val prNumber: Int? = null, val prUrl: String? = null,
    val mergeState: String = ReleaseMergeState.UNKNOWN, val mergeMethod: String? = null, val checks: ReleaseChecksDto? = null,
    val mergedAt: String? = null, val mergeCommit: String? = null,
    val issues: List<Int> = emptyList(), val accessible: Boolean = true,
)

/** [state]: [ReleaseChecksState]; [detail] e.g. "Failed: build, lint"; [sha] the head commit they were read for. */
@Serializable data class ReleaseChecksDto(val state: String, val detail: String?, val sha: String?)

@Serializable data class ReleaseStartFailureDto(val at: String, val owner: OwnerDto, val reason: String)

@Serializable data class OwnerDto(val username: String, val displayName: String)

/** A personal MCP token (docs/ARCHITECTURE.md §7 "Personal tokens"); never carries the token or its hash. */
@Serializable
data class McpTokenDto(
    val id: String, val name: String, val repo: String, val owner: OwnerDto, val createdAt: String,
    val lastUsedAt: String?, val expiresAt: String?, val expired: Boolean,
)

/** `expiresInDays`: null = never, else 1…366. */
@Serializable data class CreateMcpTokenReq(val name: String, val repo: String, val expiresInDays: Int? = null)

/** The answer to creating a token: the only place [secret] ever appears. */
@Serializable data class CreatedMcpTokenDto(val token: McpTokenDto, val secret: String, val mcpUrl: String, val serverName: String)

@Serializable
data class AgentSessionDto(
    val id: String, val repo: String, val kind: String, val model: String, val status: String,
    val owner: OwnerDto, val title: String?, val runId: String?, val parentSessionId: String?,
    val issueNumber: Int?, val createdAt: String, val updatedAt: String, val endedAt: String?,
    val costUsd: Double?, val numTurns: Int?, val lastActivity: String?,
    /** True while an agent process exists for the session (a chat can be `idle` with or without one). */
    val live: Boolean,
)

@Serializable
data class FindingDto(val severity: String, val file: String?, val detail: String)

@Serializable
data class StageDto(
    val id: String, val role: String, val round: Int, val model: String, val status: String, val verdict: String?,
    val summary: String?, val findings: List<FindingDto>, val sessionId: String?,
    val startedAt: String?, val endedAt: String?,
    /** Screenshots the stage's reviewer attached (only `ux` and `ui-design`), in attach order. Never the bytes. */
    val screenshots: List<ScreenshotDto> = emptyList(),
    /** Images the stage's agent generated, oldest first. Never the bytes. */
    val images: List<GeneratedImageDto> = emptyList(),
    /** A warning the server recorded on the stage, e.g. "UI change reviewed without screenshots: …"; null: none. */
    val warning: String? = null,
)

/** A warning on a run: the stage warnings of one [round], by the [roles] that carry it (docs/ARCHITECTURE.md §6 "Screenshots"). */
@Serializable
data class RunWarningDto(val round: Int, val roles: List<String>, val text: String)

/** A screenshot attached to a stage; the image is served by `GET /api/runs/{id}/screenshots/{sid}`. */
@Serializable
data class ScreenshotDto(
    val id: String, val caption: String, val mediaType: String, val size: Long, val width: Int, val height: Int,
    val createdAt: String,
)

@Serializable
data class RunDto(
    val id: String, val repo: String, val issueNumber: Int, val issueTitle: String, val issueKind: String,
    val status: String, val round: Int, val maxRounds: Int, val branch: String?, val prNumber: Int?,
    val prUrl: String?, val owner: OwnerDto, val startedBySessionId: String?, val error: String?,
    val createdAt: String, val updatedAt: String, val endedAt: String?, val stages: List<StageDto>,
    /** What the run decided about its sandbox when it started; null before that (and for runs from before sandboxes). */
    val sandbox: RunSandboxDto? = null,
    /** The release this run builds an issue for; null for a single run (or when the release was deleted). */
    val releaseId: String? = null,
    /** The branch the run starts from and merges into; null = the repository's default branch. */
    val baseBranch: String? = null,
    /** The conversation with the run's advisor while it needs a human (docs/ARCHITECTURE.md §6 "Conversation at needs human"). */
    val conversation: RunConversationDto = RunConversationDto(),
    /** The failed run this one continues from its commits (docs/ARCHITECTURE.md §6 "Retrying a run"). */
    val continuesRunId: String? = null,
    /** The newest run that continues this one, if any. */
    val continuedByRunId: String? = null,
    /** Why a failed run stopped ([FailureKind]); null when it did not fail (or failed before this was recorded). */
    val failureKind: String? = null,
    /** The mechanical step after the builder that failed ([FailedStep]): `push`, `pull_request` or `merge`. */
    val failedStep: String? = null,
    /** Warnings that never fail the run, per round, oldest round first (from the stages' [StageDto.warning]). */
    val warnings: List<RunWarningDto> = emptyList(),
    /** A workspace run: the member repository and directory it changes; null otherwise. */
    val targetRepo: String? = null,
    val targetDirectory: String? = null,
    /** The project's [MergePolicy] when the run started; never changes afterwards. */
    val mergePolicy: String = MergePolicy.AUTOMATIC,
    /** True when the run's pull request was merged on GitHub, outside Agern (followed for `github` runs). */
    val mergedOnGitHub: Boolean = false,
    /** The GitHub login that merged the pull request on GitHub; null when unknown or merged by Agern. */
    val mergedBy: String? = null,
)

/** Ways to retry a failed run (docs/ARCHITECTURE.md §6 "Retrying a run"). */
object RetryMode {
    /** Retry only the failed step (push, pull request, merge) on the same run and go on from there. */
    const val STEP = "step"
    /** A new run whose worktree starts from the failed run's branch, reusing its spec. */
    const val CONTINUE = "continue"
    /** A new run from scratch (the behaviour before retry modes existed; the default when a client sends none). */
    const val START_OVER = "start_over"
    /** Whatever the server recommends for the run. Only accepted in requests. */
    const val RECOMMENDED = "recommended"
    val ALL = listOf(STEP, CONTINUE, START_OVER)
}

/** Why a run failed, recorded when it fails. */
object FailureKind {
    const val TIME_LIMIT = "time_limit"; const val USAGE_LIMIT = "usage_limit"; const val RESTART = "restart"
    const val API_UNREACHABLE = "api_unreachable"; const val CREDENTIALS = "credentials"
    /** The builder made no commits (it usually says why: blocked on something). */
    const val BLOCKED = "blocked"
    /** A mechanical step after the builder failed (see [FailedStep]). */
    const val STEP = "step"
    /** The merge was refused: a submodule pointer names a commit its remote does not have (the run needs a human). */
    const val SUBMODULE_COMMIT_MISSING = "submodule_commit_missing"
    const val OTHER = "other"

    /**
     * Failures that come from the environment rather than the work. Continuing is recommended for them when the branch
     * has commits; issue #57 (pause and retry transient failures automatically) can build on this set.
     */
    val INTERRUPTED = setOf(TIME_LIMIT, USAGE_LIMIT, RESTART, API_UNREACHABLE)

    private val API_UNREACHABLE_RE = Regex(
        "(?i)can'?t reach the api|unable to connect to (the )?api|api error: (connection|network)|connection error|" +
            "network error|fetch failed|ECONNREFUSED|ENOTFOUND|ETIMEDOUT|EAI_AGAIN|ECONNRESET|ConnectException|UnknownHostException",
    )

    /** True when [error] (a stage agent's error) says the Claude API or the network could not be reached. */
    fun looksUnreachable(error: String?): Boolean = error != null && API_UNREACHABLE_RE.containsMatchIn(error)
}

/** The mechanical steps after the builder that a step retry can repeat. */
object FailedStep {
    const val PUSH = "push"; const val PULL_REQUEST = "pull_request"; const val MERGE = "merge"
}

/** One way to retry a run, and whether it is possible now. */
@Serializable
data class RetryOptionDto(val mode: String, val available: Boolean, val detail: String)

/** `GET /api/runs/{id}/retry`: how the run can be retried, and what is recommended (with a one-line reason). */
@Serializable
data class RetryOptionsDto(
    val runId: String,
    val recommended: String,
    val reason: String,
    val options: List<RetryOptionDto>,
    /** The step a step retry repeats, when one is possible. */
    val step: String? = null,
    /** Commits of the run's branch that are not on its base branch; null when the branch was not found. */
    val commits: Int? = null,
    /** For a release run: the release and its issue number (retry through the release). */
    val releaseId: String? = null,
)

/** Body of the retry endpoints; [mode] omitted = `start_over` (as before retry modes existed). */
@Serializable
data class RetryReq(val mode: String? = null)

/**
 * [sessionId]: the advisor session, once the owner has sent a message. [review]: `none`, `required` (changes made in
 * the conversation are not reviewed yet) or `running` (a follow-up review is in progress). [followUpFrom]: the first
 * follow-up round; stages of rounds >= it are follow-up rounds.
 */
@Serializable
data class RunConversationDto(val sessionId: String? = null, val review: String = ReviewState.NONE, val followUpFrom: Int? = null)

/** `POST /api/runs/{id}/conversation`: the message was accepted for the run's advisor session [sessionId]. */
@Serializable data class TalkAcceptedDto(val status: String = "accepted", val sessionId: String)

/** The follow-up review state of a `needs_human` run's conversation. */
object ReviewState {
    const val NONE = "none"; const val REQUIRED = "required"; const val RUNNING = "running"
}

/**
 * A run's sandbox decision (docs/ARCHITECTURE.md §5 "Sandboxes"), fixed when the run starts.
 * `state`: `disabled` (FACTORY_SANDBOX=off), `none` (no definition file), `invalid`, `unapproved`, `approved`.
 * Only `approved` gives the builder a sandbox; [reason] says why (not) in words.
 */
@Serializable
data class RunSandboxDto(
    val state: String, val reason: String, val sha256: String?, val image: String?,
    /** The definition's platforms besides the default image (`approved` only; empty for older runs). */
    val platforms: List<String> = emptyList(),
    /** Where the builder ran commands: `default` and platform names, in first-use order (only when there are platforms). */
    val platformsUsed: List<String> = emptyList(),
)

/** A sidecar's readiness check (`ready:`). */
@Serializable data class SandboxReadyDto(val command: String, val timeoutSeconds: Int)

@Serializable data class SandboxServiceDto(val name: String, val image: String, val env: Map<String, String>, val ready: SandboxReadyDto? = null)

/** A platform: another image the builder can run commands in. [setupInherited]: it uses the top-level `setup`. */
@Serializable data class SandboxPlatformDto(val name: String, val image: String, val setup: String?, val setupInherited: Boolean)

/** `user:` as written ([name] or [uid]) and the uid commands run as inside the container. */
@Serializable data class SandboxUserDto(val name: String?, val uid: Int)

@Serializable
data class SandboxLimitsDto(
    /** Effective per-container limits: what the file asks for, capped by the server's limits. */
    val cpus: Double, val memory: String,
    val requestedCpus: Double?, val requestedMemory: String?,
    /** The file asked for more than the server allows. */
    val capped: Boolean,
)

@Serializable
data class SandboxDefinitionDto(
    val image: String, val setup: String?, val env: Map<String, String>, val services: List<SandboxServiceDto>,
    val caches: List<String>, val limits: SandboxLimitsDto,
    val platforms: List<SandboxPlatformDto> = emptyList(),
    /** Null: commands run as root inside the container. */
    val user: SandboxUserDto? = null,
)

@Serializable data class SandboxApprovalDto(val sha256: String, val content: String, val approvedBy: OwnerDto, val approvedAt: String)

/** `GET /api/projects/{repo}/sandbox`: the definition on the default branch and its approval. */
@Serializable
data class SandboxStatusDto(
    val repo: String,
    /** FACTORY_SANDBOX is not `off`. Definitions can be approved either way. */
    val enabled: Boolean,
    val path: String,
    /** The branch the definition is read from (the default branch, which runs start from). */
    val branch: String,
    /** `none` (no file), `invalid`, `pending` (valid, this content not approved) or `approved`. */
    val state: String,
    val sha256: String?,
    val content: String?,
    val errors: List<String>,
    val definition: SandboxDefinitionDto?,
    /** The repository's approval, possibly of other content (compare [sha256]); null when none. */
    val approval: SandboxApprovalDto?,
    /** Who can approve: the admins (so a non-admin knows whom to ask). */
    val approvers: List<OwnerDto> = emptyList(),
)

/** A sandbox that exists right now (`GET /api/admin/sandboxes`). */
@Serializable
data class RunningSandboxDto(
    val runId: String, val repo: String,
    /** False when the admin cannot see the repository on GitHub: then [issueTitle] is withheld and the UI does not link. */
    val visible: Boolean,
    val issueNumber: Int, val issueTitle: String?, val runStatus: String, val owner: OwnerDto,
    val image: String, val services: List<String>,
    /** The approved definition's hash. */
    val sha256: String,
    val createdAt: String,
    /** Per-container limits it runs with. */
    val cpus: Double, val memory: String,
    /** The containers that run commands right now (the default image and/or platforms), each taking one slot. */
    val containers: List<SandboxContainerDto> = emptyList(),
    /** The definition's platforms (whether or not they exist right now). */
    val platforms: List<String> = emptyList(),
    /** Slots of FACTORY_SANDBOX_MAX_CONCURRENT this run holds: one per entry of [containers]. */
    val slots: Int = 0,
    /** The memory all of the run's containers may use together: [memory] × (command containers + services). */
    val memoryTotal: String = memory,
)

/** One container of a run's sandbox that runs commands: `default` (the top-level image) or a platform. */
@Serializable data class SandboxContainerDto(val platform: String, val image: String, val createdAt: String)

@Serializable
data class SandboxServerLimitsDto(
    val maxCpus: Double, val maxMemory: String, val maxConcurrent: Int, val execTimeoutMinutes: Long, val registries: List<String>,
    val maxPlatforms: Int = 4,
)

/** `GET /api/admin/sandboxes`: the runtime, its limits and the sandboxes that exist. */
@Serializable
data class SandboxOverviewDto(
    val enabled: Boolean,
    /** `off`, `podman` or `fake` (development). */
    val runtime: String,
    val inUse: Int,
    val limits: SandboxServerLimitsDto,
    /** Oldest first. */
    val sandboxes: List<RunningSandboxDto>,
)

/** `GET /api/admin/sandboxes/pending`: a project whose valid `.agern/sandbox.yml` is not approved in this exact version. */
@Serializable
data class PendingSandboxDto(
    val repo: String, val sha256: String, val image: String,
    /** The hash of the version that is approved (the file changed since), or null when none ever was. */
    val approvedSha256: String?,
)

@Serializable
data class EventDto(val seq: Long, val sessionId: String, val ts: String, val type: String, val payload: JsonElement)

@Serializable
data class SettingsDto(
    val agentsMayInstallSoftware: Boolean = false,
    val chatModel: String = "opus",
    val pipelineModels: Map<String, String> = DEFAULT_PIPELINE_MODELS,
    val maxFixRounds: Int = 2,
)

val PIPELINE_ROLES = listOf("spec-writer", "builder", "security", "ux", "ui-design", "code", "approver")
val REVIEWER_ROLES = listOf("security", "ux", "ui-design", "code")
/** Reviewer roles that may attach screenshots to their stage (docs/ARCHITECTURE.md §6 "Screenshots"). */
val SCREENSHOT_ROLES = setOf("ux", "ui-design")
/** Pipeline roles whose running agent accepts messages from the run's owner (docs/ARCHITECTURE.md §6 "Messages"). */
val STEERABLE_ROLES = setOf("spec-writer", "builder")
/** The session kind of the agent a run's owner talks to while the run needs a human (not a pipeline role). */
const val ADVISOR_KIND = "advisor"
/** Interactive session kinds: kept alive between turns, resumed with `--resume`, stopped by the idle reaper. */
val INTERACTIVE_KINDS = setOf("chat", ADVISOR_KIND)
val DEFAULT_PIPELINE_MODELS = mapOf(
    "spec-writer" to "opus", "builder" to "opus", "security" to "opus",
    "ux" to "sonnet", "ui-design" to "sonnet", "code" to "sonnet", "approver" to "opus",
)

@Serializable
data class SoftwareRequestDto(
    val id: String, val name: String, val reason: String, val repo: String, val requestedBy: String,
    val sessionId: String?, val status: String, val note: String?, val createdAt: String, val resolvedAt: String?,
)

/** What a user chose for themselves; null / missing = use the admin default. */
@Serializable
data class PreferenceOverridesDto(
    val chatModel: String? = null,
    val pipelineModels: Map<String, String> = emptyMap(),
    val maxFixRounds: Int? = null,
)

/** One effective value and where it comes from. */
@Serializable data class EffectiveModelDto(val value: String, val source: String)

@Serializable
data class EffectiveRoundsDto(
    val value: Int, val source: String,
    /** The admin's default, which is also the largest value a user may choose. */
    val max: Int,
    /** The user's choice was above the current maximum and is capped to it. */
    val clamped: Boolean,
)

@Serializable data class EffectivePreferencesDto(val chatModel: EffectiveModelDto, val pipelineModels: Map<String, EffectiveModelDto>, val maxFixRounds: EffectiveRoundsDto)

@Serializable
data class PreferencesDto(val overrides: PreferenceOverridesDto, val effective: EffectivePreferencesDto, val suggestions: List<String>)

/** One Claude limit window of the caller (`GET /api/me/usage`); [utilization] is a fraction, may exceed 1. */
@Serializable
data class UsageWindowDto(val type: String, val utilization: Double?, val status: String?, val resetsAt: String?, val observedAt: String)

/** What the caller's agents cost through Agern: today and this calendar month, UTC ([day] `YYYY-MM-DD`, [month] `YYYY-MM`). */
@Serializable
data class UsageSpendDto(val todayUsd: Double, val monthUsd: Double, val day: String, val month: String)

/** The caller's own Claude usage; [windows] is empty unless [credentialKind] is `oauth_token`. */
@Serializable
data class ClaudeUsageDto(val credentialKind: String?, val windows: List<UsageWindowDto>, val spend: UsageSpendDto)

/** A file or image attached to a chat message (also the upload response). */
@Serializable
data class AttachmentDto(
    val id: String, val name: String, val mediaType: String, val size: Long,
    /** `image` = png/jpeg/gif/webp (shown inline); `file` = anything else (download only). */
    val kind: String,
    val width: Int?, val height: Int?,
    /** How the model sees an image: `original`, `downscaled` copy, or `none` (only as a file). Always `none` for files. */
    val inline: String,
    /** Why an image was downscaled or is not shown to the model inline; null otherwise. */
    val note: String?,
)

@Serializable data class AttachmentLimitsDto(val maxFileBytes: Long, val maxMessageBytes: Long, val maxCount: Int, val imageTypes: List<String>)
/** Answer to a message for a running pipeline agent (`POST /api/sessions/{id}/input`, `factory_send_to_agent`). */
@Serializable data class AgentMessageDto(
    /** Always `queued`: the CLI picks it up at the agent's next step; a `system` event with this id confirms delivery. */
    val status: String,
    val messageId: String,
    val sessionId: String,
    val runId: String?,
    val role: String,
    val note: String,
)

@Serializable data class ClientConfigDto(val attachments: AttachmentLimitsDto, val images: ImageConfigDto)

/** An image provider of the catalogue (`GET /api/config`); the web renders whatever is listed. */
@Serializable data class ImageProviderInfoDto(
    val id: String, val label: String, val models: List<String>, val defaultModel: String, val supportsTransparency: Boolean,
    val keyPlaceholder: String,
    /** `api_key` or `device_login` (connected through the `/me/image-provider/codex/login` flow). */
    val auth: String = "api_key",
    val available: Boolean = true,
    val unavailableReason: String? = null,
)

/** A Codex device login of the caller (`/api/me/image-provider/codex/login`); URL and code only while `pending`. */
@Serializable data class CodexLoginDto(
    val status: String,
    val verificationUrl: String? = null,
    val userCode: String? = null,
    val expiresAt: String? = null,
    val error: String? = null,
)
@Serializable data class ImageLimitsDto(val maxPerRequest: Int, val maxPerTurn: Int, val maxPerStage: Int, val maxEdge: Int)
@Serializable data class ImageConfigDto(val providers: List<ImageProviderInfoDto>, val limits: ImageLimitsDto)

/** Where an image is selected for use; [locked] while a run that took it is not failed or cancelled. */
@Serializable data class ImageSelectionDto(val issueNumber: Int, val name: String, val usedByRunId: String?, val locked: Boolean)

/** A generated image (docs/ARCHITECTURE.md §6 "Generated images"); bytes at `GET /api/images/{id}`. */
@Serializable data class GeneratedImageDto(
    val id: String, val repo: String, val origin: String, val sessionId: String, val owner: OwnerDto,
    val provider: String, val model: String, val prompt: String, val purpose: String,
    val mediaType: String, val size: Long, val width: Int, val height: Int, val transparent: Boolean, val createdAt: String,
    val selection: ImageSelectionDto?,
)

@Serializable data class FeedFrame(
    val type: String,
    val session: AgentSessionDto? = null,
    val run: RunDto? = null,
    val repo: String? = null,
    val issue: IssueDto? = null,
    /** `session_closed`: the id of the chat session that was closed. */
    val sessionId: String? = null,
    /** `release`: the release after the change. */
    val release: ReleaseDto? = null,
    /** `release_deleted`: the id of the deleted release. */
    val releaseId: String? = null,
    /** `sandbox_approval`: the project's approval after the change; null when it was revoked. */
    val approval: SandboxApprovalDto? = null,
    /** `sandboxes` (admins only): the run whose sandbox changed. */
    val runId: String? = null,
    /** `sandboxes`: `created`, `removed` or `stopped`. */
    val change: String? = null,
)

object RunStatus {
    const val QUEUED = "queued"; const val SPEC = "spec"; const val BUILDING = "building"
    const val REVIEWING = "reviewing"; const val APPROVING = "approving"; const val MERGED = "merged"
    const val NEEDS_HUMAN = "needs_human"; const val FAILED = "failed"; const val CANCELLED = "cancelled"
    /** Passed every reviewer under [MergePolicy.GITHUB]: the pull request waits on GitHub; Agern follows it. */
    const val READY = "ready"
    val ACTIVE = setOf(QUEUED, SPEC, BUILDING, REVIEWING, APPROVING, NEEDS_HUMAN)
    val TERMINAL = setOf(MERGED, FAILED, CANCELLED, READY)
    val ALL = ACTIVE + TERMINAL
}

/** Release states (docs/ARCHITECTURE.md §6 "Releases"). */
object ReleaseStatus {
    const val PLANNING = "planning"; const val SCHEDULED = "scheduled"
    const val BUILDING = "building"; const val PAUSED = "paused"; const val CANCELLED = "cancelled"
    /** Nothing is left to build (every issue merged, held or skipped); waits for its owner's approval. */
    const val READY = "ready"
    /** Merged into the default branch; final and read-only (history). */
    const val RELEASED = "released"
    /** Not started yet: planning rules apply (a scheduled release is still a plan with a start time). */
    val PLANNED = setOf(PLANNING, SCHEDULED)
    /** Started: has an owner and a branch. */
    val STARTED = setOf(BUILDING, PAUSED, READY, RELEASED, CANCELLED)
    /** Holds the project's one building slot; a due scheduled release of the same project waits behind it. */
    val ACTIVE = setOf(BUILDING, PAUSED)
    /** Final: kept as history. */
    val FINISHED = setOf(RELEASED, CANCELLED)
}

/** How a `ready` release reaches the default branch (docs/ARCHITECTURE.md §6 "Releasing", "Release mode"). */
object ReleaseMode {
    /** Waits in `ready` for its owner's approval (the default). */
    const val MANUAL = "manual"
    /** Merged by Agern with the owner's token once it is complete, conflict-free and its checks are green. */
    const val AUTOMATIC = "automatic"
    val ALL = setOf(MANUAL, AUTOMATIC)
}

/** The checks (commit statuses and check runs) on the release pull request's head commit. */
object ReleaseChecksState {
    /** Not read yet, or could not be read. */
    const val UNKNOWN = "unknown"
    /**
     * The head commit has no checks yet, but was first seen less than the settle period ago: CI may not have registered
     * its checks yet. Not green.
     */
    const val AWAITING = "awaiting"
    /** The head commit has had no checks at all for the whole settle period: counts as green. */
    const val NONE = "none"
    const val PENDING = "pending"; const val PASSED = "passed"; const val FAILED = "failed"
    val GREEN = setOf(NONE, PASSED)
}

/** Whether the release pull request merges cleanly into the default branch, as last checked. */
object ReleaseMergeState {
    const val UNKNOWN = "unknown"; const val CLEAN = "clean"; const val CONFLICT = "conflict"
}

/** How a release is merged into the default branch: never squashed, so each issue stays one commit. */
object ReleaseMergeMethod {
    const val REBASE = "rebase"; const val MERGE = "merge"; const val UNAVAILABLE = "unavailable"
}

/** The state of an issue within a release. */
object ReleaseIssueState {
    const val WAITING = "waiting"; const val RUNNING = "running"; const val MERGED = "merged"
    const val HELD = "held"; const val SKIPPED = "skipped"
}

/** Why a release is paused. */
object PauseReason {
    const val MANUAL = "manual"; const val USAGE_LIMIT = "usage_limit"; const val CREDENTIALS = "credentials"
}

object SessionStatus {
    const val STARTING = "starting"; const val RUNNING = "running"; const val IDLE = "idle"
    const val DONE = "done"; const val FAILED = "failed"; const val STOPPED = "stopped"
}

fun issueKindFromLabels(labels: List<String>): String {
    val l = labels.map { it.lowercase() }
    return when {
        "bug" in l -> "bug"
        "enhancement" in l || "feature" in l -> "feature"
        else -> "task"
    }
}
