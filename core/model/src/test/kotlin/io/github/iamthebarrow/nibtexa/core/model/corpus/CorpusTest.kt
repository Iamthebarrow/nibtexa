package io.github.iamthebarrow.nibtexa.core.model.corpus

import io.github.iamthebarrow.nibtexa.core.model.InkDocument
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import io.github.iamthebarrow.nibtexa.core.model.serialization.InkJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CorpusTest {

    @Test
    fun parsesPromptsSkippingCommentsAndBlankLines() {
        val text = "# id\tcategory\tlatex\n\nfrac-1\tfractions\t\\frac{a}{b}\r\nsup-1\tscripts\tx^{2}\n"

        val prompts = CorpusPrompts.parse(text)

        assertEquals(
            listOf(CorpusPrompt("frac-1", "fractions", "\\frac{a}{b}"), CorpusPrompt("sup-1", "scripts", "x^{2}")),
            prompts,
        )
    }

    @Test
    fun rejectsMalformedPromptLines() {
        assertThrows(IllegalArgumentException::class.java) { CorpusPrompts.parse("only\ttwo") }
        assertThrows(IllegalArgumentException::class.java) { CorpusPrompts.parse("Bad Id\tcat\tx") }
        assertThrows(IllegalArgumentException::class.java) { CorpusPrompts.parse("a\t\tx") }
        assertThrows(IllegalArgumentException::class.java) { CorpusPrompts.parse("a\tc\tx\na\tc\ty") }
    }

    @Test
    fun buildsSampleFilesWithTruthAnnotation() {
        val prompt = CorpusPrompt("frac-1", "fractions", "\\frac{a}{b}")
        val document = InkDocument(listOf(Stroke(1, ToolType.STYLUS, 0, listOf(InkPoint(0f, 0f, 0, 0.5f)))), 2)

        val files = CorpusSamples.build(prompt, document, "20261026T223000Z", "AVD NibTeXa_Tablet_API37")

        assertEquals("frac-1_20261026T223000Z", files.baseName)
        assertTrue(files.inkMl.contains("<annotation type=\"truth\">\$\\frac{a}{b}\$</annotation>"))
        assertTrue(files.inkMl.contains("<annotation type=\"category\">fractions</annotation>"))
        assertEquals(document, InkJson.decode(files.json))
    }

    @Test
    fun textSamplesKeepTheirTruthAsPlainText() {
        val prompt = CorpusPrompt("txt-tech-pole", "technical", "a pole at s = -2")
        val document = InkDocument(listOf(Stroke(1, ToolType.STYLUS, 0, listOf(InkPoint(0f, 0f, 0)))), 2)

        val files = CorpusSamples.build(prompt, document, "20261028T120000Z", "AVD", mathTruth = false)

        assertTrue(files.inkMl.contains("<annotation type=\"truth\">a pole at s = -2</annotation>"))
    }

    @Test
    fun rejectsEmptySampleAndUnsafeTimestamp() {
        val prompt = CorpusPrompt("a", "c", "x")
        val document = InkDocument(listOf(Stroke(1, ToolType.STYLUS, 0, listOf(InkPoint(0f, 0f, 0)))), 2)

        assertThrows(IllegalArgumentException::class.java) { CorpusSamples.build(prompt, InkDocument.EMPTY, "1", "d") }
        assertThrows(IllegalArgumentException::class.java) { CorpusSamples.build(prompt, document, "../x", "d") }
    }
}
