package me.rerere.rikkahub.subagent

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 11 — in-memory store of sub-agent runs + their associated coroutine [Job]s.
 *
 * The map of runs is a [StateFlow] so the chat UI's chip row can collect it; the map of
 * jobs is private since callers shouldn't be cancelling Jobs through random handles.
 * Capped at [SubAgentDefaults.REGISTRY_LRU_CAP] entries — when the cap is reached, the
 * oldest TERMINAL run gets evicted (running runs are never evicted).
 *
 * The registry intentionally does NOT enforce concurrency caps on its own — that's the
 * engine's job, since the engine has access to per-assistant configuration. This object
 * is just a typed mutable map with cancel hooks.
 */
class SubAgentRegistry {

    private val _runs = MutableStateFlow<Map<String, SubAgentRun>>(emptyMap())
    val runs: StateFlow<Map<String, SubAgentRun>> = _runs

    /**
     * Side-table of cancellable Jobs for currently RUNNING runs. Removed once the run
     * reaches a terminal status. Kept separate from the StateFlow because [Job] is not
     * serialisable and we don't want UI consumers re-collecting on Job-pointer churn.
     */
    private val activeJobs: ConcurrentHashMap<String, Job> = ConcurrentHashMap()

    fun addPending(run: SubAgentRun, job: Job? = null) {
        _runs.update { current ->
            val pruned = pruneIfNeeded(current)
            pruned + (run.id to run)
        }
        if (job != null) activeJobs[run.id] = job
    }

    fun update(id: String, transform: (SubAgentRun) -> SubAgentRun) {
        _runs.update { current ->
            val existing = current[id] ?: return@update current
            current + (id to transform(existing))
        }
    }

    fun setJob(id: String, job: Job) {
        activeJobs[id] = job
    }

    /**
     * Update the progress note on a RUNNING run. No-op if the run is terminal or missing.
     * The note is surfaced via [SubAgentRun.progressNote] and visible to the parent
     * through subagent_list / subagent_get without polling the worker conversation.
     */
    fun reportProgress(id: String, note: String) {
        update(id) { run ->
            if (run.status == SubAgentStatus.RUNNING || run.status == SubAgentStatus.PENDING) {
                run.copy(progressNote = note.take(500))
            } else run
        }
    }

    fun get(id: String): SubAgentRun? = _runs.value[id]

    fun list(activeOnly: Boolean): List<SubAgentRun> {
        val all = _runs.value.values
        return if (activeOnly) all.filter { it.status == SubAgentStatus.RUNNING || it.status == SubAgentStatus.PENDING }
        else all.toList()
    }

    fun activeCountForAssistant(parentAssistantId: String): Int =
        _runs.value.values.count {
            it.parentAssistantId == parentAssistantId &&
                (it.status == SubAgentStatus.RUNNING || it.status == SubAgentStatus.PENDING)
        }

    fun globalActiveCount(): Int =
        _runs.value.values.count {
            it.status == SubAgentStatus.RUNNING || it.status == SubAgentStatus.PENDING
        }

    /**
     * Cancel a single run by id. Returns true if a cancellable job existed; false if the
     * run was already in a terminal state or if the id is unknown. Marking the status to
     * CANCELLED is the caller's job (typically the engine after the Job's onCompletion
     * fires) so we don't double-write.
     */
    fun requestCancel(id: String): Boolean {
        val job = activeJobs.remove(id) ?: return false
        job.cancel()
        return true
    }

    /**
     * Cancel every currently-active run dispatched from [parentChatId]. Hooked into the
     * Telegram /stop handler and the in-app stop button so a single tick takes down the
     * parent generation AND all of its sub-agents. Returns the count cancelled.
     */
    fun cancelAllForParent(parentChatId: String): Int {
        var count = 0
        val toCancel = _runs.value.values
            .filter { it.parentChatId == parentChatId && (it.status == SubAgentStatus.RUNNING || it.status == SubAgentStatus.PENDING) }
            .map { it.id }
        for (runId in toCancel) {
            if (requestCancel(runId)) count++
        }
        return count
    }

