package com.slotify.app.library

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Real-world Opus header layouts. Regression: files whose OpusTags span dozens
 * of pages (maxres cover art + embedded lyrics — common on lyric videos) were
 * rejected by an old 64-page cap and a 512 KB head-read cap, so covers never
 * reached the backdrop. Non-Latin titles were never the problem — the picture
 * bytes are language-independent.
 */
class OpusArtworkLayoutTest {

    // ── byte builders ────────────────────────────────────────────────

    private fun le32(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
    }

    private fun be32(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 24) and 0xFF); out.write((v shr 16) and 0xFF)
        out.write((v shr 8) and 0xFF); out.write(v and 0xFF)
    }

    private fun opusHeadPacket(): ByteArray =
        "OpusHead".toByteArray() + ByteArray(11)

    /** RFC 7845: magic + vendor + comment count + comments (vendor FIRST). */
    private fun opusTagsPacket(pictureBlock: ByteArray?, padding: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("OpusTags".toByteArray())
        le32(out, 6); out.write("vendor".toByteArray())
        val comments = buildList {
            if (padding > 0) add("pad=" + "x".repeat(padding))
            if (pictureBlock != null) {
                add("METADATA_BLOCK_PICTURE=" + java.util.Base64.getEncoder().encodeToString(pictureBlock))
            }
        }
        le32(out, comments.size)
        for (c in comments) {
            val bytes = c.toByteArray(Charsets.UTF_8)
            le32(out, bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun flacPicture(image: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        be32(out, 3)                       // front cover
        val mime = "image/png".toByteArray()
        be32(out, mime.size); out.write(mime)
        be32(out, 0)                       // empty description
        be32(out, 64); be32(out, 64); be32(out, 24); be32(out, 0)
        be32(out, image.size); out.write(image)
        return out.toByteArray()
    }

    private fun lacesFor(size: Int): List<Int> {
        val laces = mutableListOf<Int>()
        var rem = size
        while (rem >= 255) { laces += 255; rem -= 255 }
        laces += rem                       // 0..254 ends the packet
        return laces
    }

    private fun oggPage(seq: Int, laces: List<Int>, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("OggS".toByteArray())
        out.write(0)                        // version
        out.write(0)                        // header type
        repeat(8) { out.write(0) }          // granule
        repeat(4) { out.write(1) }          // serial
        le32(out, seq)
        repeat(4) { out.write(0) }          // crc — parser does not validate
        out.write(laces.size)
        laces.forEach { out.write(it) }
        out.write(payload)
        return out.toByteArray()
    }

    /** Packet pages like real muxers: ≤ 4080 payload bytes per page, all-255 lacing until the end. */
    private fun packetPages(seqStart: Int, packet: ByteArray): ByteArray {
        val laces = lacesFor(packet.size)
        val out = ByteArrayOutputStream()
        var seq = seqStart
        var pos = 0
        var i = 0
        while (i < laces.size) {
            var bytes = 0
            val pageLaces = mutableListOf<Int>()
            while (i < laces.size && pageLaces.size < 255 && (bytes < 4080 || pageLaces.isEmpty())) {
                val l = laces[i]
                if (bytes + l > 4080 && pageLaces.isNotEmpty()) break
                pageLaces += l; bytes += l; i++
            }
            // Non-final pages must be all-255 or the packet would end early.
            if (i < laces.size) {
                check(pageLaces.all { it == 255 }) { "packet would end mid-stream" }
            }
            out.write(oggPage(seq, pageLaces, packet.copyOfRange(pos, pos + bytes)))
            pos += bytes
            seq++
        }
        return out.toByteArray()
    }

    // ── tests ────────────────────────────────────────────────────────

    @Test
    fun bothHeaderPacketsSharePageZero() {
        val image = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)
        val block = flacPicture(image)
        val head = opusHeadPacket()
        val tags = opusTagsPacket(block, padding = 0)
        val page0 = oggPage(0, lacesFor(head.size) + lacesFor(tags.size), head + tags)
        val audio = oggPage(1, listOf(5), ByteArray(5))

        val out = parseOpusPicture(page0 + audio)
        assertContentEquals(image, out)
    }

    @Test
    fun headerSpanningOverSixtyFourPagesIsReadFromFile() {
        // 700 KB header → ~172 pages at 4080 B/page (> old 64-page cap) and a
        // file larger than the old 512 KB read cap.
        val image = ByteArray(1024) { 7 }
        val block = flacPicture(image)
        val tags = opusTagsPacket(block, padding = 700_000)

        val bytes = oggPage(0, lacesFor(opusHeadPacket().size), opusHeadPacket()) +
            packetPages(1, tags)

        val file = File.createTempFile("opus-layout", ".opus")
        try {
            file.writeBytes(bytes)
            val out = readOpusEmbeddedPicture(file.path)
            assertNotNull(out, "large multi-page comment header must yield the picture")
            assertContentEquals(image, out)
        } finally {
            file.delete()
        }
    }

    @Test
    fun headerWithoutPictureReturnsNull() {
        val head = opusHeadPacket()
        val tags = opusTagsPacket(pictureBlock = null, padding = 16)
        val bytes = oggPage(0, lacesFor(head.size) + lacesFor(tags.size), head + tags)
        assertNull(parseOpusPicture(bytes))
    }
}
