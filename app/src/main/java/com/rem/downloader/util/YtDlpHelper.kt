package com.rem.downloader.util

import android.content.Context
import android.util.Log
import com.rem.downloader.model.VideoEntry
import com.rem.downloader.model.VideoInfo
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class YtDlpHelper(private val context: Context) {

    companion object {
        private const val TAG = "YtDlpHelper"
    }

    suspend fun getVideoInfo(url: String): Result<VideoInfo> {
        return try {
            coroutineScope {
                // Run both calls in parallel — getInfo and tryGetEntries are independent
                // and each takes a few seconds, so parallelising halves the wait time.
                val infoDeferred = async { YoutubeDL.getInstance().getInfo(url) }
                val entriesDeferred = async { tryGetEntries(url) }
                val info = infoDeferred.await()
                val entries = entriesDeferred.await()
                Result.success(mapToVideoInfo(info, url, entries))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getVideoInfo error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Uses yt-dlp flat-playlist mode to list every entry in a multi-video post.
     * Returns an empty list for single-video URLs or on any error.
     */
    private fun tryGetEntries(url: String): List<VideoEntry> {
        return try {
            val sep = "|||"
            val lines = mutableListOf<String>()
            val request = YoutubeDLRequest(url).apply {
                addOption("--flat-playlist")
                addOption("--print", "%(title)s$sep%(thumbnail)s$sep%(duration)s")
                addOption("--socket-timeout", "15")
                addOption("--no-warnings")
            }
            YoutubeDL.getInstance().execute(request) { _, _, line ->
                val t = line.trim()
                if (t.contains(sep)) lines.add(t)
            }

            Log.d(TAG, "tryGetEntries: got ${lines.size} entries for $url")

            // A single result is a plain video — no selector needed
            if (lines.size <= 1) return emptyList()

            lines.mapIndexed { index, line ->
                val parts = line.split(sep, limit = 3)
                VideoEntry(
                    title = parts.getOrNull(0)
                        ?.takeIf { it.isNotEmpty() && it != "NA" }
                        ?: "Video ${index + 1}",
                    thumbnailUrl = parts.getOrNull(1)
                        ?.takeIf { it.isNotEmpty() && it != "NA" }
                        ?: "",
                    duration = parts.getOrNull(2)?.toDoubleOrNull()?.toLong() ?: 0L
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "tryGetEntries: ${e.message}")
            emptyList()
        }
    }

    fun download(
        url: String,
        format: String,
        resolution: String,
        outputDir: File,
        customFilename: String?,
        playlistIndex: Int = 0,
        onProgress: (Int, String) -> Unit
    ): Result<File> {
        return try {
            downloadInternal(url, format, resolution, outputDir, customFilename, playlistIndex, onProgress, suffix = "", attempt = 0)
        } catch (e: Exception) {
            Log.e(TAG, "download error: ${e.message}")
            Result.failure(e)
        }
    }

    private fun downloadInternal(
        url: String,
        format: String,
        resolution: String,
        outputDir: File,
        customFilename: String?,
        playlistIndex: Int,
        onProgress: (Int, String) -> Unit,
        suffix: String,
        attempt: Int
    ): Result<File> {
        outputDir.mkdirs()

        val isAudioOnly = format.contains("bestaudio") && !format.contains("bestvideo")

        val outputTemplate = if (!customFilename.isNullOrBlank()) {
            "${sanitizeFilename(customFilename)}${suffix}.%(ext)s"
        } else {
            "%(title)s${suffix}.%(ext)s"
        }

        val request = YoutubeDLRequest(url).apply {
            addOption("-f", buildFormatSelector(format, resolution))
            addOption("-o", File(outputDir, outputTemplate).absolutePath)
            addOption("--socket-timeout", "30")
            addOption("--retries", "3")
            addOption("--windows-filenames")
            addOption("--no-overwrites")

            // For a specific entry in a multi-video post use --playlist-items (1-based).
            // For a single-video URL (or first item by default) use --no-playlist.
            if (playlistIndex > 0) {
                addOption("--playlist-items", "$playlistIndex")
            } else {
                addOption("--no-playlist")
            }

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

        val downloadedFile = (finalFiles.ifEmpty { allNewFiles }).maxByOrNull { it.lastModified() }

        return when {
            downloadedFile != null && downloadedFile.exists() -> Result.success(downloadedFile)
            attempt < 99 -> {
                // --no-overwrites caused yt-dlp to skip because a file with this name already
                // exists. Retry with an incremented suffix to create a distinct copy.
                val nextAttempt = attempt + 1
                Log.d(TAG, "File already exists, retrying with suffix _$nextAttempt")
                downloadInternal(url, format, resolution, outputDir, customFilename, playlistIndex, onProgress, "_$nextAttempt", nextAttempt)
            }
            else -> Result.failure(Exception("Download completed but file not found"))
        }
    }

    /**
     * Downloads the lowest-quality version of a video for local preview purposes.
     * Caps at 360p, prefers mp4 container. Saves to [outputDir]/preview.<ext>.
     */
    fun downloadPreview(
        url: String,
        outputDir: File,
        playlistIndex: Int = 0,
        onProgress: (Int) -> Unit
    ): Result<File> {
        return try {
            outputDir.mkdirs()

            val request = YoutubeDLRequest(url).apply {
                // Prefer a pre-muxed (single-file) stream so progress is one continuous
                // 0→100% pass. Separate video+audio streams cause the bar to reset.
                addOption(
                    "-f",
                    "worst[vcodec!=none][acodec!=none][height<=360][ext=mp4]" +
                    "/worst[vcodec!=none][acodec!=none][height<=360]" +
                    "/worst[vcodec!=none][acodec!=none]" +
                    "/worst[ext=mp4]" +
                    "/worst"
                )
                addOption("-o", File(outputDir, "preview.%(ext)s").absolutePath)
                addOption("--socket-timeout", "30")
                addOption("--retries", "3")
                addOption("--merge-output-format", "mp4")

                if (playlistIndex > 0) {
                    addOption("--playlist-items", "$playlistIndex")
                } else {
                    addOption("--no-playlist")
                }
            }

            val existingPaths = outputDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()

            YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                onProgress(progress.toInt())
            }

            val previewFile = outputDir.listFiles()
                ?.filter { it.absolutePath !in existingPaths }
                ?.filter { !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.lastModified() }
                ?: outputDir.listFiles()
                    ?.filter { !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                    ?.maxByOrNull { it.lastModified() }

            if (previewFile != null && previewFile.exists()) {
                Result.success(previewFile)
            } else {
                Result.failure(Exception("Preview file not found after download"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadPreview error: ${e.message}")
            Result.failure(e)
        }
    }

    private fun mapToVideoInfo(
        info: com.yausername.youtubedl_android.mapper.VideoInfo,
        sourceUrl: String,
        entries: List<VideoEntry> = emptyList()
    ): VideoInfo {
        val isMulti = entries.size > 1

        val formats = info.formats ?: emptyList()
        val resolutions = formats
            .mapNotNull { it.height }
            .filter { it > 0 }
            .sortedDescending()
            .distinct()

        // Best format = highest resolution with both width and height available
        val bestFormat = formats
            .filter { it.width > 0 && it.height > 0 }
            .maxByOrNull { it.width * it.height }

        // For multi-video posts the library returns playlist-level info (no direct video data).
        // Use the first entry's title/thumbnail/duration for the initial display instead.
        val firstEntry = entries.firstOrNull()
        val displayTitle = if (isMulti && firstEntry != null) firstEntry.title
                           else (info.title ?: "Unknown Title")
        val displayThumbnail = if (isMulti && firstEntry != null && firstEntry.thumbnailUrl.isNotEmpty())
                               firstEntry.thumbnailUrl
                               else (info.thumbnail ?: "")
        // Prefer entry duration; fall back to library's value (e.g. Twitter gives it at video level)
        val displayDuration = if (isMulti && firstEntry != null && firstEntry.duration > 0)
                                  firstEntry.duration
                              else info.duration.toLong()

        return VideoInfo(
            title = displayTitle,
            uploader = info.uploader ?: "",
            duration = displayDuration,
            thumbnailUrl = displayThumbnail,
            directStreamUrl = info.url,
            availableResolutions = resolutions,
            sourceUrl = sourceUrl,
            platform = info.extractor ?: "Unknown",
            videoWidth = bestFormat?.width ?: 0,
            videoHeight = bestFormat?.height ?: 0,
            videoCount = if (isMulti) entries.size else 1,
            entries = if (isMulti) entries else emptyList()
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
