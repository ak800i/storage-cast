package com.storagecast.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
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
 * Streaming transcoder that outputs a **fragmented MP4 (fMP4 / CMAF-style)** stream
 * via a [PipedInputStream]. Video is re-encoded to H.264 (AVC) and audio to AAC-LC,
 * then muxed into an `init segment (ftyp + moov)` followed by a continuous sequence
 * of `moof + mdat` media fragments. The stream is served as `video/mp4`.
 *
 * Why fragmented MP4 and not MKV: the Google Cast Default Media Receiver only
 * supports the MP2T, MP3, MP4, OGG, WAV and WebM containers. Matroska/MKV is NOT a
 * supported container, and WebM only carries VP8/VP9 + Vorbis/Opus (never H.264/AAC),
 * so an H.264/AAC elementary stream must be wrapped in MP4. A plain MP4 cannot be
 * produced as a live stream (its `moov` atom requires seeking back over the whole
 * file), and [android.media.MediaMuxer] cannot write to a non-seekable pipe, so the
 * fMP4 boxes are written here by hand.
 *
 * For video-passthrough + audio-transcode, use [createRemuxWithAudioTranscodeStream]
 * which copies the H.264 video track as-is and only re-encodes the audio.
 */
class TranscodeStreamer {

    companion object {
        private const val TAG = "TranscodeStreamer"
        private const val PIPE_BUFFER_SIZE = 4 * 1024 * 1024 // 4 MB

        // Output codec settings
        private const val OUTPUT_VIDEO_MIME = "video/avc"
        private const val OUTPUT_AUDIO_MIME = "audio/mp4a-latm"
        private const val OUTPUT_VIDEO_BITRATE = 8_000_000
        private const val OUTPUT_VIDEO_FRAME_RATE = 30
        private const val OUTPUT_VIDEO_IFRAME_INTERVAL = 1
        private const val OUTPUT_AUDIO_BITRATE = 192_000
        private const val OUTPUT_AUDIO_SAMPLE_RATE = 48000
        private const val OUTPUT_AUDIO_CHANNEL_COUNT = 2
        private const val TIMEOUT_US = 10_000L
        private const val MAX_WIDTH = 1920
        private const val MAX_HEIGHT = 1080
    }

    @Volatile
    private var isCancelled = false

    fun cancel() {
        isCancelled = true
    }

    interface ProgressListener {
        fun onProgress(percent: Int)
        fun onError(error: String)
    }

    /**
     * Creates an InputStream that streams transcoded video (H.264) and audio (AAC)
     * as a fragmented MP4 container. Transcoding runs in a background thread; the
     * returned stream can be served immediately by the HTTP server.
     */
    fun createTranscodeStream(
        inputPath: String,
        probeResult: MediaProbeResult,
        selectedAudioTrack: AudioTrackInfo? = null,
        listener: ProgressListener? = null
    ): InputStream {
        isCancelled = false
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, PIPE_BUFFER_SIZE)

        Thread {
            try {
                transcodeToFmp4(inputPath, probeResult, selectedAudioTrack, pipedOut, listener)
            } catch (e: IOException) {
                // Pipe broken = reader closed (Cast device disconnected) — expected
                AppLogger.info(TAG, "Transcode stream ended: ${e.message}")
            } catch (e: Exception) {
                AppLogger.error(TAG, "Transcode stream error: ${e.message}")
                listener?.onError("Transcode failed: ${e.message}")
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }.apply {
            name = "TranscodeStreamer"
            isDaemon = true
        }.start()

        return pipedIn
    }

    /**
     * Creates an InputStream that copies an H.264 video track (passthrough, no
     * re-encoding) and re-encodes audio (→ AAC) as a fragmented MP4 container. Much
     * faster than a full transcode since the video is just copied. If the source
     * video is not H.264, falls back to a full transcode so the output is still a
     * Cast-compatible MP4.
     */
    fun createRemuxWithAudioTranscodeStream(
        inputPath: String,
        probeResult: MediaProbeResult,
        selectedAudioTrack: AudioTrackInfo,
        listener: ProgressListener? = null
    ): InputStream {
        isCancelled = false
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, PIPE_BUFFER_SIZE)

