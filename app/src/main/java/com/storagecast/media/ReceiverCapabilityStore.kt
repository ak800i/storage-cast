package com.storagecast.media

import android.content.Context

/**
 * Persists, per Cast receiver, which codecs it has failed to direct-play. The Cast SDK
 * exposes no codec-capability list, so capability is learned reactively: when a direct
 * play errors on the receiver shortly after load, the caller records the offending
 * source MIME(s) here, and [hints] then steers future casts of those codecs straight to
 * transcoding. Keyed by the receiver's stable deviceId so each TV/Chromecast learns
 * independently.
 */
class ReceiverCapabilityStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Learned hints for [deviceId]; empty when unknown or never failed. */
    fun hints(deviceId: String?): StreamingDecision.ReceiverHints {
        if (deviceId.isNullOrBlank()) return StreamingDecision.ReceiverHints()
        val stored = prefs.getStringSet(key(deviceId), emptySet()) ?: emptySet()
        // getStringSet returns a shared instance that must not be mutated — copy it.
        return StreamingDecision.ReceiverHints(unsupportedDirect = stored.toSet())
    }

    /**
     * Records that [mimes] failed direct play on [deviceId]. Returns true if this added
     * anything new (so the caller can log/learn only on genuine new information).
     */
    fun recordUnsupported(deviceId: String?, mimes: Collection<String>): Boolean {
        if (deviceId.isNullOrBlank()) return false
        val clean = mimes.map { it.lowercase() }.filter { it.isNotBlank() }.toSet()
        if (clean.isEmpty()) return false
        val existing = (prefs.getStringSet(key(deviceId), emptySet()) ?: emptySet()).toMutableSet()
        if (existing.containsAll(clean)) return false
        existing.addAll(clean)
        prefs.edit().putStringSet(key(deviceId), existing).apply()
        return true
    }

    /** Forgets everything learned about [deviceId] (advanced "re-probe from scratch"). */
    fun reset(deviceId: String?) {
        if (deviceId.isNullOrBlank()) return
        prefs.edit().remove(key(deviceId)).apply()
    }

    private fun key(deviceId: String) = "unsupported_$deviceId"

    companion object {
        private const val PREFS_NAME = "receiver_capabilities"
    }
}
