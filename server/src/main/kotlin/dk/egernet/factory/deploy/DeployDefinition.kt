package dk.egernet.factory.deploy

import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import dk.egernet.factory.sandbox.SandboxDefinitions
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/** Where a project declares its deploy targets, relative to the repository root of the default branch. */
const val DEPLOY_FILE = ".agern/deploy.yml"

/**
 * A host key pinned in `.agern/deploy.yml`: its SSH [type] and the key blob ([blob], the base64-decoded second field of
 * an `ssh-keyscan` line). Agern accepts a target's host key only when it equals [blob] byte for byte.
 */
class PinnedHostKey(val type: String, blob: ByteArray) {
    private val bytes = blob.copyOf()
    val blob: ByteArray get() = bytes.copyOf()
    /** OpenSSH style: `SHA256:<base64 without padding>`. */
    val fingerprint: String get() = fingerprintOf(bytes)

    fun matches(presented: ByteArray): Boolean = MessageDigest.isEqual(bytes, presented)

    override fun equals(other: Any?) = other is PinnedHostKey && other.type == type && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = "$type $fingerprint"

    companion object {
        fun fingerprintOf(blob: ByteArray): String =
            "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
    }
}

/** One machine an agent may deploy to. */
data class DeployTarget(
    val name: String,
    /** A DNS name or an IPv4/IPv6 literal (no brackets). */
    val host: String,
    val port: Int,
    val user: String,
    val hostKey: PinnedHostKey,
    val description: String?,
)

/** A validated `.agern/deploy.yml` (docs/DEPLOY.md), targets in file order. */
data class DeployDefinition(val targets: List<DeployTarget>) {
    fun target(name: String): DeployTarget? = targets.firstOrNull { it.name == name }
}

sealed interface ParsedDeploy {
    val sha256: String
    data class Valid(override val sha256: String, val definition: DeployDefinition) : ParsedDeploy
    data class Invalid(override val sha256: String, val errors: List<String>) : ParsedDeploy
}

/**
 * Parses and strictly validates a deploy definition like [SandboxDefinitions]: unknown keys and malformed values are
 * errors, every problem is listed with its path in the file.
 */
object DeployDefinitions {
    const val MAX_BYTES = 64 * 1024
    const val MAX_TARGETS = 20
    const val MAX_DESCRIPTION_CHARS = 200
    const val MIN_RSA_BITS = 2048
    const val DEFAULT_PORT = 22

    val HOST_KEY_TYPES = listOf("ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521", "ssh-rsa")

    private val TOP_KEYS = listOf("targets")
    private val TARGET_KEYS = listOf("host", "port", "user", "hostKey", "description")
    val TARGET_NAME_RE = Regex("^[a-z][a-z0-9-]{0,30}$")
    val USER_RE = Regex("^[A-Za-z_][A-Za-z0-9_.-]{0,31}$")
    private val LABEL_RE = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
    private val IPV4_RE = Regex("^(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}$")
    private val IPV6_CHARS_RE = Regex("^[0-9A-Fa-f:.]+$")

    fun sha256(content: String): String = SandboxDefinitions.sha256(content)

    fun parse(content: String): ParsedDeploy {
        val hash = sha256(content)
        if (content.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            return ParsedDeploy.Invalid(hash, listOf("$DEPLOY_FILE is larger than ${MAX_BYTES / 1024} KiB"))
        }
        val root = SandboxDefinitions.parseYaml(content).getOrElse { return ParsedDeploy.Invalid(hash, listOf(it.message!!)) }
        val errors = mutableListOf<String>()
        val top = root as? YamlMap ?: return ParsedDeploy.Invalid(hash, listOf("The file must be a mapping with `targets:`"))
        SandboxDefinitions.unknownKeys(top, TOP_KEYS, "", errors)
        val targets = mutableListOf<DeployTarget>()
        val node = top.get<YamlNode>("targets")
        val m = node?.takeUnless { it is YamlNull } as? YamlMap
        when {
            node == null || node is YamlNull -> errors += "targets: required (a mapping of target name to { host, user, hostKey })"
            m == null -> errors += "targets: must be a mapping of target name to { host, port, user, hostKey, description }"
            m.entries.isEmpty() -> errors += "targets: at least one target"
            else -> {
                if (m.entries.size > MAX_TARGETS) errors += "targets: at most $MAX_TARGETS targets"
                for ((k, v) in m.entries) target(k.content, v, errors)?.let { targets += it }
            }
        }
        if (errors.isNotEmpty()) return ParsedDeploy.Invalid(hash, errors)
        return ParsedDeploy.Valid(hash, DeployDefinition(targets))
    }

