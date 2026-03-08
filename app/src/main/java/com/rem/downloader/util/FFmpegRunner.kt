package com.rem.downloader.util

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

object FFmpegRunner {

    private const val TAG = "FFmpegRunner"

    /**
     * Finds libc++_shared.so by reading /proc/self/maps — the running process already has it
     * mapped into memory (via Android's JNI namespace), so we can get its real on-disk path
     * even when it's not in nativeLibDir or any standard system path.
     * Falls back to a list of known candidate paths.
     */
    private fun findLibCppShared(nativeLibDir: String): java.io.File? {
        // Primary: find the actual mapped path from the live process
        try {
            java.io.File("/proc/self/maps").forEachLine { line ->
                if (line.contains("libc++_shared.so")) {
                    // maps format: addr perms offset dev inode pathname
                    val path = line.trim().split("\\s+".toRegex()).lastOrNull()
                    if (path != null && path.startsWith("/")) {
                        val f = java.io.File(path)
                        if (f.isFile) throw FoundException(f)
                    }
                }
            }
        } catch (e: FoundException) {
            Log.d(TAG, "libc++_shared.so found via /proc/self/maps: ${e.file.absolutePath}")
            return e.file
        } catch (e: Exception) {
            Log.w(TAG, "proc/self/maps search failed: ${e.message}")
        }

        // Fallback: known static locations
        val candidates = listOf(
            java.io.File(nativeLibDir, "libc++_shared.so"),
            java.io.File("/apex/com.android.art/lib64/libc++_shared.so"),
            java.io.File("/apex/com.android.runtime/lib64/libc++_shared.so"),
            java.io.File("/system/lib64/libc++_shared.so")
        )
        return candidates.firstOrNull { it.isFile }
            .also { if (it == null) Log.e(TAG, "libc++_shared.so not found in any candidate") }
    }

    private class FoundException(val file: java.io.File) : Exception()

    /**
     * Executes FFmpeg (libffmpeg.so) with the given arguments, parsing stderr for progress.
     *
     * LD_LIBRARY_PATH is set to include:
     *   - the app's native lib dir (where libffmpeg.so lives alongside its JNI deps)
     *   - the FFmpeg shared lib dir (packages/ffmpeg/usr/lib, extracted from libffmpeg.zip.so)
     *
     * @param context Application context
     * @param binaryPath Path to libffmpeg.so
     * @param args FFmpeg arguments (without the binary path)
     * @param onProgress Callback with progress percentage (0-99)
     * @return Exit code of the FFmpeg process
     */
    fun execute(
        context: Context,
        binaryPath: String,
        args: List<String>,
        onProgress: ((Int) -> Unit)? = null
    ): Int {
        val command = mutableListOf(binaryPath).apply { addAll(args) }
        Log.d(TAG, "FFmpeg command: ${command.joinToString(" ")}")

        val processBuilder = ProcessBuilder(command).redirectErrorStream(false)

        // Build LD_LIBRARY_PATH: native lib dir + extracted FFmpeg shared libs
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val ffmpegLibDir = FFmpegHelper.getFFmpegLibDir(context)
        val ldPath = if (ffmpegLibDir != null) "$nativeLibDir:$ffmpegLibDir" else nativeLibDir
        Log.d(TAG, "LD_LIBRARY_PATH=$ldPath")

        // libc++_shared.so is needed by librubberband.so (added in youtubedl-android 0.18.x).
        // Android namespace isolation prevents subprocesses from finding it via the app's JNI
        // namespace. Copy it into ffmpegLibDir (app data dir, accessible to subprocesses).
        // The file is never on the normal filesystem, but it IS mapped into the running process —
        // find its actual path via /proc/self/maps and copy from there.
        if (ffmpegLibDir != null) {
            val dst = java.io.File(ffmpegLibDir, "libc++_shared.so")
            if (!dst.exists()) {
                val src = findLibCppShared(nativeLibDir)
                if (src != null) {
                    try {
                        src.copyTo(dst)
                        Log.d(TAG, "libc++_shared.so copied from ${src.absolutePath} (${dst.length()} bytes)")
                    } catch (e: Exception) {
                        Log.w(TAG, "libc++_shared.so copy failed: ${e.message}")
                    }
                } else {
                    Log.e(TAG, "libc++_shared.so not found anywhere — FFmpeg will likely fail")
                }
            }
        }

        val env = processBuilder.environment()
        env["LD_LIBRARY_PATH"] = ldPath

        val process = processBuilder.start()

        // Read stderr for progress updates
        val stderrReader = BufferedReader(InputStreamReader(process.errorStream))
        var lastProgress = -1
        var durationSec = -1.0

        stderrReader.forEachLine { line ->
            Log.v(TAG, "ffmpeg: $line")

            if (durationSec < 0) {
                val durationMatch = Regex("Duration:\\s*(\\d+):(\\d+):(\\d+\\.\\d+)").find(line)
                if (durationMatch != null) {
                    val (h, m, s) = durationMatch.destructured
                    durationSec = h.toDouble() * 3600 + m.toDouble() * 60 + s.toDouble()
                }
            }

            val timeMatch = Regex("time=(\\d+):(\\d+):(\\d+\\.\\d+)").find(line)
            if (timeMatch != null && durationSec > 0) {
                val (h, m, s) = timeMatch.destructured
                val currentSec = h.toDouble() * 3600 + m.toDouble() * 60 + s.toDouble()
                val pct = ((currentSec / durationSec) * 100).toInt().coerceIn(0, 99)
                if (pct != lastProgress) {
                    lastProgress = pct
                    onProgress?.invoke(pct)
                }
            }
        }

        process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        Log.d(TAG, "FFmpeg exit code: $exitCode")
        return exitCode
    }
}
