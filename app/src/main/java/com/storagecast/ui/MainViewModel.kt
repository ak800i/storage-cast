package com.storagecast.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.storagecast.model.SubtitleTrack
import com.storagecast.model.VideoItem
import com.storagecast.subtitle.SubtitleExtractor
import com.storagecast.video.VideoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val videoRepository = VideoRepository(application.contentResolver)
    private val subtitleExtractor = SubtitleExtractor()

    private var allVideos: List<VideoItem> = emptyList()

    private val _videos = MutableLiveData<List<VideoItem>>()
    val videos: LiveData<List<VideoItem>> = _videos

    private val _loading = MutableLiveData<Boolean>()
    val loading: LiveData<Boolean> = _loading

    private val _subtitleTracks = MutableLiveData<Pair<VideoItem, List<SubtitleTrack>>>()
    val subtitleTracks: LiveData<Pair<VideoItem, List<SubtitleTrack>>> = _subtitleTracks

    private val _extractedSubtitle = MutableLiveData<File?>()
    val extractedSubtitle: LiveData<File?> = _extractedSubtitle

    fun loadVideos() {
        viewModelScope.launch {
            _loading.value = true
            val videoList = withContext(Dispatchers.IO) {
                videoRepository.getVideos()
            }
            allVideos = videoList
            _videos.value = videoList
            _loading.value = false
        }
    }

    fun filterVideos(query: String?) {
        if (query.isNullOrBlank()) {
            _videos.value = allVideos
        } else {
            val lowerQuery = query.lowercase()
            _videos.value = allVideos.filter { it.title.lowercase().contains(lowerQuery) }
        }
    }

    fun loadSubtitleTracks(video: VideoItem) {
        viewModelScope.launch {
            _loading.value = true
            val tracks = withContext(Dispatchers.IO) {
                subtitleExtractor.getSubtitleTracks(video.path)
            }
            _subtitleTracks.value = Pair(video, tracks)
            _loading.value = false
        }
    }

    fun extractSubtitle(videoPath: String, trackIndex: Int) {
        viewModelScope.launch {
            _loading.value = true
            val subtitleFile = withContext(Dispatchers.IO) {
                val outputDir = File(getApplication<Application>().cacheDir, "subtitles")
                outputDir.mkdirs()
                subtitleExtractor.extractSubtitleAsVtt(videoPath, trackIndex, outputDir)
            }
            _extractedSubtitle.value = subtitleFile
            _loading.value = false
        }
    }
}
