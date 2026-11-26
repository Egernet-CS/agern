package dk.egernet.factory

import dk.egernet.factory.agent.AgentIdentity
import dk.egernet.factory.git.CliGitOps
import dk.egernet.factory.git.GitException
import dk.egernet.factory.github.ClaudeCredential
import dk.egernet.factory.github.GitHubFailure
import dk.egernet.factory.github.GitHubHealth
import dk.egernet.factory.pipeline.Transient
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [CliGitOps] reports clone/fetch/push to [GitHubHealth] and points at the status page when the network failed. */
class GitRecordingTest {
    private val id = AgentIdentity("alice", "Alice", "alice", "fake-alice", ClaudeCredential("oauth_token", "x"))
    private val path = System.getenv("PATH") ?: "/usr/bin:/bin"

    @Test
    fun `a clone that cannot connect is a network failure with the hint`() {
        val health = GitHubHealth("acme")
        val git = CliGitOps(path, "https://github.invalid/", localOnly = false, health = health)
        val tmp = Files.createTempDirectory("agern-git-rec")
        try {
            val port = ServerSocket(0).use { it.localPort }
            val e = assertFailsWith<GitException> {
                git.ensureClone(id, "http://127.0.0.1:$port/acme/app.git", tmp.resolve("p/app"), tmp.resolve("home"))
            }
            assertTrue(e.message!!.endsWith(GitHubFailure.GIT_HINT), e.message)
            assertTrue(Transient.isTransient(e))
            val k = health.snapshot().first { it.kind == "git" }
            assertEquals(1, k.last15m.calls)
            assertEquals(1, k.last15m.network)
            assertTrue("127.0.0.1" !in k.lastError!!.message, k.lastError!!.message)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `local-only git records nothing, a successful clone counts as a call`() {
        val tmp = Files.createTempDirectory("agern-git-rec")
        try {
            val local = GitHubHealth("acme")
            CliGitOps(path, "https://github.invalid/", localOnly = true, health = local).ensureClone(id, "unused", tmp.resolve("l/app"), tmp.resolve("home"))
            assertEquals(0, local.totals(15).calls)

            // A clone of that local repository through a file path: recorded as a successful git call.
            val health = GitHubHealth("acme")
            val git = CliGitOps(path, "https://github.invalid/", localOnly = false, health = health)
            git.ensureClone(id, tmp.resolve("l/app").toString(), tmp.resolve("c/app"), tmp.resolve("home"))
            val k = health.snapshot().first { it.kind == "git" }
            assertEquals(1, k.last15m.calls)
            assertEquals(null, k.lastError)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
