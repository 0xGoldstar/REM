package com.rem.downloader.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.LiveData
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.rem.downloader.model.DownloadOptions
import com.rem.downloader.model.VideoInfo
import com.rem.downloader.util.YtDlpHelper
import com.rem.downloader.worker.DownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class UiState {
    object Idle : UiState()
    object Loading : UiState()
    data class VideoInfoLoaded(val info: VideoInfo) : UiState()
    data class Error(val message: String) : UiState()
    object DownloadQueued : UiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var _downloadWorkInfo: LiveData<WorkInfo>? = null
    val downloadWorkInfo: LiveData<WorkInfo>? get() = _downloadWorkInfo

    private val ytDlpHelper = YtDlpHelper(application)

    fun fetchVideoInfo(url: String) {
        if (!isValidUrl(url)) {
            _uiState.value = UiState.Error("Please enter a valid URL")
            return
        }
        _uiState.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val result = ytDlpHelper.getVideoInfo(url)
            result.onSuccess { info ->
                _uiState.value = UiState.VideoInfoLoaded(info)
            }.onFailure { e ->
                _uiState.value = UiState.Error("Could not fetch video info: ${e.message}")
            }
        }
    }

    fun startDownload(options: DownloadOptions) {
        val inputData = Data.Builder()
            .putString(DownloadWorker.KEY_URL, options.url)
            .putString(DownloadWorker.KEY_FORMAT, options.format)
            .putString(DownloadWorker.KEY_RESOLUTION, options.resolution)
            .putString(DownloadWorker.KEY_FILENAME, options.customFilename ?: "")
            .build()

        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .addTag("rem_download")
            .build()

        val wm = WorkManager.getInstance(getApplication())
        wm.enqueue(workRequest)
        _downloadWorkInfo = wm.getWorkInfoByIdLiveData(workRequest.id)
        _uiState.value = UiState.DownloadQueued
    }

    fun clearState() {
        _uiState.value = UiState.Idle
    }

    private fun isValidUrl(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://")
    }
}
