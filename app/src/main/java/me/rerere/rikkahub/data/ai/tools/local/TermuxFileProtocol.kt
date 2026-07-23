package me.rerere.rikkahub.data.ai.tools.local

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

internal const val DEFAULT_TERMUX_BYTE_READ_LENGTH = 4_096
internal const val MAX_TERMUX_BATCH_READS = 20
internal const val TERMUX_PROTOCOL_ENVELOPE_CHARS = 6_000
internal const val MAX_TERMUX_FILE_PROTOCOL_CHARS = 99_000
private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
private val ERROR_PATTERN = Regex("^[a-z][a-z0-9_]{0,63}$")
private val UUID_V4_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

internal fun maxTermuxProtocolChars(rawBytes: Int, actualPathBytes: Int = MAX_TERMUX_ACTUAL_PATH_BYTES): Int =
    ((rawBytes + 2) / 3) * 4 + ((actualPathBytes + 2) / 3) * 4 + TERMUX_PROTOCOL_ENVELOPE_CHARS

internal data class TermuxTextReadEnvelope(
    val requestId: String,
    val actualPath: String,
    val contentBytes: ByteArray,
    val startLine: Long,
    val endLine: Long,
    val totalLines: Long,
    val truncated: Boolean,
    val nextOffset: Long?,
    val sha256: String,
)

internal data class TermuxByteReadEnvelope(
    val requestId: String,
    val actualPath: String,
    val offset: Long,
    val data: ByteArray,
    val totalBytes: Long,
    val sha256: String,
)

internal sealed class TermuxFileProtocolResult<out T> {
    data class Ok<T>(val value: T) : TermuxFileProtocolResult<T>()
    data class Error(
        val code: String,
        val detail: String? = null,
        val actualPath: String? = null,
        val totalLines: Long? = null,
        val sha256: String? = null,
    ) : TermuxFileProtocolResult<Nothing>()
}

internal fun clampTermuxTextLimit(value: Int?, maxLines: Int): Int =
    (value ?: maxLines).coerceIn(1, maxLines)
internal fun clampTermuxByteLength(value: Int?, maxBytes: Int): Int =
    (value ?: DEFAULT_TERMUX_BYTE_READ_LENGTH).coerceIn(1, maxBytes)
internal fun isValidSha256(value: String): Boolean = SHA256_PATTERN.matches(value)
internal fun isValidRequestId(value: String): Boolean = UUID_V4_PATTERN.matches(value)
internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun strictBase64(value: String, maxDecodedBytes: Int): ByteArray? =
    decodeCanonicalBase64(value, maxDecodedBytes)

private fun strictProtocolUtf8(value: String, maxBytes: Int, absolutePath: Boolean = false): String? =
    strictBase64(value, maxBytes)?.let { decodeStrictUtf8(it, absolutePath) }

private data class ParsedProtocol(val values: Map<String, String>)

private val RESOLVER_PROTOCOL_ERRORS = setOf("path_too_large", "invalid_path_encoding", "blank_path", "tmp_escape", "invalid_path")
private val PATH_PROTOCOL_ERRORS = setOf("not_found", "not_regular_file", "open_failed", "temp_failed", "unsafe_temp_path", "stat_failed", "source_changed", "invalid_protocol_state")
private val PAGED_PROTOCOL_ERRORS = setOf("offset_beyond_eof", "line_too_large")

