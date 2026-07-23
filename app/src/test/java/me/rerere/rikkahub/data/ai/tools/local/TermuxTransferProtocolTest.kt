package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

class TermuxTransferProtocolTest {
    private val requestId = "123e4567-e89b-42d3-a456-426614174000"
    private val path = "/tmp/a\n"
    private val pathB64 = Base64.getEncoder().encodeToString(path.toByteArray())
    private val pathSha = sha256Hex(path.toByteArray())
    private val contentSha = sha256Hex("abc".toByteArray())
    private val finalSha = sha256Hex("oldabc".toByteArray())

    @Test fun phase3SourcesContainNoNulBytesAndPinHonestContracts() {
        val roots = listOf(File("src/main/java/me/rerere/rikkahub/data/ai/tools/local"), File("app/src/main/java/me/rerere/rikkahub/data/ai/tools/local"))
        val root = roots.first { it.isDirectory }
        val source = File(root, "TermuxWriteTools.kt").readText()
        listOf("TermuxTransferProtocol.kt", "TermuxWriteTools.kt").forEach { name ->
            assertFalse("NUL in $name", File(root, name).readBytes().any { it == 0.toByte() })
        }
        assertTrue(TERMUX_EXTERNAL_WRITER_BOUNDARY.contains("uncooperative same-UID Termux writer can race"))
        assertTrue(TERMUX_DURABILITY_CONTRACT.contains("Recursively created ancestor directories are not individually synced"))
        assertTrue(TERMUX_ATOMIC_WRITE_SCRIPT.contains("ln -T -- \"${'$'}temp\" \"${'$'}actual_path\""))
        assertTrue(TERMUX_ATOMIC_WRITE_SCRIPT.contains("mv -nT -- \"${'$'}temp\" \"${'$'}actual_path\""))
        assertTrue(TERMUX_ATOMIC_WRITE_SCRIPT.contains("mv -Tf -- \"${'$'}temp\" \"${'$'}actual_path\""))
        assertTrue(TERMUX_ATOMIC_WRITE_SCRIPT.contains("sync \"${'$'}temp\" || path_error data_sync_failed"))
        assertTrue(TERMUX_ATOMIC_WRITE_SCRIPT.contains("sync \"${'$'}actual_path\" || path_error post_publish_sync_failed"))
        assertTrue(TERMUX_ATOMIC_WRITE_SCRIPT.contains("sync \"${'$'}parent\" || path_error post_publish_sync_failed"))
        assertTrue(TERMUX_ATOMIC_WRITE_SCRIPT.contains("sha256sum < \"${'$'}actual_path\""))
        assertTrue(source.contains("withContext(NonCancellable) { runCatching { cleanupTermuxTransfer(context, requestId) } }"))
        assertEquals(1, Regex("cleanupTermuxTransfer\\(context, requestId\\)").findAll(source).count())
    }

    @Test fun strictWriteInputsRejectTypesUtf16AndConflictingGuards() {
        fun error(json: String, append: Boolean = false) =
            (parseTermuxWriteRequest(Json.parseToJsonElement(json), append) as PublicInputResult.Error).value.code
        assertEquals("request_must_be_object", error("[]")); assertEquals("request_must_be_object", error("null"))
        assertEquals("path_must_be_string", error("""{"path":3,"content":"x"}"""))
        assertEquals("content_must_be_string", error("""{"path":"a","content":false}"""))
        assertEquals("create_only_must_be_boolean", error("""{"path":"a","content":"x","create_only":"true"}"""))
        assertEquals("expected_sha256_must_be_string_or_null", error("""{"path":"a","content":"x","expected_sha256":4}"""))
        assertEquals("invalid_expected_sha256", error("""{"path":"a","content":"x","expected_sha256":"ABC"}"""))
        assertEquals("conflicting_guards", error("""{"path":"a","content":"x","expected_sha256":"${"0".repeat(64)}","create_only":true}"""))
        assertTrue(error("""{"path":"a","content":"x","extra":1}""").startsWith("unknown_fields"))
        assertTrue(encodeUtf8StrictBounded("\uD800", 8) is BoundedUtf8Result.InvalidUtf8)
        assertTrue(encodeUtf8StrictBounded("\uDC00", 8) is BoundedUtf8Result.InvalidUtf8)
        val badPath = buildJsonObject { put("path", JsonPrimitive("a\uD800")); put("content", "x") }
        val badContent = buildJsonObject { put("path", "a"); put("content", JsonPrimitive("x\uDC00")) }
        assertEquals("invalid_utf8", (parseTermuxWriteRequest(badPath, false) as PublicInputResult.Error).value.code)
        assertEquals("content_invalid_utf8", (parseTermuxWriteRequest(badContent, false) as PublicInputResult.Error).value.code)
        val oversizedPath = buildJsonObject { put("path", "p".repeat(MAX_TERMUX_PATH_BYTES + 1)); put("content", "x") }
        assertEquals("path_too_large", (parseTermuxWriteRequest(oversizedPath, false) as PublicInputResult.Error).value.code)
    }

