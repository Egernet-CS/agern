package dk.egernet.factory

import dk.egernet.factory.agent.Attachments
import dk.egernet.factory.agent.Sniff
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun json(r: String) = AppJson.parseToJsonElement(r)
private val JsonObject.s get() = { k: String -> this[k]!!.jsonPrimitive.content }

fun png(w: Int, h: Int, alpha: Boolean = false): ByteArray {
    val img = BufferedImage(w, h, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.color = Color.RED; g.fillRect(0, 0, w / 2, h)
    g.color = Color.BLUE; g.fillRect(w / 2, 0, w - w / 2, h)
    g.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
}

class AttachmentTest {
    private val world = TestWorld(twoFactor = false, tweak = {
        it.copy(attachmentMaxBytes = 200_000, attachmentMaxMessageBytes = 300_000, attachmentMaxCount = 3)
    })
    @AfterTest fun tearDown() = world.close()

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String): HttpClient {
        val c = createClient { install(HttpCookies) }
        assertEquals(200, c.post("/api/auth/login") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
        }.status.value)
        c.put("/api/me/github-token") { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"token":"fake-$user"}""") }
        c.put("/api/me/claude-credential") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"kind":"oauth_token","value":"sk-ant-oat-$user"}""")
        }
        return c
    }

    private suspend fun HttpClient.upload(sid: String, name: String, bytes: ByteArray, csrf: Boolean = true, type: String = "image/png"): HttpResponse =
        post("/api/sessions/$sid/attachments?name=${java.net.URLEncoder.encode(name, Charsets.UTF_8)}") {
            if (csrf) header("X-Factory-Request", "1")
            contentType(ContentType.parse(type)); setBody(bytes)
        }

    private suspend fun HttpClient.send(sid: String, text: String, ids: List<String>): HttpResponse = post("/api/sessions/$sid/input") {
        header("X-Factory-Request", "1"); contentType(ContentType.Application.Json)
        setBody("""{"text":${AppJson.encodeToString(kotlinx.serialization.serializer<String>(), text)},"attachmentIds":[${ids.joinToString(",") { "\"$it\"" }}]}""")
    }

    private suspend fun HttpClient.newChat(repo: String = "demo-app") =
        json(post("/api/projects/$repo/sessions") { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("{}") }.bodyAsText())
            .jsonObject.s("id")

    private suspend fun events(c: HttpClient, sid: String, until: (JsonArray) -> Boolean): JsonArray = withTimeout(10_000) {
        while (true) {
            val ev = json(c.get("/api/sessions/$sid/events").bodyAsText()).jsonArray
            if (until(ev)) return@withTimeout ev
            delay(25)
        }
        @Suppress("UNREACHABLE_CODE") JsonArray(emptyList())
    }

    @Test
    fun `upload, sniffing, safe serving, ownership and visibility`() = testApplication {
        application { agernModule(world.services) }
        val alice = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        val sid = alice.newChat()

        // The upload is a mutating request: CSRF header required.
        assertEquals(403, alice.upload(sid, "x.png", png(4, 4), csrf = false).status.value)
        // Only the owner may upload; observers get 403.
        assertEquals(403, bob.upload(sid, "x.png", png(4, 4)).status.value)

        val img = alice.upload(sid, "../../etc/\u202Eevil\u0007 shot.png", png(32, 16))
        assertEquals(201, img.status.value, img.bodyAsText())
        val imgDto = json(img.bodyAsText()).jsonObject
        assertEquals("evil shot.png", imgDto.s("name"), "path, bidi override and control characters are stripped")
        assertEquals("image", imgDto.s("kind"))
        assertEquals("image/png", imgDto.s("mediaType"))
        assertEquals("32", imgDto.s("width"))
        assertEquals("original", imgDto.s("inline"))

        // HTML claiming to be a PNG is a text file and is never served as HTML or inline.
        val html = alice.upload(sid, ".hidden.html", "<html><script>alert(1)</script></html>".toByteArray(), type = "image/png")
        val htmlDto = json(html.bodyAsText()).jsonObject
        assertEquals("file", htmlDto.s("kind"))
        assertEquals("text/plain", htmlDto.s("mediaType"))
        assertEquals("hidden.html", htmlDto.s("name"), "no leading dot")
        val svg = alice.upload(sid, "x.svg", """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>""".toByteArray(), type = "image/svg+xml")
        assertEquals("file", json(svg.bodyAsText()).jsonObject.s("kind"))

        // Unsent uploads are the owner's draft.
        val imgUrl = "/api/sessions/$sid/attachments/${imgDto.s("id")}"
        assertEquals(200, alice.get(imgUrl).status.value)
        assertEquals(404, bob.get(imgUrl).status.value)

        // Attaching the same upload twice in one message, or someone else's id, is refused as a whole.
        assertEquals(409, alice.send(sid, "two", listOf(imgDto.s("id"), imgDto.s("id"))).status.value)
        assertEquals(409, alice.send(sid, "bogus", listOf(UUID.randomUUID().toString())).status.value)
        // /factory commands do not take attachments.
        val cmd = alice.send(sid, "/factory status", listOf(imgDto.s("id")))
        assertEquals(422, cmd.status.value)
        assertEquals("attachments_not_allowed", json(cmd.bodyAsText()).jsonObject["error"]!!.jsonObject.s("code"))

        // Observers cannot send, not even with the owner's attachment.
        assertEquals(403, bob.send(sid, "mine now", listOf(imgDto.s("id"))).status.value)

        assertEquals(202, alice.send(sid, "look at this", listOf(imgDto.s("id"), htmlDto.s("id"))).status.value)
        val ev = events(alice, sid) { e -> e.any { it.toString().contains("echo: look at this") } }
        val user = ev.map { it.jsonObject }.last { it.s("type") == "user" }["payload"]!!.jsonObject
        assertEquals(listOf("evil shot.png", "hidden.html"), user["attachments"]!!.jsonArray.map { it.jsonObject.s("name") })

        // Bob (can see the repo) may now fetch; the image inline, the "html" as an opaque download.
        val got = bob.get(imgUrl)
        assertEquals(200, got.status.value)
        assertTrue(png(32, 16).contentEquals(got.readRawBytes()))
        assertEquals("image/png", got.headers["Content-Type"])
        assertEquals("nosniff", got.headers["X-Content-Type-Options"])
        assertTrue(got.headers["Content-Disposition"]!!.startsWith("inline;"))
        assertTrue(got.headers["Content-Security-Policy"]!!.contains("default-src 'none'"))
        val gotHtml = bob.get("/api/sessions/$sid/attachments/${htmlDto.s("id")}")
        assertEquals("application/octet-stream", gotHtml.headers["Content-Type"])
        assertTrue(gotHtml.headers["Content-Disposition"]!!.startsWith("attachment;"), gotHtml.headers["Content-Disposition"])
        assertTrue(gotHtml.headers["Content-Disposition"]!!.contains("filename*=UTF-8''hidden.html"))

        // Sent attachments cannot be removed or re-sent; the unsent SVG can be removed by its owner only.
        assertEquals(409, alice.delete(imgUrl) { header("X-Factory-Request", "1") }.status.value)
        val svgUrl = "/api/sessions/$sid/attachments/${json(svg.bodyAsText()).jsonObject.s("id")}"
        assertEquals(403, bob.delete(svgUrl) { header("X-Factory-Request", "1") }.status.value)
        assertEquals(204, alice.delete(svgUrl) { header("X-Factory-Request", "1") }.status.value)
        assertEquals(404, alice.get(svgUrl).status.value)

        // A private repository's attachments are invisible to a user without access to it.
        val priv = alice.newChat("private-lab")
        val p = json(alice.upload(priv, "secret.png", png(8, 8)).bodyAsText()).jsonObject.s("id")
        assertEquals(202, alice.send(priv, "private", listOf(p)).status.value)
        assertEquals(200, alice.get("/api/sessions/$priv/attachments/$p").status.value)
        assertEquals(404, bob.get("/api/sessions/$priv/attachments/$p").status.value)
        // Ids are bound to their session.
        assertEquals(404, alice.get("/api/sessions/$sid/attachments/$p").status.value)
    }

    @Test
    fun `the agent receives image blocks and file paths outside the repository`() = testApplication {
        application { agernModule(world.services) }
        val alice = login("alice", "pw-a")
        val sid = alice.newChat()
        val img = json(alice.upload(sid, "shot.png", png(40, 20)).bodyAsText()).jsonObject.s("id")
        val csv = json(alice.upload(sid, "data.csv", "a,b\n1,2\n".toByteArray(), type = "text/csv").bodyAsText()).jsonObject.s("id")
        assertEquals(202, alice.send(sid, "what is this", listOf(img, csv)).status.value)
        events(alice, sid) { e -> e.any { it.toString().contains("echo: what is this") } }

        val content = world.launcher.chatInputs.last().jsonArray.map { it.jsonObject }
        assertEquals(listOf("image", "text", "text"), content.map { it.s("type") })
        val source = content[0]["source"]!!.jsonObject
        assertEquals("image/png", source.s("media_type"))
        assertTrue(png(40, 20).contentEquals(Base64.getDecoder().decode(source.s("data"))))
        assertEquals("what is this", content[1].s("text"))
        val listing = content[2].s("text")
        assertTrue(listing.startsWith("[Attachments]"), listing)
        val paths = Regex("path: (.+)").findAll(listing).map { Paths.get(it.groupValues[1]) }.toList()
        assertEquals(2, paths.size)
        val workDir = world.launcher.launched.last().spec.workDir
        for (p in paths) {
            assertTrue(Files.isRegularFile(p), "$p exists")
            assertFalse(p.startsWith(workDir), "attachments live outside the git working tree")
            assertTrue(p.startsWith(world.dataDir.resolve("attachments").resolve(sid)))
        }
        assertEquals("a,b\n1,2\n", Files.readString(paths[1]))
        assertTrue(listing.contains("data.csv — text/plain"))

        // The files are a cache: a cleaned data dir is restored from the database when the chat resumes.
        val owner = world.services.users.byUsername("alice")!!
        world.services.agents.stop(owner, world.services.sessions.get(UUID.fromString(sid))!!)
        paths.forEach { Files.delete(it) }
        assertEquals(202, alice.send(sid, "again", emptyList()).status.value)
        events(alice, sid) { e -> e.any { it.toString().contains("echo: again") } }
        for (p in paths) assertTrue(Files.isRegularFile(p), "$p restored")
        assertTrue(Files.readAllBytes(paths[0]).contentEquals(png(40, 20)))
    }

    @Test
    fun `limits are enforced per file, per message and by count`() = testApplication {
        application { agernModule(world.services) }
        val alice = login("alice", "pw-a")
        val sid = alice.newChat()
        val big = ByteArray(200_001) { 7 }
        val r = alice.upload(sid, "big.bin", big, type = "application/octet-stream")
        assertEquals(413, r.status.value)
        assertEquals("attachment_too_large", json(r.bodyAsText()).jsonObject["error"]!!.jsonObject.s("code"))
        assertEquals(400, alice.upload(sid, "empty.bin", ByteArray(0)).status.value)

        val a = alice.upload(sid, "a.bin", ByteArray(150_000) { 1 }, type = "application/octet-stream")
        assertEquals(201, a.status.value)
        val b = alice.upload(sid, "b.bin", ByteArray(150_001) { 2 }, type = "application/octet-stream")
        assertEquals(413, b.status.value)
        assertEquals("attachments_too_large", json(b.bodyAsText()).jsonObject["error"]!!.jsonObject.s("code"))
        assertEquals(201, alice.upload(sid, "c.png", png(4, 4)).status.value)
        assertEquals(201, alice.upload(sid, "d.png", png(4, 4)).status.value)
        val e = alice.upload(sid, "e.png", png(4, 4))
        assertEquals(409, e.status.value)
        assertEquals("too_many_attachments", json(e.bodyAsText()).jsonObject["error"]!!.jsonObject.s("code"))

        // Uploads that are never sent are cleaned up.
        assertEquals(3, world.services.attachments.store.purgeUnsent(Instant.now().plusSeconds(1)))
        assertNull(world.services.attachments.store.get(UUID.fromString(json(a.bodyAsText()).jsonObject.s("id"))))
    }

    @Test
    fun `large images are downscaled for the model and keep their original as a file`() {
        val att = world.services.attachments
        val wide = att.analyse("wide.png", png(3000, 1000))
        assertEquals("downscaled", wide.inlineMode)
        assertEquals(2000, wide.inlineWidth)
        assertEquals(666, wide.inlineHeight)
        assertEquals("image/jpeg", wide.inlineMediaType)
        assertEquals(2000 to 666, Sniff.dimensions("image/jpeg", wide.inlineData!!))
        assertNotNull(wide.note)
        val alpha = att.analyse("alpha.png", png(2400, 100, alpha = true))
        assertEquals("image/png", alpha.inlineMediaType, "transparency is kept")
        // WebP cannot be resized here: a too-large one goes as a file only, with a note.
        val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8X".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0xB7.toByte(), 0x0B, 0, 0x63, 0, 0)
        val w = att.analyse("big.webp", webp)
        assertEquals("image", w.kind)
        assertEquals(3000, w.width)
        assertEquals("none", w.inlineMode)
        assertTrue(w.note!!.contains("file only"))
        // Small images go as they are.
        assertEquals("original", att.analyse("s.png", png(100, 100)).inlineMode)
    }

    @Test
    fun `names are sanitised`() {
        assertEquals("passwd", Attachments.sanitizeName("../../etc/passwd"))
        assertEquals("b.txt", Attachments.sanitizeName("C:\\a\\b.txt"))
        assertEquals("bashrc", Attachments.sanitizeName("...bashrc"))
        assertEquals("attachment", Attachments.sanitizeName("\u0000\u0001"))
        assertEquals("a_b_.txt", Attachments.sanitizeName("a<b>.txt"))
        val long = Attachments.sanitizeName("x".repeat(300) + ".png")
        assertEquals(120, long.length)
        assertTrue(long.endsWith(".png"))
        val id = UUID.randomUUID()
        assertEquals("$id-r_sum_.pdf", Attachments.diskName(id, "résumé.pdf"))
    }
}
