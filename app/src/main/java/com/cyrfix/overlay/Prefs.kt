package com.cyrfix.overlay

import android.content.Context
import android.content.SharedPreferences

/**
 * Small typed wrapper around SharedPreferences. Shared by the settings UI and
 * the accessibility service, which live in the same process.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("cyrfix", Context.MODE_PRIVATE)

    /** Whether the user has toggled correction on via the floating button. */
    var active: Boolean
        get() = sp.getBoolean(KEY_ACTIVE, false)
        set(v) = sp.edit().putBoolean(KEY_ACTIVE, v).apply()

    /** Backdrop mode -- see [Backdrop]. */
    var backdrop: Backdrop
        get() = Backdrop.fromKey(sp.getString(KEY_BACKDROP, Backdrop.AUTO.key))
        set(v) = sp.edit().putString(KEY_BACKDROP, v.key).apply()

    /**
     * Multiplier applied to the fitted text size, 0.75..1.25. Lets the user nudge
     * the replacement text if Noto's metrics land slightly off against TikTok's.
     */
    var textScale: Float
        get() = sp.getFloat(KEY_TEXT_SCALE, 1.0f)
        set(v) = sp.edit().putFloat(KEY_TEXT_SCALE, v.coerceIn(0.75f, 1.25f)).apply()

    /** Draws node outlines instead of patches and enables tree dumping. */
    var diagnostics: Boolean
        get() = sp.getBoolean(KEY_DIAGNOSTICS, false)
        set(v) = sp.edit().putBoolean(KEY_DIAGNOSTICS, v).apply()

    /**
     * When true the scanner ignores the "must be inside a scrollable container"
     * rule and repaints every Cyrillic text node on screen. Useful if TikTok
     * changes its comment sheet and the normal heuristic stops matching.
     */
    var scanWholeScreen: Boolean
        get() = sp.getBoolean(KEY_WHOLE_SCREEN, false)
        set(v) = sp.edit().putBoolean(KEY_WHOLE_SCREEN, v).apply()

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
        const val KEY_BACKDROP = "backdrop"
        const val KEY_TEXT_SCALE = "text_scale"
        const val KEY_DIAGNOSTICS = "diagnostics"
        const val KEY_WHOLE_SCREEN = "whole_screen"
        const val KEY_BTN_X = "btn_x"
        const val KEY_BTN_Y = "btn_y"
    }
}

/**
 * A patch has to be opaque to hide the glyphs underneath, which means we must
 * know what colour the comment sheet is. We cannot sample the screen (that
 * would need screen capture, which this app deliberately avoids), so the
 * colour is chosen from the system theme with a manual override available.
 */
enum class Backdrop(val key: String, val label: String) {
    AUTO("auto", "Follow system theme"),
    DARK("dark", "Always dark"),
    LIGHT("light", "Always light");

    fun resolve(systemIsDark: Boolean): Pair<Int, Int> = when (this) {
        AUTO -> if (systemIsDark) DARK_PAIR else LIGHT_PAIR
        DARK -> DARK_PAIR
        LIGHT -> LIGHT_PAIR
    }

    companion object {
        // TikTok's dark comment sheet, and plain white for the light theme.
        private val DARK_PAIR = 0xFF161823.toInt() to 0xFFFFFFFF.toInt()
        private val LIGHT_PAIR = 0xFFFFFFFF.toInt() to 0xFF161823.toInt()

        fun fromKey(k: String?): Backdrop = entries.firstOrNull { it.key == k } ?: AUTO
    }
}
