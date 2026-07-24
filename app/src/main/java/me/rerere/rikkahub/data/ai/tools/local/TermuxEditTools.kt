package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.preferences.TermuxRuntime
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID

private data class PreparedTermuxEdit(
    val request: TermuxEditFileSpec,
    val snapshot: TermuxEditSnapshot,
    val source: TermuxTextBytes,
    val outcome: TermuxEditOutcome,
    val resultBytes: ByteArray,
    val resultSha256: String,
    val diff: String?,
    val diffOmitted: Boolean,
    var stageId: String? = null,
)

private suspend fun invokeEditHelper(context: Context, script: String, arguments: Array<String>, timeoutMs: Long): CaptureResult =
    runCommandCapture(context, "/data/data/com.termux/files/usr/bin/bash", arrayOf("-c", script, "rikka-edit", *arguments), TERMUX_FILES_HOME, timeoutMs, spoolOutput = false)

private fun captureError(capture: CaptureResult, operation: String): TermuxTransferProtocolResult.Error? = when (capture) {
    is CaptureResult.Success -> if (capture.exitCode == 0) null else TermuxTransferProtocolResult.Error("termux_${operation}_failed", capture.stderr.take(1_000))
    is CaptureResult.Timeout -> TermuxTransferProtocolResult.Error("${operation}_timeout")
    is CaptureResult.Denied -> TermuxTransferProtocolResult.Error("termux_permission_denied")
    is CaptureResult.OtherError -> TermuxTransferProtocolResult.Error("termux_${operation}_failed", capture.message)
}

private suspend fun cleanupEditTransfer(context: Context, requestId: String) {
    invokeEditHelper(context, TERMUX_TRANSFER_CLEANUP_SCRIPT, arrayOf(requestId), TERMUX_TRANSFER_HELPER_TIMEOUT_MS)
}

private suspend fun stageEditBytes(context: Context, requestId: String, bytes: ByteArray): TermuxTransferProtocolResult<Unit> {
    for (chunk in splitTermuxTransferBytes(bytes)) {
        val capture = invokeEditHelper(context, TERMUX_STAGE_CHUNK_SCRIPT, arrayOf(requestId, chunk.index.toString(), chunk.count.toString(), chunk.totalBytes.toString(), chunk.fullSha256, chunk.canonicalBase64), TERMUX_TRANSFER_HELPER_TIMEOUT_MS)
        captureError(capture, "transfer")?.let { return it }
        val parsed = parseTermuxTransferAck((capture as CaptureResult.Success).stdout, requestId, chunk.index)
        if (parsed is TermuxTransferProtocolResult.Error) return parsed
    }
    return TermuxTransferProtocolResult.Ok(Unit)
}

private suspend fun snapshotEditFiles(context: Context, requestId: String, files: List<TermuxEditFileSpec>): TermuxTransferProtocolResult<List<TermuxEditSnapshot>> {
    val args = mutableListOf(requestId, files.size.toString())
    val pathShas = files.map { sha256Hex(it.pathBytes) }
    files.forEachIndexed { index, file ->
        args += encodeTermuxPath(file.path); args += file.expectedSha256 ?: "-"; args += pathShas[index]
    }
    val capture = invokeEditHelper(context, TERMUX_EDIT_SNAPSHOT_SCRIPT, args.toTypedArray(), TermuxRuntime.commandTimeoutMs)
    captureError(capture, "snapshot")?.let { return it }
    return parseTermuxEditSnapshots((capture as CaptureResult.Success).stdout, requestId, pathShas)
}

private suspend fun readEditSnapshot(context: Context, requestId: String, snapshot: TermuxEditSnapshot): TermuxTransferProtocolResult<ByteArray> {
    val output = ByteArrayOutputStream(snapshot.bytes); var offset = 0
    while (offset < snapshot.bytes) {
        val length = minOf(TERMUX_TRANSFER_CHUNK_BYTES, snapshot.bytes - offset)
        val capture = invokeEditHelper(context, TERMUX_EDIT_READ_SNAPSHOT_SCRIPT, arrayOf(requestId, snapshot.index.toString(), offset.toString(), length.toString()), TERMUX_TRANSFER_HELPER_TIMEOUT_MS)
        captureError(capture, "snapshot_read")?.let { return it }
        when (val parsed = parseTermuxEditChunk((capture as CaptureResult.Success).stdout, requestId, snapshot.index, offset, length)) {
            is TermuxTransferProtocolResult.Error -> return parsed
            is TermuxTransferProtocolResult.Ok -> output.write(parsed.value)
        }
        offset += length
    }
    val bytes = output.toByteArray()
    return if (bytes.size == snapshot.bytes && sha256Hex(bytes) == snapshot.sha256) TermuxTransferProtocolResult.Ok(bytes) else TermuxTransferProtocolResult.Error("snapshot_mismatch")
}

