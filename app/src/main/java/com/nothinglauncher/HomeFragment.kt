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

        // 1. Check if dropped over a folder in foldersContainer
        val foldersContainer = pageHolder.findViewById<ViewGroup>(R.id.foldersContainer)
        if (foldersContainer != null && foldersContainer.childCount > 0) {
            for (i in 0 until foldersContainer.childCount) {
                val child = foldersContainer.getChildAt(i)
                val loc = IntArray(2)
                child.getLocationOnScreen(loc)
                val left = loc[0].toFloat()
                val top = loc[1].toFloat()
                val right = left + child.width
                val bottom = top + child.height
                if (rawX in left..right && rawY in top..bottom) {
                    val page = viewModel.workspacePages.value?.getOrNull(targetPage) ?: return false
                    val folder = page.folders.getOrNull(i) ?: return false
                    if (fromSource == DragController.DragSource.HOME_PAGE && fromPage != targetPage) {
                        viewModel.removeFromPage(fromPage, app.key)
                    }
                    viewModel.addAppToFolder(targetPage, folder.id, app.key)
                    requireActivity().window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    AppLauncher.toast(requireContext(), R.string.folder_add_apps)
                    return true
                }
            }
        }

        // 2. Check if dropped over homeGrid
        val homeGrid = pageHolder.findViewById<RecyclerView>(R.id.homeGrid) ?: return false
        val gridLoc = IntArray(2)
        homeGrid.getLocationOnScreen(gridLoc)
        val localX = rawX - gridLoc[0]
        val localY = rawY - gridLoc[1]

        val gridAdapter = homeGrid.adapter as? HomeIconAdapter
        val columns = viewModel.prefs.gridColumns.coerceAtLeast(1)

        val child = homeGrid.findChildViewUnder(localX, localY)
        if (child != null && gridAdapter != null) {
            val pos = homeGrid.getChildAdapterPosition(child)
            if (pos != RecyclerView.NO_POSITION) {
                val targetApp = gridAdapter.getItem(pos)
                if (targetApp != null && targetApp.key != app.key) {
                    // Dropped onto an existing app icon -> MERGE INTO FOLDER!
                    if (fromSource == DragController.DragSource.HOME_PAGE) {
                        viewModel.removeFromPage(fromPage, app.key)
                    }
                    viewModel.createFolderFromDrop(targetPage, targetApp.key, app.key)
                    requireActivity().window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    AppLauncher.toast(requireContext(), R.string.drag_folder_created)
                    return true
                } else if (targetApp == null) {
                    // Dropped onto an empty spacer slot
                    if (fromSource == DragController.DragSource.HOME_PAGE) {
                        viewModel.moveAppToPageSlot(app, fromPage, targetPage, pos)
                    } else {
                        viewModel.placeAppAtSlot(targetPage, app, pos)
                    }
                    requireActivity().window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    AppLauncher.toast(requireContext(), R.string.drag_app_pinned)
                    return true
                }
            }
        }

        // 3. If within or below grid bounds, calculate slot based on row/column
        if (localX >= 0 && localX <= homeGrid.width && localY >= 0) {
            val colWidth = (homeGrid.width.toFloat() / columns).coerceAtLeast(1f)
            val col = (localX / colWidth).toInt().coerceIn(0, columns - 1)
            val firstChildHeight = homeGrid.getChildAt(0)?.height?.toFloat()
            val rowHeight = (firstChildHeight ?: (84f * resources.displayMetrics.density)).coerceAtLeast(1f)
            val row = (localY / rowHeight).toInt().coerceAtLeast(0)
            val targetSlot = row * columns + col

            if (fromSource == DragController.DragSource.HOME_PAGE) {
                viewModel.moveAppToPageSlot(app, fromPage, targetPage, targetSlot)
            } else {
                viewModel.placeAppAtSlot(targetPage, app, targetSlot)
            }
            requireActivity().window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            AppLauncher.toast(requireContext(), R.string.drag_app_pinned)
            return true
        }

        return false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
