package com.slotify.app.playback

import android.media.MediaPlayer
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Visualizer
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
    private var meterJob: Job? = null
    private var pendingPlay = true
    private var pendingBoostDb = 0f
    private var pendingLimiter = false
    private var pendingLimiterThresholdDb = 0f
    private var dynamics: DynamicsProcessing? = null
    private var dynamicsLimiterOn = false
    private var visualizer: Visualizer? = null

    private val _snapshot = MutableStateFlow(PlayerSnapshot())
    override val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    private val _reductionMeter = MutableStateFlow(0f)
    /** 0..1 — how far the monitored peak sits above the limiter threshold. */
    override val reductionMeter: StateFlow<Float> = _reductionMeter.asStateFlow()

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
            applyDynamics()
            ensureMeter()
            startPositionPolling()
        }
        player.setOnCompletionListener {
            stopPositionPolling()
            stopMeter()
            _snapshot.value = _snapshot.value.copy(
                isPlaying = false,
                positionMs = _snapshot.value.durationMs,
            )
            _events.tryEmit(PlayerEvent.Completed)
        }
        player.setOnErrorListener { _, what, extra ->
            stopPositionPolling()
            stopMeter()
            val message = "Playback failed (what=$what extra=$extra)"
            _snapshot.value = _snapshot.value.copy(isPlaying = false, error = message)
            _events.tryEmit(PlayerEvent.Failed(message))
            true
        }
    }

    override fun load(path: String, startPaused: Boolean) {
        pendingPlay = !startPaused
        stopPositionPolling()
        stopMeter()
        clearDynamics()
        runCatching {
            player.reset()
            when {
                path.startsWith("content://") -> {
                    val ctx = com.slotify.app.data.androidAppContext
                        ?: error("Android context not ready")
                    player.setDataSource(ctx, android.net.Uri.parse(path))
                }
                else -> player.setDataSource(path)
            }
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
            ensureMeter()
        }.onFailure {
            _snapshot.value = _snapshot.value.copy(isPlaying = true)
        }
    }

    override fun pause() {
        pendingPlay = false
        runCatching { player.pause() }
        _snapshot.value = _snapshot.value.copy(isPlaying = false)
        stopPositionPolling()
        stopMeter()
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

    override fun setLoudnessCorrection(
        boostDb: Float,
        enableLimiter: Boolean,
        limiterThresholdDb: Float,
    ) {
        pendingBoostDb = boostDb
        pendingLimiter = enableLimiter
        pendingLimiterThresholdDb = limiterThresholdDb.coerceIn(-24f, 0f)
        applyDynamics()
        if (pendingLimiter && _snapshot.value.isPlaying) {
            ensureMeter()
        } else if (!pendingLimiter) {
            stopMeter()
        }
    }

    private fun clearDynamics() {
        runCatching { dynamics?.release() }
        dynamics = null
        dynamicsLimiterOn = false
    }

    private fun needsDynamics(): Boolean =
        pendingLimiter || pendingBoostDb > 0.01f || pendingBoostDb < -0.01f

    private fun buildLimiter(): DynamicsProcessing.Limiter =
        DynamicsProcessing.Limiter(
            true,
            true,
            0,
            pendingLimiterThresholdDb,
            5f,
            50f,
            1.5f,
            -3f,
        )

    private fun applyDynamics() {
        if (android.os.Build.VERSION.SDK_INT < 28) return
        if (!needsDynamics()) {
            clearDynamics()
            return
        }
        val existing = dynamics
        if (existing != null) {
            val ok = runCatching {
                if (pendingLimiter != dynamicsLimiterOn) return@runCatching false
                existing.setInputGainAllChannelsTo(pendingBoostDb.coerceIn(-24f, 24f))
                if (pendingLimiter) {
                    existing.setLimiterAllChannelsTo(buildLimiter())
                }
                existing.setEnabled(true)
                true
            }.getOrDefault(false)
            if (ok) return
            clearDynamics()
        }
        createDynamics()
    }

    private fun createDynamics() {
        val sid = runCatching { player.audioSessionId }.getOrNull() ?: return
        if (sid <= 0) return
        for (ch in intArrayOf(2, 1)) {
            val ok = runCatching {
                val cfg = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
                    ch,
                    false, 0,
                    false, 0,
                    false, 0,
                    pendingLimiter,
                ).build()
                val dpp = DynamicsProcessing(
                    DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
                    sid,
                    cfg,
                )
                dpp.setInputGainAllChannelsTo(pendingBoostDb.coerceIn(-24f, 24f))
                if (pendingLimiter) {
                    dpp.setLimiterAllChannelsTo(buildLimiter())
                }
                if (dpp.setEnabled(true) != AudioEffect.SUCCESS) {
                    dpp.release()
                    return@runCatching false
                }
                dynamics = dpp
                dynamicsLimiterOn = pendingLimiter
                true
            }.getOrDefault(false)
            if (ok) return
        }
        dynamics = null
        dynamicsLimiterOn = false
    }

    // ── Reduction meter (Visualizer peak vs limiter threshold) ────────

    private fun ensureMeter() {
        if (!pendingLimiter) return
        if (meterJob?.isActive == true) return
        val sid = runCatching { player.audioSessionId }.getOrNull() ?: return
        if (sid <= 0) return
        runCatching {
            val v = Visualizer(sid)
            v.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS)
            v.setEnabled(true)
            visualizer = v
        }.onFailure {
            visualizer = null
            _reductionMeter.value = 0f
            return
        }
        val v = visualizer ?: return
        meterJob = scope.launch {
            val m = Visualizer.MeasurementPeakRms()
            while (true) {
                delay(50)
                val code = runCatching { v.getMeasurementPeakRms(m) }.getOrDefault(-1)
                if (code != Visualizer.SUCCESS) continue
                val peakDb = m.mPeak / 1000f
                if (!pendingLimiter) {
                    _reductionMeter.value = 0f
                    continue
                }
                val over = peakDb - pendingLimiterThresholdDb
                _reductionMeter.value =
                    if (over <= 0f) 0f else (over / 12f).coerceIn(0f, 1f)
            }
        }
    }

    private fun stopMeter() {
        meterJob?.cancel()
        meterJob = null
        runCatching { visualizer?.release() }
        visualizer = null
        _reductionMeter.value = 0f
    }

    override fun stop() {
        stopPositionPolling()
        stopMeter()
        clearDynamics()
        runCatching {
            player.stop()
            player.reset()
        }
        _snapshot.value = PlayerSnapshot()
    }

    override fun release() {
        stopPositionPolling()
        stopMeter()
        clearDynamics()
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
