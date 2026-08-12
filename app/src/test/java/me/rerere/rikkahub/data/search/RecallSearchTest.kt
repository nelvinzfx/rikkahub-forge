package me.rerere.rikkahub.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecallSearchTest {
    @Test
    fun `punctuation only query is empty`() {
        assertTrue(RecallSearch.plan("?! --").isEmpty)
    }

    @Test
    fun `plan keeps multi-word intent and splits camel case`() {
        val plan = RecallSearch.plan("UnifiedProxy pricing bug")

        assertEquals("unifiedproxy pricing bug", plan.phrase)
        assertTrue(plan.terms.contains("unifiedproxy"))
        assertTrue(plan.terms.contains("unified"))
        assertTrue(plan.terms.contains("proxy"))
        assertTrue(plan.terms.contains("pricing"))
        assertTrue(plan.terms.contains("bug"))
    }

    @Test
    fun `fts query uses OR so one missing term does not erase related matches`() {
        val plan = RecallSearch.plan("UnifiedProxy pricing bug")

        assertEquals(
            "\"unifiedproxy\"* OR \"unified\"* OR \"proxy\"* OR \"pricing\"* OR \"bug\"*",
            RecallSearch.toFtsOrQuery(plan),
        )
    }

    // The former `conversation score rewards coverage across separate snippets` test was removed
    // together with RecallSearch.scoreConversation: relevance now comes from FTS5 bm25
    // (see RecallScoreTest / ConversationRecallMergeTest).

    @Test
    fun `plain text for jieba_query strips fts operator characters`() {
        assertEquals(
            "why did UnifiedProxy fail",
            RecallSearch.toFtsPlainText("why did (UnifiedProxy) fail?"),
        )
        assertEquals("cost NEAR limit", RecallSearch.toFtsPlainText("  cost \"NEAR\" -limit  "))
        assertEquals("", RecallSearch.toFtsPlainText("?! --"))
    }
}
