package com.slotify.app.playback

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter

actual fun createAudioPlayer(): AudioPlayer = VlcAudioPlayer()

/**
 * Desktop player backed by system VLC via vlcj.
 * VLC is discovered through the normal JNA library search (pacman installs
 * libvlc.so under /usr/lib). Audio-only media — no video window is created.
 */
class VlcAudioPlayer : AudioPlayer {

    private val factory = MediaPlayerFactory()
    private val player: MediaPlayer = factory.mediaPlayers().newMediaPlayer()

    private val _snapshot = MutableStateFlow(PlayerSnapshot())
    override val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    init {
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mp: MediaPlayer) {
                _snapshot.value = _snapshot.value.copy(isPlaying = true, error = null)
            }

            override fun paused(mp: MediaPlayer) {
                _snapshot.value = _snapshot.value.copy(isPlaying = false)
            }

            override fun timeChanged(mp: MediaPlayer, time: Long) {
                _snapshot.value = _snapshot.value.copy(positionMs = time)
            }

            override fun lengthChanged(mp: MediaPlayer, length: Long) {
                _snapshot.value = _snapshot.value.copy(durationMs = length)
            }

            override fun finished(mp: MediaPlayer) {
                _snapshot.value = _snapshot.value.copy(isPlaying = false)
                _events.tryEmit(PlayerEvent.Completed)
            }

            override fun stopped(mp: MediaPlayer) {
                _snapshot.value = _snapshot.value.copy(isPlaying = false)
            }

            override fun error(mp: MediaPlayer) {
                _snapshot.value = _snapshot.value.copy(
                    isPlaying = false,
                    error = "VLC could not play this file",
                )
                _events.tryEmit(PlayerEvent.Failed("VLC could not play this file"))
            }
        })
    }

    override fun load(path: String, startPaused: Boolean) {
        _snapshot.value = PlayerSnapshot(currentPath = path)
        if (startPaused) {
            player.media().startPaused(path)
        } else {
            player.media().play(path)
        }
    }

    override fun play() {
        player.controls().play()
    }

    override fun pause() {
        player.controls().setPause(true)
    }

    override fun seekTo(positionMs: Long) {
        player.controls().setTime(positionMs)
        _snapshot.value = _snapshot.value.copy(positionMs = positionMs)
    }

    override fun setVolume(volume: Float) {
        player.audio().setVolume((volume.coerceIn(0f, 1f) * 100).toInt())
    }

    override fun stop() {
        player.controls().stop()
        _snapshot.value = PlayerSnapshot()
    }

    override fun release() {
        runCatching { player.release() }
        runCatching { factory.release() }
        _snapshot.value = PlayerSnapshot()
    }
}
