package me.rerere.rikkahub.data.ai.tools.local

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

internal const val TERMUX_TRANSFER_CHUNK_BYTES = 24 * 1024
internal const val MAX_TERMUX_TRANSFER_BYTES = 4 * 1024 * 1024
internal const val TERMUX_TRANSFER_TTL_MINUTES = 24 * 60
private const val MAX_TRANSFER_PROTOCOL_CHARS = 12_000
private val TRANSFER_ERROR_PATTERN = Regex("^[a-z][a-z0-9_]{0,63}$")
private val TRANSFER_ERROR_CODES = setOf(
    "invalid_chunk_metadata", "transfer_too_large", "chunk_too_large", "invalid_chunk_base64",
    "unsafe_transfer_state", "invalid_chunk_size", "duplicate_chunk", "missing_transfer",
    "transfer_mismatch", "out_of_order_chunk",
)
private val BARE_WRITE_ERROR_CODES = setOf(
    "invalid_operation", "invalid_create_only", "invalid_expected_sha256", "conflicting_guards",
    "missing_transfer", "unsafe_transfer_state", "incomplete_transfer", "transfer_size_mismatch",
    "transfer_sha_mismatch", "path_too_large", "invalid_path_encoding", "blank_path", "tmp_escape",
    "invalid_path",
)
private val PATH_WRITE_ERROR_CODES = setOf(
    "parent_create_failed", "parent_not_directory", "symlink_rejected", "unsafe_lock",
    "not_regular_file", "open_failed", "stat_failed", "hash_failed", "already_exists",
    "temp_failed", "unsafe_temp_path", "copy_failed", "mode_failed", "source_changed",
    "data_sync_failed", "publish_failed", "noreplace_unsupported", "post_publish_sync_failed", "publication_changed",
)

/** Bounded strict UTF-8 result; no allocation is sized from untrusted encoded output. */
internal sealed class BoundedUtf8Result {
    data class Ok(val bytes: ByteArray) : BoundedUtf8Result()
    data object TooLarge : BoundedUtf8Result()
    data object InvalidUtf8 : BoundedUtf8Result()
}

internal fun encodeUtf8StrictBounded(value: String, maxBytes: Int): BoundedUtf8Result {
    require(maxBytes >= 0)
    // Every UTF-16 code unit needs at least one UTF-8 byte. This avoids touching the encoder
    // or allocating a byte buffer for obviously oversized attacker-controlled strings.
    if (value.length > maxBytes) return BoundedUtf8Result.TooLarge
    val encoder = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val capacity = when {
        maxBytes == 0 -> 0
        value.length > maxBytes / 3 -> maxBytes
        else -> value.length * 3
    }
    val output = ByteBuffer.allocate(capacity)
    val input = CharBuffer.wrap(value)
    val encoded = encoder.encode(input, output, true)
    if (encoded.isOverflow) return BoundedUtf8Result.TooLarge
    if (encoded.isError) return BoundedUtf8Result.InvalidUtf8
    val flushed = encoder.flush(output)
    if (flushed.isOverflow) return BoundedUtf8Result.TooLarge
    if (flushed.isError) return BoundedUtf8Result.InvalidUtf8
    output.flip()
    return BoundedUtf8Result.Ok(ByteArray(output.remaining()).also(output::get))
}

/** Compatibility helper remains bounded by the public 4 MiB transfer ceiling. */
internal fun encodeUtf8Strict(value: String): ByteArray? =
    (encodeUtf8StrictBounded(value, MAX_TERMUX_TRANSFER_BYTES) as? BoundedUtf8Result.Ok)?.bytes

internal data class TermuxTransferChunk(
    val index: Int,
    val count: Int,
    val totalBytes: Int,
    val fullSha256: String,
    val data: ByteArray,
) {
    val canonicalBase64: String get() = Base64.getEncoder().encodeToString(data)
}

internal data class TermuxTransferAck(val requestId: String, val index: Int)
internal data class TermuxWriteEnvelope(
    val requestId: String,
    val operation: String,
    val actualPath: String,
    val pathRequestSha256: String,
    val contentSha256: String,
    val bytesWritten: Long,
    val totalBytes: Long,
    val sha256: String,
)

