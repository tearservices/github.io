package com.lingo.overlay

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Walks WhatsApp's view hierarchy and pulls out the message text together with
 * the screen rectangle each string occupies.
 *
 * This is why the app needs no screen capture and no OCR. Screenshotting the
 * chat and running text recognition over it would be slower, would guess at
 * text that is already sitting in the view tree exactly right, and would be
 * stale the instant the list moved. The tree gives us the true string *and* the
 * rectangle it was drawn in, and those rectangles move with the scroll.
 *
 * Cost model: every `getChild()` is an IPC round trip into WhatsApp's process,
 * so a scan costs roughly one round trip per node visited. The scan therefore
 * locates the conversation list first and walks only that subtree, falling back
 * to a full walk if that finds nothing.
 */
/**
 * View ids WhatsApp gives the body of a message bubble. When any node on screen
 * matches one of these we trust it exclusively and ignore every other string,
 * which keeps timestamps, contact names and the toolbar out of the results
 * without any guesswork.
 */
private val MESSAGE_ID_SUFFIXES = listOf("message_text", "conversation_text", "text_content")

object NodeScanner {

    /** Hard ceilings so a pathological tree can never stall the scan thread. */
    private const val MAX_NODES = 1500
    private const val MAX_DEPTH = 45

    /** Budget for the shallow search that locates the conversation list. */
    private const val LOCATE_BUDGET = 400
    private const val LOCATE_MAX_DEPTH = 18

    data class Result(
        val texts: List<RawText>,
        val region: Rect?,
        val dump: String?
    )

    fun scan(
        root: AccessibilityNodeInfo,
        screen: Rect,
        wholeScreen: Boolean,
        diagnostics: Boolean,
        /**
         * Conversation region from the previous scan. Only used on the full-walk
         * path, to skip subtrees that lie entirely outside it.
         */
        hintRegion: Rect? = null
    ): Result {
        if (!wholeScreen) {
            val container = findConversationList(root, screen)
            if (container != null) {
                val bounds = Rect().also { container.getBoundsInScreen(it) }
                val harvest = harvest(container, screen, screen)
                container.recycleCompat()

                val region = Rect(bounds).apply { inset(-8, -8) }
                val texts = build(harvest.texts, region, screen, diagnostics)
                if (texts.isNotEmpty()) {
                    return Result(
                        texts = texts,
                        region = region,
                        dump = dump(diagnostics, harvest, region, screen, "focused on conversation list")
                    )
                }
                // Nothing usable in there -- fall through to the full walk rather
                // than showing the user an empty overlay.
            }
        }

        val harvest = harvest(root, screen, hintRegion ?: screen)
        val region = if (wholeScreen) null else pickConversationRegion(harvest.scrollables, screen)
        return Result(
            texts = build(harvest.texts, region, screen, diagnostics),
            region = region,
            dump = dump(diagnostics, harvest, region, screen, "full window walk")
        )
    }

