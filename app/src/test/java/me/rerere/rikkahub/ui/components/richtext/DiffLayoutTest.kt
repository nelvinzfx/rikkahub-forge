package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Test

class DiffLayoutTest {
    @Test
    fun hunkLinesCarryOldAndNewGutterNumbers() {
        val diff = """
            --- a/f.txt
            +++ b/f.txt
            @@ -10,3 +10,3 @@
             ctx
            -old
            +new
        """.trimIndent()
        val lines = layoutDiffLines(diff, showFileHeader = true)

        assertEquals(DiffLineKind.META, lines[0].kind)
        assertEquals(DiffLineKind.META, lines[1].kind)
        assertEquals(DiffLineKind.HUNK, lines[2].kind)
        assertEquals("…", lines[2].text)
        assertEquals(DiffLineKind.CONTEXT, lines[3].kind)
        assertEquals("10", lines[3].gutter)
        assertEquals("ctx", lines[3].text)
        assertEquals(DiffLineKind.DELETE, lines[4].kind)
        assertEquals("11", lines[4].gutter)
        assertEquals("old", lines[4].text)
        assertEquals(DiffLineKind.ADD, lines[5].kind)
        assertEquals("11", lines[5].gutter)
        assertEquals("new", lines[5].text)
    }

    @Test
    fun laterHunksRestartFromTheirOwnHeaderNumbers() {
        val diff = """
            @@ -1,2 +1,2 @@
            -a
            +b
            @@ -40,2 +42,2 @@
             keep
            +added
        """.trimIndent()
        val lines = layoutDiffLines(diff, showFileHeader = false)

        assertEquals(DiffLineKind.HUNK, lines[0].kind)
        assertEquals("1", lines[1].gutter)
        assertEquals("1", lines[2].gutter)
        assertEquals(DiffLineKind.HUNK, lines[3].kind)
        assertEquals("40", lines[4].gutter)
        assertEquals(DiffLineKind.CONTEXT, lines[4].kind)
        assertEquals("43", lines[5].gutter)
        assertEquals(DiffLineKind.ADD, lines[5].kind)
    }

    @Test
    fun fileHeaderIsDroppedOnlyWhenRequested() {
        val diff = "--- a/f\n+++ b/f\n@@ -1 +1 @@\n-x\n+y"
        assertEquals(5, layoutDiffLines(diff, showFileHeader = true).size)
        val hidden = layoutDiffLines(diff, showFileHeader = false)
        assertEquals(3, hidden.size)
        assertEquals(DiffLineKind.HUNK, hidden[0].kind)
    }

    @Test
    fun linesOutsideHunksHaveNoGutterNumbers() {
        val diff = "diff --git a/x b/x\n@@ not-a-header\nplain"
        val lines = layoutDiffLines(diff, showFileHeader = false)
        assertEquals(DiffLineKind.META, lines[0].kind)
        assertEquals("", lines[0].gutter)
        assertEquals(DiffLineKind.META, lines[1].kind)
        assertEquals(DiffLineKind.META, lines[2].kind)
    }
}
