package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
import me.rerere.rikkahub.data.preferences.TermuxRuntime
import java.util.UUID

internal const val TERMUX_BIN = "/data/data/com.termux/files/usr/bin"
internal const val TERMUX_HOME = "/data/data/com.termux/files/home"

internal const val DEFAULT_COLS = 200
internal const val DEFAULT_ROWS = 50
internal const val DEFAULT_READ_LINES = 200
internal const val DEFAULT_TIMEOUT_S = 20
internal const val MIN_COLS = 20
internal const val MAX_COLS = 500
internal const val MIN_ROWS = 5
internal const val MAX_ROWS = 200
internal const val MIN_READ_LINES = 1
internal const val MAX_READ_LINES = 5_000
internal const val MAX_TIMEOUT_S = 600
private const val SETTLE_MS = 600L
private const val POLL_INTERVAL_MS = 200L
private const val MAX_SESSIONS = 8
private const val TMUX_OP_TIMEOUT_MS = 8_000L
private const val INSTALL_TIMEOUT_MS = 180_000L
// Idle sessions are never explicitly killed by the model, so they would otherwise pin the
// MAX_SESSIONS budget forever. Reap any rk_ session whose tmux session_activity is older than
// this before enforcing the slot cap. 6h is long enough to leave a genuinely in-use shell
// (ssh, a REPL, a watched build) alone while clearing forgotten ones.
private const val SESSION_TTL_MS = 6L * 60 * 60 * 1000

/** Builds the argv passed to the tmux executable for each session operation. Pure. */
internal object TmuxOps {
    fun sessionName(userName: String?): String {
        val suffix = userName?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[^A-Za-z0-9_]"), "_")
            ?.take(24)
        val id = UUID.randomUUID().toString().take(8)
        return if (suffix.isNullOrBlank()) "rk_$id" else "rk_${suffix}_$id"
    }

    fun startArgv(session: String, cols: Int, rows: Int): Array<String> =
        arrayOf(
            "new-session", "-d", "-s", session,
            "-x", clampCols(cols).toString(), "-y", clampRows(rows).toString(),
        )

    // -l sends the text literally (no tmux key-name interpretation); -- ends option parsing.
    fun sendTextArgv(session: String, text: String): Array<String> =
        arrayOf("send-keys", "-t", session, "-l", "--", text)

    // Each element is a tmux key name (e.g. "C-c", "Enter", "Up", "Tab").
    fun sendKeysArgv(session: String, keys: List<String>): Array<String> =
        (listOf("send-keys", "-t", session) + keys).toTypedArray()

    fun enterArgv(session: String): Array<String> =
        arrayOf("send-keys", "-t", session, "Enter")

    fun capturePaneArgv(session: String, lines: Int): Array<String> =
        arrayOf("capture-pane", "-t", session, "-p", "-S", "-${clampReadLines(lines)}")

    fun killArgv(session: String): Array<String> =
        arrayOf("kill-session", "-t", session)

    fun listArgv(): Array<String> =
        arrayOf("list-sessions", "-F", "#{session_name}\t#{session_created}\t#{session_activity}")
}

internal data class PaneSample(val elapsedMs: Long, val content: String)

internal sealed interface PollResult {
    data object Continue : PollResult
    data class Done(val reason: Reason, val content: String) : PollResult
    enum class Reason { SETTLED, MATCHED, TIMEOUT }
}

/** Regex match with substring fallback when the pattern is not a valid regex. */
internal fun waitForMatches(pane: String, pattern: String): Boolean {
    if (pattern.isEmpty()) return false
    val rx = runCatching { Regex(pattern) }.getOrNull()
    return if (rx != null) rx.containsMatchIn(pane) else pane.contains(pattern)
}

/**
 * Decide whether the polling loop should stop, given every pane snapshot taken so far
 * (chronological, each with its elapsed time since the send). Order of precedence:
 * wait_for match, then settle (screen unchanged for >= settleMs), then timeout.
 */
