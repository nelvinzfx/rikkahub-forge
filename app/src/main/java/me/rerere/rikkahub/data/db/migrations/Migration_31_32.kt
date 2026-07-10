package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Drafts must also work for a new chat before its ConversationEntity is first persisted.
 * Rebuild the table without the invalid parent foreign key while preserving existing drafts.
 */
val Migration_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE conversation_drafts_new (
                conversation_id TEXT NOT NULL,
                parts_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(conversation_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO conversation_drafts_new (conversation_id, parts_json, updated_at)
            SELECT conversation_id, parts_json, updated_at FROM conversation_drafts
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE conversation_drafts")
        db.execSQL("ALTER TABLE conversation_drafts_new RENAME TO conversation_drafts")
        db.execSQL(
            "CREATE INDEX index_conversation_drafts_conversation_id " +
                "ON conversation_drafts(conversation_id)",
        )
    }
}
