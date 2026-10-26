package com.nothinglauncher

import android.app.Dialog
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nothinglauncher.databinding.DialogConfirmBinding
import com.nothinglauncher.databinding.DialogCountdownBinding
import com.nothinglauncher.databinding.DialogListBinding
import com.nothinglauncher.databinding.ItemHiddenAppBinding
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.nothinglauncher.databinding.DialogEditNoteBinding
import com.nothinglauncher.databinding.DialogWallpaperStyleBinding
import com.nothinglauncher.databinding.SheetCustomizeBinding

/** Floating bottom sheets & dialogs in the launcher's own visual language. */
object Sheets {

    /**
     * Creates a bottom sheet. [onTouch] lets a caller keep receiving the gesture stream from
     * the dialog window (the folder sheet uses it to continue a drag that began on an app icon
     * inside the folder).
     */
    fun create(
        context: Context,
        content: View,
        maxHeightFraction: Float = 0f,
        onTouch: ((MotionEvent) -> Boolean)? = null
    ): Dialog {
        val dialog = object : Dialog(context, R.style.Theme_NothingLauncher_Sheet) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (onTouch?.invoke(ev) == true) return true
                return super.dispatchTouchEvent(ev)
            }
        }
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        val height = if (maxHeightFraction > 0f) {
            (context.resources.displayMetrics.heightPixels * maxHeightFraction).toInt()
        } else {
            WindowManager.LayoutParams.WRAP_CONTENT
        }
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, height)
            setGravity(Gravity.BOTTOM)
        }
        return dialog
    }

    fun showCustomize(
        context: Context,
        onWidgets: () -> Unit,
        onAddPage: () -> Unit,
        onRemovePage: () -> Unit,
        canRemovePage: Boolean,
        onWallpaper: () -> Unit,
        onSettings: () -> Unit
    ): Dialog {
        val b = SheetCustomizeBinding.inflate(LayoutInflater.from(context))
        val dialog = create(context, b.root)
        fun tile(t: com.nothinglauncher.databinding.ItemSheetTileBinding, icon: Int, label: Int, enabled: Boolean = true, action: () -> Unit) {
            t.tileIcon.setImageResource(icon)
            t.tileLabel.setText(label)
            t.root.contentDescription = context.getString(label)
            t.root.alpha = if (enabled) 1f else 0.4f
            t.root.isEnabled = enabled
            t.root.setOnClickListener {
                if (enabled) {
                    dialog.dismiss()
                    action()
                }
            }
        }
        tile(b.tileWidgets, R.drawable.ic_widgets, R.string.widgets, action = onWidgets)
        tile(b.tileAddPage, R.drawable.ic_add, R.string.add_page, action = onAddPage)
        tile(b.tileRemovePage, R.drawable.ic_delete, R.string.remove_page, enabled = canRemovePage, action = onRemovePage)
        tile(b.tileWallpaper, R.drawable.ic_wallpaper, R.string.wallpaper_style, action = onWallpaper)
        tile(b.tileSettings, R.drawable.ic_tune, R.string.settings, action = onSettings)
        dialog.show()
        return dialog
    }

    fun showWallpaperStyle(
        context: Context,
        prefs: LauncherPrefs,
        onWallpaperPicker: () -> Unit,
        onChanged: () -> Unit
    ): Dialog {
        val b = DialogWallpaperStyleBinding.inflate(LayoutInflater.from(context))
        val dialog = create(context, b.root)

        b.wallpaperClose.setOnClickListener { dialog.dismiss() }
        b.cardSystemWallpaper.setOnClickListener {
            dialog.dismiss()
            onWallpaperPicker()
        }

        fun updateBgPills() {
            val bg = prefs.background
            fun stylePill(btn: TextView, active: Boolean) {
                btn.setBackgroundResource(if (active) R.drawable.bg_pill_white else R.drawable.bg_pill)
                btn.setTextColor(ContextCompat.getColor(context, if (active) R.color.pill_active_text else R.color.text_primary))
            }
            stylePill(b.btnBgDots, bg == LauncherPrefs.Background.DOTS)
            stylePill(b.btnBgSolid, bg == LauncherPrefs.Background.BLACK)
            stylePill(b.btnBgWallpaper, bg == LauncherPrefs.Background.WALLPAPER)
        }

        fun updateThemePills() {
            val tm = prefs.themeMode
            fun stylePill(btn: TextView, active: Boolean) {
                btn.setBackgroundResource(if (active) R.drawable.bg_pill_white else R.drawable.bg_pill)
                btn.setTextColor(ContextCompat.getColor(context, if (active) R.color.pill_active_text else R.color.text_primary))
            }
            stylePill(b.btnThemeSystem, tm == LauncherPrefs.ThemeMode.SYSTEM)
            stylePill(b.btnThemeDark, tm == LauncherPrefs.ThemeMode.DARK)
            stylePill(b.btnThemeLight, tm == LauncherPrefs.ThemeMode.LIGHT)
        }

        updateBgPills()
        updateThemePills()

        b.btnBgDots.setOnClickListener {
            prefs.background = LauncherPrefs.Background.DOTS
            updateBgPills()
            onChanged()
        }
        b.btnBgSolid.setOnClickListener {
            prefs.background = LauncherPrefs.Background.BLACK
            updateBgPills()
            onChanged()
        }
        b.btnBgWallpaper.setOnClickListener {
            prefs.background = LauncherPrefs.Background.WALLPAPER
            updateBgPills()
            onChanged()
        }

        b.btnThemeSystem.setOnClickListener {
            prefs.themeMode = LauncherPrefs.ThemeMode.SYSTEM
            updateThemePills()
            onChanged()
        }
        b.btnThemeDark.setOnClickListener {
            prefs.themeMode = LauncherPrefs.ThemeMode.DARK
            updateThemePills()
            onChanged()
        }
        b.btnThemeLight.setOnClickListener {
            prefs.themeMode = LauncherPrefs.ThemeMode.LIGHT
            updateThemePills()
            onChanged()
        }

        dialog.show()
        return dialog
    }

    fun showEditNote(
        context: Context,
        currentNote: String,
        onSave: (String) -> Unit
    ): Dialog {
        val b = DialogEditNoteBinding.inflate(LayoutInflater.from(context))
        val dialog = create(context, b.root)
        b.noteTitle.text = "QUICK NOTE"
        b.noteInput.setText(currentNote)
        b.noteInput.setSelection(currentNote.length)
        b.noteCancel.setOnClickListener { dialog.dismiss() }
        b.noteSave.setOnClickListener {
            dialog.dismiss()
            val text = b.noteInput.text.toString().trim()
            onSave(text.ifEmpty { "NOTHING BUT TIME" })
        }
        dialog.show()
        return dialog
    }

    /** Same sheet as the quick-note editor, used for renaming folders. */
    fun showRename(
        context: Context,
        currentName: String,
        onSave: (String) -> Unit
    ): Dialog {
        val b = DialogEditNoteBinding.inflate(LayoutInflater.from(context))
        val dialog = create(context, b.root)
        b.noteTitle.text = "RENAME"
        b.noteInput.setText(currentName)
        b.noteInput.setSelection(b.noteInput.text.length)
        b.noteInput.hint = context.getString(R.string.folder_rename_hint)
        b.noteCancel.setOnClickListener { dialog.dismiss() }
        b.noteSave.setOnClickListener {
            dialog.dismiss()
            onSave(b.noteInput.text.toString().trim())
        }
        dialog.show()
        return dialog
    }

    /**
     * Countdown element editor: event title (free text), a tappable target-date row that opens
     * the system date picker, and a clear button. Persists through [LauncherPrefs] so the tile
     * survives reboots, and re-renders through [onSaved] whenever the values actually change.
     */
    fun showCountdown(
        context: Context,
        prefs: LauncherPrefs,
        onSaved: () -> Unit
    ): Dialog {
        val b = DialogCountdownBinding.inflate(LayoutInflater.from(context))
        val dialog = create(context, b.root)
        b.countdownTitle.text = "EDIT COUNTDOWN"
        b.countdownInput.setText(prefs.countdownTitle)
        b.countdownInput.setSelection(b.countdownInput.text.length)

        var targetEpochDay = prefs.countdownEpochDay

        fun updateDateRow() {
            b.countdownDate.text = if (targetEpochDay > 0L) {
                try {
                    android.text.format.DateFormat.getMediumDateFormat(context)
                        .format(java.util.Date.from(
                            java.time.LocalDate.ofEpochDay(targetEpochDay)
                                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()
                        ))
                        .uppercase()
                } catch (_: Exception) {
                    context.getString(R.string.countdown_set_date)
                }
            } else {
                context.getString(R.string.countdown_set_date)
            }
        }

        b.countdownDate.setOnClickListener {
            val seed = if (targetEpochDay > 0L) targetEpochDay else java.time.LocalDate.now().toEpochDay()
            val date = java.time.LocalDate.ofEpochDay(seed)
            try {
                android.app.DatePickerDialog(
                    context,
                    { _, year, month, dayOfMonth ->
                        targetEpochDay = java.time.LocalDate.of(year, month + 1, dayOfMonth).toEpochDay()
                        updateDateRow()
                    },
                    date.year,
                    date.monthValue - 1,
                    date.dayOfMonth
                ).show()
            } catch (_: Exception) {
                AppLauncher.toast(context, R.string.cannot_open)
            }
        }

        b.countdownClear.setOnClickListener {
            dialog.dismiss()
            prefs.countdownEpochDay = 0L
            prefs.countdownTitle = ""
            AppLauncher.toast(context, R.string.countdown_cleared)
            onSaved()
        }

        b.countdownSave.setOnClickListener {
            dialog.dismiss()
            prefs.countdownTitle = b.countdownInput.text.toString().trim().uppercase()
            if (targetEpochDay > 0L) prefs.countdownEpochDay = targetEpochDay
            AppLauncher.toast(
                context,
                if (prefs.countdownEpochDay > 0L) R.string.countdown_saved else R.string.countdown_set_date
            )
            onSaved()
        }

        updateDateRow()
        dialog.show()
        return dialog
    }

    /**
     * Shared single-field editor used by weather location, countdown, step goal and folder rename.
     * The title and hint are plain strings so the same sheet can show dynamic content.
     */
    fun showTextInput(
        context: Context,
        title: String,
        hint: String,
        initial: String,
        maxLength: Int = 48,
        numeric: Boolean = false,
        onSave: (String) -> Unit
    ): Dialog {
        val b = DialogEditNoteBinding.inflate(LayoutInflater.from(context))
        val dialog = create(context, b.root)
        b.noteTitle.text = title.uppercase()
        b.noteInput.hint = hint
        b.noteInput.setText(initial)
        b.noteInput.setSelection(initial.length)
        b.noteInput.inputType = if (numeric) {
            android.text.InputType.TYPE_CLASS_NUMBER
        } else {
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        b.noteInput.filters = arrayOf(android.text.InputFilter.LengthFilter(maxLength))
        b.noteCancel.setOnClickListener { dialog.dismiss() }
        b.noteSave.setOnClickListener {
            dialog.dismiss()
            onSave(b.noteInput.text.toString().trim())
        }
        dialog.show()
        return dialog
    }

    fun showConfirm(context: Context, title: Int, body: Int, ok: Int, onOk: () -> Unit): Dialog {
        val b = DialogConfirmBinding.inflate(LayoutInflater.from(context))
        val dialog = create(context, b.root)
        b.confirmTitle.setText(title)
        b.confirmBody.setText(body)
        b.confirmOk.setText(ok)
        b.confirmCancel.setOnClickListener { dialog.dismiss() }
        b.confirmOk.setOnClickListener { dialog.dismiss(); onOk() }
        dialog.show()
        return dialog
    }

    /** Lists hidden apps with an "Unhide" button each. */
    fun showHiddenApps(context: Context, prefs: LauncherPrefs, onChanged: () -> Unit): Dialog {
        val b = DialogListBinding.inflate(LayoutInflater.from(context))
        val dialog = create(context, b.root, maxHeightFraction = 0.8f)
        b.listTitle.text = "HIDDEN"
        b.listClose.setOnClickListener { dialog.dismiss() }
        b.listEmpty.setText(R.string.setting_hidden_none)

        data class Row(val key: String, val label: String, val icon: Drawable?)

        val la = context.getSystemService(LauncherApps::class.java)
        val sizePx = context.resources.getDimensionPixelSize(R.dimen.icon_render_size)
        val density = context.resources.displayMetrics.densityDpi
        val rows = prefs.hiddenApps.mapNotNull { key ->
            val pkg = key.substringBefore('/')
            // Search every profile so hidden work-profile apps can be unhidden too.
            val profiles = (try { la?.profiles } catch (e: Exception) { null })
                ?.takeIf { it.isNotEmpty() } ?: listOf(Process.myUserHandle())
            val info = profiles.firstNotNullOfOrNull { user ->
                try {
                    la?.getActivityList(pkg, user)?.firstOrNull { "${it.componentName.packageName}/${it.componentName.className}" == key }
                } catch (e: Exception) { null }
            }
            if (info == null) {
                null
            } else {
                val icon = try { IconFactory.render(context.resources, info.getIcon(density), prefs.iconStyle, sizePx) } catch (e: Exception) { null }
                Row(key, info.label?.toString().orEmpty().ifEmpty { info.componentName.className }, icon)
            }
        }.sortedBy { it.label.lowercase() }

        b.listEmpty.isVisible = rows.isEmpty()
        b.listRecycler.isVisible = rows.isNotEmpty()
        b.listRecycler.layoutManager = LinearLayoutManager(context)
        b.listRecycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val row = ItemHiddenAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                return object : RecyclerView.ViewHolder(row.root) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = rows[position]
                val itemBinding = ItemHiddenAppBinding.bind(holder.itemView)
                itemBinding.hiddenIcon.setImageDrawable(item.icon)
                itemBinding.hiddenLabel.text = item.label
                itemBinding.hiddenUnhide.setOnClickListener {
                    prefs.hiddenApps = prefs.hiddenApps - item.key
                    dialog.dismiss()
                    onChanged()
                }
            }
        }
        dialog.show()
        return dialog
    }
}
