package com.rem.downloader.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.rem.downloader.R
import com.rem.downloader.model.EditConfig
import com.rem.downloader.model.OutputFormat
import com.rem.downloader.ui.MainActivity
import com.rem.downloader.util.FFmpegHelper
import com.rem.downloader.util.FFmpegRunner
import com.rem.downloader.util.ThemeManager
import com.rem.downloader.util.YtDlpHelper
import java.io.File

class DownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DownloadWorker"

        const val KEY_URL = "url"
        const val KEY_FORMAT = "format"
        const val KEY_RESOLUTION = "resolution"
        const val KEY_FILENAME = "filename"
        const val KEY_PROGRESS = "progress"
        const val KEY_PROGRESS_MESSAGE = "progress_message"

        // Edit config keys
        const val KEY_TRIM_ENABLED = "trim_enabled"
        const val KEY_TRIM_START_MS = "trim_start_ms"
        const val KEY_TRIM_END_MS = "trim_end_ms"
        const val KEY_CROP_ENABLED = "crop_enabled"
        const val KEY_CROP_X = "crop_x"
        const val KEY_CROP_Y = "crop_y"
        const val KEY_CROP_W = "crop_w"
        const val KEY_CROP_H = "crop_h"
        const val KEY_OUTPUT_FORMAT = "output_format"
        const val KEY_GIF_FPS = "gif_fps"
        const val KEY_GIF_MAX_WIDTH = "gif_max_width"
        // Reference dimensions the crop was drawn against (info.videoWidth/Height).
        // Used to scale crop coords to the actual downloaded video resolution.
        const val KEY_CROP_REF_WIDTH = "crop_ref_width"
        const val KEY_CROP_REF_HEIGHT = "crop_ref_height"

        private const val CHANNEL_ID = "rem_downloads"
        private const val CHANNEL_NAME = "REM Downloads"
        private const val NOTIFICATION_ID = 1001
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val format = inputData.getString(KEY_FORMAT) ?: "bestvideo+bestaudio/best"
        val resolution = inputData.getString(KEY_RESOLUTION) ?: "best"
        val customFilename = inputData.getString(KEY_FILENAME)?.takeIf { it.isNotBlank() }

        val editConfig = EditConfig(
            trimEnabled = inputData.getBoolean(KEY_TRIM_ENABLED, false),
            trimStartMs = inputData.getLong(KEY_TRIM_START_MS, 0L),
            trimEndMs = inputData.getLong(KEY_TRIM_END_MS, 0L),
            cropEnabled = inputData.getBoolean(KEY_CROP_ENABLED, false),
            cropX = inputData.getInt(KEY_CROP_X, 0),
            cropY = inputData.getInt(KEY_CROP_Y, 0),
            cropW = inputData.getInt(KEY_CROP_W, 0),
            cropH = inputData.getInt(KEY_CROP_H, 0),
            outputFormat = try {
                OutputFormat.valueOf(inputData.getString(KEY_OUTPUT_FORMAT) ?: "MP4")
            } catch (_: Exception) { OutputFormat.MP4 },
            gifFps = inputData.getInt(KEY_GIF_FPS, 15),
            gifMaxWidth = inputData.getInt(KEY_GIF_MAX_WIDTH, 480)
        )
        val cropRefWidth = inputData.getInt(KEY_CROP_REF_WIDTH, 0)
        val cropRefHeight = inputData.getInt(KEY_CROP_REF_HEIGHT, 0)

        Log.d(TAG, "doWork: hasEdits=${editConfig.hasEdits} trim=${editConfig.trimEnabled} crop=${editConfig.cropEnabled} format=${editConfig.outputFormat}")

        createNotificationChannel()
        setForeground(createForegroundInfo(0, "Starting download..."))

        val outputDir = getOutputDir()
        val ytDlp = YtDlpHelper(applicationContext)

        return if (editConfig.hasEdits) {
            downloadWithEdits(url, format, resolution, customFilename, outputDir, ytDlp, editConfig, cropRefWidth, cropRefHeight)
        } else {
            downloadDirect(url, format, resolution, customFilename, outputDir, ytDlp)
        }
    }

    private suspend fun downloadDirect(
        url: String, format: String, resolution: String,
        customFilename: String?, outputDir: File, ytDlp: YtDlpHelper
    ): Result {
        Log.d(TAG, "downloadDirect to: ${outputDir.absolutePath}")
        setProgressAsync(workDataOf(KEY_PROGRESS to 0, KEY_PROGRESS_MESSAGE to "Downloading..."))
        var lastProgress = 0
        val downloadResult = ytDlp.download(
            url = url, format = format, resolution = resolution,
            outputDir = outputDir, customFilename = customFilename,
            onProgress = { percent, message ->
                if (percent in 0..100 && percent >= lastProgress) {
                    lastProgress = percent
                    updateProgressNotification(percent, message)
                    setProgressAsync(workDataOf(KEY_PROGRESS to percent, KEY_PROGRESS_MESSAGE to "Downloading..."))
                }
            }
        )

        return downloadResult.fold(
            onSuccess = { file ->
                Log.d(TAG, "downloadDirect success: ${file.absolutePath}")
                MediaScannerConnection.scanFile(applicationContext, arrayOf(file.absolutePath), null, null)
                showCompletionNotification(file)
                vibrateIfEnabled()
                Result.success()
            },
            onFailure = { e ->
                Log.e(TAG, "downloadDirect failed", e)
                showErrorNotification(e.message ?: "Download failed")
                Result.failure()
            }
        )
    }

    private suspend fun downloadWithEdits(
        url: String, format: String, resolution: String,
        customFilename: String?, outputDir: File, ytDlp: YtDlpHelper,
        editConfig: EditConfig, cropRefWidth: Int, cropRefHeight: Int
    ): Result {
        // Phase 1: Download to temp (0-80%)
        val tempDir = File(applicationContext.filesDir, "rem_temp")
        tempDir.mkdirs()
        Log.d(TAG, "downloadWithEdits: tempDir=${tempDir.absolutePath} exists=${tempDir.exists()}")

        updateProgressNotification(0, "Downloading...")
        setProgressAsync(workDataOf(KEY_PROGRESS to 0, KEY_PROGRESS_MESSAGE to "Downloading..."))

        var lastDownloadProgress = 0
        val downloadResult = ytDlp.download(
            url = url, format = format, resolution = resolution,
            outputDir = tempDir, customFilename = customFilename,
            onProgress = { percent, message ->
                val scaledPercent = (percent * 0.8).toInt().coerceIn(0, 80)
                if (scaledPercent >= lastDownloadProgress) {
                    lastDownloadProgress = scaledPercent
                    updateProgressNotification(scaledPercent, "Downloading: $message")
                    setProgressAsync(workDataOf(KEY_PROGRESS to scaledPercent, KEY_PROGRESS_MESSAGE to "Downloading..."))
                }
            }
        )

        val tempFile = downloadResult.getOrElse { e ->
            Log.e(TAG, "downloadWithEdits: yt-dlp download to temp failed", e)
            showErrorNotification("Download failed: ${e.message}")
            return Result.failure()
        }

        Log.d(TAG, "downloadWithEdits: tempFile=${tempFile.absolutePath} exists=${tempFile.exists()} size=${tempFile.length()}")

        if (!tempFile.exists() || tempFile.length() == 0L) {
            Log.e(TAG, "downloadWithEdits: temp file missing or empty")
            showErrorNotification("Download failed: output file not found")
            return Result.failure()
        }

        // Phase 2: Process with FFmpeg (80-100%)
        updateProgressNotification(80, "Processing edits...")
        setProgress(workDataOf(KEY_PROGRESS to 80, KEY_PROGRESS_MESSAGE to "Processing edits..."))

        // Scale crop coordinates from the reference dimensions (used when the crop was drawn)
        // to the actual downloaded video dimensions. This handles cases where the downloaded
        // format differs in resolution or aspect ratio from the yt-dlp info dimensions.
        val scaledEditConfig = if (editConfig.cropEnabled && cropRefWidth > 0 && cropRefHeight > 0) {
            val retriever = MediaMetadataRetriever()
            var actualW = 0
            var actualH = 0
            try {
                retriever.setDataSource(tempFile.absolutePath)
                actualW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                actualH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                // Some devices swap width/height for rotated videos; check rotation.
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rotation == 90 || rotation == 270) { val tmp = actualW; actualW = actualH; actualH = tmp }
            } catch (e: Exception) {
                Log.w(TAG, "MediaMetadataRetriever failed: ${e.message}")
            } finally {
                retriever.release()
            }
            Log.d(TAG, "Crop scale: ref=${cropRefWidth}x${cropRefHeight} actual=${actualW}x${actualH}")
            if (actualW > 0 && actualH > 0 && (actualW != cropRefWidth || actualH != cropRefHeight)) {
                val scaleX = actualW.toFloat() / cropRefWidth
                val scaleY = actualH.toFloat() / cropRefHeight
                val newX = (editConfig.cropX * scaleX).toInt().coerceAtLeast(0)
                val newY = (editConfig.cropY * scaleY).toInt().coerceAtLeast(0)
                val newW = ((editConfig.cropW * scaleX).toInt() and 0x7FFFFFFE).coerceAtLeast(2)
                val newH = ((editConfig.cropH * scaleY).toInt() and 0x7FFFFFFE).coerceAtLeast(2)
                // Clamp so crop doesn't extend past the video
                val clampedX = newX.coerceAtMost((actualW - newW).coerceAtLeast(0))
                val clampedY = newY.coerceAtMost((actualH - newH).coerceAtLeast(0))
                Log.d(TAG, "Crop scaled: (${editConfig.cropX},${editConfig.cropY} ${editConfig.cropW}x${editConfig.cropH}) -> ($clampedX,$clampedY ${newW}x${newH})")
                editConfig.copy(cropX = clampedX, cropY = clampedY, cropW = newW, cropH = newH)
            } else {
                editConfig
            }
        } else {
            editConfig
        }

        val ffmpegBinary = FFmpegHelper.findFFmpegBinary(applicationContext)
        Log.d(TAG, "downloadWithEdits: ffmpegBinary=$ffmpegBinary")
        if (ffmpegBinary == null) {
            tempFile.delete()
            Log.e(TAG, "downloadWithEdits: FFmpeg binary not found under ${applicationContext.noBackupFilesDir}")
            showErrorNotification("FFmpeg not found — try downloading a video first to extract it")
            return Result.failure()
        }

        val ext = if (scaledEditConfig.outputFormat == OutputFormat.GIF) "gif" else tempFile.extension.ifEmpty { "mp4" }
        val baseNameRaw = customFilename ?: tempFile.nameWithoutExtension
        val suffix = if (ThemeManager.appendEditSuffix(applicationContext)) buildString {
            if (scaledEditConfig.trimEnabled) append("_trimmed")
            if (scaledEditConfig.cropEnabled) append("_cropped")
            if (scaledEditConfig.outputFormat == OutputFormat.GIF) append("_gif")
        } else ""
        var outputFile = File(outputDir, "${baseNameRaw}${suffix}.${ext}")
        var counter = 1
        while (outputFile.exists()) {
            outputFile = File(outputDir, "${baseNameRaw}${suffix}_${counter}.${ext}")
            counter++
        }

        val ffmpegArgs = FFmpegHelper.buildCombinedCommand(tempFile.absolutePath, outputFile.absolutePath, scaledEditConfig)
        Log.d(TAG, "downloadWithEdits: ffmpegArgs=${ffmpegArgs.joinToString(" ")}")

        return try {
            val exitCode = withContext(Dispatchers.IO) {
                FFmpegRunner.execute(applicationContext, ffmpegBinary, ffmpegArgs) { pct ->
                    val scaledPercent = 80 + (pct * 0.2).toInt()
                    updateProgressNotification(scaledPercent, "Processing: $pct%")
                    setProgressAsync(workDataOf(KEY_PROGRESS to scaledPercent, KEY_PROGRESS_MESSAGE to "Processing..."))
                }
            }

            tempFile.delete()
            Log.d(TAG, "downloadWithEdits: FFmpeg exit=$exitCode outputExists=${outputFile.exists()} size=${outputFile.length()}")

            if (exitCode == 0 && outputFile.exists() && outputFile.length() > 0) {
                MediaScannerConnection.scanFile(applicationContext, arrayOf(outputFile.absolutePath), null, null)
                showCompletionNotification(outputFile)
                vibrateIfEnabled()
                Result.success()
            } else {
                outputFile.delete()
                Log.e(TAG, "downloadWithEdits: FFmpeg failed with exit code $exitCode")
                showErrorNotification("Processing failed (exit $exitCode)")
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadWithEdits: exception during FFmpeg", e)
            tempFile.delete()
            showErrorNotification("Processing failed: ${e.message}")
            Result.failure()
        }
    }

    private fun getOutputDir(): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val remDir = File(downloadsDir, "REM")
        remDir.mkdirs()
        return remDir
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "REM video download progress"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundInfo(progress: Int, status: String): ForegroundInfo {
        val notification = buildProgressNotification(progress, status)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildProgressNotification(progress: Int, status: String) =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("REM — Downloading")
            .setContentText(if (progress > 0) "$progress% — $status" else status)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun updateProgressNotification(progress: Int, message: String) {
        val notification = buildProgressNotification(progress, message)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(file: File) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Download complete")
            .setContentText(file.name)
            .setSubText("Saved to Downloads/REM")
            .setSmallIcon(R.drawable.ic_download_done)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openFileIntent(file))
            .build()
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun showErrorNotification(error: String) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Download failed")
            .setContentText(error)
            .setSmallIcon(R.drawable.ic_download_error)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppIntent())
            .build()
        notificationManager.notify(NOTIFICATION_ID + 2, notification)
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun openFileIntent(file: File): PendingIntent {
        val uri = FileProvider.getUriForFile(
            applicationContext, "${applicationContext.packageName}.fileprovider", file
        )
        val mime = when {
            file.name.endsWith(".gif", true) -> "image/gif"
            file.name.endsWith(".mp4", true) || file.name.endsWith(".webm", true) ||
                file.name.endsWith(".mkv", true) -> "video/*"
            file.name.endsWith(".m4a", true) || file.name.endsWith(".mp3", true) ||
                file.name.endsWith(".opus", true) -> "audio/*"
            else -> "*/*"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return PendingIntent.getActivity(
            applicationContext, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun vibrateIfEnabled() {
        if (!ThemeManager.vibrateOnDownload(applicationContext)) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = applicationContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java)
        return PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
