package dk.egernet.factory

import dk.egernet.factory.sandboxvm.ParsedSandboxVm
import dk.egernet.factory.sandboxvm.SandboxVmDefinitions
import dk.egernet.factory.sandboxvm.SandboxVmManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `.agern/sandbox-vm.yml` parsing and validation, the command prefix, and the FACTORY_SANDBOX_VM* configuration. */
class SandboxVmDefinitionTest {
    private val minimal = "host: vm.example.net\nuser: agern\nhostKey: ${HostKeys.ed25519()}\ncheckout: tar xzf \"\$AGERN_ARCHIVE\"\n"

    private fun errors(content: String): List<String> = when (val p = SandboxVmDefinitions.parse(content)) {
        is ParsedSandboxVm.Invalid -> p.errors
        is ParsedSandboxVm.Valid -> error("expected invalid, got ${p.definition}")
    }

    private fun valid(content: String) = (SandboxVmDefinitions.parse(content) as? ParsedSandboxVm.Valid)?.definition
        ?: error("invalid: ${(SandboxVmDefinitions.parse(content) as ParsedSandboxVm.Invalid).errors}")

    @Test
    fun `a full and a minimal file`() {
        val full = """
            name: staging
            host: Sandbox.Example.net
            port: 2222
            user: agern
            hostKey: ${HostKeys.ed25519()}
            description: Staging VM
            url: https://sandbox.example.net/app
            checkout: |
              rm -rf /srv/app && tar xzf "${'$'}AGERN_ARCHIVE" -C /srv/app
            setup: sudo systemctl restart app
            ready:
              command: curl -fsS http://127.0.0.1:8080/health
              timeoutSeconds: 120
            reset: /srv/app/bin/restore-test-db
        """.trimIndent()
        val d = valid(full)
        assertEquals("staging", d.name)
        assertEquals(2222, d.port)
        assertEquals("sandbox.example.net:2222", d.vmKey)
        assertEquals("https://sandbox.example.net/app", d.url)
        assertEquals(120, d.ready!!.timeoutSeconds)
        assertEquals("sudo systemctl restart app", d.setup)
        assertEquals("/srv/app/bin/restore-test-db", d.reset)
        assertTrue(d.checkout.startsWith("rm -rf"))

        val m = valid(minimal)
        assertEquals("sandbox-vm", m.name)
        assertEquals(22, m.port)
        assertNull(m.url); assertNull(m.setup); assertNull(m.ready); assertNull(m.reset); assertNull(m.description)
        assertEquals(60, valid(minimal + "ready:\n  command: true\n").ready!!.timeoutSeconds)
    }

    @Test
    fun `every rule has an error with its path`() {
        assertTrue(errors(minimal + "colour: blue\n").single().startsWith("colour: unknown key"))
        assertTrue(errors(minimal + "ready:\n  command: true\n  retries: 3\n").single().startsWith("ready.retries: unknown key"))
        assertEquals(listOf("checkout: required"), errors(minimal.lines().filterNot { it.startsWith("checkout") }.joinToString("\n")))
        assertEquals(listOf("checkout: must not be empty"), errors(minimal.replace(Regex("checkout: .*"), "checkout: \"  \"")))
        val noHost = errors("user: agern\nhostKey: ${HostKeys.ed25519()}\ncheckout: x\n")
        assertEquals(listOf("host: required"), noHost)
        for (bad in listOf("javascript:alert(1)", "ftp://vm.example.net/", "https:///path", "/relative", "https://exa mple.net/")) {
            val e = errors(minimal + "url: \"$bad\"\n")
            assertTrue(e.single().startsWith("url: "), "$bad: $e")
        }
        assertTrue(errors(minimal + "url: https://x.example.net/" + "a".repeat(2001) + "\n").single().contains("at most 2000"))
        for (t in listOf("0", "601", "soon")) {
            assertTrue(errors(minimal + "ready:\n  command: true\n  timeoutSeconds: $t\n").single().startsWith("ready.timeoutSeconds:"), t)
        }
        assertEquals(listOf("ready.command: required"), errors(minimal + "ready:\n  timeoutSeconds: 5\n"))
        assertTrue(errors(minimal + "setup: \"" + "x".repeat(32 * 1024 + 1) + "\"\n").single().startsWith("setup: longer than"))
        assertTrue(errors(minimal + "reset: \"a\\0b\"\n").single().startsWith("reset: contains a NUL"))
        assertTrue(errors(minimal + "port: 70000\n").single().startsWith("port:"))
        assertTrue(errors(minimal + "name: Bad_Name\n").single().startsWith("name:"))
        assertTrue(errors(minimal + "description: \"a\\nb\"\n").single().startsWith("description: must be one line"))
        assertTrue(errors(minimal.replace(Regex("user: .*"), "user: \"-x\"")).single().startsWith("user:"))
        assertTrue(errors(minimal.replace(Regex("hostKey: .*"), "hostKey: ssh-dss AAAA")).single().startsWith("hostKey:"))
        assertTrue(errors(minimal.replace(Regex("host: .*"), "host: \"bad host\"")).single().startsWith("host:"))
        // Anchors, aliases and duplicate keys are refused like everywhere under .agern/.
        assertTrue(errors(minimal + "setup: &a x\nreset: *a\n").single().startsWith("Not valid YAML"))
        assertTrue(errors(minimal + "host: other.example.net\n").single().startsWith("Not valid YAML"))
        assertTrue(errors("x".repeat(64 * 1024 + 1)).single().contains("larger than 64 KiB"))
        assertTrue(errors("- a\n- b\n").single().startsWith("The file must be a mapping"))
        // Several problems are all listed.
        assertEquals(2, errors(minimal + "port: 0\nurl: ftp://x/\n").size)
    }

