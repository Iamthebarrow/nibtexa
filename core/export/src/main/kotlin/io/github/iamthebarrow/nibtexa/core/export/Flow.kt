package io.github.iamthebarrow.nibtexa.core.export

import io.github.iamthebarrow.nibtexa.core.model.notebook.AutoRegions
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.ColumnMode
import io.github.iamthebarrow.nibtexa.core.model.notebook.MarkStyle
import io.github.iamthebarrow.nibtexa.core.model.notebook.MathAlign
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageColumns
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextColor
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextMark
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextMarks
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextSize
import io.github.iamthebarrow.nibtexa.core.model.notebook.TheoremKind

/** How a run of text looks (Phase 16): the formatting of [TextMark]s, combined. */
data class Style(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val emphasis: Boolean = false,
    val underline: Boolean = false,
    val typewriter: Boolean = false,
    val size: TextSize? = null,
    val color: TextColor? = null,
    val link: String? = null,
)

/** A piece of a paragraph, heading or list item. */
sealed interface Inline {
    data class Text(val text: String, val style: Style = Style()) : Inline

    /** Inline maths: `$…$` in text, or a math region written in the line. */
    data class Math(val latex: String, val style: Style = Style()) : Inline

    /** A footnote, numbered through the booklet, placed where it is referenced. */
    data class Note(val number: Int, val text: String) : Inline
}

/** A theorem-style block's kind and number (null for a proof). */
data class Theorem(val kind: TheoremKind, val number: Int?)

/** One block of a booklet page as the exports set it, in reading order (Phase 16). */
sealed interface FlowBlock {
    val regionIds: List<String>

    /** [number] is "1.2" when the booklet numbers its sections (Phase 30). */
    data class Heading(val level: Int, val content: List<Inline>, val align: MathAlign, override val regionIds: List<String>, val number: String? = null) : FlowBlock
    data class Paragraph(val content: List<Inline>, val align: MathAlign, val theorem: Theorem?, override val regionIds: List<String>) : FlowBlock

    /** A list item at [level] 0–2, with its [marker] ("•", "1.", "(a)"…) as LaTeX's defaults draw it. */
    data class ListItem(val kind: Lists.Kind, val level: Int, val marker: String, val content: List<Inline>, override val regionIds: List<String>) : FlowBlock
    data class Math(val latex: String, val align: MathAlign, val number: Int?, val regionId: String) : FlowBlock {
        override val regionIds get() = listOf(regionId)
    }
    data class Figure(val region: Region, val pageId: String, val align: MathAlign) : FlowBlock {
        override val regionIds get() = listOf(region.id)
    }
    data class PageBreak(val regionId: String) : FlowBlock {
        override val regionIds get() = listOf(regionId)
    }

    /** Two columns start (Phase 30). Each column's blocks follow, with [ColumnBreak] between them and [ColumnsEnd] after. */
    data object ColumnsStart : FlowBlock {
        override val regionIds get() = emptyList<String>()
    }
    data object ColumnBreak : FlowBlock {
        override val regionIds get() = emptyList<String>()
    }
    data object ColumnsEnd : FlowBlock {
        override val regionIds get() = emptyList<String>()
    }

    /** A region not ready to export (preview and reading view only). */
    data class NotReady(val regionId: String, val stale: Boolean) : FlowBlock {
        override val regionIds get() = listOf(regionId)
    }
}

/**
 * The document the exports set (Phase 16), built once from the booklet so that the LaTeX, the
 * PDF and the reading view agree:
 * - **paragraphs**: consecutive text lines written at most [PARAGRAPH_GAP] h apart (h is the
 *   page's writing scale) read as one paragraph, or continue a list item or heading; a region's
 *   [Region.newParagraph] overrides;
 * - **inline maths**: `$…$` in text ([InlineMath]), and math regions on the line of the text
 *   next to them, unless [Region.inline] says otherwise, join the sentence;
 * - **lists**: an item further right than the one before by more than [INDENT] h is a sub-item,
 *   up to three levels, unless [Region.listLevel] sets it; a level is never skipped;
 * - **numbers**: equations, footnotes and each theorem kind count through the booklet, as LaTeX
 *   counts them in an `article`; numbered list items count from 1 in each run;
 * - **columns** (Phase 30): a page's columns ([PageColumns]) are read one after the other, and nothing carries over between them.
 */
