<div align="center">

<img src="docs/icon.png" width="96" height="96" alt="RikkaHub Forge" style="border-radius: 24px" />

# RikkaHub Forge

A fork of [RikkaHub](https://github.com/rikkahub/rikkahub) focused on agentic reliability: native Termux integration, hardened sub-agent orchestration, long-term memory, a built-in code editor, and chat UX polish.

<p>
  <a href="https://github.com/nelvinzfx/rikkahub-forge/releases"><img src="https://img.shields.io/github/v/release/nelvinzfx/rikkahub-forge?include_prereleases&style=flat-square&label=release&color=blue" alt="Release" /></a>
  <a href="https://github.com/nelvinzfx/rikkahub-forge/releases/latest"><img src="https://img.shields.io/github/downloads/nelvinzfx/rikkahub-forge/latest/total?style=flat-square&color=brightgreen" alt="Downloads" /></a>
  <img src="https://img.shields.io/badge/platform-Android%208%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8+" />
  <img src="https://img.shields.io/badge/arch-arm64--v8a_%C2%B7_x86__64_%C2%B7_universal-orange?style=flat-square" alt="arm64-v8a, x86_64, universal" />
</p>

<a href="https://github.com/nelvinzfx/rikkahub-forge/releases/latest"><strong>Download</strong></a> ·
<a href="#whats-different-from-upstream"><strong>What's different</strong></a> ·
<a href="#building"><strong>Build</strong></a>

</div>

---

## What's different from upstream

This fork tracks upstream and adds five major systems on top: native Termux tooling, a hardened sub-agent engine, long-term memory with recall, pi-style context management, and an experimental code editor. Plus a long list of chat UX and reliability work.

### Native Termux integration

- **Full shell lifecycle**: capture mode with bounded output, detached background mode that survives the caller, and timeout cleanup that kills the whole process tree without touching intended survivors.
- **Interactive sessions**: persistent tmux-backed terminals with send/read/wait-for-text, for REPLs, SSH, and anything that prompts.
- **Atomic file tools**: SHA-guarded paged readers, staged atomic writes, and transactional multi-file edits with exact-match spans and unified diffs.
- **Mutation previews in chat**: file writes/edits render as live diff cards with fair space distribution and preserved detail on large changes.

### Sub-agent orchestration

- **Background workers**: dispatch independent workers with per-call model, system prompt, reasoning level, tool snapshot, and timeout (default 600s, 20 normal trips + a reserved no-tools wrap-up).
- **Orchestrator mode**: workers can dispatch their own workers, with depth caps, per-assistant and global concurrency caps, batch dispatch, and `dispatch_continue` to resume a failed worker's conversation.
- **Cost controls**: per-run token telemetry, subtree token caps with 80% warnings, and rate limiting.
- **Lifecycle hardening**: linearizable parent stop epochs, quiescence fencing, pre-start cancellation handles, and correct terminal classification (a user stop inside a worker chat publishes CANCELLED, never SUCCEEDED).
- **Partial-result harvest**: failed, timed-out, and cancelled workers still return whatever work they completed.
- **Clean UI**: worker chats are hidden from the conversation list and open through the sub-agent chip row; `subagent_report_progress` streams live status.

### Memory, recall & context

- **Searchable memory bank**: tiered core/bank memories with semantic tags, searchable and editable by the agent itself.
- **Conversation recall**: always on for every assistant — the agent can search past conversations and read their full contents; deep links open a specific chat directly.
- **Auto-compaction**: pi-style checkpoints compress old history when the context window fills, with token-based keep windows, a pre-generation budget gate, and turn continuation after compression.
- **Context gauge**: live context-window usage in the chat top bar with a flowing liquid animation, fed by provider `context_window` metadata.
- **Per-conversation drafts**: unsent messages survive leaving the chat.

### Code editor (beta)

- **Real editor surface**: [sora-editor](https://github.com/Rosemoe/sora-editor) with tabs, per-tab undo history, JetBrains Mono, and TextMate syntax highlighting (8 grammars, dark/light themes follow the app).
- **Right-side file drawer**: nvim-tree style tree over SAF with a persisted folder grant, lazy per-directory loading, natural sort (file2 before file10), and a hidden-files toggle. Opening a file never swaps views.
- **File icons**: a 22KB Nerd Font subset tinted from the theme, mapped by exact filename, then extension, then fallback.
- **Editing tools**: in-file search with match counter, go to line, undo/redo, word wrap, dirty-tab guards, and save back through SAF.
- Gated behind Settings > Code Editor (Beta) and still experimental.

### Chat & tool-call UX

- **Outline param tree**: tool arguments and results render as a collapsible outline pill with canvas-drawn connectors; long-press copies any value.
- **Cascade collapse**: a finished tool step folds the moment a successor step appears; the tail folds at generation end.
- **Swipe multi-select**: swipe right in the drawer to select conversations; select-all and batch delete included.
- **Theming**: dedicated Chat Colors page with hex/rgb/rgba input, custom app-wide font, and an Outline Blocks toggle for code/table structure.
- **Streaming performance**: 75ms chunk conflation, cached markdown ASTs, plain-text reasoning while thinking streams, and provider SSE backpressure.

### Reliability fixes (selected)

- **Cancel-safe persistence**: cancelling mid-generation (even mid tool call) persists the partial assistant turn; it survives app restarts, and interrupted auto-approved tools are tombstoned instead of silently re-run.
- **Independent tool timeouts**: every tool call gets its own budget (default 30 min, configurable 1-120), no shared turn-wide wall clock.
- **Parallel-conversation guard**: switching between two generating conversations no longer reverts turns via stale DB hydration.
- **Streaming correctness**: parallel tool calls stay keyed by wire index and result ordering is preserved.
- **Stop-button stuck chat**: stopping during tool calls no longer wedges the conversation.
- **MCP schema fidelity**: nested JSON schema definitions survive namespacing.
- **Manual migrations**: hand-written 26>27>28 Room migrations where auto-migration schemas were missing.

### Build & CI

- **arm64-only release builds** via GitHub Actions: Compile Check on every push, Release Build (arm64) on dispatch (pinned to an exact commit SHA).
- **Signing via CI secret**: no keystore in the repo; v2 signer certificate is parsed and verified.
- **JDK 21**, Kotlin 2.4.0.
- Releases follow upstream versioning with an agent suffix: `2.3.1-agent.N`.

---

## Base features

Everything from upstream RikkaHub is included: 80+ device tools, Telegram bot, in-app browser, AI-authored workflows, scheduled jobs, SSH, file manager, music player, voice transcription, sub-agents, MCP server support, skills, and more. See the [upstream README](https://github.com/rikkahub/rikkahub) for the full feature list.

---

## Building

```bash
# Debug compile check (CI runs this on push/PR)
./gradlew compileDebugKotlin

# Release build (CI only; needs signing key in KEY_BASE64 secret)
# Trigger via GitHub Actions: Release Build (arm64) workflow
```

Builds target arm64-v8a only. To build locally you need JDK 21 and the submodules.

---

## Credits

Stands on the shoulders of giants:

| Project | Role |
|---|---|
| [RikkaHub](https://github.com/rikkahub/rikkahub) | The upstream chat client this forks |
| [sora-editor](https://github.com/Rosemoe/sora-editor) | Code editor component (LGPL-2.1+) |
| [Nerd Fonts](https://www.nerdfonts.com/) | File icon glyphs (subset) |
| [cron-utils](https://github.com/jmrozanec/cron-utils) | Cron parser for the scheduler |
| [whisper.cpp](https://github.com/ggml-org/whisper.cpp) | On-device speech-to-text via Termux |
| [Termux](https://github.com/termux/termux-app) | Shell + package manager |
| [JSch (mwiede fork)](https://github.com/mwiede/jsch) | Native SSH client |
| [FlorisBoard](https://github.com/florisboard/florisboard) | Base for the companion agent-keyboard |

This fork is unaffiliated with upstream RikkaHub maintainers. All credit for the underlying chat client, provider abstraction, and UI design goes to the upstream team.

---

## License

Same as upstream RikkaHub.
