<div align="center">

<img src="docs/icon.png" width="96" height="96" alt="RikkaHub Forge" style="border-radius: 24px" />

# RikkaHub Forge

A fork of [RikkaHub](https://github.com/rikkahub/rikkahub) focused on tool-call UI polish and build hardening.

<p>
  <a href="https://github.com/nelvinzfx/rikkahub-forge/releases"><img src="https://img.shields.io/github/v/release/nelvinzfx/rikkahub-forge?include_prereleases&style=flat-square&label=release&color=blue" alt="Release" /></a>
  <a href="https://github.com/nelvinzfx/rikkahub-forge/releases"><img src="https://img.shields.io/github/downloads/nelvinzfx/rikkahub-forge/total?style=flat-square&color=brightgreen" alt="Downloads" /></a>
  <img src="https://img.shields.io/badge/platform-Android%208%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8+" />
  <img src="https://img.shields.io/badge/arch-arm64%20only-orange?style=flat-square" alt="arm64 only" />
</p>

<a href="https://github.com/nelvinzfx/rikkahub-forge/releases/latest"><strong>Download</strong></a> ·
<a href="#whats-different"><strong>What's different</strong></a> ·
<a href="#building"><strong>Build</strong></a>

</div>

---

## What's different from upstream

This fork doesn't add new tools or change agent behavior. The changes are UI/build focused.

### Tool-call display

- **Tree-style parameter rendering** — tool call arguments and results render as an expandable JSON tree instead of raw text blocks.
- **Canvas-drawn connectors** — tree lines are drawn via Compose Canvas (not box-drawing characters), with smooth rounded brace connectors. First/last children get rounded bends; middle children get straight tees.
- **Electric-flow animation** — while a tool is running, a silver→white pulse with glow travels along the connector lines. Off when idle.
- **Long-press to copy** any value from the tree.
- **Typing animation** — parameter values animate in character-by-character while the tool is executing.
- **Value truncation** — long values are capped at 15 lines with an accurate "N more lines" count (trailing newlines don't inflate the count).

### Agent overlay pill

- **Animated gradient on "working"** — the "The agent is working" overlay pill now has a 3-color flowing gradient (violet → cyan → pink) on the word "working", bold text.

### Build & CI

- **arm64-only release builds** via GitHub Actions (`release.yml` + `compile-check.yml`).
- **Keystore removed from repo** — signing key injected via CI secret, not committed.
- **JDK 21** for Kotlin 2.4.0 compatibility.
- **Custom model icon picker**.

### Bug fixes

- **Parallel conversation data loss** — `ensureHydrated` no longer overwrites a session's live in-memory state with a stale DB snapshot while a generation is in flight. This caused turns to revert/disappear when switching between two conversations that were both generating.
- **Thick dots at tree row boundaries** — caused by `StrokeCap.Round` overlap; fixed with butt caps.
- **Thick vertical bar artifact** — brace curve radius was larger than half the row height, causing the bend's vertical leg to render past row bounds and overlap neighbouring rows. Now clamped to available height.
- **Drawer stuck after rotation** — fixed landscape-to-portrait drawer state.
- **App freeze on long tool values** — values are now truncated before rendering.

---

## Base features

Everything from upstream RikkaHub is included: 80+ device tools, Telegram bot, in-app browser, AI-authored workflows, scheduled jobs, SSH, file manager, music player, voice transcription, sub-agents, MCP server support, skills, and more. See the [upstream README](https://github.com/rikkahub/rikkahub) for the full feature list.

---

## Building

```bash
# Debug compile check (CI runs this on push/PR)
./gradlew compileDebugKotlin

# Release build (CI only — needs signing key in KEY_BASE64 secret)
# Trigger via GitHub Actions: Release Build (arm64) workflow
```

Builds target arm64-v8a only. To build locally you need JDK 21 and the submodules.

---

## License

Same as upstream RikkaHub.
