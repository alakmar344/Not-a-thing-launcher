package com.nothinglauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.nothinglauncher.databinding.ActivityMainBinding

/**
 * Launcher-wide drag & drop, modelled on Launcher3's DragController / DragLayer:
 *
 *  - the dragged item is rendered as a bitmap snapshot that stays under the finger at the
 *    exact point where it was picked up, while the original is hidden in place;
 *  - the workspace shows where the item will land (span-aware outline, folder-merge ring);
 *  - holding the item at a screen edge flips pages (and creates a new page past the last one,
 *    which is removed again if nothing is dropped on it);
 *  - the top bar offers Remove / Uninstall / App info targets; the dock accepts apps.
 */
class DragController(private val activity: MainActivity, private val binding: ActivityMainBinding) {

    sealed class DragItem {
        abstract val pageIndex: Int
        /** Id of the [WorkspaceItem] being moved, when the drag started on the workspace. */
        abstract val itemId: String?

        data class App(
            val app: AppInfo,
            val fromSource: DragSource,
            override val pageIndex: Int = 0,
            override val itemId: String? = null
        ) : DragItem()

        data class MaxApp(val app: AppInfo, override val pageIndex: Int, override val itemId: String?) : DragItem()
        data class Folder(val folder: WorkspaceFolder, override val pageIndex: Int, override val itemId: String?) : DragItem()
        data class Bespoke(val element: BespokeElement, override val pageIndex: Int, override val itemId: String?) : DragItem()
        data class Widget(val widgetId: Int, override val pageIndex: Int, override val itemId: String?) : DragItem()

        val appOrNull: AppInfo?
            get() = when (this) {
                is App -> app
                is MaxApp -> app
                else -> null
            }
    }

    enum class DragSource { DRAWER, HOME_PAGE, DOCK, FOLDER }

    var isDragging: Boolean = false
        private set

    /** Set when a drag starts; MainActivity cancels the view hierarchy's gesture once. */
    var needsHierarchyCancel: Boolean = false

    private var currentItem: DragItem? = null
    private var dragView: View? = null
    private var hiddenSource: View? = null
    private var touchOffsetX = 0f
    private var touchOffsetY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var firstCreatedPage = -1

    private val density = activity.resources.displayMetrics.density
    private val edgeWidth = 36f * density
    private val handler = Handler(Looper.getMainLooper())
    private var edgeDirection = 0
    private val easeInterpolator = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

    private val dropBar: View get() = binding.dropZoneInclude.dropZoneBar
    private val zoneRemove: View get() = binding.dropZoneInclude.dropZoneRemove
    private val zoneUninstall: View get() = binding.dropZoneInclude.dropZoneUninstall
    private val zoneInfo: View get() = binding.dropZoneInclude.dropZoneInfo

    private val edgeFlip = object : Runnable {
        override fun run() {
            if (!isDragging || edgeDirection == 0) return
            val created = activity.performPageFlip(edgeDirection)
            if (created >= 0 && firstCreatedPage < 0) firstCreatedPage = created
            activity.window.decorView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            handler.postDelayed(this, EDGE_REPEAT_MS)
        }
    }

    // ---- Start ---------------------------------------------------------------------------

    fun startDrag(item: DragItem, sourceView: View, rawX: Float, rawY: Float) {
        if (isDragging) return
        isDragging = true
        needsHierarchyCancel = true
        currentItem = item
        firstCreatedPage = -1
        lastRawX = rawX
        lastRawY = rawY
        activity.dismissPopup()

        if (item is DragItem.App && item.fromSource == DragSource.DRAWER) activity.closeDrawer()

        showDropZoneBar(item)

        val (view, scale) = createDragView(item, sourceView)
        val loc = IntArray(2)
        sourceView.getLocationOnScreen(loc)
        val inside = rawX in loc[0].toFloat()..(loc[0] + sourceView.width).toFloat() &&
            rawY in loc[1].toFloat()..(loc[1] + sourceView.height).toFloat()
        val w = view.layoutParams.width
        val h = view.layoutParams.height
        if (inside && sourceView.width > 0 && sourceView.height > 0) {
            touchOffsetX = (rawX - loc[0]) * scale
            touchOffsetY = (rawY - loc[1]) * scale
        } else {
            touchOffsetX = w / 2f
            touchOffsetY = h / 2f
        }

        binding.dragOverlay.removeAllViews()
        binding.dragOverlay.addView(view)
        binding.dragOverlay.isVisible = true
        dragView = view
        positionDragView(rawX, rawY)

        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = 0.9f
        view.animate().scaleX(LIFT_SCALE).scaleY(LIFT_SCALE).alpha(1f)
            .setDuration(150).setInterpolator(easeInterpolator).start()

        // Hide the original in place (stock behaviour) unless it lives in the drawer/folder list.
        if (item !is DragItem.App || item.fromSource == DragSource.HOME_PAGE || item.fromSource == DragSource.DOCK) {
            sourceView.visibility = View.INVISIBLE
            hiddenSource = sourceView
        }

        update(rawX, rawY)
    }

