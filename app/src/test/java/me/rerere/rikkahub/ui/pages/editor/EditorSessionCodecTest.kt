package me.rerere.rikkahub.ui.pages.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session manifest must be forward-compatible (unknown fields ignored,
 * defaults applied) and must NEVER crash on corrupt input — a bad file means a
 * cold start with no tabs, not an exception.
 */
class EditorSessionCodecTest {

    @Test
    fun roundTrip_preservesAllFields() {
        val session = EditorSession(
            tabs = listOf(
                SessionTab(
                    uri = "content://a/b/c",
                    name = "Main.kt",
                    readOnly = false,
                    dirty = true,
                    hasDraft = true,
                    diskLastModified = 1720000000000L,
                    diskLength = 12345L,
                    cursorLine = 12,
                    cursorColumn = 34,
                ),
                SessionTab(
                    uri = "content://a/b/d",
                    name = "README.md",
                    readOnly = true,
                ),
            ),
            activeUri = "content://a/b/d",
            expandedDirs = setOf("content://a/b"),
        )
        val decoded = EditorSessionCodec.decode(EditorSessionCodec.encode(session))
        assertEquals(session, decoded)
    }

    @Test
    fun decode_garbage_returnsNull() {
        assertNull(EditorSessionCodec.decode("this is not json {{{"))
    }

    @Test
    fun decode_emptyString_returnsNull() {
        assertNull(EditorSessionCodec.decode(""))
    }

    @Test
    fun decode_unknownKeys_ignored() {
        val raw = """
            {"version":99,"futureField":"x","tabs":[{"uri":"u","name":"n","newThing":1}],"activeUri":null}
        """.trimIndent()
        val decoded = EditorSessionCodec.decode(raw)
        assertNotNull(decoded)
        assertEquals(1, decoded!!.tabs.size)
        assertEquals("u", decoded.tabs[0].uri)
    }

    @Test
    fun decode_missingOptionalFields_appliesDefaults() {
        val raw = """{"tabs":[{"uri":"u","name":"n"}]}"""
        val decoded = EditorSessionCodec.decode(raw)
        assertNotNull(decoded)
        val tab = decoded!!.tabs[0]
        assertEquals(false, tab.readOnly)
        assertEquals(false, tab.dirty)
        assertEquals(false, tab.hasDraft)
        assertEquals(0L, tab.diskLastModified)
        assertEquals(-1L, tab.diskLength)
        assertNull(decoded.activeUri)
        assertEquals(1, decoded.version)
    }

    @Test
    fun encode_emptySession_decodesToEmpty() {
        val decoded = EditorSessionCodec.decode(EditorSessionCodec.encode(EditorSession()))
        assertNotNull(decoded)
        assertTrue(decoded!!.tabs.isEmpty())
        assertNull(decoded.activeUri)
    }

    @Test
    fun draftFileName_isStableAndHexOnly() {
        val a = draftFileName("content://com.android.externalstorage.documents/tree/primary%3A/document/primary%3Ax.txt")
        assertEquals(a, draftFileName("content://com.android.externalstorage.documents/tree/primary%3A/document/primary%3Ax.txt"))
        assertTrue(a.endsWith(".txt"))
        assertTrue(a.removeSuffix(".txt").all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(64, a.removeSuffix(".txt").length)
    }

    @Test
    fun draftFileName_distinctForDistinctUris() {
        assertTrue(draftFileName("content://a") != draftFileName("content://b"))
    }
}
