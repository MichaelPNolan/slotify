package com.slotify.app.library

import java.io.File
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey

actual fun writeAudioMetadata(
    path: String,
    title: String?,
    artist: String?,
    album: String?,
    isrc: String?,
): Boolean {
    val file = File(path)
    if (!file.isFile) return false
    return try {
        val audio = AudioFileIO.read(file)
        val tag = audio.tag ?: audio.createDefaultTag()
        title?.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.TITLE, it) }
        artist?.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.ARTIST, it) }
        album?.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.ALBUM, it) }
        isrc?.takeIf { it.isNotBlank() }?.let { field ->
            runCatching { tag.setField(FieldKey.ISRC, field) }
        }
        audio.commit()
        true
    } catch (_: Exception) {
        false
    }
}
