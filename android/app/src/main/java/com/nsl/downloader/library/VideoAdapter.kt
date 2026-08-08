package com.nsl.downloader.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.nsl.downloader.R
import com.nsl.downloader.data.DownloadStatus
import com.nsl.downloader.data.VideoEntity
import com.nsl.downloader.databinding.ItemVideoBinding
import com.nsl.downloader.util.MediaStorage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoAdapter(
    private val onClick: (VideoEntity) -> Unit,
    private val onDelete: (VideoEntity) -> Unit,
    private val onLongClick: (VideoEntity) -> Unit,
    private val onResume: (VideoEntity) -> Unit,
    private val onToggleSelect: (VideoEntity) -> Unit
) : ListAdapter<VideoRow, VideoAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<VideoRow>() {
            override fun areItemsTheSame(a: VideoRow, b: VideoRow) = a.video.id == b.video.id
            override fun areContentsTheSame(a: VideoRow, b: VideoRow) = a == b
        }
        private val dateFmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    }

    inner class VH(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemVideoBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
        val video = row.video
        with(holder.binding) {
            title.text = video.title
            subtitle.text = buildString {
                if (video.durationMs > 0) append(formatDuration(video.durationMs))
                if (video.fileSizeBytes > 0) {
                    if (isNotEmpty()) append(" • ")
                    append(formatSize(video.fileSizeBytes))
                }
                if (isNotEmpty()) append(" • ")
                append(dateFmt.format(Date(video.downloadedAt)))
            }

            // Default: hide progress UI.
            progressBar.visibility = View.GONE

            // While a multi-select runs the row is a checkbox, not a set of
            // per-item actions — acting on one video mid-selection would be a
            // slip, and the bar above is where the actions live.
            val selectable = row.selectionActive && video.canMove
            checkbox.visibility = if (row.selectionActive) View.VISIBLE else View.GONE
            checkbox.isEnabled = selectable
            checkbox.isChecked = row.selected
            btnResume.visibility =
                if (video.canResume && !row.selectionActive) View.VISIBLE else View.GONE
            btnDelete.visibility = if (row.selectionActive) View.GONE else View.VISIBLE

            when (video.status) {
                DownloadStatus.COMPLETED -> {
                    statusBadge.visibility = View.GONE
                    root.alpha = 1f
                }
                DownloadStatus.DOWNLOADING -> {
                    root.alpha = 0.85f
                    statusBadge.visibility = View.VISIBLE
                    val p = row.progress
                    if (p != null) {
                        progressBar.visibility = View.VISIBLE
                        if (p.percent in 0..100) {
                            progressBar.isIndeterminate = false
                            progressBar.progress = p.percent
                            statusBadge.text = buildString {
                                append("${p.percent}%")
                                if (p.bytesPerSec > 0) append(" • ${formatSpeed(p.bytesPerSec)}")
                            }
                        } else {
                            progressBar.isIndeterminate = true
                            statusBadge.text = if (p.bytesPerSec > 0)
                                "Downloading… • ${formatSpeed(p.bytesPerSec)}" else "Downloading…"
                        }
                    } else {
                        statusBadge.text = "Downloading…"
                    }
                }
                DownloadStatus.FAILED -> {
                    statusBadge.visibility = View.VISIBLE
                    statusBadge.text = root.context.getString(R.string.library_failed)
                    root.alpha = 0.6f
                }
                DownloadStatus.PENDING -> {
                    statusBadge.visibility = View.VISIBLE
                    statusBadge.text = "Pending"
                    root.alpha = 0.6f
                }
            }

            if (video.status == DownloadStatus.COMPLETED && video.localPath.isNotBlank()) {
                // Published items live in shared storage, so the path is a
                // content:// URI on API 29+ and a plain path below that.
                Glide.with(thumbnail).load(MediaStorage.toUri(video.localPath))
                    .placeholder(android.R.drawable.ic_media_play)
                    .into(thumbnail)
            } else {
                thumbnail.setImageResource(android.R.drawable.ic_media_play)
            }

            // A selected row is tinted rather than dimmed: dimming already means
            // "not finished" here.
            root.isActivated = row.selected

            // Whatever the failed attempt fetched is still on disk, so a failed
            // row is an offer to carry on, not a dead end.
            btnResume.setOnClickListener { onResume(video) }
            root.setOnClickListener {
                when {
                    row.selectionActive -> if (selectable) onToggleSelect(video)
                    video.status == DownloadStatus.COMPLETED -> onClick(video)
                    video.canResume -> onResume(video)
                    else -> Unit
                }
            }
            root.setOnLongClickListener {
                when {
                    // The gesture that starts a multi-select is also the one
                    // that extends it, so it keeps working once one is running.
                    video.canMove -> {
                        onToggleSelect(video)
                        true
                    }
                    !row.selectionActive && video.status == DownloadStatus.FAILED -> {
                        onLongClick(video)
                        true
                    }
                    else -> false
                }
            }
            // Same button, different meaning: mid-transfer it cancels.
            btnDelete.contentDescription = root.context.getString(
                if (video.status == DownloadStatus.DOWNLOADING ||
                    video.status == DownloadStatus.PENDING
                ) R.string.cancel_download else R.string.remove
            )
            btnDelete.setOnClickListener { onDelete(video) }
        }
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format(Locale.US, "%.1f GB", gb)
            mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun formatSpeed(bps: Long): String {
        val kb = bps / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1 -> String.format(Locale.US, "%.1f MB/s", mb)
            else -> String.format(Locale.US, "%.0f KB/s", kb)
        }
    }
}
