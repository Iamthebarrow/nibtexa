package io.github.iamthebarrow.nibtexa.core.model.notebook

import io.github.iamthebarrow.nibtexa.core.model.Stroke

/** Lasso selection on a page. Coordinates are canvas units. */
object Lasso {
    /** A stroke is selected when at least this share of its points lies inside the loop. */
    const val INSIDE_SHARE = 0.5f

    /**
     * Returns the ids of the strokes the closed loop selects. Selecting part of a stroke
     * selects the whole stroke; a stroke is in or out by the share of its points inside.
     * A loop with fewer than three points selects nothing.
     */
    fun select(loop: List<Pair<Float, Float>>, strokes: List<Stroke>): Set<Long> {
        if (loop.size < 3) return emptySet()
        return strokes.filter { stroke ->
            val inside = stroke.points.count { contains(loop, it.x, it.y) }
            inside >= stroke.points.size * INSIDE_SHARE
        }.mapTo(linkedSetOf()) { it.id }
    }

    /** Even-odd point-in-polygon test; the loop is closed implicitly. */
    fun contains(loop: List<Pair<Float, Float>>, x: Float, y: Float): Boolean {
        var inside = false
        var j = loop.lastIndex
        for (i in loop.indices) {
            val (xi, yi) = loop[i]
            val (xj, yj) = loop[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * The strokes an eraser path touches: any stroke whose line comes within [radius] of the
     * path (or of the single point of a tap). Segments are tested, not just sample points, so
     * fast strokes with sparse samples are still hit.
     */
    fun hit(path: List<Pair<Float, Float>>, radius: Float, strokes: List<Stroke>): Set<Long> {
        if (path.isEmpty()) return emptySet()
        val eraser = segments(path)
        return strokes.filter { stroke ->
            val ink = segments(stroke.points.map { it.x to it.y })
            ink.any { (a, b) -> eraser.any { (c, d) -> segmentDistance(a, b, c, d) <= radius } }
        }.mapTo(linkedSetOf()) { it.id }
    }

    private fun segments(points: List<Pair<Float, Float>>) =
        if (points.size == 1) listOf(points[0] to points[0]) else points.zipWithNext()

    private fun segmentDistance(a: Pair<Float, Float>, b: Pair<Float, Float>, c: Pair<Float, Float>, d: Pair<Float, Float>): Float {
        if (intersects(a, b, c, d)) return 0f
        return minOf(distanceToSegment(a.first, a.second, c, d), distanceToSegment(b.first, b.second, c, d),
            distanceToSegment(c.first, c.second, a, b), distanceToSegment(d.first, d.second, a, b))
    }

    private fun intersects(a: Pair<Float, Float>, b: Pair<Float, Float>, c: Pair<Float, Float>, d: Pair<Float, Float>): Boolean {
        fun cross(o: Pair<Float, Float>, p: Pair<Float, Float>, q: Pair<Float, Float>) =
            (p.first - o.first) * (q.second - o.second) - (p.second - o.second) * (q.first - o.first)
        val d1 = cross(c, d, a)
        val d2 = cross(c, d, b)
        val d3 = cross(a, b, c)
        val d4 = cross(a, b, d)
        return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0)) && d1 != 0f && d2 != 0f && d3 != 0f && d4 != 0f
    }

    internal fun distanceToSegment(x: Float, y: Float, a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0f) 0f else (((x - a.first) * dx + (y - a.second) * dy) / lengthSquared).coerceIn(0f, 1f)
        return kotlin.math.hypot(x - (a.first + t * dx), y - (a.second + t * dy))
    }
}
