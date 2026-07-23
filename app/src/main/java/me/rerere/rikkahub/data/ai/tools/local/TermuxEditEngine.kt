package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

internal const val MAX_TERMUX_EDIT_FILES = 20
internal const val MAX_TERMUX_EDITS_PER_FILE = 100
internal const val MAX_TERMUX_EDIT_INPUT_BYTES = MAX_TERMUX_TRANSFER_BYTES
internal const val MAX_TERMUX_EDIT_TOTAL_PATH_BYTES = 32 * 1024
internal const val MAX_TERMUX_EDIT_TOTAL_SOURCE_BYTES = 16 * 1024 * 1024
internal const val MAX_TERMUX_EDIT_TOTAL_RESULT_BYTES = 16 * 1024 * 1024
internal const val MAX_TERMUX_EDIT_DIAGNOSTIC_CHARS = 500
private const val MAX_TERMUX_EDIT_MATCH_CANDIDATES = 6
private const val MAX_TERMUX_EDIT_SIMILARITY_CHARS = 512

internal enum class TermuxEditMode(val wireName: String) {
    REPLACE("replace_match"), BEFORE("insert_before"), AFTER("insert_after");

    companion object {
        fun parse(value: String): TermuxEditMode? = when (value) {
            "replace_match" -> REPLACE
            "insert_before", "insert_before_match" -> BEFORE
            "insert_after", "insert_after_match" -> AFTER
            else -> null
        }
    }
}

internal data class TermuxEditSpec(val mode: TermuxEditMode, val matchText: String, val writeText: String)
internal data class TermuxEditFileSpec(
    val path: String,
    val pathBytes: ByteArray,
    val edits: List<TermuxEditSpec>,
    val expectedSha256: String?,
)
internal data class TermuxEditRequest(val files: List<TermuxEditFileSpec>, val dryRun: Boolean, val single: Boolean)

internal data class TermuxEditDiagnostic(
    val index: Int,
    val mode: String,
    val status: String,
    val matched: Boolean,
    val strategy: String? = null,
    val reason: String? = null,
    val candidateLines: List<Pair<Int, String>> = emptyList(),
    val closestLine: Int? = null,
    val similarity: Double? = null,
    val nearbyText: String? = null,
)

internal data class TermuxEditOutcome(
    val success: Boolean,
    val original: String,
    val edited: String,
    val changed: Boolean,
    val diagnostics: List<TermuxEditDiagnostic>,
    val error: String? = null,
)

private fun JsonPrimitive.strictString(): String? = content.takeIf { isString }
private fun JsonPrimitive.strictBoolean(): Boolean? = if (!isString) content.toBooleanStrictOrNull() else null

internal fun parseTermuxEditRequest(input: JsonElement, single: Boolean): PublicInputResult<TermuxEditRequest> {
    val root = input as? JsonObject ?: return PublicInputResult.Error(PublicInputError("request_must_be_object"))
    val allowed = if (single) setOf("path", "edits", "dry_run", "expected_sha256") else setOf("files", "dry_run")
    (root.keys - allowed).takeIf { it.isNotEmpty() }?.let {
        return PublicInputResult.Error(PublicInputError("unknown_fields:${it.sorted().joinToString(",")}"))
    }
    val dryRun = when (val raw = root["dry_run"]) {
        null -> false
        is JsonPrimitive -> raw.strictBoolean()
            ?: return PublicInputResult.Error(PublicInputError("dry_run_must_be_boolean"))
        else -> return PublicInputResult.Error(PublicInputError("dry_run_must_be_boolean"))
    }
    val objects = if (single) listOf(JsonObject(root - "dry_run")) else {
        val files = root["files"] as? JsonArray
            ?: return PublicInputResult.Error(PublicInputError("files_must_be_array"))
        if (files.size !in 1..MAX_TERMUX_EDIT_FILES) {
            return PublicInputResult.Error(PublicInputError("files_count_out_of_range"))
        }
        files.mapIndexed { index, value ->
            value as? JsonObject
                ?: return PublicInputResult.Error(PublicInputError("files[$index]_must_be_object"))
        }
    }
    val parsed = objects.mapIndexed { index, obj ->
        parseEditFile(obj, if (single) "" else "files[$index].")
            .let { result ->
                when (result) {
                    is PublicInputResult.Ok -> result.value
                    is PublicInputResult.Error -> return result
                }
            }
    }
    val totalEditBytes = parsed.sumOf { file ->
        file.edits.sumOf { edit ->
            edit.matchText.toByteArray(StandardCharsets.UTF_8).size.toLong() +
                edit.writeText.toByteArray(StandardCharsets.UTF_8).size
        }
    }
    if (totalEditBytes > MAX_TERMUX_EDIT_INPUT_BYTES) {
        return PublicInputResult.Error(PublicInputError("edit_input_too_large"))
    }
    if (parsed.sumOf { it.pathBytes.size.toLong() } > MAX_TERMUX_EDIT_TOTAL_PATH_BYTES) {
        return PublicInputResult.Error(PublicInputError("total_path_bytes_too_large"))
    }
    val lexical = mutableSetOf<String>()
    for (file in parsed) {
        val actual = (resolveTermuxPathLexically(file.path) as? TermuxPathResolution.Ok)?.actualPath
            ?: return PublicInputResult.Error(PublicInputError("invalid_path", file.path))
        if (!lexical.add(actual)) return PublicInputResult.Error(PublicInputError("duplicate_path", file.path))
    }
    return PublicInputResult.Ok(TermuxEditRequest(parsed, dryRun, single))
}

