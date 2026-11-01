package io.github.iamthebarrow.nibtexa.core.export

import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.BookletInfo
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.ColumnMode
import io.github.iamthebarrow.nibtexa.core.model.notebook.DocumentStyle
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageMargin
import io.github.iamthebarrow.nibtexa.core.model.notebook.Paper
import io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.core.model.notebook.Sheet
import io.github.iamthebarrow.nibtexa.core.model.notebook.TheoremKind
import io.github.iamthebarrow.nibtexa.core.model.notebook.writingArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 30: columns, and the document settings, in every export. */
class DocumentLayoutTest {
    private var order = 0

    private fun text(id: String, words: String, left: Float, top: Float, right: Float, bottom: Float, theorem: TheoremKind? = null, pageBreak: Boolean = false) =
        Region(id, RegionRole.TEXT, emptySet(), Bounds(left, top, right, bottom), ++order, RecognitionStatus.CONFIRMED, text = words, typed = true, newParagraph = true, theorem = theorem, pageBreak = pageBreak)

    private fun heading(id: String, words: String, level: Int, top: Float, left: Float = 40f, right: Float = 460f) =
        Region(id, RegionRole.HEADING, emptySet(), Bounds(left, top, right, top + 40f), ++order, RecognitionStatus.CONFIRMED, text = words, headingLevel = level, typed = true, newParagraph = true)

    private fun math(id: String, latex: String, left: Float, top: Float, right: Float, bottom: Float) =
        Region(id, RegionRole.MATH, emptySet(), Bounds(left, top, right, bottom), ++order, RecognitionStatus.CONFIRMED, confirmedLatex = latex, typed = true, inline = false)

    /** Like the page from the user's testing: columns either side of a wide equation, with a numbered list down the left. */
    private fun columnsPage(columns: ColumnMode = ColumnMode.AUTO) = Page(
        "p1",
        regions = listOf(
            heading("title", "Transfer functions", 1, 0f),
            text("system", "A system's transfer function.", 40f, 50f, 460f, 100f),
            text("pole", "A pole is a root.", 460f, 55f, 880f, 95f),
            math("eq", "H(s)=1", 250f, 110f, 670f, 170f),
            text("one", "1. a single pole", 40f, 180f, 460f, 220f),
            text("note", "See the article.", 460f, 190f, 880f, 230f),
            text("two", "2. a pair", 40f, 230f, 460f, 270f),
            text("three", "3. a third", 40f, 280f, 460f, 320f),
            text("zeta", "Damping below.", 460f, 290f, 880f, 330f),
        ),
        nextRegionNumber = 10,
        columns = columns,
    )

    private fun booklet(page: Page = columnsPage(), style: DocumentStyle = DocumentStyle()) = Booklet("b1", "Signals", listOf(page), BookletInfo(style = style))

    private fun latex(booklet: Booklet): Map<String, String> {
        val result = LatexExporter.export(booklet) { _, _ -> ByteArray(0) } as ExportResult.Success
        return result.files.filter { it.path.endsWith(".tex") }.associate { it.path to it.content }
    }

    private fun page(booklet: Booklet) = latex(booklet).filterKeys { it != "main.tex" }.values.single()

    private fun words(blocks: List<FlowBlock>) = blocks.map {
        when (it) {
            FlowBlock.ColumnsStart -> "["
            FlowBlock.ColumnBreak -> "|"
            FlowBlock.ColumnsEnd -> "]"
            is FlowBlock.Math -> "math"
            else -> it.regionIds.joinToString()
        }
    }

    @Test
    fun theFlowFollowsTheColumns() {
        assertEquals(
            listOf("[", "title", "system", "|", "pole", "]", "math", "[", "one", "two", "three", "|", "note", "zeta", "]"),
            words(Flow.of(booklet()).single()),
        )
    }

    /** The bug the user saw: a line from the other column landed in the list, and it numbered 1, 1. */
    @Test
    fun aListInAColumnIsOneList() {
        val items = Flow.of(booklet()).single().filterIsInstance<FlowBlock.ListItem>()
        assertEquals(listOf("1.", "2.", "3."), items.map { it.marker })
    }