private suspend fun publishEditFiles(context: Context, snapshotId: String, prepared: List<PreparedTermuxEdit>): TermuxTransferProtocolResult<TermuxEditPublishResult> {
    val args = mutableListOf(snapshotId, prepared.size.toString())
    prepared.forEach { item ->
        val actualPathBytes = item.snapshot.actualPath.toByteArray(Charsets.UTF_8)
        args += Base64.getEncoder().encodeToString(actualPathBytes)
        args += sha256Hex(actualPathBytes)
        args += item.snapshot.sha256
        args += item.snapshot.mode
        args += item.snapshot.identity
        args += item.stageId ?: "-"
        args += item.resultSha256
        args += if (item.outcome.changed) "1" else "0"
    }
    val capture = invokeEditHelper(context, TERMUX_EDIT_PUBLISH_SCRIPT, args.toTypedArray(), TermuxRuntime.commandTimeoutMs)
    captureError(capture, "publish")?.let { return it }
    return parseTermuxEditPublish((capture as CaptureResult.Success).stdout, snapshotId, prepared.map { Triple(it.snapshot.actualPath, it.snapshot.sha256, it.resultSha256) })
}

private fun diagnosticJson(value: TermuxEditDiagnostic) = buildJsonObject {
    put("index", value.index); put("mode", value.mode); put("status", value.status); put("matched", value.matched)
    value.strategy?.let { put("strategy", it) }; value.reason?.let { put("reason", it) }
    if (value.candidateLines.isNotEmpty()) put("candidate_lines", buildJsonArray { value.candidateLines.take(5).forEach { (line, text) -> add(buildJsonObject { put("line", line); put("text", text) }) } })
    value.closestLine?.let { put("closest_match_line", it) }; value.similarity?.let { put("similarity", it) }; value.nearbyText?.let { put("nearby_text", it.take(MAX_TERMUX_EDIT_DIAGNOSTIC_CHARS)) }
}

private const val EDIT_BOUNDARY = "Each file replacement is atomic. A crash between batch renames is not fully atomic without a WAL; an uncooperative same-UID writer can race the final check-to-rename interval."

/**
 * JsonObjectBuilder.put returns the previous mapping, which is null for a new key.
 * Never use `value?.let { put(key, it) } ?: put(key, JsonNull)`: the Elvis branch
 * would run after a successful first put and immediately overwrite it with null.
 */
internal fun kotlinx.serialization.json.JsonObjectBuilder.putNullableString(
    key: String,
    value: String?,
) {
    if (value == null) put(key, JsonNull) else put(key, value)
}
private fun editError(
    code: String,
    detail: String? = null,
    path: String? = null,
    dryRun: Boolean = false,
) = buildJsonObject {
    put("ok", false); put("success", false); put("applied", false); put("changed", false); put("dry_run", dryRun); put("state", "error"); put("error", code)
    path?.let { put("path", it) }; detail?.let { put("detail", it.take(1_000)) }; put("crash_atomic", false); put("boundary", EDIT_BOUNDARY)
}

private fun preparedJson(item: PreparedTermuxEdit, dryRun: Boolean, published: TermuxEditPublishItem? = null) = buildJsonObject {
    put("path", item.request.path); put("actual_path", item.snapshot.actualPath); put("source_sha256", item.snapshot.sha256); put("result_sha256", item.resultSha256)
    put("changed", item.outcome.changed); put("applied", published?.state == "published"); put("dry_run", dryRun)
    put("state", when { !item.outcome.success -> "error"; published != null -> published.state; !item.outcome.changed -> "no_change"; dryRun -> "dry_run"; else -> "aborted" })
    put("line_ending", item.source.lineEnding); put("utf8_bom", item.source.bom); put("mode", item.snapshot.mode)
    put("edits", buildJsonArray { item.outcome.diagnostics.forEach { add(diagnosticJson(it)) } })
    putNullableString("diff", item.diff)
    published?.rolledBack?.let { put("rollback_restored", it) }; item.outcome.error?.let { put("error", it) }
}