private fun parseEditFile(obj: JsonObject, prefix: String): PublicInputResult<TermuxEditFileSpec> {
    val allowed = setOf("path", "edits", "expected_sha256")
    (obj.keys - allowed).takeIf { it.isNotEmpty() }?.let {
        return PublicInputResult.Error(PublicInputError("${prefix}unknown_fields:${it.sorted().joinToString(",")}"))
    }
    val path = (obj["path"] as? JsonPrimitive)?.strictString()
        ?: return PublicInputResult.Error(PublicInputError("${prefix}path_must_be_string"))
    val pathBytes = when (val encoded = encodeUtf8StrictBounded(path, MAX_TERMUX_PATH_BYTES)) {
        is BoundedUtf8Result.Ok -> encoded.bytes
        BoundedUtf8Result.TooLarge -> return PublicInputResult.Error(PublicInputError("${prefix}path_too_large", path))
        BoundedUtf8Result.InvalidUtf8 -> return PublicInputResult.Error(PublicInputError("${prefix}invalid_utf8", path))
    }
    when (val resolved = resolveTermuxPathLexically(path)) {
        is TermuxPathResolution.Error -> return PublicInputResult.Error(PublicInputError("${prefix}${resolved.code}", path))
        is TermuxPathResolution.Ok -> Unit
    }
    val edits = obj["edits"] as? JsonArray
        ?: return PublicInputResult.Error(PublicInputError("${prefix}edits_must_be_array", path))
    if (edits.size !in 1..MAX_TERMUX_EDITS_PER_FILE) {
        return PublicInputResult.Error(PublicInputError("${prefix}edits_count_out_of_range", path))
    }
    var editBytes = 0L
    val specs = edits.mapIndexed { editIndex, raw ->
        val edit = raw as? JsonObject
            ?: return PublicInputResult.Error(PublicInputError("${prefix}edits[$editIndex]_must_be_object", path))
        when (val result = parseEdit(edit, "$prefix" + "edits[$editIndex]")) {
            is PublicInputResult.Error -> return PublicInputResult.Error(result.value.copy(path = path))
            is PublicInputResult.Ok -> {
                val spec = result.value
                val match = encodeUtf8StrictBounded(spec.matchText, MAX_TERMUX_EDIT_INPUT_BYTES)
                val write = encodeUtf8StrictBounded(spec.writeText, MAX_TERMUX_EDIT_INPUT_BYTES)
                if (match !is BoundedUtf8Result.Ok || write !is BoundedUtf8Result.Ok) {
                    val code = if (match is BoundedUtf8Result.InvalidUtf8 || write is BoundedUtf8Result.InvalidUtf8) "edit_invalid_utf8" else "edit_input_too_large"
                    return PublicInputResult.Error(PublicInputError("$prefix$code", path))
                }
                editBytes += match.bytes.size.toLong() + write.bytes.size
                if (editBytes > MAX_TERMUX_EDIT_INPUT_BYTES) {
                    return PublicInputResult.Error(PublicInputError("${prefix}edit_input_too_large", path))
                }
                spec
            }
        }
    }
    val expected = when (val raw = obj["expected_sha256"]) {
        null, JsonNull -> null
        is JsonPrimitive -> raw.strictString()
            ?: return PublicInputResult.Error(PublicInputError("${prefix}expected_sha256_must_be_string_or_null", path))
        else -> return PublicInputResult.Error(PublicInputError("${prefix}expected_sha256_must_be_string_or_null", path))
    }
    if (expected != null && !isValidSha256(expected)) {
        return PublicInputResult.Error(PublicInputError("${prefix}invalid_expected_sha256", path))
    }
    return PublicInputResult.Ok(TermuxEditFileSpec(path, pathBytes, specs, expected))
}

