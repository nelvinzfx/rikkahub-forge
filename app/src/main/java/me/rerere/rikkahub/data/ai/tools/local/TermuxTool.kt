package me.rerere.rikkahub.data.ai.tools.local

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.AgentTurnTracker
import me.rerere.rikkahub.data.preferences.TermuxDefaults
import me.rerere.rikkahub.data.preferences.TermuxRuntime
import java.util.UUID

private const val TERMUX_PACKAGE = "com.termux"
private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
private const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
private const val TERMUX_BIN_DIR = "/data/data/com.termux/files/usr/bin"
private const val TERMUX_HOME_DIR = "/data/data/com.termux/files/home"
private const val MAX_CAPTURE_TIMEOUT_MS = 10L * 60 * 1000
internal const val PUBLIC_RUN_COMMAND_SPOOL_OUTPUT = true

// The direct path is the c52a316d contract used by tmux, verify, and transcription: argv-safe
// setsid lifecycle management with complete RUN_COMMAND stdout/stderr and state under run-command.
internal val DIRECT_CAPTURE_SCRIPT = """
    set +e
    job_id=${'$'}1
    shift
    state_dir=${'$'}HOME/.cache/rikkahub/run-command
    state_file=${'$'}state_dir/${'$'}job_id
    mkdir -p -- "${'$'}state_dir" || exit 125
    umask 077
    leader=${'$'}1
    shift
    if ! command -v setsid >/dev/null 2>&1; then
        printf '%s\n' 'managed capture requires setsid (install util-linux)' >&2
        exit 125
    fi
    setsid bash -c "${'$'}leader" rikka-leader "${'$'}state_file" "${'$'}@" &
    leader_pid=${'$'}!
    wait "${'$'}leader_pid"
    exit "${'$'}?"
""".trimIndent()

