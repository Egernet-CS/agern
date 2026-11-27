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
    /** The OpenAI Codex CLI (image provider `codex`); a bare name is resolved on [agentPath]. */
    val codexBin: String = "codex",
    val publicUrl: String = "http://localhost:8080",
    val allowedOrigins: Set<String> = emptySet(),
    val maxAgentsPerUser: Int = 8,
    val chatIdleMinutes: Long = 30,
    /** FACTORY_STAGE_TIMEOUT_MINUTES: wall-clock limit for one pipeline stage agent. */
    val stageTimeoutMs: Long = 60 * 60_000L,
    /**
     * FACTORY_NETWORK_RETRY_HOURS: how long a run or a release start waits for the network (GitHub, the Claude API)
     * before it fails or holds (docs/ARCHITECTURE.md §6 "Transient failures"); 0 switches waiting off.
     */
    val networkRetryMaxMs: Long = 24 * 3_600_000L,
    /**
     * FACTORY_MAX_UPDATE_ROUNDS: update rounds ("Update and merge") a run may use after its merge was refused because its
     * target moved (docs/ARCHITECTURE.md §6 "Retrying a run"); fixed per run when it is created, 0 switches them off.
     */
    val maxUpdateRounds: Int = 2,
    /** Delays before the 2nd, 3rd, … attempt after a transient failure; the last repeats (not configurable; tests shorten it). */
    val networkRetryDelaysMs: List<Long> = listOf(1L, 2L, 5L, 10L, 15L).map { it * 60_000L },
    val githubApiUrl: String = "https://api.github.com",
    val fakeGithub: Boolean = false,
    /**
     * FACTORY_GITHUB_STATUS_URL: GitHub's official status summary shown on the GitHub status page (docs/ARCHITECTURE.md
     * §6 "GitHub status"); null switches it off. [fromEnv] defaults it to githubstatus.com for github.com only.
     */
    val githubStatusUrl: String? = null,
    val pamService: String? = null,
    val agentPath: String = defaultAgentPath(),
    /** FACTORY_ATTACHMENT_MAX_MB: largest single chat attachment. */
    val attachmentMaxBytes: Long = 20L shl 20,
    /** FACTORY_ATTACHMENT_MAX_MESSAGE_MB: all attachments of one message together. */
    val attachmentMaxMessageBytes: Long = 50L shl 20,
    /** FACTORY_ATTACHMENT_MAX_COUNT: attachments per message. */
    val attachmentMaxCount: Int = 10,
    /** FACTORY_SCREENSHOT_MAX_MB: largest single screenshot a UX or UI design reviewer attaches to its stage. */
    val screenshotMaxBytes: Long = 5L shl 20,
    /** FACTORY_SCREENSHOT_MAX_COUNT: screenshots per stage (per reviewer per round). */
    val screenshotMaxCount: Int = 12,
    /** FACTORY_IMAGE_MAX_*: limits on image generation (docs/ARCHITECTURE.md §6 "Generated images"). */
    val imageLimits: ImageLimits = ImageLimits(),
    /** FACTORY_SANDBOX*: per-run build/test sandboxes (docs/ARCHITECTURE.md §5 "Sandboxes"). */
    val sandbox: SandboxConfig = SandboxConfig(),
    /** FACTORY_DEPLOY*: per-project SSH deploy targets (docs/DEPLOY.md, docs/ARCHITECTURE.md §5 "Deploy targets"). */
    val deploy: DeployConfig = DeployConfig(),
    /** FACTORY_SANDBOX_VM*: per-project sandbox VMs (docs/SANDBOX-VMS.md, docs/ARCHITECTURE.md §5 "Sandbox VMs"). */
    val sandboxVm: SandboxVmConfig = SandboxVmConfig(),
    /**
     * FACTORY_RELEASE_CHECKS_SETTLE_SECONDS: how long a pull request's head commit must have had no checks at all before
     * "no checks" counts as green, for an automatic release and before a run is merged (CI registers its checks some
     * seconds after a push).
     */
    val releaseChecksSettleSeconds: Long = 120,
    /**
     * FACTORY_RUN_CHECKS_WAIT_MINUTES: how long an approved run waits in `waiting_for_checks` for the checks on its pull
     * request's head commit before it goes to needs_human (docs/ARCHITECTURE.md §6 "Checks before a merge").
     */
    val runChecksWaitMinutes: Long = 60,
    /** How long `POST /mcp` waits for a call before it answers as an event stream (not configurable; tests shorten it). */
    val mcpStreamAfterMs: Long = 5_000,
    /** How often a streamed MCP answer sends a progress notification or keep-alive while the call runs. */
    val mcpProgressEveryMs: Long = 15_000,
) {
    /** The git host's HTTPS base (`https://github.com/`, or the GitHub Enterprise host of [githubApiUrl]). */
    val gitHost: String
        get() = if (githubApiUrl == "https://api.github.com") "https://github.com/"
            else githubApiUrl.substringBefore("/api/").trimEnd('/') + "/"

    /**
     * The MCP tool-call and idle timeouts given to every agent (`MCP_TOOL_TIMEOUT`, `CLAUDE_CODE_MCP_TOOL_IDLE_TIMEOUT`):
     * longer than any call can take (no call outlives the stage; the first sandbox call adds creation and setup; a deploy
     * command or copy adds its connection; so does a sandbox VM command or deploy).
     */
    val mcpToolTimeoutMs: Long get() = maxOf(stageTimeoutMs, sandbox.execTimeoutMs, deploy.execTimeoutMs, sandboxVm.execTimeoutMs) + 60_000
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
            "admins=$admins, dataDir=$dataDir, webDir=$webDir, claudeBin=$claudeBin, codexBin=$codexBin, publicUrl=$publicUrl, " +
            "maxAgentsPerUser=$maxAgentsPerUser, chatIdleMinutes=$chatIdleMinutes, stageTimeoutMs=$stageTimeoutMs, networkRetryMaxMs=$networkRetryMaxMs, maxUpdateRounds=$maxUpdateRounds, fakeGithub=$fakeGithub, " +
            "githubStatusUrl=${githubStatusUrl ?: "off"}, " +
            "attachments=${attachmentMaxCount}x${attachmentMaxBytes shr 20}MB/${attachmentMaxMessageBytes shr 20}MB, " +
            "screenshots=${screenshotMaxCount}x${screenshotMaxBytes shr 20}MB, images=$imageLimits, sandbox=$sandbox, deploy=$deploy, sandboxVm=$sandboxVm)"

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
            val deploy = DeployConfig.fromEnv(::get)
            if (deploy.mode == "fake") require(fakeGithub) { "FACTORY_DEPLOY=fake is a development mode and requires FACTORY_DEV_FAKE_GITHUB" }
            val sandboxVm = SandboxVmConfig.fromEnv(::get)
            if (sandboxVm.mode == "fake") require(fakeGithub) { "FACTORY_SANDBOX_VM=fake is a development mode and requires FACTORY_DEV_FAKE_GITHUB" }

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
                    // DNs contain commas, so several groups are separated by ';'.
                    requiredGroups = (get("FACTORY_LDAP_REQUIRED_GROUP") ?: "").split(';').map { it.trim() }.filter { it.isNotEmpty() },
                    groupMemberAttribute = get("FACTORY_LDAP_GROUP_MEMBER_ATTRIBUTE") ?: "member",
                    groupMemberValue = (get("FACTORY_LDAP_GROUP_MEMBER_VALUE") ?: "dn").lowercase(),
                ),
                devUsers = devUsers,
                dataDir = Paths.get(get("FACTORY_DATA_DIR") ?: "./data").toAbsolutePath().normalize(),
                webDir = get("FACTORY_WEB_DIR")?.let { Paths.get(it).toAbsolutePath().normalize() },
                claudeBin = get("FACTORY_CLAUDE_BIN") ?: "claude",
                codexBin = get("FACTORY_CODEX_BIN") ?: "codex",
                publicUrl = get("FACTORY_PUBLIC_URL") ?: "http://localhost:8080",
                allowedOrigins = (get("FACTORY_ALLOWED_ORIGINS") ?: "").split(',').map { originOf(it.trim()) }
                    .filter { it.isNotEmpty() }.toSet(),
                maxAgentsPerUser = get("FACTORY_MAX_AGENTS_PER_USER")?.toInt() ?: 8,
                chatIdleMinutes = get("FACTORY_CHAT_IDLE_MINUTES")?.toLong() ?: 30,
                stageTimeoutMs = (get("FACTORY_STAGE_TIMEOUT_MINUTES")?.toLongOrNull()
                    ?.also { require(it in 1..24 * 60) { "FACTORY_STAGE_TIMEOUT_MINUTES must be between 1 and 1440" } }
                    ?: (get("FACTORY_STAGE_TIMEOUT_MINUTES")?.let { throw IllegalArgumentException("FACTORY_STAGE_TIMEOUT_MINUTES must be a whole number of minutes") })
                    ?: 60) * 60_000L,
                networkRetryMaxMs = (get("FACTORY_NETWORK_RETRY_HOURS")?.toLongOrNull()
                    ?.also { require(it in 0..168) { "FACTORY_NETWORK_RETRY_HOURS must be between 0 and 168" } }
                    ?: (get("FACTORY_NETWORK_RETRY_HOURS")?.let { throw IllegalArgumentException("FACTORY_NETWORK_RETRY_HOURS must be a whole number of hours") })
                    ?: 24) * 3_600_000L,
                maxUpdateRounds = get("FACTORY_MAX_UPDATE_ROUNDS")?.let { v ->
                    val n = v.toIntOrNull() ?: throw IllegalArgumentException("FACTORY_MAX_UPDATE_ROUNDS must be a whole number")
                    require(n in 0..10) { "FACTORY_MAX_UPDATE_ROUNDS must be between 0 and 10" }
                    n
                } ?: 2,
                githubApiUrl = get("FACTORY_GITHUB_API_URL") ?: "https://api.github.com",
                fakeGithub = fakeGithub,
                githubStatusUrl = githubStatusUrl(env["FACTORY_GITHUB_STATUS_URL"], get("FACTORY_GITHUB_API_URL") ?: "https://api.github.com", fakeGithub),
                pamService = get("FACTORY_PAM_SERVICE"),
                agentPath = get("FACTORY_AGENT_PATH") ?: defaultAgentPath(),
                attachmentMaxBytes = mb(get("FACTORY_ATTACHMENT_MAX_MB"), "FACTORY_ATTACHMENT_MAX_MB", 20),
                attachmentMaxMessageBytes = mb(get("FACTORY_ATTACHMENT_MAX_MESSAGE_MB"), "FACTORY_ATTACHMENT_MAX_MESSAGE_MB", 50),
                attachmentMaxCount = (get("FACTORY_ATTACHMENT_MAX_COUNT")?.toIntOrNull() ?: get("FACTORY_ATTACHMENT_MAX_COUNT")?.let {
                    throw IllegalArgumentException("FACTORY_ATTACHMENT_MAX_COUNT must be a whole number")
                } ?: 10).also { require(it in 1..20) { "FACTORY_ATTACHMENT_MAX_COUNT must be between 1 and 20" } },
                screenshotMaxBytes = mb(get("FACTORY_SCREENSHOT_MAX_MB"), "FACTORY_SCREENSHOT_MAX_MB", 5),
                screenshotMaxCount = (get("FACTORY_SCREENSHOT_MAX_COUNT")?.toIntOrNull() ?: get("FACTORY_SCREENSHOT_MAX_COUNT")?.let {
                    throw IllegalArgumentException("FACTORY_SCREENSHOT_MAX_COUNT must be a whole number")
                } ?: 12).also { require(it in 1..50) { "FACTORY_SCREENSHOT_MAX_COUNT must be between 1 and 50" } },
                imageLimits = ImageLimits(
                    maxPerRequest = intIn(get("FACTORY_IMAGE_MAX_PER_REQUEST"), "FACTORY_IMAGE_MAX_PER_REQUEST", 4, 1..10),
                    maxPerTurn = intIn(get("FACTORY_IMAGE_MAX_PER_TURN"), "FACTORY_IMAGE_MAX_PER_TURN", 8, 1..100),
                    maxPerStage = intIn(get("FACTORY_IMAGE_MAX_PER_STAGE"), "FACTORY_IMAGE_MAX_PER_STAGE", 12, 1..200),
                    maxEdge = intIn(get("FACTORY_IMAGE_MAX_EDGE"), "FACTORY_IMAGE_MAX_EDGE", 2048, 256..4096),
                ),
                sandbox = sandbox,
                deploy = deploy,
                sandboxVm = sandboxVm,
                releaseChecksSettleSeconds = get("FACTORY_RELEASE_CHECKS_SETTLE_SECONDS")?.let { v ->
                    (v.toLongOrNull() ?: throw IllegalArgumentException("FACTORY_RELEASE_CHECKS_SETTLE_SECONDS must be a whole number of seconds"))
                        .also { require(it in 0..3600) { "FACTORY_RELEASE_CHECKS_SETTLE_SECONDS must be between 0 and 3600" } }
                } ?: 120,
                runChecksWaitMinutes = get("FACTORY_RUN_CHECKS_WAIT_MINUTES")?.let { v ->
                    (v.toLongOrNull() ?: throw IllegalArgumentException("FACTORY_RUN_CHECKS_WAIT_MINUTES must be a whole number of minutes"))
                        .also { require(it in 1..1440) { "FACTORY_RUN_CHECKS_WAIT_MINUTES must be between 1 and 1440" } }
                } ?: 60,
            ).also {
                require(it.attachmentMaxMessageBytes >= it.attachmentMaxBytes) {
                    "FACTORY_ATTACHMENT_MAX_MESSAGE_MB must be at least FACTORY_ATTACHMENT_MAX_MB"
                }
            }
        }

        /**
         * FACTORY_GITHUB_STATUS_URL: unset → githubstatus.com for github.com without the fake GitHub, else off; `off` or
         * empty → off; anything else must be an http(s) URL.
         */
        fun githubStatusUrl(raw: String?, apiUrl: String, fakeGithub: Boolean): String? {
            if (raw == null) return if (apiUrl == "https://api.github.com" && !fakeGithub) "https://www.githubstatus.com/api/v2/summary.json" else null
            val v = raw.trim()
            if (v.isEmpty() || v.equals("off", ignoreCase = true)) return null
            val uri = runCatching { java.net.URI(v) }.getOrNull()
            require(uri != null && uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty()) {
                "FACTORY_GITHUB_STATUS_URL must be an http:// or https:// URL, or 'off'"
            }
            return v
        }

        private fun intIn(v: String?, name: String, default: Int, range: IntRange): Int {
            val n = if (v == null) default else v.trim().toIntOrNull() ?: throw IllegalArgumentException("$name must be a whole number")
            require(n in range) { "$name must be between ${range.first} and ${range.last}" }
            return n
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
    /** Group DNs, any one of which must list the user (FACTORY_LDAP_REQUIRED_GROUP); empty = no group check. */
    val requiredGroups: List<String> = emptyList(),
    /** Attribute of the group entry that lists members (FACTORY_LDAP_GROUP_MEMBER_ATTRIBUTE). */
    val groupMemberAttribute: String = "member",
    /** `dn` (the member attribute holds user DNs) or `uid` (it holds login names) (FACTORY_LDAP_GROUP_MEMBER_VALUE). */
    val groupMemberValue: String = "dn",
) {
    override fun toString() = "LdapConfig(url=$url, userDnTemplate=$userDnTemplate, baseDn=$baseDn, bindDn=$bindDn, " +
        "requiredGroups=$requiredGroups, groupMemberAttribute=$groupMemberAttribute, groupMemberValue=$groupMemberValue)"
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
    /**
     * FACTORY_SANDBOX_PREPARED_MAX_AGE_DAYS: how long a prepared image (a container committed after its setup script ran,
     * docs/SANDBOXES.md "Prepared images") is reused before setup runs again; 0 switches prepared images off.
     */
    val preparedMaxAgeDays: Int = 7,
) {
    val enabled: Boolean get() = mode != "off"

    override fun toString() = if (!enabled) "off" else
        "SandboxConfig(mode=$mode, podman=$podmanBin, url=${podmanUrl ?: "-"}, cgroupParent=${cgroupParent ?: "-"}, registries=$registries, maxCpus=$maxCpus, " +
            "maxMemory=${formatMemory(maxMemoryBytes)}, maxConcurrent=$maxConcurrent, execTimeoutMs=$execTimeoutMs, maxPlatforms=$maxPlatforms, " +
            "preparedMaxAgeDays=$preparedMaxAgeDays)"

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
            val preparedDays = get("FACTORY_SANDBOX_PREPARED_MAX_AGE_DAYS")?.trim()?.let {
                it.toIntOrNull() ?: throw IllegalArgumentException("FACTORY_SANDBOX_PREPARED_MAX_AGE_DAYS must be a whole number of days")
            } ?: 7
            require(preparedDays in 0..365) { "FACTORY_SANDBOX_PREPARED_MAX_AGE_DAYS must be between 0 and 365 (0 switches prepared images off)" }
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
                preparedMaxAgeDays = preparedDays,
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

/** Deploy target settings (docs/ARCHITECTURE.md §3, docs/DEPLOY.md). */
data class DeployConfig(
    /**
     * `ssh` (default: the tools exist, nothing happens until an admin generates a key and approves a file), `off` (no
     * deploy tools) or `fake` (connects nowhere, only pretends; development and end-to-end tests with
     * FACTORY_DEV_FAKE_GITHUB).
     */
    val mode: String = "ssh",
    /** FACTORY_DEPLOY_EXEC_TIMEOUT_MINUTES: default and maximum for one command or copy. */
    val execTimeoutMs: Long = 30 * 60_000L,
    /** FACTORY_DEPLOY_MAX_COPY_MB: the largest file `factory_deploy_copy` sends. */
    val maxCopyBytes: Long = 512L shl 20,
) {
    val enabled: Boolean get() = mode != "off"

    override fun toString() = if (!enabled) "off" else "DeployConfig(mode=$mode, execTimeoutMs=$execTimeoutMs, maxCopyMB=${maxCopyBytes shr 20})"

    companion object {
        fun fromEnv(get: (String) -> String?): DeployConfig {
            val mode = (get("FACTORY_DEPLOY") ?: "ssh").lowercase()
            require(mode in setOf("ssh", "off", "fake")) { "FACTORY_DEPLOY must be 'ssh' or 'off' ('fake' in development)" }
            val minutes = get("FACTORY_DEPLOY_EXEC_TIMEOUT_MINUTES")?.let {
                it.toLongOrNull() ?: throw IllegalArgumentException("FACTORY_DEPLOY_EXEC_TIMEOUT_MINUTES must be a whole number of minutes")
            } ?: 30
            require(minutes in 1..24 * 60) { "FACTORY_DEPLOY_EXEC_TIMEOUT_MINUTES must be between 1 and 1440" }
            val mb = get("FACTORY_DEPLOY_MAX_COPY_MB")?.let {
                it.toLongOrNull() ?: throw IllegalArgumentException("FACTORY_DEPLOY_MAX_COPY_MB must be a whole number of megabytes")
            } ?: 512
            require(mb in 1..10_240) { "FACTORY_DEPLOY_MAX_COPY_MB must be between 1 and 10240" }
            return DeployConfig(mode, minutes * 60_000L, mb shl 20)
        }
    }
}

/** Sandbox VM settings (docs/ARCHITECTURE.md §3, docs/SANDBOX-VMS.md). The upload limit is [DeployConfig.maxCopyBytes]. */
data class SandboxVmConfig(
    /**
     * `ssh` (default: nothing happens until an admin approves a project's `.agern/sandbox-vm.yml`), `off` (no tools, no
     * previews) or `fake` (connects nowhere, only pretends; development and end-to-end tests with FACTORY_DEV_FAKE_GITHUB).
     */
    val mode: String = "ssh",
    /** FACTORY_SANDBOX_VM_EXEC_TIMEOUT_MINUTES: default and maximum for one command, and for one whole deploy. */
    val execTimeoutMs: Long = 30 * 60_000L,
    /** FACTORY_SANDBOX_VM_PREVIEW_MINUTES: how long a human preview holds the VM. */
    val previewMs: Long = 120 * 60_000L,
) {
    val enabled: Boolean get() = mode != "off"

    override fun toString() = if (!enabled) "off" else "SandboxVmConfig(mode=$mode, execTimeoutMs=$execTimeoutMs, previewMinutes=${previewMs / 60_000})"

    companion object {
        fun fromEnv(get: (String) -> String?): SandboxVmConfig {
            val mode = (get("FACTORY_SANDBOX_VM") ?: "ssh").lowercase()
            require(mode in setOf("ssh", "off", "fake")) { "FACTORY_SANDBOX_VM must be 'ssh' or 'off' ('fake' in development)" }
            fun minutes(name: String, default: Long, range: LongRange): Long {
                val v = get(name)?.let { it.trim().toLongOrNull() ?: throw IllegalArgumentException("$name must be a whole number of minutes") } ?: default
                require(v in range) { "$name must be between ${range.first} and ${range.last}" }
                return v
            }
            val exec = minutes("FACTORY_SANDBOX_VM_EXEC_TIMEOUT_MINUTES", 30, 1L..24 * 60)
            val preview = minutes("FACTORY_SANDBOX_VM_PREVIEW_MINUTES", 120, 5L..24 * 60)
            return SandboxVmConfig(mode, exec * 60_000L, preview * 60_000L)
        }
    }
}

/** Limits on image generation per tool call, per chat message, per stage agent, and the longest edge in pixels. */
data class ImageLimits(val maxPerRequest: Int = 4, val maxPerTurn: Int = 8, val maxPerStage: Int = 12, val maxEdge: Int = 2048)
