package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import me.rerere.rikkahub.data.db.entity.ConversationDraftEntity

@Dao
interface ConversationDraftDAO {
    @Query("SELECT * FROM conversation_drafts WHERE conversation_id = :conversationId")
    suspend fun get(conversationId: String): ConversationDraftEntity?

    @Upsert
    suspend fun upsert(draft: ConversationDraftEntity)

    @Query("DELETE FROM conversation_drafts WHERE conversation_id = :conversationId")
    suspend fun delete(conversationId: String)
}
