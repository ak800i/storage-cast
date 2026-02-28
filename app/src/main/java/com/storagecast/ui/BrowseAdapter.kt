package com.storagecast.ui

import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.storagecast.databinding.ItemFolderBinding
import com.storagecast.databinding.ItemVideoBinding
import com.storagecast.model.BrowseItem
import com.storagecast.model.VideoItem
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class BrowseAdapter(
    private val onFolderClick: (BrowseItem.Folder) -> Unit,
    private val onVideoClick: (VideoItem) -> Unit
) : ListAdapter<BrowseItem, RecyclerView.ViewHolder>(BrowseDiffCallback()) {

    companion object {
        private const val TYPE_FOLDER = 0
        private const val TYPE_VIDEO = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is BrowseItem.Folder -> TYPE_FOLDER
            is BrowseItem.Video -> TYPE_VIDEO
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_FOLDER -> {
                val binding = ItemFolderBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                FolderViewHolder(binding)
            }
            else -> {
                val binding = ItemVideoBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                VideoViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is BrowseItem.Folder -> (holder as FolderViewHolder).bind(item)
            is BrowseItem.Video -> (holder as VideoViewHolder).bind(item.videoItem)
        }
    }

    inner class FolderViewHolder(
        private val binding: ItemFolderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(folder: BrowseItem.Folder) {
            binding.folderName.text = folder.name
            val count = folder.videoCount
            binding.folderCount.text = binding.root.resources.getQuantityString(
                com.storagecast.R.plurals.folder_video_count, count, count
            )
            binding.root.setOnClickListener { onFolderClick(folder) }
        }
    }

    inner class VideoViewHolder(
        private val binding: ItemVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(video: VideoItem) {
            binding.videoTitle.text = video.title
            binding.videoPath.text = File(video.path).parent ?: ""
            binding.videoDuration.text = formatDuration(video.duration)
            binding.videoSize.text = formatSize(video.size)

            loadThumbnail(video)

            binding.root.setOnClickListener { onVideoClick(video) }
            binding.subtitleButton.visibility = android.view.View.GONE
        }

        private fun loadThumbnail(video: VideoItem) {
            binding.videoThumbnail.setImageResource(com.storagecast.R.drawable.ic_video_placeholder)
            binding.videoThumbnail.scaleType = android.widget.ImageView.ScaleType.CENTER
            try {
                val thumbnail: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    binding.root.context.contentResolver.loadThumbnail(
                        video.uri, Size(160, 120), null
                    )
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Video.Thumbnails.getThumbnail(
                        binding.root.context.contentResolver,
                        video.id,
                        MediaStore.Video.Thumbnails.MINI_KIND,
                        null
                    )
                }
                if (thumbnail != null) {
                    binding.videoThumbnail.setImageBitmap(thumbnail)
                    binding.videoThumbnail.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                }
            } catch (e: Exception) {
                // Thumbnail not available, placeholder already set
            }
        }

        private fun formatDuration(durationMs: Long): String {
            val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
            val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
            return if (hours > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%d:%02d", minutes, seconds)
            }
        }

        private fun formatSize(sizeBytes: Long): String {
            val mb = sizeBytes / (1024.0 * 1024.0)
            return if (mb >= 1024) {
                String.format(Locale.US, "%.1f GB", mb / 1024.0)
            } else {
                String.format(Locale.US, "%.1f MB", mb)
            }
        }
    }

    private class BrowseDiffCallback : DiffUtil.ItemCallback<BrowseItem>() {
        override fun areItemsTheSame(oldItem: BrowseItem, newItem: BrowseItem): Boolean {
            return when {
                oldItem is BrowseItem.Folder && newItem is BrowseItem.Folder ->
                    oldItem.path == newItem.path
                oldItem is BrowseItem.Video && newItem is BrowseItem.Video ->
                    oldItem.videoItem.id == newItem.videoItem.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: BrowseItem, newItem: BrowseItem): Boolean {
            return oldItem == newItem
        }
    }
}
