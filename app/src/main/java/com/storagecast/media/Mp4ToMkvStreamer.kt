package com.storagecast.media

import android.media.MediaExtractor
import android.media.MediaFormat
import com.storagecast.log.AppLogger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer

/**
 * Streams selected tracks from an MP4 (or other non-MKV) file as a Matroska (MKV) stream.
 * Uses MediaExtractor to read individual track samples and writes them into an MKV container
 * on-the-fly — zero transcoding, pure container remuxing for streaming.
 *
 * This avoids the need to process the entire file before streaming (as MediaMuxer-based
 * remuxing requires), and supports all audio codecs that MKV can carry (unlike MediaMuxer
 * which only supports AAC in MP4 output).
 */
class Mp4ToMkvStreamer {

    companion object {
        private const val TAG = "Mp4ToMkvStreamer"
        private const val PIPE_BUFFER_SIZE = 2 * 1024 * 1024 // 2 MB

        // ──── EBML Element IDs ────
        private const val EBML_HEADER = 0x1A45DFA3L
        private const val EBML_VERSION = 0x4286L
        private const val EBML_READ_VERSION = 0x42F7L
        private const val EBML_MAX_ID_LENGTH = 0x42F2L
        private const val EBML_MAX_SIZE_LENGTH = 0x42F3L
        private const val DOC_TYPE = 0x4282L
        private const val DOC_TYPE_VERSION = 0x4287L
        private const val DOC_TYPE_READ_VERSION = 0x4285L

        private const val SEGMENT = 0x18538067L
        private const val INFO = 0x1549A966L
        private const val TIMECODE_SCALE = 0x2AD7B1L
        private const val MUXING_APP = 0x4D80L
        private const val WRITING_APP = 0x5741L

        private const val TRACKS = 0x1654AE6BL
        private const val TRACK_ENTRY = 0xAEL
        private const val TRACK_NUMBER = 0xD7L
        private const val TRACK_UID = 0x73C5L
        private const val TRACK_TYPE = 0x83L
        private const val CODEC_ID = 0x86L
        private const val CODEC_PRIVATE = 0x63A2L

        private const val VIDEO = 0xE0L
        private const val PIXEL_WIDTH = 0xB0L
        private const val PIXEL_HEIGHT = 0xBAL

        private const val AUDIO = 0xE1L
        private const val SAMPLING_FREQUENCY = 0xB5L
        private const val CHANNELS = 0x9FL

        private const val CLUSTER = 0x1F43B675L
        private const val CLUSTER_TIMECODE = 0xE7L
        private const val SIMPLE_BLOCK = 0xA3L

        // Track types
        private const val TRACK_TYPE_VIDEO = 1
        private const val TRACK_TYPE_AUDIO = 2

        // Cluster boundary thresholds
        private const val MIN_CLUSTER_DURATION_MS = 500L
        private const val MAX_CLUSTER_DURATION_MS = 5000L

        // MIME to MKV Codec ID mapping
        private val MIME_TO_CODEC_ID = mapOf(
            "video/avc" to "V_MPEG4/ISO/AVC",
            "video/hevc" to "V_MPEGH/ISO/HEVC",
            "video/x-vnd.on2.vp8" to "V_VP8",
            "video/x-vnd.on2.vp9" to "V_VP9",
            "video/av01" to "V_AV1",
            "audio/mp4a-latm" to "A_AAC",
            "audio/ac3" to "A_AC3",
            "audio/eac3" to "A_EAC3",
            "audio/opus" to "A_OPUS",
            "audio/vorbis" to "A_VORBIS",
            "audio/flac" to "A_FLAC",
            "audio/mpeg" to "A_MPEG/L3",
        )
    }

