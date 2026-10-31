package com.nothinglauncher

import android.app.Activity
import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.nothinglauncher.databinding.ItemPopupActionBinding
import com.nothinglauncher.databinding.PopupAppBinding

/**
 * The long-press card for an app (or widget): header, optional app shortcuts, then actions.
 * Appears above the anchor when there is room, otherwise below; always kept on screen.
 */
class AppPopup(private val context: Context) {

    data class Action(
        val iconRes: Int,
        val label: String,
        val destructive: Boolean = false,
        val onClick: () -> Unit
    )

    private var window: PopupWindow? = null

    val isShowing: Boolean get() = window?.isShowing == true

    fun dismiss() {
        window?.dismiss()
        window = null
    }

    /** Builds the popup card (also used by previews/tests). */
    fun buildContent(
        title: String,
        subtitle: String?,
        icon: Drawable?,
        shortcuts: List<ShortcutRow>,
        actions: List<Action>
    ): View {
        val inflater = LayoutInflater.from(context)
        val b = PopupAppBinding.inflate(inflater)

        b.popupTitle.text = title
        b.popupSubtitle.text = subtitle.orEmpty()
        b.popupSubtitle.isVisible = !subtitle.isNullOrEmpty()
        b.popupIcon.setImageDrawable(icon)
        b.popupIcon.isVisible = icon != null

        shortcuts.forEach { s ->
            val row = ItemPopupActionBinding.inflate(inflater, b.popupShortcuts, false)
            row.actionLabel.text = s.label
            if (s.icon != null) {
                row.actionIcon.imageTintList = null
                row.actionIcon.setImageDrawable(s.icon)
            } else {
                row.actionIcon.setImageResource(R.drawable.ic_shortcut)
            }
            row.root.setOnClickListener { v -> dismiss(); s.onClick(v) }
            b.popupShortcuts.addView(row.root)
        }
        b.popupDivider.isVisible = shortcuts.isNotEmpty()

        val red = ContextCompat.getColor(context, R.color.nothing_red)
        actions.forEach { action ->
            val row = ItemPopupActionBinding.inflate(inflater, b.popupActions, false)
            row.actionLabel.text = action.label
            row.actionIcon.setImageResource(action.iconRes)
            if (action.destructive) {
                row.actionLabel.setTextColor(red)
                row.actionIcon.imageTintList = ColorStateList.valueOf(red)
            }
            row.root.setOnClickListener { dismiss(); action.onClick() }
            b.popupActions.addView(row.root)
        }
        return b.root
    }

    data class ShortcutRow(val label: String, val icon: Drawable?, val onClick: (View) -> Unit)

    fun show(
        anchor: View,
        title: String,
        subtitle: String?,
        icon: Drawable?,
        shortcuts: List<ShortcutInfo>,
        actions: List<Action>,
        onShortcut: (ShortcutInfo, View) -> Unit = { _, _ -> },
        anchorBounds: Rect? = null
    ) {
        dismiss()
        // Showing a window for a finishing activity throws BadTokenException.
        (context as? Activity)?.let { if (it.isFinishing || it.isDestroyed) return }
        val rows = shortcuts.map { s ->
            ShortcutRow(
                label = (s.shortLabel ?: s.longLabel ?: "").toString(),
                icon = AppLauncher.shortcutIcon(context, s),
                onClick = { v -> onShortcut(s, v) }
            )
        }
        val content = buildContent(title, subtitle, icon, rows, actions)
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val density = context.resources.displayMetrics.density
        val margin = (12 * density).toInt()
        // The card is inflated without a parent, so its 248dp root width is lost; apply it
        // explicitly (never wider than the screen) or long titles stretch it edge to edge.
        val cardW = (CARD_WIDTH_DP * density).toInt().coerceAtMost(screenW - 2 * margin).coerceAtLeast(1)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(cardW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupW = content.measuredWidth
        val popupH = content.measuredHeight

        val gap = (6 * density).toInt()
        val anchorTop: Int
        val anchorBottom: Int
        val anchorCenterX: Int
        if (anchorBounds != null) {
            anchorTop = anchorBounds.top
            anchorBottom = anchorBounds.bottom
            anchorCenterX = anchorBounds.centerX()
        } else {
            if (!anchor.isAttachedToWindow) return
            val loc = IntArray(2)
            anchor.getLocationOnScreen(loc)
            anchorTop = loc[1]
            anchorBottom = loc[1] + anchor.height
            anchorCenterX = loc[0] + anchor.width / 2
        }

        val x = (anchorCenterX - popupW / 2).coerceIn(margin, (screenW - popupW - margin).coerceAtLeast(margin))
        val topLimit = (40 * density).toInt()
        val y = when {
            anchorTop - popupH - gap >= topLimit -> anchorTop - popupH - gap
            anchorBottom + gap + popupH <= screenH - margin -> anchorBottom + gap
            else -> ((screenH - popupH) / 2).coerceAtLeast(topLimit)
        }

        // The folder sheet owns the anchor view but must dismiss before its action menu can
        // receive touches. In that case the popup is positioned from the captured bounds and
        // attached to the activity window instead of the now-detached view.
        val host = if (anchor.isAttachedToWindow) {
            anchor
        } else {
            (context as? Activity)?.window?.decorView
        } ?: return

        val pw = PopupWindow(content, popupW, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(0))
            isOutsideTouchable = true
            elevation = 24 * density
            animationStyle = R.style.Animation_NothingLauncher_Popup
        }
        window = pw
        try {
            pw.showAtLocation(host, Gravity.NO_GRAVITY, x, y)
        } catch (e: Exception) {
            window = null
        }
    }

    private companion object {
        const val CARD_WIDTH_DP = 248
    }
}
