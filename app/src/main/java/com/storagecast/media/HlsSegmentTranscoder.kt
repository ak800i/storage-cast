package com.storagecast.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import com.storagecast.log.AppLogger
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Transcodes a bounded time range `[startUs, endUs)` of the source into encoded
 * H.264 video + AAC audio samples, for assembly into a single HLS fMP4 media segment
 * by [HlsMp4Builder]. Independent per segment (fresh codecs), which makes arbitrary
 * seeking trivial: the receiver just requests the segment covering the target time.
 *
 * Self-contained (does not share state with [TranscodeStreamer]) so the experimental
 * HLS VOD path cannot regress the working live path.
 */
class HlsSegmentTranscoder {

    companion object {
        private const val TAG = "HlsSegmentTranscoder"
        private const val OUTPUT_VIDEO_MIME = "video/avc"
        private const val OUTPUT_AUDIO_MIME = "audio/mp4a-latm"
        private const val OUTPUT_VIDEO_BITRATE = 8_000_000
        private const val OUTPUT_VIDEO_FRAME_RATE = 30
        private const val OUTPUT_AUDIO_BITRATE = 192_000
        private const val OUTPUT_AUDIO_CHANNEL_COUNT = 2
        private const val TIMEOUT_US = 10_000L
        private const val MAX_WIDTH = 1920
        private const val MAX_HEIGHT = 1080
    }

    class Result(
        val video: HlsMp4Builder.VideoInit?,
        val audio: HlsMp4Builder.AudioInit?,
        val videoSamples: List<HlsMp4Builder.Sample>,
        val audioSamples: List<HlsMp4Builder.Sample>
    )

    /**
     * @param startUs inclusive segment start in microseconds (absolute source time)
     * @param endUs exclusive segment end; Long.MAX_VALUE for "to end of file"
     */
    fun transcodeRange(
        inputPath: String,
        probeResult: MediaProbeResult,
        selectedAudioTrack: AudioTrackInfo?,
        startUs: Long,
        endUs: Long,
        copyAudio: Boolean
    ): Result {
        val videoTrack = probeResult.primaryVideo
        val audioTrack = selectedAudioTrack ?: probeResult.primaryAudio

        val videoOut = if (videoTrack != null) {
            transcodeVideoRange(inputPath, videoTrack, startUs, endUs)
        } else null

        val audioOut = if (audioTrack != null) {
            // When copy-audio is on, mux the source audio untouched (preserves 5.1).
            // Falls back to AAC transcoding when the codec can't be muxed.
            (if (copyAudio) passthroughAudioRange(inputPath, audioTrack, startUs, endUs) else null)
                ?: transcodeAudioRange(inputPath, audioTrack, startUs, endUs)
        } else null

        return Result(
            videoOut?.init, audioOut?.init,
            videoOut?.samples ?: emptyList(),
            audioOut?.samples ?: emptyList()
        )
    }

    private class TrackOut<T>(val init: T?, val samples: List<HlsMp4Builder.Sample>)

    // ──────────────────────────────────────────────────────────────────────────
    //  Video range → H.264 (surface pipeline)
    // ──────────────────────────────────────────────────────────────────────────