private const val MAX_TERMUX_DIFF_INPUT_CHARS = 256 * 1024
private const val MAX_TERMUX_DIFF_LINES = 2_000
private const val MAX_TERMUX_DIFF_WORK_UNITS = 2_000_000L

private data class BoundedDiff(val text: String?, val omitted: Boolean)

internal fun takeTermuxEditDiffPrefix(text: String, maxChars: Int): String {
    if (maxChars <= 0) return ""
    var end = minOf(text.length, maxChars)
    if (end in 1 until text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
    return text.substring(0, end)
}

internal fun isSafeTermuxEditDiffPath(path: String): Boolean = path.none { it == '\n' || it == '\r' }

private fun boundedEditDiff(oldText: String, newText: String, path: String): BoundedDiff {
    if (oldText == newText) return BoundedDiff(null, false)
    if (!isSafeTermuxEditDiffPath(path)) return BoundedDiff(null, true)
    if (oldText.length.toLong() + newText.length > MAX_TERMUX_DIFF_INPUT_CHARS) return BoundedDiff(null, true)
    fun physicalLines(text: String): Long {
        var lines = 1L
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '\r' -> { lines++; if (index + 1 < text.length && text[index + 1] == '\n') index++ }
                '\n' -> lines++
            }
            index++
        }
        return lines
    }
    val oldLines = physicalLines(oldText)
    val newLines = physicalLines(newText)
    if (oldLines > MAX_TERMUX_DIFF_LINES || newLines > MAX_TERMUX_DIFF_LINES) return BoundedDiff(null, true)
    if (oldLines * newLines > MAX_TERMUX_DIFF_WORK_UNITS) return BoundedDiff(null, true)
    val diff = me.rerere.rikkahub.utils.generateUnifiedDiff(oldText, newText, path)
    return if (diff == null) BoundedDiff(null, false) else BoundedDiff(diff, false)
}

private fun truncateDiffs(prepared: List<PreparedTermuxEdit>): Pair<List<PreparedTermuxEdit>, Boolean> {
    var remaining = TERMUX_EDIT_DIFF_MAX_CHARS; var truncated = prepared.any { it.diffOmitted }
    return prepared.map { item ->
        val diff = item.diff
        if (diff == null || remaining == 0) { if (diff != null) truncated = true; item.copy(diff = null) } else {
            val kept = takeTermuxEditDiffPrefix(diff, remaining); if (kept.length < diff.length) truncated = true; remaining -= kept.length; item.copy(diff = kept)
        }
    } to truncated
}

private fun response(request: TermuxEditRequest, prepared: List<PreparedTermuxEdit>, bounded: List<PreparedTermuxEdit>, dryRun: Boolean, published: TermuxEditPublishResult?, diffTruncated: Boolean, error: String?) = buildJsonObject {
    val success = error == null && prepared.all { it.outcome.success } && (published?.success != false)
    val changed = prepared.any { it.outcome.changed }
    val applied = !dryRun && published?.success == true && published.items.any { it.state == "published" }
    put("ok", success); put("success", success); put("applied", applied); put("changed", changed); put("dry_run", dryRun); put("batch_aborted", !success)
    put("state", when { !success -> "error"; dryRun -> "dry_run"; applied -> "applied"; else -> "no_change" })
    put("diff_truncated", diffTruncated)
    putNullableString("error", error)
    if (request.single) {
        val item = bounded.single(); val pub = published?.items?.single(); put("path", item.request.path); put("actual_path", item.snapshot.actualPath); put("replacements", item.outcome.diagnostics.count { it.status == "applied" })
        put("source_sha256", item.snapshot.sha256)
        put("result_sha256", item.resultSha256)
        putNullableString("diff", item.diff)
        put("results", buildJsonArray { item.outcome.diagnostics.forEach { add(diagnosticJson(it)) } })
        pub?.rolledBack?.let { put("rollback_restored", it) }
    } else put("files", buildJsonArray { bounded.forEachIndexed { index, item -> add(preparedJson(item, dryRun, published?.items?.get(index))) } })
    if (!dryRun) { put("crash_atomic", false); put("boundary", EDIT_BOUNDARY) }
}

