package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("assistant_id", defaultValue = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
    val assistantId: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("nodes")
    val nodes: String,
    @ColumnInfo("create_at")
    val createAt: Long,
    @ColumnInfo("update_at")
    val updateAt: Long,
    @ColumnInfo("suggestions", defaultValue = "[]")
    val chatSuggestions: String,
    @ColumnInfo("is_pinned", defaultValue = "0")
    val isPinned: Boolean,
    @ColumnInfo("custom_system_prompt", defaultValue = "")
    val customSystemPrompt: String = "",
    @ColumnInfo("chat_model_id", defaultValue = "")
    val chatModelId: String = "",
    @ColumnInfo("mode_injection_ids", defaultValue = "[]")
    val modeInjectionIds: String = "[]",
    @ColumnInfo("lorebook_ids", defaultValue = "[]")
    val lorebookIds: String = "[]",
    @ColumnInfo("workspace_cwd", defaultValue = "")
    val workspaceCwd: String = "",
    // Phase 30 (Orchestrator Mode Phase B) — sub-agent prompt/memory gating.
    @ColumnInfo("suppress_memory", defaultValue = "0")
    val suppressMemory: Boolean = false,
    @ColumnInfo("suppress_assistant_prompt", defaultValue = "0")
    val suppressAssistantPrompt: Boolean = false,
    @ColumnInfo("suppress_recent_chats", defaultValue = "0")
    val suppressRecentChats: Boolean = false,
    @ColumnInfo("enforce_subagent_rules", defaultValue = "0")
    val enforceSubAgentPromptRules: Boolean = false,
)
