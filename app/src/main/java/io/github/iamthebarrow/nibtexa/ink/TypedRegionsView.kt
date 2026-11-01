package io.github.iamthebarrow.nibtexa.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.view.View
import androidx.core.graphics.withMatrix
import io.github.iamthebarrow.nibtexa.core.export.GraphPlot
import io.github.iamthebarrow.nibtexa.core.export.GraphPlotted
import io.github.iamthebarrow.nibtexa.core.export.InlineMath
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.Diagram
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramGeometry
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramPart
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramPlotted
import io.github.iamthebarrow.nibtexa.core.model.notebook.Graph
import io.github.iamthebarrow.nibtexa.core.model.notebook.MarkStyle
import io.github.iamthebarrow.nibtexa.core.model.notebook.MathAlign
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageFit
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextMark
import kotlin.math.ceil

/**
 * A typed region as the page shows it (Phase 18): its words with their formatting, or its maths.
 * [latex] is an equation; [text] may hold `$…$` inline maths.
 */
data class TypedContent(
    val id: String,
    val bounds: Bounds,
    val text: String? = null,
    val marks: List<TextMark> = emptyList(),
    val headingLevel: Int = 0,
    val latex: String? = null,
    /** A picture (Phase 20): its name in the picture store, drawn to fill [bounds]. */
    val picture: String? = null,
    /** A graph (Phase 25), drawn to fill [bounds]. */
    val graph: Graph? = null,
    /** A diagram (Phase 31), drawn to fill [bounds]. */
    val diagram: Diagram? = null,
    /** Where its words sit across [bounds] (Phase 30), as in the exports. */
    val align: MathAlign = MathAlign.LEFT,
)

/** How tall [region]'s words are at [width] page units, as the page sets them (Phase 30). A little over for inline maths, set taller than words. */
val TYPED_TEXT_HEIGHT = PageFit.Measure { region, width ->
    val level = if (region.role == io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole.HEADING) region.headingLevel else 0
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedRegionsView.textSize(level)
        if (level > 0) typeface = Typeface.DEFAULT_BOLD
    }
    val text = region.text.orEmpty()
    val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(40)).setIncludePad(false).build()
    layout.height + TypedRegionsView.BOX_SLACK
}

/** The page's typed regions as they are drawn: words, equations and pictures (Phases 18 and 20). */
fun typedContent(page: io.github.iamthebarrow.nibtexa.core.model.notebook.Page): List<TypedContent> =
    page.regions.filter { it.typed }.map { r ->
        when {
            r.picture != null -> TypedContent(r.id, r.bounds, picture = r.picture)
            r.graph != null -> TypedContent(r.id, r.bounds, graph = r.graph)
            r.diagram != null -> TypedContent(r.id, r.bounds, diagram = r.diagram)
            r.role == io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole.MATH -> TypedContent(r.id, r.bounds, latex = r.confirmedLatex)
            else -> TypedContent(
                r.id, r.bounds, text = r.text, marks = r.marks, align = r.align ?: MathAlign.LEFT,
                headingLevel = if (r.role == io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole.HEADING) r.headingLevel else 0,
            )
        }
    }

/**
 * The maths [typed] needs captured, as (the key the page looks it up by, the LaTeX to capture):
 * equations in display style, and the inline maths in typed text.
 */
fun typedMaths(typed: List<TypedContent>): List<Pair<String, String>> =
    typed.mapNotNull { t -> t.latex?.let { it to "\\displaystyle $it" } } +
        typed.flatMap { t -> t.text?.let { s -> InlineMath.find(s).map { r -> s.substring(r.first + 1, r.last).let { it to it } } }.orEmpty() } +
        typed.flatMap { t -> t.diagram?.labels.orEmpty().mapNotNull { l -> l.latex?.let { it to it } } }

/** Captured maths for the page: an ink mask, and its baseline in pixels from the top (inline maths). */
class TypedMath(val bitmap: Bitmap, val baseline: Float)

/**
 * Draws typed regions (Phase 18) where ink would be: text and headings set in page units, with
 * their formatting and inline maths, and equations, both from MathLive captures ([setMaths], ink
 * on transparency drawn in the maths colour). Everything goes through the page's
 * [PageTransform], so it zooms and moves with the ink.
 */
