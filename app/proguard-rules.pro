# yt-dlp subprocess - keep all ProcessBuilder related classes
-keep class java.lang.ProcessBuilder { *; }
-keep class java.lang.Process { *; }

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# WorkManager
-keep class androidx.work.** { *; }

# Coil
-keep class coil.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
