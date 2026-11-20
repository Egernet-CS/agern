package dk.egernet.factory.sandbox

/** A mount into a container: a host directory ([volume] false) or a named volume ([volume] true). */
data class SandboxMount(val source: String, val target: String, val volume: Boolean, val readOnly: Boolean = false)

/** Everything the server decides about one container. Agents never choose any of it. */
data class ContainerSpec(
    val name: String,
    val image: String,
    val labels: Map<String, String>,
    val network: String,
    /** The DNS name other containers on [network] reach it under (sidecars: the service name). */
    val networkAlias: String? = null,
    val env: Map<String, String> = emptyMap(),
    val mounts: List<SandboxMount> = emptyList(),
    val workdir: String? = null,
    val cpus: Double,
    val memoryBytes: Long,
    val pidsLimit: Int = 4096,
    /** Replaces the image's entrypoint (the sandbox: `sh`, so images with entrypoint scripts still idle); null = keep it. */
    val entrypoint: String? = null,
    /** Command (arguments of the entrypoint); empty = the image's own (sidecars). */
    val command: List<String> = emptyList(),
    /**
     * The uid that the service user on the host becomes inside the container (`--userns keep-id:uid=<n>,gid=<n>`),
     * when the definition has `user:`; null keeps rootless Podman's default (container root = the service user).
     */
    val keepIdUid: Int? = null,
    /** `--user` for the container's main process; null = the image's. */
    val user: String? = null,
)

/** Who a command runs as inside the container: `--user <uid>:<gid>` plus HOME, USER and LOGNAME. */
data class ExecUser(val uid: Int, val gid: Int, val name: String, val home: String) {
    val env: Map<String, String> get() = linkedMapOf("HOME" to home, "USER" to name, "LOGNAME" to name)

    companion object {
        /** Container root, for setup and the server's own preparation steps. */
        val ROOT = ExecUser(0, 0, "root", "/root")
    }
}

data class ExecResult(
    /** -1 when the command was stopped by the time limit. */
    val exitCode: Int,
    /** stdout and stderr interleaved, at most [ExecLimits.LOG_CHARS] characters (the tail). */
    val output: String,
    /** True when [output] lost its beginning because the command wrote more than the log keeps. */
    val outputTruncated: Boolean,
    val timedOut: Boolean,
    val durationMs: Long,
)

/** A container or network the runtime found carrying the `agern.run` label. */
data class LabelledResource(val name: String, val runId: String)

object ExecLimits {
    /** How much of a command's output is kept for the transcript log. */
    const val LOG_CHARS = 1_000_000
}

/**
 * The container runtime behind sandboxes. [PodmanRuntime] drives the `podman` CLI; tests use a fake. Every method
 * blocks (call it on [kotlinx.coroutines.Dispatchers.IO]) and throws [SandboxRuntimeException] when the runtime fails.
 */
interface SandboxRuntime {
    fun createNetwork(name: String, labels: Map<String, String>)
    /** Creates the named volume unless it exists. */
    fun ensureVolume(name: String, labels: Map<String, String>)
    /** Pulls the image if needed and starts the container detached. */
    fun startContainer(spec: ContainerSpec)
    /**
     * Runs [command] in [container] and waits up to [timeoutMs]; on timeout the client is stopped and [ExecResult.timedOut]
     * is set. [user] null: the container's default user (root in a sandbox).
     */
    fun exec(container: String, command: List<String>, workdir: String, timeoutMs: Long, user: ExecUser? = null): ExecResult
    /** Kills every process in the container and starts it again (files are kept). Used after a command timed out. */
    fun restartContainer(name: String)
    /** Force-removes the containers; missing ones are ignored. */
    fun removeContainers(names: List<String>)
    /** Removes the network; a missing one is ignored. */
    fun removeNetwork(name: String)
    fun listContainers(): List<LabelledResource>
    fun listNetworks(): List<LabelledResource>
    /** The last [lines] lines of the container's output (a service that did not become ready); empty when unknown. */
    fun logs(name: String, lines: Int): String
    /** Ports the image declares (`EXPOSE`), e.g. `5432/tcp`; empty when unknown. */
    fun exposedPorts(image: String): List<String>
}

class SandboxRuntimeException(message: String) : RuntimeException(message)
