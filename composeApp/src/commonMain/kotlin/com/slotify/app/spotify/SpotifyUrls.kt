package com.slotify.app.spotify

/**
 * Extract a Spotify playlist id from a pasted URL, URI, or bare id.
 *
 * Accepts:
 *  - `https://open.spotify.com/playlist/2IVlvNaXDsLD62TVqC7yUA?si=…`
 *  - `http://open.spotify.com/playlist/…` (and any https/http host prefix)
 *  - `spotify:playlist:2IVlvNaXDsLD62TVqC7yUA`
 *  - a bare 22-char base62 playlist id
 *
 * Returns null when the text is not a playlist reference.
 */
fun parseSpotifyPlaylistId(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    spotifyPlaylistUriRegex.find(text)?.let { return it.groupValues[1] }
    spotifyPlaylistUrlRegex.find(text)?.let { return it.groupValues[1] }
    if (bareSpotifyIdRegex.matches(text)) return text
    return null
}

// open.spotify.com/playlist/<id> — id is base62 (A–Z a–z 0–9), typically 22 chars.
// Matched against the whole paste so tracking query strings after the id are ignored.
private val spotifyPlaylistUrlRegex =
    Regex("""https?://open\.spotify\.com/playlist/([A-Za-z0-9]+)""")

private val spotifyPlaylistUriRegex =
    Regex("""spotify:playlist:([A-Za-z0-9]+)""")

private val bareSpotifyIdRegex = Regex("""^[A-Za-z0-9]{22}$""")
