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
 *
 * Cost model: every `getChild()` is an IPC round trip into TikTok's process, so
 * a scan costs roughly one round trip per node visited. Walking the whole
 * window means walking the video feed as well as the comments, and on a busy
 * screen that is slow enough to be felt. So the scan locates the comment list
 * first and then walks only that subtree, falling back to a full walk if the
 * focused one finds nothing.
 */
object NodeScanner {

    /** Hard ceilings so a pathological tree can never stall the scan thread. */
    private const val MAX_NODES = 1500
    private const val MAX_DEPTH = 45

    /** Budget for the shallow search that locates the comment list. */
    private const val LOCATE_BUDGET = 400
    private const val LOCATE_MAX_DEPTH = 18

    data class Result(
        val patches: List<Patch>,
        val region: Rect?,
        val dump: String?
    )

    fun scan(
        root: AccessibilityNodeInfo,
        screen: Rect,
        wholeScreen: Boolean,
        diagnostics: Boolean,
        /**
         * Comment region from the previous scan. Only used on the full-walk
         * path, to skip subtrees that lie entirely outside it.
         */
        hintRegion: Rect? = null
    ): Result {
        if (!wholeScreen) {
            val container = findCommentList(root, screen)
            if (container != null) {
                val bounds = Rect().also { container.getBoundsInScreen(it) }
                val harvest = harvest(container, screen, screen)
                container.recycleCompat()

                val region = Rect(bounds).apply { inset(-8, -8) }
                val patches = build(harvest.texts, region, diagnostics)
                if (patches.isNotEmpty()) {
                    return Result(
                        patches = patches,
                        region = region,
                        dump = dump(diagnostics, harvest, region, screen, "focused on comment list")
                    )
                }
                // Nothing usable in there -- fall through to the full walk
                // rather than showing the user an empty overlay.
            }
        }

        val harvest = harvest(root, screen, hintRegion ?: screen)
        val region = if (wholeScreen) null else pickCommentRegion(harvest.scrollables, screen)
        return Result(
            patches = build(harvest.texts, region, diagnostics),
            region = region,
            dump = dump(diagnostics, harvest, region, screen, "full window walk")
        )
    }

    /**
     * Breadth-first search for the comment list, returning the shallowest
     * scrollable that looks like a bottom sheet.
     *
     * Breadth-first matters: the list sits only a dozen or so nodes below the
     * root, so this stops almost immediately, whereas a depth-first search
     * would wander into the feed before finding it. The caller owns the
     * returned node and must recycle it.
     */
    private fun findCommentList(root: AccessibilityNodeInfo, screen: Rect): AccessibilityNodeInfo? {
        val sheetTop = screen.top + screen.height() / 4
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var visited = 0
        var found: AccessibilityNodeInfo? = null

        while (queue.isNotEmpty() && visited < LOCATE_BUDGET && found == null) {
            val (node, depth) = queue.removeFirst()
            visited++

            val b = Rect()
            node.getBoundsInScreen(b)

            // The feed pager is scrollable and full height too, so require the
            // container to start below the top quarter of the display.
            val isCandidate = node !== root &&
                node.isScrollable &&
                b.top > sheetTop &&
                b.height() > screen.height() / 5

            if (isCandidate) {
                found = node
                break
            }

            if (depth < LOCATE_MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    val child = try {
                        node.getChild(i)
                    } catch (t: Throwable) {
                        null
                    } ?: continue
                    queue.addLast(child to depth + 1)
                }
            }

            if (node !== root) node.recycleCompat()
        }

        // Release anything still queued; the caller keeps only `found`.
        while (queue.isNotEmpty()) {
            val (n, _) = queue.removeFirst()
            if (n !== root && n !== found) n.recycleCompat()
        }
        return found
    }

    private class Harvest(
        val texts: List<RawNode>,
        val scrollables: List<Rect>,
        val visited: Int
    )

    private fun harvest(walkRoot: AccessibilityNodeInfo, screen: Rect, clip: Rect): Harvest {
        val texts = ArrayList<RawNode>(64)
        val scrollables = ArrayList<Rect>(8)
        var visited = 0

        // Iterative DFS -- recursion on a deep RecyclerView tree is a real
        // StackOverflow risk, and an explicit stack lets us cap the work.
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(walkRoot to 0)

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

                    // Drop whole subtrees that lie outside the area of interest.
                    // Containers legitimately report empty bounds, so only prune
                    // on a child that actually has a rectangle to judge.
                    val cb = Rect()
                    child.getBoundsInScreen(cb)
                    if (!cb.isEmpty && !Rect.intersects(cb, clip)) {
                        child.recycleCompat()
                        continue
                    }

                    stack.addLast(child to depth + 1)
                }
            }

            if (node !== walkRoot) node.recycleCompat()
        }

        return Harvest(texts, scrollables, visited)
    }

    private fun build(texts: List<RawNode>, region: Rect?, diagnostics: Boolean): List<Patch> {
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
                    source = if (diagnostics) {
                        "${raw.className.substringAfterLast('.')} ${raw.viewId.substringAfterLast('/')}"
                    } else {
                        ""
                    }
                )
            )
        }
        return patches
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

    private fun dump(
        enabled: Boolean,
        harvest: Harvest,
        region: Rect?,
        screen: Rect,
        strategy: String
    ): String? {
        if (!enabled) return null
        return buildString {
            appendLine("CyrFix node dump")
            appendLine("strategy=$strategy")
            appendLine("screen=${screen.toShortString()}  nodesVisited=${harvest.visited}")
            appendLine("chosen comment region=${region?.toShortString() ?: "<none - scanning whole screen>"}")
            appendLine()
            appendLine("scrollable containers (${harvest.scrollables.size}):")
            harvest.scrollables.forEach { appendLine("  ${it.toShortString()}") }
            appendLine()
            appendLine("text nodes (${harvest.texts.size}):")
            harvest.texts.forEach { n ->
                val inRegion = region == null || region.contains(n.bounds.centerX(), n.bounds.centerY())
                appendLine(
                    "  ${n.bounds.toShortString()} cyr=${n.text.hasCyrillic()} inRegion=$inRegion " +
                        "class=${n.className} id=${n.viewId}"
                )
                appendLine("      ${n.text.take(120).replace("\n", "\\n")}")
            }
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
