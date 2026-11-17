package dk.egernet.factory.github

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory GitHub for tests and for UI development (`FACTORY_DEV_FAKE_GITHUB=true`, dev auth only).
 * Tokens of the form `fake-<login>` are valid and act like a classic token with the `repo` and `read:org`
 * scopes; `fake-<login>~<scope>,<scope>` grants only those scopes (without `repo`: public repositories only).
 * Repos with an [visibleTo] set are only visible to those logins.
 */
class FakeGitHub(private val org: String) : GitHub {
    data class Repo(val name: String, val description: String?, val private: Boolean, val visibleTo: Set<String>? = null,
                    val issues: MutableList<GhIssue> = mutableListOf(), val comments: MutableMap<Int, MutableList<GhComment>> = mutableMapOf())

    val repos = mutableMapOf<String, Repo>()
    val actions = CopyOnWriteArrayList<String>()
    val pulls = mutableMapOf<String, MutableList<GhPull>>()
    /** Lets tests make merges fail. */
    @Volatile var failMerge = false

    override val isFake = true
    override fun cloneUrl(org: String, repo: String) = "fake://$org/$repo"

    fun addRepo(name: String, description: String? = null, private: Boolean = false, visibleTo: Set<String>? = null): Repo =
        synchronized(this) { Repo(name, description, private, visibleTo).also { repos[name] = it } }

    fun addIssue(repo: String, title: String, body: String, labels: List<String> = emptyList(), author: String = "someone"): GhIssue =
        synchronized(this) {
            val r = repos.getValue(repo)
            val now = Instant.now().toString()
            val issue = GhIssue(
                number = (r.issues.maxOfOrNull { it.number } ?: 0) + 1, title = title, state = "open",
                labels = labels.map { GhLabel(it, if (it == "bug") "d73a4a" else "a2eeef") }, user = GhLogin(author),
                createdAt = now, updatedAt = now, htmlUrl = "https://github.com/$org/$repo/issues/${(r.issues.size + 1)}", body = body,
            )
            r.issues += issue
            issue
        }

    override fun client(token: String): GitHubClient {
        if (!token.startsWith("fake-") || token.length < 6) throw GitHubException(401, "Bad credentials")
        val rest = token.removePrefix("fake-")
        val scopes = if ('~' in rest) parseOAuthScopes(rest.substringAfter('~').replace('+', ',')) ?: emptyList() else listOf("read:org", "repo")
        return Client(rest.substringBefore('~'), scopes)
    }

    companion object {
        fun seeded(org: String): FakeGitHub = FakeGitHub(org).apply {
            addRepo("demo-app", "A small demo web app used to try Agern without GitHub", private = false)
            addRepo("private-lab", "Only visible to alice", private = true, visibleTo = setOf("alice"))
            addIssue("demo-app", "Add a dark mode toggle", "Users want a dark mode. Add a toggle in the header that remembers the choice.", listOf("enhancement"))
            addIssue("demo-app", "Crash when the list is empty", "Opening the list view with no items throws a TypeError.", listOf("bug"))
            addIssue("demo-app", "Update dependencies", "Bump all dependencies to their latest minor versions.")
            addIssue("private-lab", "Experiment with caching", "Try an in-memory cache for the slow endpoint.")
        }
    }

