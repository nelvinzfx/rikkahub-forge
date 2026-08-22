package me.rerere.rikkahub.data.export

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstantPretty
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

/**
 * Raw, machine-readable dump of a conversation with full fidelity.
 *
 * Unlike the markdown/image exports (pretty, selected-branch-only), this dump contains
 * EVERYTHING persisted for the conversation:
 *  - [conversation]: the [Conversation] object serialized verbatim via kotlinx.serialization,
 *    which includes the FULL message-node tree (all branches/alternates, not just the
 *    selected one) with every [UIMessagePart] (text, reasoning, tool calls with raw input
 *    arguments, tool outputs, images/documents/audio/video, metadata), per-message usage
 *    tokens, and every persisted conversation setting (assistantId, chatModelId,
 *    orchestratorMode, suppress flags, lorebook/mode injection ids, workspaceCwd, ...).
 *  - [systemPrompt]: the configured system prompt of the conversation's assistant, resolved
 *    from settings by [Conversation.assistantId], plus the conversation-level
 *    customSystemPrompt override and which source is effective.
 *
 * Known limitation: anything assembled only at generation runtime (per-turn prompt
 * assembly, injected memories/soul text, recent-chats context, mode/lorebook expansion)
 * is NOT persisted per conversation and therefore cannot appear in this dump.
 */
@Serializable
data class ConversationRawExport(
    val format: String = FORMAT,
    val formatVersion: Int = VERSION,
    val exportedAt: String,
    val conversationId: String,
    val conversationTitle: String,
    /**
     * Full [Conversation] serialization: message-node tree with all branches, all parts
     * verbatim (tool call inputs/outputs, reasoning, images, metadata), per-message usage,
     * and every persisted conversation setting.
     */
    val conversation: JsonElement,
    /** Effective system prompt context, clearly labeled by [SystemPromptContext.effectiveSource]. */
    val systemPrompt: SystemPromptContext,
) {
    companion object {
        const val FORMAT = "rikkahub-conversation-raw"
        const val VERSION = 1
    }
}

/** Which source the effective system prompt came from. */
const val SYSTEM_PROMPT_SOURCE_CONVERSATION_CUSTOM = "conversation_custom"
const val SYSTEM_PROMPT_SOURCE_ASSISTANT = "assistant"
const val SYSTEM_PROMPT_SOURCE_ASSISTANT_PLUS_CONVERSATION_CUSTOM = "assistant_plus_conversation_custom"
const val SYSTEM_PROMPT_SOURCE_EMPTY = "empty"

@Serializable
data class SystemPromptContext(
    val assistantId: String,
    val assistantName: String,
    /** False when the conversation's assistantId no longer exists in settings. */
    val assistantFound: Boolean,
    /** The configured system prompt of the assistant (empty if assistant not found). */
    val assistantSystemPrompt: String,
    /** Per-conversation override stored on the conversation itself, if any. */
    val conversationCustomSystemPrompt: String?,
    /**
     * What generation actually uses as the conversation-level system prompt text
     * (mirrors GenerationHandler's resolution; the orchestrator preamble and other
     * runtime-assembled sections are not included).
     */
    val effectiveSystemPrompt: String,
    /** One of [SYSTEM_PROMPT_SOURCE_CONVERSATION_CUSTOM], [SYSTEM_PROMPT_SOURCE_ASSISTANT], [SYSTEM_PROMPT_SOURCE_ASSISTANT_PLUS_CONVERSATION_CUSTOM], [SYSTEM_PROMPT_SOURCE_EMPTY]. */
    val effectiveSource: String,
    /** When true the assistant-level prompt section was suppressed for this conversation. */
    val suppressAssistantPrompt: Boolean,
)

/**
 * Pure format assembly for the raw export. The caller (UI) resolves the assistant from
 * settings; this function stays free of Android dependencies so it is unit-testable.
 */
