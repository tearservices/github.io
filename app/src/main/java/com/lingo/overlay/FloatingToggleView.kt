package com.lingo.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The draggable circular button that sits over WhatsApp. Tap toggles
 * translation, drag repositions it (and the position is remembered).
 */
@SuppressLint("ViewConstructor")
class FloatingToggleView(
    context: Context,
    private val onTap: () -> Unit,
    private val onMoved: (dx: Int, dy: Int) -> Unit,
    private val onMoveFinished: () -> Unit
) : View(context) {

    var isActive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * True while a language pack is downloading. Shown as a colour change rather
     * than a spinner on purpose -- an animated button would invalidate itself
     * every frame, on top of the app whose smoothness is the whole point.
     */
    var isBusy: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val meridian = RectF()

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (SIZE_DP * density).roundToInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = cx - ringPaint.strokeWidth

        bgPaint.color = if (isActive) ACTIVE_BG else IDLE_BG
        ringPaint.color = when {
            isBusy -> BUSY_RING
            isActive -> ACTIVE_RING
            else -> IDLE_RING
        }
        glyphPaint.color = if (isActive) 0xFFFFFFFF.toInt() else 0xFFDDDDDD.toInt()

        canvas.drawCircle(cx, cy, r, bgPaint)
        canvas.drawCircle(cx, cy, r, ringPaint)
        drawGlobe(canvas, cx, cy, r * 0.52f)
    }

    /** A globe: outline, one meridian, and two latitude chords. */
    private fun drawGlobe(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        canvas.drawCircle(cx, cy, r, glyphPaint)

        meridian.set(cx - r * 0.48f, cy - r, cx + r * 0.48f, cy + r)
        canvas.drawOval(meridian, glyphPaint)

        canvas.drawLine(cx - r, cy, cx + r, cy, glyphPaint)

        // Chord half-width at height h on a circle of radius r.
        val h = r * 0.55f
        val halfWidth = sqrt(r * r - h * h)
        canvas.drawLine(cx - halfWidth, cy - h, cx + halfWidth, cy - h, glyphPaint)
        canvas.drawLine(cx - halfWidth, cy + h, cx + halfWidth, cy + h, glyphPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastRawX
                val dy = event.rawY - lastRawY
                if (!dragging &&
                    (abs(event.rawX - downRawX) > slop || abs(event.rawY - downRawY) > slop)
                ) {
                    dragging = true
                }
                if (dragging) {
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    onMoved(dx.roundToInt(), dy.roundToInt())
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) onMoveFinished() else onTap()
                dragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) onMoveFinished()
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private companion object {
        const val SIZE_DP = 46f
        val IDLE_BG = 0xB3000000.toInt()
        val ACTIVE_BG = 0xF225D366.toInt()   // WhatsApp green, so "on" is unmistakable
        val IDLE_RING = 0x66FFFFFF
        val ACTIVE_RING = 0xFFFFFFFF.toInt()
        val BUSY_RING = 0xFFFFC107.toInt()   // amber while a language pack downloads
    }
}
