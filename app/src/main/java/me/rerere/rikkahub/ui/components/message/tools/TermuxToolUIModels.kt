package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

internal enum class TermuxUIBadge {
    APPLIED,
    DRY_RUN,
    NO_CHANGE,
    ERROR,
    ROLLED_BACK,
    ROLLBACK_FAILED,
    TRUNCATED,
}

internal data class TermuxWriteUIModel(
    val path: String,
    val actualPath: String?,
    val content: String,
    val append: Boolean,
    val bytesWritten: Long?,
    val totalBytes: Long?,
    val error: String?,
    val detail: String?,
    val recovery: String?,
    val currentSha256: String?,
    val badges: List<TermuxUIBadge>,
)

internal data class TermuxEditFileUIModel(
    val path: String,
    val actualPath: String?,
    val state: String?,
    val changed: Boolean?,
    val diff: String?,
    val error: String?,
    val diagnostics: List<String>,
    val badges: List<TermuxUIBadge>,
)

private val EDIT_MODES = setOf("replace_match", "insert_before", "insert_after")
private val EDIT_STATUSES = setOf("failed", "aborted", "applied", "matched_no_change")
private val EDIT_STRATEGIES = setOf("exact", "fuzzy", "indent")
private val EDIT_REASONS = setOf(
    "ambiguous_match",
    "match_not_found",
    "work_budget_exceeded",
    "result_too_large",
    "overlapping_or_same_position",
)

internal data class TermuxEditUIModel(
    val single: Boolean,
    val files: List<TermuxEditFileUIModel>,
    val state: String?,
    val error: String?,
    val detail: String?,
    val diff: String?,
    val badges: List<TermuxUIBadge>,
)

internal data class BoundedTextPreview(
    val text: String,
    val truncated: Boolean,
)

internal data class BoundedDiffPreviews(
    val previews: List<BoundedTextPreview?>,
    val truncated: Boolean,
)

private data class ExpectedEdit(
    val index: Int,
    val mode: String,
)

private data class ParsedDiagnostic(
    val index: Int,
    val mode: String,
    val status: String,
    val text: String,
)

private val WRITE_TOOL_NAMES = setOf("termux_write_file", "termux_append_file")
private val EDIT_TOOL_NAMES = setOf("termux_edit_file", "termux_edit_files")
private val TOP_LEVEL_EDIT_STATES = setOf("applied", "dry_run", "no_change", "error")
private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
private val FILE_EDIT_STATES = setOf(
    "published",
    "dry_run",
    "no_change",
    "error",
    "aborted",
    "not_published",
    "rolled_back",
    "rollback_failed",
)

private fun parseEarlyTermuxWriteError(
    toolName: String,
    arguments: JsonElement,
    content: JsonElement?,
): TermuxWriteUIModel? {
    val output = content as? JsonObject ?: return null
    if (output.boolean("success") != false || output.boolean("ok") != false ||
        output.strictString("error").isNullOrEmpty() || output.strictString("recovery").isNullOrEmpty() ||
        !output.optionalStringIsValid("path") || !output.optionalStringIsValid("actual_path") ||
        !output.optionalStringIsValid("detail") || !output.optionalStringIsValid("current_sha256") ||
        !output.optionalStringIsValid("recovery")
    ) return null
    val input = arguments as? JsonObject
    val reportedPath = output.strictString("path")
    val inputPath = input?.strictString("path")
    if (reportedPath != null && inputPath != null && reportedPath != inputPath) return null
    val path = reportedPath ?: inputPath ?: "file"
    val currentSha = output.strictString("current_sha256")
    if (currentSha != null && !currentSha.matches(SHA256_REGEX)) return null
    return TermuxWriteUIModel(
        path = path,
        actualPath = output.strictString("actual_path"),
        content = input?.strictString("content").orEmpty(),
        append = toolName == "termux_append_file",
        bytesWritten = null,
        totalBytes = null,
        error = output.strictString("error"),
        detail = output.strictString("detail"),
        recovery = output.strictString("recovery"),
        currentSha256 = currentSha,
        badges = listOf(TermuxUIBadge.ERROR),
    )
}

