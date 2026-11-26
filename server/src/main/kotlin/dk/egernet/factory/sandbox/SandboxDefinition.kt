package dk.egernet.factory.sandbox

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import dk.egernet.factory.SandboxConfig
import dk.egernet.factory.security.Tokens

/** Where a project describes its sandbox, relative to the repository root of the base branch. */
const val SANDBOX_FILE = ".agern/sandbox.yml"

/**
 * How the server learns that a sidecar accepts connections: [command] is run with `sh -c` inside the service's
 * container (e.g. `pg_isready -h 127.0.0.1`) until it exits 0, for at most [timeoutSeconds].
 */
data class SandboxReady(val command: String, val timeoutSeconds: Int)

/** A sidecar container, reachable from the sandbox under its [name]. */
data class SandboxService(val name: String, val image: String, val env: Map<String, String>, val ready: SandboxReady? = null)

/**
 * A named extra image the builder can run commands in (`platforms:`), e.g. another runtime version or distribution.
 * [setup] is the platform's own script, or the top-level one when the platform names none ([setupInherited]).
 */
data class SandboxPlatform(val name: String, val image: String, val setup: String?, val setupInherited: Boolean)

/**
 * The account commands run as (`user:`): [uid] inside the container, mapped to the service user on the host. [name]
 * is null when the definition gave a uid (the image's account with that uid is used, or `sandbox` is created).
 */
data class SandboxUser(val name: String?, val uid: Int) {
    /** As written in the file. */
    val display: String get() = name ?: uid.toString()
}

/**
 * A validated `.agern/sandbox.yml` (docs/ARCHITECTURE.md §5 "Sandboxes"). [cpus] and [memoryBytes] are the effective
 * limits: what the file asks for, capped by the server's limits (the server's limits when it asks for nothing).
 */
data class SandboxDefinition(
    val image: String,
    val setup: String?,
    val env: Map<String, String>,
    val services: List<SandboxService>,
    val caches: List<String>,
    val requestedCpus: Double?,
    val requestedMemoryBytes: Long?,
    val cpus: Double,
    val memoryBytes: Long,
    /** Extra images besides [image], in file order; each runs in its own container, created on first use. */
    val platforms: List<SandboxPlatform> = emptyList(),
    /** Null: commands run as root inside the container (the service user on the host), as before `user:` existed. */
    val user: SandboxUser? = null,
) {
    /** True when the file asked for more than the server allows. */
    val capped: Boolean get() = (requestedCpus != null && requestedCpus > cpus) || (requestedMemoryBytes != null && requestedMemoryBytes > memoryBytes)
}

sealed interface ParsedDefinition {
    val sha256: String
    data class Valid(override val sha256: String, val definition: SandboxDefinition) : ParsedDefinition
    data class Invalid(override val sha256: String, val errors: List<String>) : ParsedDefinition
}

/**
 * Parses and strictly validates a sandbox definition: unknown keys, unqualified images, registries that are not
 * allowed and malformed values are all errors (every problem is listed, each with its path in the file).
 */
object SandboxDefinitions {
    const val MAX_BYTES = 64 * 1024
    const val MAX_SETUP_CHARS = 32 * 1024
    const val MAX_SERVICES = 5
    const val MAX_CACHES = 10
    const val MAX_ENV = 100
    const val MAX_READY_COMMAND_CHARS = 4096
    const val DEFAULT_READY_TIMEOUT_SECONDS = 60
    const val MAX_READY_TIMEOUT_SECONDS = 600
    /** The uid a `user:` given by name gets inside the container. */
    const val DEFAULT_UID = 1000
    const val MAX_UID = 60_000
    /** What `factory_sandbox_exec` calls the top-level image when a `platform` is named explicitly. */
    const val DEFAULT_PLATFORM = "default"

