package com.storagecast.model

import android.net.Uri
import android.os.Build
import android.os.Parcel
import android.os.Parcelable

data class VideoItem(
    val id: Long,
    val title: String,
    val uri: Uri,
    val path: String,
    val duration: Long,
    val size: Long,
    val mimeType: String
) : Parcelable {

    constructor(parcel: Parcel) : this(
        id = parcel.readLong(),
        title = parcel.readString() ?: "",
        uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            parcel.readParcelable(Uri::class.java.classLoader, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            parcel.readParcelable(Uri::class.java.classLoader)
        } ?: Uri.EMPTY,
        path = parcel.readString() ?: "",
        duration = parcel.readLong(),
        size = parcel.readLong(),
        mimeType = parcel.readString() ?: ""
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(id)
        dest.writeString(title)
        dest.writeParcelable(uri, flags)
        dest.writeString(path)
        dest.writeLong(duration)
        dest.writeLong(size)
        dest.writeString(mimeType)
    }

    companion object CREATOR : Parcelable.Creator<VideoItem> {
        override fun createFromParcel(parcel: Parcel): VideoItem = VideoItem(parcel)
        override fun newArray(size: Int): Array<VideoItem?> = arrayOfNulls(size)
    }
}