internal suspend fun executeTermuxEdit(context: Context, request: TermuxEditRequest): List<UIMessagePart> {
    val snapshotId = UUID.randomUUID().toString(); val cleanupIds = linkedSetOf(snapshotId)
    try {
        val snapshots = when (val value = snapshotEditFiles(context, snapshotId, request.files)) { is TermuxTransferProtocolResult.Error -> return listOf(UIMessagePart.Text(editError(value.code, value.detail, dryRun = request.dryRun).toString())); is TermuxTransferProtocolResult.Ok -> value.value }
        val prepared = mutableListOf<PreparedTermuxEdit>(); var totalResult = 0L
        val sharedWorkBudget = TermuxEditWorkBudget(MAX_TERMUX_EDIT_BATCH_WORK_UNITS)
        request.files.zip(snapshots).forEach { (file, snapshot) ->
            val bytes = when (val value = readEditSnapshot(context, snapshotId, snapshot)) { is TermuxTransferProtocolResult.Error -> return listOf(UIMessagePart.Text(editError(value.code, value.detail, file.path, request.dryRun).toString())); is TermuxTransferProtocolResult.Ok -> value.value }
            val source = decodeTermuxEditSource(bytes) ?: return listOf(UIMessagePart.Text(editError("source_invalid_utf8", path = file.path, dryRun = request.dryRun).toString()))
            val outcome = applyTermuxEdits(source.text, file.edits, sharedBudget = sharedWorkBudget)
            val resultBytes = encodeTermuxEditResult(source, outcome.edited) ?: return listOf(UIMessagePart.Text(editError("result_too_large_or_invalid_utf8", path = file.path, dryRun = request.dryRun).toString()))
            totalResult += resultBytes.size; if (totalResult > MAX_TERMUX_EDIT_TOTAL_RESULT_BYTES) return listOf(UIMessagePart.Text(editError("total_result_too_large", dryRun = request.dryRun).toString()))
            val resultSha = if (!outcome.changed) snapshot.sha256 else sha256Hex(resultBytes)
            val diff = if (outcome.success && outcome.changed) boundedEditDiff(source.text, outcome.edited, snapshot.actualPath) else BoundedDiff(null, false)
            prepared += PreparedTermuxEdit(file, snapshot, source, outcome, resultBytes, resultSha, diff.text, diff.omitted)
        }
        val (bounded, diffTruncated) = truncateDiffs(prepared)
        if (prepared.any { !it.outcome.success }) return listOf(UIMessagePart.Text(response(request, prepared, bounded, request.dryRun, null, diffTruncated, "atomic_edit_aborted").toString()))
        if (request.dryRun) {
            val body = response(request, prepared, bounded, true, null, diffTruncated, null); val diff = bounded.mapNotNull { it.diff }.joinToString("\n")
            return listOf(UIMessagePart.Text(body.toString(), metadata = diff.takeIf(String::isNotEmpty)?.let { DiffMetadata(it).toMetadata() }))
        }
        for (item in prepared) if (item.outcome.changed) {
            val id = UUID.randomUUID().toString(); cleanupIds += id
            when (val staged = stageEditBytes(context, id, item.resultBytes)) { is TermuxTransferProtocolResult.Error -> return listOf(UIMessagePart.Text(editError(staged.code, staged.detail, item.request.path, request.dryRun).toString())); is TermuxTransferProtocolResult.Ok -> item.stageId = id }
        }
        val published = when (val value = publishEditFiles(context, snapshotId, prepared)) { is TermuxTransferProtocolResult.Error -> return listOf(UIMessagePart.Text(editError(value.code, value.detail, dryRun = request.dryRun).toString())); is TermuxTransferProtocolResult.Ok -> value.value }
        val body = response(request, prepared, bounded, false, published, diffTruncated, published.error); val diff = bounded.mapNotNull { it.diff }.joinToString("\n")
        return listOf(UIMessagePart.Text(body.toString(), metadata = diff.takeIf(String::isNotEmpty)?.let { DiffMetadata(it).toMetadata() }))
    } finally { withContext(NonCancellable) { cleanupIds.forEach { id -> runCatching { cleanupEditTransfer(context, id) } } } }
}

