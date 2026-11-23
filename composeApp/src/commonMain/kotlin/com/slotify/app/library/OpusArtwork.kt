package com.slotify.app.library

import java.io.File
import java.util.Base64

/**
 * Raw embedded picture (e.g. PNG/JPEG bytes) from an Ogg Opus file's
 * METADATA_BLOCK_PICTURE comment. Needed because the project's jaudiotagger
 * build has no opus reader at all (spec S4). Returns null for artless,
 * unreadable, or foreign files — callers must never substitute a generated
 * image.
 */
fun readOpusEmbeddedPicture(path: String): ByteArray? = runCatching {
    val file = File(path)
    if (!file.isFile) return null
    // OpusTags lives in the first Ogg pages (RFC 7845), but the comment header
    // can be huge — a maxres cover is ~200 KB base64 and lyric videos embed
    // full lyrics — so allow up to 16 MB of header before giving up.
    val head = file.inputStream().use { input ->
        val buf = ByteArray(16 * 1024 * 1024)
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) break
            offset += n
        }
        buf.copyOf(offset)
    }
    parseOpusPicture(head)
}.getOrNull()

/**
 * Walks the Ogg packet stream (not page positions — real muxers place
 * `OpusHead`/`OpusTags` anywhere: both on page 0, spanning pages, etc.) and
 * decodes the picture from packet 1 (`OpusTags`). Packet boundaries follow
 * the lacing segments: a segment of 255 continues onto the next page.
 */
internal fun parseOpusPicture(data: ByteArray): ByteArray? {
    var offset = 0
    var packetNumber = 0
    val current = ArrayList<Byte>(8192)
    // No page-count cap: comment headers with big art + lyrics span dozens of
    // pages (real files exceed 64 easily). Termination is data-bounded — every
    // iteration consumes one page, and offsets only move forward.
    while (offset + 27 <= data.size) {
        if (!data.regionMatchesAscii(offset, "OggS")) return null
        val segCount = data[offset + 26].toInt() and 0xFF
        val table = offset + 27
        if (table + segCount > data.size) return null
        var payloadLen = 0
        for (i in 0 until segCount) payloadLen += data[table + i].toInt() and 0xFF
        val payload = table + segCount
        if (payload + payloadLen > data.size) return null

        var pos = payload
        for (i in 0 until segCount) {
            val len = data[table + i].toInt() and 0xFF
            for (j in 0 until len) current.add(data[pos + j])
            pos += len
            if (len < 255) {
                // Packet complete — header packets must come first, in order.
                when (packetNumber) {
                    0 -> if (!regionMatchesAscii(current, 0, "OpusHead")) return null
                    1 -> {
                        if (!regionMatchesAscii(current, 0, "OpusTags")) return null
                        return pictureFromCommentPacket(current)
                    }
                }
                packetNumber++
                current.clear()
            }
        }
        offset = payload + payloadLen
    }
    return null
}

/** OpusTags packet → picture bytes from the first METADATA_BLOCK_PICTURE comment. */
private fun pictureFromCommentPacket(packet: List<Byte>): ByteArray? {
    if (packet.size < 16) return null
    if (!regionMatchesAscii(packet, 0, "OpusTags")) return null
    var i = 8
    fun u32le(): Int? {
        if (i + 4 > packet.size) return null
        var v = 0
        repeat(4) { v = v or ((packet[i + it].toInt() and 0xFF) shl (8 * it)) }
        i += 4
        return v
    }
    // RFC 7845: OpusTags = magic + vendor string + comment count + comments.
    val vendorLen = u32le() ?: return null
    if (vendorLen < 0 || i + vendorLen > packet.size) return null
    i += vendorLen
    val count = u32le() ?: return null
    repeat(count) {
        val len = u32le() ?: return null
        if (len < 0 || i + len > packet.size) return null
        val entry = String(packet.subList(i, i + len).toByteArray(), Charsets.UTF_8)
        i += len
        if (entry.startsWith("METADATA_BLOCK_PICTURE=", ignoreCase = true)) {
            val base64 = entry.substringAfter('=')
            val block = runCatching { Base64.getDecoder().decode(base64.trim()) }.getOrNull() ?: return null
            return pictureDataFromFlacBlock(block)
        }
    }
    return null
}

/** FLAC METADATA_BLOCK_PICTURE payload: skip the header fields, keep image data. */
private fun pictureDataFromFlacBlock(b: ByteArray): ByteArray? {
    var i = 0
    fun u32be(): Long? {
        if (i + 4 > b.size) return null
        var v = 0L
        repeat(4) { v = (v shl 8) or (b[i + it].toLong() and 0xFF) }
        i += 4
        return v
    }
    u32be() ?: return null                       // picture type
    val mimeLen = u32be() ?: return null         // MIME type length
    i += mimeLen.toInt()
    if (i > b.size) return null
    val descLen = u32be() ?: return null         // description length
    i += descLen.toInt()
    if (i > b.size) return null
    repeat(4) { u32be() ?: return null }         // width, height, depth, colors
    val dataLen = u32be() ?: return null         // image data length
    if (dataLen <= 0 || i + dataLen > b.size) return null
    return b.copyOfRange(i, i + dataLen.toInt())
}

private fun ByteArray.regionMatchesAscii(offset: Int, ascii: String): Boolean {
    if (offset + ascii.length > size) return false
    for (j in ascii.indices) if (this[offset + j] != ascii[j].code.toByte()) return false
    return true
}

private fun regionMatchesAscii(bytes: List<Byte>, offset: Int, ascii: String): Boolean {
    if (offset + ascii.length > bytes.size) return false
    for (j in ascii.indices) if (bytes[offset + j] != ascii[j].code.toByte()) return false
    return true
}
