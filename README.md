# REM — Record, Extract, Manage

REM is a clean, dark-themed Android video downloader and editor. Paste a URL, preview the video, trim/crop it, pick your format, and download — all in one flow. Powered by **yt-dlp** and **FFmpeg** under the hood.

---

## Features

### Downloading
- **Paste any URL** from YouTube, Twitter/X, Instagram, TikTok, Reddit, Facebook, Vimeo, and 1,000+ other sites
- **Format options**: Video + Audio / Video Only
- **Resolution selector** populated from the video's actual available formats
- **Rename file** before download (optional)
- **Background download** via WorkManager with live progress bar and notification
- **Downloads to `Downloads/REM/`** on device storage
- **Share intent support** — share a URL directly from any browser or app into REM

### Editing (Advanced mode)
- **Video preview** with ExoPlayer, autoplays when ready
- **Trim**: range slider with live playback position indicator, loops within trim range
- **Crop**: interactive overlay with draggable corner handles, aspect ratio presets (16:9, 4:3, 9:16, 3:4, 4:5, 1:1, Free), portrait/landscape orientation toggle
- **GIF export**: configurable FPS (10/15/24) and quality (320/480/640px), live size estimate
- **Single FFmpeg pass**: trim + crop + format conversion applied together, no intermediate files

### UI / Settings
- **Basic / Advanced tabs**: Basic hides the preview and editing tools for a simple download experience; persists across sessions
- **Accent color picker**: 12 curated presets or Android system dynamic color (Material You)
- **Append edit labels** to filenames (`_trimmed`, `_cropped`) — toggle on/off
- **Haptic feedback** on download completion
- Material 3 dark theme throughout

---

## Setup Instructions

### 1. Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34
- Physical device or emulator running Android 8.0+ (API 26+)

### 2. Build and Run

```bash
# Open in Android Studio → Sync Gradle → Run on device
```

Or via command line:
```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Architecture

```
com.rem.downloader/
├── ui/
│   ├── MainActivity.kt          # Single-activity UI, tabs, ExoPlayer, edit controls
│   ├── SettingsActivity.kt      # Accent color, preferences
│   └── view/
│       └── CropOverlayView.kt   # Custom crop handle overlay
├── viewmodel/
│   └── MainViewModel.kt         # StateFlow/SharedFlow state, download orchestration
├── model/
│   └── Models.kt                # VideoInfo, DownloadOptions, EditConfig, enums
├── worker/
│   └── DownloadWorker.kt        # WorkManager worker: download → FFmpeg → save
├── service/
│   └── DownloadForegroundService.kt
└── util/
    ├── YtDlpHelper.kt           # Binary extraction, fetch info, preview download
    ├── FFmpegHelper.kt          # FFmpeg command builder (trim, crop, GIF)
    ├── FFmpegRunner.kt          # FFmpeg process execution, progress parsing
    ├── MediaUtils.kt            # Duration formatting, frame extraction
    └── ThemeManager.kt          # SharedPreferences wrapper for all settings
```

**Key decisions:**

- **Kotlin + Coroutines** — Modern Android standard with clean async flow
- **WorkManager** — Handles background downloads reliably, survives app backgrounding
- **ExoPlayer (Media3)** — Best-in-class Android video player for the preview
- **yt-dlp binary** — Supports 1,000+ sites, no API keys needed, actively maintained
- **FFmpeg** — Single-pass editing (trim + crop + format in one command, no re-encode for trim-only)
- **MVVM** — ViewModel + StateFlow/SharedFlow for UI state; MediatorLiveData for WorkManager progress
- **Material 3** — Dark theme with configurable accent color

---

## How the Download Pipeline Works

### Fetch
When you paste a URL and hit **Fetch Video**, the app runs:
```
yt-dlp --dump-json --no-download <url>
```
Returns title, thumbnail, duration, available formats, and dimensions.

### Preview (Advanced mode)
A low-res (≤360p) pre-muxed stream is downloaded to the app's private cache for the ExoPlayer preview. This uses a single file to keep progress linear (0→100%).

### Download
When you hit **Download**, a `DownloadWorker` job runs:

1. If **no edits**: downloads directly to `Downloads/REM/` with the chosen format and resolution
2. If **edits exist** (trim/crop/GIF): downloads to a temp file in `cacheDir`, then runs a single FFmpeg command applying all edits, saves result to `Downloads/REM/`, deletes the temp file

The FFmpeg command is built to be as efficient as possible:
- Trim-only (no re-encode needed) → uses `-c copy`
- Crop or GIF → re-encodes with the minimal filter chain


### Crop scaling
The crop overlay is drawn against the low-res preview dimensions. Before FFmpeg runs, the worker reads the actual downloaded file's dimensions using `MediaMetadataRetriever` and scales the crop coordinates proportionally — so crop is always pixel-accurate on the real video.

---

## Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | Fetch video info and download |
| `POST_NOTIFICATIONS` | Download progress notifications |
| `WRITE_EXTERNAL_STORAGE` | Save to Downloads (Android ≤ 9) |
| `READ_MEDIA_VIDEO` | Access saved videos (Android 13+) |
| `FOREGROUND_SERVICE` | Keep download running in background |
| `VIBRATE` | Optional haptic feedback on completion |

---

## Supported Sites

yt-dlp supports 1,000+ sites including YouTube (videos, shorts, playlists), Twitter/X, Instagram, TikTok, Reddit, Facebook, Vimeo, Twitch, Dailymotion, SoundCloud, and more.

Full list: https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md

---

## Troubleshooting

**"Could not fetch video info"**
- Check internet connection
- Ensure the yt-dlp binary is in `assets/` and correctly named
- Some sites require cookies — not yet supported

**Download fails with FFmpeg error**
- Check logcat for `DownloadWorker` or `FFmpegRunner` tags
- Crop dimensions that exceed the actual video size will cause exit 234 — this is auto-corrected by the crop scaling logic

**Preview doesn't load**
- Preview downloads a separate low-res clip. If the site doesn't have a pre-muxed ≤360p format, it may fall back to a slightly larger file
- Download will still work regardless of preview state

---

## License

MIT — feel free to modify and distribute.

yt-dlp is licensed under the Unlicense. ExoPlayer (Media3) and FFmpeg are Apache 2.0 / LGPL respectively.
