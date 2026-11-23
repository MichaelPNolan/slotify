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
    // OpusTags lives in the first Ogg pages (RFC 7845) — 512 KiB is ample.
    val head = file.inputStream().use { input ->
        val buf = ByteArray(512 * 1024)
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

/** Ogg page walk over [data]; collects the OpusTags packet and decodes its picture. */
internal fun parseOpusPicture(data: ByteArray): ByteArray? {
    var offset = 0
    var pageIndex = 0
    val packet = ArrayList<Byte>(8192)
    var collecting = false
    while (offset + 27 <= data.size && pageIndex <= 64) {
        if (!data.regionMatchesAscii(offset, "OggS")) return null
        val segCount = data[offset + 26].toInt() and 0xFF
        val table = offset + 27
        if (table + segCount > data.size) return null
        var payloadLen = 0
        for (i in 0 until segCount) payloadLen += data[table + i].toInt() and 0xFF
        val payload = table + segCount
        if (payload + payloadLen > data.size) return null

        when (pageIndex) {
            0 -> if (!data.regionMatchesAscii(payload, "OpusHead")) return null
            1 -> {
                if (!data.regionMatchesAscii(payload, "OpusTags")) return null
                collecting = true
                if (appendLeadingPacket(data, table, segCount, payload, packet)) {
                    return pictureFromCommentPacket(packet)
                }
            }
            else -> if (collecting && appendLeadingPacket(data, table, segCount, payload, packet)) {
                return pictureFromCommentPacket(packet)
            }
        }
        offset = payload + payloadLen
        pageIndex++
    }
    return null
}

/**
 * Appends the page's leading packet bytes to [packet] (lacing runs of 255
 * continue a packet onto the next page). True once a segment < 255 ends it.
 */
private fun appendLeadingPacket(
    data: ByteArray,
    table: Int,
    segCount: Int,
    payload: Int,
    packet: ArrayList<Byte>,
): Boolean {
    var pos = payload
    for (i in 0 until segCount) {
        val len = data[table + i].toInt() and 0xFF
        for (j in 0 until len) packet.add(data[pos + j])
        pos += len
        if (len < 255) return true
    }
    return false
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