    /**
     * Creates a PipedInputStream that streams the selected tracks from a media file
     * as an MKV container. A background thread reads from MediaExtractor and writes
     * the MKV data to the pipe.
     *
     * @param sourcePath Path to the source media file
     * @param videoTrackIndex MediaExtractor track index for video
     * @param audioTrackIndex MediaExtractor track index for audio
     * @return InputStream that produces valid MKV data
     */
    fun createStream(
        sourcePath: String,
        videoTrackIndex: Int,
        audioTrackIndex: Int
    ): InputStream {
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, PIPE_BUFFER_SIZE)

        Thread {
            try {
                mux(sourcePath, videoTrackIndex, audioTrackIndex, pipedOut)
            } catch (e: IOException) {
                // Pipe broken = reader closed (Cast device disconnected) — expected
                AppLogger.info(TAG, "Stream ended: ${e.message}")
            } catch (e: Exception) {
                AppLogger.error(TAG, "Mux error: ${e.message}")
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }.apply {
            name = "Mp4ToMkvStreamer"
            isDaemon = true
        }.start()

        return pipedIn
    }

    private fun mux(
        sourcePath: String,
        videoTrackIndex: Int,
        audioTrackIndex: Int,
        output: OutputStream
    ) {
        val out = output.buffered(65536)
        val extractor = MediaExtractor()
        extractor.setDataSource(sourcePath)

        try {
            val videoFormat = extractor.getTrackFormat(videoTrackIndex)
            val audioFormat = extractor.getTrackFormat(audioTrackIndex)

            val videoMime = videoFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            val audioMime = audioFormat.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"

            AppLogger.info(TAG, "Starting MP4→MKV stream: video=$videoMime (track $videoTrackIndex), " +
                "audio=$audioMime (track $audioTrackIndex)")

            // Write EBML header
            writeEbmlHeader(out)

            // Write Segment with unknown size (streaming)
            writeElementId(out, SEGMENT)
            writeUnknownSize(out)

            // Write SegmentInformation
            writeSegmentInfo(out)

            // Write Tracks element
            writeTracksElement(out, videoFormat, audioFormat, videoMime, audioMime)

            // Select tracks in extractor
            extractor.selectTrack(videoTrackIndex)
            extractor.selectTrack(audioTrackIndex)

            // Read samples and write clusters
            writeClusters(out, extractor, videoTrackIndex)

            out.flush()
            AppLogger.info(TAG, "MP4→MKV stream complete")
        } finally {
            extractor.release()
        }
    }

    // ──── EBML Header ────

    private fun writeEbmlHeader(out: OutputStream) {
        val content = ByteArrayOutputStream()
        writeUintElement(content, EBML_VERSION, 1)
        writeUintElement(content, EBML_READ_VERSION, 1)
        writeUintElement(content, EBML_MAX_ID_LENGTH, 4)
        writeUintElement(content, EBML_MAX_SIZE_LENGTH, 8)
        writeStringElement(content, DOC_TYPE, "matroska")
        writeUintElement(content, DOC_TYPE_VERSION, 4)
        writeUintElement(content, DOC_TYPE_READ_VERSION, 2)

        writeElementId(out, EBML_HEADER)
        writeElementSize(out, content.size().toLong())
        content.writeTo(out)
    }

    // ──── Segment Info ────

    private fun writeSegmentInfo(out: OutputStream) {
        val content = ByteArrayOutputStream()
        writeUintElement(content, TIMECODE_SCALE, 1_000_000) // 1ms granularity
        writeUtf8Element(content, MUXING_APP, "StorageCast")
        writeUtf8Element(content, WRITING_APP, "StorageCast")

        writeElementId(out, INFO)
        writeElementSize(out, content.size().toLong())
        content.writeTo(out)
    }

    // ──── Tracks ────

    private fun writeTracksElement(
        out: OutputStream,
        videoFormat: MediaFormat,
        audioFormat: MediaFormat,
        videoMime: String,
        audioMime: String
    ) {
        val content = ByteArrayOutputStream()
        writeVideoTrackEntry(content, videoFormat, videoMime)
        writeAudioTrackEntry(content, audioFormat, audioMime)

        writeElementId(out, TRACKS)
        writeElementSize(out, content.size().toLong())
        content.writeTo(out)
    }