internal fun parseTermuxWriteUIModel(
    toolName: String,
    arguments: JsonElement,
    content: JsonElement?,
): TermuxWriteUIModel? {
    if (toolName !in WRITE_TOOL_NAMES) return null
    parseEarlyTermuxWriteError(toolName, arguments, content)?.let { return it }
    val input = arguments as? JsonObject ?: return null
    val path = input.strictString("path") ?: return null
    val text = input.strictString("content") ?: return null
    val append = toolName == "termux_append_file"
    if (content == null) {
        return TermuxWriteUIModel(
            path = path,
            actualPath = null,
            content = text,
            append = append,
            bytesWritten = null,
            totalBytes = null,
            error = null,
            detail = null,
            recovery = null,
            currentSha256 = null,
            badges = emptyList(),
        )
    }

    val output = content as? JsonObject ?: return null
    val success = output.boolean("success") ?: return null
    val ok = output.boolean("ok") ?: return null
    if (success != ok) return null

    if (success) {
        val operation = output.strictString("operation") ?: return null
        val actualPath = output.strictString("actual_path")?.takeIf(String::isNotEmpty) ?: return null
        val pathSha = output.strictString("path_request_sha256") ?: return null
        val contentSha = output.strictString("content_sha256") ?: return null
        val resultSha = output.strictString("sha256") ?: return null
        val bytesWritten = output.long("bytes_written") ?: return null
        val totalBytes = output.long("total_bytes") ?: return null
        val expectedBytes = text.toByteArray(Charsets.UTF_8).size.toLong()
        val valid = operation == if (append) "append" else "write" &&
            pathSha == sha256(path.toByteArray(Charsets.UTF_8)) &&
            contentSha == sha256(text.toByteArray(Charsets.UTF_8)) &&
            resultSha.matches(SHA256_REGEX) && bytesWritten == expectedBytes &&
            totalBytes >= bytesWritten && (append || totalBytes == bytesWritten) &&
            (append || resultSha == contentSha) && output["error"] is JsonNull
        if (!valid) return null
        return TermuxWriteUIModel(
            path = path,
            actualPath = actualPath,
            content = text,
            append = append,
            bytesWritten = bytesWritten,
            totalBytes = totalBytes,
            error = null,
            detail = null,
            recovery = null,
            currentSha256 = null,
            badges = listOf(TermuxUIBadge.APPLIED),
        )
    }

    if (output.strictString("path") != path) return null
    val error = output.strictString("error")?.takeIf(String::isNotEmpty) ?: return null
    val recovery = output.strictString("recovery")?.takeIf(String::isNotEmpty) ?: return null
    if (!output.optionalStringIsValid("actual_path") ||
        !output.optionalStringIsValid("detail") ||
        !output.optionalStringIsValid("current_sha256") ||
        !output.optionalStringIsValid("recovery")
    ) return null
    val currentSha = output.strictString("current_sha256")
    if (currentSha != null && !currentSha.matches(SHA256_REGEX)) return null
    return TermuxWriteUIModel(
        path = path,
        actualPath = output.strictString("actual_path"),
        content = text,
        append = append,
        bytesWritten = null,
        totalBytes = null,
        error = error,
        detail = output.strictString("detail"),
        recovery = recovery,
        currentSha256 = currentSha,
        badges = listOf(TermuxUIBadge.ERROR),
    )
}

