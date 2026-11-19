package dk.egernet.factory

import dk.egernet.factory.sandbox.ParsedDefinition
import dk.egernet.factory.sandbox.SandboxDefinitions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `.agern/sandbox.yml` parsing and validation, and the FACTORY_SANDBOX* configuration. */
class SandboxDefinitionTest {
    private val cfg = SandboxConfig(mode = "podman")

    private fun valid(yaml: String, c: SandboxConfig = cfg) = assertIs<ParsedDefinition.Valid>(SandboxDefinitions.parse(yaml, c), yaml).definition
    private fun errors(yaml: String, c: SandboxConfig = cfg) = assertIs<ParsedDefinition.Invalid>(SandboxDefinitions.parse(yaml, c), yaml).errors

    @Test
    fun `a complete definition parses`() {
        val d = valid(
            """
            image: docker.io/library/eclipse-temurin:21-jdk
            setup: |
              apt-get update && apt-get install -y nodejs npm
            env: { CI: "true", RETRIES: 3, FLAG: true }
            services:
              postgres:
                image: docker.io/library/postgres:17
                env: { POSTGRES_PASSWORD: dev }
              cache-db:
                image: ghcr.io/example/redis@sha256:${"a".repeat(64)}
            caches: [ /root/.gradle, /root/.npm/ ]
            resources: { cpus: 1.5, memory: 2g }
            """.trimIndent(),
        )
        assertEquals("docker.io/library/eclipse-temurin:21-jdk", d.image)
        assertEquals("apt-get update && apt-get install -y nodejs npm\n", d.setup)
        assertEquals(mapOf("CI" to "true", "RETRIES" to "3", "FLAG" to "true"), d.env)
        assertEquals(listOf("postgres", "cache-db"), d.services.map { it.name })
        assertEquals(mapOf("POSTGRES_PASSWORD" to "dev"), d.services[0].env)
        assertEquals(listOf("/root/.gradle", "/root/.npm"), d.caches)
        assertEquals(1.5, d.cpus)
        assertEquals(2L shl 30, d.memoryBytes)
        assertFalse(d.capped)
    }

    @Test
    fun `only image is required and limits default to the server's`() {
        val d = valid("image: quay.io/fedora/fedora:41")
        assertNull(d.setup)
        assertTrue(d.env.isEmpty() && d.services.isEmpty() && d.caches.isEmpty())
        assertEquals(cfg.maxCpus, d.cpus)
        assertEquals(cfg.maxMemoryBytes, d.memoryBytes)
        assertNull(d.requestedCpus)
    }

    @Test
    fun `limits are capped by the server`() {
        val d = valid("image: docker.io/library/debian:12\nresources: { cpus: 16, memory: 64g }", cfg.copy(maxCpus = 2.0, maxMemoryBytes = 4L shl 30))
        assertEquals(2.0, d.cpus)
        assertEquals(4L shl 30, d.memoryBytes)
        assertEquals(16.0, d.requestedCpus)
        assertTrue(d.capped)
    }

    @Test
    fun `unknown keys are rejected with their path`() {
        val e = errors(
            """
            image: docker.io/library/debian:12
            privileged: true
            services:
              db:
                image: docker.io/library/postgres:17
                ports: [ "5432:5432" ]
            resources: { cpus: 1, disk: 10g }
            """.trimIndent(),
        )
        assertTrue(e.any { it.startsWith("privileged: unknown key") }, e.toString())
        assertTrue(e.any { it.startsWith("services.db.ports: unknown key") }, e.toString())
        assertTrue(e.any { it.startsWith("resources.disk: unknown key") }, e.toString())
    }

    @Test
    fun `images must be fully qualified and from an allowed registry`() {
        assertTrue(errors("image: debian:12").single().contains("not fully qualified"))
        assertTrue(errors("image: library/debian:12").single().contains("not fully qualified"))
        assertTrue(errors("image: registry.example.com/team/app:1").single().contains("registry 'registry.example.com' is not allowed"))
        assertTrue(errors("image: localhost:5000/app").single().contains("not allowed"))
        assertTrue(errors("image: docker.io/Library/Debian").single().contains("not a valid image reference"))
        assertTrue(errors("image: docker.io/library/debian:12 --privileged").single().contains("not a valid image reference"))
        // Allowed when the admin adds the registry.
        valid("image: registry.example.com:5000/team/app:1", cfg.copy(registries = listOf("registry.example.com:5000")))
        // Services are checked the same way.
        val e = errors("image: docker.io/library/debian:12\nservices:\n  db:\n    image: postgres:17")
        assertTrue(e.single().startsWith("services.db.image:") && e.single().contains("not fully qualified"), e.toString())
    }