    @Test
    fun theLatexIsInMulticols() {
        val files = latex(booklet())
        assertTrue(files.getValue("main.tex").contains("\\usepackage{multicol}"))
        val tex = page(booklet())
        assertEquals(2, Regex("""\\begin\{multicols\}\{2\}""").findAll(tex).count())
        assertEquals(2, Regex("""\\columnbreak""").findAll(tex).count())
        assertEquals(2, Regex("""\\end\{multicols\}""").findAll(tex).count())
        // The equation isn't in either.
        val first = tex.indexOf("\\end{multicols}")
        val equation = tex.indexOf("H(s)=1")
        assertTrue(equation > first && equation < tex.indexOf("\\begin{multicols}", first))
    }

    @Test
    fun aPageWithoutColumnsNeedsNoPackage() {
        val one = booklet(columnsPage(ColumnMode.ONE))
        assertFalse(latex(one).getValue("main.tex").contains("multicol"))
        assertFalse(page(one).contains("multicols"))
    }

    @Test
    fun theReadingOrderInMarkdownIsColumnByColumn() {
        val md = (MarkdownExporter.export(booklet()) { _, _ -> ByteArray(0) } as ExportResult.Success).files.first { it.path.endsWith(".md") }.content
        assertTrue(md.indexOf("3. a third") < md.indexOf("See the article."))
        assertFalse(md.contains("multicols"))
    }

    @Test
    fun thePdfGetsTheColumnsToo() {
        val blocks = (PdfLayout.blocks(booklet()) as PdfLayout.Result.Blocks).blocks
        assertEquals(2, blocks.count { it == PdfBlock.ColumnsStart })
        assertEquals(2, blocks.count { it == PdfBlock.ColumnBreak })
        assertEquals(2, blocks.count { it == PdfBlock.ColumnsEnd })
    }

    @Test
    fun aPageBreakInAColumnClosesTheColumnsFirst() {
        val page = Page(
            "p1",
            regions = listOf(
                text("a", "a", 40f, 0f, 400f, 40f), text("b", "b", 40f, 60f, 400f, 100f, pageBreak = true), text("c", "c", 40f, 120f, 400f, 160f),
                text("d", "d", 440f, 10f, 800f, 50f), text("e", "e", 440f, 90f, 800f, 130f),
            ),
            nextRegionNumber = 6,
        )
        val blocks = Flow.of(booklet(page)).single()
        val at = blocks.indexOfFirst { it is FlowBlock.PageBreak }
        assertEquals(FlowBlock.ColumnsEnd, blocks[at - 1])
        assertEquals(FlowBlock.ColumnsStart, blocks[at + 1])
    }

    @Test
    fun aHandArrangedOrderStands() {
        val manual = columnsPage().copy(manualOrder = true)
        assertFalse(Flow.of(booklet(manual)).single().contains(FlowBlock.ColumnsStart))
        // Unless columns are asked for as well.
        assertTrue(Flow.of(booklet(manual.copy(columns = ColumnMode.TWO))).single().contains(FlowBlock.ColumnsStart))
    }

    @Test
    fun multicolsImportBackAsColumns() {
        val tex = "\\begin{document}\nAbove.\n\\begin{multicols}{2}\nLeft one.\n\nLeft two.\n\\columnbreak\nRight one.\n\\end{multicols}\nBelow.\n\\end{document}\n"
        val imported = LatexImporter.import(tex, read = { null }, picture = { null }, newId = { "p1" }).pages.single()
        assertEquals(ColumnMode.TWO, imported.columns)
        val blocks = Flow.of(Booklet("b1", "t", listOf(imported))).single()
        val texts = blocks.filterIsInstance<FlowBlock.Paragraph>().map { Flow.plain(it.content) }
        assertEquals(listOf("Above.", "Left one.", "Left two.", "Right one.", "Below."), texts)
        assertEquals(1, blocks.count { it == FlowBlock.ColumnsStart })
        val at = blocks.indexOf(FlowBlock.ColumnBreak)
        assertEquals("Right one.", Flow.plain((blocks[at + 1] as FlowBlock.Paragraph).content))
    }

    // ---- Margins --------------------------------------------------------------------------------

    @Test
    fun theMarginIsInTheLatexAndThePdf() {
        val cheat = booklet(style = DocumentStyle(margin = PageMargin.CHEAT_SHEET))
        val main = latex(cheat).getValue("main.tex")
        assertTrue(main, main.contains("[a4paper,margin=0.25in,includefoot]{geometry}"))
        assertEquals(18f, PdfLayout.page(cheat).margin, 0.01f)
        val wide = booklet(style = DocumentStyle(margin = PageMargin.WIDE))
        assertTrue(latex(wide).getValue("main.tex").contains("margin=1.25in]{geometry}"))
        assertEquals(90f, PdfLayout.page(wide).margin, 0.01f)
    }

