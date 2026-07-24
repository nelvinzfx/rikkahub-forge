package me.rerere.rikkahub.data.ai.tools.local

import java.nio.charset.StandardCharsets
import java.util.Base64

internal const val MAX_READ_OUTPUT_LENGTH = 65_536
internal const val DEFAULT_READ_OUTPUT_LENGTH = 4_096
internal const val MAX_STDOUT_HEAD_BYTES = 49_152
internal const val MAX_STDERR_HEAD_BYTES = 12_288
internal const val MAX_COMBINED_HEAD_BYTES = 61_440
internal const val MAX_RETAINED_OUTPUT_STREAM_BYTES = 8 * 1024 * 1024
private const val MAX_PROTOCOL_CHARS = 100_000
private val JOB_ID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

internal fun isValidTermuxJobId(jobId: String): Boolean = JOB_ID_PATTERN.matches(jobId)
internal fun clampReadOutputOffset(offset: Long?): Long = (offset ?: 0L).coerceAtLeast(0L)
internal fun clampReadOutputLength(length: Int?): Int =
    (length ?: DEFAULT_READ_OUTPUT_LENGTH).coerceIn(1, MAX_READ_OUTPUT_LENGTH)
internal fun isValidOutputStream(stream: String): Boolean = stream == "stdout" || stream == "stderr"
internal fun validHeadBudgets(stdout: Int, stderr: Int): Boolean =
    stdout in 0..MAX_STDOUT_HEAD_BYTES && stderr in 0..MAX_STDERR_HEAD_BYTES &&
        stdout.toLong() + stderr.toLong() <= MAX_COMBINED_HEAD_BYTES

internal data class CapturedOutputEnvelope(
    val jobId: String,
    val status: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val stdoutHeadBytes: Int,
    val stderrHeadBytes: Int,
    val stdoutTotalBytes: Long,
    val stderrTotalBytes: Long,
    val stdoutOutputLimited: Boolean,
    val stderrOutputLimited: Boolean,
)

internal data class ReadOutputEnvelope(
    val jobId: String,
    val stream: String,
    val offset: Long,
    val data: String,
    val actualLength: Int,
    val totalBytes: Long,
)

internal sealed class ProtocolParseResult<out T> {
    data class Ok<T>(val value: T) : ProtocolParseResult<T>()
    data class Error(val code: String, val detail: String? = null) : ProtocolParseResult<Nothing>()
}

private fun parseProtocol(
    text: String,
    marker: String,
    successFields: Set<String>,
): ProtocolParseResult<Map<String, String>> {
    if (text.length > MAX_PROTOCOL_CHARS) return ProtocolParseResult.Error("protocol_too_large")
    val lines = text.trimEnd('\n').split('\n')
    if (lines.firstOrNull() != marker) return ProtocolParseResult.Error("invalid_protocol")
    val values = linkedMapOf<String, String>()
    val allowed = successFields + setOf("error", "detail")
    for (line in lines.drop(1)) {
        val separator = line.indexOf('=')
        if (separator <= 0) return ProtocolParseResult.Error("invalid_protocol")
        val key = line.substring(0, separator)
        if (key !in allowed || values.put(key, line.substring(separator + 1)) != null) {
            return ProtocolParseResult.Error("invalid_protocol")
        }
    }
    values["error"]?.let {
        if (values.keys.any { key -> key != "error" && key != "detail" }) {
            return ProtocolParseResult.Error("invalid_protocol")
        }
        if (!it.matches(Regex("^[a-z][a-z0-9_]{0,63}$"))) return ProtocolParseResult.Error("invalid_protocol")
        return ProtocolParseResult.Error(it, values["detail"])
    }
    if (values.keys != successFields) return ProtocolParseResult.Error("invalid_protocol")
    return ProtocolParseResult.Ok(values)
}

private fun Map<String, String>.unsignedLong(key: String): Long? =
    get(key)?.takeIf { it.matches(Regex("^(0|[1-9][0-9]*)$")) }?.toLongOrNull()
private fun Map<String, String>.unsignedInt(key: String): Int? = unsignedLong(key)?.takeIf { it <= Int.MAX_VALUE }?.toInt()
private fun Map<String, String>.signedInt(key: String): Int? =
    get(key)?.takeIf { it.matches(Regex("^-?(0|[1-9][0-9]*)$")) }?.toIntOrNull()

internal fun parseCapturedOutputEnvelope(text: String, expectedJobId: String): ProtocolParseResult<CapturedOutputEnvelope> {
    if (!isValidTermuxJobId(expectedJobId)) return ProtocolParseResult.Error("invalid_job_id")
    val fields = setOf(
        "job_id", "status", "exit_code", "stdout_total_bytes", "stderr_total_bytes",
        "stdout_head_bytes", "stderr_head_bytes", "stdout_output_limited", "stderr_output_limited",
        "stdout_head_b64", "stderr_head_b64",
    )
    val parsed = parseProtocol(text, "RIKKAHUB_JOB_V2", fields)
    if (parsed !is ProtocolParseResult.Ok) return parsed as ProtocolParseResult.Error
    val values = parsed.value
    val jobId = values.getValue("job_id")
    if (jobId != expectedJobId || !isValidTermuxJobId(jobId)) return ProtocolParseResult.Error("job_id_mismatch")
    val status = values.getValue("status").takeIf { it in setOf("completed", "timed_out", "cancelled", "output_limited") }
        ?: return ProtocolParseResult.Error("invalid_protocol")
    val exitCode = values.signedInt("exit_code") ?: return ProtocolParseResult.Error("invalid_protocol")
    val stdoutHeadBytes = values.unsignedInt("stdout_head_bytes") ?: return ProtocolParseResult.Error("invalid_protocol")
    val stderrHeadBytes = values.unsignedInt("stderr_head_bytes") ?: return ProtocolParseResult.Error("invalid_protocol")
    if (!validHeadBudgets(stdoutHeadBytes, stderrHeadBytes)) return ProtocolParseResult.Error("invalid_protocol")
    val stdoutTotalBytes = values.unsignedLong("stdout_total_bytes") ?: return ProtocolParseResult.Error("invalid_protocol")
    val stderrTotalBytes = values.unsignedLong("stderr_total_bytes") ?: return ProtocolParseResult.Error("invalid_protocol")
    val stdoutOutputLimited = values.getValue("stdout_output_limited").let { value ->
        when (value) { "0" -> false; "1" -> true; else -> return ProtocolParseResult.Error("invalid_protocol") }
    }
    val stderrOutputLimited = values.getValue("stderr_output_limited").let { value ->
        when (value) { "0" -> false; "1" -> true; else -> return ProtocolParseResult.Error("invalid_protocol") }
    }
    val anyOutputLimited = stdoutOutputLimited || stderrOutputLimited
    val statusMatchesLimits = when (status) {
        "completed" -> !anyOutputLimited
        "output_limited" -> anyOutputLimited
        "timed_out", "cancelled" -> true
        else -> false
    }
    if (stdoutHeadBytes.toLong() > stdoutTotalBytes || stderrHeadBytes.toLong() > stderrTotalBytes ||
        stdoutTotalBytes > MAX_RETAINED_OUTPUT_STREAM_BYTES || stderrTotalBytes > MAX_RETAINED_OUTPUT_STREAM_BYTES ||
        (stdoutOutputLimited && stdoutTotalBytes != MAX_RETAINED_OUTPUT_STREAM_BYTES.toLong()) ||
        (stderrOutputLimited && stderrTotalBytes != MAX_RETAINED_OUTPUT_STREAM_BYTES.toLong()) ||
        !statusMatchesLimits
    ) {
        return ProtocolParseResult.Error("invalid_protocol")
    }
    val stdoutRaw = try { Base64.getDecoder().decode(values.getValue("stdout_head_b64")) } catch (_: IllegalArgumentException) { return ProtocolParseResult.Error("invalid_protocol") }
    val stderrRaw = try { Base64.getDecoder().decode(values.getValue("stderr_head_b64")) } catch (_: IllegalArgumentException) { return ProtocolParseResult.Error("invalid_protocol") }
    if (stdoutRaw.size != stdoutHeadBytes || stderrRaw.size != stderrHeadBytes) return ProtocolParseResult.Error("invalid_protocol")
    return ProtocolParseResult.Ok(
        CapturedOutputEnvelope(
            jobId, status, exitCode,
            String(stdoutRaw, StandardCharsets.UTF_8), String(stderrRaw, StandardCharsets.UTF_8),
            stdoutHeadBytes, stderrHeadBytes, stdoutTotalBytes, stderrTotalBytes,
            stdoutOutputLimited, stderrOutputLimited,
        )
    )
}