object Flow {
    const val PARAGRAPH_GAP = 1.0f
    const val INDENT = 1.0f
    const val MAX_LEVEL = 2

    /** Numbers that run through the booklet. */
    class Counters {
        var equation = 0
        var footnote = 0
        val theorems = mutableMapOf<TheoremKind, Int>()

        /** Each heading level's count, for numbered sections (Phase 30). */
        val sections = IntArray(MAX_LEVEL + 1)
    }

    /** Every page's blocks, numbered through the booklet. With [preview], unready regions show as [FlowBlock.NotReady]. */
    fun of(booklet: Booklet, preview: Boolean = false): List<List<FlowBlock>> {
        val counters = Counters()
        return booklet.pages.map { page(it, booklet.info.mathAlign, counters, preview, booklet.info.style.numberedSections) }
    }

    fun page(page: Page, bookletMath: MathAlign, counters: Counters = Counters(), preview: Boolean = true, numberSections: Boolean = false): List<FlowBlock> =
        Builder(page, bookletMath, counters, preview, numberSections).build()

    /** The words of [content], without formatting: for the table of contents and plain uses. */
    fun plain(content: List<Inline>): String = content.joinToString("") {
        when (it) {
            is Inline.Text -> it.text
            is Inline.Math -> "$${it.latex}$"
            is Inline.Note -> ""
        }
    }

    /** All inline maths in the blocks, in order. */
    fun inlineMath(blocks: List<FlowBlock>): List<String> = blocks.flatMap { b ->
        when (b) {
            is FlowBlock.Heading -> b.content
            is FlowBlock.Paragraph -> b.content
            is FlowBlock.ListItem -> b.content
            else -> emptyList()
        }.filterIsInstance<Inline.Math>().map { it.latex }
    }

    private class Builder(val page: Page, val bookletMath: MathAlign, val counters: Counters, val preview: Boolean, val numberSections: Boolean) {
        private val h = AutoRegions.scale(page)
        private val out = mutableListOf<FlowBlock>()
        private val regions = page.regionsInReadingOrder.filter { it.role != RegionRole.EXCLUDED }

        /** The paragraph, heading or list item still taking text. */
        private var open: Open? = null

        private inner class Open(val kind: Kind, val region: Region, val ids: MutableList<String>, val content: MutableList<Inline>, var last: Bounds, var lineBottom: Float) {
            var level = 0
            var itemKind = Lists.Kind.BULLETED
            var marker = ""
            var theorem: Theorem? = null
        }

        private enum class Kind { PARAGRAPH, HEADING, ITEM }

        // The current list: each level's left edge, kind and count.
        private val lefts = mutableListOf<Float>()
        private val kinds = mutableListOf<Lists.Kind>()
        private val counts = mutableListOf<Int>()

        private var inColumns = false

        fun build(): List<FlowBlock> {
            // An order the user set by hand stands, unless they ask for columns as well.
            val sections = if (page.manualOrder && page.columns != ColumnMode.TWO) null else PageColumns.sections(regions.map { it.id to it.bounds }, h, page.columns)
            if (sections == null) {
                walk(regions)
            } else {
                val byId = regions.associateBy { it.id }
                for (section in sections) {
                    val columns = section.columns.map { ids -> ids.map(byId::getValue) }
                    if (columns.size == 1) walk(columns.single()) else twoColumns(columns[0], columns[1])
                }
            }
            close()
            return out
        }

        private fun twoColumns(left: List<Region>, right: List<Region>) {
            endBlock()
            val start = out.size
            inColumns = true
            out += FlowBlock.ColumnsStart
            walk(left)
            endBlock()
            val middle = out.size
            out += FlowBlock.ColumnBreak
            walk(right)
            endBlock()
            inColumns = false
            // A column with nothing ready to export is no column.
            if (middle == start + 1 || out.size == middle + 1) {
                out.removeAt(middle)
                out.removeAt(start)
            } else {
                out += FlowBlock.ColumnsEnd
            }
        }