    private fun writeVideoTrackEntry(out: OutputStream, format: MediaFormat, mime: String) {
        val entry = ByteArrayOutputStream()

        writeUintElement(entry, TRACK_NUMBER, 1)
        writeUintElement(entry, TRACK_UID, 1)
        writeUintElement(entry, TRACK_TYPE, TRACK_TYPE_VIDEO.toLong())
        writeStringElement(entry, CODEC_ID, MIME_TO_CODEC_ID[mime] ?: "V_MPEG4/ISO/AVC")

        // CodecPrivate
        val codecPrivate = buildVideoCodecPrivate(format, mime)
        if (codecPrivate.isNotEmpty()) {
            writeBinaryElement(entry, CODEC_PRIVATE, codecPrivate)
        }

        // Video element (PixelWidth, PixelHeight)
        val videoContent = ByteArrayOutputStream()
        val width = getIntSafe(format, MediaFormat.KEY_WIDTH, 0)
        val height = getIntSafe(format, MediaFormat.KEY_HEIGHT, 0)
        writeUintElement(videoContent, PIXEL_WIDTH, width.toLong())
        writeUintElement(videoContent, PIXEL_HEIGHT, height.toLong())

        writeElementId(entry, VIDEO)
        writeElementSize(entry, videoContent.size().toLong())
        videoContent.writeTo(entry)

        writeElementId(out, TRACK_ENTRY)
        writeElementSize(out, entry.size().toLong())
        entry.writeTo(out)
    }

    private fun writeAudioTrackEntry(out: OutputStream, format: MediaFormat, mime: String) {
        val entry = ByteArrayOutputStream()

        writeUintElement(entry, TRACK_NUMBER, 2)
        writeUintElement(entry, TRACK_UID, 2)
        writeUintElement(entry, TRACK_TYPE, TRACK_TYPE_AUDIO.toLong())
        writeStringElement(entry, CODEC_ID, MIME_TO_CODEC_ID[mime] ?: "A_AAC")

        // CodecPrivate
        val codecPrivate = buildAudioCodecPrivate(format, mime)
        if (codecPrivate.isNotEmpty()) {
            writeBinaryElement(entry, CODEC_PRIVATE, codecPrivate)
        }

        // Audio element (SamplingFrequency, Channels)
        val audioContent = ByteArrayOutputStream()
        val sampleRate = getIntSafe(format, MediaFormat.KEY_SAMPLE_RATE, 48000)
        val channels = getIntSafe(format, MediaFormat.KEY_CHANNEL_COUNT, 2)
        writeFloat64Element(audioContent, SAMPLING_FREQUENCY, sampleRate.toDouble())
        writeUintElement(audioContent, CHANNELS, channels.toLong())

        writeElementId(entry, AUDIO)
        writeElementSize(entry, audioContent.size().toLong())
        audioContent.writeTo(entry)

        writeElementId(out, TRACK_ENTRY)
        writeElementSize(out, entry.size().toLong())
        entry.writeTo(out)
    }

    // ──── Codec Private Data ────

    private fun buildVideoCodecPrivate(format: MediaFormat, mime: String): ByteArray {
        return when (mime) {
            "video/avc" -> buildAvcConfigRecord(format)
            "video/hevc" -> buildHevcConfigRecord(format)
            else -> getCsdBytes(format, 0) ?: ByteArray(0)
        }
    }

    private fun buildAudioCodecPrivate(format: MediaFormat, mime: String): ByteArray {
        return when (mime) {
            "audio/mp4a-latm", "audio/opus", "audio/flac" -> {
                // AAC/Opus/FLAC: csd-0 is the codec-specific config
                getCsdBytes(format, 0) ?: ByteArray(0)
            }
            "audio/vorbis" -> buildVorbisCodecPrivate(format)
            else -> ByteArray(0) // AC-3, E-AC-3, MP3: no CodecPrivate needed
        }
    }

