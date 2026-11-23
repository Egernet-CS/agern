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
                    val issues: MutableList<GhIssue> = mutableListOf(), val comments: MutableMap<Int, MutableList<GhComment>> = mutableMapOf(),
                    /** Files on the default branch, by path (only what tests put there). */
                    val files: MutableMap<String, String> = mutableMapOf())

    val repos = mutableMapOf<String, Repo>()
    val actions = CopyOnWriteArrayList<String>()
    val pulls = mutableMapOf<String, MutableList<GhPull>>()
    /** Lets tests make merges fail. */
    @Volatile var failMerge = false
    /** Lets tests make `getRepo` fail with this HTTP status (e.g. 503 for a transient GitHub error). */
    @Volatile var getRepoFailure: Int? = null
    /** Lets tests make branch creation fail as for a token without push access (403). */
    @Volatile var failCreateBranch = false
    /** Pull request bodies by number. */
    val pullBodies = java.util.concurrent.ConcurrentHashMap<Int, String>()
    /** Branches per repo besides `main`, which always exists. */
    val branches = mutableMapOf<String, MutableSet<String>>()

    /** What the fake knows about each pull request, by number. */
    data class PullMeta(
        val repo: String, val head: String, val base: String, var title: String,
        var state: String = "open", var merged: Boolean = false, var mergeCommit: String? = null,
    )
    val pullMeta = java.util.concurrent.ConcurrentHashMap<Int, PullMeta>()
    /**
     * Commit titles per `"<repo>:<branch>"`, oldest first (`main` starts with "Initial commit"): a branch is created
     * with its source's commits, a squash merge adds one commit to the base, a rebase merge replays the head's own
     * commits onto the base and a merge commit adds them plus the merge commit. Lets tests count commits per issue.
     */
    val commits = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()
    fun commitsOf(repo: String, branch: String): List<String> = synchronized(this) {
        commits.getOrPut("$repo:$branch") { if (branch == "main") mutableListOf("Initial commit") else mutableListOf() }.toList()
    }
    private fun commitList(repo: String, branch: String) =
        commits.getOrPut("$repo:$branch") { if (branch == "main") mutableListOf("Initial commit") else mutableListOf() }

    /** Head branches (`"<repo>:<branch>"`) whose pull requests do not merge cleanly (mergeable false; merging fails). */
    val conflicting: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /**
     * Head branches (`"<repo>:<branch>"`) GitHub cannot rebase onto their base (e.g. the base was merged into them with
     * a merge commit): `getPull` reports `rebaseable` false and a rebase merge is refused with 405 "can't be rebased".
     */
    val notRebaseable: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** `getPull` reports `rebaseable` as unknown (null), as GitHub does until it has computed it. */
    @Volatile var hideRebaseable = false
    /** The next this many `getPull` reads fail (502), as a transient GitHub or network error does. */
    @Volatile var getPullFailures = 0
    /** How often a pull request was read (`getPull`). */
    val pullReads = java.util.concurrent.atomic.AtomicInteger()
    /** The next this many `getPull` reads report mergeability as not computed yet (null). */
    @Volatile var mergeableComputing = 0
    /** The repository's merge settings, as `getRepo` reports them (null: GitHub leaves the field out). */
    @Volatile var allowRebaseMerge: Boolean? = true
    @Volatile var allowMergeCommit: Boolean? = true
    /** `getRepo` leaves the merge settings out (as GitHub does for some tokens); merging still follows them. */
    @Volatile var hideMergeSettings = false
    /** Lets tests make opening pull requests fail. */
    @Volatile var failCreatePull = false

    /**
     * Checks per head branch (`"<repo>:<branch>"`), as GitHub reports them for the branch's current head commit:
     * commit statuses by context (`success`, `pending`, `failure`, `error`) and check runs by name (status, conclusion).
     * A branch with neither has no checks at all.
     */
    val statuses = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, String>>()
    val checkRunsByBranch = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, GhCheckRun>>()
    /** Lets tests make reading checks fail with this HTTP status (e.g. 403 for a token without the permission). */
    @Volatile var checksFailure: Int? = null
    /** How often the checks were read (combined status reads). */
    val checkReads = java.util.concurrent.atomic.AtomicInteger()

    /** Sets (or with null removes) the commit status [context] on [branch]'s head. */
    fun setStatus(repo: String, branch: String, context: String, state: String?) {
        val m = statuses.getOrPut("$repo:$branch") { java.util.concurrent.ConcurrentHashMap() }
        if (state == null) m.remove(context) else m[context] = state
    }

    /** Sets (or with null [status] removes) the check run [name] on [branch]'s head. */
    fun setCheckRun(repo: String, branch: String, name: String, status: String?, conclusion: String? = null) {
        val m = checkRunsByBranch.getOrPut("$repo:$branch") { java.util.concurrent.ConcurrentHashMap() }
        if (status == null) m.remove(name) else m[name] = GhCheckRun(name, status, conclusion)
    }

    /** The head branch of a fake head commit `sha-<branch>-<n>` (as [Client.getPull] reports it). */
    private fun branchOfSha(sha: String): String = sha.removePrefix("sha-").substringBeforeLast('-')

    /**
     * Development (FACTORY_DEV_FAKE_GITHUB): a directory where `<repo>/<branch>.json` sets the checks of that branch's
     * head while the server runs (e.g. `demo-app/release/1.0.json`), for an end-to-end test or a developer:
     * `{"statuses": {"ci/lint": "success"}, "checkRuns": [{"name": "build", "status": "completed", "conclusion": "failure"}]}`.
     * Checks set with [setStatus] or [setCheckRun] take precedence.
     */
    @Volatile var checksDir: java.nio.file.Path? = null

    @kotlinx.serialization.Serializable
    private data class ChecksFile(val statuses: Map<String, String> = emptyMap(), val checkRuns: List<GhCheckRun> = emptyList())

    private fun checksFile(repo: String, branch: String): ChecksFile? {
        val dir = checksDir ?: return null
        if (branch.split('/').any { it == ".." || it.isEmpty() || it.startsWith(".") } || repo.contains('/') || repo.startsWith(".")) return null
        val f = dir.resolve(repo).resolve("$branch.json")
        if (!java.nio.file.Files.isRegularFile(f)) return null
        return runCatching { dk.egernet.factory.AppJson.decodeFromString(ChecksFile.serializer(), java.nio.file.Files.readString(f)) }.getOrNull()
    }

    /** As if a person merged pull request [number] on GitHub (rebase), outside Agern. */
    fun mergeOnGitHub(number: Int) = synchronized(this) { applyMerge(pullMeta.getValue(number), number, "rebase") }

    /** As if a person closed pull request [number] on GitHub without merging it. */
    fun closePull(number: Int) = synchronized(this) {
        pullMeta.getValue(number).state = "closed"
        pulls.values.forEach { l -> l.replaceAll { if (it.number == number) it.copy(state = "closed") else it } }
    }

    private fun applyMerge(m: PullMeta, number: Int, method: String): String {
        val base = commitList(m.repo, m.base)
        when (method) {
            "squash" -> base += m.title
            else -> {
                val own = commitList(m.repo, m.head).filter { it !in base }
                base += own
                if (method == "merge") base += m.title
            }
        }
        m.state = "closed"
        m.merged = true
        m.mergeCommit = "sha-${m.base}-${base.size}"
        pulls.values.forEach { l -> l.replaceAll { if (it.number == number) it.copy(state = "merged") else it } }
        return m.mergeCommit!!
    }

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

    /**
     * Development (FACTORY_DEV_FAKE_GITHUB): a directory whose `<repo>/<path>` files are on that repository's default
     * branch too, so a developer or an end-to-end test can add and change files (e.g. `.agern/sandbox.yml`) while the
     * server runs. Files set with [setFile] take precedence.
     */
    @Volatile var filesDir: java.nio.file.Path? = null

    /** The file at [path] on [repo]'s default branch, or null (no visibility check: server-side use only). */
    fun fileOf(repo: String, path: String): String? = synchronized(this) {
        repos[repo]?.files?.get(path)
    } ?: filesDir?.let { dir ->
        if (path.split('/').any { it == ".." || it.isEmpty() } || repo.contains('/') || repo.startsWith(".")) return@let null
        val f = dir.resolve(repo).resolve(path)
        if (java.nio.file.Files.isRegularFile(f)) java.nio.file.Files.readString(f) else null
    }

    /** Puts (or with null removes) a file on [repo]'s default branch. */
    fun setFile(repo: String, path: String, content: String?) = synchronized(this) {
        val r = repos.getValue(repo)
        if (content == null) r.files.remove(path) else r.files[path] = content
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
            allowRebaseMerge = if (hideMergeSettings) null else allowRebaseMerge,
            allowMergeCommit = if (hideMergeSettings) null else allowMergeCommit,
        )

        override fun currentUser() = GhUser(login, login.replaceFirstChar { it.uppercase() }, scopes)

        override fun listOrgRepos(org: String): List<GhRepo> = synchronized(this@FakeGitHub) {
            if (org != this@FakeGitHub.org) throw GitHubException(404, "Not Found")
            repos.values.filter { (it.visibleTo == null || login in it.visibleTo) && canSeePrivate(it) }.map(::ghRepo)
        }

        override fun getRepo(org: String, repo: String) = synchronized(this@FakeGitHub) {
            getRepoFailure?.let { throw GitHubException(it, "GitHub getRepo failed: $it") }
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
                if (failCreatePull) throw GitHubException(422, "Validation Failed")
                val n = 1000 + pulls.values.sumOf { it.size } + 1
                val pr = GhPull(n, "https://github.com/$org/$repo/pull/$n")
                pulls.getOrPut("$repo:$head") { mutableListOf() } += pr
                pullMeta[n] = PullMeta(repo, head, base, title)
                pullBodies[n] = body
                actions += "pr $repo $head -> $base #$n"
                pr
            }

        override fun getFile(org: String, repo: String, path: String, ref: String): String? {
            synchronized(this@FakeGitHub) { repo(org, repo) }
            return fileOf(repo, path)
        }

        override fun squashMerge(org: String, repo: String, number: Int, title: String) = synchronized(this@FakeGitHub) {
            repo(org, repo)
            if (failMerge) throw GitHubException(405, "Pull Request is not mergeable")
            val m = pullMeta[number]
            if (m != null) applyMerge(m, number, "squash")
            else pulls.values.forEach { l -> l.replaceAll { if (it.number == number) it.copy(state = "merged") else it } }
            actions += "merge $repo #$number"
            Unit
        }

        override fun branchSha(org: String, repo: String, branch: String): String? = synchronized(this@FakeGitHub) {
            repo(org, repo)
            if (branch == "main" || branch in branches[repo].orEmpty()) "sha-$branch" else null
        }

        override fun createBranch(org: String, repo: String, branch: String, sha: String) = synchronized(this@FakeGitHub) {
            repo(org, repo)
            if (failCreateBranch) throw GitHubException(403, "Resource not accessible by personal access token")
            if (branch == "main" || branch in branches[repo].orEmpty()) throw GitHubException(422, "Reference already exists")
            branches.getOrPut(repo) { mutableSetOf() } += branch
            // The new branch has the commits of the branch whose tip it starts at (`sha-<branch>`).
            val from = sha.removePrefix("sha-").takeIf { sha.startsWith("sha-") && (it == "main" || it in branches[repo].orEmpty()) } ?: "main"
            commits["$repo:$branch"] = commitList(repo, from).toMutableList()
            actions += "branch $repo $branch"
            Unit
        }

        override fun getPull(org: String, repo: String, number: Int): GhPullInfo? = synchronized(this@FakeGitHub) {
            repo(org, repo)
            pullReads.incrementAndGet()
            if (getPullFailures > 0) {
                getPullFailures--
                throw GitHubException(502, "GitHub GET pull request failed: 502 Bad Gateway")
            }
            val m = pullMeta[number]?.takeIf { it.repo == repo } ?: return@synchronized null
            val computing = mergeableComputing > 0
            if (computing) mergeableComputing--
            val clean = "$repo:${m.head}" !in conflicting
            GhPullInfo(
                number = number, htmlUrl = "https://github.com/$org/$repo/pull/$number", state = m.state, merged = m.merged,
                mergeable = if (m.state != "open" || computing) null else clean,
                rebaseable = if (m.state != "open" || computing || hideRebaseable) null else "$repo:${m.head}" !in notRebaseable,
                mergeableState = if (m.state != "open" || computing) "unknown" else if (clean) "clean" else "dirty",
                head = GhRef(m.head, "sha-${m.head}-${commitList(repo, m.head).size}"), base = GhRef(m.base, "sha-${m.base}"),
                mergeCommitSha = m.mergeCommit,
            )
        }

        override fun updatePull(org: String, repo: String, number: Int, title: String, body: String) = synchronized(this@FakeGitHub) {
            repo(org, repo)
            val m = pullMeta[number]?.takeIf { it.repo == repo } ?: throw GitHubException(404, "Not Found")
            m.title = title
            pullBodies[number] = body
            actions += "update-pr $repo #$number"
            Unit
        }

        override fun mergePull(org: String, repo: String, number: Int, method: String, commitTitle: String?, expectedHeadSha: String?): String? =
            synchronized(this@FakeGitHub) {
                repo(org, repo)
                require(method in NON_SQUASH_MERGE_METHODS) { "Unsupported merge method $method" }
                val m = pullMeta[number]?.takeIf { it.repo == repo } ?: throw GitHubException(404, "Not Found")
                if (method == "rebase" && allowRebaseMerge == false) throw GitHubException(405, "Rebase merges are not allowed on this repository.")
                if (method == "merge" && allowMergeCommit == false) throw GitHubException(405, "Merge commits are not allowed on this repository.")
                if (m.state != "open") throw GitHubException(405, "Pull Request is not mergeable")
                if (failMerge || "$repo:${m.head}" in conflicting) throw GitHubException(405, "Pull Request is not mergeable")
                if (method == "rebase" && "$repo:${m.head}" in notRebaseable) throw GitHubException(405, "This branch can't be rebased")
                if (commitTitle != null) m.title = commitTitle
                val sha = applyMerge(m, number, method)
                actions += "merge-$method $repo #$number"
                sha
            }

        override fun deleteBranch(org: String, repo: String, branch: String) = synchronized(this@FakeGitHub) {
            repo(org, repo)
            if (branch == "main") throw GitHubException(422, "Cannot delete the default branch")
            branches[repo]?.remove(branch)
            commits.remove("$repo:$branch")
            // As on GitHub: open pull requests from a deleted branch are closed.
            pullMeta.forEach { (n, m) -> if (m.repo == repo && m.head == branch && m.state == "open") { m.state = "closed"; closePullList(n) } }
            actions += "delete-branch $repo $branch"
            Unit
        }

        override fun combinedStatus(org: String, repo: String, sha: String): GhCombinedStatus = synchronized(this@FakeGitHub) {
            repo(org, repo)
            checksFailure?.let { throw GitHubException(it, "GitHub GET commit status failed: $it Resource not accessible by personal access token") }
            checkReads.incrementAndGet()
            val branch = branchOfSha(sha)
            val set = statuses["$repo:$branch"]?.takeIf { it.isNotEmpty() } ?: checksFile(repo, branch)?.statuses.orEmpty()
            val list = set.map { (c, st) -> GhCommitStatus(c, st) }.sortedBy { it.context }
            val state = when {
                list.isEmpty() -> "pending" // as GitHub: no statuses at all reads as pending with total_count 0
                list.any { it.state == "failure" || it.state == "error" } -> "failure"
                list.any { it.state == "pending" } -> "pending"
                else -> "success"
            }
            GhCombinedStatus(state, list.size, list)
        }

        override fun checkRuns(org: String, repo: String, sha: String): List<GhCheckRun> = synchronized(this@FakeGitHub) {
            repo(org, repo)
            checksFailure?.let { throw GitHubException(it, "GitHub GET check runs failed: $it Resource not accessible by personal access token") }
            val branch = branchOfSha(sha)
            (checkRunsByBranch["$repo:$branch"]?.values?.takeIf { it.isNotEmpty() } ?: checksFile(repo, branch)?.checkRuns.orEmpty())
                .sortedBy { it.name }
        }

        private fun closePullList(number: Int) =
            pulls.values.forEach { l -> l.replaceAll { if (it.number == number) it.copy(state = "closed") else it } }
    }
}
