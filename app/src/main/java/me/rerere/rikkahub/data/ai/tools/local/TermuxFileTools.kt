package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.preferences.TermuxRuntime
import java.util.Base64
import java.util.UUID

internal const val TERMUX_FILE_READ_TIMEOUT_MS = 20_000L

internal val TERMUX_FILE_COMMON_SCRIPT = """
    set -euo pipefail
    export LC_ALL=C
    ${TERMUX_PATH_RESOLVER_SCRIPT}
    request_id=${'$'}1; path_b64=${'$'}2
    valid_request_id() { [[ "${'$'}1" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}${'$'} ]]; }
    valid_request_id "${'$'}request_id" || exit 64
    protocol_error() {
        printf '%s\nrequest_id=%s\nerror=%s\n' "${'$'}protocol_marker" "${'$'}request_id" "${'$'}1"
        exit 0
    }
    set +e; resolve_termux_path "${'$'}path_b64"; resolver_rc=${'$'}?; set -e
    if [ "${'$'}resolver_rc" -ne 0 ]; then
        case "${'$'}resolver_rc" in 64) protocol_error path_too_large;; 65) protocol_error invalid_path_encoding;; 66) protocol_error blank_path;; 68) protocol_error tmp_escape;; *) protocol_error invalid_path;; esac
    fi
    actual_b64=${'$'}(printf '%s' "${'$'}actual_path" | base64 -w0)
    opened_error() {
        printf '%s\nrequest_id=%s\nerror=%s\nactual_path_b64=%s\n' "${'$'}protocol_marker" "${'$'}request_id" "${'$'}1" "${'$'}actual_b64"
        exit 0
    }
    [ -e "${'$'}actual_path" ] || opened_error not_found
    [ -f "${'$'}actual_path" ] || opened_error not_regular_file
    exec 7< "${'$'}actual_path" || opened_error open_failed
    fd_path=/proc/${'$'}${'$'}/fd/7
    [ -f "${'$'}fd_path" ] || opened_error not_regular_file
    fd_before=${'$'}(stat -Lc '%d:%i:%s:%y' -- "${'$'}fd_path" 2>/dev/null) || opened_error stat_failed
    work=${'$'}(mktemp -d "${'$'}TMPDIR/rikkahub-read.XXXXXXXX") || opened_error temp_failed
    case "${'$'}work" in "${'$'}TMPDIR"/rikkahub-read.*) ;; *) opened_error unsafe_temp_path;; esac
    [ -d "${'$'}work" ] && [ ! -L "${'$'}work" ] || opened_error unsafe_temp_path
    selected=${'$'}work/selected; positions=${'$'}work/positions
    : > "${'$'}selected"; [ -f "${'$'}selected" ] && [ ! -L "${'$'}selected" ] || opened_error unsafe_temp_path
    trap 'exec 7<&-; rm -rf -- "${'$'}work"' EXIT HUP INT TERM
    sha_before=${'$'}(sha256sum -- "${'$'}fd_path" | cut -d' ' -f1)
""".trimIndent()

