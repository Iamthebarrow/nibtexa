package io.github.iamthebarrow.nibtexa.core.model.notebook

import io.github.iamthebarrow.nibtexa.core.model.InkDocument
import io.github.iamthebarrow.nibtexa.core.model.serialization.BookletJson
import io.github.iamthebarrow.nibtexa.core.model.serialization.GraphJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A graph as stored, edited and undone (Phase 25). */
class GraphTest {
    private val box = Bounds(10f, 10f, 410f, 310f)

    /** A page with a typed equation r1 and a graph of it, r2. */
    private fun editor(): PageEditor {
        val typed = Region("r1", RegionRole.MATH, emptySet(), Bounds(0f, 0f, 100f, 40f), 1, status = RecognitionStatus.CONFIRMED, confirmedLatex = "y=x", typed = true)
        val editor = PageEditor(Page("p1", InkDocument.EMPTY, listOf(typed), nextRegionNumber = 2))
        editor.addGraph(Graph(listOf(GraphCurve("y=x", source = "r1"))), box)
        return editor
    }

    private fun PageEditor.graph() = page.region("r2")!!.graph!!

    @Test
    fun aGraphIsATypedFigureThatExportsAndUndoes() {
        val editor = editor()
        val region = editor.page.region("r2")!!
        assertEquals(RegionRole.FIGURE, region.role)
        assertTrue(region.typed)
        assertTrue(editor.page.isExportable(region))
        editor.undo()
        assertNull(editor.page.region("r2"))
        editor.redo()
        assertEquals("y=x", editor.graph().curves.single().latex)
    }

    @Test
    fun changesAreEachOneUndo() {
        val editor = editor()
        val zoomed = editor.graph().withRange(-1.0, 1.0, -2.0, 2.0)
        assertTrue(editor.setGraph("r2", zoomed))
        assertEquals(1.0, editor.graph().xMax, 0.0)
        assertFalse("nothing changed", editor.setGraph("r2", zoomed))
        editor.undo()
        assertEquals(Graph.DEFAULT_RANGE, editor.graph().xMax, 0.0)
        editor.redo()
        assertEquals(1.0, editor.graph().xMax, 0.0)
        assertFalse("not a graph", editor.setGraph("r1", zoomed))
    }

    @Test
    fun correctingTheEquationUpdatesTheGraphInTheSameUndo() {
        val editor = editor()
        assertTrue(editor.confirm("r1", "y=x^2"))
        assertEquals("y=x^2", editor.graph().curves.single().latex)
        editor.undo()
        assertEquals("y=x", editor.graph().curves.single().latex)
        assertEquals("y=x", editor.page.region("r1")!!.confirmedLatex)
        editor.redo()
        assertEquals("y=x^2", editor.graph().curves.single().latex)
    }

    @Test
    fun onlyTheCurveFromThatEquationFollows() {
        val editor = editor()
        val other = GraphCurve("y=2x", color = 1)
        editor.setGraph("r2", editor.graph().copy(curves = editor.graph().curves + other))
        editor.confirm("r1", "y=3")
        assertEquals(listOf("y=3", "y=2x"), editor.graph().curves.map { it.latex })
    }

    @Test
    fun deletingTheEquationKeepsTheCurveAndUndoRelinksIt() {
        val editor = editor()
        editor.deleteRegion("r1")
        assertEquals("y=x", editor.graph().curves.single().latex)
        editor.undo()
        editor.confirm("r1", "y=x+1")
        assertEquals("y=x+1", editor.graph().curves.single().latex)
    }

    @Test
    fun movingAndResizingWorkAsForAPicture() {
        val editor = editor()
        assertTrue(editor.moveSelection(emptySet(), setOf("r2"), 5f, 7f))
        assertEquals(15f, editor.page.region("r2")!!.bounds.left, 0f)
        assertEquals(17f, editor.page.region("r2")!!.bounds.top, 0f)
        assertTrue(editor.setBounds("r2", Bounds(0f, 0f, 200f, 150f)))
        assertEquals(200f, editor.page.region("r2")!!.bounds.right, 0f)
    }

