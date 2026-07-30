package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.entity.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [MemoryRepository.budgetCoreMemories] — the pure
 * token-budget calculation that [MemoryRepository.getCoreMemories] delegates
 * to. Runs on the JVM without Room or instrumentation.
 *
 * Production cost formula: content.length + title.length + tags.length + 48
 * Character budget: tokenBudget * 4 (coerced to ≥ 0)
 */
class MemoryRepositoryTest {

    private fun memory(
        id: Int,
        content: String = "",
        title: String = "",
        tags: String = "",
        importance: Int = 0,
    ) = MemoryEntity(
        id = id,
        assistantId = "test-assistant",
        content = content,
        title = title,
        tags = tags,
        importance = importance,
    )

    /** Production cost formula replicated for assertions. */
    private fun costOf(row: MemoryEntity): Int =
        row.content.length + row.title.length + row.tags.length + 48

    // 1 — zero budget

    @Test
    fun `budgetCoreMemories returns no memories when token budget is zero`() {
        val rows = listOf(
            memory(id = 1, content = "hello"),
            memory(id = 2, content = "world"),
        )
        val result = MemoryRepository.budgetCoreMemories(rows, tokenBudget = 0)
        assertTrue("expected empty list for zero budget", result.isEmpty())
    }

    // 2 — entries that fit are included

    @Test
    fun `budgetCoreMemories includes entries that fit within the character budget`() {
        // cost: 5+48=53 each; budget 50 tokens -> 200 chars; both fit (106 <= 200)
        val rows = listOf(
            memory(id = 1, content = "hello"),
            memory(id = 2, content = "world"),
        )
        val result = MemoryRepository.budgetCoreMemories(rows, tokenBudget = 50)
        assertEquals(2, result.size)
        assertEquals(listOf(1, 2), result.map { it.id })
    }

    // 3 — oversized entry skipped, later smaller entry still fits

    @Test
    fun `budgetCoreMemories skips an oversized entry but still includes a later smaller one`() {
        // big: 100+48=148; small: 10+48=58; budget 30 tokens -> 120 chars
        // 148 > 120 -> skip; 58 <= 120 -> include
        val rows = listOf(
            memory(id = 1, content = "x".repeat(100)),
            memory(id = 2, content = "y".repeat(10)),
        )
        val result = MemoryRepository.budgetCoreMemories(rows, tokenBudget = 30)
        assertEquals(1, result.size)
        assertEquals(2, result[0].id)
    }

    // 4 — DAO ordering preserved

    @Test
    fun `budgetCoreMemories preserves DAO ordering in the output`() {
        // Simulate DAO order (importance DESC, updated_at DESC, id DESC).
        // Mix of fitting and oversized entries to verify order is preserved among included ones.
        val rows = listOf(
            memory(id = 30, content = "ccc", importance = 10),            // cost 51, fits
            memory(id = 20, content = "x".repeat(200), importance = 50),  // cost 248, skipped
            memory(id = 10, content = "aaa", importance = 90),            // cost 51, fits
        )
        // Budget 30 tokens -> 120 chars: 30 (51) fits, 20 (248) skipped, 10 (51) fits (102 <= 120)
        val result = MemoryRepository.budgetCoreMemories(rows, tokenBudget = 30)
        assertEquals(
            "included entries must preserve input order",
            listOf(30, 10),
            result.map { it.id },
        )
    }

    // 5 — never exceeds the configured budget

    @Test
    fun `budgetCoreMemories never exceeds the configured character budget`() {
        val sizes = listOf(30, 60, 15, 90, 45, 10, 70, 25, 50, 35)
        val rows = sizes.mapIndexed { i, len -> memory(id = i + 1, content = "x".repeat(len)) }
        // 80 tokens -> 320 chars. Costs: [78,108,63,138,93,58,118,73,98,83]
        // Entries 1,2,3 fit (78+108+63=249, remaining 71). Entry 4 (138) skipped.
        // Entry 6 (58) fits (remaining 71->13). Rest skipped.
        val tokenBudget = 80
        val result = MemoryRepository.budgetCoreMemories(rows, tokenBudget)

        val charBudget = tokenBudget * 4
        val totalCost = result.sumOf(::costOf)
        assertTrue(
            "total cost $totalCost must not exceed character budget $charBudget",
            totalCost <= charBudget,
        )
        // At least one entry should have been included (otherwise the test is vacuous)
        assertTrue("expected at least one entry to fit", result.isNotEmpty())
    }

    // Bonus — negative budget coerces to zero

    @Test
    fun `budgetCoreMemories returns empty for negative token budget`() {
        val rows = listOf(memory(id = 1, content = "hello"))
        val result = MemoryRepository.budgetCoreMemories(rows, tokenBudget = -10)
        assertTrue(result.isEmpty())
    }

    // Bonus — boundary: cost exactly equals remaining budget

    @Test
    fun `budgetCoreMemories includes an entry whose cost exactly equals the remaining budget`() {
        // cost of empty row = 0+0+0+48 = 48; budget 12 tokens -> 48 chars; 48 <= 48 -> included
        // second entry also costs 48 but remaining is now 0 -> skipped
        val rows = listOf(
            memory(id = 1),
            memory(id = 2),
        )
        val result = MemoryRepository.budgetCoreMemories(rows, tokenBudget = 12)
        assertEquals("first entry (cost=48) should fit exactly in 48-char budget", 1, result.size)
        assertEquals(1, result[0].id)
    }
}
