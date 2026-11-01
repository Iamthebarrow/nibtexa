package io.github.iamthebarrow.nibtexa.core.export

import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.Diagram
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramArrow
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramFacing
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramLabel
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramLine
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramLineType
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramPoint
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramShape
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramShapeType
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramSymbol
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.core.model.serialization.BookletJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiagramExportTest {
    private fun p(x: Float, y: Float) = DiagramPoint(x, y)

    /** A feedback loop: a summing point, two blocks, an output with a take-off, and a return path. */
    private val blockDiagram = Diagram(
        width = 620f, height = 240f, nextId = 40,
        shapes = listOf(
            DiagramShape(1, DiagramShapeType.CROSS, 110f, 70f, 40f, 40f),
            DiagramShape(2, DiagramShapeType.BOX, 250f, 70f, 110f, 70f),
            DiagramShape(3, DiagramShapeType.BOX, 420f, 70f, 110f, 70f),
            DiagramShape(4, DiagramShapeType.DOT, 530f, 70f, 12f, 12f, onLine = 12, along = 0.5f),
        ),
        lines = listOf(
            DiagramLine(10, listOf(p(10f, 70f), p(110f, 70f)), to = 1, arrowEnd = true),
            DiagramLine(11, listOf(p(110f, 70f), p(250f, 70f)), from = 1, to = 2, arrowEnd = true),
            DiagramLine(13, listOf(p(250f, 70f), p(420f, 70f)), from = 2, to = 3, arrowEnd = true),
            DiagramLine(12, listOf(p(420f, 70f), p(610f, 70f)), from = 3, arrowEnd = true),
            DiagramLine(14, listOf(p(530f, 70f), p(530f, 200f), p(110f, 200f), p(110f, 70f)), from = 4, to = 1, arrowEnd = true),
        ),
        labels = listOf(
            DiagramLabel(20, 250f, 70f, latex = "\\frac{K}{s+2}", shape = 2),
            DiagramLabel(21, 420f, 70f, text = "Plant & 100%", shape = 3),
            DiagramLabel(22, 40f, 50f, latex = "R(s)"),
            DiagramLabel(23, 80f, 45f, latex = "+", shape = 1),
            DiagramLabel(24, 95f, 105f, latex = "-", shape = 1),
            DiagramLabel(25, 580f, 50f, latex = "Y(s)"),
            DiagramLabel(26, 300f, 225f, ink = listOf(listOf(p(290f, 225f), p(300f, 220f), p(310f, 225f)))),
        ),
    )

    /** A signal flow graph: nodes, gains on arcs with arrows along them, and a loop. */
    private val flowGraph = Diagram(
        width = 500f, height = 220f, nextId = 40,
        shapes = listOf(
            DiagramShape(1, DiagramShapeType.DOT, 60f, 120f, 12f, 12f),
            DiagramShape(2, DiagramShapeType.DOT, 250f, 120f, 12f, 12f),
            DiagramShape(3, DiagramShapeType.DOT, 440f, 120f, 12f, 12f),
        ),
        lines = listOf(
            DiagramLine(10, listOf(p(60f, 120f), p(250f, 120f)), from = 1, to = 2, arrows = listOf(DiagramArrow(0.5f))),
            DiagramLine(11, listOf(p(250f, 120f), p(440f, 120f)), from = 2, to = 3, arrows = listOf(DiagramArrow(0.5f))),
            DiagramLine(12, listOf(p(440f, 120f), p(250f, 40f), p(60f, 120f)), DiagramLineType.ARC, from = 3, to = 1, arrows = listOf(DiagramArrow(0.5f))),
        ),
        labels = listOf(DiagramLabel(20, 155f, 140f, latex = "G_1"), DiagramLabel(21, 345f, 140f, latex = "G_2"), DiagramLabel(22, 250f, 20f, latex = "-H")),
    )

    /** Every circuit symbol, in a loop with a ground and an op-amp. */
    private val circuit = Diagram(
        width = 620f, height = 300f, nextId = 40,
        shapes = listOf(
            DiagramShape(1, DiagramShapeType.GROUND, 40f, 270f, 30f, 30f),
            DiagramShape(2, DiagramShapeType.OP_AMP, 540f, 150f, 90f, 80f, DiagramFacing.RIGHT),
        ),
        lines = DiagramSymbol.entries.drop(1).mapIndexed { i, s -> DiagramLine(10 + i, listOf(p(20f + i % 2 * 240f, 30f + i / 2 * 60f), p(230f + i % 2 * 240f, 30f + i / 2 * 60f)), symbol = s) } +
            DiagramLine(30, listOf(p(40f, 200f), p(40f, 270f)), to = 1, dashed = true),
        labels = listOf(DiagramLabel(20, 125f, 12f, latex = "R_1")),
    )

    private fun regionOf(id: String, diagram: Diagram, top: Float) = Region(id, RegionRole.FIGURE, emptySet(), Bounds(0f, top, diagram.width, top + diagram.height), id.drop(1).toInt(), typed = true, diagram = diagram)

    private val booklet = Booklet(
        "b1", "Diagrams",
        listOf(Page("p1", regions = listOf(regionOf("r1", blockDiagram, 0f), regionOf("r2", flowGraph, 300f), regionOf("r3", circuit, 600f).copy(caption = "Every symbol")))),
    )

    private val noFigures = FigureRenderer { _, _ -> error("A diagram has no file of its own") }

    private fun exported(): ExportResult.Success = LatexExporter.export(booklet, noFigures) as ExportResult.Success

    private fun pageTex() = exported().files.first { it.path.endsWith("pages/p1.tex") }.content

    @Test
    fun aDiagramIsVectorTikzWithItsLabelsTypesetAndNoFile() {
        val files = exported().files
        assertFalse(files.any { it.path.endsWith(".png") })
        val main = files.first { it.path == "main.tex" }.content
        assertTrue(main.contains("\\usepackage{tikz}"))
        val tex = pageTex()
        assertEquals(3, Regex("""\\begin\{tikzpicture}""").findAll(tex).count())
        assertTrue(tex, tex.contains("\\frac{K}{s+2}\$}};"))
        assertTrue(tex.contains("\\node at (250.0,70.0) {\\nibinside{"))
        assertTrue(tex.contains("\\node at (40.0,50.0) {\$\\displaystyle R(s)\$};"))
        assertTrue(main.contains("\\newcommand{\\nibinside}"))
        assertTrue(tex.contains("Plant \\& 100\\%"))
        assertTrue(tex.contains("\\fill "))
        assertTrue(tex.contains("dash pattern"))
        assertFalse(tex.contains("includegraphics"))
        assertFalse(tex.contains("circuitikz"))
    }

    @Test
    fun unsafeLabelsAreEscapedNotRun() {
        val bad = blockDiagram.copy(labels = listOf(DiagramLabel(1, 1f, 1f, latex = "\\input{secret}"), DiagramLabel(2, 2f, 2f, latex = "a\$b")))
        val tex = DiagramLatex.picture(bad, 3f)
        assertFalse(tex.contains("\\input{"))
        assertTrue(tex.contains("\\textbackslash{}input"))
        assertTrue(tex.contains("a\\\$b"))
    }

    @Test
    fun aDiagramHasNoInkToWaitFor() {
        assertTrue(LatexExporter.validate(booklet).isEmpty())
    }

    @Test
    fun widthFollowsThePage() {
        assertTrue(DiagramLatex.picture(blockDiagram, 3.1f).contains("x=${"%.5f".format(java.util.Locale.ROOT, 3.1f / 620f)}in"))
    }

    @Test
    fun aBookletDocumentKeepsItsDiagrams() {
        val back = BookletJson.decode(BookletJson.encode(booklet))
        assertEquals(booklet.pages.single().regions.map { it.diagram }, back.pages.single().regions.map { it.diagram })
    }

    /** Written out for compiling by hand with the pinned image, when asked for with NIBTEXA_DIAGRAM_SAMPLE=<dir>. */
    @Test
    fun writesASampleWhenAsked() {
        val dir = System.getenv("NIBTEXA_DIAGRAM_SAMPLE") ?: return
        for (file in exported().files) File(dir, file.path).apply { parentFile?.mkdirs() }.writeBytes(file.bytes)
    }
}