private fun parseEdit(obj: JsonObject, label: String): PublicInputResult<TermuxEditSpec> {
    val allowed = setOf("mode", "match_text", "matchText", "write_text", "writeText")
    val legacy = obj.keys.intersect(setOf("old_text", "new_text", "oldText", "newText"))
    if (legacy.isNotEmpty()) return PublicInputResult.Error(PublicInputError("$label.unsupported_fields:${legacy.sorted().joinToString(",")}"))
    (obj.keys - allowed).takeIf { it.isNotEmpty() }?.let {
        return PublicInputResult.Error(PublicInputError("$label.unknown_fields:${it.sorted().joinToString(",")}"))
    }
    val modeRaw = (obj["mode"] as? JsonPrimitive)?.strictString()
        ?: return PublicInputResult.Error(PublicInputError("$label.mode_must_be_string"))
    val mode = TermuxEditMode.parse(modeRaw)
        ?: return PublicInputResult.Error(PublicInputError("$label.invalid_mode"))
    fun alias(canonical: String, compatibility: String): PublicInputResult<String> {
        val firstRaw = obj[canonical]
        val secondRaw = obj[compatibility]
        val first = (firstRaw as? JsonPrimitive)?.strictString()
        val second = (secondRaw as? JsonPrimitive)?.strictString()
        if (firstRaw != null && first == null) return PublicInputResult.Error(PublicInputError("$label.${canonical}_must_be_string"))
        if (secondRaw != null && second == null) return PublicInputResult.Error(PublicInputError("$label.${compatibility}_must_be_string"))
        if (first != null && second != null && first != second) return PublicInputResult.Error(PublicInputError("$label.conflicting_${canonical}_alias"))
        return (first ?: second)?.let { PublicInputResult.Ok(it) }
            ?: PublicInputResult.Error(PublicInputError("$label.missing_$canonical"))
    }
    val match = when (val result = alias("match_text", "matchText")) {
        is PublicInputResult.Ok -> result.value
        is PublicInputResult.Error -> return result
    }
    if (match.isEmpty()) return PublicInputResult.Error(PublicInputError("$label.empty_match_text"))
    val write = when (val result = alias("write_text", "writeText")) {
        is PublicInputResult.Ok -> result.value
        is PublicInputResult.Error -> return result
    }
    return PublicInputResult.Ok(TermuxEditSpec(mode, normalizeInputLines(match), normalizeInputLines(write)))
}

