package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.dao.ConversationRecallCandidate
import me.rerere.rikkahub.data.search.RecallSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRecallRankingTest {
    @Test
    fun `multi term query can rank one conversation by coverage across messages`() {
        val plan = RecallSearch.plan("UnifiedProxy pricing bug")
        val results = rankConversationRecall(
            candidates = listOf(
                candidate("one", "api probe", "UnifiedProxy gateway trace", 1000),
                candidate("one", "api probe", "pricing page returned stale bug", 900),
                candidate("two", "pricing notes", "pricing only", 2000),
            ),
            plan = plan,
        )

        assertEquals("one", results.first().conversationId)
        assertTrue(results.first().matchedSnippet.contains("UnifiedProxy") || results.first().matchedSnippet.contains("pricing"))
    }

    @Test
    fun `title phrase beats a newer weak content match`() {
        val plan = RecallSearch.plan("UnifiedProxy pricing bug")
        val results = rankConversationRecall(
            candidates = listOf(
                candidate("weak", "random", "pricing only", 3000),
                candidate("strong", "UnifiedProxy pricing bug", "old note", 1000, matchType = "title"),
            ),
            plan = plan,
        )

        assertEquals("strong", results.first().conversationId)
        assertEquals("title", results.first().matchType)
    }

    private fun candidate(
        id: String,
        title: String,
        text: String,
        timestamp: Long,
        matchType: String = "content",
    ) = ConversationRecallCandidate(
        conversationId = id,
        title = title,
        matchedText = text,
        matchType = matchType,
        timestamp = timestamp,
        matchRank = if (matchType == "title") 0 else 1,
    )
}
