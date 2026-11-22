package com.slotify.app.platform

/** Android audio picking remains unused (link file is desktop-first). */
actual fun pickAudioFile(initialDir: String?): String? = null

/**
 * Set by [com.slotify.app.MainActivity] — SAF document picker.
 * Returns a `content://` URI string, or null on cancel.
 */
@Volatile
internal var jsonFilePicker: (() -> String?)? = null

actual fun pickPlaylistJsonFile(): String? = jsonFilePicker?.invoke()
