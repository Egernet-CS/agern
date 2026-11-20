package dk.egernet.factory

import dk.egernet.factory.agent.AgentCommand
import dk.egernet.factory.agent.AgentIdentity
import dk.egernet.factory.agent.LaunchSpec
import dk.egernet.factory.db.iso
import dk.egernet.factory.github.ClaudeCredential
import dk.egernet.factory.security.Base32
import dk.egernet.factory.security.RecoveryCodes
import dk.egernet.factory.security.SecretBox
import dk.egernet.factory.security.Totp
import java.nio.file.Paths
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TotpTest {
    // RFC 6238 Appendix B, SHA-1 seed.
    private val seed = "12345678901234567890".toByteArray()

    @Test
    fun `RFC 6238 test vectors`() {
        val vectors = mapOf(
            59L to "94287082", 1111111109L to "07081804", 1111111111L to "14050471",
            1234567890L to "89005924", 2000000000L to "69279037", 20000000000L to "65353130",
        )
        for ((t, code) in vectors) assertEquals(code, Totp.at(seed, t, digits = 8), "t=$t")
        // 6-digit codes are the low 6 digits
        assertEquals("287082", Totp.at(seed, 59))
    }

    @Test
    fun `RFC 4226 HOTP vectors`() {
        val expected = listOf("755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583", "399871", "520489")
        expected.forEachIndexed { i, c -> assertEquals(c, Totp.hotp(seed, i.toLong())) }
    }

    @Test
    fun `verify accepts plus minus one step and rejects replay`() {
        val t = 1_700_000_000L
        val now = Totp.step(t)
        assertEquals(now, Totp.verify(seed, Totp.at(seed, t), t, null))
        assertEquals(now - 1, Totp.verify(seed, Totp.at(seed, t - 30), t, null))
        assertEquals(now + 1, Totp.verify(seed, Totp.at(seed, t + 30), t, null))
        assertNull(Totp.verify(seed, Totp.at(seed, t - 60), t, null))
        assertNull(Totp.verify(seed, Totp.at(seed, t), t, lastAcceptedStep = now), "replay within the same step")
        assertNull(Totp.verify(seed, "12345", t, null))
        assertNull(Totp.verify(seed, "abcdef", t, null))
    }

    @Test
    fun `base32 round trip and secret size`() {
        val s = Totp.newSecret()
        assertEquals(20, s.size)
        assertTrue(s.contentEquals(Base32.decode(Base32.encode(s))))
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Base32.encode(seed))
        assertTrue(Totp.otpauthUri("Agern", "alice", "ABC").startsWith("otpauth://totp/Agern:alice?secret=ABC&issuer=Agern"))
    }

    @Test
    fun `recovery codes`() {
        val codes = RecoveryCodes.generate()
        assertEquals(10, codes.size)
        assertEquals(10, codes.toSet().size)
        assertTrue(codes.all { RecoveryCodes.looksLikeRecoveryCode(it) })
        assertEquals(RecoveryCodes.hash(codes[0]), RecoveryCodes.hash(codes[0].uppercase().replace("-", " ")))
    }
}

class CryptoTest {
    private val box = SecretBox(ByteArray(32) { it.toByte() })

    @Test
    fun `round trip with fresh nonce and bound username`() {
        val a = box.encrypt("secret", "alice")
        val b = box.encrypt("secret", "alice")
        assertFalse(a.contentEquals(b), "random nonce per value")
        assertEquals("secret", box.decrypt(a, "alice"))
        assertFailsWith<Exception> { box.decrypt(a, "bob") }
        val tampered = a.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<Exception> { box.decrypt(tampered, "alice") }
    }
}