internal sealed class TermuxTransferProtocolResult<out T> {
    data class Ok<T>(val value: T) : TermuxTransferProtocolResult<T>()
    data class Error(
        val code: String,
        val detail: String? = null,
        val actualPath: String? = null,
        val currentSha256: String? = null,
    ) : TermuxTransferProtocolResult<Nothing>()
}

internal fun splitTermuxTransfer(content: String): List<TermuxTransferChunk> = when (
    val encoded = encodeUtf8StrictBounded(content, MAX_TERMUX_TRANSFER_BYTES)
) {
    is BoundedUtf8Result.Ok -> splitTermuxTransferBytes(encoded.bytes)
    BoundedUtf8Result.TooLarge -> throw IllegalArgumentException("transfer_too_large")
    BoundedUtf8Result.InvalidUtf8 -> throw IllegalArgumentException("content_invalid_utf8")
}

internal fun splitTermuxTransferBytes(bytes: ByteArray): List<TermuxTransferChunk> {
    require(bytes.size <= MAX_TERMUX_TRANSFER_BYTES) { "transfer_too_large" }
    val sha = sha256Hex(bytes)
    // Int-safe because the hard cap is 4 MiB. Each copy is independently bounded to 24 KiB.
    val count = maxOf(1, (bytes.size + TERMUX_TRANSFER_CHUNK_BYTES - 1) / TERMUX_TRANSFER_CHUNK_BYTES)
    return List(count) { index ->
        val start = index * TERMUX_TRANSFER_CHUNK_BYTES
        val end = minOf(bytes.size, start + TERMUX_TRANSFER_CHUNK_BYTES)
        TermuxTransferChunk(index, count, bytes.size, sha, bytes.copyOfRange(start, end))
    }
}

private fun strictDecimalInt(value: String): Int? =
    value.takeIf { it.matches(Regex("^(0|[1-9][0-9]*)$")) }?.toIntOrNull()
private fun strictDecimalLong(value: String): Long? =
    value.takeIf { it.matches(Regex("^(0|[1-9][0-9]*)$")) }?.toLongOrNull()

private fun parseTransferFields(
    text: String,
    marker: String,
    allowed: Set<String>,
): TermuxTransferProtocolResult<Map<String, String>> {
    if (text.length > MAX_TRANSFER_PROTOCOL_CHARS) return TermuxTransferProtocolResult.Error("protocol_too_large")
    val lines = text.removeSuffix("\n").split('\n')
    if (lines.firstOrNull() != marker) return TermuxTransferProtocolResult.Error("invalid_protocol")
    val values = linkedMapOf<String, String>()
    for (line in lines.drop(1)) {
        val separator = line.indexOf('=')
        if (separator <= 0) return TermuxTransferProtocolResult.Error("invalid_protocol")
        val key = line.substring(0, separator)
        if (key !in allowed || values.put(key, line.substring(separator + 1)) != null) {
            return TermuxTransferProtocolResult.Error("invalid_protocol")
        }
    }
    return TermuxTransferProtocolResult.Ok(values)
}

internal fun parseTermuxTransferAck(
    text: String,
    expectedRequestId: String,
    expectedIndex: Int,
): TermuxTransferProtocolResult<TermuxTransferAck> {
    if (!isValidRequestId(expectedRequestId)) return TermuxTransferProtocolResult.Error("invalid_request_id")
    val parsed = parseTransferFields(text, "RIKKAHUB_TRANSFER_V1", setOf("request_id", "index", "error"))
    if (parsed !is TermuxTransferProtocolResult.Ok) return parsed as TermuxTransferProtocolResult.Error
    val values = parsed.value
    values["error"]?.let { error ->
        if (values.keys != setOf("request_id", "error") || values["request_id"] != expectedRequestId ||
            !TRANSFER_ERROR_PATTERN.matches(error) || error !in TRANSFER_ERROR_CODES
        ) return TermuxTransferProtocolResult.Error("invalid_protocol")
        return TermuxTransferProtocolResult.Error(error)
    }
    if (values.keys != setOf("request_id", "index") || values["request_id"] != expectedRequestId) {
        return TermuxTransferProtocolResult.Error("request_id_mismatch")
    }
    val index = strictDecimalInt(values.getValue("index"))
        ?: return TermuxTransferProtocolResult.Error("invalid_protocol")
    if (index != expectedIndex) return TermuxTransferProtocolResult.Error("response_mismatch")
    return TermuxTransferProtocolResult.Ok(TermuxTransferAck(expectedRequestId, index))
}