    private val TOP_KEYS = listOf("image", "setup", "env", "services", "caches", "resources", "platforms", "user")
    private val SERVICE_KEYS = listOf("image", "env", "ready")
    private val READY_KEYS = listOf("command", "timeoutSeconds")
    private val PLATFORM_KEYS = listOf("image", "setup")
    private val PLATFORM_NAME_RE = Regex("^[a-z][a-z0-9-]{0,30}$")
    private val USER_NAME_RE = Regex("^[a-z_][a-z0-9_-]{0,31}$")
    private val RESOURCE_KEYS = listOf("cpus", "memory")
    private val ENV_KEY_RE = Regex("^[A-Za-z_][A-Za-z0-9_]{0,127}$")
    private val SERVICE_NAME_RE = Regex("^[a-z][a-z0-9-]{0,30}$")
    private val RESERVED_SERVICE_NAMES = setOf("localhost", "sandbox", "workspace", "host", "gateway")
    private val CACHE_PATH_RE = Regex("^/[A-Za-z0-9._@+-]+(/[A-Za-z0-9._@+-]+)*$")
    private val FORBIDDEN_CACHE_ROOTS = listOf("/workspace", "/proc", "/sys", "/dev", "/run", "/etc", "/bin", "/sbin", "/usr", "/lib", "/lib64", "/boot")

    // registry[:port]/path[:tag][@sha256:digest] — the reference grammar of the OCI distribution spec, lower-case path.
    private val HOST_RE = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*(:[0-9]{1,5})?$")
    private val PATH_COMPONENT_RE = Regex("^[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*$")
    private val TAG_RE = Regex("^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$")
    private val DIGEST_RE = Regex("^sha256:[a-f0-9]{64}$")

    private val yaml = Yaml.default

    fun sha256(content: String): String = Tokens.sha256Hex(content)

