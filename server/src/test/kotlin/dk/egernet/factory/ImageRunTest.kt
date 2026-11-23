package dk.egernet.factory

import dk.egernet.factory.images.ImageService
import dk.egernet.factory.mcp.McpGrant
import dk.egernet.factory.mcp.McpRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Selected images handed to a run, and run images on the run page (docs/ARCHITECTURE.md §6 "Generated images"). */
class ImageRunTest {
    private val worlds = CopyOnWriteArrayList<TestWorld>()
    private fun world() = TestWorld(twoFactor = false, imageProviders = listOf(RecordingImageProvider())).also { worlds += it }
    @AfterTest fun tearDown() = worlds.forEach { it.closeAndReleaseDb() }

    /** One image generated in a chat of [user] and selected for [issue] as [name]; returns its id. */
    private fun selected(w: TestWorld, user: String, issue: Int, name: String, purpose: String = "app icon"): UUID {
        val u = w.user(user)
        w.imageKey(user, "rec-$user-key")
        val chat = w.services.agents.createChat(u, "demo-app", null)
        val token = w.services.mcpRegistry.issue(McpGrant(chat.id, u.id, "demo-app", null, McpRegistry.CHAT_TOOLS))
        val a = Mcp.generate(w, token, buildJsonObject { put("prompt", "An icon"); put("purpose", purpose); put("size", "256x256") })
        val id = UUID.fromString(a.json["images"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        runBlocking { w.services.images.select(w.services.users.byUsername(user)!!, id, issue, name) }
        return id
    }

    private class Seen {
        val prompts = ConcurrentHashMap<String, String>()
        val files = ConcurrentHashMap<String, Boolean>()
        val workDir = CopyOnWriteArrayList<Path>()
    }

    /** Every stage passes, except a builder whose [failBuilder] is set; records prompts and what was in the worktree. */
    private fun script(w: TestWorld, seen: Seen, failBuilder: Boolean = false, changes: Boolean = false, builder: (FakeProcess) -> Unit = {}) {
        w.launcher.stage = { p ->
            val role = p.spec.kind
            val round = if (role == "builder" && p.spec.prompt!!.startsWith("The reviewers requested changes")) 1 else 0
            seen.prompts["$role:$round"] = p.spec.prompt!!
            if (role == "builder" && round == 0) {
                seen.workDir.add(p.spec.workDir)
                seen.files["prepared"] = Files.isRegularFile(p.spec.workDir.resolve(".agern-run/images/prepared-01-app-icon.png"))
                seen.files["ignore"] = Files.exists(p.spec.workDir.resolve(".agern-run/.gitignore"))
                builder(p)
            }
            p.init()
            when {
                role == "spec-writer" -> p.result("## Spec\nDo it.")
                role == "builder" && failBuilder -> {}
                role == "builder" -> p.result("Built it.")
                role == "approver" -> p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") })
                else -> {
                    val c = changes && role == "code" && "after fix round" !in p.spec.prompt!!
                    p.result(null, buildJsonObject {
                        put("verdict", if (c) "CHANGES" else "PASS"); put("summary", "ok")
                        put("findings", buildJsonArray {
                            if (c) add(buildJsonObject { put("severity", "major"); put("file", "a.kt"); put("detail", "fix it") })
                        })
                    })
                }
            }
            p.exit(if (role == "builder" && failBuilder) 1 else 0)
        }
    }

    private fun start(w: TestWorld, user: String = "alice", issue: Int = 1): UUID =
        runBlocking { w.services.orchestrator.start(w.user(user), "demo-app", issue, null) }.id

    @Test
    fun `selected images are handed to the spec-writer and the builder, then locked until the run fails or merges`() {
        val w = world()
        val bobImage = selected(w, "bob", 1, "App icon")
        val seen = Seen()
        script(w, seen, changes = true)
        val runId = start(w)
        val done = w.awaitRun(runId, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)

        assertEquals(true, seen.files["prepared"])
        assertEquals(true, seen.files["ignore"])
        for (key in listOf("spec-writer:0", "builder:0")) {
            val p = seen.prompts.getValue(key)
            assertTrue("<images>\nThese images were prepared for this issue in the chat" in p, key)
            assertTrue(Regex("1\\. App icon — purpose: app icon; image/png, 256×256, [0-9.]+ (B|KB)\n   path: \\.agern-run/images/prepared-01-app-icon\\.png\n</images>")
                .containsMatchIn(p), p)
            assertTrue("</issue_body>\n<images>" in p, "the block follows the issue text")
        }
        assertFalse("<images>" in seen.prompts.getValue("builder:1"), "not in the fix prompt")

        // The stages that got the block note it in their transcripts.
        val dto = w.services.runs.toDto(done)
        for (role in listOf("spec-writer", "builder")) {
            val session = UUID.fromString(dto.stages.first { it.role == role && it.round == 0 }.sessionId)
            assertTrue(w.services.events.list(session, 0, 500).any { it.type == "system" && "Prepared images: 1 (App icon)" in it.payload.toString() }, role)
        }
        // Locked after the merge (a run of another user took bob's image).
        val m = w.services.images.store.get(bobImage)!!
        assertEquals(runId, m.usedByRunId)
        assertTrue(m.locked)
    }

    @Test
    fun `a failed run unlocks its images and the next run takes them again`() {
        val w = world()
        val id = selected(w, "alice", 1, "App icon")
        val seen = Seen()
        script(w, seen, failBuilder = true)
        val first = start(w)
        assertEquals(RunStatus.FAILED, w.awaitRun(first, RunStatus.TERMINAL).status)
        assertFalse(w.services.images.store.get(id)!!.locked)
        script(w, Seen())
        val second = start(w)
        assertEquals(RunStatus.MERGED, w.awaitRun(second, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        assertEquals(second, w.services.images.store.get(id)!!.usedByRunId)
    }

    @Test
    fun `a run without selected images or tool calls has unchanged prompts and no agern-run directory`() {
        val w = world()
        val seen = Seen()
        script(w, seen)
        val runId = start(w)
        assertEquals(RunStatus.MERGED, w.awaitRun(runId, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        assertTrue(seen.prompts.values.none { "<images>" in it })
        assertTrue("</issue_body>\n\nYour working directory is a checkout" in seen.prompts.getValue("spec-writer:0"))
        assertTrue("</issue_body>\n\n<spec>" in seen.prompts.getValue("builder:0"))
        assertFalse(Files.exists(seen.workDir.single().resolve(".agern-run")))
        val dto = w.services.runs.toDto(w.services.runs.get(runId)!!)
        assertTrue(dto.stages.all { it.images.isEmpty() })
        val builderSession = UUID.fromString(dto.stages.first { it.role == "builder" }.sessionId)
        assertTrue(w.services.events.list(builderSession, 0, 500).none { "Prepared images" in it.payload.toString() })
    }

    @Test
    fun `a run image appears on the stage that made it`() {
        val w = world()
        w.user("alice"); w.imageKey("alice", "rec-alice-key")
        val seen = Seen()
        script(w, seen, builder = { p -> assertFalse(Mcp.generate(w, Mcp.token(p)).isError) })
        val runId = start(w)
        assertEquals(RunStatus.MERGED, w.awaitRun(runId, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        val dto = w.services.runs.toDto(w.services.runs.get(runId)!!)
        assertEquals(listOf("builder"), dto.stages.filter { it.images.isNotEmpty() }.map { it.role })
        assertEquals("A squirrel icon", dto.stages.single { it.role == "builder" }.images.single().prompt)
    }

    @Test
    fun `git ignores the agern-run directory without any change to the repository`() {
        val repo = Files.createTempDirectory("agern-git")
        fun git(vararg args: String): String {
            val p = ProcessBuilder(listOf("git", "-C", repo.toString()) + args).redirectErrorStream(true).start()
            return p.inputStream.bufferedReader().readText().also { p.waitFor() }
        }
        git("init", "-q")
        Files.writeString(repo.resolve("README.md"), "hi\n")
        val dir = ImageService.ensureRunDir(repo)
        Files.createDirectories(dir.resolve("images"))
        Files.write(dir.resolve("images/prepared-01-x.png"), png(4, 4))
        assertEquals("?? README.md", git("status", "--porcelain").trim())
        repo.toFile().deleteRecursively()
    }

    @Test
    fun `a repository with agern-run as a symlink fails the hand-over, writes nothing outside and unlocks the selection`() {
        val w = world()
        val id = selected(w, "alice", 1, "App icon")
        val outside = Files.createTempDirectory("agern-outside")
        w.git.afterWorktree = { wt -> Files.createSymbolicLink(wt.resolve(".agern-run"), outside) }
        val seen = Seen()
        script(w, seen)
        val runId = start(w)
        val done = w.awaitRun(runId, RunStatus.TERMINAL)
        assertEquals(RunStatus.FAILED, done.status)
        assertEquals("Prepared images could not be handed over: `.agern-run` in the repository is not a plain directory", done.error)
        assertTrue(seen.prompts.isEmpty(), "no stage started")
        Files.list(outside).use { assertEquals(emptyList(), it.toList()) }
        assertFalse(w.services.images.store.get(id)!!.locked)

        // A symlink planted at a prepared image's file name is replaced, not written through.
        val victim = outside.resolve("victim.png")
        w.git.afterWorktree = { wt ->
            Files.createDirectories(wt.resolve(".agern-run/images"))
            Files.createSymbolicLink(wt.resolve(".agern-run/images/prepared-01-app-icon.png"), victim)
        }
        script(w, Seen())
        val second = start(w)
        assertEquals(RunStatus.MERGED, w.awaitRun(second, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
        assertFalse(Files.exists(victim, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        outside.toFile().deleteRecursively()
    }
}