        val videoMime = probeResult.primaryVideo?.mime
        val canPassthroughVideo = videoMime == null || videoMime.equals("video/avc", ignoreCase = true)

        Thread {
            try {
                if (canPassthroughVideo) {
                    remuxWithAudioTranscodeToFmp4(inputPath, probeResult, selectedAudioTrack, pipedOut, listener)
                } else {
                    AppLogger.info(TAG, "Video is $videoMime (not H.264), full-transcoding instead of passthrough")
                    transcodeToFmp4(inputPath, probeResult, selectedAudioTrack, pipedOut, listener)
                }
            } catch (e: IOException) {
                AppLogger.info(TAG, "Remux stream ended: ${e.message}")
            } catch (e: Exception) {
                AppLogger.error(TAG, "Remux stream error: ${e.message}")
                listener?.onError("Remux failed: ${e.message}")
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }.apply {
            name = "TranscodeStreamer-Remux"
            isDaemon = true
        }.start()

        return pipedIn
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Full transcode (video + audio) → fragmented MP4
    // ──────────────────────────────────────────────────────────────────────────

    private fun transcodeToFmp4(
        inputPath: String,
        probeResult: MediaProbeResult,
        selectedAudioTrack: AudioTrackInfo?,
        output: OutputStream,
        listener: ProgressListener?
    ) {
        val out = output.buffered(65536)
        val videoTrackInfo = probeResult.primaryVideo
        val audioTrackInfo = selectedAudioTrack ?: probeResult.primaryAudio

        if (videoTrackInfo == null && audioTrackInfo == null) {
            listener?.onError("No video or audio tracks found")
            return
        }

        val durationUs = if (probeResult.durationMs > 0) probeResult.durationMs * 1000 else 0L

        var videoDecoder: MediaCodec? = null
        var videoEncoder: MediaCodec? = null
        var videoExtractor: MediaExtractor? = null
        var videoInputSurface: android.view.Surface? = null
        var outWidth = 0
        var outHeight = 0

        if (videoTrackInfo != null) {
            videoExtractor = MediaExtractor()
            videoExtractor.setDataSource(inputPath)
            videoExtractor.selectTrack(videoTrackInfo.trackIndex)
            val inputFormat = videoExtractor.getTrackFormat(videoTrackInfo.trackIndex)

            val inWidth = videoTrackInfo.width
            val inHeight = videoTrackInfo.height
            if (inWidth <= 0 || inHeight <= 0) {
                throw IllegalArgumentException("Invalid video dimensions: ${inWidth}x${inHeight}")
            }
            val scaleFactor = minOf(MAX_WIDTH.toFloat() / inWidth, MAX_HEIGHT.toFloat() / inHeight, 1f)
            outWidth = ((inWidth * scaleFactor).toInt() / 2) * 2
            outHeight = ((inHeight * scaleFactor).toInt() / 2) * 2

            val outputBitrate = if (videoTrackInfo.bitrate in 1 until OUTPUT_VIDEO_BITRATE) {
                videoTrackInfo.bitrate
            } else OUTPUT_VIDEO_BITRATE
            val outputFrameRate = if (videoTrackInfo.frameRate > 0) {
                videoTrackInfo.frameRate.toInt().coerceAtMost(OUTPUT_VIDEO_FRAME_RATE)
            } else OUTPUT_VIDEO_FRAME_RATE

            val videoOutputFormat = MediaFormat.createVideoFormat(OUTPUT_VIDEO_MIME, outWidth, outHeight).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, outputBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, outputFrameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, OUTPUT_VIDEO_IFRAME_INTERVAL)
                // Surface input → encoder consumes graphics buffers (COLOR_FormatSurface).
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                // Discourage B-frames so PTS == DTS (no composition-time offsets needed in fMP4).
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    try { setInteger(MediaFormat.KEY_LATENCY, 1) } catch (_: Exception) {}
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    try { setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) } catch (_: Exception) {}
                }
            }

            // Encoder first, so we can obtain its input Surface, then have the decoder
            // render directly onto it. This lets the GPU handle colour-space conversion
            // (e.g. 10-bit HEVC → 8-bit YUV) and scaling, instead of an unreliable
            // manual ByteBuffer copy.
            videoEncoder = createVideoEncoder()
            videoEncoder.configure(videoOutputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            videoInputSurface = videoEncoder.createInputSurface()

            videoDecoder = MediaCodec.createDecoderByType(videoTrackInfo.mime)
            videoDecoder.configure(inputFormat, videoInputSurface, null, 0)

            videoDecoder.start()
            videoEncoder.start()

            AppLogger.info(TAG, "Video transcode (surface): ${videoTrackInfo.codec} ${videoTrackInfo.width}x${videoTrackInfo.height} → H.264 ${outWidth}x${outHeight}")
        }

        var audioDecoder: MediaCodec? = null
        var audioEncoder: MediaCodec? = null
        var audioExtractor: MediaExtractor? = null

        if (audioTrackInfo != null) {
            audioExtractor = MediaExtractor()
            audioExtractor.setDataSource(inputPath)
            audioExtractor.selectTrack(audioTrackInfo.trackIndex)
            val audioInputFormat = audioExtractor.getTrackFormat(audioTrackInfo.trackIndex)

            audioDecoder = MediaCodec.createDecoderByType(audioTrackInfo.mime)
            audioDecoder.configure(audioInputFormat, null, null, 0)

            val audioOutputFormat = MediaFormat.createAudioFormat(
                OUTPUT_AUDIO_MIME, OUTPUT_AUDIO_SAMPLE_RATE, OUTPUT_AUDIO_CHANNEL_COUNT
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }

            audioEncoder = MediaCodec.createEncoderByType(OUTPUT_AUDIO_MIME)
            audioEncoder.configure(audioOutputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            audioDecoder.start()
            audioEncoder.start()

            AppLogger.info(TAG, "Audio transcode: ${audioTrackInfo.codec} → AAC")
        }

        // The init segment is written lazily by the writer once both encoders report
        // their codec config (which a hardware encoder only emits after the first
        // frame). Configs are captured from INFO_OUTPUT_FORMAT_CHANGED in the pump.
        val writer = Fmp4Writer(out, hasVideo = videoEncoder != null, hasAudio = audioEncoder != null)

        try {
            pumpTranscode(
                writer,
                videoExtractor, videoDecoder, videoEncoder, outWidth, outHeight,
                audioExtractor, audioDecoder, audioEncoder,
                durationUs, listener
            )

            writer.finish()
            out.flush()
            if (!writer.hasVideoConfig() && videoEncoder != null) {
                listener?.onError("Encoder did not produce a video codec config")
            }
            AppLogger.info(TAG, "Transcode fMP4 stream complete")
        } finally {
            safeStopRelease(videoDecoder)
            safeStopRelease(videoEncoder)
            try { videoInputSurface?.release() } catch (_: Exception) {}
            videoExtractor?.release()
            safeStopRelease(audioDecoder)
            safeStopRelease(audioEncoder)
            audioExtractor?.release()
        }
    }

    /**
     * Interleaved decode→encode pump for the full-transcode path. Encoded samples are
     * pushed into [writer] which buffers them into fMP4 fragments.
     */
    private fun pumpTranscode(
        writer: Fmp4Writer,
        videoExtractor: MediaExtractor?,
        videoDecoder: MediaCodec?,
        videoEncoder: MediaCodec?,
        outWidth: Int,
        outHeight: Int,
        audioExtractor: MediaExtractor?,
        audioDecoder: MediaCodec?,
        audioEncoder: MediaCodec?,
        durationUs: Long,
        listener: ProgressListener?
    ) {
        val bufferInfo = MediaCodec.BufferInfo()

        var videoInputDone = videoExtractor == null
        var videoDecoderDone = videoDecoder == null
        var videoEncoderEosSent = videoEncoder == null
        var videoEncoderDone = videoEncoder == null
        var audioInputDone = audioExtractor == null
        var audioDecoderDone = audioDecoder == null
        var audioEncoderEosSent = audioEncoder == null
        var audioEncoderDone = audioEncoder == null
        var lastReportedProgress = -1

        while (!isCancelled && (!videoEncoderDone || !audioEncoderDone)) {
            // ── Feed video decoder ──
            if (!videoInputDone && videoExtractor != null && videoDecoder != null) {
                val idx = videoDecoder.dequeueInputBuffer(TIMEOUT_US)
                if (idx >= 0) {
                    val buf = videoDecoder.getInputBuffer(idx)
                    if (buf != null) {
                        val size = videoExtractor.readSampleData(buf, 0)
                        if (size < 0) {
                            videoDecoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            videoInputDone = true
                        } else {
                            val pts = videoExtractor.sampleTime
                            videoDecoder.queueInputBuffer(idx, 0, size, pts, 0)
                            videoExtractor.advance()
                            if (durationUs > 0) {
                                val progress = ((pts * 100) / durationUs).toInt().coerceIn(0, 100)
                                if (progress != lastReportedProgress) {
                                    lastReportedProgress = progress
                                    listener?.onProgress(progress)
                                }
                            }
                        }
                    }
                }
            }

            // ── Decode video → render onto encoder input surface ──
            if (!videoDecoderDone && videoDecoder != null && videoEncoder != null) {
                val status = videoDecoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (status >= 0) {
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    // render = true pushes the decoded frame to the encoder's input surface.
                    val render = bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    videoDecoder.releaseOutputBuffer(status, render)
                    if (isEos) {
                        videoDecoderDone = true
                        if (!videoEncoderEosSent) {
                            videoEncoder.signalEndOfInputStream()
                            videoEncoderEosSent = true
                        }
                    }
                }
            }

            // ── Drain video encoder → writer ──
            if (videoEncoder != null && !videoEncoderDone) {
                val encStatus = videoEncoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (encStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    captureVideoConfig(writer, videoEncoder.outputFormat, outWidth, outHeight)
                } else if (encStatus >= 0) {
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val encodedBuf = videoEncoder.getOutputBuffer(encStatus)
                    if (isConfig) {
                        // Fallback: some encoders deliver SPS/PPS only as a codec-config
                        // buffer (Annex-B) rather than via INFO_OUTPUT_FORMAT_CHANGED.
                        if (!writer.hasVideoConfig() && encodedBuf != null) {
                            val cfg = ByteArray(bufferInfo.size)
                            encodedBuf.position(bufferInfo.offset)
                            encodedBuf.get(cfg, 0, bufferInfo.size)
                            val avcC = buildAvcConfigFromAnnexB(cfg)
                            if (avcC.isNotEmpty()) writer.setVideoConfig(avcC, outWidth, outHeight)
                        }
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && encodedBuf != null) {
                        val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        val data = ByteArray(bufferInfo.size)
                        encodedBuf.position(bufferInfo.offset)
                        encodedBuf.get(data, 0, bufferInfo.size)
                        writer.addVideoSample(data, bufferInfo.presentationTimeUs, isKey)
                    }
                    videoEncoder.releaseOutputBuffer(encStatus, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        videoEncoderDone = true
                    }
                }
            }

            // ── Feed audio decoder ──
            if (!audioInputDone && audioExtractor != null && audioDecoder != null) {
                val idx = audioDecoder.dequeueInputBuffer(TIMEOUT_US)
                if (idx >= 0) {
                    val buf = audioDecoder.getInputBuffer(idx)
                    if (buf != null) {
                        val size = audioExtractor.readSampleData(buf, 0)
                        if (size < 0) {
                            audioDecoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            audioInputDone = true
                        } else {
                            audioDecoder.queueInputBuffer(idx, 0, size, audioExtractor.sampleTime, 0)
                            audioExtractor.advance()
                        }
                    }
                }
            }

            // ── Decode audio → feed encoder ──
            if (!audioDecoderDone && audioDecoder != null && audioEncoder != null) {
                val status = audioDecoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (status >= 0) {
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (bufferInfo.size > 0) {
                        val decoded = audioDecoder.getOutputBuffer(status)
                        if (decoded != null) {
                            val encIdx = audioEncoder.dequeueInputBuffer(TIMEOUT_US)
                            if (encIdx >= 0) {
                                val encBuf = audioEncoder.getInputBuffer(encIdx)
                                if (encBuf != null) {
                                    encBuf.clear()
                                    val limit = minOf(decoded.remaining(), encBuf.remaining())
                                    val temp = ByteArray(limit)
                                    decoded.get(temp, 0, limit)
                                    encBuf.put(temp, 0, limit)
                                    audioEncoder.queueInputBuffer(
                                        encIdx, 0, limit, bufferInfo.presentationTimeUs,
                                        if (isEos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                                    )
                                    if (isEos) audioEncoderEosSent = true
                                }
                            }
                        }
                    } else if (isEos) {
                        val encIdx = audioEncoder.dequeueInputBuffer(TIMEOUT_US)
                        if (encIdx >= 0) {
                            audioEncoder.queueInputBuffer(encIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            audioEncoderEosSent = true
                        }
                    }
                    audioDecoder.releaseOutputBuffer(status, false)
                    if (isEos) audioDecoderDone = true
                }
            }

            // ── Drain audio encoder → writer ──
            if (audioEncoder != null && !audioEncoderDone) {
                val encStatus = audioEncoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (encStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    captureAudioConfig(writer, audioEncoder.outputFormat)
                } else if (encStatus >= 0) {
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val encodedBuf = audioEncoder.getOutputBuffer(encStatus)
                    if (isConfig) {
                        if (!writer.hasAudioConfig() && encodedBuf != null) {
                            val cfg = ByteArray(bufferInfo.size)
                            encodedBuf.position(bufferInfo.offset)
                            encodedBuf.get(cfg, 0, bufferInfo.size)
                            writer.setAudioConfig(cfg, OUTPUT_AUDIO_SAMPLE_RATE, OUTPUT_AUDIO_CHANNEL_COUNT)
                        }
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && encodedBuf != null) {
                        val data = ByteArray(bufferInfo.size)
                        encodedBuf.position(bufferInfo.offset)
                        encodedBuf.get(data, 0, bufferInfo.size)
                        writer.addAudioSample(data, bufferInfo.presentationTimeUs)
                    }
                    audioEncoder.releaseOutputBuffer(encStatus, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        audioEncoderDone = true
                    }
                }
                if (audioDecoderDone && !audioEncoderEosSent && encStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    val idx = audioEncoder.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        audioEncoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        audioEncoderEosSent = true
                    }
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Video passthrough + audio transcode → fragmented MP4
    // ──────────────────────────────────────────────────────────────────────────

    private fun remuxWithAudioTranscodeToFmp4(
        inputPath: String,
        probeResult: MediaProbeResult,
        selectedAudioTrack: AudioTrackInfo,
        output: OutputStream,
        listener: ProgressListener?
    ) {
        val out = output.buffered(65536)
        val videoTrack = probeResult.primaryVideo
        val durationUs = if (probeResult.durationMs > 0) probeResult.durationMs * 1000 else 0L

        // ── Audio transcode pipeline ──
        val audioExtractor = MediaExtractor()
        audioExtractor.setDataSource(inputPath)
        audioExtractor.selectTrack(selectedAudioTrack.trackIndex)
        val audioInputFormat = audioExtractor.getTrackFormat(selectedAudioTrack.trackIndex)

        val audioDecoder = MediaCodec.createDecoderByType(selectedAudioTrack.mime)
        audioDecoder.configure(audioInputFormat, null, null, 0)

        val audioOutputFormat = MediaFormat.createAudioFormat(
            OUTPUT_AUDIO_MIME, OUTPUT_AUDIO_SAMPLE_RATE, OUTPUT_AUDIO_CHANNEL_COUNT
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        val audioEncoder = MediaCodec.createEncoderByType(OUTPUT_AUDIO_MIME)
        audioEncoder.configure(audioOutputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioDecoder.start()
        audioEncoder.start()
        AppLogger.info(TAG, "Audio transcode: ${selectedAudioTrack.codec} → AAC")

        // ── Video passthrough setup ──
        val videoExtractor = MediaExtractor()
        videoExtractor.setDataSource(inputPath)
        var hasVideo = false
        val writer = Fmp4Writer(out, hasVideo = videoTrack != null, hasAudio = true)
        if (videoTrack != null) {
            videoExtractor.selectTrack(videoTrack.trackIndex)
            val videoFormat = videoExtractor.getTrackFormat(videoTrack.trackIndex)
            val avcC = buildAvcConfigRecord(videoFormat)
            if (avcC.isEmpty()) throw IOException("Failed to build avcC from input video format")
            val w = getIntSafe(videoFormat, MediaFormat.KEY_WIDTH, videoTrack.width)
            val h = getIntSafe(videoFormat, MediaFormat.KEY_HEIGHT, videoTrack.height)
            // The passthrough video codec config is known immediately from the input.
            writer.setVideoConfig(avcC, w, h)
            hasVideo = true
            AppLogger.info(TAG, "Video passthrough: ${videoTrack.codec} ${w}x${h}")
        }

        try {
            // The audio codec config arrives from INFO_OUTPUT_FORMAT_CHANGED during the
            // pump; the writer holds back fragments until the init segment is written.
            pumpRemux(writer, videoExtractor, hasVideo, audioExtractor, audioDecoder, audioEncoder, durationUs, listener)
            writer.finish()
            out.flush()
            AppLogger.info(TAG, "Remux+audio-transcode fMP4 stream complete")
        } finally {
            safeStopRelease(audioDecoder)
            safeStopRelease(audioEncoder)
            audioExtractor.release()
            videoExtractor.release()
        }
    }

    private fun pumpRemux(
        writer: Fmp4Writer,
        videoExtractor: MediaExtractor,
        hasVideo: Boolean,
        audioExtractor: MediaExtractor,
        audioDecoder: MediaCodec,
        audioEncoder: MediaCodec,
        durationUs: Long,
        listener: ProgressListener?
    ) {
        val sampleBuffer = ByteBuffer.allocate(2 * 1024 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()

        var videoDone = !hasVideo
        var audioInputDone = false
        var audioDecoderDone = false
        var audioEncoderEosSent = false
        var audioEncoderDone = false
        var lastReportedProgress = -1

        while (!isCancelled && (!videoDone || !audioEncoderDone)) {
            // ── Video passthrough ──
            if (!videoDone) {
                sampleBuffer.clear()
                val sampleSize = videoExtractor.readSampleData(sampleBuffer, 0)
                if (sampleSize < 0) {
                    videoDone = true
                } else {
                    val ptsUs = videoExtractor.sampleTime
                    val isKey = (videoExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    val data = ByteArray(sampleSize)
                    sampleBuffer.position(0)
                    sampleBuffer.get(data, 0, sampleSize)
                    writer.addVideoSample(data, ptsUs, isKey)
                    videoExtractor.advance()
                    if (durationUs > 0) {
                        val progress = ((ptsUs * 100) / durationUs).toInt().coerceIn(0, 100)
                        if (progress != lastReportedProgress) {
                            lastReportedProgress = progress
                            listener?.onProgress(progress)
                        }
                    }
                }
            }

            // ── Feed audio decoder ──
            if (!audioInputDone) {
                val idx = audioDecoder.dequeueInputBuffer(TIMEOUT_US)
                if (idx >= 0) {
                    val buf = audioDecoder.getInputBuffer(idx)
                    if (buf != null) {
                        val size = audioExtractor.readSampleData(buf, 0)
                        if (size < 0) {
                            audioDecoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            audioInputDone = true
                        } else {
                            audioDecoder.queueInputBuffer(idx, 0, size, audioExtractor.sampleTime, 0)
                            audioExtractor.advance()
                        }
                    }
                }
            }

            // ── Decode audio → feed encoder ──
            if (!audioDecoderDone) {
                val status = audioDecoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (status >= 0) {
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (bufferInfo.size > 0) {
                        val decoded = audioDecoder.getOutputBuffer(status)
                        if (decoded != null) {
                            val encIdx = audioEncoder.dequeueInputBuffer(TIMEOUT_US)
                            if (encIdx >= 0) {
                                val encBuf = audioEncoder.getInputBuffer(encIdx)
                                if (encBuf != null) {
                                    encBuf.clear()
                                    val limit = minOf(decoded.remaining(), encBuf.remaining())
                                    val temp = ByteArray(limit)
                                    decoded.get(temp, 0, limit)
                                    encBuf.put(temp, 0, limit)
                                    audioEncoder.queueInputBuffer(
                                        encIdx, 0, limit, bufferInfo.presentationTimeUs,
                                        if (isEos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                                    )
                                    if (isEos) audioEncoderEosSent = true
                                }
                            }
                        }
                    } else if (isEos) {
                        val encIdx = audioEncoder.dequeueInputBuffer(TIMEOUT_US)
                        if (encIdx >= 0) {
                            audioEncoder.queueInputBuffer(encIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            audioEncoderEosSent = true
                        }
                    }
                    audioDecoder.releaseOutputBuffer(status, false)
                    if (isEos) audioDecoderDone = true
                }
            }

            // ── Drain audio encoder → writer ──
            if (!audioEncoderDone) {
                val encStatus = audioEncoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (encStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    captureAudioConfig(writer, audioEncoder.outputFormat)
                } else if (encStatus >= 0) {
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val encodedBuf = audioEncoder.getOutputBuffer(encStatus)
                    if (isConfig) {
                        if (!writer.hasAudioConfig() && encodedBuf != null) {
                            val cfg = ByteArray(bufferInfo.size)
                            encodedBuf.position(bufferInfo.offset)
                            encodedBuf.get(cfg, 0, bufferInfo.size)
                            writer.setAudioConfig(cfg, OUTPUT_AUDIO_SAMPLE_RATE, OUTPUT_AUDIO_CHANNEL_COUNT)
                        }
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && encodedBuf != null) {
                        val data = ByteArray(bufferInfo.size)
                        encodedBuf.position(bufferInfo.offset)
                        encodedBuf.get(data, 0, bufferInfo.size)
                        writer.addAudioSample(data, bufferInfo.presentationTimeUs)
                    }
                    audioEncoder.releaseOutputBuffer(encStatus, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        audioEncoderDone = true
                    }
                }
                if (audioDecoderDone && !audioEncoderEosSent && encStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    val idx = audioEncoder.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        audioEncoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        audioEncoderEosSent = true
                    }
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Codec / encoder helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun createVideoEncoder(): MediaCodec {
        return try {
            val codecName = selectHardwareEncoder(OUTPUT_VIDEO_MIME)
            if (codecName != null) {
                AppLogger.info(TAG, "Using HW video encoder: $codecName")
                MediaCodec.createByCodecName(codecName)
            } else {
                AppLogger.info(TAG, "No HW video encoder, using default")
                MediaCodec.createEncoderByType(OUTPUT_VIDEO_MIME)
            }
        } catch (e: Exception) {
            AppLogger.warn(TAG, "HW video encoder setup failed, using default: ${e.message}")
            MediaCodec.createEncoderByType(OUTPUT_VIDEO_MIME)
        }
    }

    /**
     * Reads the H.264 codec config from the video encoder's output format and sets it
     * on the writer. Called when the encoder reports INFO_OUTPUT_FORMAT_CHANGED.
     */
    private fun captureVideoConfig(writer: Fmp4Writer, format: MediaFormat, outWidth: Int, outHeight: Int) {
        if (writer.hasVideoConfig()) return
        val avcC = buildAvcConfigRecord(format)
        if (avcC.isEmpty()) {
            AppLogger.warn(TAG, "Encoder format change but avcC not available yet")
            return
        }
        val w = getIntSafe(format, MediaFormat.KEY_WIDTH, outWidth)
        val h = getIntSafe(format, MediaFormat.KEY_HEIGHT, outHeight)
        writer.setVideoConfig(avcC, w, h)
        AppLogger.info(TAG, "Captured video codec config (avcC ${avcC.size} bytes, ${w}x${h})")
    }

    /** Reads the AAC codec config (ASC) from the audio encoder's output format. */
    private fun captureAudioConfig(writer: Fmp4Writer, format: MediaFormat) {
        if (writer.hasAudioConfig()) return
        val asc = getCsdBytes(format, 0)
        if (asc == null || asc.isEmpty()) {
            AppLogger.warn(TAG, "Audio encoder format change but ASC not available yet")
            return
        }
        val sr = getIntSafe(format, MediaFormat.KEY_SAMPLE_RATE, OUTPUT_AUDIO_SAMPLE_RATE)
        val ch = getIntSafe(format, MediaFormat.KEY_CHANNEL_COUNT, OUTPUT_AUDIO_CHANNEL_COUNT)
        writer.setAudioConfig(asc, sr, ch)
        AppLogger.info(TAG, "Captured audio codec config (ASC ${asc.size} bytes, ${sr}Hz ${ch}ch)")
    }

    private fun selectHardwareEncoder(mime: String): String? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return codecList.codecInfos
            .filter { it.isEncoder && it.isHardwareAccelerated }
            .firstOrNull { info ->
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }?.name
    }

    private fun safeStopRelease(codec: MediaCodec?) {
        if (codec == null) return
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
    }

    /**
     * Builds an AVCDecoderConfigurationRecord (the contents of the `avcC` box) from a
     * [MediaFormat]. Handles both Annex-B SPS/PPS in `csd-0`/`csd-1` (encoder output
     * and most MP4/MKV inputs) and a format that already exposes a packaged avcC record.
     */
    private fun buildAvcConfigRecord(format: MediaFormat): ByteArray {
        val csd0 = getCsdBytes(format, 0) ?: return ByteArray(0)

        // Already a packaged avcC record? (configurationVersion == 1 and no start code)
        if (csd0.isNotEmpty() && csd0[0].toInt() == 1 && startCodeLength(csd0, 0) == 0) {
            return csd0
        }

        val csd1 = getCsdBytes(format, 1)
        // Concatenate csd-0 (SPS) and csd-1 (PPS) into one Annex-B stream and parse.
        val annexB = if (csd1 != null) csd0 + csd1 else csd0
        return buildAvcConfigFromAnnexB(annexB)
    }

    /**
     * Builds an avcC record from a raw Annex-B byte stream containing SPS and PPS NAL
     * units (the form some encoders deliver as a single codec-config buffer).
     */
    private fun buildAvcConfigFromAnnexB(data: ByteArray): ByteArray {
        // Already a packaged avcC record?
        if (data.isNotEmpty() && data[0].toInt() == 1 && startCodeLength(data, 0) == 0) {
            return data
        }

        val nalus = parseAnnexBNalus(data)
        val sps = nalus.filter { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }
        val pps = nalus.filter { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }

        if (sps.isEmpty()) {
            AppLogger.warn(TAG, "No SPS found while building avcC")
            return ByteArray(0)
        }
        val sps0 = sps[0]
        if (sps0.size < 4) {
            AppLogger.warn(TAG, "SPS too short: ${sps0.size} bytes")
            return ByteArray(0)
        }

        val output = ByteArrayOutputStream()
        output.write(1)                       // configurationVersion
        output.write(sps0[1].toInt() and 0xFF) // AVCProfileIndication
        output.write(sps0[2].toInt() and 0xFF) // profile_compatibility
        output.write(sps0[3].toInt() and 0xFF) // AVCLevelIndication
        output.write(0xFF)                    // 6 bits reserved + lengthSizeMinusOne = 3

        output.write(0xE0 or (sps.size and 0x1F)) // 3 bits reserved + numOfSPS
        for (nalu in sps) {
            output.write((nalu.size shr 8) and 0xFF)
            output.write(nalu.size and 0xFF)
            output.write(nalu)
        }
        output.write(pps.size and 0xFF)
        for (nalu in pps) {
            output.write((nalu.size shr 8) and 0xFF)
            output.write(nalu.size and 0xFF)
            output.write(nalu)
        }
        return output.toByteArray()
    }

    private fun parseAnnexBNalus(data: ByteArray): List<ByteArray> {
        val nalus = mutableListOf<ByteArray>()
        var i = 0
        while (i < data.size) {
            val scLen = startCodeLength(data, i)
            if (scLen == 0) { i++; continue }
            val naluStart = i + scLen
            var naluEnd = data.size
            var j = naluStart
            while (j < data.size) {
                if (startCodeLength(data, j) > 0) { naluEnd = j; break }
                j++
            }
            if (naluEnd > naluStart) {
                nalus.add(data.copyOfRange(naluStart, naluEnd))
            }
            i = naluEnd
        }
        return nalus
    }

    private fun startCodeLength(data: ByteArray, offset: Int): Int {
        if (offset + 4 <= data.size &&
            data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
            data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()) return 4
        if (offset + 3 <= data.size &&
            data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
            data[offset + 2] == 1.toByte()) return 3
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
        return try {
            if (format.containsKey(key)) format.getInteger(key) else default
        } catch (e: Exception) { default }
    }
}