    /**
     * Builds AVCDecoderConfigurationRecord from MediaFormat's csd-0 (SPS) and csd-1 (PPS)
     * which are in Annex B format (start code + NALU).
     */
    private fun buildAvcConfigRecord(format: MediaFormat): ByteArray {
        val csd0 = format.getByteBuffer("csd-0") ?: return ByteArray(0)
        val csd1 = format.getByteBuffer("csd-1")

        val spsNalus = parseAnnexBNalus(csd0)
        val ppsNalus = if (csd1 != null) parseAnnexBNalus(csd1) else emptyList()

        if (spsNalus.isEmpty()) {
            AppLogger.warn(TAG, "No SPS found in csd-0")
            return ByteArray(0)
        }

        val sps = spsNalus[0]
        if (sps.size < 4) {
            AppLogger.warn(TAG, "SPS too short: ${sps.size} bytes")
            return ByteArray(0)
        }

        val output = ByteArrayOutputStream()
        output.write(1) // configurationVersion
        output.write(sps[1].toInt() and 0xFF) // AVCProfileIndication
        output.write(sps[2].toInt() and 0xFF) // profile_compatibility
        output.write(sps[3].toInt() and 0xFF) // AVCLevelIndication
        output.write(0xFF) // reserved(6) + lengthSizeMinusOne(2) = 3 → 4-byte NAL lengths

        // SPS array
        output.write(0xE0 or spsNalus.size) // reserved(3) + numSPS
        for (nalu in spsNalus) {
            output.write((nalu.size shr 8) and 0xFF)
            output.write(nalu.size and 0xFF)
            output.write(nalu)
        }

        // PPS array
        output.write(ppsNalus.size)
        for (nalu in ppsNalus) {
            output.write((nalu.size shr 8) and 0xFF)
            output.write(nalu.size and 0xFF)
            output.write(nalu)
        }

        AppLogger.info(TAG, "Built AVCDecoderConfigurationRecord: ${output.size()} bytes")
        return output.toByteArray()
    }

    /**
     * Builds HEVCDecoderConfigurationRecord from MediaFormat's csd-0 (VPS+SPS+PPS in Annex B).
     */
    private fun buildHevcConfigRecord(format: MediaFormat): ByteArray {
        val csd0 = format.getByteBuffer("csd-0") ?: return ByteArray(0)
        val allNalus = parseAnnexBNalus(csd0)
        if (allNalus.isEmpty()) return ByteArray(0)

        // Group NALUs by type (VPS=32, SPS=33, PPS=34)
        val naluArrays = mutableMapOf<Int, MutableList<ByteArray>>()
        for (nalu in allNalus) {
            if (nalu.isEmpty()) continue
            val naluType = (nalu[0].toInt() and 0x7E) shr 1
            naluArrays.getOrPut(naluType) { mutableListOf() }.add(nalu)
        }

        val sps = naluArrays[33]?.firstOrNull()

        val output = ByteArrayOutputStream()
        output.write(1) // configurationVersion

        if (sps != null && sps.size >= 15) {
            // Profile/tier/level from SPS RBSP (after 2-byte NAL header)
            // sps[2] = vps_id(4) | max_sub_layers_minus1(3) | temporal_id_nesting(1)
            // sps[3..14] = profile_tier_level()
            output.write(sps[3].toInt() and 0xFF) // general_profile_space|tier_flag|profile_idc
            for (i in 4..7) output.write(sps[i].toInt() and 0xFF) // profile_compatibility_flags
            for (i in 8..13) output.write(sps[i].toInt() and 0xFF) // constraint_indicator_flags
            output.write(sps[14].toInt() and 0xFF) // general_level_idc
        } else {
            for (i in 0..11) output.write(0) // zeroes if SPS unavailable
        }

        output.write(0xF0) // reserved(4) + min_spatial_segmentation_idc high
        output.write(0x00) // min_spatial_segmentation_idc low
        output.write(0xFC) // reserved(6) + parallelismType(2) = 0
        output.write(0xFC) // reserved(6) + chromaFormat(2) = 0
        output.write(0xF8) // reserved(5) + bitDepthLuma(3) = 0
        output.write(0xF8) // reserved(5) + bitDepthChroma(3) = 0
        output.write(0x00) // avgFrameRate high
        output.write(0x00) // avgFrameRate low
        output.write(0x03) // constantFrameRate(2)=0|numTemporalLayers(3)=0|temporalIdNested(1)=0|lengthSizeMinusOne(2)=3

        // NALU arrays (VPS=32, SPS=33, PPS=34)
        val arrayTypes = listOf(32, 33, 34).filter { naluArrays.containsKey(it) }
        output.write(arrayTypes.size)

        for (naluType in arrayTypes) {
            val nalus = naluArrays[naluType]!!
            output.write(naluType and 0x3F) // array_completeness=0|reserved=0|type
            output.write((nalus.size shr 8) and 0xFF)
            output.write(nalus.size and 0xFF)
            for (nalu in nalus) {
                output.write((nalu.size shr 8) and 0xFF)
                output.write(nalu.size and 0xFF)
                output.write(nalu)
            }
        }

        AppLogger.info(TAG, "Built HEVCDecoderConfigurationRecord: ${output.size()} bytes")
        return output.toByteArray()
    }

