package dk.egernet.factory

import dk.egernet.factory.sandbox.CommandResult
import dk.egernet.factory.sandbox.CommandRunner
import dk.egernet.factory.sandbox.ContainerSpec
import dk.egernet.factory.sandbox.LabelledResource
import dk.egernet.factory.sandbox.PodmanRuntime
import dk.egernet.factory.sandbox.SandboxManager
import dk.egernet.factory.sandbox.SandboxMount
import dk.egernet.factory.sandbox.SandboxRuntimeException
import dk.egernet.factory.sandbox.SandboxService
import dk.egernet.factory.sandbox.SandboxDefinition
import java.nio.file.Paths
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The exact podman command lines the runtime issues (no podman needed). */
class PodmanRuntimeTest {
    private val cfg = SandboxConfig(mode = "podman")
    private val remote = cfg.copy(podmanBin = "/usr/bin/podman", podmanUrl = "unix:///run/agern-podman/podman.sock")

    private class Recorder(val answer: (List<String>) -> CommandResult = { CommandResult(0, "", false, false) }) : CommandRunner {
        val calls = mutableListOf<List<String>>()
        val timeouts = mutableListOf<Long>()
        override fun run(args: List<String>, timeoutMs: Long, outputLimit: Int): CommandResult {
            calls += args; timeouts += timeoutMs
            return answer(args)
        }
    }

    private val runId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val def = SandboxDefinition(
        image = "docker.io/library/eclipse-temurin:21-jdk", setup = "true", env = mapOf("CI" to "true"),
        services = listOf(SandboxService("postgres", "docker.io/library/postgres:17", mapOf("POSTGRES_PASSWORD" to "dev"))),
        caches = listOf("/root/.gradle"), requestedCpus = null, requestedMemoryBytes = null, cpus = 1.5, memoryBytes = 2L shl 30,
    )

    /** Specs exactly as the manager builds them. */
    private val manager = SandboxManager

    @Test
    fun `the sandbox container`() {
        val spec = manager.mainSpec(runId, "demo-app", Paths.get("/var/lib/agern/workspaces/alice/demo-app/runs/$runId"), def)
        val cacheVolume = manager.cacheVolumeName("demo-app", "/root/.gradle")
        assertTrue(Regex("^agern-cache-demo-app-[0-9a-f]{12}$").matches(cacheVolume), cacheVolume)
        assertEquals(
            listOf(
                "podman", "run", "--detach", "--name", "agern-run-$runId",
                "--label", "agern.run=$runId", "--label", "agern.repo=demo-app", "--label", "agern.role=sandbox",
                "--network", "agern-run-$runId",
                "--security-opt", "no-new-privileges", "--pids-limit", "4096", "--cpus", "1.5", "--memory", "2g", "--memory-swap", "2g",
                "--stop-timeout", "5", "--pull=missing",
                "--workdir", "/workspace", "--entrypoint", "sh",
                "--env", "CI=true",
                "--mount", "type=bind,source=/var/lib/agern/workspaces/alice/demo-app/runs/$runId,target=/workspace",
                "--mount", "type=volume,source=$cacheVolume,target=/root/.gradle",
                "docker.io/library/eclipse-temurin:21-jdk",
                "-c", "trap 'exit 0' TERM INT; while :; do sleep 3600 & wait \$!; done",
            ),
            PodmanRuntime.runArgs(cfg, spec),
        )
    }

