package io.github.iamthebarrow.nibtexa.core.model

/** The kind of pointer that produced a stroke. */
enum class ToolType {
    STYLUS,
    ERASER,
    FINGER,
    MOUSE,
    UNKNOWN,
}

/**
 * The pen a stroke was written with. It decides what the ink becomes: the role of the
 * region it ends up in. Only [MATH] is offered in Phase 5; the others arrive with the
 * Phase 6 tool palette.
 */
enum class Pen {
    /** Handwritten mathematics, converted to LaTeX. */
    MATH,

    /** Handwritten prose, converted to text. */
    TEXT,

    /** A drawing, exported as an image and never sent to recognition. */
    DRAWING,

    /** Visible scratch work, excluded from export. */
    SCRATCH,
}

/**
 * One sampled point of a stroke.
 *
 * Coordinates are in canvas units (density-independent pixels). [elapsedMillis] is
 * measured from the first point of the stroke. Optional channels are `null` when the
 * input device does not report them; they are never invented.
 *
 * @property pressure normalized to `0..1`.
 * @property tiltRadians `0` (perpendicular to the screen) to `PI / 2` (flat).
 * @property orientationRadians `0` until (but excluding) `2 * PI`.
 */
data class InkPoint(
    val x: Float,
    val y: Float,
    val elapsedMillis: Long,
    val pressure: Float? = null,
    val tiltRadians: Float? = null,
    val orientationRadians: Float? = null,
)

/**
 * The kind of pen a stroke looks written with (Phase 22). It only changes how the ink looks: its
 * role comes from [Pen], and recognition reads the points. Strokes from before kinds count as [GEL],
 * which looks the same as the one pen there was.
 */
enum class PenKind { BALLPOINT, GEL, FELT }

/** A finished stroke as captured from input, before it is given an identity in a document. */
data class CapturedStroke(
    val tool: ToolType,
    /** Wall-clock-independent event time of the first point, in milliseconds. */
    val startTimeMillis: Long,
    val points: List<InkPoint>,
    val pen: Pen = Pen.MATH,
    val kind: PenKind = PenKind.GEL,
) {
    init {
        require(points.isNotEmpty()) { "A stroke needs at least one point" }
    }
}

/** A stroke stored in an [InkDocument]. [id] is unique within its document. */
data class Stroke(
    val id: Long,
    val tool: ToolType,
    val startTimeMillis: Long,
    val points: List<InkPoint>,
    val pen: Pen = Pen.MATH,
    val kind: PenKind = PenKind.GEL,
) {
    init {
        require(points.isNotEmpty()) { "A stroke needs at least one point" }
    }
}

/** An immutable page of ink. */
data class InkDocument(
    val strokes: List<Stroke> = emptyList(),
    /** The id the next added stroke will receive. Never reused, even after undo. */
    val nextStrokeId: Long = 1,
) {
    fun withStroke(captured: CapturedStroke): InkDocument = copy(
        strokes = strokes + Stroke(nextStrokeId, captured.tool, captured.startTimeMillis, captured.points, captured.pen, captured.kind),
        nextStrokeId = nextStrokeId + 1,
    )

    companion object {
        val EMPTY = InkDocument()
    }
}
