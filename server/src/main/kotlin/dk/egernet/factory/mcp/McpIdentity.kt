package dk.egernet.factory.mcp

import dk.egernet.factory.Config

/**
 * How a personal token's MCP server names itself, so several Agern instances and projects can sit side by side in one
 * Claude (docs/ARCHITECTURE.md §7 "Personal tokens").
 */
object McpIdentity {
    /** The host of [publicUrl], plus `:<port>` when the port is not the scheme's default, e.g. `agern.example`. */
    fun instance(publicUrl: String): String =
        Config.originOf(publicUrl).substringAfter("://").ifEmpty { "agern" }

    /**
     * The suggested server name in Claude: `<instance>-<repo>` in lower case, every run of characters outside `[a-z0-9]`
     * replaced by one `-`, prefixed with `agern-` unless it already starts with `agern`, at most 64 characters, e.g.
     * `agern-example-agern` for `https://agern.example` and `agern`, `agern-localhost-8080-demo-app` for
     * `http://localhost:8080` and `demo-app`.
     */
    fun serverName(publicUrl: String, repo: String): String {
        val base = "${instance(publicUrl)}-$repo".lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val prefixed = if (base.startsWith("agern")) base else "agern-$base".trimEnd('-')
        return prefixed.take(64).trimEnd('-')
    }
}