internal val TERMUX_TEXT_READ_SCRIPT = """
    protocol_marker=RIKKAHUB_FILE_TEXT_V1
    ${TERMUX_FILE_COMMON_SCRIPT}
    requested_offset=${'$'}3; requested_limit=${'$'}4; max_bytes=${'$'}5
    # od emits bounded records; awk sees only byte fields, never a whole source line.
    # awk numbers are IEEE doubles, so exact byte positions are limited to practical files below 2^53 bytes.
    scan_summary=${'$'}work/scan-summary
    od -An -v -t u1 "${'$'}fd_path" | awk '
        BEGIN { bytes=0; newlines=0; last=-1 }
        { for (i=1; i<=NF; i++) { b=${'$'}i + 0; bytes++; if (b==10) newlines++; last=b } }
        END { total=(bytes==0 ? 0 : newlines + (last==10 ? 0 : 1)); print bytes, total, last }
    ' > "${'$'}scan_summary"
    read -r total_bytes total_lines last_byte < "${'$'}scan_summary"
    if [ "${'$'}total_lines" -eq 0 ]; then
        if [ "${'$'}requested_offset" -ne 1 ]; then
            fd_after=${'$'}(stat -Lc '%d:%i:%s:%y' -- "${'$'}fd_path"); sha_after=${'$'}(sha256sum -- "${'$'}fd_path" | cut -d' ' -f1)
            [ "${'$'}fd_before" = "${'$'}fd_after" ] && [ "${'$'}sha_before" = "${'$'}sha_after" ] || opened_error source_changed
            printf '%s\nrequest_id=%s\nerror=offset_beyond_eof\nactual_path_b64=%s\ntotal_lines=0\nsha256=%s\n' "${'$'}protocol_marker" "${'$'}request_id" "${'$'}actual_b64" "${'$'}sha_before"
            exit 0
        fi
        fd_after=${'$'}(stat -Lc '%d:%i:%s:%y' -- "${'$'}fd_path"); sha_after=${'$'}(sha256sum -- "${'$'}fd_path" | cut -d' ' -f1)
        [ "${'$'}fd_before" = "${'$'}fd_after" ] && [ "${'$'}sha_before" = "${'$'}sha_after" ] || opened_error source_changed
        printf '%s\nrequest_id=%s\nactual_path_b64=%s\nstart_line=1\nend_line=0\ntotal_lines=0\ntruncated=0\nnext_offset=\nsha256=%s\ncontent_b64=\n' "${'$'}protocol_marker" "${'$'}request_id" "${'$'}actual_b64" "${'$'}sha_before"
        exit 0
    fi
    if [ "${'$'}requested_offset" -gt "${'$'}total_lines" ]; then
        fd_after=${'$'}(stat -Lc '%d:%i:%s:%y' -- "${'$'}fd_path"); sha_after=${'$'}(sha256sum -- "${'$'}fd_path" | cut -d' ' -f1)
        [ "${'$'}fd_before" = "${'$'}fd_after" ] && [ "${'$'}sha_before" = "${'$'}sha_after" ] || opened_error source_changed
        printf '%s\nrequest_id=%s\nerror=offset_beyond_eof\nactual_path_b64=%s\ntotal_lines=%s\nsha256=%s\n' "${'$'}protocol_marker" "${'$'}request_id" "${'$'}actual_b64" "${'$'}total_lines" "${'$'}sha_before"
        exit 0
    fi
    od -An -v -t u1 "${'$'}fd_path" | awk -v start="${'$'}requested_offset" -v limit="${'$'}requested_limit" -v cap="${'$'}max_bytes" -v total="${'$'}total_lines" '
        BEGIN { pos=0; line=1; line_start=0; selected_start=-1; selected_end=-1; last_selected=0; count=0; used=0; stopped=0 }
        function finish_line(line_end, current, bytes) {
            if (current < start || stopped) return
            if (count >= limit) { next_line=current; stopped=1; return }
            if (count==0 && bytes>cap) { too_large=1; next_line=current; stopped=1; return }
            if (used+bytes>cap) { next_line=current; stopped=1; return }
            if (count==0) selected_start=line_start
            used+=bytes; selected_end=line_end; last_selected=current; count++
        }
        {
            for (i=1; i<=NF; i++) {
                b=${'$'}i+0; pos++
                if (b==10) { finish_line(pos,line,pos-line_start); line++; line_start=pos }
            }
        }
        END {
            if (line<=total) finish_line(pos,line,pos-line_start)
            if (selected_start<0) selected_start=line_start
            if (selected_end<0) selected_end=selected_start
            print selected_start, selected_end, last_selected, (next_line+0), (too_large+0)
        }
    ' > "${'$'}positions"
    read -r byte_start byte_end end_line next_offset too_large < "${'$'}positions"
    if [ "${'$'}too_large" -eq 1 ]; then
        fd_after=${'$'}(stat -Lc '%d:%i:%s:%y' -- "${'$'}fd_path"); sha_after=${'$'}(sha256sum -- "${'$'}fd_path" | cut -d' ' -f1)
        [ "${'$'}fd_before" = "${'$'}fd_after" ] && [ "${'$'}sha_before" = "${'$'}sha_after" ] || opened_error source_changed
        printf '%s\nrequest_id=%s\nerror=line_too_large\nactual_path_b64=%s\ntotal_lines=%s\nsha256=%s\n' "${'$'}protocol_marker" "${'$'}request_id" "${'$'}actual_b64" "${'$'}total_lines" "${'$'}sha_before"
        exit 0
    fi
    selected_count=${'$'}((byte_end - byte_start))
    [ "${'$'}selected_count" -le "${'$'}max_bytes" ] || opened_error invalid_protocol_state
    dd if="${'$'}fd_path" of="${'$'}selected" bs=1 skip="${'$'}byte_start" count="${'$'}selected_count" status=none
    [ "${'$'}(wc -c < "${'$'}selected" | tr -d ' ')" -le "${'$'}max_bytes" ] || opened_error invalid_protocol_state
    fd_after=${'$'}(stat -Lc '%d:%i:%s:%y' -- "${'$'}fd_path"); sha_after=${'$'}(sha256sum -- "${'$'}fd_path" | cut -d' ' -f1)
    [ "${'$'}fd_before" = "${'$'}fd_after" ] && [ "${'$'}sha_before" = "${'$'}sha_after" ] || opened_error source_changed
    if [ "${'$'}next_offset" -gt 0 ]; then truncated=1; next_value=${'$'}next_offset; else truncated=0; next_value=''; fi
    content_b64=${'$'}(base64 -w0 < "${'$'}selected")
    printf '%s\nrequest_id=%s\nactual_path_b64=%s\nstart_line=%s\nend_line=%s\ntotal_lines=%s\ntruncated=%s\nnext_offset=%s\nsha256=%s\ncontent_b64=%s\n' \
        "${'$'}protocol_marker" "${'$'}request_id" "${'$'}actual_b64" "${'$'}requested_offset" "${'$'}end_line" "${'$'}total_lines" "${'$'}truncated" "${'$'}next_value" "${'$'}sha_before" "${'$'}content_b64"
""".trimIndent()

