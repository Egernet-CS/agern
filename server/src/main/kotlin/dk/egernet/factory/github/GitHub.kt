package dk.egernet.factory.github

import dk.egernet.factory.AppJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A GitHub answer that was not a success. [requestId]: GitHub's `x-github-request-id`, when it sent one. */
class GitHubException(val status: Int, message: String, val requestId: String? = null) : RuntimeException(message)

@Serializable
data class GhUser(
    val login: String, val name: String? = null,
    /** Classic tokens: the granted OAuth scopes (`X-OAuth-Scopes`); null when GitHub sends none (fine-grained tokens). */
    @kotlinx.serialization.Transient val scopes: List<String>? = null,
)

/** `X-OAuth-Scopes: repo, read:org` → `[read:org, repo]`; a missing header → null. */
fun parseOAuthScopes(header: String?): List<String>? =
    header?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.sorted()

/** What kind of GitHub token this is, from its prefix (`ghp_` classic, `github_pat_` fine-grained). */
fun githubTokenKind(token: String, scopes: List<String>?): String? = when {
    token.startsWith("github_pat_") -> "fine_grained"
    token.startsWith("ghp_") || token.startsWith("gho_") -> "classic"
    scopes != null -> "classic"
    else -> null
}
@Serializable data class GhLabel(val name: String, val color: String = "ededed")
@Serializable data class GhLogin(val login: String)

@Serializable
data class GhRepo(
    val name: String,
    @SerialName("full_name") val fullName: String,
    val description: String? = null,
    val private: Boolean = false,
    @SerialName("default_branch") val defaultBranch: String = "main",
    val language: String? = null,
    @SerialName("open_issues_count") val openIssuesCount: Int = 0,
    @SerialName("pushed_at") val pushedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("clone_url") val cloneUrl: String = "",
    val archived: Boolean = false,
    /** Merge settings; GitHub may leave them out (e.g. for tokens without push access): null counts as allowed. */
    @SerialName("allow_rebase_merge") val allowRebaseMerge: Boolean? = null,
    @SerialName("allow_merge_commit") val allowMergeCommit: Boolean? = null,
)

@Serializable
data class GhIssue(
    val number: Int,
    val title: String,
    val state: String,
    val labels: List<GhLabel> = emptyList(),
    val user: GhLogin? = null,
    val assignees: List<GhLogin> = emptyList(),
    val comments: Int = 0,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("html_url") val htmlUrl: String,
    val body: String? = null,
    @SerialName("pull_request") val pullRequest: JsonObject? = null,
)

@Serializable
data class GhComment(val user: GhLogin? = null, val body: String? = null, @SerialName("created_at") val createdAt: String = "")

@Serializable data class GhPull(val number: Int, @SerialName("html_url") val htmlUrl: String, val state: String = "open")

@Serializable data class GhRef(val ref: String = "", val sha: String = "")

/**
 * One pull request as `GET /repos/{o}/{r}/pulls/{n}` returns it. [mergeable] is null while GitHub is still computing
 * it; [rebaseable] is false when GitHub cannot rebase the head onto the base (e.g. the head holds merge commits) and
 * null while unknown; [state] is `open` or `closed` (a merged pull request is `closed` with [merged] true).
 */
@Serializable
data class GhPullInfo(
    val number: Int,
    @SerialName("html_url") val htmlUrl: String,
    val state: String = "open",
    val merged: Boolean = false,
    val mergeable: Boolean? = null,
    val rebaseable: Boolean? = null,
    @SerialName("mergeable_state") val mergeableState: String? = null,
    val head: GhRef = GhRef(),
    val base: GhRef = GhRef(),
    @SerialName("merge_commit_sha") val mergeCommitSha: String? = null,
    /** Who merged it; null while open or when GitHub does not report it. */
    @SerialName("merged_by") val mergedBy: GhLogin? = null,
    /** Its title and description (untrusted text; GitHub sends a null body for an empty description). */
    val title: String = "",
    val body: String? = null,
)

/**
 * One commit status (`GET /repos/{o}/{r}/commits/{ref}/status`, the latest per context): [state] is `success`, `pending`,
 * `failure` or `error`; [targetUrl] is where it links to (untrusted).
 */
@Serializable
data class GhCommitStatus(
    val context: String = "", val state: String = "pending", @SerialName("target_url") val targetUrl: String? = null,
)