private fun parseEarlyTermuxEditError(
    toolName: String,
    arguments: JsonElement,
    content: JsonElement?,
): TermuxEditUIModel? {
    val output = content as? JsonObject ?: return null
    if (output.boolean("success") != false || output.boolean("ok") != false ||
        output.boolean("applied") != false || output.boolean("changed") != false ||
        output.strictString("state") != "error" || output.strictString("error").isNullOrEmpty() ||
        output["batch_aborted"] != null || output["diff_truncated"] != null ||
        output["files"] != null || output["rollback_restored"] != null ||
        !output.optionalStringIsValid("path") || !output.optionalStringIsValid("detail") ||
        output.boolean("dry_run") == null
    ) return null
    val input = arguments as? JsonObject
    val single = toolName == "termux_edit_file"
    val outputDryRun = output.boolean("dry_run")!!
    val inputDryRun = input?.get("dry_run")?.let { input.boolean("dry_run") }
    if (input?.get("dry_run") != null && inputDryRun == null || inputDryRun != null && inputDryRun != outputDryRun) return null
    val reportedPath = output.strictString("path")
    val inputPaths = if (single) {
        listOfNotNull(input?.strictString("path"))
    } else {
        (input?.get("files") as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonObject)?.strictString("path")
        }
    }
    if (reportedPath != null && inputPaths.isNotEmpty() && reportedPath !in inputPaths) return null
    val paths = when {
        inputPaths.isNotEmpty() -> inputPaths
        reportedPath != null -> listOf(reportedPath)
        else -> emptyList()
    }
    val error = output.strictString("error")!!
    val files = paths.map { path ->
        TermuxEditFileUIModel(
            path = path,
            actualPath = null,
            state = if (path == reportedPath) "error" else null,
            changed = null,
            diff = null,
            error = error.takeIf { path == reportedPath },
            diagnostics = emptyList(),
            badges = if (path == reportedPath) listOf(TermuxUIBadge.ERROR) else emptyList(),
        )
    }
    return TermuxEditUIModel(
        single = single,
        files = files,
        state = "error",
        error = error,
        detail = output.strictString("detail"),
        diff = null,
        badges = listOf(TermuxUIBadge.ERROR),
    )
}

internal fun parseTermuxEditUIModel(
    toolName: String,
    arguments: JsonElement,
    content: JsonElement?,
    metadataDiff: String?,
): TermuxEditUIModel? {
    if (toolName !in EDIT_TOOL_NAMES) return null
    parseEarlyTermuxEditError(toolName, arguments, content)?.let { return it }
    val input = arguments as? JsonObject ?: return null
    val single = toolName == "termux_edit_file"
    val inputSpecs = if (single) {
        listOf(input)
    } else {
        val specs = input["files"] as? JsonArray ?: return null
        if (specs.isEmpty()) return null
        specs.map { it as? JsonObject ?: return null }
    }
    val inputPaths = inputSpecs.map { it.strictString("path") ?: return null }
    val requestedDryRun = when {
        input["dry_run"] == null -> false
        else -> input.boolean("dry_run") ?: return null
    }

    if (content == null) {
        val pending = inputPaths.map { path ->
            TermuxEditFileUIModel(path, null, null, null, null, null, emptyList(), emptyList())
        }
        return TermuxEditUIModel(single, pending, null, null, null, null, emptyList())
    }

    val output = content as? JsonObject ?: return null
    val success = output.boolean("success") ?: return null
    val ok = output.boolean("ok") ?: return null
    val applied = output.boolean("applied") ?: return null
    val changed = output.boolean("changed") ?: return null
    val dryRun = output.boolean("dry_run") ?: return null
    val state = output.strictString("state") ?: return null
    if (success != ok || state !in TOP_LEVEL_EDIT_STATES || dryRun != requestedDryRun) return null

    val batchAborted = output.boolean("batch_aborted")
    val diffTruncated = output.boolean("diff_truncated")
    val error = output.strictString("error")
    val detail = output.strictString("detail")
    if (!output.optionalStringIsValid("error") || !output.optionalStringIsValid("detail")) return null

    val validState = if (success) {
        batchAborted == false && diffTruncated != null && output["error"] is JsonNull && when (state) {
            "applied" -> applied && changed && !dryRun
            "dry_run" -> !applied && dryRun
            "no_change" -> !applied && !changed && !dryRun
            else -> false
        }
    } else {
        state == "error" && !applied && !error.isNullOrEmpty() && when {
            batchAborted == null && diffTruncated == null -> true
            batchAborted == true && diffTruncated != null -> true
            else -> false
        }
    }
    if (!validState) return null

    if (!success && batchAborted == null && diffTruncated == null) return null
    val expectedEdits = inputSpecs.map { parseExpectedEdits(it["edits"]) ?: return null }

    val rollbackRestored = output.boolean("rollback_restored")
    if (rollbackRestored != null && (!changed || dryRun || !(single && !success && batchAborted == true))) return null
    val reportedFiles = output["files"] as? JsonArray
    if (rollbackRestored != null && reportedFiles != null) return null
    val files = if (single) {
        val reportedPath = output.strictString("path")
        if (success && reportedPath != inputPaths.single()) return null
        if (reportedPath != null && reportedPath != inputPaths.single()) return null
        if (!output.optionalStringIsValid("path") ||
            !output.optionalStringIsValid("actual_path") ||
            !output.optionalStringIsValid("diff")
        ) return null
        val isFullResponse = batchAborted != null || diffTruncated != null
        if (success || isFullResponse) {
            val actualPath = output.strictString("actual_path")?.takeIf(String::isNotEmpty) ?: return null
            val sourceSha = output.strictString("source_sha256") ?: return null
            val resultSha = output.strictString("result_sha256") ?: return null
            val replacements = output.long("replacements") ?: return null
            if (actualPath.isEmpty() || !sourceSha.matches(SHA256_REGEX) ||
                !resultSha.matches(SHA256_REGEX) || replacements < 0 ||
                changed != (sourceSha != resultSha)
            ) return null
        }
        val diagnostics = parseEditDiagnostics(output["results"], expectedEdits.single()) ?: return null
        val replacements = output.long("replacements")
        if (isFullResponse && replacements != diagnostics.count { it.status == "applied" }.toLong()) return null
        val diff = output.strictString("diff") ?: metadataDiff
        listOf(
            TermuxEditFileUIModel(
                path = reportedPath ?: inputPaths.single(),
                actualPath = output.strictString("actual_path"),
                state = state,
                changed = changed,
                diff = diff,
                error = error,
                diagnostics = diagnostics.map { it.text },
                badges = badgesForState(state, rollbackRestored).withNoChange(changed),
            )
        )
    } else if (reportedFiles == null) {
        return null
    } else {
        if (reportedFiles.size != inputPaths.size) return null
        reportedFiles.mapIndexed { index, element ->
            parseEditFile(
                element as? JsonObject ?: return null,
                inputPaths[index],
                expectedEdits[index],
                success,
                dryRun,
            )
                ?: return null
        }
    }

    if (files.mapNotNull { it.changed }.any { it } != changed) return null
    if (files.any { TermuxUIBadge.APPLIED in it.badges } != applied) return null
    val perFileDiff = files.mapNotNull { it.diff }.joinToString("\n").takeIf(String::isNotEmpty)
    val badges = badgesForState(state, rollbackRestored).toMutableList().apply {
        if (state == "dry_run" && !changed) add(TermuxUIBadge.NO_CHANGE)
        if (diffTruncated == true) add(TermuxUIBadge.TRUNCATED)
    }.distinct()
    return TermuxEditUIModel(
        single = single,
        files = files,
        state = state,
        error = error,
        detail = detail,
        diff = perFileDiff ?: metadataDiff,
        badges = badges,
    )
}

