package com.storagecast.media

import org.junit.Assert.assertEquals
import org.junit.Test

class CommittedEncoderConfigTest {
    @Test fun derive_capsResolutionToQuality_noUpscale_evenDims() {
        // 1080p source, AUTO cap (1920x1080) -> unchanged
        val c = CommittedEncoderConfig.derive(1920, 1080, 12_000_000, 24, CastQuality.AUTO)
        assertEquals(1920, c.width); assertEquals(1080, c.height)
    }

    @Test fun derive_p720_stepsDown1080Source() {
        val c = CommittedEncoderConfig.derive(1920, 1080, 12_000_000, 24, CastQuality.P720)
        assertEquals(1280, c.width); assertEquals(720, c.height)
    }

    @Test fun derive_clampsBitrateAndFps_andSetsSixSecondIdr() {
        val c = CommittedEncoderConfig.derive(1920, 1080, 50_000_000, 60, CastQuality.AUTO)
        assertEquals(8_000_000, c.bitrate)      // clamped to 8 Mb/s
        assertEquals(30, c.frameRate)           // clamped to 30
        assertEquals(6, c.iFrameIntervalSec)    // 6s GOP (vs the old 1s all-IDR)
    }

    @Test fun derive_keepsLowerSourceBitrateAndFps() {
        val c = CommittedEncoderConfig.derive(1280, 720, 3_000_000, 24, CastQuality.AUTO)
        assertEquals(3_000_000, c.bitrate)
        assertEquals(24, c.frameRate)
    }
}