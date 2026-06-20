package com.storagecast.media

import org.junit.Assert.assertEquals
import org.junit.Test

class HlsSegmentCoordinatorTest {
    private fun coord(initial: Int = 0) = HlsSegmentCoordinator(
        initialSegmentIndex = initial,
        lead = 4, readAhead = 2, waitMargin = 2, backBuffer = 2, relocateAfter = 2,
    )

    // Helper: a cache that contains a fixed set of indices.
    private fun cacheOf(vararg idx: Int): (Int) -> Boolean = { it in idx.toSet() }

    @Test fun cachedRequest_servesCache_advancesPlayhead() {
        val c = coord()
        val d = c.route(index = 1, frontier = 5, lowWatermark = 0, isCached = cacheOf(0, 1, 2))
        assertEquals(HlsSegmentCoordinator.Decision.ServeCached, d)
        assertEquals(1, c.prevIndex)
    }

    @Test fun justPastFrontier_waitsForProduction() {
        val c = coord()
        // frontier=5, request 6 (within frontier+WAIT_MARGIN=7), not cached
        val d = c.route(index = 6, frontier = 5, lowWatermark = 0, isCached = cacheOf(0, 1, 2, 3, 4, 5))
        assertEquals(HlsSegmentCoordinator.Decision.WaitForProduction(6), d)
    }

    @Test fun farForwardSeek_servesOneOff_andRebasesAfterSustained() {
        val c = coord(initial = 0)
        c.route(index = 0, frontier = 0, lowWatermark = 0, isCached = cacheOf(0))            // playhead at 0
        // seek to 50 (far past playhead+READAHEAD): one-off, no rebase yet (run=1)
        val d1 = c.route(index = 50, frontier = 0, lowWatermark = 0, isCached = cacheOf(0))
        assertEquals(HlsSegmentCoordinator.Decision.OneOff(50, rebaseTo = null), d1)
        // next adjacent request 51 confirms the relocation (run=2=RELOCATE_AFTER) -> rebase
        val d2 = c.route(index = 51, frontier = 0, lowWatermark = 0, isCached = cacheOf(0, 50))
        // base = first non-cached >= 50, treating 51 (served one-off now) as cached too -> 52
        assertEquals(HlsSegmentCoordinator.Decision.OneOff(51, rebaseTo = 52), d2)
    }

    @Test fun transientFallBehind_servesOneOff_butNeverRebases() {
        val c = coord(initial = 0)
        // steady playback advanced prevIndex to 30 via cache hits
        for (i in 0..30) c.route(i, frontier = i + 4, lowWatermark = i - 2, isCached = { it <= i + 4 })
        // pipeline briefly stalls 2 behind: frontier=28; request 31 is just out of reach AND contiguous
        // with the moving playhead (31 - prevIndex(30) = 1 <= READAHEAD) -> catch-up one-off, NOT a seek.
        val d = c.route(index = 31, frontier = 28, lowWatermark = 28, isCached = { it <= 28 })
        assertEquals(HlsSegmentCoordinator.Decision.OneOff(31, rebaseTo = null), d)
        // next contiguous catch-up request 32 (32 - prevIndex(31) = 1 <= READAHEAD): still no rebase.
        val d2 = c.route(index = 32, frontier = 29, lowWatermark = 28, isCached = { it <= 29 })
        assertEquals(HlsSegmentCoordinator.Decision.OneOff(32, rebaseTo = null), d2)
    }

    @Test fun strayProbe_doesNotRebase() {
        val c = coord(initial = 40)
        c.route(index = 40, frontier = 44, lowWatermark = 38, isCached = { it <= 44 }) // playhead 40
        // lone seg0 probe (far below window) -> one-off, run=1 (does not reach RELOCATE_AFTER)
        val d = c.route(index = 0, frontier = 44, lowWatermark = 38, isCached = { it in 38..44 })
        assertEquals(HlsSegmentCoordinator.Decision.OneOff(0, rebaseTo = null), d)
        // receiver resumes at the still-cached playhead 45 -> ServeCached, relocation run reset, never rebased
        val d2 = c.route(index = 45, frontier = 49, lowWatermark = 43, isCached = { it in 43..49 })
        assertEquals(HlsSegmentCoordinator.Decision.ServeCached, d2)
    }

    @Test fun backwardRewindBelowWindow_rebasesWhenSustained() {
        val c = coord(initial = 100)
        c.route(index = 100, frontier = 104, lowWatermark = 98, isCached = { it in 98..104 })
        // rewind to 90 (below lowWatermark, not cached) -> one-off, run=1
        val d1 = c.route(index = 90, frontier = 104, lowWatermark = 98, isCached = { it in 98..104 })
        assertEquals(HlsSegmentCoordinator.Decision.OneOff(90, rebaseTo = null), d1)
        // adjacent 91 confirms -> rebase to the first NON-cached index >= 90, treating 90 (already
        // cached) and 91 (served one-off now) as cached -> base 92.
        val d2 = c.route(index = 91, frontier = 104, lowWatermark = 98, isCached = { it in 98..104 || it == 90 })
        assertEquals(HlsSegmentCoordinator.Decision.OneOff(91, rebaseTo = 92), d2)
    }
}