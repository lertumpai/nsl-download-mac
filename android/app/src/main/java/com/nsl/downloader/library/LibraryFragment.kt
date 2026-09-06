package com.nsl.downloader.library

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nsl.downloader.R
import com.nsl.downloader.data.DownloadQueueBus
import com.nsl.downloader.data.DownloadStatus
import com.nsl.downloader.data.FolderEntity
import com.nsl.downloader.data.VideoEntity
import com.nsl.downloader.databinding.FragmentLibraryBinding
import com.nsl.downloader.player.PlayerActivity
import com.nsl.downloader.service.DownloadService
import com.nsl.downloader.util.MediaStorage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LibraryViewModel by viewModels()

    private val adapter = VideoAdapter(
        onClick = { openPlayer(it) },
        onDelete = { confirmDeleteSingle(it) },
        onLongClick = { showItemActions(it) },
        onResume = { confirmResume(it) },
        onToggleSelect = { viewModel.toggleSelection(it) }
    )

    /** Back leaves a running multi-select before it leaves the screen. */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            viewModel.clearSelection()
        }
    }

    /** Guards against the chip listener firing while we rebuild the row. */
    private var rebuildingChips = false

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
        binding.btnOpenFolder.setOnClickListener { openRealFolder() }
        binding.btnCloseSelection.setOnClickListener { viewModel.clearSelection() }
        binding.btnSelectAll.setOnClickListener { viewModel.selectAll() }
        binding.btnMoveSelected.setOnClickListener { promptMoveSelection() }
        binding.btnRepairSelected.setOnClickListener { confirmRepairSelection() }
        binding.btnCancelRepair.setOnClickListener { viewModel.cancelRepair() }

        requireActivity().onBackPressedDispatcher
            .addCallback(viewLifecycleOwner, backCallback)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.rows.collectLatest { list ->
                adapter.submitList(list)
                val empty = list.isEmpty()
                binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
                binding.emptyView.setText(
                    if (viewModel.selectedFolderId.value == null) R.string.empty_library
                    else R.string.library_empty_folder
                )
                binding.btnRemoveAll.visibility = if (empty) View.GONE else View.VISIBLE
                showSelectionBar(list.count { it.selected })
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.folders.collectLatest { rebuildChips(it) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            DownloadQueueBus.state.collectLatest { showBatchBanner(it.firstOrNull()) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.repairProgress.collectLatest { showRepairProgress(it) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.repairResults.collectLatest { showRepairResult(it) }
        }
    }

    // ------------------------------------------------------ playlist queue

    /**
     * Queued playlist items have no library row until their turn comes, so the
     * banner is the only place the rest of the queue is visible — and the only
     * way to stop it without cancelling row by row as each one starts.
     */
    private fun showBatchBanner(batch: DownloadQueueBus.Batch?) {
        binding.batchBanner.visibility = if (batch == null) View.GONE else View.VISIBLE
        if (batch == null) return
        binding.batchText.text = getString(
            R.string.library_batch_progress, batch.label, batch.finished, batch.total
        )
        binding.btnCancelBatch.setOnClickListener { confirmCancelBatch(batch) }
    }

    private fun confirmCancelBatch(batch: DownloadQueueBus.Batch) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.library_cancel_batch_title)
            .setMessage(
                getString(R.string.library_cancel_batch_message, batch.label, batch.remaining)
            )
            .setPositiveButton(R.string.library_cancel_batch) { _, _ ->
                DownloadService.cancelBatch(requireContext(), batch.id)
            }
            .setNegativeButton(R.string.keep_downloading, null)
            .show()
    }

    // -------------------------------------------------------------- folders

    private fun rebuildChips(folders: List<FolderEntity>) {
        val group = binding.folderChips
        rebuildingChips = true
        group.removeAllViews()

        group.addView(folderChip(getString(R.string.library_all), null))
        folders.forEach { group.addView(folderChip(it.name, it)) }

        // Trailing action chip: creating a folder is the one non-filter entry.
        group.addView(
            Chip(requireContext()).apply {
                text = getString(R.string.library_new_folder)
                isCheckable = false
                setOnClickListener { promptCreateFolder() }
            }
        )
        rebuildingChips = false
    }

    private fun folderChip(label: String, folder: FolderEntity?): Chip =
        Chip(requireContext()).apply {
            text = label
            isCheckable = true
            isChecked = viewModel.selectedFolderId.value == folder?.id
            setOnClickListener {
                if (!rebuildingChips) viewModel.selectFolder(folder?.id)
            }
            if (folder != null) {
                setOnLongClickListener {
                    confirmDeleteFolder(folder)
                    true
                }
            }
        }

    /**
     * [onCreated] is how the move flows carry on into a folder that did not
     * exist yet: with a caller waiting on it, the new folder is not browsed to,
     * so the selection being moved survives the detour.
     */
    private fun promptCreateFolder(onCreated: ((id: Long, name: String) -> Unit)? = null) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.library_folder_name_hint)
            setSingleLine()
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad * 2, pad, pad * 2, pad)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.library_create_folder_title)
            .setView(input)
            .setPositiveButton(R.string.library_create) { _, _ ->
                val name = input.text.toString().trim()
                viewModel.createFolder(name, select = onCreated == null) { id ->
                    if (!isAdded) return@createFolder
                    if (id == null) {
                        Toast.makeText(
                            requireContext(), R.string.library_folder_exists, Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        onCreated?.invoke(id, name)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteFolder(folder: FolderEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.library_delete_folder_title)
            .setMessage(getString(R.string.library_delete_folder_message, folder.name))
            .setPositiveButton(R.string.remove) { _, _ -> viewModel.deleteFolder(folder) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Opens the folder that actually holds the files, in whichever file manager
     * the device has. Handlers vary wildly by OEM, so the candidates from
     * [MediaStorage.openFolderIntents] are tried in order.
     */
    private fun openRealFolder() {
        val folderName = viewModel.folderName(viewModel.selectedFolderId.value)
        for (intent in MediaStorage.openFolderIntents(folderName)) {
            // Handlers differ per OEM and reject these in several ways
            // (missing activity, permission, malformed document URI).
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
        Toast.makeText(requireContext(), R.string.library_no_file_manager, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------- multi-select

    /**
     * The bar is the multi-select's only chrome, so it goes up and down with
     * the pick itself — as does back, which would otherwise leave the screen
     * with rows still ticked behind it.
     */
    private fun showSelectionBar(count: Int) {
        val active = count > 0
        binding.selectionBar.visibility = if (active) View.VISIBLE else View.GONE
        backCallback.isEnabled = active
        if (!active) return
        binding.selectionCount.text =
            getString(R.string.library_selected_count, count, viewModel.selectableCount())
        binding.btnSelectionMore.visibility = if (count == 1) View.VISIBLE else View.GONE
        binding.btnSelectionMore.setOnClickListener {
            viewModel.selectedVideos.singleOrNull()?.let { showItemActions(it) }
        }
        binding.btnSelectAll.isEnabled = count < viewModel.selectableCount()
        binding.btnRepairSelected.isEnabled = viewModel.selectedVideos.any { it.canRepair }
    }

    private fun confirmRepairSelection() {
        val videos = viewModel.selectedVideos.filter { it.canRepair }
        if (videos.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(resources.getQuantityString(R.plurals.library_repair_title, videos.size, videos.size))
            .setMessage(resources.getQuantityString(R.plurals.library_repair_message, videos.size, videos.size))
            .setPositiveButton(R.string.library_repair) { _, _ -> viewModel.repairVideos(videos) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRepairProgress(progress: RepairProgress?) {
        binding.repairBanner.visibility = if (progress == null) View.GONE else View.VISIBLE
        if (progress == null) return
        binding.repairText.text = getString(
            R.string.library_repair_progress,
            progress.current, progress.total, progress.title, progress.percent
        )
        binding.repairProgress.progress = progress.percent
    }

    private fun showRepairResult(result: RepairResult) {
        if (!isAdded) return
        val message = if (result.cancelled) {
            getString(R.string.library_repair_cancelled, result.repaired)
        } else {
            getString(
                R.string.library_repair_finished,
                result.repaired, result.alreadyPlayable, result.failed
            )
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    /** Move for the whole selection; single moves go through [promptMove]. */
    private fun promptMoveSelection() {
        val picked = viewModel.selectedVideos
        if (picked.isEmpty()) return
        // A target worth offering is one that would actually move something —
        // which only rules anything out when the whole pick sits in one folder.
        val sourceFolders = picked.map { it.folderId }.toSet()
        val excluding = if (sourceFolders.size == 1) sourceFolders else emptySet()
        promptMoveTo(excluding) { targetId, targetName ->
            viewModel.moveVideos(picked, targetId) { moved ->
                if (!isAdded) return@moveVideos
                Toast.makeText(
                    requireContext(),
                    resources.getQuantityString(
                        R.plurals.library_moved_count, moved, moved, targetName
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * The folder chooser both move paths share. [excluding] drops targets that
     * would not move anything; picking "New folder" restarts the flow once the
     * folder exists, so the selection is not lost to a detour.
     */
    private fun promptMoveTo(
        excluding: Set<Long?>,
        onPick: (targetId: Long?, targetName: String) -> Unit
    ) {
        val targets = buildList<Pair<String, Long?>> {
            add(getString(R.string.library_root) to null)
            viewModel.folders.value.forEach { add(it.name to it.id) }
        }.filter { it.second !in excluding }

        if (targets.isEmpty()) {
            promptCreateFolder { id, name -> onPick(id, name) }
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.library_move_to)
            .setItems(targets.map { it.first }.toTypedArray()) { _, which ->
                val (name, id) = targets[which]
                onPick(id, name)
            }
            .setNeutralButton(R.string.library_new_folder) { _, _ ->
                promptCreateFolder { id, name -> onPick(id, name) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // The tabs share one back dispatcher; a pick left running behind the
        // browser would swallow its back press.
        if (hidden) viewModel.clearSelection()
    }

    // ---------------------------------------------------------------- items

    private fun openPlayer(video: VideoEntity) {
        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_ID, video.id)
            putExtra(PlayerActivity.EXTRA_PATH, video.localPath)
            putExtra(PlayerActivity.EXTRA_TITLE, video.title)
        }
        startActivity(intent)
    }

    private fun showItemActions(video: VideoEntity) {
        val actions = if (video.status == DownloadStatus.FAILED) {
            buildList<Pair<String, () -> Unit>> {
                if (video.canResume) {
                    add(getString(R.string.resume_download) to { confirmResume(video) })
                }
                add(getString(R.string.remove) to { confirmDeleteSingle(video) })
            }
        } else {
            listOf<Pair<String, () -> Unit>>(
                getString(R.string.library_play) to { openPlayer(video) },
                getString(R.string.library_repair) to {
                    viewModel.clearSelection()
                    viewModel.repairVideos(listOf(video))
                },
                getString(R.string.library_move_to) to { promptMove(video) },
                getString(R.string.library_share) to { shareVideo(video) },
                getString(R.string.remove) to { confirmDeleteSingle(video) }
            )
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(video.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptMove(video: VideoEntity) {
        promptMoveTo(excluding = setOf(video.folderId)) { targetId, targetName ->
            viewModel.moveVideo(video, targetId)
            Toast.makeText(
                requireContext(), getString(R.string.library_moved, targetName), Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun shareVideo(video: VideoEntity) {
        if (video.localPath.isBlank()) return
        // A raw file:// URI in an outgoing Intent throws FileUriExposedException
        // on API 24+, so legacy paths are wrapped by the FileProvider.
        val uri = if (MediaStorage.isContentUri(video.localPath)) {
            MediaStorage.toUri(video.localPath)
        } else {
            runCatching {
                FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    File(video.localPath)
                )
            }.getOrNull()
        }
        if (uri == null) {
            Toast.makeText(requireContext(), R.string.library_share_failed, Toast.LENGTH_SHORT)
                .show()
            return
        }
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = video.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                getString(R.string.library_share)
            )
        )
    }

    /**
     * The failed row's own action. Asking first because the transfer can be a
     * big one and the user may have wanted it stopped — but the partial file is
     * still there, so agreeing continues it rather than starting again.
     */
    private fun confirmResume(video: VideoEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.library_resume_title)
            .setMessage(getString(R.string.library_resume_message, video.title))
            .setPositiveButton(R.string.resume_download) { _, _ -> viewModel.resumeVideo(video) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Doubles as the cancel affordance: for a row that is still transferring,
     * removing it is what stops the download.
     */
    private fun confirmDeleteSingle(video: VideoEntity) {
        val inFlight = video.status == DownloadStatus.DOWNLOADING ||
            video.status == DownloadStatus.PENDING
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (inFlight) R.string.cancel_download else R.string.remove_video_title)
            .setMessage(
                getString(
                    if (inFlight) R.string.cancel_download_message
                    else R.string.remove_video_message,
                    video.title
                )
            )
            .setPositiveButton(if (inFlight) R.string.cancel_download else R.string.remove) { _, _ ->
                viewModel.removeVideo(video)
            }
            // "Cancel" would read as "cancel the download" on the in-flight path.
            .setNegativeButton(
                if (inFlight) R.string.keep_downloading else android.R.string.cancel, null
            )
            .show()
    }

    private fun confirmRemoveAll() {
        val folderName = viewModel.folderName(viewModel.selectedFolderId.value)
        val scope = folderName ?: getString(R.string.library_all).lowercase()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.remove_all)
            .setMessage(getString(R.string.remove_all_message, scope))
            .setPositiveButton(R.string.remove_all) { _, _ -> viewModel.removeAll() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
