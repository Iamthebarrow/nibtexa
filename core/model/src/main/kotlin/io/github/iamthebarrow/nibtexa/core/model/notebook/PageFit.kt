package io.github.iamthebarrow.nibtexa.core.model.notebook

import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import kotlin.math.abs

/** A booklet's page margin (Phase 30), on the page and in every export. A cheat sheet has very little. */
enum class PageMargin(val inches: Float) {
    CHEAT_SHEET(0.25f),
    NARROW(0.5f),

    /** 56 points, the margin the PDF always had. */
    NORMAL(56f / 72f),
    WIDE(1.25f),
}

/**
 * Where writing goes on a page (Phase 30): inside its margin. Ruled paper has its margin at the sides only, as a ruled sheet does. A
 * page over an imported PDF (`background`) can be written on anywhere, as the PDF has its own margins. A margin too big for a
 * small sheet leaves at least half of it.
 */
fun Sheet.writingArea(paper: Paper, margin: PageMargin, background: Boolean = false): Bounds {
    if (background) return Bounds(0f, 0f, width, height)
    val side = minOf(margin.inches * Sheet.UNITS_PER_INCH, width / 4)
    val end = if (paper == Paper.RULED) 0f else minOf(margin.inches * Sheet.UNITS_PER_INCH, height / 4)
    return Bounds(side, end, width - side, height - end)
}

/** The margin [page] uses: its own, or the booklet's. */
fun DocumentStyle.marginOf(page: Page): PageMargin = page.margin ?: margin

/** The part of a stroke inside these bounds: its points up to where it first leaves, ending on the edge. Empty when it starts outside. */
fun Bounds.clip(points: List<InkPoint>): List<InkPoint> {
    val first = points.firstOrNull() ?: return emptyList()
    if (!contains(first.x, first.y)) return emptyList()
    val out = mutableListOf(first)
    for (i in 1 until points.size) {
        val p = points[i]
        if (contains(p.x, p.y)) {
            out += p
            continue
        }
        // Where the line from the last point inside crosses the edge.
        val q = out.last()
        var t = 1f
        if (p.x < left) t = minOf(t, (q.x - left) / (q.x - p.x))
        if (p.x > right) t = minOf(t, (right - q.x) / (p.x - q.x))
        if (p.y < top) t = minOf(t, (q.y - top) / (q.y - p.y))
        if (p.y > bottom) t = minOf(t, (bottom - q.y) / (p.y - q.y))
        out += p.copy(x = (q.x + (p.x - q.x) * t).coerceIn(left, right), y = (q.y + (p.y - q.y) * t).coerceIn(top, bottom))
        break
    }
    return out
}

/**
 * Keeps what is placed on a page inside its [writingArea] (Phase 30), without ever cutting content off: words wrap to a narrower box,
 * which grows downwards as it must; pictures, graphs and ink get smaller, their shape kept; everything else only moves.
 */
object PageFit {
    /** A text box is never narrower than this: a few words a line. */
    const val MIN_TEXT_WIDTH = 120f

    /** Measures typed words: their height at a width, in page units. */
    fun interface Measure {
        fun height(region: Region, width: Float): Float
    }

    /** Without the app's fonts: about half an em across a character. Good enough where nothing can be measured. */
    val ESTIMATE = Measure { region, width ->
        val size = sizeOf(region)
        val perLine = (width / (size * 0.5f)).toInt().coerceAtLeast(1)
        val length = region.text.orEmpty().length.coerceAtLeast(1)
        ((length + perLine - 1) / perLine) * size * 1.25f + 8f
    }

    private fun sizeOf(region: Region): Float = if (region.role != RegionRole.HEADING) 18f else when (region.headingLevel) {
        1 -> 30f
        2 -> 25f
        else -> 21f
    }

    /** The move ([dx], [dy]) of something at [bounds], cut short at the edges of [area]. Something too big for it stays at its left or top. */
    fun moveWithin(bounds: Bounds, area: Bounds, dx: Float, dy: Float): Pair<Float, Float> {
        fun axis(low: Float, high: Float, from: Float, to: Float, by: Float): Float {
            var moved = by
            if (high + moved > to) moved = to - high
            if (low + moved < from) moved = from - low
            return moved
        }
        return axis(bounds.left, bounds.right, area.left, area.right, dx) to axis(bounds.top, bounds.bottom, area.top, area.bottom, dy)
    }

    /** [bounds] moved as little as it takes to be inside [area]. */
    fun inside(bounds: Bounds, area: Bounds): Bounds {
        val (dx, dy) = moveWithin(bounds, area, 0f, 0f)
        return Bounds(bounds.left + dx, bounds.top + dy, bounds.right + dx, bounds.bottom + dy)
    }

    /** What can happen to a box when a page is arranged: words wrap, an equation is drawn smaller, and the rest keep their shape. */
    enum class Kind { WORDS, EQUATION, SHAPE, INK }

    class Box(val bounds: Bounds, val kind: Kind)

