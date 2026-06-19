package com.storagecast.media

/** Cast video-quality cap. AUTO starts at 1080p and lets the measured fallback step down. */
enum class CastQuality {
    AUTO, P1080, P720, P540;

    /** Max (width, height) cap fed to [HlsTranscodeMath.outputSize]. */
    fun maxDimensions(): Pair<Int, Int> = when (this) {
        AUTO, P1080 -> 1920 to 1080
        P720 -> 1280 to 720
        P540 -> 960 to 540
    }

    companion object {
        fun fromPref(value: String?): CastQuality = when (value) {
            "1080" -> P1080
            "720" -> P720
            "540" -> P540
            else -> AUTO
        }

        /** The descending rung order the AUTO measured fallback walks. */
        fun autoRungs(): List<CastQuality> = listOf(P1080, P720, P540)
    }
}