package dk.egernet.factory

import dk.egernet.factory.sandbox.ContainerSpec
import dk.egernet.factory.sandbox.ExecResult
import dk.egernet.factory.sandbox.PodmanRuntime
import dk.egernet.factory.sandbox.ProcessCommandRunner
import dk.egernet.factory.sandbox.SandboxDefinition
import dk.egernet.factory.sandbox.SandboxManager
import dk.egernet.factory.sandbox.SandboxService
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [PodmanRuntime] against a real rootless Podman — opt-in, skipped unless `AGERN_PODMAN_IT=1`.
 *
 * Packaged hosts run it as the service user through the `agern-sandbox` socket, under the hardened `agern.service`
 * (see `packaging/deb/sandbox-host-test.sh`, which CI runs on a VM). Settings, all from the environment:
 *
 *   AGERN_PODMAN_IT=1                       enables the test
 *   FACTORY_SANDBOX_PODMAN, FACTORY_SANDBOX_PODMAN_URL, FACTORY_SANDBOX_CGROUP_PARENT   as for the server
 *   AGERN_PODMAN_IT_DIR                     where the worktree-like directories are created (default: FACTORY_DATA_DIR,
 *                                           else java.io.tmpdir). It must be visible to the Podman service, so not a
 *                                           private /tmp of the calling unit.
 *   AGERN_PODMAN_IT_IMAGE                   a small image with busybox tools (default docker.io/library/busybox:1.36.1)
 *
 * Containers are built with the same [SandboxManager] spec functions the server uses, so the command lines are exactly
 * those of a run (docs/ARCHITECTURE.md §5 "Sandboxes").
 */
class PodmanIntegrationTest {
    private val env = System.getenv()
    private val enabled = env["AGERN_PODMAN_IT"] == "1"
    private val image = env["AGERN_PODMAN_IT_IMAGE"] ?: "docker.io/library/busybox:1.36.1"
    private val cfg by lazy {
        SandboxConfig(
            mode = "podman",
            podmanBin = env["FACTORY_SANDBOX_PODMAN"] ?: "podman",
            podmanUrl = env["FACTORY_SANDBOX_PODMAN_URL"]?.takeIf { it.isNotBlank() },
            cgroupParent = env["FACTORY_SANDBOX_CGROUP_PARENT"]?.takeIf { it.isNotBlank() },
        )
    }
    private val rt by lazy { PodmanRuntime(cfg) }
    private val runner = ProcessCommandRunner()
    private val repo = "agern-it-" + UUID.randomUUID().toString().take(8)
    private val runA = UUID.randomUUID()
    private val runB = UUID.randomUUID()
    private lateinit var base: Path

    private val memory = 128L shl 20
    private val def by lazy {
        SandboxDefinition(
            image = image, setup = null, env = mapOf("AGERN_IT" to "yes"),
            services = listOf(SandboxService("web", image, emptyMap())),
            caches = listOf("/cache"), requestedCpus = null, requestedMemoryBytes = null, cpus = 0.5, memoryBytes = memory,
        )
    }

    @BeforeTest
    fun setUp() {
        assumeTrue(enabled, "set AGERN_PODMAN_IT=1 to run the real-Podman integration test")
        val dir = env["AGERN_PODMAN_IT_DIR"] ?: env["FACTORY_DATA_DIR"] ?: System.getProperty("java.io.tmpdir")
        base = Files.createTempDirectory(Paths.get(dir).toAbsolutePath(), "agern-podman-it-")
    }

    @AfterTest
    fun tearDown() {
        if (!enabled) return
        val names = listOf(runA, runB).flatMap { listOf(SandboxManager.containerName(it), SandboxManager.serviceContainerName(it, "web")) } +
            "agern-it-anon-$runA"
        runCatching { rt.removeContainers(names) }
        for (r in listOf(runA, runB)) runCatching { rt.removeNetwork(SandboxManager.networkName(r)) }
        runCatching { podman("volume", "rm", "--force", SandboxManager.cacheVolumeName(repo, "/cache")) }
        // Files written in the containers belong to the service user (container root), so this works without podman unshare.
        if (::base.isInitialized) base.toFile().deleteRecursively()
    }

    private fun podman(vararg args: String, timeoutMs: Long = 120_000): ExecResult {
        val r = runner.run(PodmanRuntime.base(cfg) + args.toList(), timeoutMs, 1 shl 20)
        return ExecResult(r.exitCode, r.output, r.truncated, r.timedOut, 0)
    }

    private fun sh(container: String, command: String, timeoutMs: Long = 120_000): ExecResult =
        rt.exec(container, listOf("sh", "-lc", command), SandboxManager.WORKDIR, timeoutMs)

