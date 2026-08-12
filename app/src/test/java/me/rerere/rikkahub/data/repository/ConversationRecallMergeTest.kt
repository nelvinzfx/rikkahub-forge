package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.fts.ConversationRecallHit
import me.rerere.rikkahub.data.search.RecallScore
import me.rerere.rikkahub.data.search.RecallSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replaces ConversationRecallRankingTest, which exercised the removed hand-rolled substring
 * tally over LIKE candidates. Conversation recall now merges message_fts bm25 hits with
 * LIKE title matches through [mergeConversationRecall].
 */
class ConversationRecallMergeTest {
    private val plan = RecallSearch.plan("UnifiedProxy pricing bug")

    @Test
    fun `content hits are scored from the fts rank not from substring counting`() {
        val results = mergeConversationRecall(
            contentHits = listOf(
                contentHit("weak", "api probe", rawRank = -0.3),
                contentHit("strong", "other notes", rawRank = -8.0),
            ),
            titleMatches = emptyList(),
            plan = plan,
        )

        assertEquals(listOf("strong", "weak"), results.map { it.conversationId })
        assertEquals(RecallScore.normalize(-8.0), results.first().score, 1e-9)
        assertEquals("content", results.first().matchType)
        // A substring tally could not distinguish these two: neither snippet contains a query term.
        assertTrue(results.first().score > results.last().score)
    }

    @Test
    fun `every merged score stays inside 0 to 1`() {
        val results = mergeConversationRecall(
            contentHits = listOf(
                contentHit("a", "t", rawRank = -0.2),
                contentHit("b", "t", rawRank = -1.0),
                contentHit("c", "t", rawRank = -12_345.0),
            ),
            titleMatches = listOf(
                ConversationTitleMatch("d", "UnifiedProxy pricing bug", 10),
                ConversationTitleMatch("e", "pricing", 20),
            ),
            plan = plan,
        )

        assertEquals(5, results.size)
        results.forEach { result ->
            assertTrue("score out of range: ${result.score}", result.score in 0.0..1.0)
        }
        assertEquals(1.0, results.first { it.conversationId == "d" }.score, 1e-9)
    }

    @Test
    fun `results are ordered by score and keep matchType and timestamp`() {
        val results = mergeConversationRecall(
            contentHits = listOf(contentHit("content-only", "notes", rawRank = -1.0, updateAt = 500)),
            titleMatches = listOf(ConversationTitleMatch("title-only", "UnifiedProxy pricing bug", 900)),
            plan = plan,
        )

        assertEquals(listOf("title-only", "content-only"), results.map { it.conversationId })
        assertEquals(listOf("title", "content"), results.map { it.matchType })
        assertEquals(listOf(900L, 500L), results.map { it.timestamp })
    }

    @Test
    fun `a conversation matching both title and content is returned once with the stronger reason`() {
        val results = mergeConversationRecall(
            contentHits = listOf(contentHit("both", "notes", rawRank = -50.0, updateAt = 100)),
            titleMatches = listOf(ConversationTitleMatch("both", "pricing", 700)),
            plan = plan,
        )

        assertEquals(1, results.size)
        assertEquals("content", results.single().matchType)
        assertEquals(700L, results.single().timestamp)
    }

    @Test
    fun `garbage matches below the floor are dropped`() {
        val results = mergeConversationRecall(
            // -1e-6 is what FTS5 yields when the only matched term has a clamped, useless idf.
            contentHits = listOf(contentHit("garbage", "unrelated", rawRank = -1e-6)),
            titleMatches = listOf(ConversationTitleMatch("unrelated-title", "nothing in common", 1)),
            plan = plan,
        )

        assertTrue("expected no results, got $results", results.isEmpty())
    }

    private fun contentHit(
        id: String,
        title: String,
        rawRank: Double,
        updateAt: Long = 1_000,
    ) = ConversationRecallHit(
        conversationId = id,
        title = title,
        updateAt = updateAt,
        snippet = "[$title]",
        rawRank = rawRank,
    )
}
