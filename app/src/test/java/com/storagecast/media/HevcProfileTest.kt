package com.storagecast.media

import org.junit.Assert.assertEquals
import org.junit.Test

class HevcProfileTest {

    /** Builds a minimal HEVC SPS NAL with the given general_profile_idc at byte 3. */
    private fun spsNal(profileIdc: Int): ByteArray = byteArrayOf(
        0x42, 0x01,                 // NAL header: type = (0x42 & 0x7E) >> 1 = 33 (SPS)
        0x00,                       // vps_id|max_sub_layers|nesting
        (profileIdc and 0x1F).toByte(), // profile_space(0)|tier(0)|profile_idc
        0x00, 0x00, 0x00, 0x00      // padding so the NAL is >= 4 bytes
    )

    private val startCode4 = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    private val startCode3 = byteArrayOf(0x00, 0x00, 0x01)

    @Test
    fun parsesMain10FromSps() {
        val csd = startCode4 + spsNal(HevcProfile.PROFILE_MAIN_10)
        assertEquals(2, HevcProfile.profileIdcFromCsd(csd))
    }

    @Test
    fun parsesMainFromSps() {
        val csd = startCode4 + spsNal(HevcProfile.PROFILE_MAIN)
        assertEquals(1, HevcProfile.profileIdcFromCsd(csd))
    }

    @Test
    fun handlesThreeByteStartCode() {
        val csd = startCode3 + spsNal(HevcProfile.PROFILE_MAIN_10)
        assertEquals(2, HevcProfile.profileIdcFromCsd(csd))
    }

    @Test
    fun skipsVpsAndReadsSps() {
        // VPS (type 32) then SPS (type 33). Must skip the VPS and read the SPS.
        val vps = byteArrayOf(0x40, 0x01, 0x0C, 0x01, 0x00, 0x00) // type = (0x40 & 0x7E)>>1 = 32
        val csd = startCode4 + vps + startCode4 + spsNal(HevcProfile.PROFILE_MAIN_10)
        assertEquals(2, HevcProfile.profileIdcFromCsd(csd))
    }

    @Test
    fun returnsMinusOneWhenNoSpsPresent() {
        val vps = byteArrayOf(0x40, 0x01, 0x0C, 0x01, 0x00, 0x00) // VPS only
        val csd = startCode4 + vps
        assertEquals(-1, HevcProfile.profileIdcFromCsd(csd))
    }

    @Test
    fun returnsMinusOneForEmptyInput() {
        assertEquals(-1, HevcProfile.profileIdcFromCsd(ByteArray(0)))
    }

    @Test
    fun parsesRangeExtensionProfileIdc() {
        // general_profile_idc = 4 (e.g. Main Still / RExt range) is returned as-is.
        val csd = startCode4 + spsNal(4)
        assertEquals(4, HevcProfile.profileIdcFromCsd(csd))
    }
}
