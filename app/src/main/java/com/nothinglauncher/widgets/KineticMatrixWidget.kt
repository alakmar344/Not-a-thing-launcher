package com.nothinglauncher.widgets

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.nothinglauncher.AccentTheme
import com.nothinglauncher.KineticMatrixView
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.Tactile

/**
 * 13th dot-matrix home element: Interactive Kinetic Matrix playground.
 * Switch between tilt-driven GRAVITY beads, tap-to-seed LIFE, and playable SNAKE directly on Home.
 */
class KineticMatrixWidget(context: Context) : NothingWidget(context) {
    override val labelRes = R.string.element_kinetic
    override val descriptionRes = R.string.element_kinetic_sub
    override val previewRes = R.drawable.preview_widget_kinetic
    override val spanX = 4
    override val spanY = 2
    override val minSpanX = 2
    override val minSpanY = 2

    override fun createView(host: AppWidgetHostView): View {
        val ctx = host.context
        val prefs = NothingPrefs.get(ctx)
        val root = LayoutInflater.from(ctx).inflate(R.layout.widget_kinetic_matrix, host, false)
        val dot = root.findViewById<View>(R.id.kineticDot)
        val hint = root.findViewById<TextView>(R.id.kineticHint)
        val modePill = root.findViewById<TextView>(R.id.kineticModePill)
        val matrix = root.findViewById<KineticMatrixView>(R.id.kineticMatrix)

        fun bind() {
            val mode = KineticMatrixView.Mode.fromPref(prefs.kineticMode)
            val accent = AccentTheme.accentColor(ctx)
            AccentTheme.applyWidgetSurface(root, ctx, "kinetic", defaultLight = false)
            val sc = AccentTheme.surfaceColors(ctx, "kinetic", defaultLight = false)
            AccentTheme.tintDot(dot, ctx)
            hint.setTextColor(sc.textSecondary)
            modePill.setTextColor(sc.textPrimary)
            matrix.accentColor = accent
            matrix.applySurfaceColors(sc)
            matrix.mode = mode
            when (mode) {
                KineticMatrixView.Mode.GRAVITY -> {
                    modePill.setText(R.string.nt_kinetic_gravity)
                    hint.setText(R.string.nt_kinetic_hint_gravity)
                }
                KineticMatrixView.Mode.LIFE -> {
                    modePill.setText(R.string.nt_kinetic_life)
                    hint.setText(R.string.nt_kinetic_hint_life)
                }
                KineticMatrixView.Mode.SNAKE -> {
                    modePill.setText(R.string.nt_kinetic_snake)
                    hint.text = ctx.getString(
                        if (matrix.snakeGameOver) R.string.nt_kinetic_hint_snake_over else R.string.nt_kinetic_hint_snake,
                        matrix.snakeScore
                    )
                }
            }
        }

        matrix.onScoreChanged = { score ->
            if (matrix.mode == KineticMatrixView.Mode.SNAKE && !matrix.snakeGameOver) {
                hint.text = ctx.getString(R.string.nt_kinetic_hint_snake, score)
            }
        }
        matrix.onGameOverChanged = { gameOver, score ->
            if (matrix.mode == KineticMatrixView.Mode.SNAKE) {
                hint.text = ctx.getString(
                    if (gameOver) R.string.nt_kinetic_hint_snake_over else R.string.nt_kinetic_hint_snake,
                    score
                )
            }
        }

        modePill.setOnClickListener {
            Tactile.tick(it)
            prefs.kineticMode = (prefs.kineticMode + 1) % 3
            bind()
        }

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
}
