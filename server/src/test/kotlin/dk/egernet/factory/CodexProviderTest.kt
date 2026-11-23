package dk.egernet.factory

import dk.egernet.factory.agent.Sniff
import dk.egernet.factory.images.CodexImageProvider
import dk.egernet.factory.images.ImageProviderException
import dk.egernet.factory.images.ImageRequest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [CodexImageProvider] against the fake CLI (src/test/resources/fake-codex); no database. */
class CodexProviderTest {
    companion object {
        fun fakeCodex(): String = Paths.get(CodexProviderTest::class.java.getResource("/fake-codex")!!.toURI()).toString()
        const val LOGIN = """{"tokens":{"refresh_token":"fake-refresh-1"}}"""
        val PATH: String = System.getenv("PATH") ?: "/usr/local/bin:/usr/bin:/bin"
    }

    private val base: Path = Files.createTempDirectory("agern-codex")
    @AfterTest fun tearDown() { base.toFile().deleteRecursively() }

    private fun provider(bin: String = fakeCodex(), timeout: Duration = Duration.ofSeconds(30)) =
        CodexImageProvider(bin, PATH, base.resolve("codex"), timeout)

    private fun request(prompt: String = "A squirrel", count: Int = 1, w: Int = 300, h: Int = 200) =
        ImageRequest(prompt, w, h, "png", count, transparent = false)

    private fun leftovers(): List<Path> =
        if (Files.isDirectory(base.resolve("codex"))) Files.list(base.resolve("codex")).use { it.toList() } else emptyList()

    private fun kindOf(block: () -> Unit): String = assertFailsWith<ImageProviderException> { block() }.kind

    @Test
    fun `availability depends on the binary`() {
        assertNull(provider().availability())
        assertEquals("The Codex CLI is not installed on this server.", provider(bin = "/nonexistent/codex").availability())
        assertEquals("The Codex CLI is not installed on this server.", provider(bin = "no-such-codex-binary").availability())
        assertEquals("device_login", provider().auth)
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `generate returns the requested number of PNGs and leaves no directory`() {
        val renewed = CopyOnWriteArrayList<String>()
        val images = provider().generate(LOGIN, "default", request(count = 3)) { renewed += it }
        assertEquals(3, images.size)
        assertTrue(images.all { Sniff.mediaType(it) == "image/png" && Sniff.dimensions("image/png", it) == (300 to 200) })
        assertTrue(renewed.isEmpty(), "unchanged login is not renewed")
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `the CLI gets a clean environment, the prompt on stdin only, and no sandbox bypass`() {
        // fake:dump writes the CLI's env and argv to FAKE_CODEX_DUMP (the call directory is gone afterwards).
        val dump = base.resolve("dump-target")
        Files.createDirectories(dump)
        val p = CodexImageProvider(fakeCodex(), PATH, base.resolve("codex"), Duration.ofSeconds(30), extraEnv = mapOf("FAKE_CODEX_DUMP" to dump.toString()))
        val secretEnvName = "ANTHROPIC_API_KEY"
        p.generate(LOGIN, "default", request(prompt = "fake:dump A secret prompt text")) {}
        val j = AppJson.parseToJsonElement(Files.readString(dump.resolve("dump.json"))).jsonObject
        val env = j["env"]!!.jsonObject.keys - "FAKE_CODEX_DUMP" - setOf("PWD", "SHLVL", "_", "LC_CTYPE")
        assertEquals(setOf("PATH", "HOME", "CODEX_HOME", "LANG", "TERM", "NO_COLOR"), env)
        assertFalse(secretEnvName in j["env"]!!.jsonObject)
        assertEquals(j["env"]!!.jsonObject["HOME"], j["env"]!!.jsonObject["CODEX_HOME"])
        val argv = j["argv"]!!.jsonArray.map { it.jsonPrimitive.content }.drop(1)
        assertEquals(
            listOf("exec", "--skip-git-repo-check", "--ignore-user-config", "--json", "--color", "never", "-s", "read-only", "-C"),
            argv.take(9),
        )
        assertEquals(listOf("-c", "cli_auth_credentials_store=\"file\"", "-"), argv.drop(10))
        assertTrue(argv.none { "secret prompt" in it || "dangerously" in it })
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `failures map to their kinds and never carry CLI output`() {
        val p = provider()
        val e = assertFailsWith<ImageProviderException> { p.generate("""{"other":1}""", "default", request(prompt = "fake:limit")) {} }
        assertEquals(ImageProviderException.QUOTA, e.kind)
        assertNull(e.detail)
        assertEquals(ImageProviderException.NO_IMAGE, kindOf { p.generate(LOGIN, "default", request(prompt = "fake:none")) {} })
        assertEquals(ImageProviderException.NO_IMAGE, kindOf { p.generate(LOGIN, "default", request(prompt = "fake:symlink")) {} })
        assertEquals(ImageProviderException.INVALID_CREDENTIAL, kindOf { p.generate("not json", "default", request()) {} })
        assertEquals(ImageProviderException.UNAVAILABLE, kindOf { provider(bin = "/nonexistent/codex").generate(LOGIN, "default", request()) {} })
        // Without a login file the CLI says 401.
        assertEquals(ImageProviderException.INVALID_CREDENTIAL, CodexImageProvider.failureKind("ERROR: 401 Unauthorized"))
        assertEquals(ImageProviderException.CONTENT_REFUSED, CodexImageProvider.failureKind("blocked by our content policy"))
        assertEquals(ImageProviderException.QUOTA, CodexImageProvider.failureKind("429 Too Many Requests"))
        assertEquals(ImageProviderException.INVALID_CREDENTIAL, CodexImageProvider.failureKind("rate limit; 401"), "first row wins")
        assertEquals(ImageProviderException.NO_IMAGE, CodexImageProvider.failureKind("done"))
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `a slow CLI is killed at the timeout`() {
        val started = System.currentTimeMillis()
        assertEquals(ImageProviderException.TIMEOUT, kindOf { provider(timeout = Duration.ofSeconds(2)).generate(LOGIN, "default", request(prompt = "fake:slow")) {} })
        assertTrue(System.currentTimeMillis() - started < 20_000)
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `a refreshed login is handed back, also when the call fails`() {
        val renewed = CopyOnWriteArrayList<String>()
        provider().generate(LOGIN, "default", request(prompt = "fake:refresh")) { renewed += it }
        assertEquals(1, renewed.size)
        assertTrue("fake-refresh-2" in renewed.single())
        renewed.clear()
        assertEquals(ImageProviderException.QUOTA, kindOf { provider().generate(LOGIN, "default", request(prompt = "fake:refresh fake:limit")) { renewed += it } })
        assertEquals(1, renewed.size)
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `leftover call directories are removed`() {
        val p = provider()
        val old = base.resolve("codex/old/home")
        Files.createDirectories(old)
        Files.writeString(old.resolve("auth.json"), LOGIN)
        val outside = Files.createTempDirectory("agern-outside")
        Files.writeString(outside.resolve("keep"), "x")
        Files.createSymbolicLink(base.resolve("codex/old/link"), outside)
        p.cleanLeftovers()
        assertTrue(leftovers().isEmpty())
        assertTrue(Files.exists(outside.resolve("keep")), "symlinks are not followed")
        outside.toFile().deleteRecursively()
    }
}
