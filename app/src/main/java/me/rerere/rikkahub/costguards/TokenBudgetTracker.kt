package me.rerere.rikkahub.costguards

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation

/**
 * Phase 15 — pure-function token aggregator over a [Conversation]. Walks the currently-
 * selected branch (one message per node, picked via `selectIndex`), sums every
 * non-null [TokenUsage], and reports the running totals.
 *
 * The aggregator is intentionally read-only — it never mutates the conversation or the
 * assistant settings. The auto-stop integration (live indicator pill in chat header,
 * GenerationHandler cancellation when over hard cap) is the Phase 15.5 follow-up that
 * needs deeper hooks into the streaming pipeline.
 *
 * v1 surface:
 *   - LLM-callable tool (`check_token_usage`) returns the totals + budget status so the
 *     model can self-stop or notify the user.
 *   - Future Compose / Telegram surfaces collect the same numbers.
 */
object TokenBudgetTracker {

    data class Totals(
        val inputTokens: Long,
        val outputTokens: Long,
        val totalTokens: Long,
        val perMessageMax: Long,
        val messageCount: Int,
    )

    enum class BudgetStatus {
        UNDER_SOFT,
        WARN,            // crossed soft, below hard
        OVER_HARD,       // crossed hard
        NO_BUDGET,       // no caps configured
    }

    data class Snapshot(
        val totals: Totals,
        val softCap: Int?,
        val hardCap: Int?,
        val status: BudgetStatus,
    )

    fun aggregate(conversation: Conversation): Totals {
        var input = 0L
        var output = 0L
        var total = 0L
        var perMax = 0L
        var count = 0
        for (node in conversation.messageNodes) {
            val msg = node.messages.getOrNull(node.selectIndex) ?: continue
            val usage = msg.usage ?: continue
            input += usage.promptTokens.toLong()
            output += usage.completionTokens.toLong()
            val totalThis = (usage.totalTokens.takeIf { it > 0 }
                ?: (usage.promptTokens + usage.completionTokens)).toLong()
            total += totalThis
            if (totalThis > perMax) perMax = totalThis
            count++
        }
        return Totals(
            inputTokens = input,
            outputTokens = output,
            totalTokens = total,
            perMessageMax = perMax,
            messageCount = count,
        )
    }

    /**
     * Estimated size of the next request context. Provider usage is cumulative for one
     * request, so use only the latest measured message, then estimate messages appended
     * after it. Summing every usage double-counts earlier context.
     */
    fun projectedContextTokens(conversation: Conversation): Long {
        val messages = conversation.currentMessages
        val measuredIndex = messages.indexOfLast { it.usage != null }
        val measuredTokens = messages.getOrNull(measuredIndex)?.usage?.let { usage ->
            (usage.totalTokens.takeIf { it > 0 }
                ?: (usage.promptTokens + usage.completionTokens)).toLong()
        } ?: 0L
        val suffixStart = if (measuredIndex >= 0) measuredIndex + 1 else 0
        return measuredTokens + messages.drop(suffixStart).sumOf(::estimateMessageTokens)
    }

    /**
     * Start index of the trailing "recent" window to keep verbatim when at most
     * [keepRecentTokens] tokens of messages may be kept. Walks backward from the newest
     * message; the newest message is always kept even if it alone exceeds the budget
     * (a pending user turn must survive compaction). Returns 0 when everything fits.
     */
    fun recentCutIndex(messages: List<UIMessage>, keepRecentTokens: Long): Int {
        if (messages.isEmpty()) return 0
        var accumulated = 0L
        for (i in messages.lastIndex downTo 0) {
            val next = accumulated + estimateMessageTokens(messages[i])
            if (next > keepRecentTokens && i < messages.lastIndex) return i + 1
            accumulated = next
        }
        return 0
    }

    private fun estimateMessageTokens(message: UIMessage): Long =
        message.parts.sumOf(::estimatePartTokens) + 4L

    private fun estimatePartTokens(part: UIMessagePart): Long = when (part) {
        is UIMessagePart.Text -> estimateTextTokens(part.text)
        is UIMessagePart.Reasoning -> estimateTextTokens(part.reasoning)
        is UIMessagePart.Tool -> estimateTextTokens(part.toolName) +
            estimateTextTokens(part.input) + part.output.sumOf(::estimatePartTokens)
        else -> 256L
    }

    private fun estimateTextTokens(text: String): Long {
        var ascii = 0L
        var nonAscii = 0L
        text.forEach { if (it.code <= 0x7f) ascii++ else nonAscii++ }
        return (ascii + 2L) / 3L + nonAscii
    }

    fun classify(totals: Totals, softCap: Int?, hardCap: Int?): BudgetStatus {
        // No budget configured → no-budget. Spec calls this "off"; tool surface shows
        // numbers but doesn't recommend action.
        if (softCap == null && hardCap == null) return BudgetStatus.NO_BUDGET
        if (hardCap != null && totals.totalTokens >= hardCap) return BudgetStatus.OVER_HARD
        if (softCap != null && totals.totalTokens >= softCap) return BudgetStatus.WARN
        return BudgetStatus.UNDER_SOFT
    }

    fun snapshot(conversation: Conversation, softCap: Int?, hardCap: Int?): Snapshot {
        val totals = aggregate(conversation)
        return Snapshot(
            totals = totals,
            softCap = softCap,
            hardCap = hardCap,
            status = classify(totals, softCap, hardCap),
        )
    }
}
