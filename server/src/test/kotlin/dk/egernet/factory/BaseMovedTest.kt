package dk.egernet.factory

import dk.egernet.factory.db.UpdateStatus
import dk.egernet.factory.db.UpdateTrigger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Keeping a run's branch up to date with its base branch (#35, docs/ARCHITECTURE.md §6 "Retrying a run", "Update and
 * merge") with the real git CLI in fake-GitHub mode: the base branch is the local `main` of the owner's clone, which the
 * tests move by committing to it, as a merge of another pull request would.
 */
class BaseMovedTest {
    private val world = TestWorld(twoFactor = false, realGit = true)
    private val s = world.services
    private val alice = world.user("alice")
    private val main: Path get() = s.workspaces.mainDir("alice", "demo-app")
    private val home: Path = world.dataDir.resolve("test-home")
    private val dir = "server/src/main/resources/db/migration"

    /** The first build of a run (the builder commits in [Path], its worktree). */
    @Volatile private var onBuild: (Path) -> Unit = { wt -> commit(wt, "feature.txt", "feature\n", "Feature") }
    /** An update round's builder: its worktree and prompt. */
    @Volatile private var onUpdate: (Path, String) -> Unit = { _, _ -> }
    /** Every approver: its working directory and how many approvers ran before. */
    @Volatile private var onApprover: (Path, Int) -> Unit = { _, _ -> }
    @Volatile private var escalateFirst = false
    private val approvers = AtomicInteger()
    private val updatePrompts = CopyOnWriteArrayList<String>()
    /** The run's HEAD when each approver ran, and whether it had a merge commit then. */
    private val approverHeads = CopyOnWriteArrayList<String>()

    @AfterTest fun tearDown() {
        world.close()
    }

    init {
        world.launcher.stage = { p ->
            val prompt = p.spec.prompt.orEmpty()
            p.init()
            when (p.spec.kind) {
                "spec-writer" -> p.result("## Spec\nDo it.")
                "builder" -> {
                    if (prompt.contains("This is update round")) {
                        updatePrompts += prompt
                        onUpdate(p.spec.workDir, prompt)
                        p.result("Merged and resolved.")
                    } else {
                        onBuild(p.spec.workDir)
                        p.result("Built it.")
                    }
                }
                "approver" -> {
                    val n = approvers.getAndIncrement()
                    approverHeads += gitIn(p.spec.workDir, "rev-parse", "HEAD").trim()
                    onApprover(p.spec.workDir, n)
                    val verdict = if (escalateFirst && n == 0) "ESCALATE" else "APPROVE"
                    p.result(null, buildJsonObject { put("verdict", verdict); put("summary", "decided") })
                }
                else -> p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
            }
            p.exit(0)
        }
    }

    private fun gitIn(cwd: Path, vararg args: String): String {
        Files.createDirectories(home)
        val pb = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false") + args)
            .directory(cwd.toFile()).redirectErrorStream(true)
        pb.environment()["HOME"] = home.toString()
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${args.joinToString(" ")} failed: $out" }
        return out
    }

    private fun commit(repo: Path, file: String, text: String, message: String) {
        val f = repo.resolve(file)
        Files.createDirectories(f.parent)
        Files.writeString(f, text)
        gitIn(repo, "add", file)
        gitIn(repo, "commit", "-q", "-m", message)
    }

    /** What another pull request merged into `main` meanwhile looks like: one squash commit. */
    private fun moveBase(file: String, text: String, subject: String) = commit(main, file, text, subject)

    private fun start(issue: Int = 1) = runBlocking { s.orchestrator.start(alice, "demo-app", issue, null) }

    /** Waits until no job works on the run and it has stopped (merged, needs a human, …). */
    private fun settled(id: UUID): dk.egernet.factory.db.RunRecord {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (s.orchestrator.job(id) != null || s.runs.get(id)!!.status !in RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN) {
            check(System.nanoTime() < deadline) { "timed out: ${s.runs.get(id)}" }
            Thread.sleep(20)
        }
        return s.runs.get(id)!!
    }

    private fun merges() = world.github.actions.filter { it.startsWith("merge demo-app") }

