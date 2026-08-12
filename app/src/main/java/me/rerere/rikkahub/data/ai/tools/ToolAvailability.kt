package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * General "called while unavailable" diagnostics for the tool-resolution layer.
 *
 * A tool the model legitimately remembers from earlier context can stop being executable
 * mid-conversation for several reasons: the global Termux integration switch flipped off,
 * an MCP server was disabled / disconnected / re-synced its tool list, or the name was
 * never valid. Before this existed, dispatch collapsed all of those into an opaque
 * `tool_failed: Tool X not found` (or the model silently never called the tool at all
 * because the definitions vanished without notice). [inspect] attaches the likely cause
 * and a user-actionable remedy; [buildUnavailableEnvelope] is the in-band error the model
 * receives; [toolSetChangeNotice] produces the per-generation system addendum that tells
 * the model when the advertised tool set changed; [ToolAvailability.buildTombstoneTool]
 * keeps a vanished tool's NAME advertised as a stub so the model's call forms correctly
 * and resolves into the envelope instead of materializing as hallucinated calls to
 * unrelated tools (live-observed when definitions vanish while names persist in context).
 *
 * Pure functions only — every caller-facing decision is unit-testable on the JVM.
 */

/** Model-facing names of the Termux tool group that [LocalTools.getTools] adds when
 *  `TermuxRuntime.integrationEnabled` is true. Keep in sync with the Termux block in
 *  LocalTools.getTools (the group is added as one `if` there; these are its names). */
val TERMUX_GROUP_TOOL_NAMES: Set<String> = setOf(
    "termux_run_command",
    "termux_read_output",
    "termux_read_file",
    "termux_read_file_bytes",
    "termux_read_files",
    "termux_write_file",
    "termux_edit_file",
    "termux_edit_files",
    "termux_session_start",
    "termux_session_send",
    "termux_session_read",
    "termux_session_kill",
    "termux_session_list",
    // whisper shells out through Termux's RUN_COMMAND service, so it lives under the
    // same global gate (see LocalTools.getTools).
    "transcribe_audio_file",
    "whisper_status",
)

/** Why a tool is unavailable and what the model/user can do about it. */
data class UnavailableToolInfo(
    val reason: String,
    val recovery: String,
)

/** Point-in-time snapshot of one configured MCP server for diagnostics. Built by the
 *  caller (ChatService) from settings + live client state so this file stays pure. */
data class McpServerSnapshot(
    /** First 8 hex chars of the server id — the slug embedded in model-facing names. */
    val slugPrefix: String,
    val name: String,
    val enabled: Boolean,
    val enabledForAssistant: Boolean,
    /** Currently-exposed (enabled) tool names from the last known tool list. */
    val knownToolNames: Set<String>,
    /** Live transport state at assembly time. */
    val connected: Boolean,
)

object ToolAvailability {

    /**
     * Best-effort cause lookup for a tool name that failed to resolve at dispatch time.
     * Returns null when nothing specific is known (caller falls back to a generic but
     * still explicit envelope).
     */
    fun inspect(
        toolName: String,
        termuxIntegrationEnabled: Boolean,
        mcpServers: List<McpServerSnapshot>,
    ): UnavailableToolInfo? {
        if (!termuxIntegrationEnabled && toolName in TERMUX_GROUP_TOOL_NAMES) {
            return UnavailableToolInfo(
                reason = "Termux integration is switched off, so the entire Termux tool group " +
                    "was removed from the active tool set.",
                recovery = "Do not retry Termux tools — they will keep failing while the switch " +
                    "is off. Tell the user Termux tools are unavailable and can be re-enabled in " +
                    "Settings > Termux; continue with non-Termux tools or ask the user how to proceed.",
            )
        }
        if (toolName.startsWith("mcp__")) {
            inspectMcp(toolName, mcpServers)?.let { return it }
        }
        return null
    }

    /**
     * Diagnose a model-facing MCP tool name (`mcp__<slug8>_<serverName>__<toolName>`).
     * Server names are validated alphanumeric-only at assembly time, so the first `_`
     * ends the slug and the first `__` ends the server name — the parse is unambiguous.
     */
    private fun inspectMcp(
        toolName: String,
        servers: List<McpServerSnapshot>,
    ): UnavailableToolInfo? {
        val rest = toolName.removePrefix("mcp__")
        val sep = rest.indexOf("__")
        if (sep <= 0) return null
        val head = rest.substring(0, sep)
        val calledTool = rest.substring(sep + 2)
        val slug = head.substringBefore('_')
        val server = servers.firstOrNull { it.slugPrefix == slug } ?: return null
        return when {
            !server.enabled -> UnavailableToolInfo(
                reason = "MCP server '${server.name}' is disabled, so its tools are no longer " +
                    "in the active tool set.",
                recovery = "Do not retry. Tell the user the MCP server '${server.name}' is " +
                    "disabled and can be re-enabled in Settings > MCP servers.",
            )
            !server.enabledForAssistant -> UnavailableToolInfo(
                reason = "MCP server '${server.name}' is not enabled for this assistant.",
                recovery = "Do not retry. The user can enable the server for this assistant in " +
                    "the assistant's settings.",
            )
            !server.connected -> UnavailableToolInfo(
                reason = "MCP server '${server.name}' is enabled but currently disconnected " +
                    "(crashed, unreachable, or still reconnecting).",
                recovery = "Do not retry in a loop. Tell the user the MCP server " +
                    "'${server.name}' appears to be down; they can check it in Settings > MCP " +
                    "servers, then retry once it reconnects.",
            )
            calledTool !in server.knownToolNames -> UnavailableToolInfo(
                reason = "MCP server '${server.name}' no longer exposes a tool named " +
                    "'$calledTool' — its tool list changed (re-synced) since this conversation " +
                    "started.",
                recovery = "Stop calling the old name. If the server now offers an equivalent " +
                    "tool it will appear in the current tool set; otherwise tell the user the " +
                    "capability disappeared from '${server.name}'.",
            )
            else -> null
        }
    }