    /**
     * Breadth-first search for the message list, returning the shallowest tall
     * scrollable.
     *
     * Breadth-first matters: the list sits only a dozen or so nodes below the
     * root, so this stops almost immediately, whereas a depth-first search would
     * wander deep into one bubble's subtree before finding it. The caller owns
     * the returned node and must recycle it.
     */
    private fun findConversationList(
        root: AccessibilityNodeInfo,
        screen: Rect
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var visited = 0
        var found: AccessibilityNodeInfo? = null

        while (queue.isNotEmpty() && visited < LOCATE_BUDGET && found == null) {
            val (node, depth) = queue.removeFirst()
            visited++

            val b = Rect()
            node.getBoundsInScreen(b)

            // The chat occupies everything between the toolbar and the compose
            // bar, so the list is always the tall scrollable. Requiring real
            // height keeps this off small horizontal strips like the sticker or
            // reaction rows, which are scrollable too.
            val isCandidate = node !== root &&
                node.isScrollable &&
                b.height() > screen.height() / 3

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

            if (node.isScrollable && bounds.height() > screen.height() / 3) {
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
                            viewId = node.viewIdResourceName ?: "",
                            editable = node.isEditable
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

    private fun build(
        nodes: List<RawNode>,
        region: Rect?,
        screen: Rect,
        diagnostics: Boolean
    ): List<RawText> {
        // If WhatsApp is exposing message-body view ids on this build, they are a
        // far better filter than any geometric guess -- use them and nothing else.
        val idMatched = nodes.any { it.isMessageBody() }

        val seen = HashSet<String>(nodes.size)
        val out = ArrayList<RawText>(nodes.size)
        for (raw in nodes) {
            if (region != null && !region.contains(raw.bounds.centerX(), raw.bounds.centerY())) continue
            // Never touch the compose box: the user is typing in it, and a patch
            // would sit on top of their own half-written message.
            if (raw.editable) continue
            if (idMatched && !raw.isMessageBody()) continue
            if (!diagnostics && !raw.text.looksLikeMessageText()) continue

            val key = "${raw.text}@${raw.bounds.toShortString()}"
            if (!seen.add(key)) continue

            out.add(
                RawText(
                    text = raw.text,
                    bounds = raw.bounds,
                    outgoing = isOutgoing(raw.bounds, screen),
                    source = if (diagnostics) {
                        "${raw.className.substringAfterLast('.')} ${raw.viewId.substringAfterLast('/')}"
                    } else {
                        ""
                    }
                )
            )
        }
        return out
    }

    /**
     * Which side of the chat a bubble is on, and so which colour a patch over it
     * has to be.
     *
     * Comparing against the middle of the screen would be wrong: a long incoming
     * message wraps across most of the width, so its centre sits right of
     * centre. What actually distinguishes the two is which edge the bubble is
     * pinned to, so the gaps either side are compared instead.
     */
    private fun isOutgoing(bounds: Rect, screen: Rect): Boolean {
        val leftGap = bounds.left - screen.left
        val rightGap = screen.right - bounds.right
        return leftGap > rightGap
    }

    /**
     * A node counts as text if it actually carries a string and is a leaf (or a
     * genuine TextView). WhatsApp uses plenty of custom view classes, so keying
     * purely off the class name would miss messages.
     */
    private fun isTextLeaf(node: AccessibilityNodeInfo): Boolean {
        if (node.childCount == 0) return true
        val cn = node.className?.toString() ?: return false
        return cn.endsWith("TextView") || cn.endsWith("EditText")
    }

    /**
     * On the full-walk path, the conversation is the tallest scrollable. Unlike
     * a feed there is no second full-height scroller competing with it, so the
     * largest one is the right answer.
     */
    private fun pickConversationRegion(scrollables: List<Rect>, screen: Rect): Rect? {
        val chosen = scrollables.maxByOrNull { it.width().toLong() * it.height() } ?: return null
        // Grow slightly: a bubble's text can extend a hair past the container's
        // reported bounds on some builds.
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
        val idMatched = harvest.texts.any { it.isMessageBody() }
        return buildString {
            appendLine("Lingo node dump")
            appendLine("strategy=$strategy")
            appendLine("message-id filter active=$idMatched")
            appendLine("screen=${screen.toShortString()}  nodesVisited=${harvest.visited}")
            appendLine("chosen conversation region=${region?.toShortString() ?: "<none - scanning whole screen>"}")
            appendLine()
            appendLine("scrollable containers (${harvest.scrollables.size}):")
            harvest.scrollables.forEach { appendLine("  ${it.toShortString()}") }
            appendLine()
            appendLine("text nodes (${harvest.texts.size}):")
            harvest.texts.forEach { n ->
                val inRegion = region == null || region.contains(n.bounds.centerX(), n.bounds.centerY())
                appendLine(
                    "  ${n.bounds.toShortString()} body=${n.isMessageBody()} " +
                        "out=${isOutgoing(n.bounds, screen)} inRegion=$inRegion " +
                        "editable=${n.editable} class=${n.className} id=${n.viewId}"
                )
                appendLine("      ${n.text.take(120).replace("\n", "\\n")}")
            }
        }
    }

    private data class RawNode(
        val text: String,
        val bounds: Rect,
        val className: String,
        val viewId: String,
        val editable: Boolean
    ) {
        fun isMessageBody(): Boolean {
            if (viewId.isEmpty()) return false
            val id = viewId.substringAfterLast('/')
            return MESSAGE_ID_SUFFIXES.any { id == it }
        }
    }
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
