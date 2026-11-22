package com.slotify.app.platform

import android.content.ClipboardManager
import android.content.Context
import android.content.ClipboardManager.OnPrimaryClipChangedListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Android needs a Context for the clipboard. UI code calls [setClipboardContext]
 * once (from LocalContext) before reading.
 */
@Volatile
private var appContextRef: WeakReference<Context>? = null

fun setClipboardContext(context: Context) {
    appContextRef = WeakReference(context.applicationContext)
}

actual suspend fun readClipboardText(): String? = withContext(Dispatchers.Main) {
    val ctx = appContextRef?.get() ?: return@withContext null
    runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(ctx)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
