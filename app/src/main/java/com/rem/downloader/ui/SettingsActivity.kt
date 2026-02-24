package com.rem.downloader.ui

import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.DynamicColors
import com.rem.downloader.R
import com.rem.downloader.databinding.ActivitySettingsBinding
import com.rem.downloader.util.ThemeManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    data class Preset(val label: String, val color: Int)

    // Curated palette — muted, balanced, dark-UI friendly
    private val presets = listOf(
        Preset("Crimson",  0xFFE05252.toInt()),
        Preset("Coral",    0xFFE07B52.toInt()),
        Preset("Gold",     0xFFD4A843.toInt()),
        Preset("Sage",     0xFF6BAA7A.toInt()),
        Preset("Teal",     0xFF4AADA8.toInt()),
        Preset("Sky",      0xFF5A9FD4.toInt()),
        Preset("Indigo",   0xFF7B7FD4.toInt()),
        Preset("Mauve",    0xFFA876C8.toInt()),
        Preset("Rose",     0xFFD4648A.toInt()),
        Preset("Slate",    0xFF7A9BAD.toInt()),
        Preset("Copper",   0xFFC4875A.toInt()),
        Preset("Mint",     0xFF5AB89A.toInt()),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        if (ThemeManager.useSystemColor(this)) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        loadAppInfo()
        setupDownloadSection()
        setupAccentSection()
        buildColorGrid()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun loadAppInfo() {
        binding.tvAppName.text = "REM"
        binding.tvAppFullName.text = "Record, Extract, Manage"
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            binding.tvVersion.text = "Version ${pInfo.versionName}"
        } catch (e: PackageManager.NameNotFoundException) {
            binding.tvVersion.text = "Version 1.0"
        }
    }

    private fun setupDownloadSection() {
        binding.switchVibrate.isChecked = ThemeManager.vibrateOnDownload(this)
        binding.switchVibrate.setOnCheckedChangeListener { _, checked ->
            ThemeManager.setVibrateOnDownload(this, checked)
        }
    }

    private fun setupAccentSection() {
        val useSystem = ThemeManager.useSystemColor(this)
        binding.switchSystemColor.isChecked = useSystem
        binding.cardColorPicker.visibility = if (useSystem) View.GONE else View.VISIBLE

        binding.switchSystemColor.setOnCheckedChangeListener { _, checked ->
            ThemeManager.setUseSystemColor(this, checked)
            // Recreate so DynamicColors can be applied/removed
            recreate()
        }

        applyAccentPreview()
    }

    private fun buildColorGrid() {
        val row1 = binding.colorGrid
        val row2 = binding.colorGrid2
        row1.removeAllViews()
        row2.removeAllViews()

        val currentColor = ThemeManager.getCustomColor(this)
        val allRings = mutableListOf<View>()

        presets.forEachIndexed { index, preset ->
            val chip = layoutInflater.inflate(R.layout.item_color_chip, null, false)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            chip.layoutParams = lp

            val circle = chip.findViewById<View>(R.id.viewChipColor)
            val label = chip.findViewById<TextView>(R.id.tvChipLabel)
            val ring = chip.findViewById<View>(R.id.viewSelectedRing)

            circle.background.mutate().setTint(preset.color)
            label.text = preset.label
            label.setTextColor(preset.color)
            ring.visibility = if (preset.color == currentColor) View.VISIBLE else View.GONE
            allRings.add(ring)

            chip.setOnClickListener {
                ThemeManager.setCustomColor(this, preset.color)
                allRings.forEach { it.visibility = View.GONE }
                ring.visibility = View.VISIBLE
                applyAccentPreview()
            }

            if (index < 6) row1.addView(chip) else row2.addView(chip)
        }
    }

    private fun applyAccentPreview() {
        val color = ThemeManager.resolveAccentColor(this)
        val colorSL = ColorStateList.valueOf(color)
        binding.tvAppName.setTextColor(color)
        binding.viewColorPreview.background.mutate().setTint(color)

        // Tint all switches to match accent
        val dimmed = ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 80))
        for (sw in listOf(binding.switchSystemColor, binding.switchVibrate)) {
            sw.thumbTintList = colorSL
            sw.trackTintList = dimmed
        }
    }
}
