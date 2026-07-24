package me.rerere.rikkahub.data.ai

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.service.AgentOverlay
import me.rerere.rikkahub.service.RikkaAccessibilityService
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.merge
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.limits.ToolCallTimeoutBudget
import me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.OrchestratorMode
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

/**
 * Keys whose string values are sensitive enough that the raw value MUST NOT land in
 * logcat. Tool args land in `Log.i` for debugging; without redaction, `save_ssh_host`'s
 * `private_key` / `password` and `telegram_set_token`'s `token` would print verbatim
 * — readable on debug builds, by other apps holding READ_LOGS on OEM-bugged ROMs, and
 * by `bugreport`/`dumpsys`. The match is case-insensitive against the key name and
 * applies regardless of nesting depth.
 */
private val SECRET_KEY_PATTERN: Regex =
    Regex("(?:^|_)(password|passphrase|secret|token|apikey|api[_-]?key|privatekey|private[_-]?key|key)$",
        RegexOption.IGNORE_CASE)

/**
 * Walk [element] and replace any string primitive whose KEY matches [SECRET_KEY_PATTERN]
 * with the string `"***"`. Numbers, booleans, nulls, and non-secret strings pass through
 * unchanged. Used only for the per-step "executing tool with args" log line.
 */
private fun redactSecrets(element: JsonElement, key: String? = null): JsonElement {
    val isSecret = key != null && SECRET_KEY_PATTERN.containsMatchIn(key)
    return when (element) {
        is JsonPrimitive ->
            if (isSecret && element.isString) JsonPrimitive("***") else element
        is JsonObject -> JsonObject(element.mapValues { (k, v) -> redactSecrets(v, k) })
        is JsonArray -> buildJsonArray { element.forEach { add(redactSecrets(it, key)) } }
    }
}

/**
 * Replace older tool-result `Image` parts with a small text elision so the same JPEGs
 * aren't re-encoded into base64 on every subsequent step. We keep the
 * [IMAGE_KEEP_LAST_N_TOOL_RESULTS] most-recent tool-result-bearing assistant messages
 * verbatim and elide everything older. User uploads (`role=USER`) are NEVER elided —
 * those are real input the model needs to reason over. Assistant-generated images
 * (model image-gen output) are also kept verbatim as those are visible product, not
 * intermediate reasoning state.
 */
private fun List<UIMessage>.ageOldToolImages(): List<UIMessage> {
    var toolResultsWithImagesSeen = 0
    return this.asReversed().map { msg ->
        if (msg.role == MessageRole.USER) return@map msg
        val hasImageInTool = msg.parts.any { p ->
            p is UIMessagePart.Tool && p.output.any { it is UIMessagePart.Image }
        }
        if (!hasImageInTool) return@map msg
        toolResultsWithImagesSeen++
        if (toolResultsWithImagesSeen <= IMAGE_KEEP_LAST_N_TOOL_RESULTS) return@map msg
        val newParts = msg.parts.map { part ->
            if (part is UIMessagePart.Tool) {
                val newOutput = part.output.map { o ->
                    if (o is UIMessagePart.Image) {
                        UIMessagePart.Text(
                            "[image elided — original at ${o.url}; superseded by newer screenshots]"
                        )
                    } else o
                }
                part.copy(output = newOutput)
            } else part
        }
        msg.copy(parts = newParts)
    }.asReversed()
}

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

private const val TAG_GH_LOOP = "GenHandlerLoop"

/**
 * If the model calls the same tool with the same exact JSON args this many times within a
 * single user turn, we refuse the next execution and inject a "loop_detected" envelope. The
 * threshold is INCLUSIVE of the prior occurrences, so a value of 3 means: first call runs,
 * second call runs, third call runs — fourth identical call is blocked. Picked low enough
 * that runaway loops can't drain the user's API tokens but high enough to allow legitimate
 * retries (a notification key going stale between read and dismiss, etc.).
 */
private const val LOOP_GUARD_REPEAT_THRESHOLD = 3

// Tool execution is bounded per invocation by ToolRuntimeLimits.toolCallTimeoutMs. The
// generation loop itself is bounded by maxSteps and the loop guards below; model inference and
// earlier calls must not consume a later tool's timeout before that tool starts.

/**
 * Max number of times the loop guard can trip in a single turn before we stop the normal
 * tool-capable loop. A mandatory no-tools wrap-up still gives the model one chance to report
 * what happened. Prevents the "model keeps trying different tools, each gets loop-detected"
 * pattern that produced the 27-step / 141K-token disaster: one trip means the model is
 * confused; six trips means further tool-capable turns are no longer justified.
 */
private const val MAX_LOOP_GUARD_TRIPS_PER_TURN = 6

/**
 * Number of most-recent tool-result-bearing messages whose `Image` parts are kept
 * verbatim in the prompt. Older tool-result images are replaced with a small text
 * elision so the same JPEG isn't re-encoded into base64 on every step. Without this
 * a screen-automation turn that takes 5 screenshots makes the provider re-pay
 * ~1–2MB × 5 base64 encode + upload on every subsequent step.
 *
 * 2 is the smallest value that lets the model do "look at this screenshot, decide
 * action; take new screenshot, compare" — needs both the previous and the current
 * screenshot in context. Anything older has been superseded.
 */
private const val IMAGE_KEEP_LAST_N_TOOL_RESULTS = 2

/**
 * Some read-only tools measure a real-time signal where re-calling after a TTL is
 * legitimate (battery drains, screens change, sensors update). For these, the loop guard
 * lets identical calls through if the most recent identical call is older than the TTL.
 * Without this, asking the model "what's the battery now?" after a previous reading just
 * regurgitates the stale value and the user has no idea.
 *
 * Tools NOT in this map are treated as side-effecting / idempotent-input: re-calling with
 * identical args is a loop, not a refresh. Add new freshness-sensitive tools here.
 */
private val FRESHNESS_TTL_MS_BY_TOOL: Map<String, Long> = mapOf(
    "get_battery_status" to 30_000L,
    "get_audio_info" to 30_000L,
    "get_telephony_info" to 30_000L,
    "get_wifi_info" to 30_000L,
    "get_storage_info" to 60_000L,
    "get_brightness" to 10_000L,
    "get_volume" to 10_000L,
    "get_location" to 30_000L,
    "get_time_info" to 5_000L,
    "read_sensor" to 5_000L,
    "take_screenshot" to 5_000L,
    "read_window_tree" to 5_000L,
    "list_active_notifications" to 5_000L,
    "list_jobs" to 60_000L,
)

