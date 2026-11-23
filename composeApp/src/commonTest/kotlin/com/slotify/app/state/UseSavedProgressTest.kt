package com.slotify.app.state

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UseSavedProgressTest {

    // Regression: a tap on row 0 must start THAT row. Resuming saved progress
    // for row-0 taps silently loaded a different track, paused at the saved
    // position — the first track of every playlist looked dead and playback
    // failures were never attempted (so never detected).
    @Test
    fun rowTapNeverResumesSavedProgress() {
        assertFalse(useSavedProgress(fromRowTap = true, fromRowIndex = 0, hasSaved = true))
        assertFalse(useSavedProgress(fromRowTap = true, fromRowIndex = 5, hasSaved = true))
    }

    @Test
    fun headerStartResumesSavedProgress() {
        assertTrue(useSavedProgress(fromRowTap = false, fromRowIndex = 0, hasSaved = true))
    }

    @Test
    fun withoutSavedProgressNothingResumes() {
        assertFalse(useSavedProgress(fromRowTap = false, fromRowIndex = 0, hasSaved = false))
    }

    @Test
    fun headerOnlyResumesAtRowZero() {
        assertFalse(useSavedProgress(fromRowTap = false, fromRowIndex = 3, hasSaved = true))
    }
}
