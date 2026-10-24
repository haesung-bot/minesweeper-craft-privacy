package com.soundcatch.recorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** 지금 소리 크기(막대), 녹음 기준(빨간 선), 주변 소음(회색 선)을 보여 주는 막대 */
class LevelMeterView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var level: Double? = null
    private var threshold = MIN_DB
    private var floor = MIN_DB
    private var recording = false

    private val density = resources.displayMetrics.density
    private val radius = 6 * density
    private val track = RectF()
    private val fill = RectF()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33888888 }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE53935.toInt()
        strokeWidth = 3 * density
    }
    private val floorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9E9E9E.toInt()
        strokeWidth = 2 * density
    }

    fun update(level: Double?, threshold: Double, floor: Double, recording: Boolean) {
        this.level = level
        this.threshold = threshold
        this.floor = floor
        this.recording = recording
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (28 * density).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    private fun xOf(db: Double): Float {
        val t = ((db - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0.0, 1.0).toFloat()
        return track.left + t * track.width()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = 4 * density
        track.set(
            paddingLeft.toFloat(),
            paddingTop + inset,
            (width - paddingRight).toFloat(),
            height - paddingBottom - inset,
        )
        canvas.drawRoundRect(track, radius, radius, bgPaint)
        val lv = level ?: return
        barPaint.color = when {
            recording -> 0xFFE53935.toInt()
            lv >= threshold -> 0xFFFFA000.toInt()
            else -> 0xFF43A047.toInt()
        }
        fill.set(track.left, track.top, maxOf(track.left + radius * 2, xOf(lv)), track.bottom)
        canvas.drawRoundRect(fill, radius, radius, barPaint)
        val fx = xOf(floor)
        canvas.drawLine(fx, track.top, fx, track.bottom, floorPaint)
        val tx = xOf(threshold)
        canvas.drawLine(tx, track.top - inset, tx, track.bottom + inset, thresholdPaint)
    }

    companion object {
        private const val MIN_DB = -90.0
        private const val MAX_DB = 0.0
    }
}
