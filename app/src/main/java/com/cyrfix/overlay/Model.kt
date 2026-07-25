package com.cyrfix.overlay

import android.graphics.Rect

/**
 * One piece of text we are going to repaint, plus the exact screen rectangle
 * TikTok drew it in.
 */
data class Patch(
    val text: String,
    val bounds: Rect,
    /** Debug-only: the class + view id the text came from. */
    val source: String = ""
)

/** Package names TikTok ships under. */
object TikTok {
    private val PACKAGES = setOf(
        "com.zhiliaoapp.musically",   // TikTok, most markets
        "com.ss.android.ugc.trill",   // TikTok, alternate distribution
        "com.ss.android.ugc.aweme"    // Douyin / aweme builds
    )

    fun isTikTok(pkg: CharSequence?): Boolean =
        pkg != null && pkg.toString() in PACKAGES
}

/**
 * Cheap Cyrillic test. Text with no Cyrillic in it is left completely alone --
 * there is nothing to fix, and repainting it would only risk misalignment.
 */
fun CharSequence.hasCyrillic(): Boolean {
    for (ch in this) {
        val c = ch.code
        // Cyrillic (0400-04FF) and Cyrillic Supplement (0500-052F).
        if (c in 0x0400..0x052F) return true
    }
    return false
}
