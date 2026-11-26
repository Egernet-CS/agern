package dk.egernet.factory.sandboxvm

import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import dk.egernet.factory.deploy.DeployDefinitions
import dk.egernet.factory.deploy.DeployEndpoint
import dk.egernet.factory.deploy.PinnedHostKey
import dk.egernet.factory.sandbox.SandboxDefinitions
import dk.egernet.factory.sandbox.SandboxReady
import java.net.URI

/** Where a project declares its sandbox VM, relative to the repository root of the default branch (docs/SANDBOX-VMS.md). */
const val SANDBOX_VM_FILE = ".agern/sandbox-vm.yml"

/** A validated `.agern/sandbox-vm.yml`: one VM and the project's commands for it. */
data class SandboxVmDefinition(
    val name: String,
    /** A DNS name or an IPv4/IPv6 literal (no brackets). */
    val host: String,
    val port: Int,
    val user: String,
    val hostKey: PinnedHostKey,
    val description: String?,
    /** An absolute http(s) URL where people reach the app on the VM; null when none. */
    val url: String?,
    val checkout: String,
    val setup: String?,
    val ready: SandboxReady?,
    val reset: String?,
) {
    /** The VM's identity across the server (one lease per VM, also across projects). */
    val vmKey: String get() = vmKey(host, port)
    val endpoint: DeployEndpoint get() = DeployEndpoint(name, host, port, user, hostKey)

    companion object {
        fun vmKey(host: String, port: Int) = "${host.lowercase()}:$port"
    }
}

sealed interface ParsedSandboxVm {
    val sha256: String
    data class Valid(override val sha256: String, val definition: SandboxVmDefinition) : ParsedSandboxVm
    data class Invalid(override val sha256: String, val errors: List<String>) : ParsedSandboxVm
}

/**
 * Parses and strictly validates a sandbox VM definition like [DeployDefinitions]: unknown keys and malformed values are
 * errors, every problem is listed with its path in the file.
 */
object SandboxVmDefinitions {
    const val MAX_BYTES = 64 * 1024
    const val MAX_COMMAND_CHARS = 32 * 1024
    const val MAX_URL_CHARS = 2000
    const val DEFAULT_NAME = "sandbox-vm"
    const val DEFAULT_READY_TIMEOUT_SECONDS = 60
    const val MAX_READY_TIMEOUT_SECONDS = 600

    private val TOP_KEYS = listOf("name", "host", "port", "user", "hostKey", "description", "url", "checkout", "setup", "ready", "reset")
    private val READY_KEYS = listOf("command", "timeoutSeconds")

    fun sha256(content: String): String = SandboxDefinitions.sha256(content)

