package com.storagecast.media

import com.storagecast.log.AppLogger
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Hand-rolled fragmented-MP4 (ISO BMFF / CMAF-style) writer for live streaming.
 *
 * Emits:
 *  1. An **init segment** — `ftyp` + `moov` (with `mvex`/`trex` so the file is
 *     declared fragmented and the per-track `stbl` tables stay empty).
 *  2. A sequence of **media fragments** — each `moof` + `mdat`, flushed roughly on
 *     video key-frame boundaries (~1 s) so playback can start almost immediately.
 *
 * All tracks use a media timescale of 1,000,000 (microseconds) so sample durations
 * are exact PTS deltas and no rounding is required. Video must have PTS == DTS
 * (no B-frames) — the encoder is configured to avoid B-frames — so no composition
 * time offsets (`ctts`/`cslg`) are written.
 *
 * Track IDs: video = 1, audio = 2 (kept constant even when only one is present).
 */
class Fmp4Writer(
    private val out: OutputStream,
    private val hasVideo: Boolean,
    private val hasAudio: Boolean
) {

    data class VideoConfig(val avcC: ByteArray, val width: Int, val height: Int)

    /** Audio codecs that can be muxed into this fMP4 (transcoded AAC or passthrough). */
    enum class AudioCodec { AAC, AC3, EAC3 }

    /**
     * @param codecData For [AudioCodec.AAC] the raw AudioSpecificConfig (ASC); for
     * [AudioCodec.AC3]/[AudioCodec.EAC3] the contents of the `dac3`/`dec3` box.
     */
    data class AudioConfig(
        val codec: AudioCodec,
        val codecData: ByteArray,
        val sampleRate: Int,
        val channels: Int
    )

    private var videoConfig: VideoConfig? = null
    private var audioConfig: AudioConfig? = null

    companion object {
        private const val TAG = "Fmp4Writer"
        private const val TIMESCALE = 1_000_000L            // microseconds
        private const val MOVIE_TIMESCALE = 1000L
        private const val VIDEO_TRACK_ID = 1
        private const val AUDIO_TRACK_ID = 2

        private const val MIN_FRAGMENT_US = 1_000_000L      // flush at key-frame after ~1 s
        private const val MAX_FRAGMENT_US = 4_000_000L      // hard cap so audio-only still flushes

        // Default duration for the final sample of a stream (when no successor exists).
        private const val DEFAULT_VIDEO_SAMPLE_US = 33_333L  // ~30 fps
        private const val DEFAULT_AUDIO_SAMPLE_US = 21_333L  // 1024 samples @ 48 kHz

        // Video trun sample_flags
        private const val SAMPLE_FLAG_KEYFRAME = 0x02000000  // depends_on=2, non_sync=0
        private const val SAMPLE_FLAG_NON_KEY = 0x01010000   // depends_on=1, non_sync=1
    }

    private class FragSample(val data: ByteArray, val durationUs: Long, val keyframe: Boolean)

    private inner class Track(val trackId: Int, val defaultSampleUs: Long) {
        val ready = ArrayList<FragSample>()
        var readyDurUs = 0L
        var baseDecodeTimeUs = 0L
        private var hasPending = false
        private var pendingData: ByteArray = ByteArray(0)
        private var pendingPtsUs = 0L
        private var pendingKey = false
        private var lastDurUs = defaultSampleUs

        fun add(data: ByteArray, ptsUs: Long, keyframe: Boolean) {
            if (hasPending) {
                var dur = ptsUs - pendingPtsUs
                if (dur <= 0) dur = lastDurUs
                lastDurUs = dur
                ready.add(FragSample(pendingData, dur, pendingKey))
                readyDurUs += dur
            }
            pendingData = data
            pendingPtsUs = ptsUs
            pendingKey = keyframe
            hasPending = true
        }

        /** Moves the held-back final sample into the ready list using an estimated duration. */
        fun flushPending() {
            if (hasPending) {
                ready.add(FragSample(pendingData, lastDurUs, pendingKey))
                readyDurUs += lastDurUs
                hasPending = false
            }
        }

        fun clearReady() {
            ready.clear()
            readyDurUs = 0L
        }
    }

    private val video = if (hasVideo) Track(VIDEO_TRACK_ID, DEFAULT_VIDEO_SAMPLE_US) else null
    private val audio = if (hasAudio) Track(AUDIO_TRACK_ID, DEFAULT_AUDIO_SAMPLE_US) else null
    private var sequenceNumber = 1
    private var initWritten = false

    // ──────────────────────────────────────────────────────────────────────────
    //  Public API
    // ──────────────────────────────────────────────────────────────────────────

    fun hasVideoConfig(): Boolean = videoConfig != null
    fun hasAudioConfig(): Boolean = audioConfig != null

    /**
     * Supplies the H.264 codec config (avcC). A hardware encoder only produces its
     * SPS/PPS after the first frame is encoded, so configs arrive during the encode
     * loop rather than up front. The init segment is written once every present
     * track has its config.
     */
    fun setVideoConfig(avcC: ByteArray, width: Int, height: Int) {
        if (videoConfig != null) return
        videoConfig = VideoConfig(avcC, width, height)
        maybeWriteInit()
    }

    fun setAudioConfig(asc: ByteArray, sampleRate: Int, channels: Int) {
        setAudioConfig(AudioCodec.AAC, asc, sampleRate, channels)
    }

    /**
     * Supplies the audio codec config. [codec] selects the sample entry written into
     * the init segment (`mp4a`/`ac-3`/`ec-3`); [codecData] is the matching codec box
     * payload (AAC ASC, or `dac3`/`dec3` contents). The init segment is written once
     * every present track has its config.
     */
    fun setAudioConfig(codec: AudioCodec, codecData: ByteArray, sampleRate: Int, channels: Int) {
        if (audioConfig != null) return
        audioConfig = AudioConfig(codec, codecData, sampleRate, channels)
        maybeWriteInit()
    }

    private fun maybeWriteInit() {
        if (initWritten) return
        if (hasVideo && videoConfig == null) return
        if (hasAudio && audioConfig == null) return
        out.write(buildFtyp())
        out.write(buildMoov())
        out.flush()
        initWritten = true
        AppLogger.info(TAG, "Wrote fMP4 init segment (video=$hasVideo, audio=$hasAudio)")
    }

    fun addVideoSample(rawData: ByteArray, ptsUs: Long, keyframe: Boolean) {
        val v = video ?: return
        val data = ensureAvcc(rawData)
        // Flush the previous fragment before starting a new GOP at a key-frame.
        if (keyframe && v.readyDurUs >= MIN_FRAGMENT_US) {
            flushFragment()
        }
        v.add(data, ptsUs, keyframe)
        if (v.readyDurUs >= MAX_FRAGMENT_US) {
            flushFragment()
        }
    }

    fun addAudioSample(rawData: ByteArray, ptsUs: Long) {
        val a = audio ?: return
        a.add(rawData.copyOf(), ptsUs, true)
        // When there is no video track, drive fragmentation off the audio timeline.
        if (video == null && a.readyDurUs >= MIN_FRAGMENT_US) {
            flushFragment()
        }
    }

    fun finish() {
        video?.flushPending()
        audio?.flushPending()
        flushFragment()
        out.flush()
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Fragment writing
    // ──────────────────────────────────────────────────────────────────────────

    private fun flushFragment() {
        // Do not emit fragments until the init segment (which needs every track's
        // codec config) has been written. Samples keep accumulating until then.
        if (!initWritten) return
        val vSamples = video?.ready ?: emptyList()
        val aSamples = audio?.ready ?: emptyList()
        if (vSamples.isEmpty() && aSamples.isEmpty()) return

        val trafs = ArrayList<TrafData>()
        if (vSamples.isNotEmpty()) {
            trafs.add(buildTraf(VIDEO_TRACK_ID, video!!.baseDecodeTimeUs, vSamples, includeFlags = true))
        }
        if (aSamples.isNotEmpty()) {
            trafs.add(buildTraf(AUDIO_TRACK_ID, audio!!.baseDecodeTimeUs, aSamples, includeFlags = false))
        }

        val mfhd = fullBox("mfhd", 0, 0) { it.u32(sequenceNumber.toLong()) }

        // Assemble moof content (mfhd + trafs).
        val moofContent = ByteArrayOutputStream()
        moofContent.write(mfhd)
        for (t in trafs) moofContent.write(t.bytes)
        val moofLen = 8 + moofContent.size()

        val moof = box("moof", moofContent.toByteArray())

        // Patch each trun's data_offset (relative to the start of the moof box).
        // trun data_offset sits at a constant offset 60 bytes inside each traf box
        // (8 traf header + 16 tfhd + 20 tfdt + 16 into the trun).
        var dataCursor = moofLen + 8 // skip moof + mdat header
        var trafOffsetInMoof = 8 + mfhd.size // moof header + mfhd
        for (t in trafs) {
            val patchPos = trafOffsetInMoof + 60
            patchU32(moof, patchPos, dataCursor.toLong())
            dataCursor += t.dataSize
            trafOffsetInMoof += t.bytes.size
        }

        out.write(moof)

        // mdat: header + each track's sample data in the same order as the trafs.
        val totalData = trafs.sumOf { it.dataSize }
        val mdatHeader = ByteArrayOutputStream()
        mdatHeader.u32((8 + totalData).toLong())
        mdatHeader.str4("mdat")
        out.write(mdatHeader.toByteArray())
        for (t in trafs) {
            for (s in t.samples) out.write(s.data)
        }
        out.flush()

        // Advance decode times and reset.
        if (vSamples.isNotEmpty()) {
            video!!.baseDecodeTimeUs += vSamples.sumOf { it.durationUs }
            video.clearReady()
        }
        if (aSamples.isNotEmpty()) {
            audio!!.baseDecodeTimeUs += aSamples.sumOf { it.durationUs }
            audio.clearReady()
        }
        sequenceNumber++
    }

    private class TrafData(
        val bytes: ByteArray,
        val samples: List<FragSample>,
        val dataSize: Int
    )

    private fun buildTraf(trackId: Int, baseDecodeTimeUs: Long, samples: List<FragSample>, includeFlags: Boolean): TrafData {
        // tfhd: flags = 0x020000 (default-base-is-moof), body = track_ID only.
        val tfhd = fullBox("tfhd", 0, 0x020000) { it.u32(trackId.toLong()) }
        // tfdt v1: 64-bit baseMediaDecodeTime.
        val tfdt = fullBox("tfdt", 1, 0) { it.u64(baseDecodeTimeUs) }

        var dataSize = 0
        for (s in samples) dataSize += s.data.size

        val trunFlags = if (includeFlags) 0x000701 else 0x000301 // data-offset + duration + size (+ flags)
        val trun = fullBox("trun", 0, trunFlags) { b ->
            b.u32(samples.size.toLong())
            b.u32(0L) // data_offset placeholder (patched later)
            for (s in samples) {
                b.u32(s.durationUs)
                b.u32(s.data.size.toLong())
                if (includeFlags) {
                    b.u32((if (s.keyframe) SAMPLE_FLAG_KEYFRAME else SAMPLE_FLAG_NON_KEY).toLong())
                }
            }
        }

        val traf = box("traf", tfhd + tfdt + trun)
        return TrafData(traf, samples, dataSize)
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Init segment (ftyp + moov)
    // ──────────────────────────────────────────────────────────────────────────

    private fun buildFtyp(): ByteArray {
        val body = ByteArrayOutputStream()
        body.str4("isom")       // major_brand
        body.u32(0x00000200L)   // minor_version
        body.str4("isom")
        body.str4("iso5")
        body.str4("iso6")
        body.str4("mp41")
        body.str4("avc1")
        return box("ftyp", body.toByteArray())
    }

    private fun buildMoov(): ByteArray {
        val children = ByteArrayOutputStream()
        children.write(buildMvhd())
        videoConfig?.let { children.write(buildVideoTrak(it)) }
        audioConfig?.let { children.write(buildAudioTrak(it)) }
        children.write(buildMvex())
        return box("moov", children.toByteArray())
    }

    private fun buildMvhd(): ByteArray {
        return fullBox("mvhd", 0, 0) { b ->
            b.u32(0L)                 // creation_time
            b.u32(0L)                 // modification_time
            b.u32(MOVIE_TIMESCALE)    // timescale
            b.u32(0L)                 // duration (unknown for live/fragmented)
            b.u32(0x00010000L)        // rate 1.0
            b.u16(0x0100)             // volume 1.0
            b.u16(0)                  // reserved
            b.u32(0L); b.u32(0L)      // reserved
            writeIdentityMatrix(b)
            repeat(6) { b.u32(0L) }   // pre_defined
            b.u32(3L)                 // next_track_ID
        }
    }

    private fun buildMvex(): ByteArray {
        val children = ByteArrayOutputStream()
        if (videoConfig != null) children.write(buildTrex(VIDEO_TRACK_ID))
        if (audioConfig != null) children.write(buildTrex(AUDIO_TRACK_ID))
        return box("mvex", children.toByteArray())
    }

    private fun buildTrex(trackId: Int): ByteArray {
        return fullBox("trex", 0, 0) { b ->
            b.u32(trackId.toLong())   // track_ID
            b.u32(1L)                 // default_sample_description_index
            b.u32(0L)                 // default_sample_duration
            b.u32(0L)                 // default_sample_size
            b.u32(0L)                 // default_sample_flags
        }
    }

    private fun buildVideoTrak(cfg: VideoConfig): ByteArray {
        val tkhd = fullBox("tkhd", 0, 0x000007) { b ->
            b.u32(0L); b.u32(0L)            // creation / modification
            b.u32(VIDEO_TRACK_ID.toLong())  // track_ID
            b.u32(0L)                       // reserved
            b.u32(0L)                       // duration
            b.u32(0L); b.u32(0L)            // reserved
            b.u16(0)                        // layer
            b.u16(0)                        // alternate_group
            b.u16(0)                        // volume (0 for video)
            b.u16(0)                        // reserved
            writeIdentityMatrix(b)
            b.u32((cfg.width.toLong() shl 16)) // width 16.16
            b.u32((cfg.height.toLong() shl 16)) // height 16.16
        }
        val mdia = buildMdia("vide", "VideoHandler", buildVideoMinf(cfg))
        return box("trak", tkhd + mdia)
    }

    private fun buildAudioTrak(cfg: AudioConfig): ByteArray {
        val tkhd = fullBox("tkhd", 0, 0x000007) { b ->
            b.u32(0L); b.u32(0L)
            b.u32(AUDIO_TRACK_ID.toLong())
            b.u32(0L)
            b.u32(0L)
            b.u32(0L); b.u32(0L)
            b.u16(0)
            b.u16(0)
            b.u16(0x0100)                   // volume 1.0
            b.u16(0)
            writeIdentityMatrix(b)
            b.u32(0L)                       // width
            b.u32(0L)                       // height
        }
        val mdia = buildMdia("soun", "SoundHandler", buildAudioMinf(cfg))
        return box("trak", tkhd + mdia)
    }

    private fun buildMdia(handler: String, handlerName: String, minf: ByteArray): ByteArray {
        val mdhd = fullBox("mdhd", 0, 0) { b ->
            b.u32(0L); b.u32(0L)            // creation / modification
            b.u32(TIMESCALE)               // timescale
            b.u32(0L)                       // duration
            b.u16(0x55C4)                   // language 'und'
            b.u16(0)                        // pre_defined
        }
        val hdlr = fullBox("hdlr", 0, 0) { b ->
            b.u32(0L)                       // pre_defined
            b.str4(handler)                 // handler_type
            b.u32(0L); b.u32(0L); b.u32(0L) // reserved
            b.write(handlerName.toByteArray(Charsets.UTF_8))
            b.u8(0)                         // null terminator
        }
        return box("mdia", mdhd + hdlr + minf)
    }

    private fun buildVideoMinf(cfg: VideoConfig): ByteArray {
        val vmhd = fullBox("vmhd", 0, 0x000001) { b ->
            b.u16(0)                        // graphicsmode
            b.u16(0); b.u16(0); b.u16(0)    // opcolor
        }
        val dinf = buildDinf()
        val stbl = buildStbl(buildAvc1(cfg))
        return box("minf", vmhd + dinf + stbl)
    }

    private fun buildAudioMinf(cfg: AudioConfig): ByteArray {
        val smhd = fullBox("smhd", 0, 0) { b ->
            b.u16(0)                        // balance
            b.u16(0)                        // reserved
        }
        val dinf = buildDinf()
        val sampleEntry = when (cfg.codec) {
            AudioCodec.AAC -> buildMp4a(cfg)
            AudioCodec.AC3 -> buildAudioSampleEntry("ac-3", cfg, box("dac3", cfg.codecData))
            AudioCodec.EAC3 -> buildAudioSampleEntry("ec-3", cfg, box("dec3", cfg.codecData))
        }
        val stbl = buildStbl(sampleEntry)
        return box("minf", smhd + dinf + stbl)
    }

    private fun buildDinf(): ByteArray {
        val url = fullBox("url ", 0, 0x000001) { } // self-contained, no location
        val dref = fullBox("dref", 0, 0) { b ->
            b.u32(1L)                       // entry_count
            b.write(url)
        }
        return box("dinf", dref)
    }

    private fun buildStbl(sampleEntry: ByteArray): ByteArray {
        val stsd = fullBox("stsd", 0, 0) { b ->
            b.u32(1L)                       // entry_count
            b.write(sampleEntry)
        }
        val stts = fullBox("stts", 0, 0) { it.u32(0L) }
        val stsc = fullBox("stsc", 0, 0) { it.u32(0L) }
        val stsz = fullBox("stsz", 0, 0) { b -> b.u32(0L); b.u32(0L) }
        val stco = fullBox("stco", 0, 0) { it.u32(0L) }
        return box("stbl", stsd + stts + stsc + stsz + stco)
    }

    private fun buildAvc1(cfg: VideoConfig): ByteArray {
        val body = ByteArrayOutputStream()
        repeat(6) { body.u8(0) }            // reserved
        body.u16(1)                         // data_reference_index
        body.u16(0)                         // pre_defined
        body.u16(0)                         // reserved
        repeat(3) { body.u32(0L) }          // pre_defined
        body.u16(cfg.width)
        body.u16(cfg.height)
        body.u32(0x00480000L)               // horizresolution 72 dpi
        body.u32(0x00480000L)               // vertresolution 72 dpi
        body.u32(0L)                        // reserved
        body.u16(1)                         // frame_count
        repeat(32) { body.u8(0) }           // compressorname
        body.u16(0x0018)                    // depth
        body.u16(0xFFFF)                    // pre_defined
        body.write(box("avcC", cfg.avcC))
        return box("avc1", body.toByteArray())
    }

    private fun buildMp4a(cfg: AudioConfig): ByteArray =
        buildAudioSampleEntry("mp4a", cfg, buildEsds(cfg.codecData))

    /**
     * Builds an ISO audio sample entry ([boxType] = mp4a/ac-3/ec-3) with the standard
     * AudioSampleEntry header followed by the codec-specific child box.
     */
    private fun buildAudioSampleEntry(boxType: String, cfg: AudioConfig, codecBox: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        repeat(6) { body.u8(0) }            // reserved
        body.u16(1)                         // data_reference_index
        body.u32(0L); body.u32(0L)          // reserved
        body.u16(cfg.channels)
        body.u16(16)                        // samplesize
        body.u16(0)                         // pre_defined
        body.u16(0)                         // reserved
        body.u32(cfg.sampleRate.toLong() shl 16) // samplerate 16.16
        body.write(codecBox)
        return box(boxType, body.toByteArray())
    }

    private fun buildEsds(asc: ByteArray): ByteArray {
        // DecoderSpecificInfo (tag 0x05)
        val dsi = descriptor(0x05, asc)
        // DecoderConfigDescriptor (tag 0x04)
        val dcdBody = ByteArrayOutputStream()
        dcdBody.u8(0x40)                    // objectTypeIndication = Audio ISO/IEC 14496-3 (AAC)
        dcdBody.u8(0x15)                    // streamType=audio(0x05<<2) | upStream(0) | reserved(1)
        dcdBody.u8(0); dcdBody.u16(0)       // bufferSizeDB (24-bit) = 0
        dcdBody.u32(0L)                     // maxBitrate
        dcdBody.u32(0L)                     // avgBitrate
        dcdBody.write(dsi)
        val dcd = descriptor(0x04, dcdBody.toByteArray())
        // SLConfigDescriptor (tag 0x06)
        val sl = descriptor(0x06, byteArrayOf(0x02))
        // ES_Descriptor (tag 0x03)
        val esBody = ByteArrayOutputStream()
        esBody.u16(0)                       // ES_ID
        esBody.u8(0)                        // flags
        esBody.write(dcd)
        esBody.write(sl)
        val es = descriptor(0x03, esBody.toByteArray())
        return fullBox("esds", 0, 0) { it.write(es) }
    }

    /** MPEG-4 descriptor with single-byte length (sufficient for AAC config sizes). */
    private fun descriptor(tag: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.u8(tag)
        out.u8(body.size and 0x7F)
        out.write(body)
        return out.toByteArray()
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  AVCC conversion + low-level helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Converts an Annex-B H.264 access unit (start-code separated) into the
     * length-prefixed (AVCC) form required inside MP4 `mdat`. If the sample is
     * already length-prefixed (no leading start code) it is returned unchanged.
     */
    private fun ensureAvcc(data: ByteArray): ByteArray {
        if (startCodeLen(data, 0) == 0) return data
        val out = ByteArrayOutputStream(data.size + 16)
        var i = 0
        while (i < data.size) {
            val scLen = startCodeLen(data, i)
            if (scLen == 0) { i++; continue }
            val start = i + scLen
            var end = data.size
            var j = start
            while (j < data.size) {
                if (startCodeLen(data, j) > 0) { end = j; break }
                j++
            }
            val len = end - start
            if (len > 0) {
                out.u32(len.toLong())
                out.write(data, start, len)
            }
            i = end
        }
        return out.toByteArray()
    }

    private fun startCodeLen(data: ByteArray, offset: Int): Int {
        if (offset + 4 <= data.size &&
            data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
            data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()) return 4
        if (offset + 3 <= data.size &&
            data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
            data[offset + 2] == 1.toByte()) return 3
        return 0
    }

    private fun writeIdentityMatrix(b: ByteArrayOutputStream) {
        b.u32(0x00010000L); b.u32(0L); b.u32(0L)
        b.u32(0L); b.u32(0x00010000L); b.u32(0L)
        b.u32(0L); b.u32(0L); b.u32(0x40000000L)
    }

    private fun box(type: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size + 8)
        out.u32((body.size + 8).toLong())
        out.str4(type)
        out.write(body)
        return out.toByteArray()
    }

    private fun fullBox(type: String, version: Int, flags: Int, write: (ByteArrayOutputStream) -> Unit): ByteArray {
        val body = ByteArrayOutputStream()
        body.u8(version)
        body.u8((flags shr 16) and 0xFF)
        body.u8((flags shr 8) and 0xFF)
        body.u8(flags and 0xFF)
        write(body)
        return box(type, body.toByteArray())
    }

    private fun patchU32(arr: ByteArray, pos: Int, value: Long) {
        arr[pos] = ((value shr 24) and 0xFF).toByte()
        arr[pos + 1] = ((value shr 16) and 0xFF).toByte()
        arr[pos + 2] = ((value shr 8) and 0xFF).toByte()
        arr[pos + 3] = (value and 0xFF).toByte()
    }

    // Big-endian write helpers on ByteArrayOutputStream.
    private fun ByteArrayOutputStream.u8(v: Int) { write(v and 0xFF) }
    private fun ByteArrayOutputStream.u16(v: Int) { write((v shr 8) and 0xFF); write(v and 0xFF) }
    private fun ByteArrayOutputStream.u32(v: Long) {
        write(((v shr 24) and 0xFF).toInt())
        write(((v shr 16) and 0xFF).toInt())
        write(((v shr 8) and 0xFF).toInt())
        write((v and 0xFF).toInt())
    }
    private fun ByteArrayOutputStream.u64(v: Long) {
        for (i in 7 downTo 0) write(((v shr (i * 8)) and 0xFF).toInt())
    }
    private fun ByteArrayOutputStream.str4(s: String) { write(s.toByteArray(Charsets.US_ASCII)) }
}