    /** Files in commit [sha] of the owner's clone. */
    private fun filesAt(sha: String) = gitIn(main, "ls-tree", "-r", "--name-only", sha).lines().filter { it.isNotBlank() }.toSet()
    private fun mergeCommits(sha: String) = gitIn(main, "rev-list", "--merges", "--count", sha).trim().toInt()

    @Test
    fun `a base that moved without a conflict is merged in before the approver, and the run merges both changes`() {
        onBuild = { wt ->
            commit(wt, "feature.txt", "feature\n", "Feature")
            moveBase("other.txt", "other\n", "Other change (#77)")
        }
        val run = settled(start().id)
        assertEquals(RunStatus.MERGED, run.status, run.error)
        assertEquals(1, run.updateRounds)
        val u = s.runs.updates(run.id).single()
        assertEquals(UpdateTrigger.BASE_MOVED, u.trigger)
        assertEquals(UpdateStatus.RESOLVED, u.status)
        assertEquals("merged", u.mergeStatus)
        assertEquals("`main` moved on (1 new commit) before the approver ran", u.reason)
        assertEquals(listOf(77), u.pulls.map { it.number })
        assertEquals(1, updatePrompts.size, "the builder verified the merged result")
        // The round's four reviewers and its approver; the approver ran once, on the merged result.
        assertEquals(listOf("builder") + REVIEWER_ROLES + "approver", s.runs.stages(run.id).filter { it.round == u.round }.map { it.role })
        assertEquals(1, approvers.get())
        val head = approverHeads.single()
        assertEquals(setOf("README.md", "feature.txt", "other.txt"), filesAt(head))
        assertEquals(1, mergeCommits(head), "the branch's history has a merge commit of the target")
        assertEquals(1, merges().size)
        assertEquals(run.maxRounds, s.runs.toDto(run).maxRounds)
    }

    @Test
    fun `a conflict is resolved by the resumed builder, the reviewers run again and the run merges`() {
        onBuild = { wt ->
            commit(wt, "shared.txt", "mine\n", "Feature")
            moveBase("shared.txt", "theirs\n", "Theirs (#77)")
        }
        onUpdate = { wt, _ ->
            assertTrue(Files.exists(wt.resolve(".git")))
            gitIn(wt, "rev-parse", "-q", "--verify", "MERGE_HEAD")
            Files.writeString(wt.resolve("shared.txt"), "mine\ntheirs\n")
            gitIn(wt, "add", "shared.txt")
            gitIn(wt, "commit", "-q", "--no-edit")
        }
        val run = settled(start().id)
        assertEquals(RunStatus.MERGED, run.status, run.error)
        val u = s.runs.updates(run.id).single()
        assertEquals("conflict", u.mergeStatus)
        assertEquals(listOf("shared.txt"), u.conflicts)
        val prompt = updatePrompts.single()
        assertTrue(prompt.contains("conflicts in these files:\n- `shared.txt`\n"), prompt)
        assertTrue(prompt.contains("<merged_pull_requests>\n#77 Theirs (#77)\n---\n</merged_pull_requests>"), prompt)
        assertTrue(s.runs.stages(run.id).filter { it.round == u.round && it.role in REVIEWER_ROLES }.all { it.status == "done" })
        val head = approverHeads.single()
        assertEquals("mine\ntheirs\n", gitIn(main, "show", "$head:shared.txt"))
        assertEquals(1, merges().size)
    }

    @Test
    fun `a builder that leaves the merge unfinished leaves the run needing a human with files and pull requests named`() {
        var before = ""
        onBuild = { wt ->
            commit(wt, "shared.txt", "mine\n", "Feature")
            before = gitIn(wt, "rev-parse", "HEAD").trim()
            moveBase("shared.txt", "theirs\n", "Theirs (#77)")
        }
        val run = settled(start().id)
        assertEquals(RunStatus.NEEDS_HUMAN, run.status)
        assertTrue(run.error!!.startsWith("Update round 1 of 2 with `main` did not finish: the builder left the merge unfinished."), run.error)
        assertTrue(run.error!!.contains("Conflicting files: `shared.txt`. Merged into `main` meanwhile: #77 Theirs \\(\\#77\\)."), run.error)
        assertEquals(FailureKind.MERGE_CONFLICT, run.failureKind)
        assertTrue(run.updateAvailable)
        val wt = s.workspaces.runDir("alice", "demo-app", run.id)
        assertEquals(before, gitIn(wt, "rev-parse", "HEAD").trim(), "the worktree is back where it was")
        assertEquals("", gitIn(wt, "status", "--porcelain", "--untracked-files=no"))
        assertEquals(emptyList(), merges())
        assertEquals(0, approvers.get())
    }

