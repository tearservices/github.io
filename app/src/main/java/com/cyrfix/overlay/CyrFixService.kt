package com.cyrfix.overlay

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.io.File
import kotlin.math.abs

/**
 * Owns the floating button and the correction overlay, and drives rescans off
 * TikTok's accessibility events.
 *
 * Threading: every scan runs on a dedicated background thread because
 * fetching and walking a window's node tree is an IPC round trip that would
 * otherwise land on the main thread during a fling. Only the resulting patch
 * list is posted back to the UI thread.
 */
class CyrFixService : AccessibilityService(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private var button: FloatingToggleView? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var overlay: OverlayRenderView? = null

    @Volatile private var tiktokForeground = false
    @Volatile private var scanPending = false
    private var lastPatches: List<Patch> = emptyList()
    private var lastScanAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WindowManager::class.java)
        prefs = Prefs(this)
        prefs.registerListener(this)

        // Correction always starts off; the user opts in with the button.
        prefs.active = false

        bgThread = HandlerThread("cyrfix-scan").also {
            it.start()
            bgHandler = Handler(it.looper)
        }

        // The service can be enabled while TikTok is already in front, in which
        // case no window-state event is coming -- check directly.
        tiktokForeground = try {
            TikTok.isTikTok(rootInActiveWindow?.packageName)
        } catch (t: Throwable) {
            false
        }
        syncWindows()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val isTikTok = TikTok.isTikTok(event.packageName)

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            isTikTok != tiktokForeground
        ) {
            tiktokForeground = isTikTok
            main.post { syncWindows() }
        }

        // Everything past this line reads TikTok's content only. For any other
        // app we have already returned without touching its node tree.
        if (!isTikTok || !prefs.active) return

        scheduleScan(SCAN_DEBOUNCE_MS)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        try {
            prefs.unregisterListener(this)
        } catch (_: Throwable) {
        }
        main.post {
            removeOverlay()
            removeButton()
        }
        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        main.post {
            button?.isActive = prefs.active
            syncWindows()
            if (prefs.active) {
                // lastPatches belongs to the scan thread; reset it there rather
                // than racing the in-flight scan from the main thread.
                bgHandler?.post { lastPatches = emptyList() }
                scheduleScan(0)
            }
        }
    }

    // ---------------------------------------------------------------- windows

    private fun syncWindows() {
        if (tiktokForeground) addButton() else removeButton()

        if (tiktokForeground && prefs.active) {
            addOverlay()
        } else {
            removeOverlay()
        }
    }

    private fun addButton() {
        if (button != null) return
        val view = FloatingToggleView(
            context = this,
            onTap = {
                prefs.active = !prefs.active
                button?.isActive = prefs.active
                syncWindows()
                if (prefs.active) scheduleScan(0)
            },
            onMoved = { dx, dy ->
                val p = buttonParams ?: return@FloatingToggleView
                p.x += dx
                p.y += dy
                runCatching { wm.updateViewLayout(button, p) }
            },
            onMoveFinished = {
                buttonParams?.let {
                    prefs.buttonX = it.x
                    prefs.buttonY = it.y
                }
            }
        )
        view.isActive = prefs.active

        val screen = screenBounds()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.buttonX.takeIf { it >= 0 }
                ?: (screen.width() - (64 * resources.displayMetrics.density).toInt())
            y = prefs.buttonY.takeIf { it >= 0 } ?: (screen.height() / 3)
            applyCutoutMode()
        }

        if (addView(view, params)) {
            button = view
            buttonParams = params
        }
    }

    private fun removeButton() {
        button?.let { runCatching { wm.removeView(it) } }
        button = null
        buttonParams = null
    }

    private fun addOverlay() {
        if (overlay != null) return
        val view = OverlayRenderView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            applyCutoutMode()
        }

        if (addView(view, params)) {
            overlay = view
            // Keep the button above the overlay.
            button?.let { b ->
                buttonParams?.let { p -> runCatching { wm.removeView(b); wm.addView(b, p) } }
            }
        }
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
        lastPatches = emptyList()
    }

    private fun addView(view: android.view.View, params: WindowManager.LayoutParams): Boolean {
        return try {
            wm.addView(view, params)
            true
        } catch (t: Throwable) {
            // TYPE_ACCESSIBILITY_OVERLAY should always be permitted for a bound
            // accessibility service, but some OEM builds reject it. Fall back to
            // the classic overlay type, which needs the draw-over-apps grant.
            Log.w(TAG, "addView failed with type ${params.type}, retrying", t)
            params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            try {
                wm.addView(view, params)
                true
            } catch (t2: Throwable) {
                Log.e(TAG, "overlay window rejected", t2)
                false
            }
        }
    }

    /**
     * An accessibility service may post TYPE_ACCESSIBILITY_OVERLAY windows
     * without the "draw over other apps" grant, so the normal setup path needs
     * only one permission instead of two.
     */
    private fun overlayWindowType(): Int =
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    private fun WindowManager.LayoutParams.applyCutoutMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    // ----------------------------------------------------------------- scanning

    private fun scheduleScan(delayMs: Long) {
        val h = bgHandler ?: return
        if (scanPending) return
        scanPending = true
        h.postDelayed({ doScan() }, delayMs)
    }

    private fun doScan() {
        scanPending = false
        if (!prefs.active || !tiktokForeground) return

        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            null
        } ?: return

        try {
            val screen = screenBounds()
            val result = NodeScanner.scan(
                root = root,
                screen = screen,
                wholeScreen = prefs.scanWholeScreen,
                diagnostics = prefs.diagnostics
            )

            val now = SystemClock.uptimeMillis()
            val flinging = isFlinging(lastPatches, result.patches, now - lastScanAt)
            lastPatches = result.patches
            lastScanAt = now

            val (bg, fg) = prefs.backdrop.resolve(systemIsDark())
            val scale = prefs.textScale
            val diag = prefs.diagnostics

            main.post {
                val v = overlay ?: return@post
                if (flinging) v.clear() else v.submit(result.patches, bg, fg, scale, diag)
            }

            // While the list is moving fast we intentionally draw nothing, so we
            // must keep scanning to notice when it settles again.
            if (flinging) scheduleScan(SETTLE_DELAY_MS)

            result.dump?.let { writeDump(it) }
        } catch (t: Throwable) {
            Log.e(TAG, "scan failed", t)
        } finally {
            root.recycleCompat()
        }
    }

    /**
     * A patch drawn from a scan taken 20ms ago is 40px out of place during a
     * hard fling, which reads as smeared double text. Rather than show that, we
     * measure how far a known string travelled between two scans and hide the
     * overlay entirely while the list is moving quickly. Slow, deliberate
     * scrolling stays well under the threshold and tracks normally.
     */
    private fun isFlinging(previous: List<Patch>, current: List<Patch>, dtMs: Long): Boolean {
        if (dtMs !in 1..400 || previous.isEmpty() || current.isEmpty()) return false
        val byText = HashMap<String, Int>(previous.size)
        for (p in previous) byText[p.text] = p.bounds.top
        for (p in current) {
            val before = byText[p.text] ?: continue
            val speed = abs(p.bounds.top - before) / dtMs.toFloat()
            return speed > FLING_PX_PER_MS
        }
        return false
    }

    private fun writeDump(text: String) {
        try {
            val dir = File(filesDir, "dumps").apply { mkdirs() }
            File(dir, DUMP_FILE).writeText(text)
        } catch (t: Throwable) {
            Log.w(TAG, "could not write dump", t)
        }
    }

    private fun systemIsDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun screenBounds(): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect(wm.maximumWindowMetrics.bounds)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }

    companion object {
        private const val TAG = "CyrFix"

        /** At most one tree walk per this many ms, even mid-fling. */
        private const val SCAN_DEBOUNCE_MS = 60L

        /** Follow-up scan used to notice that a fling has stopped. */
        private const val SETTLE_DELAY_MS = 110L

        /** ~1200 px/s. Above this the overlay hides instead of lagging. */
        private const val FLING_PX_PER_MS = 1.2f

        const val DUMP_FILE = "last_dump.txt"
    }
}
