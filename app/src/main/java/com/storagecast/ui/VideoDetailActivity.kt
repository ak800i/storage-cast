package com.storagecast.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
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
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.TextTrackStyle
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
import com.storagecast.media.StreamingDecision
import com.storagecast.media.ReceiverCapabilityStore
import com.storagecast.media.MediaProber
import com.storagecast.media.HlsTranscodeSession
import com.storagecast.media.TranscodeStreamer
import com.storagecast.media.VideoTranscoder
import com.storagecast.model.SubtitleTrack
import com.storagecast.model.VideoItem
import com.storagecast.server.MediaServerService
import com.storagecast.subtitle.OpenSubtitlesClient
import com.storagecast.subtitle.OpenSubtitlesHash
import com.storagecast.subtitle.GestdownClient
import com.storagecast.subtitle.SubtitleConverter
import com.storagecast.subtitle.SubtitleExtractor
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

class VideoDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO = "extra_video"
        private const val TAG = "VideoDetail"
        private const val SEEK_OFFSET_MS = 30_000L
        private const val OPENSUBTITLES_PREFS = "opensubtitles"
        private const val NORMAL_PLAYBACK_RATE = 1.0
        private const val NO_LOADING_ITEM = 0
        private const val SUBTITLE_APPLY_DEBOUNCE_MS = 800L
    }

    private lateinit var binding: ActivityVideoDetailBinding
    private var videoItem: VideoItem? = null

    private var castContext: CastContext? = null
    private var castSession: CastSession? = null
    private var mediaServerService: MediaServerService? = null
    private var serviceBound = false

    private val subtitleExtractor = SubtitleExtractor()
    private val subtitleConverter = SubtitleConverter()
    private var selectedSubtitleFile: File? = null
    private var downloadedSubtitleFile: File? = null
    private var subtitleSyncOffsetMs = 0L
    private var subtitleApplyJob: Job? = null
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var selectedAudioTrack: AudioTrackInfo? = null
    private var cachedProbeResult: MediaProbeResult? = null

    // ── Reactive receiver capability learning ──
    // The Cast SDK can't tell us up front which codecs a receiver decodes, so we learn it:
    // when a direct play errors before it ever reaches PLAYING, we record the source codecs
    // as unsupported for that receiver and automatically re-cast as a transcode. pendingDirect
    // holds the in-flight direct attempt awaiting confirmation that it actually plays.
    private val receiverCaps by lazy { ReceiverCapabilityStore(this) }
    private data class DirectAttempt(
        val deviceId: String?,
        val video: VideoItem,
        val probe: MediaProbeResult,
        val videoMime: String,
        val audioMime: String
    )
    private var pendingDirectAttempt: DirectAttempt? = null

    private val subtitleFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            handleSubtitleFileSelected(uri)
        }
    }

    private val saveSubtitleLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        if (uri != null) {
            saveSubtitleToUri(uri)
        }
    }

    private val mediaProber = MediaProber()
    private val castCompatibility = CastCompatibility()
    private var videoTranscoder: VideoTranscoder? = null
    private var transcodeStreamer: TranscodeStreamer? = null
    private var transcodedFile: File? = null

    private val progressHandler = Handler(Looper.getMainLooper())
    private var isSeekBarDragging = false
    private var pendingSeekPositionMs = 0L

    // ── Realtime-transcode (live) session seek state ──
    // The transcode is served as a single STREAM_TYPE_LIVE pipe, so the Cast
    // receiver's reported position always restarts at 0 for each stream and
    // calling remoteMediaClient.seek() on it crashes the receiver. Instead we
    // seek by restarting the transcode from a new source position. transcodeBaseMs
    // is the source offset the current live transcode began from, so the real
    // playback position shown to the user is transcodeBaseMs + streamPosition.
    private var isTranscodeSession = false
    private var transcodeBaseMs = 0L
    private var transcodeVideo: VideoItem? = null
    private var transcodeProbe: MediaProbeResult? = null
    // True for any STREAM_TYPE_LIVE streaming source (transcode or live remux). The
    // Cast receiver cannot seek these, so seek() must never be called on them.
    private var isLiveStreamSession = false
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
                MediaStatus.PLAYER_STATE_IDLE -> "IDLE"
                MediaStatus.PLAYER_STATE_PLAYING -> "PLAYING"
                MediaStatus.PLAYER_STATE_PAUSED -> "PAUSED"
                MediaStatus.PLAYER_STATE_BUFFERING -> "BUFFERING"
                MediaStatus.PLAYER_STATE_LOADING -> "LOADING"
                else -> "UNKNOWN($playerState)"
            }
            val idleReasonName = when (status.idleReason) {
                MediaStatus.IDLE_REASON_NONE -> "NONE"
                MediaStatus.IDLE_REASON_FINISHED -> "FINISHED"
                MediaStatus.IDLE_REASON_CANCELED -> "CANCELED"
                MediaStatus.IDLE_REASON_INTERRUPTED -> "INTERRUPTED"
                MediaStatus.IDLE_REASON_ERROR -> "ERROR"
                else -> "UNKNOWN(${status.idleReason})"
            }
            AppLogger.info(TAG, "Cast receiver status: playerState=$playerStateName, idleReason=$idleReasonName")
            AppLogger.info(TAG, "  streamPosition=${status.streamPosition}, streamDuration=${client.streamDuration}, volume=${status.streamVolume}, muted=${status.isMute}")
            val mediaInfo = status.mediaInfo
            if (mediaInfo != null) {
                AppLogger.info(TAG, "  receiver mediaInfo: contentId=${mediaInfo.contentId}, contentType=${mediaInfo.contentType}, streamDuration=${mediaInfo.streamDuration}")
            }
            val playbackRate = status.playbackRate
            val activeTrackIds = status.activeTrackIds
            val loadingItemId = status.loadingItemId
            if (playbackRate != NORMAL_PLAYBACK_RATE || activeTrackIds != null || loadingItemId != NO_LOADING_ITEM) {
                AppLogger.info(TAG, "  playbackRate=$playbackRate, activeTrackIds=${activeTrackIds?.toList()}, loadingItemId=$loadingItemId")
            }
            val customData = status.customData
            if (customData != null) {
                AppLogger.info(TAG, "  receiver customData: $customData")
            }
            updateProgressTracking(playerState)
            if (playerState == MediaStatus.PLAYER_STATE_PLAYING && pendingDirectAttempt != null) {
                AppLogger.info(TAG, "Direct play confirmed playing; no capability escalation needed")
                pendingDirectAttempt = null
            }
            if (playerState == MediaStatus.PLAYER_STATE_IDLE) {
                when (status.idleReason) {
                    MediaStatus.IDLE_REASON_ERROR -> {
                        val contentId = mediaInfo?.contentId ?: "unknown"
                        val contentType = mediaInfo?.contentType ?: "unknown"
                        val streamType = mediaInfo?.streamType ?: -1
                        val streamTypeName = when (streamType) {
                            MediaInfo.STREAM_TYPE_BUFFERED -> "BUFFERED"
                            MediaInfo.STREAM_TYPE_LIVE -> "LIVE"
                            MediaInfo.STREAM_TYPE_NONE -> "NONE"
                            else -> "UNKNOWN($streamType)"
                        }
                        AppLogger.error(TAG, "Cast playback error: contentId=$contentId, contentType=$contentType, streamType=$streamTypeName, customData=$customData")
                        AppLogger.error(TAG, "  Cast device: ${castSession?.castDevice?.friendlyName ?: "unknown"}, model=${castSession?.castDevice?.modelName ?: "unknown"}")
                        AppLogger.error(TAG, "  Media server running: ${mediaServerService?.isServerRunning()}")
                        if (mediaInfo != null) {
                            AppLogger.error(TAG, "  MediaInfo streamDuration=${mediaInfo.streamDuration}, mediaTracks=${mediaInfo.mediaTracks?.size ?: 0}")
                        }
                        val appStatus = castSession?.applicationStatus
                        if (appStatus != null) {
                            AppLogger.error(TAG, "  Receiver app status: $appStatus")
                        }
                        try {
                            val statusJson = status.toJson()
                            val extStatus = statusJson.optJSONObject("extendedStatus")
                            if (extStatus != null) {
                                AppLogger.error(TAG, "  Receiver extendedStatus: $extStatus")
                                val mediaErrorJson = extStatus.optJSONObject("mediaError")
                                if (mediaErrorJson != null) {
                                    AppLogger.error(TAG, "  Receiver mediaError: $mediaErrorJson")
                                }
                            }
                        } catch (e: Exception) {
                            AppLogger.warn(TAG, "  Failed to parse MediaStatus JSON: ${e.message}")
                        }
                        maybeLearnAndEscalate(streamType)
                    }
                    MediaStatus.IDLE_REASON_CANCELED ->
                        AppLogger.warn(TAG, "Cast playback canceled")
                    MediaStatus.IDLE_REASON_INTERRUPTED ->
                        AppLogger.warn(TAG, "Cast playback interrupted")
                    MediaStatus.IDLE_REASON_FINISHED ->
                        AppLogger.info(TAG, "Cast playback finished")
                }
            }
        }

        override fun onMetadataUpdated() {
            val client = castSession?.remoteMediaClient
            val mediaInfo = client?.mediaInfo
            if (mediaInfo != null) {
                val title = mediaInfo.metadata?.getString(MediaMetadata.KEY_TITLE)
                AppLogger.info(TAG, "Cast receiver metadata updated: title=$title, contentType=${mediaInfo.contentType}")
            } else {
                AppLogger.info(TAG, "Cast receiver metadata updated")
            }
        }

        override fun onQueueStatusUpdated() {
            val client = castSession?.remoteMediaClient
            val status = client?.mediaStatus
            val queueItemCount = status?.queueItemCount ?: 0
            AppLogger.info(TAG, "Cast receiver queue status updated: queueItemCount=$queueItemCount")
        }

        override fun onPreloadStatusUpdated() {
            AppLogger.info(TAG, "Cast receiver preload status updated")
        }

        override fun onSendingRemoteMediaRequest() {
            AppLogger.info(TAG, "Sending request to Cast receiver")
        }

        override fun onMediaError(mediaError: MediaError) {
            logMediaError(mediaError)
        }
    }

    private val castListener = object : Cast.Listener() {
        override fun onApplicationStatusChanged() {
            val status = castSession?.applicationStatus
            AppLogger.info(TAG, "Cast receiver app status changed: $status")
        }

        override fun onApplicationMetadataChanged(metadata: com.google.android.gms.cast.ApplicationMetadata?) {
            val appName = metadata?.name
            val appId = metadata?.applicationId
            val namespaces = metadata?.supportedNamespaces
            AppLogger.info(TAG, "Cast receiver app metadata changed: name=$appName, appId=$appId, namespaces=$namespaces")
        }

        override fun onVolumeChanged() {
            AppLogger.info(TAG, "Cast receiver volume changed: volume=${castSession?.volume}, muted=${castSession?.isMute}")
        }

        override fun onActiveInputStateChanged(activeInputState: Int) {
            val stateName = when (activeInputState) {
                Cast.ACTIVE_INPUT_STATE_YES -> "ACTIVE"
                Cast.ACTIVE_INPUT_STATE_NO -> "INACTIVE"
                Cast.ACTIVE_INPUT_STATE_UNKNOWN -> "UNKNOWN"
                else -> "UNRECOGNIZED($activeInputState)"
            }
            AppLogger.info(TAG, "Cast receiver active input state changed: $stateName")
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
        AppLogger.error(TAG, "Cast session failure: error=$error, ${describeCastStatusCode(error)}")
        session.remoteMediaClient?.unregisterCallback(remoteMediaClientCallback)
        session.removeCastListener(castListener)
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
            logCastDeviceInfo(session)
            castSession = session
            session.remoteMediaClient?.registerCallback(remoteMediaClientCallback)
            session.addCastListener(castListener)
            updateCastStatus()
        }
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            AppLogger.error(TAG, "Cast session start failed: error=$error, ${describeCastStatusCode(error)}")
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
            session.removeCastListener(castListener)
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
            logCastDeviceInfo(session)
            castSession = session
            session.remoteMediaClient?.registerCallback(remoteMediaClientCallback)
            session.addCastListener(castListener)
            updateCastStatus()
        }
        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            AppLogger.error(TAG, "Cast session resume failed: error=$error, ${describeCastStatusCode(error)}")
            handleCastSessionFailure(session, error)
        }
        override fun onSessionSuspended(session: CastSession, reason: Int) {
            AppLogger.info(TAG, "Cast session suspended: reason=$reason")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityVideoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

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
        autoLoadSidecarSubtitle()
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
        binding.videoThumbnail.setImageResource(R.drawable.ic_video_placeholder)
        binding.videoThumbnail.scaleType = android.widget.ImageView.ScaleType.CENTER
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
                binding.videoThumbnail.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            }
        } catch (e: Exception) {
            // Thumbnail not available, placeholder already set
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


        binding.subtitleButton.setOnClickListener {
            val video = videoItem ?: return@setOnClickListener
            loadSubtitleTracks(video)
        }

        binding.audioTrackButton.setOnClickListener {
            val video = videoItem ?: return@setOnClickListener
            loadAudioTracks(video)
        }

        binding.subtitleOffsetButton.setOnClickListener { showSubtitleOffsetDialog() }
    }

    private fun seekRelative(offsetMs: Long) {
        val client = castSession?.remoteMediaClient
        if (client?.hasMediaSession() == true && isMediaActive(client.mediaStatus?.playerState)) {
            // Live transcode: seeking restarts the transcode from the new position,
            // because the receiver cannot seek a STREAM_TYPE_LIVE pipe (it crashes).
            if (isTranscodeSession) {
                seekTranscodeTo(currentLivePositionMs() + offsetMs)
                return
            }
            // Other live streams can't be seeked at all — ignore rather than crash.
            if (isLiveStreamSession) {
                Toast.makeText(this, R.string.seek_not_available, Toast.LENGTH_SHORT).show()
                return
            }
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

    /** The real playback position of a live transcode = source base + receiver position. */
    private fun currentLivePositionMs(): Long {
        val client = castSession?.remoteMediaClient ?: return transcodeBaseMs
        val streamPos = client.approximateStreamPosition.coerceAtLeast(0)
        return transcodeBaseMs + streamPos
    }

    /**
     * Seeks the live transcode by restarting it from [targetMs] of the source. The
     * receiver loads a fresh stream (which begins at receiver-position 0) and we track
     * the source offset in [transcodeBaseMs] so the UI shows the true position.
     */
    private fun seekTranscodeTo(targetMs: Long) {
        val video = transcodeVideo
        val probe = transcodeProbe
        if (video == null || probe == null) {
            AppLogger.warn(TAG, "seekTranscodeTo: no cached transcode source, ignoring")
            return
        }
        val duration = video.duration.coerceAtLeast(0)
        val clamped = if (duration > 0) targetMs.coerceIn(0, duration) else targetMs.coerceAtLeast(0)
        AppLogger.info(TAG, "Transcode seek → restarting from ${formatDuration(clamped)}")
        Toast.makeText(this, getString(R.string.seeking_to, formatDuration(clamped)), Toast.LENGTH_SHORT).show()
        // Reflect the target immediately so the bar doesn't snap back to the live edge.
        binding.videoSeekBar.progress = clamped.toInt()
        binding.currentTimeText.text = formatDuration(clamped)
        startTranscoding(video, probe, clamped)
    }

    private fun adjustSubtitleSync(deltaMs: Long) {
        val baseVtt = selectedSubtitleFile ?: return
        subtitleSyncOffsetMs += deltaMs
        updateSubtitleSyncUi()

        // Debounce: cancel any pending apply and schedule a new one so rapid
        // button presses only trigger a single Cast reload after a short pause.
        subtitleApplyJob?.cancel()
        subtitleApplyJob = activityScope.launch {
            delay(SUBTITLE_APPLY_DEBOUNCE_MS)
            val effectiveFile = if (subtitleSyncOffsetMs == 0L) {
                baseVtt
            } else {
                withContext(Dispatchers.IO) {
                    val outputFile = File(cacheDir, "subtitles/offset_subtitle.vtt")
                    subtitleConverter.applySubtitleOffset(baseVtt, subtitleSyncOffsetMs, outputFile)
                } ?: return@launch
            }

            // The Cast Default Media Receiver keeps subtitle data in memory
            // after load(). Toggling setActiveMediaTracks off/on does NOT cause
            // it to re-fetch the VTT. The only reliable way to update subtitle
            // content is a full media reload at the current position.
            applyLiveSubtitleChange(effectiveFile)
        }
    }

    /**
     * Returns the effective subtitle file for casting, applying the current
     * sync offset if non-zero.
     */
    /**
     * The subtitle to send, with timestamps shifted by the manual sync offset plus
     * [extraOffsetMs]. For the live transcode, [extraOffsetMs] is `-transcodeBaseMs` so the
     * 0-based stream (which starts at the seek position) lines up with the absolute-timed
     * subtitle; cues before the start collapse to 0 and don't display.
     */
    private fun getEffectiveSubtitleFile(extraOffsetMs: Long = 0L): File? {
        val base = selectedSubtitleFile ?: return null
        val totalOffsetMs = subtitleSyncOffsetMs + extraOffsetMs
        if (totalOffsetMs == 0L) return base
        val outputFile = File(cacheDir, "subtitles/offset_subtitle.vtt")
        return subtitleConverter.applySubtitleOffset(base, totalOffsetMs, outputFile) ?: base
    }

    private fun updateSubtitleSyncUi() {
        binding.subtitleOffsetButton.visibility =
            if (selectedSubtitleFile != null) View.VISIBLE else View.GONE
    }

    private fun showSubtitleOffsetDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_subtitle_offset, null)
        val offsetValue = dialogView.findViewById<android.widget.TextView>(R.id.subtitleOffsetValue)
        val updateDisplay = {
            val seconds = subtitleSyncOffsetMs / 1000.0
            offsetValue.text = getString(R.string.subtitle_sync_status, seconds)
        }
        updateDisplay()

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.subtitle_offset_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .create()

        dialogView.findViewById<View>(R.id.offsetMinus1s).setOnClickListener {
            adjustSubtitleSync(-1000L)
            updateDisplay()
        }
        dialogView.findViewById<View>(R.id.offsetMinus01s).setOnClickListener {
            adjustSubtitleSync(-100L)
            updateDisplay()
        }
        dialogView.findViewById<View>(R.id.offsetPlus01s).setOnClickListener {
            adjustSubtitleSync(100L)
            updateDisplay()
        }
        dialogView.findViewById<View>(R.id.offsetPlus1s).setOnClickListener {
            adjustSubtitleSync(1000L)
            updateDisplay()
        }
        dialogView.findViewById<View>(R.id.offsetReset).setOnClickListener {
            if (subtitleSyncOffsetMs != 0L) {
                adjustSubtitleSync(-subtitleSyncOffsetMs)
                updateDisplay()
            }
        }

        dialog.show()
    }

    private fun autoLoadSidecarSubtitle() {
        val video = videoItem ?: return
        activityScope.launch {
            val sidecarFile = withContext(Dispatchers.IO) {
                val sidecars = subtitleExtractor.findSidecarSubtitles(video.path)
                if (sidecars.isEmpty()) return@withContext null
                val file = sidecars.first()
                try {
                    val outputDir = File(cacheDir, "subtitles")
                    file.inputStream().use { stream ->
                        subtitleConverter.convertToVtt(stream, file.name, outputDir)
                    }
                } catch (e: Exception) {
                    AppLogger.error(TAG, "Failed to auto-load sidecar subtitle: ${e.message}")
                    null
                }
            }
            if (sidecarFile != null) {
                selectedSubtitleFile = sidecarFile
                binding.subtitleStatus.text = getString(R.string.subtitle_sidecar_loaded)
                binding.subtitleStatus.visibility = View.VISIBLE
                subtitleSyncOffsetMs = 0L
                updateSubtitleSyncUi()
                AppLogger.info(TAG, "Auto-loaded sidecar subtitle: ${sidecarFile.name}")
            }
        }
    }

    private fun loadSubtitleTracks(video: VideoItem) {
        binding.progressBar.visibility = View.VISIBLE
        activityScope.launch {
            val (tracks, sidecars) = withContext(Dispatchers.IO) {
                Pair(
                    subtitleExtractor.getSubtitleTracks(video.path),
                    subtitleExtractor.findSidecarSubtitles(video.path)
                )
            }
            binding.progressBar.visibility = View.GONE
            showSubtitleDialog(video, tracks, sidecars)
        }
    }

    private fun showSubtitleDialog(video: VideoItem, tracks: List<SubtitleTrack>, sidecars: List<File> = emptyList()) {
        val options = mutableListOf<String>()
        options.add(getString(R.string.subtitle_source_none))

        val sidecarStartIndex = options.size
        if (sidecars.isNotEmpty()) {
            options.add(getString(R.string.subtitle_sidecar_header))
            sidecars.forEach { file ->
                options.add("    ${file.name}")
            }
        }
        val sidecarHeaderIndex = if (sidecars.isNotEmpty()) sidecarStartIndex else -1

        val embeddedStartIndex = options.size
        if (tracks.isNotEmpty()) {
            options.add(getString(R.string.subtitle_embedded_header))
            tracks.forEach { track ->
                options.add("    ${track.language} - ${track.title} (${track.codec})")
            }
        }

        options.add(getString(R.string.subtitle_source_file))
        val fileOptionIndex = options.size - 1

        options.add(getString(R.string.subtitle_source_opensubtitles))
        val openSubtitlesIndex = options.size - 1

        options.add(getString(R.string.subtitle_source_gestdown))
        val gestdownIndex = options.size - 1

        val embeddedHeaderIndex = if (tracks.isNotEmpty()) embeddedStartIndex else -1

        AlertDialog.Builder(this)
            .setTitle(R.string.subtitle_source_title)
            .setItems(options.toTypedArray()) { _, which ->
                when {
                    which == 0 -> {
                        selectedSubtitleFile = null
                        downloadedSubtitleFile = null
                        subtitleSyncOffsetMs = 0L
                        binding.subtitleStatus.visibility = View.GONE
                        updateSubtitleSyncUi()
                        applyLiveSubtitleChange(null)
                        invalidateOptionsMenu()
                    }
                    which == sidecarHeaderIndex || which == embeddedHeaderIndex -> {
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
                    which == openSubtitlesIndex -> {
                        searchOpenSubtitles(video)
                    }
                    which == gestdownIndex -> {
                        searchGestdown(video)
                    }
                    sidecars.isNotEmpty() && which > sidecarHeaderIndex && which < embeddedStartIndex -> {
                        val sidecarIndex = which - sidecarHeaderIndex - 1
                        loadSidecarSubtitle(sidecars[sidecarIndex])
                    }
                    tracks.isNotEmpty() && which > embeddedHeaderIndex && which < fileOptionIndex -> {
                        val trackIndex = which - embeddedHeaderIndex - 1
                        val track = tracks[trackIndex]
                        extractSubtitle(video.path, track)
                    }
                }
            }
            .show()
    }

    private fun loadSidecarSubtitle(file: File) {
        binding.progressBar.visibility = View.VISIBLE
        activityScope.launch {
            val subtitleFile = withContext(Dispatchers.IO) {
                try {
                    val outputDir = File(cacheDir, "subtitles")
                    file.inputStream().use { stream ->
                        subtitleConverter.convertToVtt(stream, file.name, outputDir)
                    }
                } catch (e: Exception) {
                    AppLogger.error(TAG, "Failed to load sidecar subtitle: ${e.message}")
                    null
                }
            }
            binding.progressBar.visibility = View.GONE
            if (subtitleFile != null) {
                selectedSubtitleFile = subtitleFile
                binding.subtitleStatus.text = getString(R.string.subtitle_sidecar_selected, file.name)
                binding.subtitleStatus.visibility = View.VISIBLE
                subtitleSyncOffsetMs = 0L
                updateSubtitleSyncUi()
                AppLogger.info(TAG, "Sidecar subtitle loaded: ${file.name}")
                applyLiveSubtitleChange(subtitleFile)
            } else {
                Toast.makeText(this@VideoDetailActivity, R.string.subtitle_file_error, Toast.LENGTH_SHORT).show()
            }
        }
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
                downloadedSubtitleFile = null
                binding.subtitleStatus.text = getString(R.string.subtitle_file_selected)
                binding.subtitleStatus.visibility = View.VISIBLE
                subtitleSyncOffsetMs = 0L
                updateSubtitleSyncUi()
                AppLogger.info(TAG, "Local subtitle loaded: ${subtitleFile.name}")
                applyLiveSubtitleChange(subtitleFile)
                invalidateOptionsMenu()
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
                downloadedSubtitleFile = null
                binding.subtitleStatus.text = getString(R.string.subtitle_selected, track.language)
                binding.subtitleStatus.visibility = View.VISIBLE
                subtitleSyncOffsetMs = 0L
                updateSubtitleSyncUi()
                AppLogger.info(TAG, "Subtitle extracted: ${subtitleFile.name}")
                applyLiveSubtitleChange(subtitleFile)
                invalidateOptionsMenu()
            } else {
                Toast.makeText(this@VideoDetailActivity, R.string.error_subtitle, Toast.LENGTH_SHORT).show()
                AppLogger.error(TAG, "Failed to extract subtitle for track ${track.index}")
            }
        }
    }

    private fun getOpenSubtitlesPrefs(): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            OPENSUBTITLES_PREFS,
            masterKeyAlias,
            this,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun searchOpenSubtitles(video: VideoItem) {
        val prefs = getOpenSubtitlesPrefs()
        val apiKey = prefs.getString("api_key", null)
        val username = prefs.getString("username", null)
        val password = prefs.getString("password", null)

        if (apiKey.isNullOrBlank() || username.isNullOrBlank() || password.isNullOrBlank()) {
            showOpenSubtitlesLoginDialog(video)
            return
        }

        performOpenSubtitlesSearch(video, apiKey, username, password)
    }

    private fun showOpenSubtitlesLoginDialog(video: VideoItem) {
        showOpenSubtitlesCredentialsDialog(video)
    }

    private fun showOpenSubtitlesCredentialsDialog(videoToSearch: VideoItem? = null) {
        val prefs = getOpenSubtitlesPrefs()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }

        val apiKeyInput = EditText(this).apply {
            hint = getString(R.string.opensubtitles_api_key_hint)
            setText(prefs.getString("api_key", ""))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val usernameInput = EditText(this).apply {
            hint = getString(R.string.opensubtitles_username_hint)
            setText(prefs.getString("username", ""))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val passwordInput = EditText(this).apply {
            hint = getString(R.string.opensubtitles_password_hint)
            setText(prefs.getString("password", ""))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        layout.addView(apiKeyInput)
        layout.addView(usernameInput)
        layout.addView(passwordInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.opensubtitles_login_title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val key = apiKeyInput.text.toString().trim()
                val user = usernameInput.text.toString().trim()
                val pass = passwordInput.text.toString().trim()

                if (key.isBlank() || user.isBlank() || pass.isBlank()) {
                    Toast.makeText(this, R.string.opensubtitles_credentials_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                prefs.edit()
                    .putString("api_key", key)
                    .putString("username", user)
                    .putString("password", pass)
                    .apply()

                if (videoToSearch != null) {
                    performOpenSubtitlesSearch(videoToSearch, key, user, pass)
                } else {
                    Toast.makeText(this, R.string.opensubtitles_credentials_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performOpenSubtitlesSearch(video: VideoItem, apiKey: String, username: String, password: String) {
        binding.progressBar.visibility = View.VISIBLE
        activityScope.launch {
            val (hashResults, queryResults) = withContext(Dispatchers.IO) {
                val client = OpenSubtitlesClient(apiKey, username, password)

                // Hash-based search (MPC-HC approach)
                val videoFile = File(video.path)
                val hash = OpenSubtitlesHash.computeHash(videoFile)
                var hashSubtitles = emptyList<OpenSubtitlesClient.SubtitleResult>()

                if (hash != null) {
                    AppLogger.info(TAG, "OpenSubtitles hash: $hash (size: ${videoFile.length()})")
                    hashSubtitles = client.searchByHash(hash)
                }

                // Text query search
                val query = video.title.replace(Regex("\\.[^.]+$"), "")
                AppLogger.info(TAG, "Searching by query: $query")
                val querySubtitles = client.searchByQuery(query)

                // Deduplicate: remove query results already found by hash
                val hashFileIds = hashSubtitles.map { it.fileId }.toSet()
                val uniqueQuerySubtitles = querySubtitles.filter { it.fileId !in hashFileIds }

                Pair(hashSubtitles, uniqueQuerySubtitles)
            }
            binding.progressBar.visibility = View.GONE

            if (hashResults.isEmpty() && queryResults.isEmpty()) {
                Toast.makeText(this@VideoDetailActivity, R.string.opensubtitles_no_results, Toast.LENGTH_SHORT).show()
            } else {
                showOpenSubtitlesResults(video, hashResults, queryResults, apiKey, username, password)
            }
        }
    }

    private fun showOpenSubtitlesResults(
        video: VideoItem,
        hashResults: List<OpenSubtitlesClient.SubtitleResult>,
        queryResults: List<OpenSubtitlesClient.SubtitleResult>,
        apiKey: String,
        username: String,
        password: String
    ) {
        // Sort each group by download count descending
        val sortedHash = hashResults.sortedByDescending { it.downloadCount }
        val sortedQuery = queryResults.sortedByDescending { it.downloadCount }

        // Build combined list: null entries are section headers
        val allResults = mutableListOf<OpenSubtitlesClient.SubtitleResult?>()
        val displayItems = mutableListOf<String>()

        if (sortedHash.isNotEmpty()) {
            allResults.add(null)
            displayItems.add(getString(R.string.opensubtitles_hash_matches))
            for (result in sortedHash) {
                allResults.add(result)
                val name = result.release.ifBlank { result.fileName }
                displayItems.add("[${result.language}] $name (${result.downloadCount} downloads)")
            }
        }

        if (sortedQuery.isNotEmpty()) {
            allResults.add(null)
            displayItems.add(getString(R.string.opensubtitles_title_matches))
            for (result in sortedQuery) {
                allResults.add(result)
                val name = result.release.ifBlank { result.fileName }
                displayItems.add("[${result.language}] $name (${result.downloadCount} downloads)")
            }
        }

        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, displayItems) {
            override fun isEnabled(position: Int): Boolean = allResults[position] != null

            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent)
                val textView = view.findViewById<android.widget.TextView>(android.R.id.text1)
                if (allResults[position] == null) {
                    textView.setTypeface(null, android.graphics.Typeface.BOLD)
                    textView.setTextColor(getColor(com.google.android.material.R.color.material_on_surface_emphasis_medium))
                    textView.textSize = 13f
                } else {
                    textView.setTypeface(null, android.graphics.Typeface.NORMAL)
                    textView.setTextColor(getColor(com.google.android.material.R.color.material_on_surface_emphasis_high_type))
                    textView.textSize = 16f
                }
                return view
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.opensubtitles_results_title)
            .setAdapter(adapter) { _, which ->
                val selected = allResults[which] ?: return@setAdapter
                downloadOpenSubtitle(selected, apiKey, username, password)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun downloadOpenSubtitle(
        result: OpenSubtitlesClient.SubtitleResult,
        apiKey: String,
        username: String,
        password: String
    ) {
        binding.progressBar.visibility = View.VISIBLE
        activityScope.launch {
            val (vttFile, rawFile) = withContext(Dispatchers.IO) {
                val client = OpenSubtitlesClient(apiKey, username, password)

                if (!client.login()) {
                    return@withContext Pair(null, null)
                }

                val outputDir = File(cacheDir, "subtitles")
                val downloadedFile = client.download(result.fileId, outputDir)
                    ?: return@withContext Pair(null, null)

                // Convert to VTT for Cast compatibility
                val fileName = downloadedFile.name
                val converted = downloadedFile.inputStream().use { stream ->
                    subtitleConverter.convertToVtt(stream, fileName, outputDir)
                }
                Pair(converted, downloadedFile)
            }
            binding.progressBar.visibility = View.GONE

            if (vttFile != null) {
                selectedSubtitleFile = vttFile
                downloadedSubtitleFile = rawFile
                binding.subtitleStatus.text = getString(R.string.opensubtitles_subtitle_selected, result.language)
                binding.subtitleStatus.visibility = View.VISIBLE
                subtitleSyncOffsetMs = 0L
                updateSubtitleSyncUi()
                AppLogger.info(TAG, "OpenSubtitles subtitle loaded: ${vttFile.name}")
                applyLiveSubtitleChange(vttFile)
                invalidateOptionsMenu()
            } else {
                Toast.makeText(this@VideoDetailActivity, R.string.opensubtitles_download_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- Gestdown (Addic7ed) subtitle search ---

    private fun searchGestdown(video: VideoItem) {
        // Try to extract show name from file name
        val fileName = File(video.path).nameWithoutExtension
        val guessedName = fileName
            .replace(Regex("[._]"), " ")
            .replace(Regex("[Ss]\\d{1,2}[Ee]\\d{1,2}.*"), "")
            .replace(Regex("\\d{3,4}p.*", RegexOption.IGNORE_CASE), "")
            .trim()
            .replace(Regex("^the\\s+", RegexOption.IGNORE_CASE), "")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }
        layout.addView(TextView(this).apply {
            text = getString(R.string.gestdown_show_search_hint)
            setPadding(0, 0, 0, 4)
        })
        val searchInput = EditText(this).apply {
            setText(guessedName)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        layout.addView(searchInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.gestdown_show_search_title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val query = searchInput.text.toString().trim()
                if (query.isNotEmpty()) {
                    performGestdownShowSearch(video, query)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performGestdownShowSearch(video: VideoItem, query: String) {
        binding.progressBar.visibility = View.VISIBLE
        activityScope.launch {
            val shows = withContext(Dispatchers.IO) {
                val client = GestdownClient()
                val results = client.searchShows(query)
                if (results.isNotEmpty()) return@withContext results

                // Retry without leading "the" if first search returned nothing
                val withoutThe = query.replace(Regex("^the\\s+", RegexOption.IGNORE_CASE), "")
                if (withoutThe != query && withoutThe.isNotEmpty()) {
                    client.searchShows(withoutThe)
                } else {
                    results
                }
            }
            binding.progressBar.visibility = View.GONE

            if (shows.isEmpty()) {
                Toast.makeText(this@VideoDetailActivity, R.string.gestdown_no_shows, Toast.LENGTH_SHORT).show()
            } else {
                showGestdownShowPicker(video, shows)
            }
        }
    }

    private fun showGestdownShowPicker(video: VideoItem, shows: List<GestdownClient.ShowResult>) {
        val displayItems = shows.map { "${it.name} (${it.nbSeasons} seasons)" }

        AlertDialog.Builder(this)
            .setTitle(R.string.gestdown_select_show_title)
            .setItems(displayItems.toTypedArray()) { _, which ->
                showGestdownEpisodeDialog(video, shows[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showGestdownEpisodeDialog(video: VideoItem, show: GestdownClient.ShowResult) {
        // Try to guess season/episode from file name
        val fileName = File(video.path).nameWithoutExtension
        val seMatch = Regex("[Ss](\\d{1,2})[Ee](\\d{1,2})").find(fileName)
        val guessedSeason = seMatch?.groupValues?.get(1) ?: ""
        val guessedEpisode = seMatch?.groupValues?.get(2) ?: ""

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }
        layout.addView(TextView(this).apply {
            text = getString(R.string.gestdown_season_hint)
            setPadding(0, 0, 0, 4)
        })
        val seasonInput = EditText(this).apply {
            setText(guessedSeason)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(seasonInput)
        layout.addView(TextView(this).apply {
            text = getString(R.string.gestdown_episode_hint)
            setPadding(0, 8, 0, 4)
        })
        val episodeInput = EditText(this).apply {
            setText(guessedEpisode)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(episodeInput)
        layout.addView(TextView(this).apply {
            text = getString(R.string.gestdown_language_hint)
            setPadding(0, 8, 0, 4)
        })
        val languageInput = EditText(this).apply {
            setText("English")
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        layout.addView(languageInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.gestdown_episode_title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val season = seasonInput.text.toString().trim().toIntOrNull()
                val episode = episodeInput.text.toString().trim().toIntOrNull()
                val language = languageInput.text.toString().trim().ifEmpty { "English" }

                if (season == null || episode == null) {
                    Toast.makeText(this, R.string.gestdown_season_episode_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                performGestdownSubtitleSearch(video, show.id, season, episode, language)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performGestdownSubtitleSearch(
        video: VideoItem,
        showId: String,
        season: Int,
        episode: Int,
        language: String
    ) {
        binding.progressBar.visibility = View.VISIBLE
        activityScope.launch {
            val results = withContext(Dispatchers.IO) {
                GestdownClient().getSubtitles(showId, season, episode, language)
            }
            binding.progressBar.visibility = View.GONE

            if (results.isEmpty()) {
                Toast.makeText(this@VideoDetailActivity, R.string.gestdown_no_results, Toast.LENGTH_SHORT).show()
            } else {
                showGestdownResults(results)
            }
        }
    }

    private fun showGestdownResults(results: List<GestdownClient.SubtitleResult>) {
        val sorted = results.sortedByDescending { it.downloadCount }
        val displayItems = sorted.map { result ->
            val flags = buildString {
                if (result.hearingImpaired) append(" [HI]")
                if (!result.completed) append(" [incomplete]")
            }
            "${result.version}$flags (${result.downloadCount} downloads)"
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.gestdown_results_title)
            .setItems(displayItems.toTypedArray()) { _, which ->
                downloadGestdownSubtitle(sorted[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun downloadGestdownSubtitle(result: GestdownClient.SubtitleResult) {
        binding.progressBar.visibility = View.VISIBLE
        activityScope.launch {
            val (vttFile, rawFile) = withContext(Dispatchers.IO) {
                val client = GestdownClient()
                val outputDir = File(cacheDir, "subtitles")
                val downloadedFile = client.download(result.subtitleId, outputDir)
                    ?: return@withContext Pair(null, null)

                val fileName = downloadedFile.name
                val converted = downloadedFile.inputStream().use { stream ->
                    subtitleConverter.convertToVtt(stream, fileName, outputDir)
                }
                Pair(converted, downloadedFile)
            }
            binding.progressBar.visibility = View.GONE

            if (vttFile != null) {
                selectedSubtitleFile = vttFile
                downloadedSubtitleFile = rawFile
                binding.subtitleStatus.text = getString(R.string.gestdown_subtitle_selected, result.language)
                binding.subtitleStatus.visibility = View.VISIBLE
                subtitleSyncOffsetMs = 0L
                updateSubtitleSyncUi()
                AppLogger.info(TAG, "Gestdown subtitle loaded: ${vttFile.name}")
                applyLiveSubtitleChange(vttFile)
                invalidateOptionsMenu()
            } else {
                Toast.makeText(this@VideoDetailActivity, R.string.gestdown_download_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createSubtitleStyle(): TextTrackStyle {
        return TextTrackStyle().apply {
            foregroundColor = Color.WHITE
            backgroundColor = Color.TRANSPARENT
            windowColor = Color.TRANSPARENT
            windowType = TextTrackStyle.WINDOW_TYPE_NONE
            edgeType = TextTrackStyle.EDGE_TYPE_OUTLINE
            edgeColor = Color.BLACK
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
        // Clear previous subtitle track first to avoid the last rendered line persisting
        client.setActiveMediaTracks(longArrayOf()).setResultCallback {
            client.setTextTrackStyle(createSubtitleStyle())
            client.load(loadRequest).setResultCallback { result ->
                if (result.status.isSuccess) {
                    AppLogger.info(TAG, "Live subtitle switch: load SUCCESS")
                    client.setTextTrackStyle(createSubtitleStyle())
                } else {
                    AppLogger.error(TAG, "Live subtitle switch: load FAILED - ${result.status.statusMessage}")
                    val mediaError = result.mediaError
                    if (mediaError != null) {
                        logMediaError(mediaError)
                    }
                }
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

        // Save current position, then re-cast. For a live transcode the receiver
        // position restarts at 0 per stream, so use the absolute source position.
        pendingSeekPositionMs = if (isTranscodeSession) {
            currentLivePositionMs()
        } else {
            client.approximateStreamPosition
        }
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
            // A non-default audio track was chosen. Produce a Cast-compatible fragmented
            // MP4 (H.264 video passthrough + selected audio re-encoded to AAC). This
            // replaces the old MKV remux/filter paths, which cast as video/x-matroska —
            // a container the Cast receiver does not support.
            AppLogger.info(TAG, "Audio track selection: remuxing to fMP4 (video passthrough + AAC audio)")
            startRemuxWithAudioTranscodeAndCast(video, probe)
        } else {
            castVideo(video, getEffectiveSubtitleFile())
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
                castVideo(video, getEffectiveSubtitleFile())
                return@launch
            }
            cachedProbeResult = probeResult

            // Single auto-decision: direct-play when the receiver supports both streams,
            // otherwise transcode only what's needed (see StreamingDecision). The legacy
            // toggles act as overrides: realtime = force transcode, HLS seeking = prefer
            // seekable HLS even for Dolby (accepting an audio transcode). Learned per-receiver
            // capability steers known-bad codecs straight to transcoding.
            val deviceId = castSession?.castDevice?.deviceId
            val plan = StreamingDecision.decide(
                probeResult,
                forceTranscode = SettingsActivity.getRealtimeTranscode(this@VideoDetailActivity),
                preferHls = SettingsActivity.getHlsSeeking(this@VideoDetailActivity),
                hints = receiverCaps.hints(deviceId)
            )
            AppLogger.info(TAG, "Streaming plan: ${plan.path} — ${plan.reason}")
            if (plan.path == StreamingDecision.Path.DIRECT) {
                // Remember this attempt so a receiver error (codec it actually can't decode)
                // can be learned and escalated to a transcode automatically.
                pendingDirectAttempt = DirectAttempt(
                    deviceId, video, probeResult,
                    videoMime = (probeResult.primaryVideo?.mime ?: "").lowercase(),
                    audioMime = (probeResult.primaryAudio?.mime ?: "").lowercase()
                )
                directStreamOrRemux(video)
            } else {
                pendingDirectAttempt = null
                startTranscoding(video, probeResult, pendingSeekPositionMs)
            }
        }
    }

    /**
     * Reactive capability learning. A receiver error on a buffered (direct-play) stream that
     * never reached PLAYING means the receiver couldn't decode the source. Record the source
     * codecs as unsupported for this receiver and automatically re-cast as a transcode, so the
     * next attempt for these codecs skips direct play. No-op once playback has started.
     */
    private fun maybeLearnAndEscalate(streamType: Int) {
        val attempt = pendingDirectAttempt ?: return
        // Only a buffered (direct) stream failure indicates a direct-play capability gap; the
        // transcode/remux paths are STREAM_TYPE_LIVE and have their own error handling.
        if (streamType != MediaInfo.STREAM_TYPE_BUFFERED) return
        pendingDirectAttempt = null
        val mimes = listOf(attempt.videoMime, attempt.audioMime).filter { it.isNotBlank() }
        val learned = receiverCaps.recordUnsupported(attempt.deviceId, mimes)
        AppLogger.warn(
            TAG,
            "Direct play failed on receiver (deviceId=${attempt.deviceId}); learned " +
                "unsupported=$mimes (new=$learned)."
        )
        // When the audio isn't demuxable by this device's MediaExtractor, the transcoder
        // can't read it — escalating would just crash. Direct play was the only option, so
        // surface the failure instead of looping into a broken transcode.
        if (!attempt.probe.audioPlatformDemuxable) {
            AppLogger.warn(TAG, "No transcode fallback: audio not demuxable on this device.")
            runOnUiThread {
                Toast.makeText(this, R.string.direct_play_failed_no_fallback, Toast.LENGTH_LONG).show()
            }
            return
        }
        AppLogger.warn(TAG, "Escalating to transcode.")
        runOnUiThread {
            Toast.makeText(this, R.string.direct_play_failed_transcoding, Toast.LENGTH_SHORT).show()
            startTranscoding(attempt.video, attempt.probe, pendingSeekPositionMs)
        }
    }

    private fun startTranscoding(video: VideoItem, probeResult: MediaProbeResult, startPositionMs: Long = 0L) {
        // Re-derive the plan (this entry is only reached when transcoding) to pick HLS vs
        // live and whether to copy or transcode the audio. HLS is seekable; live is used
        // only when Dolby audio must be preserved (receivers reject AC-3/E-AC-3 over HLS).
        val plan = StreamingDecision.decide(
            probeResult,
            forceTranscode = true,
            preferHls = SettingsActivity.getHlsSeeking(this)
        )
        if (plan.path == StreamingDecision.Path.HLS) {
            castHls(video, probeResult, plan.copyAudio)
            return
        }

        // Cancel any in-flight transcode before starting a new one (e.g. on seek).
        transcodeStreamer?.cancel()

        val streamer = TranscodeStreamer()
        transcodeStreamer = streamer

        // Remember everything needed to restart the transcode for a seek, and mark
        // this as a live transcode session so seeks restart rather than crash-seek.
        isTranscodeSession = true
        transcodeBaseMs = startPositionMs.coerceAtLeast(0)
        transcodeVideo = video
        transcodeProbe = probeResult

        val copyAudio = plan.copyAudio
        AppLogger.info(TAG, "Starting streaming transcode to fragmented MP4 (copyAudio=$copyAudio, startPosition=${formatDuration(transcodeBaseMs)})")

        val inputPath = video.path
        val audioTrack = selectedAudioTrack

        castStreamingSource(video, "video/mp4", transcodeBaseMs) {
            streamer.createTranscodeStream(inputPath, probeResult, audioTrack, copyAudio, transcodeBaseMs,
                object : TranscodeStreamer.ProgressListener {
                    override fun onProgress(percent: Int) {
                        AppLogger.info(TAG, "Transcode progress: $percent%")
                    }
                    override fun onError(error: String) {
                        AppLogger.error(TAG, "Transcode stream error: $error")
                        runOnUiThread {
                            Toast.makeText(this@VideoDetailActivity,
                                getString(R.string.transcode_failed, error), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }
    }

    private fun startRemuxWithAudioTranscodeAndCast(video: VideoItem, probeResult: MediaProbeResult) {
        val audioTrack = selectedAudioTrack ?: return
        // This is a live stream but not a restartable transcode, so disable transcode-seek.
        isTranscodeSession = false
        val streamer = TranscodeStreamer()
        transcodeStreamer = streamer

        AppLogger.info(TAG, "Remuxing with audio transcode (streaming): ${audioTrack.codec} ${audioTrack.language} (index=${audioTrack.trackIndex})")

        val inputPath = video.path

        castStreamingSource(video, "video/mp4") {
            streamer.createRemuxWithAudioTranscodeStream(inputPath, probeResult, audioTrack,
                object : TranscodeStreamer.ProgressListener {
                    override fun onProgress(percent: Int) {
                        AppLogger.info(TAG, "Remux with audio transcode progress: $percent%")
                    }
                    override fun onError(error: String) {
                        AppLogger.error(TAG, "Remux with audio transcode error: $error")
                        runOnUiThread {
                            Toast.makeText(this@VideoDetailActivity,
                                getString(R.string.remux_failed, error), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }
    }

    /**
     * Registers a streaming source factory and immediately sends it to the Cast device.
     * The factory lambda creates a fresh InputStream each time the Cast device requests data.
     * Streaming starts immediately — no need to wait for the full transcode/remux to complete.
     */
    private fun castStreamingSource(
        video: VideoItem,
        contentType: String,
        sourceOffsetMs: Long = 0L,
        factory: () -> InputStream
    ) {
        val service = mediaServerService
        if (service == null) {
            AppLogger.error(TAG, "castStreamingSource: media server service is null")
            Toast.makeText(this, R.string.server_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val session = castSession
        if (session == null) {
            AppLogger.error(TAG, "castStreamingSource: cast session is null")
            Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show()
            return
        }

        val serverIp = getDeviceIpAddress()
        val serverPort = service.getServerPort()

        // All streaming-source casts use STREAM_TYPE_LIVE, which the receiver cannot seek.
        isLiveStreamSession = true

        val streamPath = service.registerStreamingSource(video.title, contentType, factory)
        val streamUrl = "http://$serverIp:$serverPort$streamPath"

        AppLogger.info(TAG, "castStreamingSource: url=$streamUrl, contentType=$contentType")

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, video.title)
        }

        val mediaTracks = mutableListOf<MediaTrack>()

        // Shift the (absolute-timed) subtitle back by the stream's source start so it lines
        // up with the 0-based live stream after a seek-by-restart.
        val effectiveSubtitle = getEffectiveSubtitleFile(-sourceOffsetMs)
        if (effectiveSubtitle != null) {
            val subtitlePath = service.registerSubtitle(effectiveSubtitle)
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

        val mediaInfo = MediaInfo.Builder(streamUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
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
            .apply {
                if (mediaTracks.isNotEmpty()) {
                    setActiveTrackIds(longArrayOf(1))
                }
            }
            .build()

        AppLogger.info(TAG, "castStreamingSource: sending load request (streamType=LIVE, contentType=$contentType)")
        AppLogger.info(TAG, "castStreamingSource: device=${session.castDevice?.friendlyName}, model=${session.castDevice?.modelName}")
        val remoteMediaClient = session.remoteMediaClient
        if (remoteMediaClient == null) {
            AppLogger.error(TAG, "castStreamingSource: remoteMediaClient is null!")
            Toast.makeText(this, R.string.error_cast, Toast.LENGTH_SHORT).show()
            return
        }

        if (mediaTracks.isNotEmpty()) {
            remoteMediaClient.setTextTrackStyle(createSubtitleStyle())
        }

        val pendingResult = remoteMediaClient.load(loadRequest)
        pendingResult.setResultCallback { result ->
            val status = result.status
            if (status.isSuccess) {
                AppLogger.info(TAG, "castStreamingSource: load SUCCESS")
                if (mediaTracks.isNotEmpty()) {
                    remoteMediaClient.setTextTrackStyle(createSubtitleStyle())
                }
            } else {
                AppLogger.error(TAG, "castStreamingSource: load FAILED - statusCode=${status.statusCode}, statusMessage=${status.statusMessage}")
                val mediaError = result.mediaError
                if (mediaError != null) {
                    logMediaError(mediaError)
                }
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.cast_load_failed, status.statusMessage ?: "Unknown error"), Toast.LENGTH_LONG).show()
                }
            }
        }
        updateCastStatus(video.title)
        Toast.makeText(this, R.string.loading_video, Toast.LENGTH_SHORT).show()
    }

    /**
     * Experimental: cast the transcode as a seekable HLS VOD presentation. The local
     * server transcodes fMP4 segments on demand; the playlist's #EXT-X-ENDLIST makes
     * the receiver treat it as VOD, so seeking is native (no live mode, no crash).
     */
    private fun castHls(video: VideoItem, probeResult: MediaProbeResult, copyAudio: Boolean) {
        val service = mediaServerService
        if (service == null) {
            AppLogger.error(TAG, "castHls: media server service is null")
            Toast.makeText(this, R.string.server_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val session = castSession
        if (session == null) {
            AppLogger.error(TAG, "castHls: cast session is null")
            Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        val remoteMediaClient = session.remoteMediaClient
        if (remoteMediaClient == null) {
            AppLogger.error(TAG, "castHls: remoteMediaClient is null")
            Toast.makeText(this, R.string.error_cast, Toast.LENGTH_SHORT).show()
            return
        }

        // HLS VOD is natively seekable, so this is not a live/transcode-restart session.
        isLiveStreamSession = false
        isTranscodeSession = false

        val serverIp = getDeviceIpAddress()
        val serverPort = service.getServerPort()

        // Deliver subtitles as an in-manifest HLS WebVTT rendition (not a sideloaded
        // MediaTrack): sideloaded text tracks don't follow the HLS media timeline on
        // the receiver, so they desync/play from the beginning when seeking.
        val effectiveSubtitle = getEffectiveSubtitleFile()
        val subtitleVtt: ByteArray? = effectiveSubtitle?.let {
            try { it.readBytes() } catch (e: Exception) {
                AppLogger.warn(TAG, "castHls: failed to read subtitle: ${e.message}"); null
            }
        }

        val hlsSession = HlsTranscodeSession(video.path, probeResult, selectedAudioTrack, copyAudio, subtitleVtt)
        val hlsBasePath = service.registerHlsSession(video.title, hlsSession)
        // Always cast the master playlist: it advertises the real audio CODECS
        // (ec-3/ac-3/mp4a) so the receiver picks the correct decoder, and carries the
        // in-manifest subtitle rendition when subtitles are present.
        val playlistUrl = "http://$serverIp:$serverPort$hlsBasePath/master.m3u8"
        AppLogger.info(TAG, "castHls: url=$playlistUrl (subtitles=${hlsSession.hasSubtitles})")

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, video.title)
        }

        val mediaInfo = MediaInfo.Builder(playlistUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType("application/x-mpegurl")
            .setHlsSegmentFormat(com.google.android.gms.cast.HlsSegmentFormat.FMP4)
            .setHlsVideoSegmentFormat(com.google.android.gms.cast.HlsVideoSegmentFormat.FMP4)
            .setMetadata(metadata)
            .apply {
                if (probeResult.durationMs > 0) {
                    setStreamDuration(probeResult.durationMs * 1000)
                }
            }
            .build()

        val loadRequest = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .setCurrentTime(pendingSeekPositionMs)
            .build()

        if (hlsSession.hasSubtitles) {
            remoteMediaClient.setTextTrackStyle(createSubtitleStyle())
        }

        AppLogger.info(TAG, "castHls: sending load request (HLS VOD, startPosition=${formatDuration(pendingSeekPositionMs)})")
        remoteMediaClient.load(loadRequest).setResultCallback { result ->
            val status = result.status
            if (status.isSuccess) {
                AppLogger.info(TAG, "castHls: load SUCCESS")
                if (hlsSession.hasSubtitles) {
                    remoteMediaClient.setTextTrackStyle(createSubtitleStyle())
                }
            } else {
                AppLogger.error(TAG, "castHls: load FAILED - statusCode=${status.statusCode}, statusMessage=${status.statusMessage}")
                result.mediaError?.let { logMediaError(it) }
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.cast_load_failed, status.statusMessage ?: "Unknown error"), Toast.LENGTH_LONG).show()
                }
            }
        }
        updateCastStatus(video.title)
        Toast.makeText(this, R.string.loading_video, Toast.LENGTH_SHORT).show()
    }

    private fun castTranscodedVideo(originalVideo: VideoItem, transcodedFile: File, contentType: String = "video/mp4") {
        // Buffered file playback — the receiver can seek natively.
        isLiveStreamSession = false
        isTranscodeSession = false
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

        val effectiveSubtitle = getEffectiveSubtitleFile()
        if (effectiveSubtitle != null) {
            val subtitlePath = service.registerSubtitle(effectiveSubtitle)
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
                if (mediaTracks.isNotEmpty()) {
                    remoteMediaClient.setTextTrackStyle(createSubtitleStyle())
                }
            } else {
                AppLogger.error(TAG, "castTranscodedVideo: load FAILED - statusCode=${status.statusCode}, statusMessage=${status.statusMessage}")
                val mediaError = result.mediaError
                if (mediaError != null) {
                    logMediaError(mediaError)
                }
                val resultCustomData = result.customData
                if (resultCustomData != null) {
                    AppLogger.error(TAG, "castTranscodedVideo: result customData=$resultCustomData")
                }
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.cast_load_failed, status.statusMessage ?: "Unknown error"), Toast.LENGTH_LONG).show()
                }
            }
        }
        updateCastStatus("${originalVideo.title} (transcoded)")
        Toast.makeText(this, R.string.loading_video, Toast.LENGTH_SHORT).show()
    }

    private fun castVideo(video: VideoItem, subtitleFile: File?) {
        // Direct/buffered playback — the receiver can seek natively.
        isLiveStreamSession = false
        isTranscodeSession = false
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
                if (mediaTracks.isNotEmpty()) {
                    remoteMediaClient.setTextTrackStyle(createSubtitleStyle())
                }
            } else {
                AppLogger.error(TAG, "castVideo: load FAILED - statusCode=${status.statusCode}, statusMessage=${status.statusMessage}")
                val mediaError = result.mediaError
                if (mediaError != null) {
                    logMediaError(mediaError)
                }
                val resultCustomData = result.customData
                if (resultCustomData != null) {
                    AppLogger.error(TAG, "castVideo: result customData=$resultCustomData")
                }
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
        val intent = Intent(this, MediaServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.video_detail_menu, menu)
        CastButtonFactory.setUpMediaRouteButton(
            applicationContext, menu, R.id.media_route_menu_item
        )
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_save_subtitle)?.isVisible = downloadedSubtitleFile != null
        menu.findItem(R.id.action_realtime_transcode)?.isChecked =
            SettingsActivity.getRealtimeTranscode(this)
        menu.findItem(R.id.action_hls_seeking)?.isChecked =
            SettingsActivity.getHlsSeeking(this)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_logs -> {
                startActivity(Intent(this, LogActivity::class.java))
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_save_subtitle -> {
                val file = downloadedSubtitleFile
                if (file != null) {
                    val upstreamName = file.name
                        .removePrefix("opensubtitles_")
                        .removePrefix("gestdown_")
                    val subtitleExt = upstreamName.substringAfterLast('.', "srt")
                    val videoBaseName = videoItem?.path?.let { File(it).nameWithoutExtension }?.ifEmpty { null }
                    val suggestedName = if (videoBaseName != null) "$videoBaseName.$subtitleExt" else upstreamName
                    saveSubtitleLauncher.launch(suggestedName)
                }
                true
            }
            R.id.action_realtime_transcode -> {
                val enabled = !item.isChecked
                item.isChecked = enabled
                SettingsActivity.setRealtimeTranscode(this, enabled)
                Toast.makeText(
                    this,
                    if (enabled) R.string.realtime_transcode_on else R.string.realtime_transcode_off,
                    Toast.LENGTH_SHORT
                ).show()
                AppLogger.info(TAG, "Realtime transcoding toggled: $enabled")
                true
            }
            R.id.action_reset_receiver_learning -> {
                val deviceId = castSession?.castDevice?.deviceId
                receiverCaps.reset(deviceId)
                Toast.makeText(this, R.string.reset_receiver_learning_done, Toast.LENGTH_SHORT).show()
                AppLogger.info(TAG, "Reset receiver learning for deviceId=$deviceId")
                true
            }
            R.id.action_hls_seeking -> {
                val enabled = !item.isChecked
                item.isChecked = enabled
                SettingsActivity.setHlsSeeking(this, enabled)
                Toast.makeText(
                    this,
                    if (enabled) R.string.hls_seeking_on else R.string.hls_seeking_off,
                    Toast.LENGTH_SHORT
                ).show()
                AppLogger.info(TAG, "HLS seeking toggled: $enabled")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun saveSubtitleToUri(uri: Uri) {
        val file = downloadedSubtitleFile ?: return
        activityScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val output = contentResolver.openOutputStream(uri)
                        ?: return@withContext false
                    output.use { out ->
                        file.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    true
                } catch (e: Exception) {
                    AppLogger.error(TAG, "Failed to save subtitle: ${e.message}")
                    false
                }
            }
            if (success) {
                Toast.makeText(this@VideoDetailActivity, R.string.subtitle_saved, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@VideoDetailActivity, R.string.subtitle_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
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
                if (client?.hasMediaSession() == true && isMediaActive(client.mediaStatus?.playerState)) {
                    // Live transcode: restart from the dragged position (absolute source time).
                    if (isTranscodeSession) {
                        seekTranscodeTo(position)
                        return
                    }
                    if (isLiveStreamSession) {
                        Toast.makeText(this@VideoDetailActivity, R.string.seek_not_available, Toast.LENGTH_SHORT).show()
                        updateSeekBarProgress()
                        return
                    }
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
        // Live transcode: the receiver position restarts at 0 per stream, so the true
        // position is the source base plus the receiver's reported position, against
        // the full source duration.
        if (isTranscodeSession) {
            val video = videoItem ?: return
            val duration = video.duration
            if (duration > 0) {
                val pos = (transcodeBaseMs + client.approximateStreamPosition.coerceAtLeast(0))
                    .coerceIn(0, duration)
                binding.videoSeekBar.max = duration.toInt()
                binding.videoSeekBar.progress = pos.toInt()
                binding.currentTimeText.text = formatDuration(pos)
                binding.totalTimeText.text = formatDuration(duration)
            }
            return
        }
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
            currentSession?.addCastListener(castListener)
            updateCastStatus()
            updateSeekBarProgress()
            currentSession?.remoteMediaClient?.playerState?.let { updateProgressTracking(it) }
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
            castSession?.removeCastListener(castListener)
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
        transcodeStreamer?.cancel()
        activityScope.cancel()
        transcodedFile?.delete()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }

    private fun logMediaError(mediaError: MediaError) {
        try {
            val errorType = mediaError.type ?: "unknown"
            val errorReason = mediaError.reason ?: "unknown"
            val detailedCode = mediaError.detailedErrorCode
            val errorCustomData = mediaError.customData
            AppLogger.error(TAG, "Cast device error: type=$errorType, reason=$errorReason, detailedErrorCode=${detailedCode ?: "none"}")
            if (detailedCode != null) {
                AppLogger.error(TAG, "Cast device error detail: ${describeDetailedErrorCode(detailedCode)}")
            }
            if (errorCustomData != null) {
                AppLogger.error(TAG, "Cast device error customData: $errorCustomData")
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to read MediaError from cast device: ${e.message}")
        }
    }

    private fun describeDetailedErrorCode(code: Int): String {
        return when (code) {
            100 -> "MEDIA_UNKNOWN ($code) — Unknown media error"
            101 -> "MEDIA_ABORTED ($code) — Playback was aborted"
            102 -> "MEDIA_DECODE ($code) — Failed to decode media (codec may be unsupported)"
            103 -> "MEDIA_NETWORK ($code) — Network error prevented fetching media"
            104 -> "MEDIA_SRC_NOT_SUPPORTED ($code) — Media source/format not supported"
            110 -> "MEDIA_UNKNOWN_TRANSFER_MODE ($code) — Unknown transfer mode"
            200 -> "REQUEST_UNKNOWN ($code) — Unknown request error"
            201 -> "REQUEST_INVALID_PARAM ($code) — Invalid request parameter"
            202 -> "REQUEST_INVALID_MEDIA_SESSION ($code) — Invalid media session"
            203 -> "REQUEST_SKIP_LIMIT ($code) — Skip limit reached"
            204 -> "REQUEST_NOT_SUPPORTED ($code) — Request not supported"
            205 -> "REQUEST_LANGUAGE_NOT_SUPPORTED ($code) — Language not supported"
            300 -> "GENERIC_LOAD ($code) — Generic load error"
            301 -> "LOAD_INTERRUPTED ($code) — Load was interrupted"
            302 -> "BREAK_CLIP_LOADING_ERROR ($code) — Break clip load error"
            303 -> "BREAK_SEEK_INTERCEPTOR_ERROR ($code) — Break seek interceptor error"
            304 -> "IMAGE_ERROR ($code) — Image loading error"
            else -> "UNKNOWN_ERROR ($code) — Unrecognized error code"
        }
    }

    private fun logCastDeviceInfo(session: CastSession) {
        try {
            val device = session.castDevice
            if (device != null) {
                AppLogger.info(TAG, "Cast device: name=${device.friendlyName}, model=${device.modelName}")
                AppLogger.info(TAG, "  deviceId=${device.deviceId}, deviceVersion=${device.deviceVersion}")
                AppLogger.info(TAG, "  ipAddress=${device.inetAddress?.hostAddress}")
            }
            val appMetadata = session.applicationMetadata
            if (appMetadata != null) {
                AppLogger.info(TAG, "Cast receiver app: name=${appMetadata.name}, appId=${appMetadata.applicationId}")
                AppLogger.info(TAG, "  namespaces=${appMetadata.supportedNamespaces}")
            }
            val appStatus = session.applicationStatus
            if (appStatus != null) {
                AppLogger.info(TAG, "Cast receiver app status: $appStatus")
            }
            AppLogger.info(TAG, "Cast receiver volume: ${session.volume}, muted=${session.isMute}")
        } catch (e: Exception) {
            AppLogger.warn(TAG, "Failed to log cast device info: ${e.message}")
        }
    }

    private fun describeCastStatusCode(code: Int): String {
        return when (code) {
            0 -> "SUCCESS"
            1 -> "CANCELED"
            2 -> "TIMEOUT"
            3 -> "INTERRUPTED"
            4 -> "NETWORK_ERROR"
            5 -> "AUTHENTICATION_ERROR"
            6 -> "NOT_CONNECTED"
            7 -> "SESSION_START_FAILED"
            8 -> "INTERNAL_ERROR"
            else -> "UNKNOWN_STATUS($code)"
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
