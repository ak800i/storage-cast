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
 * Streaming transcoder that outputs MKV (Matroska) format via PipedInputStream.
 * Decodes and re-encodes video (→ H.264) and audio (→ AAC) while writing MKV data
 * to a pipe, allowing Cast streaming to start before the full transcode is complete.
 *
 * For video-passthrough + audio-transcode, use [createRemuxWithAudioTranscodeStream]
 * which copies the video track as-is and only re-encodes the audio.
 */
class TranscodeStreamer {

    companion object {
        private const val TAG = "TranscodeStreamer"
        private const val PIPE_BUFFER_SIZE = 2 * 1024 * 1024 // 2 MB

        // Transcode settings (matching VideoTranscoder)
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
            "audio/mp4a-latm" to "A_AAC",
        )
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
     * as a Matroska (MKV) container. Transcoding runs in a background thread;
     * the returned stream can be served immediately by the HTTP server.
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
                transcodeToMkv(inputPath, probeResult, selectedAudioTrack, pipedOut, listener)
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
     * Creates an InputStream that remuxes video (passthrough, no re-encoding) and
     * transcodes audio (→ AAC) as a Matroska (MKV) container. Much faster than full
     * transcode since video is just copied.
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

        Thread {
            try {
                remuxWithAudioTranscodeToMkv(inputPath, probeResult, selectedAudioTrack, pipedOut, listener)
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

    // ──── Full Transcode (video + audio) → MKV ────

    private fun transcodeToMkv(
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

        // ── Set up video decoder + encoder ──
        var videoDecoder: MediaCodec? = null
        var videoEncoder: MediaCodec? = null
        var videoExtractor: MediaExtractor? = null
        var videoEncoderOutputFormat: MediaFormat? = null

        if (videoTrackInfo != null) {
            videoExtractor = MediaExtractor()
            videoExtractor.setDataSource(inputPath)
            videoExtractor.selectTrack(videoTrackInfo.trackIndex)
            val inputFormat = videoExtractor.getTrackFormat(videoTrackInfo.trackIndex)

            // Determine output dimensions (cap at 1080p)
            val inWidth = videoTrackInfo.width
            val inHeight = videoTrackInfo.height
            if (inWidth <= 0 || inHeight <= 0) {
                throw IllegalArgumentException("Invalid video dimensions: ${inWidth}x${inHeight}")
            }
            val scaleFactor = minOf(MAX_WIDTH.toFloat() / inWidth, MAX_HEIGHT.toFloat() / inHeight, 1f)
            val outWidth = ((inWidth * scaleFactor).toInt() / 2) * 2
            val outHeight = ((inHeight * scaleFactor).toInt() / 2) * 2

            val outputBitrate = if (videoTrackInfo.bitrate > 0) {
                minOf(videoTrackInfo.bitrate, OUTPUT_VIDEO_BITRATE)
            } else OUTPUT_VIDEO_BITRATE
            val outputFrameRate = if (videoTrackInfo.frameRate > 0) {
                videoTrackInfo.frameRate.toInt().coerceAtMost(OUTPUT_VIDEO_FRAME_RATE)
            } else OUTPUT_VIDEO_FRAME_RATE

            videoDecoder = MediaCodec.createDecoderByType(videoTrackInfo.mime)
            videoDecoder.configure(inputFormat, null, null, 0)

            val videoOutputFormat = MediaFormat.createVideoFormat(OUTPUT_VIDEO_MIME, outWidth, outHeight).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, outputBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, outputFrameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, OUTPUT_VIDEO_IFRAME_INTERVAL)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            }

            videoEncoder = try {
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
            videoEncoder.configure(videoOutputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            videoDecoder.start()
            videoEncoder.start()

            AppLogger.info(TAG, "Video transcode: ${videoTrackInfo.codec} ${videoTrackInfo.width}x${videoTrackInfo.height} → H.264 ${outWidth}x${outHeight}")

            // Pump encoder to get output format (needed for MKV track header)
            videoEncoderOutputFormat = pumpEncoderForOutputFormat(videoEncoder)
            AppLogger.info(TAG, "Video encoder format ready")
        }

        // ── Set up audio decoder + encoder ──
        var audioDecoder: MediaCodec? = null
        var audioEncoder: MediaCodec? = null
        var audioExtractor: MediaExtractor? = null
        var audioEncoderOutputFormat: MediaFormat? = null

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

            audioEncoderOutputFormat = pumpEncoderForOutputFormat(audioEncoder)
            AppLogger.info(TAG, "Audio encoder format ready")
        }

        try {
            // ── Write MKV header ──
            writeEbmlHeader(out)
            writeElementId(out, SEGMENT)
            writeUnknownSize(out)
            writeSegmentInfo(out)

            // ── Write Tracks element ──
            writeTracksFromEncoderFormats(out, videoEncoderOutputFormat, audioEncoderOutputFormat)

            // ── Interleaved transcode → write MKV clusters ──
            writeTranscodedClusters(
                out,
                videoExtractor, videoDecoder, videoEncoder,
                audioExtractor, audioDecoder, audioEncoder,
                durationUs, listener
            )

            out.flush()
            AppLogger.info(TAG, "Transcode MKV stream complete")
        } finally {
            videoDecoder?.stop(); videoDecoder?.release()
            videoEncoder?.stop(); videoEncoder?.release()
            videoExtractor?.release()
            audioDecoder?.stop(); audioDecoder?.release()
            audioEncoder?.stop(); audioEncoder?.release()
            audioExtractor?.release()
        }
    }

    /**
     * Pumps an encoder until it emits INFO_OUTPUT_FORMAT_CHANGED, then returns the format.
     * Some encoders need at least one input sample before they produce the output format.
     */
    private fun pumpEncoderForOutputFormat(encoder: MediaCodec): MediaFormat {
        val bufferInfo = MediaCodec.BufferInfo()
        for (i in 0 until 100) {
            val status = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                return encoder.outputFormat
            }
            if (status >= 0) {
                encoder.releaseOutputBuffer(status, false)
            }
        }
        // If format not obtained yet, return the configured format
        return encoder.outputFormat
    }