internal fun evaluatePoll(
    samples: List<PaneSample>,
    settleMs: Long,
    timeoutMs: Long,
    waitFor: String?,
): PollResult {
    val cur = samples.lastOrNull() ?: return PollResult.Continue
    if (!waitFor.isNullOrEmpty() && waitForMatches(cur.content, waitFor)) {
        return PollResult.Done(PollResult.Reason.MATCHED, cur.content)
    }
    var stableSince = cur.elapsedMs
    for (i in samples.indices.reversed()) {
        if (samples[i].content == cur.content) stableSince = samples[i].elapsedMs else break
    }
    if (samples.size >= 2 && cur.elapsedMs - stableSince >= settleMs) {
        return PollResult.Done(PollResult.Reason.SETTLED, cur.content)
    }
    if (cur.elapsedMs >= timeoutMs) {
        return PollResult.Done(PollResult.Reason.TIMEOUT, cur.content)
    }
    return PollResult.Continue
}

internal data class TmuxSessionInfo(val name: String, val created: Long, val lastActivity: Long)

internal fun parseSessions(stdout: String, prefix: String = "rk_"): List<TmuxSessionInfo> =
    stdout.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3 || !parts[0].startsWith(prefix)) return@mapNotNull null
            TmuxSessionInfo(
                name = parts[0],
                created = parts[1].toLongOrNull() ?: 0L,
                lastActivity = parts[2].toLongOrNull() ?: 0L,
            )
        }.toList()

/** Sessions whose last tmux activity is older than [ttlMs] relative to [nowEpochSecs]. Pure. */
internal fun staleSessionsToReap(
    sessions: List<TmuxSessionInfo>,
    nowEpochSecs: Long,
    ttlMs: Long,
): List<TmuxSessionInfo> {
    val cutoffSecs = nowEpochSecs - ttlMs / 1000
    // session_activity is epoch seconds; treat a 0/unparsed activity as not-stale so a session
    // with a malformed timestamp is never reaped out from under an active user.
    return sessions.filter { it.lastActivity in 1 until cutoffSecs }
}

internal fun isSessionNotFound(stderr: String): Boolean {
    val s = stderr.lowercase()
    return s.contains("can't find session") ||
        s.contains("can't find pane") ||
        s.contains("no server running") ||
        s.contains("session not found") ||
        s.contains("no current session")
}

internal fun clampCols(value: Int?): Int = (value ?: DEFAULT_COLS).coerceIn(MIN_COLS, MAX_COLS)
internal fun clampRows(value: Int?): Int = (value ?: DEFAULT_ROWS).coerceIn(MIN_ROWS, MAX_ROWS)
internal fun clampReadLines(value: Int?): Int =
    (value ?: DEFAULT_READ_LINES).coerceIn(MIN_READ_LINES, MAX_READ_LINES)
internal fun resolveTimeoutMs(raw: Int?): Long {
    val seconds = if (raw == null || raw == 0) DEFAULT_TIMEOUT_S else raw.coerceIn(1, MAX_TIMEOUT_S)
    return seconds.toLong() * 1000
}

internal fun classifyTmuxResult(result: CaptureResult): CaptureResult = when (result) {
    is CaptureResult.Success -> if (result.exitCode == 0) {
        result
    } else {
        val detail = result.stderr.trim().ifEmpty { "tmux exited with code ${result.exitCode}" }
        CaptureResult.OtherError(detail)
    }
    else -> result
}

internal fun initialCommandBlockReason(command: String?): String? =
    command?.takeIf { it.isNotBlank() }?.let(HardlineCommandGuard::checkCommand)

private suspend fun tmux(context: Context, argv: Array<String>, timeoutMs: Long = TMUX_OP_TIMEOUT_MS): CaptureResult =
    classifyTmuxResult(runCommandCapture(context, "$TERMUX_BIN/tmux", argv, TERMUX_HOME, timeoutMs))

internal fun classifyTmuxInstallFailure(result: CaptureResult): String? = when (result) {
    is CaptureResult.Denied -> "termux_permission_denied"
    is CaptureResult.Timeout -> "tmux_install_timed_out"
    else -> null
}

internal fun tmuxInstallRecovery(error: String): String = when (error) {
    "tmux_install_timed_out" ->
        "The bounded tmux install attempt timed out and was stopped. Retry, or open Termux and run 'pkg install tmux' manually."
    else -> "tmux could not be installed. Open Termux, run 'pkg install tmux', and retry."
}

