package com.nsl.downloader.browser

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nsl.downloader.databinding.FragmentBrowserBinding
import com.nsl.downloader.service.DownloadService
import com.nsl.downloader.util.guessTitleFromUrl

class BrowserFragment : Fragment() {

    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!

    private val sniffer = VideoSniffer()

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
                        sniffer.consider(url, mime)
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
                val chosen = videos[which]
                val pageTitle = binding.webView.title ?: guessTitleFromUrl(chosen.url)
                DownloadService.start(requireContext(), chosen.url, pageTitle)
                Toast.makeText(requireContext(), "Download started", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
