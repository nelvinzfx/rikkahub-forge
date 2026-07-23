package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import me.rerere.rikkahub.data.preferences.TermuxDefaults
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

class TermuxFileProtocolTest {
    private val requestId = "123e4567-e89b-42d3-a456-426614174000"
    private val path = "/data/data/com.termux/files/home/a\n\n"

    @Test fun phase2SourcesContainNoNulBytes() {
        val names = listOf("TermuxPathResolver.kt", "TermuxFileProtocol.kt", "TermuxFileTools.kt")
        val roots = listOf(File("src/main/java/me/rerere/rikkahub/data/ai/tools/local"), File("app/src/main/java/me/rerere/rikkahub/data/ai/tools/local"))
        val root = roots.first { it.isDirectory }
        names.forEach { assertFalse("NUL in $it", File(root, it).readBytes().any { byte -> byte == 0.toByte() }) }
    }

    @Test fun resolverMirrorAndCanonicalBase64() {
        fun ok(value: String) = (resolveTermuxPathLexically(value) as TermuxPathResolution.Ok).actualPath
        assertEquals("$TERMUX_FILES_HOME/a b=c", ok("a b=c"))
        assertEquals("$TERMUX_FILES_HOME/x", ok("~/a/../x"))
        assertEquals("$TERMUX_FILES_TMPDIR/a", ok("/tmp/a"))
        assertTrue(resolveTermuxPathLexically("/tmp/../escape") is TermuxPathResolution.Error)
        assertTrue(resolveTermuxPathLexically("\u0000") is TermuxPathResolution.Error)
        val odd = "space=\tline\n\n"
        assertEquals(odd, decodeTermuxPath(encodeTermuxPath(odd)))
        assertNull(decodeTermuxPath(encodeTermuxPath(odd).trimEnd('=')))
        assertNull(decodeTermuxPath("@@@@"))
        assertNull(decodeStrictUtf8(byteArrayOf(0xC3.toByte())))
    }

    @Test fun strictPublicTextInputsRejectCoercionsAndContainers() {
        fun error(json: String) = (parseTermuxTextReadRequest(Json.parseToJsonElement(json), 100) as PublicInputResult.Error).value.code
        assertEquals("request_must_be_object", error("[]")); assertEquals("request_must_be_object", error("null"))
        assertEquals("path_must_be_string", error("""{"path":12}"""))
        assertEquals("offset_must_be_integer", error("""{"path":"a","offset":"1"}"""))
        assertEquals("offset_must_be_integer", error("""{"path":"a","offset":1.2}"""))
        assertEquals("offset_must_be_integer", error("""{"path":"a","offset":true}"""))
        assertEquals("offset_must_be_integer", error("""{"path":"a","offset":999999999999999999999}"""))
        assertEquals("limit_must_be_integer_or_null", error("""{"path":"a","limit":"2"}"""))
        assertEquals("line_numbers_must_be_boolean", error("""{"path":"a","line_numbers":"true"}"""))
        assertTrue(error("""{"path":"a","x":1}""").startsWith("unknown_fields"))
        val ok = parseTermuxTextReadRequest(Json.parseToJsonElement("""{"path":"a","limit":null,"line_numbers":false}"""), 100) as PublicInputResult.Ok
        assertEquals(100, ok.value.limit); assertFalse(ok.value.lineNumbers)
    }

    @Test fun strictPublicByteInputsRejectCoercions() {
        fun error(json: String) = (parseTermuxByteReadRequest(Json.parseToJsonElement(json), 100) as PublicInputResult.Error).value.code
        assertEquals("request_must_be_object", error("[]")); assertEquals("path_must_be_string", error("""{"path":true}"""))
        assertEquals("offset_must_be_integer", error("""{"path":"a","offset":"0"}"""))
        assertEquals("length_must_be_integer", error("""{"path":"a","length":1.5}"""))
        assertTrue(error("""{"path":"a","extra":0}""").startsWith("unknown_fields"))
    }

    @Test fun renderingPreservesCrlfFinalNewlineAndMalformedUtf8Replacement() {
        val raw = byteArrayOf(
            'a'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte(),
            0xC3.toByte(), '\n'.code.toByte(), 'z'.code.toByte(),
        )
        assertEquals("a\r\n�\nz", renderTermuxText(raw, 4, false))
        assertEquals("4\ta\r\n5\t�\n6\tz", renderTermuxText(raw, 4, true))
        assertFalse(renderTermuxText(raw, 4, false).endsWith("\n"))
    }

