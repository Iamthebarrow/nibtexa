package io.github.iamthebarrow.nibtexa.core.recognition

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * TexTeller's input transform, ported from `texteller.utils.image.transform` (TexTeller
 * 3.0, Apache-2.0):
 * 1. trim the background border (threshold 15 against the most common corner colour);
 * 2. convert to grayscale as torchvision does for uint8 RGB: the weights sum to 0.9999 and
 *    the result is truncated, so a gray value v becomes v - 1 (white becomes 254);
 * 3. resize so the shorter side is 447 and the longer at most 448 (torchvision `Resize`
 *    with `max_size`), bicubic with antialiasing, rounded back to 8 bits as torchvision does
 *    for uint8 input;
 * 4. scale to 0..1 and normalize with the training mean and standard deviation;
 * 5. pad right and bottom with 0 (after normalization) to 448 x 448.
 *
 * The resampling follows PIL's separable antialiased bicubic (a = -0.5), which torchvision's
 * antialiased path reproduces. Small rounding differences against PyTorch are expected and
 * bounded in the tests.
 */
object TexTellerPreprocessor {
    const val SIZE = 448
    const val MEAN = 0.9545467f
    const val STD = 0.15394445f
    private const val TRIM_THRESHOLD = 15

    /** Returns the 1 x 1 x 448 x 448 input tensor, row-major. */
    fun preprocess(image: GrayImage): FloatArray {
        val trimmed = grayscale(trim(image))
        val (h, w) = resizedSize(trimmed.height, trimmed.width)
        val resized = resize(trimmed, w, h)
        val out = FloatArray(SIZE * SIZE) // 0 is the padding value
        for (y in 0 until h) {
            for (x in 0 until w) out[y * SIZE + x] = (resized[x, y] / 255f - MEAN) / STD
        }
        return out
    }

    /** torchvision `_compute_resized_output_size(size = 447, max_size = 448)`, as (height, width). */
    fun resizedSize(height: Int, width: Int): Pair<Int, Int> {
        val short = min(height, width)
        val long = max(height, width)
        var newShort = SIZE - 1
        var newLong = ((SIZE - 1).toLong() * long / short).toInt()
        if (newLong > SIZE) {
            newShort = (SIZE.toLong() * newShort / newLong).toInt()
            newLong = SIZE
        }
        return if (width <= height) newLong to newShort else newShort to newLong
    }

    /** Crops to the bounding box of pixels that differ from the background by more than 15. */
    fun trim(image: GrayImage): GrayImage {
        val corners = listOf(image[0, 0], image[image.width - 1, 0], image[0, image.height - 1], image[image.width - 1, image.height - 1])
        // Ties go to the first corner seen, as Counter.most_common does.
        val background = corners.groupingBy { it }.eachCount().maxBy { it.value }.key
        var left = image.width
        var top = image.height
        var right = -1
        var bottom = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (abs(image[x, y] - background) > TRIM_THRESHOLD) {
                    left = min(left, x); right = max(right, x)
                    top = min(top, y); bottom = max(bottom, y)
                }
            }
        }
        // cv2.boundingRect of an empty mask is (0, 0, 0, 0); keep one pixel instead of failing.
        if (right < 0) return GrayImage(1, 1, intArrayOf(image[0, 0]))
        val w = right - left + 1
        val h = bottom - top + 1
        return GrayImage(w, h, IntArray(w * h) { i -> image[left + i % w, top + i / w] })
    }

    /** torchvision `rgb_to_grayscale` on an image whose three channels are equal. */
    fun grayscale(image: GrayImage): GrayImage =
        GrayImage(image.width, image.height, IntArray(image.pixels.size) { i ->
            val v = image.pixels[i].toFloat()
            (0.2989f * v + 0.587f * v + 0.114f * v).toInt()
        })

    /** Separable antialiased bicubic resampling: horizontal pass, then vertical. */
    fun resize(image: GrayImage, width: Int, height: Int): GrayImage {
        val horizontal = resampleRows(image.pixels.map { it.toFloat() }.toFloatArray(), image.width, image.height, width)
        val vertical = resampleColumns(horizontal, width, image.height, height)
        return GrayImage(width, height, IntArray(width * height) { vertical[it].roundToInt().coerceIn(0, 255) })
    }

    private class Filter(val start: IntArray, val weights: Array<FloatArray>)

    private fun filter(inSize: Int, outSize: Int): Filter {
        val scale = inSize.toDouble() / outSize
        val filterScale = max(scale, 1.0)
        val support = 2.0 * filterScale
        val start = IntArray(outSize)
        val weights = Array(outSize) { i ->
            val center = (i + 0.5) * scale
            val lo = max((center - support + 0.5).toInt(), 0)
            val hi = min((center + support + 0.5).toInt(), inSize)
            start[i] = lo
            val w = FloatArray(hi - lo) { j -> cubic((j + lo - center + 0.5) / filterScale).toFloat() }
            val sum = w.sum()
            if (sum != 0f) for (j in w.indices) w[j] /= sum
            w
        }
        return Filter(start, weights)
    }

    private fun resampleRows(src: FloatArray, inW: Int, h: Int, outW: Int): FloatArray {
        if (inW == outW) return src
        val f = filter(inW, outW)
        val out = FloatArray(outW * h)
        for (y in 0 until h) {
            for (x in 0 until outW) {
                var acc = 0f
                val w = f.weights[x]
                for (j in w.indices) acc += src[y * inW + f.start[x] + j] * w[j]
                out[y * outW + x] = round8(acc)
            }
        }
        return out
    }

    private fun resampleColumns(src: FloatArray, w: Int, inH: Int, outH: Int): FloatArray {
        if (inH == outH) return src
        val f = filter(inH, outH)
        val out = FloatArray(w * outH)
        for (y in 0 until outH) {
            val weights = f.weights[y]
            for (x in 0 until w) {
                var acc = 0f
                for (j in weights.indices) acc += src[(f.start[y] + j) * w + x] * weights[j]
                out[y * w + x] = round8(acc)
            }
        }
        return out
    }

    /** Each pass of the uint8 path stores 8-bit values. */
    private fun round8(v: Float): Float = floor(v + 0.5f).coerceIn(0f, 255f)

    /** Keys' cubic convolution kernel with a = -0.5, as in PIL. */
    private fun cubic(x: Double): Double {
        val a = -0.5
        val t = abs(x)
        return when {
            t < 1 -> ((a + 2) * t - (a + 3)) * t * t + 1
            t < 2 -> (((t - 5) * t + 8) * t - 4) * a
            else -> 0.0
        }
    }
}
