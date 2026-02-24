package com.rem.downloader

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RemApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // FFmpeg MUST be initialized before YoutubeDL so yt-dlp can
        // locate the FFmpeg binary and merge audio+video streams.
        try {
            FFmpeg.getInstance().init(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            YoutubeDL.getInstance().init(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Update yt-dlp binary on a background thread (requires network)
        appScope.launch {
            try {
                YoutubeDL.getInstance().updateYoutubeDL(this@RemApplication, YoutubeDL.UpdateChannel._STABLE)
            } catch (_: Exception) { }
        }
    }
}