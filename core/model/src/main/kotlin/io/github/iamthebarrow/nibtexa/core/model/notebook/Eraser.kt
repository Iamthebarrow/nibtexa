package io.github.iamthebarrow.nibtexa.core.model.notebook

import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import kotlin.math.ceil
import kotlin.math.hypot

/** What the eraser takes away (Phase 35): only the ink under it, or each whole stroke it touches. */
enum class EraserMode { PRECISE, STROKE }

/** How wide the eraser is. [radius] is in canvas units, so it stays the same size on the paper at any zoom. */
enum class EraserSize(val radius: Float) {
    SMALL(4f),
    MEDIUM(8f),
    LARGE(16f),
    EXTRA_LARGE(32f),
}

data class EraserSettings(val mode: EraserMode = EraserMode.PRECISE, val size: EraserSize = EraserSize.MEDIUM)

/** Works out what a path of the eraser leaves of a stroke. Coordinates are canvas units. */
object Eraser {
    /** A piece shorter than this is too small to see or read, so it goes too. */
    private const val SMALLEST_PIECE = 0.5f

    /**
     * The parts of [stroke] outside [radius] of [path], or null if the path doesn't touch it. An empty list means none of
     * it is left. A cut falls where the stroke meets the eraser, to about a third of [radius]; the points each side stay as
     * they were captured, and a point made at a cut has its pressure and tilt worked out from its neighbours.
     */
    fun pieces(stroke: Stroke, path: List<Pair<Float, Float>>, radius: Float): List<List<InkPoint>>? {
        if (path.isEmpty()) return null
        val segments = if (path.size == 1) listOf(path[0] to path[0]) else path.zipWithNext()
        fun under(point: InkPoint) = segments.any { (a, b) -> Lasso.distanceToSegment(point.x, point.y, a, b) <= radius }

        val step = (radius / 3f).coerceAtLeast(0.5f)
        val pieces = mutableListOf<List<InkPoint>>()
        var piece = mutableListOf<InkPoint>()
        var touched = under(stroke.points.first())
        var wasUnder = touched
        var last = stroke.points.first()
        if (!wasUnder) piece += last
        for ((a, b) in stroke.points.zipWithNext()) {
            val parts = ceil(hypot(b.x - a.x, b.y - a.y) / step).toInt().coerceAtLeast(1)
            for (i in 1..parts) {
                val point = if (i == parts) b else between(a, b, i.toFloat() / parts)
                val isUnder = under(point)
                when {
                    !wasUnder && !isUnder -> if (i == parts) piece += point
                    !wasUnder && isUnder -> {
                        if (piece.last() !== last) piece += last
                        keep(piece, pieces)
                        piece = mutableListOf()
                        touched = true
                    }
                    wasUnder && !isUnder -> piece += point
                }
                wasUnder = isUnder
                last = point
            }
        }
        if (!touched) return null
        keep(piece, pieces)
        return pieces
    }

    private fun keep(piece: List<InkPoint>, into: MutableList<List<InkPoint>>) {
        val length = piece.zipWithNext().sumOf { (a, b) -> hypot(b.x - a.x, b.y - a.y).toDouble() }
        if (piece.size >= 2 && length >= SMALLEST_PIECE) into += piece
    }

    private fun between(a: InkPoint, b: InkPoint, t: Float) = InkPoint(
        a.x + (b.x - a.x) * t,
        a.y + (b.y - a.y) * t,
        a.elapsedMillis + ((b.elapsedMillis - a.elapsedMillis) * t).toLong(),
        mix(a.pressure, b.pressure, t),
        mix(a.tiltRadians, b.tiltRadians, t),
        // An angle wraps round, so it isn't averaged.
        if (t < 0.5f) a.orientationRadians else b.orientationRadians,
    )

    private fun mix(a: Float?, b: Float?, t: Float) = if (a == null || b == null) null else a + (b - a) * t

    /** Whether [stroke] could be within [radius] of [path]: a quick check that saves measuring every stroke on the page. */
    internal fun near(stroke: Stroke, path: List<Pair<Float, Float>>, radius: Float): Boolean {
        val bounds = Bounds.of(stroke)
        return path.maxOf { it.first } + radius >= bounds.left && path.minOf { it.first } - radius <= bounds.right &&
            path.maxOf { it.second } + radius >= bounds.top && path.minOf { it.second } - radius <= bounds.bottom
    }
}
