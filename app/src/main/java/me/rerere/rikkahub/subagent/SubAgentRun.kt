package me.rerere.rikkahub.subagent

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Phase 11 — sub-agent run record. Lives in [SubAgentRegistry]'s in-memory map for the
 * lifetime of the app process. Persistence intentionally out of scope for v1: spec says
 * "Background sub-agents survive only as long as the parent process is alive" and
 * documents that user-visibly. WorkManager-backed persistence is a v2 concern.
 *
 * The run is FROZEN once it reaches a terminal status. Mutations are done by replacing
 * the entry in the registry's StateFlow rather than mutating in place.
 */
@Serializable
data class SubAgentRun(
    val id: String,
    val parentChatId: String?,         // the parent assistant chat that dispatched this — used for /stop cascade
    val parentAssistantId: String,
    // Immutable root owner chat. Descendants keep this even when direct parent runs are
    // evicted from the process-local registry, so authorization never depends on LRU history.
    val ownerChatId: String? = parentChatId,
    // Hidden worker conversation backing this run. Populated as soon as executeRun creates
    // the conversation so the parent status pill can navigate to it directly.
    val conversationId: String? = null,
    val label: String,
    val task: String,
    val modelId: String?,              // null = inherited from parent
    val tools: List<String>?,          // null = inherited from parent
    val runInBackground: Boolean,
    val notifyParent: Boolean = false,
    val reasoningLevel: String? = null,   // null = inherited from assistant
    val timeoutSeconds: Int,
    val maxTrips: Int,
    val status: SubAgentStatus,
    val result: String? = null,
    val error: String? = null,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val tripCount: Int = 0,
    // Phase 30 (Orchestrator Mode Phase A) - records when the explicitly-requested
    // model could not be used at run time (e.g. provider flipped to disabled between
    // dispatch and generation) and the engine fell back to the assistant/global default.
    // Surfaced in the run record so the user (and Phase D cost logic) can see it was not
    // a silent substitution. modelId above still holds the originally-requested id.
    val fallbackModelUsed: Boolean = false,
    val fallbackReason: String? = null,
    // Phase 30 (Orchestrator Mode Phase C) — subtree identity + depth.
    val depth: Int = 0,
    val orchestratorRunId: String? = null, // null = top-level worker; non-null = descendant
    // Direct orchestration parent. Unlike orchestratorRunId (subtree root), this preserves
    // the full ancestry needed for worker-scoped authorization and continuation lineage.
    val parentRunId: String? = null,
    // Set only for continuation attempts; points at the terminal run whose conversation
    // this attempt safely reuses.
    val sourceRunId: String? = null,
    // Phase D — subtree cost-guard signals.
    val subtreeTokenWarning: Boolean = false, // set when subtree hits 80% of cap
    val subtreeTokenCancelled: Boolean = false, // set when subtree hits 100% of cap
    // Intermediate progress note published by the worker via subagent_report_progress.
    // Updated live while the worker is RUNNING so the parent can monitor without polling.
    val progressNote: String? = null,
)

@Serializable
enum class SubAgentStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
}

object SubAgentDefaults {
    // Phase E tuning: 300s was too short for research workers that do multiple web
    // searches (each ~30-60s with API latency). 600s (10 min) gives enough headroom
    // for 4-6 searches + synthesis without premature timeout.
    const val DEFAULT_TIMEOUT_SECONDS = 600
    const val MAX_TIMEOUT_SECONDS = 1800
    // Complex research and coding workers legitimately need more than the old 20/30
    // provider-turn limits. Keep a useful default while allowing callers to opt into the
    // same hard ceiling as ordinary chat; wall-clock, subtree-token, repeated-call, and
    // per-tool timeout guards remain independent safety boundaries.
    const val DEFAULT_MAX_TRIPS = 32
    const val MAX_MAX_TRIPS = 128
    const val MAX_LABEL_LENGTH = 60
    const val GLOBAL_CONCURRENCY_CAP = 16
    const val MIN_PER_ASSISTANT_CAP = 1
    const val MAX_PER_ASSISTANT_CAP = 10
    const val REGISTRY_LRU_CAP = 50

