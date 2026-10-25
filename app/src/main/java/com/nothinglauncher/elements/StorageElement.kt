package com.nothinglauncher.elements

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import com.nothinglauncher.R
import java.util.concurrent.atomic.AtomicInteger

/** Internal storage free / total with a dot ring of used capacity. */
class StorageElement(context: Context) : ElementView(context) {

    private val p = EaPaints(context, accentColor)
    private val msgPaint = ElementUi.labelPaint(context, 10f, primary = true)

    private var totalBytes = -1L
    private var freeBytes = -1L
    private val generation = AtomicInteger(0)
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            EaKit.main.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        setWillNotDraw(false)
        setBackgroundColor(0)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            if (isHostReady) {
                EaKit.tap(this)
                host.startActivitySafely(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), this)
            }
        }
    }

    override fun onLiveChanged(live: Boolean) {
        EaKit.main.removeCallbacks(refresher)
        if (live) EaKit.main.post(refresher) else generation.incrementAndGet()
    }

    override fun onDetachedFromWindow() {
        EaKit.main.removeCallbacks(refresher)
        generation.incrementAndGet()
        super.onDetachedFromWindow()
    }

    private fun refresh() {
        val gen = generation.incrementAndGet()
        val app = context.applicationContext
        try {
            EaKit.io.execute {
                val r = query(app)
                EaKit.main.post {
                    if (gen != generation.get() || r == null) return@post
                    if (r.first != totalBytes || r.second != freeBytes) {
                        totalBytes = r.first
                        freeBytes = r.second
                        updateDescription()
                        invalidate()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    /** Returns (total, free) bytes. StorageStatsManager reports the full device capacity. */
    private fun query(ctx: Context): Pair<Long, Long>? {
        try {
            val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
            if (ssm != null) {
                val total = ssm.getTotalBytes(StorageManager.UUID_DEFAULT)
                val free = ssm.getFreeBytes(StorageManager.UUID_DEFAULT)
                if (total > 0 && free in 0..total) return total to free
            }
        } catch (_: Exception) {}
        return try {
            val fs = StatFs(Environment.getDataDirectory().path)
            val total = fs.totalBytes
            val free = fs.availableBytes
            if (total > 0) total to free.coerceIn(0, total) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun usedPct(): Int = if (totalBytes > 0) (((totalBytes - freeBytes) * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

    private fun updateDescription() {
        contentDescription = if (totalBytes > 0)
            context.getString(R.string.ea_storage_cd, EaKit.gb(freeBytes), EaKit.gb(totalBytes), usedPct())
        else context.getString(R.string.element_storage)
    }

    override fun onAccentChanged(color: Int) {
        p.setAccent(color)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val headerH = if (h > EaKit.dp(context, 110f)) p.label.textSize * 1.9f else 0f
        if (headerH > 0) ElementUi.drawHeader(canvas, 0f, p.label.textSize, context.getString(R.string.ea_storage), p.accent, p.label)
        val top = headerH
        val areaH = h - top
        val wide = w > areaH * 1.6f
        val size = if (wide) areaH else minOf(w, areaH)
        val cx = if (wide) size / 2f else w / 2f
        val cy = top + areaH / 2f
        val dotR = (size / 48f).coerceAtLeast(EaKit.dp(context, 1.2f))
        val radius = size / 2f - dotR * 1.5f
        val count = 40
        val known = totalBytes > 0
        val pct = usedPct()
        val lit = if (known) (pct * count + 50) / 100 else 0
        // Accent highlights the ring once storage is nearly full (≥ 90 %).
        EaKit.drawRing(canvas, cx, cy, radius, count, lit, dotR, p.on, p.off) { i, isLit ->
            if (isLit && (pct >= 90 || i == lit - 1)) p.accent else null
        }

        val freeText = if (known) EaKit.gb(freeBytes) else "--"
        val sub = if (known) context.getString(R.string.ea_free_of, EaKit.gb(totalBytes)) else ""
        if (wide) {
            val tx = size + EaKit.dp(context, 14f)
            val tw = w - tx
            if (tw <= 0) return
            val pitch = ElementUi.fitPitch(freeText, tw, areaH * 0.38f)
            val dotH = pitch * 7
            val blockTop = cy - (dotH + p.labelPrimary.textSize * 2.6f) / 2f
            ElementUi.drawDotText(canvas, freeText, tx, blockTop, pitch, p.on)
            EaKit.drawFitText(canvas, context.getString(R.string.ea_gb_free).uppercase(), tx, blockTop + dotH + p.labelPrimary.textSize * 1.3f, tw, p.labelPrimary, android.graphics.Paint.Align.LEFT)
            if (known) EaKit.drawFitText(canvas, context.getString(R.string.ea_used_pct, pct, EaKit.gb(totalBytes)).uppercase(), tx, blockTop + dotH + p.labelPrimary.textSize * 2.6f, tw, p.label, android.graphics.Paint.Align.LEFT)
        } else {
            val inner = (radius - dotR * 2.5f) * 2f * 0.8f
            if (inner <= 0) return
            val maxH = inner * 0.30f
            val pitch = ElementUi.fitPitch(freeText, inner, maxH)
            val dotH = pitch * 7
            val showSub = sub.isNotEmpty() && inner > p.label.textSize * 5
            val gap = p.label.textSize * 1.4f
            val blockTop = cy - (dotH + if (showSub) gap else 0f) / 2f
            EaKit.drawDotCentered(canvas, freeText, cx, blockTop, inner, maxH, p.on)
            if (showSub) EaKit.drawFitText(canvas, sub.uppercase(), cx, blockTop + dotH + gap, inner, p.label)
        }
    }

    companion object {
        private const val REFRESH_MS = 30_000L
    }
}
