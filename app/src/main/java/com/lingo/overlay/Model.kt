package com.lingo.overlay

import android.graphics.Rect

/**
 * One message we are going to repaint, the translated string that replaces it,
 * and the exact screen rectangle WhatsApp drew the original in.
 */
data class Patch(
    /** The translated text. This is what gets painted. */
    val text: String,
    val bounds: Rect,
    /**
     * True for a bubble hugging the right edge -- WhatsApp's own messages.
     * Incoming and outgoing bubbles are different colours, and a patch has to
     * be opaque, so this decides which pair of colours it is painted with.
     */
    val outgoing: Boolean,
    /** Debug-only: the class + view id the text came from. */
    val source: String = ""
)

/**
 * A text node lifted out of the view tree, before translation.
 *
 * Kept around after a scan so the overlay can be rebuilt from the translation
 * cache when a pending translation lands, without walking the tree again --
 * a rebuild is a few map lookups, a rescan is hundreds of IPC round trips.
 */
data class RawText(
    val text: String,
    val bounds: Rect,
    val outgoing: Boolean,
    val source: String = ""
)

/** Package names WhatsApp ships under. */
object WhatsApp {
    private val PACKAGES = setOf(
        "com.whatsapp",      // WhatsApp Messenger
        "com.whatsapp.w4b"   // WhatsApp Business
    )

    fun isWhatsApp(pkg: CharSequence?): Boolean =
        pkg != null && pkg.toString() in PACKAGES
}

/**
 * Cheap triage, run before anything is handed to the translator.
 *
 * WhatsApp's message list is full of strings that are not messages -- clock
 * times, unread counters, tick marks, "1 of 3". Translating those would cost a
 * model call and produce a patch over UI chrome, so anything without at least
 * two letters in it is dropped here. It is a loop over a handful of characters,
 * which is orders of magnitude cheaper than the alternative.
 */
fun CharSequence.looksLikeMessageText(): Boolean {
    if (length > MAX_MESSAGE_CHARS) return false
    var letters = 0
    for (ch in this) {
        if (ch.isLetter()) {
            letters++
            if (letters >= 2) return true
        }
    }
    return false
}

/**
 * Upper bound on what we will translate. A node this long is not a chat bubble
 * on screen; it is almost always a concatenated content description, and
 * translating it would burn the model on text nobody is reading.
 */
const val MAX_MESSAGE_CHARS = 900
