package io.github.iamthebarrow.nibtexa.recognition

import io.github.iamthebarrow.nibtexa.core.recognition.RecognitionException
import io.github.iamthebarrow.nibtexa.core.recognition.TextRecognizer

/** Provides the text recognizer. Call off the main thread. */
fun interface TextRecognizerSource {
    /** @throws RecognitionException if no recognizer is available. */
    fun recognizer(): TextRecognizer
}

/** The downloadable handwriting language, as the notebook sees it. Blocking; call off the main thread. */
interface HandwritingPack {
    val languageTag: String

    fun isDownloaded(): Boolean

    /** @throws RecognitionException if the download fails. */
    fun download(wifiOnly: Boolean)
}

/** How much a handwriting language takes, as the app tells the user before downloading one on a metered connection. */
const val HANDWRITING_PACK_SIZE_MB = 20
