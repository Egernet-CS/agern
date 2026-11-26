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
import kotlinx.coroutines.CoroutineExceptionHandler
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
    /**
     * Whether a project allows automatic releases; null = from the project's merge policy (only `automatic` allows them;
     * docs/ARCHITECTURE.md §6 "Merge policy"). Tests may pass their own.
     */
    releaseModePolicy: dk.egernet.factory.pipeline.ReleaseModePolicy? = null,
    /** The image providers offered in Settings; null = OpenAI and Gemini (plus the fake one in fake-GitHub mode). Tests inject fakes. */
    imageProviders: List<dk.egernet.factory.images.ImageProvider>? = null,
    /** Called with every exception that escapes a background coroutine of [scope], after it is logged. Tests record them. */
    onBackgroundFailure: ((Throwable) -> Unit)? = null,
    /** What Agern sees of GitHub: the same instance [github] and [git] record into (docs/ARCHITECTURE.md §6 "GitHub status"). */
    val githubHealth: dk.egernet.factory.github.GitHubHealth = dk.egernet.factory.github.GitHubHealth(cfg.githubOrg, clock),
) {
    /**
     * Background work of the server. A failure that escapes one of its coroutines is logged (instead of going to the
     * thread's default handler) and does not cancel the others.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
        LoggerFactory.getLogger("dk.egernet.factory").error("A background task failed", e)
        runCatching { onBackgroundFailure?.invoke(e) }
    })
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
    /** `GET /api/github/status`: official status, recorded outcomes, verdict and rate limits. */
    val githubStatus = dk.egernet.factory.github.GitHubStatusService(
        githubHealth, dk.egernet.factory.github.OfficialGitHubStatus(cfg.githubStatusUrl, clock), access, clock,
    )
    val workspaceDefs = dk.egernet.factory.workspace.WorkspaceService(access, gitHost = cfg.gitHost)
    val bus = EventBus()
    val mcpRegistry = McpRegistry()
    val prompts = Prompts(cfg.githubOrg)
    val workspaces = Workspaces(cfg, git, github)
    val attachments = dk.egernet.factory.agent.Attachments(cfg, dk.egernet.factory.db.AttachmentStore(db))
    val preferences = dk.egernet.factory.db.PreferenceStore(db, settings)
    val usage = dk.egernet.factory.db.ClaudeUsageStore(db, clock)
    val agents = AgentManager(cfg, sessions, events, access, launcher, bus, mcpRegistry, settings, prompts, workspaces, scope, attachments, preferences, usage,
        workspaceDefs)
    val sandboxes = SandboxManager(
        cfg, sandboxRuntime, SandboxApprovalStore(db), runs, access, git, scope,
        publishSystem = { sessionId, payload -> agents.publish(sessionId, "system", payload) },
        emitFeed = { bus.emitFeed(it) },
        emitRun = { id -> runs.get(id)?.let { bus.emitFeed(dk.egernet.factory.agent.FeedItem.Run(runs.toDto(it), it.contextRepos)) } },
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
    val screenshotStore = dk.egernet.factory.db.ScreenshotStore(db)
    val images = dk.egernet.factory.images.ImageService(
        cfg,
        imageProviders ?: (
            listOf(
                dk.egernet.factory.images.OpenAiImageProvider(), dk.egernet.factory.images.GeminiImageProvider(),
                dk.egernet.factory.images.CodexImageProvider(cfg.codexBin, cfg.agentPath, cfg.dataDir.resolve("codex")),
            ) + if (cfg.fakeGithub) listOf(dk.egernet.factory.images.DevFakeImageProvider()) else emptyList()
            ),
        dk.egernet.factory.db.ImageStore(db), users, sessions, runs, access, box, workspaces,
        publishEvent = { sessionId, type, payload -> agents.publish(sessionId, type, payload) },
        clock = clock,
    )
    /** Codex device logins; null when the catalogue has no Codex provider (the login routes then answer 409). */
    val codexLogins: dk.egernet.factory.images.CodexLogins? =
        (images.provider("codex") as? dk.egernet.factory.images.CodexImageProvider)?.let {
            dk.egernet.factory.images.CodexLogins(it, images, scope, clock)
        }
    val releaseStore = dk.egernet.factory.db.ReleaseStore(db)
    /** Per-project settings set by admins: the merge policy and its reviewers (docs/ARCHITECTURE.md §6 "Merge policy"). */
    val projectSettings = dk.egernet.factory.db.ProjectSettingsStore(db)
    val modePolicy: dk.egernet.factory.pipeline.ReleaseModePolicy = releaseModePolicy
        ?: dk.egernet.factory.pipeline.ReleaseModePolicy { repo -> projectSettings.get(repo).mergePolicy == MergePolicy.AUTOMATIC }
    val orchestrator = Orchestrator(cfg, runs, users, access, agents, workspaces, settings, prompts, bus, scope, preferences, sandboxes, screenshotStore,
        sessions, events, images, workspaceDefs, releaseStore, projectSettings, clock)
    val screenshots = dk.egernet.factory.agent.Screenshots(cfg, screenshotStore, runs, workspaces, publishRun = { orchestrator.publish(it) })
    val releaseLocks = dk.egernet.factory.pipeline.ReleaseLocks()
    val releaseMerger = dk.egernet.factory.pipeline.ReleaseMerger(
        cfg, releaseStore, users, access, workspaces, bus, releaseLocks, scope, modePolicy, clock,
        mergePolicyOf = { repo -> projectSettings.get(repo).mergePolicy },
    )
    val releaseBuilder = dk.egernet.factory.pipeline.ReleaseBuilder(
        cfg, releaseStore, runs, users, access, orchestrator, bus, scope, releaseLocks, releaseMerger, workspaceDefs,
    )
    // A scheduled start goes through the same start as "start now" (ReleaseService.startScheduled), as the owner.
    val releaseScheduler: ReleaseScheduler = ReleaseScheduler(
        cfg, clock, releaseStore, users, access, bus,
        releaseStarter ?: ReleaseStarter { release, owner -> releases.startScheduled(owner, release.id) },
    )
    val releases: ReleaseService = ReleaseService(
        cfg, access, releaseStore, bus, releaseBuilder, releaseScheduler, releaseMerger, modePolicy, workspaceDefs, projectSettings,
    )
    val projectIcons = dk.egernet.factory.db.ProjectIconStore(db)
    val memberLabels = dk.egernet.factory.workspace.MemberLabels(access)
    val factory = FactoryService(
        cfg, access, runs, orchestrator, agents, softwareRequests, sessions, bus, releases, images.store, projectIcons, workspaceDefs,
        memberLabels, projectSettings,
    )
    /** Personal MCP tokens (docs/ARCHITECTURE.md §7 "Personal tokens"). */
    val mcpTokens = dk.egernet.factory.db.McpTokenStore(db)
    /** Checks the bearer of `POST /mcp`; its `limiter` throttles unknown bearers per client address. */
    val mcpAuth = dk.egernet.factory.mcp.McpAuthenticator(mcpRegistry, mcpTokens, users, access, twoFactorStore, cfg.githubOrg, clock)
    val mcp = McpServer(mcpRegistry, users, sessions, factory, releases, BuildInfo.version, sandboxes, screenshots, images, mcpAuth, cfg)

    /** The start-up removal of leftover sandboxes (runs in the background; tests wait for it). */

    init {
        agents.commandHandler = { session, owner, text -> factory.handleCommand(session, owner, text) }
        agents.advisorLaunch = { session, owner -> orchestrator.advisorLaunch(session, owner) }
        agents.afterAdvisorTurn = { session -> session.runId?.let { orchestrator.reconcile(it) } }
        orchestrator.onReleaseRunSettled = releaseBuilder::onRunSettled
        orchestrator.onReleaseRunWaitChanged = { id -> scope.launch { releaseBuilder.publish(id) } }
        orchestrator.onReleaseBranchPushed = { id -> releaseMerger.evaluateLater(id) }
        images.publishRun = { orchestrator.publish(it) }
        agents.onChatInput = { sessionId -> images.newTurn(sessionId) }
        agents.onChatClosed = { sessionId -> images.removeSession(sessionId) }
        codexLogins?.let { logins -> images.onSettingChanged = { userId -> logins.cancel(userId) } }
    }

    /**
     * v1 restart semantics: no process survives; unfinished runs fail with a clear reason. Building releases continue
     * with the interrupted issue (docs/ARCHITECTURE.md §6 "Releases").
     */
    fun recoverAfterRestart() {
        val failed = runs.failUnfinishedAfterRestart()
        // Runs at needs_human (and waiting_for_checks) survive: an interrupted follow-up review must be started again, and
        // no stage stays open.
        runs.recoverConversationsAfterRestart()
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
        // Runs that were waiting for the checks on their pull request go on waiting, with their original deadline.
        orchestrator.resumeChecksWaits()
        sessions.recoverAfterRestart()
        webSessions.purgeExpired()
        mcpTokens.purgeRevokedBefore(clock.instant().minus(java.time.Duration.ofDays(30)))
    }

    fun start() {
        recoverAfterRestart()
        releaseScheduler.start(scope)
        agents.startIdleReaper()
        releaseBuilder.startTicker()
        releaseMerger.startTicker()
        orchestrator.startFollower()
        attachments.startReaper(scope)
    }

    fun stop() {
        codexLogins?.close()
        agents.shutdown()
        runCatching { runBlocking { withTimeoutOrNull(30_000) { sandboxes.shutdown() } } }
        scope.cancel()
    }

    companion object {
        fun production(cfg: Config): Services {
            val db = Db.connect(cfg.dbUrl, cfg.dbUser, cfg.dbPassword)
            val gitHost = cfg.gitHost
            val health = dk.egernet.factory.github.GitHubHealth(cfg.githubOrg)
            val github: GitHub = if (cfg.fakeGithub) {
                LoggerFactory.getLogger("dk.egernet.factory").warn("!!!!!!!! FACTORY_DEV_FAKE_GITHUB: using an in-memory fake GitHub. Tokens 'fake-<login>' are accepted. !!!!!!!!")
                FakeGitHub.seeded(cfg.githubOrg).apply {
                    filesDir = cfg.dataDir.resolve("fake-github")
                    checksDir = cfg.dataDir.resolve("fake-github-checks")
                }
            } else {
                HttpGitHub(cfg.githubApiUrl, gitHost.trimEnd('/'), health)
            }
            val git = CliGitOps(cfg.agentPath, gitHost, localOnly = cfg.fakeGithub, health = health)
            return Services(cfg, db, github, git, LocalProcessLauncher(cfg.claudeBin, cfg.agentPath), githubHealth = health)
        }
    }
}

fun Application.agernModule(services: Services) {
    installApi(services)
}
