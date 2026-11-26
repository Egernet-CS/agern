package dk.egernet.factory.deploy

import dk.egernet.factory.sandbox.ExecLimits
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory
import org.apache.sshd.client.channel.ChannelExec
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.NamedFactory
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.apache.sshd.common.signature.BuiltinSignatures
import org.apache.sshd.common.signature.Signature
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import org.apache.sshd.server.forward.RejectAllForwardingFilter
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Where to connect: a target of an approved `.agern/deploy.yml`. */
data class DeployEndpoint(val name: String, val host: String, val port: Int, val user: String, val hostKey: PinnedHostKey) {
    constructor(t: DeployTarget) : this(t.name, t.host, t.port, t.user, t.hostKey)
}

/**
 * A connection did not get as far as a command: [stage] is `connect`, `host_key` or `auth` (the check's stages), or
 * `remote` for a copy the target refused.
 */
class DeployException(val stage: String, message: String) : Exception(message)

/**
 * What a remote command did. [exitCode] null: it ended without an exit status ([signal] names the signal when the
 * server sent one) or it timed out. [output] is stdout and stderr interleaved, the last [ExecLimits.LOG_CHARS] characters.
 */
data class DeployExecResult(
    val exitCode: Int?, val signal: String?, val output: String, val outputTruncated: Boolean, val timedOut: Boolean, val durationMs: Long,
)

/** A local file to upload: [open] gives its content, exactly [size] bytes are sent; [mode] is its permission bits. */
class DeployUpload(val name: String, val size: Long, val mode: Int, val open: () -> InputStream)

/**
 * Makes SSH connections to deploy targets on the agent's behalf. Every method blocks (call it on
 * [kotlinx.coroutines.Dispatchers.IO]) and throws [DeployException] when it cannot connect, the host key is not the pinned
 * one, or the key is refused. [close] ends every open connection (server shutdown).
 */
interface DeployTransport : AutoCloseable {
    /** Connects, verifies the host key, authenticates and disconnects; runs nothing. */
    fun check(endpoint: DeployEndpoint, identity: DeployIdentity)
    /** Runs [command] as one exec request (no PTY, stdin at EOF at once). On timeout the connection is closed. */
    fun exec(endpoint: DeployEndpoint, identity: DeployIdentity, command: String, timeoutMs: Long): DeployExecResult
    /** Uploads one file to [dest] with SCP; returns the bytes sent. Throws [DeployTimeout] after [timeoutMs]. */
    fun copy(endpoint: DeployEndpoint, identity: DeployIdentity, upload: DeployUpload, dest: String, timeoutMs: Long): Long

    /** [check] that [cancel] can abort (the connection is closed; the call then fails or returns early). */
    fun check(endpoint: DeployEndpoint, identity: DeployIdentity, cancel: DeployCancel) = check(endpoint, identity)
    /** [exec] that [cancel] can abort: the connection is closed and the call returns early. */
    fun exec(endpoint: DeployEndpoint, identity: DeployIdentity, command: String, timeoutMs: Long, cancel: DeployCancel): DeployExecResult =
        exec(endpoint, identity, command, timeoutMs)
    /** [copy] that [cancel] can abort: the connection is closed and the call fails. */
    fun copy(endpoint: DeployEndpoint, identity: DeployIdentity, upload: DeployUpload, dest: String, timeoutMs: Long, cancel: DeployCancel): Long =
        copy(endpoint, identity, upload, dest, timeoutMs)
}

/** A copy did not finish within its time limit; the connection was closed. */
class DeployTimeout(message: String) : Exception(message)

/**
 * Aborts operations in flight from another thread (a sandbox VM lease that ends while a command runs): [cancel] runs
 * every registered hook once; a hook registered after [cancel] runs at once. A thread interrupt does not end MINA's
 * waits, so the transport closes its connection instead.
 */
class DeployCancel {
    private val hooks = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    @Volatile var cancelled = false
        private set

    /** Registers [hook]; the returned handle unregisters it. */
    fun onCancel(hook: () -> Unit): AutoCloseable {
        hooks += hook
        if (cancelled) runCatching { hook() }
        return AutoCloseable { hooks -= hook }
    }

    fun cancel() {
        if (cancelled) return
        cancelled = true
        for (h in hooks) runCatching { h() }
    }
}

/**
 * The real transport: Apache MINA SSHD in this process, so the private key never reaches a file, a process argument or
 * an agent. One client per operation, configured for exactly the pinned host key type; no known_hosts, no ~/.ssh
 * configuration or keys, no agent, no forwarding, public-key authentication only.
 */
