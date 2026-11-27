package com.slotify.app.youtube

/**
 * True when [s] means a manual Find-on-YouTube download must stay disabled
 * (index/download in flight). Terminal batch messages must NOT count —
 * otherwise “Batch stopped” leaves every result greyed out forever.
 */
fun youtubeStatusBlocksDownload(s: String?): Boolean {
    if (s == null) return false
    if (s.startsWith("Batch stopped", ignoreCase = true)) return false
    if (s.startsWith("Batch done", ignoreCase = true)) return false
    if (s.startsWith("Done", ignoreCase = true)) return false
    if (s.startsWith("Saved", ignoreCase = true)) return false
    if (s.contains("failed", ignoreCase = true)) return false
    if (s.contains("HTTP", ignoreCase = true)) return false
    if (s.startsWith("Stopping batch", ignoreCase = true)) return false
    return true
}

/**
 * Statuses clearable when the Find dialog opens — terminal/finished lines.
 * Failure lines stay so the user can still read them.
 */
fun youtubeStatusClearableWhenIdle(s: String): Boolean {
    if (s.contains("failed", ignoreCase = true) || s.contains("HTTP", ignoreCase = true)) {
        return false
    }
    if (s.startsWith("Batch stopped", ignoreCase = true) ||
        s.startsWith("Batch done", ignoreCase = true) ||
        s.startsWith("Done", ignoreCase = true) ||
        s.startsWith("Saved", ignoreCase = true) ||
        s.startsWith("Stopping batch", ignoreCase = true)
    ) {
        return true
    }
    // Still mid-work (contains activity verbs) — keep.
    val busy = s.contains("Download") || s.contains("Index") ||
        s.contains("Starting") || s.contains("Updating") ||
        s.contains("Searching") || s.contains("Batch") ||
        s.contains("download") || s.contains("searching")
    return !busy
}
