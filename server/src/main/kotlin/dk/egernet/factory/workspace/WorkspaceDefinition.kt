package dk.egernet.factory.workspace

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import dk.egernet.factory.agent.REPO_NAME_RE

/** Where a workspace project lists its member repositories, on the workspace repository's default branch. */
const val WORKSPACE_FILE = ".agern/workspace.yml"

/**
 * One member of a workspace (docs/ARCHITECTURE.md §5 "Workspace projects"): checked out at [directory] (relative to the
 * workspace root, `/`-separated), the bare repository name [repository] of the configured organization, the declared
 * base [branch] (null: the member repository's default branch) and the issue [label] that routes issues to it.
 */
data class WorkspaceMember(val directory: String, val repository: String, val branch: String?, val label: String)

sealed interface ParsedWorkspace {
    data class Valid(val members: List<WorkspaceMember>) : ParsedWorkspace
    data class Invalid(val errors: List<String>) : ParsedWorkspace
}

/**
 * Parses and strictly validates a workspace definition: unknown keys, anchors, duplicate keys and malformed values are
 * all errors, each reported with its path in the file (the style of `SandboxDefinitions.parse`).
 */
object WorkspaceDefinitions {
    const val MAX_BYTES = 64 * 1024
    const val MAX_MEMBERS = 50
    const val MAX_DIRECTORY_SEGMENTS = 4
    const val MAX_LABEL_CHARS = 50

    private val TOP_KEYS = listOf("members")
    private val MEMBER_KEYS = listOf("directory", "repository", "branch", "label")
    private val SEGMENT_RE = Regex("^[A-Za-z0-9._-]{1,100}$")
    private val RESERVED_SEGMENTS = setOf(".", "..", ".git", ".agern", ".agern-run", ".claude")
    private val BRANCH_RE = Regex("^[A-Za-z0-9._/-]{1,200}$")
    private val RESERVED_LABELS = setOf("needs-human", "bug", "enhancement", "feature")

    private val yaml = Yaml.default

    /** Whether [directory] is a valid member directory (also used to re-validate what is read back from disk). */
    fun validDirectory(directory: String): Boolean = directoryProblem(directory) == null

    /** Whether [branch] is a valid base branch name. */
    fun validBranch(branch: String): Boolean = branchProblem(branch) == null

    private fun directoryProblem(d: String): String? {
        val segments = d.split('/')
        if (segments.size > MAX_DIRECTORY_SEGMENTS) return "at most $MAX_DIRECTORY_SEGMENTS path segments"
        for (s in segments) {
            if (!SEGMENT_RE.matches(s)) {
                return "'$d' is not a relative directory: segments of letters, digits, '.', '_' and '-' (at most 100 characters each), separated by '/'"
            }
            if (s.lowercase() in RESERVED_SEGMENTS) return "'$s' may not be a path segment"
        }
        return null
    }

    private fun branchProblem(b: String): String? = when {
        !BRANCH_RE.matches(b) -> "'$b' is not a branch name (letters, digits, '.', '_', '-' and '/', at most 200 characters)"
        b.startsWith("-") || b.startsWith("/") || b.startsWith(".") -> "a branch name may not start with '-', '/' or '.'"
        b.endsWith("/") || b.endsWith(".") || b.endsWith(".lock") -> "a branch name may not end with '/', '.' or '.lock'"
        ".." in b || "//" in b -> "a branch name may not contain '..' or '//'"
        else -> null
    }

    fun parse(content: String, org: String, workspaceRepo: String): ParsedWorkspace {
        if (content.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            return ParsedWorkspace.Invalid(listOf("$WORKSPACE_FILE is larger than ${MAX_BYTES / 1024} KiB"))
        }
        val root = try {
            yaml.parseToYamlNode(content)
        } catch (e: YamlException) {
            return ParsedWorkspace.Invalid(listOf("Not valid YAML: ${e.message} (line ${e.line}, column ${e.column})"))
        } catch (e: Exception) {
            return ParsedWorkspace.Invalid(listOf("Not valid YAML: ${e.message ?: e.javaClass.simpleName}"))
        }
        val top = root as? YamlMap ?: return ParsedWorkspace.Invalid(listOf("The file must be a mapping with `members:`"))
        val errors = mutableListOf<String>()
        unknownKeys(top, TOP_KEYS, "", errors)
        val list = when (val node = top.get<YamlNode>("members")) {
            null, is YamlNull -> { errors += "members: required (a list of 1–$MAX_MEMBERS members)"; null }
            is YamlList -> node
            else -> { errors += "members: must be a list of members"; null }
        }
        val members = mutableListOf<Pair<Int, WorkspaceMember>>()
        if (list != null) {
            if (list.items.isEmpty()) errors += "members: at least one member"
            if (list.items.size > MAX_MEMBERS) errors += "members: at most $MAX_MEMBERS members"
            list.items.forEachIndexed { i, item -> member(item, "members[$i]", org, workspaceRepo, errors)?.let { members += i to it } }
        }
        // Rules across members, reported at the later one.
        members.forEachIndexed { k, (i, m) ->
            val path = "members[$i]"
            val earlier = members.take(k).map { it.second }
            val d = m.directory.lowercase()
            earlier.firstOrNull { o -> o.directory.lowercase().let { it == d || d.startsWith("$it/") || it.startsWith("$d/") } }?.let { o ->
                errors += "$path.directory: '${m.directory}' overlaps the directory '${o.directory}' of another member"
            }
            if (earlier.any { it.repository.equals(m.repository, ignoreCase = true) }) {
                errors += "$path.repository: '${m.repository}' is listed twice"
            }
            if (earlier.any { it.label.equals(m.label, ignoreCase = true) }) {
                errors += "$path.label: '${m.label}' is the label of another member (labels must be unique, ignoring case)"
            }
        }
        return if (errors.isEmpty()) ParsedWorkspace.Valid(members.map { it.second }) else ParsedWorkspace.Invalid(errors)
    }

