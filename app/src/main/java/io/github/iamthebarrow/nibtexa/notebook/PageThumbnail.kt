package io.github.iamthebarrow.nibtexa.notebook

import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import io.github.iamthebarrow.nibtexa.core.export.GraphPlot
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramGeometry
import io.github.iamthebarrow.nibtexa.ink.DiagramRenderer
import io.github.iamthebarrow.nibtexa.ink.GraphRenderer
import io.github.iamthebarrow.nibtexa.ink.GraphStyle
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.iamthebarrow.nibtexa.NibTeXaApplication
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.ink.InkStrokes
import io.github.iamthebarrow.nibtexa.core.model.notebook.Paper
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.core.model.notebook.Sheet
import io.github.iamthebarrow.nibtexa.ui.theme.Nib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * A small picture of a page (Phase 12): its paper, and its ink in the pen colours (Phase 13). The
 * whole [sheet] fills the thumbnail's width (Phase 20), so give it the sheet's shape
 * ([aspect]); a taller box shows the cover below it, a shorter one cuts the page off at the bottom.
 * Typed regions (Phase 18) show as a page preview does: a bar per line of text, thicker for
 * headings, and a tinted block for an equation. Pictures and an imported PDF page are drawn small.
 */
@Composable
fun PageThumbnail(page: Page, sheet: Sheet, modifier: Modifier = Modifier) {
    val paths = remember(page.ink) {
        page.ink.strokes.map { stroke ->
            val path = Path()
            stroke.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            if (stroke.points.size == 1) path.lineTo(stroke.points[0].x + 0.5f, stroke.points[0].y)
            Triple(path, stroke.pen, InkStrokes.widthFactor(stroke.pen, stroke.kind))
        }
    }
    val typed = remember(page.regions) { page.regions.filter { it.typed } }
    // Graphs (Phase 25), worked out once, with few points: they are small here.
    val graphs = remember(typed) { typed.mapNotNull { r -> r.graph?.let { r.id to GraphPlot.of(it, THUMBNAIL_POINTS) } }.toMap() }
    val store = (LocalContext.current.applicationContext as NibTeXaApplication).pictures
    val names = remember(page.background, typed) { listOfNotNull(page.background) + typed.mapNotNull { it.picture } }
    val images by produceState(emptyMap<String, ImageBitmap>(), names) {
        value = withContext(Dispatchers.IO) { names.distinct().mapNotNull { n -> store.bitmap(n, THUMBNAIL_PIXELS)?.let { n to it.asImageBitmap() } }.toMap() }
    }
    val palette = Nib.palette
    Canvas(modifier) {
        drawRect(palette.paper)
        val scale = size.width / sheet.width
        val background = page.background?.let { images[it] }
        if (background != null) {
            drawImage(background, dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.roundToInt(), (sheet.height * scale).roundToInt()))
        } else if (page.paper != Paper.BLANK) {
            val step = page.paper.spacing * scale
            var y = step
            while (y < size.height) {
                drawLine(palette.rule, Offset(0f, y), Offset(size.width, y))
                y += step
            }
            if (page.paper == Paper.GRID) {
                var x = step
                while (x < size.width) {
                    drawLine(palette.rule, Offset(x, 0f), Offset(x, size.height))
                    x += step
                }
            }
        }
        for (r in typed) {
            val image = r.picture?.let { images[it] } ?: continue
            val b = r.bounds
            drawImage(image, dstOffset = IntOffset((b.left * scale).roundToInt(), (b.top * scale).roundToInt()), dstSize = IntSize(((b.right - b.left) * scale).roundToInt().coerceAtLeast(1), ((b.bottom - b.top) * scale).roundToInt().coerceAtLeast(1)))
        }
        scale(scale, pivot = Offset.Zero) {

            for ((path, pen, width) in paths) drawPath(path, palette.penColor(pen), style = Stroke(width = INK_WIDTH_DP * width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            for (r in typed) {
                val b = r.bounds
                when {
                    r.picture != null -> Unit
                    r.graph != null -> drawIntoCanvas {
                        GraphRenderer.draw(it.nativeCanvas, graphs.getValue(r.id), b, GraphStyle(palette.text.toArgb(), palette.rule.toArgb(), palette.paper.toArgb()))
                    }
                    r.diagram != null -> drawIntoCanvas {
                        DiagramRenderer.draw(it.nativeCanvas, DiagramGeometry.plot(r.diagram!!), b, palette.text.toArgb())
                    }
                    r.role == RegionRole.MATH -> drawRoundRect(palette.math.copy(alpha = 0.35f), Offset(b.left, b.top), Size(minOf(b.right - b.left, 260f), b.bottom - b.top), CornerRadius(8f))
                    else -> {
                        // One bar per line; the last line of a paragraph is shorter.
                        val heading = r.role == RegionRole.HEADING
                        val line = if (heading) 40f else 26f
                        val lines = maxOf(1, ((b.bottom - b.top) / line).toInt())
                        val color = palette.text.copy(alpha = if (heading) 0.8f else 0.45f)
                        for (i in 0 until lines) {
                            val width = (b.right - b.left) * (if (i == lines - 1 && lines > 1) 0.6f else 1f)
                            drawRoundRect(color, Offset(b.left, b.top + i * line + 4f), Size(width, if (heading) 20f else 10f), CornerRadius(5f))
                        }
                    }
                }
            }
        }
    }
}

/** The thumbnail's shape for [sheet]: its width over its height. */
fun Sheet.aspect(): Float = width / height

private const val THUMBNAIL_POINTS = 120
private const val INK_WIDTH_DP = 6f
private const val THUMBNAIL_PIXELS = 480
