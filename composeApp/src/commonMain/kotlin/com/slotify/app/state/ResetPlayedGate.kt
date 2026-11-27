package com.slotify.app.state

/**
 * Whether the “Reset all played already?” dialog should open for a pick.
 * - Never when the user already answered “Just play this” this session
 *   ([askSuppressed] stays true until a real reset clears the played set).
 * - Never when there is no file or the file was not played yet.
 */
fun shouldAskResetPlayed(
    file: String?,
    played: Set<String>,
    askSuppressed: Boolean,
): Boolean {
    if (askSuppressed) return false
    if (file.isNullOrEmpty()) return false
    return file in played
}
