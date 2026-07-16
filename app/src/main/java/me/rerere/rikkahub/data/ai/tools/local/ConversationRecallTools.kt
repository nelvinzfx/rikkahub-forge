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
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

fun searchConversationsTool(repository: ConversationRepository): Tool = Tool(
    name = "search_conversations",
    description = "Search saved conversation titles and message content with broad multi-term recall. Queries may contain several related words; results are ranked by phrase match, term coverage, title/content relevance, and recency. Returns one result per conversation with a snippet around the best match.",
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

fun openConversationTool(context: Context, repository: ConversationRepository): Tool = Tool(
    name = "open_conversation",
    description = "Open an existing RikkaHub conversation by id.",
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