/** Ensure tmux is installed; auto-install on first use. Returns null on success, an error string otherwise. */
private suspend fun ensureTmux(context: Context): String? {
    val check = runCommandCapture(context, "$TERMUX_BIN/sh", arrayOf("-c", "command -v tmux"), TERMUX_HOME, TMUX_OP_TIMEOUT_MS)
    if (check is CaptureResult.Success && check.stdout.isNotBlank()) return null
    // The install is bounded. runCommandCapture stops its managed process tree on timeout, so
    // report that stopped attempt truthfully instead of implying installation continues.
    val install = runCommandCapture(context, "$TERMUX_BIN/bash", arrayOf("-c", "pkg install -y tmux"), TERMUX_HOME, INSTALL_TIMEOUT_MS)
    classifyTmuxInstallFailure(install)?.let { return it }
    val recheck = runCommandCapture(context, "$TERMUX_BIN/sh", arrayOf("-c", "command -v tmux"), TERMUX_HOME, TMUX_OP_TIMEOUT_MS)
    return if (recheck is CaptureResult.Success && recheck.stdout.isNotBlank()) null else "tmux_install_failed"
}

private fun resolveTimeoutMs(input: JsonElement): Long =
    resolveTimeoutMs(input.jsonObject["timeout_seconds"]?.jsonPrimitive?.intOrNull)

/**
 * UTF-8 byte width of the Unicode code point [cp]. Used to budget truncation by bytes while
 * iterating code points (NOT chars): an astral char (emoji, some CJK) is a surrogate PAIR of
 * two Java chars but a single 4-byte UTF-8 sequence. Measuring per-char would count each
 * surrogate half separately — overshooting the budget ~2x and letting a cut fall between the
 * two halves, corrupting the very emoji this boundary-snapping is meant to protect.
 */
private fun utf8Width(cp: Int): Int = when {
    cp < 0x80 -> 1
    cp < 0x800 -> 2
    cp < 0x10000 -> 3
    else -> 4
}

/**
 * Keep at most [maxBytes] of UTF-8 from the end of [s], snapping the cut to a code-point
 * boundary so a multi-byte sequence (including a surrogate-pair emoji) is never split, which
 * would otherwise corrupt CJK / emoji output. Returns the whole string when it already fits.
 * Pure.
 */
internal fun takeLastUtf8Bytes(s: String, maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
    // Walk back from the end one code point at a time, counting its true UTF-8 width, until
    // adding one more would exceed the budget; the surviving slice is byte-bounded and aligned.
    var bytes = 0
    var i = s.length
    while (i > 0) {
        val cp = s.codePointBefore(i)
        val w = utf8Width(cp)
        if (bytes + w > maxBytes) break
        bytes += w
        i -= Character.charCount(cp)
    }
    return s.substring(i)
}

/**
 * Keep at most [maxBytes] of UTF-8 from the start of [s], snapping the cut to a code-point
 * boundary so a multi-byte sequence (including a surrogate-pair emoji) is never split. Returns
 * the whole string when it already fits. Pure. Counterpart to [takeLastUtf8Bytes] for
 * head-keeping truncation.
 */
internal fun takeFirstUtf8Bytes(s: String, maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
    var bytes = 0
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val w = utf8Width(cp)
        if (bytes + w > maxBytes) break
        bytes += w
        i += Character.charCount(cp)
    }
    return s.substring(0, i)
}

private fun truncateOut(s: String): String {
    // capture-pane emits the full terminal height, so the screen arrives padded with a wall
    // of blank lines below the cursor. Drop trailing blank lines so each read does not burn
    // tokens on empty padding.
    val trimmed = s.trimEnd('\n', ' ', '\t')
    val max = TermuxRuntime.maxStdoutBytes
    // Bound on UTF-8 bytes, not chars: maxStdoutBytes is a byte budget, and a char-count cut
    // would over- or under-shoot for multibyte text and could split a code point.
    return if (trimmed.toByteArray(Charsets.UTF_8).size > max) {
        takeLastUtf8Bytes(trimmed, max) + "\n…[older scrollback truncated]"
    } else {
        trimmed
    }
}

private fun reasonTag(r: PollResult.Reason): String = when (r) {
    PollResult.Reason.MATCHED -> "MATCHED"
    PollResult.Reason.SETTLED -> "SETTLED"
    PollResult.Reason.TIMEOUT -> "TIMEOUT"
}

/**
 * Poll capture-pane until settled / matched / timed out. Encodes the outcome in a
 * [CaptureResult.Success] where stdout is the screen and stderr carries the reason tag
 * (MATCHED / SETTLED / TIMEOUT). A capture failure (e.g. session gone) is returned as-is.
 */
