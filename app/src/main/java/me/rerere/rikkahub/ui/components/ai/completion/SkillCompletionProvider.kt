package me.rerere.rikkahub.ui.components.ai.completion

import androidx.compose.ui.text.TextRange
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.rikkahub.data.files.SkillMetadata

class SkillCompletionProvider(
    private val skills: List<SkillMetadata>,
) : ChatCompletionProvider {
    override val id: String = "skills"

    override suspend fun complete(context: ChatCompletionContext): ChatCompletionList? {
        if (context.hasSelection) return null
        val mention = findSkillMention(context.text, context.cursor) ?: return null
        val query = mention.query.lowercase()
        val items = skills
            .mapNotNull { skill ->
                val score = skill.name.lowercase().matchScore(query) ?: return@mapNotNull null
                ChatCompletionItem(
                    label = "@${skill.name}",
                    insertText = "@${skill.name} ",
                    detail = skill.description,
                    icon = HugeIcons.Puzzle,
                    sortScore = score + SKILL_SCORE_BONUS,
                )
            }
            .sortedWith(
                compareByDescending<ChatCompletionItem> { it.sortScore }
                    .thenBy { it.label.length }
                    .thenBy { it.label.lowercase() }
            )
            .take(MAX_COMPLETION_ITEMS)

        if (items.isEmpty()) return null
        return ChatCompletionList(
            providerId = id,
            replacementRange = mention.range,
            items = items,
        )
    }

    private fun findSkillMention(text: String, cursor: Int): SkillMention? {
        if (cursor !in 0..text.length) return null
        val prefix = text.substring(0, cursor)
        val start = prefix.lastIndexOf('@')
        if (start < 0) return null
        if (start > 0 && !text[start - 1].isMentionBoundary()) return null

        val query = prefix.substring(start + 1)
        if (query.any { !it.isLetterOrDigit() && it != '-' && it != '_' }) return null
        return SkillMention(query = query, range = TextRange(start, cursor))
    }

    private fun String.matchScore(query: String): Int? {
        if (query.isBlank()) return 1
        if (this == query) return 1_000
        if (startsWith(query)) return 900 - length.coerceAtMost(200)
        val index = indexOf(query)
        if (index >= 0) return 800 - index.coerceAtMost(200)

        var queryIndex = 0
        for (char in this) {
            if (queryIndex < query.length && char == query[queryIndex]) queryIndex++
        }
        return if (queryIndex == query.length) 500 - length.coerceAtMost(300) else null
    }

    private fun Char.isMentionBoundary(): Boolean =
        isWhitespace() || this in "([{<\"'"

    private data class SkillMention(
        val query: String,
        val range: TextRange,
    )

    companion object {
        // Skill mentions share the @ trigger with workspace files. Keep explicit skills
        // ahead of file matches until the query contains path characters.
        private const val SKILL_SCORE_BONUS = 5_000
        private const val MAX_COMPLETION_ITEMS = 8
    }
}