    fun parse(content: String, cfg: SandboxConfig): ParsedDefinition {
        val hash = sha256(content)
        if (content.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            return ParsedDefinition.Invalid(hash, listOf("$SANDBOX_FILE is larger than ${MAX_BYTES / 1024} KiB"))
        }
        val root = parseYaml(content).getOrElse { return ParsedDefinition.Invalid(hash, listOf(it.message!!)) }
        val errors = mutableListOf<String>()
        val top = root as? YamlMap ?: return ParsedDefinition.Invalid(hash, listOf("The file must be a mapping with at least `image:`"))
        unknownKeys(top, TOP_KEYS, "", errors)

        val image = top.get<YamlNode>("image").let { node ->
            when (node) {
                null, is YamlNull -> { errors += "image: required (a fully qualified image, e.g. docker.io/library/eclipse-temurin:21-jdk)"; null }
                else -> scalar(node, "image", errors)?.let { checkImage(it, "image", cfg, errors) }
            }
        }
        val setup = top.get<YamlNode>("setup")?.takeUnless { it is YamlNull }?.let { setupScript(it, "setup", errors) }
        val env = top.get<YamlNode>("env")?.takeUnless { it is YamlNull }?.let { envMap(it, "env", errors) } ?: emptyMap()

        val services = mutableListOf<SandboxService>()
        top.get<YamlNode>("services")?.takeUnless { it is YamlNull }?.let { node ->
            val m = node as? YamlMap
            if (m == null) errors += "services: must be a mapping of service name to { image, env }"
            else {
                if (m.entries.size > MAX_SERVICES) errors += "services: at most $MAX_SERVICES services"
                for ((k, v) in m.entries) {
                    val name = k.content
                    val path = "services.$name"
                    if (!SERVICE_NAME_RE.matches(name) || name in RESERVED_SERVICE_NAMES) {
                        errors += "$path: service names are lower-case letters, digits and '-', start with a letter, at most 31 characters, " +
                            "and not one of ${RESERVED_SERVICE_NAMES.sorted().joinToString(", ")}"
                        continue
                    }
                    val sm = v as? YamlMap
                    if (sm == null) { errors += "$path: must be a mapping with `image:` (and optionally `env:`)"; continue }
                    unknownKeys(sm, SERVICE_KEYS, "$path.", errors)
                    val simg = sm.get<YamlNode>("image")?.takeUnless { it is YamlNull }?.let { scalar(it, "$path.image", errors) }
                        ?.let { checkImage(it, "$path.image", cfg, errors) }
                    if (sm.get<YamlNode>("image") == null || sm.get<YamlNode>("image") is YamlNull) errors += "$path.image: required"
                    val senv = sm.get<YamlNode>("env")?.takeUnless { it is YamlNull }?.let { envMap(it, "$path.env", errors) } ?: emptyMap()
                    val ready = sm.get<YamlNode>("ready")?.takeUnless { it is YamlNull }?.let { ready(it, "$path.ready", errors) }
                    if (simg != null) services += SandboxService(name, simg, senv, ready)
                }
            }
        }

        val caches = mutableListOf<String>()
        top.get<YamlNode>("caches")?.takeUnless { it is YamlNull }?.let { node ->
            val l = node as? YamlList
            if (l == null) errors += "caches: must be a list of absolute directories inside the sandbox, e.g. [ /root/.gradle ]"
            else {
                if (l.items.size > MAX_CACHES) errors += "caches: at most $MAX_CACHES directories"
                l.items.forEachIndexed { i, item ->
                    val p = scalar(item, "caches[$i]", errors) ?: return@forEachIndexed
                    val norm = p.trimEnd('/')
                    when {
                        !CACHE_PATH_RE.matches(norm) || norm.split('/').any { it == "." || it == ".." } ->
                            errors += "caches[$i]: '$p' is not an absolute directory path (letters, digits, . _ @ + -)"
                        FORBIDDEN_CACHE_ROOTS.any { norm == it || norm.startsWith("$it/") } ->
                            errors += "caches[$i]: '$p' may not be a cache (not /workspace or system directories)"
                        norm in caches -> errors += "caches[$i]: '$p' is listed twice"
                        caches.any { norm.startsWith("$it/") || it.startsWith("$norm/") } -> errors += "caches[$i]: '$p' overlaps another cache"
                        else -> caches += norm
                    }
                }
            }
        }

        var cpus: Double? = null
        var memory: Long? = null
        top.get<YamlNode>("resources")?.takeUnless { it is YamlNull }?.let { node ->
            val m = node as? YamlMap
            if (m == null) errors += "resources: must be a mapping with `cpus:` and/or `memory:`"
            else {
                unknownKeys(m, RESOURCE_KEYS, "resources.", errors)
                m.get<YamlNode>("cpus")?.takeUnless { it is YamlNull }?.let { scalar(it, "resources.cpus", errors) }?.let { c ->
                    val v = c.toDoubleOrNull()
                    if (v == null || v <= 0 || v.isNaN() || v.isInfinite()) errors += "resources.cpus: must be a positive number, e.g. 2 or 1.5"
                    else cpus = v
                }
                m.get<YamlNode>("memory")?.takeUnless { it is YamlNull }?.let { scalar(it, "resources.memory", errors) }?.let { s ->
                    val v = SandboxConfig.parseMemory(s)
                    if (v == null) errors += "resources.memory: must be a size like 4g or 512m"
                    else if (v < SandboxConfig.MIN_MEMORY) errors += "resources.memory: at least 64m"
                    else memory = v
                }
            }
        }

        val platforms = mutableListOf<SandboxPlatform>()
        top.get<YamlNode>("platforms")?.takeUnless { it is YamlNull }?.let { node ->
            val m = node as? YamlMap
            if (m == null) errors += "platforms: must be a mapping of platform name to { image, setup }"
            else {
                if (m.entries.size > cfg.maxPlatforms) {
                    errors += "platforms: at most ${cfg.maxPlatforms} on this server (FACTORY_SANDBOX_MAX_PLATFORMS)"
                }
                for ((k, v) in m.entries) {
                    val name = k.content
                    val path = "platforms.$name"
                    if (!PLATFORM_NAME_RE.matches(name) || name == DEFAULT_PLATFORM) {
                        errors += "$path: platform names are lower-case letters, digits and '-', start with a letter, at most 31 characters, " +
                            "and not '$DEFAULT_PLATFORM' (the top-level image)"
                        continue
                    }
                    val pm = v as? YamlMap
                    if (pm == null) { errors += "$path: must be a mapping with `image:` (and optionally `setup:`)"; continue }
                    unknownKeys(pm, PLATFORM_KEYS, "$path.", errors)
                    val pimgNode = pm.get<YamlNode>("image")?.takeUnless { it is YamlNull }
                    if (pimgNode == null) errors += "$path.image: required"
                    val pimg = pimgNode?.let { scalar(it, "$path.image", errors) }?.let { checkImage(it, "$path.image", cfg, errors) }
                    // No `setup:` key: the top-level script. `setup:` present but empty: none.
                    val setupNode = pm.get<YamlNode>("setup")
                    val inherited = setupNode == null
                    val psetup = if (inherited) setup else setupNode.takeUnless { it is YamlNull }?.let { setupScript(it, "$path.setup", errors) }
                    if (pimg != null) platforms += SandboxPlatform(name, pimg, psetup, inherited)
                }
            }
        }

        val user = top.get<YamlNode>("user")?.takeUnless { it is YamlNull }?.let { node ->
            scalar(node, "user", errors)?.trim()?.let { u ->
                val n = u.toLongOrNull()
                when {
                    n != null && n == 0L -> { errors += "user: 0 is root, which commands run as without `user:`"; null }
                    n != null && (n < 1 || n > MAX_UID) -> { errors += "user: a uid must be between 1 and $MAX_UID"; null }
                    n != null -> SandboxUser(null, n.toInt())
                    u == "root" -> { errors += "user: 'root' is what commands run as without `user:`"; null }
                    !USER_NAME_RE.matches(u) -> {
                        errors += "user: '$u' is neither a uid nor a user name (lower-case letters, digits, '_' and '-', at most 32 characters)"; null
                    }
                    else -> SandboxUser(u, DEFAULT_UID)
                }
            }
        }

        if (errors.isNotEmpty() || image == null) return ParsedDefinition.Invalid(hash, errors.ifEmpty { listOf("image: required") })
        return ParsedDefinition.Valid(
            hash,
            SandboxDefinition(
                image = image, setup = setup, env = env, services = services, caches = caches,
                requestedCpus = cpus, requestedMemoryBytes = memory,
                cpus = minOf(cpus ?: cfg.maxCpus, cfg.maxCpus), memoryBytes = minOf(memory ?: cfg.maxMemoryBytes, cfg.maxMemoryBytes),
                platforms = platforms, user = user,
            ),
        )
    }

