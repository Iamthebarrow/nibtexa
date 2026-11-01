package io.github.iamthebarrow.nibtexa.core.recognition

import io.github.iamthebarrow.nibtexa.core.model.Stroke
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** Converts the strokes of a math region to LaTeX. Implementations may be slow; never call on the UI thread. */
interface MathRecognizer {
    /**
     * @param isCanceled polled between decoding steps; when it returns true the call throws
     *   [RecognitionCanceledException].
     * @throws RecognitionException if recognition fails.
     */
    fun recognize(strokes: List<Stroke>, isCanceled: () -> Boolean = { false }): String
}

open class RecognitionException(message: String, cause: Throwable? = null) : Exception(message, cause)

class RecognitionCanceledException : RecognitionException("Recognition canceled")

/** The model behind [TexTellerRecognizer]: the encoder plus a stateful greedy decoding step. */
interface TexTellerModel : AutoCloseable {
    /** Runs the encoder on a 1 x 1 x 448 x 448 input and returns a decoding session. */
    fun start(pixels: FloatArray): TokenSession
}

interface TokenSession : AutoCloseable {
    /** Feeds the next token and returns the most likely following token id. */
    fun next(token: Long): Long
}

/** TexTeller: render → preprocess → encode → greedy decode → detokenize → clean up. */
class TexTellerRecognizer(
    private val model: TexTellerModel,
    private val tokenizer: ByteLevelBpeDecoder,
    private val maxTokens: Int = MAX_TOKENS,
) : MathRecognizer {

    override fun recognize(strokes: List<Stroke>, isCanceled: () -> Boolean): String {
        if (strokes.isEmpty()) throw RecognitionException("No ink to recognize")
        val pixels = TexTellerPreprocessor.preprocess(StrokeRenderer.render(strokes))
        if (isCanceled()) throw RecognitionCanceledException()
        val ids = model.start(pixels).use { session -> GreedyDecoding.decode(session, BOS, EOS, maxTokens, isCanceled) }
        return LatexCleanup.clean(tokenizer.decode(ids))
    }

    companion object {
        const val BOS = 0L
        const val PAD = 1L
        const val EOS = 2L
        const val MAX_TOKENS = 256
    }
}

object GreedyDecoding {
    /** Returns the generated ids, starting with [bos] and ending with [eos] unless [maxTokens] was reached. */
    fun decode(session: TokenSession, bos: Long, eos: Long, maxTokens: Int, isCanceled: () -> Boolean): List<Long> {
        val ids = mutableListOf(bos)
        repeat(maxTokens) {
            if (isCanceled()) throw RecognitionCanceledException()
            val next = session.next(ids.last())
            ids += next
            if (next == eos) return ids
        }
        return ids
    }
}

/**
 * Decodes byte-level BPE token ids (the RoBERTa/GPT-2 scheme TexTeller's tokenizer uses) back
 * to text: token strings are concatenated, mapped from GPT-2's printable byte alphabet back
 * to bytes, and read as UTF-8. Special tokens are skipped.
 */
class ByteLevelBpeDecoder(vocab: Map<String, Int>, private val specialIds: Set<Long>) {
    private val tokens: Map<Long, String> = vocab.entries.associate { (token, id) -> id.toLong() to token }

    fun decode(ids: List<Long>): String {
        val text = ids.filter { it !in specialIds }.joinToString("") { tokens[it] ?: throw RecognitionException("Unknown token id $it") }
        val bytes = ByteArray(text.length) { i ->
            val b = UNICODE_TO_BYTE[text[i]] ?: throw RecognitionException("Character outside the byte alphabet: ${text[i]}")
            b.toByte()
        }
        return bytes.toString(Charsets.UTF_8)
    }

    companion object {
        /** Reads a Hugging Face `vocab.json` (token → id). */
        fun fromVocabJson(text: String, specialIds: Set<Long> = setOf(TexTellerRecognizer.BOS, TexTellerRecognizer.PAD, TexTellerRecognizer.EOS, UNK, MASK)): ByteLevelBpeDecoder {
            val obj = Json.parseToJsonElement(text) as? JsonObject ?: throw RecognitionException("vocab.json is not an object")
            return ByteLevelBpeDecoder(obj.mapValues { it.value.jsonPrimitive.int }, specialIds)
        }

        private const val UNK = 3L
        private const val MASK = 4L

        /** GPT-2's `bytes_to_unicode`, inverted. */
        val UNICODE_TO_BYTE: Map<Char, Int> = run {
            val printable = (('!'.code..'~'.code) + ('¡'.code..'¬'.code) + ('®'.code..'ÿ'.code)).toMutableList()
            val chars = printable.toMutableList()
            var n = 0
            for (b in 0..255) {
                if (b !in printable) {
                    printable += b
                    chars += 256 + n
                    n++
                }
            }
            printable.indices.associate { chars[it].toChar() to printable[it] }
        }
    }
}

/** Light clean-up of recognizer output before it is shown and edited. */
object LatexCleanup {
    private val DELIMITERS = listOf("\\[" to "\\]", "\\(" to "\\)", "$$" to "$$", "$" to "$")

    fun clean(latex: String): String {
        var s = latex.trim()
        for ((open, close) in DELIMITERS) {
            if (s.length >= open.length + close.length && s.startsWith(open) && s.endsWith(close)) {
                s = s.substring(open.length, s.length - close.length).trim()
                break
            }
        }
        s = s.removePrefix("\\displaystyle").trim()
        return s.replace(Regex("\\s+"), " ")
    }
}