    @Test
    fun `the review sandbox container`() {
        val worktree = Paths.get("/var/lib/agern/workspaces/alice/demo-app/runs/$runId")
        val spec = manager.reviewSpec(runId, "demo-app", worktree, def, worktree.resolve(".agern-run/review"))
        val cacheVolume = manager.cacheVolumeName("demo-app", "/root/.gradle")
        val args = PodmanRuntime.runArgs(cfg, spec)
        assertEquals(
            listOf(
                "podman", "run", "--detach", "--name", "agern-review-$runId",
                "--label", "agern.run=$runId", "--label", "agern.repo=demo-app", "--label", "agern.role=review",
                "--network", "agern-review-$runId",
                "--security-opt", "no-new-privileges", "--pids-limit", "4096", "--cpus", "1.5", "--memory", "2g", "--memory-swap", "2g",
                "--stop-timeout", "5", "--pull=missing",
                "--workdir", "/workspace", "--entrypoint", "sh",
                "--env", "CI=true",
                "--mount", "type=bind,source=/var/lib/agern/workspaces/alice/demo-app/runs/$runId,target=/workspace,readonly=true",
                "--mount", "type=bind,source=/var/lib/agern/workspaces/alice/demo-app/runs/$runId/.agern-run/review,target=/run/agern-review",
                "--mount", "type=volume,source=$cacheVolume,target=/root/.gradle",
                "docker.io/library/eclipse-temurin:21-jdk",
                "-c", "trap 'exit 0' TERM INT; while :; do sleep 3600 & wait \$!; done",
            ),
            args,
        )
        assertFalse(args.contains("--privileged"))
        // Without an output directory there is no second bind mount.
        val bare = PodmanRuntime.runArgs(cfg, manager.reviewSpec(runId, "demo-app", worktree, def, null))
        assertEquals(1, bare.count { it.startsWith("type=bind,") })
        val svc = PodmanRuntime.runArgs(cfg, manager.reviewServiceSpec(runId, "demo-app", def.services[0], def))
        assertTrue(svc.containsAll(listOf("agern-review-$runId-postgres", "agern.role=review-service", "agern-review-$runId", "postgres")), svc.toString())
    }

    @Test
    fun `a sidecar service`() {
        val spec = manager.serviceSpec(runId, "demo-app", def.services[0], def)
        assertEquals(
            listOf(
                "/usr/bin/podman", "--url", "unix:///run/agern-podman/podman.sock",
                "run", "--detach", "--name", "agern-run-$runId-postgres",
                "--label", "agern.run=$runId", "--label", "agern.repo=demo-app", "--label", "agern.role=service", "--label", "agern.service=postgres",
                "--network", "agern-run-$runId", "--network-alias", "postgres",
                "--security-opt", "no-new-privileges", "--pids-limit", "4096", "--cpus", "1.5", "--memory", "2g", "--memory-swap", "2g",
                "--stop-timeout", "5", "--pull=missing",
                "--env", "POSTGRES_PASSWORD=dev",
                "docker.io/library/postgres:17",
            ),
            PodmanRuntime.runArgs(remote, spec),
        )
    }

    @Test
    fun `commands raise their oom_score_adj, then run with sh -lc`() {
        val cmd = SandboxManager.shellCommand("./gradlew test")
        assertEquals(listOf("sh", "-c", "{ echo 1000 > /proc/self/oom_score_adj; } 2>/dev/null; exec sh -lc \"\$1\"", "sh", "./gradlew test"), cmd)
        assertEquals("./gradlew test", cmd.last())
    }

    @Test
    fun `a cgroup parent when configured`() {
        val withParent = remote.copy(cgroupParent = "/system.slice/agern-sandbox.service/containers")
        val args = PodmanRuntime.runArgs(withParent, manager.serviceSpec(runId, "demo-app", def.services[0], def))
        val i = args.indexOf("--cgroup-parent")
        assertTrue(i > 0, "no --cgroup-parent in $args")
        assertEquals("/system.slice/agern-sandbox.service/containers", args[i + 1])
        assertEquals("--pull=missing", args[i - 1])
        assertFalse(PodmanRuntime.runArgs(cfg, manager.serviceSpec(runId, "demo-app", def.services[0], def)).contains("--cgroup-parent"))
    }

