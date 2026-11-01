package io.github.iamthebarrow.nibtexa.export

import io.github.iamthebarrow.nibtexa.core.export.GraphPlot
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramGeometry
import io.github.iamthebarrow.nibtexa.ink.DiagramMath
import io.github.iamthebarrow.nibtexa.ink.DiagramRenderer
import io.github.iamthebarrow.nibtexa.ink.GraphRenderer
import io.github.iamthebarrow.nibtexa.ink.GraphStyle
import io.github.iamthebarrow.nibtexa.ink.InkColors
import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import io.github.iamthebarrow.nibtexa.core.export.Cover
import io.github.iamthebarrow.nibtexa.core.export.ExportProblem
import io.github.iamthebarrow.nibtexa.core.export.Flow
import io.github.iamthebarrow.nibtexa.core.export.Inline
import io.github.iamthebarrow.nibtexa.core.export.PdfBlock
import io.github.iamthebarrow.nibtexa.core.export.PdfLayout
import io.github.iamthebarrow.nibtexa.core.export.PdfPaginator
import io.github.iamthebarrow.nibtexa.core.export.Placement
import io.github.iamthebarrow.nibtexa.core.export.Span
import io.github.iamthebarrow.nibtexa.core.export.Style
import io.github.iamthebarrow.nibtexa.core.export.Theorem
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.MathAlign
import io.github.iamthebarrow.nibtexa.core.model.notebook.TheoremKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.ceil

/**
 * Writes a booklet as an A4 PDF on the device (Phase 12). Same content and reading order as the
 * LaTeX export ([PdfLayout]), typeset by the app rather than by LaTeX:
 * - a cover page with the title, author and dates, without a page number; the notes are
 *   numbered from 1 after it (Phase 14);
 * - with headings, a table of contents after the cover, also unnumbered, giving each heading's
 *   page; the notes start on a new page (Phase 15). Finding the pages takes a first, dry pass
 *   that lays everything out without drawing;
 * - headings, paragraphs and captions are real text ([StaticLayout] on the PDF
 *   canvas, so fonts are embedded and the text can be selected and searched). Paragraphs break
 *   between lines across pages; a heading is kept with what follows;
 * - equations are rendered by MathLive ([MathCapture]), cropped, and placed as images, centred or
 *   flush left or right ([PdfBlock.Math.align]), numbered on the right when numbered;
 * - figures are drawn from the ink, with their caption below.
 *
 * Phase 16: formatting (bold, italic, underline, typewriter, LaTeX's sizes, colours, links in
 * blue), inline maths captured by MathLive and set on the text's baseline, footnotes at the
 * bottom of their page, theorem-style blocks with their labels, nested lists and page breaks.
 * Links cannot be clicked in this PDF; they can in the LaTeX build.
 *
 * Use from the main thread; [render] suspends while the maths lays out.
 */