internal fun parseTermuxWriteEnvelope(
    text: String,
    expectedRequestId: String,
    expectedOperation: String,
    expectedPathRequestSha256: String,
    expectedContentSha256: String,
    expectedBytesWritten: Int,
): TermuxTransferProtocolResult<TermuxWriteEnvelope> {
    if (!isValidRequestId(expectedRequestId)) return TermuxTransferProtocolResult.Error("invalid_request_id")
    if (expectedOperation !in setOf("write", "append") || !isValidSha256(expectedPathRequestSha256) ||
        !isValidSha256(expectedContentSha256) || expectedBytesWritten < 0
    ) return TermuxTransferProtocolResult.Error("invalid_expectation")
    val allowed = setOf(
        "request_id", "operation", "actual_path_b64", "path_request_sha256", "content_sha256",
        "bytes_written", "total_bytes", "sha256", "error", "current_sha256",
    )
    val parsed = parseTransferFields(text, "RIKKAHUB_WRITE_V1", allowed)
    if (parsed !is TermuxTransferProtocolResult.Ok) return parsed as TermuxTransferProtocolResult.Error
    val values = parsed.value
    if (values["request_id"] != expectedRequestId) return TermuxTransferProtocolResult.Error("request_id_mismatch")
    val actualPath = values["actual_path_b64"]?.let {
        decodeCanonicalBase64(it, MAX_TERMUX_ACTUAL_PATH_BYTES)?.let { raw -> decodeStrictUtf8(raw, absolutePath = true) }
            ?: return TermuxTransferProtocolResult.Error("invalid_protocol")
    }
    values["error"]?.let { error ->
        if (!TRANSFER_ERROR_PATTERN.matches(error)) return TermuxTransferProtocolResult.Error("invalid_protocol")
        val expectedKeys = when (error) {
            in BARE_WRITE_ERROR_CODES -> setOf("request_id", "error")
            "stale_source" -> setOf("request_id", "error", "actual_path_b64", "current_sha256")
            in PATH_WRITE_ERROR_CODES -> setOf("request_id", "error", "actual_path_b64")
            else -> return TermuxTransferProtocolResult.Error("invalid_protocol")
        }
        if (values.keys != expectedKeys) return TermuxTransferProtocolResult.Error("invalid_protocol")
        val current = values["current_sha256"]?.takeIf(::isValidSha256)
            ?: if (values.containsKey("current_sha256")) return TermuxTransferProtocolResult.Error("invalid_protocol") else null
        return TermuxTransferProtocolResult.Error(error, actualPath = actualPath, currentSha256 = current)
    }
    val successKeys = setOf(
        "request_id", "operation", "actual_path_b64", "path_request_sha256", "content_sha256",
        "bytes_written", "total_bytes", "sha256",
    )
    if (values.keys != successKeys || actualPath == null) return TermuxTransferProtocolResult.Error("invalid_protocol")
    val operation = values.getValue("operation")
    val pathSha = values.getValue("path_request_sha256")
    val contentSha = values.getValue("content_sha256")
    if (operation != expectedOperation || pathSha != expectedPathRequestSha256 || contentSha != expectedContentSha256 ||
        !isValidSha256(pathSha) || !isValidSha256(contentSha)
    ) return TermuxTransferProtocolResult.Error("response_mismatch")
    val bytesWritten = strictDecimalLong(values.getValue("bytes_written"))
        ?: return TermuxTransferProtocolResult.Error("invalid_protocol")
    val totalBytes = strictDecimalLong(values.getValue("total_bytes"))
        ?: return TermuxTransferProtocolResult.Error("invalid_protocol")
    val finalSha = values.getValue("sha256").takeIf(::isValidSha256)
        ?: return TermuxTransferProtocolResult.Error("invalid_protocol")
    if (bytesWritten != expectedBytesWritten.toLong() || totalBytes < bytesWritten) {
        return TermuxTransferProtocolResult.Error("invalid_protocol")
    }
    if (operation == "write" && (totalBytes != bytesWritten || finalSha != contentSha)) {
        return TermuxTransferProtocolResult.Error("invalid_protocol")
    }
    return TermuxTransferProtocolResult.Ok(
        TermuxWriteEnvelope(
            expectedRequestId, operation, actualPath, pathSha, contentSha,
            bytesWritten, totalBytes, finalSha,
        ),
    )
}
