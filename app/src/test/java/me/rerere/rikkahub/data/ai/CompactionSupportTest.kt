package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionSupportTest {
    @Test
    fun `serializer preserves reasoning tool arguments and tool results`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning("inspect exact path"),
                    UIMessagePart.Tool(
                        toolCallId = "call-1",
                        toolName = "edit_file",
                        input = "{\"path\":\"src/Main.kt\"}",
                        output = listOf(UIMessagePart.Text("applied cleanly")),
                        approvalState = ToolApprovalState.Approved,
                    ),
                ),
            )
        )

        val serialized = serializeForCompaction(messages)
        assertTrue(serialized.contains("inspect exact path"))
        assertTrue(serialized.contains("edit_file"))
        assertTrue(serialized.contains("src/Main.kt"))
        assertTrue(serialized.contains("applied cleanly"))
    }

    @Test
    fun `incremental prompt carries previous summary and custom focus`() {
        val prompt = buildCompactionPrompt(
            conversationChunk = "new work",
            previousSummary = "old checkpoint",
            additionalInstructions = "preserve errors",
            locale = "English",
        )
        assertTrue(prompt.contains("<previous-summary>\nold checkpoint"))
        assertTrue(prompt.contains("new work"))
        assertTrue(prompt.contains("preserve errors"))
        assertTrue(prompt.contains("## Critical Context"))
    }

    @Test
    fun `chunking keeps all nonblank content within conservative bounds`() {
        val input = (1..500).joinToString("\n\n") { "paragraph-$it ${"x".repeat(20)}" }
        val chunks = chunkCompactionInput(input, inputTokenBudget = 1_000)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 2_000 })
        val restoredParagraphs = chunks.flatMap { chunk ->
            chunk.split("\n\n").filter(String::isNotBlank)
        }
        assertEquals(500, restoredParagraphs.size)
        assertFalse(restoredParagraphs.any { it.startsWith("paragraph-").not() })
    }
}
