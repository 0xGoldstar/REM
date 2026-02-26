package com.rem.downloader.util

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

object FFmpegRunner {

    private const val TAG = "FFmpegRunner"

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
