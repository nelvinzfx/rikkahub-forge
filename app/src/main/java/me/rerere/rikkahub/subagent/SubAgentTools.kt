package me.rerere.rikkahub.subagent

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private fun errEnv(error: String, detail: String): List<UIMessagePart> {
    val obj = buildJsonObject {
        put("error", error)
        put("detail", detail)
    }
    return listOf(UIMessagePart.Text(obj.toString()))
}

private fun encodeRun(run: SubAgentRun): kotlinx.serialization.json.JsonObject = buildJsonObject {
    put("id", run.id)
    put("status", run.status.name)
    put("label", run.label)
    if (run.modelId != null) put("model_id", run.modelId)
    put("run_in_background", run.runInBackground)
    put("timeout_seconds", run.timeoutSeconds)
    put("max_trips", run.maxTrips)
    put("started_at_ms", run.startedAtMs)
    if (run.finishedAtMs != null) put("finished_at_ms", run.finishedAtMs)
    if (run.result != null) put("result", run.result)
    if (run.error != null) put("error", run.error)
    put("tokens_in", run.tokensIn)
    put("tokens_out", run.tokensOut)
    put("trip_count", run.tripCount)
    if (run.fallbackModelUsed) put("fallback_model_used", true)
    if (run.fallbackReason != null) put("fallback_reason", run.fallbackReason)
    put("depth", run.depth)
    if (run.orchestratorRunId != null) put("orchestrator_run_id", run.orchestratorRunId)
    if (run.subtreeTokenWarning) put("subtree_token_warning", true)
    if (run.subtreeTokenCancelled) put("subtree_token_cancelled", true)
    if (run.conversationId != null) put("conversation_id", run.conversationId)
}

/**
 * Phase 11 — sub-agent dispatch + observation tools. The four register only when the
 * assistant has the `Sub-agents` Local Tools toggle on, AND the calling conversation is
 * NOT itself headless (the engine refuses recursive dispatch — these tools are not
 * useful inside a sub-agent run).
 */

