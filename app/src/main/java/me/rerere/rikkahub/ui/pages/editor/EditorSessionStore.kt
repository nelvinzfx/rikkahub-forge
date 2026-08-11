package me.rerere.rikkahub.ui.pages.editor

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Disk I/O for the editor session: a JSON manifest (tab list + active tab) and
 * one draft file per dirty tab. Files beat Room here because a draft can hold
 * up to 5 MB of text — past SQLite's ~2 MB CursorWindow — and a single-writer
 * session document needs no queries. All writes go through tmp+rename so a
 * process death mid-write leaves the previous version intact. The base dir is
 * injectable so the store is unit-testable on the JVM with a temp dir.
 */
class EditorSessionStore(private val baseDir: File) {

    private val draftsDir: File get() = File(baseDir, "drafts")
    private val sessionFile: File get() = File(baseDir, "session.json")

    suspend fun loadSession(): EditorSession? = withContext(Dispatchers.IO) {
        runCatching {
            if (!sessionFile.isFile) return@withContext null
            EditorSessionCodec.decode(sessionFile.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    suspend fun saveSession(session: EditorSession) {
        withContext(Dispatchers.IO) {
            runCatching {
                baseDir.mkdirs()
                writeAtomically(sessionFile, EditorSessionCodec.encode(session))
            }
        }
    }

    suspend fun writeDraft(uri: String, text: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                draftsDir.mkdirs()
                writeAtomically(File(draftsDir, draftFileName(uri)), text)
            }
        }
    }

    suspend fun readDraft(uri: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(draftsDir, draftFileName(uri))
            if (file.isFile) file.readText(Charsets.UTF_8) else null
        }.getOrNull()
    }

    suspend fun deleteDraft(uri: String) {
        withContext(Dispatchers.IO) {
            runCatching { File(draftsDir, draftFileName(uri)).delete() }
        }
    }

    /** wipes manifest + all drafts (used when the user re-picks the tree) */
    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            runCatching { baseDir.deleteRecursively() }
        }
    }

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            // filesystem refused rename-over-existing; fall back to direct write
            target.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        fun create(context: Context): EditorSessionStore =
            EditorSessionStore(File(context.filesDir, "editor"))
    }
}