internal fun parseReadOutputEnvelope(text: String, expectedJobId: String, expectedStream: String): ProtocolParseResult<ReadOutputEnvelope> {
    if (!isValidTermuxJobId(expectedJobId)) return ProtocolParseResult.Error("invalid_job_id")
    if (!isValidOutputStream(expectedStream)) return ProtocolParseResult.Error("invalid_stream")
    val fields = setOf("job_id", "stream", "offset", "actual_length", "total_bytes", "data_b64")
    val parsed = parseProtocol(text, "RIKKAHUB_READ_V1", fields)
    if (parsed !is ProtocolParseResult.Ok) return parsed as ProtocolParseResult.Error
    val values = parsed.value
    val jobId = values.getValue("job_id")
    val stream = values.getValue("stream")
    if (jobId != expectedJobId || !isValidTermuxJobId(jobId) || stream != expectedStream || !isValidOutputStream(stream)) {
        return ProtocolParseResult.Error("response_mismatch")
    }
    val offset = values.unsignedLong("offset") ?: return ProtocolParseResult.Error("invalid_protocol")
    val actualLength = values.unsignedInt("actual_length") ?: return ProtocolParseResult.Error("invalid_protocol")
    val totalBytes = values.unsignedLong("total_bytes") ?: return ProtocolParseResult.Error("invalid_protocol")
    if (actualLength > MAX_READ_OUTPUT_LENGTH || offset > totalBytes || actualLength.toLong() > totalBytes - offset) {
        return ProtocolParseResult.Error("invalid_protocol")
    }
    val raw = try { Base64.getDecoder().decode(values.getValue("data_b64")) } catch (_: IllegalArgumentException) { return ProtocolParseResult.Error("invalid_protocol") }
    if (raw.size != actualLength) return ProtocolParseResult.Error("invalid_protocol")
    // Byte ranges may begin/end inside a UTF-8 sequence. Java deliberately emits U+FFFD there.
    return ProtocolParseResult.Ok(ReadOutputEnvelope(jobId, stream, offset, String(raw, StandardCharsets.UTF_8), actualLength, totalBytes))
}

internal data class RetentionJob(
    val jobId: String,
    val completedAtSeconds: Long?,
    val modifiedAtSeconds: Long,
    val live: Boolean,
)

/** Pure mirror of locked shell retention: live jobs survive; newest completed jobs win. */
internal fun outputJobsToDelete(
    jobs: List<RetentionJob>,
    nowSeconds: Long,
    ttlSeconds: Long,
    maxCompletedJobs: Int,
    protectedJobId: String? = null,
): Set<String> {
    val expired = jobs.filter {
        !it.live && it.jobId != protectedJobId && nowSeconds - (it.completedAtSeconds ?: it.modifiedAtSeconds) > ttlSeconds
    }.mapTo(linkedSetOf()) { it.jobId }
    val retainedCompleted = jobs.filter { !it.live && it.completedAtSeconds != null && it.jobId !in expired }
        .sortedWith(compareByDescending<RetentionJob> { it.completedAtSeconds }.thenBy { it.jobId })
    val protectedCounts = retainedCompleted.any { it.jobId == protectedJobId }
    val unprotectedQuota = (maxCompletedJobs.coerceAtLeast(1) - if (protectedCounts) 1 else 0).coerceAtLeast(0)
    retainedCompleted.filter { it.jobId != protectedJobId }.drop(unprotectedQuota).forEach { expired += it.jobId }
    return expired
}

