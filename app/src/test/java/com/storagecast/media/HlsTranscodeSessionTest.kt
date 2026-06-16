package com.storagecast.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the HLS VOD playlist/manifest generation in [HlsTranscodeSession]
 * (the segment plan, EXTINF durations, master-playlist CODECS, and the WebVTT
 * X-TIMESTAMP-MAP). Only playlist methods are exercised — never segmentBytes/initBytes,
 * which would invoke MediaCodec.
 */
class HlsTranscodeSessionTest {

    private fun probe(durationMs: Long, audioMime: String = "audio/mp4a-latm"): MediaProbeResult {
        val video = VideoTrackInfo(0, "H.264", "video/avc", 1920, 1080, 24f, 5_000_000, "high", "4.1")
        val audio = AudioTrackInfo(1, "AAC", audioMime, 48000, 2, 192_000, "en")
        return MediaProbeResult("MP4", listOf(video), listOf(audio), durationMs, 1_000_000L)
    }

    private fun session(
        durationMs: Long,
        audioMime: String = "audio/mp4a-latm",
        copyAudio: Boolean = false,
        subtitle: ByteArray? = null
    ) = HlsTranscodeSession("/x.mkv", probe(durationMs, audioMime), null, copyAudio, subtitle)

    private fun extinfDurations(playlist: String): List<Double> =
        playlist.lineSequence()
            .filter { it.startsWith("#EXTINF:") }
            .map { it.removePrefix("#EXTINF:").substringBefore(',').toDouble() }
            .toList()

    @Test
    fun playlist_isVodWithMapAndEndlist() {
        val p = session(20_000).playlist("/hls/x")
        assertTrue("starts with EXTM3U", p.startsWith("#EXTM3U"))
        assertTrue("VOD type", p.contains("#EXT-X-PLAYLIST-TYPE:VOD"))
        assertTrue("has init map", p.contains("#EXT-X-MAP:URI=\"/hls/x/init.mp4\""))
        assertTrue("ends with ENDLIST", p.trimEnd().endsWith("#EXT-X-ENDLIST"))
    }

    @Test
    fun playlist_segmentCountAndDurationsSumToTotal() {
        // 20 s / 6 s → 4 segments: 6 + 6 + 6 + 2.
        val p = session(20_000).playlist("/hls/x")
        val durs = extinfDurations(p)
        assertEquals("segment count", 4, durs.size)
        assertEquals("durations sum to total", 20.0, durs.sum(), 0.001)
        assertEquals("last is remainder", 2.0, durs.last(), 0.001)
        // Every segment is referenced once, in order.
        for (i in 0 until 4) assertTrue("seg$i present", p.contains("/hls/x/seg$i.m4s"))
    }

    @Test
    fun playlist_exactMultipleHasNoZeroRemainder() {
        // 12 s / 6 s → exactly 2 segments of 6 s.
        val durs = extinfDurations(session(12_000).playlist("/hls/x"))
        assertEquals(2, durs.size)
        assertEquals(listOf(6.0, 6.0), durs)
    }

    @Test
    fun masterPlaylist_advertisesAacByDefault() {
        val m = session(12_000).masterPlaylist("/hls/x")
        assertTrue(m.contains("#EXT-X-STREAM-INF"))
        assertTrue("aac codec", m.contains("mp4a.40.2"))
        assertFalse("no subtitle group", m.contains("SUBTITLES"))
        assertTrue("points to media playlist", m.contains("/hls/x/playlist.m3u8"))
    }

    @Test
    fun masterPlaylist_advertisesEac3WhenCopyAudio() {
        val m = session(12_000, audioMime = "audio/eac3", copyAudio = true).masterPlaylist("/hls/x")
        assertTrue("ec-3 codec", m.contains("CODECS=\"avc1.640029,ec-3\""))
    }

    @Test
    fun masterPlaylist_advertisesAc3WhenCopyAudio() {
        val m = session(12_000, audioMime = "audio/ac3", copyAudio = true).masterPlaylist("/hls/x")
        assertTrue("ac-3 codec", m.contains("CODECS=\"avc1.640029,ac-3\""))
    }

    @Test
    fun masterPlaylist_eac3TranscodedToAacWhenCopyAudioOff() {
        // copyAudio off → we transcode to AAC regardless of source, so advertise AAC.
        val m = session(12_000, audioMime = "audio/eac3", copyAudio = false).masterPlaylist("/hls/x")
        assertTrue("aac codec", m.contains("mp4a.40.2"))
        assertFalse("not ec-3", m.contains("ec-3"))
    }

