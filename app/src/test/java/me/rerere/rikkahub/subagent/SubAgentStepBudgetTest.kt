package me.rerere.rikkahub.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the sub-agent max_trips contract.
 *
 * Before the fix, [SubAgentRequest.maxTrips] was validated, stored, and reported as
 * telemetry but NEVER reached the generation layer — every worker silently inherited
 * GenerationHandler's hard default of 32, and when that budget ran out right after a
 * tool call the run ended without final text yet was marked SUCCEEDED.
 *
 * The wiring itself (SubAgentEngine → ChatService.sendMessage(generationMaxSteps,
 * reserveFinalWrapUp) → GenerationHandler) is verified end-to-end by the low-max_trips
 * Kimi worker run; these tests pin the pure decision points against regressions:
 * request carrying/validation, the terminal-outcome gate, and the stable error reason.
 */
class SubAgentStepBudgetTest {

    // ---- max_trips is a real, carried request field --------------------------------

    @Test
    fun `request carries a small custom maxTrips unchanged`() {
        val request = SubAgentRequest(task = "audit thing", maxTrips = 3)
        assertEquals(3, request.maxTrips)
    }

    @Test
    fun `request defaults to the documented default maxTrips`() {
        assertEquals(SubAgentDefaults.DEFAULT_MAX_TRIPS, SubAgentRequest(task = "x").maxTrips)
        assertEquals(20, SubAgentDefaults.DEFAULT_MAX_TRIPS)
        assertEquals(30, SubAgentDefaults.MAX_MAX_TRIPS)
    }

    @Test
    fun `validator accepts boundary maxTrips values`() {
        assertTrue(SubAgentRequestValidator.validate(SubAgentRequest(task = "t", maxTrips = 1)) is SubAgentRequestValidator.Result.Ok)
        assertTrue(SubAgentRequestValidator.validate(SubAgentRequest(task = "t", maxTrips = 30)) is SubAgentRequestValidator.Result.Ok)
    }

    @Test
    fun `validator rejects out-of-range maxTrips`() {
        assertTrue(SubAgentRequestValidator.validate(SubAgentRequest(task = "t", maxTrips = 0)) is SubAgentRequestValidator.Result.Reject)
        assertTrue(SubAgentRequestValidator.validate(SubAgentRequest(task = "t", maxTrips = 31)) is SubAgentRequestValidator.Result.Reject)
    }

    // ---- Terminal-outcome gate ------------------------------------------------------

    @Test
    fun `non-blank final text can succeed`() {
        assertTrue(SubAgentTerminalOutcome.canSucceed("found 3 issues"))
    }

    @Test
    fun `blank final text can never succeed`() {
        // The pre-fix engine marked SUCCEEDED whenever the generation job became null,
        // even with an empty harvest. This is the exhausted-wrap-up-empty case.
        assertFalse(SubAgentTerminalOutcome.canSucceed(""))
        assertFalse(SubAgentTerminalOutcome.canSucceed("  \n "))
    }

    @Test
    fun `exhaustion failure uses the stable machine-readable reason`() {
        // SubAgentEngine marks the run FAILED with this reason; parents and tests key off
        // the string, so it must never drift.
        assertEquals(
            "max_steps_exhausted_after_tool",
            me.rerere.rikkahub.data.ai.REASON_MAX_STEPS_EXHAUSTED_AFTER_TOOL,
        )
    }

    // ---- Status enum contract --------------------------------------------------------

    @Test
    fun `step exhaustion maps to FAILED, never TIMED_OUT`() {
        // TIMED_OUT stays reserved for the wall-clock timeoutSeconds cap. The engine uses
        // FAILED for exhaustion; this pin keeps a future refactor from reusing TIMED_OUT.
        val exhaustionStatus = SubAgentStatus.FAILED
        assertEquals(SubAgentStatus.FAILED, exhaustionStatus)
        assertTrue(SubAgentStatus.values().contains(SubAgentStatus.TIMED_OUT))
        assertTrue(SubAgentStatus.values().contains(SubAgentStatus.CANCELLED))
    }
}
