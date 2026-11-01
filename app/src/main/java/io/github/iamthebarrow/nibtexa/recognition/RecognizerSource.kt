package io.github.iamthebarrow.nibtexa.recognition

import io.github.iamthebarrow.nibtexa.core.recognition.ByteLevelBpeDecoder
import io.github.iamthebarrow.nibtexa.core.recognition.MathRecognizer
import io.github.iamthebarrow.nibtexa.core.recognition.RecognitionException
import io.github.iamthebarrow.nibtexa.core.recognition.TexTellerRecognizer
import java.io.File

/** Provides the recognizer, loading it on first use. Call off the main thread. */
fun interface RecognizerSource {
    /** @throws RecognitionException if no recognizer is available. */
    fun recognizer(): MathRecognizer

    /**
     * Drops a loaded recognizer, so the next call loads the model files again (after an
     * import). Never call it while a recognition is running.
     */
    fun invalidate() = Unit

    /**
     * Loads the recognizer ahead of the first conversion, at app start (Phase 14). Returns
     * false, quietly, if there is none to load.
     */
    fun warmUp(): Boolean = try {
        recognizer()
        true
    } catch (e: RecognitionException) {
        false
    }
}

class ModelNotInstalledException(directory: File, missing: List<String>) :
    RecognitionException("Recognition model not installed: ${missing.joinToString()} missing in $directory")

/**
 * TexTeller int8 from a folder on the device (ADR-0003). The personal sideload build carries
 * the model ([bundled], Phase 14) and copies it into the folder the first time it is needed;
 * otherwise it is loaded in the app or with adb (see docs/guide/MODEL_SETUP.md). Loading takes
 * several seconds and a few hundred MB, so it happens once: the app starts it when it opens
 * ([warmUp]), and a conversion that comes first waits for it.
 */
class TexTellerSource(private val directory: File, private val bundled: BundledModel? = null) : RecognizerSource {
    private var loaded: TexTellerRecognizer? = null
    private var model: OnnxTexTellerModel? = null

    @Synchronized
    override fun invalidate() {
        model?.close()
        model = null
        loaded = null
    }

    @Synchronized
    override fun recognizer(): MathRecognizer {
        loaded?.let { return it }
        bundled?.installIfMissing()
        val missing =OnnxTexTellerModel.REQUIRED_FILES.filterNot { File(directory, it).isFile }
        if (missing.isNotEmpty()) throw ModelNotInstalledException(directory, missing)
        // A damaged vocabulary or network is a typed error, like a missing one (Phase 8).
        val tokenizer = try {
            ByteLevelBpeDecoder.fromVocabJson(File(directory, OnnxTexTellerModel.VOCAB).readText())
        } catch (e: Exception) {
            throw RecognitionException("Could not read the recognition vocabulary: ${e.message?.lineSequence()?.firstOrNull()}", e)
        }
        val model = try {
            OnnxTexTellerModel(directory)
        } catch (e: Exception) {
            throw RecognitionException("Could not load the recognition model: ${e.message}", e)
        }
        this.model = model
        return TexTellerRecognizer(model, tokenizer).also { loaded = it }
    }

    companion object {
        const val MODEL_FOLDER = "texteller-int8"
    }
}
