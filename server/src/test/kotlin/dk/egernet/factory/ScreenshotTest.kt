package dk.egernet.factory

import dk.egernet.factory.agent.FeedItem
import dk.egernet.factory.db.NewScreenshot
import dk.egernet.factory.db.ScreenshotInsertResult
import dk.egernet.factory.mcp.McpGrant
import dk.egernet.factory.mcp.McpRegistry
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Screenshots the UX and UI design reviewers attach to their stage (docs/ARCHITECTURE.md §6 "Screenshots"). */
class ScreenshotTest {
    private val worlds = CopyOnWriteArrayList<TestWorld>()
    private fun world(tweak: (Config) -> Config = { it }) = TestWorld(twoFactor = false, tweak = tweak).also { worlds += it }

    @AfterTest fun tearDown() = worlds.forEach { it.close() }

    // ---------------------------------------------------------------- MCP calls the way an agent makes them

    private fun token(p: FakeProcess): String = AppJson.parseToJsonElement(p.spec.mcpConfigJson).jsonObject["mcpServers"]!!.jsonObject["factory"]!!
        .jsonObject["headers"]!!.jsonObject["Authorization"]!!.jsonPrimitive.content.removePrefix("Bearer ")

    private data class Answer(val isError: Boolean, val text: String)

    private fun attach(w: TestWorld, token: String, path: String?, caption: String?): Answer {
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
            putJsonObject("params") {
                put("name", "factory_attach_screenshot")
                putJsonObject("arguments") { path?.let { put("path", it) }; caption?.let { put("caption", it) } }
            }
        }
        val res = AppJson.parseToJsonElement(runBlocking { w.services.mcp.handle(token, body.toString()) }.second!!).jsonObject
        val r = res["result"]?.jsonObject ?: error("JSON-RPC error instead of a tool result: $res")
        return Answer(r["isError"]?.jsonPrimitive?.booleanOrNull == true, r["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    private fun toolNames(w: TestWorld, token: String): List<String> =
        AppJson.parseToJsonElement(runBlocking { w.services.mcp.handle(token, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""") }.second!!)
            .jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    /** Writes [bytes] as [name] into the stage's working directory and returns the name. */
    private fun file(p: FakeProcess, name: String, bytes: ByteArray): String {
        Files.write(p.spec.workDir.resolve(name), bytes)
        return name
    }

    /**
     * Every stage passes unless [changes] says otherwise for (role, round); [reviewer] runs inside each reviewer stage
     * (role, round, process) before it answers.
     */
    private fun script(
        w: TestWorld,
        changes: (String, Int) -> Boolean = { _, _ -> false },
        reviewer: (String, Int, FakeProcess) -> Unit = { _, _, _ -> },
        builder: (FakeProcess) -> Unit = {},
    ) {
        w.launcher.stage = { p ->
            when (val role = p.spec.kind) {
                "spec-writer" -> { p.init(); p.result("## Spec\nDo it."); p.exit(0) }
                "builder" -> { builder(p); p.init(); p.result("Built it."); p.exit(0) }
                "approver" -> { p.init(); p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") }); p.exit(0) }
                else -> {
                    val round = Regex("after fix round (\\d+)").find(p.spec.prompt ?: "")?.groupValues?.get(1)?.toInt() ?: 0
                    reviewer(role, round, p)
                    p.init()
                    val c = changes(role, round)
                    p.result(null, buildJsonObject {
                        put("verdict", if (c) "CHANGES" else "PASS"); put("summary", "$role ok")
                        put("findings", buildJsonArray {
                            if (c) add(buildJsonObject { put("severity", "major"); put("file", "a.kt"); put("detail", "fix it") })
                        })
                    })
                    p.exit(0)
                }
            }
        }
    }

    private fun runToEnd(w: TestWorld, repo: String = "demo-app", issue: Int = 1): RunDto {
        val run = runBlocking { w.services.orchestrator.start(w.user("alice"), repo, issue, null) }
        val done = w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        return w.services.runs.toDto(done)
    }

    private fun RunDto.stage(role: String, round: Int = 0) = stages.single { it.role == role && it.round == round }

    private fun storedCount(w: TestWorld): Int =
        w.services.db.queryOne("SELECT count(*) AS n FROM stage_screenshots") { it.getInt("n") }!!

    // ---------------------------------------------------------------- attaching

    @Test
    fun `the ux and ui-design reviewers attach screenshots to their own stage`() {
        val w = world()
        val answers = ConcurrentHashMap<String, Answer>()
        val tools = ConcurrentHashMap<String, List<String>>()
        script(w, reviewer = { role, _, p ->
            tools[role] = toolNames(w, token(p))
            if (role == "ux") answers[role] = attach(w, token(p), file(p, "shot-ux.png", png(40, 20)), "Settings ›\n  Tokens,\tempty state‮")
            if (role == "ui-design") {
                // Absolute paths inside the worktree are fine too.
                val abs = p.spec.workDir.resolve(file(p, "shot-ui.png", png(12, 8))).toAbsolutePath().toString()
                answers[role] = attach(w, token(p), abs, "Run page, dark theme")
            }
        })
        val run = runToEnd(w)

        assertFalse(answers.getValue("ux").isError, answers.getValue("ux").text)
        assertTrue(answers.getValue("ux").text.startsWith("Attached screenshot 1 of at most 12 for this stage: \"Settings › Tokens, empty state\" (image/png, 40×20, "),
            answers.getValue("ux").text)
        assertFalse(answers.getValue("ui-design").isError, answers.getValue("ui-design").text)

        val ux = run.stage("ux").screenshots.single()
        assertEquals("Settings › Tokens, empty state", ux.caption, "newlines and tabs collapsed, format characters removed")
        assertEquals("image/png", ux.mediaType)
        assertEquals(40, ux.width)
        assertEquals(20, ux.height)
        assertEquals(png(40, 20).size.toLong(), ux.size)
        val ui = run.stage("ui-design").screenshots.single()
        assertEquals("Run page, dark theme", ui.caption)
        assertEquals(12 to 8, ui.width to ui.height)
        for (role in listOf("spec-writer", "builder", "security", "code", "approver")) assertTrue(run.stage(role).screenshots.isEmpty(), role)
        // The source file is left as it is.
        assertTrue(Files.exists(w.services.workspaces.runDir("alice", "demo-app", UUID.fromString(run.id)).resolve("shot-ux.png")))

        // Only ux and ui-design have the tool.
        assertTrue("factory_attach_screenshot" in tools.getValue("ux"))
        assertTrue("factory_attach_screenshot" in tools.getValue("ui-design"))
        assertFalse("factory_attach_screenshot" in tools.getValue("security"))
        assertFalse("factory_attach_screenshot" in tools.getValue("code"))
        assertEquals((McpRegistry.STAGE_TOOLS + "factory_attach_screenshot").sorted(), tools.getValue("ux").sorted())
    }

    @Test
    fun `a screenshot from the review sandbox's output directory can be attached`() {
        val w = world()
        val answer = ConcurrentHashMap<String, Answer>()
        script(w, reviewer = { role, _, p ->
            if (role == "ux") {
                // The review sandbox writes to /run/agern-review, which is .agern-run/review in the working directory.
                val dir = dk.egernet.factory.images.ImageService.ensureRunDir(p.spec.workDir).resolve("review")
                Files.createDirectories(dir)
                Files.write(dir.resolve("shot.png"), png(16, 9))
                answer[role] = attach(w, token(p), "${dk.egernet.factory.sandbox.SandboxManager.REVIEW_OUTPUT_HOST}/shot.png", "From the review sandbox")
            }
        })
        val run = runToEnd(w)
        assertFalse(answer.getValue("ux").isError, answer.getValue("ux").text)
        assertEquals("From the review sandbox", run.stage("ux").screenshots.single().caption)
    }

    @Test
    fun `other roles, chats and hand-built grants cannot attach`() {
        val w = world()
        val answers = ConcurrentHashMap<String, Answer>()
        val lists = ConcurrentHashMap<String, List<String>>()
        var builderSession: UUID? = null
        script(w,
            builder = { p ->
                lists["builder"] = toolNames(w, token(p))
                answers["builder"] = attach(w, token(p), file(p, "b.png", png(4, 4)), "builder shot")
                builderSession = p.spec.sessionId
            },
            reviewer = { role, _, p ->
                if (role in setOf("security", "code")) {
                    lists[role] = toolNames(w, token(p))
                    answers[role] = attach(w, token(p), file(p, "$role.png", png(4, 4)), "$role shot")
                }
            },
        )
        val run = runToEnd(w)
        val alice = w.services.users.byUsername("alice")!!
        for (role in listOf("builder", "security", "code")) {
            assertFalse("factory_attach_screenshot" in lists.getValue(role), role)
            assertTrue(answers.getValue(role).isError, role)
            assertEquals("Tool factory_attach_screenshot is not available to this agent", answers.getValue(role).text)
        }
        // A chat token.
        val chat = w.services.agents.createChat(alice, "demo-app", null)
        val chatToken = w.services.mcpRegistry.issue(McpGrant(chat.id, alice.id, "demo-app", null, McpRegistry.CHAT_TOOLS))
        assertFalse("factory_attach_screenshot" in toolNames(w, chatToken))
        assertTrue(attach(w, chatToken, "x.png", "chat").isError)

        // Hand-built grants that include the tool: the handler itself refuses.
        val worktree = w.services.workspaces.runDir("alice", "demo-app", UUID.fromString(run.id))
        Files.createDirectories(worktree)
        Files.write(worktree.resolve("hand.png"), png(4, 4))
        val tools = McpRegistry.STAGE_TOOLS + "factory_attach_screenshot"
        val refused = "Only the ux and ui-design reviewers can attach screenshots"
        val asBuilder = w.services.mcpRegistry.issue(McpGrant(builderSession!!, alice.id, "demo-app", UUID.fromString(run.id), tools))
        assertEquals(Answer(true, refused), attach(w, asBuilder, "hand.png", "hand"))
        val noRun = w.services.mcpRegistry.issue(McpGrant(chat.id, alice.id, "demo-app", null, tools))
        assertEquals(Answer(true, refused), attach(w, noRun, "hand.png", "hand"))
        val notAStage = w.services.mcpRegistry.issue(McpGrant(chat.id, alice.id, "demo-app", UUID.fromString(run.id), tools))
        assertEquals(Answer(true, refused), attach(w, notAStage, "hand.png", "hand"))
        val uxSession = UUID.fromString(run.stage("ux").sessionId)
        val otherRepo = w.services.mcpRegistry.issue(McpGrant(uxSession, alice.id, "private-lab", UUID.fromString(run.id), tools))
        assertEquals(Answer(true, refused), attach(w, otherRepo, "hand.png", "hand"))
        val unknownRun = w.services.mcpRegistry.issue(McpGrant(uxSession, alice.id, "demo-app", UUID.randomUUID(), tools))
        assertEquals(Answer(true, refused), attach(w, unknownRun, "hand.png", "hand"))
        assertEquals(0, storedCount(w))
    }

    @Test
    fun `non-images, empty, oversized, outside paths, missing files, bad captions and the count limit are refused`() {
        val w = world { it.copy(screenshotMaxBytes = 1L shl 20, screenshotMaxCount = 2) }
        val outsideDir = Files.createTempDirectory(w.dataDir, "outside")
        val secret = outsideDir.resolve("secret.png").also { Files.write(it, png(4, 4)) }
        val answers = CopyOnWriteArrayList<Pair<String, Answer>>()
        var storedDuringRefusals = -1
        script(w, reviewer = { role, _, p ->
            if (role != "ux") return@script
            val t = token(p)
            fun a(label: String, path: String?, caption: String? = "A caption") { answers += label to attach(w, t, path, caption) }
            a("text", file(p, "text.png", "just some text\n".toByteArray()))
            a("pdf", file(p, "doc.png", "%PDF-1.7\n%âãÏÓ\n1 0 obj\n".toByteArray()))
            a("svg", file(p, "x.png", """<svg xmlns="http://www.w3.org/2000/svg"/>""".toByteArray()))
            a("truncated", file(p, "trunc.png", png(4, 4).copyOf(10)))
            a("empty", file(p, "empty.png", ByteArray(0)))
            a("oversized", file(p, "big.png", png(4, 4) + ByteArray((1 shl 20) + 1)))
            a("absolute outside", secret.toString())
            Files.write(p.spec.workDir.resolveSibling("escape.png"), png(4, 4))
            a("dot-dot", "../escape.png")
            a("dot-dot-rel", p.spec.workDir.relativize(secret).toString())
            Files.createSymbolicLink(p.spec.workDir.resolve("link.png"), secret)
            a("symlink", "link.png")
            Files.createDirectories(p.spec.workDir.resolve("adir"))
            a("directory", "adir")
            a("missing", "nope.png")
            a("no path", null)
            a("blank caption", file(p, "ok.png", png(4, 4)), " \n\t​ ")
            a("no caption", "ok.png", null)
            a("long caption", "ok.png", "x".repeat(201))
            storedDuringRefusals = storedCount(w)
            a("ok 1", "ok.png", "y".repeat(200))
            a("ok 2", "ok.png", "second")
            a("third", "ok.png", "third")
        })
        val run = runToEnd(w)
        assertEquals(0, storedDuringRefusals, "nothing stored for refusals")
        val byLabel = answers.toMap()
        fun err(label: String, text: String) {
            val a = byLabel.getValue(label)
            assertTrue(a.isError, "$label: $a")
            assertEquals(text, a.text, label)
        }
        val notImage = "Only PNG, JPEG, GIF or WebP images are accepted"
        err("text", notImage); err("pdf", notImage); err("svg", notImage); err("truncated", notImage)
        err("empty", "The file is empty")
        err("oversized", "The image is larger than 1.0 MB")
        val outside = "path must be a file inside your working directory"
        err("absolute outside", outside); err("dot-dot", outside); err("dot-dot-rel", outside); err("symlink", outside); err("directory", outside)
        err("missing", "No such file: nope.png")
        err("no path", "path is required")
        err("blank caption", "caption is required: say what the screenshot shows")
        err("no caption", "caption is required: say what the screenshot shows")
        err("long caption", "caption is too long (at most 200 characters)")
        assertFalse(byLabel.getValue("ok 1").isError, byLabel.getValue("ok 1").text)
        assertTrue(byLabel.getValue("ok 2").text.startsWith("Attached screenshot 2 of at most 2 for this stage"))
        err("third", "This stage already has 2 screenshots (the maximum)")
        assertEquals(listOf("y".repeat(200), "second"), run.stage("ux").screenshots.map { it.caption })
    }

    @Test
    fun `parallel attaches never overshoot the per-stage limit`() {
        val w = world { it.copy(screenshotMaxCount = 3) }
        val answers = CopyOnWriteArrayList<Answer>()
        script(w, reviewer = { role, _, p ->
            if (role != "ux") return@script
            val name = file(p, "p.png", png(6, 6))
            val t = token(p)
            (1..8).map { i -> Thread { answers += attach(w, t, name, "shot $i") }.also { it.start() } }.forEach { it.join() }
        })
        val run = runToEnd(w)
        assertEquals(3, answers.count { !it.isError })
        assertEquals(3, run.stage("ux").screenshots.size)
    }

    @Test
    fun `jpeg, gif and webp are accepted with their dimensions`() {
        val w = world()
        val jpeg = java.io.ByteArrayOutputStream().also {
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(9, 7, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", it)
        }.toByteArray()
        val gif = java.io.ByteArrayOutputStream().also {
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(5, 3, java.awt.image.BufferedImage.TYPE_INT_RGB), "gif", it)
        }.toByteArray()
        // A lossless WebP (VP8L) header for 2×2: the type and size are read from the header, the pixels are never decoded.
        val webp = "RIFF".toByteArray() + byteArrayOf(26, 0, 0, 0) + "WEBPVP8L".toByteArray() + byteArrayOf(13, 0, 0, 0) +
            byteArrayOf(0x2F, 0x01, 0x40, 0x00, 0x00) + ByteArray(9)
        script(w, reviewer = { role, _, p ->
            if (role != "ui-design") return@script
            for ((n, b) in listOf("a.jpg" to jpeg, "b.gif" to gif, "c.webp" to webp)) {
                val a = attach(w, token(p), file(p, n, b), n)
                assertFalse(a.isError, a.text)
            }
        })
        val shots = runToEnd(w).stage("ui-design").screenshots
        assertEquals(listOf("image/jpeg" to (9 to 7), "image/gif" to (5 to 3), "image/webp" to (2 to 2)),
            shots.map { it.mediaType to (it.width to it.height) })
    }

    // ---------------------------------------------------------------- rounds, feed, pull request note, cascade

    @Test
    fun `each round keeps its own screenshots and the pull request gets one note per round with screenshots`() {
        val w = world { it.copy(publicUrl = "https://agern.example.com") }
        script(w,
            changes = { role, round -> role == "code" && round == 0 },
            reviewer = { role, round, p ->
                if (role in SCREENSHOT_ROLES) {
                    val a = attach(w, token(p), file(p, "$role-$round.png", png(8, 8)), "$role round $round")
                    assertFalse(a.isError, a.text)
                }
            },
        )
        val run = runToEnd(w)
        assertEquals(1, run.round)
        assertEquals(listOf("ux round 0"), run.stage("ux", 0).screenshots.map { it.caption })
        assertEquals(listOf("ux round 1"), run.stage("ux", 1).screenshots.map { it.caption })
        assertEquals(listOf("ui-design round 0"), run.stage("ui-design", 0).screenshots.map { it.caption })
        assertEquals(listOf("ui-design round 1"), run.stage("ui-design", 1).screenshots.map { it.caption })

        val notes = prComments(w, run).filter { it.startsWith("**Agern: screenshots**") }
        assertEquals(2, notes.size, notes.toString())
        assertEquals(
            "**Agern: screenshots** — the reviewers attached 2 screenshots of this change (build). See them on " +
                "https://agern.example.com/p/demo-app/runs/${run.id}:\n\n- **ux:** ux round 0\n- **ui-design:** ui-design round 0",
            notes[0],
        )
        assertTrue(notes[1].contains("(fix round 1)"), notes[1])
        assertTrue(notes[1].contains("- **ux:** ux round 1\n- **ui-design:** ui-design round 1"), notes[1])
    }

    @Test
    fun `the note names the run page without an internal address, and a round without screenshots gets none`() {
        val w = world() // publicUrl http://localhost:8080
        script(w,
            changes = { role, round -> role == "code" && round == 0 },
            reviewer = { role, round, p ->
                if (role == "ux" && round == 1) assertFalse(attach(w, token(p), file(p, "u.png", png(8, 8)), "only one").isError)
            },
        )
        val run = runToEnd(w)
        val notes = prComments(w, run).filter { it.startsWith("**Agern: screenshots**") }
        assertEquals(
            listOf("**Agern: screenshots** — the reviewers attached 1 screenshot of this change (fix round 1). See them on " +
                "the page of run `${run.id}` in Agern:\n\n- **ux:** only one"),
            notes,
        )
        assertFalse(notes.single().contains("localhost"))

        val w2 = world()
        script(w2)
        val run2 = runToEnd(w2)
        assertTrue(prComments(w2, run2).none { it.contains("screenshot") })
    }

    private fun prComments(w: TestWorld, run: RunDto): List<String> =
        w.services.access.client(w.services.users.byUsername("alice")!!)!!.listComments("test-org", run.repo, run.prNumber!!).map { it.body.orEmpty() }

    @Test
    fun `attaching emits a run feed frame with the screenshot`() {
        val w = world()
        script(w, reviewer = { role, _, p ->
            if (role == "ux") assertFalse(attach(w, token(p), file(p, "f.png", png(8, 8)), "live").isError)
        })
        val frame = runBlocking(Dispatchers.Default) {
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(10_000) {
                    w.services.bus.feed.filterIsInstance<FeedItem.Run>()
                        .first { item -> item.run.stages.any { it.role == "ux" && it.screenshots.isNotEmpty() } }
                }
            }
            runToEnd(w)
            waiter.await()
        }
        val ux = frame.run.stages.single { it.role == "ux" }
        assertEquals("live", ux.screenshots.single().caption)
        assertEquals("running", ux.status, "published at attach time, before the stage ended")
    }

    @Test
    fun `screenshot rows go with their run`() {
        val w = world()
        script(w, reviewer = { role, _, p -> if (role == "ux") attach(w, token(p), file(p, "d.png", png(8, 8)), "gone soon") })
        val run = runToEnd(w)
        assertEquals(1, storedCount(w))
        w.services.db.update("DELETE FROM runs WHERE id = ?", UUID.fromString(run.id))
        assertEquals(0, storedCount(w))
    }

    // ---------------------------------------------------------------- serving

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String): HttpClient {
        val c = createClient { install(HttpCookies) }
        assertEquals(200, c.post("/api/auth/login") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
        }.status.value)
        return c
    }

    /** A run of [repo] by alice with a `ux` stage holding one screenshot, made directly in the database. */
    private fun seeded(w: TestWorld, repo: String, issue: Int, bytes: ByteArray): Pair<UUID, UUID> {
        val alice = w.user("alice")
        val run = w.services.runs.createIfNoActive(repo, issue, "t", "task", 2, alice.id, null)!!
        val st = w.services.runs.createStage(run.id, "ux", 0, "sonnet")
        val res = w.services.screenshotStore.insert(run.id, st.id, NewScreenshot("A shot", "image/png", bytes, "x", 8, 8), 12)
        return run.id to (res as ScreenshotInsertResult.Ok).meta.id
    }

    @Test
    fun `the image route serves the bytes to whoever may see the run`() {
        val w = world()
        val bytes = png(8, 8)
        val (runId, sid) = seeded(w, "demo-app", 1, bytes)
        val (privRun, privSid) = seeded(w, "private-lab", 1, bytes)
        w.user("bob")
        testApplication {
            application { agernModule(w.services) }
            val anon = createClient { }
            assertEquals(401, anon.get("/api/runs/$runId/screenshots/$sid").status.value)
            val alice = login("alice", "pw-a")
            val bob = login("bob", "pw-b")

            val r = bob.get("/api/runs/$runId/screenshots/$sid")
            assertEquals(200, r.status.value)
            assertTrue(bytes.contentEquals(r.readRawBytes()))
            assertEquals("image/png", r.headers["Content-Type"])
            assertEquals("nosniff", r.headers["X-Content-Type-Options"])
            assertEquals("default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; sandbox", r.headers["Content-Security-Policy"])
            assertEquals("same-origin", r.headers["Cross-Origin-Resource-Policy"])
            assertEquals("private, max-age=31536000, immutable", r.headers["Cache-Control"])
            val disp = r.headers["Content-Disposition"]!!
            assertTrue(disp.startsWith("inline; filename=\"screenshot-ux-${sid.toString().take(8)}.png\""), disp)

            // The run DTO lists it, without the bytes.
            val dto = AppJson.parseToJsonElement(bob.get("/api/runs/$runId").bodyAsText()).jsonObject
            val shot = dto["stages"]!!.jsonArray.single().jsonObject["screenshots"]!!.jsonArray.single().jsonObject
            assertEquals(setOf("id", "caption", "mediaType", "size", "width", "height", "createdAt"), shot.keys)

            // Private repository: alice sees it, bob gets 404 as for the run itself.
            assertEquals(200, alice.get("/api/runs/$privRun/screenshots/$privSid").status.value)
            assertEquals(404, bob.get("/api/runs/$privRun/screenshots/$privSid").status.value)
            assertEquals(404, bob.get("/api/runs/$privRun").status.value)
            // Unknown ids, and a screenshot under another run's id.
            assertEquals(404, alice.get("/api/runs/$runId/screenshots/${UUID.randomUUID()}").status.value)
            assertEquals(404, alice.get("/api/runs/${UUID.randomUUID()}/screenshots/$sid").status.value)
            assertEquals(404, alice.get("/api/runs/$runId/screenshots/$privSid").status.value)
            assertEquals(404, alice.get("/api/runs/$runId/screenshots/not-a-uuid").status.value)
        }
    }

    // ---------------------------------------------------------------- configuration

    private val base = mapOf("FACTORY_MASTER_KEY" to Base64.getEncoder().encodeToString(ByteArray(32)), "FACTORY_GITHUB_ORG" to "o")

    @Test
    fun `screenshot limits are read from the environment`() {
        val d = Config.fromEnv(base)
        assertEquals(5L shl 20, d.screenshotMaxBytes)
        assertEquals(12, d.screenshotMaxCount)
        val c = Config.fromEnv(base + mapOf("FACTORY_SCREENSHOT_MAX_MB" to "8", "FACTORY_SCREENSHOT_MAX_COUNT" to "50"))
        assertEquals(8L shl 20, c.screenshotMaxBytes)
        assertEquals(50, c.screenshotMaxCount)
        for ((k, v) in listOf(
            "FACTORY_SCREENSHOT_MAX_MB" to "0", "FACTORY_SCREENSHOT_MAX_MB" to "513", "FACTORY_SCREENSHOT_MAX_MB" to "big",
            "FACTORY_SCREENSHOT_MAX_COUNT" to "0", "FACTORY_SCREENSHOT_MAX_COUNT" to "51", "FACTORY_SCREENSHOT_MAX_COUNT" to "many",
        )) {
            val e = assertFailsWith<IllegalArgumentException>("$k=$v") { Config.fromEnv(base + (k to v)) }
            assertTrue(e.message!!.contains(k), e.message)
        }
    }

    @Test
    fun `captions are one line without control or format characters`() {
        assertEquals("a b c", dk.egernet.factory.agent.Screenshots.sanitizeCaption("  a\r\n b\u0000 c‎  "))
        assertEquals("", dk.egernet.factory.agent.Screenshots.sanitizeCaption("\n\t‮"))
    }
}
