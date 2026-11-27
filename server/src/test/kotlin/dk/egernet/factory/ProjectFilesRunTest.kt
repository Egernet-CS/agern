package dk.egernet.factory

import dk.egernet.factory.files.ProjectFiles
import dk.egernet.factory.git.NewBlob
import dk.egernet.factory.sandbox.SandboxDefinitions
import dk.egernet.factory.sandbox.SandboxManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Uploads [bytes] as project file [name] of [repo] (as the admin `root`), through the service. */
internal fun TestWorld.uploadProjectFile(repo: String, name: String, bytes: ByteArray): ProjectFileDto {
    val root = user("root", admin = true)
    val pf = services.projectFiles
    // root cannot see private repositories of the fake GitHub; tests use visible ones.
    val (u, _) = pf.createUpload(root, repo, name, bytes.size.toLong(), null, null)
    pf.append(root, repo, UUID.fromString(u.id), 0, bytes.inputStream())
    return pf.complete(root, repo, UUID.fromString(u.id), null)
}

/** Project files in runs and chats: snapshots, environment, prompts, sandbox mounts and the push guard. */
class ProjectFilesRunTest {
    private val worlds = CopyOnWriteArrayList<TestWorld>()
    @AfterTest fun tearDown() = worlds.forEach { it.close() }


    /** Every stage succeeds: a spec, a build, PASS from every reviewer and APPROVE. */
    private fun answer(p: FakeProcess) {
        p.init()
        if (p.spec.jsonSchema != null) {
            val verdict = if (p.spec.kind == "approver") "APPROVE" else "PASS"
            p.result(null, buildJsonObject { put("verdict", verdict); put("summary", "ok"); put("findings", buildJsonArray {}) })
        } else p.result(if (p.spec.kind == "spec-writer") "## Spec\nDo it." else "Built it.")
        p.exit(0)
    }

    private fun world(sandbox: Boolean = false) = TestWorld(twoFactor = false, sandbox = sandbox).also {
        it.launcher.stage = ::answer
        worlds += it
    }

    private fun mode(p: Path) = PosixFilePermissions.toString(Files.getPosixFilePermissions(p))

    private fun start(w: TestWorld, issue: Int = 1) = runBlocking { w.services.orchestrator.start(w.user("alice"), "demo-app", issue, null) }

    private fun systemTexts(w: TestWorld, runId: UUID, role: String) = w.services.runs.stages(runId).filter { it.role == role && it.sessionId != null }
        .flatMap { w.services.events.list(it.sessionId!!, 0, 1000) }.filter { it.type == "system" }
        .map { (it.payload as JsonObject)["text"]!!.jsonPrimitive.content }