    @Test fun capBoundariesAndMultiChunkReconstructionAreExact() {
        val exactAscii = "a".repeat(MAX_TERMUX_TRANSFER_BYTES)
        val exactRequest = buildJsonObject { put("path", "exact"); put("content", exactAscii) }
        val exactParsed = parseTermuxWriteRequest(exactRequest, false) as PublicInputResult.Ok
        assertEquals(MAX_TERMUX_TRANSFER_BYTES, exactParsed.value.contentBytes.size)
        val tooLargeRequest = buildJsonObject { put("path", "large"); put("content", "a".repeat(MAX_TERMUX_TRANSFER_BYTES + 1)) }
        assertEquals("content_too_large", (parseTermuxWriteRequest(tooLargeRequest, false) as PublicInputResult.Error).value.code)
        val multibyteTooLarge = "€".repeat(MAX_TERMUX_TRANSFER_BYTES / 2)
        assertTrue(multibyteTooLarge.length <= MAX_TERMUX_TRANSFER_BYTES)
        assertTrue(encodeUtf8StrictBounded(multibyteTooLarge, MAX_TERMUX_TRANSFER_BYTES) is BoundedUtf8Result.TooLarge)
        val multibyteRequest = buildJsonObject { put("path", "multi"); put("content", multibyteTooLarge) }
        assertEquals("content_too_large", (parseTermuxWriteRequest(multibyteRequest, false) as PublicInputResult.Error).value.code)
        val exact = ByteArray(MAX_TERMUX_TRANSFER_BYTES) { (it % 251).toByte() }
        val chunks = splitTermuxTransferBytes(exact)
        val rebuilt = ByteArrayOutputStream().also { out -> chunks.forEach { out.write(it.data) } }.toByteArray()
        assertArrayEquals(exact, rebuilt); assertTrue(chunks.size > 100)
        chunks.forEachIndexed { index, chunk ->
            assertEquals(index, chunk.index); assertEquals(chunks.size, chunk.count)
            assertTrue(chunk.data.size <= TERMUX_TRANSFER_CHUNK_BYTES); assertTrue(chunk.canonicalBase64.length <= 32 * 1024)
        }
        try { splitTermuxTransferBytes(ByteArray(MAX_TERMUX_TRANSFER_BYTES + 1)); fail("max+1 accepted") }
        catch (expected: IllegalArgumentException) { assertEquals("transfer_too_large", expected.message) }
        val unicode = "α\u0000β\r\n🙂tail"
        assertArrayEquals(unicode.toByteArray(), splitTermuxTransfer(unicode).single().data)
    }

    @Test fun transferAckPinsRequestIndexFieldsAndKnownErrors() {
        val ok = "RIKKAHUB_TRANSFER_V1\nrequest_id=$requestId\nindex=2\n"
        assertTrue(parseTermuxTransferAck(ok, requestId, 2) is TermuxTransferProtocolResult.Ok)
        assertTrue(parseTermuxTransferAck(ok, requestId, 1) is TermuxTransferProtocolResult.Error)
        assertTrue(parseTermuxTransferAck(ok.replace("index=2", "index=02"), requestId, 2) is TermuxTransferProtocolResult.Error)
        assertTrue(parseTermuxTransferAck(ok + "unknown=x\n", requestId, 2) is TermuxTransferProtocolResult.Error)
        val error = "RIKKAHUB_TRANSFER_V1\nrequest_id=$requestId\nerror=out_of_order_chunk\n"
        assertEquals("out_of_order_chunk", (parseTermuxTransferAck(error, requestId, 2) as TermuxTransferProtocolResult.Error).code)
        assertTrue(parseTermuxTransferAck(error.replace("out_of_order_chunk", "invented_error"), requestId, 2) is TermuxTransferProtocolResult.Error)
    }

