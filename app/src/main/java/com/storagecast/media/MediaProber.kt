package com.storagecast.media

import android.media.MediaExtractor
import android.media.MediaFormat
import com.storagecast.log.AppLogger
import java.io.File

class MediaProber {

    companion object {
        private const val TAG = "MediaProber"

        private val VIDEO_MIME_PREFIX = "video/"
        private val AUDIO_MIME_PREFIX = "audio/"

        private val CODEC_NAMES = mapOf(
            "video/avc" to "H.264 (AVC)",
            "video/hevc" to "H.265 (HEVC)",
            "video/x-vnd.on2.vp8" to "VP8",
            "video/x-vnd.on2.vp9" to "VP9",
            "video/av01" to "AV1",
            "video/mp4v-es" to "MPEG-4 Part 2",
            "video/3gpp" to "H.263",
            "video/mpeg2" to "MPEG-2",
            "video/x-ms-wmv" to "WMV",
            "video/x-flv" to "FLV",
            "audio/mp4a-latm" to "AAC",
            "audio/mpeg" to "MP3",
            "audio/vorbis" to "Vorbis",
            "audio/opus" to "Opus",
            "audio/flac" to "FLAC",
            "audio/ac3" to "AC-3 (Dolby Digital)",
            "audio/eac3" to "E-AC-3 (Dolby Digital Plus)",
            "audio/raw" to "PCM",
            "audio/x-ms-wma" to "WMA",
            "audio/ac4" to "AC-4 (Dolby AC-4)",
            "audio/true-hd" to "Dolby TrueHD",
            "audio/x-dts" to "DTS",
            "audio/x-dts-hd" to "DTS-HD"
        )
    }

    fun probe(videoPath: String): MediaProbeResult? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(videoPath)

            val file = File(videoPath)
            val container = guessContainerFormat(file.extension)

            val videoTracks = mutableListOf<VideoTrackInfo>()
            val audioTracks = mutableListOf<AudioTrackInfo>()

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                if (mime.startsWith(VIDEO_MIME_PREFIX)) {
                    videoTracks.add(extractVideoTrack(i, format, mime))
                } else if (mime.startsWith(AUDIO_MIME_PREFIX)) {
                    audioTracks.add(extractAudioTrack(i, format, mime))
                }
            }

            val durationUs = if (videoTracks.isNotEmpty() || audioTracks.isNotEmpty()) {
                val firstTrackIndex = videoTracks.firstOrNull()?.trackIndex
                    ?: audioTracks.firstOrNull()?.trackIndex ?: 0
                val format = extractor.getTrackFormat(firstTrackIndex)
                format.getLongSafe(MediaFormat.KEY_DURATION, 0L)
            } else 0L

            val result = MediaProbeResult(
                containerFormat = container,
                videoTracks = videoTracks,
                audioTracks = audioTracks,
                durationMs = durationUs / 1000,
                fileSize = file.length()
            )

            AppLogger.info(TAG, "Probed: container=$container, " +
                "video=[${videoTracks.joinToString { "${it.codec} ${it.width}x${it.height}" }}], " +
                "audio=[${audioTracks.joinToString { "${it.codec} ${it.sampleRate}Hz ${it.channelCount}ch" }}]")

            result
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to probe $videoPath: ${e.message}")
            null
        } finally {
            extractor.release()
        }
    }

    private fun extractVideoTrack(index: Int, format: MediaFormat, mime: String): VideoTrackInfo {
        val width = format.getIntSafe(MediaFormat.KEY_WIDTH, 0)
        val height = format.getIntSafe(MediaFormat.KEY_HEIGHT, 0)
        val frameRate = format.getFloatSafe(MediaFormat.KEY_FRAME_RATE, 0f)
        val bitrate = format.getIntSafe(MediaFormat.KEY_BIT_RATE, 0)
        val profile = format.getIntSafe(MediaFormat.KEY_PROFILE, -1)
        val level = format.getIntSafe(MediaFormat.KEY_LEVEL, -1)

        return VideoTrackInfo(
            trackIndex = index,
            codec = CODEC_NAMES[mime] ?: mime,
            mime = mime,
            width = width,
            height = height,
            frameRate = frameRate,
            bitrate = bitrate,
            profile = formatProfile(mime, profile),
            level = formatLevel(mime, level)
        )
    }

    private fun extractAudioTrack(index: Int, format: MediaFormat, mime: String): AudioTrackInfo {
        val sampleRate = format.getIntSafe(MediaFormat.KEY_SAMPLE_RATE, 0)
        val channelCount = format.getIntSafe(MediaFormat.KEY_CHANNEL_COUNT, 0)
        val bitrate = format.getIntSafe(MediaFormat.KEY_BIT_RATE, 0)
        val language = format.getStringSafe(MediaFormat.KEY_LANGUAGE, "und")

        return AudioTrackInfo(
            trackIndex = index,
            codec = CODEC_NAMES[mime] ?: mime,
            mime = mime,
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitrate = bitrate,
            language = language
        )
    }

    private fun formatProfile(mime: String, profile: Int): String {
        if (profile < 0) return "unknown"
        return when (mime) {
            "video/avc" -> when (profile) {
                1 -> "Baseline"
                2 -> "Main"
                8 -> "High"
                16 -> "High 10"
                32 -> "High 4:2:2"
                64 -> "High 4:4:4"
                else -> "profile=$profile"
            }
            "video/hevc" -> when (profile) {
                1 -> "Main"
                2 -> "Main 10"
                else -> "profile=$profile"
            }
            else -> "profile=$profile"
        }
    }

    private fun formatLevel(mime: String, level: Int): String {
        if (level < 0) return "unknown"
        return when (mime) {
            "video/avc" -> {
                val majorLevel = level / 10
                val minorLevel = level % 10
                if (minorLevel > 0) "$majorLevel.$minorLevel" else "$majorLevel"
            }
            else -> "level=$level"
        }
    }

    private fun guessContainerFormat(extension: String): String {
        return when (extension.lowercase()) {
            "mp4", "m4v" -> "MP4 (MPEG-4 Part 14)"
            "mkv" -> "Matroska (MKV)"
            "webm" -> "WebM"
            "avi" -> "AVI"
            "mov" -> "QuickTime (MOV)"
            "ts", "mts", "m2ts" -> "MPEG-TS"
            "flv" -> "Flash Video (FLV)"
            "wmv" -> "Windows Media Video"
            "3gp" -> "3GPP"
            "ogv" -> "Ogg"
            else -> extension.uppercase()
        }
    }

    private fun MediaFormat.getIntSafe(key: String, default: Int): Int {
        return try {
            if (containsKey(key)) getInteger(key) else default
        } catch (e: Exception) { default }
    }

    private fun MediaFormat.getLongSafe(key: String, default: Long): Long {
        return try {
            if (containsKey(key)) getLong(key) else default
        } catch (e: Exception) { default }
    }

    private fun MediaFormat.getFloatSafe(key: String, default: Float): Float {
        return try {
            if (containsKey(key)) getFloat(key) else default
        } catch (e: Exception) { default }
    }

    private fun MediaFormat.getStringSafe(key: String, default: String): String {
        return try {
            if (containsKey(key)) getString(key) ?: default else default
        } catch (e: Exception) { default }
    }
}
