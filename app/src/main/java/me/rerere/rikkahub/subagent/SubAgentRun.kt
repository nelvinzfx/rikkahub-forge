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
    // Phase E tuning: 12 trips was too tight for multi-step research. Each web search
    // + result read = 2 trips, so 12 = only 6 search cycles. 20 allows 10 cycles.
    const val DEFAULT_MAX_TRIPS = 20
    const val MAX_MAX_TRIPS = 30
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
    // If non-null, the worker sends its first message into this existing conversation
    // instead of creating a new one. Used by subagent_dispatch_continue to pick up
    // where a previous worker left off. HeadlessConversations.mark is NOT set on a
    // continue target — the call site (SubAgentEngine.executeContinueRun) handles
    // lifecycle and registration separately.
    val workerConversationId: String? = null,
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
