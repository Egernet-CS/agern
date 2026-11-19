package dk.egernet.factory.mcp

import dk.egernet.factory.security.Tokens
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What an MCP bearer token may do: it belongs to one agent process (session) of one owner in one repo. */
data class McpGrant(
    val sessionId: UUID,
    val ownerId: UUID,
    val repo: String,
    val runId: UUID?,
    val tools: Set<String>,
)

/** Per-process bearer tokens. Kept only in memory: they die with the process (and with the server). */
class McpRegistry {
    private val grants = ConcurrentHashMap<String, McpGrant>()

    fun issue(grant: McpGrant): String {
        val token = Tokens.random(32)
        grants[Tokens.sha256Hex(token)] = grant
        return token
    }

    fun lookup(token: String): McpGrant? = grants[Tokens.sha256Hex(token)]

    fun revoke(token: String) {
        grants.remove(Tokens.sha256Hex(token))
    }

    companion object {
        val CHAT_TOOLS = setOf(
            "factory_list_issues", "factory_get_issue", "factory_create_issue", "factory_start_run", "factory_list_runs",
            "factory_get_run", "factory_approve_run", "factory_cancel_run", "factory_send_to_agent", "factory_request_software",
            "factory_list_releases", "factory_create_release", "factory_assign_issues", "factory_unassign_issues",
        )
        val STAGE_TOOLS = setOf("factory_get_issue", "factory_request_software")
    }
}
