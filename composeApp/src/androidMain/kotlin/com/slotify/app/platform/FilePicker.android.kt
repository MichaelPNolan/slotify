package com.slotify.app.platform

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.slotify.app.data.androidAppContext
import com.slotify.app.data.defaultSlotifyMusicDir
import com.slotify.app.data.sanitizeSlotifyBaseName
import java.io.File

/** Set by MainActivity — SAF document picker for audio. */
@Volatile
internal var audioFilePicker: (() -> String?)? = null

/**
 * Open a system audio picker. Returns a **real filesystem path** when the
 * pick can be resolved (so `File`/player/tag readers work), or null on cancel.
 *
 * SAF content URIs are resolved via document ID / MediaStore; if that fails
 * the bytes are copied into the managed Slotify music dir.
 */
actual fun pickAudioFile(initialDir: String?): String? = audioFilePicker?.invoke()

/**
 * Set by MainActivity — SAF document picker.
 * Returns a `content://` URI string, or null on cancel.
 */
@Volatile
internal var jsonFilePicker: (() -> String?)? = null

actual fun pickPlaylistJsonFile(): String? = jsonFilePicker?.invoke()

/**
 * Resolve a picked document to a readable absolute path.
 * Order: already a real path → externalstorage primary: docId → MediaStore DATA
 * → copy into [defaultSlotifyMusicDir].
 */
internal fun resolvePickedAudioToPath(uriString: String): String? {
    if (uriString.isBlank()) return null
    if (!uriString.startsWith("content://")) {
        val direct = File(uriString.removePrefix("file://"))
        return direct.takeIf { it.isFile }?.absolutePath
    }
    resolveContentToFilePath(uriString)?.let { return it }
    return copyContentUriIntoSlotifyDir(uriString)
}

private fun resolveContentToFilePath(uriString: String): String? {
    val ctx = androidAppContext ?: return null
    val uri = Uri.parse(uriString)
    // externalstorage documents: primary:Music/Artist - Title.mp3
    runCatching {
        if (DocumentsContract.isDocumentUri(ctx, uri)) {
            val docId = DocumentsContract.getDocumentId(uri)
            if (docId.startsWith("primary:")) {
                val rel = docId.removePrefix("primary:")
                val base = Environment.getExternalStorageDirectory().absolutePath
                val path = File(base, rel).absolutePath
                if (File(path).isFile) return path
            }
            // Secondary volumes: <uuid>:path under /storage/<uuid>/
            val colon = docId.indexOf(':')
            if (colon > 0 && colon < docId.lastIndex) {
                val volume = docId.substring(0, colon)
                val rel = docId.substring(colon + 1)
                val path = File("/storage/$volume", rel).absolutePath
                if (File(path).isFile) return path
            }
        }
    }
    // MediaStore content://media/external/audio/media/…
    runCatching {
        ctx.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DATA),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (idx >= 0) {
                    val path = cursor.getString(idx)
                    if (!path.isNullOrBlank() && File(path).isFile) return path
                }
            }
        }
    }
    return null
}

private fun copyContentUriIntoSlotifyDir(uriString: String): String? {
    val ctx = androidAppContext ?: return null
    val uri = Uri.parse(uriString)
    val displayName = queryDisplayName(ctx, uri) ?: "linked audio"
    val ext = displayName.substringAfterLast('.', "").lowercase()
    val base = sanitizeSlotifyBaseName(displayName.substringBeforeLast('.'))
    val dir = File(defaultSlotifyMusicDir())
    if (!dir.isDirectory && !dir.mkdirs()) return null
    val dest = File(dir, if (ext.isBlank()) base else "$base.$ext")
    return runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        dest.absolutePath
    }.getOrNull()
}

private fun queryDisplayName(ctx: Context, uri: Uri): String? = runCatching {
    ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
        ?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
}.getOrNull()