fun buildConversationRawExport(
    conversation: Conversation,
    assistant: Assistant?,
    exportedAt: LocalDateTime = LocalDateTime.now(),
): ConversationRawExport {
    val customPrompt = conversation.customSystemPrompt?.takeIf { it.isNotBlank() }
    val assistantPrompt = assistant?.systemPrompt.orEmpty()
    // Mirrors GenerationHandler's effectiveSystemPrompt resolution: for normal
    // conversations the custom prompt only REPLACES the assistant prompt when the
    // assistant allows conversation system prompts (allowConversationSystemPrompt);
    // without the gate a disallowing assistant would be mislabeled as overridden.
    // Sub-agent conversations (enforceSubAgentPromptRules) bypass the gate and, when
    // the assistant prompt is not suppressed, CONCATENATE both prompts.
    val effectivePrompt: String
    val effectiveSource: String
    if (conversation.enforceSubAgentPromptRules) {
        when {
            customPrompt != null && conversation.suppressAssistantPrompt -> {
                effectivePrompt = customPrompt
                effectiveSource = SYSTEM_PROMPT_SOURCE_CONVERSATION_CUSTOM
            }
            customPrompt != null -> {
                effectivePrompt = listOfNotNull(
                    assistantPrompt.takeIf { it.isNotBlank() },
                    customPrompt,
                ).joinToString("\n\n")
                effectiveSource = SYSTEM_PROMPT_SOURCE_ASSISTANT_PLUS_CONVERSATION_CUSTOM
            }
            conversation.suppressAssistantPrompt -> {
                effectivePrompt = ""
                effectiveSource = SYSTEM_PROMPT_SOURCE_EMPTY
            }
            else -> {
                effectivePrompt = assistantPrompt
                effectiveSource = if (assistantPrompt.isBlank()) SYSTEM_PROMPT_SOURCE_EMPTY else SYSTEM_PROMPT_SOURCE_ASSISTANT
            }
        }
    } else if (assistant?.allowConversationSystemPrompt == true && customPrompt != null) {
        effectivePrompt = customPrompt
        effectiveSource = SYSTEM_PROMPT_SOURCE_CONVERSATION_CUSTOM
    } else {
        effectivePrompt = assistantPrompt
        effectiveSource = if (assistantPrompt.isBlank()) SYSTEM_PROMPT_SOURCE_EMPTY else SYSTEM_PROMPT_SOURCE_ASSISTANT
    }
    return ConversationRawExport(
        exportedAt = exportedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
        conversationId = conversation.id.toString(),
        conversationTitle = conversation.title,
        conversation = JsonInstantPretty.encodeToJsonElement(Conversation.serializer(), conversation),
        systemPrompt = SystemPromptContext(
            assistantId = conversation.assistantId.toString(),
            assistantName = assistant?.name.orEmpty(),
            assistantFound = assistant != null,
            assistantSystemPrompt = assistantPrompt,
            conversationCustomSystemPrompt = conversation.customSystemPrompt,
            effectiveSystemPrompt = effectivePrompt,
            effectiveSource = effectiveSource,
            suppressAssistantPrompt = conversation.suppressAssistantPrompt,
        )
    )
}

/** Convenience overload resolving the assistant from settings by the conversation's assistantId. */
fun buildConversationRawExport(
    conversation: Conversation,
    settings: Settings,
    exportedAt: LocalDateTime = LocalDateTime.now(),
): ConversationRawExport = buildConversationRawExport(
    conversation = conversation,
    assistant = settings.getAssistantById(conversation.assistantId),
    exportedAt = exportedAt,
)

/** Pretty-printed JSON document for the export file. */
fun serializeConversationRawExport(export: ConversationRawExport): String =
    JsonInstantPretty.encodeToString(ConversationRawExport.serializer(), export)

private val RAW_EXPORT_FILE_TIMESTAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

/**
 * rikkahub-conversation-<sanitized-title-or-shortId>-<yyyyMMdd-HHmmss>.json
 * Falls back to the first 8 chars of the conversation id when the title has no
 * usable characters.
 */
fun rawExportFileName(
    title: String,
    conversationId: Uuid,
    now: LocalDateTime = LocalDateTime.now(),
): String {
    val sanitized = title.trim()
        .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
        .trim('-')
        .take(40)
    val stem = sanitized.ifEmpty { conversationId.toString().take(8) }
    return "rikkahub-conversation-$stem-${now.format(RAW_EXPORT_FILE_TIMESTAMP)}.json"
}
