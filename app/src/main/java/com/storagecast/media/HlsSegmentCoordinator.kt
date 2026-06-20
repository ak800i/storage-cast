package com.storagecast.media

/**
 * Pure routing/relocation state machine for HlsTranscodeSession.segmentBytes(index).
 * Decides whether a request is served from cache, by a short wait for the production frontier, or by
 * an immediate one-off build — and whether the pipeline should re-base to follow a sustained
 * discontinuity. Anchored on the MOVING playhead (prevIndex), so normal playback far past the start
 * never re-bases. Not thread-safe; the caller mutates it only under its ReentrantLock.
 */
class HlsSegmentCoordinator(
    initialSegmentIndex: Int,
    private val lead: Int,
    private val readAhead: Int,
    private val waitMargin: Int,
    private val backBuffer: Int,
    private val relocateAfter: Int,
) {
    var prevIndex: Int = initialSegmentIndex
        private set
    private var relocRun = 0
    private var relocAnchor: Int? = null

    sealed class Decision {
        object ServeCached : Decision()
        data class WaitForProduction(val index: Int) : Decision()
        /** Serve [index] via a one-off build now; if [rebaseTo] != null, re-base the pipeline there. */
        data class OneOff(val index: Int, val rebaseTo: Int?) : Decision()
    }

    fun route(index: Int, frontier: Int, lowWatermark: Int, isCached: (Int) -> Boolean): Decision {
        if (isCached(index)) {
            prevIndex = index; relocRun = 0; relocAnchor = null
            return Decision.ServeCached
        }
        if (index > frontier && index <= frontier + waitMargin) {
            prevIndex = index; relocRun = 0; relocAnchor = null
            return Decision.WaitForProduction(index)
        }

        // out of reach: produced-but-evicted, far ahead, or a seek -> serve one-off now
        val anchor = relocAnchor
        if (anchor != null && index == anchor + relocRun) {
            relocRun += 1                                   // a started candidate is sustaining
        } else if (index > prevIndex + readAhead || index < lowWatermark) {
            relocAnchor = index; relocRun = 1               // a discontinuity starts a candidate
        } else {
            relocRun = 0; relocAnchor = null                // contiguous catch-up, not a seek
        }

        var rebaseTo: Int? = null
        if (relocRun >= relocateAfter) {
            // [index] is being served one-off right now, so treat it as cached when finding the base.
            rebaseTo = firstNonCached(relocAnchor!!) { i -> i == index || isCached(i) }
            relocRun = 0; relocAnchor = null
        }
        prevIndex = index
        return Decision.OneOff(index, rebaseTo)
    }

    private fun firstNonCached(from: Int, cached: (Int) -> Boolean): Int {
        var i = from
        while (cached(i)) i++
        return i
    }

    /** The retained low-watermark the caller passes is prevIndex - backBuffer; exposed for clarity. */
    fun lowWatermark(): Int = prevIndex - backBuffer
}