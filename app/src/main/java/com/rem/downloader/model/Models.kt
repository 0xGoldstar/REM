package com.rem.downloader.model

data class VideoInfo(
    val title: String,
    val uploader: String,
    val duration: Long,           // seconds
    val thumbnailUrl: String,
    val directStreamUrl: String?,  // null if only downloadable via yt-dlp
    val availableResolutions: List<Int>,
    val sourceUrl: String,
    val platform: String,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0
)

data class DownloadOptions(
    val url: String,
    val format: String,           // yt-dlp format string
    val resolution: String,       // e.g. "1080", "720", "best"
    val customFilename: String?
)

// ── Output Format ──

enum class OutputFormat { MP4, GIF }

// ── Edit Config ──

data class EditConfig(
    val trimEnabled: Boolean = false,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val cropEnabled: Boolean = false,
    val cropX: Int = 0,
    val cropY: Int = 0,
    val cropW: Int = 0,
    val cropH: Int = 0,
    val outputFormat: OutputFormat = OutputFormat.MP4,
    val gifFps: Int = 15,
    val gifMaxWidth: Int = 480
) {
    val hasEdits: Boolean
        get() = trimEnabled || cropEnabled || outputFormat == OutputFormat.GIF
}

// ── Crop ──

enum class CropRatio(val label: String, val widthRatio: Float, val heightRatio: Float) {
    FREE("Free", 0f, 0f),
    RATIO_16_9("16:9", 16f, 9f),
    RATIO_9_16("9:16", 9f, 16f),
    RATIO_1_1("1:1", 1f, 1f),
    RATIO_4_3("4:3", 4f, 3f),
    RATIO_3_4("3:4", 3f, 4f),
    RATIO_4_5("4:5", 4f, 5f);

    val isFixed get() = widthRatio > 0f && heightRatio > 0f
    val aspect get() = if (isFixed) widthRatio / heightRatio else 0f
}
