package com.nothinglauncher.drawer

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.android.launcher3.Insettable
import com.android.launcher3.Launcher
import com.android.launcher3.allapps.AllAppsStore
import com.nothinglauncher.Intents
import com.nothinglauncher.R
import com.nothinglauncher.settings.NothingSettingsActivity
import java.util.Locale

/** v10 drawer header: dot-matrix "APPS", the app count and the settings button. */
class DrawerHeader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs), Insettable {

    private val listener = AllAppsStore.OnUpdateListener { updateCount() }
    private var store: AllAppsStore<*>? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        findViewById<View>(R.id.drawerSettings).setOnClickListener {
            Intents.start(context, Intent(context, NothingSettingsActivity::class.java), it)
        }
    }

    override fun setInsets(insets: Rect) {
        (layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = insets.top
            layoutParams = it
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        store = try {
            Launcher.getLauncher(context).appsView.appsStore.also { it.addUpdateListener(listener) }
        } catch (_: RuntimeException) {
            null // not hosted by the launcher (e.g. a preview)
        }
        updateCount()
    }

    override fun onDetachedFromWindow() {
        store?.removeUpdateListener(listener)
        store = null
        super.onDetachedFromWindow()
    }

    private fun updateCount() {
        val count = store?.apps?.size ?: return
        findViewById<TextView>(R.id.drawerCount).text = String.format(Locale.US, "%03d", count)
    }
}
