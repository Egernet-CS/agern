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
}

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

        private fun send(method: String, path: String, body: JsonObject? = null): HttpResponse<String> {
            val b = HttpRequest.newBuilder(URI.create(if (path.startsWith("http")) path else apiUrl.trimEnd('/') + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
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

        override fun squashMerge(org: String, repo: String, number: Int, title: String) {
            ok(send("PUT", "/repos/${enc(org)}/${enc(repo)}/pulls/$number/merge",
                buildJsonObject { put("merge_method", "squash"); put("commit_title", title) }), "merge pull request")
        }
    }
}
