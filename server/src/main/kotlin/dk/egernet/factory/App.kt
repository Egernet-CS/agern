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
import kotlinx.coroutines.CoroutineScope
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
    /** Time source of the release scheduler (tests use a controllable clock). */
    val clock: java.time.Clock = java.time.Clock.systemUTC(),
    /** What a scheduled start calls; null = the real start ([ReleaseService.startScheduled]). Tests may replace it. */
    releaseStarter: ReleaseStarter? = null,
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
    val orchestrator = Orchestrator(cfg, runs, users, access, agents, workspaces, settings, prompts, bus, scope, preferences)
    val releaseStore = dk.egernet.factory.db.ReleaseStore(db)
    val releaseBuilder = dk.egernet.factory.pipeline.ReleaseBuilder(cfg, releaseStore, runs, users, access, orchestrator, bus, scope)
    // A scheduled start goes through the same start as "start now" (ReleaseService.startScheduled), as the owner.
    val releaseScheduler: ReleaseScheduler = ReleaseScheduler(
        cfg, clock, releaseStore, users, access, bus,
        releaseStarter ?: ReleaseStarter { release, owner -> releases.startScheduled(owner, release.id) },
    )
    val releases: ReleaseService = ReleaseService(cfg, access, releaseStore, bus, releaseBuilder, releaseScheduler)
    val factory = FactoryService(cfg, access, runs, orchestrator, agents, softwareRequests, sessions, bus, releases)
    val mcp = McpServer(mcpRegistry, users, sessions, factory, releases, BuildInfo.version)

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
        releaseBuilder.recoverAfterRestart()
        sessions.recoverAfterRestart()
        webSessions.purgeExpired()
    }

    fun start() {
        recoverAfterRestart()
        releaseScheduler.start(scope)
        agents.startIdleReaper()
        releaseBuilder.startTicker()
        attachments.startReaper(scope)
    }

    fun stop() {
        agents.shutdown()
        scope.cancel()
    }

    companion object {
        fun production(cfg: Config): Services {
            val db = Db.connect(cfg.dbUrl, cfg.dbUser, cfg.dbPassword)
            val gitHost = if (cfg.githubApiUrl == "https://api.github.com") "https://github.com/"
            else cfg.githubApiUrl.substringBefore("/api/").trimEnd('/') + "/"
            val github: GitHub = if (cfg.fakeGithub) {
                LoggerFactory.getLogger("dk.egernet.factory").warn("!!!!!!!! FACTORY_DEV_FAKE_GITHUB: using an in-memory fake GitHub. Tokens 'fake-<login>' are accepted. !!!!!!!!")
                FakeGitHub.seeded(cfg.githubOrg)
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
