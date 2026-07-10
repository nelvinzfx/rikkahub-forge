package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Memory Bank: enrich existing memories in-place. Old rows remain core memories. */
val Migration_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN title TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN mode TEXT NOT NULL DEFAULT 'core'")
        db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN importance INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN last_accessed_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN access_count INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN source_conversation_id TEXT")
        db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE MemoryEntity SET created_at = CAST(strftime('%s','now') AS INTEGER) * 1000, updated_at = CAST(strftime('%s','now') AS INTEGER) * 1000 WHERE created_at = 0")
    }
}
