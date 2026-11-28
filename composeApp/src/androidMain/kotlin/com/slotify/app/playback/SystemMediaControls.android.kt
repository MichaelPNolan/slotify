package com.slotify.app.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.slotify.app.data.androidAppContext

actual fun createSystemMediaControls(
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onStop: () -> Unit,
): SystemMediaControls {
    val ctx = androidAppContext ?: return NoopSystemMediaControls
    return AndroidSystemMediaControls(ctx, onPlayPause, onNext, onPrevious, onSeekTo, onStop)
}

private object NoopSystemMediaControls : SystemMediaControls {
    override fun update(state: SystemMediaState) {}
    override fun release() {}
}

/**
 * Lock-screen / media-notification controls via a framework [MediaSession]
 * around the app's existing MediaPlayer — playback itself is never touched.
 * Also pauses playback when a Bluetooth output disappears so audio cannot
 * fall through to the phone speakers mid-song.
 */
private class AndroidSystemMediaControls(
    context: Context,
    private val onPlayPause: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onSeekTo: (Long) -> Unit,
    private val onStop: () -> Unit,
) : SystemMediaControls {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val notificationManager =
        appContext.getSystemService(NotificationManager::class.java)

    private var last = SystemMediaState()

    private val session = MediaSession(appContext, "Slotify").apply {
        setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
        )
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                mainHandler.post { if (!last.isPlaying) onPlayPause() }
            }

            override fun onPause() {
                mainHandler.post { if (last.isPlaying) onPlayPause() }
            }

            override fun onSkipToNext() {
                mainHandler.post(onNext)
            }

            override fun onSkipToPrevious() {
                mainHandler.post(onPrevious)
            }

            override fun onSeekTo(posMs: Long) {
                mainHandler.post { onSeekTo(posMs) }
            }

            override fun onStop() {
                mainHandler.post(onStop)
            }
        })
    }

    // Bluetooth disconnect mid-song → pause instead of switching to the speaker.
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val bluetoothGone = removedDevices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            }
            if (bluetoothGone && last.isPlaying) {
                mainHandler.post { onPlayPause() }
            }
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
    }

    override fun update(state: SystemMediaState) {
        mainHandler.post { apply(state) }
    }

    private fun apply(state: SystemMediaState) {
        val old = last
        last = state
        if (state.title.isEmpty()) {
            session.isActive = false
            notificationManager.cancel(NOTIFICATION_ID)
            return
        }
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs)
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_STOP,
                )
                .setState(
                    if (state.isPlaying) PlaybackState.STATE_PLAYING
                    else PlaybackState.STATE_PAUSED,
                    state.positionMs,
                    if (state.isPlaying) 1f else 0f,
                )
                .build(),
        )
        session.setActive(true)
        // Repost the notification only on real changes; position-only updates
        // just refresh PlaybackState (lock-screen seek bar).
        if (old.title != state.title ||
            old.artist != state.artist ||
            old.isPlaying != state.isPlaying
        ) {
            postNotification(state)
        }
    }

    private fun postNotification(state: SystemMediaState) {
        runCatching {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Playback",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val launchIntent =
            appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(appContext, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(appContext.applicationInfo.icon)
            .setContentTitle(state.title)
            .setContentText(state.artist)
            .setContentIntent(contentIntent)
            .setOngoing(state.isPlaying)
            .setStyle(
                Notification.MediaStyle().setMediaSession(session.sessionToken),
            )
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun release() {
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        runCatching { session.release() }
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private companion object {
        const val CHANNEL_ID = "slotify_playback"
        const val NOTIFICATION_ID = 0x5107
    }
}
