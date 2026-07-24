package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.UUID

class TermuxEditProtocolTest {
    @Test fun nullableStringWriterPreservesValuesAndEmitsExplicitNulls() {
        val populated = buildJsonObject { putNullableString("diff", "--- a/file\n+++ b/file") }
        val empty = buildJsonObject { putNullableString("diff", null) }

        assertEquals("--- a/file\n+++ b/file", populated["diff"]?.jsonPrimitive?.content)
        assertTrue(empty["diff"] is JsonNull)
    }

    @Test fun snapshotParserRequiresCanonicalCorrelationAndBounds() {
        val id = UUID.randomUUID().toString(); val actual = Base64.getEncoder().encodeToString("/tmp/a".toByteArray()); val pathSha = "1".repeat(64); val contentSha = "a".repeat(64)
        val valid = "RIKKAHUB_EDIT_SNAPSHOT_V1\nrequest_id=$id\ncount=1\nitem=0,$actual,$pathSha,$contentSha,640,1:2,3\n"
        val parsed = parseTermuxEditSnapshots(valid, id, listOf(pathSha)) as TermuxTransferProtocolResult.Ok
        assertEquals("/tmp/a", parsed.value.single().actualPath); assertEquals(3, parsed.value.single().bytes)
        assertTrue(parseTermuxEditSnapshots(valid, id, listOf("2".repeat(64))) is TermuxTransferProtocolResult.Error)
        assertTrue(parseTermuxEditSnapshots(valid.replace("item=0,$actual,", "item=0,${actual}=,"), id, listOf(pathSha)) is TermuxTransferProtocolResult.Error)
        assertTrue(parseTermuxEditSnapshots(valid.replace(",3\n", ",4194305\n"), id, listOf(pathSha)) is TermuxTransferProtocolResult.Error)
        assertTrue(parseTermuxEditSnapshots(valid + "unknown=x\n", id, listOf(pathSha)) is TermuxTransferProtocolResult.Error)
        val duplicateIdentity = valid.replace("count=1", "count=2").replace("item=0,$actual,$pathSha,$contentSha,640,1:2,3", "item=0,$actual,$pathSha,$contentSha,640,1:2,3\nitem=1,${Base64.getEncoder().encodeToString("/tmp/b".toByteArray())},$pathSha,$contentSha,640,1:2,3")
        assertTrue(parseTermuxEditSnapshots(duplicateIdentity, id, listOf(pathSha, pathSha)) is TermuxTransferProtocolResult.Error)
    }

    @Test fun parsersRejectPhaseWrongAndMalformedErrors() {
        val id = UUID.randomUUID().toString()
        fun code(result: TermuxTransferProtocolResult<*>): String = (result as TermuxTransferProtocolResult.Error).code
        val snapshotWrong = "RIKKAHUB_EDIT_SNAPSHOT_V1\nrequest_id=$id\nerror=missing_snapshot\n"
        val chunkWrong = "RIKKAHUB_EDIT_CHUNK_V1\nrequest_id=$id\nerror=stale_source\n"
        val publishWrong = "RIKKAHUB_EDIT_PUBLISH_V1\nrequest_id=$id\ncount=0\nerror=stale_source\n"
        assertEquals("invalid_protocol", code(parseTermuxEditSnapshots(snapshotWrong, id, emptyList())))
        assertEquals("invalid_protocol", code(parseTermuxEditChunk(chunkWrong, id, 0, 0, 0)))
        assertEquals("invalid_protocol", code(parseTermuxEditPublish(publishWrong, id, emptyList())))
        assertEquals("invalid_protocol", code(parseTermuxEditSnapshots("RIKKAHUB_EDIT_SNAPSHOT_V1\nrequest_id=$id\nerror=Not_valid\n", id, emptyList())))
    }

