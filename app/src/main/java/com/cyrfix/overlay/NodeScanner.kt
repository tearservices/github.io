package com.cyrfix.overlay

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Walks TikTok's view hierarchy and pulls out the comment text together with
 * the screen rectangle each string occupies.
 *
 * This is the whole reason the app needs no screen capture and no OCR: the
 * strings TikTok is rendering badly are still perfectly good strings in its
 * view tree, and the tree also tells us exactly where each one was drawn.
 */
object NodeScanner {

    /** Hard ceilings so a pathological tree can never stall the scan thread. */
    private const val MAX_NODES = 3000
    private const val MAX_DEPTH = 45

    data class Result(
        val patches: List<Patch>,
        val region: Rect?,
        val dump: String?
    )

    fun scan(
        root: AccessibilityNodeInfo,
        screen: Rect,
        wholeScreen: Boolean,
        diagnostics: Boolean
    ): Result {
        val texts = ArrayList<RawNode>(64)
        val scrollables = ArrayList<Rect>(8)
        var visited = 0

        // Iterative DFS -- recursion on a deep RecyclerView tree is a real
        // StackOverflow risk, and an explicit stack lets us cap the work.
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)

        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = stack.removeLast()
            visited++

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            if (node.isScrollable && bounds.height() > screen.height() / 5) {
                scrollables.add(Rect(bounds))
            }

            val text = node.text
            if (!text.isNullOrBlank() && isTextLeaf(node)) {
                if (node.isVisibleToUser &&
                    bounds.width() > 2 && bounds.height() > 2 &&
                    Rect.intersects(bounds, screen)
                ) {
                    texts.add(
                        RawNode(
                            text = text.toString(),
                            bounds = Rect(bounds),
                            className = node.className?.toString() ?: "?",
                            viewId = node.viewIdResourceName ?: ""
                        )
                    )
                }
            }

            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    val child = try {
                        node.getChild(i)
                    } catch (t: Throwable) {
                        // getChild can throw if the window changed mid-walk.
                        null
                    } ?: continue
                    stack.addLast(child to depth + 1)
                }
            }

            if (node !== root) node.recycleCompat()
        }

        val region = if (wholeScreen) null else pickCommentRegion(scrollables, screen)

        val seen = HashSet<String>(texts.size)
        val patches = ArrayList<Patch>(texts.size)
        for (raw in texts) {
            if (region != null && !region.contains(raw.bounds.centerX(), raw.bounds.centerY())) continue
            // Nothing Cyrillic means nothing to fix; leave TikTok's own pixels alone.
            if (!diagnostics && !raw.text.hasCyrillic()) continue
            val key = "${raw.text}@${raw.bounds.toShortString()}"
            if (!seen.add(key)) continue
            patches.add(
                Patch(
                    text = raw.text,
                    bounds = raw.bounds,
                    source = if (diagnostics) "${raw.className.substringAfterLast('.')} ${raw.viewId.substringAfterLast('/')}" else ""
                )
            )
        }

        val dump = if (diagnostics) buildDump(texts, scrollables, region, visited, screen) else null
        return Result(patches, region, dump)
    }

    /**
     * A node counts as text if it actually carries a string and is a leaf (or a
     * genuine TextView). TikTok uses plenty of custom view classes, so keying
     * purely off the class name would miss comments.
     */
    private fun isTextLeaf(node: AccessibilityNodeInfo): Boolean {
        if (node.childCount == 0) return true
        val cn = node.className?.toString() ?: return false
        return cn.endsWith("TextView") || cn.endsWith("EditText")
    }

    /**
     * The comment sheet is a scrollable that starts partway down the screen.
     * The feed itself is also scrollable and usually full-height, so simply
     * taking the biggest scrollable would land on the wrong one -- we prefer
     * containers whose top edge sits below the first quarter of the display.
     */
    private fun pickCommentRegion(scrollables: List<Rect>, screen: Rect): Rect? {
        if (scrollables.isEmpty()) return null
        val sheetTopCutoff = screen.top + screen.height() / 4

        val sheets = scrollables.filter { it.top > sheetTopCutoff }
        val chosen = sheets.maxByOrNull { it.width().toLong() * it.height() }
            ?: scrollables.maxByOrNull { it.width().toLong() * it.height() }
            ?: return null

        // Grow slightly: a comment's text can extend a hair past the
        // container's reported bounds on some builds.
        return Rect(chosen).apply { inset(-8, -8) }
    }

    private fun buildDump(
        texts: List<RawNode>,
        scrollables: List<Rect>,
        region: Rect?,
        visited: Int,
        screen: Rect
    ): String = buildString {
        appendLine("CyrFix node dump")
        appendLine("screen=${screen.toShortString()}  nodesVisited=$visited")
        appendLine("chosen comment region=${region?.toShortString() ?: "<none - scanning whole screen>"}")
        appendLine()
        appendLine("scrollable containers (${scrollables.size}):")
        scrollables.forEach { appendLine("  ${it.toShortString()}") }
        appendLine()
        appendLine("text nodes (${texts.size}):")
        texts.forEach { n ->
            val inRegion = region == null || region.contains(n.bounds.centerX(), n.bounds.centerY())
            appendLine(
                "  ${n.bounds.toShortString()} cyr=${n.text.hasCyrillic()} inRegion=$inRegion " +
                    "class=${n.className} id=${n.viewId}"
            )
            appendLine("      ${n.text.take(120).replace("\n", "\\n")}")
        }
    }

    private data class RawNode(
        val text: String,
        val bounds: Rect,
        val className: String,
        val viewId: String
    )
}

/**
 * recycle() was deprecated in API 33 and is a no-op there, but on older
 * releases failing to call it leaks a real binder-backed object on every scan.
 */
@Suppress("DEPRECATION")
fun AccessibilityNodeInfo.recycleCompat() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        try {
            recycle()
        } catch (_: Throwable) {
            // Already recycled -- nothing to do.
        }
    }
}