/** The combined commit status. GitHub answers `state: pending` with [totalCount] 0 when there are no statuses at all. */
@Serializable
data class GhCombinedStatus(
    val state: String = "pending",
    @SerialName("total_count") val totalCount: Int = 0,
    val statuses: List<GhCommitStatus> = emptyList(),
)

/**
 * One check run (GitHub Actions and other apps). [status] is `queued`, `in_progress`, `completed` (or `waiting`,
 * `requested`, `pending`); once completed, [conclusion] is `success`, `neutral`, `skipped`, `failure`, `cancelled`,
 * `timed_out`, `action_required` or `stale`.
 */
@Serializable
data class GhCheckRun(
    val name: String = "", val status: String = "queued", val conclusion: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
)

/**
 * One GitHub Actions workflow run (`GET /repos/{o}/{r}/actions/runs?head_sha=`). A run that is queued but not started,
 * or that ended in `startup_failure`, has no check runs: only the workflow run shows it. [status] is `queued`,
 * `in_progress`, `completed` (or `waiting`, `requested`, `pending`); once completed, [conclusion] is e.g. `success`,
 * `failure`, `cancelled`, `startup_failure`, `timed_out`.
 */
@Serializable
data class GhWorkflowRun(
    val id: Long = 0, val name: String? = null, @SerialName("workflow_id") val workflowId: Long = 0, val event: String = "",
    val status: String = "queued", val conclusion: String? = null, @SerialName("html_url") val htmlUrl: String? = null,
)

@Serializable
private data class GhWorkflowRunPage(
    @SerialName("total_count") val totalCount: Int = 0, @SerialName("workflow_runs") val workflowRuns: List<GhWorkflowRun> = emptyList(),
)

@Serializable
private data class GhCheckRunPage(@SerialName("total_count") val totalCount: Int = 0, @SerialName("check_runs") val checkRuns: List<GhCheckRun> = emptyList())

/** One check that has not passed: its [name], where it links to (untrusted) and, for a failed one, its conclusion. */
data class CheckItem(val name: String, val url: String? = null, val conclusion: String? = null)

/**
 * What the checks on one commit add up to (docs/ARCHITECTURE.md §6 "Checks"); [failing] and [pending] name the checks
 * behind a `failed` or `pending` state.
 */
data class ChecksSummary(
    val state: String, val detail: String,
    val failing: List<CheckItem> = emptyList(), val pending: List<CheckItem> = emptyList(),
) {
    companion object {
        private val PASSING_CONCLUSIONS = setOf("success", "neutral", "skipped")

        /** Conclusions of checks that did not run to completion: re-running them on GitHub may be all it takes. */
        val INCOMPLETE_CONCLUSIONS = setOf("startup_failure", "cancelled", "timed_out", "stale")

        /**
         * `failed` if any status is `failure`/`error`, any completed check run did not pass or any completed workflow run
         * did not pass; else `pending` if any status is `pending` or any check run or workflow run is not completed; else
         * `passed`, or `none` when there are no checks. Of [workflowRuns], only the latest (highest id) per workflow and
         * event counts.
         */
        fun of(status: GhCombinedStatus, runs: List<GhCheckRun>, workflowRuns: List<GhWorkflowRun> = emptyList()): ChecksSummary {
            val failing = mutableListOf<CheckItem>()
            val pending = mutableListOf<CheckItem>()
            var passed = 0
            for (st in status.statuses) when (st.state) {
                "success" -> passed++
                "failure", "error" -> failing += CheckItem(st.context, st.targetUrl, st.state)
                else -> pending += CheckItem(st.context, st.targetUrl)
            }
            for (run in runs) when {
                run.status != "completed" -> pending += CheckItem(run.name, run.htmlUrl)
                run.conclusion in PASSING_CONCLUSIONS -> passed++
                else -> failing += CheckItem(run.name, run.htmlUrl, run.conclusion ?: "unknown")
            }
            val latest = workflowRuns.groupBy { it.workflowId to it.event }.values.map { l -> l.maxBy { it.id } }.sortedBy { it.id }
            for (wr in latest) {
                val name = wr.name?.takeIf { it.isNotBlank() } ?: "workflow ${wr.workflowId}"
                when {
                    wr.status != "completed" -> pending += CheckItem(name, wr.htmlUrl)
                    wr.conclusion in PASSING_CONCLUSIONS -> passed++
                    else -> failing += CheckItem(name, wr.htmlUrl, wr.conclusion ?: "unknown")
                }
            }
            // More statuses than were listed: the combined state still covers them.
            if (status.totalCount > status.statuses.size) when (status.state) {
                "failure", "error" -> if (failing.isEmpty()) failing += CheckItem("commit status", null, status.state)
                "pending" -> if (pending.isEmpty()) pending += CheckItem("commit status")
            }
            fun names(l: List<CheckItem>) = l.map { it.name }.distinct().joinToString(", ").take(500)
            fun distinct(l: List<CheckItem>) = l.distinctBy { it.name to it.conclusion }
            return when {
                failing.isNotEmpty() -> ChecksSummary("failed", "Failed: ${names(failing)}", distinct(failing), distinct(pending))
                pending.isNotEmpty() -> ChecksSummary("pending", "Pending: ${names(pending)}", pending = distinct(pending))
                passed == 0 -> ChecksSummary("none", "No checks")
                else -> ChecksSummary("passed", "$passed check${if (passed == 1) "" else "s"} passed")
            }
        }

        /**
         * Reads the checks on [sha] of [repo] (commit statuses, check runs and GitHub Actions workflow runs) and sums them
         * up with [of]: the one rule set for runs and releases. A refused or failed read of the statuses or check runs
         * throws; a refused or failed read of the workflow runs alone is passed to [onWorkflowRunsError] and ignored (a
         * token without Actions access still sees statuses and check runs). Blocking.
         */
        fun read(
            client: GitHubClient, org: String, repo: String, sha: String, onWorkflowRunsError: (Exception) -> Unit = {},
        ): ChecksSummary {
            val status = client.combinedStatus(org, repo, sha)
            val runs = client.checkRuns(org, repo, sha)
            val workflowRuns = try {
                client.workflowRuns(org, repo, sha)
            } catch (e: Exception) {
                onWorkflowRunsError(e)
                emptyList()
            }
            return of(status, runs, workflowRuns)
        }
    }
}

