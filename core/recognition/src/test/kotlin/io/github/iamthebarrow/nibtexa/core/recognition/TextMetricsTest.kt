package io.github.iamthebarrow.nibtexa.core.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TextMetricsTest {

    @Test
    fun matchedWordsFollowOrderAndCase() {
        assertEquals(4, TextMetrics.matchedWords("a pole at s", "a pole at s"))
        assertEquals(3, TextMetrics.matchedWords("a pole at s", "a hole at s"))
        // Case and punctuation count: "Pole" and "s." are not "pole" and "s".
        assertEquals(2, TextMetrics.matchedWords("a pole at s", "a Pole at s."))
        // An extra word costs nothing; a missing one does.
        assertEquals(3, TextMetrics.matchedWords("a pole at", "a big pole at"))
        assertEquals(2, TextMetrics.matchedWords("a pole at", "a at"))
        assertEquals(0, TextMetrics.matchedWords("a pole", ""))
    }

    @Test
    fun charEditsCountInsertionsDeletionsAndSubstitutions() {
        assertEquals(0, TextMetrics.charEdits("s = -2", "s = -2"))
        assertEquals(1, TextMetrics.charEdits("s = -2", "s = 2"))
        assertEquals(1, TextMetrics.charEdits("pole", "hole"))
        assertEquals(2, TextMetrics.charEdits("pole", "poles!"))
        assertEquals(4, TextMetrics.charEdits("pole", ""))
    }

    @Test
    fun summaryTotalsLines() {
        val s = TextMetrics.summarize(listOf("a pole" to "a pole", "at s = -2" to "at s = 2"))
        assertEquals(2, s.lines)
        assertEquals(6, s.referenceWords)
        assertEquals(5, s.matchedWords)
        assertEquals(15, s.referenceChars)
        assertEquals(1, s.charEdits)
        assertEquals(1, s.exactLines)
        assertEquals(5.0 / 6, s.wordAccuracy, 1e-9)
        assertEquals(1.0 / 15, s.charErrorRate, 1e-9)
    }

    @Test
    fun percentileIsNearestRank() {
        val values = (1L..20L).toList().shuffled(java.util.Random(7))
        assertEquals(10L, TextMetrics.percentile(values, 0.5))
        assertEquals(19L, TextMetrics.percentile(values, 0.95))
        assertEquals(20L, TextMetrics.percentile(values, 1.0))
        assertEquals(1L, TextMetrics.percentile(values, 0.0))
        assertThrows(IllegalArgumentException::class.java) { TextMetrics.percentile(emptyList(), 0.5) }
    }
}
