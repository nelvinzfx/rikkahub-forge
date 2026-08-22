package me.rerere.rikkahub.data.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.uuid.Uuid

class ConversationRawExportTest {

    private fun exportAndParse(conversation: Conversation, assistant: Assistant?): kotlinx.serialization.json.JsonObject {
        val export = buildConversationRawExport(
            conversation = conversation,
            assistant = assistant,
            exportedAt = LocalDateTime.of(2026, 8, 22, 12, 0, 0),
        )
        return Json.parseToJsonElement(serializeConversationRawExport(export)).jsonObject
    }

    @Test
    fun `dump contains full node tree with all branches tool parts and usage verbatim`() {
        val userMessage = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("list files")),
        )
        val branchA = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("thinking..."),
                UIMessagePart.Tool(
                    toolCallId = "call_1",
                    toolName = "shell",
                    input = """{"cmd":"ls"}""",
                    output = listOf(UIMessagePart.Text("a.txt\nb.txt")),
                ),
            ),
            usage = TokenUsage(promptTokens = 10, completionTokens = 20, totalTokens = 30),
        )
        val branchB = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("alternate answer")),
        )

        val doc = exportAndParse(
            conversation = Conversation(
                title = "My Chat",
                assistantId = Uuid.random(),
                messageNodes = listOf(
                    // One node with two alternate generations — both must be present.
                    MessageNode(messages = listOf(branchA, branchB), selectIndex = 0),
                    userMessage.toMessageNode(),
                ),
            ),
            assistant = Assistant(id = Uuid.random(), name = "Helper", systemPrompt = "You are helpful."),
        )

        assertEquals("rikkahub-conversation-raw", doc["format"]!!.jsonPrimitive.content)
        assertTrue(listOf("formatVersion", "exportedAt", "conversationId", "conversationTitle", "conversation", "systemPrompt")
            .all { doc.containsKey(it) })
        assertEquals("My Chat", doc["conversationTitle"]!!.jsonPrimitive.content)
        assertEquals("2026-08-22T12:00:00", doc["exportedAt"]!!.jsonPrimitive.content)

        val nodes = doc["conversation"]!!.jsonObject["messageNodes"]!!.jsonArray
        assertEquals(2, nodes.size)

        val firstNodeMessages = nodes[0].jsonObject["messages"]!!.jsonArray
        assertEquals("both alternates exported, not just the selected one", 2, firstNodeMessages.size)

        val toolParts = firstNodeMessages[0].jsonObject["parts"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["type"]!!.jsonPrimitive.content == "tool" }
        assertEquals(1, toolParts.size)
        assertEquals("shell", toolParts[0]["toolName"]!!.jsonPrimitive.content)
        assertEquals("""{"cmd":"ls"}""", toolParts[0]["input"]!!.jsonPrimitive.content)
        assertEquals("a.txt\nb.txt", toolParts[0]["output"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(30, firstNodeMessages[0].jsonObject["usage"]!!.jsonObject["totalTokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `effective system prompt resolves from assistant and is labeled`() {
        val doc = exportAndParse(
            conversation = Conversation(
                assistantId = Uuid.random(),
                messageNodes = emptyList(),
            ),
            assistant = Assistant(name = "Helper", systemPrompt = "You are helpful."),
        )
        val sp = doc["systemPrompt"]!!.jsonObject
        assertEquals("assistant", sp["effectiveSource"]!!.jsonPrimitive.content)
        assertEquals("You are helpful.", sp["effectiveSystemPrompt"]!!.jsonPrimitive.content)
        assertEquals("You are helpful.", sp["assistantSystemPrompt"]!!.jsonPrimitive.content)
        assertEquals("Helper", sp["assistantName"]!!.jsonPrimitive.content)
        assertTrue(sp["assistantFound"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `conversation custom prompt overrides assistant prompt and is labeled`() {
        val doc = exportAndParse(
            conversation = Conversation(
                assistantId = Uuid.random(),
                messageNodes = emptyList(),
                customSystemPrompt = "custom persona",
                suppressAssistantPrompt = true,
            ),
            assistant = Assistant(name = "Helper", systemPrompt = "You are helpful.", allowConversationSystemPrompt = true),
        )
        val sp = doc["systemPrompt"]!!.jsonObject
        assertEquals("conversation_custom", sp["effectiveSource"]!!.jsonPrimitive.content)
        assertEquals("custom persona", sp["effectiveSystemPrompt"]!!.jsonPrimitive.content)
        assertEquals(true, sp["suppressAssistantPrompt"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `custom prompt is gated by assistant allowConversationSystemPrompt`() {
        // allowConversationSystemPrompt defaults to false: generation IGNORES the
        // conversation-level custom prompt and keeps the assistant prompt. The dump
        // must not claim the custom prompt is effective when it is not.
        val doc = exportAndParse(
            conversation = Conversation(
                assistantId = Uuid.random(),
                messageNodes = emptyList(),
                customSystemPrompt = "custom persona",
            ),
            assistant = Assistant(name = "Helper", systemPrompt = "You are helpful."),
        )
        val sp = doc["systemPrompt"]!!.jsonObject
        assertEquals("assistant", sp["effectiveSource"]!!.jsonPrimitive.content)
        assertEquals("You are helpful.", sp["effectiveSystemPrompt"]!!.jsonPrimitive.content)
        assertEquals("custom persona", sp["conversationCustomSystemPrompt"]!!.jsonPrimitive.content)
    }

    @Test
    fun `worker conversation concatenates assistant and custom prompt unless suppressed`() {
        // Mirrors the enforceSubAgentPromptRules branch in GenerationHandler: the gate
        // is bypassed, and with include_soul (suppressAssistantPrompt=false) BOTH
        // prompts are used, concatenated.
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = emptyList(),
            customSystemPrompt = "worker task prompt",
            enforceSubAgentPromptRules = true,
        )
        val withSoul = exportAndParse(
            conversation = conversation,
            assistant = Assistant(name = "Helper", systemPrompt = "You are helpful."),
        )["systemPrompt"]!!.jsonObject
        assertEquals(
            "assistant_plus_conversation_custom",
            withSoul["effectiveSource"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "You are helpful.\n\nworker task prompt",
            withSoul["effectiveSystemPrompt"]!!.jsonPrimitive.content,
        )

        val suppressed = exportAndParse(
            conversation = conversation.copy(suppressAssistantPrompt = true),
            assistant = Assistant(name = "Helper", systemPrompt = "You are helpful."),
        )["systemPrompt"]!!.jsonObject
        assertEquals(
            "conversation_custom",
            suppressed["effectiveSource"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "worker task prompt",
            suppressed["effectiveSystemPrompt"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `missing assistant is reported instead of invented`() {
        val assistantId = Uuid.random()
        val doc = exportAndParse(
            conversation = Conversation(assistantId = assistantId, messageNodes = emptyList()),
            assistant = null,
        )
        val sp = doc["systemPrompt"]!!.jsonObject
        assertFalse(sp["assistantFound"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(assistantId.toString(), sp["assistantId"]!!.jsonPrimitive.content)
        assertEquals("", sp["effectiveSystemPrompt"]!!.jsonPrimitive.content)
        assertEquals("empty", sp["effectiveSource"]!!.jsonPrimitive.content)
    }

    @Test
    fun `file name is sanitized timestamped and falls back to short id`() {
        val id = Uuid.random()
        val now = LocalDateTime.of(2026, 8, 22, 13, 5, 9)

        assertEquals(
            "rikkahub-conversation-Hello-World-123-x-yz-20260822-130509.json",
            rawExportFileName(" Hello  World!! 123/x\\yz ", id, now),
        )

        val fallback = rawExportFileName("   ", id, now)
        assertTrue(fallback.startsWith("rikkahub-conversation-${id.toString().take(8)}-20260822-130509.json"))

        val longTitle = "x".repeat(100)
        val name = rawExportFileName(longTitle, id, now)
        val stem = name.removePrefix("rikkahub-conversation-").substringBefore("-2026")
        assertTrue(stem.length <= 40)
    }
}