private fun normalizeInputLines(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n')

private data class Projection(val text: String, val starts: IntArray, val ends: IntArray)
private data class Match(val start: Int, val end: Int, val strategy: String, val matchedText: String)
private data class Resolved(
    val diagnosticIndex: Int,
    val start: Int,
    val end: Int,
    val replacement: String,
    val changesSource: Boolean,
)

internal const val MAX_TERMUX_EDIT_WORK_UNITS = 256L * 1024 * 1024
internal const val MAX_TERMUX_EDIT_BATCH_WORK_UNITS = 512L * 1024 * 1024

internal class TermuxEditWorkBudget(
    maxUnits: Long,
    private val parent: TermuxEditWorkBudget? = null,
) {
    private var remaining = maxUnits

    fun consume(units: Long) {
        if (units < 0 || units > remaining) throw WorkBudgetExceeded
        parent?.consume(units)
        remaining -= units
    }
}
private data object WorkBudgetExceeded : RuntimeException()
private data object ResultTooLarge : RuntimeException()

private const val MAX_TERMUX_EDIT_PROJECTION_UNITS = 8 * 1024 * 1024
private const val MAX_TERMUX_EDIT_DIAGNOSTIC_LINES = 100_000

private class IntBuffer(initialCapacity: Int) {
    private var values = IntArray(maxOf(16, minOf(initialCapacity, MAX_TERMUX_EDIT_PROJECTION_UNITS)))
    var size: Int = 0
        private set
    fun add(value: Int) {
        if (size >= MAX_TERMUX_EDIT_PROJECTION_UNITS) throw WorkBudgetExceeded
        if (size == values.size) values = values.copyOf(minOf(MAX_TERMUX_EDIT_PROJECTION_UNITS, values.size * 2))
        values[size++] = value
    }
    fun truncate(newSize: Int) { size = newSize }
    fun toArray(): IntArray = values.copyOf(size)
}

private val normalizedSingleQuotes = setOf('\u2018', '\u2019', '\u201a', '\u201b')
private val normalizedDoubleQuotes = setOf('\u201c', '\u201d', '\u201e', '\u201f')
private val normalizedDashes = setOf('\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212')
private val normalizedSpaces = setOf('\u00a0', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009', '\u200a', '\u202f', '\u205f', '\u3000')

private fun normalizedChar(c: Char): Char = when (c) {
    in normalizedSingleQuotes -> '\''
    in normalizedDoubleQuotes -> '"'
    in normalizedDashes -> '-'
    in normalizedSpaces -> ' '
    else -> c
}

/** One compact NFKC/newline projection per source; primitive arrays map projected units to raw spans. */
private fun fuzzyProjection(value: String, budget: TermuxEditWorkBudget, withSpans: Boolean): Projection {
    budget.consume(value.length.toLong())
    val out = StringBuilder(value.length)
    val starts = IntBuffer(if (withSpans) value.length else 16)
    val ends = IntBuffer(if (withSpans) value.length else 16)
    var keptLength = 0
    fun appendMapped(text: String, sourceStart: Int, sourceEnd: Int) {
        budget.consume(text.length.toLong())
        text.forEach { raw ->
            val mapped = normalizedChar(raw)
            if (out.length >= MAX_TERMUX_EDIT_PROJECTION_UNITS) throw WorkBudgetExceeded
            out.append(mapped)
            if (withSpans) { starts.add(sourceStart); ends.add(sourceEnd) }
            if (!mapped.isWhitespace()) keptLength = out.length
        }
    }
    fun trimTrailingWhitespace() {
        if (out.length > keptLength) {
            out.setLength(keptLength)
            if (withSpans) { starts.truncate(keptLength); ends.truncate(keptLength) }
        }
    }

    val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(value) }
    var segmentStart = boundaries.first()
    var segmentEnd = boundaries.next()
    while (segmentEnd != BreakIterator.DONE) {
        val separatorEnd = when {
            value[segmentStart] == '\r' && segmentStart + 1 < value.length && value[segmentStart + 1] == '\n' -> segmentStart + 2
            value[segmentStart] == '\r' || value[segmentStart] == '\n' -> segmentStart + 1
            else -> -1
        }
        when {
            separatorEnd > 0 -> {
                trimTrailingWhitespace()
                if (out.length >= MAX_TERMUX_EDIT_PROJECTION_UNITS) throw WorkBudgetExceeded
                out.append('\n')
                if (withSpans) { starts.add(segmentStart); ends.add(separatorEnd) }
                keptLength = out.length
                segmentStart = separatorEnd
                if (segmentStart >= value.length) break
                segmentEnd = boundaries.following(segmentStart)
            }
            segmentEnd == segmentStart + 1 && value[segmentStart].code < 128 -> {
                appendMapped(value[segmentStart].toString(), segmentStart, segmentEnd)
                segmentStart = segmentEnd
                segmentEnd = boundaries.next()
            }
            else -> {
                appendMapped(Normalizer.normalize(value.substring(segmentStart, segmentEnd), Normalizer.Form.NFKC), segmentStart, segmentEnd)
                segmentStart = segmentEnd
                segmentEnd = boundaries.next()
            }
        }
    }
    trimTrailingWhitespace()
    return Projection(out.toString(), if (withSpans) starts.toArray() else IntArray(0), if (withSpans) ends.toArray() else IntArray(0))
}

private fun occurrenceStarts(
    content: String,
    needle: String,
    budget: TermuxEditWorkBudget,
    allowed: (start: Int, endExclusive: Int) -> Boolean = { _, _ -> true },
): List<Int> {
    if (needle.isEmpty()) return emptyList()
    budget.consume(content.length.toLong() + needle.length)
    val prefix = IntArray(needle.length)
    var matched = 0
    for (index in 1 until needle.length) {
        while (matched > 0 && needle[index] != needle[matched]) matched = prefix[matched - 1]
        if (needle[index] == needle[matched]) matched++
        prefix[index] = matched
    }
    val result = ArrayList<Int>(MAX_TERMUX_EDIT_MATCH_CANDIDATES)
    matched = 0
    for (index in content.indices) {
        while (matched > 0 && content[index] != needle[matched]) matched = prefix[matched - 1]
        if (content[index] == needle[matched]) matched++
        if (matched == needle.length) {
            val start = index - needle.length + 1
            if (allowed(start, index + 1)) {
                result += start
                if (result.size >= MAX_TERMUX_EDIT_MATCH_CANDIDATES) return result
            }
            matched = prefix[matched - 1]
        }
    }
    return result
}

private fun isCompleteProjectedSpan(projection: Projection, start: Int, endExclusive: Int): Boolean {
    if (start < 0 || endExclusive <= start || endExclusive > projection.text.length) return false
    val startsAtClusterBoundary = start == 0 ||
        projection.starts[start - 1] != projection.starts[start] || projection.ends[start - 1] != projection.ends[start]
    val endsAtClusterBoundary = endExclusive == projection.text.length ||
        projection.starts[endExclusive - 1] != projection.starts[endExclusive] || projection.ends[endExclusive - 1] != projection.ends[endExclusive]
    return startsAtClusterBoundary && endsAtClusterBoundary
}

private class SourceMatcher(private val content: String, private val budget: TermuxEditWorkBudget) {
    private val source: Projection by lazy { fuzzyProjection(content, budget, withSpans = true) }
    private val patternCache = HashMap<String, String>()
    private val matchCache = HashMap<String, List<Match>>()
    private var lines: LineIndex? = null

    private val characterBoundaries by lazy {
        budget.consume(content.length.toLong())
        val boundaries = java.util.BitSet(content.length + 1)
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(content) }
        var offset = iterator.first()
        while (offset != BreakIterator.DONE) {
            boundaries.set(offset)
            offset = iterator.next()
        }
        boundaries
    }

    fun matches(search: String): List<Match> = matchCache.getOrPut(search) {
        if ('\n' !in search) {
            val exact = occurrenceStarts(content, search, budget) { start, end ->
                characterBoundaries[start] && characterBoundaries[end]
            }
            if (exact.isNotEmpty()) return@getOrPut exact.map { start ->
                Match(start, start + search.length, "exact", content.substring(start, start + search.length))
            }
        }
        val target = patternCache.getOrPut(search) { fuzzyProjection(search, budget, withSpans = false).text }
        if (target.isEmpty()) return@getOrPut emptyList()
        val projected = source
        val result = ArrayList<Match>()
        val starts = occurrenceStarts(projected.text, target, budget) { start, end ->
            isCompleteProjectedSpan(projected, start, end)
        }
        for (projectedStart in starts) {
            val from = projected.starts[projectedStart]
            val to = projected.ends[projectedStart + target.length - 1]
            if (result.none { it.start == from && it.end == to }) {
                val slice = content.substring(from, to)
                val exact = normalizeInputLines(slice) == search
                result += Match(from, to, if (exact) "exact" else "fuzzy", slice)
            }
        }
        result
    }

    fun indentMatches(search: String): List<Match> {
        budget.consume(content.length.toLong())
        val index = lines ?: lineIndex(content).also { lines = it }
        val targetLines = search.split('\n').map { it.trim() }
        if (targetLines.isEmpty() || targetLines.size > index.size) return emptyList()
        val targetWork = targetLines.sumOf { it.length.toLong() + 1L }
        val result = ArrayList<Match>()
        for (line in 0..index.size - targetLines.size) {
            budget.consume(targetWork)
            if (targetLines.indices.all { offset ->
                    lineEqualsTrimmed(content, index.starts[line + offset], index.ends[line + offset], targetLines[offset])
                }) {
                val start = index.starts[line]
                val end = index.ends[line + targetLines.lastIndex]
                result += Match(start, end, "indent", content.substring(start, end))
                if (result.size >= MAX_TERMUX_EDIT_MATCH_CANDIDATES) break
            }
        }
        return result
    }

    fun lineIndex(): LineIndex = lines ?: lineIndex(content).also { lines = it }
}

