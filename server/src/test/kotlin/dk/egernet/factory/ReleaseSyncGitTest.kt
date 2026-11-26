package dk.egernet.factory

import dk.egernet.factory.agent.AgentIdentity
import dk.egernet.factory.db.ReleaseRecord
import dk.egernet.factory.git.CliGitOps
import dk.egernet.factory.git.PushRejectedException
import dk.egernet.factory.github.ClaudeCredential
import dk.egernet.factory.pipeline.MigrationCheck
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Bringing a release up to date with the real git CLI (docs/ARCHITECTURE.md §6 "Bringing a release up to date"). */
class ReleaseSyncGitTest {
    private val tmp: Path = Files.createTempDirectory("agern-release-sync-git")
    private val dir = "server/src/main/resources/db/migration"

    @AfterTest fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun gitIn(cwd: Path, vararg args: String, home: Path = tmp): String {
        val pb = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false") + args)
            .directory(cwd.toFile()).redirectErrorStream(true)
        pb.environment()["HOME"] = home.toString()
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${args.joinToString(" ")} failed: $out" }
        return out
    }

    private fun exit(cwd: Path, vararg args: String): Int {
        val pb = ProcessBuilder(listOf("git") + args).directory(cwd.toFile()).redirectErrorStream(true)
        pb.environment()["HOME"] = tmp.toString()
        val p = pb.start()
        p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS))
        return p.exitValue()
    }

    private fun write(repo: Path, path: String, text: String) {
        val f = repo.resolve(path)
        Files.createDirectories(f.parent)
        Files.writeString(f, text)
    }

    private fun sha(repo: Path, ref: String) = gitIn(repo, "rev-parse", ref).trim()

    /**
     * A world whose ready release `x` has `V2__mine.sql` (and, with [conflict], a README change) on its local branch while
     * `main` gained `V2__theirs.sql` (and a conflicting README change). [syncBuilder] is what the sync builder does in
     * its worktree.
     */
    private fun scenario(conflict: Boolean, syncBuilder: (Path) -> Unit, test: (TestWorld, ReleaseRecord, Path) -> Unit) {
        val world = TestWorld(twoFactor = false, realGit = true)
        try {
            val s = world.services
            s.releaseMerger.mergeablePollMillis = 20
            val alice = world.user("alice")
            val main = s.workspaces.mainDir("alice", "demo-app")
            world.launcher.stage = { p ->
                val prompt = p.spec.prompt.orEmpty()
                when (p.spec.kind) {
                    "spec-writer" -> { p.init(); p.result("## Spec\nDo it."); p.exit(0) }
                    "builder" -> {
                        p.init()
                        if (prompt.startsWith("Bring the release branch")) syncBuilder(p.spec.workDir)
                        else {
                            write(p.spec.workDir, "feature.txt", "feature\n")
                            gitIn(p.spec.workDir, "add", "-A"); gitIn(p.spec.workDir, "commit", "-q", "-m", "feature")
                        }
                        p.result("Done."); p.exit(0)
                    }
                    "approver" -> { p.init(); p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") }); p.exit(0) }
                    else -> {
                        p.init()
                        p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
                        p.exit(0)
                    }
                }
            }
            val n = world.github.addIssue("demo-app", "Feature", "body").number
            val r0 = runBlocking {
                val r = s.releases.create(alice, "demo-app", "x", "")
                s.releases.assign(alice, "demo-app", listOf(n), r.id)
                s.releases.start(alice, r.id)
            }
            awaitRelease(world, r0.id) { it.status == ReleaseStatus.READY && it.prNumber != null }
            // The release's own content on the local release branch (the fake merges only in memory)…
            gitIn(main, "checkout", "-q", "release/x")
            write(main, "$dir/V2__mine.sql", "select 2;\n")
            if (conflict) write(main, "README.md", "release line\n")
            gitIn(main, "add", "-A"); gitIn(main, "commit", "-q", "-m", "release work")
            // …and what reached main meanwhile.
            gitIn(main, "checkout", "-q", "main")
            write(main, "$dir/V2__theirs.sql", "select 22;\n")
            if (conflict) write(main, "README.md", "main line\n") else write(main, "other.txt", "other\n")
            gitIn(main, "add", "-A"); gitIn(main, "commit", "-q", "-m", "hotfix")
            val r = runBlocking { s.releases.check(alice, r0.id) }
            assertTrue(r.behindBy == 1 && r.migrationConflict && r.syncNeeded, r.toString())
            test(world, r, main)
        } finally {
            world.close()
        }
    }

    private fun awaitRelease(world: TestWorld, id: java.util.UUID, cond: (ReleaseRecord) -> Boolean): ReleaseRecord = runBlocking {
        withTimeout(15_000) {
            while (true) {
                val r = world.services.releaseLocks.of(id).withLock { world.services.releaseStore.get(id)!! }
                if (cond(r)) return@withTimeout r
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE") error("not reached")
        }
    }

    private fun sync(world: TestWorld, r: ReleaseRecord) = runBlocking {
        world.services.releases.sync(world.services.users.byUsername("alice")!!, r.id)
    }.run

    @Test
    fun `a merge with resolved conflicts and a renumbered migration is pushed to the release branch`() = scenario(
        conflict = true,
        syncBuilder = { wt ->
            // The merge stops on README.md; both sides are kept, then the release's migration is renumbered.
            assertTrue(exit(wt, "-c", "user.name=T", "-c", "user.email=t@e.invalid", "merge", "--no-ff", "main") != 0)
            write(wt, "README.md", "main line\nrelease line\n")
            gitIn(wt, "add", "README.md"); gitIn(wt, "commit", "-q", "--no-edit")
            gitIn(wt, "mv", "$dir/V2__mine.sql", "$dir/V3__mine.sql"); gitIn(wt, "commit", "-q", "-m", "Renumber V2__mine to V3")
        },
    ) { world, r, main ->
        val oldTip = sha(main, "release/x")
        val mainTip = sha(main, "main")
        val run = world.awaitRun(sync(world, r).id, RunStatus.TERMINAL, 20_000)
        assertEquals(RunStatus.MERGED, run.status, run.error)
        val tip = sha(main, "release/x")
        assertEquals(tip, run.syncPushedSha)
        assertEquals(0, exit(main, "merge-base", "--is-ancestor", oldTip, "release/x"))
        assertEquals(0, exit(main, "merge-base", "--is-ancestor", mainTip, "release/x"))
        assertTrue(gitIn(main, "rev-list", "--merges", "$oldTip..release/x").isNotBlank(), "a merge commit, not a rebase")
        assertEquals(mainTip, sha(main, "main"), "main is unchanged")
        // scripts/check-migrations.sh main passes in a checkout of the release branch.
        val script = listOf(Path.of("..", "scripts", "check-migrations.sh"), Path.of("scripts", "check-migrations.sh")).first { Files.isRegularFile(it) }
        val co = tmp.resolve("check")
        gitIn(main, "worktree", "add", "-q", "--detach", co.toString(), "release/x")
        val p = ProcessBuilder("bash", script.toAbsolutePath().toString(), "main").directory(co.toFile()).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        assertEquals(0, p.exitValue(), out)
        gitIn(main, "worktree", "remove", "--force", co.toString())
        // Re-evaluated: up to date and without a migration conflict.
        val after = awaitRelease(world, r.id) { it.behindBy == 0 && !it.migrationConflict }
        assertFalse(after.syncNeeded)
        assertEquals(ReleaseStatus.READY, after.status)
    }

    @Test
    fun `a merge that keeps the colliding migration is not pushed`() = scenario(
        conflict = false,
        syncBuilder = { wt -> gitIn(wt, "merge", "--no-ff", "-q", "main", "-m", "Merge main") },
    ) { world, r, main ->
        val oldTip = sha(main, "release/x")
        val run = world.awaitRun(sync(world, r).id, RunStatus.TERMINAL, 20_000)
        assertEquals(RunStatus.FAILED, run.status)
        assertTrue(run.error!!.contains("database migration conflict: `$dir/V2__mine.sql` has the same version (2) as `$dir/V2__theirs.sql`"), run.error)
        assertTrue(run.error!!.contains("Nothing was pushed."), run.error)
        assertEquals(oldTip, sha(main, "release/x"))
    }

    @Test
    fun `a rebase instead of a merge is not pushed`() = scenario(
        conflict = false,
        syncBuilder = { wt -> gitIn(wt, "rebase", "-q", "main") },
    ) { world, r, main ->
        val oldTip = sha(main, "release/x")
        val run = world.awaitRun(sync(world, r).id, RunStatus.TERMINAL, 20_000)
        assertEquals(RunStatus.FAILED, run.status)
        assertTrue(run.error!!.contains("history was rewritten"), run.error)
        assertEquals(oldTip, sha(main, "release/x"))
    }

    // ------------------------------------------------------------ CliGitOps against a local bare remote

    @Test
    fun `behind counts, merge states and fast-forward-only pushes`() {
        val home = tmp.resolve("home").also { Files.createDirectories(it) }
        val git = CliGitOps(System.getenv("PATH") ?: "/usr/bin:/bin", "https://github.invalid/", localOnly = false)
        val id = AgentIdentity("alice", "Alice", "alice", null, ClaudeCredential("oauth_token", "x"))
        gitIn(tmp, "init", "-q", "--bare", "-b", "main", tmp.resolve("remote.git").toString())
        val up = tmp.resolve("up")
        gitIn(tmp, "clone", "-q", tmp.resolve("remote.git").toString(), up.toString())
        gitIn(up, "checkout", "-q", "-b", "main")
        write(up, "a.txt", "a\n"); gitIn(up, "add", "-A"); gitIn(up, "commit", "-q", "-m", "a")
        gitIn(up, "push", "-q", "origin", "main")
        gitIn(up, "push", "-q", "origin", "main:release/r")
        write(up, "b.txt", "b\n"); gitIn(up, "add", "-A"); gitIn(up, "commit", "-q", "-m", "b")
        write(up, "a.txt", "main a\n"); gitIn(up, "commit", "-q", "-am", "main a")
        gitIn(up, "push", "-q", "origin", "main")
        val clone = tmp.resolve("clone")
        gitIn(tmp, "clone", "-q", tmp.resolve("remote.git").toString(), clone.toString())
        val cmp = git.compareBranches(clone, "main", "release/r", home, null, MigrationCheck::rootOf)!!
        assertEquals(2, cmp.behind)

        // A worktree on the release branch with a conflicting change, merging main: the merge is unfinished.
        val wt = tmp.resolve("wt")
        git.createWorktree(id, clone, wt, "work", "release/r", home)
        write(wt, "a.txt", "release a\n"); gitIn(wt, "commit", "-q", "-am", "release a")
        gitIn(wt, "push", "-q", "origin", "HEAD:release/r")
        val start = sha(wt, "HEAD")
        assertTrue(exit(wt, "-c", "user.name=T", "-c", "user.email=t@e.invalid", "merge", "--no-ff", "origin/main") != 0)
        val unfinished = git.mergeState(wt, start, "origin/main", home)
        assertTrue(unfinished.unfinished && unfinished.containsStart && !unfinished.containsMerged, unfinished.toString())
        write(wt, "a.txt", "both\n"); gitIn(wt, "add", "a.txt"); gitIn(wt, "commit", "-q", "--no-edit")
        val done = git.mergeState(wt, start, "origin/main", home)
        assertTrue(!done.unfinished && done.containsStart && done.containsMerged, done.toString())
        assertEquals(0, git.compareWorktree(wt, "origin/main", home, MigrationCheck::rootOf)!!.behind)

        // The release branch moves meanwhile: the push is refused, nothing forced.
        gitIn(up, "fetch", "-q", "origin"); gitIn(up, "checkout", "-q", "-b", "rel", "origin/release/r")
        write(up, "c.txt", "c\n"); gitIn(up, "add", "-A"); gitIn(up, "commit", "-q", "-m", "c")
        gitIn(up, "push", "-q", "origin", "rel:release/r")
        val moved = sha(up, "HEAD")
        assertFailsWith<PushRejectedException> { git.pushFastForward(id, wt, "release/r", home) }
        assertEquals(moved, gitIn(tmp.resolve("remote.git"), "rev-parse", "release/r").trim())
        // Brought up to date again, it is a fast-forward.
        gitIn(wt, "fetch", "-q", "origin")
        gitIn(wt, "merge", "-q", "--no-ff", "origin/release/r", "-m", "Merge")
        git.pushFastForward(id, wt, "release/r", home)
        assertEquals(sha(wt, "HEAD"), gitIn(tmp.resolve("remote.git"), "rev-parse", "release/r").trim())
    }
}
