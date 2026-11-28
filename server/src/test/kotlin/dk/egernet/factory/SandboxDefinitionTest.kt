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
    fun `files names project files the run needs`() {
        val d = valid("image: quay.io/fedora/fedora:41\nfiles: [ vendor/sdk.zip, licence.lic ]")
        assertEquals(listOf("vendor/sdk.zip", "licence.lic"), d.files)
        assertTrue(valid("image: quay.io/fedora/fedora:41").files.isEmpty())
        val errs = errors(
            "image: quay.io/fedora/fedora:41\nfiles: [ ok.zip, ../up, OK.zip, /abs ]",
        )
        assertTrue(errs.any { it.startsWith("files[1]: '../up' is not a project file name") }, errs.toString())
        assertTrue(errs.any { it == "files[2]: 'OK.zip' is listed twice" }, errs.toString())
        assertTrue(errs.any { it.startsWith("files[3]: '/abs'") }, errs.toString())
        assertTrue(errors("image: quay.io/fedora/fedora:41\nfiles: vendor/sdk.zip").single().startsWith("files: must be a list"))
        assertTrue(errors("image: quay.io/fedora/fedora:41\nfiles: [ ${(1..51).joinToString(", ") { "f$it" }} ]").contains("files: at most 50 project files"))
        // The mount point cannot be a cache.
        assertTrue(errors("image: quay.io/fedora/fedora:41\ncaches: [ /project-files/x ]").single().contains("may not be a cache"))
        // The key is part of the content hash: adding it needs a new approval.
        assertTrue(SandboxDefinitions.sha256("image: a\n") != SandboxDefinitions.sha256("image: a\nfiles: [ x ]\n"))
    }

    @Test
    fun `build chooses where the builder builds, sandbox by default`() {
        assertEquals("sandbox", valid("image: quay.io/fedora/fedora:41").build)
        assertFalse(valid("image: quay.io/fedora/fedora:41").buildOnHost)
        assertEquals("sandbox", valid("image: quay.io/fedora/fedora:41\nbuild:").build)
        assertEquals("sandbox", valid("image: quay.io/fedora/fedora:41\nbuild: sandbox").build)
        val host = valid("image: quay.io/fedora/fedora:41\nbuild: host")
        assertEquals("host", host.build)
        assertTrue(host.buildOnHost)
        for (bad in listOf("Host", "fast", "[host]", "{ a: b }")) {
            val errs = errors("image: quay.io/fedora/fedora:41\nbuild: $bad")
            assertEquals(1, errs.size, errs.toString())
            assertTrue(errs.single().startsWith("build:"), errs.toString())
        }
        assertEquals("build: must be `sandbox` or `host` (default: sandbox)", errors("image: quay.io/fedora/fedora:41\nbuild: fast").single())
        // The key is part of the content hash: adding or changing it needs a new approval.
        val base = "image: quay.io/fedora/fedora:41\n"
        val hashes = listOf(base, base + "build: host\n", base + "build: sandbox\n").map { SandboxDefinitions.parse(it, cfg).sha256 }
        assertEquals(3, hashes.toSet().size)
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

    @Test
    fun `platforms, a non-root user and readiness checks`() {
        val d = valid(
            """
            image: docker.io/library/eclipse-temurin:21-jdk
            setup: echo top
            user: builder
            services:
              db:
                image: docker.io/library/postgres:17
                ready: { command: "pg_isready -U postgres", timeoutSeconds: 90 }
              cache:
                image: docker.io/library/redis:7
                ready: { command: "redis-cli ping" }
            platforms:
              jdk17:
                image: docker.io/library/eclipse-temurin:17-jdk
              debian12:
                image: docker.io/library/debian:12
                setup: |
                  apt-get update
              bare:
                image: docker.io/library/alpine:3.20
                setup:
            """.trimIndent(),
        )
        assertEquals("builder", d.user!!.name)
        assertEquals(1000, d.user!!.uid)
        assertEquals(listOf("jdk17", "debian12", "bare"), d.platforms.map { it.name })
        assertEquals("echo top", d.platforms[0].setup)
        assertTrue(d.platforms[0].setupInherited)
        assertEquals("apt-get update\n", d.platforms[1].setup)
        assertFalse(d.platforms[1].setupInherited)
        assertNull(d.platforms[2].setup, "an empty setup: means none")
        assertEquals("pg_isready -U postgres", d.services[0].ready!!.command)
        assertEquals(90, d.services[0].ready!!.timeoutSeconds)
        assertEquals(60, d.services[1].ready!!.timeoutSeconds)
        assertEquals(1001, valid("image: docker.io/library/debian:12\nuser: 1001").user!!.uid)
        assertNull(valid("image: docker.io/library/debian:12\nuser: 1001").user!!.name)
        // A step-1 definition has neither.
        val old = valid("image: docker.io/library/debian:12")
        assertTrue(old.platforms.isEmpty() && old.user == null)
    }

    @Test
    fun `platforms, users and readiness checks are validated like the rest`() {
        val e = errors(
            """
            image: docker.io/library/debian:12
            user: root
            services:
              db:
                image: docker.io/library/postgres:17
                ready: { command: "", timeoutSeconds: 9999, interval: 2 }
              db2:
                image: docker.io/library/postgres:17
                ready: pg_isready
            platforms:
              default: { image: docker.io/library/debian:13 }
              Bad_Name: { image: docker.io/library/debian:13 }
              noimage: { setup: echo }
              unqualified: { image: debian:13 }
              other-registry: { image: registry.example.com/x:1 }
              extra: { image: docker.io/library/debian:13, env: { A: b } }
            """.trimIndent(),
        )
        val joined = e.joinToString("\n")
        for (expected in listOf(
            "user: 'root' is what commands run as", "services.db.ready.interval: unknown key", "services.db.ready.command: must not be empty",
            "services.db2.ready: must be a mapping", "platforms.default:", "platforms.Bad_Name:", "platforms.noimage.image: required",
            "platforms.unqualified.image: 'debian:13' is not fully qualified", "platforms.other-registry.image: registry 'registry.example.com' is not allowed",
            "platforms.extra.env: unknown key", "platforms: at most 4",
        )) assertTrue(joined.contains(expected), "missing '$expected' in:\n$joined")
        assertTrue(errors("image: docker.io/library/debian:12\nservices:\n  db:\n    image: docker.io/library/postgres:17\n    ready: { command: x, timeoutSeconds: 0 }")
            .single().contains("timeoutSeconds: a whole number of seconds between 1 and 600"))
        for (bad in listOf("0", "70000", "-1", "Bad User", "a:b")) {
            assertTrue(errors("image: docker.io/library/debian:12\nuser: \"$bad\"").single().startsWith("user:"), bad)
        }
        // The cap is the server's; 0 forbids platforms.
        val two = "image: docker.io/library/debian:12\nplatforms:\n  a: { image: docker.io/library/debian:13 }\n  b: { image: docker.io/library/debian:11 }"
        valid(two, cfg.copy(maxPlatforms = 2))
        assertTrue(errors(two, cfg.copy(maxPlatforms = 1)).single().contains("at most 1 on this server"))
        assertTrue(errors(two, cfg.copy(maxPlatforms = 0)).single().contains("at most 0"))
    }

    @Test
    fun `the platform cap from the environment`() {
        assertEquals(4, SandboxConfig.fromEnv(mapOf<String, String>()::get).maxPlatforms)
        assertEquals(0, SandboxConfig.fromEnv(mapOf("FACTORY_SANDBOX_MAX_PLATFORMS" to "0")::get).maxPlatforms)
        assertFailsWith<IllegalArgumentException> { SandboxConfig.fromEnv(mapOf("FACTORY_SANDBOX_MAX_PLATFORMS" to "21")::get) }
        assertFailsWith<IllegalArgumentException> { SandboxConfig.fromEnv(mapOf("FACTORY_SANDBOX_MAX_PLATFORMS" to "many")::get) }
    }

    @Test
    fun `Agern's own definition is valid`() {
        // Gradle runs the tests in server/; the file is at the repository root.
        val file = listOf(java.nio.file.Paths.get("../.agern/sandbox.yml"), java.nio.file.Paths.get(".agern/sandbox.yml")).first { java.nio.file.Files.exists(it) }
        val d = valid(java.nio.file.Files.readString(file))
        assertEquals("docker.io/library/eclipse-temurin:17-jdk", d.image, "the server's Gradle toolchain is JDK 17")
        assertEquals("ubuntu", d.user!!.name)
        assertEquals(listOf("postgres"), d.services.map { it.name })
        assertTrue(d.services[0].ready != null)
        assertTrue(d.caches.all { it.startsWith("/home/ubuntu/") }, d.caches.toString())
    }
}
