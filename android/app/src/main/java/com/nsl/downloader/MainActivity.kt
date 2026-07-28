package com.nsl.downloader

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.nsl.downloader.browser.BrowserFragment
import com.nsl.downloader.databinding.ActivityMainBinding
import com.nsl.downloader.library.LibraryFragment
import com.nsl.downloader.util.Prefs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /**
     * Resolved in [onCreate], never constructed as field initialisers: after the
     * activity is recreated (a Picture-in-Picture round trip can do it) the
     * fragments that are actually on screen are the ones the FragmentManager
     * restored. Holding freshly built instances instead left the UI wired to
     * fragments that were never attached — the Library stopped updating.
     */
    private lateinit var browserFragment: BrowserFragment
    private lateinit var libraryFragment: LibraryFragment
    private val prefs by lazy { Prefs(this) }

    /** Set while a page is showing a fullscreen <video>. */
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var pictureInPictureEntryPending = false

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best-effort */ }

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotifPermission()
        requestLegacyStoragePermission()

        val restoredBrowser = supportFragmentManager.findFragmentByTag(TAG_BROWSER)
        val restoredLibrary = supportFragmentManager.findFragmentByTag(TAG_LIBRARY)
        browserFragment = restoredBrowser as? BrowserFragment ?: BrowserFragment()
        libraryFragment = restoredLibrary as? LibraryFragment ?: LibraryFragment()
        if (restoredBrowser == null || restoredLibrary == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, libraryFragment, TAG_LIBRARY).hide(libraryFragment)
                .add(R.id.fragmentContainer, browserFragment, TAG_BROWSER)
                .commit()
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_browser -> switchTo(browserFragment)
                R.id.nav_library -> switchTo(libraryFragment)
            }
            true
        }

        binding.btnToggleFooter.setOnClickListener { setFooterCollapsed(!prefs.footerCollapsed) }
        applyFooterCollapsed(prefs.footerCollapsed)
        registerBackHandler()
    }

    /**
     * Deterministically show exactly [target] and hide every other fragment.
     * Avoids tracking a mutable "active" pointer that can desync from the
     * bottom-nav selection.
     */
    private fun switchTo(target: Fragment) {
        val tx = supportFragmentManager.beginTransaction()
        listOf(browserFragment, libraryFragment).forEach {
            if (it === target) tx.show(it) else tx.hide(it)
        }
        tx.commit()
    }

    private fun isBrowserVisible(): Boolean =
        browserFragment.isVisible

    /** Jump to the Library tab (e.g. right after a download starts). */
    fun showLibrary() {
        binding.bottomNav.selectedItemId = R.id.nav_library
    }

    // ------------------------------------------------------- footer collapse

    fun setFooterCollapsed(collapsed: Boolean) {
        prefs.footerCollapsed = collapsed
        applyFooterCollapsed(collapsed)
    }

    fun isFooterCollapsed(): Boolean = prefs.footerCollapsed

    /**
     * Collapsing hides the tab bar and shrinks the browser's download button to
     * its icon. The grab handle itself always stays put, otherwise there would
     * be no way to bring the footer back.
     */
    private fun applyFooterCollapsed(collapsed: Boolean) {
        binding.bottomNav.visibility = if (collapsed) View.GONE else View.VISIBLE
        binding.btnToggleFooter.setImageResource(
            if (collapsed) android.R.drawable.arrow_up_float
            else android.R.drawable.arrow_down_float
        )
        binding.btnToggleFooter.contentDescription =
            getString(if (collapsed) R.string.expand_footer else R.string.collapse_footer)
        browserFragment.onFooterCollapsedChanged(collapsed)
    }

    // ----------------------------------------------------- fullscreen video

    /** Hosts the page's fullscreen view above everything, bars hidden. */
    fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenCallback != null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreenCallback = callback
        binding.fullscreenContainer.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        // `mainContent` stays VISIBLE on purpose — hiding the WebView's ancestor
        // is exactly the signal Chromium uses to pause media. The opaque,
        // clickable overlay covers it instead.
        binding.fullscreenContainer.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setSystemBarsVisible(false)
    }

    fun exitFullscreen() {
        val callback = fullscreenCallback ?: return
        fullscreenCallback = null
        binding.fullscreenContainer.removeAllViews()
        binding.fullscreenContainer.visibility = View.GONE
        binding.mainContent.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setSystemBarsVisible(true)
        runCatching { callback.onCustomViewHidden() }
    }

    fun isFullscreen(): Boolean = fullscreenCallback != null

    /**
     * Only toggles the bars themselves — the decor's inset behaviour is left
     * alone so `mainContent`'s own fitsSystemWindows keeps working on return.
     */
    private fun setSystemBarsVisible(visible: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ------------------------------------------------- picture-in-picture

    /**
     * Fires when the user swipes to another app or goes home. With PiP selected
     * as the playback mode, a playing page follows them into a floating window.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        maybeEnterPictureInPicture()
    }

    private fun maybeEnterPictureInPicture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!prefs.pictureInPictureEnabled) return
        if (isInPictureInPictureMode) return
        if (pictureInPictureEntryPending) return
        if (!isBrowserVisible() || !browserFragment.hasPlayingMedia()) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return

        pictureInPictureEntryPending = true
        // The fragment first pins the playing <video> to its viewport and waits
        // for Chromium to draw it. Entering PiP before that draw is what lets a
        // responsive page resize to an empty white area.
        browserFragment.prepareForPictureInPicture {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                isFinishing || isDestroyed || isInPictureInPictureMode
            ) {
                pictureInPictureEntryPending = false
                return@prepareForPictureInPicture
            }
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .apply {
                    browserFragment.pictureInPictureSourceRect()?.let {
                        setSourceRectHint(it)
                    }
                }
                .build()
            val entered = runCatching { enterPictureInPictureMode(params) }.getOrDefault(false)
            pictureInPictureEntryPending = false
            if (!entered) browserFragment.onPictureInPictureEntryFailed()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pictureInPictureEntryPending = false
        // In a PiP window there is only room for the video itself.
        // The fragments get their own callback dispatched by FragmentActivity.
        binding.footer.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        if (!isInPictureInPictureMode) applyFooterCollapsed(prefs.footerCollapsed)
    }

    // ------------------------------------------------------------ lifecycle

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Below Android 10 there is no MediaStore hand-off, so publishing into
     * `Download/NSL Downloader` is a plain file write and needs the permission.
     */
    private fun requestLegacyStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    /**
     * Apps targeting Android 15 get predictive back by default, and the legacy
     * `onBackPressed()` override is no longer invoked — back has to go through
     * the dispatcher or fullscreen exit and in-page history both stop working.
     */
    private companion object {
        const val TAG_BROWSER = "browser"
        const val TAG_LIBRARY = "library"
    }

    private fun registerBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreen()) {
                    exitFullscreen()
                    return
                }
                if (isBrowserVisible() && browserFragment.onBackPressed()) return

                // Nothing of ours to consume: step aside and let the default
                // (finish the activity) run, then re-arm for next time.
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }
}
