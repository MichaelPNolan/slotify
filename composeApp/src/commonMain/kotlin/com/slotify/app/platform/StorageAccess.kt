package com.slotify.app.platform

/**
 * Shared-storage write access for the music folder (YouTube downloads, promote).
 *
 * Desktop: always true.
 * Android scoped storage: `READ_MEDIA_AUDIO` is not enough for `File` writes
 * under `/sdcard/Music` — need legacy `WRITE_EXTERNAL_STORAGE` (API ≤ 28) or
 * **All files access** / `MANAGE_EXTERNAL_STORAGE` (API 30+).
 */
expect fun hasSharedMusicWriteAccess(): Boolean

/**
 * Ask the OS for write access when missing.
 * Android may open the system Settings page (All files access) — returns false
 * until the user grants and the app re-checks. Desktop: true.
 */
expect fun requestSharedMusicWriteAccess(): Boolean