    private fun ok(container: String, command: String): String {
        val r = sh(container, command)
        if (r.exitCode != 0) fail("`$command` in $container exited ${r.exitCode}: ${r.output}")
        return r.output.trim()
    }

    /** Network, cache volume, sidecar and sandbox, exactly as [SandboxManager] creates them. */
    private fun create(run: UUID, worktree: Path) {
        rt.createNetwork(SandboxManager.networkName(run), SandboxManager.labels(run, repo, "network"))
        for (c in def.caches) rt.ensureVolume(SandboxManager.cacheVolumeName(repo, c), linkedMapOf("agern.repo" to repo, "agern.cache" to c))
        // busybox has no long-running default command; the server would use the image's own (e.g. postgres).
        val web = SandboxManager.serviceSpec(run, repo, def.services[0], def).copy(
            command = listOf("sh", "-c", "mkdir -p /www && echo pong-$run > /www/index.html && exec httpd -f -p 8080 -h /www"),
        )
        rt.startContainer(web)
        rt.startContainer(SandboxManager.mainSpec(run, repo, worktree, def))
    }

    @Test
    fun `a run's sandbox on real rootless podman`() {
        val worktreeA = Files.createDirectories(base.resolve("runs/a"))
        val worktreeB = Files.createDirectories(base.resolve("runs/b"))
        Files.writeString(worktreeA.resolve("from-host.txt"), "hello from the host\n")
        Files.writeString(worktreeB.resolve("secret-of-b.txt"), "run b\n")
        val a = SandboxManager.containerName(runA)

        create(runA, worktreeA)

        // Exec: output, exit code, working directory, environment.
        val hello = sh(a, "echo out; echo err >&2; pwd; echo \$AGERN_IT; exit 7")
        assertEquals(7, hello.exitCode, hello.output)
        assertFalse(hello.timedOut)
        for (line in listOf("out", "err", "/workspace", "yes")) assertTrue(hello.output.lines().contains(line), "missing '$line' in ${hello.output}")

        // The worktree is mounted read-write; what the build writes belongs to the service user on the host.
        assertEquals("hello from the host", ok(a, "cat from-host.txt"))
        ok(a, "mkdir -p build && echo built > build/out.txt")
        val written = worktreeA.resolve("build/out.txt")
        assertEquals("built\n", Files.readString(written))
        assertEquals(Files.getAttribute(worktreeA, "unix:uid"), Files.getAttribute(written, "unix:uid"), "owner of a file written in the sandbox")
        assertEquals(Files.getAttribute(worktreeA, "unix:gid"), Files.getAttribute(written, "unix:gid"), "group of a file written in the sandbox")

        // Nothing else from the host: not the data directory, the configuration, another run's worktree.
        val hostPaths = listOf(base.toString(), worktreeB.toString(), "/etc/agern", "/var/lib/agern", "/run/agern-podman")
        for (p in hostPaths) assertEquals("absent", ok(a, "test -e '$p' && echo present || echo absent"), "$p is visible in the sandbox")
        assertEquals("build from-host.txt", ok(a, "ls /workspace | tr '\\n' ' '").trim())
        assertEquals("absent", ok(a, "grep -rl 'run b' / --exclude-dir=proc --exclude-dir=sys 2>/dev/null | head -n 1 | grep -q . && echo present || echo absent"))

        // Not privileged: no CAP_SYS_ADMIN, no new privileges, seccomp on, cannot mount.
        val status = ok(a, "cat /proc/self/status")
        val capEff = Regex("CapEff:\\s*([0-9a-f]+)").find(status)!!.groupValues[1].toLong(16)
        assertEquals(0L, capEff and (1L shl 21), "CAP_SYS_ADMIN in CapEff ($status)")
        assertTrue(Regex("NoNewPrivs:\\s*1").containsMatchIn(status), "NoNewPrivs ($status)")
        assertTrue(Regex("Seccomp:\\s*2").containsMatchIn(status), "seccomp filter ($status)")
        assertTrue(sh(a, "mkdir -p /mnt/x && mount -t tmpfs none /mnt/x").exitCode != 0, "mount succeeded in the sandbox")
        assertEquals("false", podman("inspect", "--format", "{{.HostConfig.Privileged}}", a).output.trim())
        assertEquals("sandbox", podman("inspect", "--format", "{{index .Config.Labels \"agern.role\"}}", a).output.trim())

        // Limits are applied (the container has its own cgroup with them) ...
        assertEquals(memory.toString(), ok(a, "cat /sys/fs/cgroup/memory.max"), "memory.max")
        assertEquals("50000 100000", ok(a, "cat /sys/fs/cgroup/cpu.max"), "cpu.max for 0.5 CPUs")
        assertEquals("4096", ok(a, "cat /sys/fs/cgroup/pids.max"), "pids.max")
        // ... and enforced: a process that allocates more than the limit is killed, the sandbox survives.
        val before = ok(a, "echo pid1 \$(cat /proc/1/oom_score_adj) self \$(cat /proc/self/oom_score_adj) group \$(cat /sys/fs/cgroup/memory.oom.group 2>/dev/null)")
        println("before the memory hog: $before")
        val hog = sh(a, "x=\$(head -c 400000000 /dev/zero | tr '\\0' a); echo survived \${#x}", timeoutMs = 120_000)
        val state = podman("inspect", "--format", "{{.State.Status}} exit={{.State.ExitCode}} oomkilled={{.State.OOMKilled}}", a).output.trim()
        println("after the memory hog: exit ${hog.exitCode}, container $state")
        assertTrue(hog.exitCode != 0 && !hog.output.contains("survived"), "a 400 MB allocation was not stopped by a 128 MB limit: ${hog.exitCode} ${hog.output}")
        assertTrue(state.startsWith("running"), "the sandbox itself did not survive the memory hog ($state; before: $before)")
        assertTrue(Regex("oom_kill [1-9]").containsMatchIn(ok(a, "cat /sys/fs/cgroup/memory.events")), "no oom_kill in memory.events")
        assertEquals("alive", ok(a, "echo alive"))

        // The sidecar is reachable by its service name on the run's private network.
        assertEquals("pong-$runA", ok(a, "wget -q -O - http://web:8080/"))

        // Cache volume: written now, read by the next run of the same project.
        ok(a, "echo cached-by-a > /cache/marker")

        // Timeout: the client is stopped, the container restarted (every process killed), files kept.
        ok(a, "echo before-timeout > kept.txt")
        val slow = sh(a, "sleep 300", timeoutMs = 3_000)
        assertTrue(slow.timedOut, "sleep 300 did not time out")
        assertEquals(-1, slow.exitCode)
        rt.restartContainer(a)
        assertEquals("before-timeout", ok(a, "cat kept.txt"))
        assertEquals("0", ok(a, "ps -o args | grep -cx 'sleep 300' || true"), "the timed-out command survived the restart")

        // The reaper's view: labelled containers and network of this run.
        val containers = rt.listContainers().filter { it.runId == runA.toString() }.map { it.name }.toSet()
        assertEquals(setOf(a, SandboxManager.serviceContainerName(runA, "web")), containers)
        assertEquals(listOf(SandboxManager.networkName(runA)), rt.listNetworks().filter { it.runId == runA.toString() }.map { it.name })
        assertEquals(emptyList(), rt.exposedPorts(image), "busybox declares no ports")

        // Another run: its own network (the first run's sidecar is unreachable), same cache volume.
        create(runB, worktreeB)
        val b = SandboxManager.containerName(runB)
        assertEquals("cached-by-a", ok(b, "cat /cache/marker"))
        assertEquals("pong-$runB", ok(b, "wget -q -O - http://web:8080/"))
        val crossRun = sh(b, "wget -q -T 5 -O - http://${SandboxManager.serviceContainerName(runA, "web")}:8080/")
        assertTrue(crossRun.exitCode != 0, "run B reached run A's sidecar: ${crossRun.output}")
        assertEquals("absent", ok(b, "test -e /workspace/from-host.txt && echo present || echo absent"))

        // Removal: containers (with their anonymous volumes) and network; the cache volume stays.
        val anon = "agern-it-anon-$runA"
        val created = podman("run", "--detach", "--name", anon, "--label", "agern.run=$runA", "-v", "/anon-data", image, "sleep", "600")
        assertEquals(0, created.exitCode, created.output)
        val anonVolume = podman("inspect", "--format", "{{range .Mounts}}{{.Name}}{{end}}", anon).output.trim()
        assertTrue(anonVolume.isNotEmpty(), "no anonymous volume")
        rt.removeContainers(listOf(a, SandboxManager.serviceContainerName(runA, "web"), anon))
        rt.removeNetwork(SandboxManager.networkName(runA))
        rt.removeNetwork(SandboxManager.networkName(runA)) // missing: ignored
        rt.removeContainers(listOf(a)) // missing: ignored
        assertTrue(rt.listContainers().none { it.runId == runA.toString() }, "containers of run A left")
        assertTrue(rt.listNetworks().none { it.runId == runA.toString() }, "network of run A left")
        assertTrue(podman("volume", "exists", anonVolume).exitCode != 0, "the anonymous volume $anonVolume was not removed with its container")
        assertEquals(0, podman("volume", "exists", SandboxManager.cacheVolumeName(repo, "/cache")).exitCode, "the cache volume was removed")
        assertEquals("built\n", Files.readString(written), "the worktree must survive the sandbox")
    }
}
