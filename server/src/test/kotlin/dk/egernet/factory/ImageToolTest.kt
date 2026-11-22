package dk.egernet.factory

import dk.egernet.factory.images.ImageProviderException
import dk.egernet.factory.mcp.McpGrant
import dk.egernet.factory.mcp.McpRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
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

/** The tool `factory_generate_image` (docs/ARCHITECTURE.md §6 "Generated images", §7). */
class ImageToolTest {
    private val worlds = CopyOnWriteArrayList<TestWorld>()
    private val provider = RecordingImageProvider()
    private val opaque = RecordingImageProvider(id = "opaque", label = "Opaque", supportsTransparency = false)
    private fun world(tweak: (Config) -> Config = { it }) =
        TestWorld(twoFactor = false, tweak = tweak, imageProviders = listOf(provider, opaque)).also { worlds += it }

    @AfterTest fun tearDown() = worlds.forEach { it.closeAndReleaseDb() }

    private fun chatToken(w: TestWorld, user: String, repo: String = "demo-app"): Pair<UUID, String> {
        val u = w.user(user)
        val chat = w.services.agents.createChat(u, repo, null)
        return chat.id to w.services.mcpRegistry.issue(McpGrant(chat.id, u.id, repo, null, McpRegistry.CHAT_TOOLS))
    }

    private fun args(vararg extra: Pair<String, Any>) = buildJsonObject {
        put("prompt", "A squirrel icon"); put("purpose", "app icon")
        for ((k, v) in extra) when (v) {
            is String -> put(k, v); is Int -> put(k, v); is Boolean -> put(k, v); else -> error("type")
        }
    }

    /** Runs a pipeline where every stage passes; [inStage] runs inside each stage (role, process) before it answers. */
    private fun runWith(w: TestWorld, owner: String = "alice", inStage: (String, FakeProcess) -> Unit): RunDto {
        w.launcher.stage = { p ->
            val role = p.spec.kind
            inStage(role, p)
            p.init()
            when (role) {
                "spec-writer" -> p.result("## Spec\nDo it.")
                "builder" -> p.result("Built it.")
                "approver" -> p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") })
                else -> p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
            }
            p.exit(0)
        }
        val run = runBlocking { w.services.orchestrator.start(w.user(owner), "demo-app", 1, null) }
        val done = w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        return w.services.runs.toDto(done)
    }

    private fun stored(w: TestWorld): Int = w.services.db.queryOne("SELECT count(*) AS n FROM generated_images") { it.getInt("n") }!!

    @Test
    fun `the chat agent, the spec-writer and the builder have the tool, reviewers and the approver do not`() {
        val w = world()
        w.user("alice"); w.imageKey("alice", "rec-alice-key")
        val lists = ConcurrentHashMap<String, List<String>>()
        val answers = ConcurrentHashMap<String, Mcp.Answer>()
        val run = runWith(w) { role, p ->
            lists[role] = Mcp.tools(w, Mcp.token(p))
            answers[role] = Mcp.generate(w, Mcp.token(p))
        }
        for (role in listOf("spec-writer", "builder")) {
            assertTrue("factory_generate_image" in lists.getValue(role), role)
            assertFalse(answers.getValue(role).isError, answers.getValue(role).text)
        }
        for (role in REVIEWER_ROLES + "approver") {
            assertFalse("factory_generate_image" in lists.getValue(role), role)
            assertEquals(Mcp.Answer(true, "Tool factory_generate_image is not available to this agent"), answers.getValue(role), role)
        }
        assertEquals(1, run.stages.single { it.role == "spec-writer" }.images.size)
        assertEquals(1, run.stages.single { it.role == "builder" }.images.size)
        assertTrue(run.stages.filter { it.role in REVIEWER_ROLES + "approver" }.all { it.images.isEmpty() })
        val (_, chat) = chatToken(w, "alice")
        assertTrue("factory_generate_image" in Mcp.tools(w, chat))

        // A hand-built grant for a reviewer session that includes the tool: the service refuses it.
        val code = UUID.fromString(run.stages.single { it.role == "code" }.sessionId)
        val alice = w.services.users.byUsername("alice")!!
        val forged = w.services.mcpRegistry.issue(McpGrant(code, alice.id, "demo-app", UUID.fromString(run.id), setOf("factory_generate_image")))
        assertEquals(Mcp.Answer(true, "Only the chat agent, the spec-writer and the builder can generate images"), Mcp.generate(w, forged))
        // A stage grant claiming another repository.
        val builder = UUID.fromString(run.stages.single { it.role == "builder" }.sessionId)
        val otherRepo = w.services.mcpRegistry.issue(McpGrant(builder, alice.id, "private-lab", UUID.fromString(run.id), setOf("factory_generate_image")))
        assertTrue(Mcp.generate(w, otherRepo).isError)
        assertEquals(2, provider.generations.size, "refused calls never reach the provider")
    }

