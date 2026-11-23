package com.slotify.app.library

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Bounded path → decoded cover cache for the backdrop: the current song plus
 * the most recently used, so back/forward between tracks never re-reads a
 * file. Access-order LinkedHashMap gives LRU eviction at [maxEntries].
 */
class CoverArtCache(private val maxEntries: Int = 6) {

    private val entries = object : LinkedHashMap<String, ImageBitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean =
            size > maxEntries
    }

    operator fun get(path: String): ImageBitmap? = entries[path]

    fun put(path: String, art: ImageBitmap) {
        entries[path] = art
    }

    val size: Int get() = entries.size
}
