package io.github.iamthebarrow.nibtexa.core.export

import io.github.iamthebarrow.nibtexa.core.maths.MathsProblem
import io.github.iamthebarrow.nibtexa.core.model.InkDocument
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.Graph
import io.github.iamthebarrow.nibtexa.core.model.notebook.GraphCurve
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Graphs worked out and written as LaTeX (Phase 25). */
class GraphExportTest {
    private fun export(graph: Graph, caption: String? = null): ExportResult.Success {
        val region = Region("r1", RegionRole.FIGURE, emptySet(), Bounds(0f, 0f, 400f, 300f), 1, typed = true, caption = caption, graph = graph)
        val booklet = Booklet("b1", "Notes", listOf(Page("p1", InkDocument.EMPTY, listOf(region), nextRegionNumber = 2)))
        return LatexExporter.export(booklet) { _, _ -> error("A graph has no file") } as ExportResult.Success
    }

    private fun ExportResult.Success.text(path: String) = files.first { it.path == path }.bytes.decodeToString()
    private fun ExportResult.Success.page() = text("booklets/b1/pages/p1.tex")

    @Test
    fun itIsWrittenAsPgfplotsWithComputedPoints() {
        val out = export(Graph(listOf(GraphCurve("y=x^2")), -2.0, 2.0, 0.0, 4.0, title = "Squares"))
        assertTrue(out.text("main.tex").contains("\\usepackage{pgfplots}"))
        val page = out.page()
        assertTrue(page.contains("\\begin{axis}["))
        assertTrue(page.contains("xmin=-2, xmax=2, ymin=0, ymax=4"))
        assertTrue(page.contains("title={Squares}"))
        assertTrue(page.contains("\\addplot["))
        assertTrue(page.contains("coordinates {("))
        assertTrue(page.contains("\\addlegendentry{\$y=x^2\$}"))
        assertTrue(page.contains("xlabel={\$x\$}"))
        assertFalse("TeX evaluates nothing", page.contains("expression"))
        assertFalse(out.files.any { it.path.startsWith("assets/") })
    }

    @Test
    fun aPoleIsTwoPlotsWithOneLegendEntry() {
        val page = export(Graph(listOf(GraphCurve("y=\\frac{1}{x}")))).page()
        assertEquals(2, Regex("\\\\addplot\\[").findAll(page).count())
        assertEquals(1, Regex("forget plot").findAll(page).count())
        assertEquals(1, Regex("\\\\addlegendentry").findAll(page).count())
    }

    @Test
    fun whatCantBeDrawnIsLeftOutAndHiddenCurvesToo() {
        val page = export(
            Graph(listOf(GraphCurve("y=x"), GraphCurve("y=kx"), GraphCurve("x^2+y^2=1"), GraphCurve("y=2x", hidden = true))),
        ).page()
        assertEquals(1, Regex("\\\\addplot\\[").findAll(page).count())
        assertEquals(1, Regex("\\\\addlegendentry").findAll(page).count())
    }

    @Test
    fun noPackageWithoutAGraph() {
        val region = Region("r1", RegionRole.FIGURE, emptySet(), Bounds(0f, 0f, 10f, 10f), 1, typed = true, picture = "a.png")
        val booklet = Booklet("b1", "N", listOf(Page("p1", InkDocument.EMPTY, listOf(region), nextRegionNumber = 2)))
        val out = LatexExporter.export(booklet) { _, _ -> ByteArray(1) } as ExportResult.Success
        assertFalse(out.text("main.tex").contains("pgfplots"))
    }

    @Test
    fun nothingReachesTexUnchecked() {
        // The reader treats marks as names, so these get as far as the legend: they must not reach it as commands.
        val hostile = listOf("y=\\hat{\\input{/etc/passwd}}x", "y=x_{\\write18{rm}}", "y=x_{a%b}", "y=x_{}\\]")
        for (latex in hostile) {
            val graph = Graph(listOf(GraphCurve(latex)), constants = mapOf("\\hat{\\input{/etc/passwd}}" to 1.0, "x_{\\write18{rm}}" to 1.0))
            val page = export(graph.copy(xLabel = "\\input{a}", title = "{\\write18{b}}")).page()
            assertFalse(latex, page.contains("\\input{"))
            assertFalse(latex, page.contains("\\write18"))
        }
    }

