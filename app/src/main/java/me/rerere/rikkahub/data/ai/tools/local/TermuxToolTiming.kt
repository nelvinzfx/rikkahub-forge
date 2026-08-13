package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * Stamps wall-clock execution time onto native Termux tool results.
 *
 * Applied once at tool registration (LocalTools) so EVERY termux tool call -
 * run_command capture/background/interactive, read_output, read_file(s),
 * read_file_bytes, write_file, edit_file(s) and session start/send/read/kill/
 * list - is measured at the same choke point: around the whole `execute` body,
 * using the monotonic System.nanoTime clock (never wall-clock-adjustable
 * System.currentTimeMillis).
 *
 * `elapsed_ms` is purely additive on the top-level JSON envelope: older
 * persisted results without it keep rendering, and the UI-model parsers
 * ignore unknown keys. Success AND error envelopes are stamped alike because
 * the wrapper sits outside every early-return path. Only the first Text part
 * that parses as a JSON object is stamped (all termux tools emit exactly one
 * envelope part); a pre-existing `elapsed_ms` key is never overwritten.
 * Part metadata (e.g. DiffMetadata on edit results) is preserved by copy().
 */
internal fun Tool.withTermuxElapsedTime(): Tool {
    val inner = execute
    return copy(execute = { input ->
        val startNanos = System.nanoTime()
        val parts = inner(input)
        stampTermuxElapsedMs(parts, (System.nanoTime() - startNanos) / 1_000_000)
    })
}

internal fun stampTermuxElapsedMs(parts: List<UIMessagePart>, elapsedMs: Long): List<UIMessagePart> {
    var stamped = false
    return parts.map { part ->
        if (stamped || part !is UIMessagePart.Text) return@map part
        val envelope = runCatching { Json.parseToJsonElement(part.text) }.getOrNull() as? JsonObject
            ?: return@map part
        stamped = true
        if ("elapsed_ms" in envelope) return@map part
        part.copy(text = JsonObject(envelope + ("elapsed_ms" to JsonPrimitive(elapsedMs))).toString())
    }
}
