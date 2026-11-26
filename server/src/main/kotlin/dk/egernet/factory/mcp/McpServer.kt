package dk.egernet.factory.mcp

import dk.egernet.factory.ApiException
import dk.egernet.factory.AppJson
import dk.egernet.factory.FactoryService
import dk.egernet.factory.ReleaseService
import dk.egernet.factory.RunDto
import dk.egernet.factory.db.SessionStore
import dk.egernet.factory.db.UserStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Minimal MCP server (streamable HTTP transport, no sessions): initialize, notifications/initialized, ping, tools/list,
 * tools/call. Every call acts as the owner of the agent process the bearer token belongs to, or as the user of a personal
 * token in its project ([McpAuthenticator], docs/ARCHITECTURE.md §7 "Personal tokens"). [handle] answers one request
 * body with one JSON body; `POST /mcp` (api/Api.kt) authenticates first ([authenticate]) and turns a slow answer into an
 * event stream with progress notifications ([progressToken]) so the client does not give up on it.
 */
class McpServer(
    private val registry: McpRegistry,
    private val users: UserStore,
    private val sessions: SessionStore,
    private val factory: FactoryService,
    private val releases: ReleaseService,
    private val version: String,
    private val sandboxes: dk.egernet.factory.sandbox.SandboxManager,
    private val screenshots: dk.egernet.factory.agent.Screenshots,
    private val images: dk.egernet.factory.images.ImageService,
    private val auth: McpAuthenticator,
    cfg: dk.egernet.factory.Config,
) {
    private val publicUrl = cfg.publicUrl.trimEnd('/')
    private val org = cfg.githubOrg
    private val instance = McpIdentity.instance(cfg.publicUrl)
    private val log = LoggerFactory.getLogger(McpServer::class.java)

    private data class Tool(val name: String, val description: String, val schema: JsonObject)

    private fun schema(vararg props: Triple<String, String, String>, required: List<String> = emptyList()) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            for ((name, type, desc) in props) putJsonObject(name) {
                if (type == "state") {
                    put("type", "string")
                    putJsonArray("enum") { add(JsonPrimitive("open")); add(JsonPrimitive("closed")); add(JsonPrimitive("all")) }
                } else if (type == "role") {
                    put("type", "string")
                    putJsonArray("enum") { add(JsonPrimitive("spec-writer")); add(JsonPrimitive("builder")) }
                } else if (type == "kind") {
                    put("type", "string")
                    putJsonArray("enum") { add(JsonPrimitive("feature")); add(JsonPrimitive("bug")); add(JsonPrimitive("task")) }
                } else if (type == "format") {
                    put("type", "string")
                    putJsonArray("enum") { add(JsonPrimitive("png")); add(JsonPrimitive("jpeg")); add(JsonPrimitive("webp")) }
                } else if (type == "integers") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "integer") }
                } else put("type", type)
                put("description", desc)
            }
        }
        putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        put("additionalProperties", false)
    }

    private val tools = listOf(
        Tool("factory_list_issues", "List issues of this project's GitHub repository (pull requests excluded).",
            schema(Triple("state", "state", "open (default), closed or all"))),
        Tool("factory_get_issue", "Get one issue of this project with its body and comments.",
            schema(Triple("number", "integer", "Issue number"), required = listOf("number"))),
        Tool("factory_create_issue", "Create an issue in this project's repository.",
            schema(Triple("title", "string", "Issue title"), Triple("body", "string", "Issue body (Markdown)"),
                Triple("kind", "kind", "feature, bug or task"), required = listOf("title", "body", "kind"))),
        Tool("factory_start_run", "Start a pipeline run (spec → build → 4 reviews → approval → merge) for an issue of this project. " +
            "Runs with the credentials of the user who owns this chat.",
            schema(Triple("issueNumber", "integer", "Issue number"), required = listOf("issueNumber"))),
        Tool("factory_list_runs", "List pipeline runs of this project, newest first.",
            schema(Triple("activeOnly", "boolean", "Only runs that are not finished"))),
        Tool("factory_get_run", "Get a pipeline run with its stages, verdicts and findings.",
            schema(Triple("runId", "string", "Run id"), required = listOf("runId"))),
        Tool("factory_approve_run", "Merge a run that waits in needs_human. Only the run's owner may do this.",
            schema(Triple("runId", "string", "Run id"), required = listOf("runId"))),
        Tool("factory_cancel_run", "Cancel a pipeline run.",
            schema(Triple("runId", "string", "Run id"), required = listOf("runId"))),
        Tool("factory_send_to_agent", "Pass a message from the person you are talking to (who must own the run) to a running " +
            "pipeline agent — the spec-writer or the builder — e.g. \"tell the builder the button should be blue\". Address it " +
            "by runId (+ role; without role: the run's running spec-writer or builder) or by sessionId. The message is queued " +
            "and picked up at the agent's next step (after its current tool call). Reviewers, the approver and finished agents " +
            "do not accept messages. The message is not posted on GitHub.",
            schema(Triple("runId", "string", "Run id"), Triple("role", "role", "spec-writer or builder (optional with runId)"),
                Triple("sessionId", "string", "The agent's session id (instead of runId + role)"),
                Triple("message", "string", "The message, as the person meant it"), required = listOf("message"))),
        Tool("factory_list_releases", "List the releases planned in this project, with their ids and their issues in build order.",
            schema()),
        Tool("factory_create_release", "Create a release (a named plan of issues) in this project. It starts nothing. " +
            "Only create one when the person asks for it.",
            schema(Triple("name", "string", "Release name, e.g. '1.4' or 'chat-cleanup': letters, digits, '.', '_', '-'"),
                Triple("description", "string", "Optional description"), required = listOf("name"))),
        Tool("factory_assign_issues", "Put open issues into a release of this project, appended in the given order. " +
            "An issue that is in another release is moved. In a workspace project an issue needs exactly one member label (none " +
            "means the workspace repository itself in a submodule workspace); a refusal says which label to add.",
            schema(Triple("release", "string", "Release name or id"), Triple("issueNumbers", "integers", "Issue numbers"),
                required = listOf("release", "issueNumbers"))),
        Tool("factory_unassign_issues", "Take issues out of whatever release of this project they are in.",
            schema(Triple("issueNumbers", "integers", "Issue numbers"), required = listOf("issueNumbers"))),
        Tool("factory_approve_release", "Merge a release that is ready to release into the default branch (one commit per " +
            "issue), close its issues and delete its branch. Only the release's owner may do this. Call it only when the person " +
            "explicitly tells you to release that specific release. In a workspace this merges one pull request per touched " +
            "repository, members first, then the workspace repository with its submodule pointers.",
            schema(Triple("release", "string", "Release name or id"), required = listOf("release"))),
        Tool("factory_sandbox_info", "Whether this run has a sandbox (a container with the project's toolchain and services, " +
            "defined in .agern/sandbox.yml and approved by an admin) and, if not, why. When it has one: image, platforms (other " +
            "images to test on), the user commands run as, services with their host names, ports and readiness, caches and limits. " +
            "For a reviewer it describes the run's read-only review sandbox; for the spec-writer it is for planning only.",
            schema()),
        Tool("factory_sandbox_exec", "Run a shell command in this run's sandbox, e.g. a build or the tests. It runs as `sh -lc <command>` " +
            "in /workspace, which is your working directory (the same files, so edits on the host are visible at once; in a " +
            "reviewer's review sandbox /workspace is read-only and files for the host go to the output directory in factory_sandbox_info). The " +
            "sandbox is created on first use (this can take a few minutes). Returns { handle, exitCode, output (the tail), truncated, " +
            "durationMs, timedOut }. Use git on the host, not here. The call takes as long as the command, up to the time limit in " +
            "factory_sandbox_info; that is fine for builds and test suites. To keep working meanwhile, pass `background: true`: the " +
            "command starts and you get a handle at once; get the result with factory_sandbox_wait. One command runs at a time. " +
            "Never redirect output to a file and poll with `sleep`. If a call ever fails with a timeout on your side, the command " +
            "is still running: call factory_sandbox_wait without a handle.",
            schema(Triple("command", "string", "Shell command, e.g. './gradlew test' or 'npm ci && npm test'"),
                Triple("timeoutSeconds", "integer", "Optional time limit in seconds (default and maximum: the server's limit)"),
                Triple("platform", "string", "Optional: a platform from factory_sandbox_info (another image, e.g. 'jdk17'); default: the main image"),
                Triple("background", "boolean", "Optional: true starts the command and returns its handle at once; wait for it with factory_sandbox_wait"),
                required = listOf("command"))),
        Tool("factory_sandbox_wait", "Wait for a sandbox command (by handle; default: the most recently started one, also a " +
            "foreground one whose call was cut off) to end and return { running, exitCode, output (the tail), truncated, durationMs, " +
            "timedOut }. Without waitSeconds it waits until the command ends; waitSeconds limits the wait, and `running: true` " +
            "means it is still running: call it again.",
            schema(Triple("handle", "string", "Optional: the handle factory_sandbox_exec returned, e.g. 'cmd-1a2b3c4d'; default: the latest command"),
                Triple("waitSeconds", "integer", "Optional: wait at most this long (0: answer at once); default: until the command ends"))),
        Tool("factory_attach_screenshot", "Attach a screenshot of the running app to your review stage, so the people following " +
            "the run see what was built (on the run page; the pull request gets a note listing the captions). Only for the ux " +
            "and ui-design reviewers. The file must be a PNG, JPEG, GIF or WebP image inside your working directory; it is " +
            "copied and left as it is (delete it yourself afterwards, never commit it). The caption is one line saying what " +
            "the screenshot shows, including state and theme.",
            schema(Triple("path", "string", "Image file, relative to your working directory (or absolute, inside it)"),
                Triple("caption", "string", "What the screenshot shows, e.g. 'Settings › Tokens, empty state, dark theme' (at most 200 characters)"),
                required = listOf("path", "caption"))),
        Tool("factory_generate_image", "Have an image AI make graphics — icons, illustrations, logos, empty-state art, hero " +
            "images — with the image provider the user chose in Settings, billed to them. Use it whenever the request or issue asks " +
            "for graphics instead of drawing a placeholder; never when nothing calls for graphics. Write the prompt yourself from the " +
            "request and the project's existing visual style, colours, sizes and formats. Returns JSON with the files (`path`, " +
            "read it to look at the result), the remaining image budget and what to do next. Agern does not resize or convert.",
            schema(Triple("prompt", "string", "The image prompt: subject, style, colours, composition (1 to 4000 characters)"),
                Triple("purpose", "string", "What the image is for, e.g. 'app icon' or 'empty state of the issue list' (at most 200 characters)"),
                Triple("size", "string", "WIDTHxHEIGHT in pixels, default 1024x1024 (each edge from 256 up to the server's limit)"),
                Triple("format", "format", "png (default), jpeg or webp"),
                Triple("count", "integer", "How many variants, default 1 (at most the server's per-request limit)"),
                Triple("transparentBackground", "boolean", "A transparent background (png or webp; not every provider supports it)"),
                Triple("issueNumber", "integer", "Optional: the issue the images are meant for"),
                required = listOf("prompt", "purpose"))),
        Tool("factory_request_software", "Ask an administrator to install software on the factory host. " +
            "Use this instead of installing system-wide software yourself.",
            schema(Triple("name", "string", "Software name, e.g. 'ffmpeg' or 'JDK 21'"),
                Triple("reason", "string", "Why it is needed"), required = listOf("name", "reason"))),
    ).associateBy { it.name }

    /** Checks [bearer] from client address [remote]; see [McpAuthenticator]. */
    suspend fun authenticate(bearer: String?, remote: String): McpAuth = auth.authenticate(bearer, remote)

    /** The JSON-RPC error body for a refused bearer. */
    fun authError(a: McpAuth): String = when (a) {
        is McpAuth.Unauthorized -> rpcError(JsonNull, -32001, a.message).toString()
        is McpAuth.Throttled -> rpcError(JsonNull, -32001, "Too many failed attempts. Try again after ${a.until}.").toString()
        is McpAuth.Granted -> error("not an error")
    }

    /**
     * Result of handling one HTTP request body: null means 202 with no body (only notifications). Authenticates [bearer]
     * as coming from [remote] first (401, or 429 for a blocked address).
     */
    suspend fun handle(bearer: String?, body: String, remote: String = "local"): Pair<Int, String?> =
        when (val a = authenticate(bearer, remote)) {
            is McpAuth.Granted -> handle(a.grant, body)
            is McpAuth.Unauthorized -> 401 to authError(a)
            is McpAuth.Throttled -> 429 to authError(a)
        }

    /** Handles one request body for an already authenticated [grant]. */
    suspend fun handle(grant: McpGrant, body: String): Pair<Int, String?> {
        val parsed = try {
            AppJson.parseToJsonElement(body)
        } catch (e: Exception) {
            return 400 to rpcError(JsonNull, -32700, "Parse error").toString()
        }
        return when (parsed) {
            is JsonArray -> {
                val responses = parsed.mapNotNull { (it as? JsonObject)?.let { o -> handleOne(grant, o) } }
                if (responses.isEmpty()) 202 to null else 200 to JsonArray(responses).toString()
            }
            is JsonObject -> handleOne(grant, parsed)?.let { 200 to it.toString() } ?: (202 to null)
            else -> 400 to rpcError(JsonNull, -32600, "Invalid request").toString()
        }
    }

    private suspend fun handleOne(grant: McpGrant, req: JsonObject): JsonObject? {
        val id = req["id"]
        val method = req["method"]?.jsonPrimitive?.contentOrNull ?: return id?.let { rpcError(it, -32600, "Invalid request") }
        if (id == null || id is JsonNull) return null // notification (e.g. notifications/initialized)
        val params = req["params"] as? JsonObject ?: JsonObject(emptyMap())
        return try {
            when (method) {
                "initialize" -> rpcResult(id, buildJsonObject {
                    put("protocolVersion", params["protocolVersion"]?.jsonPrimitive?.contentOrNull ?: "2025-06-18")
                    putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
                    if (grant.personal == null) {
                        putJsonObject("serverInfo") { put("name", "agern"); put("version", version) }
                        put("instructions", "Agern software factory tools for the repository this agent works on.")
                    } else {
                        val owner = users.byId(grant.ownerId)
                        putJsonObject("serverInfo") {
                            put("name", McpIdentity.serverName(publicUrl, grant.repo))
                            put("title", "Agern · $org/${grant.repo} ($instance)")
                            put("version", version)
                        }
                        put("instructions", "Agern software factory at $publicUrl (instance $instance). Every tool of this server " +
                            "acts on the project $org/${grant.repo} on that instance only, as the Agern user ${owner?.username ?: "?"}. " +
                            "Other Agern servers registered in this client are other instances or other projects: pick the server " +
                            "whose project the person means.")
                    }
                })
                "ping" -> rpcResult(id, JsonObject(emptyMap()))
                "tools/list" -> rpcResult(id, buildJsonObject {
                    putJsonArray("tools") {
                        tools.values.filter { it.name in grant.tools }.forEach { t ->
                            val description = if (grant.personal == null) t.description else "[$org/${grant.repo} on $instance] ${t.description}"
                            addJsonObject { put("name", t.name); put("description", description); put("inputSchema", t.schema) }
                        }
                    }
                })
                "tools/call" -> rpcResult(id, if (grant.personal == null) callTool(grant, params) else callPersonal(grant, params))
                else -> rpcError(id, -32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            log.warn("MCP {} failed: {}", method, e.message)
            rpcError(id, -32603, "Internal error")
        }
    }

    /** A tool call with a personal token: logged with the token and user, never with the token itself or the arguments. */
    private suspend fun callPersonal(grant: McpGrant, params: JsonObject): JsonObject {
        val token = grant.personal!!
        val name = params["name"]?.jsonPrimitive?.contentOrNull
        var outcome = "error"
        try {
            return callTool(grant, params).also { if (it["isError"] == null) outcome = "ok" }
        } finally {
            log.info("MCP personal token call: tool={} token={} (\"{}\") user={} repo={} outcome={}",
                name?.take(80), token.id, token.name, users.byId(grant.ownerId)?.username, grant.repo, outcome)
        }
    }

    private suspend fun callTool(grant: McpGrant, params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.contentOrNull ?: return toolError("Missing tool name")
        if (name !in grant.tools || name !in tools) return toolError("Tool $name is not available to this agent")
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val owner = users.byId(grant.ownerId) ?: return toolError("Owner not found")
        val repo = grant.repo
        fun str(k: String) = args[k]?.jsonPrimitive?.contentOrNull
        fun int(k: String) = args[k]?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.removePrefix("#")?.toIntOrNull() }
        fun uuid(k: String): UUID = str(k)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ApiException(io.ktor.http.HttpStatusCode.BadRequest, "bad_request", "$k must be a run id")
        /** Integers or strings like `"#5"`; null when missing or not all are issue numbers. */
        fun ints(k: String): List<Int>? {
            val el = args[k] ?: return null
            val items = (el as? JsonArray)?.toList() ?: listOf(el)
            return items.map { item ->
                val p = item as? JsonPrimitive ?: return null
                p.intOrNull ?: p.contentOrNull?.trim()?.trimEnd(',')?.removePrefix("#")?.toIntOrNull() ?: return null
            }
        }
        val runs = ListSerializer(RunDto.serializer())
        return try {
            val text: String = when (name) {
                "factory_list_issues" -> {
                    val workspace = factory.isWorkspace(owner, repo)
                    val noLabel = if (factory.isSubmoduleWorkspace(owner, repo)) " [workspace repository]" else " [no member label]"
                    factory.listIssues(owner, repo, str("state") ?: "open").joinToString("\n") {
                        "#${it.number} [${it.kind}] ${it.title}" + (it.activeRunId?.let { r -> " (active run $r)" } ?: "") +
                            (it.release?.let { r -> " [release ${r.name}]" } ?: "") +
                            (if (!workspace) "" else when (it.workspaceMembers.size) {
                                0 -> noLabel
                                1 -> " [member ${it.workspaceMembers.single().directory}]"
                                else -> " [several member labels]"
                            })
                    }.ifEmpty { "No issues." }
                }
                "factory_get_issue" -> factory.issueForAgent(owner, repo, int("number") ?: return toolError("number is required"))
                "factory_create_issue" -> {
                    if (grant.runId != null) return toolError("Not available to pipeline agents")
                    val i = factory.createIssue(owner, repo, str("title") ?: "", str("body") ?: "", str("kind") ?: "task")
                    "Created issue #${i.number}: ${i.htmlUrl}"
                }
                "factory_start_run" -> {
                    val r = factory.startRun(owner, repo, int("issueNumber") ?: return toolError("issueNumber is required"), grant.sessionId)
                    "Started run ${r.id} for #${r.issueNumber} (${r.status}). Follow it with factory_get_run."
                }
                "factory_list_runs" -> AppJson.encodeToString(runs,
                    factory.listRuns(owner, repo, args["activeOnly"]?.jsonPrimitive?.contentOrNull == "true").map { it.copy(stages = emptyList()) })
                "factory_get_run" -> {
                    val r = factory.visibleRun(owner, uuid("runId"))
                    if (r.repo != repo) return toolError("Run belongs to another project")
                    AppJson.encodeToString(RunDto.serializer(), factory.listRuns(owner, repo, false).firstOrNull { it.id == r.id.toString() }
                        ?: return toolError("Run not found"))
                }
                "factory_approve_run" -> {
                    val runId = uuid("runId")
                    if (grant.personal != null && factory.visibleRun(owner, runId).repo != repo) return toolError("Run belongs to another project")
                    factory.approveRun(owner, runId).let { "Run ${it.id}: ${it.status}" + (it.error?.let { e -> " — $e" } ?: "") }
                }
                "factory_cancel_run" -> {
                    val runId = uuid("runId")
                    if (grant.personal != null && factory.visibleRun(owner, runId).repo != repo) return toolError("Run belongs to another project")
                    factory.cancelRun(owner, runId).let { "Run ${it.id}: ${it.status}" }
                }
                "factory_send_to_agent" -> {
                    if (grant.runId != null) return toolError("Not available to pipeline agents")
                    val ids = listOf("runId", "sessionId").associateWith { k -> str(k)?.trim()?.takeIf { it.isNotEmpty() } }
                    val bad = ids.entries.firstOrNull { (_, v) -> v != null && runCatching { UUID.fromString(v) }.isFailure }
                    if (bad != null) return toolError("${bad.key} is not a valid id")
                    val m = factory.sendToAgent(
                        owner, ids["runId"]?.let(UUID::fromString), str("role")?.takeIf { it.isNotBlank() }, ids["sessionId"]?.let(UUID::fromString),
                        str("message") ?: return toolError("message is required"), viaChatSession = grant.sessionId, repo = repo,
                    )
                    "Queued for the ${m.role} (message ${m.messageId}, session ${m.sessionId}). It is delivered at the agent's next " +
                        "step, after its current tool call; the agent's transcript on the run page shows when it was picked up."
                }
                "factory_list_releases" -> releases.describe(owner, repo, withIds = true)
                "factory_create_release" -> {
                    if (grant.runId != null) return toolError("Not available to pipeline agents")
                    val r = releases.create(owner, repo, str("name") ?: return toolError("name is required"), str("description") ?: "")
                    "Created release \"${r.name}\" (id ${r.id})."
                }
                "factory_assign_issues" -> {
                    if (grant.runId != null) return toolError("Not available to pipeline agents")
                    val key = str("release")?.takeIf { it.isNotBlank() } ?: return toolError("release is required")
                    val numbers = ints("issueNumbers") ?: return toolError("issueNumbers must be an array of issue numbers")
                    val target = releases.find(owner, repo, key)
                    FactoryService.assignReply(target.name, releases.assign(owner, repo, numbers, target.id))
                }
                "factory_unassign_issues" -> {
                    if (grant.runId != null) return toolError("Not available to pipeline agents")
                    val numbers = ints("issueNumbers") ?: return toolError("issueNumbers must be an array of issue numbers")
                    val out = releases.assign(owner, repo, numbers, null)
                    if (out.removed.isEmpty()) "None of these issues is in a release."
                    else "Removed ${out.removed.joinToString(", ") { "#$it" }} from their release."
                }
                "factory_approve_release" -> {
                    if (grant.runId != null) return toolError("Not available to pipeline agents")
                    val key = str("release")?.takeIf { it.isNotBlank() } ?: return toolError("release is required")
                    FactoryService.approvedReply(releases.approve(owner, releases.find(owner, repo, key).id))
                }
                "factory_sandbox_info" -> {
                    val runId = grant.runId ?: return toolError("Sandboxes belong to pipeline runs; chats have none")
                    sandboxes.info(runId, grant.sandboxScope).let { if (it.isError) return toolError(it.text) else it.text }
                }
                "factory_sandbox_exec" -> {
                    val runId = grant.runId ?: return toolError("Sandboxes belong to pipeline runs; chats have none")
                    if (grant.sandboxScope == SandboxScope.PLAN) return toolError(dk.egernet.factory.sandbox.SandboxManager.PLAN_ONLY)
                    val timeout = args["timeoutSeconds"]?.let { el ->
                        (el as? JsonPrimitive)?.let { p -> p.contentOrNull?.toLongOrNull() } ?: return toolError("timeoutSeconds must be a whole number")
                    }
                    val platform = args["platform"]?.let { el -> (el as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return toolError("platform must be a string") }
                    val background = args["background"]?.let { el ->
                        (el as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return toolError("background must be true or false")
                    } ?: false
                    val sessionId = grant.sessionId ?: return toolError("Sandboxes belong to pipeline runs; chats have none")
                    sandboxes.exec(runId, sessionId, str("command") ?: return toolError("command is required"), timeout, platform, background, grant.sandboxScope)
                        .let { if (it.isError) return toolError(it.text) else it.text }
                }
                "factory_sandbox_wait" -> {
                    val runId = grant.runId ?: return toolError("Sandboxes belong to pipeline runs; chats have none")
                    if (grant.sandboxScope == SandboxScope.PLAN) return toolError(dk.egernet.factory.sandbox.SandboxManager.PLAN_ONLY)
                    val handle = args["handle"]?.let { el -> (el as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return toolError("handle must be a string") }
                    val waitSeconds = args["waitSeconds"]?.let { el ->
                        (el as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.takeIf { it >= 0 }
                            ?: return toolError("waitSeconds must be a whole number of seconds, 0 or more")
                    }
                    sandboxes.wait(runId, handle, waitSeconds, grant.sandboxScope).let { if (it.isError) return toolError(it.text) else it.text }
                }
                "factory_attach_screenshot" ->
                    screenshots.attach(grant.sessionId ?: return toolError("Only reviewers of a run can attach screenshots"), grant.runId, repo, owner, str("path"), str("caption"))
                "factory_generate_image" -> images.generate(grant, owner, args)
                "factory_request_software" -> {
                    val sr = factory.requestSoftware(owner, repo, grant.sessionId, str("name") ?: "", str("reason") ?: "")
                    "Request ${sr.id} filed for '${sr.name}'. An administrator will handle it; continue without it for now."
                }
                else -> return toolError("Unknown tool")
            }
            buildJsonObject { putJsonArray("content") { addJsonObject { put("type", "text"); put("text", text) } } }
        } catch (e: ApiException) {
            toolError(e.message)
        }
    }

    companion object {
        /**
         * The `params._meta.progressToken` of a request body that is a single JSON-RPC object, when it is a string or a
         * number; null for anything else (a batch, no token, another type, an unparsable body).
         */
        fun progressToken(body: String): JsonElement? {
            val req = runCatching { AppJson.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
            val meta = (req["params"] as? JsonObject)?.get("_meta") as? JsonObject ?: return null
            val token = meta["progressToken"] as? JsonPrimitive ?: return null
            if (token is JsonNull) return null
            return token.takeIf { it.isString || it.longOrNull != null || it.doubleOrNull != null }
        }
    }

    private fun toolError(msg: String) = buildJsonObject {
        putJsonArray("content") { addJsonObject { put("type", "text"); put("text", msg) } }
        put("isError", true)
    }

    private fun rpcResult(id: JsonElement, result: JsonElement) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("result", result)
    }

    private fun rpcError(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id)
        putJsonObject("error") { put("code", code); put("message", message) }
    }
}
