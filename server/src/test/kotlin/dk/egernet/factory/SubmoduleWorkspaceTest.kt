package dk.egernet.factory

import dk.egernet.factory.git.CliGitOps
import dk.egernet.factory.workspace.GITMODULES_FILE
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A workspace built from git submodules (docs/ARCHITECTURE.md §5 "Workspace projects", §6 "Workspace runs"): a run on
 * a submodule target, with real git and local bare repositories as the remotes (the test home allows the `file`
 * protocol, which Agern itself never relaxes). The fake GitHub serves the same `.gitmodules` and takes the pull requests.
 */
class SubmoduleWorkspaceTest {
    private val remotes: Path = Files.createTempDirectory("agern-submodule-remotes")
    private val git = CliGitOps(System.getenv("PATH") ?: "/usr/bin:/bin", "https://github.invalid/", localOnly = false)
    private val world = TestWorld(gitOps = git)
    private val s = world.services
    private val alice = world.user("alice")
    private val ws = "demo-shop"
    private val home: Path = s.workspaces.home("alice").also {
        Files.createDirectories(it)
        Files.writeString(it.resolve(".gitconfig"), "[protocol \"file\"]\n\tallow = always\n")
    }
    private val gitmodules = """
        |[submodule "api"]
        |	path = services/api
        |	url = ../demo-shop-api.git
        |[submodule "web"]
        |	path = web
        |	url = ../demo-shop-web.git
        |""".trimMargin()

    /** The commit of demo-shop-api the workspace pins, and the newer tip of its main branch. */
    private lateinit var pinned: String
    private lateinit var apiTip: String

    init {
        for (name in listOf("demo-shop-api", "demo-shop-web")) {
            val up = repo(name, "$name.txt")
            if (name == "demo-shop-api") pinned = gitIn(up, "rev-parse", "HEAD").trim()
        }
        val parent = repo(ws, "README.md")
        Files.writeString(parent.resolve(".gitmodules"), gitmodules)
        gitIn(parent, "add", ".gitmodules")
        // Gitlinks written with plumbing: the workspace pins the first commit of each member.
        gitIn(parent, "update-index", "--add", "--cacheinfo", "160000,$pinned,services/api")
        gitIn(parent, "update-index", "--add", "--cacheinfo", "160000,${gitIn(remotes.resolve("demo-shop-web-up"), "rev-parse", "HEAD").trim()},web")
        gitIn(parent, "commit", "-q", "-m", "Add the submodules")
        gitIn(parent, "push", "-q", "origin", "main")
        // demo-shop-api moves on: a run must start from its main branch, not from the pinned commit.
        val api = remotes.resolve("demo-shop-api-up")
        Files.writeString(api.resolve("newer.txt"), "newer\n")
        gitIn(api, "add", "newer.txt")
        gitIn(api, "commit", "-q", "-m", "A newer commit")
        gitIn(api, "push", "-q", "origin", "main")
        apiTip = gitIn(api, "rev-parse", "HEAD").trim()
        for (name in listOf(ws, "demo-shop-api", "demo-shop-web")) world.github.cloneUrls[name] = remotes.resolve("$name.git").toString()
        world.github.setFile(ws, GITMODULES_FILE, gitmodules)
    }

    @AfterTest fun tearDown() {
        world.close()
        remotes.toFile().deleteRecursively()
    }

    private fun gitIn(cwd: Path, vararg args: String): String {
        val pb = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false") + args)
            .directory(cwd.toFile()).redirectErrorStream(true)
        pb.environment()["HOME"] = home.toString()
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${args.joinToString(" ")} in $cwd failed: $out" }
        return out
    }

    /** A bare repository `<name>.git` with its working clone `<name>-up` holding [file]; returns the working clone. */
    private fun repo(name: String, file: String): Path {
        gitIn(remotes, "init", "-q", "--bare", "-b", "main", remotes.resolve("$name.git").toString())
        val up = remotes.resolve("$name-up")
        gitIn(remotes, "clone", "-q", remotes.resolve("$name.git").toString(), up.toString())
        gitIn(up, "checkout", "-q", "-b", "main")
        Files.writeString(up.resolve(file), "$file\n")
        gitIn(up, "add", file)
        gitIn(up, "commit", "-q", "-m", "Add $file")
        gitIn(up, "push", "-q", "origin", "main")
        return up
    }

