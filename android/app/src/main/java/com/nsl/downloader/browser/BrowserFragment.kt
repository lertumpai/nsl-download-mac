package com.nsl.downloader.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nsl.downloader.R
import com.nsl.downloader.databinding.FragmentBrowserBinding
import com.nsl.downloader.service.DownloadService
import com.nsl.downloader.service.HlsDownloader
import com.nsl.downloader.service.PlaybackBus
import com.nsl.downloader.service.PlaybackService
import com.nsl.downloader.util.Prefs
import com.nsl.downloader.util.detectVideoType
import com.nsl.downloader.util.guessTitleFromUrl
import com.nsl.downloader.util.VideoType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class BrowserFragment : Fragment() {

    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!

    private val probeClient by lazy { OkHttpClient() }
    private val prefs by lazy { Prefs(requireContext()) }

    private inner class Tab {
        val sniffer = VideoSniffer()

        /** Whether this tab currently has a playing <video>/<audio>. */
        var isPlaying = false
        var mediaTitle = ""

        @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
        val webView: BackgroundWebView = BackgroundWebView(requireContext()).also { wv ->
            wv.backgroundPlaybackEnabled = prefs.backgroundPlaybackEnabled
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportMultipleWindows(true)
                userAgentString = wv.settings.userAgentString.replace("; wv", "")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Keeps the offscreen/backgrounded WebView rasterising instead of
                // being torn down while media plays.
                wv.settings.offscreenPreRaster = true
            }
            wv.addJavascriptInterface(MediaBridge(), "NSLBridge")
            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?, request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString()
                    if (url != null) {
                        if (prefs.adBlockEnabled && AdBlocker.shouldBlock(url)) {
                            return AdBlocker.blockedResponse()
                        }
                        val mime = request.requestHeaders["Accept"]
                        sniffer.consider(url, mime, request.requestHeaders ?: emptyMap())
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    sniffer.clear()
                    setPlaying(false, "")
                    injectBackgroundScript(view)
                    if (this@Tab === currentTab) {
                        binding.urlBar.setText(url)
                        updateFab()
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectBackgroundScript(view)
                    injectAdBlockScript(view)
                    if (this@Tab === currentTab) {
                        binding.progressBar.visibility = View.GONE
                        updateFab()
                    }
                }
            }
            wv.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress >= 40) injectAdBlockScript(view)
                    if (this@Tab === currentTab) {
                        binding.progressBar.progress = newProgress
                        binding.progressBar.visibility =
                            if (newProgress < 100) View.VISIBLE else View.GONE
                    }
                }

                override fun onCreateWindow(
                    view: WebView?, isDialog: Boolean,
                    isUserGesture: Boolean, resultMsg: Message?
                ): Boolean {
                    if (!isUserGesture || resultMsg == null) return false
                    val popupWebView = WebView(requireContext())
                    popupWebView.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(v: WebView?, url: String?, fav: Bitmap?) {
                            url?.let {
                                popupWebView.destroy()
                                addNewTab(it)
                            }
                        }
                    }
                    (resultMsg.obj as? WebView.WebViewTransport)?.webView = popupWebView
                    resultMsg.sendToTarget()
                    return true
                }
            }
            wv.setOnLongClickListener {
                val hit = wv.hitTestResult
                val url = hit.extra
                if (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE && !url.isNullOrEmpty()) {
                    addNewTab(url)
                    Toast.makeText(requireContext(), "Opened in new tab", Toast.LENGTH_SHORT).show()
                    true
                } else {
                    false
                }
            }
            sniffer.onNewVideo = {
                if (this === currentTab) {
                    requireActivity().runOnUiThread { updateFab() }
                }
            }
        }

        fun injectBackgroundScript(view: WebView?) {
            if (!prefs.backgroundPlaybackEnabled) return
            view?.evaluateJavascript(BrowserScripts.BACKGROUND_PLAYBACK, null)
        }

        fun injectAdBlockScript(view: WebView?) {
            if (!prefs.adBlockEnabled) return
            view?.evaluateJavascript(BrowserScripts.AD_BLOCK_COSMETIC, null)
        }

        /** Called from the JS bridge (worker thread) and from page transitions. */
        fun setPlaying(playing: Boolean, title: String) {
            if (isPlaying == playing) return
            isPlaying = playing
            mediaTitle = title
            syncPlaybackService()
        }

        /** Bridge exposed to page JS as `NSLBridge`. */
        inner class MediaBridge {
            @android.webkit.JavascriptInterface
            fun onMediaState(playing: Boolean, title: String?) {
                webView.post { setPlaying(playing, title.orEmpty()) }
            }
        }
    }

    private val tabs = mutableListOf<Tab>()
    private var currentTabIndex = 0
    private var playbackServiceRunning = false
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val stopPlaybackRunnable = Runnable { stopPlaybackServiceNow() }
    private val currentTab get() = tabs[currentTabIndex]
    private val currentWebView get() = currentTab.webView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupControls()
        PlaybackBus.stopRequested = { if (isAdded) pauseAllMedia(notifyService = false) }
        addNewTab("https://www.google.com")
    }

    /**
     * Starts/stops the foreground service that keeps the process (and the CPU)
     * alive so WebView media survives backgrounding and screen-off.
     */
    private fun syncPlaybackService() {
        val ctx = context ?: return
        val playing = tabs.firstOrNull { it.isPlaying }
        uiHandler.removeCallbacks(stopPlaybackRunnable)
        if (playing != null && prefs.backgroundPlaybackEnabled) {
            val title = playing.mediaTitle.takeIf { it.isNotBlank() }
                ?: playing.webView.title.orEmpty()
            PlaybackService.start(ctx, title)
            playbackServiceRunning = true
        } else if (playbackServiceRunning) {
            // Don't tear the service down on every gap: playlists, ad breaks and
            // quality switches pause for a moment, and a foreground service
            // cannot be restarted from the background on Android 12+.
            uiHandler.postDelayed(stopPlaybackRunnable, PLAYBACK_STOP_DELAY_MS)
        }
    }

    private fun stopPlaybackServiceNow() {
        if (!playbackServiceRunning || tabs.any { it.isPlaying }) return
        playbackServiceRunning = false
        context?.let { PlaybackService.stop(it) }
    }

    /**
     * Pauses every media element in every tab. When the notification's "Stop"
     * action triggers this the service is already tearing itself down, so
     * [notifyService] is false — otherwise we'd bounce a stop back at it.
     */
    private fun pauseAllMedia(notifyService: Boolean) {
        uiHandler.removeCallbacks(stopPlaybackRunnable)
        if (!notifyService) playbackServiceRunning = false
        val js = "document.querySelectorAll('video,audio').forEach(function(m){m.pause();});"
        tabs.forEach { tab ->
            tab.webView.evaluateJavascript(js, null)
            tab.setPlaying(false, "")
        }
        if (notifyService) {
            uiHandler.removeCallbacks(stopPlaybackRunnable)
            stopPlaybackServiceNow()
        }
    }

    private fun addNewTab(url: String = "https://www.google.com") {
        val tab = Tab()
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        binding.webViewContainer.addView(tab.webView, params)
        tabs.add(tab)
        switchToTab(tabs.size - 1)
        tab.webView.loadUrl(url)
    }

    private fun switchToTab(index: Int) {
        tabs.forEach { it.webView.visibility = View.GONE }
        currentTabIndex = index
        currentTab.webView.visibility = View.VISIBLE
        binding.urlBar.setText(currentWebView.url ?: "")
        updateFab()
        updateTabCount()
    }

    private fun closeTab(index: Int) {
        if (tabs.size <= 1) return
        val tab = tabs.removeAt(index)
        tab.setPlaying(false, "")
        binding.webViewContainer.removeView(tab.webView)
        tab.webView.destroy()
        val newIndex = if (index >= tabs.size) tabs.size - 1 else index
        switchToTab(newIndex)
    }

    private fun updateTabCount() {
        binding.btnTabCount.text = tabs.size.toString()
    }

    private fun showTabSwitcher() {
        val titles = tabs.mapIndexed { i, tab ->
            val mark = if (i == currentTabIndex) "▶ " else "   "
            val label = (tab.webView.title?.takeIf { it.isNotBlank() } ?: tab.webView.url ?: "New Tab")
                .let { if (it.length > 45) it.take(45) + "…" else it }
            "$mark$label"
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Tabs (${tabs.size})")
            .setItems(titles) { _, which -> switchToTab(which) }
            .setPositiveButton("New tab") { _, _ -> addNewTab() }
            .setNeutralButton("Close current") { _, _ -> closeTab(currentTabIndex) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupControls() {
        binding.urlBar.setOnEditorActionListener { _, _, _ ->
            navigateTo(binding.urlBar.text.toString())
            true
        }
        binding.btnMenu.setOnClickListener { showMenu(it) }
        binding.btnBack.setOnClickListener {
            if (currentWebView.canGoBack()) currentWebView.goBack()
        }
        binding.btnForward.setOnClickListener {
            if (currentWebView.canGoForward()) currentWebView.goForward()
        }
        binding.btnReload.setOnClickListener { currentWebView.reload() }
        binding.btnNewTab.setOnClickListener { addNewTab() }
        binding.btnTabCount.setOnClickListener { showTabSwitcher() }
        binding.fabDownload.setOnClickListener { showVideoPicker() }
    }

    /** Overflow menu: ad block + background playback toggles. */
    private fun showMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, MENU_AD_BLOCK, 0, getString(R.string.menu_ad_block)).apply {
            isCheckable = true
            isChecked = prefs.adBlockEnabled
        }
        popup.menu.add(0, MENU_BACKGROUND, 1, getString(R.string.menu_background_playback)).apply {
            isCheckable = true
            isChecked = prefs.backgroundPlaybackEnabled
        }
        popup.menu.add(0, MENU_NEW_TAB, 2, getString(R.string.tab_new))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_AD_BLOCK -> {
                    prefs.adBlockEnabled = !prefs.adBlockEnabled
                    toast(if (prefs.adBlockEnabled) R.string.ad_block_on else R.string.ad_block_off)
                    currentWebView.reload()
                }
                MENU_BACKGROUND -> {
                    val enabled = !prefs.backgroundPlaybackEnabled
                    prefs.backgroundPlaybackEnabled = enabled
                    tabs.forEach { it.webView.backgroundPlaybackEnabled = enabled }
                    if (!enabled) pauseAllMedia(notifyService = true) else syncPlaybackService()
                    toast(if (enabled) R.string.background_on else R.string.background_off)
                }
                MENU_NEW_TAB -> addNewTab()
            }
            true
        }
        popup.show()
    }

    private fun toast(resId: Int) =
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()

    private fun navigateTo(input: String) {
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.google.com/search?q=${android.net.Uri.encode(input)}"
        }
        currentWebView.loadUrl(url)
    }

    private fun updateFab() {
        val count = currentTab.sniffer.count()
        if (count > 0) {
            binding.fabDownload.show()
            binding.fabDownload.text = "Download ($count)"
        } else {
            binding.fabDownload.hide()
        }
    }

    private fun showVideoPicker() {
        val videos = currentTab.sniffer.snapshot()
        if (videos.isEmpty()) {
            Toast.makeText(requireContext(), "No videos detected yet", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = videos.map { c ->
            val type = when {
                c.url.contains(".m3u8") -> "[HLS] "
                c.url.contains(".mpd") -> "[DASH] "
                else -> "[Direct] "
            }
            type + c.url.substringBefore('?').substringAfterLast('/').take(40)
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Detected videos")
            .setItems(labels) { _, which ->
                onVideoChosen(videos[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onVideoChosen(candidate: VideoSniffer.Candidate) {
        val pageTitle = currentWebView.title ?: guessTitleFromUrl(candidate.url)
        val headers = buildHeaders(candidate.url, candidate.headers)

        if (detectVideoType(candidate.url) != VideoType.HLS) {
            startDownload(candidate.url, pageTitle, headers)
            return
        }

        Toast.makeText(requireContext(), "Reading qualities…", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val variants = withContext(Dispatchers.IO) {
                runCatching { HlsDownloader(probeClient).listVariants(candidate.url, headers) }
                    .getOrDefault(emptyList())
            }
            if (!isAdded) return@launch
            showQualityDialog(candidate.url, pageTitle, variants, headers)
        }
    }

    private fun showQualityDialog(
        playlistUrl: String,
        title: String,
        variants: List<HlsDownloader.Variant>,
        headers: HashMap<String, String>
    ) {
        val real = variants.filter { it.height > 0 }
        if (real.size <= 1) {
            startDownload(playlistUrl, title, headers)
            return
        }
        val sorted = real.sortedByDescending { it.height }
        val labels = sorted.map { v ->
            val bw = if (v.bandwidth > 0) " • ${v.bandwidth / 1000} kbps" else ""
            "${v.height}p$bw"
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select quality")
            .setItems(labels) { _, which ->
                val v = sorted[which]
                startDownload(v.url, "$title (${v.height}p)", headers)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startDownload(url: String, title: String, headers: HashMap<String, String>) {
        DownloadService.start(requireContext(), url, title, headers)
        Toast.makeText(requireContext(), "Download started", Toast.LENGTH_SHORT).show()
        (activity as? com.nsl.downloader.MainActivity)?.showLibrary()
    }

    private fun buildHeaders(videoUrl: String, captured: Map<String, String>): HashMap<String, String> {
        val h = HashMap<String, String>()
        captured.forEach { (k, v) -> if (v.isNotBlank()) h[k] = v }
        h["User-Agent"] = currentWebView.settings.userAgentString
        currentWebView.url?.let { page ->
            h.getOrPut("Referer") { page }
            runCatching {
                val u = android.net.Uri.parse(page)
                h.getOrPut("Origin") { "${u.scheme}://${u.host}" }
            }
        }
        runCatching {
            CookieManager.getInstance().getCookie(videoUrl)?.takeIf { it.isNotBlank() }?.let {
                h["Cookie"] = it
            }
        }
        return h
    }

    fun onBackPressed(): Boolean {
        return if (currentWebView.canGoBack()) {
            currentWebView.goBack()
            true
        } else false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        PlaybackBus.stopRequested = null
        uiHandler.removeCallbacks(stopPlaybackRunnable)
        if (playbackServiceRunning) {
            playbackServiceRunning = false
            context?.let { PlaybackService.stop(it) }
        }
        tabs.forEach {
            binding.webViewContainer.removeView(it.webView)
            it.webView.destroy()
        }
        tabs.clear()
        _binding = null
    }

    private companion object {
        const val MENU_AD_BLOCK = 1
        const val MENU_BACKGROUND = 2
        const val MENU_NEW_TAB = 3

        /** Grace period before the playback service is torn down. */
        const val PLAYBACK_STOP_DELAY_MS = 30_000L
    }
}
