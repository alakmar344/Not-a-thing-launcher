package com.nothinglauncher

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nothinglauncher.databinding.FragmentAppDrawerBinding
import java.util.Locale

class AppDrawerFragment : Fragment() {

    private var _binding: FragmentAppDrawerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LauncherViewModel by activityViewModels()
    private val host: MainActivity? get() = activity as? MainActivity

    private lateinit var appAdapter: AppAdapter
    private lateinit var layoutManager: GridLayoutManager

    private var allApps: List<AppInfo> = emptyList()
    private var recentApps: List<AppInfo> = emptyList()
    private var query: String = ""
    private var results: List<AppInfo> = emptyList()
    private val letterPositions = LinkedHashMap<Char, Int>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAppDrawerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupInsets()
        setupList()
        setupSearch()
        setupScroller()
        setupGestures()
        binding.settingsButton.setOnClickListener { host?.openSettings() }
        binding.webSearchButton.setOnClickListener { if (query.isNotBlank()) AppLauncher.webSearch(requireContext(), query) }
        observeViewModel()
        applyPrefs()
    }

    private fun setupInsets() {
        val density = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.drawerColumn.updatePadding(top = bars.top)
            val bottom = maxOf(bars.bottom, ime.bottom)
            binding.appsRecyclerView.updatePadding(bottom = bottom + (16 * density).toInt())
            (binding.alphabetScroller.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin =
                bottom + (8 * density).toInt()
            binding.alphabetScroller.requestLayout()
            insets
        }
        ViewCompat.requestApplyInsets(binding.drawerRoot)
    }

    private fun setupList() {
        appAdapter = AppAdapter(
            onAppClick = { app, v -> host?.launchApp(app, v) },
            onAppLongClick = { app, v -> host?.showAppPopup(app, v, MainActivity.PopupOrigin.DRAWER) }
        )
        layoutManager = GridLayoutManager(requireContext(), 4).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (appAdapter.getItemViewType(position) == AppAdapter.TYPE_HEADER) spanCount else 1
            }
        }
        binding.appsRecyclerView.layoutManager = layoutManager
        binding.appsRecyclerView.adapter = appAdapter
        binding.appsRecyclerView.itemAnimator = null
        binding.appsRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard()
            }
        })
    }

    private fun setupSearch() {
        binding.searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val newQuery = s?.toString()?.trim().orEmpty()
                binding.clearSearch.isVisible = !s.isNullOrEmpty()
                if (newQuery == query) return
                query = newQuery
                rebuild()
                binding.appsRecyclerView.scrollToPosition(0)
            }
        })
        binding.searchBar.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val first = results.firstOrNull()
                when {
                    query.isBlank() -> hideKeyboard()
                    first != null -> host?.launchApp(first, v)
                    else -> AppLauncher.webSearch(requireContext(), query)
                }
                true
            } else false
        }
        binding.clearSearch.setOnClickListener { binding.searchBar.setText("") }
    }

    private fun setupScroller() {
        binding.letterBubbleText.panel = false
        binding.alphabetScroller.onLetterChanged = { letter, centerY ->
            letterPositions[letter]?.let { layoutManager.scrollToPositionWithOffset(it, 0) }
            binding.letterBubbleText.text = letter.toString()
            val bubble = binding.letterBubble
            bubble.translationY = binding.alphabetScroller.top + centerY - bubble.height / 2f
        }
        binding.alphabetScroller.onTouchingChanged = { touching ->
            hideKeyboard()
            binding.letterBubble.animate()
                .alpha(if (touching) 1f else 0f)
                .scaleX(if (touching) 1f else 0.8f)
                .scaleY(if (touching) 1f else 0.8f)
                .setDuration(140)
                .start()
        }
    }

    private fun setupGestures() {
        binding.drawerRoot.listener = object : SwipeGestureLayout.Listener {
            override fun canStartDrag(direction: Int, downX: Float, downY: Float): Boolean {
                if (direction != SwipeGestureLayout.DOWN) return false
                if (overScroller(downX, downY)) return false
                // Only close when the list is already at the top (otherwise it just scrolls).
                val listTop = IntArray(2).also { binding.appsRecyclerView.getLocationInWindow(it) }[1]
                val rootTop = IntArray(2).also { binding.drawerRoot.getLocationInWindow(it) }[1]
                val touchingList = downY + rootTop >= listTop
                return !touchingList || !binding.appsRecyclerView.canScrollVertically(-1)
            }

            override fun onDragStart(direction: Int) {
                hideKeyboard()
                host?.beginDrawerDrag()
            }

            override fun onDrag(direction: Int, totalDy: Float) {
                host?.updateDrawerDrag(totalDy)
            }

            override fun onDragEnd(direction: Int, totalDy: Float, velocityY: Float) {
                host?.endDrawerDrag(velocityY)
            }
        }
    }

    private fun overScroller(x: Float, y: Float): Boolean {
        val s = binding.alphabetScroller
        if (!s.isVisible) return false
        val loc = IntArray(2)
        s.getLocationInWindow(loc)
        val rootLoc = IntArray(2)
        binding.drawerRoot.getLocationInWindow(rootLoc)
        val left = loc[0] - rootLoc[0]
        val top = loc[1] - rootLoc[1]
        return x >= left && x <= left + s.width && y >= top && y <= top + s.height
    }

    private fun observeViewModel() {
        viewModel.allApps.observe(viewLifecycleOwner) { apps ->
            allApps = apps
            rebuild()
        }
        viewModel.recentApps.observe(viewLifecycleOwner) { apps ->
            recentApps = apps
            if (query.isBlank()) rebuild()
        }
    }

    fun applyPrefs() {
        if (_binding == null) return
        val prefs = viewModel.prefs
        val columns = prefs.gridColumns
        layoutManager.spanCount = columns
        val accent = prefs.accentColor()
        appAdapter.accentColor = accent
        appAdapter.iconSizePx = resources.getDimensionPixelSize(
            if (columns >= 5) R.dimen.icon_size_compact else R.dimen.icon_size
        )
        binding.alphabetScroller.accentColor = accent
        rebuild(force = true)
    }

    private fun rebuild(force: Boolean = false) {
        if (_binding == null) return
        val ctx = requireContext()
        val items = ArrayList<DrawerItem>(allApps.size + 12)
        letterPositions.clear()
        if (query.isBlank()) {
            results = emptyList()
            val columns = layoutManager.spanCount
            if (viewModel.prefs.showRecents && recentApps.isNotEmpty()) {
                items += DrawerItem.Header("recent", ctx.getString(R.string.recent))
                recentApps.take(columns).forEach { items += DrawerItem.App(it, "recent") }
            }
            items += DrawerItem.Header("all", ctx.getString(R.string.all_apps), String.format(Locale.US, "%03d", allApps.size))
            allApps.forEach { app ->
                val letter = app.sectionLetter
                if (letter !in letterPositions) letterPositions[letter] = items.size
                items += DrawerItem.App(app, "all")
            }
        } else {
            results = rank(query)
            results.forEach { items += DrawerItem.App(it, "search") }
        }
        if (force) appAdapter.submitList(null)
        appAdapter.submitList(items)

        binding.appCount.text = String.format(Locale.US, "%03d", allApps.size)
        val showScroller = query.isBlank() && letterPositions.size > 1
        binding.alphabetScroller.isVisible = showScroller
        binding.alphabetScroller.letters = if (showScroller) letterPositions.keys.toList() else emptyList()
        val noMatch = query.isNotBlank() && results.isEmpty()
        binding.emptyState.isVisible = noMatch
        binding.webSearchButton.text = ctx.getString(R.string.search_web_for, query)
    }

    /** Prefix matches first, then word-start matches, then substring / package matches. */
    private fun rank(q: String): List<AppInfo> {
        val needle = q.lowercase(Locale.getDefault())
        return allApps.mapNotNull { app ->
            val label = app.label.lowercase(Locale.getDefault())
            val score = when {
                label.startsWith(needle) -> 0
                label.split(' ', '-', '.', '_').any { it.startsWith(needle) } -> 1
                label.contains(needle) -> 2
                initials(label).startsWith(needle) && needle.length >= 2 -> 3
                app.packageName.lowercase(Locale.US).contains(needle) -> 4
                else -> -1
            }
            if (score < 0) null else score to app
        }.sortedWith(compareBy({ it.first }, { it.second.label.lowercase(Locale.getDefault()) })).map { it.second }
    }

    private fun initials(label: String): String =
        label.split(' ', '-', '_').filter { it.isNotEmpty() }.joinToString("") { it.first().toString() }

    // ---- Called by MainActivity ---------------------------------------------------------

    fun onDrawerOpening(focusSearch: Boolean) {
        if (_binding == null) return
        if (focusSearch) showKeyboard()
    }

    fun onDrawerClosed() {
        if (_binding == null) return
        hideKeyboard()
        binding.searchBar.clearFocus()
        if (binding.searchBar.text.isNotEmpty()) binding.searchBar.setText("")
        binding.appsRecyclerView.stopScroll()
        layoutManager.scrollToPositionWithOffset(0, 0)
        binding.letterBubble.alpha = 0f
    }

    fun showKeyboard() {
        val field = binding.searchBar
        field.requestFocus()
        field.post {
            val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun hideKeyboard() {
        val b = _binding ?: return
        val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(b.searchBar.windowToken, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
