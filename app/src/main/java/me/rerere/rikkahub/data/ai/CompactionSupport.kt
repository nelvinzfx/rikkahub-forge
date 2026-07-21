package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal const val COMPACTION_SUMMARY_MAX_TOKENS = 4_000

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

/** Character chunks sized conservatively from a token input budget. */
internal fun chunkCompactionInput(serialized: String, inputTokenBudget: Int): List<String> {
    if (serialized.isBlank()) return emptyList()
    val maxChars = (inputTokenBudget.coerceAtLeast(1) * 2).coerceAtLeast(2_000)
    if (serialized.length <= maxChars) return listOf(serialized)
    return buildList {
        var start = 0
        while (start < serialized.length) {
            var end = (start + maxChars).coerceAtMost(serialized.length)
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
    append("Keep it concise enough to fit within ").append(COMPACTION_SUMMARY_MAX_TOKENS).append(" tokens.\n")
    if (additionalInstructions.isNotBlank()) {
        append("\nAdditional focus: ").append(additionalInstructions.trim()).append('\n')
    }
}
