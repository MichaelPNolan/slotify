package com.slotify.app.platform

/**
 * Open a native file picker filtered to accepted audio extensions.
 * Returns the absolute path, or null when the user cancels.
 */
expect fun pickAudioFile(initialDir: String? = null): String?
