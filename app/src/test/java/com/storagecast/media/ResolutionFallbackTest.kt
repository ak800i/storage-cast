package com.storagecast.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolutionFallbackTest {
    @Test fun sustains_commitsCurrentRung() {
        val fb = ResolutionFallback(CastQuality.autoRungs(), threshold = 0.85)
        assertEquals(CastQuality.P1080, fb.current)
        assertFalse(fb.evaluate(0.70))     // ratio below threshold -> committed, no step
        assertEquals(CastQuality.P1080, fb.current)
        assertTrue(fb.committed)
    }

    @Test fun overThreshold_stepsDownOneRung_thenSustains() {
        val fb = ResolutionFallback(CastQuality.autoRungs(), threshold = 0.85)
        assertTrue(fb.evaluate(0.95))      // too slow at 1080 -> step down
        assertEquals(CastQuality.P720, fb.current)
        assertFalse(fb.committed)
        assertFalse(fb.evaluate(0.60))     // 720 sustains -> commit
        assertEquals(CastQuality.P720, fb.current)
        assertTrue(fb.committed)
    }

    @Test fun atFloor_commitsEvenWhenStillTooSlow() {
        val fb = ResolutionFallback(CastQuality.autoRungs(), threshold = 0.85)
        fb.evaluate(0.95)                  // -> 720
        fb.evaluate(0.95)                  // -> 540
        assertEquals(CastQuality.P540, fb.current)
        assertFalse(fb.evaluate(0.95))     // floor: accept buffering, no further step
        assertEquals(CastQuality.P540, fb.current)
        assertTrue(fb.committed)
        assertTrue(fb.atFloor)
    }

    @Test fun manualSingleRung_alwaysCommits() {
        val fb = ResolutionFallback(listOf(CastQuality.P720), threshold = 0.85)
        assertFalse(fb.evaluate(0.99))     // single rung: commit regardless
        assertEquals(CastQuality.P720, fb.current)
        assertTrue(fb.committed)
    }
}