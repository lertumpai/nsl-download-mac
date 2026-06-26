package com.nsl.downloader.player

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nsl.downloader.databinding.ActivityPlayerBinding
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "video_path"
        const val EXTRA_TITLE = "video_title"
        const val SEEK_MS = 10_000L
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var gestureDetector: GestureDetector

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra(EXTRA_PATH)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Video"
        if (path == null) {
            Toast.makeText(this, "No video path", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.titleText.text = title
        setupPlayer(path)
        setupGestures()
        setupButtons()
    }

    private fun setupPlayer(path: String) {
        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.playerView.player = exo
            val item = MediaItem.fromUri(android.net.Uri.parse(path))
            exo.setMediaItem(item)
            exo.prepare()
            exo.playWhenReady = true
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        binding.playerView.showController()
                    }
                }
            })
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
                // horizontal fling only
                if (abs(dx) > abs(dy) && abs(dx) > 100) {
                    if (dx > 0) seekBy(SEEK_MS) else seekBy(-SEEK_MS)
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // double-tap right half = +10, left half = -10
                if (e.x > binding.playerView.width / 2) seekBy(SEEK_MS)
                else seekBy(-SEEK_MS)
                return true
            }
        })

        // Forward touches to the gesture detector; if it doesn't consume the
        // event, let PlayerView handle it (controller show/hide, seek bar).
        binding.playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }
    }

    private fun setupButtons() {
        binding.btnMinus10.setOnClickListener { seekBy(-SEEK_MS) }
        binding.btnPlus10.setOnClickListener { seekBy(SEEK_MS) }
        binding.btnClose.setOnClickListener { finish() }
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val target = (p.currentPosition + deltaMs).coerceIn(0, p.duration.coerceAtLeast(0))
        p.seekTo(target)
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

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