private data class LineIndex(val starts: IntArray, val ends: IntArray) { val size: Int get() = starts.size }

private fun lineIndex(content: String): LineIndex {
    var count = 1
    var cursor = 0
    while (cursor < content.length) {
        if (content[cursor] == '\r' || content[cursor] == '\n') {
            count++
            if (content[cursor] == '\r' && cursor + 1 < content.length && content[cursor + 1] == '\n') cursor++
        }
        cursor++
    }
    val starts = IntArray(count)
    val ends = IntArray(count)
    var line = 0
    var start = 0
    cursor = 0
    while (cursor < content.length) {
        if (content[cursor] == '\r' || content[cursor] == '\n') {
            starts[line] = start; ends[line] = cursor; line++
            if (content[cursor] == '\r' && cursor + 1 < content.length && content[cursor + 1] == '\n') cursor++
            start = cursor + 1
        }
        cursor++
    }
    starts[line] = start; ends[line] = content.length
    return LineIndex(starts, ends)
}

private fun lineEqualsTrimmed(content: String, start: Int, end: Int, target: String): Boolean {
    var from = start
    var to = end
    while (from < to && content[from].isWhitespace()) from++
    while (to > from && content[to - 1].isWhitespace()) to--
    return to - from == target.length && content.regionMatches(from, target, 0, target.length)
}

