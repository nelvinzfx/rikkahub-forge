package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class MemoryEntity(
    @PrimaryKey(true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
    @ColumnInfo("title", defaultValue = "")
    val title: String = "",
    @ColumnInfo("mode", defaultValue = "core")
    val mode: String = "core",
    @ColumnInfo("tags", defaultValue = "")
    val tags: String = "",
    @ColumnInfo("importance", defaultValue = "0")
    val importance: Int = 0,
    @ColumnInfo("created_at", defaultValue = "0")
    val createdAt: Long = 0L,
    @ColumnInfo("updated_at", defaultValue = "0")
    val updatedAt: Long = 0L,
    @ColumnInfo("last_accessed_at", defaultValue = "0")
    val lastAccessedAt: Long = 0L,
    @ColumnInfo("access_count", defaultValue = "0")
    val accessCount: Int = 0,
    @ColumnInfo("source_conversation_id")
    val sourceConversationId: String? = null,
    @ColumnInfo("archived", defaultValue = "0")
    val archived: Boolean = false,
)
