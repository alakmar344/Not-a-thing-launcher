package com.nothinglauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import java.util.Calendar

class DotMatrixClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#222222")
        style = Paint.Style.FILL
    }

    private val handler = Handler(Looper.getMainLooper())
    private var currentTime = ""

    // 5x7 dot matrix font patterns for digits 0-9 and colon
    private val digitPatterns = mapOf(
        '0' to arrayOf(
            intArrayOf(0,1,1,1,0),
            intArrayOf(1,0,0,0,1),
            intArrayOf(1,0,0,1,1),
            intArrayOf(1,0,1,0,1),
            intArrayOf(1,1,0,0,1),
            intArrayOf(1,0,0,0,1),
            intArrayOf(0,1,1,1,0)
        ),
        '1' to arrayOf(
            intArrayOf(0,0,1,0,0),
            intArrayOf(0,1,1,0,0),
            intArrayOf(0,0,1,0,0),
            intArrayOf(0,0,1,0,0),
            intArrayOf(0,0,1,0,0),
            intArrayOf(0,0,1,0,0),
            intArrayOf(0,1,1,1,0)
        ),
        '2' to arrayOf(
            intArrayOf(0,1,1,1,0),
            intArrayOf(1,0,0,0,1),
            intArrayOf(0,0,0,0,1),
            intArrayOf(0,0,0,1,0),
            intArrayOf(0,0,1,0,0),
            intArrayOf(0,1,0,0,0),
            intArrayOf(1,1,1,1,1)
        ),
        '3' to arrayOf(
            intArrayOf(1,1,1,1,0),
            intArrayOf(0,0,0,0,1),
            intArrayOf(0,0,0,0,1),
            intArrayOf(0,1,1,1,0),
            intArrayOf(0,0,0,0,1),
            intArrayOf(0,0,0,0,1),
            intArrayOf(1,1,1,1,0)
        ),
        '4' to arrayOf(
            intArrayOf(0,0,0,1,0),
            intArrayOf(0,0,1,1,0),
            intArrayOf(0,1,0,1,0),
            intArrayOf(1,0,0,1,0),
            intArrayOf(1,1,1,1,1),
            intArrayOf(0,0,0,1,0),
            intArrayOf(0,0,0,1,0)
        ),
        '5' to arrayOf(
            intArrayOf(1,1,1,1,1),
            intArrayOf(1,0,0,0,0),
            intArrayOf(1,0,0,0,0),
            intArrayOf(1,1,1,1,0),
            intArrayOf(0,0,0,0,1),
            intArrayOf(0,0,0,0,1),
            intArrayOf(1,1,1,1,0)
        ),
        '6' to arrayOf(
            intArrayOf(0,1,1,1,0),
            intArrayOf(1,0,0,0,0),
            intArrayOf(1,0,0,0,0),
            intArrayOf(1,1,1,1,0),
            intArrayOf(1,0,0,0,1),
            intArrayOf(1,0,0,0,1),
            intArrayOf(0,1,1,1,0)
        ),
        '7' to arrayOf(
            intArrayOf(1,1,1,1,1),
            intArrayOf(0,0,0,0,1),
            intArrayOf(0,0,0,1,0),
            intArrayOf(0,0,1,0,0),
            intArrayOf(0,1,0,0,0),
            intArrayOf(0,1,0,0,0),
            intArrayOf(0,1,0,0,0)
        ),
        '8' to arrayOf(
            intArrayOf(0,1,1,1,0),
            intArrayOf(1,0,0,0,1),
            intArrayOf(1,0,0,0,1),
            intArrayOf(0,1,1,1,0),
            intArrayOf(1,0,0,0,1),
            intArrayOf(1,0,0,0,1),
            intArrayOf(0,1,1,1,0)
        ),
        '9' to arrayOf(
            intArrayOf(0,1,1,1,0),
            intArrayOf(1,0,0,0,1),
            intArrayOf(1,0,0,0,1),
            intArrayOf(0,1,1,1,1),
            intArrayOf(0,0,0,0,1),
            intArrayOf(0,0,0,0,1),
            intArrayOf(0,1,1,1,0)
        ),
        ':' to arrayOf(
            intArrayOf(0,0,0),
            intArrayOf(0,1,0),
            intArrayOf(0,1,0),
            intArrayOf(0,0,0),
            intArrayOf(0,1,0),
            intArrayOf(0,1,0),
            intArrayOf(0,0,0)
        )
    )

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(updateRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(updateRunnable)
    }

    private fun updateTime() {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        currentTime = String.format("%02d:%02d", hour, minute)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (currentTime.isEmpty()) {
            updateTime()
        }
        val chars = currentTime.toList()
        var totalCols = 0
        for (c in chars) {
            totalCols += if (c == ':') 3 else 5
        }
        totalCols += (chars.size - 1)

        val dotSize = height / 9f
        val dotSpacing = dotSize * 1.4f
        val totalWidth = totalCols * dotSpacing
        var startX = (width.toFloat() - totalWidth) / 2f
        val startY = (height.toFloat() - 7 * dotSpacing) / 2f

        for (c in chars) {
            val pattern = digitPatterns[c] ?: continue
            val colCount = pattern[0].size
            for (row in pattern.indices) {
                for (col in 0 until colCount) {
                    val x = startX + col * dotSpacing + dotSpacing / 2
                    val y = startY + row * dotSpacing + dotSpacing / 2
                    val paint = if (pattern[row][col] == 1) activePaint else inactivePaint
                    canvas.drawCircle(x, y, dotSize / 2f, paint)
                }
            }
            startX += colCount * dotSpacing + dotSpacing
        }
    }
}
