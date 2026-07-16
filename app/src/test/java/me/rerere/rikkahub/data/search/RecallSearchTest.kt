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

    @Test
    fun `conversation score rewards coverage across separate snippets`() {
        val plan = RecallSearch.plan("UnifiedProxy pricing bug")
        val coveredAcrossSnippets = RecallSearch.scoreConversation(
            title = "api probe",
            matchedTexts = listOf("UnifiedProxy gateway trace", "pricing page returned stale bug"),
            plan = plan,
        )
        val oneTermOnly = RecallSearch.scoreConversation(
            title = "api probe",
            matchedTexts = listOf("pricing details only"),
            plan = plan,
        )

        assertTrue(coveredAcrossSnippets > oneTermOnly)
    }
}
