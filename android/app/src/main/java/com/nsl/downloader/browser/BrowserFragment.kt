package com.nsl.downloader.browser

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nsl.downloader.databinding.FragmentBrowserBinding
import com.nsl.downloader.service.DownloadService
import com.nsl.downloader.service.HlsDownloader
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

    private val sniffer = VideoSniffer()
    private val probeClient by lazy { OkHttpClient() }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupWebView()
        setupControls()
        updateFab()

        sniffer.onNewVideo = {
            requireActivity().runOnUiThread { updateFab() }
        }

        binding.webView.loadUrl("https://www.google.com")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(binding.webView) {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                userAgentString = settings.userAgentString.replace("; wv", "")
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?, request: WebResourceRequest?
                ): WebResourceResponse? {
                    request?.url?.toString()?.let { url ->
                        val mime = request.requestHeaders["Accept"]
                        sniffer.consider(url, mime, request.requestHeaders ?: emptyMap())
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    sniffer.clear()
                    binding.urlBar.setText(url)
                    updateFab()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    binding.progressBar.visibility = View.GONE
                    updateFab()
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    binding.progressBar.progress = newProgress
                    binding.progressBar.visibility =
                        if (newProgress < 100) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun setupControls() {
        binding.urlBar.setOnEditorActionListener { _, _, _ ->
            navigateTo(binding.urlBar.text.toString())
            true
        }
        binding.btnGo.setOnClickListener { navigateTo(binding.urlBar.text.toString()) }
        binding.btnBack.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack()
        }
        binding.btnForward.setOnClickListener {
            if (binding.webView.canGoForward()) binding.webView.goForward()
        }
        binding.btnReload.setOnClickListener { binding.webView.reload() }
        binding.fabDownload.setOnClickListener { showVideoPicker() }
    }

    private fun navigateTo(input: String) {
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.google.com/search?q=${android.net.Uri.encode(input)}"
        }
        binding.webView.loadUrl(url)
    }

    private fun updateFab() {
        val count = sniffer.count()
        if (count > 0) {
            binding.fabDownload.show()
            binding.fabDownload.text = "Download ($count)"
        } else {
            binding.fabDownload.hide()
        }
    }

    private fun showVideoPicker() {
        val videos = sniffer.snapshot()
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
        val pageTitle = binding.webView.title ?: guessTitleFromUrl(candidate.url)
        val headers = buildHeaders(candidate.url, candidate.headers)

        // Only HLS exposes resolution variants; direct/DASH download straight away.
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
        // Single/unknown quality → no choice to make.
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
        // Jump to the Library so the user sees the download progressing.
        (activity as? com.nsl.downloader.MainActivity)?.showLibrary()
    }

    /**
     * Builds the request headers a CDN expects so protected streams (e.g. missav)
     * don't return 403: the WebView's User-Agent, the page URL as Referer/Origin,
     * the request headers the WebView itself used, and the current cookies.
     */
    private fun buildHeaders(videoUrl: String, captured: Map<String, String>): HashMap<String, String> {
        val h = HashMap<String, String>()
        // Headers the WebView sent for this resource (often includes Referer/Origin).
        captured.forEach { (k, v) -> if (v.isNotBlank()) h[k] = v }
        // Always use the real browser UA.
        h["User-Agent"] = binding.webView.settings.userAgentString
        // Referer/Origin from the current page if not already captured.
        binding.webView.url?.let { page ->
            h.getOrPut("Referer") { page }
            runCatching {
                val u = android.net.Uri.parse(page)
                h.getOrPut("Origin") { "${u.scheme}://${u.host}" }
            }
        }
        // Session cookies for the video host.
        runCatching {
            CookieManager.getInstance().getCookie(videoUrl)?.takeIf { it.isNotBlank() }?.let {
                h["Cookie"] = it
            }
        }
        return h
    }

    fun onBackPressed(): Boolean {
        return if (binding.webView.canGoBack()) {
            binding.webView.goBack()
            true
        } else false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.webView.destroy()
        _binding = null
    }
}