internal val TERMUX_BYTE_READ_SCRIPT = """
    protocol_marker=RIKKAHUB_FILE_BYTES_V1
    ${TERMUX_FILE_COMMON_SCRIPT}
    requested_offset=${'$'}3; requested_length=${'$'}4
    total=${'$'}(wc -c < "${'$'}fd_path" | tr -d ' ')
    offset=${'$'}requested_offset; [ "${'$'}offset" -le "${'$'}total" ] || offset=${'$'}total
    remaining=${'$'}((total - offset)); actual=${'$'}requested_length; [ "${'$'}actual" -le "${'$'}remaining" ] || actual=${'$'}remaining
    dd if="${'$'}fd_path" of="${'$'}selected" bs=1 skip="${'$'}offset" count="${'$'}actual" status=none
    [ "${'$'}(wc -c < "${'$'}selected" | tr -d ' ')" -eq "${'$'}actual" ] || opened_error invalid_protocol_state
    fd_after=${'$'}(stat -Lc '%d:%i:%s:%y' -- "${'$'}fd_path"); sha_after=${'$'}(sha256sum -- "${'$'}fd_path" | cut -d' ' -f1)
    [ "${'$'}fd_before" = "${'$'}fd_after" ] && [ "${'$'}sha_before" = "${'$'}sha_after" ] || opened_error source_changed
    data_b64=${'$'}(base64 -w0 < "${'$'}selected")
    printf '%s\nrequest_id=%s\nactual_path_b64=%s\noffset=%s\nactual_length=%s\ntotal_bytes=%s\nsha256=%s\ndata_b64=%s\n' \
        "${'$'}protocol_marker" "${'$'}request_id" "${'$'}actual_b64" "${'$'}offset" "${'$'}actual" "${'$'}total" "${'$'}sha_before" "${'$'}data_b64"
""".trimIndent()

internal data class TermuxTextReadRequest(val path: String, val offset: Long, val limit: Int, val lineNumbers: Boolean)
internal data class TermuxByteReadRequest(val path: String, val offset: Long, val length: Int)
internal data class PublicInputError(val code: String, val path: String? = null)
internal sealed class PublicInputResult<out T> {
    data class Ok<T>(val value: T) : PublicInputResult<T>()
    data class Error(val value: PublicInputError) : PublicInputResult<Nothing>()
}

