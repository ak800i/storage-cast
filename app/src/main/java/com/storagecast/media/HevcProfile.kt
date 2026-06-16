package com.storagecast.media

/**
 * Extracts the HEVC `general_profile_idc` from a `csd-0` codec-config blob
 * (VPS+SPS+PPS in Annex-B form). Android's `MediaExtractor` frequently omits
 * `MediaFormat.KEY_PROFILE` for HEVC inside MKV, so the compatibility gate falls back
 * to this to tell 10-bit (Main 10) content from 8-bit (Main) — the distinction that
 * decides whether a file must be transcoded for Cast.
 *
 * `general_profile_idc` sits at a fixed position in the SPS RBSP (no Exp-Golomb-coded
 * fields precede it), so it is read directly with no bitstream parsing:
 *   nal[0..1] = 2-byte NAL header, nal[2] = vps_id|max_sub_layers|nesting,
 *   nal[3]    = profile_space(2)|tier_flag(1)|profile_idc(5).
 * Profile-idc values match the [android.media.MediaCodecInfo.CodecProfileLevel] HEVC
 * constants for the common cases (Main=1, Main10=2), so the result feeds
 * `MediaProber.formatProfile` unchanged.
 */
object HevcProfile {

    const val PROFILE_MAIN = 1
    const val PROFILE_MAIN_10 = 2

    private const val NAL_TYPE_SPS = 33

    /** Returns the HEVC `general_profile_idc`, or -1 if no SPS is found. */
    fun profileIdcFromCsd(csd0: ByteArray): Int {
        for (nal in splitAnnexB(csd0)) {
            if (nal.size < 4) continue
            val type = (nal[0].toInt() and 0x7E) shr 1
            if (type == NAL_TYPE_SPS) {
                return nal[3].toInt() and 0x1F
            }
        }
        return -1
    }

    /** Splits an Annex-B byte stream (3- or 4-byte start codes) into NAL unit payloads. */
    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val codePositions = ArrayList<Int>()
        var i = 0
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                codePositions.add(i)
                i += 3
            } else {
                i++
            }
        }
        val nalus = ArrayList<ByteArray>(codePositions.size)
        for (k in codePositions.indices) {
            val start = codePositions[k] + 3
            val end = if (k + 1 < codePositions.size) codePositions[k + 1] else data.size
            if (end - start >= 4) nalus.add(data.copyOfRange(start, end))
        }
        return nalus
    }
}
