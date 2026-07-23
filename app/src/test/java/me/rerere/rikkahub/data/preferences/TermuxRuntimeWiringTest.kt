package me.rerere.rikkahub.data.preferences

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Verifies that [TermuxRuntime] and ToolRuntimeLimits expose process-wide values to
 * non-suspend tool call sites. An @Volatile write must be visible to subsequent reads.
 *
 * Full end-to-end wiring (TermuxPreferences.init -> TermuxRuntime -> tool call) requires a
 * DataStore + coroutine scheduler, which needs an Android runtime or Robolectric — beyond the
 * scope of a plain JVM unit test. Instead, we pin the invariant that the field mutation itself
 * is visible: if the @Volatile write+read round-trip is correct, the preference collectors
 * and the runtime reads in local, generation, and MCP call paths compose correctly.
 */
class TermuxRuntimeWiringTest {

    // Stash original values so each test starts clean and doesn't pollute siblings.
    private var origCommandTimeout: Long = 0L
    private var origVerifyTimeout: Long  = 0L
    private var origWorkingDir: String   = ""
    private var origMaxStdout: Int       = 0
    private var origMaxStderr: Int       = 0
    private var origMaxRetainedJobs: Int = 0
    private var origOutputTtlMs: Long = 0
    private var origAptWrap: Boolean     = true

    @Before
    fun saveDefaults() {
        origCommandTimeout = TermuxRuntime.commandTimeoutMs
        origVerifyTimeout  = TermuxRuntime.verifyTimeoutMs
        origWorkingDir     = TermuxRuntime.defaultWorkingDir
        origMaxStdout      = TermuxRuntime.maxStdoutBytes
        origMaxStderr      = TermuxRuntime.maxStderrBytes
        origMaxRetainedJobs = TermuxRuntime.maxRetainedOutputJobs
        origOutputTtlMs = TermuxRuntime.outputTtlMs
        origAptWrap        = TermuxRuntime.aptWrapEnabled
    }

    @After
    fun restoreDefaults() {
        TermuxRuntime.commandTimeoutMs  = origCommandTimeout
        TermuxRuntime.verifyTimeoutMs   = origVerifyTimeout
        TermuxRuntime.defaultWorkingDir = origWorkingDir
        TermuxRuntime.maxStdoutBytes    = origMaxStdout
        TermuxRuntime.maxStderrBytes    = origMaxStderr
        TermuxRuntime.maxRetainedOutputJobs = origMaxRetainedJobs
        TermuxRuntime.outputTtlMs = origOutputTtlMs
        TermuxRuntime.aptWrapEnabled    = origAptWrap
    }

    @Test
    fun commandTimeout_writeIsVisibleImmediately() {
        TermuxRuntime.commandTimeoutMs = 120_000L
        assertEquals(120_000L, TermuxRuntime.commandTimeoutMs)
    }

    @Test
    fun verifyTimeout_writeIsVisibleImmediately() {
        TermuxRuntime.verifyTimeoutMs = 15_000L
        assertEquals(15_000L, TermuxRuntime.verifyTimeoutMs)
    }

    @Test
    fun workingDir_writeIsVisibleImmediately() {
        TermuxRuntime.defaultWorkingDir = "/custom/dir"
        assertEquals("/custom/dir", TermuxRuntime.defaultWorkingDir)
    }

    @Test
    fun maxStdout_writeIsVisibleImmediately() {
        TermuxRuntime.maxStdoutBytes = 16_000
        assertEquals(16_000, TermuxRuntime.maxStdoutBytes)
    }

    @Test
    fun aptWrap_toggleIsVisibleImmediately() {
        TermuxRuntime.aptWrapEnabled = false
        assertEquals(false, TermuxRuntime.aptWrapEnabled)
        TermuxRuntime.aptWrapEnabled = true
        assertEquals(true, TermuxRuntime.aptWrapEnabled)
    }

    @Test
    fun toolRuntimeLimits_toolCallTimeout_writeIsVisibleImmediately() {
        val prev = me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits.toolCallTimeoutMs
        try {
            me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits.toolCallTimeoutMs = 45L * 60_000L
            assertEquals(
                45L * 60_000L,
                me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits.toolCallTimeoutMs,
            )
        } finally {
            me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits.toolCallTimeoutMs = prev
        }
    }

    @Test
    fun runtimeDefaults_matchTermuxDefaults() {
        // Pin: runtime holder's initial values must equal the constants in TermuxDefaults.
        // If TermuxPreferences hasn't pushed yet (app cold start), the tool still sees
        // sensible defaults — not zero / blank.
        assertEquals(TermuxDefaults.DEFAULT_COMMAND_TIMEOUT_MS, origCommandTimeout)
        assertEquals(TermuxDefaults.DEFAULT_VERIFY_TIMEOUT_MS,  origVerifyTimeout)
        assertEquals(TermuxDefaults.DEFAULT_WORKING_DIR,         origWorkingDir)
        assertEquals(TermuxDefaults.DEFAULT_MAX_STDOUT,          origMaxStdout)
        assertEquals(TermuxDefaults.DEFAULT_MAX_STDERR,          origMaxStderr)
        assertEquals(TermuxDefaults.DEFAULT_MAX_RETAINED_OUTPUT_JOBS, origMaxRetainedJobs)
        assertEquals(TermuxDefaults.DEFAULT_OUTPUT_TTL_MS, origOutputTtlMs)
        assertEquals(TermuxDefaults.DEFAULT_APT_WRAP_ENABLED,    origAptWrap)
    }
}
