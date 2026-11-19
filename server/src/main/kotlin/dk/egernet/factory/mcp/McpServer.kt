package dk.egernet.factory.mcp

import dk.egernet.factory.ApiException
import dk.egernet.factory.AppJson
import dk.egernet.factory.FactoryService
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
    private val version: String,
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
        val runs = ListSerializer(RunDto.serializer())
        return try {
            val text: String = when (name) {
                "factory_list_issues" -> factory.listIssues(owner, repo, str("state") ?: "open").joinToString("\n") {
                    "#${it.number} [${it.kind}] ${it.title}" + (it.activeRunId?.let { r -> " (active run $r)" } ?: "")
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
