package com.rem.downloader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import coil.load
import com.google.android.material.snackbar.Snackbar
import com.rem.downloader.R
import com.rem.downloader.databinding.ActivityMainBinding
import com.rem.downloader.model.DownloadOptions
import com.rem.downloader.model.VideoInfo
import com.rem.downloader.util.ThemeManager
import com.rem.downloader.viewmodel.MainViewModel
import com.rem.downloader.viewmodel.UiState
import android.view.inputmethod.InputMethodManager
import androidx.work.WorkInfo
import com.google.android.material.color.DynamicColors
import com.rem.downloader.worker.DownloadWorker
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var player: ExoPlayer? = null
    private var wasUsingSystemColor = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        wasUsingSystemColor = ThemeManager.useSystemColor(this)
        if (wasUsingSystemColor) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        requestNotificationPermission()
        setupUI()
        observeViewModel()
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Recreate if system color preference changed (e.g. toggled in Settings)
        if (ThemeManager.useSystemColor(this) != wasUsingSystemColor) {
            recreate()
            return
        }
        // Re-apply accent color in case it changed in Settings
        applyAccentColor()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                binding.urlInput.setText(sharedText)
                viewModel.fetchVideoInfo(sharedText)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun applyAccentColor() {
        val color = ThemeManager.resolveAccentColor(this)
        val colorStateList = android.content.res.ColorStateList.valueOf(color)

        // Toolbar title
        binding.toolbar.setTitleTextColor(color)

        // Buttons
        binding.btnFetch.backgroundTintList = colorStateList
        binding.btnDownload.backgroundTintList = colorStateList
        binding.progressBar.setIndicatorColor(color)
        binding.downloadProgressBar.setIndicatorColor(color)

        // Adjust button text & icon color for contrast
        val textColor = if (ColorUtils.calculateLuminance(color) > 0.4)
            android.graphics.Color.BLACK else android.graphics.Color.WHITE
        val textColorSL = android.content.res.ColorStateList.valueOf(textColor)
        binding.btnFetch.setTextColor(textColor)
        binding.btnFetch.iconTint = textColorSL
        binding.btnDownload.setTextColor(textColor)
        binding.btnDownload.iconTint = textColorSL

        // Radio buttons
        binding.rbVideoAudio.buttonTintList = colorStateList
        binding.rbVideoOnly.buttonTintList = colorStateList
        binding.rbAudioOnly.buttonTintList = colorStateList
    }

    private fun setupUI() {
        // Combined paste/clear button — shows paste when empty, X when has text
        updatePasteClearButton(binding.urlInput.text.isNullOrEmpty())

        binding.urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updatePasteClearButton(s.isNullOrEmpty())
            }
        })

        binding.btnPasteClear.setOnClickListener {
            if (binding.urlInput.text.isNullOrEmpty()) {
                // Paste
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (!text.isNullOrBlank()) {
                    binding.urlInput.setText(text)
                    hideKeyboard()
                    viewModel.fetchVideoInfo(text)
                }
            } else {
                // Clear
                binding.urlInput.setText("")
                viewModel.clearState()
            }
        }

        // Fetch button
        binding.btnFetch.setOnClickListener {
            val url = binding.urlInput.text.toString().trim()
            if (url.isNotEmpty()) {
                hideKeyboard()
                viewModel.fetchVideoInfo(url)
            }
        }

        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val url = binding.urlInput.text.toString().trim()
                if (url.isNotEmpty()) {
                    hideKeyboard()
                    viewModel.fetchVideoInfo(url)
                }
                true
            } else false
        }

        // Download button
        binding.btnDownload.setOnClickListener {
            val url = binding.urlInput.text.toString().trim()
            val format = when (binding.rgFormat.checkedRadioButtonId) {
                R.id.rbVideoAudio -> "bestvideo+bestaudio/best"
                R.id.rbVideoOnly -> "bestvideo/best"
                R.id.rbAudioOnly -> "bestaudio/best"
                else -> "bestvideo+bestaudio/best"
            }
            val resolution = when (binding.spinnerResolution.selectedItem?.toString()) {
                "4K (2160p)" -> "2160"
                "1080p" -> "1080"
                "720p" -> "720"
                "480p" -> "480"
                "360p" -> "360"
                "Best" -> "best"
                else -> "best"
            }
            val customName = binding.etRename.text.toString().trim().ifEmpty { null }
            val options = DownloadOptions(
                url = url,
                format = format,
                resolution = resolution,
                customFilename = customName
            )
            viewModel.startDownload(options)
        }

        // Play/pause preview
        binding.playerView.setOnClickListener {
            player?.let {
                if (it.isPlaying) it.pause() else it.play()
            }
        }
    }

    private fun updatePasteClearButton(isEmpty: Boolean) {
        if (isEmpty) {
            binding.btnPasteClear.setIconResource(R.drawable.ic_content_paste)
            binding.btnPasteClear.contentDescription = "Paste URL"
        } else {
            binding.btnPasteClear.setIconResource(R.drawable.ic_close)
            binding.btnPasteClear.contentDescription = "Clear"
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                handleUiState(state)
            }
        }
    }

    private fun handleUiState(state: UiState) {
        when (state) {
            is UiState.Idle -> {
                showSection(info = false, options = false, player = false)
                binding.progressBar.visibility = View.GONE
            }
            is UiState.Loading -> {
                showSection(info = false, options = false, player = false)
                binding.progressBar.visibility = View.VISIBLE
                binding.btnFetch.isEnabled = false
            }
            is UiState.VideoInfoLoaded -> {
                binding.progressBar.visibility = View.GONE
                binding.btnFetch.isEnabled = true
                showSection(info = true, options = true, player = true)
                bindVideoInfo(state.info)
                setupPlayer(state.info)
            }
            is UiState.Error -> {
                binding.progressBar.visibility = View.GONE
                binding.btnFetch.isEnabled = true
                showSection(info = false, options = false, player = false)
                Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG).show()
            }
            is UiState.DownloadQueued -> {
                showDownloadProgress()
            }
        }
    }

    private fun showDownloadProgress() {
        binding.layoutDownloadProgress.visibility = View.VISIBLE
        binding.downloadProgressBar.isIndeterminate = true
        binding.tvDownloadStatus.text = "Starting download..."
        binding.tvDownloadPercent.text = ""

        viewModel.downloadWorkInfo?.observe(this) { workInfo ->
            if (workInfo == null) return@observe

            when (workInfo.state) {
                WorkInfo.State.RUNNING -> {
                    val progress = workInfo.progress.getInt(DownloadWorker.KEY_PROGRESS, -1)
                    val message = workInfo.progress.getString(DownloadWorker.KEY_PROGRESS_MESSAGE) ?: ""
                    if (progress > 0) {
                        binding.downloadProgressBar.isIndeterminate = false
                        binding.downloadProgressBar.setProgressCompat(progress, true)
                        binding.tvDownloadPercent.text = "$progress%"
                        binding.tvDownloadStatus.text = message.ifEmpty { "Downloading..." }
                    }
                }
                WorkInfo.State.SUCCEEDED -> {
                    binding.tvDownloadStatus.text = "Download complete"
                    binding.tvDownloadPercent.text = "100%"
                    binding.downloadProgressBar.isIndeterminate = false
                    binding.downloadProgressBar.setProgressCompat(100, true)
                    binding.layoutDownloadProgress.postDelayed({
                        binding.layoutDownloadProgress.visibility = View.GONE
                    }, 3000)
                }
                WorkInfo.State.FAILED -> {
                    binding.tvDownloadStatus.text = "Download failed"
                    binding.tvDownloadPercent.text = ""
                    binding.downloadProgressBar.isIndeterminate = false
                    binding.downloadProgressBar.setProgressCompat(0, false)
                    binding.layoutDownloadProgress.postDelayed({
                        binding.layoutDownloadProgress.visibility = View.GONE
                    }, 4000)
                }
                else -> {}
            }
        }
    }

    private fun bindVideoInfo(info: VideoInfo) {
        binding.tvTitle.text = info.title
        binding.tvMeta.text = buildString {
            if (info.duration > 0) append(formatDuration(info.duration))
            if (info.uploader.isNotEmpty()) {
                if (isNotEmpty()) append(" • ")
                append(info.uploader)
            }
        }
        binding.ivThumbnail.load(info.thumbnailUrl) {
            crossfade(true)
            placeholder(R.drawable.ic_video_placeholder)
        }
        val resolutions = mutableListOf("Best").also {
            it.addAll(info.availableResolutions.map { r -> "${r}p" })
        }
        val adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_item, resolutions
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerResolution.adapter = adapter

        binding.etRename.setText("")
        binding.etRename.hint = info.title.take(50)
    }

    private fun setupPlayer(info: VideoInfo) {
        releasePlayer()
        if (info.directStreamUrl.isNullOrEmpty()) {
            binding.playerView.visibility = View.GONE
            binding.ivThumbnail.visibility = View.VISIBLE
            return
        }
        binding.playerView.visibility = View.VISIBLE
        binding.ivThumbnail.visibility = View.GONE

        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.playerView.player = exo
            val mediaItem = MediaItem.fromUri(info.directStreamUrl)
            exo.setMediaItem(mediaItem)
            exo.prepare()
        }
    }

    private fun showSection(info: Boolean, options: Boolean, player: Boolean) {
        binding.cardVideoInfo.visibility = if (info) View.VISIBLE else View.GONE
        binding.cardOptions.visibility = if (options) View.VISIBLE else View.GONE
        binding.cardPlayer.visibility = if (player) View.VISIBLE else View.GONE
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.urlInput.windowToken, 0)
        binding.urlInput.clearFocus()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }
}
