package me.rerere.rikkahub.data.ai.limits

import me.rerere.rikkahub.data.preferences.TermuxDefaults

/**
 * App-wide runtime limits for tool execution across every tool family, including MCP.
 * The setting lives on the Termux page for historical discoverability, but its scope is
 * deliberately global.
 */
object ToolRuntimeLimits {
    /** Full timeout granted independently to every tool invocation. */
    @Volatile var toolCallTimeoutMs: Long = TermuxDefaults.DEFAULT_TOOL_CALL_TIMEOUT_MS
}

/**
 * Supplies a fresh timeout for each tool invocation. This intentionally has no elapsed-time
 * state: model inference, approval delays, deep sleep, and earlier calls must never reduce the
 * budget of a later call before it starts.
 */
internal class ToolCallTimeoutBudget(
    private val timeoutProvider: () -> Long,
) {
    fun nextTimeoutMs(): Long = timeoutProvider().coerceAtLeast(1L)
}
