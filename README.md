# REM: Record, Extract, Manage

This app is built around the power of YT-DLP and FFmpeg to deliver a fast, streamlined media downloading experience. Designed with simplicity in mind, it removes unnecessary clutter and focuses purely on what matters which is getting your media quickly and efficiently.

With an intuitive interface and minimal setup, users can download and process content in just a few steps. No distractions, no complexity, just a straightforward tool built for speed, reliability, and ease of use.

---

[![Download APK](https://img.shields.io/badge/Download-Latest%20Release-brightgreen)](https://github.com/0xGoldstar/REM/releases/latest)
## Installing the APK
1. Click on latest release and download the APK to your device. 
2. Open the file from your Downloads folder. 
3. If prompted, enable Install unknown apps for your browser or file manager. 
4. Tap Install.


---

## Features

### Downloading
- **Paste any URL** from YouTube, Twitter/X, Instagram, TikTok, Reddit and many more
- **Format options**: Video + Audio / Video Only
- **Resolution selector** populated from the video's actual available formats
- **Rename file** before download (optional)
- **Background download** and has live progress bar and notification
- **Downloads to `Downloads/REM/`** on device storage
- **Share intent support**  share a URL directly from any browser or app into REM

### Editing (Basic mode)
- **Rename**
- **Resolution selector**
- **Format options: Video + audio or video only**

### Editing (Advanced mode)
- **Video preview**
- **Trim**
- **Crop**
- **GIF export**

### UI / Settings
- **Basic / Advanced tabs**: Basic hides the preview and editing tools for a simple download experience; persists across sessions
- **Accent color picker**: 12 curated presets or Android system dynamic color
- **Append edit labels** to filenames (`_trimmed`, `_cropped`). Can be toggled on/off
- **Haptic feedback** on download completion

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

- **Kotlin + Coroutines**: Modern Android standard with clean async flow
- **WorkManager**: Handles background downloads reliably, survives app backgrounding
- **ExoPlayer (Media3)**: Best-in-class Android video player for the preview
- **yt-dlp binary**: Supports 1,000+ sites, no API keys needed, actively maintained
- **FFmpeg**: Single-pass editing (trim + crop + format in one command, no re-encode for trim-only)
- **MVVM**: ViewModel + StateFlow/SharedFlow for UI state; MediatorLiveData for WorkManager progress
- **Material 3**: Dark theme with configurable accent color

---

## How the Download Pipeline Works

### Fetch
When you paste a URL and hit **Fetch Video**, the app runs:
```
yt-dlp --dump-json --no-download <url>
```
Returns title, thumbnail, duration, available formats, and dimensions.

### Preview (Advanced mode)
A low-res (≤360p) pre-muxed stream is downloaded to the app's private cache for the ExoPlayer preview.

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

## License

MIT — feel free to modify and distribute.

yt-dlp is licensed under the Unlicense. ExoPlayer (Media3) and FFmpeg are Apache 2.0 / LGPL respectively.