/**
 * UI-observation tools that read screen/device state without changing it. Used by the loop
 * guard's reset rule below: when the model drives a UI it runs an act-observe cycle and
 * naturally repeats the same observation call (read_window_tree / take_screenshot with
 * identical args) after every action. Those repeats are progress, NOT a loop, so an
 * intervening ACTION (any executed tool NOT in this set) resets the observation repeat count.
 * Tools that ARE in this set do not reset each other, so a model that merely alternates
 * observers on a frozen screen still trips the guard (the token-drain case we must catch).
 *
 * This is the freshness-sensitive realtime readers plus find_node (the other pure screen
 * reader). Keep it to genuine read-only observers: wrongly adding an ACTION tool here would
 * stop it from resetting the counter and reintroduce the false-positive loop_detected.
 */
private val READ_ONLY_OBSERVATION_TOOLS: Set<String> =
    FRESHNESS_TTL_MS_BY_TOOL.keys + "find_node"

/** One prior executed tool call in the current turn, in chronological order. */
internal data class PriorToolCall(
    val toolName: String,
    val signature: String,
    val epochMs: Long,
)

internal data class LoopGuardDecision(
    val block: Boolean,
    val priorOccurrences: Int,
)

/**
 * Pure, testable loop-detection decision, extracted from [GenerationHandler.generateText] so
 * the act-observe reset and freshness-TTL rules can be unit-tested without an Android Context.
 */
internal object LoopGuard {
    fun evaluate(
        priorCalls: List<PriorToolCall>,
        toolName: String,
        signature: String,
        nowMs: Long,
        threshold: Int = LOOP_GUARD_REPEAT_THRESHOLD,
        readOnlyTools: Set<String> = READ_ONLY_OBSERVATION_TOOLS,
        freshnessTtlMs: Map<String, Long> = FRESHNESS_TTL_MS_BY_TOOL,
    ): LoopGuardDecision {
        // For observation tools, only repeats since the most recent ACTION count: acting on
        // the world is progress, so identical observations taken before it are stale for
        // loop-detection purposes. Side-effecting tools count every identical call in the
        // turn (re-sending the same message 3x is a loop regardless of what ran between).
        val relevant = if (toolName in readOnlyTools) {
            val lastActionIdx = priorCalls.indexOfLast { it.toolName !in readOnlyTools }
            if (lastActionIdx >= 0) priorCalls.subList(lastActionIdx + 1, priorCalls.size)
            else priorCalls
        } else {
            priorCalls
        }
        val matching = relevant.filter { it.signature == signature }
        val priorOccurrences = matching.size
        if (priorOccurrences < threshold) return LoopGuardDecision(false, priorOccurrences)
        // Freshness-TTL bypass: a real-time reader re-called after its TTL is a refresh, not
        // a loop; let it through so the model gets a fresh reading instead of a stale one.
        val ttl = freshnessTtlMs[toolName]
        if (ttl != null && nowMs - matching.maxOf { it.epochMs } >= ttl) {
            return LoopGuardDecision(false, priorOccurrences)
        }
        return LoopGuardDecision(true, priorOccurrences)
    }
}

/**
 * Phase 30 (Orchestrator Mode Phase C) — preamble injected into the system prompt when
 * the assistant has orchestratorMode = true and the conversation is the orchestrator's
 * own (not a worker). Short, dense, prescriptive. See docs/orchestrator-mode.md §5.2.
 */
internal const val ORCHESTRATOR_PREAMBLE = """
You are in Orchestrator Mode. You can decompose tasks into parallel worker sub-agents, or do them yourself.

DECIDE FIRST: If a task is one lookup, one edit, or something you can answer in a few tool calls — do it inline. Workers cost tokens and coordination; don't spawn them for work you can finish faster alone. Split when there are genuinely independent threads (parallel research, multi-file changes, multi-target probing). Workers run in background by default — dispatch them, then continue working while they execute. Call subagent_wait_all to collect results when you need them.

WRITE GOOD WORKER TASKS: Workers don't see this conversation. Each task must be self-contained — include any context the worker needs to act without asking you back. One clear deliverable per worker. Bounded scope: "search X, return Y" not "explore the codebase".

RIGHT GRANULARITY: Too fine = overhead burns more tokens than it saves. Too coarse = no parallelism, might as well do it yourself. Aim for 2-6 workers, each doing a chunk that would take you 3-10 tool calls alone.

HANDLE FAILURE: If a worker times out or fails, use what you got. Retry only if the result is essential and the failure looks transient. Don't block the whole turn on one dead worker.

SYNTHESISE: Your final reply is not a paste of worker outputs. Read their summaries, extract what matters, write one coherent answer to the user. The user sees you, not your workers.
"""

internal const val FORCE_ORCHESTRATOR_ADDENDUM = """

FORCE THIS TURN: Delegate at least one meaningful, self-contained part of this request to a worker before producing the final answer. Do not satisfy this requirement with a trivial or ceremonial worker.
"""

internal fun buildOrchestratorPreamble(
    mode: OrchestratorMode,
    enforceSubAgentPromptRules: Boolean,
): String = when {
    enforceSubAgentPromptRules || mode == OrchestratorMode.OFF -> ""
    mode == OrchestratorMode.FORCE -> ORCHESTRATOR_PREAMBLE + FORCE_ORCHESTRATOR_ADDENDUM
    else -> ORCHESTRATOR_PREAMBLE
}

/**
 * Hard ceiling of NORMAL tool-capable provider turns for ordinary chat. This remains a
 * last-resort cost guard, but 32 was too small for legitimate repository and device work.
 * The repeated-call guard, per-tool timeout, context compaction, and the mandatory final
 * no-tools wrap-up still bound pathological turns.
 */
internal const val DEFAULT_MAX_GENERATION_STEPS = 128

/** Stable machine-readable reasons for a safety boundary that produced no final text. */
internal const val REASON_MAX_STEPS_EXHAUSTED_AFTER_TOOL = "max_steps_exhausted_after_tool"
internal const val REASON_LOOP_GUARD_EXHAUSTED = "loop_guard_exhausted"

/**
 * Thrown when a generation safety boundary was reached and the mandatory reserved
 * no-tools wrap-up turn still failed to produce text. The boundary-specific [reason]
 * lets UI/error surfaces distinguish cost-budget exhaustion from repeated-loop exhaustion.
 */
