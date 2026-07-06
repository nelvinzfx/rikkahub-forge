# Orchestrator Mode — Design Doc

**Status:** DRAFT — do not implement yet.
**Owner:** za + ra
**Created:** 2026-07-06

Extends the existing Phase 11 Sub-agents feature into a full orchestration
primitive: the agent can decompose a user task into worker sub-agents, run
them in parallel, and synthesise. Also fixes the dead fields already sitting
in `Assistant`/`SubAgentRequest` (`subAgentModelId`, `subAgentSystemPrompt`,
`request.modelId`, `request.systemPrompt` — all currently no-ops).

---

## 1. Naming

Feature: **Orchestrator Mode**.

Roles:
- **Orchestrator** — the top-level agent when Orchestrator Mode is on.
  Decides whether to split work; spawns/joins workers; synthesises the final
  reply.
- **Worker** — a sub-agent spawned by the orchestrator to do one focused
  subtask.
- **Subtree** — the orchestrator run + all of its workers (transitive).

---

## 2. Design decisions (locked)

| # | Decision | Value |
|---|---|---|
| 1 | Max sub-agent depth | **1** (default). Cap 2, disabled by default. |
| 2 | Auto-approve orchestrator children | **Toggle**, default OFF. |
| 3 | Subtree token hard cap | **Unlimited by default**, prominent warning in UI. |
| 4 | `model_id` semantics | **Strict** validation. If runtime fails (e.g. provider disabled/429), fall back to assistant default with a warning entry in the run record. |

---

## 3. Goals

- Let the orchestrator decide **per turn** whether to split into workers or
  answer inline. Not every task needs decomposition.
- Give per-worker knobs: model, system prompt, memory inclusion, soul
  inclusion, tools, timeouts.
- Expose these knobs from **both surfaces**:
  - LLM per-call via `subagent_dispatch` args (agent has its own control).
  - UI defaults per assistant (user sets orchestration policy).
- Wire the existing dead fields (`subAgentModelId`, `subAgentSystemPrompt`)
  as the resolution fallback when the LLM doesn't specify per-call.
- Keep the existing "just spawn one sub-agent" flow working — Orchestrator
  Mode is an assistant-level toggle, NOT a global replacement.

## 4. Non-goals

