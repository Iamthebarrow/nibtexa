package io.github.iamthebarrow.nibtexa.core.recognition

import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TextRecognizerTest {

    private val strokes = listOf(
        Stroke(1, ToolType.STYLUS, 100, listOf(InkPoint(0f, 0f, 0), InkPoint(10f, 20f, 8)), Pen.TEXT),
        Stroke(2, ToolType.STYLUS, 140, listOf(InkPoint(15f, 0f, 0)), Pen.TEXT),
    )

    /** Returns [result] and records what it was given. */
    private class FakeEngine(private val result: () -> List<String>) : TextEngine {
        var calls = 0
        var context: TextContext? = null
        override fun candidates(strokes: List<Stroke>, context: TextContext): List<String> {
            calls++
            this.context = context
            return result()
        }
    }

    @Test
    fun returnsTheBestCandidateCleanedUp() {
        val engine = FakeEngine { listOf("  the  transfer\nfunction ", "the transfer fraction") }
        assertEquals("the transfer function", EngineTextRecognizer(engine).recognize(strokes))
    }

    @Test
    fun skipsBlankCandidates() {
        val engine = FakeEngine { listOf("  ", "pole") }
        assertEquals("pole", EngineTextRecognizer(engine).recognize(strokes))
    }

    @Test
    fun nothingRecognizedIsAnError() {
        val e = assertThrows(RecognitionException::class.java) { EngineTextRecognizer(FakeEngine { emptyList() }).recognize(strokes) }
        assertEquals("No text recognized", e.message)
    }

    @Test
    fun emptyInkIsRejectedWithoutCallingTheEngine() {
        val engine = FakeEngine { listOf("x") }
        assertThrows(RecognitionException::class.java) { EngineTextRecognizer(engine).recognize(emptyList()) }
        assertEquals(0, engine.calls)
    }

    @Test
    fun cancelBeforeTheEngineRunsSkipsIt() {
        val engine = FakeEngine { listOf("x") }
        assertThrows(RecognitionCanceledException::class.java) { EngineTextRecognizer(engine).recognize(strokes) { true } }
        assertEquals(0, engine.calls)
    }

    @Test
    fun cancelDuringTheEngineCallDiscardsItsResult() {
        var canceled = false
        val engine = FakeEngine { canceled = true; listOf("late") }
        assertThrows(RecognitionCanceledException::class.java) { EngineTextRecognizer(engine).recognize(strokes) { canceled } }
        assertEquals(1, engine.calls)
    }

    @Test
    fun missingLanguagePackKeepsItsType() {
        val missing = LanguagePackMissingException("en-US")
        val thrown = assertThrows(LanguagePackMissingException::class.java) {
            EngineTextRecognizer(FakeEngine { throw missing }).recognize(strokes)
        }
        assertSame(missing, thrown)
        assertEquals("en-US", thrown.languageTag)
        // It is still a RecognitionException, so callers that only know that type still fail safely.
        assertTrue(thrown is RecognitionException)
    }

    @Test
    fun otherEngineFailuresBecomeRecognitionExceptions() {
        val thrown = assertThrows(RecognitionException::class.java) {
            EngineTextRecognizer(FakeEngine { throw IOException("disk") }).recognize(strokes)
        }
        assertEquals("Text recognition failed: disk", thrown.message)
        assertTrue(thrown.cause is IOException)
    }

    @Test
    fun preContextIsLimitedToItsLastTwentyCharacters() {
        val engine = FakeEngine { listOf("x") }
        val context = TextContext(300f, 40f, "The impulse response of the system")
        EngineTextRecognizer(engine).recognize(strokes, context)
        assertEquals(TextContext(300f, 40f, "response of the system".takeLast(20)), engine.context)
        assertEquals(20, engine.context!!.preContext.length)
        assertEquals("", TextContext.preContextFrom(null))
    }
}
