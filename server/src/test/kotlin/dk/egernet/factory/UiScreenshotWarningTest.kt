package dk.egernet.factory

import dk.egernet.factory.pipeline.Orchestrator
import dk.egernet.factory.pipeline.UiChanges
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The warning "UI change reviewed without screenshots" (docs/ARCHITECTURE.md §6 "Screenshots"). */
class UiScreenshotWarningTest {
    private val worlds = CopyOnWriteArrayList<TestWorld>()
    private fun world(tweak: (Config) -> Config = { it }) = TestWorld(twoFactor = false, tweak = tweak).also { worlds += it }

    @AfterTest fun tearDown() = worlds.forEach { it.close() }

    // ---------------------------------------------------------------- the heuristic

    @Test
    fun `components, styles, markup and templates count as UI, while tests, docs and server code do not`() {
        val ui = listOf(
            "web/src/pages/Run.tsx", "src/App.jsx", "app/components/Card.vue", "src/routes/+page.svelte", "web/src/styles/app.css",
            "assets/site.SCSS", "index.html", "templates/mail.hbs", "app/views/users/show.html.erb", "lib/web/live/page.heex",
            "web/src/components/format.ts", "src/pages/api.js", "server/src/main/resources/templates/report.kte",
        )
        val notUi = listOf(
            "server/src/main/kotlin/dk/egernet/factory/App.kt", "README.md", "docs/ARCHITECTURE.md", "web/src/lib/format.ts",
            "web/e2e/04-runs.spec.ts", "web/src/pages/Run.test.tsx", "src/__tests__/Card.tsx", "test/fixtures/page.html",
            "web/src/components/Card.stories.tsx", "package.json", "templates/Generator.kt", "AGERN_FAKE.md", "assets/fake-icon.png",
        )
        for (p in ui) assertTrue(UiChanges.isUiFileByDefault(p), p)
        for (p in notUi) assertFalse(UiChanges.isUiFileByDefault(p), p)
        assertEquals(listOf("web/src/App.tsx"), UiChanges.uiFiles(listOf("server/A.kt", "web/src/App.tsx", "README.md")))
    }

    @Test
    fun `a project's ui-files replaces or trims the defaults, and one without rules turns the check off`() {
        val rules = UiChanges.Rules.parse(
            """
            # Our UI lives in Kotlin templates and one Python package.
            *.kte
            frontend/**
            !frontend/**/*.md
            """.trimIndent(),
        )
        assertTrue(rules.isUi("server/src/main/jte/page.kte"))
        assertTrue(rules.isUi("page.kte"))
        assertTrue(rules.isUi("frontend/app/main.py"))
        assertFalse(rules.isUi("frontend/README.md"))
        assertFalse(rules.isUi("web/src/App.tsx"), "the defaults no longer apply")
        assertFalse(rules.off)

        val off = UiChanges.Rules.parse("# no UI in this project\n")
        assertTrue(off.off)
        assertFalse(off.isUi("web/src/App.tsx"))

        assertTrue(UiChanges.Rules.parse(null).isUi("web/src/App.tsx"), "no file: the defaults")
        val minus = UiChanges.Rules.parse("!web/src/legacy/\n")
        assertTrue(minus.isUi("web/src/App.tsx"), "only exclusions: the defaults minus those")
        assertFalse(minus.isUi("web/src/legacy/Old.tsx"))
        assertFalse(minus.off)
    }

    @Test
    fun `globs keep single stars inside a segment`() {
        assertTrue(UiChanges.glob("web/*.css").matches("web/a.css"))
        assertFalse(UiChanges.glob("web/*.css").matches("web/x/a.css"))
        assertTrue(UiChanges.glob("web/**/*.css").matches("web/a.css"))
        assertTrue(UiChanges.glob("web/**/*.css").matches("web/x/y/a.css"))
        assertTrue(UiChanges.glob("*.css").matches("deep/down/a.css"))
        assertTrue(UiChanges.glob("ui/").matches("ui/a/b.txt"))
        assertFalse(UiChanges.glob("a?c.html").matches("a/c.html"))
        assertFalse(UiChanges.glob("app.(css)").matches("appXcss"), "regex characters are literal")
    }

