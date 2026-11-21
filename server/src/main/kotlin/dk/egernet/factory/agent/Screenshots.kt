package dk.egernet.factory.agent

import dk.egernet.factory.ApiException
import dk.egernet.factory.Config
import dk.egernet.factory.SCREENSHOT_ROLES
import dk.egernet.factory.badRequest
import dk.egernet.factory.db.NewScreenshot
import dk.egernet.factory.db.RunStore
import dk.egernet.factory.db.ScreenshotInsertResult
import dk.egernet.factory.db.ScreenshotStore
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.forbidden
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.UUID

/**
 * Screenshots the UX and UI design reviewers attach to their stage with `factory_attach_screenshot`
 * (docs/ARCHITECTURE.md §6 "Screenshots" and §7). Who may attach is derived from the MCP grant (session → stage →
 * role), never from tool arguments. Every refusal is an [ApiException], which the MCP server turns into a tool error.
 */
class Screenshots(
    private val cfg: Config,
    val store: ScreenshotStore,
    private val runs: RunStore,
    private val workspaces: Workspaces,
    private val publishRun: (UUID) -> Unit,
) {
    companion object {
        /** The tool offered to `ux` and `ui-design` stage agents (in addition to the stage tools). */
        val TOOLS = setOf("factory_attach_screenshot")
        const val MAX_CAPTION = 200

        /** One line: control and format characters removed, whitespace runs (newlines included) collapsed, trimmed. */
        fun sanitizeCaption(raw: String): String {
            val spaced = raw.replace(Regex("[\\s\\p{Z}]+"), " ")
            val clean = buildString {
                for (c in spaced) {
                    val t = Character.getType(c)
                    if (Character.isISOControl(c) || t == Character.FORMAT.toInt()) continue
                    append(c)
                }
            }
            return clean.replace(Regex(" {2,}"), " ").trim()
        }

        /** File name extension for a stored (sniffed) image type. */
        fun extension(mediaType: String) = when (mediaType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "bin"
        }

        private fun refused() = forbidden("Only the ux and ui-design reviewers can attach screenshots")
        private fun outside() = badRequest("path must be a file inside your working directory")
        private fun tooLarge(msg: String) = ApiException(HttpStatusCode.PayloadTooLarge, "screenshot_too_large", msg)
    }

    /**
     * Attaches the image at [path] (relative to the run worktree, or absolute) with [caption] to the stage the agent
     * session [sessionId] runs in. [runId] and [repo] come from the grant, [owner] is the grant's owner. Returns the
     * tool's answer; the source file is left untouched.
     */
    suspend fun attach(sessionId: UUID, runId: UUID?, repo: String, owner: UserRecord, path: String?, caption: String?): String {
        // Authorisation first, before anything about the arguments is revealed.
        val run = runId?.let { runs.get(it) } ?: throw refused()
        if (run.repo != repo || run.ownerId != owner.id) throw refused()
        val stage = runs.stages(run.id).firstOrNull { it.sessionId == sessionId } ?: throw refused()
        if (stage.role !in SCREENSHOT_ROLES) throw refused()

        val given = path?.takeIf { it.isNotBlank() } ?: throw badRequest("path is required")
        val text = sanitizeCaption(caption ?: "")
        if (text.isEmpty()) throw badRequest("caption is required: say what the screenshot shows")
        if (text.codePointCount(0, text.length) > MAX_CAPTION) throw badRequest("caption is too long (at most $MAX_CAPTION characters)")

        val bytes = withContext(Dispatchers.IO) { read(workspaces.runDir(owner.username, run.repo, run.id), given) }
        val type = Sniff.mediaType(bytes)
        val dims = if (type in INLINE_IMAGE_TYPES) Sniff.dimensions(type, bytes) else null
        if (dims == null) throw ApiException(HttpStatusCode.UnsupportedMediaType, "unsupported_type", "Only PNG, JPEG, GIF or WebP images are accepted")
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        val res = withContext(Dispatchers.IO) {
            store.insert(run.id, stage.id, NewScreenshot(text, type, bytes, sha, dims.first, dims.second), cfg.screenshotMaxCount)
        }
        return when (res) {
            is ScreenshotInsertResult.TooMany ->
                throw dk.egernet.factory.conflict("This stage already has ${res.count} screenshots (the maximum)")
            is ScreenshotInsertResult.Ok -> {
                publishRun(run.id)
                val m = res.meta
                "Attached screenshot ${res.position} of at most ${cfg.screenshotMaxCount} for this stage: \"${m.caption}\" " +
                    "(${m.mediaType}, ${m.width}×${m.height}, ${Attachments.humanSize(m.size)})."
            }
        }
    }

    /**
     * The bytes of [given], which must resolve (symlinks followed) to a regular file inside [worktree]. The size is
     * checked before reading, and never more than the limit + 1 bytes are read. Blocking.
     */
    private fun read(worktree: Path, given: String): ByteArray {
        val root = runCatching { worktree.toRealPath() }.getOrElse { throw outside() }
        val candidate = try {
            Paths.get(given).let { if (it.isAbsolute) it else root.resolve(it) }
        } catch (e: InvalidPathException) {
            throw outside()
        }
        val real = try {
            candidate.toRealPath()
        } catch (e: NoSuchFileException) {
            throw ApiException(HttpStatusCode.NotFound, "not_found", "No such file: $given")
        } catch (e: Exception) {
            throw outside()
        }
        if (!real.startsWith(root) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) throw outside()
        val limit = cfg.screenshotMaxBytes
        val tooLarge = "The image is larger than ${Attachments.humanSize(limit)}"
        if (Files.size(real) > limit) throw tooLarge(tooLarge)
        // The file may grow between the check and the read: read at most limit + 1 bytes.
        val bytes = Files.newInputStream(real, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes((limit + 1).toInt()) }
        if (bytes.size > limit) throw tooLarge(tooLarge)
        if (bytes.isEmpty()) throw badRequest("The file is empty")
        return bytes
    }
}
