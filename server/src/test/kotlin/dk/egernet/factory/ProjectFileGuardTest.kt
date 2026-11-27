package dk.egernet.factory

import dk.egernet.factory.git.CliGitOps
import dk.egernet.factory.git.NewBlob
import dk.egernet.factory.pipeline.ProjectFileGuard
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure matching of the push guard and its texts. */
class ProjectFileGuardTest {
    private val p = Path.of("/x")

    @Test
    fun `only candidates whose size matches a new blob are hashed, once per content`() {
        val blobs = listOf(NewBlob("a".repeat(40), 10, "x.bin"), NewBlob("b".repeat(40), 20, "y.bin"))
        val c = listOf(
            ProjectFileGuard.Candidate("one", 10, "1".repeat(64), p), ProjectFileGuard.Candidate("dup", 10, "1".repeat(64), p),
            ProjectFileGuard.Candidate("two", 30, "2".repeat(64), p),
        )
        assertEquals(listOf("one"), ProjectFileGuard.toHash(blobs, c).map { it.name })
    }

    @Test
    fun `matches by git object id, lists every path, and the texts`() {
        val blobs = listOf(NewBlob("a".repeat(40), 10, "lib/renamed.zip"), NewBlob("c".repeat(40), 11 shl 20, "big.dat"))
        val c = listOf(ProjectFileGuard.Candidate("vendor/sdk.zip", 10, "1".repeat(64), p))
        val m = ProjectFileGuard.matches(blobs, c, mapOf("1".repeat(64) to "A".repeat(40)))
        assertEquals(listOf(ProjectFileGuard.Match("vendor/sdk.zip", "1".repeat(64), "lib/renamed.zip")), m)
        assertEquals(
            "Not pushed: the branch contains project file `vendor/sdk.zip` (sha256 `111111111111`) committed as `lib/renamed.zip`. " +
                "Project files must not be committed. Remove it from every commit of the branch (a later commit that deletes it is not " +
                "enough), then use Retry → Continue, or talk to the agent on the run page.",
            ProjectFileGuard.blockedText(m),
        )
        val many = (1..12).map { ProjectFileGuard.Match("f$it", "1".repeat(64), "p$it") }
        assertTrue(ProjectFileGuard.blockedText(many).contains(", and 2 more. "))
        assertFalse(ProjectFileGuard.blockedText(many).contains("`f11`"))

        val large = ProjectFileGuard.large(blobs, m)
        assertEquals(listOf("big.dat"), large.map { it.path })
        assertEquals(
            "Large new file(s) in this branch: `big.dat` (11.0 MiB). If one is a vendor or licensed file, it belongs in the project's Files, not in git.",
            ProjectFileGuard.largeText(large),
        )
        assertTrue(ProjectFileGuard.large(blobs, m, alreadyWarned = setOf("c".repeat(40))).isEmpty())
        assertTrue(ProjectFileGuard.large(listOf(NewBlob("d".repeat(40), (10L shl 20) - 1, "small")), emptyList()).isEmpty())
    }
}

/** [CliGitOps.newBlobs] and [CliGitOps.blobId] with real git on a temporary repository. */
class ProjectFileGuardGitTest {
    private val tmp: Path = Files.createTempDirectory("agern-guard-git")
    @AfterTest fun tearDown() { tmp.toFile().deleteRecursively() }

    private fun gitIn(cwd: Path, vararg args: String): String {
        val pb = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false") + args)
            .directory(cwd.toFile()).redirectErrorStream(true)
        pb.environment()["HOME"] = tmp.toString()
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${args.joinToString(" ")} failed: $out" }
        return out
    }

    @Test
    fun `new blobs since the base, also deleted ones, and blob ids of files outside the repository`() {
        val repo = tmp.resolve("repo")
        Files.createDirectories(repo)
        gitIn(repo, "init", "-q", "-b", "main")
        Files.writeString(repo.resolve("README.md"), "readme\n")
        Files.writeString(repo.resolve("base.bin"), "already on the base\n")
        gitIn(repo, "add", "-A"); gitIn(repo, "commit", "-q", "-m", "base")
        gitIn(repo, "checkout", "-q", "-b", "run")
        // The project file, committed under another name, then deleted in a later commit.
        val sdk = tmp.resolve("store-sdk.zip")
        Files.write(sdk, ByteArray(5000) { (it % 97).toByte() })
        Files.createDirectories(repo.resolve("lib"))
        Files.copy(sdk, repo.resolve("lib/copy.zip"))
        gitIn(repo, "add", "-A"); gitIn(repo, "commit", "-q", "-m", "add copy")
        Files.delete(repo.resolve("lib/copy.zip"))
        Files.writeString(repo.resolve("src.kt"), "fun x() = 1\n")
        // The same content as a blob already on the base: not new.
        Files.writeString(repo.resolve("again.bin"), "already on the base\n")
        gitIn(repo, "add", "-A"); gitIn(repo, "commit", "-q", "-m", "delete it again")

        val git = CliGitOps(System.getenv("PATH") ?: "/usr/bin:/bin", "https://github.invalid/", localOnly = true)
        val home = tmp.resolve("home")
        val blobs = git.newBlobs(repo, "main", home)
        assertEquals(setOf("lib/copy.zip", "src.kt"), blobs.map { it.path }.toSet())
        val copy = blobs.single { it.path == "lib/copy.zip" }
        assertEquals(5000L, copy.size)
        assertEquals(copy.sha, git.blobId(repo, sdk, home))
        assertEquals(gitBlobId(sdk), git.blobId(repo, sdk, home))
        // Nothing new on the base itself.
        gitIn(repo, "checkout", "-q", "main")
        assertTrue(git.newBlobs(repo, "main", home).isEmpty())
    }
}
