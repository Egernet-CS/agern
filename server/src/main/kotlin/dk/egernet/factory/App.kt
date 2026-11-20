package dk.egernet.factory

import dk.egernet.factory.agent.AgentLauncher
import dk.egernet.factory.agent.AgentManager
import dk.egernet.factory.agent.EventBus
import dk.egernet.factory.agent.LocalProcessLauncher
import dk.egernet.factory.agent.Prompts
import dk.egernet.factory.agent.Workspaces
import dk.egernet.factory.api.installApi
import dk.egernet.factory.auth.AuthService
import dk.egernet.factory.auth.TwoFactorService
import dk.egernet.factory.db.Db
import dk.egernet.factory.db.EventStore
import dk.egernet.factory.db.RunStore
import dk.egernet.factory.db.SessionStore
import dk.egernet.factory.db.SettingsStore
import dk.egernet.factory.db.SoftwareRequestStore
import dk.egernet.factory.db.TwoFactorStore
import dk.egernet.factory.db.UserStore
import dk.egernet.factory.db.WebSessionStore
import dk.egernet.factory.git.CliGitOps
import dk.egernet.factory.git.GitOps
import dk.egernet.factory.github.Access
import dk.egernet.factory.github.FakeGitHub
import dk.egernet.factory.github.GitHub
import dk.egernet.factory.github.HttpGitHub
import dk.egernet.factory.mcp.McpRegistry
import dk.egernet.factory.mcp.McpServer
import dk.egernet.factory.pipeline.Orchestrator
import dk.egernet.factory.security.SecretBox
import io.ktor.server.application.Application
import dk.egernet.factory.db.SandboxApprovalStore
import dk.egernet.factory.sandbox.PodmanRuntime
import dk.egernet.factory.sandbox.SandboxManager
import dk.egernet.factory.sandbox.SandboxRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory

object BuildInfo {
    val version: String by lazy {
        BuildInfo::class.java.getResourceAsStream("/agern-version.txt")?.bufferedReader()?.readText()?.trim()
            ?.takeIf { it.isNotEmpty() && !it.contains("\${") } ?: "0.0.0-dev"
    }
}