    @Test
    fun `the command prefix carries only validated values`() {
        val sha = "a".repeat(40)
        val run = UUID.fromString("12345678-1234-1234-1234-123456789abc")
        assertEquals(
            "export AGERN_SHA='$sha' AGERN_RUN_ID='$run' AGERN_ARCHIVE=\"\$HOME/agern-sandbox-vm.tar.gz\"",
            SandboxVmManager.envLine(sha, run),
        )
        assertEquals("export AGERN_SHA='' AGERN_RUN_ID='' AGERN_ARCHIVE=\"\$HOME/agern-sandbox-vm.tar.gz\"", SandboxVmManager.envLine("x'; rm -rf /", null))
    }

    @Test
    fun `configuration from the environment`() {
        val d = SandboxVmConfig.fromEnv { null }
        assertEquals("ssh", d.mode); assertEquals(30 * 60_000L, d.execTimeoutMs); assertEquals(120 * 60_000L, d.previewMs)
        val env = mapOf("FACTORY_SANDBOX_VM" to "off", "FACTORY_SANDBOX_VM_EXEC_TIMEOUT_MINUTES" to "5", "FACTORY_SANDBOX_VM_PREVIEW_MINUTES" to "10")
        val c = SandboxVmConfig.fromEnv { env[it] }
        assertEquals(false, c.enabled); assertEquals(5 * 60_000L, c.execTimeoutMs); assertEquals(10 * 60_000L, c.previewMs)
        for ((k, v) in listOf("FACTORY_SANDBOX_VM" to "maybe", "FACTORY_SANDBOX_VM_EXEC_TIMEOUT_MINUTES" to "0",
            "FACTORY_SANDBOX_VM_EXEC_TIMEOUT_MINUTES" to "1441", "FACTORY_SANDBOX_VM_PREVIEW_MINUTES" to "4",
            "FACTORY_SANDBOX_VM_PREVIEW_MINUTES" to "soon")) {
            assertFailsWith<IllegalArgumentException>("$k=$v") { SandboxVmConfig.fromEnv { if (it == k) v else null } }
        }
        val base = mapOf("FACTORY_MASTER_KEY" to "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "FACTORY_GITHUB_ORG" to "o", "FACTORY_AUTH" to "dev")
        assertTrue(assertFailsWith<IllegalArgumentException> { Config.fromEnv(base + ("FACTORY_SANDBOX_VM" to "fake")) }.message!!.contains("FACTORY_DEV_FAKE_GITHUB"))
        assertEquals("fake", Config.fromEnv(base + ("FACTORY_SANDBOX_VM" to "fake") + ("FACTORY_DEV_FAKE_GITHUB" to "true")).sandboxVm.mode)
        // The MCP tool timeout covers the VM's command limit.
        val full = Config.fromEnv(base + ("FACTORY_SANDBOX_VM_EXEC_TIMEOUT_MINUTES" to "1440"))
        assertEquals(1440 * 60_000L + 60_000, full.mcpToolTimeoutMs)
    }
}