private fun editSpecSchema() = buildJsonObject {
    put("type", "object"); put("properties", buildJsonObject {
        put("mode", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add("replace_match"); add("insert_before"); add("insert_after"); add("insert_before_match"); add("insert_after_match") }) })
        put("match_text", buildJsonObject { put("type", "string"); put("minLength", 1) }); put("write_text", buildJsonObject { put("type", "string") })
        put("matchText", buildJsonObject { put("type", "string"); put("minLength", 1); put("description", "Compatibility alias for match_text; conflicting values are rejected.") }); put("writeText", buildJsonObject { put("type", "string"); put("description", "Compatibility alias for write_text; conflicting values are rejected.") })
    }); put("required", buildJsonArray { add("mode") }); put("allOf", buildJsonArray {
        add(buildJsonObject { put("anyOf", buildJsonArray { add(buildJsonObject { put("required", buildJsonArray { add("match_text") }) }); add(buildJsonObject { put("required", buildJsonArray { add("matchText") }) }) }) })
        add(buildJsonObject { put("anyOf", buildJsonArray { add(buildJsonObject { put("required", buildJsonArray { add("write_text") }) }); add(buildJsonObject { put("required", buildJsonArray { add("writeText") }) }) }) })
    }); put("additionalProperties", false)
}
private fun editsArraySchema() = buildJsonObject { put("type", "array"); put("minItems", 1); put("maxItems", MAX_TERMUX_EDITS_PER_FILE); put("items", editSpecSchema()) }
private fun expectedShaSchema() = buildJsonObject { put("type", buildJsonArray { add("string"); add("null") }); put("pattern", "^[0-9a-f]{64}$") }
private fun fileSpecSchema() = buildJsonObject { put("type", "object"); put("properties", buildJsonObject { put("path", buildJsonObject { put("type", "string") }); put("edits", editsArraySchema()); put("expected_sha256", expectedShaSchema()) }); put("required", buildJsonArray { add("path"); add("edits") }); put("additionalProperties", false) }

fun termuxEditFileTool(context: Context): Tool = Tool(
    name = "termux_edit_file", description = "Atomically edit one existing strict UTF-8 Termux file without creating it. Matches are unique exact, Unicode-normalized, or indent-insensitive spans resolved against the original source. Dry-run validates and returns a bounded unified diff. Apply preserves mode, BOM, mixed LF/CRLF/CR separators outside edited spans, and final-newline state with source SHA revalidation.",
    parameters = { InputSchema.Obj(buildJsonObject { put("path", buildJsonObject { put("type", "string") }); put("edits", editsArraySchema()); put("dry_run", buildJsonObject { put("type", "boolean"); put("default", false) }); put("expected_sha256", expectedShaSchema()) }, listOf("path", "edits"), additionalProperties = false) },
    execute = { input ->
        val requestedDryRun = (input as? kotlinx.serialization.json.JsonObject)?.get("dry_run")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { value -> !value.isString }?.content?.toBooleanStrictOrNull() } == true
        when (val parsed = parseTermuxEditRequest(input, true)) {
            is PublicInputResult.Error -> listOf(UIMessagePart.Text(editError(parsed.value.code, path = parsed.value.path, dryRun = requestedDryRun).toString()))
            is PublicInputResult.Ok -> executeTermuxEdit(context, parsed.value)
        }
    },
)
fun termuxEditFilesTool(context: Context): Tool = Tool(
    name = "termux_edit_files", description = "Transactionally edit 1 to 20 existing strict UTF-8 Termux files. All files validate and stage before publication; each replacement is atomic and a later publication failure triggers verified best-effort rollback of earlier replacements.",
    parameters = { InputSchema.Obj(buildJsonObject { put("files", buildJsonObject { put("type", "array"); put("minItems", 1); put("maxItems", MAX_TERMUX_EDIT_FILES); put("items", fileSpecSchema()) }); put("dry_run", buildJsonObject { put("type", "boolean"); put("default", false) }) }, listOf("files"), additionalProperties = false) },
    execute = { input ->
        val requestedDryRun = (input as? kotlinx.serialization.json.JsonObject)?.get("dry_run")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { value -> !value.isString }?.content?.toBooleanStrictOrNull() } == true
        when (val parsed = parseTermuxEditRequest(input, false)) {
            is PublicInputResult.Error -> listOf(UIMessagePart.Text(editError(parsed.value.code, path = parsed.value.path, dryRun = requestedDryRun).toString()))
            is PublicInputResult.Ok -> executeTermuxEdit(context, parsed.value)
        }
    },
)