// Caller owns jobs.lock. protected_job is counted but never deleted by this pass.
internal val JOB_RETENTION_LOCKED_SCRIPT = """
    set +e
    max_jobs=${'$'}1; ttl_seconds=${'$'}2; protected_job=${'$'}3
    jobs_root=${'$'}HOME/.cache/rikkahub/jobs
    [[ ${'$'}max_jobs =~ ^(0|[1-9][0-9]*)${'$'} ]] || exit 0
    [[ ${'$'}ttl_seconds =~ ^(0|[1-9][0-9]*)${'$'} ]] || exit 0
    [ "${'$'}max_jobs" -ge 1 ] && [ "${'$'}max_jobs" -le 200 ] || exit 0
    [ "${'$'}ttl_seconds" -ge 3600 ] && [ "${'$'}ttl_seconds" -le 604800 ] || exit 0
    if [ -n "${'$'}protected_job" ]; then [[ ${'$'}protected_job =~ ^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}${'$'} ]] || exit 0; fi
    [ -d "${'$'}jobs_root" ] && [ ! -L "${'$'}jobs_root" ] || exit 0
    now=${'$'}(date +%s) || exit 0
    completed_list=${'$'}(mktemp "${'$'}jobs_root/.retention.XXXXXX") || exit 0
    chmod 600 -- "${'$'}completed_list" || { rm -f -- "${'$'}completed_list"; exit 0; }
    for job_dir in "${'$'}jobs_root"/*; do
        [ -d "${'$'}job_dir" ] && [ ! -L "${'$'}job_dir" ] || continue
        job_id=${'$'}{job_dir##*/}
        [[ ${'$'}job_id =~ ^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}${'$'} ]] || continue
        unsafe=false
        for f in state meta stdout stderr stop_reason; do [ -L "${'$'}job_dir/${'$'}f" ] && unsafe=true; done
        [ "${'$'}unsafe" = false ] || continue
        live=false
        if [ -f "${'$'}job_dir/state" ]; then
            read -r mode pid root_start extra < "${'$'}job_dir/state"
            if [ "${'$'}mode" = group ] && [ -z "${'$'}extra" ] && [[ ${'$'}pid =~ ^[1-9][0-9]*${'$'} ]] && [[ ${'$'}root_start =~ ^[1-9][0-9]*${'$'} ]]; then
                live_info=${'$'}(awk '{ line=${'$'}0; sub(/^.*\) /, "", line); split(line, f, " "); print f[3], f[20] }' "/proc/${'$'}pid/stat" 2>/dev/null)
                live_pgrp=${'$'}{live_info%% *}; live_start=${'$'}{live_info#* }
                if [ "${'$'}live_pgrp" = "${'$'}pid" ] && [ "${'$'}live_start" = "${'$'}root_start" ]; then live=true; fi
            fi
        fi
        [ "${'$'}live" = true ] && continue
        completed_at=; status=
        if [ -f "${'$'}job_dir/meta" ]; then
            status=${'$'}(sed -n 's/^status=//p' "${'$'}job_dir/meta")
            completed_at=${'$'}(sed -n 's/^completed_at=//p' "${'$'}job_dir/meta")
            case "${'$'}status" in completed|timed_out|cancelled|output_limited) ;; *) status= ;; esac
            [[ ${'$'}completed_at =~ ^(0|[1-9][0-9]*)${'$'} ]] || completed_at=
        fi
        stamp=${'$'}completed_at
        [ -n "${'$'}stamp" ] || stamp=${'$'}(stat -c %Y -- "${'$'}job_dir" 2>/dev/null)
        [[ ${'$'}stamp =~ ^(0|[1-9][0-9]*)${'$'} ]] || continue
        if [ "${'$'}job_id" != "${'$'}protected_job" ] && [ "${'$'}((now - stamp))" -gt "${'$'}ttl_seconds" ]; then
            rm -rf -- "${'$'}job_dir"
        elif [ -n "${'$'}status" ]; then
            printf '%s\t%s\n' "${'$'}completed_at" "${'$'}job_id" >> "${'$'}completed_list"
        fi
    done
    count=0
    if [ -n "${'$'}protected_job" ] && awk -F '\t' -v id="${'$'}protected_job" '${'$'}2 == id { found=1 } END { exit !found }' "${'$'}completed_list"; then count=1; fi
    while IFS=${'$'}'\t' read -r completed_at job_id; do
        [ "${'$'}job_id" = "${'$'}protected_job" ] && continue
        count=${'$'}((count + 1))
        [ "${'$'}count" -le "${'$'}max_jobs" ] && continue
        [[ ${'$'}job_id =~ ^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}${'$'} ]] &&
            [ -d "${'$'}jobs_root/${'$'}job_id" ] && [ ! -L "${'$'}jobs_root/${'$'}job_id" ] && rm -rf -- "${'$'}jobs_root/${'$'}job_id"
    done < <(sort -rn -- "${'$'}completed_list")
    rm -f -- "${'$'}completed_list"
""".trimIndent()

internal val SPOOL_CAPTURE_LEADER_SCRIPT = """
    set +e
    state_file=${'$'}1; stop_file=${'$'}2; lock_file=${'$'}3
    shift 3
    pid=${'$'}${'$'}
    info=${'$'}(awk '{ line=${'$'}0; sub(/^.*\) /, "", line); split(line, f, " "); print f[3], f[20] }' "/proc/${'$'}pid/stat" 2>/dev/null)
    pgrp=${'$'}{info%% *}; start=${'$'}{info#* }
    [[ ${'$'}pid =~ ^[1-9][0-9]*${'$'} ]] && [[ ${'$'}pgrp =~ ^[1-9][0-9]*${'$'} ]] && [[ ${'$'}start =~ ^[1-9][0-9]*${'$'} ]] || exit 125
    [ "${'$'}pgrp" = "${'$'}pid" ] || exit 125
    [ ! -L "${'$'}state_file" ] && [ ! -e "${'$'}state_file" ] || exit 125
    tmp=${'$'}state_file.${'$'}pid
    printf 'group %s %s\n' "${'$'}pid" "${'$'}start" > "${'$'}tmp" && chmod 600 -- "${'$'}tmp" && mv -f -- "${'$'}tmp" "${'$'}state_file" || exit 125
    exec 8>>"${'$'}lock_file" || exit 125
    flock -x 8 || exit 125
    if [ -f "${'$'}stop_file" ] && [ ! -L "${'$'}stop_file" ]; then flock -u 8; exit 124; fi
    flock -u 8
    exec 8>&-
    trap ':' TERM
    "${'$'}@" &
    command_pid=${'$'}!
    wait "${'$'}command_pid"; exit_code=${'$'}?
    while kill -0 "${'$'}command_pid" 2>/dev/null; do wait "${'$'}command_pid"; exit_code=${'$'}?; done
    while :; do
        member_found=false
        for stat in /proc/[0-9]*/stat; do
            [ -r "${'$'}stat" ] || continue
            member_pid=${'$'}{stat#/proc/}; member_pid=${'$'}{member_pid%/stat}
            [ "${'$'}member_pid" = "${'$'}pid" ] && continue
            member_pgrp=${'$'}(awk '{ line=${'$'}0; sub(/^.*\) /, "", line); split(line, f, " "); print f[3] }' "${'$'}stat" 2>/dev/null)
            if [ "${'$'}member_pgrp" = "${'$'}pid" ]; then member_found=true; break; fi
        done
        [ "${'$'}member_found" = true ] || break
        sleep 0.05
    done
    exit "${'$'}exit_code"
""".trimIndent()