    @Test fun chunkParserRejectsNoncanonicalBase64AndMismatchedCoordinates() {
        val id = UUID.randomUUID().toString(); val valid = "RIKKAHUB_EDIT_CHUNK_V1\nrequest_id=$id\nindex=0\noffset=2\nlength=1\ndata_b64=YQ==\n"
        assertEquals("a", String((parseTermuxEditChunk(valid, id, 0, 2, 1) as TermuxTransferProtocolResult.Ok).value))
        assertTrue(parseTermuxEditChunk(valid.replace("YQ==", "YQ"), id, 0, 2, 1) is TermuxTransferProtocolResult.Error)
        assertTrue(parseTermuxEditChunk(valid, id, 0, 1, 1) is TermuxTransferProtocolResult.Error)
    }

    @Test fun publishParserCorrelatesEveryItemAndReportsRollbackExactly() {
        val id = UUID.randomUUID().toString(); val a = Base64.getEncoder().encodeToString("/a".toByteArray()); val b = Base64.getEncoder().encodeToString("/b".toByteArray()); val old = "b".repeat(64); val new = "c".repeat(64)
        val failed = "RIKKAHUB_EDIT_PUBLISH_V1\nrequest_id=$id\ncount=2\nerror=publish_failed\nitem=0,$a,$old,$new,rolled_back\nitem=1,$b,$old,$new,not_published\n"
        val expected = listOf(Triple("/a", old, new), Triple("/b", old, new)); val parsed = parseTermuxEditPublish(failed, id, expected) as TermuxTransferProtocolResult.Ok
        assertFalse(parsed.value.success); assertEquals(true, parsed.value.items[0].rolledBack); assertEquals(null, parsed.value.items[1].rolledBack)
        val sourceChanged = "RIKKAHUB_EDIT_PUBLISH_V1\nrequest_id=$id\ncount=2\nerror=source_changed\nitem=0,$a,$old,$new,not_published\nitem=1,$b,$old,$new,not_published\n"
        val correlated = parseTermuxEditPublish(sourceChanged, id, expected) as TermuxTransferProtocolResult.Ok
        assertEquals("source_changed", correlated.value.error)
        val uncorrelated = sourceChanged.replace("item=0,$a,$old,$new", "item=0,,,")
        assertTrue(parseTermuxEditPublish(uncorrelated, id, expected) is TermuxTransferProtocolResult.Error)
        val changedAsNoChange = sourceChanged.replace("item=0,$a,$old,$new,not_published", "item=0,$a,$old,$new,no_change")
        assertTrue(parseTermuxEditPublish(changedAsNoChange, id, expected) is TermuxTransferProtocolResult.Error)
        val unchangedExpected = listOf(Triple("/a", old, old))
        val unchangedAsPublished = "RIKKAHUB_EDIT_PUBLISH_V1\nrequest_id=$id\ncount=1\nitem=0,$a,$old,$old,published\n"
        assertTrue(parseTermuxEditPublish(unchangedAsPublished, id, unchangedExpected) is TermuxTransferProtocolResult.Error)
        val prePublishRollback = "RIKKAHUB_EDIT_PUBLISH_V1\nrequest_id=$id\ncount=2\nerror=invalid_sha256\nitem=0,$a,$old,$new,rolled_back\nitem=1,$b,$old,$new,not_published\n"
        assertTrue(parseTermuxEditPublish(prePublishRollback, id, expected) is TermuxTransferProtocolResult.Error)
        val outOfOrder = "RIKKAHUB_EDIT_PUBLISH_V1\nrequest_id=$id\ncount=2\nerror=publish_failed\nitem=0,$a,$old,$new,not_published\nitem=1,$b,$old,$new,rolled_back\n"
        assertTrue(parseTermuxEditPublish(outOfOrder, id, expected) is TermuxTransferProtocolResult.Error)
        val postSyncNotPublished = "RIKKAHUB_EDIT_PUBLISH_V1\nrequest_id=$id\ncount=1\nerror=post_publish_sync_failed\nitem=0,$a,$old,$new,not_published\n"
        assertTrue(parseTermuxEditPublish(postSyncNotPublished, id, listOf(expected[0])) is TermuxTransferProtocolResult.Error)
        val lonePublishFailedRollback = "RIKKAHUB_EDIT_PUBLISH_V1\nrequest_id=$id\ncount=1\nerror=publish_failed\nitem=0,$a,$old,$new,rolled_back\n"
        assertTrue(parseTermuxEditPublish(lonePublishFailedRollback, id, listOf(expected[0])) is TermuxTransferProtocolResult.Error)
        assertTrue(parseTermuxEditPublish(failed.replace("item=1,$b", "item=0,$b"), id, expected) is TermuxTransferProtocolResult.Error)
        assertTrue(parseTermuxEditPublish(failed.replace(new, "d".repeat(64)), id, expected) is TermuxTransferProtocolResult.Error)
        assertTrue(parseTermuxEditPublish(failed.replace("not_published", "mystery"), id, expected) is TermuxTransferProtocolResult.Error)
    }