    @Test fun writeSuccessStrictlyCorrelatesOperationPathContentCountsAndFinalSha() {
        fun protocol(operation: String = "append", pathHash: String = pathSha, contentHash: String = contentSha,
                     written: String = "3", total: String = "6", sha: String = finalSha) =
            "RIKKAHUB_WRITE_V1\nrequest_id=$requestId\noperation=$operation\nactual_path_b64=$pathB64\npath_request_sha256=$pathHash\ncontent_sha256=$contentHash\nbytes_written=$written\ntotal_bytes=$total\nsha256=$sha\n"
        val parsed = parseTermuxWriteEnvelope(protocol(), requestId, "append", pathSha, contentSha, 3) as TermuxTransferProtocolResult.Ok
        assertEquals(path, parsed.value.actualPath); assertEquals(6, parsed.value.totalBytes)
        assertTrue(parseTermuxWriteEnvelope(protocol(operation = "write"), requestId, "append", pathSha, contentSha, 3) is TermuxTransferProtocolResult.Error)
        assertTrue(parseTermuxWriteEnvelope(protocol(pathHash = "b".repeat(64)), requestId, "append", pathSha, contentSha, 3) is TermuxTransferProtocolResult.Error)
        assertTrue(parseTermuxWriteEnvelope(protocol(contentHash = "c".repeat(64)), requestId, "append", pathSha, contentSha, 3) is TermuxTransferProtocolResult.Error)
        assertTrue(parseTermuxWriteEnvelope(protocol(total = "2"), requestId, "append", pathSha, contentSha, 3) is TermuxTransferProtocolResult.Error)
        assertTrue(parseTermuxWriteEnvelope(protocol(sha = "Z".repeat(64)), requestId, "append", pathSha, contentSha, 3) is TermuxTransferProtocolResult.Error)
        val write = protocol(operation = "write", total = "3", sha = contentSha)
        assertTrue(parseTermuxWriteEnvelope(write, requestId, "write", pathSha, contentSha, 3) is TermuxTransferProtocolResult.Ok)
        assertTrue(parseTermuxWriteEnvelope(write.replace("total_bytes=3", "total_bytes=4"), requestId, "write", pathSha, contentSha, 3) is TermuxTransferProtocolResult.Error)
        assertTrue(parseTermuxWriteEnvelope(write.replace("\nsha256=$contentSha", "\nsha256=${"d".repeat(64)}"), requestId, "write", pathSha, contentSha, 3) is TermuxTransferProtocolResult.Error)
        assertTrue(parseTermuxWriteEnvelope(write + "x=y\n", requestId, "write", pathSha, contentSha, 3) is TermuxTransferProtocolResult.Error)
    }

    @Test fun writeErrorsRetainExactFieldSets() {
        val stale = "RIKKAHUB_WRITE_V1\nrequest_id=$requestId\nerror=stale_source\nactual_path_b64=$pathB64\ncurrent_sha256=$contentSha\n"
        assertEquals(contentSha, (parseTermuxWriteEnvelope(stale, requestId, "write", pathSha, contentSha, 3) as TermuxTransferProtocolResult.Error).currentSha256)
        assertTrue(parseTermuxWriteEnvelope(stale.replace("stale_source", "invented_error"), requestId, "write", pathSha, contentSha, 3) is TermuxTransferProtocolResult.Error)
        assertTrue(parseTermuxWriteEnvelope(stale + "operation=write\n", requestId, "write", pathSha, contentSha, 3) is TermuxTransferProtocolResult.Error)
    }

    @Test fun writeToolsRequireApprovalAndRemainEligibleForAlwaysAllow() {
        listOf("termux_write_file", "termux_append_file").forEach { name ->
            assertTrue(name in ToolApprovalDefaults.ALWAYS_ASK); assertTrue(ToolApprovalDefaults.allowsAlwaysAllow(name))
        }
    }
}
