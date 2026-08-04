package com.nsl.downloader.util

import android.content.Context

/**
 * How media should keep playing once the user leaves the app.
 *
 * These are deliberately mutually exclusive: keeping the WebView "visible" for
 * background audio and handing the activity to the system for Picture-in-Picture
 * fight over the same media pipeline, so exactly one may be active.
 */
enum class PlaybackMode {
    /** Media pauses when the app goes away. */
    OFF,

    /** Audio keeps playing with a foreground-service notification. */
    BACKGROUND,

    /** The video follows the user into other apps in a floating window. */
    PICTURE_IN_PICTURE;

    companion object {
        fun from(name: String?): PlaybackMode =
            values().firstOrNull { it.name == name } ?: BACKGROUND
    }
}

/** Small key/value store for the browser's user-facing toggles. */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("nsl_prefs", Context.MODE_PRIVATE)

    var adBlockEnabled: Boolean
        get() = sp.getBoolean(KEY_AD_BLOCK, true)
        set(value) = sp.edit().putBoolean(KEY_AD_BLOCK, value).apply()

    var playbackMode: PlaybackMode
        get() {
            sp.getString(KEY_PLAYBACK_MODE, null)?.let { return PlaybackMode.from(it) }
            // Migrate the old boolean toggle so existing installs keep their choice.
            return if (sp.getBoolean(KEY_BACKGROUND_PLAYBACK, true)) PlaybackMode.BACKGROUND
            else PlaybackMode.OFF
        }
        set(value) = sp.edit().putString(KEY_PLAYBACK_MODE, value.name).apply()

    val backgroundPlaybackEnabled: Boolean
        get() = playbackMode == PlaybackMode.BACKGROUND

    val pictureInPictureEnabled: Boolean
        get() = playbackMode == PlaybackMode.PICTURE_IN_PICTURE

    /** Footer navigation + download button collapsed to give the page more room. */
    var footerCollapsed: Boolean
        get() = sp.getBoolean(KEY_FOOTER_COLLAPSED, false)
        set(value) = sp.edit().putBoolean(KEY_FOOTER_COLLAPSED, value).apply()

    /** Last chosen MP3 bitrate, reused as the default next time. */
    var mp3Bitrate: Int
        get() = sp.getInt(KEY_MP3_BITRATE, 192)
        set(value) = sp.edit().putInt(KEY_MP3_BITRATE, value).apply()

    /** How many downloads may transfer at once; the rest queue. */
    var maxConcurrentDownloads: Int
        get() = sp.getInt(KEY_MAX_CONCURRENT, DEFAULT_MAX_CONCURRENT).coerceIn(1, 6)
        set(value) = sp.edit().putInt(KEY_MAX_CONCURRENT, value.coerceIn(1, 6)).apply()

    /** Whole-app download cap in bytes/sec; 0 is unlimited. */
    var speedLimitBytesPerSecond: Long
        get() = sp.getLong(KEY_SPEED_LIMIT, 0L).coerceAtLeast(0L)
        set(value) = sp.edit().putLong(KEY_SPEED_LIMIT, value.coerceAtLeast(0L)).apply()

    /** Folder under `Download/` that finished files are published into. */
    var downloadFolderName: String
        get() = sp.getString(KEY_DOWNLOAD_FOLDER, null)?.takeIf { it.isNotBlank() }
            ?: MediaStorage.DEFAULT_ROOT
        set(value) {
            val clean = MediaStorage.sanitizeName(value).ifBlank { MediaStorage.DEFAULT_ROOT }
            sp.edit().putString(KEY_DOWNLOAD_FOLDER, clean).apply()
            MediaStorage.root = clean
        }

    /**
     * The download root whose existing files were already marked hidden from
     * Gallery apps. Anything else (a fresh install, or a renamed root) means
     * [MediaStorage.hideExistingDownloads] still has work to do.
     */
    var galleryHiddenRoot: String?
        get() = sp.getString(KEY_GALLERY_HIDDEN_ROOT, null)
        set(value) = sp.edit().putString(KEY_GALLERY_HIDDEN_ROOT, value).apply()

    private companion object {
        const val KEY_AD_BLOCK = "ad_block"
        const val KEY_BACKGROUND_PLAYBACK = "background_playback"
        const val KEY_PLAYBACK_MODE = "playback_mode"
        const val KEY_FOOTER_COLLAPSED = "footer_collapsed"
        const val KEY_MP3_BITRATE = "mp3_bitrate"
        const val KEY_MAX_CONCURRENT = "max_concurrent"
        const val KEY_SPEED_LIMIT = "speed_limit"
        const val KEY_DOWNLOAD_FOLDER = "download_folder"
        const val KEY_GALLERY_HIDDEN_ROOT = "gallery_hidden_root"
        const val DEFAULT_MAX_CONCURRENT = 3
    }
}

/**
 * Pushes the stored download settings into the objects that enforce them.
 * Both entry points into the app (the activity and the download service) call
 * this, since either can be the first thing the system starts.
 */
fun Prefs.applyDownloadSettings() {
    MediaStorage.root = downloadFolderName
    RateLimiter.bytesPerSecond = speedLimitBytesPerSecond
}

/**
 * Runs once per download root: marks the download folder as non-media and
 * rescans what is already in it, so downloads made before this app version
 * disappear from Gallery apps too. Blocking I/O — never call on the main thread.
 */
fun Prefs.hideDownloadsFromGallery(context: Context) {
    val root = downloadFolderName
    if (galleryHiddenRoot == root) return
    MediaStorage.hideExistingDownloads(context)
    galleryHiddenRoot = root
}
