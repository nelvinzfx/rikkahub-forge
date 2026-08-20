package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.ReasoningLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for resolveTurnReasoningLevel.
 *
 * Defect history (2026-08-21): the resolution used to clamp the assistant's reasoning
 * level to XHIGH whenever orchestrator mode was not OFF. AUTO is the conversation
 * default, so the clamp fired for ordinary chat and rewrote a user-selected MAX into
 * the wire effort "xhigh", which strict providers (z.ai GLM-5.3 via OpenAI-compatible
 * gateways) reject with HTTP 400 code 1210 ("This model always engages in thinking
 * and cannot be disabled; please use low, high, or max"). Test sensitivity: restoring
 * any clamp below MAX fails the first test's effort assertion.
 */
class ResolveTurnReasoningLevelTest {

    @Test
    fun `assistant MAX is sent verbatim, not clamped to XHIGH`() {
        val resolved = resolveTurnReasoningLevel(
            reasoningLevelOverride = null,
            assistantReasoningLevel = ReasoningLevel.MAX,
        )
        assertEquals(ReasoningLevel.MAX, resolved)
        assertEquals("max", resolved.effort)
    }

    @Test
    fun `override wins over the assistant level`() {
        val resolved = resolveTurnReasoningLevel(
            reasoningLevelOverride = ReasoningLevel.HIGH,
            assistantReasoningLevel = ReasoningLevel.MAX,
        )
        assertEquals(ReasoningLevel.HIGH, resolved)
        assertEquals("high", resolved.effort)
    }

    @Test
    fun `no override returns the assistant level unchanged`() {
        val resolved = resolveTurnReasoningLevel(
            reasoningLevelOverride = null,
            assistantReasoningLevel = ReasoningLevel.LOW,
        )
        assertEquals(ReasoningLevel.LOW, resolved)
        assertEquals("low", resolved.effort)
    }
}
