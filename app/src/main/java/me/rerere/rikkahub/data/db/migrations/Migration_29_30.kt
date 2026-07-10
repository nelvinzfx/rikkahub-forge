package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds one persistent input draft per conversation. */
val Migration_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversation_drafts (
                conversation_id TEXT NOT NULL,
                parts_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(conversation_id),
                FOREIGN KEY(conversation_id) REFERENCES ConversationEntity(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_conversation_drafts_conversation_id " +
                "ON conversation_drafts(conversation_id)",
        )
    }
}
