package io.github.iamthebarrow.nibtexa.core.recognition

import io.github.iamthebarrow.nibtexa.core.model.Stroke

/**
 * What a text recognizer may use besides the ink (ADR-0007).
 *
 * @property writingWidth the width of the area the ink was written in, in canvas units, or
 *   null if unknown.
 * @property writingHeight the height of that area.
 * @property preContext the text that comes just before this ink, in reading order. At most
 *   [MAX_PRE_CONTEXT] characters are used.
 */
data class TextContext(
    val writingWidth: Float? = null,
    val writingHeight: Float? = null,
    val preContext: String = "",
) {
    companion object {
        /** ML Kit uses "up to 20 characters" of pre-context. */
        const val MAX_PRE_CONTEXT = 20

        val NONE = TextContext()

        /** The last [MAX_PRE_CONTEXT] characters of [text], or "" for none. */
        fun preContextFrom(text: String?): String = text.orEmpty().takeLast(MAX_PRE_CONTEXT)
    }
}

/** Converts the strokes of a text or heading region to plain text. Never call on the UI thread. */
interface TextRecognizer {
    /**
     * @param isCanceled checked before and after the recognizer runs; when it returns true the
     *   call throws [RecognitionCanceledException].
     * @throws LanguagePackMissingException if the handwriting language is not downloaded.
     * @throws RecognitionException if recognition fails or finds nothing.
     */
    fun recognize(strokes: List<Stroke>, context: TextContext = TextContext.NONE, isCanceled: () -> Boolean = { false }): String
}

/** The handwriting language pack is not on the device; the user has to download it first. */
class LanguagePackMissingException(val languageTag: String) :
    RecognitionException("Handwriting language $languageTag is not downloaded")

/**
 * A platform handwriting engine (ML Kit in the app): ink in, ranked candidates out. It may
 * block; it is called off the UI thread.
 */
fun interface TextEngine {
    /**
     * @return candidate transcriptions, best first. Empty if nothing was recognized.
     * @throws LanguagePackMissingException if its language is not downloaded.
     */
    fun candidates(strokes: List<Stroke>, context: TextContext): List<String>
}

/**
 * The [TextRecognizer] behaviour shared by every engine: no empty ink, cancellation around
 * the engine call, the best candidate, and whitespace cleaned up. Engine failures other than
 * the typed ones become [RecognitionException].
 */
class EngineTextRecognizer(private val engine: TextEngine) : TextRecognizer {
    override fun recognize(strokes: List<Stroke>, context: TextContext, isCanceled: () -> Boolean): String {
        if (strokes.isEmpty()) throw RecognitionException("No ink to recognize")
        if (isCanceled()) throw RecognitionCanceledException()
        val bounded = context.copy(preContext = TextContext.preContextFrom(context.preContext))
        val candidates = try {
            engine.candidates(strokes, bounded)
        } catch (e: RecognitionException) {
            throw e
        } catch (e: Exception) {
            throw RecognitionException("Text recognition failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
        if (isCanceled()) throw RecognitionCanceledException()
        return candidates.asSequence().map(TextCleanup::clean).firstOrNull { it.isNotEmpty() }
            ?: throw RecognitionException("No text recognized")
    }
}

object TextCleanup {
    /** Trims, and collapses runs of whitespace (including line breaks) to one space. */
    fun clean(text: String): String = text.trim().replace(Regex("\\s+"), " ")
}