fun subagentDispatchTool(
    engine: SubAgentEngine,
    callerContext: me.rerere.rikkahub.data.ai.tools.ToolInvocationContext =
        me.rerere.rikkahub.data.ai.tools.ToolInvocationContext.EMPTY,
): Tool = Tool(
    name = "subagent_dispatch",
    description = """
        Dispatch a focused sub-agent — a clean-context LLM run that returns a concise
        summary. Use when the task is independent (research, lookup, multi-step work)
        and would otherwise pollute your context with intermediate output, OR when the
        user explicitly asks for parallel work.

        Pass a clear, self-contained task — the sub-agent doesn't see your conversation,
        so restate any context it needs. Pass a short label so the user can recognise
        the running sub-agent. For long-running work, set run_in_background=true and
        poll with subagent_get; otherwise foreground (default) blocks until terminal.

        model_id: call subagent_list_models first to find available models across all
        providers, then pass the UUID here. Omit to inherit the current model.

        timeout_seconds: default 600 (10 min). Set higher (up to 1800) for research tasks
        that need multiple web searches. max_trips: default 20. Set higher (up to 30) for
        multi-step work.

        Concurrency caps: each assistant has its own (default 3, configurable 1-8) and
        there's a global cap of 16 across all assistants. Over-cap dispatches fail with
        a clear error — back off and retry, or wait for a slot.

        Workers that fail, time out, or are cancelled still harvest whatever text they
        produced before termination — check their result via subagent_get or
        subagent_wait_all before re-running the task. You can also dispatch a follow-up
        worker targeting the original worker's conversation (use the conversation_id
        from the run record) to continue its work.

        Approval-required: every dispatch needs explicit confirmation. Eligible for
        Always Allow if the user trusts the assistant to delegate freely.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("task", buildJsonObject { put("type", "string") })
                put("label", buildJsonObject { put("type", "string") })
                put("model_id", buildJsonObject { put("type", "string") })
                put("system_prompt", buildJsonObject { put("type", "string") })
                put("tools", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("run_in_background", buildJsonObject { put("type", "boolean") })
                put("timeout_seconds", buildJsonObject { put("type", "integer") })
                put("max_trips", buildJsonObject { put("type", "integer") })
                put("include_memory", buildJsonObject { put("type", "boolean") })
                put("include_soul", buildJsonObject { put("type", "boolean") })
                put("include_recent_chats", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("task"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        // Phase C — depth cap check at the tool level. The engine's guard checks depth
        // from parentChatId, but the tool's callerContext.isHeadless catches cron /
        // workflow (no conversation id). For sub-agent conversations, the engine's depth
        // check is the load-bearing guard. Non-sub-agent headless runs are still rejected.
        if (callerContext.isHeadless &&
            callerContext.callerConversationId?.let { SubAgentConversationTracker.lookup(it) } == null
        ) {
            return@Tool errEnv(
                "no_recursion",
                "sub-agent dispatch is not allowed from inside a headless run (cron / workflow / external automation). Run the work inline instead.",
            )
        }
        val params = args.jsonObject
        val task = params["task"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnv("invalid_task", "task is required")
        val request = SubAgentRequest(
            task = task,
            modelId = params["model_id"]?.jsonPrimitive?.contentOrNull,
            systemPrompt = params["system_prompt"]?.jsonPrimitive?.contentOrNull,
            tools = params["tools"]?.let { runCatching { it.jsonArray }.getOrNull() }
                ?.mapNotNull { it.jsonPrimitive.contentOrNull },
            runInBackground = params["run_in_background"]?.jsonPrimitive?.booleanOrNull ?: false,
            timeoutSeconds = params["timeout_seconds"]?.jsonPrimitive?.intOrNull
                ?: SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
            maxTrips = params["max_trips"]?.jsonPrimitive?.intOrNull
                ?: SubAgentDefaults.DEFAULT_MAX_TRIPS,
            label = params["label"]?.jsonPrimitive?.contentOrNull,
            includeMemory = params["include_memory"]?.jsonPrimitive?.booleanOrNull,
            includeSoul = params["include_soul"]?.jsonPrimitive?.booleanOrNull,
            includeRecentChats = params["include_recent_chats"]?.jsonPrimitive?.booleanOrNull,
        )
        // The engine's recursion guard checks `HeadlessConversations.isHeadless(parentChatId)`
        // — if the calling conversation is itself headless (cron / sub-agent / workflow /
        // external-automation) we refuse the dispatch. ToolInvocationContext propagation
        // (added 2026-05-07 stability pass) gives us the calling conversation id at tool-
        // construction time. Empty fallback is a no-knowledge sentinel — engine treats it
        // as "not in a headless run" which is correct for the legacy registration paths
        // that don't yet wire context (one-off / test).
        val parentAssistantId = callerContext.callerAssistantId.orEmpty()
        val parentChatId: String? = callerContext.callerConversationId
        when (val res = engine.dispatch(parentAssistantId, parentChatId, request)) {
            is SubAgentEngine.DispatchResult.Reject ->
                return@Tool errEnv(res.error, res.detail)
            is SubAgentEngine.DispatchResult.Ok ->
                listOf(UIMessagePart.Text(encodeRun(res.run).toString()))
        }
    },
)

fun subagentListTool(registry: SubAgentRegistry): Tool = Tool(
    name = "subagent_list",
    description = """
        List sub-agent runs visible to this assistant. Set active_only=true to omit
        terminal runs. Read-only.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("active_only", buildJsonObject { put("type", "boolean") })
            },
            required = emptyList(),
        )
    },
    execute = { args ->
        val activeOnly = args.jsonObject["active_only"]?.jsonPrimitive?.booleanOrNull ?: false
        val list = registry.list(activeOnly)
        val arr = buildJsonArray {
            list.forEach { addJsonObject {
                put("id", it.id)
                put("label", it.label)
                put("status", it.status.name)
                if (it.modelId != null) put("model_id", it.modelId)
                put("started_at_ms", it.startedAtMs)
                put("trip_count", it.tripCount)
            } }
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("runs", arr)
        }.toString()))
    },
)

fun subagentGetTool(registry: SubAgentRegistry): Tool = Tool(
    name = "subagent_get",
    description = "Fetch the full run record for a sub-agent by id. Read-only. Failed, timed-out, and cancelled runs include a partial result harvested from the worker conversation — inspect this before deciding to re-run the same task; the worker may have completed substantial work before the failure.".trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnv("invalid_id", "id is required")
        val run = registry.get(id)
            ?: return@Tool errEnv("unknown_id", "no sub-agent run with id $id")
        listOf(UIMessagePart.Text(encodeRun(run).toString()))
    },
)

