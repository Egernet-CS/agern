package dk.egernet.factory.agent

import dk.egernet.factory.Config
import dk.egernet.factory.git.GitOps
import dk.egernet.factory.github.GitHub
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

val REPO_NAME_RE = Regex("^[A-Za-z0-9._-]{1,100}$")

fun requireSafeSegment(s: String, what: String): String {
    require(REPO_NAME_RE.matches(s) && s != "." && s != "..") { "Invalid $what" }
    return s
}

/** Layout under FACTORY_DATA_DIR: homes/<user>, workspaces/<user>/<repo>/{main,runs/<runId>}. */
class Workspaces(private val cfg: Config, val git: GitOps, private val github: GitHub) {
    private val locks = ConcurrentHashMap<String, Any>()

    fun home(username: String): Path = cfg.dataDir.resolve("homes").resolve(requireSafeSegment(username, "username"))
    fun repoDir(username: String, repo: String): Path =
        cfg.dataDir.resolve("workspaces").resolve(requireSafeSegment(username, "username")).resolve(requireSafeSegment(repo, "repository"))
    fun mainDir(username: String, repo: String): Path = repoDir(username, repo).resolve("main")
    fun runDir(username: String, repo: String, runId: UUID): Path = repoDir(username, repo).resolve("runs").resolve(runId.toString())

    /** The owner's clone used by chat (and as the base of run worktrees). Blocking. */
    fun ensureMain(id: AgentIdentity, repo: String): Path {
        val dir = mainDir(id.username, repo)
        synchronized(locks.computeIfAbsent("${id.username}/$repo") { Any() }) {
            Files.createDirectories(home(id.username))
            git.ensureClone(id, github.cloneUrl(cfg.githubOrg, repo), dir, home(id.username))
        }
        return dir
    }

    fun <T> withRepoLock(username: String, repo: String, block: () -> T): T =
        synchronized(locks.computeIfAbsent("$username/$repo") { Any() }) { block() }
}
