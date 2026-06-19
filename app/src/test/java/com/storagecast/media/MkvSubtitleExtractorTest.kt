package com.storagecast.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MkvSubtitleExtractorTest {

    // EBML element IDs (must match MkvSubtitleExtractor).
    private val EBML_HEADER = 0x1A45DFA3L
    private val SEGMENT = 0x18538067L
    private val INFO = 0x1549A966L
    private val TIMECODE_SCALE = 0x2AD7B1L
    private val TRACKS = 0x1654AE6BL
    private val TRACK_ENTRY = 0xAEL
    private val TRACK_NUMBER = 0xD7L
    private val TRACK_TYPE = 0x83L
    private val CODEC_ID = 0x86L
    private val LANGUAGE = 0x22B59CL
    private val CLUSTER = 0x1F43B675L
    private val CLUSTER_TIMECODE = 0xE7L
    private val SIMPLE_BLOCK = 0xA3L
    private val BLOCK_GROUP = 0xA0L
    private val BLOCK = 0xA1L
    private val BLOCK_DURATION = 0x9BL

    private fun id(value: Long): ByteArray {
        val len = when {
            value <= 0xFF -> 1; value <= 0xFFFF -> 2; value <= 0xFFFFFF -> 3; else -> 4
        }
        return ByteArray(len) { i -> ((value shr (8 * (len - 1 - i))) and 0xFF).toByte() }
    }

    private fun size(len: Int): ByteArray = when {
        len < 0x7F -> byteArrayOf((0x80 or len).toByte())
        len < 0x3FFF -> byteArrayOf((0x40 or (len shr 8)).toByte(), (len and 0xFF).toByte())
        len < 0x1FFFFF -> byteArrayOf(
            (0x20 or (len shr 16)).toByte(), ((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte()
        )
        else -> byteArrayOf(
            (0x10 or (len shr 24)).toByte(), ((len shr 16) and 0xFF).toByte(),
            ((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte()
        )
    }

    private fun elem(idVal: Long, content: ByteArray): ByteArray =
        id(idVal) + size(content.size) + content

    private fun uint(v: Long, n: Int): ByteArray =
        ByteArray(n) { i -> ((v shr (8 * (n - 1 - i))) and 0xFF).toByte() }

    private fun block(track: Int, relTime: Int, text: String): ByteArray =
        byteArrayOf(
            (0x80 or track).toByte(),
            (relTime shr 8).toByte(), (relTime and 0xFF).toByte(),
            0x00
        ) + text.toByteArray(Charsets.UTF_8)

    private fun buildMkv(): File {
        val info = elem(INFO, elem(TIMECODE_SCALE, uint(1_000_000, 3)))
        val trackEntry = elem(
            TRACK_ENTRY,
            elem(TRACK_NUMBER, uint(1, 1)) +
                elem(TRACK_TYPE, uint(0x11, 1)) +
                elem(CODEC_ID, "S_TEXT/UTF8".toByteArray(Charsets.US_ASCII)) +
                elem(LANGUAGE, "eng".toByteArray(Charsets.US_ASCII))
        )
        val tracks = elem(TRACKS, trackEntry)
        val blockGroup = elem(
            BLOCK_GROUP,
            elem(BLOCK, block(1, 1000, "Hello")) + elem(BLOCK_DURATION, uint(2000, 2))
        )
        val simpleBlock = elem(SIMPLE_BLOCK, block(1, 5000, "World"))
        val cluster = elem(CLUSTER, elem(CLUSTER_TIMECODE, uint(0, 1)) + blockGroup + simpleBlock)
        val segment = elem(SEGMENT, info + tracks + cluster)
        val header = elem(EBML_HEADER, byteArrayOf(0x42, 0x86.toByte(), 0x81.toByte(), 0x01))

        val file = File.createTempFile("subs", ".mkv")
        file.deleteOnExit()
        file.writeBytes(header + segment)
        return file
    }

    @Test
    fun listsTextSubtitleTrack() {
        val tracks = MkvSubtitleExtractor().listTracks(buildMkv())
        assertEquals(1, tracks.size)
        assertEquals(1, tracks[0].trackNumber)
        assertEquals("eng", tracks[0].language)
        assertEquals("S_TEXT/UTF8", tracks[0].codecId)
    }

    @Test
    fun extractsCuesWithBlockDurationEndTime() {
        val cues = MkvSubtitleExtractor().extractCues(buildMkv(), 1)
        assertEquals(2, cues.size)
        // BlockGroup cue: start 1000ms, BlockDuration 2000ms -> end 3000ms.
        assertEquals(1000L, cues[0].startMs)
        assertEquals(3000L, cues[0].endMs)
        assertEquals("Hello", cues[0].text)
        // SimpleBlock cue: start 5000ms, no duration -> end derived (> start).
        assertEquals(5000L, cues[1].startMs)
        assertEquals("World", cues[1].text)
        assertTrue(cues[1].endMs > cues[1].startMs)
    }

    @Test
    fun unknownTrackYieldsNoCues() {
        assertTrue(MkvSubtitleExtractor().extractCues(buildMkv(), 99).isEmpty())
    }
}