class GenerationStepExhaustedException(
    val reason: String = REASON_MAX_STEPS_EXHAUSTED_AFTER_TOOL,
) : Exception(
    "$reason: generation reached a safety boundary and the reserved final " +
        "wrap-up turn produced no text"
)

/**
 * Why the normal generation loop exited. The pre-fix code inferred "success" from simply
 * falling out of the for-loop, which made "budget consumed right after a tool execution"
 * indistinguishable from "model finished" — the Kimi K3 silent-stop defect. Every break
 * site now classifies itself explicitly.
 */
internal enum class GenerationLoopExit {
    /** Provider turn finished with final text and no unexecuted tools. */
    COMPLETED_WITHOUT_TOOLS,

    /** Last message has tools waiting on user approval (or still Pending). */
    WAITING_FOR_APPROVAL,

    /** A tool-processing pass produced zero results (all tools were pending). */
    NO_EXECUTED_TOOL_RESULTS,

    /** Loop guard tripped MAX_LOOP_GUARD_TRIPS_PER_TURN times; tool loop stopped. */
    LOOP_GUARD_EXHAUSTED,

    /** The for-loop's last allowed iteration ended right after executing tools — the
     * model never got the follow-up turn that would have written the final answer. */
    STEP_BUDGET_EXHAUSTED_AFTER_TOOL,
}

internal enum class WrapUpOutcome {
    SUCCESS_WITH_TEXT,
    EXHAUSTED_NO_TEXT,
}

/**
 * Pure decision points for the reserved final wrap-up turn, extracted so the exhaustion
 * contract is unit-testable without an Android Context.
 */
internal object FinalWrapUpPolicy {
    /**
     * Every forced generation boundary gets exactly one final provider turn with no tools.
     * This is an invariant rather than a caller opt-in: a new chat surface cannot accidentally
     * reintroduce the silent-stop bug by forgetting a flag. Approval pauses, empty tool passes,
     * normal completion, cancellation, and provider errors do not use this path.
     */
    fun shouldRun(exit: GenerationLoopExit?): Boolean = reasonFor(exit) != null

    fun reasonFor(exit: GenerationLoopExit?): String? = when (exit) {
        GenerationLoopExit.STEP_BUDGET_EXHAUSTED_AFTER_TOOL ->
            REASON_MAX_STEPS_EXHAUSTED_AFTER_TOOL
        GenerationLoopExit.LOOP_GUARD_EXHAUSTED ->
            REASON_LOOP_GUARD_EXHAUSTED
        else -> null
    }

    /**
     * Transient per-call system addendum for the reserved wrap-up turn. It is not persisted
     * into history and is not a fake user message; it rides the volatile system section for
     * exactly one provider request.
     */
    fun addendumFor(exit: GenerationLoopExit): String {
        val reason = requireNotNull(reasonFor(exit)) { "no wrap-up for $exit" }
        val detail = when (exit) {
            GenerationLoopExit.STEP_BUDGET_EXHAUSTED_AFTER_TOOL ->
                "The normal tool-capable provider-turn budget is exhausted."
            GenerationLoopExit.LOOP_GUARD_EXHAUSTED ->
                "The repeated-tool loop guard force-ended normal execution because blocked tool calls kept recurring."
            else -> error("no wrap-up for $exit")
        }
        return """
            RUNTIME NOTICE — GENERATION SAFETY BOUNDARY: $reason. $detail
            No tools are available for this final response, and any tool call you attempt will be rejected. Using only the evidence already gathered in this conversation, return the best final answer or a concise summary of what you did and found. Plain text only — this final message is the entire response the caller will see.
        """.trimIndent()
    }

    /** The wrap-up turn succeeds iff it produced non-blank text. */
    fun outcomeFor(finalText: String): WrapUpOutcome =
        if (finalText.isBlank()) WrapUpOutcome.EXHAUSTED_NO_TEXT else WrapUpOutcome.SUCCESS_WITH_TEXT
}

