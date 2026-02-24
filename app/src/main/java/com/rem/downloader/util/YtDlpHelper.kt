package com.rem.downloader.util

import android.content.Context
import android.util.Log
import com.rem.downloader.model.VideoInfo
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

class YtDlpHelper(private val context: Context) {

    companion object {
        private const val TAG = "YtDlpHelper"
    }

    fun getVideoInfo(url: String): Result<VideoInfo> {
        return try {
            val info = YoutubeDL.getInstance().getInfo(url)
            Result.success(mapToVideoInfo(info, url))
        } catch (e: Exception) {
            Log.e(TAG, "getVideoInfo error: ${e.message}")
            Result.failure(e)
        }
    }

    fun download(
        url: String,
        format: String,
        resolution: String,
        outputDir: File,
        customFilename: String?,
        onProgress: (Int, String) -> Unit
    ): Result<File> {
        return try {
            outputDir.mkdirs()

            val isAudioOnly = format.contains("bestaudio") && !format.contains("bestvideo")

            val request = YoutubeDLRequest(url).apply {
                addOption("-f", buildFormatSelector(format, resolution))
                addOption(
                    "-o", File(
                        outputDir,
                        if (!customFilename.isNullOrBlank()) "${sanitizeFilename(customFilename)}.%(ext)s"
                        else "%(title)s.%(ext)s"
                    ).absolutePath
                )
                addOption("--no-playlist")
                addOption("--socket-timeout", "30")
                addOption("--retries", "3")
                addOption("--windows-filenames")

                if (!isAudioOnly) {
                    // Prefer H.264/H.265 in mp4 — widely compatible with all players.
                    // Falls back to vp9/av1 if avc/hevc aren't available.
                    addOption("-S", "vcodec:h265,vcodec:h264,ext:mp4:m4a")
                    addOption("--merge-output-format", "mp4")
                }
            }

            val existingFiles = outputDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()

            YoutubeDL.getInstance().execute(request) { progress, _, line ->
                onProgress(progress.toInt(), line)
            }

            val allNewFiles = outputDir.listFiles()
                ?.filter { it.absolutePath !in existingFiles }
                ?.filter { !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?: emptyList()

            // Exclude yt-dlp format-tagged intermediates like "title.f137.mp4" / "title.f140.m4a"
            // These are temp files created before FFmpeg merges them into the final output.
            val finalFiles = allNewFiles.filterNot {
                it.name.matches(Regex(".*\\.f\\d+\\.[a-zA-Z0-9]+$"))
            }

            val downloadedFile = (finalFiles.ifEmpty { allNewFiles })
                .maxByOrNull { it.lastModified() }
                ?: outputDir.listFiles()
                    ?.filter { !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                    ?.filterNot { it.name.matches(Regex(".*\\.f\\d+\\.[a-zA-Z0-9]+$")) }
                    ?.maxByOrNull { it.lastModified() }

            if (downloadedFile != null && downloadedFile.exists()) {
                Result.success(downloadedFile)
            } else {
                Result.failure(Exception("Download completed but file not found"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "download error: ${e.message}")
            Result.failure(e)
        }
    }

    private fun mapToVideoInfo(
        info: com.yausername.youtubedl_android.mapper.VideoInfo,
        sourceUrl: String
    ): VideoInfo {
        val resolutions = info.formats
            ?.mapNotNull { it.height }
            ?.filter { it > 0 }
            ?.sortedDescending()
            ?.distinct()
            ?: emptyList()

        return VideoInfo(
            title = info.title ?: "Unknown Title",
            uploader = info.uploader ?: "",
            duration = info.duration?.toLong() ?: 0L,
            thumbnailUrl = info.thumbnail ?: "",
            directStreamUrl = info.url,
            availableResolutions = resolutions,
            sourceUrl = sourceUrl,
            platform = info.extractor ?: "Unknown"
        )
    }

    private fun buildFormatSelector(format: String, resolution: String): String {
        val isAudioOnly = format.contains("bestaudio") && !format.contains("bestvideo")
        val isVideoOnly = !format.contains("bestaudio")

        return when {
            isAudioOnly -> "bestaudio[ext=m4a]/bestaudio/best"
            isVideoOnly -> {
                if (resolution == "best") "bestvideo/best"
                else "bestvideo[height<=$resolution]/best[height<=$resolution]"
            }
            // Video + Audio: prefer m4a audio (AAC) so it's compatible in an mp4 container.
            // No ext filter on video — restricting video to ext=mp4 breaks sites that only
            // serve VP9/webm video, causing the whole expression to fall through incorrectly.
            resolution == "best" ->
                "bestvideo+bestaudio[ext=m4a]/bestvideo+bestaudio/best"
            else ->
                "bestvideo[height<=$resolution]+bestaudio[ext=m4a]/bestvideo[height<=$resolution]+bestaudio/best[height<=$resolution]/best"
        }
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }
}
