package dk.egernet.factory.sandbox

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.name

/**
 * `FACTORY_SANDBOX=fake` (development and end-to-end tests only, together with FACTORY_DEV_FAKE_GITHUB): a runtime that
 * keeps its "containers" in memory and only pretends to run commands, so the sandbox UI can be developed and tested
 * without Podman. Nothing is executed: a command is split at `&&`, `;` and newlines and each part is answered from a
 * tiny repertoire — `echo …` prints its words, `ls` lists the worktree's top level (`ls /project-files` the files of the
 * project files mount), `seq N` prints 1..N (long output),
 * `sleep N` waits (up to the time limit), `exit N` / `false` end with that exit code; anything else prints a line saying
 * what would have run.
 */
class DevFakeSandboxRuntime : SandboxRuntime {
    private val containers = ConcurrentHashMap<String, ContainerSpec>()
    private val networks = ConcurrentHashMap<String, String>()

    override fun createNetwork(name: String, labels: Map<String, String>) {
        networks[name] = labels[LABEL_RUN] ?: ""
    }

    override fun ensureVolume(name: String, labels: Map<String, String>) {}

    override fun startContainer(spec: ContainerSpec) {
        Thread.sleep(150)
        containers[spec.name] = spec
    }

    override fun exec(container: String, command: List<String>, workdir: String, timeoutMs: Long, user: ExecUser?): ExecResult {
        val spec = containers[container] ?: throw SandboxRuntimeException("no such container: $container")
        // The server's own steps (SandboxManager.PREPARE_USER_SCRIPT, HAND_OVER_SCRIPT).
        when (command.getOrNull(3)) {
            "agern-user" -> command[5].ifEmpty { "sandbox" }.let { return ExecResult(0, "AGERN_USER $it /home/$it\n", false, false, 1) }
            "agern-hand-over" -> return ExecResult(0, "", false, false, 1)
        }
        val script = command.last()
        val started = System.currentTimeMillis()
        val out = StringBuilder()
        var exit = 0
        for (part in script.split(Regex("&&|;|\\n")).map { it.trim() }.filter { it.isNotEmpty() }) {
            val words = part.split(Regex("\\s+"))
            when (words[0]) {
                "echo" -> out.append(words.drop(1).joinToString(" ") { it.trim('"', '\'') }).append('\n')
                "ls" -> out.append(if (words.getOrNull(1)?.trimEnd('/') == SandboxManager.PROJECT_FILES) listProjectFiles(spec) else listWorktree(spec)).append('\n')
                "seq" -> (1..(words.getOrNull(1)?.toIntOrNull() ?: 10).coerceIn(0, 200_000)).forEach { out.append(it).append('\n') }
                "sleep" -> {
                    val ms = ((words.getOrNull(1)?.toDoubleOrNull() ?: 1.0) * 1000).toLong()
                    if (ms >= timeoutMs) {
                        Thread.sleep(timeoutMs)
                        return ExecResult(-1, out.toString(), false, true, System.currentTimeMillis() - started)
                    }
                    Thread.sleep(ms)
                }
                "exit" -> { exit = words.getOrNull(1)?.toIntOrNull() ?: 0; break }
                "false" -> { exit = 1; break }
                "true" -> {}
                else -> out.append("[fake sandbox ${spec.image}] would run: ").append(part).append('\n')
            }
        }
        val text = out.toString()
        val truncated = text.length > ExecLimits.LOG_CHARS
        return ExecResult(exit, if (truncated) text.takeLast(ExecLimits.LOG_CHARS) else text, truncated, false, System.currentTimeMillis() - started)
    }

    private fun listWorktree(spec: ContainerSpec): String {
        val src = spec.mounts.firstOrNull { !it.volume && it.target == SandboxManager.WORKDIR }?.source ?: return ""
        return runCatching { Files.list(Path.of(src)).use { s -> s.map { it.name }.sorted().toList() } }.getOrDefault(emptyList()).joinToString("\n")
    }

    /** `ls /project-files`: the files of the mount at [SandboxManager.PROJECT_FILES], as relative paths (recursively). */
    private fun listProjectFiles(spec: ContainerSpec): String {
        val src = spec.mounts.firstOrNull { !it.volume && it.target == SandboxManager.PROJECT_FILES }?.source
            ?: return "ls: cannot access '${SandboxManager.PROJECT_FILES}': No such file or directory"
        val root = Path.of(src)
        return runCatching {
            Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) }.map { root.relativize(it).toString() }.sorted().toList() }
        }.getOrDefault(emptyList()).joinToString("\n")
    }

    override fun restartContainer(name: String) {}

    override fun logs(name: String, lines: Int) = ""

    override fun removeContainers(names: List<String>) {
        names.forEach { containers.remove(it) }
    }

    override fun removeNetwork(name: String) {
        networks.remove(name)
    }

    override fun listContainers() = containers.values.map { LabelledResource(it.name, it.labels[LABEL_RUN] ?: "") }

    override fun listNetworks() = networks.map { (n, r) -> LabelledResource(n, r) }

    override fun exposedPorts(image: String) = if (image.contains("postgres")) listOf("5432/tcp") else emptyList()
}
