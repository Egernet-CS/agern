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
                    val files: MutableMap<String, String> = mutableMapOf(),
                    /** Binary files on the default branch, by path (only what tests put there); they take precedence. */
                    val byteFiles: MutableMap<String, ByteArray> = mutableMapOf(),
                    /** Labels created through [Client.createLabel], by lower-case name (labels on issues exist too). */
                    val labels: MutableMap<String, GhLabel> = mutableMapOf())

    val repos = mutableMapOf<String, Repo>()
    val actions = CopyOnWriteArrayList<String>()
    val pulls = mutableMapOf<String, MutableList<GhPull>>()
    /** Lets tests make merges fail. */
    @Volatile var failMerge = false
    /** While set, squash merges fail with this HTTP status (e.g. 502). */
    @Volatile var mergeFailure: Int? = null
    /** With [mergeFailure]: the merge happens before the error, as when GitHub merged but the answer was lost. */
    @Volatile var mergeFailureAfterMerging = false
    /** Lets tests make `getRepo` fail with this HTTP status (e.g. 503 for a transient GitHub error). */
    @Volatile var getRepoFailure: Int? = null
    /** Lets tests make branch creation fail as for a token without push access (403). */
    @Volatile var failCreateBranch = false
    /** Lets tests make branch creation fail with this HTTP status (e.g. 503 for a transient GitHub error). */
    @Volatile var createBranchFailure: Int? = null
    /**
     * Tests of transient failures (docs/ARCHITECTURE.md §6 "Transient failures"): while set, every [GitHubClient] call
     * throws a `java.net.ConnectException` without a message, as `java.net.http.HttpClient` does without a network.
     */
    @Volatile var unreachable = false
    /** While set, every [GitHubClient] call throws a [GitHubException] with this status (e.g. 502). */
    @Volatile var serverError: Int? = null

    /** Throws for [unreachable] and [serverError]; called first by every client call. */
    private fun outage() {
        if (unreachable) throw java.net.ConnectException()
        serverError?.let { throw GitHubException(it, "GitHub request failed: $it") }
    }
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
    /** Per repository: the next this many merges (squash or not) fail with 405, as when the base moved under a pull request. */
    val failNextMerges = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private fun failingMerge(repo: String): Boolean {
        val n = failNextMerges[repo] ?: 0
        if (n <= 0) return false
        failNextMerges[repo] = n - 1
        return true
    }

    /** Lets tests make label creation fail with this HTTP status (e.g. 403 for a token without push access). */
    @Volatile var createLabelFailure: Int? = null

    /**
     * Development: creating labels in a repository fails with 403 while `<filesDir>/<repo>/.agern-fake/labels-readonly`
     * exists (an end-to-end test switches it on and off).
     */
    private fun labelsReadOnly(repo: String) = dirFile(repo, ".agern-fake/labels-readonly") != null

    /** Clone URLs by repository instead of `fake://…` (a test with real remotes: local bare repositories). */
    val cloneUrls = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Gitlinks (submodule pointers) per `"<repo>:<branch>"`, by path: what [Client.gitlinkAt] answers. A branch starts
     * with the gitlinks of the commit it is created at; moving a branch to a gitlink commit ([Client.createGitlinksCommit])
     * gives it that commit's gitlinks; a merged pull request sets every gitlink of its head that differs on its base.
     */
    val gitlinks = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, String>>()
    /** Gitlink commits by SHA: (parent, gitlinks set); and where each branch made by a test or Agern points, by `"<repo>:<branch>"`. */
    private val gitlinkCommits = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Map<String, String>>>()
    private val branchHeads = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** The commit message of each gitlink commit, by SHA. */
    val gitlinkMessages = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Per repository: the next this many updates of a branch without force are refused (422), as when the branch moved. */
    val refuseNextUpdates = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** Branches (`"<repo>:<branch>"`) whose deletion fails (403). */
    val failDeleteBranch: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** The gitlinks of commit [sha] of [repo]: a gitlink commit's are its parent's plus its own; `sha-<branch>` has the branch's. */
    private fun linksOf(repo: String, sha: String): Map<String, String> {
        gitlinkCommits[sha]?.let { (parent, links) -> return linksOf(repo, parent) + links }
        if (!sha.startsWith("sha-")) return emptyMap()
        return gitlinks["$repo:${sha.removePrefix("sha-")}"]?.toMap().orEmpty()
    }

    /** Points `<repo>:<branch>` at [sha] and gives it that commit's gitlinks. */
    private fun moveBranch(repo: String, branch: String, sha: String) {
        branchHeads["$repo:$branch"] = sha
        val links = linksOf(repo, sha)
        if (links.isEmpty()) gitlinks.remove("$repo:$branch") else gitlinks["$repo:$branch"] = java.util.concurrent.ConcurrentHashMap(links)
    }
    /** Commits ([Client.commitExists]) that do not exist, as `"<repo>:<sha>"`; every other commit does. */
    val missingCommits: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

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
     * Checks set with [setStatus] or [setCheckRun] take precedence. `"conflict": true` makes pull requests with that head
     * conflict, exactly like an entry of [conflicting], while the file says so.
     */
    @Volatile var checksDir: java.nio.file.Path? = null

    @kotlinx.serialization.Serializable
    private data class ChecksFile(
        val statuses: Map<String, String> = emptyMap(), val checkRuns: List<GhCheckRun> = emptyList(), val conflict: Boolean = false,
    )

    /** Whether pull requests from [head] of [repo] conflict: an entry of [conflicting], or the checks file says so. */
    private fun conflicts(repo: String, head: String): Boolean = "$repo:$head" in conflicting || checksFile(repo, head)?.conflict == true

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
        gitlinks["${m.repo}:${m.head}"]?.let { head ->
            val base = gitlinks.getOrPut("${m.repo}:${m.base}") { java.util.concurrent.ConcurrentHashMap() }
            for ((path, sha) in head) if (base[path] != sha) base[path] = sha
        }
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
    override fun cloneUrl(org: String, repo: String) = cloneUrls[repo] ?: "fake://$org/$repo"

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
    } ?: dirFile(repo, path)?.let { java.nio.file.Files.readString(it) }

    /** The bytes at [path] on [repo]'s default branch: [setFileBytes], then [setFile] as UTF-8, then [filesDir]. */
    fun fileBytesOf(repo: String, path: String): ByteArray? = synchronized(this) {
        repos[repo]?.let { it.byteFiles[path] ?: it.files[path]?.toByteArray(Charsets.UTF_8) }
    } ?: dirFile(repo, path)?.let { java.nio.file.Files.readAllBytes(it) }

    /** A regular file under [filesDir] for [repo] and [path]; null for paths that could leave the repository's directory. */
    private fun dirFile(repo: String, path: String): java.nio.file.Path? = filesDir?.let { dir ->
        if (path.split('/').any { it == ".." || it.isEmpty() } || repo.contains('/') || repo.startsWith(".")) return@let null
        dir.resolve(repo).resolve(path).takeIf { java.nio.file.Files.isRegularFile(it) }
    }

    /** Puts (or with null removes) a binary file on [repo]'s default branch. */
    fun setFileBytes(repo: String, path: String, bytes: ByteArray?) = synchronized(this) {
        val r = repos.getValue(repo)
        if (bytes == null) r.byteFiles.remove(path) else r.byteFiles[path] = bytes
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
            seedWorkspace()
            seedSubmoduleWorkspace()
        }

        /** The `.gitmodules` of the seeded `demo-shop` (docs/ARCHITECTURE.md §5 "Workspace projects"). */
        fun demoShopGitmodules(org: String) = """
            |[submodule "api"]
            |	path = services/api
            |	url = git@github.com:$org/demo-shop-api.git
            |[submodule "web"]
            |	path = web
            |	url = ../demo-shop-web.git
            |	branch = main
            |[submodule "cli"]
            |	path = tools/cli
            |	url = ../demo-ws-tools.git
            |[submodule "vendor/icons"]
            |	path = vendor/icons
            |	url = https://github.com/another-org/icons.git
            |""".trimMargin()

        /** The workspace definition of the seeded `demo-workspace` (docs/ARCHITECTURE.md §3). */
        val DEMO_WORKSPACE_FILE = """
            |# The demo shop and its tools, checked out side by side (docs/ARCHITECTURE.md §5 "Workspace projects").
            |members:
            |  - directory: app
            |    repository: demo-ws-app
            |    branch: dev
            |    label: app
            |  - directory: tools/cli
            |    repository: demo-ws-tools
            |  - directory: lab
            |    repository: demo-ws-lab
            |    label: lab
            |""".trimMargin()
    }

    /**
     * A workspace project: `demo-workspace` with `.agern/workspace.yml` and three members, `demo-ws-app` (base branch
     * `dev`), `demo-ws-tools` (default branch) and `demo-ws-lab` (only alice can read it), and issues with one, no and
     * several member labels.
     */
    fun seedWorkspace() {
        addRepo("demo-workspace", "A workspace: the demo shop app and its tools, side by side")
        addRepo("demo-ws-app", "The shop app of demo-workspace (base branch dev)")
        addRepo("demo-ws-tools", "Command-line tools of demo-workspace")
        addRepo("demo-ws-lab", "Experiments of demo-workspace; only alice can read it", private = true, visibleTo = setOf("alice"))
        synchronized(this) { branches.getOrPut("demo-ws-app") { mutableSetOf() } += "dev" }
        setFile("demo-workspace", ".agern/workspace.yml", DEMO_WORKSPACE_FILE)
        addIssue("demo-workspace", "Show prices with currency", "Prices in the shop app lack the currency sign.", listOf("enhancement", "app"))
        addIssue("demo-workspace", "Add a --dry-run flag to the CLI", "The import command should be able to run without writing.", listOf("demo-ws-tools"))
        addIssue("demo-workspace", "Document the release process", "Nobody knows how a release is made.")
        addIssue("demo-workspace", "Share the price formatter", "The app and the CLI format prices differently.", listOf("app", "demo-ws-tools"))
        addIssue("demo-workspace", "Try a faster search index", "An experiment for the lab.", listOf("lab"))
    }

    /**
     * A workspace built from git submodules: `demo-shop` has no `.agern/workspace.yml`, only a `.gitmodules` with the
     * members `services/api` (`demo-shop-api`, an SSH URL), `web` (`demo-shop-web`, a relative URL) and `tools/cli`
     * (`demo-ws-tools`, whose label no issue carries yet, so it is missing until Agern creates it) and a submodule
     * of another organization (context only), issues labelled with a member's repository name and one without a
     * member label (a run of `demo-shop` itself).
     */
    fun seedSubmoduleWorkspace() {
        addRepo("demo-shop", "A workspace built from git submodules: the shop's API and web app")
        addRepo("demo-shop-api", "The shop's API, a submodule of demo-shop")
        addRepo("demo-shop-web", "The shop's web app, a submodule of demo-shop")
        setFile("demo-shop", ".gitmodules", demoShopGitmodules(org))
        gitlinks["demo-shop:main"] = java.util.concurrent.ConcurrentHashMap(
            mapOf(
                "services/api" to "0".repeat(39) + "1", "web" to "0".repeat(39) + "2", "tools/cli" to "0".repeat(39) + "4",
                "vendor/icons" to "0".repeat(39) + "3",
            ),
        )
        addIssue("demo-shop", "Add a health endpoint", "The load balancer needs GET /health.", listOf("enhancement", "demo-shop-api"))
        addIssue("demo-shop", "Show the cart total in the header", "Users cannot see what the cart costs.", listOf("demo-shop-web"))
        addIssue("demo-shop", "Update the architecture notes", "docs/architecture.md still describes the old checkout.")
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

        override fun currentUser(): GhUser {
            outage()
            return GhUser(login, login.replaceFirstChar { it.uppercase() }, scopes)
        }

        override fun listOrgRepos(org: String): List<GhRepo> = synchronized(this@FakeGitHub) {
            outage()
            if (org != this@FakeGitHub.org) throw GitHubException(404, "Not Found")
            repos.values.filter { (it.visibleTo == null || login in it.visibleTo) && canSeePrivate(it) }.map(::ghRepo)
        }

        override fun getRepo(org: String, repo: String) = synchronized(this@FakeGitHub) {
            outage()
            getRepoFailure?.let { throw GitHubException(it, "GitHub getRepo failed: $it") }
            runCatching { ghRepo(repo(org, repo)) }.getOrNull()
        }

        override fun listIssues(org: String, repo: String, state: String) = synchronized(this@FakeGitHub) {
            outage()
            repo(org, repo).issues.filter { state == "all" || it.state == state }.sortedByDescending { it.number }
        }

        override fun getIssue(org: String, repo: String, number: Int) = synchronized(this@FakeGitHub) {
            outage()
            repo(org, repo).issues.firstOrNull { it.number == number }
        }

        override fun listComments(org: String, repo: String, number: Int) = synchronized(this@FakeGitHub) {
            outage()
            repo(org, repo).comments[number]?.toList() ?: emptyList()
        }

        override fun createIssue(org: String, repo: String, title: String, body: String, labels: List<String>): GhIssue {
            outage()
            repo(org, repo)
            actions += "create-issue $repo $title"
            return addIssue(repo, title, body, labels, login)
        }

        override fun comment(org: String, repo: String, number: Int, body: String) = synchronized(this@FakeGitHub) {
            outage()
            val r = repo(org, repo)
            r.comments.getOrPut(number) { mutableListOf() } += GhComment(GhLogin(login), body, Instant.now().toString())
            r.issues.replaceAll { if (it.number == number) it.copy(comments = it.comments + 1) else it }
            actions += "comment $repo#$number ${body.lineSequence().first()}"
            Unit
        }

        override fun addLabels(org: String, repo: String, number: Int, labels: List<String>) = synchronized(this@FakeGitHub) {
            outage()
            val r = repo(org, repo)
            r.issues.replaceAll { i -> if (i.number == number) i.copy(labels = (i.labels + labels.map { GhLabel(it) }).distinctBy { it.name }) else i }
            actions += "label $repo#$number ${labels.joinToString(",")}"
            Unit
        }

        override fun removeLabel(org: String, repo: String, number: Int, label: String) = synchronized(this@FakeGitHub) {
            outage()
            val r = repo(org, repo)
            r.issues.replaceAll { i -> if (i.number == number) i.copy(labels = i.labels.filter { it.name != label }) else i }
            actions += "unlabel $repo#$number $label"
            Unit
        }

        override fun closeIssue(org: String, repo: String, number: Int) = synchronized(this@FakeGitHub) {
            outage()
            repo(org, repo).issues.replaceAll { if (it.number == number) it.copy(state = "closed") else it }
            actions += "close $repo#$number"
            Unit
        }

        override fun findOpenPull(org: String, repo: String, branch: String) = synchronized(this@FakeGitHub) {
            outage()
            pulls["$repo:$branch"]?.firstOrNull { it.state == "open" }
        }

        override fun createPull(org: String, repo: String, head: String, base: String, title: String, body: String): GhPull =
            synchronized(this@FakeGitHub) {
                outage()
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
            outage()
            synchronized(this@FakeGitHub) { repo(org, repo) }
            return fileOf(repo, path)
        }

        override fun getFileBytes(org: String, repo: String, path: String, ref: String, maxBytes: Long): ByteArray? {
            outage()
            synchronized(this@FakeGitHub) { repo(org, repo) }
            return fileBytesOf(repo, path)?.let { if (it.size > maxBytes) it.copyOf(maxBytes.toInt()) else it }
        }

        override fun squashMerge(org: String, repo: String, number: Int, title: String) = synchronized(this@FakeGitHub) {
            outage()
            repo(org, repo)
            if (failMerge) throw GitHubException(405, "Pull Request is not mergeable")
            if (failingMerge(repo)) throw GitHubException(405, "Base branch was modified. Review and try the merge again.")
            mergeFailure?.let { if (!mergeFailureAfterMerging) throw GitHubException(it, "GitHub merge failed: $it") }
            val m = pullMeta[number]
            if (m != null) applyMerge(m, number, "squash")
            else pulls.values.forEach { l -> l.replaceAll { if (it.number == number) it.copy(state = "merged") else it } }
            actions += "merge $repo #$number"
            mergeFailure?.let { throw GitHubException(it, "GitHub merge failed: $it") }
            Unit
        }

        override fun branchSha(org: String, repo: String, branch: String): String? = synchronized(this@FakeGitHub) {
            outage()
            repo(org, repo)
            if (branch == "main" || branch in branches[repo].orEmpty()) {
                branchHeads["$repo:$branch"]?.takeIf { gitlinkCommits.containsKey(it) } ?: "sha-$branch"
            } else null
        }

        override fun createBranch(org: String, repo: String, branch: String, sha: String) = synchronized(this@FakeGitHub) {
            outage()
            repo(org, repo)
            if (failCreateBranch) throw GitHubException(403, "Resource not accessible by personal access token")
            createBranchFailure?.let { throw GitHubException(it, "GitHub create branch failed: $it") }
            if (branch == "main" || branch in branches[repo].orEmpty()) throw GitHubException(422, "Reference already exists")
            branches.getOrPut(repo) { mutableSetOf() } += branch
            // The new branch has the commits of the branch whose tip it starts at (`sha-<branch>`).
            val from = sha.removePrefix("sha-").takeIf { sha.startsWith("sha-") && (it == "main" || it in branches[repo].orEmpty()) } ?: "main"
            commits["$repo:$branch"] = commitList(repo, from).toMutableList()
            moveBranch(repo, branch, sha)
            actions += "branch $repo $branch"
            Unit
        }

        override fun getPull(org: String, repo: String, number: Int): GhPullInfo? = synchronized(this@FakeGitHub) {
            outage()
            repo(org, repo)
            pullReads.incrementAndGet()
            if (getPullFailures > 0) {
                getPullFailures--
                throw GitHubException(502, "GitHub GET pull request failed: 502 Bad Gateway")
            }
            val m = pullMeta[number]?.takeIf { it.repo == repo } ?: return@synchronized null
            val computing = mergeableComputing > 0
            if (computing) mergeableComputing--
            val clean = !conflicts(repo, m.head)
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
            outage()
            repo(org, repo)
            val m = pullMeta[number]?.takeIf { it.repo == repo } ?: throw GitHubException(404, "Not Found")
            m.title = title
            pullBodies[number] = body
            actions += "update-pr $repo #$number"
            Unit
        }

        override fun mergePull(org: String, repo: String, number: Int, method: String, commitTitle: String?, expectedHeadSha: String?): String? =
            synchronized(this@FakeGitHub) {
                outage()
                repo(org, repo)
                require(method in NON_SQUASH_MERGE_METHODS) { "Unsupported merge method $method" }
                val m = pullMeta[number]?.takeIf { it.repo == repo } ?: throw GitHubException(404, "Not Found")
                if (method == "rebase" && allowRebaseMerge == false) throw GitHubException(405, "Rebase merges are not allowed on this repository.")
                if (method == "merge" && allowMergeCommit == false) throw GitHubException(405, "Merge commits are not allowed on this repository.")
                if (m.state != "open") throw GitHubException(405, "Pull Request is not mergeable")
                if (failMerge || conflicts(repo, m.head)) throw GitHubException(405, "Pull Request is not mergeable")
                if (failingMerge(repo)) throw GitHubException(405, "Base branch was modified. Review and try the merge again.")
                if (method == "rebase" && "$repo:${m.head}" in notRebaseable) throw GitHubException(405, "This branch can't be rebased")
                if (commitTitle != null) m.title = commitTitle
                val sha = applyMerge(m, number, method)
                actions += "merge-$method $repo #$number"
                sha
            }

        override fun deleteBranch(org: String, repo: String, branch: String) = synchronized(this@FakeGitHub) {
            outage()
            repo(org, repo)
            if (branch == "main") throw GitHubException(422, "Cannot delete the default branch")
            if ("$repo:$branch" in failDeleteBranch) throw GitHubException(403, "Resource not accessible by personal access token")
            branches[repo]?.remove(branch)
            branchHeads.remove("$repo:$branch")
            gitlinks.remove("$repo:$branch")
            commits.remove("$repo:$branch")
            // As on GitHub: open pull requests from a deleted branch are closed.
            pullMeta.forEach { (n, m) -> if (m.repo == repo && m.head == branch && m.state == "open") { m.state = "closed"; closePullList(n) } }
            actions += "delete-branch $repo $branch"
            Unit
        }

        override fun combinedStatus(org: String, repo: String, sha: String): GhCombinedStatus = synchronized(this@FakeGitHub) {
            outage()
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
            outage()
            repo(org, repo)
            checksFailure?.let { throw GitHubException(it, "GitHub GET check runs failed: $it Resource not accessible by personal access token") }
            val branch = branchOfSha(sha)
            (checkRunsByBranch["$repo:$branch"]?.values?.takeIf { it.isNotEmpty() } ?: checksFile(repo, branch)?.checkRuns.orEmpty())
                .sortedBy { it.name }
        }

        override fun commitExists(org: String, repo: String, sha: String): Boolean = synchronized(this@FakeGitHub) {
            outage()
            repo(org, repo)
            "$repo:$sha" !in missingCommits
        }

        override fun gitlinkAt(org: String, repo: String, path: String, ref: String): String? = synchronized(this@FakeGitHub) {
            outage()
            repo(org, repo)
            gitlinks["$repo:$ref"]?.get(path)
        }

        override fun createGitlinksCommit(org: String, repo: String, parentSha: String, links: Map<String, String>, message: String): String =
            synchronized(this@FakeGitHub) {
                outage()
                repo(org, repo)
                require(links.isNotEmpty()) { "No gitlink to set" }
                val commit = "sha-gitlink-${gitlinkCommits.size + 1}"
                gitlinkCommits[commit] = parentSha to links.toMap()
                for ((path, sha) in links) actions += "gitlink-commit $repo $path $sha"
                gitlinkMessages[commit] = message
                commit
            }

        override fun forceUpdateBranch(org: String, repo: String, branch: String, sha: String, force: Boolean) = synchronized(this@FakeGitHub) {
            outage()
            repo(org, repo)
            if (branch != "main" && branch !in branches[repo].orEmpty()) throw GitHubException(422, "Reference does not exist")
            if (!force) {
                val n = refuseNextUpdates[repo] ?: 0
                val current = branchHeads["$repo:$branch"]?.takeIf { gitlinkCommits.containsKey(it) } ?: "sha-$branch"
                if (n > 0) refuseNextUpdates[repo] = n - 1
                if (n > 0 || gitlinkCommits[sha]?.first != current) throw GitHubException(422, "Update is not a fast forward")
            }
            moveBranch(repo, branch, sha)
            actions += "update-branch $repo $branch"
            Unit
        }

        override fun listLabels(org: String, repo: String): List<GhLabel> = synchronized(this@FakeGitHub) {
            outage()
            val r = repo(org, repo)
            // As on GitHub, every label an issue carries exists in the repository.
            (r.labels.values + r.issues.flatMap { it.labels }).distinctBy { it.name.lowercase() }
        }

        override fun createLabel(org: String, repo: String, name: String, color: String, description: String): Boolean =
            synchronized(this@FakeGitHub) {
                outage()
                val r = repo(org, repo)
                createLabelFailure?.let { throw GitHubException(it, "Resource not accessible by personal access token") }
                if (labelsReadOnly(repo)) throw GitHubException(403, "Resource not accessible by personal access token")
                if (listLabels(org, repo).any { it.name.equals(name, ignoreCase = true) }) return@synchronized false
                r.labels[name.lowercase()] = GhLabel(name, color)
                actions += "create-label $repo $name"
                true
            }

        private fun closePullList(number: Int) =
            pulls.values.forEach { l -> l.replaceAll { if (it.number == number) it.copy(state = "closed") else it } }
    }
}
