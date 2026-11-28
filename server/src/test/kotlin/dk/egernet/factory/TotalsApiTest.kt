package dk.egernet.factory

import dk.egernet.factory.db.ReleaseRecord
import dk.egernet.factory.db.UserRecord
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `GET /api/releases/{id}/totals` and `GET /api/runs/{id}/totals` (docs/ARCHITECTURE.md §6 "Time and cost"). */
class TotalsApiTest {
    private val clock = MutableClock(Instant.now())
    private val world = TestWorld(twoFactor = false, clock = clock)
    private val s = world.services
    private val alice = world.user("alice")

    /** How the builder behaves for an issue, by title. */
    private val behaviour = ConcurrentHashMap<String, String>()
    private val gates = ConcurrentHashMap<String, Boolean>()

    @AfterTest fun tearDown() {
        gates.keys.forEach { gates[it] = true }
        world.close()
    }

    init {
        world.launcher.stage = { p ->
            val title = Regex("Issue #\\d+: (.+)").find(p.spec.prompt ?: "")?.groupValues?.get(1)?.trim() ?: ""
            val b = behaviour[title] ?: "ok"
            val role = p.spec.kind
            when {
                // Killed before its first result: no cost.
                role == "builder" && b == "fail" -> { p.init(); p.exit(1) }
                // Two results in one process: total_cost_usd is cumulative, so the session costs 0.03.
                role == "builder" && b == "double" -> { p.init(); p.result("Half built.", cost = 0.01); p.result("Built it.", cost = 0.03); p.exit(0) }
                role == "builder" && b == "gate" -> {
                    p.init()
                    while (!p.killed && gates[title] != true) Thread.sleep(10)
                    if (!p.killed) { p.result("Built it.", cost = 0.05); p.exit(0) }
                }
                role == "spec-writer" -> { p.init(); p.result("## Spec\nDo the thing.", cost = 0.02); p.exit(0) }
                role == "builder" -> { p.init(); p.result("Built it.", cost = 0.10); p.exit(0) }
                role == "approver" -> {
                    p.init(); p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") }, cost = 0.01); p.exit(0)
                }
                else -> {
                    p.init()
                    p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "fine"); put("findings", buildJsonArray { }) }, cost = 0.01)
                    p.exit(0)
                }
            }
        }
    }

    private fun issue(title: String, b: String = "ok", repo: String = "demo-app"): Int {
        behaviour[title] = b
        return world.github.addIssue(repo, title, "body").number
    }

    private fun release(name: String, issues: List<Int>, repo: String = "demo-app", by: UserRecord = alice): ReleaseRecord = runBlocking {
        val r = s.releases.create(by, repo, name, "")
        s.releases.assign(by, repo, issues, r.id)
        s.releaseStore.get(r.id)!!
    }

    private fun awaitRelease(id: UUID, cond: (ReleaseRecord) -> Boolean): ReleaseRecord = runBlocking {
        withTimeout(20_000) {
            while (true) {
                val r = s.releaseStore.get(id)!!
                if (cond(r)) return@withTimeout r
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    private fun ReleaseRecord.issue(n: Int) = issues.single { it.number == n }

    private fun awaitNoActiveSessions(runIds: Collection<UUID>) = runBlocking {
        withTimeout(10_000) {
            while (runIds.flatMap { s.sessions.byRun(it) }.any { it.status in setOf("starting", "running") }) delay(20)
        }
    }

    private fun releaseTotals(id: UUID): ReleaseTotalsDto = s.totals.release(id).let { Totals.forRelease(s.releaseStore.get(id)!!, it.runs, it.sessions, clock.instant()) }

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String): HttpClient {
        val c = createClient { install(HttpCookies) }
        assertEquals(200, c.post("/api/auth/login") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
        }.status.value)
        return c
    }

    private suspend fun HttpClient.releaseTotals(id: UUID): ReleaseTotalsDto {
        val r = get("/api/releases/$id/totals")
        assertEquals(200, r.status.value, r.bodyAsText())
        return AppJson.decodeFromString(ReleaseTotalsDto.serializer(), r.bodyAsText())
    }

    private suspend fun HttpClient.runTotals(id: UUID): RunTotalsDto {
        val r = get("/api/runs/$id/totals")
        assertEquals(200, r.status.value, r.bodyAsText())
        return AppJson.decodeFromString(RunTotalsDto.serializer(), r.bodyAsText())
    }

    private fun sessionCosts(runIds: Collection<UUID>): Double = runIds.flatMap { s.sessions.byRun(it) }.sumOf { it.costUsd ?: 0.0 }

    @Test
    fun `a release with a failed and retried issue sums every session of both runs`() = testApplication {
        application { agernModule(s) }
        world.user("bob")
        val a = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        val broken = issue("Broken", "fail")
        val twice = issue("Twice", "double")
        val r = release("1.0", listOf(broken, twice))
        runBlocking { s.releases.start(alice, r.id) }
        val held = awaitRelease(r.id) { it.issue(broken).state == ReleaseIssueState.HELD && it.issue(twice).state == ReleaseIssueState.MERGED }
        val firstRun = held.issue(broken).runId!!
        behaviour["Broken"] = "ok"
        runBlocking { s.releases.retry(alice, r.id, broken) }
        val done = awaitRelease(r.id) { it.status == ReleaseStatus.READY && it.issue(broken).state == ReleaseIssueState.MERGED }
        val secondRun = done.issue(broken).runId!!
        assertTrue(secondRun != firstRun)
        val runIds = listOf(firstRun, secondRun, done.issue(twice).runId!!)
        awaitNoActiveSessions(runIds)

        val t = a.releaseTotals(r.id)
        assertEquals(r.id.toString(), t.releaseId)
        assertEquals(3, t.runs)
        assertEquals(runIds.map { it.toString() }.toSet(), t.runIds.toSet())
        assertEquals(sessionCosts(runIds), t.cost.usd, 1e-9)
        // The failed builder was killed before its first result.
        assertEquals(1, t.cost.unknownSessions)
        assertEquals(0, t.cost.runningSessions)
        assertFalse(t.time.running)
        assertEquals(t.time.elapsedMs - t.time.workingMs, t.time.waitingMs)

        val brokenTotals = t.issues.single { it.number == broken }
        assertEquals(2, brokenTotals.runs)
        assertEquals(sessionCosts(listOf(firstRun, secondRun)), brokenTotals.cost.usd, 1e-9)
        assertEquals(listOf(broken, twice), t.issues.map { it.number })
        // Every session of the second run is a retry.
        val retry = t.byWork.single { it.key == "retry" }
        assertEquals(s.sessions.byRun(secondRun).size, retry.cost.sessions)
        assertEquals(sessionCosts(listOf(secondRun)), retry.cost.usd, 1e-9)
        for (rows in listOf(t.byRole, t.byWork)) {
            assertEquals(t.cost.usd, rows.sumOf { it.cost.usd }, 1e-6)
            assertEquals(t.cost.sessions, rows.sumOf { it.cost.sessions })
        }
        // Released on an OAuth token: all subscription.
        assertEquals(0.0, t.cost.apiKeyUsd)
        assertEquals(t.cost.usd, t.cost.subscriptionUsd, 1e-9)

        // The builder that reported 0.01 and then 0.03 counts 0.03, once.
        val twiceRun = a.runTotals(done.issue(twice).runId!!)
        assertEquals(0.03, twiceRun.byRole.single { it.key == "builder" }.cost.usd, 1e-9)
        assertEquals(1, twiceRun.byRole.single { it.key == "builder" }.cost.sessions)
        val failedRun = a.runTotals(firstRun)
        assertEquals(1, failedRun.cost.unknownSessions)
        assertEquals(sessionCosts(listOf(firstRun)), failedRun.cost.usd, 1e-9)

        // Anyone who can see the release gets the same numbers.
        assertEquals(t, bob.releaseTotals(r.id))

        // Stage sessions record their round and credential kind.
        val rows = s.db.query("SELECT kind, round, cred_kind FROM agent_sessions WHERE run_id = ?", secondRun) {
            Triple(it.getString("kind"), it.getObject("round") as Int?, it.getString("cred_kind"))
        }
        assertTrue(rows.isNotEmpty())
        for ((kind, round, cred) in rows) {
            assertEquals(0, round, kind)
            assertEquals("oauth_token", cred, kind)
        }

        // A fixed number of statements, whatever the number of runs.
        val before = s.totals.statements.get()
        s.totals.release(r.id)
        val withRuns = s.totals.statements.get() - before
        s.totals.release(UUID.randomUUID())
        assertEquals(withRuns, s.totals.statements.get() - before - withRuns)
    }

    @Test
    fun `sessions on an API key are API cost, on an OAuth token subscription`() = testApplication {
        application { agernModule(s) }
        val bobUser = world.user("bob")
        s.users.setClaude(bobUser.id, "api_key", s.box.encrypt("sk-ant-api-bob-secret", "bob"))
        val bob = s.users.byId(bobUser.id)!!
        val b = login("bob", "pw-b")
        val a = login("alice", "pw-a")
        val n = issue("On a key")
        val keyRun = runBlocking { s.orchestrator.start(bob, "demo-app", n, null) }
        world.awaitRun(keyRun.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN, 20_000)
        val m = issue("On a token")
        val tokenRun = runBlocking { s.orchestrator.start(alice, "demo-app", m, null) }
        world.awaitRun(tokenRun.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN, 20_000)
        awaitNoActiveSessions(listOf(keyRun.id, tokenRun.id))

        val key = b.runTotals(keyRun.id)
        assertTrue(key.cost.apiKeyUsd > 0)
        assertEquals(0.0, key.cost.subscriptionUsd)
        val token = a.runTotals(tokenRun.id)
        assertTrue(token.cost.subscriptionUsd > 0)
        assertEquals(0.0, token.cost.apiKeyUsd)
        assertTrue(s.sessions.byRun(keyRun.id).isNotEmpty())
        val kinds = s.db.query("SELECT DISTINCT cred_kind FROM agent_sessions WHERE run_id = ?", keyRun.id) { it.getString(1) }
        assertEquals(listOf("api_key"), kinds)
        assertFalse(key.time.running)
        assertTrue(key.time.workingMs > 0 || key.time.elapsedMs == 0L)
    }

    @Test
    fun `invisible releases and runs answer 404, visible ones 200 for everyone`() = testApplication {
        application { agernModule(s) }
        world.user("bob")
        val a = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        val lab = release("lab", listOf(issue("Secret", repo = "private-lab")), repo = "private-lab")
        runBlocking { s.releases.start(alice, lab.id) }
        val built = awaitRelease(lab.id) { it.status == ReleaseStatus.READY }
        val labRun = built.issues.single().runId!!

        assertEquals(200, a.get("/api/releases/${lab.id}/totals").status.value)
        assertEquals(200, a.get("/api/runs/$labRun/totals").status.value)
        assertEquals(404, bob.get("/api/releases/${lab.id}/totals").status.value)
        assertEquals(404, bob.get("/api/runs/$labRun/totals").status.value)
        assertEquals(404, a.get("/api/releases/${UUID.randomUUID()}/totals").status.value)
        assertEquals(404, a.get("/api/runs/${UUID.randomUUID()}/totals").status.value)
        assertEquals(404, a.get("/api/runs/not-a-uuid/totals").status.value)
        assertEquals(401, createClient { }.get("/api/releases/${lab.id}/totals").status.value)

        // A planned release: no window, no runs.
        val planned = release("planned", listOf(issue("Later")))
        val p = bob.releaseTotals(planned.id)
        assertEquals(0L, p.time.elapsedMs)
        assertFalse(p.time.running)
        assertEquals(0, p.runs)
        assertEquals(0, p.issues.single().runs)
    }

    @Test
    fun `a building release runs and works, a cancelled one keeps its numbers`() {
        val held = issue("Held", "gate")
        val r = release("2.0", listOf(held))
        runBlocking { s.releases.start(alice, r.id) }
        runBlocking {
            withTimeout(10_000) { while (world.launcher.byRole("builder").none { (it.spec.prompt ?: "").contains("Held") }) delay(10) }
        }
        clock.advance(Duration.ofMinutes(1))
        val first = releaseTotals(r.id)
        assertTrue(first.time.running)
        assertTrue(first.time.working)
        assertEquals(1, first.cost.runningSessions)
        assertEquals(0, first.cost.unknownSessions)
        val issue = first.issues.single()
        assertTrue(issue.time.running && issue.time.working)

        clock.advance(Duration.ofHours(1))
        val later = releaseTotals(r.id)
        assertEquals(first.time.elapsedMs + 3_600_000, later.time.elapsedMs)
        assertEquals(first.time.workingMs + 3_600_000, later.time.workingMs)
        assertEquals(first.time.waitingMs, later.time.waitingMs)
        val runId = UUID.fromString(later.runIds.single())
        val run = s.totals.run(runId).let { Totals.forRun(it.runs.single(), it.sessions, clock.instant()) }
        assertTrue(run.time.running && run.time.working)
        assertEquals(1, run.cost.runningSessions)

        runBlocking { s.releases.cancel(alice, r.id) }
        awaitRelease(r.id) { it.status == ReleaseStatus.CANCELLED }
        world.awaitRun(runId, RunStatus.TERMINAL)
        awaitNoActiveSessions(listOf(runId))
        val cancelled = releaseTotals(r.id)
        clock.advance(Duration.ofHours(5))
        assertEquals(cancelled, releaseTotals(r.id))
        assertFalse(cancelled.time.running)
        assertFalse(cancelled.time.working)
        assertEquals(0, cancelled.cost.runningSessions)
        assertNotNull(cancelled.issues.single().time)
        assertFalse(cancelled.issues.single().time.running)
    }

    @Test
    fun `the migration backfills the round from the title and the credential kind from the owner`() {
        val source = java.nio.file.Paths.get(javaClass.getResource("/db/migration")!!.toURI())
        val dir = java.nio.file.Files.createTempDirectory("agern-migrations")
        val db = try {
            java.nio.file.Files.list(source).use { files ->
                files.filter { (Regex("^V(\\d+)__").find(it.fileName.toString())?.groupValues?.get(1)?.toInt() ?: 99) < 35 }
                    .forEach { java.nio.file.Files.copy(it, dir.resolve(it.fileName.toString())) }
            }
            TestDb.fresh(migrations = "filesystem:$dir")
        } finally {
            dir.toFile().deleteRecursively()
        }
        try {
            val keyUser = UUID.randomUUID()
            val noCred = UUID.randomUUID()
            db.update("INSERT INTO users(id, username, display_name, claude_cred_kind) VALUES (?, 'k', 'K', 'api_key'), (?, 'n', 'N', NULL)", keyUser, noCred)
            val run = UUID.randomUUID()
            db.update(
                "INSERT INTO runs(id, repo, issue_number, issue_title, issue_kind, status, max_rounds, owner_id) VALUES (?, 'demo-app', 3, 't', 'task', 'merged', 3, ?)",
                run, keyUser,
            )
            fun session(kind: String, title: String?, runId: UUID?, owner: UUID = keyUser): UUID = UUID.randomUUID().also {
                db.update(
                    "INSERT INTO agent_sessions(id, repo, kind, model, status, owner_id, title, run_id) VALUES (?, 'demo-app', ?, 'm', 'done', ?, ?, ?)",
                    it, kind, owner, title, runId,
                )
            }
            val fix = session("builder", "builder · #3 · round 2", run)
            val build = session("spec-writer", "spec-writer · #3", run)
            val advisor = session("advisor", "advisor · #3 · round 2", run)
            val chat = session("chat", null, null, owner = noCred)
            org.flywaydb.core.Flyway.configure().dataSource(db.dataSource).locations(dk.egernet.factory.db.Db.MIGRATIONS).load().migrate()
            fun row(id: UUID) = db.queryOne("SELECT round, cred_kind FROM agent_sessions WHERE id = ?", id) {
                (it.getObject("round") as Int?) to it.getString("cred_kind")
            }!!
            assertEquals(2 to "api_key", row(fix))
            assertEquals(0 to "api_key", row(build))
            assertEquals(null to "api_key", row(advisor))
            assertEquals(null to null, row(chat))
        } finally {
            (db.dataSource as java.io.Closeable).close()
        }
    }
}
