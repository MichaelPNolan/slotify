package com.slotify.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import com.slotify.app.data.androidBaseDir
import com.slotify.app.platform.setClipboardContext
import com.slotify.app.spotify.JsonFileTokenStorage
import com.slotify.app.state.AppState
import com.slotify.app.state.createSpotifyPort
import com.slotify.app.ui.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result surfaced by scanner errors */ }

    private var appState: AppState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // App-private storage for settings/playlists/library index.
        androidBaseDir = filesDir.absolutePath
        setClipboardContext(this)

        requestAudioPermissionIfMissing()

        setContent {
            val state = remember {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val spotify = createSpotifyPort(JsonFileTokenStorage(filesDir.absolutePath), scope)
                AppState(spotify = spotify, scope = scope).also { appState = it }
            }
            App(
                state = state,
                pkceAwait = { _ ->
                    // Android PKCE loopback arrives in a later pass; cookie login works today.
                    throw IllegalStateException("Browser sign-in not available on Android yet — use the cookie method")
                },
            )
        }
    }

    override fun onDestroy() {
        appState?.release()
        appState = null
        super.onDestroy()
    }

    private fun requestAudioPermissionIfMissing() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(permission)
        }
    }
}
