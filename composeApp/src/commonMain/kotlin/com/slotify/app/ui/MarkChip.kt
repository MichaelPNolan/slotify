package com.slotify.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.slotify.app.model.RowMark

/** Compact single-letter row mark: m (none) → s → d (red) → m.
 *  Minimal horizontal padding so it hugs the row edge. */
@Composable
fun MarkChip(mark: RowMark?, onClick: () -> Unit) {
    val (label, color) = when (mark) {
        null -> "m" to MaterialTheme.colorScheme.outline
        RowMark.SKIP -> "s" to MaterialTheme.colorScheme.secondary
        RowMark.DELETE -> "d" to MaterialTheme.colorScheme.error
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
