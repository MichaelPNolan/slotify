// Written fresh for Slotify (no Parachord equivalent in commonMain — their
// currentTimeMillis lived in shared/platform/Platform.kt).

package com.slotify.app.spotify

/** Current time in milliseconds since epoch (platform actual required). */
expect fun currentTimeMillis(): Long
