package dk.egernet.factory

import dk.egernet.factory.deploy.DeployEndpoint
import dk.egernet.factory.deploy.DeployException
import dk.egernet.factory.deploy.DeployIdentity
import dk.egernet.factory.deploy.DeployKeys
import dk.egernet.factory.deploy.DeployUpload
import dk.egernet.factory.deploy.MinaDeployTransport
import dk.egernet.factory.deploy.PinnedHostKey
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import org.apache.sshd.scp.server.ScpCommandFactory
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPair
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real transport against Apache MINA's embedded SSH server on a loopback port: no system `ssh` or `sshd` needed.
 * The server accepts only the project's generated key; its commands are a tiny test repertoire, plus SCP into a
 * temporary directory.
 */
class MinaDeployTransportTest {
    private val generated = DeployKeys.generate("agern-deploy:test-org/demo-app")
    private val identity = DeployIdentity(generated.privateKey, generated.fingerprint)
    private val ed25519Host: KeyPair = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256)
    private val rsaHost: KeyPair = KeyUtils.generateKeyPair(KeyPairProvider.SSH_RSA, 2048)
    private val root: Path = Files.createTempDirectory("deploy-target")
    private val authAttempts = AtomicInteger()
    private val commands = CopyOnWriteArrayList<String>()
    private val servers = mutableListOf<SshServer>()
    private val transport = MinaDeployTransport(connectTimeoutMs = 5_000, authTimeoutMs = 5_000)

    @AfterTest fun tearDown() {
        transport.close()
        servers.forEach { runCatching { it.stop(true) } }
        root.toFile().deleteRecursively()
    }

    /** `out <text>`, `err <text>`, `exit <n>`, `sleep <ms>`, separated by `;`; anything else exits 127. */
    private inner class TestCommand(private val line: String) : Command {
        private lateinit var out: OutputStream
        private lateinit var err: OutputStream
        private lateinit var exit: ExitCallback
        override fun setInputStream(`in`: InputStream) {}
        override fun setOutputStream(out: OutputStream) { this.out = out }
        override fun setErrorStream(err: OutputStream) { this.err = err }
        override fun setExitCallback(callback: ExitCallback) { exit = callback }
        override fun destroy(channel: ChannelSession) {}
        override fun start(channel: ChannelSession, env: Environment) {
            commands += line
            Thread {
                var code = 0
                try {
                    for (part in line.split(';').map { it.trim() }) {
                        val word = part.substringBefore(' ')
                        val rest = part.substringAfter(' ', "")
                        when (word) {
                            "out" -> { out.write("$rest\n".toByteArray()); out.flush() }
                            "err" -> { err.write("$rest\n".toByteArray()); err.flush() }
                            "exit" -> { code = rest.toInt(); break }
                            "sleep" -> Thread.sleep(rest.toLong())
                            else -> { code = 127; break }
                        }
                    }
                } catch (_: Exception) {
                    code = 255
                }
                exit.onExit(code)
            }.start()
        }
    }

    private fun server(vararg hostKeys: KeyPair, acceptKey: Boolean = true): Int {
        val sshd = SshServer.setUpDefaultServer()
        sshd.host = "127.0.0.1"
        sshd.port = 0
        sshd.keyPairProvider = KeyPairProvider.wrap(*hostKeys)
        val accepted = DeployKeys.load(generated.privateKey).public
        sshd.publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { _, key, _ ->
            authAttempts.incrementAndGet()
            acceptKey && KeyUtils.compareKeys(accepted, key)
        }
        sshd.passwordAuthenticator = null
        sshd.keyboardInteractiveAuthenticator = null
        sshd.fileSystemFactory = VirtualFileSystemFactory(root)
        sshd.commandFactory = ScpCommandFactory.Builder()
            .withDelegate(CommandFactory { _, line -> TestCommand(line) })
            .build()
        sshd.start()
        servers += sshd
        return sshd.port
    }

    private fun pin(kp: KeyPair): PinnedHostKey {
        val blob = ByteArrayBuffer().apply { putPublicKey(kp.public) }.compactData
        return PinnedHostKey(KeyUtils.getKeyType(kp.public), blob)
    }

    private fun endpoint(port: Int, key: KeyPair) = DeployEndpoint("kiosk", "127.0.0.1", port, "deploy", pin(key))

    @Test
    fun `the generated key is ed25519 with an OpenSSH line and fingerprint, and loads again`() {
        assertTrue(generated.publicKey.startsWith("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5"), generated.publicKey)
        assertTrue(generated.publicKey.endsWith(" agern-deploy:test-org/demo-app"))
        assertTrue(Regex("^SHA256:[A-Za-z0-9+/]{43}$").matches(generated.fingerprint), generated.fingerprint)
        val kp = DeployKeys.load(generated.privateKey)
        assertEquals(generated.publicKey.substringBeforeLast(' '), PublicKeyEntry.toString(kp.public))
        assertFalse(identity.toString().contains("PRIVATE"))
        assertFalse(generated.toString().contains("PRIVATE"))
    }

    @Test
    fun `exec returns the exit code and stdout and stderr interleaved`() {
        val port = server(ed25519Host)
        val r = transport.exec(endpoint(port, ed25519Host), identity, "out hello; err oops; out bye; exit 3", 10_000)
        assertEquals(3, r.exitCode)
        assertFalse(r.timedOut)
        assertEquals("hello\noops\nbye\n", r.output)
        assertEquals(listOf("out hello; err oops; out bye; exit 3"), commands)
        assertEquals(0, transport.exec(endpoint(port, ed25519Host), identity, "out ok", 10_000).exitCode)
    }

    @Test
    fun `check connects, verifies and authenticates without running anything`() {
        val port = server(ed25519Host)
        transport.check(endpoint(port, ed25519Host), identity)
        assertTrue(authAttempts.get() >= 1) // a key query, then the signed attempt
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `copy transfers the content with its mode, into a directory under the file's name`() {
        val port = server(ed25519Host)
        Files.createDirectories(root.resolve("opt/app"))
        val local = Files.createTempFile("app", ".sh")
        try {
            Files.writeString(local, "#!/bin/sh\necho hi\n")
            Files.setPosixFilePermissions(local, PosixFilePermissions.fromString("rwxr-x---"))
            val size = Files.size(local)
            val upload = DeployUpload("app.sh", size, 0b111_101_000) { Files.newInputStream(local) }
            assertEquals(size, transport.copy(endpoint(port, ed25519Host), identity, upload, "/opt/app/", 10_000))
            val remote = root.resolve("opt/app/app.sh")
            assertEquals("#!/bin/sh\necho hi\n", Files.readString(remote))
            assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(remote)))
            // A file path: the content goes exactly there.
            transport.copy(endpoint(port, ed25519Host), identity, DeployUpload("app.sh", size, 0b110_100_100) { Files.newInputStream(local) },
                "/opt/app/run.sh", 10_000)
            assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("opt/app/run.sh"))))
            // A directory that does not exist: the target's scp refuses.
            val e = assertFailsWith<DeployException> {
                transport.copy(endpoint(port, ed25519Host), identity, DeployUpload("app.sh", size, 0b110_100_100) { Files.newInputStream(local) },
                    "/missing/dir/", 10_000)
            }
            assertEquals("remote", e.stage)
        } finally {
            Files.deleteIfExists(local)
        }
    }

    @Test
    fun `a different host key aborts before authentication`() {
        val port = server(ed25519Host)
        val other = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256)
        val e = assertFailsWith<DeployException> { transport.exec(endpoint(port, other), identity, "out never", 10_000) }
        assertEquals("host_key", e.stage)
        assertContains(e.message!!, "Host key of kiosk does not match the pinned key (presented SHA256:")
        assertContains(e.message!!, KeyUtils.getFingerPrint(org.apache.sshd.common.digest.BuiltinDigests.sha256, ed25519Host.public))
        assertEquals(0, authAttempts.get(), "no authentication attempted")
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `a key the server does not accept is an auth failure naming the fingerprint`() {
        val port = server(ed25519Host, acceptKey = false)
        val e = assertFailsWith<DeployException> { transport.check(endpoint(port, ed25519Host), identity) }
        assertEquals("auth", e.stage, e.message)
        assertContains(e.message!!, generated.fingerprint)
        assertTrue(authAttempts.get() >= 1)
    }

    @Test
    fun `an unreachable port is a connect failure`() {
        val port = ServerSocket(0).use { it.localPort }
        val e = assertFailsWith<DeployException> { transport.check(endpoint(port, ed25519Host), identity) }
        assertEquals("connect", e.stage, e.message)
    }

    @Test
    fun `a command that runs too long times out and the connection is closed`() {
        val port = server(ed25519Host)
        val started = System.nanoTime()
        val r = transport.exec(endpoint(port, ed25519Host), identity, "out started; sleep 20000; out never", 800)
        assertTrue(r.timedOut)
        assertNull(r.exitCode)
        assertContains(r.output, "started")
        assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000)
    }

    @Test
    fun `a server with an RSA and an ed25519 host key presents the pinned one, either way`() {
        val port = server(rsaHost, ed25519Host)
        assertEquals(0, transport.exec(endpoint(port, ed25519Host), identity, "out a", 10_000).exitCode)
        assertEquals(0, transport.exec(endpoint(port, rsaHost), identity, "out b", 10_000).exitCode)
        val onlyRsa = server(rsaHost)
        val before = authAttempts.get()
        val e = assertFailsWith<DeployException> { transport.check(endpoint(onlyRsa, ed25519Host), identity) }
        assertEquals("host_key", e.stage, e.message)
        assertEquals(before, authAttempts.get(), "no authentication attempted")
    }
}
