package com.nsl.downloader.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.nsl.downloader.data.DownloadStatus
import com.nsl.downloader.data.VideoEntity
import com.nsl.downloader.databinding.ItemVideoBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoAdapter(
    private val onClick: (VideoEntity) -> Unit,
    private val onDelete: (VideoEntity) -> Unit
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
                append(dateFmt.format(Date(video.downloadedAt)))
                if (video.fileSizeBytes > 0) {
                    append(" • ")
                    append(formatSize(video.fileSizeBytes))
                }
            }

            // Default: hide progress UI.
            progressBar.visibility = View.GONE

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
                    statusBadge.text = "Failed"
                    root.alpha = 0.6f
                }
                DownloadStatus.PENDING -> {
                    statusBadge.visibility = View.VISIBLE
                    statusBadge.text = "Pending"
                    root.alpha = 0.6f
                }
            }

            if (video.status == DownloadStatus.COMPLETED && video.localPath.isNotBlank()) {
                Glide.with(thumbnail).load(video.localPath)
                    .placeholder(android.R.drawable.ic_media_play)
                    .into(thumbnail)
            } else {
                thumbnail.setImageResource(android.R.drawable.ic_media_play)
            }

            root.setOnClickListener {
                if (video.status == DownloadStatus.COMPLETED) onClick(video)
            }
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

    private fun formatSpeed(bps: Long): String {
        val kb = bps / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1 -> String.format(Locale.US, "%.1f MB/s", mb)
            else -> String.format(Locale.US, "%.0f KB/s", kb)
        }
    }
}
