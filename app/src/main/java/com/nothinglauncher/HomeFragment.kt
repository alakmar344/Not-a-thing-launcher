package com.nothinglauncher

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nothinglauncher.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LauncherViewModel by activityViewModels()

    private lateinit var homeIconAdapter: HomeIconAdapter
    private lateinit var dockAdapter: DockAdapter
    private lateinit var gestureHandler: GestureHandler
    private var isEditMode = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupHomeGrid()
        setupDock()
        setupGestures()
        observeViewModel()
    }

    private fun setupHomeGrid() {
        homeIconAdapter = HomeIconAdapter(
            onAppClick = { appInfo -> launchApp(appInfo) },
            onAppLongClick = { _, _ ->
                enterEditMode()
                true
            }
        )

        binding.homeGrid.apply {
            layoutManager = GridLayoutManager(requireContext(), 4)
            adapter = homeIconAdapter
        }

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                    ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                homeIconAdapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewModel.saveHomeLayout(homeIconAdapter.getItems())
            }

            override fun isLongPressDragEnabled(): Boolean = isEditMode
        })
        itemTouchHelper.attachToRecyclerView(binding.homeGrid)
    }

    private fun setupDock() {
        dockAdapter = DockAdapter { appInfo -> launchApp(appInfo) }
        binding.dock.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = dockAdapter
        }
    }

    private fun setupGestures() {
        gestureHandler = GestureHandler(
            context = requireContext(),
            onSwipeUp = { openAppDrawer() },
            onSwipeDown = { gestureHandler.expandNotificationShade() },
            onDoubleTap = {}
        )

        binding.gestureOverlay.setOnTouchListener { _, event ->
            gestureHandler.onTouchEvent(event)
            false
        }
    }

    private fun observeViewModel() {
        viewModel.homeApps.observe(viewLifecycleOwner) { apps ->
            homeIconAdapter.setItems(apps)
        }
        viewModel.dockApps.observe(viewLifecycleOwner) { apps ->
            dockAdapter.submitList(apps)
        }
    }

    private fun enterEditMode() {
        isEditMode = true
    }

    fun exitEditMode() {
        isEditMode = false
    }

    private fun openAppDrawer() {
        (activity as? MainActivity)?.openAppDrawer()
    }

    private fun launchApp(appInfo: AppInfo) {
        try {
            val intent = requireContext().packageManager.getLaunchIntentForPackage(appInfo.packageName)
            intent?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
