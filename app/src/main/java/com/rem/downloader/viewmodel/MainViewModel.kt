package com.rem.downloader.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.rem.downloader.model.DownloadOptions
import com.rem.downloader.model.EditConfig
import com.rem.downloader.model.OutputFormat
import com.rem.downloader.model.VideoInfo
import com.rem.downloader.util.YtDlpHelper
import com.rem.downloader.worker.DownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class UiState {
    object Idle : UiState()
    object Loading : UiState()
    data class VideoInfoLoaded(val info: VideoInfo) : UiState()
    data class Error(val message: String) : UiState()
}

sealed class PreviewState {
    object Idle : PreviewState()
    data class Downloading(val progress: Int) : PreviewState()
    data class Ready(val filePath: String) : PreviewState()
    object Failed : PreviewState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _previewState = MutableStateFlow<PreviewState>(PreviewState.Idle)
    val previewState: StateFlow<PreviewState> = _previewState.asStateFlow()

    // MediatorLiveData manually switches sources so the Activity always observes
    // exactly one download's LiveData — no accumulation across repeated downloads
    private var _prevWorkSource: LiveData<WorkInfo>? = null
    private val _workMediator = MediatorLiveData<WorkInfo>()
    val workInfoLive: LiveData<WorkInfo> = _workMediator

    // SharedFlow (no deduplication) so every download press always triggers the progress UI
    private val _downloadStarted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val downloadStarted: SharedFlow<Unit> = _downloadStarted.asSharedFlow()

    private val ytDlpHelper = YtDlpHelper(application)
    private var previewJob: Job? = null

    // ── Edit State ──
    var trimEnabled = false
    var trimStartMs: Long = 0L
    var trimEndMs: Long = 0L

    var cropEnabled = false
    var cropX: Int = 0
    var cropY: Int = 0
    var cropW: Int = 0
    var cropH: Int = 0
    // The video dimensions the crop overlay was drawn against (info.videoWidth/Height).
    // Stored so DownloadWorker can scale crop to the actual downloaded resolution.
    var cropRefWidth: Int = 0
    var cropRefHeight: Int = 0

    var outputFormat: OutputFormat = OutputFormat.MP4
    var gifFps: Int = 15
    var gifMaxWidth: Int = 480

    fun fetchVideoInfo(url: String) {
        if (!isValidUrl(url)) {
            _uiState.value = UiState.Error("Please enter a valid URL")
            return
        }
        cancelPreview()
        _uiState.value = UiState.Loading
        _previewState.value = PreviewState.Idle
        resetEditState()
        viewModelScope.launch(Dispatchers.IO) {
            val result = ytDlpHelper.getVideoInfo(url)
            result.onSuccess { info ->
                _uiState.value = UiState.VideoInfoLoaded(info)
                startPreviewDownload(url)
            }.onFailure { e ->
                _uiState.value = UiState.Error("Could not fetch video info: ${e.message}")
            }
        }
    }

    private fun startPreviewDownload(url: String) {
        val context = getApplication<Application>()
        val previewDir = File(context.filesDir, "rem_preview")
        // Clear previous preview
        previewDir.listFiles()?.forEach { it.delete() }

        _previewState.value = PreviewState.Downloading(0)
        previewJob = viewModelScope.launch(Dispatchers.IO) {
            val result = ytDlpHelper.downloadPreview(
                url = url,
                outputDir = previewDir,
                onProgress = { progress ->
                    _previewState.value = PreviewState.Downloading(progress)
                }
            )
            result.fold(
                onSuccess = { file -> _previewState.value = PreviewState.Ready(file.absolutePath) },
                onFailure = { _previewState.value = PreviewState.Failed }
            )
        }
    }

    private fun cancelPreview() {
        previewJob?.cancel()
        previewJob = null
    }

    fun startDownload(options: DownloadOptions) {
        val editConfig = EditConfig(
            trimEnabled = trimEnabled,
            trimStartMs = trimStartMs,
            trimEndMs = trimEndMs,
            cropEnabled = cropEnabled,
            cropX = cropX,
            cropY = cropY,
            cropW = cropW,
            cropH = cropH,
            outputFormat = outputFormat,
            gifFps = gifFps,
            gifMaxWidth = gifMaxWidth
        )

        val inputData = Data.Builder()
            .putString(DownloadWorker.KEY_URL, options.url)
            .putString(DownloadWorker.KEY_FORMAT, options.format)
            .putString(DownloadWorker.KEY_RESOLUTION, options.resolution)
            .putString(DownloadWorker.KEY_FILENAME, options.customFilename ?: "")
            // Edit config
            .putBoolean(DownloadWorker.KEY_TRIM_ENABLED, editConfig.trimEnabled)
            .putLong(DownloadWorker.KEY_TRIM_START_MS, editConfig.trimStartMs)
            .putLong(DownloadWorker.KEY_TRIM_END_MS, editConfig.trimEndMs)
            .putBoolean(DownloadWorker.KEY_CROP_ENABLED, editConfig.cropEnabled)
            .putInt(DownloadWorker.KEY_CROP_X, editConfig.cropX)
            .putInt(DownloadWorker.KEY_CROP_Y, editConfig.cropY)
            .putInt(DownloadWorker.KEY_CROP_W, editConfig.cropW)
            .putInt(DownloadWorker.KEY_CROP_H, editConfig.cropH)
            .putInt(DownloadWorker.KEY_CROP_REF_WIDTH, cropRefWidth)
            .putInt(DownloadWorker.KEY_CROP_REF_HEIGHT, cropRefHeight)
            .putString(DownloadWorker.KEY_OUTPUT_FORMAT, editConfig.outputFormat.name)
            .putInt(DownloadWorker.KEY_GIF_FPS, editConfig.gifFps)
            .putInt(DownloadWorker.KEY_GIF_MAX_WIDTH, editConfig.gifMaxWidth)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .addTag("rem_download")
            .build()

        val wm = WorkManager.getInstance(getApplication())
        wm.enqueue(workRequest)
        val workLiveData = wm.getWorkInfoByIdLiveData(workRequest.id)
        _prevWorkSource?.let { _workMediator.removeSource(it) }
        _workMediator.addSource(workLiveData) { _workMediator.value = it }
        _prevWorkSource = workLiveData
        _downloadStarted.tryEmit(Unit)
    }

    fun resetEditState() {
        trimEnabled = false
        trimStartMs = 0L
        trimEndMs = 0L
        cropEnabled = false
        cropX = 0
        cropY = 0
        cropW = 0
        cropH = 0
        cropRefWidth = 0
        cropRefHeight = 0
        outputFormat = OutputFormat.MP4
        gifFps = 15
        gifMaxWidth = 480
    }

    fun clearState() {
        cancelPreview()
        _previewState.value = PreviewState.Idle
        // Delete any cached preview file
        val previewDir = File(getApplication<Application>().filesDir, "rem_preview")
        previewDir.listFiles()?.forEach { it.delete() }
        _uiState.value = UiState.Idle
        resetEditState()
    }

    fun estimateGifSize(): String {
        val durationSec = (trimEndMs - trimStartMs).coerceAtLeast(1000L) / 1000.0
        val framesCount = durationSec * gifFps
        val bytesPerFrame = gifMaxWidth * 0.6
        val estimatedBytes = (framesCount * bytesPerFrame * 1000).toLong()
        return formatFileSize(estimatedBytes.coerceAtLeast(100_000))
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }

    private fun isValidUrl(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://")
    }
}
