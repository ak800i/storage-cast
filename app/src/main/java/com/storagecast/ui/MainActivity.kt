package com.storagecast.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.storagecast.R
import com.storagecast.databinding.ActivityMainBinding
import com.storagecast.log.AppLogger
import com.storagecast.model.VideoItem
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var browseAdapter: BrowseAdapter

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.loadVideos()
        } else {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        setupRecyclerView()
        observeViewModel()
        setupCast()
        checkPermissionsAndLoad()
        setupBackNavigation()
        checkBatteryOptimization()

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
            openVideoDetail(videoItem)
        }
    }

    private fun resolveVideoItem(uri: Uri, intentMimeType: String?): VideoItem? {
        val mimeType = intentMimeType
            ?: contentResolver.getType(uri)
            ?: "video/mp4"

        var displayName = "Unknown"
        var size = 0L
        var filePath: String? = null

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
        browseAdapter = BrowseAdapter(
            onFolderClick = { folder -> viewModel.navigateToFolder(folder.path) },
            onVideoClick = { video -> openVideoDetail(video) }
        )
        binding.videoRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = browseAdapter
        }
    }

    private fun openVideoDetail(video: VideoItem) {
        val intent = Intent(this, VideoDetailActivity::class.java)
        intent.putExtra(VideoDetailActivity.EXTRA_VIDEO, video)
        startActivity(intent)
    }

    private fun observeViewModel() {
        viewModel.browseItems.observe(this) { items ->
            browseAdapter.submitList(items)
            binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.loading.observe(this) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.currentFolder.observe(this) { folderName ->
            supportActionBar?.subtitle = folderName
        }
    }

    private fun setupCast() {
        try {
            CastContext.getSharedInstance(this)
        } catch (e: Exception) {
            // Cast not available
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_logs -> {
                startActivity(Intent(this, LogActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!viewModel.navigateUp()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        AlertDialog.Builder(this)
            .setTitle(R.string.battery_optimization_title)
            .setMessage(R.string.battery_optimization_message)
            .setPositiveButton(R.string.battery_optimization_disable) { _, _ ->
                try {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    AppLogger.warn(TAG, "Direct battery optimization request not supported: ${e.message}")
                    openBatteryOptimizationSettings()
                }
            }
            .setNeutralButton(R.string.battery_optimization_settings) { _, _ ->
                openBatteryOptimizationSettings()
            }
            .setNegativeButton(R.string.battery_optimization_later, null)
            .show()
    }

    private fun openBatteryOptimizationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            AppLogger.warn(TAG, "Battery optimization settings not available: ${e.message}")
            try {
                startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            } catch (e2: Exception) {
                AppLogger.warn(TAG, "Battery saver settings not available: ${e2.message}")
                Toast.makeText(this, R.string.battery_optimization_open_settings, Toast.LENGTH_LONG).show()
            }
        }
    }
}