    @Test fun aggregateActualPathBudgetFitsRunCommandTransportAndArgv() {
        val id = UUID.randomUUID().toString()
        val sha = "a".repeat(64)
        val pathSha = "b".repeat(64)
        val perPathBytes = MAX_TERMUX_EDIT_TOTAL_ACTUAL_PATH_BYTES / MAX_TERMUX_EDIT_FILES
        fun paths(extraBytes: Int = 0) = (0 until MAX_TERMUX_EDIT_FILES).map { index ->
            val prefix = "/${index.toString().padStart(2, '0')}/"
            prefix + "p".repeat(perPathBytes + extraBytes - prefix.length)
        }
        fun snapshotBody(actualPaths: List<String>) = buildString {
            append("RIKKAHUB_EDIT_SNAPSHOT_V1\nrequest_id=$id\ncount=${actualPaths.size}\n")
            actualPaths.forEachIndexed { index, raw ->
                append("item=$index,${Base64.getEncoder().encodeToString(raw.toByteArray())},$pathSha,$sha,600,1:${index + 1},0\n")
            }
        }

        val validPaths = paths()
        val body = snapshotBody(validPaths)
        assertTrue(body.length < TERMUX_EDIT_PROTOCOL_MAX_CHARS)
        assertTrue(body.length < 100 * 1024)
        assertTrue(parseTermuxEditSnapshots(body, id, List(MAX_TERMUX_EDIT_FILES) { pathSha }) is TermuxTransferProtocolResult.Ok)

        val oversized = snapshotBody(paths(extraBytes = 1))
        assertTrue(oversized.length < TERMUX_EDIT_PROTOCOL_MAX_CHARS)
        assertTrue(parseTermuxEditSnapshots(oversized, id, List(MAX_TERMUX_EDIT_FILES) { pathSha }) is TermuxTransferProtocolResult.Error)
        assertTrue(parseTermuxEditSnapshots("RIKKAHUB_EDIT_SNAPSHOT_V1\nrequest_id=$id\n" + "x".repeat(TERMUX_EDIT_PROTOCOL_MAX_CHARS), id, listOf(pathSha)) is TermuxTransferProtocolResult.Error)

        val fixedPerFileChars = 64 + 64 + 4 + 41 + 36 + 64 + 1
        val publishArgumentChars = validPaths.sumOf { Base64.getEncoder().encodeToString(it.toByteArray()).length } +
            MAX_TERMUX_EDIT_FILES * fixedPerFileChars + id.length + 16
        val wrappedArgumentChars = TERMUX_EDIT_PUBLISH_SCRIPT.length + DIRECT_CAPTURE_SCRIPT.length +
            DIRECT_CAPTURE_LEADER_SCRIPT.length + publishArgumentChars
        assertTrue("leave at least 32 KiB below Android ARG_MAX", wrappedArgumentChars < 96 * 1024)
    }
}