/** Everything the factory needs from GitHub, always acting with one user's token. */
interface GitHubClient {
    fun currentUser(): GhUser
    fun listOrgRepos(org: String): List<GhRepo>
    /** null when the repository does not exist or the token cannot read it. */
    fun getRepo(org: String, repo: String): GhRepo?
    fun listIssues(org: String, repo: String, state: String): List<GhIssue>
    fun getIssue(org: String, repo: String, number: Int): GhIssue?
    fun listComments(org: String, repo: String, number: Int): List<GhComment>
    fun createIssue(org: String, repo: String, title: String, body: String, labels: List<String>): GhIssue
    fun comment(org: String, repo: String, number: Int, body: String)
    fun addLabels(org: String, repo: String, number: Int, labels: List<String>)
    fun removeLabel(org: String, repo: String, number: Int, label: String)
    fun closeIssue(org: String, repo: String, number: Int)
    fun findOpenPull(org: String, repo: String, branch: String): GhPull?
    fun createPull(org: String, repo: String, head: String, base: String, title: String, body: String): GhPull
    /**
     * Squash-merges a pull request. [expectedHeadSha], when given, makes GitHub refuse (409) if the head branch moved
     * since it was read; refusals surface as [GitHubException].
     */
    fun squashMerge(org: String, repo: String, number: Int, title: String, expectedHeadSha: String? = null)
    /** A file's raw content on [ref] (a branch name), or null when it does not exist there. */
    fun getFile(org: String, repo: String, path: String, ref: String): String?
    /** A file's raw bytes on [ref], at most [maxBytes] of them (the rest is not read); null when the file does not exist. */
    fun getFileBytes(org: String, repo: String, path: String, ref: String, maxBytes: Long): ByteArray?
    /** The commit a branch points at; null when the branch does not exist. */
    fun branchSha(org: String, repo: String, branch: String): String?
    /** Creates [branch] at [sha]. An existing branch surfaces as [GitHubException] 422. */
    fun createBranch(org: String, repo: String, branch: String, sha: String)
    /** One pull request with its mergeability; null when it does not exist. */
    fun getPull(org: String, repo: String, number: Int): GhPullInfo?
    /**
     * Requests [reviewers] (GitHub logins) and [teamReviewers] (team slugs of [org]) on pull request [number]
     * (`POST /repos/{o}/{r}/pulls/{n}/requested_reviewers`). Nothing is sent when both are empty.
     */
    fun requestReviewers(org: String, repo: String, number: Int, reviewers: List<String>, teamReviewers: List<String>)
    /** Replaces a pull request's title and body. */
    fun updatePull(org: String, repo: String, number: Int, title: String, body: String)
    /**
     * Merges a pull request without squashing: [method] is `rebase` or `merge` (a merge commit titled [commitTitle]).
     * [expectedHeadSha], when given, makes GitHub refuse (409) if the head branch moved since it was read. Returns the
     * resulting commit's SHA when GitHub reports it. Refusals surface as [GitHubException] (405 not mergeable or method
     * not allowed, 409 head moved).
     */
    fun mergePull(org: String, repo: String, number: Int, method: String, commitTitle: String?, expectedHeadSha: String?): String?
    /** Deletes [branch]. A branch that does not exist is not an error. */
    fun deleteBranch(org: String, repo: String, branch: String)
    /** The combined commit status of [sha] (the latest status per context). */
    fun combinedStatus(org: String, repo: String, sha: String): GhCombinedStatus
    /** The latest check runs of [sha] (at most a few hundred). */
    fun checkRuns(org: String, repo: String, sha: String): List<GhCheckRun>
    /** The GitHub Actions workflow runs of head commit [sha] (at most 100, newest first). */
    fun workflowRuns(org: String, repo: String, sha: String): List<GhWorkflowRun>
    /** Whether commit [sha] exists in [repo] (`GET /repos/{o}/{r}/commits/{sha}`: 404 and 422 mean it does not). */
    fun commitExists(org: String, repo: String, sha: String): Boolean
    /** The commit the gitlink (submodule pointer) at [path] points at on [ref]; null when [path] is no gitlink there. */
    fun gitlinkAt(org: String, repo: String, path: String, ref: String): String?
    /**
     * Creates (without moving any branch) a commit with the single parent [parentSha] whose tree is the parent's with
     * the gitlink at [path] set to [sha] (Git Data API: tree with `base_tree`, then commit). Returns the new commit's SHA.
     */
    fun createGitlinkCommit(org: String, repo: String, parentSha: String, path: String, sha: String, message: String): String =
        createGitlinksCommit(org, repo, parentSha, mapOf(path to sha), message)
    /**
     * Creates (without moving any branch) a commit with the single parent [parentSha] whose tree is the parent's with
     * every gitlink in [links] (path → commit) set, in one tree. Returns the new commit's SHA.
     */
    fun createGitlinksCommit(org: String, repo: String, parentSha: String, links: Map<String, String>, message: String): String
    /**
     * Moves the existing [branch] to [sha]. With [force] (the default) also when that is not a fast-forward; without,
     * GitHub refuses (422) an update that is not a fast-forward, e.g. because the branch moved meanwhile.
     */
    fun forceUpdateBranch(org: String, repo: String, branch: String, sha: String, force: Boolean = true)
    /** The repository's issue labels (at most a few thousand). */
    fun listLabels(org: String, repo: String): List<GhLabel>
    /**
     * Creates the issue label [name] with [color] (6 hex digits) and [description]. False when a label of that name
     * already exists (422 `already_exists`); other refusals surface as [GitHubException] (403/404: no permission).
     */
    fun createLabel(org: String, repo: String, name: String, color: String, description: String): Boolean
    /**
     * This token's REST (`core`) and GraphQL limits from `GET /rate_limit`; null when GitHub has rate limiting off
     * (GitHub Enterprise) or the implementation does not know them.
     */
    fun rateLimit(): GhRateLimits? = null
}

