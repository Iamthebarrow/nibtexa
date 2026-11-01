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
import org.junit.Assert.assertTrue
import org.junit.Test

/** Importing LaTeX (Phase 18): the app's own export comes back the same, common LaTeX reads, and nothing unsafe gets in. */
class LatexImporterTest {
    private var ids = 0
    private fun import(main: String, files: Map<String, String> = emptyMap()) = LatexImporter.import(main, { files[it] }) { "p-${++ids}" }

    /** A booklet's document, block by block, as words and kinds: what must survive a round trip. */
    private fun document(pages: List<Page>): List<String> = Flow.of(Booklet("b-1", "T", pages)).flatten().mapNotNull { b ->
        when (b) {
            is FlowBlock.Heading -> "H${b.level} ${LatexExporter.rich(b.content)}"
            is FlowBlock.Paragraph -> "P${b.theorem?.kind?.let { " $it" } ?: ""} ${b.align} ${LatexExporter.rich(b.content)}"
            is FlowBlock.ListItem -> "L${b.level} ${b.marker} ${LatexExporter.rich(b.content)}"
            is FlowBlock.Math -> "M${b.number ?: ""} ${b.align} ${b.latex}"
            else -> null
        }
    }

    @Test
    fun theAppsOwnExportImportsBackTheSame() {
        val e = PageEditor(Page("p-1"))
        var y = 0f
        fun region(value: String, role: RegionRole = RegionRole.TEXT, x: Float = 0f, marks: List<TextMark> = emptyList()): String {
            y += 80f
            e.addStroke(CapturedStroke(ToolType.STYLUS, 0, listOf(InkPoint(x, y, 0), InkPoint(x + 100f, y + 24f, 10)), if (role == RegionRole.MATH) Pen.MATH else Pen.TEXT))
            val id = e.createRegion(setOf(e.page.ink.strokes.last().id), role)!!
            e.confirm(id, value, marks)
            return id
        }
        region("Transfer functions", RegionRole.HEADING, marks = listOf(TextMark(0, 8, MarkStyle.Bold)))
        val styled = "Bold italic under mono big red link and note."
        fun at(word: String, style: MarkStyle) = styled.indexOf(word).let { TextMark(it, it + word.length, style) }
        region(styled, marks = listOf(
            at("Bold", MarkStyle.Bold), at("italic", MarkStyle.Italic), at("under", MarkStyle.Underline), at("mono", MarkStyle.Typewriter),
            at("big", MarkStyle.Size(TextSize.LARGER)), at("red", MarkStyle.Color(TextColor.RED)),
            at("link", MarkStyle.Link("https://example.org/a#b")), at("note", MarkStyle.Footnote("100% sure")),
        ))
        region("The root \$s^2+1=0\$ costs \\\$5.")
        region("- poles")
        region("- stable", x = 40f)
        region("1. first", x = 80f)
        region("- zeros")
        e.setTheorem(region("A pole is a root."), TheoremKind.DEFINITION)
        e.setTheorem(region("By partial fractions."), TheoremKind.PROOF)
        e.setNumbered(region("H(s)=\\frac{1}{s+1}", RegionRole.MATH), true)
        e.setAlign(region("y=x", RegionRole.MATH), MathAlign.LEFT)
        e.setAlign(region("On the right."), MathAlign.RIGHT)
        e.setPageBreak(region("After the break."), true)
        val booklet = Booklet("b-1", "Signals & Systems", listOf(e.page))
        val files = (LatexExporter.export(booklet) { _, _ -> ByteArray(0) } as ExportResult.Success).files.associate { it.path to it.content }

        val result = import(files.getValue("main.tex"), files)
        assertEquals("Signals & Systems", result.title)
        assertEquals(document(listOf(e.page)), document(result.pages))
        assertTrue(result.pages.flatMap { it.regions }.all { it.typed && it.strokeIds.isEmpty() })
        // The page break became a page.
        assertEquals(2, result.pages.size)
        assertEquals(emptyList<String>(), result.warnings)
    }

