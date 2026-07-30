package me.rerere.ai.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolInterruptedAttemptTest {
    private fun tool(
        state: ToolApprovalState,
        started: Boolean,
        hasOutput: Boolean,
    ) = UIMessagePart.Tool(
        toolCallId = "call-1",
        toolName = "demo",
        input = "{}",
        output = if (hasOutput) listOf(UIMessagePart.Text("ok")) else emptyList(),
        approvalState = state,
        executionStartedAt = if (started) 1_000L else null,
    )

    @Test
    fun `approved started with empty output is an interrupted attempt`() {
        assertTrue(tool(ToolApprovalState.Approved, started = true, hasOutput = false).isInterruptedAttempt)
    }

    @Test
    fun `auto started with empty output is an interrupted attempt`() {
        // Auto-approved tools (YOLO / headless / sub-agent) keep the Auto state through
        // execution. A process kill mid-execute must tombstone them on replay instead of
        // silently re-running the side effect.
        assertTrue(tool(ToolApprovalState.Auto, started = true, hasOutput = false).isInterruptedAttempt)
    }

    @Test
    fun `started with output is not interrupted`() {
        assertFalse(tool(ToolApprovalState.Approved, started = true, hasOutput = true).isInterruptedAttempt)
        assertFalse(tool(ToolApprovalState.Auto, started = true, hasOutput = true).isInterruptedAttempt)
    }

    @Test
    fun `never started is not interrupted`() {
        assertFalse(tool(ToolApprovalState.Approved, started = false, hasOutput = false).isInterruptedAttempt)
        assertFalse(tool(ToolApprovalState.Auto, started = false, hasOutput = false).isInterruptedAttempt)
    }

    @Test
    fun `pending and denied are never interrupted attempts`() {
        assertFalse(tool(ToolApprovalState.Pending, started = true, hasOutput = false).isInterruptedAttempt)
        assertFalse(tool(ToolApprovalState.Denied("no"), started = true, hasOutput = false).isInterruptedAttempt)
    }
}
