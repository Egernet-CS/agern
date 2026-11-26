package dk.egernet.factory

import dk.egernet.factory.deploy.DeployDefinitions
import dk.egernet.factory.deploy.DeployException
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
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Deploy status, approval, the project's key and target checks over HTTP (docs/ARCHITECTURE.md §8). */
class DeployApiTest {
    private val worlds = mutableListOf<TestWorld>()
    private fun world(tweak: (Config) -> Config = { it }) = TestWorld(twoFactor = false, tweak = tweak).also { worlds += it }

    @AfterTest fun tearDown() = worlds.forEach { it.close() }

    /** Every response body of the test, searched for private key material at the end. */
    private val bodies = mutableListOf<String>()

    private suspend fun ApplicationTestBuilder.login(user: String, pw: String): HttpClient {
        val c = createClient { install(HttpCookies) }
        assertEquals(200, c.post("/api/auth/login") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw"}""")
        }.status.value)
        c.put("/api/me/github-token") { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"token":"fake-$user"}""") }
        return c
    }

    private suspend fun HttpResponse.text() = bodyAsText().also { bodies += it }
    private suspend fun HttpClient.status(repo: String = "demo-app"): JsonObject {
        val r = get("/api/projects/$repo/deploy")
        assertEquals(200, r.status.value, r.text())
        return AppJson.parseToJsonElement(r.text()).jsonObject
    }
    private suspend fun HttpClient.send(path: String, body: String? = null): HttpResponse = post("/api/projects/$path") {
        header("X-Factory-Request", "1")
        if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
    }.also { it.text() }
    private suspend fun HttpClient.approve(sha: String, repo: String = "demo-app") = send("$repo/deploy/approval", """{"sha256":"$sha"}""")
    private suspend fun HttpClient.revoke(repo: String = "demo-app") = delete("/api/projects/$repo/deploy/approval") { header("X-Factory-Request", "1") }.also { it.text() }
    private suspend fun HttpClient.check(target: String, repo: String = "demo-app") = send("$repo/deploy/targets/$target/check")

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private suspend fun code(r: HttpResponse) = AppJson.parseToJsonElement(r.bodyAsText()).jsonObject["error"]!!.jsonObject.str("code")

    private val v1 = "targets:\n  kiosk:\n    host: kiosk.example.net\n    user: deploy\n    hostKey: ${HostKeys.ed25519()}\n" +
        "  lab:\n    host: badkey.example.net\n    port: 2200\n    user: lab\n    hostKey: ${HostKeys.ed25519(2)}\n    description: Test bench\n"
    private val v2 = v1 + "\n"

    @Test
    fun `states, approval, change, invalid and revoke like the sandbox's`() = testApplication {
        val w = world()
        application { agernModule(w.services) }
        val alice = login("alice", "pw-a")       // can see demo-app and private-lab, not an admin
        val root = login("root", "pw-r")         // admin, cannot see private-lab

        alice.status().let {
            assertEquals("none", it.str("state"))
            assertEquals(".agern/deploy.yml", it.str("path"))
            assertEquals("main", it.str("branch"))
            assertEquals("true", it.str("enabled"))
            assertEquals("ssh", it.str("mode"))
            assertEquals(JsonNull, it["approval"]); assertEquals(JsonNull, it["key"])
            assertTrue(it["targets"]!!.jsonArray.isEmpty())
        }
        assertEquals(404, root.approve("0".repeat(64)).status.value)

        w.deployFile(v1)
        val sha1 = DeployDefinitions.sha256(v1)
        alice.status().let {
            assertEquals("pending", it.str("state"))
            assertEquals(sha1, it.str("sha256"))
            assertEquals(v1, it.str("content"))
            val t = it["targets"]!!.jsonArray.map { e -> e.jsonObject }
            assertEquals(listOf("kiosk", "lab"), t.map { e -> e.str("name") })
            assertEquals("22", t[0].str("port")); assertEquals("ssh-ed25519", t[0].str("hostKeyType"))
            assertTrue(t[0].str("hostKeyFingerprint").startsWith("SHA256:"))
            assertEquals(JsonNull, t[0]["description"]); assertEquals("Test bench", t[1].str("description"))
            assertEquals("root", it["approvers"]!!.jsonArray.single().jsonObject.str("username"))
        }

        assertEquals(403, alice.approve(sha1).status.value)
        root.approve("0".repeat(64)).let { assertEquals(409, it.status.value); assertEquals("deploy_definition_changed", code(it)) }
        assertEquals(400, root.approve("nope").status.value)
        assertEquals(200, root.approve(sha1).status.value)
        alice.status().let {
            assertEquals("approved", it.str("state"))
            assertEquals("root", it["approval"]!!.jsonObject["approvedBy"]!!.jsonObject.str("username"))
            assertEquals(v1, it["approval"]!!.jsonObject.str("content"))
        }

        // Whitespace counts: pending again, the old approval stays for the diff.
        w.deployFile(v2)
        alice.status().let {
            assertEquals("pending", it.str("state"))
            assertEquals(sha1, it["approval"]!!.jsonObject.str("sha256"))
        }
        assertEquals(200, root.approve(DeployDefinitions.sha256(v2)).status.value)

        val bad = "targets:\n  kiosk: { host: x, user: y, hostKey: nope, port: 0 }\n"
        w.deployFile(bad)
        alice.status().let {
            assertEquals("invalid", it.str("state"))
            assertTrue(it["errors"]!!.jsonArray.any { e -> e.jsonPrimitive.content.startsWith("targets.kiosk.port") })
        }
        root.approve(DeployDefinitions.sha256(bad)).let { assertEquals(422, it.status.value); assertEquals("deploy_definition_invalid", code(it)) }

        w.deployFile(v2)
        assertEquals(403, alice.revoke().status.value)
        assertEquals(200, root.revoke().status.value)
        alice.status().let { assertEquals("pending", it.str("state")); assertEquals(JsonNull, it["approval"]) }

        // A project the user cannot see: 404 before anything else.
        for (r in listOf(root.get("/api/projects/private-lab/deploy"), root.approve(sha1, "private-lab"), root.revoke("private-lab"),
            root.send("private-lab/deploy/key"), root.send("private-lab/deploy/key/rotate"), root.check("kiosk", "private-lab"))) {
            assertEquals(404, r.status.value, r.bodyAsText())
        }
        assertNoPrivateKey(w)
    }

    @Test
    fun `admins generate and rotate the key, nobody sees the private half`() = testApplication {
        val w = world()
        application { agernModule(w.services) }
        val alice = login("alice", "pw-a")
        val root = login("root", "pw-r")

        assertEquals(403, alice.send("demo-app/deploy/key").status.value)
        root.send("demo-app/deploy/key/rotate").let { assertEquals(404, it.status.value) }
        val generated = root.send("demo-app/deploy/key")
        assertEquals(200, generated.status.value, generated.bodyAsText())
        val key1 = alice.status()["key"]!!.jsonObject
        assertTrue(key1.str("publicKey").startsWith("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5"))
        assertTrue(key1.str("publicKey").endsWith(" agern-deploy:test-org/demo-app"), key1.str("publicKey"))
        assertTrue(key1.str("fingerprint").startsWith("SHA256:"))
        assertEquals("root", key1["createdBy"]!!.jsonObject.str("username"))
        root.send("demo-app/deploy/key").let { assertEquals(409, it.status.value); assertEquals("deploy_key_exists", code(it)) }

        // Stored only encrypted, bound to the repository.
        val enc = w.services.db.queryOne("SELECT private_key_enc FROM deploy_keys WHERE repo = 'demo-app'") { it.getBytes(1) }!!
        val plain = w.services.box.decrypt(enc, "deploy-key:demo-app")
        assertTrue(plain.contains("OPENSSH PRIVATE KEY"))
        assertFalse(String(enc, Charsets.ISO_8859_1).contains("PRIVATE"))
        assertTrue(runCatching { w.services.box.decrypt(enc, "deploy-key:other") }.isFailure)

        assertEquals(403, alice.send("demo-app/deploy/key/rotate").status.value)
        assertEquals(200, root.send("demo-app/deploy/key/rotate").status.value)
        val key2 = alice.status()["key"]!!.jsonObject
        assertNotEquals(key1.str("fingerprint"), key2.str("fingerprint"))
        assertNotEquals(key1.str("publicKey"), key2.str("publicKey"))
        assertNoPrivateKey(w, plain)
    }

    @Test
    fun `check reports each stage, runs nothing and needs an approved file and a key`() = testApplication {
        val w = world()
        application { agernModule(w.services) }
        val alice = login("alice", "pw-a")
        val root = login("root", "pw-r")
        w.deployFile(v1)
        root.check("kiosk").let { assertEquals(409, it.status.value); assertEquals("deploy_not_approved", code(it)) }
        root.approve(DeployDefinitions.sha256(v1))
        root.check("kiosk").let { assertEquals(409, it.status.value); assertEquals("deploy_key_missing", code(it)) }
        root.send("demo-app/deploy/key")
        assertEquals(403, alice.check("kiosk").status.value)
        assertEquals(404, root.check("nope").status.value)

        val t = w.deployTransport
        t.onCheck = { ep ->
            when (ep.host) {
                "badkey.example.net" -> throw DeployException("host_key", "Host key of lab does not match the pinned key (presented SHA256:x)")
            }
        }
        fun body(r: String) = AppJson.parseToJsonElement(r).jsonObject
        root.check("kiosk").let {
            assertEquals(200, it.status.value)
            val b = body(it.bodyAsText())
            assertEquals("true", b.str("ok")); assertEquals("ok", b.str("stage")); assertEquals("kiosk", b.str("target"))
        }
        root.check("lab").let {
            val b = body(it.bodyAsText())
            assertEquals("false", b.str("ok")); assertEquals("host_key", b.str("stage"))
            assertTrue(b.str("message").contains("does not match"))
        }
        t.onCheck = { throw DeployException("auth", "deploy@kiosk refused the project's deploy key") }
        assertEquals("auth", body(root.check("kiosk").bodyAsText()).str("stage"))
        t.onCheck = { throw DeployException("connect", "kiosk.example.net:22: Connection refused") }
        assertEquals("connect", body(root.check("kiosk").bodyAsText()).str("stage"))
        assertEquals(listOf("check kiosk", "check lab", "check kiosk", "check kiosk"), t.calls)

        // A busy target answers 409.
        val gate = java.util.concurrent.CountDownLatch(1)
        t.onCheck = { gate.await() }
        kotlinx.coroutines.coroutineScope {
            val first = async { root.check("kiosk") }
            kotlinx.coroutines.withTimeout(5_000) { while (t.calls.size < 5) kotlinx.coroutines.delay(10) }
            root.check("kiosk").let { assertEquals(409, it.status.value); assertEquals("deploy_busy", code(it)) }
            gate.countDown()
            assertEquals(200, first.await().status.value)
        }
        assertNoPrivateKey(w)
    }

    @Test
    fun `switched off, the status says so and check answers 409`() = testApplication {
        val w = world { it.copy(deploy = DeployConfig(mode = "off")) }
        application { agernModule(w.services) }
        val root = login("root", "pw-r")
        w.deployFile(v1)
        root.status().let { assertEquals("false", it.str("enabled")); assertEquals("off", it.str("mode")) }
        assertEquals(200, root.approve(DeployDefinitions.sha256(v1)).status.value)
        assertEquals(200, root.send("demo-app/deploy/key").status.value)
        root.check("kiosk").let { assertEquals(409, it.status.value); assertEquals("deploy_disabled", code(it)) }
    }

    /** No response of the test contains private key material ([plain]: the decrypted key, when known). */
    private fun assertNoPrivateKey(w: TestWorld, plain: String? = null) {
        val keyBody = plain?.lines()?.filter { it.isNotBlank() && !it.startsWith("-----") }.orEmpty()
        for (b in bodies) {
            assertFalse(b.contains("PRIVATE KEY"), b)
            assertFalse(b.contains("private", ignoreCase = true) && b.contains("privateKey"), b)
            for (line in keyBody) assertFalse(b.contains(line), "private key line in a response")
        }
        assertTrue(w.deployTransport.calls.none { it.contains("PRIVATE") })
    }
}
