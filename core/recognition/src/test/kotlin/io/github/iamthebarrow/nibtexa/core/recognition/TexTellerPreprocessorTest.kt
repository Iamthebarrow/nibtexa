package io.github.iamthebarrow.nibtexa.core.recognition

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Parity with TexTeller's Python transform. The fixture comes from
 * tools/recognition-eval/nibtexa_eval/export_kotlin_fixtures.py, run on synthetic images that
 * [synthetic] reproduces exactly.
 */
class TexTellerPreprocessorTest {
    private companion object {
        const val ONE_STEP = 1.0 / 255 / TexTellerPreprocessor.STD + 1e-6
    }

    private val fixture = Json.parseToJsonElement(
        checkNotNull(javaClass.getResource("/texteller/preprocess.json")).readText(),
    ).jsonObject

    /** Same formula as `synthetic` in export_kotlin_fixtures.py. */
    private fun synthetic(height: Int, width: Int): GrayImage {
        val image = GrayImage(height = height, width = width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var v = 255
                if (x >= width / 10 && x < width - width / 10 && y >= height / 10 && y < height - height / 10) {
                    v = 255 - ((x * 7 + y * 13) % 97) * 2
                }
                if (abs(x * height - y * width) <= max(height, width)) v = 0
                image[x, y] = v.coerceIn(0, 255)
            }
        }
        return image
    }

    @Test
    fun matchesPythonOnEverySyntheticImage() {
        for ((name, element) in fixture) {
            val entry = element as JsonObject
            val h = entry["height"]!!.jsonPrimitive.int
            val w = entry["width"]!!.jsonPrimitive.int
            val out = TexTellerPreprocessor.preprocess(synthetic(h, w))

            // Same content area (resize rules and trimming).
            val rows = (0 until 448).last { r -> (0 until 448).any { out[r * 448 + it] != 0f } } + 1
            val cols = (0 until 448).last { c -> (0 until 448).any { out[it * 448 + c] != 0f } } + 1
            assertEquals("$name rows", entry["contentRows"]!!.jsonPrimitive.int, rows)
            assertEquals("$name cols", entry["contentCols"]!!.jsonPrimitive.int, cols)

            // Upscaled images match to float precision. Downscaling can differ by one 8-bit step
            // (1/255/STD ≈ 0.0255 after normalization) at a few pixels, from fixed-point rounding
            // in torchvision's uint8 path; the mean and RMS must still agree closely.
            val samples = entry["samples"]!!.jsonArray
            var worst = 0.0
            for (s in samples) {
                val (r, c, v) = (s as JsonArray).let { Triple(it[0].jsonPrimitive.int, it[1].jsonPrimitive.int, it[2].jsonPrimitive.double) }
                worst = max(worst, abs(out[r * 448 + c] - v))
            }
            assertTrue("$name worst sampled difference $worst", worst <= ONE_STEP)

            val n = 448.0 * 448.0
            val meanDiff = abs(out.sumOf { it.toDouble() } - entry["sum"]!!.jsonPrimitive.double) / n
            val rmsKotlin = sqrt(out.sumOf { it.toDouble() * it } / n)
            val rmsPython = sqrt(entry["sumSquares"]!!.jsonPrimitive.double / n)
            assertTrue("$name mean difference $meanDiff", meanDiff < 1e-4)
            assertTrue("$name rms $rmsKotlin vs $rmsPython", abs(rmsKotlin - rmsPython) < 1e-4)
        }
    }

    @Test
    fun resizedSizeFollowsTorchvisionRules() {
        assertEquals(447 to 447, TexTellerPreprocessor.resizedSize(100, 100))
        assertEquals(447 to 448, TexTellerPreprocessor.resizedSize(447, 448)) // long side exactly at max
        assertEquals(59 to 448, TexTellerPreprocessor.resizedSize(40, 300)) // capped by max_size
        assertEquals(448 to 112, TexTellerPreprocessor.resizedSize(200, 50))
    }

    @Test
    fun trimFindsInkAndKeepsABlankImageUsable() {
        val image = GrayImage(10, 8)
        image[3, 2] = 0
        image[6, 5] = 200 // within the threshold of white: not ink
        image[7, 6] = 100
        val trimmed = TexTellerPreprocessor.trim(image)
        assertEquals(5, trimmed.width)
        assertEquals(5, trimmed.height)
        assertEquals(0, trimmed[0, 0])

        assertEquals(1, TexTellerPreprocessor.trim(GrayImage(4, 4)).width)
    }

    @Test
    fun outputIsNormalizedAndPaddedWithZero() {
        val image = GrayImage(4, 2)
        image[0, 0] = 0
        image[3, 1] = 0
        val out = TexTellerPreprocessor.preprocess(image)

        assertEquals(448 * 448, out.size)
        assertEquals((0f - TexTellerPreprocessor.MEAN) / TexTellerPreprocessor.STD, out[0], 1e-4f)
        assertEquals(0f, out[447 * 448 + 447]) // bottom-right is padding
    }
}
