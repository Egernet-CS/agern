package dk.egernet.factory.db

import dk.egernet.factory.pipeline.MigrationCheck
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Path

/**
 * The migration files of a Flyway location (`classpath:<dir>` in a directory or a jar, or `filesystem:<dir>`), read
 * without a database, so a broken set fails with a clear message before Flyway runs (docs/ARCHITECTURE.md §6
 * "Database migrations").
 */
object MigrationFiles {
    /** The names of the files at or below [location], sorted. */
    fun list(location: String, loader: ClassLoader = MigrationFiles::class.java.classLoader): List<String> {
        val names = mutableListOf<String>()
        when {
            location.startsWith("filesystem:") -> names += walk(Path.of(location.removePrefix("filesystem:")))
            else -> {
                val dir = location.removePrefix("classpath:").trim('/')
                for (url in loader.getResources(dir).toList()) {
                    when (url.protocol) {
                        "file" -> names += walk(Path.of(url.toURI()))
                        "jar" -> (url.openConnection() as JarURLConnection).apply { useCaches = false }.jarFile.use { jar ->
                            jar.entries().toList().filter { !it.isDirectory && it.name.startsWith("$dir/") }
                                .forEach { names += it.name.substringAfterLast('/') }
                        }
                    }
                }
            }
        }
        return names.sorted()
    }

    private fun walk(dir: Path): List<String> =
        if (!Files.isDirectory(dir)) emptyList()
        else Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) }.map { it.fileName.toString() }.toList() }

    /**
     * Throws [IllegalStateException] when two versioned migrations at [location] share a version, naming both files:
     * `Duplicate migration version 11: V11__a.sql and V11__b.sql. Renumber the one that was added later to a version
     * above every other migration.`
     */
    fun requireUniqueVersions(location: String) {
        val dups = MigrationCheck.duplicates(list(location))
        check(dups.isEmpty()) {
            dups.joinToString("; ") { it.replaceFirstChar(Char::uppercase) } +
                ". Renumber the one that was added later to a version above every other migration."
        }
    }
}
