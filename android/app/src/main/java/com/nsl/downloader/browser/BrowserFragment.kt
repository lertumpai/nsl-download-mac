package com.nsl.downloader.browser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.util.Rational
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
import com.nsl.downloader.data.DownloadQueueBus
import com.nsl.downloader.databinding.FragmentBrowserBinding
import com.nsl.downloader.service.DownloadService
import com.nsl.downloader.service.HlsDownloader
import com.nsl.downloader.movies.MovieResolver
import com.nsl.downloader.service.PlaybackBus
import com.nsl.downloader.service.PlaybackService
import com.nsl.downloader.settings.SettingsActivity
import com.nsl.downloader.util.PlaybackMode
import com.nsl.downloader.util.Prefs
import com.nsl.downloader.util.detectVideoType
import com.nsl.downloader.util.guessTitleFromUrl
import com.nsl.downloader.util.VideoType
import com.nsl.downloader.vk.VkResolver
import com.nsl.downloader.vk.VkScripts
import com.nsl.downloader.youtube.YouTubeResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

        /** Intrinsic size of the tab's video, reported by the page script. */
        var videoWidth = 0
        var videoHeight = 0

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
                    videoWidth = 0
                    videoHeight = 0
                    injectBackgroundScript(view)
                    injectVkScript(view, url)
                    if (this@Tab === currentTab) {
                        binding.urlBar.setText(url)
                        updateFab()
                    }
                }

                /**
                 * VK opens a video by rewriting the URL, never by loading a
                 * document, so this is the only callback that fires when the
                 * user picks a clip out of a feed — without it the Download
                 * button would keep judging the page the tab first landed on.
                 */
                override fun doUpdateVisitedHistory(
                    view: WebView?, url: String?, isReload: Boolean
                ) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    injectVkScript(view, url)
                    if (this@Tab === currentTab) updateFab()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectBackgroundScript(view)
                    injectAdBlockScript(view)
                    injectVkScript(view, url)
                    if (pictureInPictureActive && this@Tab === currentTab) {
                        view?.evaluateJavascript(BrowserScripts.PIP_FIT_ON, null)
                    }
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

                /**
                 * Pages (YouTube included) request fullscreen by handing us a
                 * view to display; the activity hosts it above the whole UI so
                 * it also covers the tab bar and the system bars.
                 */
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view == null || callback == null) return
                    val activity = activity as? com.nsl.downloader.MainActivity
                    if (activity == null) {
                        callback.onCustomViewHidden()
                        return
                    }
                    activity.enterFullscreen(view, callback)
                }

                override fun onHideCustomView() {
                    (activity as? com.nsl.downloader.MainActivity)?.exitFullscreen()
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
                showLinkMenu(hit)
            }
            sniffer.onNewVideo = {
                if (this === currentTab) {
                    requireActivity().runOnUiThread { updateFab() }
                }
            }
        }

        /**
         * The media reporter always goes in — both the playback service and the
         * PiP trigger depend on it.
         *
         * The visibility-pinning half is needed by both non-OFF modes: page
         * scripts (YouTube's included) pause themselves on `visibilitychange`,
         * and entering Picture-in-Picture looks exactly like that to the page.
         */
        fun injectBackgroundScript(view: WebView?) {
            view?.evaluateJavascript(BrowserScripts.MEDIA_STATE, null)
            if (prefs.playbackMode == PlaybackMode.OFF) return
            view?.evaluateJavascript(BrowserScripts.BACKGROUND_PLAYBACK, null)
            view?.evaluateJavascript(BrowserScripts.PAUSE_GUARD, null)
        }

        fun injectAdBlockScript(view: WebView?) {
            if (!prefs.adBlockEnabled) return
            view?.evaluateJavascript(BrowserScripts.AD_BLOCK_COSMETIC, null)
        }

        /**
         * Starts watching for VK's player parameters. The script guards itself
         * against running twice, so the repeated calls across the page
         * lifecycle cost nothing.
         */
        fun injectVkScript(view: WebView?, url: String?) {
            if (url == null || !VkResolver.isVkUrl(url)) return
            view?.evaluateJavascript(VkScripts.HOOK, null)
        }

        /** Called from the JS bridge (worker thread) and from page transitions. */
        fun setPlaying(playing: Boolean, title: String) {
            if (isPlaying == playing) return
            isPlaying = playing
            mediaTitle = title
            syncPlaybackService()
            // Android 12+ decides whether to auto-enter PiP from the params it
            // already holds, so they have to track playback as it changes.
            (activity as? com.nsl.downloader.MainActivity)?.refreshPictureInPictureParams()
        }

        /**
         * The floating window's shape is fixed when Android creates it, so the
         * page has to tell us the video's proportions *before* the handoff —
         * hence the running report rather than a query at PiP time.
         */
        fun setVideoAspect(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            if (videoWidth == width && videoHeight == height) return
            videoWidth = width
            videoHeight = height
            if (this === currentTab) {
                (activity as? com.nsl.downloader.MainActivity)?.refreshPictureInPictureParams()
            }
        }

        /** Bridge exposed to page JS as `NSLBridge`. */
        inner class MediaBridge {
            @android.webkit.JavascriptInterface
            fun onMediaState(playing: Boolean, title: String?) {
                webView.post { setPlaying(playing, title.orEmpty()) }
            }

            @android.webkit.JavascriptInterface
            fun onVideoAspect(width: Int, height: Int) {
                webView.post { if (isAdded) setVideoAspect(width, height) }
            }
        }
    }

    private val tabs = mutableListOf<Tab>()
    private var currentTabIndex = 0
    private var playbackServiceRunning = false
    private var pictureInPictureActive = false
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val stopPlaybackRunnable = Runnable { stopPlaybackServiceNow() }

    /** Runs only if the PiP callback never arrived — see [onStop]. */
    private val pipTeardownRunnable = Runnable {
        if (_binding == null || isPictureInPicture()) return@Runnable
        Log.d(PIP_TAG, "no PiP window arrived — restoring layout and pausing")
        leavePictureInPictureLayout()
        pauseAllMedia(notifyService = true)
    }
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
        onFooterCollapsedChanged(
            (activity as? com.nsl.downloader.MainActivity)?.isFooterCollapsed() ?: false
        )
    }

    /** True while any tab has playing media — drives the PiP decision. */
    fun hasPlayingMedia(): Boolean = tabs.isNotEmpty() && tabs.any { it.isPlaying }

    /**
     * Last chance to act before the window goes away.
     *
     * With auto-enter armed there is no callback between the home gesture and
     * the PiP window existing, so the playing tab has to already be immune to
     * the window-visibility drop — otherwise Chromium stops the decoder and the
     * floating window shows a frozen frame.
     */
    override fun onPause() {
        super.onPause()
        if (_binding == null || !prefs.pictureInPictureEnabled) return
        if (activity?.isChangingConfigurations == true) return
        if (!hasPlayingMedia()) return
        if (!currentTab.isPlaying) {
            tabs.indexOfFirst { it.isPlaying }.takeIf { it >= 0 }?.let { switchToTab(it) }
        }
        tabs.forEach { it.webView.backgroundPlaybackEnabled = it === currentTab }
        currentWebView.evalLogged("mark", BrowserScripts.MARK_PIP_VIDEO)
    }

    /**
     * PiP never happened (feature unavailable, or the system refused). Undo the
     * pre-arming from [onPause]: a hidden WebView must not be left playing audio
     * with no notification and no way to stop it.
     *
     * The teardown is deferred instead of running inline. With auto-enter the
     * two callbacks race, and on the devices where `onStop` wins the inline
     * version tore the PiP layout down and paused the video a moment *after*
     * the floating window had already appeared — which is exactly what a black
     * window showing a paused player looks like.
     */
    override fun onStop() {
        super.onStop()
        if (_binding == null || !prefs.pictureInPictureEnabled) return
        if (activity?.isChangingConfigurations == true) return
        if (isPictureInPicture()) return
        uiHandler.removeCallbacks(pipTeardownRunnable)
        uiHandler.postDelayed(pipTeardownRunnable, PIP_CALLBACK_GRACE_MS)
    }

    override fun onStart() {
        super.onStart()
        uiHandler.removeCallbacks(pipTeardownRunnable)
    }

    /** True once the window really is floating, whichever callback got here first. */
    private fun isPictureInPicture(): Boolean =
        pictureInPictureActive ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                activity?.isInPictureInPictureMode == true)

    /**
     * Called just before the activity asks the system for a PiP window.
     *
     * Deliberately synchronous and fire-and-forget: the activity must call
     * `enterPictureInPictureMode()` in the same call stack as `onUserLeaveHint`,
     * so nothing here may wait on a WebView round trip. The page-side CSS lands
     * a few milliseconds later and is re-applied from
     * [onPictureInPictureModeChanged] once the window really exists.
     */
    fun prepareForPictureInPicture() {
        pictureInPictureActive = true
        if (!currentTab.isPlaying) {
            tabs.indexOfFirst { it.isPlaying }.takeIf { it >= 0 }?.let { switchToTab(it) }
        }
        applyPictureInPictureLayout(BrowserScripts.PIP_FIT_PREPARE)
    }

    /**
     * PiP reparents the activity's render surface. Keep the playing WebView
     * visible to Chromium throughout that handoff so its decoder is not paused;
     * the page script separately pins the real video surface into the resized
     * viewport.
     *
     * Idempotent, because there are two ways in: our own explicit request, and
     * the system's auto-enter on Android 12+, which arrives with no warning.
     */
    private fun applyPictureInPictureLayout(fitScript: String) {
        if (_binding == null) return
        tabs.forEach { tab ->
            val keepAlive = tab === currentTab
            tab.webView.backgroundPlaybackEnabled = keepAlive
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                tab.webView.settings.offscreenPreRaster = keepAlive
            }
        }
        // Normal browsing deliberately uses a wide overview viewport. In a
        // tiny PiP window that preserves the old phone-width layout and Android
        // displays only a cropped strip of YouTube's video. Let the viewport
        // follow the actual window while PiP is being prepared.
        currentWebView.settings.useWideViewPort = false
        currentWebView.settings.loadWithOverviewMode = false
        currentWebView.setBackgroundColor(android.graphics.Color.BLACK)
        binding.root.setBackgroundColor(android.graphics.Color.BLACK)

        currentWebView.evaluateJavascript(BrowserScripts.PAUSE_GUARD, null)
        currentWebView.evaluateJavascript(BrowserScripts.setPauseBlocked(true), null)
        currentWebView.evalLogged("fit", fitScript)
        currentWebView.requestLayout()
        currentWebView.invalidate()
    }

    /**
     * Shape of the floating window: the playing video's own proportions, so a
     * vertical clip floats vertically instead of sitting letterboxed inside a
     * 16:9 box. Falls back to 16:9 until the page has reported a size.
     *
     * Android rejects anything outside roughly 1:2.39…2.39:1, so the ratio is
     * clamped rather than allowed to throw at `enterPictureInPictureMode()`.
     */
    fun pictureInPictureAspect(): Rational {
        val tab = tabs.getOrNull(currentTabIndex) ?: return Rational(16, 9)
        val w = tab.videoWidth
        val h = tab.videoHeight
        if (w <= 0 || h <= 0) return Rational(16, 9)
        val ratio = w.toFloat() / h
        return when {
            ratio > MAX_PIP_RATIO -> Rational(239, 100)
            ratio < MIN_PIP_RATIO -> Rational(100, 239)
            else -> Rational(w, h)
        }
    }

    /**
     * Screen-space rectangle occupied by [BrowserScripts.PIP_FIT_PREPARE].
     * Supplying it to Android prevents the system from scaling/cropping the
     * whole portrait browser when it creates the floating window.
     */
    fun pictureInPictureSourceRect(): Rect? {
        if (_binding == null || tabs.isEmpty()) return null
        val rect = Rect()
        if (!currentWebView.getGlobalVisibleRect(rect) || rect.width() <= 0) return null
        val aspect = pictureInPictureAspect()
        // Same top-left anchored fit the page script applies, so the hint and
        // the pixels actually on screen describe the same rectangle.
        val scale = minOf(
            rect.width().toFloat() / aspect.numerator,
            rect.height().toFloat() / aspect.denominator
        )
        rect.right = rect.left + (aspect.numerator * scale).toInt().coerceAtLeast(1)
        rect.bottom = rect.top + (aspect.denominator * scale).toInt().coerceAtLeast(1)
        return rect
    }

    /** Undo preparation if Android rejects the PiP request. */
    fun onPictureInPictureEntryFailed() {
        if (!pictureInPictureActive) return
        leavePictureInPictureLayout()
    }

    /**
     * In a PiP window only the page itself is shown. FragmentActivity dispatches
     * this for us when the activity's own callback runs.
     */
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (_binding == null) return
        pictureInPictureActive = isInPictureInPictureMode
        binding.topBar.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        binding.progressBar.visibility = View.GONE
        if (isInPictureInPictureMode) binding.fabDownload.hide() else updateFab()

        if (isInPictureInPictureMode) {
            // On Android 12+ the system can auto-enter without ever calling
            // prepareForPictureInPicture, so the full setup has to run here too.
            applyPictureInPictureLayout(BrowserScripts.PIP_FIT_ON)
            // The real invisible/visible handoff has completed at this point.
            // Pin the active WebView visible now so Chromium does not suspend
            // decoding merely because the hosting activity remains paused.
            currentWebView.keepPlayingInPictureInPicture()
            // The page may not pause itself while it floats over another app:
            // YouTube calls pause() several times a second in this state.
            tabs.forEach {
                it.webView.evaluateJavascript(BrowserScripts.PAUSE_GUARD, null)
                it.webView.evaluateJavascript(BrowserScripts.setPauseBlocked(true), null)
            }
            currentWebView.evalLogged("resume", BrowserScripts.RESUME_PIP_MEDIA)
            // Belt and braces: if the transition still managed to pause the
            // video, start it again once the window has settled.
            resumeMediaSoon()
        } else {
            leavePictureInPictureLayout()
        }
    }

    /**
     * PiP failures are invisible from the outside — the window is simply black —
     * so every step reports what the page actually did. Watch with
     * `adb logcat -s NSLPip`.
     */
    private fun WebView.evalLogged(label: String, js: String) =
        evaluateJavascript(js) { result -> Log.d(PIP_TAG, "$label -> $result") }

    private fun leavePictureInPictureLayout() {
        pictureInPictureActive = false
        tabs.forEach {
            it.webView.evaluateJavascript(BrowserScripts.PIP_FIT_OFF, null)
            it.webView.evaluateJavascript(BrowserScripts.setPauseBlocked(false), null)
            it.webView.backgroundPlaybackEnabled = prefs.backgroundPlaybackEnabled
            it.webView.settings.useWideViewPort = true
            it.webView.settings.loadWithOverviewMode = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                it.webView.settings.offscreenPreRaster = true
            }
            it.webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        if (_binding != null) binding.root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    private fun resumeMediaSoon() {
        listOf(150L, 600L, 1500L, 3000L).forEach { delayMs ->
            uiHandler.postDelayed({
                if (!isAdded) return@postDelayed
                tabs.forEach { tab ->
                    if (tab.isPlaying || tab === currentTab) {
                        // Chromium can suspend a WebView whose activity is no
                        // longer resumed; PiP is exactly that state.
                        tab.webView.onResume()
                        tab.webView.resumeTimers()
                        tab.webView.evalLogged("resume+$delayMs", BrowserScripts.RESUME_PIP_MEDIA)
                    }
                }
                currentWebView.invalidate()
            }, delayMs)
        }
    }

    /** Collapsing the footer shrinks the download button to its icon. */
    fun onFooterCollapsedChanged(collapsed: Boolean) {
        if (_binding == null) return
        if (collapsed) binding.fabDownload.shrink() else binding.fabDownload.extend()
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
        binding.fabDownload.setOnClickListener { onDownloadTapped() }
    }

    // ------------------------------------------------------ long-press menu

    /**
     * Long-pressing a link opens a chooser rather than silently opening a tab,
     * so "open in new tab" is a deliberate choice alongside copy/share/download.
     */
    private fun showLinkMenu(hit: WebView.HitTestResult): Boolean {
        val url = hit.extra
        val isLink = hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
            hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        val isImage = hit.type == WebView.HitTestResult.IMAGE_TYPE
        if (url.isNullOrEmpty() || (!isLink && !isImage)) return false

        val actions = buildList<Pair<String, () -> Unit>> {
            if (isLink) {
                add(getString(R.string.link_open_new_tab) to { addNewTabFromMenu(url) })
                add(getString(R.string.link_open_here) to { currentWebView.loadUrl(url) })
            }
            add(getString(R.string.link_copy) to { copyToClipboard(url) })
            add(getString(R.string.link_share) to { shareUrl(url) })
            add(getString(R.string.link_download) to { downloadLink(url) })
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(url.take(80))
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        return true
    }

    private fun addNewTabFromMenu(url: String) {
        addNewTab(url)
        Toast.makeText(requireContext(), R.string.opened_in_new_tab, Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(url: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
        Toast.makeText(requireContext(), R.string.link_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareUrl(url: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                },
                getString(R.string.link_share)
            )
        )
    }

    /** Sends a long-pressed link straight to the downloader. */
    private fun downloadLink(url: String) {
        if (YouTubeResolver.isYouTubeUrl(url)) {
            showYouTubeDialog(url, guessTitleFromUrl(url))
            return
        }
        // A VK video page is not a file: its stream URLs are signed per session
        // and only exist once the player has been handed them, so the video has
        // to be opened before there is anything to fetch.
        if (VkResolver.isVideoPageUrl(url)) {
            addNewTab(url)
            Toast.makeText(requireContext(), R.string.vk_opened_for_download, Toast.LENGTH_LONG)
                .show()
            return
        }
        startDownload(url, guessTitleFromUrl(url), buildHeaders(url, emptyMap()))
    }

    /** Overflow menu: ad block + playback mode + new tab. */
    private fun showMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, MENU_AD_BLOCK, 0, getString(R.string.menu_ad_block)).apply {
            isCheckable = true
            isChecked = prefs.adBlockEnabled
        }
        popup.menu.add(0, MENU_PLAYBACK_MODE, 1, getString(R.string.menu_playback_mode))
        popup.menu.add(0, MENU_NEW_TAB, 2, getString(R.string.tab_new))
        popup.menu.add(0, MENU_TOGGLE_FOOTER, 3, getString(R.string.menu_toggle_footer))
        popup.menu.add(0, MENU_SETTINGS, 4, getString(R.string.menu_settings))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_AD_BLOCK -> {
                    prefs.adBlockEnabled = !prefs.adBlockEnabled
                    toast(if (prefs.adBlockEnabled) R.string.ad_block_on else R.string.ad_block_off)
                    currentWebView.reload()
                }
                MENU_PLAYBACK_MODE -> showPlaybackModeDialog()
                MENU_NEW_TAB -> addNewTab()
                MENU_TOGGLE_FOOTER -> (activity as? com.nsl.downloader.MainActivity)?.let {
                    it.setFooterCollapsed(!it.isFooterCollapsed())
                }
                MENU_SETTINGS -> startActivity(
                    Intent(requireContext(), SettingsActivity::class.java)
                )
            }
            true
        }
        popup.show()
    }

    /**
     * Background audio and Picture-in-Picture are one exclusive choice: they
     * both claim the same media pipeline when the app leaves the foreground.
     */
    private fun showPlaybackModeDialog() {
        val modes = listOf(
            PlaybackMode.OFF to getString(R.string.playback_mode_off),
            PlaybackMode.BACKGROUND to getString(R.string.playback_mode_background),
            PlaybackMode.PICTURE_IN_PICTURE to getString(R.string.playback_mode_pip)
        )
        val current = modes.indexOfFirst { it.first == prefs.playbackMode }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.menu_playback_mode)
            .setSingleChoiceItems(modes.map { it.second }.toTypedArray(), current) { dialog, which ->
                applyPlaybackMode(modes[which].first)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyPlaybackMode(mode: PlaybackMode) {
        prefs.playbackMode = mode
        val backgroundOn = mode == PlaybackMode.BACKGROUND
        tabs.forEach { it.webView.backgroundPlaybackEnabled = backgroundOn }
        if (backgroundOn) syncPlaybackService() else pauseServiceForModeChange()
        toast(
            when (mode) {
                PlaybackMode.OFF -> R.string.playback_mode_off_toast
                PlaybackMode.BACKGROUND -> R.string.playback_mode_background_toast
                PlaybackMode.PICTURE_IN_PICTURE -> R.string.playback_mode_pip_toast
            }
        )
    }

    /**
     * Leaving background mode retires the foreground service but must not stop
     * playback — in PiP mode the video keeps running in its own window.
     */
    private fun pauseServiceForModeChange() {
        uiHandler.removeCallbacks(stopPlaybackRunnable)
        if (playbackServiceRunning) {
            playbackServiceRunning = false
            context?.let { PlaybackService.stop(it) }
        }
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

    /**
     * The button stays available on YouTube even with nothing sniffed: its
     * media never passes through [VideoSniffer] as a downloadable URL.
     */
    private fun updateFab() {
        val count = currentTab.sniffer.count()
        val youTube = isYouTubePage()
        val vk = isVkPage()
        val movie = MovieResolver.isMoviePage(currentWebView.url.orEmpty())
        if (count > 0 || youTube || vk || movie) {
            binding.fabDownload.show()
            binding.fabDownload.text =
                if (youTube || vk || movie) getString(R.string.download) else "Download ($count)"
            onFooterCollapsedChanged(
                (activity as? com.nsl.downloader.MainActivity)?.isFooterCollapsed() ?: false
            )
        } else {
            binding.fabDownload.hide()
        }
    }

    private fun isYouTubePage(): Boolean {
        val url = currentWebView.url ?: return false
        return YouTubeResolver.isYouTubeUrl(url) &&
            (YouTubeResolver.classify(url) != YouTubeResolver.Kind.OTHER ||
                YouTubeResolver.playlistIdOf(url) != null)
    }

    /**
     * Like [isYouTubePage], this only checks the URL: VK's streams are never
     * visible to [VideoSniffer], so the button has to be offered before there
     * is any proof that something downloadable is there.
     */
    private fun isVkPage(): Boolean {
        val url = currentWebView.url ?: return false
        return VkResolver.isVideoPageUrl(url)
    }

    private fun onDownloadTapped() {
        val url = currentWebView.url
        when {
            url != null && isYouTubePage() -> showYouTubeDialog(url, youTubePageTitle(url))
            url != null && isVkPage() -> showVkDialog(url)
            url != null && MovieResolver.isMoviePage(url) -> showMovieDialog(url)
            else -> showVideoPicker()
        }
    }

    /** The document title carries a " - YouTube" suffix nobody wants in a filename. */
    private fun youTubePageTitle(url: String): String =
        (currentWebView.title ?: guessTitleFromUrl(url))
            .removeSuffix(" - YouTube")
            .trim()
            .ifBlank { guessTitleFromUrl(url) }

    // ----------------------------------------------------------- YouTube UI

    /**
     * Entry point for a YouTube page. What gets asked depends on what the page
     * actually is:
     *
     *  - a video that also sits in a playlist → format first, then whether to
     *    download just this video or selected entries from the playlist.
     *  - a plain video → just the format.
     *  - a playlist page → the format, then the per-video picker.
     */
    private fun showYouTubeDialog(pageUrl: String, pageTitle: String) {
        val playlistId = YouTubeResolver.playlistIdOf(pageUrl)
        val kind = YouTubeResolver.classify(pageUrl)
        val isPlaylistPage = kind == YouTubeResolver.Kind.PLAYLIST
        val isStream = kind == YouTubeResolver.Kind.STREAM
        val listUrl = when {
            isPlaylistPage -> pageUrl
            playlistId != null -> YouTubeResolver.playlistUrlFor(playlistId)
            else -> null
        }

        when {
            isStream && listUrl != null -> chooseFormatAndScope(pageUrl, pageTitle, listUrl)
            isStream -> chooseSingleFormat(pageUrl, pageTitle)
            listUrl != null -> choosePlaylistFormat(listUrl)
            else -> showVideoPicker()
        }
    }

    /**
     * Pick MP4/MP3 first. This mirrors the Download button wording and makes
     * scope an explicit second decision instead of silently entering playlist
     * mode for mobile YouTube mix URLs.
     */
    private fun chooseFormatAndScope(pageUrl: String, pageTitle: String, listUrl: String) {
        val actions = listOf<Pair<String, () -> Unit>>(
            getString(R.string.yt_video_mp4) to {
                chooseScope(pageUrl, pageTitle, listUrl, DownloadService.YtFormat.MP4)
            },
            getString(R.string.yt_audio_mp3) to {
                chooseScope(pageUrl, pageTitle, listUrl, DownloadService.YtFormat.MP3)
            }
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.yt_download_title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** One video, or selected entries from the list it belongs to. */
    private fun chooseScope(
        pageUrl: String,
        pageTitle: String,
        listUrl: String,
        format: DownloadService.YtFormat
    ) {
        val single: () -> Unit = when (format) {
            DownloadService.YtFormat.MP4 -> {
                { chooseMp4Quality(pageUrl, pageTitle) }
            }
            DownloadService.YtFormat.MP3 -> {
                { chooseMp3Bitrate(pageUrl, pageTitle) }
            }
        }
        val actions = listOf<Pair<String, () -> Unit>>(
            getString(R.string.yt_scope_single) to single,
            getString(R.string.yt_scope_playlist) to { downloadPlaylist(listUrl, format) }
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.yt_scope_title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseSingleFormat(pageUrl: String, pageTitle: String) {
        val actions = listOf<Pair<String, () -> Unit>>(
            getString(R.string.yt_video_mp4) to { chooseMp4Quality(pageUrl, pageTitle) },
            getString(R.string.yt_audio_mp3) to { chooseMp3Bitrate(pageUrl, pageTitle) }
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.yt_download_title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun choosePlaylistFormat(listUrl: String) {
        val actions = listOf<Pair<String, () -> Unit>>(
            getString(R.string.yt_playlist_mp4) to {
                downloadPlaylist(listUrl, DownloadService.YtFormat.MP4)
            },
            getString(R.string.yt_playlist_mp3) to {
                downloadPlaylist(listUrl, DownloadService.YtFormat.MP3)
            }
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.yt_playlist_format_title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseMp4Quality(pageUrl: String, pageTitle: String) {
        Toast.makeText(requireContext(), R.string.yt_reading_qualities, Toast.LENGTH_SHORT).show()
        val userAgent = currentWebView.settings.userAgentString
        viewLifecycleOwner.lifecycleScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                YouTubeResolver.ensureInitialised(userAgent)
                runCatching { YouTubeResolver.resolveVideo(pageUrl) }.getOrNull()
            }
            if (!isAdded) return@launch
            val options = resolved?.videoOptions.orEmpty()
            if (options.isEmpty()) {
                toast(R.string.yt_no_streams)
                return@launch
            }
            val title = resolved!!.title.ifBlank { pageTitle }
            // Renditions above 360p arrive without audio and are remuxed after
            // download; the label says so rather than surprising the user.
            val labels = options.map { option ->
                if (option.needsMux) "${option.label}  ·  ${getString(R.string.yt_merged)}"
                else option.label
            }.toTypedArray()

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.yt_select_quality)
                .setItems(labels) { _, which ->
                    startYouTubeDownload(
                        pageUrl, title, DownloadService.YtFormat.MP4, options[which].height
                    )
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun chooseMp3Bitrate(pageUrl: String, pageTitle: String) {
        val rates = intArrayOf(128, 192, 320)
        val selected = rates.indexOf(prefs.mp3Bitrate).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.yt_select_bitrate)
            .setSingleChoiceItems(
                rates.map { getString(R.string.yt_bitrate_item, it) }.toTypedArray(), selected
            ) { dialog, which ->
                prefs.mp3Bitrate = rates[which]
                dialog.dismiss()
                startYouTubeDownload(pageUrl, pageTitle, DownloadService.YtFormat.MP3, 0)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Reads the playlist listing, lets the user pick which entries to keep,
     * then queues one job per selection. Each job only carries the watch URL —
     * stream URLs are resolved by the service as it reaches the item, since
     * they expire long before a long queue drains.
     *
     * The jobs share a batch id so the Library and the notification can call
     * the rest of the playlist off in one go.
     */
    private fun downloadPlaylist(playlistUrl: String, format: DownloadService.YtFormat) {
        val userAgent = currentWebView.settings.userAgentString
        val bitrate = prefs.mp3Bitrate

        // Reading a long playlist means walking its continuation pages, so the
        // wait is worth both a running count and a way out.
        var reader: Job? = null
        val progress = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.yt_reading_playlist)
            .setMessage(getString(R.string.yt_playlist_reading_count, 0))
            .setNegativeButton(android.R.string.cancel) { _, _ -> reader?.cancel() }
            .setOnCancelListener { reader?.cancel() }
            .show()

        reader = viewLifecycleOwner.lifecycleScope.launch {
            val playlist = withContext(Dispatchers.IO) {
                YouTubeResolver.ensureInitialised(userAgent)
                val ctx = currentCoroutineContext()
                runCatching {
                    YouTubeResolver.resolvePlaylist(playlistUrl) { loaded ->
                        // Also the only place the read can notice a cancel:
                        // the extractor call itself blocks.
                        ctx.ensureActive()
                        launch(Dispatchers.Main) {
                            if (progress.isShowing) {
                                progress.setMessage(
                                    getString(R.string.yt_playlist_reading_count, loaded)
                                )
                            }
                        }
                    }
                }.getOrNull()
            }
            progress.dismiss()
            if (!isAdded) return@launch
            val items = playlist?.items.orEmpty()
            if (items.isEmpty()) {
                toast(R.string.yt_playlist_empty)
                return@launch
            }

            PlaylistPicker.show(requireContext(), playlist!!.title, items) { selected ->
                queuePlaylist(selected, playlist.title, format, bitrate, userAgent)
            }
        }
    }

    private fun queuePlaylist(
        selected: List<YouTubeResolver.PlaylistItem>,
        playlistTitle: String,
        format: DownloadService.YtFormat,
        bitrate: Int,
        userAgent: String
    ) {
        val batchId = DownloadService.newBatchId()
        // Registered before the items are handed over so the Library can show —
        // and cancel — the whole queue, not just what is transferring.
        DownloadQueueBus.start(batchId, playlistTitle, selected.size)
        DownloadService.startYouTubePlaylist(
            context = requireContext(),
            items = selected.map { it.url to it.title },
            format = format,
            mp3Bitrate = bitrate,
            userAgent = userAgent,
            batchId = batchId
        )
        Toast.makeText(
            requireContext(),
            getString(R.string.yt_playlist_queued, selected.size),
            Toast.LENGTH_SHORT
        ).show()
        (activity as? com.nsl.downloader.MainActivity)?.showLibrary()
    }

    private fun startYouTubeDownload(
        pageUrl: String,
        title: String,
        format: DownloadService.YtFormat,
        height: Int
    ) {
        DownloadService.startYouTube(
            context = requireContext(),
            pageUrl = pageUrl,
            title = title,
            format = format,
            targetHeight = height,
            mp3Bitrate = prefs.mp3Bitrate,
            userAgent = currentWebView.settings.userAgentString
        )
        Toast.makeText(requireContext(), R.string.download_started, Toast.LENGTH_SHORT).show()
        (activity as? com.nsl.downloader.MainActivity)?.showLibrary()
    }

    // ------------------------------------------------------------- VK UI

    /**
     * Entry point for a VK video page.
     *
     * The stream URLs are read out of the live page rather than resolved over
     * the network: VK signs them against the visitor's session, so a URL
     * fetched from anywhere but this WebView would be refused.
     */
    private fun showVkDialog(pageUrl: String) {
        Toast.makeText(requireContext(), R.string.vk_reading, Toast.LENGTH_SHORT).show()
        currentWebView.evaluateJavascript(VkScripts.COLLECT) { result ->
            if (!isAdded) return@evaluateJavascript
            val fallbackTitle = currentWebView.title ?: guessTitleFromUrl(pageUrl)
            val resolved = VkResolver.parse(result.orEmpty(), fallbackTitle)

            if (resolved.sources.isEmpty()) {
                // Nothing in the page yet — usually because the player has not
                // been opened. Anything the sniffer caught is still worth a try.
                if (currentTab.sniffer.count() > 0) {
                    showVideoPicker()
                } else {
                    Toast.makeText(requireContext(), R.string.vk_no_streams, Toast.LENGTH_LONG)
                        .show()
                }
                return@evaluateJavascript
            }

            if (resolved.sources.size == 1) {
                startVkDownload(resolved.sources.first(), resolved.title, labelled = false)
                return@evaluateJavascript
            }

            val labels = resolved.sources.map { it.label }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.vk_download_title)
                .setItems(labels) { _, which ->
                    startVkDownload(resolved.sources[which], resolved.title, labelled = true)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun startVkDownload(
        source: VkResolver.Source,
        title: String,
        labelled: Boolean
    ) {
        val name = if (labelled) "$title (${source.label})" else title
        startDownload(source.url, name, buildHeaders(source.url, emptyMap()))
    }

    // ------------------------------------------------------ sniffed videos

    private var movieResolveJob: Job? = null

    private fun showMovieDialog(pageUrl: String) {
        if (movieResolveJob?.isActive == true) return
        val tab = currentTab
        val title = currentWebView.title ?: "Movie"
        val agent = currentWebView.settings.userAgentString
        Toast.makeText(requireContext(), "Finding movie streams…", Toast.LENGTH_SHORT).show()
        movieResolveJob = viewLifecycleOwner.lifecycleScope.launch {
            val sources = withContext(Dispatchers.IO) {
                runCatching { MovieResolver(probeClient).resolve(pageUrl, agent) }.getOrDefault(emptyList())
            }
            if (!isAdded || currentTab !== tab || currentWebView.url != pageUrl) return@launch
            if (sources.isEmpty()) {
                Toast.makeText(requireContext(), "Movie servers are unavailable. Try again later or choose another player on the page.", Toast.LENGTH_LONG).show()
                return@launch
            }
            fun download(source: MovieResolver.Source) {
                startDownload(source.url, "$title (${source.label})", HashMap(source.headers))
            }
            if (sources.size == 1) download(sources.first())
            else MaterialAlertDialogBuilder(requireContext())
                .setTitle("Select movie audio")
                .setItems(sources.map { it.label }.toTypedArray()) { _, index -> download(sources[index]) }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    /**
     * One detected stream needs no list — it goes straight to the quality step.
     * Several get checkboxes, because a page that exposes more than one is
     * usually a page where the user wants more than one.
     */
    private fun showVideoPicker() {
        val videos = currentTab.sniffer.snapshot()
        when {
            videos.isEmpty() ->
                Toast.makeText(requireContext(), R.string.no_videos_detected, Toast.LENGTH_SHORT)
                    .show()
            videos.size == 1 -> onVideoChosen(videos.first())
            else -> showMultiVideoPicker(videos)
        }
    }

    private fun showMultiVideoPicker(videos: List<VideoSniffer.Candidate>) {
        val labels = videos.map { candidate ->
            val type = when {
                candidate.url.contains(".m3u8") -> "[HLS] "
                candidate.url.contains(".mpd") -> "[DASH] "
                else -> "[Direct] "
            }
            type + candidate.url.substringBefore('?').substringAfterLast('/').take(40)
        }.toTypedArray()
        val checked = BooleanArray(videos.size)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.detected_videos, videos.size))
            .setMultiChoiceItems(labels, checked) { _, _, _ -> }
            .setPositiveButton(R.string.download, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        // Bound by hand so the count can ride on the button and an empty
        // selection cannot dismiss the dialog.
        dialog.setOnShowListener {
            val button = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            fun sync() {
                val count = checked.count { it }
                button.isEnabled = count > 0
                button.text = getString(R.string.yt_playlist_download_n, count)
            }
            sync()
            dialog.listView.setOnItemClickListener { _, _, position, _ ->
                checked[position] = dialog.listView.isItemChecked(position)
                sync()
            }
            button.setOnClickListener {
                val picked = videos.filterIndexed { index, _ -> checked[index] }
                if (picked.isEmpty()) return@setOnClickListener
                dialog.dismiss()
                // A single pick still gets the quality step; a batch would mean
                // one dialog per item, so those take the best variant as-is.
                if (picked.size == 1) onVideoChosen(picked.first()) else downloadAll(picked)
            }
        }
        dialog.show()
    }

    private fun downloadAll(candidates: List<VideoSniffer.Candidate>) {
        val pageTitle = currentWebView.title ?: guessTitleFromUrl(currentWebView.url.orEmpty())
        candidates.forEachIndexed { index, candidate ->
            DownloadService.start(
                requireContext(),
                candidate.url,
                if (candidates.size == 1) pageTitle else "$pageTitle (${index + 1})",
                buildHeaders(candidate.url, candidate.headers)
            )
        }
        Toast.makeText(
            requireContext(),
            getString(R.string.yt_playlist_queued, candidates.size),
            Toast.LENGTH_SHORT
        ).show()
        (activity as? com.nsl.downloader.MainActivity)?.showLibrary()
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
        // Retain the master when it owns an external audio track. Passing only
        // its video variant loses the audio group and produces a silent movie.
        if (real.size <= 1 || variants.any { it.audioUrl != null }) {
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
        Toast.makeText(requireContext(), R.string.download_started, Toast.LENGTH_SHORT).show()
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
        const val MENU_PLAYBACK_MODE = 2
        const val MENU_NEW_TAB = 3
        const val MENU_TOGGLE_FOOTER = 4
        const val MENU_SETTINGS = 5

        /** Grace period before the playback service is torn down. */
        const val PLAYBACK_STOP_DELAY_MS = 30_000L

        /** Android refuses a PiP aspect ratio outside this range. */
        const val MAX_PIP_RATIO = 2.39f
        const val MIN_PIP_RATIO = 1f / 2.39f

        /** How long `onStop` waits for a late PiP-mode callback. */
        const val PIP_CALLBACK_GRACE_MS = 400L

        const val PIP_TAG = "NSLPip"
    }
}
