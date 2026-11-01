package io.github.iamthebarrow.nibtexa.recognition

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import io.github.iamthebarrow.nibtexa.core.recognition.EngineTextRecognizer
import io.github.iamthebarrow.nibtexa.core.recognition.LanguagePackMissingException
import io.github.iamthebarrow.nibtexa.core.recognition.RecognitionException
import io.github.iamthebarrow.nibtexa.core.recognition.TextContext
import io.github.iamthebarrow.nibtexa.core.recognition.TextEngine
import io.github.iamthebarrow.nibtexa.core.recognition.TextRecognizer
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Google ML Kit Digital Ink Recognition (ADR-0007). **Proprietary**: combined with NibTeXa under the
 * additional permission in NOTICE.md. Recognition runs on the device;
 * the language pack is downloaded only through [TextLanguagePack.download].
 */
class MlKitTextSource(val pack: TextLanguagePack = TextLanguagePack()) : TextRecognizerSource {
    private val recognizer = EngineTextRecognizer(MlKitTextEngine(pack))

    override fun recognizer(): TextRecognizer = recognizer
}

/** The handwriting language pack: check, download (explicit, user-started) and delete. */
class TextLanguagePack(override val languageTag: String = ENGLISH) : HandwritingPack {
    val model: DigitalInkRecognitionModel by lazy {
        val id = try {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag)
        } catch (e: MlKitException) {
            throw RecognitionException("Unsupported handwriting language $languageTag", e)
        } ?: throw RecognitionException("Unsupported handwriting language $languageTag")
        DigitalInkRecognitionModel.builder(id).build()
    }

    private val manager: RemoteModelManager get() = RemoteModelManager.getInstance()

    /** Blocks; call off the main thread. */
    override fun isDownloaded(): Boolean = await(manager.isModelDownloaded(model), CHECK_TIMEOUT_S)

    /**
     * Downloads the pack. Blocks until done; call off the main thread. ML Kit reports no
     * progress, so callers show an indeterminate indicator.
     *
     * @param wifiOnly wait for an unmetered connection, the default unless the user agreed to
     *   use mobile data.
     */
    override fun download(wifiOnly: Boolean) {
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        await(manager.download(model, conditions), DOWNLOAD_TIMEOUT_S)
    }

    /** Deletes the downloaded pack, freeing its storage. */
    fun delete() {
        await(manager.deleteDownloadedModel(model), CHECK_TIMEOUT_S)
    }

    companion object {
        const val ENGLISH = "en-US"

        /** The documented size: "Around 20MB of storage is required per language". */
        const val APPROXIMATE_SIZE_MB = 20
        // The first check after install parses ML Kit's model manifest; on the AVD it took over 10 s.
        private const val CHECK_TIMEOUT_S = 30L
        private const val DOWNLOAD_TIMEOUT_S = 600L
    }
}

/**
 * Adapts the project's strokes (canvas units, per-stroke start time plus per-point elapsed
 * time) to ML Kit's [Ink]. The recognizer client is created on first use and kept.
 */
class MlKitTextEngine(private val pack: TextLanguagePack) : TextEngine {
    private var client: DigitalInkRecognizer? = null

    @Synchronized
    private fun client(): DigitalInkRecognizer =
        client ?: DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(pack.model).build()).also { client = it }

    override fun candidates(strokes: List<Stroke>, context: TextContext): List<String> {
        if (!pack.isDownloaded()) throw LanguagePackMissingException(pack.languageTag)
        val result = await(client().recognize(ink(strokes), recognitionContext(context)), RECOGNIZE_TIMEOUT_S)
        return result.candidates.map { it.text }
    }

    companion object {
        private const val RECOGNIZE_TIMEOUT_S = 30L

        fun ink(strokes: List<Stroke>): Ink = Ink.builder().apply {
            for (stroke in strokes) {
                val builder = Ink.Stroke.builder()
                for (p in stroke.points) builder.addPoint(Ink.Point.create(p.x, p.y, stroke.startTimeMillis + p.elapsedMillis))
                addStroke(builder.build())
            }
        }.build()

        fun recognitionContext(context: TextContext): RecognitionContext = RecognitionContext.builder()
            .setPreContext(context.preContext)
            .apply {
                val w = context.writingWidth
                val h = context.writingHeight
                if (w != null && h != null && w > 0f && h > 0f) setWritingArea(WritingArea(w, h))
            }
            .build()
    }
}

/** Waits for a Play services task off the main thread, mapping failures to [RecognitionException]. */
private fun <T> await(task: Task<T>, timeoutSeconds: Long): T = try {
    Tasks.await(task, timeoutSeconds, TimeUnit.SECONDS)
} catch (e: ExecutionException) {
    val cause = e.cause ?: e
    throw RecognitionException("Handwriting recognition failed: ${cause.message ?: cause.javaClass.simpleName}", cause)
} catch (e: TimeoutException) {
    throw RecognitionException("Handwriting recognition timed out", e)
} catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    throw RecognitionException("Handwriting recognition interrupted", e)
}
