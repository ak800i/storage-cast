package com.storagecast.media

/**
 * The complete encoder contract captured once per HLS session and applied verbatim to BOTH the
 * long-lived pipeline encoder and the one-off [HlsSegmentTranscoder], so their SPS/PPS (avcC)
 * match and the published init.mp4 stays authoritative for every segment.
 *
 * [profile], [level] and [encoderName] are resolved on the Android side (they need MediaCodecInfo);
 * they default to "unset" here so the pure derivation is testable. The numeric fields below are the
 * pure, deterministic part shared by both builders.
 */
data class CommittedEncoderConfig(
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val frameRate: Int,
    val iFrameIntervalSec: Int,
    val profile: Int = UNSET,
    val level: Int = UNSET,
    val encoderName: String? = null,
) {
    companion object {
        const val UNSET = -1

        /** Six-second GOP so a boundary IDR lands ~every segment (vs the old 1s all-IDR). */
        const val I_FRAME_INTERVAL_SEC = 6

        /** Pure numeric derivation. Android side fills profile/level/encoderName afterward. */
        fun derive(inW: Int, inH: Int, srcBitrate: Int, srcFps: Int, quality: CastQuality): CommittedEncoderConfig {
            val (maxW, maxH) = quality.maxDimensions()
            val (w, h) = HlsTranscodeMath.outputSize(inW, inH, maxW, maxH)
            return CommittedEncoderConfig(
                width = w,
                height = h,
                bitrate = HlsTranscodeMath.clampBitrate(srcBitrate),
                frameRate = HlsTranscodeMath.clampFrameRate(srcFps.toDouble()),
                iFrameIntervalSec = I_FRAME_INTERVAL_SEC,
            )
        }
    }
}