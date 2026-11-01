package io.github.iamthebarrow.nibtexa.core.export

import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.MathAlign
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.core.model.notebook.marginOf

/** One piece of a booklet's PDF, in reading order (Phase 12). Sizes are in PDF points. */
sealed interface PdfBlock {
    /** The cover page: on a page of its own, which has no number (Phase 14). */
    data class CoverPage(val cover: Cover) : PdfBlock

    /** The same lines at the top of the first page, as a paper has them (Phase 30). */
    data class TitleBlock(val cover: Cover) : PdfBlock
    data class Heading(val level: Int, val content: List<Inline>, val align: MathAlign = MathAlign.LEFT, val number: String? = null) : PdfBlock {
        constructor(level: Int, text: String, align: MathAlign = MathAlign.LEFT) : this(level, listOf(Inline.Text(text)), align)
    }
    data class Paragraph(val content: List<Inline>, val align: MathAlign = MathAlign.LEFT, val theorem: Theorem? = null) : PdfBlock {
        constructor(text: String, align: MathAlign = MathAlign.LEFT) : this(listOf(Inline.Text(text)), align)
    }

    /** One item of a list (Phase 15) at [level] 0–2 (Phase 16): [marker] is "•", "2.", "(a)"… */
    data class ListItem(val marker: String, val content: List<Inline>, val level: Int = 0) : PdfBlock {
        constructor(marker: String, text: String) : this(marker, listOf(Inline.Text(text)))
    }

    /** An equation; [number] when it is numbered (Phase 16). */
    data class Math(val latex: String, val align: MathAlign = MathAlign.CENTER, val number: Int? = null) : PdfBlock

    /** The figure's ink, drawn by the app at this size, with its caption below. */
    data class Figure(val pageId: String, val regionId: String, val width: Float, val height: Float, val caption: String?, val align: MathAlign = MathAlign.CENTER) : PdfBlock

    /** What follows starts a new PDF page (Phase 16). */
    data object PageBreak : PdfBlock

    /** The pages from here on have this margin, in points (Phase 30: a page with its own), on a new PDF page. */
    data class Margin(val points: Float) : PdfBlock

    /** Two columns (Phase 30): each column's blocks follow, with [ColumnBreak] between them and [ColumnsEnd] after. */
    data object ColumnsStart : PdfBlock
    data object ColumnBreak : PdfBlock
    data object ColumnsEnd : PdfBlock
}

/**
 * What a booklet's PDF contains, and where each block goes on the pages. The PDF has the same
 * content as the LaTeX export, typeset by the app rather than by LaTeX: the cover page, then
 * every page's regions in reading order (headings, paragraphs, display math, figures), flowing
 * from one PDF page to the next. Export rules are the LaTeX ones, so a booklet that cannot export
 * as LaTeX ([LatexExporter.validate]) does not export as PDF either.
 */
object PdfLayout {
    /**
     * A PDF page of a booklet's paper size (Phase 19), in points, with the same margins on every
     * size.
     */
    class Page(val width: Float, val height: Float, val margin: Float = MARGIN) {
        val contentWidth: Float get() = width - 2 * margin
        val contentHeight: Float get() = height - 2 * margin
    }

    /** The booklet's paper and margin (Phase 30). */
    fun page(booklet: Booklet): Page = booklet.info.sheet.let { Page(it.widthPoints, it.heightPoints, booklet.info.style.margin.inches * 72f) }

    /** A4 portrait, in points: the widest text the document page lays maths out for. */
    const val PAGE_WIDTH = 595f
    const val PAGE_HEIGHT = 842f
    const val MARGIN = 56f
    const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN
    const val CONTENT_HEIGHT = PAGE_HEIGHT - 2 * MARGIN

    /** As in the LaTeX export: Android's dp is 1/160 inch, and figures are at most 4.5 in wide. */
    private const val POINTS_PER_DP = 72f / 160f
    private const val MAX_FIGURE_WIDTH = 4.5f * 72f
    private const val FIGURE_SHARE = 0.8f

