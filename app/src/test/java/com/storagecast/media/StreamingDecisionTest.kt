package com.storagecast.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingDecisionTest {

    private fun video(mime: String, profile: String = "unknown") =
        VideoTrackInfo(0, mime.substringAfter('/'), mime, 1920, 1080, 24f, 5_000_000, profile, "unknown")

    private fun audio(mime: String, ch: Int = 2) =
        AudioTrackInfo(1, mime.substringAfter('/'), mime, 48000, ch, 192_000, "und")

    private fun probe(v: VideoTrackInfo?, a: AudioTrackInfo?) =
        MediaProbeResult("mkv", listOfNotNull(v), listOfNotNull(a), 1_200_000L, 1_000_000L)

    private fun decide(v: VideoTrackInfo?, a: AudioTrackInfo?, force: Boolean = false) =
        StreamingDecision.decide(probe(v, a), forceTranscode = force)

    // ── Direct play ───────────────────────────────────────────────────────────

    @Test
    fun h264Aac_directPlay() {
        val p = decide(video("video/avc"), audio("audio/mp4a-latm"))
        assertEquals(StreamingDecision.Path.DIRECT, p.path)
        assertFalse(p.transcodeVideo)
    }

    @Test
    fun av1Opus_directPlay() {
        assertEquals(StreamingDecision.Path.DIRECT, decide(video("video/av01"), audio("audio/opus")).path)
    }

    @Test
    fun h264Ac3_directPlay_dolbyIsReceiverSupported() {
        // Dolby is fine for direct play (only HLS rejects it), so don't transcode it.
        assertEquals(StreamingDecision.Path.DIRECT, decide(video("video/avc"), audio("audio/ac3", ch = 6)).path)
    }

    // ── HLS (transcode video, copy or transcode audio) ────────────────────────

    @Test
    fun tenBitHevcAac_hlsCopyAudio() {
        // 10-bit HEVC needs video transcode; AAC is HLS-friendly so copy it.
        val p = decide(video("video/hevc", "Main 10"), audio("audio/mp4a-latm"))
        assertEquals(StreamingDecision.Path.HLS, p.path)
        assertTrue(p.transcodeVideo)
        assertTrue("AAC passed through", p.copyAudio)
    }

    @Test
    fun hevc8bitAac_hlsCopyAudio() {
        // 8-bit HEVC isn't in the direct set, so transcode video; copy AAC over HLS.
        val p = decide(video("video/hevc", "Main"), audio("audio/mp4a-latm"))
        assertEquals(StreamingDecision.Path.HLS, p.path)
        assertTrue(p.copyAudio)
    }

    @Test
    fun h264Dts_hlsTranscodeAudio() {
        // Non-Dolby unsupported audio (DTS) -> HLS with audio transcoded to AAC.
        val p = decide(video("video/avc"), audio("audio/vnd.dts"))
        assertEquals(StreamingDecision.Path.HLS, p.path)
        assertFalse("DTS not passed through; transcoded to AAC", p.copyAudio)
    }

    @Test
    fun tenBitHevcDts_hlsTranscodeBoth() {
        val p = decide(video("video/hevc", "Main 10"), audio("audio/vnd.dts"))
        assertEquals(StreamingDecision.Path.HLS, p.path)
        assertTrue(p.transcodeVideo)
        assertFalse(p.copyAudio)
    }

    // ── Live (Dolby + incompatible video) ─────────────────────────────────────

    @Test
    fun tenBitHevcEac3_liveKeeps51() {
        // The primary test file: 10-bit HEVC + E-AC-3 5.1.
        val p = decide(video("video/hevc", "Main 10"), audio("audio/eac3", ch = 6))
        assertEquals(StreamingDecision.Path.LIVE, p.path)
        assertTrue("audio passed through to keep 5.1", p.copyAudio)
        assertTrue(p.transcodeVideo)
    }

    @Test
    fun tenBitHevcAc3_liveKeeps51() {
        assertEquals(StreamingDecision.Path.LIVE, decide(video("video/hevc", "Main 10"), audio("audio/ac3", ch = 6)).path)
    }

    @Test
    fun avcHigh10Eac3_liveKeeps51() {
        // 10-bit H.264 (High 10) also needs video transcode -> Dolby stays on live.
        assertEquals(StreamingDecision.Path.LIVE, decide(video("video/avc", "High 10"), audio("audio/eac3", ch = 6)).path)
    }

    // ── Force-transcode override ──────────────────────────────────────────────

    @Test
    fun forceTranscode_compatibleFileGoesHls() {
        val p = decide(video("video/avc"), audio("audio/mp4a-latm"), force = true)
        assertEquals(StreamingDecision.Path.HLS, p.path)
        assertTrue("AAC still copied over HLS", p.copyAudio)
    }

    @Test
    fun forceTranscode_doesNotBreakDolbyLiveCase() {
        // Forcing transcode on the Dolby+10bit file still keeps it on live for 5.1.
        assertEquals(
            StreamingDecision.Path.LIVE,
            decide(video("video/hevc", "Main 10"), audio("audio/eac3", ch = 6), force = true).path
        )
    }

    @Test
    fun preferHls_overrideRoutesDolbyToHlsAac() {
        // The preferHls override trades 5.1 for native seeking: Dolby+10bit -> HLS, audio->AAC.
        val p = StreamingDecision.decide(
            probe(video("video/hevc", "Main 10"), audio("audio/eac3", ch = 6)),
            preferHls = true
        )
        assertEquals(StreamingDecision.Path.HLS, p.path)
        assertFalse("Dolby transcoded to AAC for HLS", p.copyAudio)
    }

    // ── Reactive receiver capability hints ────────────────────────────────────

    @Test
    fun learnedUnsupportedVideo_forcesTranscode() {
        // A receiver that previously failed to direct-play AV1 should now transcode it,
        // even though AV1 is in the optimistic baseline.
        val hints = StreamingDecision.ReceiverHints(unsupportedDirect = setOf("video/av01"))
        val p = StreamingDecision.decide(
            probe(video("video/av01"), audio("audio/mp4a-latm")), hints = hints
        )
        assertEquals(StreamingDecision.Path.HLS, p.path)
        assertTrue(p.transcodeVideo)
        assertTrue("AAC still copied over HLS", p.copyAudio)
    }

    @Test
    fun learnedUnsupportedDolbyAudio_routesToHls() {
        // If a receiver can't direct-play E-AC-3, the audio is no longer "supported"; with a
        // supported video that means HLS with the audio transcoded to AAC.
        val hints = StreamingDecision.ReceiverHints(unsupportedDirect = setOf("audio/eac3"))
        val p = StreamingDecision.decide(
            probe(video("video/avc"), audio("audio/eac3", ch = 6)), hints = hints
        )
        assertEquals(StreamingDecision.Path.HLS, p.path)
        assertFalse("unsupported Dolby transcoded to AAC", p.copyAudio)
    }

    @Test
    fun emptyHints_unchangedFromDefault() {
        val p = StreamingDecision.decide(
            probe(video("video/avc"), audio("audio/mp4a-latm")),
            hints = StreamingDecision.ReceiverHints()
        )
        assertEquals(StreamingDecision.Path.DIRECT, p.path)
    }

    // ── Audio not demuxable by the platform (e.g. E-AC-3 in MKV on Xiaomi) ─────

    private fun undemuxable(v: VideoTrackInfo?, a: AudioTrackInfo?) =
        MediaProbeResult("mkv", listOfNotNull(v), listOfNotNull(a), 1_200_000L, 1_000_000L, audioPlatformDemuxable = false)

    @Test
    fun undemuxableAudio_forcesDirectEvenForHevc() {
        // 10-bit HEVC + E-AC-3 would normally be LIVE, but if the device can't demux the
        // audio the transcoder can't carry it, so direct play is the only option.
        val p = StreamingDecision.decide(undemuxable(video("video/hevc", "Main 10"), audio("audio/eac3", ch = 6)))
        assertEquals(StreamingDecision.Path.DIRECT, p.path)
        assertFalse(p.transcodeVideo)
    }

    @Test
    fun undemuxableAudio_beatsForceTranscodeAndPreferHls() {
        val p = StreamingDecision.decide(
            undemuxable(video("video/hevc", "Main 10"), audio("audio/eac3", ch = 6)),
            forceTranscode = true, preferHls = true
        )
        assertEquals(StreamingDecision.Path.DIRECT, p.path)
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun audioOnly_aac_directPlay() {
        assertEquals(StreamingDecision.Path.DIRECT, decide(null, audio("audio/mp4a-latm")).path)
    }

    @Test
    fun videoOnly_h264_directPlay() {
        assertEquals(StreamingDecision.Path.DIRECT, decide(video("video/avc"), null).path)
    }
}
