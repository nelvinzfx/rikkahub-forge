package me.rerere.rikkahub.data.search

import java.util.Locale

internal data class RecallSearchPlan(
    val phrase: String,
    val terms: List<String>,
) {
    val isEmpty: Boolean get() = phrase.isBlank() && terms.isEmpty()
}

internal object RecallSearch {
    private val tokenRegex = Regex("[\\p{L}\\p{N}]+")
    private val stopWords = setOf(
        "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "in", "is", "it",
        "of", "on", "or", "the", "to", "with", "di", "dan", "dari", "ini", "itu", "ke", "yang",
    )

    fun plan(query: String, maxTerms: Int = 8): RecallSearchPlan {
        val raw = query.trim()
        val phrase = raw.lowercase(Locale.ROOT)
        val rawTokens = tokenRegex.findAll(raw).map { it.value }.toList()
        val expanded = rawTokens.flatMap { token -> listOf(token) + splitCamelCase(token) }
        val filtered = expanded
            .map { it.lowercase(Locale.ROOT) }
            .filter { it.length >= 2 && it !in stopWords }
            .distinct()
        val fallback = rawTokens
            .map { it.lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .distinct()
        val terms = (filtered.ifEmpty { fallback }).take(maxTerms)
        return RecallSearchPlan(
            phrase = if (terms.isEmpty()) "" else phrase,
            terms = terms,
        )
    }

    fun toFtsOrQuery(plan: RecallSearchPlan): String = plan.terms
        .joinToString(" OR ") { term -> "\"${term.replace("\"", "\"\"")}\"*" }

    fun likePattern(value: String): String = "%${escapeLike(value)}%"

    fun likePatterns(plan: RecallSearchPlan): List<String> =
        (listOf(plan.phrase).filter(String::isNotBlank) + plan.terms)
            .distinct()
            .map(::likePattern)

    fun scoreMemory(title: String, content: String, tags: String, plan: RecallSearchPlan): Int =
        scoreText(title, plan, phraseWeight = 90, termWeight = 14) +
            scoreText(tags, plan, phraseWeight = 70, termWeight = 10) +
            scoreText(content, plan, phraseWeight = 45, termWeight = 6) +
            coverageScore("$title\n$tags\n$content", plan)

    fun scoreConversationCandidate(matchType: String, text: String, plan: RecallSearchPlan): Int {
        val title = matchType == "title"
        return scoreText(
            text = text,
            plan = plan,
            phraseWeight = if (title) 90 else 45,
            termWeight = if (title) 14 else 8,
        )
    }

    fun scoreConversation(title: String, matchedTexts: List<String>, plan: RecallSearchPlan): Int =
        scoreText(title, plan, phraseWeight = 90, termWeight = 14) +
            matchedTexts.sumOf { text -> scoreText(text, plan, phraseWeight = 45, termWeight = 8) } +
            coverageScore((listOf(title) + matchedTexts).joinToString("\n"), plan) * 2

    fun bestSnippet(title: String, content: String, tags: String, plan: RecallSearchPlan, maxLength: Int = 120): String {
        val choices = listOf(
            "title" to title,
            "tags" to tags,
            "content" to content,
        ).map { (label, text) -> Triple(label, text, scoreText(text, plan, 90, 10)) }
        val best = choices.maxByOrNull { it.third } ?: return snippetAround(content.ifBlank { title }, plan, maxLength)
        val snippet = snippetAround(best.second.ifBlank { content.ifBlank { title } }, plan, maxLength)
        return if (best.first == "content" || snippet.startsWith("${best.first}:")) snippet else "${best.first}: $snippet"
    }

    fun snippetAround(text: String, plan: RecallSearchPlan, maxLength: Int = 120): String {
        if (text.length <= maxLength) return text
        val match = firstMatchIndex(text, plan).coerceAtLeast(0)
        val start = (match - maxLength / 2).coerceIn(0, text.length - maxLength)
        val end = (start + maxLength).coerceAtMost(text.length)
        return buildString {
            if (start > 0) append("…")
            append(text.substring(start, end))
            if (end < text.length) append("…")
        }
    }

    fun scoreText(text: String, plan: RecallSearchPlan, phraseWeight: Int, termWeight: Int): Int {
        if (text.isBlank() || plan.isEmpty) return 0
        var score = 0
        if (plan.phrase.isNotBlank() && text.contains(plan.phrase, ignoreCase = true)) {
            score += phraseWeight
        }
        plan.terms.forEach { term ->
            if (text.contains(term, ignoreCase = true)) score += termWeight
        }
        return score
    }

    private fun coverageScore(text: String, plan: RecallSearchPlan): Int =
        plan.terms.count { term -> text.contains(term, ignoreCase = true) } * 12

    private fun firstMatchIndex(text: String, plan: RecallSearchPlan): Int {
        if (plan.phrase.isNotBlank()) {
            val phraseIndex = text.indexOf(plan.phrase, ignoreCase = true)
            if (phraseIndex >= 0) return phraseIndex
        }
        return plan.terms
            .map { term -> text.indexOf(term, ignoreCase = true) }
            .filter { it >= 0 }
            .minOrNull() ?: 0
    }

    private fun escapeLike(value: String): String = value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    private fun splitCamelCase(token: String): List<String> {
        if (token.length < 4) return emptyList()
        val parts = mutableListOf<String>()
        var start = 0
        for (i in 1 until token.length) {
            val previous = token[i - 1]
            val current = token[i]
            val next = token.getOrNull(i + 1)
            val boundary = (previous.isLowerCase() && current.isUpperCase()) ||
                (previous.isUpperCase() && current.isUpperCase() && next?.isLowerCase() == true)
            if (boundary) {
                parts += token.substring(start, i)
                start = i
            }
        }
        if (start > 0) parts += token.substring(start)
        return parts.filter { it.length >= 2 }
    }
}
