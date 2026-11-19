package dk.egernet.factory.agent

import dk.egernet.factory.ApiException
import dk.egernet.factory.Config
import dk.egernet.factory.db.AttachmentMeta
import dk.egernet.factory.db.AttachmentStore
import dk.egernet.factory.db.InsertResult
import dk.egernet.factory.db.NewAttachment
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** The four raster types Claude accepts as image input; everything else is a plain file. */
val INLINE_IMAGE_TYPES = listOf("image/png", "image/jpeg", "image/gif", "image/webp")

/** Decides the type of uploaded bytes from their magic numbers; the client's Content-Type is never trusted. */
object Sniff {
    fun mediaType(b: ByteArray): String = when {
        b.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
        b.startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
        b.startsWith('G'.code, 'I'.code, 'F'.code, '8'.code) && b.size > 5 && (b[4] == '7'.code.toByte() || b[4] == '9'.code.toByte()) &&
            b[5] == 'a'.code.toByte() -> "image/gif"
        b.size >= 16 && b.startsWith('R'.code, 'I'.code, 'F'.code, 'F'.code) &&
            String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        b.startsWith('%'.code, 'P'.code, 'D'.code, 'F'.code, '-'.code) -> "application/pdf"
        b.startsWith('P'.code, 'K'.code, 3, 4) -> "application/zip"
        looksLikeText(b) -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun ByteArray.startsWith(vararg m: Int) = size >= m.size && m.indices.all { this[it] == m[it].toByte() }

    /** Valid UTF-8 without NUL bytes (only the first 64 KiB are checked). */
    private fun looksLikeText(b: ByteArray): Boolean {
        val n = minOf(b.size, 64 * 1024)
        if (n == 0) return false
        for (i in 0 until n) if (b[i] == 0.toByte()) return false
        val dec = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        // A truncated multi-byte sequence at the 64 KiB cut is fine.
        val slice = if (b.size > n) b.copyOf(maxOf(0, n - 4)) else b
        return runCatching { dec.decode(java.nio.ByteBuffer.wrap(slice)) }.isSuccess
    }

    /** Width and height from the image header (no decoding), or null. */
    fun dimensions(type: String, b: ByteArray): Pair<Int, Int>? = runCatching {
        fun u16be(i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        fun u16le(i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        fun u24le(i: Int) = u16le(i) or ((b[i + 2].toInt() and 0xFF) shl 16)
        fun u32be(i: Int) = (u16be(i) shl 16) or u16be(i + 2)
        when (type) {
            "image/png" -> u32be(16) to u32be(20)
            "image/gif" -> u16le(6) to u16le(8)
            "image/webp" -> when (String(b, 12, 4, Charsets.US_ASCII)) {
                "VP8X" -> (u24le(24) + 1) to (u24le(27) + 1)
                "VP8L" -> {
                    val bits = (b[21].toInt() and 0xFF) or ((b[22].toInt() and 0xFF) shl 8) or ((b[23].toInt() and 0xFF) shl 16) or ((b[24].toInt() and 0xFF) shl 24)
                    ((bits and 0x3FFF) + 1) to (((bits shr 14) and 0x3FFF) + 1)
                }
                "VP8 " -> (u16le(26) and 0x3FFF) to (u16le(28) and 0x3FFF)
                else -> null
            }
            "image/jpeg" -> {
                var i = 2
                var found: Pair<Int, Int>? = null
                while (i + 9 < b.size && found == null) {
                    if (b[i] != 0xFF.toByte()) { i++; continue }
                    val marker = b[i + 1].toInt() and 0xFF
                    when {
                        marker == 0xFF -> i++
                        marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 -> i += 2
                        marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC -> found = u16be(i + 7) to u16be(i + 5)
                        else -> i += 2 + u16be(i + 2)
                    }
                }
                found
            }
            else -> null
        }
    }.getOrNull()?.takeIf { (w, h) -> w > 0 && h > 0 }
}

/**
 * Chat attachments: upload analysis, the agent's on-disk copy (a cache of the database) and the stream-json
 * content a message with attachments becomes. See docs/ARCHITECTURE.md §5 and §8.
 */
class Attachments(private val cfg: Config, val store: AttachmentStore) {
    private val log = LoggerFactory.getLogger(Attachments::class.java)

    init {
        System.setProperty("java.awt.headless", "true")
    }

    companion object {
        /** Largest image passed inline: 5 MB of base64 (the strictest platform limit; the Claude API allows 10). */
        const val INLINE_MAX_BYTES = 3_750_000
        /**
         * Longest edge passed inline. The API rejects images over 8000 px, and over 2000 px once a request holds more
         * than 20 images. Every image in the conversation is resent with each request, so stay under the stricter limit.
         */
        const val INLINE_MAX_EDGE = 2000
        /** Images with more pixels than this are never decoded (decompression bombs); they become files only. */
        const val MAX_DECODE_PIXELS = 50_000_000L
        val UNSENT_TTL: Duration = Duration.ofHours(24)

        /** Display name: no path, no control or bidi-format characters, no leading dots, at most 120 characters. */
        fun sanitizeName(raw: String?): String {
            var n = (raw ?: "").substringAfterLast('/').substringAfterLast('\\')
            n = buildString {
                for (c in n) {
                    val t = Character.getType(c)
                    when {
                        Character.isISOControl(c) || t == Character.FORMAT.toInt() || t == Character.LINE_SEPARATOR.toInt() ||
                            t == Character.PARAGRAPH_SEPARATOR.toInt() -> {}
                        c in ":*?\"<>|" -> append('_')
                        else -> append(c)
                    }
                }
            }
            n = n.replace(Regex("\\s+"), " ").trim().trimStart('.', ' ').trim()
            if (n.codePointCount(0, n.length) > 120) {
                val dot = n.lastIndexOf('.')
                val ext = if (dot > 0 && n.length - dot <= 16) n.substring(dot) else ""
                val base = n.substring(0, n.length - ext.length)
                n = base.substring(0, base.offsetByCodePoints(0, minOf(base.codePointCount(0, base.length), 120 - ext.length))).trimEnd() + ext
            }
            return n.ifEmpty { "attachment" }
        }

        /** File name on disk: `<id>-<name>` with only `[A-Za-z0-9._-]`. */
        fun diskName(id: UUID, name: String): String {
            val safe = name.map { if (it.isLetterOrDigit() && it.code < 128 || it in "._-") it else '_' }.joinToString("")
                .replace(Regex("_+"), "_").trimStart('.', '_', '-').take(80).ifEmpty { "attachment" }
            return "$id-$safe"
        }

        fun humanSize(n: Long): String = when {
            n < 1024 -> "$n B"
            n < 1024 * 1024 -> "%.1f KB".format(java.util.Locale.ROOT, n / 1024.0)
            else -> "%.1f MB".format(java.util.Locale.ROOT, n / (1024.0 * 1024.0))
        }
    }

    // ------------------------------------------------------------ upload

    /** Analyses and stores one upload as an unsent attachment of [sessionId]. Blocking (image work). */
    fun upload(sessionId: UUID, uploaderId: UUID, rawName: String?, bytes: ByteArray): AttachmentMeta {
        if (bytes.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, "empty_attachment", "The file is empty")
        val a = analyse(sanitizeName(rawName), bytes)
        return when (val r = store.insertUnsent(sessionId, uploaderId, a, cfg.attachmentMaxCount, cfg.attachmentMaxMessageBytes)) {
            is InsertResult.Ok -> r.meta
            InsertResult.TooMany -> throw tooMany()
            InsertResult.TooLarge -> throw messageTooLarge()
        }
    }

    fun tooMany() = ApiException(HttpStatusCode.Conflict, "too_many_attachments", "At most ${cfg.attachmentMaxCount} attachments per message")
    fun fileTooLarge() = ApiException(
        HttpStatusCode.PayloadTooLarge, "attachment_too_large", "The file is larger than ${humanSize(cfg.attachmentMaxBytes)}",
    )
    fun messageTooLarge() = ApiException(
        HttpStatusCode.PayloadTooLarge, "attachments_too_large",
        "Attachments of one message may total at most ${humanSize(cfg.attachmentMaxMessageBytes)}",
    )

    fun analyse(name: String, bytes: ByteArray): NewAttachment {
        val type = Sniff.mediaType(bytes)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (type !in INLINE_IMAGE_TYPES) {
            return NewAttachment(name, type, "file", bytes, sha, null, null, "none", null, null, null, null, null)
        }
        val dims = Sniff.dimensions(type, bytes)
        val (w, h) = dims ?: (null to null)
        fun asImage(mode: String, data: ByteArray? = null, mt: String? = null, iw: Int? = null, ih: Int? = null, note: String? = null) =
            NewAttachment(name, type, "image", bytes, sha, w, h, mode, data, mt, iw, ih, note)

        if (dims == null) return asImage("none", note = "Could not read the image size; passed to the agent as a file only.")
        val fitsEdge = w!! <= INLINE_MAX_EDGE && h!! <= INLINE_MAX_EDGE
        if (fitsEdge && bytes.size <= INLINE_MAX_BYTES) return asImage("original")

        // Too big to pass as is: downscale when we can decode it safely.
        val why = if (!fitsEdge) "${w}×$h px" else humanSize(bytes.size.toLong())
        if (type == "image/webp") return asImage("none", note = "Too large to show to the model inline ($why, WebP cannot be resized here); passed as a file only.")
        if (w.toLong() * h!! > MAX_DECODE_PIXELS) return asImage("none", note = "Too large to show to the model inline ($why); passed as a file only.")
        val scaled = runCatching { downscale(bytes) }.onFailure { log.info("Could not downscale attachment {}: {}", name, it.toString()) }.getOrNull()
            ?: return asImage("none", note = "Too large to show to the model inline ($why) and could not be resized; passed as a file only.")
        return asImage(
            "downscaled", scaled.bytes, scaled.mediaType, scaled.width, scaled.height,
            note = "Shown to the model as a downscaled copy (${scaled.width}×${scaled.height}); the original ($why) is available as a file.",
        )
    }

    private class Scaled(val bytes: ByteArray, val mediaType: String, val width: Int, val height: Int)

    private fun downscale(bytes: ByteArray): Scaled? {
        val src = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val f = minOf(1.0, INLINE_MAX_EDGE.toDouble() / maxOf(src.width, src.height))
        val nw = maxOf(1, (src.width * f).toInt())
        val nh = maxOf(1, (src.height * f).toInt())
        val alpha = src.colorModel.hasAlpha()
        val dst = BufferedImage(nw, nh, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        val g = dst.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(src, 0, 0, nw, nh, null)
        } finally {
            g.dispose()
        }
        if (alpha) {
            val out = ByteArrayOutputStream()
            ImageIO.write(dst, "png", out)
            if (out.size() <= INLINE_MAX_BYTES) return Scaled(out.toByteArray(), "image/png", nw, nh)
            return null
        }
        for (q in listOf(0.85f, 0.7f, 0.5f)) {
            val out = ByteArrayOutputStream()
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            try {
                ImageIO.createImageOutputStream(out).use { ios ->
                    writer.output = ios
                    val p = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = q }
                    writer.write(null, IIOImage(dst, null, null), p)
                }
            } finally {
                writer.dispose()
            }
            if (out.size() <= INLINE_MAX_BYTES) return Scaled(out.toByteArray(), "image/jpeg", nw, nh)
        }
        return null
    }

    // ------------------------------------------------------------ the agent's copy on disk

    fun dir(sessionId: UUID): Path = cfg.dataDir.resolve("attachments").resolve(sessionId.toString())
    fun path(m: AttachmentMeta): Path = dir(m.sessionId).resolve(diskName(m.id, m.name))

    /** Writes the attachment to its path unless an intact copy is there already. Blocking. */
    fun materialise(m: AttachmentMeta): Path {
        val p = path(m)
        if (Files.isRegularFile(p) && Files.size(p) == m.size) return p
        val data = store.data(m.id) ?: error("attachment ${m.id} has no data")
        Files.createDirectories(p.parent)
        runCatching {
            Files.setPosixFilePermissions(p.parent.parent, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        }
        val tmp = Files.createTempFile(p.parent, ".incoming-", ".tmp")
        try {
            Files.write(tmp, data)
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
        return p
    }

    /** Restores every sent attachment of the session that is missing on disk (e.g. after the data dir was cleaned). */
    fun materialiseSession(sessionId: UUID): Int {
        var n = 0
        for (m in store.forSession(sessionId)) {
            val p = path(m)
            if (!(Files.isRegularFile(p) && Files.size(p) == m.size)) {
                runCatching { materialise(m); n++ }.onFailure { log.warn("Could not restore attachment {}: {}", m.id, it.message) }
            }
        }
        return n
    }

    /**
     * Removes the session's directory with everything in it (the session was closed). Best effort: the files are a
     * cache of rows that are already gone, so a failure is only logged. Blocking.
     */
    fun removeSession(sessionId: UUID) {
        val d = dir(sessionId)
        if (!Files.exists(d)) return
        try {
            Files.walk(d).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        } catch (e: Exception) {
            log.warn("Could not remove attachment files of closed session {}: {}", sessionId, e.toString())
        }
    }

    // ------------------------------------------------------------ what the agent receives

    /**
     * The `message.content` of a stream-json user turn. Without attachments: the plain text (as before).
     * With attachments: image blocks for the images that go inline, the user's text, and a text block that lists
     * every attachment with its path on disk, type and size. Blocking (materialises the files).
     */
    fun content(text: String, list: List<AttachmentMeta>): JsonElement {
        if (list.isEmpty()) return JsonPrimitive(text)
        val paths = list.associate { it.id to materialise(it) }
        val inline = list.filter { it.kind == "image" && it.inlineMode != "none" }
        return buildJsonArray {
            for (m in inline) {
                val data = (if (m.inlineMode == "downscaled") store.inlineData(m.id) else store.data(m.id)) ?: continue
                addJsonObject {
                    put("type", "image")
                    putJsonObject("source") {
                        put("type", "base64")
                        put("media_type", if (m.inlineMode == "downscaled") m.inlineMediaType ?: m.mediaType else m.mediaType)
                        put("data", Base64.getEncoder().encodeToString(data))
                    }
                }
            }
            if (text.isNotBlank()) addJsonObject { put("type", "text"); put("text", text) }
            addJsonObject { put("type", "text"); put("text", listing(list, inline, paths)) }
        }
    }

    private fun listing(list: List<AttachmentMeta>, inline: List<AttachmentMeta>, paths: Map<UUID, Path>) = buildString {
        append("[Attachments] The user attached ${list.size} file${if (list.size == 1) "" else "s"} to this message. ")
        append("They are saved outside the repository working tree: read them from the paths below, and copy one into the repository only if the user asks for that.\n")
        list.forEachIndexed { i, m ->
            append("${i + 1}. ${m.name} — ${m.mediaType}, ${humanSize(m.size)} (${m.size} bytes)")
            if (m.width != null && m.height != null) append(", ${m.width}×${m.height} px")
            val pos = inline.indexOf(m)
            when {
                pos >= 0 && m.inlineMode == "original" -> append(" — shown above as image ${pos + 1}")
                pos >= 0 -> append(" — shown above as image ${pos + 1}, downscaled to ${m.inlineWidth}×${m.inlineHeight}; the file has the original")
                m.kind == "image" -> append(" — not shown inline (${m.note ?: "too large"}); read the file")
            }
            append("\n   path: ${paths.getValue(m.id)}\n")
        }
    }.trimEnd()

    // ------------------------------------------------------------ cleanup

    /** Deletes uploads that were never sent within [UNSENT_TTL]. */
    fun startReaper(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            runCatching { store.purgeUnsent(Instant.now().minus(UNSENT_TTL)) }
                .onSuccess { if (it > 0) log.info("Deleted {} unsent attachment(s) older than {} h", it, UNSENT_TTL.toHours()) }
                .onFailure { log.warn("Attachment cleanup failed: {}", it.message) }
            delay(10 * 60_000L)
        }
    }
}
