package dk.egernet.factory.api

import dk.egernet.factory.ApiException
import dk.egernet.factory.AppJson
import dk.egernet.factory.BuildInfo
import dk.egernet.factory.Config
import dk.egernet.factory.ErrorBody
import dk.egernet.factory.ErrorDetail
import dk.egernet.factory.FeedFrame
import dk.egernet.factory.LoginStepDto
import dk.egernet.factory.PIPELINE_ROLES
import dk.egernet.factory.RunStatus
import dk.egernet.factory.Services
import dk.egernet.factory.SettingsDto
import dk.egernet.factory.SoftwareRequestDto
import dk.egernet.factory.agent.EventBus
import dk.egernet.factory.agent.FeedItem
import dk.egernet.factory.agent.INLINE_IMAGE_TYPES
import io.ktor.utils.io.readAvailable
import io.ktor.server.request.receiveChannel
import dk.egernet.factory.agent.requireSafeSegment
import dk.egernet.factory.badRequest
import dk.egernet.factory.conflict
import dk.egernet.factory.db.SessionRecord
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.db.WebSession
import dk.egernet.factory.forbidden
import dk.egernet.factory.github.GitHubException
import dk.egernet.factory.notFound
import dk.egernet.factory.security.Tokens
import dk.egernet.factory.unauthorized
import dk.egernet.factory.tooManyAttempts
import dk.egernet.factory.unprocessable
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.webSocket
import io.ktor.util.AttributeKey
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

const val SESSION_COOKIE = "sf_session"
private val log = LoggerFactory.getLogger("dk.egernet.factory.api")

@Serializable data class LoginReq(val username: String, val password: String)
@Serializable data class CodeReq(val code: String)
@Serializable data class ClaudeCredReq(val kind: String, val value: String)
@Serializable data class GithubTokenReq(val token: String)
@Serializable data class CreateIssueReq(val title: String, val body: String = "", val kind: String = "task")
@Serializable data class CreateRunReq(val issueNumber: Int)
@Serializable data class CreateReleaseReq(val name: String, val description: String = "")
@Serializable data class UpdateReleaseReq(val name: String, val description: String = "")
@Serializable data class AssignReq(val issueNumbers: List<Int>, val releaseId: String? = null)
@Serializable data class OrderReq(val issueNumbers: List<Int>)
@Serializable data class CreateSessionReq(val model: String? = null)
@Serializable data class InputReq(val text: String = "", val attachmentIds: List<String> = emptyList())
@Serializable data class ResolveReq(val status: String, val note: String? = null)
@Serializable data class HealthDto(val status: String, val version: String, val name: String)
@Serializable data class ActivityDto(val idle: Boolean, val activeRuns: Int, val busyAgents: Int, val version: String)
@Serializable data class ProvidersDto(val providers: List<String>, val org: String, val twoFactor: Boolean)
@Serializable data class RecoveryCodesDto(val recoveryCodes: List<String>)

private val AuthKey = AttributeKey<Pair<WebSession, UserRecord>>("agern.auth")

/** Resolves the cookie session (any stage). Cached per call. */
private fun ApplicationCall.webSession(s: Services): Pair<WebSession, UserRecord>? {
    attributes.getOrNull(AuthKey)?.let { return it }
    val cookie = request.cookies[SESSION_COOKIE] ?: return null
    val ws = s.webSessions.find(cookie) ?: return null
    val u = s.users.byId(ws.userId) ?: return null
    return (ws to u).also { attributes.put(AuthKey, it) }
}

/**
 * The central auth check. Only a *full* session (password + second factor) gets a user;
 * pending sessions are unauthenticated everywhere except the two TOTP endpoints and logout.
 */
fun ApplicationCall.requireUser(s: Services): UserRecord {
    val (ws, u) = webSession(s) ?: throw unauthorized()
    if (ws.stage != "full") throw unauthorized("Two-factor authentication required")
    return u
}

private fun ApplicationCall.requirePending(s: Services, stage: String): UserRecord {
    val (ws, u) = webSession(s) ?: throw unauthorized()
    if (ws.stage != stage) throw conflict("This login step is not expected now (session is in '${ws.stage}')", "wrong_login_step")
    return u
}

private fun ApplicationCall.requireAdmin(s: Services): UserRecord =
    requireUser(s).also { if (!it.isAdmin) throw forbidden("Admins only") }

private fun ApplicationCall.remote(): String = request.origin.remoteHost