- Nested orchestration (grandchild sub-agents). Depth cap = 1 by default.
- Cross-orchestrator messaging (workers talking to each other).
- Persistent orchestrator state across process death beyond the existing
  Phase 24 ledger (individual runs survive; live orchestration doesn't).
- Full DAG/graph workflow — this is fork-join, not arbitrary topology.

---

## 5. Behavioural model

### 5.1 Orchestrator Mode OFF (existing behaviour)

Sub-agents work exactly as today. `subagent_dispatch` is a tool the assistant
CAN call when it decides to; nothing forces decomposition.

### 5.2 Orchestrator Mode ON

Assistant's system prompt gets an **Orchestrator Preamble** injected. The
preamble is intentionally short, dense, and prescriptive — it teaches the
agent the strategy, not just the mechanics. Current draft:

```
You are in Orchestrator Mode. You can decompose tasks into parallel worker
sub-agents, or do them yourself.

DECIDE FIRST: If a task is one lookup, one edit, or something you can answer
in a few tool calls — do it inline. Workers cost tokens and coordination;
don't spawn them for work you can finish faster alone. Split when there are
genuinely independent threads (parallel research, multi-file changes,
multi-target probing).

WRITE GOOD WORKER TASKS: Workers don't see this conversation. Each task must
be self-contained — include any context the worker needs to act without
asking you back. One clear deliverable per worker. Bounded scope: "search X,
return Y" not "explore the codebase".

RIGHT GRANULARITY: Too fine = overhead burns more tokens than it saves. Too
coarse = no parallelism, might as well do it yourself. Aim for 2-6 workers,
each doing a chunk that would take you 3-10 tool calls alone.

HANDLE FAILURE: If a worker times out or fails, use what you got. Retry only
if the result is essential and the failure looks transient. Don't block the
whole turn on one dead worker.

SYNTHESISE: Your final reply is not a paste of worker outputs. Read their
summaries, extract what matters, write one coherent answer to the user.
The user sees you, not your workers.
```

The orchestrator keeps ALL existing tools — it can act itself for the parts
that don't decompose, and delegate the parts that do. Workers inherit the
agent-core system prompt (persona, memory format, safety rules) regardless
of the `include_soul` toggle — see §8.

### 5.3 Explicit override (both surfaces)

- User can force inline behaviour on a given turn with a `no orchestrate`
  hint (documented; agent respects it).
- User can force decomposition with `orchestrate this` (documented).
- Agent's judgement remains primary; hints override.

---

## 6. Schema changes

### 6.1 `Assistant` (additive, all defaults preserve current behaviour)

```kotlin
// Orchestrator Mode (Phase 30)
val orchestratorMode: Boolean = false                        // master toggle
val orchestratorAllowAutoApprove: Boolean = false            // decision #2
val subAgentMaxDepth: Int = 1                                // decision #1
val subAgentSubtreeTokenHardCap: Long? = null                // decision #3
// Per-worker defaults (used when LLM omits per-call args)
val subAgentDefaultIncludeMemory: Boolean = true
val subAgentDefaultIncludeSoul: Boolean = true               // parent assistant prompt
val subAgentDefaultIncludeRecentChats: Boolean = true
// Existing but dead — will be wired in P1
// val subAgentModelId: Uuid? = null
// val subAgentSystemPrompt: String = ""
// val maxConcurrentSubAgents: Int = 3
```

### 6.2 `Conversation` (additive, minimal)

```kotlin
// Per-conversation overrides (Phase 30) — nullable/default so
// existing conversations keep resolving to assistant defaults.
val chatModelId: Uuid? = null                                // wires subagent model
val suppressMemory: Boolean = false                          // wires include_memory=false
val suppressAssistantPrompt: Boolean = false                 // wires include_soul=false
val suppressRecentChats: Boolean = false                     // wires include_recent_chats=false
// customSystemPrompt already exists — reused for per-worker system prompt
```

### 6.3 `SubAgentRun` (additive)

```kotlin
val depth: Int = 0                                           // top-level = 0
val orchestratorRunId: String? = null                        // null if top-level
val subtreeTokensIn: Long = 0                                // rolls up from children
val subtreeTokensOut: Long = 0
val fallbackModelUsed: Boolean = false                       // decision #4 diag
val fallbackReason: String? = null
```

### 6.4 `SubAgentRequest` (additive)

```kotlin
val includeMemory: Boolean? = null                           // null → use assistant default
val includeSoul: Boolean? = null
val includeRecentChats: Boolean? = null
val maxChildSubAgents: Int? = null                           // for this worker if it's an orchestrator
```

---

## 7. Model resolution pipeline

Order (first non-null wins):

```
1. SubAgentRequest.modelId              (LLM per-call)
2. Assistant.subAgentModelId            (assistant default for sub-agents)
3. Assistant.chatModelId                (assistant default overall)
4. Settings.chatModelId                 (global default)
```

**Strict validation** at dispatch time:
- If `SubAgentRequest.modelId` is set but doesn't resolve to a model → return
  `DispatchResult.Reject("invalid_model_id", "…")`. LLM sees clear error.

**Runtime fallback** (decision #4 tail):
- If the resolved model fails at generation (provider disabled, 429 after
  retries, model gone) → engine catches once, records
  `fallbackModelUsed = true`, `fallbackReason = "…"`, retries with the
  next-in-line resolution. If that also fails, propagate the error.
- User-visible: run record shows the fallback happened, so this doesn't hide
  problems.

---

## 8. System prompt composition

The existing `SystemPromptBuilder` assembles: assistant persona → memory →
recent chats → tool prompts → addendum. For workers we gate each section:

| Section | Gated by |
|---|---|
| RikkaHub agent-core (persona, memory format, WAL doctrine) | **Always present.** Not gated — this is safety + behavior baseline. |
| Assistant custom `systemPrompt` (the user-written persona) | `!suppressAssistantPrompt` AND `assistant.systemPrompt.isNotBlank()` |
| Memory | `assistant.enableMemory && !suppressMemory` |
| Recent chats | `assistant.enableRecentChatsInject && !suppressRecentChats` (needs to check what current flag is) |
| Worker-specific prompt | `conversation.customSystemPrompt` (already reused) |
| Orchestrator preamble | Only injected on the ORCHESTRATOR conversation, never on worker conversations |

**`include_soul=false` semantics (resolved):** agent-core ALWAYS stays on workers (soul, memory format, WAL doctrine — the safety baseline). `include_soul=false` / `suppressAssistantPrompt=true` only suppresses the user's *custom* `Assistant.systemPrompt` field. Workers never run truly blank.

Worker default system prompt (when none of the above is set) stays as
`SubAgentDefaults.DEFAULT_SYSTEM_PROMPT`.

---

## 9. New / updated tools

### 9.1 Existing (wire the dead args)

`subagent_dispatch` gains real support for:
- `model_id` — strict validated
- `system_prompt` — wired to `conversation.customSystemPrompt`
- `tools` — subset filter of parent tool set (still capped by
  `assistant.localTools`)
- `include_memory`, `include_soul`, `include_recent_chats` (new)
- `max_child_subagents` (new; only meaningful if worker itself uses
  orchestrator mode, which by default it doesn't at depth 1)

### 9.2 `subagent_dispatch_batch`

```
subagent_dispatch_batch(
  workers: [
    { task, label?, model_id?, system_prompt?, tools?,
      include_memory?, include_soul?, include_recent_chats?,
      timeout_seconds?, max_trips? },
    …
  ],
  run_in_background: bool = false
)
→ { run_ids: [...], accepted: N, rejected: [{index, error, detail}] }
```

- Atomic-ish concurrency check: pre-check total (existing running + batch
  size) against caps. If it would exceed, reject the whole batch UNLESS
  `partial=true` — then admit what fits and report the rest.
- Approval: one approval prompt for the whole batch (per decision #2, or if
  user hits allow-once).
- If `run_in_background=false` (default in orchestrator use), the tool
  returns AFTER all workers reach terminal state (or timeout / subtree cap
  hit).

### 9.3 `subagent_wait_all`

```
subagent_wait_all(ids: [string], timeout_seconds?: int = 300)
→ { runs: [encodeRun(...)], all_terminal: bool }
```

Cheap wait primitive so the orchestrator doesn't burn trips polling
`subagent_get`.

### 9.4 `subagent_list_models`

```
subagent_list_models()
→ { models: [{ id, display_name, provider_name, provider_enabled,
              is_assistant_default_subagent }], … }
```

Read-only. Lets the agent pick a model per-worker without user asking. Only
returns models whose provider is enabled.

### 9.5 `subagent_get_config`

```
subagent_get_config()
→ {
    orchestrator_mode: bool,
    max_depth: int,
    per_assistant_cap: int,
    global_cap: int,
    subtree_token_hard_cap: long|null,
    defaults: {
      model_id, system_prompt, include_memory, include_soul,
      include_recent_chats, allow_auto_approve
    }
  }
```

Lets the agent read the current policy before deciding whether/how to
orchestrate.

### 9.6 `subagent_cancel_subtree`

```
subagent_cancel_subtree(orchestrator_run_id: string)
→ { cancelled: int }
```

Cancels the whole subtree in one call. Useful for hard-kill from the agent
side (rare — usually user triggers via UI).

---

## 10. UI surface

### 10.1 Assistant detail page → new "Orchestrator" section

Order in the section (top to bottom):

1. **Toggle** — Enable Orchestrator Mode
   - Subtitle: "Let this assistant split tasks into parallel workers"
2. Below only visible if toggle is ON:
3. **Dropdown** — Default worker model
   - Options: "Inherit from assistant" (default) + list of enabled models
4. **Text area** — Default worker system prompt
   - Placeholder: "Empty = focused sub-agent prompt (default)"
5. **Slider** — Max concurrent workers per turn (1–8, default 3)
6. **Slider** — Max sub-agent depth (0–2, default 1)
   - Warning below if user picks 2: "Grandchild workers can multiply
     unpredictably. Ensure hard token cap is set."
7. **Toggle** — Workers inherit memory (default ON)
8. **Toggle** — Workers inherit soul/persona (default ON)
9. **Toggle** — Workers inherit recent chats (default ON)
10. **Toggle** — Allow orchestrator to auto-approve worker dispatches
    (default OFF)
    - Warning below: "One approval covers the whole batch. HARDLINE still
      applies to individual tool calls inside workers."
11. **Number input** — Subtree token hard cap (empty = unlimited)
    - Below the field, in an amber-tinted box:
      "⚠ Unlimited by default. A runaway orchestrator can burn thousands
      of tokens per turn. Consider capping at 100 000–500 000 for
      unattended runs."

### 10.2 Chat surface — orchestration chips

Existing sub-agent chip row extends to show a tree:

```
[Orchestrator: analyze codebase — RUNNING]
  ├─ [worker: read manifest — SUCCEEDED]
  ├─ [worker: scan dex — RUNNING]
  └─ [worker: check resources — RUNNING]
```

- Tapping the orchestrator chip opens the subtree view with per-worker
  logs and a "Cancel subtree" button.
- Chip row paginates at 10 visible; overflow into a "+N more" pill.

### 10.3 Sub-agent detail page

Add:
- Depth badge (0/1/2)
- Orchestrator parent link (if `orchestratorRunId != null`)
- Fallback-model indicator (if `fallbackModelUsed == true`) with reason
- Subtree token totals (if this is an orchestrator)

---

## 11. Safety layers

Ordered by strictness. All are active simultaneously.

### 11.1 Depth cap (decision #1)

Enforced in `SubAgentEngine.dispatch`:
```
if (parentRun.depth + 1 > assistant.subAgentMaxDepth)
  reject("depth_cap_reached", "…")
```
Default 1. User can raise to 2. Never higher (hard cap in code).

### 11.2 Concurrency caps (4 layers)

| Layer | Where enforced | Default |
|---|---|---|
| Global | `SubAgentDefaults.GLOBAL_CONCURRENCY_CAP` | 16 |
| Per-assistant | `Assistant.maxConcurrentSubAgents` | 3 (max 8) |
| Per-orchestrator subtree | new, `SubAgentDefaults.MAX_WORKERS_PER_ORCHESTRATOR` | 10 |
| Per-batch (dispatch_batch) | request-time | ≤ per-assistant cap |

Over-cap: dispatch rejects with clear error; agent can back off & retry.

### 11.3 Token subtree cap (decision #3)

If `assistant.subAgentSubtreeTokenHardCap` is non-null:
- Engine accumulates `subtreeTokensIn + subtreeTokensOut` in the orchestrator
  run on every worker terminal transition.
- If cap exceeded: `cancelSubtree(orchestratorId)`, mark orchestrator FAILED
  with `subtree_budget_exceeded`.
- Default `null` (unlimited) — user got the warning.

### 11.4 Timeout inheritance

Worker `timeoutSeconds` is clamped to `min(worker_request, orchestrator_remaining)`.
Worker cannot outlive orchestrator.

### 11.5 Approval mode (decision #2)

Two-mode:
- **Strict (default):** every worker dispatch needs approval. `dispatch_batch`
  gets one approval prompt showing all N tasks; user accepts/rejects whole
  batch.
- **Auto-approve (toggle ON):** orchestrator can spawn workers within its
  subtree without per-worker prompts. **HARDLINE is not bypassed** — every
  tool call inside every worker still goes through HARDLINE and per-tool
  approval flags. The toggle only lifts the "dispatching a worker itself"
  prompt.

### 11.6 Cancel cascade

`SubAgentRegistry.cancelSubtree(rootId)`:
1. Walk `_runs.value` for all runs with `orchestratorRunId == rootId`
   (recursively if depth > 1 is ever allowed).
2. Call `requestCancel(id)` on each descendant, then on root.
3. Emit registry update once, not per-cancel.

Hooked into:
- `/stop` (Telegram) — existing `cancelAllForParent` extended to walk subtree.
- In-app cancel button on orchestrator chip.
- Subtree token cap trip.
- Parent orchestrator failure.

### 11.7 Rate-limit backpressure

`SubAgentEngine` maintains a per-provider "cooldown" map. If a worker gets
a 429 with `retry-after`, engine records the cooldown and refuses to
schedule additional workers targeting that provider until the timer
elapses. Deferred workers wait in PENDING; if orchestrator times out,
they're cancelled without ever calling the API.

### 11.8 Dispatcher isolation

Workers run on `Dispatchers.IO` (existing). We add a `Semaphore(maxWorkers)`
inside the engine so even if the registry lets many through, actual thread
usage is bounded — protects `Dispatchers.IO` from starvation.

---

## 12. Failure modes & recovery

| Failure | Detection | Action |
|---|---|---|
| Model doesn't resolve at dispatch | pre-check in `dispatch()` | Reject with `invalid_model_id`. |
| Model fails at runtime (429/disabled/gone) | catch in `executeRun` retry loop | Fallback per decision #4; mark `fallbackModelUsed`. |
| Worker exceeds its timeout | existing `withTimeoutOrNull` | Existing TIMED_OUT path; orchestrator sees it via `subagent_wait_all`. |
| Orchestrator exceeds its own timeout | existing | All workers cancelled via subtree cascade. |
| Subtree token cap hit | accumulator check on each worker terminal | Cancel subtree, orchestrator FAILED. |
| Process death mid-orchestration | `AgentRunBootRecovery` (Phase 24) | All rows flipped to `process_lost`. UI shows this. Nothing auto-resumes. |
| Cancel from user during orchestration | existing `/stop` extended | Subtree cascade cancel. |
| Circular / duplicate dispatch | depth cap + registry id uniqueness | Depth cap catches nesting; registry uniqueness catches dup ids. |

---

## 13. Phased implementation

Each phase is one PR, one build via GitHub Actions, one on-device test.
NO code until you approve.

### Phase A — wire dead fields (foundation)

**Scope:** Model + system prompt override actually works.
- `Conversation.chatModelId` field.
- `ChatService.handleMessageComplete` model resolution walks the new order.
- `SubAgentEngine.executeRun` sets `chatModelId` + `customSystemPrompt` on
  the worker conversation from `SubAgentRequest` / `Assistant` fallback.
- Strict validation of `model_id` at dispatch (decision #4 head).
- Runtime fallback with `fallbackModelUsed` flag (decision #4 tail).
- **Token telemetry:** wire `SubAgentRun.tokensIn/tokensOut` from
  `GenerationHandler` usage data. Currently these are always 0 — populate
  them so Phase D's subtree cap has real numbers to add up. Read the usage
  object from the generation flow and write to the registry on terminal.

**Test:** Dispatch a sub-agent with a specific `model_id`, verify via logs +
  telemetry that generation used that model. Dispatch with garbage id, verify
  `invalid_model_id`. Disable the provider mid-run, verify fallback + flag.
  Dispatch a worker, verify `tokensIn/tokensOut` on the run record are
  non-zero after completion (proves telemetry is wired).

**UI:** Add dropdown "Default sub-agent model" + textarea "Default
sub-agent system prompt" in existing Assistant detail Sub-agents section.

### Phase B — memory/soul/recent-chats gating

**Scope:** Per-conversation suppress flags + new `include_*` args.
- `Conversation.suppressMemory`, `suppressAssistantPrompt`,
  `suppressRecentChats` fields.
- `GenerationHandler` reads these and skips the corresponding section.
- `SubAgentRequest.includeMemory/includeSoul/includeRecentChats` args
  wired to conversation flags.
- Assistant defaults in UI (three toggles).

**Test:** Dispatch with `include_memory=false`, verify system prompt in
generation log has no `<memories>` block.

### Phase C — orchestrator mode + depth + batch

**Scope:** The actual new feature.
- `Assistant.orchestratorMode`, `subAgentMaxDepth`,
  `orchestratorAllowAutoApprove` fields.
- `SubAgentRun.depth`, `orchestratorRunId` fields.
- Orchestrator preamble injection in `SystemPromptBuilder` when
  `assistant.orchestratorMode == true` (and current conversation isn't
  itself a worker).
- Depth cap check in `SubAgentEngine.dispatch`.
- Recursion guard change: `depth < maxDepth` instead of hard-ban headless.
- `subagent_dispatch_batch` + `subagent_wait_all` +
  `subagent_cancel_subtree` tools.
- Subtree cancel cascade in `SubAgentRegistry`.

**Test:** Enable Orchestrator Mode on test assistant. Ask a multi-part
question. Verify: agent decides split, spawns N workers, each with depth=1,
`orchestratorRunId` pointing at parent run. Ask a simple question, verify
agent answers inline (no workers spawned). Set depth=1, verify workers
can't recursively dispatch.

### Phase D — cost + rate-limit + observability

**Scope:** Safety guardrails. Token telemetry plumbing itself is already
  done in Phase A; this phase builds the cap *logic* on top of it.
- `Assistant.subAgentSubtreeTokenHardCap` field + UI number input +
  warning box.
- Subtree token accumulator + cap trigger.
- Per-provider cooldown map for 429 backpressure.
- `Semaphore` on worker execution.
- `subagent_list_models` + `subagent_get_config` introspection tools.
- Fallback-model indicator in run detail page.

**Test:** Set cap = 10 000 tokens, dispatch orchestrator that would exceed,
verify subtree cancel + `subtree_budget_exceeded`. Simulate 429 (or mock
provider), verify cooldown suppresses new dispatches.

### Phase E — UI polish

**Scope:** UX for the chip row & subtree view.
- Chip row tree rendering for orchestrator + workers.
- Subtree detail page with per-worker logs.
- "Cancel subtree" button.
- Chip row pagination at 10 visible.

**Test:** Manual UX pass. Spawn 8 workers, verify tree renders. Cancel
mid-run, verify all workers cancel.

---

## 14. Open questions

All resolved (2026-07-06):

1. **Orchestrator preamble location** — Permanent in system prompt (agent
   caches it). Resolved by design.
2. **`include_soul=false` semantics** — RESOLVED: agent-core ALWAYS stays on
   workers (persona, memory format, WAL doctrine). `include_soul=false` /
   `suppressAssistantPrompt=true` only suppresses the user's custom
   `Assistant.systemPrompt`. Workers never run truly blank.
3. **Worker chat history** — Punted to post-launch. Workers start with just
   the task text.
4. **Cost telemetry accuracy** — RESOLVED: wire `SubAgentRun.tokensIn/Out`
   from `GenerationHandler` in **Phase A** as a prerequisite. Phase D builds
   cap logic on top of working telemetry.
5. **Streaming synthesis** — Wait then synthesise (simpler). Iterate later.

---

## 15. Ready-to-implement checklist

Before Phase A starts:

- [x] za signs off on decisions #1–4 (done — locked in §2)
- [x] za answers open questions §14.2 (include_soul semantics) — **agent-core always stays, only custom Assistant.systemPrompt suppressed**
- [x] za answers open question §14.4 (token telemetry timing) — **Phase A (prerequisite)**
- [x] Token telemetry added to Phase A scope
- [ ] Design doc merged as-is or with amendments
- [ ] GitHub Actions build slot free (no other RikkaHub PR mid-flight)
- [ ] za says "implement now"

Once these are green, Phase A is ~1 build + 1 test cycle. Rest follow
sequentially.