class AgentCommandTest {
    private val spec = LaunchSpec(
        sessionId = UUID.randomUUID(), kind = "security", model = "opus", workDir = Paths.get("/tmp/w"), home = Paths.get("/data/homes/alice"),
        identity = AgentIdentity("alice", "Alice", "alice-gh", "ghp_secret", ClaudeCredential("oauth_token", "sk-ant-oat-x")),
        interactive = false, resumeClaudeSessionId = "abc", appendSystemPrompt = "# Role: security", prompt = "review",
        jsonSchema = "{}", disallowedTools = listOf("Edit", "Write", "NotebookEdit"), mcpConfigJson = "{\"token\":\"t\"}",
    )

    @Test
    fun `environment is built from scratch`() {
        val env = AgentCommand.environment(spec, "/usr/bin:/bin")
        assertEquals(
            setOf("PATH", "HOME", "LANG", "TERM", "CLAUDE_CODE_OAUTH_TOKEN", "GIT_TERMINAL_PROMPT", "GIT_AUTHOR_NAME", "GIT_COMMITTER_NAME",
                "GIT_AUTHOR_EMAIL", "GIT_COMMITTER_EMAIL", "GIT_CONFIG_COUNT", "GIT_CONFIG_KEY_0", "GIT_CONFIG_VALUE_0", "GH_TOKEN"),
            env.keys,
        )
        assertEquals("/data/homes/alice", env["HOME"])
        assertEquals("sk-ant-oat-x", env["CLAUDE_CODE_OAUTH_TOKEN"])
        assertEquals("http.https://github.com/.extraheader", env["GIT_CONFIG_KEY_0"])
        val basic = env["GIT_CONFIG_VALUE_0"]!!.removePrefix("AUTHORIZATION: basic ")
        assertEquals("x-access-token:ghp_secret", String(Base64.getDecoder().decode(basic)))
        assertTrue(System.getenv().keys.filter { it !in env.keys }.none { env.containsKey(it) })

        val apiKeyEnv = AgentCommand.environment(spec.copy(identity = spec.identity.copy(claude = ClaudeCredential("api_key", "sk-ant-api"))), "/bin")
        assertEquals("sk-ant-api", apiKeyEnv["ANTHROPIC_API_KEY"])
        assertFalse(apiKeyEnv.containsKey("CLAUDE_CODE_OAUTH_TOKEN"))
    }

    @Test
    fun `command line carries the contract flags and no secrets`() {
        val args = AgentCommand.args("claude", spec, "/data/homes/alice/.agern/mcp.json")
        val joined = args.joinToString(" ")
        for (flag in listOf("-p", "--output-format stream-json", "--verbose", "--model opus", "--permission-mode bypassPermissions",
            "--strict-mcp-config", "--mcp-config /data/homes/alice/.agern/mcp.json", "--resume abc", "--json-schema {}",
            "--disallowedTools Edit,Write,NotebookEdit")) {
            assertTrue(joined.contains(flag), "missing $flag in $joined")
        }
        assertFalse(joined.contains("--input-format"))
        assertFalse(joined.contains("ghp_secret") || joined.contains("sk-ant") || joined.contains("token"))
        val chat = AgentCommand.args("claude", spec.copy(interactive = true, jsonSchema = null), "/x")
        assertTrue(chat.joinToString(" ").contains("--input-format stream-json --include-partial-messages"))
    }

    @Test
    fun `identity toString does not leak secrets`() {
        assertNotEquals(true, spec.identity.toString().contains("ghp_secret"))
        assertFalse(spec.identity.claude.toString().contains("sk-ant"))
    }
}

class ConfigTest {
    @Test
    fun `text for GitHub links to this installation only when its address is public`() {
        for (u in listOf(
            "http://localhost:8080", "http://127.0.0.1:8080", "http://10.1.2.3:8080", "http://172.16.0.9", "http://192.168.1.5:8080",
            "http://169.254.1.1", "http://100.64.0.1", "http://[::1]:8080", "http://[fd00::1]", "http://[fe80::1]", "http://agern:8080",
            "https://agern.local", "https://agern.lan", "https://build.internal", "not a url", "",
        )) assertTrue(Config.isInternalUrl(u), u)
        for (u in listOf("https://agern.example.com", "http://172.32.0.1", "http://8.8.8.8:8080", "https://[2001:db8::1]")) {
            assertFalse(Config.isInternalUrl(u), u)
        }
        assertEquals(
            "https://agern.example.com/p/app/releases/1",
            Config(masterKey = ByteArray(32), githubOrg = "o", publicUrl = "https://agern.example.com/").pageRef("p/app/releases/1", "the page of release `1.0`"),
        )
        assertEquals("the page of release `1.0` in Agern", Config(masterKey = ByteArray(32), githubOrg = "o", publicUrl = "http://10.1.2.3:8080").pageRef("p/app/releases/1", "the page of release `1.0`"))
    }

