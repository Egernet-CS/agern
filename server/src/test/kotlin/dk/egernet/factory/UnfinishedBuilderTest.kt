package dk.egernet.factory

import dk.egernet.factory.agent.Prompts
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A builder whose session ends unfinished (no commits, uncommitted changes) is resumed to finish and commit, and a run
 * that fails anyway keeps its uncommitted work as a local commit (docs/ARCHITECTURE.md §6 "Worktrees and branches").
 * Real git in a temp dir (fake-GitHub mode: local clone, pushes recorded by [FailingPushGit]).
 */
class UnfinishedBuilderTest {
    private val world = TestWorld(realGit = true, twoFactor = false)
    private val s = world.services
    private val alice = world.user("alice")
    private val git = world.realGitOps!!
    private val main: Path get() = s.workspaces.mainDir("alice", "demo-app")
    private val home: Path get() = s.workspaces.home("alice")

    @AfterTest fun tearDown() {
        world.close()
    }

    /** One builder launch: what it does in its worktree. Runs [FakeProcess.init] before and [FakeProcess.exit] after. */
    private fun interface Step { fun run(p: FakeProcess, dir: Path) }

    /** The builder launches of an issue title, in order (the first build, then its resumes); later launches commit and finish. */
    private val scripts = ConcurrentHashMap<String, List<Step>>()
    private val launchesOf = ConcurrentHashMap<String, Int>()
    /** The issue title of each Claude session id a builder reported, so a resumed builder finds its script. */
    private val titleOf = ConcurrentHashMap<String, String>()
    /** The Claude session id each builder launch reported, in launch order. */
    private val claudeIds = CopyOnWriteArrayList<String>()
    /** `git log --format=%s` of each builder's worktree when it started, and its files. */
    private val builderLogs = CopyOnWriteArrayList<List<String>>()
    private val builderFiles = CopyOnWriteArrayList<Set<String>>()

