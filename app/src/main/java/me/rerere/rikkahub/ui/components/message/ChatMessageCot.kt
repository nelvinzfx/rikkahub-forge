package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.util.fastForEachIndexed
import me.rerere.ai.ui.UIMessagePart

/**
 * 思考步骤类型，用于分组 Reasoning 和 Tool
 */
sealed interface ThinkingStep {
    val sourceIndex: Int
    val stableKey: String

    data class ReasoningStep(
        val reasoning: UIMessagePart.Reasoning,
        override val sourceIndex: Int,
    ) : ThinkingStep {
        override val stableKey: String = "reasoning:$sourceIndex"
    }

    data class ToolStep(
        val tool: UIMessagePart.Tool,
        override val sourceIndex: Int,
    ) : ThinkingStep {
        override val stableKey: String = "tool:$sourceIndex"
    }
}

/**
 * 消息部分块类型，用于保持渲染顺序
 */
sealed interface MessagePartBlock {
    val stableKey: String

    data class ThinkingBlock(
        val steps: List<ThinkingStep>,
        val startIndex: Int,
    ) : MessagePartBlock {
        override val stableKey: String = "thinking:$startIndex"
    }

    data class ContentBlock(val part: UIMessagePart, val index: Int) : MessagePartBlock {
        override val stableKey: String = "content:$index"
    }
}

/**
 * 将 parts 分组成 ThinkingBlock 和 ContentBlock
 * 连续的 Reasoning 和 Tool 会被分组到一个 ThinkingBlock 中
 */
fun List<UIMessagePart>.groupMessageParts(): List<MessagePartBlock> {
    val result = mutableListOf<MessagePartBlock>()
    var currentThinkingSteps = mutableListOf<ThinkingStep>()

    fun flushThinkingSteps() {
        if (currentThinkingSteps.isNotEmpty()) {
            result.add(
                MessagePartBlock.ThinkingBlock(
                    steps = currentThinkingSteps.toList(),
                    startIndex = currentThinkingSteps.first().sourceIndex,
                )
            )
            currentThinkingSteps = mutableListOf()
        }
    }

    this.fastForEachIndexed { index, part ->
        when (part) {
            is UIMessagePart.Reasoning -> {
                currentThinkingSteps.add(ThinkingStep.ReasoningStep(part, index))
            }

            is UIMessagePart.Tool -> {
                currentThinkingSteps.add(ThinkingStep.ToolStep(part, index))
            }

            else -> {
                flushThinkingSteps()
                result.add(MessagePartBlock.ContentBlock(part, index))
            }
        }
    }
    flushThinkingSteps()
    return result
}