    private inner class Client(val login: String, val scopes: List<String>) : GitHubClient {
        private fun canSeePrivate(r: Repo) = !r.private || "repo" in scopes

        private fun repo(org: String, name: String): Repo {
            if (org != this@FakeGitHub.org) throw GitHubException(404, "Not Found")
            val r = repos[name] ?: throw GitHubException(404, "Not Found")
            if (r.visibleTo != null && login !in r.visibleTo) throw GitHubException(404, "Not Found")
            if (!canSeePrivate(r)) throw GitHubException(404, "Not Found")
            return r
        }

        private fun ghRepo(r: Repo) = GhRepo(
            name = r.name, fullName = "$org/${r.name}", description = r.description, private = r.private,
            defaultBranch = "main", language = "Kotlin", openIssuesCount = r.issues.count { it.state == "open" },
            pushedAt = Instant.now().toString(), htmlUrl = "https://github.com/$org/${r.name}", cloneUrl = cloneUrl(org, r.name),
        )

        override fun currentUser() = GhUser(login, login.replaceFirstChar { it.uppercase() }, scopes)

        override fun listOrgRepos(org: String): List<GhRepo> = synchronized(this@FakeGitHub) {
            if (org != this@FakeGitHub.org) throw GitHubException(404, "Not Found")
            repos.values.filter { (it.visibleTo == null || login in it.visibleTo) && canSeePrivate(it) }.map(::ghRepo)
        }

        override fun getRepo(org: String, repo: String) = synchronized(this@FakeGitHub) {
            runCatching { ghRepo(repo(org, repo)) }.getOrNull()
        }

        override fun listIssues(org: String, repo: String, state: String) = synchronized(this@FakeGitHub) {
            repo(org, repo).issues.filter { state == "all" || it.state == state }.sortedByDescending { it.number }
        }

        override fun getIssue(org: String, repo: String, number: Int) = synchronized(this@FakeGitHub) {
            repo(org, repo).issues.firstOrNull { it.number == number }
        }

        override fun listComments(org: String, repo: String, number: Int) = synchronized(this@FakeGitHub) {
            repo(org, repo).comments[number]?.toList() ?: emptyList()
        }

        override fun createIssue(org: String, repo: String, title: String, body: String, labels: List<String>): GhIssue {
            repo(org, repo)
            actions += "create-issue $repo $title"
            return addIssue(repo, title, body, labels, login)
        }

        override fun comment(org: String, repo: String, number: Int, body: String) = synchronized(this@FakeGitHub) {
            val r = repo(org, repo)
            r.comments.getOrPut(number) { mutableListOf() } += GhComment(GhLogin(login), body, Instant.now().toString())
            r.issues.replaceAll { if (it.number == number) it.copy(comments = it.comments + 1) else it }
            actions += "comment $repo#$number ${body.lineSequence().first()}"
            Unit
        }

        override fun addLabels(org: String, repo: String, number: Int, labels: List<String>) = synchronized(this@FakeGitHub) {
            val r = repo(org, repo)
            r.issues.replaceAll { i -> if (i.number == number) i.copy(labels = (i.labels + labels.map { GhLabel(it) }).distinctBy { it.name }) else i }
            actions += "label $repo#$number ${labels.joinToString(",")}"
            Unit
        }

        override fun removeLabel(org: String, repo: String, number: Int, label: String) = synchronized(this@FakeGitHub) {
            val r = repo(org, repo)
            r.issues.replaceAll { i -> if (i.number == number) i.copy(labels = i.labels.filter { it.name != label }) else i }
            actions += "unlabel $repo#$number $label"
            Unit
        }

        override fun closeIssue(org: String, repo: String, number: Int) = synchronized(this@FakeGitHub) {
            repo(org, repo).issues.replaceAll { if (it.number == number) it.copy(state = "closed") else it }
            actions += "close $repo#$number"
            Unit
        }

        override fun findOpenPull(org: String, repo: String, branch: String) = synchronized(this@FakeGitHub) {
            pulls["$repo:$branch"]?.firstOrNull { it.state == "open" }
        }

        override fun createPull(org: String, repo: String, head: String, base: String, title: String, body: String): GhPull =
            synchronized(this@FakeGitHub) {
                repo(org, repo)
                val n = 1000 + pulls.values.sumOf { it.size } + 1
                val pr = GhPull(n, "https://github.com/$org/$repo/pull/$n")
                pulls.getOrPut("$repo:$head") { mutableListOf() } += pr
                actions += "pr $repo $head -> $base #$n"
                pr
            }

        override fun squashMerge(org: String, repo: String, number: Int, title: String) = synchronized(this@FakeGitHub) {
            repo(org, repo)
            if (failMerge) throw GitHubException(405, "Pull Request is not mergeable")
            pulls.values.forEach { l -> l.replaceAll { if (it.number == number) it.copy(state = "merged") else it } }
            actions += "merge $repo #$number"
            Unit
        }
    }
}
