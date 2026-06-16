package com.storagecast.media

/**
 * Extracts the H.264 (AVC) profile from a `csd-0` codec-config blob (SPS/PPS in
 * Annex-B form), for the same reason as [HevcProfile]: `MediaExtractor` often omits
 * `MediaFormat.KEY_PROFILE` for AVC inside MKV, and the compatibility gate needs to
 * tell 10-bit (High 10) from 8-bit content.
 *
 * The AVC SPS carries the H.264 *standard* `profile_idc` (Baseline=66, High=100,
 * High 10=110, …) at `sps[1]` (the SPS NAL header is a single byte, type 7). That is a
 * different number space from [android.media.MediaCodecInfo.CodecProfileLevel], so this
 * maps it to the MediaCodecInfo constants that `MediaProber.formatProfile` expects.
 */
object AvcProfile {

    private const val NAL_TYPE_SPS = 7

    /**
     * Returns the AVC profile as a [android.media.MediaCodecInfo.CodecProfileLevel]
     * value (so it feeds `formatProfile` identically to `KEY_PROFILE`), or -1 if no SPS
     * is found / the profile is unrecognized.
     */
    fun profileFromCsd(csd0: ByteArray): Int {
        for (nal in AnnexBNal.split(csd0)) {
            if (nal.size < 2) continue
            val type = nal[0].toInt() and 0x1F
            if (type == NAL_TYPE_SPS) {
                return when (nal[1].toInt() and 0xFF) {
                    66 -> 1   // AVCProfileBaseline
                    77 -> 2   // AVCProfileMain
                    88 -> 4   // AVCProfileExtended
                    100 -> 8  // AVCProfileHigh
                    110 -> 16 // AVCProfileHigh10
                    122 -> 32 // AVCProfileHigh422
                    244 -> 64 // AVCProfileHigh444
                    else -> -1
                }
            }
        }
        return -1
    }
}
