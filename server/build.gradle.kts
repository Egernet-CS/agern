plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    application
    id("com.gradleup.shadow") version "8.3.6"
}

group = "dk.egernet.factory"
// Release version: ./gradlew -Pversion=1.2.3 ... (reported by /api/health)
if (version.toString() == "unspecified") version = "0.0.0-dev"

repositories {
    mavenCentral()
}

val ktorVersion = "3.1.3"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    implementation("org.postgresql:postgresql:42.7.5")
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.flywaydb:flyway-core:11.8.2")
    implementation("org.flywaydb:flyway-database-postgresql:11.8.2")

    implementation("com.unboundid:unboundid-ldapsdk:7.0.2")
    implementation("org.kohsuke:libpam4j:1.11")
    // libpam4j depends on an old JNA without arm64 (Apple silicon) binaries
    implementation("net.java.dev.jna:jna:5.17.0")

    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-websockets:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.zonky.test:embedded-postgres:2.1.0")
    testImplementation(enforcedPlatform("io.zonky.test.postgres:embedded-postgres-binaries-bom:16.6.0"))
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("dk.egernet.factory.MainKt")
}

tasks.processResources {
    val v = project.version.toString()
    inputs.property("version", v)
    filesMatching("agern-version.txt") { expand("version" to v) }
    // Embed a pre-built frontend: ./gradlew :server:shadowJar -PwebDist=../web/dist
    // (the Gradle build never runs npm itself).
    val webDist = findProperty("webDist")?.toString()
    if (webDist != null) {
        val dir = file(webDist)
        require(dir.resolve("index.html").isFile) { "-PwebDist=$webDist does not contain index.html (build the frontend first)" }
        inputs.dir(dir)
        from(dir) { into("web") }
    }
}

tasks.shadowJar {
    archiveFileName.set("agern.jar")
    archiveClassifier.set("")
    mergeServiceFiles()
    manifest { attributes("Main-Class" to "dk.egernet.factory.MainKt", "Implementation-Version" to project.version.toString()) }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.processTestResources {
    // keep the fake CLI executable when copied into build/resources
    filesMatching("fake-claude") { permissions { unix("rwxr-xr-x") } }
}
