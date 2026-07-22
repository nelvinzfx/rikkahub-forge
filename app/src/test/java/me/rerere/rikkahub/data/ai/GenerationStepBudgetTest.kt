package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the generation-loop step-budget fix (Kimi K3 silent stop).
 *
 * Root cause: `generateText` ran `for (stepIndex in 0 until maxSteps)` with NO explicit
 * exhaustion branch. When provider turn #32 requested a tool, the tool executed, its
 * result was emitted, the loop fell off the end, and the turn returned normally — the
 * model never saw its final tool result and never wrote a final answer, yet every layer
 * above read the turn as successful.
 *
 * The fix classifies every loop exit ([GenerationLoopExit]) and grants ONE reserved
 * no-tools wrap-up turn ([FinalWrapUpPolicy]) only when the budget was consumed
 * immediately after a tool execution. These tests pin that contract. The full loop needs
 * an Android Context, so — same pattern as [InvalidToolArgsEnvelopeTest] and the sub-agent
 * completion-wait test — the decision points are extracted as pure internal objects and
 * tested directly against the real production code.
 */
class GenerationStepBudgetTest {

    // ---- Case 2: exhaustion after a tool call -------------------------------------

    @Test
    fun `exhaustion after tool execution triggers the reserved wrap-up when enabled`() {
        assertTrue(
            FinalWrapUpPolicy.shouldRun(
                GenerationLoopExit.STEP_BUDGET_EXHAUSTED_AFTER_TOOL,
                reserveFinalWrapUp = true,
            )
        )
    }

    @Test
    fun `exhaustion after tool execution does nothing without the reserve flag`() {
        // Ordinary-chat default path: reserveFinalWrapUp stays false.
        assertFalse(
            FinalWrapUpPolicy.shouldRun(
                GenerationLoopExit.STEP_BUDGET_EXHAUSTED_AFTER_TOOL,
                reserveFinalWrapUp = false,
            )
        )
    }

    // ---- Case 1 & 4: normal completion (before or exactly at the limit) -----------

    @Test
    fun `normal completion never triggers the wrap-up`() {
        assertFalse(
            FinalWrapUpPolicy.shouldRun(
                GenerationLoopExit.COMPLETED_WITHOUT_TOOLS,
                reserveFinalWrapUp = true,
            )
        )
    }

    // ---- Case 5: approval pause on the final normal step ---------------------------

    @Test
    fun `waiting for approval never triggers the wrap-up`() {
        assertFalse(
            FinalWrapUpPolicy.shouldRun(
                GenerationLoopExit.WAITING_FOR_APPROVAL,
                reserveFinalWrapUp = true,
            )
        )
    }

    // ---- Case 6: cancellation / provider exception ---------------------------------

    @Test
    fun `no classified exit (cancellation or error path) never triggers the wrap-up`() {
        // Cancellation/provider exceptions propagate out of the flow before the post-loop
        // check runs, so loopExit is never set. The policy must refuse a null exit.
        assertFalse(FinalWrapUpPolicy.shouldRun(null, reserveFinalWrapUp = true))
    }

    // ---- Case 7: loop-guard exhaustion ----------------------------------------------

    @Test
    fun `loop-guard force-end never triggers the wrap-up`() {
        assertFalse(
            FinalWrapUpPolicy.shouldRun(
                GenerationLoopExit.LOOP_GUARD_EXHAUSTED,
                reserveFinalWrapUp = true,
            )
        )
    }

    @Test
    fun `empty tool pass never triggers the wrap-up`() {
        assertFalse(
            FinalWrapUpPolicy.shouldRun(
                GenerationLoopExit.NO_EXECUTED_TOOL_RESULTS,
                reserveFinalWrapUp = true,
            )
        )
    }

    // ---- Case 2/3: wrap-up outcome ---------------------------------------------------

