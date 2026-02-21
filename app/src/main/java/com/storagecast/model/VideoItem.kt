package com.storagecast.model

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class VideoItem(
    val id: Long,
    val title: String,
    val uri: Uri,
    val path: String,
    val duration: Long,
    val size: Long,
    val mimeType: String
) : Parcelable
