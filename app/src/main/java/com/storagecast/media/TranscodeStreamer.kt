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
        copyAudio: Boolean = false,
        startPositionMs: Long = 0L,
        listener: ProgressListener? = null
    ): InputStream {
        isCancelled = false
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, PIPE_BUFFER_SIZE)

        Thread {
            try {
                transcodeToFmp4(inputPath, probeResult, selectedAudioTrack, copyAudio, startPositionMs, pipedOut, listener)
            } catch (e: IOException) {
                // Pipe broken = reader closed (Cast device disconnected) — expected
                AppLogger.info(TAG, "Transcode stream ended: ${e.message}")
            } catch (e: Exception) {
                AppLogger.error(TAG, "Transcode stream error: ${describeError(e)}")
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
                    transcodeToFmp4(inputPath, probeResult, selectedAudioTrack, copyAudio = false, startPositionMs = 0L, pipedOut, listener)
                }
            } catch (e: IOException) {
                AppLogger.info(TAG, "Remux stream ended: ${e.message}")
            } catch (e: Exception) {
                AppLogger.error(TAG, "Remux stream error: ${describeError(e)}")
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
        copyAudio: Boolean,
        startPositionMs: Long,
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
        val startPositionUs = startPositionMs.coerceAtLeast(0) * 1000

        var videoDecoder: MediaCodec? = null
        var videoEncoder: MediaCodec? = null
        var videoExtractor: MediaExtractor? = null
        var videoInputSurface: android.view.Surface? = null
        var outWidth = 0
        var outHeight = 0
        // The actual time the video extractor lands on after seeking (a sync sample).
        // Audio is then seeked to the same time so the two stay in sync.
        var seekLandingUs = startPositionUs

        if (videoTrackInfo != null) {
            videoExtractor = MediaExtractor()
            videoExtractor.setDataSource(inputPath)
            videoExtractor.selectTrack(videoTrackInfo.trackIndex)
            if (startPositionUs > 0) {
                videoExtractor.seekTo(startPositionUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val landed = videoExtractor.sampleTime
                if (landed >= 0) seekLandingUs = landed
                AppLogger.info(TAG, "Seek: video sought to ${startPositionUs / 1000}ms, landed ${seekLandingUs / 1000}ms")
            }
            val inputFormat = videoExtractor.getTrackFormat(videoTrackInfo.trackIndex)

            // 10-bit / HDR HEVC (e.g. Main10) decoded straight onto an 8-bit AVC
            // encoder surface makes some hardware encoders error out. Ask the decoder
            // to tone-map HDR → 8-bit SDR so the surface the encoder receives is 8-bit.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                try {
                    inputFormat.setInteger(
                        MediaFormat.KEY_COLOR_TRANSFER_REQUEST,
                        MediaFormat.COLOR_TRANSFER_SDR_VIDEO
                    )
                } catch (_: Exception) {}
            }

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
            // The encoder MUST be started before the decoder renders any frame onto its
            // input surface; otherwise the encoder receives buffers while not in the
            // executing state and the framework errors/releases it.
            videoEncoder.start()

            videoDecoder = MediaCodec.createDecoderByType(videoTrackInfo.mime)
            videoDecoder.configure(inputFormat, videoInputSurface, null, 0)
            videoDecoder.start()

            AppLogger.info(TAG, "Video transcode (surface): ${videoTrackInfo.codec} ${videoTrackInfo.width}x${videoTrackInfo.height} → H.264 ${outWidth}x${outHeight}")
        }

        var audioDecoder: MediaCodec? = null
        var audioExtractor: MediaExtractor? = null
        var audioStage: AudioStage? = null

        if (audioTrackInfo != null) {
            audioExtractor = MediaExtractor()
            audioExtractor.setDataSource(inputPath)
            audioExtractor.selectTrack(audioTrackInfo.trackIndex)
            if (startPositionUs > 0) {
                // Seek audio to where the video landed so A/V stay aligned.
                audioExtractor.seekTo(seekLandingUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                AppLogger.info(TAG, "Seek: audio sought to ${seekLandingUs / 1000}ms")
            }
            val audioInputFormat = audioExtractor.getTrackFormat(audioTrackInfo.trackIndex)

            // ── Audio copy (passthrough) path ──
            // When the user asked to transcode video only, try to mux the source audio
            // untouched. This preserves the original track (e.g. 5.1 surround) and skips
            // audio decode/encode entirely. Falls back to transcoding when the codec
            // can't be muxed or its config can't be built.
            if (copyAudio) {
                audioStage = tryBuildAudioPassthrough(audioExtractor, audioInputFormat, audioTrackInfo)
            }

            if (audioStage == null) {
                // The source may be multichannel (e.g. 5.1 E-AC-3). Ask the decoder to
                // downmix to stereo so its PCM output matches the 2-channel AAC encoder.
                // Feeding 6-channel PCM into a 2-channel encoder mis-frames the samples
                // and produces badly choppy audio.
                audioInputFormat.setInteger(
                    MediaFormat.KEY_MAX_OUTPUT_CHANNEL_COUNT, OUTPUT_AUDIO_CHANNEL_COUNT
                )

                audioDecoder = MediaCodec.createDecoderByType(audioTrackInfo.mime)
                audioDecoder.configure(audioInputFormat, null, null, 0)
                audioDecoder.start()

                // The AAC encoder is created lazily from the decoder's actual output format
                // (sample rate + channel count) once decoding starts — see
                // AudioTranscodePipeline. This avoids hardcoding 48 kHz/stereo, which would
                // resample-by-mislabel (wrong-speed audio) or mis-frame a mono source.
                audioStage = AudioTranscodePipeline(audioDecoder, audioExtractor)
                AppLogger.info(TAG, "Audio transcode: ${audioTrackInfo.codec} → AAC")
            }
        }

        // The init segment is written lazily by the writer once both encoders report
        // their codec config (which a hardware encoder only emits after the first
        // frame). Configs are captured from INFO_OUTPUT_FORMAT_CHANGED in the pump.
        val writer = Fmp4Writer(out, hasVideo = videoEncoder != null, hasAudio = audioStage != null)

        // A passthrough stage already knows its codec config, so apply it now.
        audioStage?.applyConfigIfKnown(writer)

        try {
            pumpTranscode(
                writer,
                videoExtractor, videoDecoder, videoEncoder, outWidth, outHeight,
                audioStage,
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
            audioStage?.release()
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
        audioStage: AudioStage?,
        durationUs: Long,
        listener: ProgressListener?
    ) {
        val bufferInfo = MediaCodec.BufferInfo()

        var videoInputDone = videoExtractor == null
        var videoDecoderDone = videoDecoder == null
        var videoEncoderEosSent = videoEncoder == null
        var videoEncoderDone = videoEncoder == null
        var lastReportedProgress = -1

        try {
            while (!isCancelled && (!videoEncoderDone || !(audioStage?.isDone ?: true))) {
            // ── Feed video decoder (non-blocking) ──
            if (!videoInputDone && videoExtractor != null && videoDecoder != null) {
                val idx = videoDecoder.dequeueInputBuffer(0)
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

            // ── Decode video → render onto encoder input surface (non-blocking) ──
            if (!videoDecoderDone && videoDecoder != null && videoEncoder != null) {
                val status = videoDecoder.dequeueOutputBuffer(bufferInfo, 0)
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

            // ── Audio (passthrough copy, or decode + AAC encode) ──
            val audioDidWork = audioStage?.step(writer) ?: false

            // The video pipeline's blocking encoder-output drain paces the main run.
            // Once video is finished, only audio remains and its polls are
            // non-blocking, so yield briefly to avoid busy-spinning the tail.
            if (videoEncoderDone && audioStage != null && !audioStage.isDone && !audioDidWork) {
                try { Thread.sleep(2) } catch (_: InterruptedException) {}
            }
            }
        } finally {
            // The stage is released by the caller (transcodeToFmp4).
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

        // Downmix multichannel source audio to stereo so the decoder's PCM output
        // matches the 2-channel AAC encoder (prevents choppy/garbled audio).
        audioInputFormat.setInteger(
            MediaFormat.KEY_MAX_OUTPUT_CHANNEL_COUNT, OUTPUT_AUDIO_CHANNEL_COUNT
        )

        val audioDecoder = MediaCodec.createDecoderByType(selectedAudioTrack.mime)
        audioDecoder.configure(audioInputFormat, null, null, 0)
        audioDecoder.start()
        // The AAC encoder is created lazily from the decoder's actual output format
        // (sample rate + channel count) — see AudioTranscodePipeline.
        AppLogger.info(TAG, "Audio transcode: ${selectedAudioTrack.codec} → AAC")
        val audioStage = AudioTranscodePipeline(audioDecoder, audioExtractor)

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
            pumpRemux(writer, videoExtractor, hasVideo, audioStage, durationUs, listener)
            writer.finish()
            out.flush()
            AppLogger.info(TAG, "Remux+audio-transcode fMP4 stream complete")
        } finally {
            audioStage.release()
            audioExtractor.release()
            videoExtractor.release()
        }
    }

    private fun pumpRemux(
        writer: Fmp4Writer,
        videoExtractor: MediaExtractor,
        hasVideo: Boolean,
        audioStage: AudioStage,
        durationUs: Long,
        listener: ProgressListener?
    ) {
        val sampleBuffer = ByteBuffer.allocate(2 * 1024 * 1024)

        var videoDone = !hasVideo
        var lastReportedProgress = -1

        while (!isCancelled && (!videoDone || !audioStage.isDone)) {
            var didWork = false
            // ── Video passthrough ──
            if (!videoDone) {
                sampleBuffer.clear()
                val sampleSize = videoExtractor.readSampleData(sampleBuffer, 0)
                if (sampleSize < 0) {
                    videoDone = true
                } else {
                    didWork = true
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

            // ── Audio (passthrough copy, or decode + AAC encode) ──
            if (audioStage.step(writer)) didWork = true

            // All polls here are non-blocking; yield when nothing progressed so
            // the loop doesn't busy-spin (e.g. while waiting on the audio tail).
            if (!didWork) {
                try { Thread.sleep(2) } catch (_: InterruptedException) {}
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Codec / encoder helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Buffers decoded PCM and feeds it into the AAC encoder without ever dropping
     * data. A decoded audio buffer can be larger than a single encoder input buffer,
     * so the PCM is queued and drained across as many encoder input buffers as needed.
     * Encoder input timestamps are derived from a running sample-frame counter so the
     * audio timeline is perfectly gapless and evenly spaced (irregular per-buffer
     * timestamps from the decoder were causing choppy playback).
     */
    private inner class AudioEncoderFeeder(
        private val encoder: MediaCodec,
        private val sampleRate: Int,
        private val channels: Int
    ) {
        private val pcmQueue = ArrayDeque<ByteArray>()
        private var headOffset = 0
        private val bytesPerFrame = channels * 2 // 16-bit PCM
        private var basePtsUs = -1L
        private var framesSent = 0L
        private var eosQueued = false

        fun enqueuePcm(data: ByteArray, ptsUs: Long) {
            if (basePtsUs < 0) basePtsUs = ptsUs
            if (data.isNotEmpty()) pcmQueue.addLast(data)
        }

        private fun ptsForNextChunk(): Long {
            val base = if (basePtsUs < 0) 0L else basePtsUs
            return base + framesSent * 1_000_000L / sampleRate
        }

        /** Drains the PCM queue into the encoder; queues EOS once drained if [decoderDone]. */
        fun pump(decoderDone: Boolean) {
            while (true) {
                if (pcmQueue.isEmpty()) {
                    if (decoderDone && !eosQueued) {
                        val idx = encoder.dequeueInputBuffer(0)
                        if (idx >= 0) {
                            encoder.queueInputBuffer(
                                idx, 0, 0, ptsForNextChunk(), MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            eosQueued = true
                        }
                    }
                    return
                }
                val idx = encoder.dequeueInputBuffer(0)
                if (idx < 0) return
                val encBuf = encoder.getInputBuffer(idx)
                if (encBuf == null) {
                    encoder.queueInputBuffer(idx, 0, 0, ptsForNextChunk(), 0)
                    return
                }
                encBuf.clear()
                val cap = encBuf.remaining()
                val usableCap = cap - (cap % bytesPerFrame)
                val pts = ptsForNextChunk()
                var written = 0
                while (written < usableCap && pcmQueue.isNotEmpty()) {
                    val head = pcmQueue.first()
                    val avail = head.size - headOffset
                    val toCopy = minOf(avail, usableCap - written)
                    encBuf.put(head, headOffset, toCopy)
                    headOffset += toCopy
                    written += toCopy
                    if (headOffset >= head.size) {
                        pcmQueue.removeFirst()
                        headOffset = 0
                    }
                }
                if (written > 0) {
                    encoder.queueInputBuffer(idx, 0, written, pts, 0)
                    framesSent += (written / bytesPerFrame).toLong()
                } else {
                    encoder.queueInputBuffer(idx, 0, 0, pts, 0)
                    return
                }
            }
        }
    }

    /**
     * One audio track's contribution to the fMP4 output. Either re-encodes to AAC
     * ([AudioTranscodePipeline]) or copies the source bitstream untouched
     * ([AudioPassthroughStage]). All methods use non-blocking codec polls so the
     * single-threaded pump never stalls the video pipeline.
     */
    private interface AudioStage {
        val isDone: Boolean
        /** Applies a codec config that is already known up front (passthrough). */
        fun applyConfigIfKnown(writer: Fmp4Writer) {}
        /** Advances the audio one iteration; returns true if any work happened. */
        fun step(writer: Fmp4Writer): Boolean
        fun release()
    }

    /**
     * Copies the source audio elementary stream straight into the fMP4 (no re-encode),
     * preserving the original codec/track (e.g. 5.1 surround). The codec config is
     * resolved up front by [tryBuildAudioPassthrough].
     */
    private inner class AudioPassthroughStage(
        private val extractor: MediaExtractor,
        private val codec: Fmp4Writer.AudioCodec,
        private val codecData: ByteArray,
        private val sampleRate: Int,
        private val channels: Int
    ) : AudioStage {
        private val sampleBuffer = ByteBuffer.allocate(512 * 1024)
        private var done = false

        override val isDone: Boolean get() = done

        override fun applyConfigIfKnown(writer: Fmp4Writer) {
            writer.setAudioConfig(codec, codecData, sampleRate, channels)
            AppLogger.info(TAG, "Audio copy (passthrough): $codec ${sampleRate}Hz ${channels}ch")
        }

        override fun step(writer: Fmp4Writer): Boolean {
            if (done) return false
            sampleBuffer.clear()
            val size = extractor.readSampleData(sampleBuffer, 0)
            if (size < 0) {
                done = true
                return false
            }
            val ptsUs = extractor.sampleTime
            val data = ByteArray(size)
            sampleBuffer.position(0)
            sampleBuffer.get(data, 0, size)
            writer.addAudioSample(data, ptsUs)
            extractor.advance()
            return true
        }

        override fun release() {}
    }

    /**
     * Attempts to set up audio passthrough for the selected track. Returns a stage when
     * the source codec can be muxed directly (AAC, AC-3, E-AC-3) and its config is
     * available; otherwise returns null so the caller falls back to transcoding.
     */
    private fun tryBuildAudioPassthrough(
        extractor: MediaExtractor,
        format: MediaFormat,
        track: AudioTrackInfo
    ): AudioStage? {
        val mime = (format.getString(MediaFormat.KEY_MIME) ?: track.mime).lowercase()
        val sampleRate = getIntSafe(format, MediaFormat.KEY_SAMPLE_RATE, track.sampleRate)
        val channels = getIntSafe(format, MediaFormat.KEY_CHANNEL_COUNT, track.channelCount)
        if (sampleRate <= 0 || channels <= 0) return null

        return when {
            mime.contains("mp4a") || mime.contains("aac") -> {
                val asc = getCsdBytes(format, 0)
                if (asc == null || asc.isEmpty()) {
                    AppLogger.warn(TAG, "Audio copy: AAC has no codec config, will transcode")
                    null
                } else {
                    AudioPassthroughStage(extractor, Fmp4Writer.AudioCodec.AAC, asc, sampleRate, channels)
                }
            }
            mime.contains("eac3") || mime.contains("ec3") || mime.contains("ec-3") -> {
                val frame = peekFirstSample(extractor) ?: return null
                val dec3 = DolbyAudioConfig.buildDec3(frame)
                if (dec3 == null) {
                    AppLogger.warn(TAG, "Audio copy: could not build dec3 for E-AC-3, will transcode")
                    null
                } else {
                    AudioPassthroughStage(extractor, Fmp4Writer.AudioCodec.EAC3, dec3, sampleRate, channels)
                }
            }
            mime.contains("ac3") || mime.contains("ac-3") -> {
                val frame = peekFirstSample(extractor) ?: return null
                val dac3 = DolbyAudioConfig.buildDac3(frame)
                if (dac3 == null) {
                    AppLogger.warn(TAG, "Audio copy: could not build dac3 for AC-3, will transcode")
                    null
                } else {
                    AudioPassthroughStage(extractor, Fmp4Writer.AudioCodec.AC3, dac3, sampleRate, channels)
                }
            }
            else -> {
                AppLogger.info(TAG, "Audio copy: $mime not muxable into MP4, will transcode to AAC")
                null
            }
        }
    }

    /**
     * Reads the first sample's bytes without consuming the extractor position (the
     * stage re-reads it), so a Dolby config can be parsed before streaming starts.
     */
    private fun peekFirstSample(extractor: MediaExtractor): ByteArray? {
        val buf = ByteBuffer.allocate(64 * 1024)
        val size = extractor.readSampleData(buf, 0)
        if (size <= 0) return null
        val data = ByteArray(size)
        buf.position(0)
        buf.get(data, 0, size)
        // Do not advance(): the passthrough stage reads this same first frame.
        return data
    }

    /**
     * Owns the audio decode → AAC encode side. The encoder is created **lazily** from
     * the decoder's actual output format (sample rate + channel count) the first time
     * the decoder produces output, rather than from hardcoded constants. This keeps the
     * AAC output at the source sample rate (no accidental resampling/wrong-speed audio)
     * and matches the post-downmix channel count exactly (mono sources, or decoders
     * that don't honour the stereo-downmix request, no longer mis-frame the samples).
     */
    private inner class AudioTranscodePipeline(
        private val decoder: MediaCodec,
        private val extractor: MediaExtractor
    ) : AudioStage {
        private val info = MediaCodec.BufferInfo()
        private var encoder: MediaCodec? = null
        private var feeder: AudioEncoderFeeder? = null
        private var sampleRate = OUTPUT_AUDIO_SAMPLE_RATE
        private var channels = OUTPUT_AUDIO_CHANNEL_COUNT
        private var inputDone = false
        private var decoderDone = false
        private var encoderDone = false

        override val isDone: Boolean get() = encoderDone

        override fun step(writer: Fmp4Writer): Boolean {
            feedInput()
            return pump(writer)
        }

        override fun release() {
            try { decoder.stop() } catch (_: Exception) {}
            try { decoder.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
        }

        private fun ensureEncoder() {
            if (encoder != null) return
            val outFmt = decoder.outputFormat
            sampleRate = getIntSafe(outFmt, MediaFormat.KEY_SAMPLE_RATE, OUTPUT_AUDIO_SAMPLE_RATE)
            channels = getIntSafe(outFmt, MediaFormat.KEY_CHANNEL_COUNT, OUTPUT_AUDIO_CHANNEL_COUNT)
            val fmt = MediaFormat.createAudioFormat(OUTPUT_AUDIO_MIME, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            val enc = MediaCodec.createEncoderByType(OUTPUT_AUDIO_MIME)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            encoder = enc
            feeder = AudioEncoderFeeder(enc, sampleRate, channels)
            AppLogger.info(TAG, "Audio encoder ready: AAC ${sampleRate}Hz ${channels}ch")
        }

        /** Feeds one compressed sample from the extractor into the audio decoder. */
        private fun feedInput() {
            if (inputDone) return
            val idx = decoder.dequeueInputBuffer(0)
            if (idx >= 0) {
                val buf = decoder.getInputBuffer(idx)
                if (buf != null) {
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
        }

        /**
         * Drains decoder → PCM feeder → encoder → [writer] for one iteration.
         * Uses non-blocking polls so the (single-threaded) caller's video pipeline is
         * never stalled waiting on audio. Returns true if any audio work happened, so
         * the caller can idle-sleep instead of busy-spinning when nothing progressed.
         */
        private fun pump(writer: Fmp4Writer): Boolean {
            var didWork = false
            if (!decoderDone) {
                val status = decoder.dequeueOutputBuffer(info, 0)
                if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    ensureEncoder()
                    val ch = getIntSafe(decoder.outputFormat, MediaFormat.KEY_CHANNEL_COUNT, -1)
                    AppLogger.info(TAG, "Audio decoder output: ${ch}ch (downmix target $OUTPUT_AUDIO_CHANNEL_COUNT)")
                    didWork = true
                } else if (status >= 0) {
                    didWork = true
                    val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (info.size > 0) {
                        ensureEncoder()
                        val decoded = decoder.getOutputBuffer(status)
                        if (decoded != null) {
                            val data = ByteArray(info.size)
                            decoded.position(info.offset)
                            decoded.get(data, 0, info.size)
                            feeder?.enqueuePcm(data, info.presentationTimeUs)
                        }
                    }
                    decoder.releaseOutputBuffer(status, false)
                    if (isEos) decoderDone = true
                }
            }

            feeder?.pump(decoderDone)

            val enc = encoder
            if (enc != null && !encoderDone) {
                val es = enc.dequeueOutputBuffer(info, 0)
                if (es == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    captureAudioConfig(writer, enc.outputFormat)
                    didWork = true
                } else if (es >= 0) {
                    didWork = true
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val encodedBuf = enc.getOutputBuffer(es)
                    if (isConfig) {
                        if (!writer.hasAudioConfig() && encodedBuf != null) {
                            val cfg = ByteArray(info.size)
                            encodedBuf.position(info.offset)
                            encodedBuf.get(cfg, 0, info.size)
                            writer.setAudioConfig(cfg, sampleRate, channels)
                        }
                        info.size = 0
                    }
                    if (info.size > 0 && encodedBuf != null) {
                        val data = ByteArray(info.size)
                        encodedBuf.position(info.offset)
                        encodedBuf.get(data, 0, info.size)
                        writer.addAudioSample(data, info.presentationTimeUs)
                    }
                    enc.releaseOutputBuffer(es, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                }
            }
            return didWork
        }
    }

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

    /** Formats an exception with its type and top stack frames for precise diagnosis. */
    private fun describeError(e: Throwable): String {
        val top = e.stackTrace.take(4).joinToString(" <- ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
        return "${e.javaClass.simpleName}: ${e.message} @ $top"
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
