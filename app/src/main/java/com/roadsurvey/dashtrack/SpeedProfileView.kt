package com.roadsurvey.dashtrack

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil

/** Speed vs video-time chart with a draggable playhead and tag ticks. */
class SpeedProfileView(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {

    var fixes: List<Fix> = emptyList()
        set(v) {
            field = v
            val mx = v.maxOfOrNull { it.speedKmh } ?: 0f
            maxSpeed = maxOf(30f, (ceil(mx / 10f) * 10f))
            invalidate()
        }
    var tags: List<Tag> = emptyList()
    var durationMs: Long = 1L
    var cursorMs: Long = 0L
    var onSeek: ((Long) -> Unit)? = null

    private var maxSpeed = 30f
    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val red = Color.parseColor("#D32F2F")
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(45, 211, 47, 47); style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#555555"); strokeWidth = dp(1.5f); style = Paint.Style.STROKE }
    private val grid = Paint().apply { color = Color.parseColor("#E5E5E5"); strokeWidth = 1f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = dp(9f) }
    private val cursor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red; strokeWidth = dp(1.5f) }
    private val rail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#DDDDDD"); strokeWidth = dp(4f); strokeCap = Paint.Cap.ROUND }
    private val progress = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red; strokeWidth = dp(4f); strokeCap = Paint.Cap.ROUND }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red; style = Paint.Style.STROKE; strokeWidth = dp(2.5f) }
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val padL get() = dp(34f)
    private val padR get() = dp(12f)

    private fun xOf(t: Long): Float {
        val w = width - padL - padR
        return padL + (t.toFloat() / durationMs.coerceAtLeast(1)) * w
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val top = dp(12f)
        val bottom = height - dp(22f)
        val railY = height - dp(10f)
        fun yOf(s: Float) = bottom - (s / maxSpeed) * (bottom - top)

        // grid + labels
        val step = if (maxSpeed <= 60f) 10f else 20f
        var s = 0f
        while (s <= maxSpeed) {
            val y = yOf(s)
            c.drawLine(padL, y, width - padR, y, grid)
            c.drawText("${s.toInt()}", dp(4f), y + dp(3f), label)
            s += step
        }
        c.drawText("km/h", dp(4f), top - dp(2f), label)

        if (fixes.size >= 2) {
            val p = Path()
            val f = Path()
            fixes.forEachIndexed { i, fx ->
                val x = xOf(fx.tMs)
                val y = yOf(fx.speedKmh)
                if (i == 0) { p.moveTo(x, y); f.moveTo(x, bottom); f.lineTo(x, y) } else { p.lineTo(x, y); f.lineTo(x, y) }
            }
            f.lineTo(xOf(fixes.last().tMs), bottom)
            f.close()
            c.drawPath(f, fill)
            c.drawPath(p, line)
        } else {
            c.drawText("No GPS fixes recorded", padL + dp(8f), (top + bottom) / 2, label)
        }

        // tag ticks along the top
        for (t in tags) {
            tagPaint.color = tagColor(t.type)
            val x = xOf(t.tMs)
            val tri = Path().apply {
                moveTo(x - dp(4f), top - dp(8f)); lineTo(x + dp(4f), top - dp(8f)); lineTo(x, top); close()
            }
            c.drawPath(tri, tagPaint)
        }

        // playhead
        val cx = xOf(cursorMs)
        c.drawLine(cx, top - dp(4f), cx, bottom, cursor)
        c.drawLine(padL, railY, width - padR, railY, rail)
        c.drawLine(padL, railY, cx, railY, progress)
        c.drawCircle(cx, railY, dp(6f), knob)
        c.drawCircle(cx, railY, dp(6f), knobRing)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val frac = ((e.x - padL) / (width - padL - padR)).coerceIn(0f, 1f)
                val t = (frac * durationMs).toLong()
                cursorMs = t
                invalidate()
                onSeek?.invoke(t)
                return true
            }
            MotionEvent.ACTION_UP -> { performClick(); return true }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()
}
