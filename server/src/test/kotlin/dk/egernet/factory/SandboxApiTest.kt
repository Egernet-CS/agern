package dk.egernet.factory

import dk.egernet.factory.sandbox.SandboxDefinitions
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Sandbox definition status, approval by content hash and revocation over HTTP (docs/ARCHITECTURE.md §8). */
class SandboxApiTest {
    private val world = TestWorld(twoFactor = false, sandbox = true)
    private val s = world.services

    @AfterTest fun tearDown() = world.closeAndReleaseDb()

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String): HttpClient {
        val c = createClient { install(HttpCookies) }
        assertEquals(200, c.post("/api/auth/login") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
        }.status.value)
        c.put("/api/me/github-token") { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"token":"fake-$user"}""") }
        return c
    }

    private suspend fun HttpClient.status(repo: String = "demo-app"): JsonObject {
        val r = get("/api/projects/$repo/sandbox")
        assertEquals(200, r.status.value, r.bodyAsText())
        return AppJson.parseToJsonElement(r.bodyAsText()).jsonObject
    }

    private suspend fun HttpClient.approve(sha: String, repo: String = "demo-app"): HttpResponse =
        post("/api/projects/$repo/sandbox/approval") { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"sha256":"$sha"}""") }

    private suspend fun HttpClient.revoke(repo: String = "demo-app"): HttpResponse =
        delete("/api/projects/$repo/sandbox/approval") { header("X-Factory-Request", "1") }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private suspend fun code(r: HttpResponse) = AppJson.parseToJsonElement(r.bodyAsText()).jsonObject["error"]!!.jsonObject.str("code")

    private val v1 = "image: docker.io/library/node:22\nservices:\n  db:\n    image: docker.io/library/postgres:17\nresources: { cpus: 8 }\n"
    private val v2 = v1 + "caches: [ /root/.npm ]\n"

    @Test
    fun `pending, approved, changed, invalid and revoked`() = testApplication {
        application { agernModule(s) }
        val alice = login("alice", "pw-a")       // can see demo-app and private-lab, not an admin
        val root = login("root", "pw-r")         // admin, cannot see private-lab
        val bob = login("bob", "pw-b")

        // No file yet.
        alice.status().let {
            assertEquals("none", it.str("state"))
            assertEquals(".agern/sandbox.yml", it.str("path"))
            assertEquals("main", it.str("branch"))
            assertEquals(true, it["enabled"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(JsonNull, it["approval"])
        }

        // A valid definition is pending, with its content, hash and capped limits; everyone who can see the project sees it.
        world.sandboxFile(v1)
        val sha1 = SandboxDefinitions.sha256(v1)
        bob.status().let {
            assertEquals("pending", it.str("state"))
            assertEquals(sha1, it.str("sha256"))
            assertEquals(v1, it.str("content"))
            val def = it["definition"]!!.jsonObject
            assertEquals("docker.io/library/node:22", def.str("image"))
            assertEquals("db", def["services"]!!.jsonArray[0].jsonObject.str("name"))
            val limits = def["limits"]!!.jsonObject
            assertEquals("2.0", limits.str("cpus"))
            assertEquals("8.0", limits.str("requestedCpus"))
            assertEquals("true", limits.str("capped"))
        }

        // Only admins approve, and only the content they saw.
        assertEquals(403, alice.approve(sha1).status.value)
        assertEquals(409, root.approve("0".repeat(64)).let { assertEquals("sandbox_definition_changed", code(it)); it }.status.value)
        assertEquals(400, root.approve("not-a-hash").status.value)
        val approved = root.approve(sha1)
        assertEquals(200, approved.status.value, approved.bodyAsText())
        alice.status().let {
            assertEquals("approved", it.str("state"))
            val a = it["approval"]!!.jsonObject
            assertEquals(sha1, a.str("sha256"))
            assertEquals(v1, a.str("content"))
            assertEquals("root", a["approvedBy"]!!.jsonObject.str("username"))
        }

        // The file changes: pending again; the old approval stays visible for a diff.
        world.sandboxFile(v2)
        alice.status().let {
            assertEquals("pending", it.str("state"))
            assertEquals(SandboxDefinitions.sha256(v2), it.str("sha256"))
            assertEquals(sha1, it["approval"]!!.jsonObject.str("sha256"))
        }
        assertEquals(200, root.approve(SandboxDefinitions.sha256(v2)).status.value)
        assertEquals("approved", alice.status().str("state"))

        // An invalid file cannot be approved.
        val bad = "image: node:22\nprivileged: true\n"
        world.sandboxFile(bad)
        alice.status().let {
            assertEquals("invalid", it.str("state"))
            assertEquals(2, it["errors"]!!.jsonArray.size)
            assertEquals(JsonNull, it["definition"])
        }
        root.approve(SandboxDefinitions.sha256(bad)).let {
            assertEquals(422, it.status.value)
            assertEquals("sandbox_definition_invalid", code(it))
        }

        // Revoking: admins only.
        assertEquals(403, alice.revoke().status.value)
        world.sandboxFile(v2)
        assertEquals("approved", alice.status().str("state"))
        val revoked = root.revoke()
        assertEquals(200, revoked.status.value)
        assertEquals("pending", AppJson.parseToJsonElement(revoked.bodyAsText()).jsonObject.str("state"))
        assertEquals(JsonNull, alice.status()["approval"])

        // Projects the caller cannot see are not found, for admins too.
        assertEquals(404, bob.get("/api/projects/private-lab/sandbox").status.value)
        world.sandboxFile(v1, repo = "private-lab")
        assertEquals("pending", alice.status("private-lab").str("state"))
        assertEquals(404, root.approve(sha1, "private-lab").status.value)
        assertEquals(404, root.revoke("private-lab").status.value)
        assertEquals(404, root.get("/api/projects/no-such-repo/sandbox").status.value)

        // Mutations need the CSRF header like every other.
        assertEquals(403, root.post("/api/projects/demo-app/sandbox/approval") {
            contentType(ContentType.Application.Json); setBody("""{"sha256":"$sha1"}""")
        }.status.value)
    }
}