    @Test fun textProtocolPinsRequestPaginationAndCanonicalFields() {
        val raw = "a\r\nb".toByteArray(); val sha = sha256Hex(raw)
        val protocol = textProtocol(raw, 1, 2, 2, false, null, sha)
        val parsed = parseTermuxTextReadEnvelope(protocol, requestId, 1, 2, 61_440, 2_000) as TermuxFileProtocolResult.Ok
        assertArrayEquals(raw, parsed.value.contentBytes); assertEquals(path, parsed.value.actualPath)
        assertTrue(parseTermuxTextReadEnvelope(protocol, requestId.replace('1','2'), 1, 2, 61_440, 2_000) is TermuxFileProtocolResult.Error)
        assertTrue(parseTermuxTextReadEnvelope(protocol, requestId, 2, 2, 61_440, 2_000) is TermuxFileProtocolResult.Error)
        assertTrue(parseTermuxTextReadEnvelope(protocol.replace("content_b64=", "content_b64=YQ"), requestId, 1, 2, 61_440, 2_000) is TermuxFileProtocolResult.Error)
        assertTrue(parseTermuxTextReadEnvelope(protocol + "unknown=x\n", requestId, 1, 2, 61_440, 2_000) is TermuxFileProtocolResult.Error)
        val nulPath = protocol.replace(b64(path), Base64.getEncoder().encodeToString("/bad\u0000path".toByteArray()))
        assertTrue(parseTermuxTextReadEnvelope(nulPath, requestId, 1, 2, 61_440, 2_000) is TermuxFileProtocolResult.Error)
        val malformedPath = protocol.replace(b64(path), Base64.getEncoder().encodeToString(byteArrayOf('/'.code.toByte(), 0xC3.toByte())))
        assertTrue(parseTermuxTextReadEnvelope(malformedPath, requestId, 1, 2, 61_440, 2_000) is TermuxFileProtocolResult.Error)
    }

    @Test fun emptyTextAndErrorsAreStrictlyBound() {
        val emptySha = sha256Hex(byteArrayOf())
        val empty = textProtocol(byteArrayOf(), 1, 0, 0, false, null, emptySha)
        assertTrue(parseTermuxTextReadEnvelope(empty, requestId, 1, 5, 100, 10) is TermuxFileProtocolResult.Ok)
        assertTrue(parseTermuxTextReadEnvelope(empty, requestId, 2, 5, 100, 10) is TermuxFileProtocolResult.Error)
        val error = "RIKKAHUB_FILE_TEXT_V1\nrequest_id=$requestId\nerror=offset_beyond_eof\nactual_path_b64=${b64(path)}\ntotal_lines=3\nsha256=${sha256Hex("x".toByteArray())}"
        val parsed = parseTermuxTextReadEnvelope(error, requestId, 9, 2, 100, 10) as TermuxFileProtocolResult.Error
        assertEquals(3L, parsed.totalLines); assertEquals(path, parsed.actualPath)
        assertTrue(parseTermuxTextReadEnvelope(error.replace("request_id=$requestId", "request_id=bad"), requestId, 9, 2, 100, 10) is TermuxFileProtocolResult.Error)
    }

    @Test fun byteProtocolPinsClampLengthAndSha() {
        val data = byteArrayOf(1,2,3); val sha = sha256Hex(byteArrayOf(9))
        val protocol = byteProtocol(7, 3, 10, data, sha)
        assertTrue(parseTermuxByteReadEnvelope(protocol, requestId, 7, 9, 100) is TermuxFileProtocolResult.Ok)
        assertTrue(parseTermuxByteReadEnvelope(protocol, requestId, 6, 9, 100) is TermuxFileProtocolResult.Error)
        val eof = byteProtocol(10, 0, 10, byteArrayOf(), sha)
        assertTrue(parseTermuxByteReadEnvelope(eof, requestId, 99, 4, 100) is TermuxFileProtocolResult.Ok)
        assertTrue(parseTermuxByteReadEnvelope(protocol.replace("sha256=$sha", "sha256=ABC"), requestId, 7, 9, 100) is TermuxFileProtocolResult.Error)
    }

    @Test fun protocolRejectsTruncatedWithoutLfAndWholeFileShaMismatches() {
        val partial = textProtocol("abc".toByteArray(), 1, 1, 2, true, 2, sha256Hex("whole".toByteArray()))
        assertTrue(parseTermuxTextReadEnvelope(partial, requestId, 1, 1, 100, 10) is TermuxFileProtocolResult.Error)
        val whole = "abc".toByteArray()
        val badTextSha = textProtocol(whole, 1, 1, 1, false, null, sha256Hex("other".toByteArray()))
        assertTrue(parseTermuxTextReadEnvelope(badTextSha, requestId, 1, 1, 100, 10) is TermuxFileProtocolResult.Error)
        val badByteSha = byteProtocol(0, whole.size, whole.size.toLong(), whole, sha256Hex("other".toByteArray()))
        assertTrue(parseTermuxByteReadEnvelope(badByteSha, requestId, 0, whole.size, 100) is TermuxFileProtocolResult.Error)
        val emptyBadSha = byteProtocol(0, 0, 0, byteArrayOf(), sha256Hex("other".toByteArray()))
        assertTrue(parseTermuxByteReadEnvelope(emptyBadSha, requestId, 0, 4, 100) is TermuxFileProtocolResult.Error)
    }

