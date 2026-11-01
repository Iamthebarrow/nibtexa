package io.github.iamthebarrow.nibtexa.core.export

import io.github.iamthebarrow.nibtexa.core.model.InkDocument
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.ColumnMode
import io.github.iamthebarrow.nibtexa.core.model.notebook.MathAlign
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageMargin
import io.github.iamthebarrow.nibtexa.core.model.notebook.Paper
import io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.core.model.notebook.Sheet
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextMark
import io.github.iamthebarrow.nibtexa.core.model.notebook.TheoremKind
import io.github.iamthebarrow.nibtexa.core.model.notebook.writingArea
import kotlin.math.ceil

/**
 * A picture an import found (Phase 20), stored by the app: its name in the picture store and its
 * size in pixels, which gives its shape on the page.
 */
class ImportedPicture(val file: String, val width: Int, val height: Int) {
    /** Its width and height on a page: its own pixels as page units, at most [maxWidth] wide. */
    fun sizeOnPage(maxWidth: Float): Pair<Float, Float> {
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        val shown = minOf(w, maxWidth)
        return shown to shown * h / w
    }
}

/** One block an importer read, before it is placed on a page. */
internal sealed interface ImportBlock {
    data class Text(val text: String, val marks: List<TextMark>, val align: MathAlign?, val theorem: TheoremKind? = null) : ImportBlock
    data class Heading(val level: Int, val text: String, val marks: List<TextMark>, val align: MathAlign?) : ImportBlock
    data class Item(val text: String, val marks: List<TextMark>, val level: Int) : ImportBlock
    data class Math(val latex: String, val numbered: Boolean, val align: MathAlign?) : ImportBlock
    data class Picture(val picture: ImportedPicture, val caption: String?, val align: MathAlign?) : ImportBlock

    /** A LaTeX `multicols` (Phase 30): each column's blocks follow, with [ColumnBreak] between them and [ColumnsEnd] after. */
    data object ColumnsStart : ImportBlock
    data object ColumnBreak : ImportBlock
    data object ColumnsEnd : ImportBlock
}

/**
 * Lays imported blocks out down a page as typed regions, in reading order (Phases 18 and 20), inside the margin of the sheet a new
 * booklet has, going on to a new page where one is full (Phase 30).
 */
internal object ImportLayout {
    private val AREA = Sheet().writingArea(Paper.BLANK, PageMargin.NORMAL)
    private val LEFT = AREA.left
    private val TOP = AREA.top
    private val BOTTOM = AREA.bottom
    private val WIDTH = AREA.right - AREA.left

    // Page units.
    private const val GAP = 32f
    private const val UNITS_PER_CHAR = 9.7f
    private const val LINE = 26f
    private const val COLUMN_GAP = 24f

    /** Pictures come in at most this wide, about half an A4 sheet. */
    private const val PICTURE_WIDTH = 480f

    fun pages(newId: () -> String, blocks: List<ImportBlock>): List<Page> {
        val pages = mutableListOf<Page>()
        var regions = mutableListOf<Region>()
        var y = TOP
        // While in columns: which one (0 or 1, -1 if not), where they start on this page, and how far down the first got.
        var column = -1
        var columnTop = TOP
        var columnBottom = TOP
        var inColumns = false
        var used = 0

        fun newPage() {
            pages += Page(newId(), InkDocument.EMPTY, regions, nextRegionNumber = used + 1, columns = if (inColumns) ColumnMode.TWO else ColumnMode.AUTO)
            regions = mutableListOf()
            used = 0
            inColumns = column >= 0
            y = TOP
            columnTop = TOP
            columnBottom = TOP
            if (column == 1) column = 0
        }

        for (block in blocks) {
            when (block) {
                ImportBlock.ColumnsStart -> {
                    column = 0
                    inColumns = true
                    columnTop = y
                    columnBottom = y
                    continue
                }
                ImportBlock.ColumnBreak -> {
                    column = 1
                    columnBottom = maxOf(columnBottom, y)
                    y = columnTop
                    continue
                }
                ImportBlock.ColumnsEnd -> {
                    column = -1
                    y = maxOf(columnBottom, y)
                    continue
                }
                else -> Unit
            }
            val full = if (column >= 0) (WIDTH - COLUMN_GAP) / 2 else WIDTH
            val chars = (full / UNITS_PER_CHAR).toInt().coerceAtLeast(1)
            val (height, width) = when (block) {
                is ImportBlock.Text -> lines(block.text, chars) * LINE + 8f to full
                is ImportBlock.Item -> lines(block.text, chars) * LINE + 8f to full - block.level * 32f
                is ImportBlock.Heading -> (44f - block.level * 6f) to full
                is ImportBlock.Math -> (block.latex.split("\\\\").size * 40f + 16f) to minOf(full, 60f + block.latex.length * 9f)
                is ImportBlock.Picture -> block.picture.sizeOnPage(minOf(PICTURE_WIDTH, full)).let { (w, h) ->
                    val room = BOTTOM - TOP
                    if (h > room) h * room / h to w * room / h else h to w
                }
                else -> error("not a region")
            }
            // Full: the second column, or a new page. A page that has nothing on it takes whatever comes.
            if (y + height > BOTTOM && y > columnTop) {
                if (column == 0) {
                    column = 1
                    columnBottom = maxOf(columnBottom, y)
                    y = columnTop
                } else if (regions.isNotEmpty()) {
                    newPage()
                }
            }
            val w = if (column >= 0) (WIDTH - COLUMN_GAP) / 2 else WIDTH
            val left = LEFT + (if (column == 1) w + COLUMN_GAP else 0f) + if (block is ImportBlock.Item) block.level * 32f else 0f
            val bounds = Bounds(left, y, left + width, y + height)
            y += height + GAP
            val n = ++used
            val base = Region("r$n", RegionRole.TEXT, emptySet(), bounds, n, status = RecognitionStatus.CONFIRMED, typed = true, newParagraph = true)
            regions += when (block) {
                is ImportBlock.Text -> base.copy(text = block.text, marks = block.marks, align = block.align, theorem = block.theorem)
                is ImportBlock.Heading -> base.copy(role = RegionRole.HEADING, text = block.text, marks = block.marks, headingLevel = block.level, align = block.align)
                is ImportBlock.Item -> base.copy(text = block.text, marks = block.marks, listLevel = block.level)
                is ImportBlock.Math -> base.copy(role = RegionRole.MATH, confirmedLatex = block.latex, numbered = block.numbered, inline = false, align = block.align, newParagraph = null)
                is ImportBlock.Picture -> base.copy(
                    role = RegionRole.FIGURE,
                    status = RecognitionStatus.NOT_REQUESTED,
                    picture = block.picture.file,
                    caption = block.caption?.takeIf { it.isNotBlank() },
                    align = block.align,
                    newParagraph = null,
                )
                else -> error("not a region")
            }
        }
        newPage()
        return pages
    }

    private fun lines(text: String, chars: Int) = maxOf(1, ceil(text.length / chars.toDouble()).toInt())
}
