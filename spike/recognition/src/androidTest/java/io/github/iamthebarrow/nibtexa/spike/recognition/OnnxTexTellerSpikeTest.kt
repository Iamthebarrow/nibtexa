package io.github.iamthebarrow.nibtexa.spike.recognition

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Phase 3 feasibility check: does ONNX Runtime Android run the int8 TexTeller model and
 * reproduce, token for token, what the same runtime produced on the laptop?
 *
 * The model and fixtures are not in the repository (size and licensing). Push them first:
 * `adb push <fixtures>/. /data/local/tmp/nibtexa-spike/` (made by tools/recognition-eval).
 * The test is skipped when they are absent, so it never fails a normal test run.
 *
 * Timings are emulator figures, which run on the laptop's CPU, and are not tablet results.
 */
@RunWith(AndroidJUnit4::class)
class OnnxTexTellerSpikeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val workDir = File(instrumentation.targetContext.cacheDir, "spike").apply { mkdirs() }

    @Test
    fun int8ModelReproducesLaptopTokenIds() {
        assumeTrue("Spike fixtures not pushed to $DEVICE_DIR", shell("ls $DEVICE_DIR/expected.json").contains("expected.json"))

        val expected = JSONArray(String(copyFromDevice("expected.json").readBytes()))
        val encoderFile = copyFromDevice("encoder_model.onnx")
        val decoderFile = copyFromDevice("decoder_model_merged.onnx")

        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(THREADS) }
        val pssBefore = Debug.getPss()
        val loadStart = SystemClock.elapsedRealtime()
        val encoder = env.createSession(encoderFile.path, options)
        val decoder = env.createSession(decoderFile.path, options)
        val loadMs = SystemClock.elapsedRealtime() - loadStart
        log("model load: $loadMs ms, PSS after load ${(Debug.getPss() - pssBefore) / 1024} MB above baseline")

        val pastInputs = decoder.inputNames.filter { it.startsWith("past_key_values") }
        val hasCacheBranch = "use_cache_branch" in decoder.inputNames
        var matches = 0
        var peakPssKb = 0L
        val sampleMs = mutableListOf<Long>()

        for (i in 0 until expected.length()) {
            val sample = expected.getJSONObject(i)
            val id = sample.getString("id")
            val shape = sample.getJSONArray("shape").let { a -> LongArray(a.length()) { a.getLong(it) } }
            val wanted = sample.getJSONArray("token_ids").let { a -> List(a.length()) { a.getLong(it) } }
            val pixels = ByteBuffer.wrap(copyFromDevice("$id.pixels.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()

            val started = SystemClock.elapsedRealtime()
            val produced = OnnxTensor.createTensor(env, pixels, shape).use { input ->
                encoder.run(mapOf("pixel_values" to input)).use { encoded ->
                    val hidden = encoded.get(0) as OnnxTensor
                    greedyDecode(env, decoder, hidden, pastInputs, hasCacheBranch)
                }
            }
            val ms = SystemClock.elapsedRealtime() - started
            sampleMs += ms
            peakPssKb = maxOf(peakPssKb, Debug.getPss())

            val same = produced == wanted
            if (same) matches++
            log("$id: ${produced.size} tokens in $ms ms, matches laptop: $same")
        }

        val sorted = sampleMs.sorted()
        log(
            "SUMMARY samples=${expected.length()} matches=$matches threads=$THREADS loadMs=$loadMs " +
                "p50Ms=${sorted[sorted.size / 2]} maxMs=${sorted.last()} peakPssMb=${peakPssKb / 1024} " +
                "nativeHeapMb=${Debug.getNativeHeapAllocatedSize() / 1048576}",
        )
        decoder.close()
        encoder.close()
        assertEquals("Token ids must match the laptop reference exactly", expected.length(), matches)
    }

    /** Greedy decoding without the KV cache, mirroring export_android_fixtures.greedy_decode. */
    private fun greedyDecode(
        env: OrtEnvironment,
        decoder: OrtSession,
        hidden: OnnxTensor,
        pastInputs: List<String>,
        hasCacheBranch: Boolean,
    ): List<Long> {
        val ids = mutableListOf(BOS)
        val emptyPast = pastInputs.associateWith { name ->
            val heads = (decoder.inputInfo.getValue(name).info as ai.onnxruntime.TensorInfo).shape
            OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, heads[1], 0, heads[3]))
        }
        val cacheBranch = if (hasCacheBranch) OnnxTensor.createTensor(env, booleanArrayOf(false)) else null
        try {
            repeat(MAX_TOKENS) {
                val inputIds = OnnxTensor.createTensor(env, LongBuffer.wrap(ids.toLongArray()), longArrayOf(1, ids.size.toLong()))
                val feeds = buildMap {
                    put("input_ids", inputIds)
                    put("encoder_hidden_states", hidden)
                    putAll(emptyPast)
                    cacheBranch?.let { put("use_cache_branch", it) }
                }
                val next = decoder.run(feeds, setOf("logits")).use { result ->
                    val logits = result.get(0) as OnnxTensor
                    val vocab = logits.info.shape[2].toInt()
                    val buffer = logits.floatBuffer
                    val offset = (ids.size - 1) * vocab
                    var best = 0
                    for (v in 1 until vocab) if (buffer.get(offset + v) > buffer.get(offset + best)) best = v
                    best.toLong()
                }
                inputIds.close()
                ids += next
                if (next == EOS) return ids
            }
            return ids
        } finally {
            emptyPast.values.forEach { it.close() }
            cacheBranch?.close()
        }
    }

    private fun copyFromDevice(name: String): File {
        val target = File(workDir, name)
        if (target.exists() && target.length() > 0 && !name.endsWith(".json")) return target
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat $DEVICE_DIR/$name")).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        check(target.length() > 0) { "Could not read $DEVICE_DIR/$name" }
        return target
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            String(it.readBytes())
        }

    private fun log(message: String) {
        Log.i(TAG, message)
        println("$TAG: $message")
    }

    companion object {
        private const val TAG = "NibTeXaSpike"
        private const val DEVICE_DIR = "/data/local/tmp/nibtexa-spike"
        private const val THREADS = 4
        private const val BOS = 0L
        private const val EOS = 2L
        private const val MAX_TOKENS = 256
    }
}
