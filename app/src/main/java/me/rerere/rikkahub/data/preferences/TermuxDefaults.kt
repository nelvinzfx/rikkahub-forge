package me.rerere.rikkahub.data.preferences

/**
 * Authoritative defaults and clamp helpers for every Termux-configurable knob. Mirrors
 * [me.rerere.rikkahub.browser.BrowserToolDefaults] in shape — one object, constants +
 * clamp functions. All ceilings are deliberate hard limits per the CLAUDE.md rule that
 * every tool MUST have a hard timeout.
 */
object TermuxDefaults {

    // --- Command capture timeout -----------------------------------------------------------
    /** Default ms to wait for a Termux background command to complete. */
    const val DEFAULT_COMMAND_TIMEOUT_MS = 60_000L   // 60 s
    const val MIN_COMMAND_TIMEOUT_MS     =  5_000L   //  5 s
    /** Hard ceiling: 10 min (600 s). Same as BrowserToolDefaults' per-tool max. */
    const val MAX_COMMAND_TIMEOUT_MS     = 600_000L  // 10 min

    // --- Per-tool call timeout (app-wide) -------------------------------------------------
    // Each invocation gets this full timeout. Model inference and earlier tool calls do not
    // consume it, so a long multi-step generation cannot starve a later call before it starts.
    const val DEFAULT_TOOL_CALL_TIMEOUT_MS =  30L * 60L * 1_000L  //  30 min
    const val MIN_TOOL_CALL_TIMEOUT_MS     =   1L * 60L * 1_000L  //   1 min
    const val MAX_TOOL_CALL_TIMEOUT_MS     = 120L * 60L * 1_000L  // 120 min

    // --- Verify smoke-test timeout ---------------------------------------------------------
    const val DEFAULT_VERIFY_TIMEOUT_MS =  8_000L   //  8 s
    const val MIN_VERIFY_TIMEOUT_MS     =  3_000L   //  3 s
    const val MAX_VERIFY_TIMEOUT_MS     = 30_000L   // 30 s

    // --- Working directory -----------------------------------------------------------------
    const val DEFAULT_WORKING_DIR = "/data/data/com.termux/files/home"

    // --- Stdout / stderr capture caps (bytes) ----------------------------------------------
    const val DEFAULT_MAX_STDOUT =  8_000
    const val MIN_MAX_STDOUT     =  1_000
    const val MAX_MAX_STDOUT     = 49_152

    const val DEFAULT_MAX_STDERR =  2_000
    const val MIN_MAX_STDERR     =    500
    const val MAX_MAX_STDERR     = 12_288

    // --- Termux file-read budgets ---------------------------------------------------------
    const val DEFAULT_TEXT_READ_MAX_LINES = 2_000
    const val MIN_TEXT_READ_MAX_LINES = 1
    const val MAX_TEXT_READ_MAX_LINES = 10_000

    const val DEFAULT_TEXT_READ_MAX_BYTES = 51_200
    const val MIN_TEXT_READ_MAX_BYTES = 4_096
    const val MAX_TEXT_READ_MAX_BYTES = 61_440

    // --- Native output-spool retention ----------------------------------------------------
    const val DEFAULT_MAX_RETAINED_OUTPUT_JOBS = 50
    const val MIN_MAX_RETAINED_OUTPUT_JOBS = 1
    const val MAX_MAX_RETAINED_OUTPUT_JOBS = 200

    const val DEFAULT_OUTPUT_TTL_MS = 24L * 60L * 60L * 1_000L
    const val MIN_OUTPUT_TTL_MS = 1L * 60L * 60L * 1_000L
    const val MAX_OUTPUT_TTL_MS = 7L * 24L * 60L * 60L * 1_000L

    // --- apt-wrap default ------------------------------------------------------------------
    /** ON by default — preserves the prior behavior for existing users. */
    const val DEFAULT_APT_WRAP_ENABLED = true

    // --- Max per-call timeout_seconds ceiling (LLM-exposed arg) ---------------------------
    /** Raised from 300 to 600 s so it aligns with the configurable command timeout ceiling. */
    const val MAX_COMMAND_TIMEOUT_SECONDS = 600

    // --- Clamp helpers ---------------------------------------------------------------------

    fun clampCommandTimeoutMs(ms: Long): Long =
        ms.coerceIn(MIN_COMMAND_TIMEOUT_MS, MAX_COMMAND_TIMEOUT_MS)

    fun clampToolCallTimeoutMs(ms: Long): Long =
        ms.coerceIn(MIN_TOOL_CALL_TIMEOUT_MS, MAX_TOOL_CALL_TIMEOUT_MS)

    /** Resolve the renamed setting while preserving an explicitly saved legacy value. */
    fun resolveToolCallTimeoutMs(configuredMs: Long?, legacyTurnBudgetMs: Long?): Long =
        clampToolCallTimeoutMs(
            configuredMs ?: legacyTurnBudgetMs ?: DEFAULT_TOOL_CALL_TIMEOUT_MS
        )

    fun clampVerifyTimeoutMs(ms: Long): Long =
        ms.coerceIn(MIN_VERIFY_TIMEOUT_MS, MAX_VERIFY_TIMEOUT_MS)

    fun clampMaxStdout(bytes: Int): Int =
        bytes.coerceIn(MIN_MAX_STDOUT, MAX_MAX_STDOUT)

    fun clampMaxStderr(bytes: Int): Int =
        bytes.coerceIn(MIN_MAX_STDERR, MAX_MAX_STDERR)

    fun clampTextReadMaxLines(lines: Int): Int =
        lines.coerceIn(MIN_TEXT_READ_MAX_LINES, MAX_TEXT_READ_MAX_LINES)

    fun clampTextReadMaxBytes(bytes: Int): Int =
        bytes.coerceIn(MIN_TEXT_READ_MAX_BYTES, MAX_TEXT_READ_MAX_BYTES)

    fun validCombinedHeadBytes(stdoutBytes: Int, stderrBytes: Int): Boolean =
        stdoutBytes in MIN_MAX_STDOUT..MAX_MAX_STDOUT &&
            stderrBytes in MIN_MAX_STDERR..MAX_MAX_STDERR &&
            stdoutBytes.toLong() + stderrBytes.toLong() <= 61_440L

    fun clampMaxRetainedOutputJobs(count: Int): Int =
        count.coerceIn(MIN_MAX_RETAINED_OUTPUT_JOBS, MAX_MAX_RETAINED_OUTPUT_JOBS)

    fun clampOutputTtlMs(ms: Long): Long =
        ms.coerceIn(MIN_OUTPUT_TTL_MS, MAX_OUTPUT_TTL_MS)

    /**
     * Non-empty rule for the working directory. An empty value would pass a blank string to
     * Termux's RUN_COMMAND intent, which silently falls back to Termux's internal default —
     * a confusing outcome. Clamp by returning the default if the supplied value is blank.
     */
    fun clampWorkingDir(dir: String): String =
        dir.ifBlank { DEFAULT_WORKING_DIR }
}
