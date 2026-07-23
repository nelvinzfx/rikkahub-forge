package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.preferences.TermuxRuntime

internal const val READ_OUTPUT_CLEANUP_ENABLED = true

private fun readOutputError(error: String, detail: String? = null) = buildJsonObject {
    put("error", error)
    detail?.let { put("detail", it) }
    put("recovery", when (error) {
        "job_not_found" -> "The job may have expired under Termux output retention. Run the command again to create a new job_id."
        "invalid_job_id" -> "Pass the opaque job_id returned by termux_run_command without changing it."
        "invalid_stream" -> "stream must be exactly stdout or stderr."
        "corrupt_job_state" -> "The retained Termux job is corrupt. Run the command again."
        "job_pending" -> "Cleanup, creation, or metadata finalization is still in progress. Retry this read shortly."
        else -> "Retry the read once. If it still fails, run the command again to create a fresh output job."
    })
}

internal suspend fun readTermuxOutput(
    context: Context,
    jobId: String,
    stream: String,
    offset: Long,
    length: Int,
): ProtocolParseResult<ReadOutputEnvelope> {
    val result = runCommandCapture(
        ctx = context,
        executable = "/data/data/com.termux/files/usr/bin/bash",
        arguments = arrayOf(
            "-c", READ_OUTPUT_SCRIPT, "rikka-read-output", jobId, stream,
            offset.toString(), length.toString(),
            TermuxRuntime.maxRetainedOutputJobs.toString(),
            TermuxRuntime.outputTtlMs.div(1000L).toString(),
            JOB_RETENTION_LOCKED_SCRIPT,
        ),
        workingDir = "/data/data/com.termux/files/home",
        timeoutMs = 15_000L,
        cleanupOnTimeoutOrCancellation = READ_OUTPUT_CLEANUP_ENABLED,
        spoolOutput = false,
    )
    return when (result) {
        is CaptureResult.Success -> parseReadOutputEnvelope(result.stdout, jobId, stream)
        is CaptureResult.Timeout -> ProtocolParseResult.Error("read_timeout")
        is CaptureResult.Denied -> ProtocolParseResult.Error("termux_permission_denied")
        is CaptureResult.OtherError -> ProtocolParseResult.Error("termux_read_failed", result.message)
    }
}

internal fun outputReadJson(read: ReadOutputEnvelope) = buildJsonObject {
    val next = read.offset + read.actualLength
    put("job_id", read.jobId)
    put("stream", read.stream)
    put("data", read.data)
    put("offset", read.offset)
    put("actual_length", read.actualLength)
    put("total_bytes", read.totalBytes)
    put("eof", next >= read.totalBytes)
    put("next_offset", if (next < read.totalBytes) JsonPrimitive(next) else JsonNull)
}

fun termuxReadOutputTool(context: Context): Tool = Tool(
    name = "termux_read_output",
    description = "Read a byte range from stdout or stderr retained by termux_run_command. Use the returned job_id and next_offset to page output. Reads are capped at 65536 bytes and byte boundaries that split UTF-8 are decoded with replacement characters.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("job_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Opaque job_id returned by termux_run_command")
                })
                put("stream", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("stdout"); add("stderr") })
                })
                put("offset", buildJsonObject {
                    put("type", "integer")
                    put("description", "Zero-based byte offset. Default 0.")
                })
                put("length", buildJsonObject {
                    put("type", "integer")
                    put("description", "Bytes to read. Default 4096, hard maximum 65536.")
                })
            },
            required = listOf("job_id", "stream"),
        )
    },
    execute = { input ->
        val jobId = input.jsonObject["job_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val stream = input.jsonObject["stream"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!isValidTermuxJobId(jobId)) {
            return@Tool listOf(UIMessagePart.Text(readOutputError("invalid_job_id").toString()))
        }
        if (!isValidOutputStream(stream)) {
            return@Tool listOf(UIMessagePart.Text(readOutputError("invalid_stream").toString()))
        }
        val offset = clampReadOutputOffset(input.jsonObject["offset"]?.jsonPrimitive?.contentOrNull?.toLongOrNull())
        val length = clampReadOutputLength(input.jsonObject["length"]?.jsonPrimitive?.intOrNull)
        val payload = when (val result = readTermuxOutput(context, jobId, stream, offset, length)) {
            is ProtocolParseResult.Ok -> outputReadJson(result.value)
            is ProtocolParseResult.Error -> readOutputError(result.code, result.detail)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)