    @Test
    fun `stages get the run's read-only snapshot, which a replacement does not change`() {
        val w = world()
        val v1 = "sdk version 1".toByteArray()
        val v2 = "sdk version 2, replaced".toByteArray()
        w.uploadProjectFile("demo-app", "vendor/sdk.zip", v1)
        val seen = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<Throwable>()
        w.launcher.stage = { p ->
            val dir = p.spec.extraEnv[ProjectFiles.ENV]
            seen += "${p.spec.kind}=$dir"
            if (p.spec.kind == "builder") try {
                val f = Path.of(dir!!).resolve("vendor/sdk.zip")
                assertContentEquals(v1, Files.readAllBytes(f))
                assertEquals("r--r--r--", mode(f))
                assertEquals("r-xr-xr-x", mode(Path.of(dir)))
                assertEquals("r-xr-xr-x", mode(Path.of(dir).resolve("vendor")))
                // Replaced while the run works: its snapshot keeps the old bytes, `current` has the new ones.
                w.uploadProjectFile("demo-app", "vendor/sdk.zip", v2)
                assertContentEquals(v1, Files.readAllBytes(f))
                assertContentEquals(v2, Files.readAllBytes(w.services.projectFiles.root.resolve("demo-app/current/vendor/sdk.zip")))
            } catch (e: Throwable) {
                failures += e
            }
            answer(p)
        }
        val run = start(w)
        val done = w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        failures.firstOrNull()?.let { throw it }
        assertEquals(RunStatus.MERGED, done.status, done.error)
        val snapshot = w.services.projectFiles.snapshotDir("demo-app", run.id)
        val roles = listOf("spec-writer", "builder", "security", "ux", "ui-design", "code", "approver")
        assertEquals(roles.toSet(), seen.map { it.substringBefore('=') }.toSet())
        assertTrue(seen.all { it.substringAfter('=') == snapshot.toString() }, seen.toString())

        // What the run recorded; the snapshot is removed with the worktree.
        val dto = w.services.runs.toDto(w.services.runs.get(run.id)!!).projectFiles!!
        assertEquals("demo-app", dto.repo)
        assertEquals(listOf(RunProjectFileDto("vendor/sdk.zip", v1.size.toLong(), sha256Hex(v1), 1)), dto.files)
        assertFalse(Files.exists(snapshot))

        // Prompts: the paragraph for every role, the line only with files.
        val builder = w.launcher.byRole("builder").single().spec
        assertTrue(builder.appendSystemPrompt!!.contains("## Project files"))
        assertTrue(builder.appendSystemPrompt!!.contains("factory_list_project_files"))
        assertTrue(builder.prompt!!.contains("Project files: read-only at `$snapshot`"), builder.prompt)
        assertTrue(builder.prompt!!.contains("vendor/sdk.zip (13 B, sha256 ${sha256Hex(v1).take(12)})"), builder.prompt)
        assertFalse(builder.prompt!!.contains("in the sandbox)"))
        assertTrue(w.launcher.byRole("security").single().spec.prompt!!.contains("Project files: read-only"))
        assertTrue(w.launcher.byRole("approver").single().spec.appendSystemPrompt!!.contains("## Project files"))

        // A second run sees the replacement, marked as changed since the previous run.
        val second = start(w, 2)
        w.awaitRun(second.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        val f2 = w.services.runs.toDto(w.services.runs.get(second.id)!!).projectFiles!!.files.single()
        assertEquals(sha256Hex(v2), f2.sha256)
        assertEquals(2, f2.version)
        assertEquals(sha256Hex(v1), f2.previousSha256)
    }

    @Test
    fun `a run without files gets an empty snapshot and no prompt line, a chat gets the current files`() {
        val w = world()
        val run = start(w)
        val done = w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        val builder = w.launcher.byRole("builder").single().spec
        assertFalse(builder.prompt!!.contains("Project files:"))
        assertTrue(builder.appendSystemPrompt!!.contains("## Project files"))
        assertNotNull(builder.extraEnv[ProjectFiles.ENV])
        assertEquals(RunProjectFilesDto("demo-app", emptyList()), w.services.runs.toDto(done).projectFiles)

        w.uploadProjectFile("demo-app", "data/set.csv", "a,b\n1,2\n".toByteArray())
        val alice = w.user("alice")
        val chat = w.services.agents.createChat(alice, "demo-app", null)
        runBlocking { w.services.agents.sendInput(alice, w.services.sessions.get(chat.id)!!, "hello", emptyList()) }
        val deadline = System.currentTimeMillis() + 10_000
        while (w.launcher.launched.none { it.spec.interactive } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val spec = w.launcher.launched.first { it.spec.interactive }.spec
        val dir = Path.of(spec.extraEnv[ProjectFiles.ENV]!!)
        assertEquals(w.services.projectFiles.root.resolve("demo-app/current"), dir)
        assertEquals("a,b\n1,2\n", Files.readString(dir.resolve("data/set.csv")))
        assertTrue(spec.appendSystemPrompt!!.contains("## Project files"))
    }

    @Test
    fun `the MCP tool lists the run's snapshot for stages and the current files for chats, never for personal tokens`() {
        val w = world()
        val bytes = "JAR-CONTENT-BYTES".toByteArray()
        w.uploadProjectFile("demo-app", "lib.jar", bytes)
        val root = w.user("root", admin = true)
        w.services.projectFiles.update(root, "demo-app", UUID.fromString(w.services.projectFiles.files("demo-app").single().id.toString()), "from vendor", null, null)
        val run = start(w)
        w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        val stage = AppJson.parseToJsonElement(w.services.orchestrator.projectFilesTool(run.id, "demo-app")).jsonObject
        assertEquals(w.services.projectFiles.snapshotDir("demo-app", run.id).toString(), stage["path"]!!.jsonPrimitive.content)
        assertEquals("true", stage["readOnly"]!!.jsonPrimitive.content)
        val f = stage["files"]!!.jsonArray.single().jsonObject
        assertEquals("lib.jar", f["name"]!!.jsonPrimitive.content)
        assertEquals(sha256Hex(bytes), f["sha256"]!!.jsonPrimitive.content)
        assertEquals("from vendor", f["note"]!!.jsonPrimitive.content)
        assertNull(stage["sandboxPath"])
        assertFalse(stage.toString().contains("JAR-CONTENT-BYTES"))
        val chat = AppJson.parseToJsonElement(w.services.orchestrator.projectFilesTool(null, "demo-app")).jsonObject
        assertEquals(w.services.projectFiles.root.resolve("demo-app/current").toString(), chat["path"]!!.jsonPrimitive.content)
        assertTrue(dk.egernet.factory.mcp.McpRegistry.CHAT_TOOLS.contains("factory_list_project_files"))
        assertTrue(dk.egernet.factory.mcp.McpRegistry.STAGE_TOOLS.contains("factory_list_project_files"))
        assertFalse(dk.egernet.factory.mcp.McpRegistry.PERSONAL_TOOLS.contains("factory_list_project_files"))
    }

    // ------------------------------------------------------------ sandboxes

    private val definition = """
        image: docker.io/library/eclipse-temurin:21-jdk
        env: { CI: "true", AGERN_PROJECT_FILES: "/elsewhere" }
        services:
          postgres:
            image: docker.io/library/postgres:17
        platforms:
          jdk17:
            image: docker.io/library/eclipse-temurin:17-jdk
        files: [ vendor/sdk.zip ]
    """.trimIndent() + "\n"

    private fun token(p: FakeProcess): String = AppJson.parseToJsonElement(p.spec.mcpConfigJson).jsonObject["mcpServers"]!!.jsonObject["factory"]!!
        .jsonObject["headers"]!!.jsonObject["Authorization"]!!.jsonPrimitive.content.removePrefix("Bearer ")

    private fun call(w: TestWorld, p: FakeProcess, tool: String, args: JsonObject = JsonObject(emptyMap())): String {
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
            putJsonObject("params") { put("name", tool); put("arguments", args) }
        }
        val res = AppJson.parseToJsonElement(runBlocking { w.services.mcp.handle(token(p), body.toString()) }.second!!).jsonObject["result"]!!.jsonObject
        return res["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
    }

    @Test
    fun `an approved sandbox mounts the snapshot read-only in every command container, not in services`() {
        val w = world(sandbox = true)
        w.sandboxFile(definition)
        runBlocking { w.services.sandboxes.approve(w.user("root", admin = true), "demo-app", SandboxDefinitions.sha256(definition)) }
        w.uploadProjectFile("demo-app", "vendor/sdk.zip", "zip".toByteArray())
        val infos = CopyOnWriteArrayList<String>()
        w.launcher.stage = { p ->
            when (p.spec.kind) {
                "builder" -> {
                    infos += call(w, p, "factory_sandbox_info")
                    call(w, p, "factory_sandbox_exec", buildJsonObject { put("command", "ls /project-files") })
                    call(w, p, "factory_sandbox_exec", buildJsonObject { put("command", "true"); put("platform", "jdk17") })
                    infos += call(w, p, "factory_list_project_files")
                }
                "ux" -> {
                    infos += call(w, p, "factory_sandbox_info")
                    call(w, p, "factory_sandbox_exec", buildJsonObject { put("command", "true") })
                }
            }
            answer(p)
        }
        val run = start(w)
        val done = w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN, 20_000)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        val snapshot = w.services.projectFiles.snapshotDir("demo-app", run.id).toAbsolutePath().normalize().toString()
        val started = w.sandboxRuntime.started
        val commandContainers = started.filter {
            it.name == SandboxManager.containerName(run.id) || it.name == SandboxManager.platformContainerName(run.id, "jdk17") ||
                it.name == SandboxManager.reviewContainerName(run.id)
        }
        assertEquals(3, commandContainers.size, started.map { it.name }.toString())
        for (c in commandContainers) {
            assertTrue(c.mounts.contains(dk.egernet.factory.sandbox.SandboxMount(snapshot, "/project-files", volume = false, readOnly = true)), c.toString())
            assertEquals("/project-files", c.env[ProjectFiles.ENV])
            assertEquals("true", c.env["CI"])
        }
        val services = started.filter { it.name.endsWith("-postgres") }
        assertTrue(services.isNotEmpty())
        assertTrue(services.none { s -> s.mounts.any { it.target == "/project-files" } || ProjectFiles.ENV in s.env })

        val info = AppJson.parseToJsonElement(infos[0]).jsonObject["projectFiles"]!!.jsonObject
        assertEquals("/project-files", info["path"]!!.jsonPrimitive.content)
        assertEquals("vendor/sdk.zip", info["files"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("/project-files", AppJson.parseToJsonElement(infos[1]).jsonObject["sandboxPath"]!!.jsonPrimitive.content)
        assertNotNull(AppJson.parseToJsonElement(infos[2]).jsonObject["projectFiles"])
        assertTrue(w.services.runs.toDto(done).projectFiles!!.files.single().required)
        assertTrue(w.launcher.byRole("builder").single().spec.prompt!!.contains("(`/project-files` in the sandbox)"))
    }

    @Test
    fun `an approved definition naming a file that is not uploaded fails the run before the spec-writer`() {
        val w = world(sandbox = true)
        w.sandboxFile(definition)
        runBlocking { w.services.sandboxes.approve(w.user("root", admin = true), "demo-app", SandboxDefinitions.sha256(definition)) }
        val run = start(w)
        val done = w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.FAILED, done.status)
        assertEquals(
            ".agern/sandbox.yml needs project file(s) `vendor/sdk.zip` that are not uploaded. Upload them on the project's Files page and start the run again.",
            done.error,
        )
        assertTrue(w.launcher.byRole("spec-writer").isEmpty())
        val status = runBlocking { w.services.sandboxes.status(w.user("alice"), "demo-app") }
        assertEquals(listOf("vendor/sdk.zip"), status.missingFiles)
        assertEquals(listOf("vendor/sdk.zip"), status.definition!!.files)
        w.uploadProjectFile("demo-app", "Vendor/SDK.zip", "zip".toByteArray())
        assertTrue(runBlocking { w.services.sandboxes.status(w.user("alice"), "demo-app") }.missingFiles.isEmpty())
    }

    // ------------------------------------------------------------ the push guard

    @Test
    fun `a branch with a committed project file is not pushed and needs a human, continue is offered`() {
        val w = world()
        val bytes = ByteArray(4096) { (it % 13).toByte() }
        w.uploadProjectFile("demo-app", "vendor/sdk.zip", bytes)
        w.git.newBlobsAnswer = listOf(NewBlob(gitBlobId(bytes), bytes.size.toLong(), "libs/renamed.zip"), NewBlob("e".repeat(40), 10, "src/a.kt"))
        val run = start(w)
        val done = w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.NEEDS_HUMAN, done.status, done.error)
        assertEquals(FailureKind.PROJECT_FILE_COMMITTED, done.failureKind)
        assertNull(done.failedStep)
        assertEquals(
            "Not pushed: the branch contains project file `vendor/sdk.zip` (sha256 `${sha256Hex(bytes).take(12)}`) committed as " +
                "`libs/renamed.zip`. Project files must not be committed. Remove it from every commit of the branch (a later commit that " +
                "deletes it is not enough), then use Retry → Continue, or talk to the agent on the run page.",
            done.error,
        )
        assertTrue(w.git.pushes.isEmpty())
        assertNull(done.prNumber)
        assertTrue(w.services.runs.stages(run.id).none { it.status in setOf("pending", "running") })
        assertTrue(w.launcher.byRole("security").isEmpty())
        val opts = w.services.orchestrator.retryOptions(done)
        assertEquals(RetryMode.CONTINUE, opts.recommended)
        assertTrue(opts.options.single { it.mode == RetryMode.CONTINUE }.available)
        assertTrue(systemTexts(w, run.id, "builder").any { it.startsWith("Not pushed: the branch contains project file") })
        // Approving cannot work (no pull request); Retry → Continue ends the run and continues from its commits.
        assertEquals(409, runCatching { runBlocking { w.services.orchestrator.approve(w.user("alice"), run.id) } }.exceptionOrNull().let { (it as ApiException).status.value })
        w.git.newBlobsAnswer = emptyList()
        val next = runBlocking { w.services.orchestrator.retry(w.user("alice"), run.id, RetryMode.CONTINUE) }
        assertEquals(run.id, next.continuesRunId)
        val old = w.services.runs.get(run.id)!!
        assertEquals(RunStatus.FAILED, old.status)
        assertTrue(old.error!!.startsWith("Not pushed: the branch contains project file"))
        val continued = w.awaitRun(next.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, continued.status, continued.error)
        assertTrue(w.launcher.byRole("builder").last().spec.prompt!!.contains("Not pushed: the branch contains project file"))
    }

    @Test
    fun `large new files only warn, once, and the push happens`() {
        val w = world()
        w.uploadProjectFile("demo-app", "vendor/sdk.zip", "small".toByteArray())
        w.git.newBlobsAnswer = listOf(NewBlob("e".repeat(40), 12L shl 20, "assets/video.mp4"))
        val run = start(w)
        val done = w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.MERGED, done.status, done.error)
        assertEquals(1, w.git.pushes.size)
        val warns = systemTexts(w, run.id, "builder").filter { it.startsWith("Large new file(s)") }
        assertEquals(listOf("Large new file(s) in this branch: `assets/video.mp4` (12.0 MiB). If one is a vendor or licensed file, it belongs in the project's Files, not in git."), warns)
    }

    @Test
    fun `a failing check fails closed as a push failure`() {
        val w = world()
        w.uploadProjectFile("demo-app", "vendor/sdk.zip", "small".toByteArray())
        w.git.newBlobsError = "fatal: bad object"
        val run = start(w)
        val done = w.awaitRun(run.id, RunStatus.TERMINAL + RunStatus.NEEDS_HUMAN)
        assertEquals(RunStatus.FAILED, done.status)
        assertEquals(FailedStep.PUSH, done.failedStep)
        assertTrue(done.error!!.startsWith("Not pushed: the project-file check could not be made (fatal: bad object)"), done.error)
        assertTrue(w.git.pushes.isEmpty())
    }
}
