package io.github.iamthebarrow.nibtexa.export

import android.app.Activity
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import io.github.iamthebarrow.nibtexa.NibTeXaApplication
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageView
import io.github.iamthebarrow.nibtexa.core.model.notebook.Paper
import io.github.iamthebarrow.nibtexa.core.model.notebook.Sheet
import io.github.iamthebarrow.nibtexa.ink.InkColors
import io.github.iamthebarrow.nibtexa.ink.InkStrokes
import io.github.iamthebarrow.nibtexa.ink.PageTransform
import io.github.iamthebarrow.nibtexa.ink.TypedMath
import io.github.iamthebarrow.nibtexa.ink.TypedRegionsView
import io.github.iamthebarrow.nibtexa.ink.typedContent
import io.github.iamthebarrow.nibtexa.ink.typedMaths
import io.github.iamthebarrow.nibtexa.storage.PictureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The booklet's pages as they look (Phase 20): one PDF page per page, the booklet's paper size,
 * with the paper's lines or the imported PDF page, then pictures and typed text, then the ink in
 * the pen colours on light paper. Scratch and everything else on the sheet is drawn too; nothing
 * on the desk is. Typed maths is captured by MathLive ([MathCapture]), which needs [activity].
 */
class AnnotatedPdf(
    private val activity: Activity,
    private val pictures: PictureStore = (activity.application as NibTeXaApplication).pictures,
) {
    suspend fun render(booklet: Booklet): ByteArray {
        val sheet = booklet.info.sheet
        val typed = booklet.pages.associate { it.id to typedContent(it) }
        val wanted = typed.values.flatMap { typedMaths(it) }
        val latex = wanted.map { it.second }.distinct()
        val captured = if (latex.isEmpty()) emptyMap() else latex.zip(MathCapture(activity).capture(latex, inline = true)).toMap()
        val maths = wanted.mapNotNull { (key, l) -> captured[l]?.let { key to TypedMath(it.bitmap, it.baseline) } }.toMap()
        // The typed layer is drawn by the page's own view, so the PDF shows what the page shows.
        val layer = TypedRegionsView(activity)
        val density = activity.resources.displayMetrics.density
        layer.setTransform(PageTransform(density).apply { set(PageView(1f / density)) })
        layer.setColors(InkColors.LIGHT)
        return withContext(Dispatchers.Default) {
            val document = PdfDocument()
            val renderer = CanvasStrokeRenderer.create()
            val toPoints = Matrix().apply { setScale(POINTS_PER_UNIT, POINTS_PER_UNIT) }
            try {
                booklet.pages.forEachIndexed { i, page ->
                    val info = PdfDocument.PageInfo.Builder(sheet.widthPoints.toInt(), sheet.heightPoints.toInt(), i + 1).create()
                    val pdfPage = document.startPage(info)
                    val canvas = pdfPage.canvas
                    canvas.concat(toPoints)
                    canvas.clipRect(0f, 0f, sheet.width, sheet.height)
                    canvas.drawColor(InkColors.LIGHT.paper)
                    val background = page.background?.let { pictures.bitmap(it) }
                    if (background != null) {
                        canvas.drawBitmap(background, null, RectF(0f, 0f, sheet.width, sheet.height), IMAGE)
                    } else {
                        lines(canvas, page.paper, sheet)
                    }
                    val items = typed[page.id].orEmpty()
                    if (items.isNotEmpty()) {
                        layer.setTyped(items)
                        layer.setMaths(maths)
                        layer.setPictures(items.mapNotNull { it.picture }.distinct().mapNotNull { n -> pictures.bitmap(n)?.let { n to it } }.toMap())
                        layer.draw(canvas)
                    }
                    for (stroke in page.ink.strokes) {
                        renderer.draw(canvas, InkStrokes.toInkStroke(stroke.tool, stroke.points, InkStrokes.penBrush(stroke.pen, colors = InkColors.LIGHT, kind = stroke.kind)), toPoints)
                    }
                    document.finishPage(pdfPage)
                }
                ByteArrayOutputStream().use { out ->
                    document.writeTo(out)
                    out.toByteArray()
                }
            } finally {
                document.close()
            }
        }
    }

    private fun lines(canvas: android.graphics.Canvas, paper: Paper, sheet: Sheet) {
        if (paper == Paper.BLANK) return
        var y = paper.spacing
        while (y < sheet.height) {
            canvas.drawLine(0f, y, sheet.width, y, RULE)
            y += paper.spacing
        }
        if (paper == Paper.GRID) {
            var x = paper.spacing
            while (x < sheet.width) {
                canvas.drawLine(x, 0f, x, sheet.height, RULE)
                x += paper.spacing
            }
        }
    }

    private companion object {
        /** Page units are 1/160 inch; PDF points 1/72. */
        const val POINTS_PER_UNIT = 72f / Sheet.UNITS_PER_INCH

        val IMAGE = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val RULE = Paint().apply {
            color = InkColors.LIGHT.rule
            strokeWidth = 1f
        }
    }
}