    @Test fun protocolErrorSchemasRejectExtraAndMissingFields() {
        val resolver = "RIKKAHUB_FILE_TEXT_V1\nrequest_id=$requestId\nerror=blank_path"
        assertTrue(parseTermuxTextReadEnvelope(resolver, requestId, 1, 1, 100, 10) is TermuxFileProtocolResult.Error)
        val resolverExtra = "$resolver\nsha256=${sha256Hex(byteArrayOf())}"
        val extraParsed = parseTermuxTextReadEnvelope(resolverExtra, requestId, 1, 1, 100, 10) as TermuxFileProtocolResult.Error
        assertEquals("invalid_protocol", extraParsed.code)
        val pathMissing = "RIKKAHUB_FILE_TEXT_V1\nrequest_id=$requestId\nerror=not_found"
        assertEquals("invalid_protocol", (parseTermuxTextReadEnvelope(pathMissing, requestId, 1, 1, 100, 10) as TermuxFileProtocolResult.Error).code)
        val pagedMissingSha = "RIKKAHUB_FILE_TEXT_V1\nrequest_id=$requestId\nerror=line_too_large\nactual_path_b64=${b64(path)}\ntotal_lines=1"
        assertEquals("invalid_protocol", (parseTermuxTextReadEnvelope(pagedMissingSha, requestId, 1, 1, 100, 10) as TermuxFileProtocolResult.Error).code)
    }

    @Test fun protocolBoundProvesWorstCaseFits() {
        assertTrue(maxTermuxProtocolChars(61_440) <= MAX_TERMUX_FILE_PROTOCOL_CHARS)
        assertEquals(99_000, MAX_TERMUX_FILE_PROTOCOL_CHARS)
        assertEquals(94_064, maxTermuxProtocolChars(61_440))
        assertEquals(TermuxDefaults.MAX_TEXT_READ_MAX_BYTES, 61_440)
    }

    @Test fun batchBudgetsAndApprovalArePinned() {
        assertEquals(TermuxBatchBudget(1, 7), consumeTermuxBatchBudget(TermuxBatchBudget(3, 10), 2, 3))
        assertNull(consumeTermuxBatchBudget(TermuxBatchBudget(1, 1), 2, 0))
        listOf("termux_read_file", "termux_read_file_bytes", "termux_read_files").forEach {
            assertTrue(ToolApprovalDefaults.requiresApproval(it)); assertTrue(ToolApprovalDefaults.allowsAlwaysAllow(it))
        }
        assertTrue(textReadSchema().containsKey("path"))
        val wrapper = termuxBatchWrapper(kotlinx.serialization.json.buildJsonArray {})
        assertTrue(wrapper["results"] is kotlinx.serialization.json.JsonArray); assertEquals(JsonNull, wrapper["error"])
    }

    @Test fun batchPolicyPreservesIndexesMixedErrorsAndExhaustion() {
        val ok = PublicInputResult.Ok(TermuxTextReadRequest("a", 1, 1, true))
        val bad = PublicInputResult.Error(PublicInputError("path_must_be_string"))
        assertEquals(listOf(0 to null, 1 to "path_must_be_string", 2 to null), simulateBatchPolicy(listOf(ok, bad, ok), 2, 2))
        assertEquals(listOf(0 to null, 1 to "batch_limit_reached", 2 to "batch_limit_reached"), simulateBatchPolicy(listOf(ok, ok, bad), 1, 1))
        val primitive = parseTermuxTextReadRequest(Json.parseToJsonElement("1"), 10) as PublicInputResult.Error
        assertEquals("request_must_be_object", primitive.value.code)
    }

    private fun textProtocol(bytes: ByteArray, start: Long, end: Long, total: Long, truncated: Boolean, next: Long?, sha: String) =
        "RIKKAHUB_FILE_TEXT_V1\nrequest_id=$requestId\nactual_path_b64=${b64(path)}\nstart_line=$start\nend_line=$end\ntotal_lines=$total\ntruncated=${if(truncated)1 else 0}\nnext_offset=${next ?: ""}\nsha256=$sha\ncontent_b64=${Base64.getEncoder().encodeToString(bytes)}"
    private fun byteProtocol(offset: Long, length: Int, total: Long, bytes: ByteArray, sha: String) =
        "RIKKAHUB_FILE_BYTES_V1\nrequest_id=$requestId\nactual_path_b64=${b64(path)}\noffset=$offset\nactual_length=$length\ntotal_bytes=$total\nsha256=$sha\ndata_b64=${Base64.getEncoder().encodeToString(bytes)}"
    private fun b64(value: String) = Base64.getEncoder().encodeToString(value.toByteArray())
}
