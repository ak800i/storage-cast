package com.storagecast.media

import org.junit.Assert.assertEquals
import org.junit.Test

class AvcProfileTest {

    /** Builds a minimal AVC SPS NAL (1-byte header, type 7) carrying the given profile_idc. */
    private fun spsNal(profileIdc: Int): ByteArray = byteArrayOf(
        0x67,                          // NAL header: type = 0x67 & 0x1F = 7 (SPS)
        (profileIdc and 0xFF).toByte(), // profile_idc
        0x00,                          // constraint flags
        0x28                           // level_idc
    )

    private val startCode4 = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    private val startCode3 = byteArrayOf(0x00, 0x00, 0x01)

    @Test
    fun mapsHigh10ToMediaCodecConstant() {
        // H.264 profile_idc 110 (High 10) → AVCProfileHigh10 (16) → "High 10" downstream.
        val csd = startCode4 + spsNal(110)
        assertEquals(16, AvcProfile.profileFromCsd(csd))
    }

    @Test
    fun mapsHighToMediaCodecConstant() {
        val csd = startCode4 + spsNal(100)
        assertEquals(8, AvcProfile.profileFromCsd(csd))
    }

    @Test
    fun mapsBaselineAndMain() {
        assertEquals(1, AvcProfile.profileFromCsd(startCode4 + spsNal(66)))
        assertEquals(2, AvcProfile.profileFromCsd(startCode4 + spsNal(77)))
    }

    @Test
    fun handlesThreeByteStartCode() {
        val csd = startCode3 + spsNal(110)
        assertEquals(16, AvcProfile.profileFromCsd(csd))
    }

    @Test
    fun returnsMinusOneForUnknownProfileIdc() {
        val csd = startCode4 + spsNal(200)
        assertEquals(-1, AvcProfile.profileFromCsd(csd))
    }

    @Test
    fun returnsMinusOneWhenNoSpsPresent() {
        // A PPS NAL (type 8), no SPS.
        val pps = byteArrayOf(0x68, 0x00, 0x00)
        assertEquals(-1, AvcProfile.profileFromCsd(startCode4 + pps))
    }

    @Test
    fun returnsMinusOneForEmptyInput() {
        assertEquals(-1, AvcProfile.profileFromCsd(ByteArray(0)))
    }
}
