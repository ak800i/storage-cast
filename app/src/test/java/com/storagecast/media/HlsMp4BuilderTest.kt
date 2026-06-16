package com.storagecast.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the hand-written fMP4/CMAF box layout used by the HLS VOD path.
 * These validate the byte-precise structure that cannot be checked without a parser
 * and would otherwise only surface as an opaque receiver failure on a real device.
 */
class HlsMp4BuilderTest {

    /** Minimal ISO-BMFF box-tree reader for assertions. */
    private class Box(val type: String, val start: Int, val size: Int, val payloadStart: Int)

    private fun topLevelBoxes(data: ByteArray): List<Box> {
        val boxes = ArrayList<Box>()
        var i = 0
        while (i + 8 <= data.size) {
            val size = u32(data, i)
            val type = String(data, i + 4, 4, Charsets.US_ASCII)
            if (size < 8 || i + size > data.size) break
            boxes.add(Box(type, i, size, i + 8))
            i += size
        }
        return boxes
    }

    private fun childBoxes(data: ByteArray, parent: Box): List<Box> {
        val boxes = ArrayList<Box>()
        var i = parent.payloadStart
        val end = parent.start + parent.size
        while (i + 8 <= end) {
            val size = u32(data, i)
            val type = String(data, i + 4, 4, Charsets.US_ASCII)
            if (size < 8 || i + size > end) break
            boxes.add(Box(type, i, size, i + 8))
            i += size
        }
        return boxes
    }

    private fun u32(d: ByteArray, o: Int): Int =
        ((d[o].toInt() and 0xFF) shl 24) or ((d[o + 1].toInt() and 0xFF) shl 16) or
            ((d[o + 2].toInt() and 0xFF) shl 8) or (d[o + 3].toInt() and 0xFF)

    private fun find(boxes: List<Box>, type: String): Box? = boxes.firstOrNull { it.type == type }

    private val avcC = byteArrayOf(
        1, 0x64, 0, 0x29, 0xFF.toByte(),
        0xE1.toByte(), 0, 3, 0x67, 0x64, 0x29, // 1 SPS, len 3
        1, 0, 2, 0x68, 0x29 // 1 PPS, len 2
    )
    private val asc = byteArrayOf(0x11, 0x90.toByte()) // AAC-LC 48k stereo

    @Test
    fun initSegment_hasFtypAndMoovWithTracks() {
        val video = HlsMp4Builder.VideoInit(avcC, 1920, 960)
        val audio = HlsMp4Builder.AudioInit(HlsMp4Builder.AudioCodec.AAC, asc, 48000, 2)
        val init = HlsMp4Builder.buildInitSegment(video, audio)

        val top = topLevelBoxes(init)
        assertEquals("ftyp must be first box", "ftyp", top[0].type)
        val moov = find(top, "moov")
        assertNotNull("moov present", moov)

        val moovChildren = childBoxes(init, moov!!)
        assertNotNull("mvhd", find(moovChildren, "mvhd"))
        val traks = moovChildren.filter { it.type == "trak" }
        assertEquals("video + audio traks", 2, traks.size)
        val mvex = find(moovChildren, "mvex")
        assertNotNull("mvex present (declares fragmented)", mvex)
        assertEquals("two trex", 2, childBoxes(init, mvex!!).count { it.type == "trex" })
    }

    @Test
    fun initSegment_eac3UsesEc3Dec3SampleEntry() {
        val video = HlsMp4Builder.VideoInit(avcC, 1920, 960)
        // dec3 payload contents are opaque here; we only assert the box wrapping.
        val dec3 = byteArrayOf(0x07, 0x00, 0x20, 0x0F, 0x00)
        val audio = HlsMp4Builder.AudioInit(HlsMp4Builder.AudioCodec.EAC3, dec3, 48000, 6)
        val init = HlsMp4Builder.buildInitSegment(video, audio)

        // The bytes "ec-3" and "dec3" must appear (the audio sample entry + config box).
        assertTrue("ec-3 sample entry present", containsAscii(init, "ec-3"))
        assertTrue("dec3 config box present", containsAscii(init, "dec3"))
    }

    @Test
    fun mediaSegment_trunDataOffsetPointsAtMdatPayload() {
        val vSamples = listOf(
            HlsMp4Builder.Sample(byteArrayOf(0, 0, 0, 4, 9, 9, 9, 9), 6_000_000L, true),
            HlsMp4Builder.Sample(byteArrayOf(0, 0, 0, 2, 7, 7), 6_033_000L, false)
        )
        val aSamples = listOf(
            HlsMp4Builder.Sample(byteArrayOf(1, 2, 3), 6_000_000L, true),
            HlsMp4Builder.Sample(byteArrayOf(4, 5), 6_021_000L, true)
        )
        val seg = HlsMp4Builder.buildMediaSegment(7, vSamples, aSamples, 33_333L, 21_333L)

        val top = topLevelBoxes(seg)
        val moof = find(top, "moof")!!
        val mdat = find(top, "mdat")!!
        assertTrue("moof precedes mdat", moof.start < mdat.start)

        // The first traf's trun data_offset must equal the offset (from moof start) of
        // the first byte of mdat payload. data_offset sits 60 bytes into the traf.
        val moofChildren = childBoxes(seg, moof)
        val mfhd = find(moofChildren, "mfhd")!!
        val firstTraf = moofChildren.first { it.type == "traf" }
        val dataOffsetPos = firstTraf.start + 60
        val dataOffset = u32(seg, dataOffsetPos)
        val expected = mdat.payloadStart - moof.start
        assertEquals("video trun data_offset → mdat payload", expected, dataOffset)

        // mdat payload length must equal the sum of all sample sizes (v + a).
        val totalSamples = vSamples.sumOf { it.data.size } + aSamples.sumOf { it.data.size }
        assertEquals("mdat size = header + samples", 8 + totalSamples, mdat.size)

        // mfhd sequence number is the supplied one.
        assertEquals("mfhd sequence number", 7, u32(seg, mfhd.payloadStart + 4))
    }

    @Test
    fun mediaSegment_secondTrafDataOffsetAccountsForFirstTrafData() {
        val vSamples = listOf(HlsMp4Builder.Sample(ByteArray(10) { 1 }, 0L, true))
        val aSamples = listOf(HlsMp4Builder.Sample(ByteArray(5) { 2 }, 0L, true))
        val seg = HlsMp4Builder.buildMediaSegment(1, vSamples, aSamples, 33_333L, 21_333L)

        val top = topLevelBoxes(seg)
        val moof = find(top, "moof")!!
        val mdat = find(top, "mdat")!!
        val trafs = childBoxes(seg, moof).filter { it.type == "traf" }
        assertEquals(2, trafs.size)

        val videoDataOffset = u32(seg, trafs[0].start + 60)
        val audioDataOffset = u32(seg, trafs[1].start + 60)
        // Audio data follows the 10 bytes of video data inside mdat.
        assertEquals(videoDataOffset + 10, audioDataOffset)
        // Video data starts at mdat payload.
        assertEquals(mdat.payloadStart - moof.start, videoDataOffset)
    }

    private fun containsAscii(data: ByteArray, s: String): Boolean {
        val needle = s.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
