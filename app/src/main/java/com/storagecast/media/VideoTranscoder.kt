package com.storagecast.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.storagecast.log.AppLogger
import java.io.File
import java.nio.ByteBuffer

/**
 * Transcodes video files using hardware-accelerated MediaCodec.
 * Re-encodes unsupported video to H.264 and unsupported audio to AAC
 * in an MP4 container suitable for Cast devices.
 */
class VideoTranscoder {

    companion object {
        private const val TAG = "VideoTranscoder"
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

        /** Audio MIME types that MediaMuxer supports for MP4 passthrough (no re-encoding). */
        private val REMUXABLE_AUDIO_MIMES = setOf(
            "audio/mp4a-latm"  // AAC
        )

        /** Returns true if the audio track can be passthrough-muxed into MP4. */
        fun canRemuxAudio(audioTrack: AudioTrackInfo): Boolean {
            return audioTrack.mime in REMUXABLE_AUDIO_MIMES
        }
    }

    interface ProgressListener {
        fun onProgress(percent: Int)
        fun onCompleted(outputFile: File)
        fun onError(error: String)
    }

    @Volatile
    private var isCancelled = false

    fun cancel() {
        isCancelled = true
    }

    fun transcode(
        inputPath: String,
        outputDir: File,
        probeResult: MediaProbeResult,
        listener: ProgressListener,
        selectedAudioTrack: AudioTrackInfo? = null
    ) {
        isCancelled = false
        val outputFile = File(outputDir, "transcode_${System.currentTimeMillis()}.mp4")

        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(inputPath)

            val videoTrack = probeResult.primaryVideo
            val audioTrack = selectedAudioTrack ?: probeResult.primaryAudio

            if (videoTrack == null && audioTrack == null) {
                listener.onError("No video or audio tracks found")
                return
            }

            val durationUs = if (probeResult.durationMs > 0) probeResult.durationMs * 1000 else 0L

            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerStarted = false

            try {
                // Transcode video track
                if (videoTrack != null) {
                    AppLogger.info(TAG, "Transcoding video: ${videoTrack.codec} ${videoTrack.width}x${videoTrack.height} → H.264")
                    transcodeVideoTrack(
                        extractor, muxer, videoTrack, durationUs, listener
                    ) { started ->
                        if (!muxerStarted && started) {
                            muxerStarted = true
                        }
                    }
                }

                // Reset extractor for audio
                extractor.release()
                val audioExtractor = MediaExtractor()
                audioExtractor.setDataSource(inputPath)

                // Transcode audio track
                if (audioTrack != null) {
                    AppLogger.info(TAG, "Transcoding audio: ${audioTrack.codec} → AAC")
                    transcodeAudioTrack(
                        audioExtractor, muxer, audioTrack, muxerStarted
                    )
                }

                audioExtractor.release()
            } finally {
                try {
                    muxer.stop()
                } catch (e: IllegalStateException) {
                    AppLogger.warn(TAG, "Muxer stop failed: ${e.message}")
                }
                try {
                    muxer.release()
                } catch (e: Exception) {
                    AppLogger.warn(TAG, "Muxer release failed: ${e.message}")
                }
            }

            if (isCancelled) {
                outputFile.delete()
                AppLogger.info(TAG, "Transcode cancelled")
                listener.onError("Transcoding cancelled")
                return
            }

            AppLogger.info(TAG, "Transcode completed: ${outputFile.name} (${outputFile.length()} bytes)")
            listener.onCompleted(outputFile)

        } catch (e: Exception) {
            AppLogger.error(TAG, "Transcode failed: ${e.message}")
            outputFile.delete()
            listener.onError("Transcoding failed: ${e.message}")
        }
    }

    private fun transcodeVideoTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        trackInfo: VideoTrackInfo,
        durationUs: Long,
        listener: ProgressListener,
        onMuxerReady: (Boolean) -> Unit
    ): Int {
        extractor.selectTrack(trackInfo.trackIndex)
        val inputFormat = extractor.getTrackFormat(trackInfo.trackIndex)

        // Determine output dimensions (cap at 1080p)
        if (trackInfo.width <= 0 || trackInfo.height <= 0) {
            AppLogger.error(TAG, "Invalid video dimensions: ${trackInfo.width}x${trackInfo.height}")
            throw IllegalArgumentException("Invalid video dimensions: ${trackInfo.width}x${trackInfo.height}")
        }
        val inWidth = trackInfo.width
        val inHeight = trackInfo.height
        val scaleFactor = minOf(
            MAX_WIDTH.toFloat() / inWidth,
            MAX_HEIGHT.toFloat() / inHeight,
            1f
        )
        val outWidth = ((inWidth * scaleFactor).toInt() / 2) * 2  // Ensure even
        val outHeight = ((inHeight * scaleFactor).toInt() / 2) * 2

        val outputBitrate = if (trackInfo.bitrate > 0) {
            minOf(trackInfo.bitrate, OUTPUT_VIDEO_BITRATE)
        } else {
            OUTPUT_VIDEO_BITRATE
        }
        val outputFrameRate = if (trackInfo.frameRate > 0) {
            trackInfo.frameRate.toInt().coerceAtMost(OUTPUT_VIDEO_FRAME_RATE)
        } else {
            OUTPUT_VIDEO_FRAME_RATE
        }

        // Create decoder
        val decoder = MediaCodec.createDecoderByType(trackInfo.mime)
        decoder.configure(inputFormat, null, null, 0)

        // Create encoder
        val outputFormat = MediaFormat.createVideoFormat(OUTPUT_VIDEO_MIME, outWidth, outHeight).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, outputBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, outputFrameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, OUTPUT_VIDEO_IFRAME_INTERVAL)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        }

        val encoder = try {
            val codecName = selectHardwareEncoder(OUTPUT_VIDEO_MIME)
            if (codecName != null) {
                AppLogger.info(TAG, "Using HW encoder: $codecName")
                MediaCodec.createByCodecName(codecName)
            } else {
                AppLogger.info(TAG, "No HW encoder found, using default")
                MediaCodec.createEncoderByType(OUTPUT_VIDEO_MIME)
            }
        } catch (e: Exception) {
            AppLogger.warn(TAG, "HW encoder setup failed, using default: ${e.message}")
            MediaCodec.createEncoderByType(OUTPUT_VIDEO_MIME)
        }
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        decoder.start()
        encoder.start()

        var muxerTrackIndex = -1
        var muxerReady = false
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var decoderDone = false
        var encoderEosSent = false
        var lastReportedProgress = -1

        try {
            while (!isCancelled) {
                // Feed data to decoder
                if (!inputDone) {
                    val inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufIndex >= 0) {
                        val inputBuf = decoder.getInputBuffer(inputBufIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()

                            if (durationUs > 0) {
                                val progress = ((presentationTimeUs * 100) / durationUs).toInt().coerceIn(0, 100)
                                if (progress != lastReportedProgress) {
                                    lastReportedProgress = progress
                                    listener.onProgress(progress)
                                }
                            }
                        }
                    }
                }

                // Get decoded output from decoder → feed to encoder
                if (!decoderDone) {
                    val decoderStatus = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (decoderStatus >= 0) {
                        val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                        if (bufferInfo.size > 0) {
                            val decodedBuf = decoder.getOutputBuffer(decoderStatus)
                            if (decodedBuf != null) {
                                // Feed decoded frame to encoder
                                val encInputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                                if (encInputIndex >= 0) {
                                    val encInputBuf = encoder.getInputBuffer(encInputIndex)
                                    if (encInputBuf != null) {
                                        encInputBuf.clear()
                                        val limit = minOf(decodedBuf.remaining(), encInputBuf.remaining())
                                        val tempBuf = ByteArray(limit)
                                        decodedBuf.get(tempBuf, 0, limit)
                                        encInputBuf.put(tempBuf, 0, limit)
                                        encoder.queueInputBuffer(
                                            encInputIndex, 0, limit,
                                            bufferInfo.presentationTimeUs,
                                            if (isEos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                                        )
                                    }
                                }
                            }
                        }

                        decoder.releaseOutputBuffer(decoderStatus, false)
                        if (isEos) decoderDone = true
                    }
                }

                // Drain encoder output → write to muxer
                val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = encoder.outputFormat
                        muxerTrackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        muxerReady = true
                        onMuxerReady(true)
                        AppLogger.info(TAG, "Muxer started with video track $muxerTrackIndex")
                    }
                    encoderStatus >= 0 -> {
                        val encodedBuf = encoder.getOutputBuffer(encoderStatus) ?: continue
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerReady) {
                            encodedBuf.position(bufferInfo.offset)
                            encodedBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(muxerTrackIndex, encodedBuf, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(encoderStatus, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            break
                        }
                    }
                }

                if (decoderDone && !encoderEosSent && encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // Signal encoder EOS if not already done
                    val encInputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (encInputIndex >= 0) {
                        encoder.queueInputBuffer(encInputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        encoderEosSent = true
                    }
                }
            }
        } finally {
            decoder.stop()
            decoder.release()
            encoder.stop()
            encoder.release()
        }

        return muxerTrackIndex
    }

    private fun transcodeAudioTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        trackInfo: AudioTrackInfo,
        muxerAlreadyStarted: Boolean
    ): Int {
        extractor.selectTrack(trackInfo.trackIndex)
        val inputFormat = extractor.getTrackFormat(trackInfo.trackIndex)

        // Create decoder
        val decoder = MediaCodec.createDecoderByType(trackInfo.mime)
        decoder.configure(inputFormat, null, null, 0)

        // Create AAC encoder
        val outputFormat = MediaFormat.createAudioFormat(
            OUTPUT_AUDIO_MIME,
            OUTPUT_AUDIO_SAMPLE_RATE,
            OUTPUT_AUDIO_CHANNEL_COUNT
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }

        val encoder = MediaCodec.createEncoderByType(OUTPUT_AUDIO_MIME)
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        decoder.start()
        encoder.start()

        var muxerTrackIndex = -1
        var muxerReady = muxerAlreadyStarted
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var decoderDone = false
        var audioEncoderEosSent = false

        try {
            while (!isCancelled) {
                if (!inputDone) {
                    val inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufIndex >= 0) {
                        val inputBuf = decoder.getInputBuffer(inputBufIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                if (!decoderDone) {
                    val decoderStatus = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (decoderStatus >= 0) {
                        val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        if (bufferInfo.size > 0) {
                            val decodedBuf = decoder.getOutputBuffer(decoderStatus)
                            if (decodedBuf != null) {
                                val encInputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                                if (encInputIndex >= 0) {
                                    val encInputBuf = encoder.getInputBuffer(encInputIndex)
                                    if (encInputBuf != null) {
                                        encInputBuf.clear()
                                        val limit = minOf(decodedBuf.remaining(), encInputBuf.remaining())
                                        val tempBuf = ByteArray(limit)
                                        decodedBuf.get(tempBuf, 0, limit)
                                        encInputBuf.put(tempBuf, 0, limit)
                                        encoder.queueInputBuffer(
                                            encInputIndex, 0, limit,
                                            bufferInfo.presentationTimeUs,
                                            if (isEos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                                        )
                                    }
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(decoderStatus, false)
                        if (isEos) decoderDone = true
                    }
                }

                val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = encoder.outputFormat
                        muxerTrackIndex = muxer.addTrack(newFormat)
                        if (!muxerReady) {
                            muxer.start()
                            muxerReady = true
                        }
                        AppLogger.info(TAG, "Audio muxer track $muxerTrackIndex added")
                    }
                    encoderStatus >= 0 -> {
                        val encodedBuf = encoder.getOutputBuffer(encoderStatus) ?: continue
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerReady && muxerTrackIndex >= 0) {
                            encodedBuf.position(bufferInfo.offset)
                            encodedBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(muxerTrackIndex, encodedBuf, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(encoderStatus, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            break
                        }
                    }
                }

                if (decoderDone && !audioEncoderEosSent && encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    val encInputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (encInputIndex >= 0) {
                        encoder.queueInputBuffer(encInputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        audioEncoderEosSent = true
                    }
                }
            }
        } finally {
            decoder.stop()
            decoder.release()
            encoder.stop()
            encoder.release()
        }

        return muxerTrackIndex
    }

    /**
     * Remuxes a media file to include only the video track and the selected audio track,
     * without re-encoding. This is used when the user selects a non-default audio track
     * for direct streaming.
     */
    fun remux(
        inputPath: String,
        outputDir: File,
        probeResult: MediaProbeResult,
        selectedAudioTrack: AudioTrackInfo,
        listener: ProgressListener
    ) {
        isCancelled = false
        val outputFile = File(outputDir, "remux_${System.currentTimeMillis()}.mp4")

        try {
            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackIndexMap = mutableMapOf<Int, Int>()

            val extractor = MediaExtractor()
            extractor.setDataSource(inputPath)

            // Add video track format
            val videoTrack = probeResult.primaryVideo
            if (videoTrack != null) {
                val format = extractor.getTrackFormat(videoTrack.trackIndex)
                trackIndexMap[videoTrack.trackIndex] = muxer.addTrack(format)
            }

            // Add selected audio track format
            val audioFormat = extractor.getTrackFormat(selectedAudioTrack.trackIndex)
            trackIndexMap[selectedAudioTrack.trackIndex] = muxer.addTrack(audioFormat)

            extractor.release()

            muxer.start()

            val durationUs = if (probeResult.durationMs > 0) probeResult.durationMs * 1000 else 0L
            val bufferSize = 1024 * 1024
            val buffer = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()
            var lastReportedProgress = -1
            val trackCount = trackIndexMap.size
            var trackNumber = 0

            for ((inputTrackIndex, muxerTrackIndex) in trackIndexMap) {
                val progressBase = (trackNumber * 100) / trackCount
                val progressRange = 100 / trackCount
                trackNumber++

                val trackExtractor = MediaExtractor()
                try {
                    trackExtractor.setDataSource(inputPath)
                    trackExtractor.selectTrack(inputTrackIndex)

                    while (!isCancelled) {
                        buffer.clear()
                        val sampleSize = trackExtractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break

                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = trackExtractor.sampleTime
                        bufferInfo.flags = trackExtractor.sampleFlags

                        muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)

                        if (durationUs > 0) {
                            val trackProgress = ((trackExtractor.sampleTime * progressRange) / durationUs).toInt()
                            val progress = (progressBase + trackProgress).coerceIn(0, 100)
                            if (progress != lastReportedProgress) {
                                lastReportedProgress = progress
                                listener.onProgress(progress)
                            }
                        }

                        trackExtractor.advance()
                    }
                } finally {
                    trackExtractor.release()
                }
            }

            try { muxer.stop() } catch (e: IllegalStateException) {
                AppLogger.warn(TAG, "Muxer stop failed: ${e.message}")
            }
            try { muxer.release() } catch (e: Exception) {
                AppLogger.warn(TAG, "Muxer release failed: ${e.message}")
            }

            if (isCancelled) {
                outputFile.delete()
                AppLogger.info(TAG, "Remux cancelled")
                listener.onError("Remuxing cancelled")
                return
            }

            AppLogger.info(TAG, "Remux completed: ${outputFile.name} (${outputFile.length()} bytes)")
            listener.onCompleted(outputFile)

        } catch (e: Exception) {
            AppLogger.error(TAG, "Remux failed: ${e.message}")
            outputFile.delete()
            listener.onError("Remuxing failed: ${e.message}")
        }
    }

    /**
     * Remuxes a media file with video passthrough and audio transcoding.
     * The video track is copied as-is (no re-encoding) while the selected audio track
     * is decoded and re-encoded to AAC. Much faster than full transcode since
     * video passthrough is essentially just an I/O copy.
     *
     * MediaMuxer requires all tracks to be added before start(), so we set up
     * the audio encoder first to obtain its output format, add both tracks,
     * then start the muxer and write samples.
     */
    fun remuxWithAudioTranscode(
        inputPath: String,
        outputDir: File,
        probeResult: MediaProbeResult,
        selectedAudioTrack: AudioTrackInfo,
        listener: ProgressListener
    ) {
        isCancelled = false
        val outputFile = File(outputDir, "remux_${System.currentTimeMillis()}.mp4")

        try {
            val videoTrack = probeResult.primaryVideo
            val durationUs = if (probeResult.durationMs > 0) probeResult.durationMs * 1000 else 0L

            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Set up audio decoder and encoder
            AppLogger.info(TAG, "Transcoding audio: ${selectedAudioTrack.codec} → AAC")
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

            // Pump audio encoder until it emits output format (needed before muxer.start())
            var muxerAudioTrack = -1
            val initBufferInfo = MediaCodec.BufferInfo()
            var audioInputDone = false
            var audioDecoderDone = false

            while (muxerAudioTrack < 0 && !isCancelled) {
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
                    val status = audioDecoder.dequeueOutputBuffer(initBufferInfo, TIMEOUT_US)
                    if (status >= 0) {
                        val isEos = (initBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        if (initBufferInfo.size > 0) {
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
                                            encIdx, 0, limit, initBufferInfo.presentationTimeUs,
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
                val encStatus = audioEncoder.dequeueOutputBuffer(initBufferInfo, TIMEOUT_US)
                if (encStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxerAudioTrack = muxer.addTrack(audioEncoder.outputFormat)
                    AppLogger.info(TAG, "Audio encoder format ready, muxer audio track $muxerAudioTrack")
                } else if (encStatus >= 0) {
                    // Hold off on writing until muxer is started
                    audioEncoder.releaseOutputBuffer(encStatus, false)
                }
            }

            // Add video track and start muxer with both tracks
            var muxerVideoTrack = -1
            val videoExtractor = MediaExtractor()
            videoExtractor.setDataSource(inputPath)
            if (videoTrack != null) {
                val videoFormat = videoExtractor.getTrackFormat(videoTrack.trackIndex)
                muxerVideoTrack = muxer.addTrack(videoFormat)
                AppLogger.info(TAG, "Video passthrough: ${videoTrack.codec} ${videoTrack.width}x${videoTrack.height}")
            }

            muxer.start()

            try {
                // Phase 1: Video passthrough (fast copy, no re-encoding)
                if (videoTrack != null && muxerVideoTrack >= 0) {
                    videoExtractor.selectTrack(videoTrack.trackIndex)
                    val buffer = ByteBuffer.allocate(1024 * 1024)
                    val bufferInfo = MediaCodec.BufferInfo()
                    var lastReportedProgress = -1

                    while (!isCancelled) {
                        buffer.clear()
                        val sampleSize = videoExtractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break

                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                        bufferInfo.flags = videoExtractor.sampleFlags

                        muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)

                        if (durationUs > 0) {
                            val progress = ((videoExtractor.sampleTime * 50) / durationUs).toInt().coerceIn(0, 50)
                            if (progress != lastReportedProgress) {
                                lastReportedProgress = progress
                                listener.onProgress(progress)
                            }
                        }

                        videoExtractor.advance()
                    }
                }
                videoExtractor.release()

                // Phase 2: Continue audio transcoding (encoder already started above)
                if (!isCancelled) {
                    listener.onProgress(50)
                    val bufferInfo = MediaCodec.BufferInfo()
                    var audioEncoderEosSent = false

                    while (!isCancelled) {
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
                        when {
                            encStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                // Already handled during init phase
                            }
                            encStatus >= 0 -> {
                                val encodedBuf = audioEncoder.getOutputBuffer(encStatus)
                                if (encodedBuf != null) {
                                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                        bufferInfo.size = 0
                                    }
                                    if (bufferInfo.size > 0 && muxerAudioTrack >= 0) {
                                        encodedBuf.position(bufferInfo.offset)
                                        encodedBuf.limit(bufferInfo.offset + bufferInfo.size)
                                        muxer.writeSampleData(muxerAudioTrack, encodedBuf, bufferInfo)
                                    }
                                }
                                audioEncoder.releaseOutputBuffer(encStatus, false)

                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    break
                                }
                            }
                        }

                        if (audioDecoderDone && !audioEncoderEosSent && encStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            val encIdx = audioEncoder.dequeueInputBuffer(TIMEOUT_US)
                            if (encIdx >= 0) {
                                audioEncoder.queueInputBuffer(encIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                audioEncoderEosSent = true
                            }
                        }
                    }
                }

                audioExtractor.release()
            } finally {
                audioDecoder.stop()
                audioDecoder.release()
                audioEncoder.stop()
                audioEncoder.release()
                try { muxer.stop() } catch (e: IllegalStateException) {
                    AppLogger.warn(TAG, "Muxer stop failed: ${e.message}")
                }
                try { muxer.release() } catch (e: Exception) {
                    AppLogger.warn(TAG, "Muxer release failed: ${e.message}")
                }
            }

            if (isCancelled) {
                outputFile.delete()
                AppLogger.info(TAG, "Remux with audio transcode cancelled")
                listener.onError("Cancelled")
                return
            }

            AppLogger.info(TAG, "Remux with audio transcode completed: ${outputFile.name} (${outputFile.length()} bytes)")
            listener.onCompleted(outputFile)

        } catch (e: Exception) {
            AppLogger.error(TAG, "Remux with audio transcode failed: ${e.message}")
            outputFile.delete()
            listener.onError("Remuxing failed: ${e.message}")
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
