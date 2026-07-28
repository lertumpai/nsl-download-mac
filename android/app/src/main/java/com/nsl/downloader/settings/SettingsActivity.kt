package com.nsl.downloader.settings

import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nsl.downloader.R
import com.nsl.downloader.databinding.ActivitySettingsBinding
import com.nsl.downloader.util.MediaStorage
import com.nsl.downloader.util.PlaybackMode
import com.nsl.downloader.util.Prefs
import com.nsl.downloader.util.applyDownloadSettings
import java.util.Locale

/**
 * The download settings the service reads on every job: how many transfers may
 * run at once, how much bandwidth they may take, and where finished files land.
 *
 * Each row opens a chooser and writes straight through to [Prefs] — there is no
 * save button, so a setting changed mid-download applies to what comes next.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { Prefs(this) }

    /** Offered caps in bytes/sec; 0 is "as fast as the link allows". */
    private val speedOptions = listOf(
        0L, 256L shl 10, 512L shl 10, 1L shl 20, 2L shl 20, 5L shl 20, 10L shl 20
    )

    private val concurrentOptions = listOf(1, 2, 3, 4, 5, 6)
    private val bitrateOptions = listOf(128, 192, 320)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClose.setOnClickListener { finish() }
        binding.rowConcurrent.setOnClickListener { chooseConcurrent() }
        binding.rowSpeed.setOnClickListener { chooseSpeedLimit() }
        binding.rowFolder.setOnClickListener { chooseFolder() }
        binding.rowBitrate.setOnClickListener { chooseBitrate() }
        binding.rowPlayback.setOnClickListener { choosePlaybackMode() }

        render()
    }

    private fun render() {
        binding.valueConcurrent.text = resources.getQuantityString(
            R.plurals.settings_concurrent_value,
            prefs.maxConcurrentDownloads,
            prefs.maxConcurrentDownloads
        )
        binding.valueSpeed.text = speedLabel(prefs.speedLimitBytesPerSecond)
        binding.valueFolder.text =
            getString(R.string.settings_folder_value, prefs.downloadFolderName)
        binding.valueBitrate.text = getString(R.string.yt_bitrate_item, prefs.mp3Bitrate)
        binding.valuePlayback.text = getString(
            when (prefs.playbackMode) {
                PlaybackMode.OFF -> R.string.playback_mode_off
                PlaybackMode.BACKGROUND -> R.string.playback_mode_background
                PlaybackMode.PICTURE_IN_PICTURE -> R.string.playback_mode_pip
            }
        )
    }

    private fun chooseConcurrent() {
        val labels = concurrentOptions.map {
            resources.getQuantityString(R.plurals.settings_concurrent_value, it, it)
        }
        pick(R.string.settings_concurrent, labels, concurrentOptions.indexOf(prefs.maxConcurrentDownloads)) {
            prefs.maxConcurrentDownloads = concurrentOptions[it]
        }
    }

    private fun chooseSpeedLimit() {
        val labels = speedOptions.map { speedLabel(it) }
        pick(R.string.settings_speed_limit, labels, speedOptions.indexOf(prefs.speedLimitBytesPerSecond)) {
            prefs.speedLimitBytesPerSecond = speedOptions[it]
            prefs.applyDownloadSettings()
        }
    }

    private fun chooseBitrate() {
        val labels = bitrateOptions.map { getString(R.string.yt_bitrate_item, it) }
        pick(R.string.settings_bitrate, labels, bitrateOptions.indexOf(prefs.mp3Bitrate)) {
            prefs.mp3Bitrate = bitrateOptions[it]
        }
    }

    private fun choosePlaybackMode() {
        val modes = listOf(
            PlaybackMode.OFF to getString(R.string.playback_mode_off),
            PlaybackMode.BACKGROUND to getString(R.string.playback_mode_background),
            PlaybackMode.PICTURE_IN_PICTURE to getString(R.string.playback_mode_pip)
        )
        pick(
            R.string.menu_playback_mode,
            modes.map { it.second },
            modes.indexOfFirst { it.first == prefs.playbackMode }
        ) {
            prefs.playbackMode = modes[it].first
        }
    }

    /**
     * Only the folder name is editable, not a free path: files are published
     * through MediaStore into `Download/`, which is the one place every Android
     * version lets the app write and the user browse.
     */
    private fun chooseFolder() {
        val input = EditText(this).apply {
            setText(prefs.downloadFolderName)
            setSingleLine()
            setSelection(text.length)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad * 2, pad, pad * 2, pad)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_folder)
            .setMessage(R.string.settings_folder_hint)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.downloadFolderName = input.text.toString()
                prefs.applyDownloadSettings()
                MediaStorage.ensureFolder(null)
                render()
            }
            .setNeutralButton(R.string.settings_folder_reset) { _, _ ->
                prefs.downloadFolderName = MediaStorage.DEFAULT_ROOT
                prefs.applyDownloadSettings()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pick(titleRes: Int, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setSingleChoiceItems(labels.toTypedArray(), selected.coerceAtLeast(0)) { dialog, which ->
                onPick(which)
                dialog.dismiss()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun speedLabel(bytesPerSecond: Long): String = when {
        bytesPerSecond <= 0 -> getString(R.string.settings_speed_unlimited)
        bytesPerSecond >= (1L shl 20) ->
            String.format(Locale.US, "%.0f MB/s", bytesPerSecond / 1024.0 / 1024.0)
        else -> String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0)
    }
}
