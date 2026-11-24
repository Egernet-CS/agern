package dk.egernet.factory

import dk.egernet.factory.github.FakeGitHub
import dk.egernet.factory.workspace.ParsedWorkspace
import dk.egernet.factory.workspace.WorkspaceDefinitions
import dk.egernet.factory.workspace.WorkspaceMember
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class WorkspaceDefinitionTest {
    private fun parse(yaml: String) = WorkspaceDefinitions.parse(yaml, "test-org", "shop")

    private fun members(yaml: String): List<WorkspaceMember> = when (val p = parse(yaml)) {
        is ParsedWorkspace.Valid -> p.members
        is ParsedWorkspace.Invalid -> fail("expected valid, got ${p.errors}")
    }

    private fun errors(yaml: String): List<String> = when (val p = parse(yaml)) {
        is ParsedWorkspace.Invalid -> p.errors
        is ParsedWorkspace.Valid -> fail("expected invalid, got ${p.members}")
    }

    /** A definition with one member whose [key] is [value] (other keys valid). */
    private fun one(key: String, value: String): String {
        val m = linkedMapOf("directory" to "app", "repository" to "shop-app", "label" to "app")
        m[key] = value
        return "members:\n  - " + m.entries.joinToString("\n    ") { "${it.key}: ${it.value}" }
    }

    private fun assertError(yaml: String, prefix: String, contains: String = "") {
        val e = errors(yaml)
        assertTrue(e.any { it.startsWith(prefix) && it.contains(contains) }, "expected '$prefix…$contains' in $e")
    }

    @Test
    fun `a valid file with defaults`() {
        val m = members(
            """
            members:
              - directory: app
                repository: shop-app
                branch: dev
                label: App
              - directory: tools/cli
                repository: test-org/shop-tools
            """.trimIndent(),
        )
        assertEquals(WorkspaceMember("app", "shop-app", "dev", "App"), m[0])
        // The default label is the repository name; no branch means the member's default branch; org/ is stripped.
        assertEquals(WorkspaceMember("tools/cli", "shop-tools", null, "shop-tools"), m[1])
    }

    @Test
    fun `the seeded demo workspace is valid`() {
        val p = WorkspaceDefinitions.parse(FakeGitHub.DEMO_WORKSPACE_FILE, "test-org", "demo-workspace")
        assertTrue(p is ParsedWorkspace.Valid && p.members.size == 3, p.toString())
    }

    @Test
    fun `file level rules`() {
        assertTrue(errors("members: []").single().startsWith("members: at least one member"))
        assertTrue(errors("members: x").single().startsWith("members: must be a list"))
        assertTrue(errors("other: 1").any { it.startsWith("other: unknown key") })
        assertTrue(errors("other: 1").any { it.startsWith("members: required") })
        assertTrue(errors("- a").single().startsWith("The file must be a mapping"))
        val many = "members:\n" + (0..50).joinToString("\n") { "  - { directory: d$it, repository: r$it }" }
        assertTrue(errors(many).any { it.startsWith("members: at most 50 members") })
        assertTrue(errors("members:\n  - x").single().startsWith("members[0]: must be a mapping"))
        assertTrue(errors("members:\n  - { directory: a, repository: b }\nmembers: []").single().startsWith("Not valid YAML"))
        assertTrue(errors("members: &m\n  - { directory: a, repository: b }\nx: *m").first().startsWith("Not valid YAML"))
        assertTrue(errors("members:\n  - { directory: a, repository: b }\n#" + "x".repeat(70_000)).single().contains("larger than"))
        assertError(one("color", "red"), "members[0].color: unknown key")
    }

    @Test
    fun `directory rules`() {
        assertError("members:\n  - { repository: shop-app }", "members[0].directory: required")
        assertError(one("directory", "a/b/c/d/e"), "members[0].directory:", "at most 4")
        assertError(one("directory", "/abs"), "members[0].directory:", "not a relative directory")
        assertError(one("directory", "a//b"), "members[0].directory:", "not a relative directory")
        assertError(one("directory", "'a b'"), "members[0].directory:", "not a relative directory")
        for (bad in listOf(".", "..", "x/..", ".git", ".Agern", ".agern-run", "a/.claude")) {
            assertError(one("directory", "'$bad'"), "members[0].directory:", "may not be a path segment")
        }
        assertEquals("a/b/c/d", members(one("directory", "a/b/c/d")).single().directory)
        assertError(
            "members:\n  - { directory: app, repository: a }\n  - { directory: App/x, repository: b }",
            "members[1].directory:", "overlaps",
        )
        assertError("members:\n  - { directory: app, repository: a }\n  - { directory: APP, repository: b }", "members[1].directory:", "overlaps")
        assertEquals(2, members("members:\n  - { directory: app, repository: a }\n  - { directory: apps, repository: b }").size)
    }

    @Test
    fun `repository rules`() {
        assertError("members:\n  - { directory: app }", "members[0].repository: required")
        assertError(one("repository", "other-org/x"), "members[0].repository:", "not in the organization")
        assertError(one("repository", "a/b/c"), "members[0].repository:", "not a repository name")
        assertError(one("repository", "'bad name'"), "members[0].repository:", "not a repository name")
        assertError(one("repository", "shop"), "members[0].repository:", "workspace repository itself")
        assertError(one("repository", "TEST-ORG/Shop"), "members[0].repository:", "workspace repository itself")
        assertError(
            "members:\n  - { directory: a, repository: x, label: a }\n  - { directory: b, repository: X, label: b }",
            "members[1].repository:", "listed twice",
        )
    }

    @Test
    fun `branch rules`() {
        assertEquals("feature/long-lived", members(one("branch", "feature/long-lived")).single().branch)
        for (bad in listOf("'-x'", "/x", ".x", "x/", "x.", "x.lock", "a..b", "a//b", "'a b'", "x".repeat(201))) {
            assertError(one("branch", bad), "members[0].branch:")
        }
        assertError(one("branch", "[a]"), "members[0].branch: must be a single value")
    }

    @Test
    fun `label rules`() {
        assertEquals("Shop App", members(one("label", "'  Shop App '")).single().label)
        assertError(one("label", "'  '"), "members[0].label:", "1–50")
        assertError(one("label", "x".repeat(51)), "members[0].label:", "1–50")
        assertError(one("label", "\"a\\tb\""), "members[0].label:", "control")
        for (reserved in listOf("needs-human", "Bug", "enhancement", "feature")) {
            assertError(one("label", reserved), "members[0].label:", "reserved")
        }
        // Defaults count too: the repository name is the label.
        assertError(
            "members:\n  - { directory: a, repository: x, label: tools }\n  - { directory: b, repository: tools }",
            "members[1].label:", "label of another member",
        )
        assertError("members:\n  - { directory: a, repository: Bug }", "members[0].label:", "default label")
    }

    @Test
    fun `every problem is listed`() {
        val e = errors("members:\n  - { directory: ., repository: shop, branch: -x, label: bug }\n  - { what: 1 }")
        assertTrue(e.size >= 6, e.toString())
        assertTrue(e.all { it.startsWith("members[") }, e.toString())
    }
}
