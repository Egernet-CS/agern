package dk.egernet.factory

import dk.egernet.factory.agent.AgentIdentity
import dk.egernet.factory.agent.AgentLauncher
import dk.egernet.factory.agent.AgentProcess
import dk.egernet.factory.agent.LaunchSpec
import dk.egernet.factory.db.Db
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.git.GitOps
import dk.egernet.factory.github.FakeGitHub
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

object TestDb {
    private val pg: EmbeddedPostgres by lazy { EmbeddedPostgres.builder().start() }
    private val counter = AtomicInteger()

    /** A fresh, migrated database per call. */
    fun fresh(): Db {
        val name = "t${System.nanoTime()}_${counter.incrementAndGet()}"
        pg.postgresDatabase.connection.use { c -> c.createStatement().use { it.execute("CREATE DATABASE $name") } }
        return Db.connect(pg.getJdbcUrl("postgres", name), "postgres", "postgres", poolSize = 5)
    }
}

/** A scripted agent process. */
class FakeProcess(val spec: LaunchSpec, private val onInput: (FakeProcess, String) -> Unit = { _, _ -> }) : AgentProcess {
    private val ch = Channel<String>(Channel.UNLIMITED)
    private val exit = CompletableDeferred<Int>()
    @Volatile var killed = false
    /** Simulates a process that will not die: closing stdin and killing it do not end it (call [exit] to end it). */
    @Volatile var unkillable = false
    /** Every line written to stdin, in order. */
    val written = CopyOnWriteArrayList<String>()
    /** Lines written to stdin and not yet picked up (see [pickUp]). */
    val inputs = java.util.concurrent.LinkedBlockingQueue<String>()
    @Volatile var inputClosed = false

    fun emit(obj: JsonObject) { ch.trySend(obj.toString()) }
    fun exit(code: Int) {
        ch.close()
        exit.complete(code)
    }

    fun init(sessionId: String = spec.resumeClaudeSessionId ?: "claude-${UUID.randomUUID()}") = emit(buildJsonObject {
        put("type", "system"); put("subtype", "init"); put("session_id", sessionId); put("model", spec.model)
    })

    fun assistantText(text: String) = emit(buildJsonObject {
        put("type", "assistant")
        putJsonObject("message") {
            put("role", "assistant")
            putJsonArray("content") { addJsonObject { put("type", "text"); put("text", text) } }
        }
    })

    fun result(text: String?, structured: JsonObject? = null, isError: Boolean = false) = emit(buildJsonObject {
        put("type", "result"); put("subtype", if (isError) "error_during_execution" else "success"); put("is_error", isError)
        put("result", text); put("num_turns", 1); put("total_cost_usd", 0.01)
        if (structured != null) put("structured_output", structured)
    })

    /**
     * Like the real CLI with `--replay-user-messages`: takes the next stdin message (waiting up to [timeoutMs]),
     * echoes it with `isReplay: true` and returns its text; null when none arrived or stdin was closed.
     */
    fun pickUp(timeoutMs: Long = 10_000): String? {
        val line = inputs.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) ?: return null
        val msg = AppJson.parseToJsonElement(line).jsonObject["message"]!!.jsonObject
        emit(buildJsonObject { put("type", "user"); put("message", msg); put("isReplay", true); put("session_id", "fake") })
        return msg["content"]!!.jsonPrimitive.content
    }

    /** After the first result: answers later messages as further turns until stdin is closed, then exits 0. */
    fun followUpsUntilClosed(reply: (String) -> String = { "follow-up: $it" }) {
        while (!killed) {
            if (inputs.isEmpty() && inputClosed) {
                exit(0)
                return
            }
            val m = pickUp(20) ?: continue
            assistantText(reply(m))
            result(reply(m))
        }
    }

    override val lines: ReceiveChannel<String> get() = ch
    override fun send(line: String) {
        if (inputClosed) throw java.io.IOException("stdin is closed")
        written += line
        inputs += line
        onInput(this, line)
    }
    override fun closeInput() {
        inputClosed = true
        if (spec.interactive && !unkillable) exit(0)
    }
    override fun kill() {
        killed = true
        if (!unkillable && !exit.isCompleted) exit(143)
    }
    override suspend fun awaitExit(): Int = exit.await()
    override fun stderrTail(): String = "fake stderr"
}

/**
 * Launcher whose one-shot behaviour is supplied per test. Chat sessions echo their input.
 * [stage] runs on its own thread so it may block (e.g. to simulate a hanging builder).
 */
class FakeLauncher(var stage: (FakeProcess) -> Unit = { it.init(); it.result("ok"); it.exit(0) }) : AgentLauncher {
    val launched = CopyOnWriteArrayList<FakeProcess>()
    /** Every chat `user` message written to a process (the stream-json `message.content`). */
    val chatInputs = CopyOnWriteArrayList<kotlinx.serialization.json.JsonElement>()

