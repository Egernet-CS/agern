package dk.egernet.factory.images

import dk.egernet.factory.AppJson
import dk.egernet.factory.agent.Sniff
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * OpenAI Codex as an image provider (docs/ARCHITECTURE.md §4 "Codex login"): the Codex CLI, run on this server with the
 * user's own ChatGPT login, uses its built-in image tool. The "key" is the text of Codex's `auth.json`, stored encrypted
 * in `users.image_key_enc` and connected through [CodexLogins], never through the API-key form.
 *
 * Security: the login is the one secret that exists in plaintext on disk, only while its `codex` process runs, in a
 * fresh `0700` directory under [baseDir] that is deleted afterwards. Codex runs as the server's OS user, like every
 * agent (the documented v1 limitation). Its environment is built from scratch — no Claude, GitHub or factory variable —
 * and the prompt reaches it on stdin only, never on the command line.
 */
class CodexImageProvider(
    private val bin: String,
    private val path: String,
    val baseDir: Path,
    private val timeout: Duration = Duration.ofSeconds(600),
    /** Tests only: extra environment variables for the CLI (e.g. the fake's modes). */
    private val extraEnv: Map<String, String> = emptyMap(),
) : ImageProvider {
    private val log = LoggerFactory.getLogger(CodexImageProvider::class.java)

    override val id = "codex"
    override val label = "OpenAI Codex"
    override val models = listOf("default")
    override val defaultModel = "default"
    override val supportsTransparency = true
    override val keyPlaceholder = ""
    override val auth = ImageProvider.DEVICE_LOGIN

    companion object {
        const val UNAVAILABLE = "The Codex CLI is not installed on this server."
        const val MAX_AUTH_BYTES = 64 * 1024
        private const val MAX_OUTPUT = 64 * 1024
        private val DIR_MODE = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        private val FILE_MODE = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        private val UNAUTHORIZED = Regex("(?i)401|unauthori[sz]ed|not logged in|log ?in required|refresh token|token.*expired")
        private val QUOTA = Regex("(?i)usage limit|rate limit|quota|too many requests")
        private val REFUSED = Regex("(?i)content policy|safety|moderation|not allowed to generate")

        /** The failure kind for a call that produced no usable image, from the CLI's output (never passed on). */
        fun failureKind(output: String): String = when {
            UNAUTHORIZED.containsMatchIn(output) -> ImageProviderException.INVALID_CREDENTIAL
            QUOTA.containsMatchIn(output) -> ImageProviderException.QUOTA
            REFUSED.containsMatchIn(output) -> ImageProviderException.CONTENT_REFUSED
            else -> ImageProviderException.NO_IMAGE
        }

        /** [secret] if it is a usable `auth.json`: 1 byte to 64 KiB of a JSON object. */
        fun usableAuth(secret: String): Boolean {
            val n = secret.toByteArray().size
            if (n < 1 || n > MAX_AUTH_BYTES) return false
            return runCatching { AppJson.parseToJsonElement(secret) is JsonObject }.getOrDefault(false)
        }

        fun instruction(r: ImageRequest): String = buildString {
            append("Use the built-in image generation tool to create exactly ${r.count} separate image(s), one tool call per image.\n")
            append("Do not run shell commands, and do not create, move or edit any other file.\n")
            append("Do not ask questions.\n")
            append("Number of images: ${r.count}\n")
            append("Wanted size: ${r.width}x${r.height} pixels (or the nearest supported aspect ratio)\n")
            append("Format: ${r.format}\n")
            append("Transparent background: ${if (r.transparent) "yes" else "no"}\n")
            append("Image description:\n")
            append(r.prompt)
            append("\n")
        }
    }

    // ------------------------------------------------------------ processes (shared with CodexLogins)

    /** A per-call directory `baseDir/<uuid>/` with `home/` (HOME and CODEX_HOME) and `work/`, all `0700`. */
    inner class CallDir : AutoCloseable {
        val root: Path = run {
            Files.createDirectories(baseDir)
            Files.createDirectory(baseDir.resolve(UUID.randomUUID().toString()), DIR_MODE)
        }
        val home: Path = Files.createDirectory(root.resolve("home"), DIR_MODE)
        val work: Path = Files.createDirectory(root.resolve("work"), DIR_MODE)
        val authFile: Path get() = home.resolve("auth.json")

        fun writeAuth(secret: String) {
            Files.deleteIfExists(authFile)
            Files.createFile(authFile, FILE_MODE)
            Files.writeString(authFile, secret)
        }

        /** The current `auth.json` if it is a regular file (not a symlink) holding a usable login. */
        fun readAuth(): String? {
            if (!Files.isRegularFile(authFile, LinkOption.NOFOLLOW_LINKS)) return null
            if (Files.size(authFile) > MAX_AUTH_BYTES) return null
            val text = runCatching { Files.readString(authFile) }.getOrNull() ?: return null
            return text.takeIf { usableAuth(it) }
        }

        override fun close() {
            runCatching { SafeFiles.deleteTree(root) }
        }
    }

    /** The binary, a bare name resolved on the agent PATH (the child's PATH, not the server's). */
    private fun binary(): String {
        if ('/' in bin) return bin
        return path.split(':').filter { it.isNotEmpty() }.map { Path.of(it, bin) }
            .firstOrNull { it.isRegularFile() && it.isExecutable() }?.toString() ?: bin
    }

    /** A process builder for `codex <args>` with an environment built from scratch around [dir]. */
    fun processBuilder(dir: CallDir, args: List<String>): ProcessBuilder {
        val pb = ProcessBuilder(listOf(binary()) + args).directory(dir.work.toFile()).redirectErrorStream(true)
        val env = pb.environment()
        env.clear()
        env["PATH"] = path
        env["HOME"] = dir.home.toString()
        env["CODEX_HOME"] = dir.home.toString()
        env["LANG"] = "C.UTF-8"
        env["TERM"] = "dumb"
        env["NO_COLOR"] = "1"
        env.putAll(extraEnv)
        return pb
    }

    /** Collects a process's output on a daemon thread, keeping at most the last 64 KiB. */
    class OutputTail(proc: Process, onChunk: (String) -> Unit = {}) {
        private val buf = ByteArrayOutputStream()
        val thread = Thread {
            runCatching {
                proc.inputStream.use { input ->
                    val chunk = ByteArray(4096)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        synchronized(buf) {
                            buf.write(chunk, 0, n)
                            if (buf.size() > MAX_OUTPUT * 2) {
                                val keep = buf.toByteArray().let { it.copyOfRange(it.size - MAX_OUTPUT, it.size) }
                                buf.reset(); buf.write(keep)
                            }
                        }
                        onChunk(text())
                    }
                }
            }
        }.apply { isDaemon = true; name = "codex-output"; start() }

        fun text(): String = synchronized(buf) {
            val b = buf.toByteArray()
            String(if (b.size > MAX_OUTPUT) b.copyOfRange(b.size - MAX_OUTPUT, b.size) else b)
        }
    }

    /** Deletes leftovers of earlier server runs (crash) under [baseDir]. */
    fun cleanLeftovers() {
        if (!Files.isDirectory(baseDir, LinkOption.NOFOLLOW_LINKS)) return
        Files.list(baseDir).use { s -> s.toList() }.forEach { runCatching { SafeFiles.deleteTree(it) } }
    }

    // ------------------------------------------------------------ availability

    @Volatile private var checked: Pair<Long, String?>? = null

    override fun availability(): String? {
        val now = System.nanoTime()
        checked?.let { (at, reason) -> if (now - at < TimeUnit.SECONDS.toNanos(60)) return reason }
        val reason = probe()
        checked = now to reason
        return reason
    }

    private fun probe(): String? {
        val dir = try { CallDir() } catch (e: Exception) { return UNAVAILABLE }
        dir.use {
            return try {
                val p = processBuilder(dir, listOf("--version")).start()
                val out = OutputTail(p)
                if (!p.waitFor(10, TimeUnit.SECONDS)) {
                    p.destroyForcibly(); UNAVAILABLE
                } else {
                    out.thread.join(1000)
                    if (p.exitValue() == 0) null else UNAVAILABLE
                }
            } catch (e: Exception) {
                UNAVAILABLE
            }
        }
    }

    // ------------------------------------------------------------ the provider

    /** Codex logins are not validated by a call (that would spend the user's quota); only their shape is checked. */
    override fun validate(key: String, model: String) {
        if (!usableAuth(key)) throw ImageProviderException(ImageProviderException.INVALID_CREDENTIAL)
    }

    override fun generate(key: String, model: String, request: ImageRequest): List<ByteArray> = generate(key, model, request) {}

    override fun generate(key: String, model: String, request: ImageRequest, renew: (String) -> Unit): List<ByteArray> {
        if (!usableAuth(key)) throw ImageProviderException(ImageProviderException.INVALID_CREDENTIAL)
        val dir = try {
            CallDir()
        } catch (e: Exception) {
            throw ImageProviderException(ImageProviderException.UNAVAILABLE)
        }
        dir.use {
            dir.writeAuth(key)
            var output = ""
            var timedOut = false
            try {
                val proc = try {
                    processBuilder(
                        dir,
                        listOf(
                            "exec", "--skip-git-repo-check", "--ignore-user-config", "--json", "--color", "never", "-s", "read-only",
                            "-C", dir.work.toString(), "-c", "cli_auth_credentials_store=\"file\"", "-",
                        ),
                    ).start()
                } catch (e: Exception) {
                    throw ImageProviderException(ImageProviderException.UNAVAILABLE)
                }
                val tail = OutputTail(proc)
                try {
                    runCatching { proc.outputStream.use { it.write(instruction(request).toByteArray()) } }
                    if (!proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                        timedOut = true
                        proc.destroyForcibly()
                        proc.waitFor(5, TimeUnit.SECONDS)
                    }
                } catch (e: InterruptedException) {
                    proc.destroyForcibly()
                    Thread.currentThread().interrupt()
                    throw ImageProviderException(ImageProviderException.UNAVAILABLE)
                } finally {
                    if (proc.isAlive) proc.destroyForcibly()
                    tail.thread.join(2000)
                    output = tail.text()
                }
            } finally {
                // Codex may have refreshed the login, on success and failure alike: the new one must be kept.
                dir.readAuth()?.let { if (it != key) renew(it) }
            }
            if (timedOut) throw ImageProviderException(ImageProviderException.TIMEOUT, timeout.seconds.toString())
            val images = collect(dir, request.count)
            if (images.isEmpty()) {
                val kind = failureKind(output)
                log.info("Codex produced no image ({})", kind)
                throw ImageProviderException(kind)
            }
            return images
        }
    }

    /** Regular files (no symlinks) under `home/generated_images/` and `work/` that are PNG, JPEG or WebP, oldest first. */
    private fun collect(dir: CallDir, count: Int): List<ByteArray> {
        val found = mutableListOf<Pair<Long, Path>>()
        for (root in listOf(dir.home.resolve("generated_images"), dir.work)) {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) continue
            Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                    if (attrs.isRegularFile && !attrs.isSymbolicLink && attrs.size() in 1..ImageService.MAX_IMAGE_BYTES) {
                        found += attrs.lastModifiedTime().toMillis() to file
                    }
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException) = java.nio.file.FileVisitResult.CONTINUE
            })
        }
        return found.sortedWith(compareBy({ it.first }, { it.second.toString() })).asSequence()
            .mapNotNull { (_, f) ->
                runCatching { Files.newInputStream(f, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(ImageService.MAX_IMAGE_BYTES.toInt() + 1) } }.getOrNull()
            }
            .filter { it.size <= ImageService.MAX_IMAGE_BYTES && Sniff.mediaType(it) in ImageService.STORED_TYPES }
            .take(count)
            .toList()
    }
}
