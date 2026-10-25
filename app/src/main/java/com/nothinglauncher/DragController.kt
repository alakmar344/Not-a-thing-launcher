package com.nothinglauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.nothinglauncher.databinding.ActivityMainBinding

/**
 * Modern Launcher Drag & Drop Controller.
 * Handles dragging apps from Drawer to Screen, moving items across workspace pages & rows,
 * dragging widgets, dropping into folders, docking, and top drop action zones (Remove, Uninstall, Info).
 */
class DragController(private val activity: MainActivity, private val binding: ActivityMainBinding) {

    sealed class DragItem {
        data class App(val app: AppInfo, val fromSource: DragSource, val pageIndex: Int = 0) : DragItem()
        data class MaxApp(val app: AppInfo, val pageIndex: Int) : DragItem()
        data class Folder(val folder: WorkspaceFolder, val pageIndex: Int) : DragItem()
        data class Bespoke(val element: BespokeElement, val pageIndex: Int) : DragItem()
        data class Widget(val widgetId: Int, val pageIndex: Int) : DragItem()
    }

    enum class DragSource {
        DRAWER, HOME_PAGE, DOCK, FOLDER
    }

    enum class DropTargetType {
        NONE, TOP_REMOVE, TOP_UNINSTALL, TOP_INFO, DOCK, FOLDER_MERGE, FOLDER_ADD, WORKSPACE_PAGE
    }

    var isDragging: Boolean = false
        private set

    private var currentItem: DragItem? = null
    private var dragFloatingView: View? = null
    private var touchOffsetX: Float = 0f
    private var touchOffsetY: Float = 0f

    private val density = activity.resources.displayMetrics.density
    private val edgePagingWidth = 52f * density
    private var edgePagingCooldown = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val springInterpolator = OvershootInterpolator(1.15f)
    private val easeInterpolator = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

    private val dropBar: View get() = binding.dropZoneInclude.dropZoneBar
    private val zoneRemove: View get() = binding.dropZoneInclude.dropZoneRemove
    private val zoneUninstall: View get() = binding.dropZoneInclude.dropZoneUninstall
    private val zoneInfo: View get() = binding.dropZoneInclude.dropZoneInfo

    fun startDrag(
        item: DragItem,
        sourceView: View,
        initialRawX: Float,
        initialRawY: Float
    ) {
        if (isDragging) return
        isDragging = true
        currentItem = item
        activity.dismissPopup()

        // Haptic feedback
        sourceView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        // If dragging from drawer, collapse drawer smoothly to reveal workspace
        if (item is DragItem.App && item.fromSource == DragSource.DRAWER) {
            activity.closeDrawer()
        }

        // Show drop zone bar at top
        showDropZoneBar(item)

        // Build floating drag view
        val floating = createFloatingView(item)
        val viewSize = (64 * density).toInt()
        touchOffsetX = viewSize / 2f
        touchOffsetY = viewSize / 2f

        val lp = FrameLayout.LayoutParams(viewSize, viewSize)
        floating.layoutParams = lp
        floating.x = initialRawX - touchOffsetX
        floating.y = initialRawY - touchOffsetY
        floating.scaleX = 0.8f
        floating.scaleY = 0.8f
        floating.alpha = 0f

        binding.dragOverlay.removeAllViews()
        binding.dragOverlay.addView(floating)
        binding.dragOverlay.isVisible = true
        dragFloatingView = floating

        // Animate floating view scale & elevation
        floating.animate()
            .scaleX(1.12f)
            .scaleY(1.12f)
            .alpha(1f)
            .setDuration(180)
            .setInterpolator(springInterpolator)
            .start()
    }

    private fun showDropZoneBar(item: DragItem) {
        dropBar.visibility = View.VISIBLE
        dropBar.translationY = -80f * density
        dropBar.alpha = 0f

        // Configure uninstall visibility: only for non-system apps
        val canUninstall = when (item) {
            is DragItem.App -> !item.app.isSystemApp
            is DragItem.MaxApp -> !item.app.isSystemApp
            else -> false
        }
        zoneUninstall.isVisible = canUninstall

        // Label update for remove: "REMOVE" vs "CANCEL"
        val isHomeOrWidget = when (item) {
            is DragItem.App -> item.fromSource != DragSource.DRAWER
            else -> true
        }
        binding.dropZoneInclude.textRemove.text = if (isHomeOrWidget) {
            activity.getString(R.string.drag_target_remove)
        } else {
            activity.getString(R.string.cancel)
        }

        dropBar.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(220)
            .setInterpolator(easeInterpolator)
            .start()
    }

    private fun hideDropZoneBar() {
        dropBar.animate()
            .translationY(-80f * density)
            .alpha(0f)
            .setDuration(180)
            .setInterpolator(easeInterpolator)
            .withEndAction {
                dropBar.visibility = View.GONE
                resetDropZoneHighlights()
            }
            .start()
    }