    @Test
    fun aPageCanHaveItsOwnMargin() {
        val own = booklet(columnsPage().copy(margin = PageMargin.CHEAT_SHEET))
        val tex = page(own)
        assertTrue(tex, tex.contains("\\newgeometry{margin=0.25in,includefoot}"))
        assertTrue(tex, tex.trimEnd().endsWith("\\restoregeometry"))
        // The booklet's stays what it is.
        assertTrue(latex(own).getValue("main.tex").contains("margin=0.78in]{geometry}"))
        val blocks = (PdfLayout.blocks(own) as PdfLayout.Result.Blocks).blocks
        assertEquals(1, blocks.count { it == PdfBlock.Margin(18f) })
        // A running header takes the new width, and doesn't run off the page.
        val headed = page(booklet(columnsPage().copy(margin = PageMargin.WIDE), DocumentStyle(runningHeader = true)))
        assertEquals(2, Regex("""\\setlength\{\\headwidth\}\{\\textwidth\}""").findAll(headed).count())
        // The same as the booklet's needs nothing.
        val same = booklet(columnsPage().copy(margin = PageMargin.NORMAL))
        assertFalse(page(same).contains("newgeometry"))
        assertFalse((PdfLayout.blocks(same) as PdfLayout.Result.Blocks).blocks.any { it is PdfBlock.Margin })
    }

    @Test
    fun aPageAfterOneWithItsOwnMarginGoesBackToTheBookletsInThePdf() {
        val pages = listOf(columnsPage().copy(id = "p1", margin = PageMargin.WIDE), columnsPage().copy(id = "p2"))
        val blocks = (PdfLayout.blocks(Booklet("b1", "T", pages)) as PdfLayout.Result.Blocks).blocks.filterIsInstance<PdfBlock.Margin>()
        assertEquals(listOf(PdfBlock.Margin(90f), PdfBlock.Margin(56f)), blocks)
    }

    @Test
    fun aHeaderInASmallMarginStaysOnThePage() {
        val main = latex(booklet(style = DocumentStyle(margin = PageMargin.CHEAT_SHEET, runningHeader = true))).getValue("main.tex")
        assertTrue(main, main.contains("margin=0.25in,includeheadfoot"))
    }

    @Test
    fun anEquationIsMadeToFitAndComesBackWithout() {
        val tex = page(booklet())
        assertTrue(tex, tex.contains("\\nibfit{H(s)=1}"))
        assertTrue(latex(booklet()).getValue("main.tex").contains("\\newcommand{\\nibfit}"))
        val back = LatexImporter.import("\\begin{document}\n\\[\n\\nibfit{x^2}\n\\]\n\\end{document}\n", newId = { "p1" }).pages.single()
        assertEquals("x^2", back.regions.single().confirmedLatex)
        // Lines broken outside an environment can't go in a box.
        val broken = Page("p1", regions = listOf(math("a", "a=1\\\\b=2", 40f, 0f, 400f, 60f)), nextRegionNumber = 2)
        assertFalse(page(booklet(broken)).contains("\\nibfit"))
    }

    @Test
    fun whatIsImportedIsInsideTheMarginAndGoesOnToNewPages() {
        val area = Sheet().writingArea(Paper.BLANK, PageMargin.NORMAL)
        val long = (1..80).joinToString("\n\n") { "Paragraph $it. " + "Words on a line. ".repeat(12) }
        var n = 0
        val pages = LatexImporter.import("\\begin{document}\n$long\n\\end{document}\n", newId = { "p${++n}" }).pages
        assertTrue("${pages.size} pages", pages.size > 1)
        for (p in pages) for (r in p.regions) {
            assertTrue("$r", r.bounds.left >= area.left && r.bounds.right <= area.right + 0.01f && r.bounds.top >= area.top && r.bounds.bottom <= area.bottom + 0.01f)
        }
        val text = pages.flatMap { it.regionsInReadingOrder }.map { it.text.orEmpty().substringBefore(".") }
        assertEquals((1..80).map { "Paragraph $it" }, text)
    }

