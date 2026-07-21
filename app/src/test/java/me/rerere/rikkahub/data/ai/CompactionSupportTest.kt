package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionSupportTest {
    @Test
    fun `compaction keeps high reasoning for summary quality`() {
        assertEquals(ReasoningLevel.HIGH, COMPACTION_REASONING_LEVEL)
    }

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
    fun `large context uses its real budget instead of legacy 32k chunks`() {
        assertEquals(389_520, compactionInputTokenBudget(410_000))
        val serialized = "x".repeat(700_000)
        assertEquals(1, chunkCompactionInput(serialized, compactionInputTokenBudget(410_000)).size)
    }

    @Test
    fun `summary extraction prefers final text and accepts structured reasoning fallback`() {
        val withText = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("draft"),
                UIMessagePart.Text("## Goal\nfinal\n## Critical Context\nkept"),
            ),
        )
        assertTrue(extractCompactionSummary(withText)!!.contains("final"))

        val reasoningOnly = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning(
                "## Goal\nfallback\n## Critical Context\nkept"
            )),
        )
        assertTrue(extractCompactionSummary(reasoningOnly)!!.contains("fallback"))
        assertEquals(null, extractCompactionSummary(UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning("unstructured scratch")),
        )))
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
