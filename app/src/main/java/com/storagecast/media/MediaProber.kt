package com.storagecast.media

import android.media.MediaExtractor
import android.media.MediaFormat
import com.storagecast.log.AppLogger
import java.io.DataInputStream
import java.io.File
import java.io.IOException

class MediaProber {

    companion object {
        private const val TAG = "MediaProber"

        private val VIDEO_MIME_PREFIX = "video/"
        private val AUDIO_MIME_PREFIX = "audio/"

        // EBML Element IDs for MKV fallback probing
        private const val MKV_EBML_HEADER = 0x1A45DFA3L
        private const val MKV_SEGMENT = 0x18538067L
        private const val MKV_TRACKS = 0x1654AE6BL
        private const val MKV_TRACK_ENTRY = 0xAEL
        private const val MKV_TRACK_NUMBER = 0xD7L
        private const val MKV_TRACK_TYPE = 0x83L
        private const val MKV_CODEC_ID = 0x86L
        private const val MKV_LANGUAGE = 0x22B59CL
        private const val MKV_AUDIO = 0xE1L
        private const val MKV_SAMPLING_FREQ = 0xB5L
        private const val MKV_CHANNELS = 0x9FL
        private const val MKV_CLUSTER = 0x1F43B675L
        private const val MKV_TRACK_TYPE_AUDIO = 2

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

            // Fallback: some devices' MediaExtractor doesn't report audio tracks
            // for certain codecs (e.g. AC-3 on Xiaomi). Parse MKV at EBML level.
            if (audioTracks.isEmpty() && isMkvContainer(container)) {
                AppLogger.info(TAG, "MediaExtractor found no audio tracks in MKV, trying EBML fallback")
                val ebmlAudioTracks = probeMkvAudioTracks(videoPath)
                if (ebmlAudioTracks.isNotEmpty()) {
                    audioTracks.addAll(ebmlAudioTracks)
                    AppLogger.info(TAG, "EBML fallback found ${ebmlAudioTracks.size} audio track(s)")
                }
            }

            val durationUs = if (videoTracks.isNotEmpty() || audioTracks.isNotEmpty()) {
                val firstTrackIndex = videoTracks.firstOrNull()?.trackIndex
                    ?: audioTracks.firstOrNull()?.trackIndex ?: 0
                try {
                    val format = extractor.getTrackFormat(firstTrackIndex)
                    format.getLongSafe(MediaFormat.KEY_DURATION, 0L)
                } catch (e: Exception) { 0L }
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
        var profile = format.getIntSafe(MediaFormat.KEY_PROFILE, -1)
        val level = format.getIntSafe(MediaFormat.KEY_LEVEL, -1)

        // MediaExtractor often omits KEY_PROFILE for HEVC/AVC in MKV; recover it from the
        // SPS so 10-bit (HEVC Main 10 / AVC High 10) content is still detected for transcode.
        if (profile < 0) {
            when (mime) {
                "video/hevc" -> csdBytes(format, 0)?.let { csd ->
                    val idc = HevcProfile.profileIdcFromCsd(csd)
                    if (idc >= 0) {
                        profile = idc
                        AppLogger.info(TAG, "HEVC profile recovered from SPS: general_profile_idc=$idc")
                    }
                }
                "video/avc" -> csdBytes(format, 0)?.let { csd ->
                    val p = AvcProfile.profileFromCsd(csd)
                    if (p >= 0) {
                        profile = p
                        AppLogger.info(TAG, "AVC profile recovered from SPS: profile=$p")
                    }
                }
            }
        }

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

    /** Reads a `csd-N` codec-config buffer into a byte array without disturbing the format. */
    private fun csdBytes(format: MediaFormat, index: Int): ByteArray? {
        if (!format.containsKey("csd-$index")) return null
        return try {
            val buf = format.getByteBuffer("csd-$index")?.duplicate() ?: return null
            ByteArray(buf.remaining()).also { buf.get(it) }
        } catch (e: Exception) {
            AppLogger.warn(TAG, "Failed to read csd-$index: ${e.message}")
            null
        }
    }

    private fun extractAudioTrack(index: Int, format: MediaFormat, mime: String): AudioTrackInfo {
        val sampleRate = format.getIntSafe(MediaFormat.KEY_SAMPLE_RATE, 0)
        val channelCount = format.getIntSafe(MediaFormat.KEY_CHANNEL_COUNT, 0)
        val bitrate = format.getIntSafe(MediaFormat.KEY_BIT_RATE, 0)
        val language = format.getStringSafe(MediaFormat.KEY_LANGUAGE, AudioTrackInfo.LANGUAGE_UNDETERMINED)

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

    // ──── MKV EBML Fallback Probing ────

    private fun isMkvContainer(container: String): Boolean {
        return container.contains("MKV", ignoreCase = true) ||
               container.contains("Matroska", ignoreCase = true) ||
               container.contains("WebM", ignoreCase = true)
    }

    /**
     * Probes audio tracks from an MKV file by parsing the EBML structure directly.
     * Used as a fallback when MediaExtractor doesn't report audio tracks.
     */
    private fun probeMkvAudioTracks(videoPath: String): List<AudioTrackInfo> {
        val file = File(videoPath)
        if (!file.exists()) return emptyList()

        return try {
            DataInputStream(file.inputStream().buffered(65536)).use { input ->
                // Read and skip EBML header
                val (headerId, _) = readEbmlElementId(input)
                if (headerId != MKV_EBML_HEADER) return emptyList()
                val headerSize = readEbmlElementSize(input)
                skipEbmlBytes(input, headerSize)

                // Read Segment
                val (segId, _) = readEbmlElementId(input)
                if (segId != MKV_SEGMENT) return emptyList()
                val segmentSize = readEbmlElementSize(input)

                // Search for Tracks element within Segment
                val segmentEnd = if (segmentSize < 0) Long.MAX_VALUE else segmentSize
                var bytesRead = 0L

                while (bytesRead < segmentEnd) {
                    val (elemId, idBytes) = try {
                        readEbmlElementId(input)
                    } catch (e: Exception) { break }
                    bytesRead += idBytes

                    val elemSize = readEbmlElementSize(input)
                    bytesRead += ebmlSizeLength(elemSize)

                    if (elemId == MKV_TRACKS) {
                        val tracksData = ByteArray(elemSize.toInt())
                        input.readFully(tracksData)
                        return parseMkvTrackEntries(tracksData)
                    } else if (elemId == MKV_CLUSTER) {
                        break // Reached data, Tracks not found
                    } else {
                        skipEbmlBytes(input, elemSize)
                        bytesRead += elemSize
                    }
                }

                emptyList()
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "EBML fallback probe failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseMkvTrackEntries(tracksData: ByteArray): List<AudioTrackInfo> {
        val audioTracks = mutableListOf<AudioTrackInfo>()
        val input = DataInputStream(tracksData.inputStream())
        var pos = 0

        while (pos < tracksData.size) {
            val (elemId, idBytes) = try {
                readEbmlElementId(input)
            } catch (e: Exception) { break }
            pos += idBytes

            val elemSize = try {
                readEbmlElementSize(input)
            } catch (e: Exception) { break }
            pos += ebmlSizeLength(elemSize)

            if (elemId == MKV_TRACK_ENTRY) {
                val entryData = ByteArray(elemSize.toInt())
                input.readFully(entryData)
                pos += elemSize.toInt()

                val trackInfo = parseMkvAudioEntry(entryData)
                if (trackInfo != null) {
                    audioTracks.add(trackInfo)
                }
            } else {
                skipEbmlBytes(input, elemSize)
                pos += elemSize.toInt()
            }
        }

        return audioTracks
    }

    private fun parseMkvAudioEntry(entryData: ByteArray): AudioTrackInfo? {
        val input = DataInputStream(entryData.inputStream())
        var pos = 0

        var trackNumber = 0
        var trackType = 0
        var codecId = ""
        var language = AudioTrackInfo.LANGUAGE_UNDETERMINED
        var sampleRate = 0
        var channelCount = 0

        while (pos < entryData.size) {
            val (elemId, idBytes) = try {
                readEbmlElementId(input)
            } catch (e: Exception) { break }
            pos += idBytes

            val elemSize = try {
                readEbmlElementSize(input)
            } catch (e: Exception) { break }
            pos += ebmlSizeLength(elemSize)

            when (elemId) {
                MKV_TRACK_NUMBER -> {
                    trackNumber = readEbmlUint(input, elemSize.toInt())
                    pos += elemSize.toInt()
                }
                MKV_TRACK_TYPE -> {
                    trackType = readEbmlUint(input, elemSize.toInt())
                    pos += elemSize.toInt()
                }
                MKV_CODEC_ID -> {
                    val bytes = ByteArray(elemSize.toInt())
                    input.readFully(bytes)
                    codecId = String(bytes, Charsets.US_ASCII).trimEnd('\u0000')
                    pos += elemSize.toInt()
                }
                MKV_LANGUAGE -> {
                    val bytes = ByteArray(elemSize.toInt())
                    input.readFully(bytes)
                    language = String(bytes, Charsets.US_ASCII).trimEnd('\u0000')
                    pos += elemSize.toInt()
                }
                MKV_AUDIO -> {
                    val audioData = ByteArray(elemSize.toInt())
                    input.readFully(audioData)
                    val (sr, ch) = parseMkvAudioSettings(audioData)
                    sampleRate = sr
                    channelCount = ch
                    pos += elemSize.toInt()
                }
                else -> {
                    skipEbmlBytes(input, elemSize)
                    pos += elemSize.toInt()
                }
            }
        }

        if (trackType != MKV_TRACK_TYPE_AUDIO) return null

        val mime = mkvCodecIdToMime(codecId)
        val codec = CODEC_NAMES[mime] ?: codecId

        return AudioTrackInfo(
            // trackIndex = mkvTrackNumber - 1 to match the convention in
            // VideoDetailActivity.startStreamingMkvFilterAndCast which does trackIndex + 1
            // to recover the MKV track number for MkvTrackFilter.
            trackIndex = trackNumber - 1,
            codec = codec,
            mime = mime,
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitrate = 0,
            language = if (language.isEmpty()) AudioTrackInfo.LANGUAGE_UNDETERMINED else language
        )
    }

    private fun parseMkvAudioSettings(data: ByteArray): Pair<Int, Int> {
        val input = DataInputStream(data.inputStream())
        var pos = 0
        var sampleRate = 0
        var channels = 0

        while (pos < data.size) {
            val (elemId, idBytes) = try {
                readEbmlElementId(input)
            } catch (e: Exception) { break }
            pos += idBytes

            val elemSize = try {
                readEbmlElementSize(input)
            } catch (e: Exception) { break }
            pos += ebmlSizeLength(elemSize)

            when (elemId) {
                MKV_SAMPLING_FREQ -> {
                    sampleRate = readEbmlFloat(input, elemSize.toInt()).toInt()
                    pos += elemSize.toInt()
                }
                MKV_CHANNELS -> {
                    channels = readEbmlUint(input, elemSize.toInt())
                    pos += elemSize.toInt()
                }
                else -> {
                    skipEbmlBytes(input, elemSize)
                    pos += elemSize.toInt()
                }
            }
        }

        return Pair(sampleRate, channels)
    }

    private fun mkvCodecIdToMime(codecId: String): String {
        return when {
            codecId == "A_AC3" -> "audio/ac3"
            codecId == "A_EAC3" || codecId.startsWith("A_EAC3/") -> "audio/eac3"
            codecId.startsWith("A_AAC") -> "audio/mp4a-latm"
            codecId == "A_MPEG/L3" -> "audio/mpeg"
            codecId == "A_MPEG/L2" -> "audio/mpeg"
            codecId == "A_VORBIS" -> "audio/vorbis"
            codecId == "A_OPUS" -> "audio/opus"
            codecId == "A_FLAC" -> "audio/flac"
            codecId.startsWith("A_DTS") -> "audio/x-dts"
            codecId == "A_TRUEHD" || codecId == "A_MLP" -> "audio/true-hd"
            codecId == "A_AC4" -> "audio/ac4"
            codecId.startsWith("A_PCM") -> "audio/raw"
            codecId == "A_MS/ACM" -> "audio/x-ms-wma"
            else -> "audio/$codecId"
        }
    }

    // ──── EBML I/O Utilities ────

    private fun readEbmlElementId(input: DataInputStream): Pair<Long, Int> {
        val first = input.readUnsignedByte()
        val numBytes = when {
            first and 0x80 != 0 -> 1
            first and 0x40 != 0 -> 2
            first and 0x20 != 0 -> 3
            first and 0x10 != 0 -> 4
            else -> throw IOException("Invalid EBML element ID: 0x${first.toString(16)}")
        }
        var value = first.toLong()
        for (i in 1 until numBytes) {
            value = (value shl 8) or input.readUnsignedByte().toLong()
        }
        return Pair(value, numBytes)
    }

    private fun readEbmlElementSize(input: DataInputStream): Long {
        val first = input.readUnsignedByte()
        val numBytes: Int
        val mask: Int
        when {
            first and 0x80 != 0 -> { numBytes = 1; mask = 0x7F }
            first and 0x40 != 0 -> { numBytes = 2; mask = 0x3F }
            first and 0x20 != 0 -> { numBytes = 3; mask = 0x1F }
            first and 0x10 != 0 -> { numBytes = 4; mask = 0x0F }
            first and 0x08 != 0 -> { numBytes = 5; mask = 0x07 }
            first and 0x04 != 0 -> { numBytes = 6; mask = 0x03 }
            first and 0x02 != 0 -> { numBytes = 7; mask = 0x01 }
            first and 0x01 != 0 -> { numBytes = 8; mask = 0x00 }
            else -> throw IOException("Invalid EBML size: 0x${first.toString(16)}")
        }
        var value = (first and mask).toLong()
        var allOnes = (first and mask) == mask
        for (i in 1 until numBytes) {
            val b = input.readUnsignedByte()
            value = (value shl 8) or b.toLong()
            if (b != 0xFF) allOnes = false
        }
        return if (allOnes) -1 else value
    }

    private fun ebmlSizeLength(size: Long): Int {
        if (size < 0) return 8
        return when {
            size < 0x7FL -> 1
            size < 0x3FFFL -> 2
            size < 0x1FFFFFL -> 3
            size < 0x0FFFFFFFL -> 4
            size < 0x07FFFFFFFFL -> 5
            size < 0x03FFFFFFFFFFL -> 6
            size < 0x01FFFFFFFFFFFFL -> 7
            else -> 8
        }
    }

    private fun skipEbmlBytes(input: DataInputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skipBytes(minOf(remaining, Int.MAX_VALUE.toLong()).toInt()).toLong()
            if (skipped <= 0) {
                if (input.read() < 0) throw IOException("Unexpected EOF")
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun readEbmlUint(input: DataInputStream, size: Int): Int {
        var value = 0L
        for (i in 0 until size) {
            value = (value shl 8) or input.readUnsignedByte().toLong()
        }
        return value.toInt()
    }

    private fun readEbmlFloat(input: DataInputStream, size: Int): Double {
        return when (size) {
            4 -> java.lang.Float.intBitsToFloat(input.readInt()).toDouble()
            8 -> java.lang.Double.longBitsToDouble(input.readLong())
            else -> {
                skipEbmlBytes(input, size.toLong())
                0.0
            }
        }
    }
}
