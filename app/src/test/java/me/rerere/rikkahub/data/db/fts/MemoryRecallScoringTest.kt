package me.rerere.rikkahub.data.db.fts

import me.rerere.rikkahub.data.search.RecallScore
import me.rerere.rikkahub.data.search.RecallSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** memory_fts post-processing: the exposed score must come from bm25, not from a substring tally. */
class MemoryRecallScoringTest {
    private val plan = RecallSearch.plan("UnifiedProxy pricing bug")

    @Test
    fun `memory score is the normalized bm25 and not a term tally`() {
        val tallyFavourite = hit(id = 1, title = "UnifiedProxy pricing bug", rawBm25 = -0.3)
        val bm25Favourite = hit(id = 2, title = "gateway notes", rawBm25 = -9.0)

        val ranked = rankMemoryHits(listOf(tallyFavourite, bm25Favourite), plan, limit = 10)

        // The old tally scored the title-stuffed row far higher; bm25 ordering wins now.
        assertEquals(listOf(2, 1), ranked.map { it.id })
        assertEquals(RecallScore.normalize(-9.0), ranked.first().score, 1e-9)
        assertEquals(RecallScore.normalize(-0.3), ranked.last().score, 1e-9)
    }

    @Test
    fun `scores stay inside 0 to 1 and respect the limit`() {
        val ranked = rankMemoryHits(
            hits = listOf(
                hit(id = 1, rawBm25 = -0.4),
                hit(id = 2, rawBm25 = -3.0),
                hit(id = 3, rawBm25 = -900.0),
            ),
            plan = plan,
            limit = 2,
        )

        assertEquals(2, ranked.size)
        ranked.forEach { assertTrue("score ${it.score} out of range", it.score in 0.0..1.0) }
        assertTrue(ranked.first().score >= ranked.last().score)
    }

    @Test
    fun `sub floor noise is dropped instead of being shown to the model`() {
        val ranked = rankMemoryHits(
            hits = listOf(hit(id = 1, rawBm25 = -1e-6), hit(id = 2, rawBm25 = -4.0)),
            plan = plan,
            limit = 10,
        )

        assertEquals(listOf(2), ranked.map { it.id })
    }

    @Test
    fun `importance and recency only break ties between equal scores`() {
        val ranked = rankMemoryHits(
            hits = listOf(
                hit(id = 1, rawBm25 = -2.0, importance = 10, updatedAt = 100),
                hit(id = 2, rawBm25 = -2.0, importance = 90, updatedAt = 50),
                hit(id = 3, rawBm25 = -2.0, importance = 90, updatedAt = 400),
            ),
            plan = plan,
            limit = 10,
        )

        assertEquals(listOf(3, 2, 1), ranked.map { it.id })
        assertEquals(1, ranked.map { it.score }.distinct().size)
    }

    private fun hit(
        id: Int,
        title: String = "note $id",
        rawBm25: Double,
        importance: Int = 0,
        updatedAt: Long = 0,
    ) = MemorySearchHit(
        id = id,
        title = title,
        content = "content for $id",
        mode = "bank",
        tags = "topic:test",
        importance = importance,
        updatedAt = updatedAt,
        sourceConversationId = null,
        score = rawBm25,
        snippet = "",
    )
}
