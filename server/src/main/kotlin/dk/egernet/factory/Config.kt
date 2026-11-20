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
    /** FACTORY_ATTACHMENT_MAX_MB: largest single chat attachment. */
    val attachmentMaxBytes: Long = 20L shl 20,
    /** FACTORY_ATTACHMENT_MAX_MESSAGE_MB: all attachments of one message together. */
    val attachmentMaxMessageBytes: Long = 50L shl 20,
    /** FACTORY_ATTACHMENT_MAX_COUNT: attachments per message. */
    val attachmentMaxCount: Int = 10,
    /** FACTORY_SANDBOX*: per-run build/test sandboxes (docs/ARCHITECTURE.md §5 "Sandboxes"). */
    val sandbox: SandboxConfig = SandboxConfig(),
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

    /**
     * How text Agern writes on GitHub (comments, pull request bodies) points at a page of this installation: the link
     * when FACTORY_PUBLIC_URL is a public address, otherwise only [label]. An internal address (loopback, a private
     * network, a host name without a public domain) is useless to other readers and must not be published in a
     * repository that may be public.
     */
    fun pageRef(path: String, label: String): String =
        if (isInternalUrl(publicUrl)) "$label in Agern" else publicUrl.trimEnd('/') + "/" + path.trimStart('/')

    override fun toString(): String =
        "Config(host=$host, port=$port, dbUrl=$dbUrl, dbUser=$dbUser, githubOrg=$githubOrg, auth=$authProviders, " +
            "admins=$admins, dataDir=$dataDir, webDir=$webDir, claudeBin=$claudeBin, publicUrl=$publicUrl, " +
            "maxAgentsPerUser=$maxAgentsPerUser, chatIdleMinutes=$chatIdleMinutes, stageTimeoutMs=$stageTimeoutMs, fakeGithub=$fakeGithub, " +
            "attachments=${attachmentMaxCount}x${attachmentMaxBytes shr 20}MB/${attachmentMaxMessageBytes shr 20}MB, sandbox=$sandbox)"

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
            val sandbox = SandboxConfig.fromEnv(::get)
            if (sandbox.mode == "fake") require(fakeGithub) { "FACTORY_SANDBOX=fake is a development mode and requires FACTORY_DEV_FAKE_GITHUB" }

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
                attachmentMaxBytes = mb(get("FACTORY_ATTACHMENT_MAX_MB"), "FACTORY_ATTACHMENT_MAX_MB", 20),
                attachmentMaxMessageBytes = mb(get("FACTORY_ATTACHMENT_MAX_MESSAGE_MB"), "FACTORY_ATTACHMENT_MAX_MESSAGE_MB", 50),
                attachmentMaxCount = (get("FACTORY_ATTACHMENT_MAX_COUNT")?.toIntOrNull() ?: get("FACTORY_ATTACHMENT_MAX_COUNT")?.let {
                    throw IllegalArgumentException("FACTORY_ATTACHMENT_MAX_COUNT must be a whole number")
                } ?: 10).also { require(it in 1..20) { "FACTORY_ATTACHMENT_MAX_COUNT must be between 1 and 20" } },
                sandbox = sandbox,
            ).also {
                require(it.attachmentMaxMessageBytes >= it.attachmentMaxBytes) {
                    "FACTORY_ATTACHMENT_MAX_MESSAGE_MB must be at least FACTORY_ATTACHMENT_MAX_MB"
                }
            }
        }

        private fun mb(v: String?, name: String, default: Long): Long {
            val n = if (v == null) default else v.toLongOrNull() ?: throw IllegalArgumentException("$name must be a whole number of megabytes")
            require(n in 1..512) { "$name must be between 1 and 512" }
            return n shl 20
        }

        /** `https://Host:8443/path` -> `https://host:8443` (default ports dropped). */
        /**
         * True when [url]'s host is not publicly reachable by its name: loopback, private (RFC 1918), carrier-grade NAT,
         * link-local and unique-local addresses, `localhost`, single-label names and the usual internal suffixes. No DNS
         * lookup is made; an unreadable URL counts as internal.
         */
        fun isInternalUrl(url: String): Boolean {
            val host = try {
                java.net.URI(url.trim()).host?.lowercase()?.removePrefix("[")?.removeSuffix("]")
            } catch (e: Exception) {
                null
            } ?: return true
            if (host.contains(':')) { // IPv6 literal
                val a = runCatching { java.net.InetAddress.getByName(host) }.getOrNull() ?: return true
                val first = a.address[0].toInt() and 0xff
                return a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isAnyLocalAddress || (first and 0xfe) == 0xfc
            }
            val v4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""").matchEntire(host)
            if (v4 != null) {
                val (a, b) = v4.destructured.toList().map { it.toInt() }
                return a == 10 || a == 127 || a == 0 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                    (a == 169 && b == 254) || (a == 100 && b in 64..127)
            }
            return !host.contains('.') || INTERNAL_SUFFIXES.any { host.endsWith(it) }
        }

        private val INTERNAL_SUFFIXES = listOf(".local", ".localhost", ".lan", ".internal", ".intranet", ".home", ".home.arpa", ".corp", ".test")

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

/** Sandbox settings (docs/ARCHITECTURE.md §3, §5 "Sandboxes"). */
data class SandboxConfig(
    /**
     * `off` (default), `podman` (rootless Podman through its CLI) or `fake` (an in-memory runtime that only pretends to run
     * commands; development and end-to-end tests, only together with FACTORY_DEV_FAKE_GITHUB).
     */
    val mode: String = "off",
    /** FACTORY_SANDBOX_PODMAN: the podman binary. */
    val podmanBin: String = "podman",
    /** FACTORY_SANDBOX_PODMAN_URL: when set, every call is `podman --url <url> …` (podman-remote against `podman system service`). */
    val podmanUrl: String? = null,
    /**
     * FACTORY_SANDBOX_CGROUP_PARENT: when set, every container gets `--cgroup-parent <path>`. Rootless Podman with the
     * cgroupfs manager (the packaged `agern-sandbox.service`, which has no systemd user session) creates no cgroup for a
     * container without one, and then CPU/memory/pids limits are not applied (docs/ARCHITECTURE.md §9).
     */
    val cgroupParent: String? = null,
    /** FACTORY_SANDBOX_REGISTRIES: registries images may come from (host, or host:port), lower case. */
    val registries: List<String> = DEFAULT_REGISTRIES,
    /** FACTORY_SANDBOX_MAX_CPUS: upper limit (and default) for one container's CPUs. */
    val maxCpus: Double = 2.0,
    /** FACTORY_SANDBOX_MAX_MEMORY: upper limit (and default) for one container's memory, in bytes. */
    val maxMemoryBytes: Long = 4L shl 30,
    /** FACTORY_SANDBOX_MAX_CONCURRENT: sandboxes that may exist at the same time on this server. */
    val maxConcurrent: Int = 2,
    /** FACTORY_SANDBOX_EXEC_TIMEOUT_MINUTES: default and maximum for one command (also capped by the stage time limit). */
    val execTimeoutMs: Long = 30 * 60_000L,
    /** FACTORY_SANDBOX_MAX_PLATFORMS: platforms a definition may name besides its default image (0 forbids them). */
    val maxPlatforms: Int = 4,
) {
    val enabled: Boolean get() = mode != "off"

    override fun toString() = if (!enabled) "off" else
        "SandboxConfig(mode=$mode, podman=$podmanBin, url=${podmanUrl ?: "-"}, cgroupParent=${cgroupParent ?: "-"}, registries=$registries, maxCpus=$maxCpus, " +
            "maxMemory=${formatMemory(maxMemoryBytes)}, maxConcurrent=$maxConcurrent, execTimeoutMs=$execTimeoutMs, maxPlatforms=$maxPlatforms)"

    companion object {
        val DEFAULT_REGISTRIES = listOf("docker.io", "ghcr.io", "quay.io", "mcr.microsoft.com")
        private val REGISTRY_RE = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*(:[0-9]{1,5})?$")

        fun fromEnv(get: (String) -> String?): SandboxConfig {
            val mode = (get("FACTORY_SANDBOX") ?: "off").lowercase()
            require(mode in setOf("off", "podman", "fake")) { "FACTORY_SANDBOX must be 'off' or 'podman' ('fake' in development)" }
            val registries = get("FACTORY_SANDBOX_REGISTRIES")?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
                ?: DEFAULT_REGISTRIES
            for (r in registries) require(REGISTRY_RE.matches(r)) { "FACTORY_SANDBOX_REGISTRIES: '$r' is not a registry host name" }
            val cpus = get("FACTORY_SANDBOX_MAX_CPUS")?.let {
                it.toDoubleOrNull() ?: throw IllegalArgumentException("FACTORY_SANDBOX_MAX_CPUS must be a number")
            } ?: 2.0
            require(cpus > 0 && cpus <= 256) { "FACTORY_SANDBOX_MAX_CPUS must be between 0 and 256" }
            val memory = get("FACTORY_SANDBOX_MAX_MEMORY")?.let {
                parseMemory(it) ?: throw IllegalArgumentException("FACTORY_SANDBOX_MAX_MEMORY must be a size like 4g or 512m")
            } ?: (4L shl 30)
            require(memory >= MIN_MEMORY) { "FACTORY_SANDBOX_MAX_MEMORY must be at least 64m" }
            val concurrent = get("FACTORY_SANDBOX_MAX_CONCURRENT")?.let {
                it.toIntOrNull() ?: throw IllegalArgumentException("FACTORY_SANDBOX_MAX_CONCURRENT must be a whole number")
            } ?: 2
            require(concurrent in 1..100) { "FACTORY_SANDBOX_MAX_CONCURRENT must be between 1 and 100" }
            val execMinutes = get("FACTORY_SANDBOX_EXEC_TIMEOUT_MINUTES")?.let {
                it.toLongOrNull() ?: throw IllegalArgumentException("FACTORY_SANDBOX_EXEC_TIMEOUT_MINUTES must be a whole number of minutes")
            } ?: 30
            require(execMinutes in 1..24 * 60) { "FACTORY_SANDBOX_EXEC_TIMEOUT_MINUTES must be between 1 and 1440" }
            val platforms = get("FACTORY_SANDBOX_MAX_PLATFORMS")?.let {
                it.toIntOrNull() ?: throw IllegalArgumentException("FACTORY_SANDBOX_MAX_PLATFORMS must be a whole number")
            } ?: 4
            require(platforms in 0..20) { "FACTORY_SANDBOX_MAX_PLATFORMS must be between 0 and 20" }
            val cgroupParent = get("FACTORY_SANDBOX_CGROUP_PARENT")?.trim()?.takeIf { it.isNotEmpty() }
            if (cgroupParent != null) {
                require(CGROUP_PARENT_RE.matches(cgroupParent) && "/../" !in "$cgroupParent/" && !cgroupParent.endsWith(".slice")) {
                    "FACTORY_SANDBOX_CGROUP_PARENT must be an absolute cgroup path such as /system.slice/agern-sandbox.service/containers"
                }
            }
            return SandboxConfig(
                mode = mode,
                podmanBin = get("FACTORY_SANDBOX_PODMAN") ?: "podman",
                podmanUrl = get("FACTORY_SANDBOX_PODMAN_URL"),
                cgroupParent = cgroupParent,
                registries = registries,
                maxCpus = cpus,
                maxMemoryBytes = memory,
                maxConcurrent = concurrent,
                execTimeoutMs = execMinutes * 60_000L,
                maxPlatforms = platforms,
            )
        }

        const val MIN_MEMORY = 64L shl 20
        private val CGROUP_PARENT_RE = Regex("^(/[A-Za-z0-9_.@-]+)+$")
        private val MEMORY_RE = Regex("^([0-9]+)\\s*([kmgt]?)i?b?$", RegexOption.IGNORE_CASE)

        /** `4g`, `512m`, `1024k`, `2048` (bytes) → bytes; null when malformed. Units are binary (1k = 1024). */
        fun parseMemory(s: String): Long? {
            val m = MEMORY_RE.matchEntire(s.trim()) ?: return null
            val n = m.groupValues[1].toLongOrNull() ?: return null
            val shift = when (m.groupValues[2].lowercase()) { "" -> 0; "k" -> 10; "m" -> 20; "g" -> 30; else -> 40 }
            if (n > (Long.MAX_VALUE shr shift)) return null
            return n shl shift
        }

        /** Bytes → the largest exact unit podman understands: `4g`, `1536m`, `100k`, or plain bytes. */
        fun formatMemory(bytes: Long): String = when {
            bytes > 0 && bytes % (1L shl 30) == 0L -> "${bytes shr 30}g"
            bytes > 0 && bytes % (1L shl 20) == 0L -> "${bytes shr 20}m"
            bytes > 0 && bytes % (1L shl 10) == 0L -> "${bytes shr 10}k"
            else -> bytes.toString()
        }
    }
}
