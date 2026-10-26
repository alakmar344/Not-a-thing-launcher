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

    /**
     * The page the user is on (or being scrolled to). Tracked explicitly so a drop right after
     * an edge flip lands on the new page even while the snap animation is still running.
     */
    var currentPageIndex: Int = 0
        private set

    private fun setCurrentPage(index: Int) {
        val count = workspaceAdapter.itemCount
        val idx = if (count == 0) 0 else index.coerceIn(0, count - 1)
        currentPageIndex = idx
        viewModel.prefs.activePageIndex = idx
        if (_binding != null) binding.pageIndicator.currentPage = idx
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
                    val snapView = snapHelper.findSnapView(workspaceLayoutManager) ?: return
                    val idx = workspaceLayoutManager.getPosition(snapView)
                    if (idx != RecyclerView.NO_POSITION) setCurrentPage(idx)
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (recyclerView.scrollState != RecyclerView.SCROLL_STATE_DRAGGING) return
                val snapView = snapHelper.findSnapView(workspaceLayoutManager) ?: return
                val idx = workspaceLayoutManager.getPosition(snapView)
                if (idx != RecyclerView.NO_POSITION && idx != currentPageIndex) setCurrentPage(idx)
            }
        })

        binding.pageIndicator.onPageSelected = { page -> scrollToPage(page) }
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
        // Double-tap to sleep on empty space; observed for every touch on home so it also works
        // over the page grid (whose cells consume taps).
        val gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (viewModel.prefs.doubleTapToSleep && host?.dragController?.isDragging != true &&
                    isEmptyWorkspaceAt(e.rawX, e.rawY)
                ) {
                    host?.lockScreen()
                    return true
                }
                return false
            }
        })
        binding.homeRoot.touchObserver = { event -> gestureDetector.onTouchEvent(event) }

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
        var firstLayout = true
        viewModel.workspacePages.observe(viewLifecycleOwner) { pages ->
            workspaceAdapter.setPages(pages)
            binding.pageIndicator.pageCount = pages.size
            val wanted = if (firstLayout) viewModel.prefs.activePageIndex else currentPageIndex
            firstLayout = false
            val active = wanted.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            setCurrentPage(active)
            binding.workspaceRecycler.post {
                if (_binding == null) return@post
                val snap = snapHelper.findSnapView(workspaceLayoutManager)
                val shown = snap?.let { workspaceLayoutManager.getPosition(it) } ?: RecyclerView.NO_POSITION
                if (shown != active && binding.workspaceRecycler.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
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

    /** Home button / back on home: return to the first page, scrolled to the top (stock). */
    fun scrollToTop() {
        if (_binding == null) return
        scrollToPage(0)
        for (i in 0 until workspaceLayoutManager.childCount) {
            (workspaceLayoutManager.getChildAt(i)?.findViewById<View>(R.id.pageScroll) as? androidx.core.widget.NestedScrollView)
                ?.smoothScrollTo(0, 0)
        }
    }

    fun scrollToPage(index: Int) {
        if (_binding == null) return
        binding.workspaceRecycler.smoothScrollToPosition(index)
        setCurrentPage(index)
    }

    /** True when ([rawX], [rawY]) is over empty workspace (not over an icon or widget). */
    fun isEmptyWorkspaceAt(rawX: Float, rawY: Float): Boolean {
        if (_binding == null) return false
        val holder = workspaceLayoutManager.findViewByPosition(currentPageIndex) ?: return false
        val pageScroll = holder.findViewById<View>(R.id.pageScroll) ?: return false
        val loc = IntArray(2)
        pageScroll.getLocationOnScreen(loc)
        val insidePage = rawX >= loc[0] && rawX <= loc[0] + pageScroll.width &&
            rawY >= loc[1] && rawY <= loc[1] + pageScroll.height
        if (!insidePage) return false
        val cellLayout = cellLayoutFor(currentPageIndex) ?: return false
        if (!cellLayout.containsRawPoint(rawX, rawY)) return true
        val cell = cellLayout.pointToCell(rawX, rawY) ?: return true
        val page = viewModel.workspacePages.value?.getOrNull(currentPageIndex) ?: return true
        return page.itemAt(cell.first, cell.second) == null
    }

    // ---- Workspace drag & drop ------------------------------------------------------------

    /** What dropping the dragged item at the current point would do. */
    sealed class DropPlan {
        abstract val page: Int
        data class Place(override val page: Int, val col: Int, val row: Int, val spanX: Int, val spanY: Int) : DropPlan()
        data class CreateFolder(override val page: Int, val target: WorkspaceItem) : DropPlan()
        data class AddToFolder(override val page: Int, val target: WorkspaceItem) : DropPlan()
    }

    private fun cellLayoutFor(page: Int): CellLayout? {
        if (_binding == null) return null
        val holder = workspaceLayoutManager.findViewByPosition(page) ?: return null
        return holder.findViewById(R.id.cellLayout)
    }

    private fun spanOf(item: DragController.DragItem): Pair<Int, Int> {
        val cols = viewModel.prefs.gridColumns
        val wsItem = item.itemId?.let { viewModel.itemById(item.pageIndex, it) }
        return when {
            wsItem != null -> wsItem.spanX.coerceIn(1, cols) to wsItem.spanY
            item is DragController.DragItem.MaxApp -> 2.coerceAtMost(cols) to 2.coerceAtMost(cols)
            item is DragController.DragItem.Bespoke -> cols to 1
            else -> 1 to 1
        }
    }

    fun planDrop(item: DragController.DragItem, centerX: Float, centerY: Float, rawX: Float, rawY: Float): DropPlan? {
        val pageIdx = currentPageIndex
        val cellLayout = cellLayoutFor(pageIdx) ?: return null
        val page = viewModel.workspacePages.value?.getOrNull(pageIdx) ?: return null
        val cols = viewModel.prefs.gridColumns
        val ignoreId = if (item.pageIndex == pageIdx) item.itemId else null
        val (sx, sy) = spanOf(item)

        // Single icons can be merged into folders when released over the middle of another icon.
        if (item is DragController.DragItem.App) {
            val cell = cellLayout.pointToCell(rawX, rawY)
            if (cell != null) {
                val target = page.itemAt(cell.first, cell.second, ignoreId)
                if (target != null && isNearCentre(cellLayout, target, rawX, rawY)) {
                    when (target.type) {
                        WorkspaceItem.Type.APP, WorkspaceItem.Type.MAX_APP ->
                            if (target.appKey != item.app.key) return DropPlan.CreateFolder(pageIdx, target)
                        WorkspaceItem.Type.FOLDER ->
                            if (item.app.key !in target.folder?.appKeys.orEmpty()) return DropPlan.AddToFolder(pageIdx, target)
                        else -> Unit
                    }
                }
            }
        }

        val (col, row) = if (sx == 1 && sy == 1) {
            cellLayout.pointToCell(rawX, rawY) ?: cellLayout.cellForDragCenter(centerX, centerY, 1, 1)
        } else {
            cellLayout.cellForDragCenter(centerX, centerY, sx, sy)
        }
        val targetCol = if (item is DragController.DragItem.Bespoke) 0 else col
        val (c, r) = GridMath.nearestFree(page.items, cols, targetCol, row, sx, sy, ignoreId)
        return DropPlan.Place(pageIdx, c, r, sx, sy)
    }

    private fun isNearCentre(cellLayout: CellLayout, target: WorkspaceItem, rawX: Float, rawY: Float): Boolean {
        val loc = IntArray(2)
        cellLayout.getLocationOnScreen(loc)
        val stepX = cellLayout.cellWidth + cellLayout.cellGapX
        val stepY = cellLayout.cellHeight + cellLayout.cellGapY
        val left = loc[0] + cellLayout.paddingStart + target.cellX * stepX
        val top = loc[1] + cellLayout.paddingTop + target.cellY * stepY
        val w = target.spanX * cellLayout.cellWidth + (target.spanX - 1) * cellLayout.cellGapX
        val h = target.spanY * cellLayout.cellHeight + (target.spanY - 1) * cellLayout.cellGapY
        val cx = left + w / 2f
        val cy = top + h / 2f
        return kotlin.math.abs(rawX - cx) < w * 0.36f && kotlin.math.abs(rawY - cy) < h * 0.36f
    }

    fun updateDropHover(item: DragController.DragItem, centerX: Float, centerY: Float, rawX: Float, rawY: Float) {
        val cellLayout = cellLayoutFor(currentPageIndex) ?: return
        when (val plan = planDrop(item, centerX, centerY, rawX, rawY)) {
            is DropPlan.CreateFolder -> cellLayout.setDropTarget(plan.target.cellX, plan.target.cellY, plan.target.spanX, plan.target.spanY, isFolderMerge = true)
            is DropPlan.AddToFolder -> cellLayout.setDropTarget(plan.target.cellX, plan.target.cellY, plan.target.spanX, plan.target.spanY, isFolderMerge = true)
            is DropPlan.Place -> cellLayout.setDropTarget(plan.col, plan.row, plan.spanX, plan.spanY, isFolderMerge = false)
            null -> cellLayout.clearDropTarget()
        }
        autoScrollPage(rawY)
    }

    /** Scrolls the current page vertically while an item is held near its top/bottom edge. */
    private fun autoScrollPage(rawY: Float) {
        if (_binding == null) return
        val holder = workspaceLayoutManager.findViewByPosition(currentPageIndex) ?: return
        val scroll = holder.findViewById<View>(R.id.pageScroll) ?: return
        val loc = IntArray(2)
        scroll.getLocationOnScreen(loc)
        val zone = 56 * resources.displayMetrics.density
        val step = (12 * resources.displayMetrics.density).toInt()
        when {
            rawY < loc[1] + zone && scroll.canScrollVertically(-1) -> scroll.scrollBy(0, -step)
            rawY > loc[1] + scroll.height - zone && rawY < loc[1] + scroll.height && scroll.canScrollVertically(1) -> scroll.scrollBy(0, step)
        }
    }

    fun clearWorkspaceDropTarget() {
        if (_binding == null) return
        for (i in 0 until workspaceLayoutManager.childCount) {
            workspaceLayoutManager.getChildAt(i)?.findViewById<CellLayout>(R.id.cellLayout)?.clearDropTarget()
        }
    }

    /** Executes a drop on the visible page. Returns false when nothing could be dropped. */
    fun handleWorkspaceDrop(item: DragController.DragItem, centerX: Float, centerY: Float, rawX: Float, rawY: Float): Boolean {
        val plan = planDrop(item, centerX, centerY, rawX, rawY) ?: return false
        clearWorkspaceDropTarget()
        val fromDock = item is DragController.DragItem.App && item.fromSource == DragController.DragSource.DOCK
        when (plan) {
            is DropPlan.CreateFolder -> {
                val app = (item as DragController.DragItem.App).app
                viewModel.createFolderFromDrop(plan.page, plan.target.appKey, app.key) ?: return false
                if (fromDock) viewModel.removeFromDock(app)
            }
            is DropPlan.AddToFolder -> {
                val app = (item as DragController.DragItem.App).app
                val folderId = plan.target.folder?.id ?: return false
                viewModel.addAppToFolder(plan.page, folderId, app.key)
                if (fromDock) viewModel.removeFromDock(app)
            }
            is DropPlan.Place -> {
                val id = item.itemId
                if (id != null && viewModel.itemById(item.pageIndex, id) != null) {
                    viewModel.moveItem(item.pageIndex, id, plan.page, plan.col, plan.row)
                } else {
                    when (item) {
                        is DragController.DragItem.App -> {
                            viewModel.placeApp(item.app, plan.page, plan.col, plan.row)
                            if (fromDock) viewModel.removeFromDock(item.app)
                        }
                        is DragController.DragItem.MaxApp -> viewModel.placeApp(item.app, plan.page, plan.col, plan.row)
                        else -> return false
                    }
                }
            }
        }
        return true
    }

    override fun onDestroyView() {
        if (::workspaceAdapter.isInitialized) workspaceAdapter.unbindAll()
        super.onDestroyView()
        _binding = null
    }
}