class BookletPdf(
    private val activity: Activity,
    private val figures: InkFigureRenderer = InkFigureRenderer((activity.application as io.github.iamthebarrow.nibtexa.NibTeXaApplication).pictures),
) {

    sealed interface Result {
        class Written(val bytes: ByteArray, val pages: Int) : Result
        data class Invalid(val problems: List<ExportProblem>) : Result
    }

    suspend fun render(booklet: Booklet): Result {
        val blocks = when (val layout = PdfLayout.blocks(booklet)) {
            is PdfLayout.Result.Invalid -> return Result.Invalid(layout.problems)
            is PdfLayout.Result.Blocks -> layout.blocks
        }
        val capture = MathCapture(activity)
        val equations = capture.capture(blocks.filterIsInstance<PdfBlock.Math>().map { it.latex })
        val inline = try {
            capture.capture(blocks.flatMap(::contentOf).filterIsInstance<Inline.Math>().map { it.latex }, inline = true)
        } catch (e: Throwable) {
            equations.forEach { it.bitmap.recycle() }
            throw e
        }
        // Diagrams' maths labels (Phase 31), each once.
        val labelMaths = booklet.pages.flatMap { p -> p.regions.flatMap { r -> r.diagram?.labels.orEmpty().mapNotNull { it.latex } } }.distinct()
        val labels = try {
            capture.capture(labelMaths, inline = true)
        } catch (e: Throwable) {
            (equations + inline).forEach { it.bitmap.recycle() }
            throw e
        }
        val density = activity.resources.displayMetrics.density
        val labelImages = labelMaths.zip(labels).associate { (latex, c) ->
            val units = DiagramRenderer.MATH_SIZE / (io.github.iamthebarrow.nibtexa.ink.DIAGRAM_MATH_PX_PER_EM * density)
            latex to DiagramMath(c.bitmap, c.bitmap.width * units, c.bitmap.height * units)
        }
        try {
            val contents = if (blocks.any { it is PdfBlock.Heading }) Typesetter(booklet, equations, inline, labelImages, dry = true).also { it.typeset(blocks) }.headings else emptyList()
            val (bytes, pages) = Typesetter(booklet, equations, inline, labelImages, contents = contents).typeset(blocks)
            return Result.Written(bytes, pages)
        } finally {
            (equations + inline + labels).forEach { it.bitmap.recycle() }
        }
    }

    private fun contentOf(block: PdfBlock): List<Inline> = when (block) {
        is PdfBlock.Heading -> block.content
        is PdfBlock.Paragraph -> block.content
        is PdfBlock.ListItem -> block.content
        else -> emptyList()
    }

    // ---- Typesetting ------------------------------------------------------------------------

    /** A heading in the table of contents, and the page of the notes it is on (from 1). */
    private data class Entry(val level: Int, val text: String, val page: Int)

    /** Marks a footnote reference in laid-out text, so its line knows the note it carries. */
    private class NoteRef(val number: Int, val text: String)

    /**
     * Inline maths in a line of text: the captured bitmap at [size] times the body size, sitting
     * on the baseline, in [color].
     */
    private class MathSpan(private val math: MathCapture.Captured, private val size: Float, color: Int) : ReplacementSpan() {
        private val points = size / math.scale
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) }
        private val width = math.bitmap.width * points
        private val above = math.baseline * points
        private val below = (math.bitmap.height - math.baseline) * points

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

    /**
     * Lays out and draws the blocks, page by page. Units are PDF points. A [dry] run draws
     * nothing and only records where the [headings] land; [contents] are the entries for the
     * table of contents, none for a booklet without headings.
     */
    private inner class Typesetter(
        private val booklet: Booklet,
        equationList: List<MathCapture.Captured>,
        inlineList: List<MathCapture.Captured>,
        private val labelImages: Map<String, DiagramMath>,
        private val dry: Boolean = false,
        private val contents: List<Entry> = emptyList(),
    ) {
        private val equations = equationList.iterator()
        private val inlineMath = inlineList.iterator()
        private val document = PdfDocument()
        /** The booklet's paper (Phase 19). */
        private val paper = PdfLayout.page(booklet)
        private var pages = PdfPaginator(paper.contentHeight)
        private var page: PdfDocument.Page? = null
        private var pageNumber = -1

        /** Footnotes waiting to be drawn at the bottom of their page. */
        private val notes = mutableMapOf<Int, MutableList<StaticLayout>>()

        /** Headings as they were placed (filled in by a dry run). */
        val headings = mutableListOf<Entry>()

        // Columns (Phase 30). Where the text is across the page. Each column runs down the pages by itself, so what they draw
        // waits until both are laid out: a PDF page can't be gone back to.
        private var left = 0f
        private var width = paper.contentWidth

        // A page's own margin (Phase 30), and what each page was laid out with, for what is drawn when it is finished.
        private var margin = paper.margin
        private var contentWidth = paper.contentWidth
        private val pageMargins = mutableMapOf<Int, Float>()

        private fun setMargin(points: Float) {
            pages.breakPage()
            margin = points
            contentWidth = paper.width - 2 * points
            width = contentWidth
            pages.setPageHeight(paper.height - 2 * points)
        }
        private var main: PdfPaginator? = null
        private var firstColumn: PdfPaginator? = null
        private var waiting: MutableList<Pair<Int, (Canvas) -> Unit>>? = null
        private val waitingBitmaps = mutableListOf<android.graphics.Bitmap>()

        private fun startColumns() {
            main = pages
            pages = pages.fork()
            width = (contentWidth - COLUMN_GAP) / 2
            left = 0f
            if (!dry) waiting = mutableListOf()
        }

        private fun nextColumn() {
            firstColumn = pages
            pages = main!!.fork()
            left = width + COLUMN_GAP
        }

        private fun endColumns() {
            val second = pages
            pages = main!!.also { it.continueAfter(listOfNotNull(firstColumn, second)) }
            main = null
            firstColumn = null
            left = 0f
            width = contentWidth
            val drawn = waiting
            waiting = null
            drawn?.sortedBy { it.first }?.forEach { (number, body) -> body(pageCanvas(number)) }
            waitingBitmaps.forEach { it.recycle() }
            waitingBitmaps.clear()
        }

        /** Unnumbered pages before the notes: the cover and the table of contents. */
        private var frontPages = 0
        private val scale = activity.resources.displayMetrics.density * MathCapture.PAGE_SCALE

        suspend fun typeset(blocks: List<PdfBlock>): Pair<ByteArray, Int> {
            try {
                for (block in blocks) {
                    when (block) {
                        is PdfBlock.CoverPage -> {
                            cover(block.cover)
                            if (contents.isNotEmpty()) contentsPages()
                        }
                        is PdfBlock.TitleBlock -> titleBlock(block.cover)
                        is PdfBlock.Heading -> {
                            val number = block.number?.let { "$it  " }.orEmpty()
                            text(
                                SpannableStringBuilder(number).append(rich(block.content, HEADINGS[block.level - 1])), HEADINGS[block.level - 1], block.align.layout,
                                before = 12f, after = 2f, heading = block.level to number + Flow.plain(block.content),
                            )
                        }
                        is PdfBlock.Paragraph -> text(paragraph(block), BODY, if (block.theorem != null) Layout.Alignment.ALIGN_NORMAL else block.align.layout, before = 6f)
                        is PdfBlock.Math -> equation(equations.next(), block.align, block.number)
                        is PdfBlock.Figure -> figure(block)
                        is PdfBlock.ListItem -> listItem(block)
                        is PdfBlock.PageBreak -> pages.breakPage()
                        is PdfBlock.Margin -> setMargin(block.points)
                        is PdfBlock.ColumnsStart -> startColumns()
                        is PdfBlock.ColumnBreak -> nextColumn()
                        is PdfBlock.ColumnsEnd -> endColumns()
                    }
                }
                if (dry) return ByteArray(0) to pages.pageCount
                if (frontPages == 0) startPageIfNone()
                page?.let(::finish)
                val bytes = withContext(Dispatchers.IO) { ByteArrayOutputStream().also(document::writeTo).toByteArray() }
                return bytes to frontPages + pageNumber + 1
            } finally {
                document.close()
            }
        }

        // ---- Rich text (Phase 16) ----------------------------------------------------------------

        /** A paragraph's text; a theorem-style block starts with its label, and a proof ends with □. */
        private fun paragraph(block: PdfBlock.Paragraph): CharSequence {
            val theorem = block.theorem ?: return rich(block.content, BODY)
            val text = SpannableStringBuilder()
            val label = label(theorem)
            text.append(label, StyleSpan(if (theorem.kind == TheoremKind.REMARK || theorem.kind == TheoremKind.PROOF) Typeface.ITALIC else Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append(" ")
            text.append(rich(block.content, BODY, italic = theorem.kind.italicBody))
            if (theorem.kind == TheoremKind.PROOF) text.append(" □")
            return text
        }

        private fun label(theorem: Theorem) = theorem.kind.label + (theorem.number?.let { " $it" } ?: "") + "."

        /** [content] as styled text: LaTeX's formatting as Android spans, inline maths as [MathSpan]s. */
        private fun rich(content: List<Inline>, paint: TextPaint, italic: Boolean = false): CharSequence {
            val text = SpannableStringBuilder()
            for (piece in content) {
                val start = text.length
                when (piece) {
                    is Inline.Text -> {
                        text.append(piece.text)
                        style(text, start, piece.style, italic)
                    }
                    is Inline.Math -> {
                        val math = inlineMath.next()
                        text.append(OBJECT)
                        val size = piece.style.size?.scale ?: 1f
                        val color = piece.style.color?.let { Color.rgb(it.rgb shr 16 and 0xFF, it.rgb shr 8 and 0xFF, it.rgb and 0xFF) } ?: paint.color
                        // Inline maths is captured for 11 pt text; headings scale it to their size.
                        text.setSpan(MathSpan(math, size * paint.textSize / BODY.textSize, color), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    is Inline.Note -> {
                        text.append(piece.number.toString())
                        text.setSpan(SuperscriptSpan(), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        text.setSpan(RelativeSizeSpan(NOTE_REF_SIZE), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        text.setSpan(NoteRef(piece.number, piece.text), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            return text
        }

        private fun style(text: SpannableStringBuilder, start: Int, style: Style, baseItalic: Boolean) {
            val end = text.length
            if (start >= end) return
            fun span(what: Any) = text.setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            // \emph switches to upright inside italic, as in LaTeX.
            val italic = (baseItalic || style.italic) != style.emphasis
            when {
                style.bold && italic -> span(StyleSpan(Typeface.BOLD_ITALIC))
                style.bold -> span(StyleSpan(Typeface.BOLD))
                italic -> span(StyleSpan(Typeface.ITALIC))
            }
            if (style.underline) span(UnderlineSpan())
            if (style.typewriter) span(TypefaceSpan("monospace"))
            style.size?.let { span(RelativeSizeSpan(it.scale)) }
            when {
                style.link != null -> span(ForegroundColorSpan(LINK_COLOR))
                style.color != null -> span(ForegroundColorSpan(Color.rgb(style.color!!.rgb shr 16 and 0xFF, style.color!!.rgb shr 8 and 0xFF, style.color!!.rgb and 0xFF)))
            }
        }

        /** The footnotes referenced in [layout]'s [line], laid out for the page bottom. */
        private fun notesIn(layout: StaticLayout, line: Int): List<StaticLayout> {
            val text = layout.text as? Spanned ?: return emptyList()
            return text.getSpans(layout.getLineStart(line), layout.getLineEnd(line), NoteRef::class.java).map { ref ->
                val note = SpannableStringBuilder().append(ref.number.toString(), SuperscriptSpan(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE).append(" ").append(ref.text)
                note.setSpan(RelativeSizeSpan(NOTE_REF_SIZE), 0, ref.number.toString().length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                layout(note, NOTE, Layout.Alignment.ALIGN_NORMAL, contentWidth)
            }
        }

        /** Places [layout]'s lines, each with room for its footnotes, and draws them with [draw]. */
        private fun placeLines(layout: StaticLayout, onLine: (line: Int, p: Placement) -> Unit = { _, _ -> }, paint: (Canvas, Placement) -> Unit) {
            for (line in 0 until layout.lineCount) {
                val lineNotes = notesIn(layout, line)
                val placed = pages.place(Span(layout.getLineTop(line).toFloat(), layout.getLineBottom(line).toFloat()), notes = lineNotes.sumOf { it.height.toDouble() }.toFloat())
                placed.firstOrNull()?.let { notes.getOrPut(it.page) { mutableListOf() } += lineNotes }
                for (p in placed) {
                    onLine(line, p)
                    draw(p) { canvas -> paint(canvas, p) }
                }
            }
        }

        // ---- Blocks ---------------------------------------------------------------------------

        /**
         * A list item: its bullet or number, then the text with a hanging indent, so wrapped lines
         * line up under the first (Phase 15); each level further in (Phase 16).
         */
        private fun listItem(block: PdfBlock.ListItem) {
            val indent = LIST_INDENT * (block.level + 1)
            val across = width
            val layout = layout(rich(block.content, BODY), BODY, Layout.Alignment.ALIGN_NORMAL, across - indent)
            pages.space(3f)
            placeLines(layout) { canvas, p ->
                canvas.clipRect(0f, p.from, across, p.to)
                if (p.from <= layout.getLineBaseline(0)) canvas.drawText(block.marker, indent - LIST_GAP, layout.getLineBaseline(0).toFloat(), LIST_MARKER)
                canvas.withTranslation(indent, 0f) { layout.draw(this) }
            }
        }

        /** Text, line by line; a [heading] (its level and words) is kept with what follows and recorded for the contents. */
        private fun text(text: CharSequence, paint: TextPaint, alignment: Layout.Alignment, before: Float, after: Float = 0f, heading: Pair<Int, String>? = null) {
            if (text.isEmpty()) return
            val across = width
            val layout = layout(text, paint, alignment)
            pages.space(before)
            // A heading never ends a page: keep room for it and two lines of what follows.
            if (heading != null) pages.keep(layout.height + 2 * BODY.fontSpacing)
            placeLines(layout, onLine = { line, p -> if (heading != null && line == 0) headings += Entry(heading.first, heading.second, p.page + 1) }) { canvas, p ->
                canvas.clipRect(0f, p.from, across, p.to)
                layout.draw(canvas) // draws only the lines inside the clip
            }
            pages.space(after)
        }

        /**
         * The cover, on a page of its own with no number: the title large, then the author and
         * the dates, centred a little above the middle of the page.
         */
        private fun cover(cover: Cover) {
            check(page == null && frontPages == 0) { "the cover comes first" }
            if (dry) {
                frontPages = 1
                return
            }
            val sheet = document.startPage(PdfDocument.PageInfo.Builder(paper.width.toInt(), paper.height.toInt(), 1).create())
            val parts = listOfNotNull(
                cover.title.takeIf { it.isNotEmpty() }?.let { layout(it, COVER_TITLE, Layout.Alignment.ALIGN_CENTER) to COVER_GAP_AFTER_TITLE },
                cover.authorLine?.let { layout(it, COVER_AUTHOR, Layout.Alignment.ALIGN_CENTER) to COVER_GAP },
                cover.dates.takeIf { it.isNotEmpty() }?.let { layout(it.joinToString("\n"), COVER_DATES, Layout.Alignment.ALIGN_CENTER) to 0f },
            )
            val height = parts.sumOf { (l, gap) -> (l.height + gap).toDouble() }.toFloat() - (parts.lastOrNull()?.second ?: 0f)
            var y = (paper.height * COVER_CENTRE - height / 2).coerceAtLeast(paper.margin)
            for ((layout, gap) in parts) {
                sheet.canvas.withTranslation(paper.margin, y) { layout.draw(this) }
                y += layout.height + gap
            }
            document.finishPage(sheet)
            frontPages = 1
        }

        /** The title, author and dates at the top of the first page (Phase 30). */
        private fun titleBlock(cover: Cover) {
            cover.title.takeIf { it.isNotEmpty() }?.let { text(it, TITLE_BLOCK, Layout.Alignment.ALIGN_CENTER, before = 0f, after = 8f) }
            cover.authorLine?.let { text(it, COVER_DATES_BLACK, Layout.Alignment.ALIGN_CENTER, before = 0f, after = 2f) }
            cover.dates.forEach { text(it, COVER_DATES, Layout.Alignment.ALIGN_CENTER, before = 0f) }
            pages.space(14f)
        }

        /**
         * The table of contents, on pages of their own after the cover, unnumbered: "Contents",
         * then each heading indented by its level, with dot leaders to its page number.
         */
        private fun contentsPages() {
            val flow = PdfPaginator(paper.contentHeight)
            var sheet: PdfDocument.Page? = null
            var sheetIndex = -1
            fun canvasFor(index: Int): Canvas {
                if (index != sheetIndex) {
                    sheet?.let(document::finishPage)
                    sheetIndex = index
                    sheet = document.startPage(PdfDocument.PageInfo.Builder(paper.width.toInt(), paper.height.toInt(), frontPages + index + 1).create())
                }
                return sheet!!.canvas
            }
            val title = layout(CONTENTS_TITLE, HEADINGS[0], Layout.Alignment.ALIGN_NORMAL)
            for (p in flow.place(Span(0f, title.height.toFloat()))) {
                canvasFor(p.page).withTranslation(paper.margin, paper.margin + p.y) { title.draw(this) }
            }
            flow.space(14f)
            for (entry in contents) {
                val indent = (entry.level - 1) * CONTENTS_INDENT
                val number = entry.page.toString()
                val paint = if (entry.level == 1) CONTENTS_TOP else BODY
                val text = layout(entry.text, paint, Layout.Alignment.ALIGN_NORMAL, paper.contentWidth - indent - CONTENTS_NUMBER_WIDTH)
                flow.space(if (entry.level == 1) 6f else 2f)
                for (p in flow.place(Span(0f, text.height.toFloat()))) {
                    canvasFor(p.page).withTranslation(paper.margin, paper.margin + p.y) {
                        withTranslation(indent, 0f) { text.draw(this) }
                        // Dots from the end of the last line to the number, which sits on the right.
                        val last = text.lineCount - 1
                        val baseline = text.getLineBaseline(last).toFloat()
                        val from = indent + text.getLineRight(last) + 6f
                        val to = paper.contentWidth - paint.measureText(number) - 6f
                        var x = from
                        while (x < to) {
                            drawText(".", x, baseline, CONTENTS_DOTS)
                            x += CONTENTS_DOT_STEP
                        }
                        drawText(number, paper.contentWidth, baseline, CONTENTS_NUMBER)
                    }
                }
            }
            sheet?.let(document::finishPage)
            frontPages += flow.pageCount
        }

        /** An equation; a numbered one has "(n)" on the right margin, as in LaTeX (Phase 16). */
        private fun equation(math: MathCapture.Captured, align: MathAlign, number: Int?) {
            val label = number?.let { "($it)" }
            val room = label?.let { BODY.measureText(it) + NUMBER_GAP } ?: 0f
            val across = this.width
            val width = minOf(math.bitmap.width / scale, across - room)
            val height = math.bitmap.height / scale * (width / (math.bitmap.width / scale))
            pages.space(8f)
            for (p in pages.place(Span(0f, height))) {
                draw(p) { canvas ->
                    canvas.clipRect(0f, p.from, across, p.to)
                    val left = when (align) {
                        MathAlign.RIGHT -> across - room - width
                        else -> align.left(width, across)
                    }
                    canvas.drawBitmap(math.bitmap, null, RectF(left, 0f, left + width, height), IMAGE)
                    label?.let { canvas.drawText(it, across, height / 2 - (BODY.ascent() + BODY.descent()) / 2, EQUATION_NUMBER) }
                }
            }
            pages.space(8f)
        }

        private fun figure(block: PdfBlock.Figure) {
            val caption = block.caption?.takeIf { it.isNotBlank() }?.let { layout(it, CAPTION, block.align.layout) }
            val across = width
            // A column can be narrower than the figure.
            val figureWidth = minOf(block.width, across)
            val figureHeight = block.height * figureWidth / block.width
            pages.space(10f)
            pages.keep(figureHeight + (caption?.height?.plus(CAPTION_GAP) ?: 0f))
            val page = booklet.pages.first { it.id == block.pageId }
            // A dry run needs only the figure's size, not its picture.
            // A graph goes straight onto the page (Phase 25), as vector lines and text.
            val region = page.region(block.regionId)!!
            val graph = region.graph?.let { GraphPlot.of(it) }
            // So does a diagram (Phase 31), with its maths labels as images, as equations are.
            val diagram = region.diagram?.let { DiagramGeometry.plot(it) }
            val bitmap = if (dry || graph != null || diagram != null) createBitmap(1, 1) else figures.bitmap(page, region)
            try {
                for (p in pages.place(Span(0f, figureHeight))) {
                    draw(p) { canvas ->
                        canvas.clipRect(0f, p.from, across, p.to)
                        val left = block.align.left(figureWidth, across)
                        if (diagram != null) {
                            val b = region.bounds
                            canvas.translate(left, 0f)
                            canvas.scale(figureWidth / (b.right - b.left).coerceAtLeast(1f), figureHeight / (b.bottom - b.top).coerceAtLeast(1f))
                            canvas.translate(-b.left, -b.top)
                            DiagramRenderer.draw(canvas, diagram, b, android.graphics.Color.BLACK, maths = labelImages::get)
                        } else if (graph != null) {
                            val b = region.bounds
                            canvas.translate(left, 0f)
                            canvas.scale(figureWidth / (b.right - b.left).coerceAtLeast(1f), figureHeight / (b.bottom - b.top).coerceAtLeast(1f))
                            canvas.translate(-b.left, -b.top)
                            GraphRenderer.draw(canvas, graph, b, GraphStyle.of(InkColors.LIGHT).copy(paper = android.graphics.Color.WHITE))
                        } else {
                            canvas.drawBitmap(bitmap, null, RectF(left, 0f, left + figureWidth, figureHeight), IMAGE)
                        }
                    }
                }
            } finally {
                if (waiting != null) waitingBitmaps += bitmap else bitmap.recycle()
            }
            if (caption != null) {
                pages.space(CAPTION_GAP)
                placeLines(caption) { canvas, p ->
                    canvas.clipRect(0f, p.from, across, p.to)
                    caption.draw(canvas)
                }
            }
            pages.space(10f)
        }

        /** Runs [body] with the canvas of [p]'s page, translated so that [Placement.from] lands at its place. */
        private fun draw(p: Placement, body: (Canvas) -> Unit) {
            if (dry) return
            val x = margin + left
            val y = margin + p.y - p.from
            val placed = { canvas: Canvas -> canvas.withTranslation(x, y) { body(this) } }
            val later = waiting
            if (later != null) later += p.page to placed else placed(pageCanvas(p.page))
        }

        private fun pageCanvas(number: Int): Canvas {
            if (number != pageNumber) {
                page?.let(::finish)
                pageNumber = number
                page = document.startPage(PdfDocument.PageInfo.Builder(paper.width.toInt(), paper.height.toInt(), frontPages + number + 1).create()).also {
                    // The page number, centred in the bottom margin; the cover is not counted.
                    pageMargins[number] = margin
                    it.canvas.drawText("${number + 1}", paper.width / 2, paper.height - margin / 2, FOOTER)
                    runningHeader(it.canvas, number)
                }
            }
            return page!!.canvas
        }

        /**
         * The booklet's title on the left and the section reached on the right, over a rule (Phase 30). As in LaTeX, the page a title
         * block is on has none, and the section is the last that started by the end of the page.
         */
        private fun runningHeader(canvas: Canvas, number: Int) {
            val style = booklet.info.style
            if (!style.runningHeader || (style.titleOnFirstPage && number == 0)) return
            val m = pageMargins[number] ?: margin
            val across = paper.width - 2 * m
            val y = m * 0.6f
            val half = across / 2 - 6f
            val title = TextUtils.ellipsize(booklet.title.trim(), HEADER, half, TextUtils.TruncateAt.END).toString()
            canvas.drawText(title, m, y, HEADER)
            contents.lastOrNull { it.level == 1 && it.page <= number + 1 }?.let { section ->
                val text = TextUtils.ellipsize(section.text, HEADER, half, TextUtils.TruncateAt.END).toString()
                canvas.drawText(text, m + across - HEADER.measureText(text), y, HEADER)
            }
            canvas.drawLine(m, y + 4f, m + across, y + 4f, RULE)
        }

        /** Draws the page's footnotes at the bottom of its text area, under a short rule, and finishes it. */
        private fun finish(sheet: PdfDocument.Page) {
            val pageNotes = notes.remove(pageNumber).orEmpty()
            if (pageNotes.isNotEmpty()) {
                val height = pageNotes.sumOf { it.height.toDouble() }.toFloat()
                val m = pageMargins[pageNumber] ?: margin
                var y = paper.height - m - height
                sheet.canvas.drawLine(m, y - PdfPaginator.NOTES_GAP / 2, m + NOTE_RULE, y - PdfPaginator.NOTES_GAP / 2, RULE)
                for (note in pageNotes) {
                    sheet.canvas.withTranslation(m, y) { note.draw(this) }
                    y += note.height
                }
            }
            document.finishPage(sheet)
        }

        private fun startPageIfNone() {
            if (page == null) pageCanvas(0)
        }

        // Layout's BREAK_STRATEGY_* and HYPHENATION_FREQUENCY_* are the API 26 names for the
        // LineBreaker constants lint expects (API 29); the values are the same.
        @SuppressLint("WrongConstant")
        private fun layout(text: CharSequence, paint: TextPaint, alignment: Layout.Alignment, width: Float = this.width): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt())
                .setAlignment(alignment)
                .setLineSpacing(0f, LINE_SPACING)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .build()
    }

    private companion object {
        /** Where something [width] wide starts across a text area [content] wide. */
        fun MathAlign.left(width: Float, content: Float): Float = when (this) {
            MathAlign.LEFT -> 0f
            MathAlign.CENTER -> (content - width) / 2
            MathAlign.RIGHT -> content - width
        }

        /** The text alignment for lines set with [StaticLayout]. */
        val MathAlign.layout: Layout.Alignment
            get() = when (this) {
                MathAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
                MathAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                MathAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
            }

        const val LINE_SPACING = 1.2f
        const val COLUMN_GAP = 14f
        const val CAPTION_GAP = 4f

        /** The object replacement character, which an inline maths span draws over. */
        const val OBJECT = "￼"

        /** A list item's text starts this far in per level; its bullet or number ends [LIST_GAP] before it. */
        const val LIST_INDENT = 22f
        const val LIST_GAP = 6f
        const val NUMBER_GAP = 12f
        const val NOTE_REF_SIZE = 0.7f
        const val NOTE_RULE = 120f

        /** Links are blue, as `hyperref`'s `urlcolor=blue` sets them in the LaTeX. */
        const val LINK_COLOR = 0xFF0000FF.toInt()

        private fun textPaint(size: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            color = Color.BLACK
            typeface = Typeface.create(Typeface.SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

        val COVER_TITLE = textPaint(28f, bold = true)
        val COVER_AUTHOR = textPaint(15f)
        val COVER_DATES = textPaint(11f).apply { color = Color.DKGRAY }
        val COVER_DATES_BLACK = textPaint(11f)
        val TITLE_BLOCK = textPaint(18f, bold = true)
        val HEADER = textPaint(9f).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC) }
        const val COVER_GAP_AFTER_TITLE = 28f
        const val COVER_GAP = 20f

        /** Where the middle of the cover's text sits, as a fraction of the page height. */
        const val COVER_CENTRE = 0.4f
        val HEADINGS = listOf(textPaint(15f, bold = true), textPaint(13f, bold = true), textPaint(11f, bold = true))
        val BODY = textPaint(11f)
        val CAPTION = textPaint(9f)
        val NOTE = textPaint(9f)
        const val CONTENTS_TITLE = "Contents"
        const val CONTENTS_INDENT = 16f
        const val CONTENTS_NUMBER_WIDTH = 40f
        const val CONTENTS_DOT_STEP = 4f
        val CONTENTS_TOP = textPaint(11f, bold = true)
        val CONTENTS_NUMBER = textPaint(11f).apply { textAlign = Paint.Align.RIGHT }
        val CONTENTS_DOTS = textPaint(11f).apply { color = Color.GRAY }
        val LIST_MARKER = textPaint(11f).apply { textAlign = Paint.Align.RIGHT }
        val EQUATION_NUMBER = textPaint(11f).apply { textAlign = Paint.Align.RIGHT }
        val FOOTER = textPaint(9f).apply { textAlign = Paint.Align.CENTER; color = Color.DKGRAY }
        val RULE = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 0.4f }
        val IMAGE = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    }
}