    private fun member(node: YamlNode, path: String, org: String, workspaceRepo: String, errors: MutableList<String>): WorkspaceMember? {
        val m = node as? YamlMap
        if (m == null) {
            errors += "$path: must be a mapping with `directory:` and `repository:`"
            return null
        }
        unknownKeys(m, MEMBER_KEYS, "$path.", errors)
        val before = errors.size
        val directory = required(m, "directory", path, errors)?.let { d ->
            directoryProblem(d)?.let { errors += "$path.directory: $it"; null } ?: d
        }
        val repository = required(m, "repository", path, errors)?.let { r ->
            val parts = r.split('/')
            val name = when {
                parts.size == 1 -> r
                parts.size == 2 && parts[0].equals(org, ignoreCase = true) -> parts[1]
                parts.size == 2 -> { errors += "$path.repository: '$r' is not in the organization $org"; return@let null }
                else -> { errors += "$path.repository: '$r' is not a repository name (`<name>` or `$org/<name>`)"; return@let null }
            }
            when {
                !REPO_NAME_RE.matches(name) || name == "." || name == ".." -> { errors += "$path.repository: '$r' is not a repository name"; null }
                name.equals(workspaceRepo, ignoreCase = true) -> { errors += "$path.repository: the workspace repository itself cannot be a member"; null }
                else -> name
            }
        }
        val branch = optional(m, "branch", path, errors)?.let { b -> branchProblem(b)?.let { errors += "$path.branch: $it"; null } ?: b }
        val label = optional(m, "label", path, errors)?.let { raw ->
            val l = raw.trim()
            when {
                l.isEmpty() || l.length > MAX_LABEL_CHARS -> { errors += "$path.label: 1–$MAX_LABEL_CHARS characters"; null }
                l.any { it.isISOControl() } -> { errors += "$path.label: no control characters"; null }
                l.lowercase() in RESERVED_LABELS -> { errors += "$path.label: '$l' is reserved (not ${RESERVED_LABELS.sorted().joinToString(", ")})"; null }
                else -> l
            }
        } ?: repository?.takeIf { m.get<YamlNode>("label").let { it == null || it is YamlNull } }?.let { r ->
            // The default label is the repository name, which must obey the same rules.
            if (r.lowercase() in RESERVED_LABELS || r.length > MAX_LABEL_CHARS) {
                errors += "$path.label: the default label '$r' (the repository name) is not allowed here; give a label"
                null
            } else r
        }
        if (errors.size != before || directory == null || repository == null || label == null) return null
        return WorkspaceMember(directory, repository, branch, label)
    }

    private fun required(m: YamlMap, key: String, path: String, errors: MutableList<String>): String? {
        val node = m.get<YamlNode>(key)
        if (node == null || node is YamlNull) {
            errors += "$path.$key: required"
            return null
        }
        return scalar(node, "$path.$key", errors)
    }

    private fun optional(m: YamlMap, key: String, path: String, errors: MutableList<String>): String? {
        val node = m.get<YamlNode>(key)?.takeUnless { it is YamlNull } ?: return null
        return scalar(node, "$path.$key", errors)
    }

    private fun unknownKeys(m: YamlMap, allowed: List<String>, prefix: String, errors: MutableList<String>) {
        for (k in m.entries.keys) {
            if (k.content !in allowed) errors += "$prefix${k.content}: unknown key (allowed here: ${allowed.joinToString(", ")})"
        }
    }

    private fun scalar(node: YamlNode, path: String, errors: MutableList<String>): String? {
        val s = node as? YamlScalar
        if (s == null) {
            errors += "$path: must be a single value, not a ${if (node is YamlMap) "mapping" else if (node is YamlList) "list" else "null"}"
            return null
        }
        return s.content
    }
}
