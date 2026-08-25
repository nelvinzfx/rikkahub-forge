package me.rerere.rikkahub.ui.components.ui

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class InputDockTelemetryTest {

    private fun mkMessage(
        role: MessageRole,
        prompt: Int = 0,
        cached: Int = 0,
        withUsage: Boolean = true,
    ): UIMessage = UIMessage(
        id = Uuid.random(),
        role = role,
        parts = emptyList(),
        usage = if (withUsage) {
            TokenUsage(
                promptTokens = prompt,
                completionTokens = 10,
                totalTokens = prompt + 10,
                cachedTokens = cached,
            )
        } else {
            null
        },
    )

    private fun mkConversation(vararg messages: UIMessage): Conversation {
        val nodes = messages.map { msg ->
            MessageNode(id = Uuid.random(), messages = listOf(msg), selectIndex = 0)
        }
        return Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            title = "test",
            messageNodes = nodes,
        )
    }

    @Test
    fun `sums cached and prompt tokens across messages`() {
        val conv = mkConversation(
            mkMessage(MessageRole.ASSISTANT, prompt = 100, cached = 0),
            mkMessage(MessageRole.ASSISTANT, prompt = 200, cached = 180),
            mkMessage(MessageRole.ASSISTANT, prompt = 300, cached = 270),
        )
        val usage = computeCacheHitRate(conv)
        assertEquals(600L, usage.promptTokens)
        assertEquals(450L, usage.cachedTokens)
        assertNotNull(usage.rate)
        assertEquals(0.75f, usage.rate!!, 0.0001f)
    }

    @Test
    fun `user messages without usage are ignored`() {
        val conv = mkConversation(
            mkMessage(MessageRole.USER, withUsage = false),
            mkMessage(MessageRole.ASSISTANT, prompt = 100, cached = 0),
            mkMessage(MessageRole.ASSISTANT, prompt = 300, cached = 150),
        )
        val usage = computeCacheHitRate(conv)
        assertEquals(400L, usage.promptTokens)
        assertEquals(150L, usage.cachedTokens)
        assertEquals(0.375f, usage.rate!!, 0.0001f)
    }

    @Test
    fun `empty conversation has no rate`() {
        val usage = computeCacheHitRate(mkConversation())
        assertEquals(0L, usage.promptTokens)
        assertEquals(0L, usage.cachedTokens)
        assertNull(usage.rate)
    }

    @Test
    fun `usage with zero prompt tokens keeps rate null`() {
        val conv = mkConversation(
            mkMessage(MessageRole.USER, withUsage = false),
            mkMessage(MessageRole.ASSISTANT, prompt = 0, cached = 0),
        )
        val usage = computeCacheHitRate(conv)
        assertEquals(0L, usage.promptTokens)
        assertNull(usage.rate)
    }

    @Test
    fun `regenerated branch counts only the selected variant`() {
        val primary = mkMessage(MessageRole.ASSISTANT, prompt = 1000, cached = 900)
        val regenerated = mkMessage(MessageRole.ASSISTANT, prompt = 2000, cached = 1900)
        val conv = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            title = "branch",
            messageNodes = listOf(
                MessageNode(
                    id = Uuid.random(),
                    messages = listOf(primary, regenerated),
                    selectIndex = 1,
                ),
            ),
        )
        val usage = computeCacheHitRate(conv)
        assertEquals(2000L, usage.promptTokens)
        assertEquals(1900L, usage.cachedTokens)
        assertEquals(0.95f, usage.rate!!, 0.0001f)
    }
}
