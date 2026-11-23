package com.slotify.app.platform

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.slotify.app.data.androidAppContext
import com.slotify.app.data.sanitizeSlotifyBaseName
import java.io.File

/** Set by MainActivity — SAF document picker for audio. */
@Volatile
internal var audioFilePicker: (() -> String?)? = null

/**
 * Open a system audio picker (after ensuring Music tree access).
 * Returns a **real filesystem path** when resolvable, otherwise a persisted
 * `content://` URI string (player/scanner accept both). Null on cancel.
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
 * Resolve a picked document to a playable identifier.
 *
 * Order (user guidance — prefer SAF identity over brittle path concat):
 * 1. Already a real path / `file://`
 * 2. `primary:` / secondary volume document ID → absolute path when the file exists
 * 3. MediaStore `DATA` column when present
 * 4. **Keep the `content://` URI** (persist permission in MainActivity) —
 *    do not force-copy large files into Slotify just to get a path.
 */
internal fun resolvePickedAudioToPath(uriString: String): String? {
    if (uriString.isBlank()) return null
    if (!uriString.startsWith("content://")) {
        return pathIfReadableFile(uriString)
    }
    resolveContentToFilePath(uriString)?.let { return it }
    // Unresolvable path — store the URI itself (see AndroidAudioPlayer / scanner).
    return uriString
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

/**
 * Last-resort copy into the managed Slotify dir — only when the caller
 * explicitly needs a real file (e.g. tag rewrite). Link file no longer uses this.
 */
internal fun copyContentUriIntoSlotifyDir(uriString: String): String? {
    val ctx = androidAppContext ?: return null
    val uri = Uri.parse(uriString)
    val displayName = safDisplayName(uriString) ?: "linked audio"
    val ext = displayName.substringAfterLast('.', "").lowercase()
    val base = sanitizeSlotifyBaseName(displayName.substringBeforeLast('.'))
    val dir = File(com.slotify.app.data.defaultSlotifyMusicDir())
    if (!dir.isDirectory && !dir.mkdirs()) return null
    val dest = File(dir, if (ext.isBlank()) base else "$base.$ext")
    return runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        dest.absolutePath
    }.getOrNull()
}