private fun parseEditFile(
    item: JsonObject,
    expectedPath: String,
    expectedEdits: List<ExpectedEdit>,
    success: Boolean,
    topDryRun: Boolean,
): TermuxEditFileUIModel? {
    if (item.strictString("path") != expectedPath ||
        !item.optionalStringIsValid("actual_path") ||
        !item.optionalStringIsValid("diff") ||
        !item.optionalStringIsValid("error")
    ) return null
    val state = item.strictString("state")?.takeIf { it in FILE_EDIT_STATES } ?: return null
    val changed = item.boolean("changed") ?: return null
    val actualPath = item.strictString("actual_path")?.takeIf(String::isNotEmpty) ?: return null
    val sourceSha = item.strictString("source_sha256") ?: return null
    val resultSha = item.strictString("result_sha256") ?: return null
    if (!sourceSha.matches(SHA256_REGEX) || !resultSha.matches(SHA256_REGEX) ||
        changed != (sourceSha != resultSha)
    ) return null
    val applied = item.boolean("applied") ?: return null
    val dryRun = item.boolean("dry_run") ?: return null
    val rollback = item.boolean("rollback_restored")
    val rollbackValid = when (state) {
        "rolled_back" -> rollback == true
        "rollback_failed" -> rollback == false
        else -> rollback == null
    }
    if (!rollbackValid) return null
    val validState = when {
        success && topDryRun && state == "dry_run" -> !applied && changed && dryRun
        success && topDryRun && state == "no_change" -> !applied && !changed && dryRun
        success && !topDryRun && state == "published" -> applied && changed && !dryRun
        success && !topDryRun && state == "no_change" -> !applied && !changed && !dryRun
        !success && topDryRun && state == "dry_run" -> !applied && changed && dryRun
        !success && topDryRun && state == "no_change" -> !applied && !changed && dryRun
        !success && topDryRun && state == "error" -> !applied && !changed && dryRun
        !success && !topDryRun && state == "rolled_back" -> !applied && changed && !dryRun
        !success && !topDryRun && state == "rollback_failed" -> !applied && changed && !dryRun
        !success && !topDryRun && state == "not_published" -> !applied && !dryRun
        !success && !topDryRun && state == "no_change" -> !applied && !changed && !dryRun
        !success && !topDryRun && state == "error" -> !applied && !changed && !dryRun
        !success && !topDryRun && state == "aborted" -> !applied && changed && !dryRun
        else -> false
    }
    if (!validState) return null
    val itemError = item.strictString("error")
    if (state == "error" && itemError.isNullOrEmpty() || state != "error" && itemError != null) return null
    return TermuxEditFileUIModel(
        path = expectedPath,
        actualPath = actualPath,
        state = state,
        changed = changed,
        diff = item.strictString("diff"),
        error = itemError,
        diagnostics = parseEditDiagnostics(item["edits"], expectedEdits)?.map { it.text } ?: return null,
        badges = badgesForState(state, rollback).withNoChange(changed),
    )
}