class MinaDeployTransport(
    private val connectTimeoutMs: Long = 15_000,
    private val authTimeoutMs: Long = 15_000,
) : DeployTransport {
    private val open: MutableSet<SshClient> = ConcurrentHashMap.newKeySet()
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "deploy-watchdog").apply { isDaemon = true } }
    @Volatile private var closed = false

    override fun check(endpoint: DeployEndpoint, identity: DeployIdentity) {
        session(endpoint, identity, null) { }
    }

    override fun check(endpoint: DeployEndpoint, identity: DeployIdentity, cancel: DeployCancel) {
        session(endpoint, identity, cancel) { }
    }

    override fun exec(endpoint: DeployEndpoint, identity: DeployIdentity, command: String, timeoutMs: Long): DeployExecResult =
        execCancellable(endpoint, identity, command, timeoutMs, null)

    override fun exec(endpoint: DeployEndpoint, identity: DeployIdentity, command: String, timeoutMs: Long, cancel: DeployCancel): DeployExecResult =
        execCancellable(endpoint, identity, command, timeoutMs, cancel)

    private fun execCancellable(endpoint: DeployEndpoint, identity: DeployIdentity, command: String, timeoutMs: Long, cancel: DeployCancel?): DeployExecResult {
        val started = System.nanoTime()
        return session(endpoint, identity, cancel) { s ->
            val out = TailOutput(ExecLimits.LOG_CHARS * 4L)
            val ch: ChannelExec = s.createExecChannel(command)
            ch.isUsePty = false
            ch.setIn(ByteArrayInputStream(ByteArray(0)))
            ch.setOut(out)
            ch.setErr(out)
            ch.open().verify(Duration.ofMillis(minOf(timeoutMs, connectTimeoutMs)))
            val left = timeoutMs - (System.nanoTime() - started) / 1_000_000
            val events = ch.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), left.coerceAtLeast(1))
            val timedOut = ClientChannelEvent.CLOSED !in events
            if (timedOut) runCatching { s.close(true) }
            val text = out.text()
            DeployExecResult(
                exitCode = if (timedOut) null else ch.exitStatus, signal = if (timedOut) null else ch.exitSignal,
                output = text.takeLast(ExecLimits.LOG_CHARS), outputTruncated = out.truncated || text.length > ExecLimits.LOG_CHARS,
                timedOut = timedOut, durationMs = (System.nanoTime() - started) / 1_000_000,
            )
        }
    }

    override fun copy(endpoint: DeployEndpoint, identity: DeployIdentity, upload: DeployUpload, dest: String, timeoutMs: Long): Long =
        copyCancellable(endpoint, identity, upload, dest, timeoutMs, null)

    override fun copy(endpoint: DeployEndpoint, identity: DeployIdentity, upload: DeployUpload, dest: String, timeoutMs: Long, cancel: DeployCancel): Long =
        copyCancellable(endpoint, identity, upload, dest, timeoutMs, cancel)

    private fun copyCancellable(endpoint: DeployEndpoint, identity: DeployIdentity, upload: DeployUpload, dest: String, timeoutMs: Long, cancel: DeployCancel?): Long =
        session(endpoint, identity, cancel) { s ->
            // A minimal SCP source (the protocol `scp -t` speaks): the content comes from an open stream, so what is sent
            // is exactly the file that was checked, and the remote side keeps the file's name when [dest] is a directory.
            // [dest] matched a pattern without spaces or shell metacharacters: the remote shell sees it unchanged.
            val ch = s.createExecChannel("scp -p -t -- $dest")
            ch.isUsePty = false
            val timer = watchdog.schedule({ runCatching { s.close(true) } }, timeoutMs, TimeUnit.MILLISECONDS)
            try {
                ch.open().verify(Duration.ofMillis(minOf(timeoutMs, connectTimeoutMs)))
                val toRemote = ch.invertedIn
                val fromRemote = ch.invertedOut
                ack(fromRemote)
                val now = System.currentTimeMillis() / 1000
                toRemote.write("T$now 0 $now 0\n".toByteArray(Charsets.UTF_8)); toRemote.flush()
                ack(fromRemote)
                toRemote.write("C%04o %d %s\n".format(upload.mode and 0x1ff, upload.size, upload.name).toByteArray(Charsets.UTF_8)); toRemote.flush()
                ack(fromRemote)
                var sent = 0L
                upload.open().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (sent < upload.size) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), upload.size - sent).toInt())
                        if (n < 0) throw DeployException("remote", "The file became shorter while it was being sent")
                        toRemote.write(buf, 0, n)
                        sent += n
                    }
                }
                toRemote.write(0); toRemote.flush()
                ack(fromRemote)
                toRemote.close()
                ch.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), Duration.ofSeconds(10))
                sent
            } catch (e: IOException) {
                if (timer.isDone && !timer.isCancelled) throw DeployTimeout("The copy did not finish within ${timeoutMs / 1000} s; the connection was closed.")
                throw e
            } finally {
                timer.cancel(false)
            }
        }

    /** Reads one SCP acknowledgement: 0 is fine, 1 and 2 carry the remote's error message. */
    private fun ack(input: InputStream) {
        when (val b = input.read()) {
            0 -> return
            -1 -> throw IOException("The target closed the connection (is scp installed there, and is the key not limited to a forced command?)")
            1, 2 -> {
                val msg = StringBuilder()
                while (true) {
                    val c = input.read()
                    if (c == -1 || c == '\n'.code || msg.length > 1000) break
                    msg.append(c.toChar())
                }
                throw DeployException("remote", "The target refused the copy: ${msg.toString().trim().removePrefix("scp: ")}")
            }
            else -> throw IOException("Unexpected answer from the target's scp (byte $b)")
        }
    }

    /**
     * Opens a session to [endpoint], verified and authenticated, runs [block] and closes everything. [cancel] stops the
     * client, which closes the connection and ends every wait of [block].
     */
    private fun <T> session(endpoint: DeployEndpoint, identity: DeployIdentity, cancel: DeployCancel?, block: (ClientSession) -> T): T {
        if (closed) throw DeployException("connect", "The server is shutting down")
        if (cancel?.cancelled == true) throw DeployException("connect", "The operation was cancelled")
        val pinned = endpoint.hostKey
        val presented = java.util.concurrent.atomic.AtomicReference<String?>()
        val verified = java.util.concurrent.atomic.AtomicBoolean()
        val client = SshClient.setUpDefaultClient()
        client.signatureFactories = hostKeySignatures(pinned.type)
        client.userAuthFactories = listOf(UserAuthPublicKeyFactory(listOf<NamedFactory<Signature>>(BuiltinSignatures.ed25519)))
        client.serverKeyVerifier = ServerKeyVerifier { _, _, key ->
            val blob = ByteArrayBuffer().apply { putPublicKey(key) }.compactData
            if (pinned.matches(blob)) { verified.set(true); true }
            else { presented.set(KeyUtils.getFingerPrint(BuiltinDigests.sha256, key)); false }
        }
        client.hostConfigEntryResolver = HostConfigEntryResolver.EMPTY
        client.keyIdentityProvider = KeyIdentityProvider.EMPTY_KEYS_PROVIDER
        client.agentFactory = null
        client.forwardingFilter = RejectAllForwardingFilter.INSTANCE
        open += client
        val hook = cancel?.onCancel { runCatching { client.stop() } }
        try {
            client.start()
            val s = try {
                client.connect(endpoint.user, endpoint.host, endpoint.port).verify(Duration.ofMillis(connectTimeoutMs)).session
            } catch (e: Exception) {
                throw DeployException("connect", "${endpoint.host}:${endpoint.port}: ${reason(e)}")
            }
            s.use {
                s.addPublicKeyIdentity(identity.keyPair())
                try {
                    s.auth().verify(Duration.ofMillis(authTimeoutMs))
                } catch (e: Exception) {
                    presented.get()?.let { fp ->
                        throw DeployException("host_key", "Host key of ${endpoint.name} does not match the pinned key (presented $fp)")
                    }
                    if (!verified.get()) {
                        val why = reason(e)
                        if (why.contains("negotiat", ignoreCase = true) || why.contains("host key", ignoreCase = true)) {
                            throw DeployException("host_key", "${endpoint.name} did not present a ${pinned.type} host key: $why")
                        }
                        throw DeployException("connect", "${endpoint.host}:${endpoint.port}: $why")
                    }
                    throw DeployException("auth", "${endpoint.user}@${endpoint.host} refused the project's deploy key (${identity.fingerprint}): ${reason(e)}")
                }
                return block(s)
            }
        } finally {
            hook?.close()
            open -= client
            runCatching { client.stop() }
        }
    }

    private fun reason(e: Throwable): String {
        var t: Throwable = e
        while (t.cause != null && t.cause !== t && (t.message.isNullOrBlank() || t is org.apache.sshd.common.SshException)) t = t.cause!!
        return (t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName).take(500)
    }

    override fun close() {
        closed = true
        for (c in open.toList()) runCatching { c.stop() }
        watchdog.shutdownNow()
    }

    companion object {
        /** Host-key algorithms the client offers: only those of the pinned key's type, so the server presents that key. */
        fun hostKeySignatures(type: String): List<NamedFactory<Signature>> = when (type) {
            "ssh-ed25519" -> listOf(BuiltinSignatures.ed25519)
            "ecdsa-sha2-nistp256" -> listOf(BuiltinSignatures.nistp256)
            "ecdsa-sha2-nistp384" -> listOf(BuiltinSignatures.nistp384)
            "ecdsa-sha2-nistp521" -> listOf(BuiltinSignatures.nistp521)
            "ssh-rsa" -> listOf(BuiltinSignatures.rsaSHA512, BuiltinSignatures.rsaSHA256)
            else -> throw IllegalArgumentException("unsupported host key type $type")
        }
    }
}

