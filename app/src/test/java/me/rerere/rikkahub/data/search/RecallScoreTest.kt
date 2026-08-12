package me.rerere.rikkahub.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bounds and monotonicity of the bm25 -> 0..1 normalization shared by both recall tools. */
class RecallScoreTest {
    @Test
    fun `normalization is bounded to 0 until 1 for any bm25 value`() {
        val raws = listOf(
            0.0, -1e-9, -1e-6, -0.1, -0.5, -1.0, -2.0, -6.0, -50.0, -1e9,
            Double.NEGATIVE_INFINITY, 3.0, Double.NaN,
        )

        raws.forEach { raw ->
            val score = RecallScore.normalize(raw)
            assertTrue("score $score out of range for bm25 $raw", score in 0.0..1.0)
        }
    }

    @Test
    fun `a better bm25 always yields a strictly higher score`() {
        val ascending = listOf(-0.1, -0.5, -1.0, -2.0, -6.0, -50.0).map(RecallScore::normalize)

        assertEquals(ascending.sorted(), ascending)
        assertEquals(ascending.distinct().size, ascending.size)
        assertEquals(0.5, RecallScore.normalize(-2.0), 1e-9)
    }

    @Test
    fun `non matches and positive bm25 score zero`() {
        assertEquals(0.0, RecallScore.normalize(0.0), 1e-9)
        assertEquals(0.0, RecallScore.normalize(1.5), 1e-9)
        assertEquals(0.0, RecallScore.normalize(Double.NaN), 1e-9)
    }

    @Test
    fun `floor rejects clamped idf noise but keeps a real single term hit`() {
        // FTS5 clamps a useless (over-common) term's idf to 1e-6 -> effectively no relevance.
        assertFalse(RecallScore.passesFloor(RecallScore.normalize(-1e-6)))
        // A genuinely selective single-term hit must still surface.
        assertTrue(RecallScore.passesFloor(RecallScore.normalize(-0.5)))
        assertTrue(RecallScore.passesFloor(RecallScore.FLOOR))
    }

    @Test
    fun `title coverage is bounded and rewards the full phrase`() {
        val plan = RecallSearch.plan("UnifiedProxy pricing bug")

        val full = RecallScore.titleCoverage("UnifiedProxy pricing bug report", plan)
        val partial = RecallScore.titleCoverage("pricing notes", plan)
        val none = RecallScore.titleCoverage("unrelated title", plan)

        assertEquals(1.0, full, 1e-9)
        assertTrue(partial in 0.0..1.0)
        assertTrue(partial < full)
        assertEquals(0.0, none, 1e-9)
    }

    @Test
    fun `an empty plan scores nothing`() {
        val empty = RecallSearch.plan("?! --")

        assertTrue(empty.isEmpty)
        assertEquals(0.0, RecallScore.titleCoverage("anything at all", empty), 1e-9)
    }

    @Test
    fun `a stopword only query degenerates to sub floor scores`() {
        // plan() keeps raw stop words as fallback terms, so the query still runs; the resulting
        // bm25 magnitude is idf-clamped noise, which the floor is there to discard.
        val plan = RecallSearch.plan("the and of")

        assertFalse(plan.isEmpty)
        assertFalse(RecallScore.passesFloor(RecallScore.normalize(-1e-6)))
        assertTrue(RecallScore.titleCoverage("the and of everything", plan) > 0.0)
    }
}
