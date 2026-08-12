package me.rerere.rikkahub.data.search

import kotlin.math.roundToInt

/**
 * Relevance scoring shared by the two agent-facing recall features:
 * `search_memories` (memory_fts) and `search_conversations` (message_fts).
 *
 * Both features rank with SQLite FTS5 `bm25()` / `rank`, which returns a NEGATIVE
 * number where a *lower* value is a better match: a strong rare-term hit sits far
 * below zero, while a hit that only matched an ultra-common term sits right next to
 * 0.0. That raw number is meaningless to an LLM, so we publish a normalized score.
 *
 * Normalization (deliberate choice): a bounded, saturating transform of the bm25
 * magnitude `r = max(0, -bm25)`
 *
 *     score = r / (r + HALF_SATURATION)
 *
 * Why this rather than "relative to the best hit in the result set":
 *  - it is absolute, so scores stay comparable across queries, across limits/paging,
 *    and — the point of exposing `score` on both tools — between memory hits and
 *    conversation hits;
 *  - it is strictly monotone in bm25 (a better match always scores higher) and lands
 *    inside (0, 1) without clamping artifacts, whereas relative-to-best would award
 *    1.0 to the top row even when every row in the set is junk.
 */
internal object RecallScore {

    /**
     * bm25 magnitude that maps to 0.5. FTS5's bm25 magnitude is roughly the sum over
     * matched terms of `idf * saturated-tf`, so on a personal-scale corpus one
     * moderately selective term lands around 0.2..0.5 and a multi-term hit
     * (r >= 6) lands above 0.75. 2.0 therefore puts the interesting range in the
     * middle of the scale instead of squashing everything against an endpoint.
     */
    private const val HALF_SATURATION = 2.0

    /**
     * Minimum score a result must reach to be returned at all.
     *
     * Justification: FTS5 clamps a non-positive idf to 1e-6, so a term that occurs in
     * more than about half of the indexed rows contributes essentially nothing. A row
     * whose only reason to match is such a term ends up with r ~ 1e-6, i.e.
     * score ~ 5e-7 — that is the garbage tail we want gone (it is also what a
     * stop-word-only query degenerates into, because [RecallSearch.plan] keeps raw
     * stop words as fallback terms rather than returning nothing). The cut is kept
     * low on purpose: a genuine single-term hit in a small corpus (idf ~ 0.5 =>
     * score ~ 0.2) still surfaces, so recall is not sacrificed.
     */
    const val FLOOR = 0.05

    /**
     * Highest score a LIKE-matched title can reach without containing the whole query
     * phrase, so that a full phrase hit (1.0) always outranks partial coverage.
     */
    private const val TITLE_PARTIAL_CEILING = 0.9

    /** Map a raw FTS5 bm25/rank value to a comparable 0..1 relevance score. */
    fun normalize(rawBm25: Double): Double {
        if (rawBm25.isNaN()) return 0.0
        val relevance = if (rawBm25 < 0.0) -rawBm25 else 0.0
        if (relevance <= 0.0) return 0.0
        if (!relevance.isFinite()) return 1.0
        return round(relevance / (relevance + HALF_SATURATION))
    }

    /** True when [score] is worth showing to the model; see [FLOOR]. */
    fun passesFloor(score: Double): Boolean = score >= FLOOR

    /**
     * Score for a conversation title matched with LIKE. Title matching stays a LIKE
     * over `conversationentity` (one row per conversation, so no index is needed) and
     * therefore has no bm25 to normalize. The only signal available is how much of the
     * query the title covers, which is already a 0..1 quantity:
     *
     *  - title contains the whole query phrase -> 1.0
     *  - otherwise -> TITLE_PARTIAL_CEILING * matchedTerms / totalTerms
     *  - no term matched -> 0.0
     */
    fun titleCoverage(title: String, plan: RecallSearchPlan): Double {
        if (title.isBlank() || plan.isEmpty) return 0.0
        if (plan.phrase.isNotBlank() && title.contains(plan.phrase, ignoreCase = true)) return 1.0
        if (plan.terms.isEmpty()) return 0.0
        val matched = plan.terms.count { term -> title.contains(term, ignoreCase = true) }
        if (matched == 0) return 0.0
        return round(TITLE_PARTIAL_CEILING * matched / plan.terms.size)
    }

    private fun round(value: Double): Double =
        (value.coerceIn(0.0, 1.0) * 10_000).roundToInt() / 10_000.0
}