class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val aiLoggingManager: AILoggingManager,
    private val systemPromptBuilder: SystemPromptBuilder,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        // NORMAL tool-capable provider-turn budget. Sub-agent callers pass their
        // request's maxTrips here; ordinary chat keeps the hard default. Forced exhaustion
        // always receives one mandatory no-tools wrap-up after this normal budget.
        maxSteps: Int = DEFAULT_MAX_GENERATION_STEPS,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        // Returns true when the user has pre-approved [toolName] for this turn (e.g.
        // "Allow for this chat" or "Always Allow" granted earlier). When true, the loop
        // below skips the Pending flip and lets the tool execute. ChatService injects the
        // closure that reads ToolApprovalAllowList + ToolApprovalPreferences. Default
        // returns false so callers that don't care still get vanilla approval gating.
        isToolAutoApproved: suspend (toolName: String) -> Boolean = { false },
        // Called before EVERY provider request (including post-tool loop requests). It may
        // persist/compact the transcript and return a replacement effective context.
        onBeforeProviderRequest: suspend (List<UIMessage>) -> List<UIMessage> = { it },
        // Return a compacted replacement context to retry a provider overflow once, or null
        // when the throwable is unrelated / recovery is unavailable.
        recoverContextOverflow: suspend (Throwable, List<UIMessage>) -> List<UIMessage>? = { _, _ -> null },
        // Optional per-call addendum appended to the system prompt. Used by surfaces that
        // need the model to know runtime context (e.g. "you're talking via Telegram, the
        // chat_id is 12345") without polluting the user message body — without this the
        // preamble is replayed in user history every turn, burning ~80 tokens × N turns.
        systemAddendum: String? = null,
        conversationId: String? = null,
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        // Phase 30 (Orchestrator Mode Phase B) — sub-agent prompt/memory gating.
        suppressMemory: Boolean = false,
        suppressAssistantPrompt: Boolean = false,
        suppressRecentChats: Boolean = false,
        enforceSubAgentPromptRules: Boolean = false,
        orchestratorMode: OrchestratorMode = OrchestratorMode.OFF,
        reasoningLevelOverride: me.rerere.ai.core.ReasoningLevel? = null,
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        // Replay safety: scan the input messages for tools that were Approved + began
        // execution but never produced output (process killed mid-execute). Without this
        // pass, the loop below would treat them as "Approved, ready to run" and execute
        // them AGAIN on replay — could double-charge a remote, duplicate a message send,
        // re-overwrite a file. Flip them to Denied so the model sees a deterministic
        // envelope and decides whether to retry deliberately.
        var messages: List<UIMessage> = messages.map { msg ->
            val newParts = msg.parts.map { part ->
                if (part is UIMessagePart.Tool && part.isInterruptedAttempt) {
                    Log.w(TAG, "replay: ${part.toolName} (${part.toolCallId}) had executionStartedAt set with empty output → Denied(interrupted_unknown_outcome)")
                    part.copy(approvalState = ToolApprovalState.Denied(
                        "interrupted_unknown_outcome: a previous attempt to execute this tool started " +
                            "but did not complete (process killed mid-execute). The side effect MAY OR " +
                            "MAY NOT have happened. Verify the target state before retrying — do not " +
                            "blindly re-run the same call."
                    ))
                } else part
            }
            if (newParts == msg.parts) msg else msg.copy(parts = newParts)
        }

        val toolCallTimeoutBudget = ToolCallTimeoutBudget {
            ToolRuntimeLimits.toolCallTimeoutMs
        }
        var loopGuardTripCount = 0

        // Why the normal loop exited. Classified explicitly at every break site — the old
        // code inferred success from simply falling out of the for-loop, which made
        // "budget consumed right after a tool execution" indistinguishable from "model
        // finished" (the Kimi silent-stop defect). Cancellation and provider errors
        // propagate as exceptions and never set this.
        var loopExit: GenerationLoopExit? = null

        // One provider turn with a single context-overflow retry + the standard post-turn
        // finalisation (visual transforms, onGenerationFinish, finishedAt, emit). Shared
        // by the normal loop and the reserved no-tools wrap-up turn so the wrap-up goes
        // through the exact same compaction hook, transformers, and streaming updates
        // without re-entering the tool loop.
        suspend fun runProviderTurn(toolsForTurn: List<Tool>, addendum: String?) {
            var overflowRetried = false
            while (true) {
                messages = onBeforeProviderRequest(messages)
                try {
                    generateInternal(
                        assistant = assistant,
                        settings = settings,
                        systemAddendum = addendum,
                        messages = messages,
                        onUpdateMessages = {
                            messages = it.transforms(
                                transformers = outputTransformers,
                                context = context,
                                model = model,
                                assistant = assistant,
                                settings = settings,
                            )
                            emit(
                                GenerationChunk.Messages(
                                    messages.visualTransforms(
                                        transformers = outputTransformers,
                                        context = context,
                                        model = model,
                                        assistant = assistant,
                                        settings = settings,
                                    )
                                )
                            )
                        },
                        transformers = inputTransformers,
                        model = model,
                        providerImpl = providerImpl,
                        provider = provider,
                        tools = toolsForTurn,
                        memories = memories ?: emptyList(),
                        stream = assistant.streamOutput,
                        processingStatus = processingStatus,
                        conversationSystemPrompt = conversationSystemPrompt,
                        conversationModeInjectionIds = conversationModeInjectionIds,
                        conversationLorebookIds = conversationLorebookIds,
                        workspaceCwd = workspaceCwd,
                        suppressMemory = suppressMemory,
                        suppressAssistantPrompt = suppressAssistantPrompt,
                        suppressRecentChats = suppressRecentChats,
                        enforceSubAgentPromptRules = enforceSubAgentPromptRules,
                        orchestratorMode = orchestratorMode,
                        reasoningLevelOverride = reasoningLevelOverride,
                    )
                    break
                } catch (t: Throwable) {
                    if (t !is CancellationException && !overflowRetried) {
                        val recovered = recoverContextOverflow(t, messages)
                        if (recovered != null) {
                            messages = recovered
                            overflowRetried = true
                            continue
                        }
                    }
                    // Preserve cancellation and put half-built tool calls in a
                    // deterministic terminal state for replay after other failures.
                    if (t !is CancellationException) {
                        val lastMsg = messages.lastOrNull()
                        if (lastMsg != null) {
                            val newParts = lastMsg.parts.map { part ->
                                if (part is UIMessagePart.Tool &&
                                    (part.approvalState is ToolApprovalState.Auto ||
                                        part.approvalState is ToolApprovalState.Pending)) {
                                    part.copy(approvalState = ToolApprovalState.Denied(
                                        "generation_failed: ${t.javaClass.simpleName}: ${t.message.orEmpty()}"
                                    ))
                                } else part
                            }
                            messages = messages.dropLast(1) + lastMsg.copy(parts = newParts)
                            emit(GenerationChunk.Messages(messages))
                        }
                    }
                    throw t
                }
            }
            messages = messages.visualTransforms(
                transformers = outputTransformers,
                context = context,
                model = model,
                assistant = assistant,
                settings = settings
            )
            messages = messages.onGenerationFinish(
                transformers = outputTransformers,
                context = context,
                model = model,
                assistant = assistant,
                settings = settings
            )
            messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                finishedAt = Clock.System.now()
                    .toLocalDateTime(TimeZone.currentSystemDefault())
            )
            emit(GenerationChunk.Messages(messages))
        }

        for (stepIndex in 0 until maxSteps) {
            // Repeated loop-guard trips mean the model is flailing: it bumps into the
            // guard, picks a different tool, that one also gets guarded, and so on. After
            // N trips stop the tool-capable loop; the mandatory no-tools wrap-up below
            // still gives it one bounded chance to report instead of disappearing.
            if (loopGuardTripCount >= MAX_LOOP_GUARD_TRIPS_PER_TURN) {
                Log.w(TAG, "generateText: loop-guard tripped $loopGuardTripCount times this turn; force-ending")
                loopExit = GenerationLoopExit.LOOP_GUARD_EXHAUSTED
                break
            }

            Log.i(TAG, "streamText: start step #$stepIndex of $maxSteps (${model.id})")

            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools($assistant)")
                if (assistant.enableMemory) {
                    val memoryAssistantId = if (assistant.useGlobalMemory) {
                        MemoryRepository.GLOBAL_MEMORY_ID
                    } else {
                        assistant.id.toString()
                    }
                    buildMemoryTools(
                        json = json,
                        repository = memoryRepo,
                        assistantId = memoryAssistantId,
                        sourceConversationId = conversationId,
                    ).let(this::addAll)
                }
                addAll(tools)
            }

            // Check if we have tool calls ready to continue after user interaction.
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()

            // Mixed-state guard: if the last message has tools STILL in Pending (waiting
            // on user approval keyboard) but nothing canResumeExecution, the existing
            // path would call generateInternal and start a brand-new assistant turn,
            // orphaning the Pending tool. Bail out instead and let handleToolApproval
            // re-enter when the user taps the keyboard.
            if (pendingTools.isEmpty()) {
                val lastHasPending = messages.lastOrNull()?.parts?.any { p ->
                    p is UIMessagePart.Tool && p.isPending
                } == true
                if (lastHasPending) {
                    Log.i(TAG, "generateText: last message has Pending tools; waiting for approval, not regenerating")
                    loopExit = GenerationLoopExit.WAITING_FOR_APPROVAL
                    break
                }
            }

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                runProviderTurn(toolsInternal, systemAddendum)

                val tools = messages.last().getTools().filter { !it.isExecuted }
                if (tools.isEmpty()) {
                    // no tool calls, break
                    loopExit = GenerationLoopExit.COMPLETED_WITHOUT_TOOLS
                    break
                }

                // Imperative loop (was .map) so we can call the suspending
                // [isToolAutoApproved] FRESH per-tool, not from a frozen pre-resolved set.
                // Without this, a grant landing between the pre-resolve and the .map
                // (user taps Always-Allow on tool X mid-iteration) gets ignored — X
                // flips to Pending and a duplicate prompt is emitted even though X is
                // now persisted-approved.
                var hasPendingApproval = false
                val updatedTools = ArrayList<UIMessagePart.Tool>(tools.size)
                for (tool in tools) {
                    val toolDef = toolsInternal.find { it.name == tool.toolName }
                    // HARDLINE check: certain command patterns (rm -rf /, mkfs, shutdown,
                    // fork bomb, …) are blocked unconditionally — even "Always Allow"
                    // can't override. We check BEFORE the auto-approval lookup so a
                    // permanently-allowed termux/ssh tool still can't smuggle one of
                    // these through. Result: tool is marked Denied with the hardline
                    // reason, the regular Denied branch downstream emits an error
                    // envelope to the model without executing.
                    val hardlineReason = me.rerere.rikkahub.data.ai.tools
                        .HardlineCommandGuard.checkTool(tool.toolName, tool.input)
                    val transformed = when {
                        hardlineReason != null && tool.approvalState is ToolApprovalState.Auto -> {
                            Log.w(TAG, "hardline-blocked ${tool.toolName}: $hardlineReason")
                            tool.copy(approvalState = ToolApprovalState.Denied(
                                "blocked by safety floor (hardline): $hardlineReason. " +
                                    "This command cannot run via the agent under any " +
                                    "circumstances. If the user genuinely needs it, they " +
                                    "should run it themselves in a terminal outside the agent."
                            ))
                        }
                        // Tool needs approval and state is Auto:
                        toolDef?.needsApproval(tool.inputAsJson()) == true &&
                            tool.approvalState is ToolApprovalState.Auto -> {
                            // Fresh per-tool auto-approval check (was a frozen pre-
                            // resolved set). Costs a DataStore.first() per tool but tools
                            // are typically <5 per turn so the latency is negligible, and
                            // freshness matters for the YOLO toggle / mid-iteration grants.
                            if (isToolAutoApproved(tool.toolName)) {
                                tool  // leave as Auto so the executor runs it without prompting
                            } else {
                                hasPendingApproval = true
                                tool.copy(approvalState = ToolApprovalState.Pending)
                            }
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> tool
                    }
                    updatedTools.add(transformed)
                }

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != tools) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages))
                }

                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    loopExit = GenerationLoopExit.WAITING_FOR_APPROVAL
                    break
                }

                toolsToProcess = updatedTools
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
            }

            // Handle tools (execute approved tools, handle denied tools)
            val executedTools = arrayListOf<UIMessagePart.Tool>()
            toolsToProcess.forEach { tool ->
                when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        // Tool was denied by user
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    json.encodeToString(
                                        buildJsonObject {
                                            put(
                                                "error",
                                                JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                            )
                                        }
                                    )
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        // Tool was answered by user (e.g., ask_user tool)
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(answer)
                            )
                        )
                    }

                    is ToolApprovalState.Pending -> {
                        // Should not reach here, but just in case
                    }

                    else -> {
                        // Auto or Approved - execute the tool.
                        //
                        // Defence-in-depth HARDLINE re-check: the primary check at line ~442
                        // only runs when approvalState is Auto (the generation step that just
                        // proposed the tool). On the resume path (pendingTools branch above)
                        // tools arrive here with state=Approved and skip that block entirely.
                        // Re-check here so that a hardline-matched tool persisted in Approved
                        // state from an old DB row (pre-hardline schema, direct DB edit) can
                        // never execute via the resume path.
                        val resumeHardlineReason = me.rerere.rikkahub.data.ai.tools
                            .HardlineCommandGuard.checkTool(tool.toolName, tool.input)
                        if (resumeHardlineReason != null) {
                            Log.w(TAG, "generateText: resume-path hardline re-check blocked ${tool.toolName}: $resumeHardlineReason")
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(buildJsonObject {
                                            put("error", JsonPrimitive(
                                                "blocked by safety floor (hardline): $resumeHardlineReason. " +
                                                    "This command cannot run via the agent under any circumstances."
                                            ))
                                        })
                                    )
                                )
                            )
                            return@forEach
                        }

                        // Loop-guard: check whether the model has already called this exact
                        // tool with the same args multiple times in this turn. Refuse a
                        // repeat run and inject a "loop_detected" envelope so the model has
                        // to pivot to a different approach. Cost safety net.
                        val signature = tool.toolName + "::" + tool.input
                        // "This turn" = since the most recent user message. Earlier
                        // identical calls in PREVIOUS turns aren't the model flailing
                        // now — they're history, and counting them produces a confusing
                        // "you already called this 3 times in this turn" envelope after
                        // a single fresh call.
                        val turnStartIndex = messages.indexOfLast { it.role == MessageRole.USER }
                        val turnSlice = messages.subList(
                            (turnStartIndex + 1).coerceAtLeast(0),
                            messages.size
                        )
                        // Flatten this turn's executed tool calls in chronological order. The
                        // epoch ms (for the freshness-TTL bypass) comes from the parent
                        // message's finish/create time, matching the prior inline behaviour.
                        val priorCalls = turnSlice.flatMap { msg ->
                            val epochMs = (msg.finishedAt ?: msg.createdAt)
                                .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
                            msg.parts.filterIsInstance<UIMessagePart.Tool>()
                                .filter { it.isExecuted }
                                .map { PriorToolCall(it.toolName, it.toolName + "::" + it.input, epochMs) }
                        }
                        val loopDecision = LoopGuard.evaluate(
                            priorCalls = priorCalls,
                            toolName = tool.toolName,
                            signature = signature,
                            nowMs = System.currentTimeMillis(),
                        )
                        val priorOccurrences = loopDecision.priorOccurrences
                        if (loopDecision.block) {
                            loopGuardTripCount++
                            Log.w(TAG, "generateText: loop-guard tripped on $signature (${priorOccurrences + 1} repeat, trip #$loopGuardTripCount this turn); injecting bail-out envelope")
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put("error", JsonPrimitive("loop_detected"))
                                                put(
                                                    "recovery", JsonPrimitive(
                                                        "You have already called ${tool.toolName} with identical arguments " +
                                                            "${priorOccurrences} time(s) in this turn without making progress. " +
                                                            "Stop retrying. Either: (a) change the args meaningfully, (b) try a " +
                                                            "different tool that addresses the underlying request, or (c) hand " +
                                                            "back to the user with what you have so far. Examples: for 'search " +
                                                            "X in chrome' use open_url(\"https://www.google.com/search?q=X\") " +
                                                            "instead of fighting Chrome's URL bar via set_text; for terminal " +
                                                            "tasks use termux_run_command instead of typing into Termux."
                                                    )
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                            // Skip the actual execution. The next generation step will see
                            // this envelope and (if the model is well-prompted by the skill
                            // docs) will pivot to a different approach.
                            return@forEach
                        }
                        // Pre-parse args BEFORE the runCatching block so we can surface a
                        // clean structured envelope when the LLM provider truncates the
                        // streaming response mid-string (max_tokens hit, network drop, etc.).
                        // Without this, kotlinx.serialization's raw exception message —
                        // which includes the entire failed input — lands in the LLM-facing
                        // `detail` field, can be thousands of tokens, and the model often
                        // retries the same too-big call.
                        val parsedArgs = runCatching {
                            json.parseToJsonElement(tool.input.ifBlank { "{}" })
                        }
                        if (parsedArgs.isFailure) {
                            val cause = parsedArgs.exceptionOrNull()
                            Log.w(TAG, "tool ${tool.toolName} args failed to parse (likely truncated stream)", cause)
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(buildJsonObject {
                                            put("error", JsonPrimitive("invalid_tool_args"))
                                            put(
                                                "detail",
                                                JsonPrimitive(
                                                    (cause?.message ?: cause?.javaClass?.simpleName ?: "json_parse_failed")
                                                        .take(200)
                                                ),
                                            )
                                            put(
                                                "recovery",
                                                JsonPrimitive(
                                                    "Tool args JSON failed to parse — most often the provider's " +
                                                        "stream was cut off mid-string by max_tokens or a network drop. " +
                                                        "Retry with a shorter call. For long payloads (e.g. a 4000-char " +
                                                        "message), split into multiple smaller tool calls or shrink the " +
                                                        "content."
                                                ),
                                            )
                                            put(
                                                "exception",
                                                JsonPrimitive(cause?.javaClass?.simpleName ?: "JsonParseException"),
                                            )
                                        })
                                    )
                                )
                            )
                            return@forEach
                        }
                        runCatching {
                            val toolDef = toolsInternal.find { toolDef -> toolDef.name == tool.toolName }
                                ?: error("Tool ${tool.toolName} not found")
                            val args = parsedArgs.getOrThrow()
                            Log.i(TAG, "generateText: executing tool ${toolDef.name} with args: ${redactSecrets(args)}")
                            // Mark the tool as "execution started" BEFORE actually running.
                            // ChatService persists this when it sees the chunk so a process
                            // kill between mark-and-output leaves a clear breadcrumb on disk:
                            // on replay we'll see Approved + executionStartedAt + empty output
                            // and refuse to silently re-run. The mark survives via the
                            // existing emit-and-persist plumbing — see ChatService chunk
                            // handler's needsImmediatePersist branch.
                            val markedTool = tool.copy(executionStartedAt = System.currentTimeMillis())
                            run {
                                val lastMsg = messages.lastOrNull()
                                if (lastMsg != null) {
                                    val markedParts = lastMsg.parts.map { p ->
                                        if (p is UIMessagePart.Tool && p.toolCallId == tool.toolCallId) markedTool else p
                                    }
                                    messages = messages.dropLast(1) + lastMsg.copy(parts = markedParts)
                                    emit(GenerationChunk.Messages(messages))
                                }
                            }
                            // Every invocation gets the complete configured timeout. Do not
                            // subtract model inference, approval waits, deep sleep, or prior tool
                            // calls: doing so caused later calls to be rejected before execution.
                            val timeoutMs = toolCallTimeoutBudget.nextTimeoutMs()
                            val result = withTimeoutOrNull(timeoutMs) { toolDef.execute(args) }
                                ?: run {
                                    Log.w(TAG, "generateText: ${toolDef.name} exceeded its ${timeoutMs}ms per-call timeout")
                                    listOf(UIMessagePart.Text(json.encodeToString(buildJsonObject {
                                        put("error", JsonPrimitive("tool_timed_out"))
                                        put(
                                            "detail",
                                            JsonPrimitive("tool execution exceeded the ${timeoutMs / 1000}s per-call timeout")
                                        )
                                        put("timeout_ms", JsonPrimitive(timeoutMs))
                                        put(
                                            "recovery",
                                            JsonPrimitive(
                                                "The tool started but did not complete before its independent timeout. " +
                                                    "Verify the target state before retrying because a remote side effect " +
                                                    "may have completed while its response was lost."
                                            )
                                        )
                                    })))
                                }
                            // Upstream tool-output truncation: when the workspace shell is
                            // available, oversized text output is spilled to /tool_outputs/
                            // and replaced with a preview + read/grep instructions so the
                            // model can pull the full payload on demand instead of burning
                            // the context window.
                            val hasShellAccess = toolsInternal.any { it.name == "workspace_shell" }
                            executedTools += markedTool.copy(
                                output = maybeTruncateToolOutput(tool.toolCallId, result, hasShellAccess)
                            )
                        }.onFailure {
                            // Cancellation is control flow, not a tool failure. Swallowing it here
                            // converted Stop into a tool_failed result and let the cancelled pipeline
                            // advance toward another model step. Propagate it so the generation Job
                            // reaches completion and its lifecycle cleanup runs.
                            if (it is CancellationException) throw it
                            // Stack trace stays in logcat for debugging; the JSON envelope
                            // sent BACK to the LLM gets just the exception's message and a
                            // short class hint. Stuffing the full multi-frame R8-obfuscated
                            // trace into `error` (the prior behaviour) burned hundreds of
                            // tokens per failure, confused the model, and surfaced
                            // user-visible "java.lang.IllegalStateException at ..." walls
                            // for what was usually a one-line "name is required" problem.
                            Log.w(TAG, "tool ${tool.toolName} threw", it)
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put("error", JsonPrimitive("tool_failed"))
                                                put(
                                                    "detail",
                                                    // Cap at 500 chars so a tool that throws with
                                                    // a giant message (e.g. an OkHttp body dump or
                                                    // an echoed input arg) doesn't ship 8000+
                                                    // tokens back to the LLM on every failure.
                                                    JsonPrimitive((it.message ?: it.javaClass.simpleName).take(500)),
                                                )
                                                // Class name as a separate hint so the LLM can
                                                // distinguish validation (IllegalStateException /
                                                // IllegalArgumentException) from runtime issues.
                                                put(
                                                    "exception",
                                                    JsonPrimitive(it.javaClass.simpleName),
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                loopExit = GenerationLoopExit.NO_EXECUTED_TOOL_RESULTS
                break
            }

            // Update last message with executed tools (NOT create TOOL message).
            // Provider usage predates these outputs, so clear it: the next budget gate
            // must estimate the changed message including potentially large tool results.
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts, usage = null)
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                )
            )

            if (stepIndex == maxSteps - 1) {
                // The normal budget is now consumed and the last thing that happened was
                // a tool execution — the model never received the follow-up turn that
                // would have let it react to these results. Classify this forced boundary
                // so the mandatory no-tools wrap-up can produce final text.
                loopExit = GenerationLoopExit.STEP_BUDGET_EXHAUSTED_AFTER_TOOL
            }
        }

        if (FinalWrapUpPolicy.shouldRun(loopExit)) {
            val exhaustedExit = requireNotNull(loopExit)
            val exhaustionReason = requireNotNull(FinalWrapUpPolicy.reasonFor(exhaustedExit))
            val exhaustedTools = messages.lastOrNull()?.getTools().orEmpty()
            Log.w(
                TAG,
                "generateText: safety boundary $exhaustionReason reached after " +
                    "${exhaustedTools.size} tool(s) [${exhaustedTools.joinToString(", ") { it.toolName }}]; " +
                    "starting mandatory reserved no-tools wrap-up turn"
            )
            val wrapUpAddendum = listOfNotNull(
                systemAddendum?.takeIf { it.isNotBlank() },
                FinalWrapUpPolicy.addendumFor(exhaustedExit),
            ).joinToString("\n\n")
            // Exactly ONE extra provider turn, tools disabled. onBeforeProviderRequest
            // (compaction), transformers, streaming updates and overflow recovery all
            // apply via the shared runProviderTurn seam; the tool loop is NOT re-entered.
            runProviderTurn(emptyList(), wrapUpAddendum)

            // The provider was offered NO tools. If it still emitted tool calls, never
            // execute them — flip them to a deterministic terminal Denied state so a
            // later resume/replay can't run them either — and judge the turn on its text.
            val lastMsg = messages.last()
            val strayTools = lastMsg.getTools().filter { !it.isExecuted }
            if (strayTools.isNotEmpty()) {
                Log.w(TAG, "generateText: wrap-up returned ${strayTools.size} tool call(s) despite an empty tool list; refusing to execute them")
                val deniedParts = lastMsg.parts.map { part ->
                    if (part is UIMessagePart.Tool && !part.isExecuted) {
                        part.copy(
                            approvalState = ToolApprovalState.Denied(
                                "tools_unavailable_final_turn: $exhaustionReason; " +
                                    "tool calls are not available in the reserved final turn."
                            )
                        )
                    } else part
                }
                messages = messages.dropLast(1) + lastMsg.copy(parts = deniedParts)
                emit(GenerationChunk.Messages(messages))
            }

            val finalText = messages.last().parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
                .trim()
            when (FinalWrapUpPolicy.outcomeFor(finalText)) {
                WrapUpOutcome.SUCCESS_WITH_TEXT ->
                    Log.i(TAG, "generateText: reserved wrap-up completed with text (${finalText.length} chars)")
                WrapUpOutcome.EXHAUSTED_NO_TEXT -> {
                    // Explicit exhaustion: the caller must NOT read this turn as ordinary
                    // success. All partial messages/tool outputs were already emitted and
                    // persist via the normal failure-path save.
                    Log.w(TAG, "generateText: reserved wrap-up produced no text → $exhaustionReason")
                    throw GenerationStepExhaustedException(exhaustionReason)
                }
            }
        }

    }
        .onStart {
            // Reset per-turn navigation tracking and surface the overlay so the user
            // sees that automation is happening even when the agent runs from Telegram.
            AgentTurnTracker.reset()
            AgentOverlay.show(context)
        }
        .onCompletion {
            AgentOverlay.hide(context)
            handleAutoReturnAfterTurn()
        }
        .flowOn(Dispatchers.IO)

    /**
     * If the agent navigated away from RikkaHub during this turn (launch_app / open_url) and
     * the user is still on that destination, bring RikkaHub back to the foreground so the
     * user is not stranded inside Chrome / Termux / etc. If the user manually switched apps
     * mid-turn, we skip the auto-return and surface a Toast explaining the safety behavior.
     */
    private fun handleAutoReturnAfterTurn() {
        if (!AgentTurnTracker.didNavigateAway()) return
        // Only auto-return when the agent actually drove the destination app via screen
        // automation (tap, click_node, set_text, swipe, scroll, global_action). A pure
        // "open Chrome and stay there" request is just launch_app + a text reply — yanking
        // the user back to RikkaHub in that case defeats the purpose of the request.
        if (!AgentTurnTracker.didAutomate()) return
        val destination = AgentTurnTracker.lastDestination()
        val currentForeground = RikkaAccessibilityService.instance
            ?.rootInActiveWindow?.packageName?.toString()

        val userSwitchedAway = destination != null
            && currentForeground != null
            && currentForeground != destination
            && currentForeground != context.packageName

        if (userSwitchedAway) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context.applicationContext,
                    "RikkaHub: skipped auto-return because you switched apps. (Safety feature)",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // startActivity throws ActivityNotFoundException / SecurityException —
            // both Exception. Catching Throwable here would also swallow JVM errors
            // (OOM, StackOverflowError); let those propagate.
            Log.w(TAG, "auto-return launch failed", e)
        }
    }

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        systemAddendum: String? = null,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        suppressMemory: Boolean = false,
        suppressAssistantPrompt: Boolean = false,
        suppressRecentChats: Boolean = false,
        enforceSubAgentPromptRules: Boolean = false,
        orchestratorMode: OrchestratorMode = OrchestratorMode.OFF,
        reasoningLevelOverride: me.rerere.ai.core.ReasoningLevel? = null,
    ) {
        val internalMessages = buildList {
            // Conversation-level system prompt override (upstream): when the assistant
            // allows it and the conversation supplies one, it replaces the assistant prompt.
            //
            // Phase B (Orchestrator Mode): sub-agent conversations (enforceSubAgentPromptRules)
            // bypass the allowConversationSystemPrompt gate so the worker-specific prompt is
            // always used. When suppressAssistantPrompt is true (include_soul=false), only
            // the worker prompt is kept. When false (include_soul=true), soul + worker prompt
            // are concatenated so the worker gets BOTH.
            val effectiveSystemPrompt =
                if (enforceSubAgentPromptRules) {
                    when {
                        !conversationSystemPrompt.isNullOrBlank() && suppressAssistantPrompt -> {
                            conversationSystemPrompt
                        }
                        !conversationSystemPrompt.isNullOrBlank() -> {
                            listOfNotNull(
                                assistant.systemPrompt.takeIf { it.isNotBlank() },
                                conversationSystemPrompt,
                            ).joinToString("\n\n")
                        }
                        suppressAssistantPrompt -> ""
                        else -> assistant.systemPrompt
                    }
                } else if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                    conversationSystemPrompt
                } else {
                    assistant.systemPrompt
                }
            // Phase C/D — inject orchestrator preamble when orchestrator mode is ON and this
            // is NOT a worker conversation. Workers never get the preamble. The model list is
            // NOT injected here — the LLM calls subagent_list_models to discover models on
            // demand, keeping the system prompt lean.
            val orchestratorPreamble = buildOrchestratorPreamble(
                mode = orchestratorMode,
                enforceSubAgentPromptRules = enforceSubAgentPromptRules,
            )
            val finalSystemPrompt = orchestratorPreamble +
                effectiveSystemPrompt.takeIf { it.isNotBlank() }?.let {
                    if (orchestratorPreamble.isBlank()) it else "\n\n$it"
                }.orEmpty()
            val memoryPrompt = if (assistant.enableMemory && !suppressMemory) {
                buildMemoryPrompt(memories = memories)
            } else ""
            val recentChatsPrompt = if (assistant.enableRecentChatsReference && !suppressRecentChats) {
                buildRecentChatsPrompt(assistant, conversationRepo)
            } else ""
            val toolPrompts = tools.map { tool -> tool.systemPrompt(model, messages) }
            // Split into stable (assistant + tools) and volatile (memory + recent chats +
            // addendum) so prompt caching survives memory injection: the stable part is the
            // cached prefix, the volatile part sits after it. See SystemPromptBuilder.
            val (stableSystem, volatileSystem) = systemPromptBuilder.buildSections(
                assistantPrompt = finalSystemPrompt,
                memoryPrompt = memoryPrompt,
                recentChatsPrompt = recentChatsPrompt,
                toolPrompts = toolPrompts,
                systemAddendum = systemAddendum,
            )
            val systemParts = buildList {
                if (stableSystem.isNotBlank()) add(UIMessagePart.Text(stableSystem))
                if (volatileSystem.isNotBlank()) add(UIMessagePart.Text(volatileSystem))
            }
            if (systemParts.isNotEmpty()) {
                add(UIMessage(role = MessageRole.SYSTEM, parts = systemParts))
            }
            addAll(messages.limitContext(assistant.contextMessageSize).ageOldToolImages())
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
        )

        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            reasoningLevel = when {
                reasoningLevelOverride != null -> reasoningLevelOverride
                !enforceSubAgentPromptRules && orchestratorMode != OrchestratorMode.OFF ->
                    assistant.reasoningLevel.coerceAtMost(me.rerere.ai.core.ReasoningLevel.XHIGH)
                else -> assistant.reasoningLevel
            },
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            }
        )
        if (stream) {
            aiLoggingManager.addLog(
                AILogging.Generation(
                    params = params,
                    messages = messages,
                    providerSetting = provider,
                    stream = true
                )
            )
            providerImpl.streamText(
                providerSetting = provider,
                messages = internalMessages,
                params = params
            ).collect {
                messages = messages.handleMessageChunk(chunk = it, model = model)
                it.usage?.let { usage ->
                    messages = messages.mapIndexed { index, message ->
                        if (index == messages.lastIndex) {
                            message.copy(usage = message.usage.merge(usage))
                        } else {
                            message
                        }
                    }
                }
                onUpdateMessages(messages)
            }
        } else {
            aiLoggingManager.addLog(
                AILogging.Generation(
                    params = params,
                    messages = messages,
                    providerSetting = provider,
                    stream = false
                )
            )
            val chunk = providerImpl.generateText(
                providerSetting = provider,
                messages = internalMessages,
                params = params,
            )
            messages = messages.handleMessageChunk(chunk = chunk, model = model)
            chunk.usage?.let { usage ->
                messages = messages.mapIndexed { index, message ->
                    if (index == messages.lastIndex) {
                        message.copy(
                            usage = message.usage.merge(usage)
                        )
                    } else {
                        message
                    }
                }
            }
            onUpdateMessages(messages)
        }
    }

    private fun maybeTruncateToolOutput(
        toolCallId: String,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
    ): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        if (totalChars <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) return output

        Log.i(TAG, "maybeTruncateToolOutput: truncating tool $toolCallId output ($totalChars chars)")

        val fullText = textParts.joinToString("\n") { it.text }
        val preview = fullText.take(TOOL_OUTPUT_PREVIEW_CHARS)

        val fileName = "${toolCallId}.txt"
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(preview)
                }
            )
        ) + nonTextParts
    }

    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )

            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""

            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                ),
            ).collect { chunk ->
                messages = messages.handleMessageChunk(chunk)
                translatedText = messages.lastOrNull()?.toText() ?: ""

                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val chunk = providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customBody = listOf(
                        CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put(
                                    "target_lang",
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                )
                            }
                        )
                    )
                ),
            )
            val translatedText = chunk.choices.firstOrNull()?.message?.toText() ?: ""

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)
}