    @Test
    fun `the warning text names the files and the comment the run page`() {
        val t = UiChanges.warningText(listOf("a.tsx", "b.css", "c.html", "d.vue", "e.svelte"))
        assertTrue(t.startsWith("UI change reviewed without screenshots: the change touches 5 UI files (a.tsx, b.css, c.html, and 2 more)"), t)
        assertTrue(UiChanges.warningText(listOf("a.tsx")).contains("touches 1 UI file (a.tsx)"))

        val cfg = testConfig(Files.createTempDirectory("agern-ui")).copy(publicUrl = "https://agern.example.com")
        val c = Orchestrator.uiWarningComment(cfg, "demo-app", java.util.UUID(0, 1), 1, (1..12).map { "web/src/F$it.tsx" })
        assertTrue(c.startsWith("**Agern: warning — UI change reviewed without screenshots** (fix round 1). This change touches 12 UI files"), c)
        assertTrue(c.contains("https://agern.example.com/p/demo-app/runs/00000000-0000-0000-0000-000000000001"), c)
        assertTrue(c.contains("- `web/src/F10.tsx`\n- … and 2 more"), c)
        assertFalse(c.contains("F11"), c)
    }

    @Test
    fun `changed files come from git relative to the merge base`() {
        val tmp = Files.createTempDirectory("agern-ui-git")
        val repo = tmp.resolve("repo").also { Files.createDirectories(it) }
        fun git(vararg args: String) {
            val p = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", *args))
                .directory(repo.toFile()).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0, out)
        }
        fun write(path: String, text: String) = repo.resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }
        git("init", "-q", "-b", "main")
        write("README.md", "hi\n"); write("web/old.css", "a{}\n")
        git("add", "-A"); git("commit", "-q", "-m", "base")
        git("checkout", "-q", "-b", "feature")
        write("web/src/New Page.tsx", "export {}\n"); Files.delete(repo.resolve("web/old.css"))
        git("add", "-A"); git("commit", "-q", "-m", "ui")
        // The base moves on meanwhile: its own changes are not part of the branch's diff.
        git("checkout", "-q", "main"); write("main-only.html", "<p>\n"); git("add", "-A"); git("commit", "-q", "-m", "main")
        git("checkout", "-q", "feature")

        val ops = dk.egernet.factory.git.CliGitOps(System.getenv("PATH") ?: "/usr/bin:/bin", "https://github.invalid/", localOnly = true)
        assertEquals(setOf("web/src/New Page.tsx", "web/old.css"), ops.changedFiles(repo, "main", tmp.resolve("home")).toSet())
    }

    // ---------------------------------------------------------------- in the pipeline

    private fun token(p: FakeProcess): String = AppJson.parseToJsonElement(p.spec.mcpConfigJson).jsonObject["mcpServers"]!!.jsonObject["factory"]!!
        .jsonObject["headers"]!!.jsonObject["Authorization"]!!.jsonPrimitive.content.removePrefix("Bearer ")

    private fun attach(w: TestWorld, p: FakeProcess, caption: String) {
        Files.write(p.spec.workDir.resolve("shot.png"), png(8, 8))
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
            putJsonObject("params") {
                put("name", "factory_attach_screenshot")
                putJsonObject("arguments") { put("path", "shot.png"); put("caption", caption) }
            }
        }
        val res = AppJson.parseToJsonElement(runBlocking { w.services.mcp.handle(token(p), body.toString()) }.second!!).jsonObject
        assertFalse(res["result"]!!.jsonObject["isError"]?.jsonPrimitive?.booleanOrNull == true, res.toString())
    }

    /** Every stage passes unless [changes]; [reviewer] runs inside each reviewer stage (role, round, process). */
    private fun script(w: TestWorld, changes: (String, Int) -> Boolean = { _, _ -> false }, reviewer: (String, Int, FakeProcess) -> Unit = { _, _, _ -> }) {
        w.launcher.stage = { p ->
            when (val role = p.spec.kind) {
                "spec-writer" -> { p.init(); p.result("## Spec\nDo it."); p.exit(0) }
                "builder" -> { p.init(); p.result("Built it."); p.exit(0) }
                "approver" -> { p.init(); p.result(null, buildJsonObject { put("verdict", "APPROVE"); put("summary", "ok") }); p.exit(0) }
                else -> {
                    val round = Regex("after fix round (\\d+)").find(p.spec.prompt ?: "")?.groupValues?.get(1)?.toInt() ?: 0
                    reviewer(role, round, p)
                    p.init()
                    val c = changes(role, round)
                    p.result(null, buildJsonObject {
                        put("verdict", if (c) "CHANGES" else "PASS"); put("summary", "$role: I only read the diff")
                        put("findings", buildJsonArray {
                            if (c) add(buildJsonObject { put("severity", "major"); put("file", "a.kt"); put("detail", "fix it") })
                        })
                    })
                    p.exit(0)
                }
            }
        }
    }

    private fun runToEnd(w: TestWorld): RunDto {
        val run = runBlocking { w.services.orchestrator.start(w.user("alice"), "demo-app", 1, null) }
        val done = w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        return w.services.runs.toDto(done)
    }

    private fun RunDto.stage(role: String, round: Int = 0) = stages.single { it.role == role && it.round == round }

    private fun prComments(w: TestWorld, run: RunDto): List<String> =
        w.services.access.client(w.services.users.byUsername("alice")!!)!!.listComments("test-org", run.repo, run.prNumber!!).map { it.body.orEmpty() }

    private fun warningComments(w: TestWorld, run: RunDto) = prComments(w, run).filter { it.startsWith("**Agern: warning") }

    @Test
    fun `a UI change reviewed without screenshots is flagged on both UI stages, the run and the pull request, and still merges`() {
        val w = world()
        w.git.changed = listOf("server/App.kt", "web/src/pages/Run.tsx", "web/src/styles/app.css")
        script(w)
        val run = runToEnd(w)

        val expected = "UI change reviewed without screenshots: the change touches 2 UI files (web/src/pages/Run.tsx, " +
            "web/src/styles/app.css), but neither the ux nor the ui-design reviewer attached a screenshot. Their summaries " +
            "should say what they tried and what failed."
        assertEquals(expected, run.stage("ux").warning)
        assertEquals(expected, run.stage("ui-design").warning)
        for (role in listOf("spec-writer", "builder", "security", "code", "approver")) assertNull(run.stage(role).warning, role)
        assertEquals(listOf(RunWarningDto(0, listOf("ux", "ui-design"), expected)), run.warnings)

        val c = warningComments(w, run).single()
        assertTrue(c.startsWith("**Agern: warning — UI change reviewed without screenshots** (build). This change touches 2 UI files"), c)
        assertTrue(c.contains("- `web/src/pages/Run.tsx`\n- `web/src/styles/app.css`"), c)
        // The UI reviewers were told where to start and which port is never theirs; the others were not.
        for (role in listOf("ux", "ui-design")) {
            val prompt = w.launcher.byRole(role).single().spec.prompt!!
            assertTrue(prompt.contains("read `.agern/preview.md` first") && prompt.contains("Agern itself listens on port ${w.cfg.port}"), prompt)
        }
        assertFalse(w.launcher.byRole("security").single().spec.prompt!!.contains("preview.md"))
        // The rest of the pipeline is untouched.
        assertEquals(RunStatus.MERGED, run.status)
        assertNull(run.error)
    }

    @Test
    fun `one screenshot from either UI reviewer is enough, per round`() {
        val w = world()
        w.git.changed = listOf("web/src/App.tsx")
        // Round 0: ui-design attaches one. Fix round 1: nobody does.
        script(w, changes = { role, round -> role == "code" && round == 0 }, reviewer = { role, round, p ->
            if (role == "ui-design" && round == 0) attach(w, p, "App, light theme")
        })
        val run = runToEnd(w)
        assertNull(run.stage("ux", 0).warning)
        assertNull(run.stage("ui-design", 0).warning)
        assertTrue(run.stage("ux", 1).warning!!.startsWith(UiChanges.WARNING_TITLE))
        assertTrue(run.stage("ui-design", 1).warning!!.startsWith(UiChanges.WARNING_TITLE))
        assertEquals(listOf(1), run.warnings.map { it.round })
        assertEquals(1, warningComments(w, run).size)
        assertTrue(warningComments(w, run).single().contains("(fix round 1)"))
    }

    @Test
    fun `no warning for a diff without UI files, an unknown diff, or a project whose ui-files turns it off`() {
        val w = world()
        w.git.changed = listOf("server/src/main/kotlin/App.kt", "docs/ARCHITECTURE.md", "web/e2e/01-auth.spec.ts")
        script(w)
        val run = runToEnd(w)
        assertTrue(run.stages.all { it.warning == null })
        assertEquals(emptyList(), run.warnings)
        assertEquals(emptyList(), warningComments(w, run))

        val w2 = world() // changedFiles answers null: nothing can be told
        script(w2)
        val run2 = runToEnd(w2)
        assertEquals(emptyList(), run2.warnings)

        val w3 = world()
        w3.git.changed = listOf("web/src/App.tsx")
        w3.git.files[UiChanges.UI_FILES] = "# this project has no UI\n"
        script(w3)
        val run3 = runToEnd(w3)
        assertEquals(emptyList(), run3.warnings)
        assertTrue(w3.git.reads.any { it.endsWith(":" + UiChanges.UI_FILES) && it.startsWith("origin/") }, "read from the base ref: ${w3.git.reads}")

        val w4 = world()
        w4.git.changed = listOf("server/src/main/jte/page.kte")
        w4.git.files[UiChanges.UI_FILES] = "*.kte\n"
        script(w4)
        assertEquals(listOf(0), runToEnd(w4).warnings.map { it.round })
    }

    // ---------------------------------------------------------------- agern doctor

    /** Runs `packaging/agern doctor` (tarball layout under a temporary prefix) with [agentPath] as the agents' PATH. */
    private fun doctor(agentPath: Path): String {
        val script = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("packaging/agern") }.first { Files.isRegularFile(it) }
        val prefix = Files.createTempDirectory("agern-doctor")
        val env = prefix.resolve("agern.env")
        Files.writeString(env, "FACTORY_AGENT_PATH=$agentPath\nFACTORY_DB_URL=jdbc:postgresql://127.0.0.1:1/none\n")
        env.toFile().setReadable(false, false); env.toFile().setReadable(true, true); env.toFile().setWritable(false, false); env.toFile().setWritable(true, true)
        // No service manager and no pid file: the doctor never contacts a running server.
        Files.writeString(prefix.resolve("agern.conf"),
            "AGERN_ENV_FILE=$env\nAGERN_DATA_DIR=${prefix.resolve("data")}\nAGERN_LOG_DIR=${prefix.resolve("log")}\nAGERN_SERVICE=none\n")
        val pb = ProcessBuilder("bash", script.toString(), "doctor").redirectErrorStream(true)
        pb.environment().apply { clear(); put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin"); put("AGERN_PREFIX", prefix.toString()); put("HOME", prefix.toString()) }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), out)
        return out
    }

    private fun executable(dir: Path, name: String) = dir.resolve(name).also {
        Files.writeString(it, "#!/bin/sh\nexit 0\n"); it.toFile().setExecutable(true)
    }

    @Test
    fun `agern doctor warns when the agents have no headless browser, and names the one they have`() {
        val none = Files.createTempDirectory("agern-path-none")
        executable(none, "git")
        val out = doctor(none)
        assertTrue(out.contains("headless browser"), out)
        assertTrue(out.contains("on $none — UI reviewers cannot take screenshots"), "looked on the agents' PATH only: $out")
        assertTrue(Regex("""warn {2}no headless browser \(chromium, chromium-browser, google-chrome, google-chrome-stable\) on \S+ — UI reviewers """ +
            """cannot take screenshots; install one: apt install chromium fonts-noto-core""").containsMatchIn(out), out)

        val some = Files.createTempDirectory("agern-path-some")
        val browser = executable(some, "chromium-browser")
        val out2 = doctor(some)
        assertTrue(out2.contains("ok    headless browser for the UI reviewers: $browser"), out2)
        assertFalse(out2.contains("no headless browser"), out2)
    }
}
