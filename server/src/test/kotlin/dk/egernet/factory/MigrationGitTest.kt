package dk.egernet.factory

import dk.egernet.factory.git.CliGitOps
import dk.egernet.factory.git.FileChange
import dk.egernet.factory.pipeline.MigrationCheck
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The git side of the migration guard ([CliGitOps.compareBranches]) with real git (fake-GitHub mode: local branches). */
class MigrationGitTest {
    private val tmp: Path = Files.createTempDirectory("agern-migration-git")
    private val dir = "server/src/main/resources/db/migration"

    @AfterTest fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun gitIn(cwd: Path, vararg args: String): String {
        val pb = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false") + args)
            .directory(cwd.toFile()).redirectErrorStream(true)
        pb.environment()["HOME"] = tmp.toString()
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${args.joinToString(" ")} failed: $out" }
        return out
    }

    private fun write(repo: Path, path: String, text: String) {
        val f = repo.resolve(path)
        Files.createDirectories(f.parent)
        Files.writeString(f, text)
    }

    @Test
    fun `compares the head's changes since the merge base with the base's tip`() {
        val repo = tmp.resolve("repo")
        Files.createDirectories(repo)
        gitIn(repo, "init", "-q", "-b", "main")
        write(repo, "$dir/V1__init.sql", "select 1;\n")
        write(repo, "$dir/V2__next.sql", "select 2;\n")
        write(repo, "README.md", "readme\n")
        gitIn(repo, "add", "-A"); gitIn(repo, "commit", "-q", "-m", "base")
        gitIn(repo, "checkout", "-q", "-b", "feature")
        write(repo, "$dir/V3__mine.sql", "select 3;\n")
        write(repo, "$dir/V1__init.sql", "select 1; -- edited\n")
        Files.delete(repo.resolve("$dir/V2__next.sql"))
        write(repo, "src/App.kt", "fun main() {}\n")
        gitIn(repo, "add", "-A"); gitIn(repo, "commit", "-q", "-m", "feature")
        // The base moves on after the fork: another branch merged V3 first.
        gitIn(repo, "checkout", "-q", "main")
        write(repo, "$dir/V3__theirs.sql", "select 33;\n")
        gitIn(repo, "add", "-A"); gitIn(repo, "commit", "-q", "-m", "theirs")

        val git = CliGitOps(System.getenv("PATH") ?: "/usr/bin:/bin", "https://github.invalid/", localOnly = true)
        val cmp = git.compareBranches(repo, "main", "feature", tmp.resolve("home"), null, MigrationCheck::rootOf)!!
        assertEquals(listOf("$dir/V1__init.sql", "$dir/V2__next.sql", "$dir/V3__theirs.sql"), cmp.baseFiles.sorted())
        assertEquals(
            setOf(FileChange('M', "$dir/V1__init.sql"), FileChange('D', "$dir/V2__next.sql"), FileChange('A', "$dir/V3__mine.sql")),
            cmp.changes.toSet(),
        )
        assertEquals(
            listOf(
                "`$dir/V1__init.sql` was changed", "`$dir/V2__next.sql` was removed",
                "`$dir/V3__mine.sql` has the same version (3) as `$dir/V3__theirs.sql`",
            ),
            MigrationCheck.problems(cmp.baseFiles, cmp.changes),
        )
        // Nothing in a migration directory changed: the base is not listed.
        gitIn(repo, "checkout", "-q", "-b", "docs", "main")
        write(repo, "README.md", "more\n")
        gitIn(repo, "commit", "-q", "-am", "docs")
        val none = git.compareBranches(repo, "main", "docs", tmp.resolve("home"), null, MigrationCheck::rootOf)!!
        assertEquals(emptyList(), none.baseFiles)
        assertEquals(emptyList(), none.changes)
        // A branch that does not exist, or no clone at all: no comparison.
        assertNull(git.compareBranches(repo, "main", "gone", tmp.resolve("home"), null, MigrationCheck::rootOf))
        assertNull(git.compareBranches(tmp.resolve("nothing"), "main", "feature", tmp.resolve("home"), null, MigrationCheck::rootOf))
    }

    @Test
    fun `a run whose branch adds a version the target gained meanwhile is not merged`() {
        val world = TestWorld(realGit = true)
        try {
            val s = world.services
            val alice = world.user("alice")
            val main = s.workspaces.mainDir("alice", "demo-app")
            world.launcher.stage = { p ->
                when (p.spec.kind) {
                    "spec-writer" -> { p.init(); p.result("## Spec\nDo the thing."); p.exit(0) }
                    "builder" -> {
                        p.init()
                        // Meanwhile another change lands V1 on the target; the builder picks V1 as well.
                        write(main, "$dir/V1__theirs.sql", "select 1;\n")
                        gitIn(main, "add", "-A"); gitIn(main, "commit", "-q", "-m", "theirs")
                        write(p.spec.workDir, "$dir/V1__mine.sql", "select 11;\n")
                        gitIn(p.spec.workDir, "add", "-A"); gitIn(p.spec.workDir, "commit", "-q", "-m", "mine")
                        p.result("Built it."); p.exit(0)
                    }
                    "approver" -> { p.init(); p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "fine") }); p.exit(0) }
                    else -> {
                        p.init()
                        p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
                        p.exit(0)
                    }
                }
            }
            val run = runBlocking { s.orchestrator.start(alice, "demo-app", 1, null) }
            val done = world.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
            assertEquals(RunStatus.NEEDS_HUMAN, done.status, done.error)
            assertTrue(
                done.error!!.startsWith("Database migration conflict with `main`: `$dir/V1__mine.sql` has the same version (1) as `$dir/V1__theirs.sql`."),
                done.error,
            )
            assertTrue(world.github.actions.none { it.startsWith("merge ") }, world.github.actions.toString())
            // The builder's prompt named the target and the rule.
            val prompt = world.launcher.byRole("builder").single().spec.prompt!!
            assertTrue(prompt.contains("Database migrations: the pull request merges into `main`."), prompt)
        } finally {
            world.close()
        }
    }
}
