package io.github.iamthebarrow.nibtexa.core.model.notebook

import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.Stroke

/**
 * What automatic grouping would do to a page (ADR-0007, section 4): new regions to create, in
 * position order, and strokes to add to existing automatic regions.
 */
data class AutoGrouping(
    val newGroups: List<Group> = emptyList(),
    val growth: Map<String, Set<Long>> = emptyMap(),
) {
    data class Group(val role: RegionRole, val strokeIds: Set<Long>, val bounds: Bounds, val headingLevel: Int = 1)

    val isEmpty: Boolean get() = newGroups.isEmpty() && growth.isEmpty()
}

/**
 * Groups ink that is in no region into regions, by pen, line, proximity and time. Pure and
 * deterministic: the same page always gives the same plan.
 *
 * Distances scale with `h`, the median height of the page's text and math strokes, so the
 * rules work for small and large handwriting:
 * - **text** strokes join when they share a line (vertical overlap of at least
 *   [LINE_OVERLAP] of the shorter one) and the horizontal gap is at most [TEXT_GAP] h, with no
 *   ink of another pen on the line between them (Phase 16: a formula splits a sentence). A small
 *   stroke (a dot, accent or comma) joins the nearest text group within [DOT_REACH_X] h across
 *   and [DOT_REACH_Y] h up or down;
 * - **math** strokes join when their gaps are at most [MATH_GAP_X] h across and [MATH_GAP_Y]
 *   h up or down, so fractions, limits and scripts stay together;
 * - **drawing** strokes join when their bounds overlap or nearly touch ([FIGURE_GAP] h);
 * - strokes of one role written in one burst (at most [BURST_MS] ms apart) join when within
 *   [BURST_REACH] h;
 * - scratch ink groups by line like text (Phase 15), into excluded regions that are never
 *   exported and only recognized for search; different pens never share a group.
 *
 * Only automatic regions that are not confirmed can grow. Ink near a confirmed or user-made
 * region starts a new group instead.
 *
 * Headings (Phase 15): a line of text written clearly larger than the page's usual writing is
 * suggested as a heading, level 1 from [HEADING_1] h and level 2 from [HEADING_2] h (the median
 * height of its strokes, dots and accents aside). Like any automatic region, the user confirms
 * it, and can make it text again.
 */
object AutoRegions {
    const val DEFAULT_HEIGHT = 24f
    const val MIN_HEIGHT = 8f
    const val LINE_OVERLAP = 0.3f
    const val TEXT_GAP = 2.0f
    const val SMALL_STROKE = 0.6f
    const val DOT_REACH_X = 0.5f
    const val DOT_REACH_Y = 0.8f
    const val MATH_GAP_X = 1.5f
    const val MATH_GAP_Y = 0.5f
    const val FIGURE_GAP = 0.5f
    const val BURST_MS = 500L
    const val BURST_REACH = 1.0f

    const val HEADING_1 = 2.2f
    const val HEADING_2 = 1.6f

    /** Regions narrower apart than this many h are never treated as separate columns. */
    const val COLUMN_GUTTER = 4.0f

    /** The page's handwriting scale: the median height of its text and math strokes. */
    fun scale(page: Page): Float {
        val heights = page.ink.strokes.filter { it.pen == Pen.TEXT || it.pen == Pen.MATH }
            .map { Bounds.of(it).let { b -> b.bottom - b.top } }
            .sorted()
        if (heights.isEmpty()) return DEFAULT_HEIGHT
        return maxOf(heights[heights.size / 2], MIN_HEIGHT)
    }

