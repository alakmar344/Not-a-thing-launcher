package com.nothinglauncher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
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

    private fun setupBottomBar() {
        binding.searchPill.setOnClickListener { host?.openDrawer(focusSearch = true) }
        binding.drawerButton.setOnClickListener { host?.openDrawer(focusSearch = false) }
    }

    private fun setupGestures() {
        val density = resources.displayMetrics.density
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
