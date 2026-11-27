package com.slotify.app.state

import kotlin.test.Test
import kotlin.test.assertEquals

class LoudnessBarTest {

    @Test
    fun neutralWhenNoCorrection() {
        assertEquals("Neutral", LoudnessBar().label)
        assertEquals("Neutral", LoudnessBar(boostDb = 0f, limiting = false).label)
    }

    @Test
    fun boostShowsDecimals() {
        assertEquals("Boost +3.5 dB", LoudnessBar(boostDb = 3.5f).label)
        assertEquals("Boost +12.0 dB", LoudnessBar(boostDb = 12f).label)
    }

    @Test
    fun limitingAlone() {
        assertEquals("Limiting", LoudnessBar(limiting = true).label)
    }

    @Test
    fun bothWhenBoostAndLimiter() {
        assertEquals(
            "Boost +2.0 · Limiting",
            LoudnessBar(boostDb = 2f, limiting = true).label,
        )
    }

    @Test
    fun balanceOffIsExplicit() {
        assertEquals("Balance off", LoudnessBar(balanceOff = true).label)
        assertEquals(
            "Balance off",
            LoudnessBar(boostDb = 3f, limiting = true, balanceOff = true).label,
        )
    }

    @Test
    fun customCutShowsCutNotNeutral() {
        assertEquals("Cut −3.5 dB", LoudnessBar(boostDb = -3.5f).label)
        assertEquals(
            "Cut −6.0 · Limiting",
            LoudnessBar(boostDb = -6f, limiting = true).label,
        )
    }
}