internal val SPOOL_OUTPUT_LIMITER_SCRIPT = """
    set +e
    state_file=${'$'}1; output_file=${'$'}2; cap=${'$'}3; limited_file=${'$'}4
    [[ ${'$'}cap =~ ^[1-9][0-9]*${'$'} ]] && [ "${'$'}cap" -le ${MAX_RETAINED_OUTPUT_STREAM_BYTES} ] || exit 125
    [ -f "${'$'}output_file" ] && [ ! -L "${'$'}output_file" ] && [ ! -L "${'$'}limited_file" ] || exit 125
    block_size=65536; blocks=${'$'}(((cap + block_size) / block_size))
    dd bs="${'$'}block_size" count="${'$'}blocks" iflag=fullblock of="${'$'}output_file" status=none || exit 125
    captured=${'$'}(stat -c %s -- "${'$'}output_file") || exit 125
    if [ "${'$'}captured" -gt "${'$'}cap" ]; then
        truncate -s "${'$'}cap" -- "${'$'}output_file" || exit 125
        : > "${'$'}limited_file"; chmod 600 -- "${'$'}limited_file"
        if [ -f "${'$'}state_file" ] && [ ! -L "${'$'}state_file" ]; then
            read -r mode pid root_start extra < "${'$'}state_file"
            if [ "${'$'}mode" = group ] && [ -z "${'$'}extra" ] && [[ ${'$'}pid =~ ^[1-9][0-9]*${'$'} ]] && [[ ${'$'}root_start =~ ^[1-9][0-9]*${'$'} ]] && [ "${'$'}pid" -gt 1 ]; then
                live_info=${'$'}(awk '{ line=${'$'}0; sub(/^.*\) /, "", line); split(line, f, " "); print f[3], f[20] }' "/proc/${'$'}pid/stat" 2>/dev/null)
                live_pgrp=${'$'}{live_info%% *}; live_start=${'$'}{live_info#* }
                if [ "${'$'}live_pgrp" = "${'$'}pid" ] && [ "${'$'}live_start" = "${'$'}root_start" ]; then
                    kill -TERM -- "-${'$'}pid" 2>/dev/null
                    sleep 0.5
                    live_info=${'$'}(awk '{ line=${'$'}0; sub(/^.*\) /, "", line); split(line, f, " "); print f[3], f[20] }' "/proc/${'$'}pid/stat" 2>/dev/null)
                    live_pgrp=${'$'}{live_info%% *}; live_start=${'$'}{live_info#* }
                    if [ "${'$'}live_pgrp" = "${'$'}pid" ] && [ "${'$'}live_start" = "${'$'}root_start" ]; then kill -KILL -- "-${'$'}pid" 2>/dev/null; fi
                fi
            fi
        fi
        cat >/dev/null
    fi
""".trimIndent()