private fun parseExpectedEdits(element: JsonElement?): List<ExpectedEdit>? {
    val edits = element as? JsonArray ?: return null
    if (edits.isEmpty() || edits.size > 100) return null
    return edits.mapIndexed { index, raw ->
        val item = raw as? JsonObject ?: return null
        val mode = when (item.strictString("mode")) {
            "replace_match" -> "replace_match"
            "insert_before", "insert_before_match" -> "insert_before"
            "insert_after", "insert_after_match" -> "insert_after"
            else -> return null
        }
        fun aliased(canonical: String, compatibility: String): String? {
            if (!item.optionalStringIsValid(canonical) || !item.optionalStringIsValid(compatibility)) return null
            val first = item.strictString(canonical)
            val second = item.strictString(compatibility)
            if (first != null && second != null && first != second) return null
            return first ?: second
        }
        aliased("match_text", "matchText")?.takeIf(String::isNotEmpty) ?: return null
        aliased("write_text", "writeText") ?: return null
        ExpectedEdit(index, mode)
    }
}

private fun parseEditDiagnostics(
    element: JsonElement?,
    expected: List<ExpectedEdit>,
): List<ParsedDiagnostic>? {
    val array = element as? JsonArray ?: return null
    if (array.size != expected.size) return null
    return array.mapIndexed { expectedIndex, entry ->
        val item = entry as? JsonObject ?: return null
        val index = item.long("index")?.takeIf { it in 0..99 } ?: return null
        val mode = item.strictString("mode")?.takeIf { it in EDIT_MODES } ?: return null
        if (index != expectedIndex.toLong() || mode != expected[expectedIndex].mode) return null
        val status = item.strictString("status")?.takeIf { it in EDIT_STATUSES } ?: return null
        val matched = item.boolean("matched") ?: return null
        val strategy = item.strictString("strategy")
        val reason = item.strictString("reason")
        val closestLine = item.long("closest_match_line")
        val similarity = item.double("similarity")
        val nearbyText = item.strictString("nearby_text")
        val finalStatusValid = when (status) {
            "applied", "matched_no_change" -> matched && strategy in EDIT_STRATEGIES && reason == null
            "failed" -> reason != null && (matched == (reason == "overlapping_or_same_position"))
            "aborted" -> when {
                matched -> strategy in EDIT_STRATEGIES && reason == null
                else -> reason in setOf("work_budget_exceeded", "result_too_large")
            }
            else -> false
        }
        if (!item.optionalStringIsValid("strategy") || !item.optionalStringIsValid("reason") ||
            !item.optionalStringIsValid("nearby_text") || strategy != null && strategy !in EDIT_STRATEGIES ||
            reason != null && reason !in EDIT_REASONS ||
            closestLine != null && closestLine < 1 || similarity != null && (similarity !in 0.0..1.0 || !similarity.isFinite()) ||
            nearbyText != null && nearbyText.length > 500 || !finalStatusValid
        ) return null
        val candidates = item["candidate_lines"]?.let { raw ->
            val list = raw as? JsonArray ?: return null
            if (list.size > 5) return null
            list.map { candidate ->
                val value = candidate as? JsonObject ?: return null
                val line = value.long("line")?.takeIf { it >= 1 } ?: return null
                val text = value.strictString("text")?.takeIf { it.length <= 500 } ?: return null
                "$line: $text"
            }
        }.orEmpty()
        val text = buildString {
            append('#').append(index + 1).append(' ').append(mode).append(": ").append(status)
            append(if (matched) " [matched]" else " [unmatched]")
            strategy?.let { append(" via ").append(it) }
            reason?.let { append(" (").append(it).append(')') }
            closestLine?.let { append("; closest line ").append(it) }
            similarity?.let { append("; similarity ").append("%.3f".format(it)) }
            if (candidates.isNotEmpty()) append("; candidates ").append(candidates.joinToString(" | "))
            nearbyText?.let { append("; nearby: ").append(it) }
        }.take(2_000)
        ParsedDiagnostic(index.toInt(), mode, status, text)
    }
}

