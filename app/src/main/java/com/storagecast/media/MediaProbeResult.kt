package com.storagecast.media

data class MediaProbeResult(
    val containerFormat: String,
    val videoTracks: List<VideoTrackInfo>,
    val audioTracks: List<AudioTrackInfo>,
    val durationMs: Long,
    val fileSize: Long,
    /**
     * Whether the platform's MediaExtractor can demux the audio. False when the audio was
     * only recovered via the EBML fallback (e.g. AC-3/E-AC-3 in MKV on Xiaomi), meaning the
     * transcoder cannot read/decode it — so the audio can only reach the receiver via direct
     * play.
     */
    val audioPlatformDemuxable: Boolean = true
) {
    val primaryVideo: VideoTrackInfo? get() = videoTracks.firstOrNull()
    val primaryAudio: AudioTrackInfo? get() = audioTracks.firstOrNull()
}

data class VideoTrackInfo(
    val trackIndex: Int,
    val codec: String,
    val mime: String,
    val width: Int,
    val height: Int,
    val frameRate: Float,
    val bitrate: Int,
    val profile: String,
    val level: String
)

data class AudioTrackInfo(
    val trackIndex: Int,
    val codec: String,
    val mime: String,
    val sampleRate: Int,
    val channelCount: Int,
    val bitrate: Int,
    val language: String
) {
    companion object {
        const val LANGUAGE_UNDETERMINED = "und"
    }
}