    @Test
    fun `images are generated with the owner's key only, and without a provider nobody else's key is used`() {
        val w = world()
        w.user("alice"); w.user("bob"); w.user("carol")
        w.imageKey("alice", "rec-alice-key", model = "rec-1")
        w.imageKey("bob", "rec-bob-key", model = "rec-2")
        val (_, a) = chatToken(w, "alice")
        val (_, b) = chatToken(w, "bob")
        val (_, c) = chatToken(w, "carol")
        assertFalse(Mcp.generate(w, a).isError)
        assertFalse(Mcp.generate(w, b).isError)
        assertEquals(listOf("rec-alice-key" to "rec-1", "rec-bob-key" to "rec-2"), provider.generations.map { it.key to it.model })

        val none = Mcp.generate(w, c)
        assertEquals(Mcp.Answer(true, "No image provider is configured for carol. Tell the user to add one under Settings → Image generation, " +
            "then ask again. Do not draw a placeholder instead."), none)
        // A provider id that is no longer in the catalogue counts as none.
        w.imageKey("carol", "rec-carol", provider = "gone")
        assertTrue(Mcp.generate(w, c).text.startsWith("No image provider is configured for carol."))
        assertEquals(2, provider.generations.size)
    }

    @Test
    fun `a builder without a provider gets the run answer and the run still merges`() {
        val w = world()
        val answers = ConcurrentHashMap<String, Mcp.Answer>()
        runWith(w) { role, p -> if (role == "builder") answers[role] = Mcp.generate(w, Mcp.token(p)) }
        assertEquals(Mcp.Answer(true, "No image provider is configured for the run's owner (alice). Continue without generated images: do not " +
            "fail the task for this and do not draw placeholder art. Say in your final summary which images are missing and that the owner " +
            "can add an image provider under Settings → Image generation."), answers.getValue("builder"))
        assertTrue(provider.generations.isEmpty())
    }

    @Test
    fun `arguments and transparency are checked before anything is spent`() {
        val w = world { it.copy(imageLimits = it.imageLimits.copy(maxEdge = 1536, maxPerRequest = 3)) }
        w.user("alice"); w.imageKey("alice", "rec-alice-key")
        val (_, t) = chatToken(w, "alice")
        fun err(a: JsonObject) = Mcp.generate(w, t, a).also { assertTrue(it.isError, it.text) }.text
        assertEquals("prompt is required: 1 to 4000 characters", err(buildJsonObject { put("purpose", "icon") }))
        assertEquals("prompt is required: 1 to 4000 characters", err(buildJsonObject { put("prompt", "x".repeat(4001)); put("purpose", "icon") }))
        assertTrue(err(buildJsonObject { put("prompt", "x") }).startsWith("purpose is required"))
        assertTrue(err(buildJsonObject { put("prompt", "x"); put("purpose", "y".repeat(201)) }).startsWith("purpose is required"))
        assertEquals("size must be WIDTHxHEIGHT with each edge 256 to 1536 pixels", err(args("size" to "2048x1024")))
        assertEquals("size must be WIDTHxHEIGHT with each edge 256 to 1536 pixels", err(args("size" to "100x300")))
        assertEquals("size must be WIDTHxHEIGHT, e.g. 1024x1024, each edge 256 to 1536 pixels", err(args("size" to "big")))
        assertEquals("format must be png, jpeg or webp", err(args("format" to "gif")))
        assertEquals("count must be a whole number from 1 to 3", err(args("count" to 4)))
        assertEquals("count must be a whole number from 1 to 3", err(args("count" to 0)))
        assertEquals("transparentBackground must be true or false", err(args("transparentBackground" to "maybe")))
        assertEquals("transparentBackground needs format png or webp: JPEG has no transparency",
            err(args("transparentBackground" to true, "format" to "jpeg")))
        w.imageKey("alice", "rec-alice-key", provider = "opaque")
        assertTrue(err(args("transparentBackground" to true)).startsWith("Opaque cannot generate a transparent background."))
        assertTrue(provider.generations.isEmpty() && opaque.generations.isEmpty())
        assertEquals(0, stored(w))

        // Valid arguments reach the provider as asked.
        w.imageKey("alice", "rec-alice-key")
        val ok = Mcp.generate(w, t, args("size" to "512x768", "format" to "webp", "count" to 2, "transparentBackground" to true))
        assertFalse(ok.isError, ok.text)
        val req = provider.generations.single().request!!
        assertEquals(listOf(512, 768, 2), listOf(req.width, req.height, req.count))
        assertEquals("webp" to true, req.format to req.transparent)
    }

