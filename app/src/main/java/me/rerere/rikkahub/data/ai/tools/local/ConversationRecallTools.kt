package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

fun searchConversationsTool(repository: ConversationRepository): Tool = Tool(
    name = "search_conversations",
    description = "Search saved conversation titles and message content with broad multi-term recall. Queries may contain several related words; results are ranked by phrase match, term coverage, title/content relevance, and recency. Returns one result per conversation with a snippet around the best match; use read_conversation to inspect its contents.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Required case-insensitive search query; use natural multi-word queries when relevant")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum results; defaults to 10")
                })
            },
            required = listOf("query"),
        )
    },
    execute = { input ->
        val query = input.jsonObject["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty()) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("error", "query must not be empty") }.toString()))
        }
        val limit = (input.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 100)
        val results = repository.searchConversationRecall(query, limit)
        listOf(UIMessagePart.Text(buildJsonArray {
            results.forEach { result ->
                add(buildJsonObject {
                    put("conversationId", result.conversationId)
                    put("title", result.title)
                    put("matchedSnippet", result.matchedSnippet)
                    put("matchType", result.matchType)
                    put("timestamp", result.timestamp)
                })
            }
        }.toString()))
    },
)

fun readConversationTool(repository: ConversationRepository): Tool =
    readConversationTool { conversationId -> repository.getConversationById(conversationId) }

internal fun readConversationTool(
    conversationLoader: suspend (Uuid) -> Conversation?,
): Tool = Tool(
    name = "read_conversation",
    description = "Read a saved RikkaHub conversation's actual contents by UUID without opening the UI. Returns metadata and a normalized transcript of the active message branch. Long transcripts are paged by character offset; pass nextOffset back as offset until it is absent.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("conversationId", buildJsonObject {
                    put("type", "string")
                    put("description", "Conversation UUID returned by search_conversations")
                })
                put("offset", buildJsonObject {
                    put("type", "integer")
                    put("description", "0-based character offset into the normalized transcript; default 0")
                })
                put("maxChars", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum transcript characters returned in this page; default 12000, maximum 64000")
                })
            },
            required = listOf("conversationId"),
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val id = params["conversationId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val uuid = runCatching { Uuid.parse(id) }.getOrNull()
            ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "invalid_conversation_id")
                put("conversationId", id)
            }.toString()))
        val conversation = conversationLoader(uuid)
            ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "conversation_not_found")
                put("conversationId", id)
            }.toString()))

        val transcript = renderConversationTranscript(conversation)
        val requestedOffset = (params["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
        val offset = requestedOffset.coerceAtMost(transcript.length)
        val maxChars = (params["maxChars"]?.jsonPrimitive?.intOrNull ?: 12_000).coerceIn(100, 64_000)
        val end = offset + minOf(maxChars, transcript.length - offset)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("conversationId", conversation.id.toString())
            put("assistantId", conversation.assistantId.toString())
            put("title", conversation.title)
            put("createdAt", conversation.createAt.toEpochMilli())
            put("updatedAt", conversation.updateAt.toEpochMilli())
            put("messageCount", conversation.messageNodes.size)
            put("alternateMessageCount", conversation.messageNodes.sumOf { (it.messages.size - 1).coerceAtLeast(0) })
            put("offset", offset)
            put("returnedChars", end - offset)
            put("totalChars", transcript.length)
            put("truncated", end < transcript.length)
            if (end < transcript.length) put("nextOffset", end)
            put("transcript", transcript.substring(offset, end))
        }.toString()))
    },
)

internal fun renderConversationTranscript(conversation: Conversation): String = buildString {
    conversation.messageNodes.forEachIndexed { nodeIndex, node ->
        if (nodeIndex > 0) append("\n\n")
        append("[message ").append(nodeIndex).append("]\n")
        val message = node.messages.getOrNull(node.selectIndex)
        if (message == null) {
            append("unavailable: selected alternative ")
                .append(node.selectIndex)
                .append(" of ")
                .append(node.messages.size)
            return@forEachIndexed
        }
        append("id: ").append(message.id).append('\n')
        append("role: ").append(message.role.name.lowercase()).append('\n')
        append("createdAt: ").append(message.createdAt).append('\n')
        message.modelId?.let { append("modelId: ").append(it).append('\n') }
        if (node.messages.size > 1) {
            append("selectedAlternative: ").append(node.selectIndex).append('\n')
            append("alternativeCount: ").append(node.messages.size).append('\n')
        }
        append("content:\n")
        if (message.parts.isEmpty()) {
            append("[empty]")
        } else {
            message.parts.forEachIndexed { partIndex, part ->
                if (partIndex > 0) append('\n')
                append(renderConversationPart(part))
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun renderConversationPart(part: UIMessagePart, indent: String = ""): String = when (part) {
    is UIMessagePart.Text -> safeConversationPayload(part.text)
    is UIMessagePart.Reasoning -> "[reasoning omitted: ${part.reasoning.length} chars]"
    is UIMessagePart.Image -> "[image: ${safeConversationReference(part.url)}]"
    is UIMessagePart.Video -> "[video: ${safeConversationReference(part.url)}]"
    is UIMessagePart.Audio -> "[audio: ${safeConversationReference(part.url)}]"
    is UIMessagePart.Document -> "[document: name=${part.fileName}, mime=${part.mime}, url=${safeConversationReference(part.url)}]"
    is UIMessagePart.Search -> "[search]"
    is UIMessagePart.ToolCall -> "[tool_call: ${part.toolName}]\n${indent}arguments: ${safeConversationPayload(part.arguments)}"
    is UIMessagePart.ToolResult -> "[tool_result: ${part.toolName}]\n${indent}arguments: ${safeConversationPayload(part.arguments.toString())}\n${indent}content: ${safeConversationPayload(part.content.toString())}"
    is UIMessagePart.Tool -> buildString {
        append("[tool: ").append(part.toolName).append("]\n")
        append(indent).append("input: ").append(safeConversationPayload(part.input))
        if (part.output.isNotEmpty()) {
            append("\n").append(indent).append("output:\n")
            append(part.output.joinToString("\n") { output ->
                indent + "  " + renderConversationPart(output, indent + "  ")
            })
        }
    }
}

private fun safeConversationPayload(value: String): String =
    if (value.length > 20_000 && value.contains("data:", ignoreCase = true)) {
        "embedded payload omitted (${value.length} chars)"
    } else {
        value
    }

private fun safeConversationReference(value: String): String =
    if (value.startsWith("data:", ignoreCase = true)) {
        "embedded data omitted (${value.length} chars)"
    } else {
        value
    }

fun openConversationTool(context: Context, repository: ConversationRepository): Tool = Tool(
    name = "open_conversation",
    description = "Open an existing RikkaHub conversation in the user-facing chat UI by id. Use read_conversation when the agent needs to inspect its contents.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("conversationId", buildJsonObject {
                    put("type", "string")
                    put("description", "Conversation UUID returned by search_conversations")
                })
            },
            required = listOf("conversationId"),
        )
    },
    execute = { input ->
        val id = input.jsonObject["conversationId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val uuid = runCatching { Uuid.parse(id) }.getOrNull()
        if (uuid == null || !repository.existsConversationById(uuid)) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("success", false)
                put("error", "Conversation not found")
            }.toString()))
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("rikka://chat/${Uri.encode(id)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("conversationId", id)
        }.toString()))
    },
)