private fun JsonPrimitive.strictString(): String? = content.takeIf { isString }
private fun JsonPrimitive.strictIntegerLong(): Long? {
    if (isString || !Regex("-?(0|[1-9][0-9]*)").matches(content)) return null
    return content.toLongOrNull()
}
private fun JsonPrimitive.strictIntegerInt(): Int? = strictIntegerLong()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
private fun JsonPrimitive.strictBoolean(): Boolean? = if (!isString) when (content) { "true" -> true; "false" -> false; else -> null } else null

internal fun parseTermuxTextReadRequest(input: JsonElement, maxLines: Int): PublicInputResult<TermuxTextReadRequest> {
    val obj = input as? JsonObject ?: return PublicInputResult.Error(PublicInputError("request_must_be_object"))
    val path = (obj["path"] as? JsonPrimitive)?.strictString()
    val validPathForError = path
    val unknown = obj.keys - setOf("path", "offset", "limit", "line_numbers")
    if (unknown.isNotEmpty()) return PublicInputResult.Error(PublicInputError("unknown_fields:${unknown.sorted().joinToString(",")}", validPathForError))
    if (path == null) return PublicInputResult.Error(PublicInputError("path_must_be_string"))
    when (val lexical = resolveTermuxPathLexically(path)) {
        is TermuxPathResolution.Error -> return PublicInputResult.Error(PublicInputError(lexical.code, path))
        is TermuxPathResolution.Ok -> Unit
    }
    val offset = when (val raw = obj["offset"]) {
        null -> 1L
        is JsonPrimitive -> raw.strictIntegerLong() ?: return PublicInputResult.Error(PublicInputError("offset_must_be_integer", path))
        else -> return PublicInputResult.Error(PublicInputError("offset_must_be_integer", path))
    }
    if (offset <= 0L) return PublicInputResult.Error(PublicInputError("offset_must_be_positive", path))
    val limit = when (val raw = obj["limit"]) {
        null, JsonNull -> maxLines
        is JsonPrimitive -> raw.strictIntegerInt() ?: return PublicInputResult.Error(PublicInputError("limit_must_be_integer_or_null", path))
        else -> return PublicInputResult.Error(PublicInputError("limit_must_be_integer_or_null", path))
    }
    if (limit <= 0) return PublicInputResult.Error(PublicInputError("limit_must_be_positive", path))
    val lineNumbers = when (val raw = obj["line_numbers"]) {
        null -> true
        is JsonPrimitive -> raw.strictBoolean() ?: return PublicInputResult.Error(PublicInputError("line_numbers_must_be_boolean", path))
        else -> return PublicInputResult.Error(PublicInputError("line_numbers_must_be_boolean", path))
    }
    return PublicInputResult.Ok(TermuxTextReadRequest(path, offset, clampTermuxTextLimit(limit, maxLines), lineNumbers))
}

internal fun parseTermuxByteReadRequest(input: JsonElement, maxBytes: Int): PublicInputResult<TermuxByteReadRequest> {
    val obj = input as? JsonObject ?: return PublicInputResult.Error(PublicInputError("request_must_be_object"))
    val path = (obj["path"] as? JsonPrimitive)?.strictString()
    val unknown = obj.keys - setOf("path", "offset", "length")
    if (unknown.isNotEmpty()) return PublicInputResult.Error(PublicInputError("unknown_fields:${unknown.sorted().joinToString(",")}", path))
    if (path == null) return PublicInputResult.Error(PublicInputError("path_must_be_string"))
    when (val lexical = resolveTermuxPathLexically(path)) {
        is TermuxPathResolution.Error -> return PublicInputResult.Error(PublicInputError(lexical.code, path))
        is TermuxPathResolution.Ok -> Unit
    }
    val offset = when (val raw = obj["offset"]) {
        null -> 0L
        is JsonPrimitive -> raw.strictIntegerLong() ?: return PublicInputResult.Error(PublicInputError("offset_must_be_integer", path))
        else -> return PublicInputResult.Error(PublicInputError("offset_must_be_integer", path))
    }
    if (offset < 0L) return PublicInputResult.Error(PublicInputError("offset_must_be_nonnegative", path))
    val length = when (val raw = obj["length"]) {
        null -> DEFAULT_TERMUX_BYTE_READ_LENGTH
        is JsonPrimitive -> raw.strictIntegerInt() ?: return PublicInputResult.Error(PublicInputError("length_must_be_integer", path))
        else -> return PublicInputResult.Error(PublicInputError("length_must_be_integer", path))
    }
    if (length <= 0) return PublicInputResult.Error(PublicInputError("length_must_be_positive", path))
    return PublicInputResult.Ok(TermuxByteReadRequest(path, offset, clampTermuxByteLength(length, maxBytes)))
}

