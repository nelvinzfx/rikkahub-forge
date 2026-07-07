package me.rerere.rikkahub.subagent

import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 30 (Orchestrator Mode Phase C) — maps a sub-agent worker conversation UUID
 * to metadata about the run executing inside it (run id, depth, subtree root).
 *
 * This is the "side-table" companion to [HeadlessConversations]: HeadlessConversations
 * marks a conversation as non-interactive (cron / sub-agent / workflow); this map adds
 * the sub-agent-specific depth and identity information that the depth-cap check and
 * subtree cancellation need.
 *
 * Lifecycle: [SubAgentEngine.executeRun] registers an entry when it creates the worker
 * conversation, and removes it in the `finally` block when the run terminates. A
 * conversation id absent from this map is simply not a sub-agent conversation (it's a
 * normal interactive conversation or a cron/workflow run).
 */
object SubAgentConversationTracker {

    data class Entry(
        val runId: String,
        val depth: Int,
        val orchestratorRunId: String?, // null = this IS the root; non-null = descendant
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun register(conversationId: String, runId: String, depth: Int, orchestratorRunId: String?) {
        entries[conversationId] = Entry(runId, depth, orchestratorRunId)
    }

    fun unregister(conversationId: String) {
        entries.remove(conversationId)
    }

    fun lookup(conversationId: String): Entry? = entries[conversationId]

    /**
     * Given a parent conversation id, determine the depth and orchestratorRunId for
     * a child worker being dispatched from it.
     *
     * - If the parent is NOT in the tracker (normal interactive conversation), the
     *   child is a top-level worker: depth=0, orchestratorRunId=null.
     * - If the parent IS in the tracker (already a sub-agent), the child is a
     *   descendant: depth=parent.depth+1, orchestratorRunId=parent's root (or parent's
     *   own run id if the parent is itself the root).
     */
    fun childDepth(parentConversationId: String?): Pair<Int, String?> {
        if (parentConversationId == null) return 0 to null
        val parent = entries[parentConversationId]
            ?: return 0 to null
        val childDepth = parent.depth + 1
        val childOrchestratorId = parent.orchestratorRunId ?: parent.runId
        return childDepth to childOrchestratorId
    }
}
