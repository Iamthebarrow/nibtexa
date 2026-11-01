package io.github.iamthebarrow.nibtexa.recognition

import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import io.github.iamthebarrow.nibtexa.core.model.latex.LatexTokens
import io.github.iamthebarrow.nibtexa.core.recognition.ByteLevelBpeDecoder
import io.github.iamthebarrow.nibtexa.core.recognition.GreedyDecoding
import io.github.iamthebarrow.nibtexa.core.recognition.TexTellerRecognizer
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The real TexTeller int8 model on the device. Fixtures are not in the repository (model size
 * and dataset licence); both tests are skipped unless they are pushed:
 * - /data/local/tmp/nibtexa-spike/: the model and the Phase 3 spike fixtures
 *   (tools/recognition-eval export_android_fixtures.py);
 * - /data/local/tmp/nibtexa-e2e/: samples.json (export_device_samples.py) and vocab.json.
 *
 * Timings are emulator figures on the laptop's CPU, not tablet results.
 */
@RunWith(AndroidJUnit4::class)
class TexTellerDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val work = File(instrumentation.targetContext.cacheDir, "texteller-test").apply { mkdirs() }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { String(it.readBytes()) }

    private fun copy(dir: String, name: String): File {
        val target = File(work, name)
        if (target.length() > 0 && name.endsWith(".onnx")) return target
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat $dir/$name")).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        check(target.length() > 0) { "Could not read $dir/$name" }
        return target
    }

    private fun model(): OnnxTexTellerModel {
        copy(SPIKE, OnnxTexTellerModel.ENCODER)
        copy(SPIKE, OnnxTexTellerModel.DECODER)
        return OnnxTexTellerModel(work)
    }

    /**
     * The app decodes with the KV cache. With dynamic int8 quantization that can differ by a
     * token from cache-less decoding (activation scales depend on the sequence fed), so the
     * reference is the laptop's cached decoding of the same input (`cached_token_ids`).
     */
    @Test
    fun cachedDecodingReproducesTheLaptopTokenIds() {
        assumeTrue("spike fixtures not pushed", shell("ls $SPIKE/expected.json").contains("expected.json"))
        val expected = JSONArray(copy(SPIKE, "expected.json").readText())
        model().use { model ->
            val millis = mutableListOf<Long>()
            for (i in 0 until expected.length()) {
                val sample = expected.getJSONObject(i)
                assumeTrue("fixture predates cached_token_ids", sample.has("cached_token_ids"))
                val wanted = sample.getJSONArray("cached_token_ids").let { a -> List(a.length()) { a.getLong(it) } }
                val pixels = FloatArray(448 * 448)
                ByteBuffer.wrap(copy(SPIKE, "${sample.getString("id")}.pixels.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(pixels)
                val started = SystemClock.elapsedRealtime()
                val produced = model.start(pixels).use { GreedyDecoding.decode(it, TexTellerRecognizer.BOS, TexTellerRecognizer.EOS, TexTellerRecognizer.MAX_TOKENS) { false } }
                millis += SystemClock.elapsedRealtime() - started
                assertEquals("token ids for ${sample.getString("id")}", wanted, produced)
            }
            val sorted = millis.sorted()
            Log.i(TAG, "RESULT kv-cache samples=${expected.length()} p50Ms=${sorted[sorted.size / 2]} maxMs=${sorted.last()}")
        }
    }

    @Test
    fun strokesToLatexMatchesTheLaptopAccuracy() {
        assumeTrue("e2e samples not pushed", shell("ls $E2E/samples.json").contains("samples.json"))
        assumeTrue("spike model not pushed", shell("ls $SPIKE/${OnnxTexTellerModel.DECODER}").contains("onnx"))
        val samples = JSONArray(copy(E2E, "samples.json").readText())
        val tokenizer = ByteLevelBpeDecoder.fromVocabJson(copy(E2E, OnnxTexTellerModel.VOCAB).readText())

        val loadStarted = SystemClock.elapsedRealtime()
        model().use { model ->
            val loadMs = SystemClock.elapsedRealtime() - loadStarted
            val recognizer = TexTellerRecognizer(model, tokenizer)
            var deviceExact = 0
            var laptopExact = 0
            var agree = 0
            var peakPss = 0L
            val millis = mutableListOf<Long>()
            for (i in 0 until samples.length()) {
                val sample = samples.getJSONObject(i)
                val truth = LatexTokens.normalize(sample.getString("truth"))
                val strokes = sample.getJSONArray("strokes").let { a ->
                    List(a.length()) { s ->
                        val points = a.getJSONArray(s).let { p ->
                            List(p.length()) { j -> p.getJSONArray(j).let { InkPoint(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toLong()) } }
                        }
                        Stroke(s + 1L, ToolType.STYLUS, 0, points)
                    }
                }
                val started = SystemClock.elapsedRealtime()
                val predicted = LatexTokens.normalize(recognizer.recognize(strokes))
                millis += SystemClock.elapsedRealtime() - started
                peakPss = maxOf(peakPss, Debug.getPss())

                val laptop = sample.optString("laptop").takeIf { it.isNotEmpty() }?.let(LatexTokens::normalize)
                if (predicted == truth) deviceExact++
                if (laptop == truth) laptopExact++
                if (laptop == predicted) agree++
            }
            val n = samples.length()
            val sorted = millis.sorted()
            Log.i(
                TAG,
                "RESULT e2e samples=$n deviceExact=$deviceExact laptopExact=$laptopExact agree=$agree loadMs=$loadMs " +
                    "p50Ms=${sorted[n / 2]} p95Ms=${sorted[(n * 95) / 100]} maxMs=${sorted.last()} peakPssMb=${peakPss / 1024}",
            )
            // The Kotlin render and preprocessing differ in small ways from the laptop's Python
            // path; accuracy must stay within five points of it.
            assertTrue("device exact $deviceExact vs laptop $laptopExact of $n", deviceExact >= laptopExact - n / 20)
        }
    }

    private companion object {
        const val TAG = "TexTellerDevice"
        const val SPIKE = "/data/local/tmp/nibtexa-spike"
        const val E2E = "/data/local/tmp/nibtexa-e2e"
    }
}