private suspend fun readUntilDone(
    context: Context,
    session: String,
    lines: Int,
    waitFor: String?,
    timeoutMs: Long,
): CaptureResult {
    val start = android.os.SystemClock.elapsedRealtime()
    val samples = ArrayList<PaneSample>()
    while (true) {
        val cap = tmux(context, TmuxOps.capturePaneArgv(session, lines))
        if (cap is CaptureResult.Success) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - start
            samples.add(PaneSample(elapsed, cap.stdout))
            when (val d = evaluatePoll(samples, SETTLE_MS, timeoutMs, waitFor)) {
                is PollResult.Done -> return CaptureResult.Success(d.content, reasonTag(d.reason), 0)
                PollResult.Continue -> {}
            }
        } else {
            return cap
        }
        if (android.os.SystemClock.elapsedRealtime() - start >= timeoutMs) {
            return CaptureResult.Success(samples.lastOrNull()?.content.orEmpty(), "TIMEOUT", 0)
        }
        delay(POLL_INTERVAL_MS)
    }
}

private fun sessionErrorEnvelope(error: String, recovery: String) = listOf(
    UIMessagePart.Text(buildJsonObject {
        put("error", error); put("recovery", recovery)
    }.toString())
)

private fun preflight(context: Context): List<UIMessagePart>? =
    when (TermuxIntegration.state(context)) {
        TermuxIntegration.State.NOT_INSTALLED -> sessionErrorEnvelope(
            "termux_not_installed",
            "Install Termux from https://github.com/termux/termux-app/releases ."
        )
        TermuxIntegration.State.NO_PERMISSION -> sessionErrorEnvelope(
            "termux_permission_not_granted",
            "Toggle Termux on in Assistant -> Local tools, or run: adb shell pm grant ${context.packageName} com.termux.permission.RUN_COMMAND"
        )
        TermuxIntegration.State.READY -> null
    }


private fun operationFailure(result: CaptureResult, fallback: String): String =
    (result as? CaptureResult.OtherError)?.message?.takeIf { it.isNotBlank() } ?: fallback

private suspend fun cleanupPartialSession(context: Context, session: String): String {
    val killed = tmux(context, TmuxOps.killArgv(session))
    return when {
        killed is CaptureResult.Success -> "Partial session was cleaned up."
        killed is CaptureResult.OtherError && isSessionNotFound(killed.message) ->
            "Partial session was already gone."
        else -> "Partial session cleanup failed: ${operationFailure(killed, "tmux kill failed")}."
    }
}

internal fun initialSetupFailure(step: String, result: CaptureResult): String? =
    if (result is CaptureResult.Success) null
    else "$step failed: ${operationFailure(result, "tmux operation failed")}."

/**
 * The only vulnerable partial-allocation window: new-session succeeded but initial text + Enter
 * have not both completed. Cancellation cleanup is NonCancellable and cancellation is rethrown.
 * Once this returns null, later screen-read cancellation deliberately leaves the session alive.
 */
private suspend fun setupInitialCommand(
    context: Context,
    session: String,
    initial: String,
): String? {
    return try {
        val sent = tmux(context, TmuxOps.sendTextArgv(session, initial))
        initialSetupFailure("Initial input", sent)?.let { failure ->
            val cleanup = withContext(NonCancellable) { cleanupPartialSession(context, session) }
            return "$failure $cleanup"
        }
        val entered = tmux(context, TmuxOps.enterArgv(session))
        initialSetupFailure("Initial Enter", entered)?.let { failure ->
            val cleanup = withContext(NonCancellable) { cleanupPartialSession(context, session) }
            return "$failure $cleanup"
        }
        null
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) { cleanupPartialSession(context, session) }
        throw cancelled
    } catch (unexpected: Throwable) {
        val cleanup = withContext(NonCancellable) { cleanupPartialSession(context, session) }
        "Initial setup failed unexpectedly (${unexpected::class.java.simpleName}). $cleanup"
    }
}

private suspend fun sessionNotFoundEnvelope(context: Context, session: String): List<UIMessagePart> {
    val live = (tmux(context, TmuxOps.listArgv()) as? CaptureResult.Success)?.let { parseSessions(it.stdout) } ?: emptyList()
    return sessionErrorEnvelope(
        "session_not_found",
        "Session '$session' is gone (killed or device rebooted). Live sessions: ${live.joinToString { it.name }.ifEmpty { "none" }}. Start a new one with termux_session_start."
    )
}

