package com.slotify.app.state

/** Limiter stage constants (Android DynamicsProcessing). */
const val LIMITER_RATIO = 1.5f
const val LIMITER_ATTACK_MS = 5f
const val LIMITER_RELEASE_MS = 50f
const val LIMITER_POST_GAIN_DB = -3f

/** Full scale of the volume-row reduction bar: this much gain reduction fills it. */
const val REDUCTION_METER_FULL_DB = 3f

/**
 * Gain reduction (dB) a [ratio] compressor applies to a peak past [thresholdDb]:
 * the overage is pulled down at [ratio]:1, so attenuation = overage × (1 − 1/ratio).
 * 0 at or below the threshold.
 */
fun gainReductionDb(peakDb: Float, thresholdDb: Float, ratio: Float): Float {
    val over = peakDb - thresholdDb
    return if (over <= 0f) 0f else over * (1f - 1f / ratio)
}

/**
 * What the loudness-balance path is doing to the current track’s audio —
 * shown beside the volume slider.
 *
 * [balanceOff] — Settings → Loudness balance is disabled (nothing is applied).
 * [boostDb] may be negative (Custom cut); positive is makeup boost.
 */
data class LoudnessBar(
    val boostDb: Float = 0f,
    val limiting: Boolean = false,
    val balanceOff: Boolean = false,
) {
    val label: String
        get() = when {
            balanceOff -> "Balance off"
            boostDb > 0.05f && limiting -> "Boost +${fmt(boostDb)} · Limiting"
            boostDb > 0.05f -> "Boost +${fmt(boostDb)} dB"
            boostDb < -0.05f && limiting -> "Cut −${fmt(-boostDb)} · Limiting"
            boostDb < -0.05f -> "Cut −${fmt(-boostDb)} dB"
            limiting -> "Limiting"
            else -> "Neutral"
        }

    companion object {
        /** 12 → "12.0"; 3.5 → "3.5" (no Locale/String.format in common). */
        internal fun fmt(db: Float): String {
            var whole = db.toInt()
            var tenths = (((db - whole) * 10f) + 0.5f).toInt()
            if (tenths >= 10) {
                whole += 1
                tenths = 0
            }
            return "$whole.$tenths"
        }
    }
}
