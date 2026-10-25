package com.nothinglauncher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.nothinglauncher.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LauncherViewModel by activityViewModels()
    private val host: MainActivity? get() = activity as? MainActivity

    private lateinit var workspaceAdapter: WorkspacePageAdapter
    private lateinit var workspaceLayoutManager: LinearLayoutManager
    private val snapHelper = PagerSnapHelper()

    val currentPageIndex: Int
        get() {
            if (_binding == null) return viewModel.prefs.activePageIndex
            val snapView = snapHelper.findSnapView(workspaceLayoutManager)
            return if (snapView != null) workspaceLayoutManager.getPosition(snapView).coerceAtLeast(0) else viewModel.prefs.activePageIndex
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupInsets()
        setupWorkspace()
        setupDock()
        setupBottomBar()
        setupGestures()
        observeViewModel()
        applyPrefs()
    }

    private fun setupInsets() {
        val density = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(binding.homeRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.workspaceRecycler.updatePadding(top = bars.top)
            binding.bottomBar.updatePadding(bottom = bars.bottom + (12 * density).toInt())
            insets
        }
        ViewCompat.requestApplyInsets(binding.homeRoot)
    }

    private fun setupWorkspace() {
        val activity = host ?: return
        workspaceAdapter = WorkspacePageAdapter(activity, viewModel)
        workspaceLayoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.workspaceRecycler.layoutManager = workspaceLayoutManager
        binding.workspaceRecycler.adapter = workspaceAdapter
        snapHelper.attachToRecyclerView(binding.workspaceRecycler)

        binding.workspaceRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val idx = currentPageIndex
                    viewModel.prefs.activePageIndex = idx
                    binding.pageIndicator.currentPage = idx
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val snapView = snapHelper.findSnapView(workspaceLayoutManager)
                if (snapView != null) {
                    val idx = workspaceLayoutManager.getPosition(snapView)
                    if (idx != RecyclerView.NO_POSITION && idx != binding.pageIndicator.currentPage) {
                        binding.pageIndicator.currentPage = idx
                        viewModel.prefs.activePageIndex = idx
                    }
                }
            }
        })

        binding.pageIndicator.onPageSelected = { page ->
            binding.workspaceRecycler.smoothScrollToPosition(page)
            viewModel.prefs.activePageIndex = page
        }
    }

    private fun setupDock() {
        binding.dock.onAppClick = { app, v -> host?.launchApp(app, v) }
        binding.dock.onAppLongClick = { app, v -> host?.showAppPopup(app, v, MainActivity.PopupOrigin.DOCK) }
        binding.dock.onAppDragStart = { app, v, rawX, rawY, _ ->
            host?.startDrag(
                DragController.DragItem.App(app, DragController.DragSource.DOCK, 0),
                v,
                rawX,
                rawY
            )
        }
    }

    fun dockDropIndex(rawX: Float): Int =
        if (_binding == null) 0 else binding.dock.dropIndexAt(rawX)

    fun showDockDropTarget(rawX: Float) {
        if (_binding == null) return
        binding.dock.showDropTarget(binding.dock.dropIndexAt(rawX))
    }

    fun clearDockDropTarget() {
        if (_binding == null) return
        binding.dock.clearDropTarget()
    }

    /** Adds or reorders an app in the dock at the position under [rawX]. */
    fun handleDockDrop(
        app: AppInfo,
        fromSource: DragController.DragSource,
        fromPage: Int,
        rawX: Float
    ): Boolean {
        if (_binding == null) return false
        val dropIndex = binding.dock.dropIndexAt(rawX)
        if (fromSource == DragController.DragSource.DOCK) {
            val fromIndex = viewModel.dockIndexOf(app.key)
            if (fromIndex < 0) return false
            var target = dropIndex
            if (fromIndex < target) target -= 1
            val max = (viewModel.dockApps.value?.size ?: 1) - 1
            return viewModel.moveDockItem(fromIndex, target.coerceIn(0, max))
        }
        val added = viewModel.insertIntoDock(app, dropIndex)
        if (added) {
            if (fromSource == DragController.DragSource.HOME_PAGE ||
                fromSource == DragController.DragSource.FOLDER
            ) {
                viewModel.removeFromHome(app)
            }
            requireActivity().window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            AppLauncher.toast(requireContext(), R.string.add_to_dock)
        }
        return added
    }

    fun isPointOverDock(rawX: Float, rawY: Float): Boolean {
        if (_binding == null || !binding.dock.isVisible) return false
        val loc = IntArray(2)
        binding.dock.getLocationOnScreen(loc)
        val left = loc[0].toFloat()
        val top = loc[1].toFloat()
        val right = left + binding.dock.width
        val bottom = top + binding.dock.height
        return rawX in left..right && rawY in top..bottom
    }

    private fun setupBottomBar() {
        binding.searchPill.setOnClickListener { host?.openDrawer(focusSearch = true) }
        binding.drawerButton.setOnClickListener { host?.openDrawer(focusSearch = false) }
    }

    private fun setupGestures() {
        val density = resources.displayMetrics.density
        val gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (host?.isPopupShowing() != true) {
                    binding.workspaceRecycler.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    host?.showCustomizeSheet()
                }
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (viewModel.prefs.doubleTapToSleep) {
                    host?.lockScreen()
                    return true
                }
                return false
            }
        })

        binding.workspaceRecycler.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }

        binding.homeRoot.listener = object : SwipeGestureLayout.Listener {
            override fun canStartDrag(direction: Int, downX: Float, downY: Float): Boolean {
                if (host?.isPopupShowing() == true) return false
                return if (direction == SwipeGestureLayout.UP) {
                    downY >= binding.bottomBar.top || !canCurrentPageScrollDown()
                } else {
                    !canCurrentPageScrollUp()
                }
            }

            override fun onDragStart(direction: Int) {
                if (direction == SwipeGestureLayout.UP) host?.beginDrawerDrag()
            }

            override fun onDrag(direction: Int, totalDy: Float) {
                if (direction == SwipeGestureLayout.UP) host?.updateDrawerDrag(totalDy)
            }

            override fun onDragEnd(direction: Int, totalDy: Float, velocityY: Float) {
                if (direction == SwipeGestureLayout.UP) {
                    host?.endDrawerDrag(velocityY)
                } else if (totalDy > 56 * density || (totalDy > 16 * density && velocityY > 800)) {
                    host?.performSwipeDown()
                }
            }
        }
    }

    private fun canCurrentPageScrollDown(): Boolean {
        if (_binding == null) return false
        val snapView = snapHelper.findSnapView(workspaceLayoutManager) ?: return false
        val scroll = snapView.findViewById<View>(R.id.pageScroll) ?: return false
        return scroll.canScrollVertically(1)
    }

    private fun canCurrentPageScrollUp(): Boolean {
        if (_binding == null) return false
        val snapView = snapHelper.findSnapView(workspaceLayoutManager) ?: return false
        val scroll = snapView.findViewById<View>(R.id.pageScroll) ?: return false
        return scroll.canScrollVertically(-1)
    }

    private fun observeViewModel() {
        viewModel.workspacePages.observe(viewLifecycleOwner) { pages ->
            workspaceAdapter.setPages(pages)
            binding.pageIndicator.pageCount = pages.size
            val active = viewModel.prefs.activePageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            binding.pageIndicator.currentPage = active
            if (workspaceLayoutManager.findFirstVisibleItemPosition() != active) {
                binding.workspaceRecycler.post {
                    binding.workspaceRecycler.scrollToPosition(active)
                }
            }
        }
        viewModel.dockApps.observe(viewLifecycleOwner) { apps ->
            binding.dock.setApps(apps)
            binding.dock.isVisible = apps.isNotEmpty()
        }
    }

    fun applyPrefs() {
        if (_binding == null) return
        val prefs = viewModel.prefs
        binding.pageIndicator.accentColor = prefs.accentColor()
        binding.dock.showLabels = prefs.showDockLabels
        binding.dock.labelShadow = prefs.background == LauncherPrefs.Background.WALLPAPER
        workspaceAdapter.notifyDataSetChanged()
    }

    fun renderWidgets() {
        if (_binding == null) return
        workspaceAdapter.notifyDataSetChanged()
    }

    fun scrollToTop() {
        if (_binding == null) return
        binding.workspaceRecycler.smoothScrollToPosition(0)
        viewModel.prefs.activePageIndex = 0
        binding.pageIndicator.currentPage = 0
    }

    fun scrollToPage(index: Int) {
        if (_binding == null) return
        binding.workspaceRecycler.smoothScrollToPosition(index)
        viewModel.prefs.activePageIndex = index
        binding.pageIndicator.currentPage = index
    }

    fun updateDropHover(rawX: Float, rawY: Float, isApp: Boolean) {
        if (_binding == null) return
        val pageHolder = workspaceLayoutManager.findViewByPosition(currentPageIndex) ?: return
        val cellLayout = pageHolder.findViewById<CellLayout>(R.id.cellLayout) ?: return
        val cell = cellLayout.pointToCell(rawX, rawY)
        if (cell == null) {
            cellLayout.clearDropTarget()
            return
        }
        val (col, row) = cell
        val page = viewModel.workspacePages.value?.getOrNull(currentPageIndex)
        val existingItem = page?.items?.firstOrNull {
            col in it.cellX until (it.cellX + it.spanX) && row in it.cellY until (it.cellY + it.spanY)
        }
        when {
            existingItem?.type == WorkspaceItem.Type.APP && isApp -> {
                cellLayout.setDropTarget(existingItem.cellX, existingItem.cellY, existingItem.spanX, existingItem.spanY, isFolderMerge = true)
            }
            existingItem?.type == WorkspaceItem.Type.FOLDER && isApp -> {
                cellLayout.setDropTarget(existingItem.cellX, existingItem.cellY, existingItem.spanX, existingItem.spanY, isFolderMerge = false)
            }
            existingItem != null -> {
                cellLayout.clearDropTarget()
            }
            else -> {
                cellLayout.setDropTarget(col, row, 1, 1, isFolderMerge = false)
            }
        }
    }

    fun clearWorkspaceDropTarget() {
        if (_binding == null) return
        val pageHolder = workspaceLayoutManager.findViewByPosition(currentPageIndex) ?: return
        val cellLayout = pageHolder.findViewById<CellLayout>(R.id.cellLayout) ?: return
        cellLayout.clearDropTarget()
    }

    fun handleAppDropOnPage(
        app: AppInfo,
        fromSource: DragController.DragSource,
        fromPage: Int,
        targetPage: Int,
        rawX: Float,
        rawY: Float
    ): Boolean {
        if (_binding == null) return false
        val pageHolder = workspaceLayoutManager.findViewByPosition(targetPage) ?: return false
        val cellLayout = pageHolder.findViewById<CellLayout>(R.id.cellLayout) ?: return false
        cellLayout.clearDropTarget()

        val cell = cellLayout.pointToCell(rawX, rawY) ?: return false
        val (col, row) = cell
        val page = viewModel.workspacePages.value?.getOrNull(targetPage) ?: return false
        val existingItem = page.items.firstOrNull {
            col in it.cellX until (it.cellX + it.spanX) && row in it.cellY until (it.cellY + it.spanY)
        }

        if (existingItem != null) {
            when (existingItem.type) {
                WorkspaceItem.Type.FOLDER -> {
                    val folder = existingItem.folder ?: return false
                    if (fromSource == DragController.DragSource.HOME_PAGE && fromPage != targetPage) {
                        viewModel.removeFromPage(fromPage, app.key)
                    }
                    if (fromSource == DragController.DragSource.DOCK) viewModel.removeFromDock(app)
                    viewModel.addAppToFolder(targetPage, folder.id, app.key)
                    requireActivity().window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    AppLauncher.toast(requireContext(), R.string.folder_add_apps)
                    return true
                }
                WorkspaceItem.Type.APP -> {
                    if (existingItem.appKey != app.key) {
                        // Dropped onto an existing app icon -> MERGE INTO FOLDER!
                        if (fromSource == DragController.DragSource.HOME_PAGE && fromPage != targetPage) {
                            viewModel.removeFromPage(fromPage, app.key)
                        }
                        if (fromSource == DragController.DragSource.DOCK) viewModel.removeFromDock(app)
                        viewModel.createFolderFromDrop(targetPage, existingItem.appKey, app.key, existingItem.cellX, existingItem.cellY)
                        requireActivity().window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        AppLauncher.toast(requireContext(), R.string.drag_folder_created)
                        return true
                    }
                }
                else -> {
                    // Over widget or max app, find nearest free cell
                    val (fc, fr) = viewModel.findFreeCell(page, 1, 1)
                    if (fromSource == DragController.DragSource.DOCK) viewModel.removeFromDock(app)
                    viewModel.placeAppAtCell(targetPage, app, fc, fr)
                    requireActivity().window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    AppLauncher.toast(requireContext(), R.string.drag_app_pinned)
                    return true
                }
            }
        }

        // Empty cell drop
        if (fromSource == DragController.DragSource.HOME_PAGE && fromPage != targetPage) {
            viewModel.removeFromPage(fromPage, app.key)
        }
        if (fromSource == DragController.DragSource.DOCK) viewModel.removeFromDock(app)
        viewModel.placeAppAtCell(targetPage, app, col, row)
        requireActivity().window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        AppLauncher.toast(requireContext(), R.string.drag_app_pinned)
        return true
    }

    override fun onDestroyView() {
        if (::workspaceAdapter.isInitialized) workspaceAdapter.unbindAll()
        super.onDestroyView()
        _binding = null
    }
}
