package dk.egernet.factory

import dk.egernet.factory.deploy.DeployDefinitions
import dk.egernet.factory.deploy.ParsedDeploy
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Host key lines for tests: well-formed SSH key blobs (the key material itself is arbitrary). */
object HostKeys {
    private fun blob(vararg parts: ByteArray): String {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d -> for (p in parts) { d.writeInt(p.size); d.write(p) } }
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    fun ed25519(seed: Int = 1) = "ssh-ed25519 " + blob("ssh-ed25519".toByteArray(), ByteArray(32) { (it + seed).toByte() })
    fun ecdsa256() = "ecdsa-sha2-nistp256 " + blob("ecdsa-sha2-nistp256".toByteArray(), "nistp256".toByteArray(), byteArrayOf(4) + ByteArray(64) { 7 })
    fun rsa(bits: Int) = "ssh-rsa " + blob("ssh-rsa".toByteArray(), BigInteger.valueOf(65537).toByteArray(), BigInteger.ONE.shiftLeft(bits - 1).add(BigInteger.ONE).toByteArray())
    /** A blob claiming [embedded] declared as [declared]. */
    fun mismatched(declared: String, embedded: String) = "$declared " + blob(embedded.toByteArray(), ByteArray(32))
}

/** `.agern/deploy.yml` parsing and validation, and the FACTORY_DEPLOY* configuration. */
class DeployDefinitionTest {
    private fun valid(yaml: String) = assertIs<ParsedDeploy.Valid>(DeployDefinitions.parse(yaml), yaml).definition
    private fun errors(yaml: String) = assertIs<ParsedDeploy.Invalid>(DeployDefinitions.parse(yaml), yaml).errors

    private fun target(name: String = "kiosk", body: String) = "targets:\n  $name:\n" + body.trimIndent().lines().joinToString("\n") { "    $it" } + "\n"

    @Test
    fun `a complete definition parses, with defaults`() {
        val d = valid(
            """
            targets:
              kiosk:
                host: kiosk.example.net
                port: 2222
                user: deploy
                hostKey: ${HostKeys.ed25519()}
                description: Kiosk in the lobby
              pi:
                host: 192.168.1.20
                user: pi
                hostKey: ${HostKeys.ecdsa256()}
              v6:
                host: fd00::1
                user: _svc.app
                hostKey: "${HostKeys.rsa(3072)}"
            """.trimIndent(),
        )
        assertEquals(listOf("kiosk", "pi", "v6"), d.targets.map { it.name })
        val k = d.targets[0]
        assertEquals("kiosk.example.net", k.host); assertEquals(2222, k.port); assertEquals("deploy", k.user)
        assertEquals("ssh-ed25519", k.hostKey.type); assertEquals("Kiosk in the lobby", k.description)
        assertTrue(Regex("^SHA256:[A-Za-z0-9+/]{43}$").matches(k.hostKey.fingerprint))
        assertEquals(22, d.targets[1].port)
        assertNull(d.targets[1].description)
        assertEquals("ssh-rsa", d.targets[2].hostKey.type)
    }

    @Test
    fun `every problem is listed with its path`() {
        val e = errors(
            """
            extra: 1
            targets:
              Bad_Name: { host: a, user: b, hostKey: x }
              kiosk:
                host: "bad host!"
                port: 70000
                user: "-x"
                hostKey: ${HostKeys.ed25519()}
                description: "${"d".repeat(201)}"
                password: hunter2
              nohost:
                user: deploy
              list: [ 1, 2 ]
            """.trimIndent(),
        )
        val joined = e.joinToString("\n")
        for (expected in listOf(
            "extra: unknown key", "targets.Bad_Name: target names", "targets.kiosk.host: 'bad host!'", "targets.kiosk.port: a whole number",
            "targets.kiosk.user: '-x'", "targets.kiosk.description: at most 200", "targets.kiosk.password: unknown key",
            "targets.nohost.host: required", "targets.nohost.hostKey: required", "targets.list: must be a mapping",
        )) assertTrue(joined.contains(expected), "missing '$expected' in:\n$joined")
    }

    @Test
    fun `targets are required, at least one and at most 20`() {
        assertTrue(errors("targets:\n").single().startsWith("targets: required"))
        assertTrue(errors("other: 1\n").any { it.startsWith("targets: required") })
        assertTrue(errors("targets: {}\n").single().startsWith("targets: at least one"))
        assertTrue(errors("targets: [ a ]\n").single().startsWith("targets: must be a mapping"))
        val many = "targets:\n" + (1..21).joinToString("") { "  t$it: { host: h$it.example, user: u, hostKey: \"${HostKeys.ed25519(it)}\" }\n" }
        assertTrue(errors(many).single().startsWith("targets: at most 20"))
    }