    private fun writeTranscodedClusters(
        out: OutputStream,
        videoExtractor: MediaExtractor?,
        videoDecoder: MediaCodec?,
        videoEncoder: MediaCodec?,
        audioExtractor: MediaExtractor?,
        audioDecoder: MediaCodec?,
        audioEncoder: MediaCodec?,
        durationUs: Long,
        listener: ProgressListener?
    ) {
        val clusterBuffer = ByteArrayOutputStream()
        var clusterTimecodeMs = -1L
        val bufferInfo = MediaCodec.BufferInfo()

        var videoInputDone = videoExtractor == null
        var videoDecoderDone = videoDecoder == null
        var videoEncoderEosSent = videoEncoder == null
        var audioInputDone = audioExtractor == null
        var audioDecoderDone = audioDecoder == null
        var audioEncoderEosSent = audioEncoder == null
        var lastReportedProgress = -1
        var allDone = false

        while (!isCancelled && !allDone) {
            // ── Feed video decoder ──
            if (!videoInputDone && videoExtractor != null && videoDecoder != null) {
                val idx = videoDecoder.dequeueInputBuffer(TIMEOUT_US)
                if (idx >= 0) {
                    val buf = videoDecoder.getInputBuffer(idx) ?: continue
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

            // ── Decode video → encode ──
            if (!videoDecoderDone && videoDecoder != null && videoEncoder != null) {
                val status = videoDecoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (status >= 0) {
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (bufferInfo.size > 0) {
                        val decoded = videoDecoder.getOutputBuffer(status)
                        if (decoded != null) {
                            val encIdx = videoEncoder.dequeueInputBuffer(TIMEOUT_US)
                            if (encIdx >= 0) {
                                val encBuf = videoEncoder.getInputBuffer(encIdx)
                                if (encBuf != null) {
                                    encBuf.clear()
                                    val limit = minOf(decoded.remaining(), encBuf.remaining())
                                    val temp = ByteArray(limit)
                                    decoded.get(temp, 0, limit)
                                    encBuf.put(temp, 0, limit)
                                    videoEncoder.queueInputBuffer(
                                        encIdx, 0, limit, bufferInfo.presentationTimeUs,
                                        if (isEos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                                    )
                                }
                            }
                        }
                    }
                    videoDecoder.releaseOutputBuffer(status, false)
                    if (isEos) videoDecoderDone = true
                }
            }

            // ── Drain video encoder → write to MKV ──
            if (videoEncoder != null) {
                val encStatus = videoEncoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (encStatus >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0) {
                        val encodedBuf = videoEncoder.getOutputBuffer(encStatus)
                        if (encodedBuf != null) {
                            val sampleTimeMs = bufferInfo.presentationTimeUs / 1000
                            val isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                            val clusterAge = if (clusterTimecodeMs >= 0) sampleTimeMs - clusterTimecodeMs else 0
                            val shouldStartNewCluster = clusterTimecodeMs < 0 ||
                                (isKeyframe && clusterAge >= MIN_CLUSTER_DURATION_MS) ||
                                clusterAge > MAX_CLUSTER_DURATION_MS

                            if (shouldStartNewCluster) {
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

                            val relativeTimeMs = (sampleTimeMs - clusterTimecodeMs).toInt().coerceIn(-32768, 32767)
                            val data = ByteArray(bufferInfo.size)
                            encodedBuf.position(bufferInfo.offset)
                            encodedBuf.get(data, 0, bufferInfo.size)
                            writeSimpleBlock(clusterBuffer, 1, relativeTimeMs, isKeyframe, data)
                        }
                    }
                    videoEncoder.releaseOutputBuffer(encStatus, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        // Video encoding done
                    }
                }

                if (videoDecoderDone && !videoEncoderEosSent && encStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    val idx = videoEncoder.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        videoEncoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        videoEncoderEosSent = true
                    }
                }
            }

            // ── Feed audio decoder ──
            if (!audioInputDone && audioExtractor != null && audioDecoder != null) {
                val idx = audioDecoder.dequeueInputBuffer(TIMEOUT_US)
                if (idx >= 0) {
                    val buf = audioDecoder.getInputBuffer(idx) ?: continue
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

            // ── Decode audio → encode ──
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
                                }
                            }
                        }
                    }
                    audioDecoder.releaseOutputBuffer(status, false)
                    if (isEos) audioDecoderDone = true
                }
            }

