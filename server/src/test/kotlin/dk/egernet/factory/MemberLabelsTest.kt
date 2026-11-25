package dk.egernet.factory

import com.sun.net.httpserver.HttpServer
import dk.egernet.factory.github.HttpGitHub
import dk.egernet.factory.workspace.GITMODULES_FILE
import dk.egernet.factory.workspace.MemberLabels
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Agern makes sure the member labels of a workspace exist (docs/ARCHITECTURE.md §5 "Member labels"). */
class MemberLabelsTest {
    private val world = TestWorld(twoFactor = false)
    private val s = world.services
    private val gh = world.github
    private val alice = world.user("alice")

    @AfterTest fun tearDown() = world.close()

    private fun project(repo: String) = runBlocking { s.factory.getProject(alice, repo) }
    private fun labelsOf(repo: String) = gh.repos.getValue(repo).labels.values.toList()

    @Test
    fun `a missing member label is created once, with a stable colour, and existing ones are left alone`() {
        // demo-shop's member tools/cli (demo-ws-tools) has a label no issue carries yet; the others exist.
        assertEquals(emptyList(), project("demo-shop").workspace!!.missingLabels)
        val created = labelsOf("demo-shop").single()
        assertEquals("demo-ws-tools", created.name)
        assertEquals(MemberLabels.color("demo-ws-tools"), created.color)
        assertTrue(Regex("^[0-9a-f]{6}$").matches(created.color), created.color)
        assertEquals("Runs change the member tools/cli (demo-ws-tools)", MemberLabels.description(s.workspaceDefs.lookup(alice, "demo-shop")!!.members[2]))
        assertEquals(1, gh.actions.count { it.startsWith("create-label demo-shop ") }, gh.actions.toString())

        // At most once per definition: a label deleted on GitHub meanwhile is not created again at once…
        gh.repos.getValue("demo-shop").labels.clear()
        project("demo-shop")
        assertEquals(1, gh.actions.count { it.startsWith("create-label") })
        // …but a changed definition is checked again.
        gh.setFile("demo-shop", GITMODULES_FILE, gh.fileOf("demo-shop", GITMODULES_FILE)!!.replace("path = tools/cli", "path = tools/cli2"))
        s.workspaceDefs.invalidate()
        project("demo-shop")
        assertEquals(2, gh.actions.count { it.startsWith("create-label demo-shop demo-ws-tools") })
        // demo-workspace's labels all exist on its issues: nothing to create.
        assertEquals(emptyList(), project("demo-workspace").workspace!!.missingLabels)
        assertTrue(gh.actions.none { it.startsWith("create-label demo-workspace") })
    }

    @Test
    fun `a token that may not create labels shows them as missing, and never fails the page`() {
        gh.createLabelFailure = 403
        assertEquals(listOf("demo-ws-tools"), project("demo-shop").workspace!!.missingLabels)
        val e = assertFailsWith<ApiException> { runBlocking { s.factory.createMemberLabels(alice, "demo-shop") } }
        assertEquals(403, e.status.value)
        assertEquals("labels_forbidden", e.code)
        gh.createLabelFailure = null
        val ok = runBlocking { s.factory.createMemberLabels(alice, "demo-shop") }
        assertEquals(listOf("demo-ws-tools"), ok.created)
        assertEquals(emptyList(), ok.missing)
        assertEquals(emptyList(), project("demo-shop").workspace!!.missingLabels)
        // Again: nothing left to create.
        assertEquals(emptyList(), runBlocking { s.factory.createMemberLabels(alice, "demo-shop") }.created)
        assertEquals("not_a_workspace", assertFailsWith<ApiException> { runBlocking { s.factory.createMemberLabels(alice, "demo-app") } }.code)
    }

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String): HttpClient {
        val c = createClient { install(HttpCookies) }
        assertEquals(200, c.post("/api/auth/login") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
        }.status.value)
        assertEquals(200, c.put("/api/me/github-token") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"token":"fake-$user"}""")
        }.status.value)
        return c
    }

    private suspend fun HttpClient.create(repo: String, csrf: Boolean = true): HttpResponse =
        post("/api/projects/$repo/workspace/labels") { if (csrf) header("X-Factory-Request", "1") }

    @Test
    fun `the endpoint creates the missing labels for whoever can see the project`() = testApplication {
        application { agernModule(world.services) }
        world.github.addRepo("hidden-shop", "only alice", private = true, visibleTo = setOf("alice"))
        world.github.setFile("hidden-shop", GITMODULES_FILE, "[submodule \"a\"]\n\tpath = a\n\turl = ../demo-app.git\n")
        gh.createLabelFailure = 403
        val bob = login("bob", "pw-b")
        val page = AppJson.parseToJsonElement(bob.get("/api/projects/demo-shop").bodyAsText()).jsonObject
        assertEquals(listOf("demo-ws-tools"), page["workspace"]!!.jsonObject["missingLabels"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(403, bob.create("demo-shop", csrf = false).status.value, "CSRF header required")
        val refused = bob.create("demo-shop")
        assertEquals(403, refused.status.value)
        assertTrue(refused.bodyAsText().contains("labels_forbidden"))
        gh.createLabelFailure = null
        val ok = bob.create("demo-shop")
        assertEquals(200, ok.status.value)
        assertEquals("""{"created":["demo-ws-tools"],"missing":[]}""", ok.bodyAsText())
        // Not visible to bob: 404, nothing created.
        assertEquals(404, bob.create("hidden-shop").status.value)
        assertFalse(gh.actions.any { it.startsWith("create-label hidden-shop") })
        assertEquals(401, createClient { }.post("/api/projects/demo-shop/workspace/labels") { header("X-Factory-Request", "1") }.status.value)
    }

    @Test
    fun `GitHub's answers for labels`() {
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
                calls += "${ex.requestMethod} ${ex.requestURI} $body"
                val (status, out) = when {
                    ex.requestMethod == "GET" -> 200 to """[{"name":"bug","color":"d73a4a"},{"name":"App","color":"aaaaaa"}]"""
                    body.contains("\"exists\"") -> 422 to """{"message":"Validation Failed","errors":[{"resource":"Label","code":"already_exists","field":"name"}]}"""
                    body.contains("\"nope\"") -> 403 to """{"message":"Resource not accessible by personal access token"}"""
                    else -> 201 to """{"name":"x"}"""
                }
                val bytes = out.toByteArray()
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            val c = HttpGitHub("http://127.0.0.1:${server.address.port}", "http://127.0.0.1").client("token")
            assertEquals(listOf("bug", "App"), c.listLabels("Org", "shop").map { it.name })
            assertTrue(c.createLabel("Org", "shop", "new", "123456", "Runs change the member app (shop-app)"))
            assertTrue(calls.any { it.startsWith("POST /repos/Org/shop/labels ") && it.contains("\"color\":\"123456\"") && it.contains("\"description\"") }, calls.toString())
            assertFalse(c.createLabel("Org", "shop", "exists", "123456", ""))
            assertEquals(403, assertFailsWith<dk.egernet.factory.github.GitHubException> { c.createLabel("Org", "shop", "nope", "123456", "") }.status)
        } finally {
            server.stop(0)
        }
    }
}
