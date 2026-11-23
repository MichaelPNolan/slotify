package com.slotify.app.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.slotify.app.data.androidAppContext

/**
 * Set by MainActivity — launches the system grant UI (All files access page
 * or WRITE_EXTERNAL_STORAGE dialog) on the UI thread.
 */
@Volatile
internal var storageAccessRequestor: (() -> Unit)? = null

actual fun hasSharedMusicWriteAccess(): Boolean {
    val ctx = androidAppContext ?: return false
    return if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }
}

actual fun requestSharedMusicWriteAccess(): Boolean {
    if (hasSharedMusicWriteAccess()) return true
    val requestor = storageAccessRequestor ?: return false
    requestor()
    // Grant is async (Settings / system dialog) — caller should re-probe after resume.
    return false
}

/**
 * Open the OS UI for write access:
 * - API 30+: All files access page for this package (`MANAGE_EXTERNAL_STORAGE`)
 * - older: request `WRITE_EXTERNAL_STORAGE`
 */
internal fun launchStorageAccessRequest(context: Context) {
    if (Build.VERSION.SDK_INT >= 30) {
        if (Environment.isExternalStorageManager()) return
        runCatching {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            runCatching {
                val allFiles = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(allFiles)
            }
        }
    } else {
        // Permission dialog is started from the Activity via storageAccessRequestor
        // wired in MainActivity (needs Activity context for RequestPermission).
        runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