    /**
     * Called when the workspace rebinds while dragging: if [view] is the freshly created view of
     * the item being dragged, hide it and track it so it is restored when the drag ends
     * (otherwise the original pops back in place mid-drag, or stays hidden after a cancel).
     */
    fun adoptHiddenSource(pageIndex: Int, itemId: String, view: View) {
        val item = currentItem ?: return
        if (!isDragging || hiddenSource == null || item.itemId != itemId || item.pageIndex != pageIndex) return
        if (hiddenSource !== view) hiddenSource?.visibility = View.VISIBLE
        view.visibility = View.INVISIBLE
        hiddenSource = view
    }

    private fun createDragView(item: DragItem, source: View): Pair<View, Float> {
        val maxW = activity.resources.displayMetrics.widthPixels * 0.92f
        val bitmap = snapshot(source)
        if (bitmap != null) {
            val scale = if (bitmap.width > maxW) maxW / bitmap.width else 1f
            val iv = ImageView(activity).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_XY
                elevation = 12f * density
            }
            iv.layoutParams = FrameLayout.LayoutParams((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt())
            return iv to scale
        }
        // Fallback: a tile with the item's icon.
        val size = (64 * density).toInt()
        val root = FrameLayout(activity).apply {
            setBackgroundResource(R.drawable.bg_tile_dark)
            elevation = 12f * density
            clipToOutline = true
        }
        val icon = ImageView(activity).apply {
            val pad = (10 * density).toInt()
            setPadding(pad, pad, pad, pad)
            when (item) {
                is DragItem.App -> setImageDrawable(item.app.icon)
                is DragItem.MaxApp -> setImageDrawable(item.app.icon)
                is DragItem.Folder -> setImageResource(R.drawable.ic_folder)
                is DragItem.Bespoke -> setImageResource(item.element.iconRes)
                is DragItem.Widget -> setImageResource(R.drawable.ic_widgets)
            }
        }
        root.addView(icon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.layoutParams = FrameLayout.LayoutParams(size, size)
        return root to (if (source.width > 0) size.toFloat() / source.width else 1f)
    }

    private fun snapshot(view: View): Bitmap? {
        if (view.width <= 0 || view.height <= 0) return null
        return try {
            val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val wasPressed = view.isPressed
            view.isPressed = false
            view.draw(Canvas(bmp))
            view.isPressed = wasPressed
            bmp
        } catch (e: Throwable) {
            null
        }
    }

    private fun positionDragView(rawX: Float, rawY: Float) {
        val v = dragView ?: return
        val overlayLoc = IntArray(2)
        binding.dragOverlay.getLocationOnScreen(overlayLoc)
        v.pivotX = touchOffsetX
        v.pivotY = touchOffsetY
        v.x = rawX - touchOffsetX - overlayLoc[0]
        v.y = rawY - touchOffsetY - overlayLoc[1]
    }

    /** Screen-space centre of the floating preview (used for span-aware placement). */
    private fun dragCenter(rawX: Float, rawY: Float): Pair<Float, Float> {
        val v = dragView ?: return rawX to rawY
        val w = v.layoutParams?.width?.toFloat() ?: 0f
        val h = v.layoutParams?.height?.toFloat() ?: 0f
        return (rawX - touchOffsetX + w / 2f) to (rawY - touchOffsetY + h / 2f)
    }

    // ---- Drop bar ----------------------------------------------------------------------------

    private fun showDropZoneBar(item: DragItem) {
        dropBar.animate().cancel()
        dropBar.visibility = View.VISIBLE
        dropBar.translationY = -80f * density
        dropBar.alpha = 0f
        val app = item.appOrNull
        zoneUninstall.isVisible = app != null && !app.isSystemApp
        zoneInfo.isVisible = app != null
        val fromDrawer = item is DragItem.App && item.fromSource == DragSource.DRAWER
        binding.dropZoneInclude.textRemove.text = activity.getString(
            if (fromDrawer) R.string.cancel else R.string.drag_target_remove
        )
        resetDropZoneHighlights()
        dropBar.animate().translationY(0f).alpha(1f).setDuration(200).setInterpolator(easeInterpolator).start()
        // Spring-loaded workspace (stock): move the grid below the drop targets so the first
        // row stays reachable while dragging.
        val lp = dropBar.layoutParams as? ViewGroup.MarginLayoutParams
        val barBottom = (lp?.topMargin ?: 0) + dropBar.height.coerceAtLeast((64 * density).toInt())
        activity.setSpringLoaded(true, barBottom + (8 * density).toInt())
    }

    private fun hideDropZoneBar() {
        activity.setSpringLoaded(false, 0)
        dropBar.animate().translationY(-80f * density).alpha(0f).setDuration(160)
            .setInterpolator(easeInterpolator)
            .withEndAction {
                if (!isDragging) {
                    dropBar.visibility = View.GONE
                    resetDropZoneHighlights()
                }
            }
            .start()
    }

    private enum class Zone { NONE, REMOVE, UNINSTALL, INFO }

    private fun zoneAt(rawX: Float, rawY: Float): Zone {
        if (!dropBar.isVisible) return Zone.NONE
        val loc = IntArray(2)
        dropBar.getLocationOnScreen(loc)
        if (rawY < loc[1] || rawY > loc[1] + dropBar.height) return Zone.NONE
        fun over(v: View): Boolean {
            if (!v.isVisible) return false
            v.getLocationOnScreen(loc)
            return rawX >= loc[0] && rawX <= loc[0] + v.width
        }
        return when {
            over(zoneRemove) -> Zone.REMOVE
            over(zoneUninstall) -> Zone.UNINSTALL
            over(zoneInfo) -> Zone.INFO
            else -> Zone.NONE
        }
    }

    private fun highlightZones(zone: Zone) {
        highlightZone(zoneRemove, zone == Zone.REMOVE, true)
        highlightZone(zoneUninstall, zone == Zone.UNINSTALL, true)
        highlightZone(zoneInfo, zone == Zone.INFO, false)
    }

    private fun highlightZone(view: View, highlighted: Boolean, destructive: Boolean) {
        if (highlighted) {
            val tint = ContextCompat.getColor(activity, if (destructive) R.color.nothing_red else R.color.nothing_white)
            view.setBackgroundColor(Color.argb(45, Color.red(tint), Color.green(tint), Color.blue(tint)))
        } else {
            view.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun resetDropZoneHighlights() = highlightZones(Zone.NONE)

    // ---- Move / drop -----------------------------------------------------------------------

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDragging) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> update(event.rawX, event.rawY)
            MotionEvent.ACTION_UP -> completeDrop(event.rawX, event.rawY)
            MotionEvent.ACTION_CANCEL -> finishDrag(dropped = false)
        }
        return true
    }