private fun parseFields(
    text: String,
    marker: String,
    successFields: Set<String>,
    expectedRequestId: String,
    allowedErrorCodes: Set<String>,
): TermuxFileProtocolResult<ParsedProtocol> {
    if (!isValidRequestId(expectedRequestId)) return TermuxFileProtocolResult.Error("invalid_request_id")
    if (text.length > MAX_TERMUX_FILE_PROTOCOL_CHARS) return TermuxFileProtocolResult.Error("protocol_too_large")
    val lines = text.removeSuffix("\n").split('\n')
    if (lines.firstOrNull() != marker) return TermuxFileProtocolResult.Error("invalid_protocol")
    val values = linkedMapOf<String, String>()
    val allFields = successFields + setOf("request_id", "error", "actual_path_b64", "total_lines", "sha256")
    for (line in lines.drop(1)) {
        val separator = line.indexOf('=')
        if (separator <= 0) return TermuxFileProtocolResult.Error("invalid_protocol")
        val key = line.substring(0, separator)
        if (key !in allFields || values.put(key, line.substring(separator + 1)) != null) {
            return TermuxFileProtocolResult.Error("invalid_protocol")
        }
    }
    if (values["request_id"] != expectedRequestId || !isValidRequestId(values["request_id"].orEmpty())) {
        return TermuxFileProtocolResult.Error("request_id_mismatch")
    }
    values["error"]?.let { code ->
        if (!ERROR_PATTERN.matches(code) || code !in allowedErrorCodes) return TermuxFileProtocolResult.Error("invalid_protocol")
        val required = when (code) {
            in RESOLVER_PROTOCOL_ERRORS -> setOf("request_id", "error")
            in PATH_PROTOCOL_ERRORS -> setOf("request_id", "error", "actual_path_b64")
            in PAGED_PROTOCOL_ERRORS -> setOf("request_id", "error", "actual_path_b64", "total_lines", "sha256")
            else -> return TermuxFileProtocolResult.Error("invalid_protocol")
        }
        if (values.keys != required) return TermuxFileProtocolResult.Error("invalid_protocol")
        val path = values["actual_path_b64"]?.let {
            strictProtocolUtf8(it, MAX_TERMUX_ACTUAL_PATH_BYTES, absolutePath = true)
                ?: return TermuxFileProtocolResult.Error("invalid_protocol")
        }
        val totalLines = values["total_lines"]?.let {
            strictUnsignedLong(it) ?: return TermuxFileProtocolResult.Error("invalid_protocol")
        }
        val sha = values["sha256"]?.also {
            if (!isValidSha256(it)) return TermuxFileProtocolResult.Error("invalid_protocol")
        }
        return TermuxFileProtocolResult.Error(code, actualPath = path, totalLines = totalLines, sha256 = sha)
    }
    if (values.keys != successFields) return TermuxFileProtocolResult.Error("invalid_protocol")
    return TermuxFileProtocolResult.Ok(ParsedProtocol(values))
}

private fun strictUnsignedLong(value: String): Long? =
    value.takeIf { it.matches(Regex("^(0|[1-9][0-9]*)$")) }?.toLongOrNull()
private fun strictBoolean(value: String): Boolean? = when (value) { "0" -> false; "1" -> true; else -> null }

internal fun parseTermuxTextReadEnvelope(
    text: String,
    expectedRequestId: String,
    requestedOffset: Long,
    requestedLimit: Int,
    maxBytes: Int,
    maxLines: Int,
): TermuxFileProtocolResult<TermuxTextReadEnvelope> {
    val fields = setOf(
        "request_id", "actual_path_b64", "start_line", "end_line", "total_lines",
        "truncated", "next_offset", "sha256", "content_b64",
    )
    val parsed = parseFields(
        text, "RIKKAHUB_FILE_TEXT_V1", fields, expectedRequestId,
        RESOLVER_PROTOCOL_ERRORS + PATH_PROTOCOL_ERRORS + PAGED_PROTOCOL_ERRORS,
    )
    if (parsed !is TermuxFileProtocolResult.Ok) return parsed as TermuxFileProtocolResult.Error
    val values = parsed.value.values
    val path = strictProtocolUtf8(values.getValue("actual_path_b64"), MAX_TERMUX_ACTUAL_PATH_BYTES, true)
        ?: return TermuxFileProtocolResult.Error("invalid_protocol")
    val start = strictUnsignedLong(values.getValue("start_line")) ?: return TermuxFileProtocolResult.Error("invalid_protocol")
    val end = strictUnsignedLong(values.getValue("end_line")) ?: return TermuxFileProtocolResult.Error("invalid_protocol")
    val total = strictUnsignedLong(values.getValue("total_lines")) ?: return TermuxFileProtocolResult.Error("invalid_protocol")
    val truncated = strictBoolean(values.getValue("truncated")) ?: return TermuxFileProtocolResult.Error("invalid_protocol")
    val next = values.getValue("next_offset").let {
        if (it.isEmpty()) null else strictUnsignedLong(it) ?: return TermuxFileProtocolResult.Error("invalid_protocol")
    }
    val sha = values.getValue("sha256").takeIf(::isValidSha256) ?: return TermuxFileProtocolResult.Error("invalid_protocol")
    val content = strictBase64(values.getValue("content_b64"), maxBytes) ?: return TermuxFileProtocolResult.Error("invalid_protocol")
    if (start != requestedOffset || requestedLimit !in 1..maxLines) return TermuxFileProtocolResult.Error("invalid_protocol")
    if (total == 0L) {
        if (requestedOffset != 1L || end != 0L || content.isNotEmpty() || truncated || next != null || sha != sha256Hex(byteArrayOf())) {
            return TermuxFileProtocolResult.Error("invalid_protocol")
        }
    } else {
        if (requestedOffset > total || end < start || end > total) return TermuxFileProtocolResult.Error("invalid_protocol")
        val selected = end - start + 1L
        if (selected !in 1..requestedLimit.toLong()) return TermuxFileProtocolResult.Error("invalid_protocol")
        if (splitTextLinesPreservingEndings(content).size.toLong() != selected) return TermuxFileProtocolResult.Error("invalid_protocol")
        val expectedTruncated = end < total
        if (truncated != expectedTruncated || next != if (expectedTruncated) end + 1L else null) {
            return TermuxFileProtocolResult.Error("invalid_protocol")
        }
        if (truncated && (content.isEmpty() || content.last() != '\n'.code.toByte())) {
            return TermuxFileProtocolResult.Error("invalid_protocol")
        }
        if (start == 1L && end == total && !truncated && sha != sha256Hex(content)) {
            return TermuxFileProtocolResult.Error("invalid_protocol")
        }
    }
    return TermuxFileProtocolResult.Ok(
        TermuxTextReadEnvelope(expectedRequestId, path, content, start, end, total, truncated, next, sha)
    )
}

