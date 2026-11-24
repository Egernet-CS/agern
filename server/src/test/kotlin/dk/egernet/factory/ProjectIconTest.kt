package dk.egernet.factory

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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun json(r: String) = AppJson.parseToJsonElement(r)

/** `iconUrl` of a project as a string, or null when it is JSON null; fails when the key is missing. */
private fun JsonObject.iconUrl(): String? = this.getValue("iconUrl").let { if (it is JsonNull) null else it.jsonPrimitive.content }

private fun encoded(w: Int, h: Int, format: String): ByteArray {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.color = Color.GREEN; g.fillRect(0, 0, w, h)
    g.dispose()
    return ByteArrayOutputStream().also { assertTrue(ImageIO.write(img, format, it)) }.toByteArray()
}

class ProjectIconTest {
    private val world = TestWorld(twoFactor = false)
    @AfterTest fun tearDown() = world.close()

    private val urlPattern = Regex("""^/api/projects/demo-app/icon\?v=[0-9a-f]{16}$""")
    private val immutable = "private, max-age=31536000, immutable"

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String): HttpClient {
        val c = createClient { install(HttpCookies) }
        assertEquals(200, c.post("/api/auth/login") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
        }.status.value)
        c.put("/api/me/github-token") { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"token":"fake-$user"}""") }
        return c
    }

    private suspend fun HttpClient.putIcon(
        bytes: ByteArray, repo: String = "demo-app", type: String = "image/png", csrf: Boolean = true,
    ): HttpResponse = put("/api/projects/$repo/icon") {
        if (csrf) header("X-Factory-Request", "1")
        contentType(ContentType.parse(type)); setBody(bytes)
    }

    private suspend fun HttpClient.deleteIcon(repo: String = "demo-app"): HttpResponse =
        delete("/api/projects/$repo/icon") { header("X-Factory-Request", "1") }

    private suspend fun HttpClient.project(repo: String = "demo-app") = json(get("/api/projects/$repo").bodyAsText()).jsonObject

    private suspend fun HttpClient.projects() =
        json(get("/api/projects").bodyAsText()).jsonArray.associate { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject }

    private fun errorCode(r: String) = json(r).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun `upload, serving, replace and remove`() = testApplication {
        application { agernModule(world.services) }
        val root = login("root", "pw-r")
        val bob = login("bob", "pw-b")

        // Without an icon the key is there, as null.
        assertNull(root.project().iconUrl())
        assertTrue(root.projects().values.all { it.containsKey("iconUrl") && it.iconUrl() == null })

        val first = png(64, 64)
        val up = root.putIcon(first)
        assertEquals(200, up.status.value, up.bodyAsText())
        val url = json(up.bodyAsText()).jsonObject.iconUrl()
        assertNotNull(url)
        assertTrue(urlPattern.matches(url), url)
        assertEquals(url, root.project().iconUrl())
        assertEquals(url, bob.projects()["demo-app"]!!.iconUrl())
        assertTrue(bob.projects().filterKeys { it != "demo-app" }.values.all { it.iconUrl() == null })

        // Anyone who can see the repository gets the bytes, with the stored-file headers.
        val got = bob.get(url)
        assertEquals(200, got.status.value)
        assertContentEquals(first, got.readRawBytes())
        assertEquals("image/png", got.headers["Content-Type"])
        assertEquals("nosniff", got.headers["X-Content-Type-Options"])
        assertTrue(got.headers["Content-Security-Policy"]!!.contains("default-src 'none'"))
        assertTrue(got.headers["Content-Security-Policy"]!!.contains("sandbox"))
        assertEquals("same-origin", got.headers["Cross-Origin-Resource-Policy"])
        assertTrue(got.headers["Content-Disposition"]!!.startsWith("inline"), got.headers["Content-Disposition"])
        assertTrue(got.headers["Content-Disposition"]!!.contains("demo-app-icon.png"), got.headers["Content-Disposition"])
        assertEquals(immutable, got.headers["Cache-Control"])
        // Without or with a stale version the response must be revalidated.
        assertEquals("private, no-cache", bob.get("/api/projects/demo-app/icon").headers["Cache-Control"])
        assertEquals("private, no-cache", bob.get("/api/projects/demo-app/icon?v=0000000000000000").headers["Cache-Control"])

        // Stored in PostgreSQL under the repository name, with the bytes' hash.
        val meta = world.services.projectIcons.meta("demo-app")
        assertNotNull(meta)
        assertEquals(sha256(first), meta.sha256)
        assertEquals("image/png", meta.mediaType)
        assertEquals(64, meta.width)
        assertEquals(first.size.toLong(), meta.size)

        // Replace: a new version, and the new bytes are served.
        val second = png(48, 32)
        val url2 = json(root.putIcon(second).bodyAsText()).jsonObject.iconUrl()!!
        assertNotEquals(url, url2)
        assertTrue(urlPattern.matches(url2))
        assertContentEquals(second, bob.get(url2).readRawBytes())
        assertEquals("private, no-cache", bob.get(url).headers["Cache-Control"], "the old URL is stale now")

        // JPEG and GIF are accepted, whatever the client claims the type is.
        val jpeg = encoded(20, 20, "jpg")
        val jpegUrl = json(root.putIcon(jpeg, type = "application/octet-stream").bodyAsText()).jsonObject.iconUrl()!!
        val jpegGot = bob.get(jpegUrl)
        assertEquals("image/jpeg", jpegGot.headers["Content-Type"])
        assertContentEquals(jpeg, jpegGot.readRawBytes())
        val gif = encoded(20, 20, "gif")
        val gifGot = bob.get(json(root.putIcon(gif).bodyAsText()).jsonObject.iconUrl()!!)
        assertEquals("image/gif", gifGot.headers["Content-Type"])
        assertContentEquals(gif, gifGot.readRawBytes())

        // Remove, then remove again.
        val del = root.deleteIcon()
        assertEquals(200, del.status.value)
        assertNull(json(del.bodyAsText()).jsonObject.iconUrl())
        assertEquals(404, bob.get("/api/projects/demo-app/icon").status.value)
        assertNull(world.services.projectIcons.meta("demo-app"))
        assertNull(bob.project().iconUrl())
        val again = root.deleteIcon()
        assertEquals(200, again.status.value)
        assertNull(json(again.bodyAsText()).jsonObject.iconUrl())
    }

    @Test
    fun `only admins who see the repository change icons`() = testApplication {
        application { agernModule(world.services) }
        val root = login("root", "pw-r")
        val alice = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        val icon = png(16, 16)
        val url = json(root.putIcon(icon).bodyAsText()).jsonObject.iconUrl()!!

        // Non-admins are refused and change nothing.
        val put = alice.putIcon(png(8, 8))
        assertEquals(403, put.status.value)
        assertEquals("forbidden", errorCode(put.bodyAsText()))
        assertEquals(403, alice.deleteIcon().status.value)
        assertEquals(url, alice.project().iconUrl())
        assertContentEquals(icon, alice.get(url).readRawBytes())

        // CSRF guard and authentication.
        val noCsrf = root.putIcon(png(8, 8), csrf = false)
        assertEquals(403, noCsrf.status.value)
        assertEquals("csrf", errorCode(noCsrf.bodyAsText()))
        val anonymous = createClient { }
        assertEquals(401, anonymous.putIcon(png(8, 8)).status.value)
        assertEquals(401, anonymous.deleteIcon().status.value)
        assertEquals(401, anonymous.get(url).status.value)
        assertEquals(url, root.project().iconUrl())

        // An admin who cannot see the repository: 404 for every route.
        assertEquals(404, root.putIcon(png(8, 8), repo = "private-lab").status.value)
        assertEquals(404, root.deleteIcon(repo = "private-lab").status.value)
        assertEquals(404, root.get("/api/projects/private-lab/icon").status.value)
        assertNull(world.services.projectIcons.meta("private-lab"))

        // An icon of a private repository is hidden from those who cannot see it.
        val aliceUser = world.services.users.byUsername("alice")!!
        world.services.projectIcons.put("private-lab", "image/png", icon, sha256(icon), 16, 16, aliceUser.id)
        assertEquals(200, alice.get("/api/projects/private-lab/icon").status.value)
        assertEquals(404, bob.get("/api/projects/private-lab/icon").status.value)
        assertTrue(bob.projects().keys.none { it == "private-lab" })
        assertTrue(alice.projects()["private-lab"]!!.iconUrl()!!.startsWith("/api/projects/private-lab/icon?v="))
    }

    @Test
    fun `invalid uploads are refused and keep the previous icon`() = testApplication {
        application { agernModule(world.services) }
        val root = login("root", "pw-r")
        val icon = png(32, 32)
        val url = json(root.putIcon(icon).bodyAsText()).jsonObject.iconUrl()!!

        suspend fun refused(status: Int, code: String, bytes: ByteArray, type: String = "image/png", message: String? = null) {
            val r = root.putIcon(bytes, type = type)
            assertEquals(status, r.status.value, r.bodyAsText())
            assertEquals(code, errorCode(r.bodyAsText()))
            message?.let { assertEquals(it, json(r.bodyAsText()).jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content) }
            assertEquals(url, root.project().iconUrl())
            assertContentEquals(icon, root.get(url).readRawBytes())
        }

        refused(400, "empty_icon", ByteArray(0), message = "The file is empty")
        val big = png(4, 4).copyOf(ProjectIcons.MAX_BYTES.toInt() + 1)
        refused(413, "icon_too_large", big, message = "The icon is larger than 1.0 MB")
        refused(
            415, "unsupported_icon_type", """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>""".toByteArray(),
            type = "image/svg+xml", message = "The icon must be a PNG, JPEG, WebP or GIF image",
        )
        refused(415, "unsupported_icon_type", "<html><script>alert(1)</script></html>".toByteArray())
        refused(415, "unsupported_icon_type", "%PDF-1.4\n".toByteArray())
        refused(422, "invalid_icon", png(4, 4).copyOf(12), message = "The image could not be read")
        refused(422, "icon_dimensions_too_large", png(2049, 10), message = "The icon may be at most 2048×2048 pixels")
        refused(422, "icon_dimensions_too_large", png(10, 2049))

        // The limits themselves are accepted.
        assertEquals(200, root.putIcon(png(2048, 1)).status.value)
    }
}