    private fun update(rawX: Float, rawY: Float) {
        lastRawX = rawX
        lastRawY = rawY
        positionDragView(rawX, rawY)
        val item = currentItem ?: return
        val zone = zoneAt(rawX, rawY)
        highlightZones(zone)
        if (zone != Zone.NONE) {
            activity.clearDockDropTarget()
            activity.clearWorkspaceDropTarget()
            setEdge(0)
            return
        }
        if (item.appOrNull != null && activity.isPointOverDock(rawX, rawY)) {
            activity.showDockDropTarget(rawX)
            activity.clearWorkspaceDropTarget()
            setEdge(0)
            return
        }
        activity.clearDockDropTarget()
        val (cx, cy) = dragCenter(rawX, rawY)
        activity.updateWorkspaceDropHover(item, cx, cy, rawX, rawY)

        val screenWidth = activity.resources.displayMetrics.widthPixels
        // In RTL the pager is mirrored: the previous page sits on the right.
        val rtl = binding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val edge = when {
            rawX < edgeWidth -> -1
            rawX > screenWidth - edgeWidth -> 1
            else -> 0
        }
        setEdge(if (rtl) -edge else edge)
    }

    private fun setEdge(direction: Int) {
        if (direction == edgeDirection) return
        edgeDirection = direction
        handler.removeCallbacks(edgeFlip)
        if (direction != 0) handler.postDelayed(edgeFlip, EDGE_DWELL_MS)
    }