    fun clearJob(id: String) {
        activeJobs.remove(id)
    }

    /**
     * Phase C — cancel every active run in the subtree rooted at [rootRunId]. Walks all
     * runs where orchestratorRunId == rootRunId (direct children and propagated descendants).
     * Also cancels the root run itself if it is still active. Returns the count cancelled.
     */
    fun cancelSubtree(rootRunId: String): Int {
        var count = 0
        val toCancel = _runs.value.values
            .filter {
                (it.orchestratorRunId == rootRunId || it.id == rootRunId) &&
                    (it.status == SubAgentStatus.RUNNING || it.status == SubAgentStatus.PENDING)
            }
            .map { it.id }
        for (runId in toCancel) {
            if (requestCancel(runId)) {
                count++
            }
        }
        return count
    }

    /**
     * Phase D — return all runs in the subtree rooted at [rootRunId]: the root itself
     * plus every descendant (runs whose orchestratorRunId matches).
     */
    fun getSubtree(rootRunId: String): List<SubAgentRun> =
        _runs.value.values.filter { it.id == rootRunId || it.orchestratorRunId == rootRunId }

    /**
     * Phase D — sum tokens across an entire subtree (root + descendants).
     * Returns (totalIn, totalOut).
     */
    fun subtreeTokenSum(rootRunId: String): Pair<Long, Long> {
        val runs = getSubtree(rootRunId)
        val totalIn = runs.sumOf { it.tokensIn }
        val totalOut = runs.sumOf { it.tokensOut }
        return totalIn to totalOut
    }

    // --- Phase D: per-assistant rate limiting (sliding 60s window) ---
    private val dispatchTimestamps = ConcurrentHashMap<String, MutableList<Long>>()

    /**
     * Check + record a dispatch under the assistant's rate-limit window.
     * Returns true if the dispatch is allowed, false if the rate limit is exceeded.
     * [limit] = max dispatches per 60s. 0 = unlimited (always true).
     */
    fun checkAndRecordRateLimit(assistantId: String, limit: Int): Boolean {
        if (limit <= 0) return true
        val now = System.currentTimeMillis()
        val cutoff = now - 60_000L
        val list = dispatchTimestamps.computeIfAbsent(assistantId) { mutableListOf() }
        synchronized(list) {
            list.removeAll { it < cutoff }
            if (list.size >= limit) return false
            list.add(now)
            return true
        }
    }

    private fun pruneIfNeeded(current: Map<String, SubAgentRun>): Map<String, SubAgentRun> {
        if (current.size < SubAgentDefaults.REGISTRY_LRU_CAP) return current
        // Evict the oldest TERMINAL run; never evict a running one. If every run is
        // running, the cap would be exceeded — we accept this since it should be rare
        // (50 concurrent sub-agents would already have been blocked by the global cap of 16).
        val terminalSorted = current.values
            .filter { it.status != SubAgentStatus.RUNNING && it.status != SubAgentStatus.PENDING }
            .sortedBy { it.finishedAtMs ?: it.startedAtMs }
        val toEvictId = terminalSorted.firstOrNull()?.id
        return if (toEvictId != null) current - toEvictId else current
    }

    companion object {
        @Volatile
        private var globalInstance: SubAgentRegistry? = null

        internal fun onInstanceCreated(registry: SubAgentRegistry) {
            globalInstance = registry
        }

        /**
         * Cancel a sub-agent run via the global singleton instance. Used by the UI chip row
         * which doesn't have direct access to the registry through DI in the composable tree.
         */
        fun cancelViaGlobalInstance(runId: String): Boolean {
            return globalInstance?.requestCancel(runId) ?: false
        }
    }

    init {
        onInstanceCreated(this)
    }
}
