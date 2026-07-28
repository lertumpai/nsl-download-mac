package com.nsl.downloader.browser

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nsl.downloader.R
import com.nsl.downloader.databinding.DialogPlaylistBinding
import com.nsl.downloader.databinding.ItemPlaylistPickBinding
import com.nsl.downloader.youtube.YouTubeResolver
import java.util.Locale

/**
 * Shows every video in a playlist with a checkbox each, so a 40-item listing
 * does not have to be an all-or-nothing download. Everything starts selected —
 * "the whole playlist" is still the common case, deselecting is the exception.
 */
object PlaylistPicker {

    fun show(
        context: Context,
        playlistTitle: String,
        items: List<YouTubeResolver.PlaylistItem>,
        onConfirm: (List<YouTubeResolver.PlaylistItem>) -> Unit
    ) {
        val binding = DialogPlaylistBinding.inflate(LayoutInflater.from(context))
        val checked = BooleanArray(items.size) { true }

        var syncButtons: () -> Unit = {}
        val adapter = PickAdapter(items, checked) { syncButtons() }

        binding.list.layoutManager = LinearLayoutManager(context)
        binding.list.adapter = adapter
        binding.list.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, listHeight(context, items.size)
        )

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(playlistTitle)
            .setView(binding.root)
            // Bound below: the label carries the count and the click must not
            // dismiss when nothing is selected.
            .setPositiveButton(R.string.download, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        binding.selectAll.setOnClickListener {
            val target = binding.selectAll.isChecked
            checked.indices.forEach { checked[it] = target }
            adapter.notifyItemRangeChanged(0, items.size)
            syncButtons()
        }

        syncButtons = {
            val selected = checked.count { it }
            binding.selectionCount.text =
                context.getString(R.string.yt_playlist_selected, selected, items.size)
            binding.selectAll.isChecked = selected == items.size
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                isEnabled = selected > 0
                text = context.getString(R.string.yt_playlist_download_n, selected)
            }
        }

        dialog.setOnShowListener {
            syncButtons()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = items.filterIndexed { index, _ -> checked[index] }
                if (selected.isEmpty()) return@setOnClickListener
                dialog.dismiss()
                onConfirm(selected)
            }
        }
        dialog.show()
    }

    /**
     * Tall enough for the whole listing, capped so the dialog keeps its title
     * and buttons on screen. A RecyclerView has no maxHeight of its own.
     */
    private fun listHeight(context: Context, count: Int): Int {
        val metrics = context.resources.displayMetrics
        val rowPx = (56 * metrics.density).toInt()
        return (count * rowPx).coerceAtMost((metrics.heightPixels * 0.55f).toInt())
    }

    private class PickAdapter(
        private val items: List<YouTubeResolver.PlaylistItem>,
        private val checked: BooleanArray,
        private val onToggled: () -> Unit
    ) : RecyclerView.Adapter<PickAdapter.VH>() {

        class VH(val binding: ItemPlaylistPickBinding) : RecyclerView.ViewHolder(binding.root)

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            ItemPlaylistPickBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            with(holder.binding) {
                title.text = "${position + 1}. ${item.title}"
                subtitle.text = buildString {
                    if (item.durationSeconds > 0) append(formatDuration(item.durationSeconds))
                    if (item.uploader.isNotBlank()) {
                        if (isNotEmpty()) append(" • ")
                        append(item.uploader)
                    }
                }
                subtitle.visibility =
                    if (subtitle.text.isNullOrBlank()) android.view.View.GONE
                    else android.view.View.VISIBLE
                check.isChecked = checked[position]

                if (item.thumbnailUrl != null) {
                    Glide.with(thumbnail).load(item.thumbnailUrl)
                        .placeholder(android.R.drawable.ic_media_play)
                        .into(thumbnail)
                } else {
                    thumbnail.setImageResource(android.R.drawable.ic_media_play)
                }

                // The row is the hit target; the checkbox itself is decorative
                // (clickable=false in the layout) so both cannot fight.
                root.setOnClickListener {
                    val index = holder.bindingAdapterPosition
                    if (index == RecyclerView.NO_POSITION) return@setOnClickListener
                    checked[index] = !checked[index]
                    check.isChecked = checked[index]
                    onToggled()
                }
            }
        }

        private fun formatDuration(seconds: Long): String {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            else String.format(Locale.US, "%d:%02d", m, s)
        }
    }
}