    @Test
    fun `per-request, per-turn and per-stage limits, with a reset on the next chat input and a release after failures`() {
        val w = world { it.copy(imageLimits = it.imageLimits.copy(maxPerRequest = 3, maxPerTurn = 4, maxPerStage = 2)) }
        val alice = w.user("alice"); w.imageKey("alice", "rec-alice-key")
        val (chatId, t) = chatToken(w, "alice")
        assertEquals(1, Mcp.generate(w, t, args("count" to 3)).json["remaining"]!!.jsonPrimitive.int)
        val refused = Mcp.generate(w, t, args("count" to 2))
        assertEquals(Mcp.Answer(true, "Limit reached: at most 4 images per chat message (1 left). The user can ask again in a new message."), refused)
        // A failure releases what it reserved.
        provider.failWith = ImageProviderException(ImageProviderException.QUOTA)
        assertTrue(Mcp.generate(w, t).isError)
        provider.failWith = null
        assertEquals(0, Mcp.generate(w, t).json["remaining"]!!.jsonPrimitive.int)
        assertTrue(Mcp.generate(w, t).text.startsWith("Limit reached: at most 4 images per chat message (0 left)."))
        // The owner's next message starts a new turn.
        w.services.agents.sendInput(alice, w.services.sessions.get(chatId)!!, "another message")
        // An undelivered image (dropped as not an image) is released: 1 of 4 used.
        provider.answer = { listOf(png(300, 300), "not an image".toByteArray()) }
        val partial = Mcp.generate(w, t, args("count" to 2))
        assertEquals(3, partial.json["remaining"]!!.jsonPrimitive.int, partial.text)
        assertEquals(1, partial.json["images"]!!.jsonArray.size)

        // Per stage.
        val answers = CopyOnWriteArrayList<Mcp.Answer>()
        provider.answer = null
        runWith(w) { role, p -> if (role == "builder") repeat(3) { answers += Mcp.generate(w, Mcp.token(p)) } }
        assertEquals(listOf(false, false, true), answers.map { it.isError })
        assertEquals("Limit reached: at most 2 images per run stage (0 left).", answers[2].text)
    }

    @Test
    fun `provider failures are plain words with the key redacted`() {
        val w = world()
        w.user("alice"); w.imageKey("alice", "rec-secret-key-123")
        val (_, t) = chatToken(w, "alice")
        val expected = mapOf(
            ImageProviderException.INVALID_CREDENTIAL to "Recorder rejected your API key. Update it under Settings → Image generation.",
            ImageProviderException.QUOTA to "Your Recorder account has no quota or credit left for image generation.",
            ImageProviderException.RATE_LIMITED to "Recorder is rate-limiting your account right now. Try again in a little while.",
            ImageProviderException.CONTENT_REFUSED to "Recorder refused to generate this image because of its content rules. Change the prompt.",
            ImageProviderException.UNAVAILABLE to "Recorder could not be reached or failed. Try again later.",
            ImageProviderException.NO_IMAGE to "Recorder answered without a usable image.",
        )
        for ((kind, text) in expected) {
            provider.failWith = ImageProviderException(kind)
            assertEquals(Mcp.Answer(true, text), Mcp.generate(w, t), kind)
        }
        provider.failWith = ImageProviderException(ImageProviderException.TIMEOUT, "300")
        assertEquals("Recorder did not answer within 300 seconds.", Mcp.generate(w, t).text)
        provider.failWith = ImageProviderException(ImageProviderException.INVALID_REQUEST, "Bad size for key rec-secret-key-123\nsecond line " + "x".repeat(400))
        val invalid = Mcp.generate(w, t).text
        assertTrue(invalid.startsWith("Recorder did not accept the request: Bad size for key *** second line xx"), invalid)
        assertFalse("rec-secret-key-123" in invalid)
        assertEquals("Recorder did not accept the request: ".length + 300, invalid.length)
        provider.failWith = null
        // Results that are not usable images.
        provider.answer = { listOf("<svg/>".toByteArray(), png(4, 4).copyOf(10), png(300, 300) + ByteArray(20 shl 20)) }
        assertEquals(Mcp.Answer(true, "Recorder answered without a usable image."), Mcp.generate(w, t))
        assertEquals(0, stored(w))
    }

