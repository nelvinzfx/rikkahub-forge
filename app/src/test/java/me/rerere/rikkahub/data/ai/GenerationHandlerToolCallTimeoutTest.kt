package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.ai.limits.ToolCallTimeoutBudget
import me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits
import me.rerere.rikkahub.data.preferences.TermuxDefaults
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Regression coverage for independent per-tool timeouts. */
class GenerationHandlerToolCallTimeoutTest {

    private var previousTimeoutMs: Long = 0L

    @Before
    fun save() {
        previousTimeoutMs = ToolRuntimeLimits.toolCallTimeoutMs
    }

    @After
    fun restore() {
        ToolRuntimeLimits.toolCallTimeoutMs = previousTimeoutMs
    }

    @Test
    fun defaultToolCallTimeout_is30Minutes() {
        assertEquals(30L * 60L * 1_000L, TermuxDefaults.DEFAULT_TOOL_CALL_TIMEOUT_MS)
    }

    @Test
    fun runtimeDefault_matchesConfiguredDefault() {
        assertEquals(TermuxDefaults.DEFAULT_TOOL_CALL_TIMEOUT_MS, previousTimeoutMs)
    }

    @Test
    fun eachInvocationReceivesTheFullCurrentTimeout() {
        var configuredMs = 30L * 60_000L
        val budget = ToolCallTimeoutBudget { configuredMs }

        assertEquals(30L * 60_000L, budget.nextTimeoutMs())
        assertEquals(30L * 60_000L, budget.nextTimeoutMs())

        configuredMs = 90L * 60_000L
        assertEquals(90L * 60_000L, budget.nextTimeoutMs())
    }

    @Test
    fun runtimeTimeout_canBeChangedImmediately() {
        val updated = 45L * 60_000L
        ToolRuntimeLimits.toolCallTimeoutMs = updated
        assertEquals(updated, ToolRuntimeLimits.toolCallTimeoutMs)
    }

    @Test
    fun timeoutBudget_neverReturnsZero() {
        assertEquals(1L, ToolCallTimeoutBudget { 0L }.nextTimeoutMs())
    }
}
