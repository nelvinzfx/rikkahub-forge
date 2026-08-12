package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.rerere.rikkahub.data.repository.ConversationRecallResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The search_conversations tool payload must expose the normalized score symmetrically with search_memories. */
class SearchConversationsToolTest {

    @Test
    fun `results expose score alongside matchType and timestamp`() {
        val tool = searchConversationsTool { _, _ ->
            listOf(
                result("11111111-1111-1111-1111-111111111111", "Pricing bug", 0.82, "content"),
                result("22222222-2222-2222-2222-222222222222", "Proxy notes", 0.41, "title"),
            )
        }

        val payload = Json.parseToJsonElement(execTool(tool, """{"query":"pricing bug"}""")).jsonArray

        assertEquals(2, payload.size)
        val first = payload[0].jsonObject
        assertEquals("11111111-1111-1111-1111-111111111111", first["conversationId"]!!.jsonPrimitive.content)
        assertEquals("Pricing bug", first["title"]!!.jsonPrimitive.content)
        assertEquals("content", first["matchType"]!!.jsonPrimitive.content)
        assertEquals(1_000L, first["timestamp"]!!.jsonPrimitive.long)
        assertNotNull(first["matchedSnippet"])
        assertEquals(0.82, first["score"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(0.41, payload[1].jsonObject["score"]!!.jsonPrimitive.double, 1e-9)
        payload.forEach { entry ->
            assertTrue(entry.jsonObject["score"]!!.jsonPrimitive.double in 0.0..1.0)
        }
    }

    @Test
    fun `empty query is rejected without touching recall`() {
        var called = false
        val tool = searchConversationsTool { _, _ ->
            called = true
            emptyList()
        }

        val result = Json.parseToJsonElement(execTool(tool, """{"query":"   "}""")).jsonObject

        assertEquals("query must not be empty", result["error"]!!.jsonPrimitive.content)
        assertFalse("recall must not run for an empty query", called)
    }

    @Test
    fun `a query with no surviving terms returns an empty array`() {
        // RecallSearch.plan() rejects punctuation-only input, so the repository returns nothing
        // and the tool must still emit a well-formed empty JSON array.
        val tool = searchConversationsTool { _, _ -> emptyList() }

        val payload = Json.parseToJsonElement(execTool(tool, """{"query":"?! --"}""")).jsonArray

        assertTrue(payload.isEmpty())
    }

    @Test
    fun `description advertises the 0 to 1 relevance score`() {
        val description = searchConversationsTool { _, _ -> emptyList() }.description

        assertTrue(description.contains("relevance score from 0 to 1"))
        assertTrue(description.contains("higher = more relevant"))
    }

    private fun result(id: String, title: String, score: Double, matchType: String) =
        ConversationRecallResult(
            conversationId = id,
            title = title,
            matchedSnippet = "[$title]",
            matchType = matchType,
            timestamp = 1_000,
            score = score,
        )
}
