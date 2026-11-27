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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun j(r: String) = AppJson.parseToJsonElement(r).jsonObject
internal fun sha256Hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

/** HTTP helpers for project files, shared by the project file tests. */
internal object PF {
    suspend fun ApplicationTestBuilder.login(user: String, pw: String): HttpClient {
        val c = createClient { install(HttpCookies) }
        assertEquals(200, c.post("/api/auth/login") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
        }.status.value)
        c.put("/api/me/github-token") { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"token":"fake-$user"}""") }
        return c
    }

    suspend fun HttpClient.create(repo: String, name: String, size: Long, sha256: String? = null, note: String? = null): HttpResponse =
        post("/api/projects/$repo/file-uploads") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(CreateProjectFileUploadReq.serializer(), CreateProjectFileUploadReq(name, size, sha256, note)))
        }

    suspend fun HttpClient.chunk(repo: String, id: String, offset: Long, bytes: ByteArray): HttpResponse =
        put("/api/projects/$repo/file-uploads/$id?offset=$offset") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.OctetStream); setBody(bytes)
        }

    suspend fun HttpClient.complete(repo: String, id: String): HttpResponse =
        post("/api/projects/$repo/file-uploads/$id/complete") { header("X-Factory-Request", "1") }

    /** Uploads [bytes] as [name] in chunks of [chunk] bytes; answers the completed file. */
    suspend fun HttpClient.uploadFile(repo: String, name: String, bytes: ByteArray, chunk: Int = 7, note: String? = null): JsonObject {
        val r = create(repo, name, bytes.size.toLong(), note = note)
        assertEquals(201, r.status.value, r.bodyAsText())
        val id = j(r.bodyAsText())["id"]!!.jsonPrimitive.content
        var off = 0
        while (off < bytes.size) {
            val end = minOf(bytes.size, off + chunk)
            val c = chunk(repo, id, off.toLong(), bytes.copyOfRange(off, end))
            assertEquals(200, c.status.value, c.bodyAsText())
            off = end
        }
        val done = complete(repo, id)
        assertEquals(200, done.status.value, done.bodyAsText())
        return j(done.bodyAsText())
    }

    suspend fun HttpClient.files(repo: String) = get("/api/projects/$repo/files")

    suspend fun HttpClient.setManagers(repo: String, vararg names: String) = put("/api/projects/$repo/file-managers") {
        header("X-Factory-Request", "1"); contentType(ContentType.Application.Json)
        setBody("""{"usernames":[${names.joinToString(",") { "\"$it\"" }}]}""")
    }

    suspend fun HttpClient.updateFile(repo: String, id: String, body: String) = put("/api/projects/$repo/files/$id") {
        header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody(body)
    }

    fun code(r: String) = j(r)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
}

class ProjectFilesApiTest {
    private val clock = MutableClock(java.time.Instant.now())
    @Volatile private var freeSpace = Long.MAX_VALUE
    private var world = TestWorld(twoFactor = false, clock = clock, projectFilesFreeSpace = { freeSpace })
    @AfterTest fun tearDown() = world.close()

    private fun audit(event: String): List<String> =
        world.services.db.query("SELECT detail FROM auth_audit WHERE event = ? ORDER BY id", event) { it.getString("detail") }

    private val store get() = world.services.projectFiles.root.resolve("demo-app")

    @Test
    fun `upload in chunks, resume, offsets, checksums, empty files`() = testApplication {
        application { agernModule(world.services) }
        with(PF) {
            val root = login("root", "pw-r")
            val bytes = "hello project files, in several chunks".toByteArray()
            val sha = sha256Hex(bytes)

            val created = root.create("demo-app", "vendor/sdk.zip", bytes.size.toLong(), note = "Licence: vendor EULA")
            assertEquals(201, created.status.value, created.bodyAsText())
            val up = j(created.bodyAsText())
            val id = up["id"]!!.jsonPrimitive.content
            assertEquals(0, up["received"]!!.jsonPrimitive.long)

            assertEquals(200, root.chunk("demo-app", id, 0, bytes.copyOfRange(0, 10)).status.value)
            // A chunk at the wrong offset is refused; the upload reports what it has.
            val wrong = root.chunk("demo-app", id, 5, bytes.copyOfRange(5, 10))
            assertEquals(409, wrong.status.value)
            assertEquals("upload_offset_mismatch", code(wrong.bodyAsText()))
            assertTrue(wrong.bodyAsText().contains("10"))
            assertEquals(10, j(root.get("/api/projects/demo-app/file-uploads/$id").bodyAsText())["received"]!!.jsonPrimitive.long)
            // Completing early is refused.
            assertEquals("upload_incomplete", code(root.complete("demo-app", id).bodyAsText()))
            // Creating the same upload again resumes the open one.
            val again = root.create("demo-app", "vendor/sdk.zip", bytes.size.toLong(), note = "Licence: vendor EULA")
            assertEquals(200, again.status.value)
            assertEquals(id, j(again.bodyAsText())["id"]!!.jsonPrimitive.content)
            assertEquals(10, j(again.bodyAsText())["received"]!!.jsonPrimitive.long)
            // A body longer than the rest is cut at the declared size.
            val tooLong = root.chunk("demo-app", id, 10, bytes.copyOfRange(10, bytes.size) + "extra".toByteArray())
            assertEquals(413, tooLong.status.value)
            assertEquals("file_too_large", code(tooLong.bodyAsText()))
            assertEquals(bytes.size.toLong(), j(root.get("/api/projects/demo-app/file-uploads/$id").bodyAsText())["received"]!!.jsonPrimitive.long)

            val done = root.complete("demo-app", id)
            assertEquals(200, done.status.value, done.bodyAsText())
            val f = j(done.bodyAsText())
            assertEquals(sha, f["sha256"]!!.jsonPrimitive.content)
            assertEquals(1, f["version"]!!.jsonPrimitive.content.toInt())

            val list = j(root.files("demo-app").bodyAsText())
            val file = list["files"]!!.jsonArray.single().jsonObject
            assertEquals("vendor/sdk.zip", file["name"]!!.jsonPrimitive.content)
            assertEquals(bytes.size.toLong(), file["size"]!!.jsonPrimitive.long)
            assertEquals(sha, file["sha256"]!!.jsonPrimitive.content)
            assertEquals("Licence: vendor EULA", file["note"]!!.jsonPrimitive.content)
            assertEquals("root", file["uploadedBy"]!!.jsonObject["username"]!!.jsonPrimitive.content)
            assertEquals("false", file["downloadable"]!!.jsonPrimitive.content)
            assertTrue(list["uploads"]!!.jsonArray.isEmpty())

            // The bytes are on disk, the blob read-only; PostgreSQL holds only metadata.
            assertContentEquals(bytes, Files.readAllBytes(store.resolve("blobs/$sha")))
            assertContentEquals(bytes, Files.readAllBytes(store.resolve("current/vendor/sdk.zip")))
            assertEquals("r--r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.resolve("blobs/$sha"))))
            assertEquals("r-xr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.resolve("current"))))
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(world.services.projectFiles.root)))
            val columns = world.services.db.query(
                "SELECT column_name FROM information_schema.columns WHERE table_name IN ('project_files','project_file_uploads') AND data_type = 'bytea'",
            ) { it.getString(1) }
            assertTrue(columns.isEmpty())
            assertEquals(1, audit("project_file_uploaded").size)
            assertTrue(audit("project_file_uploaded").single().contains("demo-app/vendor/sdk.zip v1 sha256:$sha"))

            // A wrong declared hash: nothing is published.
            val other = "other bytes".toByteArray()
            val c2 = j(root.create("demo-app", "data.bin", other.size.toLong(), sha256 = "0".repeat(64)).bodyAsText())
            val id2 = c2["id"]!!.jsonPrimitive.content
            root.chunk("demo-app", id2, 0, other)
            val bad = root.complete("demo-app", id2)
            assertEquals(422, bad.status.value)
            assertEquals("checksum_mismatch", code(bad.bodyAsText()))
            assertEquals(1, j(root.files("demo-app").bodyAsText())["files"]!!.jsonArray.size)
            assertEquals(404, root.get("/api/projects/demo-app/file-uploads/$id2").status.value)

            // A correct declared hash works.
            val c3 = j(root.create("demo-app", "data.bin", other.size.toLong(), sha256 = sha256Hex(other).uppercase()).bodyAsText())
            root.chunk("demo-app", c3["id"]!!.jsonPrimitive.content, 0, other)
            assertEquals(200, root.complete("demo-app", c3["id"]!!.jsonPrimitive.content).status.value)

            // Empty files are refused.
            val empty = root.create("demo-app", "empty.txt", 0)
            assertEquals(422, empty.status.value)
            assertEquals("empty_file", code(empty.bodyAsText()))

            // A discarded upload is gone, with its part file.
            val c4 = j(root.create("demo-app", "gone.bin", 5).bodyAsText())["id"]!!.jsonPrimitive.content
            assertEquals(204, root.delete("/api/projects/demo-app/file-uploads/$c4") { header("X-Factory-Request", "1") }.status.value)
            assertFalse(Files.exists(store.resolve("uploads/$c4.part")))
        }
    }

    @Test
    fun `a concurrent chunk of the same upload is busy`() {
        val s = world.services
        val root = world.user("root", admin = true)
        val (up, _) = s.projectFiles.createUpload(root, "demo-app", "big.bin", 10, null, null)
        val id = java.util.UUID.fromString(up.id)
        val gate = java.util.concurrent.CountDownLatch(1)
        val started = java.util.concurrent.CountDownLatch(1)
        val slow = object : java.io.InputStream() {
            var sent = false
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent) return -1
                started.countDown(); gate.await(); sent = true
                b[off] = 1; return 1
            }
        }
        val t = Thread { s.projectFiles.append(root, "demo-app", id, 0, slow) }.apply { start() }
        started.await()
        val e = runCatching { s.projectFiles.append(root, "demo-app", id, 0, ByteArray(1).inputStream()) }.exceptionOrNull() as ApiException
        assertEquals("upload_busy", e.code)
        assertEquals("upload_busy", (runCatching { s.projectFiles.complete(root, "demo-app", id, null) }.exceptionOrNull() as ApiException).code)
        gate.countDown(); t.join()
        // A request that breaks off mid-body keeps what was written.
        val broken = object : java.io.InputStream() {
            var n = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (n++ == 0) { b[off] = 2; b[off + 1] = 3; return 2 }
                throw java.io.IOException("connection reset")
            }
        }
        assertTrue(runCatching { s.projectFiles.append(root, "demo-app", id, 1, broken) }.isFailure)
        assertEquals(3, s.projectFiles.upload(root, "demo-app", id).received)
    }

    @Test
    fun `replace keeps id, name and download switch and collects the old blob`() = testApplication {
        application { agernModule(world.services) }
        with(PF) {
            val root = login("root", "pw-r")
            val v1 = "version one".toByteArray()
            val f1 = root.uploadFile("demo-app", "SDK.zip", v1, note = "first")
            val id = f1["id"]!!.jsonPrimitive.content
            assertEquals(200, root.updateFile("demo-app", id, """{"downloadable":true}""").status.value)
            val v2 = "version two, longer".toByteArray()
            // Replacing under the same name (also in other case) keeps the stored name.
            val f2 = root.uploadFile("demo-app", "sdk.zip", v2)
            assertEquals(id, f2["id"]!!.jsonPrimitive.content)
            assertEquals("SDK.zip", f2["name"]!!.jsonPrimitive.content)
            assertEquals(2, f2["version"]!!.jsonPrimitive.content.toInt())
            assertEquals("true", f2["downloadable"]!!.jsonPrimitive.content)
            assertEquals("first", f2["note"]!!.jsonPrimitive.content)
            assertFalse(Files.exists(store.resolve("blobs/${sha256Hex(v1)}")))
            assertContentEquals(v2, Files.readAllBytes(store.resolve("current/SDK.zip")))
            assertTrue(audit("project_file_replaced").single().contains("sha256:${sha256Hex(v1)} -> sha256:${sha256Hex(v2)}"))

            // Delete: row, link and blob go.
            assertEquals(204, root.delete("/api/projects/demo-app/files/$id") { header("X-Factory-Request", "1") }.status.value)
            assertTrue(j(root.files("demo-app").bodyAsText())["files"]!!.jsonArray.isEmpty())
            assertFalse(Files.exists(store.resolve("current/SDK.zip"), LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(store.resolve("blobs/${sha256Hex(v2)}")))
            assertEquals(1, audit("project_file_deleted").size)
            assertEquals(404, root.delete("/api/projects/demo-app/files/$id") { header("X-Factory-Request", "1") }.status.value)
        }
    }

    @Test
    fun `invalid names, name conflicts`() = testApplication {
        application { agernModule(world.services) }
        with(PF) {
            val root = login("root", "pw-r")
            val bad = listOf("../x", "/abs", "a//b", "a/./b", ".hidden", "-x", "a b", "a\\b", "x".repeat(201), (1..9).joinToString("/") { "s$it" })
            for (n in bad) {
                val r = root.create("demo-app", n, 3)
                assertEquals(400, r.status.value, n)
                assertEquals("invalid_file_name", code(r.bodyAsText()), n)
            }
            root.uploadFile("demo-app", "sdk/a.zip", "abc".toByteArray())
            val r = root.create("demo-app", "sdk", 3)
            assertEquals(409, r.status.value)
            assertEquals("name_conflict", code(r.bodyAsText()))
            assertEquals("name_conflict", code(root.create("demo-app", "sdk/a.zip/b", 3).bodyAsText()))
        }
    }

    @Test
    fun `limits per file, per project, in total, file count and free space`() {
        world.close()
        world = TestWorld(twoFactor = false, clock = clock, projectFilesFreeSpace = { freeSpace }, tweak = {
            it.copy(projectFilesMaxFileBytes = 10, projectFilesMaxProjectBytes = 15, projectFilesMaxTotalBytes = 20)
        })
        val s = world.services
        val root = world.user("root", admin = true)
        fun err(block: () -> Unit) = (runCatching(block).exceptionOrNull() as ApiException).let { it.status.value to it.code }
        fun upload(repo: String, name: String, size: Int) {
            val (u, _) = s.projectFiles.createUpload(root, repo, name, size.toLong(), null, null)
            val id = java.util.UUID.fromString(u.id)
            s.projectFiles.append(root, repo, id, 0, ByteArray(size) { (it + name.length).toByte() }.inputStream())
            s.projectFiles.complete(root, repo, id, null)
        }
        assertEquals(413 to "file_too_large", err { s.projectFiles.createUpload(root, "demo-app", "a", 11, null, null) })
        // Less free space than the file plus the reserve.
        freeSpace = 100
        assertEquals(507 to "insufficient_storage", err { s.projectFiles.createUpload(root, "demo-app", "z", 1, null, null) })
        freeSpace = Long.MAX_VALUE
        upload("demo-app", "a", 10)
        assertEquals(413 to "project_quota_exceeded", err { s.projectFiles.createUpload(root, "demo-app", "b", 6, null, null) })
        // An open upload's declared size counts.
        val b = s.projectFiles.createUpload(root, "demo-app", "b", 5, null, null).first
        assertEquals(413 to "project_quota_exceeded", err { s.projectFiles.createUpload(root, "demo-app", "c", 1, null, null) })
        // A replacement counts the new size minus the old one.
        val a = s.projectFiles.createUpload(root, "demo-app", "a", 10, null, null).first
        s.projectFiles.discard(root, "demo-app", java.util.UUID.fromString(a.id))
        s.projectFiles.discard(root, "demo-app", java.util.UUID.fromString(b.id))
        upload("demo-workspace", "x", 10)
        assertEquals(413 to "total_quota_exceeded", err { s.projectFiles.createUpload(root, "demo-workspace", "y", 1, null, null) })

    }

    @Test
    fun `the file count is capped`() {
        val s = world.services
        val root = world.user("root", admin = true)
        // Rows only: the count check reads the database.
        repeat(dk.egernet.factory.files.ProjectFiles.MAX_FILES) { i ->
            s.db.update(
                "INSERT INTO project_files(id, repo, name, size_bytes, sha256, uploaded_by) VALUES (?, 'demo-app', ?, 1, ?, ?)",
                java.util.UUID.randomUUID(), "f$i", "a".repeat(64), root.id,
            )
        }
        val e = runCatching { s.projectFiles.createUpload(root, "demo-app", "one-more", 1, null, null) }.exceptionOrNull() as ApiException
        assertEquals(409 to "too_many_files", e.status.value to e.code)
    }

    @Test
    fun `the limits are validated at startup`() {
        fun cfg(vararg kv: Pair<String, String>) = runCatching {
            Config.fromEnv(mapOf("FACTORY_MASTER_KEY" to java.util.Base64.getEncoder().encodeToString(ByteArray(32)), "FACTORY_GITHUB_ORG" to "o") + kv)
        }
        val ok = cfg().getOrThrow()
        assertEquals(1024L shl 20, ok.projectFilesMaxFileBytes)
        assertEquals(5120L shl 20, ok.projectFilesMaxProjectBytes)
        assertEquals(20480L shl 20, ok.projectFilesMaxTotalBytes)
        assertTrue(cfg("FACTORY_PROJECT_FILES_MAX_FILE_MB" to "0").isFailure)
        assertTrue(cfg("FACTORY_PROJECT_FILES_MAX_FILE_MB" to "102401").isFailure)
        assertTrue(cfg("FACTORY_PROJECT_FILES_MAX_FILE_MB" to "abc").isFailure)
        assertTrue(cfg("FACTORY_PROJECT_FILES_MAX_FILE_MB" to "6000").isFailure) // above the project limit
        assertTrue(cfg("FACTORY_PROJECT_FILES_MAX_PROJECT_MB" to "30000").isFailure) // above the total limit
        assertEquals(6000L shl 20, cfg("FACTORY_PROJECT_FILES_MAX_FILE_MB" to "6000", "FACTORY_PROJECT_FILES_MAX_PROJECT_MB" to "6000").getOrThrow().projectFilesMaxProjectBytes)
    }

    @Test
    fun `uploads expire 24 hours after their last write`() {
        val s = world.services
        val root = world.user("root", admin = true)
        val (u, _) = s.projectFiles.createUpload(root, "demo-app", "old.bin", 4, null, null)
        clock.advance(Duration.ofHours(25))
        s.projectFiles.createUpload(root, "demo-app", "new.bin", 4, null, null)
        assertEquals(listOf("new.bin"), s.projectFiles.store.uploads("demo-app").map { it.name })
        assertFalse(Files.exists(store.resolve("uploads/${u.id}.part")))
    }

    @Test
    fun `visibility, managers and admin routes`() = testApplication {
        application { agernModule(world.services) }
        with(PF) {
            val root = login("root", "pw-r")
            val alice = login("alice", "pw-a")
            val bob = login("bob", "pw-b")
            // private-lab: only alice sees it. Bob gets 404 everywhere, like an unknown project.
            val f = root.setManagers("demo-app", "bob")
            assertEquals(200, f.status.value, f.bodyAsText())
            assertEquals(listOf("bob"), j(f.bodyAsText())["managers"]!!.jsonArray.map { it.jsonObject["username"]!!.jsonPrimitive.content })
            assertTrue(audit("project_file_managers_changed").single().contains("demo-app: bob"))

            // A listed manager can manage; a viewer who is not listed cannot.
            val file = bob.uploadFile("demo-app", "lib.jar", "jar bytes".toByteArray())
            val id = file["id"]!!.jsonPrimitive.content
            assertTrue(j(bob.files("demo-app").bodyAsText())["canManage"]!!.jsonPrimitive.content.toBoolean())
            assertFalse(j(alice.files("demo-app").bodyAsText())["canManage"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(1, j(alice.files("demo-app").bodyAsText())["files"]!!.jsonArray.size)
            for (r in listOf(
                alice.create("demo-app", "x.bin", 3),
                alice.updateFile("demo-app", id, """{"note":"n"}"""),
                alice.delete("/api/projects/demo-app/files/$id") { header("X-Factory-Request", "1") },
                alice.setManagers("demo-app", "alice"),
            )) {
                assertEquals(403, r.status.value, r.bodyAsText())
            }
            // Bob cannot set managers either.
            assertEquals(403, bob.setManagers("demo-app", "alice").status.value)
            assertEquals(400, root.setManagers("demo-app", "nobody-known").status.value)

            // Alice manages private-lab only when listed; bob never sees it.
            val aliceAdmin = world.services.users.byUsername("alice")!!
            world.services.projectFiles.store.setManagers("private-lab", listOf(aliceAdmin.id), aliceAdmin.id)
            val secret = alice.uploadFile("private-lab", "secret.bin", "secret".toByteArray())
            val sid = secret["id"]!!.jsonPrimitive.content
            assertEquals(200, alice.updateFile("private-lab", sid, """{"downloadable":true}""").status.value)
            for (r in listOf(
                bob.files("private-lab"),
                bob.create("private-lab", "x.bin", 3),
                bob.get("/api/projects/private-lab/file-uploads/${java.util.UUID.randomUUID()}"),
                bob.chunk("private-lab", java.util.UUID.randomUUID().toString(), 0, ByteArray(1)),
                bob.complete("private-lab", java.util.UUID.randomUUID().toString()),
                bob.updateFile("private-lab", sid, """{"note":"x"}"""),
                bob.delete("/api/projects/private-lab/files/$sid") { header("X-Factory-Request", "1") },
                bob.get("/api/projects/private-lab/files/$sid/content"),
                bob.setManagers("private-lab", "bob"),
            )) {
                assertEquals(404, r.status.value, r.bodyAsText())
            }
            // A file id of another repository is 404.
            assertEquals(404, bob.get("/api/projects/demo-app/files/$sid/content").status.value)

            // Admin routes: admins only, counts without names.
            assertEquals(403, alice.get("/api/admin/project-files").status.value)
            val overview = j(root.get("/api/admin/project-files").bodyAsText())
            assertFalse(overview.toString().contains("secret.bin"))
            assertEquals(setOf("demo-app", "private-lab"), overview["repos"]!!.jsonArray.map { it.jsonObject["repo"]!!.jsonPrimitive.content }.toSet())
            // The admin purges a repository it cannot see.
            assertEquals(403, alice.delete("/api/admin/project-files/private-lab") { header("X-Factory-Request", "1") }.status.value)
            assertEquals(204, root.delete("/api/admin/project-files/private-lab") { header("X-Factory-Request", "1") }.status.value)
            assertTrue(j(alice.files("private-lab").bodyAsText())["files"]!!.jsonArray.isEmpty())
            assertFalse(Files.exists(world.services.projectFiles.root.resolve("private-lab/current")))
            assertTrue(audit("project_files_purged").single().contains("private-lab: 1 file(s), 6 bytes"))
        }
    }

    @Test
    fun `download is off by default, then streamed with safe headers`() = testApplication {
        application { agernModule(world.services) }
        with(PF) {
            val root = login("root", "pw-r")
            val alice = login("alice", "pw-a")
            val bytes = ByteArray(3000) { (it % 251).toByte() }
            val id = root.uploadFile("demo-app", "tools/blob.bin", bytes, chunk = 1000)["id"]!!.jsonPrimitive.content
            val off = alice.get("/api/projects/demo-app/files/$id/content")
            assertEquals(403, off.status.value)
            assertEquals("download_disabled", code(off.bodyAsText()))
            assertEquals(403, alice.updateFile("demo-app", id, """{"downloadable":true}""").status.value)
            val on = root.updateFile("demo-app", id, """{"downloadable":true,"note":"from the vendor portal"}""")
            assertEquals(200, on.status.value)
            assertTrue(audit("project_file_updated").single().let { it.contains("note") && it.contains("download on") })
            val r = alice.get("/api/projects/demo-app/files/$id/content")
            assertEquals(200, r.status.value)
            assertContentEquals(bytes, r.readRawBytes())
            assertEquals("application/octet-stream", r.headers["Content-Type"])
            assertTrue(r.headers["Content-Disposition"]!!.startsWith("attachment; filename=\"blob.bin\""))
            assertEquals("nosniff", r.headers["X-Content-Type-Options"])
            assertEquals("private, no-store", r.headers["Cache-Control"])
            assertEquals(1, audit("project_file_downloaded").size)
        }
    }
}
