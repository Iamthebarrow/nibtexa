package io.github.iamthebarrow.nibtexa.core.export

import io.github.iamthebarrow.nibtexa.core.model.CapturedStroke
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.BookletInfo
import io.github.iamthebarrow.nibtexa.core.model.notebook.MathAlign
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageEditor
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LatexExporterTest {

    private fun line(x: Float, y: Float, pen: Pen = Pen.MATH) =
        CapturedStroke(ToolType.STYLUS, 0, listOf(InkPoint(x, y, 0), InkPoint(x + 10, y + 10, 8)), pen)

    /** A page with two confirmed equations and one region excluded from export. */
    private fun editor(): PageEditor {
        val editor = PageEditor(Page("p-1"))
        editor.addStroke(line(0f, 0f))
        editor.addStroke(line(20f, 0f))
        editor.addStroke(line(0f, 100f))
        editor.addStroke(line(0f, 300f, Pen.SCRATCH))
        editor.confirm(editor.createRegion(setOf(1, 2), RegionRole.MATH)!!, """X[k]=\sum_{n=0}^{N-1}x[n]e^{-j2\pi kn/N}""")
        editor.confirm(editor.createRegion(setOf(3), RegionRole.MATH)!!, """  H(s)=\frac{\omega_n^2}{s^2+2\zeta\omega_n s+\omega_n^2}""" + "\n")
        editor.createRegion(setOf(4), RegionRole.EXCLUDED)
        return editor
    }

    /** A heading, a paragraph and a captioned figure. */
    private fun page2(): Page {
        val editor = PageEditor(Page("p-2"))
        editor.addStroke(line(0f, 0f, Pen.TEXT))
        editor.addStroke(line(0f, 50f, Pen.TEXT))
        editor.addStroke(CapturedStroke(ToolType.STYLUS, 0, listOf(InkPoint(0f, 200f, 0), InkPoint(320f, 300f, 8)), Pen.DRAWING))
        editor.confirm(editor.createRegion(setOf(1), RegionRole.HEADING)!!, "Block diagram & notes")
        editor.confirm(editor.createRegion(setOf(2), RegionRole.TEXT)!!, "The DFT of x[n] uses 50% overlap.")
        editor.setCaption(editor.createRegion(setOf(3), RegionRole.FIGURE)!!, "Direct form")
        return editor.page
    }

    private val png = byteArrayOf(-119, 80, 78, 71)
    private val figures = FigureRenderer { _, _ -> png }

    private val info = BookletInfo(author = "Ada Lovelace", created = LocalDate.of(2026, 9, 3), updated = LocalDate.of(2026, 10, 28))

    private fun booklet(page: Page = editor().page) = Booklet("b-dsp", "Signals & Systems: week_1", listOf(page, page2()), info)

    private fun export(booklet: Booklet) = LatexExporter.export(booklet, figures)

    private fun golden(name: String) = checkNotNull(javaClass.getResource("/golden/$name")).readText().replace("\r\n", "\n")

    private fun success(result: ExportResult) = (result as? ExportResult.Success ?: error("expected success, got $result")).files

    @Test
    fun matchesTheGoldenFiles() {
        val exported = success(export(booklet()))
        val files = exported.associate { it.path to it.content }

        assertEquals(
            listOf("main.tex", "booklets/b-dsp/pages/p-1.tex", "booklets/b-dsp/pages/p-2.tex", "assets/p-2-r3.png", "manifest.json"),
            files.keys.toList(),
        )
        assertTrue(exported.single { it.path.endsWith(".png") }.bytes.contentEquals(png))
        assertEquals(golden("main.tex"), files["main.tex"])
        assertEquals(golden("p-1.tex"), files["booklets/b-dsp/pages/p-1.tex"])
        assertEquals(golden("p-2.tex"), files["booklets/b-dsp/pages/p-2.tex"])
        assertEquals(golden("manifest.json"), files["manifest.json"])
    }

    @Test
    fun theCoverLeavesOutWhatIsNotKnownAndEscapesTheAuthor() {
        val bare = success(export(booklet().copy(info = BookletInfo(author = "  R&D_team "))))
        val main = bare.single { it.path == "main.tex" }.content

        assertTrue(main, "\\author{Author: R\\&D\\_team}\n" in main)
        assertTrue(main, "\\date{}\n" in main)
    }

    @Test
    fun equationsCanBeFlushLeftOrRight() {
        fun page1(align: MathAlign) = success(export(booklet().copy(info = info.copy(mathAlign = align))))
            .single { it.path.endsWith("p-1.tex") }.content

        val left = page1(MathAlign.LEFT)
        assertTrue(left, "\\begin{flushleft}\n\\nibfit{X[k]=\\sum_{n=0}^{N-1}x[n]e^{-j2\\pi kn/N}}\n\\end{flushleft}\n" in left)
        val right = page1(MathAlign.RIGHT)
        assertTrue(right, "\\begin{flushright}\n" in right && "\\[" !in right)
        assertEquals(golden("p-1.tex"), page1(MathAlign.CENTER))
    }

    @Test
    fun aBookletWithoutHeadingsHasNoTableOfContents() {
        val main = success(export(Booklet("b-dsp", "T", listOf(editor().page), info))).single { it.path == "main.tex" }.content
        assertTrue(main, "tableofcontents" !in main && "pagenumbering" !in main)
        assertTrue(LatexExporter.hasContents(booklet()))
    }

    @Test
    fun eachRegionCanSitWhereTheUserPutsIt() {
        val e = PageEditor(page2())
        e.setAlign("r1", MathAlign.CENTER) // the heading
        e.setAlign("r2", MathAlign.RIGHT) // the paragraph
        e.setAlign("r3", MathAlign.LEFT) // the figure
        val p2 = success(export(booklet().copy(pages = listOf(editor().page, e.page)))).single { it.path.endsWith("p-2.tex") }.content

        assertTrue(p2, "\\begin{center}\n\\section{Block diagram \\& notes}\n\\end{center}\n" in p2)
        assertTrue(p2, "\\begin{flushright}\nThe DFT of x[n] uses 50\\% overlap.\n\\end{flushright}\n" in p2)
        assertTrue(p2, "\\begin{flushleft}\n\\includegraphics" in p2)

        // One equation on its own, against the booklet's setting; back to the default with null.
        val p1 = PageEditor(editor().page).apply { setAlign("r2", MathAlign.RIGHT) }
        val out = success(export(booklet(p1.page))).single { it.path.endsWith("p-1.tex") }.content
        assertTrue(out, out.indexOf("\\[") < out.indexOf("\\begin{flushright}"))
        assertTrue(p1.setAlign("r2", null))
        assertEquals(golden("p-1.tex"), success(export(booklet(p1.page))).single { it.path.endsWith("p-1.tex") }.content)
        p1.undo()
        assertEquals(MathAlign.RIGHT, p1.page.region("r2")!!.align)
    }

    @Test
    fun unchangedNotesExportIdenticalFiles() {
        assertEquals(success(export(booklet())), success(export(booklet())))
        assertTrue(success(export(booklet())).filter { it.path.endsWith(".tex") }.none { '\r' in it.content })
    }

    @Test
    fun unconfirmedAndStaleRegionsBlockTheExport() {
        val editor = editor()
        editor.addStroke(line(5f, 0f)) // centre inside r1: joins it, so r1 is stale
        editor.addStroke(line(0f, 500f))
        editor.createRegion(setOf(6), RegionRole.MATH) // never confirmed

        val result = export(booklet(editor.page))

        assertEquals(
            ExportResult.Invalid(listOf(ExportProblem.Stale("p-1", "r1"), ExportProblem.NotConfirmed("p-1", "r4"))),
            result,
        )
    }

    @Test
    fun unsafeOrDuplicateIdsAreRejected() {
        val bad = Booklet("../escape", "t", listOf(Page("p-1"), Page("p-1"), Page("P_2")))

        val problems = (export(bad) as ExportResult.Invalid).problems

        assertTrue(ExportProblem.UnsafeId("../escape") in problems)
        assertTrue(ExportProblem.UnsafeId("P_2") in problems)
        assertTrue(ExportProblem.DuplicateId("p-1") in problems)
    }

    @Test
    fun latexThatTouchesFilesOrTheDocumentIsRejected() {
        val unsafe = listOf(
            """\input{/etc/passwd}""",
            """x\immediate\write18{rm -rf ~}""",
            """\end{document}""",
            """\def\x{1}""",
            """\frac{1}{2""",
            """a}{b""",
        )
        for (latex in unsafe) {
            val editor = PageEditor(Page("p-1"))
            editor.addStroke(line(0f, 0f))
            editor.confirm(editor.createRegion(setOf(1), RegionRole.MATH)!!, latex)
            val result = export(Booklet("b", "t", listOf(editor.page)))
            assertTrue("$latex should be rejected, got $result", result is ExportResult.Invalid)
        }
    }

    @Test
    fun ordinaryMathIsNotMistakenForForbiddenCommands() {
        val editor = PageEditor(Page("p-1"))
        editor.addStroke(line(0f, 0f))
        // \delta, \inputs-like names and escaped braces must pass.
        editor.confirm(editor.createRegion(setOf(1), RegionRole.MATH)!!, """\delta\left\{x\right\}+\letter_{\textrm{read}}""")

        assertTrue(export(Booklet("b", "t", listOf(editor.page))) is ExportResult.Success)
    }

    @Test
    fun unconfirmedTextAndFiguresWithoutInkBlockTheExport() {
        val editor = PageEditor(Page("p-1"))
        editor.addStroke(line(0f, 0f, Pen.TEXT))
        editor.createRegion(setOf(1), RegionRole.TEXT)
        // Erasing a figure's ink deletes it (Phase 14), but older saved pages can still hold one.
        val inkless = Region("r2", RegionRole.FIGURE, setOf(99), Bounds(0f, 100f, 10f, 110f), readingOrder = 2)
        val page = editor.page.copy(regions = editor.page.regions + inkless)

        assertEquals(
            ExportResult.Invalid(listOf(ExportProblem.NotConfirmed("p-1", "r1"), ExportProblem.Stale("p-1", "r2"))),
            export(Booklet("b", "t", listOf(page))),
        )
    }

    @Test
    fun headingLevelsAndFigureWidthsFollowTheRegion() {
        val editor = PageEditor(Page("p-1"))
        editor.addStroke(line(0f, 0f, Pen.TEXT))
        editor.addStroke(CapturedStroke(ToolType.STYLUS, 0, listOf(InkPoint(0f, 100f, 0), InkPoint(2000f, 120f, 8)), Pen.DRAWING))
        val heading = editor.createRegion(setOf(1), RegionRole.HEADING)!!
        editor.confirm(heading, "Part")
        editor.setHeadingLevel(heading, 3)
        editor.createRegion(setOf(2), RegionRole.FIGURE)

        val tex = success(export(Booklet("b", "t", listOf(editor.page)))).single { it.path.endsWith("p-1.tex") }.content
        assertTrue(tex, tex.contains("\\subsubsection{Part}"))
        assertTrue(tex, tex.contains("\\includegraphics[width=4.50in]{assets/p-1-r2.png}")) // capped at the text width
    }

    @Test
    fun previewShowsReadyRegionsAndMarksTheRest() {
        val editor = editor()
        editor.addStroke(line(0f, 500f))
        editor.createRegion(setOf(5), RegionRole.MATH)

        val preview = LatexExporter.previewPage(editor.page)
        assertTrue(preview, preview.startsWith(golden("p-1.tex")))
        assertTrue(preview, preview.endsWith("% region r4\n% (not confirmed yet)\n"))
    }

    @Test
    fun escapesTextSpecialCharacters() {
        assertEquals("""a\&b \%c \${'$'}d \#e \_f \{g\} \textbackslash{}h \textasciicircum{}i \textasciitilde{}j""", LatexExporter.escapeText("a&b %c \$d #e _f {g} \\h ^i ~j"))
    }
}
