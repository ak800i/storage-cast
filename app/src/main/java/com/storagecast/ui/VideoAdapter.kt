package com.storagecast.ui

import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.storagecast.databinding.ItemVideoBinding
import com.storagecast.model.VideoItem
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class VideoAdapter(
    private val onVideoClick: (VideoItem) -> Unit,
    private val onSubtitleClick: (VideoItem) -> Unit
) : ListAdapter<VideoItem, VideoAdapter.VideoViewHolder>(VideoDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemVideoBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(getItem(position))
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
            binding.subtitleButton.visibility = View.VISIBLE
            binding.subtitleButton.setOnClickListener { onSubtitleClick(video) }
        }

        private fun loadThumbnail(video: VideoItem) {
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
                }
            } catch (e: Exception) {
                // Thumbnail not available
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

    private class VideoDiffCallback : DiffUtil.ItemCallback<VideoItem>() {
        override fun areItemsTheSame(oldItem: VideoItem, newItem: VideoItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: VideoItem, newItem: VideoItem): Boolean {
            return oldItem == newItem
        }
    }
}
