package com.storagecast.media

import java.io.ByteArrayOutputStream

/**
 * Builds CMAF-style fragmented-MP4 pieces for HLS VOD playback:
 *  - an **init segment** (`ftyp` + `moov`) referenced by the playlist's `#EXT-X-MAP`,
 *  - **media segments** (`moof` + `mdat`), one per `#EXTINF` entry.
 *
 * This is intentionally a self-contained copy of the box primitives (it does NOT
 * touch [Fmp4Writer], which serves the working live path) so the experimental HLS
 * path can never regress live streaming.
 *
 * Video must be H.264 with PTS == DTS (no B-frames — the encoder is configured that
 * way), so no composition-time offsets are written. Track timescale is microseconds.
 */
object HlsMp4Builder {

    private const val TIMESCALE = 1_000_000L
    private const val MOVIE_TIMESCALE = 1000L
    private const val VIDEO_TRACK_ID = 1
    private const val AUDIO_TRACK_ID = 2
    private const val SAMPLE_FLAG_KEYFRAME = 0x02000000
    private const val SAMPLE_FLAG_NON_KEY = 0x01010000

    class Sample(val data: ByteArray, val ptsUs: Long, val keyframe: Boolean)

    class VideoInit(val avcC: ByteArray, val width: Int, val height: Int)

    /** Audio codecs muxable into an HLS fMP4 segment (transcoded AAC or passthrough). */
    enum class AudioCodec { AAC, AC3, EAC3 }

    /**
     * @param codecData For [AudioCodec.AAC] the raw AudioSpecificConfig (ASC); for
     * [AudioCodec.AC3]/[AudioCodec.EAC3] the `dac3`/`dec3` box contents.
     */
    class AudioInit(
        val codec: AudioCodec,
        val codecData: ByteArray,
        val sampleRate: Int,
        val channels: Int
    )

    // ──────────────────────────────────────────────────────────────────────────
    //  Init segment
    // ──────────────────────────────────────────────────────────────────────────

