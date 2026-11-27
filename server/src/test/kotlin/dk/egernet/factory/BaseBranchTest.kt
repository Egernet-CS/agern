package dk.egernet.factory

import dk.egernet.factory.db.RunRecord
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.sandbox.SANDBOX_FILE
import dk.egernet.factory.sandbox.SandboxDefinitions
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The per-project base branch (docs/ARCHITECTURE.md §6 "Base branch"), with the fake launcher and fake GitHub. */
class BaseBranchTest {
    private val world = TestWorld(twoFactor = false)
    private val s = world.services
    private val gh = world.github
    private val alice = world.user("alice")
    private val root = world.user("root", admin = true)

    @AfterTest fun tearDown() = world.close()

    init {
        world.git.deleteWorktrees = true
        gh.branches.getOrPut("demo-app") { mutableSetOf() } += "dev"
    }

    private fun setBase(branch: String?, policy: String = MergePolicy.AUTOMATIC) =
        s.projectSettings.put("demo-app", ProjectSettingsDto(policy, baseBranch = branch), root.id)

    private fun script(gate: CountDownLatch? = null) {
        world.launcher.stage = { p ->
            p.init()
            when (p.spec.kind) {
                "spec-writer" -> p.result("## Spec\nDo the thing.")
                "builder" -> p.result("Built it.")
                "approver" -> {
                    gate?.await(10, TimeUnit.SECONDS)
                    p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") })
                }
                else -> p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
            }
            p.exit(0)
        }
    }

    private fun start(issue: Int = 1, by: UserRecord = alice) = runBlocking { s.orchestrator.start(by, "demo-app", issue, null) }
    private fun finished(run: RunRecord) = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
    private fun comments(issue: Int = 1) = gh.repos.getValue("demo-app").comments[issue].orEmpty().mapNotNull { it.body }
    private fun issueState(issue: Int) = gh.repos.getValue("demo-app").issues.single { it.number == issue }.state
    private fun prTitle(issue: Int) = gh.repos.getValue("demo-app").issues.single { it.number == issue }.title

