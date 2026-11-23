package com.slotify.app.platform

/**
 * Android has no shared browser-cookie database Slotify can read.
 * Private playlist imports are desktop-only for now.
 */
actual fun readBrowserSpDcCookie(): String? = null