internal val SPOOL_CAPTURE_SCRIPT = """
    set +e
    umask 077
    job_id=${'$'}1; stdout_limit=${'$'}2; stderr_limit=${'$'}3; max_jobs=${'$'}4; ttl_seconds=${'$'}5
    retention_script=${'$'}6; leader=${'$'}7; limiter=${'$'}8; managed=${'$'}9
    shift 9
    [[ ${'$'}job_id =~ ^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}${'$'} ]] || exit 125
    [[ ${'$'}stdout_limit =~ ^(0|[1-9][0-9]*)${'$'} ]] && [[ ${'$'}stderr_limit =~ ^(0|[1-9][0-9]*)${'$'} ]] || exit 125
    [[ ${'$'}max_jobs =~ ^(0|[1-9][0-9]*)${'$'} ]] && [[ ${'$'}ttl_seconds =~ ^(0|[1-9][0-9]*)${'$'} ]] || exit 125
    [ "${'$'}stdout_limit" -le 49152 ] && [ "${'$'}stderr_limit" -le 12288 ] && [ "${'$'}((stdout_limit + stderr_limit))" -le 61440 ] || exit 125
    [ "${'$'}max_jobs" -ge 1 ] && [ "${'$'}max_jobs" -le 200 ] && [ "${'$'}ttl_seconds" -ge 3600 ] && [ "${'$'}ttl_seconds" -le 604800 ] || exit 125
    [ "${'$'}stdout_limit" -ge 1000 ] && [ "${'$'}stderr_limit" -ge 500 ] || exit 125
    [ "${'$'}managed" = 0 ] || [ "${'$'}managed" = 1 ] || exit 125
    base=${'$'}HOME/.cache/rikkahub; jobs_root=${'$'}base/jobs; lock_file=${'$'}base/jobs.lock; job_dir=${'$'}jobs_root/${'$'}job_id
    [ ! -L "${'$'}base" ] || exit 125; mkdir -p -m 700 -- "${'$'}base" || exit 125; [ -d "${'$'}base" ] || exit 125; chmod 700 -- "${'$'}base" || exit 125
    [ ! -L "${'$'}jobs_root" ] || exit 125; mkdir -p -m 700 -- "${'$'}jobs_root" || exit 125; [ -d "${'$'}jobs_root" ] || exit 125; chmod 700 -- "${'$'}jobs_root" || exit 125
    [ ! -L "${'$'}lock_file" ] || exit 125; [ ! -e "${'$'}lock_file" ] || [ -f "${'$'}lock_file" ] || exit 125
    exec 9>>"${'$'}lock_file" || exit 125; chmod 600 -- "${'$'}lock_file"; flock -x 9 || exit 125
    bash -c "${'$'}retention_script" rikka-retention "${'$'}max_jobs" "${'$'}ttl_seconds" "${'$'}job_id" >/dev/null 2>&1
    tombstone=false
    if [ -e "${'$'}job_dir" ]; then
        [ -d "${'$'}job_dir" ] && [ ! -L "${'$'}job_dir" ] || exit 125
        [ -f "${'$'}job_dir/stop_reason" ] && [ ! -L "${'$'}job_dir/stop_reason" ] || exit 125
        chmod 700 -- "${'$'}job_dir" || exit 125
        chmod 600 -- "${'$'}job_dir/stop_reason" || exit 125
        extras=${'$'}(find "${'$'}job_dir" -mindepth 1 -maxdepth 1 ! -name stop_reason -print -quit)
        [ -z "${'$'}extras" ] || exit 125
        read -r stop_reason extra < "${'$'}job_dir/stop_reason"
        case "${'$'}stop_reason:${'$'}extra" in timed_out:|cancelled:) tombstone=true ;; *) exit 125 ;; esac
    else
        mkdir -m 700 -- "${'$'}job_dir" || exit 125
    fi
    for f in stdout stderr meta state stop_reason stdout.pipe stderr.pipe stdout_limited stderr_limited; do [ -L "${'$'}job_dir/${'$'}f" ] && exit 125; done
    : > "${'$'}job_dir/stdout" && : > "${'$'}job_dir/stderr" || exit 125
    chmod 600 -- "${'$'}job_dir/stdout" "${'$'}job_dir/stderr" || exit 125
    rm -f -- "${'$'}job_dir/stdout_limited" "${'$'}job_dir/stderr_limited"
    flock -u 9
    stdout_file=${'$'}job_dir/stdout; stderr_file=${'$'}job_dir/stderr; state_file=${'$'}job_dir/state; stop_file=${'$'}job_dir/stop_reason
    stdout_pipe=; stderr_pipe=; stdout_limiter=; stderr_limiter=; leader_pid=
    cleanup_runtime() {
        out_pid=${'$'}stdout_limiter; err_pid=${'$'}stderr_limiter
        stdout_limiter=; stderr_limiter=
        [ -z "${'$'}out_pid" ] || kill "${'$'}out_pid" 2>/dev/null
        [ -z "${'$'}err_pid" ] || kill "${'$'}err_pid" 2>/dev/null
        [ -z "${'$'}stdout_pipe" ] || rm -f -- "${'$'}stdout_pipe"
        [ -z "${'$'}stderr_pipe" ] || rm -f -- "${'$'}stderr_pipe"
    }
    trap cleanup_runtime EXIT
    trap 'trap - EXIT HUP INT TERM; cleanup_runtime; exit 130' HUP INT TERM
    close_escaped_fifo_holders() {
        for fifo in "${'$'}stdout_pipe" "${'$'}stderr_pipe"; do
            for fd in /proc/[0-9]*/fd/*; do
                [ -L "${'$'}fd" ] || continue
                holder=${'$'}{fd#/proc/}; holder=${'$'}{holder%%/*}
                holder_fd=${'$'}{fd##*/}
                case "${'$'}holder" in "${'$'}${'$'}"|"${'$'}leader_pid"|"${'$'}stdout_limiter"|"${'$'}stderr_limiter") continue ;; esac
                [[ ${'$'}holder =~ ^[1-9][0-9]*${'$'} ]] || continue
                holder_start=${'$'}(awk '{ line=${'$'}0; sub(/^.*\) /, "", line); split(line, f, " "); print f[20] }' "/proc/${'$'}holder/stat" 2>/dev/null)
                [[ ${'$'}holder_start =~ ^[1-9][0-9]*${'$'} ]] || continue
                fd_flags=${'$'}(awk '/^flags:/{print ${'$'}2}' "/proc/${'$'}holder/fdinfo/${'$'}holder_fd" 2>/dev/null)
                [[ ${'$'}fd_flags =~ ^[0-7]+${'$'} ]] || continue
                [ "${'$'}((8#${'$'}fd_flags & 3))" -ne 0 ] || continue
                [ "${'$'}(readlink -- "${'$'}fd" 2>/dev/null)" = "${'$'}fifo" ] || continue
                current_start=${'$'}(awk '{ line=${'$'}0; sub(/^.*\) /, "", line); split(line, f, " "); print f[20] }' "/proc/${'$'}holder/stat" 2>/dev/null)
                [ "${'$'}current_start" = "${'$'}holder_start" ] || continue
                kill -TERM "${'$'}holder" 2>/dev/null
                sleep 0.05
                current_start=${'$'}(awk '{ line=${'$'}0; sub(/^.*\) /, "", line); split(line, f, " "); print f[20] }' "/proc/${'$'}holder/stat" 2>/dev/null)
                if [ "${'$'}current_start" = "${'$'}holder_start" ]; then kill -KILL "${'$'}holder" 2>/dev/null; fi
            done
        done
    }
    exit_code=0
    if [ "${'$'}tombstone" = true ]; then
        exit_code=124
    else
        stdout_pipe=${'$'}job_dir/stdout.pipe; stderr_pipe=${'$'}job_dir/stderr.pipe
        mkfifo -- "${'$'}stdout_pipe" "${'$'}stderr_pipe" || exit 125
        chmod 600 -- "${'$'}stdout_pipe" "${'$'}stderr_pipe" || exit 125
        bash -c "${'$'}limiter" rikka-output-limit "${'$'}state_file" "${'$'}stdout_file" ${MAX_RETAINED_OUTPUT_STREAM_BYTES} "${'$'}job_dir/stdout_limited" < "${'$'}stdout_pipe" & stdout_limiter=${'$'}!
        bash -c "${'$'}limiter" rikka-output-limit "${'$'}state_file" "${'$'}stderr_file" ${MAX_RETAINED_OUTPUT_STREAM_BYTES} "${'$'}job_dir/stderr_limited" < "${'$'}stderr_pipe" & stderr_limiter=${'$'}!
        if [ "${'$'}managed" = 1 ]; then
            command -v setsid >/dev/null 2>&1 || {
                : > "${'$'}stdout_pipe" & stdout_closer=${'$'}!
                printf '%s\n' 'managed capture requires setsid (install util-linux)' > "${'$'}stderr_pipe"
                wait "${'$'}stdout_closer" 2>/dev/null
                exit_code=125
            }
            if [ "${'$'}exit_code" = 0 ]; then
                setsid bash -c "${'$'}leader" rikka-spool-leader "${'$'}state_file" "${'$'}stop_file" "${'$'}lock_file" "${'$'}@" > "${'$'}stdout_pipe" 2> "${'$'}stderr_pipe" &
                leader_pid=${'$'}!
                wait "${'$'}leader_pid"; exit_code=${'$'}?
                leader_pid=
                close_escaped_fifo_holders
            fi
        else
            # Detached ownership is transferred at dispatch. Timeout cleanup is intentionally not
            # requested for managed=0; the wrapped launch must still stay within spool quotas.
            "${'$'}@" > "${'$'}stdout_pipe" 2> "${'$'}stderr_pipe"; exit_code=${'$'}?
        fi
        wait "${'$'}stdout_limiter"; stdout_limiter_rc=${'$'}?; stdout_limiter=
        wait "${'$'}stderr_limiter"; stderr_limiter_rc=${'$'}?; stderr_limiter=
        [ "${'$'}stdout_limiter_rc" -eq 0 ] && [ "${'$'}stderr_limiter_rc" -eq 0 ] || exit_code=125
        rm -f -- "${'$'}stdout_pipe" "${'$'}stderr_pipe"; stdout_pipe=; stderr_pipe=
    fi
    flock -x 9 || exit 125
    [ -d "${'$'}job_dir" ] && [ ! -L "${'$'}job_dir" ] || exit 125
    for f in stdout stderr; do [ -f "${'$'}job_dir/${'$'}f" ] && [ ! -L "${'$'}job_dir/${'$'}f" ] || exit 125; done
    status=completed
    stdout_output_limited=0; stderr_output_limited=0
    [ -f "${'$'}job_dir/stdout_limited" ] && [ ! -L "${'$'}job_dir/stdout_limited" ] && stdout_output_limited=1
    [ -f "${'$'}job_dir/stderr_limited" ] && [ ! -L "${'$'}job_dir/stderr_limited" ] && stderr_output_limited=1
    if [ -e "${'$'}stop_file" ]; then
        [ -f "${'$'}stop_file" ] && [ ! -L "${'$'}stop_file" ] || exit 125
        read -r status extra < "${'$'}stop_file"; case "${'$'}status:${'$'}extra" in timed_out:|cancelled:) ;; *) exit 125 ;; esac
    elif [ "${'$'}stdout_output_limited" = 1 ] || [ "${'$'}stderr_output_limited" = 1 ]; then
        status=output_limited
    fi
    completed_at=${'$'}(date +%s); stdout_total=${'$'}(stat -c %s -- "${'$'}stdout_file") || exit 125; stderr_total=${'$'}(stat -c %s -- "${'$'}stderr_file") || exit 125
    [ "${'$'}stdout_total" -le ${MAX_RETAINED_OUTPUT_STREAM_BYTES} ] && [ "${'$'}stderr_total" -le ${MAX_RETAINED_OUTPUT_STREAM_BYTES} ] || exit 125
    stdout_head_bytes=${'$'}stdout_total; [ "${'$'}stdout_head_bytes" -gt "${'$'}stdout_limit" ] && stdout_head_bytes=${'$'}stdout_limit
    stderr_head_bytes=${'$'}stderr_total; [ "${'$'}stderr_head_bytes" -gt "${'$'}stderr_limit" ] && stderr_head_bytes=${'$'}stderr_limit
    meta_tmp=${'$'}job_dir/meta.tmp.${'$'}${'$'}
    printf 'version=2\njob_id=%s\nstatus=%s\nexit_code=%s\ncompleted_at=%s\nstdout_bytes=%s\nstderr_bytes=%s\nstdout_output_limited=%s\nstderr_output_limited=%s\n' "${'$'}job_id" "${'$'}status" "${'$'}exit_code" "${'$'}completed_at" "${'$'}stdout_total" "${'$'}stderr_total" "${'$'}stdout_output_limited" "${'$'}stderr_output_limited" > "${'$'}meta_tmp" && chmod 600 -- "${'$'}meta_tmp" && mv -f -- "${'$'}meta_tmp" "${'$'}job_dir/meta" || exit 125
    rm -f -- "${'$'}state_file"
    bash -c "${'$'}retention_script" rikka-retention "${'$'}max_jobs" "${'$'}ttl_seconds" "${'$'}job_id" >/dev/null 2>&1
    stdout_b64=${'$'}(head -c "${'$'}stdout_head_bytes" -- "${'$'}stdout_file" | base64 -w 0) || exit 125
    stderr_b64=${'$'}(head -c "${'$'}stderr_head_bytes" -- "${'$'}stderr_file" | base64 -w 0) || exit 125
    flock -u 9
    trap - EXIT HUP INT TERM
    printf 'RIKKAHUB_JOB_V2\njob_id=%s\nstatus=%s\nexit_code=%s\nstdout_total_bytes=%s\nstderr_total_bytes=%s\nstdout_head_bytes=%s\nstderr_head_bytes=%s\nstdout_output_limited=%s\nstderr_output_limited=%s\nstdout_head_b64=%s\nstderr_head_b64=%s\n' "${'$'}job_id" "${'$'}status" "${'$'}exit_code" "${'$'}stdout_total" "${'$'}stderr_total" "${'$'}stdout_head_bytes" "${'$'}stderr_head_bytes" "${'$'}stdout_output_limited" "${'$'}stderr_output_limited" "${'$'}stdout_b64" "${'$'}stderr_b64"
    exit "${'$'}exit_code"
""".trimIndent()

