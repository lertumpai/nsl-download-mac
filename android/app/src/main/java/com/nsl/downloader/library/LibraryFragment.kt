package com.nsl.downloader.library

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nsl.downloader.data.VideoEntity
import com.nsl.downloader.databinding.FragmentLibraryBinding
import com.nsl.downloader.player.PlayerActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LibraryViewModel by viewModels()

    private val adapter = VideoAdapter(
        onClick = { openPlayer(it) },
        onDelete = { confirmDeleteSingle(it) }
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.btnRemoveAll.setOnClickListener { confirmRemoveAll() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.rows.collectLatest { list ->
                adapter.submitList(list)
                binding.emptyView.visibility =
                    if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.btnRemoveAll.visibility =
                    if (list.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun openPlayer(video: VideoEntity) {
        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_PATH, video.localPath)
            putExtra(PlayerActivity.EXTRA_TITLE, video.title)
        }
        startActivity(intent)
    }

    private fun confirmDeleteSingle(video: VideoEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Remove video")
            .setMessage("Remove \"${video.title}\" and delete its file from this device?")
            .setPositiveButton("Remove") { _, _ -> viewModel.removeVideo(video) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRemoveAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Remove all videos")
            .setMessage("This deletes every downloaded video and all their files. This cannot be undone.")
            .setPositiveButton("Remove all") { _, _ -> viewModel.removeAll() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
