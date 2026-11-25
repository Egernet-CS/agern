package dk.egernet.factory

import dk.egernet.factory.db.ReleaseRecord
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.github.GhLabel
import dk.egernet.factory.mcp.McpGrant
import dk.egernet.factory.mcp.McpRegistry
import dk.egernet.factory.security.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Releases in workspace projects (docs/ARCHITECTURE.md §6 "Several repositories"): planning member issues, building
 * them in their member repositories on `release/<name>`, one release pull request per touched repository, the ordered
 * approval with the submodule pointers, partial failures, cancel and visibility. The fake GitHub's seeded `demo-shop`
 * (submodules: `services/api` → demo-shop-api, `web` → demo-shop-web; issues #1 api, #2 web, #3 workspace) and
 * `demo-workspace` (`.agern/workspace.yml`: `app` → demo-ws-app on `dev`, `tools/cli` → demo-ws-tools, `lab` → demo-ws-lab).
 */
class WorkspaceReleaseTest {
    private val world = TestWorld(twoFactor = false)
    private val s = world.services
    private val gh = world.github
    private val alice = world.user("alice")
    private val ws = "demo-shop"
    private val org = "test-org"

    @AfterTest fun tearDown() = world.close()

    init {
        s.releaseMerger.mergeablePollMillis = 20
        s.releaseMerger.checksSettleSeconds = 0
        world.launcher.stage = { p ->
            p.init()
            when (p.spec.kind) {
                "spec-writer" -> p.result("## Spec\nDo it.")
                "builder" -> p.result("Built it.")
                "approver" -> p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") })
                else -> p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
            }
            p.exit(0)
        }
    }

    // ------------------------------------------------------------ helpers

    private fun code(block: suspend () -> Unit): String = assertFailsWith<ApiException> { runBlocking { block() } }.code

    private fun release(name: String, issues: List<Int>, repo: String = ws, mode: String? = null, by: UserRecord = alice): ReleaseRecord =
        runBlocking {
            val r = s.releases.create(by, repo, name, "", mode)
            if (issues.isNotEmpty()) s.releases.assign(by, repo, issues, r.id)
            s.releaseStore.get(r.id)!!
        }

    private fun awaitRelease(id: UUID, what: String, cond: (ReleaseRecord) -> Boolean): ReleaseRecord = runBlocking {
        withTimeoutOrNull(20_000) {
            while (true) {
                val r = s.releaseStore.get(id)!!
                if (cond(r)) return@withTimeoutOrNull r
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: error("not reached: $what: ${s.releaseStore.get(id)}")
    }

    /** Built and evaluated: ready, with a release pull request in every touched repository. */
    private fun awaitReady(id: UUID): ReleaseRecord = awaitRelease(id, "ready with pull requests") { r ->
        r.status == ReleaseStatus.READY && r.toDto().repositories.filter { it.issues.isNotEmpty() || (it.directory == null && r.projectTouched) }
            .all { it.prNumber != null && it.mergeState != ReleaseMergeState.UNKNOWN }
    }

    private fun readyRelease(name: String, issues: List<Int>, repo: String = ws, mode: String? = null): ReleaseRecord {
        val r = release(name, issues, repo, mode)
        runBlocking { s.releases.start(alice, r.id) }
        return awaitReady(r.id)
    }

    private fun eventually(what: String, cond: () -> Boolean) = runBlocking {
        withTimeoutOrNull(10_000) { while (!cond()) delay(20) } ?: error("not reached: $what")
    }

    private fun comments(repo: String, n: Int) = gh.client("fake-alice").listComments(org, repo, n).map { it.body ?: "" }
    private fun row(r: ReleaseRecord, repo: String) = r.repos.single { it.repo == repo }
    private fun merges(repo: String) = gh.actions.filter { it.startsWith("merge-rebase $repo #") || it.startsWith("merge-merge $repo #") }

    // ------------------------------------------------------------ planning

    @Test
    fun `member issues are planned in both kinds of workspace, all or nothing`() {
        val shop = release("p1", listOf(1, 2, 3))
        assertEquals(listOf(1, 2, 3), shop.issues.map { it.number })
        val yml = release("p2", listOf(1, 2), repo = "demo-workspace")
        assertEquals(listOf(1, 2), yml.issues.map { it.number })

        // An unlabelled issue of a .agern/workspace.yml workspace, two labels, an unreadable member: refused, nothing assigned.
        val missing = assertFailsWith<ApiException> { runBlocking { s.releases.assign(alice, "demo-workspace", listOf(5, 3), yml.id) } }
        assertEquals("member_label_missing", missing.code)
        assertTrue(missing.message.endsWith("You can set it with `gh issue edit 3 --repo test-org/demo-workspace --add-label \"<label>\"`."), missing.message)
        assertEquals("member_label_ambiguous", code { s.releases.assign(alice, "demo-workspace", listOf(4), yml.id) })
        val bob = world.user("bob")
        assertEquals("member_not_accessible", code { s.releases.assign(bob, "demo-workspace", listOf(5), yml.id) })
        assertEquals(listOf(1, 2), s.releaseStore.get(yml.id)!!.issues.map { it.number })
        // Taking issues out needs no routing.
        runBlocking { s.releases.assign(alice, "demo-workspace", listOf(2), null) }
        assertEquals(listOf(1), s.releaseStore.get(yml.id)!!.issues.map { it.number })
    }

    @Test
    fun `factory release commands, chat tools and personal tokens plan member issues`() = runBlocking {
        val chat = s.sessions.create("demo-workspace", "chat", "opus", "idle", alice.id, null)
        suspend fun cmd(t: String) = try { s.factory.handleCommand(chat, alice, t) } catch (e: ApiException) { e.message }
        assertTrue(cmd("/factory release create cmd").contains("Created release \"cmd\""))
        assertTrue(cmd("/factory release add cmd 1").contains("added #1"), cmd("/factory releases"))
        assertTrue(cmd("/factory release add cmd 3").contains("--add-label \"<label>\""))
        assertTrue(cmd("/factory release remove 1").contains("Removed #1"))

        suspend fun call(token: String, tool: String, args: String): Pair<Boolean, String> {
            val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$args}}"""
            val res = AppJson.parseToJsonElement(s.mcp.handle(token, body, "10.0.0.1").second!!).jsonObject["result"]!!.jsonObject
            return (res["isError"]?.jsonPrimitive?.content == "true") to res["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        }
        val chatToken = s.mcpRegistry.issue(McpGrant(chat.id, alice.id, "demo-workspace", null, McpRegistry.CHAT_TOOLS))
        val secret = dk.egernet.factory.mcp.McpAuthenticator.newToken()
        s.mcpTokens.create(alice.id, "demo-shop", "laptop", Tokens.sha256Hex(secret), java.time.Instant.now(), null)

        assertFalse(call(chatToken, "factory_create_release", """{"name":"chat"}""").first)
        assertFalse(call(chatToken, "factory_assign_issues", """{"release":"chat","issueNumbers":[1,2]}""").first)
        val (err, text) = call(chatToken, "factory_assign_issues", """{"release":"chat","issueNumbers":[3]}""")
        assertTrue(err, text)
        assertTrue(text.startsWith("Issue #3 has no member label.") && text.contains("gh issue edit 3 --repo test-org/demo-workspace --add-label"), text)
        assertFalse(call(chatToken, "factory_unassign_issues", """{"issueNumbers":[2]}""").first)
        assertEquals(listOf(1), s.releaseStore.byName("demo-workspace", "chat")!!.issues.map { it.number })

        assertFalse(call(secret, "factory_create_release", """{"name":"mine"}""").first)
        val (e2, t2) = call(secret, "factory_assign_issues", """{"release":"mine","issueNumbers":[1,2,3]}""")
        assertFalse(e2, t2)
        assertEquals(listOf(1, 2, 3), s.releaseStore.byName(ws, "mine")!!.issues.map { it.number })
    }

    // ------------------------------------------------------------ building and ready

    @Test
    fun `a mixed release builds each issue in its repository and opens one pull request per touched repository`() {
        val r0 = release("r1", listOf(1, 2, 3))
        runBlocking { s.releases.start(alice, r0.id) }
        val r = awaitReady(r0.id)
        assertEquals(listOf(ReleaseIssueState.MERGED), r.issues.map { it.state }.distinct(), r.issues.toString())

        // The member branches were created once, from each member's base branch; nothing for the untouched tools/cli.
        assertEquals(1, gh.actions.count { it == "branch demo-shop-api release/r1" })
        assertEquals(1, gh.actions.count { it == "branch demo-shop-web release/r1" })
        assertTrue(gh.actions.none { it == "branch demo-ws-tools release/r1" })
        assertEquals(listOf("demo-shop-api", "demo-shop-web"), r.touchedRepos.map { it.repo })
        assertTrue(r.projectTouched)

        val runs = s.runs.list(ws, activeOnly = false).associateBy { it.issueNumber }
        assertEquals("demo-shop-api", runs[1]!!.targetRepo)
        assertEquals("services/api", runs[1]!!.targetDir)
        assertEquals("release/r1", runs[1]!!.baseBranch)
        assertEquals("demo-shop-web", runs[2]!!.targetRepo)
        assertNull(runs[3]!!.targetRepo)
        assertEquals("release/r1", runs[3]!!.baseBranch)
        assertEquals(gh.pullMeta[runs[1]!!.prNumber!!]!!.let { it.repo to it.base }, "demo-shop-api" to "release/r1")
        assertEquals(gh.pullMeta[runs[2]!!.prNumber!!]!!.let { it.repo to it.base }, "demo-shop-web" to "release/r1")
        // Squash-merged in the member, without a pointer bump and without closing the issue.
        assertTrue(gh.actions.contains("merge demo-shop-api #${runs[1]!!.prNumber}"), gh.actions.toString())
        assertTrue(gh.pullMeta.values.none { it.head.startsWith("factory/bump-") })
        assertTrue(gh.actions.none { it == "close $ws#1" })
        assertTrue(
            comments(ws, 1).any {
                it.startsWith("**Agern: merged** pull request test-org/demo-shop-api#${runs[1]!!.prNumber} into `release/r1` of test-org/demo-shop-api") &&
                    it.endsWith("The issue stays open until the release is released.")
            },
            comments(ws, 1).toString(),
        )
        assertTrue(gh.pullBodies[runs[1]!!.prNumber!!]!!.startsWith("Issue test-org/demo-shop#1 · release `r1`"))
        // The builder's prompt: the target member on release/r1, and the pointer moves with the release.
        val prompt = world.launcher.byRole("builder").first { it.spec.prompt!!.contains("Issue #1:") }.spec.prompt!!
        assertTrue(prompt.contains("Target member directory: `services/api` (repository test-org/demo-shop-api, base branch `release/r1`)"), prompt)
        assertTrue(prompt.contains("Agern moves the pointer in test-org/demo-shop when the release is released."), prompt)
        assertTrue(prompt.contains("squash-merged into the release branch `release/r1` (not into `main`)"), prompt)

        // Three release pull requests: each member into its base branch, the workspace into main.
        val api = row(r, "demo-shop-api")
        val web = row(r, "demo-shop-web")
        assertTrue("pr demo-shop-api release/r1 -> main #${api.prNumber}" in gh.actions, gh.actions.toString())
        assertTrue("pr demo-shop-web release/r1 -> main #${web.prNumber}" in gh.actions, gh.actions.toString())
        assertTrue("pr demo-shop release/r1 -> main #${r.prNumber}" in gh.actions, gh.actions.toString())
        val apiBody = gh.pullBodies[api.prNumber!!]!!
        assertTrue(apiBody.startsWith("Release **r1** of test-org/demo-shop: the part in test-org/demo-shop-api, built by Agern for @alice."), apiBody)
        assertTrue(apiBody.contains("- test-org/demo-shop#1 Add a health endpoint (pull request #${runs[1]!!.prNumber})"), apiBody)
        assertFalse(apiBody.contains("#2 "), apiBody)
        assertTrue(apiBody.contains("## Other pull requests of this release") && apiBody.contains(r.prUrl!!) && apiBody.contains(web.prUrl!!), apiBody)
        assertTrue(apiBody.contains("Do not merge it by hand"), apiBody)
        val wsBody = gh.pullBodies[r.prNumber!!]!!
        assertTrue(wsBody.contains("- #1 Add a health endpoint (pull request test-org/demo-shop-api#${runs[1]!!.prNumber})"), wsBody)
        assertTrue(wsBody.contains("- #3 Update the architecture notes (pull request #${runs[3]!!.prNumber})"), wsBody)
        assertTrue(wsBody.contains("## Submodule pointers") && wsBody.contains("- `services/api` → test-org/demo-shop-api `release/r1` (pull request ${api.prUrl})"), wsBody)
        for (b in listOf(apiBody, wsBody, gh.pullBodies[web.prNumber!!]!!)) {
            assertFalse(Regex("(?i)\\b(close[sd]?|fix(es|ed)?|resolve[sd]?) ").containsMatchIn(b), b)
        }
        // The workspace release branch points at the tips of the members' release branches.
        assertEquals("sha-release/r1", gh.gitlinks["$ws:release/r1"]!!["services/api"])
        assertEquals("sha-release/r1", gh.gitlinks["$ws:release/r1"]!!["web"])
        assertEquals("0".repeat(39) + "4", gh.gitlinks["$ws:release/r1"]!!["tools/cli"])
        assertEquals("0".repeat(39) + "1", gh.gitlinks["$ws:main"]!!["services/api"])

        val dto = r.toDto()
        assertEquals(listOf("demo-shop-api", "demo-shop-web", "demo-shop"), dto.repositories.map { it.repo })
        assertEquals(listOf("open"), dto.repositories.map { it.state }.distinct())
        assertEquals(listOf(1), dto.repositories[0].issues)
        assertEquals(listOf(3), dto.repositories[2].issues)
        assertEquals("services/api", dto.issues.single { it.number == 1 }.targetDir)
        assertNull(dto.issues.single { it.number == 3 }.targetRepo)
        // Announced once on every release pull request.
        eventually("ready comments") { listOf(api.prNumber!! to "demo-shop-api", web.prNumber!! to "demo-shop-web", r.prNumber!! to ws).all { (n, repo) ->
            comments(repo, n).count { it.startsWith("**Agern: release `r1` is ready to release.**") } == 1
        } }
        val listing = runBlocking { s.releases.describe(alice, ws, withIds = false) }
        assertTrue(listing.contains("Pull requests: test-org/demo-shop-api#${api.prNumber} open"), listing)
        assertTrue(listing.contains("#1 Add a health endpoint [member services/api]"), listing)

        // ------------------------------------------------------------ approval
        val released = runBlocking { s.releases.approve(alice, r.id) }
        assertEquals(ReleaseStatus.RELEASED, released.status, released.notice)
        val order = gh.actions.filter { it.startsWith("merge-rebase ") }
        assertEquals(listOf("merge-rebase demo-shop-api #${api.prNumber}", "merge-rebase demo-shop-web #${web.prNumber}", "merge-rebase $ws #${r.prNumber}"), order)
        val after = s.releaseStore.get(r.id)!!
        assertEquals(row(after, "demo-shop-api").mergeSha, gh.gitlinks["$ws:main"]!!["services/api"])
        assertEquals(row(after, "demo-shop-web").mergeSha, gh.gitlinks["$ws:main"]!!["web"])
        assertEquals("0".repeat(39) + "4", gh.gitlinks["$ws:main"]!!["tools/cli"])
        assertTrue(gh.gitlinkMessages.values.any { it.startsWith("Release r1: bump services/api to ") }, gh.gitlinkMessages.toString())
        for (n in 1..3) assertTrue(gh.actions.contains("close $ws#$n"), gh.actions.toString())
        assertTrue(comments(ws, 1).any { it.startsWith("**Agern: released** in release `r1`: pull request ${api.prUrl} was rebased onto `main`") }, comments(ws, 1).toString())
        assertTrue(comments(ws, 3).any { it.startsWith("**Agern: released** in release `r1`: pull request ${r.prUrl} was rebased onto `main`") }, comments(ws, 3).toString())
        for (repo in listOf(ws, "demo-shop-api", "demo-shop-web")) assertTrue("delete-branch $repo release/r1" in gh.actions, gh.actions.toString())
        assertNull(after.notice)
        val reply = FactoryService.approvedReply(after)
        assertTrue(reply.contains("`services/api` → `${row(after, "demo-shop-api").mergeSha!!.take(12)}`"), reply)
    }

    @Test
    fun `a member release run reads its sandbox definition from the member's base branch`() {
        val sw = TestWorld(twoFactor = false, sandbox = true)
        try {
            sw.launcher.stage = world.launcher.stage
            val u = sw.user("alice")
            val r = runBlocking {
                val r = sw.services.releases.create(u, ws, "sb", "")
                sw.services.releases.assign(u, ws, listOf(1), r.id)
                sw.services.releases.start(u, r.id)
            }
            runBlocking {
                withTimeout(20_000) { while (sw.services.releaseStore.get(r.id)!!.status != ReleaseStatus.READY) delay(20) }
            }
            assertTrue(sw.git.reads.contains("origin/main:.agern/sandbox.yml"), sw.git.reads.toString())
            assertTrue(sw.git.reads.none { it.startsWith("origin/release/") }, sw.git.reads.toString())
        } finally {
            sw.close()
        }
    }

    @Test
    fun `an existing member branch or a label changed after planning holds the issue and the build goes on`() {
        synchronized(gh) { gh.branches.getOrPut("demo-shop-web") { mutableSetOf() } += "release/h" }
        val r0 = release("h", listOf(2, 3))
        runBlocking { s.releases.start(alice, r0.id) }
        val r = awaitRelease(r0.id, "settled") { it.status == ReleaseStatus.READY }
        val held = r.issues.single { it.number == 2 }
        assertEquals(ReleaseIssueState.HELD, held.state)
        assertEquals("Could not start a run: Branch release/h already exists in test-org/demo-shop-web; delete it or rename the release.", held.note)
        assertEquals(ReleaseIssueState.MERGED, r.issues.single { it.number == 3 }.state)

        // demo-workspace: the label of #2 is removed after planning.
        val y0 = release("y", listOf(1, 2), repo = "demo-workspace")
        synchronized(gh) { gh.repos.getValue("demo-workspace").issues.replaceAll { if (it.number == 2) it.copy(labels = emptyList<GhLabel>()) else it } }
        runBlocking { s.releases.start(alice, y0.id) }
        val y = awaitRelease(y0.id, "settled") { it.status == ReleaseStatus.READY }
        assertEquals(ReleaseIssueState.MERGED, y.issues.single { it.number == 1 }.state)
        val h2 = y.issues.single { it.number == 2 }
        assertEquals(ReleaseIssueState.HELD, h2.state)
        assertTrue(h2.note!!.startsWith("Could not start a run: Issue #2 has no member label."), h2.note)
        // Held: incomplete, so no release pull request anywhere.
        assertTrue(gh.actions.none { it.startsWith("pr demo-ws-app release/y") }, gh.actions.toString())
    }

    @Test
    fun `a conflict or a pending check in one member keeps the whole release back`() {
        // A conflict in one member: not announced, approval refused with nothing merged.
        synchronized(gh) { gh.conflicting += "demo-shop-web:release/c" }
        val c = readyRelease("c", listOf(1, 2))
        assertEquals(ReleaseMergeState.CONFLICT, row(c, "demo-shop-web").mergeState)
        assertFalse(s.releaseStore.get(c.id)!!.readyAnnounced)
        val e = assertFailsWith<ApiException> { runBlocking { s.releases.approve(alice, c.id) } }
        assertEquals("release_conflict", e.code)
        assertEquals("`release/c` of test-org/demo-shop-web no longer merges cleanly into `main`. Nothing was merged.", e.message)
        assertTrue(gh.actions.none { it.startsWith("merge-") }, gh.actions.toString())
        assertTrue(comments("demo-shop-web", row(c, "demo-shop-web").prNumber!!).any { it.startsWith("**Agern:** `release/c` of test-org/demo-shop-web no longer merges cleanly") })

        // Automatic: waits while one member's check is pending, merges once all are green.
        gh.setStatus("demo-shop-api", "release/a", "ci", "pending")
        val apiIssue = gh.addIssue(ws, "Rate limits", "x", listOf("demo-shop-api")).number
        val a = readyRelease("a", listOf(apiIssue, gh.addIssue(ws, "Docs", "x").number), mode = ReleaseMode.AUTOMATIC)
        assertEquals(ReleaseStatus.READY, s.releaseStore.get(a.id)!!.status)
        gh.setStatus("demo-shop-api", "release/a", "ci", "failure")
        runBlocking { s.releaseMerger.tick() }
        val failed = s.releaseStore.get(a.id)!!
        assertEquals(ReleaseStatus.READY, failed.status)
        assertTrue(failed.notice!!.startsWith("Checks failed on pull request test-org/demo-shop-api#${row(failed, "demo-shop-api").prNumber}"), failed.notice)
        assertTrue(merges("demo-shop-api").isEmpty())
        gh.setStatus("demo-shop-api", "release/a", "ci", "success")
        runBlocking { s.releaseMerger.tick() }
        val done = awaitRelease(a.id, "released automatically") { it.status == ReleaseStatus.RELEASED }
        assertTrue(done.releasedAutomatically)
        assertEquals(row(done, "demo-shop-api").mergeSha, gh.gitlinks["$ws:main"]!!["services/api"])
    }

    // ------------------------------------------------------------ partial failure

    @Test
    fun `a failure after the first merge stops partway, reports per repository and continues on the next approval`() {
        val r = readyRelease("pf", listOf(1, 2, 3))
        val api = row(r, "demo-shop-api")
        val web = row(r, "demo-shop-web")
        gh.failNextMerges["demo-shop-web"] = 1
        val e = assertFailsWith<ApiException> { runBlocking { s.releases.approve(alice, r.id) } }
        assertEquals("release_partially_merged", e.code)
        assertTrue(e.message.startsWith("Releasing `pf` stopped partway. Merged: test-org/demo-shop-api#${api.prNumber} (`main` at `sha-main-"), e.message)
        assertTrue(e.message.contains("Not merged: test-org/demo-shop-web#${web.prNumber}: Merging pull request test-org/demo-shop-web#${web.prNumber} failed"), e.message)
        assertTrue(e.message.contains("test-org/demo-shop#${r.prNumber}: not attempted"), e.message)
        assertTrue(e.message.contains("left open for a human"), e.message)
        val partly = s.releaseStore.get(r.id)!!
        assertEquals(ReleaseStatus.READY, partly.status)
        assertTrue(partly.partlyReleased)
        assertEquals(e.message, partly.notice)
        for ((repo, n) in listOf("demo-shop-web" to web.prNumber!!, ws to r.prNumber!!)) {
            assertEquals(1, comments(repo, n).count { it.startsWith("**Agern:** releasing `pf` stopped partway.") }, comments(repo, n).toString())
        }
        assertEquals("0".repeat(39) + "1", gh.gitlinks["$ws:main"]!!["services/api"])
        // Nothing can be added or retried meanwhile.
        val extra = gh.addIssue(ws, "Late", "x").number
        assertEquals("release_partially_merged", code { s.releases.assign(alice, ws, listOf(extra), r.id) })

        // A restart does not merge anything again.
        s.releaseMerger.recoverAfterRestart()
        runBlocking { delay(300); s.releaseMerger.tick() }
        assertEquals(1, merges("demo-shop-api").size)
        assertTrue(s.releaseStore.get(r.id)!!.notice!!.startsWith("Releasing `pf` stopped partway."))

        val released = runBlocking { s.releases.approve(alice, r.id) }
        assertEquals(ReleaseStatus.RELEASED, released.status, released.notice)
        assertEquals(1, merges("demo-shop-api").size, gh.actions.toString())
        assertEquals(1, merges("demo-shop-web").size, gh.actions.toString())
        assertEquals(row(released, "demo-shop-web").mergeSha, gh.gitlinks["$ws:main"]!!["web"])
    }

    @Test
    fun `a pointer the guard refuses leaves the workspace pull request open`() {
        val r = readyRelease("g", listOf(1, 3))
        // Only the workspace release branch moves a gitlink to a commit the submodule remote lacks, once the members are merged.
        world.git.gitlinks = { _, head ->
            val now = s.releaseStore.get(r.id)!!
            if (head == "release/g" && now.repos.all { it.merged }) {
                listOf(dk.egernet.factory.git.GitlinkChange("services/api", "a".repeat(40), "https://github.invalid/api.git"))
            } else emptyList()
        }
        world.git.remoteCommit = { _, _ -> false }
        val e = assertFailsWith<ApiException> { runBlocking { s.releases.approve(alice, r.id) } }
        assertEquals("release_partially_merged", e.code)
        assertTrue(e.message.contains("Submodule commit missing"), e.message)
        assertEquals(1, merges("demo-shop-api").size)
        assertTrue(merges(ws).isEmpty())
        assertEquals("open", gh.pullMeta[r.prNumber!!]!!.state)
        assertEquals("0".repeat(39) + "1", gh.gitlinks["$ws:main"]!!["services/api"])
    }

    @Test
    fun `an automatic release that stopped partway is only continued by check again`() {
        // The workspace pull request's pending check holds the automatic merge until the member merge is set to fail.
        gh.setStatus(ws, "release/ap", "ci", "pending")
        val r0 = readyRelease("ap", listOf(1, 2), mode = ReleaseMode.AUTOMATIC)
        assertTrue(merges("demo-shop-api").isEmpty())
        gh.failNextMerges["demo-shop-web"] = 1
        gh.setStatus(ws, "release/ap", "ci", "success")
        runBlocking { s.releaseMerger.tick() }
        val partly = awaitRelease(r0.id, "partly released") { it.partlyReleased }
        assertEquals(ReleaseStatus.READY, partly.status)
        assertEquals(1, merges("demo-shop-api").size)
        runBlocking { s.releaseMerger.tick() }
        assertTrue(merges("demo-shop-web").isEmpty(), gh.actions.toString())
        assertEquals(ReleaseStatus.READY, s.releaseStore.get(r0.id)!!.status)
        val done = runBlocking { s.releases.check(alice, r0.id) }
        assertEquals(ReleaseStatus.RELEASED, done.status, done.notice)
        assertEquals(1, merges("demo-shop-api").size)
    }

    @Test
    fun `member pull requests merged on GitHub are recorded and the rest needs approval`() {
        val r = readyRelease("gh", listOf(1, 2, 3))
        gh.mergeOnGitHub(row(r, "demo-shop-api").prNumber!!)
        runBlocking { s.releases.check(alice, r.id) }
        val after = s.releaseStore.get(r.id)!!
        assertNotNull(row(after, "demo-shop-api").mergedAt)
        assertEquals(ReleaseStatus.READY, after.status)
        val released = runBlocking { s.releases.approve(alice, r.id) }
        assertEquals(ReleaseStatus.RELEASED, released.status, released.notice)
        assertTrue(merges("demo-shop-api").isEmpty(), "merged on GitHub, not by Agern")
        assertEquals(row(released, "demo-shop-api").mergeSha, gh.gitlinks["$ws:main"]!!["services/api"])

        // Every pull request merged on GitHub: released without an approval.
        val all = readyRelease("all", listOf(gh.addIssue(ws, "Only web", "x", listOf("demo-shop-web")).number))
        gh.mergeOnGitHub(row(all, "demo-shop-web").prNumber!!)
        gh.mergeOnGitHub(all.prNumber!!)
        runBlocking { s.releaseMerger.tick() }
        val done = awaitRelease(all.id, "released on GitHub") { it.status == ReleaseStatus.RELEASED }
        assertNull(done.releasedByUsername)
    }

    // ------------------------------------------------------------ cancel and delete

    @Test
    fun `cancel deletes the release branch in every repository, and delete retries what failed`() {
        val r = readyRelease("x", listOf(1, 2, 3))
        synchronized(gh) { gh.failDeleteBranch += "demo-shop-web:release/x" }
        val cancelled = runBlocking { s.releases.cancel(alice, r.id) }
        assertEquals(ReleaseStatus.CANCELLED, cancelled.status)
        val after = awaitRelease(r.id, "branches deleted") { it.notice != null }
        assertTrue("delete-branch $ws release/x" in gh.actions && "delete-branch demo-shop-api release/x" in gh.actions, gh.actions.toString())
        assertTrue(after.notice!!.startsWith("Cancelled, but could not delete `release/x` in demo-shop-web: "), after.notice)
        assertTrue(after.branchDeleted && row(after, "demo-shop-api").branchDeleted && !row(after, "demo-shop-web").branchDeleted)
        val text = comments("demo-shop-api", row(r, "demo-shop-api").prNumber!!)
        assertTrue(text.any { it.startsWith("**Agern:** release `x` was cancelled in Agern by @alice. Agern will not merge this pull request. The branch `release/x` is deleted in every repository") }, text.toString())

        synchronized(gh) { gh.failDeleteBranch.clear() }
        runBlocking { s.releases.delete(alice, r.id) }
        assertTrue("delete-branch demo-shop-web release/x" in gh.actions, gh.actions.toString())
        assertNull(s.releaseStore.get(r.id))

        // A single-repository release keeps its branch and pull request on cancel.
        val single = readyRelease("keep", listOf(gh.addIssue("demo-app", "Single", "x").number), repo = "demo-app")
        runBlocking { s.releases.cancel(alice, single.id) }
        assertTrue(gh.actions.none { it == "delete-branch demo-app release/keep" })
        assertTrue(single.toDto().repositories.isEmpty())
    }

    // ------------------------------------------------------------ .agern/workspace.yml

    @Test
    fun `a workspace yml release opens and merges one pull request per member and none in the workspace`() {
        val r = readyRelease("w", listOf(2, 1), repo = "demo-workspace")
        val tools = row(r, "demo-ws-tools")
        val app = row(r, "demo-ws-app")
        assertEquals("dev", app.baseBranch)
        assertTrue("pr demo-ws-app release/w -> dev #${app.prNumber}" in gh.actions, gh.actions.toString())
        assertTrue("pr demo-ws-tools release/w -> main #${tools.prNumber}" in gh.actions, gh.actions.toString())
        assertTrue(gh.actions.none { it.startsWith("pr demo-workspace release/w") }, gh.actions.toString())
        assertNull(r.prNumber)
        assertFalse(r.projectTouched)
        assertEquals(listOf("demo-ws-tools", "demo-ws-app", "demo-workspace"), r.toDto().repositories.map { it.repo })
        val released = runBlocking { s.releases.approve(alice, r.id) }
        assertEquals(ReleaseStatus.RELEASED, released.status, released.notice)
        assertEquals(
            listOf("merge-rebase demo-ws-tools #${tools.prNumber}", "merge-rebase demo-ws-app #${app.prNumber}"),
            gh.actions.filter { it.startsWith("merge-rebase ") },
        )
        assertTrue(gh.actions.none { it.startsWith("gitlink-commit") }, gh.actions.toString())
        assertTrue("delete-branch demo-workspace release/w" in gh.actions && "delete-branch demo-ws-app release/w" in gh.actions, gh.actions.toString())
        assertTrue(comments("demo-workspace", 1).any { it.contains("was rebased onto `dev`") }, comments("demo-workspace", 1).toString())
    }

    // ------------------------------------------------------------ visibility

    @Test
    fun `a user who cannot read a member sees its entry without pull request details`() {
        val r = readyRelease("v", listOf(1, 5), repo = "demo-workspace")
        val bob = world.user("bob")
        val dto = r.toDto().forViewer { s.access.canSee(bob, it) }
        val lab = dto.repositories.single { it.repo == "demo-ws-lab" }
        assertFalse(lab.accessible)
        assertNull(lab.prNumber)
        assertNull(lab.prUrl)
        assertNull(lab.checks)
        assertEquals("lab", lab.directory)
        assertNull(dto.issues.single { it.number == 5 }.prUrl)
        assertTrue(dto.repositories.single { it.repo == "demo-ws-app" }.accessible)
        assertNotNull(dto.repositories.single { it.repo == "demo-ws-app" }.prNumber)
        // Alice sees everything.
        assertEquals(r.toDto(), r.toDto().forViewer { s.access.canSee(alice, it) })
    }
}
