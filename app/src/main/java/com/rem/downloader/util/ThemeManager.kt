package com.rem.downloader.util

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.ColorInt
import androidx.core.content.edit

object ThemeManager {

    private const val PREFS_NAME = "rem_prefs"
    private const val KEY_USE_SYSTEM_COLOR = "use_system_color"
    private const val KEY_CUSTOM_COLOR = "custom_color"
    private const val KEY_VIBRATE_ON_DOWNLOAD = "vibrate_on_download"

    // Default REM red
    const val DEFAULT_COLOR = 0xFFE53935.toInt()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun useSystemColor(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_SYSTEM_COLOR, false)

    fun setUseSystemColor(context: Context, value: Boolean) =
        prefs(context).edit { putBoolean(KEY_USE_SYSTEM_COLOR, value) }

    @ColorInt
    fun getCustomColor(context: Context): Int =
        prefs(context).getInt(KEY_CUSTOM_COLOR, DEFAULT_COLOR)

    fun setCustomColor(context: Context, @ColorInt color: Int) =
        prefs(context).edit { putInt(KEY_CUSTOM_COLOR, color) }

    @ColorInt
    fun resolveAccentColor(context: Context): Int {
        return if (useSystemColor(context)) {
            getSystemAccentColor(context)
        } else {
            getCustomColor(context)
        }
    }

    fun vibrateOnDownload(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VIBRATE_ON_DOWNLOAD, true)

    fun setVibrateOnDownload(context: Context, value: Boolean) =
        prefs(context).edit { putBoolean(KEY_VIBRATE_ON_DOWNLOAD, value) }

    @ColorInt
    fun getSystemAccentColor(context: Context): Int {
        // Resolve colorPrimary from the current theme — when DynamicColors is applied,
        // this returns the Material You harmonized shade
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorPrimary, typedValue, true
        )
        return typedValue.data
    }
}