    @Test
    fun `a chat image is stored, written as a file, announced in the transcript and described in the answer`() {
        val w = world()
        w.user("alice"); w.imageKey("alice", "rec-alice-key")
        val (chatId, t) = chatToken(w, "alice")
        provider.answer = { r -> listOf(png(1024, 1024), png(r.width, r.height)) }
        val a = Mcp.generate(w, t, args("size" to "512x512", "count" to 2, "issueNumber" to 3))
        assertFalse(a.isError, a.text)
        val imgs = a.json["images"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, imgs.size)
        val path = Path.of(imgs[0]["path"]!!.jsonPrimitive.content)
        assertTrue(path.isAbsolute && path.startsWith(w.dataDir.resolve("generated-images").resolve(chatId.toString())), path.toString())
        assertTrue(path.fileName.toString().endsWith("-app-icon.png"))
        assertTrue(Files.readAllBytes(path).contentEquals(png(1024, 1024)))
        assertEquals("rec", a.json["provider"]!!.jsonPrimitive.content)
        assertEquals("rec-1", a.json["model"]!!.jsonPrimitive.content)
        assertEquals(listOf("Image 1 is 1024×1024, not the requested 512×512: resize it with the project's tools if needed."),
            a.json["notes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(a.json["next"]!!.jsonPrimitive.content.startsWith("The user sees the images in the chat"))
        assertFalse("rec-alice-key" in a.text)

        val ev = w.services.events.list(chatId, 0, 100).single { it.type == "images" }.payload.jsonObject
        assertEquals("Recorder", ev["providerLabel"]!!.jsonPrimitive.content)
        assertEquals("app icon", ev["purpose"]!!.jsonPrimitive.content)
        assertEquals(3, ev["issueNumber"]!!.jsonPrimitive.int)
        assertEquals(imgs.map { it["id"]!!.jsonPrimitive.content }, ev["images"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertFalse("rec-alice-key" in ev.toString())

        val rows = runBlocking { w.services.images.forSession(chatId) }
        assertEquals(listOf("chat", "chat"), rows.map { it.origin })
        assertEquals("A squirrel icon", rows[0].prompt)
        assertEquals(null, rows[0].selection)
    }

    @Test
    fun `a run image is written into the worktree's ignored directory and shown on its stage`() {
        val w = world()
        w.user("alice"); w.imageKey("alice", "rec-alice-key")
        val answers = ConcurrentHashMap<String, Mcp.Answer>()
        val run = runWith(w) { role, p ->
            if (role == "builder") {
                answers[role] = Mcp.generate(w, Mcp.token(p), args("transparentBackground" to true))
                answers["file"] = Mcp.Answer(false, Files.exists(p.spec.workDir.resolve(answers.getValue(role).json["images"]!!.jsonArray[0]
                    .jsonObject["path"]!!.jsonPrimitive.content)).toString())
                answers["ignore"] = Mcp.Answer(false, Files.readString(p.spec.workDir.resolve(".agern-run/.gitignore")))
            }
        }
        val a = answers.getValue("builder")
        assertFalse(a.isError, a.text)
        val path = a.json["images"]!!.jsonArray[0].jsonObject["path"]!!.jsonPrimitive.content
        assertTrue(Regex("^\\.agern-run/images/[0-9a-f]{8}-app-icon\\.png$").matches(path), path)
        assertEquals("true", answers.getValue("file").text)
        assertEquals("*\n", answers.getValue("ignore").text)
        assertTrue(a.json["next"]!!.jsonPrimitive.content.startsWith("Copy the images you use into the repository"))
        val img = run.stages.single { it.role == "builder" }.images.single()
        assertEquals("run" to "app icon", img.origin to img.purpose)
        assertTrue(img.transparent)
        val ev = w.services.events.list(UUID.fromString(run.stages.single { it.role == "builder" }.sessionId), 0, 100)
        assertEquals(1, ev.count { it.type == "images" })
    }
}
