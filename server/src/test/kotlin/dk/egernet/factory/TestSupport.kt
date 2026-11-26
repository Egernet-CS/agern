package dk.egernet.factory

import dk.egernet.factory.agent.AgentIdentity
import dk.egernet.factory.agent.AgentLauncher
import dk.egernet.factory.agent.AgentProcess
import dk.egernet.factory.agent.LaunchSpec
import dk.egernet.factory.db.Db
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.git.GitOps
import dk.egernet.factory.github.FakeGitHub
import com.zaxxer.hikari.HikariDataSource
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * How many pools from [fresh] may be open at once. Every pool holds 5 connections and the embedded PostgreSQL allows
     * 100 clients, so a test that never closes its pools would otherwise make some later test fail far away from it.
     */
    const val MAX_OPEN_POOLS = 10

    /** A pool handed out by [fresh]: its database's name and where it was created. */
    private class Pool(val dataSource: HikariDataSource, val database: String, val createdAt: Throwable)

    /** The pools handed out by [fresh] (closed ones are forgotten, and their databases dropped, on the next [fresh]). */
    private val pools = CopyOnWriteArrayList<Pool>()

    /**
     * A fresh database per call, migrated from [migrations] (the application's own by default). Its pool must be closed
     * when the test ends: [TestWorld.close], [Services.stopAndReleaseDb] or closing [Db.dataSource] directly.
     */
    fun fresh(migrations: String = Db.MIGRATIONS): Db {
        // A test database takes several megabytes in the embedded server's data directory (under the temp dir): drop
        // the databases nobody uses any more, so the disk use stays bounded by the pools open at once.
        val closedPools = pools.filter { it.dataSource.isClosed }
        pools.removeAll(closedPools.toSet())
        for (p in closedPools) {
            runCatching {
                pg.postgresDatabase.connection.use { c -> c.createStatement().use { it.execute("DROP DATABASE IF EXISTS ${p.database} WITH (FORCE)") } }
            }
        }
        check(pools.size < MAX_OPEN_POOLS) {
            "$MAX_OPEN_POOLS test database pools are still open; close them when the test ends " +
                "(TestWorld.close() or Services.stopAndReleaseDb()). Created by: " +
                pools.joinToString(", ") { creationSite(it.createdAt) }
        }
        val name = "t${System.nanoTime()}_${counter.incrementAndGet()}"
        pg.postgresDatabase.connection.use { c -> c.createStatement().use { it.execute("CREATE DATABASE $name") } }
        val db = Db.connect(pg.getJdbcUrl("postgres", name), "postgres", "postgres", poolSize = 5, migrations = migrations)
        pools += Pool(db.dataSource as HikariDataSource, name, Throwable("pool created here"))
        return db
    }

    /** The number of pools from [fresh] that are not closed yet. */
    fun openPools(): Int = pools.count { !it.dataSource.isClosed }

    /** The number of client connections the embedded PostgreSQL has, apart from the one asking. */
    fun clientConnections(): Int = pg.postgresDatabase.connection.use { c ->
        c.createStatement().use { st ->
            st.executeQuery(
                "SELECT count(*) FROM pg_stat_activity WHERE backend_type = 'client backend' AND pid <> pg_backend_pid()",
            ).use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    /** The first stack frame of [t] outside this support code and the JDK, Kotlin and JUnit, e.g. `ApiTest.<init>(ApiTest.kt:24)`. */
    private fun creationSite(t: Throwable): String {
        val skip = listOf("java.", "jdk.", "sun.", "kotlin.", "kotlinx.", "org.junit.", "org.gradle.", "worker.org.gradle.")
        val frame = t.stackTrace.firstOrNull { f ->
            val cls = f.className.substringAfterLast('.').substringBefore('$')
            cls != "TestDb" && cls != "TestWorld" && skip.none { f.className.startsWith(it) }
        } ?: return "unknown"
        return "${frame.className.substringAfterLast('.')}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})"
    }
}

/** Stops the services, waits (bounded) until none of their coroutines still uses the database, then closes the pool. */
fun Services.stopAndReleaseDb() {
    stop()
    runBlocking { withTimeoutOrNull(10_000) { scope.coroutineContext[kotlinx.coroutines.Job]!!.join() } }
    (db.dataSource as java.io.Closeable).close()
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
    /** Answers an advisor's message (the full text written to stdin); default: echo, like a chat. Runs on its own thread. */
    @Volatile var advisor: (FakeProcess, String) -> Unit = { proc, text ->
        proc.init(proc.spec.resumeClaudeSessionId ?: "advisor-claude-${proc.spec.sessionId}")
        proc.assistantText("echo: $text")
        proc.result("echo: $text")
    }

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
                    if (proc.spec.kind == "advisor") {
                        thread(isDaemon = true) { advisor(proc, text) }
                    } else {
                        proc.init(proc.spec.resumeClaudeSessionId ?: "chat-claude-${proc.spec.sessionId}")
                        proc.assistantText("echo: $text")
                        proc.result("echo: $text")
                    }
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
    /** Files in every commit, by path (what [readFile] returns). */
    val files = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Every [readFile], as `"<ref>:<path>"`. */
    val reads = CopyOnWriteArrayList<String>()
    override fun readFile(dir: Path, ref: String, path: String, home: Path, maxBytes: Int): String? {
        reads += "$ref:$path"
        return files[path]
    }
    /** Every clone made, as `"<cloneUrl> <branch or ->"`. */
    val clones = CopyOnWriteArrayList<String>()
    override fun ensureClone(id: AgentIdentity, cloneUrl: String, dir: Path, home: Path, branch: String?) {
        if (!Files.isDirectory(dir)) clones += "$cloneUrl ${branch ?: "-"}"
        Files.createDirectories(dir)
    }
    /** Every detached worktree created, as `"<worktree path> @ <baseBranch>"`. */
    val detached = CopyOnWriteArrayList<String>()
    override fun addDetachedWorktree(id: AgentIdentity, mainDir: Path, worktree: Path, baseBranch: String, home: Path): String {
        Files.createDirectories(worktree)
        detached += "$worktree @ $baseBranch"
        return "origin/$baseBranch"
    }
    /** Runs on every worktree right after it is created (e.g. to plant files a repository could contain). */
    @Volatile var afterWorktree: (Path) -> Unit = {}
    override fun createWorktree(
        id: AgentIdentity, mainDir: Path, worktree: Path, branch: String, baseBranch: String, home: Path, startFrom: String?,
    ): String {
        Files.createDirectories(worktree)
        afterWorktree(worktree)
        worktrees += "$branch <- ${startFrom ?: baseBranch}"
        return "origin/$baseBranch"
    }
    override fun commitsAhead(worktree: Path, baseRef: String, home: Path) = commits
    /** What [head] answers; tests move it to simulate a commit. */
    @Volatile var headSha = "0000000000000000000000000000000000000001"
    override fun head(worktree: Path, home: Path) = headSha
    /** What [changedFiles] answers (null, the default: it cannot be told, so no UI-change check). */
    @Volatile var changed: List<String>? = null
    override fun changedFiles(worktree: Path, baseRef: String, home: Path): List<String>? = changed
    override fun baseRef(baseBranch: String) = "origin/$baseBranch"
    /** What [branchCommits] answers (null: the branch is not found). */
    @Volatile var branchCommits: Int? = 1
    override fun branchCommits(id: AgentIdentity?, mainDir: Path, branch: String, baseBranch: String, home: Path): Int? = branchCommits
    /** While set, [push] throws a [dk.egernet.factory.git.GitException] with this text. */
    @Volatile var failPush: String? = null
    override fun push(id: AgentIdentity, worktree: Path, branch: String, home: Path) {
        failPush?.let { throw dk.egernet.factory.git.GitException(it) }
        pushes += branch
    }
    override fun listWorktrees(mainDir: Path, home: Path): List<dk.egernet.factory.git.WorktreeInfo> = emptyList()
    /** When set, [removeWorktree] deletes the directory (like the real one) and records it in [removedWorktrees]. */
    @Volatile var deleteWorktrees = false
    val removedWorktrees = CopyOnWriteArrayList<Path>()
    /**
     * What [compareBranches] answers for (base branch, head branch), before the [dirOf] filter; null (the default) means
     * the comparison cannot be made. A thrown exception is thrown by [compareBranches].
     */
    @Volatile var comparison: (base: String, head: String) -> dk.egernet.factory.git.BranchComparison? = { _, _ -> null }
    /** Every [compareBranches], as `"<head> -> <base>"`. */
    val comparisons = CopyOnWriteArrayList<String>()
    override fun compareBranches(
        mainDir: Path, baseBranch: String, headBranch: String, home: Path, githubToken: String?, dirOf: (String) -> String?,
    ): dk.egernet.factory.git.BranchComparison? {
        comparisons += "$headBranch -> $baseBranch"
        val c = comparison(baseBranch, headBranch) ?: return null
        return dk.egernet.factory.git.BranchComparison(c.baseFiles.filter { dirOf(it) != null }, c.changes.filter { dirOf(it.path) != null }, c.behind)
    }
    /** What [changedGitlinks] answers for (base branch, head branch); a thrown exception is thrown by it. Default: none. */
    @Volatile var gitlinks: (base: String, head: String) -> List<dk.egernet.factory.git.GitlinkChange>? = { _, _ -> emptyList() }
    override fun changedGitlinks(mainDir: Path, baseBranch: String, headBranch: String, home: Path, githubToken: String?) =
        gitlinks(baseBranch, headBranch)
    /** What [remoteHasCommit] answers for (url, sha); a thrown exception is thrown by it. Default: every commit exists. */
    @Volatile var remoteCommit: (url: String, sha: String) -> Boolean = { _, _ -> true }
    override fun remoteHasCommit(url: String, sha: String, home: Path, githubToken: String?) = remoteCommit(url, sha)
    /** What [unpushedSubmodules] answers for every checkout. */
    @Volatile var unpushed: List<dk.egernet.factory.git.UnpushedSubmodule> = emptyList()
    override fun unpushedSubmodules(dir: Path, home: Path) = unpushed
    /** What [compareWorktree] answers for the base ref (before the [dirOf] filter); default: nothing changed, not behind. */
    @Volatile var worktreeComparison: (baseRef: String) -> dk.egernet.factory.git.BranchComparison? =
        { _ -> dk.egernet.factory.git.BranchComparison(emptyList(), emptyList()) }
    override fun compareWorktree(worktree: Path, baseRef: String, home: Path, dirOf: (String) -> String?): dk.egernet.factory.git.BranchComparison? {
        val c = worktreeComparison(baseRef) ?: return null
        return dk.egernet.factory.git.BranchComparison(c.baseFiles.filter { dirOf(it) != null }, c.changes.filter { dirOf(it.path) != null }, c.behind)
    }
    /** What [mergeState] answers for (start commit, merged ref); default: [headSha], merged and finished. */
    @Volatile var merge: (start: String, mergedRef: String) -> dk.egernet.factory.git.MergeCheck =
        { _, _ -> dk.egernet.factory.git.MergeCheck(headSha, containsStart = true, containsMerged = true, unfinished = false) }
    /** Every [mergeState], as `"<start> <mergedRef>"`. */
    val mergeChecks = CopyOnWriteArrayList<String>()
    override fun mergeState(worktree: Path, startSha: String, mergedRef: String, home: Path): dk.egernet.factory.git.MergeCheck {
        mergeChecks += "$startSha $mergedRef"
        return merge(startSha, mergedRef)
    }
    /** Every fast-forward push ([pushFastForward]), as `"<branch> <sha>"`; never in [pushes]. */
    val ffPushes = CopyOnWriteArrayList<String>()
    /** Runs before every fast-forward push (e.g. to clear a conflict, as GitHub would after the merge). */
    @Volatile var beforeFastForward: (branch: String) -> Unit = {}
    /** While set, [pushFastForward] is refused as a non-fast-forward with this output. */
    @Volatile var rejectFastForward: String? = null
    override fun pushFastForward(id: AgentIdentity, worktree: Path, branch: String, home: Path) {
        failPush?.let { throw dk.egernet.factory.git.GitException(it) }
        rejectFastForward?.let { throw dk.egernet.factory.git.PushRejectedException(it) }
        beforeFastForward(branch)
        ffPushes += "$branch $headSha"
    }
    override fun removeWorktree(mainDir: Path, worktree: Path, home: Path, keepUnpushed: Boolean) {
        if (!deleteWorktrees) return
        worktree.toFile().deleteRecursively()
        removedWorktrees.add(worktree)
    }
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
    // Waiting for the network is off in tests, so failures keep their meaning; tests of the wait switch it on.
    networkRetryMaxMs = 0,
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
    /** Sandboxes on (FACTORY_SANDBOX=podman) with [sandboxRuntime] as the container runtime. */
    sandbox: Boolean = false,
    launcher: dk.egernet.factory.agent.AgentLauncher? = null,
    clock: java.time.Clock = java.time.Clock.systemUTC(),
    releaseStarter: ReleaseStarter? = null,
    /** Use the real git CLI in fake-GitHub mode (local clone, no pushes) instead of [FakeGitOps]. */
    realGit: Boolean = false,
    /** The image providers (null: the production default list). */
    imageProviders: List<dk.egernet.factory.images.ImageProvider>? = null,
    /** Git operations to use instead of [FakeGitOps] or [realGit] (e.g. real git with local remotes). */
    gitOps: GitOps? = null,
) {
    val dataDir: Path = Files.createTempDirectory("agern-test")
    val cfg = tweak(testConfig(dataDir, twoFactor).let { if (sandbox) it.copy(sandbox = it.sandbox.copy(mode = "podman")) else it })
    val github = FakeGitHub.seeded("test-org")
    val git = FakeGitOps()
    val cliGit: dk.egernet.factory.git.CliGitOps? =
        if (realGit) dk.egernet.factory.git.CliGitOps(System.getenv("PATH") ?: "/usr/bin:/bin", "https://github.invalid/", localOnly = true) else null
    /** Real git ([realGit]) whose pushes fail while [FailingPushGit.failPush] is set (local-only git never pushes). */
    val realGitOps: FailingPushGit? = cliGit?.let { FailingPushGit(it) }
    val launcher = FakeLauncher()
    val sandboxRuntime = FakeSandboxRuntime()
    /** Exceptions that escaped a background coroutine of [services]; [close] fails the test when there are any. */
    val backgroundFailures = CopyOnWriteArrayList<Throwable>()
    val services = Services(
        cfg, TestDb.fresh(), github, gitOps ?: realGitOps ?: git, launcher ?: this.launcher, loginThrottle = loginThrottle, clock = clock,
        releaseStarter = releaseStarter, sandboxRuntime = if (sandbox) sandboxRuntime else null, imageProviders = imageProviders,
        onBackgroundFailure = { backgroundFailures += it },
    )

    init {
        // The fake GitHub has no checks: without this, every run would wait out the settle period before it merges.
        // Tests of the checks before a merge set their own values; release tests set the release merger's period.
        services.orchestrator.checksSettleSeconds = 0
        services.orchestrator.checksPollMillis = 50
    }

    /** Puts `.agern/sandbox.yml` on the default branch (GitHub API and git alike); null removes it. */
    fun sandboxFile(content: String?, repo: String = "demo-app") {
        github.setFile(repo, dk.egernet.factory.sandbox.SANDBOX_FILE, content)
        if (content == null) git.files.remove(dk.egernet.factory.sandbox.SANDBOX_FILE) else git.files[dk.egernet.factory.sandbox.SANDBOX_FILE] = content
    }

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

    private val closed = java.util.concurrent.atomic.AtomicBoolean()

    /**
     * Stops the services and gives the pool's connections back to PostgreSQL (see [Services.stopAndReleaseDb]); the
     * embedded server allows only so many. Calling it again does nothing. A test that simulates a restart on the same
     * database calls `services.stop()` instead, which leaves the pool open.
     *
     * Throws an [AssertionError] afterwards when a background coroutine of the services failed, so the failure is
     * reported by the test that caused it (not by whichever test happens to run next).
     */
    fun close() {
        if (closed.compareAndSet(false, true)) {
            services.stopAndReleaseDb()
            val failures = backgroundFailures.toList()
            if (failures.isNotEmpty()) {
                throw AssertionError(
                    "A background coroutine of this test's services failed: " + failures.joinToString("; ") { it.toString() },
                    failures.first(),
                ).apply { failures.drop(1).forEach { addSuppressed(it) } }
            }
        }
    }
}

/** Delegates to [inner], except that [push] throws while [failPush] is set. */
class FailingPushGit(private val inner: GitOps) : GitOps by inner {
    @Volatile var failPush: String? = null
    val pushes = CopyOnWriteArrayList<String>()
    override fun push(id: AgentIdentity, worktree: Path, branch: String, home: Path) {
        failPush?.let { throw dk.egernet.factory.git.GitException(it) }
        pushes += branch
        inner.push(id, worktree, branch, home)
    }
    override fun pushFastForward(id: AgentIdentity, worktree: Path, branch: String, home: Path) {
        failPush?.let { throw dk.egernet.factory.git.GitException(it) }
        inner.pushFastForward(id, worktree, branch, home)
        pushes += "ff $branch"
    }
}

/**
 * A container runtime in memory: records every call, keeps the containers and networks it "created" (with their
 * `agern.run` label) and answers commands through [onExec].
 */
class FakeSandboxRuntime : dk.egernet.factory.sandbox.SandboxRuntime {
    /** Every call, e.g. `network create agern-run-…`, `start agern-run-…`, `exec agern-run-… ./gradlew test`. */
    val calls = CopyOnWriteArrayList<String>()
    val started = CopyOnWriteArrayList<dk.egernet.factory.sandbox.ContainerSpec>()
    /** Commands run with exec: (container, command line after `sh -lc`, timeout ms). The server's own steps are not here. */
    val execs = CopyOnWriteArrayList<Triple<String, String, Long>>()
    /** Who each of [execs] ran as (null: the container's default user). */
    val execUsers = CopyOnWriteArrayList<dk.egernet.factory.sandbox.ExecUser?>()
    /** The server's own steps: `prepare-user <uid> <name> <caches…>`, `hand-over …`, `ready <container> <command>`. */
    val internal = CopyOnWriteArrayList<String>()
    /** Answers readiness checks: (service container, check command, attempt starting at 1). Default: ready at once. */
    @Volatile var onReady: (container: String, command: String, attempt: Int) -> Int = { _, _, _ -> 0 }
    private val readyAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** Existing containers and networks: name → run id. */
    val containers = java.util.concurrent.ConcurrentHashMap<String, String>()
    val networks = java.util.concurrent.ConcurrentHashMap<String, String>()
    val volumes: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile var onExec: (container: String, command: String, timeoutMs: Long) -> dk.egernet.factory.sandbox.ExecResult =
        { _, cmd, _ -> dk.egernet.factory.sandbox.ExecResult(0, "ran: $cmd\n", false, false, 5) }
    /** startContainer fails for names containing this. */
    @Volatile var failStart: String? = null
    /** Called after a container was started (e.g. to race a release against a creation). */
    @Volatile var onStart: (dk.egernet.factory.sandbox.ContainerSpec) -> Unit = {}

    override fun createNetwork(name: String, labels: Map<String, String>) {
        calls += "network create $name"
        networks[name] = labels.getValue("agern.run")
    }
    override fun ensureVolume(name: String, labels: Map<String, String>) {
        calls += "volume $name"
        volumes += name
    }
    override fun startContainer(spec: dk.egernet.factory.sandbox.ContainerSpec) {
        calls += "start ${spec.name}"
        failStart?.let { if (spec.name.contains(it)) throw dk.egernet.factory.sandbox.SandboxRuntimeException("image pull failed (fake)") }
        started += spec
        containers[spec.name] = spec.labels.getValue("agern.run")
        onStart(spec)
    }
    override fun exec(
        container: String, command: List<String>, workdir: String, timeoutMs: Long, user: dk.egernet.factory.sandbox.ExecUser?,
    ): dk.egernet.factory.sandbox.ExecResult {
        if (!containers.containsKey(container)) throw dk.egernet.factory.sandbox.SandboxRuntimeException("no such container $container")
        when (command.getOrNull(3)) {
            "agern-user" -> {
                internal += "prepare-user $container " + command.drop(4).joinToString(" ")
                val name = command[5].ifEmpty { "sandbox" }
                return dk.egernet.factory.sandbox.ExecResult(0, "AGERN_USER $name /home/$name\n", false, false, 1)
            }
            "agern-hand-over" -> {
                internal += "hand-over $container " + command.drop(4).joinToString(" ")
                return dk.egernet.factory.sandbox.ExecResult(0, "", false, false, 1)
            }
        }
        if (command.size == 3 && command[1] == "-c" && workdir == "/") {
            val attempt = readyAttempts.merge("$container ${command[2]}", 1, Int::plus)!!
            internal += "ready $container ${command[2]}"
            return dk.egernet.factory.sandbox.ExecResult(onReady(container, command[2], attempt), "not yet", false, false, 1)
        }
        val line = command.last() // SandboxManager.shellCommand: the command is the last argument
        calls += "exec $container $line"
        execs += Triple(container, line, timeoutMs)
        execUsers += user
        return onExec(container, line, timeoutMs)
    }
    override fun logs(name: String, lines: Int) = "LOG: database system is starting up ($name)"
    override fun restartContainer(name: String) { calls += "restart $name" }
    /** Each container removed that bind-mounted a worktree: (name, whether that directory still existed then). */
    val removedWithWorktree = CopyOnWriteArrayList<Pair<String, Boolean>>()
    override fun removeContainers(names: List<String>) {
        for (n in names) {
            if (!containers.containsKey(n)) continue
            val wt = started.lastOrNull { it.name == n }?.mounts?.firstOrNull { !it.volume && it.target == "/workspace" }?.source ?: continue
            removedWithWorktree += n to java.nio.file.Files.exists(java.nio.file.Path.of(wt))
        }
        calls += "rm ${names.joinToString(" ")}"
        names.forEach { containers.remove(it) }
    }
    override fun removeNetwork(name: String) {
        calls += "network rm $name"
        networks.remove(name)
    }
    override fun listContainers() = containers.map { (n, r) -> dk.egernet.factory.sandbox.LabelledResource(n, r) }
    override fun listNetworks() = networks.map { (n, r) -> dk.egernet.factory.sandbox.LabelledResource(n, r) }
    override fun exposedPorts(image: String) = if (image.contains("postgres")) listOf("5432/tcp") else emptyList()
}
