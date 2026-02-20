package com.storagecast.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.view.Menu
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.storagecast.R
import com.storagecast.databinding.ActivityMainBinding
import com.storagecast.model.VideoItem
import com.storagecast.server.MediaServerService
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var videoAdapter: VideoAdapter

    private var castContext: CastContext? = null
    private var castSession: CastSession? = null
    private var mediaServerService: MediaServerService? = null
    private var serviceBound = false

    private var pendingCastVideo: VideoItem? = null
    private var pendingSubtitleFile: File? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.loadVideos()
        } else {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
        }
    }

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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        setupRecyclerView()
        observeViewModel()
        setupCast()
        bindMediaServer()
        checkPermissionsAndLoad()

        binding.stopCastButton.setOnClickListener {
            castSession?.remoteMediaClient?.stop()
            updateCastStatus()
        }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIncomingIntent(it) }
    }

    private fun handleIncomingIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            val videoUri = intent.data ?: return
            val videoItem = resolveVideoItem(videoUri, intent.type) ?: return
            pendingCastVideo = videoItem
            viewModel.loadSubtitleTracks(videoItem)
        }
    }

    private fun resolveVideoItem(uri: Uri, intentMimeType: String?): VideoItem? {
        val mimeType = intentMimeType
            ?: contentResolver.getType(uri)
            ?: "video/mp4"

        var displayName = "Unknown"
        var size = 0L
        var filePath: String? = null

        // Try to get file path and metadata from the URI
        if (uri.scheme == "file") {
            filePath = uri.path
        }

        try {
            val projection = arrayOf(
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE,
                android.provider.MediaStore.MediaColumns.DATA
            )
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        displayName = cursor.getString(nameIndex) ?: displayName
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0) {
                        size = cursor.getLong(sizeIndex)
                    }
                    if (filePath == null) {
                        val dataIndex = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                        if (dataIndex >= 0) {
                            val path = cursor.getString(dataIndex)
                            if (!path.isNullOrEmpty() && File(path).exists()) {
                                filePath = path
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Couldn't query metadata; use defaults
        }

        val resolvedPath = filePath ?: uri.path ?: uri.toString()

        return VideoItem(
            id = uri.toString().hashCode().toLong(),
            title = displayName,
            uri = uri,
            path = resolvedPath,
            duration = 0L,
            size = size,
            mimeType = mimeType
        )
    }

    private fun setupRecyclerView() {
        videoAdapter = VideoAdapter(
            onVideoClick = { video -> onVideoSelected(video) },
            onSubtitleClick = { video -> onSubtitleRequested(video) }
        )
        binding.videoRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = videoAdapter
        }
    }

    private fun observeViewModel() {
        viewModel.videos.observe(this) { videos ->
            videoAdapter.submitList(videos)
            binding.emptyView.visibility = if (videos.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.loading.observe(this) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.subtitleTracks.observe(this) { (video, tracks) ->
            if (tracks.isEmpty()) {
                Toast.makeText(this, R.string.no_subtitles, Toast.LENGTH_SHORT).show()
                castVideo(video, null)
            } else {
                showSubtitleDialog(video, tracks)
            }
        }

        viewModel.extractedSubtitle.observe(this) { subtitleFile ->
            pendingSubtitleFile = subtitleFile
            pendingCastVideo?.let { video ->
                castVideo(video, subtitleFile)
                pendingCastVideo = null
            }
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

    private fun checkPermissionsAndLoad() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        when {
            ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED -> {
                viewModel.loadVideos()
            }
            else -> {
                requestPermissionLauncher.launch(permission)
            }
        }
    }

    private fun onVideoSelected(video: VideoItem) {
        if (castSession == null || castSession?.isConnected != true) {
            Toast.makeText(this, R.string.select_cast_device, Toast.LENGTH_SHORT).show()
            return
        }
        pendingCastVideo = video
        viewModel.loadSubtitleTracks(video)
    }

    private fun onSubtitleRequested(video: VideoItem) {
        viewModel.loadSubtitleTracks(video)
    }

    private fun showSubtitleDialog(video: VideoItem, tracks: List<com.storagecast.model.SubtitleTrack>) {
        val options = mutableListOf("No subtitles")
        tracks.forEachIndexed { _, track ->
            options.add("${track.language} - ${track.title} (${track.codec})")
        }

        AlertDialog.Builder(this)
            .setTitle("Select subtitle track")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    castVideo(video, null)
                } else {
                    val track = tracks[which - 1]
                    pendingCastVideo = video
                    viewModel.extractSubtitle(video.path, track.index)
                }
            }
            .show()
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        CastButtonFactory.setUpMediaRouteButton(
            applicationContext, menu, R.id.media_route_menu_item
        )

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.queryHint = getString(R.string.search_videos)
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                viewModel.filterVideos(query)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.filterVideos(newText)
                return true
            }
        })

        return true
    }

    override fun onResume() {
        super.onResume()
        try {
            castContext?.sessionManager?.addSessionManagerListener(
                sessionManagerListener, CastSession::class.java
            )
            castSession = castContext?.sessionManager?.currentCastSession
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
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }
}