            // ── Drain audio encoder → write to MKV ──
            if (audioEncoder != null) {
                val encStatus = audioEncoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (encStatus >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0) {
                        val encodedBuf = audioEncoder.getOutputBuffer(encStatus)
                        if (encodedBuf != null) {
                            val sampleTimeMs = bufferInfo.presentationTimeUs / 1000

                            // Audio blocks go into the current cluster
                            if (clusterTimecodeMs < 0) {
                                clusterTimecodeMs = sampleTimeMs
                                writeUintElement(clusterBuffer, CLUSTER_TIMECODE, clusterTimecodeMs)
                            }

                            val relativeTimeMs = (sampleTimeMs - clusterTimecodeMs).toInt().coerceIn(-32768, 32767)
                            val data = ByteArray(bufferInfo.size)
                            encodedBuf.position(bufferInfo.offset)
                            encodedBuf.get(data, 0, bufferInfo.size)
                            writeSimpleBlock(clusterBuffer, 2, relativeTimeMs, false, data)
                        }
                    }
                    audioEncoder.releaseOutputBuffer(encStatus, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        // Audio encoding done
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

            // Check if all done
            val videoDone = videoEncoder == null || (videoEncoderEosSent && videoDecoderDone)
            val audioDone = audioEncoder == null || (audioEncoderEosSent && audioDecoderDone)
            if (videoDone && audioDone) {
                // Drain remaining encoder output
                var drained = false
                if (videoEncoder != null) {
                    val s = videoEncoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (s >= 0) {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) drained = true
                        videoEncoder.releaseOutputBuffer(s, false)
                    } else {
                        drained = true
                    }
                } else {
                    drained = true
                }
                if (audioEncoder != null && drained) {
                    val s = audioEncoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (s >= 0) {
                        audioEncoder.releaseOutputBuffer(s, false)
                    }
                    allDone = true
                } else if (drained) {
                    allDone = true
                }
            }
        }

