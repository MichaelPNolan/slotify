package com.slotify.app.playback

import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

actual fun createAudioPlayer(): AudioPlayer = AndroidAudioPlayer()

/**
 * Android player over android.media.MediaPlayer — handles mp3/flac/ogg/m4a/wav
 * natively. Position is polled while playing (MediaPlayer has no time events).
 */
class AndroidAudioPlayer : AudioPlayer {

    private val player = MediaPlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null
    private var pendingPlay = true

    private val _snapshot = MutableStateFlow(PlayerSnapshot())
    override val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    init {
        player.setOnPreparedListener {
            _snapshot.value = _snapshot.value.copy(
                durationMs = player.duration.toLong(),
                error = null,
            )
            if (pendingPlay) {
                player.start()
                // Must flip isPlaying here — prepareAsync leaves it false, so the
                // playlist Stop button never appeared until a second click.
                _snapshot.value = _snapshot.value.copy(isPlaying = true)
            } else {
                _snapshot.value = _snapshot.value.copy(isPlaying = false)
            }
            startPositionPolling()
        }
        player.setOnCompletionListener {
            stopPositionPolling()
            _snapshot.value = _snapshot.value.copy(
                isPlaying = false,
                positionMs = _snapshot.value.durationMs,
            )
            _events.tryEmit(PlayerEvent.Completed)
        }
        player.setOnErrorListener { _, what, extra ->
            stopPositionPolling()
            val message = "Playback failed (what=$what extra=$extra)"
            _snapshot.value = _snapshot.value.copy(isPlaying = false, error = message)
            _events.tryEmit(PlayerEvent.Failed(message))
            true
        }
    }

    override fun load(path: String, startPaused: Boolean) {
        pendingPlay = !startPaused
        stopPositionPolling()
        runCatching {
            player.reset()
            when {
                path.startsWith("content://") -> {
                    // SAF URI (Link file when a real path was not resolvable).
                    val ctx = com.slotify.app.data.androidAppContext
                        ?: error("Android context not ready")
                    player.setDataSource(ctx, android.net.Uri.parse(path))
                }
                else -> player.setDataSource(path)
            }
            // Optimistic isPlaying so the UI shows Stop/Pause while prepare runs.
            _snapshot.value = PlayerSnapshot(
                currentPath = path,
                isPlaying = pendingPlay,
            )
            player.prepareAsync()
        }.onFailure { e ->
            val message = e.message ?: "Cannot open file"
            _snapshot.value = _snapshot.value.copy(isPlaying = false, error = message)
            _events.tryEmit(PlayerEvent.Failed(message))
        }
    }

    override fun play() {
        pendingPlay = true
        runCatching {
            player.start()
            _snapshot.value = _snapshot.value.copy(isPlaying = true)
            startPositionPolling()
        }.onFailure {
            // Still preparing — onPrepared will start() because pendingPlay is set.
            _snapshot.value = _snapshot.value.copy(isPlaying = true)
        }
    }

    override fun pause() {
        pendingPlay = false
        runCatching { player.pause() }
        _snapshot.value = _snapshot.value.copy(isPlaying = false)
        stopPositionPolling()
    }

    override fun seekTo(positionMs: Long) {
        runCatching {
            player.seekTo(positionMs.toInt())
            _snapshot.value = _snapshot.value.copy(positionMs = positionMs)
        }
    }

    override fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        runCatching { player.setVolume(v, v) }
    }

    override fun stop() {
        stopPositionPolling()
        runCatching {
            player.stop()
            player.reset()
        }
        _snapshot.value = PlayerSnapshot()
    }

    override fun release() {
        stopPositionPolling()
        runCatching { player.release() }
        _snapshot.value = PlayerSnapshot()
    }

    private fun startPositionPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                val pos = runCatching { player.currentPosition.toLong() }.getOrNull()
                if (pos != null) {
                    _snapshot.value = _snapshot.value.copy(
                        positionMs = pos,
                        durationMs = runCatching { player.duration.toLong() }
                            .getOrDefault(_snapshot.value.durationMs),
                    )
                }
                delay(400)
            }
        }
    }

    private fun stopPositionPolling() {
        pollJob?.cancel()
        pollJob = null
    }
}
