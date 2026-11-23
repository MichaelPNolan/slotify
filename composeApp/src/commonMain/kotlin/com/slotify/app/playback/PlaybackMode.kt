package com.slotify.app.playback

import com.slotify.app.data.PlaybackStore

/** Player-bar cycle: Linear → Shuffle → Artist (2) → Artist (all). */
enum class PlaybackMode { LINEAR, SHUFFLE, ARTIST_2, ARTIST_ALL }

fun PlaybackMode.cycle(): PlaybackMode =
    PlaybackMode.values()[(ordinal + 1) % PlaybackMode.values().size]

fun PlaybackMode.isArtistMode(): Boolean =
    this == PlaybackMode.ARTIST_2 || this == PlaybackMode.ARTIST_ALL

/** Restore mode from the playback store; legacy files only carry `shuffle`. */
fun PlaybackStore.initialMode(): PlaybackMode =
    mode.takeIf { it.isNotBlank() }
        ?.let { runCatching { PlaybackMode.valueOf(it) }.getOrNull() }
        ?: if (shuffle) PlaybackMode.SHUFFLE else PlaybackMode.LINEAR
