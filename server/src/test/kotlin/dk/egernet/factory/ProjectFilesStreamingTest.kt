package dk.egernet.factory

import dk.egernet.factory.files.ProjectFiles
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Deterministic bytes of [size], generated on the fly; records the largest single read asked for. */
private class GeneratedInput(private val size: Long) : InputStream() {
    var pos = 0L
    var largestRead = 0
    val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    override fun read(): Int = throw UnsupportedOperationException("byte-wise reads")
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        largestRead = maxOf(largestRead, len)
        if (pos >= size) return -1
        val n = minOf(len.toLong(), size - pos).toInt()
        for (i in 0 until n) b[off + i] = ((pos + i) * 31 % 251).toByte()
        digest.update(b, off, n)
        pos += n
        return n
    }
}

/** Records the largest single read of [inner]. */
private class CountingInput(private val inner: InputStream) : InputStream() {
    var largestRead = 0
    override fun read(): Int = throw UnsupportedOperationException("byte-wise reads")
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        largestRead = maxOf(largestRead, len)
        return inner.read(b, off, len)
    }
    override fun close() = inner.close()
}

/** Hashes what is written and records the largest single write. */
private class CountingOutput : OutputStream() {
    var largestWrite = 0
    var total = 0L
    val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    override fun write(b: Int) = throw UnsupportedOperationException("byte-wise writes")
    override fun write(b: ByteArray, off: Int, len: Int) {
        largestWrite = maxOf(largestWrite, len)
        total += len
        digest.update(b, off, len)
    }
}

private fun hex(d: MessageDigest) = d.digest().joinToString("") { "%02x".format(it) }

class ProjectFilesStreamingTest {
    private val world = TestWorld(twoFactor = false)
    @AfterTest fun tearDown() = world.close()

    @Test
    fun `upload, hashing and download of 64 MiB never move more than 1 MiB at once`() {
        val s = world.services
        val root = world.user("root", admin = true)
        val size = 64L shl 20
        val (up, _) = s.projectFiles.createUpload(root, "demo-app", "big/data.bin", size, null, null)
        val id = UUID.fromString(up.id)
        val input = GeneratedInput(size)
        s.projectFiles.append(root, "demo-app", id, 0, input)
        assertTrue(input.largestRead <= ProjectFiles.BUFFER_BYTES, "largest read ${input.largestRead}")
        val expected = hex(input.digest)
        val f = s.projectFiles.complete(root, "demo-app", id, null)
        assertEquals(expected, f.sha256)
        assertEquals(size, f.size)

        // Hashing the stored file goes through the same fixed buffer.
        val stored = CountingInput(java.nio.file.Files.newInputStream(s.projectFiles.blobFile("demo-app", f.sha256)))
        assertEquals(expected, stored.use { ProjectFiles.sha256Of(it) })
        assertTrue(stored.largestRead <= ProjectFiles.BUFFER_BYTES)

        // Download: streamed from disk through the fixed buffer.
        s.projectFiles.update(root, "demo-app", UUID.fromString(f.id), null, true, null)
        val (_, raw) = s.projectFiles.openForDownload(root, "demo-app", UUID.fromString(f.id), null)
        val counted = CountingInput(raw)
        val out = CountingOutput()
        counted.use { ProjectFiles.copy(it, out) }
        assertEquals(size, out.total)
        assertEquals(expected, hex(out.digest))
        assertTrue(counted.largestRead <= ProjectFiles.BUFFER_BYTES)
        assertTrue(out.largestWrite <= ProjectFiles.BUFFER_BYTES)
    }

    @Test
    fun `a multi-chunk file over HTTP round-trips with the same hash`() = testApplication {
        application { agernModule(world.services) }
        with(PF) {
            val root = login("root", "pw-r")
            val bytes = ByteArray((3 shl 20) + 12345) { (it * 7 % 253).toByte() }
            val f = root.uploadFile("demo-app", "sdk/vendor-sdk.zip", bytes, chunk = 1 shl 20)
            assertEquals(sha256Hex(bytes), f["sha256"]!!.jsonPrimitive.content)
            val id = f["id"]!!.jsonPrimitive.content
            root.updateFile("demo-app", id, """{"downloadable":true}""")
            val r = root.get("/api/projects/demo-app/files/$id/content")
            assertEquals(200, r.status.value)
            assertEquals(bytes.size.toString(), r.headers["Content-Length"])
            val out = CountingOutput()
            r.bodyAsChannel().toInputStream().use { ProjectFiles.copy(it, out) }
            assertEquals(sha256Hex(bytes), hex(out.digest))
        }
    }
}
