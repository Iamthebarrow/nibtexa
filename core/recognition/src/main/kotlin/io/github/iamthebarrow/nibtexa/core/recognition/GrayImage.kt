package io.github.iamthebarrow.nibtexa.core.recognition

import io.github.iamthebarrow.nibtexa.core.model.Stroke
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** An 8-bit grayscale image, row-major, 0 = black and 255 = white. */
class GrayImage(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height) { WHITE }) {
    init {
        require(width > 0 && height > 0) { "Empty image" }
        require(pixels.size == width * height) { "Pixel count does not match size" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    operator fun set(x: Int, y: Int, value: Int) {
        pixels[y * width + x] = value
    }

    companion object {
        const val WHITE = 255
        const val BLACK = 0
    }
}

/**
 * Rasterizes strokes black on white, the way the Phase 3 evaluation harness did
 * (tools/recognition-eval/nibtexa_eval/image_models.py `render`): the ink is scaled so its
 * height is [TARGET_HEIGHT] (flat expressions are capped at 8:1), with [PADDING] pixels
 * around it and solid, non-antialiased lines with round joins. Matching the harness keeps
 * the on-device input close to what the measured accuracy was obtained with.
 */
object StrokeRenderer {
    const val TARGET_HEIGHT = 128
    const val PADDING = 16

    /** The largest image this produces, to bound memory for very wide selections. */
    private const val MAX_SIDE = 4096

    fun render(strokes: List<Stroke>): GrayImage {
        require(strokes.isNotEmpty()) { "Nothing to render" }
        val points = strokes.flatMap { it.points }
        val minX = points.minOf { it.x }
        val minY = points.minOf { it.y }
        val width = points.maxOf { it.x } - minX
        val height = points.maxOf { it.y } - minY
        val scale = TARGET_HEIGHT / maxOf(height, width / 8, 1f)
        val line = max(2, (TARGET_HEIGHT / 40.0).roundToInt())
        val w = min((width * scale).toInt() + 2 * PADDING, MAX_SIDE)
        val h = min((height * scale).toInt() + 2 * PADDING, MAX_SIDE)
        val image = GrayImage(w, h)
        val radius = line / 2f
        for (stroke in strokes) {
            val xs = stroke.points.map { (it.x - minX) * scale + PADDING }
            val ys = stroke.points.map { (it.y - minY) * scale + PADDING }
            if (xs.size == 1) {
                disc(image, xs[0], ys[0], radius)
                continue
            }
            for (i in 1 until xs.size) segment(image, xs[i - 1], ys[i - 1], xs[i], ys[i], radius)
        }
        return image
    }

    /** A thick segment with round caps: every pixel centre within [radius] of the segment. */
    private fun segment(image: GrayImage, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float) {
        val dx = x1 - x0
        val dy = y1 - y0
        val lengthSquared = dx * dx + dy * dy
        val left = max(0, floor(min(x0, x1) - radius).toInt())
        val right = min(image.width - 1, ceil(max(x0, x1) + radius).toInt())
        val top = max(0, floor(min(y0, y1) - radius).toInt())
        val bottom = min(image.height - 1, ceil(max(y0, y1) + radius).toInt())
        for (y in top..bottom) {
            for (x in left..right) {
                val px = x + 0.5f
                val py = y + 0.5f
                val t = if (lengthSquared == 0f) 0f else (((px - x0) * dx + (py - y0) * dy) / lengthSquared).coerceIn(0f, 1f)
                if (hypot(px - (x0 + t * dx), py - (y0 + t * dy)) <= radius) image[x, y] = GrayImage.BLACK
            }
        }
    }

    private fun disc(image: GrayImage, cx: Float, cy: Float, radius: Float) = segment(image, cx, cy, cx, cy, radius)
}