    private val base = mapOf("FACTORY_MASTER_KEY" to Base64.getEncoder().encodeToString(ByteArray(32)), "FACTORY_GITHUB_ORG" to "o")

    @Test
    fun `defaults and 2FA policy`() {
        val c = Config.fromEnv(base)
        assertEquals("jdbc:postgresql://localhost:5432/factory", c.dbUrl)
        assertEquals("0.0.0.0", c.host)
        assertTrue(c.twoFactorRequired)
        assertEquals("http://127.0.0.1:8080/mcp", c.mcpUrl)
        assertFalse(c.secureCookies)
        assertTrue(Config.fromEnv(base + ("FACTORY_PUBLIC_URL" to "https://agern.lan")).secureCookies)
        assertFalse(Config.fromEnv(base + mapOf("FACTORY_AUTH" to "dev", "FACTORY_2FA" to "off")).twoFactorRequired)
        assertFailsWith<IllegalArgumentException> { Config.fromEnv(base + mapOf("FACTORY_AUTH" to "dev,ldap", "FACTORY_2FA" to "off")) }
        assertFailsWith<IllegalArgumentException> { Config.fromEnv(base + mapOf("FACTORY_2FA" to "off")) }
        assertFailsWith<IllegalArgumentException> { Config.fromEnv(base + mapOf("FACTORY_MASTER_KEY" to Base64.getEncoder().encodeToString(ByteArray(16)))) }
        assertEquals(setOf("http://localhost:5173"), Config.fromEnv(base + ("FACTORY_ALLOWED_ORIGINS" to "http://localhost:5173/")).allowedOrigins)
    }

    @Test
    fun `a specific non-loopback bind address also listens on loopback for MCP`() {
        fun needs(host: String) = Config.fromEnv(base + ("FACTORY_HOST" to host)).needsLoopbackListener
        assertFalse(needs("0.0.0.0"))
        assertFalse(needs("::"))
        assertFalse(needs("127.0.0.1"))
        assertTrue(needs("192.168.1.20"))
        assertTrue(needs("10.0.0.5"))
        assertTrue(needs("::1"), "IPv6 loopback is not where agents connect (127.0.0.1)")
    }
}

class TimestampTest {
    @Test
    fun `API timestamps always carry milliseconds so they sort as strings`() {
        assertEquals("2026-01-01T10:00:00.000Z", java.time.Instant.parse("2026-01-01T10:00:00Z").iso())
        assertEquals("2026-01-01T10:00:00.120Z", java.time.Instant.parse("2026-01-01T10:00:00.120456Z").iso())
    }
}

class GitHubTokenInfoTest {
    @Test
    fun `scopes header and token kind`() {
        assertEquals(listOf("read:org", "repo"), dk.egernet.factory.github.parseOAuthScopes("repo, read:org"))
        assertEquals(emptyList(), dk.egernet.factory.github.parseOAuthScopes(""))
        assertEquals(null, dk.egernet.factory.github.parseOAuthScopes(null))
        assertEquals("fine_grained", dk.egernet.factory.github.githubTokenKind("github_pat_abc", null))
        assertEquals("classic", dk.egernet.factory.github.githubTokenKind("ghp_abc", listOf("repo")))
        assertEquals(null, dk.egernet.factory.github.githubTokenKind("something", null))
    }
}