fun termuxSessionStartTool(context: Context): Tool = Tool(
    name = "termux_session_start",
    description = "Open a persistent, interactive Termux terminal session (tmux-backed, real pty). Use for ssh into a saved host, anything that prompts for a password/sudo, REPLs, or stateful shells. Returns a session_id; drive it with termux_session_send / termux_session_read. Auto-installs tmux on first use.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("name", buildJsonObject { put("type", "string"); put("description", "Optional friendly label for the session.") })
            put("command", buildJsonObject { put("type", "string"); put("description", "Optional initial command line to run, e.g. 'ssh myhost'.") })
            put("cols", buildJsonObject { put("type", "integer"); put("description", "Terminal width (default $DEFAULT_COLS, clamped to $MIN_COLS..$MAX_COLS).") })
            put("rows", buildJsonObject { put("type", "integer"); put("description", "Terminal height (default $DEFAULT_ROWS, clamped to $MIN_ROWS..$MAX_ROWS).") })
        })
    },
    execute = { input ->
        preflight(context)?.let { return@Tool it }
        // Validate before package installation, session listing/reaping, name allocation, or
        // tmux new-session. The central GenerationHandler checks hardline before approval and
        // again before execution; this local check remains defense in depth for direct callers.
        val initial = input.jsonObject["command"]?.jsonPrimitive?.contentOrNull
        initialCommandBlockReason(initial)?.let {
            return@Tool sessionErrorEnvelope("blocked_by_safety_floor", it)
        }
        ensureTmux(context)?.let { err ->
            return@Tool sessionErrorEnvelope(err, tmuxInstallRecovery(err))
        }
        val listed = tmux(context, TmuxOps.listArgv())
        var live = when (listed) {
            is CaptureResult.Success -> parseSessions(listed.stdout)
            is CaptureResult.OtherError -> if (isSessionNotFound(listed.message)) emptyList() else {
                return@Tool sessionErrorEnvelope("session_list_failed", listed.message)
            }
            else -> return@Tool sessionErrorEnvelope("session_list_failed", "Could not inspect live tmux sessions.")
        }
        // Reap idle sessions before enforcing the cap: nothing else kills forgotten sessions,
        // so without this the MAX_SESSIONS budget fills permanently. session_activity is epoch
        // seconds, so compare against wall-clock seconds (not SystemClock.elapsedRealtime).
        val nowSecs = System.currentTimeMillis() / 1000
        val stale = staleSessionsToReap(live, nowSecs, SESSION_TTL_MS)
        if (stale.isNotEmpty()) {
            // Only drop a stale session from the live count if its kill actually succeeded.
            // A failed kill (tmux error, session wedged) leaves the session occupying a slot,
            // so optimistically subtracting it would let live.size dip below the true count and
            // transiently blow past MAX_SESSIONS. isSessionNotFound also counts as reaped: the
            // session is already gone, which is the outcome we wanted.
            val reaped = stale.filter { s ->
                val killed = tmux(context, TmuxOps.killArgv(s.name))
                killed is CaptureResult.Success ||
                    (killed is CaptureResult.OtherError && isSessionNotFound(killed.message))
            }
            live = live - reaped.toSet()
        }
        if (live.size >= MAX_SESSIONS) {
            return@Tool sessionErrorEnvelope("too_many_sessions", "Max $MAX_SESSIONS sessions. Kill one with termux_session_kill first. Live: ${live.joinToString { it.name }}")
        }
        val name = TmuxOps.sessionName(input.jsonObject["name"]?.jsonPrimitive?.contentOrNull)
        val cols = clampCols(input.jsonObject["cols"]?.jsonPrimitive?.intOrNull)
        val rows = clampRows(input.jsonObject["rows"]?.jsonPrimitive?.intOrNull)
        val started = tmux(context, TmuxOps.startArgv(name, cols, rows))
        if (started !is CaptureResult.Success) {
            val detail = (started as? CaptureResult.OtherError)?.message ?: "tmux new-session failed."
            return@Tool sessionErrorEnvelope("session_start_failed", detail)
        }
        if (!initial.isNullOrBlank()) {
            setupInitialCommand(context, name, initial)?.let { failure ->
                return@Tool sessionErrorEnvelope("session_start_failed", failure)
            }
        }
        // The session is already created at this point, so a failed screen read (Timeout/
        // Denied/OtherError from readUntilDone) must not crash or report start failure —
        // the model would retry the start and hit too_many_sessions.
        val read = readUntilDone(context, name, DEFAULT_READ_LINES, null, DEFAULT_TIMEOUT_S * 1000L) as? CaptureResult.Success
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true); put("session_id", name)
            put("screen", read?.let { truncateOut(it.stdout) } ?: "")
            if (read == null) put("note", "Session created, but the initial screen read failed. Use termux_session_read to see the screen.")
        }.toString()))
    }
)

