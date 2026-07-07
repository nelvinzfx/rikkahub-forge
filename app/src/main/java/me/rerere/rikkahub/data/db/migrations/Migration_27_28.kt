package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Phase 30 (Orchestrator Mode) — v27 → v28.
 *
 * Adds the `chat_model_id` column to the `ConversationEntity` table. This column
 * was part of the Conversation data class since Phase A but was accidentally
 * omitted from ConversationEntity, causing per-conversation model overrides
 * (set by SubAgentEngine for worker sub-agents) to be silently dropped on
 * DB round-trip.
 *
 * Manual migration (not AutoMigration) because schema 27.json was never
 * generated — the v26→v27 AutoMigration only needed 26.json (which existed),
 * but v27→v28 AutoMigration needs 27.json which doesn't exist in the repo
 * (no local build was ever done at v27).
 */
val Migration_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN chat_model_id TEXT NOT NULL DEFAULT ''")
    }
}