/** Headers a reverse proxy adds: a request carrying one came from elsewhere, even when the proxy connects over loopback. */
private val PROXY_HEADERS = listOf(HttpHeaders.Forwarded, HttpHeaders.XForwardedFor, HttpHeaders.XForwardedHost, "X-Real-IP")

/** True for a loopback IP literal (127.0.0.0/8, ::1, ::ffff:127.x). Never resolves host names. */
internal fun isLoopbackAddress(address: String): Boolean {
    val h = address.trim().removePrefix("[").removeSuffix("]")
    if (h.equals("localhost", ignoreCase = true)) return true
    if (h.isEmpty() || !h.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return false
    return runCatching { java.net.InetAddress.getByName(h).isLoopbackAddress }.getOrDefault(false)
}

/**
 * A caller on this host talking to the server directly: the TCP peer (not `X-Forwarded-For`) is a loopback address
 * and no proxy header is present. Used for host-local operations endpoints (the upgrade timer).
 */
internal fun ApplicationCall.isDirectLocalCaller(): Boolean =
    isLoopbackAddress(request.local.remoteAddress) && PROXY_HEADERS.none { request.headers[it] != null }

private fun ApplicationCall.setSessionCookie(cfg: Config, value: String?) {
    response.cookies.append(
        Cookie(
            name = SESSION_COOKIE, value = value ?: "", httpOnly = true, secure = cfg.secureCookies, path = "/",
            maxAge = if (value == null) 0 else 14 * 24 * 3600, extensions = mapOf("SameSite" to "Lax"),
        ),
    )
}

/** Replaces the caller's session (if any) with a new one: the id is rotated on every stage change. */
private fun ApplicationCall.newSession(s: Services, userId: UUID, stage: String) {
    request.cookies[SESSION_COOKIE]?.let { s.webSessions.delete(it) }
    val value = Tokens.random(32)
    s.webSessions.create(userId, value, stage)
    setSessionCookie(s.cfg, value)
}

private fun originAllowed(cfg: Config, origin: String?): Boolean {
    if (origin == null) return true
    val o = Config.originOf(origin)
    return o.isNotEmpty() && (o == cfg.publicOrigin || o in cfg.allowedOrigins)
}

private val SAFE_METHODS = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)

fun Application.installApi(s: Services) {
    val cfg = s.cfg
    install(ContentNegotiation) { json(AppJson) }
    install(WebSockets) {
        pingPeriod = 30.seconds
        maxFrameSize = 1L shl 20
    }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ErrorBody(ErrorDetail(e.code, e.message, e.lockedUntil))) }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody(ErrorDetail("bad_request", "Malformed request body or parameters")))
        }
        exception<IllegalArgumentException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody(ErrorDetail("bad_request", e.message ?: "Bad request")))
        }
        exception<Throwable> { call, e ->
            log.error("Unhandled error on {} {}", call.request.httpMethod.value, call.request.path(), e)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody(ErrorDetail("internal_error", "Internal server error")))
        }
    }
    // CSRF guard + Origin check for mutating API requests and WebSocket upgrades.
    install(createApplicationPlugin("AgernRequestGuard") {
        onCall { call ->
            val path = call.request.path()
            if (!path.startsWith("/api/")) return@onCall
            val isWs = path.startsWith("/api/ws/")
            val mutating = call.request.httpMethod !in SAFE_METHODS
            if ((mutating || isWs) && !originAllowed(cfg, call.request.header(HttpHeaders.Origin))) {
                call.respond(HttpStatusCode.Forbidden, ErrorBody(ErrorDetail("bad_origin", "Origin not allowed")))
                return@onCall
            }
            if (mutating && call.request.header("X-Factory-Request") != "1") {
                call.respond(HttpStatusCode.Forbidden, ErrorBody(ErrorDetail("csrf", "Missing X-Factory-Request header")))
            }
        }
    })

    routing {
        route("/api") {
            get("/health") { call.respond(HealthDto("ok", BuildInfo.version, "agern")) }
            // Host-local, unauthenticated: is a restart safe now? Read by `agern upgrade` / the upgrade timer
            // (docs/ARCHITECTURE.md §9). Anyone else gets the generic 404.
            get("/internal/activity") {
                if (!call.isDirectLocalCaller()) throw notFound("No such API endpoint")
                val runs = s.runs.countInProgress()
                val agents = s.sessions.countBusy()
                call.respond(ActivityDto(runs == 0 && agents == 0, runs, agents, BuildInfo.version))
            }
            get("/auth/providers") { call.respond(ProvidersDto(s.auth.names, cfg.githubOrg, cfg.twoFactorRequired)) }
            get("/config") {
                call.requireUser(s)
                call.respond(
                    dk.egernet.factory.ClientConfigDto(
                        dk.egernet.factory.AttachmentLimitsDto(cfg.attachmentMaxBytes, cfg.attachmentMaxMessageBytes, cfg.attachmentMaxCount, INLINE_IMAGE_TYPES),
                    ),
                )
            }
            authRoutes(s)
            meRoutes(s)
            projectRoutes(s)
            runRoutes(s)
            releaseRoutes(s)
            sessionRoutes(s)
            adminRoutes(s)
            wsRoutes(s)
            route("{...}") {
                handle { throw notFound("No such API endpoint") }
            }
        }
        post("/mcp") {
            if (call.request.header(HttpHeaders.Origin) != null) {
                call.respond(HttpStatusCode.Forbidden, ErrorBody(ErrorDetail("bad_origin", "Browsers may not call the MCP endpoint")))
                return@post
            }
            val bearer = call.request.header(HttpHeaders.Authorization)?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
            val (status, body) = s.mcp.handle(bearer, call.receiveText())
            if (body == null) call.respond(HttpStatusCode.fromValue(status), "")
            else call.respondText(body, ContentType.Application.Json, HttpStatusCode.fromValue(status))
        }
        get("/mcp") { call.respond(HttpStatusCode.MethodNotAllowed, ErrorBody(ErrorDetail("method_not_allowed", "SSE streams are not supported"))) }
        delete("/mcp") { call.respond(HttpStatusCode.MethodNotAllowed, ErrorBody(ErrorDetail("method_not_allowed", "No MCP sessions to delete"))) }
        staticFrontend(cfg)
    }
}

