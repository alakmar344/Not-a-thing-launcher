package com.nothinglauncher

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.nothinglauncher.databinding.FragmentAppDrawerBinding

class AppDrawerFragment : Fragment() {

    private var _binding: FragmentAppDrawerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LauncherViewModel by activityViewModels()
    private lateinit var appAdapter: AppAdapter
    private var allApps: List<AppInfo> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppDrawerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSearch()
        setupGestures()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        appAdapter = AppAdapter { appInfo -> launchApp(appInfo) }
        binding.appsRecyclerView.apply {
            layoutManager = GridLayoutManager(requireContext(), 4)
            adapter = appAdapter
        }
    }

    private fun setupSearch() {
        binding.searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupGestures() {
        val gestureHandler = GestureHandler(
            context = requireContext(),
            onSwipeUp = {},
            onSwipeDown = { dismissDrawer() },
            onDoubleTap = {}
        )

        binding.root.setOnTouchListener { _, event ->
            gestureHandler.onTouchEvent(event)
            false
        }
    }

    private fun observeViewModel() {
        viewModel.allApps.observe(viewLifecycleOwner) { apps ->
            allApps = apps
            appAdapter.submitList(apps)
        }
    }

    private fun filterApps(query: String) {
        val filtered = if (query.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }
        }
        appAdapter.submitList(filtered)
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

    private fun dismissDrawer() {
        (activity as? MainActivity)?.closeAppDrawer()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
