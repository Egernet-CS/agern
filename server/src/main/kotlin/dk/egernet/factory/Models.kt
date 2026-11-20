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
)

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
)

@Serializable data class ReleaseStartFailureDto(val at: String, val owner: OwnerDto, val reason: String)

@Serializable data class OwnerDto(val username: String, val displayName: String)

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
)

@Serializable
data class RunDto(
    val id: String, val repo: String, val issueNumber: Int, val issueTitle: String, val issueKind: String,
    val status: String, val round: Int, val maxRounds: Int, val branch: String?, val prNumber: Int?,
    val prUrl: String?, val owner: OwnerDto, val startedBySessionId: String?, val error: String?,
    val createdAt: String, val updatedAt: String, val endedAt: String?, val stages: List<StageDto>,
    /** The release this run builds an issue for; null for a single run (or when the release was deleted). */
    val releaseId: String? = null,
    /** The branch the run starts from and merges into; null = the repository's default branch. */
    val baseBranch: String? = null,
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
/** Pipeline roles whose running agent accepts messages from the run's owner (docs/ARCHITECTURE.md §6 "Messages"). */
val STEERABLE_ROLES = setOf("spec-writer", "builder")
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

@Serializable data class ClientConfigDto(val attachments: AttachmentLimitsDto)

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
)

object RunStatus {
    const val QUEUED = "queued"; const val SPEC = "spec"; const val BUILDING = "building"
    const val REVIEWING = "reviewing"; const val APPROVING = "approving"; const val MERGED = "merged"
    const val NEEDS_HUMAN = "needs_human"; const val FAILED = "failed"; const val CANCELLED = "cancelled"
    val ACTIVE = setOf(QUEUED, SPEC, BUILDING, REVIEWING, APPROVING, NEEDS_HUMAN)
    val TERMINAL = setOf(MERGED, FAILED, CANCELLED)
    val ALL = ACTIVE + TERMINAL
}

/** Release states (docs/ARCHITECTURE.md §6 "Releases"). Later steps add `ready` and `released`. */
object ReleaseStatus {
    const val PLANNING = "planning"; const val SCHEDULED = "scheduled"
    const val BUILDING = "building"; const val PAUSED = "paused"; const val CANCELLED = "cancelled"
    /** Not started yet: planning rules apply (a scheduled release is still a plan with a start time). */
    val PLANNED = setOf(PLANNING, SCHEDULED)
    /** Started: has an owner and a branch. */
    val STARTED = setOf(BUILDING, PAUSED, CANCELLED)
    /** Holds the project's one building slot; a due scheduled release of the same project waits behind it. */
    val ACTIVE = setOf(BUILDING, PAUSED)
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