        // Flush final cluster
        if (clusterBuffer.size() > 0) {
            writeElementId(out, CLUSTER)
            writeElementSize(out, clusterBuffer.size().toLong())
            clusterBuffer.writeTo(out)
            out.flush()
        }
    }

    // ──── Video Passthrough + Audio Transcode → MKV ────

    private fun remuxWithAudioTranscodeToMkv(
        inputPath: String,
        probeResult: MediaProbeResult,
        selectedAudioTrack: AudioTrackInfo,
        output: OutputStream,
        listener: ProgressListener?
    ) {
        val out = output.buffered(65536)
        val videoTrack = probeResult.primaryVideo
        val durationUs = if (probeResult.durationMs > 0) probeResult.durationMs * 1000 else 0L

        // ── Set up audio transcode pipeline ──
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
        val audioEncoderOutputFormat = pumpEncoderForOutputFormat(audioEncoder)

        // ── Set up video passthrough ──
        val videoExtractor = MediaExtractor()
        videoExtractor.setDataSource(inputPath)
        var videoFormat: MediaFormat? = null
        var videoMime: String? = null
        if (videoTrack != null) {
            videoExtractor.selectTrack(videoTrack.trackIndex)
            videoFormat = videoExtractor.getTrackFormat(videoTrack.trackIndex)
            videoMime = videoFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            AppLogger.info(TAG, "Video passthrough: ${videoTrack.codec} ${videoTrack.width}x${videoTrack.height}")
        }

        try {
            // ── Write MKV header ──
            writeEbmlHeader(out)
            writeElementId(out, SEGMENT)
            writeUnknownSize(out)
            writeSegmentInfo(out)

            // ── Write Tracks element ──
            val tracksContent = ByteArrayOutputStream()
            if (videoFormat != null && videoMime != null) {
                writeVideoTrackEntryFromFormat(tracksContent, videoFormat, videoMime)
            }
            writeAudioTrackEntryFromEncoderFormat(tracksContent, audioEncoderOutputFormat)
            writeElementId(out, TRACKS)
            writeElementSize(out, tracksContent.size().toLong())
            tracksContent.writeTo(out)

            // ── Interleaved write: video passthrough + audio transcode ──
            writeRemuxClusters(
                out, videoExtractor, videoTrack?.trackIndex ?: -1,
                audioExtractor, audioDecoder, audioEncoder,
                durationUs, listener
            )

            out.flush()
            AppLogger.info(TAG, "Remux with audio transcode MKV stream complete")
        } finally {
            audioDecoder.stop(); audioDecoder.release()
            audioEncoder.stop(); audioEncoder.release()
            audioExtractor.release()
            videoExtractor.release()
        }
    }

    private fun writeRemuxClusters(
        out: OutputStream,
        videoExtractor: MediaExtractor,
        videoTrackIndex: Int,
        audioExtractor: MediaExtractor,
        audioDecoder: MediaCodec,
        audioEncoder: MediaCodec,
        durationUs: Long,
        listener: ProgressListener?
    ) {
        val clusterBuffer = ByteArrayOutputStream()
        var clusterTimecodeMs = -1L
        val sampleBuffer = ByteBuffer.allocate(1024 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()

        var videoPassthroughDone = videoTrackIndex < 0
        var audioInputDone = false
        var audioDecoderDone = false
        var audioEncoderEosSent = false
        var lastReportedProgress = -1

        while (!isCancelled) {
            // ── Video passthrough ──
            if (!videoPassthroughDone) {
                sampleBuffer.clear()
                val sampleSize = videoExtractor.readSampleData(sampleBuffer, 0)
                if (sampleSize < 0) {
                    videoPassthroughDone = true
                } else {
                    val sampleTimeMs = videoExtractor.sampleTime / 1000
                    val isKeyframe = (videoExtractor.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0

                    val clusterAge = if (clusterTimecodeMs >= 0) sampleTimeMs - clusterTimecodeMs else 0
                    val shouldStartNewCluster = clusterTimecodeMs < 0 ||
                        (isKeyframe && clusterAge >= MIN_CLUSTER_DURATION_MS) ||
                        clusterAge > MAX_CLUSTER_DURATION_MS

                    if (shouldStartNewCluster) {
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

                    val relativeTimeMs = (sampleTimeMs - clusterTimecodeMs).toInt().coerceIn(-32768, 32767)
                    val data = ByteArray(sampleSize)
                    sampleBuffer.position(0)
                    sampleBuffer.get(data, 0, sampleSize)
                    writeSimpleBlock(clusterBuffer, 1, relativeTimeMs, isKeyframe, data)
                    videoExtractor.advance()

                    if (durationUs > 0) {
                        val progress = ((videoExtractor.sampleTime * 100) / durationUs).toInt().coerceIn(0, 100)
                        if (progress != lastReportedProgress) {
                            lastReportedProgress = progress
                            listener?.onProgress(progress)
                        }
                    }
                }
            }

            // ── Audio decode → encode → write ──
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
                                }
                            }
                        }
                    }
                    audioDecoder.releaseOutputBuffer(status, false)
                    if (isEos) audioDecoderDone = true
                }
            }

            val encStatus = audioEncoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (encStatus >= 0) {
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    bufferInfo.size = 0
                }
                if (bufferInfo.size > 0) {
                    val encodedBuf = audioEncoder.getOutputBuffer(encStatus)
                    if (encodedBuf != null) {
                        val sampleTimeMs = bufferInfo.presentationTimeUs / 1000
                        if (clusterTimecodeMs < 0) {
                            clusterTimecodeMs = sampleTimeMs
                            writeUintElement(clusterBuffer, CLUSTER_TIMECODE, clusterTimecodeMs)
                        }
                        val relativeTimeMs = (sampleTimeMs - clusterTimecodeMs).toInt().coerceIn(-32768, 32767)
                        val data = ByteArray(bufferInfo.size)
                        encodedBuf.position(bufferInfo.offset)
                        encodedBuf.get(data, 0, bufferInfo.size)
                        writeSimpleBlock(clusterBuffer, 2, relativeTimeMs, false, data)
                    }
                }
                audioEncoder.releaseOutputBuffer(encStatus, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 && videoPassthroughDone) {
                    break
                }
            }

            if (audioDecoderDone && !audioEncoderEosSent && encStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                val idx = audioEncoder.dequeueInputBuffer(TIMEOUT_US)
                if (idx >= 0) {
                    audioEncoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    audioEncoderEosSent = true
                }
            }

            if (videoPassthroughDone && audioEncoderEosSent && audioDecoderDone) {
                // Drain remaining audio encoder output
                val s = audioEncoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (s >= 0) {
                    audioEncoder.releaseOutputBuffer(s, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (s == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break
                }
            }
        }

        // Flush final cluster
        if (clusterBuffer.size() > 0) {
            writeElementId(out, CLUSTER)
            writeElementSize(out, clusterBuffer.size().toLong())
            clusterBuffer.writeTo(out)
            out.flush()
        }
    }

    // ──── MKV Track Writing ────

    private fun writeTracksFromEncoderFormats(
        out: OutputStream,
        videoFormat: MediaFormat?,
        audioFormat: MediaFormat?
    ) {
        val content = ByteArrayOutputStream()

        if (videoFormat != null) {
            val mime = videoFormat.getString(MediaFormat.KEY_MIME) ?: OUTPUT_VIDEO_MIME
            writeVideoTrackEntryFromFormat(content, videoFormat, mime)
        }
        if (audioFormat != null) {
            writeAudioTrackEntryFromEncoderFormat(content, audioFormat)
        }

        writeElementId(out, TRACKS)
        writeElementSize(out, content.size().toLong())
        content.writeTo(out)
    }

    private fun writeVideoTrackEntryFromFormat(out: OutputStream, format: MediaFormat, mime: String) {
        val entry = ByteArrayOutputStream()

        writeUintElement(entry, TRACK_NUMBER, 1)
        writeUintElement(entry, TRACK_UID, 1)
        writeUintElement(entry, TRACK_TYPE, TRACK_TYPE_VIDEO.toLong())
        writeStringElement(entry, CODEC_ID, MIME_TO_CODEC_ID[mime] ?: "V_MPEG4/ISO/AVC")

        // CodecPrivate from encoder output format (csd-0/csd-1)
        val codecPrivate = buildVideoCodecPrivate(format, mime)
        if (codecPrivate.isNotEmpty()) {
            writeBinaryElement(entry, CODEC_PRIVATE, codecPrivate)
        }

        // Video element
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

    private fun writeAudioTrackEntryFromEncoderFormat(out: OutputStream, format: MediaFormat) {
        val entry = ByteArrayOutputStream()

        writeUintElement(entry, TRACK_NUMBER, 2)
        writeUintElement(entry, TRACK_UID, 2)
        writeUintElement(entry, TRACK_TYPE, TRACK_TYPE_AUDIO.toLong())
        writeStringElement(entry, CODEC_ID, "A_AAC")

        // CodecPrivate for AAC
        val codecPrivate = getCsdBytes(format, 0) ?: ByteArray(0)
        if (codecPrivate.isNotEmpty()) {
            writeBinaryElement(entry, CODEC_PRIVATE, codecPrivate)
        }

        // Audio element
        val audioContent = ByteArrayOutputStream()
        val sampleRate = getIntSafe(format, MediaFormat.KEY_SAMPLE_RATE, OUTPUT_AUDIO_SAMPLE_RATE)
        val channels = getIntSafe(format, MediaFormat.KEY_CHANNEL_COUNT, OUTPUT_AUDIO_CHANNEL_COUNT)
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
            else -> getCsdBytes(format, 0) ?: ByteArray(0)
        }
    }

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
        output.write(1)                          // configurationVersion
        output.write(sps[1].toInt() and 0xFF)    // AVCProfileIndication
        output.write(sps[2].toInt() and 0xFF)    // profile_compatibility
        output.write(sps[3].toInt() and 0xFF)    // AVCLevelIndication
        output.write(0xFF)                       // lengthSizeMinusOne = 3

        output.write(0xE0 or spsNalus.size)
        for (nalu in spsNalus) {
            output.write((nalu.size shr 8) and 0xFF)
            output.write(nalu.size and 0xFF)
            output.write(nalu)
        }

        output.write(ppsNalus.size)
        for (nalu in ppsNalus) {
            output.write((nalu.size shr 8) and 0xFF)
            output.write(nalu.size and 0xFF)
            output.write(nalu)
        }

        return output.toByteArray()
    }

    private fun parseAnnexBNalus(buffer: ByteBuffer): List<ByteArray> {
        val data = ByteArray(buffer.remaining())
        val pos = buffer.position()
        buffer.get(data)
        buffer.position(pos)

        val nalus = mutableListOf<ByteArray>()
        var i = 0

        while (i < data.size) {
            val scLen = startCodeLength(data, i)
            if (scLen == 0) { i++; continue }

            val naluStart = i + scLen
            var naluEnd = data.size
            for (j in naluStart until data.size) {
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
        if (offset + 4 <= data.size &&
            data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
            data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()) return 4
        if (offset + 3 <= data.size &&
            data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
            data[offset + 2] == 1.toByte()) return 3
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
        } catch (e: Exception) { null }
    }

    private fun getIntSafe(format: MediaFormat, key: String, default: Int): Int {
        return try {
            if (format.containsKey(key)) format.getInteger(key) else default
        } catch (e: Exception) { default }
    }

    // ──── EBML Writing ────

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

    private fun writeSegmentInfo(out: OutputStream) {
        val content = ByteArrayOutputStream()
        writeUintElement(content, TIMECODE_SCALE, 1_000_000)
        writeUtf8Element(content, MUXING_APP, "StorageCast")
        writeUtf8Element(content, WRITING_APP, "StorageCast")

        writeElementId(out, INFO)
        writeElementSize(out, content.size().toLong())
        content.writeTo(out)
    }

    private fun writeSimpleBlock(
        out: OutputStream, trackNumber: Int, relativeTimeMs: Int, keyframe: Boolean, data: ByteArray
    ) {
        val trackVint = encodeTrackVint(trackNumber)
        val totalSize = trackVint.size + 2 + 1 + data.size

        writeElementId(out, SIMPLE_BLOCK)
        writeElementSize(out, totalSize.toLong())
        out.write(trackVint)
        out.write((relativeTimeMs shr 8) and 0xFF)
        out.write(relativeTimeMs and 0xFF)
        out.write(if (keyframe) 0x80 else 0x00)
        out.write(data)
    }

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
        if (size < 0) { writeUnknownSize(out); return }
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

    private fun selectHardwareEncoder(mime: String): String? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return codecList.codecInfos
            .filter { it.isEncoder && it.isHardwareAccelerated }
            .firstOrNull { info ->
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }?.name
    }
}