    @Test
    fun importedColumnsThatFillAPageCarryOnInColumns() {
        val left = (1..60).joinToString("\n\n") { "Left $it. " + "Words. ".repeat(10) }
        var n = 0
        val pages = LatexImporter.import("\\begin{document}\n\\begin{multicols}{2}\n$left\n\\end{multicols}\n\\end{document}\n", newId = { "p${++n}" }).pages
        assertTrue(pages.size > 1)
        assertTrue(pages.all { it.columns == ColumnMode.TWO })
        val area = Sheet().writingArea(Paper.BLANK, PageMargin.NORMAL)
        for (p in pages) for (r in p.regions) assertTrue("$r", r.bounds.bottom <= area.bottom + 0.01f && r.bounds.right <= area.right + 0.01f)
    }

    // ---- The document settings ----------------------------------------------------------------

    private fun sections() = Page(
        "p1",
        regions = listOf(
            heading("a", "First", 1, 0f, right = 800f), heading("b", "Inner", 2, 60f, right = 800f), heading("c", "Inner two", 2, 120f, right = 800f),
            heading("d", "Second", 1, 180f, right = 800f), heading("e", "Deep", 3, 240f, right = 800f),
        ),
        nextRegionNumber = 6,
    )

    @Test
    fun sectionsAreNumberedAsLatexCountsThem() {
        val numbers = Flow.of(booklet(sections(), DocumentStyle(numberedSections = true))).single().filterIsInstance<FlowBlock.Heading>().map { it.number }
        assertEquals(listOf("1", "1.1", "1.2", "2", "2.0.1"), numbers)
    }

    @Test
    fun withoutNumbersLatexLeavesThemOutToo() {
        val plain = latex(booklet(sections())).getValue("main.tex")
        assertTrue(plain.contains("\\setcounter{secnumdepth}{0}"))
        val numbered = latex(booklet(sections(), DocumentStyle(numberedSections = true))).getValue("main.tex")
        assertFalse(numbered.contains("secnumdepth"))
        assertTrue(Flow.of(booklet(sections())).single().filterIsInstance<FlowBlock.Heading>().all { it.number == null })
    }

    @Test
    fun aTitleOnTheFirstPageReplacesTheCoverAndContents() {
        val style = DocumentStyle(titleOnFirstPage = true)
        val main = latex(booklet(sections(), style)).getValue("main.tex")
        assertTrue(main.contains("\\documentclass{article}"))
        assertTrue(main.contains("\\maketitle"))
        assertFalse(main.contains("\\tableofcontents"))
        val blocks = (PdfLayout.blocks(booklet(sections(), style)) as PdfLayout.Result.Blocks).blocks
        assertTrue(blocks.first() is PdfBlock.TitleBlock)
        // As it was without the setting.
        val cover = latex(booklet(sections())).getValue("main.tex")
        assertTrue(cover.contains("\\documentclass[titlepage]{article}"))
        assertTrue(cover.contains("\\tableofcontents"))
    }

    @Test
    fun theRunningHeaderIsOnlyThereWhenAskedFor() {
        val main = latex(booklet(sections(), DocumentStyle(runningHeader = true))).getValue("main.tex")
        assertTrue(main.contains("\\usepackage{fancyhdr}"))
        assertTrue(main.contains("\\fancyhead[L]{\\nouppercase{Signals}}"))
        assertFalse(latex(booklet(sections())).getValue("main.tex").contains("fancyhdr"))
    }

    @Test
    fun anAbstractUsesLatexsOwnAndRoundTrips() {
        val page = Page("p1", regions = listOf(text("a", "What this is about.", 40f, 0f, 800f, 40f, theorem = TheoremKind.ABSTRACT)), nextRegionNumber = 2)
        val tex = page(booklet(page))
        assertTrue(tex.contains("\\begin{abstract}"))
        val main = latex(booklet(page)).getValue("main.tex")
        assertFalse(main.contains("\\newtheorem{abstract}"))
        // It takes no number.
        val theorem = Flow.of(booklet(page)).single().filterIsInstance<FlowBlock.Paragraph>().single().theorem
        assertEquals(Theorem(TheoremKind.ABSTRACT, null), theorem)
        val back = LatexImporter.import("\\begin{document}\n\\begin{abstract}\nWhat this is about.\n\\end{abstract}\n\\end{document}\n", newId = { "p1" }).pages.single()
        assertEquals(TheoremKind.ABSTRACT, back.regions.single().theorem)
    }
}
