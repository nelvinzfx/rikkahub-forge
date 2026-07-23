package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure logic of the interactive Termux session tools: tmux argv
 * construction, the settle / wait_for read decision, session-list parsing, and
 * not-found detection. Android RUN_COMMAND IO still requires on-device verification.
 */
class TermuxSessionToolTest {

    @Test
    fun sessionName_hasPrefix_andSanitizes() {
        assertTrue(TmuxOps.sessionName(null).startsWith("rk_"))
        val named = TmuxOps.sessionName("my pc!")
        assertTrue(named.startsWith("rk_my_pc_"))
        assertTrue(named.none { it == ' ' || it == '!' })
    }

    @Test
    fun sessionName_isUnique() {
        assertTrue(TmuxOps.sessionName(null) != TmuxOps.sessionName(null))
    }

    @Test
    fun argvBuilders_areLiteralAndSafe() {
        assertEquals(
            listOf("new-session", "-d", "-s", "rk_x", "-x", "200", "-y", "50"),
            TmuxOps.startArgv("rk_x", 200, 50).toList()
        )
        assertEquals(
            listOf("send-keys", "-t", "rk_x", "-l", "--", "echo 'hi there'"),
            TmuxOps.sendTextArgv("rk_x", "echo 'hi there'").toList()
        )
        assertEquals(
            listOf("send-keys", "-t", "rk_x", "C-c", "Enter"),
            TmuxOps.sendKeysArgv("rk_x", listOf("C-c", "Enter")).toList()
        )
        assertEquals(
            listOf("capture-pane", "-t", "rk_x", "-p", "-S", "-200"),
            TmuxOps.capturePaneArgv("rk_x", 200).toList()
        )
        assertEquals(listOf("kill-session", "-t", "rk_x"), TmuxOps.killArgv("rk_x").toList())
    }

    @Test
    fun waitFor_matchesSubstringAndRegex() {
        assertTrue(waitForMatches("Enter password:", "password:"))
        assertTrue(waitForMatches("user@host:~$ ", "\\$ "))
        assertTrue(!waitForMatches("nothing here", "password:"))
        // invalid regex falls back to substring
        assertTrue(waitForMatches("a[b", "a[b"))
    }

    @Test
    fun evaluatePoll_returnsMatchedAsSoonAsWaitForHits() {
        val samples = listOf(
            PaneSample(0, "loading"),
            PaneSample(200, "Enter password:"),
        )
        val r = evaluatePoll(samples, settleMs = 600, timeoutMs = 20_000, waitFor = "password:")
        assertEquals(PollResult.Reason.MATCHED, (r as PollResult.Done).reason)
    }

    @Test
    fun evaluatePoll_settlesWhenStableLongEnough() {
        val samples = listOf(
            PaneSample(0, "a"),
            PaneSample(200, "b"),
            PaneSample(400, "b"),
            PaneSample(900, "b"),
        )
        val r = evaluatePoll(samples, settleMs = 600, timeoutMs = 20_000, waitFor = null)
        assertEquals(PollResult.Reason.SETTLED, (r as PollResult.Done).reason)
    }

    @Test
    fun evaluatePoll_continuesWhileStillChanging() {
        val samples = listOf(PaneSample(0, "a"), PaneSample(200, "b"))
        assertEquals(PollResult.Continue, evaluatePoll(samples, 600, 20_000, null))
    }

    @Test
    fun evaluatePoll_timesOut() {
        val samples = listOf(PaneSample(0, "a"), PaneSample(20_000, "b"))
        val r = evaluatePoll(samples, settleMs = 600, timeoutMs = 20_000, waitFor = null)
        assertEquals(PollResult.Reason.TIMEOUT, (r as PollResult.Done).reason)
    }

    @Test
    fun parseSessions_keepsOnlyRkPrefixed() {
        val out = "rk_a\t1700000000\t1700000100\nuserwork\t1700000000\t1700000050\nrk_b\t1700000200\t1700000200\n"
        val sessions = parseSessions(out)
        assertEquals(listOf("rk_a", "rk_b"), sessions.map { it.name })
        assertEquals(1700000100L, sessions[0].lastActivity)
    }

    @Test
    fun parseSessions_emptyOrGarbage() {
        assertTrue(parseSessions("").isEmpty())
        assertTrue(parseSessions("malformed-line-no-tabs").isEmpty())
    }

