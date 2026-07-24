package me.rerere.rikkahub.subagent

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal fun SubAgentStatus.isTerminal(): Boolean =
    this != SubAgentStatus.PENDING && this != SubAgentStatus.RUNNING

/** The generation and its supervising coroutine are one cancellable run-owned unit. */
class SubAgentExecutionHandle {
    private val stopRequested = AtomicBoolean(false)
    private val supervisor = AtomicReference<Job?>(null)
    private val generation = AtomicReference<Job?>(null)

    fun attachSupervisor(job: Job) {
        supervisor.set(job)
        if (stopRequested.get()) job.cancel()
    }

    fun attachGeneration(job: Job) {
        generation.set(job)
        if (stopRequested.get()) job.cancel()
    }

    fun isStopRequested(): Boolean = stopRequested.get()

    fun requestGenerationStop(): Boolean {
        if (!stopRequested.compareAndSet(false, true)) return false
        generation.get()?.cancel()
        return true
    }

    fun requestStop(): Boolean {
        if (!stopRequested.compareAndSet(false, true)) return false
        generation.get()?.cancel()
        supervisor.get()?.cancel()
        return true
    }

    fun clearGeneration(job: Job) {
        generation.compareAndSet(job, null)
    }
}

data class SubAgentAccessScope(
    val assistantId: String?,
    val conversationId: String?,
    val workerRunId: String?,
)

/**
 * Process-local sub-agent run store. Run status and execution ownership are deliberately
 * separate: serialisable records feed UI/tools while [SubAgentExecutionHandle] owns both
 * the supervising coroutine and the actual ChatService generation.
 */
class SubAgentRegistry {
    private val lock = Any()
    private val _runs = MutableStateFlow<Map<String, SubAgentRun>>(emptyMap())
    val runs: StateFlow<Map<String, SubAgentRun>> = _runs
    private val activeExecutions = ConcurrentHashMap<String, SubAgentExecutionHandle>()
    private val dispatchTimestamps = ConcurrentHashMap<String, MutableList<Long>>()

    fun addPending(run: SubAgentRun, handle: SubAgentExecutionHandle? = null): Boolean = synchronized(lock) {
        if (run.conversationId != null && _runs.value.values.any {
                it.conversationId == run.conversationId && !it.status.isTerminal()
            }) return@synchronized false
        _runs.value = pruneIfNeeded(_runs.value) + (run.id to run)
        if (handle != null) activeExecutions[run.id] = handle
        true
    }

    /** Compatibility overload for existing tests/internal callers. */
    fun addPending(run: SubAgentRun, job: Job?) {
        val handle = job?.let { SubAgentExecutionHandle().apply { attachSupervisor(it) } }
        addPending(run, handle)
    }

    fun update(id: String, transform: (SubAgentRun) -> SubAgentRun) = synchronized(lock) {
        val existing = _runs.value[id] ?: return@synchronized
        val updated = transform(existing)
        // Terminal outcome is immutable. Same-status enrichment (for subtree warning flags)
        // remains allowed, while a racing late success cannot replace cancellation/failure.
        val safe = if (existing.status.isTerminal() && updated.status != existing.status) {
            existing
        } else updated
        _runs.value = _runs.value + (id to safe)
    }

    fun transitionTerminal(
        id: String,
        status: SubAgentStatus,
        transform: (SubAgentRun) -> SubAgentRun,
    ): Boolean = synchronized(lock) {
        require(status.isTerminal())
        val existing = _runs.value[id] ?: return@synchronized false
        if (existing.status.isTerminal()) return@synchronized false
        if (status == SubAgentStatus.SUCCEEDED &&
            activeExecutions[id]?.isStopRequested() == true
        ) return@synchronized false
        _runs.value = _runs.value + (id to transform(existing).copy(status = status))
        activeExecutions.remove(id)
        true
    }

    fun reportProgress(id: String, note: String) {
        update(id) { run ->
            if (!run.status.isTerminal()) run.copy(progressNote = note.take(500)) else run
        }
    }

    fun get(id: String): SubAgentRun? = _runs.value[id]

    fun list(activeOnly: Boolean): List<SubAgentRun> = _runs.value.values
        .filter { !activeOnly || !it.status.isTerminal() }

    fun scopeFor(assistantId: String?, conversationId: String?): SubAgentAccessScope =
        SubAgentAccessScope(
            assistantId = assistantId,
            conversationId = conversationId,
            workerRunId = conversationId?.let(SubAgentConversationTracker::lookup)?.runId,
        )