internal class TypedRegionsView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private var transform = PageTransform(density)
    private var typed: List<TypedContent> = emptyList()
    private var maths: Map<String, TypedMath> = emptyMap()
    private var pictures: Map<String, Bitmap> = emptyMap()
    private var colors = InkColors.LIGHT
    private val layouts = mutableMapOf<TypedContent, StaticLayout>()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val picturePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val placeholder = Paint()

    fun setTransform(value: PageTransform) {
        transform = value
        invalidate()
    }

    fun setColors(value: InkColors) {
        if (value == colors) return
        colors = value
        layouts.clear()
        invalidate()
    }

    fun setTyped(value: List<TypedContent>) {
        if (value == typed) return
        typed = value
        layouts.keys.retainAll(value.toSet())
        invalidate()
    }

    /** Captured maths by LaTeX: equations by their own LaTeX, inline maths by what is between its dollars. */
    fun setMaths(value: Map<String, TypedMath>) {
        if (value == maths) return
        maths = value
        layouts.clear()
        invalidate()
    }

    /** Decoded pictures by name (Phase 20); one not loaded yet shows as a faint box. */
    fun setPictures(value: Map<String, Bitmap>) {
        if (value == pictures) return
        pictures = value
        invalidate()
    }

    /** A graph shown as it will be while a drag or pinch goes on (Phase 25), not yet stored. */
    private var graphPreview: Pair<String, Graph>? = null

    /** Graphs worked out, so that moving the page doesn't work them out again; only a change does. */
    private val plots = object : LinkedHashMap<Graph, GraphPlotted>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Graph, GraphPlotted>?) = size > MAX_PLOTS
    }

    fun setGraphPreview(regionId: String?, graph: Graph?) {
        graphPreview = if (regionId != null && graph != null) regionId to graph else null
        invalidate()
    }

    /** A diagram as it will be while a part is dragged (Phase 31), not yet stored, and the part chosen in the controls. */
    private var diagramPreview: Pair<String, Diagram>? = null
    private var diagramMark: Pair<String, DiagramPart>? = null

    private val diagramPlots = object : LinkedHashMap<Diagram, DiagramPlotted>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Diagram, DiagramPlotted>?) = size > MAX_PLOTS
    }

    fun setDiagramPreview(regionId: String?, diagram: Diagram?) {
        diagramPreview = if (regionId != null && diagram != null) regionId to diagram else null
        invalidate()
    }

    fun setDiagramMark(regionId: String?, part: DiagramPart?) {
        val next = if (regionId != null && part != null) regionId to part else null
        if (next == diagramMark) return
        diagramMark = next
        invalidate()
    }

    /** Page units per pixel of a capture, for maths at [size] page units per em. */
    private fun unitsPerPixel(size: Float) = size / (MATH_PX_PER_EM * density)

    @SuppressLint("WrongConstant")
    private fun layout(item: TypedContent): StaticLayout = layouts.getOrPut(item) {
        val size = textSize(item.headingLevel)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.text
            textSize = size
            if (item.headingLevel > 0) typeface = Typeface.DEFAULT_BOLD
        }
        val text = styled(item.text.orEmpty(), item.marks, size)
        val width = (item.bounds.right - item.bounds.left).toInt().coerceAtLeast(40)
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(
            when (item.align) {
                MathAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
                MathAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                MathAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
            },
        ).setIncludePad(false).build()
    }

    /** [raw] with its marks as spans, inline maths as images, and `\$` as a dollar. */
    private fun styled(raw: String, marks: List<TextMark>, size: Float): CharSequence {
        val out = SpannableStringBuilder()
        val at = IntArray(raw.length + 1) // where each character of [raw] ends up
        val ranges = InlineMath.find(raw)
        var i = 0
        while (i < raw.length) {
            val math = ranges.firstOrNull { it.first == i }
            when {
                math != null -> {
                    for (k in math) at[k] = out.length
                    val latex = raw.substring(math.first + 1, math.last)
                    val image = maths[latex]
                    if (image != null) {
                        val start = out.length
                        out.append(OBJECT)
                        out.setSpan(MathSpan(image, unitsPerPixel(size), colors.math), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else {
                        out.append(latex) // until MathLive has typeset it
                    }
                    i = math.last + 1
                }
                raw.startsWith("\\$", i) -> {
                    at[i] = out.length
                    at[i + 1] = out.length
                    out.append('$')
                    i += 2
                }
                else -> {
                    at[i] = out.length
                    out.append(raw[i])
                    i++
                }
            }
        }
        at[raw.length] = out.length
        for (m in marks) {
            val s = at[m.start.coerceIn(0, raw.length)]
            val e = at[m.end.coerceIn(0, raw.length)]
            if (s >= e) continue
            val span: Any = when (val style = m.style) {
                MarkStyle.Bold -> StyleSpan(Typeface.BOLD)
                MarkStyle.Italic, MarkStyle.Emphasis -> StyleSpan(Typeface.ITALIC)
                MarkStyle.Underline -> UnderlineSpan()
                MarkStyle.Typewriter -> TypefaceSpan("monospace")
                is MarkStyle.Size -> RelativeSizeSpan(style.size.scale)
                is MarkStyle.Color -> ForegroundColorSpan(style.color.onPaper(colors == InkColors.DARK)?.let { 0xFF000000.toInt() or it } ?: colors.text)
                is MarkStyle.Link -> ForegroundColorSpan(colors.selection)
                is MarkStyle.Footnote -> UnderlineSpan()
            }
            out.setSpan(span, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    /** Inline maths in typed text: the capture sitting on the baseline, in the maths colour. */
    private class MathSpan(private val math: TypedMath, private val units: Float, color: Int) : ReplacementSpan() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) }
        private val above = math.baseline * units
        private val below = (math.bitmap.height - math.baseline) * units
        private val width = math.bitmap.width * units

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            fm?.let {
                it.ascent = minOf(it.ascent, -ceil(above).toInt())
                it.top = minOf(it.top, it.ascent)
                it.descent = maxOf(it.descent, ceil(below).toInt())
                it.bottom = maxOf(it.bottom, it.descent)
            }
            return ceil(width).toInt() + 1
        }

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            canvas.drawBitmap(math.bitmap, null, RectF(x, y - above, x + width, y + below), this.paint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (typed.isEmpty()) return
        imagePaint.colorFilter = PorterDuffColorFilter(colors.math, PorterDuff.Mode.SRC_IN)
        canvas.withMatrix(transform.matrix) {
            for (item in typed) {
                val b = item.bounds
                if (item.picture != null) {
                    val bitmap = pictures[item.picture]
                    if (bitmap != null) {
                        drawBitmap(bitmap, null, RectF(b.left, b.top, b.right, b.bottom), picturePaint)
                    } else {
                        placeholder.color = colors.rule
                        drawRect(b.left, b.top, b.right, b.bottom, placeholder)
                    }
                } else if (item.graph != null) {
                    val graph = graphPreview?.takeIf { it.first == item.id }?.second ?: item.graph
                    GraphRenderer.draw(this, plots.getOrPut(graph) { GraphPlot.of(graph) }, b, GraphStyle.of(colors))
                } else if (item.diagram != null) {
                    val diagram = diagramPreview?.takeIf { it.first == item.id }?.second ?: item.diagram
                    val units = unitsPerPixel(DiagramRenderer.MATH_SIZE)
                    DiagramRenderer.draw(
                        this, diagramPlots.getOrPut(diagram) { DiagramGeometry.plot(diagram) }, b, colors.text,
                        maths = { latex -> maths[latex]?.let { DiagramMath(it.bitmap, it.bitmap.width * units, it.bitmap.height * units) } },
                        selected = diagramMark?.takeIf { it.first == item.id }?.second,
                        mark = colors.selection,
                    )
                } else if (item.latex != null) {
                    val image = maths[item.latex] ?: continue
                    // Maths a little larger than the text, made smaller if it is wider than its box.
                    val units = unitsPerPixel(TEXT_SIZE * MATH_SCALE)
                    var w = image.bitmap.width * units
                    var h = image.bitmap.height * units
                    val room = b.right - b.left
                    if (w > room && room > 0f) {
                        h *= room / w
                        w = room
                    }
                    drawBitmap(image.bitmap, null, RectF(b.left, b.top, b.left + w, b.top + h), imagePaint)
                } else {
                    save()
                    translate(b.left, b.top)
                    layout(item).draw(this)
                    restore()
                }
            }
        }
    }

    companion object {
        /** Typed text is this many page units high, like neat handwriting. */
        const val TEXT_SIZE = 18f
        const val BOX_SLACK = 8f

        fun textSize(headingLevel: Int): Float = when (headingLevel) {
            1 -> 30f
            2 -> 25f
            3 -> 21f
            else -> TEXT_SIZE
        }

        /** Display maths is set a little larger than the text. */
        private const val MATH_SCALE = 1.3f

        /** The captures' size: MathLive's inline maths is 22 CSS pixels per em. */
        const val MATH_PX_PER_EM = 22f

        private const val MAX_PLOTS = 16
        private const val OBJECT = "￼"
    }
}