    @Test
    fun isSessionNotFound_detectsTmuxErrors() {
        assertTrue(isSessionNotFound("can't find session: rk_x"))
        // `capture-pane -t <missing>` uses pane wording even though the requested target is
        // our session id. Treat it identically so read and kill expose one stable envelope.
        assertTrue(isSessionNotFound("can't find pane: rk_x"))
        assertTrue(isSessionNotFound("no server running on /tmp/tmux-0/default"))
        assertTrue(!isSessionNotFound("some other error"))
    }

    @Test
    fun staleSessionsToReap_reapsOnlyOlderThanTtl() {
        val now = 1_700_000_000L // epoch seconds
        val ttlMs = 6L * 60 * 60 * 1000 // 6h
        val fresh = TmuxSessionInfo("rk_fresh", created = now - 10, lastActivity = now - 60)
        val stale = TmuxSessionInfo("rk_stale", created = now - 100000, lastActivity = now - 7 * 60 * 60)
        // lastActivity == 0 (unparsed) must never be reaped, even though 0 < cutoff.
        val unknown = TmuxSessionInfo("rk_unknown", created = now, lastActivity = 0L)
        val reaped = staleSessionsToReap(listOf(fresh, stale, unknown), now, ttlMs)
        assertEquals(listOf("rk_stale"), reaped.map { it.name })
    }

    @Test
    fun takeLastUtf8Bytes_keepsTailOnByteBoundary() {
        // ASCII: byte count == char count.
        assertEquals("cde", takeLastUtf8Bytes("abcde", 3))
        // fits entirely.
        assertEquals("abc", takeLastUtf8Bytes("abc", 10))
        assertEquals("", takeLastUtf8Bytes("abc", 0))
    }

