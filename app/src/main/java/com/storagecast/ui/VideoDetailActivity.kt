package com.storagecast.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.storagecast.R
import com.storagecast.databinding.ActivityVideoDetailBinding
import com.storagecast.log.AppLogger
import com.storagecast.media.CastCompatibility
import com.storagecast.media.MediaProbeResult
import com.storagecast.media.MediaProber
import com.storagecast.media.VideoTranscoder
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
        private const val TAG = "VideoDetail"
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

    private val mediaProber = MediaProber()
    private val castCompatibility = CastCompatibility()
    private var videoTranscoder: VideoTranscoder? = null
    private var transcodedFile: File? = null

    private val progressHandler = Handler(Looper.getMainLooper())
    private var isSeekBarDragging = false
    private var pendingSeekPositionMs = 0L
    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            updateSeekBarProgress()
            progressHandler.postDelayed(this, 1000)
        }
    }

    private val remoteMediaClientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val client = castSession?.remoteMediaClient
            val status = client?.mediaStatus ?: return
            val playerState = status.playerState
            AppLogger.info(TAG, "RemoteMediaClient status updated: playerState=$playerState, idleReason=${status.idleReason}")
            updateProgressTracking(playerState)
            if (playerState == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_IDLE) {
                when (status.idleReason) {
                    com.google.android.gms.cast.MediaStatus.IDLE_REASON_ERROR ->
                        AppLogger.error(TAG, "Cast playback error (IDLE_REASON_ERROR)")
                    com.google.android.gms.cast.MediaStatus.IDLE_REASON_CANCELED ->
                        AppLogger.warn(TAG, "Cast playback canceled")
                    com.google.android.gms.cast.MediaStatus.IDLE_REASON_INTERRUPTED ->
                        AppLogger.warn(TAG, "Cast playback interrupted")
                    com.google.android.gms.cast.MediaStatus.IDLE_REASON_FINISHED ->
                        AppLogger.info(TAG, "Cast playback finished")
                }
            }
        }

        override fun onMetadataUpdated() {
            AppLogger.info(TAG, "RemoteMediaClient metadata updated")
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MediaServerService.LocalBinder
            mediaServerService = binder.getService()
            serviceBound = true
            try {
                mediaServerService?.startServer()
                AppLogger.info(TAG, "Media server service connected and started")
            } catch (e: Exception) {
                AppLogger.error(TAG, "Failed to start media server: ${e.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            AppLogger.warn(TAG, "Media server service disconnected")
            mediaServerService = null
            serviceBound = false
        }
    }

    private val sessionManagerListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            AppLogger.info(TAG, "Cast session starting")
        }
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            AppLogger.info(TAG, "Cast session started: $sessionId, device=${session.castDevice?.friendlyName}")
            castSession = session
            session.remoteMediaClient?.registerCallback(remoteMediaClientCallback)
            updateCastStatus()
        }
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            AppLogger.error(TAG, "Cast session start failed: error=$error")
        }
        override fun onSessionEnding(session: CastSession) {
            AppLogger.info(TAG, "Cast session ending")
            val client = session.remoteMediaClient
            if (client?.hasMediaSession() == true) {
                pendingSeekPositionMs = client.approximateStreamPosition
            }
        }
        override fun onSessionEnded(session: CastSession, error: Int) {
            AppLogger.info(TAG, "Cast session ended: error=$error")
            session.remoteMediaClient?.unregisterCallback(remoteMediaClientCallback)
            castSession = null
            updateCastStatus()
            stopProgressUpdates()
            resetSeekBarToLocal()
        }
        override fun onSessionResuming(session: CastSession, sessionId: String) {
            AppLogger.info(TAG, "Cast session resuming: $sessionId")
        }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            AppLogger.info(TAG, "Cast session resumed, wasSuspended=$wasSuspended")
            castSession = session
            session.remoteMediaClient?.registerCallback(remoteMediaClientCallback)
            updateCastStatus()
        }
        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            AppLogger.error(TAG, "Cast session resume failed: error=$error")
        }
        override fun onSessionSuspended(session: CastSession, reason: Int) {
            AppLogger.info(TAG, "Cast session suspended: reason=$reason")
        }
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

        if (videoItem != null) {
            AppLogger.info(TAG, "Opened video: title=${videoItem!!.title}, path=${videoItem!!.path}, mime=${videoItem!!.mimeType}, size=${videoItem!!.size}")
        } else {
            AppLogger.error(TAG, "No video item in intent extras")
        }

        setupCast()
        bindMediaServer()
        displayVideoInfo()
        setupControls()
        setupSeekBar()
    }

    private fun displayVideoInfo() {
        val video = videoItem ?: return
        supportActionBar?.title = video.title

        binding.videoTitle.text = video.title

        val duration = formatDuration(video.duration)
        val size = formatSize(video.size)
        binding.videoInfo.text = "$duration • $size"
        binding.videoPath.text = video.path

        if (video.duration > 0) {
            binding.seekBarContainer.visibility = View.VISIBLE
            binding.videoSeekBar.max = video.duration.toInt()
            binding.videoSeekBar.progress = 0
            binding.currentTimeText.text = formatDuration(0)
            binding.totalTimeText.text = formatDuration(video.duration)
        }

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
            AppLogger.info(TAG, "Play pressed")
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
            AppLogger.info(TAG, "Pause pressed")
            session.remoteMediaClient?.pause()
            Toast.makeText(this, R.string.video_paused, Toast.LENGTH_SHORT).show()
        }

        binding.stopButton.setOnClickListener {
            val session = castSession
            if (session == null || session.isConnected != true) {
                Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AppLogger.info(TAG, "Stop pressed")
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
                AppLogger.warn(TAG, "Cast video pressed but no session connected")
                Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val service = mediaServerService
            if (service == null || !service.isServerRunning()) {
                AppLogger.warn(TAG, "Cast video pressed but media server not ready (service=$service, running=${service?.isServerRunning()})")
                Toast.makeText(this, R.string.server_not_ready, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            checkCompatibilityAndCast(video)
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
                AppLogger.info(TAG, "Subtitle extracted: ${subtitleFile.name}")
            } else {
                Toast.makeText(this@VideoDetailActivity, R.string.error_subtitle, Toast.LENGTH_SHORT).show()
                AppLogger.error(TAG, "Failed to extract subtitle for track ${track.index}")
            }
        }
    }

    private fun checkCompatibilityAndCast(video: VideoItem) {
        binding.progressBar.visibility = View.VISIBLE
        activityScope.launch {
            val probeResult = withContext(Dispatchers.IO) {
                mediaProber.probe(video.path)
            }
            binding.progressBar.visibility = View.GONE

            if (probeResult == null) {
                AppLogger.warn(TAG, "Media probe failed, casting directly")
                Toast.makeText(this@VideoDetailActivity, R.string.probe_failed, Toast.LENGTH_SHORT).show()
                castVideo(video, selectedSubtitleFile)
                return@launch
            }

            val result = castCompatibility.checkCompatibility(probeResult)
            if (result.isFullyCompatible) {
                AppLogger.info(TAG, "All codecs compatible, casting directly")
                castVideo(video, selectedSubtitleFile)
            } else {
                showCodecCompatibilityDialog(video, probeResult, result)
            }
        }
    }

    private fun showCodecCompatibilityDialog(
        video: VideoItem,
        probeResult: MediaProbeResult,
        compatResult: CastCompatibility.CompatibilityResult
    ) {
        AppLogger.warn(TAG, "Codec compatibility issue: ${compatResult.summary}")

        val dialogView = layoutInflater.inflate(R.layout.dialog_codec_info, null)
        val infoText = dialogView.findViewById<android.widget.TextView>(R.id.codecInfoText)
        infoText.text = compatResult.detailedInfo
        infoText.typeface = android.graphics.Typeface.MONOSPACE
        infoText.setTextIsSelectable(true)

        AlertDialog.Builder(this)
            .setTitle(R.string.codec_incompatible_title)
            .setView(dialogView)
            .setPositiveButton(R.string.direct_stream) { _, _ ->
                AppLogger.info(TAG, "User chose direct stream despite incompatible codecs")
                castVideo(video, selectedSubtitleFile)
            }
            .setNegativeButton(R.string.transcode) { _, _ ->
                AppLogger.info(TAG, "User chose to transcode")
                startTranscoding(video, probeResult)
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun startTranscoding(video: VideoItem, probeResult: MediaProbeResult) {
        val transcoder = VideoTranscoder()
        videoTranscoder = transcoder

        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.transcoding_title)
            .setMessage(getString(R.string.transcoding_progress, 0))
            .setNegativeButton(R.string.cancel) { _, _ ->
                transcoder.cancel()
            }
            .setCancelable(false)
            .show()

        activityScope.launch {
            withContext(Dispatchers.IO) {
                val outputDir = File(cacheDir, "transcode")
                if (!outputDir.exists() && !outputDir.mkdirs()) {
                    runOnUiThread {
                        Toast.makeText(
                            this@VideoDetailActivity,
                            getString(R.string.transcode_failed, "Cannot create output directory"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@withContext
                }

                transcoder.transcode(video.path, outputDir, probeResult,
                    object : VideoTranscoder.ProgressListener {
                        override fun onProgress(percent: Int) {
                            runOnUiThread {
                                progressDialog.setMessage(getString(R.string.transcoding_progress, percent))
                            }
                        }

                        override fun onCompleted(outputFile: File) {
                            runOnUiThread {
                                progressDialog.dismiss()
                                transcodedFile = outputFile
                                AppLogger.info(TAG, "Transcode complete: ${outputFile.name}, ${outputFile.length()} bytes")
                                castTranscodedVideo(video, outputFile)
                            }
                        }

                        override fun onError(error: String) {
                            runOnUiThread {
                                progressDialog.dismiss()
                                AppLogger.error(TAG, "Transcode error: $error")
                                Toast.makeText(
                                    this@VideoDetailActivity,
                                    getString(R.string.transcode_failed, error),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    })
            }
        }
    }

    private fun castTranscodedVideo(originalVideo: VideoItem, transcodedFile: File) {
        val service = mediaServerService
        if (service == null) {
            AppLogger.error(TAG, "castTranscodedVideo: media server service is null")
            Toast.makeText(this, R.string.server_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val session = castSession
        if (session == null) {
            AppLogger.error(TAG, "castTranscodedVideo: cast session is null")
            Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show()
            return
        }

        val serverIp = getDeviceIpAddress()
        val serverPort = service.getServerPort()

        val videoPath = service.registerFile(transcodedFile.absolutePath, "video/mp4", null)
        val videoUrl = "http://$serverIp:$serverPort$videoPath"

        AppLogger.info(TAG, "castTranscodedVideo: url=$videoUrl, size=${transcodedFile.length()}")

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, "${originalVideo.title} (transcoded)")
        }

        val mediaTracks = mutableListOf<MediaTrack>()

        if (selectedSubtitleFile != null) {
            val subtitlePath = service.registerSubtitle(selectedSubtitleFile!!)
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
            .setContentType("video/mp4")
            .setMetadata(metadata)
            .setMediaTracks(mediaTracks)
            .build()

        val loadRequest = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .setCurrentTime(pendingSeekPositionMs)
            .apply {
                if (mediaTracks.isNotEmpty()) {
                    setActiveTrackIds(longArrayOf(1))
                }
            }
            .build()

        AppLogger.info(TAG, "castTranscodedVideo: sending load request")
        val remoteMediaClient = session.remoteMediaClient
        if (remoteMediaClient == null) {
            AppLogger.error(TAG, "castTranscodedVideo: remoteMediaClient is null!")
            Toast.makeText(this, R.string.error_cast, Toast.LENGTH_SHORT).show()
            return
        }

        val pendingResult = remoteMediaClient.load(loadRequest)
        pendingResult.setResultCallback { result ->
            val status = result.status
            if (status.isSuccess) {
                AppLogger.info(TAG, "castTranscodedVideo: load SUCCESS")
            } else {
                AppLogger.error(TAG, "castTranscodedVideo: load FAILED - ${status.statusMessage}")
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.cast_load_failed, status.statusMessage ?: "Unknown error"), Toast.LENGTH_LONG).show()
                }
            }
        }
        updateCastStatus("${originalVideo.title} (transcoded)")
        Toast.makeText(this, R.string.loading_video, Toast.LENGTH_SHORT).show()
    }

    private fun castVideo(video: VideoItem, subtitleFile: File?) {
        val service = mediaServerService
        if (service == null) {
            AppLogger.error(TAG, "castVideo: media server service is null")
            Toast.makeText(this, R.string.server_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val session = castSession
        if (session == null) {
            AppLogger.error(TAG, "castVideo: cast session is null")
            Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show()
            return
        }

        val serverIp = getDeviceIpAddress()
        val serverPort = service.getServerPort()

        AppLogger.info(TAG, "castVideo: serverIp=$serverIp, serverPort=$serverPort, serverRunning=${service.isServerRunning()}")

        val videoPath = service.registerFile(video.path, video.mimeType, video.uri)
        val videoUrl = "http://$serverIp:$serverPort$videoPath"

        AppLogger.info(TAG, "castVideo: videoUrl=$videoUrl, mimeType=${video.mimeType}")
        AppLogger.info(TAG, "castVideo: file path=${video.path}, exists=${File(video.path).exists()}")

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, video.title)
        }

        val mediaTracks = mutableListOf<MediaTrack>()

        if (subtitleFile != null) {
            val subtitlePath = service.registerSubtitle(subtitleFile)
            val subtitleUrl = "http://$serverIp:$serverPort$subtitlePath"
            AppLogger.info(TAG, "castVideo: subtitleUrl=$subtitleUrl")

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

        val startPositionMs = pendingSeekPositionMs

        val loadRequest = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .setCurrentTime(startPositionMs)
            .apply {
                if (mediaTracks.isNotEmpty()) {
                    setActiveTrackIds(longArrayOf(1))
                }
            }
            .build()

        AppLogger.info(TAG, "castVideo: sending load request to cast device (startPosition=${formatDuration(startPositionMs)})")
        val remoteMediaClient = session.remoteMediaClient
        if (remoteMediaClient == null) {
            AppLogger.error(TAG, "castVideo: remoteMediaClient is null!")
            Toast.makeText(this, R.string.error_cast, Toast.LENGTH_SHORT).show()
            return
        }

        val pendingResult = remoteMediaClient.load(loadRequest)
        pendingResult.setResultCallback { result ->
            val status = result.status
            if (status.isSuccess) {
                AppLogger.info(TAG, "castVideo: load SUCCESS")
            } else {
                AppLogger.error(TAG, "castVideo: load FAILED - statusCode=${status.statusCode}, statusMessage=${status.statusMessage}")
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.cast_load_failed, status.statusMessage ?: "Unknown error"), Toast.LENGTH_LONG).show()
                }
            }
        }
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
            AppLogger.info(TAG, "Cast context initialized")
        } catch (e: Exception) {
            AppLogger.warn(TAG, "Cast not available: ${e.message}")
        }
    }

    private fun bindMediaServer() {
        AppLogger.info(TAG, "Binding media server service")
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_logs -> {
                startActivity(Intent(this, LogActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun setupSeekBar() {
        binding.videoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.currentTimeText.text = formatDuration(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeekBarDragging = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeekBarDragging = false
                val position = seekBar?.progress?.toLong() ?: return
                val client = castSession?.remoteMediaClient
                if (client?.hasMediaSession() == true) {
                    AppLogger.info(TAG, "Seeking cast to ${formatDuration(position)}")
                    client.seek(MediaSeekOptions.Builder().setPosition(position).build()).setResultCallback { result ->
                        if (!result.status.isSuccess) {
                            AppLogger.error(TAG, "Seek failed: ${result.status.statusMessage}")
                        }
                    }
                } else {
                    pendingSeekPositionMs = position
                    AppLogger.info(TAG, "Start position set to ${formatDuration(position)}")
                }
            }
        })
    }

    private fun updateProgressTracking(playerState: Int) {
        val isActive = playerState == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PLAYING ||
                playerState == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PAUSED ||
                playerState == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_BUFFERING
        if (isActive) {
            startProgressUpdates()
        } else {
            stopProgressUpdates()
        }
    }

    private fun resetSeekBarToLocal() {
        val video = videoItem ?: return
        if (video.duration > 0) {
            binding.videoSeekBar.max = video.duration.toInt()
            binding.videoSeekBar.progress = pendingSeekPositionMs.toInt()
            binding.currentTimeText.text = formatDuration(pendingSeekPositionMs)
            binding.totalTimeText.text = formatDuration(video.duration)
        }
    }

    private fun updateSeekBarProgress() {
        if (isSeekBarDragging) return
        val client = castSession?.remoteMediaClient ?: return
        val duration = client.streamDuration
        val position = client.approximateStreamPosition
        if (duration > 0) {
            binding.videoSeekBar.max = duration.toInt()
            binding.videoSeekBar.progress = position.toInt()
            binding.currentTimeText.text = formatDuration(position)
            binding.totalTimeText.text = formatDuration(duration)
        }
    }

    private fun startProgressUpdates() {
        progressHandler.removeCallbacks(progressUpdateRunnable)
        progressHandler.post(progressUpdateRunnable)
    }

    private fun stopProgressUpdates() {
        progressHandler.removeCallbacks(progressUpdateRunnable)
    }

    override fun onResume() {
        super.onResume()
        try {
            castContext?.sessionManager?.addSessionManagerListener(
                sessionManagerListener, CastSession::class.java
            )
            val currentSession = castContext?.sessionManager?.currentCastSession
            castSession = currentSession
            currentSession?.remoteMediaClient?.registerCallback(remoteMediaClientCallback)
            updateCastStatus()
            AppLogger.info(TAG, "onResume: castSession=${if (currentSession != null) "connected to ${currentSession.castDevice?.friendlyName}" else "null"}")
        } catch (e: Exception) {
            AppLogger.warn(TAG, "Cast not available in onResume: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        stopProgressUpdates()
        try {
            castSession?.remoteMediaClient?.unregisterCallback(remoteMediaClientCallback)
            castContext?.sessionManager?.removeSessionManagerListener(
                sessionManagerListener, CastSession::class.java
            )
        } catch (e: Exception) {
            // Cast not available
        }
    }

    override fun onDestroy() {
        stopProgressUpdates()
        videoTranscoder?.cancel()
        activityScope.cancel()
        transcodedFile?.delete()
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