    @Test
    fun `host keys must be exactly type and base64 of a matching, strong enough key`() {
        fun keyErrors(line: String) = errors(target(body = "host: h.example\nuser: u\nhostKey: \"$line\"")).single()
        keyErrors(HostKeys.mismatched("ssh-ed25519", "ssh-rsa")).let { assertTrue(it.startsWith("targets.kiosk.hostKey: the key is of type 'ssh-rsa'"), it) }
        keyErrors("ssh-ed25519 !!!notbase64").let { assertTrue(it.contains("not valid base64"), it) }
        keyErrors(HostKeys.rsa(1024)).let { assertTrue(it.contains("1024 bits; at least 2048"), it) }
        keyErrors("${HostKeys.ed25519()} root@kiosk").let {
            assertTrue(it.contains("without a comment or host"), it)
            assertTrue(it.contains("ssh-keyscan -t ed25519 <host>"), it)
        }
        keyErrors("kiosk.example.net ${HostKeys.ed25519()}").let { assertTrue(it.contains("ssh-keyscan"), it) }
        keyErrors("ssh-dss AAAAB3NzaC1kc3M=").let { assertTrue(it.contains("not supported"), it) }
        keyErrors("ssh-ed25519 " + Base64.getEncoder().encodeToString(ByteArray(3))).let { assertTrue(it.contains("not a valid SSH public key"), it) }
        keyErrors("ssh-ed25519").let { assertTrue(it.contains("ssh-keyscan"), it) }
    }

    @Test
    fun `hosts are DNS names or address literals`() {
        for (ok in listOf("kiosk", "kiosk.example.net", "10.0.0.1", "::1", "fd00::1:2", "a-b.c")) assertTrue(DeployDefinitions.validHost(ok), ok)
        for (bad in listOf("", "-a.example", "a..b", "[::1]", "300.1.1.1", "1.2.3", "a b", "fe80::1%eth0", "x".repeat(254), "host_name")) {
            assertTrue(!DeployDefinitions.validHost(bad), bad)
        }
    }

    @Test
    fun `broken YAML, anchors, duplicate keys and oversized files are invalid like the sandbox's`() {
        assertTrue(errors("targets: [unclosed").single().startsWith("Not valid YAML"))
        assertTrue(errors("- a list").single().contains("must be a mapping"))
        assertTrue(errors("targets: {}\ntargets: {}\n").single().startsWith("Not valid YAML"))
        assertTrue(errors("x: &a { b: 1 }\ntargets: *a\n").first().startsWith("Not valid YAML"))
        assertTrue(errors("targets: {}\n#" + "x".repeat(70_000)).single().contains("larger than 64 KiB"))
    }

    @Test
    fun `the hash is the SHA-256 of the exact content`() {
        val a = DeployDefinitions.parse("targets: {}\n").sha256
        assertEquals(64, a.length)
        assertTrue(a != DeployDefinitions.parse("targets: {}\n\n").sha256)
    }

    @Test
    fun `deploy configuration from the environment`() {
        fun cfgOf(vararg kv: Pair<String, String>) = DeployConfig.fromEnv(mapOf(*kv)::get)
        cfgOf().let { assertEquals("ssh", it.mode); assertEquals(30 * 60_000L, it.execTimeoutMs); assertEquals(512L shl 20, it.maxCopyBytes) }
        assertEquals("off", cfgOf("FACTORY_DEPLOY" to "OFF").mode)
        cfgOf("FACTORY_DEPLOY_EXEC_TIMEOUT_MINUTES" to "5", "FACTORY_DEPLOY_MAX_COPY_MB" to "10").let {
            assertEquals(5 * 60_000L, it.execTimeoutMs); assertEquals(10L shl 20, it.maxCopyBytes)
        }
        for ((k, v) in listOf("FACTORY_DEPLOY" to "scp", "FACTORY_DEPLOY_EXEC_TIMEOUT_MINUTES" to "0", "FACTORY_DEPLOY_EXEC_TIMEOUT_MINUTES" to "1441",
            "FACTORY_DEPLOY_EXEC_TIMEOUT_MINUTES" to "ten", "FACTORY_DEPLOY_MAX_COPY_MB" to "0", "FACTORY_DEPLOY_MAX_COPY_MB" to "10241")) {
            val e = assertFailsWith<IllegalArgumentException>("$k=$v") { cfgOf(k to v) }
            assertTrue(e.message!!.contains(k), e.message)
        }
        val base = mapOf("FACTORY_MASTER_KEY" to "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "FACTORY_GITHUB_ORG" to "o", "FACTORY_AUTH" to "dev")
        assertFailsWith<IllegalArgumentException> { Config.fromEnv(base + ("FACTORY_DEPLOY" to "fake")) }.let {
            assertTrue(it.message!!.contains("FACTORY_DEV_FAKE_GITHUB"))
        }
        assertEquals("fake", Config.fromEnv(base + ("FACTORY_DEPLOY" to "fake") + ("FACTORY_DEV_FAKE_GITHUB" to "true")).deploy.mode)
        // The MCP tool timeout covers the deploy limit.
        val c = Config.fromEnv(base + ("FACTORY_DEPLOY_EXEC_TIMEOUT_MINUTES" to "1440"))
        assertEquals(1440 * 60_000L + 60_000, c.mcpToolTimeoutMs)
    }
}
