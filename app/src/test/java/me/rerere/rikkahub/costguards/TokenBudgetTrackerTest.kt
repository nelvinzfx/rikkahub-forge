package me.rerere.rikkahub.costguards

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TokenBudgetTrackerTest {

    private fun mkMessage(prompt: Int, completion: Int, total: Int = 0): UIMessage {
        val effectiveTotal = if (total > 0) total else prompt + completion
        return UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
            usage = TokenUsage(
                promptTokens = prompt,
                completionTokens = completion,
                totalTokens = effectiveTotal,
            ),
        )
    }

    private fun mkConversation(messages: List<UIMessage>): Conversation {
        val nodes = messages.map { msg ->
            MessageNode(
                id = Uuid.random(),
                messages = listOf(msg),
                selectIndex = 0,
            )
        }
        return Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            title = "test",
            messageNodes = nodes,
        )
    }

    @Test fun `aggregate sums prompt and completion tokens across messages`() {
        val conv = mkConversation(listOf(
            mkMessage(prompt = 100, completion = 50),
            mkMessage(prompt = 200, completion = 100),
            mkMessage(prompt = 50, completion = 25),
        ))
        val totals = TokenBudgetTracker.aggregate(conv)
        assertEquals(350L, totals.inputTokens)
        assertEquals(175L, totals.outputTokens)
        assertEquals(525L, totals.totalTokens)
        assertEquals(3, totals.messageCount)
    }

    @Test fun `aggregate ignores messages without usage`() {
        val conv = mkConversation(listOf(
            mkMessage(prompt = 100, completion = 50),
            UIMessage(id = Uuid.random(), role = MessageRole.USER, parts = emptyList()),
            mkMessage(prompt = 200, completion = 100),
        ))
        val totals = TokenBudgetTracker.aggregate(conv)
        assertEquals(300L, totals.inputTokens)
        assertEquals(150L, totals.outputTokens)
        assertEquals(2, totals.messageCount)
    }

    @Test fun `perMessageMax tracks largest single message`() {
        val conv = mkConversation(listOf(
            mkMessage(prompt = 100, completion = 50),
            mkMessage(prompt = 1000, completion = 500),
            mkMessage(prompt = 200, completion = 100),
        ))
        val totals = TokenBudgetTracker.aggregate(conv)
        assertEquals(1500L, totals.perMessageMax)
    }

    @Test fun `projected context uses latest usage instead of cumulative usage`() {
        val conv = mkConversation(listOf(
            mkMessage(prompt = 50_000, completion = 20_000),
            mkMessage(prompt = 70_000, completion = 20_000),
            mkMessage(prompt = 100_000, completion = 22_700),
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("a".repeat(120))),
            ),
        ))

        val projected = TokenBudgetTracker.projectedContextTokens(conv)
        assertEquals(122_744L, projected)
        assertFalse(projected >= 272_000L)
        assertTrue(TokenBudgetTracker.aggregate(conv).totalTokens >= 272_000L)
    }

    @Test fun `projected context estimates pending user message`() {
        val conv = mkConversation(listOf(
            mkMessage(prompt = 240_000, completion = 20_000),
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("a".repeat(36_000))),
            ),
        ))

        assertEquals(272_004L, TokenBudgetTracker.projectedContextTokens(conv))
    }

    @Test fun `projected context estimates all messages without provider usage`() {
        val conv = mkConversation(listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("a".repeat(300))),
            ),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("b".repeat(150))),
            ),
        ))

        assertEquals(158L, TokenBudgetTracker.projectedContextTokens(conv))
    }

    @Test fun `recent turn cut moves backward to user boundary`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("u1"))),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("a".repeat(300)))),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("u2"))),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("b".repeat(300)))),
        )

        assertEquals(2, TokenBudgetTracker.recentTurnCutIndex(messages, 120))
    }

    @Test fun `classify NO_BUDGET when both caps null`() {
        val totals = TokenBudgetTracker.Totals(0, 0, 0, 0, 0)
        assertEquals(TokenBudgetTracker.BudgetStatus.NO_BUDGET,
            TokenBudgetTracker.classify(totals, null, null))
    }

    @Test fun `classify UNDER_SOFT when below soft cap`() {
        val totals = TokenBudgetTracker.Totals(0, 0, 5_000, 0, 1)
        assertEquals(TokenBudgetTracker.BudgetStatus.UNDER_SOFT,
            TokenBudgetTracker.classify(totals, softCap = 50_000, hardCap = 200_000))
    }

    @Test fun `classify WARN when above soft below hard`() {
        val totals = TokenBudgetTracker.Totals(0, 0, 75_000, 0, 1)
        assertEquals(TokenBudgetTracker.BudgetStatus.WARN,
            TokenBudgetTracker.classify(totals, softCap = 50_000, hardCap = 200_000))
    }

    @Test fun `classify OVER_HARD when at or above hard`() {
        val atCap = TokenBudgetTracker.Totals(0, 0, 200_000, 0, 1)
        val overCap = TokenBudgetTracker.Totals(0, 0, 250_000, 0, 1)
        assertEquals(TokenBudgetTracker.BudgetStatus.OVER_HARD,
            TokenBudgetTracker.classify(atCap, softCap = 50_000, hardCap = 200_000))
        assertEquals(TokenBudgetTracker.BudgetStatus.OVER_HARD,
            TokenBudgetTracker.classify(overCap, softCap = 50_000, hardCap = 200_000))
    }

    @Test fun `classify with only hard cap configured`() {
        val totals = TokenBudgetTracker.Totals(0, 0, 100_000, 0, 1)
        assertEquals(TokenBudgetTracker.BudgetStatus.UNDER_SOFT,
            TokenBudgetTracker.classify(totals, softCap = null, hardCap = 200_000))
    }
}
