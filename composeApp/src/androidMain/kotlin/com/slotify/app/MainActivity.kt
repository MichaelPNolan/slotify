package com.slotify.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.slotify.app.data.androidAppContext
import com.slotify.app.data.androidBaseDir
import com.slotify.app.platform.jsonFilePicker
import com.slotify.app.platform.setClipboardContext
import com.slotify.app.spotify.JsonFileTokenStorage
import com.slotify.app.state.AppState
import com.slotify.app.state.createSpotifyPort
import com.slotify.app.ui.App
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result surfaced by scanner errors */ }

    /** Pending SAF pick for playlist JSON (content:// URI). */
    private var pendingJsonPick: CompletableDeferred<String?>? = null

    private val jsonPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            pendingJsonPick?.complete(uri?.toString())
            pendingJsonPick = null
        }

    private var appState: AppState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // App-private storage for settings/playlists/library index.
        androidBaseDir = filesDir.absolutePath
        androidAppContext = applicationContext
        setClipboardContext(this)

        jsonFilePicker = { awaitPlaylistJsonPick() }

        requestAudioPermissionIfMissing()

        setContent {
            val state = rememberAppState()
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
        pendingJsonPick?.complete(null)
        pendingJsonPick = null
        appState?.release()
        appState = null
        jsonFilePicker = null
        androidAppContext = null
        super.onDestroy()
    }

    private fun rememberAppState(): AppState {
        // Called from composition — reuse the single instance created once.
        return appState ?: run {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val spotify = createSpotifyPort(JsonFileTokenStorage(filesDir.absolutePath), scope)
            AppState(spotify = spotify, scope = scope).also { appState = it }
        }
    }

    /**
     * Block (off the UI thread) until the user picks a JSON file.
     * Returns `content://…` or null on cancel.
     */
    private fun awaitPlaylistJsonPick(): String? {
        val deferred = CompletableDeferred<String?>()
        runOnUiThread {
            pendingJsonPick = deferred
            jsonPicker.launch(arrayOf("application/json", "text/plain", "text/*", "*/*"))
        }
        return runBlocking {
            withContext(Dispatchers.Default) {
                // Wait indefinitely for user; cancel path completes with null.
                deferred.await()
            }
        }
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
