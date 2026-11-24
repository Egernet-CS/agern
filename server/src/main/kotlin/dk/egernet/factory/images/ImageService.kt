package dk.egernet.factory.images

import dk.egernet.factory.ApiException
import dk.egernet.factory.Config
import dk.egernet.factory.GeneratedImageDto
import dk.egernet.factory.ImageConfigDto
import dk.egernet.factory.ImageLimitsDto
import dk.egernet.factory.ImageProviderInfoDto
import dk.egernet.factory.agent.Attachments
import dk.egernet.factory.agent.Screenshots
import dk.egernet.factory.agent.Sniff
import dk.egernet.factory.badRequest
import dk.egernet.factory.conflict
import dk.egernet.factory.db.ImageMeta
import dk.egernet.factory.db.ImageStore
import dk.egernet.factory.db.NewImage
import dk.egernet.factory.db.RunRecord
import dk.egernet.factory.db.RunStore
import dk.egernet.factory.db.SelectResult
import dk.egernet.factory.db.SessionStore
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.db.UserStore
import dk.egernet.factory.agent.Workspaces
import dk.egernet.factory.forbidden
import dk.egernet.factory.github.Access
import dk.egernet.factory.mcp.McpGrant
import dk.egernet.factory.notFound
import dk.egernet.factory.tooManyAttempts
import dk.egernet.factory.security.SecretBox
import dk.egernet.factory.unprocessable
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** An image a run took from its issue's selection, written into the worktree at [path] (relative to it). */
data class PreparedImage(val meta: ImageMeta, val path: String)

/**
 * Image generation for agents (docs/ARCHITECTURE.md §6 "Generated images"): the per-user provider setting, the tool
 * `factory_generate_image`, selection of chat images for issues, and the hand-over of selected images to a run.
 *
 * Ownership: images are always generated with the key of the grant's owner; there is no other path to a key. The key
 * is decrypted only for the provider call and never logged, returned or handed to an agent process.
 */
