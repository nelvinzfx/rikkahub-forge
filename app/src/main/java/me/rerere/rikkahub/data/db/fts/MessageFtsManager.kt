package me.rerere.rikkahub.data.db.fts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import java.time.Instant

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
)

/**
 * One conversation-level content match produced by [MessageFtsManager.searchConversationRecall]
 * for the agent-facing `search_conversations` tool.
 *
 * [rawRank] is the FTS5 bm25 value (negative, lower = better); callers normalize it through
 * [me.rerere.rikkahub.data.search.RecallScore] instead of interpreting it directly.
 */
data class ConversationRecallHit(
    val conversationId: String,
    val title: String,
    val updateAt: Long,
    val snippet: String,
    val rawRank: Double,
)

enum class MessageSearchSort(val orderBy: String) {
    RELEVANCE("rank, update_at DESC"),
    NEWEST_FIRST("update_at DESC, rank"),
    OLDEST_FIRST("update_at ASC, rank"),
}

private const val TAG = "MessageFtsManager"

/**
 * Schema for the message_fts FTS5 virtual table. Defined here so the table-init path in
 * DataSourceModule and the Doctor's "rebuild search index" repair path use the same DDL.
 * If the columns ever change, both the CREATE in DataSourceModule and the INSERT in
 * [MessageFtsManager.indexConversation] need updating in lock-step.
 */
const val MESSAGE_FTS_CREATE_SQL = """
    CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
        text,
        node_id UNINDEXED,
        message_id UNINDEXED,
        conversation_id UNINDEXED,
        title UNINDEXED,
        update_at UNINDEXED,
        tokenize = 'simple'
    )
"""

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    /**
     * Drop and recreate the message_fts virtual table. Use this when SQLite reports
     * a malformed inverted index (PRAGMA integrity_check) — DELETE-from-FTS5 doesn't
     * free corrupted index pages, only DROP TABLE does. Safe because message_fts is a
     * standalone search projection; the actual content lives in `messages` and gets
     * reinserted by the caller (see [me.rerere.rikkahub.data.repository.ConversationRepository.rebuildAllIndexes]).
     */
    suspend fun dropAndRecreate() = withContext(Dispatchers.IO) {
        db.execSQL("DROP TABLE IF EXISTS message_fts")
        db.execSQL(MESSAGE_FTS_CREATE_SQL.trimIndent())
    }

    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        val conversationId = conversation.id.toString()
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
        conversation.messageNodes.forEach { node ->
            node.messages.forEach { message ->
                val text = message.extractFtsText()
                if (text.isNotBlank()) {
                    db.execSQL(
                        "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            text,
                            node.id.toString(),
                            message.id.toString(),
                            conversationId,
                            conversation.title,
                            conversation.updateAt.toEpochMilli().toString(),
                        )
                    )
                }
            }
        }
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts")
    }

    /**
     * Conversation-level recall over the same message_fts index the human search screen uses,
     * so the agent's `search_conversations` tool stops full-scanning `json_each(node.messages)`
     * with LIKE. Returns at most one row per conversation: the best-ranked matching message,
     * with the FTS5 snippet around it.
     *
     * [fallbackTerms] restores the OR-style multi-term recall the old LIKE query had: the
     * primary MATCH goes through `jieba_query`, whose multi-word expressions are conjunctive,
     * so if a multi-word query matches nothing we retry the individual planned terms and keep
     * the best rank per conversation.
     */
    suspend fun searchConversationRecall(
        keyword: String,
        limit: Int,
        fallbackTerms: List<String> = emptyList(),
    ): List<ConversationRecallHit> = withContext(Dispatchers.IO) {
        val candidateLimit = limit.coerceIn(1, 1000)
        val primary = queryConversationRecall(keyword, candidateLimit)
        if (primary.isNotEmpty() || fallbackTerms.size <= 1) return@withContext primary
        val merged = LinkedHashMap<String, ConversationRecallHit>()
        fallbackTerms.forEach { term ->
            queryConversationRecall(term, candidateLimit).forEach { hit ->
                val previous = merged[hit.conversationId]
                if (previous == null || hit.rawRank < previous.rawRank) {
                    merged[hit.conversationId] = hit
                }
            }
        }
        merged.values.sortedBy { it.rawRank }.take(candidateLimit)
    }

    private fun queryConversationRecall(keyword: String, limit: Int): List<ConversationRecallHit> {
        if (keyword.isBlank()) return emptyList()
        // One row per conversation is picked in Kotlin rather than with GROUP BY: SQLite's
        // bare-column guarantee only holds for a single min()/max() aggregate, and we need the
        // snippet + rank of the specific best-ranked message row.
        //
        // The whole statement (not just db.query) is wrapped: SQLiteCursor compiles and steps
        // lazily, so a malformed MATCH expression or a missing libsimple extension can surface on
        // the first moveToNext(). Either way this must degrade to "no content matches" instead of
        // failing the tool call — title recall still works.
        return runCatching {
            val best = LinkedHashMap<String, ConversationRecallHit>()
            db.query(
                """
                SELECT conversation_id, title, update_at,
                       simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet,
                       bm25(message_fts) AS rank_score
                FROM message_fts
                WHERE text MATCH jieba_query(?)
                ORDER BY rank_score ASC, update_at DESC
                LIMIT ?
                """.trimIndent(),
                // Over-fetch messages so that collapsing to one row per conversation still fills
                // the requested conversation count, but keep the scan bounded.
                arrayOf<Any?>(keyword, (limit * 5).coerceAtMost(2000)),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val conversationId = cursor.getString(0)
                    val hit = ConversationRecallHit(
                        conversationId = conversationId,
                        title = cursor.getString(1),
                        updateAt = cursor.getLong(2),
                        snippet = cursor.getString(3),
                        rawRank = cursor.getDouble(4),
                    )
                    val previous = best[conversationId]
                    if (previous == null || hit.rawRank < previous.rawRank) {
                        best[conversationId] = hit
                    }
                }
            }
            best.values.sortedBy { it.rawRank }.take(limit)
        }.onFailure { error ->
            Log.w(TAG, "searchConversationRecall failed for '$keyword'", error)
        }.getOrDefault(emptyList())
    }

    suspend fun search(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MessageSearchResult>()
        val cursor = db.query(
            """
            SELECT node_id, message_id, conversation_id, title, update_at,
                   simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
            FROM message_fts
            WHERE text MATCH jieba_query(?)
            ORDER BY ${sort.orderBy}
            LIMIT 50
            """.trimIndent(),
            arrayOf(keyword)
        )
        Log.i(TAG, "search: $keyword")
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    MessageSearchResult(
                        nodeId = it.getString(0),
                        messageId = it.getString(1),
                        conversationId = it.getString(2),
                        title = it.getString(3),
                        updateAt = Instant.ofEpochMilli(it.getLong(4)),
                        snippet = it.getString(5),
                    )
                )
            }
        }
        results
    }
}

private fun UIMessage.extractFtsText(): String =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(10_000)