// ------------------------------------------------------------------ auth

private fun Route.authRoutes(s: Services) {
    post("/auth/login") {
        val req = call.receive<LoginReq>()
        val username = req.username.trim()
        val remote = call.remote()
        // Rate limit (per username and per client address) before any password is checked.
        s.loginThrottle.lockedUntil(username, remote)?.let { until ->
            throw tooManyAttempts("Too many failed sign-in attempts. Try again after $until.", until)
        }
        val res = withContext(Dispatchers.IO) { s.auth.authenticate(username, req.password) }
        if (res == null) {
            val userId = s.users.byUsername(username)?.id
            s.twoFactorStore.audit(userId, username.take(64), "login_failed", null, remote)
            for (lock in s.loginThrottle.recordFailure(username, remote)) {
                s.twoFactorStore.audit(userId, username.take(64), "login_locked", "${lock.key} until ${lock.until}", remote)
            }
            delay(s.loginThrottle.failureDelay(username).toMillis())
            throw ApiException(HttpStatusCode.Unauthorized, "invalid_credentials", "Wrong username or password")
        }
        s.loginThrottle.recordSuccess(username)
        val user = s.users.upsertOnLogin(res.username, res.displayName, res.username in s.cfg.admins)
        val step = when {
            !s.cfg.twoFactorRequired -> {
                call.newSession(s, user.id, "full")
                LoginStepDto(next = "done", user = user.toDto())
            }
            user.totpEnabled -> {
                call.newSession(s, user.id, "totp")
                LoginStepDto(next = "totp")
            }
            else -> {
                call.newSession(s, user.id, "enroll")
                s.twoFactor.startEnrolment(user)
            }
        }
        call.respond(step)
    }
    post("/auth/totp") {
        val user = call.requirePending(s, "totp")
        val req = call.receive<CodeReq>()
        withContext(Dispatchers.IO) { s.twoFactor.verifyLogin(user, req.code, call.remote()) }
        call.newSession(s, user.id, "full")
        call.respond(LoginStepDto(next = "done", user = s.users.byId(user.id)!!.toDto()))
    }
    post("/auth/totp/enroll") {
        val user = call.requirePending(s, "enroll")
        val req = call.receive<CodeReq>()
        val codes = withContext(Dispatchers.IO) { s.twoFactor.confirmEnrolment(user, req.code, call.remote()) }
        call.newSession(s, user.id, "full")
        call.respond(LoginStepDto(next = "done", user = s.users.byId(user.id)!!.toDto(), recoveryCodes = codes))
    }
    post("/auth/logout") {
        call.request.cookies[SESSION_COOKIE]?.let { s.webSessions.delete(it) }
        call.setSessionCookie(s.cfg, null)
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun Route.meRoutes(s: Services) {
    get("/me") { call.respond(call.requireUser(s).toDto()) }
    put("/me/claude-credential") {
        val u = call.requireUser(s)
        val req = call.receive<ClaudeCredReq>()
        if (req.kind !in setOf("oauth_token", "api_key")) throw badRequest("kind must be oauth_token or api_key")
        val v = req.value.trim()
        if (v.isEmpty() || v.length > 4096 || v.any { it.isWhitespace() }) throw unprocessable("The credential looks malformed")
        s.users.setClaude(u.id, req.kind, s.box.encrypt(v, u.username))
        call.respond(s.users.byId(u.id)!!.toDto())
    }
    delete("/me/claude-credential") {
        val u = call.requireUser(s)
        s.users.setClaude(u.id, null, null)
        call.respond(s.users.byId(u.id)!!.toDto())
    }
    put("/me/github-token") {
        val u = call.requireUser(s)
        val token = call.receive<GithubTokenReq>().token.trim()
        if (token.isEmpty() || token.length > 1024 || token.any { it.isWhitespace() }) throw unprocessable("The token looks malformed", "github_token_invalid")
        val gh = try {
            withContext(Dispatchers.IO) { s.github.client(token).currentUser() }
        } catch (e: GitHubException) {
            throw unprocessable("GitHub rejected the token (${e.status})", "github_token_invalid")
        } catch (e: Exception) {
            throw unprocessable("Could not verify the token with GitHub: ${e.javaClass.simpleName}", "github_token_invalid")
        }
        s.users.setGithub(u.id, gh.login, s.box.encrypt(token, u.username), dk.egernet.factory.github.githubTokenKind(token, gh.scopes), gh.scopes)
        s.access.invalidate(u.id)
        call.respond(s.users.byId(u.id)!!.toDto())
    }
    delete("/me/github-token") {
        val u = call.requireUser(s)
        s.users.setGithub(u.id, null, null)
        s.access.invalidate(u.id)
        call.respond(s.users.byId(u.id)!!.toDto())
    }
    get("/me/preferences") { call.respond(s.preferences.dto(call.requireUser(s).id)) }
    put("/me/preferences") {
        val u = call.requireUser(s)
        call.respond(s.preferences.put(u.id, call.receive<dk.egernet.factory.PreferenceOverridesDto>()))
    }
    post("/me/recovery-codes") {
        val u = call.requireUser(s)
        val req = call.receive<CodeReq>()
        val codes = withContext(Dispatchers.IO) { s.twoFactor.regenerateRecoveryCodes(u, req.code, call.remote()) }
        call.respond(RecoveryCodesDto(codes))
    }
}

// ------------------------------------------------------------------ projects, issues, runs

private fun RoutingContext.repoParam(): String = requireSafeSegment(call.parameters["repo"] ?: "", "repository name")
private fun RoutingContext.uuidParam(name: String): UUID =
    call.parameters[name]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: throw notFound("Not found")

private fun Route.projectRoutes(s: Services) {
    get("/projects") { call.respond(s.factory.listProjects(call.requireUser(s))) }
    get("/projects/{repo}") { call.respond(s.factory.getProject(call.requireUser(s), repoParam())) }
    get("/projects/{repo}/issues") {
        val u = call.requireUser(s)
        call.respond(s.factory.listIssues(u, repoParam(), call.request.queryParameters["state"] ?: "open"))
    }
    get("/projects/{repo}/issues/{number}") {
        val u = call.requireUser(s)
        val n = call.parameters["number"]?.toIntOrNull() ?: throw notFound("Issue not found")
        call.respond(s.factory.getIssue(u, repoParam(), n))
    }
    post("/projects/{repo}/issues") {
        val u = call.requireUser(s)
        val req = call.receive<CreateIssueReq>()
        call.respond(HttpStatusCode.Created, s.factory.createIssue(u, repoParam(), req.title, req.body, req.kind))
    }
    get("/projects/{repo}/runs") {
        val u = call.requireUser(s)
        val repo = repoParam()
        withContext(Dispatchers.IO) { s.access.requireVisible(u, repo) }
        call.respond(s.factory.listRuns(u, repo, activeOnly = false))
    }
    post("/projects/{repo}/runs") {
        val u = call.requireUser(s)
        val req = call.receive<CreateRunReq>()
        call.respond(HttpStatusCode.Created, s.factory.startRun(u, repoParam(), req.issueNumber, null))
    }
}

private fun Route.runRoutes(s: Services) {
    get("/runs") {
        val u = call.requireUser(s)
        val q = call.request.queryParameters
        val statuses = q.getAll("status")?.flatMap { it.split(',') }?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        statuses?.firstOrNull { it !in RunStatus.ALL }?.let { throw badRequest("Unknown run status '$it'") }
        call.respond(s.factory.listRuns(u, null, q["active"] == "true", statuses?.takeIf { it.isNotEmpty() }))
    }
    get("/runs/{id}") {
        val u = call.requireUser(s)
        call.respond(s.runs.toDto(s.factory.visibleRun(u, uuidParam("id"))))
    }
    post("/runs/{id}/approve") { call.respond(s.factory.approveRun(call.requireUser(s), uuidParam("id"))) }
    post("/runs/{id}/cancel") { call.respond(s.factory.cancelRun(call.requireUser(s), uuidParam("id"))) }
}

// ------------------------------------------------------------------ releases (docs/ARCHITECTURE.md §6 "Releases")

private fun Route.releaseRoutes(s: Services) {
    get("/projects/{repo}/releases") {
        val u = call.requireUser(s)
        call.respond(s.releases.list(u, repoParam()).map { it.toDto() })
    }
    post("/projects/{repo}/releases") {
        val u = call.requireUser(s)
        val req = call.receive<CreateReleaseReq>()
        call.respond(HttpStatusCode.Created, s.releases.create(u, repoParam(), req.name, req.description).toDto())
    }
    post("/projects/{repo}/releases/assign") {
        val u = call.requireUser(s)
        val repo = repoParam()
        val req = call.receive<AssignReq>()
        val releaseId = req.releaseId?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw badRequest("releaseId is not a valid id") }
        s.releases.assign(u, repo, req.issueNumbers, releaseId)
        call.respond(s.releases.list(u, repo).map { it.toDto() })
    }
    get("/releases/{id}") {
        val u = call.requireUser(s)
        call.respond(s.releases.visible(u, uuidParam("id")).toDto())
    }
    put("/releases/{id}") {
        val u = call.requireUser(s)
        val id = uuidParam("id")
        val req = call.receive<UpdateReleaseReq>()
        call.respond(s.releases.update(u, id, req.name, req.description).toDto())
    }
    delete("/releases/{id}") {
        val u = call.requireUser(s)
        s.releases.delete(u, uuidParam("id"))
        call.respond(HttpStatusCode.NoContent)
    }
    put("/releases/{id}/issues/order") {
        val u = call.requireUser(s)
        val id = uuidParam("id")
        val req = call.receive<OrderReq>()
        call.respond(s.releases.reorder(u, id, req.issueNumbers).toDto())
    }
}

// ------------------------------------------------------------------ agent sessions

private suspend fun visibleSession(s: Services, u: UserRecord, id: UUID): SessionRecord {
    val rec = s.sessions.get(id) ?: throw notFound("Session not found")
    if (!withContext(Dispatchers.IO) { s.access.canSee(u, rec.repo) }) throw notFound("Session not found")
    return rec
}

private fun Route.sessionRoutes(s: Services) {
    val cfg = s.cfg
    get("/projects/{repo}/sessions") {
        val u = call.requireUser(s)
        val repo = repoParam()
        withContext(Dispatchers.IO) { s.access.requireVisible(u, repo) }
        call.respond(s.sessions.list(repo, call.request.queryParameters["kind"], activeOnly = false).map { s.agents.dto(it) })
    }
    post("/projects/{repo}/sessions") {
        val u = call.requireUser(s)
        val repo = repoParam()
        val req = runCatching { call.receive<CreateSessionReq>() }.getOrDefault(CreateSessionReq())
        withContext(Dispatchers.IO) { s.access.requireVisible(u, repo) }
        call.respond(HttpStatusCode.Created, s.agents.dto(s.agents.createChat(u, repo, req.model)))
    }
    get("/agents") {
        val u = call.requireUser(s)
        val active = call.request.queryParameters["active"] == "true"
        val list = s.sessions.list(null, null, active).let { l -> if (active) l.filter { s.agents.isActive(it) } else l }
        val visible = withContext(Dispatchers.IO) { list.map { it.repo }.distinct().filter { s.access.canSee(u, it) }.toSet() }
        call.respond(list.filter { it.repo in visible }.map { s.agents.dto(it) })
    }
    get("/sessions/{id}") {
        val u = call.requireUser(s)
        call.respond(s.agents.dto(visibleSession(s, u, uuidParam("id"))))
    }
    get("/sessions/{id}/events") {
        val u = call.requireUser(s)
        val rec = visibleSession(s, u, uuidParam("id"))
        val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 500).coerceIn(1, 5000)
        call.respond(s.events.list(rec.id, after, limit))
    }
    post("/sessions/{id}/input") {
        val u = call.requireUser(s)
        val rec = visibleSession(s, u, uuidParam("id"))
        val req = call.receive<InputReq>()
        val ids = req.attachmentIds.map { runCatching { UUID.fromString(it) }.getOrNull() ?: throw badRequest("Invalid attachment id") }
        if (rec.kind != "chat") {
            // A message for a running pipeline agent (spec-writer / builder), from its owner directly.
            if (rec.ownerId == u.id && ids.isNotEmpty()) throw unprocessable("Messages to pipeline agents are text only", "attachments_not_allowed")
            call.respond(HttpStatusCode.Accepted, s.agents.sendToStageAgent(u, rec, req.text))
            return@post
        }
        s.agents.sendInput(u, rec, req.text, ids)
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
    }
    // ---- attachments (docs/ARCHITECTURE.md §8 "Attachments")
    post("/sessions/{id}/attachments") {
        val u = call.requireUser(s)
        val rec = visibleSession(s, u, uuidParam("id"))
        if (rec.ownerId != u.id) throw forbidden("Only the owner of this session can attach files")
        if (rec.kind != "chat") throw conflict("Only chat sessions take attachments")
        val att = s.attachments
        // Cheap early refusals before reading the body; the store checks again under a lock.
        val unsent = withContext(Dispatchers.IO) { att.store.forSession(rec.id, sentOnly = false) }.filter { it.sentAt == null && it.uploaderId == u.id }
        if (unsent.size >= cfg.attachmentMaxCount) throw att.tooMany()
        val declared = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (declared != null && declared > cfg.attachmentMaxBytes) throw att.fileTooLarge()
        if (declared != null && declared + unsent.sumOf { it.size } > cfg.attachmentMaxMessageBytes) throw att.messageTooLarge()
        // Enforced while streaming: never more than the limit is buffered.
        val bytes = readLimited(call.receiveChannel(), cfg.attachmentMaxBytes) ?: throw att.fileTooLarge()
        val meta = withContext(Dispatchers.IO) { att.upload(rec.id, u.id, call.request.queryParameters["name"], bytes) }
        call.respond(HttpStatusCode.Created, meta.toDto())
    }
    get("/sessions/{id}/attachments/{aid}") {
        val u = call.requireUser(s)
        val rec = visibleSession(s, u, uuidParam("id"))
        val aid = uuidParam("aid")
        // Unsent uploads are the owner's draft: nobody else sees them.
        val m = withContext(Dispatchers.IO) { s.attachments.store.get(aid) }
            ?.takeIf { it.sessionId == rec.id && (it.sentAt != null || it.uploaderId == u.id) } ?: throw notFound("Attachment not found")
        val preview = call.request.queryParameters["preview"] == "1" && m.inlineMode == "downscaled"
        val bytes = withContext(Dispatchers.IO) { if (preview) s.attachments.store.inlineData(m.id) else s.attachments.store.data(m.id) }
            ?: throw notFound("Attachment not found")
        // Only the four raster types are ever served as what they are, and inline; everything else (SVG, HTML, …)
        // is an opaque download, so an attachment can never run script in our origin.
        val image = m.kind == "image" && m.mediaType in INLINE_IMAGE_TYPES
        val type = if (!image) "application/octet-stream" else if (preview) m.inlineMediaType ?: m.mediaType else m.mediaType
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; sandbox")
        call.response.header("Cross-Origin-Resource-Policy", "same-origin")
        call.response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
        call.response.header(HttpHeaders.ContentDisposition, contentDisposition(if (image) "inline" else "attachment", m.name))
        call.respondBytes(bytes, ContentType.parse(type))
    }
    delete("/sessions/{id}/attachments/{aid}") {
        val u = call.requireUser(s)
        val rec = visibleSession(s, u, uuidParam("id"))
        if (rec.ownerId != u.id) throw forbidden("Only the owner of this session can remove attachments")
        val aid = uuidParam("aid")
        val removed = withContext(Dispatchers.IO) { s.attachments.store.deleteUnsent(rec.id, u.id, aid) }
        if (!removed) {
            val m = withContext(Dispatchers.IO) { s.attachments.store.get(aid) }
            if (m != null && m.sessionId == rec.id && m.sentAt != null) throw conflict("The attachment was already sent", "attachment_sent")
            throw notFound("Attachment not found")
        }
        call.respond(HttpStatusCode.NoContent)
    }
    post("/sessions/{id}/interrupt") {
        val u = call.requireUser(s)
        val rec = visibleSession(s, u, uuidParam("id"))
        s.agents.interrupt(u, rec)
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
    }
    post("/sessions/{id}/stop") {
        val u = call.requireUser(s)
        val rec = visibleSession(s, u, uuidParam("id"))
        call.respond(s.agents.dto(s.agents.stop(u, rec)))
    }
    // Close a chat: stop its agent and delete it permanently (docs/ARCHITECTURE.md §8).
    delete("/sessions/{id}") {
        val u = call.requireUser(s)
        val rec = visibleSession(s, u, uuidParam("id"))
        s.agents.closeChat(u, rec)
        call.respond(HttpStatusCode.NoContent)
    }
}

