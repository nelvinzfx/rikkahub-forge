package me.rerere.rikkahub.ui.pages.setting.termux

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.preferences.TermuxDefaults
import me.rerere.rikkahub.data.preferences.TermuxPreferences
import me.rerere.rikkahub.data.preferences.TermuxRuntimeConfig

class SettingTermuxViewModel(
    private val prefs: TermuxPreferences,
) : ViewModel() {

    /**
     * Combined settings state. Nested [combine] calls stay within the 5-argument typed
     * overloads to avoid the intersection-type warning from the vararg overload.
     */
    val config: StateFlow<TermuxRuntimeConfig> = combine(
        combine(
            prefs.commandTimeoutFlow(),
            prefs.toolCallTimeoutFlow(),
            prefs.verifyTimeoutFlow(),
            prefs.defaultWorkingDirFlow(),
            prefs.maxStdoutFlow(),
        ) { commandTimeout, toolCallTimeout, verifyTimeout, workingDir, maxStdout ->
            Partial(commandTimeout, toolCallTimeout, verifyTimeout, workingDir, maxStdout)
        },
        combine(
            prefs.maxStderrFlow(),
            prefs.textReadMaxLinesFlow(),
            prefs.textReadMaxBytesFlow(),
            prefs.maxRetainedOutputJobsFlow(),
            prefs.outputTtlFlow(),
        ) { maxStderr, textLines, textBytes, maxJobs, outputTtl ->
            LimitsPartial(maxStderr, textLines, textBytes, maxJobs, outputTtl)
        },
        prefs.aptWrapEnabledFlow(),
    ) { partial, limits, aptWrap ->
        TermuxRuntimeConfig(
            commandTimeoutMs  = partial.commandTimeoutMs,
            toolCallTimeoutMs = partial.toolCallTimeoutMs,
            verifyTimeoutMs   = partial.verifyTimeoutMs,
            defaultWorkingDir = partial.defaultWorkingDir,
            maxStdoutBytes    = partial.maxStdoutBytes,
            maxStderrBytes    = limits.maxStderrBytes,
            textReadMaxLines  = limits.textReadMaxLines,
            textReadMaxBytes  = limits.textReadMaxBytes,
            maxRetainedOutputJobs = limits.maxRetainedOutputJobs,
            outputTtlMs       = limits.outputTtlMs,
            aptWrapEnabled    = aptWrap,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TermuxRuntimeConfig(
            commandTimeoutMs  = TermuxDefaults.DEFAULT_COMMAND_TIMEOUT_MS,
            toolCallTimeoutMs = TermuxDefaults.DEFAULT_TOOL_CALL_TIMEOUT_MS,
            verifyTimeoutMs   = TermuxDefaults.DEFAULT_VERIFY_TIMEOUT_MS,
            defaultWorkingDir = TermuxDefaults.DEFAULT_WORKING_DIR,
            maxStdoutBytes    = TermuxDefaults.DEFAULT_MAX_STDOUT,
            maxStderrBytes    = TermuxDefaults.DEFAULT_MAX_STDERR,
            textReadMaxLines  = TermuxDefaults.DEFAULT_TEXT_READ_MAX_LINES,
            textReadMaxBytes  = TermuxDefaults.DEFAULT_TEXT_READ_MAX_BYTES,
            maxRetainedOutputJobs = TermuxDefaults.DEFAULT_MAX_RETAINED_OUTPUT_JOBS,
            outputTtlMs       = TermuxDefaults.DEFAULT_OUTPUT_TTL_MS,
            aptWrapEnabled    = TermuxDefaults.DEFAULT_APT_WRAP_ENABLED,
        ),
    )

    // --- Write helpers. Unit conversion happens here so [TermuxPreferences] always receives ms/bytes. ---

    /** [seconds] is the UI display unit for command timeout. Clamping in [TermuxPreferences]. */
    fun setCommandTimeoutSeconds(seconds: Long) {
        viewModelScope.launch { prefs.setCommandTimeoutMs(seconds * 1_000L) }
    }

    /** [minutes] is the UI display unit for the per-tool timeout. */
    fun setToolCallTimeoutMinutes(minutes: Long) {
        viewModelScope.launch { prefs.setToolCallTimeoutMs(minutes * 60_000L) }
    }

    /** [seconds] is the UI display unit for verify timeout. */
    fun setVerifyTimeoutSeconds(seconds: Long) {
        viewModelScope.launch { prefs.setVerifyTimeoutMs(seconds * 1_000L) }
    }

    fun setDefaultWorkingDir(dir: String) {
        viewModelScope.launch { prefs.setDefaultWorkingDir(dir) }
    }

    fun setMaxStdoutBytes(bytes: Int) {
        viewModelScope.launch { prefs.setMaxStdoutBytes(bytes) }
    }

    fun setMaxStderrBytes(bytes: Int) {
        viewModelScope.launch { prefs.setMaxStderrBytes(bytes) }
    }

    fun setTextReadMaxLines(lines: Int) {
        viewModelScope.launch { prefs.setTextReadMaxLines(lines) }
    }

    fun setTextReadMaxBytes(bytes: Int) {
        viewModelScope.launch { prefs.setTextReadMaxBytes(bytes) }
    }

    fun setMaxRetainedOutputJobs(count: Int) {
        viewModelScope.launch { prefs.setMaxRetainedOutputJobs(count) }
    }

    fun setOutputTtlHours(hours: Long) {
        viewModelScope.launch { prefs.setOutputTtlMs(hours * 60L * 60L * 1_000L) }
    }

    fun setAptWrapEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setAptWrapEnabled(enabled) }
    }

    // Private intermediate holder to avoid 7-flow combine vararg.
    private data class LimitsPartial(
        val maxStderrBytes: Int,
        val textReadMaxLines: Int,
        val textReadMaxBytes: Int,
        val maxRetainedOutputJobs: Int,
        val outputTtlMs: Long,
    )

    private data class Partial(
        val commandTimeoutMs: Long,
        val toolCallTimeoutMs: Long,
        val verifyTimeoutMs: Long,
        val defaultWorkingDir: String,
        val maxStdoutBytes: Int,
    )
}
