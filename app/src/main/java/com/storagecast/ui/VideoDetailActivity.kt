package com.storagecast.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.activity.result.contract.ActivityResultContracts
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
import com.storagecast.media.AudioTrackInfo
import com.storagecast.media.CastCompatibility
import com.storagecast.media.MediaProbeResult
import com.storagecast.media.MediaProber
import com.storagecast.media.Mp4ToMkvStreamer
import com.storagecast.media.MkvTrackFilter
import com.storagecast.media.VideoTranscoder
import com.storagecast.model.SubtitleTrack
import com.storagecast.model.VideoItem
import com.storagecast.server.MediaServerService
import com.storagecast.subtitle.SubtitleConverter
import com.storagecast.subtitle.SubtitleExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

class VideoDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO = "extra_video"
        private const val TAG = "VideoDetail"
        private const val SEEK_OFFSET_MS = 30_000L
    }

    private lateinit var binding: ActivityVideoDetailBinding
    private var videoItem: VideoItem? = null
    private var thumbnailRotation = 0f

    private var castContext: CastContext? = null
    private var castSession: CastSession? = null
    private var mediaServerService: MediaServerService? = null
    private var serviceBound = false

    private val subtitleExtractor = SubtitleExtractor()
    private val subtitleConverter = SubtitleConverter()
    private var selectedSubtitleFile: File? = null
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var selectedAudioTrack: AudioTrackInfo? = null
    private var cachedProbeResult: MediaProbeResult? = null

    private val subtitleFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            handleSubtitleFileSelected(uri)
        }
    }

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
            val playerStateName = when (playerState) {
                com.google.android.gms.cast.MediaStatus.PLAYER_STATE_IDLE -> "IDLE"
                com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PLAYING -> "PLAYING"
                com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PAUSED -> "PAUSED"
                com.google.android.gms.cast.MediaStatus.PLAYER_STATE_BUFFERING -> "BUFFERING"
                com.google.android.gms.cast.MediaStatus.PLAYER_STATE_LOADING -> "LOADING"
                else -> "UNKNOWN($playerState)"
            }
            val idleReasonName = when (status.idleReason) {
                com.google.android.gms.cast.MediaStatus.IDLE_REASON_NONE -> "NONE"
                com.google.android.gms.cast.MediaStatus.IDLE_REASON_FINISHED -> "FINISHED"
                com.google.android.gms.cast.MediaStatus.IDLE_REASON_CANCELED -> "CANCELED"
                com.google.android.gms.cast.MediaStatus.IDLE_REASON_INTERRUPTED -> "INTERRUPTED"
                com.google.android.gms.cast.MediaStatus.IDLE_REASON_ERROR -> "ERROR"
                else -> "UNKNOWN(${status.idleReason})"
            }
            AppLogger.info(TAG, "RemoteMediaClient status: playerState=$playerStateName, idleReason=$idleReasonName")
            AppLogger.info(TAG, "  streamPosition=${status.streamPosition}, streamDuration=${client.streamDuration}, volume=${status.streamVolume}, muted=${status.isMute}")
            updateProgressTracking(playerState)
            if (playerState == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_IDLE) {
                when (status.idleReason) {
                    com.google.android.gms.cast.MediaStatus.IDLE_REASON_ERROR -> {
                        val mediaInfo = status.mediaInfo
                        val contentId = mediaInfo?.contentId ?: "unknown"
                        val contentType = mediaInfo?.contentType ?: "unknown"
                        val streamType = mediaInfo?.streamType ?: -1
                        val streamTypeName = when (streamType) {
                            MediaInfo.STREAM_TYPE_BUFFERED -> "BUFFERED"
                            MediaInfo.STREAM_TYPE_LIVE -> "LIVE"
                            MediaInfo.STREAM_TYPE_NONE -> "NONE"
                            else -> "UNKNOWN($streamType)"
                        }
                        val customData = status.customData
                        AppLogger.error(TAG, "Cast playback error: contentId=$contentId, contentType=$contentType, streamType=$streamTypeName, customData=$customData")
                        AppLogger.error(TAG, "  Cast device: ${castSession?.castDevice?.friendlyName ?: "unknown"}, model=${castSession?.castDevice?.modelName ?: "unknown"}")
                        AppLogger.error(TAG, "  Media server running: ${mediaServerService?.isServerRunning()}")
                        if (mediaInfo != null) {
                            AppLogger.error(TAG, "  MediaInfo streamDuration=${mediaInfo.streamDuration}, mediaTracks=${mediaInfo.mediaTracks?.size ?: 0}")
                        }
                    }
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

    private fun handleCastSessionFailure(session: CastSession, error: Int) {
        session.remoteMediaClient?.unregisterCallback(remoteMediaClientCallback)
        castSession = null
        updateCastStatus()
        stopProgressUpdates()
        resetSeekBarToLocal()
        Toast.makeText(this, getString(R.string.cast_session_failed, error), Toast.LENGTH_LONG).show()
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
            handleCastSessionFailure(session, error)
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
            handleCastSessionFailure(session, error)
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
            val video = videoItem ?: return@setOnClickListener
            val session = castSession
            if (session == null || session.isConnected != true) {
                Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val playerState = session.remoteMediaClient?.mediaStatus?.playerState
            if (isMediaActive(playerState)) {
                AppLogger.info(TAG, "Play pressed – resuming")
                session.remoteMediaClient?.play()
                Toast.makeText(this, getString(R.string.video_playing, video.title), Toast.LENGTH_SHORT).show()
            } else {
                AppLogger.info(TAG, "Play pressed – casting video")
                val service = mediaServerService
                if (service == null || !service.isServerRunning()) {
                    AppLogger.warn(TAG, "Play pressed but media server not ready (service=$service, running=${service?.isServerRunning()})")
                    Toast.makeText(this, R.string.server_not_ready, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                checkCompatibilityAndCast(video)
            }
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

        binding.rewindButton.setOnClickListener {
            seekRelative(-SEEK_OFFSET_MS)
        }

        binding.forwardButton.setOnClickListener {
            seekRelative(SEEK_OFFSET_MS)
        }

        binding.rotateButton.setOnClickListener {
            thumbnailRotation = (thumbnailRotation + 90f) % 360f
            binding.videoThumbnail.animate()
                .rotation(thumbnailRotation)
                .setDuration(200)
                .start()
            AppLogger.info(TAG, "Thumbnail rotated to ${thumbnailRotation.toInt()}°")
        }

        binding.subtitleButton.setOnClickListener {
            val video = videoItem ?: return@setOnClickListener
            loadSubtitleTracks(video)
        }

        binding.audioTrackButton.setOnClickListener {
            val video = videoItem ?: return@setOnClickListener
            loadAudioTracks(video)
        }
    }

    private fun seekRelative(offsetMs: Long) {
        val client = castSession?.remoteMediaClient
        if (client?.hasMediaSession() == true) {
            val current = client.approximateStreamPosition
            val duration = client.streamDuration
            val target = (current + offsetMs).coerceIn(0, duration.coerceAtLeast(0))
            AppLogger.info(TAG, "Seek ${if (offsetMs > 0) "forward" else "rewind"}: ${formatDuration(current)} → ${formatDuration(target)}")
            client.seek(MediaSeekOptions.Builder().setPosition(target).build())
                .setResultCallback { result ->
                    if (!result.status.isSuccess) {
                        AppLogger.error(TAG, "Seek failed: ${result.status.statusMessage}")
                    }
                }
        } else {
            // No active cast — adjust pending seek position
            val video = videoItem ?: return
            val duration = video.duration
            pendingSeekPositionMs = (pendingSeekPositionMs + offsetMs).coerceIn(0, duration.coerceAtLeast(0))
            binding.videoSeekBar.progress = pendingSeekPositionMs.toInt()
            binding.currentTimeText.text = formatDuration(pendingSeekPositionMs)
            AppLogger.info(TAG, "Pending start position: ${formatDuration(pendingSeekPositionMs)}")
        }
    }

    private fun loadSubtitleTracks(video: VideoItem) {
        binding.progressBar.visibility = View.VISIBLE
        activityScope.launch {
            val tracks = withContext(Dispatchers.IO) {
                subtitleExtractor.getSubtitleTracks(video.path)
            }
            binding.progressBar.visibility = View.GONE
            showSubtitleDialog(video, tracks)
        }
    }

    private fun showSubtitleDialog(video: VideoItem, tracks: List<SubtitleTrack>) {
        val options = mutableListOf<String>()
        options.add(getString(R.string.subtitle_source_none))

        val embeddedStartIndex = options.size
        if (tracks.isNotEmpty()) {
            options.add(getString(R.string.subtitle_embedded_header))
            tracks.forEach { track ->
                options.add("    ${track.language} - ${track.title} (${track.codec})")
            }
        }

        options.add(getString(R.string.subtitle_source_file))
        val fileOptionIndex = options.size - 1

        val headerIndex = if (tracks.isNotEmpty()) embeddedStartIndex else -1

        AlertDialog.Builder(this)
            .setTitle(R.string.subtitle_source_title)
            .setItems(options.toTypedArray()) { _, which ->
                when {
                    which == 0 -> {
                        selectedSubtitleFile = null
                        binding.subtitleStatus.visibility = View.GONE
                        applyLiveSubtitleChange(null)
                    }
                    which == headerIndex -> {
                        // Header item tapped, ignore
                    }
                    which == fileOptionIndex -> {
                        subtitleFilePicker.launch(arrayOf(
                            "text/plain",
                            "text/vtt",
                            "application/x-subrip",
                            "text/x-ssa",
                            "text/x-ass",
                            "application/octet-stream"
                        ))
                    }
                    tracks.isNotEmpty() && which > headerIndex && which < fileOptionIndex -> {
                        val trackIndex = which - headerIndex - 1
                        val track = tracks[trackIndex]
                        extractSubtitle(video.path, track)
                    }
                }
            }
            .show()
    }

    private fun handleSubtitleFileSelected(uri: Uri) {
        binding.progressBar.visibility = View.VISIBLE
        activityScope.launch {
            val subtitleFile = withContext(Dispatchers.IO) {
                try {
                    val fileName = getFileNameFromUri(uri) ?: "subtitle.srt"
                    val inputStream = contentResolver.openInputStream(uri) ?: return@withContext null
                    val outputDir = File(cacheDir, "subtitles")
                    inputStream.use { stream ->
                        subtitleConverter.convertToVtt(stream, fileName, outputDir)
                    }
                } catch (e: Exception) {
                    AppLogger.error(TAG, "Failed to load subtitle file: ${e.message}")
                    null
                }
            }
            binding.progressBar.visibility = View.GONE
            if (subtitleFile != null) {
                selectedSubtitleFile = subtitleFile
                binding.subtitleStatus.text = getString(R.string.subtitle_file_selected)
                binding.subtitleStatus.visibility = View.VISIBLE
                AppLogger.info(TAG, "Local subtitle loaded: ${subtitleFile.name}")
                applyLiveSubtitleChange(subtitleFile)
            } else {
                Toast.makeText(this@VideoDetailActivity, R.string.subtitle_file_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    return it.getString(nameIndex)
                }
            }
        }
        return uri.lastPathSegment
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
                applyLiveSubtitleChange(subtitleFile)
            } else {
                Toast.makeText(this@VideoDetailActivity, R.string.error_subtitle, Toast.LENGTH_SHORT).show()
                AppLogger.error(TAG, "Failed to extract subtitle for track ${track.index}")
            }
        }
    }

    /**
     * Applies a subtitle change while casting is active.
     * If subtitleFile is null, disables subtitles. Otherwise, registers and activates the new track.
     */
    private fun applyLiveSubtitleChange(subtitleFile: File?) {
        val client = castSession?.remoteMediaClient ?: return
        if (!client.hasMediaSession()) return

        if (subtitleFile == null) {
            // Disable subtitles on the active cast
            client.setActiveMediaTracks(longArrayOf())
                .setResultCallback { result ->
                    if (result.status.isSuccess) {
                        AppLogger.info(TAG, "Live subtitle disabled")
                    } else {
                        AppLogger.error(TAG, "Failed to disable live subtitles: ${result.status.statusMessage}")
                    }
                }
            Toast.makeText(this, R.string.subtitle_disabled_live, Toast.LENGTH_SHORT).show()
            return
        }

        // Register subtitle file with server and reload media with the new subtitle track
        val service = mediaServerService ?: return
        val serverIp = getDeviceIpAddress()
        val serverPort = service.getServerPort()
        val subtitlePath = service.registerSubtitle(subtitleFile)
        val subtitleUrl = "http://$serverIp:$serverPort$subtitlePath"

        val currentMediaInfo = client.mediaInfo ?: return
        val currentPosition = client.approximateStreamPosition

        val newSubtitleTrack = MediaTrack.Builder(1, MediaTrack.TYPE_TEXT)
            .setName("Subtitles")
            .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
            .setContentId(subtitleUrl)
            .setContentType("text/vtt")
            .setLanguage("en")
            .build()

        val metadata = currentMediaInfo.metadata ?: MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE)

        val newMediaInfo = MediaInfo.Builder(currentMediaInfo.contentId)
            .setStreamType(currentMediaInfo.streamType)
            .setContentType(currentMediaInfo.contentType)
            .setMetadata(metadata)
            .setMediaTracks(listOf(newSubtitleTrack))
            .build()

        val loadRequest = MediaLoadRequestData.Builder()
            .setMediaInfo(newMediaInfo)
            .setAutoplay(true)
            .setCurrentTime(currentPosition)
            .setActiveTrackIds(longArrayOf(1))
            .build()

        AppLogger.info(TAG, "Reloading media with new subtitle at position ${formatDuration(currentPosition)}")
        client.load(loadRequest).setResultCallback { result ->
            if (result.status.isSuccess) {
                AppLogger.info(TAG, "Live subtitle switch: load SUCCESS")
            } else {
                AppLogger.error(TAG, "Live subtitle switch: load FAILED - ${result.status.statusMessage}")
            }
        }
        Toast.makeText(this, R.string.subtitle_switched_live, Toast.LENGTH_SHORT).show()
    }

    private fun loadAudioTracks(video: VideoItem) {
        binding.progressBar.visibility = View.VISIBLE
        activityScope.launch {
            val probeResult = cachedProbeResult ?: withContext(Dispatchers.IO) {
                mediaProber.probe(video.path)
            }
            binding.progressBar.visibility = View.GONE

            if (probeResult == null) {
                Toast.makeText(this@VideoDetailActivity, R.string.probe_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            cachedProbeResult = probeResult

            val audioTracks = probeResult.audioTracks
            if (audioTracks.isEmpty()) {
                Toast.makeText(this@VideoDetailActivity, R.string.audio_no_tracks, Toast.LENGTH_SHORT).show()
                return@launch
            }
            showAudioTrackDialog(audioTracks)
        }
    }

    private fun showAudioTrackDialog(tracks: List<AudioTrackInfo>) {
        val options = mutableListOf<String>()
        options.add(getString(R.string.audio_track_default))
        tracks.forEachIndexed { index, track ->
            options.add(formatAudioTrackLabel(index + 1, track))
        }

        val foundIndex = if (selectedAudioTrack == null) -1
            else tracks.indexOfFirst { it.trackIndex == selectedAudioTrack!!.trackIndex }
        val currentSelection = if (foundIndex >= 0) foundIndex + 1 else 0

        AlertDialog.Builder(this)
            .setTitle(R.string.audio_track_title)
            .setSingleChoiceItems(options.toTypedArray(), currentSelection) { dialog, which ->
                val previousTrack = selectedAudioTrack
                if (which == 0) {
                    selectedAudioTrack = null
                    binding.audioTrackStatus.visibility = View.GONE
                    AppLogger.info(TAG, "Audio track reset to default")
                } else {
                    val track = tracks[which - 1]
                    selectedAudioTrack = track
                    binding.audioTrackStatus.text = getString(R.string.audio_track_selected,
                        formatAudioTrackLabel(which, track))
                    binding.audioTrackStatus.visibility = View.VISIBLE
                    AppLogger.info(TAG, "Audio track selected: ${track.codec} ${track.language} (index=${track.trackIndex})")
                }
                dialog.dismiss()
                applyLiveAudioTrackChange(previousTrack)
            }
            .show()
    }

    /**
     * If casting is active, reload the stream with the newly selected audio track.
     * Saves current position and resumes from there.
     */
    private fun applyLiveAudioTrackChange(previousTrack: AudioTrackInfo?) {
        val client = castSession?.remoteMediaClient ?: return
        if (!client.hasMediaSession()) return
        val video = videoItem ?: return

        // Check if the track actually changed
        val previousIndex = previousTrack?.trackIndex
        val newIndex = selectedAudioTrack?.trackIndex
        if (previousIndex == newIndex) return

        // Save current position, then re-cast
        pendingSeekPositionMs = client.approximateStreamPosition
        AppLogger.info(TAG, "Audio track changed during cast, reloading from ${formatDuration(pendingSeekPositionMs)}")
        Toast.makeText(this, R.string.audio_track_switch_reload, Toast.LENGTH_SHORT).show()

        // Re-initiate the cast pipeline which will use the new selectedAudioTrack
        checkCompatibilityAndCast(video)
    }

    private fun formatAudioTrackLabel(number: Int, track: AudioTrackInfo): String {
        val lang = if (track.language != AudioTrackInfo.LANGUAGE_UNDETERMINED) track.language else ""
        val channels = when (track.channelCount) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> if (track.channelCount > 0) "${track.channelCount}ch" else ""
        }
        val parts = mutableListOf("Track $number: ${track.codec}")
        if (lang.isNotEmpty()) parts.add(lang)
        if (channels.isNotEmpty()) parts.add(channels)
        return parts.joinToString(" · ")
    }

    private fun needsAudioRemux(): Boolean {
        val selected = selectedAudioTrack ?: return false
        val probe = cachedProbeResult ?: return false
        val primary = probe.primaryAudio ?: return false
        return selected.trackIndex != primary.trackIndex
    }

    private fun directStreamOrRemux(video: VideoItem) {
        val probe = cachedProbeResult
        val audioTrack = selectedAudioTrack
        if (needsAudioRemux() && probe != null && audioTrack != null) {
            if (isMkvContainer(probe)) {
                // MKV + audio selection → remux to MP4 (Cast receivers may not support MKV containers)
                AppLogger.info(TAG, "MKV with audio selection, remuxing to MP4")
                remuxToMp4AndCast(video, probe, audioTrack)
            } else {
                AppLogger.info(TAG, "Using streaming MP4→MKV remux for audio selection")
                startStreamingMp4AsMkvAndCast(video, probe, audioTrack)
            }
        } else if (probe != null && isMkvContainer(probe)) {
            // MKV container → remux to MP4 for Cast compatibility
            val targetAudio = audioTrack ?: probe.primaryAudio
            if (targetAudio != null) {
                AppLogger.info(TAG, "MKV container detected, remuxing to MP4 for Cast compatibility")
                remuxToMp4AndCast(video, probe, targetAudio)
            } else {
                AppLogger.warn(TAG, "MKV with no audio track found, attempting direct cast")
                castVideo(video, selectedSubtitleFile)
            }
        } else {
            castVideo(video, selectedSubtitleFile)
        }
    }

    private fun isMkvContainer(probe: MediaProbeResult): Boolean {
        return probe.containerFormat.contains("MKV", ignoreCase = true) ||
               probe.containerFormat.contains("Matroska", ignoreCase = true) ||
               probe.containerFormat.contains("WebM", ignoreCase = true)
    }

    /**
     * Remuxes an MKV file to MP4 for Cast compatibility.
     * Video is passed through (no re-encoding). Audio is either passed through
     * (if AAC) or transcoded to AAC (for AC-3, Vorbis, etc.).
     */
    private fun remuxToMp4AndCast(video: VideoItem, probe: MediaProbeResult, audioTrack: AudioTrackInfo) {
        val transcoder = VideoTranscoder()
        videoTranscoder = transcoder

        val useAudioTranscode = !VideoTranscoder.canRemuxAudio(audioTrack)

        AppLogger.info(TAG, "Remuxing MKV→MP4: audio=${audioTrack.codec} ${audioTrack.language}, " +
            "audioTranscode=$useAudioTranscode")

        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.remuxing_title)
            .setMessage(getString(R.string.remuxing_progress, 0))
            .setNegativeButton(R.string.cancel) { _, _ ->
                transcoder.cancel()
            }
            .setCancelable(false)
            .show()

        activityScope.launch {
            withContext(Dispatchers.IO) {
                val outputDir = File(cacheDir, "remux")
                if (!outputDir.exists() && !outputDir.mkdirs()) {
                    runOnUiThread {
                        progressDialog.dismiss()
                        Toast.makeText(
                            this@VideoDetailActivity,
                            getString(R.string.remux_failed, "Cannot create output directory"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@withContext
                }

                val listener = object : VideoTranscoder.ProgressListener {
                    override fun onProgress(percent: Int) {
                        runOnUiThread {
                            progressDialog.setMessage(getString(R.string.remuxing_progress, percent))
                        }
                    }

                    override fun onCompleted(outputFile: File) {
                        runOnUiThread {
                            progressDialog.dismiss()
                            transcodedFile?.delete()
                            transcodedFile = outputFile
                            AppLogger.info(TAG, "MKV→MP4 remux complete: ${outputFile.name}, ${outputFile.length()} bytes")
                            castTranscodedVideo(video, outputFile)
                        }
                    }

                    override fun onError(error: String) {
                        runOnUiThread {
                            progressDialog.dismiss()
                            AppLogger.error(TAG, "MKV→MP4 remux error: $error")
                            Toast.makeText(
                                this@VideoDetailActivity,
                                getString(R.string.remux_failed, error),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }

                if (useAudioTranscode) {
                    transcoder.remuxWithAudioTranscode(video.path, outputDir, probe, audioTrack, listener)
                } else {
                    transcoder.remux(video.path, outputDir, probe, audioTrack, listener)
                }
            }
        }
    }

    private fun startStreamingMkvFilterAndCast(video: VideoItem, probe: MediaProbeResult, audioTrack: AudioTrackInfo) {
        // MKV track numbers are 1-based (MediaExtractor index + 1)
        val videoTrack = probe.primaryVideo
        if (videoTrack == null) {
            AppLogger.warn(TAG, "No video track found, falling back to direct cast")
            castVideo(video, selectedSubtitleFile)
            return
        }
        val videoTrackNum = videoTrack.trackIndex + 1
        val audioTrackNum = audioTrack.trackIndex + 1
        val keepTrackNumbers = setOf(videoTrackNum, audioTrackNum)

        AppLogger.info(TAG, "MKV filter: keeping track numbers $keepTrackNumbers " +
            "(video=${probe.primaryVideo?.codec}, audio=${audioTrack.codec} ${audioTrack.language})")

        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.remuxing_title)
            .setMessage(getString(R.string.loading_video))
            .setCancelable(false)
            .show()

        val filter = MkvTrackFilter()
        val videoPath = video.path
        val videoUri = video.uri

        activityScope.launch {
            try {
                val outputFile = withContext(Dispatchers.IO) {
                    val outputDir = File(cacheDir, "mkvfilter")
                    if (!outputDir.exists() && !outputDir.mkdirs()) {
                        throw IOException("Cannot create output directory")
                    }
                    val tempFile = File(outputDir, "filtered_${System.currentTimeMillis()}.mkv")

                    val sourceStream = if (videoUri != null) {
                        try {
                            val pfd = contentResolver.openFileDescriptor(videoUri, "r")
                            if (pfd != null) {
                                android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
                            } else {
                                java.io.FileInputStream(videoPath)
                            }
                        } catch (e: Exception) {
                            AppLogger.warn(TAG, "ContentResolver failed, falling back to FileInputStream: ${e.message}")
                            java.io.FileInputStream(videoPath)
                        }
                    } else {
                        java.io.FileInputStream(videoPath)
                    }

                    try {
                        java.io.FileOutputStream(tempFile).use { fos ->
                            filter.filter(sourceStream, fos, keepTrackNumbers)
                        }
                    } finally {
                        try { sourceStream.close() } catch (_: Exception) {}
                    }

                    AppLogger.info(TAG, "MKV filter complete: ${tempFile.name}, ${tempFile.length()} bytes")
                    tempFile
                }

                progressDialog.dismiss()
                transcodedFile?.delete()
                transcodedFile = outputFile
                castTranscodedVideo(video, outputFile, "video/x-matroska")
            } catch (e: Exception) {
                AppLogger.error(TAG, "MKV filter failed: ${e.message}")
                progressDialog.dismiss()
                Toast.makeText(this@VideoDetailActivity, getString(R.string.remux_failed, e.message ?: "Unknown error"), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startStreamingMp4AsMkvAndCast(video: VideoItem, probe: MediaProbeResult, audioTrack: AudioTrackInfo) {
        val videoTrack = probe.primaryVideo
        if (videoTrack == null) {
            AppLogger.warn(TAG, "No video track found, falling back to direct cast")
            castVideo(video, selectedSubtitleFile)
            return
        }

        AppLogger.info(TAG, "MP4→MKV remux: video=${videoTrack.codec} (track ${videoTrack.trackIndex}), " +
            "audio=${audioTrack.codec} ${audioTrack.language} (track ${audioTrack.trackIndex})")

        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.remuxing_title)
            .setMessage(getString(R.string.loading_video))
            .setCancelable(false)
            .show()

        val streamer = Mp4ToMkvStreamer()
        val videoPath = video.path

        activityScope.launch {
            try {
                val outputFile = withContext(Dispatchers.IO) {
                    val outputDir = File(cacheDir, "mkvfilter")
                    if (!outputDir.exists() && !outputDir.mkdirs()) {
                        throw IOException("Cannot create output directory")
                    }
                    val tempFile = File(outputDir, "remuxed_${System.currentTimeMillis()}.mkv")

                    java.io.FileOutputStream(tempFile).use { fos ->
                        streamer.writeTo(videoPath, videoTrack.trackIndex, audioTrack.trackIndex, fos)
                    }

                    AppLogger.info(TAG, "MP4→MKV remux complete: ${tempFile.name}, ${tempFile.length()} bytes")
                    tempFile
                }

                progressDialog.dismiss()
                transcodedFile?.delete()
                transcodedFile = outputFile
                castTranscodedVideo(video, outputFile, "video/x-matroska")
            } catch (e: Exception) {
                AppLogger.error(TAG, "MP4→MKV remux failed: ${e.message}")
                progressDialog.dismiss()
                Toast.makeText(this@VideoDetailActivity, getString(R.string.remux_failed, e.message ?: "Unknown error"), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkCompatibilityAndCast(video: VideoItem) {
        binding.progressBar.visibility = View.VISIBLE
        activityScope.launch {
            val probeResult = cachedProbeResult ?: withContext(Dispatchers.IO) {
                mediaProber.probe(video.path)
            }
            binding.progressBar.visibility = View.GONE

            if (probeResult == null) {
                AppLogger.warn(TAG, "Media probe failed, casting directly")
                Toast.makeText(this@VideoDetailActivity, R.string.probe_failed, Toast.LENGTH_SHORT).show()
                castVideo(video, selectedSubtitleFile)
                return@launch
            }
            cachedProbeResult = probeResult

            val result = castCompatibility.checkCompatibility(probeResult)
            if (result.isFullyCompatible) {
                AppLogger.info(TAG, "All codecs compatible, casting directly")
                directStreamOrRemux(video)
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
                directStreamOrRemux(video)
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
                    },
                    selectedAudioTrack = selectedAudioTrack
                )
            }
        }
    }

    private fun startRemuxAndCast(video: VideoItem, probeResult: MediaProbeResult) {
        val audioTrack = selectedAudioTrack ?: return
        val transcoder = VideoTranscoder()
        videoTranscoder = transcoder

        AppLogger.info(TAG, "Remuxing to select audio track: ${audioTrack.codec} ${audioTrack.language} (index=${audioTrack.trackIndex})")

        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.remuxing_title)
            .setMessage(getString(R.string.remuxing_progress, 0))
            .setNegativeButton(R.string.cancel) { _, _ ->
                transcoder.cancel()
            }
            .setCancelable(false)
            .show()

        activityScope.launch {
            withContext(Dispatchers.IO) {
                val outputDir = File(cacheDir, "remux")
                if (!outputDir.exists() && !outputDir.mkdirs()) {
                    runOnUiThread {
                        progressDialog.dismiss()
                        Toast.makeText(
                            this@VideoDetailActivity,
                            getString(R.string.remux_failed, "Cannot create output directory"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@withContext
                }

                transcoder.remux(video.path, outputDir, probeResult, audioTrack,
                    object : VideoTranscoder.ProgressListener {
                        override fun onProgress(percent: Int) {
                            runOnUiThread {
                                progressDialog.setMessage(getString(R.string.remuxing_progress, percent))
                            }
                        }

                        override fun onCompleted(outputFile: File) {
                            runOnUiThread {
                                progressDialog.dismiss()
                                transcodedFile = outputFile
                                AppLogger.info(TAG, "Remux complete: ${outputFile.name}, ${outputFile.length()} bytes")
                                castTranscodedVideo(video, outputFile)
                            }
                        }

                        override fun onError(error: String) {
                            runOnUiThread {
                                progressDialog.dismiss()
                                AppLogger.error(TAG, "Remux error: $error")
                                Toast.makeText(
                                    this@VideoDetailActivity,
                                    getString(R.string.remux_failed, error),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                )
            }
        }
    }

    private fun startRemuxWithAudioTranscodeAndCast(video: VideoItem, probeResult: MediaProbeResult) {
        val audioTrack = selectedAudioTrack ?: return
        val transcoder = VideoTranscoder()
        videoTranscoder = transcoder

        AppLogger.info(TAG, "Remuxing with audio transcode: ${audioTrack.codec} ${audioTrack.language} (index=${audioTrack.trackIndex})")

        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.remuxing_title)
            .setMessage(getString(R.string.remuxing_progress, 0))
            .setNegativeButton(R.string.cancel) { _, _ ->
                transcoder.cancel()
            }
            .setCancelable(false)
            .show()

        activityScope.launch {
            withContext(Dispatchers.IO) {
                val outputDir = File(cacheDir, "remux")
                if (!outputDir.exists() && !outputDir.mkdirs()) {
                    runOnUiThread {
                        progressDialog.dismiss()
                        Toast.makeText(
                            this@VideoDetailActivity,
                            getString(R.string.remux_failed, "Cannot create output directory"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@withContext
                }

                transcoder.remuxWithAudioTranscode(video.path, outputDir, probeResult, audioTrack,
                    object : VideoTranscoder.ProgressListener {
                        override fun onProgress(percent: Int) {
                            runOnUiThread {
                                progressDialog.setMessage(getString(R.string.remuxing_progress, percent))
                            }
                        }

                        override fun onCompleted(outputFile: File) {
                            runOnUiThread {
                                progressDialog.dismiss()
                                transcodedFile = outputFile
                                AppLogger.info(TAG, "Remux with audio transcode complete: ${outputFile.name}, ${outputFile.length()} bytes")
                                castTranscodedVideo(video, outputFile)
                            }
                        }

                        override fun onError(error: String) {
                            runOnUiThread {
                                progressDialog.dismiss()
                                AppLogger.error(TAG, "Remux with audio transcode error: $error")
                                Toast.makeText(
                                    this@VideoDetailActivity,
                                    getString(R.string.remux_failed, error),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                )
            }
        }
    }

    private fun castTranscodedVideo(originalVideo: VideoItem, transcodedFile: File, contentType: String = "video/mp4") {
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

        val videoPath = service.registerFile(transcodedFile.absolutePath, contentType, null)
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
            .setContentType(contentType)
            .setMetadata(metadata)
            .apply {
                if (mediaTracks.isNotEmpty()) {
                    setMediaTracks(mediaTracks)
                }
            }
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

        AppLogger.info(TAG, "castTranscodedVideo: sending load request (startPosition=${formatDuration(pendingSeekPositionMs)}, contentType=${contentType})")
        AppLogger.info(TAG, "castTranscodedVideo: mediaInfo contentId=$videoUrl")
        AppLogger.info(TAG, "castTranscodedVideo: device=${session.castDevice?.friendlyName}, model=${session.castDevice?.modelName}")
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
                AppLogger.error(TAG, "castTranscodedVideo: load FAILED - statusCode=${status.statusCode}, statusMessage=${status.statusMessage}")
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
            .apply {
                if (mediaTracks.isNotEmpty()) {
                    setMediaTracks(mediaTracks)
                }
            }
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

        AppLogger.info(TAG, "castVideo: sending load request to cast device (startPosition=${formatDuration(startPositionMs)}, streamType=BUFFERED, contentType=${video.mimeType})")
        AppLogger.info(TAG, "castVideo: mediaInfo contentId=$videoUrl")
        AppLogger.info(TAG, "castVideo: device=${session.castDevice?.friendlyName}, model=${session.castDevice?.modelName}")
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

    private fun isMediaActive(playerState: Int?): Boolean {
        return playerState == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PLAYING ||
                playerState == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PAUSED ||
                playerState == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_BUFFERING
    }

    private fun updateProgressTracking(playerState: Int) {
        if (isMediaActive(playerState)) {
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