    /**
     * Where everything on a page goes to fit [area] together (from the user's testing), so that what sits side by side stays side by side
     * and nothing is left over the edge:
     * - if it is all wider than the area, every left edge is drawn in by the same share, so that columns stay columns; if not, it all
     *   moves together only as far as it must;
     * - a box with another to its right narrows by that share, to leave its neighbour room. The last in a row only comes in as far as the
     *   margin needs. Ink always takes the share, so handwriting stays one size;
     * - words wrap, and whatever is below a box that grew is pushed down by as much, column by column.
     *
     * Only a box that would run past the bottom of the area moves up, and that can overlap: there is no next page to go to here.
     * [wordsHeight] measures words at a width, by the box's index.
     */
    fun arranged(boxes: List<Box>, area: Bounds, wordsHeight: (Int, Float) -> Float): List<Bounds> {
        if (boxes.isEmpty()) return emptyList()
        val low = boxes.minOf { it.bounds.left }
        val high = boxes.maxOf { it.bounds.right }
        val roomX = area.right - area.left
        val roomY = area.bottom - area.top
        val share = minOf(1f, roomX / (high - low).coerceAtLeast(1f))
        val shift = if (share < 1f) 0f else moveWithin(Bounds(low, 0f, high, 0f), area, 0f, 0f).first
        fun across(x: Float) = if (share < 1f) area.left + (x - low) * share else x + shift
        val sizes = boxes.mapIndexed { i, box ->
            val b = box.bounds
            val w = b.right - b.left
            val h = b.bottom - b.top
            val left = across(b.left)
            val toMargin = area.right - left
            val hasNeighbour = boxes.indices.any { j ->
                val other = boxes[j].bounds
                j != i && other.top < b.bottom && other.bottom > b.top && other.left >= b.right - w * NEIGHBOUR_SLACK
            }
            val width = minOf(if (hasNeighbour || box.kind == Kind.INK) w * share else w, toMargin)
            when (box.kind) {
                Kind.WORDS -> {
                    val wrapped = width.coerceAtLeast(minOf(MIN_TEXT_WIDTH, toMargin))
                    val measured = wordsHeight(i, wrapped)
                    wrapped to if (abs(wrapped - w) > 0.5f) measured else maxOf(h, measured)
                }
                Kind.EQUATION -> width to minOf(h, roomY)
                Kind.SHAPE, Kind.INK -> {
                    val scale = minOf(if (w > 0f) width / w else 1f, if (h > 0f) roomY / h else 1f)
                    w * scale to h * scale
                }
            }
        }
        val start = maxOf(0f, area.top - boxes.minOf { it.bounds.top })
        val order = boxes.indices.sortedWith(compareBy({ boxes[it].bounds.top }, { boxes[it].bounds.left }))
        val topMoved = FloatArray(boxes.size)
        val bottomMoved = FloatArray(boxes.size)
        for ((n, i) in order.withIndex()) {
            val b = boxes[i].bounds
            val left = across(b.left)
            var push = 0f
            for (e in order.take(n)) {
                val above = boxes[e].bounds
                val aboveLeft = across(above.left)
                if (minOf(left + sizes[i].first, aboveLeft + sizes[e].first) - maxOf(left, aboveLeft) <= 1f) continue
                push = maxOf(push, if (above.bottom <= b.top + OVERLAP_SLACK) bottomMoved[e] else topMoved[e])
            }
            topMoved[i] = push
            bottomMoved[i] = push + sizes[i].second - (b.bottom - b.top)
        }
        return boxes.mapIndexed { i, box ->
            val (width, height) = sizes[i]
            val left = across(box.bounds.left)
            var top = box.bounds.top + start + topMoved[i]
            if (top + height > area.bottom) top = maxOf(area.top, area.bottom - height)
            Bounds(left, top, left + width, top + height)
        }
    }

    private const val NEIGHBOUR_SLACK = 0.1f
    private const val OVERLAP_SLACK = 4f

    /** Typed content over the margin, or words taller than their box: what is put right when a page is shown. */
    fun typedSpills(page: Page, area: Bounds, measure: Measure): Boolean = page.regions.filter { it.typed }.any { r ->
        val b = r.bounds
        b.left < area.left - 0.5f || b.right > area.right + 0.5f || b.top < area.top - 0.5f || b.bottom > area.bottom + 0.5f ||
            (r.picture == null && r.graph == null && r.diagram == null && r.role != RegionRole.MATH && measure.height(r, b.right - b.left) > b.bottom - b.top + 1f)
    }

    fun kindOf(region: Region): Kind = when {
        region.picture != null || region.graph != null || region.diagram != null -> Kind.SHAPE
        region.role == RegionRole.MATH -> Kind.EQUATION
        else -> Kind.WORDS
    }

    /** A typed region's box, fitted to [area]. */
    fun typedBox(region: Region, area: Bounds, measure: Measure = ESTIMATE): Bounds {
        val b = region.bounds
        val width = b.right - b.left
        val height = b.bottom - b.top
        val roomX = area.right - area.left
        val roomY = area.bottom - area.top
        val box = when {
            // Drawn to fill their box, so they keep their shape.
            region.picture != null || region.graph != null || region.diagram != null -> {
                val scale = minOf(1f, if (width > 0f) roomX / width else 1f, if (height > 0f) roomY / height else 1f)
                Bounds(b.left, b.top, b.left + width * scale, b.top + height * scale)
            }
            // An equation is drawn smaller if its box is narrower, and needs no more height.
            region.role == RegionRole.MATH -> Bounds(b.left, b.top, b.left + minOf(width, roomX), b.top + minOf(height, roomY))
            width > roomX -> Bounds(b.left, b.top, b.left + roomX, b.top + measure.height(region, roomX))
            else -> b
        }
        return inside(box, area)
    }

    /** [strokes] scaled from their top left by [scale], then moved by ([dx], [dy]). */
    internal fun placed(points: List<InkPoint>, from: Bounds, scale: Float, dx: Float, dy: Float): List<InkPoint> = points.map {
        it.copy(x = from.left + (it.x - from.left) * scale + dx, y = from.top + (it.y - from.top) * scale + dy)
    }
}