fun termuxSessionSendTool(context: Context): Tool = Tool(
    name = "termux_session_send",
    description = "Type input into a session and read what comes back. Set enter=false to type without a newline (e.g. answering a prompt). Use keys for control keys (tmux names: 'C-c', 'Enter', 'Up', 'Tab'). Pass wait_for (substring/regex) to return as soon as expected text appears (e.g. 'password:'). Returns the screen, matched_wait_for, timed_out.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("session_id", buildJsonObject { put("type", "string"); put("description", "Session id from termux_session_start.") })
            put("input", buildJsonObject { put("type", "string"); put("description", "Text to type. Optional if keys is given.") })
            put("enter", buildJsonObject { put("type", "boolean"); put("description", "Press Enter after input. Default true.") })
            put("keys", buildJsonObject { put("type", "array"); put("description", "tmux key names to send (e.g. ['C-c']).") ; put("items", buildJsonObject { put("type", "string") }) })
            put("wait_for", buildJsonObject { put("type", "string"); put("description", "Return as soon as this substring/regex appears on screen.") })
            put("timeout_seconds", buildJsonObject { put("type", "integer"); put("description", "Default $DEFAULT_TIMEOUT_S, max $MAX_TIMEOUT_S.") })
        })
    },
    execute = { input ->
        preflight(context)?.let { return@Tool it }
        val session = input.jsonObject["session_id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool sessionErrorEnvelope("missing_session_id", "Pass session_id from termux_session_start.")
        val text = input.jsonObject["input"]?.jsonPrimitive?.contentOrNull
        val enter = input.jsonObject["enter"]?.jsonPrimitive?.booleanOrNull ?: true
        val keys = input.jsonObject["keys"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val waitFor = input.jsonObject["wait_for"]?.jsonPrimitive?.contentOrNull
        val timeoutMs = resolveTimeoutMs(input)
        if (!text.isNullOrEmpty()) {
            HardlineCommandGuard.checkCommand(text)?.let {
                return@Tool sessionErrorEnvelope("blocked_by_safety_floor", it)
            }
            val sent = tmux(context, TmuxOps.sendTextArgv(session, text))
            if (sent !is CaptureResult.Success) {
                if (sent is CaptureResult.OtherError && isSessionNotFound(sent.message)) {
                    return@Tool sessionNotFoundEnvelope(context, session)
                }
                return@Tool sessionErrorEnvelope("send_failed", operationFailure(sent, "Could not send input to session."))
            }
        }
        // Check the keys/enter sends for a dead session too. Previously these failures were
        // swallowed and only surfaced indirectly by the later read, so a not-found returned a
        // generic read_failed instead of the actionable session_not_found envelope.
        if (keys.isNotEmpty()) {
            val sentKeys = tmux(context, TmuxOps.sendKeysArgv(session, keys))
            if (sentKeys !is CaptureResult.Success) {
                if (sentKeys is CaptureResult.OtherError && isSessionNotFound(sentKeys.message)) {
                    return@Tool sessionNotFoundEnvelope(context, session)
                }
                return@Tool sessionErrorEnvelope("send_failed", operationFailure(sentKeys, "Could not send keys to session."))
            }
        }
        if (enter) {
            val sentEnter = tmux(context, TmuxOps.enterArgv(session))
            if (sentEnter !is CaptureResult.Success) {
                if (sentEnter is CaptureResult.OtherError && isSessionNotFound(sentEnter.message)) {
                    return@Tool sessionNotFoundEnvelope(context, session)
                }
                return@Tool sessionErrorEnvelope("send_failed", operationFailure(sentEnter, "Could not send Enter to session."))
            }
        }
        val read = readUntilDone(context, session, DEFAULT_READ_LINES, waitFor, timeoutMs)
        if (read is CaptureResult.OtherError && isSessionNotFound(read.message)) {
            return@Tool sessionNotFoundEnvelope(context, session)
        }
        val r = read as? CaptureResult.Success
            ?: return@Tool sessionErrorEnvelope(
                "read_failed",
                operationFailure(read, "Input was sent, but the screen read failed. Use termux_session_read to see the result."),
            )
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("screen", truncateOut(r.stdout))
            put("matched_wait_for", r.stderr == "MATCHED")
            put("timed_out", r.stderr == "TIMEOUT")
        }.toString()))
    }
)

