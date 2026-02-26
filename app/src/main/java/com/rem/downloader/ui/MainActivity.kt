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
import androidx.transition.TransitionManager
import coil.load
import com.google.android.material.slider.RangeSlider
import com.google.android.material.snackbar.Snackbar
import com.rem.downloader.R
import com.rem.downloader.databinding.ActivityMainBinding
import com.rem.downloader.model.CropRatio
import com.rem.downloader.model.DownloadOptions
import com.rem.downloader.model.OutputFormat
import com.rem.downloader.model.VideoInfo
import com.rem.downloader.util.MediaUtils
import com.rem.downloader.util.ThemeManager
import com.rem.downloader.viewmodel.MainViewModel
import com.rem.downloader.viewmodel.PreviewState
import com.rem.downloader.viewmodel.UiState
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import androidx.work.WorkInfo
import com.google.android.material.color.DynamicColors
import com.rem.downloader.worker.DownloadWorker
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var player: ExoPlayer? = null
    private var wasUsingSystemColor = false
    private var isAdvancedMode = false
    private var hideProgressRunnable: Runnable? = null

    private val timeHandler = Handler(Looper.getMainLooper())
    private val timeRunnable = object : Runnable {
        override fun run() {
            val exo = player
            if (exo != null && ::binding.isInitialized) {
                val pos = exo.currentPosition
                val dur = exo.duration.takeIf { it > 0 } ?: 0L
                binding.tvCurrentTime.text =
                    "${MediaUtils.formatDuration(pos)} / ${MediaUtils.formatDuration(dur)}"

                // Loop playback within trim range
                if (viewModel.trimEnabled) {
                    val endMs = viewModel.trimEndMs
                    val startMs = viewModel.trimStartMs
                    if (pos >= endMs) {
                        exo.seekTo(startMs)
                        if (!exo.isPlaying) exo.play()
                    }
                }

                updatePositionIndicator(pos)
            }
            timeHandler.postDelayed(this, 100)
        }
    }

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
        setupEditSections()
        setupModeTabs()
        observeViewModel()
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (ThemeManager.useSystemColor(this) != wasUsingSystemColor) {
            recreate()
            return
        }
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

    // ── Accent Color ──

    private fun applyAccentColor() {
        val color = ThemeManager.resolveAccentColor(this)
        val colorStateList = android.content.res.ColorStateList.valueOf(color)

        binding.toolbar.setTitleTextColor(color)

        binding.btnFetch.backgroundTintList = colorStateList
        binding.btnDownload.backgroundTintList = colorStateList
        binding.progressBar.setIndicatorColor(color)
        binding.downloadProgressBar.setIndicatorColor(color)

        val textColor = if (ColorUtils.calculateLuminance(color) > 0.4)
            android.graphics.Color.BLACK else android.graphics.Color.WHITE
        val textColorSL = android.content.res.ColorStateList.valueOf(textColor)
        binding.btnFetch.setTextColor(textColor)
        binding.btnFetch.iconTint = textColorSL
        binding.btnDownload.setTextColor(textColor)
        binding.btnDownload.iconTint = textColorSL

        binding.rbVideoAudio.buttonTintList = colorStateList
        binding.rbVideoOnly.buttonTintList = colorStateList

        // Edit section accent
        binding.cbCropEnabled.buttonTintList = colorStateList
        binding.trimRangeSlider.trackActiveTintList = colorStateList
        binding.trimRangeSlider.thumbTintList = colorStateList
        binding.cropOverlayView.accentColor = color

        // Tab indicator and selected text tint
        binding.tabLayout.setSelectedTabIndicatorColor(color)
        binding.tabLayout.setTabTextColors(
            ContextCompat.getColor(this, R.color.text_secondary), color
        )
    }

    // ── Basic Download UI ──

    private fun setupUI() {
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
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (!text.isNullOrBlank()) {
                    binding.urlInput.setText(text)
                    hideKeyboard()
                    viewModel.fetchVideoInfo(text)
                }
            } else {
                binding.urlInput.setText("")
                viewModel.clearState()
            }
        }

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

        binding.btnDownload.setOnClickListener {
            val url = binding.urlInput.text.toString().trim()
            val format = when (binding.rgFormat.checkedRadioButtonId) {
                R.id.rbVideoOnly -> "bestvideo/best"
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

            // Sync crop coords from overlay if crop is enabled
            if (viewModel.cropEnabled) {
                val rect = binding.cropOverlayView.getCropInVideoCoords()
                viewModel.cropX = rect.left
                viewModel.cropY = rect.top
                viewModel.cropW = rect.width() and 0x7FFFFFFE.toInt()
                viewModel.cropH = rect.height() and 0x7FFFFFFE.toInt()
            }

            val options = DownloadOptions(
                url = url, format = format,
                resolution = resolution, customFilename = customName
            )
            viewModel.startDownload(options)
        }

        binding.playerView.setOnClickListener {
            player?.let { p ->
                if (p.isPlaying) {
                    p.pause()
                    showPlayPauseOverlay(playing = false)
                } else {
                    p.play()
                    showPlayPauseOverlay(playing = true)
                }
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
            viewModel.uiState.collect { state -> handleUiState(state) }
        }
        lifecycleScope.launch {
            viewModel.previewState.collect { state -> handlePreviewState(state) }
        }
        // SharedFlow: fires every time download is started, no StateFlow deduplication issue
        lifecycleScope.launch {
            viewModel.downloadStarted.collect { showDownloadProgress() }
        }
        // Observe work info once via switchMap — automatically tracks the current download
        viewModel.workInfoLive.observe(this) { workInfo ->
            if (workInfo == null) return@observe
            when (workInfo.state) {
                WorkInfo.State.RUNNING -> {
                    val progress = workInfo.progress.getInt(DownloadWorker.KEY_PROGRESS, -1)
                    val message = workInfo.progress.getString(DownloadWorker.KEY_PROGRESS_MESSAGE) ?: ""
                    binding.tvDownloadStatus.text = message.ifEmpty { "Downloading..." }
                    if (progress > 0) {
                        binding.downloadProgressBar.isIndeterminate = false
                        binding.downloadProgressBar.setProgressCompat(progress, true)
                        binding.tvDownloadPercent.text = "$progress%"
                    }
                }
                WorkInfo.State.SUCCEEDED -> {
                    binding.tvDownloadStatus.text = "Download complete"
                    binding.tvDownloadPercent.text = "100%"
                    binding.downloadProgressBar.isIndeterminate = false
                    binding.downloadProgressBar.setProgressCompat(100, true)
                    scheduleHideProgress(3000)
                }
                WorkInfo.State.FAILED -> {
                    binding.tvDownloadStatus.text = "Download failed"
                    binding.tvDownloadPercent.text = ""
                    binding.downloadProgressBar.isIndeterminate = false
                    binding.downloadProgressBar.setProgressCompat(0, false)
                    scheduleHideProgress(4000)
                }
                else -> {}
            }
        }
    }

    private fun scheduleHideProgress(delayMs: Long) {
        hideProgressRunnable?.let { binding.layoutDownloadProgress.removeCallbacks(it) }
        val runnable = Runnable { binding.layoutDownloadProgress.visibility = View.GONE }
        hideProgressRunnable = runnable
        binding.layoutDownloadProgress.postDelayed(runnable, delayMs)
    }

    private fun handlePreviewState(state: PreviewState) {
        // In Basic mode the player card is never shown
        if (!isAdvancedMode) return
        when (state) {
            is PreviewState.Idle -> {
                binding.layoutPreviewLoading.visibility = View.GONE
            }
            is PreviewState.Downloading -> {
                binding.cardPlayer.visibility = View.VISIBLE
                binding.layoutPreviewLoading.visibility = View.VISIBLE
                binding.layoutTrimControls.visibility = View.GONE
                binding.layoutCropControls.visibility = View.GONE
                if (state.progress > 0) {
                    binding.progressPreview.isIndeterminate = false
                    binding.progressPreview.setProgressCompat(state.progress, true)
                    binding.tvPreviewStatus.text = "Fetching preview… ${state.progress}%"
                } else {
                    binding.progressPreview.isIndeterminate = true
                    binding.tvPreviewStatus.text = "Fetching preview…"
                }
            }
            is PreviewState.Ready -> {
                binding.cardPlayer.visibility = View.VISIBLE
                binding.layoutPreviewLoading.visibility = View.GONE
                binding.layoutTrimControls.visibility = View.VISIBLE
                binding.layoutCropControls.visibility = View.VISIBLE
                loadPreviewInPlayer(state.filePath)
            }
            is PreviewState.Failed -> {
                binding.layoutPreviewLoading.visibility = View.GONE
                binding.layoutTrimControls.visibility = View.GONE
                binding.layoutCropControls.visibility = View.GONE
            }
        }
    }

    private fun loadPreviewInPlayer(filePath: String) {
        releasePlayer()
        binding.playerView.visibility = View.VISIBLE
        binding.tvCurrentTime.text = "0:00 / 0:00"
        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.playerView.player = exo
            exo.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(filePath))))
            exo.playWhenReady = true
            exo.prepare()
            // NOTE: do NOT set cropOverlayView dims from preview video size —
            // setupCropPreview() already set the actual full-res dimensions from yt-dlp.
        }
        timeHandler.removeCallbacks(timeRunnable)
        timeHandler.post(timeRunnable)
    }

    private fun handleUiState(state: UiState) {
        when (state) {
            is UiState.Idle -> {
                showSection(info = false, options = false, player = false, editCards = false)
                binding.progressBar.visibility = View.GONE
            }
            is UiState.Loading -> {
                showSection(info = false, options = false, player = false, editCards = false)
                binding.progressBar.visibility = View.VISIBLE
                binding.btnFetch.isEnabled = false
            }
            is UiState.VideoInfoLoaded -> {
                binding.progressBar.visibility = View.GONE
                binding.btnFetch.isEnabled = true
                // player=true shows the card; trim card will appear once preview is ready
                showSection(info = true, options = true, player = true, editCards = true)
                bindVideoInfo(state.info)
                setupTrimRange(state.info)
                setupCropPreview(state.info)
            }
            is UiState.Error -> {
                binding.progressBar.visibility = View.GONE
                binding.btnFetch.isEnabled = true
                showSection(info = false, options = false, player = false, editCards = false)
                Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showDownloadProgress() {
        // Cancel any pending hide from a previous download before showing new progress
        hideProgressRunnable?.let { binding.layoutDownloadProgress.removeCallbacks(it) }
        hideProgressRunnable = null

        binding.layoutDownloadProgress.visibility = View.VISIBLE
        binding.downloadProgressBar.isIndeterminate = true
        binding.tvDownloadStatus.text = "Starting download..."
        binding.tvDownloadPercent.text = ""
        binding.nestedScrollView.post {
            binding.nestedScrollView.smoothScrollTo(0, binding.cardOptions.bottom)
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

    private fun showSection(info: Boolean, options: Boolean, player: Boolean, editCards: Boolean) {
        binding.cardVideoInfo.visibility = if (info) View.VISIBLE else View.GONE
        binding.cardOptions.visibility = if (options) View.VISIBLE else View.GONE
        // Player card only in Advanced mode
        binding.cardPlayer.visibility = if (player && isAdvancedMode) View.VISIBLE else View.GONE
        if (editCards) {
            // GIF format toggle only in Advanced mode; trim/crop driven by handlePreviewState
            if (isAdvancedMode) {
                binding.tvOutputFormatLabel.visibility = View.VISIBLE
                binding.toggleOutputFormat.visibility = View.VISIBLE
            }
        } else {
            binding.layoutTrimControls.visibility = View.GONE
            binding.layoutCropControls.visibility = View.GONE
            binding.tvOutputFormatLabel.visibility = View.GONE
            binding.toggleOutputFormat.visibility = View.GONE
            binding.layoutGifSettings.visibility = View.GONE
            binding.layoutPreviewLoading.visibility = View.GONE
        }
    }

    // ── Edit Sections ──

    private fun setupEditSections() {
        setupTrimSection()
        setupCropSection()
        setupFormatToggle()
    }

    private fun setupTrimSection() {
        // Pause player while dragging so the frame stays visible
        binding.trimRangeSlider.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: RangeSlider) {
                player?.pause()
            }
            override fun onStopTrackingTouch(slider: RangeSlider) {
                player?.play()
            }
        })

        binding.trimRangeSlider.addOnChangeListener(RangeSlider.OnChangeListener { slider, value, fromUser ->
            val values = slider.values
            val startSec = values[0]
            val endSec = values[1]
            updateTrimLabels(startSec, endSec)
            viewModel.trimStartMs = (startSec * 1000).toLong()
            viewModel.trimEndMs = (endSec * 1000).toLong()
            if (fromUser) {
                // Seek preview to whichever thumb is being dragged
                player?.seekTo((value * 1000).toLong())
                // Auto-enable trim as soon as the user moves a handle
                viewModel.trimEnabled = true
            }
        })
    }

    private fun setupTrimRange(info: VideoInfo) {
        val durationSec = info.duration.toFloat().coerceAtLeast(1f)
        binding.trimRangeSlider.valueFrom = 0f
        binding.trimRangeSlider.valueTo = durationSec
        binding.trimRangeSlider.values = listOf(0f, durationSec)
        updateTrimLabels(0f, durationSec)
        viewModel.trimStartMs = 0L
        viewModel.trimEndMs = info.duration * 1000L
        viewModel.trimEnabled = false
    }

    private fun updateTrimLabels(startSec: Float, endSec: Float) {
        binding.tvTrimStart.text = MediaUtils.formatDuration((startSec * 1000).toLong())
        binding.tvTrimEnd.text = MediaUtils.formatDuration((endSec * 1000).toLong())
    }

    private fun setupCropSection() {
        binding.cbCropEnabled.setOnCheckedChangeListener { _, isChecked ->
            viewModel.cropEnabled = isChecked
            TransitionManager.beginDelayedTransition(binding.cardPlayer)
            binding.cropOverlayView.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.layoutCropSettings.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Orientation chips: switching resets to Free and shows relevant ratio chips
        binding.chipGroupOrientation.check(R.id.chipOrientLandscape)
        binding.chipGroupOrientation.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            updateOrientationChips(isPortrait = id == R.id.chipOrientPortrait)
            binding.chipGroupRatio.check(R.id.chipRatioFree)
        }

        binding.chipGroupRatio.check(R.id.chipRatioFree)
        binding.chipGroupRatio.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            binding.cropOverlayView.cropRatio = when (id) {
                R.id.chipRatio169 -> CropRatio.RATIO_16_9
                R.id.chipRatio43  -> CropRatio.RATIO_4_3
                R.id.chipRatio11  -> CropRatio.RATIO_1_1
                R.id.chipRatio916 -> CropRatio.RATIO_9_16
                R.id.chipRatio34  -> CropRatio.RATIO_3_4
                R.id.chipRatio45  -> CropRatio.RATIO_4_5
                else              -> CropRatio.FREE
            }
        }

        binding.cropOverlayView.onCropChanged = { updateCropInfo() }

        binding.cbCropEnabled.isChecked = false
        binding.cropOverlayView.visibility = View.GONE
        binding.layoutCropSettings.visibility = View.GONE
    }

    private fun updateOrientationChips(isPortrait: Boolean) {
        binding.chipRatio169.visibility = if (isPortrait) View.GONE else View.VISIBLE
        binding.chipRatio43.visibility  = if (isPortrait) View.GONE else View.VISIBLE
        binding.chipRatio916.visibility = if (isPortrait) View.VISIBLE else View.GONE
        binding.chipRatio34.visibility  = if (isPortrait) View.VISIBLE else View.GONE
        binding.chipRatio45.visibility  = if (isPortrait) View.VISIBLE else View.GONE
    }

    private fun setupCropPreview(info: VideoInfo) {
        // Set ACTUAL video dimensions so getCropInVideoCoords() maps to real pixel space,
        // not the 360p preview resolution. Never overwrite from onVideoSizeChanged.
        if (info.videoWidth > 0 && info.videoHeight > 0) {
            binding.cropOverlayView.videoWidth = info.videoWidth
            binding.cropOverlayView.videoHeight = info.videoHeight
            viewModel.cropRefWidth = info.videoWidth
            viewModel.cropRefHeight = info.videoHeight
        }
        binding.cbCropEnabled.isChecked = false
        binding.cropOverlayView.visibility = View.GONE
        binding.layoutCropSettings.visibility = View.GONE
        binding.chipGroupOrientation.check(R.id.chipOrientLandscape)
        updateOrientationChips(isPortrait = false)
        binding.chipGroupRatio.check(R.id.chipRatioFree)
    }

    private fun updateCropInfo() {
        val rect = binding.cropOverlayView.getCropInVideoCoords()
        binding.tvCropInfo.text = "Crop: ${rect.width()}\u00D7${rect.height()} at (${rect.left}, ${rect.top})"
    }

    private fun setupFormatToggle() {
        binding.toggleOutputFormat.check(R.id.btnFormatMp4)

        binding.toggleOutputFormat.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val isGif = checkedId == R.id.btnFormatGif
            viewModel.outputFormat = if (isGif) OutputFormat.GIF else OutputFormat.MP4
            TransitionManager.beginDelayedTransition(binding.cardOptions)
            binding.layoutGifSettings.visibility = if (isGif) View.VISIBLE else View.GONE
            if (isGif) updateGifEstimate()
        }

        // FPS toggle
        binding.toggleFps.check(R.id.btnFps15)
        binding.toggleFps.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            viewModel.gifFps = when (checkedId) {
                R.id.btnFps10 -> 10
                R.id.btnFps15 -> 15
                R.id.btnFps24 -> 24
                else -> 15
            }
            updateGifEstimate()
        }

        // Quality toggle
        binding.toggleQuality.check(R.id.btnQualityMed)
        binding.toggleQuality.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            viewModel.gifMaxWidth = when (checkedId) {
                R.id.btnQualityLow -> 320
                R.id.btnQualityMed -> 480
                R.id.btnQualityHigh -> 640
                else -> 480
            }
            updateGifEstimate()
        }
    }

    private fun updateGifEstimate() {
        binding.tvGifEstimate.text = "Estimated size: ~${viewModel.estimateGifSize()}"
    }

    // ── Basic / Advanced tab ──

    private fun setupModeTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Basic"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Advanced"))

        // Restore saved tab before attaching the listener to avoid spurious saves
        val startAdvanced = ThemeManager.persistTab(this) && ThemeManager.getLastTab(this) == 1
        isAdvancedMode = startAdvanced
        if (startAdvanced) binding.tabLayout.selectTab(binding.tabLayout.getTabAt(1))

        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                applyTabMode(advanced = tab.position == 1)
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })
    }

    private fun applyTabMode(advanced: Boolean) {
        isAdvancedMode = advanced
        if (ThemeManager.persistTab(this)) {
            ThemeManager.setLastTab(this, if (advanced) 1 else 0)
        }
        if (advanced) {
            // Re-apply current states so player/trim/crop appear if video is already loaded
            handlePreviewState(viewModel.previewState.value)
            if (viewModel.uiState.value is UiState.VideoInfoLoaded) {
                binding.tvOutputFormatLabel.visibility = View.VISIBLE
                binding.toggleOutputFormat.visibility = View.VISIBLE
            }
        } else {
            // Hide all advanced-only elements and pause playback
            binding.cardPlayer.visibility = View.GONE
            binding.layoutTrimControls.visibility = View.GONE
            binding.layoutCropControls.visibility = View.GONE
            binding.tvOutputFormatLabel.visibility = View.GONE
            binding.toggleOutputFormat.visibility = View.GONE
            binding.layoutGifSettings.visibility = View.GONE
            // Reset GIF → MP4 so downloads in basic mode always use MP4
            binding.toggleOutputFormat.check(R.id.btnFormatMp4)
            // Clear all edit state so basic downloads are never processed through FFmpeg
            viewModel.resetEditState()
            player?.pause()
        }
    }

    // ── Helpers ──

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.urlInput.windowToken, 0)
        binding.urlInput.clearFocus()
    }

    private fun updatePositionIndicator(posMs: Long) {
        val slider = binding.trimRangeSlider
        if (slider.width == 0) return
        val valueRange = slider.valueTo - slider.valueFrom
        if (valueRange <= 0f) return
        val posSec = posMs / 1000f
        val fraction = ((posSec - slider.valueFrom) / valueRange).coerceIn(0f, 1f)
        // Material Slider places thumb centres at trackSidePadding from the view edge.
        // trackSidePadding = max(thumbRadius, minTouchTargetSize/2) = max(thumbRadius, 24dp)
        val density = resources.displayMetrics.density
        val trackSidePadding = maxOf(slider.thumbRadius.toFloat(), 24f * density)
        val paddingLeft = slider.paddingLeft.toFloat()
        val paddingRight = slider.paddingRight.toFloat()
        val trackWidth = slider.width - paddingLeft - paddingRight - 2f * trackSidePadding
        val indicatorHalfW = 1.5f * density // half of 3dp
        val x = paddingLeft + trackSidePadding + fraction * trackWidth - indicatorHalfW
        binding.positionIndicator.translationX = x
    }

    private fun showPlayPauseOverlay(playing: Boolean) {
        binding.ivPlayPauseOverlay.setImageResource(
            if (playing) R.drawable.ic_play_arrow else R.drawable.ic_pause
        )
        binding.ivPlayPauseOverlay.alpha = 1f
        binding.ivPlayPauseOverlay.visibility = View.VISIBLE
        binding.ivPlayPauseOverlay.animate()
            .alpha(0f)
            .setDuration(500)
            .setStartDelay(300)
            .withEndAction { binding.ivPlayPauseOverlay.visibility = View.GONE }
            .start()
    }

    private fun releasePlayer() {
        timeHandler.removeCallbacks(timeRunnable)
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
