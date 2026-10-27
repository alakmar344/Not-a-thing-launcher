package com.nothinglauncher

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import com.nothinglauncher.databinding.ItemSettingRowBinding
import com.nothinglauncher.databinding.ItemSettingSectionBinding

/**
 * Small toolkit for building the settings screen: sections → cards → rows.
 * Kept free of preference logic so the same pieces render in previews/tests.
 */
object SettingsUi {

    fun section(parent: ViewGroup, title: String, accent: Int) {
        val b = ItemSettingSectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        b.sectionTitle.text = title
        b.sectionDot.background.mutate().setTint(accent)
        parent.addView(b.root)
    }

    fun card(parent: ViewGroup): LinearLayout {
        val card = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            clipToOutline = true
        }
        parent.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return card
    }

    private fun row(card: LinearLayout, title: String, subtitle: String?): ItemSettingRowBinding {
        if (card.childCount > 0) {
            val d = card.resources.displayMetrics.density
            val divider = View(card.context).apply { setBackgroundColor(ContextCompat.getColor(context, R.color.hairline)) }
            card.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * d).toInt().coerceAtLeast(1)).apply {
                marginStart = (18 * d).toInt()
            })
        }
        val b = ItemSettingRowBinding.inflate(LayoutInflater.from(card.context), card, false)
        b.rowTitle.text = title
        b.rowSubtitle.text = subtitle.orEmpty()
        b.rowSubtitle.isVisible = !subtitle.isNullOrEmpty()
        card.addView(b.root)
        return b
    }

    fun switchRow(card: LinearLayout, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit): NothingSwitch {
        val b = row(card, title, subtitle)
        val sw = NothingSwitch(card.context)
        sw.setChecked(checked)
        sw.onCheckedChange = onChange
        sw.contentDescription = title
        b.rowTrailing.addView(sw)
        b.root.setOnClickListener { sw.setChecked(!sw.isChecked, animate = true, notify = true) }
        sw.isClickable = false
        sw.isFocusable = false
        b.root.contentDescription = title
        return sw
    }

    /** A full-width segmented control under the title. */
    fun choiceRow(card: LinearLayout, title: String, subtitle: String?, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        val b = row(card, title, subtitle)
        b.root.background = null
        b.rowBelow.isVisible = true
        val ctx = card.context
        val d = ctx.resources.displayMetrics.density
        val group = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_pill_outline)
            val p = (3 * d).toInt()
            setPadding(p, p, p, p)
        }
        val mono = try { ResourcesCompat.getFont(ctx, R.font.space_mono_regular) } catch (e: Exception) { null }
        val views = mutableListOf<TextView>()
        fun paint(sel: Int) {
            views.forEachIndexed { i, tv ->
                val on = i == sel
                tv.setBackgroundResource(if (on) R.drawable.bg_pill_white else 0)
                tv.setTextColor(ContextCompat.getColor(ctx, if (on) R.color.pill_active_text else R.color.text_secondary))
                tv.isSelected = on
            }
        }
        options.forEachIndexed { index, label ->
            val tv = TextView(ctx).apply {
                text = label.uppercase()
                typeface = mono
                textSize = 11f
                letterSpacing = 0.08f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = "$title: $label"
                setOnClickListener { paint(index); onSelect(index) }
            }
            views += tv
            group.addView(tv, LinearLayout.LayoutParams(0, (36 * d).toInt(), 1f))
        }
        paint(selected)
        b.rowBelow.addView(group, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun actionRow(
        card: LinearLayout,
        title: String,
        subtitle: String?,
        destructive: Boolean = false,
        trailingIcon: Int = R.drawable.ic_chevron_right,
        onClick: () -> Unit
    ): ItemSettingRowBinding {
        val b = row(card, title, subtitle)
        val ctx = card.context
        val d = ctx.resources.displayMetrics.density
        val icon = ImageView(ctx).apply {
            setImageResource(trailingIcon)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, if (destructive) R.color.nothing_red else R.color.text_tertiary))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        b.rowTrailing.addView(icon, FrameLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt()))
        if (destructive) b.rowTitle.setTextColor(ContextCompat.getColor(ctx, R.color.nothing_red))
        b.root.setOnClickListener { onClick() }
        return b
    }

    fun infoRow(card: LinearLayout, title: String, subtitle: String?): ItemSettingRowBinding {
        val b = row(card, title, subtitle)
        b.root.background = null
        return b
    }
}
