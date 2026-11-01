package io.github.iamthebarrow.nibtexa.core.export

import io.github.iamthebarrow.nibtexa.core.model.CapturedStroke
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.MarkStyle
import io.github.iamthebarrow.nibtexa.core.model.notebook.MathAlign
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageEditor
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextColor
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextMark
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextSize
import io.github.iamthebarrow.nibtexa.core.model.notebook.TheoremKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The document flow and its LaTeX (Phase 16). Writing is 24 units tall (h = 24): lines 30 apart
 * top to top are one paragraph (a gap of 6), lines 80 apart are separate (a gap of 56).
 */
class FlowTest {
    private val editor = PageEditor(Page("p-1"))

    /** A region of one stroke filling (x, y, w, 24), confirmed as [value]. Returns its id. */
    private fun region(x: Float, y: Float, value: String, role: RegionRole = RegionRole.TEXT, w: Float = 100f, marks: List<TextMark> = emptyList()): String {
        val pen = if (role == RegionRole.MATH) Pen.MATH else Pen.TEXT
        editor.addStroke(CapturedStroke(ToolType.STYLUS, 0, listOf(InkPoint(x, y, 0), InkPoint(x + w, y + 24f, 10)), pen))
        val id = editor.createRegion(setOf(editor.page.ink.strokes.last().id), role)!!
        editor.confirm(id, value, marks)
        return id
    }

    private fun blocks() = Flow.page(editor.page, MathAlign.CENTER, preview = false)

    private fun tex() = LatexExporter.previewPage(editor.page)

    private fun export(): Map<String, String> {
        val result = LatexExporter.export(Booklet("b-1", "Notes", listOf(editor.page))) { _, _ -> ByteArray(0) }
        check(result is ExportResult.Success) { "not exported: $result" }
        return result.files.associate { it.path to it.content }
    }

    private fun main() = export().getValue("main.tex")

    // ---- Paragraphs --------------------------------------------------------------------------

    @Test
    fun linesWrittenCloseTogetherAreOneParagraph() {
        region(0f, 0f, "The closed loop has")
        region(0f, 30f, "two complex poles.")
        region(0f, 110f, "A new thought.")

        val paragraphs = blocks().filterIsInstance<FlowBlock.Paragraph>()
        assertEquals(listOf("The closed loop has two complex poles.", "A new thought."), paragraphs.map { Flow.plain(it.content) })
        assertEquals(listOf("r1", "r2"), paragraphs.first().regionIds)
        assertTrue(tex().contains("% regions r1, r2\nThe closed loop has two complex poles.\n"))
    }

    @Test
    fun theNewParagraphSwitchOverridesTheSpacing() {
        region(0f, 0f, "One")
        val second = region(0f, 30f, "Two")
        val third = region(0f, 110f, "Three")
        editor.setNewParagraph(second, true)
        editor.setNewParagraph(third, false)

        assertEquals(listOf("One", "Two Three"), blocks().map { Flow.plain((it as FlowBlock.Paragraph).content) })
    }

    // ---- Inline maths --------------------------------------------------------------------------

    @Test
    fun dollarsInTextAreInlineMaths() {
        region(0f, 0f, "the root \$x^2\$ is real, costs \$5 and \$10, and \\\$ is a dollar")

        val content = (blocks().single() as FlowBlock.Paragraph).content
        assertEquals(Inline.Math("x^2"), content[1])
        assertEquals(Inline.Text(" is real, costs \$5 and \$10, and \$ is a dollar"), content[2])
        assertTrue(tex().contains("the root \$x^2\$ is real, costs \\\$5 and \\\$10, and \\\$ is a dollar"))
    }

    @Test
    fun aMathRegionOnTheLineOfTextJoinsTheSentence() {
        region(0f, 0f, "the root")
        region(110f, 0f, "x^2", RegionRole.MATH, w = 30f)
        region(150f, 0f, "is real")

        val paragraph = blocks().single() as FlowBlock.Paragraph
        assertEquals(listOf(Inline.Text("the root "), Inline.Math("x^2"), Inline.Text(" is real")), paragraph.content)
        assertTrue(tex().contains("the root \$x^2\$ is real\n"))
    }

