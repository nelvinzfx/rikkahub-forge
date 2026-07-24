package me.rerere.rikkahub.subagent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.agentrun.AgentRunKind
import me.rerere.rikkahub.data.agentrun.AgentRunRepository
import me.rerere.rikkahub.data.agentrun.AgentRunStatus
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.ChatGenerationOverrides
import kotlin.uuid.Uuid

private const val TAG = "SubAgentEngine"

/**
 * Phase 11 — sub-agent dispatch engine.
 *
 * The engine reuses the existing cron-headless dispatch pattern (mark conv headless,
 * sendMessage, await generation flow's terminal state). It deliberately does NOT
 * re-implement [me.rerere.rikkahub.data.ai.GenerationHandler] — that path is already
 * battle-tested and any duplicate would diverge.
 *
 * Recursion guard: SubAgentEngine refuses to dispatch if the calling conversation is
 * itself headless (i.e. we're already inside a sub-agent / cron / external-automation
 * run). The four `subagent_*` tools are also not registered for headless conversations
 * via the standard tool gating in [me.rerere.rikkahub.data.ai.tools.LocalTools] — but the
 * engine-level check is the load-bearing guard since a misconfigured assistant could
 * still try to call us. v1: no recursion.
 *
 * Concurrency caps:
 *  - Per-assistant cap from [me.rerere.rikkahub.data.model.Assistant.maxConcurrentSubAgents]
 *  - Global cap from [SubAgentDefaults.GLOBAL_CONCURRENCY_CAP]
 *  - Both enforced at dispatch entry — over-cap requests fail fast (background) or block
 *    up to 30s waiting for a slot before failing (foreground, per spec).
 */
