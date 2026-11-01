package io.github.iamthebarrow.nibtexa.core.model.serialization

import io.github.iamthebarrow.nibtexa.core.model.Stroke
import java.util.Locale

/**
 * Writes strokes as W3C InkML, following the subset used by the CROHME datasets so
 * that standard handwriting-recognition tooling can read the corpus.
 *
 * Channels: X and Y always, T (ms from the first point of the sample) always, and F
 * (pressure, 0..1) only when every point of every stroke reports pressure, because a
 * trace format applies to all traces.
 */
object InkMlWriter {
    const val NAMESPACE = "http://www.w3.org/2003/InkML"

    fun write(strokes: List<Stroke>, annotations: List<Pair<String, String>> = emptyList()): String {
        require(strokes.isNotEmpty()) { "InkML output needs at least one stroke" }
        val withPressure = strokes.all { s -> s.points.all { it.pressure != null } }
        val origin = strokes.minOf { it.startTimeMillis }

        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<ink xmlns=\"").append(NAMESPACE).append("\">\n")
            append("  <traceFormat>\n")
            append("    <channel name=\"X\" type=\"decimal\"/>\n")
            append("    <channel name=\"Y\" type=\"decimal\"/>\n")
            append("    <channel name=\"T\" type=\"integer\" units=\"ms\"/>\n")
            if (withPressure) append("    <channel name=\"F\" type=\"decimal\" min=\"0\" max=\"1\"/>\n")
            append("  </traceFormat>\n")
            for ((type, value) in annotations) {
                append("  <annotation type=\"").append(escape(type)).append("\">")
                append(escape(value)).append("</annotation>\n")
            }
            strokes.forEachIndexed { index, stroke ->
                append("  <trace id=\"").append(index).append("\">")
                stroke.points.joinTo(this, separator = ", ") { p ->
                    val t = stroke.startTimeMillis - origin + p.elapsedMillis
                    buildString {
                        append(format(p.x)).append(' ').append(format(p.y)).append(' ').append(t)
                        if (withPressure) append(' ').append(format(p.pressure!!))
                    }
                }
                append("</trace>\n")
            }
            append("</ink>\n")
        }
    }

    private fun format(value: Float): String = String.format(Locale.ROOT, "%.2f", value)

    internal fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}