    @Test
    fun aMathRegionOnItsOwnLineStaysDisplayedUnlessSetInline() {
        region(0f, 0f, "so that")
        val eq = region(0f, 30f, "x^2", RegionRole.MATH)
        assertEquals(2, blocks().size)
        assertTrue(blocks()[1] is FlowBlock.Math)

        editor.setInline(eq, true)
        assertEquals(listOf(Inline.Text("so that "), Inline.Math("x^2")), (blocks().single() as FlowBlock.Paragraph).content)
    }

    @Test
    fun unsafeInlineMathsIsRefused() {
        region(0f, 0f, "see \$\\input{secret}\$")
        val problems = LatexExporter.validate(Booklet("b-1", "Notes", listOf(editor.page)))
        assertTrue(problems.single() is ExportProblem.UnsafeLatex)
    }

    // ---- Lists -----------------------------------------------------------------------------------

    @Test
    fun itemsFurtherRightAreSubItems() {
        region(0f, 0f, "- poles")
        region(40f, 80f, "- stable")
        region(80f, 160f, "1. first")
        region(80f, 240f, "2. second")
        region(0f, 320f, "- zeros")

        val items = blocks().map { it as FlowBlock.ListItem }
        assertEquals(listOf(0, 1, 2, 2, 0), items.map { it.level })
        assertEquals(listOf("•", "–", "1.", "2.", "•"), items.map { it.marker })
        val tex = tex()
        assertTrue(tex, tex.contains("\\begin{itemize}\n\\item poles\n\n% region r2\n\\begin{itemize}\n\\item stable\n\n% region r3\n\\begin{enumerate}\n\\item first"))
        assertTrue(tex, tex.contains("\\item second\n\\end{enumerate}\n\\end{itemize}\n\n% region r5\n\\item zeros\n\\end{itemize}"))
    }

    @Test
    fun aLevelIsNeverSkippedAndIndentOverrides() {
        region(0f, 0f, "- first")
        val second = region(0f, 80f, "- second")
        editor.setListLevel(second, 2) // only one deeper than the item before is possible
        assertEquals(listOf(0, 1), blocks().map { (it as FlowBlock.ListItem).level })
    }

    @Test
    fun aContinuationLineJoinsItsItem() {
        region(0f, 0f, "- the poles move")
        region(20f, 30f, "towards the zeros")
        val item = blocks().single() as FlowBlock.ListItem
        assertEquals("the poles move towards the zeros", Flow.plain(item.content))
    }

    // ---- Theorems, equation numbers, page breaks ----------------------------------------------------

    @Test
    fun theoremsAreNumberedByKindAndProofsAreNot() {
        val d1 = region(0f, 0f, "A pole is a root of the denominator.")
        val t1 = region(0f, 80f, "Stable iff all poles are in the left half-plane.")
        val p = region(0f, 160f, "Partial fractions.")
        val d2 = region(0f, 240f, "A zero is a root of the numerator.")
        editor.setTheorem(d1, TheoremKind.DEFINITION)
        editor.setTheorem(t1, TheoremKind.THEOREM)
        editor.setTheorem(p, TheoremKind.PROOF)
        editor.setTheorem(d2, TheoremKind.DEFINITION)

        val theorems = blocks().map { (it as FlowBlock.Paragraph).theorem }
        assertEquals(listOf(Theorem(TheoremKind.DEFINITION, 1), Theorem(TheoremKind.THEOREM, 1), Theorem(TheoremKind.PROOF, null), Theorem(TheoremKind.DEFINITION, 2)), theorems)
        assertTrue(tex().contains("\\begin{definition}\nA pole is a root of the denominator.\n\\end{definition}"))
        assertTrue(tex().contains("\\begin{proof}\nPartial fractions.\n\\end{proof}"))
        val main = main()
        assertTrue(main, main.contains("\\usepackage{amsthm}\n\\theoremstyle{definition}\n\\newtheorem{definition}{Definition}\n\\newtheorem{example}{Example}\n"))
        assertTrue(main.contains("\\theoremstyle{plain}\n\\newtheorem{theorem}{Theorem}\n\\newtheorem{lemma}{Lemma}\n"))
    }

    @Test
    fun aDefinitionRunsOnToItsNextLine() {
        val d = region(0f, 0f, "A pole is a root")
        region(0f, 30f, "of the denominator.")
        editor.setTheorem(d, TheoremKind.DEFINITION)
        assertEquals("A pole is a root of the denominator.", Flow.plain((blocks().single() as FlowBlock.Paragraph).content))
    }

