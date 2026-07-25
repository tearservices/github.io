package com.cyrfix.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import android.view.View
import androidx.core.content.res.ResourcesCompat

/**
 * Full-screen, touch-transparent overlay that paints corrected text on top of
 * TikTok's comments.
 *
 * The only genuinely performance-sensitive part of this app is here. Text
 * layout is expensive and scrolling changes every rectangle's y coordinate
 * sixty times a second -- so layouts are cached against the text and the *size*
 * of the box, never its position. Scrolling therefore reuses cached layouts and
 * costs nothing but a canvas translate.
 */
@SuppressLint("ViewConstructor")
class OverlayRenderView(context: Context) : View(context) {

    private var patches: List<Patch> = emptyList()
    private var backdropColor: Int = Color.BLACK
    private var textColor: Int = Color.WHITE
    private var textScale: Float = 1f
    private var diagnostics: Boolean = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.RED
    }

    private val typeface: Typeface by lazy {
        ResourcesCompat.getFont(context, R.font.noto_sans_regular) ?: Typeface.SANS_SERIF
    }

    private val cornerRadius = 2f * resources.displayMetrics.density
    private val layoutCache = object : LruCache<String, StaticLayout>(160) {}

    init {
        setWillNotDraw(false)
        // No background: every pixel we do not explicitly paint stays TikTok's.
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun submit(
        newPatches: List<Patch>,
        backdrop: Int,
        foreground: Int,
        scale: Float,
        diag: Boolean
    ) {
        val styleChanged =
            backdrop != backdropColor || foreground != textColor ||
                scale != textScale || diag != diagnostics

        patches = newPatches
        backdropColor = backdrop
        textColor = foreground
        textScale = scale
        diagnostics = diag
        if (styleChanged) layoutCache.evictAll()
        invalidate()
    }

    fun clear() {
        patches = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val list = patches
        if (list.isEmpty()) return

        if (diagnostics) {
            for (p in list) {
                canvas.drawRect(p.bounds, outlinePaint)
            }
            return
        }

        fillPaint.color = backdropColor
        for (p in list) {
            val w = p.bounds.width()
            val h = p.bounds.height()
            if (w <= 0 || h <= 0) continue

            val layout = layoutFor(p.text, w, h) ?: continue

            // Cover the badly-rendered original first.
            canvas.drawRoundRect(
                p.bounds.left.toFloat(),
                p.bounds.top.toFloat(),
                p.bounds.right.toFloat(),
                p.bounds.bottom.toFloat(),
                cornerRadius,
                cornerRadius,
                fillPaint
            )

            val save = canvas.save()
            canvas.clipRect(p.bounds)
            // Centre vertically inside the original box so replacement text sits
            // on roughly the same baseline as what it replaced.
            val dy = p.bounds.top + ((h - layout.height) / 2f).coerceAtLeast(0f)
            canvas.translate(p.bounds.left.toFloat(), dy)
            layout.draw(canvas)
            canvas.restoreToCount(save)
        }
    }

    private fun layoutFor(text: String, width: Int, height: Int): StaticLayout? {
        val key = "$width\u0000$height\u0000$text"
        layoutCache.get(key)?.let { return it }

        val size = fitTextSize(text, width, height)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = this@OverlayRenderView.typeface
            color = textColor
            textSize = size
        }
        val layout = buildLayout(text, paint, width)
        layoutCache.put(key, layout)
        return layout
    }

    /**
     * Binary-searches the largest text size whose wrapped layout still fits the
     * rectangle TikTok used. Because we reuse the original box, line breaks land
     * in nearly the same places as the text being covered.
     */
    private fun fitTextSize(text: String, width: Int, height: Int): Float {
        val measure = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = this@OverlayRenderView.typeface }

        var lo = MIN_TEXT_PX
        var hi = height.toFloat().coerceIn(MIN_TEXT_PX, MAX_TEXT_PX)
        var best = MIN_TEXT_PX

        repeat(9) {
            val mid = (lo + hi) / 2f
            measure.textSize = mid
            val fits = buildLayout(text, measure, width).height <= height
            if (fits) {
                best = mid
                lo = mid
            } else {
                hi = mid
            }
        }
        return (best * textScale).coerceIn(MIN_TEXT_PX, MAX_TEXT_PX)
    }

    private fun buildLayout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()

    private companion object {
        const val MIN_TEXT_PX = 8f
        const val MAX_TEXT_PX = 96f
    }
}