    /**
     * The in-band error envelope returned to the model when a tool call cannot resolve to
     * an executable. Always explicit (never silent, never a bare exception): the name, why
     * it is unavailable when known, and what to do next.
     */
    fun buildUnavailableEnvelope(toolName: String, info: UnavailableToolInfo?): String =
        buildJsonObject {
            put("error", JsonPrimitive("tool_unavailable"))
            put("tool", JsonPrimitive(toolName))
            put(
                "detail",
                JsonPrimitive(
                    info?.reason
                        ?: "Tool '$toolName' is not in the active tool set for this generation. " +
                            "It may have been disabled mid-conversation, its source (e.g. an MCP " +
                            "server) may have changed, or the name was never valid."
                ),
            )
            put(
                "recovery",
                JsonPrimitive(
                    info?.recovery
                        ?: "Do not retry the same tool name — it will keep failing. Continue with " +
                            "the tools that are currently available, and if the user is expecting " +
                            "this capability, tell them plainly that it is unavailable."
                ),
            )
        }.toString()

    private const val MAX_LISTED_NAMES = 8

    /**
     * Names that must be tombstoned this generation: advertised to the model in the
     * previous generation of this conversation but absent from the current assembly.
     *
     * Scope boundary (deliberate): ONLY present-then-absent within the SAME conversation.
     * A name that was never advertised here — e.g. a tool on an MCP server that was never
     * enabled for this assistant — is never tombstoned: advertising it would bloat the
     * context and silently widen assistant-scoping semantics. The first generation of a
     * conversation (previous == null) therefore tombstones nothing.
     */
    fun tombstoneNames(previous: Set<String>?, current: Set<String>): Set<String> =
        previous.orEmpty() - current

    /** Model-facing description for a tombstone definition. Deliberately terse — the
     *  tombstone count is bounded by the previous set size, but every description still
     *  costs context budget in each generation it appears in. */
    fun tombstoneDescription(info: UnavailableToolInfo?): String =
        if (info != null) {
            "CURRENTLY DISABLED — ${info.reason} DO NOT CALL. If the user asks for this " +
                "capability, tell them how to re-enable it."
        } else {
            "CURRENTLY UNAVAILABLE — no longer offered. DO NOT CALL; inform the user."
        }

    /**
     * A stub [Tool] that keeps a vanished tool's NAME valid in the model's context.
     *
     * When a definition silently disappears while earlier turns still reference the name,
     * the model's attempted call cannot form against the current definitions and was
     * live-observed materializing as calls to RANDOM other tools — which means the
     * dispatch-layer toolDef==null backstop never fires. A tombstone keeps the name
     * addressable: the call forms correctly, resolves through the NORMAL dispatch path
     * (toolDef != null), and its execute returns the same explicit [buildUnavailableEnvelope]
     * output. Tombstones are recomputed per generation from the real-tools-only baseline,
     * so when a tool comes back the real definition simply replaces the stub.
     */
    fun buildTombstoneTool(toolName: String, info: UnavailableToolInfo?): Tool = Tool(
        name = toolName,
        description = tombstoneDescription(info),
        // Minimal empty object schema — some providers reject definitions without one.
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
        // Never approval-gate a tombstone: the whole point is that a call resolves
        // immediately and cheaply into the explicit tool_unavailable envelope.
        needsApproval = { false },
        execute = {
            listOf(UIMessagePart.Text(buildUnavailableEnvelope(toolName, info)))
        },
    )

    /**
     * Short system-addendum note for when the assembled tool definitions differ from the
     * previous generation in the same conversation. The model's in-context history still
     * references the old set (earlier assistant turns may even show successful calls to
     * now-removed tools), so a silent swap leaves it working from stale context.
     *
     * Returns null on the first generation of a conversation (no baseline) and when
     * nothing changed.
     */
    fun toolSetChangeNotice(previous: Set<String>?, current: Set<String>): String? {
        if (previous == null) return null
        val removed = previous - current
        val added = current - previous
        if (removed.isEmpty() && added.isEmpty()) return null

        fun format(names: Set<String>): String {
            val sorted = names.sorted()
            val listed = sorted.take(MAX_LISTED_NAMES).joinToString(", ")
            val overflow = sorted.size - MAX_LISTED_NAMES
            return if (overflow > 0) "$listed, … (+$overflow more)" else listed
        }

        return buildString {
            append("Note: the set of available tools changed since the previous generation in this conversation.")
            if (removed.isNotEmpty()) {
                append(" Removed: ")
                append(format(removed))
                append(" — do not call these; a call now returns a tool_unavailable error.")
            }
            if (added.isNotEmpty()) {
                append(" Added: ")
                append(format(added))
                append(".")
            }
            append(" If the user expects a removed capability, tell them it is currently unavailable and how to re-enable it")
            append(" (Settings > Termux for termux_* / transcription tools, Settings > MCP servers for mcp__* tools).")
        }
    }
}
