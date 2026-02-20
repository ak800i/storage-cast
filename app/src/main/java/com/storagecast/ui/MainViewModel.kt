package com.storagecast.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.storagecast.model.BrowseItem
import com.storagecast.model.VideoItem
import com.storagecast.video.VideoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val videoRepository = VideoRepository(application.contentResolver)

    private var allVideos: List<VideoItem> = emptyList()
    private var currentPath: String? = null
    private var isSearching = false

    private val _browseItems = MutableLiveData<List<BrowseItem>>()
    val browseItems: LiveData<List<BrowseItem>> = _browseItems

    private val _loading = MutableLiveData<Boolean>()
    val loading: LiveData<Boolean> = _loading

    private val _currentFolder = MutableLiveData<String?>()
    val currentFolder: LiveData<String?> = _currentFolder

    fun loadVideos() {
        viewModelScope.launch {
            _loading.value = true
            val videoList = withContext(Dispatchers.IO) {
                videoRepository.getVideos()
            }
            allVideos = videoList
            currentPath = null
            isSearching = false
            updateBrowseItems()
            _loading.value = false
        }
    }

    fun navigateToFolder(folderPath: String) {
        currentPath = folderPath
        isSearching = false
        updateBrowseItems()
    }

    fun navigateUp(): Boolean {
        if (isSearching) {
            isSearching = false
            updateBrowseItems()
            return true
        }
        val path = currentPath ?: return false
        val basePath = computeBasePath()
        if (path == basePath) {
            currentPath = null
            updateBrowseItems()
            return true
        }
        val parent = File(path).parent
        currentPath = if (parent != null && parent.startsWith(basePath)) parent else null
        updateBrowseItems()
        return true
    }

    fun filterVideos(query: String?) {
        if (query.isNullOrBlank()) {
            isSearching = false
            updateBrowseItems()
        } else {
            isSearching = true
            val lowerQuery = query.lowercase()
            val filtered = allVideos.filter { it.title.lowercase().contains(lowerQuery) }
            _browseItems.value = filtered.map { BrowseItem.Video(it) }
            _currentFolder.value = null
        }
    }

    private fun updateBrowseItems() {
        val path = currentPath
        if (path == null) {
            _browseItems.value = buildTopLevelItems()
            _currentFolder.value = null
        } else {
            _browseItems.value = buildItemsForPath(path)
            _currentFolder.value = File(path).name
        }
    }

    private fun computeBasePath(): String {
        val paths = allVideos.map { File(it.path).parent ?: "" }.filter { it.isNotEmpty() }
        if (paths.isEmpty()) return ""
        var common = paths[0]
        for (p in paths) {
            while (!p.startsWith(common)) {
                common = File(common).parent ?: ""
            }
        }
        return common
    }

    private fun buildTopLevelItems(): List<BrowseItem> {
        if (allVideos.isEmpty()) return emptyList()
        val basePath = computeBasePath()
        return buildItemsForPath(basePath)
    }

    private fun buildItemsForPath(path: String): List<BrowseItem> {
        val items = mutableListOf<BrowseItem>()
        val folders = mutableMapOf<String, Int>()
        val videosHere = mutableListOf<VideoItem>()

        for (video in allVideos) {
            val videoDir = File(video.path).parent ?: continue
            if (videoDir == path) {
                videosHere.add(video)
            } else if (videoDir.startsWith("$path/")) {
                val relative = videoDir.removePrefix("$path/")
                val topFolder = relative.split("/")[0]
                val folderPath = "$path/$topFolder"
                folders[folderPath] = (folders[folderPath] ?: 0) + 1
            }
        }

        folders.entries.sortedBy { File(it.key).name.lowercase() }.forEach { (folderPath, count) ->
            items.add(BrowseItem.Folder(File(folderPath).name, folderPath, count))
        }

        videosHere.sortedBy { it.title.lowercase() }.forEach { video ->
            items.add(BrowseItem.Video(video))
        }

        return items
    }
}