internal val SPOOL_CLEANUP_SCRIPT = """
    set +e
    umask 077
    job_id=${'$'}1; reason=${'$'}2
    [[ ${'$'}job_id =~ ^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}${'$'} ]] || exit 0
    case "${'$'}reason" in timed_out|cancelled) ;; *) exit 0 ;; esac
    base=${'$'}HOME/.cache/rikkahub; jobs_root=${'$'}base/jobs; lock_file=${'$'}base/jobs.lock; job_dir=${'$'}jobs_root/${'$'}job_id
    [ ! -L "${'$'}base" ] || exit 0; mkdir -p -m 700 -- "${'$'}base" || exit 0; chmod 700 -- "${'$'}base" || exit 0
    [ ! -L "${'$'}jobs_root" ] || exit 0; mkdir -p -m 700 -- "${'$'}jobs_root" || exit 0; chmod 700 -- "${'$'}jobs_root" || exit 0
    [ ! -L "${'$'}lock_file" ] || exit 0; [ ! -e "${'$'}lock_file" ] || [ -f "${'$'}lock_file" ] || exit 0
    exec 9>>"${'$'}lock_file" || exit 0; chmod 600 -- "${'$'}lock_file"; flock -x 9 || exit 0
    if [ -e "${'$'}job_dir" ]; then [ -d "${'$'}job_dir" ] && [ ! -L "${'$'}job_dir" ] || exit 0; else mkdir -m 700 -- "${'$'}job_dir" || exit 0; fi
    chmod 700 -- "${'$'}job_dir" || exit 0
    for f in stdout stderr meta state stop_reason stdout.pipe stderr.pipe stdout_limited stderr_limited; do [ -L "${'$'}job_dir/${'$'}f" ] && exit 0; done
    unknown=${'$'}(find "${'$'}job_dir" -mindepth 1 -maxdepth 1 ! -name stdout ! -name stderr ! -name meta ! -name state ! -name stop_reason ! -name stdout.pipe ! -name stderr.pipe ! -name stdout_limited ! -name stderr_limited -print -quit)
    [ -z "${'$'}unknown" ] || exit 0
    if [ ! -f "${'$'}job_dir/meta" ]; then
        stop_tmp=${'$'}job_dir/stop_reason.tmp.${'$'}${'$'}
        [ ! -e "${'$'}stop_tmp" ] && [ ! -L "${'$'}stop_tmp" ] || exit 0
        set -C
        printf '%s\n' "${'$'}reason" > "${'$'}stop_tmp" || { set +C; exit 0; }
        set +C
        chmod 600 -- "${'$'}stop_tmp" && mv -f -- "${'$'}stop_tmp" "${'$'}job_dir/stop_reason"
    fi
    flock -u 9
    state_file=${'$'}job_dir/state
    [ -f "${'$'}state_file" ] && [ ! -L "${'$'}state_file" ] || exit 0
    reject_state() { exit 0; }
    read -r mode pid root_start extra < "${'$'}state_file"
    [ "${'$'}mode" = group ] && [ -z "${'$'}extra" ] && [[ ${'$'}pid =~ ^[1-9][0-9]*${'$'} ]] && [[ ${'$'}root_start =~ ^[1-9][0-9]*${'$'} ]] || reject_state
    [ "${'$'}pid" -gt 1 ] || reject_state
    live_info=${'$'}(awk '{ line=${'$'}0; sub(/^.*\) /, "", line); split(line, f, " "); print f[3], f[20] }' "/proc/${'$'}pid/stat" 2>/dev/null)
    live_pgrp=${'$'}{live_info%% *}; live_start=${'$'}{live_info#* }
    [ -n "${'$'}live_info" ] && [ "${'$'}live_start" = "${'$'}root_start" ] && [ "${'$'}live_pgrp" = "${'$'}pid" ] || reject_state
    kill -TERM -- "-${'$'}pid" 2>/dev/null; sleep 0.5
    live_info=${'$'}(awk '{ line=${'$'}0; sub(/^.*\) /, "", line); split(line, f, " "); print f[3], f[20] }' "/proc/${'$'}pid/stat" 2>/dev/null)
    live_pgrp=${'$'}{live_info%% *}; live_start=${'$'}{live_info#* }
    if [ "${'$'}live_pgrp" = "${'$'}pid" ] && [ "${'$'}live_start" = "${'$'}root_start" ]; then kill -KILL -- "-${'$'}pid" 2>/dev/null; fi
""".trimIndent()

