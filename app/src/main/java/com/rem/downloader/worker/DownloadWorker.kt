package com.rem.downloader.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.rem.downloader.R
import com.rem.downloader.ui.MainActivity
import com.rem.downloader.util.ThemeManager
import com.rem.downloader.util.YtDlpHelper
import java.io.File

class DownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_URL = "url"
        const val KEY_FORMAT = "format"
        const val KEY_RESOLUTION = "resolution"
        const val KEY_FILENAME = "filename"
        const val KEY_PROGRESS = "progress"
        const val KEY_PROGRESS_MESSAGE = "progress_message"

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

        createNotificationChannel()
        setForeground(createForegroundInfo(0, "Starting download..."))

        val outputDir = getOutputDir()
        val ytDlp = YtDlpHelper(applicationContext)

        val downloadResult = ytDlp.download(
            url = url,
            format = format,
            resolution = resolution,
            outputDir = outputDir,
            customFilename = customFilename,
            onProgress = { percent, message ->
                if (percent in 0..100) {
                    updateProgressNotification(percent, message)
                    CoroutineScope(Dispatchers.IO).launch {
                        setProgress(workDataOf(
                            KEY_PROGRESS to percent,
                            KEY_PROGRESS_MESSAGE to message
                        ))
                    }
                }
            }
        )

        return downloadResult.fold(
            onSuccess = { file ->
                // Scan file so it appears in Gallery / Photos
                MediaScannerConnection.scanFile(
                    applicationContext,
                    arrayOf(file.absolutePath),
                    null,
                    null
                )
                showCompletionNotification(file)
                vibrateIfEnabled()
                Result.success()
            },
            onFailure = { e ->
                showErrorNotification(e.message ?: "Download failed")
                Result.failure()
            }
        )
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
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "REM video download progress"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundInfo(progress: Int, status: String): ForegroundInfo {
        val notification = buildProgressNotification(progress, status)
        // Android 14+ (API 34) requires explicit foreground service type
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
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
            applicationContext,
            "${applicationContext.packageName}.fileprovider",
            file
        )
        val mime = when {
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
