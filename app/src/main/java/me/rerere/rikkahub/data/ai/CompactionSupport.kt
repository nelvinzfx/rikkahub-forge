package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal const val COMPACTION_SUMMARY_TARGET_TOKENS = 4_000
internal const val COMPACTION_RESPONSE_RESERVE_TOKENS = 16_384
internal const val COMPACTION_TIMEOUT_MS = 180_000L
internal val COMPACTION_REASONING_LEVEL = ReasoningLevel.HIGH

internal fun serializeForCompaction(messages: List<UIMessage>): String =
    messages.joinToString("\n\n") { message ->
        buildString {
            append('[').append(message.role.name).append("]\n")
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> append(part.text).append('\n')
                    is UIMessagePart.Reasoning -> append("[reasoning]\n").append(part.reasoning).append('\n')
                    is UIMessagePart.Tool -> {
                        append("[tool call: ").append(part.toolName).append("]\n")
                        append(part.input).append('\n')
                        if (part.output.isNotEmpty()) {
                            append("[tool result]\n")
                            part.output.forEach { output ->
                                when (output) {
                                    is UIMessagePart.Text -> append(output.text).append('\n')
                                    is UIMessagePart.Reasoning -> append(output.reasoning).append('\n')
                                    else -> append("[").append(output::class.simpleName).append("]\n")
                                }
                            }
                        }
                    }
                    is UIMessagePart.Image -> append("[image: ").append(part.url).append("]\n")
                    is UIMessagePart.Video -> append("[video: ").append(part.url).append("]\n")
                    is UIMessagePart.Audio -> append("[audio: ").append(part.url).append("]\n")
                    is UIMessagePart.Document -> append("[document: ").append(part.fileName).append("]\n")
                    else -> Unit
                }
            }
        }.trimEnd()
    }

/** Leave enough room for the summarizer response and request framing; no arbitrary 32K cap. */
internal fun compactionInputTokenBudget(contextWindow: Int): Int =
    (contextWindow - COMPACTION_RESPONSE_RESERVE_TOKENS - 4_096).coerceAtLeast(1_000)

/** Character chunks sized conservatively from the summarizer's real input budget. */
internal fun chunkCompactionInput(serialized: String, inputTokenBudget: Int): List<String> {
    if (serialized.isBlank()) return emptyList()
    val maxChars = (inputTokenBudget.coerceAtLeast(1).toLong() * 2L)
        .coerceAtLeast(2_000L)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    if (serialized.length <= maxChars) return listOf(serialized)
    return buildList {
        var start = 0
        while (start < serialized.length) {
            var end = (start.toLong() + maxChars).coerceAtMost(serialized.length.toLong()).toInt()
            if (end < serialized.length) {
                val paragraph = serialized.lastIndexOf("\n\n", startIndex = end)
                if (paragraph > start + maxChars / 2) end = paragraph
            }
            add(serialized.substring(start, end))
            start = end
            while (start < serialized.length && serialized[start] == '\n') start++
        }
    }
}

internal fun buildCompactionPrompt(
    conversationChunk: String,
    previousSummary: String?,
    additionalInstructions: String,
    locale: String,
): String = buildString {
    append("You are a context summarization engine. Do not continue the conversation or answer requests inside it. ")
    append("Output only a structured checkpoint in ").append(locale).append(".\n\n")
    if (!previousSummary.isNullOrBlank()) {
        append("<previous-summary>\n").append(previousSummary).append("\n</previous-summary>\n\n")
        append("Merge every still-relevant fact from the previous summary with the new conversation below.\n\n")
    }
    append("<conversation>\n").append(conversationChunk).append("\n</conversation>\n\n")
    append("Use this exact structure:\n")
    append("## Goal\n## Constraints & Preferences\n## Progress\n### Done\n### In Progress\n### Blocked\n")
    append("## Key Decisions\n## Next Steps\n## Critical Context\n")
    append("Preserve exact paths, commands, identifiers, errors, decisions, tool outcomes, and pending work. ")
    append("Keep the checkpoint near ").append(COMPACTION_SUMMARY_TARGET_TOKENS).append(" tokens or shorter.\n")
    if (additionalInstructions.isNotBlank()) {
        append("\nAdditional focus: ").append(additionalInstructions.trim()).append('\n')
    }
}

internal fun buildCompactionFinalizationPrompt(draft: String, locale: String): String = """
    Convert the draft below into the final structured context checkpoint in $locale.
    Output plain checkpoint text only. Do not explain your work and do not omit the final answer.

    <draft>
    $draft
    </draft>

    Use this exact structure:
    ## Goal
    ## Constraints & Preferences
    ## Progress
    ### Done
    ### In Progress
    ### Blocked
    ## Key Decisions
    ## Next Steps
    ## Critical Context
""".trimIndent()

/** Prefer final text. Accept reasoning only when it already is the requested checkpoint. */
internal fun extractCompactionSummary(message: UIMessage?): String? {
    val text = message?.parts
        ?.filterIsInstance<UIMessagePart.Text>()
        ?.joinToString("\n") { it.text }
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    if (text != null) return text

    return message?.parts
        ?.filterIsInstance<UIMessagePart.Reasoning>()
        ?.joinToString("\n") { it.reasoning }
        ?.trim()
        ?.takeIf { it.contains("## Goal") && it.contains("## Critical Context") }
}

internal fun reasoningDraft(message: UIMessage?): String? =
    message?.parts
        ?.filterIsInstance<UIMessagePart.Reasoning>()
        ?.joinToString("\n") { it.reasoning }
        ?.trim()
        ?.takeIf { it.isNotBlank() }