/** Reads a request body, giving up (null) as soon as it exceeds [max] bytes. */
private suspend fun readLimited(ch: io.ktor.utils.io.ByteReadChannel, max: Long): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = ch.readAvailable(buf, 0, buf.size)
        if (n == -1) break
        out.write(buf, 0, n)
        if (out.size() > max) {
            ch.cancel(null)
            return null
        }
    }
    return out.toByteArray()
}

/** `inline|attachment; filename="ascii"; filename*=UTF-8''…` (RFC 6266 / 5987). */
private fun contentDisposition(kind: String, name: String): String {
    val ascii = name.map { if (it.code in 0x20..0x7e && it != '"' && it != '\\') it else '_' }.joinToString("")
    val encoded = java.net.URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")
    return "$kind; filename=\"$ascii\"; filename*=UTF-8''$encoded"
}

// ------------------------------------------------------------------ admin

private fun Route.adminRoutes(s: Services) {
    get("/admin/settings") { call.requireAdmin(s); call.respond(s.settings.get()) }
    put("/admin/settings") {
        call.requireAdmin(s)
        val req = call.receive<SettingsDto>()
        val modelRe = dk.egernet.factory.db.MODEL_RE
        if (!modelRe.matches(req.chatModel)) throw badRequest("Invalid chatModel")
        val unknown = req.pipelineModels.keys - PIPELINE_ROLES.toSet()
        if (unknown.isNotEmpty()) throw badRequest("Unknown roles in pipelineModels: $unknown")
        req.pipelineModels.values.forEach { if (!modelRe.matches(it)) throw badRequest("Invalid model '$it'") }
        if (req.maxFixRounds !in 0..10) throw badRequest("maxFixRounds must be between 0 and 10")
        val merged = req.copy(pipelineModels = dk.egernet.factory.DEFAULT_PIPELINE_MODELS + req.pipelineModels)
        call.respond(s.settings.put(merged))
    }
    get("/admin/users") { call.requireAdmin(s); call.respond(s.users.all().map { it.toDto() }) }
    post("/admin/users/{id}/reset-2fa") {
        val admin = call.requireAdmin(s)
        val target = s.users.byId(uuidParam("id")) ?: throw notFound("User not found")
        s.twoFactor.reset(admin, target, call.remote())
        s.webSessions.deleteAllFor(target.id)
        call.respond(s.users.byId(target.id)!!.toDto())
    }
    get("/admin/software-requests") { call.requireAdmin(s); call.respond(s.softwareRequests.all()) }
    post("/admin/software-requests/{id}") {
        call.requireAdmin(s)
        val req = call.receive<ResolveReq>()
        if (req.status !in setOf("pending", "installed", "denied")) throw badRequest("status must be pending, installed or denied")
        val r: SoftwareRequestDto = s.softwareRequests.resolve(uuidParam("id"), req.status, req.note?.take(4000)) ?: throw notFound("Request not found")
        call.respond(r)
    }
}

