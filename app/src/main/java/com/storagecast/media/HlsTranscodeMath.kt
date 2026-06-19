package com.storagecast.media

/**
 * Pure (Android-free) decisions that govern how the source maps onto each HLS
 * segment: output sizing, bit-rate / frame-rate clamping, and the frame-inclusion
 * predicates that make segments tile cleanly over `[startUs, endUs)`.
 *
 * These are the points where an off-by-one causes on-device gaps, duplicated
 * frames at segment boundaries, or a wrong-resolution decode, so they are kept
 * here, separate from the MediaCodec plumbing, and unit-tested.
 */
object HlsTranscodeMath {

    const val MAX_WIDTH = 1920
    const val MAX_HEIGHT = 1080
    const val MAX_VIDEO_BITRATE = 8_000_000
    const val MAX_FRAME_RATE = 30

    /**
     * Output video dimensions: fit within [maxW]x[maxH] preserving aspect ratio,
     * never upscaling, and rounded down to even numbers (H.264 requires even
     * width/height). Returns `width to height`.
     */
    fun outputSize(inW: Int, inH: Int, maxW: Int = MAX_WIDTH, maxH: Int = MAX_HEIGHT): Pair<Int, Int> {
        require(inW > 0 && inH > 0) { "Invalid video dimensions ${inW}x${inH}" }
        val scale = minOf(maxW.toFloat() / inW, maxH.toFloat() / inH, 1f)
        val w = ((inW * scale).toInt() / 2) * 2
        val h = ((inH * scale).toInt() / 2) * 2
        // Guard against degenerate rounding to zero for tiny inputs.
        return maxOf(w, 2) to maxOf(h, 2)
    }

    /** Clamp the encode bit-rate: use the source rate only when it is positive and below the cap. */
    fun clampBitrate(srcBitrate: Int, max: Int = MAX_VIDEO_BITRATE): Int =
        if (srcBitrate in 1 until max) srcBitrate else max

    /** Clamp the encode frame-rate to [max]; fall back to [max] for an unknown/non-positive source rate. */
    fun clampFrameRate(srcFps: Double, max: Int = MAX_FRAME_RATE): Int =
        if (srcFps > 0) srcFps.toInt().coerceAtMost(max) else max

    /**
     * Whether a decoded video frame should be rendered into this segment: it must carry
     * payload, not be a codec-config buffer, and fall inside `[startUs, endUs)`. Frames
     * before [startUs] are decode pre-roll (seek lands on the previous sync sample);
     * frames at or after [endUs] belong to the next segment.
     */
    fun shouldRenderVideoFrame(size: Int, isCodecConfig: Boolean, ptsUs: Long, startUs: Long, endUs: Long): Boolean =
        size > 0 && !isCodecConfig && ptsUs >= startUs && ptsUs < endUs

    /** Whether the video decode for this segment is finished (end-of-stream, or past the segment end). */
    fun isVideoSegmentComplete(endOfStream: Boolean, ptsUs: Long, endUs: Long): Boolean =
        endOfStream || ptsUs >= endUs

    /** Whether to stop reading source audio for this segment (frame is at/after the segment end). */
    fun audioRangeEnded(ptsUs: Long, endUs: Long): Boolean = ptsUs >= endUs

    /** Whether a source audio frame falls within this segment (at/after start; end already checked). */
    fun audioFrameIncluded(ptsUs: Long, startUs: Long): Boolean = ptsUs >= startUs

    /**
     * Whether two `avcC` (AVCDecoderConfigurationRecord) blobs are interchangeable.
     *
     * HLS fMP4 segments share a single init segment, so every independently-encoded
     * segment must decode against the same SPS/PPS. Encoding each segment with an
     * identical output format should yield a byte-identical record; this is the check
     * used to detect (and warn about) a hardware encoder that deviates, since that
     * would otherwise surface as silent corruption on the receiver.
     */
    fun avcConfigsMatch(a: ByteArray?, b: ByteArray?): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else -> a.contentEquals(b)
    }

    /** Floor PTS to its segment index. */
    fun segmentIndexForPts(ptsUs: Long, segDurUs: Long): Int = (ptsUs / segDurUs).toInt()

    /**
     * True when consecutive frames straddle [boundaryUs]: the previous frame is strictly below
     * and the current frame is at/above. The pipeline requests a sync frame before rendering the
     * current (crossing) frame so it becomes the segment's first (IDR) frame.
     */
    fun crossesBoundary(prevPtsUs: Long, ptsUs: Long, boundaryUs: Long): Boolean =
        prevPtsUs < boundaryUs && ptsUs >= boundaryUs

    /**
     * A segment ending at [endUs] may be flushed only once BOTH encoders have produced a sample
     * with PTS >= endUs, so independent codec output latency never truncates boundary audio.
     */
    fun segmentDrained(videoMaxPtsUs: Long, audioMaxPtsUs: Long, endUs: Long): Boolean =
        videoMaxPtsUs >= endUs && audioMaxPtsUs >= endUs
}
