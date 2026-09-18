package com.lingo.overlay

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
import android.widget.Toast
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Owns the floating button and the translation overlay, and drives rescans off
 * WhatsApp's accessibility events.
 *
 * Threading: every scan runs on a dedicated background thread, because fetching
 * and walking a window's node tree is hundreds of IPC round trips that would
 * otherwise land on the main thread mid-fling. Translation is asynchronous on
 * top of that and never blocks the scan -- see [TranslationEngine]. Only the
 * finished patch list is posted back to the UI thread.
 */
class LingoService : AccessibilityService(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private var button: FloatingToggleView? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var overlay: OverlayRenderView? = null

    @Volatile private var engine: TranslationEngine? = null

    @Volatile private var whatsappForeground = false
    @Volatile private var scanPending = false
    @Volatile private var rebuildPending = false

    /**
     * Text nodes from the last scan, kept so the overlay can be rebuilt when a
     * pending translation lands. A rebuild is a handful of hash lookups; a
     * rescan is a full tree walk, and there is no reason to pay for one when
     * nothing on screen has moved.
     */
    private var lastRaw: List<RawText> = emptyList()
    private var lastScanAt = 0L

    /** Hash of what the overlay is currently showing, so we never redraw a no-op. */
    private var lastSignature = SIGNATURE_CLEARED

    /** Last busy state pushed to the button, so it is not re-posted every scan. */
    @Volatile private var lastBusy = false

    /** Conversation region from the previous scan, used to prune the next walk. */
    private var regionHint: Rect? = null

    /** How long the last tree walk actually took; drives the scan cadence. */
    @Volatile private var lastScanDurationMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WindowManager::class.java)
        prefs = Prefs(this)
        prefs.registerListener(this)

        // Translation always starts off; the user opts in with the button.
        prefs.active = false

        bgThread = HandlerThread("lingo-scan").also {
            it.start()
            bgHandler = Handler(it.looper)
        }

        engine = TranslationEngine(
            onTranslationsArrived = { scheduleRebuild() },
            onStatus = { status -> main.post { showStatus(status) } }
        )
        syncEngineSettings()

        // The service can be enabled while WhatsApp is already in front, in
        // which case no window-state event is coming -- check directly.
        whatsappForeground = try {
            WhatsApp.isWhatsApp(rootInActiveWindow?.packageName)
        } catch (t: Throwable) {
            false
        }
        syncWindows()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // An exception escaping this callback takes the whole accessibility
        // service down with it, and Android will not bring it back without the
        // user re-enabling it by hand. Nothing in here is worth that.
        try {
            handleEvent(event)
        } catch (t: Throwable) {
            Log.e(TAG, "event handling failed", t)
        }
    }

    private fun handleEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        val isWhatsApp = WhatsApp.isWhatsApp(pkg)

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            updateForeground(isWhatsApp, pkg)
        }

        // Everything past this line reads WhatsApp's content only. For any other
        // app we have already returned without touching its node tree.
        if (!isWhatsApp || !prefs.active) return

        scheduleScan(nextScanDelay())
    }

    /**
     * Decides whether WhatsApp is still the app in front.
     *
     * The naive version of this -- "any window state change from another package
     * means WhatsApp left" -- is wrong, and visibly so: the status bar, the
     * keyboard, toasts, volume popups and system dialogs all raise window state
     * changes while WhatsApp is very much still in front. On a messaging app the
     * keyboard alone would do it constantly. Transient system windows are
     * ignored outright.
     */
    private fun updateForeground(isWhatsApp: Boolean, pkg: String?) {
        val next = when {
            isWhatsApp -> true
            isTransientWindow(pkg) -> return
            else -> {
                // A single stray event is not proof WhatsApp left. Confirm
                // against the window actually in front before tearing anything
                // down; if that cannot be read right now, keep the current state
                // rather than guessing.
                val active = activeWindowPackage() ?: return
                WhatsApp.isWhatsApp(active)
            }
        }
        if (next != whatsappForeground) {
            whatsappForeground = next
            main.post { syncWindows() }
        }
    }

    /**
     * Windows that appear over WhatsApp without replacing it.
     *
     * Our own overlays matter most here. Adding the button creates a window,
     * which raises a window state change naming this package -- which, read
     * naively, says "WhatsApp is gone". That removed the button, which let
     * WhatsApp raise another event, which added it back: a self-sustaining
     * flicker loop that ended with no button at all.
     */
    private fun isTransientWindow(pkg: String?): Boolean =
        pkg == null ||
            pkg == packageName ||
            pkg == "android" ||
            pkg.startsWith("com.android.systemui") ||
            pkg.contains("inputmethod") ||
            pkg.endsWith(".ime")

    private fun activeWindowPackage(): String? =
        try {
            rootInActiveWindow?.packageName?.toString()
        } catch (t: Throwable) {
            null
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
        engine?.close()
        engine = null
        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        // Dragging the button rewrites its position on every gesture end. That
        // is not a reason to rebuild windows or kick off a scan.
        if (key == Prefs.KEY_BTN_X || key == Prefs.KEY_BTN_Y) return

        main.post {
            button?.isActive = prefs.active
            syncWindows()
            syncEngineSettings()
            if (prefs.active) {
                // lastRaw belongs to the scan thread; reset it there rather than
                // racing the in-flight scan from the main thread.
                bgHandler?.post {
                    lastRaw = emptyList()
                    lastSignature = SIGNATURE_CLEARED
                }
                scheduleScan(0)
            }
        }
    }

    private fun syncEngineSettings() {
        val e = engine ?: return
        e.wifiOnly = prefs.wifiOnlyDownloads
        e.setTarget(prefs.targetLanguage)
    }

    private fun showStatus(status: TranslationEngine.Status) {
        val text = when (status) {
            is TranslationEngine.Status.Downloading ->
                getString(R.string.pack_downloading, displayName(status.language))
            is TranslationEngine.Status.Ready ->
                getString(R.string.pack_ready, displayName(status.language))
            is TranslationEngine.Status.Failed ->
                getString(R.string.pack_failed, displayName(status.language))
        }
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        val busy = engine?.isDownloading == true
        lastBusy = busy
        button?.isBusy = busy
    }

    // ---------------------------------------------------------------- windows

    private fun syncWindows() {
        try {
            if (whatsappForeground) addButton() else removeButton()

            if (whatsappForeground && prefs.active) {
                addOverlay()
            } else {
                removeOverlay()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "window sync failed", t)
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
        view.isBusy = engine?.isDownloading == true

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
            // A new overlay starts blank, so whatever the scan thread thinks is
            // on screen no longer is. Clear the signature or the next emit would
            // decide nothing had changed and leave this one empty.
            bgHandler?.post { lastSignature = SIGNATURE_CLEARED }
            // Re-add the button so it stacks above the overlay. Handing the same
            // View instance straight back to addView after removeView is not
            // reliable -- the removal is still in flight, so the add throws and
            // the button silently disappears. Build a fresh one instead.
            if (button != null) {
                removeButton()
                addButton()
            }
        }
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
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

    // ---------------------------------------------------------------- scanning

    private fun scheduleScan(delayMs: Long) {
        val h = bgHandler ?: return
        if (scanPending) return
        scanPending = true
        h.postDelayed({ doScan() }, delayMs)
    }

    /**
     * Repaint from the translation cache without touching the view tree.
     *
     * Translations arrive in bursts -- six at a time, out of order, tens of
     * milliseconds apart. Repainting on each one would be six layout passes for
     * one visual change, so they are coalesced into a single rebuild.
     */
    private fun scheduleRebuild() {
        val h = bgHandler ?: return
        if (rebuildPending) return
        rebuildPending = true
        h.postDelayed({
            rebuildPending = false
            rebuild()
        }, REBUILD_DELAY_MS)
    }

    /**
     * Paces scans against what they actually cost.
     *
     * A fixed cadence was the wrong model. A tree walk is hundreds of IPC round
     * trips into WhatsApp's process, and on a long conversation one can take far
     * longer than the interval -- so scans ran effectively back to back,
     * saturating the binder and making WhatsApp's own scrolling stutter. Leaving
     * roughly half the time idle keeps the app responsive, and the ceiling
     * guarantees the overlay still refreshes at least once a second no matter
     * how slow the walk gets.
     */
    private fun nextScanDelay(): Long =
        (lastScanDurationMs * 3 / 2).coerceIn(SCAN_MIN_INTERVAL_MS, SCAN_MAX_INTERVAL_MS)

    private fun doScan() {
        scanPending = false
        if (!prefs.active || !whatsappForeground) return

        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            null
        } ?: return

        try {
            val screen = screenBounds()
            val startedAt = SystemClock.uptimeMillis()
            val result = NodeScanner.scan(
                root = root,
                screen = screen,
                wholeScreen = prefs.scanWholeScreen,
                diagnostics = prefs.diagnostics,
                hintRegion = if (prefs.scanWholeScreen) null else regionHint
            )
            lastScanDurationMs = SystemClock.uptimeMillis() - startedAt

            // Keep the hint only while it is actually producing results. If a
            // scan comes back empty the conversation has probably been left, so
            // fall back to a full-screen walk next time rather than staying
            // pinned to a region that no longer holds anything.
            regionHint = if (result.texts.isEmpty()) null else result.region

            val now = SystemClock.uptimeMillis()
            val flinging = isFlinging(lastRaw, result.texts, now - lastScanAt)
            lastRaw = result.texts
            lastScanAt = now

            emit(flinging)

            // While the list is moving fast we intentionally draw nothing, so we
            // must keep scanning to notice when it settles again.
            if (flinging) scheduleScan(SETTLE_DELAY_MS)

            result.dump?.let {
                writeDump(
                    "scan took ${lastScanDurationMs}ms, next scan in ${nextScanDelay()}ms\n" +
                        "target language: ${prefs.targetLanguage}\n" +
                        "region hint in use: ${regionHint?.toShortString() ?: "<none, full screen walk>"}\n\n" + it
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "scan failed", t)
        } finally {
            root.recycleCompat()
        }
    }

    private fun rebuild() {
        if (!prefs.active || !whatsappForeground) return
        if (lastRaw.isEmpty()) return
        emit(flinging = false)
    }

    /**
     * Turns the last scan's text nodes into patches and hands them to the
     * overlay, skipping the hop entirely when nothing would change.
     *
     * Only messages whose translation is already cached become patches. One that
     * is still being translated is left exactly as WhatsApp drew it, which is
     * why a message never flashes as a blank coloured box before its text
     * appears.
     */
    private fun emit(flinging: Boolean) {
        val e = engine
        val diagnostics = prefs.diagnostics

        val patches = when {
            flinging -> emptyList()
            // In diagnostic mode we outline what the scanner saw, so nothing is
            // translated -- no point spending the models on a debugging pass.
            diagnostics -> lastRaw.map { Patch(it.text, it.bounds, it.outgoing, it.source) }
            e == null -> emptyList()
            else -> lastRaw.mapNotNull { raw ->
                e.translate(raw.text)?.let { Patch(it, raw.bounds, raw.outgoing, raw.source) }
            }
        }

        // Pushed ahead of the early return below: a language pack can start or
        // finish downloading during a stretch where the overlay itself has not
        // changed at all, and the button still has to say so.
        val busy = e?.isDownloading == true
        if (busy != lastBusy) {
            lastBusy = busy
            main.post { button?.isBusy = busy }
        }

        val signature = if (patches.isEmpty()) SIGNATURE_CLEARED else signatureOf(patches)
        if (signature == lastSignature) return
        lastSignature = signature

        val palette = prefs.bubbleTheme.resolve(systemIsDark())
        val scale = prefs.textScale

        main.post {
            val v = overlay ?: return@post
            if (patches.isEmpty()) v.clear() else v.submit(patches, palette, scale, diagnostics)
        }
    }

    private fun signatureOf(patches: List<Patch>): Int {
        var h = 17
        for (p in patches) {
            h = h * 31 + p.text.hashCode()
            h = h * 31 + p.bounds.top
            h = h * 31 + p.bounds.left
            h = h * 31 + p.bounds.right
            h = h * 31 + p.bounds.bottom
            h = h * 31 + if (p.outgoing) 1 else 0
        }
        // SIGNATURE_CLEARED is reserved for "showing nothing".
        return if (h == SIGNATURE_CLEARED) h + 1 else h
    }

    /**
     * A patch drawn from a scan taken 20 ms ago is ~40 px out of place during a
     * hard fling, which reads as smeared double text. Rather than show that, we
     * measure how far a known string travelled between two scans and hide the
     * overlay entirely while the list is moving quickly. Slow, deliberate
     * scrolling stays well under the threshold and tracks normally.
     */
    private fun isFlinging(previous: List<RawText>, current: List<RawText>, dtMs: Long): Boolean {
        if (dtMs !in 1..1500 || previous.isEmpty() || current.isEmpty()) return false
        val byText = HashMap<String, Int>(previous.size)
        for (p in previous) byText[p.text] = p.bounds.top
        for (p in current) {
            val before = byText[p.text] ?: continue
            val speed = abs(p.bounds.top - before) / dtMs.toFloat()
            return speed > FLING_PX_PER_MS
        }
        return false
    }

    private fun displayName(languageTag: String): String =
        Locale.forLanguageTag(languageTag).displayLanguage.ifBlank { languageTag }

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
        private const val TAG = "Lingo"

        /** Floor on the gap between tree walks. */
        private const val SCAN_MIN_INTERVAL_MS = 60L

        /** Ceiling on that gap, so the overlay never drops below ~1 FPS. */
        private const val SCAN_MAX_INTERVAL_MS = 1000L

        /** Follow-up scan used to notice that a fling has stopped. */
        private const val SETTLE_DELAY_MS = 110L

        /** Window over which arriving translations are coalesced into one repaint. */
        private const val REBUILD_DELAY_MS = 60L

        /** ~1200 px/s. Above this the overlay hides instead of lagging. */
        private const val FLING_PX_PER_MS = 1.2f

        /** Sentinel signature meaning "the overlay is showing nothing". */
        private const val SIGNATURE_CLEARED = 0

        const val DUMP_FILE = "last_dump.txt"
    }
}