        private fun endBlock() {
            close()
            endList()
        }

        private fun walk(regions: List<Region>) {
            for ((i, region) in regions.withIndex()) {
                if (!page.isExportable(region)) {
                    close()
                    endList()
                    if (preview) out += FlowBlock.NotReady(region.id, page.isStale(region))
                    continue
                }
                if (region.pageBreak) {
                    close()
                    endList()
                    // A new page can't start inside columns: they end, and carry on after it.
                    if (inColumns) out += FlowBlock.ColumnsEnd
                    out += FlowBlock.PageBreak(region.id)
                    if (inColumns) out += FlowBlock.ColumnsStart
                }
                when (region.role) {
                    RegionRole.TEXT -> text(region)
                    RegionRole.HEADING -> heading(region)
                    RegionRole.MATH -> math(region, regions.getOrNull(i + 1))
                    RegionRole.FIGURE -> {
                        close()
                        endList()
                        out += FlowBlock.Figure(region, page.id, page.alignOf(region, bookletMath))
                    }
                    RegionRole.EXCLUDED -> Unit
                }
            }
        }

        /** Whether [region] continues the open block: on its line, or on the next line close below. */
        private fun continues(region: Region): Boolean {
            val o = open ?: return false
            region.newParagraph?.let { return !it }
            val b = region.bounds
            if (AutoRegions.sameLine(o.last, b)) return true
            return b.top > o.last.top && b.top - o.lineBottom <= PARAGRAPH_GAP * h
        }

        private fun append(region: Region, content: List<Inline>) {
            val o = open!!
            if (o.content.isNotEmpty() && content.isNotEmpty()) o.content += Inline.Text(" ")
            o.content += content
            o.ids += region.id
            o.lineBottom = if (AutoRegions.sameLine(o.last, region.bounds)) maxOf(o.lineBottom, region.bounds.bottom) else region.bounds.bottom
            o.last = region.bounds
        }

        private fun text(region: Region) {
            val raw = region.text.orEmpty()
            val lead = raw.length - raw.trimStart().length
            val trimmed = raw.trim()
            val marks = region.marks.map { it.copy(start = it.start - lead, end = it.end - lead) }
            val item = Lists.item(trimmed)
            val o = open
            if (item == null && region.theorem == null && o != null && o.kind != Kind.HEADING && continues(region)) {
                append(region, inlines(trimmed, marks))
                return
            }
            close()
            if (item != null) {
                val content = inlines(trimmed.substring(item.start), marks.map { it.copy(start = it.start - item.start, end = it.end - item.start) })
                val level = listLevel(region, item.kind)
                open = Open(Kind.ITEM, region, mutableListOf(region.id), content.toMutableList(), region.bounds, region.bounds.bottom).also {
                    it.level = level
                    it.itemKind = item.kind
                    // LaTeX counts itemize and enumerate depths apart: a numbered list inside bullets is 1., 2.
                    it.marker = marker(item.kind, kinds.take(level + 1).count { k -> k == item.kind } - 1, counts[level])
                }
                return
            }
            endList()
            open = Open(Kind.PARAGRAPH, region, mutableListOf(region.id), inlines(trimmed, marks).toMutableList(), region.bounds, region.bounds.bottom).also {
                it.theorem = region.theorem?.let { kind -> Theorem(kind, if (kind.numbered) counters.theorems.merge(kind, 1, Int::plus) else null) }
            }
        }

        private fun heading(region: Region) {
            val raw = region.text.orEmpty()
            val lead = raw.length - raw.trimStart().length
            val content = inlines(raw.trim(), region.marks.map { it.copy(start = it.start - lead, end = it.end - lead) })
            val o = open
            if (o != null && o.kind == Kind.HEADING && o.region.headingLevel == region.headingLevel && continues(region)) {
                append(region, content)
                return
            }
            close()
            endList()
            open = Open(Kind.HEADING, region, mutableListOf(region.id), content.toMutableList(), region.bounds, region.bounds.bottom)
        }

