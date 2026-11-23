package dk.egernet.factory

import dk.egernet.factory.git.FileChange
import dk.egernet.factory.pipeline.MigrationCheck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure rules for colliding database migrations (docs/ARCHITECTURE.md §6 "Database migrations"). */
class MigrationCheckTest {
    private val dir = "server/src/main/resources/db/migration"
    private fun v(name: String) = MigrationCheck.versionOf(name)!!
    private fun added(vararg paths: String) = paths.map { FileChange('A', it) }

    @Test
    fun `versions compare as Flyway compares them`() {
        assertEquals(v("V11__a.sql"), v("V011__b.sql"))
        assertEquals(v("V1__a.sql"), v("V1.0__a.sql"))
        assertEquals(v("V1_2__a.sql"), v("V1.2__a.sql"))
        assertTrue(v("V9__a.sql") < v("V10__a.sql"))
        assertTrue(v("V1_2__a.sql") < v("V1.10__a.sql"))
        assertTrue(v("V99999999999999999999999__a.sql") > v("V99999999999999999999998__a.sql"), "no overflow")
        assertEquals("16", v("$dir/V16__x.sql").text)
        for (name in listOf("R__view.sql", "v1__a.sql", "V1_a.sql", "V1__a.txt", "V__a.sql", "Va__b.sql")) {
            assertNull(MigrationCheck.versionOf(name), name)
        }
    }

    @Test
    fun `the known layout is src-main-resources-db-migration at the root or in a module`() {
        assertEquals("src/main/resources/db/migration", MigrationCheck.rootOf("src/main/resources/db/migration/V1__a.sql"))
        assertEquals(dir, MigrationCheck.rootOf("$dir/V1__a.sql"))
        assertEquals(dir, MigrationCheck.rootOf("$dir/sub/V1__a.sql"))
        assertNull(MigrationCheck.rootOf("db/migration/V1__a.sql"))
        assertNull(MigrationCheck.rootOf("server/src/test/resources/db/migration/V1__a.sql"))
        assertNull(MigrationCheck.rootOf("docs/V1__a.sql"))
    }

    @Test
    fun `duplicates name both files`() {
        assertEquals(
            listOf("duplicate migration version 11: V11__claude_usage.sql and V11__release_mode.sql"),
            MigrationCheck.duplicates(listOf("V10__x.sql", "V11__release_mode.sql", "V11__claude_usage.sql", "notes.txt")),
        )
        assertEquals(listOf("duplicate migration version 7: V07__a.sql and V7__b.sql"), MigrationCheck.duplicates(listOf("V7__b.sql", "V07__a.sql")))
        // Different migration roots do not collide.
        assertEquals(emptyList(), MigrationCheck.duplicates(listOf("a/$ROOT/V1__x.sql", "b/$ROOT/V1__x.sql")))
    }

    @Test
    fun `an added migration with a version on the base is a duplicate naming both files`() {
        val base = listOf("$dir/V15__earlier.sql", "$dir/V16__other.sql", "README.md")
        assertEquals(
            listOf("`$dir/V16__mine.sql` has the same version (16) as `$dir/V16__other.sql`"),
            MigrationCheck.problems(base, added("$dir/V16__mine.sql")),
        )
        // Two additions with one version.
        assertEquals(
            listOf("`$dir/V17__b.sql` has the same version (17) as `$dir/V17__a.sql`"),
            MigrationCheck.problems(base, added("$dir/V17__a.sql", "$dir/V17__b.sql")),
        )
    }

    @Test
    fun `an added migration below the base's highest is out of order`() {
        val base = listOf("$dir/V1__init.sql", "$dir/V5__five.sql", "$dir/V10__ten.sql")
        assertEquals(
            listOf("`$dir/V9__mine.sql` (version 9) is not higher than `$dir/V10__ten.sql` (version 10)"),
            MigrationCheck.problems(base, added("$dir/V9__mine.sql")),
        )
        assertEquals(emptyList(), MigrationCheck.problems(base, added("$dir/V11__mine.sql")))
        assertEquals(emptyList(), MigrationCheck.problems(emptyList(), added("$dir/V1__first.sql")))
    }

    @Test
    fun `a base migration that is changed, removed or renamed is reported`() {
        val base = listOf("$dir/V1__init.sql", "$dir/V2__next.sql")
        assertEquals(listOf("`$dir/V1__init.sql` was changed"), MigrationCheck.problems(base, listOf(FileChange('M', "$dir/V1__init.sql"))))
        assertEquals(listOf("`$dir/V2__next.sql` was removed"), MigrationCheck.problems(base, listOf(FileChange('D', "$dir/V2__next.sql"))))
        // A rename is a removal plus an addition: the new name is checked as an addition (V3 is fine).
        assertEquals(
            listOf("`$dir/V2__next.sql` was removed"),
            MigrationCheck.problems(base, listOf(FileChange('D', "$dir/V2__next.sql"), FileChange('A', "$dir/V3__next.sql"))),
        )
    }

    @Test
    fun `other files, other layouts and unrelated changes are ignored`() {
        val base = listOf("$dir/V5__five.sql", "src/main/resources/db/migration/V5__root.sql")
        val changes = listOf(
            FileChange('A', "$dir/R__view.sql"), FileChange('M', "$dir/notes.md"), FileChange('A', "db/migration/V1__elsewhere.sql"),
            FileChange('A', "server/src/test/resources/db/migration/V1__test.sql"), FileChange('M', "README.md"),
        )
        assertEquals(emptyList(), MigrationCheck.problems(base, changes))
        // Each root is checked on its own: V6 at the root is above the root's V5 and unaffected by the module's.
        assertEquals(emptyList(), MigrationCheck.problems(base, added("src/main/resources/db/migration/V6__root.sql")))
        // A file added on both sides under the same name is git's business (same name, so not a version clash).
        assertEquals(emptyList(), MigrationCheck.problems(base, added("$dir/V5__five.sql")))
    }

    @Test
    fun `paths are made safe for GitHub text and long lists are cut`() {
        val evil = "$dir/V1__a`@x\nb.sql"
        val p = MigrationCheck.problems(listOf("$dir/V1__base.sql"), added(evil)).single()
        assertEquals("`$dir/V1__a??x?b.sql` has the same version (1) as `$dir/V1__base.sql`", p)
        val many = (1..7).map { "problem $it" }
        assertEquals((1..5).joinToString("; ") { "problem $it" } + " (and 2 more)", MigrationCheck.listed(many))
        val reason = MigrationCheck.runReason("release/Summer", 12, listOf(p))
        assertTrue(reason.startsWith("Database migration conflict with `release/Summer`: `$dir/V1__a??x?b.sql` has the same version"), reason)
        assertTrue(reason.contains("Pull request #12 was not merged."), reason)
    }

    private companion object {
        const val ROOT = MigrationCheck.ROOT
    }
}
