package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v26 → v27: Phase 30 (Orchestrator Mode Phase B) — adds 4 boolean columns to
 * ConversationEntity for sub-agent prompt/memory gating.
 *
 * Manual migration because AutoMigration requires schema 27.json which was never
 * generated (no local build at v27). The 4 columns all default to 0 (false).
 */
val Migration_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN suppress_memory INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN suppress_assistant_prompt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN suppress_recent_chats INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN enforce_subagent_rules INTEGER NOT NULL DEFAULT 0")
    }
}