fun subagentCancelTool(registry: SubAgentRegistry): Tool = Tool(
    name = "subagent_cancel",
    description = """
        Cancel a running sub-agent by id. Marks the run CANCELLED; safe to call on
        already-terminal runs (returns ok=false). Read-only from the user's perspective
        — no approval required.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnv("invalid_id", "id is required")
        val cancelled = registry.requestCancel(id)
        if (cancelled) {
            registry.update(id) { it.copy(status = SubAgentStatus.CANCELLED, finishedAtMs = System.currentTimeMillis()) }
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", cancelled)
            put("id", id)
        }.toString()))
    },
)

/**
 * Phase C — dispatch a batch of workers atomically. One approval covers the whole batch.
 * Pre-checks total (existing + batch) against caps. If partial=false (default), the
 * whole batch is rejected on cap overflow; if true, admits what fits.
 */
fun subagentDispatchBatchTool(
    engine: SubAgentEngine,
    callerContext: me.rerere.rikkahub.data.ai.tools.ToolInvocationContext =
        me.rerere.rikkahub.data.ai.tools.ToolInvocationContext.EMPTY,
): Tool = Tool(
    name = "subagent_dispatch_batch",
    description = """
        Dispatch multiple worker sub-agents in one call. Use for parallel decomposition —
        spawned workers run concurrently, then call subagent_wait_all to collect results.
        Each entry in the workers array accepts the same fields as subagent_dispatch
        (task, label, model_id, system_prompt, tools, run_in_background, timeout_seconds,
        max_trips, include_memory, include_soul, include_recent_chats). task is required.
        One approval prompt covers the entire batch. Returns run_ids for accepted workers
        and rejections for any that didn't fit the cap.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("workers", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "object") })
                })
                put("run_in_background", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("workers"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        if (callerContext.isHeadless &&
            callerContext.callerConversationId?.let { SubAgentConversationTracker.lookup(it) } == null
        ) {
            return@Tool errEnv("no_recursion",
                "batch dispatch is not allowed from inside a headless run (cron / workflow / external automation).")
        }
        val params = args.jsonObject
        val workersArr = params["workers"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?: return@Tool errEnv("invalid_workers", "workers array is required")
        val runInBackground = params["run_in_background"]?.jsonPrimitive?.booleanOrNull ?: false
        val parentAssistantId = callerContext.callerAssistantId.orEmpty()
        val parentChatId = callerContext.callerConversationId

        val results = mutableListOf<Pair<Int, Any>>()
        for ((index, entry) in workersArr.withIndex()) {
            val obj = runCatching { entry.jsonObject }.getOrNull()
                ?: return@Tool errEnv("invalid_worker", "workers[$index] is not an object")
            val task = obj["task"]?.jsonPrimitive?.contentOrNull
                ?: return@Tool errEnv("invalid_task", "workers[$index].task is required")
            val request = SubAgentRequest(
                task = task,
                modelId = obj["model_id"]?.jsonPrimitive?.contentOrNull,
                systemPrompt = obj["system_prompt"]?.jsonPrimitive?.contentOrNull,
                tools = obj["tools"]?.let { runCatching { it.jsonArray }.getOrNull() }
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull },
                runInBackground = runInBackground,
                timeoutSeconds = obj["timeout_seconds"]?.jsonPrimitive?.intOrNull
                    ?: SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
                maxTrips = obj["max_trips"]?.jsonPrimitive?.intOrNull
                    ?: SubAgentDefaults.DEFAULT_MAX_TRIPS,
                label = obj["label"]?.jsonPrimitive?.contentOrNull,
                includeMemory = obj["include_memory"]?.jsonPrimitive?.booleanOrNull,
                includeSoul = obj["include_soul"]?.jsonPrimitive?.booleanOrNull,
                includeRecentChats = obj["include_recent_chats"]?.jsonPrimitive?.booleanOrNull,
            )
            when (val res = engine.dispatch(parentAssistantId, parentChatId, request)) {
                is SubAgentEngine.DispatchResult.Reject ->
                    results.add(index to buildJsonObject {
                        put("error", res.error)
                        put("detail", res.detail)
                    })
                is SubAgentEngine.DispatchResult.Ok ->
                    results.add(index to buildJsonObject {
                        put("run_id", res.run.id)
                        put("label", res.run.label)
                        put("status", res.run.status.name)
                    })
            }
        }
        val accepted = results.count { it.second is kotlinx.serialization.json.JsonObject &&
            (it.second as kotlinx.serialization.json.JsonObject)["run_id"] != null }
        val rejected = results.size - accepted
        listOf(UIMessagePart.Text(buildJsonObject {
            put("accepted", accepted)
            put("rejected", rejected)
            put("results", buildJsonArray {
                results.forEach { (idx, data) ->
                    addJsonObject {
                        put("index", idx)
                        if (data is kotlinx.serialization.json.JsonObject) {
                            data.forEach { (k, v) -> put(k, v) }
                        }
                    }
                }
            })
        }.toString()))
    },
)

