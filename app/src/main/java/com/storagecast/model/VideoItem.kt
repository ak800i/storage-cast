package com.storagecast.model

import android.net.Uri

data class VideoItem(
    val id: Long,
    val title: String,
    val uri: Uri,
    val path: String,
    val duration: Long,
    val size: Long,
    val mimeType: String
)
