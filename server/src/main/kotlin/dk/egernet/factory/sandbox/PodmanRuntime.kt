package dk.egernet.factory.sandbox

import dk.egernet.factory.SandboxConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/** Result of one CLI invocation: exit code and stdout+stderr (the tail, at most the limit given). */
data class CommandResult(val exitCode: Int, val output: String, val truncated: Boolean, val timedOut: Boolean)

/** Runs one command line. The production runner starts a process; tests capture the argument lists. */
fun interface CommandRunner {
    fun run(args: List<String>, timeoutMs: Long, outputLimit: Int): CommandResult
}

/**
 * Starts the command with an environment built from an allow-list of the server's variables: what rootless podman
 * needs to find its storage and runtime directory, nothing else (the server's environment holds the master key and the
 * database password).
 */
class ProcessCommandRunner(private val env: Map<String, String> = System.getenv()) : CommandRunner {
    override fun run(args: List<String>, timeoutMs: Long, outputLimit: Int): CommandResult {
        val pb = ProcessBuilder(args).redirectErrorStream(true)
        pb.environment().clear()
        for (k in PASS_THROUGH) env[k]?.let { pb.environment()[k] = it }
        pb.environment().putIfAbsent("PATH", "/usr/local/bin:/usr/bin:/bin")
        pb.environment()["LANG"] = "C.UTF-8"
        val p = try {
            pb.start()
        } catch (e: Exception) {
            throw SandboxRuntimeException("Could not start ${args.first()}: ${e.message}")
        }
        p.outputStream.close()
        val out = StringBuilder()
        var dropped = false
        val reader = Thread({
            try {
                p.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                    val buf = CharArray(8192)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(out) {
                            out.append(buf, 0, n)
                            if (out.length > outputLimit + 65536) {
                                out.delete(0, out.length - outputLimit)
                                dropped = true
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }, "podman-output").apply { isDaemon = true; start() }
        val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            p.descendants().forEach { it.destroyForcibly() }
            p.destroyForcibly()
            p.waitFor(5, TimeUnit.SECONDS)
        }
        reader.join(5000)
        val text = synchronized(out) {
            if (out.length > outputLimit) { out.delete(0, out.length - outputLimit); dropped = true }
            out.toString()
        }
        return CommandResult(if (finished) p.exitValue() else -1, text, dropped, !finished)
    }

    companion object {
        val PASS_THROUGH = listOf(
            "PATH", "HOME", "USER", "LOGNAME", "XDG_RUNTIME_DIR", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "DBUS_SESSION_BUS_ADDRESS", "TMPDIR",
        )
    }
}

/**
 * Rootless Podman through its CLI (docs/ARCHITECTURE.md §5 "Sandboxes"). With [SandboxConfig.podmanUrl] every call is
 * `podman --url <url> …` (podman-remote against `podman system service`). The argument lists are built by the pure
 * functions in the companion object, so they can be checked without podman: never `--privileged`, no host
 * network/pid/ipc, no added capabilities, no devices, `no-new-privileges`.
 */
class PodmanRuntime(
    private val cfg: SandboxConfig,
    private val runner: CommandRunner = ProcessCommandRunner(),
) : SandboxRuntime {
    private val log = LoggerFactory.getLogger(PodmanRuntime::class.java)

    private fun call(args: List<String>, timeoutMs: Long = 120_000, what: String = args.getOrNull(base(cfg).size) ?: "podman"): CommandResult {
        val r = runner.run(args, timeoutMs, 64 * 1024)
        if (r.timedOut) throw SandboxRuntimeException("podman $what timed out")
        if (r.exitCode != 0) throw SandboxRuntimeException("podman $what failed (exit ${r.exitCode}): ${r.output.trim().takeLast(2000)}")
        return r
    }

    override fun createNetwork(name: String, labels: Map<String, String>) {
        call(createNetworkArgs(cfg, name, labels), what = "network create")
    }

    override fun ensureVolume(name: String, labels: Map<String, String>) {
        val exists = runner.run(volumeExistsArgs(cfg, name), 60_000, 4096)
        if (exists.exitCode == 0) return
        call(createVolumeArgs(cfg, name, labels), what = "volume create")
    }

    override fun startContainer(spec: ContainerSpec) {
        // Includes pulling the image the first time.
        call(runArgs(cfg, spec), timeoutMs = 20 * 60_000L, what = "run ${spec.name}")
    }

    override fun exec(container: String, command: List<String>, workdir: String, timeoutMs: Long, user: ExecUser?): ExecResult {
        val started = System.nanoTime()
        val r = runner.run(execArgs(cfg, container, workdir, command, user), timeoutMs, ExecLimits.LOG_CHARS)
        return ExecResult(
            exitCode = if (r.timedOut) -1 else r.exitCode, output = r.output, outputTruncated = r.truncated, timedOut = r.timedOut,
            durationMs = (System.nanoTime() - started) / 1_000_000,
        )
    }

    override fun restartContainer(name: String) {
        call(restartArgs(cfg, name), what = "restart")
    }

    override fun removeContainers(names: List<String>) {
        if (names.isEmpty()) return
        call(removeContainersArgs(cfg, names), timeoutMs = 120_000, what = "rm")
    }

    override fun removeNetwork(name: String) {
        val r = runner.run(removeNetworkArgs(cfg, name), 60_000, 4096)
        if (r.exitCode != 0 && !r.output.contains("no such network", ignoreCase = true) && !r.output.contains("network not found", ignoreCase = true)) {
            throw SandboxRuntimeException("podman network rm failed (exit ${r.exitCode}): ${r.output.trim().takeLast(1000)}")
        }
    }

    override fun logs(name: String, lines: Int): String = try {
        runner.run(logsArgs(cfg, name, lines), 60_000, 64 * 1024).output
    } catch (e: SandboxRuntimeException) {
        ""
    }

    override fun listContainers(): List<LabelledResource> = parseLabelled(call(listContainersArgs(cfg), 60_000, "ps").output)

    override fun listNetworks(): List<LabelledResource> = parseNetworksJson(call(listNetworksArgs(cfg), 60_000, "network ls").output)

    override fun exposedPorts(image: String): List<String> = try {
        val out = call(exposedPortsArgs(cfg, image), 60_000, "image inspect").output.trim()
        Regex("\"([0-9]+/[a-z]+)\"").findAll(out).map { it.groupValues[1] }.toList().sorted()
    } catch (e: SandboxRuntimeException) {
        log.debug("Could not read exposed ports of {}: {}", image, e.message)
        emptyList()
    }

    override fun imageInfo(image: String): LocalImage? {
        val exists = runner.run(imageExistsArgs(cfg, image), 60_000, 4096)
        if (exists.timedOut) throw SandboxRuntimeException("podman image exists timed out")
        if (exists.exitCode == 1) return null
        if (exists.exitCode != 0) throw SandboxRuntimeException("podman image exists failed (exit ${exists.exitCode}): ${exists.output.trim().takeLast(1000)}")
        return parseImagesJson(call(imageInspectArgs(cfg, listOf(image)), 60_000, "image inspect").output).firstOrNull()
    }

    override fun pullImage(image: String) {
        call(pullArgs(cfg, image), timeoutMs = 20 * 60_000L, what = "pull $image")
    }

    override fun commitContainer(container: String, image: String, labels: Map<String, String>) {
        call(commitArgs(cfg, container, image, labels), timeoutMs = 20 * 60_000L, what = "commit $container")
    }

    override fun listPreparedImages(): List<LocalImage> {
        val ids = call(listPreparedArgs(cfg), 60_000, "images").output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
        if (ids.isEmpty()) return emptyList()
        // One inspect for all; when one of them vanished in between, one by one (skipping the gone ones).
        return try {
            parseImagesJson(call(imageInspectArgs(cfg, ids), 60_000, "image inspect").output)
        } catch (e: SandboxRuntimeException) {
            ids.mapNotNull { id -> runCatching { parseImagesJson(call(imageInspectArgs(cfg, listOf(id)), 60_000, "image inspect").output).firstOrNull() }.getOrNull() }
        }
    }

    override fun removeImage(id: String): Boolean {
        val r = runner.run(removeImageArgs(cfg, id), 120_000, 16 * 1024)
        if (r.timedOut) throw SandboxRuntimeException("podman rmi timed out")
        if (r.exitCode == 0) return true
        val out = r.output.lowercase()
        // Podman: exit 2 "image is in use by a container", exit 1 "image not known".
        if ("in use" in out || "not known" in out || "no such image" in out) return false
        throw SandboxRuntimeException("podman rmi failed (exit ${r.exitCode}): ${r.output.trim().takeLast(1000)}")
    }

    companion object {
        /** `podman` or `podman --url <url>`. */
        fun base(cfg: SandboxConfig): List<String> =
            listOf(cfg.podmanBin) + (cfg.podmanUrl?.let { listOf("--url", it) } ?: emptyList())

        private fun labelArgs(labels: Map<String, String>) = labels.flatMap { (k, v) -> listOf("--label", "$k=$v") }

        fun createNetworkArgs(cfg: SandboxConfig, name: String, labels: Map<String, String>) =
            base(cfg) + listOf("network", "create") + labelArgs(labels) + name

        fun volumeExistsArgs(cfg: SandboxConfig, name: String) = base(cfg) + listOf("volume", "exists", name)

        fun createVolumeArgs(cfg: SandboxConfig, name: String, labels: Map<String, String>) =
            base(cfg) + listOf("volume", "create") + labelArgs(labels) + name

        fun runArgs(cfg: SandboxConfig, s: ContainerSpec): List<String> {
            val a = base(cfg).toMutableList()
            a += listOf("run", "--detach", "--name", s.name)
            a += labelArgs(s.labels)
            a += listOf("--network", s.network)
            s.networkAlias?.let { a += listOf("--network-alias", it) }
            // Never `host`: a private user namespace in which the service user is uid n (not root), Podman 4.3+.
            s.keepIdUid?.let { a += listOf("--userns", "keep-id:uid=$it,gid=$it") }
            s.user?.let { a += listOf("--user", it) }
            a += listOf(
                "--security-opt", "no-new-privileges",
                "--pids-limit", s.pidsLimit.toString(),
                "--cpus", formatCpus(s.cpus),
                "--memory", SandboxConfig.formatMemory(s.memoryBytes),
                "--memory-swap", SandboxConfig.formatMemory(s.memoryBytes),
                "--stop-timeout", "5",
                "--pull=${s.pull}",
            )
            cfg.cgroupParent?.let { a += listOf("--cgroup-parent", it) }
            s.workdir?.let { a += listOf("--workdir", it) }
            s.entrypoint?.let { a += listOf("--entrypoint", it) }
            for ((k, v) in s.env) a += listOf("--env", "$k=$v")
            for (m in s.mounts) {
                a += listOf("--mount", buildString {
                    append(if (m.volume) "type=volume" else "type=bind")
                    append(",source=").append(m.source).append(",target=").append(m.target)
                    if (m.readOnly) append(",readonly=true")
                })
            }
            a += s.image
            a += s.command
            return a
        }

        fun execArgs(cfg: SandboxConfig, container: String, workdir: String, command: List<String>, user: ExecUser? = null): List<String> {
            val who = user?.let { u -> listOf("--user", "${u.uid}:${u.gid}") + u.env.flatMap { (k, v) -> listOf("--env", "$k=$v") } } ?: emptyList()
            return base(cfg) + listOf("exec") + who + listOf("--workdir", workdir, container) + command
        }

        fun logsArgs(cfg: SandboxConfig, name: String, lines: Int) = base(cfg) + listOf("logs", "--tail", lines.toString(), name)

        fun restartArgs(cfg: SandboxConfig, name: String) = base(cfg) + listOf("restart", "--time", "0", name)

        fun removeContainersArgs(cfg: SandboxConfig, names: List<String>) = base(cfg) + listOf("rm", "--force", "--ignore", "--volumes") + names

        fun removeNetworkArgs(cfg: SandboxConfig, name: String) = base(cfg) + listOf("network", "rm", "--force", name)

        fun listContainersArgs(cfg: SandboxConfig) =
            base(cfg) + listOf("ps", "--all", "--filter", "label=$LABEL_RUN", "--format", "{{.Names}}\t{{index .Labels \"$LABEL_RUN\"}}")

        /**
         * JSON, not a Go template: `network ls` cannot `index .Labels` (Podman 4.9: "cannot index slice/array with type
         * string"), unlike `ps`. The JSON (`name`, `labels`) is the same from Podman 4.3 on.
         */
        fun listNetworksArgs(cfg: SandboxConfig) =
            base(cfg) + listOf("network", "ls", "--filter", "label=$LABEL_RUN", "--format", "json")

        fun parseNetworksJson(out: String): List<LabelledResource> {
            val text = out.trim()
            if (text.isEmpty() || text == "null") return emptyList()
            val arr = try {
                kotlinx.serialization.json.Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray
            } catch (e: Exception) {
                null
            } ?: throw SandboxRuntimeException("podman network ls: unexpected output: ${text.take(500)}")
            return arr.mapNotNull { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val name = (o["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
                val run = ((o["labels"] as? kotlinx.serialization.json.JsonObject)?.get(LABEL_RUN) as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                LabelledResource(name, run)
            }
        }

        fun exposedPortsArgs(cfg: SandboxConfig, image: String) =
            base(cfg) + listOf("image", "inspect", "--format", "{{json .Config.ExposedPorts}}", image)

        fun imageExistsArgs(cfg: SandboxConfig, image: String) = base(cfg) + listOf("image", "exists", image)

        fun imageInspectArgs(cfg: SandboxConfig, images: List<String>) = base(cfg) + listOf("image", "inspect") + images

        fun pullArgs(cfg: SandboxConfig, image: String) = base(cfg) + listOf("pull", "--quiet", image)

        /** What a label value of a prepared image may contain (they all come from the server: names, hashes, references). */
        private val LABEL_VALUE_RE = Regex("^[A-Za-z0-9._:/@-]*$")
        private val LABEL_KEY_RE = Regex("^[a-z0-9][a-z0-9._-]*$")

        /**
         * `podman commit`: only the container's own file system (never `--include-volumes`, so neither the worktree bind
         * mount nor cache volumes), without `--pause`, the labels as `--change "LABEL k=v"`.
         */
        fun commitArgs(cfg: SandboxConfig, container: String, image: String, labels: Map<String, String>): List<String> {
            for ((k, v) in labels) {
                require(LABEL_KEY_RE.matches(k)) { "label key '$k' is not allowed" }
                require(LABEL_VALUE_RE.matches(v)) { "label value of '$k' is not allowed" }
            }
            return base(cfg) + listOf("commit", "--quiet") + labels.flatMap { (k, v) -> listOf("--change", "LABEL $k=$v") } + listOf(container, image)
        }

        fun listPreparedArgs(cfg: SandboxConfig) =
            base(cfg) + listOf("images", "--filter", "label=$LABEL_PREPARED", "--format", "{{.ID}}")

        fun removeImageArgs(cfg: SandboxConfig, id: String) = base(cfg) + listOf("rmi", id)

        /**
         * `podman image inspect` output: a JSON array of objects with `Id`, `Created` (RFC 3339), `Size`, `Labels` (or
         * `Config.Labels`) and `RepoTags`. Read tolerantly: an entry without id or time is skipped.
         */
        fun parseImagesJson(out: String): List<LocalImage> {
            val text = out.trim()
            if (text.isEmpty() || text == "null") return emptyList()
            val arr = try {
                kotlinx.serialization.json.Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray
            } catch (e: Exception) {
                null
            } ?: throw SandboxRuntimeException("podman image inspect: unexpected output: ${text.take(500)}")
            fun str(o: kotlinx.serialization.json.JsonObject?, k: String) =
                ((o?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString || it.content != "null" })?.content
            fun labels(el: kotlinx.serialization.json.JsonElement?) = (el as? kotlinx.serialization.json.JsonObject)?.mapNotNull { (k, v) ->
                (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { k to it }
            }?.toMap()
            return arr.mapNotNull { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val id = str(o, "Id")?.removePrefix("sha256:")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val created = str(o, "Created")?.let { c ->
                    runCatching { java.time.OffsetDateTime.parse(c).toInstant() }.getOrNull() ?: runCatching { java.time.Instant.parse(c) }.getOrNull()
                } ?: return@mapNotNull null
                val size = str(o, "Size")?.toLongOrNull() ?: 0L
                val lab = labels(o["Labels"])?.takeIf { it.isNotEmpty() }
                    ?: labels((o["Config"] as? kotlinx.serialization.json.JsonObject)?.get("Labels")) ?: emptyMap()
                val tags = (o["RepoTags"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
                LocalImage(id, created, size, lab, tags)
            }
        }

        fun formatCpus(c: Double): String = if (c == Math.floor(c)) c.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, c).trimEnd('0')

        fun parseLabelled(out: String): List<LabelledResource> = out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
            val parts = line.split('\t')
            val run = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() && it != "<no value>" } ?: return@mapNotNull null
            LabelledResource(parts[0].trim(), run)
        }.toList()
    }
}

const val LABEL_RUN = "agern.run"
const val LABEL_REPO = "agern.repo"
const val LABEL_ROLE = "agern.role"
/** Marks a prepared image (its value: the key); docs/ARCHITECTURE.md §5 "Prepared images". */
const val LABEL_PREPARED = "agern.prepared"
