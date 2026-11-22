package com.slotify.app.platform

/**
 * Read plain text from the system clipboard (playlist URL Paste button).
 * Returns null when the clipboard is empty or holds a non-text flavor.
 */
expect suspend fun readClipboardText(): String?
