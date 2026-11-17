package dk.egernet.factory

import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64

/** All configuration comes from environment variables (see docs/ARCHITECTURE.md §3). */
data class Config(
    val port: Int = 8080,
    val host: String = "0.0.0.0",
    val dbUrl: String = "jdbc:postgresql://localhost:5432/factory",
    val dbUser: String = "factory",
    val dbPassword: String = "factory",
    val masterKey: ByteArray,
    val githubOrg: String,
    val authProviders: List<String> = listOf("os"),
    /** FACTORY_2FA: true = required (default), false = off (only allowed with dev as the sole provider). */
    val twoFactorRequired: Boolean = true,
    val admins: Set<String> = emptySet(),
    val ldap: LdapConfig = LdapConfig(),
    val devUsers: Map<String, String> = emptyMap(),
    val dataDir: Path = Paths.get("./data").toAbsolutePath().normalize(),
    /** null: serve the frontend embedded on the classpath under `web/` (if any). */
    val webDir: Path? = null,
    val claudeBin: String = "claude",
    val publicUrl: String = "http://localhost:8080",
    val allowedOrigins: Set<String> = emptySet(),
    val maxAgentsPerUser: Int = 8,
    val chatIdleMinutes: Long = 30,
    /** FACTORY_STAGE_TIMEOUT_MINUTES: wall-clock limit for one pipeline stage agent. */
    val stageTimeoutMs: Long = 60 * 60_000L,
    val githubApiUrl: String = "https://api.github.com",
    val fakeGithub: Boolean = false,
    val pamService: String? = null,
    val agentPath: String = defaultAgentPath(),
) {
    /** Agents reach the MCP endpoint over loopback, never through the public URL. */
    val mcpUrl: String get() = "http://127.0.0.1:$port/mcp"
    val publicOrigin: String get() = originOf(publicUrl)

    /**
     * Agents always reach MCP on 127.0.0.1. When FACTORY_HOST is a specific address that is not 127.0.0.1
     * (e.g. a LAN address), the server listens on 127.0.0.1:<port> as well.
     */
    val needsLoopbackListener: Boolean get() {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        if (h == "*" || h.isEmpty()) return false
        val addr = runCatching { java.net.InetAddress.getByName(h) }.getOrNull() ?: return true
        return !(addr.isAnyLocalAddress || addr.hostAddress == "127.0.0.1")
    }
    val secureCookies: Boolean get() = publicUrl.lowercase().startsWith("https://")

    override fun toString(): String =
        "Config(host=$host, port=$port, dbUrl=$dbUrl, dbUser=$dbUser, githubOrg=$githubOrg, auth=$authProviders, " +
            "admins=$admins, dataDir=$dataDir, webDir=$webDir, claudeBin=$claudeBin, publicUrl=$publicUrl, " +
            "maxAgentsPerUser=$maxAgentsPerUser, chatIdleMinutes=$chatIdleMinutes, stageTimeoutMs=$stageTimeoutMs, fakeGithub=$fakeGithub)"

    companion object {
        fun defaultAgentPath(): String =
            System.getenv("PATH")?.takeIf { it.isNotBlank() } ?: "/usr/local/bin:/usr/bin:/bin"

        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun get(name: String): String? = env[name]?.trim()?.takeIf { it.isNotEmpty() }
            fun required(name: String): String =
                get(name) ?: throw IllegalStateException("Environment variable $name is required")

            val keyB64 = required("FACTORY_MASTER_KEY")
            val key = try {
                Base64.getDecoder().decode(keyB64)
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("FACTORY_MASTER_KEY is not valid base64")
            }
            require(key.size == 32) { "FACTORY_MASTER_KEY must decode to exactly 32 bytes (got ${key.size})" }

            val auth = (get("FACTORY_AUTH") ?: "os").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            for (p in auth) require(p in setOf("os", "ldap", "dev")) { "Unknown auth provider '$p' in FACTORY_AUTH" }

            val twoFactor = (get("FACTORY_2FA") ?: "required").lowercase()
            require(twoFactor in setOf("required", "off")) { "FACTORY_2FA must be 'required' or 'off'" }
            if (twoFactor == "off") require(auth == listOf("dev")) {
                "FACTORY_2FA=off is only accepted when 'dev' is the sole login provider (FACTORY_AUTH=dev)"
            }

            val devUsers = if ("dev" in auth) parseDevUsers(get("FACTORY_DEV_USERS")) else emptyMap()
            val fakeGithub = get("FACTORY_DEV_FAKE_GITHUB")?.lowercase() in setOf("1", "true", "yes")
            if (fakeGithub) require("dev" in auth) { "FACTORY_DEV_FAKE_GITHUB requires 'dev' in FACTORY_AUTH" }

            return Config(
                port = get("FACTORY_PORT")?.toInt() ?: 8080,
                host = get("FACTORY_HOST") ?: "0.0.0.0",
                dbUrl = get("FACTORY_DB_URL") ?: "jdbc:postgresql://localhost:5432/factory",
                dbUser = get("FACTORY_DB_USER") ?: "factory",
                dbPassword = get("FACTORY_DB_PASSWORD") ?: "factory",
                masterKey = key,
                githubOrg = required("FACTORY_GITHUB_ORG"),
                authProviders = auth,
                twoFactorRequired = twoFactor == "required",
                admins = (get("FACTORY_ADMINS") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                ldap = LdapConfig(
                    url = get("FACTORY_LDAP_URL"),
                    userDnTemplate = get("FACTORY_LDAP_USER_DN_TEMPLATE"),
                    baseDn = get("FACTORY_LDAP_BASE_DN"),
                    userFilter = get("FACTORY_LDAP_USER_FILTER") ?: "(uid={0})",
                    bindDn = get("FACTORY_LDAP_BIND_DN"),
                    bindPassword = get("FACTORY_LDAP_BIND_PASSWORD"),
                ),
                devUsers = devUsers,
                dataDir = Paths.get(get("FACTORY_DATA_DIR") ?: "./data").toAbsolutePath().normalize(),
                webDir = get("FACTORY_WEB_DIR")?.let { Paths.get(it).toAbsolutePath().normalize() },
                claudeBin = get("FACTORY_CLAUDE_BIN") ?: "claude",
                publicUrl = get("FACTORY_PUBLIC_URL") ?: "http://localhost:8080",
                allowedOrigins = (get("FACTORY_ALLOWED_ORIGINS") ?: "").split(',').map { originOf(it.trim()) }
                    .filter { it.isNotEmpty() }.toSet(),
                maxAgentsPerUser = get("FACTORY_MAX_AGENTS_PER_USER")?.toInt() ?: 8,
                chatIdleMinutes = get("FACTORY_CHAT_IDLE_MINUTES")?.toLong() ?: 30,
                stageTimeoutMs = (get("FACTORY_STAGE_TIMEOUT_MINUTES")?.toLongOrNull()
                    ?.also { require(it in 1..24 * 60) { "FACTORY_STAGE_TIMEOUT_MINUTES must be between 1 and 1440" } }
                    ?: (get("FACTORY_STAGE_TIMEOUT_MINUTES")?.let { throw IllegalArgumentException("FACTORY_STAGE_TIMEOUT_MINUTES must be a whole number of minutes") })
                    ?: 60) * 60_000L,
                githubApiUrl = get("FACTORY_GITHUB_API_URL") ?: "https://api.github.com",
                fakeGithub = fakeGithub,
                pamService = get("FACTORY_PAM_SERVICE"),
                agentPath = get("FACTORY_AGENT_PATH") ?: defaultAgentPath(),
            )
        }

        /** `https://Host:8443/path` -> `https://host:8443` (default ports dropped). */
        fun originOf(url: String): String {
            if (url.isBlank()) return ""
            return try {
                val u = java.net.URI(url.trim())
                val scheme = u.scheme?.lowercase() ?: return ""
                val host = u.host?.lowercase() ?: return ""
                val port = u.port.takeIf { it != -1 && !(scheme == "http" && it == 80) && !(scheme == "https" && it == 443) }
                "$scheme://$host" + (port?.let { ":$it" } ?: "")
            } catch (e: Exception) {
                ""
            }
        }

        fun parseDevUsers(s: String?): Map<String, String> =
            (s ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.associate {
                val i = it.indexOf(':')
                require(i > 0) { "FACTORY_DEV_USERS entries must be user:password" }
                it.substring(0, i) to it.substring(i + 1)
            }
    }
}

data class LdapConfig(
    val url: String? = null,
    val userDnTemplate: String? = null,
    val baseDn: String? = null,
    val userFilter: String = "(uid={0})",
    val bindDn: String? = null,
    val bindPassword: String? = null,
) {
    override fun toString() = "LdapConfig(url=$url, userDnTemplate=$userDnTemplate, baseDn=$baseDn, bindDn=$bindDn)"
}
