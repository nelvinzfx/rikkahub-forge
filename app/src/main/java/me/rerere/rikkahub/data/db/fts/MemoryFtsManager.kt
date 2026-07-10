package me.rerere.rikkahub.data.db.fts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.MemoryEntity

const val MEMORY_FTS_CREATE_SQL = """
    CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(
        title,
        content,
        tags,
        memory_id UNINDEXED,
        assistant_id UNINDEXED,
        tokenize = 'unicode61'
    )
"""

data class MemorySearchHit(
    val id: Int,
    val title: String,
    val content: String,
    val mode: String,
    val tags: String,
    val importance: Int,
    val updatedAt: Long,
    val sourceConversationId: String?,
    val score: Double,
    val snippet: String,
)

class MemoryFtsManager(private val database: AppDatabase) {
    private val db get() = database.openHelper.writableDatabase

    suspend fun index(memory: MemoryEntity) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM memory_fts WHERE memory_id = ?", arrayOf(memory.id))
        if (!memory.archived) {
            db.execSQL(
                "INSERT INTO memory_fts(title, content, tags, memory_id, assistant_id) VALUES (?, ?, ?, ?, ?)",
                arrayOf(memory.title, memory.content, memory.tags, memory.id, memory.assistantId),
            )
        }
    }

    suspend fun delete(id: Int) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM memory_fts WHERE memory_id = ?", arrayOf(id))
    }

    suspend fun deleteScope(assistantId: String) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM memory_fts WHERE assistant_id = ?", arrayOf(assistantId))
    }

    suspend fun search(assistantId: String, query: String, limit: Int): List<MemorySearchHit> =
        withContext(Dispatchers.IO) {
            val hits = mutableListOf<MemorySearchHit>()
            val cursor = db.query(
                """
                SELECT m.id, m.title, m.content, m.mode, m.tags, m.importance,
                       m.updated_at, m.source_conversation_id, bm25(memory_fts, 3.0, 1.0, 1.5) AS score,
                       snippet(memory_fts, 1, '[', ']', '…', 24) AS matched_snippet
                FROM memory_fts
                JOIN MemoryEntity m ON m.id = CAST(memory_fts.memory_id AS INTEGER)
                WHERE memory_fts MATCH ? AND memory_fts.assistant_id = ? AND m.archived = 0
                ORDER BY score ASC, m.importance DESC, m.updated_at DESC
                LIMIT ?
                """.trimIndent(),
                arrayOf(toFtsQuery(query), assistantId, limit.coerceIn(1, 100)),
            )
            cursor.use {
                while (it.moveToNext()) {
                    hits += MemorySearchHit(
                        id = it.getInt(0), title = it.getString(1), content = it.getString(2),
                        mode = it.getString(3), tags = it.getString(4), importance = it.getInt(5),
                        updatedAt = it.getLong(6), sourceConversationId = it.getString(7),
                        score = it.getDouble(8), snippet = it.getString(9),
                    )
                }
            }
            hits
        }

    suspend fun rebuild(memories: List<MemoryEntity>) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM memory_fts")
        memories.filterNot { it.archived }.forEach { memory ->
            db.execSQL(
                "INSERT INTO memory_fts(title, content, tags, memory_id, assistant_id) VALUES (?, ?, ?, ?, ?)",
                arrayOf(memory.title, memory.content, memory.tags, memory.id, memory.assistantId),
            )
        }
    }

    private fun toFtsQuery(input: String): String = input
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .joinToString(" AND ") { token -> "\"${token.replace("\"", "\"\"")}\"*" }
}