    init {
        world.launcher.stage = { p ->
            when (p.spec.kind) {
                "spec-writer" -> { p.init(); p.result("## Spec\nBuild it."); p.exit(0) }
                "builder" -> {
                    val title = p.spec.resumeClaudeSessionId?.let { titleOf[it] }
                        ?: Regex("Issue #\\d+: (.+)").find(p.spec.prompt ?: "")?.groupValues?.get(1)?.trim() ?: ""
                    val claude = p.spec.resumeClaudeSessionId ?: "claude-${UUID.randomUUID()}"
                    titleOf[claude] = title
                    claudeIds += claude
                    p.init(claude)
                    val dir = p.spec.workDir
                    builderLogs += gitIn(dir, "log", "--format=%s").lines().filter { it.isNotBlank() }
                    builderFiles += Files.list(dir).use { files -> files.map { it.fileName.toString() }.toList().toSet() }
                    val n = launchesOf.merge(title, 1, Int::plus)!! - 1
                    val step = scripts[title]?.getOrNull(n) ?: Step { proc, d ->
                        commit(d, "done-$n.txt", "feature finished")
                        proc.result("Built it.")
                    }
                    step.run(p, dir)
                    p.exit(0)
                }
                "approver" -> { p.init(); p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") }); p.exit(0) }
                else -> {
                    p.init()
                    p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
                    p.exit(0)
                }
            }
        }
    }

    private fun gitIn(dir: Path, vararg args: String): String {
        val pb = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid") + args)
            .directory(dir.toFile()).redirectErrorStream(true)
        pb.environment()["HOME"] = home.toString()
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${args.joinToString(" ")} failed: $out" }
        return out
    }

    private fun commit(dir: Path, file: String, message: String) {
        Files.writeString(dir.resolve(file), "$message\n")
        gitIn(dir, "add", "-A")
        gitIn(dir, "commit", "-q", "-m", message)
    }

    private fun issue(title: String, vararg steps: Step): Int {
        scripts[title] = steps.toList()
        return world.github.addIssue("demo-app", title, "body").number
    }

    private fun start(n: Int) = runBlocking { s.orchestrator.start(alice, "demo-app", n, null) }
    private fun await(id: UUID) = world.awaitRun(id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN, 20_000)
    private fun options(id: UUID) = runBlocking { s.factory.retryOptions(alice, id) }
    private fun retry(id: UUID, mode: String?) = runBlocking { s.factory.retryRun(alice, id, mode) }
    private fun builders() = world.launcher.byRole("builder")
    private fun localBranches() = gitIn(main, "branch", "--format=%(refname:short)").lines().filter { it.isNotBlank() }

    /** Side effects follow status changes asynchronously: wait for [cond]. */
    private fun eventually(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!cond()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    /** The texts of the `warn` system events of agent session [sid]. */
    private fun warnings(sid: UUID) = s.events.list(sid, 0, 5000)
        .filter { it.type == "system" && (it.payload as JsonObject)["level"]?.jsonPrimitive?.content == "warn" }
        .map { (it.payload as JsonObject)["text"]!!.jsonPrimitive.content }

    private val stillRunning = Step { p, dir ->
        Files.writeString(dir.resolve("group-a.txt"), "group A\n")
        p.result("Groups A and B are done and their diffs look right. Two migration agents are still running; " +
            "I'll merge their results when they finish.")
    }
    private val idle = Step { p, _ -> p.result("Two agents are still running.") }

    @Test
    fun `a builder that ends with uncommitted work is resumed, and its finished work goes on to review`() {
        val n = issue("Delegated refactor", stillRunning, Step { p, dir ->
            commit(dir, "group-b.txt", "Refactor the templates")
            p.result("Refactored all templates.\n\nDetails follow.")
        })
        val done = await(start(n).id)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertEquals(2, builders().size)
        val resumed = builders()[1].spec
        assertEquals(claudeIds[0], resumed.resumeClaudeSessionId)
        val prompt = resumed.prompt.orEmpty()
        assertTrue(prompt.startsWith("Message from Agern") && prompt.contains("the branch has no commits and the working tree has uncommitted changes"), prompt)
        // The resumed builder saw what the first one left, and committed it.
        assertTrue("group-a.txt" in builderFiles[1], builderFiles[1].toString())
        val stage = s.runs.stages(done.id).single { it.role == "builder" && it.round == 0 }
        assertEquals("done", stage.status)
        assertEquals("Refactored all templates.", stage.summary)
        assertEquals(resumed.sessionId, stage.sessionId)
        assertEquals("Refactored all templates.\n\nDetails follow.", done.builderSummary)
        val first = builders()[0].spec.sessionId
        assertEquals(first, s.sessions.get(resumed.sessionId)!!.parentSessionId)
        assertEquals(
            listOf("The builder ended its turn with no commits and uncommitted changes; resuming it to finish and commit (attempt 1 of 2)."),
            warnings(first),
        )
        assertEquals(listOf(done.branch), git.pushes.toList())
    }

    @Test
    fun `a builder that never commits its work fails after two resumes, and the work is saved for Continue`() {
        val n = issue("Never finished", stillRunning, idle, idle)
        val failed = await(start(n).id)
        assertEquals(RunStatus.FAILED, failed.status)
        assertEquals(FailureKind.OTHER, failed.failureKind)
        assertEquals(3, builders().size)
        val error = failed.error!!
        assertTrue(error.startsWith("The builder made no commits on ${failed.branch} and left uncommitted changes, also after being asked " +
            "to finish 2 time(s). The builder said: Two agents are still running."), error)
        assertTrue(error.contains("saved as commit `") && error.contains("on the local branch `${failed.branch}` (not pushed)"), error)
        assertTrue(error.endsWith("retry the run with \"Continue\" to go on from it."), error)
        val stage = s.runs.stages(failed.id).single { it.role == "builder" }
        assertEquals("failed", stage.status)
        assertEquals("No commits on the branch; uncommitted changes left. Two agents are still running.", stage.summary)
        // Each resume was announced on the session before it; the last one also carries the saved commit.
        assertEquals(1, warnings(builders()[0].spec.sessionId).size)
        assertTrue(warnings(builders()[1].spec.sessionId).single().endsWith("(attempt 2 of 2)."))
        assertTrue(warnings(builders()[2].spec.sessionId).single().contains("saved as commit"))

        // The WIP commit is on the local branch, by Agern, with the file; nothing was pushed and the worktree is gone.
        val branch = failed.branch!!
        assertTrue(branch in localBranches(), localBranches().toString())
        val subject = gitIn(main, "log", "-1", "--format=%an|%s", branch).trim()
        assertEquals("Agern|WIP: uncommitted work of Agern run ${failed.id}, saved when the run failed", subject)
        val body = gitIn(main, "log", "-1", "--format=%B", branch)
        assertTrue(body.contains("The run failed with: The builder made no commits on $branch and left uncommitted changes"), body)
        assertTrue(body.contains("This commit was made by Agern, not by the builder."), body)
        assertTrue(error.contains("`${gitIn(main, "rev-parse", "--short=7", branch).trim()}`"), error)
        assertEquals(listOf("group-a.txt"), gitIn(main, "show", "--name-only", "--format=", branch).lines().filter { it.isNotBlank() })
        assertFalse(Files.exists(s.workspaces.runDir("alice", "demo-app", failed.id)))
        assertTrue(git.pushes.isEmpty(), git.pushes.toString())
        eventually("failure comment") { world.github.actions.any { it.contains("failed:") && it.contains("saved as commit") } }

        val opts = options(failed.id)
        assertEquals(RetryMode.CONTINUE, opts.recommended, opts.reason)
        assertEquals(1, opts.commits)
        val next = retry(failed.id, RetryMode.CONTINUE)
        val done = await(UUID.fromString(next.id))
        assertEquals(RunStatus.MERGED, done.status, done.error)
        // The continuing builder started from the saved commit.
        assertEquals("WIP: uncommitted work of Agern run ${failed.id}, saved when the run failed", builderLogs[3].first())
        assertTrue("group-a.txt" in builderFiles[3], builderFiles[3].toString())
    }

    @Test
    fun `a builder that commits but leaves stray files is resumed twice, then the run goes on with a warning`() {
        val n = issue("Stray files", Step { p, dir ->
            commit(dir, "feature.txt", "Add the feature")
            Files.writeString(dir.resolve("scratch.txt"), "notes\n")
            p.result("Added the feature.")
        }, idle, idle)
        val done = await(start(n).id)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertEquals(3, builders().size)
        assertTrue(builders()[1].spec.prompt.orEmpty().contains(": the working tree has uncommitted changes."), builders()[1].spec.prompt)
        assertTrue(warnings(builders()[0].spec.sessionId).single().startsWith("The builder ended its turn with uncommitted changes;"))
        assertTrue(
            "The builder left uncommitted changes in the worktree; they are not part of the pull request." in warnings(builders()[2].spec.sessionId),
            warnings(builders()[2].spec.sessionId).toString(),
        )
        // Merged runs save nothing.
        assertTrue(done.error.isNullOrEmpty(), done.error)
    }

    @Test
    fun `a builder that finishes cleanly is never resumed`() {
        val done = await(start(issue("Clean build")).id)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertEquals(1, builders().size)
        assertNull(builders()[0].spec.resumeClaudeSessionId)
    }

    @Test
    fun `a builder that fails with uncommitted work has it saved on top of its commits`() {
        val n = issue("Crashing builder", Step { p, dir ->
            commit(dir, "part-1.txt", "feature part 1")
            Files.writeString(dir.resolve("part-2.txt"), "half done\n")
            p.result("Something went badly wrong", isError = true)
        })
        val failed = await(start(n).id)
        assertEquals(RunStatus.FAILED, failed.status)
        assertEquals(1, builders().size)
        assertTrue(failed.error!!.startsWith("builder failed:"), failed.error)
        assertTrue(failed.error!!.contains("saved as commit"), failed.error)
        val branch = failed.branch!!
        assertEquals(
            listOf("WIP: uncommitted work of Agern run ${failed.id}, saved when the run failed", "feature part 1"),
            gitIn(main, "log", "-2", "--format=%s", branch).lines().filter { it.isNotBlank() },
        )
        assertEquals(listOf("part-2.txt"), gitIn(main, "show", "--name-only", "--format=", branch).lines().filter { it.isNotBlank() })
        assertEquals(RetryMode.CONTINUE, options(failed.id).recommended)
    }

    @Test
    fun `the builder and spec-writer prompts forbid ending with background work in flight`() {
        val builder = Prompts("test-org").role("builder")
        assertTrue(builder.contains("## Subagents and background work") && builder.contains("session ends when your turn ends"), builder)
        assertTrue(builder.contains("run_in_background") && builder.contains("background"), builder)
        val specWriter = Prompts("test-org").role("spec-writer")
        assertTrue(specWriter.contains("session ends") && specWriter.contains("background"), specWriter)
    }
}