private fun leadingIndentUntilBreak(value: String): Int {
    var index = 0
    while (index < value.length && value[index] != '\n' && value[index] != '\r' && value[index].isWhitespace()) index++
    return index
}

private fun strictUtf8Length(value: String, start: Int, end: Int, maxBytes: Long): Long {
    var bytes = 0L
    var index = start
    while (index < end) {
        val c = value[index]
        bytes += when {
            c.code <= 0x7f -> 1
            c.code <= 0x7ff -> 2
            c.isHighSurrogate() && index + 1 < end && value[index + 1].isLowSurrogate() -> {
                index++
                4
            }
            c.isSurrogate() -> throw ResultTooLarge
            else -> 3
        }
        if (bytes > maxBytes) throw ResultTooLarge
        index++
    }
    return bytes
}

private fun reindent(write: String, matched: String, intended: String, budget: TermuxEditWorkBudget): String {
    val delta = leadingIndentUntilBreak(matched) - leadingIndentUntilBreak(intended)
    if (delta == 0) return write

    var estimatedChars = 0L
    var estimatedBytes = 0L
    var lineStart = 0
    while (lineStart <= write.length) {
        val lineEnd = write.indexOf('\n', lineStart).let { if (it < 0) write.length else it }
        var firstContent = lineStart
        var blank = true
        while (firstContent < lineEnd) {
            if (!write[firstContent].isWhitespace()) {
                blank = false
                break
            }
            firstContent++
        }
        if (!blank) {
            val indent = max(0, firstContent - lineStart + delta)
            estimatedChars += indent.toLong() + (lineEnd - firstContent)
            estimatedBytes += indent.toLong() + strictUtf8Length(
                write,
                firstContent,
                lineEnd,
                MAX_TERMUX_TRANSFER_BYTES - estimatedBytes - indent,
            )
        }
        if (lineEnd < write.length) {
            estimatedChars++
            estimatedBytes++
        }
        if (estimatedChars > MAX_TERMUX_TRANSFER_BYTES || estimatedBytes > MAX_TERMUX_TRANSFER_BYTES) throw ResultTooLarge
        if (lineEnd == write.length) break
        lineStart = lineEnd + 1
    }
    budget.consume(write.length.toLong() + estimatedChars)

    val output = StringBuilder(estimatedChars.toInt())
    lineStart = 0
    while (lineStart <= write.length) {
        val lineEnd = write.indexOf('\n', lineStart).let { if (it < 0) write.length else it }
        var firstContent = lineStart
        while (firstContent < lineEnd && write[firstContent].isWhitespace()) firstContent++
        if (firstContent < lineEnd) {
            repeat(max(0, firstContent - lineStart + delta)) { output.append(' ') }
            output.append(write, firstContent, lineEnd)
        }
        if (lineEnd == write.length) break
        output.append('\n')
        lineStart = lineEnd + 1
    }
    return output.toString()
}

private fun bounded(value: String, max: Int = 200): String = value.replace("\r", "\\r").take(max)

private fun ambiguity(content: String, lines: LineIndex, found: List<Match>): List<Pair<Int, String>> {
    var rangeIndex = 0
    return found.sortedBy { it.start }.take(5).map { match ->
        while (rangeIndex + 1 < lines.size && lines.starts[rangeIndex + 1] <= match.start) rangeIndex++
        rangeIndex + 1 to bounded(content.substring(lines.starts[rangeIndex], lines.ends[rangeIndex]))
    }
}