    private fun createFloatingView(item: DragItem): View {
        val root = FrameLayout(activity).apply {
            setBackgroundResource(R.drawable.bg_tile_dark)
            elevation = 24f * density
            clipToOutline = true
        }
        val iconView = ImageView(activity).apply {
            val pad = (10 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        when (item) {
            is DragItem.App -> iconView.setImageDrawable(item.app.icon)
            is DragItem.MaxApp -> iconView.setImageDrawable(item.app.icon)
            is DragItem.Folder -> iconView.setImageResource(R.drawable.ic_folder)
            is DragItem.Bespoke -> iconView.setImageResource(item.element.iconRes)
            is DragItem.Widget -> iconView.setImageResource(R.drawable.ic_widgets)
        }
        iconView.imageTintList = null

        root.addView(iconView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDragging) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val rawX = event.rawX
                val rawY = event.rawY

                dragFloatingView?.let { v ->
                    v.x = rawX - touchOffsetX
                    v.y = rawY - touchOffsetY
                }

                // Check top drop zones
                checkTopDropZoneHover(rawX, rawY)

                // Check edge screen paging
                checkEdgePaging(rawX)
            }
            MotionEvent.ACTION_UP -> {
                completeDrop(event.rawX, event.rawY)
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelDrag()
            }
        }
        return true
    }

    private fun checkTopDropZoneHover(rawX: Float, rawY: Float) {
        val barLocation = IntArray(2)
        dropBar.getLocationOnScreen(barLocation)
        val barTop = barLocation[1]
        val barBottom = barTop + dropBar.height

        if (rawY in barTop.toFloat()..barBottom.toFloat()) {
            val removeLoc = IntArray(2)
            zoneRemove.getLocationOnScreen(removeLoc)
            val isOverRemove = rawX in removeLoc[0].toFloat()..(removeLoc[0] + zoneRemove.width).toFloat()

            val uninsLoc = IntArray(2)
            zoneUninstall.getLocationOnScreen(uninsLoc)
            val isOverUninstall = zoneUninstall.isVisible && rawX in uninsLoc[0].toFloat()..(uninsLoc[0] + zoneUninstall.width).toFloat()

            val infoLoc = IntArray(2)
            zoneInfo.getLocationOnScreen(infoLoc)
            val isOverInfo = rawX in infoLoc[0].toFloat()..(infoLoc[0] + zoneInfo.width).toFloat()

            highlightZone(zoneRemove, isOverRemove, isDestructive = true)
            highlightZone(zoneUninstall, isOverUninstall, isDestructive = true)
            highlightZone(zoneInfo, isOverInfo, isDestructive = false)
        } else {
            resetDropZoneHighlights()
        }
    }