    @Test
    fun `malformed values are reported, all at once`() {
        val e = errors(
            """
            setup: [ "a" ]
            env: { "1BAD": x }
            services:
              Bad_Name: { image: docker.io/library/postgres:17 }
              localhost: { image: docker.io/library/postgres:17 }
              noimage: {}
            caches: [ relative/path, /workspace/build, /root/../etc, /root/.m2, /root/.m2 ]
            resources: { cpus: -1, memory: lots }
            """.trimIndent(),
        )
        val joined = e.joinToString("\n")
        for (expected in listOf(
            "image: required", "setup: must be a single value", "env.1BAD: not a valid environment variable name",
            "services.Bad_Name:", "services.localhost:", "services.noimage.image: required",
            "caches[0]:", "caches[1]:", "caches[2]:", "caches[4]: '/root/.m2' is listed twice",
            "resources.cpus: must be a positive number", "resources.memory: must be a size",
        )) assertTrue(joined.contains(expected), "missing '$expected' in:\n$joined")
    }

    @Test
    fun `broken YAML, non-mappings and oversized files are invalid`() {
        assertTrue(errors("image: [unclosed").single().startsWith("Not valid YAML"))
        assertTrue(errors("- just\n- a list").single().contains("must be a mapping"))
        assertTrue(errors("image: docker.io/library/debian:12\nimage: docker.io/library/debian:13").single().startsWith("Not valid YAML"))
        assertTrue(errors("image: docker.io/library/debian:12\n#" + "x".repeat(70_000)).single().contains("larger than"))
        // Anchors and aliases are not accepted (no YAML bombs).
        assertTrue(errors("env: &e { A: b }\nimage: docker.io/library/debian:12\nx: *e").first().startsWith("Not valid YAML"))
    }

    @Test
    fun `the hash is the SHA-256 of the exact content`() {
        val a = SandboxDefinitions.parse("image: docker.io/library/debian:12\n", cfg).sha256
        val b = SandboxDefinitions.parse("image: docker.io/library/debian:12\n\n", cfg).sha256
        assertEquals(64, a.length)
        assertTrue(a != b)
        assertEquals(SandboxDefinitions.sha256("image: docker.io/library/debian:12\n"), a)
    }

    @Test
    fun `sandbox configuration from the environment`() {
        fun cfgOf(vararg kv: Pair<String, String>) = SandboxConfig.fromEnv(mapOf(*kv)::get)
        val d = cfgOf()
        assertEquals("off", d.mode)
        assertFalse(d.enabled)
        assertEquals(listOf("docker.io", "ghcr.io", "quay.io", "mcr.microsoft.com"), d.registries)
        assertEquals(2.0, d.maxCpus)
        assertEquals(4L shl 30, d.maxMemoryBytes)
        assertEquals(2, d.maxConcurrent)
        assertEquals(30 * 60_000L, d.execTimeoutMs)
        val c = cfgOf(
            "FACTORY_SANDBOX" to "podman", "FACTORY_SANDBOX_PODMAN" to "/usr/bin/podman", "FACTORY_SANDBOX_PODMAN_URL" to "unix:///run/agern/podman.sock",
            "FACTORY_SANDBOX_REGISTRIES" to "Docker.io, registry.example.com:5000", "FACTORY_SANDBOX_MAX_CPUS" to "1.5",
            "FACTORY_SANDBOX_MAX_MEMORY" to "1536m", "FACTORY_SANDBOX_MAX_CONCURRENT" to "1", "FACTORY_SANDBOX_EXEC_TIMEOUT_MINUTES" to "10",
        )
        assertTrue(c.enabled)
        assertEquals("unix:///run/agern/podman.sock", c.podmanUrl)
        assertEquals(listOf("docker.io", "registry.example.com:5000"), c.registries)
        assertEquals(1536L shl 20, c.maxMemoryBytes)
        assertEquals("1536m", SandboxConfig.formatMemory(c.maxMemoryBytes))
        assertEquals(10 * 60_000L, c.execTimeoutMs)
        assertFailsWith<IllegalArgumentException> { cfgOf("FACTORY_SANDBOX" to "docker") }
        assertFailsWith<IllegalArgumentException> { cfgOf("FACTORY_SANDBOX_MAX_MEMORY" to "1m") }
        assertFailsWith<IllegalArgumentException> { cfgOf("FACTORY_SANDBOX_MAX_CONCURRENT" to "0") }
        assertFailsWith<IllegalArgumentException> { cfgOf("FACTORY_SANDBOX_REGISTRIES" to "not a host") }
        assertEquals(null, d.cgroupParent)
        assertEquals("/system.slice/agern-sandbox.service/containers",
            cfgOf("FACTORY_SANDBOX_CGROUP_PARENT" to "/system.slice/agern-sandbox.service/containers").cgroupParent)
        for (bad in listOf("containers", "/a/../b", "/system.slice", "/a b", "/a/")) {
            assertFailsWith<IllegalArgumentException>(bad) { cfgOf("FACTORY_SANDBOX_CGROUP_PARENT" to bad) }
        }
    }
}
