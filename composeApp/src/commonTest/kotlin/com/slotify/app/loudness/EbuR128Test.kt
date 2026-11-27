package com.slotify.app.loudness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EbuR128Test {

    /** 1 kHz sine, stereo, peak 0.5 — integrated should be roughly −10…0 LUFS. */
    private fun sine1k(sampleRate: Int = 48000, seconds: Int = 10, amp: Float = 0.5f): FloatArray {
        val n = sampleRate * seconds
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val v = (amp * kotlin.math.sin(2.0 * Math.PI * 1000.0 * i / sampleRate)).toFloat()
            out[i * 2] = v
            out[i * 2 + 1] = v
        }
        return out
    }

    @Test
    fun sine1kIntegratedLufsIsReasonable() {
        val r = EbuR128(48000, 2)
        val pcm = sine1k()
        r.processInterleaved(pcm, pcm.size)
        val out = r.finish()
        assertTrue(out.integratedLufs in -15.0..0.0, "got ${out.integratedLufs}")
        assertTrue(out.loudnessRange >= 0.0, "LRA got ${out.loudnessRange}")
        assertTrue(out.truePeakDbtp in -20.0..0.0, "dBTP got ${out.truePeakDbtp}")
    }

    @Test
    fun digitalSilenceIsGatedOut() {
        val r = EbuR128(48000, 2)
        val pcm = FloatArray(48000 * 2 * 5)
        r.processInterleaved(pcm, pcm.size)
        val out = r.finish()
        assertTrue(out.integratedLufs <= -70.0, "silence got ${out.integratedLufs}")
    }

    @Test
    fun chipMapsSilenceToZeroAndLoudNear99() {
        assertEquals(0, loudnessChip(-70.0))
        assertEquals(0, loudnessChip(-80.0))
        assertEquals(99, loudnessChip(0.0))
        assertEquals(99, loudnessChip(3.0))
        assertEquals(50, loudnessChip(-35.0))
    }

    @Test
    fun recommendedBoostIsClampedToZeroAndMax() {
        assertEquals(0.0, recommendedBoostDb(-10.0, -14.0, 12.0))
        assertEquals(4.0, recommendedBoostDb(-18.0, -14.0, 12.0), absoluteTolerance = 1e-6)
        assertEquals(12.0, recommendedBoostDb(-30.0, -14.0, 12.0))
        assertEquals(0.0, recommendedBoostDb(-18.0, null, 12.0))
    }
}
