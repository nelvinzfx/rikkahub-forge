package me.rerere.rikkahub.ui.pages.editor

/**
 * Pure change-detection decisions for the editor's external-modification watch.
 * SAF document URIs cannot be observed with FileObserver/WatchService, so the
 * VM polls [DocumentFile] metadata and feeds it here; keeping the verdict logic
 * pure makes it unit-testable on the JVM.
 */
data class DiskSnapshot(val lastModified: Long, val length: Long)

enum class ChangeVerdict { UNCHANGED, CHANGED, MISSING }

/**
 * Compares the snapshot taken when the tab last touched disk with a fresh stat.
 * [current] == null means the file is gone or the provider refused the query.
 *
 * Detection contract:
 * - no baseline (never stated successfully) -> UNCHANGED; the caller adopts the
 *   fresh snapshot as the baseline silently instead of prompting on first poll
 * - mtimes only count when BOTH sides report one (> 0); some providers always
 *   return 0, in which case detection degrades to length-only
 * - length is only compared when both sides know theirs (>= 0)
 */
fun compareSnapshots(known: DiskSnapshot, current: DiskSnapshot?): ChangeVerdict {
    if (current == null) return ChangeVerdict.MISSING
    val noBaseline = known.lastModified <= 0L && known.length < 0L
    if (noBaseline) return ChangeVerdict.UNCHANGED
    val mtimesUsable = known.lastModified > 0L && current.lastModified > 0L
    if (mtimesUsable && known.lastModified != current.lastModified) return ChangeVerdict.CHANGED
    if (known.length >= 0L && current.length >= 0L && known.length != current.length) {
        return ChangeVerdict.CHANGED
    }
    return ChangeVerdict.UNCHANGED
}

enum class AutoSaveDecision { SAVE, DEFER_CONFLICT, SKIP }

/**
 * Auto-save must never silently overwrite an external edit: anything suspicious
 * defers to the same conflict flow the poller raises, and the user's local
 * content is kept safe as a draft until they resolve it.
 */
fun decideAutoSave(
    dirty: Boolean,
    readOnly: Boolean,
    conflictPending: Boolean,
    verdict: ChangeVerdict,
): AutoSaveDecision = when {
    !dirty || readOnly -> AutoSaveDecision.SKIP
    conflictPending || verdict != ChangeVerdict.UNCHANGED -> AutoSaveDecision.DEFER_CONFLICT
    else -> AutoSaveDecision.SAVE
}
