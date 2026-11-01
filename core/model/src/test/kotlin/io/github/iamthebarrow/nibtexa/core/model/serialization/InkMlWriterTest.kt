package io.github.iamthebarrow.nibtexa.core.model.serialization

import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class InkMlWriterTest {

    private fun parse(xml: String): Document = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    private fun Document.elements(name: String): List<Element> {
        val nodes = getElementsByTagNameNS(InkMlWriter.NAMESPACE, name)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private val stylus = Stroke(1, ToolType.STYLUS, 1000, listOf(InkPoint(1f, 2f, 0, 0.5f), InkPoint(3.456f, 4f, 10, 0.75f)))
    private val later = Stroke(2, ToolType.STYLUS, 1500, listOf(InkPoint(5f, 6f, 0, 1f)))
    private val mouse = Stroke(3, ToolType.MOUSE, 2000, listOf(InkPoint(7f, 8f, 0)))

    @Test
    fun producesWellFormedInkMlInTheW3cNamespace() {
        val doc = parse(InkMlWriter.write(listOf(stylus, later)))

        assertEquals("ink", doc.documentElement.localName)
        assertEquals(InkMlWriter.NAMESPACE, doc.documentElement.namespaceURI)
        assertEquals(2, doc.elements("trace").size)
    }

    @Test
    fun includesPressureChannelOnlyWhenEveryPointHasIt() {
        val withF = parse(InkMlWriter.write(listOf(stylus, later))).elements("channel").map { it.getAttribute("name") }
        val withoutF = parse(InkMlWriter.write(listOf(stylus, mouse))).elements("channel").map { it.getAttribute("name") }

        assertEquals(listOf("X", "Y", "T", "F"), withF)
        assertEquals(listOf("X", "Y", "T"), withoutF)
    }

    @Test
    fun traceTimesAreRelativeToFirstStrokeOfSample() {
        val traces = parse(InkMlWriter.write(listOf(stylus, later))).elements("trace")

        assertEquals("1.00 2.00 0 0.50, 3.46 4.00 10 0.75", traces[0].textContent)
        assertEquals("5.00 6.00 500 1.00", traces[1].textContent)
        assertEquals(listOf("0", "1"), traces.map { it.getAttribute("id") })
    }

    @Test
    fun annotationsAreEscaped() {
        val latex = "\$a < b & c > \"d\"\$"
        val doc = parse(InkMlWriter.write(listOf(mouse), listOf("truth" to latex)))

        val annotation = doc.elements("annotation").single()
        assertEquals("truth", annotation.getAttribute("type"))
        assertEquals(latex, annotation.textContent)
    }

    @Test
    fun rejectsEmptyStrokeList() {
        assertThrows(IllegalArgumentException::class.java) { InkMlWriter.write(emptyList()) }
    }
}
