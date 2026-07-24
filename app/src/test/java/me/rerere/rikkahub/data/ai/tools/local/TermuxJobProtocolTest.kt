package me.rerere.rikkahub.data.ai.tools.local

import java.util.Base64
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxJobProtocolTest {
    private val jobId = "123e4567-e89b-42d3-a456-426614174000"

    @Test fun validationAndReadClamps_areClosedAndBounded() {
        assertTrue(isValidTermuxJobId(jobId))
        assertFalse(isValidTermuxJobId("../stdout"))
        assertTrue(isValidOutputStream("stdout"))
        assertFalse(isValidOutputStream("meta"))
        assertEquals(0L, clampReadOutputOffset(-4))
        assertEquals(DEFAULT_READ_OUTPUT_LENGTH, clampReadOutputLength(null))
        assertEquals(1, clampReadOutputLength(0))
        assertEquals(65_536, clampReadOutputLength(Int.MAX_VALUE))
        assertFalse(ToolApprovalDefaults.requiresApproval("termux_read_output"))
        assertTrue(READ_OUTPUT_CLEANUP_ENABLED)
        assertTrue(PUBLIC_RUN_COMMAND_SPOOL_OUTPUT)
        assertFalse(isValidTermuxJobId(jobId.uppercase()))
        assertFalse(isValidTermuxJobId("123e4567-e89b-12d3-a456-426614174000"))
        assertTrue(validHeadBudgets(49_152, 12_288))
        assertFalse(validHeadBudgets(49_153, 0))
        assertFalse(validHeadBudgets(49_152, 12_289))
    }

    @Test fun runCommandPayloadValidation_ignoresAcksAndMalformedShapes() {
        fun int(value: Any?) = RunCommandValueKind.INT to value
        fun string(value: Any?) = RunCommandValueKind.STRING to value
        fun other(value: Any?) = RunCommandValueKind.OTHER to value

        assertEquals(RunCommandPayloadShape.Ignore, validateRunCommandPayloadShape(emptyMap()))
        assertEquals(RunCommandPayloadShape.Ignore, validateRunCommandPayloadShape(null))
        assertEquals(
            RunCommandPayloadShape.Ignore,
            validateRunCommandPayloadShape(mapOf("ack" to string("started"))),
        )
        assertEquals(
            RunCommandPayloadShape.Ignore,
            validateRunCommandPayloadShape(mapOf("err" to string("-1"))),
        )
        assertEquals(
            RunCommandPayloadShape.Ignore,
            validateRunCommandPayloadShape(
                mapOf("err" to int(-1), "stdout" to string(""), "stderr" to string("")),
            ),
        )
        assertEquals(
            RunCommandPayloadShape.Ignore,
            validateRunCommandPayloadShape(
                mapOf("err" to int(-1), "exitCode" to other(0L), "stdout" to string(""), "stderr" to string("")),
            ),
        )
        assertEquals(
            RunCommandPayloadShape.Ignore,
            validateRunCommandPayloadShape(mapOf("err" to int(2))),
        )
    }

    @Test fun runCommandPayloadValidation_acceptsOnlyCompleteFinalShapes() {
        fun int(value: Int) = RunCommandValueKind.INT to value
        fun string(value: String) = RunCommandValueKind.STRING to value

        assertEquals(
            RunCommandPayloadShape.Success(-1),
            validateRunCommandPayloadShape(
                mapOf(
                    "err" to int(-1),
                    "exitCode" to int(17),
                    "stdout" to string("out"),
                    "stderr" to string("err"),
                ),
            ),
        )
        assertEquals(
            RunCommandPayloadShape.InternalError(3),
            validateRunCommandPayloadShape(mapOf("err" to int(3), "errmsg" to string("failed"))),
        )
    }

    @Test fun captureLifecycle_finalSuccessReleasesEverythingElseCleans() {
        assertEquals(CaptureLifecycleAction.RELEASE, captureLifecycleAction(CaptureCompletionKind.FINAL_SUCCESS, true))
        listOf(
            CaptureCompletionKind.INTERNAL_ERROR,
            CaptureCompletionKind.EXCEPTION,
            CaptureCompletionKind.TIMEOUT,
            CaptureCompletionKind.CANCELLATION,
        ).forEach { assertEquals(CaptureLifecycleAction.CLEANUP, captureLifecycleAction(it, true)) }
        assertEquals(CaptureLifecycleAction.NONE, captureLifecycleAction(CaptureCompletionKind.EXCEPTION, false))
        assertEquals(CaptureLifecycleAction.NONE, captureLifecycleAction(CaptureCompletionKind.FINAL_SUCCESS, false))
        assertEquals("timed_out", spoolCleanupReason("timed_out"))
        assertEquals("cancelled", spoolCleanupReason("cancelled"))
        assertEquals("cancelled", spoolCleanupReason("internal_error"))
        assertEquals("cancelled", spoolCleanupReason("transport_exception"))
        assertEquals("cancelled", spoolCleanupReason("security_exception"))
    }

    @Test fun capturedEnvelope_decodesHeadsAndCounts() {
        val protocol = """
            RIKKAHUB_JOB_V2
            job_id=$jobId
            status=completed
            exit_code=17
            stdout_total_bytes=10
            stderr_total_bytes=3
            stdout_head_bytes=3
            stderr_head_bytes=3
            stdout_output_limited=0
            stderr_output_limited=0
            stdout_head_b64=${Base64.getEncoder().encodeToString("abc".toByteArray())}
            stderr_head_b64=${Base64.getEncoder().encodeToString("bad".toByteArray())}
        """.trimIndent()
        val parsed = parseCapturedOutputEnvelope(protocol, jobId) as ProtocolParseResult.Ok
        assertEquals(17, parsed.value.exitCode)
        assertEquals("abc", parsed.value.stdout)
        assertEquals(10, parsed.value.stdoutTotalBytes)
    }

    @Test fun protocolFailsClosedOnMismatchCorruptionAndImpossibleCounts() {
        val wrong = parseCapturedOutputEnvelope("RIKKAHUB_JOB_V2\njob_id=$jobId", "223e4567-e89b-42d3-a456-426614174000")
        assertTrue(wrong is ProtocolParseResult.Error)
        val corrupt = parseReadOutputEnvelope(
            "RIKKAHUB_READ_V1\njob_id=$jobId\nstream=stdout\noffset=0\nactual_length=9\ntotal_bytes=9\ndata_b64=@@@",
            jobId, "stdout",
        )
        assertTrue(corrupt is ProtocolParseResult.Error)
        val unknownField = """
            RIKKAHUB_READ_V1
            job_id=$jobId
            stream=stdout
            offset=0
            actual_length=0
            total_bytes=0
            data_b64=
            surprise=yes
        """.trimIndent()
        assertTrue(parseReadOutputEnvelope(unknownField, jobId, "stdout") is ProtocolParseResult.Error)
        val duplicate = unknownField.replace("surprise=yes", "offset=0")
        assertTrue(parseReadOutputEnvelope(duplicate, jobId, "stdout") is ProtocolParseResult.Error)
        val oversized = "RIKKAHUB_READ_V1\n" + "x".repeat(100_001)
        assertTrue(parseReadOutputEnvelope(oversized, jobId, "stdout") is ProtocolParseResult.Error)
        val impossibleHead = """
            RIKKAHUB_JOB_V2
            job_id=$jobId
            status=completed
            exit_code=+1
            stdout_total_bytes=49153
            stderr_total_bytes=0
            stdout_head_bytes=49153
            stderr_head_bytes=0
            stdout_output_limited=0
            stderr_output_limited=0
            stdout_head_b64=
            stderr_head_b64=
        """.trimIndent()
        assertTrue(parseCapturedOutputEnvelope(impossibleHead, jobId) is ProtocolParseResult.Error)
        val contradictoryLimit = impossibleHead
            .replace("exit_code=+1", "exit_code=1")
            .replace("stdout_total_bytes=49153", "stdout_total_bytes=0")
            .replace("stdout_head_bytes=49153", "stdout_head_bytes=0")
            .replace("stdout_output_limited=0", "stdout_output_limited=1")
        assertTrue(parseCapturedOutputEnvelope(contradictoryLimit, jobId) is ProtocolParseResult.Error)
    }

    @Test fun outputLimitEnvelope_isExplicitAndCorrelated() {
        val protocol = """
            RIKKAHUB_JOB_V2
            job_id=$jobId
            status=output_limited
            exit_code=143
            stdout_total_bytes=$MAX_RETAINED_OUTPUT_STREAM_BYTES
            stderr_total_bytes=0
            stdout_head_bytes=0
            stderr_head_bytes=0
            stdout_output_limited=1
            stderr_output_limited=0
            stdout_head_b64=
            stderr_head_b64=
        """.trimIndent()
        val parsed = parseCapturedOutputEnvelope(protocol, jobId) as ProtocolParseResult.Ok
        assertEquals("output_limited", parsed.value.status)
        assertTrue(parsed.value.stdoutOutputLimited)
        assertFalse(parsed.value.stderrOutputLimited)
        val wrongSize = protocol.replace("stdout_total_bytes=$MAX_RETAINED_OUTPUT_STREAM_BYTES", "stdout_total_bytes=7")
        assertTrue(parseCapturedOutputEnvelope(wrongSize, jobId) is ProtocolParseResult.Error)
    }

    @Test fun timeoutMayAlsoReportRetainedOutputCap() {
        val protocol = """
            RIKKAHUB_JOB_V2
            job_id=$jobId
            status=timed_out
            exit_code=124
            stdout_total_bytes=$MAX_RETAINED_OUTPUT_STREAM_BYTES
            stderr_total_bytes=0
            stdout_head_bytes=0
            stderr_head_bytes=0
            stdout_output_limited=1
            stderr_output_limited=0
            stdout_head_b64=
            stderr_head_b64=
        """.trimIndent()
        val parsed = parseCapturedOutputEnvelope(protocol, jobId) as ProtocolParseResult.Ok
        assertEquals("timed_out", parsed.value.status)
        assertTrue(parsed.value.stdoutOutputLimited)
    }

    @Test fun byteRangeUtf8Split_usesReplacementCharacter() {
        val split = byteArrayOf(0xE4.toByte(), 0xBD.toByte())
        val protocol = "RIKKAHUB_READ_V1\njob_id=$jobId\nstream=stdout\noffset=0\nactual_length=2\ntotal_bytes=3\ndata_b64=${Base64.getEncoder().encodeToString(split)}"
        val parsed = parseReadOutputEnvelope(protocol, jobId, "stdout") as ProtocolParseResult.Ok
        assertTrue(parsed.value.data.contains('\uFFFD'))
    }

    @Test fun retentionNeverDeletesLive_andAppliesTtlThenCount() {
        val jobs = listOf(
            RetentionJob("live", completedAtSeconds = 1, modifiedAtSeconds = 1, live = true),
            RetentionJob("expired", completedAtSeconds = 100, modifiedAtSeconds = 100, live = false),
            RetentionJob("newest", completedAtSeconds = 950, modifiedAtSeconds = 950, live = false),
            RetentionJob("older", completedAtSeconds = 900, modifiedAtSeconds = 900, live = false),
        )
        val deleted = outputJobsToDelete(jobs, nowSeconds = 1_000, ttlSeconds = 500, maxCompletedJobs = 1)
        assertEquals(setOf("expired", "older"), deleted)
        assertFalse("live" in deleted)
        val protected = outputJobsToDelete(
            listOf(
                RetentionJob("current", 950, 950, live = false),
                RetentionJob("newest", 900, 900, live = false),
            ),
            nowSeconds = 1_000,
            ttlSeconds = 500,
            maxCompletedJobs = 1,
            protectedJobId = "current",
        )
        assertEquals(setOf("newest"), protected)
    }

    @Test fun scriptsExposeLockedTombstoneAndFilesystemContracts() {
        assertFalse(SPOOL_CAPTURE_SCRIPT.contains(jobId))
        assertTrue(SPOOL_OUTPUT_LIMITER_SCRIPT.contains("truncate -s"))
        assertTrue(SPOOL_CAPTURE_SCRIPT.contains("stdout_limited"))
        assertTrue(SPOOL_CAPTURE_SCRIPT.indexOf("stdout_pipe=") < SPOOL_CAPTURE_SCRIPT.indexOf("if [ \"${'$'}managed\" = 1 ]"))
        assertTrue(SPOOL_CAPTURE_SCRIPT.contains("close_escaped_fifo_holders"))
        assertTrue(SPOOL_CAPTURE_SCRIPT.contains("holder_start"))
        assertTrue(SPOOL_CAPTURE_SCRIPT.contains("stdout_closer"))
        assertTrue(SPOOL_CAPTURE_SCRIPT.contains("stdout_limit + stderr_limit"))
        assertTrue(SPOOL_CAPTURE_SCRIPT.contains("flock -x 9"))
        assertTrue(SPOOL_CAPTURE_SCRIPT.contains("tombstone=true"))
        assertTrue(SPOOL_CAPTURE_LEADER_SCRIPT.indexOf("stop_file") < SPOOL_CAPTURE_LEADER_SCRIPT.indexOf("\"${'$'}@\" &"))
        assertTrue(SPOOL_CAPTURE_LEADER_SCRIPT.contains("member_pgrp"))
        assertTrue(SPOOL_CAPTURE_LEADER_SCRIPT.contains("trap ':' TERM"))
        assertTrue(SPOOL_CLEANUP_SCRIPT.contains("stop_reason.tmp"))
        assertTrue(SPOOL_CLEANUP_SCRIPT.contains("mkdir -m 700"))
        assertTrue(READ_OUTPUT_SCRIPT.contains("flock -x 9"))
        assertTrue(READ_OUTPUT_SCRIPT.contains("error=job_pending"))
        assertTrue(READ_OUTPUT_SCRIPT.contains("[ ! -L"))
        assertTrue(READ_OUTPUT_SCRIPT.contains("count=\"${'$'}length\""))
        assertFalse(READ_OUTPUT_SCRIPT.contains("eval "))
    }
}
