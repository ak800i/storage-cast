package com.storagecast.media

data class MediaProbeResult(
    val containerFormat: String,
    val videoTracks: List<VideoTrackInfo>,
    val audioTracks: List<AudioTrackInfo>,
    val durationMs: Long,
    val fileSize: Long
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
    val bitrate: Int
)
