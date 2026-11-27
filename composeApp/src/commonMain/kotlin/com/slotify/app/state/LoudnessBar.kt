package com.slotify.app.state

/**
 * What the loudness-balance path is doing to the current track’s audio —
 * shown beside the volume slider.
 */
data class LoudnessBar(
    val boostDb: Float = 0f,
    val limiting: Boolean = false,
) {
    val label: String
        get() = when {
            boostDb > 0.05f && limiting -> "Boost +${fmt(boostDb)} · Limiting"
            boostDb > 0.05f -> "Boost +${fmt(boostDb)} dB"
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