private fun closest(content: String, search: String, lines: LineIndex, budget: TermuxEditWorkBudget): Triple<Int?, Double?, String?> {
    if (lines.size > MAX_TERMUX_EDIT_DIAGNOSTIC_LINES) return Triple(null, null, null)
    budget.consume(content.length.toLong())
    val target = search.trim().substringBefore('\n')
    if (target.isEmpty()) return Triple(null, null, null)
    var bestIndex = -1
    var best = 0.0
    for (index in 0 until lines.size) {
        val lineStart = lines.starts[index]
        val lineEnd = lines.ends[index]
        budget.consume(minOf(MAX_TERMUX_EDIT_SIMILARITY_CHARS, lineEnd - lineStart).toLong())
        val ratio = similarity(target, content.substring(lineStart, lineEnd).trim())
        if (ratio > best) { best = ratio; bestIndex = index }
    }
    if (bestIndex < 0 || best < .2) return Triple(null, null, null)
    val from = max(0, bestIndex - 2)
    val to = minOf(lines.size, bestIndex + 3)
    val excerpt = (from until to).joinToString("\n") { i ->
        "${if (i == bestIndex) ">>" else "  "} ${i + 1}: ${bounded(content.substring(lines.starts[i], lines.ends[i]))}"
    }.take(MAX_TERMUX_EDIT_DIAGNOSTIC_CHARS)
    return Triple(bestIndex + 1, (best * 1000).toInt() / 1000.0, excerpt)
}

private fun similarity(a: String, b: String): Double {
    if (a == b) return 1.0
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val left = a.take(MAX_TERMUX_EDIT_SIMILARITY_CHARS)
    val right = b.take(MAX_TERMUX_EDIT_SIMILARITY_CHARS)
    if (left.length == 1 || right.length == 1) return if (left.first() == right.first()) .5 else 0.0
    val available = HashMap<Int, Int>()
    for (i in 0 until left.lastIndex) {
        val pair = (left[i].code shl 16) xor left[i + 1].code
        available[pair] = (available[pair] ?: 0) + 1
    }
    var intersection = 0
    for (i in 0 until right.lastIndex) {
        val pair = (right[i].code shl 16) xor right[i + 1].code
        val count = available[pair] ?: 0
        if (count > 0) {
            intersection++
            if (count == 1) available.remove(pair) else available[pair] = count - 1
        }
    }
    return 2.0 * intersection / (left.lastIndex + right.lastIndex)
}

