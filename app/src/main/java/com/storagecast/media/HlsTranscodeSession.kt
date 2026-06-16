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
    private val selectedAudioTrack: AudioTrackInfo?,
    /** When true, mux source audio untouched (5.1 passthrough) instead of AAC transcode. */
    private val copyAudio: Boolean = false,
    /** Raw WebVTT bytes for the selected subtitle, or null. Served as an in-manifest rendition. */
    private val subtitleVtt: ByteArray? = null
) {
    companion object {
        private const val TAG = "HlsTranscodeSession"
        const val SEGMENT_DURATION_US = 6_000_000L
        private const val MAX_CACHED_SEGMENTS = 4
    }

    val hasSubtitles: Boolean = subtitleVtt != null && subtitleVtt.isNotEmpty()

    private val transcoder = HlsSegmentTranscoder()
    private val durationUs: Long = (probeResult.durationMs.coerceAtLeast(0)) * 1000
    private val segmentCount: Int =
        if (durationUs <= 0) 1
        else ((durationUs + SEGMENT_DURATION_US - 1) / SEGMENT_DURATION_US).toInt().coerceAtLeast(1)

    /**
     * HLS CODECS attribute audio component. With copy-audio on, reflect the source
     * codec so the receiver sets up the correct decoder (AAC/AC-3/E-AC-3); otherwise
     * we transcode to AAC-LC. Falls back to AAC if the source codec isn't muxable.
     */
    private val audioCodecAttr: String = run {
        val mime = (probeResult.primaryAudio?.mime ?: "").lowercase()
        if (!copyAudio) "mp4a.40.2"
        else when {
            mime.contains("eac3") || mime.contains("ec3") || mime.contains("ec-3") -> "ec-3"
            mime.contains("ac3") || mime.contains("ac-3") -> "ac-3"
            else -> "mp4a.40.2"
        }
    }

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

    /**
     * Master playlist that references the video media playlist plus an in-manifest
     * WebVTT subtitle rendition. Sideloaded text tracks don't reliably follow the HLS
     * media timeline on the Cast receiver (subtitles desync on seek); an in-manifest
     * rendition shares the video timeline so cues stay aligned through seeks.
     */
    fun masterPlaylist(basePath: String): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:7\n")
        if (hasSubtitles) {
            sb.append("#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"Subtitles\",")
            sb.append("DEFAULT=YES,AUTOSELECT=YES,FORCED=NO,LANGUAGE=\"en\",URI=\"")
            sb.append(basePath).append("/subs.m3u8\"\n")
            sb.append("#EXT-X-STREAM-INF:BANDWIDTH=8000000,CODECS=\"avc1.640029,").append(audioCodecAttr).append("\",SUBTITLES=\"subs\"\n")
        } else {
            sb.append("#EXT-X-STREAM-INF:BANDWIDTH=8000000,CODECS=\"avc1.640029,").append(audioCodecAttr).append("\"\n")
        }
        sb.append(basePath).append("/playlist.m3u8\n")
        return sb.toString()
    }

    /** WebVTT subtitle media playlist: a single segment covering the whole VOD. */
    fun subtitlePlaylist(basePath: String): String {
        val totalSecs = (durationUs.coerceAtLeast(1)).toDouble() / 1_000_000.0
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:7\n")
        sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n")
        sb.append("#EXT-X-TARGETDURATION:").append(Math.ceil(totalSecs).toInt()).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n")
        sb.append("#EXTINF:").append(String.format(java.util.Locale.US, "%.3f", totalSecs)).append(",\n")
        sb.append(basePath).append("/subs.vtt\n")
        sb.append("#EXT-X-ENDLIST\n")
        return sb.toString()
    }

    /**
     * The WebVTT body with an `X-TIMESTAMP-MAP` header so the receiver maps cue local
     * time directly onto the (0-based, absolute-source) media timeline. MPEGTS:0 +
     * LOCAL:0 is an identity mapping, so a cue at time T shows at media time T.
     *
     * The `X-TIMESTAMP-MAP` line is injected immediately after the existing `WEBVTT`
     * signature so it stays inside the header block (before the first blank line),
     * preserving any other header metadata or STYLE blocks that follow.
     */
    fun subtitleVttBytes(): ByteArray? {
        val raw = subtitleVtt ?: return null
        var text = String(raw, Charsets.UTF_8)
        // Drop a leading UTF-8 BOM if present.
        if (text.isNotEmpty() && text[0] == '\uFEFF') text = text.substring(1)

        val mapLine = "X-TIMESTAMP-MAP=MPEGTS:0,LOCAL:00:00:00.000"
        // Already has a timestamp map? Leave it untouched.
        if (text.contains("X-TIMESTAMP-MAP")) return text.toByteArray(Charsets.UTF_8)

        val nl = text.indexOf('\n')
        val out = if (nl < 0 || !text.take(6).startsWith("WEBVTT")) {
            // No newline yet, or doesn't start with the signature — write a clean header.
            "WEBVTT\n$mapLine\n\n" + text.removePrefix("WEBVTT")
        } else {
            // Insert the map line right after the WEBVTT signature line.
            val firstLineEnd = nl + 1
            text.substring(0, firstLineEnd) + mapLine + "\n" + text.substring(firstLineEnd)
        }
        return out.toByteArray(Charsets.UTF_8)
    }

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
        val result = transcoder.transcodeRange(inputPath, probeResult, selectedAudioTrack, startUs, endUs, copyAudio)
        // Capture configs from the first segment we build (used for the shared init).
        if (videoInit == null) videoInit = result.video
        if (audioInit == null) audioInit = result.audio
        // All segments share one init segment, so each must decode against the same
        // SPS/PPS. Warn if a later segment's avcC diverges (would corrupt on the receiver).
        val establishedAvcC = videoInit?.avcC
        val segmentAvcC = result.video?.avcC
        if (segmentAvcC != null && !HlsTranscodeMath.avcConfigsMatch(establishedAvcC, segmentAvcC)) {
            AppLogger.warn(TAG, "Segment $index avcC differs from init segment avcC; receiver may corrupt this segment")
        }
        // Likewise the audio codec must match the shared init. passthroughAudioRange decides
        // passthrough-vs-AAC-fallback per segment, so a later segment that fell back to AAC
        // while the init declares AC-3/E-AC-3 (or vice versa) would be decoded with the wrong
        // codec. This shouldn't happen for sync-word-aligned Dolby frames, but warn if it does.
        val establishedAudioCodec = audioInit?.codec
        val segmentAudioCodec = result.audio?.codec
        if (segmentAudioCodec != null && establishedAudioCodec != null &&
            segmentAudioCodec != establishedAudioCodec
        ) {
            AppLogger.warn(TAG, "Segment $index audio codec ($segmentAudioCodec) differs from init ($establishedAudioCodec); receiver may corrupt this segment's audio")
        }
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

    /** Frees cached transcoded segments. Safe to call when the session is no longer cast. */
    fun release() {
        synchronized(lock) {
            segmentCache.clear()
            initSegment = null
        }
    }
}