    /** What the builder saw, by key. */
    private val seen = ConcurrentHashMap<String, String>()

    private fun script(approver: String = "APPROVE") {
        world.launcher.stage = { p ->
            p.init()
            when (p.spec.kind) {
                "spec-writer" -> p.result("## Spec\nDo it.")
                "builder" -> {
                    val target = p.spec.workDir.resolve("services/api")
                    seen["branch"] = gitIn(target, "rev-parse", "--abbrev-ref", "HEAD").trim()
                    seen["base"] = gitIn(target, "rev-parse", "HEAD").trim()
                    seen["web"] = gitIn(p.spec.workDir.resolve("web"), "rev-parse", "HEAD").trim()
                    seen["prompt"] = p.spec.prompt.orEmpty()
                    Files.writeString(target.resolve("health.txt"), "ok\n")
                    gitIn(target, "add", "health.txt")
                    gitIn(target, "commit", "-q", "-m", "Add a health endpoint")
                    p.result("Built it.")
                }
                "approver" -> p.result(null, buildJsonObject { put("verdict", approver); put("summary", "approver: $approver") })
                else -> p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
            }
            p.exit(0)
        }
    }

    private fun start() = runBlocking { s.orchestrator.start(alice, ws, 1, null) }
    private fun await(id: UUID) = world.awaitRun(id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN, timeoutMs = 60_000)
    private fun main(): Path = s.workspaces.mainDir("alice", ws)
    private fun runDir(id: UUID) = s.workspaces.runDir("alice", ws, id)
    private fun localBranches(dir: Path) = gitIn(dir, "branch", "--format=%(refname:short)").lines().map { it.trim() }

    @Test
    fun `a run changes the target submodule from its base branch and opens the pull request in the member repository`() {
        script()
        val run = start()
        val done = await(run.id)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertEquals("demo-shop-api", done.targetRepo)
        assertEquals("services/api", done.targetDir)
        val branch = done.branch!!
        // The target was on the run branch from origin/main of the member (not the pinned commit); the other
        // submodule stayed at the commit the workspace pins.
        assertEquals(branch, seen["branch"])
        assertEquals(apiTip, seen["base"])
        assertEquals(gitIn(remotes.resolve("demo-shop-web-up"), "rev-parse", "HEAD").trim(), seen["web"])
        assertTrue(seen["prompt"]!!.contains("`services/api` is a git submodule of test-org/demo-shop"), seen["prompt"])
        assertTrue(seen["prompt"]!!.contains("- `web` — test-org/demo-shop-web @ the commit the workspace pins (submodule)"), seen["prompt"])
        // Pushed to the member repository, pull request there, issue in the workspace.
        val pushed = gitIn(remotes.resolve("demo-shop-api.git"), "rev-parse", "refs/heads/$branch").trim()
        assertEquals(apiTip, gitIn(remotes.resolve("demo-shop-api.git"), "rev-parse", "$pushed~1").trim())
        assertTrue(world.github.actions.any { it.startsWith("pr demo-shop-api $branch -> main") }, world.github.actions.toString())
        assertTrue(world.github.actions.contains("close demo-shop#1"), world.github.actions.toString())
        // The pointer bump: a pull request in the workspace repository, merged.
        assertTrue(world.github.actions.any { it.startsWith("gitlink-commit demo-shop services/api ") }, world.github.actions.toString())
        assertTrue(world.github.actions.any { it.startsWith("pr demo-shop factory/bump-services-api-") }, world.github.actions.toString())
        // The target's commits are the run's own: no "commits inside submodules" warning, nothing bundled.
        val builder = s.runs.stages(run.id).last { it.role == "builder" }.sessionId!!
        val events = s.events.list(builder, 0, 1000).joinToString("\n") { it.payload.toString() }
        assertFalse(events.contains("Commits inside submodules"), events)
        assertFalse(Files.exists(s.workspaces.bundleDir("alice", ws, run.id)))
        // Nothing is left behind: no run directory, no worktrees in the submodule's clone, the merged branch is gone.
        assertFalse(Files.exists(runDir(run.id)))
        assertEquals(1, git.listWorktrees(main().resolve("services/api"), home).size)
        assertFalse(localBranches(main().resolve("services/api")).contains(branch))
        // The workspace clone still pins the old commit (the bump goes through GitHub) and reports no change.
        assertEquals("", gitIn(main(), "status", "--porcelain").trim())
    }

