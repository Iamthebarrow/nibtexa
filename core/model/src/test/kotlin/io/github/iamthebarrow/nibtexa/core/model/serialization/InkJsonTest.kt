package io.github.iamthebarrow.nibtexa.core.model.serialization

import io.github.iamthebarrow.nibtexa.core.model.InkDocument
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InkJsonTest {

    private val document = InkDocument(
        strokes = listOf(
            Stroke(
                1, ToolType.STYLUS, 5000,
                listOf(InkPoint(1.5f, 2.25f, 0, 0.4f, 0.3f, 1.2f), InkPoint(3f, 4f, 7, 0.6f, 0.3f, 1.2f)),
            ),
            Stroke(3, ToolType.MOUSE, 6000, listOf(InkPoint(10f, 10f, 0)), Pen.DRAWING),
        ),
        nextStrokeId = 4,
    )

    @Test
    fun roundTripPreservesEverything() {
        assertEquals(document, InkJson.decode(InkJson.encode(document)))
    }

    @Test
    fun absentChannelsAreOmittedNotWrittenAsNull() {
        val text = InkJson.encode(document)

        assertFalse(text.contains("null"))
        assertTrue(text.contains("\"format\":\"nibtexa-ink\""))
        assertTrue(text.contains("\"version\":2"))
    }

    @Test
    fun emptyDocumentRoundTrips() {
        assertEquals(InkDocument.EMPTY, InkJson.decode(InkJson.encode(InkDocument.EMPTY)))
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val text = InkJson.encode(document).replace("\"version\":2", "\"version\":3")

        assertThrows(InkFormatException::class.java) { InkJson.decode(text) }
    }

    @Test
    fun rejectsWrongFormat() {
        val text = InkJson.encode(document).replace("nibtexa-ink", "other")

        assertThrows(InkFormatException::class.java) { InkJson.decode(text) }
    }

    @Test
    fun rejectsMalformedJson() {
        assertThrows(InkFormatException::class.java) { InkJson.decode("{not json") }
        assertThrows(InkFormatException::class.java) { InkJson.decode("""{"format":"nibtexa-ink"}""") }
    }

    @Test
    fun rejectsInconsistentIds() {
        val duplicate = InkJson.encode(document).replace("\"id\":3", "\"id\":1")
        val tooHigh = InkJson.encode(document).replace("\"nextStrokeId\":4", "\"nextStrokeId\":2")

        assertThrows(InkFormatException::class.java) { InkJson.decode(duplicate) }
        assertThrows(InkFormatException::class.java) { InkJson.decode(tooHigh) }
    }

    @Test
    fun rejectsStrokeWithoutPoints() {
        val text = """{"format":"nibtexa-ink","version":1,"nextStrokeId":2,"strokes":[{"id":1,"tool":"STYLUS","startTimeMillis":0,"points":[]}]}"""

        assertThrows(InkFormatException::class.java) { InkJson.decode(text) }
    }

    @Test
    fun versionOneDocumentsDecodeWithTheMathPen() {
        val text = """{"format":"nibtexa-ink","version":1,"nextStrokeId":2,"strokes":[{"id":1,"tool":"STYLUS","startTimeMillis":0,"points":[{"x":1,"y":2,"t":0}]}]}"""

        assertEquals(Pen.MATH, InkJson.decode(text).strokes.single().pen)
    }

    @Test
    fun versionTwoRequiresThePen() {
        val text = """{"format":"nibtexa-ink","version":2,"nextStrokeId":2,"strokes":[{"id":1,"tool":"STYLUS","startTimeMillis":0,"points":[{"x":1,"y":2,"t":0}]}]}"""

        assertThrows(InkFormatException::class.java) { InkJson.decode(text) }
    }

    @Test
    fun pointsRoundTripOnTheirOwn() {
        val points = document.strokes.first().points
        assertEquals(points, InkJson.decodePoints(InkJson.encodePoints(points)))
        assertThrows(InkFormatException::class.java) { InkJson.decodePoints("[]") }
        assertThrows(InkFormatException::class.java) { InkJson.decodePoints("{") }
    }
}
