package io.github.iamthebarrow.nibtexa.core.model.notebook

/** Whether a page is set in columns (Phase 30): as the writing shows, or as the user says. */
enum class ColumnMode { AUTO, ONE, TWO }

/** A part of a page, top to bottom: one column of region ids, or two side by side. */
data class PageSection(val columns: List<List<String>>)

/**
 * Finds the columns in what was written on a page (Phase 30), for the exports and the reading order alike. A vertical line
 * near the middle divides the page. What crosses it goes across the whole width (a title, a wide equation) and separates the
 * parts above and below it, each of which may be in columns.
 *
 * Handwriting often has words and an equation side by side on a line, which must stay one sentence, so columns are only
 * found when a part of the page has at least two rows on each side and its sides aren't simply paired row by row. Once one
 * part qualifies, every part with writing on both sides of that line is in columns too. The user can always say otherwise
 * ([ColumnMode]).
 */
object PageColumns {
    /** The dividing line is looked for between these shares of the writing's width. */
    private const val CUT_FROM = 0.3f
    private const val CUT_TO = 0.7f

    /** A box may reach this far past the line, in h, and still be on its side. */
    private const val REACH = 0.25f

    /** The page's sections, top to bottom, or null when it isn't in columns. */
    fun sections(boxes: List<Pair<String, Bounds>>, h: Float, mode: ColumnMode): List<PageSection>? {
        if (mode == ColumnMode.ONE || boxes.size < 2) return null
        val cut = (if (mode == ColumnMode.TWO) middle(boxes) else bestCut(boxes, h)) ?: return null
        val reach = REACH * h

        // Top to bottom: what crosses the line separates the parts on either side of it.
        val parts = mutableListOf<Part>()
        for (box in boxes.sortedWith(compareBy({ it.second.top }, { it.second.left }, { it.first }))) {
            val crosses = box.second.left < cut - reach && box.second.right > cut + reach
            when {
                crosses -> parts += Part(mutableListOf(box), mutableListOf(), across = true)
                else -> {
                    val part = parts.lastOrNull()?.takeIf { !it.across } ?: Part(mutableListOf(), mutableListOf(), across = false).also { parts += it }
                    (if (box.second.centerX < cut) part.left else part.right) += box
                }
            }
        }
        if (mode == ColumnMode.AUTO && parts.none { qualifies(it, h) }) return null

        val sections = mutableListOf<PageSection>()
        for (part in parts) {
            val left = AutoRegions.inLines(part.left, h).map { it.first }
            val right = AutoRegions.inLines(part.right, h).map { it.first }
            if (left.isNotEmpty() && right.isNotEmpty()) {
                sections += PageSection(listOf(left, right))
            } else {
                // One column: it joins the one before, so that a paragraph can carry on.
                val last = sections.lastOrNull()?.takeIf { it.columns.size == 1 }
                val ids = left + right
                if (last != null) sections[sections.lastIndex] = PageSection(listOf(last.columns.single() + ids)) else sections += PageSection(listOf(ids))
            }
        }
        return sections.takeIf { list -> list.any { it.columns.size == 2 } || mode == ColumnMode.TWO }
    }

    private class Part(val left: MutableList<Pair<String, Bounds>>, val right: MutableList<Pair<String, Bounds>>, val across: Boolean)

    private fun middle(boxes: List<Pair<String, Bounds>>): Float = (boxes.minOf { it.second.left } + boxes.maxOf { it.second.right }) / 2

    /** The line near the middle that the fewest boxes cross, with writing on both sides; the nearest the middle if several tie. */
    private fun bestCut(boxes: List<Pair<String, Bounds>>, h: Float): Float? {
        val from = boxes.minOf { it.second.left }
        val width = boxes.maxOf { it.second.right } - from
        if (width <= 0f) return null
        val reach = REACH * h
        val mid = from + width / 2
        return boxes.flatMap { listOf(it.second.left, it.second.right) }
            .filter { it >= from + CUT_FROM * width && it <= from + CUT_TO * width }
            .filter { cut -> boxes.any { it.second.right <= cut + reach } && boxes.any { it.second.left >= cut - reach } }
            .minWithOrNull(compareBy({ cut -> boxes.count { it.second.left < cut - reach && it.second.right > cut + reach } }, { kotlin.math.abs(it - mid) }))
    }

    private fun qualifies(part: Part, h: Float): Boolean {
        if (part.across || rows(part.left) < 2 || rows(part.right) < 2) return false
        // Only where both sides have writing: a title above a table of two columns doesn't make it columns.
        val top = maxOf(part.left.minOf { it.second.top }, part.right.minOf { it.second.top })
        val bottom = minOf(part.left.maxOf { it.second.bottom }, part.right.maxOf { it.second.bottom })
        val beside = part.left.filter { it.second.bottom >= top && it.second.top <= bottom }
        // One row on one side for one on the other. A paragraph beside two shorter lines isn't that.
        val paired = rows(beside) == rows(part.right) && part.right.all { r -> part.left.any { AutoRegions.sameLine(it.second, r.second) } } &&
            beside.all { l -> part.right.any { AutoRegions.sameLine(it.second, l.second) } }
        return !paired
    }

    private fun rows(boxes: List<Pair<String, Bounds>>): Int {
        var count = 0
        var bottom = Float.NEGATIVE_INFINITY
        for (box in boxes.sortedBy { it.second.top }) {
            if (box.second.top >= bottom) count++
            bottom = maxOf(bottom, box.second.bottom)
        }
        return count
    }
}