    /**
     * The YAML tree of [content], or a failure whose message says why it is not valid YAML (with line and column). Shared
     * with the other parsers of files under `.agern/`, so anchors and duplicate keys are treated the same everywhere.
     */
    internal fun parseYaml(content: String): Result<YamlNode> = try {
        Result.success(yaml.parseToYamlNode(content))
    } catch (e: YamlException) {
        Result.failure(IllegalArgumentException("Not valid YAML: ${e.message} (line ${e.line}, column ${e.column})"))
    } catch (e: Exception) {
        Result.failure(IllegalArgumentException("Not valid YAML: ${e.message ?: e.javaClass.simpleName}"))
    }

    /** The registry host (and port) of a fully qualified reference, or null when the reference names none. */
    fun registryOf(image: String): String? {
        val first = image.substringBefore('/')
        if (first == image) return null
        return first.takeIf { it.contains('.') || it.contains(':') || it == "localhost" }?.lowercase()
    }

    private fun checkImage(ref: String, path: String, cfg: SandboxConfig, errors: MutableList<String>): String? {
        val image = ref.trim()
        val registry = registryOf(image)
        if (registry == null) {
            errors += "$path: '$ref' is not fully qualified; name the registry, e.g. docker.io/library/${image.ifEmpty { "debian:12" }}"
            return null
        }
        if (!validReference(image)) {
            errors += "$path: '$ref' is not a valid image reference (registry/name[:tag][@sha256:digest], lower-case name)"
            return null
        }
        if (registry !in cfg.registries) {
            errors += "$path: registry '$registry' is not allowed on this server (allowed: ${cfg.registries.joinToString(", ")})"
            return null
        }
        return image
    }