    private fun highlightZone(view: View, highlighted: Boolean, isDestructive: Boolean) {
        if (highlighted) {
            val tint = if (isDestructive) ContextCompat.getColor(activity, R.color.nothing_red) else ContextCompat.getColor(activity, R.color.nothing_white)
            view.setBackgroundColor(Color.argb(45, Color.red(tint), Color.green(tint), Color.blue(tint)))
        } else {
            view.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun resetDropZoneHighlights() {
        zoneRemove.setBackgroundColor(Color.TRANSPARENT)
        zoneUninstall.setBackgroundColor(Color.TRANSPARENT)
        zoneInfo.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun checkEdgePaging(rawX: Float) {
        if (edgePagingCooldown) return
        val screenWidth = activity.resources.displayMetrics.widthPixels

        if (rawX < edgePagingWidth) {
            triggerEdgePage(-1)
        } else if (rawX > screenWidth - edgePagingWidth) {
            triggerEdgePage(1)
        }
    }

    private fun triggerEdgePage(direction: Int) {
        edgePagingCooldown = true
        activity.performPageFlip(direction)
        activity.window.decorView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

        mainHandler.postDelayed({
            edgePagingCooldown = false
        }, 550)
    }

    private fun completeDrop(rawX: Float, rawY: Float) {
        val item = currentItem ?: return cancelDrag()

        // 1. Check if dropped on Top Action Zone
        val barLocation = IntArray(2)
        dropBar.getLocationOnScreen(barLocation)
        val barTop = barLocation[1]
        val barBottom = barTop + dropBar.height

        if (dropBar.isVisible && rawY in barTop.toFloat()..barBottom.toFloat()) {
            val removeLoc = IntArray(2)
            zoneRemove.getLocationOnScreen(removeLoc)
            if (rawX in removeLoc[0].toFloat()..(removeLoc[0] + zoneRemove.width).toFloat()) {
                handleDropRemove(item)
                finishDrag()
                return
            }

            val uninsLoc = IntArray(2)
            zoneUninstall.getLocationOnScreen(uninsLoc)
            if (zoneUninstall.isVisible && rawX in uninsLoc[0].toFloat()..(uninsLoc[0] + zoneUninstall.width).toFloat()) {
                handleDropUninstall(item)
                finishDrag()
                return
            }

            val infoLoc = IntArray(2)
            zoneInfo.getLocationOnScreen(infoLoc)
            if (rawX in infoLoc[0].toFloat()..(infoLoc[0] + zoneInfo.width).toFloat()) {
                handleDropInfo(item)
                finishDrag()
                return
            }
        }

        // 2. Check if dropped on Dock
        if (activity.isPointOverDock(rawX, rawY)) {
            handleDropOnDock(item)
            finishDrag()
            return
        }

        // 3. Drop on workspace page
        handleDropOnWorkspace(item, rawX, rawY)
        finishDrag()
    }

    private fun handleDropRemove(item: DragItem) {
        when (item) {
            is DragItem.App -> {
                if (item.fromSource != DragSource.DRAWER) {
                    activity.viewModel.removeFromHome(item.app)
                    AppLauncher.toast(activity, R.string.remove_from_home)
                }
            }
            is DragItem.MaxApp -> {
                activity.viewModel.toggleEnlargeApp(item.pageIndex, item.app.key)
                activity.viewModel.removeFromHome(item.app)
            }
            is DragItem.Folder -> {
                activity.viewModel.dissolveFolder(item.pageIndex, item.folder.id)
                AppLauncher.toast(activity, R.string.folder_ungroup)
            }
            is DragItem.Bespoke -> {
                activity.viewModel.removeElementFromPage(item.pageIndex, item.element.id)
                AppLauncher.toast(activity, R.string.remove_element)
            }
            is DragItem.Widget -> {
                activity.viewModel.removeWidget(item.widgetId)
                AppLauncher.toast(activity, R.string.remove_widget)
            }
        }
    }

    private fun handleDropUninstall(item: DragItem) {
        val app = when (item) {
            is DragItem.App -> item.app
            is DragItem.MaxApp -> item.app
            else -> null
        }
        if (app != null && !app.isSystemApp) {
            AppLauncher.uninstall(activity, app)
        }
    }

    private fun handleDropInfo(item: DragItem) {
        val app = when (item) {
            is DragItem.App -> item.app
            is DragItem.MaxApp -> item.app
            else -> null
        }
        if (app != null) {
            AppLauncher.openAppInfo(activity, app, activity.window.decorView)
        }
    }

    private fun handleDropOnDock(item: DragItem) {
        val app = when (item) {
            is DragItem.App -> item.app
            is DragItem.MaxApp -> item.app
            else -> null
        }
        if (app != null) {
            if (activity.viewModel.addToDock(app)) {
                activity.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                AppLauncher.toast(activity, R.string.add_to_dock)
            } else {
                AppLauncher.toast(activity, R.string.dock_full)
            }
        }
    }

    private fun handleDropOnWorkspace(item: DragItem, rawX: Float, rawY: Float) {
        val targetPage = activity.currentPageIndex
        when (item) {
            is DragItem.App -> {
                activity.handleWorkspaceAppDrop(item.app, item.fromSource, item.pageIndex, targetPage, rawX, rawY)
            }
            is DragItem.MaxApp -> {
                if (item.pageIndex != targetPage) {
                    activity.viewModel.moveAppBetweenPages(item.app, item.pageIndex, targetPage)
                    activity.viewModel.toggleEnlargeApp(item.pageIndex, item.app.key)
                    activity.viewModel.toggleEnlargeApp(targetPage, item.app.key)
                }
            }
            is DragItem.Bespoke -> {
                if (item.pageIndex != targetPage) {
                    activity.viewModel.removeElementFromPage(item.pageIndex, item.element.id)
                    activity.viewModel.addElementToPage(targetPage, item.element.id)
                }
            }
            is DragItem.Widget -> {
                if (item.pageIndex != targetPage) {
                    activity.viewModel.removeWidget(item.widgetId)
                    activity.viewModel.addWidgetToPage(targetPage, item.widgetId)
                }
            }
            is DragItem.Folder -> {
                if (item.pageIndex != targetPage) {
                    activity.viewModel.moveFolderBetweenPages(item.folder, item.pageIndex, targetPage)
                    activity.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                }
            }
        }
    }

    private fun cancelDrag() {
        finishDrag()
    }

    private fun finishDrag() {
        isDragging = false
        hideDropZoneBar()

        dragFloatingView?.animate()
            ?.scaleX(0.7f)
            ?.scaleY(0.7f)
            ?.alpha(0f)
            ?.setDuration(160)
            ?.setInterpolator(easeInterpolator)
            ?.withEndAction {
                binding.dragOverlay.removeAllViews()
                binding.dragOverlay.isVisible = false
                dragFloatingView = null
                currentItem = null
            }
            ?.start()
    }
}
