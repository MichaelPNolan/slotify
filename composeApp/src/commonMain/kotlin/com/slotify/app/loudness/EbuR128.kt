package com.slotify.app.loudness

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.tan

data class LoudnessResult(
    val integratedLufs: Double,
    val loudnessRange: Double,
    val truePeakDbtp: Double,
)

/**
 * EBU R128 / ITU-R BS.1770-4 loudness over interleaved float PCM in −1..1.
 *
 * K-weight each sample once on input, keep a ring of filtered samples, emit one
 * mean-square per 400 ms block every 100 ms (75 % overlap), then gate.
 */
class EbuR128(private val sampleRate: Int, private val channels: Int) {

    private val blockSize = (sampleRate * 0.4).toInt().coerceAtLeast(1)
    private val hop = (sampleRate * 0.1).toInt().coerceAtLeast(1)
    private val ringFrames = blockSize
    private val ringSamples = ringFrames * channels

    private val shelfB = DoubleArray(3)
    private val shelfA = DoubleArray(3)
    private val hpB = DoubleArray(3)
    private val hpA = DoubleArray(3)
    private val shelfX1 = DoubleArray(channels)
    private val shelfX2 = DoubleArray(channels)
    private val shelfY1 = DoubleArray(channels)
    private val shelfY2 = DoubleArray(channels)
    private val hpX1 = DoubleArray(channels)
    private val hpX2 = DoubleArray(channels)
    private val hpY1 = DoubleArray(channels)
    private val hpY2 = DoubleArray(channels)

    /** Filtered interleaved samples in a circular buffer (oldest at [head]). */
    private val ring = DoubleArray(ringSamples)
    private var head = 0
    private var ringSize = 0

    private val blockMs = ArrayList<Double>()
    private var rawPeak = 1e-9

    init {
        designKWeighting(sampleRate)
    }

    fun processInterleaved(samples: FloatArray, count: Int) {
        var i = 0
        while (i + channels <= count) {
            for (ch in 0 until channels) {
                val raw = samples[i + ch]
                val a = abs(raw.toDouble())
                if (a > rawPeak) rawPeak = a
                val filtered = kWeight(ch, raw)
                if (ringSize < ringSamples) {
                    ring[(head + ringSize) % ringSamples] = filtered
                    ringSize++
                } else {
                    ring[head] = filtered
                    head = (head + 1) % ringSamples
                }
            }
            i += channels
            while (ringSize >= ringSamples) {
                blockMs.add(meanSquareRing())
                // Drop one hop of frames from the front (75% overlap).
                repeat(hop * channels) {
                    head = (head + 1) % ringSamples
                    ringSize--
                }
            }
        }
    }

    fun finish(): LoudnessResult {
        if (ringSize >= ringSamples / 2) {
            blockMs.add(meanSquareRing())
        }
        val integrated = gatedLufs(blockMs, channels)
        val lra = computeLra(blockMs, channels)
        val dbtp = 20.0 * log10(rawPeak.coerceAtLeast(1e-9))
        return LoudnessResult(integrated, lra, dbtp)
    }

    private fun meanSquareRing(): Double {
        // BS.1770: per-channel mean square × weight 1.0, summed over channels.
        var sum = 0.0
        val frames = ringSize / channels
        if (frames == 0) return 0.0
        for (ch in 0 until channels) {
            var acc = 0.0
            for (f in 0 until frames) {
                val idx = (head + f * channels + ch) % ringSamples
                val v = ring[idx]
                acc += v * v
            }
            sum += acc / frames
        }
        return sum
    }

    private fun kWeight(ch: Int, x: Float): Double {
        val xv = x.toDouble()
        val shelfY = shelfB[0] * xv + shelfB[1] * shelfX1[ch] + shelfB[2] * shelfX2[ch] -
            shelfA[1] * shelfY1[ch] - shelfA[2] * shelfY2[ch]
        shelfX2[ch] = shelfX1[ch]
        shelfX1[ch] = xv
        shelfY2[ch] = shelfY1[ch]
        shelfY1[ch] = shelfY
        val y = hpB[0] * shelfY + hpB[1] * hpX1[ch] + hpB[2] * hpX2[ch] -
            hpA[1] * hpY1[ch] - hpA[2] * hpY2[ch]
        hpX2[ch] = hpX1[ch]
        hpX1[ch] = shelfY
        hpY2[ch] = hpY1[ch]
        hpY1[ch] = y
        return y
    }

    private fun designKWeighting(sr: Int) {
        val f0 = 1681.974450955533
        val G = 3.999843853973347
        val Q = 0.7071752369554196
        val K = tan(PI * f0 / sr)
        val vh = 10.0.pow(G / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val a0 = 1.0 + K / Q + K * K
        shelfB[0] = (vh + vb * K / Q + K * K) / a0
        shelfB[1] = 2.0 * (K * K - vh) / a0
        shelfB[2] = (vh - vb * K / Q + K * K) / a0
        shelfA[0] = 1.0
        shelfA[1] = 2.0 * (K * K - 1.0) / a0
        shelfA[2] = (1.0 - K / Q + K * K) / a0

        val f0h = 38.13547087602444
        val Qh = 0.5003270373238773
        val Kh = tan(PI * f0h / sr)
        val a0h = 1.0 + Kh / Qh + Kh * Kh
        hpB[0] = 1.0
        hpB[1] = -2.0
        hpB[2] = 1.0
        hpA[0] = 1.0
        hpA[1] = 2.0 * (Kh * Kh - 1.0) / a0h
        hpA[2] = (1.0 - Kh / Qh + Kh * Kh) / a0h
    }

    private fun gatedLufs(powers: List<Double>, ch: Int): Double {
        if (powers.isEmpty()) return -70.0
        val absPow = 10.0.pow(-70.0 / 10.0)
        val weighted = powers.map { it * ch }
        val aboveAbs = weighted.filter { it > absPow }
        if (aboveAbs.isEmpty()) return -70.0
        val rel = 10.0 * log10(aboveAbs.average()) - 10.0
        val aboveRel = aboveAbs.filter { it > 10.0.pow(rel / 10.0) }
        if (aboveRel.isEmpty()) return -70.0
        return -0.691 + 10.0 * log10(aboveRel.average())
    }

    private fun computeLra(powers: List<Double>, ch: Int): Double {
        if (powers.size < 2) return 0.0
        val st = powers.map { 10.0 * log10((it * ch).coerceAtLeast(1e-12)) }
            .filter { it > -70.0 }
        if (st.size < 2) return 0.0
        val rel = 10.0 * log10(st.map { 10.0.pow(it / 10.0) }.average()) - 20.0
        val gated = st.filter { it > rel }.sorted()
        if (gated.size < 2) return 0.0
        val lo = gated[quantileIndex(gated.size, 0.10)]
        val hi = gated[quantileIndex(gated.size, 0.95)]
        return (hi - lo).coerceAtLeast(0.0)
    }

    private fun quantileIndex(n: Int, q: Double): Int =
        round(q * (n - 1)).toInt().coerceIn(0, n - 1)
}

/** 0 = silence (−70 LUFS), 99 = very loud (≥0 LUFS). */
fun loudnessChip(integratedLufs: Double): Int {
    val v = round((integratedLufs + 70.0) / 70.0 * 99.0)
    return v.toInt().coerceIn(0, 99)
}

/** Boost-only corrective gain toward playlist mean (never negative). */
fun recommendedBoostDb(trackLufs: Double, meanLufs: Double?, maxBoostDb: Double): Double {
    if (meanLufs == null) return 0.0
    return (meanLufs - trackLufs).coerceIn(0.0, maxBoostDb)
}