// ------------------------------------------------------------------ websockets

/** WebSocket close code for a session socket whose chat was closed (deleted). */
private const val SESSION_CLOSED: Short = 4404

private fun Route.wsRoutes(s: Services) {
    route("/ws") {
        // Authenticate before the upgrade so pending/anonymous callers get a plain 401.
        install(io.ktor.server.application.createRouteScopedPlugin("AgernWsAuth") {
            onCall { call ->
                try {
                    call.requireUser(s)
                } catch (e: ApiException) {
                    call.respond(e.status, ErrorBody(ErrorDetail(e.code, e.message, e.lockedUntil)))
                }
            }
        })
        webSocket("/sessions/{id}") {
            val u = call.requireUser(s)
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val rec = id?.let { s.sessions.get(it) }
            if (rec == null || !withContext(Dispatchers.IO) { s.access.canSee(u, rec.repo) }) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "not found"))
                return@webSocket
            }
            var last = call.request.queryParameters["after"]?.toLongOrNull() ?: 0
            val live = Channel<dk.egernet.factory.EventDto>(Channel.UNLIMITED)
            // Subscribe before reading history so nothing falls in between; duplicates are dropped by seq.
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { s.bus.session(rec.id).collect { live.send(it) } }
            try {
                // Closed between the check above and the subscription: its sentinel was missed.
                if (withContext(Dispatchers.IO) { s.sessions.get(rec.id) } == null) {
                    close(CloseReason(SESSION_CLOSED, "session closed"))
                    return@webSocket
                }
                while (true) {
                    val page = withContext(Dispatchers.IO) { s.events.list(rec.id, last, 1000) }
                    for (e in page) {
                        send(Frame.Text(AppJson.encodeToString(dk.egernet.factory.EventDto.serializer(), e)))
                        last = e.seq
                    }
                    if (page.size < 1000) break
                }
                for (e in live) {
                    if (e.type == EventBus.CLOSED) {
                        close(CloseReason(SESSION_CLOSED, "session closed"))
                        break
                    }
                    if (e.seq != -1L && e.seq <= last) continue
                    send(Frame.Text(AppJson.encodeToString(dk.egernet.factory.EventDto.serializer(), e)))
                    if (e.seq != -1L) last = e.seq
                }
            } finally {
                collector.cancel()
            }
        }
        webSocket("/feed") {
            val u = call.requireUser(s)
            s.bus.feed.collect { item ->
                val visible = withContext(Dispatchers.IO) { s.access.canSee(u, item.repo) }
                if (!visible) return@collect
                val frame = when (item) {
                    is FeedItem.Session -> FeedFrame(type = "session", session = item.session)
                    is FeedItem.Run -> FeedFrame(type = "run", run = item.run)
                    is FeedItem.Issue -> FeedFrame(type = "issue", repo = item.repo, issue = item.issue)
                    is FeedItem.SessionClosed -> FeedFrame(type = "session_closed", repo = item.repo, sessionId = item.sessionId)
                    is FeedItem.Release -> FeedFrame(type = "release", repo = item.repo, release = item.release)
                    is FeedItem.ReleaseDeleted -> FeedFrame(type = "release_deleted", repo = item.repo, releaseId = item.releaseId)
                }
                send(Frame.Text(AppJson.encodeToString(FeedFrame.serializer(), frame)))
            }
        }
    }
}

