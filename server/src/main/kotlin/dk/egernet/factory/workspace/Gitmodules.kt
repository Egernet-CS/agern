package dk.egernet.factory.workspace

import dk.egernet.factory.agent.REPO_NAME_RE

/** Where a repository declares its git submodules, on its default branch. */
const val GITMODULES_FILE = ".gitmodules"

/** One `[submodule "<name>"]` section of a `.gitmodules` file with its [path], [url] and optional [branch]. */
data class Submodule(val name: String, val path: String, val url: String, val branch: String?)

/**
 * A submodule with the repository its URL names: [repository] is the bare name of a repository of the configured
 * organization on the configured git host, or null when the submodule lives elsewhere (context only, never a target).
 */
data class ResolvedSubmodule(val submodule: Submodule, val repository: String?) {
    val path get() = submodule.path
}

/** What a `.gitmodules` file makes of a project (docs/ARCHITECTURE.md §5 "Workspace projects"). */
data class GitmodulesWorkspace(val members: List<WorkspaceMember>, val contextOnly: List<ResolvedSubmodule>)

/**
 * Parses `.gitmodules` (git-config syntax) and resolves submodule URLs to repositories of the configured organization.
 * Never throws: what cannot be read is left out.
 */
object Gitmodules {
    const val MAX_BYTES = 64 * 1024

    private val SECTION_RE = Regex("""^\[\s*([A-Za-z0-9.-]+)(?:\s+"((?:[^"\\]|\\.)*)")?\s*]\s*(?:[#;].*)?$""")
    private val KEY_RE = Regex("""^([A-Za-z][A-Za-z0-9-]*)\s*(?:=(.*))?$""")
    private val SCP_RE = Regex("""^[A-Za-z0-9._~-]+@([^:/\s]+):(.+)$""")
    private val URL_RE = Regex("""^(ssh|git\+ssh|https?|git)://(?:[^@/\s]+@)?([^/:\s]+)(?::\d+)?(/.*)$""", RegexOption.IGNORE_CASE)

    /** Every submodule section with both a `path` and a `url`, in file order (the last value of a key wins). */
    fun parse(content: String): List<Submodule> {
        if (content.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return emptyList()
        val sections = linkedMapOf<String, MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        val lines = joinContinuations(content.replace("\r\n", "\n").split('\n'))
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[")) {
                val m = SECTION_RE.matchEntire(line)
                current = if (m != null && m.groupValues[1].equals("submodule", ignoreCase = true) && m.groups[2] != null) {
                    sections.getOrPut(unescape(m.groupValues[2])) { mutableMapOf() }
                } else null
                continue
            }
            val kv = KEY_RE.matchEntire(line) ?: continue
            val target = current ?: continue
            val value = kv.groups[2]?.value?.let { value(it) } ?: "true"
            target[kv.groupValues[1].lowercase()] = value
        }
        return sections.mapNotNull { (name, keys) ->
            val path = keys["path"]?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val url = keys["url"]?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Submodule(name, path, url, keys["branch"]?.trim()?.takeIf { it.isNotEmpty() })
        }
    }

