package dk.egernet.factory.mcp

import dk.egernet.factory.security.Tokens
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * What an MCP bearer token may do. A per-process token belongs to one agent process ([sessionId]) of one owner in one
 * repo; a personal token ([personal]) belongs to a user and a project and has no session.
 */
data class McpGrant(
    val sessionId: UUID?,
    val ownerId: UUID,
    val repo: String,
    val runId: UUID?,
    val tools: Set<String>,
    val personal: PersonalTokenRef? = null,
)

/** The personal token a grant comes from: for attribution only (never the token or its hash). */
data class PersonalTokenRef(val id: UUID, val name: String)

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
            "factory_approve_release", "factory_generate_image",
        )
        /**
         * What a personal token offers (docs/ARCHITECTURE.md §7 "Personal tokens"): the chat tools without the image tool,
         * which depends on a chat session (files on the server, selection in the chat, a budget per chat message).
         */
        val PERSONAL_TOOLS = CHAT_TOOLS - "factory_generate_image"
        val STAGE_TOOLS = setOf("factory_get_issue", "factory_request_software")
    }
}
