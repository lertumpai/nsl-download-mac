package com.nsl.downloader.player

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import com.nsl.downloader.R
import com.nsl.downloader.data.AppDatabase
import com.nsl.downloader.databinding.ActivityPlayerBinding
import com.nsl.downloader.util.MediaStorage
import com.nsl.downloader.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "video_id"
        const val EXTRA_PATH = "video_path"
        const val EXTRA_TITLE = "video_title"
        const val SEEK_MS = 10_000L
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var gestureDetector: GestureDetector

    private var videoId: Long = -1L
    private var videoPath: String? = null
    private var thumbnailJob: Job? = null
    private var pictureInPictureEntryPending = false
    private val prefs by lazy { Prefs(this) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        videoId = intent.getLongExtra(EXTRA_ID, -1L)
        videoPath = intent.getStringExtra(EXTRA_PATH)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Video"

        if (videoPath == null) {
            Toast.makeText(this, "No video path", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupPlayer(videoPath!!)
        setupGestures()
        setupButtons(title)
        setupThumbnailPreview()
    }

    private fun setupPlayer(path: String) {
        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.playerView.player = exo
            val item = MediaItem.fromUri(MediaStorage.toUri(path))
            exo.setMediaItem(item)
            exo.prepare()
            exo.playWhenReady = true
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        binding.playerView.showController()
                        savePosition(0L)
                    }
                }
            })
        }

        // Restore last playback position
        if (videoId != -1L) {
            lifecycleScope.launch {
                val lastPos = withContext(Dispatchers.IO) {
                    AppDatabase.getInstance(this@PlayerActivity).videoDao().getById(videoId)?.lastPositionMs ?: 0L
                }
                if (lastPos > 0L) player?.seekTo(lastPos)
            }
        }
    }

    private fun setupThumbnailPreview() {
        val timeBar = binding.playerView.findViewById<DefaultTimeBar>(R.id.exo_progress) ?: return
        val card = binding.playerView.findViewById<View>(R.id.seekThumbnailCard) ?: return
        val image = binding.playerView.findViewById<android.widget.ImageView>(R.id.seekThumbnail) ?: return

        timeBar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) = showThumbnail(position, card, image)
            override fun onScrubMove(timeBar: TimeBar, position: Long) = showThumbnail(position, card, image)
            override fun onScrubStop(timeBar: TimeBar, position: Long, cancelled: Boolean) {
                thumbnailJob?.cancel()
                card.visibility = View.GONE
            }
        })
    }

    private fun showThumbnail(positionMs: Long, card: View, image: android.widget.ImageView) {
        thumbnailJob?.cancel()
        thumbnailJob = lifecycleScope.launch {
            val path = videoPath ?: return@launch
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    MediaStorage.applyDataSource(retriever, this@PlayerActivity, path)
                    val b = retriever.getFrameAtTime(positionMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    retriever.release()
                    b
                }.getOrNull()
            }
            if (isActive && bmp != null) {
                image.setImageBitmap(bmp)
                card.visibility = View.VISIBLE
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent,
                velocityX: Float, velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > abs(dy) && abs(dx) > 100) {
                    if (dx > 0) seekBy(SEEK_MS) else seekBy(-SEEK_MS)
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (e.x > binding.playerView.width / 2) seekBy(SEEK_MS)
                else seekBy(-SEEK_MS)
                return true
            }
        })

        binding.playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }
    }

    private fun setupButtons(title: String) {
        binding.playerView.findViewById<android.widget.TextView>(R.id.titleText)?.text = title
        binding.playerView.findViewById<View>(R.id.btnMinus10)?.setOnClickListener { seekTo(-SEEK_MS) }
        binding.playerView.findViewById<View>(R.id.btnPlus10)?.setOnClickListener { seekTo(SEEK_MS) }
        binding.playerView.findViewById<View>(R.id.btnClose)?.setOnClickListener { finish() }
    }

    private fun seekTo(deltaMs: Long) {
        val p = player ?: return
        val target = (p.currentPosition + deltaMs).coerceIn(0, p.duration.coerceAtLeast(0))
        p.seekTo(target)
    }

    private fun seekBy(deltaMs: Long) {
        seekTo(deltaMs)
        showSeekFeedback(deltaMs)
    }

    private fun showSeekFeedback(deltaMs: Long) {
        val text = if (deltaMs > 0) "+10s ⏩" else "⏪ -10s"
        binding.seekFeedback.text = text
        binding.seekFeedback.visibility = View.VISIBLE
        binding.seekFeedback.removeCallbacks(hideFeedback)
        binding.seekFeedback.postDelayed(hideFeedback, 600)
    }

    private val hideFeedback = Runnable {
        binding.seekFeedback.visibility = View.GONE
    }

    private fun savePosition(positionMs: Long) {
        if (videoId == -1L) return
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(this@PlayerActivity).videoDao()
            val video = dao.getById(videoId) ?: return@launch
            dao.update(video.copy(lastPositionMs = positionMs))
        }
    }

    // ------------------------------------------------- picture-in-picture

    /**
     * Fires when the user swipes to another app or goes home. With PiP chosen
     * as the playback mode the video follows them into a floating window; the
     * mutually exclusive background-audio mode deliberately does nothing here.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!prefs.pictureInPictureEnabled) return
        if (isInPictureInPictureMode) return
        if (player?.isPlaying != true) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return

        pictureInPictureEntryPending = true
        val entered = runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(currentAspectRatio())
                    .build()
            )
        }.getOrDefault(false)
        if (!entered) pictureInPictureEntryPending = false
    }

    /** PiP rejects extreme ratios, so the video's own ratio is clamped. */
    private fun currentAspectRatio(): Rational {
        val size = player?.videoSize
        val width = size?.width ?: 0
        val height = size?.height ?: 0
        if (width <= 0 || height <= 0) return Rational(16, 9)
        val ratio = width.toFloat() / height
        return when {
            ratio > 2.39f -> Rational(239, 100)
            ratio < 0.42f -> Rational(42, 100)
            else -> Rational(width, height)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pictureInPictureEntryPending = false
        // The PiP window is far too small for the transport controls.
        binding.playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) binding.playerView.hideController()
    }

    override fun onPause() {
        super.onPause()
        val pos = player?.currentPosition ?: 0L
        savePosition(pos)
        // In PiP the activity is paused but the window is still on screen, so
        // pausing here would defeat the whole point of the mode.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            (pictureInPictureEntryPending || isInPictureInPictureMode)
        ) return
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        // Some Android builds stop the activity during the PiP transition even
        // though its floating window remains visible. Only pause after the PiP
        // window has actually gone away.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            (pictureInPictureEntryPending || isInPictureInPictureMode)
        ) return
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        thumbnailJob?.cancel()
        player?.release()
        player = null
    }
}