    /** Default system prompt used when the assistant's per-sub-agent prompt is empty. */
    val DEFAULT_SYSTEM_PROMPT = """
        You are a focused sub-agent dispatched by a parent assistant to complete a single
        task and return a concise summary.

        Rules:
        - Stay tightly scoped to the task you were given. Do not expand scope.
        - Use tools to gather facts before answering when accuracy matters.
        - Return a clear, structured final summary as your last message — that summary is
          what the parent will see. Aim for 100-500 words unless the task asks otherwise.
        - If the task is impossible, return a single short paragraph explaining why.
        - Do not ask the parent for clarification — make the best judgment call you can
          and proceed.
    """.trimIndent()
}

/**
 * Terminal-outcome gate for a finished sub-agent generation. Extracted as a pure function
 * so the "never SUCCEEDED with an empty result" contract is unit-testable without the
 * Android-laden engine. SUCCEEDED requires harvestable final text; a blank harvest means
 * the turn died without a deliverable (typically max_steps_exhausted_after_tool after the
 * reserved wrap-up also produced nothing) and must go FAILED instead.
 */
internal object SubAgentTerminalOutcome {
    fun canSucceed(finalText: String): Boolean = finalText.isNotBlank()
}

@Serializable
data class SubAgentRequest(
    val task: String,
    val modelId: String? = null,
    val systemPrompt: String? = null,
    val tools: List<String>? = null,
    val runInBackground: Boolean = false,
    // Internal continuation target. Public callers identify the source run; the engine
    // resolves this id only after ownership, terminal-state, assistant, and exclusivity checks.
    val workerConversationId: String? = null,
    val sourceRunId: String? = null,
    val timeoutSeconds: Int = SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
    val maxTrips: Int = SubAgentDefaults.DEFAULT_MAX_TRIPS,
    val label: String? = null,
    // Phase 30 (Orchestrator Mode Phase B) — per-dispatch inclusion overrides. Null = use
    // When true, the engine posts a synthetic user message into the parent conversation
    // when the worker finishes, waking the parent agent. Default false — the parent is
    // expected to collect results via subagent_wait_all or subagent_get instead.
    val notifyParent: Boolean = false,
    // the parent assistant's default (subAgentDefaultInclude*). Boolean = override.
    val includeMemory: Boolean? = null,
    val includeSoul: Boolean? = null,
    val includeRecentChats: Boolean? = null,
    // Reasoning level override for this worker. Null = resolve from assistant's
    // subAgentReasoningLevel, then fall back to ReasoningLevel.AUTO.
    val reasoningLevel: me.rerere.ai.core.ReasoningLevel? = null,
)

internal object SubAgentTerminalCleanup {
    suspend fun stopThenPublish(
        stop: suspend () -> Unit,
        publish: suspend () -> Unit,
    ) {
        try {
            stop()
        } finally {
            publish()
        }
    }
}

internal fun parseSubAgentReasoningLevel(value: String?): me.rerere.ai.core.ReasoningLevel? {
    if (value == null) return null
    return me.rerere.ai.core.ReasoningLevel.entries.firstOrNull {
        it.name.equals(value.trim(), ignoreCase = true)
    } ?: throw IllegalArgumentException(
        "reasoning_level must be one of ${me.rerere.ai.core.ReasoningLevel.entries.joinToString { it.name.lowercase() }}"
    )
}

internal object SubAgentAttemptBoundary {
    fun <T> after(items: List<T>, boundary: Int): List<T> = items.drop(boundary.coerceAtLeast(0))

    fun <T> usageAfter(
        items: List<T>,
        boundary: Int,
        usageOf: (T) -> Pair<Long, Long>?,
        isAssistant: (T) -> Boolean,
    ): Triple<Long, Long, Int> {
        var tokensIn = 0L
        var tokensOut = 0L
        var trips = 0
        for (item in after(items, boundary)) {
            usageOf(item)?.let { (input, output) ->
                tokensIn += input
                tokensOut += output
            }
            if (isAssistant(item)) trips++
        }
        return Triple(tokensIn, tokensOut, trips)
    }
}

