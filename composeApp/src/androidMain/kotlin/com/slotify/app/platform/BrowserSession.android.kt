package com.slotify.app.platform

/**
 * Android has no shared browser-cookie database Slotify can read.
 * Users paste `sp_dc` (or use OAuth) in Settings instead.
 */
actual fun readBrowserSpDcCookie(): String? = null