// ------------------------------------------------------------------ frontend

/**
 * Serves the built frontend with SPA fallback: from FACTORY_WEB_DIR when set, otherwise from classpath
 * resources under `web/` (embedded in the release jar). With neither, non-API paths answer 404.
 */
private fun Route.staticFrontend(cfg: Config) {
    get("{path...}") {
        val segments = call.parameters.getAll("path") ?: emptyList()
        if (segments.any { it == ".." || it == "." || it.contains('\\') || it.contains('\u0000') }) throw notFound("Not found")
        val rel = segments.joinToString("/")
        val dir = cfg.webDir
        if (dir != null) {
            val file = dir.resolve(rel).normalize()
            val target = when {
                file.startsWith(dir) && rel.isNotEmpty() && Files.isRegularFile(file) -> file
                Files.isRegularFile(dir.resolve("index.html")) && !looksLikeAsset(rel) -> dir.resolve("index.html")
                else -> null
            }
            if (target == null) {
                call.respondText("Not found", status = HttpStatusCode.NotFound)
                return@get
            }
            cacheHeaders(call, target.fileName.toString() == "index.html", rel)
            call.respondFile(target.toFile())
            return@get
        }
        val loader = Services::class.java.classLoader
        val (path, bytes) = (if (rel.isNotEmpty()) loader.getResource("web/$rel")?.takeIf { it.protocol != "file" || !java.io.File(it.toURI()).isDirectory }
            ?.let { rel to it.readBytes() } else null)
            ?: (if (!looksLikeAsset(rel)) loader.getResource("web/index.html")?.let { "index.html" to it.readBytes() } else null)
            ?: run {
                call.respondText("Not found", status = HttpStatusCode.NotFound)
                return@get
            }
        cacheHeaders(call, path == "index.html", rel)
        call.respondBytes(bytes, ContentType.defaultForFilePath(path))
    }
}

private fun looksLikeAsset(rel: String) = rel.startsWith("assets/") || rel.substringAfterLast('/').contains('.')

private fun cacheHeaders(call: ApplicationCall, isIndex: Boolean, rel: String) {
    when {
        isIndex -> call.response.header(HttpHeaders.CacheControl, "no-cache")
        rel.startsWith("assets/") -> call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
    }
}
