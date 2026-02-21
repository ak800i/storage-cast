package com.storagecast.media

import com.storagecast.log.AppLogger

class CastCompatibility {

    companion object {
        private const val TAG = "CastCompat"

        // Codecs supported by Chromecast and Android TV with Cast
        // https://developers.google.com/cast/docs/media
        private val SUPPORTED_VIDEO_MIMES = setOf(
            "video/avc",       // H.264
            "video/hevc",      // H.265
            "video/x-vnd.on2.vp8", // VP8
            "video/x-vnd.on2.vp9", // VP9
            "video/av01"       // AV1
        )

        private val SUPPORTED_AUDIO_MIMES = setOf(
            "audio/mp4a-latm", // AAC
            "audio/mpeg",      // MP3
            "audio/vorbis",    // Vorbis
            "audio/opus",      // Opus
            "audio/flac",      // FLAC
            "audio/ac3",       // AC-3 (Dolby Digital)
            "audio/eac3"       // E-AC-3 (Dolby Digital Plus)
        )

        private val SUPPORTED_CONTAINERS = setOf(
            "mp4", "m4v", "mkv", "webm", "ts", "mts", "m2ts", "3gp"
        )
    }

    data class CompatibilityResult(
        val isFullyCompatible: Boolean,
        val unsupportedVideoCodecs: List<VideoTrackInfo>,
        val unsupportedAudioCodecs: List<AudioTrackInfo>,
        val isContainerSupported: Boolean,
        val summary: String,
        val detailedInfo: String
    )

    fun checkCompatibility(probeResult: MediaProbeResult): CompatibilityResult {
        val unsupportedVideo = probeResult.videoTracks.filter { it.mime !in SUPPORTED_VIDEO_MIMES }
        val unsupportedAudio = probeResult.audioTracks.filter { it.mime !in SUPPORTED_AUDIO_MIMES }

        val containerExt = probeResult.containerFormat
            .substringBefore(" ")
            .substringBefore("(")
            .trim()
            .lowercase()
        val isContainerSupported = containerExt in SUPPORTED_CONTAINERS ||
                SUPPORTED_CONTAINERS.any { probeResult.containerFormat.lowercase().contains(it) }

        val isFullyCompatible = unsupportedVideo.isEmpty() && unsupportedAudio.isEmpty()

        val summary = buildSummary(unsupportedVideo, unsupportedAudio, isContainerSupported)
        val detailedInfo = buildDetailedInfo(probeResult, unsupportedVideo, unsupportedAudio, isContainerSupported)

        val result = CompatibilityResult(
            isFullyCompatible = isFullyCompatible,
            unsupportedVideoCodecs = unsupportedVideo,
            unsupportedAudioCodecs = unsupportedAudio,
            isContainerSupported = isContainerSupported,
            summary = summary,
            detailedInfo = detailedInfo
        )

        AppLogger.info(TAG, "Compatibility check: compatible=$isFullyCompatible, " +
            "unsupportedVideo=${unsupportedVideo.map { it.codec }}, " +
            "unsupportedAudio=${unsupportedAudio.map { it.codec }}")

        return result
    }

    private fun buildSummary(
        unsupportedVideo: List<VideoTrackInfo>,
        unsupportedAudio: List<AudioTrackInfo>,
        isContainerSupported: Boolean
    ): String {
        val issues = mutableListOf<String>()

        if (unsupportedVideo.isNotEmpty()) {
            issues.add("Video: ${unsupportedVideo.joinToString { it.codec }}")
        }
        if (unsupportedAudio.isNotEmpty()) {
            issues.add("Audio: ${unsupportedAudio.joinToString { it.codec }}")
        }
        if (!isContainerSupported) {
            issues.add("Container format may not be supported")
        }

        return if (issues.isEmpty()) {
            "All codecs are compatible with Cast devices"
        } else {
            "Unsupported codecs: ${issues.joinToString("; ")}"
        }
    }

