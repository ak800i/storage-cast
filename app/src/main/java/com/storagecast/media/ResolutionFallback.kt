package com.storagecast.media

/**
 * Walks descending quality [rungs] during prepare(). After producing a steady-state segment at the
 * current rung, call [evaluate] with its build ratio (wall_time / content_time). Steps down one rung
 * if the ratio is above [threshold] and a lower rung exists; otherwise commits the current rung.
 */
class ResolutionFallback(
    private val rungs: List<CastQuality>,
    private val threshold: Double = 0.85,
) {
    init { require(rungs.isNotEmpty()) }

    private var idx = 0

    val current: CastQuality get() = rungs[idx]
    val atFloor: Boolean get() = idx == rungs.lastIndex
    var committed: Boolean = false
        private set

    /** @return true if it stepped down a rung; false if it committed [current]. */
    fun evaluate(buildRatio: Double): Boolean {
        if (committed || buildRatio <= threshold || atFloor) {
            committed = true
            return false
        }
        idx++
        return true
    }
}