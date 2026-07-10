package me.rerere.rikkahub.data.repository

import android.util.Log
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.data.db.dao.ConversationDraftDAO
import me.rerere.rikkahub.data.db.entity.ConversationDraftEntity
import me.rerere.rikkahub.utils.JsonInstant

private const val TAG = "ConversationDraftRepo"

class ConversationDraftRepository(
    private val dao: ConversationDraftDAO,
) {
    suspend fun load(conversationId: String): List<UIMessagePart>? {
        val row = dao.get(conversationId) ?: return null
        return runCatching {
            JsonInstant.decodeFromString<List<UIMessagePart>>(row.partsJson)
        }.onFailure {
            Log.w(TAG, "discarding unreadable draft for $conversationId", it)
            dao.delete(conversationId)
        }.getOrNull()
    }

    suspend fun replace(conversationId: String, parts: List<UIMessagePart>) {
        if (parts.isEmptyInputMessage()) {
            dao.delete(conversationId)
            return
        }
        dao.upsert(
            ConversationDraftEntity(
                conversationId = conversationId,
                partsJson = JsonInstant.encodeToString(parts),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun delete(conversationId: String) {
        dao.delete(conversationId)
    }
}