    private fun buildDetailedInfo(
        probeResult: MediaProbeResult,
        unsupportedVideo: List<VideoTrackInfo>,
        unsupportedAudio: List<AudioTrackInfo>,
        isContainerSupported: Boolean
    ): String = buildString {
        appendLine("═══ Media Information ═══")
        appendLine()
        appendLine("Container: ${probeResult.containerFormat}")
        appendLine("File size: ${formatSize(probeResult.fileSize)}")
        if (probeResult.durationMs > 0) {
            appendLine("Duration: ${formatDuration(probeResult.durationMs)}")
        }

        if (probeResult.videoTracks.isNotEmpty()) {
            appendLine()
            appendLine("── Video Tracks ──")
            probeResult.videoTracks.forEach { vt ->
                val supported = vt.mime in SUPPORTED_VIDEO_MIMES
                val status = if (supported) "✓" else "✗ UNSUPPORTED"
                appendLine("  [$status] ${vt.codec}")
                appendLine("    MIME: ${vt.mime}")
                if (vt.width > 0 && vt.height > 0) {
                    appendLine("    Resolution: ${vt.width}×${vt.height}")
                }
                if (vt.frameRate > 0) {
                    appendLine("    Frame rate: ${"%.1f".format(vt.frameRate)} fps")
                }
                if (vt.bitrate > 0) {
                    appendLine("    Bitrate: ${formatBitrate(vt.bitrate)}")
                }
                if (vt.profile != "unknown") {
                    appendLine("    Profile: ${vt.profile}")
                }
                if (vt.level != "unknown") {
                    appendLine("    Level: ${vt.level}")
                }
            }
        }

        if (probeResult.audioTracks.isNotEmpty()) {
            appendLine()
            appendLine("── Audio Tracks ──")
            probeResult.audioTracks.forEach { at ->
                val supported = at.mime in SUPPORTED_AUDIO_MIMES
                val status = if (supported) "✓" else "✗ UNSUPPORTED"
                appendLine("  [$status] ${at.codec}")
                appendLine("    MIME: ${at.mime}")
                if (at.sampleRate > 0) {
                    appendLine("    Sample rate: ${at.sampleRate} Hz")
                }
                if (at.channelCount > 0) {
                    appendLine("    Channels: ${at.channelCount} (${formatChannelLayout(at.channelCount)})")
                }
                if (at.bitrate > 0) {
                    appendLine("    Bitrate: ${formatBitrate(at.bitrate)}")
                }
            }
        }

        if (unsupportedVideo.isNotEmpty() || unsupportedAudio.isNotEmpty()) {
            appendLine()
            appendLine("── Compatibility Issues ──")
            if (unsupportedVideo.isNotEmpty()) {
                appendLine("  Video codec${if (unsupportedVideo.size > 1) "s" else ""} " +
                    "${unsupportedVideo.joinToString { "'${it.codec}' (${it.mime})" }} " +
                    "${if (unsupportedVideo.size > 1) "are" else "is"} not natively supported by Cast devices.")
            }
            if (unsupportedAudio.isNotEmpty()) {
                appendLine("  Audio codec${if (unsupportedAudio.size > 1) "s" else ""} " +
                    "${unsupportedAudio.joinToString { "'${it.codec}' (${it.mime})" }} " +
                    "${if (unsupportedAudio.size > 1) "are" else "is"} not natively supported by Cast devices.")
            }
            if (!isContainerSupported) {
                appendLine("  Container format '${probeResult.containerFormat}' may not be supported.")
            }

            appendLine()
            appendLine("── Options ──")
            appendLine("  • Direct Stream: Send as-is. May work if the")
            appendLine("    device has built-in decoder support.")
            appendLine("  • Transcode: Re-encode to H.264/AAC using")
            appendLine("    hardware acceleration on this device.")
        }
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) {
            "%.1f GB".format(mb / 1024.0)
        } else {
            "%.1f MB".format(mb)
        }
    }

    private fun formatBitrate(bps: Int): String {
        return when {
            bps >= 1_000_000 -> "%.1f Mbps".format(bps / 1_000_000.0)
            bps >= 1_000 -> "%d kbps".format(bps / 1_000)
            else -> "$bps bps"
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    private fun formatChannelLayout(channels: Int): String {
        return when (channels) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1 Surround"
            8 -> "7.1 Surround"
            else -> "$channels channels"
        }
    }
}
