package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity

@Dao
interface MessageNodeDAO {
    @Query("SELECT * FROM message_node WHERE conversation_id = :conversationId ORDER BY node_index ASC")
    suspend fun getNodesOfConversation(conversationId: String): List<MessageNodeEntity>

    @Query(
        "SELECT * FROM message_node WHERE conversation_id = :conversationId " +
            "ORDER BY node_index ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getNodesOfConversationPaged(
        conversationId: String,
        limit: Int,
        offset: Int
    ): List<MessageNodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(nodes: List<MessageNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(node: MessageNodeEntity)

    @Update
    suspend fun update(node: MessageNodeEntity)

    @Query("DELETE FROM message_node WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM message_node WHERE id = :nodeId")
    suspend fun deleteById(nodeId: String)

    // 使用 @RawQuery 绕过 Room 编译期校验，以便使用 json_each() 虚拟表
    @RawQuery
    suspend fun getTokenStatsRaw(query: SupportSQLiteQuery): MessageTokenStats

    @RawQuery
    suspend fun getMessageCountPerDayRaw(query: SupportSQLiteQuery): List<MessageDayCount>

    @RawQuery
    suspend fun searchConversationRecallRaw(query: SupportSQLiteQuery): List<ConversationRecallCandidate>
}

data class MessageTokenStats(
    val totalMessages: Int = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
)

data class MessageDayCount(val day: String, val count: Int)

data class ConversationRecallCandidate(
    val conversationId: String,
    val title: String,
    val matchedText: String,
    val matchType: String,
    val timestamp: Long,
    val matchRank: Int,
)

suspend fun MessageNodeDAO.searchConversationRecall(
    patterns: List<String>,
    limit: Int,
): List<ConversationRecallCandidate> {
    if (patterns.isEmpty()) return emptyList()
    val textExpression = "CAST(json_extract(part.value, '$.text') AS TEXT)"
    val titleWhere = patterns.joinToString(" OR ") { "c.title LIKE ? ESCAPE '\\' COLLATE NOCASE" }
    val contentWhere = patterns.joinToString(" OR ") { "$textExpression LIKE ? ESCAPE '\\' COLLATE NOCASE" }
    return searchConversationRecallRaw(
        SimpleSQLiteQuery(
            """
            SELECT conversationId, title, matchedText, matchType, timestamp, matchRank FROM (
                SELECT c.id AS conversationId, c.title AS title, c.title AS matchedText,
                       'title' AS matchType, c.update_at AS timestamp, 0 AS matchRank
                FROM conversationentity c
                WHERE $titleWhere
                UNION ALL
                SELECT c.id AS conversationId, c.title AS title,
                       $textExpression AS matchedText,
                       'content' AS matchType,
                       COALESCE(
                           CAST(strftime('%s', json_extract(message.value, '$.createdAt')) AS INTEGER) * 1000,
                           c.update_at
                       ) AS timestamp,
                       1 AS matchRank
                FROM conversationentity c
                JOIN message_node node ON node.conversation_id = c.id
                JOIN json_each(node.messages) message
                JOIN json_each(json_extract(message.value, '$.parts')) part
                WHERE json_extract(part.value, '$.text') IS NOT NULL
                  AND ($contentWhere)
            )
            ORDER BY matchRank ASC, timestamp DESC
            LIMIT ?
            """.trimIndent(),
            buildList<Any?> {
                addAll(patterns)
                addAll(patterns)
                add(limit.coerceIn(1, 1000))
            }.toTypedArray(),
        )
    )
}

// SQLite json_each() 展开 messages JSON 数组，json_extract() 提取 Token 字段并聚合
private val TOKEN_STATS_SQL = SimpleSQLiteQuery(
    "SELECT COUNT(*) AS totalMessages, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.promptTokens') AS INTEGER)), 0) AS promptTokens, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.completionTokens') AS INTEGER)), 0) AS completionTokens, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.cachedTokens') AS INTEGER)), 0) AS cachedTokens " +
        "FROM message_node mn, json_each(mn.messages) j"
)

suspend fun MessageNodeDAO.getTokenStats(): MessageTokenStats = getTokenStatsRaw(TOKEN_STATS_SQL)

// 按用户消息的 createdAt 字段（LocalDateTime ISO 字符串前10位即日期）统计每日消息数
suspend fun MessageNodeDAO.getMessageCountPerDay(startDate: String): List<MessageDayCount> =
    getMessageCountPerDayRaw(
        SimpleSQLiteQuery(
            "SELECT substr(json_extract(j.value, '$.createdAt'), 1, 10) AS day, " +
                "COUNT(*) AS count " +
                "FROM message_node mn, json_each(mn.messages) j " +
                "WHERE json_extract(j.value, '$.role') = 'user' " +
                "AND json_extract(j.value, '$.createdAt') >= ? " +
                "GROUP BY day",
            arrayOf(startDate)
        )
    )