/**
 * Phase C — wait for multiple sub-agent runs to reach a terminal state. Blocks until all
 * are terminal (or timeout). Cheaper than polling subagent_get in a loop.
 */
fun subagentWaitAllTool(registry: SubAgentRegistry): Tool = Tool(
    name = "subagent_wait_all",
    description = """
        Wait for one or more sub-agent runs to finish. Blocks until all given run ids
        reach a terminal status (SUCCEEDED/FAILED/TIMED_OUT/CANCELLED) or the timeout
        expires. Returns the final status of each run, including partial results from
        failed/timed-out/cancelled workers — inspect these before re-running. Use after
        subagent_dispatch_batch or multiple subagent_dispatch calls.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("ids", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("timeout_seconds", buildJsonObject { put("type", "integer") })
            },
            required = listOf("ids"),
        )
    },
    needsApproval = { false },
    execute = { args ->
        val params = args.jsonObject
        val ids = params["ids"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: return@Tool errEnv("invalid_ids", "ids array is required")
        val timeoutSec = params["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 300
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        // Poll loop: check every 500ms if all runs are terminal
        while (true) {
            val allTerminal = ids.all { id ->
                val run = registry.get(id)
                run == null || run.status.let { s ->
                    s != SubAgentStatus.RUNNING && s != SubAgentStatus.PENDING
                }
            }
            if (allTerminal) break
            if (System.currentTimeMillis() >= deadline) break
            Thread.sleep(500)
        }
        val arr = buildJsonArray {
            ids.forEach { id ->
                val run = registry.get(id)
                addJsonObject {
                    put("id", id)
                    if (run != null) {
                        put("status", run.status.name)
                        if (run.result != null) put("result", run.result)
                        if (run.error != null) put("error", run.error)
                        put("tokens_in", run.tokensIn)
                        put("tokens_out", run.tokensOut)
                        put("trip_count", run.tripCount)
                    } else {
                        put("status", "unknown")
                    }
                }
            }
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("runs", arr)
            put("all_terminal", ids.all { id ->
                val run = registry.get(id)
                run == null || run.status.let { s ->
                    s != SubAgentStatus.RUNNING && s != SubAgentStatus.PENDING
                }
            })
        }.toString()))
    },
)

/**
 * Phase C — cancel all sub-agent runs in a subtree. Takes the root orchestrator run id
 * and cancels every active descendant plus the root itself.
 */
fun subagentCancelSubtreeTool(registry: SubAgentRegistry): Tool = Tool(
    name = "subagent_cancel_subtree",
    description = """
        Cancel all sub-agent runs in a subtree. Pass the orchestrator run id (the root
        worker that spawned the subtree). Cancels the root and all its descendants in
        one call. Useful for hard-killing an orchestration that's gone wrong.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("orchestrator_run_id", buildJsonObject { put("type", "string") })
            },
            required = listOf("orchestrator_run_id"),
        )
    },
    needsApproval = { false },
    execute = { args ->
        val id = args.jsonObject["orchestrator_run_id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnv("invalid_id", "orchestrator_run_id is required")
        val cancelled = registry.cancelSubtree(id)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("cancelled", cancelled)
            put("orchestrator_run_id", id)
        }.toString()))
    },
)

/**
 * Phase D — observability tool: show the full subtree tree + aggregate token usage.
 * Takes the root orchestrator run id and returns a flat list of all runs in the subtree
 * with their depth, status, tokens, and trip count, plus aggregate totals.
 */