    @Test
    fun masterPlaylist_withSubtitlesHasRenditionAndReference() {
        val s = session(12_000, subtitle = "WEBVTT\n\n00:00.000 --> 00:01.000\nHi\n".toByteArray())
        assertTrue(s.hasSubtitles)
        val m = s.masterPlaylist("/hls/x")
        assertTrue("subtitle media", m.contains("#EXT-X-MEDIA:TYPE=SUBTITLES"))
        assertTrue("subs playlist uri", m.contains("/hls/x/subs.m3u8"))
        assertTrue("stream references subs group", m.contains("SUBTITLES=\"subs\""))
    }

    @Test
    fun subtitleVtt_prependsTimestampMapAndKeepsBody() {
        val src = "WEBVTT - some header\n\n00:00:01.000 --> 00:00:02.000\nHello\n"
        val out = String(session(12_000, subtitle = src.toByteArray()).subtitleVttBytes()!!)
        assertTrue("starts with WEBVTT", out.startsWith("WEBVTT"))
        assertTrue("has identity timestamp map", out.contains("X-TIMESTAMP-MAP=MPEGTS:0,LOCAL:00:00:00.000"))
        assertTrue("keeps the cue", out.contains("00:00:01.000 --> 00:00:02.000"))
        assertTrue("keeps cue text", out.contains("Hello"))
        // The original "WEBVTT - some header" first line is preserved (map injected after it).
        assertTrue("keeps original signature line", out.contains("WEBVTT - some header"))
        // The map line sits on line 2, inside the header block (before the first blank line).
        val lines = out.split("\n")
        assertEquals("map is line 2", "X-TIMESTAMP-MAP=MPEGTS:0,LOCAL:00:00:00.000", lines[1])
        assertEquals("exactly one WEBVTT token", 1, Regex("WEBVTT").findAll(out).count())
    }

    @Test
    fun subtitleVtt_keepsStyleBlockInHeader() {
        // A STYLE block must remain after the header lines and before the first cue,
        // with no stray blank line splitting it from the header.
        val src = "WEBVTT\n\nSTYLE\n::cue { color: yellow }\n\n00:00:01.000 --> 00:00:02.000\nHi\n"
        val out = String(session(12_000, subtitle = src.toByteArray()).subtitleVttBytes()!!)
        val lines = out.split("\n")
        assertEquals("WEBVTT first", "WEBVTT", lines[0])
        assertEquals("map second", "X-TIMESTAMP-MAP=MPEGTS:0,LOCAL:00:00:00.000", lines[1])
        assertEquals("blank line ends header", "", lines[2])
        assertEquals("STYLE block preserved", "STYLE", lines[3])
        assertTrue("cue preserved", out.contains("00:00:01.000 --> 00:00:02.000"))
    }

    @Test
    fun subtitleVtt_doesNotDuplicateExistingTimestampMap() {
        val src = "WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000\n\n00:00:01.000 --> 00:00:02.000\nHi\n"
        val out = String(session(12_000, subtitle = src.toByteArray()).subtitleVttBytes()!!)
        assertEquals("one timestamp map only", 1, Regex("X-TIMESTAMP-MAP").findAll(out).count())
        assertTrue("preserves the existing map value", out.contains("MPEGTS:900000"))
    }

    @Test
    fun subtitleVtt_nullWhenNoSubtitle() {
        assertNull(session(12_000).subtitleVttBytes())
        assertFalse(session(12_000).hasSubtitles)
    }

    @Test
    fun subtitlePlaylist_isSingleSegmentVod() {
        val s = session(20_000, subtitle = "WEBVTT\n".toByteArray())
        val p = s.subtitlePlaylist("/hls/x")
        assertTrue("VOD", p.contains("#EXT-X-PLAYLIST-TYPE:VOD"))
        assertEquals("single subtitle segment", 1, extinfDurations(p).size)
        assertEquals("covers full duration", 20.0, extinfDurations(p).first(), 0.001)
        assertTrue("points to subs.vtt", p.contains("/hls/x/subs.vtt"))
        assertTrue("ends with ENDLIST", p.trimEnd().endsWith("#EXT-X-ENDLIST"))
    }
}