    override fun launch(spec: LaunchSpec): AgentProcess {
        val p = if (spec.interactive) {
            FakeProcess(spec) { proc, line ->
                val o = AppJson.parseToJsonElement(line).jsonObject
                if (o["type"]?.jsonPrimitive?.contentOrNull == "user") {
                    val content = o["message"]!!.jsonObject["content"]!!
                    chatInputs += content
                    val text = (content as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }?.joinToString(" ")
                        ?: content.jsonPrimitive.content
                    proc.init(proc.spec.resumeClaudeSessionId ?: "chat-claude-${proc.spec.sessionId}")
                    proc.assistantText("echo: $text")
                    proc.result("echo: $text")
                }
            }
        } else FakeProcess(spec)
        launched += p
        // A steerable stage (spec-writer, builder) gets its task as the first stdin message: pick it up like the CLI does.
        if (!spec.interactive) thread(isDaemon = true) {
            if (spec.steerable) p.pickUp()
            stage(p)
        }
        return p
    }

    fun byRole(role: String) = launched.filter { it.spec.kind == role }
}

class FakeGitOps : GitOps {
    val pushes = CopyOnWriteArrayList<String>()
    /** Every worktree created, as `"<branch> <- <baseBranch>"`. */
    val worktrees = CopyOnWriteArrayList<String>()
    @Volatile var commits = 1
    override fun ensureClone(id: AgentIdentity, cloneUrl: String, dir: Path, home: Path) { Files.createDirectories(dir) }
    override fun createWorktree(id: AgentIdentity, mainDir: Path, worktree: Path, branch: String, baseBranch: String, home: Path): String {
        Files.createDirectories(worktree)
        worktrees += "$branch <- $baseBranch"
        return "origin/$baseBranch"
    }
    override fun commitsAhead(worktree: Path, baseRef: String, home: Path) = commits
    override fun push(id: AgentIdentity, worktree: Path, branch: String, home: Path) { pushes += branch }
    override fun listWorktrees(mainDir: Path, home: Path): List<dk.egernet.factory.git.WorktreeInfo> = emptyList()
    override fun removeWorktree(mainDir: Path, worktree: Path, home: Path, keepUnpushed: Boolean) {}
}

fun testConfig(dataDir: Path, twoFactor: Boolean = true, devUsers: Map<String, String> = mapOf("alice" to "pw-a", "bob" to "pw-b", "root" to "pw-r")) = Config(
    masterKey = Base64.getDecoder().decode("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="),
    githubOrg = "test-org",
    authProviders = listOf("dev"),
    admins = setOf("root"),
    devUsers = devUsers,
    dataDir = dataDir,
    twoFactorRequired = twoFactor,
    chatIdleMinutes = 30,
)

/** A clock whose instant the test sets and advances. */
class MutableClock(@Volatile var now: java.time.Instant = java.time.Instant.parse("2026-01-01T12:00:00Z")) : java.time.Clock() {
    override fun instant(): java.time.Instant = now
    override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): java.time.Clock = this
    fun advance(d: java.time.Duration) { now = now.plus(d) }
}

class TestWorld(
    twoFactor: Boolean = true,
    tweak: (Config) -> Config = { it },
    loginThrottle: dk.egernet.factory.auth.LoginThrottle = dk.egernet.factory.auth.LoginThrottle(stepDelay = java.time.Duration.ZERO),
    clock: java.time.Clock = java.time.Clock.systemUTC(),
    releaseStarter: ReleaseStarter? = null,
    /** Use the real git CLI in fake-GitHub mode (local clone, no pushes) instead of [FakeGitOps]. */
    realGit: Boolean = false,
) {
    val dataDir: Path = Files.createTempDirectory("agern-test")
    val cfg = tweak(testConfig(dataDir, twoFactor))
    val github = FakeGitHub.seeded("test-org")
    val git = FakeGitOps()
    val cliGit: dk.egernet.factory.git.CliGitOps? =
        if (realGit) dk.egernet.factory.git.CliGitOps(System.getenv("PATH") ?: "/usr/bin:/bin", "https://github.invalid/", localOnly = true) else null
    val launcher = FakeLauncher()
    val services = Services(
        cfg, TestDb.fresh(), github, cliGit ?: git, launcher, loginThrottle = loginThrottle, clock = clock, releaseStarter = releaseStarter,
    )

    /** Creates a user with credentials registered, as if they had logged in and filled in Settings. */
    fun user(name: String, admin: Boolean = false, claude: Boolean = true, github: Boolean = true): UserRecord {
        val u = services.users.upsertOnLogin(name, name.replaceFirstChar { it.uppercase() }, admin)
        if (claude) services.users.setClaude(u.id, "oauth_token", services.box.encrypt("sk-ant-oat-$name-secret", name))
        if (github) services.users.setGithub(u.id, name, services.box.encrypt("fake-$name", name))
        return services.users.byId(u.id)!!
    }

    fun awaitRun(id: UUID, statuses: Set<String>, timeoutMs: Long = 10_000) = runBlocking {
        withTimeout(timeoutMs) {
            while (true) {
                val r = services.runs.get(id)!!
                if (r.status in statuses) return@withTimeout r
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    fun close() = services.stop()
}
