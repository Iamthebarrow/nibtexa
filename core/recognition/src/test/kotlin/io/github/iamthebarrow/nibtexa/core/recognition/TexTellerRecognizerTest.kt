package io.github.iamthebarrow.nibtexa.core.recognition

import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class TexTellerRecognizerTest {

    private val strokes = listOf(
        Stroke(1, ToolType.STYLUS, 0, listOf(InkPoint(0f, 0f, 0), InkPoint(40f, 40f, 8))),
        Stroke(2, ToolType.STYLUS, 0, listOf(InkPoint(50f, 20f, 0))),
    )

    /** Emits a fixed script of tokens and records what it was fed. */
    private class ScriptedModel(private val script: List<Long>) : TexTellerModel {
        val fed = mutableListOf<Long>()
        var pixels: FloatArray? = null
        var closed = false

        override fun start(pixels: FloatArray): TokenSession {
            this.pixels = pixels
            var i = 0
            return object : TokenSession {
                override fun next(token: Long): Long {
                    fed += token
                    return script[i++]
                }

                override fun close() {
                    closed = true
                }
            }
        }

        override fun close() = Unit
    }

    // A tiny vocabulary in GPT-2's byte alphabet: "Ġ" is a space.
    private val decoder = ByteLevelBpeDecoder(
        mapOf("<s>" to 0, "<pad>" to 1, "</s>" to 2, "x" to 5, "^" to 6, "{" to 7, "2" to 8, "}" to 9, "Ġ+" to 10, "Ġ1" to 11),
        setOf(0, 1, 2),
    )

    @Test
    fun pipelineRendersDecodesAndCleansUp() {
        val model = ScriptedModel(listOf(5, 6, 7, 8, 9, 10, 11, 2))
        val latex = TexTellerRecognizer(model, decoder).recognize(strokes)

        assertEquals("x^{2} + 1", latex)
        assertEquals(listOf(0L, 5, 6, 7, 8, 9, 10, 11), model.fed)
        assertEquals(448 * 448, model.pixels!!.size)
        assertTrue(model.closed)
    }

    @Test
    fun decodingStopsAtTheTokenLimit() {
        val model = ScriptedModel(List(10) { 5L })
        assertEquals("xxx", TexTellerRecognizer(model, decoder, maxTokens = 3).recognize(strokes))
    }

    @Test
    fun cancellationStopsDecodingAndClosesTheSession() {
        val model = ScriptedModel(List(10) { 5L })
        var calls = 0
        assertThrows(RecognitionCanceledException::class.java) {
            TexTellerRecognizer(model, decoder).recognize(strokes) { ++calls > 3 }
        }
        assertTrue(model.closed)
    }

    @Test
    fun emptyInkAndUnknownTokensFail() {
        assertThrows(RecognitionException::class.java) { TexTellerRecognizer(ScriptedModel(listOf(2)), decoder).recognize(emptyList()) }
        assertThrows(RecognitionException::class.java) { TexTellerRecognizer(ScriptedModel(listOf(99, 2)), decoder).recognize(strokes) }
    }

    @Test
    fun byteLevelDecodingHandlesMultiByteUtf8() {
        // "é" is UTF-8 C3 A9, which GPT-2's alphabet maps to "Ã" and "©".
        val d = ByteLevelBpeDecoder(mapOf("Ã©" to 5, "Ġ\\" to 6), emptySet())
        assertEquals("é \\", d.decode(listOf(5, 6)))
        assertEquals(256, ByteLevelBpeDecoder.UNICODE_TO_BYTE.size)
    }

    @Test
    fun cleanupStripsDelimitersAndCollapsesSpace() {
        assertEquals("a + b", LatexCleanup.clean("  \\[ a  +\n b \\]  "))
        assertEquals("x", LatexCleanup.clean("$\\displaystyle x$"))
        assertEquals("\\left( x \\right)", LatexCleanup.clean("\\left( x \\right)"))
    }

    @Test
    fun rendererMatchesTheHarnessGeometry() {
        val image = StrokeRenderer.render(strokes)
        // Height 40 canvas units scaled to 128, plus 16 px padding on each side; width 50 → 160.
        assertEquals(128 + 32, image.height)
        assertEquals(160 + 32, image.width)
        assertEquals(GrayImage.BLACK, image[16, 16])
        assertEquals(GrayImage.WHITE, image[0, 0])
        assertEquals(GrayImage.BLACK, image[16 + 160, 16 + 64]) // the dot
    }

    /**
     * The real TexTeller vocabulary is not in the repository. Set NIBTEXA_TEXTELLER_DIR to the
     * model folder (with vocab.json) to check the decoder against Hugging Face's output on
     * every corpus prompt.
     */
    @Test
    fun decodesLikeHuggingFaceOnTheCorpusPrompts() {
        val dir = System.getenv("NIBTEXA_TEXTELLER_DIR")?.let(::File)
        assumeTrue("NIBTEXA_TEXTELLER_DIR not set", dir != null && File(dir, "vocab.json").isFile)
        val real = ByteLevelBpeDecoder.fromVocabJson(File(dir, "vocab.json").readText())
        val cases = Json.parseToJsonElement(checkNotNull(javaClass.getResource("/texteller/decode.json")).readText()).jsonArray

        for (case in cases) {
            val ids = case.jsonObject["ids"]!!.jsonArray.map { it.jsonPrimitive.long }
            assertEquals(case.jsonObject["text"]!!.jsonPrimitive.content, real.decode(ids))
        }
        assertEquals(159, cases.size)
    }
}
