package me.rerere.rikkahub.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin coverage for the Termux configurable-knob clamping helpers introduced for
 * GitHub issue #5. Mirrors [me.rerere.rikkahub.browser.BrowserTimeoutClampTest] in
 * structure — every clamp function gets floor / ceiling / in-range / default tests.
 */
class TermuxDefaultsTest {

    // --- clampCommandTimeoutMs ------------------------------------------------------------

    @Test
    fun commandTimeout_belowFloor_snapsToMin() {
        assertEquals(TermuxDefaults.MIN_COMMAND_TIMEOUT_MS,
            TermuxDefaults.clampCommandTimeoutMs(0L))
        assertEquals(TermuxDefaults.MIN_COMMAND_TIMEOUT_MS,
            TermuxDefaults.clampCommandTimeoutMs(-1L))
        assertEquals(TermuxDefaults.MIN_COMMAND_TIMEOUT_MS,
            TermuxDefaults.clampCommandTimeoutMs(TermuxDefaults.MIN_COMMAND_TIMEOUT_MS - 1L))
    }

    @Test
    fun commandTimeout_aboveCeiling_snapsToMax() {
        assertEquals(TermuxDefaults.MAX_COMMAND_TIMEOUT_MS,
            TermuxDefaults.clampCommandTimeoutMs(Long.MAX_VALUE))
        assertEquals(TermuxDefaults.MAX_COMMAND_TIMEOUT_MS,
            TermuxDefaults.clampCommandTimeoutMs(TermuxDefaults.MAX_COMMAND_TIMEOUT_MS + 1L))
    }

    @Test
    fun commandTimeout_inRange_passesThrough() {
        assertEquals(TermuxDefaults.DEFAULT_COMMAND_TIMEOUT_MS,
            TermuxDefaults.clampCommandTimeoutMs(TermuxDefaults.DEFAULT_COMMAND_TIMEOUT_MS))
        assertEquals(30_000L, TermuxDefaults.clampCommandTimeoutMs(30_000L))
        assertEquals(TermuxDefaults.MIN_COMMAND_TIMEOUT_MS,
            TermuxDefaults.clampCommandTimeoutMs(TermuxDefaults.MIN_COMMAND_TIMEOUT_MS))
        assertEquals(TermuxDefaults.MAX_COMMAND_TIMEOUT_MS,
            TermuxDefaults.clampCommandTimeoutMs(TermuxDefaults.MAX_COMMAND_TIMEOUT_MS))
    }

    // --- clampToolCallTimeoutMs -----------------------------------------------------------

    @Test
    fun toolCallTimeout_belowFloor_snapsToMin() {
        assertEquals(TermuxDefaults.MIN_TOOL_CALL_TIMEOUT_MS,
            TermuxDefaults.clampToolCallTimeoutMs(0L))
        assertEquals(TermuxDefaults.MIN_TOOL_CALL_TIMEOUT_MS,
            TermuxDefaults.clampToolCallTimeoutMs(TermuxDefaults.MIN_TOOL_CALL_TIMEOUT_MS - 1L))
    }

    @Test
    fun toolCallTimeout_aboveCeiling_snapsToMax() {
        assertEquals(TermuxDefaults.MAX_TOOL_CALL_TIMEOUT_MS,
            TermuxDefaults.clampToolCallTimeoutMs(Long.MAX_VALUE))
        assertEquals(TermuxDefaults.MAX_TOOL_CALL_TIMEOUT_MS,
            TermuxDefaults.clampToolCallTimeoutMs(TermuxDefaults.MAX_TOOL_CALL_TIMEOUT_MS + 1L))
    }

    @Test
    fun toolCallTimeout_inRange_passesThrough() {
        assertEquals(TermuxDefaults.DEFAULT_TOOL_CALL_TIMEOUT_MS,
            TermuxDefaults.clampToolCallTimeoutMs(TermuxDefaults.DEFAULT_TOOL_CALL_TIMEOUT_MS))
        assertEquals(5L * 60_000L, TermuxDefaults.clampToolCallTimeoutMs(5L * 60_000L))
    }

