package com.storagecast.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import com.storagecast.log.AppLogger
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.TreeMap

/**
 * One long-lived sequential decode->encode pipeline per HLS run. Decodes the source forward from a
 * base segment index (each frame once), forces an IDR at every N*6s boundary, cuts the encoded
 * stream into fMP4 segments via HlsMp4Builder, and publishes finished segments to [onSegment] while
 * advancing [frontier]. Bounded to LEAD segments ahead of the playhead (back-pressure). Modeled on
 * TranscodeStreamer's surface loop (10-bit tone-map, encoder-before-decoder, finally teardown);
 * does NOT refactor it.
 */
class HlsSegmentPipeline(
    private val inputPath: String,
    private val probeResult: MediaProbeResult,
    private val selectedAudioTrack: AudioTrackInfo?,
    private val copyAudio: Boolean,
    private val committedConfig: CommittedEncoderConfig,
    private val segDurUs: Long,
    private val lead: Int,
    /** Called with each finished segment (index, bytes, videoInit, audioInit). */
    private val onSegment: (SegmentResult) -> Unit,
    /** Called when a boundary-IDR miss drops segment [index] (so the session routes it to one-off). */
    private val onSkipped: (Int) -> Unit = {},
    /** Returns the current playhead (prevIndex) so production can back-pressure to playhead+LEAD. */
    private val playhead: () -> Int,
) {
    data class SegmentResult(
        val index: Int,
        val bytes: ByteArray,
        val videoInit: HlsMp4Builder.VideoInit,
        val audioInit: HlsMp4Builder.AudioInit?,
    )

    @Volatile private var cancelled = false
    @Volatile var frontier: Int = -1
        private set
    @Volatile var capturedVideoInit: HlsMp4Builder.VideoInit? = null
        private set
    @Volatile var capturedAudioInit: HlsMp4Builder.AudioInit? = null
        private set

    private var worker: Thread? = null

    fun start(baseIndex: Int) {
        if (worker?.isAlive == true) return
        cancelled = false
        worker = Thread({
            try {
                runPipeline(baseIndex)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Throwable) {
                if (!cancelled) AppLogger.error(TAG, "HLS segment pipeline failed: ${describeError(e)}")
            }
        }, "HlsSegmentPipeline-$baseIndex").apply {
            isDaemon = true
            start()
        }
    }

    fun cancel() { cancelled = true; worker?.interrupt(); worker?.join(2000) }

    private fun runPipeline(baseIndex: Int) {
        val videoTrack = probeResult.primaryVideo
            ?: throw IllegalArgumentException("HlsSegmentPipeline requires a video track")
        val audioTrack = selectedAudioTrack ?: probeResult.primaryAudio
        val baseStartUs = baseIndex * segDurUs
        val segments = SegmentAccumulator(baseIndex, audioTrack != null)

        var videoExtractor: MediaExtractor? = null
        var videoDecoder: MediaCodec? = null
        var videoEncoder: MediaCodec? = null
        var videoInputSurface: android.view.Surface? = null
        var audioStage: AudioStage? = null

        try {
            val vExtractor = MediaExtractor()
            videoExtractor = vExtractor
            vExtractor.setDataSource(inputPath)
            vExtractor.selectTrack(videoTrack.trackIndex)
            vExtractor.seekTo(baseStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val inputFormat = vExtractor.getTrackFormat(videoTrack.trackIndex)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                try {
                    inputFormat.setInteger(
                        MediaFormat.KEY_COLOR_TRANSFER_REQUEST,
                        MediaFormat.COLOR_TRANSFER_SDR_VIDEO
                    )
                } catch (_: Exception) {}
            }

            videoEncoder = EncoderFormatFactory.createCommittedEncoder(committedConfig)
            videoEncoder.configure(
                EncoderFormatFactory.buildAvcEncoderFormat(committedConfig),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )
            videoInputSurface = videoEncoder.createInputSurface()
            videoEncoder.start()
            requestSyncFrame(videoEncoder, "base segment $baseIndex")

            videoDecoder = MediaCodec.createDecoderByType(videoTrack.mime)
            videoDecoder.configure(inputFormat, videoInputSurface, null, 0)
            videoDecoder.start()

            audioStage = audioTrack?.let { createAudioStage(it, baseStartUs) }
            audioStage?.init?.let { segments.setAudioInit(it) }

            AppLogger.info(
                TAG,
                "HLS pipeline start base=$baseIndex video=${videoTrack.codec} -> H.264 " +
                    "${committedConfig.width}x${committedConfig.height} audio=${audioTrack?.codec ?: "none"}"
            )

            pumpLoop(
                baseStartUs = baseStartUs,
                videoExtractor = videoExtractor,
                videoDecoder = videoDecoder,
                videoEncoder = videoEncoder,
                audioStage = audioStage,
                segments = segments,
            )
        } finally {
            safeStopRelease(videoDecoder)
            safeStopRelease(videoEncoder)
            try { videoInputSurface?.release() } catch (_: Exception) {}
            videoExtractor?.release()
            audioStage?.release()
        }
    }

    private fun pumpLoop(
        baseStartUs: Long,
        videoExtractor: MediaExtractor,
        videoDecoder: MediaCodec,
        videoEncoder: MediaCodec,
        audioStage: AudioStage?,
        segments: SegmentAccumulator,
    ) {
        val decoderInfo = MediaCodec.BufferInfo()
        val encoderInfo = MediaCodec.BufferInfo()
        var videoInputDone = false
        var videoDecoderDone = false
        var videoEncoderEosSent = false
        var videoEncoderDone = false
        var prevRenderedPtsUs = baseStartUs - 1
        var nextBoundaryUs = (HlsTranscodeMath.segmentIndexForPts(baseStartUs, segDurUs) + 1) * segDurUs
        var completed = false
        var interrupted = false

        while (true) {
            if (cancelled || Thread.interrupted()) {
                interrupted = true
                break
            }
            if (videoEncoderDone && (audioStage?.isDone ?: true)) {
                completed = true
                break
            }

            var didWork = false

            if (!videoInputDone) {
                val idx = videoDecoder.dequeueInputBuffer(0)
                if (idx >= 0) {
                    val buf = videoDecoder.getInputBuffer(idx)
                    val size = if (buf != null) videoExtractor.readSampleData(buf, 0) else -1
                    if (size < 0) {
                        videoDecoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        videoInputDone = true
                    } else {
                        videoDecoder.queueInputBuffer(idx, 0, size, videoExtractor.sampleTime, 0)
                        videoExtractor.advance()
                    }
                    didWork = true
                }
            }

            if (!videoDecoderDone) {
                val status = videoDecoder.dequeueOutputBuffer(decoderInfo, 0)
                if (status >= 0) {
                    val eos = (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val ptsUs = decoderInfo.presentationTimeUs
                    val isCodecConfig = (decoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val render = HlsTranscodeMath.shouldRenderVideoFrame(
                        decoderInfo.size,
                        isCodecConfig,
                        ptsUs,
                        baseStartUs,
                        Long.MAX_VALUE
                    )
                    if (render && HlsTranscodeMath.crossesBoundary(prevRenderedPtsUs, ptsUs, nextBoundaryUs)) {
                        requestSyncFrame(videoEncoder, "boundary ${nextBoundaryUs / 1000}ms")
                        while (ptsUs >= nextBoundaryUs) nextBoundaryUs += segDurUs
                    }
                    videoDecoder.releaseOutputBuffer(status, render)
                    if (render) prevRenderedPtsUs = ptsUs
                    if (eos) {
                        videoDecoderDone = true
                        if (!videoEncoderEosSent) {
                            videoEncoder.signalEndOfInputStream()
                            videoEncoderEosSent = true
                        }
                    }
                    didWork = true
                }
            }

            if (!videoEncoderDone) {
                val status = videoEncoder.dequeueOutputBuffer(encoderInfo, TIMEOUT_US)
                if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val avcC = buildAvcConfigRecord(videoEncoder.outputFormat)
                    if (avcC.isNotEmpty()) {
                        segments.setVideoInit(
                            HlsMp4Builder.VideoInit(avcC, committedConfig.width, committedConfig.height)
                        )
                    } else {
                        AppLogger.warn(TAG, "Pipeline encoder format changed before avcC was available")
                    }
                    didWork = true
                } else if (status >= 0) {
                    val isConfig = (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val encodedBuf = videoEncoder.getOutputBuffer(status)
                    if (isConfig && segments.videoInit == null && encodedBuf != null) {
                        val cfg = ByteArray(encoderInfo.size)
                        encodedBuf.position(encoderInfo.offset)
                        encodedBuf.get(cfg, 0, encoderInfo.size)
                        val avcC = buildAvcConfigFromAnnexB(cfg)
                        if (avcC.isNotEmpty()) {
                            segments.setVideoInit(
                                HlsMp4Builder.VideoInit(avcC, committedConfig.width, committedConfig.height)
                            )
                        }
                    }
                    if (!isConfig && encoderInfo.size > 0 && encodedBuf != null) {
                        val data = ByteArray(encoderInfo.size)
                        encodedBuf.position(encoderInfo.offset)
                        encodedBuf.get(data, 0, encoderInfo.size)
                        val key = (encoderInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        segments.addVideoSample(
                            HlsMp4Builder.Sample(
                                HlsMp4Builder.ensureAvcc(data),
                                encoderInfo.presentationTimeUs,
                                key
                            )
                        )
                    }
                    videoEncoder.releaseOutputBuffer(status, false)
                    if ((encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        videoEncoderDone = true
                        segments.markVideoDone()
                    }
                    didWork = true
                }
            }

            if (audioStage != null && !audioStage.isDone) {
                didWork = audioStage.step(segments::addAudioSample, segments::setAudioInit) || didWork
                if (audioStage.isDone) segments.markAudioDone()
            }

            segments.flushReady()

            if (videoEncoderDone && audioStage != null && !audioStage.isDone && !didWork) {
                try { Thread.sleep(2) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        if (completed && !cancelled && !interrupted) {
            segments.markVideoDone()
            if (audioStage == null || audioStage.isDone) segments.markAudioDone()
            segments.flushRemaining()
            AppLogger.info(TAG, "HLS pipeline complete at frontier=$frontier")
        }
    }

    private fun createAudioStage(track: AudioTrackInfo, baseStartUs: Long): AudioStage {
        if (copyAudio) {
            createPassthroughAudioStage(track, baseStartUs)?.let { return it }
            AppLogger.info(TAG, "Audio passthrough unavailable for ${track.codec}; falling back to AAC")
        }
        return createTranscodeAudioStage(track, baseStartUs)
    }

    private fun createPassthroughAudioStage(track: AudioTrackInfo, baseStartUs: Long): AudioStage? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(inputPath)
            extractor.selectTrack(track.trackIndex)
            val format = extractor.getTrackFormat(track.trackIndex)
            val mime = (format.getString(MediaFormat.KEY_MIME) ?: track.mime).lowercase()
            val sampleRate = getIntSafe(format, MediaFormat.KEY_SAMPLE_RATE, track.sampleRate)
            val channels = getIntSafe(format, MediaFormat.KEY_CHANNEL_COUNT, track.channelCount)
            extractor.seekTo(baseStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val init = when {
                mime.contains("mp4a") || mime.contains("aac") -> {
                    val asc = getCsdBytes(format, 0) ?: return null.also { extractor.release() }
                    HlsMp4Builder.AudioInit(HlsMp4Builder.AudioCodec.AAC, asc, sampleRate, channels)
                }
                mime.contains("eac3") || mime.contains("ec3") || mime.contains("ec-3") -> {
                    val firstFrame = peekFirstIncludedAudioFrame(extractor, baseStartUs)
                        ?: return null.also { extractor.release() }
                    val dec3 = DolbyAudioConfig.buildDec3(firstFrame) ?: return null.also { extractor.release() }
                    HlsMp4Builder.AudioInit(HlsMp4Builder.AudioCodec.EAC3, dec3, sampleRate, channels)
                }
                mime.contains("ac3") || mime.contains("ac-3") -> {
                    val firstFrame = peekFirstIncludedAudioFrame(extractor, baseStartUs)
                        ?: return null.also { extractor.release() }
                    val dac3 = DolbyAudioConfig.buildDac3(firstFrame) ?: return null.also { extractor.release() }
                    HlsMp4Builder.AudioInit(HlsMp4Builder.AudioCodec.AC3, dac3, sampleRate, channels)
                }
                else -> return null.also { extractor.release() }
            }

            extractor.seekTo(baseStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            AppLogger.info(TAG, "Audio passthrough pipeline: ${init.codec} ${sampleRate}Hz ${channels}ch")
            return PassthroughAudioStage(extractor, baseStartUs, init)
        } catch (e: Exception) {
            extractor.release()
            AppLogger.warn(TAG, "Audio passthrough setup failed: ${e.message}")
            return null
        }
    }

    private fun createTranscodeAudioStage(track: AudioTrackInfo, baseStartUs: Long): AudioStage {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(inputPath)
            extractor.selectTrack(track.trackIndex)
            extractor.seekTo(baseStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val inputFormat = extractor.getTrackFormat(track.trackIndex)
            inputFormat.setInteger(MediaFormat.KEY_MAX_OUTPUT_CHANNEL_COUNT, OUTPUT_AUDIO_CHANNEL_COUNT)
            decoder = MediaCodec.createDecoderByType(track.mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            AppLogger.info(TAG, "Audio transcode pipeline: ${track.codec} -> AAC")
            return TranscodeAudioStage(extractor, decoder, baseStartUs)
        } catch (e: Exception) {
            safeStopRelease(decoder)
            try { extractor.release() } catch (_: Exception) {}
            throw e
        }
    }

    private fun peekFirstIncludedAudioFrame(extractor: MediaExtractor, baseStartUs: Long): ByteArray? {
        val buf = ByteBuffer.allocate(AUDIO_SAMPLE_BUFFER_SIZE)
        while (true) {
            buf.clear()
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) return null
            val ptsUs = extractor.sampleTime
            if (ptsUs >= baseStartUs) {
                val data = ByteArray(size)
                buf.position(0)
                buf.get(data, 0, size)
                return data
            }
            extractor.advance()
        }
    }

    private interface AudioStage {
        val isDone: Boolean
        val init: HlsMp4Builder.AudioInit?
        fun step(
            onSample: (HlsMp4Builder.Sample) -> Unit,
            onInit: (HlsMp4Builder.AudioInit) -> Unit
        ): Boolean
        fun release()
    }

    private class PassthroughAudioStage(
        private val extractor: MediaExtractor,
        private val baseStartUs: Long,
        override val init: HlsMp4Builder.AudioInit,
    ) : AudioStage {
        private val sampleBuffer = ByteBuffer.allocate(AUDIO_SAMPLE_BUFFER_SIZE)
        override var isDone: Boolean = false
            private set

        override fun step(
            onSample: (HlsMp4Builder.Sample) -> Unit,
            onInit: (HlsMp4Builder.AudioInit) -> Unit
        ): Boolean {
            if (isDone) return false
            onInit(init)
            sampleBuffer.clear()
            val size = extractor.readSampleData(sampleBuffer, 0)
            if (size < 0) {
                isDone = true
                return true
            }
            val ptsUs = extractor.sampleTime
            if (ptsUs >= baseStartUs) {
                val data = ByteArray(size)
                sampleBuffer.position(0)
                sampleBuffer.get(data, 0, size)
                onSample(HlsMp4Builder.Sample(data, ptsUs, true))
            }
            extractor.advance()
            return true
        }

        override fun release() { extractor.release() }
    }

    private inner class TranscodeAudioStage(
        private val extractor: MediaExtractor,
        private val decoder: MediaCodec,
        private val baseStartUs: Long,
    ) : AudioStage {
        private val decoderInfo = MediaCodec.BufferInfo()
        private val encoderInfo = MediaCodec.BufferInfo()
        private var inputDone = false
        private var decoderDone = false
        private var encoderDone = false
        private var encoder: MediaCodec? = null
        private var feeder: HlsAudioEncoderFeeder? = null
        private var sampleRate = 48000
        private var channels = OUTPUT_AUDIO_CHANNEL_COUNT
        private var currentInit: HlsMp4Builder.AudioInit? = null

        override val isDone: Boolean get() = encoderDone
        override val init: HlsMp4Builder.AudioInit? get() = currentInit

        override fun step(
            onSample: (HlsMp4Builder.Sample) -> Unit,
            onInit: (HlsMp4Builder.AudioInit) -> Unit
        ): Boolean {
            if (encoderDone) return false
            var didWork = false

            if (!inputDone) {
                val idx = decoder.dequeueInputBuffer(0)
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
                    didWork = true
                }
            }

            if (!decoderDone) {
                val status = decoder.dequeueOutputBuffer(decoderInfo, 0)
                if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    ensureEncoder()
                    didWork = true
                } else if (status >= 0) {
                    val eos = (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val ptsUs = decoderInfo.presentationTimeUs
                    if (decoderInfo.size > 0 && ptsUs >= baseStartUs) {
                        ensureEncoder()
                        val decoded = decoder.getOutputBuffer(status)
                        if (decoded != null) {
                            val data = ByteArray(decoderInfo.size)
                            decoded.position(decoderInfo.offset)
                            decoded.get(data, 0, decoderInfo.size)
                            feeder?.enqueuePcm(data, ptsUs)
                        }
                    }
                    decoder.releaseOutputBuffer(status, false)
                    if (eos) {
                        decoderDone = true
                        ensureEncoder()
                    }
                    didWork = true
                }
            }

            feeder?.pump(decoderDone)

            val enc = encoder
            if (enc != null) {
                val status = enc.dequeueOutputBuffer(encoderInfo, 0)
                if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    captureInit(enc.outputFormat)?.let {
                        currentInit = it
                        onInit(it)
                    }
                    didWork = true
                } else if (status >= 0) {
                    val isConfig = (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val encoded = enc.getOutputBuffer(status)
                    if (isConfig && currentInit == null && encoded != null) {
                        val cfg = ByteArray(encoderInfo.size)
                        encoded.position(encoderInfo.offset)
                        encoded.get(cfg, 0, encoderInfo.size)
                        val init = HlsMp4Builder.AudioInit(
                            HlsMp4Builder.AudioCodec.AAC,
                            cfg,
                            sampleRate,
                            channels
                        )
                        currentInit = init
                        onInit(init)
                    }
                    if (!isConfig && encoderInfo.size > 0 && encoded != null) {
                        val data = ByteArray(encoderInfo.size)
                        encoded.position(encoderInfo.offset)
                        encoded.get(data, 0, encoderInfo.size)
                        onSample(HlsMp4Builder.Sample(data, encoderInfo.presentationTimeUs, true))
                    }
                    enc.releaseOutputBuffer(status, false)
                    if ((encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoderDone = true
                    }
                    didWork = true
                }
            } else if (decoderDone) {
                encoderDone = true
            }

            return didWork
        }

        private fun ensureEncoder() {
            if (encoder != null) return
            val outFmt = decoder.outputFormat
            sampleRate = getIntSafe(outFmt, MediaFormat.KEY_SAMPLE_RATE, 48000)
            channels = getIntSafe(outFmt, MediaFormat.KEY_CHANNEL_COUNT, OUTPUT_AUDIO_CHANNEL_COUNT)
            val format = MediaFormat.createAudioFormat(OUTPUT_AUDIO_MIME, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            encoder = MediaCodec.createEncoderByType(OUTPUT_AUDIO_MIME).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            feeder = HlsAudioEncoderFeeder(encoder!!, sampleRate, channels)
        }

        private fun captureInit(format: MediaFormat): HlsMp4Builder.AudioInit? {
            val asc = getCsdBytes(format, 0) ?: return null
            return HlsMp4Builder.AudioInit(HlsMp4Builder.AudioCodec.AAC, asc, sampleRate, channels)
        }

        override fun release() {
            safeStopRelease(decoder)
            safeStopRelease(encoder)
            extractor.release()
        }
    }

    private inner class SegmentAccumulator(baseIndex: Int, hasAudio: Boolean) {
        private val buckets = TreeMap<Int, SegmentBucket>()
        private var nextSegmentIndex = baseIndex
        private var videoMaxPtsUs = Long.MIN_VALUE
        private var audioMaxPtsUs = if (hasAudio) Long.MIN_VALUE else Long.MAX_VALUE
        var videoInit: HlsMp4Builder.VideoInit? = null
            private set
        private var audioInit: HlsMp4Builder.AudioInit? = null

        fun setVideoInit(init: HlsMp4Builder.VideoInit) {
            val previous = videoInit
            if (previous != null && !HlsTranscodeMath.avcConfigsMatch(previous.avcC, init.avcC)) {
                AppLogger.warn(TAG, "Pipeline avcC changed after initial capture")
            }
            videoInit = previous ?: init
        }

        fun setAudioInit(init: HlsMp4Builder.AudioInit) {
            val previous = audioInit
            if (previous != null && !audioConfigsMatch(previous, init)) {
                AppLogger.warn(TAG, "Pipeline audio init changed after initial capture")
            }
            audioInit = previous ?: init
        }

        fun addVideoSample(sample: HlsMp4Builder.Sample) {
            videoMaxPtsUs = maxOf(videoMaxPtsUs, sample.ptsUs)
            val index = HlsTranscodeMath.segmentIndexForPts(sample.ptsUs, segDurUs)
            if (index < nextSegmentIndex) return
            buckets.getOrPut(index) { SegmentBucket() }.videoSamples.add(sample)
        }

        fun addAudioSample(sample: HlsMp4Builder.Sample) {
            audioMaxPtsUs = maxOf(audioMaxPtsUs, sample.ptsUs)
            val index = HlsTranscodeMath.segmentIndexForPts(sample.ptsUs, segDurUs)
            if (index < nextSegmentIndex) return
            buckets.getOrPut(index) { SegmentBucket() }.audioSamples.add(sample)
        }

        fun markVideoDone() { videoMaxPtsUs = Long.MAX_VALUE }

        fun markAudioDone() { audioMaxPtsUs = Long.MAX_VALUE }

        fun flushReady() {
            while (!cancelled) {
                val endUs = (nextSegmentIndex + 1) * segDurUs
                if (!HlsTranscodeMath.segmentDrained(videoMaxPtsUs, audioMaxPtsUs, endUs)) return
                if (!flushOne(force = false)) return
            }
        }

        fun flushRemaining() {
            while (!cancelled && buckets.isNotEmpty()) {
                if (!flushOne(force = true)) return
            }
        }

        private fun flushOne(force: Boolean): Boolean {
            val bucket = buckets[nextSegmentIndex]
            if (bucket == null) {
                if (!force && buckets.isEmpty()) return false
                val firstKey = if (buckets.isEmpty()) return false else buckets.firstKey()
                if (!force && firstKey <= nextSegmentIndex) return false
                if (!waitForBackpressure(nextSegmentIndex)) return false
                AppLogger.warn(TAG, "Skipping empty HLS pipeline segment $nextSegmentIndex")
                onSkipped(nextSegmentIndex)
                frontier = nextSegmentIndex
                nextSegmentIndex++
                return true
            }

            if (!waitForBackpressure(nextSegmentIndex)) return false
            val removed = publishOrSkip(nextSegmentIndex, bucket, force)
            if (removed) {
                buckets.remove(nextSegmentIndex)
                nextSegmentIndex++
            }
            return removed
        }

        private fun publishOrSkip(index: Int, bucket: SegmentBucket, force: Boolean): Boolean {
            val init = videoInit
            if (init == null) {
                if (!force) return false
                return skip(index, "video init was not captured")
            }
            val firstVideo = bucket.videoSamples.firstOrNull()
                ?: return skip(index, "no video samples")
            if (!firstVideo.keyframe) {
                return skip(index, "first video sample is not an IDR")
            }
            if (bucket.audioSamples.isNotEmpty() && audioInit == null) {
                if (!force) return false
                return skip(index, "audio init was not captured")
            }

            val bytes = HlsMp4Builder.buildMediaSegment(
                sequenceNumber = index + 1,
                videoSamples = bucket.videoSamples,
                audioSamples = bucket.audioSamples,
                defaultVideoDurUs = 33_333L,
                defaultAudioDurUs = 21_333L
            )

            if (capturedVideoInit == null) capturedVideoInit = init
            val aInit = audioInit
            if (aInit != null && capturedAudioInit == null) capturedAudioInit = aInit
            frontier = index
            onSegment(SegmentResult(index, bytes, init, aInit))
            AppLogger.info(
                TAG,
                "Built HLS pipeline segment $index: video=${bucket.videoSamples.size} " +
                    "audio=${bucket.audioSamples.size} bytes=${bytes.size}"
            )
            return true
        }

        private fun skip(index: Int, reason: String): Boolean {
            AppLogger.warn(TAG, "Dropping HLS pipeline segment $index: $reason")
            frontier = index
            onSkipped(index)
            return true
        }
    }

    private class SegmentBucket {
        val videoSamples = ArrayList<HlsMp4Builder.Sample>()
        val audioSamples = ArrayList<HlsMp4Builder.Sample>()
    }

    private fun waitForBackpressure(segIndex: Int): Boolean {
        while (!cancelled && !Thread.currentThread().isInterrupted && segIndex > playhead() + lead) {
            try { Thread.sleep(20) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return !cancelled && !Thread.currentThread().isInterrupted
    }

    private fun requestSyncFrame(encoder: MediaCodec, reason: String) {
        try {
            encoder.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (e: Exception) {
            AppLogger.warn(TAG, "Sync-frame request failed ($reason): ${e.message}")
        }
    }

    private fun audioConfigsMatch(a: HlsMp4Builder.AudioInit, b: HlsMp4Builder.AudioInit): Boolean =
        a.codec == b.codec &&
            a.sampleRate == b.sampleRate &&
            a.channels == b.channels &&
            a.codecData.contentEquals(b.codecData)

    private fun safeStopRelease(codec: MediaCodec?) {
        if (codec == null) return
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
    }

    private fun describeError(e: Throwable): String {
        val top = e.stackTrace.take(4).joinToString(" <- ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
        return "${e.javaClass.simpleName}: ${e.message} @ $top"
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
        val out = ByteArrayOutputStream()
        out.write(1)
        out.write(s[1].toInt() and 0xFF)
        out.write(s[2].toInt() and 0xFF)
        out.write(s[3].toInt() and 0xFF)
        out.write(0xFF)
        out.write(0xE0 or (sps.size and 0x1F))
        for (n in sps) {
            out.write((n.size shr 8) and 0xFF)
            out.write(n.size and 0xFF)
            out.write(n)
        }
        out.write(pps.size and 0xFF)
        for (n in pps) {
            out.write((n.size shr 8) and 0xFF)
            out.write(n.size and 0xFF)
            out.write(n)
        }
        return out.toByteArray()
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
            while (j < data.size) {
                if (startCodeLength(data, j) > 0) { end = j; break }
                j++
            }
            if (end > start) nalus.add(data.copyOfRange(start, end))
            i = end
        }
        return nalus
    }

    private fun startCodeLength(data: ByteArray, offset: Int): Int {
        if (offset + 4 <= data.size &&
            data[offset] == 0.toByte() &&
            data[offset + 1] == 0.toByte() &&
            data[offset + 2] == 0.toByte() &&
            data[offset + 3] == 1.toByte()
        ) return 4
        if (offset + 3 <= data.size &&
            data[offset] == 0.toByte() &&
            data[offset + 1] == 0.toByte() &&
            data[offset + 2] == 1.toByte()
        ) return 3
        return 0
    }

    companion object {
        private const val TAG = "HlsSegmentPipeline"
        private const val OUTPUT_AUDIO_MIME = "audio/mp4a-latm"
        private const val OUTPUT_AUDIO_BITRATE = 192_000
        private const val OUTPUT_AUDIO_CHANNEL_COUNT = 2
        private const val TIMEOUT_US = 10_000L
        private const val AUDIO_SAMPLE_BUFFER_SIZE = 64 * 1024

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
}