/** Keeps the last [maxBytes] bytes written (stdout and stderr interleaved as they arrive). */
private class TailOutput(private val maxBytes: Long) : OutputStream() {
    private val buf = java.io.ByteArrayOutputStream()
    @Volatile var truncated = false
        private set

    @Synchronized override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
        buf.write(b, off, len)
        if (buf.size() > maxBytes * 2) {
            val all = buf.toByteArray()
            buf.reset()
            buf.write(all, (all.size - maxBytes).toInt(), maxBytes.toInt())
            truncated = true
        }
    }

    @Synchronized fun text(): String {
        val all = buf.toByteArray()
        if (all.size > maxBytes) { truncated = true; return String(all, (all.size - maxBytes).toInt(), maxBytes.toInt(), Charsets.UTF_8) }
        return String(all, Charsets.UTF_8)
    }
}

/**
 * FACTORY_DEPLOY=fake (development and end-to-end tests): connects nowhere and runs nothing. Commands get the small
 * repertoire of the fake sandbox (`echo`, `sleep N`, `exit N`, `true`, `false`; anything else prints what would have
 * run); a copy reports the file's size. Hosts starting with `unreachable`, `badkey` or `noauth` fail at the connection,
 * the host key or the authentication.
 */
class DevFakeDeployTransport : DeployTransport {
    private fun connect(endpoint: DeployEndpoint, identity: DeployIdentity) {
        Thread.sleep(50)
        val h = endpoint.host.lowercase()
        when {
            h.startsWith("unreachable") -> throw DeployException("connect", "${endpoint.host}:${endpoint.port}: Connection refused (fake)")
            h.startsWith("badkey") -> throw DeployException(
                "host_key",
                "Host key of ${endpoint.name} does not match the pinned key (presented SHA256:fakefakefakefakefakefakefakefakefakefakefak)",
            )
            h.startsWith("noauth") -> throw DeployException("auth", "${endpoint.user}@${endpoint.host} refused the project's deploy key (${identity.fingerprint})")
        }
    }