    fun plan(page: Page): AutoGrouping {
        val claimed = page.regions.flatMapTo(HashSet()) { it.strokeIds }
        val loose = page.ink.strokes.filter { it.id !in claimed }
        if (loose.isEmpty()) return AutoGrouping()
        val h = scale(page)

        val growable = page.regions.filter { it.isGrowable() && page.membersOf(it).isNotEmpty() }
        val nodes = loose.map { Node.Ink(it) } + growable.map { Node.Area(it) }
        val sets = UnionFind(nodes.size)

        // Ink of another pen between two words on a line splits them (Phase 16): a formula written
        // in a sentence becomes its own region, with the words before and after it apart.
        val others = loose.map { wordsRole(RegionRole.forPen(it.pen)) to Bounds.of(it) } +
            page.regions.filter { page.membersOf(it).isNotEmpty() }.map { wordsRole(it.role) to it.bounds }
        fun blocked(a: Node, b: Node): Boolean {
            if (a.role != RegionRole.TEXT && a.role != RegionRole.EXCLUDED) return false
            val (left, right) = when {
                a.bounds.right <= b.bounds.left -> a.bounds to b.bounds
                b.bounds.right <= a.bounds.left -> b.bounds to a.bounds
                else -> return false
            }
            return others.any { (role, box) ->
                role != a.role && box.right > left.right && box.left < right.left && (sameLine(box, left) || sameLine(box, right))
            }
        }

        fun link(i: Int, j: Int) {
            val a = nodes[i]
            val b = nodes[j]
            if (a.role != b.role || (a is Node.Area && b is Node.Area)) return
            if (near(a, b, h) && !blocked(a, b)) sets.union(i, j)
        }
        // Small text strokes attach afterwards, to their nearest group only, so a dot between
        // two lines cannot join them.
        val small = nodes.indices.filterTo(HashSet()) { nodes[it].isSmallText(h) }
        for (i in nodes.indices) for (j in i + 1 until nodes.size) if (i !in small && j !in small) link(i, j)
        linkBursts(nodes, sets, h)
        val alone = mutableListOf<Int>()
        for (i in small.sorted()) {
            val target = nodes.indices.filter { it !in small && nodes[it].role == RegionRole.TEXT && dotReaches(nodes[i].bounds, nodes[it].bounds, h) }
                .minByOrNull { distance(nodes[i].bounds, nodes[it].bounds) }
            if (target != null) sets.union(i, target) else alone += i
        }
        // Small strokes with no group nearby group among themselves (a lone colon, say).
        for (i in alone) for (j in alone) if (i < j) link(i, j)

        val newGroups = mutableListOf<AutoGrouping.Group>()
        val growth = sortedMapOf<String, Set<Long>>()
        for (component in nodes.indices.groupBy(sets::find).values) {
            val strokes = component.mapNotNull { (nodes[it] as? Node.Ink)?.stroke }
            if (strokes.isEmpty()) continue
            val ids = strokes.mapTo(sortedSetOf()) { it.id }
            val host = component.mapNotNull { (nodes[it] as? Node.Area)?.region }.minByOrNull { it.readingOrder }
            if (host != null) {
                growth[host.id] = (growth[host.id] ?: emptySet()) + ids
            } else {
                val role = nodes[component.first()].role
                val heading = if (role == RegionRole.TEXT) headingLevel(strokes, h) else null
                newGroups += AutoGrouping.Group(if (heading != null) RegionRole.HEADING else role, ids, Bounds.of(strokes)!!, heading ?: 1)
            }
        }
        val ordered = newGroups.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }, { it.strokeIds.first() }))
        return AutoGrouping(ordered, growth)
    }

    /**
     * Reading order for [page]'s regions after [added] (ids to bounds) are inserted: existing
     * regions keep their relative order, and each new region goes after the last region that
     * precedes it by position ([byPosition]).
     */
    fun readingOrderWith(page: Page, added: List<Pair<String, Bounds>>): List<String> {
        if (added.isEmpty()) return page.regionsInReadingOrder.map { it.id }
        val all = page.regions.map { it.id to it.bounds } + added
        val position = byPosition(all, scale(page), page.columns).withIndex().associate { (i, id) -> id to i }
        val result = page.regionsInReadingOrder.map { it.id }.toMutableList()
        for ((id, _) in added.sortedBy { position.getValue(it.first) }) {
            val p = position.getValue(id)
            val after = result.indexOfLast { position.getValue(it) < p }
            result.add(after + 1, id)
        }
        return result
    }

    /**
     * Orders boxes top to bottom and left to right by recursive cuts: a vertical gutter of at
     * least [COLUMN_GUTTER] h splits the page into columns, read one after the other; within a
     * column, horizontal gaps split lines, and lines read left to right. Columns that are closer
     * together are found by [PageColumns] (Phase 30), and read one after the other too.
     */
    fun byPosition(boxes: List<Pair<String, Bounds>>, h: Float, columns: ColumnMode = ColumnMode.AUTO): List<String> =
        PageColumns.sections(boxes, h, columns)?.flatMap { it.columns.flatten() } ?: order(boxes, h, inRow = false).map { it.first }

    /** [boxes] in order by position, with no columns looked for but those with a wide gutter. */
    internal fun inLines(boxes: List<Pair<String, Bounds>>, h: Float): List<Pair<String, Bounds>> = order(boxes, h, inRow = false)

    private fun order(boxes: List<Pair<String, Bounds>>, h: Float, inRow: Boolean): List<Pair<String, Bounds>> {
        if (boxes.size <= 1) return boxes
        val columns = split(boxes, if (inRow) 0f else COLUMN_GUTTER * h, { it.left }, { it.right })
        if (columns.size > 1) return columns.flatMap { order(it, h, inRow = false) }
        val rows = split(boxes, 0f, { it.top }, { it.bottom })
        if (rows.size > 1) return rows.flatMap { order(it, h, inRow = true) }
        return boxes.sortedWith(compareBy({ if (inRow) it.second.left else it.second.top }, { if (inRow) it.second.top else it.second.left }, { it.first }))
    }

    /** Splits wherever the projection has a gap of at least [gap] (strictly more than 0 when [gap] is 0). */
    private fun split(boxes: List<Pair<String, Bounds>>, gap: Float, start: (Bounds) -> Float, end: (Bounds) -> Float): List<List<Pair<String, Bounds>>> {
        val sorted = boxes.sortedWith(compareBy({ start(it.second) }, { it.first }))
        val groups = mutableListOf(mutableListOf(sorted.first()))
        var reach = end(sorted.first().second)
        for (box in sorted.drop(1)) {
            val space = start(box.second) - reach
            if (space > 0f && space >= gap) groups += mutableListOf(box) else groups.last() += box
            reach = maxOf(reach, end(box.second))
        }
        return groups
    }

    /** The heading level a line of text this large suggests, or null for ordinary text. */
    private fun headingLevel(strokes: List<Stroke>, h: Float): Int? {
        val heights = strokes.map(Bounds::of).filter { maxOf(it.right - it.left, it.bottom - it.top) >= SMALL_STROKE * h }
            .map { it.bottom - it.top }.sorted()
        if (heights.size < 2) return null
        val size = heights[heights.size / 2] / h
        return when {
            size >= HEADING_1 -> 1
            size >= HEADING_2 -> 2
            else -> null
        }
    }

    private fun Region.isGrowable() = auto && status != RecognitionStatus.CONFIRMED &&
        (role == RegionRole.TEXT || role == RegionRole.HEADING || role == RegionRole.MATH || role == RegionRole.FIGURE || role == RegionRole.EXCLUDED)

    private fun near(a: Node, b: Node, h: Float): Boolean {
        val x = gapX(a.bounds, b.bounds)
        val y = gapY(a.bounds, b.bounds)
        return when (a.role) {
            RegionRole.TEXT -> sameLine(a.bounds, b.bounds) && x <= TEXT_GAP * h
            RegionRole.MATH -> x <= MATH_GAP_X * h && y <= MATH_GAP_Y * h
            RegionRole.FIGURE -> x <= FIGURE_GAP * h && y <= FIGURE_GAP * h
            // Scratch notes are words too, as far as grouping goes.
            RegionRole.EXCLUDED -> sameLine(a.bounds, b.bounds) && x <= TEXT_GAP * h
            RegionRole.HEADING -> false
        }
    }

    /** Consecutive strokes of one role, written quickly one after the other and close together. */
    private fun linkBursts(nodes: List<Node>, sets: UnionFind, h: Float) {
        val inks = nodes.indices.filter { nodes[it] is Node.Ink && !nodes[it].isSmallText(h) }
            .sortedWith(compareBy({ (nodes[it] as Node.Ink).stroke.startTimeMillis }, { (nodes[it] as Node.Ink).stroke.id }))
        for ((i, j) in inks.zipWithNext()) {
            val a = (nodes[i] as Node.Ink).stroke
            val b = (nodes[j] as Node.Ink).stroke
            if (a.pen != b.pen) continue
            val pause = b.startTimeMillis - (a.startTimeMillis + a.points.last().elapsedMillis)
            if (pause in 0..BURST_MS && distance(nodes[i].bounds, nodes[j].bounds) <= BURST_REACH * h) sets.union(i, j)
        }
    }

    /** Headings are words, like text, to the grouping. */
    private fun wordsRole(role: RegionRole) = if (role == RegionRole.HEADING) RegionRole.TEXT else role

    /** The boxes share a line: they overlap vertically by at least [LINE_OVERLAP] of the shorter. */
    fun sameLine(a: Bounds, b: Bounds): Boolean {
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        val shorter = minOf(a.bottom - a.top, b.bottom - b.top)
        return overlap >= 0f && overlap >= LINE_OVERLAP * shorter
    }

    private fun dotReaches(dot: Bounds, target: Bounds, h: Float) = gapX(dot, target) <= DOT_REACH_X * h && gapY(dot, target) <= DOT_REACH_Y * h

    private fun gapX(a: Bounds, b: Bounds) = maxOf(0f, maxOf(a.left, b.left) - minOf(a.right, b.right))

    private fun gapY(a: Bounds, b: Bounds) = maxOf(0f, maxOf(a.top, b.top) - minOf(a.bottom, b.bottom))

    private fun distance(a: Bounds, b: Bounds) = maxOf(gapX(a, b), gapY(a, b))

    private sealed interface Node {
        val role: RegionRole
        val bounds: Bounds

        fun isSmallText(h: Float): Boolean = this is Ink && role == RegionRole.TEXT &&
            maxOf(bounds.right - bounds.left, bounds.bottom - bounds.top) < SMALL_STROKE * h

        class Ink(val stroke: Stroke) : Node {
            override val role = RegionRole.forPen(stroke.pen)
            override val bounds = Bounds.of(stroke)
        }

        class Area(val region: Region) : Node {
            // A suggested heading is a line of text to the grouping: its line can still grow.
            override val role = if (region.role == RegionRole.HEADING) RegionRole.TEXT else region.role
            override val bounds = region.bounds
        }
    }

    private class UnionFind(size: Int) {
        private val parent = IntArray(size) { it }

        fun find(i: Int): Int {
            var root = i
            while (parent[root] != root) root = parent[root]
            var node = i
            while (parent[node] != root) node = parent[node].also { parent[node] = root }
            return root
        }

        /** Keeps the smaller index as the root, so results do not depend on the call order. */
        fun union(i: Int, j: Int) {
            val a = find(i)
            val b = find(j)
            if (a != b) parent[maxOf(a, b)] = minOf(a, b)
        }
    }
}