internal suspend fun readTermuxTextFile(context: Context, request: TermuxTextReadRequest, maxBytes: Int, maxLines: Int): TermuxFileProtocolResult<TermuxTextReadEnvelope> {
    val requestId = UUID.randomUUID().toString()
    val result = runCommandCapture(
        ctx = context, executable = "/data/data/com.termux/files/usr/bin/bash",
        arguments = arrayOf("-c", TERMUX_TEXT_READ_SCRIPT, "rikka-read-file", requestId, encodeTermuxPath(request.path), request.offset.toString(), request.limit.toString(), maxBytes.toString()),
        workingDir = TERMUX_FILES_HOME, timeoutMs = TERMUX_FILE_READ_TIMEOUT_MS, spoolOutput = false,
    )
    return when (result) {
        is CaptureResult.Success -> if (result.exitCode == 0) parseTermuxTextReadEnvelope(result.stdout, requestId, request.offset, request.limit, maxBytes, maxLines) else TermuxFileProtocolResult.Error("termux_read_failed", result.stderr.take(1_000))
        is CaptureResult.Timeout -> TermuxFileProtocolResult.Error("read_timeout")
        is CaptureResult.Denied -> TermuxFileProtocolResult.Error("termux_permission_denied")
        is CaptureResult.OtherError -> TermuxFileProtocolResult.Error("termux_read_failed", result.message)
    }
}

internal suspend fun readTermuxFileBytes(context: Context, request: TermuxByteReadRequest, maxBytes: Int): TermuxFileProtocolResult<TermuxByteReadEnvelope> {
    val requestId = UUID.randomUUID().toString()
    val result = runCommandCapture(
        ctx = context, executable = "/data/data/com.termux/files/usr/bin/bash",
        arguments = arrayOf("-c", TERMUX_BYTE_READ_SCRIPT, "rikka-read-file-bytes", requestId, encodeTermuxPath(request.path), request.offset.toString(), request.length.toString()),
        workingDir = TERMUX_FILES_HOME, timeoutMs = TERMUX_FILE_READ_TIMEOUT_MS, spoolOutput = false,
    )
    return when (result) {
        is CaptureResult.Success -> if (result.exitCode == 0) parseTermuxByteReadEnvelope(result.stdout, requestId, request.offset, request.length, maxBytes) else TermuxFileProtocolResult.Error("termux_read_failed", result.stderr.take(1_000))
        is CaptureResult.Timeout -> TermuxFileProtocolResult.Error("read_timeout")
        is CaptureResult.Denied -> TermuxFileProtocolResult.Error("termux_permission_denied")
        is CaptureResult.OtherError -> TermuxFileProtocolResult.Error("termux_read_failed", result.message)
    }
}

