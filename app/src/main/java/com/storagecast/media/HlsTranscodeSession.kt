package com.storagecast.media

import com.storagecast.log.AppLogger

/**
 * Orchestrates an HLS VOD presentation for a single source file transcoded on demand.
 *
 * The playlist is a VOD media playlist (terminated by `#EXT-X-ENDLIST`) so the Cast
 * Web Receiver treats it as seekable VOD rather than a live stream. Each `#EXTINF`
 * segment is an fMP4 media segment produced on demand by [HlsSegmentTranscoder] and
 * cached, with one shared `#EXT-X-MAP` init segment.
 *
 * Segment boundaries are uniform [SEGMENT_DURATION_US] slices of the source timeline;
 * because the video is re-encoded, each segment starts on a fresh IDR, so the receiver
 * can seek to any segment.
 */
class HlsTranscodeSession(
    private val inputPath: String,
    private val probeResult: MediaProbeResult,
    private val selectedAudioTrack: AudioTrackInfo?
) {
    companion object {
        private const val TAG = "HlsTranscodeSession"
        const val SEGMENT_DURATION_US = 6_000_000L
        private const val MAX_CACHED_SEGMENTS = 4
    }

    private val transcoder = HlsSegmentTranscoder()
    private val durationUs: Long = (probeResult.durationMs.coerceAtLeast(0)) * 1000
    private val segmentCount: Int =
        if (durationUs <= 0) 1
        else ((durationUs + SEGMENT_DURATION_US - 1) / SEGMENT_DURATION_US).toInt().coerceAtLeast(1)

    @Volatile
    private var initSegment: ByteArray? = null
    private var videoInit: HlsMp4Builder.VideoInit? = null
    private var audioInit: HlsMp4Builder.AudioInit? = null

    private val segmentCache = object : LinkedHashMap<Int, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean =
            size > MAX_CACHED_SEGMENTS
    }
    private val lock = Any()

    /** Source time range [startUs, endUs) for segment [index]. */
    private fun rangeFor(index: Int): Pair<Long, Long> {
        val start = index * SEGMENT_DURATION_US
        val end = if (index == segmentCount - 1) Long.MAX_VALUE else (index + 1) * SEGMENT_DURATION_US
        return start to end
    }

    /** Builds the VOD media playlist. */
    fun playlist(basePath: String): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:7\n")
        sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n")
        sb.append("#EXT-X-TARGETDURATION:").append((SEGMENT_DURATION_US / 1_000_000)).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n")
        sb.append("#EXT-X-MAP:URI=\"").append(basePath).append("/init.mp4\"\n")
        for (i in 0 until segmentCount) {
            val segUs = if (i == segmentCount - 1 && durationUs > 0) {
                durationUs - i * SEGMENT_DURATION_US
            } else SEGMENT_DURATION_US
            val secs = (segUs.coerceAtLeast(1)).toDouble() / 1_000_000.0
            sb.append("#EXTINF:").append(String.format(java.util.Locale.US, "%.3f", secs)).append(",\n")
            sb.append(basePath).append("/seg").append(i).append(".m4s\n")
        }
        sb.append("#EXT-X-ENDLIST\n")
        return sb.toString()
    }

    /** Returns the init segment, transcoding segment 0 first if needed to learn the codec config. */
    fun initBytes(): ByteArray {
        initSegment?.let { return it }
        synchronized(lock) {
            initSegment?.let { return it }
            // Build segment 0 to capture the codec configs, then the init segment.
            buildAndCacheSegment(0)
            val init = HlsMp4Builder.buildInitSegment(videoInit, audioInit)
            initSegment = init
            AppLogger.info(TAG, "Built init segment (${init.size} bytes, video=${videoInit != null}, audio=${audioInit != null})")
            return init
        }
    }

    /** Returns the fMP4 media segment for [index] (cached). */
    fun segmentBytes(index: Int): ByteArray? {
        if (index < 0 || index >= segmentCount) return null
        synchronized(lock) {
            segmentCache[index]?.let { return it }
            return buildAndCacheSegment(index)
        }
    }

    private fun buildAndCacheSegment(index: Int): ByteArray {
        val (startUs, endUs) = rangeFor(index)
        val result = transcoder.transcodeRange(inputPath, probeResult, selectedAudioTrack, startUs, endUs)
        // Capture configs from the first segment we build (used for the shared init).
        if (videoInit == null) videoInit = result.video
        if (audioInit == null) audioInit = result.audio
        val bytes = HlsMp4Builder.buildMediaSegment(
            sequenceNumber = index + 1,
            videoSamples = result.videoSamples,
            audioSamples = result.audioSamples,
            defaultVideoDurUs = 33_333L,
            defaultAudioDurUs = 21_333L
        )
        segmentCache[index] = bytes
        AppLogger.info(TAG, "Built segment $index (${bytes.size} bytes, v=${result.videoSamples.size} a=${result.audioSamples.size})")
        return bytes
    }
}
