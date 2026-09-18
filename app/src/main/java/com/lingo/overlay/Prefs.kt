package com.lingo.overlay

import android.content.Context
import android.content.SharedPreferences
import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/**
 * Small typed wrapper around SharedPreferences. Shared by the settings UI and
 * the accessibility service, which live in the same process.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("lingo", Context.MODE_PRIVATE)

    /** Whether the user has toggled translation on via the floating button. */
    var active: Boolean
        get() = sp.getBoolean(KEY_ACTIVE, false)
        set(v) = sp.edit().putBoolean(KEY_ACTIVE, v).apply()

    /**
     * The language everything is translated *into*, as an ML Kit language code.
     *
     * Defaults to whatever the phone is set to, which is almost always what the
     * user wants and means the app is useful before they open settings at all.
     * Falls back to English if the phone's language is one ML Kit cannot
     * produce.
     */
    var targetLanguage: String
        get() {
            val stored = sp.getString(KEY_TARGET_LANG, null)
            if (stored != null && TranslateLanguage.fromLanguageTag(stored) != null) return stored
            return deviceDefaultLanguage()
        }
        set(v) = sp.edit().putString(KEY_TARGET_LANG, v).apply()

    /** Bubble colour mode -- see [BubbleTheme]. */
    var bubbleTheme: BubbleTheme
        get() = BubbleTheme.fromKey(sp.getString(KEY_BUBBLE_THEME, BubbleTheme.AUTO.key))
        set(v) = sp.edit().putString(KEY_BUBBLE_THEME, v.key).apply()

    /**
     * Multiplier applied to the fitted text size, 0.75..1.25. A translation is
     * rarely the same length as its source, so the fitted size can land a shade
     * off WhatsApp's; this lets the user nudge it.
     */
    var textScale: Float
        get() = sp.getFloat(KEY_TEXT_SCALE, 1.0f)
        set(v) = sp.edit().putFloat(KEY_TEXT_SCALE, v.coerceIn(0.75f, 1.25f)).apply()

    /** Draws node outlines instead of patches and enables tree dumping. */
    var diagnostics: Boolean
        get() = sp.getBoolean(KEY_DIAGNOSTICS, false)
        set(v) = sp.edit().putBoolean(KEY_DIAGNOSTICS, v).apply()

    /**
     * When true the scanner ignores the "must be inside the conversation list"
     * rule and translates every text node on screen. Useful if WhatsApp changes
     * its layout and the normal heuristic stops matching.
     */
    var scanWholeScreen: Boolean
        get() = sp.getBoolean(KEY_WHOLE_SCREEN, false)
        set(v) = sp.edit().putBoolean(KEY_WHOLE_SCREEN, v).apply()

    /**
     * Restricts language-pack downloads to Wi-Fi. Off by default: a pack is
     * around 30 MB, but a user who has just tapped the translate button wants
     * it to work now, and silently doing nothing on mobile data reads as the
     * app being broken.
     */
    var wifiOnlyDownloads: Boolean
        get() = sp.getBoolean(KEY_WIFI_ONLY, false)
        set(v) = sp.edit().putBoolean(KEY_WIFI_ONLY, v).apply()

    /** Last known floating-button position, so it stays where the user put it. */
    var buttonX: Int
        get() = sp.getInt(KEY_BTN_X, -1)
        set(v) = sp.edit().putInt(KEY_BTN_X, v).apply()

    var buttonY: Int
        get() = sp.getInt(KEY_BTN_Y, -1)
        set(v) = sp.edit().putInt(KEY_BTN_Y, v).apply()

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        const val KEY_ACTIVE = "active"
        const val KEY_TARGET_LANG = "target_lang"
        const val KEY_BUBBLE_THEME = "bubble_theme"
        const val KEY_TEXT_SCALE = "text_scale"
        const val KEY_DIAGNOSTICS = "diagnostics"
        const val KEY_WHOLE_SCREEN = "whole_screen"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_BTN_X = "btn_x"
        const val KEY_BTN_Y = "btn_y"

        fun deviceDefaultLanguage(): String =
            TranslateLanguage.fromLanguageTag(Locale.getDefault().language)
                ?: TranslateLanguage.ENGLISH
    }
}

/**
 * A patch has to be opaque to hide the original text underneath, which means we
 * must know what colour the bubble is. We cannot sample the screen (that would
 * need screen capture, which this app deliberately avoids), so the colours come
 * from WhatsApp's own palette, picked off the system theme with a manual
 * override available.
 */
enum class BubbleTheme(val key: String, val label: String) {
    AUTO("auto", "Follow system theme"),
    DARK("dark", "Always dark"),
    LIGHT("light", "Always light");

    fun resolve(systemIsDark: Boolean): Palette = when (this) {
        AUTO -> if (systemIsDark) DARK_PALETTE else LIGHT_PALETTE
        DARK -> DARK_PALETTE
        LIGHT -> LIGHT_PALETTE
    }

    companion object {
        /** WhatsApp's dark theme: slate incoming bubble, deep green outgoing. */
        private val DARK_PALETTE = Palette(
            incomingBg = 0xFF202C33.toInt(),
            incomingFg = 0xFFE9EDEF.toInt(),
            outgoingBg = 0xFF005C4B.toInt(),
            outgoingFg = 0xFFE9EDEF.toInt()
        )

        /** WhatsApp's light theme: white incoming bubble, pale green outgoing. */
        private val LIGHT_PALETTE = Palette(
            incomingBg = 0xFFFFFFFF.toInt(),
            incomingFg = 0xFF111B21.toInt(),
            outgoingBg = 0xFFD9FDD3.toInt(),
            outgoingFg = 0xFF111B21.toInt()
        )

        fun fromKey(k: String?): BubbleTheme = entries.firstOrNull { it.key == k } ?: AUTO
    }
}

data class Palette(
    val incomingBg: Int,
    val incomingFg: Int,
    val outgoingBg: Int,
    val outgoingFg: Int
) {
    fun background(outgoing: Boolean) = if (outgoing) outgoingBg else incomingBg
    fun foreground(outgoing: Boolean) = if (outgoing) outgoingFg else incomingFg
}
