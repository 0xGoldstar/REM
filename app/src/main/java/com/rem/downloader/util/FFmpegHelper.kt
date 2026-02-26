package com.rem.downloader.util

import android.content.Context
import android.util.Log
import com.rem.downloader.model.EditConfig
import com.rem.downloader.model.OutputFormat
import com.yausername.ffmpeg.FFmpeg
import java.io.File

object FFmpegHelper {

    private const val TAG = "FFmpegHelper"

    /**
     * Finds the FFmpeg binary.
     *
     * The youtubedl-android ffmpeg AAR ships two things:
     *   - libffmpeg.so  → the actual FFmpeg ELF binary, installed to the app's native lib dir
     *   - libffmpeg.zip.so → a ZIP of shared libs extracted to packages/ffmpeg/usr/lib/
     *
     * So the binary is at: <nativeLibraryDir>/libffmpeg.so
     * And LD_LIBRARY_PATH needs packages/ffmpeg/usr/lib/ added.
     */
    fun findFFmpegBinary(context: Context): String? {
        // Strategy 1: libffmpeg.so in the app's native library directory (the correct location)
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val nativeBinary = File(nativeLibDir, "libffmpeg.so")
        if (nativeBinary.isFile) {
            nativeBinary.setExecutable(true)
            Log.d(TAG, "FFmpeg binary (native lib): ${nativeBinary.absolutePath}")
            return nativeBinary.absolutePath
        }

        // Strategy 2: binDir field from FFmpeg singleton (File type, not String)
        try {
            val instance = FFmpeg.getInstance()
            val field = instance.javaClass.getDeclaredField("binDir")
            field.isAccessible = true
            val binDir = field.get(instance) as? File
            if (binDir != null) {
                val binary = File(binDir, "libffmpeg.so")
                if (binary.isFile) {
                    binary.setExecutable(true)
                    Log.d(TAG, "FFmpeg via binDir reflection: ${binary.absolutePath}")
                    return binary.absolutePath
                }
            }
        } catch (e: Exception) { Log.w(TAG, "binDir reflection failed: ${e.message}") }

        // Strategy 3: walk the entire app's nativeLibraryDir for libffmpeg.so
        try {
            nativeLibDir.walkTopDown()
                .filter { it.isFile && it.name == "libffmpeg.so" }
                .firstOrNull()
                ?.let { it.setExecutable(true); Log.d(TAG, "FFmpeg via nativeLib walk: ${it.absolutePath}"); return it.absolutePath }
        } catch (_: Exception) {}

        Log.e(TAG, "FFmpeg binary not found. nativeLibraryDir=${nativeLibDir.absolutePath} exists=${nativeLibDir.exists()}")
        nativeLibDir.listFiles()?.forEach { Log.e(TAG, "  nativeLib: ${it.name}") }
        return null
    }

    /**
     * Returns the directory containing the FFmpeg shared libraries that must be
     * added to LD_LIBRARY_PATH when running libffmpeg.so as a subprocess.
     */
    fun getFFmpegLibDir(context: Context): String? {
        val libDir = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")
        if (libDir.isDirectory && (libDir.listFiles()?.isNotEmpty() == true)) {
            Log.d(TAG, "FFmpeg lib dir: ${libDir.absolutePath}")
            return libDir.absolutePath
        }
        Log.w(TAG, "FFmpeg lib dir not found at ${libDir.absolutePath}")
        return null
    }

    /**
     * Builds a single-pass FFmpeg command applying trim + crop + format conversion.
     *
     * Trim:  -ss (input seek) + -t (duration) before -i for fast seek.
     *        Uses -t (duration), not -to (absolute end), which is more reliable
     *        with fast input seeking across FFmpeg versions.
     *
     * Crop:  simple -vf for MP4; part of -filter_complex for GIF.
     *
     * GIF:   -filter_complex with palette generation (split→palettegen→paletteuse).
     *        Must use -filter_complex, NOT -vf, because the filter graph has named
     *        streams ([s0],[s1]) which are not valid in a simple -vf chain.
     */
    fun buildCombinedCommand(inputPath: String, outputPath: String, config: EditConfig): List<String> {
        val args = mutableListOf<String>()
        args.add("-y")

        // Input seeking: -ss + -t before -i for fast seek.
        val trimDurationSec = (config.trimEndMs - config.trimStartMs) / 1000.0
        val doTrim = config.trimEnabled && trimDurationSec > 0.1
        if (doTrim) {
            val startSec = config.trimStartMs / 1000.0
            args.addAll(listOf("-ss", "%.3f".format(startSec)))
            args.addAll(listOf("-t",  "%.3f".format(trimDurationSec)))
        }

        args.addAll(listOf("-i", inputPath))

        // Validate crop dimensions — must be positive and even for libx264.
        val cropW = config.cropW and 0x7FFFFFFE  // round down to even
        val cropH = config.cropH and 0x7FFFFFFE
        val doCrop = config.cropEnabled && cropW > 0 && cropH > 0

        val needsReencode = doCrop || config.outputFormat == OutputFormat.GIF

        when {
            !needsReencode -> {
                // Trim-only: stream copy is fastest
                args.addAll(listOf("-c", "copy", "-avoid_negative_ts", "make_zero"))
            }

            config.outputFormat == OutputFormat.GIF -> {
                // GIF requires a complex filtergraph with palette generation.
                // -filter_complex (not -vf) is required when named streams are used.
                val cropPart = if (doCrop) "crop=$cropW:$cropH:${config.cropX}:${config.cropY}," else ""
                val filterComplex = buildString {
                    append("[0:v]")
                    append(cropPart)
                    append("fps=${config.gifFps},")
                    append("scale=${config.gifMaxWidth}:-2:flags=lanczos,")  // -2 = keep even height
                    append("split[s0][s1];")
                    append("[s0]palettegen=max_colors=128[p];")
                    append("[s1][p]paletteuse=dither=bayer[out]")
                }
                args.addAll(listOf("-filter_complex", filterComplex, "-map", "[out]", "-loop", "0"))
            }

            else -> {
                // MP4 re-encode (crop required)
                if (doCrop) {
                    args.addAll(listOf("-vf", "crop=$cropW:$cropH:${config.cropX}:${config.cropY}"))
                }
                args.addAll(listOf("-c:v", "libx264", "-preset", "fast", "-crf", "23", "-c:a", "copy"))
            }
        }

        args.add(outputPath)
        Log.d(TAG, "FFmpeg command: ${args.joinToString(" ")}")
        return args
    }

}