fun termuxSessionReadTool(context: Context): Tool = Tool(
    name = "termux_session_read",
    description = "Re-read a session's screen without sending input (e.g. check on a long-running command). Optional wait_for + timeout_seconds to wait for expected text; otherwise returns the current screen immediately. lines sets scrollback depth (default $DEFAULT_READ_LINES).",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("session_id", buildJsonObject { put("type", "string"); put("description", "Session id.") })
            put("wait_for", buildJsonObject { put("type", "string"); put("description", "Optional substring/regex to wait for.") })
            put("timeout_seconds", buildJsonObject { put("type", "integer"); put("description", "Used only with wait_for. Default $DEFAULT_TIMEOUT_S.") })
            put("lines", buildJsonObject { put("type", "integer"); put("description", "Scrollback lines (default $DEFAULT_READ_LINES, clamped to $MIN_READ_LINES..$MAX_READ_LINES).") })
        })
    },
    execute = { input ->
        preflight(context)?.let { return@Tool it }
        val session = input.jsonObject["session_id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool sessionErrorEnvelope("missing_session_id", "Pass session_id from termux_session_start.")
        val waitFor = input.jsonObject["wait_for"]?.jsonPrimitive?.contentOrNull
        val lines = clampReadLines(input.jsonObject["lines"]?.jsonPrimitive?.intOrNull)
        val read = if (waitFor.isNullOrEmpty()) {
            tmux(context, TmuxOps.capturePaneArgv(session, lines))
        } else {
            readUntilDone(context, session, lines, waitFor, resolveTimeoutMs(input))
        }
        if (read is CaptureResult.OtherError && isSessionNotFound(read.message)) {
            return@Tool sessionNotFoundEnvelope(context, session)
        }
        val r = read as? CaptureResult.Success
            ?: return@Tool sessionErrorEnvelope("read_failed", operationFailure(read, "Could not read session."))
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true); put("screen", truncateOut(r.stdout))
        }.toString()))
    }
)

fun termuxSessionKillTool(context: Context): Tool = Tool(
    name = "termux_session_kill",
    description = "End a Termux session opened by termux_session_start.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("session_id", buildJsonObject { put("type", "string"); put("description", "Session id to kill.") })
        })
    },
    execute = { input ->
        preflight(context)?.let { return@Tool it }
        val session = input.jsonObject["session_id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool sessionErrorEnvelope("missing_session_id", "Pass session_id.")
        when (val killed = tmux(context, TmuxOps.killArgv(session))) {
            is CaptureResult.Success -> listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true); put("killed", session)
            }.toString()))
            is CaptureResult.OtherError -> if (isSessionNotFound(killed.message)) {
                sessionNotFoundEnvelope(context, session)
            } else {
                sessionErrorEnvelope("session_kill_failed", killed.message)
            }
            else -> sessionErrorEnvelope("session_kill_failed", "Could not kill session.")
        }
    }
)

fun termuxSessionListTool(context: Context): Tool = Tool(
    name = "termux_session_list",
    description = "List live Termux sessions opened by the agent (id, name, last activity).",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = { _ ->
        preflight(context)?.let { return@Tool it }
        val listed = tmux(context, TmuxOps.listArgv())
        val list = when (listed) {
            is CaptureResult.Success -> parseSessions(listed.stdout)
            is CaptureResult.OtherError -> if (isSessionNotFound(listed.message)) emptyList() else {
                return@Tool sessionErrorEnvelope("session_list_failed", listed.message)
            }
            else -> return@Tool sessionErrorEnvelope("session_list_failed", "Could not list sessions.")
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("sessions", buildJsonArray {
                list.forEach { s ->
                    add(buildJsonObject {
                        put("session_id", s.name); put("created", s.created); put("last_activity", s.lastActivity)
                    })
                }
            })
        }.toString()))
    }
)