private fun termuxFileError(result: TermuxFileProtocolResult.Error, index: Int? = null, path: String? = null) = buildJsonObject {
    index?.let { put("index", it) }; path?.let { put("path", it) }
    put("error", result.code); result.detail?.let { put("detail", it) }; result.actualPath?.let { put("actual_path", it) }
    result.totalLines?.let { put("total_lines", it) }; result.sha256?.let { put("sha256", it) }
    put("recovery", when (result.code) {
        "line_too_large" -> "Use termux_read_file_bytes; the first requested line exceeds the configured byte cap."
        "offset_beyond_eof" -> "Use a valid line offset; total_lines and sha256 are returned when available."
        "batch_limit_reached" -> "Continue remaining files in another batch."
        "source_changed" -> "The opened file changed during the read. Retry after the writer is idle."
        else -> "Check the request, path, and Termux RUN_COMMAND integration."
    })
}
private fun inputError(error: PublicInputError, index: Int? = null) = termuxFileError(TermuxFileProtocolResult.Error(error.code), index, error.path)
internal fun termuxBatchWrapper(results: JsonArray, error: JsonElement = JsonNull) = buildJsonObject {
    put("results", results); put("error", error)
}
internal fun simulateBatchPolicy(items: List<PublicInputResult<TermuxTextReadRequest>>, lineBudget: Int, byteBudget: Int): List<Pair<Int, String?>> {
    var lines = lineBudget; var bytes = byteBudget; var exhausted = false
    return items.mapIndexed { index, item ->
        when {
            exhausted || lines == 0 || bytes == 0 -> { exhausted = true; index to "batch_limit_reached" }
            item is PublicInputResult.Error -> index to item.value.code
            else -> { lines--; bytes--; index to null }
        }
    }
}

private fun textReadJson(value: TermuxTextReadEnvelope, lineNumbers: Boolean, index: Int? = null) = buildJsonObject {
    index?.let { put("index", it) }; put("actual_path", value.actualPath); put("content", renderTermuxText(value.contentBytes, value.startLine, lineNumbers))
    put("start_line", value.startLine); put("end_line", value.endLine); put("total_lines", value.totalLines); put("truncated", value.truncated)
    put("next_offset", value.nextOffset?.let(::JsonPrimitive) ?: JsonNull); put("sha256", value.sha256); put("error", JsonNull)
}

internal fun textReadSchema() = buildJsonObject {
    put("path", buildJsonObject { put("type", "string"); put("description", "Relative/~ paths use Termux HOME; /tmp maps to Termux TMPDIR.") })
    put("offset", buildJsonObject { put("type", "integer"); put("description", "1-indexed line offset; default 1.") })
    put("limit", buildJsonObject { put("type", buildJsonArray { add("integer"); add("null") }); put("description", "Positive line limit; null/default uses configured maximum.") })
    put("line_numbers", buildJsonObject { put("type", "boolean"); put("description", "Display-only prefixes; never copy them into edit match text.") })
}

fun termuxReadFileTool(context: Context): Tool = Tool(
    name = "termux_read_file",
    description = "Read a normal Termux file through one stable opened-file descriptor. Relative and ~/ paths resolve from Termux HOME; /tmp maps to TMPDIR with lexical escapes rejected. Exact full-file SHA-256 is checked before and after bounded selection; changes return source_changed. Line numbers are display-only. Use pagination and termux_read_file_bytes for binary data or line_too_large.",
    parameters = { InputSchema.Obj(textReadSchema(), listOf("path")) },
    execute = { input ->
        when (val parsed = parseTermuxTextReadRequest(input, TermuxRuntime.textReadMaxLines)) {
            is PublicInputResult.Error -> listOf(UIMessagePart.Text(inputError(parsed.value).toString()))
            is PublicInputResult.Ok -> {
                val json = when (val result = readTermuxTextFile(context, parsed.value, TermuxRuntime.textReadMaxBytes, TermuxRuntime.textReadMaxLines)) {
                    is TermuxFileProtocolResult.Ok -> textReadJson(result.value, parsed.value.lineNumbers)
                    is TermuxFileProtocolResult.Error -> termuxFileError(result)
                }
                listOf(UIMessagePart.Text(json.toString()))
            }
        }
    },
)

