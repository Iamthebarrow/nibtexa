package io.github.iamthebarrow.nibtexa.core.export

import io.github.iamthebarrow.nibtexa.core.model.CapturedStroke
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageEditor
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lists in handwriting (Phase 15): what counts as an item, and how runs of items export. */
class ListsTest {

    @Test
    fun bulletsAndNumbersMakeItemsAndOrdinaryTextDoesNot() {
        assertEquals(Lists.Item(Lists.Kind.BULLETED, "poles", 2), Lists.item("- poles"))
        assertEquals(Lists.Item(Lists.Kind.BULLETED, "zeros", 2), Lists.item("• zeros"))
        assertEquals(Lists.Item(Lists.Kind.BULLETED, "zeros", 1), Lists.item("•zeros"))
        assertEquals(Lists.Item(Lists.Kind.BULLETED, "gain", 4), Lists.item("  – gain "))
        assertEquals(Lists.Item(Lists.Kind.NUMBERED, "sample", 3), Lists.item("1. sample"))
        assertEquals(Lists.Item(Lists.Kind.NUMBERED, "filter", 4), Lists.item("12) filter"))
        assertNull(Lists.item("-5 dB at the cutoff")) // a minus sign, not a bullet
        assertNull(Lists.item("2.5 seconds"))
        assertNull(Lists.item("The DFT"))
        assertNull(Lists.item("- "))
    }

    private fun line(y: Float) = CapturedStroke(ToolType.STYLUS, 0, listOf(InkPoint(0f, y, 0), InkPoint(10f, y + 10f, 8)), Pen.TEXT)

    /** Text regions top to bottom, each confirmed as [texts] says. */
    private fun page(vararg texts: String): Page {
        val e = PageEditor(Page("p-1"))
        texts.forEachIndexed { i, text ->
            e.addStroke(line(i * 50f))
            e.confirm(e.createRegion(setOf(i + 1L), RegionRole.TEXT)!!, text)
        }
        return e.page
    }

    private fun tex(page: Page) = (LatexExporter.export(Booklet("b", "T", listOf(page))) { _, _ -> ByteArray(0) } as ExportResult.Success)
        .files.single { it.path.endsWith("p-1.tex") }.content

    @Test
    fun consecutiveItemsMakeOneListAndOtherTextEndsIt() {
        val tex = tex(page("Steps", "1. sample", "2. window", "3. transform", "- fast", "- exact", "Done."))

        assertTrue(tex, "\\begin{enumerate}\n\\item sample\n\n% region r3\n\\item window\n\n% region r4\n\\item transform\n\\end{enumerate}\n" in tex)
        assertTrue(tex, "\\begin{itemize}\n\\item fast\n\n% region r6\n\\item exact\n\\end{itemize}\n" in tex)
        assertTrue(tex, tex.indexOf("Steps") < tex.indexOf("\\begin{enumerate}"))
        assertTrue(tex, tex.indexOf("\\end{itemize}") < tex.indexOf("Done."))
    }

    @Test
    fun aListAtTheEndOfAPageIsClosed() {
        val tex = tex(page("- one", "- two"))
        assertTrue(tex, tex.trimEnd().endsWith("\\end{itemize}"))
    }

    @Test
    fun thePdfNumbersEachRunFromOne() {
        val blocks = (PdfLayout.blocks(Booklet("b", "T", listOf(page("3. a", "7. b", "Then", "5. c")))) as PdfLayout.Result.Blocks).blocks
        assertEquals(
            listOf(PdfBlock.ListItem("1.", "a"), PdfBlock.ListItem("2.", "b"), PdfBlock.Paragraph("Then"), PdfBlock.ListItem("1.", "c")),
            blocks.drop(1),
        )
    }
}
