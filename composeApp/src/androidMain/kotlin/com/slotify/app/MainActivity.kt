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
import com.slotify.app.platform.OpenDocumentInMusic
import com.slotify.app.platform.SafMusic
import com.slotify.app.platform.audioFilePicker
import com.slotify.app.platform.hasSharedMusicWriteAccess
import com.slotify.app.platform.jsonFilePicker
import com.slotify.app.platform.launchStorageAccessRequest
import com.slotify.app.platform.resolvePickedAudioToPath
import com.slotify.app.platform.setClipboardContext
import com.slotify.app.platform.storageAccessRequestor
import com.slotify.app.spotify.JsonFileTokenStorage
import com.slotify.app.state.AppState
import com.slotify.app.state.createSpotifyPort
import com.slotify.app.ui.App
import com.slotify.app.youtube.initYoutubeRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result surfaced by scanner errors */ }

    /** WRITE_EXTERNAL_STORAGE on API ≤ 28 (legacy File writes). */
    private val writePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                android.util.Log.w(
                    "SlotifyStorage",
                    "WRITE_EXTERNAL_STORAGE denied — music folder File writes will fail",
                )
            }
        }

    private var pendingJsonPick: CompletableDeferred<String?>? = null
    private var pendingAudioPick: CompletableDeferred<String?>? = null
    private var pendingTreePick: CompletableDeferred<Uri?>? = null

    private val jsonPicker =
        registerForActivityResult(OpenDocumentInMusic()) { uri: Uri? ->
            pendingJsonPick?.complete(uri?.toString())
            pendingJsonPick = null
        }

    private val audioPicker =
        registerForActivityResult(OpenDocumentInMusic()) { uri: Uri? ->
            pendingAudioPick?.complete(uri?.toString())
            pendingAudioPick = null
        }

    /** Input = EXTRA_INITIAL_URI (Music tree when known). */
    private val treePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            pendingTreePick?.complete(uri)
            pendingTreePick = null
        }

    private var appState: AppState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidBaseDir = filesDir.absolutePath
        androidAppContext = applicationContext
        setClipboardContext(this)

        jsonFilePicker = { awaitPlaylistJsonPick() }
        audioFilePicker = { awaitAudioFilePick() }
        // YouTube download / promote need File write under the music folder.
        storageAccessRequestor = {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= 30) {
                    launchStorageAccessRequest(applicationContext)
                } else if (!hasSharedMusicWriteAccess()) {
                    writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }

        initYoutubeRuntime(applicationContext)
        requestAudioPermissionIfMissing()
        // One-shot: if music folder isn’t writable yet, open All files access.
        if (!hasSharedMusicWriteAccess()) {
            storageAccessRequestor?.invoke()
        }

        setContent {
            val state = rememberAppState()
            App(state = state)
        }
    }

    override fun onDestroy() {
        pendingJsonPick?.complete(null)
        pendingJsonPick = null
        pendingAudioPick?.complete(null)
        pendingAudioPick = null
        pendingTreePick?.complete(null)
        pendingTreePick = null
        appState?.release()
        appState = null
        jsonFilePicker = null
        audioFilePicker = null
        storageAccessRequestor = null
        androidAppContext = null
        super.onDestroy()
    }

    private fun rememberAppState(): AppState {
        return appState ?: run {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val spotify = createSpotifyPort(JsonFileTokenStorage(filesDir.absolutePath), scope)
            AppState(spotify = spotify, scope = scope).also { appState = it }
        }
    }

    /** Opens inside the Music tree when a grant already exists. */
    private fun awaitPlaylistJsonPick(): String? {
        val deferred = CompletableDeferred<String?>()
        runOnUiThread {
            pendingJsonPick = deferred
            jsonPicker.launch(arrayOf("application/json", "text/plain", "text/*", "*/*"))
        }
        return runBlocking {
            withContext(Dispatchers.Default) { deferred.await() }
        }
    }

    /**
     * 1. Ensure persisted Music tree (one-time folder grant).
     * 2. Open document picker with EXTRA_INITIAL_URI = that tree.
     * 3. Persist read access; resolve to a path or keep `content://`.
     */
    private fun awaitAudioFilePick(): String? {
        ensureMusicTreeAccess() ?: return null

        val deferred = CompletableDeferred<String?>()
        runOnUiThread {
            pendingAudioPick = deferred
            audioPicker.launch(arrayOf("audio/*", "*/*"))
        }
        val uriString = runBlocking {
            withContext(Dispatchers.Default) { deferred.await() }
        } ?: return null

        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
        if (uri != null) SafMusic.takeReadPermission(uri)

        return runBlocking {
            withContext(Dispatchers.IO) {
                runCatching { resolvePickedAudioToPath(uriString) }.getOrNull()
            }
        }
    }

    /** Returns tree URI with persisted read, or null if the user cancels. */
    private fun ensureMusicTreeAccess(): Uri? {
        SafMusic.savedTree()?.let { return it }

        val deferred = CompletableDeferred<Uri?>()
        runOnUiThread {
            pendingTreePick = deferred
            treePicker.launch(SafMusic.musicTreeHint())
        }
        val tree = runBlocking {
            withContext(Dispatchers.Default) { deferred.await() }
        } ?: return null
        SafMusic.saveTree(tree)
        return tree
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
