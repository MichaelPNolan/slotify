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

    @Test
    fun gainReductionZeroAtOrBelowThreshold() {
        assertEquals(0f, gainReductionDb(peakDb = -6f, thresholdDb = 0f, ratio = 1.5f))
        assertEquals(0f, gainReductionDb(peakDb = 0f, thresholdDb = 0f, ratio = 1.5f))
    }

    @Test
    fun gainReductionIsOverageAtRatio() {
        // 3 dB over at 1.5:1 → output 2 dB over → 1 dB attenuation.
        assertEquals(1f, gainReductionDb(peakDb = 0f, thresholdDb = -3f, ratio = 1.5f), 1e-4f)
        // 6 dB over at 2:1 → 3 dB attenuation.
        assertEquals(3f, gainReductionDb(peakDb = 0f, thresholdDb = -6f, ratio = 2f), 1e-4f)
    }
}
