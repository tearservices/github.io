package com.lingo.overlay

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
import kotlin.math.min

/**
 * Full-screen, touch-transparent overlay that paints translated text on top of
 * WhatsApp's message bubbles.
 *
 * The only genuinely performance-sensitive part of this app is here. Text layout
 * is expensive and scrolling changes every rectangle's y coordinate sixty times
 * a second -- so layouts are cached against the text and the *size* of the box,
 * never its position. Scrolling therefore reuses cached layouts and costs
 * nothing but a canvas translate.
 */
@SuppressLint("ViewConstructor")
class OverlayRenderView(context: Context) : View(context) {

    private var patches: List<Patch> = emptyList()
    private var palette: Palette = BubbleTheme.DARK.resolve(true)
    private var textScale: Float = 1f
    private var diagnostics: Boolean = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val typeface: Typeface by lazy {
        ResourcesCompat.getFont(context, R.font.noto_sans_regular) ?: Typeface.SANS_SERIF
    }

    private val density = resources.displayMetrics.density
    private val cornerRadius = 3f * density

    /**
     * Below this a translation is technically visible and practically unreadable.
     * When shrinking to fit would cross it, the patch grows downward instead --
     * see [render].
     */
    private val minReadablePx = 11f * density

    private val layoutCache = object : LruCache<String, Rendered>(160) {}

    private class Rendered(val layout: StaticLayout, val boxHeight: Int)

    init {
        setWillNotDraw(false)
        // No background: every pixel we do not explicitly paint stays WhatsApp's.
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun submit(
        newPatches: List<Patch>,
        newPalette: Palette,
        scale: Float,
        diag: Boolean
    ) {
        val styleChanged =
            newPalette != palette || scale != textScale || diag != diagnostics

        patches = newPatches
        palette = newPalette
        textScale = scale
        diagnostics = diag
        if (styleChanged) layoutCache.evictAll()
        invalidate()
    }

    fun clear() {
        if (patches.isEmpty()) return
        patches = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val list = patches
        if (list.isEmpty()) return

        if (diagnostics) {
            for (p in list) {
                // Incoming red, outgoing blue -- makes the side detection, and so
                // the bubble colour a patch would use, visible at a glance.
                outlinePaint.color = if (p.outgoing) Color.BLUE else Color.RED
                canvas.drawRect(p.bounds, outlinePaint)
            }
            return
        }

        for (p in list) {
            val w = p.bounds.width()
            val h = p.bounds.height()
            if (w <= 0 || h <= 0) continue

            val rendered = renderFor(p.text, w, h, p.outgoing) ?: continue

            // Cover the original message first.
            fillPaint.color = palette.background(p.outgoing)
            val top = p.bounds.top.toFloat()
            val bottom = top + rendered.boxHeight
            canvas.drawRoundRect(
                p.bounds.left.toFloat(),
                top,
                p.bounds.right.toFloat(),
                bottom,
                cornerRadius,
                cornerRadius,
                fillPaint
            )

            val save = canvas.save()
            canvas.clipRect(p.bounds.left.toFloat(), top, p.bounds.right.toFloat(), bottom)
            // Centre vertically inside the patch so the replacement sits on
            // roughly the same baseline as what it replaced.
            val dy = top + ((rendered.boxHeight - rendered.layout.height) / 2f).coerceAtLeast(0f)
            canvas.translate(p.bounds.left.toFloat(), dy)
            rendered.layout.draw(canvas)
            canvas.restoreToCount(save)
        }
    }

    private fun renderFor(text: String, width: Int, height: Int, outgoing: Boolean): Rendered? {
        val key = "${if (outgoing) 'o' else 'i'}\u0000$width\u0000$height\u0000$text"
        layoutCache.get(key)?.let { return it }

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = this@OverlayRenderView.typeface
            color = palette.foreground(outgoing)
        }
        val rendered = render(text, width, height, paint)
        layoutCache.put(key, rendered)
        return rendered
    }

    /**
     * Fits the translation into the box WhatsApp used for the original.
     *
     * A translation is almost never the same length as its source, so this is
     * the one place where replacing text in place gets awkward. The size is
     * binary-searched down until the wrapped layout fits, which keeps line
     * breaks landing close to where the covered text had them -- but only down
     * to [minReadablePx]. A German rendering of a terse Russian line can be half
     * again as long, and shrinking it to fit would produce something nobody can
     * read. Past that point the patch grows downward into the bubble's padding
     * instead, capped so it can never swallow the message below it.
     */
    private fun render(text: String, width: Int, height: Int, paint: TextPaint): Rendered {
        var lo = MIN_TEXT_PX
        var hi = height.toFloat().coerceIn(MIN_TEXT_PX, MAX_TEXT_PX)
        var best = MIN_TEXT_PX

        repeat(9) {
            val mid = (lo + hi) / 2f
            paint.textSize = mid
            if (buildLayout(text, paint, width).height <= height) {
                best = mid
                lo = mid
            } else {
                hi = mid
            }
        }

        var size = (best * textScale).coerceIn(MIN_TEXT_PX, MAX_TEXT_PX)
        paint.textSize = size
        var layout = buildLayout(text, paint, width)

        if (size < minReadablePx && layout.height > height) {
            size = minReadablePx
            paint.textSize = size
            layout = buildLayout(text, paint, width)
        }

        val maxBox = (height * MAX_GROW).toInt()
        val boxHeight = min(maxOf(layout.height, height), maxOf(maxBox, height))
        return Rendered(layout, boxHeight)
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

        /**
         * How far a patch may grow past the original text box. WhatsApp leaves
         * padding and a timestamp row under the message, so there is real slack
         * here, but not unlimited -- beyond this the patch would start covering
         * the next bubble.
         */
        const val MAX_GROW = 1.6f
    }
}
