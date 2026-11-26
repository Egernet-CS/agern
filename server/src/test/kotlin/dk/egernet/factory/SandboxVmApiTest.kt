package dk.egernet.factory

import dk.egernet.factory.db.SandboxVmLeaseStore
import dk.egernet.factory.deploy.DeployException
import dk.egernet.factory.deploy.DeployExecResult
import dk.egernet.factory.sandboxvm.SandboxVmDefinitions
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The sandbox VM page's endpoints over HTTP: status, approval, check, force release, lease visibility, the preview routes and the switch. */
class SandboxVmApiTest {
    private val worlds = mutableListOf<TestWorld>()
    private fun world(tweak: (Config) -> Config = { it }) = TestWorld(twoFactor = false, tweak = tweak).also { worlds += it }

    @AfterTest fun tearDown() = worlds.forEach { it.close() }

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
        val r = get("/api/projects/$repo/sandbox-vm")
        assertEquals(200, r.status.value, r.text())
        return AppJson.parseToJsonElement(r.text()).jsonObject
    }
    private suspend fun HttpClient.send(path: String, body: String? = null): HttpResponse = post("/api/$path") {
        header("X-Factory-Request", "1")
        if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
    }.also { it.text() }
    private suspend fun HttpClient.remove(path: String) = delete("/api/$path") { header("X-Factory-Request", "1") }.also { it.text() }
    private suspend fun HttpClient.approve(sha: String, repo: String = "demo-app") = send("projects/$repo/sandbox-vm/approval", """{"sha256":"$sha"}""")
    private suspend fun HttpClient.check(repo: String = "demo-app") = send("projects/$repo/sandbox-vm/check")

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private suspend fun HttpResponse.json() = AppJson.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun code(r: HttpResponse) = r.json()["error"]!!.jsonObject.str("code")

    private val v1 = "name: staging\nhost: vm.example.net\nuser: agern\nhostKey: ${HostKeys.ed25519()}\nurl: https://vm.example.net/\n" +
        "checkout: tar xzf archive\nready:\n  command: curl -fsS localhost/health\nreset: restore-db\n"

    @Test
    fun `states, approval, change, invalid, revoke and admin-only rules`() = testApplication {
        val w = world()
        application { agernModule(w.services) }
        val alice = login("alice", "pw-a")
        val root = login("root", "pw-r")

        alice.status().let {
            assertEquals("none", it.str("state")); assertEquals(".agern/sandbox-vm.yml", it.str("path")); assertEquals("ssh", it.str("mode"))
            assertEquals(JsonNull, it["vm"]); assertEquals(JsonNull, it["lease"]); assertEquals(JsonNull, it["key"])
        }
        w.sandboxVmFile(v1)
        val sha1 = SandboxVmDefinitions.sha256(v1)
        alice.status().let {
            assertEquals("pending", it.str("state")); assertEquals(sha1, it.str("sha256"))
            val vm = it["vm"]!!.jsonObject
            assertEquals("staging", vm.str("name")); assertEquals("22", vm.str("port")); assertEquals("ssh-ed25519", vm.str("hostKeyType"))
            assertEquals("curl -fsS localhost/health", vm["ready"]!!.jsonObject.str("command")); assertEquals(JsonNull, vm["setup"])
        }
        assertEquals(403, alice.approve(sha1).status.value)
        root.approve("0".repeat(64)).let { assertEquals(409, it.status.value); assertEquals("sandbox_vm_definition_changed", code(it)) }
        assertEquals(200, root.approve(sha1).status.value)
        assertEquals("approved", alice.status().str("state"))
        w.sandboxVmFile(v1 + "\n")
        alice.status().let { assertEquals("pending", it.str("state")); assertEquals(sha1, it["approval"]!!.jsonObject.str("sha256")) }
        val bad = v1.replace("https://vm.example.net/", "javascript:alert(1)")
        w.sandboxVmFile(bad)
        alice.status().let { s ->
            assertEquals("invalid", s.str("state"))
            assertTrue(s["errors"]!!.jsonArray.any { it.jsonPrimitive.content.startsWith("url:") })
        }
        root.approve(SandboxVmDefinitions.sha256(bad)).let { assertEquals(422, it.status.value); assertEquals("sandbox_vm_definition_invalid", code(it)) }
        assertEquals(403, alice.remove("projects/demo-app/sandbox-vm/approval").status.value)
        assertEquals(200, root.remove("projects/demo-app/sandbox-vm/approval").status.value)
        assertEquals(JsonNull, alice.status()["approval"])
        assertEquals(403, alice.check().status.value)
        assertEquals(403, alice.remove("projects/demo-app/sandbox-vm/lease").status.value)
        // A project the admin cannot see: 404.
        assertEquals(404, root.get("/api/projects/private-lab/sandbox-vm").status.value)
    }

    @Test
    fun `check stages, force release and lease visibility`() = testApplication {
        val w = world()
        application { agernModule(w.services) }
        val alice = login("alice", "pw-a")
        val bob = login("bob", "pw-b")
        val root = login("root", "pw-r")
        w.sandboxVmFile(v1)
        assertEquals(200, root.approve(SandboxVmDefinitions.sha256(v1)).status.value)
        root.check().let { assertEquals(409, it.status.value); assertEquals("sandbox_vm_key_missing", code(it)) }
        assertEquals(200, root.send("projects/demo-app/deploy/key").status.value)
        alice.status().let { assertTrue(it["key"]!!.jsonObject.str("publicKey").startsWith("ssh-ed25519 ")) }

        root.check().json().let { assertEquals("true", it.str("ok")); assertEquals("ok", it.str("stage")) }
        w.vmTransport.onExec = { _, _, _ -> DeployExecResult(7, null, "connection refused\n", false, false, 3) }
        root.check().json().let {
            assertEquals("false", it.str("ok")); assertEquals("ready", it.str("stage")); assertEquals("7", it.str("exitCode"))
            assertTrue(it.str("output").contains("connection refused"))
        }
        assertTrue(w.vmTransport.calls.last().startsWith("exec staging export AGERN_SHA='' AGERN_RUN_ID=''"))
        for (stage in listOf("connect", "host_key", "auth")) {
            w.vmTransport.onCheck = { throw DeployException(stage, "fails at $stage") }
            root.check().json().let { assertEquals("false", it.str("ok")); assertEquals(stage, it.str("stage")) }
        }
        w.vmTransport.onCheck = {}
        w.vmTransport.onExec = { _, cmd, _ -> DeployExecResult(0, null, "ran: $cmd\n", false, false, 1) }

        assertEquals(404, root.remove("projects/demo-app/sandbox-vm/lease").status.value)
        // A lease held by a run in private-lab (which bob cannot see) on the same VM.
        val run = runBlocking {
            w.launcher.stage = { p ->
                p.init()
                when (p.spec.kind) {
                    "spec-writer" -> p.result("## Spec")
                    "builder" -> p.result("Built.")
                    "approver" -> p.result(null, buildJsonObject { put("verdict", "ESCALATE"); put("summary", "?") })
                    else -> p.result(null, buildJsonObject { put("verdict", "PASS"); put("summary", "ok"); put("findings", buildJsonArray { }) })
                }
                p.exit(0)
            }
            w.services.orchestrator.start(w.user("alice"), "private-lab", 1, null)
        }
        w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        SandboxVmLeaseStore(w.services.db).acquire("vm.example.net:22", "demo-app", run.id, "build", SandboxVmDefinitions.sha256(v1), null, null)
        alice.status()["lease"]!!.jsonObject.let {
            assertEquals("true", it.str("visible")); assertEquals(run.id.toString(), it.str("runId")); assertEquals("private-lab", it.str("repo"))
        }
        bob.status()["lease"]!!.jsonObject.let {
            assertEquals("false", it.str("visible")); assertEquals(JsonNull, it["runId"]); assertEquals(JsonNull, it["repo"]); assertEquals("held", it.str("state"))
        }
        assertEquals(200, root.remove("projects/demo-app/sandbox-vm/lease").status.value)
        val deadline = System.currentTimeMillis() + 10_000
        while (SandboxVmLeaseStore(w.services.db).all().isNotEmpty()) { check(System.currentTimeMillis() < deadline); Thread.sleep(20) }
        assertTrue(w.vmTransport.calls.any { it.endsWith("restore-db") }, "reset ran")

        // The preview routes: 404 for a run the caller cannot see; the owner's preview of a run whose VM is not approved: 409.
        assertEquals(404, bob.send("runs/${run.id}/sandbox-vm/preview").status.value)
        alice.send("runs/${run.id}/sandbox-vm/preview").let { assertEquals(409, it.status.value); assertEquals("sandbox_vm_not_approved", code(it)) }
        assertEquals(404, alice.remove("runs/${run.id}/sandbox-vm/preview").status.value)
        for (b in bodies) assertFalse(b.contains("PRIVATE KEY"), b)
    }

    @Test
    fun `switched off, the page still shows the file and preview and check answer 409`() = testApplication {
        val w = world { it.copy(sandboxVm = SandboxVmConfig(mode = "off")) }
        application { agernModule(w.services) }
        val root = login("root", "pw-r")
        w.sandboxVmFile(v1)
        root.status().let { assertEquals("false", it.str("enabled")); assertEquals("off", it.str("mode")); assertEquals("pending", it.str("state")) }
        assertEquals(200, root.approve(SandboxVmDefinitions.sha256(v1)).status.value)
        assertEquals(200, root.send("projects/demo-app/deploy/key").status.value)
        root.check().let { assertEquals(409, it.status.value); assertEquals("sandbox_vm_disabled", code(it)) }
    }
}
