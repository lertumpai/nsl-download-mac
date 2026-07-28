package com.nsl.downloader.browser

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView

/**
 * WebView that keeps decoding media while the app is backgrounded or the
 * screen is off.
 *
 * Chromium pauses <video>/<audio> as soon as the hosting window stops being
 * visible. Swallowing that signal (always reporting VISIBLE upstream) keeps the
 * media pipeline running; the page's own visibility handlers are neutralised
 * separately by [BrowserScripts.BACKGROUND_PLAYBACK].
 *
 * Disable via [backgroundPlaybackEnabled] before destroying the view so the
 * real teardown visibility still reaches Chromium.
 */
class BackgroundWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    var backgroundPlaybackEnabled: Boolean = true

    /**
     * PiP first needs Android's real visibility event so Chromium can move its
     * render surface, then it needs a visible signal again because the hosting
     * activity remains paused while the floating window is on screen.
     */
    fun keepPlayingInPictureInPicture() {
        backgroundPlaybackEnabled = true
        super.onWindowVisibilityChanged(VISIBLE)
        onResume()
        resumeTimers()
        invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (backgroundPlaybackEnabled && visibility != VISIBLE) {
            super.onWindowVisibilityChanged(VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }

    override fun destroy() {
        backgroundPlaybackEnabled = false
        super.destroy()
    }
}
