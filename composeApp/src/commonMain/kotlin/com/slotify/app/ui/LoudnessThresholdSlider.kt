package com.slotify.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Limiter threshold control (−24..0 dBFS) shared by the per-track Custom sheet
 * and Settings → Loudness balance, so both look identical.
 */
@Composable
fun LoudnessThresholdSlider(
    thresholdDb: Float,
    onThresholdChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = if (thresholdDb <= -0.05f) {
                "Limit starts at ${"%.1f".format(thresholdDb)} dBFS " +
                    "(${fmtAbsThreshold(thresholdDb)} dB below max)"
            } else {
                "Limit starts at max volume (0 dBFS)"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = thresholdDb,
            onValueChange = onThresholdChange,
            valueRange = -24f..0f,
        )
    }
}

private fun fmtAbsThreshold(db: Float): String {
    val a = kotlin.math.abs(db)
    val whole = a.toInt()
    val tenths = (((a - whole) * 10) + 0.5).toInt()
    return if (tenths == 0) "$whole" else "$whole.$tenths"
}
