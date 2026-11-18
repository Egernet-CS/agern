package dk.egernet.factory

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Personal model and fix-round choices: the owner's preference, else the admin default (capped by it). */
class PreferencesTest {
    private val world = TestWorld(twoFactor = false)
    private val s = world.services
    private val alice = world.user("alice")
    private val bob = world.user("bob")

    @AfterTest fun tearDown() = world.close()

    /** Reviewers answer [verdict]; everything else succeeds. */
    private fun script(verdict: String = "PASS") {
        world.launcher.stage = { p ->
            p.init()
            when (p.spec.kind) {
                "spec-writer" -> p.result("## Spec")
                "builder" -> p.result("Built.")
                "approver" -> p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") })
                else -> p.result(null, buildJsonObject {
                    put("verdict", verdict); put("summary", verdict)
                    put("findings", buildJsonArray { if (verdict == "CHANGES") addJsonObject { put("severity", "major"); put("file", "a"); put("detail", "x") } })
                })
            }
            p.exit(0)
        }
    }

    private fun prefs(u: dk.egernet.factory.db.UserRecord, chat: String? = null, models: Map<String, String> = emptyMap(), rounds: Int? = null) =
        s.preferences.put(u.id, PreferenceOverridesDto(chat, models, rounds))

    @Test
    fun `each user's runs use their own models and fix rounds, never someone else's`() {
        script("CHANGES")
        prefs(alice, models = mapOf("builder" to "haiku", "security" to "claude-sonnet-5-5"), rounds = 1)
        val ra = runBlocking { s.orchestrator.start(alice, "demo-app", 1, null) }
        val rb = runBlocking { s.orchestrator.start(bob, "demo-app", 2, null) }
        assertEquals(1, ra.maxRounds)
        assertEquals(2, rb.maxRounds, "bob has no preference: the admin default")
        // Stages show the starting user's effective model before they start.
        val stA = s.runs.stages(ra.id).associate { it.role to it.model }
        val stB = s.runs.stages(rb.id).associate { it.role to it.model }
        assertEquals("haiku", stA["builder"])
        assertEquals("claude-sonnet-5-5", stA["security"])
        assertEquals("opus", stA["spec-writer"], "roles alice did not choose use the default")
        assertEquals("opus", stB["builder"])
        assertEquals("opus", stB["security"])

        val doneA = world.awaitRun(ra.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        val doneB = world.awaitRun(rb.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.NEEDS_HUMAN, doneA.status)
        assertEquals(1, doneA.round, "alice: one fix round")
        assertEquals(2, doneB.round, "bob: the default two")
        // The agents really ran with those models, also in the fix rounds.
        val launched = world.launcher.launched.filter { !it.spec.interactive }
        val aliceBuilders = launched.filter { it.spec.identity.username == "alice" && it.spec.kind == "builder" }.map { it.spec.model }
        val bobBuilders = launched.filter { it.spec.identity.username == "bob" && it.spec.kind == "builder" }.map { it.spec.model }
        assertEquals(listOf("haiku", "haiku"), aliceBuilders)
        assertEquals(listOf("opus", "opus", "opus"), bobBuilders)
        assertTrue(s.runs.stages(ra.id).filter { it.role == "builder" }.all { it.model == "haiku" })
    }

    @Test
    fun `the max rounds of a run do not change when the preference is edited mid-run`() {
        script("CHANGES")
        prefs(alice, rounds = 2)
        val r = runBlocking { s.orchestrator.start(alice, "demo-app", 1, null) }
        prefs(alice, rounds = 0)
        val done = world.awaitRun(r.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(2, done.maxRounds)
        assertEquals(2, done.round)
    }

    @Test
    fun `zero fix rounds - changes after the first review go straight to needs_human`() {
        script("CHANGES")
        prefs(alice, rounds = 0)
        val r = runBlocking { s.orchestrator.start(alice, "demo-app", 1, null) }
        assertEquals(0, r.maxRounds)
        val done = world.awaitRun(r.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.NEEDS_HUMAN, done.status, done.error)
        assertEquals(0, done.round)
        assertTrue(done.error!!.contains("after 0 fix round"), done.error)
        val stages = s.runs.stages(r.id)
        assertEquals(1, stages.count { it.role == "builder" }, "no fix-round builder")
        assertEquals("skipped", stages.single { it.role == "approver" }.status)
        // And with all PASS, zero rounds still merges.
        script("PASS")
        val r2 = runBlocking { s.orchestrator.start(alice, "demo-app", 2, null) }
        assertEquals(RunStatus.MERGED, world.awaitRun(r2.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN).status)
    }

    @Test
    fun `chat model - owner's preference, explicit model wins, others unaffected`() {
        prefs(alice, chat = "haiku")
        assertEquals("haiku", s.agents.createChat(alice, "demo-app", null).model)
        assertEquals("opus", s.agents.createChat(bob, "demo-app", null).model)
        assertEquals("sonnet", s.agents.createChat(alice, "demo-app", "sonnet").model)
    }

    @Test
    fun `validation, bound and clamping, sources`() {
        assertFailsWith<ApiException> { prefs(alice, rounds = 3) }.let { assertEquals("max_fix_rounds_out_of_range", it.code) }
        assertFailsWith<ApiException> { prefs(alice, models = mapOf("builder" to "rm -rf /")) }
        assertFailsWith<ApiException> { prefs(alice, models = mapOf("janitor" to "opus")) }
        assertFailsWith<ApiException> { prefs(alice, rounds = -1) }

        val d = prefs(alice, models = mapOf("ux" to "haiku"), rounds = 2)
        assertEquals("user", d.effective.pipelineModels["ux"]!!.source)
        assertEquals("default", d.effective.pipelineModels["code"]!!.source)
        assertEquals("sonnet", d.effective.pipelineModels["code"]!!.value)
        assertEquals("default", d.effective.chatModel.source)
        assertEquals(2, d.effective.maxFixRounds.max)

        // The admin lowers the bound below alice's stored choice: clamped, and reported as such.
        s.settings.put(s.settings.get().copy(maxFixRounds = 1))
        val c = s.preferences.dto(alice.id)
        assertEquals(2, c.overrides.maxFixRounds)
        assertEquals(1, c.effective.maxFixRounds.value)
        assertEquals(true, c.effective.maxFixRounds.clamped)
        script()
        assertEquals(1, runBlocking { s.orchestrator.start(alice, "demo-app", 1, null) }.maxRounds)
    }

    @Test
    fun `preferences over HTTP are per user`() = testApplication {
        application { agernModule(s) }
        suspend fun login(u: String, pw: String) = createClient { install(HttpCookies) }.also {
            it.post("/api/auth/login") { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"username":"$u","password":"$pw"}""") }
        }
        val a = login("alice", "pw-a")
        val b = login("bob", "pw-b")
        val put = a.put("/api/me/preferences") {
            header("X-Factory-Request", "1"); contentType(ContentType.Application.Json)
            setBody("""{"chatModel":"haiku","pipelineModels":{"builder":"sonnet"},"maxFixRounds":1}""")
        }
        assertEquals(200, put.status.value, put.bodyAsText())
        val over = a.put("/api/me/preferences") { header("X-Factory-Request", "1"); contentType(ContentType.Application.Json); setBody("""{"maxFixRounds":9}""") }
        assertEquals(422, over.status.value)
        assertEquals(403, a.put("/api/me/preferences") { contentType(ContentType.Application.Json); setBody("{}") }.status.value, "CSRF")
        val mine = AppJson.parseToJsonElement(a.get("/api/me/preferences").bodyAsText()).jsonObject
        assertEquals("haiku", mine["effective"]!!.jsonObject["chatModel"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        val bobs = AppJson.parseToJsonElement(b.get("/api/me/preferences").bodyAsText()).jsonObject
        assertEquals("opus", bobs["effective"]!!.jsonObject["chatModel"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("default", bobs["effective"]!!.jsonObject["chatModel"]!!.jsonObject["source"]!!.jsonPrimitive.content)
    }
}
