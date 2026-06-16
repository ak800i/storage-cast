package com.storagecast.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests for the compatibility gate that decides whether a file is
 * cast directly or must be transcoded. This logic is user-facing (it drives the
 * transcode decision) and was previously untested; these tests pin its current
 * behavior so any future change to the supported-codec matrix is intentional.
 *
 * Requires `testOptions.unitTests.isReturnDefaultValues = true` because
 * `checkCompatibility` logs via AppLogger (android.util.Log) at the end.
 */
class CastCompatibilityTest {

    private fun video(mime: String, w: Int = 1920, h: Int = 1080, profile: String = "unknown") =
        VideoTrackInfo(0, mime.substringAfter('/'), mime, w, h, 24f, 5_000_000, profile, "unknown")

    private fun audio(mime: String, ch: Int = 2) =
        AudioTrackInfo(1, mime.substringAfter('/'), mime, 48000, ch, 192_000, "und")

    private fun probe(container: String, v: List<VideoTrackInfo>, a: List<AudioTrackInfo>) =
        MediaProbeResult(container, v, a, 1_200_000L, 1_000_000L)

    private val cc = CastCompatibility()

    @Test
    fun h264AacMp4_isFullyCompatible() {
        val r = cc.checkCompatibility(probe("mp4", listOf(video("video/avc")), listOf(audio("audio/mp4a-latm"))))
        assertTrue(r.isFullyCompatible)
        assertTrue(r.unsupportedVideoCodecs.isEmpty())
        assertTrue(r.unsupportedAudioCodecs.isEmpty())
        assertTrue(r.isContainerSupported)
    }

    @Test
    fun unsupportedVideoCodec_isFlagged() {
        val r = cc.checkCompatibility(probe("mp4", listOf(video("video/mp2v")), listOf(audio("audio/mp4a-latm"))))
        assertFalse(r.isFullyCompatible)
        assertEquals(1, r.unsupportedVideoCodecs.size)
        assertEquals("video/mp2v", r.unsupportedVideoCodecs[0].mime)
    }

    @Test
    fun unsupportedAudioCodec_isFlagged() {
        val r = cc.checkCompatibility(probe("mp4", listOf(video("video/avc")), listOf(audio("audio/vnd.dts"))))
        assertFalse(r.isFullyCompatible)
        assertEquals(1, r.unsupportedAudioCodecs.size)
        assertEquals("audio/vnd.dts", r.unsupportedAudioCodecs[0].mime)
    }

    @Test
    fun unknownContainer_isReportedUnsupportedButDoesNotAffectCodecCompatibility() {
        // isFullyCompatible depends only on codecs; container support is a separate flag.
        val r = cc.checkCompatibility(probe("avi", listOf(video("video/avc")), listOf(audio("audio/mp4a-latm"))))
        assertTrue("codecs are fine", r.isFullyCompatible)
        assertFalse("avi container not in supported set", r.isContainerSupported)
    }

    @Test
    fun supportedContainers_areDetected() {
        for (container in listOf("mp4", "mkv", "webm", "ts", "m2ts", "3gp")) {
            val r = cc.checkCompatibility(probe(container, listOf(video("video/avc")), listOf(audio("audio/mp4a-latm"))))
            assertTrue("$container should be supported", r.isContainerSupported)
        }
    }

    @Test
    fun multipleUnsupportedTracks_allListed() {
        val r = cc.checkCompatibility(
            probe(
                "mkv",
                listOf(video("video/avc"), video("video/mp2v")),
                listOf(audio("audio/mp4a-latm"), audio("audio/vnd.dts"))
            )
        )
        assertFalse(r.isFullyCompatible)
        assertEquals(1, r.unsupportedVideoCodecs.size)
        assertEquals(1, r.unsupportedAudioCodecs.size)
    }

    @Test
    fun dolbyAudioCodecs_areSupported() {
        // AC-3 and E-AC-3 are passthrough-capable on Cast receivers and must not be flagged.
        for (mime in listOf("audio/ac3", "audio/eac3")) {
            val r = cc.checkCompatibility(probe("mkv", listOf(video("video/avc")), listOf(audio(mime, ch = 6))))
            assertTrue("$mime should be supported", r.unsupportedAudioCodecs.isEmpty())
        }
    }

    @Test
    fun hevc8bit_passesTheCodecGate() {
        // 8-bit HEVC (Main) is left to the receiver — only 10-bit is force-transcoded.
        val r = cc.checkCompatibility(probe("mkv", listOf(video("video/hevc", profile = "Main")), listOf(audio("audio/eac3", ch = 6))))
        assertTrue("8-bit hevc passes the codec gate", r.unsupportedVideoCodecs.isEmpty())
        assertTrue(r.isFullyCompatible)
    }

    @Test
    fun hevcMain10_isFlaggedForTranscode() {
        // 10-bit HEVC (Main 10) fails on most Cast targets, so it must route to transcoding.
        val r = cc.checkCompatibility(probe("mkv", listOf(video("video/hevc", profile = "Main 10")), listOf(audio("audio/eac3", ch = 6))))
        assertEquals(1, r.unsupportedVideoCodecs.size)
        assertFalse("Main 10 must not be reported fully compatible", r.isFullyCompatible)
    }

    @Test
    fun avcHigh10_isFlaggedForTranscode() {
        val r = cc.checkCompatibility(probe("mp4", listOf(video("video/avc", profile = "High 10")), listOf(audio("audio/mp4a-latm"))))
        assertEquals(1, r.unsupportedVideoCodecs.size)
        assertFalse(r.isFullyCompatible)
    }
}
