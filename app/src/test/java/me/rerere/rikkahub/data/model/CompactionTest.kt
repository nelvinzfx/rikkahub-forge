package me.rerere.rikkahub.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.costguards.TokenBudgetTracker
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CompactionTest {
    private fun message(role: MessageRole, text: String, usage: Int? = null) = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
        usage = usage?.let { TokenUsage(promptTokens = it, totalTokens = it) },
    )

    private fun conversation(messages: List<UIMessage>) = Conversation(
        assistantId = Uuid.random(),
        messageNodes = messages.map { it.toMessageNode() },
    )

    @Test
    fun `checkpoint preserves transcript while provider context becomes summary plus suffix`() {
        val original = listOf(
            message(MessageRole.USER, "old request"),
            message(MessageRole.ASSISTANT, "old answer", usage = 410_000),
            message(MessageRole.USER, "recent request"),
            message(MessageRole.ASSISTANT, "recent answer", usage = 410_000),
        )
        val compacted = conversation(original).withCompactionCheckpoint(
            boundaryIndex = 1,
            summary = "structured checkpoint",
            tokensBefore = 410_000,
            createdAtEpochMillis = 123L,
        )

        assertEquals(original.map { it.toText() }, compacted.currentMessages.map { it.toText() })
        assertEquals(4, compacted.currentMessages.size)
        assertNull(compacted.currentMessages[1].usage)
        assertNull(compacted.currentMessages[3].usage)

        val effective = compacted.effectiveMessages()
        assertEquals(listOf("structured checkpoint", "recent request", "recent answer"), effective.map { it.toText() })
        assertTrue(TokenBudgetTracker.projectedContextTokens(compacted) < 1_000)
    }

    @Test
    fun `provider loop updates suffix without replacing transcript prefix`() {
        val compacted = conversation(listOf(
            message(MessageRole.USER, "old request"),
            message(MessageRole.ASSISTANT, "old answer"),
            message(MessageRole.USER, "pending request"),
        )).withCompactionCheckpoint(1, "checkpoint", 90_000, 123L)

        val effective = compacted.effectiveMessages()
        val generated = effective + message(MessageRole.ASSISTANT, "new answer", usage = 500)
        val merged = compacted.updateEffectiveMessages(generated)

        assertEquals(listOf("old request", "old answer", "pending request", "new answer"),
            merged.currentMessages.map { it.toText() })
        assertEquals("checkpoint", merged.latestCompaction()?.annotation?.summary)
    }

    @Test
    fun `newer checkpoint supersedes older checkpoint incrementally`() {
        val first = conversation(listOf(
            message(MessageRole.USER, "one"),
            message(MessageRole.ASSISTANT, "two"),
            message(MessageRole.USER, "three"),
            message(MessageRole.ASSISTANT, "four"),
        )).withCompactionCheckpoint(1, "first", 50_000, 1L)
        val second = first.withCompactionCheckpoint(2, "second", 60_000, 2L)

        assertEquals(2, second.latestCompaction()?.nodeIndex)
        assertEquals(listOf("second", "four"), second.effectiveMessages().map { it.toText() })
        assertEquals(4, second.currentMessages.size)
    }

    @Test
    fun `provider loop updates an existing suffix message by id`() {
        val recentAssistant = message(MessageRole.ASSISTANT, "partial")
        val compacted = conversation(listOf(
            message(MessageRole.USER, "old request"),
            message(MessageRole.ASSISTANT, "old answer"),
            recentAssistant,
        )).withCompactionCheckpoint(1, "checkpoint", 90_000, 123L)

        val effective = compacted.effectiveMessages()
        val updated = recentAssistant.copy(
            parts = listOf(UIMessagePart.Text("complete")),
            usage = TokenUsage(promptTokens = 500, totalTokens = 500),
        )
        val merged = compacted.updateEffectiveMessages(effective.dropLast(1) + updated)

        assertEquals(3, merged.currentMessages.size)
        assertEquals("complete", merged.currentMessages.last().toText())
        assertEquals(500, merged.currentMessages.last().usage?.totalTokens)
    }

    @Test
    fun `checkpoint annotation survives json persistence`() {
        val annotation: UIMessageAnnotation = UIMessageAnnotation.Compaction(
            summary = "persist me",
            tokensBefore = 123_456,
            createdAtEpochMillis = 999,
        )
        val restored = JsonInstant.decodeFromString<UIMessageAnnotation>(
            JsonInstant.encodeToString(annotation)
        )
        val checkpoint = restored as UIMessageAnnotation.Compaction
        assertEquals("persist me", checkpoint.summary)
        assertEquals(123_456L, checkpoint.tokensBefore)
        assertEquals(999L, checkpoint.createdAtEpochMillis)
    }
}
