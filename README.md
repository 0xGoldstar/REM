# REM — Video Downloader for Android

REM is a clean, dark-themed Android app that downloads videos from YouTube, Twitter/X, Instagram, TikTok, Reddit, and 1,000+ other platforms using **yt-dlp** under the hood.

---

## Features

- **Paste any URL** from YouTube, Twitter/X, Instagram, TikTok, Reddit, Facebook, Vimeo, and more
- **Video preview** with ExoPlayer (plays directly in-app before downloading)
- **Format options**: Video + Audio / Video Only / Audio Only
- **Resolution selector** populated from the video's actual available formats
- **Rename file** before download (optional)
- **Background download** via WorkManager with live notification progress
- **Downloads to `Downloads/REM/`** on your device storage
- **Share intent support** — share a URL from any browser or app directly into REM
- Dark theme, Material 3 design

---

## Setup Instructions

### 1. Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34
- A physical device or emulator running Android 8.0+ (API 26+)

### 2. Get the yt-dlp Binary

REM uses the **yt-dlp Android binary** which runs natively on Android without Python.

Download the latest release from: https://github.com/yt-dlp/yt-dlp/releases

You need **two binaries**:

| File to download | Rename to | Purpose |
|---|---|---|
| `yt-dlp_android` (or `yt-dlp_linux_armv7l`) | `yt-dlp-arm64` | Real ARM64 Android devices |
| `yt-dlp_linux` (x86_64) | `yt-dlp-x86_64` | Android emulators (x86_64) |

Place both files in:
```
app/src/main/assets/
├── yt-dlp-arm64
└── yt-dlp-x86_64
```

> **Important:** The app detects the device architecture at runtime and uses the correct binary. If running on an ARM device (most phones), `yt-dlp-arm64` is used. If on an x86 emulator, `yt-dlp-x86_64` is used.

### 3. Build and Run

```bash
# Clone / open in Android Studio
# Sync Gradle (File > Sync Project with Gradle Files)
# Run on device or emulator
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
│   └── MainActivity.kt          # Single-activity UI, ExoPlayer setup
├── viewmodel/
│   └── MainViewModel.kt         # State management, coroutines
├── model/
│   └── Models.kt                # VideoInfo, DownloadOptions data classes
├── worker/
│   └── DownloadWorker.kt        # WorkManager worker, progress notifications
├── service/
│   └── DownloadForegroundService.kt
└── util/
    └── YtDlpHelper.kt           # Binary extraction, yt-dlp process management
```

**Key decisions:**

- **Kotlin** — Modern Android standard, excellent coroutine support
- **WorkManager** — Handles background downloads reliably; survives app backgrounding
- **ExoPlayer (Media3)** — Best-in-class Android video player, built by Google
- **yt-dlp binary** — Supports 1,000+ sites, actively maintained, no API keys needed
- **MVVM** — Clean separation via ViewModel + StateFlow
- **Material 3** — Modern dark theme with red accent

---

## How yt-dlp Integration Works

1. **Binary extraction**: On first launch, `YtDlpHelper` extracts the appropriate yt-dlp binary from `assets/` to the app's private `filesDir` and marks it executable.

2. **Fetch info**: When you paste a URL and hit "Fetch Video", the app runs:
   ```
   yt-dlp --dump-json --no-download <url>
   ```
   This returns a JSON blob with title, thumbnail, duration, available formats, and direct stream URLs.

3. **Download**: When you hit "Download", a `WorkManager` job runs:
   ```
   yt-dlp -f <format_selector> -o <output_path> --merge-output-format mp4 <url>
   ```
   Progress is parsed from yt-dlp's stdout (`45.3%` pattern) and reported as a notification.

4. **Output**: Files are saved to `Downloads/REM/` on the device.

---

## Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | Fetch video info and download |
| `POST_NOTIFICATIONS` | Download progress notifications |
| `WRITE_EXTERNAL_STORAGE` | Save to Downloads (Android ≤ 9) |
| `READ_MEDIA_VIDEO` | Access saved videos (Android 13+) |
| `FOREGROUND_SERVICE` | Keep download running in background |

---

## Supported Sites (Examples)

yt-dlp supports 1,000+ sites including:

- YouTube (videos, shorts, playlists)
- Twitter / X
- Instagram (reels, posts)
- TikTok
- Reddit (video posts)
- Facebook
- Vimeo
- Twitch (VODs, clips)
- Dailymotion
- SoundCloud (audio)
- And hundreds more: https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md

---

## Troubleshooting

**"Could not fetch video info"**
- Check internet connection
- Ensure the yt-dlp binary is in `assets/` and correctly named
- Some sites may require cookies — future enhancement

**Download stalls at 0%**
- yt-dlp binary may not be executable — check `YtDlpHelper.extractBinary()`
- Check logcat for `YtDlpHelper` tag

**ExoPlayer preview doesn't load**
- Not all sites expose direct stream URLs in JSON — preview falls back to thumbnail only
- The download will still work even without preview

---

## Future Improvements

- [ ] Cookie import for age-gated / login-required content
- [ ] Download history / library screen
- [ ] Playlist/batch download support
- [ ] Custom output directory picker
- [ ] Speed/progress graph in notification
- [ ] Audio-only with metadata tagging (MP3/M4A)
- [ ] Auto-update yt-dlp binary in-app

---

## License

MIT — feel free to modify and distribute.

yt-dlp is licensed under the Unlicense. ExoPlayer (Media3) is Apache 2.0.