internal val DIRECT_CAPTURE_LEADER_SCRIPT = """
    set +e
    state_file=${'$'}1
    shift
    pid=${'$'}${'$'}
    info=${'$'}(awk '{ line=${'$'}0; sub(/^.*\) /, "", line); split(line, f, " "); print f[3], f[20] }' "/proc/${'$'}pid/stat" 2>/dev/null)
    pgrp=${'$'}{info%% *}
    start=${'$'}{info#* }
    case "${'$'}pid" in *[!0-9]*|'') exit 125 ;; esac
    case "${'$'}pgrp" in *[!0-9]*|'') exit 125 ;; esac
    case "${'$'}start" in *[!0-9]*|'') exit 125 ;; esac
    [ "${'$'}pgrp" = "${'$'}pid" ] || exit 125
    tmp=${'$'}state_file.${'$'}pid
    printf 'group %s %s\n' "${'$'}pid" "${'$'}start" > "${'$'}tmp" &&
        mv -f -- "${'$'}tmp" "${'$'}state_file" || { rm -f -- "${'$'}tmp"; exit 125; }
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

internal val DIRECT_CAPTURE_CLEANUP_SCRIPT = """
    set +e
    job_id=${'$'}1
    state_file=${'$'}HOME/.cache/rikkahub/run-command/${'$'}job_id
    n=0
    while [ ! -s "${'$'}state_file" ] && [ "${'$'}n" -lt 20 ]; do sleep 0.1; n=${'$'}((n + 1)); done
    [ -s "${'$'}state_file" ] || exit 0
    reject_state() { rm -f -- "${'$'}state_file"; exit 0; }
    read -r mode pid root_start extra < "${'$'}state_file"
    [ "${'$'}mode" = group ] || reject_state
    case "${'$'}pid" in *[!0-9]*|'') reject_state ;; esac
    case "${'$'}root_start" in *[!0-9]*|'') reject_state ;; esac
    [ -z "${'$'}extra" ] && [ "${'$'}pid" -gt 1 ] 2>/dev/null && [ "${'$'}root_start" -gt 0 ] 2>/dev/null || reject_state
    live_info=${'$'}(awk '{ line=${'$'}0; sub(/^.*\) /, "", line); split(line, f, " "); print f[3], f[20] }' "/proc/${'$'}pid/stat" 2>/dev/null)
    live_pgrp=${'$'}{live_info%% *}; live_start=${'$'}{live_info#* }
    [ -n "${'$'}live_info" ] && [ "${'$'}live_start" = "${'$'}root_start" ] && [ "${'$'}live_pgrp" = "${'$'}pid" ] || reject_state
    kill -TERM -- "-${'$'}pid" 2>/dev/null
    sleep 0.5
    live_info=${'$'}(awk '{ line=${'$'}0; sub(/^.*\) /, "", line); split(line, f, " "); print f[3], f[20] }' "/proc/${'$'}pid/stat" 2>/dev/null)
    live_pgrp=${'$'}{live_info%% *}; live_start=${'$'}{live_info#* }
    if [ "${'$'}live_pgrp" = "${'$'}pid" ] && [ "${'$'}live_start" = "${'$'}root_start" ]; then kill -KILL -- "-${'$'}pid" 2>/dev/null; fi
    rm -f -- "${'$'}state_file"
""".trimIndent()

internal val DIRECT_CAPTURE_RELEASE_SCRIPT = """
    job_id=${'$'}1
    rm -f -- "${'$'}HOME/.cache/rikkahub/run-command/${'$'}job_id"
""".trimIndent()

internal data class CaptureLaunch(
    val executable: String,
    val arguments: Array<String>,
    val jobId: String?,
    val spooled: Boolean,
    val managed: Boolean,
)

internal fun captureTimeoutMs(requestedMs: Long): Long = requestedMs.coerceIn(1L, MAX_CAPTURE_TIMEOUT_MS)
internal fun shouldManageCapture(backgroundRequested: Boolean, shellCommandMode: Boolean): Boolean =
    !backgroundRequested || !shellCommandMode

internal enum class CaptureLifecycleAction { RELEASE, CLEANUP, NONE }
internal enum class CaptureCompletionKind { FINAL_SUCCESS, INTERNAL_ERROR, EXCEPTION, TIMEOUT, CANCELLATION }
internal fun captureLifecycleAction(kind: CaptureCompletionKind, managed: Boolean): CaptureLifecycleAction = when {
    !managed -> CaptureLifecycleAction.NONE
    kind == CaptureCompletionKind.FINAL_SUCCESS -> CaptureLifecycleAction.RELEASE
    else -> CaptureLifecycleAction.CLEANUP
}

/** The spool protocol persists only terminal timeout/cancellation states. */
internal fun spoolCleanupReason(reason: String): String =
    if (reason == "timed_out") "timed_out" else "cancelled"

internal fun buildDirectCaptureLaunch(
    executable: String,
    arguments: Array<String>,
    managed: Boolean,
    jobId: String = UUID.randomUUID().toString(),
): CaptureLaunch = if (managed) {
    CaptureLaunch(
        "$TERMUX_BIN_DIR/bash",
        arrayOf("-c", DIRECT_CAPTURE_SCRIPT, "rikka-capture", jobId, DIRECT_CAPTURE_LEADER_SCRIPT, executable, *arguments),
        jobId,
        spooled = false,
        managed = true,
    )
} else {
    CaptureLaunch(executable, arguments, null, spooled = false, managed = false)
}

internal fun buildSpoolCaptureLaunch(
    executable: String,
    arguments: Array<String>,
    managed: Boolean,
    jobId: String = UUID.randomUUID().toString(),
): CaptureLaunch {
    require(isValidTermuxJobId(jobId)) { "invalid generated Termux job id" }
    return CaptureLaunch(
        "$TERMUX_BIN_DIR/bash",
        arrayOf(
            "-c", SPOOL_CAPTURE_SCRIPT, "rikka-spool", jobId,
            TermuxRuntime.maxStdoutBytes.toString(), TermuxRuntime.maxStderrBytes.toString(),
            TermuxRuntime.maxRetainedOutputJobs.toString(), TermuxRuntime.outputTtlMs.div(1000L).toString(),
            JOB_RETENTION_LOCKED_SCRIPT, SPOOL_CAPTURE_LEADER_SCRIPT, SPOOL_OUTPUT_LIMITER_SCRIPT,
            if (managed) "1" else "0", executable, *arguments,
        ),
        jobId,
        spooled = true,
        managed = managed,
    )
}

internal fun buildCaptureLaunch(
    executable: String,
    arguments: Array<String>,
    managed: Boolean,
    spoolOutput: Boolean = false,
    jobId: String = UUID.randomUUID().toString(),
): CaptureLaunch = if (spoolOutput) {
    buildSpoolCaptureLaunch(executable, arguments, managed, jobId)
} else {
    buildDirectCaptureLaunch(executable, arguments, managed, jobId)
}

internal fun directCleanupArguments(jobId: String): Array<String> =
    arrayOf("-c", DIRECT_CAPTURE_CLEANUP_SCRIPT, "rikka-cleanup", jobId)
internal fun spoolCleanupArguments(jobId: String, reason: String): Array<String> =
    arrayOf("-c", SPOOL_CLEANUP_SCRIPT, "rikka-spool-cleanup", jobId, reason)
internal fun directReleaseArguments(jobId: String): Array<String> =
    arrayOf("-c", DIRECT_CAPTURE_RELEASE_SCRIPT, "rikka-release", jobId)

// Termux delivers stdout / stderr / exitCode via a result Bundle attached to the
// RUN_COMMAND_PENDING_INTENT we register. Documented at:
// https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
private const val EXTRA_RESULT_BUNDLE = "result"
private const val RESULT_KEY_STDOUT = "stdout"
private const val RESULT_KEY_STDERR = "stderr"
private const val RESULT_KEY_EXIT_CODE = "exitCode"
private const val RESULT_KEY_ERR = "err"
private const val RESULT_KEY_ERRMSG = "errmsg"

internal enum class RunCommandValueKind { INT, STRING, OTHER }

internal sealed class RunCommandPayloadShape {
    data class Success(val err: Int) : RunCommandPayloadShape()
    data class InternalError(val err: Int) : RunCommandPayloadShape()
    data object Ignore : RunCommandPayloadShape()
}

/** Pure validation seam shared by BroadcastReceiver and JVM tests. */
internal fun validateRunCommandPayloadShape(values: Map<String, Pair<RunCommandValueKind, Any?>>?): RunCommandPayloadShape {
    values ?: return RunCommandPayloadShape.Ignore
    val errEntry = values[RESULT_KEY_ERR] ?: return RunCommandPayloadShape.Ignore
    if (errEntry.first != RunCommandValueKind.INT) return RunCommandPayloadShape.Ignore
    val err = errEntry.second as? Int ?: return RunCommandPayloadShape.Ignore
    return if (err == -1) {
        val exit = values[RESULT_KEY_EXIT_CODE]
        val stdout = values[RESULT_KEY_STDOUT]
        val stderr = values[RESULT_KEY_STDERR]
        if (exit?.first == RunCommandValueKind.INT && exit.second is Int &&
            stdout?.first == RunCommandValueKind.STRING && stdout.second is String &&
            stderr?.first == RunCommandValueKind.STRING && stderr.second is String
        ) RunCommandPayloadShape.Success(err) else RunCommandPayloadShape.Ignore
    } else {
        val errmsg = values[RESULT_KEY_ERRMSG]
        if (errmsg?.first == RunCommandValueKind.STRING && errmsg.second is String) {
            RunCommandPayloadShape.InternalError(err)
        } else {
            RunCommandPayloadShape.Ignore
        }
    }
}

private fun bundlePayloadShape(bundle: Bundle): RunCommandPayloadShape {
    fun entry(key: String): Pair<RunCommandValueKind, Any?>? {
        if (!bundle.containsKey(key)) return null
        @Suppress("DEPRECATION") val value = bundle.get(key)
        val kind = when (value) {
            is Int -> RunCommandValueKind.INT
            is String -> RunCommandValueKind.STRING
            else -> RunCommandValueKind.OTHER
        }
        return kind to value
    }
    return validateRunCommandPayloadShape(
        listOf(RESULT_KEY_ERR, RESULT_KEY_EXIT_CODE, RESULT_KEY_STDOUT, RESULT_KEY_STDERR, RESULT_KEY_ERRMSG)
            .mapNotNull { key -> entry(key)?.let { key to it } }
            .toMap()
    )
}

// DEFAULT_CAPTURE_TIMEOUT_MS, MAX_RETURNED_STDOUT, MAX_RETURNED_STDERR removed — read from
// TermuxRuntime at call time so they reflect any user edits from Settings → Termux.

/**
 * Termux installation + integration probe used by both the LLM tool and the toggle row in
 * the assistant tools page.
 */
internal object TermuxIntegration {
    enum class State { NOT_INSTALLED, NO_PERMISSION, READY }

    /**
     * Process-scoped timestamp of the last successful end-to-end smoke test. The toggle row
     * in the assistant Local-tools page reads this so the green indicator persists across
     * navigations within the session — without it the dot would reset to orange every time
     * the user left and re-entered the page. Resets on app restart, which is acceptable
     * since re-verifying is one tap.
     */
    @Volatile
    var lastVerifiedOkAtMs: Long = 0L
        private set

    fun markVerifiedOk() {
        lastVerifiedOkAtMs = System.currentTimeMillis()
    }

    fun clearVerified() {
        lastVerifiedOkAtMs = 0L
    }

    fun state(ctx: Context): State {
        val pm = ctx.packageManager
        val installed = try {
            pm.getPackageInfo(TERMUX_PACKAGE, 0); true
        } catch (_: Throwable) { false }
        if (!installed) return State.NOT_INSTALLED
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, "com.termux.permission.RUN_COMMAND"
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return State.NO_PERMISSION
        return State.READY
    }

    /**
     * Run a tiny `echo` smoke test through the Termux RUN_COMMAND service and wait for the
     * result bundle. Returns true iff the bundle came back with our marker on stdout, which
     * proves the entire chain works (manifest perm + runtime perm + allow-external-apps in
     * termux.properties + Termux is allowed to start a background session).
     */
    suspend fun verify(ctx: Context, timeoutMs: Long = TermuxRuntime.verifyTimeoutMs): VerifyResult {
        val s = state(ctx)
        if (s == State.NOT_INSTALLED) return VerifyResult.NotInstalled
        if (s == State.NO_PERMISSION) return VerifyResult.NoPermission
        val result = runCommandCapture(
            ctx = ctx,
            executable = "$TERMUX_BIN_DIR/bash",
            arguments = arrayOf("-c", "echo RIKKAHUB_OK"),
            workingDir = TERMUX_HOME_DIR,
            timeoutMs = timeoutMs,
            spoolOutput = false,
        )
        return when (result) {
            is CaptureResult.Success -> if (result.stdout.contains("RIKKAHUB_OK"))
                VerifyResult.Ok else VerifyResult.UnexpectedOutput(result.stdout)
            is CaptureResult.Timeout -> VerifyResult.AllowExternalAppsMissing
            is CaptureResult.Denied -> VerifyResult.NoPermission
            is CaptureResult.OtherError -> VerifyResult.OtherError(result.message)
        }
    }

    sealed class VerifyResult {
        data object NotInstalled : VerifyResult()
        data object NoPermission : VerifyResult()
        data object AllowExternalAppsMissing : VerifyResult()
        data object Ok : VerifyResult()
        data class UnexpectedOutput(val stdout: String) : VerifyResult()
        data class OtherError(val message: String) : VerifyResult()
    }
}

internal sealed class CaptureResult {
    data class Success(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val jobId: String? = null,
        val status: String = "completed",
        val stdoutTotalBytes: Long = stdout.toByteArray(Charsets.UTF_8).size.toLong(),
        val stderrTotalBytes: Long = stderr.toByteArray(Charsets.UTF_8).size.toLong(),
        val stdoutHeadBytes: Int = stdout.toByteArray(Charsets.UTF_8).size,
        val stderrHeadBytes: Int = stderr.toByteArray(Charsets.UTF_8).size,
        val stdoutOutputLimited: Boolean = false,
        val stderrOutputLimited: Boolean = false,
    ) : CaptureResult()
    data class Timeout(val jobId: String?) : CaptureResult()
    data object Denied : CaptureResult()
    data class OtherError(val message: String, val jobId: String? = null) : CaptureResult()
}

/** A non-zero process exit is still an ordinary completed shell result. */
internal fun completedCommandResult(stdout: String, stderr: String, exitCode: Int): CaptureResult =
    CaptureResult.Success(stdout = stdout, stderr = stderr, exitCode = exitCode)

internal fun completedSpooledCommandResult(protocol: String, expectedJobId: String): CaptureResult =
    when (val parsed = parseCapturedOutputEnvelope(protocol, expectedJobId)) {
        is ProtocolParseResult.Error -> CaptureResult.OtherError(
            message = "termux_output_${parsed.code}${parsed.detail?.let { ": $it" }.orEmpty()}",
            jobId = expectedJobId,
        )
        is ProtocolParseResult.Ok -> parsed.value.let { envelope ->
            CaptureResult.Success(
                stdout = envelope.stdout,
                stderr = envelope.stderr,
                exitCode = envelope.exitCode,
                jobId = envelope.jobId,
                status = envelope.status,
                stdoutTotalBytes = envelope.stdoutTotalBytes,
                stderrTotalBytes = envelope.stderrTotalBytes,
                stdoutHeadBytes = envelope.stdoutHeadBytes,
                stderrHeadBytes = envelope.stderrHeadBytes,
                stdoutOutputLimited = envelope.stdoutOutputLimited,
                stderrOutputLimited = envelope.stderrOutputLimited,
            )
        }
    }

private fun dispatchCaptureMaintenance(ctx: Context, arguments: Array<String>, operation: String) {
    val maintenance = Intent().apply {
        setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
        action = TERMUX_RUN_COMMAND_ACTION
        putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_BIN_DIR/bash")
        putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments)
        putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME_DIR)
        putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
    }
    try {
        ctx.startService(maintenance)
    } catch (t: Throwable) {
        // There is no cross-app process handle to fall back to. Avoid command/output logging; the
        // exception class is enough to diagnose a maintenance dispatch failure in logcat.
        android.util.Log.w("RikkaTermux", "capture $operation dispatch failed: ${t::class.java.simpleName}")
    }
}

private fun dispatchCaptureCleanup(ctx: Context, launch: CaptureLaunch, reason: String) {
    val jobId = launch.jobId ?: return
    val arguments = if (launch.spooled) {
        spoolCleanupArguments(jobId, spoolCleanupReason(reason))
    } else {
        directCleanupArguments(jobId)
    }
    dispatchCaptureMaintenance(ctx, arguments, "cleanup")
}

private fun dispatchCaptureRelease(ctx: Context, launch: CaptureLaunch) {
    if (!launch.spooled) launch.jobId?.let {
        dispatchCaptureMaintenance(ctx, directReleaseArguments(it), "release")
    }
}

/**
 * Dispatch a Termux command and suspend until it completes (or times out), returning the
 * captured output. Implementation registers a one-shot BroadcastReceiver, hands a
 * PendingIntent for it to Termux, and waits on a CompletableDeferred until Termux fires
 * the broadcast back. Always uses background mode internally because the result-bundle
 * delivery path only fires for background commands.
 */
internal suspend fun runCommandCapture(
    ctx: Context,
    executable: String,
    arguments: Array<String>,
    workingDir: String,
    // Default reads from the runtime holder so callers that don't pass a timeout get the
    // user-configured value, not a stale compile-time constant.
    timeoutMs: Long = TermuxRuntime.commandTimeoutMs,
    cleanupOnTimeoutOrCancellation: Boolean = true,
    spoolOutput: Boolean = false,
): CaptureResult {
    // Mark Termux as freshly touched BEFORE we issue the broadcast. The notification
    // listener uses this signal to suppress Termux's foreground-service notification
    // updates (the "0 sessions, N tasks" pill) from auto-forwarding to Telegram while
    // the agent is actively running shell commands. Without this, every internal
    // runCommandCapture (whisper_status, transcribe_audio_file, etc.) makes Termux
    // flap its notification and the listener forwards each flap to the user's chat.
    // The touch here covers ALL callers of runCommandCapture, so individual tool
    // factories don't have to remember to call it themselves.
    me.rerere.rikkahub.data.ai.AgentTurnTracker.touchPackage(TERMUX_PACKAGE, "com.termux.api")
    val resultDeferred = CompletableDeferred<Bundle>()
    val launch = buildCaptureLaunch(
        executable = executable,
        arguments = arguments,
        managed = cleanupOnTimeoutOrCancellation,
        spoolOutput = spoolOutput,
    )
    val boundedTimeoutMs = captureTimeoutMs(timeoutMs)
    val resultAction = "${ctx.packageName}.TERMUX_RESULT_${UUID.randomUUID()}"
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            // Termux's RunCommandService fires the PendingIntent *twice* in 0.118.x:
            // - once almost immediately as a "started" / acknowledgement broadcast with
            //   intent.extras == null
            // - again when the command actually completes, this time with a "result" Bundle
            //   containing stdout / stderr / exitCode.
            // FLAG_ONE_SHOT used to consume the first empty fire and the real one was
            // never delivered. Now we ignore empty broadcasts and only complete the
            // deferred when we see a usable payload.
            val keys = intent.extras?.keySet()?.joinToString(",")
            val bundle = intent.getBundleExtra(EXTRA_RESULT_BUNDLE)
            android.util.Log.i(
                "RikkaTermux",
                "broadcast: action=${intent.action} hasExtras=${intent.extras != null} extraKeys=[$keys] hasResultBundle=${bundle != null}",
            )
            if (bundle == null && intent.extras == null) return  // empty ack, wait for real fire
            // Some Termux variants put the keys directly on the broadcast intent rather than
            // nested under "result". Validate either shape before accepting it as final. Populated
            // acknowledgements and malformed payloads are ignored without releasing lifecycle state.
            val flat = intent.extras
            val nestedShape = bundle?.let(::bundlePayloadShape) ?: RunCommandPayloadShape.Ignore
            val flatShape = flat?.let(::bundlePayloadShape) ?: RunCommandPayloadShape.Ignore
            val effective = when {
                nestedShape != RunCommandPayloadShape.Ignore -> bundle
                flatShape != RunCommandPayloadShape.Ignore -> flat
                else -> null
            }
            android.util.Log.i(
                "RikkaTermux",
                "result candidate nested=${nestedShape::class.java.simpleName} flat=${flatShape::class.java.simpleName}",
            )
            if (effective != null && resultDeferred.isActive) resultDeferred.complete(effective)
        }
    }
    val filter = IntentFilter(resultAction)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        ctx.registerReceiver(receiver, filter)
    }

    val pi = try {
        val resultIntent = Intent(resultAction).setPackage(ctx.packageName)
        PendingIntent.getBroadcast(
            ctx,
            resultAction.hashCode(),
            resultIntent,
            // Termux fires this PendingIntent twice (started ack + final result). Using
            // FLAG_ONE_SHOT used to consume the ack and lose the real result; FLAG_MUTABLE
            // lets Termux append its own extras to the intent it sends.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    } catch (t: Throwable) {
        try { ctx.unregisterReceiver(receiver) } catch (_: Throwable) {}
        return CaptureResult.OtherError("PendingIntent creation failed: ${t.message}")
    }

    val intent = Intent().apply {
        setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
        action = TERMUX_RUN_COMMAND_ACTION
        putExtra("com.termux.RUN_COMMAND_PATH", launch.executable)
        putExtra("com.termux.RUN_COMMAND_ARGUMENTS", launch.arguments)
        putExtra("com.termux.RUN_COMMAND_WORKDIR", workingDir)
        putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        putExtra(EXTRA_PENDING_INTENT, pi)
    }

    return try {
        ctx.startService(intent)
        val bundle = withTimeoutOrNull(boundedTimeoutMs) { resultDeferred.await() }
        if (bundle == null) {
            if (cleanupOnTimeoutOrCancellation) dispatchCaptureCleanup(ctx, launch, "timed_out")
            CaptureResult.Timeout(launch.jobId)
        } else {
            // Per the Termux RUN_COMMAND wiki: `err = -1` (= Activity.RESULT_OK) means
            // "no internal error" — i.e. the success sentinel. Any value other than -1
            // is an actual Termux internal failure (service start failed, manual exit,
            // OS killed it, etc). Earlier revisions inverted this and rejected the
            // success path because err=-1 != 0.
            val errCode = bundle.getInt(RESULT_KEY_ERR, -1)
            val completed = if (errCode != -1) {
                val errMsg = bundle.getString(RESULT_KEY_ERRMSG).orEmpty()
                if (errMsg.contains("PermissionDenied", ignoreCase = true) ||
                    errMsg.contains("not allowed", ignoreCase = true)
                ) {
                    CaptureResult.Denied
                } else {
                    CaptureResult.OtherError("err=$errCode: $errMsg")
                }
            } else if (spoolOutput) {
                completedSpooledCommandResult(
                    protocol = bundle.getString(RESULT_KEY_STDOUT).orEmpty(),
                    expectedJobId = requireNotNull(launch.jobId),
                )
            } else {
                completedCommandResult(
                    stdout = bundle.getString(RESULT_KEY_STDOUT).orEmpty(),
                    stderr = bundle.getString(RESULT_KEY_STDERR).orEmpty(),
                    exitCode = bundle.getInt(RESULT_KEY_EXIT_CODE, -1),
                )
            }
            // Only a complete final process result may release direct lifecycle state. Termux
            // internal errors have not proven process completion and must request cleanup.
            when (captureLifecycleAction(
                if (errCode == -1) CaptureCompletionKind.FINAL_SUCCESS else CaptureCompletionKind.INTERNAL_ERROR,
                launch.managed,
            )) {
                CaptureLifecycleAction.RELEASE -> dispatchCaptureRelease(ctx, launch)
                CaptureLifecycleAction.CLEANUP -> dispatchCaptureCleanup(ctx, launch, "internal_error")
                CaptureLifecycleAction.NONE -> Unit
            }
            completed
        }
    } catch (t: CancellationException) {
        if (cleanupOnTimeoutOrCancellation) dispatchCaptureCleanup(ctx, launch, "cancelled")
        throw t
    } catch (t: SecurityException) {
        if (cleanupOnTimeoutOrCancellation) dispatchCaptureCleanup(ctx, launch, "security_exception")
        CaptureResult.Denied
    } catch (t: Throwable) {
        if (cleanupOnTimeoutOrCancellation) dispatchCaptureCleanup(ctx, launch, "transport_exception")
        CaptureResult.OtherError(t.message ?: t::class.java.simpleName)
    } finally {
        try { ctx.unregisterReceiver(receiver) } catch (_: Throwable) {}
        try { pi.cancel() } catch (_: Throwable) {}
    }
}

/**
 * LLM-callable termux command tool. Defaults to capture mode (background command, output
 * returned in the JSON envelope so the model can reason about it). Pass `interactive=true`
 * for the legacy "open visible Termux session" mode where the user sees output live but
 * the bot cannot read it.
 */
private const val MAX_TERMUX_RUN_FIELD_BYTES = 64 * 1024
private const val MAX_TERMUX_RUN_REQUEST_BYTES = 96 * 1024

internal data class TermuxRunCommandRequest(
    val command: String,
    val workingDir: String,
    val interactive: Boolean,
    val background: Boolean,
    val timeoutMs: Long,
)

private fun strictRunString(root: JsonObject, key: String): String? {
    val value = root[key] ?: return null
    return (value as? JsonPrimitive)?.takeIf { it.isString }?.content
}

private fun strictRunBoolean(root: JsonObject, key: String): Boolean? {
    val value = root[key] ?: return false
    return (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
}

private fun boundedRunFieldBytes(value: String): Int? {
    if ('\u0000' in value) return null
    return when (val encoded = encodeUtf8StrictBounded(value, MAX_TERMUX_RUN_FIELD_BYTES)) {
        is BoundedUtf8Result.Ok -> encoded.bytes.size
        BoundedUtf8Result.InvalidUtf8, BoundedUtf8Result.TooLarge -> null
    }
}

internal fun parseTermuxRunCommandRequest(input: JsonElement): PublicInputResult<TermuxRunCommandRequest> {
    val root = input as? JsonObject
        ?: return PublicInputResult.Error(PublicInputError("request_must_be_object"))
    val allowed = setOf("command", "working_dir", "interactive", "background", "timeout_seconds")
    val unknown = root.keys - allowed
    if (unknown.isNotEmpty()) {
        return PublicInputResult.Error(PublicInputError("unknown_fields:${unknown.sorted().joinToString(",")}"))
    }

    fun optionalString(key: String): PublicInputResult<String?> {
        if (root[key] == null) return PublicInputResult.Ok(null)
        val value = strictRunString(root, key)
            ?: return PublicInputResult.Error(PublicInputError("${key}_must_be_string"))
        if (boundedRunFieldBytes(value) == null) {
            return PublicInputResult.Error(PublicInputError("${key}_too_large_or_invalid_utf8"))
        }
        return PublicInputResult.Ok(value)
    }

    val command = when (val parsed = optionalString("command")) {
        is PublicInputResult.Error -> return parsed
        is PublicInputResult.Ok -> parsed.value
            ?: return PublicInputResult.Error(PublicInputError("command_is_required"))
    }
    val workingDir = when (val parsed = optionalString("working_dir")) {
        is PublicInputResult.Error -> return parsed
        is PublicInputResult.Ok -> parsed.value ?: TermuxRuntime.defaultWorkingDir
    }

    val interactive = strictRunBoolean(root, "interactive")
        ?: return PublicInputResult.Error(PublicInputError("interactive_must_be_boolean"))
    val background = strictRunBoolean(root, "background")
        ?: return PublicInputResult.Error(PublicInputError("background_must_be_boolean"))
    val timeoutSeconds = when (val raw = root["timeout_seconds"]) {
        null -> 0
        is JsonPrimitive -> raw.takeIf { !it.isString }?.intOrNull
            ?: return PublicInputResult.Error(PublicInputError("timeout_seconds_must_be_integer"))
        else -> return PublicInputResult.Error(PublicInputError("timeout_seconds_must_be_integer"))
    }
    if (timeoutSeconds !in 0..TermuxDefaults.MAX_COMMAND_TIMEOUT_SECONDS) {
        return PublicInputResult.Error(PublicInputError("timeout_seconds_out_of_range"))
    }
    if (command.isBlank()) {
        return PublicInputResult.Error(PublicInputError("command_must_not_be_blank"))
    }
    if (interactive && background) {
        return PublicInputResult.Error(PublicInputError("interactive_background_conflict"))
    }
    val aggregateBytes = listOf(command, workingDir).sumOf { boundedRunFieldBytes(it)!!.toLong() }
    if (aggregateBytes > MAX_TERMUX_RUN_REQUEST_BYTES) {
        return PublicInputResult.Error(PublicInputError("request_too_large"))
    }
    val timeoutMs = if (timeoutSeconds == 0) {
        TermuxRuntime.commandTimeoutMs
    } else {
        timeoutSeconds.toLong() * 1_000L
    }
    return PublicInputResult.Ok(
        TermuxRunCommandRequest(command, workingDir, interactive, background, timeoutMs)
    )
}

internal fun termuxRunCommandSchema(): InputSchema.Obj = InputSchema.Obj(
    properties = buildJsonObject {
        put("command", buildJsonObject {
            put("type", "string")
            put("description", "Shell command line, e.g. 'pkg update && pkg upgrade -y'.")
            put("minLength", 1)
        })
        put("working_dir", buildJsonObject {
            put("type", "string")
            put("description", "Working directory. Defaults to Termux home (/data/data/com.termux/files/home).")
        })
        put("interactive", buildJsonObject {
            put("type", "boolean")
            put("description", "If true, opens a visible Termux session and does NOT capture output. Default false (background + capture).")
        })
        put("background", buildJsonObject {
            put("type", "boolean")
            put("description", "If true, launch the command fully detached (nohup, streams redirected) and return immediately with its PID. Use for servers / long-running processes that would otherwise keep the capture pipe open and block until timeout. Default false.")
        })
        put("timeout_seconds", buildJsonObject {
            put("type", "integer")
            put("description", "Capture-mode timeout in seconds. Omit or pass 0 to use the user-configured default (Settings -> Termux). Max ${TermuxDefaults.MAX_COMMAND_TIMEOUT_SECONDS} s.")
            put("minimum", 0)
            put("maximum", TermuxDefaults.MAX_COMMAND_TIMEOUT_SECONDS)
        })
    },
    required = listOf("command"),
    additionalProperties = false,
)

fun termuxRunCommandTool(context: Context): Tool = Tool(
    name = "termux_run_command",
    description = """
        Execute a shell command in Termux. By default the command runs in the background and
        a bounded stdout / stderr head, byte counts, truncation flags, and an opaque job_id are
        returned. Use termux_read_output with job_id and next offsets for remaining output. Pass
        interactive=true to instead open a visible Termux session - useful when the user
        explicitly wants to watch output live or when the command needs an interactive prompt;
        in that mode no output is returned. Termux must have allow-external-apps=true set in
        ~/.termux/termux.properties (one-time setup). In command mode, apt/apt-get are
        automatically wrapped with DEBIAN_FRONTEND=noninteractive and safe dpkg defaults;
        do not add extra -y flags unless the user specifically asked for unattended upgrades.
    """.trimIndent().replace("\n", " "),
    parameters = { termuxRunCommandSchema() },
    execute = { input ->
        val request = when (val parsed = parseTermuxRunCommandRequest(input)) {
            is PublicInputResult.Error -> {
                return@Tool listOf(
                    UIMessagePart.Text(buildJsonObject { put("error", parsed.value.code) }.toString())
                )
            }
            is PublicInputResult.Ok -> parsed.value
        }
        val rawCommand = request.command
        val workingDir = request.workingDir
        val interactive = request.interactive
        val background = request.background
        val timeoutMs = request.timeoutMs

        // Pre-flight: Termux installed?
        when (TermuxIntegration.state(context)) {
            TermuxIntegration.State.NOT_INSTALLED -> {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "termux_not_installed")
                            put("recovery", "Install Termux from the official GitHub releases page: https://github.com/termux/termux-app/releases . Do not use the Play Store or F-Droid build - those are unmaintained.")
                        }.toString()
                    )
                )
            }
            TermuxIntegration.State.NO_PERMISSION -> {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "termux_permission_not_granted")
                            put("recovery", "Toggle Termux on in Assistant -> Local tools so the runtime permission dialog appears, OR run: adb shell pm grant ${context.packageName} com.termux.permission.RUN_COMMAND")
                        }.toString()
                    )
                )
            }
            TermuxIntegration.State.READY -> Unit  // proceed
        }

        // Mark Termux + Termux:API as freshly touched by the agent so the notification
        // listener stops forwarding their persistent foreground notifications ("0 sessions",
        // "1 session", "2 sessions") to the user's Telegram chat for the duration of the
        // turn plus a short grace window. The user knows the agent is running Termux work;
        // they don't need a Telegram ping for every session counter flap.
        AgentTurnTracker.touchPackage(TERMUX_PACKAGE, "com.termux.api")

        // Prepend a noninteractive preamble so apt/pkg upgrades don't hang waiting for
        // "keep your existing config?" debconf prompts. Public calls always use bash -c;
        // internal helpers still use argv-safe RUN_COMMAND path/argument dispatch directly.
        val preamble = if (TermuxRuntime.aptWrapEnabled) {
            "export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a; " +
                "apt(){ command apt -o Dpkg::Options::='--force-confdef' -o Dpkg::Options::='--force-confold' \"\$@\"; }; " +
                "apt-get(){ command apt-get -o Dpkg::Options::='--force-confdef' -o Dpkg::Options::='--force-confold' \"\$@\"; }; " +
                "export -f apt apt-get; "
        } else ""
        // background: detach so a long-running child doesn't keep the capture pipe open and
        // stall the result bundle until timeout. Same inherited-fd hazard as the SSH exec
        // channel; wrapDetachedCommand applies the identical nohup + redirect + echo-pid fix.
        val body = if (background) wrapDetachedCommand(rawCommand) else rawCommand
        val resolvedExe = "$TERMUX_BIN_DIR/bash"
        val resolvedArgs = arrayOf("-c", preamble + body)

        if (interactive) {
            // Legacy fire-and-forget interactive session. Cannot read output back.
            val intent = Intent().apply {
                setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
                action = TERMUX_RUN_COMMAND_ACTION
                putExtra("com.termux.RUN_COMMAND_PATH", resolvedExe)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", resolvedArgs)
                putExtra("com.termux.RUN_COMMAND_WORKDIR", workingDir)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            return@Tool try {
                context.startService(intent)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("mode", "interactive")
                            put("note", "Opened a visible Termux session. Output is NOT captured by this tool. The user can see it directly.")
                        }.toString()
                    )
                )
            } catch (t: SecurityException) {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "termux_permission_denied")
                            put("recovery", "In Termux, run: mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties. Force-stop Termux and reopen, then retry.")
                        }.toString()
                    )
                )
            } catch (t: Throwable) {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "dispatch_failed")
                            put("reason", t.message ?: t::class.java.simpleName)
                        }.toString()
                    )
                )
            }
        }

        // Capture mode (default).
        val payload = when (val res = runCommandCapture(
            ctx = context,
            executable = resolvedExe,
            arguments = resolvedArgs,
            workingDir = workingDir,
            timeoutMs = timeoutMs,
            // A deliberately detached command owns its lifetime after this short PID-returning
            // call; do not register it for timeout/cancellation cleanup.
            cleanupOnTimeoutOrCancellation = shouldManageCapture(
                backgroundRequested = background,
                shellCommandMode = true,
            ),
            spoolOutput = PUBLIC_RUN_COMMAND_SPOOL_OUTPUT,
        )) {
            is CaptureResult.Success -> buildJsonObject {
                put("success", true)
                put("mode", "capture")
                res.jobId?.let { put("job_id", it) }
                put("status", res.status)
                put("exit_code", res.exitCode)
                put("timed_out", res.status == "timed_out")
                put("stdout", res.stdout)
                put("stderr", res.stderr)
                val stdoutTruncated = res.stdoutHeadBytes.toLong() < res.stdoutTotalBytes
                val stderrTruncated = res.stderrHeadBytes.toLong() < res.stderrTotalBytes
                put("stdout_truncated", stdoutTruncated)
                put("stderr_truncated", stderrTruncated)
                put("stdout_total_bytes", res.stdoutTotalBytes)
                put("stderr_total_bytes", res.stderrTotalBytes)
                put("stdout_output_limited", res.stdoutOutputLimited)
                put("stderr_output_limited", res.stderrOutputLimited)
                put("stdout_next_offset", if (stdoutTruncated) kotlinx.serialization.json.JsonPrimitive(res.stdoutHeadBytes) else kotlinx.serialization.json.JsonNull)
                put("stderr_next_offset", if (stderrTruncated) kotlinx.serialization.json.JsonPrimitive(res.stderrHeadBytes) else kotlinx.serialization.json.JsonNull)
                if (res.exitCode != 0) put("note", "Non-zero exit code; check stderr.")
            }
            is CaptureResult.Timeout -> buildJsonObject {
                put("error", "timeout")
                res.jobId?.let { put("job_id", it) }
                put("timed_out", true)
                val managed = shouldManageCapture(backgroundRequested = background, shellCommandMode = true)
                put("cleanup_requested", managed)
                put(
                    "recovery",
                    if (managed) "Cleanup was requested after ${timeoutMs / 1000}s. Partial output may become available through termux_read_output using this job_id."
                    else "The detached launch timed out before returning its PID; ownership was already detached, so this tool did not request termination.",
                )
            }
            is CaptureResult.Denied -> buildJsonObject {
                put("error", "termux_permission_denied")
                put("recovery", "Open Termux, then run: mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties. Force-stop Termux from app info and reopen it. Then retry.")
            }
            is CaptureResult.OtherError -> buildJsonObject {
                put("error", "termux_run_failed")
                res.jobId?.let { put("job_id", it) }
                put("reason", res.message)
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
