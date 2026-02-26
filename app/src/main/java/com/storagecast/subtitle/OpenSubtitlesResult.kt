package com.storagecast.subtitle

data class OpenSubtitlesResult(
    val fileId: Int,
    val fileName: String,
    val language: String,
    val release: String,
    val downloadCount: Int
)