    fun parse(content: String): ParsedSandboxVm {
        val hash = sha256(content)
        if (content.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            return ParsedSandboxVm.Invalid(hash, listOf("$SANDBOX_VM_FILE is larger than ${MAX_BYTES / 1024} KiB"))
        }
        val root = SandboxDefinitions.parseYaml(content).getOrElse { return ParsedSandboxVm.Invalid(hash, listOf(it.message!!)) }
        val m = root as? YamlMap
            ?: return ParsedSandboxVm.Invalid(hash, listOf("The file must be a mapping with `host:`, `user:`, `hostKey:` and `checkout:`"))
        val errors = mutableListOf<String>()
        SandboxDefinitions.unknownKeys(m, TOP_KEYS, "", errors)
        fun node(key: String): YamlNode? = m.get<YamlNode>(key)?.takeUnless { it is YamlNull }
        fun value(key: String, required: Boolean): String? {
            val n = node(key)
            if (n == null) { if (required) errors += "$key: required"; return null }
            return SandboxDefinitions.scalar(n, key, errors)
        }
        val before = errors.size
        val name = value("name", false)?.trim()?.let {
            if (!DeployDefinitions.TARGET_NAME_RE.matches(it)) {
                errors += "name: lower-case letters, digits and '-', starting with a letter, at most 31 characters"
                null
            } else it
        } ?: DEFAULT_NAME
        val host = value("host", true)?.trim()?.also {
            if (!DeployDefinitions.validHost(it)) errors += "host: '$it' is neither a DNS name nor an IPv4 or IPv6 address (no brackets)"
        }
        val port = value("port", false)?.trim().let { p ->
            if (p == null) DeployDefinitions.DEFAULT_PORT
            else p.toIntOrNull()?.takeIf { it in 1..65535 } ?: run { errors += "port: a whole number between 1 and 65535"; null }
        }
        val user = value("user", true)?.trim()?.also {
            if (!DeployDefinitions.USER_RE.matches(it)) {
                errors += "user: '$it' is not a user name (letters, digits, '_', '.', '-', starting with a letter or '_', at most 32 characters)"
            }
        }
        val hostKey = value("hostKey", true)?.let { line -> DeployDefinitions.parseHostKey(line).getOrElse { errors += "hostKey: ${it.message}"; null } }
        val description = value("description", false)?.trim()?.takeIf { it.isNotEmpty() }?.also {
            if (it.length > DeployDefinitions.MAX_DESCRIPTION_CHARS) errors += "description: at most ${DeployDefinitions.MAX_DESCRIPTION_CHARS} characters"
            if ('\n' in it || '\r' in it || '\u0000' in it) errors += "description: must be one line"
        }
        val url = value("url", false)?.trim()?.takeIf { it.isNotEmpty() }?.also { u -> urlError(u)?.let { errors += "url: $it" } }
        val checkout = command("checkout", node("checkout"), errors, required = true)
        val setup = command("setup", node("setup"), errors, required = false)
        val reset = command("reset", node("reset"), errors, required = false)
        val ready = node("ready")?.let { ready(it, errors) }
        if (errors.size > before || errors.isNotEmpty() || host == null || port == null || user == null || hostKey == null || checkout == null) {
            return ParsedSandboxVm.Invalid(hash, errors.ifEmpty { listOf("The definition is incomplete") })
        }
        return ParsedSandboxVm.Valid(hash, SandboxVmDefinition(name, host, port, user, hostKey, description, url, checkout, setup, ready, reset))
    }

    /** Why [u] is not an acceptable `url:` (absolute http or https with a host, no whitespace or control characters), or null. */
    fun urlError(u: String): String? {
        if (u.length > MAX_URL_CHARS) return "at most $MAX_URL_CHARS characters"
        if (u.any { it.isWhitespace() || it.isISOControl() }) return "must not contain whitespace or control characters"
        val uri = try {
            URI(u)
        } catch (e: Exception) {
            return "not a valid URL"
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "must be an absolute http:// or https:// URL"
        if (uri.host.isNullOrEmpty()) return "must name a host, e.g. https://sandbox.example.net/"
        return null
    }

    private fun command(key: String, node: YamlNode?, errors: MutableList<String>, required: Boolean): String? {
        if (node == null) { if (required) errors += "$key: required"; return null }
        val s = SandboxDefinitions.scalar(node, key, errors) ?: return null
        if (s.length > MAX_COMMAND_CHARS) { errors += "$key: longer than $MAX_COMMAND_CHARS characters"; return null }
        if ('\u0000' in s) { errors += "$key: contains a NUL character"; return null }
        if (s.isBlank()) { if (required) errors += "$key: must not be empty"; return null }
        return s
    }

    private fun ready(node: YamlNode, errors: MutableList<String>): SandboxReady? {
        val m = node as? YamlMap
        if (m == null) { errors += "ready: must be a mapping with `command:` (and optionally `timeoutSeconds:`)"; return null }
        SandboxDefinitions.unknownKeys(m, READY_KEYS, "ready.", errors)
        val command = command("ready.command", m.get<YamlNode>("command")?.takeUnless { it is YamlNull }, errors, required = true) ?: return null
        var timeout = DEFAULT_READY_TIMEOUT_SECONDS
        m.get<YamlNode>("timeoutSeconds")?.takeUnless { it is YamlNull }?.let { SandboxDefinitions.scalar(it, "ready.timeoutSeconds", errors) }?.let { t ->
            val v = t.trim().toIntOrNull()
            if (v == null || v < 1 || v > MAX_READY_TIMEOUT_SECONDS) {
                errors += "ready.timeoutSeconds: a whole number of seconds between 1 and $MAX_READY_TIMEOUT_SECONDS"
                return null
            }
            timeout = v
        }
        return SandboxReady(command, timeout)
    }
}