    /** The blocks to typeset, or the problems that stop the export. */
    fun blocks(booklet: Booklet): Result {
        val problems = LatexExporter.validate(booklet)
        if (problems.isNotEmpty()) return Result.Invalid(problems)
        val sheet = page(booklet)
        val blocks = buildList<PdfBlock> {
            add(if (booklet.info.style.titleOnFirstPage) PdfBlock.TitleBlock(Cover.of(booklet)) else PdfBlock.CoverPage(Cover.of(booklet)))
            var margin = sheet.margin
            for ((source, flow) in booklet.pages.zip(Flow.of(booklet))) for (block in listOf<FlowBlock?>(null) + flow) {
                if (block == null) {
                    val own = booklet.info.style.marginOf(source).inches * 72f
                    if (own != margin) add(PdfBlock.Margin(own))
                    margin = own
                    continue
                }
                when (block) {
                    is FlowBlock.Heading -> add(PdfBlock.Heading(block.level, block.content, block.align, block.number))
                    is FlowBlock.Paragraph -> add(PdfBlock.Paragraph(block.content, block.align, block.theorem))
                    is FlowBlock.ListItem -> add(PdfBlock.ListItem(block.marker, block.content, block.level))
                    is FlowBlock.Math -> add(PdfBlock.Math(block.latex, block.align, block.number))
                    is FlowBlock.Figure -> {
                        val region = block.region
                        val b = region.bounds
                        val w = (b.right - b.left).coerceAtLeast(1f)
                        val h = (b.bottom - b.top).coerceAtLeast(1f)
                        var width = minOf(w * POINTS_PER_DP, MAX_FIGURE_WIDTH, sheet.contentWidth)
                        var height = width * h / w
                        val tallest = sheet.contentHeight * FIGURE_SHARE
                        if (height > tallest) {
                            width *= tallest / height
                            height = tallest
                        }
                        add(PdfBlock.Figure(block.pageId, region.id, width, height, region.caption, block.align))
                    }
                    is FlowBlock.PageBreak -> add(PdfBlock.PageBreak)
                    is FlowBlock.ColumnsStart -> add(PdfBlock.ColumnsStart)
                    is FlowBlock.ColumnBreak -> add(PdfBlock.ColumnBreak)
                    is FlowBlock.ColumnsEnd -> add(PdfBlock.ColumnsEnd)
                    is FlowBlock.NotReady -> Unit
                }
            }
        }
        return Result.Blocks(blocks)
    }

    sealed interface Result {
        data class Blocks(val blocks: List<PdfBlock>) : Result
        data class Invalid(val problems: List<ExportProblem>) : Result
    }
}

/** A laid-out block's vertical extent, in points from the top of its rendering. */
data class Span(val top: Float, val bottom: Float) {
    val height: Float get() = bottom - top
}

/** Where (part of) a block goes: on [page], at [y] from the top of the content area, showing [from] to [to] of its span. */
data class Placement(val page: Int, val y: Float, val from: Float, val to: Float)

/**
 * Fills pages top to bottom. A block that does not fit in what is left of a page starts the
 * next page, so blocks are never split, except one taller than a whole page, which is sliced.
 *
 * Footnotes (Phase 16) take room at the bottom of the page their reference is on: a line placed
 * with `notes` keeps that much (and, for the page's first note, [NOTES_GAP] for the rule) free
 * below everything else on its page.
 */
class PdfPaginator(private var pageHeight: Float = PdfLayout.CONTENT_HEIGHT) {
    var page = 0
        private set
    private var y = 0f

    /** Pages from the next one on are [height] tall (Phase 30: a page's own margin). Call on a new page. */
    fun setPageHeight(height: Float) {
        pageHeight = height
    }

    /** A paginator starting where this one is, for a column (Phase 30). */
    fun fork(): PdfPaginator = PdfPaginator(pageHeight).also {
        it.page = page
        it.y = y
        it.notesHeight = notesHeight
    }

    /** Carries on below the longest of [columns]. */
    fun continueAfter(columns: List<PdfPaginator>) {
        val last = columns.maxWith(compareBy({ it.page }, { it.y }))
        page = last.page
        y = last.y
        notesHeight = columns.filter { it.page == last.page }.maxOf { it.notesHeight }
    }

    /** Room kept for this page's footnotes. */
    var notesHeight = 0f
        private set

    /** Starts a new page unless this one is still empty (a page break, Phase 16). */
    fun breakPage() {
        if (y > 0f) newPage()
    }

    /** Number of pages used so far. */
    val pageCount: Int get() = page + 1

    /** Vertical space between blocks; dropped at the top of a page, and it never starts one. */
    fun space(points: Float) {
        if (y > 0f) y = minOf(y + points, pageHeight)
    }

    /** Starts a new page unless [points] still fit on this one (keeps a heading with what follows). */
    fun keep(points: Float) {
        if (y > 0f && y + points > pageHeight) newPage()
    }

    fun place(span: Span, notes: Float = 0f): List<Placement> {
        val height = span.height.coerceAtLeast(0f)
        fun room(note: Float) = if (note > 0f) note + (if (notesHeight == 0f) NOTES_GAP else 0f) else 0f
        if (height + room(notes) <= pageHeight) {
            if (y > 0f && y + height + notesHeight + room(notes) > pageHeight) newPage()
            notesHeight += room(notes)
            return listOf(Placement(page, y, span.top, span.bottom)).also { y += height }
        }
        if (y > 0f) newPage()
        val slices = mutableListOf<Placement>()
        var from = span.top
        while (from < span.bottom) {
            val to = minOf(from + pageHeight, span.bottom)
            slices += Placement(page, 0f, from, to)
            if (to < span.bottom) newPage() else y = to - from
            from = to
        }
        return slices
    }

    private fun newPage() {
        page++
        y = 0f
        notesHeight = 0f
    }

    companion object {
        /** Space above a page's footnotes, for the short rule. */
        const val NOTES_GAP = 14f
    }
}