    @Test
    fun `nothing that widens the boundary is ever passed`() {
        val all = listOf(
            PodmanRuntime.runArgs(cfg, manager.mainSpec(runId, "r", Paths.get("/w"), def)),
            PodmanRuntime.runArgs(cfg, manager.serviceSpec(runId, "r", def.services[0], def)),
            PodmanRuntime.runArgs(cfg, ContainerSpec("n", "docker.io/library/x", mapOf("agern.run" to "x"), "net", cpus = 1.0, memoryBytes = 1L shl 30,
                mounts = listOf(SandboxMount("/w", "/workspace", volume = false, readOnly = true)))),
            PodmanRuntime.runArgs(cfg, manager.mainSpec(runId, "r", Paths.get("/w"), def).copy(image = "localhost/agern-prepared/r:0123456789abcdef", pull = "never")),
            PodmanRuntime.commitArgs(cfg, "agern-run-x", "localhost/agern-prepared/r:0123456789abcdef", mapOf("agern.prepared" to "0123456789abcdef")),
            PodmanRuntime.pullArgs(cfg, "docker.io/library/x"),
        ).flatten()
        for (bad in listOf("--pull=always", "--include-volumes", "--pause", "--privileged", "--cap-add", "--device", "--userns=host", "--pid", "--ipc", "--uts", "--security-opt=seccomp=unconfined", "host")) {
            assertFalse(all.any { it == bad || it.startsWith("$bad=") }, "found $bad")
        }
        assertFalse(all.any { it.contains("label=disable") || it.contains("unconfined") })
        assertTrue(all.contains("type=bind,source=/w,target=/workspace,readonly=true"))
    }

    @Test
    fun `other commands`() {
        assertEquals(listOf("podman", "network", "create", "--label", "agern.run=x", "--label", "agern.repo=r", "agern-run-x"),
            PodmanRuntime.createNetworkArgs(cfg, "agern-run-x", linkedMapOf("agern.run" to "x", "agern.repo" to "r")))
        assertEquals(listOf("podman", "exec", "--workdir", "/workspace", "agern-run-x", "sh", "-lc", "./gradlew test"),
            PodmanRuntime.execArgs(cfg, "agern-run-x", "/workspace", listOf("sh", "-lc", "./gradlew test")))
        assertEquals(listOf("podman", "restart", "--time", "0", "agern-run-x"), PodmanRuntime.restartArgs(cfg, "agern-run-x"))
        // --volumes: anonymous volumes (e.g. a sidecar image's VOLUME) go with the container; named cache volumes stay.
        assertEquals(listOf("podman", "rm", "--force", "--ignore", "--volumes", "a", "b"), PodmanRuntime.removeContainersArgs(cfg, listOf("a", "b")))
        assertEquals(listOf("podman", "network", "rm", "--force", "agern-run-x"), PodmanRuntime.removeNetworkArgs(cfg, "agern-run-x"))
        assertEquals(listOf("podman", "volume", "exists", "v"), PodmanRuntime.volumeExistsArgs(cfg, "v"))
        assertEquals(listOf("podman", "volume", "create", "--label", "agern.repo=r", "v"), PodmanRuntime.createVolumeArgs(cfg, "v", mapOf("agern.repo" to "r")))
        assertEquals(listOf("podman", "ps", "--all", "--filter", "label=agern.run", "--format", "{{.Names}}\t{{index .Labels \"agern.run\"}}"),
            PodmanRuntime.listContainersArgs(cfg))
        assertEquals(listOf("podman", "network", "ls", "--filter", "label=agern.run", "--format", "json"),
            PodmanRuntime.listNetworksArgs(cfg))
        // As Podman 4.9 prints it (other fields left out); no networks: [] (older versions: null).
        val json = """[{"name":"agern-run-a","driver":"bridge","labels":{"agern.repo":"r","agern.run":"a"}},
            {"name":"other","labels":{"x":"y"}},{"name":"agern-run-b","labels":{"agern.run":"b"}}]"""
        assertEquals(listOf(LabelledResource("agern-run-a", "a"), LabelledResource("agern-run-b", "b")), PodmanRuntime.parseNetworksJson(json))
        assertEquals(emptyList(), PodmanRuntime.parseNetworksJson("[]\n"))
        assertEquals(emptyList(), PodmanRuntime.parseNetworksJson("null"))
        assertFailsWith<SandboxRuntimeException> { PodmanRuntime.parseNetworksJson("Error: something") }
        assertEquals(listOf("/usr/bin/podman", "--url", "unix:///run/agern-podman/podman.sock", "exec", "--workdir", "/workspace", "c", "true"),
            PodmanRuntime.execArgs(remote, "c", "/workspace", listOf("true")))
    }