internal val READ_OUTPUT_SCRIPT = """
    set +e
    umask 077
    job_id=${'$'}1; stream=${'$'}2; offset=${'$'}3; length=${'$'}4; max_jobs=${'$'}5; ttl_seconds=${'$'}6; retention_script=${'$'}7
    [[ ${'$'}job_id =~ ^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}${'$'} ]] || { printf 'RIKKAHUB_READ_V1\nerror=invalid_job_id\n'; exit 0; }
    case "${'$'}stream" in stdout|stderr) ;; *) printf 'RIKKAHUB_READ_V1\nerror=invalid_stream\n'; exit 0 ;; esac
    [[ ${'$'}offset =~ ^(0|[1-9][0-9]*)${'$'} ]] && [[ ${'$'}length =~ ^(0|[1-9][0-9]*)${'$'} ]] || { printf 'RIKKAHUB_READ_V1\nerror=invalid_range\n'; exit 0; }
    [[ ${'$'}max_jobs =~ ^(0|[1-9][0-9]*)${'$'} ]] && [[ ${'$'}ttl_seconds =~ ^(0|[1-9][0-9]*)${'$'} ]] || { printf 'RIKKAHUB_READ_V1\nerror=invalid_retention\n'; exit 0; }
    [ "${'$'}max_jobs" -ge 1 ] && [ "${'$'}max_jobs" -le 200 ] && [ "${'$'}ttl_seconds" -ge 3600 ] && [ "${'$'}ttl_seconds" -le 604800 ] || { printf 'RIKKAHUB_READ_V1\nerror=invalid_retention\n'; exit 0; }
    [ "${'$'}length" -ge 1 ] && [ "${'$'}length" -le 65536 ] || { printf 'RIKKAHUB_READ_V1\nerror=invalid_range\n'; exit 0; }
    base=${'$'}HOME/.cache/rikkahub; jobs_root=${'$'}base/jobs; lock_file=${'$'}base/jobs.lock; job_dir=${'$'}jobs_root/${'$'}job_id
    [ -d "${'$'}base" ] && [ ! -L "${'$'}base" ] && [ -d "${'$'}jobs_root" ] && [ ! -L "${'$'}jobs_root" ] || { printf 'RIKKAHUB_READ_V1\nerror=job_not_found\n'; exit 0; }
    [ ! -L "${'$'}lock_file" ] && { [ ! -e "${'$'}lock_file" ] || [ -f "${'$'}lock_file" ]; } || { printf 'RIKKAHUB_READ_V1\nerror=corrupt_job_state\n'; exit 0; }
    exec 9>>"${'$'}lock_file" || { printf 'RIKKAHUB_READ_V1\nerror=output_unreadable\n'; exit 0; }; flock -x 9 || exit 0
    bash -c "${'$'}retention_script" rikka-retention "${'$'}max_jobs" "${'$'}ttl_seconds" "${'$'}job_id" >/dev/null 2>&1
    [ -d "${'$'}job_dir" ] && [ ! -L "${'$'}job_dir" ] || { printf 'RIKKAHUB_READ_V1\nerror=job_not_found\n'; exit 0; }
    for f in stdout stderr meta state stop_reason stdout.pipe stderr.pipe stdout_limited stderr_limited; do [ -L "${'$'}job_dir/${'$'}f" ] && { printf 'RIKKAHUB_READ_V1\nerror=corrupt_job_state\n'; exit 0; }; done
    if [ -f "${'$'}job_dir/meta" ]; then
        [ ! -L "${'$'}job_dir/meta" ] || { printf 'RIKKAHUB_READ_V1\nerror=corrupt_job_state\n'; exit 0; }
        meta_version=${'$'}(sed -n 's/^version=//p' "${'$'}job_dir/meta"); meta_job=${'$'}(sed -n 's/^job_id=//p' "${'$'}job_dir/meta")
        meta_status=${'$'}(sed -n 's/^status=//p' "${'$'}job_dir/meta"); meta_exit=${'$'}(sed -n 's/^exit_code=//p' "${'$'}job_dir/meta")
        meta_completed=${'$'}(sed -n 's/^completed_at=//p' "${'$'}job_dir/meta"); meta_stdout=${'$'}(sed -n 's/^stdout_bytes=//p' "${'$'}job_dir/meta"); meta_stderr=${'$'}(sed -n 's/^stderr_bytes=//p' "${'$'}job_dir/meta")
        meta_stdout_limited=${'$'}(sed -n 's/^stdout_output_limited=//p' "${'$'}job_dir/meta"); meta_stderr_limited=${'$'}(sed -n 's/^stderr_output_limited=//p' "${'$'}job_dir/meta")
        [ "${'$'}meta_job" = "${'$'}job_id" ] || { printf 'RIKKAHUB_READ_V1\nerror=corrupt_job_state\n'; exit 0; }
        case "${'$'}meta_version" in 1) meta_stdout_limited=0; meta_stderr_limited=0 ;; 2) ;; *) printf 'RIKKAHUB_READ_V1\nerror=corrupt_job_state\n'; exit 0 ;; esac
        case "${'$'}meta_status" in completed|timed_out|cancelled|output_limited) ;; *) printf 'RIKKAHUB_READ_V1\nerror=corrupt_job_state\n'; exit 0 ;; esac
        [[ ${'$'}meta_exit =~ ^-?(0|[1-9][0-9]*)${'$'} ]] && [[ ${'$'}meta_completed =~ ^(0|[1-9][0-9]*)${'$'} ]] && [[ ${'$'}meta_stdout =~ ^(0|[1-9][0-9]*)${'$'} ]] && [[ ${'$'}meta_stderr =~ ^(0|[1-9][0-9]*)${'$'} ]] || { printf 'RIKKAHUB_READ_V1\nerror=corrupt_job_state\n'; exit 0; }
        case "${'$'}meta_stdout_limited:${'$'}meta_stderr_limited:${'$'}meta_status" in 0:0:completed|0:0:timed_out|0:0:cancelled|0:1:output_limited|1:0:output_limited|1:1:output_limited|0:1:timed_out|1:0:timed_out|1:1:timed_out|0:1:cancelled|1:0:cancelled|1:1:cancelled) ;; *) printf 'RIKKAHUB_READ_V1\nerror=corrupt_job_state\n'; exit 0 ;; esac
        if [ "${'$'}meta_version" = 2 ]; then
            [ "${'$'}meta_stdout" -le ${MAX_RETAINED_OUTPUT_STREAM_BYTES} ] && [ "${'$'}meta_stderr" -le ${MAX_RETAINED_OUTPUT_STREAM_BYTES} ] || { printf 'RIKKAHUB_READ_V1\nerror=corrupt_job_state\n'; exit 0; }
            [ "${'$'}meta_stdout_limited" = 0 ] || [ "${'$'}meta_stdout" -eq ${MAX_RETAINED_OUTPUT_STREAM_BYTES} ] || { printf 'RIKKAHUB_READ_V1\nerror=corrupt_job_state\n'; exit 0; }
            [ "${'$'}meta_stderr_limited" = 0 ] || [ "${'$'}meta_stderr" -eq ${MAX_RETAINED_OUTPUT_STREAM_BYTES} ] || { printf 'RIKKAHUB_READ_V1\nerror=corrupt_job_state\n'; exit 0; }
        fi
        [ -f "${'$'}job_dir/stdout" ] && [ -f "${'$'}job_dir/stderr" ] || { printf 'RIKKAHUB_READ_V1\nerror=corrupt_job_state\n'; exit 0; }
        [ "${'$'}meta_stdout" = "${'$'}(stat -c %s -- "${'$'}job_dir/stdout")" ] && [ "${'$'}meta_stderr" = "${'$'}(stat -c %s -- "${'$'}job_dir/stderr")" ] || { printf 'RIKKAHUB_READ_V1\nerror=corrupt_job_state\n'; exit 0; }
    elif [ -f "${'$'}job_dir/state" ]; then
        [ ! -L "${'$'}job_dir/state" ] || { printf 'RIKKAHUB_READ_V1\nerror=corrupt_job_state\n'; exit 0; }
        read -r mode pid root_start extra < "${'$'}job_dir/state"
        [ "${'$'}mode" = group ] && [ -z "${'$'}extra" ] && [[ ${'$'}pid =~ ^[1-9][0-9]*${'$'} ]] && [[ ${'$'}root_start =~ ^[1-9][0-9]*${'$'} ]] || { printf 'RIKKAHUB_READ_V1\nerror=corrupt_job_state\n'; exit 0; }
        live_info=${'$'}(awk '{ line=${'$'}0; sub(/^.*\) /, "", line); split(line, f, " "); print f[3], f[20] }' "/proc/${'$'}pid/stat" 2>/dev/null); live_pgrp=${'$'}{live_info%% *}; live_start=${'$'}{live_info#* }
        live=false
        if [ "${'$'}live_pgrp" = "${'$'}pid" ] && [ "${'$'}live_start" = "${'$'}root_start" ]; then live=true; fi
        [ "${'$'}live" = true ] || { printf 'RIKKAHUB_READ_V1\nerror=job_pending\ndetail=Job finalization is still in progress.\n'; exit 0; }
    elif [ -f "${'$'}job_dir/stop_reason" ]; then
        [ ! -L "${'$'}job_dir/stop_reason" ] || { printf 'RIKKAHUB_READ_V1\nerror=corrupt_job_state\n'; exit 0; }
        read -r reason extra < "${'$'}job_dir/stop_reason"; case "${'$'}reason:${'$'}extra" in timed_out:|cancelled:) printf 'RIKKAHUB_READ_V1\nerror=job_pending\ndetail=Cleanup or finalization is still in progress.\n'; exit 0 ;; *) printf 'RIKKAHUB_READ_V1\nerror=corrupt_job_state\n'; exit 0 ;; esac
    else
        printf 'RIKKAHUB_READ_V1\nerror=job_pending\ndetail=Job creation or finalization is still in progress.\n'; exit 0
    fi
    file=${'$'}job_dir/${'$'}stream
    [ -f "${'$'}file" ] && [ ! -L "${'$'}file" ] || { printf 'RIKKAHUB_READ_V1\nerror=job_pending\n'; exit 0; }
    total=${'$'}(stat -c %s -- "${'$'}file") || { printf 'RIKKAHUB_READ_V1\nerror=output_unreadable\n'; exit 0; }
    effective_offset=${'$'}offset; [ "${'$'}effective_offset" -gt "${'$'}total" ] && effective_offset=${'$'}total
    data_b64=${'$'}(dd if="${'$'}file" bs=1 skip="${'$'}effective_offset" count="${'$'}length" status=none | base64 -w 0) || { printf 'RIKKAHUB_READ_V1\nerror=output_unreadable\n'; exit 0; }
    actual=${'$'}((total - effective_offset)); [ "${'$'}actual" -gt "${'$'}length" ] && actual=${'$'}length
    printf 'RIKKAHUB_READ_V1\njob_id=%s\nstream=%s\noffset=%s\nactual_length=%s\ntotal_bytes=%s\ndata_b64=%s\n' "${'$'}job_id" "${'$'}stream" "${'$'}effective_offset" "${'$'}actual" "${'$'}total" "${'$'}data_b64"
""".trimIndent()
