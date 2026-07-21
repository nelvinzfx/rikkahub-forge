package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart

/** Latest persisted compaction checkpoint on the selected conversation branch. */
data class CompactionCheckpoint(
    val nodeIndex: Int,
    val annotation: UIMessageAnnotation.Compaction,
)

fun Conversation.latestCompaction(): CompactionCheckpoint? {
    for (index in messageNodes.indices.reversed()) {
        val annotation = messageNodes[index].currentMessage.annotations
            .filterIsInstance<UIMessageAnnotation.Compaction>()
            .lastOrNull()
        if (annotation != null) return CompactionCheckpoint(index, annotation)
    }
    return null
}

/**
 * Effective transcript sent to providers. The full transcript remains persisted/rendered;
 * a checkpoint replaces only the prefix through its boundary with one synthetic summary.
 */
fun Conversation.effectiveMessages(): List<UIMessage> {
    val checkpoint = latestCompaction() ?: return currentMessages
    val summary = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(checkpoint.annotation.summary)),
    )
    return listOf(summary) + currentMessages.drop(checkpoint.nodeIndex + 1)
}

/** Attach [summary] to the selected message at [boundaryIndex] without deleting history. */
fun Conversation.withCompactionCheckpoint(
    boundaryIndex: Int,
    summary: String,
    tokensBefore: Long,
    createdAtEpochMillis: Long = System.currentTimeMillis(),
): Conversation {
    require(boundaryIndex in messageNodes.indices)
    val nodes = messageNodes.mapIndexed { index, node ->
        if (index < boundaryIndex) return@mapIndexed node
        val selected = node.currentMessage
        val updated = if (index == boundaryIndex) {
            val annotations = selected.annotations
                .filterNot { it is UIMessageAnnotation.Compaction } +
                UIMessageAnnotation.Compaction(
                    summary = summary,
                    tokensBefore = tokensBefore,
                    createdAtEpochMillis = createdAtEpochMillis,
                )
            selected.copy(annotations = annotations, usage = null)
        } else {
            // Kept messages still contain provider usage for the old, pre-checkpoint
            // request. Clear that stale baseline; the next provider response repopulates
            // usage and the gauge can adopt it immediately, even when the same assistant
            // message is extended by a tool-loop continuation.
            selected.copy(usage = null)
        }
        node.copy(
            messages = node.messages.mapIndexed { messageIndex, message ->
                if (messageIndex == node.selectIndex) updated else message
            }
        )
    }
    return copy(messageNodes = nodes, chatSuggestions = emptyList())
}

/** Merge provider-loop messages back into the full transcript after a checkpoint. */
fun Conversation.updateEffectiveMessages(messages: List<UIMessage>): Conversation {
    val checkpoint = latestCompaction() ?: return updateCurrentMessages(messages)
    val suffixMessages = messages.drop(1) // index 0 is the synthetic checkpoint summary
    val newNodes = messageNodes.toMutableList()
    suffixMessages.forEachIndexed { suffixIndex, message ->
        val index = checkpoint.nodeIndex + 1 + suffixIndex
        val node = newNodes.getOrElse(index) { message.toMessageNode() }
        val variants = node.messages.toMutableList()
        var selected = node.selectIndex
        val existing = variants.indexOfFirst { it.id == message.id }
        if (existing >= 0) {
            variants[existing] = message
        } else {
            variants.add(message)
            selected = variants.lastIndex
        }
        val updated = node.copy(messages = variants, selectIndex = selected)
        if (index > newNodes.lastIndex) newNodes.add(updated) else newNodes[index] = updated
    }
    return copy(messageNodes = newNodes)
}
