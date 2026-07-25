package com.cyrfix.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.res.ResourcesCompat
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The draggable circular button that sits over TikTok. Tap toggles correction,
 * drag repositions it (and the position is remembered).
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
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 15f * density
        typeface = ResourcesCompat.getFont(context, R.font.noto_sans_bold) ?: Typeface.DEFAULT_BOLD
    }

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
        ringPaint.color = if (isActive) ACTIVE_RING else IDLE_RING
        labelPaint.color = if (isActive) Color.WHITE else 0xFFDDDDDD.toInt()

        canvas.drawCircle(cx, cy, r, bgPaint)
        canvas.drawCircle(cx, cy, r, ringPaint)

        // Cyrillic "Aa" -- shown in the very font the button switches text to.
        val fm = labelPaint.fontMetrics
        canvas.drawText("Аа", cx, cy - (fm.ascent + fm.descent) / 2f, labelPaint)
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
        val ACTIVE_BG = 0xF2FE2C55.toInt()   // TikTok red, so "on" is unmistakable
        val IDLE_RING = 0x66FFFFFF
        val ACTIVE_RING = 0xFFFFFFFF.toInt()
    }
}