    // ------------------------------------------------------------ settings API

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String): HttpClient {
        val c = createClient { install(HttpCookies) }
        assertEquals(200, c.post("/api/auth/login") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
        }.status.value)
        c.put("/api/me/github-token") { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"token":"fake-$user"}""") }
        return c
    }

    private suspend fun HttpClient.putSettings(body: String): HttpResponse = put("/api/projects/demo-app/settings") {
        header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody(body)
    }

    private fun json(t: String) = AppJson.parseToJsonElement(t).jsonObject
    private fun JsonObject.base() = this["settings"]!!.jsonObject["baseBranch"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content

    @Test
    fun `the base branch is admin-only, validated, checked on GitHub and a full replacement`() = testApplication {
        application { agernModule(s) }
        val admin = login("root", "pw-r")
        val user = login("alice", "pw-a")
        assertNull(json(user.get("/api/projects/demo-app").bodyAsText()).base(), "unset by default")

        val ok = admin.putSettings("""{"mergePolicy":"automatic","baseBranch":" dev "}""")
        assertEquals(200, ok.status.value, ok.bodyAsText())
        assertEquals("dev", json(ok.bodyAsText()).base())
        assertEquals("dev", s.projectSettings.get("demo-app").baseBranch)
        assertEquals("dev", json(user.get("/api/projects/demo-app").bodyAsText()).base(), "shown to everyone")
        val listed = AppJson.parseToJsonElement(user.get("/api/projects").bodyAsText()).jsonArray.map { it.jsonObject }
            .single { it["name"]!!.jsonPrimitive.content == "demo-app" }
        assertEquals("dev", listed.base())

        // The default branch's name, blank, null and omitted all mean "the default branch".
        for (body in listOf(""""baseBranch":"main"""", """"baseBranch":"  """", """"baseBranch":null""", null)) {
            admin.putSettings("""{"mergePolicy":"automatic","baseBranch":"dev"}""")
            val r = admin.putSettings("""{"mergePolicy":"automatic"${body?.let { ",$it" }.orEmpty()}}""")
            assertEquals(200, r.status.value, r.bodyAsText())
            assertNull(json(r.bodyAsText()).base(), body)
            assertNull(s.projectSettings.get("demo-app").baseBranch, body)
        }

        admin.putSettings("""{"mergePolicy":"automatic","baseBranch":"dev"}""")
        for (bad in listOf(
            "-dev", "dev branch", "a..b", "a//b", "dev/", "dev.", "dev.lock", "a/.hidden", ".hidden", "factory/issue-1", "dev`x`",
            "dev;rm", "dev\$x", "x".repeat(201), "ä",
        )) {
            val r = admin.putSettings(buildJsonObject { put("mergePolicy", "automatic"); put("baseBranch", bad) }.toString())
            assertEquals(400, r.status.value, "$bad ${r.bodyAsText()}")
            assertTrue(json(r.bodyAsText())["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.startsWith("baseBranch:"), r.bodyAsText())
        }
        val missing = admin.putSettings("""{"mergePolicy":"github","baseBranch":"nope"}""")
        assertEquals(422, missing.status.value)
        assertEquals("base_branch_not_found", json(missing.bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertTrue(missing.bodyAsText().contains("Branch `nope` does not exist in test-org/demo-app."), missing.bodyAsText())
        assertEquals(ProjectSettingsDto(MergePolicy.AUTOMATIC, baseBranch = "dev"), s.projectSettings.get("demo-app"), "nothing stored")

        assertEquals(403, user.putSettings("""{"mergePolicy":"automatic","baseBranch":"dev"}""").status.value)
    }

    // ------------------------------------------------------------ runs

    @Test
    fun `a run on the base branch starts from it, opens its pull request against it and merges into it`() {
        setBase("dev")
        script()
        val started = start()
        assertEquals("dev", started.baseBranch)
        val run = finished(started)
        assertEquals(RunStatus.MERGED, run.status, run.error)
        assertEquals("dev", run.baseBranch)
        assertTrue("${run.branch} <- dev" in world.git.worktrees, world.git.worktrees.toString())
        assertTrue(gh.actions.any { it == "pr demo-app ${run.branch} -> dev #${run.prNumber}" }, gh.actions.toString())
        assertTrue(gh.actions.any { it.startsWith("merge") && it.endsWith("#${run.prNumber}") }, gh.actions.toString())
        val title = prTitle(1)
        assertTrue(gh.commitsOf("demo-app", "dev").any { it.contains(title) }, gh.commitsOf("demo-app", "dev").toString())
        assertTrue(gh.commitsOf("demo-app", "main").none { it.contains(title) }, gh.commitsOf("demo-app", "main").toString())
        assertEquals("closed", issueState(1))
        assertTrue(comments().any { it == "**Agern: merged** pull request #${run.prNumber} into `dev` (approved by the approver agent)." }, comments().toString())
        assertEquals("dev", s.runs.toDto(run).baseBranch)

        // The builder's prompt names the base, but is not told about a release.
        val prompt = world.launcher.byRole("builder").last().spec.prompt!!
        assertFalse(prompt.contains("The release is later merged"), prompt)
    }

    @Test
    fun `a project without a base branch runs on the default branch as before`() {
        script()
        val run = finished(start())
        assertEquals(RunStatus.MERGED, run.status, run.error)
        assertEquals("main", run.baseBranch)
        assertTrue("${run.branch} <- main" in world.git.worktrees, world.git.worktrees.toString())
        assertTrue(gh.actions.any { it == "pr demo-app ${run.branch} -> main #${run.prNumber}" }, gh.actions.toString())
        assertTrue(comments().any { it == "**Agern: merged** pull request #${run.prNumber} (approved by the approver agent)." }, comments().toString())
        assertEquals("closed", issueState(1))
    }

    @Test
    fun `a base branch that no longer exists refuses the start and creates nothing`() {
        setBase("dev")
        gh.branches.getValue("demo-app").remove("dev")
        script()
        val e = assertFailsWith<ApiException> { start() }
        assertEquals(409, e.status.value)
        assertEquals("base_branch_not_found", e.code)
        assertEquals(
            "The base branch `dev` does not exist in test-org/demo-app. Create it on GitHub, or ask an admin to change the project's base branch.",
            e.message,
        )
        assertTrue(s.runs.list("demo-app", activeOnly = false).isEmpty())
        assertTrue(world.git.worktrees.isEmpty(), world.git.worktrees.toString())
    }

    @Test
    fun `changing the base branch mid-run does not move the run`() {
        // dev → cleared while the approver works: the run stays on dev.
        setBase("dev")
        val gate = CountDownLatch(1)
        script(gate)
        val a = start(issue = 1)
        world.awaitRun(a.id, setOf(RunStatus.APPROVING))
        setBase(null)
        gate.countDown()
        val doneA = finished(a)
        assertEquals(RunStatus.MERGED, doneA.status, doneA.error)
        assertEquals("dev", doneA.baseBranch)
        assertTrue(gh.actions.any { it == "pr demo-app ${doneA.branch} -> dev #${doneA.prNumber}" }, gh.actions.toString())
        assertTrue(gh.commitsOf("demo-app", "dev").any { it.contains(prTitle(1)) })

        // Unset → dev while the approver works: the run stays on main.
        val gate2 = CountDownLatch(1)
        script(gate2)
        val b = start(issue = 2)
        world.awaitRun(b.id, setOf(RunStatus.APPROVING))
        setBase("dev")
        gate2.countDown()
        val doneB = finished(b)
        assertEquals(RunStatus.MERGED, doneB.status, doneB.error)
        assertEquals("main", doneB.baseBranch)
        assertTrue(gh.actions.any { it == "pr demo-app ${doneB.branch} -> main #${doneB.prNumber}" }, gh.actions.toString())
        assertTrue(gh.commitsOf("demo-app", "main").any { it.contains(prTitle(2)) })
        assertTrue(gh.commitsOf("demo-app", "dev").none { it.contains(prTitle(2)) })
    }

    // ------------------------------------------------------------ merged on GitHub

    @Test
    fun `a pull request merged on GitHub into the base branch closes the issue, on the default branch it does not`() {
        setBase("dev", MergePolicy.GITHUB)
        script()
        val onDev = finished(start(issue = 1))
        assertEquals(RunStatus.READY, onDev.status, onDev.error)
        setBase(null, MergePolicy.GITHUB)
        val onMain = finished(start(issue = 2))
        assertEquals(RunStatus.READY, onMain.status, onMain.error)
        assertEquals("main", onMain.baseBranch)

        gh.mergeOnGitHub(onDev.prNumber!!, by = "carol")
        gh.mergeOnGitHub(onMain.prNumber!!, by = "carol")
        val before = gh.actions.size
        runBlocking { s.orchestrator.followOnce() }
        assertEquals(RunStatus.MERGED, s.runs.get(onDev.id)!!.status)
        assertEquals(RunStatus.MERGED, s.runs.get(onMain.id)!!.status)
        assertEquals("closed", issueState(1))
        assertTrue(
            comments(1).any {
                it == "**Agern:** pull request #${onDev.prNumber} was merged into `dev` on GitHub by @carol. Closing this issue: " +
                    "GitHub closes issues itself only for merges into the default branch (`main`)."
            },
            comments(1).toString(),
        )
        val after = gh.actions.drop(before)
        assertTrue(after.contains("close demo-app#1"), after.toString())
        assertFalse(after.any { it.startsWith("comment demo-app#2") || it == "close demo-app#2" }, after.toString())
        assertEquals("open", issueState(2), "GitHub's own Closes #N handles the default branch")
    }
}

/** A run on a non-default base branch reads its sandbox definitions from the default branch, like a release run. */
class BaseBranchSandboxTest {
    private val world = TestWorld(twoFactor = false, sandbox = true)
    private val s = world.services
    private val alice = world.user("alice")
    private val root = world.user("root", admin = true)
    private val definition = "image: docker.io/library/eclipse-temurin:21-jdk\nsetup: echo setup\n"

    @AfterTest fun tearDown() = world.close()

    @Test
    fun `the sandbox definition of a run on dev comes from the default branch`() {
        world.github.branches.getOrPut("demo-app") { mutableSetOf() } += "dev"
        s.projectSettings.put("demo-app", ProjectSettingsDto(baseBranch = "dev"), root.id)
        world.sandboxFile(definition)
        runBlocking { s.sandboxes.approve(root, "demo-app", SandboxDefinitions.sha256(definition)) }
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
        val run = world.awaitRun(runBlocking { s.orchestrator.start(alice, "demo-app", 1, null) }.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, run.status, run.error)
        assertEquals("dev", run.baseBranch)
        assertEquals("approved", s.runs.toDto(run).sandbox!!.state)
        assertTrue(world.git.worktrees.single().endsWith("<- dev"), world.git.worktrees.toString())
        assertEquals(listOf("origin/main:$SANDBOX_FILE", "origin/main:${dk.egernet.factory.sandboxvm.SANDBOX_VM_FILE}"), world.git.reads.toList())
    }
}
