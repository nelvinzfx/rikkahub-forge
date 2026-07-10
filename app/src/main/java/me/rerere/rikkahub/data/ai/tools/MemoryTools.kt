package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.MemoryRepository

fun buildMemoryTools(
    json: Json,
    repository: MemoryRepository,
    assistantId: String,
    sourceConversationId: String? = null,
): List<Tool> = listOf(
    Tool(
        name = "memory_tool",
        description = "Create, edit, or delete long-term memory. Core memories are always injected within a token budget; bank memories are retrieved on demand. Existing calls with action/content/id remain compatible.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", enumSchema("create", "edit", "delete"))
                    put("id", typeSchema("integer", "Memory id; required for edit/delete"))
                    put("content", typeSchema("string", "Memory content; required for create/edit"))
                    put("title", typeSchema("string", "Optional short title"))
                    put("mode", enumSchema("core", "bank"))
                    put("tags", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                    put("importance", typeSchema("integer", "Optional priority from 0 to 100"))
                },
                required = listOf("action"),
            )
        },
        execute = { input ->
            val params = input.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            val result = when (action) {
                "create" -> repository.addMemory(
                    assistantId = assistantId,
                    content = params.string("content") ?: error("content is required"),
                    title = params.string("title").orEmpty(),
                    mode = params.string("mode") ?: MemoryRepository.MODE_CORE,
                    tags = params.tags(),
                    importance = params["importance"]?.jsonPrimitive?.intOrNull ?: 0,
                    sourceConversationId = sourceConversationId,
                ).let { json.encodeToJsonElement(me.rerere.rikkahub.data.model.AssistantMemory.serializer(), it) }
                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    val old = repository.getMemory(assistantId, id) ?: error("Memory record #$id not found")
                    repository.updateMemory(old.copy(
                        content = params.string("content") ?: error("content is required"),
                        title = params.string("title") ?: old.title,
                        mode = params.string("mode") ?: old.mode,
                        tags = if (params.containsKey("tags")) params.tags() else old.tags,
                        importance = params["importance"]?.jsonPrimitive?.intOrNull ?: old.importance,
                    )).let { json.encodeToJsonElement(me.rerere.rikkahub.data.model.AssistantMemory.serializer(), it) }
                }
                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    if (repository.getMemory(assistantId, id) == null) error("Memory record #$id not found")
                    repository.deleteMemory(id)
                    buildJsonObject { put("success", true); put("id", id) }
                }
                else -> error("unknown action: $action")
            }
            listOf(UIMessagePart.Text(result.toString()))
        },
    ),
    Tool(
        name = "search_memories",
        description = "Search this assistant's long-term memory bank. Returns ranked snippets; use get_memory for full content.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", typeSchema("string", "Required search query"))
                    put("limit", typeSchema("integer", "Maximum results; default 10"))
                },
                required = listOf("query"),
            )
        },
        execute = { input ->
            val query = input.jsonObject.string("query")?.trim().orEmpty()
            require(query.isNotEmpty()) { "query must not be empty" }
            val limit = (input.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 100)
            val payload = buildJsonArray {
                repository.search(assistantId, query, limit).forEach { hit ->
                    add(buildJsonObject {
                        put("id", hit.id); put("title", hit.title); put("snippet", hit.snippet)
                        put("mode", hit.mode); put("tags", hit.tags); put("score", hit.score)
                        put("updatedAt", hit.updatedAt)
                        hit.sourceConversationId?.let { put("sourceConversationId", it) }
                    })
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    ),
    Tool(
        name = "get_memory",
        description = "Read one full memory by id from this assistant's memory bank.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject { put("id", typeSchema("integer", "Memory id")) },
                required = listOf("id"),
            )
        },
        execute = { input ->
            val id = input.jsonObject["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
            val memory = repository.getMemory(assistantId, id)
            val payload = if (memory == null) buildJsonObject {
                put("success", false); put("error", "Memory not found")
            } else json.encodeToJsonElement(me.rerere.rikkahub.data.model.AssistantMemory.serializer(), memory)
            listOf(UIMessagePart.Text(payload.toString()))
        },
    ),
)

private fun typeSchema(type: String, description: String) = buildJsonObject {
    put("type", type); put("description", description)
}

private fun enumSchema(vararg values: String) = buildJsonObject {
    put("type", "string"); put("enum", buildJsonArray { values.forEach(::add) })
}

private fun kotlinx.serialization.json.JsonObject.string(key: String) =
    this[key]?.jsonPrimitive?.contentOrNull

private fun kotlinx.serialization.json.JsonObject.tags(): List<String> =
    this["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
