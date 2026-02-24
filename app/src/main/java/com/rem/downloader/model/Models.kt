package com.rem.downloader.model

data class VideoInfo(
    val title: String,
    val uploader: String,
    val duration: Long,           // seconds
    val thumbnailUrl: String,
    val directStreamUrl: String?,  // null if only downloadable via yt-dlp
    val availableResolutions: List<Int>,
    val sourceUrl: String,
    val platform: String
)

data class DownloadOptions(
    val url: String,
    val format: String,           // yt-dlp format string
    val resolution: String,       // e.g. "1080", "720", "best"
    val customFilename: String?
)