    @Test
    fun toolCallTimeout_resolver_prefersNewKey_thenLegacy_thenDefault() {
        assertEquals(
            45L * 60_000L,
            TermuxDefaults.resolveToolCallTimeoutMs(45L * 60_000L, 10L * 60_000L),
        )
        assertEquals(
            10L * 60_000L,
            TermuxDefaults.resolveToolCallTimeoutMs(null, 10L * 60_000L),
        )
        assertEquals(
            TermuxDefaults.DEFAULT_TOOL_CALL_TIMEOUT_MS,
            TermuxDefaults.resolveToolCallTimeoutMs(null, null),
        )
    }

    // --- clampVerifyTimeoutMs -------------------------------------------------------------

    @Test
    fun verifyTimeout_belowFloor_snapsToMin() {
        assertEquals(TermuxDefaults.MIN_VERIFY_TIMEOUT_MS,
            TermuxDefaults.clampVerifyTimeoutMs(0L))
        assertEquals(TermuxDefaults.MIN_VERIFY_TIMEOUT_MS,
            TermuxDefaults.clampVerifyTimeoutMs(TermuxDefaults.MIN_VERIFY_TIMEOUT_MS - 1L))
    }

    @Test
    fun verifyTimeout_aboveCeiling_snapsToMax() {
        assertEquals(TermuxDefaults.MAX_VERIFY_TIMEOUT_MS,
            TermuxDefaults.clampVerifyTimeoutMs(Long.MAX_VALUE))
    }

    @Test
    fun verifyTimeout_inRange_passesThrough() {
        assertEquals(TermuxDefaults.DEFAULT_VERIFY_TIMEOUT_MS,
            TermuxDefaults.clampVerifyTimeoutMs(TermuxDefaults.DEFAULT_VERIFY_TIMEOUT_MS))
    }

    // --- clampMaxStdout / clampMaxStderr --------------------------------------------------

    @Test
    fun maxStdout_belowFloor_snapsToMin() {
        assertEquals(TermuxDefaults.MIN_MAX_STDOUT, TermuxDefaults.clampMaxStdout(0))
        assertEquals(TermuxDefaults.MIN_MAX_STDOUT,
            TermuxDefaults.clampMaxStdout(TermuxDefaults.MIN_MAX_STDOUT - 1))
    }

    @Test
    fun maxStdout_aboveCeiling_snapsToMax() {
        assertEquals(TermuxDefaults.MAX_MAX_STDOUT, TermuxDefaults.clampMaxStdout(Int.MAX_VALUE))
    }

    @Test
    fun maxStdout_inRange_passesThrough() {
        assertEquals(TermuxDefaults.DEFAULT_MAX_STDOUT,
            TermuxDefaults.clampMaxStdout(TermuxDefaults.DEFAULT_MAX_STDOUT))
    }

    @Test
    fun maxStderr_belowFloor_snapsToMin() {
        assertEquals(TermuxDefaults.MIN_MAX_STDERR, TermuxDefaults.clampMaxStderr(0))
        assertEquals(TermuxDefaults.MIN_MAX_STDERR,
            TermuxDefaults.clampMaxStderr(TermuxDefaults.MIN_MAX_STDERR - 1))
    }

    @Test
    fun maxStderr_aboveCeiling_snapsToMax() {
        assertEquals(TermuxDefaults.MAX_MAX_STDERR, TermuxDefaults.clampMaxStderr(Int.MAX_VALUE))
        assertEquals(TermuxDefaults.MAX_MAX_STDERR,
            TermuxDefaults.clampMaxStderr(TermuxDefaults.MAX_MAX_STDERR + 1))
    }

    @Test
    fun maxStderr_inRange_passesThrough() {
        assertEquals(TermuxDefaults.DEFAULT_MAX_STDERR,
            TermuxDefaults.clampMaxStderr(TermuxDefaults.DEFAULT_MAX_STDERR))
    }

