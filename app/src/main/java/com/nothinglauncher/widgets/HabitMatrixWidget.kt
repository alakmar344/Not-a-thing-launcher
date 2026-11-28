package com.nothinglauncher.widgets

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.nothinglauncher.AccentTheme
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.Sheets
import com.nothinglauncher.Tactile
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.min

/**
 * Interactive 28-day LED contribution matrix (Habit Pegboard).
 * Tap "LOG" / "DONE" pill to mark today complete with haptic confirmation; tap title to rename habit.
 */
class HabitMatrixWidget(context: Context) : NothingWidget(context) {
    override val labelRes = R.string.element_habit
    override val descriptionRes = R.string.element_habit_sub
    override val previewRes = R.drawable.preview_widget_habit
    override val spanX = 4
    override val spanY = 2
    override val minSpanX = 2
    override val minSpanY = 2

    override fun createView(host: AppWidgetHostView): View {
        val ctx = host.context
        val prefs = NothingPrefs.get(ctx)
        val root = LayoutInflater.from(ctx).inflate(R.layout.widget_habit_matrix, host, false)
        val dot = root.findViewById<View>(R.id.habitDot)
        val title = root.findViewById<TextView>(R.id.habitTitle)
        val streakTv = root.findViewById<TextView>(R.id.habitStreak)
        val grid = root.findViewById<HabitPegboardView>(R.id.habitGrid)
        val toggleBtn = root.findViewById<TextView>(R.id.habitToggle)

        fun todayKey(): String = Companion.todayKey()

        fun bind() {
            val accent = AccentTheme.accentColor(ctx)
            AccentTheme.applyWidgetSurface(root, ctx, "habit", defaultLight = false)
            val sc = AccentTheme.surfaceColors(ctx, "habit", defaultLight = false)
            AccentTheme.tintDot(dot, ctx)
            grid.accentColor = accent
            grid.applySurfaceColors(sc)
            title.setTextColor(sc.textPrimary)
            streakTv.setTextColor(sc.textSecondary)
            title.text = prefs.habitTitle.uppercase(Locale.getDefault())
            val dates = prefs.habitDates
            val streak = computeStreak(dates)
            streakTv.text = "${streak}D"
            val doneToday = dates.contains(todayKey())
            toggleBtn.setText(if (doneToday) R.string.habit_done else R.string.habit_log)
            toggleBtn.setBackgroundResource(if (doneToday) R.drawable.bg_pill_white else R.drawable.bg_pill_outline)
            toggleBtn.setTextColor(
                if (doneToday) ctx.getColor(R.color.pill_active_text) else sc.textPrimary
            )
            grid.completedDates = dates
        }

        title.setOnClickListener {
            Tactile.tick(it)
            Sheets.showTextInput(
                ctx,
                ctx.getString(R.string.habit_rename_title),
                ctx.getString(R.string.habit_rename_hint),
                prefs.habitTitle,
                maxLength = 20
            ) { name ->
                if (name.isNotBlank()) prefs.habitTitle = name.uppercase(Locale.getDefault())
                bind()
            }
        }

        val logClick = View.OnClickListener {
            Tactile.confirm(it)
            toggleToday(prefs)
            bind()
        }
        toggleBtn.setOnClickListener(logClick)
        grid.setOnClickListener(logClick)

        val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            if (root.isAttachedToWindow) bind()
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                prefs.addListener(prefListener)
                bind()
            }

            override fun onViewDetachedFromWindow(v: View) {
                prefs.removeListener(prefListener)
            }
        })

        bind()
        return root
    }

    companion object {
        private val DATE_FMT_TL = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        }

        fun todayKey(): String =
            DATE_FMT_TL.get()!!.format(Calendar.getInstance().time)

        fun isDoneToday(prefs: NothingPrefs): Boolean =
            prefs.habitDates.contains(todayKey())

        fun toggleToday(prefs: NothingPrefs): Boolean {
            val key = todayKey()
            val next = prefs.habitDates.toMutableSet()
            val added = if (key in next) {
                next.remove(key)
                false
            } else {
                next.add(key)
                true
            }
            prefs.habitDates = next
            return added
        }

        fun computeStreak(dates: Set<String>): Int {
            val fmt = DATE_FMT_TL.get()!!
            val cal = Calendar.getInstance()
            var streak = 0
            if (!dates.contains(fmt.format(cal.time))) {
                cal.add(Calendar.DAY_OF_YEAR, -1)
            }
            while (dates.contains(fmt.format(cal.time))) {
                streak++
                cal.add(Calendar.DAY_OF_YEAR, -1)
            }
            return streak
        }
    }
}

class HabitPegboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.dot_on) }
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.dot_off) }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.nothing_red) }

    var accentColor: Int
        get() = accentPaint.color
        set(value) { accentPaint.color = value; invalidate() }

    fun applySurfaceColors(sc: AccentTheme.SurfaceColors) {
        onPaint.color = sc.onDot
        offPaint.color = sc.offDot
        invalidate()
    }

    var completedDates: Set<String> = emptySet()
        set(value) {
            if (field !== value && field != value) {
                field = value
                cachedForDayStamp = Long.MIN_VALUE
            } else {
                field = value
            }
            invalidate()
        }

    private val doneMask = BooleanArray(TOTAL_DAYS)
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val cal = Calendar.getInstance()
    private var cachedForDayStamp: Long = Long.MIN_VALUE

    private fun ensureMask() {
        cal.timeInMillis = System.currentTimeMillis()
        val stamp = cal.get(Calendar.YEAR) * 1000L + cal.get(Calendar.DAY_OF_YEAR)
        if (stamp == cachedForDayStamp) return
        cachedForDayStamp = stamp
        cal.add(Calendar.DAY_OF_YEAR, -(TOTAL_DAYS - 1))
        for (i in 0 until TOTAL_DAYS) {
            doneMask[i] = completedDates.contains(dateFmt.format(cal.time))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cols = 7
        val rows = 4
        if (width <= 0 || height <= 0) return
        ensureMask()
        val pitchX = width / cols.toFloat()
        val pitchY = height / rows.toFloat()
        val r = min(pitchX, pitchY) * 0.28f
        val todayR = r * 1.25f
        val offR = r * 0.8f

        for (i in 0 until TOTAL_DAYS) {
            val row = i / cols
            val col = i % cols
            val isToday = i == TOTAL_DAYS - 1
            val done = doneMask[i]
            val cx = (col + 0.5f) * pitchX
            val cy = (row + 0.5f) * pitchY
            val paint = when {
                done && isToday -> accentPaint
                done -> onPaint
                else -> offPaint
            }
            canvas.drawCircle(cx, cy, if (isToday && done) todayR else if (done) r else offR, paint)
        }
    }

    private companion object {
        const val TOTAL_DAYS = 28
    }
}
