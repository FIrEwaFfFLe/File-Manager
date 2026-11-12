package org.fossify.filemanager.fragments

import android.app.DatePickerDialog
import android.content.ContentResolver
import android.content.Context
import android.provider.MediaStore.Files
import android.provider.MediaStore.Files.FileColumns
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getDoesFilePathExist
import org.fossify.commons.extensions.getDocumentFile
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getLongValue
import org.fossify.commons.extensions.getStringValue
import org.fossify.commons.extensions.getOTGItems
import org.fossify.commons.extensions.hasOTGConnected
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.VIEW_TYPE_GRID
import org.fossify.commons.helpers.VIEW_TYPE_LIST
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.FileDirItem
import org.fossify.commons.views.MyGridLayoutManager
import org.fossify.commons.views.MyRecyclerView
import org.fossify.filemanager.activities.MainActivity
import org.fossify.filemanager.activities.SimpleActivity
import org.fossify.filemanager.R
import org.fossify.filemanager.adapters.ItemsAdapter
import org.fossify.filemanager.databinding.RecentsFragmentBinding
import org.fossify.filemanager.extensions.config
import org.fossify.filemanager.extensions.isPathInHiddenFolder
import org.fossify.filemanager.helpers.MAX_COLUMN_COUNT
import org.fossify.filemanager.helpers.RecentFileClassifier
import org.fossify.filemanager.helpers.RecentDateRange
import org.fossify.filemanager.helpers.RecentStorageSource
import org.fossify.filemanager.helpers.RecentStorageSourceResolver
import org.fossify.filemanager.interfaces.ItemOperationsListener
import org.fossify.filemanager.models.ListItem
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.ArrayDeque
import java.util.PriorityQueue
import java.util.Locale

class RecentsFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment<MyViewPagerFragment.RecentsInnerBinding>(context, attributeSet),
    ItemOperationsListener {
    private val QUERY_PAGE_SIZE = 500
    private var filesIgnoringSearch = ArrayList<ListItem>()
    private var lastSearchedText = ""
    private var zoomListener: MyRecyclerView.MyZoomListener? = null
    private lateinit var binding: RecentsFragmentBinding

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = RecentsFragmentBinding.bind(this)
        innerBinding = RecentsInnerBinding(binding)
    }

    override fun setupFragment(activity: SimpleActivity) {
        if (this.activity == null) {
            this.activity = activity
            binding.recentsSwipeRefresh.setOnRefreshListener { refreshFragment() }
        }

        refreshFragment()
    }

    override fun refreshFragment() {
        ensureBackgroundThread {
            getRecents { recents ->
                binding.apply {
                    recentsSwipeRefresh.isRefreshing = false
                    recentsList.beVisibleIf(recents.isNotEmpty())
                    recentsPlaceholder.beVisibleIf(recents.isEmpty())
                    recentsPlaceholder.setText(if (context?.config?.let { !it.recentsImages && !it.recentsVideos && !it.recentsOther } == true) R.string.no_recent_types_enabled else R.string.no_items_found)
                }
                filesIgnoringSearch = recents
                addItems(recents, false)

                if (context != null && currentViewType != context!!.config.getFolderViewType("")) {
                    setupLayoutManager()
                }
            }
        }
    }

    private fun addItems(recents: ArrayList<ListItem>, forceRefresh: Boolean) {
        if (!forceRefresh && recents.hashCode() == (binding.recentsList.adapter as? ItemsAdapter)?.listItems.hashCode()) {
            return
        }

        ItemsAdapter(activity as SimpleActivity, recents, this, binding.recentsList, isPickMultipleIntent, binding.recentsSwipeRefresh, false) {
            clickedPath((it as FileDirItem).path)
        }.apply {
            setupZoomListener(zoomListener)
            binding.recentsList.adapter = this
        }

        if (context.areSystemAnimationsEnabled) {
            binding.recentsList.scheduleLayoutAnimation()
        }
    }

    override fun onResume(textColor: Int) {
        binding.recentsPlaceholder.setTextColor(textColor)

        getRecyclerAdapter()?.apply {
            updatePrimaryColor()
            updateTextColor(textColor)
            initDrawables()
        }

        binding.recentsSwipeRefresh.isEnabled = lastSearchedText.isEmpty() && activity?.config?.enablePullToRefresh != false
        refreshFragment()
    }

    private fun setupLayoutManager() {
        if (context!!.config.getFolderViewType("") == VIEW_TYPE_GRID) {
            currentViewType = VIEW_TYPE_GRID
            setupGridLayoutManager()
        } else {
            currentViewType = VIEW_TYPE_LIST
            setupListLayoutManager()
        }

        val oldItems = (binding.recentsList.adapter as? ItemsAdapter)?.listItems?.toMutableList() as ArrayList<ListItem>
        binding.recentsList.adapter = null
        initZoomListener()
        addItems(oldItems, true)
    }

    private fun setupGridLayoutManager() {
        val layoutManager = binding.recentsList.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = context?.config?.fileColumnCnt ?: 3
    }

    private fun setupListLayoutManager() {
        val layoutManager = binding.recentsList.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = 1
        zoomListener = null
    }

    private fun initZoomListener() {
        if (context?.config?.getFolderViewType("") == VIEW_TYPE_GRID) {
            val layoutManager = binding.recentsList.layoutManager as MyGridLayoutManager
            zoomListener = object : MyRecyclerView.MyZoomListener {
                override fun zoomIn() {
                    if (layoutManager.spanCount > 1) {
                        reduceColumnCount()
                        getRecyclerAdapter()?.finishActMode()
                    }
                }

                override fun zoomOut() {
                    if (layoutManager.spanCount < MAX_COLUMN_COUNT) {
                        increaseColumnCount()
                        getRecyclerAdapter()?.finishActMode()
                    }
                }
            }
        } else {
            zoomListener = null
        }
    }

    private fun getRecents(callback: (recents: ArrayList<ListItem>) -> Unit) {
        val showHidden = context?.config?.shouldShowHidden() ?: return
        val config = context?.config ?: return
        val host = activity ?: return
        if (!config.recentsImages && !config.recentsVideos && !config.recentsOther) {
            activity?.runOnUiThread { callback(arrayListOf()) }
            return
        }
        val limit = config.recentsLimit
        val internalItems = arrayListOf<ListItem>()
        val seenInternalPaths = HashSet<String>()
        if (config.recentsInternalStorage) {
            try {
                val uri = Files.getContentUri("external")
                val projection = arrayOf(
                    FileColumns.DATA,
                    FileColumns.DISPLAY_NAME,
                    FileColumns.DATE_MODIFIED,
                    FileColumns.SIZE,
                    FileColumns.MIME_TYPE
                )

                var offset = 0
                var exhausted = false
                while (!exhausted && internalItems.size < limit) {
                    val queryArgs = bundleOf(
                        ContentResolver.QUERY_ARG_LIMIT to QUERY_PAGE_SIZE,
                        ContentResolver.QUERY_ARG_OFFSET to offset,
                        ContentResolver.QUERY_ARG_SORT_COLUMNS to arrayOf(FileColumns.DATE_MODIFIED),
                        ContentResolver.QUERY_ARG_SORT_DIRECTION to ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                    )
                    var rowCount = 0
                    context.contentResolver.query(uri, projection, queryArgs, null)?.use { cursor ->
                        while (cursor.moveToNext() && internalItems.size < limit) {
                            rowCount++
                            val path = cursor.getStringValue(FileColumns.DATA)
                            if (path.isNullOrBlank() || RecentStorageSourceResolver.classify(
                                    path, config.internalStoragePath, config.OTGPath
                                ) != RecentStorageSource.INTERNAL || File(path).isDirectory
                            ) {
                                continue
                            }

                            val name = cursor.getStringValue(FileColumns.DISPLAY_NAME) ?: path.getFilenameFromPath()
                            val isHiddenFile = name.startsWith(".")
                            val shouldShow = showHidden || (!isHiddenFile && !path.isPathInHiddenFolder())
                            val mimeType = cursor.getStringValue(FileColumns.MIME_TYPE)
                            val type = RecentFileClassifier.classify(path, mimeType)
                            val included = when (type) {
                                RecentFileClassifier.Type.IMAGE -> config.recentsImages
                                RecentFileClassifier.Type.VIDEO -> config.recentsVideos
                                RecentFileClassifier.Type.OTHER -> config.recentsOther
                            }
                            if (!included || !shouldShow || host.getDoesFilePathExist(path) != true ||
                                !wantedMimeTypes.any { isProperMimeType(it, path, false) } ||
                                !seenInternalPaths.add(path)
                            ) {
                                continue
                            }

                            val filesystemModified = cursor.getLongValue(FileColumns.DATE_MODIFIED) * 1000L
                            internalItems.add(
                                ListItem(path, name, false, 0, cursor.getLongValue(FileColumns.SIZE), filesystemModified, false, false)
                            )
                        }
                    }
                    exhausted = rowCount < QUERY_PAGE_SIZE
                    offset += rowCount
                }
            } catch (e: Exception) {
                // Internal MediaStore access is independent from the separately granted OTG tree.
                host.showErrorToast(e)
            }
        }

        val usbItems = if (config.recentsUsbStorage) {
            try {
                collectUsbRecents(host, config.OTGPath, showHidden, limit, config.recentsImages, config.recentsVideos, config.recentsOther)
            } catch (e: Exception) {
                host.showErrorToast(e)
                emptyList()
            }
        } else {
            emptyList()
        }
        val merged = (internalItems + usbItems)
            .distinctBy { it.path }
            .sortedWith(compareByDescending<ListItem> { it.modified }.thenBy { it.path })
            .take(limit)
        val listItems = ArrayList(merged)

        activity?.runOnUiThread { callback(listItems) }
    }

    /** OTG files are not reliably indexed by MediaStore, so walk the granted Fossify OTG tree. */
    private fun collectUsbRecents(
        host: SimpleActivity,
        usbRoot: String,
        showHidden: Boolean,
        limit: Int,
        includeImages: Boolean,
        includeVideos: Boolean,
        includeOther: Boolean
    ): List<ListItem> {
        if (usbRoot.isBlank() || host.config.OTGTreeUri.isBlank() || !host.hasOTGConnected()) return emptyList()

        val candidates = PriorityQueue<ListItem>(
            compareBy<ListItem> { it.modified }.thenByDescending { it.path }
        )
        val queuedPaths = HashSet<String>()
        val pendingDirectories = ArrayDeque<String>()
        pendingDirectories.add(usbRoot.trimEnd('/'))

        while (pendingDirectories.isNotEmpty()) {
            val directoryPath = pendingDirectories.removeFirst()
            if (!queuedPaths.add(directoryPath)) continue

            host.getOTGItems(directoryPath, showHidden, false) { children ->
                children.forEach { child ->
                    val source = RecentStorageSourceResolver.classify(child.path, host.config.internalStoragePath, usbRoot)
                    if (source != RecentStorageSource.USB) return@forEach
                    if (child.isDirectory) {
                        pendingDirectories.addLast(child.path)
                        return@forEach
                    }

                    val path = child.path
                    val name = child.name
                    if ((!showHidden && name.startsWith(".")) || child.modified <= 0L ||
                        host.getDocumentFile(path)?.canRead() != true ||
                        !wantedMimeTypes.any { isProperMimeType(it, path, false) }
                    ) {
                        return@forEach
                    }
                    val type = RecentFileClassifier.classify(path, null)
                    val included = when (type) {
                        RecentFileClassifier.Type.IMAGE -> includeImages
                        RecentFileClassifier.Type.VIDEO -> includeVideos
                        RecentFileClassifier.Type.OTHER -> includeOther
                    }
                    if (!included) return@forEach

                    val item = ListItem(path, name, false, 0, child.size, child.modified, false, false)
                    if (candidates.size < limit) {
                        candidates.add(item)
                    } else if (candidates.comparator().compare(item, candidates.peek()) > 0) {
                        candidates.poll()
                        candidates.add(item)
                    }
                }
            }
        }

        return candidates.toList().sortedWith(compareByDescending<ListItem> { it.modified }.thenBy { it.path })
    }

    private fun getRecyclerAdapter() = binding.recentsList.adapter as? ItemsAdapter

    fun showDateRangeSelectionDialog() {
        val host = activity ?: return
        val today = LocalDate.now()
        var startDate: LocalDate? = today
        var endDate: LocalDate? = today
        val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())

        val content = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
        }
        val validation = TextView(host).apply {
            setTextColor(host.getColor(org.fossify.commons.R.color.md_red_500))
            visibility = TextView.GONE
        }

        // Keep the date controls separate; each opens a native calendar picker using the current value.
        val startPicker = Button(host).apply {
            isAllCaps = false
        }
        val endPicker = Button(host).apply {
            isAllCaps = false
        }
        fun updateDateButton(button: Button, labelRes: Int, date: LocalDate) {
            button.text = host.getString(labelRes, dateFormatter.format(date))
        }
        fun showPicker(button: Button, labelRes: Int, selectedDate: () -> LocalDate, update: (LocalDate) -> Unit) {
            updateDateButton(button, labelRes, selectedDate())
            button.setOnClickListener {
                val date = selectedDate()
                DatePickerDialog(host, { _, year, month, day ->
                    update(LocalDate.of(year, month + 1, day))
                }, date.year, date.monthValue - 1, date.dayOfMonth).show()
            }
        }
        showPicker(startPicker, R.string.date_range_starting_date, { startDate ?: today }) { selected ->
            startDate = selected
            updateDateButton(startPicker, R.string.date_range_starting_date, selected)
            validation.visibility = TextView.GONE
        }
        showPicker(endPicker, R.string.date_range_ending_date, { endDate ?: today }) { selected ->
            endDate = selected
            updateDateButton(endPicker, R.string.date_range_ending_date, selected)
            validation.visibility = TextView.GONE
        }
        content.addView(startPicker, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        content.addView(endPicker, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        content.addView(validation, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val dialog = host.getAlertDialogBuilder()
            .setTitle(R.string.select_by_date_range)
            .setView(content)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .setPositiveButton(R.string.select_files, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val start = startDate
                val end = endDate
                if (start == null || end == null) {
                    validation.setText(R.string.date_range_dates_required)
                    validation.visibility = TextView.VISIBLE
                    return@setOnClickListener
                }
                if (start.isAfter(end)) {
                    validation.setText(R.string.date_range_start_after_end)
                    validation.visibility = TextView.VISIBLE
                    return@setOnClickListener
                }

                val range = try {
                    RecentDateRange.from(start, end)
                } catch (_: IllegalArgumentException) {
                    validation.setText(R.string.date_range_start_after_end)
                    validation.visibility = TextView.VISIBLE
                    return@setOnClickListener
                }
                dialog.dismiss()
                selectDisplayedFilesInRange(range)
            }
        }
        dialog.show()
    }

    private fun selectDisplayedFilesInRange(range: RecentDateRange) {
        val adapter = getRecyclerAdapter() ?: return
        // listItems is the adapter's effective dataset, already limited, classified, hidden-filtered,
        // and (when active) search-filtered. Snapshot it on the UI thread, then check/filter off-thread.
        val displayedSnapshot = adapter.listItems.toList()
        ensureBackgroundThread {
            val matchingPaths = displayedSnapshot.asSequence()
                .filter { !it.isSectionTitle && !it.isGridTypeDivider && range.contains(it.modified) }
                .filter { activity?.getDoesFilePathExist(it.path) == true }
                .map { it.path }
                .toSet()

            activity?.runOnUiThread {
                val selectedCount = getRecyclerAdapter()?.selectDisplayedItems(matchingPaths) ?: 0
                if (selectedCount == 0) {
                    activity?.toast(R.string.no_files_in_selected_date_range)
                }
            }
        }
    }

    override fun toggleFilenameVisibility() {
        getRecyclerAdapter()?.updateDisplayFilenamesInGrid()
    }

    private fun increaseColumnCount() {
        if (currentViewType == VIEW_TYPE_GRID) {
            context!!.config.fileColumnCnt += 1
            (activity as? MainActivity)?.updateFragmentColumnCounts()
        }
    }

    private fun reduceColumnCount() {
        if (currentViewType == VIEW_TYPE_GRID) {
            context!!.config.fileColumnCnt -= 1
            (activity as? MainActivity)?.updateFragmentColumnCounts()
        }
    }

    override fun columnCountChanged() {
        (binding.recentsList.layoutManager as MyGridLayoutManager).spanCount = context!!.config.fileColumnCnt
        (activity as? MainActivity)?.refreshMenuItems()
        getRecyclerAdapter()?.apply {
            notifyItemRangeChanged(0, listItems.size)
        }
    }

    override fun setupFontSize() {
        getRecyclerAdapter()?.updateFontSizes()
    }

    override fun setupDateTimeFormat() {
        getRecyclerAdapter()?.updateDateTimeFormat()
    }

    override fun selectedPaths(paths: ArrayList<String>) {
        (activity as MainActivity).pickedPaths(paths)
    }

    override fun deleteFiles(files: ArrayList<FileDirItem>) {
        handleFileDeleting(files, false)
    }

    override fun searchQueryChanged(text: String) {
        lastSearchedText = text
        val normalizedText = text.normalizeString()
        val filtered = filesIgnoringSearch.filter {
            it.mName.normalizeString().contains(normalizedText, true)
        }.toMutableList() as ArrayList<ListItem>

        binding.apply {
            (recentsList.adapter as? ItemsAdapter)?.updateItems(filtered, text)
            recentsPlaceholder.beVisibleIf(filtered.isEmpty())
            recentsSwipeRefresh.isEnabled = lastSearchedText.isEmpty() && activity?.config?.enablePullToRefresh != false
        }
    }

    override fun finishActMode() {
        getRecyclerAdapter()?.finishActMode()
    }

}
