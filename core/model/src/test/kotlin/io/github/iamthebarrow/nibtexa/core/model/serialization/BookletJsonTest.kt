package io.github.iamthebarrow.nibtexa.core.model.serialization

import io.github.iamthebarrow.nibtexa.core.model.InkDocument
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.Paper
import io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BookletJsonTest {

    private val booklet = Booklet(
        id = "b-1",
        title = "Signals \"week 3\"",
        pages = listOf(
            Page(
                id = "p-1",
                ink = InkDocument(
                    strokes = listOf(
                        Stroke(1, ToolType.STYLUS, 1000, listOf(InkPoint(1f, 2f, 0, pressure = 0.5f), InkPoint(3f, 4f, 8)), Pen.MATH),
                        Stroke(2, ToolType.STYLUS, 2000, listOf(InkPoint(10f, 20f, 0)), Pen.DRAWING),
                    ),
                    nextStrokeId = 3,
                ),
                regions = listOf(
                    Region(
                        id = "r-1", role = RegionRole.MATH, strokeIds = setOf(1), bounds = Bounds(1f, 2f, 3f, 4f), readingOrder = 0,
                        status = RecognitionStatus.CONFIRMED, sourceStrokeIds = setOf(1), recognizedLatex = "x^2", confirmedLatex = "x^{2}",
                    ),
                    Region(
                        id = "r-2", role = RegionRole.FIGURE, strokeIds = setOf(2), bounds = Bounds(10f, 20f, 10f, 20f), readingOrder = 1,
                        caption = "Block diagram",
                    ),
                ),
                nextRegionNumber = 3,
                paper = Paper.GRID,
            ),
            Page(id = "p-2"),
        ),
    )

    @Test
    fun roundTripsEverything() {
        assertEquals(booklet, BookletJson.decode(BookletJson.encode(booklet)))
    }

    @Test
    fun isDeterministic() {
        val reordered = booklet.copy(
            pages = booklet.pages.map { page ->
                page.copy(regions = page.regions.map { it.copy(strokeIds = it.strokeIds.reversed().toSet()) })
            },
        )
        assertEquals(BookletJson.encode(booklet), BookletJson.encode(reordered))
        assertEquals(BookletJson.encode(booklet), BookletJson.encode(BookletJson.decode(BookletJson.encode(booklet))))
    }

    @Test
    fun rejectsOtherDocuments() {
        assertThrows(InkFormatException::class.java) { BookletJson.decode(InkJson.encode(InkDocument.EMPTY)) }
        assertThrows(InkFormatException::class.java) { BookletJson.decode("{") }
        val text = BookletJson.encode(booklet)
        assertThrows(InkFormatException::class.java) { BookletJson.decode(text.replace("\"version\": 1", "\"version\": 9")) }
        assertThrows(InkFormatException::class.java) { BookletJson.decode(text.replace("\"GRID\"", "\"WAVY\"")) }
    }
}