    @Test
    fun `runtime methods drive the runner`() {
        val rec = Recorder { args ->
            when {
                args.contains("exists") -> CommandResult(1, "", false, false)
                args.contains("ps") -> CommandResult(0, "agern-run-a\ta\nagern-run-a-db\ta\nother\t<no value>\n", false, false)
                args.contains("slow") -> CommandResult(-1, "partial", false, true)
                args.contains("broken") -> CommandResult(125, "Error: no such image", false, false)
                else -> CommandResult(0, "ok", false, false)
            }
        }
        val rt = PodmanRuntime(cfg, rec)
        rt.ensureVolume("v", mapOf("agern.repo" to "r"))
        assertEquals(listOf("podman", "volume", "exists", "v"), rec.calls[0])
        assertEquals(listOf("podman", "volume", "create", "--label", "agern.repo=r", "v"), rec.calls[1])
        assertEquals(listOf(LabelledResource("agern-run-a", "a"), LabelledResource("agern-run-a-db", "a")), rt.listContainers())
        val r = rt.exec("c", listOf("sh", "-lc", "slow"), "/workspace", 1234)
        assertTrue(r.timedOut)
        assertEquals(-1, r.exitCode)
        assertEquals(1234L, rec.timeouts.last())
        val e = assertFailsWith<SandboxRuntimeException> {
            rt.startContainer(ContainerSpec("broken", "docker.io/library/x", mapOf("agern.run" to "x"), "net", cpus = 1.0, memoryBytes = 1L shl 30))
        }
        assertTrue(e.message!!.contains("no such image"))
        rt.removeContainers(emptyList())
        assertFalse(rec.calls.last().contains("rm"), "no rm without names")
    }

    @Test
    fun `a platform container with a non-root user`() {
        val withUser = def.copy(
            platforms = listOf(dk.egernet.factory.sandbox.SandboxPlatform("jdk17", "docker.io/library/eclipse-temurin:17-jdk", "true", true)),
            user = dk.egernet.factory.sandbox.SandboxUser("builder", 1000),
        )
        val spec = manager.commandSpec(runId, "demo-app", Paths.get("/w"), withUser, withUser.platforms[0])
        val cacheVolume = manager.cacheVolumeName("demo-app", "/root/.gradle", "jdk17")
        assertTrue(cacheVolume != manager.cacheVolumeName("demo-app", "/root/.gradle"), "platforms have their own cache volumes")
        assertEquals(
            listOf(
                "podman", "run", "--detach", "--name", "agern-run-$runId--jdk17",
                "--label", "agern.run=$runId", "--label", "agern.repo=demo-app", "--label", "agern.role=platform", "--label", "agern.platform=jdk17",
                "--network", "agern-run-$runId", "--userns", "keep-id:uid=1000,gid=1000", "--user", "0:0",
                "--security-opt", "no-new-privileges", "--pids-limit", "4096", "--cpus", "1.5", "--memory", "2g", "--memory-swap", "2g",
                "--stop-timeout", "5", "--pull=missing",
                "--workdir", "/workspace", "--entrypoint", "sh",
                "--env", "CI=true",
                "--mount", "type=bind,source=/w,target=/workspace",
                "--mount", "type=volume,source=$cacheVolume,target=/root/.gradle",
                "docker.io/library/eclipse-temurin:17-jdk",
                "-c", "trap 'exit 0' TERM INT; while :; do sleep 3600 & wait \$!; done",
            ),
            PodmanRuntime.runArgs(cfg, spec),
        )
        // The default container of the same definition: same user mapping; services never get one.
        assertTrue(PodmanRuntime.runArgs(cfg, manager.mainSpec(runId, "demo-app", Paths.get("/w"), withUser)).containsAll(listOf("keep-id:uid=1000,gid=1000", "0:0")))
        assertFalse(PodmanRuntime.runArgs(cfg, manager.serviceSpec(runId, "demo-app", withUser.services[0], withUser)).contains("--userns"))
        assertEquals(
            listOf("podman", "exec", "--user", "1000:1000", "--env", "HOME=/home/builder", "--env", "USER=builder", "--env", "LOGNAME=builder",
                "--workdir", "/workspace", "agern-run-x", "true"),
            PodmanRuntime.execArgs(cfg, "agern-run-x", "/workspace", listOf("true"), dk.egernet.factory.sandbox.ExecUser(1000, 1000, "builder", "/home/builder")),
        )
        assertEquals(listOf("podman", "logs", "--tail", "30", "agern-run-x-db"), PodmanRuntime.logsArgs(cfg, "agern-run-x-db", 30))
    }

