package com.rem.downloader.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Placeholder service required for foreground service declaration in manifest.
 * WorkManager handles the actual foreground service setup internally via ForegroundInfo.
 */
class DownloadForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf()
        return START_NOT_STICKY
    }
}
