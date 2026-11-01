package io.github.iamthebarrow.nibtexa.recognition

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import io.github.iamthebarrow.nibtexa.core.recognition.TexTellerModel
import io.github.iamthebarrow.nibtexa.core.recognition.TexTellerPreprocessor
import io.github.iamthebarrow.nibtexa.core.recognition.TokenSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * TexTeller int8 on ONNX Runtime (ADR-0003): `encoder_model.onnx` plus the merged decoder
 * `decoder_model_merged.onnx`, decoded greedily **with** the KV cache. The first step runs
 * the no-cache branch on `<s>` with empty past tensors; every later step feeds only the last
 * token and the previous step's `present.*` outputs with `use_cache_branch = true`.
 *
 * The cross-attention (`.encoder.`) caches are computed once, on the first step, and reused
 * unchanged: the merged model's cached branch does not return usable encoder caches, and
 * feeding them back made decoding diverge from the third token (found on the AVD).
 *
 * Not thread-safe: run one recognition at a time.
 */
class OnnxTexTellerModel(directory: File, threads: Int = DEFAULT_THREADS) : TexTellerModel {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
    private val encoder = env.createSession(File(directory, ENCODER).path, options)
    private val decoder = env.createSession(File(directory, DECODER).path, options)

    /** past_key_values.N.{decoder,encoder}.{key,value}, in the decoder's input order. */
    private val pastNames = decoder.inputNames.filter { it.startsWith("past_key_values.") }
    private val pastShapes = pastNames.associateWith { (decoder.inputInfo.getValue(it).info as TensorInfo).shape }

    override fun start(pixels: FloatArray): TokenSession {
        require(pixels.size == TexTellerPreprocessor.SIZE * TexTellerPreprocessor.SIZE) { "Expected a 448 x 448 input" }
        val shape = longArrayOf(1, 1, TexTellerPreprocessor.SIZE.toLong(), TexTellerPreprocessor.SIZE.toLong())
        val hidden = OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), shape).use { input ->
            encoder.run(mapOf("pixel_values" to input)).use { result ->
                // Copy out, so the encoder's result can be released now.
                val tensor = result.get(0) as OnnxTensor
                OnnxTensor.createTensor(env, tensor.floatBuffer, tensor.info.shape)
            }
        }
        return Session(hidden)
    }

    private inner class Session(private val hidden: OnnxTensor) : TokenSession {
        private var past: Map<String, OnnxTensor> = pastNames.associateWith { name ->
            val s = pastShapes.getValue(name)
            OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, s[1], 0, s[3]))
        }
        private var first = true

        override fun next(token: Long): Long {
            val ids = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(token)), longArrayOf(1, 1))
            val branch = OnnxTensor.createTensor(env, booleanArrayOf(!first))
            try {
                val feeds = buildMap {
                    put("input_ids", ids)
                    put("encoder_hidden_states", hidden)
                    putAll(past)
                    put("use_cache_branch", branch)
                }
                decoder.run(feeds).use { result ->
                    val next = argmaxLast(result.get("logits").get() as OnnxTensor)
                    // present.N.decoder.* becomes the next step's past; the encoder caches are taken
                    // from the first step only. Copy before the result is released.
                    val updated = pastNames.associateWith { name ->
                        if (!first && ".encoder." in name) {
                            past.getValue(name)
                        } else {
                            val present = result.get(name.replace("past_key_values.", "present.")).get() as OnnxTensor
                            OnnxTensor.createTensor(env, present.floatBuffer, present.info.shape)
                        }
                    }
                    past.forEach { (name, tensor) -> if (updated[name] !== tensor) tensor.close() }
                    past = updated
                    first = false
                    return next
                }
            } finally {
                ids.close()
                branch.close()
            }
        }

        override fun close() {
            past.values.forEach { it.close() }
            past = emptyMap()
            hidden.close()
        }
    }

    private fun argmaxLast(logits: OnnxTensor): Long {
        val shape = logits.info.shape
        val vocab = shape[2].toInt()
        val offset = (shape[1].toInt() - 1) * vocab
        val buffer = logits.floatBuffer
        var best = 0
        var bestValue = buffer.get(offset)
        for (v in 1 until vocab) {
            val value = buffer.get(offset + v)
            if (value > bestValue) {
                bestValue = value
                best = v
            }
        }
        return best.toLong()
    }

    override fun close() {
        decoder.close()
        encoder.close()
        options.close()
    }

    companion object {
        const val ENCODER = "encoder_model.onnx"
        const val DECODER = "decoder_model_merged.onnx"
        const val VOCAB = "vocab.json"
        const val DEFAULT_THREADS = 4

        /** The files a model folder must contain. */
        val REQUIRED_FILES = listOf(ENCODER, DECODER, VOCAB)
    }
}