    private fun target(name: String, node: YamlNode, errors: MutableList<String>): DeployTarget? {
        val path = "targets.$name"
        if (!TARGET_NAME_RE.matches(name)) {
            errors += "$path: target names are lower-case letters, digits and '-', start with a letter, at most 31 characters"
            return null
        }
        val tm = node as? YamlMap
        if (tm == null) { errors += "$path: must be a mapping with `host:`, `user:` and `hostKey:`"; return null }
        val before = errors.size
        SandboxDefinitions.unknownKeys(tm, TARGET_KEYS, "$path.", errors)
        fun value(key: String, required: Boolean): String? {
            val n = tm.get<YamlNode>(key)
            if (n == null || n is YamlNull) { if (required) errors += "$path.$key: required"; return null }
            return SandboxDefinitions.scalar(n, "$path.$key", errors)
        }
        val host = value("host", true)?.trim()?.also { if (!validHost(it)) errors += "$path.host: '$it' is neither a DNS name nor an IPv4 or IPv6 address (no brackets)" }
        val port = value("port", false)?.trim().let { p ->
            if (p == null) DEFAULT_PORT
            else p.toIntOrNull()?.takeIf { it in 1..65535 } ?: run { errors += "$path.port: a whole number between 1 and 65535"; null }
        }
        val user = value("user", true)?.trim()?.also {
            if (!USER_RE.matches(it)) errors += "$path.user: '$it' is not a user name (letters, digits, '_', '.', '-', starting with a letter or '_', at most 32 characters)"
        }
        val hostKey = value("hostKey", true)?.let { line -> parseHostKey(line).getOrElse { errors += "$path.hostKey: ${it.message}"; null } }
        val description = value("description", false)?.trim()?.takeIf { it.isNotEmpty() }?.also {
            if (it.length > MAX_DESCRIPTION_CHARS) errors += "$path.description: at most $MAX_DESCRIPTION_CHARS characters"
            if ('\n' in it || '\r' in it || '\u0000' in it) errors += "$path.description: must be one line"
        }
        if (errors.size > before || host == null || port == null || user == null || hostKey == null) return null
        return DeployTarget(name, host, port, user, hostKey, description)
    }

    /** A DNS name (RFC 1123, at most 253 characters) or an IPv4/IPv6 literal without brackets. */
    fun validHost(h: String): Boolean {
        if (h.isEmpty() || h.length > 253) return false
        if (':' in h) {
            // IPv6: only hex digits, ':' and '.' (an embedded IPv4), so the parse below never looks anything up.
            if (!IPV6_CHARS_RE.matches(h)) return false
            return runCatching { java.net.InetAddress.getByName(h) is java.net.Inet6Address }.getOrDefault(false)
        }
        val labels = h.split('.')
        if (labels.all { it.isNotEmpty() && it.all(Char::isDigit) }) return IPV4_RE.matches(h)
        return labels.all { LABEL_RE.matches(it) }
    }

    private const val KEYSCAN_HINT = "get it with `ssh-keyscan -t ed25519 <host>` and drop the first field (the host)"

    /**
     * Parses `<type> <base64>` as `ssh-keyscan` prints it without the host. The blob must carry the declared type, be
     * well formed for it, and an RSA key needs at least [MIN_RSA_BITS] bits.
     */
    fun parseHostKey(line: String): Result<PinnedHostKey> {
        val fields = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (fields.size != 2) {
            return fail(
                if (fields.size > 2) "must be exactly \"<type> <base64>\" without a comment or host; $KEYSCAN_HINT"
                else "must be \"<type> <base64>\"; $KEYSCAN_HINT",
            )
        }
        val (type, b64) = fields
        if (type !in HOST_KEY_TYPES) {
            return fail("type '${type.take(40)}' is not supported (one of ${HOST_KEY_TYPES.joinToString(", ")}); $KEYSCAN_HINT")
        }
        val blob = try {
            Base64.getDecoder().decode(b64)
        } catch (e: IllegalArgumentException) {
            return fail("the key is not valid base64")
        }
        return try {
            val buf = ByteBuffer.wrap(blob)
            val embedded = String(readString(buf), Charsets.US_ASCII)
            if (embedded != type) return fail("the key is of type '${embedded.take(40)}', not '$type'")
            when {
                type == "ssh-ed25519" -> if (readString(buf).size != 32) return fail("not a valid ed25519 key")
                type.startsWith("ecdsa-sha2-") -> {
                    val curve = String(readString(buf), Charsets.US_ASCII)
                    if (curve != type.removePrefix("ecdsa-sha2-")) return fail("the key's curve '${curve.take(20)}' does not match '$type'")
                    val q = readString(buf)
                    if (q.isEmpty() || q[0] != 4.toByte()) return fail("not a valid ECDSA key")
                }
                else -> {
                    readString(buf) // e
                    val n = BigInteger(readString(buf))
                    if (n.signum() <= 0) return fail("not a valid RSA key")
                    if (n.bitLength() < MIN_RSA_BITS) return fail("the RSA key has ${n.bitLength()} bits; at least $MIN_RSA_BITS are needed")
                }
            }
            if (buf.hasRemaining()) return fail("the key has trailing data")
            Result.success(PinnedHostKey(type, blob))
        } catch (e: IllegalArgumentException) {
            fail("the key is not a valid SSH public key")
        }
    }

    private fun fail(msg: String) = Result.failure<PinnedHostKey>(IllegalArgumentException(msg))

    private fun readString(buf: ByteBuffer): ByteArray {
        require(buf.remaining() >= 4)
        val len = buf.int
        require(len >= 0 && len <= buf.remaining())
        return ByteArray(len).also { buf.get(it) }
    }
}