    override fun check(endpoint: DeployEndpoint, identity: DeployIdentity) = connect(endpoint, identity)

    override fun exec(endpoint: DeployEndpoint, identity: DeployIdentity, command: String, timeoutMs: Long): DeployExecResult {
        connect(endpoint, identity)
        val started = System.currentTimeMillis()
        val out = StringBuilder()
        var exit = 0
        // A sandbox VM command starts with its `export AGERN_SHA=… AGERN_RUN_ID=… AGERN_ARCHIVE=…` line: nothing to run.
        val body = if (command.startsWith("export AGERN_")) command.substringAfter('\n', "") else command
        for (part in body.split(Regex("&&|;|\\n")).map { it.trim() }.filter { it.isNotEmpty() }) {
            val words = part.split(Regex("\\s+"))
            when (words[0]) {
                "echo" -> out.append(words.drop(1).joinToString(" ") { it.trim('"', '\'') }).append('\n')
                "sleep" -> {
                    val ms = ((words.getOrNull(1)?.toDoubleOrNull() ?: 1.0) * 1000).toLong()
                    if (ms >= timeoutMs) {
                        Thread.sleep(timeoutMs)
                        return DeployExecResult(null, null, out.toString(), false, true, System.currentTimeMillis() - started)
                    }
                    Thread.sleep(ms)
                }
                "exit" -> { exit = words.getOrNull(1)?.toIntOrNull() ?: 0; break }
                "false" -> { exit = 1; break }
                "true" -> {}
                else -> out.append("[fake deploy ${endpoint.user}@${endpoint.host}] would run: ").append(part).append('\n')
            }
        }
        return DeployExecResult(exit, null, out.toString(), false, false, System.currentTimeMillis() - started)
    }

    override fun copy(endpoint: DeployEndpoint, identity: DeployIdentity, upload: DeployUpload, dest: String, timeoutMs: Long): Long {
        connect(endpoint, identity)
        return upload.size
    }

    override fun close() {}
}
