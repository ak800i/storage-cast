package com.storagecast.media

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class NeedsRecast(val reason: String, val startMs: Long, val quality: CastQuality)

/**
 * Orchestrates an HLS VOD presentation for a single source file transcoded on demand.
 *
 * The playlist is a VOD media playlist (terminated by `#EXT-X-ENDLIST`) so the Cast
 * Web Receiver treats it as seekable VOD rather than a live stream. Each `#EXTINF`
 * segment is an fMP4 media segment produced on demand by [HlsSegmentTranscoder] and
 * cached, with one shared `#EXT-X-MAP` init segment.
 *
 * Segment boundaries are uniform [SEGMENT_DURATION_US] slices of the source timeline;
 * because the video is re-encoded, each segment starts on a fresh IDR, so the receiver
 * can seek to any segment.
 */
class HlsTranscodeSession(
    private val inputPath: String,
    private val probeResult: MediaProbeResult,
    private val selectedAudioTrack: AudioTrackInfo?,
    /** When true, mux source audio untouched (5.1 passthrough) instead of AAC transcode. */
    private val copyAudio: Boolean = false,
    /** Raw WebVTT bytes for the selected subtitle, or null. Served as an in-manifest rendition. */
    private val subtitleVtt: ByteArray? = null,
    private val quality: CastQuality = CastQuality.AUTO,
    private val configForQuality: (CastQuality) -> CommittedEncoderConfig = { selectedQuality ->
        val video = probeResult.primaryVideo ?: error("HLS transcode requires a video track")
        CommittedEncoderConfig.derive(
            video.width,
            video.height,
            video.bitrate,
            video.frameRate.toInt(),
            selectedQuality,
        )
    },
    /**
     * Invoked (while the session lock is held) when a produced segment's avcC/audio init can't match
     * the committed init. The callback MUST be non-blocking and MUST NOT re-enter this session
     * (e.g. call release()/segmentBytes()); the production wiring posts to the UI thread.
     */
    private val onNeedsRecast: (NeedsRecast) -> Unit = {},
    private val forcePerSegment: Boolean = false,
) {
    companion object {
        private const val TAG = "HlsTranscodeSession"
        const val SEGMENT_DURATION_US = 6_000_000L
        private const val MAX_CACHED_SEGMENTS = 10
        const val PREBUFFER = 3
        const val LEAD = 4
        const val READAHEAD = 2
        const val WAIT_MARGIN = 2
        const val BACK_BUFFER = 2
        const val RELOCATE_AFTER = 2
        const val RATIO_THRESHOLD = 0.85
    }

    val hasSubtitles: Boolean = subtitleVtt != null && subtitleVtt.isNotEmpty()

    private val transcoder = HlsSegmentTranscoder()
    private val durationUs: Long = (probeResult.durationMs.coerceAtLeast(0)) * 1000
    private val segmentCount: Int =
        if (durationUs <= 0) 1
        else ((durationUs + SEGMENT_DURATION_US - 1) / SEGMENT_DURATION_US).toInt().coerceAtLeast(1)

    private val audioCodecAttr: String = HlsTranscodeMath.hlsAudioCodecAttr()

    @Volatile
    private var initSegment: ByteArray? = null
    private var videoInit: HlsMp4Builder.VideoInit? = null
    private var audioInit: HlsMp4Builder.AudioInit? = null

    private val lock = ReentrantLock()
    private val produced = lock.newCondition()
    private val segmentCache = object : LinkedHashMap<Int, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean =
            size > MAX_CACHED_SEGMENTS
    }
    private var coordinator: HlsSegmentCoordinator? = null
    private var pipeline: HlsSegmentPipeline? = null
    @Volatile private var committedConfig: CommittedEncoderConfig? = null
    @Volatile private var frontier: Int = -1
    @Volatile private var pipelineBase: Int = 0
    @Volatile private var producedSinceRebase = false
    @Volatile private var effectiveCopyAudio = false
    @Volatile private var generation = 0
    @Volatile var draining: Boolean = false
    @Volatile private var released = false
    private val oneOffLock = Any()
    private val skipped = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val inFlight = java.util.concurrent.ConcurrentHashMap<Int, FutureTask<ByteArray?>>()

    /** Builds the VOD media playlist. */
    fun playlist(basePath: String): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:7\n")
        sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n")
        sb.append("#EXT-X-TARGETDURATION:").append((SEGMENT_DURATION_US / 1_000_000)).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n")
        sb.append("#EXT-X-MAP:URI=\"").append(basePath).append("/init.mp4\"\n")
        for (i in 0 until segmentCount) {
            val segUs = if (i == segmentCount - 1 && durationUs > 0) {
                durationUs - i * SEGMENT_DURATION_US
            } else SEGMENT_DURATION_US
            val secs = (segUs.coerceAtLeast(1)).toDouble() / 1_000_000.0
            sb.append("#EXTINF:").append(String.format(java.util.Locale.US, "%.3f", secs)).append(",\n")
            sb.append(basePath).append("/seg").append(i).append(".m4s\n")
        }
        sb.append("#EXT-X-ENDLIST\n")
        return sb.toString()
    }

    /**
     * Master playlist that references the video media playlist plus an in-manifest
     * WebVTT subtitle rendition. Sideloaded text tracks don't reliably follow the HLS
     * media timeline on the Cast receiver (subtitles desync on seek); an in-manifest
     * rendition shares the video timeline so cues stay aligned through seeks.
     */
    fun masterPlaylist(basePath: String): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:7\n")
        if (hasSubtitles) {
            sb.append("#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"Subtitles\",")
            sb.append("DEFAULT=YES,AUTOSELECT=YES,FORCED=NO,LANGUAGE=\"en\",URI=\"")
            sb.append(basePath).append("/subs.m3u8\"\n")
            sb.append("#EXT-X-STREAM-INF:BANDWIDTH=8000000,CODECS=\"avc1.640029,").append(audioCodecAttr).append("\",SUBTITLES=\"subs\"\n")
        } else {
            sb.append("#EXT-X-STREAM-INF:BANDWIDTH=8000000,CODECS=\"avc1.640029,").append(audioCodecAttr).append("\"\n")
        }
        sb.append(basePath).append("/playlist.m3u8\n")
        return sb.toString()
    }

    /** WebVTT subtitle media playlist: a single segment covering the whole VOD. */
    fun subtitlePlaylist(basePath: String): String {
        val totalSecs = (durationUs.coerceAtLeast(1)).toDouble() / 1_000_000.0
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:7\n")
        sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n")
        sb.append("#EXT-X-TARGETDURATION:").append(Math.ceil(totalSecs).toInt()).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n")
        sb.append("#EXTINF:").append(String.format(java.util.Locale.US, "%.3f", totalSecs)).append(",\n")
        sb.append(basePath).append("/subs.vtt\n")
        sb.append("#EXT-X-ENDLIST\n")
        return sb.toString()
    }

    /**
     * The WebVTT body with an `X-TIMESTAMP-MAP` header so the receiver maps cue local
     * time directly onto the (0-based, absolute-source) media timeline. MPEGTS:0 +
     * LOCAL:0 is an identity mapping, so a cue at time T shows at media time T.
     *
     * The `X-TIMESTAMP-MAP` line is injected immediately after the existing `WEBVTT`
     * signature so it stays inside the header block (before the first blank line),
     * preserving any other header metadata or STYLE blocks that follow.
     */
    fun subtitleVttBytes(): ByteArray? {
        val raw = subtitleVtt ?: return null
        var text = String(raw, Charsets.UTF_8)
        // Drop a leading UTF-8 BOM if present.
        if (text.isNotEmpty() && text[0] == '\uFEFF') text = text.substring(1)

        val mapLine = "X-TIMESTAMP-MAP=MPEGTS:0,LOCAL:00:00:00.000"
        // Already has a timestamp map? Leave it untouched.
        if (text.contains("X-TIMESTAMP-MAP")) return text.toByteArray(Charsets.UTF_8)

        val nl = text.indexOf('\n')
        val out = if (nl < 0 || !text.take(6).startsWith("WEBVTT")) {
            // No newline yet, or doesn't start with the signature — write a clean header.
            "WEBVTT\n$mapLine\n\n" + text.removePrefix("WEBVTT")
        } else {
            // Insert the map line right after the WEBVTT signature line.
            val firstLineEnd = nl + 1
            text.substring(0, firstLineEnd) + mapLine + "\n" + text.substring(firstLineEnd)
        }
        return out.toByteArray(Charsets.UTF_8)
    }

    private fun newPipeline(baseIndex: Int): HlsSegmentPipeline {
        val gen = generation
        return HlsSegmentPipeline(
            inputPath, probeResult, selectedAudioTrack, effectiveCopyAudio,
            committedConfig!!, SEGMENT_DURATION_US, LEAD,
            onSegment = { putSegment(gen, it) },
            onSkipped = { idx -> lock.withLock { if (gen == generation) { skipped.add(idx); produced.signalAll() } } },
            playhead = { lock.withLock { coordinator?.prevIndex ?: baseIndex } },
        )
    }

    fun prepare(initialSegmentIndex: Int) {
        effectiveCopyAudio = HlsTranscodeMath.effectiveCopyAudio(
            copyAudio,
            (selectedAudioTrack ?: probeResult.primaryAudio)?.mime,
            (selectedAudioTrack ?: probeResult.primaryAudio)?.channelCount ?: 2
        )

        if (forcePerSegment) {
            // Persistent-mismatch fallback: no pipeline, no coordinator. Every request is served by a
            // one-off build; capture the committed init from a single one-off here.
            committedConfig = configForQuality(quality)
            lock.withLock { coordinator = null; pipeline = null }
            buildOneOff(initialSegmentIndex)
            lock.withLock { initSegment ?: error("init not ready after prepare (per-segment)") }
            return
        }

        val coord = HlsSegmentCoordinator(initialSegmentIndex, LEAD, READAHEAD, WAIT_MARGIN, BACK_BUFFER, RELOCATE_AFTER)
        lock.withLock { coordinator = coord }

        if (quality == CastQuality.AUTO) {
            val fallback = ResolutionFallback(CastQuality.autoRungs(), RATIO_THRESHOLD)
            while (true) {
                startPipelineGeneration(initialSegmentIndex, configForQuality(fallback.current))
                val ratio = measureSteadyStateRatio(initialSegmentIndex, timeoutMs = 20_000)
                if (!fallback.evaluate(ratio)) break
            }
        } else {
            startPipelineGeneration(initialSegmentIndex, configForQuality(quality))
        }

        waitForFrontier(initialSegmentIndex + PREBUFFER - 1, timeoutMs = 20_000)
    }

    /**
     * Cancel any existing pipeline (OUTSIDE the lock — cancel() joins the worker, which takes the
     * lock) and start a fresh generation at [config]. A rung change means a NEW committed init, so
     * the prior rung's cache/init are discarded here.
     */
    private fun startPipelineGeneration(baseIndex: Int, config: CommittedEncoderConfig) {
        val old = lock.withLock {
            val existing = pipeline
            pipeline = null
            existing
        }
        old?.cancel()
        lock.withLock {
            committedConfig = config
            generation++
            pipelineBase = baseIndex
            frontier = baseIndex - 1
            producedSinceRebase = false
            skipped.clear()
            segmentCache.clear()
            videoInit = null
            audioInit = null
            initSegment = null
        }
        val pipe = newPipeline(baseIndex)
        lock.withLock { pipeline = pipe }
        pipe.start(baseIndex)
    }

    /**
     * Wall time for the frontier to advance from segment [baseIndex]+1 to [baseIndex]+2 (steady state,
     * excluding the pre-roll-inflated first segment), divided by one segment's content seconds.
     * Returns a small ratio when there are too few segments to measure (commit), and a large ratio on
     * timeout (too slow -> step down if a lower rung exists; at the floor evaluate() commits anyway).
     */
    private fun measureSteadyStateRatio(baseIndex: Int, timeoutMs: Long): Double {
        val secondIdx = (baseIndex + 1).coerceAtMost(segmentCount - 1)
        val thirdIdx = (baseIndex + 2).coerceAtMost(segmentCount - 1)
        if (thirdIdx <= secondIdx) return 0.0
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        lock.lock()
        try {
            while (frontier < secondIdx && System.nanoTime() < deadline) {
                produced.await(250, TimeUnit.MILLISECONDS)
            }
            val t2 = System.nanoTime()
            while (frontier < thirdIdx && System.nanoTime() < deadline) {
                produced.await(250, TimeUnit.MILLISECONDS)
            }
            if (frontier < thirdIdx) return Double.MAX_VALUE
            val t3 = System.nanoTime()
            val wallSec = (t3 - t2).toDouble() / 1_000_000_000.0
            val contentSec = SEGMENT_DURATION_US.toDouble() / 1_000_000.0
            return wallSec / contentSec
        } finally {
            lock.unlock()
        }
    }

    private fun waitForFrontier(targetIndex: Int, timeoutMs: Long) {
        val target = targetIndex.coerceAtMost(segmentCount - 1)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        lock.lock()
        try {
            while (frontier < target && System.nanoTime() < deadline) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                produced.await(remaining.coerceAtMost(TimeUnit.MILLISECONDS.toNanos(250)), TimeUnit.NANOSECONDS)
            }
        } finally {
            lock.unlock()
        }
        if (lock.withLock { initSegment == null }) {
            buildOneOff(pipelineBase)
        }
        lock.withLock { initSegment ?: error("init not ready after prepare") }
    }

    private fun putSegment(gen: Int, result: HlsSegmentPipeline.SegmentResult) {
        lock.withLock {
            if (gen != generation || result.index < pipelineBase) return
            if (videoInit == null) {
                videoInit = result.videoInit; audioInit = result.audioInit
                initSegment = HlsMp4Builder.buildInitSegment(result.videoInit, result.audioInit)
            }
            val videoBad = !HlsTranscodeMath.avcConfigsMatch(videoInit!!.avcC, result.videoInit.avcC)
            val ai = audioInit; val rai = result.audioInit
            // A video-only committed init must not accept a later segment that carries audio (the
            // receiver would get undecodable audio under a video-only init.mp4). Plus codec/field drift.
            val audioBad = (ai == null && rai != null) ||
                (ai != null && rai != null &&
                 (ai.codec != rai.codec || !ai.codecData.contentEquals(rai.codecData) ||
                  ai.sampleRate != rai.sampleRate || ai.channels != rai.channels))
            if (videoBad || audioBad) {
                if (!draining) {   // fire the recast once; later mismatches on this session are no-ops
                    draining = true
                    onNeedsRecast(NeedsRecast("init-mismatch", result.index.toLong() * SEGMENT_DURATION_US / 1000, quality))
                }
                return
            }
            segmentCache[result.index] = result.bytes
            frontier = result.index
            producedSinceRebase = true
            produced.signalAll()
        }
    }

    /** Returns the fMP4 media segment for [index] (cached). */
    fun segmentBytes(index: Int): ByteArray? {
        if (index < 0 || index >= segmentCount) return null
        if (released) return null   // fast path: torn-down session serves nothing
        lock.lock()
        try {
            if (released) return null   // release() may have run between the pre-lock check and here
            val coord = coordinator ?: return buildOneOff(index)
            val frontier = if (producedSinceRebase) this.frontier else Int.MIN_VALUE / 2
            val low = coord.prevIndex - BACK_BUFFER
            when (val d = coord.route(index, frontier, low) { i -> segmentCache.containsKey(i) }) {
                HlsSegmentCoordinator.Decision.ServeCached -> return segmentCache[index]
                is HlsSegmentCoordinator.Decision.WaitForProduction -> {
                    if (index in skipped) return buildOneOff(index)
                    val deadline = System.nanoTime() + 5_000_000_000L
                    while (!segmentCache.containsKey(index) && index !in skipped && !released && System.nanoTime() < deadline) {
                        produced.await(250, TimeUnit.MILLISECONDS)
                    }
                    if (released) return null
                    return segmentCache[index] ?: buildOneOff(index)
                }
                is HlsSegmentCoordinator.Decision.OneOff -> {
                    val bytes = buildOneOff(index)
                    if (coordinator != null) d.rebaseTo?.let { reBase(it) }
                    return bytes
                }
            }
        } finally {
            if (lock.isHeldByCurrentThread) lock.unlock()
        }
    }

    private fun buildOneOff(index: Int): ByteArray? {
        val heldByMe = lock.isHeldByCurrentThread
        if (heldByMe) lock.unlock()
        try {
            val task = inFlight.computeIfAbsent(index) {
                FutureTask { actuallyBuildOneOff(index) }
            }
            try {
                if (inFlight[index] === task) task.run()
                return task.get()
            } finally {
                inFlight.remove(index, task)
            }
        } finally {
            if (heldByMe) lock.lock()
        }
    }

    private fun runTranscode(index: Int, cfg: CommittedEncoderConfig) =
        transcoder.transcodeRange(
            inputPath, probeResult, selectedAudioTrack,
            index * SEGMENT_DURATION_US, (index + 1) * SEGMENT_DURATION_US, effectiveCopyAudio, cfg
        )

    private fun actuallyBuildOneOff(index: Int): ByteArray? = synchronized(oneOffLock) {
        if (released) return@synchronized null   // session torn down: skip the (codec-allocating) build
        val cfg = committedConfig!!
        val res = try {
            runTranscode(index, cfg)
        } catch (e: Exception) {
            // 1+1 codec-limit: free the pipeline's codecs and fall back to per-segment one-off mode.
            // Bump generation so any callback already blocked entering putSegment/onSkipped from the
            // now-cancelled pipeline is dropped by the stale-callback guard.
            val old = lock.withLock { generation++; val p = pipeline; pipeline = null; coordinator = null; p }
            old?.cancel()
            runTranscode(index, cfg)
        }
        val bytes = HlsMp4Builder.buildMediaSegment(index + 1, res.videoSamples, res.audioSamples, 33_333L, 21_333L)
        lock.withLock {
            if (videoInit == null) {
                videoInit = res.video; audioInit = res.audio
                initSegment = HlsMp4Builder.buildInitSegment(res.video, res.audio)
            }
            val videoBad = !HlsTranscodeMath.avcConfigsMatch(videoInit!!.avcC, res.video?.avcC)
            val cai = audioInit; val rai = res.audio
            val audioBad = (cai == null && rai != null) ||
                (cai != null && rai != null &&
                 (cai.codec != rai.codec || !cai.codecData.contentEquals(rai.codecData) ||
                  cai.sampleRate != rai.sampleRate || cai.channels != rai.channels))
            if (videoBad || audioBad) {
                if (!draining) {
                    draining = true
                    onNeedsRecast(NeedsRecast("init-mismatch", index.toLong() * SEGMENT_DURATION_US / 1000, quality))
                }
                return@withLock null
            }
            segmentCache[index] = bytes
            produced.signalAll()
            bytes
        }
    }

    private fun reBase(baseIndex: Int) {
        val old = pipeline
        pipeline = null
        generation++
        pipelineBase = baseIndex
        frontier = baseIndex - 1
        producedSinceRebase = false
        skipped.clear()
        lock.unlock()
        try {
            old?.cancel()
            val pipe = newPipeline(baseIndex)
            pipe.start(baseIndex)
            lock.lock()
            if (released) {
                // The session was released during this unlocked window; do not publish the new
                // pipeline. Cancel it with the lock NOT held (cancel() joins the worker, which takes
                // the lock), then re-acquire so the caller's `finally` can unlock as it expects.
                lock.unlock()
                pipe.cancel()
                lock.lock()
            } else {
                pipeline = pipe
            }
        } catch (t: Throwable) {
            lock.lock()
            throw t
        }
    }

    fun initBytes(): ByteArray = lock.withLock { initSegment ?: error("init not ready; prepare() must run first") }

    /** Frees cached transcoded segments. Safe to call when the session is no longer cast. */
    fun release() {
        // Capture the pipeline and mark released UNDER the lock so a concurrent reBase (which nulls
        // `pipeline` during its unlocked window and republishes afterward) observes `released` and
        // discards its new pipeline instead of orphaning it. Cancel OUTSIDE the lock (cancel() joins
        // the producer thread, which itself takes the lock).
        val old = lock.withLock {
            released = true
            val p = pipeline
            pipeline = null
            coordinator = null
            segmentCache.clear()
            initSegment = null
            p
        }
        old?.cancel()
    }
}
