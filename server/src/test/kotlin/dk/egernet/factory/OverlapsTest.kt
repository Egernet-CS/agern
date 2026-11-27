package dk.egernet.factory

import dk.egernet.factory.pipeline.BlockedMarker
import dk.egernet.factory.pipeline.IncomingPulls
import dk.egernet.factory.pipeline.Overlaps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure rules of #35: issue references, spec paths, the blocked marker and the pull requests of incoming commits. */
class OverlapsTest {
    @Test
    fun `an issue reference is a whole token`() {
        assertTrue(Overlaps.references("Builds on #12.", 12))
        assertTrue(Overlaps.references("(#12)", 12))
        assertTrue(Overlaps.references("#12", 12))
        assertFalse(Overlaps.references("see #123", 12))
        assertFalse(Overlaps.references("org/repo#12", 12))
        assertFalse(Overlaps.references("a#12", 12))
        assertFalse(Overlaps.references(null, 12))
    }

    @Test
    fun `spec paths are backticked files with a directory`() {
        val spec = """
            Change `server/src/Main.kt:120` and `web/src/pages/Run.tsx`, not `README.md` (no directory), not
            `https://example.com/a.js`, `-v/x.txt`, `/api/runs/x.json`, `a dir/x.kt`, `src/noext` or `docs/`.
            Also `server/src/Main.kt` again.
        """.trimIndent()
        assertEquals(setOf("server/src/Main.kt", "web/src/pages/Run.tsx"), Overlaps.paths(spec))
        assertEquals(listOf("web/src/pages/Run.tsx"), Overlaps.sharedFiles(spec, "Touches `web/src/pages/Run.tsx` and `a/b.css`."))
        assertEquals(emptyList(), Overlaps.sharedFiles(spec, null))
        val many = (1..30).joinToString(" ") { "`d/f$it.kt`" }
        assertEquals(20, Overlaps.sharedFiles(many, many).size)
    }

    @Test
    fun `the blocked marker is the first non-empty line`() {
        val b = BlockedMarker.parse("\n**Blocked on:** #12, #15\n\nIt needs the API that #12 adds.\nSecond line.\n\nMore.")!!
        assertEquals(listOf(12, 15), b.issues)
        assertEquals("It needs the API that #12 adds. Second line.", b.reason)
        assertEquals(listOf(3), BlockedMarker.parse("blocked on: #3")!!.issues)
        assertEquals("", BlockedMarker.parse("Blocked on: #3")!!.reason)
        assertNull(BlockedMarker.parse("## Summary\nBlocked on: #3"))
        assertNull(BlockedMarker.parse("Blocked on: #3 and more"))
        assertNull(BlockedMarker.parse("Blocked on: 3"))
        assertNull(BlockedMarker.parse(null))
        assertEquals(500, BlockedMarker.parse("Blocked on: #1\n" + "x".repeat(900))!!.reason.length)
    }

    @Test
    fun `pull requests come from squash and merge subjects`() {
        val subjects = listOf(
            "Fix the thing (#12)", "Merge pull request #14 from org/branch", "Direct push", "Fix the thing again (#12)",
            "Mentions (#13) in the middle only", "Tail (#13) ",
        )
        assertEquals(listOf(12 to "Fix the thing (#12)", 14 to "Merge pull request #14 from org/branch", 13 to "Tail (#13) "), IncomingPulls.numbers(subjects))
        assertEquals(10, IncomingPulls.numbers((1..20).map { "c (#$it)" }).size)
    }
}
