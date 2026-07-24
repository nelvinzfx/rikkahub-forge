package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for forced generation-boundary finalization.
 *
 * Root cause: `generateText` ran a bounded provider loop but ordinary chat did not opt into
 * the reserved no-tools wrap-up. If the final normal provider turn requested a tool, the tool
 * executed and the flow returned successfully before the model saw that result or wrote final
 * text. Repeated loop-guard exhaustion had the same silent-success shape.
 *
 * Finalization is now a GenerationHandler invariant: either forced boundary receives exactly
 * one no-tools wrap-up. Normal completion, approval pauses, empty tool passes, cancellation,
 * and provider errors do not. These pure policy tests pin that contract without Android.
 */
class GenerationStepBudgetTest {

    @Test
    fun `step budget exhaustion always triggers mandatory wrap-up`() {
        assertTrue(
            FinalWrapUpPolicy.shouldRun(
                GenerationLoopExit.STEP_BUDGET_EXHAUSTED_AFTER_TOOL,
            )
        )
        assertEquals(
            REASON_MAX_STEPS_EXHAUSTED_AFTER_TOOL,
            FinalWrapUpPolicy.reasonFor(GenerationLoopExit.STEP_BUDGET_EXHAUSTED_AFTER_TOOL),
        )
    }

    @Test
    fun `loop guard exhaustion always triggers mandatory wrap-up`() {
        assertTrue(FinalWrapUpPolicy.shouldRun(GenerationLoopExit.LOOP_GUARD_EXHAUSTED))
        assertEquals(
            REASON_LOOP_GUARD_EXHAUSTED,
            FinalWrapUpPolicy.reasonFor(GenerationLoopExit.LOOP_GUARD_EXHAUSTED),
        )
    }

    @Test
    fun `normal completion never triggers wrap-up`() {
        assertFalse(FinalWrapUpPolicy.shouldRun(GenerationLoopExit.COMPLETED_WITHOUT_TOOLS))
    }

    @Test
    fun `approval pause never triggers wrap-up`() {
        assertFalse(FinalWrapUpPolicy.shouldRun(GenerationLoopExit.WAITING_FOR_APPROVAL))
    }

    @Test
    fun `empty tool pass never triggers wrap-up`() {
        assertFalse(FinalWrapUpPolicy.shouldRun(GenerationLoopExit.NO_EXECUTED_TOOL_RESULTS))
    }

    @Test
    fun `unclassified cancellation or provider error never triggers wrap-up`() {
        assertFalse(FinalWrapUpPolicy.shouldRun(null))
    }

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
    fun `exhaustion exception preserves boundary-specific reason`() {
        val step = GenerationStepExhaustedException(REASON_MAX_STEPS_EXHAUSTED_AFTER_TOOL)
        assertEquals("max_steps_exhausted_after_tool", step.reason)
        assertTrue(step.message.orEmpty().contains(REASON_MAX_STEPS_EXHAUSTED_AFTER_TOOL))

        val loop = GenerationStepExhaustedException(REASON_LOOP_GUARD_EXHAUSTED)
        assertEquals("loop_guard_exhausted", loop.reason)
        assertTrue(loop.message.orEmpty().contains(REASON_LOOP_GUARD_EXHAUSTED))
    }

    @Test
    fun `step-budget wrap-up addendum forbids tools and demands final answer`() {
        val addendum = FinalWrapUpPolicy.addendumFor(
            GenerationLoopExit.STEP_BUDGET_EXHAUSTED_AFTER_TOOL,
        )
        assertTrue(addendum.contains(REASON_MAX_STEPS_EXHAUSTED_AFTER_TOOL))
        assertTrue(addendum.contains("No tools are available"))
        assertTrue(addendum.contains("final answer"))
    }

    @Test
    fun `loop-guard wrap-up addendum names repeated-loop boundary`() {
        val addendum = FinalWrapUpPolicy.addendumFor(GenerationLoopExit.LOOP_GUARD_EXHAUSTED)
        assertTrue(addendum.contains(REASON_LOOP_GUARD_EXHAUSTED))
        assertTrue(addendum.contains("repeated-tool loop guard"))
        assertTrue(addendum.contains("No tools are available"))
    }

    @Test
    fun `ordinary chat keeps a bounded but complex-task-capable budget`() {
        assertEquals(128, DEFAULT_MAX_GENERATION_STEPS)
    }

    // A run with maxSteps = N executes at most N normal tool-capable provider turns plus
    // at most ONE no-tools wrap-up. The wrap-up runs after the bounded loop and never
    // re-enters it, so mandatory finalization does not make tool execution unbounded.
    @Test
    fun `N normal turns plus exactly one wrap-up on forced exhaustion`() {
        val normalMaxSteps = 2
        var providerTurns = 0
        var wrapUpTurns = 0
        var loopExit: GenerationLoopExit? = null

        for (stepIndex in 0 until normalMaxSteps) {
            providerTurns++
            if (stepIndex == normalMaxSteps - 1) {
                loopExit = GenerationLoopExit.STEP_BUDGET_EXHAUSTED_AFTER_TOOL
            }
        }
        if (FinalWrapUpPolicy.shouldRun(loopExit)) {
            wrapUpTurns++
            providerTurns++
        }

        assertEquals(2, providerTurns - wrapUpTurns)
        assertEquals(1, wrapUpTurns)
        assertEquals(3, providerTurns)
    }

    @Test
    fun `completion on last normal turn needs no wrap-up`() {
        val normalMaxSteps = 2
        var providerTurns = 0
        var loopExit: GenerationLoopExit? = null
        for (stepIndex in 0 until normalMaxSteps) {
            providerTurns++
            loopExit = if (stepIndex == 0) {
                null
            } else {
                GenerationLoopExit.COMPLETED_WITHOUT_TOOLS
            }
            if (loopExit == GenerationLoopExit.COMPLETED_WITHOUT_TOOLS) break
        }
        assertFalse(FinalWrapUpPolicy.shouldRun(loopExit))
        assertEquals(2, providerTurns)
    }
}
