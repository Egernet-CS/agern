package dk.egernet.factory

import dk.egernet.factory.db.Db
import dk.egernet.factory.db.MigrationFiles
import dk.egernet.factory.pipeline.MigrationCheck
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The application's own migrations: unique versions, and a duplicate stops the start with a clear message. */
class MigrationFilesTest {
    @Test
    fun `the bundled migrations have unique versions and all apply to a fresh database`() {
        val files = MigrationFiles.list(Db.MIGRATIONS)
        assertTrue(files.size >= 16, files.toString())
        assertTrue(files.all { MigrationCheck.versionOf(it) != null }, "every bundled file is a versioned migration: $files")
        assertEquals(emptyList(), MigrationCheck.duplicates(files), "duplicate versions among $files")
        val db = TestDb.fresh()
        try {
            val applied = db.query("SELECT script FROM flyway_schema_history WHERE success AND version IS NOT NULL") { it.getString(1) }
            assertEquals(files.toSet(), applied.toSet())
        } finally {
            (db.dataSource as java.io.Closeable).close()
        }
    }

    @Test
    fun `starting against migrations with a duplicate version fails before Flyway, naming both files`() {
        val dir = Files.createTempDirectory("agern-migrations")
        try {
            Files.writeString(dir.resolve("V1__init.sql"), "CREATE TABLE a (id INT);\n")
            Files.writeString(dir.resolve("V11__claude_usage.sql"), "CREATE TABLE b (id INT);\n")
            Files.writeString(dir.resolve("V11__release_mode.sql"), "CREATE TABLE c (id INT);\n")
            val e = assertFailsWith<IllegalStateException> { TestDb.fresh(migrations = "filesystem:$dir") }
            assertEquals(
                "Duplicate migration version 11: V11__claude_usage.sql and V11__release_mode.sql. Renumber the one that was " +
                    "added later to a version above every other migration.",
                e.message,
            )
            // Renumbered: the same start succeeds.
            Files.move(dir.resolve("V11__release_mode.sql"), dir.resolve("V12__release_mode.sql"))
            val db = TestDb.fresh(migrations = "filesystem:$dir")
            (db.dataSource as java.io.Closeable).close()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