fun termuxReadFileBytesTool(context: Context): Tool = Tool(
    name = "termux_read_file_bytes",
    description = "Read bounded raw bytes through one stable opened Termux file descriptor. Returns canonical Base64, exact full-file SHA-256, and byte pagination; offsets beyond EOF clamp to EOF. Path semantics match termux_read_file.",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("path", buildJsonObject { put("type", "string") }); put("offset", buildJsonObject { put("type", "integer") }); put("length", buildJsonObject { put("type", "integer") })
    }, listOf("path")) },
    execute = { input ->
        when (val parsed = parseTermuxByteReadRequest(input, TermuxRuntime.textReadMaxBytes)) {
            is PublicInputResult.Error -> listOf(UIMessagePart.Text(inputError(parsed.value).toString()))
            is PublicInputResult.Ok -> {
                val json = when (val result = readTermuxFileBytes(context, parsed.value, TermuxRuntime.textReadMaxBytes)) {
                    is TermuxFileProtocolResult.Error -> termuxFileError(result)
                    is TermuxFileProtocolResult.Ok -> result.value.let { value ->
                        val next = value.offset + value.data.size
                        buildJsonObject { put("actual_path", value.actualPath); put("data", Base64.getEncoder().encodeToString(value.data)); put("offset", value.offset); put("actual_length", value.data.size); put("total_bytes", value.totalBytes); put("eof", next >= value.totalBytes); put("next_offset", if (next < value.totalBytes) JsonPrimitive(next) else JsonNull); put("sha256", value.sha256); put("error", JsonNull) }
                    }
                }
                listOf(UIMessagePart.Text(json.toString()))
            }
        }
    },
)

fun termuxReadFilesTool(context: Context): Tool = Tool(
    name = "termux_read_files",
    description = "Batch-read 1 to 20 Termux text files in order through stable opened-file views. Returns {results,error}; every item has index. Item errors do not abort siblings. Configured raw-byte and line budgets apply across successful items; remaining items return batch_limit_reached.",
    parameters = { InputSchema.Obj(buildJsonObject { put("reads", buildJsonObject { put("type", "array"); put("minItems", 1); put("maxItems", MAX_TERMUX_BATCH_READS); put("items", buildJsonObject { put("type", "object"); put("properties", textReadSchema()); put("required", buildJsonArray { add("path") }); put("additionalProperties", false) }) }) }, listOf("reads")) },
    execute = { input ->
        val obj = input as? JsonObject
        val reads = obj?.get("reads") as? JsonArray
        val topError = when {
            obj == null -> "request_must_be_object"
            (obj.keys - setOf("reads")).isNotEmpty() -> "unknown_fields:${(obj.keys - setOf("reads")).sorted().joinToString(",")}"
            reads == null -> "reads_must_be_array"
            reads.isEmpty() -> "reads_must_not_be_empty"
            reads.size > MAX_TERMUX_BATCH_READS -> "too_many_reads"
            else -> null
        }
        if (topError != null) return@Tool listOf(UIMessagePart.Text(termuxBatchWrapper(buildJsonArray {}, termuxFileError(TermuxFileProtocolResult.Error(topError))).toString()))
        var budget = TermuxBatchBudget(TermuxRuntime.textReadMaxLines, TermuxRuntime.textReadMaxBytes)
        var exhausted = false
        val results = buildJsonArray {
            reads!!.forEachIndexed { index, item ->
                if (exhausted || budget.remainingLines == 0 || budget.remainingBytes == 0) {
                    add(termuxFileError(TermuxFileProtocolResult.Error("batch_limit_reached"), index)); exhausted = true
                } else when (val parsed = parseTermuxTextReadRequest(item, budget.remainingLines)) {
                    is PublicInputResult.Error -> add(inputError(parsed.value, index))
                    is PublicInputResult.Ok -> {
                        val request = parsed.value.copy(limit = parsed.value.limit.coerceAtMost(budget.remainingLines))
                        when (val result = readTermuxTextFile(context, request, budget.remainingBytes, budget.remainingLines)) {
                            is TermuxFileProtocolResult.Error -> add(termuxFileError(result, index))
                            is TermuxFileProtocolResult.Ok -> {
                                add(textReadJson(result.value, request.lineNumbers, index))
                                val usedLines = if (result.value.totalLines == 0L) 0 else (result.value.endLine - result.value.startLine + 1L).toInt()
                                budget = consumeTermuxBatchBudget(budget, usedLines, result.value.contentBytes.size) ?: TermuxBatchBudget(0, 0)
                            }
                        }
                    }
                }
            }
        }
        listOf(UIMessagePart.Text(termuxBatchWrapper(results).toString()))
    },
)