    /**
     * Builds Vorbis CodecPrivate using Xiph lacing (3 headers packed together).
     */
    private fun buildVorbisCodecPrivate(format: MediaFormat): ByteArray {
        val csd0 = getCsdBytes(format, 0) ?: return ByteArray(0) // Identification header
        val csd1 = getCsdBytes(format, 1) // Comment header
        val csd2 = getCsdBytes(format, 2) // Setup header

        val commentHeader = csd1 ?: return ByteArray(0)
        val setupHeader = csd2 ?: return ByteArray(0)

        val output = ByteArrayOutputStream()
        output.write(2) // num_packets - 1

        // Xiph lacing: encode size of first packet
        var remaining = csd0.size
        while (remaining >= 255) {
            output.write(255)
            remaining -= 255
        }
        output.write(remaining)

        // Xiph lacing: encode size of second packet
        remaining = commentHeader.size
        while (remaining >= 255) {
            output.write(255)
            remaining -= 255
        }
        output.write(remaining)

        // Third packet size is implicit
        output.write(csd0)
        output.write(commentHeader)
        output.write(setupHeader)

        return output.toByteArray()
    }

    // ──── Sample Writing (Clusters + SimpleBlocks) ────

    private fun writeClusters(
        out: OutputStream,
        extractor: MediaExtractor,
        videoTrackIndex: Int
    ) {
        val bufferSize = 1024 * 1024
        val buffer = ByteBuffer.allocate(bufferSize)
        val clusterBuffer = ByteArrayOutputStream()
        var clusterTimecodeMs = -1L
        var samplesWritten = 0L

        while (true) {
            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break

            val sampleTimeUs = extractor.sampleTime
            val sampleTimeMs = sampleTimeUs / 1000
            val sampleFlags = extractor.sampleFlags
            val sampleTrackIndex = extractor.sampleTrackIndex

            // MKV track number: 1 = video, 2 = audio
            val mkvTrackNumber = if (sampleTrackIndex == videoTrackIndex) 1 else 2
            val isKeyframe = (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
            val isVideo = sampleTrackIndex == videoTrackIndex

            // Start new cluster at video keyframes (with minimum duration) or max duration
            val clusterAge = if (clusterTimecodeMs >= 0) sampleTimeMs - clusterTimecodeMs else 0
            val shouldStartNewCluster = clusterTimecodeMs < 0 ||
                (isVideo && isKeyframe && clusterAge >= MIN_CLUSTER_DURATION_MS) ||
                clusterAge > MAX_CLUSTER_DURATION_MS

            if (shouldStartNewCluster) {
                // Flush previous cluster
                if (clusterBuffer.size() > 0) {
                    writeElementId(out, CLUSTER)
                    writeElementSize(out, clusterBuffer.size().toLong())
                    clusterBuffer.writeTo(out)
                    out.flush()
                    clusterBuffer.reset()
                }
                clusterTimecodeMs = sampleTimeMs
                writeUintElement(clusterBuffer, CLUSTER_TIMECODE, clusterTimecodeMs)
            }

            // Write SimpleBlock
            val relativeTimeMs = (sampleTimeMs - clusterTimecodeMs).toInt()
                .coerceIn(-32768, 32767) // int16 range safety
            val blockData = ByteArray(sampleSize)
            buffer.position(0)
            buffer.get(blockData, 0, sampleSize)

            writeSimpleBlock(clusterBuffer, mkvTrackNumber, relativeTimeMs, isKeyframe, blockData)
            samplesWritten++
            extractor.advance()
        }

        // Flush final cluster
        if (clusterBuffer.size() > 0) {
            writeElementId(out, CLUSTER)
            writeElementSize(out, clusterBuffer.size().toLong())
            clusterBuffer.writeTo(out)
        }

        AppLogger.info(TAG, "Wrote $samplesWritten samples in MKV stream")
    }

    /**
     * Writes a SimpleBlock element.
     *
     * SimpleBlock format:
     *   Track Number (EBML vint)
     *   Timecode (int16, relative to cluster, in ms)
     *   Flags (1 byte: bit 7 = keyframe)
     *   Frame data
     */
    private fun writeSimpleBlock(
        out: OutputStream,
        trackNumber: Int,
        relativeTimeMs: Int,
        keyframe: Boolean,
        data: ByteArray
    ) {
        val trackVint = encodeTrackVint(trackNumber)
        val totalSize = trackVint.size + 2 + 1 + data.size // vint + timecode + flags + data

        writeElementId(out, SIMPLE_BLOCK)
        writeElementSize(out, totalSize.toLong())

        // Track number as EBML vint
        out.write(trackVint)

        // Relative timecode (int16, big-endian)
        out.write((relativeTimeMs shr 8) and 0xFF)
        out.write(relativeTimeMs and 0xFF)

        // Flags: bit 7 = keyframe
        out.write(if (keyframe) 0x80 else 0x00)

        // Frame data
        out.write(data)
    }

    // ──── Annex B Parsing ────

    /**
     * Parses NAL units from Annex B byte stream format.
     * Strips start codes (00 00 01 or 00 00 00 01) and returns raw NALUs.
     */
    private fun parseAnnexBNalus(buffer: ByteBuffer): List<ByteArray> {
        val data = ByteArray(buffer.remaining())
        val pos = buffer.position()
        buffer.get(data)
        buffer.position(pos)

        val nalus = mutableListOf<ByteArray>()
        var i = 0

        while (i < data.size) {
            val scLen = startCodeLength(data, i)
            if (scLen == 0) {
                i++
                continue
            }

            val naluStart = i + scLen
            var naluEnd = data.size
            for (j in naluStart until data.size - 2) {
                if (startCodeLength(data, j) > 0) {
                    naluEnd = j
                    break
                }
            }

            if (naluEnd > naluStart) {
                nalus.add(data.copyOfRange(naluStart, naluEnd))
            }
            i = naluEnd
        }

        return nalus
    }

    private fun startCodeLength(data: ByteArray, offset: Int): Int {
        if (offset + 3 < data.size &&
            data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
            data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()) {
            return 4
        }
        if (offset + 2 < data.size &&
            data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
            data[offset + 2] == 1.toByte()) {
            return 3
        }
        return 0
    }

    // ──── CSD / Format Helpers ────

    private fun getCsdBytes(format: MediaFormat, index: Int): ByteArray? {
        return try {
            val buffer = format.getByteBuffer("csd-$index") ?: return null
            val data = ByteArray(buffer.remaining())
            val pos = buffer.position()
            buffer.get(data)
            buffer.position(pos)
            data
        } catch (e: Exception) {
            null
        }
    }

    private fun getIntSafe(format: MediaFormat, key: String, default: Int): Int {
        return try {
            if (format.containsKey(key)) format.getInteger(key) else default
        } catch (e: Exception) { default }
    }

    // ──── EBML Writing Utilities ────

    private fun writeElementId(out: OutputStream, id: Long) {
        val bytes = when {
            id < 0x100L -> 1
            id < 0x10000L -> 2
            id < 0x1000000L -> 3
            else -> 4
        }
        for (i in bytes - 1 downTo 0) {
            out.write(((id shr (i * 8)) and 0xFF).toInt())
        }
    }

    private fun writeElementSize(out: OutputStream, size: Long) {
        if (size < 0) {
            writeUnknownSize(out)
            return
        }
        val numBytes = when {
            size < 0x7FL -> 1
            size < 0x3FFFL -> 2
            size < 0x1FFFFFL -> 3
            size < 0x0FFFFFFFL -> 4
            size < 0x07FFFFFFFFL -> 5
            size < 0x03FFFFFFFFFFL -> 6
            size < 0x01FFFFFFFFFFFFL -> 7
            else -> 8
        }
        val marker = 1L shl (7 * numBytes)
        val value = marker or size
        for (i in numBytes - 1 downTo 0) {
            out.write(((value shr (i * 8)) and 0xFF).toInt())
        }
    }

    private fun writeUnknownSize(out: OutputStream) {
        out.write(0x01)
        for (i in 0 until 7) out.write(0xFF)
    }

    private fun writeUintElement(out: OutputStream, id: Long, value: Long) {
        writeElementId(out, id)
        val numBytes = when {
            value < 0x100L -> 1
            value < 0x10000L -> 2
            value < 0x1000000L -> 3
            value < 0x100000000L -> 4
            value < 0x10000000000L -> 5
            value < 0x1000000000000L -> 6
            value < 0x100000000000000L -> 7
            else -> 8
        }
        writeElementSize(out, numBytes.toLong())
        for (i in numBytes - 1 downTo 0) {
            out.write(((value shr (i * 8)) and 0xFF).toInt())
        }
    }

    private fun writeStringElement(out: OutputStream, id: Long, value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        writeElementId(out, id)
        writeElementSize(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeUtf8Element(out: OutputStream, id: Long, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeElementId(out, id)
        writeElementSize(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeFloat64Element(out: OutputStream, id: Long, value: Double) {
        writeElementId(out, id)
        writeElementSize(out, 8)
        val bits = java.lang.Double.doubleToLongBits(value)
        for (i in 7 downTo 0) {
            out.write(((bits shr (i * 8)) and 0xFF).toInt())
        }
    }

    private fun writeBinaryElement(out: OutputStream, id: Long, data: ByteArray) {
        writeElementId(out, id)
        writeElementSize(out, data.size.toLong())
        out.write(data)
    }

    /**
     * Encodes a track number as an EBML variable-length integer.
     * Track numbers 1-127 use a single byte with the high bit set.
     */
    private fun encodeTrackVint(trackNumber: Int): ByteArray {
        return when {
            trackNumber < 0x80 -> byteArrayOf((trackNumber or 0x80).toByte())
            trackNumber < 0x4000 -> byteArrayOf(
                ((trackNumber shr 8) or 0x40).toByte(),
                (trackNumber and 0xFF).toByte()
            )
            else -> throw IOException("Track number too large: $trackNumber")
        }
    }
}