class SubAgentEngine(
    private val registry: SubAgentRegistry,
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    /**
     * Phase 24 — unified AgentRun ledger writer. The [SubAgentRegistry] is in-memory only,
     * so a backgrounded sub-agent does NOT survive process death — its registry entry is
     * gone on restart. Writing each sub-agent run to the persistent ledger closes that gap:
     * a run left `running` when the process dies is flipped to `process_lost` by
     * [me.rerere.rikkahub.data.agentrun.AgentRunBootRecovery] on next start, so the user
     * (and `subagent_get`, via the ledger) can see what actually happened. No DI-cycle
     * risk: AgentRunRepository depends only on its DAO.
     */
    private val agentRunRepo: AgentRunRepository,
) {

    /**
     * [ChatService] is resolved lazily via Koin to break the construction cycle:
     *   - [ChatService] constructor takes [LocalTools]
     *   - [LocalTools] constructor takes [SubAgentEngine] (so subagent_dispatch can fire)
     *   - [SubAgentEngine] needs [ChatService] only at dispatch time (sendMessage), so
     *     eager constructor injection here would close the cycle.
     * Same lazy-Koin pattern as [me.rerere.rikkahub.workflow.execution.WorkflowEngine.localTools].
     * Verified post-DI-fix 2026-05-08 — installed APK reaches MainActivity without crash.
     */
    private val chatService: ChatService by lazy {
        org.koin.java.KoinJavaComponent.getKoin().get<ChatService>()
    }

    /**
     * Phase 24 — maps a sub-agent run id to its `agent_runs` ledger row id. Populated when
     * the run is dispatched, consulted by [executeRun] / [markTerminal] when transitioning
     * the ledger row, removed when the run reaches a terminal status. A run with no entry
     * here simply skips the ledger write (best-effort — the ledger never breaks a run).
     */
    private val ledgerIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    sealed class DispatchResult {
        data class Ok(val run: SubAgentRun) : DispatchResult()
        data class Reject(val error: String, val detail: String) : DispatchResult()
    }

    /**
     * Dispatch a sub-agent. For foreground runs, blocks until terminal status; for
     * background, returns immediately with a PENDING-then-RUNNING run that the caller
     * can poll via subagent_get.
     */
    suspend fun dispatch(
        parentAssistantId: String,
        parentChatId: String?,
        request: SubAgentRequest,
    ): DispatchResult = withContext(Dispatchers.Default) {
        // Continuations preserve the source's position in the orchestration tree. New runs
        // derive direct-parent and root lineage from the currently executing worker.
        val callerWorker = parentChatId?.let(SubAgentConversationTracker::lookup)
        val sourceRun = request.sourceRunId?.let { sourceId ->
            registry.getScoped(sourceId, registry.scopeFor(parentAssistantId, parentChatId))
                ?: return@withContext DispatchResult.Reject(
                    "unknown_run", "no accessible terminal sub-agent run with id '$sourceId'"
                )
        }
        if (sourceRun != null && !sourceRun.status.isTerminal()) {
            return@withContext DispatchResult.Reject(
                "source_not_terminal", "source run '${sourceRun.id}' is still active"
            )
        }
        val childDepth = sourceRun?.depth ?: ((callerWorker?.depth ?: -1) + 1)
        val childOrchestratorId = sourceRun?.orchestratorRunId
            ?: callerWorker?.let { it.orchestratorRunId ?: it.runId }
        val callerRun = callerWorker?.runId?.let(registry::get)
        val childParentRunId = sourceRun?.parentRunId ?: callerWorker?.runId
        val childOwnerChatId = sourceRun?.ownerChatId ?: callerRun?.ownerChatId ?: parentChatId
        val parentUuid = runCatching { Uuid.parse(parentAssistantId) }.getOrNull()
            ?: return@withContext DispatchResult.Reject(
                "invalid_assistant_id", "parent assistant id '$parentAssistantId' is not a valid UUID"
            )
        val parentAssistant = settingsStore.settingsFlow.first().getAssistantById(parentUuid)
            ?: return@withContext DispatchResult.Reject(
                "invalid_assistant_id", "no assistant with id '$parentAssistantId' is configured"
            )
        val maxDepth = parentAssistant.subAgentMaxDepth
        if (!SubAgentDepthPolicy.canDispatch(childDepth, maxDepth)) {
            return@withContext DispatchResult.Reject(
                "depth_cap_reached",
                "sub-agent depth $childDepth is not allowed by maxDepth $maxDepth for this assistant"
            )
        }
        // Phase D — rate-limit check (sliding 60s window per assistant).
        val rateLimit = parentAssistant?.subAgentDispatchRateLimit ?: 0
        if (rateLimit > 0 && !registry.checkAndRecordRateLimit(parentAssistantId, rateLimit)) {
            return@withContext DispatchResult.Reject(
                "rate_limited",
                "dispatch rate limit of $rateLimit/minute for this assistant is exceeded"
            )
        }
        // Still reject dispatch from non-sub-agent headless contexts (cron, workflow,
        // external automation) — those aren't orchestrator contexts and shouldn't spawn.
        if (parentChatId != null) {
            val parentUuid = runCatching { Uuid.parse(parentChatId) }.getOrNull()
            if (parentUuid != null && HeadlessConversations.isHeadless(parentUuid)
                && SubAgentConversationTracker.lookup(parentChatId) == null) {
                return@withContext DispatchResult.Reject(
                    "no_recursion",
                    "sub-agent dispatch is not allowed from inside a headless run (cron / workflow / external automation)"
                )
            }
        }
        val validation = SubAgentRequestValidator.validate(request)
        if (validation is SubAgentRequestValidator.Result.Reject) {
            return@withContext DispatchResult.Reject(validation.error, validation.detail)
        }
        val cleaned = (validation as SubAgentRequestValidator.Result.Ok).request

        // Concurrency cap. Global first (cheaper), then per-assistant.
        if (registry.globalActiveCount() >= SubAgentDefaults.GLOBAL_CONCURRENCY_CAP) {
            return@withContext DispatchResult.Reject(
                "global_cap_reached",
                "max ${SubAgentDefaults.GLOBAL_CONCURRENCY_CAP} concurrent sub-agents across all assistants"
            )
        }
        val perAssistantCap = currentAssistantCap(parentAssistantId)
        if (registry.activeCountForAssistant(parentAssistantId) >= perAssistantCap) {
            return@withContext DispatchResult.Reject(
                "assistant_cap_reached",
                "this assistant's max_concurrent_sub_agents cap of $perAssistantCap is reached"
            )
        }

        // Strictly validate explicit per-call model selection before deriving model-specific
        // tool capabilities. Assistant defaults remain best-effort and fall back to inherit.
        val settings = settingsStore.settingsFlow.first()
        val explicitModelId = request.modelId?.let { mid ->
            val parsed = runCatching { Uuid.parse(mid) }.getOrNull()
                ?: return@withContext DispatchResult.Reject(
                    "invalid_model_id", "model_id '$mid' is not a valid UUID"
                )
            val model = settings.findModelById(parsed)
                ?: return@withContext DispatchResult.Reject(
                    "invalid_model_id", "no model with id '$mid' is configured"
                )
            val provider = model.findProvider(settings.providers)
                ?: return@withContext DispatchResult.Reject(
                    "invalid_model_id", "model '$mid' has no configured provider"
                )
            if (!provider.enabled) {
                return@withContext DispatchResult.Reject(
                    "model_provider_disabled",
                    "provider '${provider.name}' (for model '$mid') is disabled - pick a different model or re-enable it"
                )
            }
            parsed
        }
        val configuredModelId = explicitModelId ?: parentAssistant.subAgentModelId
        val effectiveToolModelId = configuredModelId?.takeIf { id ->
            val configured = settings.findModelById(id)
            configured?.findProvider(settings.providers)?.enabled == true
        }
        val eligibleTools = chatService.resolveSubAgentToolNames(
            assistantId = parentUuid,
            modelId = effectiveToolModelId,
            allowDispatchTools = SubAgentDepthPolicy.canSpawnChild(childDepth, maxDepth),
        )
        val effectiveTools = when (val resolved = SubAgentToolAllowlist.resolve(cleaned.tools, eligibleTools)) {
            is ToolAllowlistResult.Ok -> resolved.names ?: eligibleTools
            is ToolAllowlistResult.Reject -> return@withContext DispatchResult.Reject(
                "unknown_tools",
                "requested tools are not eligible for this worker: ${resolved.unknown.sorted().joinToString(", ")}"
            )
        }.toSet()

        val runId = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val initialRun = SubAgentRun(
            id = runId,
            parentChatId = parentChatId,
            parentAssistantId = parentAssistantId,
            ownerChatId = childOwnerChatId,
            label = cleaned.label?.takeIf { it.isNotBlank() } ?: cleaned.task.take(60),
            task = cleaned.task,
            modelId = cleaned.modelId,
            // Preserve the public request contract: null means inherited, [] means none.
            // The stable resolved snapshot is carried separately into executeRun.
            tools = cleaned.tools?.distinct(),
            runInBackground = cleaned.runInBackground,
            notifyParent = cleaned.notifyParent,
            reasoningLevel = cleaned.reasoningLevel?.name,
            timeoutSeconds = cleaned.timeoutSeconds,
            maxTrips = cleaned.maxTrips,
            status = SubAgentStatus.PENDING,
            startedAtMs = now,
            depth = childDepth,
            orchestratorRunId = childOrchestratorId,
            parentRunId = childParentRunId,
            sourceRunId = cleaned.sourceRunId,
            conversationId = cleaned.workerConversationId,
        )
        val executionHandle = SubAgentExecutionHandle()
        if (!registry.addPending(initialRun, executionHandle)) {
            return@withContext DispatchResult.Reject(
                "continuation_active", "the source worker conversation already has an active continuation"
            )
        }

        // Phase 24 — open the cross-pillar ledger row. domain_id is the sub-agent run id.
        // The row starts in `queued` (the execution coroutine hasn't been launched yet);
        // executeRun() flips it to `running`. If the process dies before then, boot
        // recovery flips the stranded `queued` row to `process_lost`.
        val ledgerId = agentRunRepo.open(
            kind = AgentRunKind.SubAgent,
            domainId = runId,
            parentRunId = parentChatId,
            status = AgentRunStatus.queued,
            metadata = buildJsonObject {
                put("label", initialRun.label)
                put("parent_assistant_id", parentAssistantId)
                put("run_in_background", cleaned.runInBackground)
            },
        )
        ledgerIds[runId] = ledgerId

        val executionJob = appScope.launch(Dispatchers.IO) {
            try {
                executeRun(
                    runId, parentAssistantId, parentChatId, cleaned, childDepth,
                    childOrchestratorId, effectiveTools, executionHandle,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    markTerminal(
                        runId, SubAgentStatus.FAILED,
                        "${failure::class.simpleName}: ${failure.message.orEmpty()}",
                    )
                }
            } finally {
                withContext(NonCancellable) {
                    finalizeStartupCancellation(runId)
                }
            }
        }
        executionHandle.attachSupervisor(executionJob)
        executionJob.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                appScope.launch(NonCancellable) {
                    finalizeStartupCancellation(runId)
                }
            }
        }
        if (cleaned.runInBackground) {
            // Return immediately; final status delivered via registry observation.
            DispatchResult.Ok(registry.get(runId) ?: initialRun)
        } else {
            // Foreground — block until terminal.
            try {
                executionJob.join()
            } catch (t: Throwable) {
                Log.w(TAG, "foreground sub-agent join failed for $runId", t)
            }
            DispatchResult.Ok(registry.get(runId) ?: initialRun)
        }
    }

    /**
     * Dispatch a sub-agent into an existing worker conversation (continue a failed/
     * timed-out/cancelled run). Behaves like a normal dispatch but reuses the target
     * conversation instead of creating a new one. The worker sees the full history
     * of the previous run, including any partial results, tool outputs, and errors.
     *
     * The source is addressed by run id and must be terminal and caller-owned. The actual
     * worker conversation is re-marked/re-registered for this attempt, and only output and
     * usage after the continuation boundary are harvested.
     */
    suspend fun dispatchContinue(
        parentAssistantId: String,
        parentChatId: String?,
        sourceRunId: String,
        task: String,
        label: String? = null,
        modelId: String? = null,
        systemPrompt: String? = null,
        tools: List<String>? = null,
        runInBackground: Boolean = true,
        notifyParent: Boolean = false,
        reasoningLevel: me.rerere.ai.core.ReasoningLevel? = null,
        timeoutSeconds: Int = SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
        maxTrips: Int = SubAgentDefaults.DEFAULT_MAX_TRIPS,
        includeMemory: Boolean? = null,
        includeSoul: Boolean? = null,
        includeRecentChats: Boolean? = null,
    ): DispatchResult {
        val scope = registry.scopeFor(parentAssistantId, parentChatId)
        val candidate = registry.get(sourceRunId)
        val source = registry.getScoped(sourceRunId, scope)
        val activeConversation = source?.conversationId
            ?.let(registry::hasActiveConversation) == true
        when (SubAgentContinuationPolicy.validate(
            source = candidate,
            authorized = source != null,
            conversationActive = activeConversation,
        )) {
            ContinuationPolicyResult.UNKNOWN_OR_UNAUTHORIZED -> return DispatchResult.Reject(
                "unknown_run", "no accessible terminal sub-agent run with id '$sourceRunId'"
            )
            ContinuationPolicyResult.SOURCE_NOT_TERMINAL -> return DispatchResult.Reject(
                "source_not_terminal", "source run '$sourceRunId' is still active"
            )
            ContinuationPolicyResult.CONVERSATION_ACTIVE -> return DispatchResult.Reject(
                "continuation_active", "the source worker conversation already has an active continuation"
            )
            ContinuationPolicyResult.OK -> Unit
        }
        val ownedSource = requireNotNull(source)
        val workerConversationId = ownedSource.conversationId
            ?: return DispatchResult.Reject("worker_missing", "source run has no worker conversation")
        val workerUuid = runCatching { Uuid.parse(workerConversationId) }.getOrNull()
            ?: return DispatchResult.Reject("worker_missing", "source worker conversation id is invalid")
        val worker = conversationRepo.getConversationById(workerUuid)
            ?: return DispatchResult.Reject("worker_missing", "source worker conversation no longer exists")
        if (worker.assistantId.toString() != parentAssistantId) {
            return DispatchResult.Reject(
                "unknown_run", "no accessible terminal sub-agent run with id '$sourceRunId'"
            )
        }
        val request = SubAgentRequest(
            task = task,
            modelId = modelId,
            systemPrompt = systemPrompt,
            tools = tools,
            runInBackground = runInBackground,
            timeoutSeconds = timeoutSeconds,
            maxTrips = maxTrips,
            label = label,
            includeMemory = includeMemory,
            includeSoul = includeSoul,
            includeRecentChats = includeRecentChats,
            notifyParent = notifyParent,
            reasoningLevel = reasoningLevel,
            workerConversationId = workerConversationId,
            sourceRunId = sourceRunId,
        )
        return dispatch(parentAssistantId, parentChatId, request)
    }

    private suspend fun currentAssistantCap(parentAssistantId: String): Int {
        val asstUuid = runCatching { Uuid.parse(parentAssistantId) }.getOrNull() ?: return SubAgentDefaults.MAX_PER_ASSISTANT_CAP
        val settings = settingsStore.settingsFlow.first()
        val asst = settings.assistants.firstOrNull { it.id == asstUuid }
            ?: return SubAgentDefaults.MAX_PER_ASSISTANT_CAP
        return asst.maxConcurrentSubAgents.coerceIn(
            SubAgentDefaults.MIN_PER_ASSISTANT_CAP,
            SubAgentDefaults.MAX_PER_ASSISTANT_CAP,
        )
    }

    private suspend fun executeRun(
        runId: String,
        parentAssistantId: String,
        parentChatId: String?,
        request: SubAgentRequest,
        childDepth: Int,
        childOrchestratorId: String?,
        effectiveTools: Set<String>,
        executionHandle: SubAgentExecutionHandle,
    ) {
        registry.update(runId) { it.copy(status = SubAgentStatus.RUNNING) }
        ledgerIds[runId]?.let { agentRunRepo.setStatus(it, AgentRunStatus.running) }

        val parentAsstUuid = runCatching { Uuid.parse(parentAssistantId) }.getOrNull()
            ?: run {
                markTerminal(runId, SubAgentStatus.FAILED, "bad parent assistant id")
                return
            }
        // Phase 30 (Orchestrator Mode Phase A) - resolve the worker's model + system prompt.
        // Resolution order (first non-null wins):
        //   model:    request.modelId (LLM per-call) -> assistant.subAgentModelId -> null (inherit)
        //   prompt:   request.systemPrompt (LLM per-call) -> assistant.subAgentSystemPrompt -> null
        // Null model = inherit: conversation.chatModelId stays null and ChatService falls
        // back to assistant.chatModelId / global default. request.modelId was already strict-
        // validated in dispatch(); here we only do the run-time fallback for the assistant
        // default (subAgentModelId), which was NOT strict-validated - if its provider got
        // disabled between dispatch and now, drop to inherit and record the fallback.
        val settings = settingsStore.settingsFlow.first()
        val parentAssistant = settings.getAssistantById(parentAsstUuid)
        var chosenModelId: Uuid? = request.modelId
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: parentAssistant?.subAgentModelId
        var fallbackUsed = false
        var fallbackReason: String? = null
        if (chosenModelId != null) {
            val chosen = settings.findModelById(chosenModelId)
            val provider = chosen?.findProvider(settings.providers)
            if (chosen == null || provider == null || !provider.enabled) {
                fallbackUsed = true
                fallbackReason = buildString {
                    append("requested sub-agent model unavailable")
                    if (provider != null && !provider.enabled) append(" (provider '${provider.name}' disabled)")
                    append(" - falling back to assistant/global default")
                }
                chosenModelId = null
            }
        }
        val workerReasoningLevel = request.reasoningLevel
            ?: parentAssistant?.subAgentReasoningLevel
            ?: me.rerere.ai.core.ReasoningLevel.AUTO
        val workerSystemPrompt = (request.systemPrompt ?: parentAssistant?.subAgentSystemPrompt)
            ?.takeIf { it.isNotBlank() }
        // Phase B — resolve include_* args against assistant defaults. suppress = !(include).
        val suppressMemory = !(request.includeMemory ?: parentAssistant?.subAgentDefaultIncludeMemory ?: true)
        val suppressSoul = !(request.includeSoul ?: parentAssistant?.subAgentDefaultIncludeSoul ?: true)
        val suppressChats = !(request.includeRecentChats ?: parentAssistant?.subAgentDefaultIncludeRecentChats ?: true)
        val conv = Conversation.ofId(
            id = Uuid.random(),
            assistantId = parentAsstUuid,
            newConversation = true,
        ).copy(
            title = "[Sub-agent] ${request.label?.take(40) ?: request.task.take(40)}",
            chatModelId = chosenModelId,
            customSystemPrompt = workerSystemPrompt,
            suppressMemory = suppressMemory,
            suppressAssistantPrompt = suppressSoul,
            suppressRecentChats = suppressChats,
            enforceSubAgentPromptRules = true,
        )
        val workerConvId = if (request.workerConversationId != null) {
            val targetUuid = runCatching { Uuid.parse(request.workerConversationId) }.getOrNull()
            if (targetUuid == null) {
                markTerminal(runId, SubAgentStatus.FAILED, "invalid worker conversation", 0)
                return
            }
            registry.update(runId) { it.copy(conversationId = request.workerConversationId) }
            chatService.initializeConversation(targetUuid)
            targetUuid
        } else {
            conversationRepo.insertConversation(conv)
            registry.update(runId) { it.copy(conversationId = conv.id.toString()) }
            chatService.initializeConversation(conv.id)
            conv.id
        }
        val continuationBoundary = conversationRepo.getConversationById(workerConvId)
            ?.messageNodes?.size ?: 0
        HeadlessConversations.mark(workerConvId)
        SubAgentConversationTracker.register(
            workerConvId.toString(), runId, childDepth, childOrchestratorId
        )
        var generationJob: Job? = null
        try {
            // Prepend a wrap-up instruction. Some models naturally write a summary paragraph
            // after their tool-call sequence; others stop after the last tool result and emit
            // no closing text. Without explicit text the parent has nothing to harvest and
            // the sub-agent's findings are lost.
            val taskWithWrapup = buildString {
                append(request.task)
                appendLine()
                appendLine()
                append("When you have finished, end with one short paragraph in plain text that summarises what you did and what you found. Do NOT stop on a tool call — finish with assistant text. The dispatcher harvests only your final text reply, so this paragraph is the entire response the parent sees.")
            }
            val startedGeneration = chatService.sendMessageOwned(
                workerConvId,
                listOf(UIMessagePart.Text(taskWithWrapup)),
                reasoningLevelOverride = workerReasoningLevel,
                generationMaxSteps = request.maxTrips,
                exactToolNames = effectiveTools,
                generationOverrides = ChatGenerationOverrides(
                    chatModelId = chosenModelId,
                    customSystemPrompt = workerSystemPrompt,
                    suppressMemory = suppressMemory,
                    suppressAssistantPrompt = suppressSoul,
                    suppressRecentChats = suppressChats,
                    enforceSubAgentPromptRules = true,
                ),
            ) ?: throw IllegalStateException("worker generation did not start")
            generationJob = startedGeneration
            executionHandle.attachGeneration(startedGeneration)
            Log.i(TAG, "sub-agent $runId dispatched with maxTrips=${request.maxTrips} normal turn(s) + one reserved no-tools wrap-up")
            // The naive form `withTimeoutOrNull { …first { it == null } }` followed by a
            // `finished == null` check is BROKEN: `.first { it == null }` returns the matched
            // value — which IS null on successful completion (the Job? went to null when the
            // LLM finished). So `finished == null` was true on BOTH timeout AND success, and
            // every sub-agent looked TIMED_OUT despite actually finishing. Use a Unit sentinel
            // so the two outcomes are distinguishable.
            val completed: Unit? = withTimeoutOrNull(request.timeoutSeconds * 1000L) {
                startedGeneration.join()
                Unit
            }
            if (completed == null) {
                executionHandle.requestGenerationStop()
                stopWorkerThenMarkTerminal(
                    runId = runId,
                    workerConvId = workerConvId,
                    generationJob = startedGeneration,
                    status = SubAgentStatus.TIMED_OUT,
                    error = "exceeded ${request.timeoutSeconds}-second cap",
                    boundary = continuationBoundary,
                )
                if (registry.get(runId)?.notifyParent == true) {
                    notifyParentIfBackground(parentChatId, registry.get(runId))
                }
                return
            }
            // Harvest the assistant's final text from the conversation. Best-effort —
            // we read the latest persisted state of the conversation and concatenate any
            // text parts from the last assistant message. This mirrors how the
            // CronJobWorker treats LLM-mode jobs.
            val finalText = harvestFinalText(workerConvId, continuationBoundary)
            if (!SubAgentTerminalOutcome.canSucceed(finalText)) {
                // No harvestable result — most commonly step exhaustion where even the
                // reserved wrap-up produced no text (GenerationStepExhaustedException
                // surfaced through ChatService's error channel). Never mark SUCCEEDED
                // with an empty result: the parent would harvest nothing and treat the
                // run as useful work. Partial tool outputs/assistant text stay in the
                // worker conversation; markTerminal's own harvest keeps any walk-back
                // text it can find.
                markTerminal(
                    runId,
                    SubAgentStatus.FAILED,
                    me.rerere.rikkahub.data.ai.REASON_MAX_STEPS_EXHAUSTED_AFTER_TOOL +
                        ": worker finished without any final assistant text",
                    continuationBoundary,
                )
                if (registry.get(runId)?.notifyParent == true) {
                    notifyParentIfBackground(parentChatId, registry.get(runId))
                }
                return
            }
            // Phase 30 (Orchestrator Mode Phase A) - token telemetry. Sum prompt/
            // completion tokens across the worker conversation's selected message branch so
            // Phase D's subtree cap has real numbers. tripCount = assistant messages = LLM
            // round-trips. Best-effort: a missing/empty conversation just yields zeros.
            val (tokensIn, tokensOut, trips) = harvestUsage(workerConvId, continuationBoundary)
            val succeeded = registry.transitionTerminal(runId, SubAgentStatus.SUCCEEDED) {
                it.copy(
                    result = finalText,
                    reasoningLevel = workerReasoningLevel.name,
                    finishedAtMs = System.currentTimeMillis(),
                    tokensIn = tokensIn,
                    tokensOut = tokensOut,
                    tripCount = trips,
                    fallbackModelUsed = fallbackUsed,
                    fallbackReason = fallbackReason,
                )
            }
            if (!succeeded) {
                stopWorkerThenMarkTerminal(
                    runId = runId,
                    workerConvId = workerConvId,
                    generationJob = startedGeneration,
                    status = SubAgentStatus.CANCELLED,
                    error = "cancelled before terminal success",
                    boundary = continuationBoundary,
                )
                return
            }
            // Phase D — subtree token cap enforcement. Only check for the root worker
            // (orchestratorRunId == null); descendants are covered by their root's check.
            // We also check for descendants whose root is still active so the cap fires
            // regardless of which worker finishes first. The root id is either this run
            // (if it IS the root) or childOrchestratorId.
            val rootId = childOrchestratorId ?: runId
            val subtreeCap = parentAssistant?.subAgentSubtreeTokenCap
            if (subtreeCap != null && subtreeCap > 0) {
                val (subIn, subOut) = registry.subtreeTokenSum(rootId)
                val subTotal = subIn + subOut
                if (subTotal >= subtreeCap) {
                    // Cancel all remaining workers in the subtree.
                    val cancelled = registry.cancelSubtree(rootId)
                    if (cancelled > 0) {
                        Log.w(TAG, "subtree token cap hit: $subTotal >= $subtreeCap, cancelled $cancelled remaining runs")
                    }
                    // Mark the root run with the cancelled flag.
                    registry.update(rootId) { it.copy(subtreeTokenCancelled = true) }
                } else if (subTotal >= subtreeCap * 0.8) {
                    // 80% warning — surface on the root run.
                    registry.update(rootId) { it.copy(subtreeTokenWarning = true) }
                    Log.w(TAG, "subtree token warning: $subTotal >= ${subtreeCap * 0.8} (80% of $subtreeCap)")
                }
            }
            ledgerIds.remove(runId)?.let {
                agentRunRepo.markTerminal(it, AgentRunStatus.succeeded)
            }
            if (registry.get(runId)?.notifyParent == true) {
                notifyParentIfBackground(parentChatId, registry.get(runId))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "sub-agent run failed", t)
            val terminal = if (t is CancellationException || executionHandle.isStopRequested()) {
                SubAgentStatus.CANCELLED
            } else SubAgentStatus.FAILED
            stopWorkerThenMarkTerminal(
                runId = runId,
                workerConvId = workerConvId,
                generationJob = generationJob,
                status = terminal,
                error = "${t::class.simpleName}: ${t.message.orEmpty()}",
                boundary = continuationBoundary,
            )
            if (registry.get(runId)?.notifyParent == true) {
                notifyParentIfBackground(parentChatId, registry.get(runId))
            }
        } finally {
            withContext(NonCancellable) {
                generationJob?.let(executionHandle::clearGeneration)
                HeadlessConversations.unmark(workerConvId)
                SubAgentConversationTracker.unregister(workerConvId.toString())
                registry.clearExecution(runId)
            }
        }
    }

    private suspend fun finalizeStartupCancellation(runId: String) {
        val run = registry.get(runId) ?: return
        if (run.status.isTerminal()) return
        val workerConvId = run.conversationId
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (workerConvId == null) {
            // No worker conversation/session exists yet, so there is nothing ChatService
            // can detach or persist. Terminalize the pre-start cancellation directly.
            markTerminal(runId, SubAgentStatus.CANCELLED, "cancelled before worker start")
        } else {
            stopWorkerThenMarkTerminal(
                runId = runId,
                workerConvId = workerConvId,
                generationJob = null,
                status = SubAgentStatus.CANCELLED,
                error = "cancelled before worker generation started",
                boundary = 0,
            )
        }
    }

    private suspend fun stopWorkerThenMarkTerminal(
        runId: String,
        workerConvId: Uuid,
        generationJob: Job?,
        status: SubAgentStatus,
        error: String?,
        boundary: Int,
    ) = withContext(NonCancellable) {
        generationJob?.cancel()
        SubAgentTerminalCleanup.stopThenPublish(
            stop = {
                // stopGeneration detaches the ConversationSession and finalizes persisted
                // Pending tools. Bound cleanup so a non-cooperative provider/tool cannot
                // prevent terminal publication forever.
                runCatching {
                    withTimeoutOrNull(3_000L) {
                        chatService.stopGeneration(workerConvId)
                    }
                }.onFailure { cleanupError ->
                    Log.w(TAG, "worker cleanup failed before terminal publication", cleanupError)
                }
            },
            publish = {
                // Attempt-scoped harvest happens only after the real ChatService cleanup.
                markTerminal(runId, status, error, boundary)
            },
        )
    }

    private suspend fun markTerminal(
        runId: String,
        status: SubAgentStatus,
        error: String?,
        boundary: Int = 0,
    ) {
        // Harvest any partial work from the worker conversation so the parent can
        // inspect what was completed before the failure and decide whether to
        // continue it rather than re-running the same work from scratch.
        val run = registry.get(runId)
        val partialResult = run?.conversationId
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?.let { harvestFinalText(it, boundary) }
            .orEmpty()
        val (tokensIn, tokensOut, trips) = run?.conversationId
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?.let { harvestUsage(it, boundary) }
            ?: Triple(0L, 0L, 0)
        val transitioned = registry.transitionTerminal(runId, status) {
            it.copy(
                error = error,
                result = partialResult.ifEmpty { null },
                tokensIn = tokensIn,
                tokensOut = tokensOut,
                tripCount = trips,
                finishedAtMs = System.currentTimeMillis(),
            )
        }
        if (!transitioned) return
        // Phase 24 — mirror the terminal status into the cross-pillar ledger. TIMED_OUT and
        // FAILED both map to `failed`; CANCELLED maps to `cancelled`. (SUCCEEDED never
        // routes through here — it transitions the ledger row inline in executeRun.)
        ledgerIds.remove(runId)?.let { ledgerId ->
            val ledgerStatus = when (status) {
                SubAgentStatus.CANCELLED -> AgentRunStatus.cancelled
                SubAgentStatus.SUCCEEDED -> AgentRunStatus.succeeded
                else -> AgentRunStatus.failed
            }
            agentRunRepo.markTerminal(ledgerId, ledgerStatus, error)
        }
    }

    /**
     * Wake the parent conversation when a backgrounded sub-agent finishes — the parent's
     * LLM gets a synthetic user message describing the completion and naturally synthesises
     * a reply. Without this, the parent has no way to know the sub-agent finished except by
     * the user manually asking "what happened?".
     *
     * Skip rules:
     *  - Foreground runs: dispatch is synchronous (executionJob.join() in dispatch()), so the
     *    tool result already carries the final state. No wake needed.
     *  - Parents in headless mode: would loop / fork weirdly with cron + sub-agent + workflow
     *    runs. The parent must be a regular interactive (in-app or Telegram-bot) conversation.
     *  - parentChatId / runs missing: defensive.
     *
     * Cancellation hygiene: ChatService.sendMessage cancels any in-flight generation in the
     * target conversation. To avoid stomping on a turn the user is engaged with, we wait up
     * to 5 minutes for the parent to be idle before posting. After 5 minutes we post anyway
     * — better to interrupt than to silently lose the completion.
     */
    private suspend fun notifyParentIfBackground(parentChatId: String?, run: SubAgentRun?) {
        if (parentChatId == null || run == null || !run.runInBackground) return
        val parentUuid = runCatching { Uuid.parse(parentChatId) }.getOrNull() ?: return
        if (HeadlessConversations.isHeadless(parentUuid)) return

        val message = buildString {
            appendLine("[Sub-agent ${run.label} — ${run.status.name}]")
            run.error?.takeIf { it.isNotBlank() }?.let {
                appendLine("Error: $it")
            }
            run.result?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                append(it)
            }
        }.trimEnd()

        // Use sendMessageIfIdle so the wake message never cancels a user-initiated
        // generation that started between the "parent idle" check and the sendMessage
        // call. Wait up to 5 minutes for the parent to be idle; after that, drop the
        // notification rather than interrupting a human.
        runCatching {
            withTimeoutOrNull(5 * 60_000L) {
                while (!chatService.sendMessageIfIdle(parentUuid, listOf(UIMessagePart.Text(message)))) {
                    chatService.getGenerationJobStateFlow(parentUuid).first { it == null }
                    Unit
                }
                Unit
            }
        }.onFailure {
            Log.w(TAG, "failed to notify parent $parentChatId of subagent completion", it)
        }
    }

    private suspend fun harvestFinalText(conversationId: Uuid, boundary: Int = 0): String {
        // The Conversation persisted by the generation pipeline contains the full message
        // history (messageNodes). Each MessageNode holds parallel branches in
        // `messages: List<UIMessage>` keyed by `selectIndex`. Walk the currently-selected
        // branch and pull text from the last assistant message — that's the sub-agent's
        // final summary.
        //
        // Robustness: if the last assistant message has NO Text part (some models stop
        // after a tool call and emit no closing text), walk back through previous assistant
        // messages and concatenate their Text parts so we don't return empty. Better to
        // surface partial intermediate text than to return "" and lose the sub-agent's
        // work entirely.
        return runCatching {
            val conv = conversationRepo.getConversationById(conversationId) ?: return@runCatching ""
            val selectedMessages = SubAgentAttemptBoundary.after(conv.messageNodes, boundary)
                .mapNotNull { node -> node.messages.getOrNull(node.selectIndex) }
            val assistantMessages = selectedMessages.filter { msg ->
                msg.role.name.equals("assistant", ignoreCase = true)
            }
            if (assistantMessages.isEmpty()) return@runCatching ""

            // Try the last assistant message's text first.
            val lastTexts = assistantMessages.last().parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
                .trim()
            if (lastTexts.isNotBlank()) return@runCatching lastTexts

            // Fallback: collect text from all assistant messages (preserve order).
            assistantMessages
                .flatMap { it.parts.filterIsInstance<UIMessagePart.Text>() }
                .joinToString("\n") { it.text }
                .trim()
        }.getOrDefault("")
    }

    /**
     * Phase 30 (Orchestrator Mode Phase A) — best-effort token + trip telemetry.
     *
     * Walks the selected branch of the worker conversation, sums [me.rerere.ai.core.TokenUsage]
     * across ALL messages (both user and assistant — providers report cumulative usage on the
     * last assistant chunk, so summing only assistant messages double-counts; summing all is
     * safe because user messages have usage == null), and counts assistant messages as trips.
     *
     * Returns (promptTokens, completionTokens, assistantMessageCount). Best-effort: a missing
     * conversation or one with no usage data yields (0, 0, N) — the trip count is still useful.
     */
    private suspend fun harvestUsage(conversationId: Uuid, boundary: Int = 0): Triple<Long, Long, Int> {
        return runCatching {
            val conv = conversationRepo.getConversationById(conversationId) ?: return@runCatching Triple(0L, 0L, 0)
            val selectedMessages = SubAgentAttemptBoundary.after(conv.messageNodes, boundary)
                .mapNotNull { node -> node.messages.getOrNull(node.selectIndex) }
            SubAgentAttemptBoundary.usageAfter(
                items = selectedMessages,
                boundary = 0,
                usageOf = { msg -> msg.usage?.let { it.promptTokens.toLong() to it.completionTokens.toLong() } },
                isAssistant = { msg -> msg.role.name.equals("assistant", ignoreCase = true) },
            )
        }.getOrDefault(Triple(0L, 0L, 0))
    }
}