    fun buildInitSegment(video: VideoInit?, audio: AudioInit?): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(buildFtyp())
        out.write(buildMoov(video, audio))
        return out.toByteArray()
    }

    private fun buildFtyp(): ByteArray {
        val b = ByteArrayOutputStream()
        b.str4("isom"); b.u32(0x00000200L)
        b.str4("isom"); b.str4("iso5"); b.str4("iso6"); b.str4("mp41"); b.str4("avc1")
        return box("ftyp", b.toByteArray())
    }

    private fun buildMoov(video: VideoInit?, audio: AudioInit?): ByteArray {
        val c = ByteArrayOutputStream()
        c.write(buildMvhd())
        if (video != null) c.write(buildVideoTrak(video))
        if (audio != null) c.write(buildAudioTrak(audio))
        c.write(buildMvex(video != null, audio != null))
        return box("moov", c.toByteArray())
    }

    private fun buildMvhd(): ByteArray = fullBox("mvhd", 0, 0) { b ->
        b.u32(0L); b.u32(0L); b.u32(MOVIE_TIMESCALE); b.u32(0L)
        b.u32(0x00010000L); b.u16(0x0100); b.u16(0); b.u32(0L); b.u32(0L)
        writeIdentityMatrix(b); repeat(6) { b.u32(0L) }; b.u32(3L)
    }

    private fun buildMvex(hasVideo: Boolean, hasAudio: Boolean): ByteArray {
        val c = ByteArrayOutputStream()
        if (hasVideo) c.write(buildTrex(VIDEO_TRACK_ID))
        if (hasAudio) c.write(buildTrex(AUDIO_TRACK_ID))
        return box("mvex", c.toByteArray())
    }

    private fun buildTrex(trackId: Int): ByteArray = fullBox("trex", 0, 0) { b ->
        b.u32(trackId.toLong()); b.u32(1L); b.u32(0L); b.u32(0L); b.u32(0L)
    }

    private fun buildVideoTrak(cfg: VideoInit): ByteArray {
        val tkhd = fullBox("tkhd", 0, 0x000007) { b ->
            b.u32(0L); b.u32(0L); b.u32(VIDEO_TRACK_ID.toLong()); b.u32(0L); b.u32(0L)
            b.u32(0L); b.u32(0L); b.u16(0); b.u16(0); b.u16(0); b.u16(0)
            writeIdentityMatrix(b)
            b.u32(cfg.width.toLong() shl 16); b.u32(cfg.height.toLong() shl 16)
        }
        return box("trak", tkhd + buildMdia("vide", "VideoHandler", buildVideoMinf(cfg)))
    }

    private fun buildAudioTrak(cfg: AudioInit): ByteArray {
        val tkhd = fullBox("tkhd", 0, 0x000007) { b ->
            b.u32(0L); b.u32(0L); b.u32(AUDIO_TRACK_ID.toLong()); b.u32(0L); b.u32(0L)
            b.u32(0L); b.u32(0L); b.u16(0); b.u16(0); b.u16(0x0100); b.u16(0)
            writeIdentityMatrix(b); b.u32(0L); b.u32(0L)
        }
        return box("trak", tkhd + buildMdia("soun", "SoundHandler", buildAudioMinf(cfg)))
    }

    private fun buildMdia(handler: String, name: String, minf: ByteArray): ByteArray {
        val mdhd = fullBox("mdhd", 0, 0) { b ->
            b.u32(0L); b.u32(0L); b.u32(TIMESCALE); b.u32(0L); b.u16(0x55C4); b.u16(0)
        }
        val hdlr = fullBox("hdlr", 0, 0) { b ->
            b.u32(0L); b.str4(handler); b.u32(0L); b.u32(0L); b.u32(0L)
            b.write(name.toByteArray(Charsets.UTF_8)); b.u8(0)
        }
        return box("mdia", mdhd + hdlr + minf)
    }

    private fun buildVideoMinf(cfg: VideoInit): ByteArray {
        val vmhd = fullBox("vmhd", 0, 0x000001) { b -> b.u16(0); b.u16(0); b.u16(0); b.u16(0) }
        return box("minf", vmhd + buildDinf() + buildStbl(buildAvc1(cfg)))
    }

    private fun buildAudioMinf(cfg: AudioInit): ByteArray {
        val smhd = fullBox("smhd", 0, 0) { b -> b.u16(0); b.u16(0) }
        val sampleEntry = when (cfg.codec) {
            AudioCodec.AAC -> buildMp4a(cfg)
            AudioCodec.AC3 -> buildAudioSampleEntry("ac-3", cfg, box("dac3", cfg.codecData))
            AudioCodec.EAC3 -> buildAudioSampleEntry("ec-3", cfg, box("dec3", cfg.codecData))
        }
        return box("minf", smhd + buildDinf() + buildStbl(sampleEntry))
    }

    private fun buildDinf(): ByteArray {
        val url = fullBox("url ", 0, 0x000001) { }
        val dref = fullBox("dref", 0, 0) { b -> b.u32(1L); b.write(url) }
        return box("dinf", dref)
    }

    private fun buildStbl(sampleEntry: ByteArray): ByteArray {
        val stsd = fullBox("stsd", 0, 0) { b -> b.u32(1L); b.write(sampleEntry) }
        val stts = fullBox("stts", 0, 0) { it.u32(0L) }
        val stsc = fullBox("stsc", 0, 0) { it.u32(0L) }
        val stsz = fullBox("stsz", 0, 0) { b -> b.u32(0L); b.u32(0L) }
        val stco = fullBox("stco", 0, 0) { it.u32(0L) }
        return box("stbl", stsd + stts + stsc + stsz + stco)
    }

    private fun buildAvc1(cfg: VideoInit): ByteArray {
        val b = ByteArrayOutputStream()
        repeat(6) { b.u8(0) }; b.u16(1); b.u16(0); b.u16(0); repeat(3) { b.u32(0L) }
        b.u16(cfg.width); b.u16(cfg.height)
        b.u32(0x00480000L); b.u32(0x00480000L); b.u32(0L); b.u16(1)
        repeat(32) { b.u8(0) }; b.u16(0x0018); b.u16(0xFFFF)
        b.write(box("avcC", cfg.avcC))
        return box("avc1", b.toByteArray())
    }

    private fun buildMp4a(cfg: AudioInit): ByteArray =
        buildAudioSampleEntry("mp4a", cfg, buildEsds(cfg.codecData))

    /** Standard AudioSampleEntry header ([boxType] mp4a/ac-3/ec-3) + codec-specific box. */
    private fun buildAudioSampleEntry(boxType: String, cfg: AudioInit, codecBox: ByteArray): ByteArray {
        val b = ByteArrayOutputStream()
        repeat(6) { b.u8(0) }; b.u16(1); b.u32(0L); b.u32(0L)
        b.u16(cfg.channels); b.u16(16); b.u16(0); b.u16(0)
        b.u32(cfg.sampleRate.toLong() shl 16)
        b.write(codecBox)
        return box(boxType, b.toByteArray())
    }

    private fun buildEsds(asc: ByteArray): ByteArray {
        val dsi = descriptor(0x05, asc)
        val dcdBody = ByteArrayOutputStream()
        dcdBody.u8(0x40); dcdBody.u8(0x15); dcdBody.u8(0); dcdBody.u16(0)
        dcdBody.u32(0L); dcdBody.u32(0L); dcdBody.write(dsi)
        val dcd = descriptor(0x04, dcdBody.toByteArray())
        val sl = descriptor(0x06, byteArrayOf(0x02))
        val esBody = ByteArrayOutputStream()
        esBody.u16(0); esBody.u8(0); esBody.write(dcd); esBody.write(sl)
        val es = descriptor(0x03, esBody.toByteArray())
        return fullBox("esds", 0, 0) { it.write(es) }
    }

    private fun descriptor(tag: Int, body: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.u8(tag); o.u8(body.size and 0x7F); o.write(body)
        return o.toByteArray()
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Media segment (moof + mdat)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Builds one media segment. [videoSamples]/[audioSamples] are the encoded samples
     * for this segment in presentation order; [defaultVideoDurUs]/[defaultAudioDurUs]
     * are used for the final sample of each track (no successor to derive duration).
     */
    fun buildMediaSegment(
        sequenceNumber: Int,
        videoSamples: List<Sample>,
        audioSamples: List<Sample>,
        defaultVideoDurUs: Long,
        defaultAudioDurUs: Long
    ): ByteArray {
        val trafs = ArrayList<Traf>()
        if (videoSamples.isNotEmpty()) {
            trafs.add(buildTraf(VIDEO_TRACK_ID, videoSamples, defaultVideoDurUs, includeFlags = true))
        }
        if (audioSamples.isNotEmpty()) {
            trafs.add(buildTraf(AUDIO_TRACK_ID, audioSamples, defaultAudioDurUs, includeFlags = false))
        }

        val mfhd = fullBox("mfhd", 0, 0) { it.u32(sequenceNumber.toLong()) }
        val moofContent = ByteArrayOutputStream()
        moofContent.write(mfhd)
        for (t in trafs) moofContent.write(t.bytes)
        val moofLen = 8 + moofContent.size()
        val moof = box("moof", moofContent.toByteArray())

        // Patch each trun data_offset (relative to moof start).
        // trun data_offset sits 60 bytes into each traf (8 traf + 16 tfhd + 20 tfdt + 16 into trun).
        var dataCursor = moofLen + 8
        var trafOffsetInMoof = 8 + mfhd.size
        for (t in trafs) {
            patchU32(moof, trafOffsetInMoof + 60, dataCursor.toLong())
            dataCursor += t.dataSize
            trafOffsetInMoof += t.bytes.size
        }

        val out = ByteArrayOutputStream()
        out.write(moof)
        val totalData = trafs.sumOf { it.dataSize }
        out.u32((8 + totalData).toLong()); out.str4("mdat")
        for (t in trafs) for (s in t.samples) out.write(s.data)
        return out.toByteArray()
    }

    private class Traf(val bytes: ByteArray, val samples: List<EncSample>, val dataSize: Int)
    private class EncSample(val data: ByteArray, val durationUs: Long, val keyframe: Boolean)

    private fun buildTraf(
        trackId: Int,
        samples: List<Sample>,
        defaultDurUs: Long,
        includeFlags: Boolean
    ): Traf {
        // Convert PTS to per-sample durations; the last sample has no successor to measure
        // against, so reuse the previous real inter-sample delta (codec-agnostic) rather
        // than a fixed default — a hardcoded default is only correct for one frame rate /
        // audio sample layout (e.g. AAC's 21333us is wrong for AC-3/E-AC-3's 32000us, which
        // would otherwise add a ~10ms discontinuity at every segment boundary).
        val enc = ArrayList<EncSample>(samples.size)
        for (i in samples.indices) {
            val dur = when {
                i + 1 < samples.size -> (samples[i + 1].ptsUs - samples[i].ptsUs).coerceAtLeast(1)
                samples.size >= 2 -> (samples[i].ptsUs - samples[i - 1].ptsUs).coerceAtLeast(1)
                else -> defaultDurUs
            }
            enc.add(EncSample(samples[i].data, dur, samples[i].keyframe))
        }
        val baseDecodeTimeUs = samples.first().ptsUs.coerceAtLeast(0)
        var dataSize = 0
        for (s in enc) dataSize += s.data.size

        val tfhd = fullBox("tfhd", 0, 0x020000) { it.u32(trackId.toLong()) }
        val tfdt = fullBox("tfdt", 1, 0) { it.u64(baseDecodeTimeUs) }
        val trunFlags = if (includeFlags) 0x000701 else 0x000301
        val trun = fullBox("trun", 0, trunFlags) { b ->
            b.u32(enc.size.toLong())
            b.u32(0L) // data_offset placeholder
            for (s in enc) {
                b.u32(s.durationUs)
                b.u32(s.data.size.toLong())
                if (includeFlags) b.u32((if (s.keyframe) SAMPLE_FLAG_KEYFRAME else SAMPLE_FLAG_NON_KEY).toLong())
            }
        }
        val traf = box("traf", tfhd + tfdt + trun)
        return Traf(traf, enc, dataSize)
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  AVCC conversion + primitives
    // ──────────────────────────────────────────────────────────────────────────

    /** Converts an Annex-B access unit to length-prefixed (AVCC); passes through if already AVCC. */
    fun ensureAvcc(data: ByteArray): ByteArray {
        if (startCodeLen(data, 0) == 0) return data
        val out = ByteArrayOutputStream(data.size + 16)
        var i = 0
        while (i < data.size) {
            val sc = startCodeLen(data, i)
            if (sc == 0) { i++; continue }
            val start = i + sc
            var end = data.size
            var j = start
            while (j < data.size) { if (startCodeLen(data, j) > 0) { end = j; break }; j++ }
            val len = end - start
            if (len > 0) { out.u32(len.toLong()); out.write(data, start, len) }
            i = end
        }
        return out.toByteArray()
    }

    private fun startCodeLen(d: ByteArray, o: Int): Int {
        if (o + 4 <= d.size && d[o] == 0.toByte() && d[o + 1] == 0.toByte() && d[o + 2] == 0.toByte() && d[o + 3] == 1.toByte()) return 4
        if (o + 3 <= d.size && d[o] == 0.toByte() && d[o + 1] == 0.toByte() && d[o + 2] == 1.toByte()) return 3
        return 0
    }

    private fun writeIdentityMatrix(b: ByteArrayOutputStream) {
        b.u32(0x00010000L); b.u32(0L); b.u32(0L)
        b.u32(0L); b.u32(0x00010000L); b.u32(0L)
        b.u32(0L); b.u32(0L); b.u32(0x40000000L)
    }

    private fun box(type: String, body: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(body.size + 8)
        o.u32((body.size + 8).toLong()); o.str4(type); o.write(body)
        return o.toByteArray()
    }

    private fun fullBox(type: String, version: Int, flags: Int, write: (ByteArrayOutputStream) -> Unit): ByteArray {
        val body = ByteArrayOutputStream()
        body.u8(version); body.u8((flags shr 16) and 0xFF); body.u8((flags shr 8) and 0xFF); body.u8(flags and 0xFF)
        write(body)
        return box(type, body.toByteArray())
    }

    private fun patchU32(arr: ByteArray, pos: Int, value: Long) {
        arr[pos] = ((value shr 24) and 0xFF).toByte()
        arr[pos + 1] = ((value shr 16) and 0xFF).toByte()
        arr[pos + 2] = ((value shr 8) and 0xFF).toByte()
        arr[pos + 3] = (value and 0xFF).toByte()
    }

    private fun ByteArrayOutputStream.u8(v: Int) { write(v and 0xFF) }
    private fun ByteArrayOutputStream.u16(v: Int) { write((v shr 8) and 0xFF); write(v and 0xFF) }
    private fun ByteArrayOutputStream.u32(v: Long) {
        write(((v shr 24) and 0xFF).toInt()); write(((v shr 16) and 0xFF).toInt())
        write(((v shr 8) and 0xFF).toInt()); write((v and 0xFF).toInt())
    }
    private fun ByteArrayOutputStream.u64(v: Long) { for (i in 7 downTo 0) write(((v shr (i * 8)) and 0xFF).toInt()) }
    private fun ByteArrayOutputStream.str4(s: String) { write(s.toByteArray(Charsets.US_ASCII)) }
}