    @Test
    fun `a base that moves again before the merge gets another round, up to the limit, then the owner decides`() {
        onBuild = { wt ->
            commit(wt, "feature.txt", "feature\n", "Feature")
            moveBase("one.txt", "1\n", "One (#71)")
        }
        // Each round's approver sees the target move once more before the merge.
        onApprover = { _, n -> moveBase("after-$n.txt", "$n\n", "After approver $n (#${80 + n})") }
        val run = settled(start().id)
        assertEquals(RunStatus.NEEDS_HUMAN, run.status)
        assertEquals(2, run.updateRounds)
        assertEquals(
            listOf("`main` moved on (1 new commit) before the approver ran", "`main` moved on (1 new commit) before the merge"),
            s.runs.updates(run.id).map { it.reason },
        )
        assertTrue(run.error!!.startsWith("Not merged: `main` moved on again (1 new commit(s)) and the limit of 2 update round(s) is reached"), run.error)
        assertEquals(emptyList(), merges())
        onApprover = { _, _ -> }
        val approved = runBlocking { s.orchestrator.approve(alice, run.id) }
        assertEquals(RunStatus.MERGED, approved.status, approved.error)
        assertEquals(1, merges().size)
    }

    @Test
    fun `approving an escalated run whose base moved with a conflict updates it first, then it merges`() {
        escalateFirst = true
        onBuild = { wt -> commit(wt, "shared.txt", "mine\n", "Feature") }
        onApprover = { _, n -> if (n == 0) moveBase("shared.txt", "theirs\n", "Theirs (#77)") }
        onUpdate = { wt, _ ->
            Files.writeString(wt.resolve("shared.txt"), "mine\ntheirs\n")
            gitIn(wt, "add", "shared.txt")
            gitIn(wt, "commit", "-q", "--no-edit")
        }
        val run = settled(start().id)
        assertEquals(RunStatus.NEEDS_HUMAN, run.status)
        assertEquals("The approver escalated: decided", run.error)
        val answered = runBlocking { s.factory.approveRun(alice, run.id) }
        assertEquals(RunStatus.BUILDING, answered.status)
        assertEquals(UpdateTrigger.BASE_MOVED, answered.updates.single().trigger)
        val done = settled(run.id)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertEquals("conflict", s.runs.updates(run.id).single().mergeStatus)
        assertEquals(1, merges().size)
    }

    @Test
    fun `two runs adding the same migration version - the second is renumbered in its update round and merges`() {
        // The first run's migration reached main while the second was built (as the first run's merge does).
        onBuild = { wt ->
            commit(wt, "$dir/V2__b.sql", "select 2;\n", "Add b")
            moveBase("$dir/V2__a.sql", "select 1;\n", "Add a (#5)")
        }
        onUpdate = { wt, prompt ->
            assertTrue(prompt.contains("`$dir/V2__b.sql` → version 3 (`V3__b.sql`); `$dir/V2__a.sql` on `main` keeps version 2"), prompt)
            gitIn(wt, "mv", "$dir/V2__b.sql", "$dir/V3__b.sql")
            gitIn(wt, "commit", "-q", "-m", "Renumber the migration")
        }
        val run = settled(start().id)
        assertEquals(RunStatus.MERGED, run.status, run.error)
        val u = s.runs.updates(run.id).single()
        assertEquals(listOf("`$dir/V2__b.sql` has the same version (2) as `$dir/V2__a.sql`"), u.migrations)
        assertEquals(setOf("README.md", "$dir/V2__a.sql", "$dir/V3__b.sql"), filesAt(approverHeads.single()))
        assertFalse(merges().isEmpty())
    }
}