    @Test
    fun `wrap-up with non-blank text succeeds`() {
        assertEquals(
            WrapUpOutcome.SUCCESS_WITH_TEXT,
            FinalWrapUpPolicy.outcomeFor("summary of findings"),
        )
    }

    @Test
    fun `wrap-up with blank text is explicit exhaustion`() {
        assertEquals(WrapUpOutcome.EXHAUSTED_NO_TEXT, FinalWrapUpPolicy.outcomeFor(""))
        assertEquals(WrapUpOutcome.EXHAUSTED_NO_TEXT, FinalWrapUpPolicy.outcomeFor("   \n  "))
    }

    @Test
    fun `exhaustion exception carries the stable machine-readable reason`() {
        val e = GenerationStepExhaustedException()
        assertEquals("max_steps_exhausted_after_tool", e.reason)
        assertTrue(e.message.orEmpty().contains(REASON_MAX_STEPS_EXHAUSTED_AFTER_TOOL))
        assertEquals("max_steps_exhausted_after_tool", REASON_MAX_STEPS_EXHAUSTED_AFTER_TOOL)
    }

    // ---- Wrap-up turn contract --------------------------------------------------------

    @Test
    fun `wrap-up addendum forbids tools and demands a final answer`() {
        val addendum = RESERVED_WRAP_UP_ADDENDUM
        assertTrue(addendum.contains("TOOL BUDGET EXHAUSTED"))
        assertTrue(addendum.contains("No tools are available"))
        assertTrue(addendum.contains("final answer"))
    }

    @Test
    fun `ordinary chat default budget stays 32`() {
        // Backward compatibility: the fix must not silently change the normal-chat budget.
        assertEquals(32, DEFAULT_MAX_GENERATION_STEPS)
    }

    // ---- Turn-count invariant ----------------------------------------------------------
    //
    // A run with maxTrips = N executes at most N normal tool-capable provider turns (the
    // for-loop bound) plus at most ONE no-tools wrap-up turn (gated by shouldRun, which
    // can fire only once per generation call — it runs after the loop, not inside it).
    // This simulation pins the arithmetic of that contract against the real policy.

    @Test
    fun `N normal turns plus at most one wrap-up - simulated two-step exhaustion`() {
        val normalMaxSteps = 2
        var providerTurns = 0
        var wrapUpTurns = 0

        // Both normal turns request a tool, so the loop runs to exhaustion.
        var loopExit: GenerationLoopExit? = null
        for (stepIndex in 0 until normalMaxSteps) {
            providerTurns++
            // (tool executes here in production)
            if (stepIndex == normalMaxSteps - 1) {
                loopExit = GenerationLoopExit.STEP_BUDGET_EXHAUSTED_AFTER_TOOL
            }
        }
        if (FinalWrapUpPolicy.shouldRun(loopExit, reserveFinalWrapUp = true)) {
            wrapUpTurns++
            providerTurns++
        }

        assertEquals(2, providerTurns - wrapUpTurns) // exactly N normal turns
        assertEquals(1, wrapUpTurns)                 // exactly one reserved turn
        assertEquals(3, providerTurns)               // N + 1 total, never more
    }

    @Test
    fun `completion on the last normal turn needs no wrap-up - simulated`() {
        val normalMaxSteps = 2
        var providerTurns = 0
        var loopExit: GenerationLoopExit? = null
        for (stepIndex in 0 until normalMaxSteps) {
            providerTurns++
            // Turn 1: tool requested + executed. Turn 2: final text, no tools.
            loopExit = if (stepIndex == 0) {
                null // loop continues; no exit classified mid-loop
            } else {
                GenerationLoopExit.COMPLETED_WITHOUT_TOOLS
            }
            if (loopExit == GenerationLoopExit.COMPLETED_WITHOUT_TOOLS) break
        }
        assertFalse(FinalWrapUpPolicy.shouldRun(loopExit, reserveFinalWrapUp = true))
        assertEquals(2, providerTurns)
    }
}