    fun canAccess(scope: SubAgentAccessScope, run: SubAgentRun): Boolean {
        if (scope.assistantId == null || scope.conversationId == null) return false
        if (run.parentAssistantId != scope.assistantId) return false
        val anchorRunId = scope.workerRunId
        if (anchorRunId != null) return run.id == anchorRunId || isDescendantOf(run, anchorRunId)
        return run.ownerChatId == scope.conversationId
    }

    fun getScoped(id: String, scope: SubAgentAccessScope): SubAgentRun? =
        get(id)?.takeIf { canAccess(scope, it) }

    fun listScoped(activeOnly: Boolean, scope: SubAgentAccessScope): List<SubAgentRun> =
        list(activeOnly).filter { canAccess(scope, it) }

    private fun isDescendantOf(candidate: SubAgentRun, ancestorId: String): Boolean {
        var parentId = candidate.parentRunId
        val seen = mutableSetOf<String>()
        while (parentId != null && seen.add(parentId)) {
            if (parentId == ancestorId) return true
            parentId = _runs.value[parentId]?.parentRunId
        }
        return false
    }

    fun activeCountForAssistant(parentAssistantId: String): Int =
        _runs.value.values.count { it.parentAssistantId == parentAssistantId && !it.status.isTerminal() }

    fun globalActiveCount(): Int = _runs.value.values.count { !it.status.isTerminal() }

    fun hasActiveConversation(conversationId: String): Boolean =
        _runs.value.values.any { it.conversationId == conversationId && !it.status.isTerminal() }

    fun requestCancel(id: String): Boolean = synchronized(lock) {
        val run = _runs.value[id] ?: return@synchronized false
        if (run.status.isTerminal()) return@synchronized false
        // Serialize stop intent with transitionTerminal so a racing success cannot publish
        // between the authorization/status check and the handle's stop flag.
        activeExecutions[id]?.requestStop() ?: false
    }

    fun requestCancelScoped(id: String, scope: SubAgentAccessScope): Boolean =
        getScoped(id, scope)?.let { requestCancel(it.id) } ?: false

    fun cancelAllForParent(parentChatId: String): Int {
        val directRoots = _runs.value.values.filter { it.parentChatId == parentChatId }
        val ids = directRoots.flatMap { getSubtree(it.id) }
            .filter { !it.status.isTerminal() }
            .map { it.id }
            .distinct()
        return ids.count(::requestCancel)
    }

    fun clearExecution(id: String) {
        activeExecutions.remove(id)
    }

    fun cancelSubtree(rootRunId: String): Int {
        val ids = getSubtree(rootRunId).filter { !it.status.isTerminal() }.map { it.id }
        return ids.count(::requestCancel)
    }

    fun cancelSubtreeScoped(rootRunId: String, scope: SubAgentAccessScope): Int? {
        val root = getScoped(rootRunId, scope) ?: return null
        return getSubtree(root.id)
            .filter { canAccess(scope, it) && !it.status.isTerminal() }
            .count { requestCancel(it.id) }
    }

    fun getSubtree(rootRunId: String): List<SubAgentRun> =
        _runs.value.values.filter {
            it.id == rootRunId || it.orchestratorRunId == rootRunId || isDescendantOf(it, rootRunId)
        }

    fun getSubtreeScoped(rootRunId: String, scope: SubAgentAccessScope): List<SubAgentRun>? {
        val root = getScoped(rootRunId, scope) ?: return null
        return getSubtree(root.id).filter { canAccess(scope, it) }
    }

    fun subtreeTokenSum(rootRunId: String): Pair<Long, Long> {
        val subtree = getSubtree(rootRunId)
        return subtree.sumOf { it.tokensIn } to subtree.sumOf { it.tokensOut }
    }

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
        val id = current.values.filter { it.status.isTerminal() }
            .minByOrNull { it.finishedAtMs ?: it.startedAtMs }?.id
        return if (id == null) current else current - id
    }

    companion object {
        @Volatile private var globalInstance: SubAgentRegistry? = null
        internal fun onInstanceCreated(registry: SubAgentRegistry) { globalInstance = registry }
        fun cancelViaGlobalInstance(runId: String): Boolean =
            globalInstance?.requestCancel(runId) ?: false
    }

    init { onInstanceCreated(this) }
}
