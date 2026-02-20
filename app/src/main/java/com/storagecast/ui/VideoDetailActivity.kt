package com.storagecast.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.util.Size
import android.view.Menu
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.storagecast.R
import com.storagecast.databinding.ActivityVideoDetailBinding
import com.storagecast.model.SubtitleTrack
import com.storagecast.model.VideoItem
import com.storagecast.server.MediaServerService
import com.storagecast.subtitle.SubtitleExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class VideoDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO = "extra_video"
    }

    private lateinit var binding: ActivityVideoDetailBinding
    private var videoItem: VideoItem? = null

    private var castContext: CastContext? = null
    private var castSession: CastSession? = null
    private var mediaServerService: MediaServerService? = null
    private var serviceBound = false

    private val subtitleExtractor = SubtitleExtractor()
    private var selectedSubtitleFile: File? = null
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MediaServerService.LocalBinder
            mediaServerService = binder.getService()
            serviceBound = true
            mediaServerService?.startServer()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mediaServerService = null
            serviceBound = false
        }
    }

    private val sessionManagerListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            castSession = session
            updateCastStatus()
        }
        override fun onSessionStartFailed(session: CastSession, error: Int) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionEnded(session: CastSession, error: Int) {
            castSession = null
            updateCastStatus()
        }
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            castSession = session
            updateCastStatus()
        }
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        @Suppress("DEPRECATION")
        videoItem = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_VIDEO, VideoItem::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_VIDEO)
        }

        setupCast()
        bindMediaServer()
        displayVideoInfo()
        setupControls()
    }

    private fun displayVideoInfo() {
        val video = videoItem ?: return
        supportActionBar?.title = video.title

        binding.videoTitle.text = video.title

        val duration = formatDuration(video.duration)
        val size = formatSize(video.size)
        binding.videoInfo.text = "$duration • $size"
        binding.videoPath.text = video.path

        loadThumbnail(video)
    }

    private fun loadThumbnail(video: VideoItem) {
        try {
            val thumbnail: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.loadThumbnail(video.uri, Size(640, 360), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    contentResolver, video.id,
                    MediaStore.Video.Thumbnails.MINI_KIND, null
                )
            }
            if (thumbnail != null) {
                binding.videoThumbnail.setImageBitmap(thumbnail)
            }
        } catch (e: Exception) {
            // Thumbnail not available
        }
    }

    private fun setupControls() {
        binding.playButton.setOnClickListener {
            val session = castSession
            if (session == null || session.isConnected != true) {
                Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            session.remoteMediaClient?.play()
            val video = videoItem
            Toast.makeText(this, getString(R.string.video_playing, video?.title ?: ""), Toast.LENGTH_SHORT).show()
        }

        binding.pauseButton.setOnClickListener {
            val session = castSession
            if (session == null || session.isConnected != true) {
                Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            session.remoteMediaClient?.pause()
            Toast.makeText(this, R.string.video_paused, Toast.LENGTH_SHORT).show()
        }

        binding.stopButton.setOnClickListener {
            val session = castSession
            if (session == null || session.isConnected != true) {
                Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            session.remoteMediaClient?.stop()
            Toast.makeText(this, R.string.video_stopped, Toast.LENGTH_SHORT).show()
        }

        binding.subtitleButton.setOnClickListener {
            val video = videoItem ?: return@setOnClickListener
            loadSubtitleTracks(video)
        }

        binding.castAndPlayButton.setOnClickListener {
            val video = videoItem ?: return@setOnClickListener
            val session = castSession
            if (session == null || session.isConnected != true) {
                Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            castVideo(video, selectedSubtitleFile)
        }
    }

    private fun loadSubtitleTracks(video: VideoItem) {
        binding.progressBar.visibility = View.VISIBLE
        activityScope.launch {
            val tracks = withContext(Dispatchers.IO) {
                subtitleExtractor.getSubtitleTracks(video.path)
            }
            binding.progressBar.visibility = View.GONE
            if (tracks.isEmpty()) {
                Toast.makeText(this@VideoDetailActivity, R.string.no_subtitles, Toast.LENGTH_SHORT).show()
            } else {
                showSubtitleDialog(video, tracks)
            }
        }
    }

    private fun showSubtitleDialog(video: VideoItem, tracks: List<SubtitleTrack>) {
        val options = mutableListOf("No subtitles")
        tracks.forEach { track ->
            options.add("${track.language} - ${track.title} (${track.codec})")
        }

        AlertDialog.Builder(this)
            .setTitle("Select subtitle track")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    selectedSubtitleFile = null
                    binding.subtitleStatus.visibility = View.GONE
                } else {
                    val track = tracks[which - 1]
                    extractSubtitle(video.path, track)
                }
            }
            .show()
    }

    private fun extractSubtitle(videoPath: String, track: SubtitleTrack) {
        binding.progressBar.visibility = View.VISIBLE
        activityScope.launch {
            val subtitleFile = withContext(Dispatchers.IO) {
                val outputDir = File(cacheDir, "subtitles")
                outputDir.mkdirs()
                subtitleExtractor.extractSubtitleAsVtt(videoPath, track.index, outputDir)
            }
            binding.progressBar.visibility = View.GONE
            if (subtitleFile != null) {
                selectedSubtitleFile = subtitleFile
                binding.subtitleStatus.text = getString(R.string.subtitle_selected, track.language)
                binding.subtitleStatus.visibility = View.VISIBLE
            } else {
                Toast.makeText(this@VideoDetailActivity, R.string.error_subtitle, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun castVideo(video: VideoItem, subtitleFile: File?) {
        val service = mediaServerService ?: return
        val session = castSession ?: return

        val serverIp = getDeviceIpAddress()
        val serverPort = service.getServerPort()

        val videoPath = service.registerFile(video.path, video.mimeType)
        val videoUrl = "http://$serverIp:$serverPort$videoPath"

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, video.title)
        }

        val mediaTracks = mutableListOf<MediaTrack>()

        if (subtitleFile != null) {
            val subtitlePath = service.registerSubtitle(subtitleFile)
            val subtitleUrl = "http://$serverIp:$serverPort$subtitlePath"

            val subtitleTrack = MediaTrack.Builder(1, MediaTrack.TYPE_TEXT)
                .setName("Subtitles")
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(subtitleUrl)
                .setContentType("text/vtt")
                .setLanguage("en")
                .build()

            mediaTracks.add(subtitleTrack)
        }

        val mediaInfo = MediaInfo.Builder(videoUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(video.mimeType)
            .setMetadata(metadata)
            .setMediaTracks(mediaTracks)
            .build()

        val loadRequest = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .apply {
                if (mediaTracks.isNotEmpty()) {
                    setActiveTrackIds(longArrayOf(1))
                }
            }
            .build()

        session.remoteMediaClient?.load(loadRequest)
        updateCastStatus(video.title)

        Toast.makeText(this, R.string.loading_video, Toast.LENGTH_SHORT).show()
    }

    @Suppress("DEPRECATION")
    private fun getDeviceIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipInt = wifiManager.connectionInfo.ipAddress
        return String.format(
            Locale.US, "%d.%d.%d.%d",
            ipInt and 0xff, ipInt shr 8 and 0xff,
            ipInt shr 16 and 0xff, ipInt shr 24 and 0xff
        )
    }

    private fun updateCastStatus(videoTitle: String? = null) {
        if (castSession?.isConnected == true) {
            binding.castStatusBar.visibility = View.VISIBLE
            val deviceName = castSession?.castDevice?.friendlyName ?: "device"
            binding.castStatusText.text = if (videoTitle != null) {
                "$videoTitle → $deviceName"
            } else {
                getString(R.string.casting_to, deviceName)
            }
        } else {
            binding.castStatusBar.visibility = View.GONE
        }
    }

    private fun setupCast() {
        try {
            castContext = CastContext.getSharedInstance(this)
        } catch (e: Exception) {
            // Cast not available
        }
    }

    private fun bindMediaServer() {
        Intent(this, MediaServerService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.video_detail_menu, menu)
        CastButtonFactory.setUpMediaRouteButton(
            applicationContext, menu, R.id.media_route_menu_item
        )
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        try {
            castContext?.sessionManager?.addSessionManagerListener(
                sessionManagerListener, CastSession::class.java
            )
            castSession = castContext?.sessionManager?.currentCastSession
            updateCastStatus()
        } catch (e: Exception) {
            // Cast not available
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            castContext?.sessionManager?.removeSessionManagerListener(
                sessionManagerListener, CastSession::class.java
            )
        } catch (e: Exception) {
            // Cast not available
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
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