    @Test
    fun `prepared image commands`() {
        assertEquals(listOf("podman", "image", "exists", "docker.io/library/x:1"), PodmanRuntime.imageExistsArgs(cfg, "docker.io/library/x:1"))
        assertEquals(listOf("podman", "image", "inspect", "a", "b"), PodmanRuntime.imageInspectArgs(cfg, listOf("a", "b")))
        assertEquals(listOf("/usr/bin/podman", "--url", "unix:///run/agern-podman/podman.sock", "pull", "--quiet", "docker.io/library/x:1"),
            PodmanRuntime.pullArgs(remote, "docker.io/library/x:1"))
        val labels = SandboxManager.preparedLabels("0123456789abcdef", "demo-app", "default", "d".repeat(64), "docker.io/library/eclipse-temurin:21-jdk@sha256:" + "e".repeat(64))
        assertEquals(
            listOf("podman", "commit", "--quiet",
                "--change", "LABEL agern.prepared=0123456789abcdef", "--change", "LABEL agern.prepared.repo=demo-app",
                "--change", "LABEL agern.prepared.platform=default", "--change", "LABEL agern.prepared.definition=${"d".repeat(64)}",
                "--change", "LABEL agern.prepared.base=docker.io/library/eclipse-temurin:21-jdk@sha256:${"e".repeat(64)}",
                "agern-run-x", "localhost/agern-prepared/demo-app:0123456789abcdef"),
            PodmanRuntime.commitArgs(cfg, "agern-run-x", "localhost/agern-prepared/demo-app:0123456789abcdef", labels),
        )
        for (bad in listOf("a b", "x\ny", "\"q\"", "\$(id)", "a=b")) {
            assertFailsWith<IllegalArgumentException> { PodmanRuntime.commitArgs(cfg, "c", "i", mapOf("agern.prepared" to bad)) }
        }
        assertEquals(listOf("podman", "images", "--filter", "label=agern.prepared", "--format", "{{.ID}}"), PodmanRuntime.listPreparedArgs(cfg))
        assertEquals(listOf("podman", "rmi", "abc"), PodmanRuntime.removeImageArgs(cfg, "abc"))
        val spec = manager.mainSpec(runId, "demo-app", Paths.get("/w"), def)
        assertTrue(PodmanRuntime.runArgs(cfg, spec).contains("--pull=missing"))
        val prepared = PodmanRuntime.runArgs(cfg, spec.copy(image = "localhost/agern-prepared/demo-app:0123456789abcdef", pull = ContainerSpec.PULL_NEVER))
        assertTrue(prepared.contains("--pull=never") && !prepared.contains("--pull=missing"))
        assertEquals("localhost/agern-prepared/demo-app:0123456789abcdef", prepared[prepared.size - 3], "the image, before the keep-alive command")
        assertFailsWith<IllegalArgumentException> { spec.copy(pull = "always") }
    }