internal fun applyTermuxEdits(
    original: String,
    edits: List<TermuxEditSpec>,
    maxWorkUnits: Long = MAX_TERMUX_EDIT_WORK_UNITS,
    sharedBudget: TermuxEditWorkBudget? = null,
): TermuxEditOutcome {
    val budget = TermuxEditWorkBudget(maxWorkUnits, sharedBudget)
    val resolved = mutableListOf<Resolved>()
    val diagnostics = mutableListOf<TermuxEditDiagnostic>()
    var failed = false
    try {
        val matcher = SourceMatcher(original, budget)
        edits.forEachIndexed { index, edit ->
            val direct = matcher.matches(edit.matchText)
            val found = if (direct.isNotEmpty()) direct else matcher.indentMatches(edit.matchText)
            when {
                found.size > 1 -> {
                    failed = true
                    diagnostics += TermuxEditDiagnostic(index, edit.mode.wireName, "failed", false, reason = "ambiguous_match", candidateLines = ambiguity(original, matcher.lineIndex(), found))
                }
                found.isEmpty() -> {
                    failed = true
                    val near = closest(original, edit.matchText, matcher.lineIndex(), budget)
                    diagnostics += TermuxEditDiagnostic(index, edit.mode.wireName, "failed", false, reason = "match_not_found", closestLine = near.first, similarity = near.second, nearbyText = near.third)
                }
                else -> {
                    val match = found.single()
                    val replacement = when {
                        edit.mode == TermuxEditMode.REPLACE && normalizeInputLines(match.matchedText) == edit.writeText -> match.matchedText
                        edit.mode == TermuxEditMode.REPLACE && match.strategy == "indent" ->
                            reindent(edit.writeText, match.matchedText, edit.matchText, budget)
                        else -> edit.writeText
                    }
                    val start = if (edit.mode == TermuxEditMode.AFTER) match.end else match.start
                    val end = if (edit.mode == TermuxEditMode.REPLACE) match.end else start
                    val changesSource = when (edit.mode) {
                        TermuxEditMode.REPLACE -> replacement != match.matchedText
                        TermuxEditMode.BEFORE, TermuxEditMode.AFTER -> replacement.isNotEmpty()
                    }
                    resolved += Resolved(index, start, end, replacement, changesSource)
                    diagnostics += TermuxEditDiagnostic(index, edit.mode.wireName, "matched", true, match.strategy)
                }
            }
        }
    } catch (_: WorkBudgetExceeded) {
        val completed = diagnostics.size
        for (index in completed until edits.size) diagnostics += TermuxEditDiagnostic(
            index, edits[index].mode.wireName, if (index == completed) "failed" else "aborted", false,
            reason = "work_budget_exceeded",
        )
        return TermuxEditOutcome(false, original, original, false, diagnostics.map {
            if (it.status == "matched") it.copy(status = "aborted") else it
        }, "work_budget_exceeded")
    } catch (_: ResultTooLarge) {
        val completed = diagnostics.size
        for (index in completed until edits.size) diagnostics += TermuxEditDiagnostic(
            index, edits[index].mode.wireName, if (index == completed) "failed" else "aborted", false,
            reason = "result_too_large",
        )
        return TermuxEditOutcome(false, original, original, false, diagnostics.map {
            if (it.status == "matched") it.copy(status = "aborted") else it
        }, "result_too_large")
    }

    val conflictIndexes = mutableSetOf<Int>()
    for (i in resolved.indices) for (j in i + 1 until resolved.size) {
        val a = resolved[i]; val b = resolved[j]
        val samePosition = a.start == b.start
        val overlap = when {
            a.start == a.end && b.start == b.end -> samePosition
            a.start == a.end -> a.start > b.start && a.start < b.end
            b.start == b.end -> b.start > a.start && b.start < a.end
            else -> max(a.start, b.start) < minOf(a.end, b.end)
        }
        if (samePosition || overlap) { conflictIndexes += a.diagnosticIndex; conflictIndexes += b.diagnosticIndex }
    }
    if (conflictIndexes.isNotEmpty()) {
        failed = true
        conflictIndexes.forEach { index -> diagnostics[index] = diagnostics[index].copy(status = "failed", reason = "overlapping_or_same_position") }
    }
    if (failed) return TermuxEditOutcome(false, original, original, false, diagnostics.map {
        if (it.matched && it.status == "matched") it.copy(status = "aborted") else it
    }, "atomic_edit_aborted")

    val ordered = resolved.sortedBy { it.start }
    val estimated = original.length.toLong() + ordered.sumOf { it.replacement.length.toLong() - (it.end - it.start) }
    if (estimated < 0 || estimated > MAX_TERMUX_TRANSFER_BYTES.toLong()) {
        return TermuxEditOutcome(false, original, original, false, diagnostics.map { it.copy(status = "aborted") }, "result_too_large")
    }
    val builder = StringBuilder(estimated.toInt())
    var cursor = 0
    ordered.forEach { edit ->
        builder.append(original, cursor, edit.start)
        builder.append(edit.replacement)
        cursor = edit.end
    }
    builder.append(original, cursor, original.length)
    val edited = builder.toString()
    val changed = edited != original
    val changedIndexes = resolved.asSequence().filter { it.changesSource }.map { it.diagnosticIndex }.toSet()
    return TermuxEditOutcome(true, original, edited, changed, diagnostics.map {
        it.copy(status = if (it.index in changedIndexes) "applied" else "matched_no_change")
    })
}

internal data class TermuxTextBytes(
    val text: String,
    val bom: Boolean,
    val lineEnding: String,
    val originalBytes: ByteArray,
)

internal fun decodeTermuxEditSource(bytes: ByteArray): TermuxTextBytes? {
    val bom = bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()
    val body = if (bom) bytes.copyOfRange(3, bytes.size) else bytes
    val text = decodeStrictUtf8AllowNul(body) ?: return null
    var crlf = 0; var lf = 0; var cr = 0; var index = 0
    while (index < text.length) {
        when {
            text[index] == '\r' && index + 1 < text.length && text[index + 1] == '\n' -> { crlf++; index += 2 }
            text[index] == '\r' -> { cr++; index++ }
            text[index] == '\n' -> { lf++; index++ }
            else -> index++
        }
    }
    val kinds = listOf(crlf, lf, cr).count { it > 0 }
    val lineEnding = when {
        kinds > 1 -> "MIXED"
        crlf > 0 -> "CRLF"
        cr > 0 -> "CR"
        else -> "LF"
    }
    return TermuxTextBytes(text, bom, lineEnding, bytes)
}

private fun decodeStrictUtf8AllowNul(bytes: ByteArray): String? = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
} catch (_: Exception) { null }

internal fun encodeTermuxEditResult(source: TermuxTextBytes, text: String): ByteArray? {
    if (text == source.text) return source.originalBytes
    val encoded = when (val result = encodeUtf8StrictBounded(text, MAX_TERMUX_TRANSFER_BYTES)) {
        is BoundedUtf8Result.Ok -> result.bytes
        else -> return null
    }
    if (encoded.size + (if (source.bom) 3 else 0) > MAX_TERMUX_TRANSFER_BYTES) return null
    return if (source.bom) byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + encoded else encoded
}
