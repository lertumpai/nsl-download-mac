package com.nsl.downloader.util

import android.content.Context

/** Small key/value store for the browser's user-facing toggles. */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("nsl_prefs", Context.MODE_PRIVATE)

    var adBlockEnabled: Boolean
        get() = sp.getBoolean(KEY_AD_BLOCK, true)
        set(value) = sp.edit().putBoolean(KEY_AD_BLOCK, value).apply()

    var backgroundPlaybackEnabled: Boolean
        get() = sp.getBoolean(KEY_BACKGROUND_PLAYBACK, true)
        set(value) = sp.edit().putBoolean(KEY_BACKGROUND_PLAYBACK, value).apply()

    private companion object {
        const val KEY_AD_BLOCK = "ad_block"
        const val KEY_BACKGROUND_PLAYBACK = "background_playback"
    }
}