    private fun completeDrop(rawX: Float, rawY: Float) {
        val item = currentItem ?: return finishDrag(dropped = false)
        setEdge(0)
        var dropped = true
        when (zoneAt(rawX, rawY)) {
            Zone.REMOVE -> handleDropRemove(item)
            Zone.UNINSTALL -> item.appOrNull?.let { if (!it.isSystemApp) AppLauncher.uninstall(activity, it) }
            Zone.INFO -> item.appOrNull?.let { AppLauncher.openAppInfo(activity, it, activity.window.decorView) }
            Zone.NONE -> {
                val app = item.appOrNull
                dropped = if (app != null && activity.isPointOverDock(rawX, rawY)) {
                    handleDropOnDock(item, app, rawX)
                } else {
                    val (cx, cy) = dragCenter(rawX, rawY)
                    activity.handleWorkspaceDrop(item, cx, cy, rawX, rawY)
                }
            }
        }
        finishDrag(dropped)
    }

    private fun handleDropRemove(item: DragItem) {
        val vm = activity.viewModel
        when (item) {
            is DragItem.App -> when (item.fromSource) {
                DragSource.DRAWER -> Unit
                DragSource.DOCK -> vm.removeFromDock(item.app)
                DragSource.FOLDER, DragSource.HOME_PAGE -> vm.removeFromHome(item.app)
            }
            is DragItem.MaxApp -> vm.removeFromHome(item.app)
            is DragItem.Folder -> item.itemId?.let { vm.removeItem(item.pageIndex, it) }
            is DragItem.Bespoke -> vm.removeElementFromPage(item.pageIndex, item.element.id)
            is DragItem.Widget -> activity.removeWidgetHost(item.widgetId)
        }
    }

    private fun handleDropOnDock(item: DragItem, app: AppInfo, rawX: Float): Boolean {
        val source = (item as? DragItem.App)?.fromSource ?: DragSource.HOME_PAGE
        val handled = activity.handleDockDrop(app, source, item.pageIndex, rawX)
        if (!handled && activity.viewModel.isDockFull() && !activity.viewModel.isInDock(app)) {
            AppLauncher.toast(activity, R.string.dock_full)
        }
        return handled
    }

    private fun finishDrag(dropped: Boolean) {
        isDragging = false
        needsHierarchyCancel = false
        setEdge(0)
        handler.removeCallbacks(edgeFlip)
        activity.clearDockDropTarget()
        activity.clearWorkspaceDropTarget()
        hideDropZoneBar()

        hiddenSource?.visibility = View.VISIBLE
        hiddenSource = null
        if (firstCreatedPage >= 0) {
            activity.viewModel.removeEmptyPagesFrom(firstCreatedPage)
            firstCreatedPage = -1
        }
        if (dropped) activity.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

        val v = dragView
        dragView = null
        currentItem = null
        if (v == null) {
            binding.dragOverlay.removeAllViews()
            binding.dragOverlay.isVisible = false
            return
        }
        v.animate().cancel()
        v.animate().scaleX(1f).scaleY(1f).alpha(0f).setDuration(140)
            .setInterpolator(easeInterpolator)
            .withEndAction {
                if (!isDragging) {
                    binding.dragOverlay.removeAllViews()
                    binding.dragOverlay.isVisible = false
                }
            }
            .start()
    }

    /** Aborts an in-flight drag (activity paused, home pressed...). */
    fun cancel() {
        if (isDragging) finishDrag(dropped = false)
    }

    private companion object {
        const val LIFT_SCALE = 1.06f
        const val EDGE_DWELL_MS = 500L
        const val EDGE_REPEAT_MS = 900L
    }
}