    @Test
    fun theOutputIsDeterministic() {
        val graph = Graph(listOf(GraphCurve("y=\\sin x"), GraphCurve("y=\\frac{1}{x}", color = 1)))
        assertEquals(export(graph).page(), export(graph).page())
    }

    @Test
    fun matchesTheGoldenFileThatWasCompiled() {
        val graph = Graph(
            listOf(GraphCurve("\\[y=\\sin x\\]"), GraphCurve("y=\\frac{1}{x}", color = 1), GraphCurve("f(\\omega)=e^{-\\omega}", color = 2)),
            -6.0, 6.0, -3.0, 3.0, title = "Three & more", xLabel = "Time 100%",
        )
        val out = export(graph, caption = "A graph")
        val dir = File("build/graph-export")
        out.files.forEach { f -> File(dir, f.path).apply { parentFile.mkdirs() }.writeBytes(f.bytes) }
        val golden = checkNotNull(javaClass.getResource("/golden/graph-page.tex")).readText()
        assertEquals(golden, out.page())
    }

    @Test
    fun problemsAreSaidCurveByCurve() {
        val plotted = GraphPlot.of(Graph(listOf(GraphCurve("y=x"), GraphCurve("y=kx"), GraphCurve("x^2+y^2=1"), GraphCurve("y=a+b"))))
        assertEquals(null, plotted.curves[0].problem)
        assertEquals(CurveProblem.NeedsValues(listOf("k")), plotted.curves[1].problem)
        assertEquals(CurveProblem.CantRead(MathsProblem.Implicit), plotted.curves[2].problem)
        assertEquals(CurveProblem.CantRead(MathsProblem.AmbiguousInput(listOf("a", "b"))), plotted.curves[3].problem)
        assertEquals(1, plotted.shown.size)
    }

    @Test
    fun theUsersChoicesApply() {
        val chosen = GraphPlot.of(Graph(listOf(GraphCurve("y=a+b", input = "b")), constants = mapOf("a" to 1.0)))
        assertEquals(1, chosen.shown.size)
        assertEquals("b", chosen.xName)
        val degrees = GraphPlot.of(Graph(listOf(GraphCurve("y=\\sin x")), 0.0, 180.0, -1.0, 1.0, degrees = true), 181)
        val piece = degrees.shown.single().pieces.single()
        assertEquals(1.0, piece.ys[90], 1e-9)
    }

    @Test
    fun fittingFindsTheCurvesAndIgnoresWhatItCant() {
        val fitted = GraphPlot.fitted(Graph(listOf(GraphCurve("y=x^2+100"), GraphCurve("y=kx"))))
        assertTrue("${fitted.yMin}..${fitted.yMax}", fitted.yMin in 70.0..100.0 && fitted.yMax in 200.0..230.0)
        val none = Graph(listOf(GraphCurve("y=kx")))
        assertEquals(none, GraphPlot.fitted(none))
        val undefined = Graph(listOf(GraphCurve("y=\\sqrt{-x^2-1}")))
        assertEquals(undefined, GraphPlot.fitted(undefined))
    }

    @Test
    fun legendsAreShortPlainWords() {
        assertEquals("y = 1/x", GraphText.plain("y=\\frac{1}{x}"))
        assertEquals("y = (x+1)/2", GraphText.plain("y=\\frac{x+1}{2}"))
        assertEquals("y = x²", GraphText.plain("\\[y=x^2\\]"))
        assertEquals("y = x^(n+1)", GraphText.plain("y=x^{n+1}"))
        assertEquals("y = sin x", GraphText.plain("y=\\sin x"))
        assertEquals("y = √x", GraphText.plain("y=\\sqrt{x}"))
        assertEquals("y = √(x+1)", GraphText.plain("y=\\sqrt{x+1}"))
        assertEquals("y = x^(1/3)", GraphText.plain("y=\\sqrt[3]{x}"))
        assertEquals("f(ω) = 2πω", GraphText.plain("f(\\omega)=2\\pi\\omega"))
        assertEquals("v = v_0+at", GraphText.plain("v=v_{0}+at"))
        assertEquals("y = (x)", GraphText.plain("y=\\left(x\\right)"))
        assertTrue(GraphText.plain("y=" + "x+".repeat(200) + "x").length <= 60)
        // Never fails on anything.
        GraphText.plain("\\frac{{{")
        GraphText.plain("\\")
        GraphText.plain("{".repeat(5000))
        GraphText.plain("^")
    }
}
