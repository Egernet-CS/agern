package dk.egernet.factory.files

import dk.egernet.factory.AdminProjectFilesDto
import dk.egernet.factory.AdminProjectFilesRepoDto
import dk.egernet.factory.ApiException
import dk.egernet.factory.Config
import dk.egernet.factory.OwnerDto
import dk.egernet.factory.ProjectFileDto
import dk.egernet.factory.ProjectFileUploadDto
import dk.egernet.factory.ProjectFilesDto
import dk.egernet.factory.ProjectFilesLimitsDto
import dk.egernet.factory.ProjectFilesUsageDto
import dk.egernet.factory.RunProjectFileDto
import dk.egernet.factory.agent.requireSafeSegment
import dk.egernet.factory.badRequest
import dk.egernet.factory.conflict
import dk.egernet.factory.db.ProjectFileRecord
import dk.egernet.factory.db.ProjectFileStore
import dk.egernet.factory.db.ProjectFileUploadRecord
import dk.egernet.factory.db.TwoFactorStore
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.db.UserStore
import dk.egernet.factory.forbidden
import dk.egernet.factory.github.Access
import dk.egernet.factory.db.iso
import dk.egernet.factory.notFound
import dk.egernet.factory.unprocessable
import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The project file store (docs/ARCHITECTURE.md §5 "Project files"): files a project needs but must not keep in git,
 * uploaded once per repository and handed read-only to every chat and run of it. Metadata lives in PostgreSQL
 * ([ProjectFileStore]); the bytes live on disk under `$FACTORY_DATA_DIR/project-files/<repo>/`:
 *
 * - `blobs/<sha256>`: one regular file per distinct content, mode 0444;
 * - `current/<name>`: hard links to the blobs, the live view chats get;
 * - `runs/<runId>/<name>`: hard links, a run's snapshot;
 * - `uploads/<uploadId>.part`: uploads in flight.
 *
 * View directories carry no write bits except while the server changes them. The server never executes, unpacks or
 * sniffs a file, and no method holds a whole file in memory: every copy goes through a buffer of [BUFFER_BYTES].
 * Disk paths derive only from validated repository names, validated file names, UUIDs and hex hashes, and every
 * resolved path is checked to stay inside its repository's directory without following symlinks.
 *
 * Read-only on the host is advisory (agents run as the server's OS user); only the sandbox's bind mount is enforced.
 */
class ProjectFiles(
    private val cfg: Config,
    val store: ProjectFileStore,
    private val users: UserStore,
    private val access: Access,
    private val audit: TwoFactorStore,
    private val clock: Clock = Clock.systemUTC(),
    /** The usable bytes of the file system holding [dir]; injectable for tests. */
    private val freeSpace: (Path) -> Long = { dir -> Files.getFileStore(dir).usableSpace },
) {
    private val log = LoggerFactory.getLogger(ProjectFiles::class.java)

    /** `$FACTORY_DATA_DIR/project-files`, mode 0700. */
    val root: Path = cfg.dataDir.resolve("project-files").toAbsolutePath().normalize()

    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    /** Uploads with a chunk being written or a completion in progress: one at a time per upload. */
    private val busy: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    val limits: ProjectFilesLimitsDto
        get() = ProjectFilesLimitsDto(cfg.projectFilesMaxFileBytes, cfg.projectFilesMaxProjectBytes, cfg.projectFilesMaxTotalBytes, MAX_FILES)

    private fun <T> locked(repo: String, block: () -> T): T = locks.computeIfAbsent(repo) { ReentrantLock() }.withLock(block)

    // ------------------------------------------------------------------ paths

    private fun ensureRoot(): Path {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(root.parent)
            try {
                Files.createDirectory(root)
            } catch (_: FileAlreadyExistsException) {
            }
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw IOException("$root is not a plain directory")
        setMode(root, "rwx------")
        return root
    }

    /** The repository's directory (validated name), created when [create]. */
    private fun repoDir(repo: String, create: Boolean = true): Path {
        requireSafeSegment(repo, "repository name")
        val dir = root.resolve(repo)
        if (create) {
            ensureRoot()
            plainDir(dir)
        }
        return dir
    }

    /** [sub] below the repository's directory, each a plain directory (created when missing; write bits as found). */
    private fun sub(repo: String, vararg sub: String): Path {
        var dir = repoDir(repo)
        for (s in sub) {
            dir = dir.resolve(s)
            plainDir(dir)
        }
        return dir
    }

    private fun plainDir(dir: Path) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(dir)
            } catch (_: FileAlreadyExistsException) {
            }
        }
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw IOException("$dir is not a plain directory")
    }

    /** [rel] (a validated name) below [base]; throws when it would leave it. */
    private fun inside(base: Path, rel: String): Path {
        val p = base.resolve(rel).normalize()
        if (!p.startsWith(base) || p == base) throw IOException("$rel leaves $base")
        return p
    }

    private fun blobPath(repo: String, sha256: String): Path {
        require(SHA256_RE.matches(sha256)) { "Invalid hash" }
        return repoDir(repo, create = false).resolve(BLOBS).resolve(sha256)
    }

    /** Where the bytes of content [sha256] of [repo] are (it may not exist). */
    fun blobFile(repo: String, sha256: String): Path = blobPath(repo, sha256)

    private fun partPath(repo: String, id: UUID): Path = repoDir(repo, create = false).resolve(UPLOADS).resolve("$id.part")

    /**
     * The live view of [repo]'s files, what a chat gets as `AGERN_PROJECT_FILES` (created empty when missing). Blocking.
     */
    fun currentDir(repo: String): Path = locked(repo) {
        val dir = sub(repo, CURRENT)
        setMode(dir, "r-xr-xr-x")
        dir
    }

    /** Where run [runId]'s snapshot of [repo] lives (it may not exist). */
    fun snapshotDir(repo: String, runId: UUID): Path = repoDir(repo, create = false).resolve(RUNS).resolve(runId.toString())

    // ------------------------------------------------------------------ permissions

    /** Visible to [user] (404 otherwise, exactly like an unknown project). Blocking. */
    fun requireVisible(user: UserRecord, repo: String) {
        access.requireVisible(user, repo)
    }

    fun canManage(user: UserRecord, repo: String): Boolean = user.isAdmin || store.isManager(repo, user.id)

    /** Visible first (404), then an admin or on the managers list (403). Blocking. */
    fun requireManager(user: UserRecord, repo: String) {
        requireVisible(user, repo)
        if (!canManage(user, repo)) throw forbidden("Only admins and this project's file managers can change its files")
    }

    // ------------------------------------------------------------------ reading

    /** `GET /api/projects/{repo}/files`. Blocking. */
    fun list(user: UserRecord, repo: String): ProjectFilesDto {
        requireVisible(user, repo)
        val manage = canManage(user, repo)
        val files = store.files(repo)
        return ProjectFilesDto(
            repo, manage, files.map { it.toDto() },
            store.managers(repo).map { OwnerDto(it.username, it.displayName) },
            ProjectFilesUsageDto(files.size, files.sumOf { it.size }, store.totalBytes()),
            limits,
            if (manage) store.uploads(repo).map { u -> uploadDto(u, files) } else emptyList(),
        )
    }

    /** The current files of [repo], for runs, chats and the MCP tool. Blocking. */
    fun files(repo: String): List<ProjectFileRecord> = store.files(repo)

    fun ProjectFileRecord.toDto() = ProjectFileDto(
        id.toString(), name, size, sha256, version, note, downloadable,
        uploadedByUsername?.let { OwnerDto(it, uploadedByDisplayName ?: it) }, uploadedAt.iso(),
    )

    private fun uploadDto(u: ProjectFileUploadRecord, files: List<ProjectFileRecord>? = null): ProjectFileUploadDto {
        val part = runCatching { partPath(u.repo, u.id) }.getOrNull()
        val received = part?.let { runCatching { Files.size(it) }.getOrDefault(0L) } ?: 0L
        val replaces = (files ?: store.files(u.repo)).firstOrNull { it.name.equals(u.name, ignoreCase = true) }?.id?.toString()
        return ProjectFileUploadDto(
            u.id.toString(), u.name, u.size, received, u.sha256, u.note,
            u.createdByUsername?.let { OwnerDto(it, u.createdByDisplayName ?: it) }, u.createdAt.iso(), u.touchedAt.iso(),
            u.touchedAt.plus(UPLOAD_TTL).iso(), replaces,
        )
    }

    // ------------------------------------------------------------------ uploads

    /**
     * `POST /api/projects/{repo}/file-uploads`: validates the name, the limits and the free space, and opens an upload.
     * Answers the upload and true when it is new; an open upload of the same name, size, declared hash and user is
     * returned as it is (false), so the client resumes at its `received`. Blocking.
     */
    fun createUpload(user: UserRecord, repo: String, name: String, size: Long, sha256: String?, note: String?): Pair<ProjectFileUploadDto, Boolean> {
        requireManager(user, repo)
        validateName(name)?.let { throw invalidName(it) }
        val declared = sha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (declared != null && !SHA256_RE.matches(declared)) throw badRequest("sha256 must be 64 hexadecimal digits")
        val cleanNote = cleanNote(note)
        if (size <= 0) throw emptyFile()
        if (size > cfg.projectFilesMaxFileBytes) throw fileTooLarge(size)
        expireUploads()
        return locked(repo) {
            val open = store.uploadByName(repo, name)
            if (open != null) {
                if (open.name == name && open.size == size && open.sha256 == declared && open.createdBy == user.id && partExists(open)) {
                    return@locked uploadDto(open) to false
                }
                discardLocked(open)
            }
            val files = store.files(repo)
            val replacing = files.firstOrNull { it.name.equals(name, ignoreCase = true) }
            nameConflict(name, files.map { it.name } + store.uploads(repo).map { it.name })?.let { throw it }
            if (replacing == null && files.size >= MAX_FILES) {
                throw conflict("A project can have at most $MAX_FILES files; this one has ${files.size}. Delete one first.", "too_many_files")
            }
            checkQuotas(repo, size, replacing?.size ?: 0, files.sumOf { it.size }, exceptUpload = null)
            ensureRoot()
            val free = freeSpace(root)
            if (free < size + SPACE_RESERVE) {
                throw ApiException(
                    HttpStatusCode.InsufficientStorage, "insufficient_storage",
                    "Not enough free disk space on the server for $size bytes: $free bytes are free and ${SPACE_RESERVE} bytes are kept in reserve.",
                )
            }
            val id = UUID.randomUUID()
            val dir = sub(repo, UPLOADS)
            Files.newOutputStream(dir.resolve("$id.part"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).close()
            try {
                store.createUpload(id, repo, name, size, declared, cleanNote, user.id)
            } catch (e: Exception) {
                Files.deleteIfExists(dir.resolve("$id.part"))
                throw e
            }
            uploadDto(store.upload(repo, id)!!, files) to true
        }
    }

    private fun partExists(u: ProjectFileUploadRecord) = Files.isRegularFile(partPath(u.repo, u.id), LinkOption.NOFOLLOW_LINKS)

    /** `GET /api/projects/{repo}/file-uploads/{id}`. Blocking. */
    fun upload(user: UserRecord, repo: String, id: UUID): ProjectFileUploadDto {
        requireManager(user, repo)
        return uploadDto(store.upload(repo, id) ?: throw uploadNotFound())
    }

    /**
     * `PUT …/file-uploads/{id}?offset=<n>`: appends [input] at [offset], which must be the bytes received so far. Never
     * more than the declared size stays on disk (413 when the body is longer); when the body breaks off, what was
     * written stays and counts. Streams through a fixed buffer. Blocking.
     */
    fun append(user: UserRecord, repo: String, id: UUID, offset: Long, input: InputStream): ProjectFileUploadDto {
        requireManager(user, repo)
        val u = store.upload(repo, id) ?: throw uploadNotFound()
        if (!busy.add(id)) throw uploadBusy()
        try {
            val part = partPath(repo, id)
            if (!Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS)) throw uploadNotFound()
            val received = Files.size(part)
            if (offset != received) {
                throw conflict("This upload has received $received bytes; send the next chunk at offset $received (not $offset).", "upload_offset_mismatch")
            }
            val remaining = u.size - received
            var tooLong = false
            Files.newOutputStream(part, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS).use { out ->
                val buf = ByteArray(BUFFER_BYTES)
                var written = 0L
                try {
                    while (true) {
                        val n = input.read(buf, 0, buf.size)
                        if (n < 0) break
                        val take = minOf(n.toLong(), remaining - written).toInt()
                        if (take > 0) {
                            out.write(buf, 0, take)
                            written += take
                        }
                        if (take < n) {
                            tooLong = true
                            break
                        }
                    }
                } finally {
                    out.flush()
                    runCatching { store.touchUpload(id) }
                }
            }
            if (tooLong) throw fileTooLarge(u.size, declared = true)
            return uploadDto(store.upload(repo, id) ?: throw uploadNotFound())
        } finally {
            busy.remove(id)
        }
    }

    /** `DELETE …/file-uploads/{id}`: discards the upload and its bytes. Blocking. */
    fun discard(user: UserRecord, repo: String, id: UUID) {
        requireManager(user, repo)
        locked(repo) { discardLocked(store.upload(repo, id) ?: throw uploadNotFound()) }
    }

    private fun discardLocked(u: ProjectFileUploadRecord) {
        store.deleteUpload(u.id)
        runCatching { Files.deleteIfExists(partPath(u.repo, u.id)) }
    }

    /** Deletes uploads 24 hours after their last write (at server start and whenever an upload is created). Blocking. */
    fun expireUploads(): Int {
        val cutoff = clock.instant().minus(UPLOAD_TTL)
        var n = 0
        for (u in store.allUploads().filter { it.touchedAt.isBefore(cutoff) }) {
            locked(u.repo) {
                val fresh = store.upload(u.repo, u.id) ?: return@locked
                if (!fresh.touchedAt.isBefore(cutoff) || u.id in busy) return@locked
                discardLocked(fresh)
                n++
            }
        }
        // Part files without a row (a crash between the two): removed as well.
        runCatching {
            if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                Files.list(root).use { repos -> repos.toList() }.forEach { dir ->
                    val up = dir.resolve(UPLOADS)
                    if (!Files.isDirectory(up, LinkOption.NOFOLLOW_LINKS)) return@forEach
                    val repo = dir.fileName.toString()
                    Files.list(up).use { s -> s.toList() }.forEach { f ->
                        val id = runCatching { UUID.fromString(f.fileName.toString().removeSuffix(".part")) }.getOrNull()
                        if (id != null && store.upload(repo, id) == null && id !in busy) locked(repo) {
                            if (store.upload(repo, id) == null) Files.deleteIfExists(f)
                        }
                    }
                }
            }
        }
        if (n > 0) log.info("Removed {} expired project file upload(s)", n)
        return n
    }

    /**
     * `POST …/file-uploads/{id}/complete`: computes the SHA-256 in one streamed pass, checks a declared hash and the
     * limits again, then (under the repository's lock) moves the bytes into `blobs/`, writes the row and only then
     * updates `current/` and the audit log. A failed database write leaves the on-disk view as it was. Blocking.
     */
    fun complete(user: UserRecord, repo: String, id: UUID, remote: String?): ProjectFileDto {
        requireManager(user, repo)
        val u = store.upload(repo, id) ?: throw uploadNotFound()
        if (!busy.add(id)) throw uploadBusy()
        try {
            val part = partPath(repo, id)
            if (!Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS)) throw uploadNotFound()
            val received = Files.size(part)
            if (received != u.size) {
                throw conflict("The upload has $received of ${u.size} bytes; send the rest before completing it.", "upload_incomplete")
            }
            val sha = sha256Of(part)
            if (u.sha256 != null && u.sha256 != sha) {
                locked(repo) { discardLocked(u) }
                throw unprocessable(
                    "The uploaded bytes have SHA-256 $sha, but ${u.sha256} was expected. The upload was discarded; upload the file again.",
                    "checksum_mismatch",
                )
            }
            return locked(repo) {
                store.upload(repo, id) ?: throw uploadNotFound()
                val files = store.files(repo)
                val replacing = files.firstOrNull { it.name.equals(u.name, ignoreCase = true) }
                nameConflict(u.name, files.map { it.name })?.let { throw it }
                if (replacing == null && files.size >= MAX_FILES) {
                    throw conflict("A project can have at most $MAX_FILES files; this one has ${files.size}. Delete one first.", "too_many_files")
                }
                checkQuotas(repo, u.size, replacing?.size ?: 0, files.sumOf { it.size }, exceptUpload = id)
                val blob = sub(repo, BLOBS).resolve(sha)
                val newBlob = !Files.exists(blob, LinkOption.NOFOLLOW_LINKS)
                if (newBlob) {
                    moveAtomic(part, blob)
                    setMode(blob, "r--r--r--")
                }
                val name = replacing?.name ?: u.name
                val fileId = try {
                    store.publish(repo, name, u.size, sha, u.note, user.id, id, replacing)
                } catch (e: Exception) {
                    // Nothing published: the view stays as it was; the bytes go back to the upload.
                    if (newBlob && store.countBySha(repo, sha) == 0) runCatching { moveAtomic(blob, part) }
                    throw e
                }
                Files.deleteIfExists(part)
                linkCurrent(repo, name, blob)
                if (replacing != null && replacing.sha256 != sha) collectBlob(repo, replacing.sha256)
                val f = store.file(repo, fileId)!!
                if (replacing == null) {
                    audit(user, remote, "project_file_uploaded", "$repo/${f.name} v${f.version} sha256:${f.sha256} ${f.size} bytes")
                } else {
                    audit(
                        user, remote, "project_file_replaced",
                        "$repo/${f.name} v${f.version} sha256:${replacing.sha256} -> sha256:${f.sha256} ${f.size} bytes",
                    )
                }
                log.info("Project file {}/{} v{} ({} bytes) published by {}", repo, f.name, f.version, f.size, user.username)
                f.toDto()
            }
        } finally {
            busy.remove(id)
        }
    }

    private fun checkQuotas(repo: String, size: Long, replacedSize: Long, projectBytes: Long, exceptUpload: UUID?) {
        val projectUsed = projectBytes - replacedSize + store.openUploadBytes(repo, exceptUpload ?: NO_UPLOAD)
        if (projectUsed + size > cfg.projectFilesMaxProjectBytes) {
            throw ApiException(
                HttpStatusCode.PayloadTooLarge, "project_quota_exceeded",
                "The project's files may use at most ${cfg.projectFilesMaxProjectBytes} bytes (FACTORY_PROJECT_FILES_MAX_PROJECT_MB); " +
                    "$projectUsed bytes are in use or being uploaded, and this file has $size bytes.",
            )
        }
        val totalUsed = store.totalBytes() - replacedSize + store.openUploadBytes(null, exceptUpload ?: NO_UPLOAD)
        if (totalUsed + size > cfg.projectFilesMaxTotalBytes) {
            throw ApiException(
                HttpStatusCode.PayloadTooLarge, "total_quota_exceeded",
                "All projects' files together may use at most ${cfg.projectFilesMaxTotalBytes} bytes on this server " +
                    "(FACTORY_PROJECT_FILES_MAX_TOTAL_MB); $totalUsed bytes are in use or being uploaded, and this file has $size bytes.",
            )
        }
    }

    // ------------------------------------------------------------------ changing and deleting

    /** `PUT /api/projects/{repo}/files/{id}`: the note and/or the download switch. Blocking. */
    fun update(user: UserRecord, repo: String, id: UUID, note: String?, downloadable: Boolean?, remote: String?): ProjectFileDto {
        requireManager(user, repo)
        val clean = note?.let { cleanNote(it) }
        return locked(repo) {
            val before = store.file(repo, id) ?: throw fileNotFound()
            store.update(repo, id, clean, clearNote = note != null && clean == null, downloadable = downloadable)
            val after = store.file(repo, id)!!
            val changes = listOfNotNull(
                if (note != null && before.note != after.note) "note" else null,
                if (downloadable != null && before.downloadable != after.downloadable) "download ${if (after.downloadable) "on" else "off"}" else null,
            )
            if (changes.isNotEmpty()) {
                audit(user, remote, "project_file_updated", "$repo/${after.name} v${after.version} sha256:${after.sha256}: ${changes.joinToString(", ")}")
            }
            after.toDto()
        }
    }

    /** `DELETE /api/projects/{repo}/files/{id}`: the row, its `current` link and, when unreferenced, the blob. Blocking. */
    fun delete(user: UserRecord, repo: String, id: UUID, remote: String?) {
        requireManager(user, repo)
        locked(repo) {
            val f = store.file(repo, id) ?: throw fileNotFound()
            store.delete(repo, id)
            unlinkCurrent(repo, f.name)
            collectBlob(repo, f.sha256)
            audit(user, remote, "project_file_deleted", "$repo/${f.name} v${f.version} sha256:${f.sha256} ${f.size} bytes")
        }
    }

    /**
     * `GET …/files/{id}/content`: the file's metadata and an open stream of its bytes, when the caller can see the
     * repository and the file is downloadable (403 `download_disabled` otherwise). The stream stays valid even if the file
     * is replaced meanwhile. Writes the audit row. Blocking.
     */
    fun openForDownload(user: UserRecord, repo: String, id: UUID, remote: String?): Pair<ProjectFileRecord, InputStream> {
        requireVisible(user, repo)
        val f = store.file(repo, id) ?: throw fileNotFound()
        if (!f.downloadable) throw ApiException(HttpStatusCode.Forbidden, "download_disabled", "Downloading this file is switched off.")
        val input = Files.newInputStream(blobPath(repo, f.sha256), LinkOption.NOFOLLOW_LINKS)
        audit(user, remote, "project_file_downloaded", "$repo/${f.name} v${f.version} sha256:${f.sha256} ${f.size} bytes")
        return f to input
    }

    /** `PUT /api/projects/{repo}/file-managers`: admins only; unknown usernames are 400. Blocking. */
    fun setManagers(user: UserRecord, repo: String, usernames: List<String>, remote: String?): ProjectFilesDto {
        requireVisible(user, repo)
        if (!user.isAdmin) throw forbidden("Only admins can choose who manages a project's files")
        val names = usernames.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (names.size > 100) throw badRequest("At most 100 file managers")
        val found = names.map { n -> n to users.byUsername(n) }
        val unknown = found.filter { it.second == null }.map { it.first }
        if (unknown.isNotEmpty()) throw badRequest("Unknown user(s): ${unknown.joinToString(", ")}")
        store.setManagers(repo, found.map { it.second!!.id }, user.id)
        audit(user, remote, "project_file_managers_changed", "$repo: ${found.joinToString(", ") { it.second!!.username }.ifEmpty { "(none)" }}")
        return list(user, repo)
    }

    // ------------------------------------------------------------------ admin

    /** `GET /api/admin/project-files`: counts and bytes per repository, never names. Blocking. */
    fun overview(): AdminProjectFilesDto =
        AdminProjectFilesDto(store.totalBytes(), limits, store.perRepo().map { (repo, n, bytes) -> AdminProjectFilesRepoDto(repo, n, bytes) })

    /**
     * `DELETE /api/admin/project-files/{repo}`: every file, open upload and the `current` view of [repo] — also of a
     * repository the admin cannot see or that no longer exists. Run snapshots keep their own links. Blocking.
     */
    fun purge(admin: UserRecord, repo: String, remote: String?) {
        if (!admin.isAdmin) throw forbidden("Admins only")
        requireSafeSegment(repo, "repository name")
        locked(repo) {
            val (n, bytes) = store.purge(repo)
            val dir = repoDir(repo, create = false)
            for (d in listOf(CURRENT, BLOBS, UPLOADS)) {
                val p = dir.resolve(d)
                if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) deleteTree(p)
            }
            audit(admin, remote, "project_files_purged", "$repo: $n file(s), $bytes bytes")
            log.info("Admin {} deleted all {} project file(s) of {} ({} bytes)", admin.username, n, repo, bytes)
        }
    }

    // ------------------------------------------------------------------ runs

    /**
     * Creates run [runId]'s snapshot of [repo]: `runs/<runId>/` with hard links to the current files (empty when the
     * project has none), replacing an earlier one. Later replacements or deletions do not change it. Blocking.
     */
    fun snapshot(runId: UUID, repo: String): List<RunProjectFileDto> = locked(repo) {
        val runs = sub(repo, RUNS)
        val dir = runs.resolve(runId.toString())
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) deleteTree(dir)
        Files.createDirectory(dir)
        val files = store.files(repo)
        try {
            for (f in files) {
                val target = inside(dir, f.name)
                makeParents(dir, target)
                Files.createLink(target, blobPath(repo, f.sha256))
            }
        } finally {
            freeze(dir)
        }
        files.map { RunProjectFileDto(it.name, it.size, it.sha256, it.version) }
    }

    /** True when run [runId]'s snapshot directory exists. */
    fun hasSnapshot(repo: String, runId: UUID): Boolean = Files.isDirectory(snapshotDir(repo, runId), LinkOption.NOFOLLOW_LINKS)

    /** Removes run [runId]'s snapshot of [repo] (nothing when there is none). Blocking; never throws. */
    fun removeSnapshot(repo: String, runId: UUID) {
        runCatching {
            locked(repo) {
                val dir = snapshotDir(repo, runId)
                if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) deleteTree(dir)
            }
        }.onFailure { log.warn("Removing the project files snapshot of run {} failed: {}", runId, it.message) }
    }

    /** At server start: removes the snapshots of runs that are not [live]. Returns the run ids removed. Blocking. */
    fun reapSnapshots(live: (UUID) -> Boolean): List<UUID> {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val removed = mutableListOf<UUID>()
        Files.list(root).use { s -> s.toList() }.forEach { dir ->
            val repo = dir.fileName.toString()
            val runs = dir.resolve(RUNS)
            if (!Files.isDirectory(runs, LinkOption.NOFOLLOW_LINKS)) return@forEach
            Files.list(runs).use { s -> s.toList() }.forEach { r ->
                val id = runCatching { UUID.fromString(r.fileName.toString()) }.getOrNull()
                if (id != null && !live(id)) {
                    removeSnapshot(repo, id)
                    removed += id
                }
            }
        }
        return removed
    }

    // ------------------------------------------------------------------ the current view

    /** Points `current/<name>` at [blob], atomically (a reader sees the old or the new bytes). Under the lock. */
    private fun linkCurrent(repo: String, name: String, blob: Path) {
        val dir = sub(repo, CURRENT)
        val target = inside(dir, name)
        thaw(dir)
        try {
            makeParents(dir, target)
            val tmp = target.resolveSibling(".tmp-${UUID.randomUUID()}")
            Files.createLink(tmp, blob)
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                Files.deleteIfExists(tmp)
                throw e
            }
        } finally {
            freeze(dir)
        }
    }

    private fun unlinkCurrent(repo: String, name: String) {
        val dir = repoDir(repo, create = false).resolve(CURRENT)
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return
        val target = inside(dir, name)
        thaw(dir)
        try {
            Files.deleteIfExists(target)
            // Empty parent directories go too, so a later file may use the name of one.
            var p = target.parent
            while (p != null && p != dir && p.startsWith(dir)) {
                val empty = Files.list(p).use { !it.findAny().isPresent }
                if (!empty) break
                Files.delete(p)
                p = p.parent
            }
        } finally {
            freeze(dir)
        }
    }

    /** Unlinks blob [sha256] of [repo] when no file row references it any more. Under the lock. */
    private fun collectBlob(repo: String, sha256: String) {
        if (store.countBySha(repo, sha256) > 0) return
        runCatching { Files.deleteIfExists(blobPath(repo, sha256)) }
    }

    /** Creates the directories between [dir] and [target] (exclusive), refusing anything that is not a plain directory. */
    private fun makeParents(dir: Path, target: Path) {
        var p = dir
        for (seg in dir.relativize(target.parent ?: dir)) {
            p = p.resolve(seg.toString())
            if (p == dir) continue
            plainDir(p)
            setMode(p, "rwxr-xr-x")
        }
    }

    /** Gives [dir] and the directories below it their write bit back (owner only), to change them. */
    private fun thaw(dir: Path) = walkDirs(dir) { setMode(it, "rwxr-xr-x") }

    /** Takes the write bits off [dir] and the directories below it (0555). */
    private fun freeze(dir: Path) = walkDirs(dir) { setMode(it, "r-xr-xr-x") }

    private fun walkDirs(dir: Path, action: (Path) -> Unit) {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isSymbolicLink) return FileVisitResult.SKIP_SUBTREE
                action(d)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** Deletes [path] and everything below it, making directories writable first; never follows a symlink. */
    private fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) thaw(path)
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                if (exc !is NoSuchFileException) throw exc
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun moveAtomic(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from, to)
        }
    }

    private fun audit(user: UserRecord, remote: String?, event: String, detail: String) =
        runCatching { audit.audit(user.id, user.username, event, detail, remote) }
            .onFailure { log.warn("Writing the audit entry {} failed: {}", event, it.message) }

    private fun cleanNote(note: String?): String? {
        val n = note?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (n.length > MAX_NOTE_CHARS) throw badRequest("A note has at most $MAX_NOTE_CHARS characters")
        return n
    }

    private fun fileTooLarge(size: Long, declared: Boolean = false) = ApiException(
        HttpStatusCode.PayloadTooLarge, "file_too_large",
        if (declared) "The body is longer than the declared size of $size bytes; nothing beyond it was kept."
        else "A project file may have at most ${cfg.projectFilesMaxFileBytes} bytes (FACTORY_PROJECT_FILES_MAX_FILE_MB); this one has $size bytes.",
    )

    companion object {
        /** No request handler or service method reads or writes more than this at once. */
        const val BUFFER_BYTES = 1 shl 20
        const val MAX_FILES = 200
        const val MAX_NAME_CHARS = 200
        const val MAX_SEGMENTS = 8
        const val MAX_NOTE_CHARS = 2000
        /** Free space kept besides the declared size of a new upload. */
        const val SPACE_RESERVE = 256L shl 20
        val UPLOAD_TTL: Duration = Duration.ofHours(24)
        const val BLOBS = "blobs"
        const val CURRENT = "current"
        const val RUNS = "runs"
        const val UPLOADS = "uploads"
        /** The fixed path of the run's files inside a sandbox (mounted read-only). */
        const val SANDBOX_PATH = "/project-files"
        /** The environment variable agents and sandbox commands find the files with. */
        const val ENV = "AGERN_PROJECT_FILES"
        private val NO_UPLOAD = UUID(0, 0)
        val SHA256_RE = Regex("^[0-9a-f]{64}$")
        private val SEGMENT_RE = Regex("^[A-Za-z0-9_][A-Za-z0-9._+@-]{0,99}$")
        const val NAME_RULE = "1 to $MAX_NAME_CHARS characters in 1 to $MAX_SEGMENTS segments separated by '/'; each segment starts " +
            "with a letter, digit or '_' and has only letters, digits and . _ + @ - (at most 100 characters)"

        /** Why [name] is not a valid project file name, or null when it is. */
        fun validateName(name: String): String? {
            if (name.isEmpty() || name.length > MAX_NAME_CHARS) return "A file name has $NAME_RULE."
            val segs = name.split('/')
            if (segs.size > MAX_SEGMENTS) return "A file name has $NAME_RULE."
            if (segs.any { !SEGMENT_RE.matches(it) }) return "A file name has $NAME_RULE."
            return null
        }

        /** 409 `name_conflict` when [name] is a directory prefix of one of [others], or one of them is of it. */
        fun nameConflict(name: String, others: List<String>): ApiException? {
            val n = name.lowercase()
            val clash = others.firstOrNull { o ->
                val l = o.lowercase()
                l != n && (l.startsWith("$n/") || n.startsWith("$l/"))
            } ?: return null
            return conflict("`$name` and `$clash` cannot both exist: one would be a directory of the other.", "name_conflict")
        }

        fun invalidName(why: String) = ApiException(HttpStatusCode.BadRequest, "invalid_file_name", why)
        fun emptyFile() = unprocessable("Empty files cannot be uploaded.", "empty_file")
        fun uploadNotFound() = notFound("Upload not found")
        fun fileNotFound() = notFound("File not found")
        fun uploadBusy() = conflict("Another chunk of this upload is being written; wait for it to finish.", "upload_busy")

        /** The SHA-256 of [file] in one streamed pass. */
        fun sha256Of(file: Path): String = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { sha256Of(it) }

        fun sha256Of(input: InputStream): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(BUFFER_BYTES)
            while (true) {
                val n = input.read(buf, 0, buf.size)
                if (n < 0) break
                md.update(buf, 0, n)
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** Copies [input] to [output] through a buffer of [BUFFER_BYTES]; returns the bytes copied. */
        fun copy(input: InputStream, output: java.io.OutputStream): Long {
            val buf = ByteArray(BUFFER_BYTES)
            var total = 0L
            while (true) {
                val n = input.read(buf, 0, buf.size)
                if (n < 0) break
                output.write(buf, 0, n)
                total += n
            }
            return total
        }

        /** `1.2 MiB`-style sizes for prompts and texts. */
        fun humanSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024L * 1024 -> "%.1f KiB".format(java.util.Locale.ROOT, bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> "%.1f MiB".format(java.util.Locale.ROOT, bytes / 1048576.0)
            else -> "%.1f GiB".format(java.util.Locale.ROOT, bytes / 1073741824.0)
        }

        private fun setMode(p: Path, mode: String) {
            runCatching { Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(mode)) }
        }
    }
}