    @Test
    fun takeLastUtf8Bytes_neverSplitsMultibyte() {
        // Each CJK char is 3 UTF-8 bytes. Budget 4 must keep only the last whole char (3 bytes),
        // never half of a 3-byte sequence.
        val s = "你好" // two 3-byte chars, 6 bytes total
        val out = takeLastUtf8Bytes(s, 4)
        assertEquals("好", out)
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= 4)
    }

    @Test
    fun takeFirstUtf8Bytes_neverSplitsMultibyte() {
        val s = "你好世" // three 3-byte chars, 9 bytes
        val out = takeFirstUtf8Bytes(s, 4)
        assertEquals("你", out) // only the first whole char fits in 4 bytes
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= 4)
        assertEquals("abc", takeFirstUtf8Bytes("abcde", 3))
    }

    @Test
    fun takeLastUtf8Bytes_honorsSurrogatePairs() {
        // Each emoji is a surrogate PAIR (2 Java chars) but a single 4-byte UTF-8 sequence.
        // Regression: measuring per-char counted each surrogate half separately, ~2x over
        // budget, and could cut between the halves. Budget 4 must keep exactly one whole emoji.
        val s = "😀😁" // two emoji, 8 bytes, 4 chars
        val out = takeLastUtf8Bytes(s, 4)
        assertEquals("😁", out)
        assertEquals(4, out.toByteArray(Charsets.UTF_8).size)
        // Budget 5 still fits only one emoji (the second is 4 bytes, no room for a partial).
        assertEquals("😁", takeLastUtf8Bytes(s, 5))
        // Budget 7 still under 8 total: one whole emoji survives, never a split surrogate pair.
        assertEquals("😁", takeLastUtf8Bytes(s, 7))
    }

    @Test
    fun takeFirstUtf8Bytes_honorsSurrogatePairs() {
        val s = "😀😁" // two emoji, 8 bytes
        val out = takeFirstUtf8Bytes(s, 4)
        assertEquals("😀", out)
        assertEquals(4, out.toByteArray(Charsets.UTF_8).size)
        assertEquals("😀", takeFirstUtf8Bytes(s, 7))
        // Mixed: an ASCII prefix then an emoji. Budget 5 keeps "a" + one emoji (1 + 4 = 5).
        assertEquals("a😀", takeFirstUtf8Bytes("a😀😁", 5))
    }

    @Test
    fun tmuxClassification_turnsNonzeroExitIntoActionableFailure() {
        val classified = classifyTmuxResult(
            CaptureResult.Success("", "no server running on /tmp/tmux-123/default\n", 1)
        )
        assertTrue(classified is CaptureResult.OtherError)
        assertTrue(isSessionNotFound((classified as CaptureResult.OtherError).message))
    }

    @Test
    fun tmuxClassification_keepsZeroExitSuccess() {
        val completed = CaptureResult.Success("pane", "", 0)
        assertSame(completed, classifyTmuxResult(completed))
    }

    @Test
    fun ordinaryCommand_nonzeroExitRemainsCompletedResult() {
        val completed = completedCommandResult("out", "bad", 17)
        assertTrue(completed is CaptureResult.Success)
        completed as CaptureResult.Success
        assertEquals(17, completed.exitCode)
        assertEquals("bad", completed.stderr)
    }

    @Test
    fun dimensionsLinesAndTimeouts_areExplicitlyClamped() {
        assertEquals(MIN_COLS, clampCols(-1))
        assertEquals(MAX_COLS, clampCols(Int.MAX_VALUE))
        assertEquals(DEFAULT_COLS, clampCols(null))
        assertEquals(MIN_ROWS, clampRows(-1))
        assertEquals(MAX_ROWS, clampRows(Int.MAX_VALUE))
        assertEquals(MIN_READ_LINES, clampReadLines(-1))
        assertEquals(MAX_READ_LINES, clampReadLines(Int.MAX_VALUE))
        assertEquals(DEFAULT_READ_LINES, clampReadLines(null))
        assertEquals(1_000L, resolveTimeoutMs(-5))
        assertEquals(DEFAULT_TIMEOUT_S * 1_000L, resolveTimeoutMs(0))
        assertEquals(MAX_TIMEOUT_S * 1_000L, resolveTimeoutMs(Int.MAX_VALUE))
        assertEquals(1L, captureTimeoutMs(Long.MIN_VALUE))
        assertEquals(MAX_TIMEOUT_S * 1_000L, captureTimeoutMs(Long.MAX_VALUE))
        assertTrue(shouldManageCapture(backgroundRequested = false, shellCommandMode = true))
        assertTrue(shouldManageCapture(backgroundRequested = true, shellCommandMode = false))
        assertFalse(shouldManageCapture(backgroundRequested = true, shellCommandMode = true))
        assertEquals(
            listOf("new-session", "-d", "-s", "rk_x", "-x", "$MIN_COLS", "-y", "$MIN_ROWS"),
            TmuxOps.startArgv("rk_x", -1, -1).toList(),
        )
        assertEquals("-$MIN_READ_LINES", TmuxOps.capturePaneArgv("rk_x", -1).last())
        assertEquals("-$MAX_READ_LINES", TmuxOps.capturePaneArgv("rk_x", Int.MAX_VALUE).last())
    }

    @Test
    fun initialCommandHardlineCheck_canRunBeforeAnyAllocation() {
        assertTrue(initialCommandBlockReason("reboot")?.isNotBlank() == true)
        assertNull(initialCommandBlockReason("ssh myhost"))
        // GenerationHandler calls this tool-aware seam before approval and again before execution;
        // the local initialCommandBlockReason check remains defense in depth for direct callers.
        assertTrue(
            me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard.checkTool(
                "termux_session_start",
                """{"command":"reboot"}""",
            )?.isNotBlank() == true,
        )
        assertTrue(
            me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard.checkTool(
                "termux_session_send",
                """{"session_id":"rk_x","input":"reboot"}""",
            )?.isNotBlank() == true,
        )
    }

    @Test
    fun managedCaptureWrapper_passesUserValuesAsArgvAndRegistersCleanup() {
        val launch = buildCaptureLaunch(
            executable = "/tmp/exe;touch /tmp/injected",
            arguments = arrayOf("a b", "$(touch nope)", "'quoted'"),
            managed = true,
            jobId = "opaque-job-123",
        )
        assertEquals("$TERMUX_BIN/bash", launch.executable)
        assertEquals("opaque-job-123", launch.jobId)
        assertEquals("opaque-job-123", launch.arguments[3])
        assertEquals(MANAGED_CAPTURE_LEADER_SCRIPT, launch.arguments[4])
        assertEquals("/tmp/exe;touch /tmp/injected", launch.arguments[5])
        assertEquals(listOf("a b", "$(touch nope)", "'quoted'"), launch.arguments.drop(6))
        assertFalse(MANAGED_CAPTURE_SCRIPT.contains("opaque-job-123"))
        assertTrue(MANAGED_CAPTURE_SCRIPT.contains("managed capture requires setsid (install util-linux)"))
        assertTrue(MANAGED_CAPTURE_SCRIPT.contains("setsid bash -c \"${'$'}leader\" rikka-leader"))
        assertFalse(MANAGED_CAPTURE_SCRIPT.contains("rikka-leader tree"))
        assertTrue(MANAGED_CAPTURE_LEADER_SCRIPT.contains("pid=${'$'}${'$'}"))
        assertTrue(MANAGED_CAPTURE_LEADER_SCRIPT.contains("printf 'group %s %s\\n' \"${'$'}pid\" \"${'$'}start\""))
        assertTrue(MANAGED_CAPTURE_LEADER_SCRIPT.contains("mv -f -- \"${'$'}tmp\" \"${'$'}state_file\""))
        assertTrue(MANAGED_CAPTURE_LEADER_SCRIPT.contains("exec \"${'$'}@\""))
        assertTrue(MANAGED_CAPTURE_LEADER_SCRIPT.indexOf("mv -f --") < MANAGED_CAPTURE_LEADER_SCRIPT.indexOf("exec \"${'$'}@\""))
        assertFalse(MANAGED_CAPTURE_SCRIPT.contains("/proc/${'$'}leader_pid/stat"))
        assertTrue(CAPTURE_CLEANUP_SCRIPT.contains("read -r mode pid root_start extra"))
        assertTrue(CAPTURE_CLEANUP_SCRIPT.contains("[ \"${'$'}mode\" = group ]"))
        assertTrue(CAPTURE_CLEANUP_SCRIPT.contains("[ \"${'$'}live_start\" = \"${'$'}root_start\" ]"))
        assertTrue(CAPTURE_CLEANUP_SCRIPT.contains("[ \"${'$'}live_pgrp\" = \"${'$'}pid\" ]"))
        assertTrue(CAPTURE_CLEANUP_SCRIPT.contains("member_found=false"))
        assertTrue(CAPTURE_CLEANUP_SCRIPT.contains("[ \"${'$'}member_start\" -ge \"${'$'}root_start\" ]"))
        assertTrue(CAPTURE_CLEANUP_SCRIPT.contains("for stat in /proc/[0-9]*/stat"))
        assertFalse(CAPTURE_CLEANUP_SCRIPT.contains("mode=tree"))
        assertTrue(CAPTURE_CLEANUP_SCRIPT.contains("reject_state"))
        assertTrue(CAPTURE_CLEANUP_SCRIPT.contains("kill -TERM -- \"-${'$'}pid\""))
        assertTrue(CAPTURE_CLEANUP_SCRIPT.contains("[ \"${'$'}pid\" -gt 1 ]"))
        assertEquals("opaque-job-123", captureCleanupArguments("opaque-job-123").last())
        assertEquals("opaque-job-123", captureReleaseArguments("opaque-job-123").last())
    }

    @Test
    fun detachedCapture_preservesOriginalArgvAndHasNoManagedJob() {
        val launch = buildCaptureLaunch(
            executable = "/usr/bin/bash",
            arguments = arrayOf("-c", "nohup sh -c 'server' >/dev/null 2>&1 < /dev/null & echo ${'$'}!"),
            managed = false,
            jobId = "must-not-be-used",
        )
        assertEquals("/usr/bin/bash", launch.executable)
        assertEquals(listOf("-c", "nohup sh -c 'server' >/dev/null 2>&1 < /dev/null & echo ${'$'}!"), launch.arguments.toList())
        assertNull(launch.jobId)
    }


    @Test
    fun tmuxInstallTimeout_isReportedAsStopped() {
        assertEquals("tmux_install_timed_out", classifyTmuxInstallFailure(CaptureResult.Timeout))
        assertEquals("termux_permission_denied", classifyTmuxInstallFailure(CaptureResult.Denied))
        assertNull(classifyTmuxInstallFailure(CaptureResult.Success("", "", 0)))
        val recovery = tmuxInstallRecovery("tmux_install_timed_out")
        assertTrue(recovery.contains("timed out and was stopped"))
        assertFalse(recovery.contains("still installing"))
    }


    @Test
    fun initialSetupFailure_classifiesOnlyExplicitTmuxFailures() {
        assertNull(initialSetupFailure("Initial input", CaptureResult.Success("", "", 0)))
        assertEquals(
            "Initial Enter failed: no server running.",
            initialSetupFailure("Initial Enter", CaptureResult.OtherError("no server running")),
        )
        assertEquals(
            "Initial input failed: tmux operation failed.",
            initialSetupFailure("Initial input", CaptureResult.Timeout),
        )
    }

}