private fun List<TermuxUIBadge>.withNoChange(changed: Boolean?): List<TermuxUIBadge> =
    if (changed == false) (this + TermuxUIBadge.NO_CHANGE).distinct() else this

private fun badgesForState(state: String?, rollbackRestored: Boolean?): List<TermuxUIBadge> {
    val result = mutableListOf<TermuxUIBadge>()
    when (state) {
        "applied", "published" -> result += TermuxUIBadge.APPLIED
        "dry_run" -> result += TermuxUIBadge.DRY_RUN
        "no_change" -> result += TermuxUIBadge.NO_CHANGE
        "rolled_back" -> result += TermuxUIBadge.ROLLED_BACK
        "rollback_failed" -> result += TermuxUIBadge.ROLLBACK_FAILED
        "error", "aborted", "not_published" -> result += TermuxUIBadge.ERROR
    }
    when (rollbackRestored) {
        true -> result += TermuxUIBadge.ROLLED_BACK
        false -> result += TermuxUIBadge.ROLLBACK_FAILED
        null -> Unit
    }
    return result.distinct()
}

internal fun boundedTextPreview(
    text: String,
    maxChars: Int,
    maxLines: Int = Int.MAX_VALUE,
): BoundedTextPreview {
    require(maxChars >= 0)
    require(maxLines >= 1)
    var end = 0
    var line = 1
    while (end < text.length && end < maxChars) {
        val char = text[end]
        if (char == '\r' || char == '\n') {
            if (line >= maxLines) break
            val width = if (char == '\r' && end + 1 < text.length && text[end + 1] == '\n') 2 else 1
            if (end + width > maxChars) break
            end += width
            line++
        } else {
            end++
        }
    }
    if (end in 1 until text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) {
        end--
    }
    return BoundedTextPreview(text.substring(0, end), end < text.length)
}

internal fun boundedDiffPreviews(
    diffs: List<String?>,
    maxChars: Int,
    maxLines: Int,
): BoundedDiffPreviews {
    require(maxChars >= 0)
    require(maxLines >= 1)
    var remainingChars = maxChars
    var remainingLines = maxLines
    var truncated = false
    val previews = diffs.map { diff ->
        when {
            diff == null -> null
            remainingChars == 0 || remainingLines == 0 -> {
                truncated = true
                BoundedTextPreview("", true)
            }
            else -> {
                val preview = boundedTextPreview(diff, remainingChars, remainingLines)
                remainingChars -= preview.text.length
                remainingLines -= physicalLineCount(preview.text)
                if (preview.truncated) {
                    truncated = true
                    remainingChars = 0
                    remainingLines = 0
                }
                preview
            }
        }
    }
    return BoundedDiffPreviews(previews, truncated)
}

private fun physicalLineCount(text: String): Int {
    if (text.isEmpty()) return 0
    var lines = 1
    var index = 0
    while (index < text.length) {
        when (text[index]) {
            '\r' -> {
                lines++
                if (index + 1 < text.length && text[index + 1] == '\n') index++
            }
            '\n' -> lines++
        }
        index++
    }
    return lines
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it.toInt() and 0xff)
    }

private fun JsonObject.strictString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.optionalStringIsValid(key: String): Boolean {
    val value = this[key] ?: return true
    return value is JsonNull || value is JsonPrimitive && value.isString
}

private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun JsonObject.double(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
