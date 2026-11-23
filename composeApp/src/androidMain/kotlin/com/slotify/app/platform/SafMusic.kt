package com.slotify.app.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.slotify.app.data.androidAppContext
import java.io.File

/**
 * Persisted SAF access to the user's Music tree so Link file / JSON pickers
 * can open *inside* Music instead of Recents, and so content URIs stay readable
 * after process death.
 *
 * Preference key stores `tree/primary:Music` (or whatever tree the user chose).
 * Permissions live in the system (`ContentResolver.persistedUriPermissions`).
 */
internal object SafMusic {

    private const val PREFS = "slotify_saf"
    private const val KEY_MUSIC_TREE = "music_tree_uri"

    /** Hint URI for EXTRA_INITIAL_URI — external storage Music folder. */
    fun musicTreeHint(): Uri =
        DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Music",
        )

    fun savedTree(): Uri? {
        val ctx = androidAppContext ?: return null
        val raw = prefs(ctx).getString(KEY_MUSIC_TREE, null) ?: return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        return if (hasPersistedRead(uri)) uri else null
    }

    fun saveTree(uri: Uri) {
        val ctx = androidAppContext ?: return
        prefs(ctx).edit().putString(KEY_MUSIC_TREE, uri.toString()).apply()
        takeReadPermission(uri)
    }

    fun takeReadPermission(uri: Uri) {
        val ctx = androidAppContext ?: return
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    fun hasPersistedRead(uri: Uri): Boolean {
        val ctx = androidAppContext ?: return false
        return runCatching {
            ctx.contentResolver.persistedUriPermissions.any {
                it.isReadPermission && it.uri == uri
            }
        }.getOrDefault(false)
    }

    /** Re-check that [uri] is still readable (tree or single document). */
    fun canRead(uri: Uri): Boolean {
        val ctx = androidAppContext ?: return false
        if (hasPersistedRead(uri)) return true
        return runCatching {
            val first = ctx.contentResolver.openInputStream(uri)?.use { it.read() } ?: -1
            first != -1
        }.getOrDefault(false)
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when [path] is a SAF content URI. */
    fun isContentUri(path: String): Boolean = path.startsWith("content://")
}

/** Display name for a content document, or null. */
internal fun safDisplayName(uriString: String): String? {
    val ctx = androidAppContext ?: return null
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
    return runCatching {
        ctx.contentResolver.query(
            uri,
            arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    }.getOrNull()
}

/** Absolute path for a real file under a filesystem path string, or null. */
internal fun pathIfReadableFile(path: String): String? {
    if (path.isBlank() || SafMusic.isContentUri(path)) return null
    val f = File(path.removePrefix("file://"))
    return f.takeIf { it.isFile }?.absolutePath
}