    @Test
    fun `an unlabelled issue is a run of the workspace repository with the submodules as context`() {
        val n = world.github.addIssue(ws, "Update the plan", "The plan is out of date.").number
        world.launcher.stage = { p ->
            p.init()
            when (p.spec.kind) {
                "spec-writer" -> p.result("## Spec\nDo it.")
                "builder" -> {
                    val root = p.spec.workDir
                    seen["api"] = gitIn(root.resolve("services/api"), "rev-parse", "HEAD").trim()
                    Files.writeString(root.resolve("PLAN.md"), "plan\n")
                    gitIn(root, "add", "PLAN.md")
                    gitIn(root, "commit", "-q", "-m", "Update the plan")
                    p.result("Built it.")
                }
                "approver" -> p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") })
                else -> p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
            }
            p.exit(0)
        }
        val run = runBlocking { s.orchestrator.start(alice, ws, n, null) }
        val done = await(run.id)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertEquals(null, done.targetRepo)
        // The submodules were fetched at the commit the workspace pins; the branch went to the workspace repository.
        assertEquals(pinned, seen["api"])
        val pushed = gitIn(remotes.resolve("$ws.git"), "rev-parse", "refs/heads/${done.branch}").trim()
        assertEquals("Update the plan", gitIn(remotes.resolve("$ws.git"), "log", "-1", "--format=%s", pushed).trim())
        assertTrue(world.github.actions.any { it.startsWith("pr $ws ${done.branch} -> main") }, world.github.actions.toString())
        assertFalse(Files.exists(runDir(run.id)))
    }

    @Test
    fun `the run's layout while it waits for a human, and clean-up`() {
        script(approver = "ESCALATE")
        val run = start()
        val done = await(run.id)
        assertEquals(RunStatus.NEEDS_HUMAN, done.status, done.error)
        val root = runDir(run.id)
        assertEquals("HEAD", gitIn(root, "rev-parse", "--abbrev-ref", "HEAD").trim())
        assertEquals(done.branch, gitIn(root.resolve("services/api"), "rev-parse", "--abbrev-ref", "HEAD").trim())
        // The target is a worktree of the workspace clone's submodule checkout, the other submodule a checkout of the root.
        assertTrue(git.listWorktrees(main().resolve("services/api"), home).any { it.branch == done.branch })
        assertTrue(Files.exists(root.resolve("web/demo-shop-web.txt")))
        runBlocking { s.orchestrator.cancel(alice, run.id) }
        assertFalse(Files.exists(root))
        assertEquals(1, git.listWorktrees(main().resolve("services/api"), home).size)
    }

    @Test
    fun `unpushed work in the target submodule survives the clean-up`() {
        script()
        val failing = FailingPushGit(git)
        failing.failPush = "remote rejected"
        val w = TestWorld(gitOps = failing)
        try {
            val h = w.services.workspaces.home("alice")
            Files.createDirectories(h)
            Files.writeString(h.resolve(".gitconfig"), "[protocol \"file\"]\n\tallow = always\n")
            for (name in listOf(ws, "demo-shop-api", "demo-shop-web")) w.github.cloneUrls[name] = remotes.resolve("$name.git").toString()
            w.github.setFile(ws, GITMODULES_FILE, gitmodules)
            w.launcher.stage = world.launcher.stage
            val a = w.user("alice")
            val run = runBlocking { w.services.orchestrator.start(a, ws, 1, null) }
            val failed = w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN, timeoutMs = 60_000)
            assertEquals(RunStatus.FAILED, failed.status)
            assertEquals(FailedStep.PUSH, failed.failedStep)
            assertFalse(Files.exists(w.services.workspaces.runDir("alice", ws, run.id)))
            // The run branch with its commit is kept in the submodule's clone of the workspace checkout.
            val clone = w.services.workspaces.mainDir("alice", ws).resolve("services/api")
            assertTrue(localBranches(clone).contains(failed.branch), localBranches(clone).toString())
        } finally {
            w.close()
        }
    }
}