    @Test
    fun numberedEquationsCountThroughTheBooklet() {
        val a = region(0f, 0f, "a=b", RegionRole.MATH)
        region(0f, 80f, "c=d", RegionRole.MATH)
        val c = region(0f, 160f, "e=f", RegionRole.MATH)
        editor.setNumbered(a, true)
        editor.setNumbered(c, true)

        assertEquals(listOf(1, null, 2), blocks().map { (it as FlowBlock.Math).number })
        assertTrue(tex().contains("\\begin{equation}\n\\nibfit{a=b}\n\\end{equation}"))
        assertTrue(tex().contains("\\[\n\\nibfit{c=d}\n\\]"))
        editor.setAlign(c, MathAlign.LEFT)
        assertTrue(tex().contains("\\nibfit{e=f}\\hfill\\refstepcounter{equation}(\\theequation)"))
    }

    @Test
    fun aPageBreakStartsANewPage() {
        region(0f, 0f, "Before")
        val after = region(0f, 80f, "After")
        editor.setPageBreak(after, true)
        assertEquals(listOf("Paragraph", "PageBreak", "Paragraph"), blocks().map { it::class.simpleName })
        assertTrue(tex().contains("% region r2\n\\clearpage\n\n% region r2\nAfter"))
    }

    // ---- Formatting --------------------------------------------------------------------------------

    @Test
    fun formattingBecomesLatexCommandsAndPackages() {
        val text = "bold italic under mono big red link note"
        fun at(word: String, style: MarkStyle) = text.indexOf(word).let { TextMark(it, it + word.length, style) }
        region(
            0f, 0f, text,
            marks = listOf(
                at("bold", MarkStyle.Bold),
                at("italic", MarkStyle.Italic),
                at("under", MarkStyle.Underline),
                at("mono", MarkStyle.Typewriter),
                at("big", MarkStyle.Size(TextSize.LARGER)),
                at("red", MarkStyle.Color(TextColor.RED)),
                at("link", MarkStyle.Link("https://example.org/a#b")),
                at("note", MarkStyle.Footnote("100% sure")),
            ),
        )
        val tex = tex()
        assertTrue(
            tex,
            tex.contains(
                "\\textbf{bold} \\textit{italic} \\uline{under} \\texttt{mono} {\\Large big} \\textcolor{red}{red} " +
                    "\\href{https://example.org/a\\#b}{link} note\\footnote{100\\% sure}",
            ),
        )
        val main = main()
        assertTrue(main.contains("\\usepackage[normalem]{ulem}\n\\usepackage{xcolor}\n\\usepackage[colorlinks=true,linkcolor=black,urlcolor=blue]{hyperref}\n"))
    }

    @Test
    fun plainBookletsNeedNoExtraPackages() {
        region(0f, 0f, "Just words.")
        val main = main()
        assertFalse(main.contains("ulem") || main.contains("xcolor") || main.contains("hyperref") || main.contains("amsthm"))
    }

    @Test
    fun aFormattedHeadingKeepsTheContentsPlain() {
        val id = region(0f, 0f, "Transfer functions", RegionRole.HEADING, marks = listOf(TextMark(0, 8, MarkStyle.Bold)))
        editor.setHeadingLevel(id, 2)
        assertTrue(tex().contains("\\subsection[Transfer functions]{\\textbf{Transfer} functions}"))
    }

    @Test
    fun footnotesCountThroughTheBooklet() {
        region(0f, 0f, "one two", marks = listOf(TextMark(0, 3, MarkStyle.Footnote("a")), TextMark(4, 7, MarkStyle.Footnote("b"))))
        val notes = (blocks().single() as FlowBlock.Paragraph).content.filterIsInstance<Inline.Note>()
        assertEquals(listOf(Inline.Note(1, "a"), Inline.Note(2, "b")), notes)
    }

    // ---- Footnotes in the PDF -------------------------------------------------------------------------

    @Test
    fun aLineWithAFootnoteKeepsRoomForItOnItsPage() {
        val pages = PdfPaginator(pageHeight = 100f)
        pages.place(Span(0f, 50f))
        // 30 of line, 20 of note and the 14 gap do not fit in the 50 left.
        val placed = pages.place(Span(0f, 30f), notes = 20f)
        assertEquals(1, placed.single().page)
        assertEquals(20f + PdfPaginator.NOTES_GAP, pages.notesHeight)
        // The next line has to fit above the notes.
        assertEquals(1, pages.place(Span(0f, 30f)).single().page)
        assertEquals(2, pages.place(Span(0f, 30f)).single().page)
    }
}