fun subagentSubtreeStatusTool(registry: SubAgentRegistry): Tool = Tool(
    name = "subagent_subtree_status",
    description = """
        Show the full status of a sub-agent subtree. Pass the root orchestrator run id.
        Returns every run in the subtree (root + all descendants) with depth, status,
        token usage, and trip count, plus aggregate totals for the whole subtree.
        Use this to inspect an ongoing or completed orchestration.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("orchestrator_run_id", buildJsonObject { put("type", "string") })
            },
            required = listOf("orchestrator_run_id"),
        )
    },
    needsApproval = { false },
    execute = { args ->
        val id = args.jsonObject["orchestrator_run_id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnv("invalid_id", "orchestrator_run_id is required")
        val runs = registry.getSubtree(id)
        if (runs.isEmpty()) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "no_runs_found")
                put("detail", "no sub-agent runs found for orchestrator_run_id '$id'")
            }.toString()))
        }
        val (totalIn, totalOut) = registry.subtreeTokenSum(id)
        val runArr = buildJsonArray {
            runs.sortedBy { it.depth }.forEach { run ->
                addJsonObject {
                    put("run_id", run.id)
                    put("label", run.label)
                    put("depth", run.depth)
                    put("status", run.status.name)
                    put("tokens_in", run.tokensIn)
                    put("tokens_out", run.tokensOut)
                    put("trip_count", run.tripCount)
                    if (run.modelId != null) put("model_id", run.modelId)
                    if (run.fallbackModelUsed) put("fallback_model_used", true)
                    if (run.subtreeTokenWarning) put("subtree_token_warning", true)
                    if (run.subtreeTokenCancelled) put("subtree_token_cancelled", true)
                }
            }
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("orchestrator_run_id", id)
            put("run_count", runs.size)
            put("aggregate_tokens_in", totalIn)
            put("aggregate_tokens_out", totalOut)
            put("aggregate_tokens_total", totalIn + totalOut)
            put("active_count", runs.count {
                it.status == SubAgentStatus.RUNNING || it.status == SubAgentStatus.PENDING
            })
            put("terminal_count", runs.count {
                it.status != SubAgentStatus.RUNNING && it.status != SubAgentStatus.PENDING
            })
            put("runs", runArr)
        }.toString()))
    },
)

/**
 * Phase D/E — model discovery tool. Lets the LLM search for models across ALL
 * configured providers by name, returning the UUID it needs for subagent_dispatch.
 * Avoids bloating the system prompt with a full model list every turn.
 */
fun subagentListModelsTool(
    settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore,
): Tool = Tool(
    name = "subagent_list_models",
    description = """
        Search for available chat models across ALL configured providers. Returns
        matching models with their UUID, display name, modelId (API name), and
        provider name. Use this to find the model_id UUID needed for subagent_dispatch
        or subagent_dispatch_batch. Search is case-insensitive and matches both
        display name and modelId. Omit the query to list ALL chat models on ALL
        providers. Only enabled providers are included.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject { put("type", "string") })
            },
            required = listOf(),
        )
    },
    needsApproval = { false },
    execute = { args ->
        val params = args.jsonObject
        val query = params["query"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim() ?: ""
        val settings = settingsStore.settingsFlow.value
        val results = buildJsonArray {
            settings.providers.filter { it.enabled }.forEach { provider ->
                provider.models.filter { it.type == me.rerere.ai.provider.ModelType.CHAT }
                    .forEach { model ->
                        val displayName = model.displayName.lowercase()
                        val apiName = model.modelId.lowercase()
                        if (query.isBlank() || displayName.contains(query) || apiName.contains(query)) {
                            addJsonObject {
                                put("id", model.id.toString())
                                put("display_name", model.displayName.ifBlank { model.modelId })
                                put("model_id", model.modelId)
                                put("provider", provider.name)
                                if (model.contextLength != null) put("context_length", model.contextLength)
                            }
                        }
                    }
            }
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("query", query)
            put("count", results.size)
            put("models", results)
            if (results.isNotEmpty()) {
                put("hint", "Pass one of the above 'id' values as model_id in subagent_dispatch.")
            }
        }.toString()))
    },
)