        private fun math(region: Region, next: Region?) {
            val latex = region.confirmedLatex.orEmpty().trim()
            val o = open
            val besideOpen = o != null && AutoRegions.sameLine(o.last, region.bounds)
            val besideNext = next != null && next.role in WORDS && page.isExportable(next) && AutoRegions.sameLine(region.bounds, next.bounds)
            val inline = region.inline ?: (besideOpen || besideNext)
            if (inline && !region.numbered) {
                if (o != null && (besideOpen || region.inline == true)) {
                    append(region, listOf(Inline.Math(latex)))
                } else {
                    close()
                    endList()
                    open = Open(Kind.PARAGRAPH, region, mutableListOf(region.id), mutableListOf(Inline.Math(latex)), region.bounds, region.bounds.bottom)
                }
                return
            }
            close()
            endList()
            out += FlowBlock.Math(latex, page.alignOf(region, bookletMath), if (region.numbered) ++counters.equation else null, region.id)
        }

        /** The item's level, and the list's state updated for it. */
        private fun listLevel(region: Region, kind: Lists.Kind): Int {
            val left = region.bounds.left
            val wanted = region.listLevel ?: when {
                lefts.isEmpty() -> 0
                else -> {
                    var level = lefts.lastIndex
                    while (level > 0 && left < lefts[level] - INDENT * h) level--
                    if (left > lefts[level] + INDENT * h) level + 1 else level
                }
            }
            val level = wanted.coerceIn(0, minOf(MAX_LEVEL, lefts.size))
            while (lefts.size > level + 1) {
                lefts.removeAt(lefts.lastIndex)
                kinds.removeAt(kinds.lastIndex)
                counts.removeAt(counts.lastIndex)
            }
            if (lefts.size == level) {
                lefts += left
                kinds += kind
                counts += 0
            } else if (kinds[level] != kind) {
                kinds[level] = kind
                counts[level] = 0
            }
            counts[level]++
            return level
        }

        private fun endList() {
            lefts.clear()
            kinds.clear()
            counts.clear()
        }

        private fun close() {
            val o = open ?: return
            open = null
            val content = merged(o.content)
            out += when (o.kind) {
                Kind.HEADING -> o.region.headingLevel.coerceIn(1, 3).let { level -> FlowBlock.Heading(level, content, page.alignOf(o.region, bookletMath), o.ids, if (numberSections) sectionNumber(level) else null) }
                Kind.ITEM -> FlowBlock.ListItem(o.itemKind, o.level, o.marker, content, o.ids)
                Kind.PARAGRAPH -> FlowBlock.Paragraph(content, page.alignOf(o.region, bookletMath), o.theorem, o.ids)
            }
        }

        /** The next number at heading [level], as LaTeX counts: a level restarts under the one above it. */
        private fun sectionNumber(level: Int): String {
            val sections = counters.sections
            sections[level - 1]++
            for (i in level until sections.size) sections[i] = 0
            return sections.take(level).joinToString(".")
        }

        /** [text] with its [marks] as inline pieces: styled runs, inline maths and footnotes. */
        private fun inlines(text: String, marks: List<TextMark>): List<Inline> {
            val flat = text.replace('\n', ' ').replace('\r', ' ')
            val maths = InlineMath.find(flat)
            fun snap(i: Int, isEnd: Boolean): Int {
                val m = maths.firstOrNull { i > it.first && i < it.last + 1 } ?: return i
                return if (isEnd) m.last + 1 else m.first
            }
            val clamped = marks.mapNotNull { m ->
                val s = snap(m.start.coerceIn(0, flat.length), isEnd = false)
                val e = snap(m.end.coerceIn(0, flat.length), isEnd = true)
                if (s < e) m.copy(start = s, end = e) else null
            }
            val cuts = sortedSetOf(0, flat.length)
            clamped.forEach { cuts += it.start; cuts += it.end }
            maths.forEach { cuts += it.first; cuts += it.last + 1 }
            val result = mutableListOf<Inline>()
            for ((s, e) in cuts.zipWithNext()) {
                if (s < e) {
                    val style = styleAt(clamped, s, e)
                    val math = maths.firstOrNull { it.first == s }
                    result += if (math != null) Inline.Math(flat.substring(s + 1, e - 1), style) else Inline.Text(InlineMath.unescape(flat.substring(s, e)), style)
                }
                clamped.filter { it.style is MarkStyle.Footnote && it.end == e }.forEach {
                    result += Inline.Note(++counters.footnote, (it.style as MarkStyle.Footnote).note)
                }
            }
            return result
        }
    }

