package com.slotify.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import com.slotify.app.state.AppState

/**
 * The current song's embedded cover behind the content screens: sharp image,
 * dark gradient scrim for readability (spec S6). Renders nothing when art is
 * null — never generates or fetches a substitute (spec S2/S9).
 */
@Composable
fun CoverBackdrop(state: AppState) {
    val art by state.coverArt.collectAsState()
    // Stays mounted so Crossfade can also fade OUT when art clears.
    Crossfade(targetState = art, animationSpec = tween(250), label = "coverArt") { bitmap ->
        if (bitmap != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.45f),
                                    Color.Black.copy(alpha = 0.70f),
                                ),
                            ),
                        ),
                )
            }
        }
    }
}