internal fun parseTermuxByteReadEnvelope(
    text: String,
    expectedRequestId: String,
    requestedOffset: Long,
    requestedLength: Int,
    maxBytes: Int,
): TermuxFileProtocolResult<TermuxByteReadEnvelope> {
    val fields = setOf("request_id", "actual_path_b64", "offset", "actual_length", "total_bytes", "sha256", "data_b64")
    val parsed = parseFields(
        text, "RIKKAHUB_FILE_BYTES_V1", fields, expectedRequestId,
        RESOLVER_PROTOCOL_ERRORS + PATH_PROTOCOL_ERRORS,
    )
    if (parsed !is TermuxFileProtocolResult.Ok) return parsed as TermuxFileProtocolResult.Error
    val values = parsed.value.values
    val path = strictProtocolUtf8(values.getValue("actual_path_b64"), MAX_TERMUX_ACTUAL_PATH_BYTES, true)
        ?: return TermuxFileProtocolResult.Error("invalid_protocol")
    val offset = strictUnsignedLong(values.getValue("offset")) ?: return TermuxFileProtocolResult.Error("invalid_protocol")
    val actualLength = strictUnsignedLong(values.getValue("actual_length"))?.takeIf { it <= maxBytes }?.toInt()
        ?: return TermuxFileProtocolResult.Error("invalid_protocol")
    val total = strictUnsignedLong(values.getValue("total_bytes")) ?: return TermuxFileProtocolResult.Error("invalid_protocol")
    val sha = values.getValue("sha256").takeIf(::isValidSha256) ?: return TermuxFileProtocolResult.Error("invalid_protocol")
    val data = strictBase64(values.getValue("data_b64"), maxBytes)?.takeIf { it.size == actualLength }
        ?: return TermuxFileProtocolResult.Error("invalid_protocol")
    if (requestedLength !in 1..maxBytes) return TermuxFileProtocolResult.Error("invalid_protocol")
    val expectedOffset = minOf(requestedOffset, total)
    val expectedLength = minOf(requestedLength.toLong(), total - expectedOffset).toInt()
    if (offset != expectedOffset || actualLength != expectedLength) return TermuxFileProtocolResult.Error("invalid_protocol")
    if (total == 0L && (offset != 0L || data.isNotEmpty() || sha != sha256Hex(byteArrayOf()))) {
        return TermuxFileProtocolResult.Error("invalid_protocol")
    }
    if (offset == 0L && total == data.size.toLong() && sha != sha256Hex(data)) {
        return TermuxFileProtocolResult.Error("invalid_protocol")
    }
    return TermuxFileProtocolResult.Ok(TermuxByteReadEnvelope(expectedRequestId, path, offset, data, total, sha))
}

internal fun splitTextLinesPreservingEndings(bytes: ByteArray): List<ByteArray> {
    if (bytes.isEmpty()) return emptyList()
    val result = mutableListOf<ByteArray>()
    var start = 0
    bytes.forEachIndexed { index, byte ->
        if (byte == '\n'.code.toByte()) { result += bytes.copyOfRange(start, index + 1); start = index + 1 }
    }
    if (start < bytes.size) result += bytes.copyOfRange(start, bytes.size)
    return result
}

internal fun renderTermuxText(bytes: ByteArray, startLine: Long, lineNumbers: Boolean): String {
    if (!lineNumbers) return bytes.toString(StandardCharsets.UTF_8)
    return buildString {
        splitTextLinesPreservingEndings(bytes).forEachIndexed { index, line ->
            append(startLine + index).append("\t").append(line.toString(StandardCharsets.UTF_8))
        }
    }
}

internal data class TermuxBatchBudget(val remainingLines: Int, val remainingBytes: Int)
internal fun consumeTermuxBatchBudget(budget: TermuxBatchBudget, usedLines: Int, usedBytes: Int): TermuxBatchBudget? {
    if (usedLines < 0 || usedBytes < 0 || usedLines > budget.remainingLines || usedBytes > budget.remainingBytes) return null
    return TermuxBatchBudget(budget.remainingLines - usedLines, budget.remainingBytes - usedBytes)
}
