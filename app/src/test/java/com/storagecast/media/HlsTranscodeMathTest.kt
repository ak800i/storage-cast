package com.storagecast.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsTranscodeMathTest {

    // ── outputSize ────────────────────────────────────────────────────────────

    @Test
    fun outputSize_downscales1080pSourceUnchanged() {
        assertEquals(1920 to 1080, HlsTranscodeMath.outputSize(1920, 1080))
    }

    @Test
    fun outputSize_neverUpscalesSmallSource() {
        assertEquals(1280 to 720, HlsTranscodeMath.outputSize(1280, 720))
    }

    @Test
    fun outputSize_downscales4kToFitWithEvenDimensions() {
        val (w, h) = HlsTranscodeMath.outputSize(3840, 2160)
        assertEquals(1920, w)
        assertEquals(1080, h)
    }

    @Test
    fun outputSize_widerThanWideClampsOnWidthKeepsAspect() {
        // 2560x1080 (21:9): width is the binding constraint.
        val (w, h) = HlsTranscodeMath.outputSize(2560, 1080)
        assertEquals(1920, w)
        // 1080 * (1920/2560) = 810, even.
        assertEquals(810, h)
        assertTrue("width within cap", w <= 1920)
        assertTrue("height within cap", h <= 1080)
    }

    @Test
    fun outputSize_resultsAreAlwaysEven() {
        for (pair in listOf(1919 to 1079, 1421 to 799, 1281 to 721)) {
            val (w, h) = HlsTranscodeMath.outputSize(pair.first, pair.second)
            assertEquals("width even for $pair", 0, w % 2)
            assertEquals("height even for $pair", 0, h % 2)
            assertTrue("no upscale w for $pair", w <= pair.first)
            assertTrue("no upscale h for $pair", h <= pair.second)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun outputSize_rejectsZeroDimensions() {
        HlsTranscodeMath.outputSize(0, 1080)
    }

    // ── clampBitrate ──────────────────────────────────────────────────────────

    @Test
    fun clampBitrate_usesSourceWhenBelowCap() {
        assertEquals(3_000_000, HlsTranscodeMath.clampBitrate(3_000_000))
    }

    @Test
    fun clampBitrate_capsHighSource() {
        assertEquals(8_000_000, HlsTranscodeMath.clampBitrate(40_000_000))
    }

    @Test
    fun clampBitrate_fallsBackForUnknownSource() {
        assertEquals(8_000_000, HlsTranscodeMath.clampBitrate(0))
        assertEquals(8_000_000, HlsTranscodeMath.clampBitrate(-1))
    }

    // ── clampFrameRate ────────────────────────────────────────────────────────

    @Test
    fun clampFrameRate_passesLowRate() {
        // Truncates toward zero, matching the encoder format integer frame-rate.
        assertEquals(23, HlsTranscodeMath.clampFrameRate(23.976))
        assertEquals(25, HlsTranscodeMath.clampFrameRate(25.0))
    }

    @Test
    fun clampFrameRate_capsHighRate() {
        assertEquals(30, HlsTranscodeMath.clampFrameRate(59.94))
    }

    @Test
    fun clampFrameRate_fallsBackForUnknownRate() {
        assertEquals(30, HlsTranscodeMath.clampFrameRate(0.0))
        assertEquals(30, HlsTranscodeMath.clampFrameRate(-1.0))
    }

    // ── video frame inclusion ─────────────────────────────────────────────────

    @Test
    fun shouldRender_includesFrameInsideRange() {
        assertTrue(HlsTranscodeMath.shouldRenderVideoFrame(size = 100, isCodecConfig = false, ptsUs = 6_500_000, startUs = 6_000_000, endUs = 12_000_000))
    }

    @Test
    fun shouldRender_dropsPreRollBeforeStart() {
        assertFalse(HlsTranscodeMath.shouldRenderVideoFrame(100, false, 5_999_999, 6_000_000, 12_000_000))
    }

    @Test
    fun shouldRender_isHalfOpenAtEnd() {
        // End is exclusive: a frame exactly at endUs belongs to the next segment.
        assertFalse(HlsTranscodeMath.shouldRenderVideoFrame(100, false, 12_000_000, 6_000_000, 12_000_000))
        // Start is inclusive.
        assertTrue(HlsTranscodeMath.shouldRenderVideoFrame(100, false, 6_000_000, 6_000_000, 12_000_000))
    }

    @Test
    fun shouldRender_dropsEmptyAndConfigBuffers() {
        assertFalse(HlsTranscodeMath.shouldRenderVideoFrame(0, false, 7_000_000, 6_000_000, 12_000_000))
        assertFalse(HlsTranscodeMath.shouldRenderVideoFrame(100, true, 7_000_000, 6_000_000, 12_000_000))
    }

    @Test
    fun videoSegmentComplete_onEosOrPastEnd() {
        assertTrue("eos", HlsTranscodeMath.isVideoSegmentComplete(endOfStream = true, ptsUs = 0, endUs = 12_000_000))
        assertTrue("past end", HlsTranscodeMath.isVideoSegmentComplete(false, 12_000_000, 12_000_000))
        assertFalse("still inside", HlsTranscodeMath.isVideoSegmentComplete(false, 11_999_999, 12_000_000))
    }

    // ── boundary tiling: consecutive segments must partition frames exactly once ─

    @Test
    fun consecutiveSegments_partitionFramesExactlyOnce() {
        // Frames every 1s; segment A = [6s,12s), segment B = [12s,18s).
        val segAStart = 6_000_000L; val segAEnd = 12_000_000L
        val segBStart = 12_000_000L; val segBEnd = 18_000_000L
        for (i in 0..23) {
            val pts = i * 1_000_000L
            val inA = HlsTranscodeMath.shouldRenderVideoFrame(100, false, pts, segAStart, segAEnd)
            val inB = HlsTranscodeMath.shouldRenderVideoFrame(100, false, pts, segBStart, segBEnd)
            // No frame may appear in both adjacent segments.
            assertFalse("frame at ${pts}us double-counted", inA && inB)
        }
        // The shared boundary (12s) belongs to B, not A.
        assertFalse(HlsTranscodeMath.shouldRenderVideoFrame(100, false, 12_000_000, segAStart, segAEnd))
        assertTrue(HlsTranscodeMath.shouldRenderVideoFrame(100, false, 12_000_000, segBStart, segBEnd))
    }

    // ── audio range ───────────────────────────────────────────────────────────

    @Test
    fun audio_rangeEndIsExclusive() {
        assertTrue(HlsTranscodeMath.audioRangeEnded(12_000_000, 12_000_000))
        assertFalse(HlsTranscodeMath.audioRangeEnded(11_999_999, 12_000_000))
    }

    @Test
    fun audio_startIsInclusive() {
        assertTrue(HlsTranscodeMath.audioFrameIncluded(6_000_000, 6_000_000))
        assertFalse(HlsTranscodeMath.audioFrameIncluded(5_999_999, 6_000_000))
    }

    // ── avcC equivalence (shared-init invariant) ──────────────────────────────

    @Test
    fun avcConfigsMatch_trueForIdenticalRecords() {
        val a = byteArrayOf(1, 0x64, 0, 0x29, 7, 8, 9)
        val b = byteArrayOf(1, 0x64, 0, 0x29, 7, 8, 9)
        assertTrue(HlsTranscodeMath.avcConfigsMatch(a, b))
    }

    @Test
    fun avcConfigsMatch_falseWhenContentDiffers() {
        val a = byteArrayOf(1, 0x64, 0, 0x29, 7, 8, 9)
        val b = byteArrayOf(1, 0x4D, 0, 0x28, 7, 8, 9) // different profile/level
        assertFalse(HlsTranscodeMath.avcConfigsMatch(a, b))
    }

    @Test
    fun avcConfigsMatch_falseWhenLengthDiffers() {
        val a = byteArrayOf(1, 0x64, 0, 0x29)
        val b = byteArrayOf(1, 0x64, 0, 0x29, 0)
        assertFalse(HlsTranscodeMath.avcConfigsMatch(a, b))
    }

    @Test
    fun avcConfigsMatch_nullHandling() {
        assertTrue("both null match", HlsTranscodeMath.avcConfigsMatch(null, null))
        assertFalse("one null differs", HlsTranscodeMath.avcConfigsMatch(byteArrayOf(1), null))
        assertFalse("other null differs", HlsTranscodeMath.avcConfigsMatch(null, byteArrayOf(1)))
    }

    @Test fun segmentIndexForPts_floorsToSegment() {
        assertEquals(0, HlsTranscodeMath.segmentIndexForPts(0L, 6_000_000L))
        assertEquals(0, HlsTranscodeMath.segmentIndexForPts(5_999_999L, 6_000_000L))
        assertEquals(1, HlsTranscodeMath.segmentIndexForPts(6_000_000L, 6_000_000L))
        assertEquals(2, HlsTranscodeMath.segmentIndexForPts(12_500_000L, 6_000_000L))
    }

    @Test fun crossesBoundary_trueOnlyWhenPrevBelowAndCurrentAtOrAbove() {
        // boundary at 6s; frame at 5.96s -> 6.00s crosses it
        assertTrue(HlsTranscodeMath.crossesBoundary(5_960_000L, 6_000_000L, 6_000_000L))
        // both below
        assertFalse(HlsTranscodeMath.crossesBoundary(5_900_000L, 5_960_000L, 6_000_000L))
        // both at/above (already crossed earlier)
        assertFalse(HlsTranscodeMath.crossesBoundary(6_000_000L, 6_040_000L, 6_000_000L))
    }

    @Test fun segmentDrained_requiresBothTracksPastEnd() {
        assertTrue(HlsTranscodeMath.segmentDrained(6_000_000L, 6_010_000L, 6_000_000L))
        assertFalse(HlsTranscodeMath.segmentDrained(6_000_000L, 5_990_000L, 6_000_000L)) // audio short
        assertFalse(HlsTranscodeMath.segmentDrained(5_990_000L, 6_010_000L, 6_000_000L)) // video short
    }

    @Test fun effectiveCopyAudio_trueOnlyForAacMonoStereoWhenPlanAllows() {
        assertTrue(HlsTranscodeMath.effectiveCopyAudio(true, "audio/mp4a-latm", 2))
        assertTrue(HlsTranscodeMath.effectiveCopyAudio(true, "audio/mp4a-latm", 1))
    }

    @Test fun effectiveCopyAudio_falseForMultichannelAac() {
        assertFalse(HlsTranscodeMath.effectiveCopyAudio(true, "audio/mp4a-latm", 6))
    }

    @Test fun effectiveCopyAudio_falseForMp3_evenWhenPlanAllows() {
        // plan.copyAudio may be true for audio/mpeg, but HlsMp4Builder can't mux MP3 -> transcode
        assertFalse(HlsTranscodeMath.effectiveCopyAudio(true, "audio/mpeg", 2))
    }

    @Test fun effectiveCopyAudio_falseWhenPlanDisallows() {
        assertFalse(HlsTranscodeMath.effectiveCopyAudio(false, "audio/mp4a-latm", 2))
    }

    @Test fun hlsAudioCodecAttr_isAlwaysAacOnHls() {
        // HLS audio is always AAC (copy AAC, or transcode everything else to AAC)
        assertEquals("mp4a.40.2", HlsTranscodeMath.hlsAudioCodecAttr())
    }
}
