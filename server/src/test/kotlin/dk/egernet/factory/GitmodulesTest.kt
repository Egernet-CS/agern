package dk.egernet.factory

import dk.egernet.factory.workspace.GITMODULES_FILE
import dk.egernet.factory.workspace.Gitmodules
import dk.egernet.factory.workspace.ParsedWorkspace
import dk.egernet.factory.workspace.Submodule
import dk.egernet.factory.workspace.WORKSPACE_FILE
import dk.egernet.factory.workspace.WorkspaceDefinitions
import dk.egernet.factory.workspace.WorkspaceMember
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Workspaces from git submodules: `.gitmodules` parsing, URL resolution and members (docs/ARCHITECTURE.md §5). */
class GitmodulesTest {
    private val host = "https://github.com/"
    private fun repoOf(url: String) = Gitmodules.repositoryOf(url, "Org", "shop", host)

    @Test
    fun `git-config syntax is parsed`() {
        val subs = Gitmodules.parse(
            """
            # a comment
            [submodule "api"]
            	path = services/api
            	url = git@github.com:Org/shop-api.git
            [submodule "web"] ; trailing comment
            	path = "web"   # quoted value, inline comment
            	URL = ../shop-web.git
            	branch = dev
            [core]
            	path = not/a/submodule
            [submodule "no-url"]
            	path = x
            [submodule "a \"quoted\" name"]
            	path = libs/q
            	url = https://github.com/Org/q
            [submodule "api"]
            	url = git@github.com:Org/shop-api-v2.git
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                Submodule("api", "services/api", "git@github.com:Org/shop-api-v2.git", null),
                Submodule("web", "web", "../shop-web.git", "dev"),
                Submodule("a \"quoted\" name", "libs/q", "https://github.com/Org/q", null),
            ),
            subs,
        )
        assertTrue(Gitmodules.parse("not [ config").isEmpty())
        assertTrue(Gitmodules.parse("[submodule \"x\"]\n\tpath = a\n\turl = b\n#" + "x".repeat(70_000)).isEmpty())
    }

    @Test
    fun `submodule URLs resolve to repositories of the organization`() {
        assertEquals("shop-api", repoOf("https://github.com/Org/shop-api.git"))
        assertEquals("shop-api", repoOf("https://github.com/org/shop-api"))
        assertEquals("shop-api", repoOf("https://user@GitHub.com:443/Org/shop-api.git/"))
        assertEquals("shop-api", repoOf("git@github.com:Org/shop-api.git"))
        assertEquals("shop-api", repoOf("ssh://git@github.com/Org/shop-api.git"))
        assertEquals("shop-api", repoOf("ssh://git@github.com:22/Org/shop-api"))
        assertEquals("shop-api", repoOf("../shop-api.git"))
        assertEquals("shop-api", repoOf("../../Org/shop-api.git"))
        // Elsewhere: another organization, another host, not a repository, or nonsense.
        assertNull(repoOf("https://github.com/Other/shop-api.git"))
        assertNull(repoOf("git@gitlab.com:Org/shop-api.git"))
        assertNull(repoOf("https://example.com/Org/shop-api.git"))
        assertNull(repoOf("../../Other/shop-api.git"))
        assertNull(repoOf("./nested.git"))
        assertNull(repoOf("../../../x.git"))
        assertNull(repoOf("https://github.com/Org/a/b.git"))
        assertNull(repoOf("/srv/git/shop-api.git"))
        assertNull(repoOf("https://github.com/Org/bad name.git"))
        assertNull(repoOf(""))
    }

    @Test
    fun `submodules of the organization are members, others are context only`() {
        val subs = Gitmodules.resolve(
            """
            [submodule "api"]
            	path = services/api
            	url = git@github.com:Org/shop-api.git
            [submodule "web"]
            	path = web
            	url = ../shop-web.git
            	branch = .
            [submodule "icons"]
            	path = vendor/icons
            	url = https://github.com/elsewhere/icons.git
            [submodule "self"]
            	path = self
            	url = ../shop.git
            [submodule "twice"]
            	path = again
            	url = ../shop-api.git
            [submodule "bad"]
            	path = .git/x
            	url = ../shop-bad.git
            [submodule "badbranch"]
            	path = docs
            	url = ../shop-docs.git
            	branch = -x
            """.trimIndent(),
            "Org", "shop", host,
        )
        val ws = Gitmodules.members(subs, "shop", "trunk")
        assertEquals(
            listOf(
                WorkspaceMember("services/api", "shop-api", null, "shop-api", submodule = true),
                WorkspaceMember("web", "shop-web", "trunk", "shop-web", submodule = true),
                WorkspaceMember("docs", "shop-docs", null, "shop-docs", submodule = true),
            ),
            ws.members,
        )
        assertEquals(listOf("vendor/icons", "self", "again", ".git/x"), ws.contextOnly.map { it.path })
    }

    @Test
    fun `workspace yml wins and may name submodule paths`() {
        val subs = Gitmodules.resolve(
            "[submodule \"api\"]\n\tpath = services/api\n\turl = ../shop-api.git\n\tbranch = dev\n" +
                "[submodule \"web\"]\n\tpath = web\n\turl = ../shop-web.git\n",
            "test-org", "shop", host,
        )
        val yml = """
            members:
              - directory: services/api
                repository: shop-api
                label: api
              - directory: web
                repository: shop-web
                branch: main
              - directory: tools
                repository: shop-tools
        """.trimIndent()
        val p = WorkspaceDefinitions.parse(yml, "test-org", "shop", subs, "main")
        val members = (p as? ParsedWorkspace.Valid)?.members ?: fail(p.toString())
        assertEquals(
            listOf(
                // The submodule's branch unless the file gives one; the file's label.
                WorkspaceMember("services/api", "shop-api", "dev", "api", submodule = true),
                WorkspaceMember("web", "shop-web", "main", "shop-web", submodule = true),
                WorkspaceMember("tools", "shop-tools", null, "shop-tools"),
            ),
            members,
        )
        // A member at a submodule's path must be that submodule's repository.
        val wrong = WorkspaceDefinitions.parse("members:\n  - { directory: web, repository: shop-api }", "test-org", "shop", subs)
        val errors = (wrong as? ParsedWorkspace.Invalid)?.errors ?: fail(wrong.toString())
        assertEquals("members[0].directory: 'web' is the submodule ../shop-web.git in .gitmodules, not the repository test-org/shop-api", errors.single())
    }

    // ------------------------------------------------------------ WorkspaceService with the fake GitHub

    private val world = TestWorld()
    private val s = world.services
    private val alice = world.user("alice")

    @AfterTest fun tearDown() = world.close()

    @Test
    fun `a repository with submodules of the organization is a workspace`() {
        val state = assertNotNull(s.workspaceDefs.lookup(alice, "demo-shop"))
        assertTrue(state.valid)
        assertEquals(GITMODULES_FILE, state.path)
        assertEquals(
            listOf(
                WorkspaceMember("services/api", "demo-shop-api", null, "demo-shop-api", submodule = true),
                WorkspaceMember("web", "demo-shop-web", "main", "demo-shop-web", submodule = true),
            ),
            state.members,
        )
        assertEquals(listOf("vendor/icons"), state.contextOnly.map { it.path })
        // Submodules elsewhere only: not a workspace.
        world.github.setFile("demo-app", GITMODULES_FILE, "[submodule \"x\"]\n\tpath = x\n\turl = https://github.com/elsewhere/x.git\n")
        assertNull(s.workspaceDefs.lookup(alice, "demo-app"))
        // .agern/workspace.yml wins over .gitmodules.
        world.github.setFile("demo-shop", WORKSPACE_FILE, "members:\n  - { directory: web, repository: demo-shop-web, label: web }\n")
        s.workspaceDefs.invalidate()
        val yml = assertNotNull(s.workspaceDefs.lookup(alice, "demo-shop"))
        assertEquals(WORKSPACE_FILE, yml.path)
        assertEquals(listOf(WorkspaceMember("web", "demo-shop-web", "main", "web", submodule = true)), yml.members)
        assertEquals(listOf("services/api", "vendor/icons"), yml.contextOnly.map { it.path })
    }
}