    private fun transcodeVideoRange(
        inputPath: String,
        track: VideoTrackInfo,
        startUs: Long,
        endUs: Long
    ): TrackOut<HlsMp4Builder.VideoInit> {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputPath)
        extractor.selectTrack(track.trackIndex)
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val inputFormat = extractor.getTrackFormat(track.trackIndex)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                inputFormat.setInteger(
                    MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO
                )
            } catch (_: Exception) {}
        }

        val inW = track.width
        val inH = track.height
        val (outW, outH) = HlsTranscodeMath.outputSize(inW, inH, MAX_WIDTH, MAX_HEIGHT)

        val outBitrate = HlsTranscodeMath.clampBitrate(track.bitrate, OUTPUT_VIDEO_BITRATE)
        val outFps = HlsTranscodeMath.clampFrameRate(track.frameRate.toDouble(), OUTPUT_VIDEO_FRAME_RATE)

        val outFormat = MediaFormat.createVideoFormat(OUTPUT_VIDEO_MIME, outW, outH).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, outBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, outFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try { setInteger(MediaFormat.KEY_LATENCY, 1) } catch (_: Exception) {}
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                try { setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) } catch (_: Exception) {}
            }
        }

        val encoder = createVideoEncoder()
        encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()

        val decoder = MediaCodec.createDecoderByType(track.mime)
        decoder.configure(inputFormat, surface, null, 0)
        decoder.start()

        val samples = ArrayList<HlsMp4Builder.Sample>()
        var avcC: ByteArray? = null
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var decoderDone = false
        var encoderEosSent = false
        var encoderDone = false

        try {
            while (!encoderDone) {
                if (!inputDone) {
                    val idx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = decoder.getInputBuffer(idx)
                        val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        if (size < 0) {
                            decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                if (!decoderDone) {
                    val status = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (status >= 0) {
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val pts = info.presentationTimeUs
                        // Drop pre-roll frames before the segment start; stop feeding at end.
                        val isCfg = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val render = HlsTranscodeMath.shouldRenderVideoFrame(info.size, isCfg, pts, startUs, endUs)
                        decoder.releaseOutputBuffer(status, render)
                        if (HlsTranscodeMath.isVideoSegmentComplete(eos, pts, endUs)) {
                            decoderDone = true
                            if (!encoderEosSent) { encoder.signalEndOfInputStream(); encoderEosSent = true }
                        }
                    }
                }

                val es = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (es == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    avcC = buildAvcConfigRecord(encoder.outputFormat)
                } else if (es >= 0) {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val buf = encoder.getOutputBuffer(es)
                    if (isConfig && avcC == null && buf != null) {
                        val cfg = ByteArray(info.size)
                        buf.position(info.offset); buf.get(cfg, 0, info.size)
                        val built = buildAvcConfigFromAnnexB(cfg)
                        if (built.isNotEmpty()) avcC = built
                    }
                    if (!isConfig && info.size > 0 && buf != null) {
                        val data = ByteArray(info.size)
                        buf.position(info.offset); buf.get(data, 0, info.size)
                        val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        samples.add(HlsMp4Builder.Sample(HlsMp4Builder.ensureAvcc(data), info.presentationTimeUs, key))
                    }
                    encoder.releaseOutputBuffer(es, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                }
            }
        } finally {
            safeStopRelease(decoder)
            safeStopRelease(encoder)
            try { surface.release() } catch (_: Exception) {}
            extractor.release()
        }

        val init = avcC?.let { HlsMp4Builder.VideoInit(it, outW, outH) }
        AppLogger.info(TAG, "Segment video [${startUs / 1000}..${if (endUs == Long.MAX_VALUE) "end" else (endUs / 1000).toString()}]ms: ${samples.size} frames")
        return TrackOut(init, samples)
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Audio range → AAC
    // ──────────────────────────────────────────────────────────────────────────

    private fun transcodeAudioRange(
        inputPath: String,
        track: AudioTrackInfo,
        startUs: Long,
        endUs: Long
    ): TrackOut<HlsMp4Builder.AudioInit> {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputPath)
        extractor.selectTrack(track.trackIndex)
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val inputFormat = extractor.getTrackFormat(track.trackIndex)
        inputFormat.setInteger(MediaFormat.KEY_MAX_OUTPUT_CHANNEL_COUNT, OUTPUT_AUDIO_CHANNEL_COUNT)

        val decoder = MediaCodec.createDecoderByType(track.mime)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()

        var encoder: MediaCodec? = null
        var sampleRate = 48000
        var channels = OUTPUT_AUDIO_CHANNEL_COUNT
        var asc: ByteArray? = null
        val samples = ArrayList<HlsMp4Builder.Sample>()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var decoderDone = false
        var encoderEosSent = false
        var encoderDone = false

        fun ensureEncoder() {
            if (encoder != null) return
            val outFmt = decoder.outputFormat
            sampleRate = getIntSafe(outFmt, MediaFormat.KEY_SAMPLE_RATE, 48000)
            channels = getIntSafe(outFmt, MediaFormat.KEY_CHANNEL_COUNT, OUTPUT_AUDIO_CHANNEL_COUNT)
            val fmt = MediaFormat.createAudioFormat(OUTPUT_AUDIO_MIME, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            encoder = MediaCodec.createEncoderByType(OUTPUT_AUDIO_MIME).apply {
                configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        }

        try {
            while (!encoderDone) {
                if (!inputDone) {
                    val idx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = decoder.getInputBuffer(idx)
                        val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        if (size < 0) {
                            decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                if (!decoderDone) {
                    val status = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        ensureEncoder()
                    } else if (status >= 0) {
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val pts = info.presentationTimeUs
                        val enc = if (info.size > 0 && pts >= startUs && pts < endUs) {
                            ensureEncoder(); encoder
                        } else null
                        if (enc != null) {
                            val decoded = decoder.getOutputBuffer(status)
                            if (decoded != null) {
                                val encIdx = enc.dequeueInputBuffer(TIMEOUT_US)
                                if (encIdx >= 0) {
                                    val encBuf = enc.getInputBuffer(encIdx)
                                    if (encBuf != null) {
                                        encBuf.clear()
                                        val limit = minOf(decoded.remaining(), encBuf.remaining())
                                        val tmp = ByteArray(limit)
                                        decoded.get(tmp, 0, limit)
                                        encBuf.put(tmp, 0, limit)
                                        enc.queueInputBuffer(encIdx, 0, limit, pts, 0)
                                    }
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(status, false)
                        if (eos || pts >= endUs) {
                            decoderDone = true
                            ensureEncoder()
                            val enc2 = encoder
                            if (enc2 != null && !encoderEosSent) {
                                val encIdx = enc2.dequeueInputBuffer(TIMEOUT_US)
                                if (encIdx >= 0) {
                                    enc2.queueInputBuffer(encIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    encoderEosSent = true
                                }
                            }
                        }
                    }
                }

                val enc = encoder
                if (enc != null) {
                    val es = enc.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (es == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        asc = getCsdBytes(enc.outputFormat, 0)
                    } else if (es >= 0) {
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val buf = enc.getOutputBuffer(es)
                        if (isConfig && asc == null && buf != null) {
                            val cfg = ByteArray(info.size)
                            buf.position(info.offset); buf.get(cfg, 0, info.size)
                            asc = cfg
                        }
                        if (!isConfig && info.size > 0 && buf != null) {
                            val data = ByteArray(info.size)
                            buf.position(info.offset); buf.get(data, 0, info.size)
                            samples.add(HlsMp4Builder.Sample(data, info.presentationTimeUs, true))
                        }
                        enc.releaseOutputBuffer(es, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                    } else if (encoderEosSent && es == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // No more output coming.
                        encoderDone = true
                    }
                } else if (decoderDone) {
                    encoderDone = true
                }
            }
        } finally {
            safeStopRelease(decoder)
            safeStopRelease(encoder)
            extractor.release()
        }

        val init = asc?.let { HlsMp4Builder.AudioInit(HlsMp4Builder.AudioCodec.AAC, it, sampleRate, channels) }
        return TrackOut(init, samples)
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Audio range → passthrough copy (AAC / AC-3 / E-AC-3)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Copies the source audio bitstream for the range without re-encoding (preserves
     * the original track, e.g. 5.1). Returns null when the codec can't be muxed, so the
     * caller falls back to AAC transcoding.
     */
    private fun passthroughAudioRange(
        inputPath: String,
        track: AudioTrackInfo,
        startUs: Long,
        endUs: Long
    ): TrackOut<HlsMp4Builder.AudioInit>? {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputPath)
        extractor.selectTrack(track.trackIndex)
        val format = extractor.getTrackFormat(track.trackIndex)
        val mime = (format.getString(MediaFormat.KEY_MIME) ?: track.mime).lowercase()
        val sampleRate = getIntSafe(format, MediaFormat.KEY_SAMPLE_RATE, track.sampleRate)
        val channels = getIntSafe(format, MediaFormat.KEY_CHANNEL_COUNT, track.channelCount)

        // Read all frames in [startUs, endUs).
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val frames = ArrayList<HlsMp4Builder.Sample>()
        val buf = ByteBuffer.allocate(64 * 1024)
        var firstFrame: ByteArray? = null
        try {
            while (true) {
                buf.clear()
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) break
                val pts = extractor.sampleTime
                if (HlsTranscodeMath.audioRangeEnded(pts, endUs)) break
                if (HlsTranscodeMath.audioFrameIncluded(pts, startUs)) {
                    val data = ByteArray(size)
                    buf.position(0); buf.get(data, 0, size)
                    if (firstFrame == null) firstFrame = data
                    frames.add(HlsMp4Builder.Sample(data, pts, true))
                }
                extractor.advance()
            }
        } finally {
            extractor.release()
        }
        if (frames.isEmpty()) return null

        val init: HlsMp4Builder.AudioInit = when {
            mime.contains("mp4a") || mime.contains("aac") -> {
                val asc = getCsdBytes(format, 0) ?: return null
                HlsMp4Builder.AudioInit(HlsMp4Builder.AudioCodec.AAC, asc, sampleRate, channels)
            }
            mime.contains("eac3") || mime.contains("ec3") || mime.contains("ec-3") -> {
                val dec3 = DolbyAudioConfig.buildDec3(firstFrame!!) ?: return null
                HlsMp4Builder.AudioInit(HlsMp4Builder.AudioCodec.EAC3, dec3, sampleRate, channels)
            }
            mime.contains("ac3") || mime.contains("ac-3") -> {
                val dac3 = DolbyAudioConfig.buildDac3(firstFrame!!) ?: return null
                HlsMp4Builder.AudioInit(HlsMp4Builder.AudioCodec.AC3, dac3, sampleRate, channels)
            }
            else -> return null
        }

        AppLogger.info(TAG, "Segment audio passthrough: ${init.codec} ${frames.size} frames")
        return TrackOut(init, frames)
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Helpers (local copies; isolated from TranscodeStreamer)
    // ──────────────────────────────────────────────────────────────────────────

    private fun createVideoEncoder(): MediaCodec {
        return try {
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.isHardwareAccelerated }
                .firstOrNull { info -> info.supportedTypes.any { it.equals(OUTPUT_VIDEO_MIME, true) } }?.name
            if (name != null) MediaCodec.createByCodecName(name)
            else MediaCodec.createEncoderByType(OUTPUT_VIDEO_MIME)
        } catch (e: Exception) {
            MediaCodec.createEncoderByType(OUTPUT_VIDEO_MIME)
        }
    }

    private fun safeStopRelease(codec: MediaCodec?) {
        if (codec == null) return
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
    }

    private fun buildAvcConfigRecord(format: MediaFormat): ByteArray {
        val csd0 = getCsdBytes(format, 0) ?: return ByteArray(0)
        if (csd0.isNotEmpty() && csd0[0].toInt() == 1 && startCodeLength(csd0, 0) == 0) return csd0
        val csd1 = getCsdBytes(format, 1)
        return buildAvcConfigFromAnnexB(if (csd1 != null) csd0 + csd1 else csd0)
    }

    private fun buildAvcConfigFromAnnexB(data: ByteArray): ByteArray {
        if (data.isNotEmpty() && data[0].toInt() == 1 && startCodeLength(data, 0) == 0) return data
        val nalus = parseAnnexBNalus(data)
        val sps = nalus.filter { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }
        val pps = nalus.filter { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }
        if (sps.isEmpty()) return ByteArray(0)
        val s = sps[0]
        if (s.size < 4) return ByteArray(0)
        val o = ByteArrayOutputStream()
        o.write(1); o.write(s[1].toInt() and 0xFF); o.write(s[2].toInt() and 0xFF); o.write(s[3].toInt() and 0xFF)
        o.write(0xFF); o.write(0xE0 or (sps.size and 0x1F))
        for (n in sps) { o.write((n.size shr 8) and 0xFF); o.write(n.size and 0xFF); o.write(n) }
        o.write(pps.size and 0xFF)
        for (n in pps) { o.write((n.size shr 8) and 0xFF); o.write(n.size and 0xFF); o.write(n) }
        return o.toByteArray()
    }

    private fun parseAnnexBNalus(data: ByteArray): List<ByteArray> {
        val nalus = ArrayList<ByteArray>()
        var i = 0
        while (i < data.size) {
            val sc = startCodeLength(data, i)
            if (sc == 0) { i++; continue }
            val start = i + sc
            var end = data.size
            var j = start
            while (j < data.size) { if (startCodeLength(data, j) > 0) { end = j; break }; j++ }
            if (end > start) nalus.add(data.copyOfRange(start, end))
            i = end
        }
        return nalus
    }

    private fun startCodeLength(d: ByteArray, o: Int): Int {
        if (o + 4 <= d.size && d[o] == 0.toByte() && d[o + 1] == 0.toByte() && d[o + 2] == 0.toByte() && d[o + 3] == 1.toByte()) return 4
        if (o + 3 <= d.size && d[o] == 0.toByte() && d[o + 1] == 0.toByte() && d[o + 2] == 1.toByte()) return 3
        return 0
    }

    private fun getCsdBytes(format: MediaFormat, index: Int): ByteArray? {
        return try {
            val buffer = format.getByteBuffer("csd-$index") ?: return null
            val dup = buffer.duplicate()
            val data = ByteArray(dup.remaining())
            dup.get(data)
            data
        } catch (e: Exception) { null }
    }

    private fun getIntSafe(format: MediaFormat, key: String, default: Int): Int {
        return try { if (format.containsKey(key)) format.getInteger(key) else default } catch (e: Exception) { default }
    }
}