internal object SubAgentDepthPolicy {
    /** maxDepth=0 disables workers; maxDepth=1 allows only depth-0 workers. */
    fun canDispatch(childDepth: Int, maxDepth: Int): Boolean =
        maxDepth > 0 && childDepth >= 0 && childDepth < maxDepth

    /** Whether a worker already running at [workerDepth] may expose child-dispatch tools. */
    fun canSpawnChild(workerDepth: Int, maxDepth: Int): Boolean =
        canDispatch(workerDepth + 1, maxDepth)
}

internal enum class ContinuationPolicyResult {
    OK,
    UNKNOWN_OR_UNAUTHORIZED,
    SOURCE_NOT_TERMINAL,
    CONVERSATION_ACTIVE,
}

internal object SubAgentContinuationPolicy {
    fun validate(
        source: SubAgentRun?,
        authorized: Boolean,
        conversationActive: Boolean,
    ): ContinuationPolicyResult = when {
        source == null || !authorized -> ContinuationPolicyResult.UNKNOWN_OR_UNAUTHORIZED
        !source.status.isTerminal() -> ContinuationPolicyResult.SOURCE_NOT_TERMINAL
        conversationActive -> ContinuationPolicyResult.CONVERSATION_ACTIVE
        else -> ContinuationPolicyResult.OK
    }
}

internal sealed class ToolAllowlistResult {
    data class Ok(val names: Set<String>?) : ToolAllowlistResult()
    data class Reject(val unknown: Set<String>) : ToolAllowlistResult()
}

/** Pure exact-allowlist policy shared by dispatch preflight and JVM regressions. */
internal object SubAgentToolAllowlist {
    fun resolve(requested: List<String>?, eligible: Set<String>): ToolAllowlistResult {
        if (requested == null) return ToolAllowlistResult.Ok(null)
        val normalized = requested.map(String::trim).filter(String::isNotEmpty).toCollection(linkedSetOf())
        val unknown = normalized - eligible
        return if (unknown.isEmpty()) ToolAllowlistResult.Ok(normalized)
        else ToolAllowlistResult.Reject(unknown)
    }
}

object SubAgentRequestValidator {

    sealed class Result {
        data class Ok(val request: SubAgentRequest) : Result()
        data class Reject(val error: String, val detail: String) : Result()
    }

    fun validate(request: SubAgentRequest): Result {
        val task = request.task.trim()
        if (task.isEmpty()) {
            return Result.Reject("invalid_task", "task is required and may not be blank")
        }
        if (request.timeoutSeconds < 1) {
            return Result.Reject(
                "invalid_timeout",
                "timeout_seconds must be at least 1; got ${request.timeoutSeconds}"
            )
        }
        if (request.timeoutSeconds > SubAgentDefaults.MAX_TIMEOUT_SECONDS) {
            return Result.Reject(
                "invalid_timeout",
                "timeout_seconds exceeds max ${SubAgentDefaults.MAX_TIMEOUT_SECONDS}; got ${request.timeoutSeconds}"
            )
        }
        if (request.maxTrips < 1) {
            return Result.Reject(
                "invalid_max_trips",
                "max_trips must be at least 1; got ${request.maxTrips}"
            )
        }
        if (request.maxTrips > SubAgentDefaults.MAX_MAX_TRIPS) {
            return Result.Reject(
                "invalid_max_trips",
                "max_trips exceeds max ${SubAgentDefaults.MAX_MAX_TRIPS}; got ${request.maxTrips}"
            )
        }
        request.label?.let {
            if (it.length > SubAgentDefaults.MAX_LABEL_LENGTH) {
                return Result.Reject(
                    "invalid_label",
                    "label exceeds ${SubAgentDefaults.MAX_LABEL_LENGTH} chars; got ${it.length}"
                )
            }
        }
        return Result.Ok(request.copy(task = task))
    }
}