/** Everything the server is made of. Tests construct it with a fake launcher, fake GitHub and fake git. */
class Services(
    val cfg: Config,
    val db: Db,
    val github: GitHub,
    val git: GitOps,
    val launcher: AgentLauncher,
    val auth: AuthService = AuthService.fromConfig(cfg),
    val loginThrottle: dk.egernet.factory.auth.LoginThrottle = dk.egernet.factory.auth.LoginThrottle(),
    /** The container runtime for sandboxes; null (sandboxes off) unless FACTORY_SANDBOX names one. */
    val sandboxRuntime: SandboxRuntime? = when (cfg.sandbox.mode) {
        "podman" -> PodmanRuntime(cfg.sandbox)
        "fake" -> dk.egernet.factory.sandbox.DevFakeSandboxRuntime()
        else -> null
    },
    /** Time source of the release scheduler (tests use a controllable clock). */
    val clock: java.time.Clock = java.time.Clock.systemUTC(),
    /** What a scheduled start calls; null = the real start ([ReleaseService.startScheduled]). Tests may replace it. */
    releaseStarter: ReleaseStarter? = null,
    /** Whether a project allows automatic releases: the seam for #24 (every project does until it exists). */
    val releaseModePolicy: dk.egernet.factory.pipeline.ReleaseModePolicy = dk.egernet.factory.pipeline.ReleaseModePolicy.ALLOW_ALL,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val box = SecretBox(cfg.masterKey)
    val users = UserStore(db)
    val webSessions = WebSessionStore(db)
    val sessions = SessionStore(db)
    val events = EventStore(db)
    val runs = RunStore(db)
    val settings = SettingsStore(db)
    val softwareRequests = SoftwareRequestStore(db)
    val twoFactorStore = TwoFactorStore(db)
    val twoFactor = TwoFactorService(users, twoFactorStore, box)
    val access = Access(cfg, users, box, github)
    val bus = EventBus()
    val mcpRegistry = McpRegistry()
    val prompts = Prompts(cfg.githubOrg)
    val workspaces = Workspaces(cfg, git, github)
    val attachments = dk.egernet.factory.agent.Attachments(cfg, dk.egernet.factory.db.AttachmentStore(db))
    val preferences = dk.egernet.factory.db.PreferenceStore(db, settings)
    val agents = AgentManager(cfg, sessions, events, access, launcher, bus, mcpRegistry, settings, prompts, workspaces, scope, attachments, preferences)
    val sandboxes = SandboxManager(
        cfg, sandboxRuntime, SandboxApprovalStore(db), runs, access, git,
        publishSystem = { sessionId, payload -> agents.publish(sessionId, "system", payload) },
        emitFeed = { bus.emitFeed(it) },
        emitRun = { id -> runs.get(id)?.let { bus.emitFeed(dk.egernet.factory.agent.FeedItem.Run(runs.toDto(it))) } },
        approvers = {
            val known = users.all().filter { it.isAdmin }
            (known.map { OwnerDto(it.username, it.displayName) } + (cfg.admins - known.map { it.username }.toSet()).map { OwnerDto(it, it) })
                .sortedBy { it.username }
        },
        // The fake GitHub's workspaces are local `git init` repositories without the file: runs read the fake's copy.
        devDefinitionSource = (github as? FakeGitHub)?.takeIf { cfg.fakeGithub }?.let { fake ->
            { repo -> fake.fileOf(repo, dk.egernet.factory.sandbox.SANDBOX_FILE) }
        },
    )
    val orchestrator = Orchestrator(cfg, runs, users, access, agents, workspaces, settings, prompts, bus, scope, preferences, sandboxes)
    val releaseStore = dk.egernet.factory.db.ReleaseStore(db)
    val releaseLocks = dk.egernet.factory.pipeline.ReleaseLocks()
    val releaseMerger = dk.egernet.factory.pipeline.ReleaseMerger(
        cfg, releaseStore, users, access, workspaces, bus, releaseLocks, scope, releaseModePolicy,
    )
    val releaseBuilder = dk.egernet.factory.pipeline.ReleaseBuilder(
        cfg, releaseStore, runs, users, access, orchestrator, bus, scope, releaseLocks, releaseMerger,
    )
    // A scheduled start goes through the same start as "start now" (ReleaseService.startScheduled), as the owner.
    val releaseScheduler: ReleaseScheduler = ReleaseScheduler(
        cfg, clock, releaseStore, users, access, bus,
        releaseStarter ?: ReleaseStarter { release, owner -> releases.startScheduled(owner, release.id) },
    )
    val releases: ReleaseService = ReleaseService(cfg, access, releaseStore, bus, releaseBuilder, releaseScheduler, releaseMerger, releaseModePolicy)
    val factory = FactoryService(cfg, access, runs, orchestrator, agents, softwareRequests, sessions, bus, releases)
    val mcp = McpServer(mcpRegistry, users, sessions, factory, releases, BuildInfo.version, sandboxes)

    /** The start-up removal of leftover sandboxes (runs in the background; tests wait for it). */

    init {
        agents.commandHandler = { session, owner, text -> factory.handleCommand(session, owner, text) }
        orchestrator.onReleaseRunSettled = releaseBuilder::onRunSettled
    }

    /**
     * v1 restart semantics: no process survives; unfinished runs fail with a clear reason. Building releases continue
     * with the interrupted issue (docs/ARCHITECTURE.md §6 "Releases").
     */
    fun recoverAfterRestart() {
        val failed = runs.failUnfinishedAfterRestart()
        if (failed.isNotEmpty()) LoggerFactory.getLogger("dk.egernet.factory").warn("Marked {} unfinished run(s) as failed after restart", failed.size)
        // Order matters: first the sandboxes of runs that are no longer active (their containers bind-mount the worktrees),
        // then those worktrees, then the releases (which may start new runs). Bounded, so a hanging Podman cannot keep
        // the server from starting; what is left is removed by the next start or `agern sandbox prune`.
        if (sandboxes.enabled) {
            runCatching { runBlocking { withTimeoutOrNull(120_000) { sandboxes.reapOrphans() } } }
                .onFailure { LoggerFactory.getLogger("dk.egernet.factory").warn("Reaping sandboxes failed: {}", it.message) }
        }
        // Worktrees of runs that have ended (including the ones just failed); needs_human runs keep theirs.
        val reaped = runCatching { orchestrator.reapWorktrees() }
            .onFailure { LoggerFactory.getLogger("dk.egernet.factory").warn("Reaping run worktrees failed: {}", it.message) }
            .getOrDefault(emptyList())
        if (reaped.isNotEmpty()) LoggerFactory.getLogger("dk.egernet.factory").info("Removed {} worktree(s) of ended runs", reaped.size)
        releaseBuilder.recoverAfterRestart()
        sessions.recoverAfterRestart()
        webSessions.purgeExpired()
    }

    fun start() {
        recoverAfterRestart()
        releaseScheduler.start(scope)
        agents.startIdleReaper()
        releaseBuilder.startTicker()
        releaseMerger.startTicker()
        attachments.startReaper(scope)
    }

    fun stop() {
        agents.shutdown()
        runCatching { runBlocking { withTimeoutOrNull(30_000) { sandboxes.shutdown() } } }
        scope.cancel()
    }

    companion object {
        fun production(cfg: Config): Services {
            val db = Db.connect(cfg.dbUrl, cfg.dbUser, cfg.dbPassword)
            val gitHost = if (cfg.githubApiUrl == "https://api.github.com") "https://github.com/"
            else cfg.githubApiUrl.substringBefore("/api/").trimEnd('/') + "/"
            val github: GitHub = if (cfg.fakeGithub) {
                LoggerFactory.getLogger("dk.egernet.factory").warn("!!!!!!!! FACTORY_DEV_FAKE_GITHUB: using an in-memory fake GitHub. Tokens 'fake-<login>' are accepted. !!!!!!!!")
                FakeGitHub.seeded(cfg.githubOrg).apply {
                    filesDir = cfg.dataDir.resolve("fake-github")
                    checksDir = cfg.dataDir.resolve("fake-github-checks")
                }
            } else {
                HttpGitHub(cfg.githubApiUrl, gitHost.trimEnd('/'))
            }
            val git = CliGitOps(cfg.agentPath, gitHost, localOnly = cfg.fakeGithub)
            return Services(cfg, db, github, git, LocalProcessLauncher(cfg.claudeBin, cfg.agentPath))
        }
    }
}

fun Application.agernModule(services: Services) {
    installApi(services)
}
