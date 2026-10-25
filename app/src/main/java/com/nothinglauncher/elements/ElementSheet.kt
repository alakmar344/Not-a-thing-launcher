package com.nothinglauncher.elements

import android.app.Dialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.nothinglauncher.DotMatrixView
import com.nothinglauncher.R
import com.nothinglauncher.Sheets

/**
 * Builder for small settings sheets used by elements, in the launcher's visual language:
 * dot-matrix title, arbitrary content rows, and Cancel / primary buttons.
 */
class ElementSheet(private val context: Context, title: String) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_sheet)
        setPadding(dp(18), dp(16), dp(18), dp(18))
    }
    private val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val root = FrameLayout(context).apply { setPadding(dp(12), dp(12), dp(12), dp(12)) }
    private var dialog: Dialog? = null

    init {
        val handle = View(context).apply { setBackgroundResource(R.drawable.bg_drag_handle) }
        card.addView(handle, LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val t = DotMatrixView(context).apply {
            text = title.uppercase()
            dotColor = ContextCompat.getColor(context, R.color.dot_on)
            dotScale = 0.42f
            gravityMode = 0
        }
        card.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(18)).apply { topMargin = dp(16) })
        card.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        card.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(18) })
        root.addView(card)
    }

    /** Secondary explanatory text. */
    fun addText(text: CharSequence): TextView {
        val tv = TextView(context).apply {
            this.text = text
            setTextAppearance(R.style.Text_Body_Secondary)
        }
        content.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        return tv
    }

    /** A labelled single-line text field. */
    fun addField(label: String, value: String?, hint: String? = null, inputType: Int = InputType.TYPE_CLASS_TEXT): EditText {
        val l = TextView(context).apply {
            text = label.uppercase()
            setTextAppearance(R.style.Text_Label)
        }
        content.addView(l, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        val e = EditText(context).apply {
            setText(value.orEmpty())
            this.hint = hint
            this.inputType = inputType
            isSingleLine = true
            setTextAppearance(R.style.Text_Body)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_tile_dark)
            setPadding(dp(16), 0, dp(16), 0)
        }
        content.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) })
        return e
    }

    /** A tappable pill row (e.g. a choice); [selected] renders it inverted. */
    fun addOption(label: String, selected: Boolean, onClick: () -> Unit): TextView {
        val tv = TextView(context).apply {
            text = label
            setTextAppearance(R.style.Text_Body)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            setBackgroundResource(if (selected) R.drawable.bg_pill_white else R.drawable.bg_pill)
            setTextColor(ContextCompat.getColor(context, if (selected) R.color.pill_active_text else R.color.text_primary))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        content.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(8) })
        return tv
    }

    fun addView(v: View, lp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) {
        content.addView(v, lp)
    }

    /** Adds Cancel + [primary] buttons. [onPrimary] returns false to keep the sheet open. */
    fun buttons(primary: String, onPrimary: () -> Boolean, secondary: String? = context.getString(R.string.cancel), onSecondary: (() -> Unit)? = null) {
        buttons.removeAllViews()
        if (secondary != null) {
            buttons.addView(button(secondary, false) { onSecondary?.invoke(); dialog?.dismiss() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            buttons.addView(View(context), LinearLayout.LayoutParams(dp(12), 1))
        }
        buttons.addView(button(primary, true) { if (onPrimary()) dialog?.dismiss() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun button(text: String, primary: Boolean, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        setTextAppearance(R.style.Text_Label)
        gravity = Gravity.CENTER
        setBackgroundResource(if (primary) R.drawable.bg_pill_white else R.drawable.bg_pill)
        setTextColor(ContextCompat.getColor(context, if (primary) R.color.pill_active_text else R.color.text_secondary))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    fun show(host: ElementHost? = null): Dialog {
        if (buttons.childCount == 0) buttons.visibility = View.GONE
        val d = Sheets.create(context, root)
        dialog = d
        host?.trackDialog(d)
        d.show()
        return d
    }

    fun dismiss() = dialog?.dismiss()
}