    private fun validReference(image: String): Boolean {
        var rest = image
        val digest = rest.substringAfter('@', "")
        if ('@' in rest) {
            if (!DIGEST_RE.matches(digest)) return false
            rest = rest.substringBefore('@')
        }
        val host = rest.substringBefore('/')
        if (!HOST_RE.matches(host)) return false
        var path = rest.substringAfter('/')
        val lastSlash = path.lastIndexOf('/')
        val colon = path.lastIndexOf(':')
        if (colon > lastSlash) {
            if (!TAG_RE.matches(path.substring(colon + 1))) return false
            path = path.substring(0, colon)
        }
        if (path.isEmpty() || path.length > 255) return false
        return path.split('/').all { PATH_COMPONENT_RE.matches(it) }
    }

    private fun setupScript(node: YamlNode, path: String, errors: MutableList<String>): String? =
        scalar(node, path, errors)?.also {
            if (it.length > MAX_SETUP_CHARS) errors += "$path: longer than $MAX_SETUP_CHARS characters"
            if ('\u0000' in it) errors += "$path: contains a NUL character"
        }?.takeIf { it.isNotBlank() }

    private fun ready(node: YamlNode, path: String, errors: MutableList<String>): SandboxReady? {
        val m = node as? YamlMap
        if (m == null) { errors += "$path: must be a mapping with `command:` (and optionally `timeoutSeconds:`)"; return null }
        unknownKeys(m, READY_KEYS, "$path.", errors)
        val command = m.get<YamlNode>("command")?.takeUnless { it is YamlNull }?.let { scalar(it, "$path.command", errors) }
        if (command == null) { if (m.get<YamlNode>("command").let { it == null || it is YamlNull }) errors += "$path.command: required"; return null }
        if (command.isBlank()) { errors += "$path.command: must not be empty"; return null }
        if (command.length > MAX_READY_COMMAND_CHARS || '\u0000' in command) { errors += "$path.command: too long or contains NUL"; return null }
        var timeout = DEFAULT_READY_TIMEOUT_SECONDS
        m.get<YamlNode>("timeoutSeconds")?.takeUnless { it is YamlNull }?.let { scalar(it, "$path.timeoutSeconds", errors) }?.let { t ->
            val v = t.trim().toIntOrNull()
            if (v == null || v < 1 || v > MAX_READY_TIMEOUT_SECONDS) {
                errors += "$path.timeoutSeconds: a whole number of seconds between 1 and $MAX_READY_TIMEOUT_SECONDS"
                return null
            }
            timeout = v
        }
        return SandboxReady(command, timeout)
    }

    internal fun unknownKeys(m: YamlMap, allowed: List<String>, prefix: String, errors: MutableList<String>) {
        for (k in m.entries.keys) {
            if (k.content !in allowed) errors += "$prefix${k.content}: unknown key (allowed here: ${allowed.joinToString(", ")})"
        }
    }

    internal fun scalar(node: YamlNode, path: String, errors: MutableList<String>): String? {
        val s = node as? YamlScalar
        if (s == null) {
            errors += "$path: must be a single value, not a ${if (node is YamlMap) "mapping" else if (node is YamlList) "list" else "null"}"
            return null
        }
        return s.content
    }

    private fun envMap(node: YamlNode, path: String, errors: MutableList<String>): Map<String, String>? {
        val m = node as? YamlMap
        if (m == null) { errors += "$path: must be a mapping of NAME: value"; return null }
        if (m.entries.size > MAX_ENV) errors += "$path: at most $MAX_ENV variables"
        val out = linkedMapOf<String, String>()
        for ((k, v) in m.entries) {
            val name = k.content
            if (!ENV_KEY_RE.matches(name)) { errors += "$path.$name: not a valid environment variable name"; continue }
            val value = if (v is YamlNull) "" else scalar(v, "$path.$name", errors) ?: continue
            if ('\u0000' in value || value.length > 4096) { errors += "$path.$name: value too long or contains NUL"; continue }
            out[name] = value
        }
        return out
    }
}