/** The merge methods [GitHubClient.mergePull] accepts: never `squash`. */
val NON_SQUASH_MERGE_METHODS = setOf("rebase", "merge")

/** Creates clients per token. Real implementation talks to the REST API; tests and dev mode use [FakeGitHub]. */
interface GitHub {
    fun client(token: String): GitHubClient
    /** Clone URL for a repo (no credentials in it; auth is injected via environment). */
    fun cloneUrl(org: String, repo: String): String
    val isFake: Boolean get() = false
}

/**
 * GitHub's REST API. Every exchange goes through one function that reports its outcome to [health] (docs/ARCHITECTURE.md
 * §6 "GitHub status"), when given.
 */
class HttpGitHub(
    private val apiUrl: String = "https://api.github.com",
    private val webUrl: String = "https://github.com",
    private val health: GitHubHealth? = null,
) : GitHub {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()

    override fun client(token: String): GitHubClient = Client(token)
    override fun cloneUrl(org: String, repo: String) = "$webUrl/$org/$repo.git"

    private inner class Client(private val token: String) : GitHubClient {
        /** The token's fingerprint for the rate-limit observations; the token itself never reaches [health]. */
        private val fingerprint: String? by lazy { if (health == null) null else GitHubHealth.fingerprint(token) }

        private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

        private fun request(method: String, path: String, body: JsonObject? = null, accept: String = "application/vnd.github+json"): HttpRequest {
            val b = HttpRequest.newBuilder(URI.create(if (path.startsWith("http")) path else apiUrl.trimEnd('/') + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $token")
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "agern")
            val pub = if (body != null) HttpRequest.BodyPublishers.ofString(body.toString()) else HttpRequest.BodyPublishers.noBody()
            if (body != null) b.header("Content-Type", "application/json")
            b.method(method, pub)
            return b.build()
        }

        /**
         * The one place an HTTP exchange with GitHub happens: sends [req] and records the outcome (the answer's status,
         * headers and [bodyMessage], or the exception, which is rethrown unchanged).
         */
        private fun <T> exchange(
            req: HttpRequest, path: String, handler: HttpResponse.BodyHandler<T>, bodyMessage: (HttpResponse<T>) -> String?,
        ): HttpResponse<T> {
            val r = try {
                http.send(req, handler)
            } catch (e: Throwable) {
                health?.recordHttpFailure(relative(path), e)
                throw e
            }
            health?.let { h ->
                val msg = if (r.statusCode() >= 400) runCatching { bodyMessage(r) }.getOrNull() else null
                h.recordHttp(relative(path), r.statusCode(), { name -> r.headers().firstValue(name).orElse(null) }, msg, fingerprint)
            }
            return r
        }

        /** [path] without the API base when it is an absolute URL on it (pagination links). */
        private fun relative(path: String): String {
            val base = apiUrl.trimEnd('/')
            return if (path.startsWith("$base/")) path.removePrefix(base) else path
        }

        private fun send(method: String, path: String, body: JsonObject? = null, accept: String = "application/vnd.github+json"): HttpResponse<String> =
            exchange(request(method, path, body, accept), path, HttpResponse.BodyHandlers.ofString()) { messageOf(it.body()) }

        private fun ok(r: HttpResponse<String>, what: String): String {
            if (r.statusCode() in 200..299) return r.body()
            throw failure(r.statusCode(), r.body(), what, r.headers())
        }

        /** GitHub's `message` of an error body; null when the body is no JSON object with a string `message`. */
        private fun messageOf(body: String?): String? = runCatching {
            ((AppJson.parseToJsonElement(body ?: return null) as? JsonObject)?.get("message") as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString }?.content
        }.getOrNull()

        private fun failure(status: Int, body: String, what: String, headers: java.net.http.HttpHeaders): GitHubException {
            val requestId = GitHubHealth.validRequestId(headers.firstValue(GitHubHealth.REQUEST_ID_HEADER).orElse(null))
            if (status >= 500) return GitHubFailure.serverError(what, status, requestId, messageOf(body), health?.org ?: "")
            val msg = runCatching { AppJson.parseToJsonElement(body).let { (it as JsonObject)["message"].toString().trim('"') } }.getOrNull()
            return GitHubException(status, "GitHub $what failed: $status ${msg ?: ""}".trim(), requestId)
        }

        private fun nextLink(r: HttpResponse<String>): String? =
            r.headers().firstValue("link").orElse(null)?.split(',')?.firstOrNull { it.contains("rel=\"next\"") }
                ?.substringAfter('<')?.substringBefore('>')

        private fun <T> paged(path: String, ser: kotlinx.serialization.KSerializer<T>, maxPages: Int = 10): List<T> {
            val out = mutableListOf<T>()
            var url: String? = path
            var pages = 0
            while (url != null && pages < maxPages) {
                val r = send("GET", url)
                out += AppJson.decodeFromString(ListSerializer(ser), ok(r, "GET $path"))
                url = nextLink(r)
                pages++
            }
            return out
        }

        override fun currentUser(): GhUser {
            val r = send("GET", "/user")
            return AppJson.decodeFromString(GhUser.serializer(), ok(r, "GET /user"))
                .copy(scopes = parseOAuthScopes(r.headers().firstValue("x-oauth-scopes").orElse(null)))
        }

        override fun listOrgRepos(org: String) = paged("/orgs/${enc(org)}/repos?per_page=100&sort=pushed", GhRepo.serializer())

        override fun getRepo(org: String, repo: String): GhRepo? {
            val r = send("GET", "/repos/${enc(org)}/${enc(repo)}")
            if (r.statusCode() == 404 || r.statusCode() == 403 || r.statusCode() == 401) return null
            return AppJson.decodeFromString(GhRepo.serializer(), ok(r, "GET repo"))
        }

        override fun listIssues(org: String, repo: String, state: String) =
            paged("/repos/${enc(org)}/${enc(repo)}/issues?state=${enc(state)}&per_page=100", GhIssue.serializer(), maxPages = 5)
                .filter { it.pullRequest == null }

        override fun getIssue(org: String, repo: String, number: Int): GhIssue? {
            val r = send("GET", "/repos/${enc(org)}/${enc(repo)}/issues/$number")
            if (r.statusCode() == 404 || r.statusCode() == 410) return null
            return AppJson.decodeFromString(GhIssue.serializer(), ok(r, "GET issue")).takeIf { it.pullRequest == null }
        }

        override fun listComments(org: String, repo: String, number: Int) =
            paged("/repos/${enc(org)}/${enc(repo)}/issues/$number/comments?per_page=100", GhComment.serializer(), maxPages = 3)

        override fun createIssue(org: String, repo: String, title: String, body: String, labels: List<String>): GhIssue {
            val payload = buildJsonObject {
                put("title", title); put("body", body)
                putJsonArray("labels") { labels.forEach { add(it) } }
            }
            return AppJson.decodeFromString(GhIssue.serializer(), ok(send("POST", "/repos/${enc(org)}/${enc(repo)}/issues", payload), "create issue"))
        }

        override fun comment(org: String, repo: String, number: Int, body: String) {
            ok(send("POST", "/repos/${enc(org)}/${enc(repo)}/issues/$number/comments", buildJsonObject { put("body", body) }), "comment")
        }

        override fun addLabels(org: String, repo: String, number: Int, labels: List<String>) {
            ok(send("POST", "/repos/${enc(org)}/${enc(repo)}/issues/$number/labels",
                buildJsonObject { putJsonArray("labels") { labels.forEach { add(it) } } }), "add labels")
        }

        override fun removeLabel(org: String, repo: String, number: Int, label: String) {
            val r = send("DELETE", "/repos/${enc(org)}/${enc(repo)}/issues/$number/labels/${enc(label)}")
            if (r.statusCode() != 404) ok(r, "remove label")
        }

        override fun closeIssue(org: String, repo: String, number: Int) {
            ok(send("PATCH", "/repos/${enc(org)}/${enc(repo)}/issues/$number",
                buildJsonObject { put("state", "closed"); put("state_reason", "completed") }), "close issue")
        }

        override fun findOpenPull(org: String, repo: String, branch: String): GhPull? {
            val r = send("GET", "/repos/${enc(org)}/${enc(repo)}/pulls?state=open&head=${enc("$org:$branch")}")
            return AppJson.decodeFromString(ListSerializer(GhPull.serializer()), ok(r, "list pulls")).firstOrNull()
        }

        override fun createPull(org: String, repo: String, head: String, base: String, title: String, body: String): GhPull {
            val payload = buildJsonObject { put("title", title); put("head", head); put("base", base); put("body", body) }
            return AppJson.decodeFromString(GhPull.serializer(), ok(send("POST", "/repos/${enc(org)}/${enc(repo)}/pulls", payload), "create pull request"))
        }

        override fun getFile(org: String, repo: String, path: String, ref: String): String? {
            val p = path.split('/').joinToString("/") { enc(it) }
            val r = send("GET", "/repos/${enc(org)}/${enc(repo)}/contents/$p?ref=${enc(ref)}", accept = "application/vnd.github.raw+json")
            if (r.statusCode() == 404) return null
            return ok(r, "GET contents")
        }

        override fun getFileBytes(org: String, repo: String, path: String, ref: String, maxBytes: Long): ByteArray? {
            val p = path.split('/').joinToString("/") { enc(it) }
            val path = "/repos/${enc(org)}/${enc(repo)}/contents/$p?ref=${enc(ref)}"
            val req = request("GET", path, accept = "application/vnd.github.raw+json")
            // The body is a stream that is read only below: GitHub's message is not known when the exchange is recorded.
            val r = exchange(req, path, HttpResponse.BodyHandlers.ofInputStream()) { null }
            // Closing the stream early drops the rest of the body: never more than the limit is read.
            return r.body().use { body ->
                when (r.statusCode()) {
                    404 -> null
                    in 200..299 -> body.readNBytes(maxBytes.coerceIn(0, Int.MAX_VALUE.toLong() - 8).toInt())
                    else -> throw failure(r.statusCode(), String(body.readNBytes(64 * 1024), Charsets.UTF_8), "GET contents", r.headers())
                }
            }
        }

        override fun squashMerge(org: String, repo: String, number: Int, title: String, expectedHeadSha: String?) {
            ok(send("PUT", "/repos/${enc(org)}/${enc(repo)}/pulls/$number/merge",
                buildJsonObject {
                    put("merge_method", "squash"); put("commit_title", title)
                    if (expectedHeadSha != null) put("sha", expectedHeadSha)
                }), "merge pull request")
        }

        /** A branch name in a URL path: each segment encoded, the `/` between them kept. */
        private fun encBranch(branch: String) = branch.split('/').joinToString("/") { enc(it) }

        override fun branchSha(org: String, repo: String, branch: String): String? {
            val r = send("GET", "/repos/${enc(org)}/${enc(repo)}/git/ref/heads/${encBranch(branch)}")
            if (r.statusCode() == 404) return null
            val o = AppJson.parseToJsonElement(ok(r, "get branch")) as? JsonObject ?: return null
            return ((o["object"] as? JsonObject)?.get("sha") as? kotlinx.serialization.json.JsonPrimitive)?.content
        }

        override fun createBranch(org: String, repo: String, branch: String, sha: String) {
            ok(send("POST", "/repos/${enc(org)}/${enc(repo)}/git/refs",
                buildJsonObject { put("ref", "refs/heads/$branch"); put("sha", sha) }), "create branch")
        }

        override fun getPull(org: String, repo: String, number: Int): GhPullInfo? {
            val r = send("GET", "/repos/${enc(org)}/${enc(repo)}/pulls/$number")
            if (r.statusCode() == 404) return null
            return AppJson.decodeFromString(GhPullInfo.serializer(), ok(r, "GET pull request"))
        }

        override fun requestReviewers(org: String, repo: String, number: Int, reviewers: List<String>, teamReviewers: List<String>) {
            if (reviewers.isEmpty() && teamReviewers.isEmpty()) return
            ok(send("POST", "/repos/${enc(org)}/${enc(repo)}/pulls/$number/requested_reviewers",
                buildJsonObject {
                    putJsonArray("reviewers") { reviewers.forEach { add(it) } }
                    putJsonArray("team_reviewers") { teamReviewers.forEach { add(it) } }
                }), "request reviewers")
        }

        override fun updatePull(org: String, repo: String, number: Int, title: String, body: String) {
            ok(send("PATCH", "/repos/${enc(org)}/${enc(repo)}/pulls/$number",
                buildJsonObject { put("title", title); put("body", body) }), "update pull request")
        }

        override fun mergePull(org: String, repo: String, number: Int, method: String, commitTitle: String?, expectedHeadSha: String?): String? {
            require(method in NON_SQUASH_MERGE_METHODS) { "Unsupported merge method $method" }
            val payload = buildJsonObject {
                put("merge_method", method)
                if (method == "merge" && commitTitle != null) put("commit_title", commitTitle)
                if (expectedHeadSha != null) put("sha", expectedHeadSha)
            }
            val body = ok(send("PUT", "/repos/${enc(org)}/${enc(repo)}/pulls/$number/merge", payload), "merge pull request")
            return runCatching {
                ((AppJson.parseToJsonElement(body) as JsonObject)["sha"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            }.getOrNull()
        }

        override fun deleteBranch(org: String, repo: String, branch: String) {
            val r = send("DELETE", "/repos/${enc(org)}/${enc(repo)}/git/refs/heads/${encBranch(branch)}")
            // Already gone: 404, or 422 "Reference does not exist".
            if (r.statusCode() == 404 || (r.statusCode() == 422 && r.body().contains("Reference does not exist"))) return
            ok(r, "delete branch")
        }

        override fun combinedStatus(org: String, repo: String, sha: String): GhCombinedStatus {
            // per_page caps the statuses listed; the combined state itself covers every context.
            val r = send("GET", "/repos/${enc(org)}/${enc(repo)}/commits/${enc(sha)}/status?per_page=100")
            return AppJson.decodeFromString(GhCombinedStatus.serializer(), ok(r, "GET commit status"))
        }

        override fun commitExists(org: String, repo: String, sha: String): Boolean {
            val r = send("GET", "/repos/${enc(org)}/${enc(repo)}/commits/${enc(sha)}")
            if (r.statusCode() == 404 || r.statusCode() == 422) return false
            ok(r, "GET commit")
            return true
        }

        override fun gitlinkAt(org: String, repo: String, path: String, ref: String): String? {
            val p = path.split('/').joinToString("/") { enc(it) }
            val r = send("GET", "/repos/${enc(org)}/${enc(repo)}/contents/$p?ref=${enc(ref)}")
            if (r.statusCode() == 404) return null
            val o = AppJson.parseToJsonElement(ok(r, "GET contents")) as? JsonObject ?: return null
            if ((o["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "submodule") return null
            return (o["sha"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        }

        private fun shaOf(body: String, vararg path: String): String? {
            var e: kotlinx.serialization.json.JsonElement? = AppJson.parseToJsonElement(body)
            for (k in path) e = (e as? JsonObject)?.get(k)
            return (e as? kotlinx.serialization.json.JsonPrimitive)?.content
        }

        override fun createGitlinksCommit(org: String, repo: String, parentSha: String, links: Map<String, String>, message: String): String {
            require(links.isNotEmpty()) { "No gitlink to set" }
            val base = "/repos/${enc(org)}/${enc(repo)}/git"
            val parentTree = shaOf(ok(send("GET", "$base/commits/${enc(parentSha)}"), "GET commit"), "tree", "sha")
                ?: throw GitHubException(502, "GitHub GET commit returned no tree")
            val tree = shaOf(ok(send("POST", "$base/trees", buildJsonObject {
                put("base_tree", parentTree)
                putJsonArray("tree") {
                    for ((path, sha) in links) {
                        add(buildJsonObject { put("path", path); put("mode", "160000"); put("type", "commit"); put("sha", sha) })
                    }
                }
            }), "create tree"), "sha") ?: throw GitHubException(502, "GitHub create tree returned no sha")
            return shaOf(ok(send("POST", "$base/commits", buildJsonObject {
                put("message", message)
                put("tree", tree)
                putJsonArray("parents") { add(parentSha) }
            }), "create commit"), "sha") ?: throw GitHubException(502, "GitHub create commit returned no sha")
        }

        override fun listLabels(org: String, repo: String): List<GhLabel> =
            paged("/repos/${enc(org)}/${enc(repo)}/labels?per_page=100", GhLabel.serializer(), maxPages = 30)

        override fun createLabel(org: String, repo: String, name: String, color: String, description: String): Boolean {
            val r = send("POST", "/repos/${enc(org)}/${enc(repo)}/labels",
                buildJsonObject { put("name", name); put("color", color); put("description", description) })
            if (r.statusCode() == 422 && r.body().contains("already_exists")) return false
            ok(r, "create label")
            return true
        }

        override fun forceUpdateBranch(org: String, repo: String, branch: String, sha: String, force: Boolean) {
            ok(send("PATCH", "/repos/${enc(org)}/${enc(repo)}/git/refs/heads/${encBranch(branch)}",
                buildJsonObject { put("sha", sha); put("force", force) }), "update branch")
        }

        override fun rateLimit(): GhRateLimits? {
            val r = send("GET", "/rate_limit")
            if (r.statusCode() == 404) return null
            val resources = (AppJson.parseToJsonElement(ok(r, "GET rate limit")) as? JsonObject)?.get("resources") as? JsonObject ?: return null
            val now = java.time.Instant.now()
            fun one(name: String): GhRateLimit? {
                val o = resources[name] as? JsonObject ?: return null
                fun n(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
                val limit = n("limit") ?: return null
                val remaining = n("remaining") ?: return null
                val reset = n("reset") ?: return null
                return GhRateLimit(limit.toInt(), remaining.toInt(), java.time.Instant.ofEpochSecond(reset), now)
            }
            return GhRateLimits(one("core"), one("graphql"))
        }

        override fun checkRuns(org: String, repo: String, sha: String): List<GhCheckRun> {
            val out = mutableListOf<GhCheckRun>()
            var page = 1
            while (page <= 5) {
                val r = send("GET", "/repos/${enc(org)}/${enc(repo)}/commits/${enc(sha)}/check-runs?filter=latest&per_page=100&page=$page")
                val p = AppJson.decodeFromString(GhCheckRunPage.serializer(), ok(r, "GET check runs"))
                out += p.checkRuns
                if (p.checkRuns.isEmpty() || out.size >= p.totalCount) break
                page++
            }
            return out
        }

        override fun workflowRuns(org: String, repo: String, sha: String): List<GhWorkflowRun> {
            val r = send("GET", "/repos/${enc(org)}/${enc(repo)}/actions/runs?head_sha=${enc(sha)}&per_page=100")
            return AppJson.decodeFromString(GhWorkflowRunPage.serializer(), ok(r, "GET workflow runs")).workflowRuns
        }
    }
}
