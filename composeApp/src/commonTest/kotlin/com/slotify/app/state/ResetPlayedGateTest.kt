package com.slotify.app.state

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResetPlayedGateTest {

    @Test
    fun asksOnlyForPlayedFiles() {
        assertTrue(
            shouldAskResetPlayed("/m/a.opus", setOf("/m/a.opus"), askSuppressed = false),
        )
        assertFalse(
            shouldAskResetPlayed("/m/b.opus", setOf("/m/a.opus"), askSuppressed = false),
        )
        assertFalse(shouldAskResetPlayed(null, setOf("/m/a.opus"), askSuppressed = false))
        assertFalse(shouldAskResetPlayed("", setOf(""), askSuppressed = false))
    }

    @Test
    fun justPlayThisStopsAskingForSession() {
        assertFalse(
            shouldAskResetPlayed("/m/a.opus", setOf("/m/a.opus"), askSuppressed = true),
        )
        assertFalse(
            shouldAskResetPlayed("/m/b.opus", setOf("/m/a.opus"), askSuppressed = true),
        )
    }
}
