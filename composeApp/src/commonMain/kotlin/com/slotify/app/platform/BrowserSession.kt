package com.slotify.app.platform

/**
 * Find the `sp_dc` session cookie for open.spotify.com in a local desktop
 * browser profile the user is already logged into (Floorp/Firefox family).
 *
 * Returns null when no plaintext cookie is found (browser not installed,
 * not logged in, or cookies encrypted / unreadable). Never throws.
 */
expect fun readBrowserSpDcCookie(): String?
