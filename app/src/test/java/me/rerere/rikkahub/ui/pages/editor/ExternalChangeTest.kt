package me.rerere.rikkahub.ui.pages.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM coverage for the external-change verdicts and the auto-save gate.
 * The contract that matters most: anything that is not a confident "unchanged"
 * must defer to the conflict flow instead of writing over an external edit.
 */
class ExternalChangeTest {

    @Test
    fun compare_identical_returnsUnchanged() {
        val snap = DiskSnapshot(lastModified = 1000L, length = 42L)
        assertEquals(ChangeVerdict.UNCHANGED, compareSnapshots(snap, snap))
    }

    @Test
    fun compare_lastModifiedDiffers_returnsChanged() {
        val known = DiskSnapshot(lastModified = 1000L, length = 42L)
        val current = DiskSnapshot(lastModified = 2000L, length = 42L)
        assertEquals(ChangeVerdict.CHANGED, compareSnapshots(known, current))
    }

    @Test
    fun compare_lengthDiffers_returnsChanged() {
        val known = DiskSnapshot(lastModified = 1000L, length = 42L)
        val current = DiskSnapshot(lastModified = 1000L, length = 43L)
        assertEquals(ChangeVerdict.CHANGED, compareSnapshots(known, current))
    }

    @Test
    fun compare_nullCurrent_returnsMissing() {
        assertEquals(
            ChangeVerdict.MISSING,
            compareSnapshots(DiskSnapshot(1000L, 42L), null),
        )
    }

    @Test
    fun compare_noBaseline_adoptsSilently() {
        // open-time stat failure leaves DiskSnapshot(0, -1); the first
        // successful stat must become the baseline, not a false conflict
        assertEquals(
            ChangeVerdict.UNCHANGED,
            compareSnapshots(DiskSnapshot(0L, -1L), DiskSnapshot(1234L, 99L)),
        )
    }

    @Test
    fun compare_zeroMtimeBothSidesSameLength_returnsUnchanged() {
        // providers reporting mtime 0: documented length-only blind spot
        val known = DiskSnapshot(lastModified = 0L, length = 42L)
        val current = DiskSnapshot(lastModified = 0L, length = 42L)
        assertEquals(ChangeVerdict.UNCHANGED, compareSnapshots(known, current))
    }

    @Test
    fun compare_zeroMtimeLengthDiffers_returnsChanged() {
        val known = DiskSnapshot(lastModified = 0L, length = 42L)
        val current = DiskSnapshot(lastModified = 0L, length = 100L)
        assertEquals(ChangeVerdict.CHANGED, compareSnapshots(known, current))
    }

    @Test
    fun decideAutoSave_cleanTab_skips() {
        assertEquals(
            AutoSaveDecision.SKIP,
            decideAutoSave(
                dirty = false,
                readOnly = false,
                conflictPending = false,
                verdict = ChangeVerdict.UNCHANGED,
            ),
        )
    }

    @Test
    fun decideAutoSave_readOnly_skips() {
        assertEquals(
            AutoSaveDecision.SKIP,
            decideAutoSave(
                dirty = true,
                readOnly = true,
                conflictPending = false,
                verdict = ChangeVerdict.UNCHANGED,
            ),
        )
    }

    @Test
    fun decideAutoSave_conflictPending_defers() {
        assertEquals(
            AutoSaveDecision.DEFER_CONFLICT,
            decideAutoSave(
                dirty = true,
                readOnly = false,
                conflictPending = true,
                verdict = ChangeVerdict.UNCHANGED,
            ),
        )
    }

    @Test
    fun decideAutoSave_changedVerdict_defers() {
        assertEquals(
            AutoSaveDecision.DEFER_CONFLICT,
            decideAutoSave(
                dirty = true,
                readOnly = false,
                conflictPending = false,
                verdict = ChangeVerdict.CHANGED,
            ),
        )
    }

    @Test
    fun decideAutoSave_missingVerdict_defers() {
        assertEquals(
            AutoSaveDecision.DEFER_CONFLICT,
            decideAutoSave(
                dirty = true,
                readOnly = false,
                conflictPending = false,
                verdict = ChangeVerdict.MISSING,
            ),
        )
    }

    @Test
    fun decideAutoSave_dirtyUnchanged_saves() {
        assertEquals(
            AutoSaveDecision.SAVE,
            decideAutoSave(
                dirty = true,
                readOnly = false,
                conflictPending = false,
                verdict = ChangeVerdict.UNCHANGED,
            ),
        )
    }
}
