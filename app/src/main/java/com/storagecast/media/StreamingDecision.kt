package com.storagecast.media

/**
 * Decides, from a probed source, how to get it onto the Cast receiver with the least
 * processing: direct play when both streams are supported, otherwise transcode only what's
 * needed. Pure (Android-free) so it is unit-tested.
 *
 * Routing reflects two on-device-verified receiver constraints (Default Media Receiver):
 *  - Dolby (AC-3 / E-AC-3) plays via direct play and the progressive live stream, but the
 *    receiver REJECTS it in HLS fMP4 ("Invalid Request"). So Dolby that must accompany a
 *    transcoded video stays on the live path (seek-by-restart) to keep 5.1; everything else
 *    transcodes over seekable HLS VOD.
 *  - The SDK exposes no codec-capability list, so [decide] uses a conservative baseline; the
 *    caller layers reactive learning on top (escalate + remember when a direct play errors).
 */
object StreamingDecision {

    enum class Path { DIRECT, HLS, LIVE }

    /**
     * Capability learned about a specific receiver at runtime. The Cast SDK exposes no
     * codec list, so [unsupportedDirect] starts empty and grows reactively: when a direct
     * play errors on the receiver, the caller records the source's MIME(s) here so future
     * casts of those codecs skip straight to transcoding.
     */
    data class ReceiverHints(
        /** Lower-case MIME types this receiver has failed to direct-play. */
        val unsupportedDirect: Set<String> = emptySet()
    )

    data class Plan(
        val path: Path,
        /** Whether the video stream is re-encoded (false only for DIRECT). */
        val transcodeVideo: Boolean,
        /** For HLS/LIVE: pass the source audio through (true) vs transcode to AAC (false). */
        val copyAudio: Boolean,
        val reason: String
    )

    /** 8-bit video codecs the receiver can typically decode directly. */
    private val DIRECT_VIDEO = setOf(
        "video/avc", "video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "video/av01"
    )

    /** Audio the receiver can play directly (includes Dolby via passthrough). */
    private val DIRECT_AUDIO = setOf(
        "audio/mp4a-latm", "audio/mpeg", "audio/vorbis", "audio/opus", "audio/flac",
        "audio/ac3", "audio/eac3"
    )

    /** Audio that can be muxed into HLS fMP4 and accepted by the receiver (NOT Dolby). */
    private val HLS_FRIENDLY_AUDIO = setOf("audio/mp4a-latm", "audio/mpeg")

    /**
     * @param forceTranscode user/advanced override: never direct-play (always transcode).
     * @param preferHls user/advanced override: prefer seekable HLS even for Dolby audio,
     *   accepting an audio transcode to AAC (loses 5.1) instead of falling back to live.
     * @param hints learned per-receiver capability; codecs in [ReceiverHints.unsupportedDirect]
     *   are treated as not directly playable (forces transcode) even if in the optimistic baseline.
     */
    fun decide(
        probe: MediaProbeResult,
        forceTranscode: Boolean = false,
        preferHls: Boolean = false,
        hints: ReceiverHints = ReceiverHints()
    ): Plan {
        val v = probe.primaryVideo
        val a = probe.primaryAudio
        val vMime = (v?.mime ?: "").lowercase()
        val aMime = (a?.mime ?: "").lowercase()

        val tenBit = v != null && (v.profile == "Main 10" || v.profile == "High 10")
        val videoSupported = v == null ||
            (vMime in DIRECT_VIDEO && !tenBit && vMime !in hints.unsupportedDirect)
        val audioSupported = a == null ||
            (aMime in DIRECT_AUDIO && aMime !in hints.unsupportedDirect)
        val audioHlsFriendly = a == null || aMime in HLS_FRIENDLY_AUDIO
        val audioDolby = aMime.contains("ac3") || aMime.contains("ac-3") ||
            aMime.contains("eac3") || aMime.contains("ec3")

        if (!forceTranscode && videoSupported && audioSupported) {
            return Plan(
                Path.DIRECT, transcodeVideo = false, copyAudio = true,
                reason = "receiver supports both streams; direct play"
            )
        }

        // Dolby audio that must accompany a transcoded video: HLS would reject the codec.
        // By default keep it on the live path to preserve 5.1; the preferHls override trades
        // 5.1 for native seeking by transcoding the audio to AAC over HLS instead.
        if (audioDolby && !videoSupported && !preferHls) {
            return Plan(
                Path.LIVE, transcodeVideo = true, copyAudio = true,
                reason = "incompatible video + Dolby audio: live preserves 5.1 (receiver rejects $aMime over HLS)"
            )
        }

        // Seekable HLS for everything else. The HLS engine always re-encodes video; audio is
        // passed through when HLS-friendly, otherwise transcoded to AAC.
        return Plan(
            Path.HLS, transcodeVideo = true, copyAudio = audioHlsFriendly,
            reason = "HLS transcode (audio=${if (audioHlsFriendly) "copy" else "AAC"})"
        )
    }
}
