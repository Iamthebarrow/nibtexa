package io.github.iamthebarrow.nibtexa.export

import io.github.iamthebarrow.nibtexa.core.export.GraphPlot
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramGeometry
import io.github.iamthebarrow.nibtexa.ink.DiagramMath
import io.github.iamthebarrow.nibtexa.ink.DiagramRenderer
import io.github.iamthebarrow.nibtexa.ink.GraphRenderer
import io.github.iamthebarrow.nibtexa.ink.GraphStyle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.core.graphics.createBitmap
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import io.github.iamthebarrow.nibtexa.core.export.FigureRenderer
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.ink.InkColors
import io.github.iamthebarrow.nibtexa.ink.InkStrokes
import io.github.iamthebarrow.nibtexa.storage.PictureStore
import java.io.IOException
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.min

/**
 * Renders a figure region's ink, cropped with padding, in the strokes' pen colours on white,
 * without paper lines or overlays. Used for export (PNG) and the reading view (bitmap), which
 * draws it in the theme's pen colours on transparency, so it sits on the dark page too.
 * Safe off the main thread: it draws into a software bitmap.
 */
class InkFigureRenderer(private val pictures: PictureStore? = null, private val pixelsPerDp: Float = PIXELS_PER_DP) : FigureRenderer {
    private val renderer = CanvasStrokeRenderer.create()

    override fun render(page: Page, region: Region): ByteArray {
        // A picture (Phase 20) is exported as it is stored.
        region.picture?.let { name ->
            return pictures?.file(name)?.readBytes() ?: throw IOException("A picture is missing from the app's storage")
        }
        val bitmap = bitmap(page, region)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    /** The figure as a bitmap the caller may recycle: its ink, or a copy of its picture (Phase 20). */
    fun bitmap(page: Page, region: Region, colors: InkColors = InkColors.LIGHT, background: Int = Color.WHITE, maths: (String) -> DiagramMath? = { null }): Bitmap {
        region.picture?.let { name ->
            val picture = pictures?.bitmap(name) ?: throw IOException("A picture is missing from the app's storage")
            // The store caches its bitmaps, so the caller gets its own.
            return picture.copy(Bitmap.Config.ARGB_8888, false)
        }
        region.diagram?.let { diagram ->
            val b = region.bounds
            val scale = min(pixelsPerDp, MAX_SIDE / maxOf(b.right - b.left, b.bottom - b.top, 1f))
            val bitmap = createBitmap(ceil((b.right - b.left) * scale).toInt().coerceAtLeast(1), ceil((b.bottom - b.top) * scale).toInt().coerceAtLeast(1))
            bitmap.eraseColor(background)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            canvas.translate(-b.left, -b.top)
            DiagramRenderer.draw(canvas, DiagramGeometry.plot(diagram), b, colors.text, maths)
            return bitmap
        }
        region.graph?.let { graph ->
            val b = region.bounds
            val scale = min(pixelsPerDp, MAX_SIDE / maxOf(b.right - b.left, b.bottom - b.top, 1f))
            val bitmap = createBitmap(ceil((b.right - b.left) * scale).toInt().coerceAtLeast(1), ceil((b.bottom - b.top) * scale).toInt().coerceAtLeast(1))
            bitmap.eraseColor(background)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            canvas.translate(-b.left, -b.top)
            val style = GraphStyle.of(colors).let { if (background == Color.TRANSPARENT) it else it.copy(paper = background) }
            GraphRenderer.draw(canvas, GraphPlot.of(graph), b, style)
            return bitmap
        }
        val members = page.membersOf(region)
        val strokes = page.ink.strokes.filter { it.id in members }
        val bounds = Bounds.of(strokes) ?: region.bounds
        // Very large figures are scaled down to keep the bitmap bounded.
        val wanted = maxOf(bounds.right - bounds.left, bounds.bottom - bounds.top) + 2 * PADDING_DP
        val scale = min(pixelsPerDp, MAX_SIDE / wanted)
        val width = ceil((bounds.right - bounds.left + 2 * PADDING_DP) * scale).toInt().coerceAtLeast(1)
        val height = ceil((bounds.bottom - bounds.top + 2 * PADDING_DP) * scale).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(width, height)
        bitmap.eraseColor(background)
        val transform = Matrix().apply {
            setTranslate(PADDING_DP - bounds.left, PADDING_DP - bounds.top)
            postScale(scale, scale)
        }
        val canvas = Canvas(bitmap)
        canvas.concat(transform)
        for (stroke in strokes) {
            renderer.draw(canvas, InkStrokes.toInkStroke(stroke.tool, stroke.points, InkStrokes.penBrush(stroke.pen, colors = colors, kind = stroke.kind)), transform)
        }
        return bitmap
    }

    private companion object {
        const val PIXELS_PER_DP = 2f
        const val PADDING_DP = 8f
        const val MAX_SIDE = 4096f
    }
}
