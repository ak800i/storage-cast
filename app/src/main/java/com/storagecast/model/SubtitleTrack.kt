package com.storagecast.model

data class SubtitleTrack(
    val index: Int,
    val language: String,
    val title: String,
    val codec: String,
    /**
     * Matroska track number when this track was discovered via the EBML fallback (because
     * MediaExtractor couldn't expose it). Null for tracks read through MediaExtractor.
     */
    val mkvTrackNumber: Int? = null
)
