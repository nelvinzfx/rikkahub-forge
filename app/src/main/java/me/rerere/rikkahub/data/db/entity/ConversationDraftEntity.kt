package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversation_drafts",
    indices = [Index("conversation_id")],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ConversationDraftEntity(
    @PrimaryKey
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("parts_json")
    val partsJson: String,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
