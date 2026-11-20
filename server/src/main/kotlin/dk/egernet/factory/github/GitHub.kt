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

class GitHubException(val status: Int, message: String) : RuntimeException(message)

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
 * it; [state] is `open` or `closed` (a merged pull request is `closed` with [merged] true).
 */
@Serializable
data class GhPullInfo(
    val number: Int,
    @SerialName("html_url") val htmlUrl: String,
    val state: String = "open",
    val merged: Boolean = false,
    val mergeable: Boolean? = null,
    @SerialName("mergeable_state") val mergeableState: String? = null,
    val head: GhRef = GhRef(),
    val base: GhRef = GhRef(),
    @SerialName("merge_commit_sha") val mergeCommitSha: String? = null,
)

/** One commit status (`GET /repos/{o}/{r}/commits/{ref}/status`, the latest per context): [state] is `success`, `pending`, `failure` or `error`. */
@Serializable data class GhCommitStatus(val context: String = "", val state: String = "pending")

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
@Serializable data class GhCheckRun(val name: String = "", val status: String = "queued", val conclusion: String? = null)

@Serializable
private data class GhCheckRunPage(@SerialName("total_count") val totalCount: Int = 0, @SerialName("check_runs") val checkRuns: List<GhCheckRun> = emptyList())

/** What the checks on one commit add up to (docs/ARCHITECTURE.md §6 "Releasing", "Checks"). */
data class ChecksSummary(val state: String, val detail: String) {
    companion object {
        private val PASSING_CONCLUSIONS = setOf("success", "neutral", "skipped")

        /**
         * `failed` if any status is `failure`/`error` or any completed check run did not pass; else `pending` if any
         * status is `pending` or any check run is not completed; else `passed`, or `none` when there are no checks.
         */
        fun of(status: GhCombinedStatus, runs: List<GhCheckRun>): ChecksSummary {
            val failing = mutableListOf<String>()
            val pending = mutableListOf<String>()
            var passed = 0
            for (st in status.statuses) when (st.state) {
                "success" -> passed++
                "failure", "error" -> failing += st.context
                else -> pending += st.context
            }
            for (run in runs) when {
                run.status != "completed" -> pending += run.name
                run.conclusion in PASSING_CONCLUSIONS -> passed++
                else -> failing += run.name
            }
            // More statuses than were listed: the combined state still covers them.
            if (status.totalCount > status.statuses.size) when (status.state) {
                "failure", "error" -> if (failing.isEmpty()) failing += "commit status"
                "pending" -> if (pending.isEmpty()) pending += "commit status"
            }
            fun names(l: List<String>) = l.distinct().joinToString(", ").take(500)
            return when {
                failing.isNotEmpty() -> ChecksSummary("failed", "Failed: ${names(failing)}")
                pending.isNotEmpty() -> ChecksSummary("pending", "Pending: ${names(pending)}")
                passed == 0 -> ChecksSummary("none", "No checks")
                else -> ChecksSummary("passed", "$passed check${if (passed == 1) "" else "s"} passed")
            }
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
    fun squashMerge(org: String, repo: String, number: Int, title: String)
    /** A file's raw content on [ref] (a branch name), or null when it does not exist there. */
    fun getFile(org: String, repo: String, path: String, ref: String): String?
    /** The commit a branch points at; null when the branch does not exist. */
    fun branchSha(org: String, repo: String, branch: String): String?
    /** Creates [branch] at [sha]. An existing branch surfaces as [GitHubException] 422. */
    fun createBranch(org: String, repo: String, branch: String, sha: String)
    /** One pull request with its mergeability; null when it does not exist. */
    fun getPull(org: String, repo: String, number: Int): GhPullInfo?
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

class HttpGitHub(private val apiUrl: String = "https://api.github.com", private val webUrl: String = "https://github.com") : GitHub {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()

    override fun client(token: String): GitHubClient = Client(token)
    override fun cloneUrl(org: String, repo: String) = "$webUrl/$org/$repo.git"

    private inner class Client(private val token: String) : GitHubClient {
        private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

        private fun send(method: String, path: String, body: JsonObject? = null, accept: String = "application/vnd.github+json"): HttpResponse<String> {
            val b = HttpRequest.newBuilder(URI.create(if (path.startsWith("http")) path else apiUrl.trimEnd('/') + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $token")
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "agern")
            val pub = if (body != null) HttpRequest.BodyPublishers.ofString(body.toString()) else HttpRequest.BodyPublishers.noBody()
            if (body != null) b.header("Content-Type", "application/json")
            b.method(method, pub)
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        }

        private fun ok(r: HttpResponse<String>, what: String): String {
            if (r.statusCode() in 200..299) return r.body()
            val msg = runCatching { AppJson.parseToJsonElement(r.body()).let { (it as JsonObject)["message"].toString().trim('"') } }.getOrNull()
            throw GitHubException(r.statusCode(), "GitHub $what failed: ${r.statusCode()} ${msg ?: ""}".trim())
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

        override fun squashMerge(org: String, repo: String, number: Int, title: String) {
            ok(send("PUT", "/repos/${enc(org)}/${enc(repo)}/pulls/$number/merge",
                buildJsonObject { put("merge_method", "squash"); put("commit_title", title) }), "merge pull request")
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
    }
}
