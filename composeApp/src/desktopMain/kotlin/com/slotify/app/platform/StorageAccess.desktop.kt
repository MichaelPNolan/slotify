package com.slotify.app.platform

/** Desktop: full filesystem access under the user’s music folder. */
actual fun hasSharedMusicWriteAccess(): Boolean = true

actual fun requestSharedMusicWriteAccess(): Boolean = true
