package com.slotify.app.platform

/**
 * Open a native file picker filtered to accepted audio extensions.
 * Returns the absolute path, or null when the user cancels.
 *
 * Android: SAF (`content://…` URI, resolved/copied to a real path before return).
 * Desktop: absolute filesystem path.
 */
expect fun pickAudioFile(initialDir: String? = null): String?

/**
 * Pick a playlist JSON file via the system picker.
 * Android: SAF (`content://…` URI string). Desktop: absolute filesystem path.
 * Null when cancelled.
 */
expect fun pickPlaylistJsonFile(): String?
