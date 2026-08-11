package me.rerere.rikkahub.ui.pages.editor

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * File-backed session store against a real temp dir: round-trips, corrupt-file
 * tolerance, draft lifecycle, and the wipe used when the user re-picks the tree.
 */
class EditorSessionStoreTest {

    private lateinit var baseDir: java.io.File
    private lateinit var store: EditorSessionStore

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("editor-session-test").toFile()
        store = EditorSessionStore(baseDir)
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    @Test
    fun loadSession_withoutFile_returnsNull() = runBlocking {
        assertNull(store.loadSession())
    }

    @Test
    fun saveThenLoad_roundTrips() = runBlocking {
        val session = EditorSession(
            tabs = listOf(
                SessionTab(uri = "u1", name = "A.kt", dirty = true, hasDraft = true),
                SessionTab(uri = "u2", name = "B.md"),
            ),
            activeUri = "u2",
        )
        store.saveSession(session)
        assertEquals(session, store.loadSession())
    }

    @Test
    fun saveSession_overwritesPrevious() = runBlocking {
        store.saveSession(EditorSession(tabs = listOf(SessionTab(uri = "u1", name = "A.kt"))))
        store.saveSession(EditorSession(tabs = emptyList(), activeUri = null))
        assertEquals(EditorSession(), store.loadSession())
    }

    @Test
    fun loadSession_corruptJson_returnsNull() = runBlocking {
        baseDir.mkdirs()
        java.io.File(baseDir, "session.json").writeText("{ not json", Charsets.UTF_8)
        assertNull(store.loadSession())
    }

    @Test
    fun draft_writeReadDelete() = runBlocking {
        val uri = "content://some/file"
        assertNull(store.readDraft(uri))
        val payload = "fun main() { println(\"hi\") }\n".repeat(1000)
        store.writeDraft(uri, payload)
        assertEquals(payload, store.readDraft(uri))
        store.deleteDraft(uri)
        assertNull(store.readDraft(uri))
    }

    @Test
    fun clearAll_wipesManifestAndDrafts() = runBlocking {
        store.saveSession(EditorSession(tabs = listOf(SessionTab(uri = "u", name = "n", hasDraft = true))))
        store.writeDraft("u", "draft body")
        store.clearAll()
        assertNull(store.loadSession())
        assertNull(store.readDraft("u"))
        assertFalse(baseDir.exists())
    }
}