    @Test
    fun commonLatexReads() {
        val result = import(
            """
            \documentclass{article}
            \title{Week 3}
            \author{Ada}
            \begin{document}
            \maketitle
            \section*{Poles}
            A pole is where ${'$'}H${'$'} blows up. % a comment
            It costs 5\% of the \textbf{gain} and {\large more}.

            \begin{itemize}
              \item first
              \begin{enumerate}
                \item inner
              \end{enumerate}
              \item second
            \end{itemize}
            \begin{align*}
              a &= b \\
              c &= d
            \end{align*}
            \begin{theorem}[Stability]
            All poles on the left.
            \end{theorem}
            See \href{https://example.org}{the notes}\footnote{Or the book.} and \url{https://example.org}.

            \foo{kept words} \includegraphics{plot.png}
            \begin{tabular}{cc} 1 & 2 \end{tabular}
            \end{document}
            """.trimIndent(),
        )
        assertEquals("Week 3", result.title)
        assertEquals("Ada", result.author)
        val doc = document(result.pages)
        assertEquals("H1 Poles", doc[0])
        assertEquals("P LEFT A pole is where \$H\$ blows up. It costs 5\\% of the \\textbf{gain} and {\\large more}.", doc[1])
        assertEquals(listOf("L0 • first", "L1 1. inner", "L0 • second"), doc.subList(2, 5))
        assertTrue(doc[5], doc[5].startsWith("M CENTER \\begin{aligned}"))
        assertEquals("P THEOREM LEFT (Stability) All poles on the left.", doc[6])
        assertTrue(doc[7], doc[7].contains("\\href{https://example.org}{the notes}\\footnote{Or the book.}"))
        assertTrue(doc[8], doc[8].startsWith("P LEFT kept words"))
        assertTrue(result.warnings.any { "foo" in it })
        assertTrue(result.warnings.any { "plot.png" in it })
        assertTrue(result.warnings.any { "table" in it })
    }

    @Test
    fun unsafeMathsAndFilesOutsideTheProjectAreLeftOut() {
        val result = import(
            """
            Text with ${'$'}\input{secret}${'$'} in it.
            \[ \write18{rm} \]
            \input{../outside}
            """.trimIndent(),
        )
        val doc = document(result.pages)
        assertEquals(listOf("P LEFT Text with in it."), doc)
        assertTrue(result.warnings.count { "maths was left out" in it } >= 1)
        assertTrue(result.warnings.any { "not in the project" in it })
    }

    @Test
    fun eachIncludedFileIsAPage() {
        val result = import("\\begin{document}\\input{a}\\input{b.tex}\\end{document}", mapOf("a" to "First page.", "b.tex" to "Second page."))
        assertEquals(2, result.pages.size)
        assertEquals(listOf("P LEFT First page.", "P LEFT Second page."), document(result.pages))
    }

    @Test
    fun picturesExportWithTheirTypeAndImportBackFromTheProject() {
        val e = PageEditor(Page("p-1"))
        val id = e.addPicture("photo.jpg", io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds(40f, 40f, 340f, 240f))
        e.setCaption(id, "The board")
        val sheet = io.github.iamthebarrow.nibtexa.core.model.notebook.Sheet.ofPoints(960f, 540f)
        val booklet = Booklet("b-1", "T", listOf(e.page), io.github.iamthebarrow.nibtexa.core.model.notebook.BookletInfo(sheet = sheet))
        val files = (LatexExporter.export(booklet) { _, _ -> byteArrayOf(1) } as ExportResult.Success).files.associate { it.path to it.content }
        assertTrue(files.keys.toString(), "assets/p-1-r1.jpg" in files)
        // A custom page size (an imported PDF's) is given by its measures.
        assertTrue("\\usepackage[paperwidth=13.333in,paperheight=7.500in,margin=0.78in]{geometry}" in files.getValue("main.tex"))

        val stored = mutableListOf<String>()
        val result = LatexImporter.import(files.getValue("main.tex"), { files[it] }, { path -> stored += path; ImportedPicture("new.jpg", 300, 200) }) { "p-${++ids}" }
        assertEquals(listOf("assets/p-1-r1.jpg"), stored)
        val picture = result.pages.single().regions.single()
        assertEquals("new.jpg", picture.picture)
        assertEquals("The board", picture.caption)
        assertEquals(RegionRole.FIGURE, picture.role)
        assertEquals(emptyList<String>(), result.warnings)
    }
}
