package io.github.iamthebarrow.nibtexa.core.model.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Formatting over text (Phase 16): toggling, setting, clearing, following edits and storage. */
class TextMarksTest {
    private val text = "the quick brown fox"

    private fun bold(s: Int, e: Int) = TextMark(s, e, MarkStyle.Bold)

    @Test
    fun togglingAddsThenRemoves() {
        val on = TextMarks.toggle(emptyList(), 4, 9, MarkStyle.Bold, text.length)
        assertEquals(listOf(bold(4, 9)), on)
        assertTrue(TextMarks.covers(on, 4, 9, MarkStyle.Bold))
        assertEquals(emptyList<TextMark>(), TextMarks.toggle(on, 4, 9, MarkStyle.Bold, text.length))
    }

    @Test
    fun togglingPartOfAMarkSplitsIt() {
        val marks = TextMarks.toggle(listOf(bold(0, 15)), 4, 9, MarkStyle.Bold, text.length)
        assertEquals(listOf(bold(0, 4), bold(9, 15)), marks)
    }

    @Test
    fun aPartlyBoldRangeBecomesAllBold() {
        val marks = TextMarks.toggle(listOf(bold(4, 6)), 0, 9, MarkStyle.Bold, text.length)
        assertEquals(listOf(bold(0, 9)), marks)
    }

    @Test
    fun touchingMarksOfOneStyleMerge() {
        assertEquals(listOf(bold(0, 9)), TextMarks.normalize(listOf(bold(0, 4), bold(4, 9)), text.length))
    }

    @Test
    fun aSizeReplacesTheSizeUnderIt() {
        val large = TextMarks.set(emptyList(), 0, 15, TextMarks.SIZE, MarkStyle.Size(TextSize.LARGE), text.length)
        val marks = TextMarks.set(large, 4, 9, TextMarks.SIZE, MarkStyle.Size(TextSize.HUGE), text.length)
        assertEquals(
            listOf(TextMark(0, 4, MarkStyle.Size(TextSize.LARGE)), TextMark(4, 9, MarkStyle.Size(TextSize.HUGE)), TextMark(9, 15, MarkStyle.Size(TextSize.LARGE))),
            marks,
        )
        // Null takes the size off.
        assertEquals(emptyList<TextMark>(), TextMarks.set(marks, 0, 15, TextMarks.SIZE, null, text.length))
    }

    @Test
    fun aColourDoesNotTouchBold() {
        val marks = TextMarks.set(listOf(bold(0, 9)), 0, 9, TextMarks.COLOR, MarkStyle.Color(TextColor.RED), text.length)
        assertEquals(2, marks.size)
        assertTrue(TextMarks.covers(marks, 0, 9, MarkStyle.Bold))
    }

    @Test
    fun clearingRemovesFormattingAndFootnotesEndingThere() {
        val note = TextMark(4, 9, MarkStyle.Footnote("a note"))
        val elsewhere = TextMark(10, 15, MarkStyle.Footnote("kept"))
        val marks = TextMarks.clear(listOf(bold(0, 15), note, elsewhere), 0, 9, text.length)
        assertEquals(listOf(bold(9, 15), elsewhere), marks)
    }

    @Test
    fun typingAfterAMarkedWordShiftsTheMarkAfterIt() {
        // "the quick" -> "the very quick": bold "quick" moves along.
        val marks = TextMarks.follow(listOf(bold(4, 9)), "the quick", "the very quick")
        assertEquals(listOf(bold(9, 14)), marks)
    }

    @Test
    fun typingAtTheEndOfABoldWordContinuesIt() {
        assertEquals(listOf(bold(4, 11)), TextMarks.follow(listOf(bold(4, 9)), "the quick", "the quickly"))
    }

    @Test
    fun typingAtTheEndOfALinkDoesNotExtendIt() {
        val link = TextMark(4, 9, MarkStyle.Link("https://example.org"))
        assertEquals(listOf(link), TextMarks.follow(listOf(link), "the quick", "the quickly"))
    }

    @Test
    fun deletingInsideAMarkShrinksItAndDeletingItAllRemovesIt() {
        assertEquals(listOf(bold(4, 7)), TextMarks.follow(listOf(bold(4, 9)), "the quick fox", "the qck fox"))
        assertEquals(emptyList<TextMark>(), TextMarks.follow(listOf(bold(4, 9)), "the quick fox", "the  fox"))
    }

    @Test
    fun replacingAMarkedWordKeepsItsFormatting() {
        assertEquals(listOf(bold(4, 8)), TextMarks.follow(listOf(bold(4, 9)), "the quick fox", "the slow fox"))
    }

    @Test
    fun onlyWebAndMailLinksAreSafe() {
        assertTrue(TextMarks.isSafeUrl("https://example.org/a?b=1&c=2#d"))
        assertTrue(TextMarks.isSafeUrl("mailto:someone@example.org"))
        assertFalse(TextMarks.isSafeUrl("javascript:alert(1)"))
        assertFalse(TextMarks.isSafeUrl("file:///etc/passwd"))
        assertFalse(TextMarks.isSafeUrl("https://example.org/}\\input{x}"))
        assertFalse(TextMarks.isSafeUrl("https://exa mple.org"))
    }

    @Test
    fun marksSurviveStorage() {
        val marks = listOf(
            bold(0, 3),
            TextMark(4, 9, MarkStyle.Italic),
            TextMark(4, 9, MarkStyle.Emphasis),
            TextMark(4, 9, MarkStyle.Underline),
            TextMark(4, 9, MarkStyle.Typewriter),
            TextMark(10, 15, MarkStyle.Size(TextSize.TINY)),
            TextMark(10, 15, MarkStyle.Color(TextColor.MAGENTA)),
            TextMark(16, 19, MarkStyle.Link("https://example.org")),
            TextMark(16, 19, MarkStyle.Footnote("see \"here\"")),
        )
        assertEquals(marks, TextMarks.decode(TextMarks.encode(marks)))
        assertEquals(null, TextMarks.encode(emptyList()))
        assertEquals(emptyList<TextMark>(), TextMarks.decode("not json"))
        // An unsafe link read from storage is dropped.
        assertEquals(emptyList<TextMark>(), TextMarks.decode("""[{"s":0,"e":3,"t":"link","v":"javascript:x"}]"""))
    }
}