    @Test
    fun aDragMovesTheViewWithTheFinger() {
        val g = Graph(emptyList())
        // A quarter of the width to the right: what was at the left quarter is now at the middle.
        val moved = g.moved(0.25, 0.5, 0.5, 0.5, 1.0)
        assertEquals(-15.0, moved.xMin, 1e-9)
        assertEquals(5.0, moved.xMax, 1e-9)
        assertEquals(-10.0, moved.yMin, 1e-9)
        // Down moves it down: a higher value now sits at the middle.
        val down = g.moved(0.5, 0.25, 0.5, 0.5, 1.0)
        assertEquals(15.0, down.yMax, 1e-9)
    }

    @Test
    fun aPinchKeepsThePointUnderTheFingers() {
        val g = Graph(emptyList())
        val zoomed = g.moved(0.75, 0.25, 0.75, 0.25, 2.0)
        assertEquals(10.0, zoomed.xMax - zoomed.xMin, 1e-9)
        assertEquals(5.0, zoomed.xMin + 0.75 * (zoomed.xMax - zoomed.xMin), 1e-9)
        assertEquals(5.0, zoomed.yMax - 0.25 * (zoomed.yMax - zoomed.yMin), 1e-9)
    }

    @Test
    fun theViewStaysWithinLimits() {
        val g = Graph(emptyList())
        assertEquals(g, g.withRange(1.0, 1.0, 0.0, 1.0))
        assertEquals(g, g.withRange(2.0, 1.0, 0.0, 1.0))
        assertEquals(g, g.withRange(Double.NaN, 1.0, 0.0, 1.0))
        assertEquals(g, g.withRange(0.0, Double.POSITIVE_INFINITY, 0.0, 1.0))
        val far = g.moved(0.5, 0.5, 0.5, 0.5, 1e20)
        assertTrue(far.xMax - far.xMin >= 1e-6)
        val out = g.moved(0.5, 0.5, 0.5, 0.5, 1e-20)
        assertTrue(out.xMax - out.xMin <= 1e9)
    }

    @Test
    fun colorsAreGivenOutInTurn() {
        assertEquals(0, Graph(emptyList()).nextColor())
        assertEquals(1, Graph(listOf(GraphCurve("x"))).nextColor())
        assertEquals(0, Graph(listOf(GraphCurve("x", color = 1))).nextColor())
    }

    @Test
    fun itRoundTripsThroughJson() {
        val graph = Graph(
            listOf(GraphCurve("y=kx", "r1", 2, hidden = true, input = "x"), GraphCurve("\\frac{1}{x}")),
            -1.5, 2.5, -3.0, 4.0, true, mapOf("k" to 2.5, "a" to -1.0), "Title", "Time", "Speed",
        )
        assertEquals(graph, GraphJson.decode(GraphJson.encode(graph)))
        // Deterministic, whatever order the constants were set in.
        assertEquals(GraphJson.encode(graph), GraphJson.encode(graph.copy(constants = mapOf("a" to -1.0, "k" to 2.5))))
    }

    @Test
    fun aDamagedOrHostileGraphIsKeptWithinLimits() {
        assertNull(GraphJson.decode("not json"))
        assertNull(GraphJson.decode("""{"curves":[],"x":[1],"y":[1,2]}"""))
        val flipped = GraphJson.decode("""{"curves":[],"x":[5,1],"y":[0,1]}""")!!
        assertEquals(Graph.DEFAULT_RANGE, flipped.xMax, 0.0)
        val curves = (1..50).joinToString(",") { """{"latex":"x","color":${it * 1000}}""" }
        val many = GraphJson.decode("""{"curves":[$curves],"x":[0,1],"y":[0,1]}""")!!
        assertEquals(Graph.MAX_CURVES, many.curves.size)
        assertTrue(many.curves.all { it.color in GraphColors.RGB.indices })
    }

    @Test
    fun itIsInTheBookletDocument() {
        val editor = editor()
        val booklet = Booklet("b1", "B", listOf(editor.page))
        val decoded = BookletJson.decode(BookletJson.encode(booklet))
        assertEquals(editor.graph(), decoded.pages.single().region("r2")!!.graph)
    }
}
