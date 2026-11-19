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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Minimal MCP server (streamable HTTP transport, JSON responses only): initialize, notifications/initialized,
 * ping, tools/list, tools/call. Every call acts as the owner of the agent process the bearer token belongs to.
 */
class McpServer(
    private val registry: McpRegistry,
    private val users: UserStore,
    private val sessions: SessionStore,
    private val factory: FactoryService,
    private val releases: ReleaseService,
    private val version: String,
    private val sandboxes: dk.egernet.factory.sandbox.SandboxManager,
) {
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
            "An issue that is in another release is moved.",
            schema(Triple("release", "string", "Release name or id"), Triple("issueNumbers", "integers", "Issue numbers"),
                required = listOf("release", "issueNumbers"))),
        Tool("factory_unassign_issues", "Take issues out of whatever release of this project they are in.",
            schema(Triple("issueNumbers", "integers", "Issue numbers"), required = listOf("issueNumbers"))),
        Tool("factory_sandbox_info", "Whether this run has a sandbox (a container with the project's toolchain and services, " +
            "defined in .agern/sandbox.yml and approved by an admin) and, if not, why. When it has one: image, platforms (other " +
            "images to test on), the user commands run as, services with their host names, ports and readiness, caches and limits.",
            schema()),
        Tool("factory_sandbox_exec", "Run a shell command in this run's sandbox, e.g. a build or the tests. It runs as `sh -lc <command>` " +
            "in /workspace, which is your working directory (the same files, so edits on the host are visible at once). The " +
            "sandbox is created on first use (this can take a few minutes). Returns { exitCode, output (the tail), truncated, " +
            "durationMs, timedOut }. Use git on the host, not here.",
            schema(Triple("command", "string", "Shell command, e.g. './gradlew test' or 'npm ci && npm test'"),
                Triple("timeoutSeconds", "integer", "Optional time limit in seconds (default and maximum: the server's limit)"),
                Triple("platform", "string", "Optional: a platform from factory_sandbox_info (another image, e.g. 'jdk17'); default: the main image"),
                required = listOf("command"))),
        Tool("factory_request_software", "Ask an administrator to install software on the factory host. " +
            "Use this instead of installing system-wide software yourself.",
            schema(Triple("name", "string", "Software name, e.g. 'ffmpeg' or 'JDK 21'"),
                Triple("reason", "string", "Why it is needed"), required = listOf("name", "reason"))),
    ).associateBy { it.name }

    /** Result of handling one HTTP request body: null means 202 with no body (only notifications). */
    suspend fun handle(bearer: String?, body: String): Pair<Int, String?> {
        val grant = bearer?.let { registry.lookup(it) } ?: return 401 to rpcError(JsonNull, -32001, "Unauthorized").toString()
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
                    putJsonObject("serverInfo") { put("name", "agern"); put("version", version) }
                    put("instructions", "Agern software factory tools for the repository this agent works on.")
                })
                "ping" -> rpcResult(id, JsonObject(emptyMap()))
                "tools/list" -> rpcResult(id, buildJsonObject {
                    putJsonArray("tools") {
                        tools.values.filter { it.name in grant.tools }.forEach { t ->
                            addJsonObject { put("name", t.name); put("description", t.description); put("inputSchema", t.schema) }
                        }
                    }
                })
                "tools/call" -> rpcResult(id, callTool(grant, params))
                else -> rpcError(id, -32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            log.warn("MCP {} failed: {}", method, e.message)
            rpcError(id, -32603, "Internal error")
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
                "factory_list_issues" -> factory.listIssues(owner, repo, str("state") ?: "open").joinToString("\n") {
                    "#${it.number} [${it.kind}] ${it.title}" + (it.activeRunId?.let { r -> " (active run $r)" } ?: "") +
                        (it.release?.let { r -> " [release ${r.name}]" } ?: "")
                }.ifEmpty { "No issues." }
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
                "factory_approve_run" -> factory.approveRun(owner, uuid("runId")).let { "Run ${it.id}: ${it.status}" + (it.error?.let { e -> " — $e" } ?: "") }
                "factory_cancel_run" -> factory.cancelRun(owner, uuid("runId")).let { "Run ${it.id}: ${it.status}" }
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
                "factory_sandbox_info" -> {
                    val runId = grant.runId ?: return toolError("Sandboxes belong to pipeline runs; chats have none")
                    sandboxes.info(runId).let { if (it.isError) return toolError(it.text) else it.text }
                }
                "factory_sandbox_exec" -> {
                    val runId = grant.runId ?: return toolError("Sandboxes belong to pipeline runs; chats have none")
                    val timeout = args["timeoutSeconds"]?.let { el ->
                        (el as? JsonPrimitive)?.let { p -> p.contentOrNull?.toLongOrNull() } ?: return toolError("timeoutSeconds must be a whole number")
                    }
                    val platform = args["platform"]?.let { el -> (el as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return toolError("platform must be a string") }
                    sandboxes.exec(runId, grant.sessionId, str("command") ?: return toolError("command is required"), timeout, platform)
                        .let { if (it.isError) return toolError(it.text) else it.text }
                }
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