    private fun styleAt(marks: List<TextMark>, start: Int, end: Int): Style {
        var style = Style()
        for (m in marks) {
            if (m.start > start || m.end < end) continue
            style = when (val s = m.style) {
                MarkStyle.Bold -> style.copy(bold = true)
                MarkStyle.Italic -> style.copy(italic = true)
                MarkStyle.Emphasis -> style.copy(emphasis = true)
                MarkStyle.Underline -> style.copy(underline = true)
                MarkStyle.Typewriter -> style.copy(typewriter = true)
                is MarkStyle.Size -> style.copy(size = s.size)
                is MarkStyle.Color -> style.copy(color = s.color)
                is MarkStyle.Link -> if (TextMarks.isSafeUrl(s.url)) style.copy(link = s.url) else style
                is MarkStyle.Footnote -> style
            }
        }
        return style
    }

    /** Neighbouring runs of one style as one run. */
    private fun merged(content: List<Inline>): List<Inline> {
        val result = mutableListOf<Inline>()
        for (piece in content) {
            val last = result.lastOrNull()
            if (piece is Inline.Text && last is Inline.Text && last.style == piece.style) result[result.lastIndex] = last.copy(text = last.text + piece.text) else result += piece
        }
        return result
    }

    private val WORDS = setOf(RegionRole.TEXT, RegionRole.HEADING)

    private val BULLETS = listOf("•", "–", "∗")
    private val ROMAN = listOf("i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x", "xi", "xii")

    /** The marker LaTeX draws for item [n] (from 1) at [level], the depth among lists of its kind: •, –, ∗ or 1., (a), i. */
    fun marker(kind: Lists.Kind, level: Int, n: Int): String = when (kind) {
        Lists.Kind.BULLETED -> BULLETS[level.coerceIn(0, 2)]
        Lists.Kind.NUMBERED -> when (level) {
            0 -> "$n."
            1 -> "(${letters(n)})"
            else -> "${ROMAN.getOrElse(n - 1) { n.toString() }}."
        }
    }

    private fun letters(n: Int): String {
        var k = n
        val s = StringBuilder()
        while (k > 0) {
            s.insert(0, 'a' + (k - 1) % 26)
            k = (k - 1) / 26
        }
        return s.toString()
    }
}

/**
 * `$…$` in typed text (Phase 16), by Pandoc's rule: the opening dollar has a non-space after it,
 * the closing one a non-space before it and no digit after it, so "$5 and $10" stays text. A
 * dollar after a backslash is a literal dollar.
 */
object InlineMath {
    /** The ranges of each `$…$`, dollars included. */
    fun find(text: String): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var i = 0
        while (i < text.length) {
            if (text[i] == '$' && !escaped(text, i) && i + 1 < text.length && !text[i + 1].isWhitespace() && text[i + 1] != '$') {
                var j = i + 1
                var close = -1
                while (j < text.length) {
                    if (text[j] == '$' && !escaped(text, j)) {
                        if (!text[j - 1].isWhitespace() && (j + 1 >= text.length || !text[j + 1].isDigit())) close = j
                        break
                    }
                    j++
                }
                if (close > i + 1) {
                    result += i..close
                    i = close + 1
                    continue
                }
            }
            i++
        }
        return result
    }

    private fun escaped(text: String, i: Int) = i > 0 && text[i - 1] == '\\'

    /** Text outside maths: `\$` is a dollar. */
    fun unescape(text: String): String = text.replace("\\$", "$")
}