    @Test
    fun binderHeadBudgets_areCombinedAtSixtyKiB() {
        assertEquals(49_152, TermuxDefaults.MAX_MAX_STDOUT)
        assertEquals(12_288, TermuxDefaults.MAX_MAX_STDERR)
        assertTrue(TermuxDefaults.validCombinedHeadBytes(49_152, 12_288))
        assertFalse(TermuxDefaults.validCombinedHeadBytes(49_153, 12_288))
        assertFalse(TermuxDefaults.validCombinedHeadBytes(49_152, 12_289))
    }

    @Test
    fun outputRetention_clampsToNativeHardRanges() {
        assertEquals(1, TermuxDefaults.clampMaxRetainedOutputJobs(0))
        assertEquals(200, TermuxDefaults.clampMaxRetainedOutputJobs(Int.MAX_VALUE))
        assertEquals(50, TermuxDefaults.clampMaxRetainedOutputJobs(50))
        assertEquals(60L * 60L * 1_000L, TermuxDefaults.clampOutputTtlMs(0))
        assertEquals(7L * 24L * 60L * 60L * 1_000L, TermuxDefaults.clampOutputTtlMs(Long.MAX_VALUE))
        assertEquals(24L * 60L * 60L * 1_000L, TermuxDefaults.clampOutputTtlMs(TermuxDefaults.DEFAULT_OUTPUT_TTL_MS))
    }

    // --- clampWorkingDir ------------------------------------------------------------------

    @Test
    fun workingDir_blank_returnsDefault() {
        assertEquals(TermuxDefaults.DEFAULT_WORKING_DIR, TermuxDefaults.clampWorkingDir(""))
        assertEquals(TermuxDefaults.DEFAULT_WORKING_DIR, TermuxDefaults.clampWorkingDir("   "))
    }

    @Test
    fun workingDir_nonEmpty_passesThrough() {
        val custom = "/data/data/com.termux/files/home/myproject"
        assertEquals(custom, TermuxDefaults.clampWorkingDir(custom))
    }

    // --- Default values are within their own bounds (regression guard) --------------------

    @Test
    fun defaults_areWithinTheirOwnBounds() {
        assertEquals(TermuxDefaults.DEFAULT_COMMAND_TIMEOUT_MS,
            TermuxDefaults.clampCommandTimeoutMs(TermuxDefaults.DEFAULT_COMMAND_TIMEOUT_MS))
        assertEquals(TermuxDefaults.DEFAULT_TOOL_CALL_TIMEOUT_MS,
            TermuxDefaults.clampToolCallTimeoutMs(TermuxDefaults.DEFAULT_TOOL_CALL_TIMEOUT_MS))
        assertEquals(TermuxDefaults.DEFAULT_VERIFY_TIMEOUT_MS,
            TermuxDefaults.clampVerifyTimeoutMs(TermuxDefaults.DEFAULT_VERIFY_TIMEOUT_MS))
        assertEquals(TermuxDefaults.DEFAULT_MAX_STDOUT,
            TermuxDefaults.clampMaxStdout(TermuxDefaults.DEFAULT_MAX_STDOUT))
        assertEquals(TermuxDefaults.DEFAULT_MAX_STDERR,
            TermuxDefaults.clampMaxStderr(TermuxDefaults.DEFAULT_MAX_STDERR))
    }

    @Test
    fun toolCallTimeout_defaultIs30Minutes_andMaxIs120Minutes() {
        assertEquals(30L * 60L * 1_000L, TermuxDefaults.DEFAULT_TOOL_CALL_TIMEOUT_MS)
        assertEquals(120L * 60L * 1_000L, TermuxDefaults.MAX_TOOL_CALL_TIMEOUT_MS)
    }

    @Test
    fun commandTimeout_maxSecondsAligns600() {
        // Ceiling raised from 300 to 600 per spec override.
        assertEquals(600, TermuxDefaults.MAX_COMMAND_TIMEOUT_SECONDS)
        assertEquals(TermuxDefaults.MAX_COMMAND_TIMEOUT_MS,
            TermuxDefaults.MAX_COMMAND_TIMEOUT_SECONDS.toLong() * 1_000L)
    }
}
