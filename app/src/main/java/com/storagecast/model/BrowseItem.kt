package com.storagecast.model

sealed class BrowseItem {
    data class Folder(
        val name: String,
        val path: String,
        val videoCount: Int
    ) : BrowseItem()

    data class Video(val videoItem: VideoItem) : BrowseItem()
}
