package com.slotify.app.playback

import android.media.MediaPlayer
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Visualizer
import android.util.Log
import com.slotify.app.state.LIMITER_ATTACK_MS
import com.slotify.app.state.LIMITER_POST_GAIN_DB
import com.slotify.app.state.LIMITER_RATIO
import com.slotify.app.state.LIMITER_RELEASE_MS
import com.slotify.app.state.REDUCTION_METER_FULL_DB
import com.slotify.app.state.gainReductionDb
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
 *
 * Dynamics: two engines, Settings → Dynamics engine.
 * - `"builtin"` — [DynamicsProcessing] (input gain + optional limiter)
 * - `"alt"` — [LoudnessEnhancer] (separate effect library, boost only)
 * Attach outcomes are non-silent: logcat tag `SlotifyDyn` + [dynamicsStatus].
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
    private var dynamicsEngine: String = "builtin"
    private var dynamics: DynamicsProcessing? = null
    private var dynamicsLimiterOn = false
    private var loudnessEnhancer: LoudnessEnhancer? = null
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

    private val _dynamicsStatus = MutableStateFlow("Dynamics: idle")
    override val dynamicsStatus: StateFlow<String> = _dynamicsStatus.asStateFlow()

    private fun dynLog(msg: String) {
        Log.i(DYN_TAG, msg)
        _dynamicsStatus.value = msg
    }

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
            dynLog(
                "onPrepared sid=${safeSid()} engine=$dynamicsEngine " +
                    "boost=${fmtDb(pendingBoostDb)} limiter=$pendingLimiter " +
                    "thr=${fmtDb(pendingLimiterThresholdDb)}",
            )
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
        clearDynamics("load")
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
            dynLog("load sid=${safeSid()} engine=$dynamicsEngine prepareAsync…")
            player.prepareAsync()
        }.onFailure { e ->
            val message = e.message ?: "Cannot open file"
            dynLog("load FAILED: $message")
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

    override fun setDynamicsEngine(engine: String) {
        val next = if (engine == "alt") "alt" else "builtin"
        if (next == dynamicsEngine) return
        dynLog("engine switch $dynamicsEngine → $next")
        dynamicsEngine = next
        clearDynamics("engine-switch")
        applyDynamics()
    }

    override fun setLoudnessCorrection(
        boostDb: Float,
        enableLimiter: Boolean,
        limiterThresholdDb: Float,
    ) {
        pendingBoostDb = boostDb
        pendingLimiter = enableLimiter
        pendingLimiterThresholdDb = limiterThresholdDb.coerceIn(-24f, 0f)
        dynLog(
            "setCorrection engine=$dynamicsEngine boost=${fmtDb(pendingBoostDb)} " +
                "limiter=$pendingLimiter thr=${fmtDb(pendingLimiterThresholdDb)} " +
                "sid=${safeSid()}",
        )
        applyDynamics()
        if (pendingLimiter && _snapshot.value.isPlaying && dynamicsEngine == "builtin") {
            ensureMeter()
        } else if (!pendingLimiter || dynamicsEngine == "alt") {
            stopMeter()
        }
    }

    private fun clearDynamics(reason: String) {
        val hadDx = dynamics != null
        val hadLe = loudnessEnhancer != null
        runCatching { dynamics?.release() }
        runCatching { loudnessEnhancer?.release() }
        dynamics = null
        loudnessEnhancer = null
        dynamicsLimiterOn = false
        if (hadDx || hadLe) {
            dynLog("clearDynamics ($reason) hadBuiltin=$hadDx hadAlt=$hadLe")
        }
    }

    private fun needsDynamics(): Boolean =
        pendingLimiter || pendingBoostDb > 0.01f || pendingBoostDb < -0.01f

    // Limiter(inUse, enabled, linkGroup, attackMs, releaseMs, ratio, thresholdDb, postGainDb)
    private fun buildLimiter(): DynamicsProcessing.Limiter =
        DynamicsProcessing.Limiter(
            true,
            true,
            0,
            LIMITER_ATTACK_MS,
            LIMITER_RELEASE_MS,
            LIMITER_RATIO,
            pendingLimiterThresholdDb,
            LIMITER_POST_GAIN_DB,
        )

    private fun safeSid(): Int =
        runCatching { player.audioSessionId }.getOrDefault(-1)

    private fun fmtDb(v: Float): String {
        val whole = v.toInt()
        val tenths = (((v - whole) * 10f) + 0.5f).toInt().coerceIn(0, 9)
        return if (v < 0 && whole == 0) "-0.$tenths" else "$whole.$tenths"
    }

    private fun applyDynamics() {
        if (android.os.Build.VERSION.SDK_INT < 28 && dynamicsEngine == "builtin") {
            dynLog("skip builtin: SDK ${android.os.Build.VERSION.SDK_INT} < 28")
            return
        }
        if (!needsDynamics()) {
            clearDynamics("neutral")
            dynLog("neutral (no boost/limit) — effect cleared")
            return
        }
        when (dynamicsEngine) {
            "alt" -> applyLoudnessEnhancer()
            else -> applyBuiltinDynamics()
        }
    }

    private fun applyBuiltinDynamics() {
        val existing = dynamics
        if (existing != null) {
            val ok = runCatching {
                if (pendingLimiter != dynamicsLimiterOn) return@runCatching false
                existing.setInputGainAllChannelsTo(pendingBoostDb.coerceIn(-24f, 24f))
                if (pendingLimiter) {
                    existing.setLimiterAllChannelsTo(buildLimiter())
                }
                if (existing.setEnabled(true) != AudioEffect.SUCCESS) {
                    return@runCatching false
                }
                true
            }.getOrElse { e ->
                dynLog("builtin update error: ${e.message}")
                false
            }
            if (ok) {
                dynLog(
                    "builtin update ok boost=${fmtDb(pendingBoostDb)} " +
                        "limiter=$pendingLimiter thr=${fmtDb(pendingLimiterThresholdDb)} " +
                        "control=${runCatching { existing.hasControl() }.getOrDefault(false)} " +
                        "enabled=${runCatching { existing.getEnabled() }.getOrDefault(false)} " +
                        "gain0=${runCatching { existing.getInputGainByChannelIndex(0) }.getOrDefault(Float.NaN)}",
                )
                return
            }
            clearDynamics("builtin-recreate")
        }
        createBuiltinDynamics()
    }

    private fun createBuiltinDynamics() {
        val sid = safeSid()
        if (sid <= 0) {
            dynLog("builtin SKIP: invalid session sid=$sid")
            return
        }
        // Docs: null cfg → engine default configuration; binds to this session's
        // MediaPlayer/AudioTrack. Do not hand-build a Config here — that is what
        // the engine rejected with "bad parameter value".
        val dpp = runCatching { DynamicsProcessing(0, sid, null) }.getOrElse { e ->
            dynLog("builtin FAIL sid=$sid: ${e.message}")
            return
        }
        val setEn = dpp.setEnabled(true)
        if (setEn != AudioEffect.SUCCESS) {
            dpp.release()
            dynLog("builtin FAIL sid=$sid: setEnabled=$setEn")
            return
        }
        dynamics = dpp
        dynamicsLimiterOn = pendingLimiter
        runCatching {
            dpp.setInputGainAllChannelsTo(pendingBoostDb.coerceIn(-24f, 24f))
            if (pendingLimiter) {
                dpp.setLimiterAllChannelsTo(buildLimiter())
            }
        }.onFailure { dynLog("builtin param apply failed (attached anyway): ${it.message}") }
        val lim = if (pendingLimiter) {
            runCatching {
                val l = dpp.getLimiterByChannelIndex(0)
                "inUse=${l.isInUse} en=${l.isEnabled} thr=${l.threshold} " +
                    "ratio=${l.ratio} post=${l.postGain}"
            }.getOrDefault("limiter-read-fail")
        } else {
            "limiter=off"
        }
        dynLog(
            "builtin OK sid=$sid control=${runCatching { dpp.hasControl() }.getOrDefault(false)} " +
                "enabled=${runCatching { dpp.getEnabled() }.getOrDefault(false)} " +
                "gain0=${runCatching { dpp.getInputGainByChannelIndex(0) }.getOrDefault(Float.NaN)} " +
                "$lim",
        )
    }

    private fun applyLoudnessEnhancer() {
        if (pendingLimiter) {
            dynLog("alt: limiter requested but LoudnessEnhancer has no limiter stage")
        }
        val existing = loudnessEnhancer
        if (existing != null) {
            val ok = runCatching {
                // Target gain is millibels: 100 mB = 1 dB.
                val mb = (pendingBoostDb.coerceIn(-24f, 24f) * 100f).toInt()
                existing.setTargetGain(mb)
                if (existing.setEnabled(true) != AudioEffect.SUCCESS) {
                    return@runCatching false
                }
                true
            }.getOrElse { e ->
                dynLog("alt update error: ${e.message}")
                false
            }
            if (ok) {
                dynLog(
                    "alt update ok targetMB=${(pendingBoostDb * 100f).toInt()} " +
                        "control=${runCatching { existing.hasControl() }.getOrDefault(false)} " +
                        "enabled=${runCatching { existing.getEnabled() }.getOrDefault(false)} " +
                        "gain=${runCatching { existing.getTargetGain() }.getOrDefault(Int.MIN_VALUE)}",
                )
                return
            }
            clearDynamics("alt-recreate")
        }
        createLoudnessEnhancer()
    }

    private fun createLoudnessEnhancer() {
        val sid = safeSid()
        if (sid <= 0) {
            dynLog("alt SKIP: invalid session sid=$sid")
            return
        }
        val attempt = runCatching {
            val le = LoudnessEnhancer(sid)
            val mb = (pendingBoostDb.coerceIn(-24f, 24f) * 100f).toInt()
            le.setTargetGain(mb)
            val setEn = le.setEnabled(true)
            if (setEn != AudioEffect.SUCCESS) {
                le.release()
                return@runCatching Result.failure(IllegalStateException("setEnabled=$setEn"))
            }
            loudnessEnhancer = le
            Result.success(
                "alt OK sid=$sid control=${le.hasControl()} enabled=${le.getEnabled()} " +
                    "targetMB=${le.getTargetGain()} (requested $mb)",
            )
        }.getOrElse { e -> Result.failure(e) }
        attempt.fold(
            onSuccess = { dynLog(it) },
            onFailure = { e -> dynLog("alt FAIL sid=$sid: ${e.message}") },
        )
    }

    // ── Reduction meter (Visualizer peak vs limiter threshold) ────────

    private fun ensureMeter() {
        if (!pendingLimiter) return
        if (dynamicsEngine != "builtin") return
        if (meterJob?.isActive == true) return
        val sid = safeSid()
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
                // mPeak is millibels (100 mB = 1 dB) below full scale.
                val peakDb = m.mPeak / 100f
                if (!pendingLimiter) {
                    _reductionMeter.value = 0f
                    continue
                }
                _reductionMeter.value =
                    (gainReductionDb(peakDb, pendingLimiterThresholdDb, LIMITER_RATIO) /
                        REDUCTION_METER_FULL_DB).coerceIn(0f, 1f)
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
        clearDynamics("stop")
        runCatching {
            player.stop()
            player.reset()
        }
        _snapshot.value = PlayerSnapshot()
        dynLog("stop")
    }

    override fun release() {
        stopPositionPolling()
        stopMeter()
        clearDynamics("release")
        runCatching { player.release() }
        _snapshot.value = PlayerSnapshot()
        dynLog("release")
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

    private companion object {
        const val DYN_TAG = "SlotifyDyn"
    }
}
