package io.github.iamthebarrow.nibtexa.ink

import android.util.Log
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.PenKind
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import androidx.ink.strokes.Stroke as InkStroke

/**
 * The page's colours as ARGB ints (Phase 13, palette A; they match `ui/theme/Theme.kt`): paper,
 * ruling, each pen's ink, region tints and the selection. Display only; the stored ink has no
 * colour, only a pen.
 */
data class InkColors(
    val paper: Int,
    val rule: Int,
    val math: Int,
    val text: Int,
    val drawing: Int,
    val scratch: Int,
    val selection: Int,
    val attention: Int,
    /** Round the sheet (Phase 19). */
    val desk: Int = 0xFFE7E1D8.toInt(),
    /** The sheet's edge. */
    val edge: Int = 0x22000000,
) {
    companion object {
        val LIGHT = InkColors(
            paper = 0xFFFFFDF9.toInt(), rule = 0xFFE3EAF0.toInt(),
            math = 0xFF2F63A8.toInt(), text = 0xFF3B3A38.toInt(), drawing = 0xFF2F7D4C.toInt(), scratch = 0xFF9A948A.toInt(),
            selection = 0xFF2F7A74.toInt(), attention = 0xFFC2553B.toInt(),
        )
        val DARK = InkColors(
            paper = 0xFF232220.toInt(), rule = 0xFF33312D.toInt(),
            math = 0xFF93B7EE.toInt(), text = 0xFFDAD5CD.toInt(), drawing = 0xFF8BCB9F.toInt(), scratch = 0xFF8F897F.toInt(),
            selection = 0xFF73C4BA.toInt(), attention = 0xFFF09A82.toInt(),
            desk = 0xFF141312.toInt(), edge = 0x33FFFFFF,
        )
    }
}

/** Brushes and conversion from the project's stroke model to androidx.ink for rendering. */
internal object InkStrokes {
    private const val TAG = "InkStrokes"

    /** Pen width in canvas units (dp). */
    const val PEN_SIZE_DP = 2.5f

    private const val BALLPOINT_SIZE_DP = 1.6f
    private const val FELT_SIZE_DP = 3.8f
    private const val EPSILON_DP = 0.05f

    /** Each pen has its own colour, so what a stroke will become is visible on the page. */
    fun color(pen: Pen, colors: InkColors = InkColors.LIGHT): Int = when (pen) {
        Pen.MATH -> colors.math
        Pen.TEXT -> colors.text
        Pen.DRAWING -> colors.drawing
        Pen.SCRATCH -> colors.scratch
    }

    /**
     * [colors] is the page's (light or dark); exports always use [InkColors.LIGHT], the print colours.
     * The [kind] sets the look (Phase 22): ballpoint is a thin even line, gel the pressure pen, and felt
     * a thick soft even line. Scratch is a soft grey pencil whatever the kind: it can be read, and stays behind the notes.
     */
    fun penBrush(pen: Pen = Pen.MATH, scale: Float = 1f, colors: InkColors = InkColors.LIGHT, kind: PenKind = PenKind.GEL): Brush = when {
        pen == Pen.SCRATCH -> Brush.createWithColorIntArgb(StockBrushes.pressurePen(), color(pen, colors), PEN_SIZE_DP * scale, EPSILON_DP * scale)
        kind == PenKind.BALLPOINT -> Brush.createWithColorIntArgb(StockBrushes.marker(), color(pen, colors), BALLPOINT_SIZE_DP * scale, EPSILON_DP * scale)
        kind == PenKind.FELT -> Brush.createWithColorIntArgb(StockBrushes.marker(), color(pen, colors), FELT_SIZE_DP * scale, EPSILON_DP * scale)
        else -> Brush.createWithColorIntArgb(StockBrushes.pressurePen(), color(pen, colors), PEN_SIZE_DP * scale, EPSILON_DP * scale)
    }

    /** A stroke's width relative to the gel pen's, for simple drawings of it such as thumbnails. */
    fun widthFactor(pen: Pen, kind: PenKind): Float = when {
        pen == Pen.SCRATCH -> 1f
        kind == PenKind.BALLPOINT -> BALLPOINT_SIZE_DP / PEN_SIZE_DP
        kind == PenKind.FELT -> FELT_SIZE_DP / PEN_SIZE_DP
        else -> 1f
    }

    /** One brush for every pen and kind, in [colors]. */
    fun allBrushes(colors: InkColors): Map<Pair<Pen, PenKind>, Brush> =
        Pen.entries.flatMap { pen -> PenKind.entries.map { kind -> (pen to kind) to penBrush(pen, colors = colors, kind = kind) } }.toMap()

    /**
     * Builds a renderable stroke in canvas units. Points that androidx.ink rejects are
     * skipped and logged rather than crashing the renderer, so a damaged stroke still
     * shows as much as possible.
     */
    fun toInkStroke(tool: ToolType, points: List<InkPoint>, brush: Brush): InkStroke {
        val type = when (tool) {
            ToolType.STYLUS, ToolType.ERASER -> InputToolType.STYLUS
            ToolType.FINGER -> InputToolType.TOUCH
            ToolType.MOUSE -> InputToolType.MOUSE
            ToolType.UNKNOWN -> InputToolType.UNKNOWN
        }
        val batch = MutableStrokeInputBatch()
        for (p in points) {
            try {
                batch.add(
                    type = type,
                    x = p.x,
                    y = p.y,
                    elapsedTimeMillis = p.elapsedMillis,
                    pressure = p.pressure ?: StrokeInput.NO_PRESSURE,
                    tiltRadians = p.tiltRadians ?: StrokeInput.NO_TILT,
                    orientationRadians = p.orientationRadians ?: StrokeInput.NO_ORIENTATION,
                )
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Skipping point rejected by androidx.ink: ${e.message}")
            }
        }
        return InkStroke(brush, batch)
    }
}