class ImageService(
    private val cfg: Config,
    providers: List<ImageProvider>,
    val store: ImageStore,
    private val users: UserStore,
    private val sessions: SessionStore,
    private val runs: RunStore,
    private val access: Access,
    private val box: SecretBox,
    private val workspaces: Workspaces,
    /** Appends an event to a session's transcript (AgentManager.publish). */
    private val publishEvent: (UUID, String, JsonElement) -> Unit,
    /** Time source of the key and login throttles (tests use a controllable clock). */
    clock: java.time.Clock = java.time.Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(ImageService::class.java)
    private val providers: Map<String, ImageProvider> = providers.associateBy { it.id }
    private val limits get() = cfg.imageLimits

    /** Publishes a run on the feed (Orchestrator.publish); set after construction, as the orchestrator depends on this. */
    @Volatile var publishRun: (UUID) -> Unit = {}

    /** The user's provider setting was saved or removed: cancels a pending device login (set to CodexLogins.cancel). */
    @Volatile var onSettingChanged: (UUID) -> Unit = {}

    /** Provider-rejected keys per user: 5 within 15 minutes block further `PUT /api/me/image-provider` calls. */
    private val rejectedKeys = AttemptLimiter(5, java.time.Duration.ofMinutes(15), clock)
    /** Device-login starts per user (CodexLogins). */
    val loginStarts = AttemptLimiter(5, java.time.Duration.ofMinutes(15), clock)

    companion object {
        /** The tool offered to chats, spec-writers and builders. */
        val TOOLS = setOf("factory_generate_image")
        /** Session kinds that may generate images (defence in depth behind the tool grant). */
        val KINDS = setOf("chat", "spec-writer", "builder")
        val FORMATS = setOf("png", "jpeg", "webp")
        val STORED_TYPES = setOf("image/png", "image/jpeg", "image/webp")
        const val MAX_PROMPT = 4000
        const val MAX_PURPOSE = 200
        const val MAX_NAME = 60
        const val MAX_PER_ISSUE = 20
        const val MAX_IMAGE_BYTES = 20L shl 20
        private val MODEL = Regex("^[A-Za-z0-9._:-]{1,100}$")
        private val SIZE = Regex("^(\\d{1,5})x(\\d{1,5})$")
        /** The worktree directory the server writes for the agents; ignored by git through its own `.gitignore`. */
        const val RUN_DIR = ".agern-run"

        fun slug(s: String): String =
            s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-').ifEmpty { "image" }

        /**
         * Creates `<worktree>/.agern-run/` with a `.gitignore` of `*`, so git ignores the directory, that file included.
         * Never follows a symlink: `.agern-run` must be absent or a real directory inside the worktree, else
         * [UnsafeDirectoryException] and nothing is written.
         */
        fun ensureRunDir(worktree: Path): Path {
            val dir = SafeFiles.plainDirectories(worktree, RUN_DIR)
            val ignore = dir.resolve(".gitignore")
            if (!Files.isRegularFile(ignore, LinkOption.NOFOLLOW_LINKS)) SafeFiles.write(ignore, "*\n".toByteArray())
            return dir
        }

        /** `<worktree>/.agern-run/images/`, created under the rules of [ensureRunDir]. Blocking. */
        fun runImagesDir(worktree: Path): Path {
            ensureRunDir(worktree)
            return SafeFiles.plainDirectories(worktree, RUN_DIR, "images")
        }

        /** The `<images>` block of the spec-writer's and builder's task prompts. */
        fun promptBlock(images: List<PreparedImage>): String = buildString {
            append("<images>\n")
            append("These images were prepared for this issue in the chat and are in your working directory. Use them for their purpose; ")
            append("do not generate replacements for the same purpose. `.agern-run/` is ignored by git: copy what you use to where the ")
            append("project keeps such assets.\n")
            images.forEachIndexed { i, p ->
                val m = p.meta
                append("${i + 1}. ${m.name} — purpose: ${m.purpose}; ${m.mediaType}, ${m.width}×${m.height}, ${Attachments.humanSize(m.size)}")
                if (m.transparent) append(", transparent background")
                append("\n   path: ${p.path}\n")
            }
            append("</images>\n")
        }

        const val UNSAFE_RUN_DIR_TOOL = "Images cannot be written: `.agern-run` in this working directory is not a plain directory."
        const val UNSAFE_RUN_DIR_RUN = "Prepared images could not be handed over: `.agern-run` in the repository is not a plain directory"

        private fun toolError(msg: String) = ApiException(HttpStatusCode.BadRequest, "image_tool", msg)
    }

    // ------------------------------------------------------------ catalogue and the per-user setting

    fun catalogue() = ImageConfigDto(
        providers.values.map {
            val reason = it.availability()
            ImageProviderInfoDto(
                it.id, it.label, it.models, it.defaultModel, it.supportsTransparency, it.keyPlaceholder, it.auth, reason == null, reason,
            )
        },
        ImageLimitsDto(limits.maxPerRequest, limits.maxPerTurn, limits.maxPerStage, limits.maxEdge),
    )

    /** Validates [apiKey] and [model] with the provider, then stores them encrypted for [user] (replacing any earlier setting). */
    suspend fun save(user: UserRecord, providerId: String, model: String, apiKey: String): UserRecord {
        val p = providers[providerId] ?: throw badRequest("Unknown image provider '$providerId'")
        if (p.auth == ImageProvider.DEVICE_LOGIN) {
            throw conflict("${p.label} is connected by signing in with ChatGPT, not with an API key.", "codex_login_required")
        }
        val m = model.trim()
        if (!MODEL.matches(m)) throw badRequest("The model name may only contain letters, digits, '.', '_', ':' and '-' (at most 100)")
        val key = apiKey.trim()
        if (key.isEmpty() || key.length > 4096 || key.any { it.isWhitespace() }) throw unprocessable("The API key looks malformed", "image_key_invalid")
        withContext(Dispatchers.IO) { p.availability() }?.let { throw unavailableHere(it) }
        // Testing stolen keys through us is throttled: provider-rejected keys count, per user (a success does not reset).
        rejectedKeys.blockedUntil(user.id)?.let { throw tooManyAttempts("Too many rejected API keys. Try again later.", it) }
        try {
            withContext(Dispatchers.IO) { p.validate(key, m) }
        } catch (e: ImageProviderException) {
            throw when (e.kind) {
                ImageProviderException.INVALID_CREDENTIAL -> {
                    rejectedKeys.record(user.id)
                    unprocessable("${p.label} rejected the API key. Check that it is complete and active.", "image_key_invalid")
                }
                ImageProviderException.UNKNOWN_MODEL -> unprocessable("${p.label} does not know the model '$m' for this key.", "image_model_unknown")
                else -> unavailable(p)
            }
        } catch (e: Exception) {
            throw unavailable(p)
        }
        users.setImageProvider(user.id, p.id, m, box.encrypt(key, user.username))
        onSettingChanged(user.id)
        log.info("Image provider of {} set to {} ({})", user.username, p.id, m)
        return users.byId(user.id)!!
    }

    /**
     * Stores a device-login secret (e.g. Codex's `auth.json`) encrypted for [userId], replacing any earlier setting.
     * Called by CodexLogins when the user finished signing in. Blocking.
     */
    fun storeLogin(userId: UUID, providerId: String, secret: String) {
        val p = providers[providerId] ?: error("unknown provider $providerId")
        val u = users.byId(userId) ?: return
        users.setImageProvider(u.id, p.id, p.defaultModel, box.encrypt(secret, u.username))
        log.info("Image provider of {} set to {} (device login)", u.username, p.id)
    }

    fun provider(id: String): ImageProvider? = providers[id]

    fun unavailableHere(reason: String) = conflict(reason, "image_provider_unavailable_here")

    private fun unavailable(p: ImageProvider) =
        ApiException(HttpStatusCode.BadGateway, "image_provider_unavailable", "Could not reach ${p.label} to check the key. Nothing was saved.")

    fun remove(user: UserRecord): UserRecord {
        onSettingChanged(user.id)
        users.setImageProvider(user.id, null, null, null)
        return users.byId(user.id)!!
    }

    // ------------------------------------------------------------ budgets (in memory, per agent session)

    private val used = ConcurrentHashMap<UUID, Int>()

    /** Per-user lock of device-login providers: one generation at a time per stored login. */
    private val loginLocks = ConcurrentHashMap<UUID, Mutex>()

    /** Reserves [n] images of [max] for [sessionId]; null on success, else how many are left. */
    private fun reserve(sessionId: UUID, n: Int, max: Int): Int? {
        synchronized(used) {
            val u = used[sessionId] ?: 0
            if (u + n > max) return maxOf(0, max - u)
            used[sessionId] = u + n
            return null
        }
    }

    private fun release(sessionId: UUID, n: Int) {
        if (n <= 0) return
        synchronized(used) { used.computeIfPresent(sessionId) { _, u -> (u - n).takeIf { it > 0 } } }
    }

    private fun usedBy(sessionId: UUID) = synchronized(used) { used[sessionId] ?: 0 }

    /** The owner's next chat message was accepted: the chat's per-message budget starts again. */
    fun newTurn(sessionId: UUID) {
        synchronized(used) { used.remove(sessionId) }
    }

    // ------------------------------------------------------------ the tool

    private class Args(
        val prompt: String, val purpose: String, val width: Int, val height: Int, val format: String, val count: Int,
        val transparent: Boolean, val issueNumber: Int?,
    )

    private fun parse(a: JsonObject): Args {
        fun prim(k: String): JsonPrimitive? = a[k] as? JsonPrimitive
        fun text(k: String): String? = prim(k)?.takeIf { it.isString }?.content
        val prompt = text("prompt")?.trim()
        if (prompt.isNullOrEmpty() || prompt.length > MAX_PROMPT) throw toolError("prompt is required: 1 to $MAX_PROMPT characters")
        val purpose = Screenshots.sanitizeCaption(text("purpose") ?: "")
        if (purpose.isEmpty() || purpose.codePointCount(0, purpose.length) > MAX_PURPOSE) {
            throw toolError("purpose is required: what the image is for, e.g. \"app icon\" (1 to $MAX_PURPOSE characters)")
        }
        val edge = "256 to ${limits.maxEdge} pixels"
        val size = a["size"]?.let { el -> (el as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase() ?: "" } ?: "1024x1024"
        val m = SIZE.matchEntire(size) ?: throw toolError("size must be WIDTHxHEIGHT, e.g. 1024x1024, each edge $edge")
        val w = m.groupValues[1].toInt()
        val h = m.groupValues[2].toInt()
        if (w !in 256..limits.maxEdge || h !in 256..limits.maxEdge) throw toolError("size must be WIDTHxHEIGHT with each edge $edge")
        val format = a["format"]?.let { el -> (el as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase() ?: "" } ?: "png"
        if (format !in FORMATS) throw toolError("format must be png, jpeg or webp")
        val count = a["count"]?.let { el ->
            (el as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }?.takeIf { it in 1..limits.maxPerRequest }
                ?: throw toolError("count must be a whole number from 1 to ${limits.maxPerRequest}")
        } ?: 1
        val transparent = a["transparentBackground"]?.let { el ->
            (el as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.trim()?.lowercase()?.toBooleanStrictOrNull() }
                ?: throw toolError("transparentBackground must be true or false")
        } ?: false
        val issue = a["issueNumber"]?.let { el ->
            (el as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.removePrefix("#")?.toIntOrNull() }?.takeIf { it >= 1 }
                ?: throw toolError("issueNumber must be an issue number")
        }
        return Args(prompt, purpose, w, h, format, count, transparent, issue)
    }

    /**
     * `factory_generate_image` for the agent session of [grant], as its [owner] (the grant's owner). Returns the tool's
     * JSON answer; every refusal is an [ApiException], which the MCP server turns into a tool error.
     */
    suspend fun generate(grant: McpGrant, owner: UserRecord, rawArgs: JsonObject): String {
        require(owner.id == grant.ownerId) { "owner must be the grant's owner" }
        // Who may call: derived from the grant's session, never from arguments.
        val session = withContext(Dispatchers.IO) { sessions.get(grant.sessionId) }
        if (session == null || session.kind !in KINDS || session.ownerId != owner.id || session.repo != grant.repo) {
            throw forbidden("Only the chat agent, the spec-writer and the builder can generate images")
        }
        val run: RunRecord? = grant.runId?.let { id ->
            val r = withContext(Dispatchers.IO) { runs.get(id) }
            if (r == null || r.repo != grant.repo || r.ownerId != owner.id || session.runId != r.id) {
                throw forbidden("Only the chat agent, the spec-writer and the builder can generate images")
            }
            r
        }
        if ((run == null) != (session.kind == "chat")) throw forbidden("Only the chat agent, the spec-writer and the builder can generate images")

        val args = parse(rawArgs)

        // The owner's provider, or the no-provider answer. Never anyone else's key.
        val provider = owner.imageProvider?.let { providers[it] }
        val model = owner.imageModel
        val enc = owner.imageKeyEnc
        if (provider == null || model == null || enc == null) {
            throw toolError(
                if (run == null) "No image provider is configured for ${owner.username}. Tell the user to add one under Settings → Image " +
                    "generation, then ask again. Do not draw a placeholder instead."
                else "No image provider is configured for the run's owner (${owner.username}). Continue without generated images: do " +
                    "not fail the task for this and do not draw placeholder art. Say in your final summary which images are missing and " +
                    "that the owner can add an image provider under Settings → Image generation.",
            )
        }
        if (args.transparent && args.format == "jpeg") throw toolError("transparentBackground needs format png or webp: JPEG has no transparency")
        if (args.transparent && !provider.supportsTransparency) {
            throw toolError("${provider.label} cannot generate a transparent background. Ask without transparentBackground (or generate on a plain background).")
        }
        // Where a run's images go must be a plain directory before anything is spent (a repository may plant a symlink).
        val worktree = run?.let { workspaces.runDir(owner.username, it.repo, it.id) }
        if (worktree != null) {
            try {
                withContext(Dispatchers.IO) { runImagesDir(worktree) }
            } catch (e: UnsafeDirectoryException) {
                throw toolError(UNSAFE_RUN_DIR_TOOL)
            }
        }

        val max = if (run == null) limits.maxPerTurn else limits.maxPerStage
        reserve(session.id, args.count, max)?.let { left ->
            throw toolError(
                if (run == null) "Limit reached: at most $max images per chat message ($left left). The user can ask again in a new message."
                else "Limit reached: at most $max images per run stage ($left left).",
            )
        }

        var delivered = 0
        var key = ""
        try {
            val started = System.currentTimeMillis()
            val request = ImageRequest(args.prompt, args.width, args.height, args.format, args.count, args.transparent)
            val raw = try {
                if (provider.auth == ImageProvider.DEVICE_LOGIN) {
                    // A login must not be used by two processes at once (it may be refreshed and rewritten): one call per
                    // user at a time, and the stored login is read only once the lock is held.
                    loginLocks.computeIfAbsent(owner.id) { Mutex() }.withLock {
                        val now = withContext(Dispatchers.IO) { users.byId(owner.id) }
                        val nowEnc = now?.imageKeyEnc
                        if (now?.imageProvider != provider.id || nowEnc == null) {
                            throw ImageProviderException(ImageProviderException.INVALID_CREDENTIAL)
                        }
                        key = box.decrypt(nowEnc, owner.username)
                        var current: ByteArray = nowEnc
                        withContext(Dispatchers.IO) {
                            provider.generate(key, now.imageModel ?: model, request) { renewed ->
                                val newEnc = box.encrypt(renewed, owner.username)
                                // Compare-and-set: a re-login or removal during the call wins.
                                if (users.replaceImageKey(owner.id, provider.id, current, newEnc)) current = newEnc
                            }
                        }
                    }
                } else {
                    key = box.decrypt(enc, owner.username)
                    withContext(Dispatchers.IO) { provider.generate(key, model, request) {} }
                }
            } catch (e: ImageProviderException) {
                log.info("Image generation for {} with {} ({}) failed: {}", owner.username, provider.id, model, e.kind)
                throw toolError(providerMessage(provider, e, key))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.info("Image generation for {} with {} ({}) failed: {}", owner.username, provider.id, model, e.javaClass.simpleName)
                throw toolError(providerMessage(provider, ImageProviderException(ImageProviderException.UNAVAILABLE), key))
            }
            // Only real PNG/JPEG/WebP images with readable dimensions are kept, at most what was asked for.
            val checked = raw.take(args.count).mapNotNull { b ->
                if (b.isEmpty() || b.size > MAX_IMAGE_BYTES) return@mapNotNull null
                val type = Sniff.mediaType(b).takeIf { it in STORED_TYPES } ?: return@mapNotNull null
                val dims = Sniff.dimensions(type, b) ?: return@mapNotNull null
                Triple(b, type, dims)
            }
            if (checked.isEmpty()) throw toolError(providerMessage(provider, ImageProviderException(ImageProviderException.NO_IMAGE), key))

            val requestId = UUID.randomUUID()
            val stored = withContext(Dispatchers.IO) {
                checked.map { (bytes, type, dims) ->
                    val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    val meta = store.insert(
                        NewImage(
                            grant.repo, owner.id, if (run == null) "chat" else "run", session.id, run?.id, requestId, provider.id, model,
                            args.prompt, args.purpose, type, bytes, sha, dims.first, dims.second, args.transparent,
                        ),
                    )
                    meta to try {
                        writeFile(meta, bytes, worktree)
                    } catch (e: UnsafeDirectoryException) {
                        store.deleteUnselected(meta.id, owner.id)
                        throw toolError(UNSAFE_RUN_DIR_TOOL)
                    }
                }
            }
            delivered = stored.size
            log.info("Generated {} image(s) for {} with {} ({}) in {} ms", delivered, owner.username, provider.id, model, System.currentTimeMillis() - started)

            publishEvent(session.id, "images", buildJsonObject {
                put("requestId", requestId.toString()); put("provider", provider.id); put("providerLabel", provider.label); put("model", model)
                put("prompt", args.prompt); put("purpose", args.purpose)
                if (args.issueNumber != null) put("issueNumber", args.issueNumber)
                putJsonArray("images") {
                    stored.forEach { (m, _) ->
                        addJsonObject { put("id", m.id.toString()); put("mediaType", m.mediaType); put("width", m.width); put("height", m.height); put("size", m.size) }
                    }
                }
            })
            run?.let { publishRun(it.id) }

            val notes = mutableListOf<String>()
            stored.forEachIndexed { i, (m, _) ->
                if (m.width != args.width || m.height != args.height) {
                    notes += "Image ${i + 1} is ${m.width}×${m.height}, not the requested ${args.width}×${args.height}: resize it with the project's tools if needed."
                }
                val asked = if (args.format == "jpeg") "image/jpeg" else "image/${args.format}"
                if (m.mediaType != asked) notes += "Image ${i + 1} is ${m.mediaType}, not $asked: convert it with the project's tools if needed."
            }
            if (checked.size < args.count) notes += "${provider.label} delivered ${checked.size} usable image(s) of ${args.count} requested."
            return buildJsonObject {
                putJsonArray("images") {
                    stored.forEach { (m, path) ->
                        addJsonObject {
                            put("id", m.id.toString()); put("path", path); put("mediaType", m.mediaType)
                            put("width", m.width); put("height", m.height); put("bytes", m.size)
                        }
                    }
                }
                put("provider", provider.id); put("model", model)
                putJsonArray("notes") { notes.forEach { add(JsonPrimitive(it)) } }
                put("remaining", maxOf(0, max - (usedBy(session.id) - (args.count - delivered))))
                put(
                    "next",
                    if (run == null) "The user sees the images in the chat and chooses there which to use for an issue. Do not copy them " +
                        "into the repository unless asked."
                    else "Copy the images you use into the repository where the project keeps such assets, in the formats and sizes it " +
                        "needs. `.agern-run/` is ignored by git and must stay uncommitted.",
                )
            }.toString()
        } finally {
            release(session.id, args.count - delivered)
        }
    }

    /** The plain-word tool error for a provider failure; the key is redacted from anything the provider said. */
    private fun providerMessage(p: ImageProvider, e: ImageProviderException, key: String): String {
        val l = p.label
        return when (e.kind) {
            ImageProviderException.INVALID_CREDENTIAL ->
                if (p.auth == ImageProvider.DEVICE_LOGIN) "Your sign-in to $l is no longer valid. Connect it again under Settings → Image generation."
                else "$l rejected your API key. Update it under Settings → Image generation."
            ImageProviderException.QUOTA -> "Your $l account has no quota or credit left for image generation."
            ImageProviderException.RATE_LIMITED -> "$l is rate-limiting your account right now. Try again in a little while."
            ImageProviderException.CONTENT_REFUSED -> "$l refused to generate this image because of its content rules. Change the prompt."
            ImageProviderException.INVALID_REQUEST -> {
                val said = redact(e.detail ?: "", key).replace(Regex("\\s+"), " ").trim().take(300).ifEmpty { "no reason given" }
                "$l did not accept the request: $said"
            }
            ImageProviderException.TIMEOUT -> "$l did not answer within ${e.detail?.toIntOrNull() ?: 300} seconds."
            ImageProviderException.NO_IMAGE -> "$l answered without a usable image."
            else -> "$l could not be reached or failed. Try again later."
        }
    }

    private fun redact(text: String, key: String) = if (key.isEmpty()) text else text.replace(key, "***")

    // ------------------------------------------------------------ files for the agents

    fun chatDir(sessionId: UUID): Path = cfg.dataDir.resolve("generated-images").resolve(sessionId.toString())

    private fun fileName(m: ImageMeta) = "${m.id.toString().take(8)}-${slug(m.purpose)}.${Screenshots.extension(m.mediaType)}"

    /** Writes the agent's copy and returns the tool's `path`: relative to the worktree in a run, absolute in a chat. Blocking. */
    private fun writeFile(m: ImageMeta, bytes: ByteArray, worktree: Path?): String {
        return if (worktree != null) {
            SafeFiles.write(runImagesDir(worktree).resolve(fileName(m)), bytes)
            "$RUN_DIR/images/${fileName(m)}"
        } else {
            val parent = cfg.dataDir.resolve("generated-images")
            Files.createDirectories(parent)
            val dir = SafeFiles.plainDirectories(parent, m.sessionId.toString())
            val f = dir.resolve(fileName(m))
            SafeFiles.write(f, bytes)
            f.toAbsolutePath().toString()
        }
    }

    // ------------------------------------------------------------ viewing and selection

    /** The image if [user] may see its repository; 404 otherwise (unknown ids alike). */
    suspend fun visible(user: UserRecord, id: UUID): ImageMeta {
        val m = withContext(Dispatchers.IO) { store.get(id) } ?: throw notFound("Image not found")
        // The image's session records the member repositories of a workspace it saw (docs/ARCHITECTURE.md §4).
        val also = withContext(Dispatchers.IO) { sessions.get(m.sessionId)?.contextRepos.orEmpty() }
        if (!withContext(Dispatchers.IO) { access.canSee(user, m.repo, also) }) throw notFound("Image not found")
        return m
    }

    private fun requireOwnChatImage(user: UserRecord, m: ImageMeta) {
        if (m.ownerId != user.id) throw forbidden("Only ${m.ownerUsername}, who generated this image, can change it")
        if (m.origin != "chat") throw conflict("Images generated in a run cannot be selected or discarded", "run_image")
    }

    suspend fun select(user: UserRecord, id: UUID, issueNumber: Int, rawName: String): GeneratedImageDto {
        val m = visible(user, id)
        requireOwnChatImage(user, m)
        val name = Screenshots.sanitizeCaption(rawName)
        if (name.isEmpty() || name.codePointCount(0, name.length) > MAX_NAME) throw badRequest("name is required: 1 to $MAX_NAME characters")
        if (issueNumber < 1) throw notFound("Issue #$issueNumber not found")
        val client = access.requireClient(user)
        val issue = withContext(Dispatchers.IO) {
            try {
                client.getIssue(access.org, m.repo, issueNumber)
            } catch (e: dk.egernet.factory.github.GitHubException) {
                if (e.status == 404) null else throw ApiException(HttpStatusCode.BadGateway, "github_error", e.message ?: "GitHub error")
            }
        } ?: throw notFound("Issue #$issueNumber not found")
        if (issue.state != "open") throw conflict("Issue #$issueNumber is closed", "issue_closed")
        return when (val r = withContext(Dispatchers.IO) { store.select(id, user.id, m.repo, issueNumber, name, MAX_PER_ISSUE) }) {
            is SelectResult.Ok -> r.meta.toDto()
            is SelectResult.TooMany -> throw conflict("Issue #$issueNumber already has ${r.max} selected images", "too_many_images")
            SelectResult.Refused -> throw refusedChange(id)
        }
    }

    suspend fun unselect(user: UserRecord, id: UUID): GeneratedImageDto {
        val m = visible(user, id)
        requireOwnChatImage(user, m)
        if (!withContext(Dispatchers.IO) { store.unselect(id, user.id) }) throw refusedChange(id)
        return withContext(Dispatchers.IO) { store.get(id) }?.toDto() ?: throw notFound("Image not found")
    }

    suspend fun discard(user: UserRecord, id: UUID) {
        val m = visible(user, id)
        requireOwnChatImage(user, m)
        if (!withContext(Dispatchers.IO) { store.deleteUnselected(id, user.id) }) {
            val now = withContext(Dispatchers.IO) { store.get(id) } ?: throw notFound("Image not found")
            if (now.selected) throw conflict("The image is selected for #${now.issueNumber}; remove it from the issue first", "image_selected")
            throw notFound("Image not found")
        }
        withContext(Dispatchers.IO) { runCatching { Files.deleteIfExists(chatDir(m.sessionId).resolve(fileName(m))) } }
    }

    /** Why a conditional selection change did not apply, read after the fact. */
    private fun refusedChange(id: UUID): ApiException {
        val now = store.get(id) ?: return notFound("Image not found")
        if (now.locked) return conflict("A run is using this image (run ${now.usedByRunId}); it can no longer be changed", "image_in_use")
        return conflict("The image changed meanwhile; reload and try again")
    }

    suspend fun forSession(sessionId: UUID): List<GeneratedImageDto> = withContext(Dispatchers.IO) { store.forSession(sessionId).map { it.toDto() } }
    suspend fun forIssue(repo: String, issue: Int): List<GeneratedImageDto> = withContext(Dispatchers.IO) { store.forIssue(repo, issue).map { it.toDto() } }

    // ------------------------------------------------------------ runs and cleanup

    /**
     * When [run] starts: takes its issue's selected images and writes them to `<worktree>/.agern-run/images/`. Nothing
     * (no directory) when the issue has none. Blocking.
     */
    fun handOver(run: RunRecord, worktree: Path): List<PreparedImage> {
        val claimed = store.claimForRun(run.repo, run.issueNumber, run.id)
        if (claimed.isEmpty()) return emptyList()
        // The selection is claimed already; a failed run unlocks it again.
        val dir = try {
            runImagesDir(worktree)
        } catch (e: UnsafeDirectoryException) {
            throw IllegalStateException(UNSAFE_RUN_DIR_RUN)
        }
        return claimed.mapIndexedNotNull { i, m ->
            val bytes = store.data(m.id) ?: return@mapIndexedNotNull null
            val name = "prepared-%02d-%s.%s".format(i + 1, slug(m.name ?: m.purpose), Screenshots.extension(m.mediaType))
            try {
                SafeFiles.write(dir.resolve(name), bytes)
            } catch (e: UnsafeDirectoryException) {
                throw IllegalStateException(UNSAFE_RUN_DIR_RUN)
            }
            PreparedImage(m, "$RUN_DIR/images/$name")
        }
    }

    /** A chat was closed: its unselected images and its directory go; selected images stay with their issue. Blocking. */
    fun removeSession(sessionId: UUID) {
        newTurn(sessionId)
        store.deleteUnselectedOfSession(sessionId)
        runCatching { SafeFiles.deleteTree(chatDir(sessionId)) }
    }
}
