package dk.egernet.factory.images

import dk.egernet.factory.ApiException
import dk.egernet.factory.CodexLoginDto
import dk.egernet.factory.db.iso
import dk.egernet.factory.tooManyAttempts
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Codex device logins, one per user, in memory (docs/ARCHITECTURE.md §4 "Codex login"): runs
 * `codex login --device-auth` in a fresh `0700` directory, shows the user the verification URL and one-time code, and
 * stores the resulting `auth.json` encrypted for that user through [ImageService.storeLogin].
 *
 * The code, the URL and `auth.json` are never logged. Processes are killed on cancel, replace, expiry and [close].
 */
class CodexLogins(
    private val provider: CodexImageProvider,
    private val images: ImageService,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val startTimeout: Duration = Duration.ofSeconds(20),
    private val expiry: Duration = Duration.ofMinutes(15),
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(CodexLogins::class.java)

    companion object {
        private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")
        private val URL = Regex("https://\\S+")
        private val CODE = Regex("\\b[A-Z0-9]{4}-[A-Z0-9]{4,6}\\b")
        const val ALLOWED_URL = "https://auth.openai.com/"
        const val FAILED = "Codex sign-in failed. Try again."
        const val NO_CODE = "Codex did not offer a device code. Enable device code login in your ChatGPT security settings and try again."

        /** The verification URL and the user code in the CLI's output (ANSI stripped), once both have appeared. */
        fun parse(raw: String): Pair<String, String>? {
            val text = ANSI.replace(raw, "")
            val url = URL.find(text) ?: return null
            val code = CODE.find(text, url.range.last + 1) ?: return null
            return url.value to code.value
        }
    }

    private class Login(val process: Process, val dir: CodexImageProvider.CallDir) {
        @Volatile var status = "pending"
        @Volatile var url: String? = null
        @Volatile var code: String? = null
        @Volatile var expiresAt: Instant? = null
        @Volatile var error: String? = null
        @Volatile var watcher: Job? = null
        val offered = CountDownLatch(1)

        fun kill() {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private val logins = ConcurrentHashMap<UUID, Login>()

    init {
        runCatching { provider.cleanLeftovers() }
    }

    /** Starts a device login for [userId], replacing a pending one; answers once the URL and the code are shown. */
    suspend fun start(userId: UUID): CodexLoginDto {
        withContext(Dispatchers.IO) { provider.availability() }?.let { throw images.unavailableHere(it) }
        images.loginStarts.blockedUntil(userId)?.let { throw tooManyAttempts("Too many sign-in attempts. Try again later.", it) }
        images.loginStarts.record(userId)
        cancel(userId)

        val dir = try {
            withContext(Dispatchers.IO) { provider.CallDir() }
        } catch (e: Exception) {
            throw ApiException(HttpStatusCode.BadGateway, "codex_login_failed", FAILED)
        }
        val proc = try {
            withContext(Dispatchers.IO) {
                provider.processBuilder(dir, listOf("login", "--device-auth", "-c", "cli_auth_credentials_store=\"file\"")).start()
                    .also { it.outputStream.close() }
            }
        } catch (e: Exception) {
            dir.close()
            throw ApiException(HttpStatusCode.BadGateway, "codex_login_failed", FAILED)
        }
        val login = Login(proc, dir)
        CodexImageProvider.OutputTail(proc) { text ->
            if (login.url == null && login.error == null) {
                parse(text)?.let { (url, code) ->
                    if (url.startsWith(ALLOWED_URL)) {
                        login.url = url; login.code = code
                    } else {
                        login.error = FAILED
                    }
                    login.offered.countDown()
                }
            }
        }
        logins.put(userId, login)?.let { finish(it, "none", null) }
        login.watcher = scope.launch(Dispatchers.IO) { watch(userId, login) }

        val offered = runInterruptible(Dispatchers.IO) { login.offered.await(startTimeout.toMillis(), TimeUnit.MILLISECONDS) }
        if (!offered || login.url == null) {
            if (login.error != null) {
                finish(login, "failed", FAILED)
                return dto(login)
            }
            finish(login, "none", null)
            logins.remove(userId, login)
            throw ApiException(HttpStatusCode.BadGateway, "codex_login_failed", NO_CODE)
        }
        login.expiresAt = clock.instant().plus(expiry)
        return dto(login)
    }

    /** Waits for the CLI to exit (or the login to expire) and stores the login on success. */
    private suspend fun watch(userId: UUID, login: Login) {
        val exited = withTimeoutOrNull(expiry.toMillis()) { runInterruptible { login.process.waitFor() } }
        if (login.status != "pending") return
        if (exited == null) {
            finish(login, "expired", "The code expired. Start again.")
            return
        }
        // Let the output reader see everything the CLI printed (e.g. a refused URL) before deciding.
        if (login.offered.count > 0) runInterruptible { login.offered.await(500, TimeUnit.MILLISECONDS) }
        val auth = if (exited == 0 && login.url != null && login.error == null) runCatching { login.dir.readAuth() }.getOrNull() else null
        if (auth == null) {
            finish(login, "failed", FAILED)
            return
        }
        // Only the current login of this user may store (a replaced or cancelled one is no longer in the map).
        if (logins[userId] !== login) return
        try {
            images.storeLogin(userId, provider.id, auth)
            finish(login, "connected", null)
        } catch (e: Exception) {
            log.warn("Storing the Codex login failed: {}", e.javaClass.simpleName)
            finish(login, "failed", FAILED)
        }
    }

    private fun finish(login: Login, status: String, error: String?) {
        synchronized(login) {
            if (login.status != "pending") return
            login.status = status
            login.error = error
            login.url = null
            login.code = null
        }
        login.kill()
        login.dir.close()
    }

    fun status(userId: UUID): CodexLoginDto {
        val login = logins[userId] ?: return CodexLoginDto("none")
        val exp = login.expiresAt
        if (login.status == "pending" && exp != null && !clock.instant().isBefore(exp)) finish(login, "expired", "The code expired. Start again.")
        return dto(login)
    }

    /** Cancels a pending login of [userId] (status `none` afterwards); idempotent. */
    fun cancel(userId: UUID) {
        logins.remove(userId)?.let { finish(it, "none", null); it.watcher?.cancel() }
    }

    private fun dto(l: Login): CodexLoginDto =
        if (l.status == "pending") CodexLoginDto("pending", l.url, l.code, l.expiresAt?.iso())
        else CodexLoginDto(l.status, error = l.error)

    override fun close() {
        for (k in logins.keys.toList()) cancel(k)
    }
}