    @Test
    fun `image inspect output is read tolerantly`() {
        val out = """
            [{"Id": "sha256:aaa", "Created": "2026-11-20T10:15:30.123456789+01:00", "Size": 123456, "Labels": {"agern.prepared": "k1", "agern.prepared.repo": "demo-app"},
              "RepoTags": ["localhost/agern-prepared/demo-app:k1"]},
             {"Id": "bbb", "Created": "2026-11-21T00:00:00Z", "Size": 7, "Labels": null, "Config": {"Labels": {"agern.prepared": "k2"}}},
             {"Created": "2026-11-21T00:00:00Z"},
             {"Id": "ccc", "Created": "not a time"}]
        """.trimIndent()
        val images = PodmanRuntime.parseImagesJson(out)
        assertEquals(listOf("aaa", "bbb"), images.map { it.id })
        assertEquals(java.time.Instant.parse("2026-11-20T09:15:30.123456789Z"), images[0].createdAt)
        assertEquals(123456L, images[0].sizeBytes)
        assertEquals("demo-app", images[0].labels["agern.prepared.repo"])
        assertEquals(listOf("localhost/agern-prepared/demo-app:k1"), images[0].tags)
        assertEquals(mapOf("agern.prepared" to "k2"), images[1].labels)
        assertEquals(emptyList(), PodmanRuntime.parseImagesJson("[]"))
        assertEquals(emptyList(), PodmanRuntime.parseImagesJson(""))
        assertFailsWith<SandboxRuntimeException> { PodmanRuntime.parseImagesJson("Error: nope") }
    }

    @Test
    fun `image lookups, pulls and removals through the runner`() {
        val inspect = """[{"Id":"sha256:abc","Created":"2026-11-20T10:00:00Z","Size":5,"Labels":{}}]"""
        val missing = Recorder { a -> if (a[1] == "image" && a[2] == "exists") CommandResult(1, "", false, false) else CommandResult(0, inspect, false, false) }
        assertEquals(null, PodmanRuntime(cfg, missing).imageInfo("docker.io/library/x:1"))
        assertEquals(1, missing.calls.size)
        val present = Recorder { a -> CommandResult(0, if (a[1] == "image" && a[2] == "inspect") inspect else "", false, false) }
        assertEquals("abc", PodmanRuntime(cfg, present).imageInfo("docker.io/library/x:1")!!.id)
        assertEquals(listOf(listOf("podman", "image", "exists", "docker.io/library/x:1"), listOf("podman", "image", "inspect", "docker.io/library/x:1")), present.calls)

        val list = Recorder { a -> CommandResult(0, if (a[1] == "images") "abc\nabc\ndef\n" else inspect, false, false) }
        PodmanRuntime(cfg, list).listPreparedImages()
        assertEquals(listOf("podman", "image", "inspect", "abc", "def"), list.calls[1])
        assertEquals(emptyList(), PodmanRuntime(cfg, Recorder { CommandResult(0, "", false, false) }).listPreparedImages())

        assertTrue(PodmanRuntime(cfg, Recorder { CommandResult(0, "", false, false) }).removeImage("abc"))
        assertFalse(PodmanRuntime(cfg, Recorder { CommandResult(2, "Error: image used by 123: image is in use by a container", false, false) }).removeImage("abc"))
        assertFailsWith<SandboxRuntimeException> { PodmanRuntime(cfg, Recorder { CommandResult(125, "Error: storage broken", false, false) }).removeImage("abc") }
        val pull = Recorder()
        PodmanRuntime(cfg, pull).pullImage("docker.io/library/x:1")
        assertEquals(20 * 60_000L, pull.timeouts.single())
    }
}