    /** Lines ending in an unescaped backslash continue on the next line (git-config). */
    private fun joinContinuations(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (l in lines) {
            val trailing = l.takeLastWhile { it == '\\' }.length
            if (trailing % 2 == 1) sb.append(l.dropLast(1)) else { sb.append(l); out += sb.toString(); sb.clear() }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /** A git-config value: quotes removed, escapes resolved, an unquoted `#` or `;` starts a comment. */
    private fun value(raw: String): String {
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        val s = raw.trim()
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> {
                    sb.append(when (val n = s[i + 1]) { 'n' -> '\n'; 't' -> '\t'; 'b' -> '\b'; else -> n })
                    i++
                }
                c == '"' -> quoted = !quoted
                !quoted && (c == '#' || c == ';') -> break
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString().trim()
    }

    private fun unescape(s: String) = s.replace(Regex("""\\(.)"""), "$1")

    /**
     * The bare name of the repository [url] names when it is a repository of [org] on [gitHost] (`https://<host>/…`),
     * else null. Accepted forms: `https://<host>/<org>/<repo>[.git]` (also `http`, `git`, with a user or port),
     * `git@<host>:<org>/<repo>[.git]`, `ssh://git@<host>[:port]/<org>/<repo>[.git]`, and URLs relative to the
     * workspace repository [parentRepo] of [org] (`../<repo>.git`, `../../<org>/<repo>.git`).
     */
    fun repositoryOf(url: String, org: String, parentRepo: String, gitHost: String): String? {
        val u = url.trim()
        if (u.isEmpty() || u.any { it.isWhitespace() || it.isISOControl() }) return null
        val host = hostOf(gitHost) ?: return null
        val path: List<String> = when {
            u.startsWith("./") || u.startsWith("../") -> {
                // Relative to the workspace repository's own URL `<host>/<org>/<parentRepo>`.
                val segs = mutableListOf(org, parentRepo)
                for (s in u.split('/')) {
                    when (s) {
                        "", "." -> {}
                        ".." -> if (segs.isEmpty()) return null else segs.removeAt(segs.size - 1)
                        else -> segs += s
                    }
                }
                segs
            }
            URL_RE.matchEntire(u) != null -> {
                val m = URL_RE.matchEntire(u)!!
                if (!m.groupValues[2].equals(host, ignoreCase = true)) return null
                m.groupValues[3].split('/').filter { it.isNotEmpty() }
            }
            SCP_RE.matchEntire(u) != null -> {
                val m = SCP_RE.matchEntire(u)!!
                if (!m.groupValues[1].equals(host, ignoreCase = true)) return null
                m.groupValues[2].split('/').filter { it.isNotEmpty() }
            }
            else -> return null
        }
        if (path.size != 2 || !path[0].equals(org, ignoreCase = true)) return null
        val name = path[1].removeSuffix("/").let { if (it.endsWith(".git", ignoreCase = true)) it.dropLast(4) else it }
        if (!REPO_NAME_RE.matches(name) || name == "." || name == "..") return null
        return name
    }

    private fun hostOf(gitHost: String): String? =
        runCatching { java.net.URI(gitHost.trim()).host }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** [parse]d submodules with their repositories ([repositoryOf]). */
    fun resolve(content: String, org: String, parentRepo: String, gitHost: String): List<ResolvedSubmodule> =
        parse(content).map { ResolvedSubmodule(it, repositoryOf(it.url, org, parentRepo, gitHost)) }

    /**
     * The members a `.gitmodules` file declares when the repository has no `.agern/workspace.yml`: every submodule of
     * the organization (not the workspace repository itself) whose path is a valid member directory, with the
     * repository's name as label and the submodule's `branch` as base branch (`.` means [parentDefaultBranch]; an
     * invalid one means the member's default branch). A submodule elsewhere, or one that cannot be a member (invalid
     * path, reserved or duplicate label, a repository listed twice, a path inside another member) is context only.
     */
    fun members(subs: List<ResolvedSubmodule>, parentRepo: String, parentDefaultBranch: String): GitmodulesWorkspace {
        val members = mutableListOf<WorkspaceMember>()
        val context = mutableListOf<ResolvedSubmodule>()
        for (s in subs) {
            val repo = s.repository
            if (repo == null) { context += s; continue }
            val dir = s.path.lowercase()
            val clash = members.any { m ->
                val o = m.directory.lowercase()
                m.repository.equals(repo, ignoreCase = true) || m.label.equals(repo, ignoreCase = true) ||
                    o == dir || dir.startsWith("$o/") || o.startsWith("$dir/")
            }
            if (clash || repo.equals(parentRepo, ignoreCase = true) || !WorkspaceDefinitions.validDirectory(s.path) ||
                !WorkspaceDefinitions.validLabel(repo)
            ) {
                context += s
                continue
            }
            members += WorkspaceMember(s.path, repo, branchOf(s.submodule, parentDefaultBranch), repo, submodule = true)
        }
        return GitmodulesWorkspace(members, context)
    }

    /** A submodule's `branch` as a base branch: `.` is the workspace's [parentDefaultBranch]; invalid names are ignored. */
    fun branchOf(s: Submodule, parentDefaultBranch: String): String? = when (val b = s.branch) {
        null -> null
        "." -> parentDefaultBranch
        else -> b.takeIf { WorkspaceDefinitions.validBranch(it) }
    }
}
